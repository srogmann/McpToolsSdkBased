package org.rogmann.mcp2sdk.rag;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * Reads the {@code mcp.rag.*} configuration and publishes it: the limits, the source
 * registry, the embedding endpoint.
 * <p>
 * Called once from {@code McpConfig} at start-up. Everything is validated strictly -
 * an invalid value or an unreadable source registry aborts the start with the property
 * name (or file) and the quoted value, instead of surfacing on the first search
 * (AGENTS.md: never fall back to a default silently).
 * </p>
 * <p>
 * The sources themselves are not loaded here: a {@link RAGProvider} reads its chunks on
 * the first search, so the start does not depend on the size of the corpora.
 * </p>
 */
public final class RagConfiguration {

    /** Logger instance. */
    private static final Logger LOG = LoggerFactory.getLogger(RagConfiguration.class);

    /** Switch of the {@code rag} module. */
    public static final String PROP_ENABLED = "mcp.rag.enabled";
    /** Path of the source registry file (JSON). */
    public static final String PROP_SOURCES = "mcp.rag.sources";
    /** URL of the OpenAI-compatible embedding endpoint. */
    public static final String PROP_EMBEDDING_URL = "mcp.rag.embedding.url";
    /** Model name of the embedding endpoint. */
    public static final String PROP_EMBEDDING_MODEL = "mcp.rag.embedding.model";
    /** Optional bearer token of the embedding endpoint. */
    public static final String PROP_EMBEDDING_API_KEY = "mcp.rag.embedding.apiKey";

    /**
     * Single source for a quick trial, e.g. {@code -Dmcp.rag.localSource=embeddings.F32.csv}.
     * The type follows the extension ({@code .csv}, {@code .jsonl}); the name and the topic
     * are mandatory companions - nothing is derived from the file name.
     */
    public static final String PROP_LOCAL_SOURCE = "mcp.rag.localSource";
    /** Topic of the single source. */
    public static final String PROP_LOCAL_SOURCE_TOPIC = "mcp.rag.localSource.topic";
    /** Name of the single source. */
    public static final String PROP_LOCAL_SOURCE_NAME = "mcp.rag.localSource.name";
    /** Optional description of the single source. */
    public static final String PROP_LOCAL_SOURCE_DESCRIPTION = "mcp.rag.localSource.description";
    /**
     * Embedding model of the single source; default is {@link #PROP_EMBEDDING_MODEL}.
     * A CSV database carries no model name, so it has to come from here (or from the
     * embedding endpoint's model).
     */
    public static final String PROP_LOCAL_SOURCE_MODEL = "mcp.rag.localSource.model";

    private RagConfiguration() {
        // utility class
    }

    /**
     * Applies the RAG configuration of a Spring environment.
     *
     * @param environment the environment (application.yml, {@code -D}, ENV)
     * @throws IllegalStateException if a value is invalid or a source registry cannot be opened
     */
    public static void apply(Environment environment) {
        apply(environment::getProperty);
    }

    /**
     * Applies the RAG configuration through a property getter (the seam unit tests use).
     *
     * @param get maps a property name to its value, or to {@code null}
     * @throws IllegalStateException if a value is invalid or a source registry cannot be opened
     */
    public static void apply(Function<String, String> get) {
        if (!booleanProperty(get, PROP_ENABLED, true)) {
            LOG.info("RAG: module switched off ({} = false) - sources and endpoint are not touched",
                    PROP_ENABLED);
            return;
        }

        RagLimits limits = RagLimits.from(get);
        RagLimits.setCurrent(limits);

        String sources = trimToNull(get.apply(PROP_SOURCES));
        if (sources != null) {
            Path path = resolveInProject(sources);
            try {
                RAGRegistry.get().loadSources(path);
            } catch (IOException e) {
                throw new IllegalStateException("rag: property " + PROP_SOURCES + " = '" + sources
                        + "' cannot be loaded: " + e.getMessage(), e);
            }
        }

        String url = trimToNull(get.apply(PROP_EMBEDDING_URL));
        String model = trimToNull(get.apply(PROP_EMBEDDING_MODEL));
        if (url != null && model == null) {
            throw new IllegalStateException("rag: properties " + PROP_EMBEDDING_URL
                    + " and " + PROP_EMBEDDING_MODEL + " must be set together ("
                    + PROP_EMBEDDING_URL + "='" + url + "', " + PROP_EMBEDDING_MODEL
                    + "=null)");
        }
        if (model != null && url == null) {
            throw new IllegalStateException("rag: properties " + PROP_EMBEDDING_URL
                    + " and " + PROP_EMBEDDING_MODEL + " must be set together ("
                    + PROP_EMBEDDING_URL + "=null, " + PROP_EMBEDDING_MODEL
                    + "='" + model + "')");
        }

        // A single source for a quick trial, before the endpoint is published - it only
        // needs the model name, and a failure here must name the property, not the endpoint.
        applyLocalSource(get, model);

        if (url != null) {
            String apiKey = trimToNull(get.apply(PROP_EMBEDDING_API_KEY));
            EmbeddingClient client = new EmbeddingClient(url, model, apiKey,
                    Duration.ofMillis(limits.embedTimeoutMillis()));
            // The query path may only send what the search accepts (M4): one limit, one place.
            client.setMaxInputChars(limits.maxQueryChars());
            RAGRegistry.get().setEmbeddingModel(client);
        } else {
            LOG.info("RAG: no embedding endpoint configured ({} / {}) - rag.search() will"
                    + " report that a model is missing", PROP_EMBEDDING_URL, PROP_EMBEDDING_MODEL);
        }
        LOG.info("RAG: {} source(s) registered, embedding model '{}', endpoint {}",
                RAGRegistry.get().providers().size(), model == null ? "(none)" : model,
                url == null ? "(none)" : url);
    }

