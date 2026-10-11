package org.rogmann.mcp2sdk.js;

import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyArray;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.rogmann.mcp2sdk.pdf.PdfDocumentStructure;
import org.rogmann.mcp2sdk.pdf.PdfException;
import org.rogmann.mcp2sdk.pdf.PdfFilters;
import org.rogmann.mcp2sdk.pdf.PdfStructTree;
import org.rogmann.mcp2sdk.pdf.PdfTextDecoder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bridge between GraalVM JavaScript and {@link JsPdf} (PDF reading without an external
 * library).
 * <p>
 * Creates a {@link ProxyObject} namespace {@code "pdf"} that exposes the file structure
 * (header, body, cross-reference table/stream, trailer), every PDF object type including
 * indirect and object-stream-packed objects, streams with their filter chain decoded, the
 * content streams of the pages, the fonts a page names (described from their dictionary - kind,
 * encoding, widths, embedded programs and the tables of an embedded sfnt program,
 * {@code /ToUnicode}) and the strings of a page read through those fonts ({@code pdf.text()}, {@code pdf.contentStreams()} and {@code pdf.markedContent()}
 * follow the {@code Tf} operators and ask the mapping chain per string, reporting per page what
 * became characters and what stayed a code), the metrics of one glyph
 * ({@code pdf.glyphMetrics()} - the width of {@code /W}/{@code /Widths}/{@code hmtx} and the box
 * {@code glyf} writes, each on the base the file uses and again in text space at a given size), plus
 * the document layer above them: the
 * catalogue with its page tree, outlines, annotations, form and name trees, and the logical
 * structure of a tagged PDF ({@code /StructTreeRoot}, {@code /ParentTree}, marked content).
 * Results are plain JS
 * structures; stream bytes are real {@code Uint8Array} values (the unified byte contract of the
 * JS tool family).
 * </p>
 *
 * <h3>Usage in JavaScript</h3>
 * <pre>{@code
 * pdf.structure("doc/report.pdf");                        // header, xref sections, trailer, eof
 * pdf.info("doc/report.pdf");                             // producer, dates, /Root, /ID
 * pdf.objects("doc/report.pdf", {limit: 200});            // what is in the file
 * pdf.object("doc/report.pdf", 12, {resolveRefs: true});   // one object, references followed
 * var bytes = pdf.stream("doc/report.pdf", 5);            // FlateDecode applied -> Uint8Array
 * fs.writeBytes("tmp/content.bin", bytes);                // hand it on to other tools
 * pdf.operations("doc/report.pdf", 5);                    // the drawing commands, readable
 * pdf.text("doc/report.pdf", {page: 1});                  // text of one page, through its fonts
 * pdf.text("doc/report.pdf", {page: 21}).mapping;         // what was read, what stayed a code
 * pdf.text("doc/report.pdf", {page: 21, fonts: false});   // the raw view (document convention)
 * pdf.fonts("doc/report.pdf");                            // every font, and where it draws
 * pdf.font("doc/report.pdf", 4, {widths: 'full'});         // one font: encoding, widths, maps
 * pdf.font("doc/report.pdf", 4, {program: 'tables'});      // plus the tables of its font program
 * pdf.cmap("doc/report.pdf", {font: 4}, {codes: "41 0B"}); // what those glyph codes mean
 * pdf.decodeString("doc/report.pdf", 4, "<0B41>");          // -> "ffA", with the source per code
 * pdf.glyphMetrics("doc/report.pdf", 4, "41", {fontSize: 10}); // width, its text-space value, box
 * pdf.document("doc/report.pdf");                         // catalog, pages, tagging, form
 * pdf.pages("doc/report.pdf", {resources: true});         // boxes, labels, annotations
 * pdf.outlines("doc/report.pdf");                         // bookmarks, destinations resolved
 * pdf.structTree("doc/report.pdf");                       // tagged-PDF structure tree
 * pdf.markedContent("doc/report.pdf", {page: 1});         // marked content vs. structure tree
 * pdf.names("doc/report.pdf", "EmbeddedFiles");           // name and number trees
 * pdf.placement("doc/report.pdf", {page: 21});            // where the text stands, in page space
 * pdf.placement("doc/report.pdf", {page: 21, what: ['all']}); // + clips, paths, pictures, XObjects
 * pdf.placement("doc/report.pdf", {what: ['clips']});     // each W/W* with the region it leaves
 * pdf.placement("doc/report.pdf", {page: 3, what: ['xobjects'], includeXObjects: true});
 *                                                          // descend into a form's own stream
 * }</pre>
 */
public class JsPdfBridge implements JsModuleInterface {

    /** Supported options of {@code pdf.objects} (strict). */
    private static final Set<String> OBJECTS_OPTIONS = Set.of("limit", "includeFree", "salvage");

    /** Supported options of {@code pdf.object} (strict). */
    private static final Set<String> OBJECT_OPTIONS =
            Set.of("resolveRefs", "maxDepth", "maxStringChars");

    /** Supported options of {@code pdf.streams} (strict). */
    private static final Set<String> STREAMS_OPTIONS = Set.of("limit");

    /** Supported options of {@code pdf.stream} / {@code pdf.streamText} (strict). */
    private static final Set<String> STREAM_OPTIONS = Set.of("decode", "encoding");

    /** Supported options of {@code pdf.contentStreams} (strict). */
    private static final Set<String> CONTENT_OPTIONS =
            Set.of("maxPages", "text", "maxTextChars", "fonts");

    /** Supported options of {@code pdf.operations} (strict). */
    private static final Set<String> OPERATIONS_OPTIONS = Set.of("maxItems", "after");

    /** Supported options of {@code pdf.text} (strict). */
    private static final Set<String> TEXT_OPTIONS =
            Set.of("page", "maxPages", "fonts", "maxUnmapped");

    /** Supported options of {@code pdf.fonts} (strict). */
    private static final Set<String> FONTS_OPTIONS =
            Set.of("page", "limit", "maxPages", "formXObjects", "detail");

    /** Supported options of {@code pdf.font} (strict). */
    private static final Set<String> FONT_OPTIONS =
            Set.of("toUnicode", "widths", "program", "maxItems");

    /** Supported options of {@code pdf.cmap} (strict). */
    private static final Set<String> CMAP_OPTIONS = Set.of("codes", "limit", "maxEntries");

    /** Supported options of {@code pdf.decodeString} (strict). */
    private static final Set<String> DECODE_OPTIONS = Set.of("as", "widths", "maxItems");

    /** Supported options of {@code pdf.glyphMetrics} (strict). */
    private static final Set<String> METRICS_OPTIONS = Set.of("fontSize", "bbox", "maxItems");

    /** Supported options of {@code pdf.document} (strict). */
    private static final Set<String> DOCUMENT_OPTIONS = Set.of("maxPages");

    /** Supported options of {@code pdf.pages} (strict). */
    private static final Set<String> PAGES_OPTIONS =
            Set.of("maxPages", "resources", "annotations", "maxItems");

    /** Supported options of {@code pdf.outlines} (strict). */
    private static final Set<String> OUTLINES_OPTIONS = Set.of("maxItems", "maxDepth");

    /** Supported options of {@code pdf.structTree} (strict). */
    private static final Set<String> STRUCT_OPTIONS =
            Set.of("maxElements", "maxDepth", "attributes");

    /** Supported options of {@code pdf.markedContent} (strict). */
    private static final Set<String> MARKED_OPTIONS =
            Set.of("page", "maxPages", "maxItems", "textChars", "fonts");

    /** Supported options of {@code pdf.names} (strict). */
    private static final Set<String> NAMES_OPTIONS = Set.of("needle", "limit");

    /** Supported options of {@code pdf.placement} (strict). */
    private static final Set<String> PLACEMENT_OPTIONS = Set.of("page", "maxPages", "what",
            "bbox", "dpi", "state", "glyphs", "maxItems", "fonts", "includeXObjects", "drawing");

    /**
     * Creates the bridge (the namespace itself is built per call with the context's
     * {@code Uint8Array} constructor).
     */
    public JsPdfBridge() {
        // stateless
    }

    @Override
    public String getNamespace() {
        return "pdf";
    }

    @Override
    public String getSummary() {
        return "`pdf.help()` explains PDF file structure, objects, streams, content streams read as"
                + " the state machine they are (one walk over CTM, q/Q, text state, path and clip,"
                + " answered by pdf.placement(): where a page's text, clips, paths, external objects"
                + " and pictures stand, every number in a named space, with the walk's findings in a"
                + " diagnostics block), fonts (dictionary, encoding, widths, the tables of an embedded"
                + " font program, CMap, the width and box of a glyph in text space, every string of a"
                + " page read through its font) and document structure (page tree, outlines,"
                + " tagged PDF)";
    }

    @Override
    public String getHelpTip() {
        return "pdf.help() (PDF file structure, objects, streams, content streams and placement,"
                + " fonts and glyph codes, document and tag structure)";
    }

    @Override
    public AutoCloseable wireApi(Value jsBindings) {
        jsBindings.putMember("pdf", createPdfNamespace(jsBindings.getMember("Uint8Array")));
        return null;
    }

