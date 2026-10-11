package org.rogmann.mcp2sdk.pdf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The widths a font dictionary gives, as a <em>query</em> view - the counterpart of the listing in
 * {@link PdfFont}, which answers "what does {@code /W} look like" while this answers "how wide is
 * code 72".
 * <p>
 * Both read the dictionary only: {@code /W} of a CIDFont in its three forms ({@code c [w…]},
 * {@code cFirst cLast w} and {@code c []}, which means "the {@code /DW} default applies"), and
 * {@code /FirstChar}+{@code /Widths} of a simple font. A width the dictionary does not name reads
 * as {@code null} - not as 0 and not as the descriptor's {@code /MaxWidth}; an embedded program's
 * {@code hmtx} or CFF {@code defaultWidthX} would be the next source, and reading a font program is
 * a step of its own ({@code pdf.status().notImplemented}).
 * </p>
 * <p>
 * A region of a {@code /W} array is expanded into the query map (a region of 65 000 codes is
 * counted up to {@link PdfFont#MAX_WIDTH_ENTRIES}), so a lookup costs one hash access instead of a
 * walk over the array per character.
 * </p>
 */
public final class PdfFontWidths {

    /** Widths come from the CIDFont's {@code /W}. */
    public static final String SOURCE_W = "W";

    /** Widths come from {@code /FirstChar}+{@code /Widths}. */
    public static final String SOURCE_WIDTHS = "Widths";

    /** Only {@code /DW} applies (a CIDFont without {@code /W}). */
    public static final String SOURCE_DW = "DW";

    /**
     * Widths come from the embedded program's {@code hmtx} - never the source of this view itself
     * (a dictionary view cannot speak it), but the answer {@link PdfGlyphMapper#widthOf(long)}
     * gives when the dictionary has no value of its own.
     */
    public static final String SOURCE_HMTX = "hmtx";

    /** The dictionary says nothing about widths. */
    public static final String SOURCE_NONE = "none";

    /** Vertical metrics come from the CIDFont's {@code /W2}. */
    public static final String SOURCE_W2 = "W2";

    /** Vertical metrics come from a {@code /DW2} the file names itself. */
    public static final String SOURCE_DW2 = "DW2";

    /**
     * Vertical metrics come from the {@code /DW2} the specification defaults to
     * ({@code [880 -1000]}, PDF 1.7 Table 115) because the file names none.
     */
    public static final String SOURCE_DW2_DEFAULT = "DW2-default";

    /** Nothing - file or specification - says anything about vertical metrics here. */
    public static final String SOURCE_VERTICAL_NONE = "none";

    /** The specification's {@code /DW2} default: v_y = 880, w1_y = -1000. */
    public static final long[] DEFAULT_DW2 = {880L, -1000L};

    /** How many shaped-wrong metric entries one answer names before it only counts. */
    private static final int DEFECTS_LISTED = 3;

    /**
     * One glyph's vertical metrics, in the specification's words (PDF 1.7 5.1.3, Figure 5.5, and
     * 5.6.3, "Glyph Metrics in CIDFonts").
     *
     * @param w1y    the vertical component of the displacement vector {@code w1} of writing mode 1
     *               (its horizontal component is always 0)
     * @param vx     the horizontal component of the position vector {@code v} - half the glyph
     *               width where {@code /W2} names none, because that is what the specification says
     *               the default is
     * @param vy     the vertical component of the position vector {@code v}
     * @param source {@code W2}, {@code DW2}, {@code DW2-default} or {@code none}
     */
    public record Vertical(Number w1y, Number vx, Number vy, String source) {
    }

    private final Map<Long, Number> widths = new LinkedHashMap<>();
    private final Map<Long, Number[]> vertical = new LinkedHashMap<>();
    private final Number[] defaultVertical;
    private final boolean defaultVerticalNamed;
    private final List<String> defects;
    private final List<String> verticalDefects;
    private final Number defaultWidth;
    private final String source;
    private final boolean countedCut;
    private final String note;

    private PdfFontWidths(Map<Long, Number> collected, Number defaultWidth, String source,
                          boolean countedCut, String note) {
        this(collected, Map.of(), null, false, List.of(), List.of(), defaultWidth, source,
                countedCut, note);
    }

    private PdfFontWidths(Map<Long, Number> collected, Map<Long, Number[]> verticalMetrics,
                          Number[] defaultVerticalMetrics, boolean defaultVerticalNamed,
                          List<String> horizontalDefects, List<String> verticalDefects,
                          Number defaultWidth, String source, boolean countedCut, String note) {
        this.widths.putAll(collected);
        this.vertical.putAll(verticalMetrics);
        this.defaultVertical = defaultVerticalMetrics;
        this.defaultVerticalNamed = defaultVerticalNamed;
        this.verticalDefects = List.copyOf(verticalDefects);
        List<String> all = new ArrayList<>(horizontalDefects);
        all.addAll(verticalDefects);
        this.defects = List.copyOf(all);
        this.defaultWidth = defaultWidth;
        this.source = source;
        this.countedCut = countedCut;
        this.note = note;
    }

    /**
     * Reads the widths of one font.
     * @param document the document (arrays may sit behind a reference - pdfTeX does exactly that)
     * @param font the resolved font dictionary
     * @param chain the descendant chain of a {@code /Type0} font (empty for a simple font and for
     *              a {@code CIDFont} that is addressed by its own object number)
     * @return the query view - never null
     */
    public static PdfFontWidths of(PdfDocument document, Map<String, Object> font,
                                  List<PdfFont.ChainEntry> chain) {
        if (chain != null && !chain.isEmpty()) {
            return forCidFont(document, chain);
        }
        if (PdfFont.isCidFontDict(font)) {
            // The dictionary <em>is</em> the CIDFont: /W and /DW are its own entries, and reading
            // /Widths here would answer "none" to a font that lists every CID width.
            return forCidFont(document, List.of(new PdfFont.ChainEntry(null, font)));
        }
        return forSimpleFont(document, font);
    }

    /**
     * A CIDFont: {@code /W} in its three forms, {@code /DW} (default 1000) for everything else, and
     * the vertical pair {@code /W2}/{@code /DW2} (PDF 1.7 5.6.3). The vertical metrics are read
     * because a CIDFont can be shown in either writing mode and the file - not the viewer - says
     * what each glyph displaces to; which set is <em>used</em> is the CMap's {@code WMode} and is
     * reported by {@link PdfCMap#wMode()}.
     */
    private static PdfFontWidths forCidFont(PdfDocument document,
                                            List<PdfFont.ChainEntry> chain) {
        Number defaultWidth = null;
        boolean defaultWidthUnusable = false;
        for (PdfFont.ChainEntry entry : chain) {
            Object raw = entry.dict().get("DW");
            if (raw == null) {
                continue;
            }
            Number named = asNumber(raw);
            if (named != null) {
                defaultWidth = named;
                break;
            }
            // The specification writes /DW as an integer with default 1000. An array here is a
            // defect, not a second width: say so, and let the default answer rather than guess.
            defaultWidthUnusable = true;
            break;
        }
        List<String> defects = new ArrayList<>();
        if (defaultWidthUnusable) {
            defects.add("the CIDFont names /DW as " + PdfParser.kindOf(firstRaw(chain, "DW"))
                    + " instead of an integer: no default width is said, so the specification's"
                    + " default 1000 applies");
        }
        Map<Long, Number> collected = new LinkedHashMap<>();
        boolean cut = false;
        List<?> array = null;
        for (PdfFont.ChainEntry entry : chain) {
            Object value = document.dereference(entry.dict().get("W"));
            if (value instanceof List<?> list && !list.isEmpty()) {
                array = list;
                break;
            }
        }
        if (array != null) {
            int i = 0;
            while (i < array.size()) {
                Long first = asIntegral(array.get(i));
                if (first == null || i + 1 >= array.size()) {
                    break;   // PdfFont.describe reads the same array and names the junk entry
                }
                Object second = array.get(i + 1);
                if (second instanceof List<?> values) {
                    i += 2;
                    long offset = 0;
                    for (Object value : values) {
                        if (collected.size() >= PdfFont.MAX_WIDTH_ENTRIES) {
                            cut = true;
                            break;
                        }
                        Number width = asNumber(value);
                        if (width != null) {
                            collected.put(first + offset, width);
                        } else if (value instanceof List<?>) {
                            // PDF 1.7 5.6.3 lets each element of the inner array be a number - "the
                            // widths for n consecutive CIDs". A nested array is not that, so this
                            // CID has no width of its own and /DW answers. The listing in PdfFont
                            // shows the same entry as null; both say it in words now, because a
                            // silent /DW here reads to a caller as "the file named no width".
                            addDefect(defects, "the /W entry of CID " + (first + offset) + " is an"
                                    + " array instead of a number: the specification gives one"
                                    + " number per CID, so this CID reads as /DW");
                        }
                        offset++;
                    }
                    continue;
                }
                Long last = asIntegral(second);
                Number width = i + 2 < array.size() ? asNumber(array.get(i + 2)) : null;
                if (last == null || width == null || last < first) {
                    break;
                }
                i += 3;
                for (long cid = first; cid <= last; cid++) {
                    if (collected.size() >= PdfFont.MAX_WIDTH_ENTRIES) {
                        cut = true;
                        break;
                    }
                    collected.put(cid, width);
                }
            }
        }
        Map<Long, Number[]> verticalMetrics = new LinkedHashMap<>();
        Number[] defaultVertical = null;
        boolean defaultVerticalNamed = false;
        List<String> verticalDefects = new ArrayList<>();
        for (PdfFont.ChainEntry entry : chain) {
            Object raw = document.dereference(entry.dict().get("DW2"));
            if (raw instanceof List<?> pair && pair.size() >= 2) {
                Number vy = asNumber(pair.get(0));
                Number w1y = asNumber(pair.get(1));
                if (vy != null && w1y != null) {
                    defaultVertical = new Number[]{w1y, vy};
                    defaultVerticalNamed = true;
                } else {
                    addDefect(verticalDefects, "the CIDFont's /DW2 is not two numbers: the"
                            + " specification's default [880 -1000] applies");
                }
                break;
            }
            if (raw != null) {
                addDefect(verticalDefects, "the CIDFont names /DW2 as " + PdfParser.kindOf(raw)
                        + " instead of an array of two numbers: the specification's default"
                        + " [880 -1000] applies");
                break;
            }
        }
        readW2(document, chain, verticalMetrics, verticalDefects);
        String source = array != null ? SOURCE_W : SOURCE_DW;
        return new PdfFontWidths(collected, verticalMetrics, defaultVertical, defaultVerticalNamed,
                defects, verticalDefects,
                defaultWidth != null ? defaultWidth : PdfFont.DEFAULT_CID_WIDTH, source,
                cut, array == null ? "the CIDFont has no /W: every CID reads as /DW ("
                        + (defaultWidth != null ? "from the dictionary" : "the specification's"
                        + " default 1000") + ")" : null);
    }

    /**
     * Reads {@code /W2} (PDF 1.7 5.6.3): groups of two or five, where {@code c [ w1y vx vy … ]}
     * gives three numbers per consecutive CID and {@code cFirst cLast w1y vx vy} gives the same
     * three for a range. An inner array whose length is no multiple of three says so - a truncated
     * triple would silently shift every following CID's metrics by one.
     */
    private static void readW2(PdfDocument document, List<PdfFont.ChainEntry> chain,
                              Map<Long, Number[]> vertical, List<String> defects) {
        List<?> array = null;
        for (PdfFont.ChainEntry entry : chain) {
            Object value = document.dereference(entry.dict().get("W2"));
            if (value instanceof List<?> list && !list.isEmpty()) {
                array = list;
                break;
            }
        }
        if (array == null) {
            return;
        }
        int i = 0;
        while (i < array.size()) {
            Long first = asIntegral(array.get(i));
            if (first == null || i + 1 >= array.size()) {
                return;   // PdfFont.describe counts and names the entry that stops the reading
            }
            Object second = array.get(i + 1);
            if (second instanceof List<?> values) {
                i += 2;
                if (values.size() % 3 != 0) {
                    addDefect(defects, "the /W2 entry of CID " + first + " holds " + values.size()
                            + " numbers, not a multiple of three: the specification groups them as"
                            + " (w1y, vx, vy) per CID, so this group was skipped");
                    continue;
                }
                long offset = 0;
                for (int k = 0; k + 2 < values.size(); k += 3) {
                    if (vertical.size() >= PdfFont.MAX_WIDTH_ENTRIES) {
                        return;
                    }
                    Number w1y = asNumber(values.get(k));
                    Number vx = asNumber(values.get(k + 1));
                    Number vy = asNumber(values.get(k + 2));
                    if (w1y != null || vx != null || vy != null) {
                        vertical.put(first + offset, new Number[]{w1y, vx, vy});
                    }
                    offset++;
                }
                continue;
            }
            Long last = asIntegral(second);
            Number w1y = i + 2 < array.size() ? asNumber(array.get(i + 2)) : null;
            Number vx = i + 3 < array.size() ? asNumber(array.get(i + 3)) : null;
            Number vy = i + 4 < array.size() ? asNumber(array.get(i + 4)) : null;
            if (last == null || last < first || (w1y == null && vx == null && vy == null)) {
                addDefect(defects, "the /W2 entry of CID " + first + " is neither a CID with an"
                        + " array nor a range with three numbers: the rest of the array was not"
                        + " read");
                return;
            }
            i += 5;
            for (long cid = first; cid <= last; cid++) {
                if (vertical.size() >= PdfFont.MAX_WIDTH_ENTRIES) {
                    return;
                }
                vertical.put(cid, new Number[]{w1y, vx, vy});
            }
        }
    }

    /** Defect sentences are worth reading, not enumerating: a few examples, then the count. */
    private static void addDefect(List<String> defects, String sentence) {
        if (defects.size() < DEFECTS_LISTED) {
            defects.add(sentence);
            return;
        }
        if (defects.size() == DEFECTS_LISTED) {
            defects.add("more metric entries of this font are shaped the same way (only the first "
                    + DEFECTS_LISTED + " are named)");
        }
    }

    /** A simple font: {@code /FirstChar}+{@code /Widths}; a gap or a junk entry stays unknown. */
    private static PdfFontWidths forSimpleFont(PdfDocument document, Map<String, Object> font) {
        Object value = document.dereference(font.get("Widths"));
        if (!(value instanceof List<?> array) || array.isEmpty()) {
            return new PdfFontWidths(Map.of(), null, SOURCE_NONE, false,
                    "the dictionary names no /Widths: a width is only known where the embedded"
                            + " program's hmtx reaches the code (pdf.font(…) reads its tables)");
        }
        Long firstChar = asIntegral(font.get("FirstChar"));
        long start = firstChar != null ? firstChar : 0;
        Map<Long, Number> collected = new LinkedHashMap<>();
        boolean cut = false;
        for (int i = 0; i < array.size(); i++) {
            if (collected.size() >= PdfFont.MAX_WIDTH_ENTRIES) {
                cut = true;
                break;
            }
            Number width = asNumber(array.get(i));
            if (width != null) {
                collected.put(start + i, width);
            }
        }
        return new PdfFontWidths(collected, null, SOURCE_WIDTHS, cut,
                firstChar == null ? "/Widths has no /FirstChar, so its values were read from code"
                        + " 0 - which code a width belongs to is not said" : null);
    }

    /**
     * True when this view holds a value of its own for that code - the difference between a
     * {@code /W} entry and the {@code /DW} default that covers every other CID. Only the first
     * counts as an answer of the dictionary; a default is what the specification supplies where the
     * file says nothing, and an embedded program's own advance outranks it.
     * @param code the code or CID
     * @return whether {@code /W} or {@code /Widths} names this code
     */
    public boolean names(long code) {
        return widths.containsKey(code);
    }

    /**
     * The width of one code (a simple font's byte value, a CIDFont's CID).
     * @param code the code or CID
     * @return the width in text space units, or null when the dictionary does not name one
     */
    public Number widthOf(long code) {
        Number width = widths.get(code);
        if (width != null || !SOURCE_W.equals(source) && !SOURCE_DW.equals(source)) {
            return width;
        }
        return defaultWidth;
    }

    /** Where the widths came from: {@code W}, {@code Widths}, {@code DW} or {@code none}. */
    public String source() {
        return source;
    }

    /** The {@code /DW} of a CIDFont (or the spec's default 1000); null for simple fonts. */
    public Number defaultWidth() {
        return defaultWidth;
    }

    /**
     * Whether this view can say anything about vertical writing: the file named {@code /W2} or
     * {@code /DW2}. Whether those are the metrics a viewer uses is a question about the CMap's
     * {@code WMode}, not about this dictionary - see {@link PdfCMap#wMode()}.
     */
    public boolean hasVerticalMetrics() {
        return !vertical.isEmpty() || defaultVerticalNamed;
    }

    /** CIDs that {@code /W2} names vertical metrics for. */
    public int verticalIndexed() {
        return vertical.size();
    }

    /** The {@code /DW2} as the file writes it ({@code [v_y, w1_y]}), or null when it names none. */
    public List<Object> defaultVertical() {
        if (defaultVertical == null) {
            return null;
        }
        List<Object> asWritten = new ArrayList<>();
        asWritten.add(defaultVertical[1]);
        asWritten.add(defaultVertical[0]);
        return asWritten;
    }

    /**
     * The vertical metrics of one CID, in the specification's vocabulary: the displacement vector
     * {@code w1} of writing mode 1 (horizontal component always 0) and the position vector {@code v}
     * from the horizontal origin to the vertical one. {@code /W2} answers where it names the CID,
     * the file's {@code /DW2} where it named a default, and the specification's default
     * {@code [880 -1000]} otherwise; where {@code /W2} names no {@code v_x}, half the glyph width
     * answers, because that is what the specification says the horizontal component is.
     * @param code the CID
     * @return the metrics (with {@code source} {@code none} only where nothing can be said)
     */
    public Vertical verticalOf(long code) {
        Number[] named = vertical.get(code);
        if (named == null && !hasVerticalMetrics()) {
            return new Vertical(null, null, null, SOURCE_VERTICAL_NONE);
        }
        Number w1y = named != null && named[0] != null ? named[0]
                : defaultVertical != null ? defaultVertical[0] : DEFAULT_DW2[1];
        Number vy = named != null && named[2] != null ? named[2]
                : defaultVertical != null ? defaultVertical[1] : DEFAULT_DW2[0];
        Number vx = named != null && named[1] != null ? named[1] : half(widthOf(code));
        String source = named != null ? SOURCE_W2
                : defaultVerticalNamed ? SOURCE_DW2 : SOURCE_DW2_DEFAULT;
        return new Vertical(w1y, vx, vy, source);
    }

    /**
     * Metric entries the file shaped wrongly, named as they are (a few, then counted). This exists
     * because a width that is not really named must not answer as a quiet {@code /DW}: the caller
     * of {@code pdf.glyphMetrics()} reads a number and has a right to know that the number is a
     * default the file did not actually state for this CID.
     */
    public List<String> defects() {
        return defects;
    }

    /** The vertical half of {@link #defects()} - what a listing of {@code /W2} has to say too. */
    public List<String> verticalDefects() {
        return verticalDefects;
    }

    /** Codes this view holds a width for. */
    public int indexed() {
        return widths.size();
    }

    /** True when {@link PdfFont#MAX_WIDTH_ENTRIES} stopped the reading. */
    public boolean countedCut() {
        return countedCut;
    }

    /** One sentence about what this view cannot answer, or null. */
    public String note() {
        return note;
    }

    /** The block {@code pdf.decodeString()} shows next to the items ({@code widths: true}). */
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", source);
        out.put("indexed", (long) widths.size());
        if (defaultWidth != null) {
            out.put("defaultWidth", defaultWidth);
        }
        if (countedCut) {
            out.put("truncated", true);
        }
        if (hasVerticalMetrics()) {
            Map<String, Object> verticalBlock = new LinkedHashMap<>();
            verticalBlock.put("w2Entries", (long) vertical.size());
            List<Object> dw2 = defaultVertical();
            if (dw2 != null) {
                verticalBlock.put("dw2", dw2);
            }
            out.put("vertical", verticalBlock);
        }
        if (!defects.isEmpty()) {
            out.put("defects", new ArrayList<Object>(defects));
        }
        return out;
    }

    private static Number asNumber(Object value) {
        return value instanceof Long || value instanceof Double ? (Number) value : null;
    }

    /** The first entry a chain element names under that key, for a message that quotes the file. */
    private static Object firstRaw(List<PdfFont.ChainEntry> chain, String key) {
        for (PdfFont.ChainEntry entry : chain) {
            if (entry.dict().get(key) != null) {
                return entry.dict().get(key);
            }
        }
        return null;
    }

    /**
     * Half a width - the specification's default horizontal component of the position vector
     * (PDF 1.7 5.6.3). A whole number stays a whole number: a width of 1000 halves to 500, not to
     * 500.0, because the file's unit was whole.
     */
    private static Number half(Number width) {
        if (width == null) {
            return null;
        }
        if (width instanceof Long whole) {
            // Two separate boxes: a conditional expression would promote both branches to double
            // and turn half of 900 into 450.0.
            return whole % 2 == 0 ? (Number) (Long) (whole / 2) : (Number) (Double) (whole / 2d);
        }
        return (Number) (Double) (width.doubleValue() / 2d);
    }

    private static Long asIntegral(Object value) {
        if (value instanceof Long number) {
            return number;
        }
        if (value instanceof Double real && real == Math.floor(real) && !real.isInfinite()
                && Math.abs(real) <= 9e18) {
            return real.longValue();
        }
        return null;
    }
}
