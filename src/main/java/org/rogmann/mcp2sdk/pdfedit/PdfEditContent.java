package org.rogmann.mcp2sdk.pdfedit;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceCMYK;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.util.Matrix;
import org.rogmann.mcp2sdk.js.JsFileSystem;
import org.rogmann.mcp2sdk.js.JsUserRuntimeException;
import org.rogmann.mcp2sdk.pdf.geo.PdfMatrix;
import org.rogmann.mcp2sdk.pdf.geo.PdfPageSpace;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * The structured content ops: {@code text.put}, {@code text.block}, {@code draw.rect},
 * {@code draw.line} and {@code image.put}.
 * <p>
 * These are the ops that make a title a title: they write operators, but the caller writes
 * coordinates, text and a font name. Everything they need beyond the operators - the font
 * resource, the image XObject, the graphics state for an opacity - they wire into the page
 * themselves, which is the whole difference to {@code content.append} (that one writes bytes and
 * touches nothing).
 * </p>
 * <p>
 * Three house rules run through all five:
 * </p>
 * <ul>
 *   <li><b>No silent default for the space.</b> {@code space} is mandatory and the answer echoes
 *       what the numbers became (§3.4).</li>
 *   <li><b>Nothing leaks to sibling pages.</b> {@code /Resources} is inheritable, so the page gets
 *       its own copy before the first entry is added - otherwise a font added for page 3 would
 *       appear in the resource dictionary of every page that inherits from the same node.</li>
 *   <li><b>A character the font cannot show is an error.</b> Writing it anyway would put a
 *       question mark (or nothing) into the document.</li>
 * </ul>
 * <p>
 * The content is written through {@link PDPageContentStream} in append mode with
 * {@code resetContext}: the existing content stays byte for byte, and the new stream starts from a
 * restored graphics state, so an unbalanced {@code q} in the page's own content cannot distort what
 * this module writes. The new stream is uncompressed - the result stays readable for
 * {@code pdf.decodeStream()} and {@code pdf.operations()}, which are the next steps of every
 * recipe.
 * </p>
 */
final class PdfEditContent {

    /** Named colours an op may write instead of components. */
    private static final Map<String, float[]> NAMED_COLORS = namedColors();

    /** Default text size when an op does not name one (points). */
    private static final float DEFAULT_SIZE = 12f;

    /** Line height as a multiple of the text size when {@code text.block} names no leading. */
    private static final float DEFAULT_LEADING_FACTOR = 1.2f;

    private PdfEditContent() {
        // Utility class
    }

    private static Map<String, float[]> namedColors() {
        Map<String, float[]> map = new LinkedHashMap<>();
        map.put("black", new float[]{0, 0, 0});
        map.put("white", new float[]{1, 1, 1});
        map.put("red", new float[]{1, 0, 0});
        map.put("green", new float[]{0, 0.5f, 0});
        map.put("blue", new float[]{0, 0, 1});
        map.put("yellow", new float[]{1, 1, 0});
        map.put("orange", new float[]{1, 0.5f, 0});
        map.put("purple", new float[]{0.5f, 0, 0.5f});
        map.put("brown", new float[]{0.6f, 0.3f, 0});
        map.put("pink", new float[]{1, 0.75f, 0.8f});
        map.put("gray", new float[]{0.5f, 0.5f, 0.5f});
        map.put("grey", new float[]{0.5f, 0.5f, 0.5f});
        map.put("lightgray", new float[]{0.83f, 0.83f, 0.83f});
        map.put("lightgrey", new float[]{0.83f, 0.83f, 0.83f});
        map.put("darkgray", new float[]{0.25f, 0.25f, 0.25f});
        map.put("darkgrey", new float[]{0.25f, 0.25f, 0.25f});
        map.put("navy", new float[]{0, 0, 0.5f});
        map.put("teal", new float[]{0, 0.5f, 0.5f});
        map.put("lime", new float[]{0, 1, 0});
        return Map.copyOf(map);
    }

    // -------------------------------------------------------------------
    // text.put
    // -------------------------------------------------------------------

    /**
     * Writes one line of text at a coordinate.
     * <p>
     * "One line" is the whole promise: no wrapping, no kerning beyond the font's widths, no
     * hyphenation. The anchor is the start of the baseline - a line placed at
     * {@code space:'page', y:70} has its capitals roughly 70 points below the top edge.
     * </p>
     * @param ctx the call context
     * @param op the op
     */
    static void textPut(PdfEditContext ctx, PdfEditOp op) {
        op.allow("page", "space", "x", "y", "text", "font", "size", "color", "rotate",
                "charSpacing", "wordSpacing", "unit");
        int pageNum = op.page("page");
        String space = PdfEditGeometry.space(op);
        double factor = PdfEditGeometry.unitFactor(op);
        String text = text(op);
        if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            throw PdfEditException.usage(op.where() + ": 'text' holds a line break",
                    "text.put writes ONE line at a coordinate - text.block wraps a paragraph"
                            + " and takes a line break in its text as a new line");
        }
        double givenX = length(op, "x");
        double givenY = length(op, "y");
        double x = givenX * factor;
        double y = givenY * factor;
        float size = size(op);
        double rotate = number(op, "rotate", 0d);
        Float charSpacing = floatOrNull(op, "charSpacing");
        Float wordSpacing = floatOrNull(op, "wordSpacing");
        PDColor color = colorOrNull(op, "color");
        PDPage page = PdfEditPages.requirePage(ctx, pageNum);
        PdfEditFonts.Choice choice = PdfEditFonts.choose(ctx, page, op);
        PDFont font = choice.font();
        PdfEditFonts.requireEncodable(font, text, op);
        PdfPageSpace frame = PdfEditGeometry.frame(page, op, pageNum);
        // The font goes into the page's /Resources - which have to be the page's own before.
        ctx.ownResources(page);
        // Word spacing is a Tw for a simple font and a TJ adjustment for a Type 0 font (whose
        // codes are two bytes wide, so code 32 - the one Tw applies to - never occurs).
        boolean gapByTj = wordSpacing != null && wordSpacing != 0 && font instanceof PDType0Font;

