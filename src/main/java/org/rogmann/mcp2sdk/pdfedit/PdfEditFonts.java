package org.rogmann.mcp2sdk.pdfedit;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontFactory;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.rogmann.mcp2sdk.js.JsFileSystem;
import org.rogmann.mcp2sdk.js.JsUserRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The fonts of the structured text ops: the fourteen standard fonts, an embedded TrueType file and
 * a font the page already uses.
 * <p>
 * A Base-14 font is a name and a metric table, not a file: every viewer has the glyphs, PDFBox
 * carries the AFM widths, and the document stays small. That is the right default for "write a
 * title on a page". What it cannot do is a glyph outside WinAnsi (Cyrillic, Greek, CJK) - and the
 * error says so instead of writing a question mark.
 * </p>
 * <p>
 * <b>{@code {file:'…ttf', subset:true}}</b> embeds a TrueType file as a Type 0 font with an
 * Identity-H encoding, so <em>any</em> code point the file has a glyph for can be written. With
 * {@code subset:true} (the default) only the glyphs the call really shows are copied into the
 * result; PDFBox collects them while the text is written
 * ({@code PDAbstractContentStream.showTextInternal}) and cuts the programme at save time. The
 * subset tag is derived from the glyph set ({@code TrueTypeEmbedder.getTag}), so the same text
 * writes the same bytes - that is the determinism promise of the module (§6 of the design).
 * </p>
 * <p>
 * <b>{@code {objNum:12}}</b> reuses a font the page already names - "the same font as the rest of
 * the page". It works for <em>simple</em> fonts whose encoding names the characters (Type 1,
 * TrueType): those can encode a string. A Type 0 (Identity-H/CID) font codes glyphs, not text, and
 * a Type 3 font has no encoding at all - both are refused with that reason instead of writing
 * bytes that happen to look like a font.
 * </p>
 */
final class PdfEditFonts {

    private static final Logger LOGGER = LoggerFactory.getLogger(PdfEditFonts.class);

    /** The standard fonts by the name an op writes, lowercased. */
    private static final Map<String, Standard14Fonts.FontName> BY_NAME = byName();

    /** The members of a font object that names a file. */
    private static final Set<String> FILE_KEYS = new TreeSet<>(Set.of("file", "subset"));

    /** The members of a font object that names an object number. */
    private static final Set<String> OBJECT_KEYS = new TreeSet<>(Set.of("objNum"));

    private PdfEditFonts() {
        // Utility class
    }

    /**
     * A font an op writes with, and where it came from - the second half is what the answer says,
     * because "which font is this" is the question an embedded font raises.
     *
     * @param font the font
     * @param source one phrase about where the font comes from ({@code "embedded from 'x.ttf'"} …)
     */
    record Choice(PDFont font, String source) {
    }

    /**
     * A TrueType file as a font spec names it.
     *
     * @param path the path in the project
     * @param subset whether only the glyphs this call writes go into the result
     */
    record FileSpec(String path, boolean subset) {
    }

    private static Map<String, Standard14Fonts.FontName> byName() {
        Map<String, Standard14Fonts.FontName> map = new LinkedHashMap<>();
        for (Standard14Fonts.FontName name : Standard14Fonts.FontName.values()) {
            map.put(name.getName().toLowerCase(Locale.ROOT), name);
            map.put(name.name().toLowerCase(Locale.ROOT), name);
            map.put(name.getName().toLowerCase(Locale.ROOT).replace("-", ""), name);
        }
        return Map.copyOf(map);
    }

    /** @return the names an op may write into {@code font} */
    static TreeSet<String> names() {
        TreeSet<String> names = new TreeSet<>();
        for (Standard14Fonts.FontName name : Standard14Fonts.FontName.values()) {
            names.add(name.getName());
        }
        return names;
    }

    /**
     * Reads the {@code font} parameter of an op.
     * <p>
     * A string is a standard font name ({@code 'Helvetica'}, {@code 'Times-BoldItalic'}, ...),
     * case and hyphens aside; the default is {@code Helvetica}, the font every PDF viewer has. An
     * object either names a TrueType file to embed or the object number of a font the page already
     * uses.
     * </p>
     * @param ctx the call context (the document an embedded font belongs to)
     * @param page the page the text goes to (needed to find a font of that page)
     * @param op the op
     * @return the font and its source
     */
    static Choice choose(PdfEditContext ctx, PDPage page, PdfEditOp op) {
        Object raw = op.params().get("font");
        if (raw == null) {
            return standard(ctx, Standard14Fonts.FontName.HELVETICA);
        }
        if (raw instanceof String text) {
            return standard(ctx, standard14(text, op));
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw PdfEditException.usage(op.where() + ": font is " + PdfEditOp.describe(raw),
                    "'font' is the name of a standard font, {file:'…ttf', subset:true} or"
                            + " {objNum:12} - standard fonts are " + names());
        }
        Map<String, Object> spec = stringKeys(map);
        if (spec.containsKey("file")) {
            return fromFile(ctx, spec, op);
        }
        if (spec.containsKey("objNum")) {
            return fromObject(ctx, page, spec, op);
        }
        throw PdfEditException.usage(op.where() + ": font has neither 'file' nor 'objNum'",
                "a font is a standard font name, {file:'…ttf', subset:true} (embed a TrueType"
                        + " file) or {objNum:12} (a font the page already uses)");
    }

