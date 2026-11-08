package org.rogmann.mcp2sdk.pdfedit;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code pdfedit.create()} and {@code pdfedit.edit()}: load or create a document, apply the ops,
 * write the result.
 * <p>
 * The two functions are the whole lifecycle of a call - nothing of it survives the call (no
 * handle, no registry). What they add to "apply and save" is the part that decides whether a
 * result is usable: the limits, the deterministic {@code /ID}, the dates, and the notes that say
 * what the save did to the file (an incremental update leaves the old revision in place, a full
 * save renumbers the objects).
 * </p>
 */
final class PdfEditDoc {

    private static final Logger LOGGER = LoggerFactory.getLogger(PdfEditDoc.class);

    private PdfEditDoc() {
        // Utility class
    }

    /**
     * Applies ops to an existing document.
     * @param displayName the input's display name (for messages and the verify pass)
     * @param input the input bytes
     * @param opsValue the op list as the caller passed it
     * @param options the parsed options
     * @return the answer of the call ({@code data} is the result's byte array)
     */
    static Map<String, Object> edit(String displayName, byte[] input, Object opsValue,
                                    PdfEditOptions options) {
        if (input.length > PdfEditLimits.MAX_INPUT_BYTES) {
            throw new PdfEditException("limit", displayName + " has " + input.length + " bytes",
                    "at most " + PdfEditLimits.MAX_INPUT_BYTES + " bytes are accepted");
        }
        if (options.incremental() && input.length > PdfEditLimits.MAX_INCREMENTAL_BYTES) {
            throw new PdfEditException("limit", displayName + " has " + input.length
                    + " bytes (incremental updates are limited to "
                    + PdfEditLimits.MAX_INCREMENTAL_BYTES + ")",
                    "an incremental update holds the old and the new revision in memory -"
                            + " use incremental:false or split the document");
        }
        List<PdfEditOp> ops = PdfEditOp.parse(opsValue);
        refuseStructuralIncremental(ops, options);
        try (PDDocument document = load(input, displayName)) {
            PdfEditContext ctx = new PdfEditContext(document, displayName,
                    options.created(), options.modified());
            PdfEditOps.apply(ctx, ops);
            applyDates(document, options);
            document.setDocumentId(documentId(input, ops, options));
            byte[] result = save(document, options, input.length);
            return answer(ctx, result, options, input.length);
        } catch (IOException e) {
            throw new PdfEditException("broken", displayName + ": the document could not be"
                    + " written: " + e.getMessage(),
                    "pdf.structure() shows what the reader sees; an unsupported object may be"
                            + " the cause", e);
        }
    }

    /**
     * Creates a new document from ops.
     * @param opsValue the op list as the caller passed it
     * @param options the parsed options
     * @return the answer of the call ({@code data} is the result's byte array)
     */
    static Map<String, Object> create(Object opsValue, PdfEditOptions options) {
        if (options.incremental()) {
            throw new PdfEditException("unsupported", "incremental:true on a created document",
                    "a created document has no previous revision to append to - incremental"
                            + " works on edit() only");
        }
        List<PdfEditOp> ops = PdfEditOp.parse(opsValue);
        try (PDDocument document = new PDDocument()) {
            PdfEditContext ctx = new PdfEditContext(document, null, options.created(),
                    options.modified());
            PdfEditOps.apply(ctx, ops);
            applyDates(document, options);
            document.setDocumentId(documentId(null, ops, options));
            if (document.getNumberOfPages() == 0) {
                ctx.note("the document has no pages - a viewer may refuse to open it; add one"
                        + " with {op:'page.add', size:'A4'}");
            }
            byte[] result = save(document, options, 0);
            return answer(ctx, result, options, 0);
        } catch (IOException e) {
            throw new PdfEditException("broken", "the document could not be written: "
                    + e.getMessage(),
                    "this is a fault of the writer, not of the ops - pdfedit.help() names the"
                            + " supported ops", e);
        }
    }

