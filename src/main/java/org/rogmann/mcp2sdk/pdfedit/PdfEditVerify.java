package org.rogmann.mcp2sdk.pdfedit;

import org.rogmann.mcp2sdk.pdf.PdfDocument;
import org.rogmann.mcp2sdk.pdf.PdfDocumentStructure;
import org.rogmann.mcp2sdk.pdf.PdfFileStructure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code pdfedit.verify()}: the result read back with the project's own PDF reader.
 * <p>
 * PDFBox does not check its own output, and the own reader ({@code org.rogmann.mcp2sdk.pdf}) is an
 * independent implementation of the same specification - so this is not a second truth but a
 * summary of the first one: the call sequence {@code structure}, {@code pages}, the content walk
 * of every page, and the object listing, with an {@code ok} in front. What it answers is the
 * mechanical part (is this a readable PDF at all, does the xref chain hold, does every content
 * stream walk, is every object readable); what it does <em>not</em> answer is whether the right
 * word was hit - that is {@code pdf.text()} and {@code pdf.placement()} on the result.
 * </p>
 */
final class PdfEditVerify {

    private PdfEditVerify() {
        // Utility class
    }

    /**
     * Verifies a PDF image.
     * @param displayName the name used in messages
     * @param data the bytes
     * @param strict true to turn every note into a problem
     * @return {@code {ok, problems, notes, metrics}} - or {@code {ok:null, reason}} when the
     *         result cannot be checked (an encrypted file)
     */
    static Map<String, Object> run(String displayName, byte[] data, boolean strict) {
        List<String> problems = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Map<String, Object> metrics = new LinkedHashMap<>();
        PdfDocument document;
        try {
            document = PdfDocument.open(displayName, data);
        } catch (RuntimeException e) {
            problems.add("the result cannot be read: " + e.getMessage());
            metrics.put("bytes", (long) data.length);
            return answer(false, problems, notes, metrics);
        }
        PdfFileStructure structure = document.structure();
        metrics.put("bytes", (long) data.length);
        if (structure.encryption() != null) {
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("ok", null);
            answer.put("reason", "encrypted");
            answer.put("notes", "the result is encrypted - the project's own reader does not"
                    + " decrypt, so pdfedit cannot check what it wrote");
            answer.put("metrics", metrics);
            return answer;
        }
        List<Object> sections = asList(document.structure().toMap().get("crossReference"));
        metrics.put("xrefSections", (long) sections.size());
        metrics.put("previousRevisions", (long) Math.max(0, sections.size() - 1));
        for (String warning : structure.warnings()) {
            notes.add("structure: " + warning);
        }
        int declared = document.declaredPageCount();
        List<String> pageNotes = new ArrayList<>();
        List<Map<String, Object>> pageListings = new ArrayList<>();
        int walked = 0;
        try {
            // One walk serves both questions: how many pages are there, and does every page have
            // a /PageLabels range (the check the merge repair answers for).
            pageListings = new PdfDocumentStructure(document).pageListings(
                    PdfEditLimits.MAX_VERIFY_PAGES, false, false, 0);
            for (Map<String, Object> page : pageListings) {
                if (page.get("page") != null) {
                    walked++;
                } else {
                    for (Object note : asList(page.get("notes"))) {
                        pageNotes.add(String.valueOf(note));
                    }
                }
            }
        } catch (RuntimeException e) {
            problems.add("the page tree cannot be walked: " + e.getMessage());
        }
        notes.addAll(pageNotes);
        metrics.put("pages", (long) walked);
        if (declared >= 0 && declared != walked) {
            problems.add("the page tree declares " + declared + " pages but " + walked
                    + " were walked");
        }
        checkPageLabels(pageListings, problems, metrics);
        checkContent(document, problems, notes);
        checkObjects(document, problems, notes, metrics);
        List<Map<String, Object>> orphans = document.orphanObjects();
        if (!orphans.isEmpty()) {
            notes.add(orphans.size() + " object(s) found outside the cross-reference"
                    + " (a damaged file or a broken save)");
        }
        metrics.put("orphanObjects", (long) orphans.size());
        if (strict) {
            problems.addAll(notes);
            notes.clear();
        }
        return answer(problems.isEmpty(), problems, notes, metrics);
    }