    /**
     * A standard font - one object per call and name.
     * <p>
     * Without this cache every text op added <b>its own</b> font dictionary to the page: a page
     * written by a layout with 45 lines named {@code /Helvetica} 45 times, with 45 objects of the
     * same {@code /BaseFont} (measured on a one-page report of {@code src/main/resources/js/tools/lib/pdftext.js}). One
     * object per call and name is enough - PDFBox writes the resource entry into the page that
     * shows the text, and that entry may well point at a shared object.
     * </p>
     * @param ctx the call context (holds the cache)
     * @param name the standard font
     * @return the font and the source phrase for the answer
     */
    private static Choice standard(PdfEditContext ctx, Standard14Fonts.FontName name) {
        String key = "std:" + name.getName();
        PDFont known = ctx.cachedFont(key);
        if (known == null) {
            known = new PDType1Font(name);
            ctx.cacheFont(key, known);
        }
        return new Choice(known, name.getName() + " (a standard font - nothing is embedded)");
    }

    /**
     * Resolves a standard font name.
     * @param text the name the op wrote
     * @param op the op (for the message)
     * @return the font name
     */
    private static Standard14Fonts.FontName standard14(String text, PdfEditOp op) {
        String key = text.trim().toLowerCase(Locale.ROOT);
        Standard14Fonts.FontName name = BY_NAME.get(key);
        if (name == null) {
            name = BY_NAME.get(key.replace(" ", "").replace("-", ""));
        }
        if (name == null) {
            throw PdfEditException.usage(op.where() + ": unknown font '" + text + "'",
                    "standard fonts are " + names() + " - each is a name every viewer knows and"
                            + " nothing is embedded; any other font comes from a TrueType file"
                            + " ({file:'…ttf', subset:true}) or from the page itself ({objNum:12})");
        }
        return name;
    }

    // -------------------------------------------------------------------
    // An embedded TrueType file
    // -------------------------------------------------------------------

    /**
     * Embeds a TrueType file as a Type 0 font (or returns the one this call already embedded).
     * @param ctx the call context
     * @param spec the font object ({@code file}, {@code subset})
     * @param op the op (for the message)
     * @return the font and its source
     */
    private static Choice fromFile(PdfEditContext ctx, Map<String, Object> spec, PdfEditOp op) {
        FileSpec file = fileSpec(spec, op);
        String source = "embedded from '" + file.path() + "'"
                + (file.subset() ? " (subset)" : " (whole file)");
        String key = "file:" + file.path() + "|subset:" + file.subset();
        PDFont known = ctx.cachedFont(key);
        if (known != null) {
            return new Choice(known, source);
        }
        byte[] data = read(file.path(), op);
        PDFont font = loadInto(ctx.document(), data, file.path(), file.subset(), op);
        ctx.cacheFont(key, font);
        ctx.note("the TrueType font '" + file.path() + "' was embedded"
                + (file.subset() ? " as a subset: only the glyphs this call writes are in the file" : "")
                + " - pdf.fonts() on the result lists it (PDFBox assigns the object number while"
                + " writing)");
        return new Choice(font, source);
    }

    /**
     * Loads a TrueType file for <b>measuring</b>: into a document the caller throws away.
     * <p>
     * The widths have to come from the same code path the writer uses, or the numbers would be a
     * second opinion about the font - so the file is loaded as the same {@link PDType0Font}, only
     * into a scratch document. Nothing of it reaches the result: a caller that measures and writes
     * in one call embeds the file once (through {@link #fromFile}), a caller that only measures
     * embeds nothing.
     * </p>
     * @param scratch the document that holds the font while the measurement runs
     * @param spec the font object ({@code file}, {@code subset})
     * @param op the op (for the message)
     * @return the font and the source phrase for the answer
     */
    static Choice measureFont(PDDocument scratch, Map<String, Object> spec, PdfEditOp op) {
        FileSpec file = fileSpec(spec, op);
        byte[] data = read(file.path(), op);
        PDFont font = loadInto(scratch, data, file.path(), file.subset(), op);
        return new Choice(font, "measured from '" + file.path() + "' (nothing embedded)");
    }

