package org.rogmann.mcp2sdk.poi;

/**
 * Runtime exception for POI-related errors that can be shown to the user (LLM).
 * <p>
 * Instances of this exception contain user-friendly messages without exposing
 * internal file system paths. Detailed technical information is logged separately.
 * </p>
 */
public class PoiUserRuntimeException extends RuntimeException {

    /**
     * Creates a new PoiUserRuntimeException with a user-friendly message.
     * @param message description of the error suitable for the LLM
     */
    public PoiUserRuntimeException(String message) {
        super(message);
    }

    /**
     * Creates a new PoiUserRuntimeException with a user-friendly message and a cause.
     * @param message description of the error suitable for the LLM
     * @param cause the underlying cause (logged separately)
     */
    public PoiUserRuntimeException(String message, Throwable cause) {
        super(message, cause);
    }
}
