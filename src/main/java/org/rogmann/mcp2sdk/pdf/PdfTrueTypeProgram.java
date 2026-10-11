package org.rogmann.mcp2sdk.pdf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads the <em>tables</em> of an embedded TrueType or OpenType program (the sfnt format the PDF
 * specification refers to in Annex F): the table directory, {@code head}, {@code hhea},
 * {@code maxp}, {@code hmtx}, {@code loca}+{@code glyf} and {@code cmap}.
 * <p>
 * <b>Tables, not instructions.</b> A font program is a program, and this class is where the plan's
 * rule "fonts are data, not code" is held to: no hinting instruction and no glyph description is
 * ever executed. {@code glyf} is read as far as its <em>bounding box</em> reaches - the first ten
 * bytes of a glyph description - and a composite glyph is reported as one instead of being
 * composed.
 * </p>
 * <p>
 * Two questions are asked of the {@code cmap}, and they need two different directions:
 * </p>
 * <ul>
 *   <li><b>glyph → character</b> (step 4 of the mapping chain) only reads a subtable whose
 *       platform/encoding pair promises Unicode: platform 0, platform 3 with a Unicode encoding id
 *       (1, 4, 6, 10, 768, …) or platform 2. Platform 1 is refused on purpose - its id 256 is
 *       "user-defined", i.e. arbitrary numbers, and calling one of those a character would be an
 *       invention (exactly what the ReportLab subset of the fixture does, and it says so in a note).
 *       The inverse relation keeps the <em>smallest</em> code point of a glyph and counts the glyphs
 *       that more than one code point names.</li>
 *   <li><b>code → glyph</b> (which glyph a byte of a simple font's string selects, so that
 *       {@code hmtx} can be asked for its width) reads <em>any</em> readable subtable, because that
 *       lookup says nothing about meaning - it is the same lookup a viewer performs to draw.</li>
 * </ul>
 * <p>
 * Widths leave this class on the base the dictionary uses. {@code hmtx} counts in
 * {@code head.unitsPerEm} while {@code /W} and {@code /Widths} count in thousandths of the text
 * space, so {@link #advanceInTextUnits(int)} converts ({@code 1000 * advance / unitsPerEm}) and
 * keeps a fraction where there is one: the fixture's 2048-unit font answers 651 as
 * {@code 317.8711}, which is exactly the number its {@code /Widths} array carries - so the two
 * sources can be compared instead of being confused.
 * </p>
 * <p>
 * Nothing here throws for a broken program: a table directory that points behind the stream, an
 * {@code hmtx} shorter than {@code numberOfHMetrics} claims, a {@code loca} whose offsets run past
 * {@code glyf} - each is one sentence in {@link #notes()} and a {@code null} answer, never a
 * {@link PdfException} on the way to a font sheet.
 * </p>
 */
public final class PdfTrueTypeProgram {

    /** The base {@code /W} and {@code /Widths} count in; {@code hmtx} counts in {@code unitsPerEm}. */
    public static final int TEXT_UNITS = 1000;

    /** Table directory entries read at most - see {@link PdfFontProgram#MAX_TABLES}. */
    public static final int MAX_TABLES = PdfFontProgram.MAX_TABLES;

    /** {@code cmap} entries read into the inverse table at most (the {@code /ToUnicode} budget). */
    public static final int MAX_CMAP_ENTRIES = PdfCMap.MAX_ENTRIES;

    /** The sfnt carries TrueType outlines. */
    public static final String KIND_TRUETYPE = "truetype";

    /** The sfnt carries a {@code CFF } table instead of {@code glyf} (OpenType). */
    public static final String KIND_OPENTYPE = "opentype";

    /** Note keys of the plan's catalog this class can raise. */
    public static final String NOTE_TABLE_UNREADABLE = "program-table-unreadable";
    public static final String NOTE_TABLES_CUT = "program-tables-cut";
    public static final String NOTE_NO_CMAP = "program-without-cmap";
    public static final String NOTE_CMAP_NOT_UNICODE = "program-cmap-not-unicode";
    public static final String NOTE_CMAP_PARTIAL = "program-cmap-partial";
    public static final String NOTE_HMTX_SHORT = "program-hmtx-short";
    public static final String NOTE_LOCA_SHORT = "program-loca-short";
    public static final String NOTE_GLYPH_BEYOND = "program-glyph-beyond";

    /**
     * One entry of the table directory.
     *
     * @param tag    the four characters as written (a space is part of {@code "cvt "})
     * @param offset offset inside the program
     * @param length length of the table
     * @param usable whether the range lies inside the program's bytes
     */
    public record Table(String tag, long offset, long length, boolean usable) {
    }

    /**
     * What the program says about one glyph.
     *
     * @param advance            {@code hmtx} advance in the program's own units, or null
     * @param advanceInTextUnits the same advance on the 1000-unit base (comparable to {@code /W}),
     *                           or null
     * @param lsb                left side bearing in the program's units, or null
     * @param bbox               {@code glyf} bounding box {@code [xMin, yMin, xMax, yMax]}, or null
     * @param composite          true when the glyph is composed of other glyphs - the reason its box
     *                           is not claimed
     * @param empty              true when the glyph holds no description at all
     */
    public record Metrics(Integer advance, Number advanceInTextUnits, Integer lsb, int[] bbox,
                          boolean composite, boolean empty) {
    }

    /**
     * One {@code cmap} subtable.
     *
     * @param platform platform id
     * @param encoding encoding id
     * @param format   0, 4, 6 or 12 - the formats this class can read; others are listed only
     * @param offset   offset of the subtable inside the program
     * @param unicode  whether platform/encoding promise Unicode
     */
    public record CmapSubtable(int platform, int encoding, int format, long offset,
                               boolean unicode) {

        /** How the module names a subtable: {@code "platform 3/encoding 1"}. */
        public String source() {
            return "platform " + platform + "/encoding " + encoding;
        }
    }

    private final List<String> notes;
    /** Sentences only the {@code program} block shows - see {@link #remark(String)}. */
    private final List<String> remarks = new ArrayList<>();
    /** The decoded program - read in place, never copied, never executed. */
    private byte[] data = new byte[0];
    private final List<Table> tables = new ArrayList<>();
    private final Map<String, Table> usable = new LinkedHashMap<>();
    private final List<CmapSubtable> subtables = new ArrayList<>();
    private final Map<Integer, Integer> glyphToUnicode = new HashMap<>();
    private final Set<Integer> ambiguousGlyphs = new LinkedHashSet<>();

    private String sfntVersion;
    private String kind;
    private boolean usableProgram;
    private int declaredTables;
    private boolean tablesCut;
    private Integer unitsPerEm;
    private Integer indexToLocFormat;
    private int[] fontBBox;
    private Integer numberOfHMetrics;
    private Integer ascent;
    private Integer descent;
    private Integer numGlyphs;
    private CmapSubtable unicodeTable;
    private CmapSubtable selectionTable;
    private long cmapEntries;
    private boolean cmapCut;
    private long glyphsMapped;
    private long glyphsBeyondProgram;
    private boolean hmtxNoted;
    private boolean locaNoted;
    private boolean beyondNoted;

    private PdfTrueTypeProgram(List<String> notes) {
        this.notes = notes == null ? new ArrayList<>() : notes;
    }

    /**
     * Reads a program.
     *
     * @param bytes the decoded bytes of a {@code /FontFile2} (or of a sfnt {@code /FontFile3})
     * @param notes note/warning sink (never null)
     * @return the reader - never null; what it could not read is in {@link #notes()}
     */
    public static PdfTrueTypeProgram read(byte[] bytes, List<String> notes) {
        PdfTrueTypeProgram program = new PdfTrueTypeProgram(notes);
        program.data = bytes == null ? new byte[0] : bytes;
        program.readAll();
        return program;
    }

    // ===================================================================
    // Reading
    // ===================================================================

    private void readAll() {
        byte[] bytes = data;
        if (bytes.length < 12) {
            note(NOTE_TABLE_UNREADABLE, "the embedded program holds " + bytes.length
                    + " bytes, which is not even an sfnt header: no table is readable");
            return;
        }
        sfntVersion = String.format(Locale.ROOT, "%08x", u32(bytes, 0));
        kind = 0x4f54544fL == u32(bytes, 0) ? KIND_OPENTYPE : KIND_TRUETYPE;
        declaredTables = (int) u16(bytes, 4);
        if (declaredTables <= 0) {
            note(NOTE_TABLE_UNREADABLE, "the table directory of the embedded program declares "
                    + declaredTables + " tables: nothing can be read from it");
            return;
        }
        int read = Math.min(declaredTables, MAX_TABLES);
        List<String> broken = new ArrayList<>();
        for (int i = 0; i < read; i++) {
            int entry = 12 + i * 16;
            if (entry + 16 > bytes.length) {
                note(NOTE_TABLE_UNREADABLE, "the table directory stops inside entry " + (i + 1)
                        + " of the " + declaredTables + " it declares (the program holds "
                        + bytes.length + " bytes): the tables behind it are not listed");
                break;
            }
            String tag = tag(bytes, entry);
            long offset = u32(bytes, entry + 8);
            long length = u32(bytes, entry + 12);
            boolean fits = offset >= 0 && offset + length <= bytes.length;
            Table table = new Table(tag, offset, length, fits && length > 0);
            tables.add(table);
            if (!fits) {
                broken.add(tag);
            } else if (length > 0) {
                usable.putIfAbsent(tag, table);
            }
        }
        if (declaredTables > read) {
            tablesCut = true;
            note(NOTE_TABLES_CUT, "the table directory of the embedded program names "
                    + declaredTables + " tables, " + read + " were read (maxProgramTables): the"
                    + " tables behind the cut are not listed");
        }
        if (!broken.isEmpty()) {
            note(NOTE_TABLE_UNREADABLE, "the table directory of the embedded program points behind"
                    + " its " + bytes.length + " bytes: " + listNames(broken) + " - the"
                    + " values of those tables are unknown");
        }
        usableProgram = !usable.isEmpty();
        readHead(bytes);
        readHhea(bytes);
        readMaxp(bytes);
        readCmap(bytes);
    }

    private void readHead(byte[] bytes) {
        Table table = usable.get("head");
        if (table == null) {
            note(NOTE_TABLE_UNREADABLE, "the embedded program has no readable head table: the unit"
                    + " of its metrics (unitsPerEm) is not known, so no width of it can be"
                    + " converted");
            return;
        }
        if (table.length() < 54) {
            note(NOTE_TABLE_UNREADABLE, "the head table of the embedded program holds "
                    + table.length() + " bytes instead of 54: its metrics are not read");
            return;
        }
        long base = table.offset();
        int units = (int) u16(bytes, base + 18);
        if (units > 0) {
            unitsPerEm = units;
        } else {
            note(NOTE_TABLE_UNREADABLE, "the head table of the embedded program says unitsPerEm 0:"
                    + " a width of it cannot be brought onto the 1000-unit base");
        }
        indexToLocFormat = (int) s16(bytes, base + 50);
        fontBBox = new int[]{s16(bytes, base + 36), s16(bytes, base + 38), s16(bytes, base + 40),
                s16(bytes, base + 42)};
    }

    private void readHhea(byte[] bytes) {
        Table table = usable.get("hhea");
        if (table == null || table.length() < 36) {
            return;
        }
        long base = table.offset();
        ascent = (int) s16(bytes, base + 4);
        descent = (int) s16(bytes, base + 6);
        numberOfHMetrics = (int) u16(bytes, base + 34);
    }

    private void readMaxp(byte[] bytes) {
        Table table = usable.get("maxp");
        if (table == null || table.length() < 6) {
            return;
        }
        numGlyphs = (int) u16(bytes, table.offset() + 4);
    }

    private void readCmap(byte[] bytes) {
        Table table = usable.get("cmap");
        if (table == null) {
            remark(NOTE_NO_CMAP, "the embedded program has no /cmap table: a glyph index of it"
                    + " stays an index, so only a /ToUnicode can say what its codes mean");
            return;
        }
        long base = table.offset();
        if (table.length() < 4 || base + 4 > bytes.length) {
            note(NOTE_TABLE_UNREADABLE, "the /cmap table of the embedded program is too short for"
                    + " its header: no subtable is read");
            return;
        }
        long count = u16(bytes, base + 2);
        for (int i = 0; i < count; i++) {
            long entry = base + 4 + i * 8L;
            if (entry + 8 > bytes.length || entry + 8 > base + table.length()) {
                note(NOTE_TABLE_UNREADABLE, "the /cmap table of the embedded program lists " + count
                        + " subtables and ends before number " + (i + 1) + ": the rest is not read");
                break;
            }
            int platform = (int) u16(bytes, entry);
            int encoding = (int) u16(bytes, entry + 2);
            long offset = base + u32(bytes, entry + 4);
            int format = offset + 2 <= bytes.length ? (int) u16(bytes, offset) : -1;
            boolean readable = (format == 0 || format == 4 || format == 6 || format == 12)
                    && offset >= base && offset + 6 <= bytes.length
                    && offset < base + table.length();
            CmapSubtable subtable = new CmapSubtable(platform, encoding, format, offset,
                    unicodeEncoding(platform, encoding) > 0);
            subtables.add(subtable);
            if (!readable) {
                continue;
            }
            if (subtable.unicode() && (unicodeTable == null || better(subtable, unicodeTable))) {
                unicodeTable = subtable;
            }
            if (selectionTable == null || better(subtable, selectionTable)) {
                selectionTable = subtable;
            }
        }
        if (unicodeTable == null) {
            if (!subtables.isEmpty()) {
                note(NOTE_CMAP_NOT_UNICODE, "the /cmap of the embedded program holds "
                        + subtables.size() + " subtable(s), none of them a Unicode"
                        + " platform/encoding (" + subtableNames() + "): its values select glyphs,"
                        + " they are not characters");
            }
            return;
        }
        buildInverse(unicodeTable);
    }

    /** Builds glyph → smallest code point out of the preferred Unicode subtable. */
    private void buildInverse(CmapSubtable subtable) {
        long[] holder = new long[1];
        boolean[] cut = new boolean[1];
        forEachMapping(subtable, (code, glyph) -> {
            if (holder[0] >= MAX_CMAP_ENTRIES) {
                cut[0] = true;
                return false;
            }
            holder[0]++;
            Integer known = glyphToUnicode.get(glyph);
            if (known == null) {
                glyphToUnicode.put(glyph, (int) code);
                glyphsMapped++;
            } else {
                // The walk runs in ascending code order, so what is stored is the smallest.
                ambiguousGlyphs.add(glyph);
            }
            if (numGlyphs != null && glyph >= numGlyphs) {
                glyphsBeyondProgram++;
            }
            return true;
        });
        cmapEntries = holder[0];
        cmapCut = cut[0];
        if (cmapCut) {
            note(NOTE_CMAP_PARTIAL, "the /cmap of the embedded program holds more than "
                    + MAX_CMAP_ENTRIES + " mappings (maxCmapEntries): the glyph indices behind the"
                    + " cut stay unmapped instead of being guessed");
        }
    }

    /** One {@code cmap} entry: a code point and the glyph it selects. */
    private interface MappingVisitor {
        /** @return false to stop the walk */
        boolean visit(long code, int glyph);
    }

    /** Walks the entries of a subtable in ascending code order, bounds-checked at every read. */
    private void forEachMapping(CmapSubtable subtable, MappingVisitor visitor) {
        byte[] bytes = data;
        long base = subtable.offset();
        switch (subtable.format()) {
            case 0: {
                long available = bytes.length - base - 6;
                for (long code = 0; code < 256 && code < available; code++) {
                    int glyph = (int) u8(bytes, base + 6 + code);
                    if (glyph != 0 && !visitor.visit(code, glyph)) {
                        return;
                    }
                }
                return;
            }
            case 6: {
                if (base + 10 > bytes.length) {
                    return;
                }
                long first = u16(bytes, base + 6);
                long count = u16(bytes, base + 8);
                for (long i = 0; i < count; i++) {
                    long at = base + 10 + i * 2;
                    if (at + 2 > bytes.length) {
                        return;
                    }
                    int glyph = (int) u16(bytes, at);
                    if (glyph != 0 && !visitor.visit(first + i, glyph)) {
                        return;
                    }
                }
                return;
            }
            case 12: {
                if (base + 16 > bytes.length) {
                    return;
                }
                long groups = u32(bytes, base + 12);
                for (long group = 0; group < groups; group++) {
                    long entry = base + 16 + group * 12L;
                    if (entry + 12 > bytes.length) {
                        return;
                    }
                    long from = u32(bytes, entry);
                    long to = u32(bytes, entry + 4);
                    long start = u32(bytes, entry + 8);
                    if (to < from || to - from > MAX_CMAP_ENTRIES) {
                        continue;                    // a group of four billion codes is not a font
                    }
                    for (long code = from; code <= to; code++) {
                        long glyph = start + (code - from);
                        if (glyph != 0 && !visitor.visit(code, (int) (glyph & 0xFFFFL))) {
                            return;
                        }
                    }
                }
                return;
            }
            case 4: {
                if (base + 16 > bytes.length) {
                    return;
                }
                long segments = u16(bytes, base + 6) / 2;
                if (segments <= 0 || segments > 0x8000) {
                    return;
                }
                long starts = base + 16 + segments * 2;
                long deltas = starts + segments * 2;
                long ranges = deltas + segments * 2;
                for (long seg = 0; seg < segments; seg++) {
                    if (base + 14 + seg * 2 + 2 > bytes.length || ranges + seg * 2 + 2 > bytes.length
                            || starts + seg * 2 + 2 > bytes.length
                            || deltas + seg * 2 + 2 > bytes.length) {
                        return;
                    }
                    long end = u16(bytes, base + 14 + seg * 2);
                    long start = u16(bytes, starts + seg * 2);
                    int delta = s16(bytes, deltas + seg * 2);
                    int rangeOffset = (int) u16(bytes, ranges + seg * 2);
                    if (start == 0xFFFF || start > end) {
                        continue;                    // the segment every format 4 closes with
                    }
                    long rangeAt = ranges + seg * 2;
                    for (long code = start; code <= end; code++) {
                        int glyph;
                        if (rangeOffset == 0) {
                            glyph = (int) ((code + delta) & 0xFFFFL);
                        } else {
                            long at = rangeAt + rangeOffset + (code - start) * 2;
                            if (at + 2 > bytes.length) {
                                break;
                            }
                            int found = (int) u16(bytes, at);
                            glyph = found == 0 ? 0 : (int) ((found + delta) & 0xFFFFL);
                        }
                        if (glyph != 0 && !visitor.visit(code, glyph)) {
                            return;
                        }
                    }
                }
                return;
            }
            default:
                return;
        }
    }

    // ===================================================================
    // Preferences
    // ===================================================================

    /**
     * Whether a platform/encoding pair promises Unicode, and how far that promise reaches. The
     * order is the one a layout engine uses: a subtable that reaches the supplementary planes
     * answers more glyphs than a BMP-only one, whatever platform wrote it.
     * <ol>
     *   <li>{@code 3/10}, {@code 0/6}, {@code 0/4} - the full-repertoire ids, normally paired with a
     *       format-12 subtable (the CID fixture's own pair is {@code 0/4} + format 12),</li>
     *   <li>{@code 3/1} - the Windows BMP table, the one most producers ship,</li>
     *   <li>{@code 0/0}, {@code 0/2}, {@code 0/3}, {@code 3/0}, {@code 3/2}, {@code 3/4},
     *       {@code 3/6}, {@code 3/768}, platform 2 - Unicode in an older or rarer wrapping
     *       ({@code 3/768} is the "UCS 2.0 full repertoire" id the CID fixture lists beside its
     *       platform-0 pair),</li>
     *   <li>everything else - and that is the point: platform 1 (Macintosh Roman, or the
     *       "user-defined" id 256 a ReportLab subset ships) and {@code 3/5} (a symbol font) hold
     *       values that are <em>not</em> code points, and the variation-sequence ids
     *       ({@code 0/5}, {@code 0/7}) are no character table either. Calling one of them a
     *       character would be an invention.</li>
     * </ol>
     *
     * @return a preference score, 0 for "these values are not Unicode"
     */
    private static int unicodeEncoding(int platform, int encoding) {
        if (platform == 3 || platform == 0) {
            switch (encoding) {
                case 10:                                     // full repertoire
                case 6:
                case 4:
                    return 4;
                case 1:                                      // the BMP table
                    return 3;
                case 0:                                      // "Unicode" without a version promise
                case 2:
                case 3:
                    return 2;
                default:
                    return platform == 3 && (encoding == 0 || encoding == 768) ? 2 : 0;
            }
        }
        return platform == 2 ? 2 : 0;              // platform 1 (Mac) and 4 are refused here
    }

    /** Preference between two readable subtables: the Unicode promise first, then the format. */
    private static boolean better(CmapSubtable candidate, CmapSubtable current) {
        int score = unicodeEncoding(candidate.platform(), candidate.encoding());
        int known = unicodeEncoding(current.platform(), current.encoding());
        if (score != known) {
            return score > known;
        }
        return formatRank(candidate.format()) > formatRank(current.format());
    }

    /** Format 12 reaches beyond U+FFFF, format 4 reaches further than 6, 6 further than 0. */
    private static int formatRank(int format) {
        switch (format) {
            case 12:
                return 4;
            case 4:
                return 3;
            case 6:
                return 2;
            case 0:
                return 1;
            default:
                return 0;
        }
    }

    // ===================================================================
    // What the program says
    // ===================================================================

    /** Whether a table directory could be read at all. */
    public boolean usable() {
        return usableProgram;
    }

    /** {@code truetype}, or {@code opentype} when the sfnt carries a {@code CFF } table. */
    public String kind() {
        return kind == null ? PdfFontProgram.KIND_UNKNOWN : kind;
    }

    /** The sfnt version as eight hex digits ({@code "00010000"}, {@code "4f54544f"} for OTTO). */
    public String sfntVersion() {
        return sfntVersion;
    }

    /** Tags of the directory, in the order the file lists them. */
    public List<String> tableTags() {
        List<String> out = new ArrayList<>(tables.size());
        for (Table table : tables) {
            out.add(table.tag());
        }
        return out;
    }

    /** The directory entries that were read. */
    public List<Table> tables() {
        return List.copyOf(tables);
    }

    /** Whether {@link #MAX_TABLES} stopped the directory. */
    public boolean tablesCut() {
        return tablesCut;
    }

    /** Tables the directory declares. */
    public int declaredTables() {
        return declaredTables;
    }

    /** Whether the program holds a readable table of that tag. */
    public boolean hasTable(String tag) {
        return usable.containsKey(tag);
    }

    /** {@code head.unitsPerEm}, or null. */
    public Integer unitsPerEm() {
        return unitsPerEm;
    }

    /** {@code head.indexToLocFormat} (0 = short {@code loca}), or null. */
    public Integer indexToLocFormat() {
        return indexToLocFormat;
    }

    /** {@code head} bounding box {@code [xMin, yMin, xMax, yMax]} in font units, or null. */
    public int[] fontBBox() {
        return fontBBox == null ? null : fontBBox.clone();
    }

    /** {@code hhea.numberOfHMetrics}, or null. */
    public Integer numberOfHMetrics() {
        return numberOfHMetrics;
    }

    /** {@code hhea.ascender} in font units, or null. */
    public Integer ascent() {
        return ascent;
    }

    /** {@code hhea.descender} in font units, or null. */
    public Integer descent() {
        return descent;
    }

    /** {@code maxp.numGlyphs}, or null. */
    public Integer numGlyphs() {
        return numGlyphs;
    }

    /** Whether {@code hmtx} can be asked for a width at all. */
    public boolean hasMetrics() {
        return usable.containsKey("hmtx") && numberOfHMetrics != null && numberOfHMetrics > 0
                && unitsPerEm != null && unitsPerEm > 0;
    }

    /** The {@code cmap} subtables, as the file lists them. */
    public List<CmapSubtable> cmapSubtables() {
        return List.copyOf(subtables);
    }

    /** Whether a Unicode {@code cmap} was read, so glyph indices can become characters. */
    public boolean canMapGlyphs() {
        return unicodeTable != null;
    }

    /** The subtable the mapping reads, or null. */
    public CmapSubtable unicodeCmap() {
        return unicodeTable;
    }

    /** How the mapping names its source: {@code "format12"}, {@code "format4"}, … or null. */
    public String mappingFormat() {
        return unicodeTable == null ? null : "format" + unicodeTable.format();
    }

    /** Entries the inverse table holds. */
    public long cmapEntries() {
        return cmapEntries;
    }

    /** Distinct glyphs the inverse table answers for. */
    public long glyphsMapped() {
        return glyphsMapped;
    }

    /** How many glyphs more than one code point names. */
    public int ambiguousGlyphs() {
        return ambiguousGlyphs.size();
    }

    /** The first glyph that more than one code point names, or null. */
    public Integer firstAmbiguousGlyph() {
        return ambiguousGlyphs.isEmpty() ? null : ambiguousGlyphs.iterator().next();
    }

    /** Code points of the {@code cmap} that select a glyph the program does not hold. */
    public long glyphsBeyondProgram() {
        return glyphsBeyondProgram;
    }

    /** Whether {@link #MAX_CMAP_ENTRIES} stopped the inverse table. */
    public boolean cmapCut() {
        return cmapCut;
    }

    /**
     * The character a glyph index means according to the program's own Unicode {@code cmap}.
     *
     * @param glyph the glyph index (the target of a CID through {@code /CIDToGIDMap})
     * @return the smallest code point naming that glyph, or null when the program names none
     */
    public Integer unicodeOf(int glyph) {
        if (!holdsGlyph(glyph)) {
            // A subset's /cmap names glyphs the subset dropped (the CID fixture's table does it
            // 3353 times); those entries cannot be what a drawn glyph means. The count stands in the
            // program block as cmapBeyondGlyphs.
            return null;
        }
        return glyphToUnicode.get(glyph);
    }

    /** Whether more than one code point names that glyph (the smallest one is the answer). */
    public boolean isAmbiguous(int glyph) {
        return ambiguousGlyphs.contains(glyph);
    }

    /** The subtable the code → glyph lookup uses, or null. */
    public CmapSubtable selectionCmap() {
        return selectionTable;
    }

    /**
     * The glyph a simple font's byte code selects, read from the program's {@code cmap}. This is
     * selection, not meaning - which is why a user-defined subtable is good enough here (and is
     * exactly what a viewer looks at).
     *
     * @param code the byte code of a string
     * @return the glyph index, or null when no readable subtable names one
     */
    public Integer glyphOfCode(long code) {
        return selectionTable == null || code < 0 ? null : lookupForward(code, selectionTable);
    }

    /** Forward lookup in one subtable. */
    private Integer lookupForward(long code, CmapSubtable subtable) {
        byte[] bytes = data;
        long base = subtable.offset();
        switch (subtable.format()) {
            case 0: {
                if (code > 255 || base + 7 + code > bytes.length) {
                    return null;
                }
                int glyph = (int) u8(bytes, base + 6 + code);
                return glyph == 0 ? null : glyph;
            }
            case 6: {
                if (base + 10 > bytes.length) {
                    return null;
                }
                long first = u16(bytes, base + 6);
                long count = u16(bytes, base + 8);
                if (code < first || code >= first + count) {
                    return null;
                }
                long at = base + 10 + (code - first) * 2;
                if (at + 2 > bytes.length) {
                    return null;
                }
                int glyph = (int) u16(bytes, at);
                return glyph == 0 ? null : glyph;
            }
            case 12: {
                if (base + 16 > bytes.length) {
                    return null;
                }
                long groups = u32(bytes, base + 12);
                for (long group = 0; group < groups; group++) {
                    long entry = base + 16 + group * 12L;
                    if (entry + 12 > bytes.length) {
                        return null;
                    }
                    long from = u32(bytes, entry);
                    long to = u32(bytes, entry + 4);
                    if (code < from || code > to) {
                        continue;
                    }
                    long glyph = u32(bytes, entry + 8) + (code - from);
                    return glyph == 0 ? null : (int) (glyph & 0xFFFFL);
                }
                return null;
            }
            case 4: {
                if (base + 16 > bytes.length) {
                    return null;
                }
                long segments = u16(bytes, base + 6) / 2;
                if (segments <= 0 || segments > 0x8000) {
                    return null;
                }
                long starts = base + 16 + segments * 2;
                long deltas = starts + segments * 2;
                long ranges = deltas + segments * 2;
                for (long seg = 0; seg < segments; seg++) {
                    if (base + 14 + seg * 2 + 2 > bytes.length || ranges + seg * 2 + 2 > bytes.length
                            || starts + seg * 2 + 2 > bytes.length
                            || deltas + seg * 2 + 2 > bytes.length) {
                        return null;
                    }
                    if (code > u16(bytes, base + 14 + seg * 2)) {
                        continue;
                    }
                    long start = u16(bytes, starts + seg * 2);
                    if (code < start) {
                        return null;
                    }
                    int delta = s16(bytes, deltas + seg * 2);
                    int rangeOffset = (int) u16(bytes, ranges + seg * 2);
                    if (rangeOffset == 0) {
                        int glyph = (int) ((code + delta) & 0xFFFFL);
                        return glyph == 0 ? null : glyph;
                    }
                    long at = ranges + seg * 2 + rangeOffset + (code - start) * 2;
                    if (at + 2 > bytes.length) {
                        return null;
                    }
                    int found = (int) u16(bytes, at);
                    int glyph = found == 0 ? 0 : (int) ((found + delta) & 0xFFFFL);
                    return glyph == 0 ? null : glyph;
                }
                return null;
            }
            default:
                return null;
        }
    }

    /**
     * What the program says about one glyph: its advance (in its own units and on the 1000-unit
     * base), its left side bearing and the bounding box {@code glyf} writes for it.
     *
     * @param glyph the glyph index
     * @return the metrics - every field null that the program does not answer
     */
    public Metrics metricsOf(int glyph) {
        Integer advance = advanceWidth(glyph);
        Number text = advance == null ? null : toTextUnits(advance);
        Integer side = leftSideBearing(glyph);
        int[] box = boundingBox(glyph);
        return new Metrics(advance, text, side, box, box == null && isComposite(glyph),
                box == null && !isComposite(glyph) && isEmpty(glyph));
    }

    /**
     * True when the program can hold that glyph at all. {@code maxp.numGlyphs} is the answer, and it
     * matters because an identity {@code /CIDToGIDMap} turns every CID into a glyph index: a CID of
     * 6000 in a 93-glyph subset names nothing, and the tables would happily answer "the last
     * advance" if they were asked.
     *
     * @param glyph the glyph index
     * @return whether the index is inside the font (true when {@code numGlyphs} is unknown)
     */
    public boolean holdsGlyph(int glyph) {
        if (glyph < 0) {
            return false;
        }
        if (numGlyphs == null) {
            return true;
        }
        if (glyph < numGlyphs) {
            return true;
        }
        if (!beyondNoted) {
            beyondNoted = true;
            note(NOTE_GLYPH_BEYOND, "the embedded program holds " + numGlyphs
                    + " glyph(s), so the glyph index asked for is beyond it: neither its width nor"
                    + " its character is known (a /CIDToGIDMap that is not /Identity, or a CID the"
                    + " subset does not hold, is the usual reason)");
        }
        return false;
    }

    /** {@code hmtx} advance in the program's own units. Beyond {@code numberOfHMetrics} the last
     * advance applies - that is the table's compression, not a gap. */
    public Integer advanceWidth(int glyph) {
        Table table = usable.get("hmtx");
        if (table == null || numberOfHMetrics == null || numberOfHMetrics <= 0
                || !holdsGlyph(glyph)) {
            return null;
        }
        long at = table.offset() + (long) Math.min(glyph, numberOfHMetrics - 1) * 4;
        if (at + 2 > table.offset() + table.length() || at + 2 > data.length) {
            noteHmtxShort();
            return null;
        }
        return (int) u16(data, at);
    }

    /**
     * {@code hmtx} left side bearing. Past {@code numberOfHMetrics} only the bearing array runs on,
     * which is why an advance can be known where a bearing is not.
     *
     * @param glyph the glyph index
     * @return the bearing in font units, or null
     */
    public Integer leftSideBearing(int glyph) {
        Table table = usable.get("hmtx");
        if (table == null || numberOfHMetrics == null || numberOfHMetrics <= 0
                || !holdsGlyph(glyph)) {
            return null;
        }
        long end = table.offset() + table.length();
        long at = glyph < numberOfHMetrics
                ? table.offset() + (long) glyph * 4 + 2
                : table.offset() + numberOfHMetrics * 4L + (glyph - numberOfHMetrics) * 2L;
        if (at + 2 > end || at + 2 > data.length) {
            return null;
        }
        return (int) s16(data, at);
    }

    /**
     * The {@code glyf} bounding box of a glyph, read from the first ten bytes of its description.
     *
     * @param glyph the glyph index
     * @return {@code [xMin, yMin, xMax, yMax]} in font units, or null (no description, a composite
     *         glyph, or a {@code loca} that does not reach it)
     */
    public int[] boundingBox(int glyph) {
        long[] range = glyphRange(glyph);
        Table glyf = usable.get("glyf");
        if (range == null || glyf == null || range[0] == range[1]) {
            return null;
        }
        long at = glyf.offset() + range[0];
        if (s16(data, at) < 0 || at + 10 > glyf.offset() + glyf.length()) {
            return null;                                       // composite, or a truncated outline
        }
        return new int[]{s16(data, at + 2), s16(data, at + 4), s16(data, at + 6), s16(data, at + 8)};
    }

    /** True when the glyph is composed of other glyphs (its box would have to be computed). */
    public boolean isComposite(int glyph) {
        long[] range = glyphRange(glyph);
        Table glyf = usable.get("glyf");
        return range != null && glyf != null && range[0] != range[1]
                && s16(data, glyf.offset() + range[0]) < 0;
    }

    /** True when the glyph holds no description at all ({@code loca} says zero length). */
    public boolean isEmpty(int glyph) {
        long[] range = glyphRange(glyph);
        return range != null && range[0] == range[1];
    }

    /** The byte range of one glyph inside {@code glyf}, or null when it cannot be said. */
    private long[] glyphRange(int glyph) {
        Table loca = usable.get("loca");
        Table glyf = usable.get("glyf");
        if (loca == null || glyf == null || indexToLocFormat == null || glyph < 0) {
            return null;
        }
        if (indexToLocFormat != 0 && indexToLocFormat != 1) {
            if (!locaNoted) {
                locaNoted = true;
                note(NOTE_LOCA_SHORT, "the head table of the embedded program says"
                        + " indexToLocFormat " + indexToLocFormat + ", which is neither of the two"
                        + " forms: no glyph position is read");
            }
            return null;
        }
        int step = indexToLocFormat == 0 ? 2 : 4;
        if (!holdsGlyph(glyph)) {
            return null;
        }
        long entries = numGlyphs == null ? glyph + 2L : (long) numGlyphs + 1;
        if (loca.length() < entries * step) {
            if (!locaNoted) {
                locaNoted = true;
                note(NOTE_LOCA_SHORT, "the loca table of the embedded program holds "
                        + loca.length() + " bytes, which is less than the " + (entries * step)
                        + " its " + entries + " glyph positions need: the glyphs behind it have no"
                        + " bounding box");
            }
            return null;
        }
        long at = loca.offset() + (long) glyph * step;
        if (at + step > loca.offset() + loca.length() || at + step > data.length) {
            return null;
        }
        long from = indexToLocFormat == 0 ? u16(data, at) * 2L : u32(data, at);
        long to = indexToLocFormat == 0 ? u16(data, at + step) * 2L : u32(data, at + step);
        if (from > to || glyf.offset() + to > glyf.offset() + glyf.length()
                || glyf.offset() + to > (long) data.length) {
            if (!locaNoted) {
                locaNoted = true;
                note(NOTE_LOCA_SHORT, "the loca table of the embedded program points to glyph bytes"
                        + " behind its glyf table: the glyphs at that position have no bounding"
                        + " box");
            }
            return null;
        }
        return new long[]{from, to};
    }

    /**
     * A width of this program on the base the dictionary uses: {@code 1000 * advance / unitsPerEm}.
     * A whole result stays a whole number, so a font of 1000 units per em answers exactly as a
     * {@code /W} entry would and a 2048-unit font keeps its fraction.
     *
     * @param advanceWidth the advance in the program's units
     * @return the width on the 1000-unit base, or null when {@code unitsPerEm} is unknown
     */
    public Number toTextUnits(int advanceWidth) {
        if (unitsPerEm == null || unitsPerEm <= 0) {
            return null;
        }
        double converted = (double) TEXT_UNITS * advanceWidth / unitsPerEm;
        double rounded = Math.round(converted * 10_000d) / 10_000d;
        if (rounded == Math.rint(rounded)) {
            // A whole number stays a whole number - written as 612, not 612.0, because that is how
            // a dictionary names the same width. (A conditional expression would promote both
            // branches to double, so this is deliberately two statements.)
            return Long.valueOf((long) rounded);
        }
        return Double.valueOf(rounded);
    }

    /** What {@code hmtx} says about a glyph's width, on the 1000-unit base, or null. */
    public Number advanceInTextUnits(int glyph) {
        Integer advance = advanceWidth(glyph);
        return advance == null ? null : toTextUnits(advance);
    }

    private void noteHmtxShort() {
        if (hmtxNoted) {
            return;
        }
        hmtxNoted = true;
        Table table = usable.get("hmtx");
        note(NOTE_HMTX_SHORT, "the hmtx table of the embedded program holds "
                + (table == null ? 0 : table.length()) + " bytes, which is less than the "
                + numberOfHMetrics + " advances its numberOfHMetrics announces: the widths behind"
                + " it are unknown");
    }

    // ===================================================================
    // The program block of pdf.font()
    // ===================================================================

    /**
     * The block: what the file is and what its tables say.
     *
     * @param mode     {@code "tables"} adds the table directory, anything else is the summary
     * @param maxItems entries listed per list
     * @return the block (its own {@code notes}, because its sentences are about the program)
     */
    public Map<String, Object> block(String mode, int maxItems) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind());
        if (sfntVersion != null) {
            out.put("sfntVersion", sfntVersion);
        }
        out.put("tableCount", (long) tables.size());
        int limit = Math.max(1, maxItems);
        List<Object> shown = new ArrayList<>();
        for (String tag : tableTags()) {
            if (shown.size() >= limit) {
                out.put("tablesTruncated", true);
                break;
            }
            shown.add(tag);
        }
        out.put("tables", shown);
        if (tablesCut) {
            out.put("tablesTruncated", true);
        }
        if (unitsPerEm != null) {
            out.put("unitsPerEm", (long) (int) unitsPerEm);
        }
        if (numGlyphs != null) {
            out.put("numGlyphs", (long) (int) numGlyphs);
        }
        if (indexToLocFormat != null) {
            out.put("indexToLocFormat", (long) (int) indexToLocFormat);
        }
        if (numberOfHMetrics != null) {
            out.put("numberOfHMetrics", (long) (int) numberOfHMetrics);
        }
        if (ascent != null) {
            out.put("ascent", (long) (int) ascent);
        }
        if (descent != null) {
            out.put("descent", (long) (int) descent);
        }
        if (fontBBox != null) {
            List<Object> box = new ArrayList<>(4);
            for (int value : fontBBox) {
                box.add((long) value);
            }
            out.put("fontBBox", box);
        }
        if (hasMetrics()) {
            out.put("widths", "hmtx");
        }
        out.put("cmap", canMapGlyphs());
        if (unicodeTable != null) {
            out.put("cmapSource", unicodeTable.source());
            out.put("cmapFormat", (long) unicodeTable.format());
            out.put("cmapEntries", cmapEntries);
            out.put("cmapGlyphs", glyphsMapped);
            if (ambiguousGlyphs() > 0) {
                out.put("cmapAmbiguous", (long) ambiguousGlyphs());
            }
            if (glyphsBeyondProgram > 0) {
                out.put("cmapBeyondGlyphs", glyphsBeyondProgram);
            }
        }
        if (!subtables.isEmpty()) {
            out.put("cmapSubtables", (long) subtables.size());
        }
        if (selectionTable != null && selectionTable != unicodeTable) {
            out.put("cmapSelection", selectionTable.source() + ", format "
                    + selectionTable.format());
        }
        if ("tables".equals(mode)) {
            List<Object> directory = new ArrayList<>();
            for (Table table : tables) {
                if (directory.size() >= limit) {
                    out.put("directoryTruncated", true);
                    break;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("tag", table.tag());
                item.put("offset", table.offset());
                item.put("length", table.length());
                if (!table.usable()) {
                    item.put("usable", false);
                }
                directory.add(item);
            }
            out.put("tableDirectory", directory);
        }
        if (!allNotes().isEmpty()) {
            out.put("notes", new ArrayList<Object>(allNotes()));
        }
        return out;
    }

    /** The sentences this reader put into the caller's note sink. */
    public List<String> notes() {
        return List.copyOf(notes);
    }

    /**
     * Every sentence about this program: the ones the caller heard and the ones only the block
     * shows (what a program lacks is said once, in the sheet that opened it).
     */
    public List<String> allNotes() {
        List<String> out = new ArrayList<>(notes);
        for (String remark : remarks) {
            if (!out.contains(remark)) {
                out.add(remark);
            }
        }
        return List.copyOf(out);
    }

    // ===================================================================
    // Helpers
    // ===================================================================

    private void note(String key, String sentence) {
        if (!notes.contains(sentence)) {
            notes.add(sentence);
        }
    }

    /**
     * A sentence for the block only. What a program <em>lacks</em> is a fact about it that the
     * caller does not need twice: the mapping chain already says "no /cmap this module can read"
     * when a code stayed a code, so the sheet says it and the page does not repeat it.
     */
    private void remark(String key, String sentence) {
        if (!remarks.contains(sentence)) {
            remarks.add(sentence);
        }
    }

    private static String listNames(List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size() && i < 3; i++) {
            if (i > 0) {
                sb.append(i + 1 == names.size() ? " and " : ", ");
            }
            sb.append(names.get(i));
        }
        if (names.size() > 3) {
            sb.append(" and ").append(names.size() - 3).append(" more");
        }
        return sb.toString();
    }

    /** The subtables the reader saw, for the sentence that refuses them all. */
    private String subtableNames() {
        List<String> names = new ArrayList<>();
        for (CmapSubtable subtable : subtables) {
            names.add(subtable.platform() + "/" + subtable.encoding() + " format "
                    + subtable.format());
        }
        return String.join(", ", names);
    }

    private static long u8(byte[] data, long at) {
        return at >= 0 && at < data.length ? data[(int) at] & 0xFF : 0;
    }

    private static long u16(byte[] data, long at) {
        if (at < 0 || at + 2 > data.length) {
            return 0;
        }
        return ((data[(int) at] & 0xFFL) << 8) | (data[(int) at + 1] & 0xFFL);
    }

    private static int s16(byte[] data, long at) {
        long value = u16(data, at);
        return (int) (value >= 0x8000 ? value - 0x10000 : value);
    }

    private static long u32(byte[] data, long at) {
        if (at < 0 || at + 4 > data.length) {
            return 0;
        }
        return ((data[(int) at] & 0xFFL) << 24) | ((data[(int) at + 1] & 0xFFL) << 16)
                | ((data[(int) at + 2] & 0xFFL) << 8) | (data[(int) at + 3] & 0xFFL);
    }

    private static String tag(byte[] data, int at) {
        StringBuilder sb = new StringBuilder(4);
        for (int i = 0; i < 4; i++) {
            char c = (char) (data[at + i] & 0xFF);
            sb.append(c >= 0x20 && c < 0x7F ? c : '?');
        }
        return sb.toString();
    }
}
