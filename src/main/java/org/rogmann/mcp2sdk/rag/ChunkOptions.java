 package org.rogmann.mcp2sdk.rag;

import java.util.Locale;
import java.util.function.Function;

/**
 * The parameters of the chunking - at exactly one place (M2).
 * <p>
 * Chunking is an <strong>indexing</strong> concern, not a search limit: it is consumed by
 * {@link TextChunker} and by whoever builds an index ({@link EmbeddingClient#computeChunks},
 * the command-line tool), never by a running search. That is why these values are not part
 * of {@link RagLimits} and not read by {@code RagConfiguration}.
 * </p>
 * <p>
 * The values are validated strictly: an unusable combination aborts with the property name
 * and the quoted value, instead of falling back to a default silently (AGENTS.md).
 * </p>
 *
 * @param maxChars largest chunk length in characters ({@code char} units of the decoded text)
 * @param overlap  characters a chunk shares with the one before it; {@code 0} means no overlap
 * @param lookback characters searched backwards for a sentence or line boundary near the end
 *                 of a chunk
 */
public record ChunkOptions(int maxChars, int overlap, int lookback) {

    /** Largest chunk length in characters. */
    public static final String PROP_MAX_CHARS = "mcp.rag.chunk.maxChars";
    /** Overlap between consecutive chunks in characters. */
    public static final String PROP_OVERLAP = "mcp.rag.chunk.overlap";
    /** Lookback window for a clean split point in characters. */
    public static final String PROP_LOOKBACK = "mcp.rag.chunk.lookback";

    /**
     * Validates the combination.
     */
    public ChunkOptions {
        if (maxChars <= 0) {
            throw new IllegalArgumentException(
                    "ChunkOptions: maxChars must be positive, got: " + maxChars);
        }
        if (overlap < 0) {
            throw new IllegalArgumentException(
                    "ChunkOptions: overlap must not be negative, got: " + overlap);
        }
        if (overlap >= maxChars) {
            throw new IllegalArgumentException("ChunkOptions: overlap (" + overlap
                    + ") must be smaller than maxChars (" + maxChars + ")");
        }
        if (lookback < 0) {
            throw new IllegalArgumentException(
                    "ChunkOptions: lookback must not be negative, got: " + lookback);
        }
    }

    /**
     * Returns the values the existing indexes were built with: 6000 characters per chunk,
     * 480 characters overlap, 240 characters lookback.
     *
     * @return the default options
     */
    public static ChunkOptions defaults() {
        return new ChunkOptions(6000, 480, 240);
    }

    /**
     * Reads the options through a property getter ({@code mcp.rag.chunk.*}), missing values
     * from {@link #defaults()}.
     *
     * @param get maps a property name to its value, or to {@code null}
     * @return the validated options
     * @throws IllegalArgumentException if a value is not a valid number or out of range
     */
    public static ChunkOptions from(Function<String, String> get) {
        ChunkOptions defaults = defaults();
        int maxChars = intProperty(get, PROP_MAX_CHARS, defaults.maxChars(), 1, 1000000,
                "a whole number of characters");
        int overlap = intProperty(get, PROP_OVERLAP, defaults.overlap(), 0, 1000000,
                "a whole number of characters");
        int lookback = intProperty(get, PROP_LOOKBACK, defaults.lookback(), 0, 1000000,
                "a whole number of characters");
        return new ChunkOptions(maxChars, overlap, lookback);
    }

    /**
     * Reads one integer property with a range check; an unreadable or out-of-range value aborts.
     *
     * @param get      property getter
     * @param key      property name
     * @param fallback value used when the property is absent
     * @param min      smallest accepted value
     * @param max      largest accepted value
     * @param what     description of the expected value
     * @return the value
     */
    private static int intProperty(Function<String, String> get, String key, int fallback,
                                   int min, int max, String what) {
        String value = get.apply(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String expected = what + " between " + min + " and " + max;
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < min || parsed > max) {
                throw invalidProperty(key, value, expected);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw invalidProperty(key, value, expected);
        }
    }

    /**
     * Builds the strict-validation error: names the property and quotes the offending value.
     *
     * @param key      property name
     * @param value    the offending value
     * @param expected what the property expects
     * @return the exception to throw
     */
    private static IllegalArgumentException invalidProperty(String key, String value,
                                                            String expected) {
        return new IllegalArgumentException(String.format(Locale.ROOT,
                "rag: property %s = '%s' is invalid - expected %s (refusing to guess a default)",
                key, value, expected));
    }
}
