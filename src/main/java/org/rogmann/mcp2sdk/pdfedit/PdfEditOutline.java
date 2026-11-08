package org.rogmann.mcp2sdk.pdfedit;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code doc.outline}: the bookmark tree of a document, set or pruned.
 * <p>
 * The tree is built through PDFBox' outline API ({@link PDOutlineItem}/{@link PDOutlineNode}),
 * which maintains the four pointers a bookmark list needs - {@code /Parent}, {@code /First} and
 * {@code /Last}, {@code /Next} and {@code /Prev}, and {@code /Count} with its sign for "collapsed".
 * Writing those by hand is the part of a PDF that looks trivial and is not: one missing
 * {@code /Prev} makes a viewer show half the tree.
 * </p>
 * <p>
 * {@code prune} is the counterpart for a document that lost pages: it removes every entry whose
 * destination cannot be resolved any more. An entry that has no destination at all (a separator, a
 * URI action) is <em>not</em> touched - it never pointed at a page.
 * </p>
 */
final class PdfEditOutline {

    /** Keys of one bookmark item. */
    private static final Set<String> ITEM_KEYS = Set.of("title", "page", "y", "children", "open");

    private PdfEditOutline() {
        // Utility class
    }

    /**
     * Sets or prunes the bookmark tree.
     * @param ctx the call context
     * @param op the op
     */
    static void outline(PdfEditContext ctx, PdfEditOp op) {
        op.allow("set", "prune");
        boolean prune = op.bool("prune", false);
        List<?> items = op.params().get("set") == null ? null : op.list("set");
        if (prune && items != null) {
            throw PdfEditException.usage(op.where() + ": 'set' and prune:true are combined",
                    "either replace the bookmarks with set:[…] or remove the broken ones with"
                            + " prune:true");
        }
        if (prune) {
            prune(ctx, op);
            return;
        }
        if (items == null) {
            throw PdfEditException.usage(op.where() + ": neither 'set' nor prune given",
                    "doc.outline takes set:[{title:'Kapitel 1', page:1}, …] or prune:true");
        }
        PDDocument document = ctx.document();
        PDDocumentCatalog catalog = document.getDocumentCatalog();
        boolean replaced = catalog.getDocumentOutline() != null;
        PDDocumentOutline outline = new PDDocumentOutline();
        List<String> titles = new ArrayList<>();
        int count = addItems(ctx, outline, items, titles, 0);
        catalog.setDocumentOutline(outline);
        catalog.getCOSObject().setNeedToBeUpdated(true);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("items", (long) count);
        details.put("titles", new ArrayList<Object>(titles));
        ctx.changed(op, (replaced ? "bookmark tree replaced (" : "bookmark tree set (") + count
                + " item(s))", details);
        if (replaced) {
            ctx.note("doc.outline replaced the existing bookmark tree - the old entries are gone"
                    + " (prune:true keeps the tree and removes only the entries without a page)");
        }
    }

