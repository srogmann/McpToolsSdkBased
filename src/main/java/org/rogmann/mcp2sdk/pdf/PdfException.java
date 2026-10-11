package org.rogmann.mcp2sdk.pdf;

import java.io.Serial;

/**
 * Runtime exception of the PDF reader with a message suitable for the caller (LLM).
 * <p>
 * Messages describe the problem in terms of the PDF file (byte offset, object number,
 * filter name) and never expose an absolute file system path - the caller only ever
 * uses project-relative paths, and those are already part of the message.
 * </p>
 * <p>
 * The reader distinguishes two error classes:
 * </p>
 * <ul>
 *   <li><em>fatal</em>: the file is not a PDF at all (no {@code %PDF-} header, no usable
 *       cross-reference data) - reported as an exception.</li>
 *   <li><em>degradable</em>: a single object, stream or xref entry is damaged - reported
 *       as a {@code warnings} entry in the result so that the rest of the file stays
 *       readable. A warning is never silently dropped.</li>
 * </ul>
 */
public class PdfException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 20260920L;

    /**
     * Creates a new exception with a caller-friendly message.
     * @param message description of the problem (including offset/object number where known)
     */
    public PdfException(String message) {
        super(message);
    }

    /**
     * Creates a new exception with a caller-friendly message and a cause.
     * @param message description of the problem
     * @param cause underlying cause (for the server log)
     */
    public PdfException(String message, Throwable cause) {
        super(message, cause);
    }
}
