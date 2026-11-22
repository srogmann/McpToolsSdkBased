package org.rogmann.mcp2sdk.js;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.rogmann.mcp2sdk.rag.EmbeddingDb;
import org.rogmann.mcp2sdk.rag.RAGHit;
import org.rogmann.mcp2sdk.rag.RAGOptions;
import org.rogmann.mcp2sdk.rag.RAGProvider;
import org.rogmann.mcp2sdk.rag.RAGRegistry;
import org.rogmann.mcp2sdk.rag.RAGResult;
import org.rogmann.mcp2sdk.rag.RAGScope;
import org.rogmann.mcp2sdk.rag.RagEmbeddingException;
import org.rogmann.mcp2sdk.rag.RagLimits;
import org.rogmann.mcp2sdk.rag.RagScopeException;

/**
 * The logic behind the {@code rag} JavaScript module: semantic search in the RAG
 * sources registered in {@link RAGRegistry}.
 * <p>
 * Every method returns plain Maps and Lists ({@link GraalProxies} turns them into
 * JavaScript objects), and every failure is a {@link JsUserRuntimeException} with a
 * stable code - a script can branch on the code instead of matching a message.
 * </p>
 * <p>
 * Nothing here loads a source: the first {@code rag.search()} does, so a server start
 * does not depend on the size of the corpora.
 * </p>
 */
public final class JsRag {

    /** Default number of hits when the caller does not ask for a number. */
    private static final int DEFAULT_HITS = 8;

    private JsRag() {
        // utility class
    }

    /**
     * Returns the help text of the module, including the available sources. Sources
     * that share a description are listed together - the description is explained once.
     *
     * @return the help text
     */
    public static String help() {
        RagLimits limits = RagLimits.current();
        StringBuilder sb = new StringBuilder(2048);
        sb.append("""
                rag - semantic search in the RAG sources of this server

                The query is embedded once and compared with the chunk vectors of the
                registered sources (cosine similarity). A source is addressed by its
                topic (the shelf, e.g. "News") and its name (e.g. "DLF-News").

                Entry points
                  rag.help()
                      this text

                  rag.sources()
                      [{topic, name, description, model, dimension, chunks, metadata}]

                  rag.topics()
                      distinct topics, e.g. ["Java", "News"]

                  rag.status()
                      {enabled, embeddingModelConfigured, limits, sources}

                  rag.search(query[, options])
                      { hits: [{provider, title, source, score, textStatus, text,
                               startOffset, endOffset, numberTokens, metadata}],
                        counts: {providersSearched, chunksScanned, hits},
                        warnings: [] }
                      options:
                        topic          only sources of this topic
                        source         only this source (name)
                        n              number of hits (default %d, max %d)
                        maxPerSource   largest contribution of one source
                                       (default 0 = unlimited)
                        minScore       drop hits below this cosine, e.g. 0.6
                        maxChars       longest text per hit (default %d)
                        withText       false: do not resolve missing texts
                                       (no network access of the sources)

                A hit may carry no text: textStatus says why.
                  AVAILABLE     the text is complete
                  TRUNCATED     cut at maxChars
                  UNAVAILABLE   the source could not deliver it (timeout, error)
                  NOT_PROVIDED  the source carries no text
                The 'source' field points at the original document in every case.
                """.formatted(DEFAULT_HITS, limits.maxHits(), limits.maxCharsPerHit()));
        appendSources(sb);
        return sb.toString();
    }

    /**
     * Appends the available sources to a text; sources sharing a description are
     * grouped so that the description appears once.
     *
     * @param sb the target
     */
    private static void appendSources(StringBuilder sb) {
        List<RAGProvider> providers = RAGRegistry.get().providers();
        sb.append('\n').append("Sources\n");
        if (providers.isEmpty()) {
            sb.append("  (none registered)\n");
            return;
        }
        Map<String, List<String>> byDescription = new LinkedHashMap<>();
        for (RAGProvider provider : providers) {
            String description = provider.shortDescription() == null
                    ? "" : provider.shortDescription().trim();
            byDescription.computeIfAbsent(description, key -> new ArrayList<>())
                    .add(provider.topic() + "/" + provider.name());
        }
        for (Map.Entry<String, List<String>> entry : byDescription.entrySet()) {
            sb.append("  ").append(String.join(", ", entry.getValue())).append('\n');
            if (!entry.getKey().isEmpty()) {
                sb.append("      ").append(entry.getKey()).append('\n');
            }
        }
    }

    /**
     * Returns the registered sources.
     *
     * @return one map per source
     */
    public static List<Map<String, Object>> sources() {
        return RAGRegistry.get().sourceInfos();
    }

    /**
     * Returns the distinct topics of the registered sources.
     *
     * @return the topics, sorted
     */
    public static List<String> topics() {
        return RAGRegistry.get().topics();
    }

