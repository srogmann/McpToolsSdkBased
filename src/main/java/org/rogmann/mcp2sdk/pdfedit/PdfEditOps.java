package org.rogmann.mcp2sdk.pdfedit;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The op vocabulary of {@code pdfedit}: which ops exist, which are planned and where each one is
 * applied.
 * <p>
 * An op list is applied from front to back; every op sees the result of the one before it (which
 * is what makes {@code object.add} → {@code {ref:'$0'}} work). An op that this increment does not
 * implement is refused with its name and the increment it belongs to - a silent no-op would be the
 * worst answer, because the caller would save a document without the change.
 * </p>
 */
final class PdfEditOps {

    /** The ops of this module, in the order the help lists them. */
    static final List<String> IMPLEMENTED = List.of(
            "doc.info",
            "doc.version",
            "doc.outline",
            "doc.merge",
            "page.add",
            "page.remove",
            "page.move",
            "page.import",
            "page.set",
            "content.append",
            "content.prepend",
            "content.cut",
            "content.insert",
            "text.put",
            "text.block",
            "text.measure",
            "draw.rect",
            "draw.line",
            "image.put",
            "annot.add",
            "link.add",
            "form.fill",
            "form.flatten",
            "object.set",
            "object.add",
            "object.delete",
            "stream.set");

    /** Ops of the design that are not built yet, with the increment they belong to. */
    static final Map<String, String> PLANNED = planned();

    /**
     * Ops that restructure the page tree - an incremental update cannot express them.
     * <p>
     * {@code doc.merge} and {@code page.import} belong here as well: an appended revision that
     * changes the page tree would leave the old tree in the old revision.
     * </p>
     */
    private static final Set<String> STRUCTURAL =
            new LinkedHashSet<>(List.of("page.add", "page.remove", "page.move", "doc.merge",
                    "page.import"));

    /** The fields {@code doc.info} knows; every other name is an error. */
    private static final Set<String> INFO_FIELDS = new LinkedHashSet<>(List.of(
            "title", "author", "subject", "keywords", "creator", "producer"));

    private PdfEditOps() {
        // Utility class
    }

    private static Map<String, String> planned() {
        // E3 built the last two ops of the design (doc.merge, page.import); E4 is about tagged
        // PDF, encryption and signing - none of those is an op, so nothing is left to announce.
        return Map.of();
    }

    /**
     * Tests whether an op restructures the page tree.
     * @param name the op name
     * @return true for {@code page.add}, {@code page.remove}, {@code page.move},
     *         {@code page.import} and {@code doc.merge}
     */
    static boolean isStructural(String name) {
        return STRUCTURAL.contains(name);
    }

    /**
     * Applies the op list in order.
     * @param ctx the call context
     * @param ops the ops
     */
    static void apply(PdfEditContext ctx, List<PdfEditOp> ops) {
        for (PdfEditOp op : ops) {
            switch (op.name()) {
                case "doc.info" -> info(ctx, op);
                case "doc.version" -> version(ctx, op);
                case "doc.outline" -> PdfEditOutline.outline(ctx, op);
                case "doc.merge" -> PdfEditMerge.merge(ctx, op);
                case "page.add" -> PdfEditPages.add(ctx, op);
                case "page.remove" -> PdfEditPages.remove(ctx, op);
                case "page.move" -> PdfEditPages.move(ctx, op);
                case "page.import" -> PdfEditMerge.importPages(ctx, op);
                case "page.set" -> PdfEditPages.set(ctx, op);
                case "content.append" -> PdfEditRaw.contentStream(ctx, op, false);
                case "content.prepend" -> PdfEditRaw.contentStream(ctx, op, true);
                case "content.cut" -> PdfEditRaw.contentCut(ctx, op);
                case "content.insert" -> PdfEditRaw.contentInsert(ctx, op);
                case "text.put" -> PdfEditContent.textPut(ctx, op);
                case "text.block" -> PdfEditContent.textBlock(ctx, op);
                case "text.measure" -> PdfEditContent.textMeasure(ctx, op);
                case "draw.rect" -> PdfEditContent.drawRect(ctx, op);
                case "draw.line" -> PdfEditContent.drawLine(ctx, op);
                case "image.put" -> PdfEditContent.imagePut(ctx, op);
                case "annot.add" -> PdfEditAnnots.add(ctx, op);
                case "link.add" -> PdfEditAnnots.link(ctx, op);
                case "form.fill" -> PdfEditForms.fill(ctx, op);
                case "form.flatten" -> PdfEditForms.flatten(ctx, op);
                case "object.set" -> PdfEditRaw.objectSet(ctx, op);
                case "object.add" -> PdfEditRaw.objectAdd(ctx, op);
                case "object.delete" -> PdfEditRaw.objectDelete(ctx, op);
                case "stream.set" -> PdfEditRaw.streamSet(ctx, op);
                default -> throw unknown(op);
            }
        }
    }

