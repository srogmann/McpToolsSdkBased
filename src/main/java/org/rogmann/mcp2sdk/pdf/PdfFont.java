package org.rogmann.mcp2sdk.pdf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Describes a <em>font dictionary</em> (ISO 32000-1, 9.5-9.8): what kind of font it is, where its
 * program and its maps are, and how wide its glyphs are <em>according to the dictionary</em>.
 * <p>
 * Three questions, three entries:
 * </p>
 * <ul>
 *   <li>{@link #references} - which fonts does one page name in its resources (and, on request,
 *       the resources of its form XObjects),</li>
 *   <li>{@link #inventory} - the compact per-page listing that {@code pdf.text()} and
 *       {@code pdf.contentStreams()} attach to a page, so a caller can tell readable text from
 *       codes,</li>
 *   <li>{@link #describe} - the object sheet behind {@code pdf.font(path, objNum)}: subtype,
 *       BaseFont, encoding (see {@link PdfEncoding}), descendant chain, descriptor, embedded
 *       programs and the {@code /ToUnicode} summary.</li>
 * </ul>
 * <p>
 * What this class deliberately does <em>not</em> do: turn a code into a character (that is the
 * mapping chain - {@code PdfGlyphMapper} - which needs {@link PdfCMap} and the Adobe Glyph List),
 * and read widths out of an embedded program ({@code hmtx}, CFF widths - reading a font program is
 * the next step). It says where the widths <em>are</em>: {@code W} (a CIDFont's {@code /W}, in all
 * three forms {@code c [w…]}, {@code cFirst cLast w} and {@code c []}, which means "the default
 * width applies"), {@code Widths} (a simple font's {@code /FirstChar}+{@code /Widths}, gaps shown
 * as {@code null} instead of as zero) or {@code none}.
 * </p>
 * <p>
 * Like every other reader here it degrades instead of guessing: a dictionary that is not a font
 * says which required entry is missing, a {@code /Widths} array that ends early keeps its
 * {@code null} entries plus one note, and a listing that reaches a budget marks
 * {@code truncated} where the cut happened.
 * </p>
 */
public final class PdfFont {

    /** Default number of listed items of one font (width ranges, differences, codes). */
    public static final int DEFAULT_MAX_ITEMS = 200;

    /** Default number of fonts {@code pdf.fonts()} lists. */
    public static final int DEFAULT_LIST_LIMIT = 500;

    /** Width entries one font describes at most (a {@code /W} of 65 000 values is not a list). */
    public static final int MAX_WIDTH_ENTRIES = 50_000;

    /** How deep a {@code /DescendantFonts} chain is followed. */
    public static final int MAX_DESCENDANT_DEPTH = 4;

    /** Form XObjects whose resources are opened per page when {@code formXObjects: true}. */
    public static final int MAX_FORM_XOBJECTS = 200;

    /** {@code /DW} of a CIDFont when the dictionary omits it (the specification's default). */
    public static final long DEFAULT_CID_WIDTH = 1000;

    /** Dictionary keys that carry an embedded program (the first three live in the descriptor). */
    private static final List<String> FONT_FILE_KEYS =
            List.of("FontFile", "FontFile2", "FontFile3", "CIDSet");

    /** Mappings that mean "the codes of this font can be turned into characters". */
    public static final Set<String> RESOLVABLE =
            Set.of("toUnicode", "toUnicode-descendant", "unicode-code", "differences",
                    "cmap-table", "glyph-names");

    /** Mappings that mean "what the content stream holds stays a code". */
    public static final Set<String> CODES_ONLY =
            Set.of("none", "predefined-cmap-unsupported", "builtin-encoding");

    /** Note keys of the plan's catalog that this class can raise (fixed wording, so that tests
     * and users can hold the reader to it). */
    public static final String NOTE_NOT_A_FONT = "not-a-font";
    public static final String NOTE_SUBTYPE_MISSING = "subtype-missing";
    public static final String NOTE_BASE_FONT_MISSING = "base-font-missing";
    public static final String NOTE_DESCENDANT_MISSING = "descendant-missing";
    public static final String NOTE_WIDTHS_MISMATCH = "widths-mismatch";
    public static final String NOTE_WIDTHS_CUT = "widths-cut";
    public static final String NOTE_W_ENTRY_SKIPPED = "w-entry-skipped";
    public static final String NOTE_LIST_CUT = "font-list-cut";
    public static final String NOTE_SCAN_PARTIAL = "font-scan-partial";
    /**
     * Raised since D0b by {@link PdfResources} (one sentence for every resource whose object
     * cannot be read, form XObjects included), so a broken {@code /XObject} and a broken
     * {@code /Font} entry are explained by the one reader that looked at both.
     */
    public static final String NOTE_XOBJECT_UNREADABLE = PdfResources.NOTE_RESOURCE_UNREADABLE;
    public static final String NOTE_CMAP_UNREADABLE = "cmap-unreadable";
    public static final String NOTE_FONTS_UNREADABLE = "fonts-unreadable";
    /** More form XObjects on one page than {@link #MAX_FORM_XOBJECTS} opens. */
    public static final String NOTE_FORMS_CUT = "form-xobjects-cut";

    /**
     * Note key of a page that draws with a CID font whose identity encoding has no
     * {@code /ToUnicode} map - the one listing case in which the dictionary alone already knows
     * that the text of the page cannot be characters.
     */
    public static final String NOTE_CID_UNMAPPED = "cid-without-tounicode";

    /** Bit names of the descriptor's {@code /Flags} (Table 123), lowest bit first. */
    private static final Map<Long, String> FLAGS = new LinkedHashMap<>();

    static {
        FLAGS.put(1L, "fixedpitch");
        FLAGS.put(2L, "serif");
        FLAGS.put(4L, "symbolic");
        FLAGS.put(8L, "script");
        FLAGS.put(32L, "nonsymbolic");
        FLAGS.put(64L, "italic");
        FLAGS.put(128L, "allserif");
        FLAGS.put(256L, "mixedfamily");
        FLAGS.put(512L, "allcap");
        FLAGS.put(1024L, "smallcap");
        FLAGS.put(2048L, "forcebold");
    }

    /**
     * One font resource as a page (or a form XObject) names it.
     *
     * @param resourceName the resource name with slash; a form XObject font reads
     *                     {@code "/F0@XObject/Fm1"}
     * @param objNum       object number of the font dictionary, or null when the resource holds
     *                     the dictionary inline
     * @param dict         the resolved font dictionary
     */
    public record Ref(String resourceName, Integer objNum, Map<String, Object> dict) {
    }

    private PdfFont() {
        // static access only
    }

    // ===================================================================
    // Which fonts does a page name
    // ===================================================================

    /**
     * Collects the font dictionaries of a page's resources - the {@link PdfResources} lookup of
     * the category {@code Font}, and nothing else: which dictionary a page uses (its own, or a
     * {@code /Pages} ancestor's), that a name may hold a value inline instead of a reference and
     * that a lost object is a finding all live in that class, so the six other resource
     * categories of a page are answered the same way as this one.
     * <p>
     * With {@code formXObjects} the {@code /Font} dictionaries of the page's form XObjects are
     * collected too, named {@code "/F1@XObject/X1"}. A form that names no {@code /Resources} of
     * its own is read through the <em>page's</em> dictionary (PDF 1.7 3.7.2) - its fonts then
     * appear a second time under the form's name, which is what the specification answers and
     * what a listing of the form alone never showed.
     * </p>
     * @param document the document
     * @param pageObjNum page object number
     * @param formXObjects whether the resources of the page's form XObjects are collected too
     * @param notes note/warning sink (never null)
     * @return the fonts in resource-name order, form XObject fonts last
     */
    public static List<Ref> references(PdfDocument document, int pageObjNum, boolean formXObjects,
                                       List<String> notes) {
        List<Ref> fonts = new ArrayList<>();
        if (pageObjNum < 0) {
            return fonts;
        }
        PdfResources page = PdfResources.ofPage(document, pageObjNum, notes);
        collectFonts(page, "", fonts, notes);
        if (!formXObjects) {
            return fonts;
        }
        int opened = 0;
        for (PdfResources.Entry xObject : page.list("XObject")) {
            if (!"/Form".equals(xObject.subtype())) {
                continue;
            }
            if (opened >= MAX_FORM_XOBJECTS) {
                note(notes, NOTE_FORMS_CUT, "Only the first " + MAX_FORM_XOBJECTS
                        + " form XObject(s) of page object " + pageObjNum
                        + " were opened for fonts (maxFormXObjects)");
                break;
            }
            opened++;
            PdfResources form = PdfResources.ofStream(document, xObject.dict(),
                    "form XObject " + xObject.resourceName(), page, notes);
            collectFonts(form, "@XObject/" + xObject.resourceName().substring(1), fonts, notes);
        }
        return fonts;
    }

    /**
     * The {@code Font} entries of one resolved dictionary, as this class names them. A name whose
     * value is no dictionary stays out of the listing and says why; a lost object was already
     * explained by {@link PdfResources}.
     */
    private static void collectFonts(PdfResources resources, String suffix, List<Ref> fonts,
                                     List<String> notes) {
        for (PdfResources.Entry entry : resources.list("Font")) {
            if (!"dictionary".equals(entry.shape())) {
                if (!entry.missing()) {
                    note(notes, NOTE_FONTS_UNREADABLE, "/Resources /Font " + entry.resourceName()
                            + suffixOf(suffix) + " is " + entry.shape()
                            + " instead of a dictionary");
                }
                continue;
            }
            fonts.add(new Ref(entry.resourceName() + suffix, entry.objNum(), entry.dict()));
        }
    }

    /**
     * The same entries as a plain list - the page's text reading ({@link PdfFontScope}) asks this
     * way, so a name means the same thing there as in {@code pdf.fonts()}.
     *
     * @param resources the resolved dictionary (usually {@link PdfResources#ofPage})
     * @param notes     note/warning sink (never null)
     * @return the fonts of that dictionary, in name order
     */
    public static List<Ref> fontEntries(PdfResources resources, List<String> notes) {
        List<Ref> fonts = new ArrayList<>();
        collectFonts(resources, "", fonts, notes);
        return fonts;
    }

    private static String suffixOf(String suffix) {
        return suffix.isEmpty() ? "" : " (of " + suffix + ")";
    }

    // ===================================================================
    // The per-page inventory (pdf.text(), pdf.contentStreams())
    // ===================================================================

    /**
     * Describes the fonts of a page so the caller can judge the extracted text (the fonts are not
     * measured, because no text was read through them).
     * @param document the document
     * @param pageObjNum the page object number
     * @param notes note/warning sink (never null)
     * @return {@code {fonts: [...]}} or an empty map when the page names no font
     */
    public static Map<String, Object> inventory(PdfDocument document, int pageObjNum,
                                                List<String> notes) {
        return inventory(document, pageObjNum, null, notes);
    }

    /**
     * Describes the fonts of a page so the caller can judge the extracted text.
     * <p>
     * Next to the dictionary facts - {@code objNum} (read the object), {@code toUnicodeObjNum}
     * (read the map with {@code pdf.cmap()}), {@code mapping} (which source could resolve the
     * codes), {@code codeBytes} (how wide a code is) and {@code widths} (where the widths are) -
     * every entry answers {@code reliableText}. Given a {@code scope} (a page whose text was read
     * through its fonts) that answer is a <em>measurement</em>: this much of what the font drew
     * became characters, this much stayed codes, and the codes are named next to the verdict.
     * Without a scope - or for a font no {@code Tf} used - the dictionary says what it can: a CID
     * font whose identity encoding has no map holds glyph indices and is not reliable, a simple
     * font that the document convention reads correctly is.
     * </p>
     * @param document the document
     * @param pageObjNum the page object number
     * @param scope the page's fonts as the text extraction used them, or null
     * @param notes note/warning sink (never null)
     * @return {@code {fonts: [...]}} or an empty map when the page names no font
     */
    public static Map<String, Object> inventory(PdfDocument document, int pageObjNum,
                                                PdfFontScope scope, List<String> notes) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> fonts = fontEntries(document, pageObjNum, scope, notes);
        if (fonts.isEmpty()) {
            return result;
        }
        result.put("fonts", fonts);
        return result;
    }

    /**
     * The font blocks of the fonts a {@code Tf} really named - what
     * {@code pdf.markedContent()} shows next to its sequences. It lists the drawers of the page
     * rather than its whole resource dictionary: a marked-content listing answers what happened
     * here, and a font that never drew is not part of that answer.
     * @param document the document
     * @param scope the scope the text was read with
     * @param notes note/warning sink (never null)
     * @return the list of font blocks (empty when no font drew)
     */
    public static List<Map<String, Object>> usedFontEntries(PdfDocument document,
                                                            PdfFontScope scope,
                                                            List<String> notes) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (scope == null) {
            return out;
        }
        List<Ref> refs = references(document, scope.pageObjNum(), false, notes);
        for (String name : scope.usedFontNames()) {
            for (Ref ref : refs) {
                if (ref.resourceName().equals(name)) {
                    out.add(inventoryEntry(document, scope.pageObjNum(), ref, scope, notes));
                    break;
                }
            }
        }
        return out;
    }

    /** The entries of {@link #inventory}. */
    private static List<Map<String, Object>> fontEntries(PdfDocument document, int pageObjNum,
                                                         PdfFontScope scope, List<String> notes) {
        List<Map<String, Object>> fonts = new ArrayList<>();
        for (Ref ref : references(document, pageObjNum, false, notes)) {
            fonts.add(inventoryEntry(document, pageObjNum, ref, scope, notes));
        }
        return fonts;
    }

    /**
     * One entry of the per-page listing: the dictionary facts, plus the measurement of the page
     * that drew with the font when there is one.
     */
    private static Map<String, Object> inventoryEntry(PdfDocument document, int pageObjNum, Ref ref,
                                                      PdfFontScope scope, List<String> notes) {
        Map<String, Object> font = ref.dict();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("resourceName", ref.resourceName());
        if (ref.objNum() != null) {
            info.put("objNum", (long) (int) ref.objNum());
        }
        putName(info, font, "Subtype", "subtype");
        putName(info, font, "BaseFont", "baseFont");
        putName(info, font, "Encoding", "encoding");
        boolean toUnicode = font.containsKey("ToUnicode");
        info.put("toUnicode", toUnicode);
        Integer mapAddress = toUnicodeAddress(document, font);
        if (mapAddress != null) {
            info.put("toUnicodeObjNum", (long) (int) mapAddress);
        }
        PdfEncoding encoding = PdfEncoding.of(document, font);
        List<ChainEntry> chain = descendantChain(document, font, notes);
        String mapping = mapping(document, font, chain, encoding);
        info.put("mapping", mapping);
        info.put("widths", widthSource(document, font, chain));
        info.put("codeBytes", encoding.codeBytes() == null ? null
                : (long) (int) encoding.codeBytes());

        Map<String, Object> measurement =
                scope == null ? null : scope.measurement(ref.resourceName());
        if (measurement != null) {
            // Measured on this page: what the font drew, what became characters, what stayed codes.
            info.putAll(measurement);
            String measuredMethod = scope.methodOf(ref.resourceName());
            if (measuredMethod != null) {
                // The dictionary's guess about resolvability is replaced by what actually answered -
                // a subset whose codes came out of the embedded program's /cmap does not get to
                // stand there as a font nothing can resolve.
                info.put("mapping", measuredMethod);
            }
            boolean left = scope.leftCodes(ref.resourceName());
            info.put("reliableText", !left);
            if (left) {
                info.put("reason", leftBehindSentence(measurement));
            }
            return info;
        }
        // Nothing was drawn with this font here, so only the dictionary can answer - and it answers
        // less than the measurement would.
        PdfName subtype = PdfName.ofDict(font, "Subtype");
        if (subtype != null && subtype.equalsName("Type0")) {
            PdfName encodingName = PdfName.ofDict(font, "Encoding");
            boolean identity = encodingName == null
                    || encodingName.value().startsWith("Identity");
            if (identity && !toUnicode) {
                info.put("reliableText", false);
                info.put("reason", "CID font with an identity encoding and no /ToUnicode map:"
                        + " the content stream holds glyph indices, not characters (nothing of"
                        + " this font was drawn here, so its embedded program was not read either"
                        + " - pdf.font() says whether its /cmap would answer)");
                note(notes, NOTE_CID_UNMAPPED, "Page object " + pageObjNum
                        + " uses a CID font without /ToUnicode: its codes are glyph indices, so the"
                        + " text of this page leaves them out (pdf.fonts(path, {page: …}) names the"
                        + " font, pdf.decodeString() maps one string through it, pdf.font() reads"
                        + " the /cmap of its embedded program)");
            }
        } else if (CODES_ONLY.contains(mapping) && !isReadByDocumentConvention(encoding, mapping,
                font)) {
            info.put("reliableText", false);
            info.put("reason", "no source of this font answers its codes (mapping: " + mapping
                    + "): what it draws stays a code");
        } else {
            info.put("reliableText", true);
        }
        return info;
    }

    /**
     * Why the text a font drew on this page is not complete - in the units that are really meant.
     * A string that was never cut into codes is a different statement from a code that no source
     * answers, and the reader has to be able to tell them apart: the first is about the encoding,
     * the second about a missing map entry.
     */
    private static String leftBehindSentence(Map<String, Object> measurement) {
        List<String> parts = new ArrayList<>();
        Object unread = measurement.get("unread");
        Object unmapped = measurement.get("unmapped");
        if (unread != null) {
            parts.add(unread + " string(s) could not be cut into codes at all (its encoding does"
                    + " not say how wide a code is)");
        }
        if (unmapped instanceof Long count && count > 0) {
            parts.add(count + " code(s) stayed a code (no source of this font answers them)");
        }
        if (parts.isEmpty()) {
            parts.add("the call's code budget stopped before its strings were read");
        }
        return "of what this font drew on this page, " + String.join(" and ", parts)
                + ": the text shows what the font can say and leaves the rest out";
    }

    /**
     * Whether the font is left to the document convention - a simple one-byte font that names no
     * {@code /ToUnicode}, no {@code /Differences} and no UCS encoding (the same verdict
     * {@link PdfGlyphMapper#isDocumentConvention()} reaches, derived from the dictionary here
     * because the listing does not build a chain).
     */
    private static boolean isReadByDocumentConvention(PdfEncoding encoding, String mapping,
                                                      Map<String, Object> font) {
        if (isCidFont(font) || !("none".equals(mapping) || "builtin-encoding".equals(mapping))) {
            return false;
        }
        return encoding.codeBytes() != null && encoding.codeBytes() == 1;
    }

    private static void putName(Map<String, Object> target, Object dict, String key, String name) {
        PdfName value = PdfName.ofDict(dict, key);
        if (value != null) {
            target.put(name, value.toString());
        }
    }

    // ===================================================================
    // The font list (pdf.fonts())
    // ===================================================================

    /**
     * Lists every font the walked pages name, together with the pages that really use it.
     * @param document the document
     * @param pageIndex 1-based page number, or null for the whole walk
     * @param maxPages page limit of the walk (ignored when {@code pageIndex} is given)
     * @param formXObjects whether form XObject resources are collected too
     * @param full whether each entry also carries the sheet {@link #describe} writes
     * @param limit fonts listed (0 = no limit)
     * @return {@code {fonts, summary, truncated, notes}}
     */
    public static Map<String, Object> listing(PdfDocument document, Integer pageIndex,
                                              int maxPages, boolean formXObjects, boolean full,
                                              int limit) {
        NoteSink notes = new NoteSink();
        int walkLimit = pageIndex != null ? Math.max(1, pageIndex) : Math.max(1, maxPages);
        List<String> walkNotes = new ArrayList<>();
        List<PdfDocument.Page> pages = document.pages(walkLimit, walkNotes);
        if (pageIndex != null && pages.size() < pageIndex) {
            throw new PdfException("There is no page " + pageIndex + " in " + document.displayName()
                    + " (" + pages.size() + " page(s) found)");
        }
        // One page more is a cheap probe: "stopped at maxPages" may only be said when there really
        // were further pages, not when the document happens to end exactly there. A page filter is
        // no truncation - the answer is about that page, and it is complete for it.
        int probeLimit = walkLimit == Integer.MAX_VALUE ? walkLimit : walkLimit + 1;
        boolean partial = pageIndex == null && pages.size() >= walkLimit
                && document.pages(probeLimit, new ArrayList<>()).size() > pages.size();
        if (partial) {
            notes.add(NOTE_SCAN_PARTIAL, "fonts were collected from the first " + pages.size()
                    + " page(s), the document has more (maxPages=" + walkLimit
                    + "): pages/pageCount are a subset");
        } else {
            for (String walkNote : walkNotes) {
                notes.raw(walkNote);
            }
        }

        Map<String, Map<String, Object>> usageOf = new LinkedHashMap<>();
        Map<String, Ref> refsByKey = new LinkedHashMap<>();
        Map<String, List<Object>> namesByKey = new LinkedHashMap<>();
        Map<String, String> mappingByKey = new LinkedHashMap<>();
        int formFonts = 0;
        int type0 = 0;
        int simple = 0;
        int resolvable = 0;
        int codesOnly = 0;
        for (PdfDocument.Page page : pages) {
            if (pageIndex != null && page.page() != pageIndex) {
                continue;
            }
            List<Ref> refs = references(document, page.objNum(), formXObjects, notes.list());
            Map<String, Long> usage = textUsage(document, page, notes.list());
            for (Ref ref : refs) {
                String key = ref.objNum() != null ? "o" + ref.objNum()
                        : "d" + System.identityHashCode(ref.dict());
                Ref previous = refsByKey.get(key);
                if (previous == null) {
                    refsByKey.put(key, ref);
                    usageOf.put(key, new LinkedHashMap<>());
                    namesByKey.put(key, new ArrayList<>());
                }
                List<Object> names = namesByKey.get(key);
                if (!names.contains(ref.resourceName())) {
                    names.add(ref.resourceName());
                }
                if (ref.resourceName().contains("@XObject")) {
                    formFonts++;
                }
                long shown = usage.getOrDefault(ref.resourceName(), 0L);
                if (shown > 0) {
                    Map<String, Object> collected = usageOf.get(key);
                    @SuppressWarnings("unchecked")
                    List<Object> pageList = (List<Object>) collected.computeIfAbsent("pages",
                            k -> new ArrayList<Object>());
                    if (!pageList.contains((long) page.page())) {
                        pageList.add((long) page.page());
                    }
                    collected.put("showTextOps",
                            countOf(collected.get("showTextOps")) + shown);
                }
                if (previous == null) {
                    PdfName subtype = PdfName.ofDict(ref.dict(), "Subtype");
                    if (subtype != null && subtype.equalsName("Type0")) {
                        type0++;
                    } else {
                        simple++;
                    }
                    String mapping = mapping(document, ref.dict(),
                            descendantChain(document, ref.dict(), notes.list()),
                            PdfEncoding.of(document, ref.dict()));
                    mappingByKey.put(key, mapping);
                    if (RESOLVABLE.contains(mapping)) {
                        resolvable++;
                    }
                    if (CODES_ONLY.contains(mapping)) {
                        codesOnly++;
                    }
                }
            }
        }

        List<Map<String, Object>> listed = new ArrayList<>();
        boolean listCut = false;
        for (String key : sortedKeys(namesByKey)) {
            if (limit > 0 && listed.size() >= limit) {
                listCut = true;
                break;
            }
            listed.add(listingEntry(document, refsByKey.get(key), usageOf.get(key),
                    namesByKey.get(key), mappingByKey.get(key), full, notes));
        }
        if (listCut) {
            notes.add(NOTE_LIST_CUT, refsByKey.size() + " font(s) were found but only "
                    + listed.size() + " are listed (limit): raise limit to see the rest");
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("count", (long) refsByKey.size());
        summary.put("listed", (long) listed.size());
        summary.put("type0", (long) type0);
        summary.put("simple", (long) simple);
        summary.put("resolvable", (long) resolvable);
        summary.put("codesOnly", (long) codesOnly);
        summary.put("formXObjectFonts", (long) formFonts);
        summary.put("pagesWalked", (long) (pageIndex != null ? 1 : pages.size()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fonts", listed);
        result.put("summary", summary);
        result.put("truncated", listCut || partial);
        if (!notes.sentences().isEmpty()) {
            result.put("notes", new ArrayList<Object>(notes.sentences()));
        }
        return result;
    }

    /** The keys of the collected fonts, ordered by their first resource name. */
    private static List<String> sortedKeys(Map<String, List<Object>> namesByKey) {
        List<String> keys = new ArrayList<>(namesByKey.keySet());
        keys.sort((a, b) -> first(namesByKey.get(a)).compareTo(first(namesByKey.get(b))));
        return keys;
    }

    private static String first(List<Object> names) {
        return names == null || names.isEmpty() ? "" : String.valueOf(names.get(0));
    }

    /** Builds one entry of {@link #listing}. */
    private static Map<String, Object> listingEntry(PdfDocument document, Ref ref,
                                                    Map<String, Object> usage, List<Object> names,
                                                    String mapping, boolean full, NoteSink notes) {
        Map<String, Object> font = ref.dict();
        List<String> blockNotes = new ArrayList<>();
        List<ChainEntry> chain = descendantChain(document, font, blockNotes);
        PdfEncoding encoding = PdfEncoding.of(document, font);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("resourceName", first(names));
        if (names != null && names.size() > 1) {
            item.put("resourceNames", new ArrayList<Object>(names));
        }
        item.put("objNum", ref.objNum() == null ? null : (long) (int) ref.objNum());
        PdfName subtype = PdfName.ofDict(font, "Subtype");
        if (subtype != null) {
            item.put("subtype", subtype.toString());
        }
        putName(item, font, "BaseFont", "baseFont");
        item.put("kind", kind(subtype));
        item.put("encodingKind", encoding.kind());
        String encodingName = encoding.shape().name();
        if (encodingName != null) {
            item.put("encoding", encodingName);
        }
        item.put("codeBytes",
                encoding.codeBytes() == null ? null : (long) (int) encoding.codeBytes());
        Integer mapAddress = toUnicodeAddress(document, font);
        if (mapAddress != null) {
            item.put("toUnicode", (long) (int) mapAddress);
            if (!mapAddress.equals(refNum(font, "ToUnicode"))) {
                item.put("toUnicodeFrom", "descendant");
            }
        }
        if (!chain.isEmpty() && chain.get(0).objNum() != null) {
            item.put("descendant", (long) (int) chain.get(0).objNum());
        }
        Object cidToGid = cidToGidMap(document, chain, 0);
        if (cidToGid != null) {
            item.put("cidToGidMap", cidToGid);
        }
        item.put("embedded", embeddedNames(document, font, chain));
        item.put("widths", widthSource(document, font, chain));
        item.put("mapping", mapping);
        Object pages = usage.get("pages");
        item.put("pages", pages != null ? pages : new ArrayList<Object>());
        item.put("pageCount", (long) ((List<?>) item.get("pages")).size());
        item.put("showTextOps", countOf(usage.get("showTextOps")));
        if (full) {
            item.put("details", describe(document, ref.objNum(), font, "summary", "summary",
                    "summary", DEFAULT_MAX_ITEMS, blockNotes));
        }
        for (String blockNote : blockNotes) {
            notes.raw(blockNote);
        }
        return item;
    }

    /**
     * Counts the text-showing operators that follow a {@code Tf}, per resource name - the
     * difference between "this font is in the resources" and "this font draws here".
     * <p>
     * Since D4 the count is {@link org.rogmann.mcp2sdk.pdf.ops.UseSink} on the walk of the page's
     * content, so the font credited with a showing operator is the one the state carried at it: a
     * {@code Q} takes a {@code Tf} back, and a {@code Tf} whose operands do not fit its registry row
     * sets nothing (the operand defect says so). The numbers themselves are unchanged, which is what
     * {@code pdf.fonts()}'s {@code pages} and {@code showTextOps} are measured against.
     * </p>
     * @param document the document
     * @param page the page
     * @param notes note/warning sink (never null)
     * @return resource name (with slash) → number of showing operators
     */
    public static Map<String, Long> textUsage(PdfDocument document, PdfDocument.Page page,
                                              List<String> notes) {
        byte[] content;
        try {
            content = document.pageContent(page, notes);
        } catch (PdfException e) {
            notes.add("The content of page " + page.page() + " (object " + page.objNum()
                    + ") cannot be read (" + e.getMessage() + "), so its fonts are listed without"
                    + " a use count");
            return new LinkedHashMap<>();
        }
        // What the stream itself has to say (an unparseable byte, say) is deliberately not added to
        // `notes` here - the font listing has never carried it, and this call only wants the counts.
        // D7 decides where a stream's own sentences belong.
        return PdfContentStream.textUsage(content, new ArrayList<>());
    }

    // ===================================================================
    // The object sheet (pdf.font())
    // ===================================================================

    /**
     * Describes one font object: the sheet behind {@code pdf.font(path, objNum)}.
     * @param document the document
     * @param objNum object number of the font dictionary
     * @param toUnicodeMode {@code "summary"} (what the {@code /ToUnicode} map holds), {@code
     *                      "full"} (plus a bounded view of it) or {@code "off"}
     * @param widthsMode {@code "summary"} (counts) or {@code "full"} (plus the values);
     *                   {@code "off"} leaves the widths out
     * @param maxItems items listed per block
     * @param notes note/warning sink (never null)
     * @return the sheet
     * @throws PdfException when the object is not a dictionary at all
     */
    public static Map<String, Object> describe(PdfDocument document, int objNum,
                                               String toUnicodeMode, String widthsMode,
                                               int maxItems, List<String> notes) {
        return describe(document, objNum, toUnicodeMode, widthsMode, "summary", maxItems, notes);
    }

    /**
     * Describes one font object, with the embedded program asked for.
     * @param document the document
     * @param objNum object number of the font dictionary
     * @param toUnicodeMode {@code "summary"}, {@code "full"} or {@code "off"}
     * @param widthsMode {@code "summary"}, {@code "full"} or {@code "off"}
     * @param programMode {@code "summary"} (what its tables say), {@code "tables"} (plus the
     *                    directory), {@code "bytes"} (plus the program itself) or {@code "off"}
     * @param maxItems items listed per block
     * @param notes note/warning sink of the <em>call</em> (never null) - what the program has to
     *              say stands in the {@code notes} of its own block
     * @return the sheet
     */
    public static Map<String, Object> describe(PdfDocument document, int objNum,
                                               String toUnicodeMode, String widthsMode,
                                               String programMode, int maxItems,
                                               List<String> notes) {
        Map<String, Object> font = document.dictOf(objNum);
        if (font.isEmpty()) {
            throw new PdfException("Object " + objNum + " of " + document.displayName()
                    + " is not a dictionary, so it cannot be a font");
        }
        return describe(document, (Integer) objNum, font, toUnicodeMode, widthsMode, programMode,
                maxItems, notes);
    }

    /** The sheet of an already resolved dictionary (an inline resource has no object number). */
    private static Map<String, Object> describe(PdfDocument document, Integer objNum,
                                                Map<String, Object> font, String toUnicodeMode,
                                                String widthsMode, String programMode, int maxItems,
                                                List<String> notes) {
        NoteSink sink = new NoteSink();
        List<String> blockNotes = notes == null ? sink.list() : notes;
        Map<String, Object> out = new LinkedHashMap<>();
        if (objNum != null) {
            out.put("objNum", (long) (int) objNum);
        }
        PdfName type = PdfName.ofDict(font, "Type");
        if (type != null) {
            out.put("type", type.toString());
        }
        PdfName subtype = PdfName.ofDict(font, "Subtype");
        if (subtype != null) {
            out.put("subtype", subtype.toString());
        }
        out.put("kind", kind(subtype));
        putName(out, font, "BaseFont", "baseFont");
        String what = objNum != null ? "Object " + objNum : "This font dictionary";
        if (type == null) {
            sink.add(NOTE_NOT_A_FONT, what + " has no /Type: nothing here says it is a font (the"
                    + " specification wants /Type /Font and a /Subtype)");
        } else if (!type.equalsName("Font")) {
            sink.add(NOTE_NOT_A_FONT, "/Type is " + type + ", not /Font - the entries below read"
                    + " the dictionary as if it were one");
        }
        if (subtype == null) {
            sink.add(NOTE_SUBTYPE_MISSING, what + " has no /Subtype: whether its strings hold"
                    + " one-byte codes or CIDs is not said");
        }
        if (font.get("BaseFont") == null) {
            sink.add(NOTE_BASE_FONT_MISSING, what + " has no /BaseFont, which a font dictionary"
                    + " requires");
        }

        PdfEncoding encoding = PdfEncoding.of(document, font);
        for (String encodingNote : encoding.notes()) {
            sink.raw(encodingNote);
        }
        out.put("encoding", encoding.toMap(maxItems));

        List<ChainEntry> chain = descendantChain(document, font, blockNotes);
        Map<String, Object> descriptorInfo = describeDescriptor(document, font);
        if (descriptorInfo != null) {
            out.put("fontDescriptor", descriptorInfo);
        } else if (font.get("FontDescriptor") != null) {
            sink.add("font-descriptor-unreadable", "/FontDescriptor is not a dictionary - the"
                    + " metrics of this font stay unread");
        } else {
            // A /Type0 names no descriptor of its own: the metrics - and with them every embedded
            // program - hang on the CIDFont below it. Saying "no descriptor" there would be a
            // statement about the wrapper that the CIDFont has already contradicted.
            for (ChainEntry entry : chain) {
                Map<String, Object> fromChild = describeDescriptor(document, entry.dict());
                if (fromChild != null) {
                    out.put("fontDescriptor", fromChild);
                    out.put("fontDescriptorFrom", "descendant");
                    break;
                }
            }
        }
        Map<String, Object> files = describeFontFiles(document, font, chain);
        if (files != null) {
            out.put("fontFiles", files);
        }
        // The embedded program is read here, because a font sheet is the place that asks for it -
        // and its sentences stay in the notes of its own block (the mapping chain, which may read
        // the same program for one code, hands them to the caller's note list instead).
        PdfFontProgram.Read program = null;
        if (!"off".equals(programMode) && files != null) {
            Object[] pick = PdfFontProgram.pick(files);
            if (pick != null) {
                program = PdfFontProgram.read(document, (int) (long) (Long) pick[1],
                        String.valueOf(pick[0]), false, null);
                out.put("program", program.block(programMode, maxItems));
            }
        }
        if (subtype != null && subtype.equalsName("Type3")) {
            describeType3(document, font, out, maxItems);
        }

        if (subtype != null && subtype.equalsName("Type0")) {
            if (chain.isEmpty()) {
                sink.add(NOTE_DESCENDANT_MISSING, "A /Type0 font needs /DescendantFonts naming the"
                        + " CIDFont that holds the widths and the CID system - " + what.toLowerCase(
                        Locale.ROOT) + " has none");
            } else {
                out.put("descendant",
                        describeDescendant(document, chain, widthsMode, maxItems, blockNotes));
            }
        } else if (isCidFontDict(font)) {
            // This dictionary <em>is</em> the CIDFont: /W, /DW and /CIDSystemInfo are its own
            // entries, so they are read here - under a key that says so, because there is no
            // descendant to point at.
            out.put("cidFont", describeDescendant(document, List.of(new ChainEntry(objNum, font)),
                    widthsMode, maxItems, blockNotes));
        } else {
            simpleWidths(document, font, out, widthsMode, maxItems, blockNotes);
        }

        Integer own = refNum(font, "ToUnicode");
        Integer mapAddress = toUnicodeAddress(document, font);
        Map<String, Object> summary = null;
        if (mapAddress != null) {
            out.put("toUnicode", (long) (int) mapAddress);
            if (!mapAddress.equals(own)) {
                out.put("toUnicodeFrom", "descendant");
            }
            if (!"off".equals(toUnicodeMode)) {
                summary = toUnicodeSummary(document, mapAddress, "full".equals(toUnicodeMode),
                        maxItems, blockNotes);
                out.put("toUnicodeSummary", summary);
            }
        }
        Map<String, Object> mapping = new LinkedHashMap<>();
        String method = mapping(document, font, chain, encoding);
        if (!RESOLVABLE.contains(method) && program != null && program.canMapGlyphs()
                && isCidFont(font) && PdfGlyphMapper.glyphIndexKnown(document, font, chain)) {
            // The dictionary names no source and the program does: same verdict the mapping chain
            // reaches (PdfGlyphMapper), read from the program this sheet opens anyway.
            method = "cmap-table";
        }
        if (!RESOLVABLE.contains(method) && program != null
                && (!isCidFont(font) && program.canNameCodes()
                || isCidFont(font) && program.canNameGlyphs()
                && PdfGlyphMapper.glyphIndexKnown(document, font, chain))) {
            // The other thing a program can say for itself: which glyph a code selects, written in
            // its own /Encoding (Type 1) or Charset (CFF). Same verdict as step 5 of the chain.
            method = "glyph-names";
        }
        mapping.put("method", method);
        mapping.put("possible", RESOLVABLE.contains(method));
        if (summary != null && summary.get("mappedEntries") != null) {
            mapping.put("mapped", summary.get("mappedEntries"));
        }
        out.put("mapping", mapping);
        for (String blockNote : blockNotes) {
            sink.raw(blockNote);
        }
        if (!sink.sentences().isEmpty()) {
            out.put("notes", new ArrayList<Object>(sink.sentences()));
        }
        return out;
    }

    /** The lowercase subtype as the listing's {@code kind} (type0, type1, truetype, type3). */
    public static String kind(PdfName subtype) {
        return subtype == null ? "unknown" : subtype.value().toLowerCase(Locale.ROOT);
    }

    /**
     * True when the dictionary is a CID font: the {@code /Type0} wrapper <em>or</em> the
     * {@code CIDFont} below it. Both are addressed by object number, and a reader that knows only
     * the wrapper treats a CIDFont as a simple font - one byte per code, a {@code StandardEncoding}
     * it never has, and a {@code /W} array nobody reads.
     */
    public static boolean isCidFont(Map<?, ?> dict) {
        PdfName subtype = PdfName.ofDict(dict, "Subtype");
        return subtype != null && (subtype.equalsName("Type0") || isCidFontSubtype(subtype));
    }

    /** True when the dictionary <em>is</em> the CIDFont, not the {@code /Type0} above it. */
    public static boolean isCidFontDict(Map<?, ?> dict) {
        return isCidFontSubtype(PdfName.ofDict(dict, "Subtype"));
    }

    private static boolean isCidFontSubtype(PdfName subtype) {
        return subtype != null
                && (subtype.equalsName("CIDFontType0") || subtype.equalsName("CIDFontType2"));
    }

    // ===================================================================
    // Blocks of the sheet
    // ===================================================================

    /** One dictionary of a descendant chain with its object number. */
    public record ChainEntry(Integer objNum, Map<String, Object> dict) {
    }

    /**
     * Resolves the CIDFont(s) behind a {@code /Type0} font. The array holds one element by the
     * specification; a chain that some producers still nest is followed up to
     * {@link #MAX_DESCENDANT_DEPTH} so the widths of the inner dictionary are not lost.
     * <p>
     * Public because the mapping chain ({@link PdfGlyphMapper}) needs the same three facts the
     * sheet reads: the descendant's {@code /ToUnicode}, its {@code /CIDToGIDMap} and its
     * {@code /W}.
     * </p>
     */
    public static List<ChainEntry> descendantChain(PdfDocument document,
                                                   Map<String, Object> font, List<String> notes) {
        List<ChainEntry> chain = new ArrayList<>();
        Object value = font.get("DescendantFonts");
        if (value == null) {
            return chain;
        }
        if (value instanceof List<?> list && list.size() > 1) {
            note(notes, "descendant-multiple", "/DescendantFonts holds " + list.size()
                    + " entries; the specification expects one, so only the chain behind the"
                    + " first is described");
        }
        Object current = value instanceof List<?> list ? (list.isEmpty() ? null : list.get(0))
                : value;
        if (current == null) {
            note(notes, NOTE_DESCENDANT_MISSING, "/DescendantFonts is an empty array: the CIDFont"
                    + " that holds the widths is not named");
            return chain;
        }
        int depth = 0;
        while (depth < MAX_DESCENDANT_DEPTH) {
            Integer objNum = current instanceof PdfReference reference ? reference.objNum() : null;
            Object resolved;
            try {
                resolved = document.dereference(current);
            } catch (PdfException e) {
                note(notes, NOTE_DESCENDANT_MISSING, "The descendant font of this font cannot be"
                        + " read (" + e.getMessage() + ")");
                return chain;
            }
            if (!(resolved instanceof Map<?, ?> dict)) {
                note(notes, NOTE_DESCENDANT_MISSING, "The descendant font is "
                        + PdfParser.kindOf(resolved) + " instead of a dictionary");
                return chain;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) dict;
            chain.add(new ChainEntry(objNum, typed));
            depth++;
            Object nested = typed.get("DescendantFonts");
            if (nested == null) {
                return chain;
            }
            current = nested instanceof List<?> list && !list.isEmpty() ? list.get(0) : nested;
        }
        note(notes, NOTE_DESCENDANT_MISSING, "A /DescendantFonts chain deeper than "
                + MAX_DESCENDANT_DEPTH + " levels was not followed further");
        return chain;
    }

    /** The descendant block: CID system, CID→GID map, the width defaults and {@code /W}. */
    private static Map<String, Object> describeDescendant(PdfDocument document,
                                                          List<ChainEntry> chain,
                                                          String widthsMode, int maxItems,
                                                          List<String> notes) {
        Map<String, Object> primary = chain.get(0).dict();
        Map<String, Object> out = new LinkedHashMap<>();
        Integer objNum = chain.get(0).objNum();
        if (objNum != null) {
            out.put("objNum", (long) (int) objNum);
        }
        PdfName subtype = PdfName.ofDict(primary, "Subtype");
        if (subtype != null) {
            out.put("subtype", subtype.toString());
        }
        putName(out, primary, "BaseFont", "baseFont");
        Map<String, Object> info = cidSystemInfo(document, chain);
        if (info != null) {
            out.put("cidSystemInfo", info);
        }
        Object cidToGid = cidToGidMap(document, chain, maxItems);
        if (cidToGid != null) {
            out.put("cidToGidMap", cidToGid);
        }
        Number defaultWidth = firstNumberWith(chain, "DW");
        out.put("defaultWidth", defaultWidth != null ? defaultWidth : DEFAULT_CID_WIDTH);
        out.put("missingWidth", firstNumberWith(chain, "MissingWidth"));
        List<?> array = firstArrayOf(document, chain, "W");
        if (array != null) {
            readCidWidths(array, defaultWidth != null ? defaultWidth : DEFAULT_CID_WIDTH,
                    widthsMode, maxItems, out, notes);
        } else {
            out.put("widthSource", "none");
            if (chain.size() > 1) {
                note(notes, "descendant-widths", "None of the " + chain.size()
                        + " dictionaries of the descendant chain has /W: every CID has /DW (or the"
                        + " default 1000)");
            }
        }
        verticalMetrics(document, primary, chain, widthsMode, out, notes);
        return out;
    }

    /**
     * The vertical half of a CIDFont: {@code /W2} and {@code /DW2} (PDF 1.7 5.6.3). The reading is
     * the one {@link PdfFontWidths} does for a query, so the listing and the answer can never
     * disagree about what the file wrote; only what the file wrote <em>wrong</em> is said here,
     * because that is a fact about the array and not about a width.
     */
    private static void verticalMetrics(PdfDocument document, Map<String, Object> primary,
                                        List<ChainEntry> chain, String widthsMode,
                                        Map<String, Object> out, List<String> notes) {
        PdfFontWidths metrics = PdfFontWidths.of(document, primary, chain);
        if (!metrics.hasVerticalMetrics()) {
            for (String defect : metrics.verticalDefects()) {
                note(notes, "w2-shape", defect);
            }
            return;
        }
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("w2Entries", (long) metrics.verticalIndexed());
        List<Object> dw2 = metrics.defaultVertical();
        if (dw2 != null) {
            block.put("dw2", dw2);
        } else {
            block.put("dw2", List.of(PdfFontWidths.DEFAULT_DW2[0], PdfFontWidths.DEFAULT_DW2[1]));
            block.put("dw2Source", PdfFontWidths.SOURCE_DW2_DEFAULT);
        }
        if ("full".equals(widthsMode)) {
            block.put("note", "dw2 is written as the file writes it: [v_y, w1_y]; a glyph's own"
                    + " w1 and v come from pdf.glyphMetrics()");
        }
        out.put("vertical", block);
        for (String defect : metrics.verticalDefects()) {
            note(notes, "w2-shape", defect);
        }
    }

    /**
     * Reads a {@code /W} array in its three forms: {@code c [w1 w2 …]}, {@code cFirst cLast w}
     * and {@code c []} (this CID has no own width, {@code /DW} applies).
     * <p>
     * Two different cuts matter: {@link #MAX_WIDTH_ENTRIES} bounds what is <em>counted</em>, and
     * {@code maxItems} bounds what is <em>listed</em>. Counting a region costs one subtraction, so
     * the count usually stays complete while the listing stops early - which is the difference
     * between "this font has 65 000 widths" and "here are 200 of them".
     * </p>
     */
    private static void readCidWidths(List<?> array, Number defaultWidth, String widthsMode,
                                      int maxItems, Map<String, Object> out, List<String> notes) {
        boolean wantList = !"summary".equals(widthsMode) && !"off".equals(widthsMode);
        long entries = 0;
        long groups = 0;
        boolean countedCut = false;
        boolean listedCut = false;
        List<Object> ranges = new ArrayList<>();
        int i = 0;
        while (i < array.size()) {
            Long first = asIntegral(array.get(i));
            if (first == null) {
                note(notes, NOTE_W_ENTRY_SKIPPED, "A /W entry at index " + i + " is "
                        + PdfParser.kindOf(array.get(i)) + " instead of the first CID - the rest of"
                        + " the array was not read");
                break;
            }
            groups++;
            if (i + 1 >= array.size()) {
                note(notes, NOTE_W_ENTRY_SKIPPED, "The /W array ends after the CID " + first
                        + " without its widths");
                break;
            }
            Object second = array.get(i + 1);
            if (second instanceof List<?> widths) {
                i += 2;
                if (widths.isEmpty()) {
                    if (!wantList) {
                        continue;
                    }
                    if (ranges.size() >= maxItems) {
                        listedCut = true;
                        continue;
                    }
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("cid", first);
                    item.put("widths", new ArrayList<Object>());
                    item.put("width", defaultWidth);
                    item.put("usesDefaultWidth", true);
                    ranges.add(item);
                    continue;
                }
                List<Object> values = new ArrayList<>();
                long offset = 0;
                for (Object width : widths) {
                    if (entries >= MAX_WIDTH_ENTRIES) {
                        countedCut = true;
                        break;
                    }
                    entries++;
                    values.add(widthValue(width, first + offset, notes));
                    offset++;
                }
                if (wantList) {
                    if (ranges.size() < maxItems) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("cid", first);
                        item.put("widths", values);
                        ranges.add(item);
                    } else {
                        listedCut = true;
                    }
                }
                continue;
            }
            Long last = second instanceof Long number ? number : asIntegral(second);
            Number width = i + 2 < array.size() ? asNumber(array.get(i + 2)) : null;
            if (last != null && width != null) {
                i += 3;
                if (last < first) {
                    note(notes, NOTE_W_ENTRY_SKIPPED, "A /W region runs backwards (" + first
                            + " to " + last + ") and was skipped");
                    continue;
                }
                long count = last - first + 1;
                if (entries + count > MAX_WIDTH_ENTRIES) {
                    entries = MAX_WIDTH_ENTRIES;
                    countedCut = true;
                    break;
                }
                entries += count;
                if (wantList) {
                    if (ranges.size() < maxItems) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("from", first);
                        item.put("to", last);
                        item.put("width", width);
                        ranges.add(item);
                    } else {
                        listedCut = true;
                    }
                }
                continue;
            }
            note(notes, NOTE_W_ENTRY_SKIPPED, "The /W entry of CID " + first
                    + " is none of the three forms (a CID with an array, a CID range with one"
                    + " width) - the rest of the array was not read");
            break;
        }
        out.put("widthSource", "W");
        out.put("widthEntries", entries);
        out.put("widthGroups", groups);
        if (countedCut) {
            out.put("widthEntriesCut", true);
            note(notes, NOTE_WIDTHS_CUT, "/W describes more than " + MAX_WIDTH_ENTRIES
                    + " widths: widthEntries stopped counting there");
        }
        if (wantList) {
            out.put("widthRanges", ranges);
            if (listedCut) {
                out.put("widthsTruncated", true);
                note(notes, NOTE_WIDTHS_CUT, "Only the first " + maxItems
                        + " /W entries are listed (maxItems): widthEntries counts all of them");
            }
        }
    }

    /**
     * A simple font's widths: {@code /FirstChar}+{@code /LastChar}+{@code /Widths}, read as
     * code&nbsp;→&nbsp;width. A gap in the array stays a gap ({@code null}), because a zero width
     * would be a claim about a glyph nobody measured.
     */
    private static void simpleWidths(PdfDocument document, Map<String, Object> font,
                                     Map<String, Object> out, String widthsMode, int maxItems,
                                     List<String> notes) {
        Long firstChar = asIntegral(font.get("FirstChar"));
        Long lastChar = asIntegral(font.get("LastChar"));
        if (firstChar != null) {
            out.put("firstChar", firstChar);
        }
        if (lastChar != null) {
            out.put("lastChar", lastChar);
        }
        Object raw = font.get("Widths");
        if (raw != null) {
            raw = document.dereference(raw);
        }
        if (raw != null && !(raw instanceof List<?>)) {
            note(notes, NOTE_WIDTHS_MISMATCH, "/Widths is " + PdfParser.kindOf(raw)
                    + " instead of an array of numbers - the widths stay unread");
            out.put("widthSource", "none");
            return;
        }
        List<?> array = raw == null ? List.of() : (List<?>) raw;
        if (array.isEmpty()) {
            out.put("widthSource", "none");
            if (raw != null) {
                note(notes, NOTE_WIDTHS_MISMATCH, "/Widths is an empty array although /FirstChar"
                        + (firstChar != null ? " is " + firstChar : " and /LastChar are set")
                        + ": no width is given");
            }
            return;
        }
        out.put("widthSource", "Widths");
        long start = firstChar != null ? firstChar : 0;
        long expected = lastChar != null && firstChar != null ? lastChar - start + 1 : array.size();
        if (firstChar == null || lastChar == null) {
            note(notes, NOTE_WIDTHS_MISMATCH, "/Widths holds " + array.size() + " values"
                    + (firstChar == null && lastChar == null
                    ? " but the font names neither /FirstChar nor /LastChar"
                    : " without the matching /FirstChar or /LastChar")
                    + ": which code a value belongs to is not said");
        }
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            if (i >= MAX_WIDTH_ENTRIES) {
                note(notes, NOTE_WIDTHS_CUT, "/Widths holds more than " + MAX_WIDTH_ENTRIES
                        + " values - the rest was not read");
                break;
            }
            values.add(widthValue(array.get(i), start + i, notes));
        }
        out.put("widthCount", (long) values.size());
        if (values.size() < expected) {
            note(notes, NOTE_WIDTHS_MISMATCH, "/Widths covers codes " + start + " to "
                    + (start + values.size() - 1) + " although /LastChar is "
                    + (lastChar != null ? lastChar : start + expected - 1) + ": the codes beyond"
                    + " it read as null, not as width 0");
        } else if (expected > 0 && values.size() > expected) {
            note(notes, NOTE_WIDTHS_MISMATCH, "/Widths holds " + values.size()
                    + " values while /FirstChar and /LastChar cover " + expected
                    + " codes: the extra values belong to no code the dictionary names");
        }
        if (!"summary".equals(widthsMode) && !"off".equals(widthsMode)) {
            List<Object> shown = new ArrayList<>();
            for (Object value : values) {
                if (shown.size() >= maxItems) {
                    out.put("widthsTruncated", true);
                    break;
                }
                shown.add(value);
            }
            out.put("widthFirstCode", start);
            out.put("widthValues", shown);
        }
    }

    /** The descriptor block: name, box, metrics and the parsed {@code /Flags}. */
    private static Map<String, Object> describeDescriptor(PdfDocument document,
                                                          Map<String, Object> font) {
        Object value = font.get("FontDescriptor");
        if (value == null) {
            return null;
        }
        Integer objNum = value instanceof PdfReference reference ? reference.objNum() : null;
        Object resolved = document.dereference(value);
        if (!(resolved instanceof Map<?, ?> dict)) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (objNum != null) {
            out.put("objNum", (long) (int) objNum);
        }
        putName(out, dict, "FontName", "fontName");
        Object box = document.dereference(dict.get("FontBBox"));
        if (box instanceof List<?> list) {
            out.put("fontBBox", numbers(list));
        }
        for (String key : List.of("ItalicAngle", "Ascent", "Descent", "Leading", "CapHeight",
                "XHeight", "StemV", "StemH", "UnderlinePosition", "UnderlineThickness",
                "StrikethroughPosition", "StrikethroughThickness")) {
            Object item = dict.get(key);
            if (item instanceof Long || item instanceof Double) {
                out.put(camel(key), item);
            }
        }
        Long flags = asLong(dict.get("Flags"));
        if (flags != null) {
            out.put("flags", flags);
            List<Object> parsed = new ArrayList<>();
            for (Map.Entry<Long, String> bit : FLAGS.entrySet()) {
                if ((flags & bit.getKey()) != 0) {
                    parsed.add(bit.getValue());
                }
            }
            out.put("flagsParsed", parsed);
        }
        return out;
    }

    /**
     * The embedded programs this font names: {@code /FontFile} (Type 1), {@code /FontFile2}
     * (TrueType), {@code /FontFile3} (CFF and friends) and the CIDFont's {@code /CIDSet}. The
     * first three live in the descriptor, the last one in the CIDFont - both places are looked at,
     * so the answer does not depend on where a producer put it.
     * <p>
     * Public because the mapping chain ({@link PdfGlyphMapper}) reads the same dictionaries to find
     * the program it may need for a code - one place decides where a font's file is.
     * </p>
     * @param document the document
     * @param font the resolved font dictionary
     * @param chain the descendant chain (may be empty)
     * @return the {@code fontFiles} block, or null when nothing is embedded
     */
    public static Map<String, Object> describeFontFiles(PdfDocument document,
                                                        Map<String, Object> font,
                                                        List<ChainEntry> chain) {
        Map<String, Object> out = new LinkedHashMap<>();
        collectFontFiles(font, out);
        for (ChainEntry entry : chain) {
            collectFontFiles(entry.dict(), out);
        }
        for (Map<?, ?> dict : descriptors(document, font, chain)) {
            collectFontFiles(dict, out);
            Integer file3 = refNum(dict, "FontFile3");
            if (file3 != null && !out.containsKey("fontFile3Subtype")) {
                PdfName subtype = PdfName.ofDict(document.dictOf(file3), "Subtype");
                if (subtype != null) {
                    out.put("fontFile3Subtype", subtype.toString());
                }
            }
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * The descriptor dictionaries this font knows: its own and - the usual case behind a
     * {@code /Type0} - the one each CIDFont names. Reading only the top dictionary would report
     * "nothing embedded" for every CID font in the world, because a wrapper has no descriptor.
     */
    private static List<Map<?, ?>> descriptors(PdfDocument document, Map<String, Object> font,
                                               List<ChainEntry> chain) {
        List<Map<?, ?>> found = new ArrayList<>();
        if (document.dereference(font.get("FontDescriptor")) instanceof Map<?, ?> own) {
            found.add(own);
        }
        for (ChainEntry entry : chain) {
            if (document.dereference(entry.dict().get("FontDescriptor")) instanceof Map<?, ?> child
                    && !found.contains(child)) {
                found.add(child);
            }
        }
        return found;
    }

    private static void collectFontFiles(Map<?, ?> dict, Map<String, Object> out) {
        for (String key : FONT_FILE_KEYS) {
            Integer objNum = refNum(dict, key);
            if (objNum != null) {
                out.putIfAbsent(camel(key), (long) (int) objNum);
            }
        }
    }

    /** The Type 3 extras: which glyph programs exist and how the font scales. */
    private static void describeType3(PdfDocument document, Map<String, Object> font,
                                      Map<String, Object> out, int maxItems) {
        Object value = document.dereference(font.get("CharProcs"));
        if (value instanceof Map<?, ?> dict && !dict.isEmpty()) {
            List<Object> names = new ArrayList<>();
            for (Object key : dict.keySet()) {
                if (names.size() >= maxItems) {
                    out.put("charProcsTruncated", true);
                    break;
                }
                names.add("/" + key);
            }
            out.put("charProcs", names);
            out.put("charProcsCount", (long) dict.size());
        }
        if (font.get("FontMatrix") instanceof List<?> matrix) {
            out.put("fontMatrix", numbers(matrix));
        }
        if (font.get("FontBBox") instanceof List<?> box) {
            out.put("fontBBox", numbers(box));
        }
    }

    // ===================================================================
    // Facts shared by the listings
    // ===================================================================

    /**
     * Which source could resolve this font's codes (the plan's {@code mapping} value), in the
     * order the mapping chain will try them: the font's own {@code /ToUnicode}, then a code that
     * <em>is</em> a character (the UCS family), then the descendant's map, then what the encoding
     * alone implies. A base encoding guessed from the descriptor's {@code Flags} is deliberately
     * not in this list - that would invent a code page.
     */
    public static String mapping(PdfDocument document, Map<String, Object> font,
                                 List<ChainEntry> chain, PdfEncoding encoding) {
        if (font.containsKey("ToUnicode")) {
            return "toUnicode";
        }
        if (encoding.isUcs()) {
            return "unicode-code";
        }
        for (ChainEntry entry : chain) {
            if (entry.dict().containsKey("ToUnicode")) {
                return "toUnicode-descendant";
            }
        }
        return encoding.suggestedMapping();
    }

    /**
     * Where the widths of this font are: {@code W}, {@code Widths} or {@code none}. Both arrays
     * may sit behind an indirect reference (a producer that keeps the width table in its own
     * object), so the value is resolved before the question "is this an array?" is answered.
     */
    public static String widthSource(PdfDocument document, Map<String, Object> font,
                                     List<ChainEntry> chain) {
        for (ChainEntry entry : chain) {
            if (arrayOf(document, entry.dict().get("W")) != null) {
                return "W";
            }
        }
        if (isCidFontDict(font) && arrayOf(document, font.get("W")) != null) {
            // A CIDFont addressed by its own object number keeps /W in this very dictionary; the
            // chain is empty here, and "none" would deny a width table that is on the page.
            return "W";
        }
        return arrayOf(document, font.get("Widths")) != null ? "Widths" : "none";
    }

    /** A non-empty array behind a value that may be an indirect reference, or null. */
    private static List<?> arrayOf(PdfDocument document, Object value) {
        if (value == null) {
            return null;
        }
        return document.dereference(value) instanceof List<?> list && !list.isEmpty() ? list : null;
    }

    /** The {@code /ToUnicode} stream of the font itself, or of its descendant when it has none. */
    private static Integer toUnicodeAddress(PdfDocument document, Map<String, Object> font) {
        Integer own = refNum(font, "ToUnicode");
        if (own != null) {
            return own;
        }
        for (ChainEntry entry : descendantChain(document, font, new ArrayList<>())) {
            Integer fromDescendant = refNum(entry.dict(), "ToUnicode");
            if (fromDescendant != null) {
                return fromDescendant;
            }
        }
        return null;
    }

    /**
     * {@code /CIDToGIDMap} of the chain. The specification defaults it to {@code /Identity} for
     * {@code CIDFontType2} only - a {@code CIDFontType0} without the entry has no mapping to
     * claim, so nothing is answered there.
     * <p>
     * A stream is a field of 16-bit glyph indices, so it is worth reading: with
     * {@code sampleCount > 0} (the object sheet) the stream is decoded and shown as
     * {@code {objNum, entries, bytes, sample}} - which is where a reader sees that the GID need not
     * be the CID. The per-font listing passes 0 and stays at the address, because opening a 128 KiB
     * field for every font of a walk answers a different question than that listing asks.
     * </p>
     */
    private static Object cidToGidMap(PdfDocument document, List<ChainEntry> chain,
                                      int sampleCount) {
        for (ChainEntry entry : chain) {
            Object value = entry.dict().get("CIDToGIDMap");
            if (value == null) {
                continue;
            }
            if (value instanceof PdfReference reference) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("objNum", (long) reference.objNum());
                if (sampleCount <= 0) {
                    out.put("note", "a /CIDToGIDMap stream: pdf.font() shows its entries and"
                            + " pdf.decodeString() maps with them");
                    return out;
                }
                try {
                    PdfCidToGidMap map = PdfCidToGidMap.read(
                            document.stream(reference.objNum()).data());
                    out.putAll(map.toMap(Math.min(sampleCount, PdfCidToGidMap.DEFAULT_SAMPLE)));
                } catch (PdfException e) {
                    out.put("readable", false);
                    out.put("note", "the /CIDToGIDMap stream " + reference.objNum()
                            + " could not be read (" + e.getMessage() + "): the glyph indices stay"
                            + " unread");
                }
                return out;
            }
            Object resolved = document.dereference(value);
            if (resolved instanceof PdfName name) {
                return name.toString();
            }
            return PdfParser.kindOf(resolved);
        }
        if (!chain.isEmpty()
                && PdfName.dictHas(chain.get(0).dict(), "Subtype", "CIDFontType2")) {
            return "/Identity";
        }
        return null;
    }

    /** Which embedded programs the dictionaries name (names only - the listing wants it short). */
    private static List<Object> embeddedNames(PdfDocument document, Map<String, Object> font,
                                              List<ChainEntry> chain) {
        List<Object> names = new ArrayList<>();
        addEmbedded(font, names);
        for (ChainEntry entry : chain) {
            addEmbedded(entry.dict(), names);
        }
        for (Map<?, ?> dict : descriptors(document, font, chain)) {
            addEmbedded(dict, names);
        }
        return names;
    }

    private static void addEmbedded(Map<?, ?> dict, List<Object> names) {
        for (String key : FONT_FILE_KEYS) {
            if (dict.get(key) != null && !names.contains(camel(key))) {
                names.add(camel(key));
            }
        }
    }

    /** {@code /CIDSystemInfo} of the chain (the first dictionary that carries one). */
    private static Map<String, Object> cidSystemInfo(PdfDocument document, List<ChainEntry> chain) {
        for (ChainEntry entry : chain) {
            Object resolved = document.dereference(entry.dict().get("CIDSystemInfo"));
            if (!(resolved instanceof Map<?, ?> dict)) {
                continue;
            }
            Map<String, Object> info = new LinkedHashMap<>();
            for (String key : List.of("Registry", "Ordering", "Supplement")) {
                Object item = dict.get(key);
                if (item instanceof PdfString string) {
                    info.put(key.toLowerCase(Locale.ROOT), string.text());
                } else if (item instanceof PdfName name) {
                    info.put(key.toLowerCase(Locale.ROOT), name.value());
                } else if (item != null) {
                    info.put(key.toLowerCase(Locale.ROOT), item);
                }
            }
            if (!info.isEmpty()) {
                return info;
            }
        }
        return null;
    }

    /**
     * The {@code /ToUnicode} summary: what the map holds, without the map itself - that is what
     * {@code pdf.cmap()} is for, and a font sheet that pasted 90 000 entries into every page would
     * answer a different question than the one asked.
     */
    private static Map<String, Object> toUnicodeSummary(PdfDocument document, int objNum,
                                                        boolean full, int maxItems,
                                                        List<String> notes) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("present", true);
        out.put("objNum", (long) objNum);
        PdfCMap cmap;
        try {
            cmap = PdfCMap.parse(document.stream(objNum).data(), PdfCMap.MAX_ENTRIES);
        } catch (PdfException e) {
            note(notes, NOTE_CMAP_UNREADABLE, "The /ToUnicode stream " + objNum
                    + " could not be read (" + e.getMessage() + "): the map stays unread");
            out.put("readable", false);
            return out;
        }
        if (cmap.cmapName() != null) {
            out.put("cmapName", cmap.cmapName());
        }
        out.put("cmapType", cmap.cmapType());
        List<Object> spaces = new ArrayList<>();
        for (PdfCMap.CodeSpace space : cmap.codeSpaces()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("from", PdfCMap.hexOf(space.from(), space.bytes()));
            item.put("to", PdfCMap.hexOf(space.to(), space.bytes()));
            item.put("bytes", (long) space.bytes());
            item.put("notation", space.notation());
            spaces.add(item);
        }
        out.put("codeSpaces", spaces);
        out.put("bfchars", (long) cmap.bfcharPairs());
        out.put("bfranges", (long) cmap.bfrangeRegions());
        out.put("multiCharTargets", (long) cmap.multiCharTargets());
        out.put("mappedEntries", (long) cmap.entryCount());
        out.put("conflicts", (long) cmap.conflicts());
        if (cmap.truncated()) {
            out.put("truncated", true);
        }
        if (!cmap.useCMaps().isEmpty()) {
            out.put("useCmaps", new ArrayList<Object>(cmap.useCMaps()));
        }
        if (full) {
            Map<String, Object> view = cmap.toMap(maxItems, null);
            out.put("codes", view.get("codes"));
            out.put("shown", view.get("shown"));
            if (Boolean.TRUE.equals(view.get("truncated"))) {
                out.put("truncated", true);
            }
        }
        if (!cmap.warnings().isEmpty()) {
            out.put("warnings", new ArrayList<Object>(cmap.warnings()));
        }
        return out;
    }

    // ===================================================================
    // Small readers
    // ===================================================================

    private static Integer refNum(Map<?, ?> dict, String key) {
        return dict.get(key) instanceof PdfReference reference ? reference.objNum() : null;
    }

    private static List<?> firstArrayOf(PdfDocument document, List<ChainEntry> chain, String key) {
        for (ChainEntry entry : chain) {
            List<?> list = arrayOf(document, entry.dict().get(key));
            if (list != null) {
                return list;
            }
        }
        return null;
    }

    private static Number firstNumberWith(List<ChainEntry> chain, String key) {
        for (ChainEntry entry : chain) {
            Number value = asNumber(entry.dict().get(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** A number as written: an integer stays an integer, a real stays a real. */
    private static Number asNumber(Object value) {
        return value instanceof Long || value instanceof Double ? (Number) value : null;
    }

    /** A number that has to be an integer (a code, a CID, a dictionary index). */
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

    private static Long asLong(Object value) {
        return value instanceof Long number ? number : null;
    }

    /**
     * One width as the dictionary wrote it - an integer stays an integer and a real (which is
     * what TeX writes) stays a real. Anything that is not a number reads as null, and says so: a
     * zero here would be a claim about a glyph nobody measured.
     */
    private static Object widthValue(Object value, long code, List<String> notes) {
        Number number = asNumber(value);
        if (number == null) {
            note(notes, NOTE_WIDTHS_MISMATCH, "The width of code " + code + " is "
                    + PdfParser.kindOf(value) + " instead of a number and reads as null");
            return null;
        }
        return number;
    }

    /** A counter that may not exist yet reads as zero (it is a count, not a measurement). */
    private static long countOf(Object value) {
        return value instanceof Long number ? number : 0L;
    }

    /** A numeric array as plain numbers (boxes, matrices); junk entries stay visible as null. */
    private static List<Object> numbers(List<?> list) {
        List<Object> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Long || item instanceof Double) {
                out.add(item);
            } else {
                out.add(null);
            }
        }
        return out;
    }

    /**
     * {@code FontFile2} reads as {@code fontFile2} and {@code CIDSet} as {@code cidSet} - the
     * camelCase the results use elsewhere. A leading run of capitals is one acronym whose last
     * letter already belongs to the next word ({@code CID|Set}, {@code C|MapName}), so writing
     * {@code cIDSet} would be a spelling the dictionary never had.
     */
    private static String camel(String key) {
        if (key.isEmpty()) {
            return key;
        }
        int run = 0;
        while (run < key.length() && Character.isUpperCase(key.charAt(run))) {
            run++;
        }
        if (run == 0) {
            return key;
        }
        int acronym = run > 1 && run < key.length() ? run - 1 : run;
        return key.substring(0, acronym).toLowerCase(Locale.ROOT) + key.substring(acronym);
    }

    /** One sentence per statement, so a walk over 300 pages says each thing once. */
    private static void note(List<String> notes, String key, String sentence) {
        if (!notes.contains(sentence)) {
            notes.add(sentence);
        }
    }

    /** Notes with a catalog key: the key is remembered, so the same statement cannot repeat. */
    private static final class NoteSink {
        private final List<String> sentences = new ArrayList<>();
        private final Set<String> keys = new LinkedHashSet<>();

        void add(String key, String sentence) {
            if (keys.add(key)) {
                raw(sentence);
            }
        }

        /** A sentence without a key of its own (another class already phrased it). */
        void raw(String sentence) {
            if (!sentences.contains(sentence)) {
                sentences.add(sentence);
            }
        }

        List<String> sentences() {
            return List.copyOf(sentences);
        }

        /** The live list, for helpers that only take a {@code List<String>}. */
        List<String> list() {
            return sentences;
        }
    }
}