    /**
     * Creates a ProxyObject representing the pdf namespace for JavaScript.
     * @param uint8ArrayCtor the JS {@code Uint8Array} constructor from the bindings
     * @return ProxyObject with pdf methods
     */
    public static ProxyObject createPdfNamespace(Value uint8ArrayCtor) {
        Map<String, Object> methods = new HashMap<>();

        methods.put("structure", (ProxyExecutable) args -> {
            requireArgs(args, 1, "structure(pathOrBytes)");
            return GraalProxies.toProxyObject(JsPdf.structure(source(args[0])));
        });

        methods.put("info", (ProxyExecutable) args -> {
            requireArgs(args, 1, "info(pathOrBytes)");
            return GraalProxies.toProxyObject(JsPdf.info(source(args[0])));
        });

        methods.put("objects", (ProxyExecutable) args -> {
            requireArgs(args, 1, "objects(pathOrBytes[, {limit, includeFree, salvage}])");
            Options options = Options.parse(argOrNull(args, 1), OBJECTS_OPTIONS, "objects");
            return toList(JsPdf.objects(source(args[0]),
                    options.getInt("limit", JsPdf.DEFAULT_LIMIT),
                    options.getBoolean("includeFree", false),
                    options.getBoolean("salvage", false)));
        });

        methods.put("object", (ProxyExecutable) args -> {
            requireArgs(args, 2, "object(pathOrBytes, objNum[, {resolveRefs, maxDepth,"
                    + " maxStringChars}])");
            Options options = Options.parse(argOrNull(args, 2), OBJECT_OPTIONS, "object");
            return GraalProxies.toProxyObject(JsPdf.object(source(args[0]),
                    objNum(args[1]),
                    options.getBoolean("resolveRefs", false),
                    options.getInt("maxDepth", 40),
                    options.getInt("maxStringChars", 200_000)));
        });

        methods.put("streams", (ProxyExecutable) args -> {
            requireArgs(args, 1, "streams(pathOrBytes[, {limit}])");
            Options options = Options.parse(argOrNull(args, 1), STREAMS_OPTIONS, "streams");
            return toList(JsPdf.streams(source(args[0]), options.getInt("limit", 0)));
        });

        methods.put("stream", (ProxyExecutable) args -> {
            requireArgs(args, 2, "stream(pathOrBytes, objNum[, {decode}])");
            Options options = Options.parse(argOrNull(args, 2), STREAM_OPTIONS, "stream");
            return toU8(uint8ArrayCtor, JsPdf.stream(source(args[0]), objNum(args[1]),
                    options.getBoolean("decode", true)));
        });

        methods.put("streamText", (ProxyExecutable) args -> {
            requireArgs(args, 2, "streamText(pathOrBytes, objNum[, {decode, encoding}])");
            Options options = Options.parse(argOrNull(args, 2), STREAM_OPTIONS, "streamText");
            return JsPdf.streamText(source(args[0]), objNum(args[1]),
                    options.getBoolean("decode", true), options.getString("encoding", "pdfdoc"));
        });

        methods.put("contentStreams", (ProxyExecutable) args -> {
            requireArgs(args, 1,
                    "contentStreams(pathOrBytes[, {maxPages, text, maxTextChars,"
                            + " fonts: 'auto'|false}])");
            Options options = Options.parse(argOrNull(args, 1), CONTENT_OPTIONS, "contentStreams");
            return toList(JsPdf.contentStreams(source(args[0]),
                    options.getInt("maxPages", JsPdf.DEFAULT_MAX_PAGES),
                    options.getBoolean("text", false),
                    options.getInt("maxTextChars", JsPdf.DEFAULT_MAX_TEXT_CHARS),
                    useFonts(options, "contentStreams")));
        });

        methods.put("operations", (ProxyExecutable) args -> {
            requireArgs(args, 2, "operations(pathOrBytes, objNum[, {maxItems, after}])");
            Options options = Options.parse(argOrNull(args, 2), OPERATIONS_OPTIONS, "operations");
            return GraalProxies.toProxyObject(JsPdf.contentOperations(source(args[0]),
                    objNum(args[1]),
                    options.getInt("maxItems", 100),
                    options.getInt("after", 0)));
        });

        methods.put("text", (ProxyExecutable) args -> {
            requireArgs(args, 1, "text(pathOrBytes[, {page, maxPages, fonts: 'auto'|false,"
                    + " maxUnmapped: 20}])");
            Options options = Options.parse(argOrNull(args, 1), TEXT_OPTIONS, "text");
            Object page = options.get("page");
            Integer pageIndex = page == null ? null : (int) longValue(page, "page");
            return GraalProxies.toProxyObject(JsPdf.text(source(args[0]), pageIndex,
                    options.getInt("maxPages", JsPdf.DEFAULT_MAX_PAGES),
                    useFonts(options, "text"),
                    options.getInt("maxUnmapped", JsPdf.DEFAULT_MAX_UNMAPPED)));
        });

        methods.put("fonts", (ProxyExecutable) args -> {
            requireArgs(args, 1, "fonts(pathOrBytes[, {page, limit: 500, maxPages: 200,"
                    + " formXObjects: false, detail: 'list'|'full'}])");
            Options options = Options.parse(argOrNull(args, 1), FONTS_OPTIONS, "fonts");
            Object page = options.get("page");
            Integer pageIndex = page == null ? null : (int) longValue(page, "page");
            return GraalProxies.toProxyObject(JsPdf.fonts(source(args[0]), pageIndex,
                    options.getInt("limit", JsPdf.DEFAULT_FONT_LIMIT),
                    options.getInt("maxPages", JsPdf.DEFAULT_MAX_PAGES),
                    options.getBoolean("formXObjects", false),
                    detailIsFull(options)));
        });

        methods.put("font", (ProxyExecutable) args -> {
            requireArgs(args, 2, "font(pathOrBytes, objNum[, {toUnicode: 'summary'|'full'|false,"
                    + " widths: 'summary'|'full'|false, program: 'summary'|'tables'|'bytes'|false,"
                    + " maxItems: 200}])");
            Options options = Options.parse(argOrNull(args, 2), FONT_OPTIONS, "font");
            Map<String, Object> sheet = JsPdf.font(source(args[0]), objNum(args[1]),
                    modeOption(options, "toUnicode"), modeOption(options, "widths"),
                    modeOption(options, "program"),
                    options.getInt("maxItems", JsPdf.DEFAULT_FONT_ITEMS));
            return GraalProxies.toProxyObject(programBytesAsU8(sheet, uint8ArrayCtor));
        });

        methods.put("cmap", (ProxyExecutable) args -> {
            requireArgs(args, 2, "cmap(pathOrBytes, objNum|{font: objNum}[, {codes, limit,"
                    + " maxEntries}])");
            Value options = argOrNull(args, 2);
            Options parsed = Options.parse(options, CMAP_OPTIONS, "cmap");
            CodeQuery query = codesArgument(Options.member(options, "codes"), "cmap");
            Integer fontObjNum = null;
            Integer streamObjNum = null;
            Value target = args[1];
            if (target != null && !target.isNull() && target.hasMembers()
                    && !target.hasArrayElements()) {
                if (!target.hasMember("font")) {
                    throw new IllegalArgumentException("pdf.cmap: an object argument needs a"
                            + " 'font' member - pdf.cmap(path, {font: 4}) reads the /ToUnicode of"
                            + " font 4");
                }
                fontObjNum = objNum(target.getMember("font"));
            } else {
                streamObjNum = objNum(target);
            }
            return GraalProxies.toProxyObject(JsPdf.cmap(source(args[0]), streamObjNum, fontObjNum,
                    query.codes(), query.rawCodes(),
                    parsed.getInt("limit", JsPdf.DEFAULT_CMAP_LIMIT),
                    parsed.getInt("maxEntries", JsPdf.MAX_CMAP_ENTRIES)));
        });

        methods.put("decodeString", (ProxyExecutable) args -> {
            requireArgs(args, 3, "decodeString(pathOrBytes, objNum|{page: n, name: '/F0'},"
                    + " string[, {as: 'both'|'unicode'|'codes', widths: false, maxItems: 2000}])");
            Options options = Options.parse(argOrNull(args, 3), DECODE_OPTIONS, "decodeString");
            FontRef font = fontReference(args[1], "decodeString");
            return GraalProxies.toProxyObject(JsPdf.decodeString(source(args[0]), font.objNum(),
                    font.pageIndex(), font.resourceName(), stringArgument(args[2]),
                    options.getString("as", "both"),
                    options.getBoolean("widths", false),
                    options.getInt("maxItems", JsPdf.MAX_GLYPH_QUERIES)));
        });

        methods.put("glyphMetrics", (ProxyExecutable) args -> {
            requireArgs(args, 3, "glyphMetrics(pathOrBytes, objNum|{page: n, name: '/F0'}, codes"
                    + "[, {fontSize: 1, bbox: true, maxItems: 2000}])");
            Options options = Options.parse(argOrNull(args, 3), METRICS_OPTIONS, "glyphMetrics");
            FontRef font = fontReference(args[1], "glyphMetrics");
            CodeQuery query = codesArgument(args[2], "glyphMetrics");
            return GraalProxies.toProxyObject(JsPdf.glyphMetrics(source(args[0]), font.objNum(),
                    font.pageIndex(), font.resourceName(), query.codes(), query.rawCodes(),
                    options.getDouble("fontSize", 1),
                    options.getBoolean("bbox", true),
                    options.getInt("maxItems", JsPdf.MAX_GLYPH_QUERIES)));
        });

        methods.put("placement", (ProxyExecutable) args -> {
            requireArgs(args, 1, "placement(pathOrBytes[, {page, maxPages,"
                    + " what: ['text'] | 'clips' | 'paths' | 'xobjects' | 'images' | 'all', bbox,"
                    + " dpi, state: 'key'|'full'|'colour', glyphs: false, maxItems,"
                    + " fonts: 'auto'|false, includeXObjects: false, drawing: false}])");
            Value optionValue = argOrNull(args, 1);
            Options options = Options.parse(optionValue, PLACEMENT_OPTIONS, "placement");
            Object page = options.get("page");
            Integer pageIndex = page == null ? null : (int) longValue(page, "page");
            List<Object> what = listMember(Options.member(optionValue, "what"), "what");
            List<Object> box = listMember(Options.member(optionValue, "bbox"), "bbox");
            List<Number> bbox = new ArrayList<>();
            for (Object value : box) {
                if (!(value instanceof Number number)) {
                    throw new IllegalArgumentException("pdf.placement: bbox is four numbers in page"
                            + " space, [x1, y1, x2, y2] - '" + value + "' is not a number");
                }
                bbox.add(number);
            }
            Object dpiValue = options.get("dpi");
            Double dpi = null;
            if (dpiValue != null) {
                if (!(dpiValue instanceof Number number)) {
                    throw new IllegalArgumentException("pdf.placement: dpi must be a number (dots"
                            + " per inch, the caller's own - the file names none)");
                }
                dpi = number.doubleValue();
            }
            List<String> kinds = new ArrayList<>();
            for (Object value : what) {
                kinds.add(String.valueOf(value));
            }
            return GraalProxies.toProxyObject(JsPdf.placement(source(args[0]), pageIndex,
                    options.getInt("maxPages", JsPdf.DEFAULT_MAX_PAGES), kinds,
                    bbox.isEmpty() ? null : bbox, dpi,
                    options.getString("state", "key"),
                    options.getBoolean("glyphs", false),
                    options.getInt("maxItems", JsPdf.DEFAULT_MAX_PLACEMENT_ITEMS),
                    useFonts(options, "placement"),
                    options.getBoolean("includeXObjects", false),
                    options.getBoolean("drawing", false)));
        });

        methods.put("document", (ProxyExecutable) args -> {
            requireArgs(args, 1, "document(pathOrBytes[, {maxPages}])");
            Options options = Options.parse(argOrNull(args, 1), DOCUMENT_OPTIONS, "document");
            return GraalProxies.toProxyObject(JsPdf.document(source(args[0]),
                    options.getInt("maxPages", JsPdf.DEFAULT_MAX_PAGES)));
        });

        methods.put("pages", (ProxyExecutable) args -> {
            requireArgs(args, 1, "pages(pathOrBytes[, {maxPages, resources, annotations,"
                    + " maxItems}])");
            Options options = Options.parse(argOrNull(args, 1), PAGES_OPTIONS, "pages");
            return toList(JsPdf.pages(source(args[0]),
                    options.getInt("maxPages", JsPdf.DEFAULT_MAX_PAGES),
                    options.getBoolean("resources", false),
                    options.getBoolean("annotations", true),
                    options.getInt("maxItems", PdfDocumentStructure.DEFAULT_MAX_ANNOTATIONS)));
        });

        methods.put("outlines", (ProxyExecutable) args -> {
            requireArgs(args, 1, "outlines(pathOrBytes[, {maxItems, maxDepth}])");
            Options options = Options.parse(argOrNull(args, 1), OUTLINES_OPTIONS, "outlines");
            return GraalProxies.toProxyObject(JsPdf.outlines(source(args[0]),
                    options.getInt("maxItems", PdfDocumentStructure.DEFAULT_MAX_ITEMS),
                    options.getInt("maxDepth", 0)));
        });

        methods.put("structTree", (ProxyExecutable) args -> {
            requireArgs(args, 1, "structTree(pathOrBytes[, {maxElements, maxDepth, attributes}])");
            Options options = Options.parse(argOrNull(args, 1), STRUCT_OPTIONS, "structTree");
            return GraalProxies.toProxyObject(JsPdf.structTree(source(args[0]),
                    options.getInt("maxElements", PdfStructTree.DEFAULT_MAX_ELEMENTS),
                    options.getInt("maxDepth", 0),
                    options.getBoolean("attributes", false)));
        });

        methods.put("markedContent", (ProxyExecutable) args -> {
            requireArgs(args, 1, "markedContent(pathOrBytes[, {page, maxPages, maxItems,"
                    + " textChars, fonts: 'auto'|false}])");
            Options options = Options.parse(argOrNull(args, 1), MARKED_OPTIONS, "markedContent");
            Object page = options.get("page");
            Integer pageIndex = page == null ? null : (int) longValue(page, "page");
            return toList(JsPdf.markedContent(source(args[0]), pageIndex,
                    options.getInt("maxPages", PdfStructTree.DEFAULT_MAX_PAGES),
                    options.getInt("maxItems", PdfStructTree.DEFAULT_MAX_ITEMS),
                    options.getInt("textChars", PdfStructTree.DEFAULT_MAX_TEXT_CHARS),
                    useFonts(options, "markedContent")));
        });

        methods.put("names", (ProxyExecutable) args -> {
            requireArgs(args, 2, "names(pathOrBytes, tree[, {needle, limit}])");
            Options options = Options.parse(argOrNull(args, 2), NAMES_OPTIONS, "names");
            if (!args[1].isString()) {
                throw new IllegalArgumentException("pdf.names: the tree name must be a string,"
                        + " e.g. 'Dests', 'EmbeddedFiles', 'PageLabels' or 'ParentTree'");
            }
            return GraalProxies.toProxyObject(JsPdf.names(source(args[0]), args[1].asString(),
                    options.getString("needle", null),
                    options.getInt("limit", PdfDocumentStructure.DEFAULT_TREE_LIMIT)));
        });

        methods.put("decodeStream", (ProxyExecutable) args -> {
            requireArgs(args, 2, "decodeStream(data, filterName[, parms])");
            byte[] data = GraalProxies.toByteArray(args[0]);
            if (!args[1].isString()) {
                throw new IllegalArgumentException("pdf.decodeStream: the filter name must be a"
                        + " string, e.g. 'FlateDecode'");
            }
            Map<String, Object> parms = parmsFromValue(argOrNull(args, 2));
            PdfFilters.Result result = JsPdf.decodeStream(data, args[1].asString(), parms);
            Map<String, Object> out = new LinkedHashMap<>();
            // A Java byte[] inside a proxy object would depend on the HostAccess policy, so the
            // bytes go out as a real Uint8Array (the byte contract of the JS tool family).
            out.put("data", toU8(uint8ArrayCtor, result.data()));
            out.put("bytes", (long) result.data().length);
            if (!result.notes().isEmpty()) {
                out.put("notes", new ArrayList<Object>(result.notes()));
            }
            return new GraalProxies.NestedProxyObject(out);
        });

        methods.put("status", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(JsPdf.status()));

        methods.put("help", (ProxyExecutable) args -> help());

        return ProxyObject.fromMap(methods);
    }

