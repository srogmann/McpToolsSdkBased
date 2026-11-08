package org.rogmann.mcp2sdk.pdfedit;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageTree;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.rogmann.mcp2sdk.js.JsFileSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * {@code doc.merge} and {@code page.import}: take pages from another document.
 * <p>
 * The two ops share one mechanism and make two promises. {@code doc.merge} merges a document -
 * pages, form fields, bookmarks, page labels - and {@code page.import} takes pages and nothing
 * else. Both let {@link PDFMergerUtility} do the copy work (it deep-clones pages, resources,
 * annotations and the structure tree and knows the name trees a hand-written merge forgets), and
 * both answer with the three things that make a merge usable instead of merely successful:
 * </p>
 * <ul>
 *   <li><b>Collisions are always reported.</b> Form fields live in one document-wide namespace
 *       (12.7.4: the {@code /T} chain); two documents that both have a field {@code Datum} have
 *       two fields of that name after a {@code keep}, and a viewer fills both with the same value.
 *       PDFBox renames the colliding field to {@code dummyFieldName<n>} - silently, and a field
 *       name can be referenced by {@code /AA} or JavaScript, so a rename breaks the document
 *       (§4.1 of the design). This module restores the name and says it loudly instead.</li>
 *   <li><b>The page labels are repaired.</b> {@code /PageLabels} is a number tree of ranges: after
 *       an append the pages either fall into a range that now numbers more pages than it was
 *       written for, or - when only the source had labels - the destination's own pages have no
 *       range at all. {@link #repairLabels} makes the tree cover every page and says what it
 *       added.</li>
 *   <li><b>A page selection drags nothing in that nobody asked for.</b> "Pages 3 to 7 of that
 *       report" is not "that report": a page carries references back into its own document
 *       ({@code /Annots} → {@code /P}, a link's destination, the tag structure), and appending a
 *       page whose neighbours are gone would copy them along or leave pointers that lead nowhere.
 *       The selection is therefore taken by <em>pruning the source</em> ({@link #takePages}) -
 *       after it, the source holds exactly the chosen pages and nothing document-level that
 *       describes the pages that are gone. What was left behind stands in the answer.</li>
 * </ul>
 * <p>
 * Both ops restructure the page tree, so both are refused with {@code incremental:true} - an
 * appended revision cannot express a new page tree ({@code PdfEditDoc.refuseStructuralIncremental}).
 * </p>
 */
final class PdfEditMerge {

    private static final Logger LOGGER = LoggerFactory.getLogger(PdfEditMerge.class);

    /** Deepest {@code /PageLabels} number-tree the repair walks (a malformed tree could loop). */
    private static final int MAX_LABEL_TREE_DEPTH = 16;

    /** Deepest field tree the form filter walks. */
    private static final int MAX_FIELD_DEPTH = 32;

    /** The prefix PDFBox gives a renamed field ({@code PDFMergerUtility.acroFormLegacyMode}). */
    private static final String DUMMY_FIELD_PREFIX = "dummyFieldName";

    /**
     * The catalog entries that point into the pages that a selection leaves behind: a named
     * destination, the open action, the tag structure, the page labels (which describe the
     * numbering of pages that are gone). A page selection drops them in both modes.
     */
    private static final List<COSName> POINTING_AWAY = List.of(
            COSName.NAMES, COSName.DESTS, COSName.OPEN_ACTION, COSName.PAGE_LABELS,
            COSName.STRUCT_TREE_ROOT);

    /**
     * The catalog entries that describe the <em>document</em> rather than its pages. Only
     * {@code page.import} drops them - it promises pages and nothing else.
     */
    private static final List<COSName> DOCUMENT_ONLY = List.of(
            COSName.ACRO_FORM, COSName.OUTLINES, COSName.OCPROPERTIES, COSName.METADATA,
            COSName.THREADS, COSName.PAGE_MODE, COSName.VIEWER_PREFERENCES, COSName.MARK_INFO,
            COSName.LANG, COSName.OUTPUT_INTENTS);

    private PdfEditMerge() {
        // Utility class
    }

    // -------------------------------------------------------------------
    // doc.merge
    // -------------------------------------------------------------------

    /**
     * Merges another document into this one.
     * @param ctx the call context
     * @param op the op
     */
    static void merge(PdfEditContext ctx, PdfEditOp op) {
        op.allow("from", "pages", "at", "formFields", "outlines");
        String formFields = oneOf(op, "formFields", "drop", "drop", "keep");
        String outlines = oneOf(op, "outlines", "append", "append", "drop");
        PDDocument destination = ctx.document();
        int destPages = destination.getNumberOfPages();
        int at = position(op, destPages);
        String from = displayName(op);
        PDDocument source = load(op);
        try {
            int sourcePages = source.getNumberOfPages();
            // The field names are read before a selection prunes the form: a collision is a
            // property of the two documents, so it is reported in both modes and for every
            // selection.
            List<String> sourceNames = fieldNames(source);
            List<Integer> selection = selection(op, sourcePages);
            int leftBehind = 0;
            if (selection != null) {
                leftBehind = takePages(ctx, source, selection, "keep".equals(formFields), false,
                        new ArrayList<>());
            }
            mergeInto(ctx, op, from, source, selection == null, sourcePages, sourceNames,
                    formFields, outlines, destPages, at, leftBehind);
        } finally {
            closeQuietly(source);
        }
    }

    /**
     * Runs the merge and answers for it.
     * @param ctx the call context
     * @param op the op
     * @param from the source's display name
     * @param source the document to merge (already pruned when a page selection was given)
     * @param whole whether {@code source} is the whole source document
     * @param sourcePages the page count of the original source
     * @param sourceNames the field names of the source before a selection pruned it
     * @param formFields {@code drop} or {@code keep}
     * @param outlines {@code append} or {@code drop}
     * @param destPages the destination's page count before the merge
     * @param at the 1-based position the first appended page takes
     * @param leftBehind form fields of the source that are not part of {@code source}
     */
    private static void mergeInto(PdfEditContext ctx, PdfEditOp op, String from,
                                  PDDocument source, boolean whole, int sourcePages,
                                  List<String> sourceNames, String formFields, String outlines,
                                  int destPages, int at, int leftBehind) {
        PDDocument destination = ctx.document();
        float versionBefore = destination.getVersion();
        boolean destTagged = hasStructureTree(destination);
        boolean sourceTagged = hasStructureTree(source);
        boolean destOcg = destination.getDocumentCatalog().getCOSObject()
                .getDictionaryObject(COSName.OCPROPERTIES) != null;
        boolean sourceOcg = source.getDocumentCatalog().getCOSObject()
                .getDictionaryObject(COSName.OCPROPERTIES) != null;
        boolean destMetadata = destination.getDocumentCatalog().getCOSObject()
                .getDictionaryObject(COSName.METADATA) != null;
        boolean needAppearances = needAppearances(destination);

        // What the two documents would collide in - read before anything moves. A collision is a
        // property of the two files, so it is reported in both modes.
        List<Map<String, Object>> conflicts = conflicts(fieldNames(destination), sourceNames);
        List<String> rootNames = rootFieldNames(source);
        int destFields = fieldCount(destination);
        Set<COSDictionary> keptItems = topLevelOutlines(destination);
        int sourceBookmarks = countTopLevel(source);
        if ("drop".equals(formFields)) {
            leftBehind = Math.max(leftBehind, detachForm(source));
        }

        try {
            new PDFMergerUtility().appendDocument(destination, source);
        } catch (IOException e) {
            throw new PdfEditException("broken", op.where() + ": '" + from + "' could not be"
                    + " merged: " + e.getMessage(),
                    "a dynamic XFA form or a broken object in the source is the usual cause -"
                            + " pdf.structure() on the source shows what the reader sees", e);
        }

        int merged = destination.getNumberOfPages() - destPages;
        List<String> restored = "keep".equals(formFields)
                ? restoreFieldNames(destination, rootNames, destFields) : List.of();
        int droppedItems = "drop".equals(outlines) ? dropAppendedOutlines(destination, keptItems)
                : 0;
        if (at != destPages + 1) {
            moveTail(destination, merged, at);
        }
        Map<String, Object> labels = repairLabels(ctx, at - 1);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("from", from);
        details.put("pages", (long) merged);
        details.put("pageNumbers", numbers(at, merged));
        details.put("formFields", formFields);
        details.put("outlines", outlines);
        if (leftBehind > 0) {
            details.put("fieldsLeftBehind", (long) leftBehind);
        }
        if (!conflicts.isEmpty()) {
            details.put("conflicts", new ArrayList<Object>(conflicts));
        }
        if (!restored.isEmpty()) {
            details.put("restoredNames", new ArrayList<Object>(restored));
        }
        if (droppedItems > 0) {
            details.put("droppedBookmarks", (long) droppedItems);
        }
        if (labels != null) {
            details.put("labels", labels);
        }
        ctx.changed(op, "merged " + merged + " page(s) from '" + from + "' (the document has "
                + destination.getNumberOfPages() + " now)", details);
        noteFixedAppearances(ctx, needAppearances);

        if (!whole) {
            ctx.note("doc.merge took a page selection (" + merged + " of " + sourcePages
                    + " page(s)): the source's named destinations, attachments, open action and"
                    + " tag structure stay behind - merge without 'pages' to take the whole"
                    + " document");
        }
        if ("drop".equals(formFields) && leftBehind > 0) {
            ctx.note("the source's /AcroForm was not merged (formFields:'drop'): its "
                    + leftBehind + " field(s) are gone, the widgets stay as annotations and still"
                    + " show the value their /AP holds - they are not fillable any more");
        }
        if ("keep".equals(formFields) && leftBehind > 0) {
            ctx.note("formFields:'keep' merged the fields of the pages that were taken; "
                    + leftBehind + " field(s) of the source belong to pages that stayed behind");
        }
        if (!conflicts.isEmpty()) {
            addConflictNotes(ctx, conflicts, "keep".equals(formFields));
        }
        if ("append".equals(outlines) && sourceBookmarks > 0) {
            ctx.note("the source's " + sourceBookmarks + " top-level bookmark(s) were appended"
                    + " after the existing ones (outlines:'drop' leaves them out)");
        }
        if ("drop".equals(outlines) && droppedItems > 0) {
            ctx.note("outlines:'drop': " + droppedItems + " bookmark(s) of the source were"
                    + " removed again, the destination's own tree is unchanged");
        }
        if (sourceTagged && !destTagged) {
            ctx.note("the source's tag structure (/StructTreeRoot) came along; the pages that"
                    + " were already there are untagged - pdfedit does not rebuild the tree (E4)");
        } else if (sourceTagged && destTagged) {
            ctx.note("both documents carry a tag structure: PDFBox merged the source's elements"
                    + " and renumbered /StructParents - the result is worth a look with"
                    + " pdf.markedContent() before it is handed on");
        }
        if (sourceOcg && destOcg) {
            ctx.note("both documents have optional content (/OCProperties): the two group"
                    + " dictionaries were merged into one - two layers of the same name stay two"
                    + " layers");
        }
        if (!destMetadata && destination.getDocumentCatalog().getCOSObject()
                .getDictionaryObject(COSName.METADATA) != null) {
            ctx.note("the destination had no XMP metadata, so the source's came along; /Info is"
                    + " set by doc.info, the metadata stream is not rewritten");
        }
        if (destination.getVersion() > versionBefore) {
            ctx.note("the version of the result is " + destination.getVersion() + " - the source"
                    + " named a higher one than the destination's " + versionBefore);
        }
    }

    // -------------------------------------------------------------------
    // page.import
    // -------------------------------------------------------------------

    /**
     * Imports individual pages of another document - pages only, nothing else.
     * @param ctx the call context
     * @param op the op
     */
    static void importPages(PdfEditContext ctx, PdfEditOp op) {
        op.allow("from", "pages", "at");
        PDDocument destination = ctx.document();
        int destPages = destination.getNumberOfPages();
        int at = position(op, destPages);
        String from = displayName(op);
        PDDocument source = load(op);
        try {
            int sourcePages = source.getNumberOfPages();
            List<Integer> selection = selection(op, sourcePages);
            if (selection == null) {
                throw PdfEditException.usage(op.where() + ": 'pages' is missing",
                        "page.import takes the pages it is told (pages:[1,2]) - for a whole"
                                + " document use doc.merge, which brings form fields, bookmarks"
                                + " and page labels along");
            }
            List<String> leftOut = new ArrayList<>();
            takePages(ctx, source, selection, false, true, leftOut);
            boolean needAppearances = needAppearances(destination);
            COSDictionary info = source.getDocument().getTrailer()
                    .getCOSDictionary(COSName.INFO);
            int sourceInfo = info == null ? 0 : info.size();
            if (info != null) {
                info.clear();
            }
            try {
                new PDFMergerUtility().appendDocument(destination, source);
            } catch (IOException e) {
                throw new PdfEditException("broken", op.where() + ": page(s) " + selection
                        + " of '" + from + "' could not be imported: " + e.getMessage(),
                        "a broken object on one of the pages is the usual cause - pdf.objects()"
                                + " on the source shows it", e);
            }
            if (at != destPages + 1) {
                moveTail(destination, selection.size(), at);
            }
            Map<String, Object> labels = repairLabels(ctx, at - 1);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("from", from);
            details.put("pages", (long) selection.size());
            details.put("pageNumbers", numbers(at, selection.size()));
            if (labels != null) {
                details.put("labels", labels);
            }
            ctx.changed(op, "imported " + selection.size() + " page(s) from '" + from + "' as page"
                    + " " + at + "…" + (at + selection.size() - 1), details);
            noteFixedAppearances(ctx, needAppearances);
            ctx.note("page.import takes pages and nothing else: the source's form fields,"
                    + " bookmarks, named destinations, page labels and metadata are not part of"
                    + " the result - doc.merge brings them along");
            if (sourceInfo > 0) {
                ctx.note("the source's document information (" + sourceInfo + " entr(y/ies)) was"
                        + " not merged - doc.info sets the /Info of the result");
            }
            if (!leftOut.isEmpty()) {
                ctx.note("not taken from the source: " + String.join(", ", leftOut));
            }
        } finally {
            closeQuietly(source);
        }
    }

    // -------------------------------------------------------------------
    // The page selection
    // -------------------------------------------------------------------

    /**
     * Reads the optional {@code pages} parameter.
     * @param op the op
     * @param pageCount the source's page count
     * @return the 1-based page numbers in call order, or {@code null} for "the whole document"
     */
    private static List<Integer> selection(PdfEditOp op, int pageCount) {
        if (op.params().get("pages") == null) {
            return null;
        }
        List<?> values = op.list("pages");
        if (values.isEmpty()) {
            throw PdfEditException.usage(op.where() + ": 'pages' is empty",
                    "pages is an array of 1-based page numbers, e.g. [1, 2, 5]");
        }
        List<Integer> pages = new ArrayList<>();
        Set<Integer> seen = new LinkedHashSet<>();
        for (Object value : values) {
            if (!(value instanceof Number number) || number.doubleValue() != number.intValue()) {
                throw PdfEditException.usage(op.where() + ": pages contains "
                        + PdfEditOp.describe(value),
                        "pages is an array of 1-based page numbers, e.g. [1, 2, 5]");
            }
            int page = number.intValue();
            if (page < 1 || page > pageCount) {
                throw new PdfEditException("out-of-range", op.where() + ": page " + page
                        + " does not exist in the source (it has " + pageCount + " page(s))",
                        "pages of the source are counted from 1 - pdf.pages() on the source"
                                + " prints the numbers");
            }
            if (!seen.add(page)) {
                throw PdfEditException.usage(op.where() + ": page " + page + " is named twice",
                        "each page of the source is taken once; for the same page twice, merge"
                                + " the source twice");
            }
            pages.add(page);
        }
        if (pages.size() > PdfEditLimits.MAX_PAGES_ADDED) {
            throw new PdfEditException("limit", op.where() + ": " + pages.size() + " pages",
                    "at most " + PdfEditLimits.MAX_PAGES_ADDED + " pages per call - split the"
                            + " call into several ones, each result can be the next input");
        }
        return pages;
    }

    /**
     * Reduces the source to the selected pages: the page tree keeps exactly those pages (in the
     * order the caller named them), the form keeps their fields and the bookmarks keep the entries
     * that still have a page. Everything the source's <em>document</em> says about the pages that
     * stay behind is dropped by {@link #stripDocumentLevel}.
     * <p>
     * Pruning the source is what makes the selection honest: {@code PDFMergerUtility} clones a
     * page together with everything it references, and a page references its document
     * ({@code /Annots} → {@code /P} → the page again, a link's destination, the tag structure).
     * After the prune there is nothing left to drag in but the pages that were asked for.
     * </p>
     * @param ctx the call context
     * @param source the source document (modified in place)
     * @param selection the 1-based page numbers in call order
     * @param keepForm whether the fields of the selected pages come along
     * @param pagesOnly true for {@code page.import}: drop every document-level entry as well
     * @param leftOut collects the document-level entries that were dropped
     * @return the number of root fields that stay behind
     */
    private static int takePages(PdfEditContext ctx, PDDocument source, List<Integer> selection,
                                 boolean keepForm, boolean pagesOnly, List<String> leftOut) {
        PDPageTree tree = source.getPages();
        int before = tree.getCount();
        Set<Integer> wanted = new LinkedHashSet<>(selection);
        for (int page = tree.getCount(); page >= 1; page--) {
            if (!wanted.contains(page)) {
                tree.remove(page - 1);
            }
        }
        reorder(tree, selection);
        int leftBehind = filterForm(source, keepForm);
        int bookmarks = pagesOnly ? 0 : PdfEditOutline.pruneDestinations(source);
        int links = removeLinksOutsideSelection(source);
        stripDocumentLevel(source, pagesOnly, leftOut);
        if (bookmarks > 0) {
            ctx.note("the page selection removed " + bookmarks + " bookmark(s) that led to pages"
                    + " outside the selection");
        }
        if (links > 0) {
            ctx.note("the page selection removed " + links + " link(s) on the selected pages that"
                    + " led to pages outside the selection");
        }
        if (before != selection.size()) {
            LOGGER.debug("pdfedit: {} of {} pages of the source were taken", selection.size(),
                    before);
        }
        return leftBehind;
    }

    /**
     * Puts the remaining pages into the order the caller named them.
     * @param tree the page tree (holding exactly the selected pages, in document order)
     * @param selection the 1-based page numbers as the caller named them
     */
    private static void reorder(PDPageTree tree, List<Integer> selection) {
        List<Integer> sorted = new ArrayList<>(selection);
        Collections.sort(sorted);
        boolean sameOrder = sorted.equals(selection);
        if (sameOrder) {
            return;
        }
        Map<Integer, PDPage> bySourceNumber = new LinkedHashMap<>();
        for (int i = 0; i < sorted.size(); i++) {
            bySourceNumber.put(sorted.get(i), tree.get(i));
        }
        for (int i = tree.getCount() - 1; i >= 0; i--) {
            tree.remove(i);
        }
        for (Integer pageNum : selection) {
            tree.add(bySourceNumber.get(pageNum));
        }
    }

    /**
     * Keeps the form fields of the remaining pages (or drops the form).
     * @param source the source document (pruned)
     * @param keepForm whether the fields come along
     * @return the number of root fields that stay behind
     */
    private static int filterForm(PDDocument source, boolean keepForm) {
        COSDictionary catalog = source.getDocumentCatalog().getCOSObject();
        COSDictionary formDict = catalog.getCOSDictionary(COSName.ACRO_FORM);
        if (formDict == null) {
            return 0;
        }
        COSArray fields = formDict.getCOSArray(COSName.FIELDS);
        if (fields == null) {
            catalog.removeItem(COSName.ACRO_FORM);
            return 0;
        }
        Set<COSDictionary> kept = Collections.newSetFromMap(new IdentityHashMap<>());
        for (PDPage page : source.getPages()) {
            COSArray annots = page.getCOSObject().getCOSArray(COSName.ANNOTS);
            if (annots == null) {
                continue;
            }
            for (int i = 0; i < annots.size(); i++) {
                if (annots.getObject(i) instanceof COSDictionary annot) {
                    kept.add(annot);
                }
            }
        }
        List<COSDictionary> remaining = new ArrayList<>();
        int leftBehind = 0;
        for (int i = 0; i < fields.size(); i++) {
            if (!(fields.getObject(i) instanceof COSDictionary field)) {
                continue;
            }
            if (keepForm && hasWidget(field, kept, 0)) {
                remaining.add(field);
            } else {
                leftBehind++;
            }
        }
        if (!keepForm || remaining.isEmpty()) {
            catalog.removeItem(COSName.ACRO_FORM);
            return leftBehind;
        }
        COSArray keptFields = new COSArray();
        for (COSDictionary field : remaining) {
            keptFields.add(field);
        }
        formDict.setItem(COSName.FIELDS, keptFields);
        formDict.setNeedToBeUpdated(true);
        return leftBehind;
    }

    /**
     * Removes the links on the remaining pages that lead to pages that are not part of the result.
     * @param source the source document (pruned)
     * @return the number of removed links
     */
    private static int removeLinksOutsideSelection(PDDocument source) {
        Set<COSDictionary> pages = Collections.newSetFromMap(new IdentityHashMap<>());
        for (PDPage page : source.getPages()) {
            pages.add(page.getCOSObject());
        }
        int removed = 0;
        for (PDPage page : source.getPages()) {
            COSArray annots = page.getCOSObject().getCOSArray(COSName.ANNOTS);
            if (annots == null) {
                continue;
            }
            for (int i = annots.size() - 1; i >= 0; i--) {
                if (!(annots.getObject(i) instanceof COSDictionary annot)) {
                    continue;
                }
                COSName subtype = annot.getCOSName(COSName.SUBTYPE);
                if (subtype == null || !"Link".equals(subtype.getName())) {
                    continue;
                }
                PDPage target = PdfEditPages.linkTarget(source, new PDAnnotationLink(annot));
                if (target != null && !pages.contains(target.getCOSObject())) {
                    annots.remove(i);
                    removed++;
                    LOGGER.debug("pdfedit: link to a page outside the selection removed: {}",
                            linkText(annot));
                }
            }
        }
        return removed;
    }

    /**
     * Drops what the source says about its document rather than about its pages.
     * <p>
     * After a page selection these entries point outside the selection (a named destination, the
     * open action, the tag structure) or describe the numbering of pages that are not there (the
     * page labels). {@code page.import} additionally drops everything document-level - its
     * promise is "pages and nothing else". What was dropped is collected for the answer.
     * </p>
     * @param source the source document (pruned)
     * @param pagesOnly true for {@code page.import} (drop the document-level entries as well)
     * @param leftOut collects the names of the dropped entries
     */
    private static void stripDocumentLevel(PDDocument source, boolean pagesOnly,
                                           List<String> leftOut) {
        PDDocumentCatalog catalog = source.getDocumentCatalog();
        List<COSName> entries = new ArrayList<>(POINTING_AWAY);
        if (pagesOnly) {
            entries.addAll(DOCUMENT_ONLY);
        }
        for (COSName name : entries) {
            if (catalog.getCOSObject().getDictionaryObject(name) == null) {
                continue;
            }
            catalog.getCOSObject().removeItem(name);
            leftOut.add("/" + name.getName());
        }
    }

    // -------------------------------------------------------------------
    // Reading the source
    // -------------------------------------------------------------------

    /**
     * The display name of the {@code from} parameter, for messages and the answer.
     * @param op the op
     * @return the project-relative path, or {@code "<bytes>"}
     */
    private static String displayName(PdfEditOp op) {
        Object value = op.params().get("from");
        if (value instanceof String path && !path.isBlank()) {
            return JsFileSystem.toRelative(JsFileSystem.resolveSafePath(path));
        }
        return "<bytes>";
    }

    /**
     * Reads the {@code from} parameter into bytes.
     * @param op the op
     * @return the bytes
     */
    private static byte[] read(PdfEditOp op) {
        Object value = op.params().get("from");
        if (value == null) {
            throw PdfEditException.usage(op.where() + ": 'from' is missing",
                    "from is the source document: a project path ('anhang.pdf') or a"
                            + " Uint8Array of PDF bytes");
        }
        if (value instanceof byte[] data) {
            return data;
        }
        if (!(value instanceof String path) || path.isBlank()) {
            throw PdfEditException.usage(op.where() + ": from is " + PdfEditOp.describe(value),
                    "from is a project path ('anhang.pdf') or a Uint8Array of PDF bytes");
        }
        Path file = JsFileSystem.resolveSafePath(path);
        if (!Files.isRegularFile(file)) {
            throw new PdfEditException("out-of-range", op.where() + ": the source '"
                    + JsFileSystem.toRelative(file) + "' does not exist",
                    "the path is relative to the project directory - pdf.structure() shows what"
                            + " the reader makes of a file");
        }
        try {
            long size = Files.size(file);
            if (size > PdfEditLimits.MAX_INPUT_BYTES) {
                throw new PdfEditException("limit", op.where() + ": '" + JsFileSystem
                        .toRelative(file) + "' has " + size + " bytes",
                        "at most " + PdfEditLimits.MAX_INPUT_BYTES + " bytes are accepted");
            }
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new PdfEditException("broken", op.where() + ": '" + JsFileSystem
                    .toRelative(file) + "' cannot be read: " + e.getMessage(),
                    "the source is read through the project directory (the same rules as"
                            + " fs.readFile)", e);
        }
    }

    /**
     * Loads the source document.
     * @param op the op
     * @return the document (to be closed by the caller)
     */
    private static PDDocument load(PdfEditOp op) {
        byte[] data = read(op);
        String name = displayName(op);
        PDDocument document;
        try {
            document = Loader.loadPDF(data);
        } catch (InvalidPasswordException e) {
            throw new PdfEditException("encrypted", op.where() + ": the source '" + name
                    + "' is encrypted", "decryption is not implemented - remove the protection"
                    + " with another tool first", e);
        } catch (IOException | RuntimeException e) {
            throw new PdfEditException("broken", op.where() + ": the source '" + name
                    + "' cannot be read: " + e.getMessage(),
                    "try pdf.structure() on the source to see what the reader sees", e);
        }
        if (document.isEncrypted()) {
            closeQuietly(document);
            throw new PdfEditException("encrypted", op.where() + ": the source '" + name
                    + "' is encrypted",
                    "a merge needs the objects of both documents in one file - remove the"
                            + " protection of the source first");
        }
        return document;
    }

    /**
     * Reads the {@code at} parameter: the position the first appended page takes.
     * @param op the op
     * @param count the document's page count before the call
     * @return the 1-based position ({@code count + 1} appends)
     */
    private static int position(PdfEditOp op, int count) {
        Integer value = op.intOrNull("at");
        if (value == null) {
            return count + 1;
        }
        if (value < 1 || value > count + 1) {
            throw new PdfEditException("out-of-range", op.where() + ": at is " + value,
                    "at is the position of the first appended page: 1 … " + (count + 1)
                            + " (the document has " + count + " page(s)); without at the pages"
                            + " are appended");
        }
        return value;
    }

    /**
     * Reads an enumerated string parameter.
     * @param op the op
     * @param key the parameter name
     * @param fallback the value used when the parameter is absent
     * @param allowed the accepted values
     * @return the value
     */
    private static String oneOf(PdfEditOp op, String key, String fallback, String... allowed) {
        String value = op.stringOrNull(key);
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        for (String candidate : allowed) {
            if (candidate.equals(trimmed)) {
                return candidate;
            }
        }
        throw PdfEditException.usage(op.where() + ": " + key + " is '" + value + "'",
                key + " is " + String.join(" or ", allowed) + " (the default is '" + fallback
                        + "')");
    }

    /**
     * The numbers a block of appended pages takes, for the answer.
     * @param at the first position
     * @param count the number of pages
     * @return the numbers as strings
     */
    private static List<Object> numbers(int at, int count) {
        List<Object> numbers = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            numbers.add(Integer.toString(at + i));
        }
        return numbers;
    }

    // -------------------------------------------------------------------
    // Form fields
    // -------------------------------------------------------------------

    /**
     * Tests whether a field or one of its children has a widget among the given annotations.
     * @param field the field dictionary
     * @param annots the annotations of the remaining pages (identity)
     * @param depth the nesting depth
     * @return true when the field belongs to a remaining page
     */
    private static boolean hasWidget(COSDictionary field, Set<COSDictionary> annots, int depth) {
        if (depth > MAX_FIELD_DEPTH) {
            return false;
        }
        if (annots.contains(field)) {
            return true;
        }
        COSArray kids = field.getCOSArray(COSName.KIDS);
        if (kids == null) {
            return false;
        }
        for (int i = 0; i < kids.size(); i++) {
            if (kids.getObject(i) instanceof COSDictionary kid
                    && hasWidget(kid, annots, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The fully qualified names of every field of a document.
     * @param document the document
     * @return the names in form order
     */
    private static List<String> fieldNames(PDDocument document) {
        List<String> names = new ArrayList<>();
        PDAcroForm form = form(document);
        if (form == null) {
            return names;
        }
        for (PDField field : form.getFieldTree()) {
            String name = field.getFullyQualifiedName();
            if (name != null && !name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * The partial names of the root fields - the level {@code PDFMergerUtility} clones.
     * @param document the document
     * @return the names in {@code /Fields} order
     */
    private static List<String> rootFieldNames(PDDocument document) {
        List<String> names = new ArrayList<>();
        PDAcroForm form = form(document);
        if (form == null) {
            return names;
        }
        for (PDField field : form.getFields()) {
            names.add(field.getPartialName());
        }
        return names;
    }

    /**
     * Counts the root fields of a document.
     * @param document the document
     * @return the count (0 when there is no form)
     */
    private static int fieldCount(PDDocument document) {
        PDAcroForm form = form(document);
        return form == null ? 0 : form.getFields().size();
    }

    /**
     * The document's form, read without PDFBox' automatic fixup - a merge must not repair the
     * source's form on the side.
     * @param document the document
     * @return the form or {@code null}
     */
    private static PDAcroForm form(PDDocument document) {
        COSDictionary formDict = document.getDocumentCatalog().getCOSObject()
                .getCOSDictionary(COSName.ACRO_FORM);
        return formDict == null ? null : new PDAcroForm(document, formDict);
    }

    /**
     * Builds the list of field names both documents have.
     * @param destination the names of the destination
     * @param source the names of the source
     * @return one entry per name, {@code {name, inFrom, inTo}}
     */
    private static List<Map<String, Object>> conflicts(List<String> destination,
                                                       List<String> source) {
        Set<String> inDestination = new LinkedHashSet<>(destination);
        Set<String> reported = new LinkedHashSet<>();
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (String name : source) {
            if (inDestination.contains(name) && reported.add(name)) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", name);
                entry.put("inFrom", true);
                entry.put("inTo", true);
                conflicts.add(entry);
            }
        }
        return conflicts;
    }

    /**
     * Adds one note per conflict - the collision is the answer's job, not a silent rename.
     * @param ctx the call context
     * @param conflicts the conflicts
     * @param kept whether the fields were kept (otherwise the form was dropped)
     */
    private static void addConflictNotes(PdfEditContext ctx, List<Map<String, Object>> conflicts,
                                         boolean kept) {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> conflict : conflicts) {
            names.add(String.valueOf(conflict.get("name")));
        }
        if (kept) {
            for (String name : names) {
                ctx.note("the field name '" + name + "' exists in both documents: the result has"
                        + " two fields of that name, and a viewer fills both with the same value -"
                        + " rename one (object.set on its /T) or merge with formFields:'drop'");
            }
        } else {
            ctx.note("the field name(s) " + names + " exist in both documents; the source's form"
                    + " was dropped, so no two fields share a name");
        }
    }

    /**
     * Removes the source's {@code /AcroForm} before the merge - {@code formFields:'drop'}.
     * <p>
     * The widgets stay where they are (they are annotations of the pages and carry their
     * {@code /AP}), so the appended pages still <em>look</em> filled; the document is just not
     * fillable any more.
     * </p>
     * @param source the source document
     * @return the number of root fields that were not merged
     */
    private static int detachForm(PDDocument source) {
        PDDocumentCatalog catalog = source.getDocumentCatalog();
        COSDictionary formDict = catalog.getCOSObject().getCOSDictionary(COSName.ACRO_FORM);
        if (formDict == null) {
            return 0;
        }
        COSArray fields = formDict.getCOSArray(COSName.FIELDS);
        int count = fields == null ? 0 : fields.size();
        catalog.getCOSObject().removeItem(COSName.ACRO_FORM);
        return count;
    }

    /**
     * Gives the appended fields their names back.
     * <p>
     * {@code PDFMergerUtility} renames a colliding field to {@code dummyFieldName<n>} - silently,
     * and a field name can be referenced by {@code /AA} or JavaScript, so a rename breaks the
     * document. The appended fields are the tail of {@code /AcroForm /Fields}, one per root field
     * of the source, in order: the name is restored where PDFBox put a dummy name.
     * </p>
     * @param destination the destination document
     * @param rootNames the partial names of the source's root fields
     * @param fromIndex the size of {@code /Fields} before the merge
     * @return the names that were restored
     */
    private static List<String> restoreFieldNames(PDDocument destination, List<String> rootNames,
                                                  int fromIndex) {
        List<String> restored = new ArrayList<>();
        COSDictionary formDict = destination.getDocumentCatalog().getCOSObject()
                .getCOSDictionary(COSName.ACRO_FORM);
        if (formDict == null) {
            return restored;
        }
        COSArray fields = formDict.getCOSArray(COSName.FIELDS);
        if (fields == null) {
            return restored;
        }
        int index = fromIndex;
        for (String name : rootNames) {
            if (index >= fields.size()) {
                break;
            }
            COSBase base = fields.getObject(index);
            index++;
            if (!(base instanceof COSDictionary field) || name == null) {
                continue;
            }
            String current = field.getString(COSName.T);
            if (current != null && current.startsWith(DUMMY_FIELD_PREFIX)
                    && !current.equals(name)) {
                field.setString(COSName.T, name);
                field.setNeedToBeUpdated(true);
                restored.add(name);
            }
        }
        if (!restored.isEmpty()) {
            formDict.setNeedToBeUpdated(true);
        }
        return restored;
    }

    // -------------------------------------------------------------------
    // Bookmark tree
    // -------------------------------------------------------------------

    /**
     * The destination's top-level bookmarks, by identity - what {@code outlines:'drop'} keeps.
     * @param document the document
     * @return the item dictionaries
     */
    private static Set<COSDictionary> topLevelOutlines(PDDocument document) {
        Set<COSDictionary> items = Collections.newSetFromMap(new IdentityHashMap<>());
        PDDocumentOutline outline = document.getDocumentCatalog().getDocumentOutline();
        if (outline != null) {
            for (PDOutlineItem item : outline.children()) {
                items.add(item.getCOSObject());
            }
        }
        return items;
    }

    /**
     * Counts the top-level bookmarks of a document.
     * @param document the document
     * @return the count
     */
    private static int countTopLevel(PDDocument document) {
        return topLevelOutlines(document).size();
    }

    /**
     * Removes the bookmarks the merge appended ({@code outlines:'drop'}).
     * <p>
     * {@code PDFMergerUtility} inserts the source's items <em>after</em> the destination's last
     * top-level entry (or replaces the whole tree when the destination has none). Removing them is
     * cheaper and more faithful than copying the destination's tree away and back: the items that
     * were there before the merge are exactly the ones that stay.
     * </p>
     * @param destination the destination document
     * @param keptItems the top-level items that existed before the merge (identity)
     * @return the number of removed entries
     */
    private static int dropAppendedOutlines(PDDocument destination, Set<COSDictionary> keptItems) {
        PDDocumentOutline outline = destination.getDocumentCatalog().getDocumentOutline();
        if (outline == null) {
            return 0;
        }
        COSDictionary outlineDict = outline.getCOSObject();
        List<PDOutlineItem> children = new ArrayList<>();
        for (PDOutlineItem item : outline.children()) {
            children.add(item);
        }
        int dropped = 0;
        for (PDOutlineItem item : children) {
            if (keptItems.contains(item.getCOSObject())) {
                continue;
            }
            PdfEditPages.removeOutlineItem(item.getCOSObject(), outlineDict);
            dropped++;
        }
        if (dropped > 0) {
            destination.getDocumentCatalog().getCOSObject().setNeedToBeUpdated(true);
            if (outlineDict.getDictionaryObject(COSName.FIRST) == null) {
                destination.getDocumentCatalog().getCOSObject().removeItem(COSName.OUTLINES);
            }
        }
        return dropped;
    }

    // -------------------------------------------------------------------
    // Moving the appended pages to 'at'
    // -------------------------------------------------------------------

    /**
     * Moves the last {@code count} pages of the document to the 1-based position {@code at}.
     * @param document the document
     * @param count the number of pages to move
     * @param at the target position
     */
    private static void moveTail(PDDocument document, int count, int at) {
        PDPageTree tree = document.getPages();
        int total = tree.getCount();
        List<PDPage> tail = new ArrayList<>();
        for (int i = total - count; i < total; i++) {
            tail.add(tree.get(i));
        }
        for (int i = 0; i < count; i++) {
            tree.remove(tree.getCount() - 1);
        }
        if (at > tree.getCount()) {
            for (PDPage page : tail) {
                tree.add(page);
            }
            return;
        }
        PDPage anchor = tree.get(at - 1);
        for (PDPage page : tail) {
            tree.insertBefore(page, anchor);
        }
    }

    // -------------------------------------------------------------------
    // Page labels
    // -------------------------------------------------------------------

    /**
     * Repairs {@code /PageLabels} after pages were appended.
     * <p>
     * A label tree is a number tree of ranges: each entry says how the pages from its index on are
     * numbered (style {@code /S}, prefix {@code /P}, start {@code /St}), and it reaches to the next
     * entry. Two things go wrong on an append:
     * </p>
     * <ul>
     *   <li>the appended pages fall into the <em>last</em> range of the destination, which then
     *       numbers more pages than it was written for (a roman range would continue i, ii, iii
     *       over the appendix);</li>
     *   <li>when only the source had labels, its ranges were copied shifted to the append index -
     *       the destination's own pages now have <em>no</em> range, and a range for the first page
     *       is what the specification asks for (PDFBox writes it in every
     *       {@code new PDPageLabels(document)}).</li>
     * </ul>
     * <p>
     * The repair keeps every range the file has and adds a plain decimal range at index 0 where
     * one is missing, and at the first appended page where the governing range does not simply
     * continue counting. What was added stands in the answer - a numbering the caller did not ask
     * for is a change like any other. {@code verify()} reads the result back and reports a page
     * without a label as a problem.
     * </p>
     * @param ctx the call context
     * @param mergeIndex the 0-based index of the first appended page
     * @return the details for the answer, or {@code null} when the document has no labels
     */
    private static Map<String, Object> repairLabels(PdfEditContext ctx, int mergeIndex) {
        PDDocumentCatalog catalog = ctx.document().getDocumentCatalog();
        COSDictionary labels = catalog.getCOSObject().getCOSDictionary(COSName.PAGE_LABELS);
        if (labels == null) {
            return null;
        }
        TreeMap<Integer, COSDictionary> ranges = new TreeMap<>();
        List<String> unreadable = new ArrayList<>();
        collectRanges(labels, ranges, unreadable, 0);
        List<String> added = new ArrayList<>();
        if (!ranges.containsKey(0)) {
            ranges.put(0, decimalRange());
            added.add("0 (the first page of the document)");
        }
        Map.Entry<Integer, COSDictionary> governing = ranges.floorEntry(mergeIndex);
        if (!ranges.containsKey(mergeIndex)
                && (governing == null || !continuesCounting(governing.getValue()))) {
            ranges.put(mergeIndex, decimalRange());
            added.add(mergeIndex + " (the first appended page)");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("ranges", (long) ranges.size());
        details.put("added", new ArrayList<Object>(added));
        if (added.isEmpty() && unreadable.isEmpty()) {
            // Nothing to repair: the tree stays as the file wrote it. Rewriting it would be a
            // change of the document that nobody asked for.
            ctx.note("page labels: the appended pages continue the numbering of the range that"
                    + " governs them, so /PageLabels needed no repair");
            return details;
        }
        COSArray nums = new COSArray();
        for (Map.Entry<Integer, COSDictionary> entry : ranges.entrySet()) {
            nums.add(COSInteger.get(entry.getKey()));
            // Inline, not as an object of its own: a range belongs IN the tree. Stand it as a
            // reference, a reader shows the reference instead of the style (the own reader keeps
            // a reference visible by rule, and xpdf found no appearance state behind a reference
            // for the very same reason in §19.6). Same lesson, second round - and this method's
            // own documentation said it before the code did; the second E3 build said it again.
            COSDictionary range = new COSDictionary(entry.getValue());
            range.setDirect(true);
            nums.add(range);
        }
        COSDictionary flat = new COSDictionary();
        flat.setItem(COSName.NUMS, nums);
        catalog.getCOSObject().setItem(COSName.PAGE_LABELS, flat);
        catalog.getCOSObject().setNeedToBeUpdated(true);
        if (!unreadable.isEmpty()) {
            details.put("unreadable", new ArrayList<Object>(unreadable));
            // Dropping an entry the file does have is a change of the document: it is said, not
            // swallowed. (The first E3 build failed exactly here: a fixture wrote
            // /Nums [[0 <<…>>]] - an array where the page index belongs - and the repair dropped
            // it without a word.)
            ctx.note("the /PageLabels tree held " + unreadable.size() + " entr(y/ies) that are no"
                    + " range (" + String.join("; ", unreadable) + "): they were dropped - the"
                    + " repair writes the tree as a flat /Nums, and verify() names every page that"
                    + " is left without a label");
        }
        if (!added.isEmpty()) {
            ctx.note("page labels: a decimal range was added at index " + String.join(" and ",
                    added) + " - without it the appended pages would either have no label at all"
                    + " or continue a range that was written for other pages (object.set writes"
                    + " /PageLabels if the appendix needs its own numbering)");
        }
        return details;
    }

    /**
     * Collects the ranges of a label number tree ({@code /Nums} and nested {@code /Kids}).
     * <p>
     * An entry that is not a pair of page index and range dictionary is <b>collected, not
     * skipped</b>: the repair rewrites the tree, so an entry it cannot read is lost - and a
     * number tree is a place where a producer writes the obvious wrong shape (an array
     * {@code [[0 <<…>>]]} instead of the entries {@code [0 <<…>>]}, which PDFBox answers with
     * "page labels ignored, index 0 should be a number").
     * </p>
     * @param node the tree node
     * @param ranges the collected ranges, by start index (the first definition wins)
     * @param unreadable collects one sentence per entry that is not a range
     * @param depth the nesting depth
     */
    private static void collectRanges(COSDictionary node, Map<Integer, COSDictionary> ranges,
                                      List<String> unreadable, int depth) {
        if (depth > MAX_LABEL_TREE_DEPTH) {
            return;
        }
        COSArray nums = node.getCOSArray(COSName.NUMS);
        if (nums != null) {
            for (int i = 0; i + 1 < nums.size(); i += 2) {
                COSBase key = nums.getObject(i);
                COSBase value = nums.getObject(i + 1);
                if (key instanceof COSNumber number && value instanceof COSDictionary range) {
                    ranges.putIfAbsent(number.intValue(), range);
                } else {
                    unreadable.add("entry " + (i / 2) + ": " + kind(key) + " where the page index"
                            + " belongs, " + kind(value) + " where the range belongs");
                }
            }
            if (nums.size() % 2 != 0) {
                unreadable.add("an odd /Nums array: its last entry is "
                        + kind(nums.getObject(nums.size() - 1)) + " where a page index belongs");
            }
        }
        COSArray kids = node.getCOSArray(COSName.KIDS);
        if (kids != null) {
            for (int i = 0; i < kids.size(); i++) {
                if (kids.getObject(i) instanceof COSDictionary kid) {
                    collectRanges(kid, ranges, unreadable, depth + 1);
                }
            }
        }
    }

    /**
     * Names a COS value for a message ("an array where the page index belongs").
     * @param value the value, may be {@code null}
     * @return a short phrase
     */
    private static String kind(COSBase value) {
        if (value == null) {
            return "nothing";
        }
        if (value instanceof COSArray) {
            return "an array";
        }
        if (value instanceof COSNumber) {
            return "a number";
        }
        if (value instanceof COSName) {
            return "a name";
        }
        if (value instanceof COSString) {
            return "a string";
        }
        if (value instanceof COSDictionary) {
            return "a dictionary";
        }
        return "a " + value.getClass().getSimpleName();
    }

    /**
     * A plain decimal range: "D" without a prefix - the style that continues counting when more
     * pages join it. The dictionary is marked direct, so it stands inline in the tree.
     * @return the range dictionary
     */
    private static COSDictionary decimalRange() {
        COSDictionary range = new COSDictionary();
        range.setItem(COSName.S, COSName.D);
        range.setDirect(true);
        return range;
    }

    /**
     * Tests whether a range numbers its pages "1, 2, 3, …" - then pages that join it simply
     * continue the sequence and no second range is needed.
     * @param range the range dictionary
     * @return true for decimal numbering without a prefix
     */
    private static boolean continuesCounting(COSDictionary range) {
        COSName style = range.getCOSName(COSName.S);
        boolean decimal = style == null || "D".equals(style.getName());
        return decimal && !range.containsKey(COSName.P);
    }

    // -------------------------------------------------------------------
    // Shared
    // -------------------------------------------------------------------

    /**
     * Tests whether a document's form asks the viewer to build the appearances
     * ({@code /AcroForm /NeedAppearances}).
     * @param document the document
     * @return true when the flag is set
     */
    private static boolean needAppearances(PDDocument document) {
        COSDictionary formDict = document.getDocumentCatalog().getCOSObject()
                .getCOSDictionary(COSName.ACRO_FORM);
        return formDict != null && formDict.getBoolean(COSName.NEED_APPEARANCES, false);
    }

    /**
     * Says that PDFBox built the appearances of the destination's form during the merge.
     * <p>
     * {@code PDDocumentCatalog.getAcroForm()} applies {@code AcroFormDefaultFixup}, and that
     * processor builds the appearance streams of every field when the form says
     * {@code /NeedAppearances true} ({@code AcroFormDefaultFixup} → {@code
     * AcroFormGenerateAppearancesProcessor}). The merge therefore changes the pictures of the
     * destination's own widgets - not their values, but a change nobody asked for, so it is said.
     * </p>
     * @param ctx the call context
     * @param before whether the flag was set before the merge
     */
    private static void noteFixedAppearances(PdfEditContext ctx, boolean before) {
        if (!before) {
            return;
        }
        ctx.note("the destination's form said /NeedAppearances true: the merge built the"
                + " appearance streams of its fields (PDFBox' AcroFormDefaultFixup) - the values"
                + " are unchanged, the widgets now carry an /AP of their own");
    }

    /**
     * Tests whether a document has a tag structure.
     * @param document the document
     * @return true when the catalog names a {@code /StructTreeRoot}
     */
    private static boolean hasStructureTree(PDDocument document) {
        return document.getDocumentCatalog().getCOSObject()
                .getDictionaryObject(COSName.STRUCT_TREE_ROOT) != null;
    }

    /**
     * The text of a link, for a debug line.
     * @param annot the annotation dictionary
     * @return the link's URI or the name of its action
     */
    private static String linkText(COSDictionary annot) {
        COSDictionary action = annot.getCOSDictionary(COSName.A);
        if (action != null) {
            COSBase uri = action.getDictionaryObject(COSName.URI);
            if (uri instanceof COSString text) {
                return "\"" + text.getString() + "\"";
            }
            return "action /" + action.getNameAsString(COSName.S);
        }
        return "a destination";
    }

    /**
     * Closes a document without letting a close error hide the original problem.
     * @param document the document
     */
    private static void closeQuietly(PDDocument document) {
        try {
            document.close();
        } catch (IOException e) {
            LOGGER.debug("pdfedit: closing the source document failed", e);
        }
    }
}
