package org.rogmann.mcp2sdk.rag;

import java.io.IOException;

/**
 * The embedding side of a RAG search: one text becomes one vector.
 * <p>
 * This is deliberately the narrowest possible seam. It is the method the search
 * calls exactly once per query, and it is the method a test replaces with a fake,
 * so that the whole search path (scope selection, merge, dedupe, scoring) can be
 * tested without a running embedding endpoint.
 * </p>
 * <p>
 * {@link EmbeddingClient} is the production implementation (an OpenAI-compatible
 * {@code /v1/embeddings} endpoint).
 * </p>
 */
public interface EmbeddingModel {

    /**
     * Computes the embedding of a text.
     *
     * @param id   a caller-defined identifier of the text, carried into the returned chunk
     *             (e.g. {@code "query"}); it has no influence on the embedding itself
     * @param text the text to embed
     * @return the chunk carrying the vector ({@link EmbeddingChunk#embeddingVector()}),
     *         the model name and the token count of the call
     * @throws IOException if the endpoint cannot be reached or answers with an error
     */
    EmbeddingChunk computeEmbedding(String id, String text) throws IOException;
}
