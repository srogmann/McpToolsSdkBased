package org.rogmann.mcp2sdk.pdf;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.rogmann.mcp2sdk.pdf.ops.MarkedContentSink;
import org.rogmann.mcp2sdk.pdf.ops.PdfContentInterpreter;
import org.rogmann.mcp2sdk.pdf.state.PdfGraphicsState;

import static org.rogmann.mcp2sdk.pdf.PdfDocumentStructure.asList;
import static org.rogmann.mcp2sdk.pdf.PdfDocumentStructure.asLong;
import static org.rogmann.mcp2sdk.pdf.PdfDocumentStructure.asMap;
import static org.rogmann.mcp2sdk.pdf.PdfDocumentStructure.clip;
import static org.rogmann.mcp2sdk.pdf.PdfDocumentStructure.histogram;
import static org.rogmann.mcp2sdk.pdf.PdfDocumentStructure.refOf;

/**
 * The <em>logical</em> structure of a document: the tagged-PDF layer of ISO 32000-1, 14.8.
 * <p>
 * A PDF carries two structures. The page tree ({@link PdfDocumentStructure}) says which pages
 * exist and what is drawn on them; the <em>structure tree</em> of
 * {@code /Catalog/StructTreeRoot} says what the document <em>means</em>: headings, paragraphs,
 * list items, table rows and cells - each of them tied to the marked-content sequence of a page
 * content stream that shows it. That second layer is what a screen reader reads, what a
 * converter uses to rebuild a Word document, and what an accessibility check (WCAG, EN 301 549,
 * PDF/UA) looks at.
 * </p>
 * <p>
 * Three pieces belong together and this class reads all three:
 * </p>
 * <ul>
 *   <li>the <em>structure tree</em> itself: {@code /StructTreeRoot}, its elements
 *       ({@code /S} tag, {@code /K} children, {@code /Pg} page, {@code /Alt}, {@code /Lang},
 *       attributes) and its {@code /RoleMap}, which maps producer-specific tags onto the
 *       standard roles;</li>
 *   <li>the <em>{@code /ParentTree}</em>, a number tree keyed by the {@code /StructParents} value
 *       of a page, holding the top-level elements of that page - without it the tree and the
 *       pages are two islands;</li>
 *   <li>the <em>marked-content sequences</em> of the content streams ({@code /BMC}, {@code /BDC}
 *       with its {@code /MCID}, {@code /EMC}), which is where a structure element points to.</li>
 * </ul>
 * <p>
 * Consistency between those three is exactly what is usually broken in real documents, so
 * {@link #markedContent(Integer, int, int, int)} reports both directions: marked content that no
 * structure element claims, and structure elements whose {@code /MCID} never appears on the page.
 * </p>
 */
public final class PdfStructTree {

    /** Structure elements a listing returns by default. */
    public static final int DEFAULT_MAX_ELEMENTS = 1000;

    /** Elements {@link #summary(int)} counts - a summary should not walk a huge tree. */
    public static final int SUMMARY_MAX_ELEMENTS = 5000;

    /** Nesting limit of a structure walk by default. */
    public static final int DEFAULT_MAX_DEPTH = 100;

    /** Hard nesting limit (a producer that builds a cyclic tree stops here). */
    public static final int MAX_STRUCTURE_DEPTH = 500;

    /** Depth limit of a number tree walk. */
    public static final int MAX_TREE_DEPTH = 64;

    /** Pages {@link #markedContent(Integer, int, int, int)} walks by default. */
    public static final int DEFAULT_MAX_PAGES = 50;

    /** Marked-content sequences listed per page by default. */
    public static final int DEFAULT_MAX_ITEMS = 300;

    /** Characters of text extracted per (outermost) marked-content sequence. */
    public static final int DEFAULT_MAX_TEXT_CHARS = 300;

    /** Elements walked to decide which marked content a page claims. */
    public static final int MAX_CLAIM_ELEMENTS = 20_000;

    /** Characters of a text value (title, alt text) shown in a listing. */
    public static final int MAX_TEXT_CHARS = 200;

    /** Roles a listing shows. */
    private static final int MAX_LISTED_ROLES = 40;

    /** Identifiers shown in a "not claimed" list. */
    private static final int MAX_LISTED_IDS = 25;

    /** The document whose structure is read. */
    private final PdfDocument document;

    /** Page object number to 1-based page number, built on first need. */
    private Map<Integer, Integer> pageNumbers;
    /** Whether that index stopped at its page limit. */
    private boolean pageIndexTruncated;
    /** Explanations that must not repeat per instance. */
    private final Set<String> noted = new HashSet<>();

    /**
     * Creates the structure view of one document.
     * @param document the document
     */
    public PdfStructTree(PdfDocument document) {
        this.document = document;
    }

    /**
     * The root of the structure tree.
     *
     * @param dict the {@code /StructTreeRoot} dictionary
     * @param objNum its object number ({@code null} for a direct object)
     */
    private record Root(Map<String, Object> dict, Integer objNum) {
    }

    /** Elements created so far and whether a limit stopped the walk. */
    private static final class Budget {
        private final int limit;
        private int used;
        private boolean stopped;

        private Budget(int limit) {
            this.limit = limit;
        }

        /** Whether the budget is used up (and the walk must be marked as cut). */
        private boolean exhausted() {
            if (limit > 0 && used >= limit) {
                stopped = true;
                return true;
            }
            return false;
        }
    }

    /** What a page's structure elements claim. */
    private static final class Claims {
        private final Set<Long> mcids = new LinkedHashSet<>();
        private final Set<Integer> objects = new LinkedHashSet<>();
        private boolean truncated;
        private boolean fromParentTree;
    }

    // ===================================================================
    // Summary
    // ===================================================================

