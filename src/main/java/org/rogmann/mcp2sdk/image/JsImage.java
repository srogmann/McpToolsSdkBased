package org.rogmann.mcp2sdk.image;

import org.rogmann.mcp2sdk.js.JsArchive;
import org.rogmann.mcp2sdk.js.JsFileSystem;
import org.rogmann.mcp2sdk.js.JsUserRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.IIOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Core of the {@code image} JS-module: metadata, colour/texture analysis (with a measured
 * "kind" guess), bitmap generation and encoding - plain Java, no Graal imports, no new
 * dependency (JDK {@link ImageIO}/{@link BufferedImage} only).
 * <p>
 * One instance lives per JavaScript tool call (wired by
 * {@code org.rogmann.mcp2sdk.js.JsImageBridge}): open handles belong to the instance and
 * {@link #close()} releases them when the call ends. Nothing survives a call.</p>
 * <p>
 * Plan and documentation: {@code docs/js/image.md}. Phase P1 covers decode/info/create/
 * encode/save, P2 covers {@link #analyze(byte[], String, Map)} with the kind guess tuned on
 * {@code src/test/resources/js/image_math.png} (text), {@code image_rainbow.png} (drawing)
 * and {@code image_birds.jpg} (photo).</p>
 *
 * <h3>Security / limits</h3>
 * <ul>
 *   <li>paths go through {@link JsArchive#entryBytes(String)} (project base, no traversal,
 *       archive display paths {@code a.zip#pic.png} read per level from memory) and are
 *       additionally capped at {@value #MAX_FILE_BYTES} bytes,</li>
 *   <li>decompression-bomb guard: the image dimensions are read from the format header
 *       <em>before</em> the raster is decoded; more than {@value #MAX_PIXELS} pixels are
 *       rejected by every decoding function ({@code open}/{@code analyze}) - {@code info()}
 *       keeps reporting the claimed header dimensions, so the diagnosis "what does this file
 *       claim?" stays possible,</li>
 *   <li>the sum of all open rasters per call is capped at {@value #RASTER_BUDGET_BYTES}
 *       bytes (32-bit ARGB raster + raw file bytes).</li>
 * </ul>
 */
public class JsImage implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(JsImage.class);

    /** Maximum accepted encoded input size (64 MiB). */
    public static final long MAX_FILE_BYTES = 64L * 1024 * 1024;
    /** Maximum decoded pixels per image - open/analyze reject the bomb, info reports the claim. */
    public static final long MAX_PIXELS = 32L * 1024 * 1024;
    /** Maximum side length of a created/opened image. */
    public static final int MAX_SIDE = 32768;
    /** Raster budget per call: sum over open handles of (ARGB raster + raw file bytes). */
    public static final long RASTER_BUDGET_BYTES = 256L * 1024 * 1024;
    /** Default analysis budget in pixels; larger images are sampled by a grid stride. */
    public static final int DEFAULT_ANALYSIS_BUDGET = 4 * 1024 * 1024;
    /** Full-resolution structure window (one quadrant) budget in pixels. */
    private static final int STRUCTURE_TILE_BUDGET = 1024 * 1024;
    /** Maximum region size of one getPixels/putPixels transfer. */
    private static final long MAX_TRANSFER_PIXELS = 16L * 1024 * 1024;
    /** Allowed histogram bin counts. */
    private static final Set<Integer> HISTOGRAM_BINS = Set.of(16, 32, 64, 128, 256);

    /** The per-call open images, handle name -&gt; entry. Insertion-ordered for messages. */
    private final LinkedHashMap<String, OpenedImage> images = new LinkedHashMap<>();
    /** Handle counter ({@code img-1}, {@code img-2}, ...). */
    private int handleCounter;
    /** Lazy font check of the first text() call of the call instance. */
    private Boolean fontsAvailable;

    /** One opened (or created) image: the decoded raster plus its raw bytes and origin. */
    private record OpenedImage(BufferedImage image, byte[] raw, String display, String format,
                               ParsedHeader header, boolean autoOriented) {
    }

    /**
     * Creates the toolbox.
     */
    public JsImage() {
        // per-call state only
    }

    // ========================================================================
    // Public API
    // ========================================================================

    /**
     * Returns the file/container metadata of an image given as raw bytes.
     * @param data raw image bytes (PNG, JPEG, GIF, BMP, WBMP)
     * @param display display path for messages (never an absolute path)
     * @param options options map, see below
     * @return metadata map, see docs/js/image.md §3
     */
    public Map<String, Object> info(byte[] data, String display, Map<String, Object> options) {
        String fn = "info";
        requireKnown(fn, options, "chunks");
        boolean chunks = boolOpt(fn, options, "chunks", false);
        Source source = readSource(data, display);
        return describe(source.header(), source.format(), source.raw(), null, source.display(),
                chunks);
    }

    /**
     * Analyses the colour and texture statistics of an image and guesses its kind
     * (text / screenshot / drawing / photo) from measured features.
     * @param data raw image bytes
     * @param display display path for messages
     * @param options options map: {@code sample} ({@code 1} = every pixel, otherwise the
     *                sampling budget in pixels, default {@value #DEFAULT_ANALYSIS_BUDGET}),
     *                {@code histogram} (bin count 16/32/64/128/256 for the luminance histogram)
     * @return analysis map, see docs/js/image.md §3
     */
    public Map<String, Object> analyze(byte[] data, String display, Map<String, Object> options) {
        String fn = "analyze";
        requireKnown(fn, options, "sample", "histogram", "preview");
        int budget = intOpt(fn, options, "sample", DEFAULT_ANALYSIS_BUDGET, 1, Integer.MAX_VALUE);
        int histogramBins = intOpt(fn, options, "histogram", 0, 0, 256);
        if (histogramBins != 0 && !HISTOGRAM_BINS.contains(histogramBins)) {
            throw new JsUserRuntimeException(String.format(
                    "image.%s: histogram must be one of %s bins (got %d)",
                    fn, HISTOGRAM_BINS, histogramBins));
        }
        BufferedImage img = decodeCapped(data, display);

        int n = img.getWidth() * img.getHeight();
        // {sample: 1} means "every pixel" (full resolution up to the decode cap)
        int stride = budget == 1 ? 1 : (int) Math.ceil(Math.sqrt((double) n / budget));
        GlobalStats global = collectGlobal(img, stride, histogramBins);
        StructureStats structure = collectStructure(img);
        Map<String, Object> guess = guessKind(global, structure);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", display);
        result.put("width", img.getWidth());
        result.put("height", img.getHeight());
        result.put("megapixels", round(n / 1_000_000.0, 2));
        if (stride > 1) {
            Map<String, Object> sampled = new LinkedHashMap<>();
            sampled.put("stride", stride);
            sampled.put("pixels", global.pixelCount);
            result.put("sampled", sampled);
        } else {
            result.put("sampled", null);
        }
        result.put("uniqueColors", global.uniqueColors);
        result.put("quantized", global.quantized());
        result.put("dominant", global.dominant());
        result.put("luminance", global.luminance());
        result.put("structure", structure.map());
        result.put("guess", guess);
        if (boolOpt(fn, options, "preview", false)) {
            // raw grid (no markdown fence) - the caller decides how to embed it
            result.put("preview", renderPreview(img, 64, "ascii", 2.2, false));
        }
        return result;
    }

    /**
     * Opens an image (or creates state for one) and returns a handle for the other functions.
     * @param data raw image bytes
     * @param display display path for messages
     * @param options options map: {@code autoOrient} (default {@code true}: apply the EXIF
     *                orientation of a JPEG so the raster shows what a viewer shows)
     * @return the handle, e.g. {@code "img-1"}
     */
    public String open(byte[] data, String display, Map<String, Object> options) {
        String fn = "open";
        requireKnown(fn, options, "autoOrient");
        boolean autoOrient = boolOpt(fn, options, "autoOrient", true);
        Source source = readSource(data, display);
        BufferedImage img = decodeCapped(data, display);
        boolean oriented = false;
        Integer orientation = source.header().orientation;
        if (autoOrient && orientation != null && orientation != 1) {
            BufferedImage turned = applyOrientation(img, orientation);
            oriented = turned != img;
            img = turned;
        }
        registerRaster(img, data, "open");
        images.put(nextHandle(), new OpenedImage(img, data, display, source.format(),
                source.header(), oriented));
        return handleName(handleCounter);
    }

    /**
     * Returns the metadata of an open image (the info structure plus the handle state).
     * @param handle handle from {@link #open(byte[], String, Map)} or {@link #create(Map)}
     * @return metadata map
     */
    public Map<String, Object> meta(String handle) {
        OpenedImage opened = requireHandle(handle, "meta");
        ParsedHeader header = opened.header() != null ? opened.header() : new ParsedHeader();
        Map<String, Object> result = describe(header, opened.format(), opened.raw(),
                opened.image(), opened.display(), false);
        result.put("handle", handle);
        result.put("autoOriented", opened.autoOriented());
        return result;
    }

    /**
     * Creates a new bitmap and returns its handle.
     * @param options options map: {@code width}, {@code height} (pixels, required),
     *                {@code background} colour (default {@code 'transparent'})
     * @return the handle, e.g. {@code "img-1"}
     */
    public String create(Map<String, Object> options) {
        String fn = "create";
        int width = intOpt(fn, options, "width", 0, 1, MAX_SIDE);
        int height = intOpt(fn, options, "height", 0, 1, MAX_SIDE);
        requireKnown(fn, options, "width", "height", "background");
        if (width == 0 || height == 0) {
            throw new JsUserRuntimeException(
                    "image.create: width and height are required (e.g. {width: 640, height: 400})");
        }
        if ((long) width * height > MAX_PIXELS) {
            throw new JsUserRuntimeException(String.format(
                    "image.create: %dx%d = %d pixels exceeds the limit of %d pixels",
                    width, height, (long) width * height, MAX_PIXELS));
        }
        String colorSpec = stringOpt(fn, options, "background", "transparent");
        int background = parseColor(fn, "background", colorSpec);
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] fill = new int[width * height];
        Arrays.fill(fill, background);
        img.setRGB(0, 0, width, height, fill, 0, width);
        registerRaster(img, null, "create");
        ParsedHeader header = new ParsedHeader();
        header.bitDepth = 8;
        header.colorType = "truecolor+alpha";
        header.hasAlpha = true;
        header.frames = 1;
        images.put(nextHandle(), new OpenedImage(img, null, "<created>", "PNG", header, false));
        return handleName(handleCounter);
    }

    /**
     * Encodes an open image into image bytes.
     * @param handle handle from {@link #open(byte[], String, Map)} or {@link #create(Map)}
     * @param options options map: {@code format} ({@code 'png'} (default), {@code 'jpeg'},
     *                {@code 'gif'}, {@code 'bmp'}), {@code quality} (0..1, JPEG only),
     *                {@code background} (alpha flattening colour for JPEG/BMP, default white)
     * @return encoded bytes (PNG via the standard encoder)
     */
    public byte[] encode(String handle, Map<String, Object> options) {
        String fn = "encode";
        requireKnown(fn, options, "format", "quality", "background");
        OpenedImage opened = requireHandle(handle, fn);
        String format = stringOpt(fn, options, "format", "png");
        double quality = doubleOpt(fn, options, "quality", -1.0, 0.0, 1.0);
        String backgroundSpec = stringOpt(fn, options, "background", "#ffffff");
        BufferedImage img = opened.image();
        switch (format) {
            case "jpeg", "jpg", "bmp" -> {
                int background = parseColor(fn, "background", backgroundSpec);
                img = flatten(img, background);
            }
            case "png", "gif" -> {
                // alpha keeps its own channel
            }
            default -> throw new JsUserRuntimeException(String.format(
                    "image.encode: format must be one of 'png', 'jpeg', 'gif', 'bmp' (got '%s')",
                    format));
        }
        return writeImage(img, format, quality, opened.display());
    }

    /**
     * Encodes an open image and writes it into the project (same path rules as {@code fs}).
     * @param handle handle from {@link #open(byte[], String, Map)} or {@link #create(Map)}
     * @param path destination path relative to the project base directory
     * @param options options map, same as {@link #encode(String, Map)}
     * @return result map {@code {path, format, width, height, bytes}}
     */
    public Map<String, Object> save(String handle, String path, Map<String, Object> options) {
        String fn = "save";
        requireKnown(fn, options, "format", "quality", "background");
        OpenedImage opened = requireHandle(handle, fn);
        byte[] bytes = encode(handle, options);
        String format = stringOpt(fn, options, "format", "png");
        if ("jpg".equals(format)) {
            format = "jpeg";
        }
        JsFileSystem.writeBytes(path, bytes);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", path);
        result.put("format", format);
        result.put("width", opened.image().getWidth());
        result.put("height", opened.image().getHeight());
        result.put("bytes", bytes.length);
        return result;
    }

    /**
     * @return the help text of the module (usage examples)
     */
    public String help() {
        return """
                image.help() - image analysis, bitmap generation and annotation (no external library)

                Read-only analysis (path, archive display path 'a.zip#pic.png', or raw bytes):
                  image.info("img/shot.png")              // format, dimensions, EXIF orientation, DPI, ...
                  image.info("img/shot.png", {chunks: true})  // PNG chunk / JPEG marker list
                  image.analyze("img/shot.png")           // colours, entropy, structure + kind guess
                  image.analyze("img/shot.png", {histogram: 32, preview: true})
                  image.preview("img/shot.png")           // Grobansicht, markdown code block
                  image.preview("img/shot.png", {width: 100, charset: 'blocks'})
                  // guess.kind: 'text' | 'screenshot' | 'drawing' | 'photo' - with scores and reasons;
                  // the numbers are measured, the LLM keeps the final word

                Bitmap generation and annotation (handles are per-call, released automatically):
                  var img = image.open("img/shot.png")    // {autoOrient: false} keeps raw pixels
                  var img = image.create({width: 640, height: 400, background: '#ffffff'})

                  image.rect(img, 10, 10, 300, 200, {color: '#0033cc', fill: true})
                  image.line(img, 10, 10, 310, 210, {color: 'red', width: 3, arrow: 'end'})
                  image.ellipse(img, 40, 40, 60, 60, {color: 'green'})
                  image.point(img, 100, 100, {color: 'black', size: 4})
                  image.text(img, 20, 40, "Fehler hier", {color: '#b30000', size: 18,
                                                          bold: true, background: '#ffffff'})
                  // options per family: color, width (outline), fill, opacity, antialias (default on);
                  // text: size, font ('sans'|'serif'|'mono'), bold, italic, background, align

                  image.setPixel(img, 5, 5, '#ff0000')    // single pixel
                  var region = image.getPixels(img, {x: 0, y: 0, width: 16, height: 16})
                  // region.data: Uint8Array, RGBA row-major (canvas ImageData semantics)
                  image.putPixels(img, 0, 0, 16, 16, region.data)
                  var small = image.crop(img, 10, 10, 200, 100)      // -> new handle
                  var half  = image.resize(img, {scale: 0.5})        // -> new handle
                  var thumb = image.resize(img, {width: 800, hint: 'smooth'})

                Output:
                  image.encode(img, {format: 'png'})      // -> Uint8Array ('png'|'jpeg'|'gif'|'bmp')
                  fs.writeBytes("out/annotated.png", image.encode(img))
                  image.save(img, "out/annotated.png")    // convenience, same path rules as fs
                  image.toDataUrl(img)                    // data URL for LLM calls (JPEG q85 by
                                                          // default, PNG for alpha, max side 1568)
                  // pass the format from your analysis: kind 'text'/'screenshot'/'drawing'
                  // -> {format: 'png'} (flat colours compress much better); 'photo' -> jpeg
                  // (measured: formula 49 KB png vs 63 KB jpeg; bird photo 40 KB jpeg vs 285 KB png)
                  var answer = image.vision(img, "What does this screenshot show?",
                                            {format: "png"})
                  // -> {text, model, usage, image}; one-shot vision request to the CURRENT
                  //    model (webui.hasVision must be true, webui.model.url must be set;
                  //    see docs/js/image.md §7); the image is scaled to max side 1568 and
                  //    sent as base64 data URL.
                  image.fonts()                           // installed font families (debug aid)

                Limits: files up to 64 MiB, images up to 33554432 pixels (decompression-bomb guard;
                info() reports the claimed dimensions without decoding), analysis samples large
                images down to 4194304 pixels ({sample: 1} forces full). See docs/js/image.md.
                """;
    }

    /**
     * Releases all open images (called after every JavaScript tool call).
     */
    @Override
    public void close() {
        images.clear();
    }

    // ========================================================================
    // Source reading and decoding
    // ========================================================================

    /** A successfully sniffed source: format name, parsed header and raw bytes. */
    private record Source(ParsedHeader header, byte[] raw, String display, String format) {
    }

    /**
     * Reads the bytes behind a path or archive display path
     * (project base, no traversal, per-level in-memory archive access).
     * @param path path relative to the project base, or an archive display path
     * @return raw bytes
     */
    public byte[] readSourceBytes(String path) {
        byte[] data = JsArchive.entryBytes(path);
        if (data.length > MAX_FILE_BYTES) {
            throw new JsUserRuntimeException(String.format(
                    "image: '%s' has %d bytes, exceeding the image limit of %d bytes",
                    path, data.length, MAX_FILE_BYTES));
        }
        return data;
    }

    /**
     * Wraps raw bytes plus what the format header says (format name, EXIF orientation, DPI).
     * @param data raw bytes (already size-checked)
     * @param display display path for messages
     * @return the source
     */
    private Source readSource(byte[] data, String display) {
        String format = sniffFormat(data, display);
        ParsedHeader header = switch (format) {
            case "PNG" -> parsePng(data, display);
            case "JPEG" -> parseJpeg(data, display);
            case "GIF" -> parseGif(data, display);
            case "BMP" -> parseBmp(data, display);
            default -> new ParsedHeader();
        };
        return new Source(header, data, display, format);
    }

    /** What a format-specific header walk reports (all fields optional). */
    private static final class ParsedHeader {
        Integer orientation;
        Integer[] dpi;
        Integer bitDepth;
        String colorType;
        Boolean hasAlpha;
        Integer frames;
        Boolean progressive;
        List<String> markers;
    }

    /**
     * Detects the image format by magic bytes (message quality) and confirms it with ImageIO's
     * reader registry (authority).
     * @param data raw bytes
     * @param display display path for messages
     * @return upper-case format name, e.g. {@code "PNG"}
     */
    private static String sniffFormat(byte[] data, String display) {
        ByteArrayInputStream bin = new ByteArrayInputStream(data);
        ImageInputStream iis = new MemoryCacheImageInputStream(bin);
        Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
        if (!readers.hasNext()) {
            throw new JsUserRuntimeException(String.format(
                    "image: '%s' is not a recognisable image (PNG, JPEG, GIF, BMP, WBMP)"
                            + " - magic %s", display, magicOf(data)));
        }
        ImageReader reader = readers.next();
        try {
            return safeFormatName(reader);
        } finally {
            reader.dispose();
        }
    }

    /**
     * Format name of a reader, with a fallback when the name cannot be read.
     * @param reader a reader from the ImageIO registry
     * @return upper-case format name, e.g. {@code "PNG"}
     */
    private static String safeFormatName(ImageReader reader) {
        try {
            return reader.getFormatName().toUpperCase();
        } catch (IOException e) {
            LOG.warn("image: format name unavailable, using the reader class name: {}",
                    e.getMessage());
            return reader.getClass().getSimpleName().toUpperCase();
        }
    }

    /** First four bytes as hex, for error messages. */
    private static String magicOf(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(4, data.length); i++) {
            sb.append(i > 0 ? ' ' : "").append(String.format("%02x", data[i] & 0xFF));
        }
        return sb.toString();
    }

    /**
     * Decodes the raster after the header-level pixel guard.
     * @param data raw bytes
     * @param display display path for messages
     * @return the decoded image
     */
    private static BufferedImage decodeCapped(byte[] data, String display) {
        ByteArrayInputStream bin = new ByteArrayInputStream(data);
        ImageInputStream iis = new MemoryCacheImageInputStream(bin);
        Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
        if (!readers.hasNext()) {
            throw new JsUserRuntimeException(String.format(
                    "image: '%s' is not a recognisable image (PNG, JPEG, GIF, BMP, WBMP)"
                            + " - magic %s", display, magicOf(data)));
        }
        ImageReader reader = readers.next();
        try {
            reader.setInput(iis, true, true);
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            long pixels = (long) width * height;
            if (pixels > MAX_PIXELS) {
                throw new JsUserRuntimeException(String.format(
                        "image: '%s' claims %dx%d = %d pixels, exceeding the limit of %d pixels"
                                + " (decompression-bomb guard)", display, width, height, pixels,
                        MAX_PIXELS));
            }
            return reader.read(0);
        } catch (IIOException e) {
            throw new JsUserRuntimeException("image: decode of '" + display + "' failed ("
                    + safeFormatName(reader) + "): " + e.getMessage() + " (file truncated or"
                    + " corrupt?)", e);
        } catch (IOException e) {
            throw new JsUserRuntimeException("image: decode of '" + display + "' failed: "
                    + e.getMessage(), e);
        } finally {
            reader.dispose();
        }
    }

    // ========================================================================
    // Format-specific header walks
    // ========================================================================

    /**
     * Walks the PNG chunks: IHDR (dimensions, bit depth, colour type, interlace), pHYs (DPI),
     * optional chunk-name list.
     * @param data raw bytes
     * @param display display path for messages
     * @return parsed header
     */
    private static ParsedHeader parsePng(byte[] data, String display) {
        ParsedHeader header = new ParsedHeader();
        if (data.length < 33) {
            return header;
        }
        int width = u32be(data, 16);
        int height = u32be(data, 20);
        int bitDepth = data[24] & 0xFF;
        int colorType = data[25] & 0xFF;
        header.bitDepth = bitDepth;
        header.colorType = switch (colorType) {
            case 0 -> "grayscale";
            case 2 -> "truecolor";
            case 3 -> "palette";
            case 4 -> "grayscale+alpha";
            case 6 -> "truecolor+alpha";
            default -> "unknown(" + colorType + ")";
        };
        header.hasAlpha = colorType == 4 || colorType == 6;
        header.frames = 1;
        header.markers = new ArrayList<>();
        header.markers.add("IHDR");
        int off = 8;
        while (off + 12 <= data.length) {
            long len = u32be(data, off) & 0xFFFFFFFFL;
            if (off + 12 + len > data.length) {
                LOG.warn("image: PNG of '{}' ends inside chunk at offset {}", display, off);
                header.markers.add("TRUNCATED");
                return header;
            }
            String type = new String(data, off + 4, 4, StandardCharsets.US_ASCII);
            if (!"IHDR".equals(type)) {
                header.markers.add(type);
            }
            if ("pHYs".equals(type) && len >= 9 && header.dpi == null) {
                long ppuX = u32be(data, off + 8) & 0xFFFFFFFFL;
                long ppuY = u32be(data, off + 12) & 0xFFFFFFFFL;
                int unit = data[off + 16] & 0xFF;
                if (unit == 1 && ppuX > 0 && ppuY > 0) {
                    header.dpi = new Integer[] {
                            (int) Math.round(ppuX * 0.0254), (int) Math.round(ppuY * 0.0254)};
                }
            }
            if ("IEND".equals(type)) {
                break;
            }
            off += 12 + (int) len;
        }
        return header;
    }

    /**
     * Walks the JPEG markers: SOF (dimensions, precision, progressive), APP0/JFIF (density),
     * APP1/EXIF (orientation); optional marker-name list.
     * @param data raw bytes
     * @param display display path for messages
     * @return parsed header
     */
    private static ParsedHeader parseJpeg(byte[] data, String display) {
        ParsedHeader header = new ParsedHeader();
        header.frames = 1;
        header.hasAlpha = false;
        header.markers = new ArrayList<>();
        int off = 2;
        while (off + 4 <= data.length) {
            if ((data[off] & 0xFF) != 0xFF) {
                LOG.warn("image: JPEG of '{}' desynchronises at offset {}", display, off);
                header.markers.add("DESYNC");
                return header;
            }
            int marker = data[off + 1] & 0xFF;
            if (marker == 0xFF || marker == 0x00 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                // stand-alone marker or stuffed byte: step over the marker only
                off += 2;
                continue;
            }
            if (off + 4 > data.length) {
                return header;
            }
            int len = u16be(data, off + 2);
            if (off + 2 + len > data.length) {
                LOG.warn("image: JPEG of '{}' ends inside the segment at offset {}", display, off);
                header.markers.add("TRUNCATED");
                return header;
            }
            header.markers.add(markerName(marker));
            boolean isSof = marker >= 0xC0 && marker <= 0xCF
                    && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (isSof && header.bitDepth == null) {
                header.bitDepth = data[off + 4] & 0xFF;
                int components = data[off + 9] & 0xFF;
                header.colorType = switch (components) {
                    case 1 -> "grayscale";
                    case 3 -> "truecolor";
                    case 4 -> "cmyk";
                    default -> "unknown(" + components + ")";
                };
                header.progressive = marker == 0xC2;
            }
            if (marker == 0xE0 && len >= 16 && "JFIF".equals(new String(data, off + 4, 4,
                    StandardCharsets.US_ASCII)) && header.dpi == null) {
                int units = data[off + 11] & 0xFF;
                if (units == 1) {
                    header.dpi = new Integer[] {u16be(data, off + 12), u16be(data, off + 14)};
                }
            }
            if (marker == 0xE1 && len >= 4 && header.orientation == null) {
                header.orientation = exifOrientation(data, off + 4, len);
            }
            if (marker == 0xDA) {
                break; // compressed scan follows
            }
            off += 2 + len;
        }
        return header;
    }

    /** Marker names for the optional chunk list ({@code info(…, {chunks: true})}). */
    private static String markerName(int marker) {
        return switch (marker) {
            case 0xD8 -> "SOI";
            case 0xC0 -> "SOF0";
            case 0xC1 -> "SOF1";
            case 0xC2 -> "SOF2";
            case 0xC4 -> "DHT";
            case 0xDB -> "DQT";
            case 0xDA -> "SOS";
            case 0xDD -> "DRI";
            case 0xE0 -> "APP0";
            case 0xE1 -> "APP1";
            case 0xEE -> "APP14";
            case 0xFE -> "COM";
            default -> String.format("m%02x", marker);
        };
    }

    /**
     * Reads the EXIF orientation (tag 0x0112) out of an APP1 segment.
     * @param data raw bytes
     * @param offset offset of the segment payload (after the length field)
     * @param len payload length
     * @return the orientation (1..8) or {@code null}
     */
    private static Integer exifOrientation(byte[] data, int offset, int len) {
        try {
            int tiff = offset + 6; // "Exif\0\0"
            if (tiff + 8 > offset + len || data[offset + 2] != 'E') {
                return null;
            }
            boolean le = data[tiff] == 'I';
            int ifd0 = tiff + u32(tiff + 4, le, data);
            if (ifd0 + 2 > data.length) {
                return null;
            }
            int count = u16(ifd0, le, data);
            for (int i = 0; i < count; i++) {
                int entry = ifd0 + 2 + i * 12;
                if (entry + 12 > data.length) {
                    return null;
                }
                int tag = u16(entry, le, data);
                if (tag == 0x0112) {
                    int value = u16(entry + 8, le, data);
                    return value >= 1 && value <= 8 ? value : null;
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("image: EXIF orientation parse failed, ignoring: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Walks the GIF blocks: logical screen descriptor (dimensions), image-descriptor count
     * (frames), graphic-control extensions (transparency).
     * @param data raw bytes
     * @param display display path for messages
     * @return parsed header
     */
    private static ParsedHeader parseGif(byte[] data, String display) {
        ParsedHeader header = new ParsedHeader();
        if (data.length < 14) {
            return header;
        }
        header.bitDepth = 8;
        header.colorType = "palette";
        header.hasAlpha = false;
        int off = 13;
        int flags = data[10] & 0xFF;
        if ((flags & 0x80) != 0) {
            off += 3 * (1 << ((flags & 0x07) + 1)); // global colour table
        }
        int frames = 0;
        while (off < data.length) {
            int introducer = data[off++] & 0xFF;
            if (introducer == 0x3B) {
                break;
            }
            if (introducer == 0x21) { // extension
                if (off + 2 > data.length) {
                    break;
                }
                int label = data[off++] & 0xFF;
                if (label == 0xF9 && off + 4 <= data.length) { // graphic control
                    if ((data[off] & 0x01) != 0) {
                        header.hasAlpha = true;
                        header.colorType = "palette+alpha";
                    }
                    off += 4;
                }
                while (off < data.length) { // sub-blocks
                    int size = data[off++] & 0xFF;
                    if (size == 0) {
                        break;
                    }
                    off += size;
                }
                continue;
            }
            if (introducer == 0x2C) { // image descriptor
                frames++;
                if (off + 9 > data.length) {
                    break;
                }
                int local = data[off + 8] & 0xFF;
                off += 9;
                if ((local & 0x80) != 0) {
                    off += 3 * (1 << ((local & 0x07) + 1)); // local colour table
                }
                if (off >= data.length) {
                    break;
                }
                off++; // LZW minimum code size
                while (off < data.length) {
                    int size = data[off++] & 0xFF;
                    if (size == 0) {
                        break;
                    }
                    off += size;
                }
                continue;
            }
            LOG.warn("image: GIF of '{}' has an unknown block 0x{} at offset {}",
                    display, Integer.toHexString(introducer), off - 1);
            break;
        }
        header.frames = Math.max(frames, 1);
        return header;
    }

    /**
     * Reads the BMP header: dimensions, bit depth.
     * @param data raw bytes
     * @param display display path for messages
     * @return parsed header
     */
    private static ParsedHeader parseBmp(byte[] data, String display) {
        ParsedHeader header = new ParsedHeader();
        if (data.length < 30) {
            return header;
        }
        header.frames = 1;
        header.hasAlpha = false;
        int bitDepth = u16le(data, 28);
        header.bitDepth = bitDepth;
        int height = Math.abs(s32le(data, 22));
        int width = s32le(data, 18);
        header.colorType = bitDepth <= 8 ? "palette"
                : (bitDepth == 32 ? "truecolor" : "truecolor");
        if (width > 0 && height > 0) {
            // the dimensions only matter for the message quality; ImageIO reads them anyway
            LOG.debug("image: BMP of '{}' is {}x{}", display, width, height);
        }
        return header;
    }

    // ========================================================================
    // Metadata assembly
    // ========================================================================

    /**
     * Builds the {@code info()} map from a parsed header plus, optionally, the decoded raster
     * (an opened/created image reports its raster dimensions; bytes carry the SHA-256).
     * @param header the parsed format header
     * @param format format name
     * @param raw raw bytes, or {@code null} for a created image
     * @param img the decoded raster, or {@code null} for a pure header read
     * @param display display path for messages
     * @param chunks whether to include the chunk/marker list
     * @return metadata map
     */
    private Map<String, Object> describe(ParsedHeader header, String format, byte[] raw,
                                         BufferedImage img, String display, boolean chunks) {
        int width = img != null ? img.getWidth() : 0;
        int height = img != null ? img.getHeight() : 0;
        if (width == 0 && raw != null) {
            int[] dims = headerDims(format, raw);
            width = dims[0];
            height = dims[1];
        }
        int orientation = header.orientation != null ? header.orientation : 1;
        boolean swap = orientation >= 5; // transposed/rotated 90°: displayed dims swap

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", display);
        result.put("format", format);
        result.put("mimeType", mimeType(format));
        result.put("width", width);
        result.put("height", height);
        result.put("displayedWidth", swap ? height : width);
        result.put("displayedHeight", swap ? width : height);
        result.put("megapixels", round((double) width * height / 1_000_000, 2));
        result.put("bitDepth", header.bitDepth);
        result.put("hasAlpha", header.hasAlpha != null && header.hasAlpha);
        result.put("colorType", header.colorType);
        result.put("progressive", header.progressive);
        result.put("orientation", orientation);
        Integer[] dpi = header.dpi;
        if (dpi != null) {
            Map<String, Object> dpiMap = new LinkedHashMap<>();
            dpiMap.put("x", dpi[0]);
            dpiMap.put("y", dpi[1]);
            result.put("dpi", dpiMap);
        } else {
            result.put("dpi", null);
        }
        result.put("frames", header.frames != null ? header.frames : 1);
        result.put("fileSize", raw != null ? raw.length : 0);
        result.put("sha256", raw != null ? sha256(raw) : null);
        result.put("chunks", chunks && header.markers != null ? header.markers : null);
        return result;
    }

    /**
     * Dimensions straight from the ImageIO header reader (fallback when the quick parse is
     * format-unknown, e.g. WBMP).
     * @param format format name
     * @param data raw bytes
     * @return {@code [width, height]}, or {@code [0, 0]}
     */
    private static int[] headerDims(String format, byte[] data) {
        ByteArrayInputStream bin = new ByteArrayInputStream(data);
        ImageInputStream iis = new MemoryCacheImageInputStream(bin);
        Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
        if (!readers.hasNext()) {
            return new int[] {0, 0};
        }
        ImageReader reader = readers.next();
        try {
            reader.setInput(iis, true, true);
            return new int[] {reader.getWidth(0), reader.getHeight(0)};
        } catch (IOException e) {
            LOG.warn("image: header read of '{}' ({}) failed: {}", format, data.length, e.getMessage());
            return new int[] {0, 0};
        } finally {
            reader.dispose();
        }
    }

    /** MIME type per format name. */
    private static String mimeType(String format) {
        return switch (format) {
            case "PNG" -> "image/png";
            case "JPEG", "JPG" -> "image/jpeg";
            case "GIF" -> "image/gif";
            case "BMP" -> "image/bmp";
            case "WBMP" -> "image/vnd.wap.wbmp";
            default -> null;
        };
    }

    /** SHA-256 of the raw bytes ({@code crypto.sha256} composition). */
    private static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from every JRE", e);
        }
    }

    // ========================================================================
    // Analysis
    // ========================================================================

    /** Colour and luminance statistics over the (sampled) raster. */
    private static final class GlobalStats {
        int pixelCount;
        int uniqueColors;
        int stride;
        final int[] lumHist = new int[256];
        final int[] quantBins = new int[64];
        final long[] quantRSum = new long[64];   // per-channel sums per bin (packed sums would
        final long[] quantGSum = new long[64];   // spill one channel into the next)
        final long[] quantBSum = new long[64];
        final int[] hueHist = new int[36];
        long satSum100;                          // mean saturation * 100
        long brightSum100;                       // mean value * 100
        long grayCount;
        double lumMean;
        double lumStddev;
        double entropy;
        List<Double> histogram;

        /**
         * @return the {@code quantized} sub-map: 4x4x4 RGB cube, top 8 bins by share
         */
        Map<String, Object> quantized() {
            int distinct = 0;
            List<int[]> order = new ArrayList<>();
            for (int bin = 0; bin < 64; bin++) {
                if (quantBins[bin] > 0) {
                    distinct++;
                    order.add(new int[] {quantBins[bin], bin});
                }
            }
            order.sort((a, b) -> b[0] != a[0] ? b[0] - a[0] : a[1] - b[1]);
            List<Map<String, Object>> bins = new ArrayList<>();
            for (int i = 0; i < order.size() && i < 8; i++) {
                int bin = order.get(i)[1];
                int count = quantBins[bin];
                int r = (int) (quantRSum[bin] / count);
                int g = (int) (quantGSum[bin] / count);
                int b = (int) (quantBSum[bin] / count);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("rgb", String.format("#%02x%02x%02x", r, g, b));
                entry.put("share", round((double) count / pixelCount, 4));
                bins.add(entry);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("distinct", distinct);
            result.put("bins", bins);
            return result;
        }

        /**
         * @return the {@code dominant} sub-map: hue family of the chromatic pixels plus the
         *         quantized background colour
         */
        Map<String, Object> dominant() {
            int chromatic = 0;
            for (int count : hueHist) {
                chromatic += count;
            }
            int bestBin = 0;
            int bestCount = 0;
            long maxShare = 0;
            int maxBin = 0;
            for (int bin = 0; bin < 64; bin++) {
                if (quantBins[bin] > maxShare) {
                    maxShare = quantBins[bin];
                    maxBin = bin;
                }
            }
            for (int bin = 0; bin < 36; bin++) {
                if (hueHist[bin] > bestCount) {
                    bestCount = hueHist[bin];
                    bestBin = bin;
                }
            }
            int backgroundCount = quantBins[maxBin];
            int br = (int) (quantRSum[maxBin] / backgroundCount);
            int bg = (int) (quantGSum[maxBin] / backgroundCount);
            int bb = (int) (quantBSum[maxBin] / backgroundCount);
            Map<String, Object> result = new LinkedHashMap<>();
            if (bestCount > 0) {
                int degrees = bestBin * 10 + 5;
                result.put("hue", hueName(degrees));
                result.put("hueDegrees", degrees);
                result.put("hueShare", round((double) bestCount / chromatic, 3));
            } else {
                result.put("hue", null);
                result.put("hueDegrees", null);
                result.put("hueShare", null);
            }
            result.put("saturation", round(satSum100 / (100.0 * pixelCount), 3));
            result.put("brightness", round(brightSum100 / (100.0 * pixelCount), 3));
            result.put("grayscaleShare", round((double) grayCount / pixelCount, 4));
            Map<String, Object> background = new LinkedHashMap<>();
            background.put("rgb", String.format("#%02x%02x%02x", br, bg, bb));
            background.put("share", round((double) backgroundCount / pixelCount, 4));
            result.put("background", background);
            return result;
        }

        /**
         * @return the {@code luminance} sub-map: mean, stddev, entropy and the optional histogram
         */
        Map<String, Object> luminance() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("mean", round(lumMean, 3));
            result.put("stddev", round(lumStddev, 3));
            result.put("entropy", round(entropy, 2));
            result.put("histogram", histogram);
            return result;
        }
    }

    /** Structure statistics over full-resolution windows (quadrants). */
    private record StructureStats(double edgeDensity, double meanGradient, double flatShare,
                                  double largestFlatArea, double rowUniformity, double noise) {

        /**
         * @return the {@code structure} sub-map
         */
        Map<String, Object> map() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("edgeDensity", round(edgeDensity, 4));
            result.put("meanGradient", round(meanGradient, 2));
            result.put("flatShare", round(flatShare, 4));
            result.put("largestFlatArea", round(largestFlatArea, 4));
            result.put("rowUniformity", round(rowUniformity, 4));
            result.put("noise", round(noise, 3));
            return result;
        }
    }

    /**
     * Collects the global colour statistics over a grid sample of the raster.
     * @param img decoded image
     * @param stride grid stride (1 = every pixel)
     * @param histogramBins luminance histogram bins (0 = none)
     * @return the global statistics
     */
    private static GlobalStats collectGlobal(BufferedImage img, int stride, int histogramBins) {
        GlobalStats stats = new GlobalStats();
        stats.stride = stride;
        Set<Integer> unique = new HashSet<>();
        int width = img.getWidth();
        int height = img.getHeight();
        int[] row = new int[width];
        for (int y = 0; y < height; y += stride) {
            row = img.getRGB(0, y, width, 1, row, 0, width);
            for (int x = 0; x < width; x += stride) {
                int argb = row[x];
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                stats.pixelCount++;
                int packed = (r << 16) | (g << 8) | b;
                unique.add(packed);
                int bin = ((r >> 6) << 4) | ((g >> 6) << 2) | (b >> 6);
                stats.quantBins[bin]++;
                stats.quantRSum[bin] += r;
                stats.quantGSum[bin] += g;
                stats.quantBSum[bin] += b;
                float[] hsb = Color.RGBtoHSB(r, g, b, null);
                int lum = luminance(r, g, b);
                stats.lumHist[lum]++;
                stats.satSum100 += (int) (hsb[1] * 100);
                stats.brightSum100 += (int) (hsb[2] * 100);
                if (maxOf(r, g, b) - minOf(r, g, b) < 16) {
                    stats.grayCount++;
                }
                if (maxOf(r, g, b) - minOf(r, g, b) >= 32) {
                    stats.hueHist[(int) (hsb[0] * 360) / 10 % 36]++;
                }
            }
        }
        stats.uniqueColors = unique.size();
        long sum = 0;
        long sumSq = 0;
        for (int l = 0; l < 256; l++) {
            sum += (long) l * stats.lumHist[l];
            sumSq += (long) l * l * stats.lumHist[l];
        }
        stats.lumMean = (double) sum / stats.pixelCount;
        stats.lumStddev = Math.sqrt(Math.max(0, (double) sumSq / stats.pixelCount
                - stats.lumMean * stats.lumMean));
        double entropy = 0;
        for (int count : stats.lumHist) {
            if (count > 0) {
                double p = (double) count / stats.pixelCount;
                entropy -= p * (Math.log(p) / Math.log(2));
            }
        }
        stats.entropy = entropy;
        if (histogramBins > 0) {
            int[] hist = new int[histogramBins];
            int per = 256 / histogramBins;
            for (int l = 0; l < 256; l++) {
                hist[Math.min(l / per, histogramBins - 1)] += stats.lumHist[l];
            }
            List<Double> normalized = new ArrayList<>(histogramBins);
            for (int i = 0; i < histogramBins; i++) {
                normalized.add(round((double) hist[i] / stats.pixelCount, 5));
            }
            stats.histogram = normalized;
        }
        return stats;
    }

    /**
     * Collects the structure statistics over up to four full-resolution quadrant windows.
     * @param img decoded image
     * @return the structure statistics
     */
    private static StructureStats collectStructure(BufferedImage img) {
        int width = img.getWidth();
        int height = img.getHeight();
        if (width < 3 || height < 3) {
            return new StructureStats(0, 0, 0, 0, 0, 0);
        }
        List<int[]> tiles = new ArrayList<>(4);
        List<int[]> tileDims = new ArrayList<>(4);
        for (int q = 0; q < 4; q++) {
            int x0 = (q & 1) == 0 ? 0 : width / 2;
            int y0 = (q & 2) == 0 ? 0 : height / 2;
            int qw = (q & 1) == 0 ? width - width / 2 : width / 2;
            int qh = (q & 2) == 0 ? height - height / 2 : height / 2;
            if (qw < 1 || qh < 1) {
                continue;
            }
            // keep full resolution: shrink oversized quadrants to a centred window
            if ((long) qw * qh > STRUCTURE_TILE_BUDGET) {
                double scale = Math.sqrt((double) STRUCTURE_TILE_BUDGET / ((long) qw * qh));
                qw = Math.max(3, (int) (qw * scale));
                qh = Math.max(3, (int) (qh * scale));
                x0 += (width - x0 - qw) / 2;
                y0 += (height - y0 - qh) / 2;
                x0 = Math.max(0, Math.min(x0, width - qw));
                y0 = Math.max(0, Math.min(y0, height - qh));
            }
            tiles.add(img.getRGB(x0, y0, qw, qh, null, 0, qw));
            tileDims.add(new int[] {qw, qh});
        }
        long edge = 0;
        long count = 0;
        double gradSum = 0;
        long flat = 0;
        double noiseSum = 0;
        long flatCount = 0;
        double uniformRows = 0;
        double totalRows = 0;
        double largestFlat = 0;
        for (int t = 0; t < tiles.size(); t++) {
            int[] px = tiles.get(t);
            int tw = tileDims.get(t)[0];
            int th = tileDims.get(t)[1];
            int[] lum = new int[tw * th];
            for (int i = 0; i < lum.length; i++) {
                lum[i] = luminance((px[i] >> 16) & 0xFF, (px[i] >> 8) & 0xFF, px[i] & 0xFF);
            }
            byte[] flatMask = new byte[lum.length];
            for (int y = 1; y < th - 1; y++) {
                boolean rowUniform = true;
                for (int x = 1; x < tw - 1; x++) {
                    int i = y * tw + x;
                    int gx = lum[i + 1] - lum[i - 1];
                    int gy = lum[i + tw] - lum[i - tw];
                    int mag = Math.abs(gx) + Math.abs(gy);
                    gradSum += mag;
                    count++;
                    if (mag > 48) {
                        edge++;
                    }
                    if (Math.abs(gx) <= 2 && Math.abs(gy) <= 2) {
                        flat++;
                        flatMask[i] = 1;
                        flatCount++;
                        noiseSum += mag / 2.0;
                    }
                    int argb = px[i];
                    int left = px[i - 1];
                    if (argb != left) {
                        rowUniform = false;
                    }
                }
                totalRows++;
                if (rowUniform) {
                    uniformRows++;
                }
            }
            largestFlat = Math.max(largestFlat, largestFlatRegion(flatMask, tw, th));
        }
        if (count == 0) {
            return new StructureStats(0, 0, 0, 0, 0, 0);
        }
        return new StructureStats(
                (double) edge / count,
                gradSum / count,
                (double) flat / count,
                largestFlat,
                totalRows > 0 ? uniformRows / totalRows : 0,
                flatCount > 0 ? noiseSum / flatCount : 0);
    }

    /**
     * Largest 4-connected region of the flat mask, as a share of the tile (BFS with an int stack).
     * @param mask flat mask (1 = flat)
     * @param width tile width
     * @param height tile height
     * @return largest region share (0..1)
     */
    private static double largestFlatRegion(byte[] mask, int width, int height) {
        boolean[] visited = new boolean[mask.length];
        int[] stack = new int[mask.length];
        int best = 0;
        for (int start = 0; start < mask.length; start++) {
            if (mask[start] != 1 || visited[start]) {
                continue;
            }
            int top = 0;
            stack[top++] = start;
            visited[start] = true;
            int size = 0;
            while (top > 0) {
                int i = stack[--top];
                size++;
                int x = i % width;
                if (x > 0 && mask[i - 1] == 1 && !visited[i - 1]) {
                    visited[i - 1] = true;
                    stack[top++] = i - 1;
                }
                if (x < width - 1 && mask[i + 1] == 1 && !visited[i + 1]) {
                    visited[i + 1] = true;
                    stack[top++] = i + 1;
                }
                if (i >= width && mask[i - width] == 1 && !visited[i - width]) {
                    visited[i - width] = true;
                    stack[top++] = i - width;
                }
                if (i + width < mask.length && mask[i + width] == 1 && !visited[i + width]) {
                    visited[i + width] = true;
                    stack[top++] = i + width;
                }
            }
            best = Math.max(best, size);
        }
        return (double) best / mask.length;
    }

    /**
     * Guesses the image kind from the measured features - an additive score per kind with the
     * reasons attached, so the calling LLM can disagree. The weights are tuned on the seven
     * sample images under {@code src/test/resources/js} (formula -&gt; text, rainbow and two
     * line drawings -&gt; drawing, bird and cat photos -&gt; photo, fvwm desktop -&gt;
     * screenshot); retuning is a table edit here, see docs/js/image.md §9. Edge density gates
     * the text score (thin glyph strokes), luminance entropy separates line art from palette
     * screenshots; row uniformity and the grey "chrome" test failed on the samples and no
     * longer contribute.
     * @param global global colour statistics
     * @param structure structure statistics
     * @return map {@code {kind, scores, reasons}}
     */
    private static Map<String, Object> guessKind(GlobalStats global, StructureStats structure) {
        long n = global.pixelCount;
        double bgShare = backgroundShare(global);
        double grayShare = (double) global.grayCount / n;
        double u = global.uniqueColors;
        double entropy = global.entropy;
        double flat = structure.flatShare();
        double noise = structure.noise();
        double edges = structure.edgeDensity();

        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, List<String>> reasons = new LinkedHashMap<>();
        for (String kind : List.of("text", "screenshot", "drawing", "photo")) {
            scores.put(kind, 0.0);
            reasons.put(kind, new ArrayList<>());
        }
        // text: thin glyph strokes leave few edge pixels - a page has a low edge density
        // (formula sample 0.06); line art has many (outline drawings 0.14 / 0.32). Without this
        // gate a black-and-white drawing scores as "text" on background share, palette size and
        // entropy alone.
        boolean textLikeEdges = edges <= 0.10;
        add(scores, reasons, "text", 3.0, bgShare >= 0.85 && textLikeEdges,
                String.format(Locale.ROOT, "one colour covers %.0f%% (uniform background)",
                        bgShare * 100));
        add(scores, reasons, "text", 2.0, grayShare >= 0.9 && textLikeEdges,
                String.format(Locale.ROOT, "grayscale share %.0f%%", grayShare * 100));
        add(scores, reasons, "text", 1.5, u <= 512,
                String.format(Locale.ROOT, "only %d unique colours", (long) u));
        add(scores, reasons, "text", 1.0, entropy <= 1.5,
                String.format(Locale.ROOT, "luminance entropy %.2f bits is very low", entropy));

        // screenshot: a palette with many flat areas and real edges, but luminance entropy well
        // between line art (~0.7) and photos (~7). Row uniformity and the grey "chrome" test
        // failed on the samples (the fvwm desktop screenshot has 0.001 uniform rows and is
        // colourful; a sheep line drawing has 0.19 uniform rows and is grey) and were dropped.
        add(scores, reasons, "screenshot", 2.0, bgShare >= 0.5 && u <= 20000,
                String.format(Locale.ROOT, "background %.0f%% with %d colours", bgShare * 100,
                        (long) u));
        add(scores, reasons, "screenshot", 1.5, flat >= 0.6,
                String.format(Locale.ROOT, "%.0f%% flat areas", flat * 100));
        add(scores, reasons, "screenshot", 1.0, noise <= 0.3,
                String.format(Locale.ROOT, "noise %.2f is near zero", noise));
        add(scores, reasons, "screenshot", 2.5,
                flat >= 0.55 && noise <= 0.3 && edges >= 0.02 && u <= 20000
                        && entropy >= 1.5 && entropy <= 6.0,
                String.format(Locale.ROOT, "desktop-like palette: %.0f%% flat, edges %.3f,"
                        + " entropy %.2f bits", flat * 100, edges, entropy));

        add(scores, reasons, "drawing", 2.5, grayShare <= 0.5 && u <= 5000,
                String.format(Locale.ROOT, "colourful and few colours (grayscale share %.0f%%,"
                        + " %d colours)", grayShare * 100, (long) u));
        add(scores, reasons, "drawing", 2.0, noise <= 0.3 && edges <= 0.02,
                String.format(Locale.ROOT, "smooth shading, almost no edges (%.3f)", edges));
        add(scores, reasons, "drawing", 1.5, u <= 5000,
                String.format(Locale.ROOT, "%d unique colours", (long) u));
        add(scores, reasons, "drawing", 1.0, entropy <= 3.5,
                String.format(Locale.ROOT, "luminance entropy %.2f bits is low", entropy));
        add(scores, reasons, "drawing", 1.0, bgShare >= 0.3,
                String.format(Locale.ROOT, "background colour covers %.0f%%", bgShare * 100));
        add(scores, reasons, "drawing", 2.5,
                u <= 512 && noise <= 0.3 && edges >= 0.10 && entropy <= 2.5 && bgShare >= 0.5,
                String.format(Locale.ROOT, "line art: many edges (%.3f) for %d colours on a"
                        + " flat background", edges, (long) u));

        add(scores, reasons, "photo", 2.5, u > Math.max(20000, n / 20.0),
                String.format(Locale.ROOT, "%d unique colours", (long) u));
        add(scores, reasons, "photo", 2.0, entropy >= 4.5,
                String.format(Locale.ROOT, "luminance entropy %.2f bits", entropy));
        add(scores, reasons, "photo", 1.5, noise >= 0.2,
                String.format(Locale.ROOT, "sensor/JPEG noise %.2f", noise));
        add(scores, reasons, "photo", 1.0, flat <= 0.5,
                String.format(Locale.ROOT, "only %.0f%% flat areas", flat * 100));
        add(scores, reasons, "photo", 1.0, bgShare <= 0.3,
                String.format(Locale.ROOT, "no dominant background (%.0f%%)", bgShare * 100));

        String best = "unknown";
        double bestScore = 0;
        for (Map.Entry<String, Double> e : scores.entrySet()) {
            if (e.getValue() > bestScore) {
                bestScore = e.getValue();
                best = e.getKey();
            }
        }
        if (bestScore < 2.0) {
            best = "unknown";
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", best);
        result.put("scores", scores);
        result.put("reasons", reasons.get(best));
        return result;
    }

    /**
     * Adds a weighted feature to one kind's score when its condition holds.
     * @param scores score map per kind
     * @param reasons reason lists per kind
     * @param kind the kind to score
     * @param weight feature weight
     * @param condition feature condition
     * @param reason human-readable feature description (for the reasons list)
     */
    private static void add(Map<String, Double> scores, Map<String, List<String>> reasons,
                            String kind, double weight, boolean condition, String reason) {
        if (condition) {
            scores.merge(kind, weight, Double::sum);
            reasons.get(kind).add(reason);
        }
    }

    /**
     * Share of the dominant quantized colour (the "background" tell).
     * @param global global statistics
     * @return largest 4x4x4 bin share (0..1)
     */
    private static double backgroundShare(GlobalStats global) {
        long max = 0;
        for (int bin = 0; bin < 64; bin++) {
            max = Math.max(max, global.quantBins[bin]);
        }
        return (double) max / global.pixelCount;
    }

    /**
     * Coarse hue-family name for a hue in degrees.
     * @param degrees hue 0..359
     * @return one of red/orange/yellow/green/cyan/blue/violet/magenta
     */
    private static String hueName(int degrees) {
        if (degrees < 21 || degrees >= 344) {
            return "red";
        }
        if (degrees < 46) {
            return "orange";
        }
        if (degrees < 68) {
            return "yellow";
        }
        if (degrees < 161) {
            return "green";
        }
        if (degrees < 201) {
            return "cyan";
        }
        if (degrees < 261) {
            return "blue";
        }
        if (degrees < 291) {
            return "violet";
        }
        return "magenta";
    }

    // ========================================================================
    // Drawing and raster access (P3)
    // ========================================================================

    /**
     * Sets a single pixel.
     * @param handle handle from {@link #open(byte[], String, Map)} or {@link #create(Map)}
     * @param x x coordinate (0-based, within the raster)
     * @param y y coordinate (0-based, within the raster)
     * @param color colour specification ({@code '#rrggbb'}, CSS name, ...)
     */
    public void setPixel(String handle, int x, int y, String color) {
        String fn = "setPixel";
        OpenedImage opened = requireHandle(handle, fn);
        BufferedImage img = opened.image();
        requireInRaster(fn, "x", x, img.getWidth());
        requireInRaster(fn, "y", y, img.getHeight());
        img.setRGB(x, y, parseColor(fn, "color", color));
    }

    /**
     * Draws a line, optionally with an arrowhead at the start, the end or both.
     * @param handle handle from {@link #open(byte[], String, Map)} or {@link #create(Map)}
     * @param x1 start x (0..width)
     * @param y1 start y (0..height)
     * @param x2 end x (0..width)
     * @param y2 end y (0..height)
     * @param options options map: {@code color} (default black), {@code width} (default 2),
     *                {@code opacity} (0..1, default 1), {@code antialias} (default true),
     *                {@code arrow} ({@code 'none'} (default), {@code 'start'}, {@code 'end'},
     *                {@code 'both'})
     */
    public void line(String handle, int x1, int y1, int x2, int y2, Map<String, Object> options) {
        String fn = "line";
        requireKnown(fn, options, "color", "width", "opacity", "antialias", "arrow");
        OpenedImage opened = requireHandle(handle, fn);
        BufferedImage img = opened.image();
        requireOnBorder(fn, "x1", x1, img.getWidth());
        requireOnBorder(fn, "y1", y1, img.getHeight());
        requireOnBorder(fn, "x2", x2, img.getWidth());
        requireOnBorder(fn, "y2", y2, img.getHeight());
        Graphics2D g = img.createGraphics();
        try {
            Color color = drawColor(fn, options);
            float lineWidth = (float) doubleOpt(fn, options, "width", 2.0, 0.1, 200.0);
            applyDrawHints(g, boolOpt(fn, options, "antialias", true));
            g.setColor(color);
            g.setStroke(new BasicStroke(lineWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Double(x1, y1, x2, y2));
            String arrow = stringOpt(fn, options, "arrow", "none");
            boolean arrowEnd = "end".equals(arrow) || "both".equals(arrow);
            boolean arrowStart = "start".equals(arrow) || "both".equals(arrow);
            if (!arrowEnd && !arrowStart && !"none".equals(arrow)) {
                throw badValue(fn, "arrow", "'none', 'start', 'end' or 'both'");
            }
            if (arrowEnd) {
                drawArrowHead(g, x1, y1, x2, y2, lineWidth);
            }
            if (arrowStart) {
                drawArrowHead(g, x2, y2, x1, y1, lineWidth);
            }
        } finally {
            g.dispose();
        }
    }

    /**
     * Draws a rectangle.
     * @param handle handle from {@link #open(byte[], String, Map)} or {@link #create(Map)}
     * @param x left edge (within the raster)
     * @param y top edge (within the raster)
     * @param width rectangle width (at least 1)
     * @param height rectangle height (at least 1)
     * @param options options map: {@code color} (default black), {@code fill} (default false
     *                = outline), {@code width} (outline line width, default 2),
     *                {@code opacity}, {@code antialias}
     */
    public void rect(String handle, int x, int y, int width, int height,
                     Map<String, Object> options) {
        String fn = "rect";
        requireKnown(fn, options, "color", "width", "fill", "antialias", "opacity");
        BufferedImage img = region(fn, handle, x, y, width, height);
        Graphics2D g = img.createGraphics();
        try {
            Color color = drawColor(fn, options);
            applyDrawHints(g, boolOpt(fn, options, "antialias", true));
            g.setColor(color);
            if (boolOpt(fn, options, "fill", false)) {
                g.fillRect(x, y, width, height);
            } else {
                float lineWidth = (float) doubleOpt(fn, options, "width", 2.0, 0.1, 200.0);
                g.setStroke(new BasicStroke(lineWidth, BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND));
                g.draw(new Rectangle2D.Double(x, y, width, height));
            }
        } finally {
            g.dispose();
        }
    }

    /**
     * Draws an ellipse (bounding box, like the DOM canvas).
     * @param handle handle from {@link #open(byte[], String, Map)} or {@link #create(Map)}
     * @param x left edge of the bounding box
     * @param y top edge of the bounding box
     * @param width bounding-box width (at least 1)
     * @param height bounding-box height (at least 1)
     * @param options options map: {@code color}, {@code fill} (default false), {@code width},
     *                {@code opacity}, {@code antialias}
     */
    public void ellipse(String handle, int x, int y, int width, int height,
                        Map<String, Object> options) {
        String fn = "ellipse";
        requireKnown(fn, options, "color", "width", "fill", "antialias", "opacity");
        BufferedImage img = region(fn, handle, x, y, width, height);
        Graphics2D g = img.createGraphics();
        try {
            Color color = drawColor(fn, options);
            applyDrawHints(g, boolOpt(fn, options, "antialias", true));
            g.setColor(color);
            if (boolOpt(fn, options, "fill", false)) {
                g.fillOval(x, y, width, height);
            } else {
                float lineWidth = (float) doubleOpt(fn, options, "width", 2.0, 0.1, 200.0);
                g.setStroke(new BasicStroke(lineWidth, BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND));
                g.draw(new Ellipse2D.Double(x, y, width, height));
            }
        } finally {
            g.dispose();
        }
    }

    /**
     * Draws a dot (filled circle of the given diameter, centred on the coordinate).
     * @param handle handle from {@link #open(byte[], String, Map)} or {@link #create(Map)}
     * @param x centre x (within the raster)
     * @param y centre y (within the raster)
     * @param options options map: {@code color} (default black), {@code size} (diameter,
     *                default 2), {@code opacity}, {@code antialias}
     */
    public void point(String handle, int x, int y, Map<String, Object> options) {
        String fn = "point";
        requireKnown(fn, options, "color", "size", "antialias", "opacity");
        OpenedImage opened = requireHandle(handle, fn);
        BufferedImage img = opened.image();
        requireInRaster(fn, "x", x, img.getWidth());
        requireInRaster(fn, "y", y, img.getHeight());
        double size = doubleOpt(fn, options, "size", 2.0, 1.0, 200.0);
        Graphics2D g = img.createGraphics();
        try {
            Color color = drawColor(fn, options);
            applyDrawHints(g, boolOpt(fn, options, "antialias", true));
            g.setColor(color);
            g.fillOval((int) Math.round(x - size / 2.0), (int) Math.round(y - size / 2.0),
                    (int) Math.round(size), (int) Math.round(size));
        } finally {
            g.dispose();
        }
    }

    /**
     * Draws a single line of text; {@code y} is the baseline, {@code x} the anchor according to
     * {@code align}. Logical fonts only ({@code 'sans'}, {@code 'serif'}, {@code 'mono'}), so
     * the output does not depend on which families are installed.
     * @param handle handle from {@link #open(byte[], String, Map)} or {@link #create(Map)}
     * @param x anchor x (within the raster)
     * @param y baseline y (within the raster)
     * @param text the text (single line; {@code \n}/&#x5C;r are rejected)
     * @param options options map: {@code color} (default black), {@code size} (default 14),
     *                {@code font} ({@code 'sans'} (default), {@code 'serif'}, {@code 'mono'}),
     *                {@code bold}, {@code italic}, {@code background} (colour filled behind
     *                the text first), {@code align} ({@code 'left'} (default), {@code 'center'},
     *                {@code 'right'}), {@code opacity}, {@code antialias}
     */
    public void text(String handle, int x, int y, String text, Map<String, Object> options) {
        String fn = "text";
        requireKnown(fn, options, "color", "size", "font", "bold", "italic", "background",
                "align", "opacity", "antialias");
        if (text == null) {
            throw new JsUserRuntimeException("image.text: text must not be null");
        }
        if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            throw new JsUserRuntimeException("image.text: text must be a single line"
                    + " (no \\n / \\r)");
        }
        OpenedImage opened = requireHandle(handle, fn);
        BufferedImage img = opened.image();
        requireInRaster(fn, "x", x, img.getWidth());
        requireInRaster(fn, "y", y, img.getHeight());
        ensureFontsAvailable(fn);
        String family = switch (stringOpt(fn, options, "font", "sans")) {
            case "sans" -> Font.SANS_SERIF;
            case "serif" -> Font.SERIF;
            case "mono" -> Font.MONOSPACED;
            default -> throw badValue(fn, "font", "'sans', 'serif' or 'mono'");
        };
        int size = intOpt(fn, options, "size", 14, 1, 400);
        int style = (boolOpt(fn, options, "bold", false) ? Font.BOLD : Font.PLAIN)
                | (boolOpt(fn, options, "italic", false) ? Font.ITALIC : Font.PLAIN);
        Font font = new Font(family, style, size);
        String align = stringOpt(fn, options, "align", "left");
        Color color = drawColor(fn, options);
        String backgroundSpec = stringOpt(fn, options, "background", null);

        Graphics2D g = img.createGraphics();
        try {
            applyDrawHints(g, boolOpt(fn, options, "antialias", true));
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    boolOpt(fn, options, "antialias", true)
                            ? RenderingHints.VALUE_TEXT_ANTIALIAS_ON
                            : RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            FontMetrics metrics = g.getFontMetrics(font);
            int textWidth = metrics.stringWidth(text);
            int x0 = switch (align) {
                case "left" -> x;
                case "center" -> x - textWidth / 2;
                case "right" -> x - textWidth;
                default -> throw badValue(fn, "align", "'left', 'center' or 'right'");
            };
            if (backgroundSpec != null) {
                g.setColor(new Color(parseColor(fn, "background", backgroundSpec), true));
                g.fillRect(x0, y - metrics.getAscent(), textWidth, metrics.getHeight());
            }
            g.setColor(color);
            g.setFont(font);
            g.drawString(text, x0, y);
        } finally {
            g.dispose();
        }
    }

    /**
     * Reads a region of the raster as RGBA bytes (row-major, 0-255; canvas
     * {@code ImageData} semantics).
     * @param handle handle from {@link #open(byte[], String, Map)} or {@link #create(Map)}
     * @param options options map: {@code x} (default 0), {@code y} (default 0),
     *                {@code width} (default: image width), {@code height} (default: image
     *                height)
     * @return map {@code {width, height, data}} with the RGBA bytes (the bridge turns them
     *         into a real {@code Uint8Array})
     */
    public Map<String, Object> getPixels(String handle, Map<String, Object> options) {
        String fn = "getPixels";
        requireKnown(fn, options, "x", "y", "width", "height");
        OpenedImage opened = requireHandle(handle, fn);
        BufferedImage img = opened.image();
        int x = intOpt(fn, options, "x", 0, 0, img.getWidth() - 1);
        int y = intOpt(fn, options, "y", 0, 0, img.getHeight() - 1);
        int width = intOpt(fn, options, "width", img.getWidth(), 1, img.getWidth());
        int height = intOpt(fn, options, "height", img.getHeight(), 1, img.getHeight());
        if ((long) x + width > img.getWidth() || (long) y + height > img.getHeight()) {
            throw new JsUserRuntimeException(String.format(Locale.ROOT,
                    "image.%s: region %d,%d %dx%d leaves the raster of %dx%d",
                    fn, x, y, width, height, img.getWidth(), img.getHeight()));
        }
        if ((long) width * height > MAX_TRANSFER_PIXELS) {
            throw new JsUserRuntimeException(String.format(Locale.ROOT,
                    "image.%s: region %dx%d = %d pixels exceeds the transfer limit of %d pixels",
                    fn, width, height, (long) width * height, MAX_TRANSFER_PIXELS));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("width", width);
        result.put("height", height);
        result.put("data", rasterToRgba(img.getSubimage(x, y, width, height)));
        return result;
    }

    /**
     * Writes RGBA bytes (row-major, 0-255) into a region of the raster - the script's
     * per-pixel paintbrush.
     * @param handle handle from {@link #open(byte[], String, Map)} or {@link #create(Map)}
     * @param x left edge of the region
     * @param y top edge of the region
     * @param width region width
     * @param height region height
     * @param rgba {@code width * height * 4} bytes (R, G, B, A per pixel)
     */
    public void putPixels(String handle, int x, int y, int width, int height, byte[] rgba) {
        String fn = "putPixels";
        BufferedImage img = region(fn, handle, x, y, width, height);
        if (rgba == null || rgba.length != (long) width * height * 4) {
            throw new JsUserRuntimeException(String.format(Locale.ROOT,
                    "image.%s: rgba must hold width * height * 4 = %d bytes for the region"
                            + " %dx%d (got %s)", fn, (long) width * height * 4, width, height,
                    rgba == null ? "null" : rgba.length + " bytes"));
        }
        int[] argb = new int[width * height];
        for (int i = 0; i < argb.length; i++) {
            int r = rgba[i * 4] & 0xFF;
            int g = rgba[i * 4 + 1] & 0xFF;
            int b = rgba[i * 4 + 2] & 0xFF;
            int a = rgba[i * 4 + 3] & 0xFF;
            argb[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        img.setRGB(x, y, width, height, argb, 0, width);
    }

    /**
     * Cuts a region into a new image (independent raster, new handle; the source is untouched).
     * @param handle handle from {@link #open(byte[], String, Map)} or {@link #create(Map)}
     * @param x left edge of the region
     * @param y top edge of the region
     * @param width region width (at least 1)
     * @param height region height (at least 1)
     * @return the handle of the cropped image
     */
    public String crop(String handle, int x, int y, int width, int height) {
        String fn = "crop";
        BufferedImage img = region(fn, handle, x, y, width, height);
        BufferedImage out = new BufferedImage(width, height, typeOf(img));
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(img, -x, -y, null);
        } finally {
            g.dispose();
        }
        return registerNewImage(out, "crop");
    }

    /**
     * Scales an image into a new handle (the source is untouched). Give {@code width} and/or
     * {@code height} (the missing side keeps the aspect ratio) or a {@code scale} factor -
     * never all three.
     * @param handle handle from {@link #open(byte[], String, Map)} or {@link #create(Map)}
     * @param options options map: {@code width}, {@code height}, {@code scale} (0.01..16),
     *                {@code hint} ({@code 'smooth'} (default, bilinear) or {@code 'fast'}
     *                (nearest neighbour, pixel-exact))
     * @return the handle of the resized image
     */
    public String resize(String handle, Map<String, Object> options) {
        String fn = "resize";
        requireKnown(fn, options, "width", "height", "scale", "hint");
        OpenedImage opened = requireHandle(handle, fn);
        BufferedImage img = opened.image();
        boolean hasWidth = options.containsKey("width");
        boolean hasHeight = options.containsKey("height");
        boolean hasScale = options.containsKey("scale");
        if (!hasWidth && !hasHeight && !hasScale) {
            throw new JsUserRuntimeException("image.resize: one of 'width', 'height' or"
                    + " 'scale' is required (e.g. {width: 800} or {scale: 0.5})");
        }
        if (hasScale && (hasWidth || hasHeight)) {
            throw new JsUserRuntimeException("image.resize: 'scale' must not be combined with"
                    + " 'width'/'height'");
        }
        int oldWidth = img.getWidth();
        int oldHeight = img.getHeight();
        int newWidth;
        int newHeight;
        if (hasScale) {
            double scale = doubleOpt(fn, options, "scale", 1.0, 0.01, 16.0);
            newWidth = Math.max(1, (int) Math.round(oldWidth * scale));
            newHeight = Math.max(1, (int) Math.round(oldHeight * scale));
        } else {
            newWidth = hasWidth
                    ? intOpt(fn, options, "width", 1, 1, MAX_SIDE) : -1;
            newHeight = hasHeight
                    ? intOpt(fn, options, "height", 1, 1, MAX_SIDE) : -1;
            if (!hasWidth) {
                newWidth = Math.max(1, (int) Math.round((double) oldWidth * newHeight
                        / oldHeight));
            }
            if (!hasHeight) {
                newHeight = Math.max(1, (int) Math.round((double) oldHeight * newWidth
                        / oldWidth));
            }
        }
        if ((long) newWidth * newHeight > MAX_PIXELS) {
            throw new JsUserRuntimeException(String.format(Locale.ROOT,
                    "image.resize: %dx%d = %d pixels exceeds the limit of %d pixels",
                    newWidth, newHeight, (long) newWidth * newHeight, MAX_PIXELS));
        }
        boolean smooth = switch (stringOpt(fn, options, "hint", "smooth")) {
            case "smooth" -> true;
            case "fast" -> false;
            default -> throw badValue(fn, "hint", "'smooth' or 'fast'");
        };
        BufferedImage out = new BufferedImage(newWidth, newHeight, typeOf(img));
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, smooth
                    ? RenderingHints.VALUE_INTERPOLATION_BILINEAR
                    : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(img, 0, 0, newWidth, newHeight, null);
        } finally {
            g.dispose();
        }
        return registerNewImage(out, "resize");
    }

    /**
     * Encodes an image into a base64 data URL ({@code data:image/...;base64,...}), sized for
     * an LLM request: larger images are scaled down (never up) so the longer side fits
     * {@code maxWidth}/{@code maxHeight} (default 1568). The default format is JPEG unless the
     * image has alpha (then PNG).
     * @param handleOrPath handle from {@link #open(byte[], String, Map)}/{@link #create(Map)},
     *                     or a path / archive display path
     * @param options options map: {@code maxWidth} (default 1568), {@code maxHeight}
     *                (default 1568), {@code format} ({@code 'png'} or {@code 'jpeg'},
     *                default: automatic), {@code quality} (0..1, default 0.85, JPEG only),
     *                {@code background} (alpha flattening colour for JPEG, default white)
     * @return the data URL
     */
    public String toDataUrl(String handleOrPath, Map<String, Object> options) {
        String fn = "toDataUrl";
        OpenedImage opened = images.get(handleOrPath);
        if (opened != null) {
            return toDataUrl(opened.image(), opened.display(), options);
        }
        byte[] data = readSourceBytes(handleOrPath);
        return toDataUrl(decodeCapped(data, handleOrPath), handleOrPath, options);
    }

    /**
     * Encodes raw image bytes into a base64 data URL (see
     * {@link #toDataUrl(String, Map)}).
     * @param data raw image bytes
     * @param display display path for messages
     * @param options options map, see {@link #toDataUrl(String, Map)}
     * @return the data URL
     */
    public String toDataUrl(byte[] data, String display, Map<String, Object> options) {
        return toDataUrl(decodeCapped(data, display), display, options);
    }

    /**
     * Common body of both {@code toDataUrl} variants.
     * @param img the raster
     * @param display display path for messages
     * @param options options map, see {@link #toDataUrl(String, Map)}
     * @return the data URL
     */
    private String toDataUrl(BufferedImage img, String display, Map<String, Object> options) {
        EncodedImage encoded = encodeForDataUrl("toDataUrl", img, display, options);
        return "data:image/" + encoded.format() + ";base64,"
                + Base64.getEncoder().encodeToString(encoded.bytes());
    }

    /**
     * An image encoded for a data URL: bytes, format and the (possibly scaled-down) size.
     * @param bytes encoded image bytes
     * @param format {@code 'png'} or {@code 'jpeg'}
     * @param width encoded width
     * @param height encoded height
     */
    private record EncodedImage(byte[] bytes, String format, int width, int height) {
    }

    /**
     * Scales (never up) and encodes a raster for a data URL; shared by {@code toDataUrl} and
     * {@code vision}.
     * @param fn function name for the error messages
     * @param img the raster
     * @param display display path for messages
     * @param options options map: {@code maxWidth}, {@code maxHeight}, {@code format},
     *                {@code quality}, {@code background}
     * @return the encoded image
     */
    private EncodedImage encodeForDataUrl(String fn, BufferedImage img, String display,
                                          Map<String, Object> options) {
        requireKnown(fn, options, "maxWidth", "maxHeight", "format", "quality", "background");
        int maxWidth = intOpt(fn, options, "maxWidth", 1568, 1, MAX_SIDE);
        int maxHeight = intOpt(fn, options, "maxHeight", 1568, 1, MAX_SIDE);
        double scale = Math.min(1.0, Math.min((double) maxWidth / img.getWidth(),
                (double) maxHeight / img.getHeight()));
        if (scale < 1.0) {
            BufferedImage scaled = new BufferedImage(
                    Math.max(1, (int) Math.round(img.getWidth() * scale)),
                    Math.max(1, (int) Math.round(img.getHeight() * scale)), typeOf(img));
            Graphics2D g = scaled.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(img, 0, 0, scaled.getWidth(), scaled.getHeight(), null);
            } finally {
                g.dispose();
            }
            img = scaled;
        }
        String format = stringOpt(fn, options, "format",
                usesAlpha(img) ? "png" : "jpeg");
        double quality = doubleOpt(fn, options, "quality", 0.85, 0.0, 1.0);
        switch (format) {
            case "png" -> {
                // alpha keeps its own channel
            }
            case "jpeg", "jpg" -> {
                format = "jpeg";
                int background = parseColor(fn, "background", stringOpt(fn, options,
                        "background", "#ffffff"));
                img = flatten(img, background);
            }
            default -> throw badValue(fn, "format", "'png' or 'jpeg'");
        }
        byte[] bytes = writeImage(img, format, "jpeg".equals(format) ? quality : -1.0, display);
        return new EncodedImage(bytes, format, img.getWidth(), img.getHeight());
    }

    /**
     * Asks a vision-capable LLM about an image (one-shot, no conversation history). The image
     * goes through the {@code toDataUrl} pipeline first (longest side capped at 1568 px,
     * JPEG q85 unless {@code format}/{@code quality} say otherwise), so a request stays within
     * the gateway limits. Configure {@code llmtool.vision.url} or {@code llmtool.model.url};
     * without a configured URL the call fails with a clear message (see docs/js/image.md §7).
     * @param handleOrPath handle from {@link #open(byte[], String, Map)}/{@link #create(Map)},
     *                     or a path / archive display path
     * @param prompt the question about the image
     * @param options options map: {@code maxWidth} (default 1568), {@code maxHeight}
     *                (default 1568), {@code format} ({@code 'png'} or {@code 'jpeg'}),
     *                {@code quality} (0..1, JPEG only)
     * @return map {@code {text, model, usage, image: {width, height, format, bytes}}}
     */
    public Map<String, Object> vision(String handleOrPath, String prompt,
                                      Map<String, Object> options) {
        OpenedImage opened = images.get(handleOrPath);
        if (opened != null) {
            return vision(opened.image(), opened.display(), prompt, options);
        }
        byte[] data = readSourceBytes(handleOrPath);
        return vision(decodeCapped(data, handleOrPath), handleOrPath, prompt, options);
    }

    /**
     * Asks a vision-capable LLM about raw image bytes (see
     * {@link #vision(String, String, Map)}).
     * @param data raw image bytes
     * @param display display path for messages
     * @param prompt the question about the image
     * @param options options map, see {@link #vision(String, String, Map)}
     * @return map {@code {text, model, usage, image}}
     */
    public Map<String, Object> vision(byte[] data, String display, String prompt,
                                      Map<String, Object> options) {
        return vision(decodeCapped(data, display), display, prompt, options);
    }

    /**
     * Common body of both {@code vision} variants.
     * @param img the raster
     * @param display display path for messages
     * @param prompt the question about the image
     * @param options options map, see {@link #vision(String, String, Map)}
     * @return map {@code {text, model, usage, image}}
     */
    private Map<String, Object> vision(BufferedImage img, String display, String prompt,
                                       Map<String, Object> options) {
        String fn = "vision";
        requireKnown(fn, options, "maxWidth", "maxHeight", "format", "quality");
        if (prompt == null || prompt.isBlank()) {
            throw new JsUserRuntimeException("image.vision: the prompt must not be empty"
                    + " (e.g. image.vision(img, \"What does this screenshot show?\"))");
        }
        VisionClient client = new VisionClient(); // strict property validation happens here
        if (!client.isEnabled()) {
            throw new JsUserRuntimeException("image.vision: the feature is switched off"
                    + " (webui.vision.enabled=false) - remove the property or set it to"
                    + " 'true'");
        }
        if (!client.hasVisionEnabled()) {
            throw new JsUserRuntimeException("image.vision: the current model is not marked as"
                    + " vision-capable (webui.hasVision=false) - mark it in the configuration"
                    + " only if it supports input_image, or use image.analyze() /"
                    + " image.preview() instead");
        }
        if (!client.hasEndpoint()) {
            throw new JsUserRuntimeException("image.vision: no vision model configured - set"
                    + " webui.model.url (the current model's gateway) or webui.vision.url");
        }
        EncodedImage encoded = encodeForDataUrl(fn, img, display, options);
        if (encoded.bytes().length > client.maxImageBytes()) {
            throw new JsUserRuntimeException(String.format(Locale.ROOT,
                    "image.vision: the encoded image has %d bytes, exceeding the limit of %d"
                            + " bytes - pass smaller maxWidth/maxHeight or format 'jpeg'"
                            + " (property webui.vision.maxImageBytes)",
                    encoded.bytes().length, client.maxImageBytes()));
        }
        String dataUrl = "data:image/" + encoded.format() + ";base64,"
                + Base64.getEncoder().encodeToString(encoded.bytes());
        Map<String, Object> result = client.ask(prompt, dataUrl);
        Map<String, Object> imageInfo = new LinkedHashMap<>();
        imageInfo.put("width", encoded.width());
        imageInfo.put("height", encoded.height());
        imageInfo.put("format", encoded.format());
        imageInfo.put("bytes", encoded.bytes().length);
        result.put("image", imageInfo);
        return result;
    }

    /**
     * Renders a coarse text preview of the image - the "Grobansicht" a script can show the
     * user, and the layout hint for a text-only LLM. By default the grid is wrapped in a
     * Markdown code block (fixed-width rendering in chat UIs); {@code markdown: false} returns
     * the raw grid.
     * @param data raw image bytes
     * @param display display path for messages
     * @param options options map: {@code width} (columns, default 64), {@code charset}
     *                ({@code 'ascii'} (default), {@code 'blocks'}, {@code 'ansi'} - the latter
     *                only for output channels that render ANSI, never in Markdown/HTML),
     *                {@code gamma} (default 2.2), {@code invert} (default false, for light
     *                terminal backgrounds), {@code markdown} (default true: wrap in a
     *                {@code ```text} fence)
     * @return the preview string
     */
    public String preview(byte[] data, String display, Map<String, Object> options) {
        String fn = "preview";
        requireKnown(fn, options, "width", "charset", "gamma", "invert", "markdown");
        BufferedImage img = decodeCapped(data, display);
        int cols = intOpt(fn, options, "width", 64, 8, 400);
        double gamma = doubleOpt(fn, options, "gamma", 2.2, 0.1, 5.0);
        boolean invert = boolOpt(fn, options, "invert", false);
        String charset = stringOpt(fn, options, "charset", "ascii");
        if (!"ascii".equals(charset) && !"blocks".equals(charset) && !"ansi".equals(charset)) {
            throw badValue(fn, "charset", "'ascii', 'blocks' or 'ansi'");
        }
        String grid = renderPreview(img, cols, charset, gamma, invert);
        return boolOpt(fn, options, "markdown", true) ? "```text\n" + grid + "```" : grid;
    }

    /**
     * Lists the installed font families - the cheap debug aid for the font question
     * ({@code text()} needs at least one; a fontless JRE would draw nothing).
     * @return the family names (empty when the JRE has no fonts)
     */
    public String[] fonts() {
        try {
            String[] families = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getAvailableFontFamilyNames();
            return families != null ? families : new String[0];
        } catch (RuntimeException e) {
            LOG.warn("image: font enumeration failed: {}", e.getMessage());
            return new String[0];
        }
    }

    /**
     * Renders the preview grid: block-averaged luminance per cell, character-cell aspect
     * corrected (rows = h / w * cols * 0.5), gamma-mapped onto the ramp.
     * @param img decoded raster
     * @param cols preview columns
     * @param charset {@code 'ascii'} | {@code 'blocks'} | {@code 'ansi'}
     * @param gamma gamma exponent (mapped for a typical dark-on-light terminal)
     * @param invert flip the ramp (for light terminal backgrounds)
     * @return the preview string
     */
    private static String renderPreview(BufferedImage img, int cols, String charset,
                                        double gamma, boolean invert) {
        int w = img.getWidth();
        int h = img.getHeight();
        int rows = Math.max(1, (int) Math.round((double) h / w * cols * 0.5));
        double cellWidth = (double) w / cols;
        double cellHeight = (double) h / rows;
        String ramp = "blocks".equals(charset) || "ansi".equals(charset) ? " ░▒▓█" : " .:-=+*#%@";
        StringBuilder sb = new StringBuilder();
        int[] row = null;
        for (int cy = 0; cy < rows; cy++) {
            int y0 = Math.min(h - 1, (int) (cy * cellHeight));
            int y1 = Math.min(h, Math.max(y0 + 1, (int) ((cy + 1) * cellHeight)));
            for (int cx = 0; cx < cols; cx++) {
                int x0 = Math.min(w - 1, (int) (cx * cellWidth));
                int x1 = Math.min(w, Math.max(x0 + 1, (int) ((cx + 1) * cellWidth)));
                long lumSum = 0;
                long rSum = 0;
                long gSum = 0;
                long bSum = 0;
                int count = 0;
                int stepX = Math.max(1, (x1 - x0) / 16);
                int stepY = Math.max(1, (y1 - y0) / 16);
                for (int y = y0; y < y1; y += stepY) {
                    row = img.getRGB(0, y, w, 1, row, 0, w);
                    for (int x = x0; x < x1; x += stepX) {
                        int argb = row[x];
                        int r = (argb >> 16) & 0xFF;
                        int green = (argb >> 8) & 0xFF;
                        int b = argb & 0xFF;
                        lumSum += luminance(r, green, b);
                        rSum += r;
                        gSum += green;
                        bSum += b;
                        count++;
                    }
                }
                if (count == 0) {
                    count = 1;
                }
                double v = Math.pow((lumSum / (double) count) / 255.0, gamma);
                int idx = (int) Math.round(v * (ramp.length() - 1));
                idx = Math.max(0, Math.min(ramp.length() - 1, idx));
                if (invert) {
                    idx = ramp.length() - 1 - idx;
                }
                if ("ansi".equals(charset)) {
                    sb.append(String.format(Locale.ROOT, "\u001b[38;2;%d;%d;%dm",
                            (int) (rSum / count), (int) (gSum / count), (int) (bSum / count)));
                }
                sb.append(ramp.charAt(idx));
            }
            if ("ansi".equals(charset)) {
                sb.append("\u001b[0m");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Resolves the drawing colour including the {@code opacity} factor.
     * @param fn function name for errors
     * @param options options map
     * @return the effective colour
     */
    private Color drawColor(String fn, Map<String, Object> options) {
        Color color = new Color(parseColor(fn, "color", stringOpt(fn, options, "color",
                "#000000")), true);
        double opacity = doubleOpt(fn, options, "opacity", 1.0, 0.0, 1.0);
        return new Color(color.getRed(), color.getGreen(), color.getBlue(),
                (int) (color.getAlpha() * opacity));
    }

    /** Applies the shared drawing hints (anti-aliasing on/off). */
    private static void applyDrawHints(Graphics2D g, boolean antialias) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, antialias
                ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF);
    }

    /**
     * Draws a filled arrowhead whose tip sits on {@code (tipX, tipY)} and whose body points
     * back to {@code (fromX, fromY)}; sized from the line width.
     * @param g target graphics (colour already set)
     * @param fromX line start x
     * @param fromY line start y
     * @param tipX arrowhead tip x
     * @param tipY arrowhead tip y
     * @param lineWidth the line width the head is sized from
     */
    private static void drawArrowHead(Graphics2D g, double fromX, double fromY,
                                      double tipX, double tipY, double lineWidth) {
        double angle = Math.atan2(tipY - fromY, tipX - fromX);
        double headLength = Math.max(9.0, 3.2 * lineWidth);
        double headHalf = headLength * 0.5;
        double baseX = tipX - headLength * Math.cos(angle);
        double baseY = tipY - headLength * Math.sin(angle);
        double perpX = -Math.sin(angle) * headHalf;
        double perpY = Math.cos(angle) * headHalf;
        Path2D head = new Path2D.Double();
        head.moveTo(tipX, tipY);
        head.lineTo(baseX + perpX, baseY + perpY);
        head.lineTo(baseX - perpX, baseY - perpY);
        head.closePath();
        g.fill(head);
    }

    /**
     * Validates and returns the raster of a handle for a region operation.
     * @param fn function name for errors
     * @param handle handle name
     * @param x left edge (0-based, within the raster)
     * @param y top edge (0-based, within the raster)
     * @param width region width (at least 1, within the raster)
     * @param height region height (at least 1, within the raster)
     * @return the raster
     */
    private BufferedImage region(String fn, String handle, int x, int y, int width, int height) {
        OpenedImage opened = requireHandle(handle, fn);
        BufferedImage img = opened.image();
        if (width < 1 || height < 1) {
            throw new JsUserRuntimeException(String.format(Locale.ROOT,
                    "image.%s: width and height must be at least 1 (got %dx%d)",
                    fn, width, height));
        }
        if (x < 0 || y < 0 || (long) x + width > img.getWidth()
                || (long) y + height > img.getHeight()) {
            throw new JsUserRuntimeException(String.format(Locale.ROOT,
                    "image.%s: region %d,%d %dx%d leaves the raster of %dx%d"
                            + " (valid: 0 <= x, x + width <= %d; 0 <= y, y + height <= %d)",
                    fn, x, y, width, height, img.getWidth(), img.getHeight(),
                    img.getWidth(), img.getHeight()));
        }
        if ((long) width * height > MAX_TRANSFER_PIXELS) {
            throw new JsUserRuntimeException(String.format(Locale.ROOT,
                    "image.%s: region %dx%d = %d pixels exceeds the transfer limit of %d pixels",
                    fn, width, height, (long) width * height, MAX_TRANSFER_PIXELS));
        }
        return img;
    }

    /** Rejects a coordinate outside the raster (per-axis check for point-like operations). */
    private static void requireInRaster(String fn, String name, int value, int size) {
        if (value < 0 || value >= size) {
            throw new JsUserRuntimeException(String.format(Locale.ROOT,
                    "image.%s: %s must be within 0..%d (got %d)", fn, name, size - 1, value));
        }
    }

    /** Rejects a coordinate outside 0..size (line endpoints may sit on the border). */
    private static void requireOnBorder(String fn, String name, int value, int size) {
        if (value < 0 || value > size) {
            throw new JsUserRuntimeException(String.format(Locale.ROOT,
                    "image.%s: %s must be within 0..%d (got %d)", fn, name, size, value));
        }
    }

    /** The uniform bad-value error (enumerations in options). */
    private static JsUserRuntimeException badValue(String fn, String name, String expected) {
        return new JsUserRuntimeException(String.format(Locale.ROOT,
                "image.%s: option '%s' must be one of %s", fn, name, expected));
    }

    /** A raster type safe for new images (TYPE_CUSTOM falls back to ARGB). */
    private static int typeOf(BufferedImage img) {
        return img.getType() == BufferedImage.TYPE_CUSTOM
                ? BufferedImage.TYPE_INT_ARGB : img.getType();
    }

    /**
     * True when the raster actually uses its alpha channel (any sampled pixel below full
     * opacity) - a freshly created ARGB raster with an opaque background does not count, so
     * {@code toDataUrl} defaults to JPEG for it. Sampled down to roughly a million lookups.
     * @param img the raster
     * @return whether any sampled pixel is semi- or transparent
     */
    private static boolean usesAlpha(BufferedImage img) {
        if (!img.getColorModel().hasAlpha()) {
            return false;
        }
        int width = img.getWidth();
        int height = img.getHeight();
        long pixels = (long) width * height;
        int step = (int) Math.max(1, Math.round(Math.sqrt(pixels / 1_000_000.0)));
        for (int y = 0; y < height; y += step) {
            for (int x = 0; x < width; x += step) {
                if ((img.getRGB(x, y) >>> 24) != 0xFF) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Packs a raster into row-major RGBA bytes (canvas ImageData order). */
    private static byte[] rasterToRgba(BufferedImage img) {
        int width = img.getWidth();
        int height = img.getHeight();
        int[] argb = img.getRGB(0, 0, width, height, null, 0, width);
        byte[] rgba = new byte[Math.toIntExact((long) width * height * 4)];
        for (int i = 0; i < argb.length; i++) {
            rgba[i * 4] = (byte) ((argb[i] >> 16) & 0xFF);
            rgba[i * 4 + 1] = (byte) ((argb[i] >> 8) & 0xFF);
            rgba[i * 4 + 2] = (byte) (argb[i] & 0xFF);
            rgba[i * 4 + 3] = (byte) ((argb[i] >> 24) & 0xFF);
        }
        return rgba;
    }

    /** Registers a freshly created raster under the next handle and returns the handle. */
    private String registerNewImage(BufferedImage img, String fn) {
        registerRaster(img, null, fn);
        OpenedImage last = null;
        for (OpenedImage opened : images.values()) {
            last = opened;
        }
        String display = last != null ? last.display() : "<created>";
        images.put(nextHandle(), new OpenedImage(img, null, display, "PNG", null, false));
        return handleName(handleCounter);
    }

    /** Rejects text() on a fontless JRE instead of drawing a silent blank. */
    private void ensureFontsAvailable(String fn) {
        if (fontsAvailable == null) {
            try {
                String[] families = GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getAvailableFontFamilyNames();
                fontsAvailable = families != null && families.length > 0;
            } catch (RuntimeException e) {
                LOG.warn("image: font enumeration failed: {}", e.getMessage());
                fontsAvailable = false;
            }
            if (!fontsAvailable) {
                throw new JsUserRuntimeException("image." + fn + ": no fonts are installed in"
                        + " this JRE (headless fontconfig?) - text() would draw nothing;"
                        + " image.fonts() lists what is available");
            }
        }
    }

    // ========================================================================
    // Encoding
    // ========================================================================

    /**
     * Encodes a raster into image bytes.
     * @param img raster (alpha already flattened for JPEG/BMP)
     * @param format {@code png}|{@code jpeg}|{@code gif}|{@code bmp}
     * @param quality JPEG quality 0..1, or &lt; 0 for the encoder default
     * @param display display path for messages
     * @return encoded bytes
     */
    private static byte[] writeImage(BufferedImage img, String format, double quality,
                                     String display) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean ok;
        if (("jpeg".equals(format) || "jpg".equals(format)) && quality >= 0) {
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
            if (!writers.hasNext()) {
                throw new JsUserRuntimeException("image: no JPEG encoder in this JRE");
            }
            ImageWriter writer = writers.next();
            MemoryCacheImageOutputStream os = new MemoryCacheImageOutputStream(out);
            try (os) {
                writer.setOutput(os);
                ImageWriteParam param = writer.getDefaultWriteParam();
                if (param.canWriteCompressed()) {
                    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    param.setCompressionType("JPEG");
                    param.setCompressionQuality((float) quality);
                }
                writer.write(null, new IIOImage(img, null, null), param);
            } catch (IOException e) {
                throw new JsUserRuntimeException("image: JPEG encoding of '" + display
                        + "' failed: " + e.getMessage(), e);
            } finally {
                writer.dispose();
            }
            ok = true;
        } else {
            try {
                ok = ImageIO.write(img, format, out);
            } catch (IOException e) {
                throw new JsUserRuntimeException("image: " + format + " encoding of '" + display
                        + "' failed: " + e.getMessage(), e);
            }
        }
        if (!ok) {
            throw new JsUserRuntimeException(String.format(
                    "image: no encoder for format '%s' in this JRE", format));
        }
        return out.toByteArray();
    }

    /**
     * Draws an alpha raster onto an opaque background (JPEG/BMP have no alpha channel).
     * @param img source raster
     * @param background ARGB background colour (alpha ignored)
     * @return an opaque {@code TYPE_INT_RGB} raster
     */
    private static BufferedImage flatten(BufferedImage img, int background) {
        if (!img.getColorModel().hasAlpha()) {
            return img;
        }
        BufferedImage flat = new BufferedImage(img.getWidth(), img.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = flat.createGraphics();
        try {
            g.setColor(new Color(background));
            g.fillRect(0, 0, flat.getWidth(), flat.getHeight());
            g.drawImage(img, 0, 0, null);
        } finally {
            g.dispose();
        }
        return flat;
    }

    /**
     * Applies an EXIF orientation (1..8) to a raster, so it shows what a viewer shows.
     * @param img decoded raster
     * @param orientation EXIF orientation value
     * @return the transformed raster (or the input for orientation 1)
     */
    private static BufferedImage applyOrientation(BufferedImage img, int orientation) {
        AffineTransform transform;
        int w = img.getWidth();
        int h = img.getHeight();
        switch (orientation) {
            case 2 -> transform = new AffineTransform(-1, 0, 0, 1, w, 0);          // flip H
            case 3 -> transform = new AffineTransform(-1, 0, 0, -1, w, h);         // 180°
            case 4 -> transform = new AffineTransform(1, 0, 0, -1, 0, h);          // flip V
            case 5 -> transform = new AffineTransform(0, 1, 1, 0, 0, 0);           // transpose
            case 6 -> transform = new AffineTransform(0, 1, -1, 0, h, 0);          // 90° CW
            case 7 -> transform = new AffineTransform(0, -1, -1, 0, h, w);         // anti-transpose
            case 8 -> transform = new AffineTransform(0, -1, 1, 0, 0, w);          // 270° CW
            default -> {
                return img;
            }
        }
        boolean swap = orientation >= 5;
        BufferedImage turned = new BufferedImage(swap ? h : w, swap ? w : h, img.getType() == 0
                ? BufferedImage.TYPE_INT_ARGB : img.getType());
        AffineTransformOp op = new AffineTransformOp(transform,
                AffineTransformOp.TYPE_NEAREST_NEIGHBOR);
        return op.filter(img, turned);
    }

    // ========================================================================
    // Handles, colours, options, small helpers
    // ========================================================================

    /** Allocates the next handle name and counts the raster against the call budget. */
    private String nextHandle() {
        handleCounter++;
        return handleName(handleCounter);
    }

    /** Handle name for a counter value. */
    private static String handleName(int counter) {
        return "img-" + counter;
    }

    /** Registers a raster's memory against the per-call budget. */
    private void registerRaster(BufferedImage img, byte[] raw, String fn) {
        long bytes = (long) img.getWidth() * img.getHeight() * 4 + (raw != null ? raw.length : 0);
        long used = images.values().stream().mapToLong(o ->
                (long) o.image().getWidth() * o.image().getHeight() * 4
                        + (o.raw() != null ? o.raw().length : 0)).sum();
        if (used + bytes > RASTER_BUDGET_BYTES) {
            throw new JsUserRuntimeException(String.format(
                    "image.%s: %d bytes of raster memory would exceed the per-call budget of"
                            + " %d bytes - close is automatic at call end, but fewer open images"
                            + " or smaller ones are needed", fn, bytes, RASTER_BUDGET_BYTES));
        }
    }

    /**
     * Resolves a handle to its opened image.
     * @param handle handle name
     * @param fn function name for the error message
     * @return the opened image
     */
    private OpenedImage requireHandle(String handle, String fn) {
        OpenedImage opened = images.get(handle);
        if (opened == null) {
            throw new JsUserRuntimeException(String.format(
                    "image.%s: unknown handle '%s' - open() and create() return handles like '%s'",
                    fn, handle, handleName(Math.max(handleCounter, 1))));
        }
        return opened;
    }

    /**
     * Parses a colour specification into ARGB.
     * @param fn function name for errors
     * @param name argument name for errors
     * @param spec {@code '#rgb'}, {@code '#rgba'}, {@code '#rrggbb'}, {@code '#rrggbbaa'},
     *             {@code 'transparent'} or one of the 16 CSS basic colour names
     * @return ARGB value
     */
    static int parseColor(String fn, String name, String spec) {
        if (spec == null) {
            throw badColor(fn, name, null);
        }
        String s = spec.trim().toLowerCase();
        if (s.startsWith("#")) {
            String hex = s.substring(1);
            try {
                return switch (hex.length()) {
                    case 3 -> Integer.parseInt(expand(hex), 16);
                    case 4 -> Integer.parseInt(expand(hex.substring(0, 3)), 16)
                            | (Integer.parseInt(hex.substring(3, 4), 16) * 0x11) << 24;
                    case 6 -> Integer.parseInt(hex, 16) | 0xFF000000;
                    case 8 -> (int) Long.parseLong(hex, 16);
                    default -> throw badColor(fn, name, spec);
                };
            } catch (NumberFormatException e) {
                throw badColor(fn, name, spec);
            }
        }
        return switch (s) {
            case "black" -> 0xFF000000;
            case "silver" -> 0xFFC0C0C0;
            case "gray", "grey" -> 0xFF808080;
            case "white" -> 0xFFFFFFFF;
            case "maroon" -> 0xFF800000;
            case "red" -> 0xFFFF0000;
            case "purple" -> 0xFF800080;
            case "fuchsia", "magenta" -> 0xFFFF00FF;
            case "green" -> 0xFF008000;
            case "lime" -> 0xFF00FF00;
            case "olive" -> 0xFF808000;
            case "yellow" -> 0xFFFFFF00;
            case "navy" -> 0xFF000080;
            case "blue" -> 0xFF0000FF;
            case "teal" -> 0xFF008080;
            case "aqua", "cyan" -> 0xFF00FFFF;
            case "transparent" -> 0x00000000;
            default -> throw badColor(fn, name, spec);
        };
    }

    /** Expands '#abc' to 'aabbcc' for parsing. */
    private static String expand(String hex) {
        return "" + hex.charAt(0) + hex.charAt(0)
                + hex.charAt(1) + hex.charAt(1)
                + hex.charAt(2) + hex.charAt(2);
    }

    /** The uniform colour-parse error. */
    private static JsUserRuntimeException badColor(String fn, String name, String spec) {
        return new JsUserRuntimeException(String.format(
                "image.%s: %s must be a colour like '#rgb', '#rrggbb', '#rrggbbaa',"
                        + " 'transparent' or a CSS basic name (got %s)",
                fn, name, spec == null ? "null" : "'" + spec + "'"));
    }

    /** Luminance (Rec. 601 weights, 0..255). */
    private static int luminance(int r, int g, int b) {
        return (r * 77 + g * 150 + b * 29) >> 8;
    }

    /** Maximum of three ints. */
    private static int maxOf(int a, int b, int c) {
        return Math.max(a, Math.max(b, c));
    }

    /** Minimum of three ints. */
    private static int minOf(int a, int b, int c) {
        return Math.min(a, Math.min(b, c));
    }

    /** Rounds to {@code digits} decimals for the result maps. */
    private static double round(double value, int digits) {
        double scale = Math.pow(10, digits);
        return Math.round(value * scale) / scale;
    }

    private static int u16be(byte[] data, int off) {
        return ((data[off] & 0xFF) << 8) | (data[off + 1] & 0xFF);
    }

    private static int u16le(byte[] data, int off) {
        return ((data[off + 1] & 0xFF) << 8) | (data[off] & 0xFF);
    }

    private static int u32be(byte[] data, int off) {
        return (u16be(data, off) << 16) | u16be(data, off + 2);
    }

    private static int s32le(byte[] data, int off) {
        return (u16le(data, off + 2) << 16) | u16le(data, off);
    }

    private static int u16(int off, boolean le, byte[] data) {
        return le ? u16le(data, off) : u16be(data, off);
    }

    private static int u32(int off, boolean le, byte[] data) {
        return le ? s32le(data, off) : u32be(data, off);
    }

    // ========================================================================
    // Options helpers (strict: unknown keys are rejected)
    // ========================================================================

    /** Rejects unknown option keys with the standard message style. */
    private static void requireKnown(String fn, Map<String, Object> options, String... known) {
        if (options == null) {
            return;
        }
        Set<String> allowed = Set.of(known);
        for (String key : options.keySet()) {
            if (!allowed.contains(key)) {
                throw new JsUserRuntimeException(String.format(
                        "image.%s: unknown option '%s' (known: %s)",
                        fn, key, known.length == 0 ? "(none)" : String.join(", ", known)));
            }
        }
    }

    private static boolean boolOpt(String fn, Map<String, Object> options, String key,
                                   boolean fallback) {
        Object value = options == null ? null : options.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        throw new JsUserRuntimeException(String.format(
                "image.%s: option '%s' must be a boolean (got %s)", fn, key, describe(value)));
    }

    private static int intOpt(String fn, Map<String, Object> options, String key, int fallback,
                              int min, int max) {
        Object value = options == null ? null : options.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            long v = number.longValue();
            if (v < min || v > max) {
                throw new JsUserRuntimeException(String.format(
                        "image.%s: option '%s' must be between %d and %d (got %d)",
                        fn, key, min, max, v));
            }
            return (int) v;
        }
        throw new JsUserRuntimeException(String.format(
                "image.%s: option '%s' must be a number (got %s)", fn, key, describe(value)));
    }

    private static double doubleOpt(String fn, Map<String, Object> options, String key,
                                    double fallback, double min, double max) {
        Object value = options == null ? null : options.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            double v = number.doubleValue();
            if (v < min || v > max) {
                throw new JsUserRuntimeException(String.format(
                        "image.%s: option '%s' must be between %s and %s (got %s)",
                        fn, key, min, max, v));
            }
            return v;
        }
        throw new JsUserRuntimeException(String.format(
                "image.%s: option '%s' must be a number (got %s)", fn, key, describe(value)));
    }

    private static String stringOpt(String fn, Map<String, Object> options, String key,
                                    String fallback) {
        Object value = options == null ? null : options.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof String s) {
            return s;
        }
        throw new JsUserRuntimeException(String.format(
                "image.%s: option '%s' must be a string (got %s)", fn, key, describe(value)));
    }

    /** Names an unexpected option value for the error messages. */
    private static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            return "'" + s + "'";
        }
        return value.getClass().getSimpleName() + " " + value;
    }
}
