package org.rogmann.mcp2sdk.rag;

/**
 * Signals that a {@link RAGScope} matches no registered source.
 * <p>
 * It extends {@link IllegalArgumentException} because it is a caller error, but it
 * carries its own type so that the JavaScript bridge can report {@code NOT_FOUND}
 * (the name does not exist) instead of {@code ARGUMENT_INVALID} (the value is unusable).
 * The message lists the sources that do exist.
 * </p>
 */
public class RagScopeException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message the message, naming the available sources
     */
    public RagScopeException(String message) {
        super(message);
    }
}
