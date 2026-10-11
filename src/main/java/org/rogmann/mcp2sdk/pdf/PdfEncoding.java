package org.rogmann.mcp2sdk.pdf;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Says how the bytes of a text string become <em>codes</em> (ISO 32000-1, 9.6-9.7) - the question
 * a {@link PdfCMap} only answers afterwards ("and what is this code?").
 * <p>
 * The one fact worth a class is the <em>width of a code</em>: it decides where the byte string is
 * cut, and a wrong cut silently produces other characters. Everything that cannot know the width
 * says so - {@link #codeBytes()} is {@code null} and a note names the reason:
 * </p>
 * <ul>
 *   <li>{@code /Identity-H}: two bytes per code, code space {@code <0000>-<FFFF>}; the code is a
 *       CID, and which glyph that is stays with the CIDFont and its {@code /CIDToGIDMap};</li>
 *   <li>the UCS family ({@code *-UCS2-*}, {@code *-UCS4-*}, {@code *-UTF16-*}): the code
 *       <em>is</em> the character, so it needs no table - the only family of predefined CMaps
 *       decoded here;</li>
 *   <li>every other predefined CMap ({@code /UniCNS-EUC-H}, {@code /EUC-KR-H}, …): refused by
 *       name. Their code tables are the CMap collection of the specification, which this module
 *       does not carry, and reading them off the font's {@code Flags} would be a guess;</li>
 *   <li>an encoding <em>dictionary</em>: {@code /CodeSpaceRange} is read directly, an embedded
 *       {@code /CMapFile} and the {@code /UseCMap} chain are followed to their code spaces (depth
 *       limited, cycles marked), {@code /CodeMaps} is listed rather than resolved;</li>
 *   <li>a simple font: one byte per code, always. Its base table (Standard, WinAnsi, …) is
 *       <em>named</em> but not applied - the tables of Annex D are not built in - and a
 *       {@code /Differences} array is reported as what it is: glyph names that only the Adobe
 *       Glyph List ({@code src/main/resources/pdf/agl-aglfn}) can turn into characters.</li>
 *   <li>a {@code CIDFont} dictionary of its own ({@code CIDFontType0}, {@code CIDFontType2}): it
 *       names no {@code /Encoding} at all - that entry belongs to the {@code /Type0} above it - so
 *       reading it as a simple font would invent a byte width and a code page it never had.</li>
 * </ul>
 * <p>
 * Nothing here maps a code to a character; {@link #suggestedMapping()} only says which method the
 * encoding alone would imply, the chain itself runs in {@code PdfGlyphMapper}.
 * </p>
 */
public final class PdfEncoding {

    /** How deep a {@code /UseCMap} or {@code /CMapFile} chain is followed. */
    public static final int MAX_USE_CMAP_DEPTH = 8;

    /** Upper bound of {@code /Differences} entries kept. */
    public static final int MAX_DIFFERENCES = 1_000;

    /** Upper bound of code space ranges collected for one encoding. */
    public static final int MAX_CODE_SPACES = 1_024;

    /** Base encodings named by the specification (named - their tables are not built in). */
    private static final Set<String> BASE_ENCODINGS = Set.of("StandardEncoding", "WinAnsiEncoding",
            "MacRomanEncoding", "MacExpertEncoding", "PDFDocEncoding", "SymbolEncoding",
            "ZapfDingbatsEncoding", "EPSEncoding", "PSEncoding");

    /** Note keys of the plan's catalog that this classification can raise. */
    public static final String NOTE_PREDEFINED_UNSUPPORTED = "predefined-cmap-unsupported";
    public static final String NOTE_CODESPACE_UNKNOWN = "codespace-unknown";
    public static final String NOTE_ENCODING_MISSING = "encoding-missing";
    public static final String NOTE_BUILTIN_ENCODING = "builtin-encoding";
    public static final String NOTE_BASE_TABLE_NOT_BUILT_IN = "base-encoding-table";
    public static final String NOTE_USE_CMAP_DEPTH = "usecmap-depth";
    public static final String NOTE_DIFFERENCES_CUT = "differences-cut";

    /**
     * The classification as a value (so it can travel into a result map).
     *
     * @param kind         {@code identity-h}, {@code identity-v}, {@code ucs},
     *                     {@code predefined-unsupported}, {@code cid-codespace},
     *                     {@code cid-cmapfile}, {@code cid-usecmap}, {@code table},
     *                     {@code builtin} or {@code unknown}
     * @param name         the {@code /Encoding} name as written (with slash), or null
     * @param codeBytes    bytes per code, or null when the encoding cannot say
     * @param vertical     a vertical encoding ({@code Identity-V})
     * @param ucs          the code is a Unicode code point
     * @param baseEncoding the simple font's base table (Standard, WinAnsi, "built-in", …)
     * @param source       where the knowledge came from: {@code encoding}, {@code encoding-dict},
     *                     {@code cmapfile}, {@code usecmap} or {@code default}
     */
    public record Shape(String kind, String name, Integer codeBytes, boolean vertical, boolean ucs,
                        String baseEncoding, String source) {
    }

    private String kind = "unknown";
    private String name;
    private Integer codeBytes;
    private boolean vertical;
    private Long wMode;
    private String wModeSource;
    private boolean ucs;
    private String baseEncoding;
    private String source = "default";
    private String cmapName;

    private final List<PdfCMap.CodeSpace> codeSpaces = new ArrayList<>();
    private final List<Map<String, Object>> differences = new ArrayList<>();
    private final List<String> useCMaps = new ArrayList<>();
    private final List<Map<String, Object>> codeMaps = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private final List<String> noteKeys = new ArrayList<>();
    private Map<String, Object> cidSystemInfo;
    private boolean differencesCut;

    private PdfEncoding() {
        // built by of()
    }

    // ===================================================================
    // Classification
    // ===================================================================

    /**
     * Classifies the encoding of a font dictionary.
     * @param document the document (to follow {@code /CMapFile} and {@code /UseCMap}), may be null
     * @param fontDict the resolved font dictionary
     * @return the classification - never null; an unreadable encoding is {@code unknown} plus a
     *         note instead of a guess
     */
    public static PdfEncoding of(PdfDocument document, Map<?, ?> fontDict) {
        PdfEncoding encoding = new PdfEncoding();
        if (fontDict == null || fontDict.isEmpty()) {
            encoding.note(NOTE_ENCODING_MISSING, "there is no font dictionary to read an encoding"
                    + " from");
            return encoding;
        }
        PdfName subtype = PdfName.ofDict(fontDict, "Subtype");
        boolean cidFont = subtype != null && (subtype.equalsName("Type0") || isCidFontDict(subtype));
        Object value = document != null ? document.dereference(fontDict.get("Encoding"))
                : fontDict.get("Encoding");
        if (value == null) {
            if (isCidFontDict(subtype)) {
                // A CIDFont has no /Encoding entry at all (9.7.4.1): the width of a code is the
                // /Type0 wrapper's statement. Treating it as a simple font would invent a byte
                // width, a StandardEncoding and a code page for a font that has none of them.
                encoding.note(NOTE_CODESPACE_UNKNOWN, "/Subtype " + subtype + " is a CIDFont, which"
                        + " has no /Encoding of its own: the width of a code comes from the /Type0"
                        + " font above it");
                return encoding;
            }
            if (cidFont) {
                // The specification makes /Encoding a requirement of a Type0 font. Without it the
                // byte width is unknown, and "probably two bytes" would be a guess about every
                // string on the page.
                encoding.note(NOTE_ENCODING_MISSING, "/Type0 font without /Encoding: the"
                        + " specification requires it, so the byte width of a code is unknown");
                return encoding;
            }
            encoding.simpleFontWithoutEncoding(document, fontDict, subtype);
            return encoding;
        }
        if (value instanceof PdfName name) {
            encoding.readName(name.value(), cidFont);
            return encoding;
        }
        if (value instanceof Map<?, ?> dict) {
            encoding.readEncodingDictionary(document, dict, cidFont);
            return encoding;
        }
        encoding.note(NOTE_CODESPACE_UNKNOWN, "/Encoding is " + PdfParser.kindOf(value)
                + " instead of a name or a dictionary - the byte width of a code is unknown");
        return encoding;
    }

    /** The two subtypes that <em>are</em> a CIDFont (not the {@code /Type0} above them). */
    private static boolean isCidFontDict(PdfName subtype) {
        return subtype != null
                && (subtype.equalsName("CIDFontType0") || subtype.equalsName("CIDFontType2"));
    }

    /** A simple font without {@code /Encoding}: one byte per code, base table named only. */
    private void simpleFontWithoutEncoding(PdfDocument document, Map<?, ?> fontDict,
                                           PdfName subtype) {
        boolean trueType = subtype != null && subtype.equalsName("TrueType");
        kind = "builtin";
        codeBytes = 1;
        baseEncoding = trueType ? "built-in" : "StandardEncoding";
        source = "default";
        codeSpaces.add(new PdfCMap.CodeSpace(1, 0, 0xFF, "hex"));
        if (trueType) {
            note(NOTE_BUILTIN_ENCODING, "a /TrueType font without /Encoding uses the encoding"
                    + " built into its program - the text stays as it is read");
        } else if (isSymbolic(document, fontDict)) {
            note(NOTE_BUILTIN_ENCODING, "no /Encoding: the specification says StandardEncoding,"
                    + " but the font is flagged symbolic - text stays as it is (document"
                    + " convention)");
        } else {
            note(NOTE_BASE_TABLE_NOT_BUILT_IN, "no /Encoding: the specification says"
                    + " StandardEncoding, whose table is not built in - its codes are read by the"
                    + " document convention, not by that table");
        }
    }

    /** A named encoding: Identity, the UCS family, a base table, or a refusal. */
    private void readName(String bareName, boolean cidFont) {
        name = "/" + bareName;
        source = "encoding";
        if ("Identity-H".equals(bareName) || "Identity-V".equals(bareName)) {
            vertical = bareName.endsWith("-V");
            modeFromName(bareName);
            kind = vertical ? "identity-v" : "identity-h";
            codeBytes = 2;
            codeSpaces.add(new PdfCMap.CodeSpace(2, 0, 0xFFFF, "hex"));
            return;
        }
        String family = ucsFamily(bareName);
        if (family != null) {
            ucs = true;
            kind = "ucs";
            modeFromName(bareName);
            codeBytes = "UCS4".equals(family) ? 4 : 2;
            codeSpaces.add(new PdfCMap.CodeSpace(codeBytes, 0,
                    codeBytes == 4 ? 0xFFFF_FFFFL : 0xFFFFL, "hex"));
            return;
        }
        if (!cidFont && BASE_ENCODINGS.contains(bareName)) {
            // A simple font's base table: one byte per code is certain, the mapping is not.
            kind = "table";
            codeBytes = 1;
            baseEncoding = bareName;
            codeSpaces.add(new PdfCMap.CodeSpace(1, 0, 0xFF, "hex"));
            note(NOTE_BASE_TABLE_NOT_BUILT_IN, "/" + bareName + " is named but its table is not"
                    + " built in: its codes are read by the document convention, its glyph names"
                    + " stay unresolved");
            return;
        }
        if (BASE_ENCODINGS.contains(bareName)) {
            // A base encoding name on a CID font is not a CID encoding - say what is missing.
            baseEncoding = bareName;
            note(NOTE_CODESPACE_UNKNOWN, "/Encoding /" + bareName + " on a CID font is a base"
                    + " encoding, not a CID CMap: the byte width is unknown");
            return;
        }
        kind = "predefined-unsupported";
        modeFromName(bareName);
        note(NOTE_PREDEFINED_UNSUPPORTED, "/Encoding /" + bareName + " is a predefined CMap: only"
                + theUcsNote());
    }

    /** The wording of the UCS refusal, with the writing mode added when the name states one. */
    private String theUcsNote() {
        return vertical ? " the UCS families (code = Unicode) are decoded, codes stay codes;"
                + " the name states vertical writing, so a /W2 in the CIDFont is the metric set"
                + " a viewer would use"
                : " the UCS families (code = Unicode) are decoded, codes stay codes";
    }

    /**
     * The writing mode a predefined CMap name states: PDF 1.7 5.6.4 - "CMaps whose names end in H
     * specify horizontal writing mode; those ending in V specify vertical writing mode". An
     * embedded CMap says the same thing with its {@code /WMode} entry; where neither speaks, the
     * specification's default is horizontal and {@link #wMode()} stays null, because "defaulted"
     * and "stated" are different answers.
     */
    private void modeFromName(String bareName) {
        if (bareName.endsWith("-V")) {
            vertical = true;
            wMode = 1L;
            wModeSource = "predefined-cmap-name";
        } else if (bareName.endsWith("-H")) {
            wMode = 0L;
            wModeSource = "predefined-cmap-name";
        }
    }

    /**
     * A simple font's encoding dictionary (9.6.4): {@code /BaseEncoding} names the table,
     * {@code /Differences} overwrites single codes. One byte per code either way, and the table
     * itself stays unnamed-by-mapping because Annex D is not built in.
     */
    private void simpleEncodingDictionary(PdfDocument document, Map<?, ?> dict) {
        kind = "table";
        source = "encoding-dict";
        codeBytes = 1;
        codeSpaces.add(new PdfCMap.CodeSpace(1, 0, 0xFF, "hex"));
        Object base = document != null ? document.dereference(dict.get("BaseEncoding"))
                : dict.get("BaseEncoding");
        if (base instanceof PdfName baseName) {
            baseEncoding = baseName.value();
            name = "/" + baseName.value();
        }
        Object differences = document != null ? document.dereference(dict.get("Differences"))
                : dict.get("Differences");
        boolean hasDifferences = readDifferences(differences);
        String table = baseEncoding != null ? baseEncoding : "StandardEncoding";
        if (baseEncoding != null && !BASE_ENCODINGS.contains(baseEncoding)) {
            note(NOTE_BASE_TABLE_NOT_BUILT_IN, "/BaseEncoding /" + baseEncoding + " is not one of"
                    + " the base tables of the specification, and no table is built in: codes stay"
                    + " codes");
        } else if (hasDifferences) {
            note(NOTE_BASE_TABLE_NOT_BUILT_IN, "the base table " + table + " behind /Differences"
                    + " is not built in: only the named glyphs can be mapped (Adobe Glyph List)");
        } else {
            note(NOTE_BASE_TABLE_NOT_BUILT_IN, "the base table " + table + " is not built in:"
                    + " codes stay codes");
        }
    }

    /** An encoding dictionary (9.7.5.1): {@code /CodeSpaceRange}, {@code /CMapFile},
     * {@code /UseCMap}, {@code /CodeMaps}. The dictionary's own range wins over anything
     * inherited, because it is the one the producer wrote for this font. */
    private void readEncodingDictionary(PdfDocument document, Map<?, ?> dict, boolean cidFont) {
        if (!cidFont && (dict.containsKey("Differences") || dict.containsKey("BaseEncoding"))) {
            // A simple font's encoding dictionary (9.6.4): /BaseEncoding plus /Differences - not
            // a CID encoding, so no code space question beyond "one byte".
            simpleEncodingDictionary(document, dict);
            return;
        }
        kind = "unknown";
        source = "encoding-dict";
        Object info = document != null ? document.dereference(dict.get("CIDSystemInfo"))
                : dict.get("CIDSystemInfo");
        if (info instanceof Map<?, ?> infoDict) {
            cidSystemInfo = infoView(infoDict);
        }
        Object range = dict.get("CodeSpaceRange");
        if (range != null && document != null) {
            range = document.dereference(range);
        }
        if (range instanceof List<?> list && !list.isEmpty()) {
            readCodeSpaceRange(list);
            kind = "cid-codespace";
        }
        Object rawCMapFile = dict.get("CMapFile");
        if (rawCMapFile instanceof PdfReference reference && document != null) {
            followByNumber(document, reference.objNum(), 1, new HashSet<>(), "cmapfile");
        } else {
            Object cMapFile = document != null ? document.dereference(rawCMapFile) : rawCMapFile;
            if (cMapFile instanceof PdfStream stream && document != null) {
                applyCMap(PdfCMap.parse(readInline(document, stream), PdfCMap.MAX_ENTRIES),
                        "cmapfile", 1);
            }
        }
        Object rawUseCMap = dict.get("UseCMap");
        if (rawUseCMap instanceof PdfReference reference && document != null) {
            // A reference to an embedded CMap stream - the name form below is the predefined one.
            useCMaps.add(reference.toString());
            followByNumber(document, reference.objNum(), 1, new HashSet<>(), "usecmap");
        } else {
            Object useCMap = document != null ? document.dereference(rawUseCMap) : rawUseCMap;
            if (useCMap instanceof PdfName useName) {
                useCMaps.add(useName.toString());
                if (codeBytes == null) {
                    boolean wasUnknown = "unknown".equals(kind);
                    readName(useName.value(), true);
                    if (wasUnknown) {
                        source = "usecmap";
                    }
                }
            } else if (useCMap instanceof PdfStream stream && document != null) {
                applyCMap(PdfCMap.parse(readInline(document, stream), PdfCMap.MAX_ENTRIES),
                        "usecmap", 1);
            } else if (useCMap != null) {
                note(NOTE_CODESPACE_UNKNOWN, "/UseCMap is " + PdfParser.kindOf(useCMap)
                        + " instead of a name or a stream - it was not followed");
            }
        }
        Object codeMaps = dict.get("CodeMaps");
        if (codeMaps != null && document != null) {
            codeMaps = document.dereference(codeMaps);
        }
        if (codeMaps instanceof List<?> list) {
            readCodeMaps(list);
        }
        if (codeBytes == null) {
            note(NOTE_CODESPACE_UNKNOWN, "no code space: /Encoding dictionary without"
                    + " /CodeSpaceRange and without an embedded /CMapFile");
        }
    }

    /** Reads {@code /CodeSpaceRange [<lo> <hi> <lo> <hi> …]} - pairs of equal width. */
    private void readCodeSpaceRange(List<?> list) {
        for (int i = 0; i + 1 < list.size(); i += 2) {
            if (!(list.get(i) instanceof PdfString from)
                    || !(list.get(i + 1) instanceof PdfString to)) {
                note(NOTE_CODESPACE_UNKNOWN, "a /CodeSpaceRange entry is not a pair of strings -"
                        + " the rest of the array was skipped");
                return;
            }
            byte[] fromBytes = from.bytes();
            if (fromBytes.length == 0 || fromBytes.length > 4) {
                note(NOTE_CODESPACE_UNKNOWN, "a /CodeSpaceRange entry of " + fromBytes.length
                        + " byte(s) is out of reach and was skipped");
                continue;
            }
            if (codeBytes == null) {
                codeBytes = fromBytes.length;
            } else if (codeBytes != fromBytes.length) {
                note(NOTE_CODESPACE_UNKNOWN, "/CodeSpaceRange mixes " + codeBytes + " and "
                        + fromBytes.length + "-byte codes: the wider codes cannot be cut reliably");
            }
            if (codeSpaces.size() >= MAX_CODE_SPACES) {
                return;
            }
            codeSpaces.add(new PdfCMap.CodeSpace(fromBytes.length, value(fromBytes),
                    value(to.bytes()), from.isHex() ? "hex" : "literal"));
        }
    }

    /**
     * {@code /CodeMaps} is a flat array of triples - start code, end code, and the code map
     * streams for that range (9.7.5.1). Listed, not resolved: which code maps to which glyph is
     * task A4's question.
     */
    private void readCodeMaps(List<?> list) {
        for (int i = 0; i + 2 < list.size(); i += 3) {
            if (codeMaps.size() >= MAX_CODE_SPACES) {
                return;
            }
            if (!(list.get(i) instanceof PdfString from)
                    || !(list.get(i + 1) instanceof PdfString to)) {
                note(NOTE_CODESPACE_UNKNOWN, "a /CodeMaps entry is not the triple"
                        + " start code / end code / code maps - the rest was skipped");
                return;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("from", PdfCMap.hex(from.bytes()));
            item.put("to", PdfCMap.hex(to.bytes()));
            List<Object> streams = new ArrayList<>();
            Object third = list.get(i + 2);
            if (third instanceof List<?> refs) {
                for (Object ref : refs) {
                    if (ref instanceof PdfReference reference) {
                        streams.add((long) reference.objNum());
                    }
                }
            } else if (third instanceof PdfReference reference) {
                streams.add((long) reference.objNum());
            } else {
                note(NOTE_CODESPACE_UNKNOWN, "the code maps of the range "
                        + PdfCMap.hex(from.bytes()) + '-' + PdfCMap.hex(to.bytes())
                        + " are not stream references");
            }
            item.put("cmapStreams", streams);
            codeMaps.add(item);
        }
    }

    /**
     * Follows a CMap stream by object number and takes what it says about the code width. The
     * chain stops at {@link #MAX_USE_CMAP_DEPTH} and at the first object seen twice, so a
     * {@code /UseCMap} that points back cannot spin.
     */
    private void followByNumber(PdfDocument document, int objNum, int depth, Set<Integer> visited,
                                String sourceLabel) {
        if (!visited.add(objNum)) {
            note(NOTE_USE_CMAP_DEPTH, "a /CMapFile or /UseCMap chain reaches object " + objNum
                    + " twice - the chain was cut here");
            return;
        }
        if (depth > MAX_USE_CMAP_DEPTH) {
            note(NOTE_USE_CMAP_DEPTH, "/UseCMap chain deeper than " + MAX_USE_CMAP_DEPTH
                    + " levels - the rest was not followed");
            return;
        }
        try {
            modeFromStreamDict(document.dictOf(objNum));
            applyCMap(PdfCMap.parse(document.stream(objNum).data(), PdfCMap.MAX_ENTRIES),
                    sourceLabel, depth);
        } catch (PdfException e) {
            note(NOTE_CODESPACE_UNKNOWN, "the CMap stream " + objNum + " could not be read ("
                    + e.getMessage() + "): its code space is unknown");
        }
    }

    /**
     * The stream dictionary of an embedded CMap repeats the writing mode (PDF 1.7 5.6.4: "The value
     * of this entry should be the same as the value of WMode in the CMap file"). It is read first
     * because it is what a reader sees before it opens the stream; the file's own entry is compared
     * against it in {@link #applyCMap}.
     */
    private void modeFromStreamDict(Map<String, Object> streamDict) {
        if (wMode != null || !(streamDict.get("WMode") instanceof Long number)) {
            return;
        }
        wMode = number;
        wModeSource = "cmap-stream-dictionary";
        if (number == 1L) {
            vertical = true;
        }
    }

    private byte[] readInline(PdfDocument document, PdfStream stream) {
        List<String> warnings = new ArrayList<>();
        byte[] data = document.decodedStream(stream, warnings);
        for (String warning : warnings) {
            note(NOTE_CODESPACE_UNKNOWN, "the embedded /CMapFile stream says: " + warning);
        }
        return data;
    }

    /** Merges one CMap's facts; the width already known from the dictionary wins. */
    private void applyCMap(PdfCMap cmap, String sourceLabel, int depth) {
        if (cmapName == null && cmap.cmapName() != null) {
            cmapName = cmap.cmapName();
        }
        if (cidSystemInfo == null) {
            cidSystemInfo = cmap.cidSystemInfo();
        }
        // The CMap's writing mode decides whether a CIDFont's /W2 metrics are the ones in use.
        // The first statement wins, a disagreement is said (the specification expects the stream
        // dictionary and the CMap file to agree, so a difference is worth hearing about).
        if (wMode == null && cmap.wMode() != null) {
            wMode = cmap.wMode();
            wModeSource = "cmap-file";
            if (wMode == 1L) {
                vertical = true;
            }
        } else if (wMode != null && cmap.wMode() != null && !wMode.equals(cmap.wMode())) {
            note(NOTE_CODESPACE_UNKNOWN, "the encoding states its writing mode twice and"
                    + " differently (" + wMode + " against " + cmap.wMode() + "): the first"
                    + " statement is the one reported");
        }
        for (String inherited : cmap.useCMaps()) {
            if (!useCMaps.contains(inherited)) {
                useCMaps.add(inherited);
            }
        }
        for (PdfCMap.CodeSpace space : cmap.codeSpaces()) {
            if (codeSpaces.size() >= MAX_CODE_SPACES) {
                break;
            }
            codeSpaces.add(space);
            if (codeBytes == null) {
                codeBytes = space.bytes();
                source = depth >= 2 && "usecmap".equals(sourceLabel) ? "usecmap" : sourceLabel;
                kind = "usecmap".equals(sourceLabel) ? "cid-usecmap" : "cid-cmapfile";
            }
        }
        for (String warning : cmap.warnings()) {
            note(NOTE_CODESPACE_UNKNOWN, "the CMap behind the encoding says: " + warning);
        }
    }

    /**
     * Reads a {@code /Differences} array: {@code [24 /bullet 26 /copyright]} - the number sets the
     * code the following names run from. Names are kept as written; resolving them to characters
     * is the Adobe Glyph List's job, not this class's.
     * @param value the array (already dereferenced)
     * @return true when something was read
     */
    public boolean readDifferences(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return false;
        }
        long code = 0;
        for (Object item : list) {
            if (item instanceof Long number) {
                code = number;
                continue;
            }
            if (!(item instanceof PdfName glyphName)) {
                continue;
            }
            if (differences.size() >= MAX_DIFFERENCES) {
                differencesCut = true;
                note(NOTE_DIFFERENCES_CUT, "/Differences holds more than " + MAX_DIFFERENCES
                        + " entries - the rest was not listed (the count stays honest)");
                break;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("code", code);
            entry.put("glyph", glyphName.toString());
            entry.put("glyphBytes", PdfCMap.hex(glyphName.bytes()));
            differences.add(entry);
            code++;
        }
        if (differences.isEmpty()) {
            return false;
        }
        if ("builtin".equals(kind) || "table".equals(kind) || "unknown".equals(kind)) {
            kind = "differences";
            source = "differences";
        }
        if (codeBytes == null) {
            codeBytes = 1;
            codeSpaces.add(new PdfCMap.CodeSpace(1, 0, 0xFF, "hex"));
        }
        return true;
    }

    // ===================================================================
    // Facts
    // ===================================================================

    /** The classification as a value. */
    public Shape shape() {
        return new Shape(kind, name, codeBytes, vertical, ucs, baseEncoding, source);
    }

    /** {@code kind} of the shape (shortcut). */
    public String kind() {
        return kind;
    }

    /** Bytes per code, or null when the encoding cannot say it. */
    public Integer codeBytes() {
        return codeBytes;
    }

    /** True when the code <em>is</em> the Unicode code point. */
    public boolean isUcs() {
        return ucs;
    }

    /** The code spaces this encoding knows (empty when it cannot say). */
    public List<PdfCMap.CodeSpace> codeSpaces() {
        return List.copyOf(codeSpaces);
    }

    /** {@code /Differences} entries as {@code {code, glyph, glyphBytes}}. */
    public List<Map<String, Object>> differences() {
        return List.copyOf(differences);
    }

    /** Predefined CMaps or CMap streams this encoding inherits from. */
    public List<String> useCMaps() {
        return List.copyOf(useCMaps);
    }

    /** The {@code /CodeMaps} entries of an encoding dictionary (listed, not resolved). */
    public List<Map<String, Object>> codeMaps() {
        return List.copyOf(codeMaps);
    }

    /** {@code /CIDSystemInfo} of the dictionary or of the CMap it points at. */
    public Map<String, Object> cidSystemInfo() {
        return cidSystemInfo;
    }

    /** The name of the CMap that supplied the code space, if it came from one. */
    public String cmapName() {
        return cmapName;
    }

    /** What this encoding has to say (catalog sentence per key, first key wins). */
    /**
     * The writing mode the encoding states ({@code 0} horizontal, {@code 1} vertical), or null when
     * neither a predefined name nor the CMap file says one. The specification's default is
     * horizontal, but a default the file never stated is not the file's answer - that difference is
     * what {@link #wModeSource()} and the null are for.
     */
    public Long wMode() {
        return wMode;
    }

    /** Who stated the writing mode: {@code predefined-cmap-name} or {@code cmap-file}. */
    public String wModeSource() {
        return wModeSource;
    }

    public List<String> notes() {
        return List.copyOf(notes);
    }

    /** The catalog keys raised, in the order they were raised. */
    public List<String> noteKeys() {
        return List.copyOf(noteKeys);
    }

    /** True when {@code /Differences} was longer than {@link #MAX_DIFFERENCES}. */
    public boolean differencesCut() {
        return differencesCut;
    }

    /**
     * The mapping method the encoding alone implies (the plan's {@code mapping} value): the UCS
     * family resolves by itself, everything else needs another source.
     */
    public String suggestedMapping() {
        if (ucs) {
            return "unicode-code";
        }
        if ("predefined-unsupported".equals(kind)) {
            return "predefined-cmap-unsupported";
        }
        if (!differences.isEmpty()) {
            return "differences";
        }
        if ("table".equals(kind) || "builtin".equals(kind)) {
            return "builtin-encoding";
        }
        return "none";
    }

    // ===================================================================
    // Listing
    // ===================================================================

    /**
     * The encoding as a JS-friendly block (goes into {@code pdf.font()}, task A1).
     * @param maxDifferences differences listed (0 = no limit)
     * @return the map
     */
    public Map<String, Object> toMap(int maxDifferences) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("kind", kind);
        if (name != null) {
            map.put("name", name);
        }
        // a Long like every other number in the results: the same value reads the same way in
        // pdf.fonts() (where the listing writes it) and in pdf.font() (where this block lives).
        map.put("codeBytes", codeBytes == null ? null : (long) (int) codeBytes);
        if (vertical) {
            map.put("vertical", true);
        }
        if (wMode != null) {
            map.put("wMode", wMode);
            map.put("wModeSource", wModeSource);
        }
        if (ucs) {
            map.put("codeIsUnicode", true);
        }
        if (baseEncoding != null) {
            map.put("baseEncoding", baseEncoding);
        }
        if (cmapName != null) {
            map.put("cmapName", cmapName);
        }
        map.put("source", source);
        map.put("mapping", suggestedMapping());
        List<Map<String, Object>> spaces = new ArrayList<>();
        for (PdfCMap.CodeSpace space : codeSpaces) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("from", PdfCMap.hexOf(space.from(), space.bytes()));
            item.put("to", PdfCMap.hexOf(space.to(), space.bytes()));
            item.put("bytes", (long) space.bytes());
            item.put("notation", space.notation());
            spaces.add(item);
        }
        map.put("codeSpaces", spaces);
        if (!useCMaps.isEmpty()) {
            map.put("useCmaps", new ArrayList<Object>(useCMaps));
        }
        if (!codeMaps.isEmpty()) {
            map.put("codeMaps", new ArrayList<Object>(codeMaps));
        }
        if (cidSystemInfo != null) {
            map.put("cidSystemInfo", cidSystemInfo);
        }
        if (!differences.isEmpty()) {
            List<Object> listed = new ArrayList<>();
            for (Map<String, Object> item : differences) {
                if (maxDifferences > 0 && listed.size() >= maxDifferences) {
                    map.put("differencesTruncated", true);
                    break;
                }
                listed.add(item);
            }
            map.put("differences", listed);
            map.put("differencesCount", (long) differences.size());
        }
        if (!notes.isEmpty()) {
            map.put("notes", new ArrayList<Object>(notes));
        }
        return map;
    }

    // ===================================================================
    // Helpers
    // ===================================================================

    /**
     * The UCS family of a predefined CMap name: a name part {@code UCS2}, {@code UCS4} or
     * {@code UTF16} ({@code /UniGB-UCS2-H}, {@code /UniJIS2000-UTF16-H}). Null for everything
     * else, including {@code Identity}.
     * @param bareName the name without its slash
     * @return {@code UCS2}, {@code UCS4}, {@code UTF16} or null
     */
    public static String ucsFamily(String bareName) {
        if (bareName == null || bareName.startsWith("Identity")) {
            return null;
        }
        for (String part : bareName.split("-")) {
            String upper = part.toUpperCase(Locale.ROOT);
            if (upper.equals("UCS2") || upper.equals("UCS4") || upper.equals("UTF16")) {
                return upper;
            }
        }
        return null;
    }

    /** Whether the font's descriptor carries the {@code Symbolic} flag (bit value 4). */
    private static boolean isSymbolic(PdfDocument document, Map<?, ?> fontDict) {
        Object descriptor = fontDict.get("FontDescriptor");
        if (descriptor instanceof PdfReference reference && document != null) {
            descriptor = document.dereference(reference);
        }
        return descriptor instanceof Map<?, ?> dict && dict.get("Flags") instanceof Long flags
                && (flags & 0x04L) != 0;
    }

    private static Map<String, Object> infoView(Map<?, ?> dict) {
        Map<String, Object> info = new LinkedHashMap<>();
        for (String key : List.of("Registry", "Ordering", "Supplement")) {
            Object value = dict.get(key);
            if (value instanceof PdfString string) {
                info.put(key.toLowerCase(Locale.ROOT), string.text());
            } else if (value instanceof PdfName name) {
                info.put(key.toLowerCase(Locale.ROOT), name.value());
            } else if (value != null) {
                info.put(key.toLowerCase(Locale.ROOT), value);
            }
        }
        return info.isEmpty() ? null : info;
    }

    private static long value(byte[] bytes) {
        long value = 0;
        for (byte b : bytes) {
            value = (value << 8) | (b & 0xFFL);
        }
        return value;
    }

    private void note(String key, String sentence) {
        if (!noteKeys.contains(key)) {
            noteKeys.add(key);
            notes.add(sentence);
        }
    }
}
