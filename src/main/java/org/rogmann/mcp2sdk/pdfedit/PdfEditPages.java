package org.rogmann.mcp2sdk.pdfedit;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDDestinationNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageTree;
import org.apache.pdfbox.pdmodel.common.PDDestinationOrAction;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Page operations: {@code page.add}, {@code page.remove}, {@code page.move} - including the
 * reference sweep that says what points at a page that is about to vanish.
 * <p>
 * Removing pages is not "deleting a few objects": a bookmark, a link, a named destination, a form
 * widget and the open action may all lead to that page, and the result would be a document whose
 * navigation leads nowhere. The {@code refs} parameter decides what happens to those pointers:
 * </p>
 * <ul>
 *   <li>{@code prune} (the default) removes them and counts what was removed - the answer for
 *       "this extract should stand on its own";</li>
 *   <li>{@code keep} leaves them and reports them as {@code orphans} - the answer for "only the
 *       pages may change, everything else must stay as it is";</li>
 *   <li>{@code error} refuses the call when anything points at a removed page.</li>
 * </ul>
 * <p>
 * The sweep reads the document <em>before</em> the pages go: it resolves destinations the way a
 * viewer does (a direct page reference, a named destination, a {@code /GoTo} action) and compares
 * the page dictionary it finds with the dictionaries that are about to be removed. The tag
 * structure ({@code /StructTreeRoot}) is deliberately not touched - marked content that loses its
 * page is reported, not repaired.
 * </p>
 */
final class PdfEditPages {

    private static final Logger LOGGER = LoggerFactory.getLogger(PdfEditPages.class);

    /** Deepest bookmark nesting the sweep walks (a malformed file could loop). */
    static final int MAX_OUTLINE_DEPTH = 200;

    /** Most siblings the sweep walks in one sibling list. */
    private static final int MAX_OUTLINE_SIBLINGS = 100_000;

    /** Page sizes by name, lowercase; the values are the ISO/US sizes of PDFBox. */
    private static final Map<String, PDRectangle> SIZES = sizes();

    private PdfEditPages() {
        // Utility class
    }

    private static Map<String, PDRectangle> sizes() {
        Map<String, PDRectangle> map = new LinkedHashMap<>();
        map.put("a0", PDRectangle.A0);
        map.put("a1", PDRectangle.A1);
        map.put("a2", PDRectangle.A2);
        map.put("a3", PDRectangle.A3);
        map.put("a4", PDRectangle.A4);
        map.put("a5", PDRectangle.A5);
        map.put("a6", PDRectangle.A6);
        map.put("letter", PDRectangle.LETTER);
        map.put("legal", PDRectangle.LEGAL);
        map.put("tabloid", PDRectangle.TABLOID);
        return Collections.unmodifiableMap(map);
    }

    // -------------------------------------------------------------------
    // page.add
    // -------------------------------------------------------------------

    /**
     * Adds a page at a position.
     * @param ctx the call context
     * @param op the op
     */
    static void add(PdfEditContext ctx, PdfEditOp op) {
        op.allow("size", "width", "height", "unit", "at", "before", "after", "rotation");
        ctx.countAddedPage();
        PDRectangle rect = rectangle(op);
        PDPage page = new PDPage(rect);
        Integer rotation = op.intOrNull("rotation");
        if (rotation != null) {
            if (rotation % 90 != 0) {
                throw PdfEditException.usage(op.where() + ": rotation is " + rotation,
                        "rotation is 0, 90, 180 or 270 (degrees clockwise)");
            }
            page.setRotation(((rotation % 360) + 360) % 360);
        }
        PDPageTree pages = ctx.document().getPages();
        int count = pages.getCount();
        int target = position(op, count);
        if (target > count) {
            pages.add(page);
        } else {
            pages.insertBefore(page, pages.get(target - 1));
        }
        pages.getCOSObject().setNeedToBeUpdated(true);
        ctx.markPageTreeChanged();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("page", (long) target);
        details.put("objNum", null);
        details.put("widthPt", (double) rect.getWidth());
        details.put("heightPt", (double) rect.getHeight());
        if (rotation != null) {
            details.put("rotation", (long) page.getRotation());
        }
        ctx.changed(op, "page added (" + Math.round(rect.getWidth()) + " x "
                + Math.round(rect.getHeight()) + " pt) at position " + target, details);
    }

    /**
     * Reads the page size: a named size ({@code 'A4'}) or width and height with an optional unit.
     * @param op the op
     * @return the rectangle
     */
    private static PDRectangle rectangle(PdfEditOp op) {
        String size = op.stringOrNull("size");
        Double width = op.doubleOrNull("width");
        Double height = op.doubleOrNull("height");
        if (size != null) {
            if (width != null || height != null) {
                throw PdfEditException.usage(op.where() + ": 'size' and width/height are both given",
                        "give either size:'A4' or width and height");
            }
            PDRectangle rect = SIZES.get(size.trim().toLowerCase(Locale.ROOT));
            if (rect == null) {
                throw PdfEditException.usage(op.where() + ": unknown page size '" + size + "'",
                        "named sizes are " + new TreeSet<>(SIZES.keySet())
                                + " (or give width and height in points)");
            }
            return rect;
        }
        if (width == null || height == null) {
            throw PdfEditException.usage(op.where() + ": page size missing",
                    "give size:'A4' or width and height (points, or with unit:'mm'|'cm'|'in')");
        }
        double factor = PdfEditGeometry.unitFactor(op);
        float w = (float) (width * factor);
        float h = (float) (height * factor);
        if (w <= 0 || h <= 0) {
            throw PdfEditException.usage(op.where() + ": page size " + width + " x " + height,
                    "width and height are positive numbers");
        }
        return new PDRectangle(w, h);
    }

