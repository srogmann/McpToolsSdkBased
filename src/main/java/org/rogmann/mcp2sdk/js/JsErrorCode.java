package org.rogmann.mcp2sdk.js;

/**
 * A user-facing error that carries a stable, machine-readable code.
 * <p>
 * The code exists so that a caller can branch on the <em>kind</em> of failure instead of matching
 * the prose of a message that is written for a human: a script may retry after
 * {@code NOT_FOUND}, must fix its arguments after {@code ARGUMENT_INVALID} and cannot retry an
 * {@code ACCESS_DENIED} at all. The message stays the same; the code is one additional line
 * ({@code Error code: <CODE>}) that the JavaScript tool puts next to it.
 * </p>
 * <p>
 * Codes are upper case with underscores and are part of the tool's contract - renaming one is a
 * breaking change (docs/js/modules.md, section 'Error codes').
 * </p>
 */
public interface JsErrorCode {

    /** The path is outside the permitted directories or leaves them through a symbolic link. */
    String ACCESS_DENIED = "ACCESS_DENIED";

    /** The caller passed an argument or an option that the function cannot use. */
    String ARGUMENT_INVALID = "ARGUMENT_INVALID";

    /** Wrong credentials (unknown user, wrong password/passphrase, wrong api key) */
    String AUTHENTICATION_ERROR = "AUTHENTICATION_ERROR";

    /** Missing authorization to access an entity or execute an operation */
    String AUTHORIZATION_ERROR = "AUTHORIZATION_ERROR";

    /** Bytes do not decode with the chosen charset, or the charset is unknown. */
    String ENCODING_INVALID = "ENCODING_INVALID";

    /** The script itself failed: syntax error, unknown identifier, uncaught exception. */
    String JS_SCRIPT_ERROR = "JS_SCRIPT_ERROR";

    /** The JavaScript syntax could not be parsed. */
    String JS_SYNTAX_ERROR = "JS_SYNTAX_ERROR";

    /** The script was cancelled because it exceeded its timeout. */
    String JS_TIMEOUT = "JS_TIMEOUT";

    /** An identifier does not exist in the sandbox (typo, or a module that is not bound). */
    String JS_UNKNOWN_IDENTIFIER = "JS_UNKNOWN_IDENTIFIER";

    /** Arrival at some limit (maximum number of bytes, entities, ...) */
    String LIMIT_ERROR = "LIMIT_ERROR";

    /** network error */
    String NETWORK_ERROR = "NETWORK_ERROR";

    /** The addressed object does not exist (file, handle, paragraph, table, comment, ...). */
    String NOT_FOUND = "NOT_FOUND";

    /** The document carries no style with that id/name. */
    String STYLE_UNKNOWN = "STYLE_UNKNOWN";

    /** Some language/dsl error in a document */
    String SYNTAX_ERROR = "SYNTAX_ERROR";

    /** timeout reached */
    String TIMEOUT_ERROR = "TIMEOUT_ERROR";

    /** Some error without specific classification */
    String UNCATEGORIZED_ERROR = "UNCATEGORIZED_ERROR";

    /** Unknown type of an entity or object */
    String UNKNOWN_TYPE_ERROR = "";

    /** Code of a user error without a more specific classification. */
    String USER_ERROR = "USER_ERROR";

    /**
     * Gets the stable code of this error.
     * @return an upper-case code, e.g. {@code ARGUMENT_INVALID}
     */
    String getCode();
}
