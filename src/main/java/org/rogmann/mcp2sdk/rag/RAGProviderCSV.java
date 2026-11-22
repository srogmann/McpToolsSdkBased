package org.rogmann.mcp2sdk.rag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A RAG source read from the semicolon-separated CSV database written by
 * {@link EmbeddingClient#save(java.io.File, boolean)}.
 * <p>
 * The CSV stores one line per indexed file (key = file path, chunk intervals and the
 * Base64-encoded vectors), but no text. The text is therefore resolved on demand from
 * the referenced file ({@link #text(EmbeddingChunk)}), which is exactly what
 * {@code EmbeddingMain} did before printing its results.
 * </p>
 * <p>
 * The CSV carries no model name: the model the database was built with has to be
 * given at registration, otherwise a search would silently compare vectors of two
 * different embedding models.
 * </p>
 */
public class RAGProviderCSV implements RAGProvider {

    /** Logger instance. */
    private static final Logger LOG = LoggerFactory.getLogger(RAGProviderCSV.class);

    /** The CSV database. */
    private final Path file;

    /** Topic of the source. */
    private final String topic;

    /** Name of the source. */
    private final String name;

    /** One-line description of the source. */
    private final String shortDescription;

    /** Embedding model the database was built with. */
    private final String model;

    /** Chunks after the first {@link #embeddings()} call. */
    private final AtomicReference<List<EmbeddingChunk>> loaded = new AtomicReference<>();

    /** Guards the one-time load. */
    private final ReentrantLock loadLock = new ReentrantLock();

    /** Warnings collected while loading. */
    private final AtomicReference<List<String>> warningRef = new AtomicReference<>(List.of());

    /**
     * Creates a provider for a CSV embedding database.
     *
     * @param file             the CSV file
     * @param topic            the topic the source belongs to
     * @param name             the name of the source
     * @param shortDescription a one-line description for {@code rag.help()}
     * @param model            the embedding model the database was built with
     */
    public RAGProviderCSV(Path file, String topic, String name, String shortDescription,
                          String model) {
        this.file = file;
        this.topic = requireText(topic, "topic");
        this.name = requireText(name, "name");
        this.shortDescription = shortDescription == null ? "" : shortDescription;
        this.model = requireText(model, "model");
    }

    @Override
    public String topic() {
        return topic;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String shortDescription() {
        return shortDescription;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public int dimension() {
        List<EmbeddingChunk> chunks = loaded.get();
        return chunks == null || chunks.isEmpty() ? 0 : chunks.get(0).dimension();
    }

    @Override
    public int chunkCount() {
        List<EmbeddingChunk> chunks = loaded.get();
        return chunks == null ? -1 : chunks.size();
    }

    @Override
    public Map<String, String> metadata() {
        return Map.of("format", "csv", "file", file.toString());
    }

    @Override
    public List<EmbeddingChunk> embeddings() throws IOException {
        List<EmbeddingChunk> chunks = loaded.get();
        if (chunks != null) {
            return chunks;
        }
        loadLock.lock();
        try {
            chunks = loaded.get();
            if (chunks == null) {
                chunks = load();
                loaded.set(chunks);
            }
            return chunks;
        } finally {
            loadLock.unlock();
        }
    }

    @Override
    public String text(EmbeddingChunk chunk) throws IOException {
        // A database variant may carry the texts itself (e.g. a glossary): then there is
        // nothing to read and no call to make.
        if (chunk.text() != null) {
            return chunk.text();
        }
        Path source = Path.of(chunk.referenceId());
        if (!Files.isRegularFile(source)) {
            return null;
        }
        String content = Files.readString(source, StandardCharsets.UTF_8);
        int start = Math.max(0, chunk.offsetStart());
        int end = chunk.offsetEnd() < 0 ? content.length() : Math.min(chunk.offsetEnd(), content.length());
        if (start >= end) {
            return chunk.offsetStart() < 0 ? content : "";
        }
        return content.substring(start, end);
    }

    @Override
    public List<String> warnings() {
        return warningRef.get();
    }

    /**
     * Loads the CSV database and converts its entries into chunks.
     *
     * @return the chunks
     * @throws IOException if the CSV cannot be read
     */
    private List<EmbeddingChunk> load() throws IOException {
        long started = System.nanoTime();
        EmbeddingClient client = new EmbeddingClient("unused://no-embedding-call", model);
        client.load(file.toFile());

        List<EmbeddingChunk> chunks = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (Map.Entry<String, EmbeddingClient.Entry> entry : client.entries().entrySet()) {
            String key = entry.getKey();
            EmbeddingClient.Entry value = entry.getValue();
            List<float[]> vectors = value.embeddings();
            List<int[]> intervals = value.intervals();
            if (vectors.size() != intervals.size()) {
                warnings.add("entry '" + key + "' has " + vectors.size() + " vectors but "
                        + intervals.size() + " intervals - the entry is skipped");
                continue;
            }
            Map<String, String> chunkMetadata = new LinkedHashMap<>();
            chunkMetadata.put(EmbeddingChunk.META_TITLE, fileNameOf(key));
            chunkMetadata.put(EmbeddingChunk.META_SOURCE, key);
            for (int i = 0; i < vectors.size(); i++) {
                int[] interval = intervals.get(i);
                chunks.add(new EmbeddingChunk(key, interval[0], interval[1], 0, model,
                        vectors.get(i), null, chunkMetadata));
            }
        }

        warningRef.set(capWarnings(warnings));
        LOG.info("Loaded RAG source '{}' ({}): {} chunks from CSV {} in {} ms",
                name, topic, chunks.size(), file, (System.nanoTime() - started) / 1_000_000L);
        return Collections.unmodifiableList(chunks);
    }

    /**
     * Returns the last path segment of a key, used as title.
     *
     * @param key the key (usually a file path)
     * @return the file name, or the key itself
     */
    private static String fileNameOf(String key) {
        try {
            Path path = Path.of(key);
            Path fileName = path.getFileName();
            return fileName != null ? fileName.toString() : key;
        } catch (RuntimeException e) {
            return key;
        }
    }

    /**
     * Caps a warning list at {@link RagLimits#maxWarnings()} entries.
     *
     * @param warnings the warnings
     * @return the capped, immutable list
     */
    private static List<String> capWarnings(List<String> warnings) {
        int max = RagLimits.current().maxWarnings();
        if (max <= 0) {
            return List.of();
        }
        if (warnings.size() <= max) {
            return List.copyOf(warnings);
        }
        List<String> capped = new ArrayList<>(warnings.subList(0, max));
        capped.add("... and " + (warnings.size() - max) + " more warnings");
        return List.copyOf(capped);
    }

    /**
     * Validates a required provider attribute.
     *
     * @param value the value
     * @param field the field name
     * @return the trimmed value
     */
    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("RAGProviderCSV: " + field + " must not be null or blank");
        }
        return value.trim();
    }

    @Override
    public String toString() {
        return "RAGProviderCSV{" + topic + "/" + name + ", file=" + file + ", model=" + model + "}";
    }
}
