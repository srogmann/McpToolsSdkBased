package org.rogmann.mcp2sdk.pdfedit;

import java.io.Serial;

/**
 * Error of the {@code pdfedit} module: a message for the caller plus a machine-readable
 * {@code code} and a {@code hint} that says what to do instead.
 * <p>
 * The message is the text a language model reads, so it carries the code and the hint itself
 * (the JavaScript tool only shows {@link #getMessage()}). The code names the class of the
 * failure - {@code encrypted}, {@code broken}, {@code unsupported}, {@code out-of-range},
 * {@code imbalanced}, {@code limit}, {@code readonly}, {@code usage} - and the hint names the
 * function or property that helps.
 * </p>
 */
public class PdfEditException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 20261106L;

    /** Error class, see the list in the class comment. */
    private final String code;

    /** What to do instead, a sentence for the caller. */
    private final String hint;

    /**
     * Creates an exception with a code and a hint.
     * @param code error class, e.g. {@code "out-of-range"}
     * @param message what went wrong, in the terms of the API
     * @param hint what helps instead
     */
    public PdfEditException(String code, String message, String hint) {
        super(format(code, message, hint));
        this.code = code;
        this.hint = hint;
    }

    /**
     * Creates an exception with a code, a hint and a cause.
     * @param code error class, e.g. {@code "broken"}
     * @param message what went wrong, in the terms of the API
     * @param hint what helps instead
     * @param cause technical cause (logged, not shown to the caller)
     */
    public PdfEditException(String code, String message, String hint, Throwable cause) {
        super(format(code, message, hint), cause);
        this.code = code;
        this.hint = hint;
    }

    /**
     * Builds the caller-facing message: message, code and hint in one line each.
     * @param code error class
     * @param message what went wrong
     * @param hint what helps instead
     * @return the message text
     */
    private static String format(String code, String message, String hint) {
        StringBuilder sb = new StringBuilder("pdfedit: ").append(message);
        sb.append(" [code: ").append(code).append(']');
        if (hint != null && !hint.isBlank()) {
            sb.append(" hint: ").append(hint);
        }
        return sb.toString();
    }

    /**
     * Gets the error class.
     * @return the code, e.g. {@code "limit"}
     */
    public String code() {
        return code;
    }

    /**
     * Gets the hint that says what to do instead.
     * @return the hint (never {@code null}, may be empty)
     */
    public String hint() {
        return hint;
    }

    /**
     * Convenience for a usage error (a wrong or missing op parameter).
     * @param message what is wrong
     * @param hint what the op expects
     * @return the exception
     */
    public static PdfEditException usage(String message, String hint) {
        return new PdfEditException("usage", message, hint);
    }
}