    /**
     * Builds the items of one level.
     * @param ctx the call context
     * @param parent the node the items are appended to
     * @param items the item maps
     * @param titles collects the titles for the answer
     * @param depth the nesting depth
     * @return the number of items created (including children)
     */
    private static int addItems(PdfEditContext ctx, PDOutlineNode parent, List<?> items,
                                List<String> titles, int depth) {
        if (depth > PdfEditPages.MAX_OUTLINE_DEPTH) {
            throw PdfEditException.usage("doc.outline: more than "
                    + PdfEditPages.MAX_OUTLINE_DEPTH + " levels of bookmarks",
                    "a bookmark tree that deep is a data structure, not a table of contents");
        }
        int count = 0;
        for (Object value : items) {
            if (!(value instanceof Map<?, ?> map)) {
                throw PdfEditException.usage("doc.outline: an item is "
                        + PdfEditOp.describe(value),
                        "each bookmark is an object: {title:'Kapitel 1', page:1, y:700,"
                                + " children:[…], open:true}");
            }
            Map<String, Object> item = stringKeys(map);
            Set<String> unknown = new TreeSet<>(item.keySet());
            unknown.removeAll(ITEM_KEYS);
            if (!unknown.isEmpty()) {
                throw PdfEditException.usage("doc.outline: unknown item key(s) " + unknown,
                        "an item has " + new TreeSet<>(ITEM_KEYS));
            }
            Object title = item.get("title");
            if (!(title instanceof String text) || text.isBlank()) {
                throw PdfEditException.usage("doc.outline: an item has no title",
                        "each bookmark needs a title, e.g. {title:'Kapitel 1', page:1}");
            }
            Object pageValue = item.get("page");
            if (!(pageValue instanceof Number number)) {
                throw PdfEditException.usage("doc.outline: '" + text + "' has no page",
                        "a bookmark names the page it leads to (1-based, as pdf.pages() prints"
                                + " it)");
            }
            int pageNum = number.intValue();
            PDPage page = PdfEditPages.requirePage(ctx, pageNum);
            PDOutlineItem entry = new PDOutlineItem();
            entry.setTitle(text);
            entry.setDestination(destination(page, item.get("y")));
            parent.addLast(entry);
            titles.add(text);
            count++;
            if (count > PdfEditLimits.MAX_OUTLINE_ITEMS) {
                throw new PdfEditException("limit", "more than " + PdfEditLimits.MAX_OUTLINE_ITEMS
                        + " bookmarks in one call",
                        "a document with more entries needs them built in several calls, or a"
                                + " table of contents page instead");
            }
            List<?> children = item.get("children") == null ? null
                    : item.get("children") instanceof List<?> list ? list
                    : failNotAList(text, item.get("children"));
            if (children != null && !children.isEmpty()) {
                count += addItems(ctx, entry, children, titles, depth + 1);
            }
            Object open = item.get("open");
            if (open instanceof Boolean flag && !flag) {
                entry.closeNode();
            } else if (open != null && !(open instanceof Boolean)) {
                throw PdfEditException.usage("doc.outline: 'open' of '" + text + "' is "
                        + PdfEditOp.describe(open), "open is true (the default) or false");
            }
        }
        return count;
    }

    private static List<?> failNotAList(String title, Object value) {
        throw PdfEditException.usage("doc.outline: 'children' of '" + title + "' is "
                + PdfEditOp.describe(value), "children is an array of bookmarks");
    }

    /**
     * The destination of a bookmark: the page, and the {@code y} on it when named.
     * @param page the page
     * @param y the y coordinate in default user space, or {@code null}
     * @return the destination
     */
    private static PDPageXYZDestination destination(PDPage page, Object y) {
        PDPageXYZDestination destination = new PDPageXYZDestination();
        destination.setPage(page);
        if (y == null) {
            return destination;
        }
        if (!(y instanceof Number number)) {
            throw PdfEditException.usage("doc.outline: y is " + PdfEditOp.describe(y),
                    "y is a coordinate in default user space (points, y = 0 at the bottom) - a"
                            + " bookmark without y jumps to the top of the page");
        }
        destination.setTop((int) Math.round(number.doubleValue()));
        return destination;
    }

