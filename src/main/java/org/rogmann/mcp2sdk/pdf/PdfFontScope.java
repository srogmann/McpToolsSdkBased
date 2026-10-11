package org.rogmann.mcp2sdk.pdf;

import org.rogmann.mcp2sdk.pdf.ops.PdfTextWidths;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The fonts one page (or one slice of a page) draws with - resolved <em>when a {@code Tf} asks</em>
 * for them and not before.
 * <p>
 * A page names its fonts by resource name ({@code /F39 12 Tf}); what stands behind that name is a
 * dictionary, and what the dictionary means is a {@link PdfGlyphMapper}. Building a mapper reads the
 * font's {@code /ToUnicode} stream the first time a code is asked, so a page that carries twelve
 * fonts in its resources and shows text in two of them must not pay for ten - and a walk over two
 * hundred pages must not read the same map two hundred times, which is why the mappers come from
 * {@link PdfDocument#glyphMapper} (one chain per document call, keyed by object number).
 * </p>
 * <p>
 * The second half of the job is the accounting, because "the text of this page is right" is a claim
 * that has to be measurable: how many codes did the page hold, how many became characters, how many
 * stayed codes, which strings could not be read at all, and what happens to a name the resources do
 * not define. Each answer is a counter here and turns up in {@code pdf.text()}'s {@code mapping}
 * block. A string that cannot be read stays <em>out</em> of the text - inventing characters for it
 * would be worse than losing it - and is counted in {@code mapping.unmapped}, listed in
 * {@link #unmappedSample()} and explained by one note.
 * </p>
 * <p>
 * One scope per page (the counters talk about a page), one {@link Budget} per call (the call is what
 * has to stay bounded when somebody walks a whole document).
 * </p>
 */
public final class PdfFontScope implements PdfTextWidths {

    /** Note key of a {@code Tf} whose name the page resources do not define. */
    public static final String NOTE_FONT_UNKNOWN = "font-unknown";

    /** Note key of strings this page left unread because the call's code budget ran out. */
    public static final String NOTE_CODES_CUT = "text-codes-cut";

    /** Note key of strings this page left unread because no code width is known. */
    public static final String NOTE_STRINGS_UNREAD = "strings-unread";

    /** Note key of an {@code unmappedSample} that its own budget cut. */
    public static final String NOTE_SAMPLE_CUT = "unmapped-sample-cut";

    /** Note key of a text slice whose first font could not be determined. */
    public static final String NOTE_INITIAL_FONT = "initial-font-unknown";

    /** Codes one call maps through its fonts at most (the whole call, not one page). */
    public static final long MAX_TEXT_CODES = 200_000;

    /** Codes one string is read at most here (the same number {@code pdf.decodeString} uses). */
    public static final int MAX_CODES_PER_STRING = PdfTextDecoder.MAX_STRING_CODES;

    /** Codes the {@code unmappedSample} of a page names by default (the count stays complete). */
    public static final int DEFAULT_MAX_UNMAPPED = 20;

    /** Hex characters one sample entry may fill (a whole uncut string would be a wall of hex). */
    private static final int MAX_SAMPLE_HEX = 40;

    /** Method label of a page whose fonts answered nothing (text identical to the old extract). */
    public static final String METHOD_NONE = "none";

    /** Method label of a page whose fonts answered in more than one way. */
    public static final String METHOD_MIXED = "mixed";

    /**
     * The code budget of one call, shared by the pages it walks. Only work done <em>through a
     * font</em> is charged: reading a page by the document convention costs one table lookup per
     * byte, is bounded by the page itself and is never cut - so no text that was there before can
     * disappear because of this budget.
     */
    public static final class Budget {
        private final long maxCodes;
        private long used;
        private boolean cut;

        /** @param maxCodes codes for the whole call; 0 or less means "no limit" */
        public Budget(long maxCodes) {
            this.maxCodes = maxCodes > 0 ? maxCodes : Long.MAX_VALUE;
        }

        /** A budget that never stops anything (one page read on its own). */
        public static Budget unlimited() {
            return new Budget(0);
        }

        /** Codes that may still be read. */
        public long remaining() {
            return Math.max(0, maxCodes - used);
        }

        /** Whether at least {@code codes} codes could still be read. */
        public boolean fits(long codes) {
            return codes <= remaining();
        }

        /** Charges the budget; a charge that crosses the limit marks the call as cut. */
        public void charge(long codes) {
            used += codes;
            if (used > maxCodes) {
                cut = true;
            }
        }

        /** Marks the call as cut because a string could not be read within the budget. */
        public void markCut() {
            cut = true;
        }

        /** Whether the budget stopped something. */
        public boolean cut() {
            return cut;
        }

        /** Codes charged so far. */
        public long used() {
            return used;
        }

        /** The limit this budget enforces. */
        public long max() {
            return maxCodes;
        }
    }

    /** What one font of this page did - measured on the page, not read off the dictionary. */
    private static final class Measurement {
        private long codes;
        private long unmapped;
        private long unread;
        private long spaceFallback;
        private boolean cut;

        private long mapped() {
            return Math.max(0, codes - unmapped);
        }

        private boolean read() {
            return codes > 0 || unread > 0;
        }

        private Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("codes", codes);
            out.put("mapped", mapped());
            out.put("unmapped", unmapped);
            if (unread > 0) {
                out.put("unread", unread);
            }
            if (spaceFallback > 0) {
                out.put("spaceFallback", spaceFallback);
            }
            return out;
        }
    }

    private final PdfDocument document;
    private final int pageObjNum;
    private final List<String> notes;
    private final int maxUnmapped;
    private final Budget budget;

    /** Resource name → font, read the first time a {@code Tf} asks. */
    private Map<String, PdfFont.Ref> resources;
    /** Resource name → mapper; a name the resources do not define stays a null entry. */
    private final Map<String, PdfGlyphMapper> mappers = new LinkedHashMap<>();
    /**
     * Object number → mapper, for the font an {@code ExtGState} set by reference: Table 4.8, S. 221
     * makes that entry an indirect reference rather than a resource name, so there is no name to look
     * a mapper up under.
     */
    private final Map<Integer, PdfGlyphMapper> mappersByObject = new LinkedHashMap<>();
    /** The fonts a {@code Tf} really named, in the order they appeared. */
    private final Set<String> usedFonts = new LinkedHashSet<>();
    private final Map<String, Measurement> measurements = new LinkedHashMap<>();
    private final List<Object> sample = new ArrayList<>();
    private final Set<String> methods = new LinkedHashSet<>();
    private long codes;
    private long mapped;
    private long unmapped;
    private long unread;
    private long spaceFallback;
    private long stringsWithoutFont;
    private boolean sampleCut;

    /**
     * Creates the scope of one page.
     *
     * @param document    the document
     * @param pageObjNum  page object number whose resources name the fonts
     * @param notes       note/warning sink of the page (never null)
     * @param maxUnmapped size of the {@code unmappedSample} (0 = no sample)
     * @param budget      the call's code budget
     */
    public PdfFontScope(PdfDocument document, int pageObjNum, List<String> notes, int maxUnmapped,
                        Budget budget) {
        this.document = document;
        this.pageObjNum = pageObjNum;
        this.notes = notes == null ? new ArrayList<>() : notes;
        this.maxUnmapped = maxUnmapped;
        this.budget = budget == null ? Budget.unlimited() : budget;
    }

    /** Creates the scope with the default sample size. */
    public PdfFontScope(PdfDocument document, int pageObjNum, List<String> notes, Budget budget) {
        this(document, pageObjNum, notes, DEFAULT_MAX_UNMAPPED, budget);
    }

    /** The page object number whose resources this scope resolves names in. */
    public int pageObjNum() {
        return pageObjNum;
    }

    // ===================================================================
    // One string of the content stream
    // ===================================================================

    /**
     * What one string of a content stream becomes in the font that was current when it was written.
     *
     * @param string   the string operand of a text-showing operator (its bytes are the codes)
     * @param fontName the resource name of the {@code Tf} in effect at that operator (with slash),
     *                 or null when none came — the same statement {@link #show(byte[], String)} makes
     * @return the characters to append to the line, {@code ""} when nothing can be read
     */
    public String show(PdfString string, String fontName) {
        // The overload a content-stream walk uses: it holds the string, and PdfString keeps its bytes
        // to itself outside this package, so copying them per string would be the price of the call.
        return show(string == null ? null : string.bytesRef(), fontName);
    }

    /**
     * What one string of a content stream becomes in the font that was current when it was written.
     *
     * @param bytes    the string's bytes (a literal or hex string of the stream)
     * @param fontName the resource name of the last {@code Tf} (with slash), or null when none came
     * @return the characters to append to the line - {@code ""} when nothing can be read, which is
     *         a statement the counters keep and the notes explain
     */
    public String show(byte[] bytes, String fontName) {
        byte[] data = bytes == null ? new byte[0] : bytes;
        if (data.length == 0) {
            return "";
        }
        if (fontName == null) {
            // A text-showing operator before the first Tf: the specification wants a Tf first, so
            // this is the file's slip and the document convention is the least inventive answer.
            stringsWithoutFont++;
            return PdfString.decode(data);
        }
        PdfGlyphMapper mapper = mapperFor(fontName);
        if (mapper == null || mapper.isDocumentConvention()) {
            // Either a name the resources do not define (the font-unknown note said it) or a simple
            // font with no source of its own: both read exactly the way the whole module reads a
            // string, which is what the caller has always seen - and nothing stays a code there.
            return PdfString.decode(data);
        }
        Measurement measurement = measurements.computeIfAbsent(fontName,
                key -> new Measurement());
        if (mapper.codeBytes() == null) {
            // Decision 18: a font whose encoding cannot say where a code ends is not read through
            // the document convention either - those bytes would be a text nobody can vouch for.
            return unread(data, measurement, NOTE_STRINGS_UNREAD,
                    "a string of this page holds codes whose width its encoding does not say: it"
                            + " stays out of the text and is counted as unmapped (the font's own"
                            + " note names the encoding)");
        }
        if (!budget.fits(1)) {
            budget.markCut();
            return unread(data, measurement, NOTE_CODES_CUT,
                    "the call's code budget of " + budget.max() + " codes (maxTextCodes) is used"
                            + " up: the further strings of this page stay out of the text and are"
                            + " counted as unmapped");
        }
        int allowance = (int) Math.min(MAX_CODES_PER_STRING, budget.remaining());
        PdfTextDecoder.Result result = PdfTextDecoder.decode(data, mapper, allowance, notes);
        budget.charge(result.codes());
        codes += result.codes();
        mapped += result.mapped();
        unmapped += result.unmappedCount();
        spaceFallback += result.spaceFallback();
        measurement.codes += result.codes();
        measurement.unmapped += result.unmappedCount();
        measurement.spaceFallback += result.spaceFallback();
        if (result.truncated()) {
            // The codes behind the cut were read by nobody, so they are not mapped text either.
            long left = Math.max(0, result.codes() - result.read());
            unmapped += left;
            measurement.unmapped += left;
            measurement.cut = true;
        }
        for (String hex : result.unmapped()) {
            addSample(hex);
        }
        return result.unicode();
    }

    /** A string that stays out of the text: counted, sampled, and never guessed at. */
    private String unread(byte[] data, Measurement measurement, String noteKey, String sentence) {
        unread++;
        // The byte width is unknown here, and one byte is at least one code: counting the bytes
        // into the page's `unmapped` can overstate the number of codes, but it can never
        // understate what stayed unread - a page must not look clean because reading was refused.
        // The per-font measurement stays exact: it counts strings in `unread` and never pretends
        // to have read a code.
        unmapped += data.length;
        measurement.unread++;
        addSample(PdfCMap.hex(data));
        note(noteKey, sentence);
        return "";
    }

    /** The mapper of one resource name, built on first use (a name that is no font stays null). */
    private PdfGlyphMapper mapperFor(String fontName) {
        if (fontName == null) {
            return null;
        }
        if (mappers.containsKey(fontName)) {
            return mappers.get(fontName);
        }
        PdfFont.Ref ref = resources().get(fontName);
        if (ref == null) {
            note(NOTE_FONT_UNKNOWN, "Tf names " + fontName + " which the resources of page object "
                    + pageObjNum + " do not define: the document convention reads its strings (a"
                    + " form XObject could be the reason)");
            mappers.put(fontName, null);
            return null;
        }
        PdfGlyphMapper mapper = document.glyphMapper(ref.objNum(), ref.dict(), notes);
        mappers.put(fontName, mapper);
        usedFonts.add(fontName);
        if (!mapper.isDocumentConvention()) {
            methods.add(mapper.method());
        }
        return mapper;
    }

    /**
     * The page's font resources, read once on the first {@code Tf} - through {@link PdfResources}
     * and {@link PdfFont#fontEntries}, the same lookup {@code pdf.fonts()} and the page listing
     * use, so a name means one thing in all three answers (including what is said about a name
     * whose value is no dictionary). A {@code Tf} naming something the resources do not name stays
     * a null entry here: the {@code Tf} itself is what says whether that is a finding (see
     * {@link #mapperFor(String)}).
     */
    private Map<String, PdfFont.Ref> resources() {
        if (resources == null) {
            resources = new LinkedHashMap<>();
            for (PdfFont.Ref ref : PdfFont.fontEntries(
                    PdfResources.ofPage(document, pageObjNum, notes), notes)) {
                resources.put(ref.resourceName(), ref);
            }
        }
        return resources;
    }

    // ===================================================================
    // The same fonts as a width source (increment D5)
    // ===================================================================

    /**
     * The width answers of the font a showing operator draws with — {@link PdfTextWidths}, and the
     * reason a page's <em>characters</em> and a page's <em>advances</em> cannot disagree: both come
     * out of one {@link PdfGlyphMapper} per font, built by one lookup of one {@code Tf}.
     *
     * @param resourceName the name a {@code Tf} wrote, or null for an {@code ExtGState} font
     * @param fontObjNum   the object number an {@code ExtGState} referenced, or null
     * @return the answers, or null when nothing resolves (the walk then reports the font as
     *         unreachable instead of assuming a width)
     */
    @Override
    public PdfTextWidths.Font fontFor(String resourceName, Integer fontObjNum) {
        if (fontObjNum != null) {
            PdfGlyphMapper mapper = mappersByObject.containsKey(fontObjNum)
                    ? mappersByObject.get(fontObjNum) : mapperOfObject(fontObjNum);
            return mapper == null ? null : new FontAnswers(mapper, subtypeOfObject(fontObjNum));
        }
        PdfGlyphMapper mapper = mapperFor(resourceName);
        if (mapper == null) {
            return null;
        }
        PdfFont.Ref ref = resources().get(resourceName);
        return new FontAnswers(mapper, ref == null ? null : subtypeOf(ref.dict()));
    }

    /**
     * The object number behind a resource name, or null when the name resolves nowhere (or is
     * written inline in the resources, which has no number to name). A placement answer puts this
     * next to the name so a caller can go on with {@code pdf.font(objNum)}.
     */
    public Integer objNumOf(String resourceName) {
        if (resourceName == null) {
            return null;
        }
        PdfFont.Ref ref = resources().get(resourceName);
        return ref == null ? null : ref.objNum();
    }

    /** The mapper of a font an {@code ExtGState} referenced (null when the object is no dictionary). */
    private PdfGlyphMapper mapperOfObject(int objNum) {
        Map<String, Object> dict = document.dictOf(objNum);
        PdfGlyphMapper mapper = dict.isEmpty() ? null
                : PdfGlyphMapper.of(document, objNum, dict, notes);
        mappersByObject.put(objNum, mapper);
        if (mapper != null) {
            usedFonts.add("#" + objNum);
            if (!mapper.isDocumentConvention()) {
                methods.add(mapper.method());
            }
        }
        return mapper;
    }

    /** The {@code /Subtype} of a font dictionary as the file wrote it, or null. */
    private static String subtypeOf(Map<String, Object> dict) {
        Object subtype = dict == null ? null : dict.get("Subtype");
        return subtype == null ? null : String.valueOf(subtype);
    }

    /** The subtype of a font object named by number, or null when the object is not a dictionary. */
    private String subtypeOfObject(int objNum) {
        return subtypeOf(document.dictOf(objNum));
    }

    /**
     * One mapper, seen as a width source. Nothing here decides anything the mapper does not already
     * answer: the code width comes from the encoding, the advance from the dictionary and the
     * embedded program, and the writing mode from the CMap (5.6.3, S. 440: "This is selected by the
     * WMode entry in the associated CMap dictionary").
     */
    private static final class FontAnswers implements PdfTextWidths.Font {
        private final PdfGlyphMapper mapper;
        private final String subtype;

        FontAnswers(PdfGlyphMapper mapper, String subtype) {
            this.mapper = mapper;
            this.subtype = subtype;
        }

        @Override
        public Integer codeBytes() {
            return mapper.codeBytes();
        }

        @Override
        public PdfGlyphMapper.Width widthOf(long code) {
            return mapper.widthOf(code);
        }

        @Override
        public boolean vertical() {
            return Long.valueOf(1L).equals(mapper.encoding().wMode());
        }

        @Override
        public boolean hasVerticalMetrics() {
            return mapper.hasVerticalMetrics();
        }

        @Override
        public PdfFontWidths.Vertical verticalOf(long code) {
            return mapper.verticalOf(code);
        }

        @Override
        public String subtype() {
            return subtype;
        }

        @Override
        public String toString() {
            return "widths of " + mapper.method() + (subtype == null ? "" : " (" + subtype + ")");
        }
    }

    // ===================================================================
    // What the page did
    // ===================================================================

    /**
     * The {@code mapping} block of the page: what was read and what stayed a code. {@code method}
     * is {@code none} when no font answered (so the text is exactly the extract this module always
     * gave), one method when a single kind of source did it, and {@code mixed} when several did.
     */
    public Map<String, Object> mapping() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("method", method());
        out.put("fonts", (long) usedFonts.size());
        out.put("fontsResolved", fontsResolved());
        out.put("codes", codes);
        out.put("mapped", mapped);
        out.put("unmapped", unmapped);
        if (unread > 0) {
            out.put("unread", unread);
        }
        if (spaceFallback > 0) {
            out.put("spaceFallback", spaceFallback);
        }
        if (stringsWithoutFont > 0) {
            out.put("stringsWithoutFont", stringsWithoutFont);
        }
        return out;
    }

    /** How many of the fonts a {@code Tf} named can answer for their codes. */
    public long fontsResolved() {
        long resolved = 0;
        for (String name : usedFonts) {
            PdfGlyphMapper mapper = mappers.get(name);
            if (mapper != null && (mapper.possible() || mapper.isDocumentConvention())) {
                resolved++;
            }
        }
        return resolved;
    }

    /** The method label of the page. */
    public String method() {
        if (methods.isEmpty()) {
            return METHOD_NONE;
        }
        return methods.size() == 1 ? methods.iterator().next() : METHOD_MIXED;
    }

    /** The codes that stayed codes, up to the sample size (the count is in {@link #mapping()}). */
    public List<Object> unmappedSample() {
        return List.copyOf(sample);
    }

    /** Whether the sample reached its size (the count in {@code mapping} stays complete). */
    public boolean sampleTruncated() {
        return sampleCut;
    }

    /** Whether the call's code budget stopped the reading. */
    public boolean cut() {
        return budget.cut();
    }

    /**
     * Strings a text-showing operator wrote while no {@code Tf} had run yet. For a whole page this
     * is a slip of the file (the specification wants a {@code Tf} first); for a <em>slice</em> of a
     * page it is the case decision 14 is about - the font that applies stands outside the slice, and
     * if none was found the document convention read those strings.
     */
    public long stringsWithoutFont() {
        return stringsWithoutFont;
    }

    /** The resource names a {@code Tf} really named, in the order they appeared. */
    public List<String> usedFontNames() {
        return new ArrayList<>(usedFonts);
    }

    /**
     * What one font of this page did, or null when no {@code Tf} drew with it - the difference
     * between "this dictionary sits in the resources" and "this dictionary is why the text looks
     * the way it does".
     */
    public Map<String, Object> measurement(String resourceName) {
        Measurement measurement = measurements.get(resourceName);
        return measurement == null || !measurement.read() ? null : measurement.toMap();
    }

    /**
     * The method one measured font answered with, or null when no {@code Tf} drew with it (or it is
     * left to the document convention). The listing shows this instead of its dictionary guess
     * wherever a measurement exists - a font whose codes came out of the embedded program's
     * {@code /cmap} is not "unresolvable" just because its dictionary is thin.
     *
     * @param resourceName the resource name as the page names it
     * @return the mapper's method, or null
     */
    public String methodOf(String resourceName) {
        PdfGlyphMapper mapper = mappers.get(resourceName);
        return mapper == null || mapper.isDocumentConvention() ? null : mapper.method();
    }

    /** Whether a measured font left something behind (the honest {@code reliableText} verdict). */
    public boolean leftCodes(String resourceName) {
        Measurement measurement = measurements.get(resourceName);
        return measurement != null
                && (measurement.unmapped > 0 || measurement.unread > 0 || measurement.cut);
    }

    /** One sentence, once per page - the same statement is not repeated per string. */
    public void noteOnce(String sentence) {
        if (sentence != null && !notes.contains(sentence)) {
            notes.add(sentence);
        }
    }

    /**
     * One sentence of the note catalog, once per page. The key is the plan's catalog key - it is
     * not part of the text (a user reads sentences, tests hold them by wording), so the guard is
     * the sentence and not the key.
     */
    public void note(String key, String sentence) {
        noteOnce(sentence);
    }

    // ===================================================================
    // Internals
    // ===================================================================

    private void addSample(String hex) {
        if (maxUnmapped <= 0) {
            return;
        }
        if (sample.size() >= maxUnmapped) {
            if (!sampleCut) {
                sampleCut = true;
                note(NOTE_SAMPLE_CUT, "unmappedSample names " + maxUnmapped + " of them"
                        + " (maxUnmapped) - mapping.unmapped counts all of them");
            }
            return;
        }
        sample.add(hex.length() > MAX_SAMPLE_HEX
                ? hex.substring(0, MAX_SAMPLE_HEX) + "…" : hex);
    }
}