    /**
     * Reads the target position of {@code page.add} as a 1-based insert position.
     * @param op the op
     * @param count the current number of pages
     * @return the 1-based target position (count + 1 means "append")
     */
    private static int position(PdfEditOp op, int count) {
        Integer at = op.intOrNull("at");
        Integer before = op.intOrNull("before");
        Integer after = op.intOrNull("after");
        int given = (at != null ? 1 : 0) + (before != null ? 1 : 0) + (after != null ? 1 : 0);
        if (given > 1) {
            throw PdfEditException.usage(op.where() + ": at, before and after are combined",
                    "give exactly one of at, before, after");
        }
        if (at != null) {
            if (at < 1 || at > count + 1) {
                throw new PdfEditException("out-of-range", op.where() + ": at is " + at
                        + " (the document has " + count + " pages)",
                        "at is between 1 and " + (count + 1) + " - pdf.pages() shows the numbers");
            }
            return at;
        }
        if (before != null) {
            requirePageInRange(op, before, count);
            return before;
        }
        if (after != null) {
            requirePageInRange(op, after, count);
            return after + 1;
        }
        return count + 1;
    }

    private static void requirePageInRange(PdfEditOp op, int page, int count) {
        if (page < 1 || page > count) {
            throw new PdfEditException("out-of-range", op.where() + ": page " + page
                    + " (the document has " + count + " pages)",
                    "pages are counted from 1 - pdf.pages() shows the numbers");
        }
    }

    // -------------------------------------------------------------------
    // page.move
    // -------------------------------------------------------------------

    /**
     * Moves a page to another position.
     * @param ctx the call context
     * @param op the op
     */
    static void move(PdfEditContext ctx, PdfEditOp op) {
        op.allow("page", "at", "before", "after");
        int pageNum = op.page("page");
        PDPageTree pages = ctx.document().getPages();
        int count = pages.getCount();
        requirePageInRange(op, pageNum, count);
        Integer at = op.intOrNull("at");
        Integer before = op.intOrNull("before");
        Integer after = op.intOrNull("after");
        if ((at != null ? 1 : 0) + (before != null ? 1 : 0) + (after != null ? 1 : 0) != 1) {
            throw PdfEditException.usage(op.where() + ": give exactly one of at, before, after",
                    "page.move moves one page, e.g. {op:'page.move', page:3, at:1}");
        }
        int target;
        if (at != null) {
            requirePageInRange(op, at, count);
            target = at;
        } else if (before != null) {
            requirePageInRange(op, before, count);
            target = before;
        } else {
            requirePageInRange(op, after, count);
            target = after + 1;
        }
        PDPage page = pages.get(pageNum - 1);
        pages.remove(pageNum - 1);
        if (target > count - 1) {
            pages.add(page);
            target = count;
        } else {
            pages.insertBefore(page, pages.get(target - 1));
        }
        pages.getCOSObject().setNeedToBeUpdated(true);
        ctx.markPageTreeChanged();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("page", (long) pageNum);
        details.put("at", (long) target);
        ctx.changed(op, "page moved from " + pageNum + " to " + target, details);
    }

    // -------------------------------------------------------------------
    // page.set
    // -------------------------------------------------------------------

