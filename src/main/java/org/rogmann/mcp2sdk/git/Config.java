package org.rogmann.mcp2sdk.git;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reading of the module's system properties, with the validation the house rules demand: an
 * invalid value aborts with the property name <em>and</em> the value quoted instead of falling back
 * to a default - a silently used default takes days to notice, a message takes a minute.
 *
 * <p>
 * All properties of this module are read on use and never cached: the values are operator
 * decisions, and a test (or an operator changing {@code -D} between calls of an embedded server)
 * should see the value it set.
 * </p>
 */
public final class Config {

    private Config() {
    }

    /**
     * @param key the property name
     * @return the trimmed value, or {@code null} if unset or blank
     */
    public static String text(String key) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.strip();
    }

    /**
     * @param key          the property name
     * @param defaultValue the value returned when the property is unset
     * @return the trimmed value, or {@code defaultValue}
     */
    public static String text(String key, String defaultValue) {
        String value = text(key);
        return (value == null) ? defaultValue : value;
    }

    /**
     * @param key          the property name
     * @param defaultValue the value returned when the property is unset
     * @param min          smallest accepted value
     * @param max          largest accepted value
     * @return the value
     * @throws IllegalStateException if the property is set but not a number in the range
     */
    public static int intValue(String key, int defaultValue, int min, int max) {
        return (int) longValue(key, defaultValue, min, max);
    }

    /**
     * @param key          the property name
     * @param defaultValue the value returned when the property is unset
     * @param min          smallest accepted value
     * @param max          largest accepted value
     * @return the value
     * @throws IllegalStateException if the property is set but not a number in the range
     */
    public static long longValue(String key, long defaultValue, long min, long max) {
        String raw = text(key);
        if (raw == null) {
            return defaultValue;
        }
        long value;
        try {
            value = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw invalid(key, raw, "a number between " + min + " and " + max);
        }
        if (value < min || value > max) {
            throw invalid(key, raw, "a number between " + min + " and " + max);
        }
        return value;
    }

    /**
     * @param key          the property name
     * @param defaultValue the value returned when the property is unset
     * @return the value
     * @throws IllegalStateException if the property is set but neither {@code true} nor
     *         {@code false}
     */
    public static boolean bool(String key, boolean defaultValue) {
        String raw = text(key);
        if (raw == null) {
            return defaultValue;
        }
        String value = raw.toLowerCase(Locale.ROOT);
        if (!"true".equals(value) && !"false".equals(value)) {
            throw invalid(key, raw, "'true' or 'false'");
        }
        return Boolean.parseBoolean(value);
    }

    /**
     * Reads a comma-separated list.
     *
     * @param key the property name
     * @return the trimmed elements, empty entries dropped; empty list when unset
     */
    public static List<String> list(String key) {
        String raw = text(key);
        if (raw == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String part : raw.split(",")) {
            String value = part.strip();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }

    /**
     * @param key    the property name
     * @param raw    the value as configured
     * @param expect the expectation, e.g. {@code "a number between 1 and 300"}
     * @return the exception describing the invalid value
     */
    public static IllegalStateException invalid(String key, String raw, String expect) {
        return new IllegalStateException("Property '" + key + "' has invalid value '" + raw
                + "' (expected " + expect + ").");
    }
}
