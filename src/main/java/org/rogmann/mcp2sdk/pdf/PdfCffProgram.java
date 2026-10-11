package org.rogmann.mcp2sdk.pdf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads a CFF program (a {@code /FontFile3} with {@code /Subtype /Type1C},
 * {@code /CIDFontType0C} or {@code /OpenType}'s {@code CFF} table) as far as its <em>tables</em>
 * reach - and stops in front of the charstrings.
 *
 * <h2>The structure it walks</h2>
 * Header, Name INDEX, Top DICT INDEX, String INDEX, Global Subr INDEX, then either a FontSet (one
 * font) or - when the Top DICT carries a {@code ROS} - the Top DICT <em>is</em> the CID font's
 * DICT, with {@code FDArray}/{@code FDSelect} for its font families. DICTs are read as operand
 * lists (the number encoding of TN 5176, one- and two-byte operators); a charstring INDEX is only
 * <strong>counted</strong>, its contents are never touched. So no width comes out of a CFF either:
 * {@code defaultWidthX}/{@code nominalWidthX} say what the <em>font</em> uses, but which glyph
 * carries its own width operand is written inside the charstrings - and those are not interpreted
 * (decision 6 of the plan).
 *
 * <h2>The names, and the honest limit</h2>
 * A {@code Charset} maps glyph index → SID. Below SID 391 the string lives in the spec's
 * <em>standard string</em> table, which this module does not ship: such a name is reported as
 * {@code sid<N>} and named in a note instead of being guessed. SIDs from the program's own String
 * INDEX are resolved and are usable as a mapping source (step 5) for a non-CID program.
 * A CID-keyed program's charset maps glyph index → <em>CID</em>, so it says which CIDs the subset
 * holds - which is what {@link #coversCid(long)} answers - and nothing about characters.
 *
 * <h2>Measured at the fixture</h2>
 * {@code producer-cid.pdf} font 6 ({@code /NYPVVX+NotoSerifCJKsc-Regular}, object 26, 3 567 bytes,
 * {@code /CIDFontType0C}): header {@code 1.0}, one name, Top DICT with
 * {@code ROS = Adobe/Identity/0}, {@code CIDCount 65535}, {@code FontBBox
 * [-997,-1049,2929,1809]}, {@code charset} format 0 with eight entries and nine charstrings,
 * {@code FDSelect} format 3, {@code FDArray} of 18 families named
 * {@code NotoSerifCJKjp-Regular-Alphabetic}, {@code -AlphabeticDigits}, {@code -Bopomofo}, …, and
 * Private DICTs whose {@code defaultWidthX} runs from 331 to 1000. <em>Those eight charset CIDs are
 * exactly the eight CIDs the CIDFont's {@code /W} array covers</em> - the charset and the dictionary
 * agree, and that is the cross-check a test can pin.
 */
public final class PdfCffProgram {

    /** Names and CIDs listed at most ({@code pdf.status().maxGlyphNames}); counts stay complete. */
    public static final int MAX_NAMES = 1000;

    /** First SID that is not in the specification's standard string table. */
    public static final int FIRST_LOCAL_STRING = 391;

    /** Top DICT / Font DICT operators of TN 5176, by operator number. */
    private static final Map<String, String> TOP_KEYS = Map.ofEntries(
            Map.entry("0", "version"), Map.entry("1", "Notice"), Map.entry("2", "FullName"),
            Map.entry("3", "FamilyName"), Map.entry("4", "Weight"), Map.entry("5", "FontBBox"),
            Map.entry("13", "UniqueID"), Map.entry("14", "XUID"), Map.entry("15", "charset"),
            Map.entry("16", "encoding"), Map.entry("17", "charstrings"), Map.entry("18", "Private"),
            Map.entry("37", "FontName"), Map.entry("12.0", "Copyright"),
            Map.entry("12.1", "IsFixedPitch"), Map.entry("12.2", "ItalicAngle"),
            Map.entry("12.5", "PaintType"), Map.entry("12.6", "CharstringType"),
            Map.entry("12.7", "FontMatrix"), Map.entry("12.8", "StrokeWidth"),
            Map.entry("12.30", "ROS"), Map.entry("12.31", "CIDFontVersion"),
            Map.entry("12.32", "CIDFontRevision"), Map.entry("12.33", "CIDFontType"),
            Map.entry("12.34", "CIDCount"), Map.entry("12.35", "UIDBase"),
            Map.entry("12.36", "FDArray"), Map.entry("12.37", "FDSelect"),
            Map.entry("12.38", "FDFontName"));

    /** Private DICT operators of TN 5176. */
    private static final Map<String, String> PRIVATE_KEYS = Map.ofEntries(
            Map.entry("6", "BlueValues"), Map.entry("7", "OtherBlues"),
            Map.entry("8", "FamilyBlues"), Map.entry("9", "FamilyOtherBlues"),
            Map.entry("10", "StdHW"), Map.entry("11", "StdVW"), Map.entry("19", "Subrs"),
            Map.entry("20", "defaultWidthX"), Map.entry("21", "nominalWidthX"),
            Map.entry("12.12", "BlueScale"), Map.entry("12.13", "BlueShift"),
            Map.entry("12.14", "BlueFuzz"), Map.entry("12.17", "LanguageGroup"),
            Map.entry("12.18", "ExpansionFactor"), Map.entry("12.19", "InitialRandomSeed"));

    private final byte[] data;
    private final List<String> notes = new ArrayList<>();
    private final List<String> strings = new ArrayList<>();
    private final Map<Integer, String> glyphNames = new LinkedHashMap<>();
    private final Set<Long> cids = new LinkedHashSet<>();
    private final List<String> fdNames = new ArrayList<>();

    private int major = -1;
    private int minor = -1;
    private int headerSize = -1;
    private int globalSubrs;
    private String programName;
    private String fullName;
    private String familyName;
    private String weight;
    private double[] fontMatrix;
    private double[] fontBBox;
    private boolean cidKeyed;
    private String registry;
    private String ordering;
    private Long supplement;
    private Long cidCount;
    private int glyphCount = -1;
    private int charsetFormat = -1;
    private long charsetEntries;
    private boolean charsetCut;
    private Integer fdCount;
    private Integer fdSelectFormat;
    private Long defaultWidthX;
    private Long nominalWidthX;
    private Integer localSubrs;
    private boolean standardStringsNoted;
    private boolean namesCut;

    private PdfCffProgram(byte[] data) {
        this.data = data;
    }

    // ===================================================================
    // Reading
    // ===================================================================

    /**
     * Reads a CFF program.
     *
     * @param data the decoded {@code /FontFile3} stream
     * @return the program - never null; a broken index or DICT is a note, never an exception
     */
    public static PdfCffProgram read(byte[] data) {
        PdfCffProgram program = new PdfCffProgram(data);
        if (data == null || data.length < 4) {
            program.notes.add("The CFF program is shorter than its header: nothing can be read"
                    + " from it");
            return program;
        }
        program.major = data[0] & 0xFF;
        program.minor = data[1] & 0xFF;
        program.headerSize = data[2] & 0xFF;
        if (program.headerSize < 3 || program.headerSize > data.length) {
            program.notes.add("The CFF header announces " + program.headerSize
                    + " bytes of header in a stream of " + data.length + ": only the header is read");
            return program;
        }
        int at = program.headerSize;
        Index names = Index.read(data, at);
        if (names != null && names.count > 0) {
            program.programName = names.text(0);
        }
        if (names != null) {
            at = names.end;
        }
        Index top = Index.read(data, at);
        if (top == null || top.count < 1) {
            program.notes.add("The CFF program holds no Top DICT: its FontBBox, its charset and"
                    + " its charstring count stay unread");
            return program;
        }
        at = top.end;
        Index stringIndex = Index.read(data, at);
        if (stringIndex != null) {
            for (int i = 0; i < stringIndex.count && i < 4096; i++) {
                program.strings.add(stringIndex.text(i));
            }
            at = stringIndex.end;
        }
        Index globalIndex = Index.read(data, at);
        if (globalIndex != null) {
            program.globalSubrs = globalIndex.count;
            at = globalIndex.end;
        }

        Map<String, List<Double>> topDict = program.itemDict(top, 0);
        program.takeNames(topDict);
        program.fontBBox = numbers(topDict.get("FontBBox"), 4);
        program.fontMatrix = numbers(topDict.get("FontMatrix"), 6);
        List<Double> ros = topDict.get("ROS");
        if (ros != null && ros.size() >= 3) {
            program.cidKeyed = true;
            program.registry = program.string((int) (double) ros.get(0));
            program.ordering = program.string((int) (double) ros.get(1));
            program.supplement = (long) (double) ros.get(2);
        }
        if (topDict.get("CIDCount") != null) {
            program.cidCount = (long) (double) topDict.get("CIDCount").get(0);
        }
        // A non-CID CFF keeps its font DICT in a FontSet; a CID-keyed one IS that DICT.
        Map<String, List<Double>> fontDict = topDict;
        if (!program.cidKeyed) {
            Index fontSet = Index.read(data, at);
            if (fontSet != null && fontSet.count > 0) {
                fontDict = program.itemDict(fontSet, 0);
                if (fontDict.containsKey("FontName") || fontDict.containsKey("FullName")) {
                    program.takeNames(fontDict);
                }
            }
        }
        program.glyphCount = charstringCount(data, program.offset(fontDict, "charstrings"));
        program.charset(data, fontDict);
        program.encodingNotice(fontDict);
        program.privateDict(data, program.offsetPair(fontDict, "Private"), 0);
        program.fontDictionaries(data, topDict);
        if (program.cidKeyed && !program.cids.isEmpty()) {
            program.notes.add("This is a CID-keyed CFF (ROS " + program.registry + "-"
                    + program.ordering + "-" + program.supplement + "): its charset names the CIDs"
                    + " the subset holds, not glyph names - so it answers which CIDs are in the"
                    + " program, and never which character a CID is");
        }
        return program;
    }

    private void takeNames(Map<String, List<Double>> dict) {
        String value = sidString(dict, "FullName");
        if (value != null) {
            fullName = value;
        }
        value = sidString(dict, "FamilyName");
        if (value != null) {
            familyName = value;
        }
        value = sidString(dict, "Weight");
        if (value != null) {
            weight = value;
        }
    }

    /** The charset: names for a plain CFF, CIDs for a CID-keyed one. */
    private void charset(byte[] data, Map<String, List<Double>> dict) {
        Integer at = offset(dict, "charset");
        if (at == null) {
            return;
        }
        if (at >= data.length) {
            notes.add("The charset announced at offset " + at + " lies outside the stream of "
                    + data.length + " bytes: its names stay unread");
            return;
        }
        charsetFormat = data[at] & 0xFF;
        int want = Math.max(0, glyphCount - 1);
        int p = at + 1;
        int index = 0;
        if (charsetFormat == 0) {
            while (index < want && p + 1 < data.length) {
                addCharset(index + 1, (data[p] & 0xFF) << 8 | (data[p + 1] & 0xFF));
                index++;
                p += 2;
            }
        } else if (charsetFormat == 1 || charsetFormat == 2) {
            while (index < want && p + 2 < data.length) {
                int first = (data[p] & 0xFF) << 8 | (data[p + 1] & 0xFF);
                long left;
                if (charsetFormat == 1) {
                    left = data[p + 2] & 0xFFL;
                    p += 3;
                } else {
                    if (p + 6 >= data.length) {
                        break;
                    }
                    left = u32(data, p + 3);
                    p += 7;
                }
                for (long k = 0; k <= left && index < want; k++) {
                    // the first charset entry belongs to glyph 1: .notdef is always glyph 0 and is
                    // never listed, so an index into the charset is one below its glyph index
                    addCharset(index + 1, first + (int) k);
                    index++;
                }
            }
        } else {
            notes.add("The charset announces format " + charsetFormat + ", which is not one of the"
                    + " three formats of the specification: its names stay unread");
            return;
        }
        if (index < want) {
            notes.add("The charset ends after " + index + " of the " + want
                    + " glyphs the charstring INDEX announces: the names behind it are unknown");
        }
        if (charsetEntries > (long) glyphCount - 1 && glyphCount > 0) {
            charsetCut = true;
        }
    }

    private void addCharset(int glyph, int sid) {
        charsetEntries++;
        if (cidKeyed) {
            if (cids.size() < MAX_NAMES) {
                cids.add((long) sid);
            } else {
                charsetCut = true;
            }
            return;
        }
        if (glyphNames.size() >= MAX_NAMES) {
            namesCut = true;
            return;
        }
        glyphNames.put(glyph, string(sid));
    }

    /** {@code FDArray}: one DICT per font family, each with its own Private DICT. */
    private void fontDictionaries(byte[] data, Map<String, List<Double>> topDict) {
        Integer at = offset(topDict, "FDArray");
        if (at == null) {
            return;
        }
        Index fd = Index.read(data, at);
        if (fd == null) {
            notes.add("The FDArray offset " + at + " of the CFF program holds no INDEX:"
                    + " its font families stay unread");
            return;
        }
        fdCount = fd.count;
        Integer select = offset(topDict, "FDSelect");
        if (select != null && select < data.length) {
            fdSelectFormat = data[select] & 0xFF;
        }
        for (int i = 0; i < fd.count && i < 64; i++) {
            Map<String, List<Double>> dict = itemDict(fd, i);
            String name = sidString(dict, "FDFontName");
            if (name == null) {
                name = sidString(dict, "FontName");
            }
            if (fdNames.size() < MAX_NAMES && name != null) {
                fdNames.add(name);
            }
            if (i == 0) {
                // The first family carries the numbers the block reports; the others are counted.
                privateDict(data, offsetPair(dict, "Private"), i);
            }
        }
        if (fd.count > 0 && defaultWidthX == null && nominalWidthX == null) {
            notes.add("None of the " + fd.count + " font families names a default width: the"
                    + " program says nothing about widths at all");
        }
    }

    /** A Private DICT, read as operand lists; only the first family's numbers are kept. */
    private void privateDict(byte[] data, long[] pair, int family) {
        if (pair == null || pair[0] == 0) {
            return;                                    // a Private of no length names nothing
        }
        if (pair[1] < 0 || pair[1] + pair[0] > data.length) {
            if (family == 0) {
                notes.add("The Private DICT announced at " + pair[1] + " with " + pair[0]
                        + " bytes lies outside the stream: its widths and its blue zones stay"
                        + " unread");
            }
            return;
        }
        Map<String, List<Double>> dict = dictRange((int) pair[1], (int) (pair[1] + pair[0]));
        if (family != 0) {
            return;
        }
        if (dict.get("defaultWidthX") != null) {
            defaultWidthX = (long) (double) dict.get("defaultWidthX").get(0);
        }
        if (dict.get("nominalWidthX") != null) {
            nominalWidthX = (long) (double) dict.get("nominalWidthX").get(0);
        }
        Integer subrs = offset(dict, "Subrs");
        if (subrs != null) {
            Index local = Index.read(data, subrs);
            localSubrs = local == null ? 0 : local.count;
        }
    }

    private void encodingNotice(Map<String, List<Double>> dict) {
        if (dict.containsKey("encoding")) {
            notes.add("The program names its own Encoding at offset "
                    + (long) (double) dict.get("encoding").get(0) + " (a code-to-name table of the"
                    + " CFF flavor); pdf.font() reads the dictionary's /Encoding and /Differences"
                    + " instead, which is what the content stream is encoded by");
        }
    }

    // ===================================================================
    // DICT and INDEX helpers
    // ===================================================================

    /** A DICT as {@code key -> operands}, keys translated by {@link #TOP_KEYS}. */
    private Map<String, List<Double>> itemDict(Index index, int item) {
        if (index == null || item < 0 || item >= index.count) {
            return Map.of();
        }
        return dictRange(index.from(item), index.to(item));
    }

    private Map<String, List<Double>> dictRange(int from, int to) {
        Map<String, List<Double>> out = new LinkedHashMap<>();
        List<Double> stack = new ArrayList<>();
        int i = Math.max(0, from);
        int end = Math.min(to, data.length);
        while (i < end) {
            int b0 = data[i] & 0xFF;
            if (b0 >= 32 && b0 <= 246) {
                stack.add((double) b0 - 139);
                i++;
                continue;
            }
            if (b0 >= 247 && b0 <= 254) {
                if (i + 1 >= end) {
                    break;
                }
                int b1 = data[i + 1] & 0xFF;
                stack.add(b0 <= 250 ? (b0 - 247) * 256.0 + b1 + 108
                        : -(b0 - 251) * 256.0 - b1 - 108);
                i += 2;
                continue;
            }
            if (b0 == 255) {
                if (i + 4 >= end) {
                    break;
                }
                stack.add(((data[i + 1] & 0xFFL) << 24 | (data[i + 2] & 0xFFL) << 16
                        | (data[i + 3] & 0xFFL) << 8 | (data[i + 4] & 0xFFL)) / 65536.0);
                i += 5;
                continue;
            }
            if (b0 == 28 || b0 == 29) {
                int width = b0 == 28 ? 2 : 4;
                if (i + width >= end) {
                    break;
                }
                long value = 0;
                for (int k = 1; k <= width; k++) {
                    value = (value << 8) | (data[i + k] & 0xFFL);
                }
                if (b0 == 28) {
                    value = (short) value;
                } else {
                    value = (int) value;
                }
                stack.add((double) value);
                i += width + 1;
                continue;
            }
            if (b0 == 30) {
                int[] next = new int[1];
                stack.add(real(i + 1, end, next));
                i = next[0];
                continue;
            }
            String key;
            if (b0 == 12) {
                if (i + 1 >= end) {
                    break;
                }
                key = "12." + (data[i + 1] & 0xFF);
                i += 2;
            } else {
                key = String.valueOf(b0);
                i += 1;
            }
            String name = TOP_KEYS.getOrDefault(key, PRIVATE_KEYS.getOrDefault(key, "op" + key));
            out.put(name, new ArrayList<>(stack));
            stack.clear();
        }
        return out;
    }

    /** The real-number encoding of TN 5176 (two nibbles per byte, 0xE ends the number). */
    private double real(int from, int end, int[] next) {
        StringBuilder sb = new StringBuilder();
        int i = from;
        while (i < end) {
            int value = data[i] & 0xFF;
            i++;
            int[] nibbles = {(value >> 4) & 0x0F, value & 0x0F};
            boolean stop = false;
            for (int nibble : nibbles) {
                if (nibble <= 9) {
                    sb.append((char) ('0' + nibble));
                } else if (nibble == 10) {
                    sb.append('.');
                } else if (nibble == 11) {
                    sb.append('E');
                } else if (nibble == 12) {
                    sb.append("E-");
                } else if (nibble == 13) {
                    sb.append('-');
                } else if (nibble == 14 || nibble == 15) {
                    stop = true;
                }
            }
            if (stop) {
                break;
            }
        }
        next[0] = i;
        try {
            return Double.parseDouble(sb.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The count of the INDEX a {@code charstrings} offset points at. */
    private static int charstringCount(byte[] data, Integer at) {
        if (at == null || at + 1 >= data.length) {
            return -1;
        }
        return (data[at] & 0xFF) << 8 | (data[at + 1] & 0xFF);
    }

    private Integer offset(Map<String, List<Double>> dict, String key) {
        List<Double> value = dict.get(key);
        if (value == null || value.isEmpty()) {
            return null;
        }
        return (int) (double) value.get(0);
    }

    private long[] offsetPair(Map<String, List<Double>> dict, String key) {
        List<Double> value = dict.get(key);
        if (value == null || value.size() < 2) {
            return null;
        }
        return new long[]{(long) (double) value.get(0), (long) (double) value.get(1)};
    }

    private String sidString(Map<String, List<Double>> dict, String key) {
        Integer sid = offset(dict, key);
        return sid == null ? null : string(sid);
    }

    /**
     * A SID as the program can say it: from its own String INDEX from 391 up. Below that the string
     * is in the specification's standard string table, which this module does not ship - so the
     * number is reported and named once, not invented.
     */
    private String string(int sid) {
        if (sid >= FIRST_LOCAL_STRING && sid - FIRST_LOCAL_STRING < strings.size()) {
            return strings.get(sid - FIRST_LOCAL_STRING);
        }
        if (sid < FIRST_LOCAL_STRING && !standardStringsNoted) {
            standardStringsNoted = true;
            notes.add("A name below SID " + FIRST_LOCAL_STRING + " lives in the specification's"
                    + " standard string table, which this module does not ship: those names are"
                    + " reported as sid<N> and cannot become characters");
        }
        return sid < FIRST_LOCAL_STRING ? "sid" + sid : null;
    }

    private static double[] numbers(List<Double> values, int count) {
        if (values == null || values.size() < count) {
            return null;
        }
        double[] out = new double[count];
        for (int i = 0; i < count; i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    /** The offset of {@code bytes} inside the program (the only array a slice came from). */
    private static long u32(byte[] data, int at) {
        return ((data[at] & 0xFFL) << 24) | ((data[at + 1] & 0xFFL) << 16)
                | ((data[at + 2] & 0xFFL) << 8) | (data[at + 3] & 0xFFL);
    }

    // ===================================================================
    // What it says
    // ===================================================================

    /** True when the Top DICT carries a {@code ROS} - a CID-keyed program. */
    public boolean isCidKeyed() {
        return cidKeyed;
    }

    /** Glyphs the charstring INDEX announces, or -1. */
    public int glyphCount() {
        return glyphCount;
    }

    /** The glyph name a glyph index carries in a non-CID program, or null. */
    public String nameOfGlyph(long glyph) {
        String name = glyphNames.get((int) glyph);
        return name == null || ".notdef".equals(name) || name.startsWith("sid") ? null : name;
    }

    /** True when the program has names to give - strings it carries itself, not sid<N> numbers. */
    public boolean hasNames() {
        for (String name : glyphNames.values()) {
            if (name != null && !".notdef".equals(name) && !name.startsWith("sid")) {
                return true;
            }
        }
        return false;
    }

    /** True when a CID-keyed program's charset holds this CID. */
    public boolean coversCid(long cid) {
        return cidKeyed && cids.contains(cid);
    }

    /** How many CIDs (or names) the charset gave - complete count. */
    public long charsetEntries() {
        return charsetEntries;
    }

    /** The {@code Registry-Ordering-Supplement} of a CID-keyed program, or null. */
    public String ros() {
        return cidKeyed ? registry + "-" + ordering + "-" + supplement : null;
    }

    /** The font family names of the {@code FDArray} (bounded). */
    public List<String> fdNames(int maxItems) {
        return new ArrayList<>(fdNames.subList(0, Math.min(fdNames.size(), Math.max(1, maxItems))));
    }

    /** True where a listing could not hold everything. */
    public boolean namesTruncated() {
        return namesCut || charsetCut;
    }

    /** What this reader noticed. */
    public List<String> notes() {
        return List.copyOf(notes);
    }

    /** The {@code cff} part of a {@code program} block. */
    public Map<String, Object> block(int maxItems) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("read", true);
        if (major >= 0) {
            out.put("cffVersion", major + "." + minor);
        }
        if (programName != null) {
            out.put("programFontName", programName);
        }
        if (fullName != null) {
            out.put("programFullName", fullName);
        }
        if (familyName != null) {
            out.put("programFamilyName", familyName);
        }
        if (weight != null) {
            out.put("programWeight", weight);
        }
        out.put("cidKeyed", cidKeyed);
        if (cidKeyed) {
            out.put("ros", ros());
            if (cidCount != null) {
                out.put("cidCount", cidCount);
            }
        }
        out.put("globalSubrs", (long) globalSubrs);
        if (glyphCount >= 0) {
            out.put("glyphs", (long) glyphCount);
        }
        if (fontBBox != null) {
            out.put("fontBBox", values(fontBBox));
        }
        if (fontMatrix != null) {
            out.put("fontMatrix", values(fontMatrix));
        }
        if (charsetFormat >= 0) {
            out.put("charsetFormat", (long) charsetFormat);
            out.put("charsetEntries", charsetEntries);
            if (cidKeyed) {
                List<Object> sample = new ArrayList<>();
                int shown = 0;
                for (Long cid : cids) {
                    if (shown++ >= Math.max(1, maxItems)) {
                        out.put("charsetTruncated", true);
                        break;
                    }
                    sample.add(cid);
                }
                out.put("charsetCids", sample);
            } else if (!glyphNames.isEmpty()) {
                List<Object> sample = new ArrayList<>();
                int shown = 0;
                for (Map.Entry<Integer, String> entry : glyphNames.entrySet()) {
                    if (shown++ >= Math.max(1, maxItems)) {
                        out.put("charsetTruncated", true);
                        break;
                    }
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("glyph", (long) (int) entry.getKey());
                    item.put("name", entry.getValue());
                    sample.add(item);
                }
                out.put("charsetNames", sample);
            }
        }
        if (fdCount != null) {
            out.put("fdCount", (long) (int) fdCount);
            if (!fdNames.isEmpty()) {
                out.put("fdNames", new ArrayList<Object>(fdNames(Math.max(1, maxItems))));
            }
        }
        if (fdSelectFormat != null) {
            out.put("fdSelectFormat", (long) (int) fdSelectFormat);
        }
        if (defaultWidthX != null) {
            out.put("defaultWidthX", defaultWidthX);
        }
        if (nominalWidthX != null) {
            out.put("nominalWidthX", nominalWidthX);
        }
        if (localSubrs != null) {
            out.put("localSubrs", (long) (int) localSubrs);
        }
        return out;
    }

    private static List<Object> values(double[] numbers) {
        List<Object> out = new ArrayList<>();
        for (double value : numbers) {
            out.add(value == Math.rint(value) ? (Object) (long) value : (Object) value);
        }
        return out;
    }

    /** An INDEX of TN 5176: count, offset size, and where each item sits in the program. */
    private static final class Index {
        private final int count;
        private final int start;
        private final int end;
        private final int[] offsets;
        private final byte[] data;

        private Index(byte[] data, int count, int[] offsets, int start, int end) {
            this.data = data;
            this.count = count;
            this.offsets = offsets;
            this.start = start;
            this.end = end;
        }

        static Index read(byte[] data, int at) {
            if (data == null || at + 1 >= data.length) {
                return null;
            }
            int count = (data[at] & 0xFF) << 8 | (data[at + 1] & 0xFF);
            if (count == 0) {
                return new Index(data, 0, new int[]{1, 1}, at + 2, at + 2);
            }
            int size = data[at + 2] & 0xFF;
            if (size < 1 || size > 4 || at + 3 + (long) (count + 1) * size > data.length) {
                return null;
            }
            int[] offsets = new int[count + 1];
            int p = at + 3;
            for (int i = 0; i <= count; i++) {
                long value = 0;
                for (int k = 0; k < size; k++) {
                    value = (value << 8) | (data[p + k] & 0xFFL);
                }
                offsets[i] = (int) value;
                p += size;
            }
            int start = p;
            long end = start + offsets[count] - 1L;
            if (end > data.length) {
                return null;
            }
            return new Index(data, count, offsets, start, (int) end);
        }

        int from(int item) {
            return Math.max(0, start + offsets[item] - 1);
        }

        int to(int item) {
            return Math.min(data.length, start + offsets[item + 1] - 1);
        }

        String text(int item) {
            if (item < 0 || item + 1 >= offsets.length) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = from(item); i < to(item); i++) {
                sb.append((char) (data[i] & 0xFF));
            }
            return sb.toString();
        }
    }
}