    /**
     * Sets page attributes: the boxes, the rotation and the user unit.
     * <p>
     * These are the four entries that decide where a page's coordinates stand (§3.4): changing one
     * of them moves everything that is already on the page. That is not an error - "give this page
     * a 2 cm margin" is a legitimate request - but the answer says it, because the next
     * {@code text.put} in page space measures against the new frame.
     * </p>
     * @param ctx the call context
     * @param op the op
     */
    static void set(PdfEditContext ctx, PdfEditOp op) {
        op.allow("page", "mediaBox", "cropBox", "rotation", "userUnit", "unit");
        int pageNum = op.page("page");
        PDPage page = requirePage(ctx, pageNum);
        double factor = PdfEditGeometry.unitFactor(op);
        List<String> written = new ArrayList<>();
        PDRectangle media = rectangle(op, "mediaBox", factor, pageNum);
        if (media != null) {
            page.setMediaBox(media);
            written.add("/MediaBox");
        }
        if (op.params().containsKey("cropBox")) {
            if (op.params().get("cropBox") == null) {
                page.setCropBox(null);
                written.add("/CropBox (removed)");
            } else {
                PDRectangle crop = rectangle(op, "cropBox", factor, pageNum);
                page.setCropBox(crop);
                written.add("/CropBox");
            }
        }
        Integer rotation = op.intOrNull("rotation");
        if (rotation != null) {
            if (rotation % 90 != 0) {
                throw PdfEditException.usage(op.where() + ": rotation is " + rotation,
                        "rotation is 0, 90, 180 or 270 degrees clockwise");
            }
            page.setRotation(((rotation % 360) + 360) % 360);
            written.add("/Rotate " + page.getRotation());
        }
        Double userUnit = op.doubleOrNull("userUnit");
        if (userUnit != null) {
            if (userUnit <= 0 || userUnit > 75_000) {
                throw PdfEditException.usage(op.where() + ": userUnit is " + userUnit,
                        "userUnit is the length of a unit in multiples of 1/72 inch: a positive"
                                + " number (75.000 is the maximum of the specification)");
            }
            page.setUserUnit(userUnit.floatValue());
            written.add("/UserUnit " + PdfEditGeometry.round(userUnit));
        }
        if (written.isEmpty()) {
            throw PdfEditException.usage(op.where() + ": no page attribute given",
                    "page.set writes mediaBox, cropBox, rotation or userUnit - e.g."
                            + " {op:'page.set', page:1, rotation:90}");
        }
        page.getCOSObject().setNeedToBeUpdated(true);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("page", (long) pageNum);
        details.put("objNum", PdfEditRaw.pageObjNum(page));
        details.put("fields", new ArrayList<Object>(written));
        ctx.changed(op, "page attributes set: " + String.join(", ", written), details);
        ctx.note("page.set changed the frame of page " + pageNum + ": content and annotations"
                + " keep their user-space coordinates, so a box or a rotation moves what is"
                + " already there (pdf.placement() shows the new frame)");
    }

    /**
     * Reads a page box ({@code mediaBox} or {@code cropBox}) as a rectangle in points.
     * @param op the op
     * @param key the parameter name
     * @param factor points per unit
     * @param pageNum the page number (for the message)
     * @return the rectangle, or {@code null} when the op does not name it
     */
    private static PDRectangle rectangle(PdfEditOp op, String key, double factor, int pageNum) {
        if (!op.params().containsKey(key)) {
            return null;
        }
        List<?> values = op.list(key);
        if (values.size() != 4) {
            throw PdfEditException.usage(op.where() + ": " + key + " has " + values.size()
                    + " value(s)", key + " is [llx, lly, urx, ury] - four numbers in the op's unit");
        }
        float[] four = new float[4];
        for (int i = 0; i < 4; i++) {
            Object value = values.get(i);
            if (!(value instanceof Number number)) {
                throw PdfEditException.usage(op.where() + ": " + key + "[" + i + "] is "
                        + PdfEditOp.describe(value), key + " holds four numbers");
            }
            four[i] = (float) (number.doubleValue() * factor);
        }
        float width = Math.abs(four[2] - four[0]);
        float height = Math.abs(four[3] - four[1]);
        if (width <= 0 || height <= 0) {
            throw PdfEditException.usage(op.where() + ": " + key + " of page " + pageNum
                    + " has no extent (" + width + " × " + height + ")",
                    "a page box needs two different corners - pdf.pages() shows the box the page"
                            + " has today");
        }
        return new PDRectangle(four[0], four[1], width, height);
    }

    // -------------------------------------------------------------------
    // page.remove
    // -------------------------------------------------------------------

    /**
     * Removes pages, with the requested handling of pointers that lead to them.
     * @param ctx the call context
     * @param op the op
     * @return the answer block of this op ({@code removed}, {@code pruned}, {@code orphans})
     */
    static Map<String, Object> remove(PdfEditContext ctx, PdfEditOp op) {
        op.allow("page", "pages", "refs");
        String refs = refs(op);
        PDPageTree pages = ctx.document().getPages();
        int count = pages.getCount();
        List<Integer> numbers = numbers(op, count);
        if (numbers.size() >= count) {
            throw new PdfEditException("unsupported", op.where() + ": all " + count
                    + " pages would be removed",
                    "a PDF without pages is not a document - keep at least one page");
        }
        List<PDPage> doomed = new ArrayList<>();
        for (int pageNum : numbers) {
            PDPage page = pages.get(pageNum - 1);
            doomed.add(page);
            ctx.markRemoved(page.getCOSObject());
        }
        Sweep sweep = sweep(ctx, doomed);
        if ("error".equals(refs) && !sweep.orphans.isEmpty()) {
            throw new PdfEditException("orphans", op.where() + ": " + sweep.orphans.size()
                    + " reference(s) point at the pages to remove: "
                    + String.join("; ", sweep.orphans),
                    "use refs:'prune' to remove those references together with the pages, or"
                            + " refs:'keep' to leave them (the answer then lists them)");
        }
        if ("prune".equals(refs)) {
            sweep.prune(ctx);
        }
        List<Integer> descending = new ArrayList<>(numbers);
        descending.sort(Collections.reverseOrder());
        for (int pageNum : descending) {
            pages.remove(pageNum - 1);
        }
        pages.getCOSObject().setNeedToBeUpdated(true);
        ctx.markPageTreeChanged();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("pages", new ArrayList<Object>(numbers));
        details.put("removed", (long) numbers.size());
        details.put("refs", refs);
        if ("prune".equals(refs)) {
            details.put("pruned", sweep.counts());
        }
        if (!sweep.orphans.isEmpty()) {
            details.put("orphans", new ArrayList<Object>(sweep.orphans));
        }
        ctx.changed(op, numbers.size() + " page(s) removed: " + numbers, details);
        ctx.note("pages " + numbers + " are gone from the page tree; a full save drops their"
                + " objects, while an incremental save would keep them readable");
        if (sweep.tagged) {
            ctx.note("the document is tagged: removed pages may leave entries in the structure"
                    + " tree (/StructTreeRoot) - pdfedit reports that and does not repair it");
        }
        return details;
    }

