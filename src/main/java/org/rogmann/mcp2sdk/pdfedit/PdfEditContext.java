package org.rogmann.mcp2sdk.pdfedit;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The state of one {@code pdfedit.create()} or {@code pdfedit.edit()} call: the document being
 * written, what was done ({@code changed}), what was not done ({@code notes}) and the indirect
 * objects the call created itself ({@code $0}, {@code $1}, …).
 * <p>
 * The context is per call and is never handed out - the module has no handle, no registry and no
 * state between calls (§1 of the design). Its job is to keep the three answers of a call in one
 * place, so that an op reports into them instead of returning a shape of its own.
 * </p>
 */
final class PdfEditContext {

    /** The sub-dictionaries of {@code /Resources} this module adds entries to. */
    private static final List<COSName> SHARED_SUBDICTIONARIES =
            List.of(COSName.FONT, COSName.XOBJECT, COSName.EXT_G_STATE);

    /** The document being written. */
    private final PDDocument document;

    /** Display name of the input, or {@code null} for a created document. */
    private final String sourceName;

    /** Notes of the answer: what was covered rather than removed, what was measured. */
    private final List<String> notes = new ArrayList<>();

    /** One entry per op that changed something. */
    private final List<Map<String, Object>> changed = new ArrayList<>();

    /**
     * One entry per op that only <b>answered a question</b> ({@code text.measure}).
     * <p>
     * Kept apart from {@link #changed} on purpose: "changed" is the list of what the result holds,
     * and a measured width is not in the file. An op list that measures and writes reports both -
     * in the two lists - so a reader of the answer can tell the file from the arithmetic.
     * </p>
     */
    private final List<Map<String, Object>> measured = new ArrayList<>();

    /** Objects created by {@code object.add}, addressed as {@code $0}, {@code $1}, … */
    private final Map<String, COSBase> createdObjects = new LinkedHashMap<>();

    /** Objects of removed pages, by identity (a page dictionary may have no object number). */
    private final Set<COSDictionary> removedPages =
            Collections.newSetFromMap(new IdentityHashMap<>());

    /** Per page (by dictionary identity) the resources dictionary this call may add to. */
    private final Map<COSDictionary, PDResources> ownResources = new IdentityHashMap<>();

    /**
     * Fonts this call has already embedded, by a key that names the source ({@code file:…}).
     * <p>
     * Two text ops that name the same TrueType file must share <b>one</b> font object: a second
     * {@code PDType0Font.load} would embed the file twice and the document would grow by the font
     * per op. The cache lives in the call, so nothing survives it - the module stays stateless.
     * </p>
     */
    private final Map<String, PDFont> fonts = new LinkedHashMap<>();

    /** One entry per placed element: what the op's coordinates became in user space (§3.4). */
    private final List<Map<String, Object>> geometry = new ArrayList<>();

    /** Number of pages this call added (bounded by {@link PdfEditLimits#MAX_PAGES_ADDED}). */
    private int pagesAdded;

    /** The call's {@code dates.created} option, or {@code null} (then no date is written at all). */
    private final Calendar created;

    /** The call's {@code dates.modified} option ({@code /M} of annotations comes from here). */
    private final Calendar modified;

    /** Whether the page tree was restructured (which an incremental save cannot express). */
    private boolean pageTreeChanged;

    /**
     * Creates the context of a call.
     * @param document the document
     * @param sourceName the input's display name, {@code null} when the document was created
     * @param created the {@code dates.created} option (may be {@code null})
     * @param modified the {@code dates.modified} option (may be {@code null})
     */
    PdfEditContext(PDDocument document, String sourceName, Calendar created, Calendar modified) {
        this.document = document;
        this.sourceName = sourceName;
        this.created = created;
        this.modified = modified;
    }

    /** @return the document being written */
    PDDocument document() {
        return document;
    }

    /** @return the input's display name, {@code null} for a created document */
    String sourceName() {
        return sourceName;
    }

