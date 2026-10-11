package org.rogmann.mcp2sdk.pdf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The document view of a PDF file: what the file <em>is</em> instead of how it is laid out.
 * <p>
 * {@link PdfFileStructure} answers "where in the file is what" (header, cross-reference,
 * trailer) and {@link PdfDocument} answers "what does object <em>n</em> contain". This class
 * sits above both and reads the parts that make a <em>document</em>: the catalogue and what it
 * points at, the page tree with its inherited page attributes, the outline (bookmark) tree, the
 * annotations, the interactive form, the name and number trees ({@code /Names},
 * {@code /PageLabels}, …) and destinations - plus a pointer into the tagged-PDF structure tree
 * (see {@link PdfStructTree}).
 * </p>
 * <p>
 * The view is deliberately a <em>summary with addresses</em>, not a whole-document snapshot.
 * A catalogue is a graph, not a tree: hundreds of pages share one {@code /Resources}, one
 * annotation may be referenced by the structure tree and by the page at once. Following
 * everything would reproduce exactly the explosion
 * {@code docs/js/pdf-resource-explosion-plan.md} was written about, so every listing here is
 * (a) bounded by an explicit limit and (b) marked when it was cut - the same rule the object
 * walk follows. What is listed can be read in full afterwards, object by object, with
 * {@code pdf.object(path, objNum)}.
 * </p>
 */
public final class PdfDocumentStructure {

    /** Pages {@link #overview(int)} walks by default. */
    public static final int DEFAULT_MAX_PAGES = 200;

    /** Entries a single name/number tree listing returns by default. */
    public static final int DEFAULT_TREE_LIMIT = 200;

    /** Outline items {@link #outlines(int, int)} returns by default. */
    public static final int DEFAULT_MAX_ITEMS = 500;

    /** Annotations a page listing shows per page by default. */
    public static final int DEFAULT_MAX_ANNOTATIONS = 100;

    /** Nesting limit of the outline walk (a producer that loops stops here). */
    public static final int MAX_OUTLINE_DEPTH = 200;

    /** Siblings of one outline level; more than this is treated as a damaged chain. */
    public static final int MAX_OUTLINE_SIBLINGS = 100_000;

    /** Depth limit of a name/number tree walk. */
    public static final int MAX_TREE_DEPTH = 64;

    /** Characters of a text value shown in a listing. */
    public static final int MAX_TEXT_CHARS = 200;

    /** Distinct values (page sizes, tag names) a histogram lists. */
    private static final int MAX_HISTOGRAM_ENTRIES = 20;

    /** Keys a dictionary summary lists. */
    private static final int MAX_DICT_KEYS = 80;

    /** Items shown when a tree entry holds an array (a /ParentTree value). */
    private static final int MAX_LISTED_VALUE_ITEMS = 20;

    /** Pages named in one "these pages could not be read" sentence before it says "…". */
    private static final int MAX_LISTED_NOTE_PAGES = 10;

    /**
     * The sink of a call whose caller reports the problem itself (a sub-walk that must not add
     * to the enclosing listing). It is read-only on purpose - {@link #note} tolerates exactly
     * that, so nothing is ever thrown because of a sink.
     */
    private static final List<String> DISCARD_NOTES = List.of();

    /** Resource categories of a page, in the order of the specification. */
    private static final List<String> RESOURCE_CATEGORIES =
            List.of("ExtGState", "Font", "XObject", "ColorSpace", "Pattern", "Shading",
                    "Properties", "ProcSet", "Resource");

    /**
     * A page of the page tree together with its (already resolved) dictionary.
     *
     * @param page 1-based page number
     * @param objNum page object number ({@code -1} for an inline page object)
     * @param dict the page dictionary (empty when the object cannot be read)
     */
    public record PageEntry(int page, int objNum, Map<String, Object> dict) {
    }

    private final PdfDocument document;

    /**
     * Creates the document view of one opened document.
     * @param document the document
     */
    public PdfDocumentStructure(PdfDocument document) {
        this.document = document;
    }

    /**
     * Convenience factory mirroring {@link PdfDocument#open(String, byte[])}.
     * @param document the document
     * @return the document view
     */
    public static PdfDocumentStructure of(PdfDocument document) {
        return new PdfDocumentStructure(document);
    }

    // ===================================================================
    // Catalogue
    // ===================================================================

    /**
     * The document catalogue ({@code /Root}) as a dictionary.
     * @param notes note/warning sink (never null)
     * @return the catalogue dictionary or {@code null} when there is none
     */
    public Map<String, Object> catalog(List<String> notes) {
        Integer objNum = refOf(document.crossReference().trailer().get("Root"));
        if (objNum == null) {
            note(notes, "The trailer has no /Root, so this file has no readable document"
                    + " catalogue. The file itself stays analysable with pdf.structure(),"
                    + " pdf.objects() and pdf.streams().");
            return null;
        }
        Map<String, Object> dict = document.dictOf(objNum);
        if (dict.isEmpty()) {
            note(notes, "/Root (object " + objNum + ") does not point at a readable dictionary -"
                    + " the document view stops here");
            return null;
        }
        return dict;
    }

    // ===================================================================
    // Overview
    // ===================================================================

    /**
     * The document in one answer: version, catalogue contents, pages, tagging, outlines,
     * annotations, form, name trees - each as a summary with the object numbers to read next.
     * @param maxPages page limit of the walks that need the page tree
     * @return the overview map
     */
    public Map<String, Object> overview(int maxPages) {
        List<String> notes = new ArrayList<>();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", document.displayName());

        String fileVersion = document.structure().version();
        result.put("fileVersion", fileVersion != null ? fileVersion : "unknown");

        Integer catalogObjNum = refOf(document.crossReference().trailer().get("Root"));
        Map<String, Object> catalog = catalog(notes);
        if (catalog == null) {
            result.put("notes", notes);
            return result;
        }
        result.put("catalogObjNum", (long) catalogObjNum);
        result.put("entries", catalogEntries(catalog));

        PdfName declaredVersion = PdfName.ofDict(catalog, "Version");
        if (declaredVersion != null) {
            result.put("declaredVersion", declaredVersion.value());
        }
        if (catalog.get("Lang") instanceof PdfString lang) {
            result.put("lang", clip(lang.text(), MAX_TEXT_CHARS));
        }
        Map<String, Object> viewerPreferences = dictValue(catalog.get("ViewerPreferences"), notes);
        if (viewerPreferences != null) {
            result.put("viewerPreferences", keyNames(viewerPreferences));
        }

        // Tagged or not: /MarkInfo /Marked is what the specification asks a producer to write.
        Map<String, Object> markInfo = dictValue(catalog.get("MarkInfo"), notes);
        if (markInfo == null) {
            result.put("tagged", false);
        } else if (markInfo.get("Marked") instanceof Boolean marked) {
            result.put("tagged", marked);
        } else {
            result.put("tagged", null);
            notes.add("/MarkInfo exists but holds no boolean /Marked entry: the producer did not"
                    + " say whether the document is tagged");
        }

        List<PageEntry> pages = pageEntries(maxPages, notes);
        Map<Integer, Integer> pageNumbers = numbersByObject(pages);
        result.put("pages", pageSummary(catalog, pages, maxPages, notes));

        Map<String, Object> annotations = annotationSummary(pages, notes);
        if (annotations != null) {
            result.put("annotations", annotations);
        }
        result.put("outlines", outlineSummary(catalog, pageNumbers, notes));

        Map<String, Object> acroForm = dictValue(catalog.get("AcroForm"), notes);
        if (acroForm != null) {
            result.put("form", formSummary(acroForm, notes));
        }

        result.put("structure", new PdfStructTree(document)
                .summary(PdfStructTree.SUMMARY_MAX_ELEMENTS));

        Map<String, Object> names = dictValue(catalog.get("Names"), notes);
        if (names != null && !names.isEmpty()) {
            result.put("nameTrees", keyNames(names));
            if (names.get("EmbeddedFiles") != null) {
                result.put("embeddedFiles", readTree(names.get("EmbeddedFiles"), null, 25, notes));
            }
        }
        Object legacyDests = names != null && names.get("Dests") != null
                ? names.get("Dests") : catalog.get("Dests");
        if (legacyDests != null) {
            result.put("namedDestinations", readTree(legacyDests, null, 25, notes));
        }
        if (catalog.get("PageLabels") != null) {
            result.put("pageLabels", readTree(catalog.get("PageLabels"), null, 20, notes));
        }
        Integer metadataObjNum = refOf(catalog.get("Metadata"));
        if (metadataObjNum != null) {
            result.put("metadata", metadataSummary(metadataObjNum));
        }
        if (catalog.get("OpenAction") != null) {
            // /OpenAction holds an action dictionary (or, older, a destination directly).
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("A", catalog.get("OpenAction"));
            Map<String, Object> described = destinationOf(wrapper, pageNumbers, notes);
            result.put("openAction", described.containsKey("action")
                    ? described.get("action")
                    : destination(catalog.get("OpenAction"), pageNumbers, notes));
        }
        if (!notes.isEmpty()) {
            result.put("notes", new ArrayList<Object>(notes));
        }
        return result;
    }