    /**
     * Reads the {@code refs} parameter: {@code prune} (default), {@code keep} or {@code error}.
     * @param op the op
     * @return the normalised value
     */
    private static String refs(PdfEditOp op) {
        String refs = op.stringOrNull("refs");
        if (refs == null) {
            return "prune";
        }
        String value = refs.trim().toLowerCase(Locale.ROOT);
        if (value.equals("prune") || value.equals("keep") || value.equals("error")) {
            return value;
        }
        throw PdfEditException.usage(op.where() + ": refs reads '" + refs + "'",
                "refs is 'prune' (default: remove the references with the pages), 'keep'"
                        + " (leave them and list them as orphans) or 'error' (refuse)");
    }

    /**
     * Reads {@code page} or {@code pages} and returns the numbers in ascending order.
     * @param op the op
     * @param count the number of pages
     * @return the page numbers
     */
    private static List<Integer> numbers(PdfEditOp op, int count) {
        Set<Integer> numbers = new TreeSet<>();
        if (op.params().get("page") != null) {
            numbers.add(op.page("page"));
        }
        if (op.params().get("pages") != null) {
            for (Object item : op.list("pages")) {
                if (!(item instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())) {
                    throw PdfEditException.usage(op.where() + ": pages contains "
                            + PdfEditOp.describe(item),
                            "pages is a list of page numbers, e.g. [3, 4]");
                }
                numbers.add(n.intValue());
            }
        }
        if (numbers.isEmpty()) {
            throw PdfEditException.usage(op.where() + ": no page given",
                    "give page:3 or pages:[3, 4, 5]");
        }
        for (int pageNum : numbers) {
            if (pageNum < 1 || pageNum > count) {
                throw new PdfEditException("out-of-range", op.where() + ": page " + pageNum
                        + " (the document has " + count + " pages)",
                        "pages are counted from 1 - pdf.pages() shows the numbers");
            }
        }
        return new ArrayList<>(numbers);
    }

    // -------------------------------------------------------------------
    // The reference sweep
    // -------------------------------------------------------------------

    /**
     * Everything that points at the pages that are about to be removed.
     */
    private static final class Sweep {

        /** One sentence per pointer, as the answer prints it. */
        private final List<String> orphans = new ArrayList<>();

        /** Counted per kind: {@code outlineItems}, {@code links}, {@code dests}, {@code fields}. */
        private final Map<String, Long> counts = new LinkedHashMap<>();

        /** How the page labels were handled: {@code kept}, {@code removed} or {@code null}. */
        private String labels;

        /** Whether the document carries a structure tree. */
        private boolean tagged;

        /** The link annotations to remove, per page. */
        private final Map<PDPage, List<PDAnnotation>> links = new LinkedHashMap<>();

        /** The outline items to remove → the node whose child list holds them. */
        private final Map<COSDictionary, COSDictionary> outlineItems = new LinkedHashMap<>();

        /** Named destinations to remove: name-tree node dictionary → names. */
        private final Map<COSDictionary, List<String>> namedDests = new LinkedHashMap<>();

        /** Top-level fields of {@code /AcroForm /Fields} that belong to a removed page. */
        private final List<COSDictionary> fields = new ArrayList<>();

        /** Whether the open action has to go. */
        private boolean openAction;

        void count(String kind) {
            counts.compute(kind, (k, value) -> value == null ? 1L : value + 1L);
        }

        Map<String, Object> counts() {
            Map<String, Object> map = new LinkedHashMap<>(counts);
            if (labels != null) {
                map.put("labels", labels);
            }
            return map;
        }

