package org.rogmann.mcp2sdk.rag;

/**
 * The knobs of one search call.
 *
 * @param maxHits      number of hits returned in total (at least 1)
 * @param maxPerSource largest number of hits one source may contribute before the merge;
 *                     {@code 0} means "no per-source limit" (the total {@code maxHits} still applies)
 * @param minScore     hits below this cosine score are dropped; {@code Double.NEGATIVE_INFINITY}
 *                     keeps everything
 * @param maxChars     longest text returned per hit; longer texts are cut and marked
 *                     {@link TextStatus#TRUNCATED}
 * @param withText     whether the text of the hits is resolved at all; {@code false} skips
 *                     every provider text call (and therefore every network access of it)
 */
public record RAGOptions(int maxHits, int maxPerSource, double minScore, int maxChars,
                         boolean withText) {

    /**
     * Validates the options.
     */
    public RAGOptions {
        if (maxHits < 1) {
            throw new IllegalArgumentException("maxHits must be at least 1, got: " + maxHits);
        }
        if (maxPerSource < 0) {
            throw new IllegalArgumentException("maxPerSource must not be negative, got: " + maxPerSource);
        }
        if (maxChars < 0) {
            throw new IllegalArgumentException("maxChars must not be negative, got: " + maxChars);
        }
    }

    /**
     * Returns the default options for a search.
     *
     * @param maxHits      number of hits
     * @param maxChars     longest text per hit
     * @return the options, without per-source limit, without minimum score, with text
     */
    public static RAGOptions defaults(int maxHits, int maxChars) {
        return new RAGOptions(maxHits, 0, Double.NEGATIVE_INFINITY, maxChars, true);
    }
}