    private static Map<String, Object> stringKeys(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    // -------------------------------------------------------------------
    // prune
    // -------------------------------------------------------------------

    /**
     * Removes every bookmark whose destination does not lead to a page of this document.
     * <p>
     * The same walk as {@code prune}, without the op around it: {@code doc.merge} and
     * {@code page.import} use it on a source document whose pages were reduced to a selection, so
     * that no bookmark is copied that would point outside the result.
     * </p>
     * @param document the document (its bookmark tree is modified)
     * @param removed collects one sentence per removed entry
     * @return the number of removed entries
     */
    static int pruneDestinations(PDDocument document, List<String> removed) {
        PDDocumentOutline outline = document.getDocumentCatalog().getDocumentOutline();
        if (outline == null) {
            return 0;
        }
        Set<COSDictionary> pageDicts = Collections.newSetFromMap(new IdentityHashMap<>());
        for (PDPage page : document.getPages()) {
            pageDicts.add(page.getCOSObject());
        }
        int before = removed.size();
        pruneNode(document, outline, outline.getCOSObject(), pageDicts, removed, 0);
        if (removed.size() > before) {
            document.getDocumentCatalog().getCOSObject().setNeedToBeUpdated(true);
        }
        return removed.size() - before;
    }

    /**
     * Removes every bookmark whose destination does not lead to a page of this document.
     * @param document the document (its bookmark tree is modified)
     * @return the number of removed entries
     */
    static int pruneDestinations(PDDocument document) {
        return pruneDestinations(document, new ArrayList<>());
    }

    /**
     * Removes the bookmarks whose destination does not lead to a page of this document.
     * @param ctx the call context
     * @param op the op
     */
    private static void prune(PdfEditContext ctx, PdfEditOp op) {
        PDDocument document = ctx.document();
        PDDocumentCatalog catalog = document.getDocumentCatalog();
        PDDocumentOutline outline = catalog.getDocumentOutline();
        if (outline == null) {
            ctx.changed(op, "bookmark tree pruned (there is none)",
                    new LinkedHashMap<>());
            return;
        }
        List<String> removed = new ArrayList<>();
        int count = pruneDestinations(document, removed);
        if (count > 0) {
            PdfEditPages.dropEmptyOutline(ctx);
            catalog.getCOSObject().setNeedToBeUpdated(true);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("removed", (long) count);
        details.put("bookmarks", new ArrayList<Object>(removed));
        ctx.changed(op, "bookmark tree pruned: " + count + " entr(y/ies) without a page",
                details);
        if (count > 0) {
            ctx.note("doc.outline prune removed " + count + " bookmark(s) whose"
                    + " destination leads nowhere: " + String.join("; ", removed));
        } else {
            ctx.note("doc.outline prune found no bookmark whose destination leads nowhere");
        }
    }

    /**
     * Walks one level and removes the items that point nowhere.
     * @param document the document
     * @param node the node whose children are walked
     * @param nodeDict the node's dictionary
     * @param pageDicts the page dictionaries of the document
     * @param removed collects the titles of the removed items
     * @param depth the nesting depth
     */
    private static void pruneNode(PDDocument document, PDOutlineNode node,
                                  COSDictionary nodeDict, Set<COSDictionary> pageDicts,
                                  List<String> removed, int depth) {
        if (depth > PdfEditPages.MAX_OUTLINE_DEPTH) {
            return;
        }
        List<PDOutlineItem> children = new ArrayList<>();
        for (PDOutlineItem child : node.children()) {
            children.add(child);
        }
        for (PDOutlineItem item : children) {
            if (!pointsNowhere(document, item, pageDicts)) {
                pruneNode(document, item, item.getCOSObject(), pageDicts, removed, depth + 1);
                continue;
            }
            int kids = PdfEditPages.countItems(item, 0);
            PDPage target = PdfEditPages.outlineDestination(document, item);
            removed.add("\"" + item.getTitle() + "\" → "
                    + (target == null ? "no resolvable destination"
                    : "a page that is not in the page tree")
                    + (kids > 0 ? " (" + kids + " child item(s) go with it)" : ""));
            // The children are only reachable through this item, so they go with it.
            PdfEditPages.removeOutlineItem(item.getCOSObject(), nodeDict);
        }
    }

    /**
     * Tests whether an item has a destination that leads to a page outside the document.
     * @param document the document
     * @param item the bookmark
     * @param pageDicts the page dictionaries of the document
     * @return true when the item points at a page that is not there any more
     */
    private static boolean pointsNowhere(PDDocument document, PDOutlineItem item,
                                         Set<COSDictionary> pageDicts) {
        boolean hasDestination = item.getCOSObject().getDictionaryObject(COSName.DEST) != null
                || item.getAction() instanceof PDActionGoTo;
        if (!hasDestination) {
            // A separator, a URI action, a JavaScript action: it never pointed at a page.
            return false;
        }
        PDPage target = PdfEditPages.outlineDestination(document, item);
        return target == null || !pageDicts.contains(target.getCOSObject());
    }
}
