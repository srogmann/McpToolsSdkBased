package org.rogmann.mcp2sdk.pdfedit;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code pdfedit.render()}: a page of a PDF as a bitmap, with PDFBox' own renderer.
 * <p>
 * This is the module's only <em>reading</em> function that produces pixels, and it is deliberately
 * here and not in the {@code pdf} module: that module is a reader without a device (its CTM maps
 * user space into page space, never into a raster), and rendering is the one job the specification
 * solves with a device. {@code docs/js/pdf.md} §4 names PDFBox as "the only dependency that solves
 * this properly (font handling, CMaps, rendering)" - the reader keeps its own parser, this call
 * borrows PDFBox' rasterizer.
 * </p>
 *
 * <h2>Why it exists</h2>
 *
 * A caller that writes a PDF with {@code pdftext} or {@code pdfedit} can check it mechanically
 * ({@code verify}, {@code pdf.placement}) but cannot <em>look</em> at it. {@code image.preview} and
 * {@code image.describe} need a bitmap, and this is where it comes from - so the loop closes:
 * write, render, look, correct.
 *
 * <h2>Coordinates of a region</h2>
 *
 * A {@code region} is given in <b>points of the page as it is displayed</b> (origin top-left, y
 * pointing down, page rotation applied), which is exactly the space {@code pdf.placement()}
 * reports its items in - so a caller can take the box of a path from the reader and ask the
 * renderer for that area without converting anything. The unit is points, never pixels: the same
 * region means the same picture area at every {@code dpi}.
 * <p>
 * The region is cut out of the page rendered at the requested {@code dpi}. That keeps the
 * arithmetic trivial (a scale of {@code dpi/72}, no rotation matrix) at the price of memory for
 * the whole page - which is why the pixel limit is checked against the page, not the region. A
 * very large sheet at a high {@code dpi} is therefore refused with the numbers in the message
 * instead of half-rendering.
 * </p>
 */
final class PdfEditRender {

    private static final Logger LOGGER = LoggerFactory.getLogger(PdfEditRender.class);

    /** Points per inch - the PDF unit. */
    private static final double POINTS_PER_INCH = 72.0;

    /** The resolution used when the caller does not name one. */
    static final double DEFAULT_DPI = 150;

    /** The formats {@code render} can write. */
    static final List<String> FORMATS = List.of("png", "jpeg");

    /** The compression quality of a JPEG result (1.0 = best). */
    private static final double DEFAULT_QUALITY = 0.85;

    /**
     * Above this many bytes the answer adds a note: the {@code image} module's default input limit
     * is 32 MiB ({@code mcp.js.image.maxInputBytes}), so a larger PNG cannot be opened with the
     * defaults. A note, not an error - the file itself is fine.
     */
    private static final long IMAGE_INPUT_HINT_BYTES = 32L * 1024 * 1024;

    /** The option names {@code render} accepts. */
    static final Set<String> NAMES = Set.of("page", "dpi", "region", "format", "quality");

    private PdfEditRender() {
        // Utility class
    }

    /**
     * Renders one page of a document.
     * @param displayName the name used in messages (may be {@code null})
     * @param data the PDF bytes
     * @param values the options as the caller passed them (may be {@code null})
     * @return {@code {data, page, pages, dpi, format, width, height, byteLength, pageSize, rotate,
     *         region, notes}}
     */
    static Map<String, Object> run(String displayName, byte[] data, Map<String, Object> values) {
        String name = (displayName == null || displayName.isBlank()) ? "the document" : displayName;
        Options options = Options.parse(values);
        PDDocument document = load(name, data);
        try {
            int pages = document.getNumberOfPages();
            if (pages == 0) {
                throw new PdfEditException("empty", name + " has no pages",
                        "a document without pages has nothing to render - pdf.pages() shows what"
                                + " the reader sees");
            }
            if (options.page() > pages) {
                throw new PdfEditException("out-of-range", "page " + options.page() + " is asked"
                        + " for, but " + name + " has " + pages + " page(s)",
                        "pages are 1-based, as pdf.pages() prints them");
            }
            PDPage page = document.getPage(options.page() - 1);
            double[] size = displayedSize(page);
            long pixels = pixelCount(size, options.dpi());
            if (pixels > PdfEditLimits.MAX_RENDER_PIXELS) {
                throw new PdfEditException("limit", "page " + options.page() + " would be "
                        + Math.round(size[0] * options.dpi() / POINTS_PER_INCH) + " x "
                        + Math.round(size[1] * options.dpi() / POINTS_PER_INCH) + " pixels at "
                        + formatNumber(options.dpi()) + " dpi (" + pixels + " pixels), the limit is "
                        + PdfEditLimits.MAX_RENDER_PIXELS,
                        "use a lower dpi (150 is enough to see a page, 300 to read small print),"
                                + " or render a region - but note that the whole page is rendered"
                                + " either way");
            }
            List<String> notes = new ArrayList<>();
            BufferedImage image = render(document, page, options, notes);
            image = cutOut(image, options, size, notes);
            byte[] bytes = encode(image, options);
            return answer(image, bytes, options, pages, size, page.getRotation(), notes);
        } finally {
            closeQuietly(document);
        }
    }

