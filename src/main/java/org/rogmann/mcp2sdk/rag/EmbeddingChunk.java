package org.rogmann.mcp2sdk.rag;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One embedded chunk of a RAG source: the vector plus everything needed to show
 * and cite the piece of text it stands for.
 * <p>
 * The record is the shared data model of the RAG providers (CSV, JSONL, classpath,
 * ZIP, ...) and of the search in {@link EmbeddingDb}.
 * </p>
 *
 * <h3>Contract</h3>
 * <ul>
 *   <li><b>Identity</b> of a chunk is {@code (provider, referenceId, offsetStart)} -
 *       not {@code equals()}. The record keeps the default record equality, which
 *       compares the {@code float[]} component by reference; two chunks with equal
 *       vectors are therefore never equal. Chunks must not be used as elements of a
 *       {@code HashSet} or as map keys. Deduplication is done by {@link EmbeddingDb}
 *       over the identity above.</li>
 *   <li><b>{@code embeddingVector}</b> is not copied (defensively) - a chunk with
 *       768 floats is the normal case and copying it would double the memory of a
 *       whole corpus. Callers must not modify the returned array.</li>
 *   <li><b>{@code text} may be {@code null}</b>: then the chunk is only the vector and
 *       the text is resolved on demand through {@link RAGProvider#text(EmbeddingChunk)}
 *       (a source file, a wiki page, ...). Only the returned hits are resolved, never
 *       all chunks of a source.</li>
 *   <li><b>{@code metadata}</b> is never {@code null} and never mutable. The keys
 *       {@link #META_TITLE} and {@link #META_SOURCE} are reserved: they are carried
 *       into {@link RAGHit#title()} and {@link RAGHit#source()}.</li>
 * </ul>
 *
 * @param referenceId     identifier of the referenced document/source (unique together
 *                        with {@code offsetStart}); never {@code null} or blank
 * @param offsetStart     start offset of the chunk inside the referenced text, or {@code -1}
 *                        if unknown (unit: UTF-16 code units, i.e. Java {@code char})
 * @param offsetEnd       end offset (exclusive), or {@code -1} if unknown
 * @param numberTokens    token count of the chunk (context budget), {@code 0} if unknown
 * @param model           embedding model the vector was computed with; never {@code null}
 * @param embeddingVector the embedding vector; never {@code null}, length = dimension
 * @param text            the chunk text, or {@code null} if it has to be resolved on demand
 * @param metadata        free-form per-chunk data, never {@code null}
 */
public record EmbeddingChunk(
        String referenceId,
        int offsetStart,
        int offsetEnd,
        int numberTokens,
        String model,
        float[] embeddingVector,
        String text,
        Map<String, String> metadata) {

    /** Reserved metadata key: document/section title, surfaced as {@link RAGHit#title()}. */
    public static final String META_TITLE = "title";

    /** Reserved metadata key: URL or path of the original source, surfaced as {@link RAGHit#source()}. */
    public static final String META_SOURCE = "source";

    /**
     * Compact canonical constructor: validates the required parts and makes the
     * metadata map immutable (with a message naming the offending key, instead of
     * the bare {@code NullPointerException} of {@code Map.copyOf}).
     */
    public EmbeddingChunk {
        if (referenceId == null || referenceId.isBlank()) {
            throw new IllegalArgumentException("EmbeddingChunk: referenceId must not be null or blank");
        }
        if (model == null) {
            throw new IllegalArgumentException(
                    "EmbeddingChunk " + referenceId + ": model must not be null");
        }
        if (embeddingVector == null || embeddingVector.length == 0) {
            throw new IllegalArgumentException(
                    "EmbeddingChunk " + referenceId + ": embeddingVector must not be null or empty");
        }
        if (offsetStart < -1 || offsetEnd < -1 || (offsetEnd >= 0 && offsetEnd < offsetStart)) {
            throw new IllegalArgumentException("EmbeddingChunk " + referenceId
                    + ": invalid offsets " + offsetStart + ".." + offsetEnd);
        }
        metadata = immutableMetadata(metadata, referenceId);
    }

    /**
     * Copies a metadata map into an unmodifiable insertion-ordered map.
     *
     * @param metadata the map, or {@code null}
     * @param ref      the reference id, for the error message
     * @return an unmodifiable map, never {@code null}
     */
    private static Map<String, String> immutableMetadata(Map<String, String> metadata, String ref) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, String> copy = new LinkedHashMap<>(metadata.size() * 2);
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException(
                        "EmbeddingChunk " + ref + ": metadata key must not be null or blank");
            }
            if (value == null) {
                throw new IllegalArgumentException(
                        "EmbeddingChunk " + ref + ": metadata value of '" + key + "' is null");
            }
            copy.put(key, value);
        }
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Returns the number of floats in the vector (its dimension).
     *
     * @return the dimension
     */
    public int dimension() {
        return embeddingVector.length;
    }

    /**
     * Returns a short representation that never prints the vector and never follows
     * a provider reference (the record has none) - a log line stays a log line.
     *
     * @return a short description
     */
    @Override
    public String toString() {
        return "EmbeddingChunk{" + referenceId
                + ", " + offsetStart + ".." + offsetEnd
                + ", model=" + model
                + ", dim=" + embeddingVector.length
                + (text == null ? ", text=on-demand" : ", text=" + text.length() + " chars")
                + "}";
    }
}
