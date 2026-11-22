package org.rogmann.mcp2sdk.rag;

/**
 * Says why a {@link RAGHit} carries the text it carries (or does not carry one).
 * <p>
 * A hit without text is not an error - the search result stays valid, and the
 * {@code source} reference lets a caller follow up on the original document. The
 * status exists so that a caller can tell the three cases apart instead of seeing
 * only {@code text == null}:
 * </p>
 */
public enum TextStatus {

    /** The text is present and complete. */
    AVAILABLE,

    /** The text was cut at the configured {@code maxChars} limit. */
    TRUNCATED,

    /** The provider could not deliver the text (timeout, network error, HTTP error). */
    UNAVAILABLE,

    /** The source carries no text and cannot obtain one (e.g. a CSV database without the source files). */
    NOT_PROVIDED
}
