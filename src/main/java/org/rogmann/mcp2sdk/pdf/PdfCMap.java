package org.rogmann.mcp2sdk.pdf;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads a PDF <em>CMap</em> stream (ISO 32000-1, 9.7.5.2): the {@code /ToUnicode} map of a font
 * or the {@code /CMapFile} of an encoding dictionary.
 * <p>
 * A CMap is written in PostScript syntax, and this class <em>tokenizes</em> it - nothing is
 * executed. There is no {@code findresource}, no {@code CFontResource} and no dictionary stack:
 * the reader knows the markers of the specification ({@code begincodespacerange},
 * {@code beginbfchar}, {@code beginbfrange}, {@code begincmap}) plus the ones real producers
 * write and the specification does not define ({@code beginwmode}, the
 * {@code begincmapdef}/{@code enddeterministicmapdef} macros, {@code usecmap}); those are
 * counted and named in {@link #warnings()}, never interpreted. Everything else PostScript
 * ({@code 12 dict begin}, {@code def}, {@code dup}, {@code pop}) is noise and skipped - as is a
 * bare word with a capital letter, which is a name used bare ({@code CMapName} in the tail
 * {@code CMapName currentdict /CMap defineresource pop}) rather than an operator.
 * </p>
 * <p>
 * What survives is a code&nbsp;→&nbsp;Unicode map: hex code (uppercase, no brackets) → code
 * points. A code is kept as hex rather than as a number because its byte width belongs to the
 * fact - a one-byte {@code 0041} and a two-byte {@code 0041} are different codes. The map is
 * built completely up to {@link #MAX_ENTRIES}; {@link #toMap(int, List)} is only a view, so the
 * caller who lists ten entries and the caller who asks for one code know the same amount. A code
 * defined twice keeps its <em>first</em> definition and is counted in {@link #conflicts()}:
 * "last one wins" would let a trailing region silently rewrite what is above it.
 * </p>
 */
public final class PdfCMap {

    /** Upper bound of mapping entries built from one CMap (the map, not the listing). */
    public static final int MAX_ENTRIES = 200_000;

    /** Default number of entries a listing shows. */
    public static final int DEFAULT_LIMIT = 200;

    /** Upper bound for a code sample list. */
    public static final int MAX_CODE_ITEMS = 2_000;

    /** Upper bound of tokens read from one CMap stream. */
    public static final int MAX_TOKENS = 4_000_000;

    /** Maximum number of distinct unknown operators named in the listing. */
    private static final int MAX_LISTED_UNKNOWN_OPS = 10;

    /** How many conflicting codes are named individually. */
    private static final int MAX_LISTED_CONFLICTS = 3;

    /** Reader mode: outside a block. */
    private static final int OUTSIDE = 0;
    /** Reader mode: inside {@code begincodespacerange}. */
    private static final int CODE_SPACE = 1;
    /** Reader mode: inside {@code beginbfchar}. */
    private static final int BF_CHAR = 2;
    /** Reader mode: inside {@code beginbfrange}. */
    private static final int BF_RANGE = 3;

    /**
     * One code space range: it says how many bytes one code occupies.
     *
     * @param bytes    bytes per code (the width of {@code from})
     * @param from     first code of the range
     * @param to       last code of the range
     * @param notation how the range was written: {@code hex} or {@code literal}
     */
    public record CodeSpace(int bytes, long from, long to, String notation) {
    }

    private final Map<String, int[]> mapping = new TreeMap<>(PdfCMap::compareCodes);
    private final Map<String, String> sources = new HashMap<>();
    private final List<CodeSpace> codeSpaces = new ArrayList<>();
    private final List<String> useCMaps = new ArrayList<>();
    private final List<String> unknownOps = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    private String cmapName;
    private Long cmapType;
    private Double cmapVersion;
    private Long wMode;
    private String registry;
    private String ordering;
    private Long supplement;

    private int bfcharPairs;
    private int bfrangeRegions;
    private int conflicts;
    private int multiCharTargets;
    private int wModeBlocks;
    private boolean truncated;
    private int codeBytes = -1;

    private PdfCMap() {
        // built by parse()
    }

    // ===================================================================
    // Parsing
    // ===================================================================

    /**
     * Reads a CMap stream.
     * @param data the decoded stream bytes
     * @param maxEntries entry budget of the map (values above {@link #MAX_ENTRIES} are clamped;
     *                   0 means the default budget)
     * @return the parsed CMap - never null; an unreadable stream yields an empty map plus a note
     */
    public static PdfCMap parse(byte[] data, int maxEntries) {
        int budget = maxEntries <= 0 ? MAX_ENTRIES : Math.min(maxEntries, MAX_ENTRIES);
        PdfCMap cmap = new PdfCMap();
        cmap.read(data == null ? new byte[0] : data, budget);
        if (cmap.codeSpaces().isEmpty() && !cmap.isEmpty()) {
            // A map without a code space cannot be cut into codes by anyone else.
            cmap.warnings.add("This CMap declares no code space range: its entries are readable,"
                    + " but the byte width of a code is not known");
        }
        return cmap;
    }

    private void read(byte[] data, int budget) {
        PdfParser parser = new PdfParser(data);
        List<Object> pending = new ArrayList<>();
        int mode = OUTSIDE;
        // A WMode block re-defines codes for vertical writing. Those are not the horizontal
        // mapping a text extraction needs, so its entries stay out of the map - but its markers
        // are tracked, because otherwise the blocks behind it would be read as "outside".
        int wModeDepth = 0;
        int tokens = 0;
        while (true) {
            parser.skipSpaceAndComments();
            if (parser.position() >= data.length) {
                break;
            }
            if (++tokens > MAX_TOKENS) {
                warnings.add("CMap stopped after " + MAX_TOKENS + " tokens (MAX_TOKENS)");
                break;
            }
            int start = parser.position();
            String word = wordAt(data, start);
            if (isKeyword(word)) {
                parser.seek(start + word.length());
                if (hasUpperCase(word)) {
                    // A bare word that contains a capital letter is a *name used bare*, not an
                    // operator: PostScript keys are CamelCase ({@code CMapName},
                    // {@code CIDSystemInfo}, {@code WMode}) while the operators of this syntax are
                    // all lowercase. The tail every producer writes -
                    // {@code CMapName currentdict /CMap defineresource pop} - would otherwise
                    // raise an "unknown operator" note in every well-formed CMap.
                    continue;
                }
                String action = apply(word, pending, wModeDepth);
                switch (action) {
                    case "codespace" -> mode = CODE_SPACE;
                    case "bfchar" -> mode = BF_CHAR;
                    case "bfrange" -> mode = BF_RANGE;
                    case "end" -> mode = OUTSIDE;
                    case "wmode-begin" -> {
                        wModeDepth++;
                        wModeBlocks++;
                        mode = OUTSIDE;
                    }
                    case "wmode-end" -> {
                        if (wModeDepth > 0) {
                            wModeDepth--;
                        }
                        mode = OUTSIDE;
                    }
                    default -> { /* nothing structural - apply() has reported what it had to */ }
                }
                pending.clear();
                continue;
            }
            Object value;
            try {
                value = parser.parseValue();
            } catch (PdfException e) {
                warnings.add("CMap could not be read beyond offset " + start + ": "
                        + e.getMessage());
                break;
            }
            if (parser.position() <= start) {
                parser.seek(start + 1);
                warnings.add("Skipped an unreadable byte at offset " + start);
                continue;
            }
            pending.add(value);
            if (pending.size() > 64) {
                // Operands without end (a stream of junk): forget instead of growing forever.
                pending.remove(0);
            }
            if (wModeDepth == 0) {
                consume(mode, pending, budget);
            }
        }
        if (mode != OUTSIDE) {
            warnings.add("The CMap ends inside a " + describeMode(mode)
                    + " block (no matching end marker)");
        }
        if (truncated) {
            warnings.add("CMap stopped after " + budget + " entries (maxCmapEntries): the map is"
                    + " partial");
        }
        computeCodeBytes();
    }

    /**
     * Consumes the operands in front of a keyword and reports what happens to the block mode.
     * @return one of {@code codespace}, {@code bfchar}, {@code bfrange}, {@code end},
     *         {@code wmode-begin}, {@code wmode-end} or {@code ""}
     */
    private String apply(String word, List<Object> pending, int wModeDepth) {
        switch (word) {
            case "begincodespacerange" -> {
                return "codespace";
            }
            case "begincodemaprange" -> {
                // Encoding dictionaries spell their code maps this way (9.7.5.1); the entries are
                // the same shape, so they are read as code spaces and named.
                nameUnknown(word);
                return "codespace";
            }
            case "beginbfchar" -> {
                return wModeDepth > 0 ? "" : "bfchar";
            }
            case "beginbfrange" -> {
                return wModeDepth > 0 ? "" : "bfrange";
            }
            case "endcodespacerange", "endcodemaprange", "endbfchar", "endbfrange" -> {
                return "end";
            }
            case "beginwmode" -> {
                return "wmode-begin";
            }
            case "endwmode" -> {
                return "wmode-end";
            }
            case "begincmap", "endcmap" -> {
                return "";
            }
            case "begincmapdef", "begincmapresourcemark", "begincodemapdef",
                 "enddeterministicmapdef", "endcmapresourcemark" -> {
                // CidGen/Vm macros: named, not interpreted.
                nameUnknown(word);
                return "";
            }
            case "usecmap" -> {
                // PostScript CMap inheritance. The names in front of the operator say which CMaps
                // this one continues; their tables are not built in, so the names are reported
                // and nothing else happens.
                for (Object operand : pending) {
                    if (operand instanceof PdfName name) {
                        useCMaps.add(name.toString());
                    }
                }
                if (useCMaps.isEmpty()) {
                    nameUnknown(word);
                }
                return "";
            }
            case "def" -> {
                headerField(pending);
                return "";
            }
            // PostScript noise that carries no information for a reader.
            case "dict", "begin", "end", "dup", "pop", "defineresource", "findresource",
                 "currentglobal", "setglobal", "currentdict", "globaldict", "systemdict",
                 "userdict", "exch", "index", "put", "get", "store", "load", "if", "ifelse",
                 "for", "repeat", "exec", "mark", "cleartomark", "counttomark", "not", "and",
                 "or", "eq", "ne", "length", "cvx", "resourcestring", "ProcSet" -> {
                return "";
            }
            default -> {
                nameUnknown(word);
                return "";
            }
        }
    }

    /**
     * Picks the header fields out of an operand list ({@code /CMapName /X def},
     * {@code /CIDSystemInfo << … >> def}).
     */
    private void headerField(List<Object> pending) {
        if (pending.size() < 2 || !(pending.get(pending.size() - 2) instanceof PdfName key)) {
            return;
        }
        Object value = pending.get(pending.size() - 1);
        switch (key.value()) {
            case "CMapName" -> {
                if (value instanceof PdfName name) {
                    cmapName = name.toString();
                }
            }
            case "CMapType" -> {
                if (value instanceof Long number) {
                    cmapType = number;
                }
            }
            case "CMapVersion" -> cmapVersion = asDouble(value);
            // The writing mode this CMap asks for: 0 horizontal, 1 vertical (PDF 1.7 5.6.4). It is
            // the entry that decides whether a CIDFont's /W2 and /DW2 metrics are the ones to use,
            // so it is worth reading even though the module never paints a glyph. A
            // beginwmode/endwmode block is a different thing (a vertical mapping inside a
            // horizontal CMap) and is counted separately in wModeBlocks.
            case "WMode" -> {
                if (value instanceof Long number) {
                    wMode = number;
                }
            }
            case "CIDSystemInfo" -> {
                if (value instanceof Map<?, ?> dict) {
                    registry = textOrName(dict, "Registry");
                    ordering = textOrName(dict, "Ordering");
                    supplement = dict.get("Supplement") instanceof Long number ? number : null;
                }
            }
            default -> { /* some other definition - not ours */ }
        }
    }

    /**
     * Reads a {@code /CIDSystemInfo} entry: the specification writes Registry and Ordering as byte
     * strings, real producers sometimes as names - both are the same statement here.
     */
    private static String textOrName(Map<?, ?> dict, String key) {
        Object raw = dict.get(key);
        if (raw instanceof PdfString string) {
            return string.text();
        }
        if (raw instanceof PdfName name) {
            return name.value();
        }
        return null;
    }

    /**
     * Consumes whatever operands a full block entry has collected.
     */
    private void consume(int mode, List<Object> pending, int budget) {
        switch (mode) {
            case CODE_SPACE -> {
                if (pending.size() >= 2 && pending.get(pending.size() - 2) instanceof PdfString from
                        && pending.get(pending.size() - 1) instanceof PdfString to) {
                    pending.remove(pending.size() - 1);
                    pending.remove(pending.size() - 1);
                    addCodeSpace(from, to);
                }
            }
            case BF_CHAR -> {
                if (pending.size() >= 2 && pending.get(pending.size() - 2) instanceof PdfString src
                        && pending.get(pending.size() - 1) instanceof PdfString dst) {
                    pending.remove(pending.size() - 1);
                    pending.remove(pending.size() - 1);
                    bfcharPairs++;
                    putCode(src.bytes(), codePoints(dst), "bfchar", budget);
                }
            }
            case BF_RANGE -> {
                if (pending.size() >= 3 && pending.get(pending.size() - 3) instanceof PdfString lo
                        && pending.get(pending.size() - 2) instanceof PdfString hi
                        && (pending.get(pending.size() - 1) instanceof PdfString
                            || pending.get(pending.size() - 1) instanceof List<?>)) {
                    Object dst = pending.remove(pending.size() - 1);
                    pending.remove(pending.size() - 1);
                    pending.remove(pending.size() - 1);
                    bfrangeRegions++;
                    addRange(lo, hi, dst, budget);
                }
            }
            default -> { /* outside a block: operands belong to a keyword */ }
        }
    }

    private void addCodeSpace(PdfString from, PdfString to) {
        byte[] fromBytes = from.bytes();
        byte[] toBytes = to.bytes();
        if (fromBytes.length != toBytes.length) {
            warnings.add("A code space range has different widths (" + fromBytes.length
                    + " and " + toBytes.length + " bytes) - read with the width of its start");
        }
        if (fromBytes.length == 0 || fromBytes.length > 4) {
            warnings.add("A code space range of " + fromBytes.length
                    + " byte(s) is out of reach and was skipped");
            return;
        }
        codeSpaces.add(new CodeSpace(fromBytes.length, value(fromBytes), value(toBytes),
                from.isHex() ? "hex" : "literal"));
    }

    private void addRange(PdfString lo, PdfString hi, Object dst, int budget) {
        byte[] loBytes = lo.bytes();
        byte[] hiBytes = hi.bytes();
        if (loBytes.length == 0 || loBytes.length > 4 || hiBytes.length != loBytes.length) {
            warnings.add("A bfrange with widths " + loBytes.length + "/" + hiBytes.length
                    + " byte(s) was skipped");
            return;
        }
        long from = value(loBytes);
        long to = value(hiBytes);
        if (to < from) {
            warnings.add("A bfrange runs backwards (from " + hex(loBytes) + " to " + hex(hiBytes)
                    + ") and was skipped");
            return;
        }
        if (dst instanceof List<?> array) {
            int index = 0;
            for (Object item : array) {
                if (!(item instanceof PdfString target)) {
                    warnings.add("A bfrange target array holds " + PdfParser.kindOf(item)
                            + " instead of a string - the rest of the array was skipped");
                    break;
                }
                if (!putCode(bytesOf(from + index, loBytes.length), codePoints(target),
                        "bfrange-array", budget)) {
                    return;
                }
                index++;
            }
            return;
        }
        // The dstStart form: the destination is the first character of an ascending run, and a
        // character code in a CMap is UTF-16BE - two bytes. Anything else is not continued by
        // counting, because guessing the width would invent a mapping.
        byte[] target = ((PdfString) dst).bytes();
        if (target.length != 2) {
            warnings.add("A bfrange destination of " + target.length
                    + " byte(s) is not a two-byte character code - the region was skipped");
            return;
        }
        long base = value(target);
        for (long code = from; code <= to; code++) {
            if (!putCode(bytesOf(code, loBytes.length),
                    new int[]{(int) (base + (code - from))}, "bfrange", budget)) {
                return; // entry budget reached
            }
        }
    }

    /**
     * Adds one mapping entry; the first definition of a code wins.
     * @return false when the entry budget is reached
     */
    private boolean putCode(byte[] code, int[] codePoints, String source, int budget) {
        String key = hex(code);
        if (mapping.containsKey(key)) {
            conflicts++;
            if (conflicts <= MAX_LISTED_CONFLICTS
                    && !Arrays.equals(mapping.get(key), codePoints)) {
                warnings.add("Code <" + key + "> is defined twice with different targets - the"
                        + " first definition (" + sources.get(key) + ") wins");
            }
            return true;
        }
        if (mapping.size() >= budget) {
            truncated = true;
            return false;
        }
        if (codePoints.length == 0) {
            warnings.add("Code <" + key + "> maps to nothing");
        }
        mapping.put(key, codePoints);
        sources.put(key, source);
        if (codePoints.length > 1) {
            // A ligature code (<0B> -> "ff") or a surrogate pair: one code, several characters.
            multiCharTargets++;
        }
        return true;
    }

    private void nameUnknown(String word) {
        if (unknownOps.size() < MAX_LISTED_UNKNOWN_OPS && !unknownOps.contains(word)) {
            unknownOps.add(word);
        }
    }

    private void computeCodeBytes() {
        int narrowest = -1;
        for (CodeSpace space : codeSpaces) {
            if (narrowest < 0 || space.bytes() < narrowest) {
                narrowest = space.bytes();
            }
        }
        codeBytes = narrowest;
    }

    // ===================================================================
    // Facts
    // ===================================================================

    /** The {@code /CMapName} of the stream (with slash), or null. */
    public String cmapName() {
        return cmapName;
    }

    /** The {@code /CMapType} (usually 2), or null. */
    public Long cmapType() {
        return cmapType;
    }

    /** The {@code /CMapVersion}, or null. */
    public Double cmapVersion() {
        return cmapVersion;
    }

    /**
     * The {@code /WMode} the CMap declares (0 horizontal, 1 vertical), or null when it names none.
     * A CMap that ends in {@code -V} among the predefined names says the same thing without a
     * stream; a {@code beginwmode} block is not this entry and never sets it.
     */
    public Long wMode() {
        return wMode;
    }

    /** {@code /CIDSystemInfo} of the stream, or null when it declares none. */
    public Map<String, Object> cidSystemInfo() {
        if (registry == null && ordering == null && supplement == null) {
            return null;
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("registry", registry);
        info.put("ordering", ordering);
        info.put("supplement", supplement);
        return info;
    }

    /** The code space ranges in document order (empty when the stream declares none). */
    public List<CodeSpace> codeSpaces() {
        return List.copyOf(codeSpaces);
    }

    /** Bytes per code, from the narrowest code space range; -1 when there is none. */
    public int codeBytes() {
        return codeBytes;
    }

    /** Names of the CMaps this one inherits from ({@code usecmap}) - tables not built in. */
    public List<String> useCMaps() {
        return List.copyOf(useCMaps);
    }

    /** Number of {@code bfchar} pairs read. */
    public int bfcharPairs() {
        return bfcharPairs;
    }

    /** Number of {@code bfrange} regions read. */
    public int bfrangeRegions() {
        return bfrangeRegions;
    }

    /** Codes defined more than once (the first definition is the one that stayed). */
    public int conflicts() {
        return conflicts;
    }

    /**
     * Entries whose target is more than one character: a ligature code ({@code <0B> → "ff"}) or a
     * code point beyond U+FFFF. {@code pdf.font()} reports this count because it is the number of
     * codes a reader must not treat as one character.
     * @return entries with several code points
     */
    public int multiCharTargets() {
        return multiCharTargets;
    }

    /** Number of {@code beginwmode} blocks (vertical mappings, kept out of the map). */
    public int wModeBlocks() {
        return wModeBlocks;
    }

    /** True when the entry budget stopped the map. */
    public boolean truncated() {
        return truncated;
    }

    /** Operators that were counted and reported instead of interpreted. */
    public List<String> unsupportedOps() {
        return List.copyOf(unknownOps);
    }

    /** What the reader has to say: malformed places, skipped regions, the budget cut. */
    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    /** Entries of the map (the whole map, not the listing). */
    public int entryCount() {
        return mapping.size();
    }

    /** Whether the map has no entry at all. */
    public boolean isEmpty() {
        return mapping.isEmpty();
    }

    /**
     * Looks one code up.
     * @param codeHex the code as hex, brackets and spaces are tolerated ({@code "0041"})
     * @return the code points, or null when the code is not mapped
     */
    public int[] lookup(String codeHex) {
        return codeHex == null ? null : mapping.get(normalise(codeHex));
    }

    /** How one code got its mapping: {@code bfchar}, {@code bfrange} or {@code bfrange-array}. */
    public String sourceOf(String codeHex) {
        return codeHex == null ? null : sources.get(normalise(codeHex));
    }

    // ===================================================================
    // Listing
    // ===================================================================

    /**
     * The listing view of the map.
     * @param limit entries shown (0 = no limit) - the map itself is always complete
     * @param codes requested code samples, or null for "the first entries in code order"
     * @return a map ready for the JS API
     */
    public Map<String, Object> toMap(int limit, List<String> codes) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (cmapName != null) {
            result.put("cmapName", cmapName);
        }
        result.put("cmapType", cmapType);
        if (cmapVersion != null) {
            result.put("cmapVersion", cmapVersion);
        }
        if (wMode != null) {
            result.put("wMode", wMode);
        }
        Map<String, Object> info = cidSystemInfo();
        if (info != null) {
            result.put("cidSystemInfo", info);
        }
        List<Map<String, Object>> spaces = new ArrayList<>();
        for (CodeSpace space : codeSpaces) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("from", hexOf(space.from(), space.bytes()));
            item.put("to", hexOf(space.to(), space.bytes()));
            item.put("bytes", (long) space.bytes());
            item.put("notation", space.notation());
            spaces.add(item);
        }
        result.put("codeSpaces", spaces);
        if (codeBytes > 0) {
            result.put("codeBytes", (long) codeBytes);
        }
        if (!useCMaps.isEmpty()) {
            result.put("useCmaps", new ArrayList<Object>(useCMaps));
        }
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("bfchars", (long) bfcharPairs);
        counts.put("bfranges", (long) bfrangeRegions);
        counts.put("mappedEntries", (long) mapping.size());
        counts.put("conflicts", (long) conflicts);
        counts.put("multiCharTargets", (long) multiCharTargets);
        counts.put("wModes", (long) wModeBlocks);
        counts.put("unsupportedOps", new ArrayList<Object>(unknownOps));
        result.put("counts", counts);

        List<Map<String, Object>> items = new ArrayList<>();
        boolean listingCut = false;
        if (codes != null) {
            for (String code : codes) {
                String key = requireCode(code);
                int[] found = mapping.get(key);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("code", key);
                item.put("unicode", found == null ? null : text(found));
                item.put("codePoints", codePointList(found));
                item.put("mapped", found != null);
                if (found != null) {
                    item.put("source", sources.get(key));
                    if (found.length > 1) {
                        item.put("multi", true);
                    }
                }
                items.add(item);
            }
        } else {
            for (Map.Entry<String, int[]> entry : mapping.entrySet()) {
                if (limit > 0 && items.size() >= limit) {
                    listingCut = true;
                    break;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("code", entry.getKey());
                item.put("unicode", text(entry.getValue()));
                item.put("codePoints", codePointList(entry.getValue()));
                item.put("source", sources.get(entry.getKey()));
                if (entry.getValue().length > 1) {
                    item.put("multi", true);
                }
                items.add(item);
            }
        }
        result.put("codes", items);
        result.put("shown", (long) items.size());
        if (listingCut || truncated) {
            result.put("truncated", true);
        }
        if (!warnings.isEmpty()) {
            result.put("warnings", new ArrayList<Object>(warnings));
        }
        return result;
    }

    /**
     * Validates one requested code against the width the code space says.
     * @param code the code as hex
     * @return the normalised uppercase hex
     * @throws PdfException when the width does not match - filling it up silently would answer a
     *                      question nobody asked
     */
    public String requireCode(String code) {
        String key = normalise(code);
        if (key.isEmpty()) {
            throw new PdfException("pdf.cmap: an empty code is not a code");
        }
        if (key.length() % 2 != 0) {
            throw new PdfException("pdf.cmap: code '" + code + "' is not a whole number of bytes");
        }
        if (codeBytes > 0 && key.length() / 2 != codeBytes) {
            throw new PdfException("pdf.cmap: code '" + code + "' is " + (key.length() / 2)
                    + " byte(s) but this CMap's code space says " + codeBytes
                    + " byte(s) per code - write codes in that width");
        }
        return key;
    }

    private static List<Object> codePointList(int[] codePoints) {
        List<Object> list = new ArrayList<>();
        if (codePoints != null) {
            for (int codePoint : codePoints) {
                list.add((long) codePoint);
            }
        }
        return list;
    }

    // ===================================================================
    // Bytes and hex
    // ===================================================================

    /**
     * Turns target bytes into code points. They are UTF-16BE code units (that is what the
     * specification writes into a bfchar destination), so a surrogate pair becomes one code point
     * and a four-byte target stays <em>two</em> characters unless it really is a pair - reading
     * four bytes as one UCS-4 value would turn {@code <00660066>} into a private-plane character
     * instead of the ligature "ff" it is.
     * @param string the target string of a bfchar or a bfrange-array entry
     * @return the code points (empty for an empty target)
     */
    static int[] codePoints(PdfString string) {
        byte[] raw = string.bytes();
        List<Integer> units = new ArrayList<>();
        for (int i = 0; i + 1 < raw.length; i += 2) {
            units.add(((raw[i] & 0xFF) << 8) | (raw[i + 1] & 0xFF));
        }
        if (raw.length % 2 != 0) {
            units.add(raw[raw.length - 1] & 0xFF);
        }
        List<Integer> codePoints = new ArrayList<>();
        for (int i = 0; i < units.size(); i++) {
            int unit = units.get(i);
            if (unit >= 0xD800 && unit <= 0xDBFF && i + 1 < units.size()
                    && units.get(i + 1) >= 0xDC00 && units.get(i + 1) <= 0xDFFF) {
                codePoints.add(Character.toCodePoint((char) unit,
                        (char) units.get(i + 1).intValue()));
                i++;
            } else {
                codePoints.add(unit);
            }
        }
        int[] out = new int[codePoints.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = codePoints.get(i);
        }
        return out;
    }

    /** Big-endian bytes of a code value in the given width. */
    static byte[] bytesOf(long value, int width) {
        byte[] out = new byte[width];
        for (int i = width - 1; i >= 0; i--) {
            out[i] = (byte) (value & 0xFF);
            value >>= 8;
        }
        return out;
    }

    private static long value(byte[] bytes) {
        long value = 0;
        for (byte b : bytes) {
            value = (value << 8) | (b & 0xFFL);
        }
        return value;
    }

    /** Uppercase hex without brackets (the code notation of this module). */
    public static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.ROOT, "%02X", b));
        }
        return sb.toString();
    }

    /** Hex of a code value in the given byte width, uppercase, no brackets. */
    public static String hexOf(long value, int width) {
        return hex(bytesOf(value, width));
    }

    private static String normalise(String codeHex) {
        String trimmed = codeHex.trim();
        if (trimmed.length() > 1 && trimmed.startsWith("<") && trimmed.endsWith(">")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        StringBuilder sb = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.digit(c, 16) >= 0) {
                sb.append(Character.toUpperCase(c));
            }
        }
        return sb.toString();
    }

    private static String text(int[] codePoints) {
        StringBuilder sb = new StringBuilder(codePoints.length);
        for (int codePoint : codePoints) {
            // The rendering may fall back to a placeholder; the number in codePoints never does.
            sb.appendCodePoint(Character.isValidCodePoint(codePoint) ? codePoint : 0xFFFD);
        }
        return sb.toString();
    }

    private static Double asDouble(Object value) {
        if (value instanceof Long number) {
            return number.doubleValue();
        }
        if (value instanceof Double real) {
            return real;
        }
        return null;
    }

    /** Codes are ordered by byte width first: a one-byte code is not the two-byte one. */
    private static int compareCodes(String a, String b) {
        int byLength = Integer.compare(a.length(), b.length());
        return byLength != 0 ? byLength : a.compareTo(b);
    }

    /** Whether the word contains a capital letter (then it is a key, not an operator). */
    private static boolean hasUpperCase(String word) {
        for (int i = 0; i < word.length(); i++) {
            if (Character.isUpperCase(word.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** Whether the word at the cursor is a PostScript keyword rather than a PDF value. */
    private static boolean isKeyword(String word) {
        if (word.isEmpty() || word.equals("true") || word.equals("false")
                || word.equals("null")) {
            return false;
        }
        char first = word.charAt(0);
        return first != '-' && first != '+' && first != '.' && !(first >= '0' && first <= '9');
    }

    /** The run of non-delimiter bytes at the offset (a keyword, a number, a bare word). */
    private static String wordAt(byte[] data, int offset) {
        int end = offset;
        while (end < data.length && !PdfParser.isDelimiterByte(data[end] & 0xFF)) {
            end++;
        }
        return new String(data, offset, end - offset, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    private static String describeMode(int mode) {
        return switch (mode) {
            case CODE_SPACE -> "codespacerange";
            case BF_CHAR -> "bfchar";
            case BF_RANGE -> "bfrange";
            default -> "unknown";
        };
    }
}