        /**
         * Removes everything the sweep found (called before the pages leave the tree).
         * @param ctx the call context
         */
        void prune(PdfEditContext ctx) {
            for (Map.Entry<PDPage, List<PDAnnotation>> entry : links.entrySet()) {
                try {
                    entry.getKey().getAnnotations().removeAll(entry.getValue());
                } catch (IOException e) {
                    LOGGER.warn("pdfedit: a link could not be removed", e);
                }
                entry.getKey().getCOSObject().setNeedToBeUpdated(true);
            }
            for (Map.Entry<COSDictionary, COSDictionary> entry : outlineItems.entrySet()) {
                removeOutlineItem(entry.getKey(), entry.getValue());
            }
            if (!outlineItems.isEmpty()) {
                dropEmptyOutline(ctx);
            }
            for (Map.Entry<COSDictionary, List<String>> entry : namedDests.entrySet()) {
                removeNameTreeEntries(entry.getKey(), entry.getValue());
            }
            COSDictionary formDict = ctx.document().getDocumentCatalog().getCOSObject()
                    .getCOSDictionary(COSName.ACRO_FORM);
            if (formDict != null && !fields.isEmpty()) {
                COSArray formFields = formDict.getCOSArray(COSName.FIELDS);
                if (formFields != null) {
                    Set<COSDictionary> doomedFields =
                            Collections.newSetFromMap(new IdentityHashMap<>());
                    doomedFields.addAll(fields);
                    for (int i = formFields.size() - 1; i >= 0; i--) {
                        if (formFields.getObject(i) instanceof COSDictionary dict
                                && doomedFields.contains(dict)) {
                            formFields.remove(i);
                        }
                    }
                    formDict.setNeedToBeUpdated(true);
                }
            }
            if (openAction) {
                ctx.document().getDocumentCatalog().setOpenAction((PDDestinationOrAction) null);
            }
            if ("removed".equals(labels)) {
                ctx.document().getDocumentCatalog().getCOSObject().removeItem(COSName.PAGE_LABELS);
            }
            if (!orphans.isEmpty()) {
                ctx.note("prune removed " + orphans.size() + " reference(s) to the removed pages: "
                        + String.join("; ", orphans));
            }
            LOGGER.debug("pdfedit: pruned {} references", orphans.size());
        }
    }

    /**
     * Finds every pointer to the doomed pages.
     * @param ctx the call context
     * @param doomed the pages that go
     * @return the sweep
     */
    private static Sweep sweep(PdfEditContext ctx, List<PDPage> doomed) {
        Sweep sweep = new Sweep();
        Set<COSDictionary> doomedDicts = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<COSDictionary, Integer> pageNumbers = new IdentityHashMap<>();
        Set<COSDictionary> doomedAnnots = Collections.newSetFromMap(new IdentityHashMap<>());
        PDDocument document = ctx.document();
        for (PDPage page : doomed) {
            doomedDicts.add(page.getCOSObject());
            int pageNum = pageIndex(document, page);
            pageNumbers.put(page.getCOSObject(), pageNum);
            try {
                for (PDAnnotation annotation : page.getAnnotations()) {
                    doomedAnnots.add(annotation.getCOSObject());
                }
            } catch (IOException e) {
                ctx.note("the annotations of page " + pageNum + " could not be read: "
                        + e.getMessage());
            }
        }
        PDDocumentCatalog catalog = document.getDocumentCatalog();
        sweep.tagged = catalog.getCOSObject().getDictionaryObject(
                COSName.getPDFName("StructTreeRoot")) != null;
        sweepOutlines(document, catalog, doomedDicts, pageNumbers, sweep);
        sweepLinks(document, doomedDicts, pageNumbers, sweep);
        sweepDestinations(document, catalog, doomedDicts, pageNumbers, sweep);
        sweepFields(catalog, doomedAnnots, sweep);
        sweepOpenAction(document, catalog, doomedDicts, pageNumbers, sweep);
        sweepLabels(catalog, sweep);
        return sweep;
    }

    /**
     * Walks the bookmarks and collects the items whose destination is a removed page.
     */
    private static void sweepOutlines(PDDocument document, PDDocumentCatalog catalog,
                                      Set<COSDictionary> doomedDicts,
                                      Map<COSDictionary, Integer> pageNumbers, Sweep sweep) {
        PDDocumentOutline outline = catalog.getDocumentOutline();
        if (outline != null) {
            collectOutlineItems(document, outline, doomedDicts, pageNumbers, sweep, 0);
        }
    }

    private static void collectOutlineItems(PDDocument document, PDOutlineNode node,
                                            Set<COSDictionary> doomedDicts,
                                            Map<COSDictionary, Integer> pageNumbers,
                                            Sweep sweep, int depth) {
        if (depth > MAX_OUTLINE_DEPTH) {
            return;
        }
        int siblings = 0;
        for (PDOutlineItem item : node.children()) {
            if (++siblings > MAX_OUTLINE_SIBLINGS) {
                break;
            }
            PDPage target = outlineDestination(document, item);
            if (target != null && doomedDicts.contains(target.getCOSObject())) {
                int children = countItems(item, 0);
                sweep.count("outlineItems");
                sweep.orphans.add("bookmark \"" + item.getTitle() + "\" → page "
                        + pageNumbers.get(target.getCOSObject())
                        + (children > 0 ? " (" + children + " child item(s) go with it)" : ""));
                sweep.outlineItems.put(item.getCOSObject(), node.getCOSObject());
            } else {
                collectOutlineItems(document, item, doomedDicts, pageNumbers, sweep, depth + 1);
            }
        }
    }

    static int countItems(PDOutlineItem item, int depth) {
        if (depth > MAX_OUTLINE_DEPTH) {
            return 0;
        }
        int count = 0;
        int siblings = 0;
        for (PDOutlineItem child : item.children()) {
            if (++siblings > MAX_OUTLINE_SIBLINGS) {
                break;
            }
            count += 1 + countItems(child, depth + 1);
        }
        return count;
    }

