package org.rogmann.mcp2sdk.image;

import org.rogmann.mcp2sdk.js.JsUserRuntimeException;
import org.springframework.core.env.Environment;

import java.util.Locale;
import java.util.function.Function;

/**
 * The effective limits of the {@code image} module - one place, read by the checks, by
 * {@code image.status()} and by {@code image.help()}.
 * <p>
 * Every number is a property ({@code mcp.js.image.*}, resolved through the Spring
 * {@link Environment}, so {@code application.yml}, {@code -D} and environment variables all
 * work). The values are validated <strong>strictly</strong>: a value that cannot be read or is
 * out of range aborts with the property name and the quoted value - a silent fallback to the
 * default would take far too long to notice (AGENTS.md). {@code McpConfig} resolves the limits
 * once at startup, so a bad value is a start error and not a surprise in the middle of a call.
 * </p>
 * <p>
 * {@link #defaults()} is the built-in set; it is also what unit tests use, because they do not
 * run a Spring context.
 * </p>
 *
 * @param maxInputBytes largest accepted encoded input (file, byte array, data URL) in bytes
 * @param maxOutputBytes largest accepted result of {@code encode}/{@code fromRaw}/{@code toDataUrl}
 * @param maxPixels pixels per image (decompression-bomb guard, checked before the decode)
 * @param rasterBudgetBytes raster memory of all open handles of one call
 * @param maxTransferPixels pixels of one {@code getPixels}/{@code putPixels} transfer
 * @param timeoutSeconds HTTP timeout of the image endpoints (smaller than the sandbox cap)
 */
public record ImageLimits(long maxInputBytes, long maxOutputBytes, long maxPixels,
                          long rasterBudgetBytes, long maxTransferPixels, int timeoutSeconds) {

    /** Largest accepted encoded input (file, byte array, data URL). */
    public static final String PROP_MAX_INPUT_BYTES = "mcp.js.image.maxInputBytes";
    /** Largest accepted result of {@code encode}/{@code fromRaw}/{@code toDataUrl}. */
    public static final String PROP_MAX_OUTPUT_BYTES = "mcp.js.image.maxOutputBytes";
    /** Pixels per image - the decompression-bomb guard. */
    public static final String PROP_MAX_PIXELS = "mcp.js.image.maxPixels";
    /** Raster memory of one call. */
    public static final String PROP_RASTER_BUDGET_BYTES = "mcp.js.image.rasterBudgetBytes";
    /** Pixels of one {@code getPixels}/{@code putPixels} transfer. */
    public static final String PROP_MAX_TRANSFER_PIXELS = "mcp.js.image.maxTransferPixels";
    /** HTTP timeout of the image endpoints in seconds. */
    public static final String PROP_TIMEOUT_SECONDS = "mcp.js.image.timeoutSeconds";

    /** The limits in effect for the running server (set once at startup by {@code McpConfig}). */
    private static volatile ImageLimits current = defaults();

    /**
     * The built-in limits, used when no property is set: 32 MiB in, 32 MiB out, 32 MP,
     * 256 MiB raster budget, 16 MP per transfer, 55 s HTTP timeout (the sandbox runs scripts
     * with 60 s by default, so an HTTP call must give up first).
     * @return the default limits
     */
    public static ImageLimits defaults() {
        return new ImageLimits(32L * 1024 * 1024, 32L * 1024 * 1024, 32L * 1024 * 1024,
                256L * 1024 * 1024, 16L * 1024 * 1024, 55);
    }

    /**
     * Reads the limits from a Spring environment (application.yml, {@code -D}, ENV).
     * @param environment the environment, or {@code null} to fall back to system properties
     * @return the validated limits
     * @throws JsUserRuntimeException if a property is not a valid value
     */
    public static ImageLimits from(Environment environment) {
        if (environment == null) {
            return from(System::getProperty);
        }
        return from(environment::getProperty);
    }

    /**
     * Reads the limits through a property getter (the seam unit tests use).
     * @param get maps a property name to its value, or to {@code null}
     * @return the validated limits
     * @throws JsUserRuntimeException if a property is not a valid value
     */
    public static ImageLimits from(Function<String, String> get) {
        ImageLimits d = defaults();
        return new ImageLimits(
                longProperty(get, PROP_MAX_INPUT_BYTES, d.maxInputBytes(), 1024,
                        4L * 1024 * 1024 * 1024, "a whole number of bytes"),
                longProperty(get, PROP_MAX_OUTPUT_BYTES, d.maxOutputBytes(), 1024,
                        4L * 1024 * 1024 * 1024, "a whole number of bytes"),
                longProperty(get, PROP_MAX_PIXELS, d.maxPixels(), 1, 1_000_000_000L,
                        "a whole number of pixels"),
                longProperty(get, PROP_RASTER_BUDGET_BYTES, d.rasterBudgetBytes(),
                        1024 * 1024, 16L * 1024 * 1024 * 1024, "a whole number of bytes"),
                longProperty(get, PROP_MAX_TRANSFER_PIXELS, d.maxTransferPixels(), 1,
                        1_000_000_000L, "a whole number of pixels"),
                (int) longProperty(get, PROP_TIMEOUT_SECONDS, d.timeoutSeconds(), 1, 3600,
                        "a whole number of seconds"));
    }

    /**
     * The limits in effect.
     * @return the current limits (the defaults when nobody initialised them, e.g. in unit tests)
     */
    public static ImageLimits current() {
        return current;
    }

    /**
     * Publishes the limits for the running server (called once from {@code McpConfig}).
     * @param limits the validated limits, {@code null} restores the defaults
     */
    public static void setCurrent(ImageLimits limits) {
        current = limits != null ? limits : defaults();
    }

    /**
     * Reads one long property with a range check; an unreadable or out-of-range value aborts.
     * @param get property getter
     * @param key property name
     * @param fallback value used when the property is absent
     * @param min smallest accepted value
     * @param max largest accepted value
     * @param what description of the expected value for the error message
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
     * The strict-validation error: names the property and quotes the offending value.
     * @param key property name
     * @param value the offending value
     * @param expected what the property expects
     * @return the exception to throw
     */
    private static JsUserRuntimeException invalidProperty(String key, String value,
                                                          String expected) {
        return new JsUserRuntimeException(String.format(Locale.ROOT,
                "image: property %s = '%s' is invalid - expected %s (refusing to guess a"
                        + " default)", key, value, expected));
    }
}