    /**
     * The short answer: is this document tagged, how many elements, which tags, is there a
     * {@code /ParentTree} and a {@code /RoleMap}.
     * @param maxElements element budget of the counting walk (0 = no limit)
     * @return the summary map
     */
    public Map<String, Object> summary(int maxElements) {
        List<String> notes = new ArrayList<>();
        Map<String, Object> result = new LinkedHashMap<>();
        Root root = root(notes);
        if (root == null) {
            // root() explained why (no catalogue, no /StructTreeRoot, or an unreadable one).
            result.put("present", false);
            result.put("notes", new ArrayList<Object>(notes));
            return result;
        }
        result.put("present", true);
        if (root.objNum() != null) {
            result.put("objNum", (long) root.objNum());
        }

        Budget budget = new Budget(maxElements);
        Map<String, Long> tags = new LinkedHashMap<>();
        int[] deepest = new int[1];
        Object k = root.dict().get("K");
        if (k == null) {
            notes.add("/StructTreeRoot has no /K: no root element is declared");
        } else if (k instanceof Long declared) {
            result.put("declaredElementCount", declared);
            notes.add("/StructTreeRoot /K is the number " + declared + " instead of the array of"
                    + " root elements, so the tree cannot be walked from the root");
        } else {
            List<Object> kids = kidList(k);
            if (kids != null) {
                for (Object kid : kids) {
                    countElement(kid, null, 1, budget, tags, deepest, notes);
                    if (budget.stopped) {
                        break;
                    }
                }
            }
        }
        result.put("elementCount", (long) budget.used);
        if (budget.stopped) {
            result.put("elementLimitReached", true);
        }
        result.put("distinctTags", (long) tags.size());
        result.put("tagHistogram", histogram(tags, "tag", "count"));
        result.put("maxDepth", (long) deepest[0]);

        Map<String, String> roles = roleMap(root.dict(), notes);
        if (!roles.isEmpty()) {
            result.put("roleMapEntries", (long) roles.size());
            List<Object> listed = new ArrayList<>();
            for (Map.Entry<String, String> entry : roles.entrySet()) {
                if (listed.size() >= MAX_LISTED_ROLES) {
                    result.put("roleMapTruncated", true);
                    break;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("from", entry.getKey());
                item.put("to", entry.getValue());
                listed.add(item);
            }
            result.put("roleMap", listed);
        }

        Object parentTree = root.dict().get("ParentTree");
        if (parentTree != null) {
            int[] keys = new int[1];
            boolean[] cut = new boolean[1];
            countNumberTreeKeys(parentTree, 0, new HashSet<>(), keys, cut);
            Map<String, Object> info = new LinkedHashMap<>();
            Integer objNum = refOf(parentTree);
            if (objNum != null) {
                info.put("objNum", (long) objNum);
            }
            info.put("pageKeys", (long) keys[0]);
            if (cut[0]) {
                info.put("truncated", true);
            }
            info.put("note", "the keys are the /StructParents values of the pages that carry"
                    + " structure");
            result.put("parentTree", info);
        } else {
            notes.add("/StructTreeRoot has no /ParentTree: pages and structure elements can only"
                    + " be matched through the /Pg entries inside the tree (a PDF 1.3-1.6 document"
                    + " or a producer that skipped it)");
        }
        if (root.dict().containsKey("ClassMap")) {
            result.put("classMap", true);
        }
        if (root.dict().containsKey("IDTree")) {
            result.put("idTree", true);
        }
        if (pageIndexTruncated) {
            notes.add("Page numbers were resolved for the first " + pageIndexLimit()
                    + " pages only; further pages show their object number");
        }
        if (!notes.isEmpty()) {
            result.put("notes", new ArrayList<Object>(notes));
        }
        return result;
    }

    /**
     * The children a {@code /K} entry holds. The specification allows three forms: an array, a
     * single element (direct or referenced) and a marked-content number - so a value that is
     * neither an array nor a number is <em>one</em> child, not a defect.
     * @param k the {@code /K} value
     * @return the children, or {@code null} for {@code /K} absent or a plain number
     */
    private static List<Object> kidList(Object k) {
        if (k == null || k instanceof Long) {
            return null;
        }
        List<Object> array = asList(k);
        return array != null ? array : List.of(k);
    }

    /**
     * Counts elements, tags and depth without building the tree.
     */
    private void countElement(Object node, Integer inheritedPage, int depth, Budget budget,
                              Map<String, Long> tags, int[] deepest, List<String> notes) {
        if (budget.exhausted()) {
            return;
        }
        if (depth > MAX_STRUCTURE_DEPTH) {
            budget.stopped = true;
            noteOnce(notes, "deep", "A structure tree deeper than " + MAX_STRUCTURE_DEPTH
                    + " levels is treated as damaged - the walk stops there");
            return;
        }
        Object value = deref(node);
        if (value == null || value instanceof Long) {
            // A marked-content identifier is a child, not an element.
            return;
        }
        Map<String, Object> dict = asMap(value);
        if (dict == null || PdfName.dictHas(dict, "Type", "OBJR")) {
            return;
        }
        budget.used++;
        deepest[0] = Math.max(deepest[0], depth);
        if (dict.get("S") instanceof PdfName tag) {
            tags.merge(tag.toString(), 1L, Long::sum);
        }
        List<Object> kids = kidList(dict.get("K"));
        if (kids == null) {
            return;
        }
        Integer page = refOf(dict.get("Pg"));
        Integer effective = page != null ? page : inheritedPage;
        for (Object kid : kids) {
            countElement(kid, effective, depth + 1, budget, tags, deepest, notes);
            if (budget.stopped) {
                return;
            }
        }
    }

    // ===================================================================
    // The tree
    // ===================================================================

    /**
     * The structure tree as a nested listing.
     * @param maxElements element budget (0 = no limit)
     * @param maxDepth nesting limit (0 = {@link #DEFAULT_MAX_DEPTH})
     * @param attributes whether attribute lists ({@code /A}) and attachments ({@code /AF}) are
     *        included
     * @return {@code {present, objNum, elements: [...], tagHistogram, ...}}
     */
    public Map<String, Object> tree(int maxElements, int maxDepth, boolean attributes) {
        List<String> notes = new ArrayList<>();
        Map<String, Object> result = new LinkedHashMap<>();
        Root root = root(notes);
        if (root == null) {
            result.put("present", false);
            result.put("notes", new ArrayList<Object>(notes));
            return result;
        }
        result.put("present", true);
        if (root.objNum() != null) {
            result.put("objNum", (long) root.objNum());
        }
        Map<String, String> roles = roleMap(root.dict(), notes);
        if (!roles.isEmpty()) {
            result.put("roleMap", roles);
        }
        Budget budget = new Budget(maxElements);
        int depthLimit = maxDepth <= 0 ? DEFAULT_MAX_DEPTH : Math.min(maxDepth, MAX_STRUCTURE_DEPTH);
        Map<String, Long> tags = new LinkedHashMap<>();
        int[] deepest = new int[1];
        List<Object> elements = new ArrayList<>();

        Object k = root.dict().get("K");
        if (k == null) {
            notes.add("/StructTreeRoot has no /K: no root element is declared");
        } else if (k instanceof Long declared) {
            result.put("declaredElementCount", declared);
            notes.add("/StructTreeRoot /K is the number " + declared + " instead of the array of"
                    + " root elements, so the tree cannot be walked from the root");
        } else {
            List<Object> kids = kidList(k);
            if (kids != null) {
                for (int i = 0; i < kids.size(); i++) {
                    Map<String, Object> item = describeKid(kids.get(i),
                            "/StructTreeRoot/K" + i, null, 1, budget, depthLimit, roles, tags,
                            deepest, attributes, notes);
                    if (item != null) {
                        elements.add(item);
                    }
                    if (budget.stopped) {
                        break;
                    }
                }
            }
        }
        result.put("elementCount", (long) budget.used);
        result.put("maxDepth", (long) deepest[0]);
        if (!tags.isEmpty()) {
            result.put("tagHistogram", histogram(tags, "tag", "count"));
        }
        result.put("elements", elements);
        if (budget.stopped) {
            result.put("truncated", true);
            notes.add("Stopped after " + budget.used + " element(s): raise maxElements or read one"
                    + " subtree with pdf.object(path, objNum, {resolveRefs: true})");
        }
        if (pageIndexTruncated) {
            notes.add("Page numbers were resolved for the first " + pageIndexLimit()
                    + " pages only; further pages show their object number");
        }
        if (!notes.isEmpty()) {
            result.put("notes", new ArrayList<Object>(notes));
        }
        return result;
    }

    /**
     * One child of {@code /K}: a nested element, a marked-content identifier or an
     * {@code /OBJR} reference to an annotation or XObject.
     */
    private Map<String, Object> describeKid(Object node, String pathId, Integer inheritedPage,
                                            int depth, Budget budget, int depthLimit,
                                            Map<String, String> roles, Map<String, Long> tags,
                                            int[] deepest, boolean attributes, List<String> notes) {
        Object value = deref(node);
        if (value == null) {
            return null;
        }
        if (value instanceof Long mcid) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kind", "mcid");
            item.put("mcid", mcid);
            putPage(item, inheritedPage);
            return item;
        }
        Map<String, Object> dict = asMap(value);
        if (dict == null) {
            notes.add("A child of a structure element is " + PdfParser.kindOf(value)
                    + " instead of an element, a marked-content number or a reference - skipped");
            return null;
        }
        if (PdfName.dictHas(dict, "Type", "OBJR")) {
            return objectReference(dict);
        }
        return element(dict, refOf(node), pathId, inheritedPage, depth, budget, depthLimit, roles,
                tags, deepest, attributes, notes);
    }