    /**
     * Reads the {@code file} and {@code subset} members of a font object.
     * @param spec the font object
     * @param op the op (for the message)
     * @return the file name and whether only the used glyphs go into the file
     */
    private static FileSpec fileSpec(Map<String, Object> spec, PdfEditOp op) {
        requireKeys(spec, FILE_KEYS, op, "file");
        Object value = spec.get("file");
        if (!(value instanceof String path) || path.isBlank()) {
            throw PdfEditException.usage(op.where() + ": file is " + PdfEditOp.describe(value),
                    "file is the path of a TrueType font in the project, e.g."
                            + " {font:{file:'fonts/LiberationSans-Regular.ttf', subset:true}}");
        }
        Object subsetValue = spec.get("subset");
        if (subsetValue != null && !(subsetValue instanceof Boolean)) {
            throw PdfEditException.usage(op.where() + ": subset is "
                    + PdfEditOp.describe(subsetValue),
                    "subset is true (only the glyphs this call writes - the default) or false"
                            + " (the whole programme, for a file that is used again later)");
        }
        boolean subset = subsetValue == null || (Boolean) subsetValue;
        return new FileSpec(path, subset);
    }

    /**
     * Loads a TrueType file as a Type 0 font into a document.
     * @param document the document that holds the font
     * @param data the font programme
     * @param path the path the op named (for the message)
     * @param subset whether only the used glyphs are to be written
     * @param op the op (for the message)
     * @return the font
     */
    private static PDFont loadInto(PDDocument document, byte[] data, String path, boolean subset,
                                   PdfEditOp op) {
        try (ByteArrayInputStream in = new ByteArrayInputStream(data)) {
            return PDType0Font.load(document, in, subset);
        } catch (IOException e) {
            throw new PdfEditException("broken", op.where() + ": '" + path + "' cannot be embedded"
                    + " as a TrueType font: " + e.getMessage(),
                    "pdfedit embeds TrueType (.ttf) files with a Unicode cmap; a damaged file or"
                            + " an OpenType file whose outlines are CFF is refused here", e);
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            throw new PdfEditException("unsupported", op.where() + ": '" + path + "' cannot be"
                    + " embedded: " + e.getMessage(),
                    "subsetting needs a TrueType file; store the font as .ttf (an OpenType file"
                            + " with CFF outlines cannot be subset here)", e);
        }
    }

    /**
     * Reads a font file from the project directory.
     * @param path the path as the op wrote it
     * @param op the op (for the message)
     * @return the bytes
     */
    private static byte[] read(String path, PdfEditOp op) {
        Path file = JsFileSystem.resolveSafePath(path);
        if (!Files.isRegularFile(file)) {
            // The same answer a missing image gets in image.put: a path that leads nowhere is a
            // file error, not an op error.
            throw new JsUserRuntimeException("File not found: " + JsFileSystem.toRelative(file));
        }
        try {
            long size = Files.size(file);
            if (size > PdfEditLimits.MAX_FONT_BYTES) {
                throw new PdfEditException("limit", op.where() + ": " + JsFileSystem
                        .toRelative(file) + " has " + size + " bytes",
                        "at most " + PdfEditLimits.MAX_FONT_BYTES + " bytes per embedded font");
            }
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new JsUserRuntimeException("Failed to read font: "
                    + JsFileSystem.toRelative(file), e);
        }
    }

    // -------------------------------------------------------------------
    // A font the page already has
    // -------------------------------------------------------------------