    // -------------------------------------------------------------------
    // Argument handling
    // -------------------------------------------------------------------

    /**
     * Turns the first argument into a PDF source: a string is a project path, anything with
     * array elements is a raw byte image (e.g. from {@code archive.zipEntry}).
     */
    private static JsPdf.Source source(Value value) {
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("pdf: a file path (string) or PDF bytes"
                    + " (Uint8Array) is required");
        }
        if (value.isString()) {
            return JsPdf.fromPath(value.asString());
        }
        if (value.hasArrayElements()) {
            String name = null;
            if (value.hasMembers() && value.hasMember("path")) {
                Object pathValue = GraalProxies.fromValue(value.getMember("path"));
                name = pathValue != null ? String.valueOf(pathValue) : null;
            }
            return JsPdf.fromBytes(GraalProxies.toByteArray(value), name);
        }
        throw new IllegalArgumentException("pdf: expected a file path (string) or PDF bytes"
                + " (Uint8Array) - pdf.help() lists the methods");
    }

    /**
     * Reads and validates an object number.
     */
    private static int objNum(Value value) {
        if (value == null || value.isNull() || !value.isNumber()) {
            throw new IllegalArgumentException("pdf: the object number must be a number"
                    + " (see pdf.objects() for the available numbers)");
        }
        long number = value.asLong();
        if (number < 0 || number > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("pdf: object number out of range: " + number);
        }
        return (int) number;
    }

    private static long longValue(Object value, String what) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalArgumentException("pdf: " + what + " must be a number");
    }

    /**
     * A {@code 'summary' | 'full' | false} option of {@code pdf.font}. The boolean stays the
     * simple case it is everywhere else in this API: {@code false} switches the block off,
     * {@code true} asks for the summary - {@code 'full'} is what asks for the values themselves.
     */
    private static String modeOption(Options options, String name) {
        Object value = options.get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean flag) {
            return flag ? "summary" : "off";
        }
        if (value instanceof String text) {
            return text;
        }
        throw new IllegalArgumentException("pdf.font: option '" + name + "' reads 'summary',"
                + " 'full' or false");
    }

    /**
     * The {@code fonts} option of the text-extracting functions: {@code 'auto'} (the default) reads
     * every string through the font its {@code Tf} names, {@code false} is the documented raw view
     * that reads the whole page by the document convention. Anything else is an error naming the
     * signature - a typo here would silently change the text a caller is looking at.
     */
    private static boolean useFonts(Options options, String what) {
        Object value = options.get("fonts");
        if (value == null || Boolean.TRUE.equals(value)) {
            return true;
        }
        if (Boolean.FALSE.equals(value)) {
            return false;
        }
        if (value instanceof String text && text.equals("auto")) {
            return true;
        }
        throw new IllegalArgumentException("pdf." + what + ": option 'fonts' reads 'auto'"
                + " (the default, text through its font) or false (the raw view, document"
                + " convention), not '" + value + "'");
    }

    /** The {@code detail} option of {@code pdf.fonts}: 'list' (default) or 'full'. */
    private static boolean detailIsFull(Options options) {        String detail = options.getString("detail", "list");
        if (detail.equals("full")) {
            return true;
        }
        if (detail.equals("list")) {
            return false;
        }
        throw new IllegalArgumentException("pdf.fonts: option 'detail' reads 'list' or 'full',"
                + " not '" + detail + "'");
    }

    /**
     * The codes argument of {@code pdf.cmap()} and {@code pdf.glyphMetrics()}: a hex text
     * ({@code "41 0B"}), a list of hex strings and/or numbers, or a {@code Uint8Array} of raw code
     * bytes. The raw form cannot be split here - only the code space (or the encoding) knows where a
     * code ends, so the bytes travel to {@link JsPdf} whole.
     */
    private record CodeQuery(List<Object> codes, byte[] rawCodes) {
    }

    private static CodeQuery codesArgument(Value value, String what) {
        if (value == null) {
            return new CodeQuery(List.of(), null);
        }
        if (value.isString()) {
            return new CodeQuery(List.of(value.asString()), null);
        }
        if (value.isNumber()) {
            // One code may be written as a number (glyphMetrics(path, 4, 72)); its width is filled
            // in by the function that knows the code space, exactly like in a list.
            return new CodeQuery(List.of(value.fitsInLong()
                    ? (Object) value.asLong() : (Object) value.asDouble()), null);
        }
        if (value.hasArrayElements() && value.hasMembers() && value.hasMember("byteLength")) {
            return new CodeQuery(List.of(), GraalProxies.toByteArray(value));
        }
        if (value.hasArrayElements()) {
            List<Object> items = new ArrayList<>();
            for (long i = 0; i < value.getArraySize(); i++) {
                Object item = GraalProxies.fromValue(value.getArrayElement(i));
                if (!(item instanceof String) && !(item instanceof Number)) {
                    throw new IllegalArgumentException("pdf." + what + ": codes takes hex strings"
                            + " and numbers - '" + item + "' is neither");
                }
                items.add(item);
            }
            return new CodeQuery(items, null);
        }
        throw new IllegalArgumentException("pdf." + what + ": codes must be a string ('41 0B'), a"
                + " list (['41', 11]) or a Uint8Array of code bytes - see pdf.help()");
    }

    /** A font reference: an object number, or the {@code {page: n, name: '/F0'}} form. */
    private record FontRef(Integer objNum, Integer pageIndex, String resourceName) {
    }

    /**
     * Reads the second argument of {@code pdf.decodeString()} / {@code pdf.glyphMetrics()}: either a
     * plain object number or {@code {page: n, name: '/F0'}} - the form that matches how a page is
     * debugged, because the resource name is what a {@code Tf} operator actually writes.
     */
    private static FontRef fontReference(Value target, String what) {
        if (target != null && !target.isNull() && target.hasMembers()
                && !target.hasArrayElements()) {
            if (!target.hasMember("page") || !target.hasMember("name")) {
                throw new IllegalArgumentException("pdf." + what + ": an object argument needs both"
                        + " a 'page' and a 'name' member - pdf." + what
                        + "(path, {page: 21, name: '/F0'}, …)");
            }
            Object page = GraalProxies.fromValue(target.getMember("page"));
            Object name = GraalProxies.fromValue(target.getMember("name"));
            if (!(name instanceof String text)) {
                throw new IllegalArgumentException("pdf." + what + ": 'name' must be the resource"
                        + " name as a string, e.g. '/F0'");
            }
            return new FontRef(null, (int) longValue(page, "page"), text);
        }
        return new FontRef(objNum(target), null, null);
    }

    /**
     * The string argument of {@code pdf.decodeString}: a PDF string as text ({@code '<00480065>'},
     * {@code '(Hi)'}) or the raw bytes as a {@code Uint8Array}. Both arrive as bytes here - the
     * literal form through the module's own parser, so an escape means what it means in a file.
     */
    private static byte[] stringArgument(Value value) {
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("pdf.decodeString: a string is required -"
                    + " '<00480065>', '(Hi)' or a Uint8Array of bytes");
        }
        if (value.hasArrayElements()) {
            return GraalProxies.toByteArray(value);
        }
        if (value.isString()) {
            return PdfTextDecoder.stringBytes(value.asString());
        }
        throw new IllegalArgumentException("pdf.decodeString: the string must be written as hex"
                + " ('<00480065>'), as a literal string ('(Hi)') or as a Uint8Array - pdf.help()"
                + " shows the forms");
    }

    /**
     * A list option ({@code what: ['text']}, {@code bbox: [0, 30, 612, 120]}). Arrays never travel
     * through {@link Options#parse} (it would flatten them), so they are read from the raw member -
     * and a plain string is accepted for one item, because {@code what: 'text'} is the same ask.
     */
    private static List<Object> listMember(Value value, String name) {
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (value.hasArrayElements()) {
            List<Object> items = new ArrayList<>();
            for (long i = 0; i < value.getArraySize(); i++) {
                items.add(GraalProxies.fromValue(value.getArrayElement(i)));
            }
            return items;
        }
        Object single = GraalProxies.fromValue(value);
        return single == null ? List.of() : List.of(single);
    }

    /**
     * Converts a JS options object into a parameter map (strict: unknown keys are rejected by
     * the caller with {@link Options#parse}).
     */
    private static Map<String, Object> parmsFromValue(Value value) {
        if (value == null) {
            return Map.of();
        }
        if (!value.hasMembers()) {
            throw new IllegalArgumentException("pdf.decodeStream: parms must be an object like"
                    + " {Predictor: 12, Columns: 4, Colors: 1, BitsPerComponent: 8}");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (Object key : value.getMemberKeys()) {
            String name = String.valueOf(key);
            map.put(name, decodeParm(value.getMember(name)));
        }
        return map;
    }

    /**
     * Converts one filter parameter: numbers stay numbers, names keep their slash so that
     * e.g. {@code {ColorSpace: '/DeviceRGB'}} reads like PDF.
     */
    private static Object decodeParm(Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isNumber()) {
            return value.fitsInLong() ? (Object) value.asLong() : (Object) value.asDouble();
        }
        if (value.isString()) {
            return value.asString();
        }
        if (value.hasArrayElements()) {
            List<Object> list = new ArrayList<>();
            for (long i = 0; i < value.getArraySize(); i++) {
                list.add(decodeParm(value.getArrayElement(i)));
            }
            return list;
        }
        return value.asString();
    }

    /**
     * Wraps a list of maps as a JS-traversable array.
     */
    private static ProxyArray toList(List<Map<String, Object>> list) {
        return new GraalProxies.NestedProxyArray(list);
    }

    /** The {@code program} block's raw bytes as a real Uint8Array (the byte contract). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> programBytesAsU8(Map<String, Object> sheet,
                                                        Value uint8ArrayCtor) {
        Object block = sheet.get("program");
        if (block instanceof Map<?, ?> map && map.get("data") instanceof byte[] data) {
            ((Map<String, Object>) map).put("data", toU8(uint8ArrayCtor, data));
        }
        return sheet;
    }

    /**
     * Converts a Java byte array to a real JS Uint8Array.
     */
    private static Value toU8(Value uint8ArrayCtor, byte[] data) {
        if (uint8ArrayCtor == null) {
            throw new PdfException("The pdf module needs the Uint8Array constructor of the"
                    + " JavaScript context to return bytes");
        }
        return GraalProxies.toUint8Array(uint8ArrayCtor, data);
    }

    private static void requireArgs(Value[] args, int min, String signature) {
        if (args == null || args.length < min) {
            throw new IllegalArgumentException("Usage: pdf." + signature + " - see pdf.help()");
        }
    }

    private static Value argOrNull(Value[] args, int index) {
        if (args == null || index >= args.length) {
            return null;
        }
        Value value = args[index];
        // isNull() covers both null and an omitted/undefined argument (the convention used by
        // all bridges in this project).
        return (value == null || value.isNull()) ? null : value;
    }

    // -------------------------------------------------------------------
    // Options
    // -------------------------------------------------------------------

    /**
     * Strict option object: unknown keys are rejected (a typo in an option must not silently
     * change what the caller sees), and every accessor checks the type.
     */
    private static final class Options {
        private final Map<String, Object> values;

        private Options(Map<String, Object> values) {
            this.values = values;
        }

        static Options parse(Value options, Set<String> allowed, String what) {
            if (options == null) {
                return new Options(Map.of());
            }
            if (!options.hasMembers()) {
                throw new IllegalArgumentException("pdf." + what + ": the options argument must be"
                        + " an object with any of (" + String.join(", ", allowed)
                        + ") or omitted - see pdf.help()");
            }
            Set<String> unknown = new HashSet<>();
            Map<String, Object> map = new LinkedHashMap<>();
            for (Object key : options.getMemberKeys()) {
                String name = String.valueOf(key);
                if (!allowed.contains(name)) {
                    unknown.add(name);
                    continue;
                }
                Value member = options.getMember(name);
                if (member == null || member.isNull()) {
                    continue;
                }
                if (member.hasArrayElements()) {
                    // Arrays (a list of codes, raw bytes) stay with the caller - read them with
                    // member(); fromValue() would flatten them into a string.
                    continue;
                }
                map.put(name, GraalProxies.fromValue(member));
            }
            if (!unknown.isEmpty()) {
                throw new IllegalArgumentException("pdf." + what + ": unknown option(s) " + unknown
                        + " (supported: " + String.join(", ", allowed) + ") - see pdf.help()");
            }
            return new Options(map);
        }

        /**
         * The raw value of one option (an array, a typed array - anything {@link #parse} did not
         * flatten), or null.
         */
        static Value member(Value options, String name) {
            if (options == null || !options.hasMembers() || !options.hasMember(name)) {
                return null;
            }
            Value value = options.getMember(name);
            return value == null || value.isNull() ? null : value;
        }

        Object get(String key) {
            return values.get(key);
        }

        int getInt(String key, int fallback) {
            Object value = values.get(key);
            if (value == null) {
                return fallback;
            }
            if (value instanceof Number number) {
                return number.intValue();
            }
            throw new IllegalArgumentException("pdf: option '" + key + "' must be a number, not "
                    + value.getClass().getSimpleName());
        }

        /** A number option that may have fractions (a font size, a scale). */
        double getDouble(String key, double fallback) {
            Object value = values.get(key);
            if (value == null) {
                return fallback;
            }
            if (value instanceof Number number) {
                return number.doubleValue();
            }
            throw new IllegalArgumentException("pdf: option '" + key + "' must be a number, not "
                    + value.getClass().getSimpleName());
        }

        boolean getBoolean(String key, boolean fallback) {
            Object value = values.get(key);
            if (value == null) {
                return fallback;
            }
            if (value instanceof Boolean flag) {
                return flag;
            }
            throw new IllegalArgumentException("pdf: option '" + key + "' must be true or false");
        }

        String getString(String key, String fallback) {
            Object value = values.get(key);
            if (value == null) {
                return fallback;
            }
            if (value instanceof String text) {
                return text;
            }
            throw new IllegalArgumentException("pdf: option '" + key + "' must be a string");
        }
    }

    // -------------------------------------------------------------------
    // Help
    // -------------------------------------------------------------------

    /**
     * The {@code pdf.help()} text: API, value conventions and the honest limits.
     * @return help text
     */
    public static String help() {
        return """
                JS PDF API (namespace 'pdf')
                ============================

                Reads PDF files without an external library: the file structure, every object
                type, indirect objects (including those packed in object streams), the content
                streams of the pages (read as the state machine they are - one walk over CTM,
                q/Q, text state, path and clip, which is what pdf.placement() takes its positions
                from) and the document above them: catalogue, page tree, outlines,
                annotations, form, name trees and the tagged-PDF structure tree with
                its marked content. Nothing is ever written or executed.

                1) Where the file is (header / body / cross-reference / trailer)
                   pdf.structure(pathOrBytes)
                     -> {path, size, header: {offset, line, version, binaryComment},
                         eof: {markerOffset, startxref, startxrefCandidates},
                         crossReference: [{section, kind: 'table'|'stream', offset, size, prev,
                                           w, index, entries, warnings}],
                         trailer: {...}, body: {objects, free, uncompressed, compressed,
                                 incrementalUpdates, recoveredByScan},
                         linearized?, encryption?, warnings?}
                   pdf.info(pathOrBytes)
                     -> {fileVersion, root, id, info: {objNum, values: {Title, Producer, ...}},
                         objects, encryption?, notes?, structureWarnings?}
                        'root' and the /Info values are pointers - {ref: '1 0 R', objNum: 1,
                        gen: 0} - info() does not walk the catalog, pdf.object() does.

                2) The objects (null, boolean, integer, real, string, name, array,
                   dictionary, stream, indirect reference)
                   pdf.objects(pathOrBytes[, {limit: 1000, includeFree: false, salvage: false}])
                     -> one entry per object ({objNum, gen, where: 'uncompressed'|'compressed'
                        |'free'|'scanned', offset|objStm, kind, dictType?, subtype?, filters?,
                        storedBytes?, decodedBytes?}) plus a trailing summary object.
                        salvage: also report object markers that the cross-reference does not
                        know about (damaged or hand-edited files).
                   pdf.object(pathOrBytes, objNum[, {resolveRefs: false, maxDepth: 40,
                                                     maxStringChars: 200000}])
                     -> {objNum, gen, kind, location, value, stream?, notes?, warnings?}
                        kind is one of: null, boolean, integer, real, string, name, array,
                        dictionary, stream, reference, unreadable.

                3) Streams (compressed data is delivered uncompressed)
                   pdf.streams(pathOrBytes[, {limit: 0}])   -> all stream objects with metadata
                   pdf.stream(pathOrBytes, objNum[, {decode: true}])
                     -> Uint8Array. /FlateDecode is inflated (zlib, raw deflate as fallback),
                        LZWDecode, RunLengthDecode, ASCIIHexDecode, ASCII85Decode likewise,
                        predictors (/Predictor 2 and 10..15) included.
                        {decode: false} returns the bytes as stored.
                        A JPEG (/DCTDecode) or JPEG2000 stream is already a complete image: it
                        is returned unchanged, so fs.writeBytes('img.jpg', bytes) works.
                   pdf.streamText(pathOrBytes, objNum[, {decode: true, encoding: 'pdfdoc'}])
                     -> String ('pdfdoc' = document convention, or 'utf-8', 'windows-1252', ...)
                   pdf.decodeStream(data, 'FlateDecode'[, parms])
                     -> {data: Uint8Array, bytes, notes?} - manual single-filter decode, e.g.
                        pdf.decodeStream(bytes, 'FlateDecode', {Predictor: 12, Columns: 4}).
                        For bare zlib streams outside a PDF use crypto.inflate() instead.

                4) Content streams (the drawing commands of a page)
                   pdf.contentStreams(pathOrBytes[, {maxPages: 200, text: false,
                                                    maxTextChars: 4000, fonts: 'auto'}])
                     -> per page {page, pageObjNum, contentObjNums, decodedBytes, operatorCount,
                                 operators: [{operator, count}], fonts?, text?, unmappedSample?,
                                 notes?}
                   pdf.operations(pathOrBytes, objNum[, {maxItems: 100, after: 0}])
                     -> {objNum, decodedBytes, operatorCount, histogram,
                         operations: {items: [{offset, op, operandsText, operands, operandCount,
                                               operandValues, operandValuesTruncated?,
                                               operandValuesCount?, commentOffset?, comment?}]}}
                        'after' continues a listing that was cut off by maxItems.
                        `operands` is a COUNT, not the operands - that is what it has always held, and
                        it keeps its number for one version (pdf.status().retiredFieldNames says so);
                        the count reads `operandCount` from now on and the operands themselves are
                        `operandValues`: numbers as numbers, a name as "/F1", a literal string as its
                        text, a hex string as "<00480065>", a reference as {ref, objNum, gen}, and an
                        array or dictionary as its PDF source text (a nested structure would be a
                        second answer, not the operand of this line). At most
                        pdf.status().maxOperandValues values per item, then
                        operandValuesTruncated with the full count in operandValuesCount. Nothing
                        here is read through a font - pdf.placement() is the function that turns a
                        string's bytes into characters.
                   pdf.text(pathOrBytes[, {page: <1-based>, maxPages: 200, fonts: 'auto'|false,
                                           maxUnmapped: 20}])
                     -> {pages: [{page, objNum, text, wordGaps?, mapping: {method, fonts, fontsResolved,
                          codes, mapped, unmapped, unread?, spaceFallback?, stringsWithoutFont?},
                          unmappedSample: [hex], unmappedTruncated?, truncated?, fonts: [...]}],
                         pageCount, notes?}
                        Every string is read through the font its Tf named: the content stream is
                        walked for Tf, each name is resolved to the page's font resources (lazily,
                        only what a Tf really uses), and the string's bytes go through the mapping
                        chain of 4b. mapping says what happened - method is 'none' when no font
                        answered (the text is then exactly the old extract), one of the chain's
                        methods ('toUnicode', 'unicode-code', 'toUnicode-descendant',
                        'differences', …) when one kind of source did it, 'mixed' when several did;
                        codes counts what ran through a font, mapped what became characters,
                        unmapped what stayed a code. A string whose font cannot be cut into codes
                        (a non-UCS predefined CMap) or that the call's code budget
                        (pdf.status().maxTextCodes) could not reach stays out of the text: it counts
                        in unmapped, its hex appears in unmappedSample, and a note says why -
                        inventing characters for it would be worse than losing it.
                        fonts[].reliableText is now measured per font on this page (codes/mapped/
                        unmapped, plus unread for strings the font refused to cut, next to it)
                        instead of derived from the dictionary; a font no Tf used keeps the
                        dictionary verdict. fonts: false is the documented raw view - every string
                        read by the document convention, byte for byte what this module always
                        answered, with no mapping block. maxUnmapped bounds the sample, never the
                        count.
                        A {page: n} read walks to page n: maxPages bounds only the read that lists
                        every page, so it never decides which page of a document exists. A page the
                        document does not have is refused by naming its length ("the document has
                        876 page(s)"); a tree whose /Count promises more than the walk reaches says
                        both numbers, because those are different facts.
                        wordGaps {threshold: -120, numbers, spaces, ignoredOpenings,
                                 widestIgnoredOpening?, note?} - present on a page whose spaces came
                        from a TJ array, and only there. Where a word gap begins is not decided by
                        the specification, so this module applies a rule of thumb: a number at or
                        below pdf.status().wordGapThreshold becomes a space. The block is that rule
                        admitting what it cannot see: `numbers` is every number the array offered,
                        `spaces` how many became one, `ignoredOpenings` how many negative ones did
                        not (each is a gap the text does not show) and `widestIgnoredOpening` the
                        widest of them as the file wrote it. Those are operand values, thousandths of
                        the font size: Tz, Tc, the CTM and UserUnit are not applied to them, and a
                        page under CTM 2 0 0 2 … opens twice the gap its number says. pdf.placement()
                        answers the same operand as a computed distance in a named space; pdf.text()
                        keeps the rule and now says what the rule cost on this page
                        (docs/js/pdf-content-plan.md, decision 10).

                4b) Fonts (what a font is, and what one of its codes means)
                   pdf.fonts(pathOrBytes[, {page: <1-based>, limit: 500, maxPages: 200,
                                            formXObjects: false, detail: 'list'|'full'}])
                     -> {fonts: [{resourceName, resourceNames?, objNum, subtype, baseFont, kind,
                                  encodingKind, encoding?, codeBytes, toUnicode?, toUnicodeFrom?,
                                  descendant?, cidToGidMap?, embedded, widths, mapping,
                                  pages, pageCount, showTextOps, details?}],
                         summary: {count, listed, type0, simple, resolvable, codesOnly,
                                   formXObjectFonts, pagesWalked},
                         truncated, notes?}
                        One entry per font dictionary the walked pages name - the same object under
                        two resource names stays one entry, listed with resourceNames. pages,
                        pageCount and showTextOps count the pages whose text really uses the font
                        (a Tf that a Tj, TJ, ' or " follows), so "what is wrong with page 21" is
                        answered without opening thirty font objects; a font that sits in the
                        resources unused has pageCount 0. mapping names the source that could
                        resolve its codes: 'toUnicode', 'toUnicode-descendant' (the map of the
                        descendant - toUnicodeFrom says so), 'unicode-code' (UCS family: the code
                        is the character), 'differences', 'builtin-encoding',
                        'predefined-cmap-unsupported' or 'none'. In summary, resolvable and
                        codesOnly count the two ends of that list; a 'builtin-encoding' font is in
                        neither, because its bytes are readable by the document convention and not
                        by the font. In this listing mapping and widths are what the dictionary
                        says ('W', 'Widths' or 'none'); where a page actually drew with the font,
                        mapping is what answered - a subset whose codes came out of the embedded
                        program's /cmap reads 'cmap-table' there, and pdf.font() opens the program.
                        formXObjects: true also opens the resources of the page's form XObjects
                        (resourceName '/F0@XObject/Fm1'), detail: 'full' adds the whole sheet of
                        pdf.font() as 'details'. The walk is bounded by maxPages and a walk that
                        stopped early is marked (truncated plus the note that pages is a subset) -
                        pages would otherwise claim a completeness the walk never established.
                   pdf.font(pathOrBytes, objNum[, {toUnicode: 'summary'|'full'|false,
                                                   widths: 'summary'|'full'|false,
                                                   program: 'summary'|'tables'|'bytes'|false,
                                                   maxItems: 200}])
                     -> {objNum, type, subtype, kind, baseFont, encoding: {kind, name, codeBytes,
                          codeSpaces, mapping, differences?, notes?}, descendant?, cidFont?,
                         fontDescriptor?, fontDescriptorFrom?, fontFiles?, program?,
                         charProcs?/fontMatrix? (Type 3),
                         firstChar?/lastChar?/widthSource/widthCount?/widthValues?,
                         vertical? {w2Entries, dw2, dw2Source?}, toUnicode?,
                         toUnicodeSummary?, mapping: {method, possible, mapped?}, notes?}
                        A CIDFont addressed by its own object number has no descendant to point at,
                        so its own /W, /DW and /CIDSystemInfo read from 'cidFont'; a /Type0 names
                        no descriptor of its own, so its 'fontDescriptor' (and every embedded
                        program behind it) comes from the CIDFont and says so in
                        'fontDescriptorFrom'.
                        The dictionary, not the program: the widths come from the CIDFont's /W -
                        in all three forms c [w…], cFirst cLast w and c [], which means "the /DW
                        default applies" - or from /FirstChar+/Widths, where a gap stays null
                        instead of turning into width 0. widthEntries counts every width a /W
                        region describes while maxItems bounds only the listing, and each cut is
                        marked (widthEntriesCut, widthsTruncated). toUnicodeSummary says what the
                        /ToUnicode map holds - bfchars, bfranges, mappedEntries and
                        multiCharTargets, the codes that are more than one character (the ligature
                        <0B> -> "ff", a code point beyond U+FFFF); the entries themselves stay in
                        pdf.cmap(), which 'full' shows a first view of here. A dictionary that is
                        not a font is not waved through: notes names the required entry that is
                        missing (/Type, /Subtype, /BaseFont).
                        'program' opens the embedded file itself (fontFile2 before fontFile3
                        before fontFile; a cidSet is not a program). For an sfnt it reads the
                        tables - {kind, sfntVersion, tableCount, tables, unitsPerEm, numGlyphs,
                        indexToLocFormat, numberOfHMetrics, ascent, descent, fontBBox,
                        widths: 'hmtx', cmap, cmapSource, cmapFormat, cmapEntries, cmapGlyphs,
                        cmapBeyondGlyphs?, size} - and never runs a thing: no hinting instruction,
                        no charstring, and a composite glyph is named rather than composed.
                        'tables' adds the table directory, 'bytes' adds the program itself as a
                        Uint8Array (for fs.writeBytes), false leaves the biggest stream of the font
                        closed. A Type 1 program is decrypted behind eexec (the cipher hides a
                        format, not a document) and read as far as its declarations go -
                        {kind, read: true, clearTextBytes, eexecDecrypted, programFontName,
                        programFullName, lenIV?, fontMatrix, fontBBox, fontBBoxScaled,
                        encodingBase?, encodingSize, encodingFill, encodingEntries,
                        encodingNames, declaredGlyphs, charstrings, charstringBytes, subrs, size};
                        its charstrings are skipped by their announced length, never executed, so
                        no width comes from it. A CFF is walked through its indices and DICTs -
                        {kind, read: true, cffVersion, programFontName, programFullName,
                        programFamilyName, programWeight?, cidKeyed, ros?, cidCount?, glyphs,
                        fontBBox, fontMatrix?, charsetFormat, charsetEntries, charsetCids? or
                        charsetNames, fdCount?, fdNames?, fdSelectFormat?, defaultWidthX?,
                        nominalWidthX?, localSubrs?, globalSubrs, size}. Which glyph carries its
                        own width operand stands inside the charstrings, so /W stays the answer.
                        cmap is true only for a subtable whose platform/encoding
                        promises Unicode: the ReportLab subset's platform 1 table selects glyphs
                        (and its widths are read with it) but its values are not characters, and
                        saying otherwise would be inventing a code page.
                   pdf.cmap(pathOrBytes, objNum|{font: objNum}[, {codes, limit: 200,
                                                                 maxEntries: 200000}])
                     -> {objNum, kind: 'toUnicode'|'cmap', viaFont?, fontSubtype?, baseFont?,
                         cmapName?, cmapType, cmapVersion?, cidSystemInfo?,
                         codeSpaces: [{from, to, bytes, notation}], codeBytes?, useCmaps?,
                         counts: {bfchars, bfranges, mappedEntries, conflicts, wModes,
                                  unsupportedOps},
                         codes: [{code, unicode, codePoints, source, multi?}], shown,
                         truncated?, warnings?}
                        Reads one CMap stream - the /ToUnicode map of a font or an embedded
                        /CMapFile. Its PostScript is tokenized, never executed: bfchar, bfrange
                        (with a destination start and with a target array), the code space and the
                        header are read; WMode blocks, the CidGen macros and usecmap are named in
                        counts/unsupportedOps instead of being interpreted, because their tables
                        are not built in. The map is built completely (up to maxCmapEntries) and
                        limit only bounds the listing, so a sample knows as much as a full read.
                        A code defined twice keeps its first definition and counts a conflict.
                        {codes: '41 0B'} answers exactly those codes; a code whose width does not
                        match the code space is an error instead of a silent fill-up.
                        {font: 4} resolves the /ToUnicode of font 4 - and says so, with the
                        object number of the descendant's map, when font 4 has none itself. It also
                        compares the map's code space with what the font's /Encoding says: a
                        one-byte map next to a two-byte encoding is reported as
                        'code-space mismatch' in warnings, because then the map does not address
                        this font's strings at all.
                   pdf.decodeString(pathOrBytes, objNum|{page: n, name: '/F0'}, string
                                    [, {as: 'both'|'unicode'|'codes', widths: false,
                                       maxItems: 2000}])
                     -> {font: {objNum, resourceName?, subtype, kind, baseFont},
                         split: {codeBytes, codeSpaces, source, bytes, codes, tail?},
                         mapping: {method, possible, codes, mapped, unmapped, spaceFallback},
                         items: [{i, hex, cid?, gid?, unicode, codePoints, mappedBy, multi?,
                                  continues?, width?}],
                         unicode, unmapped, widths?, truncated?, notes?}
                        "What do these bytes mean in this font?" The string is written the way a
                        content stream writes it - '<00480065>', '(Hi)' (escapes included) or a
                        Uint8Array - and is cut into codes by the encoding, never by a guess:
                        /Identity-H every two bytes, a simple font every one, and an encoding that
                        cannot say (a non-UCS predefined CMap, a /Type0 without /Encoding) leaves
                        the bytes whole in split.tail. Each code is then asked through one chain,
                        and mappedBy names the step that answered: the font's own /ToUnicode
                        ('toUnicode:bfchar', ':bfrange', ':bfrange-array'), a UCS code that is its
                        own character ('unicode-code'; a surrogate pair is merged and shown at the
                        high code, the low one stays listed with 'continues'), the descendant's map
                        ('toUnicode-descendant:…'), the /cmap of the embedded program reached over
                        CID -> GID ('cmap-table:format4', 'cmap-table:format12' - only a subtable
                        whose platform/encoding promises Unicode, and its smallest code point, with
                        a note where a glyph is named more than once), or a glyph name from
                        /Differences through the Adobe Glyph List - 'differences:agl' for a listed
                        name, 'differences:uni-pattern' for uniXXXX, 'differences:underscore' for
                        f_i, which is in no list and means f plus i. Two readings need no entry and
                        both say so: 'space-fallback' (code 20 - TeX and CidGen maps leave <20> out
                        although the text holds word gaps, and a word gap is the only reading of a
                        space code) and 'document-convention' (a simple font without /ToUnicode,
                        without /Differences and without a name source, read exactly the way
                        pdf.text() has always read it). Everything else stays unicode: null and is
                        listed in unmapped - no replacement character, no invented letter.
                        'cid' is the code of a CID font, 'gid' only what /CIDToGIDMap says (the
                        /CIDToGIDMap stream is read as the 16-bit field it is). widths adds the
                        width of every code together with its 'widthSource': the dictionary where it
                        has a value of its own ('W', 'Widths'), the embedded program's hmtx where it
                        has none ('hmtx', converted to the same 1000-unit base: 1000 * adv /
                        unitsPerEm), and the CIDFont's standing default last ('DW') - a zero in a
                        subset's hmtx is the width of an empty box, not a measurement of a glyph.
                        maxItems bounds the codes read (pdf.status().maxGlyphQueries); split.codes
                        counts all of them either way, and a cut is marked with truncated.
                        The same chain runs over a whole page in pdf.text(), pdf.contentStreams()
                        and pdf.markedContent() - one string at a time, each through the font its
                        Tf named, with the page's mapping block as the receipt. Half a code point
                        is never a character: a /ToUnicode destination cut between a surrogate pair
                        answers unmapped (with a note), so no invalid UTF-16 leaves this module.
                   pdf.glyphMetrics(pathOrBytes, objNum|{page: n, name: '/F0'}, codes
                                    [, {fontSize: 1, bbox: true, maxItems: 2000}])
                     -> {font: {objNum, resourceName?, subtype, kind, baseFont, codeBytes?,
                                program?: {objNum, kind}, unitsPerEm?, fontSize, widthBase,
                                scaling, glyphScaling?},
                         sources: {width, widthProgram?, vertical?, bbox?, sideBearing?},
                         glyphs: [{hex, cid?, gid?, width, widthSource, widthPt?, w1?, v?,
                                   verticalSource?, w1Pt?, vPt?, lsb?, lsbPt?,
                                   bbox?, bboxPt?, bboxSource?, empty?}],
                         summary: {asked, listed, widths, boxes?}, truncated?, notes?}
                        "How wide is this glyph, and how much room does it take at 10.5 pt?" The
                        codes are written the way the content stream writes them - '0048' for a
                        two-byte font, '41' for a one-byte one, a number filled up to that width, a
                        Uint8Array cut by it - and a code in the wrong width is an error naming the
                        right one instead of a silent zero-fill. A CIDFont addressed by its own
                        object number is the one case with no byte width to hold to (only the /Type0
                        above it names one): there a code is read as the value it is, and a note
                        says that.
                        Every number is given twice: on the base the file uses (a width on the
                        1000-unit base of /W, a box and a bearing on the program's unitsPerEm) and
                        multiplied by fontSize into text space as widthPt / lsbPt / bboxPt.
                        widthBase, unitsPerEm and fontSize are the exact facts; scaling and
                        glyphScaling are the two factors shown to six decimals, and the *Pt values
                        are computed from the ratio, not from the rounded factor. A Type 3
                        /FontMatrix replaces the 1000-unit base and says so in a note.
                        widthSource says who answered: the dictionary ('W', 'Widths', or 'DW' - the
                        CIDFont's standing default) or the embedded program's hmtx brought onto the
                        same base ('hmtx'); width stays null where the named source holds no value
                        for that code (below /FirstChar, a gap in /Widths) instead of becoming 0.
                        sources is the same statement for the font as a whole - where a value can
                        come from at all - while widthSource and bboxSource answer it per glyph.
                        A CIDFont that describes vertical writing (/W2, /DW2) also answers the
                        specification's other two vectors: w1 = [0, w1_y] is the displacement of
                        writing mode 1 (its horizontal component is always 0) and v = [v_x, v_y] is
                        the position of the vertical origin relative to the horizontal one.
                        verticalSource says who answered - 'W2' for the CID's own entry, 'DW2' for
                        the file's default, 'DW2-default' for [880 -1000] where the file named none -
                        and v_x is half the glyph width where /W2 gives none, which is what the
                        specification states rather than a guess. Which set a viewer uses is the
                        CMap's WMode (pdf.font() shows it as encoding.wMode, pdf.cmap() reports the
                        CMap file's own entry as wMode); the program's vhea/vmtx are not read, so a
                        vertical advance never comes from a program. Where a metric entry is shaped
                        wrong - an array in /W, an array in /DW, a /W2 group that is no multiple of
                        three - the number that answers is a default and a note says so: a default is
                        never presented as a measurement.
                        bboxSource is 'glyf' (the box the program itself writes),
                        'composite' (a glyph built of others: the box would have to be computed from
                        the parts, so it stays null instead of being claimed) or 'none' (nothing to
                        measure: a space or a dropped glyph - those carry empty: true - or a Type 1 /
                        CFF program, whose advances and outlines stand inside charstrings that are
                        not executed). A code the program does not hold keeps width: null rather
                        than inheriting the last advance of hmtx, and a descriptor's FontBBox is
                        never presented as the box of a glyph. bbox: false reads the width only -
                        no bearing, no box, and no gid of a simple font.

                4c) Placement (where a page's text, paths, clips and images stand)
                   pdf.placement(pathOrBytes[, {page, maxPages: 200,
                                                what: ['text'] | 'clips' | 'paths'
                                                      | 'xobjects' | 'images' | 'all',
                                                bbox: [x1, y1, x2, y2], dpi,
                                                state: 'key'|'full'|'colour', glyphs: false,
                                                maxItems: 1000, fonts: 'auto'|false,
                                                includeXObjects: false, drawing: false}])
                     -> {pages: [{page, pageObjNum, frame, walk, items, summary, diagnostics,
                                  mapping?, notes?}],
                         pageCount, notes?}
                        frame   {mediaBox, cropBox?, boxSpace: 'defaultUser', basis, rotate,
                                 userUnit, width, height, space: 'page', origin: 'top-left',
                                 yDown: true, userToPage: [a,b,c,d,e,f], dpi?, deviceScale?,
                                 filterRule?}
                                 - the page's own matrix, the same for every item of the page, built
                                   from the visible box (CropBox, else MediaBox), UserUnit and Rotate;
                                   where a page has no frame to compute against the answer says
                                   space: 'user' and pageComputed: false instead of inventing a box.
                                   filterRule appears when bbox filters, and says which test ran:
                                   'text:origin' (a text item is asked where its origin stands) or
                                   'text:origin,area:intersects' (an area item is asked whether its
                                   box reaches into the band - a path crossing the corner has no
                                   position to test). An item whose box cannot be computed is kept.
                        items   one per operator of the kinds you asked for, in stream order. An
                                item's at.ctm is the matrix at the operator the item names, not a
                                matrix per point: a path built across a cm carries each segment's own
                                in drawing, bbox and currentPoint - which is exactly when
                                ctmChangedMidPath appears and the page's diagnostics name the byte.
                          {kind: 'text', offset, space, x, y, advance, advanceVector,
                           text, font: {resourceName|objNum, setBy, size, scalePercent?,
                           sizeEffective?, subtype?}, visible, renderingMode?,
                           renderingModeName?, clipping?, knockout?, widthSource,
                           widthsMissing?, adjustments?, elements?, glyphs?, bbox: null,
                           bboxSource, at: {op, ctm, textMatrix, lineMatrix?, clipDepth}, state?}
                                 x/y is the pen position - the glyph origin through
                                 T_rm = [T_fs*T_h 0 0; 0 T_fs 0; 0 T_rise 1] x T_m x CTM (5.3.3),
                                 so a Ts (rise) moves it and a folded T_m mirrors it. advance is the
                                 length of the operand's displacement and advanceVector its direction,
                                 both computed as S. 410 prints them
                                 (t_x = ((w0 - Tj/1000) x T_fs + T_c + T_w) x T_h) and mapped through
                                 T_m x CTM - never through T_rm, which holds the font size a second
                                 time. Tr 3 answers visible: false with the advance still counted;
                                 Tr 4..7 add clipping: true. Where the font names no width the answer
                                 is advance: null plus widthsMissing, never a guessed 1000.
                                 bboxSource is 'advance-only' (the item's extent is its advance - a
                                 text item's box would need the glyph outlines) or 'none'.
                                 elements lists the operand's pieces - each string and each TJ number
                                 once, with the gap that piece opens in page units and in text units.
                                 glyphs (only with {glyphs: true}) goes one level deeper, per code:
                                 {i, hex, code, width, widthSource, x, y, advance, piece, kernBefore}.
                                 x/y is that code's foot in the item's space - the item's own origin
                                 plus the pen's walk to the code, so the first entry is exactly the
                                 item's x/y and a later one is where T_c, T_w and the TJ numbers have
                                 left the pen. piece is the index of the entry in elements this code
                                 belongs to (a TJ number counts as a piece of its own, so the strings
                                 of `[(8) -2550 (12)]` are pieces 0 and 2 and elements[piece] always
                                 resolves; under detailsTruncated the printed piece list can be
                                 shorter than the numbers name), kernBefore the number written
                                 directly in front of this code (0 where there is none). Why per
                                 code at all: a line is written by three mechanisms at once - pieces,
                                 kerning numbers, and a Td that jumps back to the line start - and
                                 none of their boundaries is the boundary of a word. Measured on the
                                 probe probes/hello-TD.pdf: `20 0 Td` three times puts four labels at
                                 284/304/324/344 whatever the widths were, while one string `(01)`
                                 under `12 Tc` spans 16,668 pt between its two codes. Summing widths
                                 gets both wrong; the positions are the answer.
                          {kind: 'path', offset, op, space, subpaths, segments: {l: 6, c: 1},
                           drawing?, drawingRotated?, drawingMirrored?, drawingExpanded?,
                           drawingTruncated?, drawingPointsDropped?, drawingSegmentsDropped?,
                           painted, evenOdd, closed, implicitlyClosed?,
                           currentPoint, bbox, bboxSource, lineWidth, lineWidthSpan?,
                           ctmChangedMidPath?, pathTruncated?, at: {op, ctm, clipDepth}, state?}
                                 one item per operator of Table 4.10 with the path as that operator
                                 saw it - after it there is none (4.4.1, S. 226). n ends a path and
                                 paints nothing, so it gets an item with painted: false. closed is
                                 the h the file wrote; a filling operator closes an open subpath
                                 implicitly (4.5.2, S. 232) and says so with implicitlyClosed.
                                 bboxSource is 'path-points' or 'control-polygon' when a curve is
                                 involved - flattening a Bezier is rendering, not reading.
                                 lineWidth is the state's number in user space (4.3.2, S. 215 calls
                                 it a distance); where the CTM scales the two axes unevenly the
                                 thickness depends on direction, and lineWidthSpan gives both ends.
                                 drawing (only with {drawing: true}) is the shape behind those
                                 counts: one entry per segment, in the space the item names and each
                                 under the CTM its own operator ran under -
                                 {"m":[x,y]}, {"l":[x,y]}, {"c":[x1,y1,x2,y2,x,y]},
                                 {"re":[x1,y1,w,h]}, "h". A rect is listed in the space the item
                                 names, normalised when its image is axis-parallel (w >= 0, h >= 0 -
                                 the same four corners bbox spans, in the same shape, so the page's
                                 own y-flip does not make it read a negative height); a re whose
                                 matrix turns it is listed as the m l l l h equivalence S. 227 prints
                                 for it and marks itself drawingRotated. A curve stays three points
                                 and two control points (no flattening, decision 12); a v/y is the c
                                 it expanded to (PathBuildOps). drawingMirrored says the matrix
                                 changed handedness (det < 0 - the file mirrored the space, it did
                                 not only turn it, so the points run the other round: what a fill
                                 rule, a signed area or a hand-drawn shape turns on); the page's own
                                 flip is not that, it is frame.yDown. segments counts what the file
                                 wrote, drawing lists what the path is - where the two are not the
                                 same list, the item says drawingExpanded.
                                 The point budget (pdf.status().maxPolygonPoints, the same one a
                                 clip's polygon spends) cuts whole segments and then says
                                 drawingTruncated with drawingPointsDropped and
                                 drawingSegmentsDropped next to it.
                          {kind: 'clip', offset, op, paintOffset, paintOp, space, established,
                           rule, depth, subpaths, points, polygon, bbox, bboxSource, emptied?,
                           computed?, polygonTruncated?, polygonPointsDropped?, note?, at, state?}
                                 offset is the byte that wrote the W, paintOffset the one that
                                 established the region: 4.4.3, S. 234 says a W "does not alter the
                                 clipping path at the point where it appears" but modifies the
                                 succeeding painting operator. A W whose path was never painted is
                                 reported with established: false and no box. A clip can only shrink
                                 (S. 235), bboxSource is the layer's own ('path',
                                 'control-polygon' or 'intersection-of-boxes'), and computed: false
                                 means the walk had no page frame so no page numbers were invented.
                          {kind: 'xobject', offset, op, space, name, objNum, subtype, formType?,
                           matrix?, matrixSource?, bbox, bboxSource, bboxRotated?, corners?,
                           resourcesFrom, missing?, bboxMissing?, depth?, in?, singularNote?,
                           at, state?}
                                 what a Do points at: /BBox in form space mapped through the form's
                                 /Matrix (pre-multiplied onto the CTM, per S. 357) and the page's
                                 frame. A form without the Required /BBox says bboxMissing. The form
                                 is only walked when you ask with includeXObjects: true - its items
                                 then carry depth and share maxItems with the page's own, and a form
                                 that names itself again is reported as the cycle it is.
                          {kind: 'image', offset, op, space, inline, objNum, subtype, width, height,
                           colourSpace?, bitsPerComponent?, dataBytes?, sampleBytes?, bbox,
                           bboxSource, at, state?}
                                 the box a picture covers: the unit square of image space, mapped
                                 ("All images are 1 unit wide by 1 unit high in user space", S. 203)
                                 - width/height are the sample counts of the dictionary, never a box,
                                 and no pixel is returned. An inline image carries the bytes its
                                 stream holds (dataBytes, delimiters included) and, beside it,
                                 sampleBytes: the data itself, without the white-space deliverer
                                 4.10.4, S. 204 requires in front of EI (4.10.4 demands one, and only
                                 one - what a producer wrote beyond it cannot be told apart from
                                 data without decoding the sample stream, which stays out). Neither
                                 number is a sample count from /W, /H and /BPC: that product is a
                                 decode, not a measurement. An /Image XObject arrives through Do with
                                 its objNum.
                        state   on every kind, when you ask for it (`state` is not a text-only
                                 option - "womit ist hier gemalt worden" is answered by the fill
                                 colour of a painted box, not by a Tf):
                          'key' (default) - nothing extra; the item's own at block is the state.
                          'full'  - every parameter that differs from its initial value (ctm,
                                 lineWidth, lineCap, lineJoin, miterLimit, dash, renderingIntent,
                                 flatness, smoothness?, alphaStroking, alphaNonStroking, blendMode,
                                 softMask, strokeColour?, nonStrokeColour?).
                          'colour' - the four colour parameters of Table 4.2 (S. 210/211) and
                                 nothing else: strokeColour, nonStrokeColour, strokeColourSpace,
                                 nonStrokeColourSpace. Unlike 'full' this answers on a page that
                                 named no colour, because Table 4.2 gives both an initial value
                                 (DeviceGray, black) - the object then reads defaulted: true, which
                                 is the specification's value and not one this module invented.
                                 A colour object is {space, spaceSource, operator, offset,
                                 components, text?, defaulted?, note?}: the numbers the file wrote,
                                 the space its operator named (rg -> DeviceRGB, /CS1 cs -> /CS1),
                                 the byte that wrote them (paste it into pdf.operations()), and the
                                 source line inside the structure. It is not a colour this module
                                 understood: nothing is converted (no CMYK->RGB, no ICC, no Lab, no
                                 rendering intent), a page's DefaultGray/DefaultRGB/DefaultCMYK
                                 resources are named once in the page's notes and never applied
                                 (4.5.4, S. 257/258), an scn/SCN in a space the module did not read
                                 keeps components null with the reason in note (S. 288), and no
                                 colour gets a name.
                        summary {text, clips, paths, xobjects, images, marks, filteredOut?,
                                 beyondBudget?} - counts of what was collected; a kind you did not
                                 ask for reads 0. marks is always 0 and that is not "this page marked
                                 nothing": a span's MCID lives in the /ParentTree and not in the BDC
                                 operand, so pdf.markedContent() answers a span; a page that really
                                 did open BMC/BDC sequences says so in its notes, so the 0 cannot be
                                 read as an absence.
                        walk    the counts of the one walk that produced the items (operators,
                                showOperators, textAdvances, paths, clipOperators, clipDepth,
                                clipComputed, xobjects/xobjectNames, saved/restored/balanced,
                                unknownOperators, itemsFilteredOut, descentStreams?, ...)
                        diagnostics {clean, operandDefects, stateUnderflows, emptyClips, pathErrors,
                                 otherReports, total, listed, beyondList?, entries, walkCut?,
                                 operatorBudget?, descents?}
                                 - what the walk could not use in this page's file, said in the answer
                                 instead of only in the walk (pdf.placement() is the function that
                                 asks for a walk, so this is where its findings surface). Counts come
                                 first and the sentences (`entries`, each starting "offset N:") only
                                 after, because "37 operand defects" is a statement even when the
                                 listing stopped at pdf.status().maxDiagnostics - the sentences the cap
                                 dropped are `beyondList`, and every one of them stays in `total`.
                                 operandDefects: operands that did not fit the operator's row;
                                 stateUnderflows: a Q with an empty stack, an EMC nobody opened;
                                 emptyClips: a clip after which nothing could be painted; pathErrors:
                                 an l before any m, …; otherReports: everything else - a show without a
                                 font, a gs whose dictionary could not be read, a W whose path was
                                 never painted, a second W that ran before the path was painted, a
                                 path on a matrix without an inverse, and a path whose segments ran
                                 under more than one CTM (at.ctm names the one at the operator that
                                 ended the path, the item's points carry their own - the count of
                                 matrices is in the sentence, the flag on the item is
                                 ctmChangedMidPath).
                                 walkCut plus operatorBudget appear when the operator budget
                                 (pdf.status().maxOps) cut the walk itself, which is a different cut
                                 from the item budget that sets the page's truncated. descents carries
                                 the same families for the form streams {includeXObjects: true} walked,
                                 each sentence naming the form it was found in.
                                 Notes stay the other family: notes are this module's sentences about
                                 its own answer, entries are the file's. That split is also why
                                 pdf.contentStreams() and pdf.operations() answer exactly what they
                                 answered before this block existed.
                   Example: pdf.placement('doc.pdf', {page: 21, bbox: [0, 30, 612, 120]}) reads the
                   text of one page's top band; {page: 21, what: ['all']} reads the band's text,
                   clips, paths, pictures and external objects together. An unknown word in what is
                   refused by name, because an answer that quietly leaves a kind out reads as "the
                   page has none of those".

                5) The document (what the file means, not how it is laid out)
                   pdf.document(pathOrBytes[, {maxPages: 200}])
                     -> {path, fileVersion, declaredVersion?, lang?, tagged, catalogObjNum,
                         entries: [{key, kind, objNum?, targetKind?, value?}],
                         pages: {treeRootObjNum, declaredCount?, count, mediaBoxes, rotations},
                         annotations?, outlines, form?, structure, nameTrees?, embeddedFiles?,
                         namedDestinations?, pageLabels?, metadata?, viewerPreferences?, notes?}
                        'entries' lists every key of the catalogue - the summaries below it
                        interpret the important ones, nothing is hidden.
                   pdf.pages(pathOrBytes[, {maxPages: 200, resources: false, annotations: true,
                                            maxItems: 100}])
                     -> per page {page, objNum, boxes: {mediaBox, cropBox, ...}, widthPt,
                                 heightPt, rotate?, pageLabel?, structParents?,
                                 resources?: {resourcesFrom?, font|xObject|extGState|colorSpace|
                                              pattern|shading|properties: {count, names, entries:
                                              [{name, objNum?, subtype?, shape?}], truncated?},
                                              procSet: ["/PDF", ...]},
                                 annotations: {count, items: [{subtype, rect, dest?, action?}],
                                               annotsUnreadable?}}
                        Boxes and Rotate follow the /Parent chain, as the specification asks. An
                        /Annots that is only readable through a reference is followed; a page whose
                        list cannot be read at all says annotsUnreadable instead of count: 0, and
                        the listing adds one sentence naming how many pages that was.
                        'resources' answers every category of PDF 1.7 Table 3.30 the same way: the
                        name a content stream writes, the object behind it and (for an XObject) its
                        /Subtype. A value written inline instead of referenced has no objNum, and
                        says so with shape: 'array' (a colour space written as [/ICCBased 12 0 R]),
                        'dictionary' or 'unreadable' (a reference to an object the file lost).
                        'procSet' is a list of names and never gets an address - a procedure set is
                        not an object. 'resourcesFrom' appears only when the dictionary was
                        inherited: 'pages:123' names the /Pages node that carries it (3.6.2). A
                        category longer than the listing shows is marked truncated where it was cut,
                        while count keeps the full number (the resolver behind pdf.fonts() and
                        pdf.text() goes to maxResourceEntries names per category).
                   pdf.outlines(pathOrBytes[, {maxItems: 500, maxDepth: 0}])
                     -> {present, objNum, declaredCount?, count, items: [{level, objNum, title,
                         page?, dest, action?, open?, children?}], truncated?}
                        A destination is resolved as far as it goes: explicit destinations give
                        the page number, a named destination is looked up in /Names /Dests (and
                        in the legacy /Dests dictionary) - the specification writes such a name as
                        a byte string, so a string destination is a named one, not a value - and a
                        /GoTo action points at its target.
                   pdf.names(pathOrBytes, tree[, {needle, limit: 200}])
                     -> {found, name, kind: 'names'|'numbers'|'dictionary', read, matched,
                         items: [{key, value}]}
                        tree is a key of /Names ('Dests', 'EmbeddedFiles', 'JavaScript', ...) or
                        one of 'PageLabels', 'ParentTree', 'KIDs', 'IDTree'.

                6) Tagged PDF (the logical structure - what a screen reader reads)
                   pdf.structTree(pathOrBytes[, {maxElements: 1000, maxDepth: 100,
                                                 attributes: false}])
                     -> {present, objNum, roleMap?, elementCount, maxDepth, tagHistogram,
                         elements: [{kind: 'element', tag, role?, id, objNum?|inline, page?,
                                     pageObjNum?, title?, alt?, lang?, mcid?, children?},
                                    {kind: 'mcid', mcid, page?},
                                    {kind: 'objectReference', objNum, targetSubtype?, page?}]}
                        'id' identifies an element ('12 0 R', or a /K path for inline ones);
                        'role' is the standard role a /RoleMap maps the producer's tag onto.
                   pdf.markedContent(pathOrBytes[, {page, maxPages: 50, maxItems: 300,
                                                    textChars: 300, fonts: 'auto'|false}])
                     -> per page {page, objNum, structParents?, marked, sequenceCount,
                                 sequences: [{sequence, operator, depth, offset, tag, mcid?,
                                              inStructureTree?, artifact?, text?}],
                                 fonts?, artifacts?, unclaimedMcids?, claimedButNotMarked?,
                                 structureObjectRefs?, unmarkedTextOperators?, notes?}
                        This is the check an accessibility review needs: 'unclaimedMcids' is
                        marked content no structure element refers to, 'claimedButNotMarked' is
                        the other way round, and 'unmarkedTextOperators' counts text drawn
                        outside any marked-content sequence. A page explains itself in its own
                        'notes'; only what the walk could not do (no structure tree at all, a page
                        walk that stopped) appears as a trailing {page: null, notes} entry.
                        The text of a sequence is read through its font as pdf.text() reads a page:
                        a sequence's first string usually inherits the Tf that stands *before* the
                        BMC/BDC, outside the bytes the text is cut from, so the token walk
                        remembers the font that was in effect when the sequence opened. 'fonts'
                        lists the fonts that drew here (the known fields, measured); there is no
                        second mapping block - the numbers of a page are read with
                        pdf.text(path, {page: n}). fonts: false reads every sequence by the
                        document convention.
                        pdf.document() says whether the document claims to be tagged at all
                        (/MarkInfo /Marked).
                        As in pdf.text(): a {page: n} read walks to page n, maxPages bounds only the
                        read that lists every page, and a page beyond the document is refused with
                        the document's length. 'page' is 1-based everywhere in this module (0 is a
                        call error, not a page).

                7) Value conventions (important for reading the results)
                   - a PDF *name* reads as a string with its slash: {Type: '/Page'}
                   - a PDF *string* is decoded text; the bytes stay available through
                     pdf.object(...).value and streamText/encoding
                   - an *indirect reference* stays visible: {ref: '12 0 R', objNum: 12, gen: 0};
                     with {resolveRefs: true} the target is added as 'target'
                     (cycles become {ref, cycle: true} instead of looping forever)
                   - the operands of `pdf.operations()` read the same way as any other value:
                     numbers as numbers, a name as "/F1", a literal string as its text, a hex
                     string as "<00480065>", a reference as {ref, objNum, gen}, an array or a
                     dictionary as its PDF source text. `operands` is the one exception and has
                     always been a count; the count is `operandCount` now and the values are
                     `operandValues`
                   - an object that is referenced *from several places* (the /Resources shared
                     by all pages, a font used everywhere) is expanded at its first occurrence
                     only; every further one reads as {ref, objNum, gen, shared: true}. The
                     result therefore grows with the number of distinct objects, not with the
                     number of references
                   - a walk is bounded: at most 100000 plain nodes (pdf.status().maxPlainNodes),
                     and pdf.info() at most pdf.status().maxInfoNodes. Beyond that a branch
                     reads as {kind: ..., truncated: true} and 'notes' says why - read the
                     object in question directly with pdf.object(path, objNum)
                   - every cut is *marked* in the result, but the matching sentence appears at
                     most five times per kind (pdf.status().maxListedNoteRepeats); the rest is
                     summarised with its exact count, so 'notes' stays readable on a document
                     with hundreds of shared or cyclic objects
                   - a *stream* object appears as its dictionary plus a 'stream' summary
                     (dataOffset, length, filters, storedBytes, decodedBytes, decodeError?)
                   - a budget is named after the thing it bounds, and pdf.status() prints every one
                     under that name: maxOps (operators one stream walks), maxDiagnostics (sentences
                     such a walk keeps), maxItems (items one pdf.placement() page read contributes -
                     the default of its maxItems option, and literally so: an item your own bbox
                     filter removed is not in the answer and does not spend the budget of one that
                     is), maxGlyphs (codes one showing operator lists with glyphs: true),
                     maxDepth (how far includeXObjects: true descends into forms),
                     maxPolygonPoints / maxPolygonPointsTotal (points one item / one read lists -
                     a clip's polygon and a path's drawing spend the same one), maxPathSegments,
                     maxClipLayers, maxStateDepth. Those are the words an answer quotes:
                     a placement page truncates on maxItems, its diagnostics block caps at
                     maxDiagnostics and names maxOps when the walk itself was cut. The older
                     spellings of the same numbers (maxWalkOperators, maxWalkDiagnostics,
                     defaultMaxPlacementItems, maxPlacementDetails, maxPlacementPolygonPoints,
                     maxPlacementPolygonPointsTotal, maxXobjectDepth) are still in pdf.status() -
                     same value, one version longer - and pdf.status().retiredLimits names every pair
                   - integers are integers, real numbers are reals - the reader does not
                     round, truncate or normalise anything

                8) Honest limits (what the reader will tell you instead of guessing)
                   - Encrypted documents are *described*, not decrypted: pdf.structure() shows
                     the /Encrypt dictionary, streams then fail with a clear message.
                   - Text extraction reads a content stream, not a rendering: a content stream
                     stores glyph codes, and every one of them is asked of the font its Tf named -
                     the chain /ToUnicode -> UCS code -> descendant's map -> the /cmap of the
                     embedded program (over CID -> GID) -> glyph name -> nothing (each step named
                     in mappedBy). What no source answers stays *out* of the text and is counted
                     (mapping.unmapped, unmappedSample, reliableText=false plus a reason); no
                     replacement character, no invented letter, no "closest ASCII".
                     A font whose encoding cannot say how wide a code is (a non-UCS predefined CMap)
                     keeps its strings unread for the same reason. {fonts: false} is the raw view:
                     the document convention over the whole page, which is what a CID page looked
                     like before - useful to compare, not useful to read.
                   - Text extraction is *not* laid out: in pdf.text() a line break comes from
                     Td/TD/Tm/T* and a word gap from a TJ kern value at or below -120. That is a
                     rule of thumb and it stays one (docs/js/pdf-content-plan.md, decision 10, closed
                     by increment D7 on the pair D5 measured) - not because the computed answer is
                     unavailable, but because it is *different*: on the check corpus every ignored TJ
                     number is positive (it closes a gap instead of opening one), while a number the
                     rule does honour is scaled by Tz, Tc, the CTM and UserUnit, none of which the
                     rule reads. Replacing the rule would move an answer users compare in order to buy
                     a precision the module would then have to earn back per document. What the answer
                     does instead is name the blind spot where it bites: a page whose spaces came from
                     a TJ array carries wordGaps {threshold, numbers, spaces, ignoredOpenings,
                     widestIgnoredOpening?}, so "these three spaces are a rule of thumb, and here is
                     what the rule passed over" is readable without switching the extraction over.
                     The computed answer is pdf.placement(): positions and real advances from the text
                     state (Tf size, Tz, Tc, Tw, Tj, T_rise, T_m and the CTM), every one of them in a
                     named space, each TJ element naming its distance twice - once in text units, once
                     on the page. pdf.text() does not use it, so its output has not moved.
                   - A walk's findings are a block of their own. What the interpreter could not use -
                     an operand that did not fit its row, a Q with an empty stack, a clip that
                     narrowed to nothing, a W whose path was never painted, a second W before the path
                     was ended, a path on a matrix without an inverse - is counted and quoted with its
                     byte offset in pdf.placement()'s diagnostics block, the function that asks for a
                     walk. notes stay the module's own sentences about its answer. Both families are
                     kept out of pdf.contentStreams() and pdf.operations(): those two answer what they
                     answered before the walk existed, which is why the walk can be checked against
                     them at all.
                   - Widths are the dictionary's first (/W, /DW, /FirstChar+/Widths) and the
                     embedded sfnt's where the dictionary has no value of its own (hmtx, brought
                     onto the same 1000-unit base, reported as widthSource 'hmtx'). A dictionary
                     that says nothing answers 'none' instead of a guessed 1000, and a program
                     whose hmtx says zero for a glyph it dropped leaves the width to /DW - the zero
                     is the width of an empty box, not a measurement. pdf.glyphMetrics() is that
                     answer per code, with the same value multiplied by a font size; the height of
                     a glyph is only ever what glyf writes for it (a composite is named, not
                     composed, and a Type 1 or CFF program has no box to give).
                   - All four program formats are read as far as their *tables and declarations*
                     reach, and nothing is executed. An sfnt is read as tables (head, hhea, maxp,
                     hmtx, loca, glyf, cmap). A Type 1 program is decrypted behind eexec - the
                     cipher hides a format, not a document - and its declarations are read
                     (/FontMatrix, /FontBBox, the /Encoding names, the /CharStrings names, /Subrs);
                     its charstrings are skipped by their announced length. A CFF is walked through
                     its indices and DICTs (Top DICT, Charset, FDArray families, Private widths),
                     and its charstring INDEX is counted, not parsed. So no width and no outline
                     comes out of a Type 1 or CFF program: /Widths and /W stay the answer. A name
                     below SID 391 lives in the CFF standard string table, which is not built in -
                     it is reported as sid<N> instead of being guessed. Nothing is ever executed: no
                     hinting instruction, no charstring, and the outline of a glyph is read as far
                     as its bounding box reaches - a composite glyph is named instead of composed.
                   - A /cmap of the program answers for a glyph only when its platform/encoding
                     pair promises Unicode; a Mac Roman or "user-defined" subtable (platform 1) is
                     used to pick a glyph for a width and is refused as a source of characters.
                   - A glyph name is only resolved from a name the file really names: a
                     /Differences entry, or the /Encoding a Type 1 program builds itself and the
                     Charset a plain CFF carries (through the Adobe Glyph List and the uniXXXX /
                     uXXXX / underscore rules; the mapped-by then says program-name:<rule>). A
                     CID-keyed Charset names CIDs, not glyphs, so it answers which CIDs the subset
                     holds and never which character one is. Neither is the base table a font
                     merely implies - a code page invented from Flags 4 would be worse than an
                     honest unmapped code.
                   - Text inside form XObjects is not followed (pdf.fonts(path, {formXObjects:
                     true}) at least lists the fonts such an XObject would draw with).
                   - The document view is a *bounded summary with addresses*, never a snapshot of
                     the whole document: pages, outline items, structure elements, tree entries
                     and annotations each have a limit, and a listing that reached it says so
                     (truncated / countNote / a note). Raise the option or read the single object
                     with pdf.object(path, objNum).
                   - The structure tree is read as it is written: /RoleMap is applied one hop,
                     /Lang and /Pg are inherited from the parent element, a missing /ParentTree is
                     reported and worked around through /Pg - but a producer that wrote an
                     element without /S, or attribute values the specification does not define,
                     is shown as it is rather than corrected.
                   - Page numbers of a structure element or a destination come from the page tree:
                     beyond pdf.status().defaultMaxPages they stay object numbers.
                   - Damaged files degrade instead of failing: warnings say what was skipped,
                     {salvage: true} lists objects the cross-reference lost.
                   - Resource-heavy files degrade too: pdf.info() does not expand /Root, an
                     object shared by many pages is expanded once ({shared: true} afterwards),
                     and a walk stops at its node budget with {truncated: true} plus a note -
                     a catalog of hundreds of pages is read object by object, never as a
                     whole-tree snapshot.

                Examples:
                   var st = pdf.structure('doc/report.pdf');
                   st.header.version;                     // '1.7'
                   st.crossReference[0].kind;             // 'table' or 'stream'
                   pdf.objects('doc/report.pdf').filter(function (o) { return o.subtype; });
                   var ops = pdf.operations('doc/report.pdf', 5);
                   ops.operations.items.forEach(function (i) {
                       console.log(i.offset + '  ' + i.operandsText);
                   });
                   var img = pdf.stream('doc/report.pdf', 8);        // JPEG bytes
                   fs.writeBytes('tmp/page-image.jpg', img);
                   pdf.text('doc/report.pdf', {page: 1}).pages[0].text;
                   pdf.text('doc/report.pdf').pages.forEach(function (p) {   // page through font
                       console.log(p.page, p.mapping.method, p.mapping.mapped + '/'
                           + p.mapping.codes, p.text.slice(0, 60));
                   });
                   pdf.text('doc/report.pdf', {page: 21, fonts: false});    // the raw view
                   pdf.cmap('doc/report.pdf', 26, {codes: '41 0B'}).codes;   // 'A' and 'ff'
                   pdf.cmap('doc/report.pdf', {font: 4}).counts;             // what /ToUnicode maps
                   pdf.fonts('doc/report.pdf').summary;               // {resolvable, codesOnly, …}
                   pdf.fonts('doc/report.pdf', {page: 1}).fonts[0];   // the font and where it draws
                   pdf.font('doc/report.pdf', 4, {widths: 'full'});   // encoding, widths, maps
                   pdf.decodeString('doc/report.pdf', 4, '<0B41>');   // {unicode: 'ffA', items: …}
                   pdf.decodeString('doc/report.pdf', {page: 21, name: '/F0'}, '<00480065>',
                                    {widths: true});
                   pdf.glyphMetrics('doc/report.pdf', 4, '41', {fontSize: 10});
                   pdf.glyphMetrics('doc/report.pdf', {page: 1, name: '/F0'}, [72, 101],
                                    {fontSize: 10.5}).glyphs;    // width, widthPt, bbox, lsb

                   var doc = pdf.document('doc/report.pdf');
                   doc.tagged;                            // /MarkInfo /Marked
                   doc.entries.forEach(function (e) { console.log(e.key, e.objNum); });
                   pdf.pages('doc/report.pdf', {resources: true})
                       .forEach(function (p) {
                           console.log(p.page, p.widthPt + 'x' + p.heightPt,
                                       p.resources ? JSON.stringify(p.resources.font.entries) : '',
                                       p.resources ? p.resources.resourcesFrom || '' : '');
                       });
                   pdf.outlines('doc/report.pdf').items.forEach(function (o) {
                       console.log('  '.repeat(o.level - 1) + (o.title || '') + ' -> p.' + o.page);
                   });
                   var tree = pdf.structTree('doc/report.pdf');
                   tree.tagHistogram;                     // [{tag: '/P', count: 120}, ...]
                   tree.elements.forEach(printElement);   // tag, page, mcid, children
                   pdf.markedContent('doc/report.pdf', {page: 1}).forEach(function (p) {
                       console.log('marked:', p.marked, 'unclaimed:', p.unclaimedMcids);
                   });
                   pdf.names('doc/report.pdf', 'EmbeddedFiles');

                   function printElement(el, indent) {
                       indent = indent || '';
                       console.log(indent + (el.tag || el.kind)
                           + (el.page ? ' p.' + el.page : '')
                           + (el.mcid !== undefined ? ' mcid ' + el.mcid : ''));
                       (el.children || []).forEach(function (c) {
                           printElement(c, indent + '  ');
                       });
                   }

                --- Help ---
                pdf.help()             - This help text.
                pdf.status()           - Limits, supported filters, open tasks.
                """;
    }
}
