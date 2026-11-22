package org.rogmann.mcp2sdk.rag;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * One searchable RAG source: a topic (the shelf, e.g. {@code "News"} or {@code "Java"}),
 * a name (the source, e.g. {@code "DLF-News"} or {@code "Spring Framework Documentation"})
 * and the embeddings of its chunks.
 * <p>
 * A provider is the answer to "where do the vectors come from?" - a semicolon-separated
 * CSV file ({@link RAGProviderCSV}), a JSONL file ({@link RAGProviderJSONL}), a
 * classpath resource, a ZIP entry, a remote index. The search itself lives in
 * {@link EmbeddingDb} and is written once for all providers.
 * </p>
 *
 * <h3>Lazy loading</h3>
 * <p>
 * The cheap facts - {@link #topic()}, {@link #name()}, {@link #shortDescription()},
 * {@link #model()}, {@link #dimension()}, {@link #chunkCount()}, {@link #metadata()} -
 * are answered without reading the vectors, so that a server start does not depend on
 * the size of the corpora. {@link #embeddings()} loads on first call, memoizes the
 * result and must be safe under concurrent first calls.
 * </p>
 *
 * <h3>Text on demand</h3>
 * <p>
 * A chunk does not have to carry its text: a CSV database references files, a wiki
 * corpus references pages. {@link #text(EmbeddingChunk)} resolves it - but only for
 * the few hits that are actually returned, never for all chunks of a source.
 * </p>
 */
public interface RAGProvider extends AutoCloseable {

    /**
     * Returns the topic of this source (the selection shelf, e.g. {@code "News"}).
     *
     * @return the topic, never {@code null} or blank
     */
    String topic();

    /**
     * Returns the name of this source (e.g. {@code "DLF-News"}), unique within its topic.
     *
     * @return the name, never {@code null} or blank
     */
    String name();

    /**
     * Returns a one-line description of what this source contains. It is shown once per
     * source in {@code rag.help()}, so an LLM can decide which source to search.
     *
     * @return a short description, never {@code null} (may be empty)
     */
    String shortDescription();

    /**
     * Returns the embedding model whose vectors this source stores.
     *
     * @return the model name, never {@code null}
     */
    String model();

    /**
     * Returns the dimension of the stored vectors without loading them, or {@code 0}
     * if it is only known after {@link #embeddings()} was called.
     *
     * @return the vector dimension, or {@code 0}
     */
    int dimension();

    /**
     * Returns the number of chunks without loading them, or {@code -1} if unknown.
     *
     * @return the chunk count, or {@code -1}
     */
    int chunkCount();

    /**
     * Returns free-form facts about the source (language, origin, license, creation date, ...).
     *
     * @return an unmodifiable map, never {@code null}
     */
    Map<String, String> metadata();

    /**
     * Returns the chunks of the source - on the first call the source is loaded.
     * The result is unmodifiable and in a stable (file) order, so that a search over
     * equal scores stays reproducible.
     *
     * @return the chunks, never {@code null}
     * @throws IOException if the source cannot be read
     */
    List<EmbeddingChunk> embeddings() throws IOException;

    /**
     * Resolves the text of a chunk.
     * <p>
     * Called only for the hits that are returned to the caller. A provider that has to
     * fetch the text (a wiki page, a source file) may perform network access here; it
     * must bound the call (timeout) and may return {@code null} - the hit then stays,
     * with {@link TextStatus#UNAVAILABLE}.
     * </p>
     * <p>
     * The default returns the text materialized in the chunk, so a provider whose
     * chunks carry their text needs no implementation.
     * </p>
     *
     * @param chunk the chunk to resolve
     * @return the text, or {@code null} if it cannot be delivered
     * @throws IOException if resolving fails (the caller turns this into
     *                     {@link TextStatus#UNAVAILABLE})
     */
    default String text(EmbeddingChunk chunk) throws IOException {
        return chunk.text();
    }

    /**
     * Returns warnings collected while loading (duplicate chunk keys, ignored lines, ...).
     * <p>
     * The search reports them once per call; a provider should cap the list (the
     * {@link EmbeddingDb} caps it again) so that a broken corpus does not flood the
     * tool output.
     * </p>
     *
     * @return the warnings, never {@code null}
     */
    default List<String> warnings() {
        return List.of();
    }

    /**
     * Releases resources of the source. The default does nothing - a provider that
     * holds an open file or a connection overrides it.
     *
     * @throws IOException if closing fails
     */
    @Override
    default void close() throws IOException {
        // nothing to release
    }
}