    /**
     * Registers the optional single source of {@link #PROP_LOCAL_SOURCE}.
     * <p>
     * The type follows the file extension ({@code .csv} or {@code .jsonl}); topic and name
     * are mandatory companions - deriving them from the file name would be a silent guess.
     * A companion property without the main property is refused, so a typo cannot leave the
     * source silently unregistered.
     * </p>
     *
     * @param get           property getter
     * @param embeddingModel the model of the embedding endpoint, or {@code null}
     * @throws IllegalStateException if a property is invalid or the source cannot be opened
     */
    private static void applyLocalSource(Function<String, String> get, String embeddingModel) {
        String value = trimToNull(get.apply(PROP_LOCAL_SOURCE));
        String topic = trimToNull(get.apply(PROP_LOCAL_SOURCE_TOPIC));
        String name = trimToNull(get.apply(PROP_LOCAL_SOURCE_NAME));
        if (value == null) {
            if (topic != null || name != null
                    || trimToNull(get.apply(PROP_LOCAL_SOURCE_MODEL)) != null) {
                throw new IllegalStateException("rag: properties " + PROP_LOCAL_SOURCE_TOPIC
                        + ", " + PROP_LOCAL_SOURCE_NAME + " and " + PROP_LOCAL_SOURCE_MODEL
                        + " are only valid together with " + PROP_LOCAL_SOURCE);
            }
            return;
        }
        if (topic == null) {
            throw new IllegalStateException("rag: property " + PROP_LOCAL_SOURCE_TOPIC
                    + " is missing for " + PROP_LOCAL_SOURCE + " = '" + value + "'");
        }
        if (name == null) {
            throw new IllegalStateException("rag: property " + PROP_LOCAL_SOURCE_NAME
                    + " is missing for " + PROP_LOCAL_SOURCE + " = '" + value + "'");
        }
        String description = trimToNull(get.apply(PROP_LOCAL_SOURCE_DESCRIPTION));
        String model = trimToNull(get.apply(PROP_LOCAL_SOURCE_MODEL));
        if (model == null) {
            model = embeddingModel;
        }
        Path path = resolveInProject(value);
        String lower = value.toLowerCase(Locale.ROOT);
        try {
            if (lower.endsWith(".csv")) {
                if (model == null) {
                    throw new IllegalStateException("rag: a CSV source carries no model name -"
                            + " set " + PROP_LOCAL_SOURCE_MODEL + " or " + PROP_EMBEDDING_MODEL
                            + " for " + PROP_LOCAL_SOURCE + " = '" + value + "'");
                }
                RAGRegistry.get().register(new RAGProviderCSV(path, topic, name,
                        description == null ? "" : description, model));
            } else if (lower.endsWith(".jsonl")) {
                RAGRegistry.get().register(new RAGProviderJSONL(path, topic, name,
                        description == null ? "" : description));
            } else {
                throw new IllegalStateException("rag: property " + PROP_LOCAL_SOURCE + " = '"
                        + value + "' has an unknown type - expected a .csv or .jsonl file");
            }
        } catch (IOException e) {
            throw new IllegalStateException("rag: property " + PROP_LOCAL_SOURCE + " = '"
                    + value + "' cannot be opened: " + e.getMessage(), e);
        }
        LOG.info("RAG: local source '{}/{}' registered from {} (lazy, loaded on first search)",
                topic, name, path);
    }

    /**
     * Reads a boolean property strictly ({@code true} or {@code false}).
     *
     * @param get      property getter
     * @param key      property name
     * @param fallback the default
     * @return the value
     * @throws IllegalStateException if the value is neither {@code true} nor {@code false}
     */
    private static boolean booleanProperty(Function<String, String> get, String key,
                                           boolean fallback) {
        String value = trimToNull(get.apply(key));
        if (value == null) {
            return fallback;
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalStateException("rag: property " + key + " = '" + value
                + "' is invalid - expected true or false");
    }

    /**
     * Resolves a relative path against the project base directory
     * ({@code IDE_PROJECT_DIR}), like the other path-valued properties of the server.
     *
     * @param value the configured path
     * @return the absolute path
     */
    private static Path resolveInProject(String value) {
        Path path = Path.of(value);
        if (path.isAbsolute()) {
            return path;
        }
        Path base = Path.of(System.getProperty("IDE_PROJECT_DIR", ".")).toAbsolutePath().normalize();
        return base.resolve(path).normalize();
    }

    /**
     * Returns the trimmed value, or {@code null} for an absent or blank one.
     *
     * @param value the value
     * @return the trimmed value or {@code null}
     */
    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
