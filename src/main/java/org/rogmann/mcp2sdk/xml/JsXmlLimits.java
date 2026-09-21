package org.rogmann.mcp2sdk.xml;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Limits of the {@code xml} module, overridable via system properties.
 * <p>
 * Every limit is read once at class initialization (properties are process-wide). Values
 * that cannot be parsed fall back to the default with a logged warning, so a typo in a
 * property never disables the guard silently.
 * </p>
 *
 * <table border="1">
 *   <caption>System properties</caption>
 *   <tr><th>Property</th><th>Default</th><th>Meaning</th></tr>
 *   <tr><td>{@code mcp.js.xml.maxBytes}</td><td>16 MiB</td>
 *       <td>largest XML document that is parsed into a DOM</td></tr>
 *   <tr><td>{@code mcp.js.xml.maxNodes}</td><td>1 000 000</td>
 *       <td>largest DOM (all node types)</td></tr>
 *   <tr><td>{@code mcp.js.xml.maxCachedBytes}</td><td>256 MiB</td>
 *       <td>sum of DOM document sizes kept open in one JavaScript call</td></tr>
 *   <tr><td>{@code mcp.js.xml.maxStreamBytes}</td><td>512 MiB</td>
 *       <td>largest source read by the streaming functions</td></tr>
 *   <tr><td>{@code mcp.js.xml.maxTextChars}</td><td>1 MiB</td>
 *       <td>absolute ceiling of a single {@code xml.text()} result</td></tr>
 * </table>
 */
final class JsXmlLimits {

    private static final Logger LOG = LoggerFactory.getLogger(JsXmlLimits.class);

    /** Prefix of all xml-module properties. */
    static final String PROP_PREFIX = "mcp.js.xml.";

    /** Largest document parsed into a DOM (bytes). */
    static final long MAX_BYTES = readLong("maxBytes", 16L * 1024 * 1024);

    /** Largest DOM (all node types, not only elements). */
    static final int MAX_NODES = readInt("maxNodes", 1_000_000);

    /** Sum of document bytes that may be kept open as DOM in one JavaScript call. */
    static final long MAX_CACHED_BYTES = readLong("maxCachedBytes", 256L * 1024 * 1024);

    /** Largest source read by the streaming functions (bytes). */
    static final long MAX_STREAM_BYTES = readLong("maxStreamBytes", 512L * 1024 * 1024);

    /** Absolute ceiling for one {@code xml.text()} result (characters). */
    static final int MAX_TEXT_CHARS = readInt("maxTextChars", 1024 * 1024);

    /** Largest accepted {@code max} option of the list-returning functions. */
    static final int HARD_RESULT_CAP = 10_000;

    /** Default result cap of {@code find}/{@code children}. */
    static final int DEFAULT_CAP_FIND = 200;

    /** Default result cap of {@code scan}. */
    static final int DEFAULT_CAP_SCAN = 500;

    /** Default result cap of {@code grep}. */
    static final int DEFAULT_CAP_GREP = 50;

    /** Default result cap of {@code sample}. */
    static final int DEFAULT_CAP_SAMPLE = 2;

    /** Default text preview length of descriptors (characters). */
    static final int DEFAULT_PREVIEW_CHARS = 400;

    /** Default text limit of {@code xml.text()} (non-strict, truncating). */
    static final int DEFAULT_TEXT_CHARS = 4000;

    /** Default of {@code profile.maxElements}. */
    static final int DEFAULT_MAX_ELEMENTS = 200;

    /** Default of {@code profile.maxAttrs}. */
    static final int DEFAULT_MAX_ATTRS = 50;

    /** Default depth of {@code tree}. */
    static final int DEFAULT_TREE_DEPTH = 4;

    /** Default line limit of {@code tree}. */
    static final int DEFAULT_TREE_LINES = 200;

    private JsXmlLimits() {
        // Utility class
    }

    /**
     * Reads a long property, falling back to {@code def} when absent or unparsable.
     */
    private static long readLong(String name, long def) {
        String raw = System.getProperty(PROP_PREFIX + name);
        if (raw == null || raw.isBlank()) {
            return def;
        }
        try {
            long value = Long.parseLong(raw.trim());
            if (value <= 0) {
                throw new NumberFormatException("must be positive");
            }
            return value;
        } catch (NumberFormatException e) {
            LOG.warn("Ignoring invalid {}={} (using {})", PROP_PREFIX + name, raw, def);
            return def;
        }
    }

    /**
     * Reads an int property, falling back to {@code def} when absent or unparsable.
     */
    private static int readInt(String name, int def) {
        long value = readLong(name, def);
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }
}