    /**
     * Reuses a font of the page, addressed by the object number {@code pdf.fonts()} printed.
     * @param ctx the call context
     * @param page the page the text goes to
     * @param spec the font object ({@code objNum})
     * @param op the op (for the message)
     * @return the font and its source
     */
    private static Choice fromObject(PdfEditContext ctx, PDPage page, Map<String, Object> spec,
                                     PdfEditOp op) {
        requireKeys(spec, OBJECT_KEYS, op, "objNum");
        Object value = spec.get("objNum");
        if (!(value instanceof Number number)) {
            throw PdfEditException.usage(op.where() + ": objNum is " + PdfEditOp.describe(value),
                    "objNum is the object number of a font, as pdf.fonts(path, {page:n}) prints"
                            + " it");
        }
        int objNum = number.intValue();
        int pageNum = ctx.document().getPages().indexOf(page) + 1;
        COSBase base = ctx.object(objNum).getObject();
        if (!(base instanceof COSDictionary dict)) {
            throw PdfEditException.usage(op.where() + ": object " + objNum + " is "
                    + PdfEditOp.describe(base),
                    "objNum names a font dictionary - pdf.fonts(path, {page:n}) lists them");
        }
        PDFont font;
        try {
            font = PDFontFactory.createFont(dict);
        } catch (IOException e) {
            throw new PdfEditException("broken", op.where() + ": object " + objNum + " cannot be"
                    + " read as a font: " + e.getMessage(),
                    "pdf.fonts(path, {page:n}) names the fonts of a page with their object"
                            + " numbers (and says which entry is missing when one is broken)", e);
        }
        if (font instanceof PDType0Font) {
            throw new PdfEditException("unsupported", op.where() + ": object " + objNum
                    + " is a Type 0 (CID) font",
                    "reusing a document font works for simple fonts, whose encoding names the"
                            + " characters; a Type 0 font codes glyphs, not text - embed your own"
                            + " file ({file:'…ttf', subset:true}) or write the text as operators"
                            + " with content.append");
        }
        if (font instanceof PDType3Font) {
            throw new PdfEditException("unsupported", op.where() + ": object " + objNum
                    + " is a Type 3 font",
                    "a Type 3 font draws with procedures and has no encoding to write text with"
                            + " - use a standard font or an embedded TrueType file");
        }
        COSName name = resourceName(page, dict);
        if (name == null) {
            throw new PdfEditException("out-of-range", op.where() + ": object " + objNum
                    + " is not a font resource of page " + pageNum,
                    "pdf.fonts(path, {page:" + pageNum + "}) lists the fonts of that page with"
                            + " their object numbers - a font of another page is not at hand"
                            + " here");
        }
        return new Choice(font, "resource /" + name.getName() + " of page " + pageNum
                + " (object " + objNum + ")");
    }

    /**
     * Finds the resource name under which a page names a font dictionary.
     * @param page the page
     * @param fontDict the font dictionary (compared by identity - the cross-reference hands out
     *                 the same instance for the same object)
     * @return the name, or {@code null} when the page does not use this font
     */
    private static COSName resourceName(PDPage page, COSDictionary fontDict) {
        PDResources resources = page.getResources();
        if (resources == null) {
            return null;
        }
        for (COSName name : resources.getFontNames()) {
            try {
                PDFont font = resources.getFont(name);
                if (font != null && font.getCOSObject() == fontDict) {
                    return name;
                }
            } catch (IOException e) {
                LOGGER.debug("pdfedit: the font resource {} cannot be read", name, e);
            }
        }
        return null;
    }

    // -------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------

    /**
     * Rejects members a font object does not know - a typo would otherwise be a silently ignored
     * wish.
     * @param spec the font object
     * @param keys the supported members
     * @param op the op
     * @param named the member that makes the object a file / an object reference
     */
    private static void requireKeys(Map<String, Object> spec, Set<String> keys, PdfEditOp op,
                                    String named) {
        Set<String> unknown = new TreeSet<>(spec.keySet());
        unknown.removeAll(keys);
        if (!unknown.isEmpty()) {
            throw PdfEditException.usage(op.where() + ": unknown font member(s) " + unknown,
                    "a font object with '" + named + "' knows " + keys + " - pdfedit.help()"
                            + " shows the three font forms");
        }
    }

    private static Map<String, Object> stringKeys(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    /**
     * The name of a font as the answer echoes it.
     * @param font the font
     * @return the base font name
     */
    static String nameOf(PDFont font) {
        String name = font.getName();
        return name == null ? font.getSubType() : name;
    }

    /**
     * Encodes a string in the font's encoding, so that an unsupported character is a refusal here
     * and not a question mark in the document.
     * @param font the font
     * @param text the text
     * @param op the op (for the message)
     */
    static void requireEncodable(PDFont font, String text, PdfEditOp op) {
        try {
            font.encode(text);
        } catch (IOException e) {
            throw new PdfEditException("broken", op.where() + ": the text could not be encoded",
                    "this is a fault of the font, not of the text", e);
        } catch (IllegalArgumentException e) {
            throw PdfEditException.usage(op.where() + ": the font " + nameOf(font) + " cannot show"
                    + " the text: " + e.getMessage(),
                    "a standard font covers WinAnsi (Latin-1 and a few more); for any other"
                            + " script embed a TrueType file with {file:'…ttf', subset:true} -"
                            + " an embedded font carries the glyphs, a standard font does not");
        }
    }

    /**
     * Encodes every word of a paragraph in the font's encoding.
     * <p>
     * A layout op splits its text at whitespace and writes the words; the whitespace itself never
     * reaches the font. Checking the whole string would therefore refuse a perfectly good
     * paragraph because WinAnsi has no line feed - the words are what has to be encodable.
     * </p>
     * @param font the font
     * @param text the paragraph
     * @param op the op (for the message)
     */
    static void requireEncodableWords(PDFont font, String text, PdfEditOp op) {
        for (String word : text.split("\\s+")) {
            if (!word.isEmpty()) {
                requireEncodable(font, word, op);
            }
        }
    }
}