    /**
     * One structure element with its children.
     */
    private Map<String, Object> element(Map<String, Object> dict, Integer objNum, String pathId,
                                        Integer inheritedPage, int depth, Budget budget,
                                        int depthLimit, Map<String, String> roles,
                                        Map<String, Long> tags, int[] deepest, boolean attributes,
                                        List<String> notes) {
        PdfName tag = PdfName.ofDict(dict, "S");
        if (budget.exhausted()) {
            Map<String, Object> marker = new LinkedHashMap<>();
            marker.put("kind", "element");
            marker.put("truncated", true);
            if (objNum != null) {
                marker.put("objNum", (long) objNum);
            }
            if (tag != null) {
                marker.put("tag", tag.toString());
            }
            return marker;
        }
        budget.used++;
        deepest[0] = Math.max(deepest[0], depth);

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("kind", "element");
        if (tag != null) {
            item.put("tag", tag.toString());
            tags.merge(tag.toString(), 1L, Long::sum);
            String mapped = roles.get(tag.toString());
            if (mapped != null) {
                item.put("role", mapped);
            }
        } else {
            noteOnce(notes, "noTag", "At least one structure element has no /S tag: the producer"
                    + " wrote a PDF 2.0 element type or left the tag away - it appears without"
                    + " 'tag'");
        }
        String id = objNum != null ? objNum + " 0 R" : pathId;
        item.put("id", id);
        if (objNum != null) {
            item.put("objNum", (long) objNum);
        } else {
            item.put("inline", true);
        }
        Integer ownPage = refOf(dict.get("Pg"));
        putPage(item, ownPage != null ? ownPage : inheritedPage);
        if (dict.get("T") instanceof PdfString title) {
            item.put("title", clip(title.text(), MAX_TEXT_CHARS));
        }
        if (dict.get("Lang") instanceof PdfString lang) {
            item.put("lang", clip(lang.text(), MAX_TEXT_CHARS));
        }
        if (dict.get("Alt") instanceof PdfString alt) {
            item.put("alt", clip(alt.text(), MAX_TEXT_CHARS));
        }
        if (dict.get("ActualText") instanceof PdfString actual) {
            item.put("actualText", clip(actual.text(), MAX_TEXT_CHARS));
        }
        if (dict.get("E") instanceof PdfString expanded) {
            item.put("expansion", clip(expanded.text(), MAX_TEXT_CHARS));
        }
        Long revision = asLong(dict.get("R"));
        if (revision != null) {
            item.put("revision", revision);
        }
        if (dict.get("Placement") instanceof PdfName placement) {
            item.put("placement", placement.toString());
        }
        if (attributes) {
            if (dict.get("A") != null) {
                item.put("attributes", attributeSummary(dict.get("A"), notes));
            }
            if (dict.get("AF") != null) {
                item.put("attachments", attachmentSummary(dict.get("AF")));
            }
        }

        Object k = dict.get("K");
        if (k == null) {
            return item;
        }
        if (k instanceof Long mcid) {
            // The element covers exactly one marked-content sequence on its page.
            item.put("mcid", mcid);
            return item;
        }
        List<Object> kids = kidList(k);
        if (kids == null) {
            return item;
        }
        if (depth >= depthLimit) {
            item.put("childCount", (long) kids.size());
            item.put("childrenTruncated", true);
            noteOnce(notes, "depth", "The tree is deeper than maxDepth=" + depthLimit
                    + ": further levels read as {childrenTruncated: true} - raise maxDepth");
            return item;
        }
        Integer effectivePage = ownPage != null ? ownPage : inheritedPage;
        List<Map<String, Object>> children = new ArrayList<>();
        for (int i = 0; i < kids.size(); i++) {
            Map<String, Object> child = describeKid(kids.get(i), id + "/K" + i, effectivePage,
                    depth + 1, budget, depthLimit, roles, tags, deepest, attributes, notes);
            if (child != null) {
                children.add(child);
            }
            if (budget.stopped) {
                item.put("childrenTruncated", true);
                break;
            }
        }
        item.put("childCount", (long) children.size());
        item.put("children", children);
        return item;
    }

