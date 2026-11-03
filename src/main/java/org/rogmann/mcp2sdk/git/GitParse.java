package org.rogmann.mcp2sdk.git;

/**
 * Helpers for reading git's machine output - and the one rule they all follow: <b>an unexpected
 * shape is an error, not a silent default.</b>
 *
 * <p>
 * A parser that answers {@code null} for something it did not understand produces the worst kind
 * of result: the caller cannot tell "there is no number" from "this build does not understand the
 * output". {@code ahead: null} would then read as "nothing to push" although git said something
 * the parser did not expect - the same false green the diff engine refuses to produce with its
 * {@code notes}.
 * </p>
 *
 * <p>
 * Absence and unexpectedness are therefore different answers: a field git does <em>not</em> send
 * (no upstream, no tracking information) stays {@code null} at the call site, while a field git
 * <em>does</em> send in a shape this parser does not know raises
 * {@link IllegalArgumentException} with the offending token.
 * </p>
 */
final class GitParse {

    private GitParse() {
    }

    /**
     * Reads a decimal count from a git output field.
     *
     * @param value   the field as git wrote it (surrounding blanks are ignored)
     * @param context what is being parsed, for the error message (e.g. {@code branch.ab})
     * @return the number
     * @throws IllegalArgumentException if the field is not a decimal number
     */
    static int requireCount(String value, String context) {
        String text = (value == null) ? "" : value.strip();
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Unexpected value in git output (" + context
                    + "): '" + text + "' - the output format is not the one this parser knows.");
        }
    }

    /**
     * Reads a decimal number from a git output field (an epoch second does not fit into an
     * {@code int} beyond 2038, and a size does not either).
     *
     * @param value   the field as git wrote it (surrounding blanks are ignored)
     * @param context what is being parsed, for the error message
     * @return the number
     * @throws IllegalArgumentException if the field is not a decimal number
     */
    static long requireLong(String value, String context) {
        String text = (value == null) ? "" : value.strip();
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Unexpected value in git output (" + context
                    + "): '" + text + "' - the output format is not the one this parser knows.");
        }
    }
}
