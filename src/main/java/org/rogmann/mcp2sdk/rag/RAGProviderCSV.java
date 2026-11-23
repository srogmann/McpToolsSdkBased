package org.rogmann.mcp2sdk.rag;

import java.io.IOException;
import java.io.Reader;
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
 * A RAG source read from the semicolon-separated CSV database of
 * {@link CsvEmbeddingStore}.
 * <p>
 * The database stores vectors and character intervals, but no text: the text is
 * resolved on demand from the referenced file ({@link #text(EmbeddingChunk)}), and only
 * for the hits that are actually returned. The read is streamed to the requested range,
 * so a hit in a large document costs the range, not the document.
 * </p>
 * <p>
 * Reading is strict ({@link CsvEmbeddingStore#read(Path)}): a malformed line or a
 * missing file aborts with file and line number. The CSV carries no model name, so the
 * model the database was built with has to be given at registration - otherwise a
 * search would silently compare vectors of two different embedding models.
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

    /**
     * Resolves the text of a chunk from the referenced file.
     * <p>
     * The offsets are character offsets (UTF-16 code units) of the decoded UTF-8 text, so
     * the file is decoded up to {@code offsetStart} and only the range itself is kept in
     * memory.
     * </p>
     * {@inheritDoc}
     */
    @Override
    public String text(EmbeddingChunk chunk) throws IOException {
        // A database variant may carry the texts itself (e.g. a glossary): then there is
        // nothing to read and no call to make.
        if (chunk.text() != null) {
            return chunk.text();
        }
        int start = chunk.offsetStart();
        int end = chunk.offsetEnd();
        if (start < 0 || end < 0 || start > end) {
            // Offsets unknown or contradictory: the source cannot say where the text is.
            return null;
        }
        if (start == end) {
            // A valid, empty span - different from "not resolvable".
            return "";
        }
        Path source = Path.of(chunk.referenceId());
        if (!Files.isRegularFile(source)) {
            return null;
        }
        return readRange(source, start, end);
    }

    /**
     * Reads a character range of a UTF-8 file without holding the whole file.
     *
     * @param source the file
     * @param start  the start offset (UTF-16 code units of the decoded text)
     * @param end    the end offset (exclusive)
     * @return the text, or {@code null} if the file ends before {@code end} (the index no
     *         longer matches the source)
     * @throws IOException if the file cannot be read
     */
    private static String readRange(Path source, int start, int end) throws IOException {
        try (Reader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            long remaining = start;
            while (remaining > 0) {
                long skipped = reader.skip(remaining);
                if (skipped <= 0) {
                    // skip() may return 0 at the end of the file; a single read tells.
                    if (reader.read() < 0) {
                        return null;
                    }
                    remaining--;
                } else {
                    remaining -= skipped;
                }
            }
            char[] buffer = new char[end - start];
            int filled = 0;
            while (filled < buffer.length) {
                int read = reader.read(buffer, filled, buffer.length - filled);
                if (read < 0) {
                    // The file is shorter than the index says: the chunk is stale.
                    return null;
                }
                filled += read;
            }
            return new String(buffer);
        }
    }

    /**
     * Loads the CSV database and converts its entries into chunks.
     *
     * @return the chunks
     * @throws IOException if the CSV cannot be read or is malformed
     */
    private List<EmbeddingChunk> load() throws IOException {
        long started = System.nanoTime();
        List<EmbeddingChunk> chunks = new ArrayList<>();

        for (CsvEmbeddingStore.Entry entry : CsvEmbeddingStore.read(file)) {
            Map<String, String> chunkMetadata = new LinkedHashMap<>(4);
            chunkMetadata.put(EmbeddingChunk.META_TITLE, fileNameOf(entry.key()));
            chunkMetadata.put(EmbeddingChunk.META_SOURCE, entry.key());
            List<float[]> vectors = entry.embeddings();
            List<int[]> intervals = entry.intervals();
            for (int i = 0; i < vectors.size(); i++) {
                int[] interval = intervals.get(i);
                chunks.add(new EmbeddingChunk(entry.key(), interval[0], interval[1], 0, model,
                        vectors.get(i), null, chunkMetadata));
            }
        }

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