    /**
     * One line per catalogue entry: every key the catalogue holds, so nothing is hidden by the
     * interpreted summaries below it.
     */
    private List<Map<String, Object>> catalogEntries(Map<String, Object> catalog) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Map.Entry<String, Object> entry : catalog.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", "/" + entry.getKey());
            Object value = entry.getValue();
            item.put("kind", PdfParser.kindOf(value));
            Integer objNum = refOf(value);
            if (objNum != null) {
                item.put("objNum", (long) objNum);
                describeTarget(item, objNum);
            } else {
                item.put("value", brief(value));
            }
            entries.add(item);
        }
        return entries;
    }

    /**
     * Adds {@code targetKind} (and {@code targetType}) of a referenced object to a listing.
     */
    private void describeTarget(Map<String, Object> item, int objNum) {
        try {
            Object target = document.objectValue(objNum);
            item.put("targetKind", PdfParser.kindOf(target));
            Map<String, Object> dict = target instanceof PdfStream stream
                    ? stream.dict() : asMap(target);
            if (dict != null) {
                PdfName type = PdfName.ofDict(dict, "Type");
                if (type != null) {
                    item.put("targetType", type.toString());
                }
                PdfName subtype = PdfName.ofDict(dict, "Subtype");
                if (subtype != null) {
                    item.put("targetSubtype", subtype.toString());
                }
            }
        } catch (PdfException e) {
            item.put("targetUnreadable", e.getMessage());
        }
    }

    /**
     * Metadata stream summary - the XMP packet itself is read with {@code pdf.streamText()}.
     */
    private Map<String, Object> metadataSummary(int objNum) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("objNum", (long) objNum);
        Map<String, Object> dict = document.dictOf(objNum);
        PdfName subtype = PdfName.ofDict(dict, "Subtype");
        if (subtype != null) {
            item.put("subtype", subtype.toString());
        }
        Long length = asLong(dict.get("Length"));
        if (length != null) {
            item.put("bytes", length);
        }
        item.put("readWith", "pdf.streamText(path, " + objNum + ")");
        return item;
    }

    // ===================================================================
    // Pages
    // ===================================================================

    /**
     * Walks the page tree and resolves every page dictionary.
     * @param maxPages page limit
     * @param notes note/warning sink (never null)
     * @return the pages in document order
     */
    public List<PageEntry> pageEntries(int maxPages, List<String> notes) {
        List<PdfDocument.Page> walked = document.pages(maxPages, notes);
        List<PageEntry> result = new ArrayList<>(walked.size());
        for (PdfDocument.Page page : walked) {
            result.add(new PageEntry(page.page(), page.objNum(), document.dictOf(page.objNum())));
        }
        return result;
    }

    /**
     * Index {@code page object number -> 1-based page number} of a page listing.
     * @param pages the pages
     * @return the index (first occurrence wins if a page object appears twice)
     */
    public static Map<Integer, Integer> numbersByObject(List<PageEntry> pages) {
        Map<Integer, Integer> numbers = new HashMap<>();
        for (PageEntry entry : pages) {
            if (entry.objNum() >= 0) {
                numbers.putIfAbsent(entry.objNum(), entry.page());
            }
        }
        return numbers;
    }

    /**
     * One entry per page: the boxes (with the inheritance the specification asks for), rotation,
     * page label, the {@code /StructParents} key of the structure tree and, on request, the
     * resources and the annotations.
     * @param maxPages page limit
     * @param withResources whether the resource dictionaries are summarised
     * @param withAnnotations whether annotations are listed
     * @param maxAnnotations annotations listed per page (0 = no limit)
     * @return one entry per page plus a trailing {@code notes} entry when something is to say
     */
    public List<Map<String, Object>> pageListings(int maxPages, boolean withResources,
                                                 boolean withAnnotations, int maxAnnotations) {
        List<String> notes = new ArrayList<>();
        List<PageEntry> pages = pageEntries(maxPages, notes);
        Map<Integer, Integer> pageNumbers = numbersByObject(pages);

        List<Map<String, Object>> labelRanges = null;
        Map<String, Object> catalog = catalog(notes);
        if (catalog != null && catalog.get("PageLabels") != null) {
            labelRanges = treeItems(catalog.get("PageLabels"), 0, notes).items();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        // Pages whose /Annots could not be read; the listing says it once, the page shows it.
        Set<Integer> unreadableAnnots = new LinkedHashSet<>();
        for (PageEntry entry : pages) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("page", (long) entry.page());
            item.put("objNum", (long) entry.objNum());
            Map<String, Object> dict = entry.dict();
            if (dict.isEmpty()) {
                item.put("unreadable", true);
                result.add(item);
                continue;
            }
            Map<String, Object> boxes = new LinkedHashMap<>();
            for (String box : List.of("MediaBox", "CropBox", "TrimBox", "BleedBox", "ArtBox")) {
                List<Object> value = numbers(inherited(entry.dict(), box));
                if (value != null) {
                    boxes.put(camel(box), value);
                }
            }
            if (!boxes.isEmpty()) {
                item.put("boxes", boxes);
            }
            // The visible size of a page is the CropBox where one exists, the MediaBox otherwise.
            List<Object> media = castList(boxes.get("mediaBox"));
            List<Object> crop = castList(boxes.get("cropBox"));
            List<Object> size = crop != null ? crop : media;
            if (size != null && size.size() == 4) {
                item.put("widthPt", widthOf(size));
                item.put("heightPt", heightOf(size));
            }
            Long rotate = asLong(inherited(entry.dict(), "Rotate"));
            if (rotate != null) {
                item.put("rotate", rotate);
            }
            Long structParents = asLong(dict.get("StructParents"));
            if (structParents != null) {
                item.put("structParents", structParents);
            }
            PdfName tabs = PdfName.ofDict(dict, "Tabs");
            if (tabs != null) {
                item.put("tabs", tabs.toString());
            }
            if (dict.containsKey("Thumb")) {
                item.put("thumbnail", true);
            }
            if (dict.containsKey("B") || dict.containsKey("Blinds") || dict.containsKey("Dur")) {
                item.put("transition", true);
            }
            // UserUnit is a *number*, not necessarily an integer: Table 3.27, S. 147 lets a page
            // name 2.5, and reading the entry with asLong dropped it silently - which is the trap
            // the plan named for the first placement() page ("sonst rechnet die fertige Matrix mit
            // einer erfundenen Eins"). A page whose unit cannot be read now stays without the field
            // rather than answering 1.
            Object userUnit = numericOrNull(dict.get("UserUnit"));
            if (userUnit != null) {
                item.put("userUnit", userUnit);
            }
            if (labelRanges != null) {
                Map<String, Object> label = labelFor(labelRanges, entry.page() - 1);
                if (label != null) {
                    item.put("pageLabel", label);
                }
            }
            if (withResources) {
                item.put("resources", resourceSummary(entry, notes));
            }
            if (withAnnotations) {
                item.put("annotations", pageAnnotations(entry, maxAnnotations, pageNumbers,
                        notes, unreadableAnnots));
            }
            result.add(item);
        }
        noteUnreadableAnnots(notes, unreadableAnnots);
        if (!notes.isEmpty()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("page", null);
            item.put("notes", new ArrayList<Object>(notes));
            result.add(item);
        }
        return result;
    }

    /**
     * Page summary of {@link #overview(int)}: count, declared count, size and rotation
     * histograms.
     */
    private Map<String, Object> pageSummary(Map<String, Object> catalog, List<PageEntry> pages,
                                           int maxPages, List<String> notes) {
        Map<String, Object> info = new LinkedHashMap<>();
        Integer treeRoot = refOf(catalog.get("Pages"));
        if (treeRoot != null) {
            info.put("treeRootObjNum", (long) treeRoot);
            Long declared = asLong(document.dictOf(treeRoot).get("Count"));
            if (declared != null) {
                info.put("declaredCount", declared);
                if (declared != pages.size() && pages.size() < maxPages) {
                    notes.add("The /Pages node declares /Count " + declared + ", but "
                            + pages.size() + " page(s) are reachable through /Kids");
                }
            }
        }
        info.put("count", (long) pages.size());
        if (pages.size() >= maxPages) {
            info.put("countNote", "the walk stopped at maxPages=" + maxPages
                    + " (raise maxPages to see more pages)");
        }
        Map<String, Long> sizes = new LinkedHashMap<>();
        Map<String, Long> rotations = new LinkedHashMap<>();
        Map<String, Long> boxesSeen = new LinkedHashMap<>();
        for (PageEntry entry : pages) {
            List<Object> media = numbers(inherited(entry.dict(), "MediaBox"));
            sizes.merge(media != null && media.size() == 4 ? join(media) : "unknown", 1L, Long::sum);
            if (media != null && media.size() != 4) {
                boxesSeen.merge("malformed MediaBox", 1L, Long::sum);
            }
            Object rotate = inherited(entry.dict(), "Rotate");
            rotations.merge(rotate == null ? "0" : String.valueOf(rotate), 1L, Long::sum);
            for (String box : List.of("CropBox", "TrimBox", "BleedBox", "ArtBox")) {
                if (entry.dict().containsKey(box) || hasInherited(entry.dict(), box)) {
                    boxesSeen.merge("/" + box, 1L, Long::sum);
                }
            }
        }
        info.put("mediaBoxes", histogram(sizes, "mediaBox", "pages"));
        info.put("rotations", histogram(rotations, "rotate", "pages"));
        if (!boxesSeen.isEmpty()) {
            info.put("extraBoxes", histogram(boxesSeen, "box", "pages"));
        }
        return info;
    }

    /**
     * Resource summary of one page: how many entries each category holds, which names, and - since
     * D0b - <em>which objects</em>. The lookup itself is {@link PdfResources}, the same one
     * {@code pdf.fonts()} and the text reading follow, so a name means the same thing in all three
     * answers; what this adds is the address next to it.
     * <p>
     * {@code resourcesFrom} is named only when the dictionary was not the page's own: a
     * {@code /Pages} ancestor carrying it is the fact worth telling (PDF 1.7 3.6.2), and a page
     * that names its resources is the case that needs no sentence. {@code ProcSet} stays a name
     * list - a procedure set is not an object and is never given an address.
     * </p>
     */
    private Map<String, Object> resourceSummary(PageEntry entry, List<String> notes) {
        Map<String, Object> result = new LinkedHashMap<>();
        PdfResources resources = PdfResources.ofPage(document, entry.dict(),
                entry.objNum() < 0 ? null : entry.objNum(), notes);
        if (resources.isEmpty()) {
            return result;
        }
        if (!PdfResources.FROM_PAGE.equals(resources.resourcesFrom())) {
            result.put("resourcesFrom", resources.resourcesFrom());
        }
        for (String category : RESOURCE_CATEGORIES) {
            Object raw = resources.dict().get(category);
            if (raw == null) {
                continue;
            }
            if ("ProcSet".equals(category)) {
                // The eighth key of Table 3.30 is an array of names, not a namespace: the names,
                // never an address.
                List<Object> names = new ArrayList<>();
                int shown = 0;
                for (String name : resources.procSets()) {
                    if (shown++ >= MAX_LISTED_VALUE_ITEMS) {
                        names.add("... (" + resources.procSets().size() + " in total)");
                        break;
                    }
                    names.add(name);
                }
                result.put(camel(category), names);
                continue;
            }
            if (!PdfResources.CATEGORIES.contains(category)
                    || !"dictionary".equals(resources.categoryShape(category))) {
                // The PDF 2.0 /Resource key and anything the file wrote where a sub-dictionary
                // belongs: shown as it is, never as an address.
                result.put(camel(category), brief(deref(raw, notes)));
                continue;
            }
            // The names keep the order the dictionary wrote them in (that is what this listing
            // always showed); the address next to each one comes from the same resolve() a
            // content stream would use.
            Map<String, Object> sub = asMap(deref(raw, notes));
            if (sub == null) {
                result.put(camel(category), brief(deref(raw, notes)));
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("count", (long) sub.size());
            List<Object> names = new ArrayList<>();
            List<Object> entries = new ArrayList<>();
            int shown = 0;
            for (String key : sub.keySet()) {
                if (shown++ >= MAX_HISTOGRAM_ENTRIES) {
                    item.put("truncated", true);
                    break;
                }
                names.add("/" + key);
                PdfResources.Entry found = resources.resolve(category, key);
                Map<String, Object> target = new LinkedHashMap<>();
                target.put("name", found.resourceName());
                if (found.objNum() != null) {
                    target.put("objNum", (long) (int) found.objNum());
                }
                if (found.subtype() != null) {
                    target.put("subtype", found.subtype());
                }
                if (found.objNum() == null || found.missing()) {
                    // No address to follow - so the listing says why: an inline dictionary, an
                    // array, or "99 0 R cannot be read". A producer that references everything
                    // (which is all of them, practically) never sees this key.
                    target.put("shape", found.shape());
                }
                entries.add(target);
            }
            item.put("names", names);
            item.put("entries", entries);
            result.put(camel(category), item);
        }
        return result;
    }

    // ===================================================================
    // Annotations
    // ===================================================================

    /**
     * Annotation histogram over the walked pages.
     * @param pages the pages
     * @param notes note/warning sink (never null)
     * @return the summary or {@code null} when no page has annotations (an unreadable
     *         {@code /Annots} is explained in {@code notes} either way)
     */
    private Map<String, Object> annotationSummary(List<PageEntry> pages, List<String> notes) {
        Map<String, Long> subtypes = new LinkedHashMap<>();
        long total = 0;
        long withAction = 0;
        long withUri = 0;
        List<String> uris = new ArrayList<>();
        Set<Integer> unreadable = new LinkedHashSet<>();
        for (PageEntry page : pages) {
            for (Map<String, Object> annot : annotationDicts(page, unreadable)) {
                total++;
                PdfName subtype = PdfName.ofDict(annot, "Subtype");
                subtypes.merge(subtype != null ? subtype.toString() : "/(none)", 1L, Long::sum);
                Map<String, Object> action = dictValue(annot.get("A"), notes);
                if (action != null) {
                    withAction++;
                    PdfName kind = PdfName.ofDict(action, "S");
                    if (kind != null && kind.equalsName("URI")
                            && action.get("URI") instanceof PdfString uri) {
                        withUri++;
                        if (uris.size() < 10) {
                            uris.add(clip(uri.text(), MAX_TEXT_CHARS));
                        }
                    }
                }
            }
        }
        noteUnreadableAnnots(notes, unreadable);
        if (total == 0) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", total);
        result.put("withAction", withAction);
        result.put("subtypes", histogram(subtypes, "subtype", "count"));
        if (withUri > 0) {
            result.put("uriActions", withUri);
            result.put("uriSamples", new ArrayList<Object>(uris));
        }
        return result;
    }

    /**
     * Annotations of one page for {@link #pageListings(int, boolean, boolean, int)}.
     * @param page the page
     * @param maxAnnotations annotation limit of the listing
     * @param pageNumbers page object number to page number
     * @param notes note/warning sink of the listing (never null)
     * @param unreadable collects pages whose {@code /Annots} could not be read (one sentence is
     *        written for the listing, the marker stays at the page)
     */
    private Map<String, Object> pageAnnotations(PageEntry page, int maxAnnotations,
                                                Map<Integer, Integer> pageNumbers,
                                                List<String> notes, Set<Integer> unreadable) {
        List<Map<String, Object>> dicts = annotationDicts(page, unreadable);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", (long) dicts.size());
        if (unreadable.contains(page.page())) {
            // The gap is marked where it is: "count: 0" must never read as "this page has no
            // annotations" when the reader could not get to the list.
            result.put("annotsUnreadable", true);
        }
        if (dicts.isEmpty()) {
            return result;
        }
        List<Object> items = new ArrayList<>();
        for (int i = 0; i < dicts.size(); i++) {
            if (maxAnnotations > 0 && i >= maxAnnotations) {
                result.put("truncated", true);
                result.put("note", "the first " + maxAnnotations + " of " + dicts.size()
                        + " annotation(s) are listed (raise maxItems)");
                break;
            }
            items.add(annotSummary(dicts.get(i), pageNumbers, notes));
        }
        result.put("items", items);
        return result;
    }

    /**
     * The annotation dictionaries of a page. {@code /Annots} is very often an <em>indirect</em>
     * reference to the array rather than the array itself - producers that write every page on
     * its own do that routinely, so the value is followed before it is read. A page whose list
     * cannot be read at all (or one entry of it) is collected in {@code unreadable}: the marker
     * belongs to the page, the explanation is one sentence for the whole listing.
     * @param page the page
     * @param unreadable collects the page numbers of the pages that could not be read fully
     * @return the readable annotation dictionaries, in file order
     */
    private List<Map<String, Object>> annotationDicts(PageEntry page, Set<Integer> unreadable) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (page.dict().get("Annots") == null) {
            return result;
        }
        List<Object> items = asList(deref(page.dict().get("Annots"), DISCARD_NOTES));
        if (items == null) {
            unreadable.add(page.page());
            return result;
        }
        for (Object item : items) {
            Map<String, Object> dict = dictValue(item, DISCARD_NOTES);
            if (dict == null) {
                unreadable.add(page.page());
                continue;
            }
            result.add(dict);
        }
        return result;
    }

    /**
     * The one sentence about pages whose {@code /Annots} could not be read. Naming all of them
     * would be honest but useless: a document that stores its annotation arrays in a form this
     * reader cannot follow has hundreds of those pages, and the sentence then pushes the notes
     * that matter out of the answer. The number is exact, the listed pages are the first ones.
     */
    private static void noteUnreadableAnnots(List<String> notes, Set<Integer> pages) {
        if (pages.isEmpty()) {
            return;
        }
        StringBuilder listed = new StringBuilder();
        int shown = 0;
        for (Integer page : pages) {
            if (shown++ >= MAX_LISTED_NOTE_PAGES) {
                listed.append(", ...");
                break;
            }
            if (listed.length() > 0) {
                listed.append(", ");
            }
            listed.append(page);
        }
        note(notes, "The /Annots of " + pages.size() + " page(s) is not a readable array of"
                + " annotations (first: page " + listed + ") - those pages show"
                + " annotsUnreadable instead of a count");
    }

    /**
     * One annotation of a page listing.
     */
    private Map<String, Object> annotSummary(Map<String, Object> annot,
                                             Map<Integer, Integer> pageNumbers,
                                             List<String> notes) {
        Map<String, Object> item = new LinkedHashMap<>();
        if (annot.get("Subtype") instanceof PdfName subtype) {
            item.put("subtype", subtype.toString());
        }
        List<Object> rect = numbers(annot.get("Rect"));
        if (rect != null) {
            item.put("rect", rect);
        }
        if (annot.get("T") instanceof PdfString title) {
            item.put("name", clip(title.text(), MAX_TEXT_CHARS));
        }
        if (annot.get("Contents") instanceof PdfString contents) {
            item.put("contents", clip(contents.text(), MAX_TEXT_CHARS));
        }
        // /F is the annotation flag integer; a file attachment uses /F for its file specification,
        // and that one stays out of "flags" because it is not a number.
        Long flags = asLong(annot.get("F"));
        if (flags != null) {
            item.put("flags", flags);
        }
        if (annot.get("FS") instanceof PdfName fileSpec) {
            item.put("fileSpec", fileSpec.toString());
        }
        item.putAll(destinationOf(annot, pageNumbers, notes));
        return item;
    }

    // ===================================================================
    // Interactive form
    // ===================================================================

    /**
     * AcroForm summary: field count, field types, defaults, XFA presence.
     */
    private Map<String, Object> formSummary(Map<String, Object> acroForm, List<String> notes) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> fields = new ArrayList<>();
        Map<String, Long> types = new LinkedHashMap<>();
        Map<String, Long> layouts = new LinkedHashMap<>();
        collectFields(acroForm.get("Fields"), 1, fields, types, layouts, notes);
        result.put("fields", (long) fields.size());
        result.put("fieldTypes", histogram(types, "subtype", "count"));
        if (!layouts.isEmpty()) {
            result.put("widgetSubtypes", histogram(layouts, "layout", "count"));
        }
        List<Object> sample = new ArrayList<>();
        for (int i = 0; i < Math.min(15, fields.size()); i++) {
            sample.add(fields.get(i));
        }
        if (!sample.isEmpty()) {
            result.put("firstFields", sample);
            if (fields.size() > sample.size()) {
                result.put("sampleNote", "the first " + sample.size() + " of " + fields.size()
                        + " field(s); pdf.object(path, objNum) reads any of them");
            }
        }
        if (Boolean.TRUE.equals(acroForm.get("NeedAppearances"))) {
            result.put("needAppearances", true);
        }
        if (acroForm.get("DA") instanceof PdfString defaultAppearance) {
            result.put("defaultAppearance", clip(defaultAppearance.text(), MAX_TEXT_CHARS));
        }
        Long sigFlags = asLong(acroForm.get("SigFlags"));
        if (sigFlags != null) {
            result.put("sigFlags", sigFlags);
        }
        if (acroForm.get("XFA") != null) {
            result.put("xfa", true);
            notes.add("The form carries XFA data: pdf sees the AcroForm fields, the XFA packet"
                    + " itself is a stream (pdf.streamText) and is not interpreted");
        }
        if (acroForm.get("DS") != null) {
            result.put("defaultStyle", true);
        }
        return result;
    }

    /**
     * Walks a field array (fields are a tree: {@code /Kids} below a field are its children).
     */
    private void collectFields(Object value, int depth, List<Map<String, Object>> fields,
                               Map<String, Long> types, Map<String, Long> layouts,
                               List<String> notes) {
        if (value == null || depth > PdfDocument.MAX_PAGE_DEPTH) {
            return;
        }
        List<Object> items = asList(value);
        if (items == null) {
            items = List.of(value);
        }
        for (Object item : items) {
            if (fields.size() >= PdfDocumentStructure.MAX_FIELD_SCAN) {
                notes.add("The form holds more than " + MAX_FIELD_SCAN
                        + " field objects - only the first ones were counted");
                return;
            }
            Map<String, Object> dict = dictValue(item, DISCARD_NOTES);
            if (dict == null) {
                notes.add("A form field could not be read and was skipped");
                continue;
            }
            Map<String, Object> info = new LinkedHashMap<>();
            if (refOf(item) != null) {
                info.put("objNum", (long) refOf(item));
            }
            PdfName subtype = PdfName.ofDict(dict, "Subtype");
            if (subtype != null) {
                types.merge(subtype.toString(), 1L, Long::sum);
                info.put("subtype", subtype.toString());
            }
            if (dict.get("T") instanceof PdfString name) {
                info.put("name", clip(name.text(), MAX_TEXT_CHARS));
            }
            if (dict.get("FT") instanceof PdfString fieldType) {
                info.put("fieldType", fieldType.text());
            } else if (dict.get("FT") instanceof PdfName fieldTypeName) {
                info.put("fieldType", fieldTypeName.toString());
            }
            if (dict.get("V") != null) {
                info.put("hasValue", true);
            }
            Long flags = asLong(dict.get("Ff"));
            if (flags != null) {
                info.put("flags", flags);
            }
            Object kids = dict.get("Kids");
            if (kids != null) {
                // A widget-only kid (/Subtype /Widget without /FT) is the appearance of its
                // parent field, so the layout is counted but the field is not double-counted.
                collectFields(kids, depth + 1, fields, types, layouts, notes);
                continue;
            }
            PdfName widget = PdfName.ofDict(dict, "Subtype");
            if (widget != null && widget.equalsName("Widget")) {
                PdfName measure = PdfName.ofDict(dict, "Measure");
                if (measure != null) {
                    layouts.merge(measure.toString(), 1L, Long::sum);
                }
            }
            fields.add(info);
        }
    }

    /** Upper bound of the field scan of one form. */
    private static final int MAX_FIELD_SCAN = 20_000;

    // ===================================================================
    // Outlines (bookmarks)
    // ===================================================================

    /**
     * The outline tree.
     * @param maxItems item limit (0 = no limit)
     * @param maxDepth nesting limit
     * @return {@code {present, objNum, count, items: [...]}} (plus {@code notes} when needed)
     */
    public Map<String, Object> outlines(int maxItems, int maxDepth) {
        List<String> notes = new ArrayList<>();
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> catalog = catalog(notes);
        if (catalog == null) {
            result.put("present", false);
            result.put("notes", new ArrayList<Object>(notes));
            return result;
        }
        Integer objNum = refOf(catalog.get("Outlines"));
        if (objNum == null) {
            result.put("present", false);
            notes.add("The catalogue has no /Outlines: this document has no bookmarks");
            result.put("notes", new ArrayList<Object>(notes));
            return result;
        }
        Map<Integer, Integer> pageNumbers = pageIndex(notes);
        OutlineWalk walk = new OutlineWalk();
        walk.declaredCount = asLong(document.dictOf(objNum).get("Count"));
        walkSiblings(document.dictOf(objNum).get("First"), 1, maxItems,
                maxDepth <= 0 ? MAX_OUTLINE_DEPTH : maxDepth, new HashSet<>(), pageNumbers, walk,
                notes);
        result.put("present", true);
        result.put("objNum", (long) objNum);
        if (walk.declaredCount != null) {
            result.put("declaredCount", walk.declaredCount);
        }
        result.put("count", (long) walk.items.size());
        result.put("items", new ArrayList<Object>(walk.items));
        if (walk.truncated) {
            result.put("truncated", true);
        }
        if (!notes.isEmpty()) {
            result.put("notes", new ArrayList<Object>(notes));
        }
        return result;
    }

    /**
     * Outline summary of {@link #overview(int)}: a shallow walk only.
     */
    private Map<String, Object> outlineSummary(Map<String, Object> catalog,
                                              Map<Integer, Integer> pageNumbers,
                                              List<String> notes) {
        Map<String, Object> result = new LinkedHashMap<>();
        Integer objNum = refOf(catalog.get("Outlines"));
        if (objNum == null) {
            result.put("present", false);
            return result;
        }
        result.put("present", true);
        result.put("objNum", (long) objNum);
        Map<String, Object> root = document.dictOf(objNum);
        Long declared = asLong(root.get("Count"));
        if (declared != null) {
            result.put("declaredCount", declared);
        }
        OutlineWalk walk = new OutlineWalk();
        walkSiblings(root.get("First"), 1, 25, 2, new HashSet<>(), pageNumbers, walk, notes);
        List<Object> titles = new ArrayList<>();
        for (Map<String, Object> item : walk.items) {
            Map<String, Object> brief = new LinkedHashMap<>();
            brief.put("level", item.get("level"));
            if (item.get("title") != null) {
                brief.put("title", item.get("title"));
            }
            if (item.get("page") != null) {
                brief.put("page", item.get("page"));
            }
            if (item.get("action") != null) {
                brief.put("action", item.get("action"));
            }
            titles.add(brief);
        }
        result.put("items", (long) walk.items.size());
        result.put("titles", titles);
        if (walk.truncated) {
            result.put("truncated", true);
            result.put("note", "the overview walks 25 items in 2 levels - pdf.outlines() reads"
                    + " the whole tree");
        }
        return result;
    }

    /** Items, the declared {@code /Count} and whether a limit cut the walk. */
    private static final class OutlineWalk {
        private final List<Map<String, Object>> items = new ArrayList<>();
        private Long declaredCount;
        private boolean truncated;
    }

    /**
     * Walks one {@code /First}-{@code /Next} sibling chain, descending into the items below.
     * @return {@code false} when the item limit stopped the walk
     */
    private boolean walkSiblings(Object node, int level, int maxItems, int maxDepth,
                                 Set<String> visited, Map<Integer, Integer> pageNumbers,
                                 OutlineWalk walk, List<String> notes) {
        Object current = node;
        int hops = 0;
        while (current != null) {
            if (++hops > MAX_OUTLINE_SIBLINGS) {
                walk.truncated = true;
                notes.add("More than " + MAX_OUTLINE_SIBLINGS + " siblings in one outline level -"
                        + " the /Next chain was cut");
                return false;
            }
            Integer objNum = refOf(current);
            if (objNum != null && !visited.add("o" + objNum)) {
                walk.truncated = true;
                notes.add("Outline item " + objNum + " is reached twice (its /First or /Next"
                        + " chain loops back) - the walk stops here");
                return false;
            }
            Map<String, Object> dict = dictValue(current, DISCARD_NOTES);
            if (dict == null) {
                walk.truncated = true;
                notes.add("An outline item (object " + objNum + ") could not be read - the rest"
                        + " of that level was skipped");
                return false;
            }
            if (!describeOutlineItem(dict, objNum, level, maxItems, maxDepth, visited, pageNumbers,
                    walk, notes)) {
                return false;
            }
            current = dict.get("Next");
        }
        return true;
    }

    /**
     * One outline item plus its subtree.
     * @return {@code false} when the item limit is reached
     */
    private boolean describeOutlineItem(Map<String, Object> dict, Integer objNum, int level,
                                        int maxItems, int maxDepth, Set<String> visited,
                                        Map<Integer, Integer> pageNumbers, OutlineWalk walk,
                                        List<String> notes) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("level", (long) level);
        if (objNum != null) {
            item.put("objNum", (long) objNum);
        }
        if (dict.get("Title") instanceof PdfString title) {
            item.put("title", clip(title.text(), MAX_TEXT_CHARS));
        }
        Long count = asLong(dict.get("Count"));
        if (count != null) {
            item.put("count", count);
            item.put("open", count >= 0);
        }
        Long flags = asLong(dict.get("F"));
        if (flags != null) {
            if ((flags & 1L) != 0) {
                item.put("bold", true);
            }
            if ((flags & 2L) != 0) {
                item.put("italic", true);
            }
        }
        Map<String, Object> described = destinationOf(dict, pageNumbers, notes);
        item.putAll(described);
        // A bookmark is asked for by page number far more often than by object number, and the
        // walk already knows it: the explicit destination, the target of a /GoTo, or the
        // destination a name resolves to. It is a shortcut next to dest/action, not a new claim.
        Object page = destinationPage(described);
        if (page != null) {
            item.put("page", page);
        }
        walk.items.add(item);
        if (maxItems > 0 && walk.items.size() >= maxItems) {
            walk.truncated = true;
            notes.add("Stopped after " + maxItems + " outline item(s) (raise maxItems to see"
                    + " more)");
            return false;
        }
        if (dict.get("First") != null) {
            if (level >= maxDepth) {
                item.put("childrenTruncated", true);
            } else if (!walkSiblings(dict.get("First"), level + 1, maxItems, maxDepth, visited,
                    pageNumbers, walk, notes)) {
                return false;
            }
        }
        return true;
    }

    // ===================================================================
    // Destinations and actions
    // ===================================================================

    /**
     * {@code dest} and/or {@code action} of a dictionary that has {@code /Dest} and/or {@code /A}
     * (outline items, annotations, {@code /OpenAction}).
     * @param dict the dictionary holding the entries
     * @param pageNumbers page object number to page number
     * @param notes note/warning sink (never null)
     * @return the (possibly empty) destination/action part
     */
    public Map<String, Object> destinationOf(Map<String, Object> dict,
                                             Map<Integer, Integer> pageNumbers,
                                             List<String> notes) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (dict.get("Dest") != null) {
            out.put("dest", destination(dict.get("Dest"), pageNumbers, notes));
        }
        Object actionValue = dict.get("A") != null ? dict.get("A") : dict.get("PA");
        if (actionValue == null) {
            return out;
        }
        Map<String, Object> action = dictValue(actionValue, notes);
        if (action == null) {
            return out;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        PdfName kind = PdfName.ofDict(action, "S");
        result.put("kind", kind != null ? kind.toString() : "/(unnamed)");
        if (action.get("URI") instanceof PdfString uri) {
            result.put("uri", clip(uri.text(), MAX_TEXT_CHARS));
        }
        if (action.get("F") != null) {
            result.put("file", brief(deref(action.get("F"), notes)));
        }
        if (action.get("JS") != null) {
            Object script = deref(action.get("JS"), notes);
            result.put("scriptChars", script instanceof PdfString string
                    ? (long) string.text().length() : null);
        }
        if (action.get("NewWindow") instanceof Boolean newWindow) {
            result.put("newWindow", newWindow);
        }
        if (kind != null && (kind.equalsName("GoTo") || kind.equalsName("GoToR"))
                && action.get("D") != null) {
            result.put("target", destination(action.get("D"), pageNumbers, notes));
        }
        out.put("action", result);
        return out;
    }

    /**
     * Reads one destination: an explicit destination array, a destination <em>name</em> (resolved
     * through {@code /Names /Dests} or the legacy {@code /Dests} dictionary) or a destination
     * dictionary.
     * @param value the destination value
     * @param pageNumbers page object number to page number
     * @param notes note/warning sink (never null)
     * @return a plain description of the destination
     */
    public Map<String, Object> destination(Object value, Map<Integer, Integer> pageNumbers,
                                           List<String> notes) {
        return destination(value, pageNumbers, notes, new HashSet<>());
    }

    /**
     * Reads one destination, carrying the names it already followed (a destination whose name
     * leads back to itself is a defect of the file, not a reason to recurse forever).
     */
    private Map<String, Object> destination(Object value, Map<Integer, Integer> pageNumbers,
                                            List<String> notes, Set<String> seenNames) {
        Map<String, Object> result = new LinkedHashMap<>();
        Object resolved = deref(value, notes);
        if (resolved == null) {
            result.put("kind", "unreadable");
            return result;
        }
        if (resolved instanceof List<?> array) {
            result.put("kind", "explicit");
            List<Object> items = castList(resolved);
            if (items == null || items.isEmpty()) {
                notes.add("A destination array is empty");
                return result;
            }
            Integer pageObjNum = refOf(items.get(0));
            if (pageObjNum != null) {
                result.put("pageObjNum", (long) pageObjNum);
                Integer number = pageNumbers.get(pageObjNum);
                if (number != null) {
                    result.put("page", (long) number);
                }
            } else if (items.get(0) instanceof Map) {
                result.put("inlinePage", true);
            } else {
                notes.add("The first item of a destination is "
                        + PdfParser.kindOf(items.get(0)) + " instead of a page");
            }
            if (items.size() > 1) {
                if (items.get(1) instanceof PdfName fit) {
                    result.put("fit", fit.toString());
                }
                if (items.size() > 2) {
                    result.put("parameters", items.subList(2, items.size()));
                }
            }
            return result;
        }
        if (resolved instanceof Map<?, ?> dict) {
            result.put("kind", "destinationDictionary");
            if (dict.get("D") != null) {
                result.put("resolved",
                        destination(dict.get("D"), pageNumbers, notes, seenNames));
            }
            return result;
        }
        // The second form a destination may take (ISO 32000-1, 12.3.2.2) is the <em>name</em> of
        // one, and the specification writes that name as a byte string - a string destination is
        // therefore not a value of its own, it is a key of /Names /Dests. Producers occasionally
        // spell the same thing as a name, so both are read here the same way; the result shows it
        // in the /Name form that the listings of this module use for names.
        String destName = null;
        if (resolved instanceof PdfString string) {
            destName = string.text();
        } else if (resolved instanceof PdfName name) {
            destName = name.value();
        }
        if (destName != null) {
            result.put("kind", "named");
            result.put("name", "/" + clip(destName, MAX_TEXT_CHARS));
            if (!seenNames.add(destName)) {
                result.put("cycle", true);
                note(notes, "The named destination /" + clip(destName, MAX_TEXT_CHARS)
                        + " is reached again through its own target - the resolution stops here");
                return result;
            }
            Object target = lookupNamedDestination(destName, notes);
            if (target != null) {
                result.put("resolved", destination(target, pageNumbers, notes, seenNames));
            } else {
                result.put("unresolved", true);
            }
            return result;
        }
        result.put("kind", PdfParser.kindOf(resolved));
        return result;
    }

    /** Keys behind which a described destination can hide its page number. */
    private static final List<String> DESTINATION_PATHS =
            List.of("dest", "action", "target", "resolved");

    /**
     * The page number a described destination reaches: its own {@code dest}, the target of a
     * {@code /GoTo} action, or what a named destination resolves to.
     * @param described the result of {@link #destinationOf} (or one of its nested parts)
     * @return the page number, or {@code null} when the file does not say one (an unresolved
     *         name, a page beyond the page-index limit)
     */
    private static Object destinationPage(Object described) {
        if (!(described instanceof Map<?, ?> map)) {
            return null;
        }
        Object page = map.get("page");
        if (page != null) {
            return page;
        }
        for (String key : DESTINATION_PATHS) {
            Object nested = destinationPage(map.get(key));
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    /**
     * Looks a named destination up in {@code /Names /Dests} and, as the specification's
     * compatibility rule asks, in the legacy flat {@code /Dests} dictionary of the catalogue.
     * @param name the destination name as the destination spells it (without the leading slash
     *        the result shows) - name trees are keyed by <em>strings</em>, so this is a string
     *        and not a {@link PdfName}
     * @param notes note/warning sink (never null)
     * @return the destination value or {@code null}
     */
    public Object lookupNamedDestination(String name, List<String> notes) {
        Map<String, Object> catalog = catalog(DISCARD_NOTES);
        if (catalog == null) {
            return null;
        }
        Map<String, Object> names = dictValue(catalog.get("Names"), DISCARD_NOTES);
        if (names != null && names.get("Dests") != null) {
            Object hit = searchTree(names.get("Dests"), name, 0, new HashSet<>());
            if (hit != null) {
                return hit;
            }
        }
        Map<String, Object> legacy = dictValue(catalog.get("Dests"), DISCARD_NOTES);
        if (legacy != null) {
            Object hit = legacy.get(name);
            if (hit != null) {
                return deref(hit, notes);
            }
        }
        notes.add("The named destination /" + name + " is not in /Names /Dests nor in the legacy"
                + " /Dests dictionary");
        return null;
    }

    // ===================================================================
    // Name and number trees
    // ===================================================================

    /**
     * Items of a name or number tree, in file order.
     *
     * @param kind {@code names}, {@code numbers}, {@code kids} or {@code dictionary}
     * @param items the entries ({@code {key, value}})
     * @param read how many entries were read before the limit
     * @param truncated whether the limit or a damaged node cut the listing
     */
    public record TreeItems(String kind, List<Map<String, Object>> items, long read,
                            boolean truncated) {
    }

    /**
     * Lists a name/number tree of the document by name.
     * @param which tree name, e.g. {@code "Dests"}, {@code "EmbeddedFiles"}, {@code "PageLabels"},
     *        {@code "ParentTree"} or {@code "KIDs"}
     * @param needle case-insensitive filter of the keys (may be {@code null})
     * @param limit entry limit (0 = no limit)
     * @return {@code {found, name, kind, read, matched, items}} plus {@code notes}
     */
    public Map<String, Object> treeListing(String which, String needle, int limit) {
        List<String> notes = new ArrayList<>();
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> catalog = catalog(notes);
        if (catalog == null) {
            result.put("found", false);
            result.put("notes", new ArrayList<Object>(notes));
            return result;
        }
        String name = which.startsWith("/") ? which.substring(1) : which;
        result.put("name", "/" + name);
        Object node = null;
        switch (name) {
            case "PageLabels" -> node = catalog.get("PageLabels");
            case "ParentTree", "KIDs", "IDTree" -> {
                Map<String, Object> structRoot = dictValue(catalog.get("StructTreeRoot"),
                        DISCARD_NOTES);
                if (structRoot != null) {
                    node = structRoot.get(name);
                }
            }
            default -> {
                Map<String, Object> names = dictValue(catalog.get("Names"), DISCARD_NOTES);
                if (names != null) {
                    node = names.get(name);
                }
                if (node == null && "Dests".equals(name)) {
                    node = catalog.get("Dests");
                }
            }
        }
        if (node == null) {
            result.put("found", false);
            notes.add("This document has no /" + name + " tree");
            result.put("notes", new ArrayList<Object>(notes));
            return result;
        }
        result.put("found", true);
        result.putAll(readTree(node, needle, limit, notes));
        if (!notes.isEmpty()) {
            result.put("notes", new ArrayList<Object>(notes));
        }
        return result;
    }

    /**
     * Reads a name or number tree into {@code {kind, read, matched, items, truncated?}}.
     * {@code read} counts the collected entries, {@code matched} the ones the listing shows -
     * a filter or a limit is a view, never a different read.
     */
    public Map<String, Object> readTree(Object node, String needle, int limit,
                                        List<String> notes) {
        TreeItems collected = treeItems(node, 0, notes);
        List<Object> items = new ArrayList<>();
        int matched = 0;
        boolean truncated = collected.truncated();
        for (Map<String, Object> item : collected.items()) {
            if (needle != null && !matchesNeedle(item.get("key"), needle)) {
                continue;
            }
            if (limit > 0 && matched >= limit) {
                truncated = true;
                break;
            }
            items.add(item);
            matched++;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", collected.kind());
        result.put("read", collected.read());
        result.put("matched", (long) matched);
        result.put("items", items);
        if (truncated) {
            result.put("truncated", true);
        }
        return result;
    }

    /**
     * Collects the entries of a name tree ({@code /Names}, {@code /Kids}), a number tree
     * ({@code /Nums}, {@code /Kids}) or a plain dictionary (the legacy {@code /Dests} form).
     * @param node the tree root
     * @param limit entry limit of the collection itself (0 = no limit)
     * @param notes note/warning sink (never null)
     * @return the collected entries
     */
    public TreeItems treeItems(Object node, int limit, List<String> notes) {
        List<Map<String, Object>> items = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        String[] kind = new String[1];
        boolean[] truncated = new boolean[1];
        collectTree(node, 0, limit, visited, items, kind, truncated, notes);
        String name = kind[0] != null ? kind[0] : "empty";
        return new TreeItems(name, items, items.size(), truncated[0]);
    }

    private void collectTree(Object node, int depth, int limit, Set<String> visited,
                             List<Map<String, Object>> items, String[] kind, boolean[] truncated,
                             List<String> notes) {
        if (limit > 0 && items.size() >= limit) {
            truncated[0] = true;
            return;
        }
        if (depth > MAX_TREE_DEPTH) {
            truncated[0] = true;
            notes.add("A name/number tree is nested deeper than " + MAX_TREE_DEPTH
                    + " levels - the remaining branches were skipped");
            return;
        }
        Map<String, Object> dict = dictValue(node, DISCARD_NOTES);
        if (dict == null) {
            truncated[0] = true;
            notes.add("A node of a name/number tree could not be read - the branch was skipped");
            return;
        }
        Integer objNum = refOf(node);
        if (objNum != null && !visited.add("t" + objNum)) {
            truncated[0] = true;
            return;
        }
        Object entries = dict.get("Nums");
        if (entries == null) {
            entries = dict.get("Names");
        }
        if (entries != null) {
            kind[0] = entries == dict.get("Nums") ? "numbers" : "names";
            List<Object> pairs = asList(entries);
            if (pairs == null) {
                truncated[0] = true;
                notes.add("The /Nums or /Names array of a tree is " + PdfParser.kindOf(entries)
                        + " instead of an array");
                return;
            }
            if (pairs.size() % 2 != 0) {
                notes.add("A name/number tree holds an odd number of array items - the last one"
                        + " was skipped");
            }
            for (int i = 0; i + 1 < pairs.size(); i += 2) {
                if (limit > 0 && items.size() >= limit) {
                    truncated[0] = true;
                    return;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("key", brief(deref(pairs.get(i), DISCARD_NOTES)));
                item.put("value", treeValue(pairs.get(i + 1)));
                items.add(item);
            }
            return;
        }
        List<Object> kids = asList(dict.get("Kids"));
        if (kids != null) {
            if (kind[0] == null) {
                kind[0] = "kids";
            }
            for (Object kid : kids) {
                collectTree(kid, depth + 1, limit, visited, items, kind, truncated, notes);
                if (truncated[0] && limit > 0 && items.size() >= limit) {
                    return;
                }
            }
            return;
        }
        // Neither /Names, /Nums nor /Kids: the legacy flat dictionary of a PDF 1.1 /Dests.
        if (kind[0] == null) {
            kind[0] = "dictionary";
        }
        int shown = 0;
        for (Map.Entry<String, Object> entry : dict.entrySet()) {
            if (limit > 0 && items.size() >= limit) {
                truncated[0] = true;
                return;
            }
            if (shown++ >= MAX_DICT_KEYS) {
                truncated[0] = true;
                break;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", "/" + entry.getKey());
            item.put("value", treeValue(entry.getValue()));
            items.add(item);
        }
    }

    /**
     * The value of a tree entry: a reference stays visible, an inline dictionary keeps its keys,
     * an array (a {@code /ParentTree} entry holds one per page) keeps its items.
     */
    private Object treeValue(Object value) {
        if (value instanceof List<?> array) {
            List<Object> items = new ArrayList<>();
            for (Object item : array) {
                if (items.size() >= MAX_LISTED_VALUE_ITEMS) {
                    items.add("... (" + array.size() + " items in total)");
                    break;
                }
                items.add(treeValue(item));
            }
            return items;
        }
        Integer objNum = refOf(value);
        if (objNum != null) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("ref", value.toString());
            view.put("objNum", (long) objNum);
            describeTarget(view, objNum);
            return view;
        }
        Map<String, Object> dict = asMap(value);
        if (dict != null) {
            Map<String, Object> view = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : dict.entrySet()) {
                if (view.size() >= MAX_DICT_KEYS) {
                    view.put("truncated", true);
                    break;
                }
                view.put(entry.getKey(), brief(entry.getValue()));
            }
            return view;
        }
        return brief(value);
    }

    /**
     * Depth-first search for one key of a name or number tree.
     * @param node the tree root
     * @param key the key to look for: a {@link String} (the text of a name-tree key), a
     *        {@link PdfString}, a {@link PdfName} or a {@link Long} (a number-tree key)
     * @param depth current depth
     * @param visited visited object numbers (loop guard)
     * @return the value behind the key or {@code null}
     */
    public Object searchTree(Object node, Object key, int depth, Set<String> visited) {
        if (node == null || depth > MAX_TREE_DEPTH) {
            return null;
        }
        Map<String, Object> dict = dictValue(node, DISCARD_NOTES);
        if (dict == null) {
            return null;
        }
        Integer objNum = refOf(node);
        if (objNum != null && !visited.add("s" + objNum)) {
            return null;
        }
        List<Object> pairs = asList(dict.get("Nums") != null ? dict.get("Nums")
                : dict.get("Names"));
        if (pairs != null) {
            for (int i = 0; i + 1 < pairs.size(); i += 2) {
                Object candidate = deref(pairs.get(i), DISCARD_NOTES);
                if (treeKeyEquals(key, candidate)) {
                    return deref(pairs.get(i + 1), DISCARD_NOTES);
                }
            }
            return null;
        }
        List<Object> kids = asList(dict.get("Kids"));
        if (kids == null) {
            return null;
        }
        for (Object kid : kids) {
            Object hit = searchTree(kid, key, depth + 1, visited);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /**
     * Whether a tree key is the one that is looked for. A name tree keys its entries by
     * <em>string</em> (a damaged file sometimes uses a name), a number tree by a number, so the
     * comparison is by text or by number and never by the class a caller happened to pass.
     * @param wanted the searched key (string, name or number)
     * @param candidate the key found in the tree
     * @return whether the two are the same key
     */
    private static boolean treeKeyEquals(Object wanted, Object candidate) {
        if (wanted == null || candidate == null) {
            return false;
        }
        Long wantedNumber = asLong(wanted);
        Long candidateNumber = asLong(candidate);
        if (wantedNumber != null || candidateNumber != null) {
            return wantedNumber != null && wantedNumber.equals(candidateNumber);
        }
        String wantedText = keyText(wanted);
        String candidateText = keyText(candidate);
        return wantedText != null && wantedText.equals(candidateText);
    }

    /**
     * The text of a tree key: a string's text, a name's value (without the slash the rendering
     * adds) or a plain string; {@code null} for anything that is not a text key.
     */
    private static String keyText(Object value) {
        if (value instanceof PdfString string) {
            return string.text();
        }
        if (value instanceof PdfName name) {
            return name.value();
        }
        return value instanceof String text ? text : null;
    }

    /**
     * The label range that covers a page, see {@code /PageLabels} (the label itself is built by
     * the viewer from {@code /S}, {@code /P} and {@code /St}, so the range is shown as it is).
     */
    private Map<String, Object> labelFor(List<Map<String, Object>> ranges, int pageIndex) {
        Map<String, Object> best = null;
        long bestStart = Long.MIN_VALUE;
        for (Map<String, Object> range : ranges) {
            Long start = asLong(range.get("key"));
            if (start == null || start > pageIndex || start < bestStart) {
                continue;
            }
            bestStart = start;
            best = range;
        }
        if (best == null || !(best.get("value") instanceof Map<?, ?> value)) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("startIndex", bestStart);
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            result.put(camel(String.valueOf(entry.getKey())), brief(entry.getValue()));
        }
        return result;
    }

    // ===================================================================
    // Page index
    // ===================================================================

    /**
     * Page object number to 1-based page number, walking at most
     * {@link PdfDocument#DEFAULT_MAX_PAGES} pages.
     * @param notes note/warning sink (never null)
     * @return the index
     */
    public Map<Integer, Integer> pageIndex(List<String> notes) {
        List<PageEntry> pages = pageEntries(PdfDocument.DEFAULT_MAX_PAGES, notes);
        if (pages.size() >= PdfDocument.DEFAULT_MAX_PAGES) {
            notes.add("The page index stops after " + PdfDocument.DEFAULT_MAX_PAGES
                    + " pages: further page references are shown by object number only");
        }
        return numbersByObject(pages);
    }

    // ===================================================================
    // Shared helpers
    // ===================================================================

    /**
     * Reads a dictionary, following one indirect reference; a missing or foreign value is
     * reported in {@code notes} instead of guessing.
     */
    Map<String, Object> dictValue(Object value, List<String> notes) {
        Object resolved = deref(value, notes);
        if (resolved == null) {
            return null;
        }
        Map<String, Object> map = asMap(resolved);
        if (map == null) {
            note(notes, "Expected a dictionary but found " + PdfParser.kindOf(resolved)
                    + " - the entry was skipped");
            return null;
        }
        return map;
    }

    /**
     * Follows a reference (a plain value is returned unchanged).
     */
    Object deref(Object value, List<String> notes) {
        if (!(value instanceof PdfReference reference)) {
            return value;
        }
        try {
            return document.dereference(reference);
        } catch (PdfException e) {
            note(notes, "Reference " + reference + " could not be read: " + e.getMessage());
            return null;
        }
    }

    /**
     * Adds an explanation to a listing. A caller that does not want the explanation itself
     * passes {@link #DISCARD_NOTES}; that sink is read-only, and adding to it must never turn
     * into an error of the caller.
     */
    private static void note(List<String> notes, String text) {
        try {
            notes.add(text);
        } catch (UnsupportedOperationException ignored) {
            // A discarded note is a decision of the caller, not a failure of the walk.
        }
    }

    /**
     * A page attribute is inherited from the {@code /Parent} chain (ISO 32000-1, 7.7.3.4). A
     * broken chain reads as "not present" - {@link PdfDocument#pages(int, List)} already
     * reported what it could not walk, and a missing box must not fail the whole listing.
     */
    private Object inherited(Map<String, Object> pageDict, String key) {
        try {
            return document.inheritedAttribute(pageDict, key);
        } catch (PdfException e) {
            return null;
        }
    }

    private boolean hasInherited(Map<String, Object> pageDict, String key) {
        return inherited(pageDict, key) != null;
    }

    /** Object number of a reference value, else {@code null}. */
    static Integer refOf(Object value) {
        return value instanceof PdfReference reference ? reference.objNum() : null;
    }

    /** Long of a numeric value, else {@code null}. */
    static Long asLong(Object value) {
        if (value instanceof Long number) {
            return number;
        }
        if (value instanceof Integer number) {
            return (long) number;
        }
        return null;
    }

    /**
     * A numeric value as itself: an integer stays a {@code Long}, a real stays a {@code Double}.
     * Where an entry is stated as a number rather than an integer — {@code UserUnit} is the one in a
     * page dictionary (Table 3.27, S. 147) — this is the reading that neither loses the fraction nor
     * drops the entry.
     */
    static Object numericOrNull(Object value) {
        return value instanceof Number ? value : null;
    }

    /** The value as a list, else {@code null}. */
    @SuppressWarnings("unchecked")
    static List<Object> asList(Object value) {
        return value instanceof List<?> list ? (List<Object>) list : null;
    }

    /** The value as a dictionary, else {@code null}. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    /** The value as a list (only used on values already known to be lists). */
    @SuppressWarnings("unchecked")
    static List<Object> castList(Object value) {
        return value instanceof List<?> list ? (List<Object>) list : null;
    }

    /**
     * The numbers of an array value (a box, a rectangle, a colour) - {@code null} when the value
     * is not an array of numbers.
     */
    static List<Object> numbers(Object value) {
        List<Object> items = asList(value);
        if (items == null || items.isEmpty()) {
            return null;
        }
        List<Object> numbers = new ArrayList<>(items.size());
        for (Object item : items) {
            if (item instanceof Long || item instanceof Double || item instanceof Integer) {
                numbers.add(item);
            } else {
                return null;
            }
        }
        return numbers;
    }

    private static Object widthOf(List<Object> box) {
        return difference(box.get(2), box.get(0));
    }

    private static Object heightOf(List<Object> box) {
        return difference(box.get(3), box.get(1));
    }

    private static Object difference(Object high, Object low) {
        if (high instanceof Double || low instanceof Double) {
            return toDouble(high) - toDouble(low);
        }
        return toDouble(high).longValue() - toDouble(low).longValue();
    }

    private static Double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.NaN;
    }

    /** Short, JSON-safe rendering of a PDF value. */
    static Object brief(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof PdfName name) {
            return name.toString();
        }
        if (value instanceof PdfString string) {
            return clip(string.text(), MAX_TEXT_CHARS);
        }
        if (value instanceof PdfReference reference) {
            return reference.toString();
        }
        if (value instanceof Long || value instanceof Double || value instanceof Boolean
                || value instanceof Integer) {
            return value;
        }
        if (value instanceof List<?> list) {
            return list.size() + " item(s)";
        }
        if (value instanceof Map<?, ?> map) {
            return map.size() + " key(s)";
        }
        if (value instanceof PdfStream) {
            return "stream";
        }
        return String.valueOf(value);
    }

    /** Text clipped to {@link #MAX_TEXT_CHARS}. */
    static String clip(String text, int maxChars) {
        if (text == null) {
            return null;
        }
        if (maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "...";
    }

    /** The keys of a dictionary as {@code /Name} strings. */
    private static List<Object> keyNames(Map<String, Object> dict) {
        List<Object> names = new ArrayList<>();
        for (String key : dict.keySet()) {
            if (names.size() >= MAX_DICT_KEYS) {
                names.add("... (" + dict.size() + " keys in total)");
                break;
            }
            names.add("/" + key);
        }
        return names;
    }

    /** A histogram map as a list of {@code {name, count}} entries. */
    static List<Object> histogram(Map<String, Long> counts, String nameKey, String countKey) {
        List<Map.Entry<String, Long>> ordered = new ArrayList<>(counts.entrySet());
        ordered.sort((left, right) -> {
            int byCount = Long.compare(right.getValue(), left.getValue());
            return byCount != 0 ? byCount : left.getKey().compareTo(right.getKey());
        });
        List<Object> result = new ArrayList<>();
        for (Map.Entry<String, Long> entry : ordered) {
            if (result.size() >= MAX_HISTOGRAM_ENTRIES) {
                Map<String, Object> rest = new LinkedHashMap<>();
                rest.put(nameKey, "...");
                rest.put(countKey, (long) (ordered.size() - result.size()));
                rest.put("more", true);
                result.add(rest);
                break;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put(nameKey, entry.getKey());
            item.put(countKey, entry.getValue());
            result.add(item);
        }
        return result;
    }

    private static String join(List<Object> numbers) {
        StringBuilder sb = new StringBuilder();
        for (Object number : numbers) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(number);
        }
        return sb.toString();
    }

    private static boolean matchesNeedle(Object key, String needle) {
        if (key == null) {
            return false;
        }
        return String.valueOf(key).toLowerCase(java.util.Locale.ROOT)
                .contains(needle.toLowerCase(java.util.Locale.ROOT));
    }

    private static String camel(String key) {
        if (key.isEmpty()) {
            return key;
        }
        return Character.toLowerCase(key.charAt(0)) + key.substring(1);
    }
}