    /** @return the call's {@code dates.created} option, or {@code null} */
    Calendar created() {
        return created;
    }

    /** @return the call's {@code dates.modified} option ({@code /M} of annotations), or null */
    Calendar modified() {
        return modified;
    }

    /**
     * Adds a note to the answer.
     * @param note the sentence
     */
    void note(String note) {
        if (note != null && !note.isBlank() && !notes.contains(note)) {
            notes.add(note);
        }
    }

    /** @return the notes, in the order they were added */
    List<String> notes() {
        return notes;
    }

    /**
     * Records that an op changed something.
     * @param op the op
     * @param what what it did, in one phrase
     * @param details additional fields of the entry (may be empty)
     */
    void changed(PdfEditOp op, String what, Map<String, Object> details) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("op", op.name());
        entry.put("what", what);
        if (details != null) {
            entry.putAll(details);
        }
        changed.add(entry);
    }

    /** @return the changed entries */
    List<Map<String, Object>> changed() {
        return changed;
    }

    /**
     * Records the answer of a measuring op ({@code text.measure}) - nothing was written.
     * @param op the op
     * @param details the fields of the entry ({@code op} and {@code what} are added here)
     */
    void measured(PdfEditOp op, Map<String, Object> details) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("op", op.name());
        if (details != null) {
            entry.putAll(details);
        }
        measured.add(entry);
    }

    /** @return the entries of the measuring ops, in the order they were applied */
    List<Map<String, Object>> measurements() {
        return measured;
    }

    /**
     * Remembers a newly created indirect object under its {@code $n} handle.
     * @param handle the handle the caller used, e.g. {@code "$0"}
     * @param base the object
     */
    void putCreated(String handle, COSBase base) {
        createdObjects.put(handle, base);
    }

    /**
     * Resolves a {@code {$0: …}} reference to the object created under that handle.
     * @param handle the handle, e.g. {@code "$0"}
     * @return the object or {@code null} when no such object was created in this call
     */
    COSBase createdObject(String handle) {
        return createdObjects.get(handle);
    }

    /** @return the handles of all objects created in this call */
    Set<String> createdHandles() {
        return createdObjects.keySet();
    }

    /**
     * Marks a page dictionary as removed (identity based - page dictionaries are compared by
     * identity here, not by content).
     * @param pageDict the page dictionary
     */
    void markRemoved(COSDictionary pageDict) {
        removedPages.add(pageDict);
    }

    /**
     * Tests whether a page dictionary belongs to a page removed in this call.
     * @param pageDict the page dictionary, may be {@code null}
     * @return true if the page is (or is about to be) removed
     */
    boolean isRemoved(COSDictionary pageDict) {
        return pageDict != null && removedPages.contains(pageDict);
    }

    /** @return the page dictionaries marked as removed */
    Set<COSDictionary> removedPages() {
        return removedPages;
    }

    /**
     * Counts an added page and enforces the budget.
     */
    void countAddedPage() {
        pagesAdded++;
        if (pagesAdded > PdfEditLimits.MAX_PAGES_ADDED) {
            throw new PdfEditException("limit", "more than " + PdfEditLimits.MAX_PAGES_ADDED
                    + " pages added in one call",
                    "split the call into several ones - each result can be the next input");
        }
    }

    /** @return how many pages this call added */
    int pagesAdded() {
        return pagesAdded;
    }

    /**
     * Records what an op's coordinates became in default user space.
     * @param entry the echo of {@link PdfEditGeometry#echo}
     */
    void putGeometry(Map<String, Object> entry) {
        geometry.add(entry);
    }

    /** @return the geometry echoes of this call, in op order */
    List<Map<String, Object>> geometries() {
        return geometry;
    }

    /**
     * Looks up a font this call has already embedded.
     * @param key the source key, e.g. {@code "file:fonts/x.ttf|subset"}
     * @return the font, or {@code null} when it is not embedded yet
     */
    PDFont cachedFont(String key) {
        return fonts.get(key);
    }

    /**
     * Remembers a font this call embedded, so that the next op naming the same file shares it.
     * @param key the source key
     * @param font the font
     */
    void cacheFont(String key, PDFont font) {
        fonts.put(key, font);
    }

    /**
     * The {@code /Resources} dictionary of a page that this call may add entries to.
     * <p>
     * {@code /Resources} is <b>inheritable</b>: a page without its own dictionary gets the one of
     * its {@code /Pages} node, and adding a font there would make it appear on every sibling page.
     * So the page gets its own copy first - and the copy is a copy of the sub-dictionaries this
     * module writes into ({@code /Font}, {@code /XObject}, {@code /ExtGState}), because those are
     * shared just as often.
     * </p>
     * @param page the page
     * @return the page's own resources
     */
    PDResources ownResources(PDPage page) {
        COSDictionary pageDict = page.getCOSObject();
        PDResources known = ownResources.get(pageDict);
        if (known != null) {
            return known;
        }
        COSDictionary own = pageDict.getCOSDictionary(COSName.RESOURCES);
        PDResources target;
        if (own != null) {
            target = new PDResources(copyOf(own));
        } else {
            PDResources inherited = page.getResources();
            target = new PDResources(inherited == null ? new COSDictionary()
                    : copyOf(inherited.getCOSObject()));
            if (inherited != null) {
                note("page " + pageNumberOf(page) + " inherited its /Resources from the page"
                        + " tree; the call gave it its own copy, so the new font or image does"
                        + " not appear on the other pages");
            }
        }
        page.setResources(target);
        pageDict.setNeedToBeUpdated(true);
        ownResources.put(pageDict, target);
        return target;
    }

    /**
     * Copies a resources dictionary one level deeper for the sub-dictionaries this module adds to.
     * @param source the resources dictionary
     * @return the copy
     */
    private static COSDictionary copyOf(COSDictionary source) {
        COSDictionary copy = new COSDictionary(source);
        for (COSName key : SHARED_SUBDICTIONARIES) {
            COSDictionary sub = copy.getCOSDictionary(key);
            if (sub != null) {
                copy.setItem(key, new COSDictionary(sub));
            }
        }
        return copy;
    }

    /**
     * The 1-based number of a page.
     * @param page the page
     * @return the number, or 0 when the page is not in the page tree
     */
    private int pageNumberOf(PDPage page) {
        int index = document.getPages().indexOf(page);
        return index < 0 ? 0 : index + 1;
    }

    /** Marks the page tree as restructured. */
    void markPageTreeChanged() {
        pageTreeChanged = true;
    }

    /** @return whether pages were added, removed or moved */
    boolean pageTreeChanged() {
        return pageTreeChanged;
    }

    /**
     * Looks up the indirect object with the given number.
     * @param objNum the object number as {@code pdf.objects()} printed it
     * @return the object wrapper
     * @throws PdfEditException with code {@code out-of-range} when the document has no such object
     */
    COSObject object(int objNum) {
        if (objNum < 0) {
            throw new PdfEditException("out-of-range", "object number " + objNum,
                    "object numbers start at 1 - pdf.objects() lists the ones that exist");
        }
        COSObjectKey key = keyOf(objNum);
        if (key == null) {
            throw new PdfEditException("out-of-range", "the document has no object " + objNum,
                    "pdf.objects() lists the object numbers of this document");
        }
        return document.getDocument().getObjectFromPool(key);
    }

    /**
     * Finds the cross-reference key of an object number (an object may have a generation).
     * @param objNum the object number
     * @return the key or {@code null} when the number is not in the cross-reference
     */
    private COSObjectKey keyOf(int objNum) {
        COSObjectKey exact = new COSObjectKey(objNum, 0);
        if (document.getDocument().getXrefTable().containsKey(exact)) {
            return exact;
        }
        for (COSObjectKey key : document.getDocument().getXrefTable().keySet()) {
            if (key.getNumber() == objNum) {
                return key;
            }
        }
        return null;
    }
}
