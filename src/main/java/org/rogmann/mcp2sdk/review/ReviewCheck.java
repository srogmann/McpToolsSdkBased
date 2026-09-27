package org.rogmann.mcp2sdk.review;

/**
 * The outcome of one thing the tool promised to check - the row of a {@link AttributeKind#CHECKLIST}
 * attribute.
 * <p>
 * Deliberately a triple and nothing more: <em>how</em> an expectation is formulated and verified
 * stays with the tool (the Python workflow matches {@code exit 0}, {@code keine Zeile &lt;text&gt;},
 * {@code /regex/} against the complete log files; another tool compares file hashes or checks that
 * a diff stayed inside the declared targets). The review area only ever shows
 * "what was promised, was it met, and why not" - which is exactly what a reviewer needs to see
 * after the fact and nothing that could leak a tool's grammar into the page.
 * </p>
 * <p>
 * A check that <b>cannot be decided</b> must be reported as {@code met=false} with a detail that
 * says why ("NOT PROVABLE: the log was larger than the scan limit"), never as fulfilled. That
 * keeps a green run an artifact rather than a claim - the rule the Python workflow already follows.
 * </p>
 *
 * @param expectation what was promised, in the tool's own words
 * @param met         true only if the tool established that it holds
 * @param detail      short human-readable explanation of the outcome
 */
public record ReviewCheck(String expectation, boolean met, String detail) {

    /**
     * Compact constructor; normalizes the text parts.
     */
    public ReviewCheck {
        if (expectation == null || expectation.isBlank()) {
            throw new IllegalArgumentException("A review check needs the expectation it refers to.");
        }
        expectation = ReviewAttributes.abbreviate(expectation.trim(), ReviewAttributes.MAX_EXPECT_CHARS);
        detail = (detail == null) ? "" : detail.trim();
    }
}