    /**
     * An {@code /OBJR} child: a structure element referring to an annotation or an XObject
     * instead of to marked content.
     */
    private Map<String, Object> objectReference(Map<String, Object> dict) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("kind", "objectReference");
        Integer objNum = refOf(dict.get("Obj"));
        if (objNum != null) {
            item.put("objNum", (long) objNum);
            try {
                Object target = document.objectValue(objNum);
                item.put("targetKind", PdfParser.kindOf(target));
                Map<String, Object> targetDict = target instanceof PdfStream stream
                        ? stream.dict() : asMap(target);
                if (targetDict != null) {
                    PdfName type = PdfName.ofDict(targetDict, "Type");
                    if (type != null) {
                        item.put("targetType", type.toString());
                    }
                    PdfName subtype = PdfName.ofDict(targetDict, "Subtype");
                    if (subtype != null) {
                        item.put("targetSubtype", subtype.toString());
                    }
                }
            } catch (PdfException e) {
                item.put("targetUnreadable", e.getMessage());
            }
        } else if (dict.get("Obj") != null) {
            item.put("targetKind", PdfParser.kindOf(dict.get("Obj")));
        }
        putPage(item, refOf(dict.get("Pg")));
        return item;
    }

    /**
     * Attribute lists ({@code /A}) as the property lists they are: owner, type and the role
     * keys, without pretending to know their meaning.
     */
    private List<Object> attributeSummary(Object value, List<String> notes) {
        List<Object> entries = asList(value);
        if (entries == null) {
            entries = List.of(value);
        }
        List<Object> result = new ArrayList<>();
        for (Object entry : entries) {
            Object target = deref(entry);
            if (target == null) {
                continue;
            }
            if (target instanceof PdfStream stream) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("propertyListStream", true);
                Integer objNum = refOf(entry);
                if (objNum != null) {
                    item.put("objNum", (long) objNum);
                }
                PdfName type = PdfName.ofDict(stream.dict(), "Type");
                if (type != null) {
                    item.put("type", type.toString());
                }
                result.add(item);
                continue;
            }
            Map<String, Object> dict = asMap(target);
            if (dict == null) {
                notes.add("An entry of an attribute list is " + PdfParser.kindOf(target)
                        + " instead of a property list - skipped");
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            if (dict.get("O") instanceof PdfName owner) {
                item.put("owner", owner.toString());
            }
            if (dict.get("Type") instanceof PdfName type) {
                item.put("type", type.toString());
            }
            Integer elementObjNum = refOf(dict.get("X"));
            if (elementObjNum != null) {
                item.put("elementObjNum", (long) elementObjNum);
            }
            List<Object> keys = new ArrayList<>();
            for (String key : dict.keySet()) {
                if (key.equals("O") || key.equals("Type") || key.equals("X")) {
                    continue;
                }
                keys.add("/" + key);
            }
            item.put("keys", keys);
            result.add(item);
        }
        return result;
    }

    /**
     * Associated files ({@code /AF}) of an element.
     */
    private List<Object> attachmentSummary(Object value) {
        List<Object> entries = asList(value);
        if (entries == null) {
            entries = List.of(value);
        }
        List<Object> result = new ArrayList<>();
        for (Object entry : entries) {
            Map<String, Object> dict = asMap(deref(entry));
            Map<String, Object> item = new LinkedHashMap<>();
            Integer objNum = refOf(entry);
            if (objNum != null) {
                item.put("objNum", (long) objNum);
            }
            if (dict != null) {
                PdfName type = PdfName.ofDict(dict, "Type");
                if (type != null) {
                    item.put("type", type.toString());
                }
                if (dict.get("F") instanceof PdfString name) {
                    item.put("fileName", clip(name.text(), MAX_TEXT_CHARS));
                }
                if (dict.get("AFFileName") instanceof PdfString afName) {
                    item.put("fileName", clip(afName.text(), MAX_TEXT_CHARS));
                }
                PdfName subtype = PdfName.ofDict(dict, "Subtype");
                if (subtype != null) {
                    item.put("subtype", subtype.toString());
                }
            }
            result.add(item);
        }
        return result;
    }

    // ===================================================================
    // Marked content
    // ===================================================================

    /**
     * The marked-content sequences of the pages and what the structure tree claims of them; the
     * text of a sequence is read through the font that was current when the sequence opened.
     * @param pageIndex 1-based page number, or {@code null} for all walked pages
     * @param maxPages page limit when no page is given
     * @param maxItems sequences listed per page (0 = no limit)
     * @param maxTextChars characters of text per outermost sequence (0 = no text)
     * @return one entry per page; a page explains itself in its own {@code notes}, and only what
     *         the walk itself could not do (a structure tree that is missing, a page walk that
     *         stopped) is added as a trailing {@code {page: null, notes}} entry
     */
    public List<Map<String, Object>> markedContent(Integer pageIndex, int maxPages, int maxItems,
                                                  int maxTextChars) {
        return markedContent(pageIndex, maxPages, maxItems, maxTextChars, true);
    }

    /**
     * The marked-content sequences of the pages, with or without their fonts.
     * @param pageIndex 1-based page number, or {@code null} for all walked pages
     * @param maxPages page limit when no page is given
     * @param maxItems sequences listed per page (0 = no limit)
     * @param maxTextChars characters of text per outermost sequence (0 = no text)
     * @param useFonts whether the text of a sequence is read through the font its {@code Tf} names
     *                 (false: the document convention, the documented raw view)
     * @return one entry per page
     */
    public List<Map<String, Object>> markedContent(Integer pageIndex, int maxPages, int maxItems,
                                                  int maxTextChars, boolean useFonts) {
        List<String> notes = new ArrayList<>();
        List<PdfDocument.Page> pages = document.pagesForRead(pageIndex, maxPages, notes);
        Root root = root(notes);
        Object parentTree = root != null ? root.dict().get("ParentTree") : null;
        List<Map<String, Object>> result = new ArrayList<>();
        PdfFontScope.Budget budget = new PdfFontScope.Budget(PdfFontScope.MAX_TEXT_CODES);
        for (PdfDocument.Page page : pages) {
            if (pageIndex != null && page.page() != pageIndex) {
                continue;
            }
            result.add(pageMarkedContent(page, root, parentTree, maxItems, maxTextChars, useFonts,
                    budget));
        }
        if (pageIndex != null && result.isEmpty()) {
            throw new PdfException(document.pageNotFound(pageIndex, pages.size(), notes));
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
     * One page: its sequences, the text they contain, and the two consistency directions.
     * Everything a page itself is to say goes into the {@code notes} of its own entry - a page
     * listing should not have to explain one page in a document-wide note.
     */
    private Map<String, Object> pageMarkedContent(PdfDocument.Page page, Root root,
                                                 Object parentTree, int maxItems,
                                                 int maxTextChars, boolean useFonts,
                                                 PdfFontScope.Budget budget) {
        List<String> pageNotes = new ArrayList<>();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("page", (long) page.page());
        item.put("objNum", (long) page.objNum());
        Map<String, Object> pageDict = document.dictOf(page.objNum());
        Long structParents = asLong(pageDict.get("StructParents"));
        if (structParents != null) {
            item.put("structParents", structParents);
        }
        Claims claims = claimsFor(root, parentTree, structParents, page.objNum(), pageNotes);

        byte[] content;
        try {
            content = document.pageContent(page, pageNotes);
        } catch (PdfException e) {
            item.put("error", e.getMessage());
            item.put("notes", new ArrayList<Object>(pageNotes));
            return item;
        }
        List<PdfContentStream.Token> tokens = PdfContentStream.tokenize(content, pageNotes);
        // The scope is only worth building when text is asked for at all; it reads nothing before
        // the first Tf of the page anyway.
        PdfFontScope scope = useFonts && maxTextChars > 0
                ? new PdfFontScope(document, page.objNum(), pageNotes, budget)
                : null;

        // Since D4 this is one walk with a listener, not a second token loop: the sink owns the
        // nesting, the operands and the byte slice of a sequence, the listener below owns what only
        // the document can say - which /MCID the structure tree claims, what a property name resolves
        // to, and how long a text may be.
        MarkedSequenceListing listing = new MarkedSequenceListing(pageDict, claims, pageNotes,
                maxTextChars, scope, page.page());
        // A slice is read for its text whenever text was asked for at all - also in the raw view
        // (useFonts false), where the strings read by the document convention instead.
        MarkedContentSink sink = new MarkedContentSink(content, maxItems,
                maxTextChars > 0 ? listing : null, listing);
        // The walk's own sentences are stream problems (an operator budget that cut the stream); the
        // sink's findings go to pageNotes through the listener, in document order.
        List<String> walkNotes = new ArrayList<>();
        PdfContentInterpreter.run(tokens, PdfContentInterpreter.Options.defaults(), walkNotes, sink);
        pageNotes.addAll(walkNotes);

        long total = sink.sequencesFound();
        long unmarkedTextOps = sink.unmarkedTextOperators();
        boolean truncated = sink.truncated();
        if (sink.openAtEnd() > 0) {
            pageNotes.add("Page " + page.page() + " ends with " + sink.openAtEnd()
                    + " marked-content sequence(s) still open (no matching EMC)");
        }
        if (listing.withoutStartFont && scope != null && scope.stringsWithoutFont() > 0) {
            // The text of a sequence is cut out of the stream, and the Tf that sets its font stands
            // before the cut. When the state at the cut names no font and the sequence shows text
            // before its own first Tf, the document convention read those strings - said once per
            // page, with the number of strings it concerns.
            scope.note(PdfFontScope.NOTE_INITIAL_FONT,
                    scope.stringsWithoutFont() + " string(s) of a marked-content sequence"
                            + " on page " + page.page() + " are shown before any Tf names a font"
                            + " (none was in effect before the sequence either): the document"
                            + " convention read them, so a CID font would show codes here");
        }
        List<Object> sequences = listing.sequences;
        Set<Long> seenMcids = listing.seenMcids;
        Set<Long> unclaimed = listing.unclaimed;
        long artifacts = listing.artifacts;
        item.put("marked", total > 0);
        item.put("sequenceCount", total);
        item.put("sequences", sequences);
        if (scope != null) {
            // The fonts that drew the text of this page - the same fields pdf.text() shows, and no
            // second mapping block: the numbers of a page are read with pdf.text(path, {page: n}).
            List<Map<String, Object>> drawers = PdfFont.usedFontEntries(document, scope, pageNotes);
            if (!drawers.isEmpty()) {
                item.put("fonts", drawers);
            }
        }
        if (truncated) {
            item.put("truncated", true);
            item.put("note", "the first " + maxItems + " of " + total
                    + " sequence(s) are listed (raise maxItems)");
        }
        if (artifacts > 0) {
            item.put("artifacts", artifacts);
        }
        if (claims.fromParentTree) {
            // Only the /ParentTree says which elements belong to *this* page; without it the
            // numbers below are document-wide and say so.
            item.put("structureElements", (long) claims.mcids.size() + claims.objects.size());
        }
        if (!claims.mcids.isEmpty()) {
            List<Object> notMarked = new ArrayList<>();
            for (Long mcid : claims.mcids) {
                if (!seenMcids.contains(mcid) && notMarked.size() < MAX_LISTED_IDS) {
                    notMarked.add(mcid);
                }
            }
            if (!notMarked.isEmpty()) {
                item.put("claimedButNotMarked", notMarked);
            }
        }
        if (!claims.objects.isEmpty()) {
            item.put("structureObjectRefs", limit(claims.objects));
        }
        if (!unclaimed.isEmpty()) {
            item.put("unclaimedMcids", limit(unclaimed));
        }
        if (unmarkedTextOps > 0) {
            item.put("unmarkedTextOperators", unmarkedTextOps);
            pageNotes.add(unmarkedTextOps + " text-showing operator(s) (Tj, TJ, ' or \") on page "
                    + page.page() + " draw outside any marked-content sequence: that text is"
                    + " invisible to the structure tree");
        }
        if (claims.truncated) {
            item.put("claimWalkTruncated", true);
        }
        if (!claims.fromParentTree && structParents != null) {
            item.put("withoutParentTree", true);
        }
        if (!pageNotes.isEmpty()) {
            item.put("notes", new ArrayList<Object>(pageNotes));
        }
        return item;
    }

    /**
     * What {@link #pageMarkedContent} makes of the sequences the walk reports: the listing itself, the
     * artifact count, each {@code /MCID} paired with what the structure tree claims, and - since
     * increment D4 - the text of a sequence read in the state its first byte was written in.
     *
     * <p>
     * It answers the walk's two announcements (see
     * {@link org.rogmann.mcp2sdk.pdf.ops.MarkedContentSink.Listener}) rather than running a loop of
     * its own, which is what keeps the page's notes in document order: a sequence's own sentences
     * (an unusable tag operand, a property list that is no dictionary, the notes of its slice) are
     * appended at the moment the operator that caused them is walked, exactly where the old token loop
     * appended them.
     * </p>
     */
    private final class MarkedSequenceListing implements MarkedContentSink.Listener,
                                                        MarkedContentSink.SliceReader {

        private final List<Object> sequences = new ArrayList<>();
        private final Set<Long> seenMcids = new LinkedHashSet<>();
        private final Set<Long> unclaimed = new LinkedHashSet<>();
        /** The listed maps of the sequences still open, so that their text can join them at the EMC. */
        private final Map<Long, Map<String, Object>> openSequences = new LinkedHashMap<>();

        private final Map<String, Object> pageDict;
        private final Claims claims;
        private final List<String> pageNotes;
        private final int maxTextChars;
        private final PdfFontScope scope;
        private final int pageIndex;

        private long artifacts;
        /** True when a slice began while no font was in effect - the page says so once, below. */
        private boolean withoutStartFont;

        private MarkedSequenceListing(Map<String, Object> pageDict, Claims claims,
                                      List<String> pageNotes, int maxTextChars, PdfFontScope scope,
                                      int pageIndex) {
            this.pageDict = pageDict;
            this.claims = claims;
            this.pageNotes = pageNotes;
            this.maxTextChars = maxTextChars;
            this.scope = scope;
            this.pageIndex = pageIndex;
        }

        @Override
        public void opened(MarkedContentSink.Sequence sequence) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("sequence", sequence.index());
            entry.put("operator", sequence.operator());
            entry.put("depth", (long) sequence.depth());
            entry.put("offset", (long) sequence.offset());
            String tag = null;
            Object tagOperand = sequence.tagOperand();
            if (tagOperand instanceof PdfName name) {
                tag = name.toString();
                entry.put("tag", tag);
            } else if (tagOperand instanceof PdfString string) {
                tag = clip(string.text(), MAX_TEXT_CHARS);
                entry.put("tag", tag);
            }
            if ("/Artifact".equals(tag)) {
                artifacts++;
                entry.put("artifact", true);
            }
            Object propertyOperand = sequence.propertyOperand();
            Map<String, Object> properties = markedProperties(pageDict, propertyOperand, pageNotes);
            Long mcid = properties != null ? asLong(properties.get("MCID")) : null;
            if (mcid != null) {
                entry.put("mcid", mcid);
                seenMcids.add(mcid);
                boolean claimed = claims.mcids.contains(mcid);
                entry.put("inStructureTree", claimed);
                if (!claimed && !"/Artifact".equals(tag)) {
                    unclaimed.add(mcid);
                }
            } else if (propertyOperand != null && !(propertyOperand instanceof Map)) {
                entry.put("propertyList", PdfDocumentStructure.brief(propertyOperand));
            }
            if (sequence.listed()) {
                sequences.add(entry);
                openSequences.put(sequence.index(), entry);
            }
        }

        @Override
        public void closed(MarkedContentSink.Sequence sequence) {
            Map<String, Object> entry = openSequences.remove(sequence.index());
            if (entry == null || sequence.text() == null) {
                return;
            }
            String text = sequence.text().trim();
            if (!text.isEmpty()) {
                entry.put("text", clip(text, maxTextChars));
            }
        }

        @Override
        public void tagIsNotAName(int offset, Object tagOperand) {
            pageNotes.add("A marked-content tag at offset " + offset + " is "
                    + PdfParser.kindOf(tagOperand) + " instead of a name");
        }

        @Override
        public void strayEmc(int offset) {
            pageNotes.add("An EMC at offset " + offset + " closes no marked-content sequence");
        }

        /**
         * The text of one sequence's bytes, starting from the state the sequence opened in: the
         * {@code Tf} that applies to its first string stands outside the slice, and since D4 the walk
         * hands that state over instead of this class searching the tokens to the left for a font name.
         */
        @Override
        public String read(byte[] slice, PdfGraphicsState state) {
            if (state == null || !state.textState().hasFont()) {
                withoutStartFont = true;
            }
            return PdfContentStream.text(slice, scope, state, pageNotes);
        }

        @Override
        public String toString() {
            return "page " + pageIndex + ": " + sequences.size() + " listed sequence(s)";
        }
    }

    /**
     * A "these numbers" list, cut after {@link #MAX_LISTED_IDS} entries. Every object number in a
     * result is a {@code long} (the object numbers of a file are, and a reader compares them
     * against {@code pdf.object(path, objNum)}), so the values are widened here rather than
     * leaking the {@code Integer} a lookup returned.
     */
    private static List<Object> limit(Set<? extends Number> values) {
        List<Object> result = new ArrayList<>();
        for (Number value : values) {
            if (result.size() >= MAX_LISTED_IDS) {
                result.add("... (" + values.size() + " in total)");
                break;
            }
            result.add(value.longValue());
        }
        return result;
    }

    /**
     * The property dictionary of a marked-content sequence: inline ({@code BDC /Tag << ... >>})
     * or a reference into {@code /Resources /Properties /MarkedContent}
     * ({@code BDC /Tag /Prop0}).
     */
    private Map<String, Object> markedProperties(Map<String, Object> pageDict, Object propertyValue,
                                                 List<String> notes) {
        if (propertyValue == null) {
            return null;
        }
        Map<String, Object> inline = asMap(propertyValue);
        if (inline != null) {
            return inline;
        }
        if (propertyValue instanceof PdfName name) {
            Object resources;
            try {
                resources = document.inheritedAttribute(pageDict, "Resources");
            } catch (PdfException e) {
                return null;
            }
            Map<String, Object> resourceDict = asMap(resources);
            if (resourceDict == null) {
                return null;
            }
            Map<String, Object> properties = asMap(deref(resourceDict.get("Properties")));
            if (properties == null) {
                return null;
            }
            Map<String, Object> marked = asMap(deref(properties.get("MarkedContent")));
            if (marked == null) {
                return null;
            }
            Object target = deref(marked.get(name.value()));
            Map<String, Object> dict = asMap(target);
            if (dict == null) {
                notes.add("The marked-content property list /" + name.value() + " of a page is "
                        + PdfParser.kindOf(target) + " instead of a dictionary");
            }
            return dict;
        }
        return null;
    }

    /**
     * What the structure elements of one page claim: their {@code /MCID}s and their
     * {@code /OBJR} object numbers.
     */
    private Claims claimsFor(Root root, Object parentTree, Long structParents, int pageObjNum,
                             List<String> notes) {
        Claims claims = new Claims();
        List<Object> startNodes = new ArrayList<>();
        if (parentTree != null && structParents != null) {
            Object hit = searchNumberTree(parentTree, structParents, 0, new HashSet<>());
            if (hit != null) {
                List<Object> array = asList(hit);
                if (array != null) {
                    startNodes.addAll(array);
                } else {
                    startNodes.add(hit);
                }
                claims.fromParentTree = true;
            } else {
                notes.add("The /ParentTree has no entry for the /StructParents value "
                        + structParents + " of page object " + pageObjNum + ": the structure tree"
                        + " does not know this page, so its elements can only be found through"
                        + " their /Pg entries");
            }
        }
        if (!startNodes.isEmpty()) {
            Budget budget = new Budget(MAX_CLAIM_ELEMENTS);
            for (Object node : startNodes) {
                collectClaims(node, null, pageObjNum, budget, claims, 0, notes);
                if (budget.stopped) {
                    claims.truncated = true;
                    break;
                }
            }
            return claims;
        }
        if (root == null) {
            return claims;
        }
        // No (usable) /ParentTree: the only link left is /Pg inside the tree, so walk the tree.
        List<Object> kids = kidList(root.dict().get("K"));
        if (kids == null) {
            return claims;
        }
        Budget budget = new Budget(MAX_CLAIM_ELEMENTS);
        for (Object kid : kids) {
            collectClaims(kid, null, null, budget, claims, 0, notes);
            if (budget.stopped) {
                claims.truncated = true;
                break;
            }
        }
        return claims;
    }

    /**
     * Collects the marked-content identifiers an element (and everything below it) claims.
     * @param node the element, reference or number
     * @param inheritedPage the {@code /Pg} of the closest ancestor that has one
     * @param wantedPage the page to restrict to, or {@code null} to take everything
     */
    private void collectClaims(Object node, Integer inheritedPage, Integer wantedPage,
                              Budget budget, Claims claims, int depth, List<String> notes) {
        if (budget.exhausted()) {
            return;
        }
        if (depth > MAX_STRUCTURE_DEPTH) {
            budget.stopped = true;
            return;
        }
        Object value = deref(node);
        if (value == null) {
            return;
        }
        if (value instanceof Long mcid) {
            if (wantedPage == null || wantedPage.equals(inheritedPage)) {
                claims.mcids.add(mcid);
            }
            return;
        }
        Map<String, Object> dict = asMap(value);
        if (dict == null) {
            return;
        }
        if (PdfName.dictHas(dict, "Type", "OBJR")) {
            Integer referenced = refOf(dict.get("Obj"));
            Integer page = refOf(dict.get("Pg"));
            // An /OBJR without /PG belongs to the page of the element that holds it - and an
            // element that came from the /ParentTree of this page has no /Pg exactly when it
            // inherits the page of its entry.
            Integer effective = page != null ? page : inheritedPage;
            if (referenced != null && (wantedPage == null || effective == null
                    || wantedPage.equals(effective))) {
                claims.objects.add(referenced);
            }
            return;
        }
        budget.used++;
        Integer ownPage = refOf(dict.get("Pg"));
        Integer effective = ownPage != null ? ownPage : inheritedPage;
        boolean onWantedPage = wantedPage == null || effective == null
                || wantedPage.equals(effective);
        Object k = dict.get("K");
        if (k == null) {
            return;
        }
        if (k instanceof Long mcid) {
            if (onWantedPage) {
                claims.mcids.add(mcid);
            }
            return;
        }
        List<Object> kids = kidList(k);
        if (kids == null) {
            return;
        }
        for (Object kid : kids) {
            collectClaims(kid, effective, wantedPage, budget, claims, depth + 1, notes);
            if (budget.stopped) {
                return;
            }
        }
    }

    // ===================================================================
    // Helpers
    // ===================================================================

    /**
     * The {@code /StructTreeRoot} of the document.
     * @param notes note/warning sink (never null)
     * @return the root or {@code null} when the document is not tagged
     */
    private Root root(List<String> notes) {
        Integer catalogObjNum = refOf(document.crossReference().trailer().get("Root"));
        if (catalogObjNum == null) {
            notes.add("The trailer has no /Root, so there is no catalogue and no structure tree");
            return null;
        }
        Map<String, Object> catalog = document.dictOf(catalogObjNum);
        Object value = catalog.get("StructTreeRoot");
        if (value == null) {
            notes.add("The catalogue has no /StructTreeRoot: this document carries no tagged-PDF"
                    + " structure tree. Its content streams may still be marked -"
                    + " pdf.markedContent() shows what is there without a tree");
            return null;
        }
        Object resolved = deref(value);
        Map<String, Object> dict = asMap(resolved);
        if (dict == null) {
            notes.add("/StructTreeRoot is " + PdfParser.kindOf(resolved) + " instead of a"
                    + " dictionary - the structure tree cannot be read");
            return null;
        }
        return new Root(dict, refOf(value));
    }

    /**
     * The role map: producer-specific tag to standard role (one hop, as the specification
     * defines it).
     */
    private Map<String, String> roleMap(Map<String, Object> rootDict, List<String> notes) {
        Map<String, String> roles = new LinkedHashMap<>();
        Map<String, Object> map = asMap(deref(rootDict.get("RoleMap")));
        if (map == null) {
            if (rootDict.get("RoleMap") != null) {
                notes.add("/RoleMap is " + PdfParser.kindOf(rootDict.get("RoleMap"))
                        + " instead of a dictionary - the tags are shown unmapped");
            }
            return roles;
        }
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (entry.getValue() instanceof PdfName role) {
                roles.put("/" + entry.getKey(), role.toString());
            } else {
                noteOnce(notes, "roleMapValue", "At least one /RoleMap entry does not name a role"
                        + " (a PDF 2.0 element type dictionary): that tag is shown unmapped");
            }
        }
        return roles;
    }

    /**
     * Counts the keys of a number tree without resolving its values.
     */
    private void countNumberTreeKeys(Object node, int depth, Set<String> visited, int[] keys,
                                     boolean[] truncated) {
        if (depth > MAX_TREE_DEPTH) {
            truncated[0] = true;
            return;
        }
        Map<String, Object> dict = asMap(deref(node));
        if (dict == null) {
            truncated[0] = true;
            return;
        }
        Integer objNum = refOf(node);
        if (objNum != null && !visited.add("k" + objNum)) {
            truncated[0] = true;
            return;
        }
        List<Object> nums = asList(dict.get("Nums"));
        if (nums != null) {
            for (int i = 0; i + 1 < nums.size(); i += 2) {
                if (asLong(deref(nums.get(i))) != null) {
                    keys[0]++;
                }
            }
            return;
        }
        List<Object> kids = asList(dict.get("Kids"));
        if (kids == null) {
            truncated[0] = true;
            return;
        }
        for (Object kid : kids) {
            countNumberTreeKeys(kid, depth + 1, visited, keys, truncated);
        }
    }

    /**
     * Looks one number up in a number tree (the {@code /ParentTree} of a structure tree).
     */
    private Object searchNumberTree(Object node, long key, int depth, Set<String> visited) {
        if (node == null || depth > MAX_TREE_DEPTH) {
            return null;
        }
        Map<String, Object> dict = asMap(deref(node));
        if (dict == null) {
            return null;
        }
        Integer objNum = refOf(node);
        if (objNum != null && !visited.add("n" + objNum)) {
            return null;
        }
        List<Object> nums = asList(dict.get("Nums"));
        if (nums != null) {
            for (int i = 0; i + 1 < nums.size(); i += 2) {
                Long candidate = asLong(deref(nums.get(i)));
                if (candidate != null && candidate == key) {
                    return deref(nums.get(i + 1));
                }
            }
            return null;
        }
        List<Object> kids = asList(dict.get("Kids"));
        if (kids == null) {
            return null;
        }
        for (Object kid : kids) {
            Object hit = searchNumberTree(kid, key, depth + 1, visited);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /**
     * Adds {@code pageObjNum} and, when the page index knows it, the 1-based {@code page}.
     */
    private void putPage(Map<String, Object> item, Integer pageObjNum) {
        if (pageObjNum == null) {
            return;
        }
        item.put("pageObjNum", (long) pageObjNum);
        Integer number = pageNumber(pageObjNum);
        if (number != null) {
            item.put("page", (long) number);
        }
    }

    private Integer pageNumber(Integer pageObjNum) {
        if (pageObjNum == null) {
            return null;
        }
        return pageIndex().get(pageObjNum);
    }

    /** Page object number to page number, built once per structure view. */
    private Map<Integer, Integer> pageIndex() {
        if (pageNumbers == null) {
            List<String> notes = new ArrayList<>();
            List<PdfDocument.Page> pages = document.pages(PdfDocument.DEFAULT_MAX_PAGES, notes);
            Map<Integer, Integer> index = new LinkedHashMap<>();
            for (PdfDocument.Page page : pages) {
                if (page.objNum() >= 0) {
                    index.putIfAbsent(page.objNum(), page.page());
                }
            }
            pageIndexTruncated = pages.size() >= PdfDocument.DEFAULT_MAX_PAGES;
            pageNumbers = index;
        }
        return pageNumbers;
    }

    private static int pageIndexLimit() {
        return PdfDocument.DEFAULT_MAX_PAGES;
    }

    /** Follows one reference (a plain value is returned unchanged). */
    private Object deref(Object value) {
        if (!(value instanceof PdfReference reference)) {
            return value;
        }
        try {
            return document.dereference(reference);
        } catch (PdfException e) {
            return null;
        }
    }

    /** Adds an explanation at most once per instance and key. */
    private void noteOnce(List<String> notes, String key, String text) {
        if (noted.add(key)) {
            notes.add(text);
        }
    }
}