    /**
     * Renders the page at the requested resolution.
     * @param document the document
     * @param page the page
     * @param options the options
     * @param notes the notes of the call (receives what the renderer decided)
     * @return the raster
     */
    private static BufferedImage render(PDDocument document, PDPage page, Options options,
                                        List<String> notes) {
        PDFRenderer renderer = new PDFRenderer(document);
        // Subsampling is off on purpose: a scaled-down embedded image is cheaper, but this answer
        // is what a caller judges ("is the label on the line?") and not a thumbnail.
        renderer.setSubsamplingAllowed(false);
        try {
            return renderer.renderImageWithDPI(options.page() - 1, (float) options.dpi(),
                    ImageType.RGB);
        } catch (IOException e) {
            throw new PdfEditException("broken", "page " + options.page() + " cannot be rendered: "
                    + e.getMessage(),
                    "try pdf.placement() on the page - it shows what the reader can read; a page"
                            + " with a defect may still render in another viewer", e);
        } catch (RuntimeException e) {
            LOGGER.warn("pdfedit.render: page {} of the document failed", options.page(), e);
            throw new PdfEditException("broken", "page " + options.page() + " cannot be rendered: "
                    + e.getMessage(),
                    "the page (or a font, or an image it uses) is beyond what PDFBox can draw -"
                            + " pdf.placement() shows what the reader found", e);
        }
    }

    /**
     * Cuts the requested region out of the rendered page.
     * @param image the rendered page
     * @param options the options
     * @param size the displayed page size in points
     * @param notes the notes of the call
     * @return the region (or the page itself when no region was asked for)
     */
    private static BufferedImage cutOut(BufferedImage image, Options options, double[] size,
                                        List<String> notes) {
        double[] region = options.region();
        if (region == null) {
            return image;
        }
        double scale = options.dpi() / POINTS_PER_INCH;
        if (region[0] < -0.01 || region[1] < -0.01
                || region[0] + region[2] > size[0] + 0.5 || region[1] + region[3] > size[1] + 0.5) {
            throw new PdfEditException("out-of-range", "the region " + describeRegion(region)
                    + " does not lie on the page, which is "
                    + formatNumber(size[0]) + " x " + formatNumber(size[1]) + " pt",
                    "a region is [x, y, width, height] in points of the displayed page (origin"
                            + " top-left, rotation applied) - the space pdf.placement() reports"
                            + " its items in");
        }
        int x = (int) Math.round(region[0] * scale);
        int y = (int) Math.round(region[1] * scale);
        int width = (int) Math.round(region[2] * scale);
        int height = (int) Math.round(region[3] * scale);
        // A region outside the raster cannot happen after the check above; a rounding edge can.
        x = Math.max(0, Math.min(x, image.getWidth() - 1));
        y = Math.max(0, Math.min(y, image.getHeight() - 1));
        width = Math.max(1, Math.min(width, image.getWidth() - x));
        height = Math.max(1, Math.min(height, image.getHeight() - y));
        if (width < 1 || height < 1) {
            throw new PdfEditException("out-of-range", "the region " + describeRegion(region)
                    + " is smaller than one pixel at " + formatNumber(options.dpi()) + " dpi",
                    "raise the dpi or make the region larger");
        }
        if (x + width > image.getWidth() || y + height > image.getHeight()) {
            notes.add("the region was cut back to the page edge");
        }
        // A copy, not a view: the sub-image shares the parent's raster, and the parent is the whole
        // page (holding it would keep the full page in memory while the answer is encoded).
        BufferedImage part = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = part.createGraphics();
        try {
            graphics.drawImage(image.getSubimage(x, y, width, height), 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return part;
    }

    /**
     * Encodes the raster.
     * @param image the raster
     * @param options the options
     * @return the encoded bytes
     */
    private static byte[] encode(BufferedImage image, Options options) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            if ("jpeg".equals(options.format())) {
                writeJpeg(image, options.quality(), buffer);
            } else if (!ImageIO.write(image, "png", buffer)) {
                throw new PdfEditException("unsupported", "no PNG writer is available in this JRE",
                        "this is a fault of the runtime - use format:'jpeg' or report it");
            }
        } catch (IOException e) {
            throw new PdfEditException("broken", "the rendered page cannot be encoded as "
                    + options.format() + ": " + e.getMessage(),
                    "this is a fault of the encoder, not of the document", e);
        }
        byte[] bytes = buffer.toByteArray();
        if (bytes.length > PdfEditLimits.MAX_OUTPUT_BYTES) {
            throw new PdfEditException("limit", "the result has " + bytes.length + " bytes, the"
                    + " limit is " + PdfEditLimits.MAX_OUTPUT_BYTES,
                    "use a lower dpi, format:'jpeg' or a region");
        }
        return bytes;
    }