        try (PDPageContentStream out = open(ctx, page)) {
            out.beginText();
            out.setFont(font, size);
            if (color != null) {
                out.setNonStrokingColor(color);
            }
            if (charSpacing != null) {
                out.setCharacterSpacing(charSpacing);
            }
            if (wordSpacing != null && wordSpacing != 0 && !gapByTj) {
                out.setWordSpacing(wordSpacing);
            }
            out.setTextMatrix(matrix(PdfEditGeometry.textMatrix(frame, space, x, y, rotate, op)));
            if (gapByTj) {
                out.showTextWithPositioning(spaced(text, wordSpacing, size));
            } else {
                out.showText(text);
            }
            out.endText();
        } catch (IOException e) {
            throw broken(op, pageNum, e);
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw broken(op, pageNum, e);
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("page", (long) pageNum);
        details.put("objNum", PdfEditRaw.pageObjNum(page));
        details.put("text", shorten(text));
        details.put("font", PdfEditFonts.nameOf(font));
        details.put("fontSource", choice.source());
        details.put("size", (double) size);
        if (wordSpacing != null) {
            details.put("wordSpacing", (double) wordSpacing);
            details.put("wordSpacingMode", gapByTj ? "TJ" : "Tw");
            if (gapByTj) {
                ctx.note("text.put: word spacing on an embedded TrueType font is written as TJ"
                        + " adjustments - a Tw would have no effect on a Type 0 font, whose codes"
                        + " are two bytes wide, so code 32 never appears as a single-byte code");
            }
        }
        ctx.putGeometry(PdfEditGeometry.echo(op, pageNum, space, new double[]{givenX, givenY},
                PdfEditGeometry.toUser(frame, space, x, y, op), frame,
                PdfEditGeometry.unitName(op)));
        ctx.changed(op, "text written (" + text.length() + " characters, "
                + PdfEditFonts.nameOf(font) + " " + format(size) + ")", details);
        if (rotate != 0) {
            ctx.note("text.put rotated by " + format(rotate) + " degrees counterclockwise: the"
                    + " anchor stays where the op put it and the line turns around it");
        }
    }

    // -------------------------------------------------------------------
    // text.block
    // -------------------------------------------------------------------

    /**
     * Writes a paragraph: word-wrapped into one column, line by line.
     * <p>
     * What it is not: justified, hyphenated or balanced. A word that does not fit into the column
     * is placed alone on its line and keeps overflowing - that is reported, because a silently
     * clipped footer is the kind of thing one only sees in the printed document.
     * </p>
     * @param ctx the call context
     * @param op the op
     */
    static void textBlock(PdfEditContext ctx, PdfEditOp op) {
        op.allow("page", "space", "x", "y", "width", "text", "font", "size", "leading",
                "color", "align", "unit");
        int pageNum = op.page("page");
        String space = PdfEditGeometry.space(op);
        double factor = PdfEditGeometry.unitFactor(op);
        String text = text(op);
        double givenX = length(op, "x");
        double givenY = length(op, "y");
        double x = givenX * factor;
        double y = givenY * factor;
        double width = length(op, "width") * factor;
        if (width <= 0) {
            throw PdfEditException.usage(op.where() + ": width is " + format(width),
                    "width is the column width in the op's unit - it has to be positive");
        }
        float size = size(op);
        float leading = leading(op, size);
        String align = align(op);
        PDColor color = colorOrNull(op, "color");
        PDPage page = PdfEditPages.requirePage(ctx, pageNum);
        PdfEditFonts.Choice choice = PdfEditFonts.choose(ctx, page, op);
        PDFont font = choice.font();
        // The words go into the font, the whitespace does not - a paragraph with a line break in
        // it is not "a character WinAnsi cannot show".
        PdfEditFonts.requireEncodableWords(font, text, op);
        List<String> lines = wrap(font, size, width, text);
        PdfPageSpace frame = PdfEditGeometry.frame(page, op, pageNum);
        // The font goes into the page's /Resources - which have to be the page's own before.
        ctx.ownResources(page);

        float widest = 0;
        try (PDPageContentStream out = open(ctx, page)) {
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                float lineWidth = widthOf(font, size, line);
                widest = Math.max(widest, lineWidth);
                double lineX = x + offset(align, width, lineWidth);
                double lineY = y + i * (double) leading;
                out.beginText();
                out.setFont(font, size);
                if (color != null) {
                    out.setNonStrokingColor(color);
                }
                out.setTextMatrix(matrix(
                        PdfEditGeometry.textMatrix(frame, space, lineX, lineY, 0, op)));
                out.showText(line);
                out.endText();
            }
        } catch (IOException e) {
            throw broken(op, pageNum, e);
        } catch (IllegalStateException e) {
            throw broken(op, pageNum, e);
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("page", (long) pageNum);
        details.put("objNum", PdfEditRaw.pageObjNum(page));
        details.put("lines", (long) lines.size());
        details.put("font", PdfEditFonts.nameOf(font));
        details.put("fontSource", choice.source());
        details.put("size", (double) size);
        details.put("leading", (double) leading);
        details.put("align", align);
        ctx.putGeometry(PdfEditGeometry.echo(op, pageNum, space, new double[]{givenX, givenY},
                PdfEditGeometry.toUser(frame, space, x, y, op), frame,
                PdfEditGeometry.unitName(op)));
        ctx.changed(op, "text block written (" + lines.size() + " line(s), "
                + PdfEditFonts.nameOf(font) + " " + format(size) + ")", details);
        if (widest > width + 0.01) {
            ctx.note("text.block: the widest line is " + format(widest) + " pt wide and the"
                    + " column is " + format(width) + " pt - a word that does not fit is not"
                    + " broken (no hyphenation), so the line runs over");
        }
    }

    // -------------------------------------------------------------------
    // text.measure
    // -------------------------------------------------------------------

