package org.rogmann.mcp2sdk.pdf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns one <em>glyph code</em> of one font into characters - the chain the plan calls
 * "Aufgabe 4", and the only place that decides in which order the sources are trusted.
 * <p>
 * The chain, per font (each step names itself in {@link Mapping#mappedBy()}, so a result can always
 * be traced back to the entry that produced it):
 * </p>
 * <ol>
 *   <li><b>{@code /ToUnicode} of the font itself</b> - a Type0 <em>or</em> a simple font: pdfTeX
 *       hangs one map behind each of its Type 1 subsets, and those are the commonest case in
 *       practice ({@code toUnicode:bfchar}, {@code toUnicode:bfrange},
 *       {@code toUnicode:bfrange-array});</li>
 *   <li><b>the code <em>is</em> the character</b> - the UCS family ({@code *-UCS2-*},
 *       {@code *-UCS4-*}, {@code *-UTF16-*}), the only predefined CMaps resolvable without a table
 *       ({@code unicode-code});</li>
 *   <li><b>{@code /ToUnicode} of the descendant</b> - a producer that puts the map on the CIDFont
 *       instead of on the Type0 wrapper ({@code toUnicode-descendant:…});</li>
 *   <li><b>the {@code /cmap} of the embedded program</b> over CID→GID - the program's own answer,
 *       and the only source a subset without {@code /ToUnicode} has ({@code cmap-table:format4},
 *       {@code cmap-table:format12}, …). It only ever reads a subtable whose platform/encoding
 *       promises Unicode, and it takes the <em>smallest</em> code point of a glyph, saying so in a
 *       note when more than one names it - see {@link PdfTrueTypeProgram};</li>
 *   <li><b>a glyph name</b> through the Adobe Glyph List ({@link PdfGlyphNames}) - and only from a
 *       <em>real</em> name source: {@code /Differences} today, the program's own
 *       {@code /Encoding}/{@code Charset} once a program is read. A base encoding merely
 *       <em>implied</em> by the descriptor's {@code Flags} is not a name source: that would invent
 *       a code page;</li>
 *   <li><b>nothing</b> - {@code null}, plus one note. No U+FFFD, no "closest ASCII".</li>
 * </ol>
 * <p>
 * Two documented exceptions to step 6, both visible in {@code mappedBy}: the <b>space code</b>
 * ({@code 20}) reads as U+0020 when nothing maps it - TeX/CidGen maps leave {@code <20>} out of the
 * CMap although the string clearly holds word gaps, and a word gap is the only reading of that code
 * ({@code space-fallback}); and a <b>simple font that touches nothing</b> (no {@code /ToUnicode},
 * no {@code /Differences}, no name source) keeps being read the way the rest of this module has
 * read it - the document convention ({@code document-convention}) - because a specification-minded
 * {@code StandardEncoding} guess over a symbolic TeX subset would be worse than what the user sees
 * today.
 * </p>
 */
public final class PdfGlyphMapper {

    /** {@link Mapping#mappedBy()} of the documented space fallback. */
    public static final String MAPPED_BY_SPACE_FALLBACK = "space-fallback";

    /** {@link Mapping#mappedBy()} of a simple font that is left to the document convention. */
    public static final String MAPPED_BY_DOCUMENT = "document-convention";

    /** The code value the plan allows to stand for a word gap without an entry. */
    public static final long SPACE_CODE = 0x20;

    /** How many unknown glyph names are named individually. */
    private static final int MAX_LISTED_NAMES = 3;

    /** Note keys of the plan's catalog this class can raise. */
    public static final String NOTE_NO_MAPPING = "no-mapping";
    public static final String NOTE_CODESPACE_MISMATCH = "codespace-mismatch";
    public static final String NOTE_CMAP_EMPTY = "cmap-empty";
    public static final String NOTE_CMAP_UNREADABLE = "cmap-unreadable";
    public static final String NOTE_GLYPH_NAME_UNKNOWN = "glyph-name-unknown";
    public static final String NOTE_GLYPH_LIST_MISSING = "glyph-list-missing";
    public static final String NOTE_GLYPH_LIST_PARTIAL = "glyph-list-partial";
    public static final String NOTE_CID_TO_GID_SHORT = "cid-to-gid-short";
    public static final String NOTE_UCS_CODE_INVALID = "ucs-code-invalid";

    /**
     * One code's answer: the characters it means and who said so.
     *
     * @param codePoints the characters (a ligature code holds several)
     * @param mappedBy   where it came from, e.g. {@code "toUnicode:bfchar"}, {@code "ucs"},
     *                   {@code "differences:agl"}, {@code "space-fallback"}
     */
    public record Mapping(int[] codePoints, String mappedBy) {
    }

    private final PdfDocument document;
    private final Integer objNum;
    private final Map<String, Object> font;
    private final PdfEncoding encoding;
    private final List<PdfFont.ChainEntry> chain;
    /** The dictionaries that carry the CID facts: the chain, or this font when it <em>is</em> one. */
    private final List<PdfFont.ChainEntry> cidChain;
    private final boolean cidFont;
    private final Map<String, Object> fontFiles;
    private final Map<Long, String> glyphNames = new LinkedHashMap<>();
    private final List<String> notes;
    private final PdfGlyphNames glyphList;
    private final boolean zapfBase;
    /**
     * True for the caller that measures ({@code pdf.glyphMetrics()}) instead of mapping: what the
     * encoding refuses to resolve - a base table whose code page is not built in, a glyph list it
     * cannot read - is an answer to "which character is this byte", and a caller that only asks for
     * a width should not be told about it. The statements about the <em>program</em> (a broken
     * table, a cmap beyond the subset) keep reaching the caller either way.
     */
    private final boolean metricsOnly;

    /** The plan's {@code mapping} value - see the constructor for the one case the program decides. */
    private String method;

    private PdfCMap ownCmap;
    private PdfCMap descendantCmap;
    private boolean ownCmapRead;
    private boolean descendantCmapRead;
    private PdfCidToGidMap cidToGid;
    private boolean cidToGidRead;
    private PdfFontWidths widths;
    private PdfFontProgram.Read program;
    private boolean programLooked;
    private boolean ambiguityNoted;
    private int unknownNames;
    private boolean noMappingNoted;
    private boolean ucsNoted;
    private boolean gidShortNoted;

    private PdfGlyphMapper(PdfDocument document, Integer objNum, Map<String, Object> font,
                           PdfGlyphNames glyphList, List<String> notes, boolean metricsOnly) {
        this.document = document;
        this.objNum = objNum;
        this.font = font;
        this.glyphList = glyphList;
        this.notes = notes == null ? new ArrayList<>() : notes;
        this.metricsOnly = metricsOnly;
        this.encoding = PdfEncoding.of(document, font);
        this.chain = PdfFont.descendantChain(document, font, new ArrayList<>());
        // CIDs, not byte codes - whether the dictionary is the /Type0 wrapper, a CIDFont addressed
        // by its own object number, or a font that reaches one through a chain.
        this.cidFont = PdfFont.isCidFont(font) || !chain.isEmpty();
        // A CIDFont addressed by its own object number has no chain, yet /CIDToGIDMap and /W are
        // entries of this very dictionary: the CID facts are read from here in that case.
        this.cidChain = chain.isEmpty() && PdfFont.isCidFontDict(font)
                ? List.of(new PdfFont.ChainEntry(objNum, font))
                : chain;
        this.fontFiles = PdfFont.describeFontFiles(document, font, chain);
        this.method = PdfFont.mapping(document, font, chain, encoding);
        if (!PdfFont.RESOLVABLE.contains(method) && cidFont && glyphIndexKnown()) {
            PdfFontProgram.Read embedded = program();
            if (embedded != null && embedded.canMapGlyphs()) {
                // Step 4 decided in advance: the dictionary names no source, so the only thing that
                // can still answer for a code is the /cmap of the embedded program. Looking at it is
                // what the verdict `method` is worth - and the mapper itself is cached per document
                // call (PdfDocument.glyphMapper), so a 500-page walk reads a subset once, not 500
                // times.
                this.method = "cmap-table";
            }
        }
        if (!PdfFont.RESOLVABLE.contains(method) && programNamesCodesWorthAsking()) {
            // Step 5 decided in advance, for the same reason step 4 is: a subset with no
            // /ToUnicode and no /Differences is not "unresolvable" when its own program declares
            // which glyph a code selects. `glyph-names` is the plan's word for that source, and it
            // means what it says only after the program has been asked.
            if (programNamesCodes()) {
                this.method = "glyph-names";
            }
        }
        for (Map<String, Object> difference : encoding.differences()) {
            if (difference.get("code") instanceof Long code
                    && difference.get("glyph") instanceof String glyph) {
                glyphNames.put(code, glyph);
            }
        }
        this.zapfBase = isZapfBase();
        // A simple font that names no /Encoding at all is read by PDFDocEncoding - which is what the
        // specification asks for in a content stream anyway. "The specification says
        // StandardEncoding, whose table is not built in" is therefore a fact about the font (that
        // is what pdf.font() names) and not a shortfall of a page that reads correctly through it,
        // so it stays out of the page's notes. A table the file <em>does</em> name
        // (/WinAnsiEncoding, an unknown /BaseEncoding) keeps its note: there 0x80-0x9F really can
        // mean something else than the document convention makes of them.
        boolean readByDocumentConvention = isDocumentConvention()
                && "default".equals(encoding.shape().source());
        if (!metricsOnly
                && !readByDocumentConvention
                && !"toUnicode".equals(method) && !"toUnicode-descendant".equals(method)) {
            // What the encoding refuses (a non-UCS predefined CMap, a base table it will not apply)
            // is part of the answer - unless a /ToUnicode map reads the codes anyway, where the
            // refusal would only be noise.
            List<String> encodingNotes = encoding.notes();
            List<String> encodingKeys = encoding.noteKeys();
            for (int i = 0; i < encodingNotes.size(); i++) {
                note(i < encodingKeys.size() ? encodingKeys.get(i) : "encoding",
                        encodingNotes.get(i));
            }
        }
        if (!metricsOnly && glyphList.loadError() != null) {
            note(NOTE_GLYPH_LIST_MISSING, glyphList.loadError());
        } else if (!metricsOnly && glyphList.rejected() > 0) {
            note(NOTE_GLYPH_LIST_PARTIAL, "the Adobe Glyph List has " + glyphList.rejected()
                    + " unusable lines of " + glyphList.lines() + ": those names stay unresolved");
        }
    }

    /**
     * Builds the mapper for one font dictionary.
     * @param document the document
     * @param font the resolved font dictionary
     * @param notes note/warning sink (never null)
     * @return the mapper - never null
     */
    public static PdfGlyphMapper of(PdfDocument document, Map<String, Object> font,
                                    List<String> notes) {
        return new PdfGlyphMapper(document, null, font, PdfGlyphNames.builtin(), notes, false);
    }

    /**
     * Builds the mapper, naming the font's object number in the notes.
     * @param document the document
     * @param objNum object number of the font dictionary (may be null for an inline dictionary)
     * @param font the resolved font dictionary
     * @param notes note/warning sink (never null)
     * @return the mapper
     */
    public static PdfGlyphMapper of(PdfDocument document, Integer objNum, Map<String, Object> font,
                                    List<String> notes) {
        return new PdfGlyphMapper(document, objNum, font, PdfGlyphNames.builtin(), notes, false);
    }

    /**
     * Builds the mapper of a caller that <em>measures</em> rather than maps
     * ({@code pdf.glyphMetrics()}): widths, glyph indices and the program's boxes are asked, no code
     * is turned into a character. The chain is built the same way - including what the embedded
     * program reports about itself - but the sentences that answer a character question ("this base
     * table's code page is not built in", "the glyph list cannot be read") stay unsaid, because the
     * question was not asked.
     *
     * @param document the document
     * @param objNum   object number of the font dictionary, may be null
     * @param font     the resolved font dictionary
     * @param notes    note sink for what is wrong with the font or its program (never null)
     * @return the mapper
     */
    public static PdfGlyphMapper forMetrics(PdfDocument document, Integer objNum,
                                            Map<String, Object> font, List<String> notes) {
        return new PdfGlyphMapper(document, objNum, font, PdfGlyphNames.builtin(), notes, true);
    }

    /**
     * Builds the mapper with an explicit glyph list (the tests carry their own; the production
     * path uses {@link PdfGlyphNames#builtin()}).
     * @param document the document
     * @param objNum object number of the font dictionary, may be null
     * @param font the resolved font dictionary
     * @param glyphList the name list to resolve glyph names with
     * @param notes note/warning sink (never null)
     * @return the mapper
     */
    public static PdfGlyphMapper of(PdfDocument document, Integer objNum, Map<String, Object> font,
                                    PdfGlyphNames glyphList, List<String> notes) {
        return new PdfGlyphMapper(document, objNum, font,
                glyphList != null ? glyphList : PdfGlyphNames.builtin(), notes, false);
    }

    // ===================================================================
    // What the font is
    // ===================================================================

    /** Which source the chain will use first (the plan's {@code mapping} value). */
    public String method() {
        return method;
    }

    /** True when a source exists that can resolve codes (the {@code possible} of the sheet). */
    public boolean possible() {
        return PdfFont.RESOLVABLE.contains(method);
    }

    /** Bytes per code, or null when the encoding cannot say (then no string can be cut). */
    public Integer codeBytes() {
        return encoding.codeBytes();
    }

    /** The code spaces the encoding declares. */
    public List<PdfCMap.CodeSpace> codeSpaces() {
        return encoding.codeSpaces();
    }

    /** The encoding behind this mapper (code spaces, differences, notes). */
    public PdfEncoding encoding() {
        return encoding;
    }

    /** True when the codes of this font are CIDs (a Type0 wrapper or a CIDFont). */
    public boolean isCidFont() {
        return cidFont;
    }

    /**
     * True when the font is left to the document convention: a simple font with one-byte codes that
     * names no {@code /ToUnicode}, no {@code /Differences} and no UCS encoding. Its bytes are read
     * as every other string of this module reads them - which is what the user has always seen, and
     * it says so in {@code mappedBy} instead of pretending to be a font fact.
     */
    public boolean isDocumentConvention() {
        if (cidFont || !"none".equals(method) && !"builtin-encoding".equals(method)) {
            return false;
        }
        return encoding.codeBytes() != null && encoding.codeBytes() == 1;
    }

    /**
     * True when the chain reaches its end without a source, so the codes stay codes - the verdict
     * {@code pdf.text()} (A5) turns into {@code reliableText: false}. The note is raised once (a
     * string of 300 unmapped codes is one statement, not 300).
     */
    public boolean isCodesOnly() {
        return !possible() && !isDocumentConvention();
    }

    // ===================================================================
    // Code -> characters
    // ===================================================================

    /**
     * Maps one code.
     * @param codeHex the code as uppercase hex of the encoding's width ({@code "0048"}, "48")
     * @return the mapping, or null when this font cannot say what the code means
     */
    public Mapping map(String codeHex) {
        if (codeHex == null || codeHex.isEmpty()) {
            return null;
        }
        long code = valueOf(codeHex);
        // 1) the font's own /ToUnicode
        Mapping own = lookup(ownCmap(), codeHex, "toUnicode");
        if (own != null) {
            return own;
        }
        // 2) the code is the character (UCS family)
        if (encoding.isUcs()) {
            return unicodeValue(code);
        }
        // 3) the descendant's /ToUnicode
        Mapping descendant = lookup(descendantCmap(), codeHex, "toUnicode-descendant");
        if (descendant != null) {
            return descendant;
        }
        // 4) the /cmap of the embedded program, reached over CID -> GID: the program's own answer
        //    to "which character is this glyph", and the only one a subset without /ToUnicode has.
        if (cidFont) {
            Long glyph = gidOf(code);
            if (glyph != null) {
                Mapping fromProgram = fromProgramCmap(glyph);
                if (fromProgram != null) {
                    return fromProgram;
                }
            }
        }
        // 5) a glyph name from /Differences through the Adobe Glyph List
        Mapping named = fromGlyphName(code);
        if (named != null) {
            return named;
        }
        // 6) nothing - except the two readings the plan allows without an entry. The document
        //    convention first: a font that is left to it reads its space code as a space there, so
        //    the fallback is only counted where there would be no character at all without it.
        if (isDocumentConvention()) {
            return new Mapping(new int[]{PdfString.pdfDocEncodingUnit((int) code)},
                    MAPPED_BY_DOCUMENT);
        }
        if (code == SPACE_CODE) {
            return new Mapping(new int[]{(int) SPACE_CODE}, MAPPED_BY_SPACE_FALLBACK);
        }
        if (!possible() && !noMappingNoted) {
            // Only a font without any source says "nothing maps here" - a font whose /ToUnicode
            // simply does not cover this code has its answer in `unmapped`, and 300 uncovered codes
            // are 300 entries there, not 300 notes.
            noMappingNoted = true;
            note(NOTE_NO_MAPPING, noMappingSentence());
        }
        return null;
    }

    /** The sentence of the last step: it names what was looked for, so the absence is checkable. */
    private String noMappingSentence() {
        String what = objNum != null ? "font " + objNum : "this font";
        if (cidFont && fontFiles != null) {
            return "no /ToUnicode and no /cmap this module can read in the embedded program of "
                    + what + ": the content stream holds glyph indices, not characters";
        }
        if (cidFont) {
            return "no /ToUnicode for " + what + ": a CID without a map is a glyph index, not a"
                    + " character";
        }
        return "nothing maps the codes of " + what + " (no /ToUnicode and no glyph name that"
                + " resolves): the codes stay codes";
    }

    /**
     * Whether asking the embedded program for names can pay off. Two gates, both cheap: the
     * dictionary must name no other source (the caller checked), and the program must be of a shape
     * that can carry names at all - {@code /FontFile} (Type 1 {@code /Encoding}) or
     * {@code /FontFile3} (CFF {@code Charset}). An sfnt's {@code /FontFile2} is not one of them, and
     * opening its stream to discover that is exactly the cost this gate prevents.
     *
     * @return true when {@link #programNamesCodes()} is worth asking
     */
    private boolean programNamesCodesWorthAsking() {
        if (fontFiles == null) {
            return false;
        }
        if (!fontFiles.containsKey("fontFile") && !fontFiles.containsKey("fontFile3")) {
            return false;
        }
        // A CID font reaches its names over the glyph index, so it needs the CID -> GID answer.
        return !cidFont || glyphIndexKnown();
    }

    /** Whether the font embeds a program at all (the source step 4 would need). */
    private boolean hasEmbeddedProgram() {
        return fontFiles != null;
    }

    /**
     * A UCS code: the value <em>is</em> the character. A surrogate survives as itself here - the
     * {@link PdfTextDecoder} joins a high and the following low surrogate into one character,
     * because only the string knows what came before.
     */
    private Mapping unicodeValue(long code) {
        if (code <= 0 || code > 0x10FFFFL) {
            if (!ucsNoted) {
                ucsNoted = true;
                note(NOTE_UCS_CODE_INVALID, "a code of this " + encoding.shape().name()
                        + " font is outside Unicode (0 or beyond U+10FFFF): such a code is not a"
                        + " character and stays unmapped");
            }
            return null;
        }
        return new Mapping(new int[]{(int) code}, "unicode-code");
    }

    /**
     * Step 5: the glyph name this code selects, if any, through the glyph list. Two sources, in the
     * order the plan sets: what the dictionary's {@code /Differences} names first, because the file
     * asked for it, and what the embedded program declares in its own {@code /Encoding} (Type 1) or
     * {@code Charset} (CFF) second - the font's own statement, not a guess from {@code /Flags}.
     */
    private Mapping fromGlyphName(long code) {
        String glyph = glyphNames.get(code);
        String source = "differences";
        if (glyph == null) {
            glyph = programNameOf(code);
            source = "program-name";
        }
        if (glyph == null) {
            return null;
        }
        PdfGlyphNames.Name resolved = glyphList.resolve(glyph);
        if (resolved != null) {
            // The rule is part of the answer: 'bullet' is in the list, 'uni00E1' is a pattern and
            // 'f_i' is a decomposition - three different reasons for the same two characters.
            return new Mapping(resolved.codePoints(), source + ":" + resolved.rule());
        }
        if (zapfBase && "differences".equals(source)) {
            int[] dingbat = PdfGlyphNames.zapfDingbats().fromTable(glyph);
            if (dingbat != null) {
                return new Mapping(dingbat, "differences:zapfdingbats");
            }
        }
        if (unknownNames < MAX_LISTED_NAMES) {
            unknownNames++;
            note(NOTE_GLYPH_NAME_UNKNOWN, "glyph name '" + PdfGlyphNames.bareName(glyph)
                    + "' is not in the Adobe Glyph List");
        }
        return null;
    }

    /**
     * The name the embedded program gives this code: the {@code /Encoding} array of a Type 1
     * program for a simple font, or the {@code Charset} of a CFF reached over the glyph index for a
     * CID font. Asked lazily - a font with a {@code /ToUnicode} map never pays for it.
     *
     * @param code the code (a byte for a simple font, a CID for a CID font)
     * @return the glyph name, or null
     */
    private String programNameOf(long code) {
        PdfFontProgram.Read read = program();
        if (read == null) {
            return null;
        }
        if (!cidFont && read.canNameCodes()) {
            return read.nameOfCode(code);
        }
        if (cidFont && read.canNameGlyphs()) {
            Long glyph = gidOf(code);
            return glyph == null ? null : read.nameOfGlyph(glyph);
        }
        return null;
    }

    /**
     * Whether the embedded program can name the codes of this font on its own - the verdict that
     * turns {@code mapping.method} into {@code glyph-names} when the dictionary names nothing.
     *
     * @return true when a name source exists in the program
     */
    public boolean programNamesCodes() {
        PdfFontProgram.Read read = program();
        if (read == null) {
            return false;
        }
        if (!cidFont) {
            return read.canNameCodes();
        }
        return read.canNameGlyphs();
    }

    /** Whether the dictionary names ZapfDingbats as its base table (not: whether it looks so). */
    private boolean isZapfBase() {
        return "ZapfDingbatsEncoding".equals(encoding.shape().baseEncoding());
    }

    private Mapping lookup(PdfCMap cmap, String codeHex, String label) {
        if (cmap == null) {
            return null;
        }
        int[] codePoints = cmap.lookup(codeHex);
        if (codePoints == null) {
            return null;
        }
        String source = cmap.sourceOf(codeHex);
        return new Mapping(codePoints, label + (source != null ? ":" + source : ""));
    }

    // ===================================================================
    // CID -> GID, and widths
    // ===================================================================

    /**
     * The glyph index a CID selects. {@code /Identity} (and its default for {@code CIDFontType2})
     * says CID = GID; a {@code /CIDToGIDMap} stream says otherwise and is read as the 16-bit field
     * it is. A {@code CIDFontType0} without the entry has nothing to answer - the default belongs
     * to {@code CIDFontType2} only.
     *
     * @param cid the CID
     * @return the GID, or null when the font does not say
     */
    public Long gidOf(long cid) {
        if (!cidFont) {
            return null;
        }
        Object value = null;
        for (PdfFont.ChainEntry entry : cidChain) {
            if (entry.dict().get("CIDToGIDMap") != null) {
                value = entry.dict().get("CIDToGIDMap");
                break;
            }
        }
        if (value == null) {
            return !cidChain.isEmpty()
                    && PdfName.dictHas(cidChain.get(0).dict(), "Subtype", "CIDFontType2")
                    && cid <= 0xFFFFL ? cid : null;
        }
        Object resolved = document.dereference(value);
        if (resolved instanceof PdfName name) {
            return name.equalsName("Identity") && cid <= 0xFFFFL ? cid : null;
        }
        if (!(resolved instanceof PdfStream stream)) {
            return null;
        }
        if (!cidToGidRead) {
            cidToGidRead = true;
            try {
                cidToGid = PdfCidToGidMap.read(document.decodedStream(stream, notes));
                if (cidToGid.warning() != null) {
                    note(NOTE_CID_TO_GID_SHORT, cidToGid.warning());
                }
            } catch (PdfException e) {
                note(NOTE_CID_TO_GID_SHORT, "the /CIDToGIDMap stream could not be read ("
                        + e.getMessage() + "): the glyph indices stay unknown");
            }
        }
        Integer gid = cidToGid == null ? null : cidToGid.gidOf(cid);
        if (gid == null && !gidShortNoted) {
            // One sentence per font: a string of 300 CIDs beyond the map is one statement.
            gidShortNoted = true;
            note(NOTE_CID_TO_GID_SHORT, "the /CIDToGIDMap stream holds "
                    + (cidToGid == null ? 0 : cidToGid.entries()) + " entries, which does not reach"
                    + " CID " + cid + ": its glyph index stays unknown");
        }
        return gid == null ? null : (long) (int) gid;
    }

    /** The dictionary widths of this font ({@code /W}, {@code /DW}, {@code /Widths}). */
    public PdfFontWidths widths() {
        if (widths == null) {
            widths = PdfFontWidths.of(document, font, cidChain);
        }
        return widths;
    }

    /**
     * The glyph a code selects - the index the program's own tables are addressed by. Two answers,
     * and both come from the file: a CID font names its glyph through {@code /CIDToGIDMap}
     * ({@link #gidOf(long)}), a simple font through the {@code cmap} of its embedded program, which
     * is the lookup a viewer performs for it. Selection is not meaning - a program's {@code cmap}
     * may be a user-defined table and still be the right one to ask here, see
     * {@link PdfTrueTypeProgram#glyphOfCode(long)} for that difference.
     *
     * @param code the code (a simple font's byte value, a CID for a CID font)
     * @return the glyph index, or null when neither the font nor its program says which glyph it is
     */
    public Long glyphOf(long code) {
        if (cidFont) {
            return gidOf(code);
        }
        PdfTrueTypeProgram sfnt = programSfnt();
        Integer glyph = sfnt == null ? null : sfnt.glyphOfCode(code);
        return glyph == null ? null : (long) (int) glyph;
    }

    /**
     * A width with the source that answered for it. The dictionary comes first - a producer that
     * writes {@code /W} writes what it wants drawn - and the embedded program answers where the
     * dictionary is silent. The order between a {@code /W} entry and the {@code /DW} default is the
     * specification's; the program only steps in where neither has a value of its own, because
     * "the default 1000" is not a measurement of a glyph.
     *
     * @param code the code (a simple font's byte value) or CID
     * @return the width and its source - {@code W}, {@code Widths}, {@code hmtx}, {@code DW} or
     *         {@code none}; the width is null exactly when the source is {@code none}
     */
    public Width widthOf(long code) {
        PdfFontWidths dictionary = widths();
        if (dictionary.names(code)) {
            return new Width(dictionary.widthOf(code), dictionary.source());
        }
        Number fromProgram = programWidthOf(code);
        if (fromProgram != null) {
            return new Width(fromProgram, PdfFontWidths.SOURCE_HMTX);
        }
        Number fallback = dictionary.widthOf(code);
        if (fallback != null) {
            // Only a CIDFont has a default of its own; naming the source "DW" says that the number
            // is the dictionary's standing answer rather than a measured width for this code.
            return new Width(fallback, dictionary.names(code) ? dictionary.source()
                    : PdfFontWidths.SOURCE_DW);
        }
        return new Width(null, dictionary.source());
    }

    /**
     * A width and who said it.
     *
     * @param width  the width on the dictionary's 1000-unit base, or null
     * @param source {@code W}, {@code Widths}, {@code hmtx}, {@code DW} or {@code none}
     */
    public record Width(Number width, String source) {
    }

    /**
     * The vertical metrics of a code - {@code w1} (the displacement vector of writing mode 1) and
     * {@code v} (the position vector) in the specification's words. Only a CIDFont dictionary can
     * answer; where it declares nothing and the font is not shown vertically there is nothing to
     * answer ({@link PdfFontWidths.Vertical#source()} says {@code none}).
     * @param code the code or CID
     * @return the vertical metrics
     */
    public PdfFontWidths.Vertical verticalOf(long code) {
        return widths().verticalOf(code);
    }

    /** Whether this font's dictionary declares vertical metrics ({@code /W2} or {@code /DW2}). */
    public boolean hasVerticalMetrics() {
        return widths().hasVerticalMetrics();
    }

    /**
     * Metric entries the dictionary shaped wrongly, so that a number which is really a default is
     * never read as a measurement (PDF 1.7 5.6.3 shapes {@code /W}, {@code /DW}, {@code /W2} and
     * {@code /DW2}; anything else is the file's mistake and is named).
     */
    public List<String> widthDefects() {
        return widths().defects();
    }

    /** The width the embedded program's {@code hmtx} gives this code, converted, or null. */
    private Number programWidthOf(long code) {
        PdfTrueTypeProgram sfnt = programSfnt();
        if (sfnt == null || !sfnt.hasMetrics()) {
            return null;
        }
        Integer glyph;
        if (cidFont) {
            Long glyphIndex = gidOf(code);
            glyph = glyphIndex == null ? null : (int) (long) glyphIndex;
        } else {
            // A simple font selects its glyph through the program's own cmap - the same lookup a
            // viewer performs, which is why a user-defined subtable is good enough for a width.
            glyph = sfnt.glyphOfCode(code);
        }
        if (glyph == null) {
            return null;
        }
        if (sfnt.isEmpty(glyph)) {
            // A subset that dropped the glyph also dropped its metrics: the zero in hmtx is the
            // width of an empty box, not a measurement of the glyph. Where the dictionary has a
            // default of its own (/DW), that one answers - which is why this returns nothing.
            return null;
        }
        return sfnt.advanceInTextUnits(glyph);
    }

    /** Whether this font's program can answer a width (its {@code hmtx} is readable). */
    public boolean hasProgramMetrics() {
        return program() != null && program().canMeasure();
    }

    // ===================================================================
    // The embedded program (step 4 and the widths of hmtx)
    // ===================================================================

    /**
     * The program this font embeds, read on the first question that needs it - and then kept, so a
     * walk over a hundred pages parses a subset once. A Type 1 or CFF program is identified and
     * answered with the sentence that says its reader is the next step; what cannot be read is
     * never an exception.
     *
     * @return the read program, or null when the font embeds none
     */
    public PdfFontProgram.Read program() {
        if (programLooked) {
            return program;
        }
        programLooked = true;
        Object[] pick = PdfFontProgram.pick(fontFiles);
        if (pick == null) {
            return null;
        }
        program = PdfFontProgram.read(document, (int) (long) (Long) pick[1],
                String.valueOf(pick[0]), true, notes);
        return program;
    }

    /**
     * The sfnt tables of the embedded program, or null when it is not an sfnt. Public because
     * {@code pdf.glyphMetrics()} asks it for {@code hmtx}/{@code glyf} values - the reader of a
     * format stays the only one that touches that format's bytes (decision 4 of the plan), and the
     * program itself is opened lazily and once per call (decision 5).
     */
    public PdfTrueTypeProgram programSfnt() {
        PdfFontProgram.Read read = program();
        return read == null ? null : read.sfnt();
    }

    /** Whether the font says which glyph a CID selects (the {@code /CIDToGIDMap} verdict). */
    public boolean glyphIndexKnown() {
        return glyphIndexKnown(document, font, chain);
    }

    /**
     * Whether a font says which glyph a CID selects, without reading the map itself:
     * {@code /Identity} says it, a {@code /CIDToGIDMap} <em>stream</em> says it (and is read when a
     * code asks), and its absence says it only for {@code CIDFontType2} - the default belongs to
     * that subtype alone, so a {@code CIDFontType0} without the entry has no answer to give.
     *
     * @param document the document (the entry may sit behind a reference)
     * @param font the font dictionary
     * @param chain the descendant chain (may be empty)
     * @return whether a glyph index can be derived from a CID
     */
    public static boolean glyphIndexKnown(PdfDocument document, Map<String, Object> font,
                                          List<PdfFont.ChainEntry> chain) {
        List<PdfFont.ChainEntry> cidChain = chain == null || chain.isEmpty()
                ? (PdfFont.isCidFontDict(font)
                ? List.of(new PdfFont.ChainEntry(null, font)) : List.of())
                : chain;
        if (cidChain.isEmpty()) {
            return false;
        }
        Object value = null;
        for (PdfFont.ChainEntry entry : cidChain) {
            if (entry.dict().get("CIDToGIDMap") != null) {
                value = entry.dict().get("CIDToGIDMap");
                break;
            }
        }
        if (value == null) {
            return PdfName.dictHas(cidChain.get(0).dict(), "Subtype", "CIDFontType2");
        }
        Object resolved = document.dereference(value);
        if (resolved instanceof PdfName name) {
            return name.equalsName("Identity");
        }
        return resolved instanceof PdfStream;
    }

    /** Step 4: the character the program's own {@code cmap} names for a glyph. */
    private Mapping fromProgramCmap(long glyph) {
        PdfTrueTypeProgram sfnt = programSfnt();
        if (sfnt == null) {
            return null;
        }
        Integer codePoint = sfnt.unicodeOf((int) glyph);
        if (codePoint == null) {
            return null;
        }
        if (sfnt.isAmbiguous((int) glyph) && !ambiguityNoted) {
            ambiguityNoted = true;
            Integer first = sfnt.firstAmbiguousGlyph();
            note("program-cmap-ambiguous", "the /cmap of the embedded program names "
                    + sfnt.ambiguousGlyphs() + " glyph(s) more than once (first: glyph " + first
                    + "): its smallest code point is used, which is a choice and not a fact");
        }
        return new Mapping(new int[]{codePoint}, "cmap-table:" + sfnt.mappingFormat());
    }

    // ===================================================================
    // Lazy sources
    // ===================================================================

    private PdfCMap ownCmap() {
        if (!ownCmapRead) {
            ownCmapRead = true;
            ownCmap = readCmap(refNum(font, "ToUnicode"), "the /ToUnicode stream");
        }
        return ownCmap;
    }

    private PdfCMap descendantCmap() {
        if (!descendantCmapRead) {
            descendantCmapRead = true;
            for (PdfFont.ChainEntry entry : chain) {
                Integer objNum = refNum(entry.dict(), "ToUnicode");
                if (objNum != null) {
                    descendantCmap = readCmap(objNum, "the descendant's /ToUnicode stream");
                    break;
                }
            }
        }
        return descendantCmap;
    }

    /** Reads one CMap stream and compares its code width with what the encoding says. */
    private PdfCMap readCmap(Integer objNum, String what) {
        if (objNum == null) {
            return null;
        }
        PdfCMap cmap;
        try {
            cmap = PdfCMap.parse(document.stream(objNum).data(), PdfCMap.MAX_ENTRIES);
        } catch (PdfException e) {
            note(NOTE_CMAP_UNREADABLE, what + " " + objNum + " could not be read ("
                    + e.getMessage() + "): the map stays unread");
            return null;
        }
        Integer width = encoding.codeBytes();
        if (width != null && cmap.codeBytes() > 0 && !width.equals(cmap.codeBytes())) {
            note(NOTE_CODESPACE_MISMATCH, "code-space mismatch: the font's /Encoding says "
                    + width + "-byte codes, " + what + " " + objNum + " maps "
                    + cmap.codeBytes() + "-byte codes - its entries cannot address this font's"
                    + " strings");
        }
        if (cmap.isEmpty()) {
            note(NOTE_CMAP_EMPTY, what + " " + objNum + " holds no mapping entry: the codes stay"
                    + " codes");
        }
        return cmap;
    }

    private static Integer refNum(Map<?, ?> dict, String key) {
        return dict.get(key) instanceof PdfReference reference ? reference.objNum() : null;
    }

    private static long valueOf(String hex) {
        long value = 0;
        for (int i = 0; i < hex.length(); i++) {
            int digit = Character.digit(hex.charAt(i), 16);
            if (digit < 0) {
                return -1;
            }
            value = (value << 4) | digit;
        }
        return value;
    }

    /**
     * Adds one sentence, once. The key is the plan's catalog key - it is not part of the text (a
     * user reads sentences, tests hold them by wording) and two different statements may share it
     * on purpose, so the guard is the sentence itself and not the key.
     */
    private void note(String key, String sentence) {
        if (!notes.contains(sentence)) {
            notes.add(sentence);
        }
    }
}
