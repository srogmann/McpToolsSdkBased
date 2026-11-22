package org.rogmann.mcp2sdk.rag;

import java.io.IOException;
import java.io.Serial;

/**
 * Signals that the query could not be embedded (endpoint unreachable, HTTP error,
 * timeout). It exists so that a caller can distinguish "the embedding service is
 * unavailable" ({@code NETWORK_ERROR}) from "a source file cannot be read"
 * ({@code USER_ERROR}) without matching on the message text.
 */
public class RagEmbeddingException extends IOException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message the message
     * @param cause   the cause
     */
    public RagEmbeddingException(String message, Throwable cause) {
        super(message, cause);
    }
}