    /**
     * Removes a {@code /Outlines} dictionary that has no items left.
     * <p>
     * Without this the catalogue would keep an empty bookmark tree, and {@code pdf.outlines()}
     * would answer {@code present:true, count:0} - a caller who removed the last bookmark would
     * have to read the count to learn that nothing is left.
     * </p>
     * @param ctx the call context
     */
    static void dropEmptyOutline(PdfEditContext ctx) {
        COSDictionary catalog = ctx.document().getDocumentCatalog().getCOSObject();
        COSDictionary outline = catalog.getCOSDictionary(COSName.getPDFName("Outlines"));
        if (outline == null || outline.getDictionaryObject(COSName.FIRST) != null) {
            return;
        }
        catalog.removeItem(COSName.getPDFName("Outlines"));
        catalog.setNeedToBeUpdated(true);
        ctx.note("the last bookmark was removed, so the empty /Outlines tree is gone too");
    }

    /**
     * Removes an outline item from the sibling list of the given node and keeps the ancestors'
     * {@code /Count} in step. The item's children are only reachable through the item, so they go
     * with it.
     * <p>
     * The containing node comes from the traversal, not from the item's {@code /Parent}: an item
     * without a {@code /Parent} (malformed, but it occurs) would otherwise be found by the sweep
     * and then silently left in place - exactly the kind of no-op this module must not have.
     * </p>
     * @param itemDict the item's dictionary
     * @param nodeDict the dictionary of the node whose {@code /First}/{@code /Last} chain holds it,
     *                 or {@code null} to fall back to the item's {@code /Parent}
     */
    static void removeOutlineItem(COSDictionary itemDict, COSDictionary nodeDict) {
        COSDictionary parent = nodeDict != null ? nodeDict
                : itemDict.getCOSDictionary(COSName.PARENT);
        if (parent == null) {
            LOGGER.warn("pdfedit: an outline item has neither a containing node nor a /Parent");
            return;
        }
        COSBase next = itemDict.getDictionaryObject(COSName.NEXT);
        COSBase prev = itemDict.getDictionaryObject(COSName.PREV);
        if (prev instanceof COSDictionary prevDict) {
            prevDict.setItem(COSName.NEXT, next);
        } else {
            parent.setItem(COSName.FIRST, next);
        }
        if (next instanceof COSDictionary nextDict) {
            nextDict.setItem(COSName.PREV, prev);
        } else {
            parent.setItem(COSName.LAST, prev);
        }
        int removed = 1 + countChildren(itemDict, 0);
        COSDictionary current = parent;
        int guard = 0;
        while (current != null && guard++ < MAX_OUTLINE_DEPTH) {
            int count = current.getInt(COSName.COUNT, 0);
            if (count > 0) {
                current.setInt(COSName.COUNT, Math.max(0, count - removed));
            } else if (count < 0) {
                current.setInt(COSName.COUNT, Math.min(0, count + removed));
            }
            current.setNeedToBeUpdated(true);
            current = current.getCOSDictionary(COSName.PARENT);
        }
        parent.setNeedToBeUpdated(true);
    }

    private static int countChildren(COSDictionary itemDict, int depth) {
        if (depth > MAX_OUTLINE_DEPTH) {
            return 0;
        }
        COSBase current = itemDict.getDictionaryObject(COSName.FIRST);
        int count = 0;
        int siblings = 0;
        while (current instanceof COSDictionary dict && ++siblings <= MAX_OUTLINE_SIBLINGS) {
            count += 1 + countChildren(dict, depth + 1);
            current = dict.getDictionaryObject(COSName.NEXT);
        }
        return count;
    }

    /**
     * Collects link annotations of remaining pages whose target is a removed page.
     */
    private static void sweepLinks(PDDocument document, Set<COSDictionary> doomedDicts,
                                   Map<COSDictionary, Integer> pageNumbers, Sweep sweep) {
        for (PDPage page : document.getPages()) {
            if (doomedDicts.contains(page.getCOSObject())) {
                continue;
            }
            List<PDAnnotation> hits = new ArrayList<>();
            try {
                for (PDAnnotation annotation : page.getAnnotations()) {
                    if (!(annotation instanceof PDAnnotationLink link)) {
                        continue;
                    }
                    PDPage target = linkTarget(document, link);
                    if (target != null && doomedDicts.contains(target.getCOSObject())) {
                        hits.add(annotation);
                        sweep.count("links");
                        sweep.orphans.add("link on page " + pageIndex(document, page)
                                + " → page " + pageNumbers.get(target.getCOSObject()));
                    }
                }
            } catch (IOException e) {
                LOGGER.debug("pdfedit: the annotations of a page could not be read", e);
                continue;
            }
            if (!hits.isEmpty()) {
                sweep.links.put(page, hits);
            }
        }
    }

    /**
     * Resolves the page a link points at ({@code /Dest} or {@code /A /GoTo}).
     * <p>
     * Package-wide because {@code doc.merge}/{@code page.import} ask the same question of a link
     * before they copy a page: a link that leads to a page outside the selection is removed there
     * instead of being copied.
     * </p>
     * @param document the document the link belongs to
     * @param link the link annotation
     * @return the page, or {@code null} when the link has no resolvable page
     */
    static PDPage linkTarget(PDDocument document, PDAnnotationLink link) {
        try {
            if (link.getAction() instanceof PDActionGoTo goTo) {
                PDPage page = destinationPage(document, goTo.getDestination());
                if (page != null) {
                    return page;
                }
            }
            return destinationPage(document, link.getDestination());
        } catch (IOException e) {
            LOGGER.debug("pdfedit: a link destination could not be read", e);
            return null;
        }
    }