    /**
     * Builds the error for an op name this module does not know.
     * @param op the op
     * @return the exception
     */
    private static PdfEditException unknown(PdfEditOp op) {
        String name = op.name();
        String plannedSince = PLANNED.get(name);
        if (plannedSince != null) {
            return new PdfEditException("unsupported", op.where() + " is designed but not built"
                    + " yet",
                    "the op belongs to increment " + plannedSince + "; pdfedit.help() lists what"
                            + " works today, and pdf.operations()/object.set cover the raw way");
        }
        return PdfEditException.usage(op.where() + ": unknown op '" + name + "'",
                "known ops: " + String.join(", ", IMPLEMENTED) + " - pdfedit.help() shows an"
                        + " example per op");
    }

    // -------------------------------------------------------------------
    // doc.version
    // -------------------------------------------------------------------

    /**
     * Sets the PDF version of the document ({@code /Version} and the header line).
     * <p>
     * The version is a promise about which features the file may use, so the op only accepts what
     * the specification has: 1.0 to 1.7 and 2.0. Downgrading a file that already uses a newer
     * feature is not repaired here - {@code verify()} says whether the result still reads.
     * </p>
     * @param ctx the call context
     * @param op the op
     */
    private static void version(PdfEditContext ctx, PdfEditOp op) {
        op.allow("value");
        Double value = op.doubleOrNull("value");
        if (value == null) {
            throw PdfEditException.usage(op.where() + ": 'value' is missing",
                    "value is the PDF version, e.g. 1.7 or 2.0");
        }
        double version = Math.round(value * 10d) / 10d;
        if (version < 1.0 || version > 2.0) {
            throw PdfEditException.usage(op.where() + ": version is " + value,
                    "the PDF versions are 1.0 … 1.7 and 2.0");
        }
        float before = ctx.document().getVersion();
        float target = (float) version;
        if (target < before) {
            // PDFBox' setVersion() refuses a downgrade (it logs and returns), which would be
            // exactly the silent no-op this module must not have - so the two places that carry
            // the version are written here.
            PDDocumentCatalog catalog = ctx.document().getDocumentCatalog();
            if (target >= 1.4f) {
                catalog.setVersion(Float.toString(target));
            } else {
                catalog.getCOSObject().removeItem(COSName.VERSION);
            }
            catalog.getCOSObject().setNeedToBeUpdated(true);
        } else {
            ctx.document().setVersion(target);
        }
        // PDDocument.setVersion() leaves the header alone once it is 1.4 or newer (from there the
        // catalog is the place the specification reads), so a doc.version would be invisible in
        // %PDF-x.y - and pdf.structure().header.version is what a caller checks. Both places are
        // written, so they agree.
        ctx.document().getDocument().setVersion(target);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("version", version);
        details.put("before", (double) before);
        ctx.changed(op, "PDF version set to " + version, details);
        if (target < before) {
            ctx.note("doc.version lowered the version from " + before + " to " + version + ":"
                    + " the version entries are written, but nothing is converted - content that"
                    + " needs the newer version stays as it is, and verify() says whether the"
                    + " file still reads");
        }
    }

    // -------------------------------------------------------------------
    // doc.info
    // -------------------------------------------------------------------

    /**
     * Sets the document information ({@code /Info}).
     * <p>
     * Only the fields that are named are written: a document without dates keeps having no dates,
     * and a field that is not mentioned keeps its value. Dates are not a field of this op on
     * purpose - they come from the call's {@code dates} option, so that the answer can say what
     * was written.
     * </p>
     * @param ctx the call context
     * @param op the op
     */
    private static void info(PdfEditContext ctx, PdfEditOp op) {
        op.allow("set");
        Map<String, Object> values = op.map("set");
        Set<String> unknown = new TreeSet<>(values.keySet());
        unknown.removeAll(INFO_FIELDS);
        if (!unknown.isEmpty()) {
            throw PdfEditException.usage(op.where() + ": unknown field(s) " + unknown,
                    "doc.info knows " + new TreeSet<>(INFO_FIELDS) + "; dates come from the"
                            + " call's dates option, everything else from object.set");
        }
        PDDocumentInformation information = ctx.document().getDocumentInformation();
        List<String> written = new ArrayList<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String field = entry.getKey();
            Object value = entry.getValue();
            if (!(value instanceof String text)) {
                throw PdfEditException.usage(op.where() + ": " + field + " is "
                        + PdfEditOp.describe(value),
                        field + " is a string - an empty string clears the field");
            }
            switch (field.toLowerCase(Locale.ROOT)) {
                case "title" -> information.setTitle(text);
                case "author" -> information.setAuthor(text);
                case "subject" -> information.setSubject(text);
                case "keywords" -> information.setKeywords(text);
                case "creator" -> information.setCreator(text);
                case "producer" -> information.setProducer(text);
                default -> throw unknown(op);
            }
            written.add(field);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("fields", new ArrayList<Object>(written));
        ctx.changed(op, "document information set: " + String.join(", ", written), details);
    }
}
