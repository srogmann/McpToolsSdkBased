package org.rogmann.mcp2sdk.rag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The registered RAG sources of this server and the search built over them.
 * <p>
 * The registry is the single place a source becomes reachable: the {@code rag}
 * JavaScript module binds to it, and a source is registered either programmatically
 * ({@link #register(RAGProvider)}) or through a source registry file
 * ({@link #loadSources(Path)}), which keeps the start-up configuration reviewable
 * outside the code.
 * </p>
 * <p>
 * {@link #db()} is built lazily and cached; registering a source or replacing the
 * embedding model invalidates it. Nothing here loads vectors - a provider loads its
 * chunks on its first search.
 * </p>
 */
public final class RAGRegistry {

    /** Logger instance. */
    private static final Logger LOG = LoggerFactory.getLogger(RAGRegistry.class);

    /** JSON mapper for the source registry file. */
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** The one registry of this process. */
    private static final RAGRegistry INSTANCE = new RAGRegistry();

    /** The registered sources, in registration order. */
    private final CopyOnWriteArrayList<RAGProvider> providers = new CopyOnWriteArrayList<>();

    /** The model that embeds the query, or {@code null} until configured. */
    private volatile EmbeddingModel embedder;

    /** The cached search, or {@code null} when it has to be rebuilt. */
    private volatile EmbeddingDb db;

    /** Guards the rebuild of {@link #db}. */
    private final ReentrantLock dbLock = new ReentrantLock();

    /**
     * Returns the registry of this process.
     *
     * @return the singleton
     */
    public static RAGRegistry get() {
        return INSTANCE;
    }

    /**
     * Registers a source. A second source with the same topic and name is refused -
     * the scope would otherwise address two sources at once.
     *
     * @param provider the source
     */
    public void register(RAGProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("RAGRegistry: provider must not be null");
        }
        for (RAGProvider existing : providers) {
            if (existing.topic().equalsIgnoreCase(provider.topic())
                    && existing.name().equalsIgnoreCase(provider.name())) {
                throw new IllegalArgumentException("RAGRegistry: source '" + provider.name()
                        + "' in topic '" + provider.topic() + "' is already registered");
            }
        }
        providers.add(provider);
        db = null;
        LOG.info("Registered RAG source '{}' ({}), model={}, dimension={}",
                provider.name(), provider.topic(), provider.model(), provider.dimension());
    }

    /**
     * Returns the registered sources.
     *
     * @return the sources, unmodifiable, in registration order
     */
    public List<RAGProvider> providers() {
        return List.copyOf(providers);
    }

    /**
     * Removes all sources and the cached search (for tests and for a configuration
     * reload).
     */
    public void clear() {
        providers.clear();
        db = null;
    }

    /**
     * Sets the model that embeds the query.
     *
     * @param model the model, or {@code null} to clear it
     */
    public void setEmbeddingModel(EmbeddingModel model) {
        this.embedder = model;
        this.db = null;
    }

    /**
     * Indicates whether an embedding model is configured.
     *
     * @return true if a search can embed its query
     */
    public boolean hasEmbeddingModel() {
        return embedder != null;
    }

    /**
     * Returns the search over the registered sources, building it on first use.
     *
     * @return the search
     * @throws IllegalStateException if no embedding model is configured
     */
    public EmbeddingDb db() {
        EmbeddingDb current = db;
        if (current != null) {
            return current;
        }
        dbLock.lock();
        try {
            current = db;
            if (current == null) {
                EmbeddingModel model = embedder;
                if (model == null) {
                    throw new IllegalStateException("rag: no embedding model configured"
                            + " - set mcp.rag.embedding.url and mcp.rag.embedding.model");
                }
                current = new EmbeddingDb(providers(), model, RagLimits.current());
                db = current;
            }
            return current;
        } finally {
            dbLock.unlock();
        }
    }

    /**
     * Returns the distinct topics of the registered sources, sorted.
     *
     * @return the topics
     */
    public List<String> topics() {
        Set<String> topics = new LinkedHashSet<>();
        providers.forEach(provider -> topics.add(provider.topic()));
        List<String> sorted = new ArrayList<>(topics);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        return sorted;
    }

    /**
     * Registers the sources of a source registry file (JSON).
     * <p>
     * Format:
     * </p>
     * <pre>
     * {
     *   "sources": [
     *     { "type": "jsonl", "path": "corpus/dlf.jsonl", "topic": "News",
     *       "name": "DLF-News", "description": "Deutschlandfunk news articles" },
     *     { "type": "csv", "path": "corpus/java.csv", "topic": "Java",
     *       "name": "Spring Documentation", "model": "nomic-embed-text-v1.5" }
     *   ]
     * }
     * </pre>
     * <p>
     * A relative {@code path} is resolved against the directory of the registry file.
     * An unknown type, a missing field or an unreadable header aborts with the file
     * and the entry that caused it - a source that silently does not exist is worse
     * than a start that fails.
     * </p>
     *
     * @param registryFile the registry file
     * @throws IOException if the file cannot be read or a source cannot be opened
     */
    public void loadSources(Path registryFile) throws IOException {
        String content = Files.readString(registryFile, StandardCharsets.UTF_8);
        JsonNode root;
        try {
            root = MAPPER.readTree(content);
        } catch (RuntimeException e) {
            throw new IOException(registryFile + ": not valid JSON: " + e.getMessage(), e);
        }
        JsonNode sources = root == null ? null : root.get("sources");
        if (sources == null || !sources.isArray()) {
            throw new IOException(registryFile + ": expected an object with an array 'sources'");
        }
        Path baseDir = registryFile.toAbsolutePath().getParent();
        int index = 0;
        for (JsonNode source : sources) {
            index++;
            String where = registryFile + ": source #" + index;
            String type = requiredText(source, "type", where);
            String topic = requiredText(source, "topic", where);
            String name = requiredText(source, "name", where);
            String description = optionalText(source, "description");
            String pathText = requiredText(source, "path", where);
            Path path = Path.of(pathText);
            if (!path.isAbsolute() && baseDir != null) {
                path = baseDir.resolve(path);
            }
            switch (type.toLowerCase(Locale.ROOT)) {
                case "jsonl" -> register(new RAGProviderJSONL(path, topic, name, description));
                case "csv" -> {
                    String model = requiredText(source, "model", where);
                    register(new RAGProviderCSV(path, topic, name, description, model));
                }
                default -> throw new IOException(where + ": unknown type '" + type
                        + "' - expected 'jsonl' or 'csv'");
            }
        }
        LOG.info("Loaded {} RAG source(s) from {}", index, registryFile);
    }

    /**
     * Reads a mandatory string field.
     *
     * @param node  the entry
     * @param field the field name
     * @param where description for the error message
     * @return the value
     * @throws IOException if the field is missing or blank
     */
    private static String requiredText(JsonNode node, String field, String where) throws IOException {
        String value = optionalText(node, field);
        if (value == null || value.isBlank()) {
            throw new IOException(where + ": field '" + field + "' is missing");
        }
        return value;
    }

    /**
     * Reads an optional string field.
     *
     * @param node  the entry
     * @param field the field name
     * @return the value, or {@code null}
     */
    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    /**
     * Returns a map of the registered sources for {@code rag.status()}.
     *
     * @return one entry per source
     */
    public List<Map<String, Object>> sourceInfos() {
        List<Map<String, Object>> infos = new ArrayList<>(providers.size());
        for (RAGProvider provider : providers) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("topic", provider.topic());
            info.put("name", provider.name());
            info.put("description", provider.shortDescription());
            info.put("model", provider.model());
            info.put("dimension", provider.dimension());
            info.put("chunks", provider.chunkCount());
            info.put("metadata", new LinkedHashMap<>(provider.metadata()));
            infos.add(info);
        }
        return infos;
    }
}