    /**
     * Writes the raster as a JPEG with an explicit quality.
     * @param image the raster
     * @param quality the quality (0..1)
     * @param buffer the target
     * @throws IOException if the encoder fails
     */
    private static void writeJpeg(BufferedImage image, double quality, ByteArrayOutputStream buffer)
            throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality((float) quality);
        try (ImageOutputStream out = ImageIO.createImageOutputStream(buffer)) {
            if (out == null) {
                throw new IOException("no JPEG output stream is available");
            }
            writer.setOutput(out);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    /**
     * Builds the answer of the call.
     * @param image the result raster
     * @param bytes the encoded result
     * @param options the options
     * @param pages the page count of the document
     * @param size the displayed page size in points
     * @param rotation the page rotation as the file declares it
     * @param notes the notes of the call
     * @return the answer map
     */
    private static Map<String, Object> answer(BufferedImage image, byte[] bytes, Options options,
                                              int pages, double[] size, int rotation,
                                              List<String> notes) {
        if (pages > 1) {
            notes.add("the document has " + pages + " page(s); rendered page " + options.page()
                    + " - one call per page");
        }
        if (rotation != 0) {
            notes.add("the page declares /Rotate " + rotation + " - it is rendered as it is"
                    + " displayed, and a region refers to that displayed page");
        }
        if (bytes.length > IMAGE_INPUT_HINT_BYTES) {
            notes.add("the result has " + Math.round(bytes.length / (1024.0 * 1024.0)) + " MB; the"
                    + " image module opens at most 32 MiB by default"
                    + " (mcp.js.image.maxInputBytes) - use a lower dpi or format:'jpeg'");
        }
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("data", bytes);
        answer.put("page", (long) options.page());
        answer.put("pages", (long) pages);
        answer.put("dpi", (long) Math.round(options.dpi()));
        answer.put("format", options.format());
        answer.put("width", (long) image.getWidth());
        answer.put("height", (long) image.getHeight());
        answer.put("byteLength", (long) bytes.length);
        Map<String, Object> pageSize = new LinkedHashMap<>();
        pageSize.put("width", round(size[0]));
        pageSize.put("height", round(size[1]));
        answer.put("pageSize", pageSize);
        answer.put("rotate", (long) rotation);
        answer.put("region", options.region() == null ? null : regionList(options.region()));
        answer.put("notes", notes);
        return answer;
    }

    /**
     * The size of a page as it is displayed: the crop box with the rotation applied.
     * @param page the page
     * @return {@code [width, height]} in points
     */
    private static double[] displayedSize(PDPage page) {
        PDRectangle box = page.getCropBox();
        if (box == null) {
            box = page.getMediaBox();
        }
        if (box == null || box.getWidth() <= 0 || box.getHeight() <= 0) {
            throw new PdfEditException("broken", "the page declares no usable box",
                    "pdf.pages() shows the boxes the reader computed for the page");
        }
        int rotation = ((page.getRotation() % 360) + 360) % 360;
        boolean turned = rotation == 90 || rotation == 270;
        return turned ? new double[] {box.getHeight(), box.getWidth()}
                : new double[] {box.getWidth(), box.getHeight()};
    }

    /**
     * The number of pixels a page needs at a resolution.
     * @param size the displayed page size in points
     * @param dpi the resolution
     * @return the pixel count
     */
    private static long pixelCount(double[] size, double dpi) {
        long width = (long) Math.ceil(size[0] * dpi / POINTS_PER_INCH);
        long height = (long) Math.ceil(size[1] * dpi / POINTS_PER_INCH);
        return width * height;
    }

    /**
     * Loads the document, with messages in the terms of this module.
     * @param name the display name
     * @param data the bytes
     * @return the document
     */
    private static PDDocument load(String name, byte[] data) {
        if (data == null || data.length == 0) {
            throw new PdfEditException("usage", "the document is empty",
                    "pass a project path or a Uint8Array of PDF bytes");
        }
        if (data.length > PdfEditLimits.MAX_INPUT_BYTES) {
            throw new PdfEditException("limit", "the document has " + data.length + " bytes, the"
                    + " limit is " + PdfEditLimits.MAX_INPUT_BYTES,
                    "this is the input limit the reading module enforces as well");
        }
        try {
            return Loader.loadPDF(data);
        } catch (InvalidPasswordException e) {
            throw new PdfEditException("encrypted", name + " is encrypted",
                    "rendering needs the file to open without a password; removing the"
                            + " protection is a separate step (E4)", e);
        } catch (IOException e) {
            throw new PdfEditException("broken", name + " cannot be read: " + e.getMessage(),
                    "try pdf.structure() to see what the reader sees", e);
        } catch (RuntimeException e) {
            throw new PdfEditException("broken", name + " cannot be read: " + e.getMessage(),
                    "the file is probably not a PDF - pdf.structure() shows what the reader sees",
                    e);
        }
    }

    /**
     * Closes a document without failing the call.
     * @param document the document (may be {@code null})
     */
    private static void closeQuietly(PDDocument document) {
        if (document == null) {
            return;
        }
        try {
            document.close();
        } catch (IOException e) {
            LOGGER.debug("Closing the rendered document failed", e);
        }
    }

    /**
     * Renders a number the way the answer prints it.
     * @param value the value
     * @return the text
     */
    private static String formatNumber(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001) {
            return Long.toString(Math.round(value));
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /**
     * Rounds to two decimals.
     * @param value the value
     * @return the rounded value
     */
    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /**
     * The region as the answer echoes it (floats, not longs).
     * @param region the region in points
     * @return the list
     */
    private static List<Object> regionList(double[] region) {
        List<Object> list = new ArrayList<>(4);
        for (double value : region) {
            list.add(round(value));
        }
        return list;
    }

    /**
     * A region as a reader of a message wants it.
     * @param region the region in points
     * @return the text
     */
    private static String describeRegion(double[] region) {
        return "[" + formatNumber(region[0]) + ", " + formatNumber(region[1]) + ", "
                + formatNumber(region[2]) + ", " + formatNumber(region[3]) + "]";
    }

    /**
     * The parsed options of one {@code render} call.
     * @param page the 1-based page number
     * @param dpi the resolution in dots per inch
     * @param region the region in points of the displayed page, or {@code null}
     * @param format {@code png} or {@code jpeg}
     * @param quality the JPEG quality
     */
    record Options(int page, double dpi, double[] region, String format, double quality) {

        /**
         * Parses the options strictly: an unknown name is an error that lists the supported ones.
         * @param values the options map (may be {@code null})
         * @return the options
         */
        static Options parse(Map<String, Object> values) {
            Map<String, Object> map = (values == null) ? Map.of() : values;
            Set<String> unknown = new TreeSet<>(map.keySet());
            unknown.removeAll(NAMES);
            if (!unknown.isEmpty()) {
                throw PdfEditException.usage("unknown option(s) " + unknown,
                        "supported: " + new TreeSet<>(NAMES)
                                + " - e.g. render(page, {page:1, dpi:200})");
            }
            double pageValue = number(map.get("page"), "page", 1);
            int page = (int) pageValue;
            if (page != pageValue) {
                throw PdfEditException.usage("page is " + PdfEditOp.describe(map.get("page")),
                        "page is a whole number (1-based, as pdf.pages() prints them)");
            }
            if (page < 1) {
                throw PdfEditException.usage("page is " + page, "pages are 1-based");
            }
            double dpi = number(map.get("dpi"), "dpi", DEFAULT_DPI);
            if (dpi < PdfEditLimits.MIN_RENDER_DPI || dpi > PdfEditLimits.MAX_RENDER_DPI) {
                throw PdfEditException.usage("dpi is " + formatNumber(dpi),
                        "allowed range is " + formatNumber(PdfEditLimits.MIN_RENDER_DPI) + ".."
                                + formatNumber(PdfEditLimits.MAX_RENDER_DPI)
                                + " (150 sees a page, 300 reads small print)");
            }
            String format = text(map.get("format"), "format", "png").toLowerCase(Locale.ROOT);
            if (!FORMATS.contains(format)) {
                throw PdfEditException.usage("format is '" + format + "'",
                        "supported: " + FORMATS + " (png is lossless, jpeg is smaller)");
            }
            double quality = number(map.get("quality"), "quality", DEFAULT_QUALITY);
            if (quality <= 0 || quality > 1) {
                throw PdfEditException.usage("quality is " + quality,
                        "quality is 0 < q <= 1 and belongs to jpeg (default "
                                + DEFAULT_QUALITY + ")");
            }
            if (map.containsKey("quality") && !"jpeg".equals(format)) {
                throw PdfEditException.usage("quality is given for format '" + format + "'",
                        "quality is a JPEG parameter - use format:'jpeg' or drop it");
            }
            return new Options(page, dpi, region(map.get("region")), format, quality);
        }

        /**
         * Reads the region option.
         * @param value the value ({@code null} = not given)
         * @return {@code [x, y, width, height]} in points, or {@code null}
         */
        private static double[] region(Object value) {
            if (value == null) {
                return null;
            }
            if (!(value instanceof List<?> list) || list.size() != 4) {
                throw PdfEditException.usage("region is " + PdfEditOp.describe(value),
                        "region is [x, y, width, height] in points of the displayed page"
                                + " (origin top-left, rotation applied) - the space"
                                + " pdf.placement() reports its items in");
            }
            double[] region = new double[4];
            String[] names = {"x", "y", "width", "height"};
            for (int i = 0; i < 4; i++) {
                Object element = list.get(i);
                if (!(element instanceof Number number)) {
                    throw PdfEditException.usage("region[" + i + "] (" + names[i] + ") is "
                            + PdfEditOp.describe(element),
                            "region is [x, y, width, height] in points, four numbers");
                }
                region[i] = number.doubleValue();
            }
            if (region[2] <= 0 || region[3] <= 0) {
                throw PdfEditException.usage("region " + describeRegion(region)
                        + " has no area", "width and height must be positive");
            }
            return region;
        }

        /**
         * Reads a number option.
         * @param value the value ({@code null} = not given)
         * @param what the option name
         * @param fallback the default
         * @return the number
         */
        private static double number(Object value, String what, double fallback) {
            if (value == null) {
                return fallback;
            }
            if (!(value instanceof Number number)) {
                throw PdfEditException.usage(what + " is " + PdfEditOp.describe(value),
                        what + " is a number");
            }
            double result = number.doubleValue();
            if (Double.isNaN(result) || Double.isInfinite(result)) {
                throw PdfEditException.usage(what + " is " + result, what + " is a finite number");
            }
            return result;
        }

        /**
         * Reads a text option.
         * @param value the value ({@code null} = not given)
         * @param what the option name
         * @param fallback the default
         * @return the text
         */
        private static String text(Object value, String what, String fallback) {
            if (value == null) {
                return fallback;
            }
            if (!(value instanceof String text) || text.isBlank()) {
                throw PdfEditException.usage(what + " is " + PdfEditOp.describe(value),
                        what + " is a non-empty string");
            }
            return text;
        }
    }
}
