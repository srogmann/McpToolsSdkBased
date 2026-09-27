package org.rogmann.mcp2sdk.review;

import java.util.List;

/**
 * One decision-relevant fact of a request, as the review UI shows it.
 * <p>
 * Attributes are the generic part of a request: a tool does not pass "script, argv, cwd" to the
 * review area (that was the old, Python-shaped API), it passes <em>labelled, typed values</em>.
 * The label and the order come from the tool because a reviewer must read them in the order in
 * which the decision is made - not in map order.
 * </p>
 * <p>
 * <b>Nothing is silently dropped.</b> A value longer than
 * {@link ReviewAttributes#MAX_VALUE_CHARS} is shortened head+tail and reported with
 * {@link #truncated()} and {@link #valueBytes()} (the size of the <em>complete</em> value); the
 * complete value belongs with the request and is named by {@link #artifact()}. The same rule the
 * process logs already follow: the copy that travels to the browser is bounded, the record is not,
 * and the omission is always visible - so a shortened display can neither hide a problem nor fake
 * an approval.
 * </p>
 *
 * @param key        stable machine-readable name ({@code "script"}, {@code "argv"},
 *                   {@code "targets"}, {@code "exitCode"}); unique inside a request
 * @param label      human-readable heading shown in the table ({@code "Script"},
 *                   {@code "Arguments"})
 * @param kind       how to present it, see {@link AttributeKind}
 * @param value      the value to show, possibly shortened (never {@code null}); for
 *                   {@link AttributeKind#CHECKLIST} this is a one-line fallback ("2 of 3 met")
 *                   for places that only render text
 * @param truncated  true if {@code value} is shorter than the real value
 * @param valueBytes size (characters, or bytes for a {@link AttributeKind#LOG} value) the complete
 *                   value had
 * @param artifact   project-relative path of the file holding the complete value, or {@code null}
 *                   when {@code value} is complete
 * @param note       one-line hint rendered under the value ("3 lines, 120 bytes - complete output:
 *                   runs/&hellip;/stdout.txt"), may be empty
 * @param checks     the rows of a {@link AttributeKind#CHECKLIST} attribute (empty for every other
 *                   kind), see {@link ReviewCheck}
 */
public record ReviewAttribute(
        String key,
        String label,
        AttributeKind kind,
        String value,
        boolean truncated,
        long valueBytes,
        String artifact,
        String note,
        List<ReviewCheck> checks) {

    /**
     * Compact constructor enforcing the invariants the templates rely on.
     *
     * @throws IllegalArgumentException if the key is missing or a checklist has no rows
     */
    public ReviewAttribute {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("A review attribute needs a key.");
        }
        if (kind == null) {
            throw new IllegalArgumentException("Review attribute '" + key + "' needs a kind.");
        }
        if (label == null || label.isBlank()) {
            label = key;                       // a missing label must not empty a table header
        }
        if (value == null) {
            value = "";
        }
        if (valueBytes < 0) {
            valueBytes = value.length();
        }
        if (kind == AttributeKind.CHECKLIST && (checks == null || checks.isEmpty())) {
            throw new IllegalArgumentException("Checklist attribute '" + key
                    + "' needs at least one check row (or use TEXT for a plain sentence).");
        }
        checks = (checks == null) ? List.of() : List.copyOf(checks);
        note = (note == null) ? "" : note.trim();
        // An absent artifact is "nothing more to read", not an empty path: keeping "" would make
        // every attribute look like it had a file behind it.
        artifact = (artifact == null || artifact.isBlank()) ? null : artifact.trim();
        key = key.trim();
        label = ReviewAttributes.sanitize(label, ReviewAttributes.MAX_LABEL_CHARS);
    }

    /**
     * Attribute without artifact reference, note or check rows.
     *
     * @param key   machine-readable name
     * @param label heading for the UI
     * @param kind  presentation kind
     * @param value the value
     * @return the attribute
     */
    public static ReviewAttribute of(String key, String label, AttributeKind kind, String value) {
        return new ReviewAttribute(key, label, kind, value, false, -1L, null, "", List.of());
    }

    /**
     * Copy with a one-line hint below the value.
     *
     * @param text the hint (may be null or empty)
     * @return a copy of this attribute carrying the hint
     */
    public ReviewAttribute withNote(String text) {
        return new ReviewAttribute(key, label, kind, value, truncated, valueBytes, artifact,
                ReviewAttributes.sanitize(text, 300), checks);
    }

    /**
     * True when the complete value is bigger than what is shown here, or when the complete value
     * lives in a file.
     *
     * @return true if the reviewer is looking at an excerpt
     */
    public boolean hasMoreToRead() {
        return truncated || artifact != null;
    }
}
