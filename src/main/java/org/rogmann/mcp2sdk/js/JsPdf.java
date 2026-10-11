package org.rogmann.mcp2sdk.js;

import org.rogmann.mcp2sdk.pdf.PdfCidToGidMap;
import org.rogmann.mcp2sdk.pdf.PdfCffProgram;
import org.rogmann.mcp2sdk.pdf.PdfCMap;
import org.rogmann.mcp2sdk.pdf.PdfContentStream;
import org.rogmann.mcp2sdk.pdf.PdfDocument;
import org.rogmann.mcp2sdk.pdf.PdfDocumentStructure;
import org.rogmann.mcp2sdk.pdf.PdfEncoding;
import org.rogmann.mcp2sdk.pdf.PdfException;
import org.rogmann.mcp2sdk.pdf.PdfFileStructure;
import org.rogmann.mcp2sdk.pdf.PdfFilters;
import org.rogmann.mcp2sdk.pdf.PdfFont;
import org.rogmann.mcp2sdk.pdf.PdfFontProgram;
import org.rogmann.mcp2sdk.pdf.PdfFontScope;
import org.rogmann.mcp2sdk.pdf.PdfFontWidths;
import org.rogmann.mcp2sdk.pdf.PdfGlyphMapper;
import org.rogmann.mcp2sdk.pdf.PdfGlyphNames;
import org.rogmann.mcp2sdk.pdf.PdfName;
import org.rogmann.mcp2sdk.pdf.PdfReference;
import org.rogmann.mcp2sdk.pdf.PdfResources;
import org.rogmann.mcp2sdk.pdf.PdfString;
import org.rogmann.mcp2sdk.pdf.PdfStructTree;
import org.rogmann.mcp2sdk.pdf.PdfTextDecoder;
import org.rogmann.mcp2sdk.pdf.PdfTrueTypeProgram;
import org.rogmann.mcp2sdk.pdf.PdfType1Program;
import org.rogmann.mcp2sdk.pdf.PdfValues;
import org.rogmann.mcp2sdk.pdf.geo.PdfRect;
import org.rogmann.mcp2sdk.pdf.geo.PdfSpace;
import org.rogmann.mcp2sdk.pdf.ops.ListingSink;
import org.rogmann.mcp2sdk.pdf.ops.PdfContentInterpreter;
import org.rogmann.mcp2sdk.pdf.ops.PlacementSink;
import org.rogmann.mcp2sdk.pdf.ops.TextSink;
import org.rogmann.mcp2sdk.pdf.ops.XObjectOps;
import org.rogmann.mcp2sdk.pdf.state.PdfClipPath;
import org.rogmann.mcp2sdk.pdf.state.PdfPath;
import org.rogmann.mcp2sdk.pdf.state.PdfStateStack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Controlled read access to PDF documents for JavaScript.
 * <p>
 * The class is the API behind the {@code pdf} namespace: it resolves the file like
 * {@link JsFileSystem} (project-relative, add-on mounts, no {@code ..}), reads it into memory
 * and hands it to the self-contained reader in {@link org.rogmann.mcp2sdk.pdf} - no external
 * PDF library, no writing, no execution of anything the document contains.
 * </p>
 * <p>
 * Two layers are read. The <em>file</em>: its structure, objects (including indirect and packed
 * ones), streams (stored and decoded) and content streams. And the <em>document</em>: the
 * catalogue with its page tree, outlines, annotations, form and name trees
 * ({@link PdfDocumentStructure}), plus the logical structure of a tagged PDF - the
 * {@code /StructTreeRoot} tree, its {@code /ParentTree} and the marked content it refers to
 * ({@link PdfStructTree}). What a PDF <em>looks like</em> (colour, rendering) and what it
 * <em>protects</em> (signatures, encryption) stay out of scope. Fonts are read as <em>data</em>:
 * {@link PdfFont} describes what a font dictionary says (kind, encoding and code space,
 * descendant, descriptor, embedded program, the widths of {@code /W} and {@code /Widths}),
 * {@link PdfTrueTypeProgram} reads the tables of an embedded sfnt program
 * ({@code head}/{@code hhea}/{@code maxp}/{@code hmtx}/{@code loca}+{@code glyf}/{@code cmap} -
 * tables only, no instruction is ever executed), {@link PdfType1Program} decrypts a Type 1 program
 * behind {@code eexec} and reads its declarations (its {@code /Encoding} and {@code /CharStrings}
 * names; its charstrings are skipped, not executed), {@link PdfCffProgram} walks a CFF's indices and
 * DICTs (charset, font families, the widths its Private DICTs announce; its charstrings are counted,
 * not parsed), {@link PdfCMap} reads one {@code /ToUnicode}
 * stream, and {@link PdfGlyphMapper} plus {@link PdfTextDecoder} turn the bytes of one string into
 * codes and characters - through the map, the UCS family, the program's own {@code /cmap} or a glyph
 * name of the Adobe Glyph List, and never through a guess.
 * {@link PdfFontScope} applies that chain to a whole page: {@code pdf.text()},
 * {@code pdf.contentStreams()} and {@code pdf.markedContent()} read every string through the font
 * its {@code Tf} names and report, per page, how much became characters and how much stayed a code
 * ({@code pdf.decodeString()} is the same chain asked of a single string). The same two readers
 * answer the metrics question {@code pdf.glyphMetrics()} asks: the width the dictionary names
 * ({@code /W}, {@code /Widths}, {@code /DW}) or the program's {@code hmtx} where it has none, and
 * the box {@code glyf} writes, each on the base the file uses and again multiplied by a font size
 * into text space. No glyph outline is drawn and nothing is composed: a composite glyph is named,
 * and a Type 1 or CFF program keeps its advances inside charstrings that are never executed.
 * </p>
 */
public final class JsPdf {

    /** Default limit for listing results such as {@code pdf.objects()}. */
    public static final int DEFAULT_LIMIT = 1000;

    /** Default page limit for {@code pdf.contentStreams()} and {@code pdf.text()}. */
    public static final int DEFAULT_MAX_PAGES = 200;

    /** Default number of characters of text returned per page. */
    public static final int DEFAULT_MAX_TEXT_CHARS = 4000;

    /** Default number of CMap entries {@code pdf.cmap()} shows (the map itself is complete). */
    public static final int DEFAULT_CMAP_LIMIT = PdfCMap.DEFAULT_LIMIT;

    /** Upper bound of the {@code /ToUnicode} map {@code pdf.cmap()} builds. */
    public static final int MAX_CMAP_ENTRIES = PdfCMap.MAX_ENTRIES;

    /** Upper bound of the code sample list of {@code pdf.cmap({codes: …})}. */
    public static final int MAX_CODE_ITEMS = PdfCMap.MAX_CODE_ITEMS;

    /** Default number of fonts {@code pdf.fonts()} lists. */
    public static final int DEFAULT_FONT_LIMIT = PdfFont.DEFAULT_LIST_LIMIT;

    /** Default number of items per block of {@code pdf.font()} (widths, differences, codes). */
    public static final int DEFAULT_FONT_ITEMS = PdfFont.DEFAULT_MAX_ITEMS;

    /** Upper number of width entries one font describes ({@code /W}, {@code /Widths}). */
    public static final int MAX_WIDTH_ENTRIES = PdfFont.MAX_WIDTH_ENTRIES;

    /** How deep a {@code /DescendantFonts} chain is followed. */
    public static final int MAX_DESCENDANT_DEPTH = PdfFont.MAX_DESCENDANT_DEPTH;

    /** Form XObjects whose resources are opened per page by {@code pdf.fonts()}. */
    public static final int MAX_FORM_XOBJECTS = PdfFont.MAX_FORM_XOBJECTS;

    /**
     * Names one resource category lists at most. A page naming five hundred fonts is a listing
     * nobody reads; the cut is marked where it happened and the full count stays in the answer.
     */
    public static final int MAX_RESOURCE_ENTRIES = PdfResources.MAX_ENTRIES;

    /**
     * Codes one {@link #decodeString} call reads and lists by default. The byte and code counts of
     * the string stay complete either way - the limit bounds the work, not the statement.
     */
    public static final int MAX_GLYPH_QUERIES = PdfTextDecoder.MAX_STRING_CODES;

    /**
     * Table directory entries of an embedded program read at most. A directory can declare 65 000
     * tags; a font is not a listing, and reading a thousand of them would be a way to make this
     * module walk memory that no glyph lives in.
     */
    public static final int MAX_PROGRAM_TABLES = PdfFontProgram.MAX_TABLES;

    /**
     * Codes one {@code pdf.text()} / {@code pdf.markedContent()} call maps through fonts at most.
     * Only strings that run through a font are charged, so a page the document convention reads is
     * never shortened by this budget.
     */
    public static final long MAX_TEXT_CODES = PdfFontScope.MAX_TEXT_CODES;

    /** Codes a page names in {@code unmappedSample} by default (the count stays complete). */
    public static final int DEFAULT_MAX_UNMAPPED = PdfFontScope.DEFAULT_MAX_UNMAPPED;

    /**
     * The one item kind D5 introduced. Kept because the default of {@code what} is text and the
     * constant reads better than the literal; the full list is {@link #PLACEMENT_KINDS}.
     */
    public static final String PLACEMENT_KIND_TEXT = PlacementSink.KIND_TEXT;

    /**
     * The item kinds {@code pdf.placement()} can collect. D5 built {@code 'text'}; increment D6 added
     * {@code 'clips'}, {@code 'paths'}, {@code 'xobjects'} and {@code 'images'}. Marked-content spans
     * stay with {@code pdf.markedContent()}, which also reads the {@code /ParentTree} a
     * {@code BDC} operand alone does not carry. Asking for a kind that does not exist is an error
     * that names the kinds that do, because an answer that quietly leaves a kind out reads as "this
     * page has none of those".
     */
    public static final Set<String> PLACEMENT_KINDS =
            java.util.Set.copyOf(PlacementSink.WHAT_NAMES.keySet());

    /** The word {@code what} takes for every kind at once. */
    public static final String PLACEMENT_KIND_ALL = "all";

    /** Items one content stream contributes to {@code pdf.placement()} by default. */
    public static final int DEFAULT_MAX_PLACEMENT_ITEMS = PlacementSink.DEFAULT_MAX_ITEMS;

    /**
     * Points one item lists — a clip's {@code polygon} and a path's {@code drawing} spend the same
     * budget (the full counts stay in {@code points} / {@code segments}).
     */
    public static final int MAX_PLACEMENT_POLYGON_POINTS = PlacementSink.MAX_POLYGON_POINTS;

    /** How deep {@code {includeXObjects: true}} may descend into forms. */
    public static final int MAX_XOBJECT_DEPTH = XObjectOps.MAX_DEPTH;

    /** How many elements and glyphs one showing operator lists ({@code pdf.placement}). */
    public static final int MAX_PLACEMENT_DETAILS = org.rogmann.mcp2sdk.pdf.ops.TextShowOps
            .MAX_DETAILS_PER_OPERATOR;

    /** How many digits a {@code pdf.placement()} coordinate keeps. */
    public static final double PLACEMENT_ROUNDING = PlacementSink.ROUNDING;

    /**
     * The limit names D7 replaced, old &rarr; new. Both spellings are in {@link #status()} for one
     * version (an answer somebody compares with last week's must not lose a field to a rename), and
     * this map is what makes the pair a statement instead of a silent duplicate: it is printed as
     * {@code pdf.status().retiredLimits}, so a caller reading the new name learns what it used to be
     * called and a caller reading the old one learns where it is going.
     */
    public static final Map<String, String> RETIRED_LIMIT_NAMES = retiredLimitNames();

