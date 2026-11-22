package org.rogmann.mcp2sdk.poi;

import org.rogmann.mcp2sdk.js.JsErrorCode;

import java.io.Serial;

/**
 * Runtime exception for POI-related errors that can be shown to the user (LLM).
 * <p>
 * Instances of this exception contain user-friendly messages without exposing
 * internal file system paths. Detailed technical information is logged separately.
 * </p>
 * <p>
 * The optional code ({@link JsErrorCode}) tells the caller what kind of failure it is: a script can
 * retry after {@code NOT_FOUND}, has to fix its arguments after {@code ARGUMENT_INVALID} and cannot
 * do anything about {@code ACCESS_DENIED}. It appears as one {@code Error code: ...} line in the
 * JavaScript tool's error result.
 * </p>
 */
public class PoiUserRuntimeException extends RuntimeException implements JsErrorCode {

    @Serial
    private static final long serialVersionUID = 20261121L;

    /** The stable code of this error, or null for an unclassified user error. */
    private final String code;

    /**
     * Creates a new PoiUserRuntimeException with a user-friendly message.
     * @param message description of the error suitable for the LLM
     */
    public PoiUserRuntimeException(String message) {
        this(null, message, null);
    }

    /**
     * Creates a new PoiUserRuntimeException with a user-friendly message and a cause.
     * @param message description of the error suitable for the LLM
     * @param cause the underlying cause (logged separately)
     */
    public PoiUserRuntimeException(String message, Throwable cause) {
        this(null, message, cause);
    }

    /**
     * Creates a new PoiUserRuntimeException with an explicit code.
     * @param code one of the constants of {@link JsErrorCode}, or null for {@code USER_ERROR}
     * @param message description of the error suitable for the LLM
     */
    public PoiUserRuntimeException(String code, String message) {
        this(code, message, null);
    }

    /**
     * Creates a new PoiUserRuntimeException with an explicit code and a cause.
     * @param code one of the constants of {@link JsErrorCode}, or null for {@code USER_ERROR}
     * @param message description of the error suitable for the LLM
     * @param cause the underlying cause (logged separately)
     */
    public PoiUserRuntimeException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    @Override
    public String getCode() {
        return code == null ? USER_ERROR : code;
    }
}
