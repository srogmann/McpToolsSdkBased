package org.rogmann.mcp2sdk.rag;

import java.util.List;

/**
 * The result of a search over one or more RAG sources.
 * <p>
 * Besides the hits it reports how far the search reached ({@code providersSearched},
 * {@code chunksScanned}) and what went wrong on the way (a source that cannot be read,
 * a text that could not be fetched, duplicate chunk keys) - the same shape the
 * {@code search} module uses, because a silent skip is the failure a caller cannot see.
 * </p>
 *
 * @param hits              the hits, best first
 * @param providersSearched number of sources the scope matched
 * @param chunksScanned     number of chunks compared
 * @param model             the embedding model of the query (the model the scores belong to)
 * @param elapsedMillis     wall-clock time of the whole search
 * @param warnings          problems collected on the way (capped)
 */
public record RAGResult(List<RAGHit> hits, int providersSearched, long chunksScanned,
                        String model, long elapsedMillis, List<String> warnings) {

    /**
     * Compact canonical constructor: makes the lists immutable.
     */
    public RAGResult {
        hits = List.copyOf(hits);
        warnings = List.copyOf(warnings);
    }
}
