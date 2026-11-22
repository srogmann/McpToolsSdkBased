package org.rogmann.mcp2sdk.rag;

/**
 * Selects the sources a search runs against: all of them, all of a topic, or one source.
 * <p>
 * Matching is exact (case-insensitive, trimmed) on the provider's {@link RAGProvider#topic()}
 * and {@link RAGProvider#name()}. Both may be {@code null}, which means "any".
 * </p>
 *
 * @param topic  the topic to search in, or {@code null} for all topics
 * @param source the source name to search in, or {@code null} for all sources
 */
public record RAGScope(String topic, String source) {

    /** A scope that matches every provider. */
    public static final RAGScope ALL = new RAGScope(null, null);

    /**
     * Indicates whether a provider belongs to this scope.
     *
     * @param provider the provider to test
     * @return true if the provider should be searched
     */
    public boolean matches(RAGProvider provider) {
        if (topic != null && !equalsNormalized(topic, provider.topic())) {
            return false;
        }
        return source == null || equalsNormalized(source, provider.name());
    }

    /**
     * Compares two names case-insensitively, ignoring surrounding whitespace.
     *
     * @param a first value
     * @param b second value
     * @return true if both are non-null and equal in that sense
     */
    private static boolean equalsNormalized(String a, String b) {
        return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
    }

    /**
     * Returns the scope as a human-readable filter, e.g. for warnings.
     *
     * @return a description like {@code "topic 'News'"} or {@code "all sources"}
     */
    public String describe() {
        if (topic == null && source == null) {
            return "all sources";
        }
        if (source == null) {
            return "topic '" + topic + "'";
        }
        if (topic == null) {
            return "source '" + source + "'";
        }
        return "source '" + source + "' in topic '" + topic + "'";
    }
}