    /**
     * Loads a document.
     * @param input the bytes
     * @param displayName the display name
     * @return the document
     */
    private static PDDocument load(byte[] input, String displayName) {
        PDDocument document;
        try {
            document = Loader.loadPDF(input);
        } catch (InvalidPasswordException e) {
            throw new PdfEditException("encrypted", displayName + " is encrypted",
                    "decryption is not implemented (pdf.status().notImplemented) - remove the"
                            + " protection with another tool first", e);
        } catch (IOException e) {
            throw new PdfEditException("broken", displayName + " cannot be read: "
                    + e.getMessage(), "try pdf.structure() to see what the reader sees", e);
        } catch (RuntimeException e) {
            throw new PdfEditException("broken", displayName + " cannot be read: "
                    + e.getMessage(), "the file is probably not a PDF - pdf.structure() shows"
                    + " what the reader sees", e);
        }
        if (document.isEncrypted()) {
            closeQuietly(document);
            throw new PdfEditException("encrypted", displayName + " is encrypted",
                    "pdfedit writes unencrypted documents only; removing the protection is a"
                            + " separate step (E4)");
        }
        return document;
    }

    /**
     * An incremental update appends to the existing page tree; restructuring it there would leave
     * the old tree in the old revision and the new one in the new - so it is refused.
     * @param ops the ops
     * @param options the options
     */
    private static void refuseStructuralIncremental(List<PdfEditOp> ops, PdfEditOptions options) {
        if (!options.incremental()) {
            return;
        }
        for (PdfEditOp op : ops) {
            if (PdfEditOps.isStructural(op.name())) {
                throw new PdfEditException("unsupported", op.where() + ": a page-tree change"
                        + " cannot be saved incrementally",
                        "use incremental:false for page.add/page.remove/page.move - an"
                                + " incremental update is for changes that leave the tree alone");
            }
        }
    }

    /**
     * Writes the /CreationDate and /ModDate of the call's {@code dates} option (only when asked -
     * a document without dates keeps having none).
     * @param document the document
     * @param options the options
     */
    private static void applyDates(PDDocument document, PdfEditOptions options) {
        if (options.created() == null && options.modified() == null) {
            return;
        }
        PDDocumentInformation information = document.getDocumentInformation();
        if (options.created() != null) {
            information.setCreationDate(options.created());
        }
        if (options.modified() != null) {
            information.setModificationDate(options.modified());
        }
    }