    private static Map<String, String> retiredLimitNames() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("maxWalkOperators", "maxOps");
        map.put("maxWalkDiagnostics", "maxDiagnostics");
        map.put("defaultMaxPlacementItems", "maxItems");
        map.put("maxPlacementDetails", "maxGlyphs");
        map.put("maxXobjectDepth", "maxDepth");
        map.put("maxPlacementPolygonPoints", "maxPolygonPoints");
        map.put("maxPlacementPolygonPointsTotal", "maxPolygonPointsTotal");
        return java.util.Collections.unmodifiableMap(map);
    }

    /** How many code space ranges the compact {@code split.codeSpaces} string names. */
    private static final int LISTED_CODE_SPACES = 4;

    /** A PDF source: a project file or an in-memory byte image. */
    public record Source(String displayName, byte[] data) {
    }

    private JsPdf() {
        // Utility class
    }

    // ===================================================================
    // Source handling
    // ===================================================================

    /**
     * Reads a PDF file from the project base directory (or an add-on mount).
     * @param filePath project-relative path
     * @return the source
     * @throws JsUserRuntimeException when the file is missing or too large
     */
    public static Source fromPath(String filePath) {
        Path path = JsFileSystem.resolveSafePath(filePath);
        if (!Files.isRegularFile(path)) {
            throw new JsUserRuntimeException("File not found: " + JsFileSystem.toRelative(path));
        }
        try {
            long size = Files.size(path);
            if (size > PdfFileStructure.MAX_PDF_BYTES) {
                throw new JsUserRuntimeException(String.format(
                        "PDF too large: %s has %d bytes (limit %d bytes)",
                        JsFileSystem.toRelative(path), size, PdfFileStructure.MAX_PDF_BYTES));
            }
            return new Source(JsFileSystem.toRelative(path), Files.readAllBytes(path));
        } catch (IOException e) {
            throw new JsUserRuntimeException("Failed to read PDF: "
                    + JsFileSystem.toRelative(path), e);
        }
    }

    /**
     * Wraps an in-memory PDF image (e.g. bytes extracted from a ZIP with
     * {@code archive.zipEntry()}).
     * @param data raw PDF bytes
     * @param displayName name used in messages (optional)
     * @return the source
     */
    public static Source fromBytes(byte[] data, String displayName) {
        if (data == null || data.length == 0) {
            throw new PdfException("PDF data is empty");
        }
        if (data.length > PdfFileStructure.MAX_PDF_BYTES) {
            throw new PdfException("PDF data is too large: " + data.length + " bytes (limit "
                    + PdfFileStructure.MAX_PDF_BYTES + ")");
        }
        return new Source(displayName != null && !displayName.isBlank() ? displayName : "<bytes>",
                data);
    }

    /**
     * Opens the document of a source.
     * @param source the source
     * @return the document
     */
    public static PdfDocument open(Source source) {
        return PdfDocument.open(source.displayName(), source.data());
    }

    // ===================================================================
    // API
    // ===================================================================

    /**
     * File structure: header, body statistics, cross-reference sections, trailer, EOF.
     * @param source the PDF source
     * @return structure map
     */
    public static Map<String, Object> structure(Source source) {
        return open(source).structure().toMap();
    }

    /**
     * Document information from the trailer ({@code /Info}, {@code /Root}, {@code /ID}) plus
     * a few counts. The {@code /Root}/{@code /Info} pointers are shown as references, not
     * expanded - see {@link PdfDocument#info()}.
     * @param source the PDF source
     * @return info map
     */
    public static Map<String, Object> info(Source source) {
        PdfDocument document = open(source);
        Map<String, Object> info = document.info();
        info.put("objects", (long) document.crossReference().objectCount());
        return info;
    }

    /**
     * Lists the registered objects.
     * @param source the PDF source
     * @param limit maximum number of entries (0 = no limit)
     * @param includeFree whether free entries are listed
     * @param salvage whether to add objects found by scanning the file
     * @return list with a trailing summary entry
     */
    public static List<Map<String, Object>> objects(Source source, int limit, boolean includeFree,
                                                   boolean salvage) {
        PdfDocument document = open(source);
        List<Map<String, Object>> list = new ArrayList<>(
                document.objects(limit, includeFree));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("registered", (long) document.crossReference().objectCount());
        summary.put("listed", (long) list.size());
        summary.put("sections", (long) document.crossReference().sections().size());
        summary.put("recoveredByScan", document.crossReference().salvaged());
        if (salvage) {
            List<Map<String, Object>> orphans = document.orphanObjects();
            summary.put("notInCrossReference", (long) orphans.size());
            list.addAll(orphans);
        }
        if (limit > 0 && document.crossReference().objectCount() > limit) {
            summary.put("note", "only the first " + limit + " object(s) are listed - raise limit"
                    + " to see more");
        }
        list.add(summary);
        return list;
    }

    /**
     * Reads one object.
     * @param source the PDF source
     * @param objNum object number
     * @param resolveRefs whether nested references are resolved
     * @param maxDepth maximum nesting depth
     * @param maxStringChars maximum length of a string value
     * @return object map
     */
    public static Map<String, Object> object(Source source, int objNum, boolean resolveRefs,
                                            int maxDepth, int maxStringChars) {
        PdfDocument document = open(source);
        return document.objectInfo(objNum, resolveRefs, maxDepth, maxStringChars);
    }

    /**
     * Lists all stream objects with metadata.
     * @param source the PDF source
     * @param limit maximum number of entries (0 = no limit)
     * @return stream list
     */
    public static List<Map<String, Object>> streams(Source source, int limit) {
        return open(source).streams(limit);
    }

    /**
     * Reads a stream, decoding its filter chain by default.
     * @param source the PDF source
     * @param objNum object number
     * @param decode whether to apply the filters ({@code false} returns the stored bytes)
     * @return the bytes
     */
    public static byte[] stream(Source source, int objNum, boolean decode) {
        PdfDocument document = open(source);
        if (decode) {
            return document.stream(objNum).data();
        }
        return document.rawStreamBytes(objNum);
    }

    /**
     * Reads a stream as text.
     * @param source the PDF source
     * @param objNum object number
     * @param decode whether to apply the filters
     * @param encoding charset name or {@code "pdfdoc"} (document convention)
     * @return the text
     */
    public static String streamText(Source source, int objNum, boolean decode, String encoding) {
        byte[] data = stream(source, objNum, decode);
        return PdfString.decodeWith(data, encoding);
    }

    /**
     * Content streams per page with an operator histogram; the text extract (on request) is read
     * through the fonts of the page.
     * @param source the PDF source
     * @param maxPages page limit
     * @param includeText whether to add a text extract
     * @param maxTextChars maximum characters of that extract
     * @return one entry per page
     */
    public static List<Map<String, Object>> contentStreams(Source source, int maxPages,
                                                          boolean includeText, int maxTextChars) {
        return contentStreams(source, maxPages, includeText, maxTextChars, true);
    }

    /**
     * Content streams per page with an operator histogram.
     * @param source the PDF source
     * @param maxPages page limit
     * @param includeText whether to add a text extract
     * @param maxTextChars maximum characters of that extract
     * @param useFonts whether the extract is read through the font each {@code Tf} names
     * @return one entry per page
     */
    public static List<Map<String, Object>> contentStreams(Source source, int maxPages,
                                                          boolean includeText, int maxTextChars,
                                                          boolean useFonts) {
        return open(source).contentStreams(maxPages, includeText, maxTextChars, useFonts);
    }

    /**
     * Operator listing of a stream (a readable view of the drawing commands).
     * @param source the PDF source
     * @param objNum stream object number
     * @param maxItems maximum number of operations
     * @param afterByte only operations at or after this byte offset
     * @return operations map
     */
    public static Map<String, Object> contentOperations(Source source, int objNum, int maxItems,
                                                        int afterByte) {
        PdfDocument document = open(source);
        byte[] content = document.stream(objNum).data();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("objNum", (long) objNum);
        result.put("decodedBytes", (long) content.length);
        // One walk for both halves of the answer (counts and listing) - see PdfContentStream
        // .analyseAndList: two walks over the same stream would double the price of the state
        // machine for no additional statement.
        PdfContentStream.Walked walked = PdfContentStream.analyseAndList(content, 20, maxItems,
                afterByte);
        result.putAll(walked.counted());
        result.put("operations", walked.listed());
        return result;
    }

    /**
     * Best-effort text of the document, read through the fonts each {@code Tf} names.
     * @param source the PDF source
     * @param pageIndex 1-based page number or {@code null} for all pages
     * @param maxPages page limit
     * @return text map
     */
    public static Map<String, Object> text(Source source, Integer pageIndex, int maxPages) {
        return text(source, pageIndex, maxPages, true, PdfFontScope.DEFAULT_MAX_UNMAPPED);
    }

    /**
     * Text of the document, with or without its fonts.
     *
     * @param source      the PDF source
     * @param pageIndex   1-based page number or {@code null} for all pages
     * @param maxPages    page limit of the all-pages read; a read that names one page walks to
     *                    that page, because a listing limit must not decide which page exists
     * @param useFonts    whether every string is read through the font that was current at that
     *                    point ({@code false}: the document convention, the documented raw view)
     * @param maxUnmapped codes a page names in {@code unmappedSample} (0 = no sample)
     * @return text map
     */
    public static Map<String, Object> text(Source source, Integer pageIndex, int maxPages,
                                           boolean useFonts, int maxUnmapped) {
        return open(source).text(requirePage(pageIndex, "pdf.text"), maxPages, useFonts,
                maxUnmapped);
    }

    /**
     * Where the text, paths, clips, external objects and images of a page stand —
     * {@code pdf.placement()} (increments D5 and D6 of {@code docs/js/pdf-content-plan.md}).
     *
     * <h2>What it answers that {@code pdf.text()} cannot</h2>
     *
     * {@code pdf.text()} returns characters; this returns <em>positions</em>: one item per
     * text-showing operator with the pen's position, the distance its operand displaced the pen, and
     * the state that was in effect at that byte. Both read the same interpreter, and this one
     * additionally asks the page's fonts for their widths, which is what makes the advance a computed
     * number (5.3.3, S. 410) rather than the extract's rule of thumb. {@code pdf.text()} itself is
     * untouched — the plan's decision 10 lets it move only after the two answers have been written
     * down side by side ({@code target/scratch/pdf-d5-pair.js}).
     *
     * <h2>The four kinds D6 adds, and what each one claims</h2>
     *
     * {@code what: ['paths']} reports an operator of Table 4.10 with the path it ended ({@code n}
     * included, with {@code painted: false}); {@code ['clips']} reports a {@code W}/{@code W*} with
     * the region its <em>painting</em> operator established (4.4.3, S. 234 puts the clip there, which
     * is why an item names both its own offset and the {@code paintOffset}); {@code ['xobjects']}
     * names what a {@code Do} points at — object number, {@code /Subtype}, {@code /BBox} mapped
     * through the form's {@code /Matrix} and the CTM, where its resources come from — and descends
     * into the form only on {@code {includeXObjects: true}}; {@code ['images']} gives the unit square
     * of image space that an inline image or an {@code /Image} XObject covers, never its pixels.
     *
     * <h2>Every number names its space</h2>
     *
     * An item's {@code space} is {@code 'page'} — origin at the top-left of the visible box, y
     * downwards, one point — or {@code 'user'} when the page has no frame to compute against. The
     * page's own matrix is in {@code frame.userToPage}, each item's CTM and text matrix in {@code at},
     * and {@code at.offset} is the byte that {@code pdf.operations(path, objNum, {after: offset})}
     * shows.
     *
     * <h2>Per code: {@code {glyphs: true}} (increment D9a)</h2>
     *
     * A text item's own {@code x}/{@code y} is one position — the pen where the operand starts — and
     * a producer can put two codes of one operand 72 pt apart while putting two codes of different
     * operands 2 pt apart. {@code {glyphs: true}} answers per code, in the item's space:
     * {@code x}/{@code y} (that code's foot: the item's origin plus the pen's walk to it, so the
     * first entry is the item's own position exactly), {@code advance} (the width it displaced the
     * pen by, {@code T_c}/{@code T_w} included), {@code piece} (which entry of {@code elements} the
     * code belongs to, a {@code TJ} number counting as a piece of its own — so {@code elements[piece]}
     * resolves), and {@code kernBefore} (the {@code TJ} number written directly in front of it, 0
     * where there is none). The three mechanisms a line is really written with — pieces, kerning
     * numbers, and a {@code Td} that jumps back to the line — then stay visible instead of merging
     * into one string.
     *
     * <h2>Two families of sentences, and the two kinds D7 left alone</h2>
     *
     * Every page answer carries a {@code diagnostics} block (increment D7): what the walk could not
     * use — operands that did not fit their row, a {@code Q} with an empty stack, a clip that
     * narrowed to nothing, a {@code W} whose path was never painted, a second {@code W} before the
     * path was ended, a path built on a matrix without an inverse — counted by family and quoted with
     * its byte offset, at most {@code pdf.status().maxDiagnostics} sentences with the rest counted in
     * {@code beyondList}. Those are findings about <em>the file</em>. The page's {@code notes} stay
     * what they always were: sentences about <em>this answer</em> ("this item has no box because
     * 4.4.3, S. 234 gives it none"). Keeping the two apart is what lets a caller tell a broken stream
     * from a documented limit, and it is why {@code pdf.contentStreams()} and {@code
     * pdf.operations()} answer exactly what they answered before.
     *
     * Two kinds are deliberately not items here. A marked-content span stays with {@code
     * pdf.markedContent()}, which is also the function that reads the {@code /ParentTree} a {@code
     * BDC} operand does not carry; as soon as a page opened sequences its {@code notes} say so,
     * because otherwise {@code summary.marks: 0} would read as "this page marked nothing". And no
     * word gap moves into this answer from {@code pdf.text()}: positions are computed here while the
     * text extract keeps its documented rule of thumb, now with its own {@code wordGaps} count
     * (plan decision 10, closed by D7 on the measurement D5 wrote down).
     *
     * @param source          the PDF source
     * @param pageIndex       1-based page number, or {@code null} to walk up to {@code maxPages}
     * @param maxPages        page limit of the all-pages read (a named page is reached regardless)
     * @param what            the kinds to collect: {@code 'text'} (the default), {@code 'clips'},
     *                        {@code 'paths'}, {@code 'xobjects'}, {@code 'images'}, or {@code 'all'}
     * @param bbox            a box {@code [x1 y1 x2 y2]} in <b>page space</b>: a text item must have
     *                        its origin inside it, an area item must reach into it (both counted)
     * @param dpi             the caller's resolution; it buys {@code frame.deviceScale} and nothing
     *                        else, because the file never names one
     * @param state           {@code 'key'} (the item's own state), {@code 'full'} (the whole
     *                        graphics state at the operator) or {@code 'colour'} (only the four
     *                        colour parameters of Table 4.2)
     * @param glyphs          whether every code of an operand is listed with its width, its own
     *                        position and its place in the operand ({@code x}, {@code y},
     *                        {@code advance}, {@code piece}, {@code kernBefore} — D9a)
     * @param maxItems        items per page read (0 = {@link PlacementSink#DEFAULT_MAX_ITEMS})
     * @param useFonts        whether an item's text is read through its font (false: document convention)
     * @param includeXObjects whether a {@code Do} naming a form is <em>walked</em> as well as named
     *                        (its items then carry {@code depth} and share {@code maxItems})
     * @param drawing         whether a path item also answers its segments ({@code drawing}, D8b)
     * @return {@code {pages: [{page, pageObjNum, frame, walk, items, summary, diagnostics,
     *         mapping?, notes?}], pageCount, notes?}}
     */
    public static Map<String, Object> placement(Source source, Integer pageIndex, int maxPages,
                                               List<String> what, List<Number> bbox, Double dpi,
                                               String state, boolean glyphs, int maxItems,
                                               boolean useFonts, boolean includeXObjects,
                                               boolean drawing) {
        PlacementSink.kindSet(what);      // refuses a kind that does not exist, before any reading
        if (maxItems < 0) {
            throw new PdfException("pdf.placement: maxItems must be 0 (the default "
                    + PlacementSink.DEFAULT_MAX_ITEMS + ") or a positive number, not " + maxItems);
        }
        String stateMode = PlacementSink.stateOption(state);
        PdfRect box = placementBox(bbox);
        if (dpi != null && (dpi <= 0 || !Double.isFinite(dpi))) {
            throw new PdfException("pdf.placement: dpi is the caller's resolution in dots per inch,"
                    + " not " + dpi + " - the file itself names none (4.2.1, S. 199/200)");
        }
        if (box != null && !PdfSpace.PAGE.equals(box.space())) {
            throw new PdfException("pdf.placement: the filter box is in page space");
        }
        PdfDocument document = open(source);
        return document.placement(requirePage(pageIndex, "pdf.placement"),
                new PdfDocument.Placement(maxPages, useFonts, box, dpi, stateMode, glyphs, maxItems,
                        what, includeXObjects, drawing));
    }

    /**
     * The same read without the segment list: {@code drawing} is false, so a path item answers its
     * envelope and its counts and not its points.
     */
    public static Map<String, Object> placement(Source source, Integer pageIndex, int maxPages,
                                               List<String> what, List<Number> bbox, Double dpi,
                                               String state, boolean glyphs, int maxItems,
                                               boolean useFonts, boolean includeXObjects) {
        return placement(source, pageIndex, maxPages, what, bbox, dpi, state, glyphs, maxItems,
                useFonts, includeXObjects, false);
    }

    /**
     * The same read without the descent option: {@code includeXObjects} is false, so a {@code Do} is
     * named and its form is not walked.
     */
    public static Map<String, Object> placement(Source source, Integer pageIndex, int maxPages,
                                               List<String> what, List<Number> bbox, Double dpi,
                                               String state, boolean glyphs, int maxItems,
                                               boolean useFonts) {
        return placement(source, pageIndex, maxPages, what, bbox, dpi, state, glyphs, maxItems,
                useFonts, false, false);
    }

    /** The {@code bbox} option as a rectangle in page space, or null. */
    private static PdfRect placementBox(List<Number> bbox) {
        if (bbox == null || bbox.isEmpty()) {
            return null;
        }
        if (bbox.size() != 4 || bbox.stream().anyMatch(v -> v == null)) {
            throw new PdfException("pdf.placement: bbox is four numbers in page space,"
                    + " [x1, y1, x2, y2] - not " + bbox);
        }
        double x1 = bbox.get(0).doubleValue();
        double y1 = bbox.get(1).doubleValue();
        double x2 = bbox.get(2).doubleValue();
        double y2 = bbox.get(3).doubleValue();
        return PdfRect.of(Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2),
                PdfSpace.PAGE);
    }

    /**
     * Decodes raw bytes with a single filter (escape hatch for unusual nestings).
     * @param data stored bytes
     * @param filterName filter name, e.g. {@code "FlateDecode"}
     * @param parms filter parameters (may be null)
     * @return decoded bytes and notes
     */
    public static PdfFilters.Result decodeStream(byte[] data, String filterName,
                                                 Map<String, Object> parms) {
        return PdfFilters.decode(data, filterName, parms);
    }

    // ===================================================================
    // Fonts (what a font is, and what one of its codes means)
    // ===================================================================

    /**
     * Lists the fonts of the walked pages: what each one is, where its map and its program are,
     * which source could resolve its codes and on which pages it actually draws text.
     *
     * @param source       the PDF source
     * @param pageIndex    1-based page number (its resources, following {@code /Parent}), or null
     * @param limit        fonts listed (0 = no limit)
     * @param maxPages     page limit of the walk that counts the use of a font
     * @param formXObjects whether the resources of form XObjects are collected too
     * @param full         whether each entry also carries the sheet {@link #font} writes
     * @return {@code {fonts, summary, truncated, notes}}
     */
    public static Map<String, Object> fonts(Source source, Integer pageIndex, int limit,
                                            int maxPages, boolean formXObjects, boolean full) {
        return PdfFont.listing(open(source), requirePage(pageIndex, "pdf.fonts"),
                maxPages > 0 ? maxPages : DEFAULT_MAX_PAGES, formXObjects, full, limit);
    }

    public static Map<String, Object> font(Source source, int objNum, String toUnicodeMode,
                                           String widthsMode, int maxItems) {
        return font(source, objNum, toUnicodeMode, widthsMode, "summary", maxItems);
    }

    /**
     * Describes one font object: subtype, base font, encoding and code space, descendant chain,
     * descriptor, embedded programs and their tables, the {@code /ToUnicode} summary and the widths
     * the dictionary gives ({@code /W} in all three forms, {@code /FirstChar}+{@code /Widths}).
     *
     * @param source        the PDF source
     * @param objNum        object number of the font dictionary
     * @param toUnicodeMode {@code "summary"} (what the map holds), {@code "full"} (plus a bounded
     *                      view of it) or {@code "off"}
     * @param widthsMode    {@code "summary"} (counts) or {@code "full"} (plus the values),
     *                      {@code "off"} leaves the widths out
     * @param programMode   {@code "summary"} (what the tables of the embedded program say),
     *                      {@code "tables"} (plus the table directory), {@code "bytes"} (plus the
     *                      program itself) or {@code "off"}
     * @param maxItems      items listed per block
     * @return the font sheet
     */
    public static Map<String, Object> font(Source source, int objNum, String toUnicodeMode,
                                           String widthsMode, String programMode, int maxItems) {
        List<String> notes = new ArrayList<>();
        return PdfFont.describe(open(source), objNum,
                mode(toUnicodeMode, "summary", "toUnicode"),
                mode(widthsMode, "summary", "widths"),
                programMode(programMode), maxItems, notes);
    }

    /**
     * Normalises the {@code program} option. Unlike the two blocks above it has a third reading:
     * {@code 'tables'} (the directory), {@code 'bytes'} (the file itself, as a Uint8Array) - and a
     * program that is not read at all is the only way to ask for a sheet without opening the
     * biggest stream of the font.
     */
    private static String programMode(String value) {
        if (value == null || value.isBlank()) {
            return "summary";
        }
        switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "summary":
                return "summary";
            case "tables":
                return "tables";
            case "bytes":
                return "bytes";
            case "off":
            case "false":
            case "none":
                return "off";
            default:
                throw new PdfException("pdf.font: option 'program' is '" + value
                        + "' - it reads 'summary', 'tables', 'bytes' or false");
        }
    }

    /**
     * Normalises a {@code 'summary' | 'full' | false} option. A boolean stays the simple case it
     * is in the rest of this API: {@code false} switches the block off, {@code true} asks for the
     * summary - writing {@code 'full'} on purpose is what gets the values.
     */
    private static String mode(String value, String fallback, String what) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "summary":
                return "summary";
            case "full":
                return "full";
            case "off":
            case "false":
            case "none":
                return "off";
            default:
                throw new PdfException("pdf.font: option '" + what + "' is '" + value
                        + "' - it reads 'summary', 'full' or false");
        }
    }

    /**
     * Reads a CMap stream: the {@code /ToUnicode} map of a font or an embedded
     * {@code /CMapFile}. Nothing is executed - the PostScript syntax of the stream is
     * tokenized, see {@link PdfCMap}.
     *
     * @param source       the PDF source
     * @param streamObjNum object number of the CMap stream, or null
     * @param fontObjNum   font object number whose own {@code /ToUnicode} should be read, or null
     * @param codes        requested code samples (hex strings and/or numbers), may be null
     * @param rawCodes     code samples as raw bytes, split by the code space width, may be null
     * @param limit        entries shown when no codes are asked for (0 = no limit)
     * @param maxEntries   budget of the map itself
     * @return code spaces, counts and a view of the map
     */
    public static Map<String, Object> cmap(Source source, Integer streamObjNum,
                                           Integer fontObjNum, List<Object> codes,
                                           byte[] rawCodes, int limit, int maxEntries) {
        PdfDocument document = open(source);
        List<Object> notes = new ArrayList<>();
        List<String> crossChecks = new ArrayList<>();
        Integer objNum = null;
        String kind = "cmap";
        Map<String, Object> head = new LinkedHashMap<>();
        if (fontObjNum != null) {
            Map<String, Object> font = document.dictOf(fontObjNum);
            if (font.isEmpty()) {
                throw new PdfException("Object " + fontObjNum + " of " + source.displayName()
                        + " is not a dictionary, so it cannot be a font");
            }
            head.put("viaFont", (long) fontObjNum);
            PdfName subtype = PdfName.ofDict(font, "Subtype");
            if (subtype != null) {
                head.put("fontSubtype", subtype.toString());
            }
            PdfName baseFont = PdfName.ofDict(font, "BaseFont");
            if (baseFont != null) {
                head.put("baseFont", baseFont.toString());
            }
            objNum = refNum(font, "ToUnicode");
            kind = "toUnicode";
            if (objNum == null) {
                head.put("objNum", null);
                head.put("found", false);
                notes.add("Font " + fontObjNum + " has no /ToUnicode stream: nothing to read");
                Integer descendant = firstRefNum(font, "DescendantFonts");
                if (descendant != null && refNum(document.dictOf(descendant), "ToUnicode") != null) {
                    notes.add("Its descendant font " + descendant + " does have one - read it as"
                            + " pdf.cmap(path, " + refNum(document.dictOf(descendant), "ToUnicode")
                            + ")");
                }
                head.put("notes", notes);
                return head;
            }
        } else if (streamObjNum != null) {
            objNum = streamObjNum;
        } else {
            throw new PdfException("pdf.cmap: an object number or {font: objNum} is required");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("objNum", (long) objNum);
        result.put("kind", kind);
        result.putAll(head);
        byte[] data = document.stream(objNum).data();
        PdfCMap cmap = PdfCMap.parse(data, maxEntries);
        if (fontObjNum != null) {
            // The two halves have to agree: the font's encoding says where the bytes are cut, the
            // CMap says which width it maps. A CMap of one-byte codes next to a two-byte encoding
            // is not a smaller map but a different document, and saying "163 entries" without
            // that would be misleading.
            PdfEncoding encoding = PdfEncoding.of(document, document.dictOf(fontObjNum));
            Integer fontWidth = encoding.codeBytes();
            if (fontWidth != null && cmap.codeBytes() > 0 && !fontWidth.equals(cmap.codeBytes())) {
                crossChecks.add("code-space mismatch: the font's /Encoding says " + fontWidth
                        + "-byte codes, this CMap maps " + cmap.codeBytes() + "-byte codes - the"
                        + " codes of this map do not address the strings of this font");
            }
        }
        result.putAll(cmap.toMap(limit, requestedCodes(cmap, codes, rawCodes)));
        if (!crossChecks.isEmpty()) {
            @SuppressWarnings("unchecked")
            List<Object> warnings = (List<Object>) result.get("warnings");
            if (warnings == null) {
                result.put("warnings", new ArrayList<Object>(crossChecks));
            } else {
                warnings.addAll(crossChecks);
            }
        }
        if (!notes.isEmpty()) {
            result.put("notes", notes);
        }
        return result;
    }

    /**
     * Normalises the requested codes to hex of the right width. Numbers are written in the width
     * the code space says - filling them up with a guess would answer a question nobody asked.
     */
    private static List<String> requestedCodes(PdfCMap cmap, List<Object> codes, byte[] rawCodes) {
        List<String> out = new ArrayList<>();
        int width = cmap.codeBytes();
        if (rawCodes != null && rawCodes.length > 0) {
            if (width <= 0) {
                throw new PdfException("pdf.cmap: raw code bytes need a code space to be split"
                        + " - this CMap declares none, pass the codes as hex");
            }
            if (rawCodes.length % width != 0) {
                throw new PdfException("pdf.cmap: " + rawCodes.length + " raw bytes are not a"
                        + " whole number of " + width + "-byte codes");
            }
            for (int i = 0; i < rawCodes.length; i += width) {
                out.add(PdfCMap.hex(java.util.Arrays.copyOfRange(rawCodes, i, i + width)));
            }
        }
        if (codes != null) {
            for (Object item : codes) {
                if (item instanceof String text) {
                    for (String token : text.split("[,;\\s]+")) {
                        if (!token.isBlank()) {
                            out.add(token);
                        }
                    }
                } else if (item instanceof Number number) {
                    if (width <= 0) {
                        throw new PdfException("pdf.cmap: a code given as a number needs the code"
                                + " space to know its width - pass it as hex instead");
                    }
                    out.add(PdfCMap.hexOf(number.longValue(), width));
                } else {
                    throw new PdfException("pdf.cmap: a code must be a string or a number, not "
                            + item);
                }
            }
        }
        if (out.size() > MAX_CODE_ITEMS) {
            throw new PdfException("pdf.cmap: " + out.size() + " codes are more than the "
                    + MAX_CODE_ITEMS + " a single call answers");
        }
        for (int i = 0; i < out.size(); i++) {
            out.set(i, cmap.requireCode(out.get(i)));
        }
        return out.isEmpty() ? null : out;
    }

    private static Integer refNum(Map<?, ?> dict, String key) {
        return dict.get(key) instanceof PdfReference reference ? reference.objNum() : null;
    }

    private static Integer firstRefNum(Map<?, ?> dict, String key) {
        Object value = dict.get(key);
        if (value instanceof List<?> list && !list.isEmpty()
                && list.get(0) instanceof PdfReference reference) {
            return reference.objNum();
        }
        return refNum(dict, key);
    }

    /**
     * Maps one string through one font: the bytes are cut into codes the way the font's encoding
     * says, and each code is looked up through the chain of
     * {@link PdfGlyphMapper} ({@code /ToUnicode} → UCS code → descendant's map → glyph name →
     * nothing).
     * <p>
     * The font is addressed by object number or by page and resource name - the second form is the
     * one that matches how a page is debugged ("what does this {@code Tf} string mean"), and it
     * resolves through the same resource lookup {@code pdf.text()} uses, form XObjects included.
     * </p>
     *
     * @param source       the PDF source
     * @param fontObjNum   object number of the font dictionary, or null
     * @param pageIndex    1-based page number whose resources name the font, or null
     * @param resourceName the resource name ({@code "/F0"}, also accepted without the slash)
     * @param stringBytes  the string's raw bytes (hex or literal strings are read by
     *                     {@link PdfTextDecoder#stringBytes(String)})
     * @param as           {@code "both"} (items and text), {@code "unicode"} (text only) or
     *                     {@code "codes"} (items only)
     * @param widths       whether each item carries its dictionary width
     * @param maxItems     codes read and listed (0 = no limit)
     * @return {@code {font, split, mapping, items, unicode, unmapped, widths?, truncated?, notes?}}
     */
    public static Map<String, Object> decodeString(Source source, Integer fontObjNum,
                                                   Integer pageIndex, String resourceName,
                                                   byte[] stringBytes, String as, boolean widths,
                                                   int maxItems) {
        String view = decodeAs(as);
        requirePage(pageIndex, "pdf.decodeString");
        PdfDocument document = open(source);
        List<String> notes = new ArrayList<>();
        List<String> resourceNotes = new ArrayList<>();
        FontTarget target = resolveFont(document, fontObjNum, pageIndex, resourceName,
                "pdf.decodeString", notes, resourceNotes);
        Integer objNum = target.objNum();
        Map<String, Object> font = target.dict();
        PdfFont.Ref byName = target.byName();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("font", fontBlock(objNum, font, byName));
        PdfGlyphMapper mapper = PdfGlyphMapper.of(document, objNum, font, notes);
        PdfEncoding encoding = mapper.encoding();
        byte[] bytes = stringBytes == null ? new byte[0] : stringBytes;

        PdfTextDecoder.Result result = PdfTextDecoder.decode(bytes, mapper, maxItems, notes);
        Map<String, Object> split = new LinkedHashMap<>();
        split.put("codeBytes", encoding.codeBytes() == null ? null
                : (long) (int) encoding.codeBytes());
        split.put("codeSpaces", codeSpaceText(mapper.codeSpaces()));
        split.put("source", encoding.shape().source());
        split.put("bytes", (long) bytes.length);
        split.put("codes", result.codes());
        if (result.tail() != null) {
            split.put("tail", result.tail());
        }
        if (encoding.codeBytes() == null) {
            split.put("cut", false);
            split.put("note", "the string was not cut: this font's encoding does not say how wide"
                    + " a code is, so its bytes are shown whole in split.tail");
        }
        out.put("split", split);

        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("method", mapper.method());
        mapping.put("possible", mapper.possible());
        mapping.put("codes", result.read());
        mapping.put("mapped", result.mapped());
        mapping.put("unmapped", result.unmappedCount());
        mapping.put("spaceFallback", result.spaceFallback());
        out.put("mapping", mapping);

        // Widths are asked per code, so that the source of every shown value is known: the
        // dictionary first, the embedded program's hmtx where the dictionary is silent. Asking once
        // per code (not once per item) also keeps a repeated code from being looked up twice.
        Map<Long, PdfGlyphMapper.Width> itemWidths = new LinkedHashMap<>();
        List<Object> widthSources = new ArrayList<>();
        if (widths) {
            for (PdfTextDecoder.Item item : result.items()) {
                PdfGlyphMapper.Width width = mapper.widthOf(item.code());
                itemWidths.put((long) item.index(), width);
                if (width.width() != null && !widthSources.contains(width.source())) {
                    widthSources.add(width.source());
                }
            }
        }
        PdfFontWidths fontWidths = widths ? mapper.widths() : null;
        if (fontWidths != null) {
            Map<String, Object> widthsBlock = fontWidths.toMap();
            if (!widthSources.isEmpty()) {
                widthsBlock.put("itemSources", widthSources);
            }
            out.put("widths", widthsBlock);
            // The sentence "this dictionary names no /Widths" is only true until the program has
            // answered - which is exactly what itemSources says.
            if (fontWidths.note() != null
                    && !widthSources.contains(PdfFontWidths.SOURCE_HMTX)) {
                notes.add(fontWidths.note());
            }
        }
        if (!"unicode".equals(view)) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (PdfTextDecoder.Item item : result.items()) {
                items.add(itemBlock(item, mapper, itemWidths.get((long) item.index())));
            }
            out.put("items", items);
        }
        if (!"codes".equals(view)) {
            out.put("unicode", result.unicode());
        }
        out.put("unmapped", new ArrayList<Object>(result.unmapped()));
        if (result.truncated()) {
            out.put("truncated", true);
        }
        for (String resourceNote : resourceNotes) {
            if (!notes.contains(resourceNote)) {
                notes.add(resourceNote);
            }
        }
        if (!notes.isEmpty()) {
            out.put("notes", new ArrayList<Object>(notes));
        }
        return out;
    }

    /** The {@code font} block: the address, the subtype and - if named that way - the resource. */
    private static Map<String, Object> fontBlock(Integer objNum, Map<String, Object> font,
                                                 PdfFont.Ref byName) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (objNum != null) {
            out.put("objNum", (long) (int) objNum);
        }
        if (byName != null) {
            out.put("resourceName", byName.resourceName());
        }
        PdfName subtype = PdfName.ofDict(font, "Subtype");
        if (subtype != null) {
            out.put("subtype", subtype.toString());
        }
        out.put("kind", PdfFont.kind(subtype));
        PdfName baseFont = PdfName.ofDict(font, "BaseFont");
        if (baseFont != null) {
            out.put("baseFont", baseFont.toString());
        }
        return out;
    }

    /** One item of {@code items}: the code, what it means, who said so and how wide it is. */
    private static Map<String, Object> itemBlock(PdfTextDecoder.Item item, PdfGlyphMapper mapper,
                                                 PdfGlyphMapper.Width width) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("i", (long) item.index());
        out.put("hex", item.hex());
        if (mapper.isCidFont()) {
            out.put("cid", item.code());
            if (item.gid() != null) {
                out.put("gid", item.gid());
            }
        }
        out.put("unicode", item.unicode());
        List<Object> codePoints = new ArrayList<>();
        for (int codePoint : item.codePoints()) {
            codePoints.add((long) codePoint);
        }
        out.put("codePoints", codePoints);
        out.put("mappedBy", item.mappedBy());
        if (item.codePoints().length > 1) {
            out.put("multi", true);
        }
        if (item.continues()) {
            out.put("continues", true);
        }
        if (width != null) {
            out.put("width", width.width());
            out.put("widthSource", width.source());
        }
        return out;
    }

    /** The page object of a 1-based page number (the same walk {@code pdf.text()} takes). */
    private static PdfDocument.Page pageOf(PdfDocument document, int pageIndex,
                                          List<String> notes) {
        // pagesForRead() ignores the listing limit when a page is named: the walk goes to that
        // page and no further, which is why this call has never needed a maxPages of its own.
        List<PdfDocument.Page> pages = document.pagesForRead(pageIndex, 0, notes);
        for (PdfDocument.Page page : pages) {
            if (page.page() == pageIndex) {
                return page;
            }
        }
        throw new PdfException(document.pageNotFound(pageIndex, pages.size(), notes));
    }

    /**
     * A page option is a page number or nothing. Reading {@code {page: 0}} as "no page filter" or
     * as "walk zero pages" would both answer a question nobody asked, and the refusal a caller
     * would otherwise get ("there is no page 0 - the document has 876 page(s)") reads like a bug
     * in the document rather than in the call.
     */
    private static Integer requirePage(Integer pageIndex, String what) {
        if (pageIndex != null && pageIndex < 1) {
            throw new PdfException(what + ": 'page' is " + pageIndex
                    + " - pages are numbered from 1 (leave the option out to read every page)");
        }
        return pageIndex;
    }

    /** The resource names a page does define, for the message that says "no such font". */
    private static String namesOf(List<PdfFont.Ref> refs) {
        if (refs.isEmpty()) {
            return "no fonts at all";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < refs.size() && i < 12; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(refs.get(i).resourceName());
        }
        if (refs.size() > 12) {
            sb.append(" … (").append(refs.size()).append(" in total)");
        }
        return sb.toString();
    }

    /** The code spaces as one compact string ({@code "0000-FFFF"}), the ranges the map has. */
    private static String codeSpaceText(List<PdfCMap.CodeSpace> spaces) {
        if (spaces.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < spaces.size() && i < LISTED_CODE_SPACES; i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            PdfCMap.CodeSpace space = spaces.get(i);
            sb.append(PdfCMap.hexOf(space.from(), space.bytes())).append('-')
                    .append(PdfCMap.hexOf(space.to(), space.bytes()));
        }
        if (spaces.size() > LISTED_CODE_SPACES) {
            sb.append(" +").append(spaces.size() - LISTED_CODE_SPACES).append(" more");
        }
        return sb.toString();
    }

    /** The {@code as} option of {@code pdf.decodeString}. */
    private static String decodeAs(String value) {
        if (value == null || value.isBlank() || "both".equals(value)) {
            return "both";
        }
        switch (value) {
            case "unicode":
            case "codes":
                return value;
            default:
                throw new PdfException("pdf.decodeString: option 'as' is '" + value
                        + "' - it reads 'both', 'unicode' or 'codes'");
        }
    }

    // ===================================================================
    // Glyph metrics (how wide and how high the glyph of a code is)
    // ===================================================================

    /** Glyph units a width is written on, unless the font dictionary says otherwise. */
    private static final double GLYPH_SPACE_UNITS = 1000d;

    /** A font addressed by object number or by the resources of one page. */
    private record FontTarget(Integer objNum, Map<String, Object> dict, PdfFont.Ref byName) {
    }

    /**
     * Resolves the font a call was pointed at: an object number, or a page plus a resource name.
     * The second form is how a page is debugged ("how wide is what {@code /F1} draws here"), and it
     * goes through the same resource lookup {@code pdf.text()} uses - {@code /Parent} inheritance
     * and form XObjects included, so a font a form draws with is reachable too.
     *
     * @param document      the document
     * @param fontObjNum    object number of the font dictionary, or null
     * @param pageIndex     1-based page number whose resources name the font, or null
     * @param resourceName  resource name of the font, with or without the leading slash
     * @param what          the calling function, named in the messages
     * @param notes         note sink of the call
     * @param resourceNotes sink for what the resource walk itself could not read
     * @return the address, the resolved dictionary and - for the named form - the resource entry
     */
    private static FontTarget resolveFont(PdfDocument document, Integer fontObjNum,
                                          Integer pageIndex, String resourceName, String what,
                                          List<String> notes, List<String> resourceNotes) {
        PdfFont.Ref byName = null;
        Integer objNum = fontObjNum;
        if (objNum == null) {
            if (pageIndex == null || resourceName == null) {
                throw new PdfException(what + ": a font is addressed by object number or by"
                        + " {page: n, name: '/F0'}");
            }
            PdfDocument.Page page = pageOf(document, pageIndex, notes);
            String wanted = resourceName.startsWith("/") ? resourceName : "/" + resourceName;
            List<PdfFont.Ref> refs = PdfFont.references(document, page.objNum(), true,
                    resourceNotes);
            for (PdfFont.Ref ref : refs) {
                if (ref.resourceName().equals(wanted)) {
                    byName = ref;
                    objNum = ref.objNum();
                    break;
                }
            }
            if (byName == null) {
                throw new PdfException(what + ": page " + pageIndex + " does not name '" + wanted
                        + "' in its font resources (it names " + namesOf(refs) + ")");
            }
        }
        Map<String, Object> font = byName != null ? byName.dict() : document.dictOf(objNum);
        if (font.isEmpty()) {
            throw new PdfException("Object " + objNum + " of " + document.displayName()
                    + " is not a dictionary, so it cannot be a font");
        }
        return new FontTarget(objNum, font, byName);
    }

    /**
     * Asks one font how wide and how high the glyph of a code is: the dictionary's width
     * ({@code /W}, {@code /Widths}, else the CIDFont's {@code /DW}), the embedded program's
     * {@code hmtx} where the dictionary has no value of its own, and the {@code glyf} bounding box
     * where the program has one.
     * <p>
     * Every number is given twice: on the base the file writes it on (a width on the 1000-unit base
     * of {@code /W}, a box on the program's own {@code unitsPerEm}) and multiplied by
     * {@code fontSize} into text space, because a width nobody can draw with is a trivia. Nothing is
     * drawn and nothing is interpreted: a glyph composed of others is <em>named</em> rather than
     * computed, and a Type 1 or CFF program keeps its advances inside charstrings this module does
     * not execute - there the dictionary is the whole answer and exactly one note says so. A glyph
     * the program does not hold stays {@code width: null}; it does not inherit the last width of
     * {@code hmtx} or the {@code FontBBox} of the descriptor.
     * </p>
     *
     * @param source       the PDF source
     * @param fontObjNum   object number of the font dictionary, or null
     * @param pageIndex    1-based page number whose resources name the font, or null
     * @param resourceName the resource name of the font ({@code "/F0"}), or null
     * @param codes        the codes asked for (hex strings and/or numbers), may be null
     * @param rawCodes     code bytes, cut by the width the encoding says, may be null
     * @param fontSize     the size the text-space values are computed for
     * @param wantBox      whether the program's side bearing and bounding box are read at all
     * @param maxItems     glyphs listed (0 = no limit)
     * @return {@code {font, sources, glyphs, summary, truncated?, notes?}}
     */
    public static Map<String, Object> glyphMetrics(Source source, Integer fontObjNum,
                                                   Integer pageIndex, String resourceName,
                                                   List<Object> codes, byte[] rawCodes,
                                                   double fontSize, boolean wantBox,
                                                   int maxItems) {
        requirePage(pageIndex, "pdf.glyphMetrics");
        PdfDocument document = open(source);
        List<String> notes = new ArrayList<>();
        FontTarget target = resolveFont(document, fontObjNum, pageIndex, resourceName,
                "pdf.glyphMetrics", notes, new ArrayList<>());
        PdfGlyphMapper mapper = PdfGlyphMapper.forMetrics(document, target.objNum(),
                target.dict(), notes);
        List<String> wanted = metricCodes(mapper, codes, rawCodes, notes);
        if (fontSize <= 0) {
            throw new PdfException("pdf.glyphMetrics: fontSize is " + fontSize
                    + " - a glyph is measured for a size greater than zero");
        }

        PdfFontProgram.Read embedded = mapper.program();
        PdfTrueTypeProgram sfnt = embedded == null ? null : embedded.sfnt();
        Integer unitsPerEm = sfnt == null ? null : sfnt.unitsPerEm();
        double perUnit = glyphSpaceUnit(target.dict(), notes);
        double widthFactor = fontSize * perUnit;
        double unitFactor = unitsPerEm == null || unitsPerEm <= 0 ? 0 : fontSize / unitsPerEm;
        // Vertical metrics are dictionary data (W2/DW2), not program data - a CIDFont that declares
        // none has nothing to answer here, and the program's vhea/vmtx are not read by this module.
        boolean verticalAsked = mapper.hasVerticalMetrics();
        if (wantBox) {
            programMetricNote(embedded, notes);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("font", metricsFontBlock(target, mapper, embedded, unitsPerEm, fontSize, perUnit,
                widthFactor, unitFactor));
        out.put("sources", metricsSources(mapper, sfnt, wantBox));

        List<Object> glyphs = new ArrayList<>();
        int answeredWidths = 0;
        int answeredBoxes = 0;
        int composites = 0;
        int glyphless = 0;
        String firstComposite = null;
        String firstGlyphless = null;
        boolean cut = false;
        for (int i = 0; i < wanted.size(); i++) {
            if (maxItems > 0 && glyphs.size() >= maxItems) {
                cut = true;
                break;
            }
            String hex = wanted.get(i);
            long code = Long.parseLong(hex, 16);
            Map<String, Object> glyph = new LinkedHashMap<>();
            glyph.put("hex", hex);
            if (mapper.isCidFont()) {
                glyph.put("cid", code);
            }
            Long gid = wantBox || mapper.isCidFont() ? mapper.glyphOf(code) : null;
            if (gid != null) {
                glyph.put("gid", gid);
            } else if (wantBox && sfnt != null) {
                // Only an sfnt has tables to ask; a Type 1/CFF program (or none at all) is already
                // named by its own sentence, and saying "no glyph" there would report a missing
                // glyph index that no reader of that format would ever have needed.
                glyphless++;
                if (firstGlyphless == null) {
                    firstGlyphless = hex;
                }
            }
            PdfGlyphMapper.Width width = mapper.widthOf(code);
            glyph.put("width", width.width());
            glyph.put("widthSource", width.source());
            if (width.width() != null) {
                glyph.put("widthPt", textSpace(width.width().doubleValue(), widthFactor));
                answeredWidths++;
            }
            if (verticalAsked) {
                // Vectors in the specification's sense: w1 has no horizontal component (5.1.3), and
                // v is the position of the vertical origin relative to the horizontal one.
                PdfFontWidths.Vertical metrics = mapper.verticalOf(code);
                List<Object> w1 = new ArrayList<>();
                w1.add(0L);
                w1.add(metrics.w1y());
                List<Object> position = new ArrayList<>();
                position.add(metrics.vx());
                position.add(metrics.vy());
                glyph.put("w1", w1);
                glyph.put("v", position);
                glyph.put("verticalSource", metrics.source());
                if (metrics.w1y() != null) {
                    List<Object> w1Pt = new ArrayList<>();
                    w1Pt.add(0L);
                    w1Pt.add(textSpace(metrics.w1y().doubleValue(), widthFactor));
                    glyph.put("w1Pt", w1Pt);
                }
                if (metrics.vx() != null && metrics.vy() != null) {
                    List<Object> positionPt = new ArrayList<>();
                    positionPt.add(textSpace(metrics.vx().doubleValue(), widthFactor));
                    positionPt.add(textSpace(metrics.vy().doubleValue(), widthFactor));
                    glyph.put("vPt", positionPt);
                }
            }
            if (wantBox) {
                PdfTrueTypeProgram.Metrics metrics = sfnt == null || gid == null
                        ? null : sfnt.metricsOf((int) (long) gid);
                if (metrics == null) {
                    // Nothing to ask (no program at all, or the font does not say which glyph the
                    // code is): a missing box is stated, not filled with the descriptor's FontBBox.
                    glyph.put("bbox", null);
                    glyph.put("bboxSource", "none");
                } else {
                    boolean glyphEmpty = metrics.empty();
                    if (metrics.lsb() != null && !glyphEmpty) {
                        // A glyph the subset dropped also dropped its bearings: the zero hmtx holds
                        // for it describes an empty box, so `empty: true` says it instead.
                        glyph.put("lsb", (long) (int) metrics.lsb());
                        glyph.put("lsbPt", programUnits(metrics.lsb(), unitFactor));
                    }
                    if (metrics.bbox() != null) {
                        List<Object> box = new ArrayList<>(4);
                        List<Object> boxPt = new ArrayList<>(4);
                        for (int unit : metrics.bbox()) {
                            box.add((long) unit);
                            boxPt.add(programUnits(unit, unitFactor));
                        }
                        glyph.put("bbox", box);
                        glyph.put("bboxPt", boxPt);
                        glyph.put("bboxSource", "glyf");
                        answeredBoxes++;
                    } else if (metrics.composite()) {
                        glyph.put("bbox", null);
                        glyph.put("bboxSource", "composite");
                        composites++;
                        if (firstComposite == null) {
                            firstComposite = hex;
                        }
                    } else {
                        glyph.put("bbox", null);
                        glyph.put("bboxSource", "none");
                        if (glyphEmpty) {
                            // loca says "no description": a space, or a glyph the subset dropped.
                            glyph.put("empty", true);
                        }
                    }
                }
            }
            glyphs.add(glyph);
        }
        out.put("glyphs", glyphs);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("asked", (long) wanted.size());
        summary.put("listed", (long) glyphs.size());
        summary.put("widths", (long) answeredWidths);
        if (wantBox) {
            summary.put("boxes", (long) answeredBoxes);
        }
        out.put("summary", summary);

        if (glyphless > 0) {
            notes.add(glyphless + " code(s) of this answer name no glyph (first: " + firstGlyphless
                    + ") - "
                    + (mapper.isCidFont()
                    ? "the font does not say which glyph that CID is"
                    : "the cmap of the embedded program does not reach them")
                    + ": hmtx and glyf have nothing to answer for them, so only the dictionary"
                    + " width does");
        }
        if (composites > 0) {
            notes.add(composites + " glyph(s) are composed of other glyphs (first: " + firstComposite
                    + "): their box would have to be computed from the parts, so bbox stays null and"
                    + " bboxSource says 'composite' instead of claiming one");
        }
        // A width that is really a default is said here, not only in pdf.font(): a caller who
        // reads `width: 1000, widthSource: DW` has a right to know the file wrote something that
        // could not be read as a width at all.
        for (String defect : mapper.widthDefects()) {
            if (!notes.contains(defect)) {
                notes.add(defect);
            }
        }
        if (answeredWidths == 0 && sfnt != null) {
            // "This dictionary names no /Widths" points at the program's hmtx as the way out, and
            // that pointer is only fair when an sfnt is there to ask. A Base-14 font without a
            // program and a Type 1/CFF program are told by their own sentence above, which says the
            // whole truth at once.
            String widthNote = mapper.widths().note();
            if (widthNote != null && !notes.contains(widthNote)) {
                notes.add(widthNote);
            }
        }
        if (cut) {
            out.put("truncated", true);
            notes.add("only the first " + glyphs.size() + " code(s) are listed (maxItems) -"
                    + " summary.asked counts all of them");
        }
        if (!notes.isEmpty()) {
            out.put("notes", new ArrayList<Object>(notes));
        }
        return out;
    }

    /** The {@code font} block of {@code pdf.glyphMetrics()}: address, bases and the scaling. */
    private static Map<String, Object> metricsFontBlock(FontTarget target, PdfGlyphMapper mapper,
                                                        PdfFontProgram.Read embedded,
                                                        Integer unitsPerEm, double fontSize,
                                                        double perUnit, double widthFactor,
                                                        double unitFactor) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (target.objNum() != null) {
            out.put("objNum", (long) (int) target.objNum());
        }
        if (target.byName() != null) {
            out.put("resourceName", target.byName().resourceName());
        }
        PdfName subtype = PdfName.ofDict(target.dict(), "Subtype");
        if (subtype != null) {
            out.put("subtype", subtype.toString());
        }
        out.put("kind", PdfFont.kind(subtype));
        PdfName baseFont = PdfName.ofDict(target.dict(), "BaseFont");
        if (baseFont != null) {
            out.put("baseFont", baseFont.toString());
        }
        out.put("codeBytes", mapper.codeBytes() == null ? null : (long) (int) mapper.codeBytes());
        if (embedded != null && embedded.objNum() != null) {
            Map<String, Object> program = new LinkedHashMap<>();
            program.put("objNum", (long) embedded.objNum().intValue());
            program.put("kind", embedded.kind());
            out.put("program", program);
        }
        if (unitsPerEm != null) {
            out.put("unitsPerEm", (long) (int) unitsPerEm);
        }
        out.put("fontSize", exact(fontSize));
        // The bases are the exact statement and the factors are its convenience: a Type 3
        // /FontMatrix of 0.001 would otherwise print as 999.9999999999999 units, which is a
        // rounding artefact of the division and not a fact about the font.
        out.put("widthBase", exact(round(1d / perUnit, 6)));
        out.put("scaling", factor(widthFactor));
        if (unitFactor > 0) {
            out.put("glyphScaling", factor(unitFactor));
        }
        return out;
    }

    /** The {@code sources} block: which tables can answer, and which of them this call asked. */
    private static Map<String, Object> metricsSources(PdfGlyphMapper mapper,
                                                      PdfTrueTypeProgram sfnt, boolean wantBox) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("width", mapper.widths().source());
        if (mapper.hasProgramMetrics()) {
            out.put("widthProgram", PdfFontWidths.SOURCE_HMTX);
        }
        if (mapper.hasVerticalMetrics()) {
            out.put("vertical", "W2/DW2");
        }
        if (wantBox) {
            out.put("bbox", sfnt != null && sfnt.hasTable("glyf") ? "glyf" : "none");
            out.put("sideBearing", sfnt != null && sfnt.hasMetrics() ? "hmtx" : "none");
        }
        return out;
    }

    /**
     * The one sentence about a program that cannot answer a box: an sfnt answers from its tables, a
     * Type 1 or CFF program hides its advances inside charstrings, and a font without a program has
     * nothing to ask at all.
     */
    private static void programMetricNote(PdfFontProgram.Read embedded, List<String> notes) {
        if (embedded == null) {
            notes.add("this font embeds no program: the dictionary is the only source of a width"
                    + " here, and nothing in the file says how high or how wide the glyph itself is"
                    + " drawn");
            return;
        }
        if (embedded.sfnt() != null) {
            return;
        }
        notes.add("the embedded program is a " + embedded.kind() + " program, whose widths and"
                + " outlines stand inside charstrings - this module reads a program's tables and"
                + " declarations but executes nothing, so the width comes from the dictionary and a"
                + " bounding box stays unknown (pdf.font(path, objNum) shows what the program does"
                + " declare)");
    }

    /**
     * The codes of a {@code pdf.glyphMetrics()} call as hex. Numbers are written in the width the
     * encoding says and a hex code has to be written in exactly that width - filling a short code up
     * with zeros would answer a question nobody asked (the same rule {@code pdf.cmap()} holds for
     * its samples). A font whose encoding names no width (a {@code CIDFont} addressed by its own
     * object number) is the one case where a code is only a number: nothing has to be cut there, so
     * the value stands on its own and is shown in as many bytes as it needs.
     */
    private static List<String> metricCodes(PdfGlyphMapper mapper, List<Object> codes,
                                            byte[] rawCodes, List<String> notes) {
        Integer width = mapper.codeBytes();
        List<String> out = new ArrayList<>();
        if (rawCodes != null && rawCodes.length > 0) {
            if (width == null) {
                throw new PdfException("pdf.glyphMetrics: raw code bytes need an encoding that says"
                        + " how wide a code is, and this font's does not - pass the codes as hex or"
                        + " as numbers");
            }
            if (rawCodes.length % width != 0) {
                throw new PdfException("pdf.glyphMetrics: " + rawCodes.length + " raw bytes are not"
                        + " a whole number of " + width + "-byte codes");
            }
            for (int i = 0; i < rawCodes.length; i += width) {
                out.add(PdfCMap.hex(java.util.Arrays.copyOfRange(rawCodes, i, i + width)));
            }
        }
        if (codes != null) {
            for (Object item : codes) {
                if (item instanceof String text) {
                    for (String token : text.split("[,;\\s]+")) {
                        if (!token.isBlank()) {
                            out.add(metricCode(token, width));
                        }
                    }
                } else if (item instanceof Number number) {
                    long value = number.longValue();
                    if (value < 0) {
                        throw new PdfException("pdf.glyphMetrics: code " + value
                                + " is negative - a glyph code is a byte value or a CID");
                    }
                    out.add(PdfCMap.hexOf(value, width != null ? width : bytesOf(value)));
                } else {
                    throw new PdfException("pdf.glyphMetrics: a code must be a hex string or a"
                            + " number, not " + item);
                }
            }
        }
        if (out.isEmpty()) {
            throw new PdfException("pdf.glyphMetrics: at least one code is required - '0048',"
                    + " ['41', 65] or a Uint8Array of code bytes");
        }
        if (out.size() > MAX_GLYPH_QUERIES) {
            throw new PdfException("pdf.glyphMetrics: " + out.size() + " codes are more than the "
                    + MAX_GLYPH_QUERIES + " a single call answers (pdf.status().maxGlyphQueries)");
        }
        if (width == null) {
            notes.add("this font's encoding does not say how wide a code is (a CIDFont names no"
                    + " byte width - only the /Type0 above it does), so every code asked for is read"
                    + " as the value it is, not as a cut-out of a string");
        }
        return out;
    }

    /** One requested code as hex of the width the encoding says, or a message naming that width. */
    private static String metricCode(String token, Integer width) {
        String text = token.trim();
        if (text.length() > 1 && text.startsWith("<") && text.endsWith(">")) {
            text = text.substring(1, text.length() - 1);
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.digit(c, 16) < 0) {
                throw new PdfException("pdf.glyphMetrics: code '" + token + "' holds '" + c
                        + "', which is no hex digit");
            }
            sb.append(Character.toUpperCase(c));
        }
        if (sb.length() == 0) {
            throw new PdfException("pdf.glyphMetrics: '" + token + "' holds no hex digit");
        }
        if (sb.length() % 2 != 0 || sb.length() > 8) {
            throw new PdfException("pdf.glyphMetrics: code '" + token + "' is not a whole number of"
                    + " bytes of at most four");
        }
        int bytes = sb.length() / 2;
        if (width != null && bytes != width) {
            throw new PdfException("pdf.glyphMetrics: code '" + token + "' is " + bytes
                    + " byte(s) but this font's encoding says " + width + "-byte codes - write a"
                    + " code in " + (width * 2) + " hex digits ('"
                    + PdfCMap.hexOf(0x48, width) + "' for 72)");
        }
        return sb.toString();
    }

    /** How many bytes a code value needs where the encoding does not say (unknown width only). */
    private static int bytesOf(long value) {
        if (value <= 0xFFL) {
            return 1;
        }
        if (value <= 0xFFFFL) {
            return 2;
        }
        if (value <= 0xFFFFFFL) {
            return 3;
        }
        return 4;
    }

    /**
     * How much of a text-space unit one glyph-space unit is: {@code 1/1000}, unless the font
     * dictionary names a {@code /FontMatrix} (a Type 3 font), which is exactly what turns its glyph
     * space into text space.
     */
    private static double glyphSpaceUnit(Map<String, Object> font, List<String> notes) {
        if (font.get("FontMatrix") instanceof List<?> matrix && !matrix.isEmpty()
                && matrix.get(0) instanceof Number first && first.doubleValue() > 0) {
            notes.add("this font names /FontMatrix, so its widths are in the glyph space that"
                    + " matrix scales and not on the 1000-unit base the other fonts use: the"
                    + " scaling of the text-space values is calculated from it (x " + first
                    + " of a text-space unit per glyph-space unit)");
            return first.doubleValue();
        }
        return 1d / GLYPH_SPACE_UNITS;
    }

    /** A metric of the program's own units in text space, or null when that base is unknown. */
    private static Number programUnits(long value, double unitFactor) {
        if (unitFactor <= 0) {
            return null;
        }
        return textSpace(value, unitFactor);
    }

    /**
     * One value into text space (four decimals). The factor is always a ratio the caller derived
     * from the file - {@code fontSize / widthBase} or {@code fontSize / unitsPerEm} - so this class
     * never rounds a width before it multiplies it.
     */
    private static Number textSpace(double value, double factor) {
        double rounded = Math.round(value * factor * 10_000d) / 10_000d;
        if (rounded == Math.rint(rounded)) {
            // A whole number stays whole (612, not 612.0) - deliberately two statements, because a
            // conditional expression would promote both branches to double.
            return Long.valueOf((long) rounded);
        }
        return Double.valueOf(rounded);
    }

    /** A scaling factor, shown to six decimals (the {@code *Pt} values use the exact ratio). */
    private static Number factor(double value) {
        double rounded = round(value, 6);
        if (rounded == Math.rint(rounded)) {
            return Long.valueOf((long) rounded);
        }
        return Double.valueOf(rounded);
    }

    /** A number that is allowed to be whole: a font size of 10 reads as 10, not 10.0. */
    private static Number exact(double value) {
        if (!Double.isInfinite(value) && !Double.isNaN(value) && value == Math.rint(value)) {
            return Long.valueOf((long) value);
        }
        return Double.valueOf(value);
    }

    /** The value rounded to {@code decimals} places, as a double. */
    private static double round(double value, int decimals) {
        double scale = Math.pow(10, decimals);
        return Math.round(value * scale) / scale;
    }

    // ===================================================================
    // Document structure (what the document means, not how the file is laid out)
    // ===================================================================

    /**
     * The document view: catalogue, pages, tagging, outlines, annotations, form, name trees.
     * @param source the PDF source
     * @param maxPages page limit of the walks that need the page tree
     * @return overview map
     */
    public static Map<String, Object> document(Source source, int maxPages) {
        return new PdfDocumentStructure(open(source)).overview(
                maxPages > 0 ? maxPages : DEFAULT_MAX_PAGES);
    }

    /**
     * One entry per page with its boxes, label, structure key and, on request, its resources
     * and annotations.
     * @param source the PDF source
     * @param maxPages page limit
     * @param withResources whether the resource dictionaries are summarised
     * @param withAnnotations whether the annotations are listed
     * @param maxItems annotations listed per page (0 = no limit)
     * @return one entry per page
     */
    public static List<Map<String, Object>> pages(Source source, int maxPages,
                                                  boolean withResources, boolean withAnnotations,
                                                  int maxItems) {
        return new PdfDocumentStructure(open(source)).pageListings(
                maxPages > 0 ? maxPages : DEFAULT_MAX_PAGES, withResources, withAnnotations,
                maxItems);
    }

    /**
     * The outline (bookmark) tree with resolved destinations.
     * @param source the PDF source
     * @param maxItems item limit (0 = no limit)
     * @param maxDepth nesting limit (0 = no practical limit)
     * @return outline map
     */
    public static Map<String, Object> outlines(Source source, int maxItems, int maxDepth) {
        return new PdfDocumentStructure(open(source)).outlines(
                maxItems < 0 ? DEFAULT_LIMIT : maxItems, maxDepth);
    }

    /**
     * The tagged-PDF structure tree ({@code /StructTreeRoot}).
     * @param source the PDF source
     * @param maxElements element limit (0 = no limit)
     * @param maxDepth nesting limit (0 = default)
     * @param attributes whether attribute lists and attachments are included
     * @return structure tree map
     */
    public static Map<String, Object> structTree(Source source, int maxElements, int maxDepth,
                                                 boolean attributes) {
        return new PdfStructTree(open(source)).tree(
                maxElements < 0 ? PdfStructTree.DEFAULT_MAX_ELEMENTS : maxElements, maxDepth,
                attributes);
    }

    /**
     * The marked-content sequences of the content streams and what the structure tree claims
     * of them; the text of a sequence is read through the font that was current when it opened.
     * @param source the PDF source
     * @param pageIndex 1-based page number or {@code null} for all walked pages
     * @param maxPages page limit of the all-pages read (a named page is reached regardless)
     * @param maxItems sequences per page (0 = no limit)
     * @param maxTextChars characters of text per outermost sequence (0 = none)
     * @return one entry per page
     */
    public static List<Map<String, Object>> markedContent(Source source, Integer pageIndex,
                                                         int maxPages, int maxItems,
                                                         int maxTextChars) {
        return markedContent(source, pageIndex, maxPages, maxItems, maxTextChars, true);
    }

    /**
     * The marked-content sequences of the content streams, with or without their fonts.
     * @param source the PDF source
     * @param pageIndex 1-based page number or {@code null} for all walked pages
     * @param maxPages page limit of the all-pages read (a named page is reached regardless)
     * @param maxItems sequences per page (0 = no limit)
     * @param maxTextChars characters of text per outermost sequence (0 = none)
     * @param useFonts whether the text is read through the font each sequence starts in
     * @return one entry per page
     */
    public static List<Map<String, Object>> markedContent(Source source, Integer pageIndex,
                                                         int maxPages, int maxItems,
                                                         int maxTextChars, boolean useFonts) {
        return new PdfStructTree(open(source)).markedContent(
                requirePage(pageIndex, "pdf.markedContent"),
                maxPages > 0 ? maxPages : PdfStructTree.DEFAULT_MAX_PAGES, maxItems, maxTextChars,
                useFonts);
    }

    /**
     * A name or number tree of the document ({@code /Names/*}, {@code /PageLabels},
     * {@code /ParentTree}, {@code /KIDs}).
     * @param source the PDF source
     * @param tree tree name, e.g. {@code "Dests"} or {@code "EmbeddedFiles"}
     * @param needle case-insensitive key filter (may be {@code null})
     * @param limit entry limit (0 = no limit)
     * @return tree listing
     */
    public static Map<String, Object> names(Source source, String tree, String needle,
                                            int limit) {
        return new PdfDocumentStructure(open(source)).treeListing(tree, needle,
                limit < 0 ? PdfDocumentStructure.DEFAULT_TREE_LIMIT : limit);
    }

    /**
     * Status of the module: limits and scope.
     * @return status map
     */
    public static Map<String, Object> status() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("scope", "file structure, indirect objects, streams, content streams,"
                + " fonts (dictionary, encoding, code space, CMap, the tables of an embedded sfnt"
                + " and the declarations of an embedded Type 1 or CFF program, the width and box of"
                + " a glyph in text space, every string of a page read through its font), document"
                + " structure (catalog, page tree, outlines,"
                + " annotations, form, name trees), the named resources of a stream (every category"
                + " of PDF 1.7 Table 3.30: the name a content stream writes resolved to its object),"
                + " the graphics state a content stream builds (CTM, q/Q, text state, path, clip)"
                + " read as the state machine it is, and the tagged-PDF structure tree with its"
                + " marked content");
        map.put("maxFileBytes", PdfFileStructure.MAX_PDF_BYTES);
        map.put("maxDecodedStreamBytes", PdfFilters.MAX_DECODED_BYTES);
        map.put("defaultLimit", (long) DEFAULT_LIMIT);
        map.put("defaultMaxPages", (long) DEFAULT_MAX_PAGES);
        map.put("defaultMaxTextChars", (long) DEFAULT_MAX_TEXT_CHARS);
        map.put("defaultCmapLimit", (long) DEFAULT_CMAP_LIMIT);
        map.put("maxCmapEntries", (long) MAX_CMAP_ENTRIES);
        map.put("maxCodeItems", (long) MAX_CODE_ITEMS);
        map.put("defaultFontLimit", (long) DEFAULT_FONT_LIMIT);
        map.put("defaultMaxFontItems", (long) DEFAULT_FONT_ITEMS);
        map.put("maxWidthEntries", (long) MAX_WIDTH_ENTRIES);
        map.put("maxDescendantDepth", (long) MAX_DESCENDANT_DEPTH);
        map.put("maxFormXObjects", (long) MAX_FORM_XOBJECTS);
        map.put("maxResourceEntries", (long) MAX_RESOURCE_ENTRIES);
        map.put("maxUseCMapDepth", (long) PdfEncoding.MAX_USE_CMAP_DEPTH);
        map.put("maxDifferences", (long) PdfEncoding.MAX_DIFFERENCES);
        map.put("maxGlyphQueries", (long) MAX_GLYPH_QUERIES);
        map.put("maxProgramTables", (long) PdfFontProgram.MAX_TABLES);
        map.put("maxGlyphNames", (long) PdfType1Program.MAX_GLYPH_NAMES);
        map.put("maxTextCodes", MAX_TEXT_CODES);
        map.put("defaultMaxUnmapped", (long) DEFAULT_MAX_UNMAPPED);
        // ---- The walk's and the placement answer's budgets, one naming (increment D7) ----------
        // The word of a budget is the thing it bounds, so the number a caller meets in an answer is
        // met again under that word in pdf.status(): placement's `maxItems` option and
        // `pdf.status().maxItems` are the same long, `glyphs: true` is bounded by maxGlyphs, the
        // descent of `includeXObjects: true` by maxDepth, the walk itself by maxOps. None of these
        // is a new number - each still comes from the class that owns it, which is the rule a rename
        // may never break ("umbenennen darf keine zweite Zahl erzeugen", plan D7). Because these
        // words are short, §7 of pdf.help() names the option and the function each one bounds.
        map.put("maxOps", (long) PdfContentInterpreter.DEFAULT_MAX_OPERATORS);
        map.put("maxDiagnostics", (long) PdfContentInterpreter.MAX_DIAGNOSTICS);
        map.put("maxItems", (long) DEFAULT_MAX_PLACEMENT_ITEMS);
        map.put("maxGlyphs", (long) MAX_PLACEMENT_DETAILS);
        map.put("maxDepth", (long) MAX_XOBJECT_DEPTH);
        map.put("maxPolygonPoints", (long) MAX_PLACEMENT_POLYGON_POINTS);
        map.put("maxPolygonPointsTotal", (long) PlacementSink.MAX_POLYGON_POINTS_PER_STREAM);
        // Values one `pdf.operations()` item lists (plan P1). The count of operands was always there
        // under the name `operands`; the values arrived with `operandValues`, and they are bounded by
        // this number, which is the only reason the number exists.
        map.put("maxOperandValues", (long) ListingSink.MAX_OPERAND_VALUES);
        map.put("maxPathSegments", (long) PdfPath.MAX_SEGMENTS);
        map.put("maxClipLayers", (long) PdfClipPath.MAX_LAYERS);
        map.put("maxStateDepth", (long) PdfStateStack.MAX_DEPTH);
        map.put("placementKinds", new ArrayList<Object>(PLACEMENT_KINDS));
        // The three views of the graphics state `state` answers (D8c). Named in status() because the
        // third one is only useful if a caller can discover it without reading the source.
        map.put("placementStateValues", new ArrayList<Object>(PlacementSink.STATE_VALUES));
        // The D5/D6 spellings of exactly those numbers, kept for one version so that a run somebody
        // compares with last week's still finds its fields - and each pair is pinned equal by
        // JsPdfPlacementTest.thePlacementBudgetsAreNamedAfterWhatTheyBoundAndTheOldNamesStillAgree
        // (there rather than in JsPdfTest because the guest runs this class and the two names of one
        // budget must not be able to drift apart unnoticed). They go away when the next increment
        // says so; pdf.status().retiredLimits names every pair while both are printed.
        map.put("maxWalkOperators", (long) PdfContentInterpreter.DEFAULT_MAX_OPERATORS);
        map.put("maxWalkDiagnostics", (long) PdfContentInterpreter.MAX_DIAGNOSTICS);
        map.put("defaultMaxPlacementItems", (long) DEFAULT_MAX_PLACEMENT_ITEMS);
        map.put("maxPlacementDetails", (long) MAX_PLACEMENT_DETAILS);
        map.put("maxPlacementPolygonPoints", (long) MAX_PLACEMENT_POLYGON_POINTS);
        map.put("maxPlacementPolygonPointsTotal",
                (long) PlacementSink.MAX_POLYGON_POINTS_PER_STREAM);
        map.put("maxXobjectDepth", (long) MAX_XOBJECT_DEPTH);
        map.put("retiredLimits", new ArrayList<Object>(RETIRED_LIMIT_NAMES.entrySet().stream()
                .map(pair -> pair.getKey() + " is now " + pair.getValue() + " (same number,"
                        + " one name)").toList()));
        // The same promise for a field name rather than a budget: `pdf.operations()` listed the
        // operand COUNT under the name `operands`. Both spellings are in one version's answer, and
        // this line is what makes the pair a statement instead of a silent duplicate (plan P1).
        map.put("retiredFieldNames", new ArrayList<Object>(
                ListingSink.RETIRED_FIELD_NAMES.entrySet().stream()
                        .map(pair -> pair.getKey() + " is now " + pair.getValue() + " (the count"
                                + " kept its number; the values are operandValues)").toList()));
        map.put("wordGapThreshold", TextSink.WORD_GAP_THRESHOLD);
        map.put("maxCidToGidEntries", (long) PdfCidToGidMap.MAX_ENTRIES);
        map.put("glyphListNames", (long) PdfGlyphNames.builtin().size());
        if (PdfGlyphNames.builtin().loadError() != null) {
            map.put("glyphListError", PdfGlyphNames.builtin().loadError());
        }
        map.put("defaultMaxElements", (long) PdfStructTree.DEFAULT_MAX_ELEMENTS);
        map.put("maxOutlineDepth", (long) PdfDocumentStructure.MAX_OUTLINE_DEPTH);
        map.put("maxStructureDepth", (long) PdfStructTree.MAX_STRUCTURE_DEPTH);
        map.put("maxPlainNodes", (long) PdfValues.DEFAULT_MAX_NODES);
        map.put("maxInfoNodes", (long) PdfDocument.INFO_MAX_NODES);
        map.put("maxListedNoteRepeats", (long) PdfValues.EXPLICIT_NOTES_PER_KIND);
        map.put("filters", PdfFilters.supportedFilters());
        map.put("passthroughFilters", List.of("DCTDecode", "JPXDecode", "JBIG2Decode",
                "CCITTFaxDecode"));
        map.put("notImplemented", List.of(
                "the code tables of the non-UCS predefined CMaps (/UniCNS-EUC-H, /EUC-KR-H, …) and"
                        + " the base encodings of Annex D - both are named, not applied, so a font"
                        + " encoded that way keeps its strings unread (they stay out of the text and"
                        + " are counted in mapping.unmapped)",
                "CID->Unicode for the Adobe orderings (they need the O-Charset collections)",
                "the widths and outlines a Type 1 or CFF program carries in its charstrings: an sfnt"
                        + " program is read (head/hhea/maxp/hmtx/loca/glyf/cmap), a Type 1 program is"
                        + " read as far as its declarations go (/FontMatrix, /FontBBox, the glyph"
                        + " names of its /Encoding and /CharStrings) and a CFF as far as its DICTs go"
                        + " (its charset and its font families) - but no charstring is executed, so"
                        + " pdf.glyphMetrics() answers such a glyph from the dictionary alone"
                        + " (/Widths, /W) and gives it no box",
                "the standard string table of the CFF specification: a glyph name below SID 391 is"
                        + " reported as sid<N> and cannot become a character through such a charset",
                "the meaning of a colour space, a pattern or a shading: pdf.pages(path,"
                        + " {resources: true}) names them, gives their object address and their"
                        + " /Subtype and shows their dictionary - what the colour is, how the tile"
                        + " repeats or what the gradient paints is not interpreted here",
                "reading a colour out of a colour: pdf.placement()'s colour fields name the numbers"
                        + " the operator wrote, the space the operator named and the byte that wrote"
                        + " them - there is no conversion from ICCBased, Cal*, Lab or Separation, no"
                        + " ICC profile, no rendering intent, no CMYK-to-RGB, no follow-through of a"
                        + " page's DefaultGray/DefaultRGB/DefaultCMYK resources (4.5.4, S. 257/258 -"
                        + " such a space is named in the page's notes, never applied), no alpha"
                        + " composed with the state's CA/ca",
                "the components behind a scn/SCN in a space this module did not read: 4.5.7, S. 288"
                        + " makes their number and meaning a property of the colour space, so the"
                        + " answer keeps the operands as text, names the pattern's resource name and"
                        + " leaves components null - and a tiling pattern is not painted, only named",
                "which items of pdf.placement() belong to one figure: an item answers its own"
                        + " geometry, and with {drawing: true} a caller can see that four closed edges"
                        + " stand where a frame is drawn. That they belong together is a statement"
                        + " about the producer's intent, which the file does not make - so placement"
                        + " counts, names and locates, and groups nothing (plan D8)",
                "glyph outlines and composite glyphs: an sfnt is read as far as its bounding boxes"
                        + " reach (head and glyf), nothing is drawn, no outline is interpreted and"
                        + " no page is laid out",
                "the box of a text item in pdf.placement(): an item names its pen position, the"
                        + " advance its operand caused and where the widths came from, and answers"
                        + " bboxSource 'advance-only' - the rectangle a glyph covers needs its"
                        + " outline (pdf.glyphMetrics() gives those per code), and unioning them here"
                        + " would be typesetting, not reading",
                "the item kinds pdf.placement() does not collect: marked-content spans (the kind is"
                        + " named in what and refused there on purpose - pdf.markedContent() is the"
                        + " function that answers a span, because an MCID lives in the /ParentTree and"
                        + " not in the BDC operand); a page that opened sequences says so in its notes"
                        + " so that summary.marks: 0 is never read as 'this page marked nothing', and"
                        + " asking for a word that names no kind answers with an error naming the kinds"
                        + " that exist, rather than with a list that leaves the asked kind out",
                "a page rendered, a path filled and a curve flattened: the box of a path is the"
                        + " envelope of its points and says so with bboxSource 'control-polygon',"
                        + " because finding where a Bézier turns means flattening it and flattening is"
                        + " rendering",
                "the pixels of an image: pdf.placement() names a picture's box (the 1 x 1 unit square"
                        + " of image space, mapped) and the byte count an inline image's stream holds,"
                        + " which is what was measured - the sample count would need /BitsPerComponent,"
                        + " /ColorSpace, /Decode and /AltDecode applied, and that is decoding",
                "widths of a simple TrueType font whose program has no readable cmap: hmtx counts"
                        + " by glyph index, and without the cmap a byte code does not name one",
                "the text inside a form XObject: pdf.text() reads the page's own content stream"
                        + " (pdf.fonts(path, {formXObjects: true}) at least lists the fonts an"
                        + " XObject would need)",
                "decryption of encrypted documents (the encryption is described, not applied)",
                "page rendering and form filling",
                "XFA form packets (the AcroForm fields are read, the XFA stream is not"
                        + " interpreted)",
                "digital signatures (their structure is listed, the cryptographic state is not"
                        + " verified)"));
        map.put("rawBytes", "for byte-level questions use fs.readBytes / fs.readHex - pdf never"
                + " rewrites a document");
        return map;
    }
}