    /**
     * Walks the content of every page with the own reader - which resolves every {@code Tf} and
     * reports what it could not read.
     * @param document the document
     * @param problems the problems found so far
     * @param notes the notes found so far
     */
    private static void checkContent(PdfDocument document, List<String> problems,
                                     List<String> notes) {
        Map<String, Object> text;
        try {
            text = document.text(null, PdfEditLimits.MAX_VERIFY_PAGES);
        } catch (RuntimeException e) {
            problems.add("the content walk failed: " + e.getMessage());
            return;
        }
        List<Map<String, Object>> pages = asList(text.get("pages"));
        for (Map<String, Object> page : pages) {
            Object error = page.get("error");
            Object pageNum = page.get("page");
            if (error != null) {
                problems.add("page " + pageNum + ": " + error);
            }
            for (Object note : asList(page.get("notes"))) {
                notes.add("page " + pageNum + ": " + note);
            }
        }
        for (Object note : asList(text.get("notes"))) {
            notes.add("content: " + note);
        }
    }

    /**
     * Reads every cross-reference entry - an object the reader cannot parse is the failure mode a
     * visual check never sees.
     * @param document the document
     * @param problems the problems found so far
     * @param notes the notes found so far
     * @param metrics the metrics collected so far
     */
    private static void checkObjects(PdfDocument document, List<String> problems,
                                     List<String> notes, Map<String, Object> metrics) {
        List<Map<String, Object>> objects;
        try {
            objects = document.objects(PdfEditLimits.MAX_VERIFY_OBJECTS, false);
        } catch (RuntimeException e) {
            problems.add("the object listing failed: " + e.getMessage());
            metrics.put("objects", 0L);
            return;
        }
        metrics.put("objects", (long) objects.size());
        int unreadable = 0;
        int decodeErrors = 0;
        for (Map<String, Object> object : objects) {
            if ("unreadable".equals(object.get("kind"))) {
                unreadable++;
                problems.add("object " + object.get("objNum") + " cannot be read: "
                        + object.get("error"));
            }
            if (object.get("decodeError") != null) {
                decodeErrors++;
                problems.add("stream " + object.get("objNum") + " cannot be decoded: "
                        + object.get("decodeError"));
            }
        }
        metrics.put("unreadableObjects", (long) unreadable);
        metrics.put("undecodableStreams", (long) decodeErrors);
        if (objects.size() >= PdfEditLimits.MAX_VERIFY_OBJECTS) {
            notes.add("the object listing stopped at " + PdfEditLimits.MAX_VERIFY_OBJECTS
                    + " objects - the rest was not read");
        }
    }

    /**
     * Checks the {@code /PageLabels} tree against the pages that are there.
     * <p>
     * A label tree is a number tree of ranges, and the specification expects a range for the first
     * page (PDFBox writes one in every {@code new PDPageLabels(document)}). A merge that copies
     * the source's ranges to the append index leaves the destination's own pages without a range -
     * the pages then have no label at all, which is exactly what "die Nummern stimmen nicht" means.
     * {@code doc.merge}/{@code page.import} repair that; {@code verify()} is the independent half
     * that says whether the repair worked.
     * </p>
     * @param pages the page listing of the reader
     * @param problems the problems found so far
     * @param metrics the metrics collected so far
     */
    private static void checkPageLabels(List<Map<String, Object>> pages, List<String> problems,
                                        Map<String, Object> metrics) {
        int labelled = 0;
        int unlabelled = 0;
        int firstUnlabelled = 0;
        for (Map<String, Object> page : pages) {
            if (page.get("page") == null || Boolean.TRUE.equals(page.get("unreadable"))) {
                continue;
            }
            if (page.get("pageLabel") != null) {
                labelled++;
            } else {
                unlabelled++;
                if (firstUnlabelled == 0) {
                    firstUnlabelled = ((Number) page.get("page")).intValue();
                }
            }
        }
        metrics.put("labelledPages", (long) labelled);
        if (labelled == 0) {
            return;
        }
        if (unlabelled > 0) {
            problems.add("the /PageLabels tree covers " + labelled + " page(s) but not page "
                    + firstUnlabelled + " (and " + (unlabelled - 1) + " more): a page without a"
                    + " range has no label - doc.merge/page.import add a range at the first page");
        }
    }

    /**
     * Builds the answer map.
     * @param ok whether the checks passed
     * @param problems the problems
     * @param notes the notes
     * @param metrics the measured numbers
     * @return the answer
     */
    private static Map<String, Object> answer(boolean ok, List<String> problems,
                                              List<String> notes, Map<String, Object> metrics) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", ok);
        result.put("problems", new ArrayList<Object>(problems));
        result.put("notes", new ArrayList<Object>(notes));
        result.put("metrics", metrics);
        return result;
    }

    /**
     * Reads a list from a map value that may be absent.
     * @param value the value
     * @param <T> the element type
     * @return the list, empty when the value is not a list
     */
    @SuppressWarnings("unchecked")
    private static <T> List<T> asList(Object value) {
        if (value instanceof List<?> list) {
            return (List<T>) list;
        }
        return List.of();
    }
}
