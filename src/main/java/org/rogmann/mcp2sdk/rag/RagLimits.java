package org.rogmann.mcp2sdk.rag;

import java.util.Locale;
import java.util.function.Function;

/**
 * The effective limits of the {@code rag} module - one place, read by the search,
 * by {@code rag.status()} and by {@code rag.help()}.
 * <p>
 * Every number is a property ({@code mcp.rag.*}), resolved exactly like the
 * {@code image} module's limits ({@link org.rogmann.mcp2sdk.image.ImageLimits}).
 * The values are validated <strong>strictly</strong>: a value that cannot be read or
 * is out of range aborts with the property name and the quoted value - a silent
 * fallback to a default would take far too long to notice (AGENTS.md).
 * </p>
 *
 * @param embedTimeoutMillis HTTP timeout of the query embedding call
 * @param textTimeoutMillis  HTTP timeout of one text resolution (one hit)
 * @param textBudgetMillis   total time one search may spend resolving texts
 * @param maxHits            largest accepted number of hits per search
 * @param maxCharsPerHit     longest text returned per hit
 * @param maxWarnings        largest number of warnings reported per search
 */
public record RagLimits(long embedTimeoutMillis, long textTimeoutMillis, long textBudgetMillis,
                        int maxHits, int maxCharsPerHit, int maxWarnings) {

    /** HTTP timeout of the query embedding call. */
    public static final String PROP_EMBED_TIMEOUT = "mcp.rag.embedTimeoutMillis";
    /** HTTP timeout of one text resolution. */
    public static final String PROP_TEXT_TIMEOUT = "mcp.rag.textTimeoutMillis";
    /** Total budget of all text resolutions of one search. */
    public static final String PROP_TEXT_BUDGET = "mcp.rag.textBudgetMillis";
    /** Largest accepted number of hits per search. */
    public static final String PROP_MAX_HITS = "mcp.rag.maxHits";
    /** Longest text returned per hit. */
    public static final String PROP_MAX_CHARS = "mcp.rag.maxCharsPerHit";
    /** Largest number of warnings reported per search. */
    public static final String PROP_MAX_WARNINGS = "mcp.rag.maxWarnings";

    /** The limits in effect (set once at start-up by McpConfig). */
    private static volatile RagLimits current = defaults();

    /**
     * Returns the built-in limits: 20 s embedding timeout, 5 s per text, 20 s text
     * budget (well below the 60 s script timeout of the JavaScript tool), 50 hits,
     * 2000 characters per hit, 10 warnings.
     *
     * @return the default limits
     */
    public static RagLimits defaults() {
        return new RagLimits(20000, 5000, 20000, 50, 2000, 10);
    }

    /**
     * Reads the limits through a property getter (the seam unit tests use).
     *
     * @param get maps a property name to its value, or to {@code null}
     * @return the validated limits
     * @throws IllegalArgumentException if a property is not a valid value
     */
    public static RagLimits from(Function<String, String> get) {
        RagLimits defaults = defaults();
        return new RagLimits(
                longProperty(get, PROP_EMBED_TIMEOUT, defaults.embedTimeoutMillis(), 100, 600000,
                        "a whole number of milliseconds"),
                longProperty(get, PROP_TEXT_TIMEOUT, defaults.textTimeoutMillis(), 100, 600000,
                        "a whole number of milliseconds"),
                longProperty(get, PROP_TEXT_BUDGET, defaults.textBudgetMillis(), 0, 600000,
                        "a whole number of milliseconds"),
                (int) longProperty(get, PROP_MAX_HITS, defaults.maxHits(), 1, 1000,
                        "a whole number of hits"),
                (int) longProperty(get, PROP_MAX_CHARS, defaults.maxCharsPerHit(), 1, 1000000,
                        "a whole number of characters"),
                (int) longProperty(get, PROP_MAX_WARNINGS, defaults.maxWarnings(), 0, 100,
                        "a whole number of warnings"));
    }

    /**
     * Returns the limits in effect.
     *
     * @return the current limits (the defaults when nobody initialised them)
     */
    public static RagLimits current() {
        return current;
    }

    /**
     * Publishes the limits for the running server (called once from {@code McpConfig}).
     *
     * @param limits the validated limits, {@code null} restores the defaults
     */
    public static void setCurrent(RagLimits limits) {
        current = limits != null ? limits : defaults();
    }

    /**
     * Reads one long property with a range check; an unreadable or out-of-range value aborts.
     *
     * @param get      property getter
     * @param key      property name
     * @param fallback value used when the property is absent
     * @param min      smallest accepted value
     * @param max      largest accepted value
     * @param what     description of the expected value for the error message
     * @return the value
     */
    private static long longProperty(Function<String, String> get, String key, long fallback,
                                     long min, long max, String what) {
        String value = get.apply(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String expected = what + " between " + min + " and " + max;
        try {
            long parsed = Long.parseLong(value.trim());
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
