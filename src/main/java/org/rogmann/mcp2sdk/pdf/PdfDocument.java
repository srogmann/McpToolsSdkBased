package org.rogmann.mcp2sdk.pdf;

import org.rogmann.mcp2sdk.pdf.geo.PdfPageSpace;
import org.rogmann.mcp2sdk.pdf.geo.PdfRect;
import org.rogmann.mcp2sdk.pdf.geo.PdfSpace;
import org.rogmann.mcp2sdk.pdf.ops.PdfContentInterpreter;
import org.rogmann.mcp2sdk.pdf.ops.PlacementSink;
import org.rogmann.mcp2sdk.pdf.ops.TextSink;
import org.rogmann.mcp2sdk.pdf.ops.XObjectOps;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read access to a PDF document: its file structure, its objects (including the indirect
 * ones and those packed in object streams), its streams (stored and decoded) and its
 * content streams.
 * <p>
 * This class is the Java counterpart of the {@code pdf} JavaScript module. It works on the
 * raw file image in memory and never writes anything. Objects are parsed on demand and cached
 * for the lifetime of the instance; stream data is only decoded when a caller asks for it.
 * </p>
 * <p>
 * The <em>meaning</em> of the document (catalog, page tree, forms, signatures) is deliberately
 * out of focus here: only the minimum of the page tree is walked to find content streams
 * (see {@link #pages(int, List)}), because "which content stream belongs to which page" is
 * the question a caller of {@code pdf.contentStreams()} has. A full document-structure view is
 * a separate step.
 * </p>
 */
public final class PdfDocument {

    /** Streams up to this stored size are inflated for the {@code decodedBytes} summary. */
    public static final int DECODE_FOR_SUMMARY_LIMIT = 8 * 1024 * 1024;

    /** Maximum page-tree depth (a document with deeper nesting is treated as damaged). */
    public static final int MAX_PAGE_DEPTH = 64;

    /**
     * Marker key in the page walk's <em>visited</em> set: it holds the one occurrence of the
     * "stopped at the page limit" explanation, because that is a property of the walk and not of
     * every branch the walk then skips.
     */
    private static final String PAGE_LIMIT_NOTED = "limit";

    /**
     * Nesting depth {@link #info()} uses for the trailer values it shows. The trailer content
     * is shallow by nature ({@code /ID} is an array of two strings, {@code /Info} a dictionary
     * of strings); the limit is there for a producer that stuffs something enormous in there.
     */
    public static final int INFO_MAX_DEPTH = 8;

    /**
     * Node budget {@link #info()} allows itself. Much smaller than
     * {@link PdfValues#DEFAULT_MAX_NODES} because {@code info()} resolves nothing: a trailer
     * and an {@code /Info} dictionary that need more than this are unusual enough that a
     * truncation note is the better answer than a large result.
     */
    public static final int INFO_MAX_NODES = 10_000;

    /** Default upper bound for the number of pages walked. */
    public static final int DEFAULT_MAX_PAGES = 5000;

    /** Upper bound for the objects a single object stream may declare. */
    private static final int MAX_OBJECTS_PER_STREAM = 2_000_000;

    private final String displayName;
    private final byte[] data;
    private final PdfFileStructure structure;
    private final Map<Integer, Object> objectCache = new HashMap<>();
    private final Map<Integer, Map<Integer, Object>> objectStreamCache = new HashMap<>();
    private final Map<Integer, List<String>> objectWarnings = new HashMap<>();
    /** Lazily built index of the cross-reference entries (used for indirect {@code /Length}). */
    private Map<Integer, PdfCrossReference.Entry> entryIndex;
    /**
     * The mapping chain of every font this document was asked about, keyed by object number: a
     * 500-page walk over five fonts must not read the same {@code /ToUnicode} stream 2 500 times.
     * A document instance lives exactly one {@code pdf.*()} call, so this cache needs no
     * invalidation (decision 5 of {@code docs/js/pdf-fonts-plan.md}).
     */
    private final Map<Integer, PdfGlyphMapper> glyphMappers = new HashMap<>();

    private PdfDocument(String displayName, byte[] data, PdfFileStructure structure) {
        this.displayName = displayName;
        this.data = data;
        this.structure = structure;
    }

    /**
     * Opens a PDF file image.
     * @param displayName display name used in messages (project-relative path)
     * @param data raw file bytes
     * @return the document
     * @throws PdfException when the file is not a readable PDF
     */
    public static PdfDocument open(String displayName, byte[] data) {
        PdfFileStructure analysed = PdfFileStructure.analyze(displayName, data);
        PdfDocument document = new PdfDocument(displayName, data, analysed);
        analysed.attach(document);
        return document;
    }

    /** Display name (project-relative path). */
    public String displayName() {
        return displayName;
    }

    /** The file image. */
    public byte[] data() {
        return data;
    }

    /** The analysed file structure. */
    public PdfFileStructure structure() {
        return structure;
    }

    /** The cross-reference data. */
    public PdfCrossReference crossReference() {
        return structure.crossReference();
    }

    // ===================================================================
    // Objects
    // ===================================================================

    /**
     * Looks up the cross-reference entry of an object.
     * @param objNum object number
     * @return the entry
     * @throws PdfException when the object is not registered
     */
    public PdfCrossReference.Entry requireEntry(int objNum) {
        PdfCrossReference.Entry entry = crossReference().entry(objNum);
        if (entry == null) {
            throw new PdfException("Object " + objNum + " is not in the cross-reference of "
                    + displayName + " (" + crossReference().objectCount() + " object(s) registered,"
                    + (crossReference().salvaged() ? " recovered by a file scan"
                    : " highest object number " + highestObjectNumber())
                    + "). Try pdf.objects(" + quote(displayName) + ", {salvage: true}).");
        }
        return entry;
    }

    private int highestObjectNumber() {
        int highest = -1;
        for (Integer objNum : crossReference().objectNumbers()) {
            highest = Math.max(highest, objNum);
        }
        return highest;
    }

    private static String quote(String text) {
        return "'" + text + "'";
    }

    /**
     * Resolves an indirect reference (used for dictionary values and filter parameters).
     * @param ref the reference
     * @return the referenced value
     */
    public Object resolve(PdfReference ref) {
        return objectValue(ref.objNum());
    }

    /**
     * Reads the value of an object (a {@link PdfStream} for stream objects).
     * @param objNum object number
     * @return the parsed value
     * @throws PdfException when the object is missing, free or unreadable
     */
    public Object objectValue(int objNum) {
        if (objectCache.containsKey(objNum)) {
            return objectCache.get(objNum);
        }
        PdfCrossReference.Entry entry = requireEntry(objNum);
        List<String> warnings = new ArrayList<>();
        Object value = parseEntry(entry, warnings);
        objectCache.put(objNum, value);
        if (!warnings.isEmpty()) {
            objectWarnings.put(objNum, warnings);
        }
        return value;
    }

    /**
     * Warnings produced while reading one object.
     * @param objNum object number
     * @return warnings (possibly empty, never null)
     */
    public List<String> objectWarnings(int objNum) {
        return objectWarnings.getOrDefault(objNum, List.of());
    }

    /**
     * Parses the object a cross-reference entry points at.
     */
    private Object parseEntry(PdfCrossReference.Entry entry, List<String> warnings) {
        if (entry.type() == PdfCrossReference.TYPE_FREE) {
            throw new PdfException("Object " + entry.objNum() + " is a free (deleted) entry in the"
                    + " cross-reference of " + displayName);
        }
        if (entry.type() == PdfCrossReference.TYPE_UNCOMPRESSED) {
            if (entry.offset() < 0 || entry.offset() >= data.length) {
                throw new PdfException("Object " + entry.objNum() + " points to offset "
                        + entry.offset() + " which lies outside " + displayName);
            }
            PdfParser parser = new PdfParser(data, (int) entry.offset());
            parser.setWarningSink(warnings);
            int[] header = parser.readObjectHeader();
            if (header == null) {
                warnings.add("No 'num gen obj' header at offset " + entry.offset()
                        + "; the value was read anyway");
            } else if (header[0] != entry.objNum()) {
                warnings.add("The object at offset " + entry.offset() + " calls itself "
                        + header[0] + " but the cross-reference says " + entry.objNum());
            }
            return parser.parseValue();
        }
        if (entry.type() == PdfCrossReference.TYPE_COMPRESSED) {
            Map<Integer, Object> packed = objectStreamObjects(entry.streamObj(), warnings);
            if (!packed.containsKey(entry.objNum())) {
                throw new PdfException("Object " + entry.objNum() + " is declared as number "
                        + entry.index() + " inside object stream " + entry.streamObj()
                        + ", but that stream does not contain it");
            }
            return packed.get(entry.objNum());
        }
        throw new PdfException("Object " + entry.objNum() + " has an unknown cross-reference type "
                + entry.type());
    }

    /**
     * Reads the objects packed inside an object stream ({@code /Type /ObjStm}).
     * @param streamObjNum object number of the object stream
     * @param warnings warning sink
     * @return map of object number to value (insertion order)
     */
    public Map<Integer, Object> objectStreamObjects(int streamObjNum, List<String> warnings) {
        Map<Integer, Object> cached = objectStreamCache.get(streamObjNum);
        if (cached != null) {
            return cached;
        }
        Map<Integer, Object> result = new LinkedHashMap<>();
        Object streamValue = objectValue(streamObjNum);
        if (!(streamValue instanceof PdfStream stream)) {
            throw new PdfException("Object " + streamObjNum + " should be an object stream but is "
                    + PdfParser.kindOf(streamValue));
        }
        if (!PdfName.dictHas(stream.dict(), "Type", "ObjStm")) {
            warnings.add("Object " + streamObjNum + " is used as an object stream but its /Type is "
                    + PdfName.ofDict(stream.dict(), "Type") + " instead of /ObjStm");
        }
        Object nValue = stream.dict().get("N");
        Object firstValue = stream.dict().get("First");
        if (!(nValue instanceof Long) || !(firstValue instanceof Long)) {
            throw new PdfException("Object stream " + streamObjNum + " has no numeric /N and /First");
        }
        int count = Math.toIntExact((Long) nValue);
        int first = Math.toIntExact((Long) firstValue);
        byte[] plain = decodedStream(stream, warnings);
        if (first < 0 || first > plain.length) {
            throw new PdfException("Object stream " + streamObjNum + " declares /First " + first
                    + " outside its " + plain.length + " decoded bytes");
        }
        if (count < 0 || count > MAX_OBJECTS_PER_STREAM) {
            throw new PdfException("Object stream " + streamObjNum + " declares an implausible /N "
                    + count + " (limit " + MAX_OBJECTS_PER_STREAM + " objects per stream)");
        }
        PdfParser headerParser = new PdfParser(plain, 0);
        int[] objNums = new int[count];
        int[] offsets = new int[count];
        int read = 0;
        for (int i = 0; i < count; i++) {
            Long objNum = headerParser.tryReadIntegerToken();
            Long offset = headerParser.tryReadIntegerToken();
            if (objNum == null || offset == null) {
                warnings.add("The header of object stream " + streamObjNum + " holds only " + i
                        + " of the declared " + count + " object(s)");
                break;
            }
            objNums[i] = objNum.intValue();
            offsets[i] = offset.intValue();
            read++;
        }
        for (int i = 0; i < read; i++) {
            int valueStart = first + offsets[i];
            if (valueStart < 0 || valueStart > plain.length) {
                warnings.add("Object " + objNums[i] + " inside object stream " + streamObjNum
                        + " points to offset " + valueStart + " which is outside the stream data");
                continue;
            }
            try {
                PdfParser valueParser = new PdfParser(plain, valueStart);
                result.put(objNums[i], valueParser.parseValue());
            } catch (PdfException e) {
                warnings.add("Object " + objNums[i] + " inside object stream " + streamObjNum
                        + " cannot be parsed: " + e.getMessage());
            }
        }
        objectStreamCache.put(streamObjNum, result);
        return result;
    }

    /**
     * Plain, JS-ready view of one object.
     * @param objNum object number
     * @param resolveRefs whether nested references are resolved
     * @param maxDepth maximum nesting depth
     * @param maxStringChars maximum length of a single string value
     * @return {@code {objNum, gen, kind, location, value, stream?, notes?}}
     */
    public Map<String, Object> objectInfo(int objNum, boolean resolveRefs, int maxDepth,
                                         int maxStringChars) {
        PdfCrossReference.Entry entry = requireEntry(objNum);
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("objNum", (long) objNum);
        info.put("gen", (long) entry.gen());
        info.put("location", entry.toMap());
        Object value;
        try {
            value = objectValue(objNum);
        } catch (PdfException e) {
            info.put("kind", "unreadable");
            info.put("error", e.getMessage());
            addCollectedWarnings(info, objNum);
            return info;
        }
        info.put("kind", PdfParser.kindOf(value));
        PdfValues.Ctx ctx = new PdfValues.Ctx().resolveRefs(resolveRefs)
                .resolver(this::resolve).maxDepth(maxDepth).maxStringChars(maxStringChars);
        if (value instanceof PdfStream stream) {
            info.put("value", PdfValues.toPlain(stream.dict(), ctx));
            info.put("stream", streamSummary(stream, true));
        } else {
            info.put("value", PdfValues.toPlain(value, ctx));
        }
        if (!ctx.notes().isEmpty()) {
            info.put("notes", new ArrayList<Object>(ctx.notes()));
        }
        addCollectedWarnings(info, objNum);
        return info;
    }

    private void addCollectedWarnings(Map<String, Object> info, int objNum) {
        List<String> warnings = objectWarnings(objNum);
        if (!warnings.isEmpty()) {
            info.put("warnings", new ArrayList<Object>(warnings));
        }
    }

    /**
     * Overview of all registered objects - the fastest way to get oriented in a PDF.
     * @param limit maximum number of entries (0 = no limit)
     * @param includeFree whether free entries are listed too
     * @return one entry per object
     */
    public List<Map<String, Object>> objects(int limit, boolean includeFree) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Integer objNum : crossReference().objectNumbers()) {
            if (limit > 0 && list.size() >= limit) {
                break;
            }
            PdfCrossReference.Entry entry = crossReference().entry(objNum);
            if (entry == null) {
                continue;
            }
            if (!includeFree && entry.type() == PdfCrossReference.TYPE_FREE) {
                continue;
            }
            list.add(objectSummary(objNum, entry));
        }
        return list;
    }

    /**
     * Objects that exist in the file but are not registered in the cross-reference: the
     * result of scanning for {@code num gen obj} markers. On a healthy file the list is
     * empty; on a damaged or hand-edited one it shows what a repair would have to recover.
     * @return one entry per orphaned object
     */
    public List<Map<String, Object>> orphanObjects() {
        List<String> scanWarnings = new ArrayList<>();
        Map<Integer, PdfCrossReference.Entry> scanned =
                PdfFileStructure.scanForObjects(data, scanWarnings);
        List<Map<String, Object>> orphans = new ArrayList<>();
        for (PdfCrossReference.Entry entry : scanned.values()) {
            if (crossReference().entry(entry.objNum()) != null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("objNum", (long) entry.objNum());
            item.put("gen", (long) entry.gen());
            item.put("where", "scanned");
            item.put("offset", entry.offset());
            try {
                item.put("kind", PdfParser.kindOf(parseEntry(entry, new ArrayList<>())));
            } catch (PdfException e) {
                item.put("kind", "unreadable");
                item.put("error", e.getMessage());
            }
            orphans.add(item);
        }
        return orphans;
    }

    /**
     * One line of the {@link #objects(int, boolean)} listing.
     */
    private Map<String, Object> objectSummary(int objNum, PdfCrossReference.Entry entry) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("objNum", (long) objNum);
        item.put("gen", (long) entry.gen());
        item.put("where", entry.typeName());
        if (entry.type() == PdfCrossReference.TYPE_UNCOMPRESSED) {
            item.put("offset", entry.offset());
        } else if (entry.type() == PdfCrossReference.TYPE_COMPRESSED) {
            item.put("objStm", (long) entry.streamObj());
            item.put("index", (long) entry.index());
        }
        if (entry.type() == PdfCrossReference.TYPE_FREE) {
            item.put("kind", "free");
            return item;
        }
        try {
            Object value = objectValue(objNum);
            item.put("kind", PdfParser.kindOf(value));
            if (value instanceof PdfStream stream) {
                PdfName subtype = PdfName.ofDict(stream.dict(), "Subtype");
                if (subtype != null) {
                    item.put("subtype", subtype.toString());
                }
                PdfName type = PdfName.ofDict(stream.dict(), "Type");
                if (type != null) {
                    item.put("dictType", type.toString());
                }
                item.put("filters", filterNames(stream.dict().get("Filter")));
                List<String> streamNotes = new ArrayList<>();
                byte[] raw = rawBytes(stream, streamNotes);
                item.put("storedBytes", (long) raw.length);
                if (raw.length <= DECODE_FOR_SUMMARY_LIMIT) {
                    try {
                        item.put("decodedBytes",
                                (long) PdfFilters.decodeChain(raw, stream.dict().get("Filter"),
                                        firstNonNull(stream.dict().get("DecodeParms"),
                                                stream.dict().get("DP")), this::resolve)
                                        .data().length);
                    } catch (PdfException e) {
                        item.put("decodeError", e.getMessage());
                    }
                }
                if (!streamNotes.isEmpty()) {
                    item.put("notes", new ArrayList<Object>(streamNotes));
                }
            } else if (value instanceof Map<?, ?> dict) {
                PdfName type = PdfName.ofDict(dict, "Type");
                if (type != null) {
                    item.put("dictType", type.toString());
                }
                item.put("entries", (long) dict.size());
            } else if (value instanceof List<?> array) {
                item.put("items", (long) array.size());
            } else if (value instanceof PdfString string) {
                item.put("chars", (long) string.text().length());
            } else if (value instanceof PdfName name) {
                item.put("name", name.toString());
            }
        } catch (PdfException e) {
            item.put("kind", "unreadable");
            item.put("error", e.getMessage());
        }
        return item;
    }

    /**
     * Trailer-level information: version, the {@code /Root} and {@code /Info} pointers plus the
     * document information dictionary (title, author, producer, dates).
     * <p>
     * The pointers are <em>shown, not followed</em>: {@code root} reads as
     * {@code {ref: "1 0 R", objNum: 1, gen: 0}} and the values of {@code /Info} keep their
     * references as they are. Following {@code /Root} would expand the whole catalog - with a
     * few hundred pages sharing their {@code /Resources} trees that turned a trailer summary
     * into a walk over the entire document and ran the server out of heap
     * ({@code docs/js/pdf-resource-explosion-plan.md}). The way to look into the catalog is
     * {@code pdf.object(path, objNum, {resolveRefs: true})}, which is bounded by
     * {@link PdfValues#DEFAULT_MAX_NODES} nodes.
     * </p>
     * @return info map
     */
    public Map<String, Object> info() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", displayName);
        result.put("fileVersion", structure.version() != null ? structure.version() : "unknown");
        Map<String, Object> trailer = crossReference().trailer();
        // One context for the three parts below (root, id, info values): nothing is resolved,
        // so the budget and the notes cover the whole info() answer as a single bounded walk.
        PdfValues.Ctx ctx = new PdfValues.Ctx().resolveRefs(false).maxDepth(INFO_MAX_DEPTH)
                .maxNodes(INFO_MAX_NODES);
        Object root = trailer.get("Root");
        if (root != null) {
            result.put("root", PdfValues.toPlain(root, ctx));
        }
        Object id = trailer.get("ID");
        if (id != null) {
            result.put("id", PdfValues.toPlain(id, ctx));
        }
        result.put("updateSections", (long) crossReference().sections().size());
        Object infoRef = trailer.get("Info");
        if (infoRef != null) {
            Map<String, Object> info = new LinkedHashMap<>();
            Object infoValue = infoRef;
            if (infoRef instanceof PdfReference reference) {
                info.put("objNum", (long) reference.objNum());
                try {
                    infoValue = objectValue(reference.objNum());
                } catch (PdfException e) {
                    info.put("unreadable", e.getMessage());
                }
            }
            if (infoValue instanceof Map<?, ?> dict) {
                Map<String, Object> plain = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : dict.entrySet()) {
                    plain.put(String.valueOf(entry.getKey()), PdfValues.toPlain(entry.getValue(), ctx));
                }
                info.put("values", plain);
                Object producer = dict.get("Producer");
                if (producer instanceof PdfString string) {
                    info.put("producer", string.text());
                }
            }
            result.put("info", info);
        }
        Map<String, Object> encryption = structure.encryption();
        if (encryption != null) {
            result.put("encryption", encryption);
        }
        if (!ctx.notes().isEmpty()) {
            result.put("notes", new ArrayList<Object>(ctx.notes()));
        }
        if (!structure.warnings().isEmpty()) {
            result.put("structureWarnings", new ArrayList<Object>(structure.warnings()));
        }
        return result;
    }

    // ===================================================================
    // Streams
    // ===================================================================

    /**
     * The stored (still encoded) bytes of a stream object.
     * @param objNum object number
     * @return stored bytes
     */
    public byte[] rawStreamBytes(int objNum) {
        Object value = objectValue(objNum);
        if (!(value instanceof PdfStream stream)) {
            throw new PdfException("Object " + objNum + " of " + displayName + " is "
                    + PdfParser.kindOf(value) + ", not a stream. Streams are listed by"
                    + " pdf.streams(" + quote(displayName) + ").");
        }
        return rawBytes(stream, null);
    }

    /**
     * Decoded bytes of a stream object (filter chain applied).
     * @param objNum object number
     * @return decoded bytes and notes
     */
    public PdfFilters.Result stream(int objNum) {
        Object value = objectValue(objNum);
        if (!(value instanceof PdfStream stream)) {
            throw new PdfException("Object " + objNum + " of " + displayName + " is "
                    + PdfParser.kindOf(value) + ", not a stream.");
        }
        List<String> notes = new ArrayList<>();
        byte[] decoded = decodedStream(stream, notes);
        return new PdfFilters.Result(decoded, notes);
    }

    /**
     * Reads and decodes a stream object.
     * @param stream the stream
     * @param notes note/warning sink
     * @return decoded bytes
     */
    public byte[] decodedStream(PdfStream stream, List<String> notes) {
        byte[] raw = rawBytes(stream, notes);
        Map<String, Object> dict = stream.dict();
        try {
            PdfFilters.Result result = PdfFilters.decodeChain(raw, dict.get("Filter"),
                    firstNonNull(dict.get("DecodeParms"), dict.get("DP")), this::resolve);
            notes.addAll(result.notes());
            return result.data();
        } catch (PdfException e) {
            throw new PdfException("Object " + describeStream(stream) + ": " + e.getMessage(), e);
        }
    }

    private String describeStream(PdfStream stream) {
        return "stream at offset " + stream.dataOffset();
    }

    /**
     * Stored bytes of a stream, honouring a possibly indirect {@code /Length}.
     * @param stream the stream
     * @param notes note/warning sink (may be {@code null})
     * @return stored bytes
     */
    public byte[] rawBytes(PdfStream stream, List<String> notes) {
        List<String> warnings = notes != null ? notes : new ArrayList<>();
        return PdfFileStructure.rawBytes(data, stream, entryIndex(), warnings);
    }

    /**
     * Index of the cross-reference entries (built once - the chain never changes).
     * @return object number to entry
     */
    private Map<Integer, PdfCrossReference.Entry> entryIndex() {
        Map<Integer, PdfCrossReference.Entry> index = this.entryIndex;
        if (index == null) {
            index = new HashMap<>();
            for (Integer objNum : crossReference().objectNumbers()) {
                PdfCrossReference.Entry entry = crossReference().entry(objNum);
                if (entry != null) {
                    index.put(objNum, entry);
                }
            }
            this.entryIndex = index;
        }
        return index;
    }

    /**
     * Summary of a stream: offsets, declared length, filters and (for small streams) the
     * decoded size.
     * @param stream the stream
     * @param probeDecode whether small streams may be inflated to report their decoded size
     * @return summary map
     */
    public Map<String, Object> streamSummary(PdfStream stream, boolean probeDecode) {
        Map<String, Object> map = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        byte[] raw = rawBytes(stream, notes);
        map.put("dataOffset", stream.dataOffset());
        Long declared = PdfParser.plainLength(stream.rawLength());
        if (declared != null) {
            map.put("length", declared);
        }
        if (stream.rawLength() instanceof PdfReference reference) {
            map.put("lengthRef", reference.toString());
        }
        if (stream.lengthApproximate()) {
            map.put("lengthApproximate", true);
        }
        map.put("storedBytes", (long) raw.length);
        List<String> filters = filterNames(stream.dict().get("Filter"));
        if (!filters.isEmpty()) {
            map.put("filters", filters);
        }
        PdfName subtype = PdfName.ofDict(stream.dict(), "Subtype");
        if (subtype != null) {
            map.put("subtype", subtype.toString());
        }
        for (String key : List.of("Type", "Width", "Height", "BitsPerComponent", "ColorSpace",
                "DecodeParms", "Matrix", "BBox")) {
            if (stream.dict().containsKey(key)) {
                map.put(camel(key), PdfValues.toPlain(stream.dict().get(key), new PdfValues.Ctx()));
            }
        }
        if (probeDecode && !filters.isEmpty()) {
            if (raw.length <= DECODE_FOR_SUMMARY_LIMIT) {
                try {
                    byte[] decoded = decodedStream(stream, notes);
                    map.put("decodedBytes", (long) decoded.length);
                } catch (PdfException e) {
                    map.put("decodeError", e.getMessage());
                }
            } else {
                map.put("decodedBytes", null);
                map.put("decodeSkipped", "the stream holds " + raw.length
                        + " stored bytes (over the " + DECODE_FOR_SUMMARY_LIMIT
                        + " byte summary limit) - use pdf.stream() to decode it");
            }
        }
        if (!notes.isEmpty()) {
            map.put("notes", new ArrayList<Object>(notes));
        }
        return map;
    }

    private static String camel(String key) {
        return Character.toLowerCase(key.charAt(0)) + key.substring(1);
    }

    /**
     * Lists all stream objects with their metadata (no stream is decoded here).
     * @param limit maximum number of entries (0 = no limit)
     * @return one entry per stream object
     */
    public List<Map<String, Object>> streams(int limit) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Integer objNum : crossReference().objectNumbers()) {
            if (limit > 0 && list.size() >= limit) {
                break;
            }
            PdfCrossReference.Entry entry = crossReference().entry(objNum);
            if (entry == null || entry.type() == PdfCrossReference.TYPE_FREE) {
                continue;
            }
            try {
                Object value = objectValue(objNum);
                if (value instanceof PdfStream stream) {
                    Map<String, Object> item = streamSummary(stream, false);
                    item.put("objNum", (long) objNum);
                    item.put("gen", (long) entry.gen());
                    list.add(item);
                }
            } catch (PdfException e) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("objNum", (long) objNum);
                item.put("kind", "unreadable");
                item.put("error", e.getMessage());
                list.add(item);
            }
        }
        return list;
    }

    private static List<String> filterNames(Object filterValue) {
        List<String> names = new ArrayList<>();
        if (filterValue instanceof PdfName name) {
            names.add(name.toString());
        } else if (filterValue instanceof List<?> list) {
            for (Object item : list) {
                names.add(item instanceof PdfName name ? name.toString()
                        : PdfValues.toPlain(item, new PdfValues.Ctx()) + "");
            }
        }
        return names;
    }

    private static Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    // ===================================================================
    // Page tree (only as far as content streams need it)
    // ===================================================================

    /**
     * One page of the document: its object number plus the objects holding its content.
     *
     * @param page 1-based page number
     * @param objNum page object number
     * @param contentObjNums the objects of {@code /Contents} (usually one)
     */
    public record Page(int page, int objNum, List<Integer> contentObjNums) {
    }

    /**
     * Walks the page tree. This is the smallest possible slice of the document structure: it
     * follows {@code /Root}, {@code /Pages}, {@code /Kids} and {@code /Type /Page} and nothing
     * else. A full document view (named destinations, outlines, forms) is a later step.
     * @param maxPages upper bound for the number of pages
     * @param notes note/warning sink
     * @return the pages in document order
     */
    public List<Page> pages(int maxPages, List<String> notes) {
        return pages(maxPages, notes, true);
    }

    /**
     * The page tree as the calling read needs it. A <em>listing</em> walks up to its limit; a call
     * that asked for <em>one</em> page walks to that page and no further. A {@code maxPages} that
     * bounds a listing must not decide which page of the document exists - and the "raise maxPages"
     * sentence would point at an option that never bound here, so it is left out for a single read.
     * @param pageIndex the page the call wants, or null for a listing
     * @param maxPages the limit of a listing (not the limit of a single page read)
     * @param notes note/warning sink
     * @return the pages the walk reached, in document order
     */
    public List<Page> pagesForRead(Integer pageIndex, int maxPages, List<String> notes) {
        return pageIndex != null ? pages(pageIndex, notes, false) : pages(maxPages, notes, true);
    }

    /**
     * The page count the page tree declares ({@code /Count}), or -1 when the tree cannot be read.
     * It is what the file <em>claims</em>; {@link #pagesForRead} is what the walk <em>found</em>.
     * Both belong in the answer to "there is no page 400", because the difference between them is
     * the difference between a short document and a tree the walk could not finish.
     * @return the declared page count, or -1
     */
    public int declaredPageCount() {
        try {
            Object root = dereference(crossReference().trailer().get("Root"));
            if (!(root instanceof Map<?, ?> rootDict)) {
                return -1;
            }
            Object tree = dereference(rootDict.get("Pages"));
            if (!(tree instanceof Map<?, ?> treeDict)) {
                return -1;
            }
            Object count = treeDict.get("Count");
            if (count instanceof Long number) {
                return number.intValue();
            }
            if (count instanceof Double real && real == Math.floor(real) && !real.isInfinite()) {
                return real.intValue();
            }
        } catch (PdfException e) {
            // A missing declared count is a fact the caller can live with; a broken pointer must
            // not turn a refusal into a second error on top of the first one.
            return -1;
        }
        return -1;
    }

    /**
     * One sentence for "this page is not there" - the same wording in every function that asks for
     * a page, so the refusal names its reason. A tree that counts more than it holds says both
     * numbers (a walk that stopped early is not a short document); a tree that simply ends says how
     * long it is. The older "(K page(s) found)" could not tell the two apart.
     * <p>
     * The walk's own notes ride along: an exception path has no {@code notes} field, so a sentence
     * that said "the walk stopped, its notes explain" without the note itself would send the caller
     * to a list that never reaches them.
     * </p>
     * @param pageIndex the page that was asked for
     * @param walked how many pages the walk reached
     * @param walkNotes what the walk itself reported (may be null or empty)
     * @return the message
     */
    public String pageNotFound(int pageIndex, int walked, List<String> walkNotes) {
        int declared = declaredPageCount();
        StringBuilder sb = new StringBuilder("There is no page ").append(pageIndex).append(" in ")
                .append(displayName).append(" - ");
        if (declared > walked) {
            sb.append("the page tree holds ").append(walked)
                    .append(" page(s) while /Count declares ").append(declared)
                    .append(": the walk stopped before the tree did");
        } else if (declared > 0) {
            sb.append("the document has ").append(declared).append(" page(s)");
        } else {
            sb.append("the page tree holds ").append(walked).append(" page(s)");
        }
        if (walkNotes != null && !walkNotes.isEmpty()) {
            sb.append("; the walk reported: ").append(walkNotes.get(0));
            if (walkNotes.size() > 1) {
                sb.append(" (and ").append(walkNotes.size() - 1).append(" more)");
            }
        }
        return sb.toString();
    }

    private List<Page> pages(int maxPages, List<String> notes, boolean noteLimit) {
        List<Page> result = new ArrayList<>();
        Map<String, Object> trailer = crossReference().trailer();
        Object rootValue = trailer.get("Root");
        if (rootValue == null) {
            notes.add("The trailer has no /Root: the page tree cannot be located (the document may"
                    + " need repair). Streams are still readable with pdf.streams().");
            return result;
        }
        Object root = dereference(rootValue);
        if (!(root instanceof Map<?, ?> rootDict)) {
            notes.add("/Root does not point at a dictionary but at " + PdfParser.kindOf(root));
            return result;
        }
        Object pagesValue = rootDict.get("Pages");
        if (pagesValue == null) {
            notes.add("The document catalogue has no /Pages entry");
            return result;
        }
        Set<String> visited = new HashSet<>();
        collectPages(pagesValue, 1, maxPages, result, notes, visited, noteLimit);
        return result;
    }

    /**
     * Resolves a value that may be an indirect reference.
     * @param value value or reference
     * @return the dereferenced value
     */
    public Object dereference(Object value) {
        Object current = value;
        int hops = 0;
        while (current instanceof PdfReference reference) {
            if (++hops > 10) {
                throw new PdfException("Indirect reference chain longer than 10 steps ("
                        + reference + ")");
            }
            current = objectValue(reference.objNum());
        }
        return current;
    }

    private void collectPages(Object node, int depth, int maxPages, List<Page> result,
                              List<String> notes, Set<String> visited, boolean noteLimit) {
        if (result.size() >= maxPages) {
            return;
        }
        if (depth > MAX_PAGE_DEPTH) {
            notes.add("Page tree deeper than " + MAX_PAGE_DEPTH + " levels - the walk stops here");
            return;
        }
        Object resolved = dereference(node);
        if (!(resolved instanceof Map<?, ?> dict)) {
            notes.add("A page-tree node is " + PdfParser.kindOf(resolved) + " instead of a"
                    + " dictionary - it was skipped");
            return;
        }
        int objNum = objNumOf(node);
        if (objNum >= 0 && !visited.add("p" + objNum)) {
            notes.add("Page object " + objNum + " appears twice in the page tree - the second"
                    + " occurrence was skipped (loop guard)");
            return;
        }
        if (PdfName.dictHas(dict, "Type", "Page")) {
            List<Integer> contents = contentObjects(dict.get("Contents"), notes);
            result.add(new Page(result.size() + 1, objNum, contents));
            return;
        }
        Object kids = dict.get("Kids");
        if (kids == null) {
            // A /Type /Page without /Kids marker: treat it as a page when it has content.
            List<Integer> contents = contentObjects(dict.get("Contents"), notes);
            if (!contents.isEmpty()) {
                result.add(new Page(result.size() + 1, objNum, contents));
            } else {
                notes.add("A page-tree node (object " + objNum + ") has neither /Type /Page nor"
                        + " /Kids - it was skipped");
            }
            return;
        }
        if (!(kids instanceof List<?> kidList)) {
            notes.add("/Kids of page object " + objNum + " is not an array - the subtree was skipped");
            return;
        }
        for (Object kid : kidList) {
            if (result.size() >= maxPages) {
                // One sentence for the cause, not one per remaining node: a page tree with
                // several /Pages levels would otherwise repeat it for every branch it skips. A
                // single-page read is not a listing - for it, maxPages never bound anything, and a
                // "raise maxPages" note would describe an option the call never used.
                if (noteLimit && visited.add(PAGE_LIMIT_NOTED)) {
                    notes.add("Stopped after " + maxPages
                            + " page(s) (raise maxPages to see more)");
                }
                return;
            }
            collectPages(kid, depth + 1, maxPages, result, notes, visited, noteLimit);
        }
    }

    private int objNumOf(Object node) {
        return node instanceof PdfReference reference ? reference.objNum() : -1;
    }

    /**
     * Collects the object numbers behind {@code /Contents} (a reference or an array of them).
     */
    private List<Integer> contentObjects(Object contentsValue, List<String> notes) {
        List<Integer> objNums = new ArrayList<>();
        if (contentsValue == null) {
            return objNums;
        }
        if (contentsValue instanceof PdfReference reference) {
            objNums.add(reference.objNum());
            return objNums;
        }
        if (contentsValue instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof PdfReference reference) {
                    objNums.add(reference.objNum());
                } else {
                    notes.add("A /Contents item is " + PdfParser.kindOf(item)
                            + " instead of a reference - it was skipped");
                }
            }
            return objNums;
        }
        notes.add("/Contents is " + PdfParser.kindOf(contentsValue)
                + " instead of a stream reference or an array of them");
        return objNums;
    }

    /**
     * Content streams per page, with metadata and an operator histogram (text read through the
     * page's fonts, the way {@code pdf.text()} reads it).
     * @param maxPages page limit
     * @param includeText whether a (possibly truncated) text extract is added
     * @param maxTextChars maximum characters of that text extract
     * @return one entry per page
     */
    public List<Map<String, Object>> contentStreams(int maxPages, boolean includeText,
                                                   int maxTextChars) {
        return contentStreams(maxPages, includeText, maxTextChars, true);
    }

    /**
     * Content streams per page.
     * @param maxPages page limit
     * @param includeText whether a (possibly truncated) text extract is added
     * @param maxTextChars maximum characters of that text extract
     * @param useFonts whether the text is read through the font each {@code Tf} names (false: the
     *                 document convention, the documented raw view)
     * @return one entry per page
     */
    public List<Map<String, Object>> contentStreams(int maxPages, boolean includeText,
                                                   int maxTextChars, boolean useFonts) {
        List<String> notes = new ArrayList<>();
        List<Page> pages = pages(maxPages, notes);
        List<Map<String, Object>> result = new ArrayList<>();
        PdfFontScope.Budget budget = new PdfFontScope.Budget(PdfFontScope.MAX_TEXT_CODES);
        for (Page page : pages) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("page", (long) page.page());
            item.put("pageObjNum", (long) page.objNum());
            List<Object> contentObjNums = new ArrayList<>();
            for (Integer objNum : page.contentObjNums()) {
                contentObjNums.add((long) objNum);
            }
            item.put("contentObjNums", contentObjNums);
            // Like pdf.text(): a page explains itself. What its fonts have to say goes into the
            // page entry, so the trailing {page: null, notes} entry keeps holding only what the
            // *walk* could not do - and a healthy document stays one entry per page.
            List<String> pageNotes = new ArrayList<>();
            try {
                byte[] content = pageContent(page, pageNotes);
                item.put("decodedBytes", (long) content.length);
                Map<String, Object> analysis = PdfContentStream.analyse(content, 40);
                item.put("operators", analysis.get("histogram"));
                item.put("operatorCount", analysis.get("operatorCount"));
                PdfFontScope scope = useFonts
                        ? new PdfFontScope(this, page.objNum(), pageNotes, budget)
                        : null;
                if (includeText) {
                    String text = PdfContentStream.text(content, scope, pageNotes);
                    if (text.length() > maxTextChars) {
                        item.put("text", text.substring(0, maxTextChars));
                        item.put("textTruncated", true);
                        item.put("textChars", (long) text.length());
                    } else {
                        item.put("text", text);
                    }
                    if (scope != null && !scope.unmappedSample().isEmpty()) {
                        // The numbers belong to pdf.text(): one shape per statement (decision 21).
                        item.put("unmappedSample", scope.unmappedSample());
                    }
                }
                item.putAll(scope != null
                        ? PdfFont.inventory(this, page.objNum(), scope, pageNotes)
                        : PdfFont.inventory(this, page.objNum(), pageNotes));
            } catch (PdfException e) {
                item.put("error", e.getMessage());
            }
            if (!pageNotes.isEmpty()) {
                item.put("notes", new ArrayList<Object>(pageNotes));
            }
            result.add(item);
        }
        if (!notes.isEmpty()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("page", null);
            item.put("notes", new ArrayList<Object>(notes));
            result.add(item);
        }
        return result;
    }

    /**
     * Concatenates the (decoded) content streams of a page.
     * @param page the page
     * @param notes note/warning sink
     * @return the content bytes
     */
    public byte[] pageContent(Page page, List<String> notes) {
        if (page.contentObjNums().isEmpty()) {
            notes.add("Page " + page.page() + " (object " + page.objNum()
                    + ") has no /Contents");
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean first = true;
        for (Integer objNum : page.contentObjNums()) {
            byte[] decoded = stream(objNum).data();
            if (!first && out.size() > 0) {
                // ISO 32000-1: the streams of one page behave as if they were concatenated
                // with a line ending between each pair - but not appended after the last one,
                // that would change the byte count of a single-stream page.
                out.write('\n');
            }
            out.write(decoded, 0, decoded.length);
            first = false;
        }
        return out.toByteArray();
    }

    // =============================================================================================
    // The page's own frame, and where its text stands (increment D5)
    // =============================================================================================

    /**
     * The frame of one page: its visible box, its unit and its rotation, turned into the matrix that
     * leads from default user space into page space (PDF 1.7 4.2.1, S. 201; Table 3.27, S. 145-147).
     * <p>
     * {@code MediaBox}, {@code CropBox} and {@code Rotate} are looked up the way the page tree
     * resolves them — inheritable, so a {@code /Pages} node may carry them (3.6.2, S. 149).
     * {@code UserUnit} is <em>not</em> in that table's inheritable column, so it is read from the page
     * itself. It is read as a <b>number</b>: this is the place the plan named when it wrote that the
     * first {@code placement()} page has to read the entry for what it is — a {@code UserUnit 2.5}
     * that quietly became 1 would make a finished matrix compute with an invented unit (plan,
     * "Zu verifizieren").
     * </p>
     *
     * @param pageObjNum the page object
     * @param notes      note/warning sink of the call (a page whose box cannot be read says so here)
     * @return the frame, or {@code null} when the page names no usable MediaBox; a frame with a
     *         {@link PdfPageSpace#defect()} is returned as it is, because "the rotation is not a
     *         multiple of 90" is a finding about the file
     */
    public PdfPageSpace pageSpace(int pageObjNum, List<String> notes) {
        Map<String, Object> dict = dictOf(pageObjNum);
        if (dict.isEmpty()) {
            notes.add("Object " + pageObjNum + " is not a dictionary, so it has no page frame");
            return null;
        }
        PdfRect media = pageBox(dict, "MediaBox", notes);
        if (media == null) {
            return null;
        }
        PdfRect crop = pageBox(dict, "CropBox", notes);
        Double userUnit = asNumber(dict.get("UserUnit"));
        Integer rotate = asInteger(inheritedAttribute(dict, "Rotate"));
        try {
            return PdfPageSpace.of(media, crop, userUnit, rotate);
        } catch (PdfException e) {
            notes.add("Page " + pageObjNum + " has no usable frame: " + e.getMessage());
            return null;
        }
    }

    /**
     * One page box as a rectangle in default user space (Table 3.27, S. 145: the boxes "are
     * expressed in default user space units"). Anything the file writes where a box should be stays a
     * note instead of becoming a guess.
     */
    private PdfRect pageBox(Map<String, Object> pageDict, String key, List<String> notes) {
        Object value = inheritedAttribute(pageDict, key);
        if (value == null) {
            if ("MediaBox".equals(key)) {
                notes.add("The page names no MediaBox, and it is the one box a page dictionary must"
                        + " have (Table 3.27, S. 145) - there is no page space without it");
            }
            return null;
        }
        if (!(value instanceof List<?> list)) {
            notes.add("/" + key + " is " + PdfParser.kindOf(value) + " instead of an array of four"
                    + " numbers - the box is not read");
            return null;
        }
        try {
            return PdfRect.fromArray((List<?>) list, PdfSpace.DEFAULT_USER);
        } catch (PdfException e) {
            notes.add("/" + key + " cannot be read as a rectangle: " + e.getMessage());
            return null;
        }
    }

    /** The value as a number (an integer stays an integer, 2.5 stays 2.5), or null. */
    private static Double asNumber(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    /** The value as an integer operand, or null (a real with a fraction is not an integer). */
    private static Integer asInteger(Object value) {
        if (value instanceof Long number) {
            return Math.toIntExact(number);
        }
        if (value instanceof Integer number) {
            return number;
        }
        if (value instanceof Double real && real == Math.rint(real) && !real.isInfinite()
                && Math.abs(real) <= Integer.MAX_VALUE) {
            return (int) (long) real.doubleValue();
        }
        return null;
    }

    /**
     * What {@code pdf.placement()} asks for one page read. The per-stream choices (which space, what
     * to collect, how much) live in {@link PlacementSink.Options}; this is the page-level part.
     *
     * @param maxPages        how far to walk when no page was named
     * @param useFonts        whether the shown text is read through its font (false: document convention)
     * @param bbox            a box in <b>page space</b> that items must reach into, or null
     * @param dpi             the caller's resolution, which only buys {@code frame.deviceScale}
     * @param stateMode       which view of the graphics state an item carries: {@code 'key'},
     *                        {@code 'full'} or {@code 'colour'} ({@link PlacementSink#STATE_VALUES})
     * @param glyphs          whether every code of an operand is listed with its width
     * @param maxItems        items per page read (0 = {@link PlacementSink#DEFAULT_MAX_ITEMS})
     * @param what            the kinds to collect, as {@code what} wrote them ({@code 'clips'},
     *                        {@code 'paths'}, …); null or empty means {@code ['text']}
     * @param includeXObjects {@code {includeXObjects: true}}: walk into a form a {@code Do} names,
     *                        instead of only naming it
     * @param drawing         {@code {drawing: true}}: a path item also answers its segments (D8b)
     */
    public record Placement(int maxPages, boolean useFonts, PdfRect bbox, Double dpi,
                            String stateMode, boolean glyphs, int maxItems, List<String> what,
                            boolean includeXObjects, boolean drawing) {

        /** The plain ask: every page up to {@code maxPages}, text through its fonts. */
        public static Placement of(int maxPages) {
            return new Placement(maxPages, true, null, null, PlacementSink.STATE_KEY, false,
                    PlacementSink.DEFAULT_MAX_ITEMS, List.of("text"), false, false);
        }

        /** The walk of one named page. */
        public static Placement ofPage() {
            return of(1);
        }
    }

    /**
     * Where a form XObject's bytes and resources come from — the {@link XObjectOps.Source} seam,
     * filled by the document that can actually read objects. A descent reads the form's own decoded
     * stream; nothing here is executed, only walked by the same interpreter the page was walked with.
     */
    /**
     * The seam {@link XObjectOps} reads a form through: its decoded bytes, and its own
     * {@code /Resources} with the <b>page's</b> as fallback (3.7.2, S. 153 — not the calling
     * stream's). Nothing here is executed; a descent walks the form with the same interpreter that
     * walked the page.
     */
    private final class FormSource implements XObjectOps.Source {

        private final PdfResources pageResources;

        private FormSource(PdfResources pageResources) {
            this.pageResources = pageResources;
        }

        @Override
        public byte[] content(int objNum, List<String> notes) {
            try {
                return stream(objNum).data();
            } catch (PdfException e) {
                notes.add("object " + objNum + " cannot be read as a form's content ("
                        + e.getMessage() + ") - the Do is still named, its items are not");
                return null;
            }
        }

        @Override
        public PdfResources resourcesOf(int objNum, String label, List<String> notes) {
            // 3.7.2, S. 153: a form uses its own /Resources, and without one the PAGE's - not the
            // calling stream's. PdfResources.ofStream implements exactly that and says which.
            return PdfResources.ofStream(FormSource.this.thisObject(), dictOf(objNum), label,
                    pageResources, notes);
        }

        @Override
        public String resourcesFrom(int objNum, String label) {
            // Read once more, into a throwaway note sink: the answer a caller wants in the field is
            // "does this form carry its own names, or does it lean on the page" (3.7.2, S. 153), and
            // the sentence for the leaning is told where the form is actually walked, not twice.
            return PdfResources.ofStream(FormSource.this.thisObject(), dictOf(objNum), label,
                    pageResources, new ArrayList<>()).resourcesFrom();
        }

        @Override
        public Object deref(Object value) {
            try {
                return value instanceof PdfReference ? dereference(value) : value;
            } catch (PdfException e) {
                // A box the file cannot deliver: no value, and no invented one.
                return null;
            }
        }

        private PdfDocument thisObject() {
            return PdfDocument.this;
        }
    }

    /**
     * Where the text of a page stands — {@code pdf.placement()} (docs/js/pdf-content-plan.md,
     * Aufgabe 5). One walk per page over the same bytes {@link #pageContent(Page, List)} concatenates
     * for the text extract, with the widths of the page's fonts available to it, which is what lets a
     * showing operator's displacement be <em>computed</em> (5.3.3, S. 410) instead of guessed at.
     * <p>
     * The item offsets are offsets in that concatenated content. For the common single-stream page
     * that is the same number {@code pdf.operations(path, objNum)} prints — the traceability the plan
     * asks for — and a page of several streams says so in a note rather than letting a caller compare
     * two offset spaces unknowingly.
     * </p>
     *
     * @param pageIndex 1-based page number, or {@code null} to walk up to {@link Placement#maxPages()}
     * @param placement what to collect and how
     * @return {@code {pages: [{page, pageObjNum, frame, walk, items, summary, diagnostics,
     *         mapping?, notes?}], pageCount, notes?}}
     */
    public Map<String, Object> placement(Integer pageIndex, Placement placement) {
        Placement options = placement == null ? Placement.of(DEFAULT_MAX_PAGES) : placement;
        List<String> notes = new ArrayList<>();
        List<Page> pages = pagesForRead(pageIndex, options.maxPages(), notes);
        List<Map<String, Object>> answers = new ArrayList<>();
        PdfFontScope.Budget budget = new PdfFontScope.Budget(PdfFontScope.MAX_TEXT_CODES);
        for (Page page : pages) {
            if (pageIndex != null && page.page() != pageIndex) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("page", (long) page.page());
            item.put("pageObjNum", (long) page.objNum());
            List<String> pageNotes = new ArrayList<>();
            try {
                byte[] content = pageContent(page, pageNotes);
                if (page.contentObjNums().size() > 1) {
                    pageNotes.add("The offsets are offsets in the " + page.contentObjNums().size()
                            + " content streams of this page concatenated (the way the text extract"
                            + " reads them), not in one stream object");
                }
                PdfPageSpace frame = pageSpace(page.objNum(), pageNotes);
                // The scope answers for the widths even in the raw view: a width is dictionary data,
                // not a mapping decision. Only the *text* of an item honours useFonts.
                PdfFontScope scope = new PdfFontScope(this, page.objNum(), pageNotes,
                        PdfFontScope.DEFAULT_MAX_UNMAPPED, budget);
                // One resolver per page: the walk needs it for `gs`, the sink needs it for `Do`, and
                // a descent needs it as the fallback a form without /Resources falls back to (3.7.2).
                PdfResources resources = PdfResources.ofPage(this, page.objNum(), pageNotes);
                PlacementSink.Options sinkOptions = (frame != null && frame.isUsable()
                        ? PlacementSink.Options.of(frame, options.useFonts() ? scope : null)
                        : PlacementSink.Options.withoutPage(options.useFonts() ? scope : null))
                        .withWidths(scope)
                        .withState(options.stateMode())
                        .withGlyphs(options.glyphs())
                        .withBbox(options.bbox())
                        .withDpi(options.dpi())
                        .withMaxItems(options.maxItems())
                        .withKinds(PlacementSink.kindSet(options.what()))
                        .withXObjectDescent(options.includeXObjects())
                        .withXObjectSource(new FormSource(resources))
                        .withDrawing(options.drawing())
                        .withResources(resources);
                PlacementSink sink = new PlacementSink(sinkOptions);
                // The sink derives what its walk has to ask (page frame, widths, code list) - one
                // place knows the two, so `glyphs: true` cannot ask for a list the walk never built.
                PdfContentInterpreter.Options walkOptions = sink.options().walkOptions(
                        PdfContentInterpreter.Options.defaults().withResources(resources));
                PdfContentInterpreter.run(content, walkOptions, pageNotes, sink);

                item.put("frame", sink.frame());
                item.put("walk", sink.walkBlock());
                item.put("items", sink.items());
                item.put("summary", sink.summaryBlock());
                // D7: the walk's findings about this page's file, in the answer that asked for the
                // walk. Not in `notes` - those are this module's sentences about its own answer, and
                // D3 kept the two families apart so that an existing output would not move.
                item.put("diagnostics", sink.diagnosticsBlock());
                if (sink.isTruncated()) {
                    item.put("truncated", true);
                }
                if (options.useFonts()) {
                    item.put("mapping", scope.mapping());
                }
                List<String> joined = new ArrayList<>(sink.notes());
                joined.addAll(pageNotes);
                if (!joined.isEmpty()) {
                    item.put("notes", new ArrayList<Object>(joined));
                }
            } catch (PdfException e) {
                item.put("error", e.getMessage());
            }
            answers.add(item);
        }
        if (pageIndex != null && answers.isEmpty()) {
            throw new PdfException(pageNotFound(pageIndex, pages.size(), notes));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pages", answers);
        result.put("pageCount", (long) answers.size());
        if (!notes.isEmpty()) {
            result.put("notes", new ArrayList<Object>(notes));
        }
        return result;
    }

    /**
     * The mapping chain of one font, shared by everything this call does with that font.
     * <p>
     * A {@link PdfGlyphMapper} reads the font's {@code /ToUnicode} stream when its first code is
     * asked, so handing out one instance per object number is what keeps a page walk from parsing
     * the same map once per page. The note sink is the one of the call that built the chain: a
     * font's own sentence is therefore said once per call, at the page that used the font first -
     * the per-page numbers ({@code mapping}, {@code fonts[]}) stay on every page.
     * </p>
     * @param objNum object number of the font dictionary (null for an inline dictionary)
     * @param font the resolved font dictionary
     * @param notes note/warning sink of the call that builds the chain
     * @return the mapper - never null
     */
    public PdfGlyphMapper glyphMapper(Integer objNum, Map<String, Object> font,
                                      List<String> notes) {
        if (objNum == null) {
            // An inline dictionary has no address to be cached under; it is rare and cheap.
            return PdfGlyphMapper.of(this, null, font, notes);
        }
        return glyphMappers.computeIfAbsent(objNum,
                key -> PdfGlyphMapper.of(this, key, font, notes));
    }

    /**
     * Best-effort text of the document (see {@code pdf.help()} for the limits).
     * @param pageIndex 1-based page number, or {@code null} for all pages
     * @param maxPages page limit when extracting all pages
     * @return {@code {pages: [{page, objNum, text}], notes: [...]}}
     */
    public Map<String, Object> text(Integer pageIndex, int maxPages) {
        return text(pageIndex, maxPages, true, PdfFontScope.DEFAULT_MAX_UNMAPPED);
    }

    /**
     * Text of the document, with or without its fonts.
     * <p>
     * With {@code useFonts} every string is read through the font the {@code Tf} before it named
     * (the chain of {@link PdfGlyphMapper}: {@code /ToUnicode}, a UCS code, the descendant's map, a
     * glyph name - and nothing else), each page carries a {@code mapping} block that says what was
     * read and what stayed a code, and {@code fonts[]} answers {@code reliableText} from that
     * measurement instead of from a dictionary guess. Without it the pages read by the document
     * convention, exactly as this module has always read them - the documented raw view.
     * </p>
     * @param pageIndex 1-based page number, or {@code null} for all pages
     * @param maxPages page limit when extracting all pages
     * @param useFonts whether the strings are read through their font
     * @param maxUnmapped codes of a page named in {@code unmappedSample} (0 = no sample)
     * @return {@code {pages: [{page, objNum, text, wordGaps?, mapping?, unmappedSample?, fonts?}],
     *         pageCount, notes?}}
     */
    public Map<String, Object> text(Integer pageIndex, int maxPages, boolean useFonts,
                                    int maxUnmapped) {
        List<String> notes = new ArrayList<>();
        List<Page> pages = pagesForRead(pageIndex, maxPages, notes);
        List<Map<String, Object>> pageTexts = new ArrayList<>();
        PdfFontScope.Budget budget = new PdfFontScope.Budget(PdfFontScope.MAX_TEXT_CODES);
        for (Page page : pages) {
            if (pageIndex != null && page.page() != pageIndex) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("page", (long) page.page());
            item.put("objNum", (long) page.objNum());
            // A page explains itself: what the fonts have to say about this page lands in the page
            // entry, not in a document-wide list that loses track of which page said it.
            List<String> pageNotes = new ArrayList<>();
            try {
                byte[] content = pageContent(page, pageNotes);
                if (useFonts) {
                    PdfFontScope scope = new PdfFontScope(this, page.objNum(), pageNotes,
                            maxUnmapped, budget);
                    TextSink extract = PdfContentStream.textSink(content, scope, null, pageNotes);
                    item.put("text", extract.text());
                    putWordGaps(item, extract);
                    item.put("mapping", scope.mapping());
                    item.put("unmappedSample", scope.unmappedSample());
                    if (scope.sampleTruncated()) {
                        item.put("unmappedTruncated", true);
                    }
                    if (scope.cut()) {
                        item.put("truncated", true);
                    }
                    item.putAll(PdfFont.inventory(this, page.objNum(), scope, pageNotes));
                } else {
                    TextSink extract = PdfContentStream.textSink(content, null, null, pageNotes);
                    item.put("text", extract.text());
                    putWordGaps(item, extract);
                    item.putAll(PdfFont.inventory(this, page.objNum(), pageNotes));
                }
            } catch (PdfException e) {
                item.put("error", e.getMessage());
            }
            if (!pageNotes.isEmpty()) {
                item.put("notes", new ArrayList<Object>(pageNotes));
            }
            pageTexts.add(item);
        }
        if (pageIndex != null && pageTexts.isEmpty()) {
            throw new PdfException(pageNotFound(pageIndex, pages.size(), notes));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pages", pageTexts);
        result.put("pageCount", (long) pages.size());
        if (!notes.isEmpty()) {
            result.put("notes", new ArrayList<Object>(notes));
        }
        return result;
    }

    /**
     * The page's {@code wordGaps} block, when its spaces came from the threshold at all
     * (increment D7). Absent for a page no {@code TJ} array touched and for a page whose every
     * ignored number was positive — the extract of an ordinary page therefore still has exactly the
     * fields it always had, and a page whose word spaces depend on a rule of thumb says which rule
     * and how much of the operand it could not see.
     *
     * @param item    the page entry being built
     * @param extract the finished text sink of that page
     */
    private static void putWordGaps(Map<String, Object> item, TextSink extract) {
        Map<String, Object> block = extract.wordGaps().block();
        if (block != null) {
            item.put("wordGaps", block);
        }
    }

    /**
     * Looks up an inherited page attribute (PDF resolves it up the parent chain).
     * @param pageDict the page dictionary
     * @param key attribute name, e.g. {@code Resources}
     * @return the value or {@code null}
     */
    public Object inheritedAttribute(Map<?, ?> pageDict, String key) {
        Map<?, ?> current = pageDict;
        int hops = 0;
        while (current != null && hops++ < MAX_PAGE_DEPTH) {
            Object value = current.get(key);
            if (value != null) {
                return dereference(value);
            }
            Object parent = current.get("Parent");
            if (parent == null) {
                return null;
            }
            Object resolvedParent = dereference(parent);
            current = resolvedParent instanceof Map<?, ?> map ? map : null;
        }
        return null;
    }

    /**
     * Reads a page dictionary (for the content-stream helpers).
     * @param objNum page object number
     * @return the dictionary, or an empty map when the object is not a dictionary
     */
    public Map<String, Object> dictOf(int objNum) {
        try {
            Object value = objectValue(objNum);
            if (value instanceof PdfStream stream) {
                return stream.dict();
            }
            if (value instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) map;
                return typed;
            }
        } catch (PdfException e) {
            // fall through
        }
        return Map.of();
    }

    /**
     * Summary of the document: the counts that help to decide where to look next.
     * @return summary map
     */
    public Map<String, Object> summary() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("path", displayName);
        map.put("bytes", (long) data.length);
        map.put("fileVersion", structure.version() != null ? structure.version() : "unknown");
        map.put("objects", (long) crossReference().objectCount());
        map.put("updateSections", (long) crossReference().sections().size());
        map.put("recoveredByScan", crossReference().salvaged());
        int streamCount = 0;
        for (Integer objNum : crossReference().objectNumbers()) {
            try {
                if (objectValue(objNum) instanceof PdfStream) {
                    streamCount++;
                }
            } catch (PdfException e) {
                // Listed with its error message by objects().
            }
        }
        map.put("streams", (long) streamCount);
        List<String> notes = new ArrayList<>();
        List<Page> pages = pages(DEFAULT_MAX_PAGES, notes);
        map.put("pages", (long) pages.size());
        if (!notes.isEmpty()) {
            map.put("notes", new ArrayList<Object>(notes));
        }
        if (!structure.warnings().isEmpty()) {
            map.put("structureWarnings", new ArrayList<Object>(structure.warnings()));
        }
        return map;
    }
}
