package org.rogmann.mcp2sdk.js;

/**
 * Runtime exception for file-system-related errors that can be shown to the user (LLM).
 * <p>
 * Instances of this exception contain user-friendly messages without exposing
 * internal absolute file system paths. Detailed technical information is logged separately.
 * </p>
 */
public class JsUserRuntimeException extends RuntimeException {

    /**
     * Creates a new JsUserRuntimeException with a user-friendly message.
     * @param message description of the error suitable for the LLM
     */
    public JsUserRuntimeException(String message) {
        super(message);
    }

    /**
     * Creates a new JsUserRuntimeException with a user-friendly message and a cause.
     * @param message description of the error suitable for the LLM
     * @param cause the underlying cause (logged separately)
     */
    public JsUserRuntimeException(String message, Throwable cause) {
        super(message, cause);
    }
}