    /**
     * Resolves a destination to a page, following a named destination when needed.
     * @param document the document
     * @param destination the destination, may be {@code null}
     * @return the page or {@code null} when it cannot be resolved
     */
    private static PDPage destinationPage(PDDocument document, PDDestination destination)
            throws IOException {
        if (destination == null) {
            return null;
        }
        if (destination instanceof PDNamedDestination named) {
            PDPageDestination resolved = document.getDocumentCatalog()
                    .findNamedDestinationPage(named);
            return resolved == null ? null : resolved.getPage();
        }
        if (destination instanceof PDPageDestination pageDestination) {
            return pageDestination.getPage();
        }
        return null;
    }

    static PDPage outlineDestination(PDDocument document, PDOutlineItem item) {
        try {
            return item.findDestinationPage(document);
        } catch (IOException e) {
            LOGGER.debug("pdfedit: an outline destination could not be read", e);
            return null;
        }
    }

    /**
     * Collects named destinations that lead to a removed page: the {@code /Names /Dests} tree and
     * the older {@code /Dests} dictionary in the catalog.
     */
    private static void sweepDestinations(PDDocument document, PDDocumentCatalog catalog,
                                          Set<COSDictionary> doomedDicts,
                                          Map<COSDictionary, Integer> pageNumbers, Sweep sweep) {
        PDDocumentNameDictionary names = catalog.getNames();
        if (names != null) {
            PDDestinationNameTreeNode dests = names.getDests();
            if (dests != null) {
                collectNameTree(dests.getCOSObject(), document, doomedDicts, pageNumbers, sweep);
            }
        }
        COSDictionary oldDests = catalog.getCOSObject().getCOSDictionary(COSName.DESTS);
        if (oldDests != null) {
            List<String> hits = new ArrayList<>();
            for (COSName key : new ArrayList<>(oldDests.keySet())) {
                PDPage target = destinationPage(document, oldDests.getDictionaryObject(key));
                if (target != null && doomedDicts.contains(target.getCOSObject())) {
                    hits.add(key.getName());
                    sweep.count("dests");
                    sweep.orphans.add("named destination \"" + key.getName() + "\" → page "
                            + pageNumbers.get(target.getCOSObject()));
                }
            }
            for (String name : hits) {
                oldDests.removeItem(COSName.getPDFName(name));
            }
            if (!hits.isEmpty()) {
                oldDests.setNeedToBeUpdated(true);
            }
        }
    }

    private static void collectNameTree(COSDictionary node, PDDocument document,
                                        Set<COSDictionary> doomedDicts,
                                        Map<COSDictionary, Integer> pageNumbers, Sweep sweep) {
        COSArray entries = node.getCOSArray(COSName.NAMES);
        if (entries != null) {
            List<String> hits = new ArrayList<>();
            for (int i = 0; i + 1 < entries.size(); i += 2) {
                COSBase key = entries.getObject(i);
                if (!(key instanceof COSString string)) {
                    continue;
                }
                PDPage target = destinationPage(document, entries.getObject(i + 1));
                if (target != null && doomedDicts.contains(target.getCOSObject())) {
                    hits.add(string.getString());
                    sweep.count("dests");
                    sweep.orphans.add("named destination \"" + string.getString() + "\" → page "
                            + pageNumbers.get(target.getCOSObject()));
                }
            }
            if (!hits.isEmpty()) {
                sweep.namedDests.put(node, hits);
            }
        }
        COSArray kids = node.getCOSArray(COSName.KIDS);
        if (kids != null) {
            for (int i = 0; i < kids.size(); i++) {
                if (kids.getObject(i) instanceof COSDictionary kid) {
                    collectNameTree(kid, document, doomedDicts, pageNumbers, sweep);
                }
            }
        }
    }

    /**
     * Resolves a destination value as it appears in a name tree or the old {@code /Dests}
     * dictionary: an array, a name, a string or a dictionary with {@code /D} or a {@code /GoTo}
     * action.
     */
    private static PDPage destinationPage(PDDocument document, COSBase value) {
        if (value == null) {
            return null;
        }
        if (value instanceof COSDictionary dict) {
            COSName type = dict.getCOSName(COSName.S);
            if (type != null) {
                return "GoTo".equals(type.getName())
                        ? destinationPage(document, dict.getDictionaryObject(COSName.D))
                        : null;
            }
            if (dict.containsKey(COSName.DEST)) {
                return destinationPage(document, dict.getDictionaryObject(COSName.DEST));
            }
            return destinationPage(document, dict.getDictionaryObject(COSName.D));
        }
        try {
            return destinationPage(document, PDDestination.create(value));
        } catch (IOException e) {
            LOGGER.debug("pdfedit: a destination could not be read", e);
            return null;
        }
    }

