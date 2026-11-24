package org.rogmann.mcp2sdk.js;

import java.io.Serial;

/**
 * Runtime exception for file-system-related errors that can be shown to the user (LLM).
 * <p>
 * Instances of this exception contain user-friendly messages without exposing
 * internal absolute file system paths. Detailed technical information is logged separately.
 * </p>
  * <p>
 * The optional code ({@link JsErrorCode}) tells the caller what kind of failure it is, so that a
 * script can react (retry, fix an argument, give up) without parsing the message.
 * </p>
 */
public class JsUserRuntimeException extends RuntimeException implements JsErrorCode {
    @Serial
    private static final long serialVersionUID = 20261122L;

    /** The stable code of this error, or null for an unclassified user error. */
    private final String code;

    /**
     * Creates a new JsUserRuntimeException with a user-friendly message.
     * @param message description of the error suitable for the LLM
     */
    public JsUserRuntimeException(String message) {
        this(null, message, null);
    }

    /**
     * Creates a new JsUserRuntimeException with a user-friendly message and a cause.
     * @param message description of the error suitable for the LLM
     * @param cause the underlying cause (logged separately)
     */
    public JsUserRuntimeException(String message, Throwable cause) {
        this(null, message, cause);
    }

    /**
     * Creates a new JsUserRuntimeException with an explicit code.
     * @param code one of the constants of {@link JsErrorCode}, or null for {@code USER_ERROR}
     * @param message description of the error suitable for the LLM
     */
    public JsUserRuntimeException(String code, String message) {
        this(code, message, null);
    }

    /**
     * Creates a new JsUserRuntimeException with an explicit code and a cause.
     * @param code one of the constants of {@link JsErrorCode}, or null for {@code USER_ERROR}
     * @param message description of the error suitable for the LLM
     * @param cause the underlying cause (logged separately)
     */
    public JsUserRuntimeException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    @Override
    public String getCode() {
        return code == null ? USER_ERROR : code;
    }
}