    /**
     * Saves the document.
     * @param document the document
     * @param options the options
     * @param inputLength the input's size (for the buffer and the note)
     * @return the bytes
     * @throws IOException when PDFBox cannot write
     */
    private static byte[] save(PDDocument document, PdfEditOptions options, int inputLength)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, inputLength + 1024));
        if (options.incremental()) {
            document.saveIncremental(out);
        } else {
            document.save(out, options.compress()
                    ? CompressParameters.DEFAULT_COMPRESSION : CompressParameters.NO_COMPRESSION);
        }
        byte[] result = out.toByteArray();
        if (result.length > options.maxOutputBytes()) {
            throw new PdfEditException("limit", "the result would have " + result.length
                    + " bytes", "maxOutputBytes is " + options.maxOutputBytes()
                    + " - compress:true or a smaller document helps");
        }
        return result;
    }

    /**
     * Builds the answer of a call: the bytes, what changed, what was noted and the verification.
     * @param ctx the call context
     * @param result the written bytes
     * @param options the options
     * @param inputLength the input's size (0 for a created document)
     * @return the answer
     */
    private static Map<String, Object> answer(PdfEditContext ctx, byte[] result,
                                              PdfEditOptions options, int inputLength) {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("data", result);
        answer.put("bytes", (long) result.length);
        answer.put("bytesBefore", (long) inputLength);
        answer.put("changed", new ArrayList<Object>(ctx.changed()));
        if (!ctx.measurements().isEmpty()) {
            answer.put("measured", new ArrayList<Object>(ctx.measurements()));
        }
        if (!ctx.geometries().isEmpty()) {
            answer.put("geometry", new ArrayList<Object>(ctx.geometries()));
        }
        List<Object> notes = new ArrayList<>();
        if (options.incremental()) {
            notes.add("incremental: the previous revision (" + inputLength + " bytes) is still in"
                    + " the file, so removed content stays readable there - use"
                    + " incremental:false to purge it");
        } else if (ctx.sourceName() != null) {
            notes.add("full save: PDFBox writes the objects in traversal order, so the object"
                    + " numbers of the result differ from the input (`changed[].objNum` names the"
                    + " input object) and unreachable objects are dropped; incremental:true keeps"
                    + " the numbers");
        }
        notes.addAll(ctx.notes());
        if (options.compress()) {
            notes.add("compress:true packed the objects into object streams and wrote an xref"
                    + " stream - the own reader handles both, older tools may not");
        }
        answer.put("notes", notes);
        if (options.verify()) {
            answer.put("verify", PdfEditVerify.run("result", result, false));
        }
        return answer;
    }

    /**
     * The {@code /ID} seed: the caller's value, or a hash of input and ops - so that the same call
     * writes the same bytes (§6 of the design: no clock in the file).
     * @param input the input bytes, {@code null} for a created document
     * @param ops the ops
     * @param options the options
     * @return the id
     */
    private static long documentId(byte[] input, List<PdfEditOp> ops, PdfEditOptions options) {
        if (options.id() != null) {
            return options.id();
        }
        MessageDigest digest = sha256();
        digest.update((byte) (input == null ? 'c' : 'e'));
        if (input != null) {
            digest.update(input);
        }
        StringBuilder canonical = new StringBuilder();
        for (PdfEditOp op : ops) {
            canonical.append(op.name()).append('(');
            canonical(op.params(), canonical);
            canonical.append(")\n");
        }
        digest.update(canonical.toString().getBytes(StandardCharsets.UTF_8));
        return ByteBuffer.wrap(digest.digest()).getLong();
    }

    /**
     * A canonical text of a parameter value: same values in any order of keys produce the same
     * text, so that the derived {@code /ID} does not depend on how the caller built the object.
     * @param value the value
     * @param sb the text builder
     */
    private static void canonical(Object value, StringBuilder sb) {
        if (value == null) {
            sb.append("null;");
            return;
        }
        if (value instanceof String text) {
            sb.append('s').append(text.length()).append(':').append(text).append(';');
            return;
        }
        if (value instanceof Boolean flag) {
            sb.append("b:").append(flag).append(';');
            return;
        }
        if (value instanceof Number number) {
            double d = number.doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d)) {
                sb.append("i:").append(number.longValue()).append(';');
            } else {
                sb.append("f:").append(d).append(';');
            }
            return;
        }
        if (value instanceof byte[] data) {
            sb.append("x:").append(data.length).append(':')
                    .append(hex(sha256().digest(data))).append(';');
            return;
        }
        if (value instanceof List<?> list) {
            sb.append('[');
            for (Object item : list) {
                canonical(item, sb);
            }
            sb.append("];");
            return;
        }
        if (value instanceof Map<?, ?> map) {
            sb.append('{');
            List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
            entries.sort(Comparator.comparing(entry -> String.valueOf(entry.getKey())));
            for (Map.Entry<?, ?> entry : entries) {
                sb.append(entry.getKey()).append('=');
                canonical(entry.getValue(), sb);
            }
            sb.append("};");
            return;
        }
        sb.append("?:").append(value.getClass().getName()).append(';');
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    /**
     * Closes a document without letting a close error hide the original problem.
     * @param document the document
     */
    private static void closeQuietly(PDDocument document) {
        try {
            document.close();
        } catch (IOException e) {
            LOGGER.debug("pdfedit: closing the document failed", e);
        }
    }
}