    /**
     * Returns the state of the module.
     *
     * @return a map with the sources and the effective limits
     */
    public static Map<String, Object> status() {
        RAGRegistry registry = RAGRegistry.get();
        RagLimits limits = RagLimits.current();
        Map<String, Object> limitMap = new LinkedHashMap<>();
        limitMap.put("embedTimeoutMillis", limits.embedTimeoutMillis());
        limitMap.put("textTimeoutMillis", limits.textTimeoutMillis());
        limitMap.put("textBudgetMillis", limits.textBudgetMillis());
        limitMap.put("maxHits", limits.maxHits());
        limitMap.put("maxCharsPerHit", limits.maxCharsPerHit());
        limitMap.put("maxWarnings", limits.maxWarnings());

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", Boolean.parseBoolean(
                System.getProperty(JsRagBridge.PROP_ENABLED, "true")));
        status.put("embeddingModelConfigured", registry.hasEmbeddingModel());
        status.put("limits", limitMap);
        status.put("sources", registry.sourceInfos());
        return status;
    }

    /**
     * Searches the registered sources.
     *
     * @param query   the query text
     * @param options the options ({@code topic}, {@code source}, {@code n},
     *                {@code maxPerSource}, {@code minScore}, {@code maxChars},
     *                {@code withText}); plain Java values, may be {@code null}
     * @return {@code {hits, counts, warnings}}
     */
    public static Map<String, Object> search(String query, Map<String, Object> options) {
        if (query == null || query.isBlank()) {
            throw new JsUserRuntimeException(JsErrorCode.ARGUMENT_INVALID,
                    "rag.search: query must not be empty");
        }
        Map<String, Object> opts = options != null ? options : Map.of();
        RagLimits limits = RagLimits.current();

        int n = intOption(opts, "n", DEFAULT_HITS, 1, limits.maxHits());
        int maxPerSource = intOption(opts, "maxPerSource", 0, 0, limits.maxHits());
        int maxChars = intOption(opts, "maxChars", limits.maxCharsPerHit(), 1, 1000000);
        double minScore = doubleOption(opts, "minScore", Double.NEGATIVE_INFINITY);
        boolean withText = booleanOption(opts, "withText", true);
        RAGScope scope = new RAGScope(stringOption(opts, "topic"), stringOption(opts, "source"));

        RAGOptions searchOptions = new RAGOptions(n, maxPerSource, minScore, maxChars, withText);

        try {
            EmbeddingDb db = RAGRegistry.get().db();
            RAGResult result = db.search(query, scope, searchOptions);
            return toMap(result);
        } catch (RagEmbeddingException e) {
            throw new JsUserRuntimeException(JsErrorCode.NETWORK_ERROR,
                    "rag.search: " + e.getMessage(), e);
        } catch (RagScopeException e) {
            throw new JsUserRuntimeException(JsErrorCode.NOT_FOUND,
                    "rag.search: " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new JsUserRuntimeException(JsErrorCode.ARGUMENT_INVALID,
                    "rag.search: " + e.getMessage(), e);
        } catch (IllegalStateException e) {
            throw new JsUserRuntimeException(JsErrorCode.USER_ERROR,
                    "rag.search: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new JsUserRuntimeException(JsErrorCode.USER_ERROR,
                    "rag.search: " + e.getMessage(), e);
        }
    }

    /**
     * Converts a search result into the JavaScript shape.
     *
     * @param result the result
     * @return the map
     */
    private static Map<String, Object> toMap(RAGResult result) {
        List<Object> hits = new ArrayList<>(result.hits().size());
        for (RAGHit hit : result.hits()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("provider", hit.provider());
            map.put("referenceId", hit.referenceId());
            map.put("title", hit.title());
            map.put("source", hit.source());
            map.put("score", Math.round(hit.score() * 10000.0) / 10000.0);
            map.put("startOffset", hit.startOffset());
            map.put("endOffset", hit.endOffset());
            map.put("numberTokens", hit.numberTokens());
            map.put("textStatus", hit.textStatus().name());
            map.put("text", hit.text());
            map.put("metadata", new LinkedHashMap<>(hit.metadata()));
            hits.add(map);
        }

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("providersSearched", result.providersSearched());
        counts.put("chunksScanned", result.chunksScanned());
        counts.put("hits", hits.size());

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("hits", hits);
        map.put("counts", counts);
        map.put("warnings", result.warnings());
        return map;
    }

    /**
     * Reads an integer option with a range check.
     *
     * @param options the options
     * @param key     the key
     * @param fallback the default
     * @param min     the smallest accepted value
     * @param max     the largest accepted value
     * @return the value
     */
    private static int intOption(Map<String, Object> options, String key, int fallback,
                                 int min, int max) {
        Object value = options.get(key);
        if (value == null) {
            return fallback;
        }
        int parsed;
        if (value instanceof Number number) {
            parsed = number.intValue();
        } else {
            try {
                parsed = Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException e) {
                throw new JsUserRuntimeException(JsErrorCode.ARGUMENT_INVALID,
                        "rag.search: option '" + key + "' must be a number, got: '" + value + "'");
            }
        }
        if (parsed < min || parsed > max) {
            throw new JsUserRuntimeException(JsErrorCode.ARGUMENT_INVALID,
                    "rag.search: option '" + key + "' must be between " + min + " and " + max
                            + ", got: " + parsed);
        }
        return parsed;
    }

    /**
     * Reads a double option.
     *
     * @param options  the options
     * @param key      the key
     * @param fallback the default
     * @return the value
     */
    private static double doubleOption(Map<String, Object> options, String key, double fallback) {
        Object value = options.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new JsUserRuntimeException(JsErrorCode.ARGUMENT_INVALID,
                    "rag.search: option '" + key + "' must be a number, got: '" + value + "'");
        }
    }

    /**
     * Reads a boolean option.
     *
     * @param options  the options
     * @param key      the key
     * @param fallback the default
     * @return the value
     */
    private static boolean booleanOption(Map<String, Object> options, String key, boolean fallback) {
        Object value = options.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = String.valueOf(value).trim();
        if ("true".equalsIgnoreCase(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text)) {
            return false;
        }
        throw new JsUserRuntimeException(JsErrorCode.ARGUMENT_INVALID,
                "rag.search: option '" + key + "' must be true or false, got: '" + value + "'");
    }

    /**
     * Reads a string option.
     *
     * @param options the options
     * @param key     the key
     * @return the value, or {@code null}
     */
    private static String stringOption(Map<String, Object> options, String key) {
        Object value = options.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