    /**
     * Measures strings with a font and writes nothing.
     * <p>
     * The numbers are the ones {@code text.put} produces: the same font object, the same width
     * source (PDFBox' AFM table for a standard font, the programme's {@code hmtx} for an embedded
     * file) and the same rules for {@code charSpacing} and {@code wordSpacing}. That is the whole
     * point of the op: a caller that lays out a table has to know a cell's width <em>before</em> it
     * writes the cell, and a second implementation of "how wide is this word" would put the line
     * somewhere else than the writer.
     * </p>
     * <p>
     * Nothing is embedded. An embedded file is loaded into a scratch document that is closed at the
     * end of the op, so a measuring call does not grow the result; a standard font needs no
     * document at all. The answer goes to {@code measured[]} and not to {@code changed[]}, because
     * a measured width is arithmetic, not something the file holds.
     * </p>
     * @param ctx the call context
     * @param op the op
     */
    static void textMeasure(PdfEditContext ctx, PdfEditOp op) {
        op.allow("text", "texts", "font", "size", "charSpacing", "wordSpacing", "page");
        List<String> texts = measureTexts(op);
        float size = size(op);
        Float charSpacing = floatOrNull(op, "charSpacing");
        Float wordSpacing = floatOrNull(op, "wordSpacing");
        try (PDDocument scratch = new PDDocument()) {
            PdfEditFonts.Choice choice = metricsFont(ctx, scratch, op);
            PDFont font = choice.font();
            List<Object> widths = new ArrayList<>(texts.size());
            int chars = 0;
            double widest = 0;
            for (String text : texts) {
                PdfEditFonts.requireEncodable(font, text, op);
                chars += text.length();
                double width = round(widthOf(font, size, text)
                        + spacingWidth(text, charSpacing, wordSpacing));
                widths.add(width);
                widest = Math.max(widest, width);
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("what", "measured " + texts.size() + " string(s) ("
                    + PdfEditFonts.nameOf(font) + " " + format(size) + ") - nothing written");
            details.put("font", PdfEditFonts.nameOf(font));
            details.put("fontSource", choice.source());
            details.put("size", (double) size);
            details.put("count", (long) texts.size());
            details.put("chars", (long) chars);
            details.put("widths", widths);
            details.put("widest", round(widest));
            verticalMetrics(font, size, details);
            ctx.measured(op, details);
        } catch (IOException e) {
            throw new PdfEditException("broken", op.where() + ": the scratch document of the"
                    + " measurement could not be closed: " + e.getMessage(),
                    "this is a fault of the writer, not of the text", e);
        }
    }

    /**
     * Resolves the font a measurement runs against, without touching the document being written.
     * @param ctx the call context
     * @param scratch the document that holds an embedded file while the measurement runs
     * @param op the op
     * @return the font and where it came from
     */
    private static PdfEditFonts.Choice metricsFont(PdfEditContext ctx, PDDocument scratch,
                                                   PdfEditOp op) {
        Object raw = op.params().get("font");
        if (raw instanceof Map<?, ?>) {
            Map<String, Object> spec = op.map("font");
            if (spec.containsKey("file")) {
                return PdfEditFonts.measureFont(scratch, spec, op);
            }
            if (spec.containsKey("objNum")) {
                Integer pageNum = op.intOrNull("page");
                if (pageNum == null) {
                    throw PdfEditException.usage(op.where() + ": {objNum:…} needs 'page'",
                            "a font of the page is found through the page - name it, or measure"
                                    + " with a standard font or a file ({file:'…ttf'})");
                }
                return PdfEditFonts.choose(ctx, PdfEditPages.requirePage(ctx, pageNum), op);
            }
        }
        return PdfEditFonts.choose(ctx, null, op);
    }

    /**
     * Reads the strings a measurement is about: one in {@code text} or several in {@code texts}.
     * @param op the op
     * @return the strings, in the order the caller wrote them
     */
    private static List<String> measureTexts(PdfEditOp op) {
        String single = op.stringOrNull("text");
        Object raw = op.params().get("texts");
        if (single != null && raw != null) {
            throw PdfEditException.usage(op.where() + ": 'text' and 'texts' are both given",
                    "text.measure takes one string in 'text' or several in 'texts' - two forms,"
                            + " not two copies");
        }
        if (single != null) {
            requireSingleLine(single, "text", op);
            return List.of(single);
        }
        if (raw == null) {
            throw PdfEditException.usage(op.where() + ": neither 'text' nor 'texts'",
                    "text.measure measures 'text' (one string) or 'texts' (an array of strings),"
                            + " e.g. texts:['Nr.','Leistung','Betrag']");
        }
        if (!(raw instanceof List<?> list)) {
            throw PdfEditException.usage(op.where() + ": texts is " + PdfEditOp.describe(raw),
                    "texts is an array of strings, e.g. texts:['Nr.','Leistung','Betrag']");
        }
        if (list.size() > PdfEditLimits.MAX_MEASURE_TEXTS) {
            throw new PdfEditException("limit", op.where() + ": " + list.size() + " strings",
                    "at most " + PdfEditLimits.MAX_MEASURE_TEXTS + " strings per measurement -"
                            + " measure per page instead of per document");
        }
        List<String> texts = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String text)) {
                throw PdfEditException.usage(op.where() + ": texts[" + texts.size() + "] is "
                        + PdfEditOp.describe(item), "texts is an array of strings");
            }
            requireSingleLine(text, "texts[" + texts.size() + "]", op);
            if (text.length() > PdfEditLimits.MAX_TEXT_CHARS) {
                throw new PdfEditException("limit", op.where() + ": texts[" + texts.size() + "]"
                        + " has " + text.length() + " characters",
                        "at most " + PdfEditLimits.MAX_TEXT_CHARS + " characters per string");
            }
            texts.add(text);
        }
        if (texts.isEmpty()) {
            throw PdfEditException.usage(op.where() + ": texts is empty",
                    "measure at least one string - an empty array has no widths to answer");
        }
        return texts;
    }

    /**
     * Refuses a string that holds a line break: a measurement is about one line.
     * @param text the string
     * @param what the name the error uses ({@code text} or {@code texts[3]})
     * @param op the op
     */
    private static void requireSingleLine(String text, String what, PdfEditOp op) {
        if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            throw PdfEditException.usage(op.where() + ": '" + what + "' holds a line break",
                    "text.measure measures ONE line per string - pass the lines as separate"
                            + " entries of 'texts'");
        }
    }

    /**
     * What {@code charSpacing} and {@code wordSpacing} add to a string's width.
     * <p>
     * The rules are the writer's: {@code Tc} is added after every glyph (code point, not UTF-16
     * unit) and word spacing once per space. {@code text.put} writes word spacing as {@code Tw} for
     * a simple font and as {@code TJ} adjustments for a Type 0 font - the effect is the same, which
     * is why one rule covers both.
     * </p>
     * @param text the string
     * @param charSpacing the character spacing ({@code Tc}) or {@code null}
     * @param wordSpacing the word spacing or {@code null}
     * @return the added width in points
     */
    private static double spacingWidth(String text, Float charSpacing, Float wordSpacing) {
        double width = 0;
        if (charSpacing != null) {
            width += (double) charSpacing * text.codePointCount(0, text.length());
        }
        if (wordSpacing != null) {
            width += (double) wordSpacing * spaces(text);
        }
        return width;
    }

    /**
     * Counts the spaces of a string.
     * @param text the string
     * @return the number of {@code ' '} characters
     */
    private static int spaces(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == ' ') {
                count++;
            }
        }
        return count;
    }

    /**
     * Splits a line into the pieces of a {@code TJ} array so that every space gets an extra gap of
     * {@code gap} points.
     * <p>
     * The strings keep their spaces - the space glyph advances as it should - and the number after
     * a run of spaces moves the pen by {@code -n/1000 × size}, which is the gap. That is the way to
     * space words on a Type 0 font, where {@code Tw} does nothing (see the note in
     * {@link #textPut}).
     * </p>
     * @param text the line
     * @param gap the extra width per space, in points
     * @param size the text size
     * @return the pieces (strings and numbers) of the {@code TJ} array
     */
    private static Object[] spaced(String text, float gap, float size) {
        List<Object> pieces = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            int wordEnd = i;
            while (wordEnd < text.length() && text.charAt(wordEnd) != ' ') {
                wordEnd++;
            }
            int runEnd = wordEnd;
            while (runEnd < text.length() && text.charAt(runEnd) == ' ') {
                runEnd++;
            }
            pieces.add(text.substring(i, runEnd));
            if (runEnd > wordEnd && runEnd < text.length()) {
                // PDFBox takes Float, not Double ("Argument must consist of array of Float and
                // String types") - the value is -gap/1000 of the text size, in text units.
                pieces.add((float) (-(runEnd - wordEnd) * gap * 1000d / size));
            }
            i = runEnd;
        }
        return pieces.toArray();
    }

    /**
     * Adds the vertical metrics of a font at a text size to the answer - what a caller needs to
     * pick a leading or to place the first baseline of a box. A font whose descriptor has no value
     * contributes no key at all (a number that is not there is better than an invented zero).
     * @param font the font
     * @param size the text size
     * @param details the answer entry
     */
    private static void verticalMetrics(PDFont font, float size, Map<String, Object> details) {
        PDFontDescriptor descriptor = font.getFontDescriptor();
        if (descriptor == null) {
            return;
        }
        if (descriptor.getAscent() != 0) {
            details.put("ascent", round(descriptor.getAscent() * size / 1000d));
        }
        if (descriptor.getDescent() != 0) {
            details.put("descent", round(descriptor.getDescent() * size / 1000d));
        }
        if (descriptor.getCapHeight() != 0) {
            details.put("capHeight", round(descriptor.getCapHeight() * size / 1000d));
        }
        if (descriptor.getXHeight() != 0) {
            details.put("xHeight", round(descriptor.getXHeight() * size / 1000d));
        }
    }

    /**
     * Rounds a measured length. The reader prints four decimals, so a width that differs in the
     * fifth is the same width - and a hundred of them read better as {@code 12.3456} than as
     * {@code 12.345600000000001}.
     * @param value the length in points
     * @return the rounded length
     */
    private static double round(double value) {
        return Math.round(value * 10_000d) / 10_000d;
    }

    /**
     * Splits the text into lines that fit the column, breaking at word boundaries.
     * @param font the font (for the widths)
     * @param size the text size
     * @param width the column width in points
     * @param text the text; a line break in the text starts a new line
     * @return the lines (at least one)
     */
    private static List<String> wrap(PDFont font, float size, double width, String text) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\r\n|\n|\r", -1)) {
            String[] words = paragraph.trim().isEmpty() ? new String[0]
                    : paragraph.trim().split("\\s+");
            StringBuilder current = new StringBuilder();
            for (String word : words) {
                String candidate = current.isEmpty() ? word : current + " " + word;
                if (!current.isEmpty() && widthOf(font, size, candidate) > width) {
                    lines.add(current.toString());
                    current.setLength(0);
                    current.append(word);
                } else {
                    current.setLength(0);
                    current.append(candidate);
                }
            }
            if (!current.isEmpty()) {
                lines.add(current.toString());
            }
        }
        if (lines.isEmpty()) {
            lines.add("");
        }
        return lines;
    }

    /**
     * The width of a string in points.
     * @param font the font
     * @param size the text size
     * @param text the text
     * @return the width
     */
    private static float widthOf(PDFont font, float size, String text) {
        try {
            return font.getStringWidth(text) * size / 1000f;
        } catch (IOException e) {
            throw new PdfEditException("broken", "the width of '" + text + "' could not be read"
                    + " from the font", "this is a fault of the font, not of the text", e);
        }
    }

    /**
     * The x offset of an aligned line inside the column.
     * @param align {@code left}, {@code center} or {@code right}
     * @param width the column width
     * @param lineWidth the width of the line
     * @return the offset
     */
    private static double offset(String align, double width, double lineWidth) {
        return switch (align) {
            case "center" -> (width - lineWidth) / 2d;
            case "right" -> width - lineWidth;
            default -> 0d;
        };
    }

    private static String align(PdfEditOp op) {
        String value = op.stringOrNull("align");
        if (value == null) {
            return "left";
        }
        String align = value.trim().toLowerCase(Locale.ROOT);
        if (align.startsWith("cent")) {
            return "center";
        }
        if (align.equals("left") || align.equals("right")) {
            return align;
        }
        throw PdfEditException.usage(op.where() + ": align reads '" + value + "'",
                "align is 'left' (the default), 'center' or 'right' - justification is not"
                        + " implemented (that is typesetting)");
    }

    private static float leading(PdfEditOp op, float size) {
        Double value = op.doubleOrNull("leading");
        if (value == null) {
            return size * DEFAULT_LEADING_FACTOR;
        }
        if (value <= 0) {
            throw PdfEditException.usage(op.where() + ": leading is " + format(value),
                    "leading is the distance between two baselines in points - it has to be"
                            + " positive");
        }
        return value.floatValue();
    }

    // -------------------------------------------------------------------
    // draw.rect / draw.line
    // -------------------------------------------------------------------

    /**
     * Draws a rectangle, filled, stroked or both. Without {@code fill} and {@code stroke} it is a
     * black outline - the shape a caller means by "a box around this".
     * @param ctx the call context
     * @param op the op
     */
    static void drawRect(PdfEditContext ctx, PdfEditOp op) {
        op.allow("page", "space", "x", "y", "width", "height", "fill", "stroke", "lineWidth",
                "unit");
        int pageNum = op.page("page");
        String space = PdfEditGeometry.space(op);
        double factor = PdfEditGeometry.unitFactor(op);
        double givenX = length(op, "x");
        double givenY = length(op, "y");
        double x = givenX * factor;
        double y = givenY * factor;
        double width = length(op, "width") * factor;
        double height = length(op, "height") * factor;
        PDColor fill = colorOrNull(op, "fill");
        PDColor stroke = colorOrNull(op, "stroke");
        if (fill == null && stroke == null) {
            stroke = color("black", op, "stroke");
        }
        Float lineWidth = floatOrNull(op, "lineWidth");
        PDPage page = PdfEditPages.requirePage(ctx, pageNum);
        PdfPageSpace frame = PdfEditGeometry.frame(page, op, pageNum);

        try (PDPageContentStream out = open(ctx, page)) {
            applySpace(out, frame, space, op);
            if (lineWidth != null) {
                out.setLineWidth(lineWidth);
            }
            if (fill != null) {
                out.setNonStrokingColor(fill);
            }
            if (stroke != null) {
                out.setStrokingColor(stroke);
            }
            out.addRect((float) x, (float) y, (float) width, (float) height);
            if (fill != null && stroke != null) {
                out.fillAndStroke();
            } else if (fill != null) {
                out.fill();
            } else {
                out.stroke();
            }
        } catch (IOException e) {
            throw broken(op, pageNum, e);
        } catch (IllegalStateException e) {
            throw broken(op, pageNum, e);
        }

        String mode = fill != null && stroke != null ? "fillAndStroke"
                : fill != null ? "fill" : "stroke";
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("page", (long) pageNum);
        details.put("objNum", PdfEditRaw.pageObjNum(page));
        details.put("width", PdfEditGeometry.round(width));
        details.put("height", PdfEditGeometry.round(height));
        details.put("mode", mode);
        ctx.putGeometry(PdfEditGeometry.echo(op, pageNum, space, new double[]{givenX, givenY},
                PdfEditGeometry.toUser(frame, space, x, y, op), frame,
                PdfEditGeometry.unitName(op)));
        ctx.changed(op, "rectangle drawn (" + format(width) + " × " + format(height) + " pt, "
                + mode + ")", details);
        if (fill != null && "black".equalsIgnoreCase(String.valueOf(op.params().get("fill")))) {
            ctx.note("draw.rect with fill:'black' covers, it does not remove: the text below is"
                    + " still there, copyable and searchable (pdf.text() on the result proves it)"
                    + " - removing content is content.cut");
        }
    }

    /**
     * Draws a line.
     * @param ctx the call context
     * @param op the op
     */
    static void drawLine(PdfEditContext ctx, PdfEditOp op) {
        op.allow("page", "space", "x1", "y1", "x2", "y2", "color", "lineWidth", "dash", "unit");
        int pageNum = op.page("page");
        String space = PdfEditGeometry.space(op);
        double factor = PdfEditGeometry.unitFactor(op);
        double givenX = length(op, "x1");
        double givenY = length(op, "y1");
        double x1 = givenX * factor;
        double y1 = givenY * factor;
        double x2 = length(op, "x2") * factor;
        double y2 = length(op, "y2") * factor;
        PDColor color = colorOrNull(op, "color");
        if (color == null) {
            color = color("black", op, "color");
        }
        Float lineWidth = floatOrNull(op, "lineWidth");
        float[] dash = dash(op);
        PDPage page = PdfEditPages.requirePage(ctx, pageNum);
        PdfPageSpace frame = PdfEditGeometry.frame(page, op, pageNum);

        try (PDPageContentStream out = open(ctx, page)) {
            applySpace(out, frame, space, op);
            if (lineWidth != null) {
                out.setLineWidth(lineWidth);
            }
            if (dash != null) {
                out.setLineDashPattern(dash, 0f);
            }
            out.setStrokingColor(color);
            out.moveTo((float) x1, (float) y1);
            out.lineTo((float) x2, (float) y2);
            out.stroke();
        } catch (IOException e) {
            throw broken(op, pageNum, e);
        } catch (IllegalStateException e) {
            throw broken(op, pageNum, e);
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("page", (long) pageNum);
        details.put("objNum", PdfEditRaw.pageObjNum(page));
        details.put("from", List.of(PdfEditGeometry.round(x1), PdfEditGeometry.round(y1)));
        details.put("to", List.of(PdfEditGeometry.round(x2), PdfEditGeometry.round(y2)));
        ctx.putGeometry(PdfEditGeometry.echo(op, pageNum, space, new double[]{givenX, givenY},
                PdfEditGeometry.toUser(frame, space, x1, y1, op), frame,
                PdfEditGeometry.unitName(op)));
        ctx.changed(op, "line drawn (" + format(x1) + "," + format(y1) + " → " + format(x2) + ","
                + format(y2) + ")", details);
    }

    /**
     * Reads the {@code dash} parameter: dash and gap lengths in points, empty for a solid line.
     * @param op the op
     * @return the pattern, or {@code null} for "not given" or "all zero"
     */
    private static float[] dash(PdfEditOp op) {
        if (op.params().get("dash") == null) {
            return null;
        }
        List<?> values = op.list("dash");
        float[] pattern = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            if (!(value instanceof Number number) || number.doubleValue() < 0) {
                throw PdfEditException.usage(op.where() + ": dash[" + i + "] is "
                        + PdfEditOp.describe(value),
                        "dash is an array of lengths in points, e.g. [3,2] - an empty array"
                                + " draws a solid line");
            }
            pattern[i] = number.floatValue();
        }
        for (float length : pattern) {
            if (length > 0) {
                return pattern;
            }
        }
        return null;
    }

    // -------------------------------------------------------------------
    // image.put
    // -------------------------------------------------------------------

    /**
     * Places a PNG or JPEG image.
     * <p>
     * A JPEG is embedded as it is (its DCT data is the stream); everything else goes through
     * {@link LosslessFactory}, which also builds an {@code /SMask} for transparency. The image
     * becomes an {@code /XObject} of the page and is drawn with the matrix that maps its unit
     * square onto the rectangle the op names - including the optional rotation, which turns the
     * image around its own centre.
     * </p>
     * @param ctx the call context
     * @param op the op
     */
    static void imagePut(PdfEditContext ctx, PdfEditOp op) {
        op.allow("page", "space", "x", "y", "width", "height", "data", "path", "format",
                "rotate", "opacity", "unit");
        int pageNum = op.page("page");
        String space = PdfEditGeometry.space(op);
        double factor = PdfEditGeometry.unitFactor(op);
        double givenX = length(op, "x");
        double givenY = length(op, "y");
        double x = givenX * factor;
        double y = givenY * factor;
        double width = length(op, "width") * factor;
        double height = length(op, "height") * factor;
        if (width <= 0 || height <= 0) {
            throw PdfEditException.usage(op.where() + ": image size " + format(width) + " × "
                    + format(height), "width and height are positive numbers");
        }
        byte[] data = imageData(op);
        String format = format(op, data);
        double rotate = number(op, "rotate", 0d);
        Float opacity = floatOrNull(op, "opacity");
        if (opacity != null && (opacity < 0f || opacity > 1f)) {
            throw PdfEditException.usage(op.where() + ": opacity is " + format(opacity),
                    "opacity is between 0 (invisible) and 1 (opaque)");
        }
        PDPage page = PdfEditPages.requirePage(ctx, pageNum);
        PdfPageSpace frame = PdfEditGeometry.frame(page, op, pageNum);
        PDImageXObject image = image(ctx.document(), data, format, op);
        // The page needs its own /Resources before the image is added to them.
        ctx.ownResources(page);

        try (PDPageContentStream out = open(ctx, page)) {
            out.saveGraphicsState();
            if (opacity != null) {
                PDExtendedGraphicsState state = new PDExtendedGraphicsState();
                state.setNonStrokingAlphaConstant(opacity);
                state.setStrokingAlphaConstant(opacity);
                out.setGraphicsStateParameters(state);
            }
            out.drawImage(image, matrix(imageMatrix(frame, space, x, y, width, height, rotate,
                    op)));
            out.restoreGraphicsState();
        } catch (IOException e) {
            throw broken(op, pageNum, e);
        } catch (IllegalStateException e) {
            throw broken(op, pageNum, e);
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("page", (long) pageNum);
        details.put("objNum", PdfEditRaw.pageObjNum(page));
        details.put("width", PdfEditGeometry.round(width));
        details.put("height", PdfEditGeometry.round(height));
        details.put("format", format);
        details.put("pixels", List.of((long) image.getWidth(), (long) image.getHeight()));
        ctx.putGeometry(PdfEditGeometry.echo(op, pageNum, space, new double[]{givenX, givenY},
                PdfEditGeometry.toUser(frame, space, x, y, op), frame,
                PdfEditGeometry.unitName(op)));
        ctx.changed(op, "image placed (" + image.getWidth() + " × " + image.getHeight()
                + " pixels into " + format(width) + " × " + format(height) + " pt)", details);
        if (opacity != null) {
            ctx.note("image.put opacity " + format(opacity) + ": the value lives in an /ExtGState"
                    + " of the page (/ca and /CA); a viewer without transparency support shows"
                    + " the image opaque");
        }
    }

    /**
     * The transformation matrix of an image: the unit square of the image onto the target
     * rectangle, optionally turned around the rectangle's centre.
     * @param frame the page frame
     * @param space {@code 'page'} or {@code 'user'}
     * @param x left edge in the op's space
     * @param y top edge in page space, bottom edge in user space
     * @param width the width
     * @param height the height
     * @param rotate degrees clockwise on the displayed page
     * @param op the op (for the message)
     * @return the matrix for {@code cm}
     */
    private static PdfMatrix imageMatrix(PdfPageSpace frame, String space, double x, double y,
                                         double width, double height, double rotate,
                                         PdfEditOp op) {
        double cx = x + width / 2d;
        double cy = y + height / 2d;
        // The image is drawn by mapping its unit square: (0,0) is the image's lower-left corner.
        // In page space (y downwards) that is the corner *below* the anchor, in default user
        // space (y upwards) the anchor itself.
        double[][] corners = PdfEditGeometry.SPACE_PAGE.equals(space)
                ? new double[][]{{x, y + height}, {x + width, y + height}, {x, y}}
                : new double[][]{{x, y}, {x + width, y}, {x, y + height}};
        double[][] mapped = new double[3][];
        for (int i = 0; i < corners.length; i++) {
            double[] corner = rotate == 0d ? corners[i]
                    : rotatePoint(corners[i][0], corners[i][1], cx, cy, rotate);
            mapped[i] = PdfEditGeometry.toUser(frame, space, corner[0], corner[1], op);
        }
        double a = mapped[1][0] - mapped[0][0];
        double b = mapped[1][1] - mapped[0][1];
        double c = mapped[2][0] - mapped[0][0];
        double d = mapped[2][1] - mapped[0][1];
        return PdfMatrix.of(a, b, c, d, mapped[0][0], mapped[0][1]);
    }

    /**
     * Turns a point around a centre, clockwise on a page whose y axis points down.
     * @param x the point's x
     * @param y the point's y
     * @param cx the centre's x
     * @param cy the centre's y
     * @param degrees the angle
     * @return the turned point
     */
    private static double[] rotatePoint(double x, double y, double cx, double cy,
                                        double degrees) {
        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        double dx = x - cx;
        double dy = y - cy;
        return new double[]{cx + dx * cos - dy * sin, cy + dx * sin + dy * cos};
    }

    /**
     * Reads the image bytes from {@code data} or {@code path}.
     * @param op the op
     * @return the bytes
     */
    private static byte[] imageData(PdfEditOp op) {
        byte[] data = op.params().get("data") == null ? null : op.bytes("data");
        String path = op.stringOrNull("path");
        if (data != null && path != null) {
            throw PdfEditException.usage(op.where() + ": 'data' and 'path' are both given",
                    "give either data (a Uint8Array) or path (a file in the project)");
        }
        if (data != null) {
            if (data.length > PdfEditLimits.MAX_STREAM_BYTES) {
                throw new PdfEditException("limit", op.where() + ": image data of "
                        + data.length + " bytes",
                        "at most " + PdfEditLimits.MAX_STREAM_BYTES + " bytes per image");
            }
            return data;
        }
        if (path == null) {
            throw PdfEditException.usage(op.where() + ": image data missing",
                    "give data (a Uint8Array) or path (e.g. 'bild.png') - pdf.images() lists"
                            + " the images a document already has");
        }
        Path file = JsFileSystem.resolveSafePath(path);
        if (!Files.isRegularFile(file)) {
            throw new JsUserRuntimeException("File not found: " + JsFileSystem.toRelative(file));
        }
        try {
            long size = Files.size(file);
            if (size > PdfEditLimits.MAX_STREAM_BYTES) {
                throw new PdfEditException("limit", op.where() + ": " + JsFileSystem
                        .toRelative(file) + " has " + size + " bytes",
                        "at most " + PdfEditLimits.MAX_STREAM_BYTES + " bytes per image");
            }
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new JsUserRuntimeException("Failed to read image: "
                    + JsFileSystem.toRelative(file), e);
        }
    }

    /**
     * The image format: as named, else from the first bytes.
     * @param op the op
     * @param data the image bytes
     * @return {@code "jpeg"} or {@code "png"}
     */
    private static String format(PdfEditOp op, byte[] data) {
        boolean jpeg = data.length > 3 && (data[0] & 0xff) == 0xff && (data[1] & 0xff) == 0xd8;
        boolean png = data.length > 8 && (data[0] & 0xff) == 0x89 && data[1] == 'P'
                && data[2] == 'N' && data[3] == 'G';
        String format = op.stringOrNull("format");
        if (format == null) {
            if (jpeg) {
                return "jpeg";
            }
            if (png) {
                return "png";
            }
            throw PdfEditException.usage(op.where() + ": the image is neither PNG nor JPEG",
                    "give format:'png' or format:'jpeg' if the bytes are such a file with an"
                            + " unusual header - other formats would have to be converted first");
        }
        String value = format.trim().toLowerCase(Locale.ROOT);
        if (value.equals("jpg") || value.equals("jpeg")) {
            return "jpeg";
        }
        if (value.equals("png")) {
            return "png";
        }
        throw PdfEditException.usage(op.where() + ": unknown image format '" + format + "'",
                "format is 'png' or 'jpeg' (SVG and GIF would have to be converted first)");
    }

    /**
     * Builds the image XObject.
     * @param document the document
     * @param data the image bytes
     * @param format {@code "jpeg"} or {@code "png"}
     * @param op the op
     * @return the image
     */
    private static PDImageXObject image(PDDocument document, byte[] data, String format,
                                        PdfEditOp op) {
        try {
            if ("jpeg".equals(format)) {
                return JPEGFactory.createFromByteArray(document, data);
            }
            BufferedImage buffered = ImageIO.read(new ByteArrayInputStream(data));
            if (buffered == null) {
                throw PdfEditException.usage(op.where() + ": the PNG data cannot be read",
                        "the bytes are not an image the JDK can decode - pdfedit embeds PNG"
                                + " and JPEG only");
            }
            return LosslessFactory.createFromImage(document, buffered);
        } catch (IOException e) {
            throw new PdfEditException("broken", op.where() + ": the image could not be embedded",
                    "the bytes may be truncated - image.info() shows what the file holds", e);
        }
    }

    // -------------------------------------------------------------------
    // Shared helpers of the structured ops
    // -------------------------------------------------------------------

    /**
     * Opens the appending content stream of a page.
     * @param ctx the call context
     * @param page the page
     * @return the stream, to be closed by the caller
     * @throws IOException when PDFBox cannot write
     */
    private static PDPageContentStream open(PdfEditContext ctx, PDPage page) throws IOException {
        PDPageContentStream out = new PDPageContentStream(ctx.document(), page,
                PDPageContentStream.AppendMode.APPEND, false, true);
        page.getCOSObject().setNeedToBeUpdated(true);
        return out;
    }

    /**
     * Applies the page frame as {@code cm} when the op writes in page space, so that its
     * coordinates can be written as they are (including {@code /Rotate} and {@code /UserUnit}).
     * @param out the content stream
     * @param frame the page frame
     * @param space {@code 'page'} or {@code 'user'}
     * @param op the op (for the message)
     * @throws IOException when PDFBox cannot write
     */
    private static void applySpace(PDPageContentStream out, PdfPageSpace frame, String space,
                                   PdfEditOp op) throws IOException {
        if (PdfEditGeometry.SPACE_PAGE.equals(space)) {
            out.transform(matrix(PdfEditGeometry.pageToUser(frame, op)));
        }
    }

    /**
     * Converts a frame matrix into a PDFBox matrix.
     * @param matrix the matrix
     * @return the same matrix, for {@code cm} or {@code Tm}
     */
    private static Matrix matrix(PdfMatrix matrix) {
        return new Matrix((float) matrix.a(), (float) matrix.b(), (float) matrix.c(),
                (float) matrix.d(), (float) matrix.e(), (float) matrix.f());
    }

    /**
     * Reads the mandatory {@code text} parameter and checks the character budget.
     * @param op the op
     * @return the text
     */
    private static String text(PdfEditOp op) {
        String text = op.string("text");
        if (text.isEmpty()) {
            throw PdfEditException.usage(op.where() + ": 'text' is empty",
                    "an empty string writes an empty line - drop the op instead");
        }
        if (text.length() > PdfEditLimits.MAX_TEXT_CHARS) {
            throw new PdfEditException("limit", op.where() + ": " + text.length()
                    + " characters", "at most " + PdfEditLimits.MAX_TEXT_CHARS + " characters"
                    + " per op - split the text into several ops");
        }
        return text;
    }

    /**
     * Reads a required length parameter (not yet scaled by the op's unit).
     * @param op the op
     * @param key the parameter name
     * @return the value
     */
    private static double length(PdfEditOp op, String key) {
        Double value = op.doubleOrNull(key);
        if (value == null) {
            throw PdfEditException.usage(op.where() + ": '" + key + "' is missing",
                    "'" + key + "' is a coordinate or a length in the op's unit (pt by default)");
        }
        return value;
    }

    private static double number(PdfEditOp op, String key, double fallback) {
        Double value = op.doubleOrNull(key);
        return value == null ? fallback : value;
    }

    private static float size(PdfEditOp op) {
        Double value = op.doubleOrNull("size");
        if (value == null) {
            return DEFAULT_SIZE;
        }
        if (value <= 0) {
            throw PdfEditException.usage(op.where() + ": size is " + format(value),
                    "size is the text size in points - it has to be positive");
        }
        return value.floatValue();
    }

    private static Float floatOrNull(PdfEditOp op, String key) {
        Double value = op.doubleOrNull(key);
        return value == null ? null : value.floatValue();
    }

    /**
     * Reads an optional colour parameter.
     * @param op the op
     * @param key the parameter name
     * @return the colour, or {@code null} when the op does not name one
     */
    static PDColor colorOrNull(PdfEditOp op, String key) {
        Object raw = op.params().get(key);
        if (raw == null) {
            return null;
        }
        return color(raw, op, key);
    }

    /**
     * Reads a colour: a name ({@code 'black', 'red', …}), {@code '#rgb'} / {@code '#rrggbb'} or an
     * array of one (gray), three (RGB) or four (CMYK) components between 0 and 1.
     * @param raw the value
     * @param op the op (for the message)
     * @param key the parameter name
     * @return the colour
     */
    static PDColor color(Object raw, PdfEditOp op, String key) {
        if (raw instanceof String text) {
            String value = text.trim().toLowerCase(Locale.ROOT);
            float[] named = NAMED_COLORS.get(value);
            if (named != null) {
                return new PDColor(named, PDDeviceRGB.INSTANCE);
            }
            if (value.startsWith("#")) {
                return hexColor(value, op, key);
            }
            throw PdfEditException.usage(op.where() + ": unknown colour '" + text + "' in " + key,
                    "colours are " + new TreeSet<>(NAMED_COLORS.keySet()) + ", '#rrggbb' or an"
                            + " array of 1 (gray), 3 (rgb) or 4 (cmyk) components between 0 and 1");
        }
        if (raw instanceof List<?> list) {
            float[] components = new float[list.size()];
            for (int i = 0; i < list.size(); i++) {
                Object value = list.get(i);
                if (!(value instanceof Number number)) {
                    throw PdfEditException.usage(op.where() + ": " + key + "[" + i + "] is "
                            + PdfEditOp.describe(value), key + " holds numbers between 0 and 1");
                }
                components[i] = number.floatValue();
            }
            PDColorSpace space = switch (components.length) {
                case 1 -> PDDeviceGray.INSTANCE;
                case 3 -> PDDeviceRGB.INSTANCE;
                case 4 -> PDDeviceCMYK.INSTANCE;
                default -> throw PdfEditException.usage(op.where() + ": " + key + " has "
                        + components.length + " components",
                        key + " is [gray], [r,g,b] or [c,m,y,k]");
            };
            return new PDColor(components, space);
        }
        throw PdfEditException.usage(op.where() + ": " + key + " is " + PdfEditOp.describe(raw),
                key + " is a colour name, '#rrggbb' or an array of components");
    }

    private static PDColor hexColor(String value, PdfEditOp op, String key) {
        String hex = value.substring(1);
        if (hex.length() == 3) {
            hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1)
                    + hex.charAt(2) + hex.charAt(2);
        }
        if (hex.length() != 6) {
            throw PdfEditException.usage(op.where() + ": " + key + " reads '" + value + "'",
                    "a hex colour is '#rgb' or '#rrggbb'");
        }
        try {
            int rgb = Integer.parseInt(hex, 16);
            return new PDColor(new float[]{((rgb >> 16) & 0xff) / 255f,
                    ((rgb >> 8) & 0xff) / 255f, (rgb & 0xff) / 255f}, PDDeviceRGB.INSTANCE);
        } catch (NumberFormatException e) {
            throw new PdfEditException("usage", op.where() + ": " + key + " reads '" + value + "'",
                    "a hex colour is '#rgb' or '#rrggbb'", e);
        }
    }

    /**
     * Builds the error for a failure of PDFBox while writing content.
     * @param op the op
     * @param pageNum the page number
     * @param cause the cause
     * @return the exception
     */
    private static PdfEditException broken(PdfEditOp op, int pageNum, Exception cause) {
        return new PdfEditException("broken", op.where() + ": the content of page " + pageNum
                + " could not be written: " + cause.getMessage(),
                "pdf.contentStreams()/pdf.operations() show what the page holds before the call",
                cause);
    }

    /**
     * Cuts a text for the answer: an op may write a hundred thousand characters, and an answer
     * that repeats them is a second copy of the document.
     * @param text the text
     * @return the text, or its beginning with a marker
     */
    private static String shorten(String text) {
        int limit = 200;
        return text.length() <= limit ? text : text.substring(0, limit) + "… ("
                + text.length() + " characters)";
    }

    /** Formats a number for a phrase. */
    private static String format(double value) {
        double rounded = Math.round(value * 100d) / 100d;
        if (rounded == Math.rint(rounded)) {
            return Long.toString((long) rounded);
        }
        return Double.toString(rounded);
    }
}