    /**
     * Removes named destinations from a name-tree node's {@code /Names} array.
     */
    private static void removeNameTreeEntries(COSDictionary node, List<String> names) {
        COSArray entries = node.getCOSArray(COSName.NAMES);
        if (entries == null) {
            return;
        }
        for (int i = entries.size() - 2; i >= 0; i -= 2) {
            COSBase key = entries.getObject(i);
            if (key instanceof COSString string && names.contains(string.getString())) {
                entries.remove(i + 1);
                entries.remove(i);
            }
        }
        node.setNeedToBeUpdated(true);
    }

    /**
     * Collects top-level form fields whose widgets sit on a removed page.
     */
    private static void sweepFields(PDDocumentCatalog catalog, Set<COSDictionary> doomedAnnots,
                                    Sweep sweep) {
        COSDictionary formDict = catalog.getCOSObject().getCOSDictionary(COSName.ACRO_FORM);
        if (formDict == null) {
            return;
        }
        COSArray formFields = formDict.getCOSArray(COSName.FIELDS);
        if (formFields == null) {
            return;
        }
        for (int i = 0; i < formFields.size(); i++) {
            if (!(formFields.getObject(i) instanceof COSDictionary field)) {
                continue;
            }
            if (fieldOnRemovedPage(field, doomedAnnots)) {
                sweep.fields.add(field);
                sweep.count("fields");
                String name = field.getString(COSName.T);
                sweep.orphans.add("form field \"" + (name == null ? "(unnamed)" : name)
                        + "\" (its widget is on a removed page)");
            }
        }
    }

    private static boolean fieldOnRemovedPage(COSDictionary field, Set<COSDictionary> doomedAnnots) {
        COSArray kids = field.getCOSArray(COSName.KIDS);
        if (kids != null && kids.size() > 0) {
            for (int i = 0; i < kids.size(); i++) {
                if (kids.getObject(i) instanceof COSDictionary kid
                        && fieldOnRemovedPage(kid, doomedAnnots)) {
                    return true;
                }
            }
            return false;
        }
        return doomedAnnots.contains(field);
    }

    /**
     * Reports an open action that jumps to a removed page (and marks it for pruning).
     */
    private static void sweepOpenAction(PDDocument document, PDDocumentCatalog catalog,
                                        Set<COSDictionary> doomedDicts,
                                        Map<COSDictionary, Integer> pageNumbers, Sweep sweep) {
        try {
            COSBase openAction = catalog.getCOSObject().getDictionaryObject(COSName.OPEN_ACTION);
            if (openAction == null) {
                return;
            }
            PDPage target = destinationPage(document, openAction);
            if (target != null && doomedDicts.contains(target.getCOSObject())) {
                sweep.openAction = true;
                sweep.count("openActions");
                sweep.orphans.add("open action → page " + pageNumbers.get(target.getCOSObject()));
            }
        } catch (RuntimeException e) {
            LOGGER.debug("pdfedit: the open action could not be read", e);
        }
    }

    /**
     * Decides what happens to {@code /PageLabels}: a plain numbering survives a page removal, a
     * range tree does not - it is removed and the answer says so.
     */
    private static void sweepLabels(PDDocumentCatalog catalog, Sweep sweep) {
        COSBase labels = catalog.getCOSObject().getDictionaryObject(COSName.PAGE_LABELS);
        if (labels == null) {
            return;
        }
        if (isPlainNumbering(labels)) {
            sweep.labels = "kept";
        } else {
            sweep.labels = "removed";
            sweep.count("labels");
            sweep.orphans.add("page labels (their ranges no longer match the remaining pages)");
        }
    }

    /**
     * Tests whether a page-label tree is just "1, 2, 3, …" for the whole document.
     */
    private static boolean isPlainNumbering(COSBase labels) {
        if (!(labels instanceof COSDictionary dict)) {
            return false;
        }
        COSArray nums = dict.getCOSArray(COSName.NUMS);
        if (nums == null) {
            return dict.getCOSArray(COSName.KIDS) == null;
        }
        if (nums.size() != 2) {
            return false;
        }
        COSBase key = nums.getObject(0);
        if (!(key instanceof COSNumber number) || number.intValue() != 0) {
            return false;
        }
        if (!(nums.getObject(1) instanceof COSDictionary style)) {
            return false;
        }
        COSName type = style.getCOSName(COSName.S);
        COSBase start = style.getDictionaryObject(COSName.ST);
        boolean decimal = type != null && "D".equals(type.getName());
        boolean fromOne = start == null || (start instanceof COSNumber n && n.intValue() == 1);
        return decimal && fromOne;
    }

    /**
     * The 1-based page number of a page.
     */
    private static int pageIndex(PDDocument document, PDPage page) {
        int index = document.getPages().indexOf(page);
        return index < 0 ? 0 : index + 1;
    }

    /**
     * Resolves a 1-based page number to a page, with an error that names the document's size.
     * @param ctx the call context
     * @param pageNum the page number
     * @return the page
     */
    static PDPage requirePage(PdfEditContext ctx, int pageNum) {
        int count = ctx.document().getPages().getCount();
        if (pageNum < 1 || pageNum > count) {
            throw new PdfEditException("out-of-range", "page " + pageNum + " does not exist (the"
                    + " document has " + count + " page(s))",
                    "pages are counted from 1 - pdf.pages() prints the numbers");
        }
        return ctx.document().getPages().get(pageNum - 1);
    }
}
