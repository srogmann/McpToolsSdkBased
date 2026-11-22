package org.rogmann.mcp2sdk.rag;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The semantic search over a list of {@link RAGProvider}s.
 * <p>
 * One implementation for all sources: a provider contributes its chunks, the database
 * embeds the query once, scores every chunk, merges the sources, removes duplicates
 * and resolves the text of the few hits that are returned.
 * </p>
 * <p>
 * The low-level entry point {@link #search(float[], List, int)} works on a chunk list
 * only (that is the shape the search had in the fork this class comes from), the
 * high-level {@link #search(String, RAGScope, RAGOptions)} adds scope selection,
 * merging, de-duplication and the text budget.
 * </p>
 */
public final class EmbeddingDb {

    /** Logger instance. */
    private static final Logger LOG = LoggerFactory.getLogger(EmbeddingDb.class);

    /** Counter for readable thread names of the text resolution pool. */
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

    /**
     * Runs text resolutions so that a slow provider can be given up on after
     * {@link RagLimits#textTimeoutMillis()}. Daemon threads: a resolution that cannot be
     * cancelled never keeps the JVM alive.
     */
    private static final ExecutorService TEXT_EXECUTOR = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "rag-text-" + THREAD_COUNTER.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    /** The sources to search. */
    private final List<RAGProvider> providers;

    /** The model that embeds the query. */
    private final EmbeddingModel embedder;

    /** The effective limits. */
    private final RagLimits limits;

    /**
     * Creates the search over the given sources.
     *
     * @param providers the sources, in a stable order
     * @param embedder  the model that embeds the query
     * @param limits    the effective limits
     */
    public EmbeddingDb(List<RAGProvider> providers, EmbeddingModel embedder, RagLimits limits) {
        if (providers == null) {
            throw new IllegalArgumentException("EmbeddingDb: providers must not be null");
        }
        if (embedder == null) {
            throw new IllegalArgumentException("EmbeddingDb: embedder must not be null");
        }
        this.providers = List.copyOf(providers);
        this.embedder = embedder;
        this.limits = limits != null ? limits : RagLimits.defaults();
    }

    /**
     * Returns the sources of this database.
     *
     * @return the providers, unmodifiable
     */
    public List<RAGProvider> providers() {
        return providers;
    }

    // ---- Low level: score a chunk list ----

    /**
     * Scores a list of chunks against a query vector and returns the best ones.
     * <p>
     * This is the format-independent core of the search: it knows nothing about
     * sources, scopes or text.
     * </p>
     *
     * @param queryVector the query vector
     * @param chunks      the chunks to score
     * @param maxHits     the maximum number of results
     * @return the best chunks, best first
     */
    public List<ScoredChunk> search(float[] queryVector, List<EmbeddingChunk> chunks, int maxHits) {
        if (queryVector == null || queryVector.length == 0) {
            throw new IllegalArgumentException("search: queryVector must not be null or empty");
        }
        if (chunks == null) {
            throw new IllegalArgumentException("search: chunks must not be null");
        }
        if (maxHits < 1) {
            throw new IllegalArgumentException("search: maxHits must be at least 1, got: " + maxHits);
        }

        List<ScoredChunk> scored = new ArrayList<>(chunks.size());
        for (EmbeddingChunk chunk : chunks) {
            if (chunk.dimension() != queryVector.length) {
                throw new IllegalArgumentException("search: chunk '" + chunk.referenceId()
                        + "' has dimension " + chunk.dimension() + ", query has "
                        + queryVector.length + " - the source was built with another model");
            }
            scored.add(new ScoredChunk(chunk, VectorCodec.cosine(queryVector, chunk.embeddingVector())));
        }
        scored.sort(ScoredChunk.BEST_FIRST);
        if (scored.size() > maxHits) {
            return List.copyOf(scored.subList(0, maxHits));
        }
        return List.copyOf(scored);
    }

    // ---- High level: scope, merge, text ----

    /**
     * Searches the sources of the scope and returns the best hits.
     *
     * @param query   the query text
     * @param scope   the sources to search, {@code null} for all
     * @param options the knobs of this call, {@code null} for the defaults
     * @return the hits, best first, with warnings
     * @throws IOException              if the query cannot be embedded or a source cannot be read
     * @throws IllegalArgumentException if the scope matches no source, the selected sources use
     *                                  different models, or a chunk contradicts its source
     */
    public RAGResult search(String query, RAGScope scope, RAGOptions options) throws IOException {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("search: query must not be null or blank");
        }
        RAGScope effectiveScope = scope != null ? scope : RAGScope.ALL;
        RAGOptions effectiveOptions = options != null
                ? options
                : RAGOptions.defaults(8, limits.maxCharsPerHit());

        long started = System.nanoTime();
        List<RAGProvider> selected = select(effectiveScope);
        requireOneModel(selected, effectiveScope);

        EmbeddingChunk queryEmbedding;
        try {
            queryEmbedding = embedder.computeEmbedding("query", query);
        } catch (IOException e) {
            throw new RagEmbeddingException("query embedding failed: " + e.getMessage(), e);
        }
        float[] queryVector = queryEmbedding.embeddingVector();

        List<String> warnings = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();
        long chunksScanned = 0;
        int perSource = effectiveOptions.maxPerSource() > 0
                ? effectiveOptions.maxPerSource()
                : effectiveOptions.maxHits();

        for (RAGProvider provider : selected) {
            List<EmbeddingChunk> chunks = provider.embeddings();
            warnings.addAll(provider.warnings());
            chunksScanned += chunks.size();
            List<ScoredChunk> best = search(queryVector, chunks, perSource);
            for (ScoredChunk scored : best) {
                if (scored.score() >= effectiveOptions.minScore()) {
                    candidates.add(new Candidate(provider, scored.chunk(), scored.score()));
                }
            }
        }

        candidates.sort(Candidate.BEST_FIRST);
        List<Candidate> hits = dedupe(candidates, effectiveOptions.maxHits());

        long textDeadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(limits.textBudgetMillis());
        List<RAGHit> result = new ArrayList<>(hits.size());
        for (Candidate candidate : hits) {
            result.add(toHit(candidate, effectiveOptions, textDeadline, warnings));
        }

        List<String> cappedWarnings = capWarnings(warnings);
        LOG.debug("RAG search '{}' in {}: {} sources, {} chunks, {} hits, {} ms",
                abbreviate(query), effectiveScope.describe(), selected.size(), chunksScanned,
                result.size(), (System.nanoTime() - started) / 1_000_000L);
        return new RAGResult(result, selected.size(), chunksScanned, cappedWarnings);
    }

    /**
     * Selects the providers of a scope, refusing an empty selection with the list of
     * what does exist (a caller - and an LLM - must not guess names).
     *
     * @param scope the scope
     * @return the matching providers
     */
    private List<RAGProvider> select(RAGScope scope) {
        List<RAGProvider> selected = new ArrayList<>();
        for (RAGProvider provider : providers) {
            if (scope.matches(provider)) {
                selected.add(provider);
            }
        }
        if (selected.isEmpty()) {
            throw new RagScopeException("rag: no source matches " + scope.describe()
                    + " - available: " + describeAvailable());
        }
        return selected;
    }

    /**
     * Ensures that all selected sources were built with the same embedding model: a
     * ranking that mixes two models is meaningless, so it is refused instead of
     * silently produced.
     *
     * @param selected the selected providers
     * @param scope    the scope, for the error message
     */
    private static void requireOneModel(List<RAGProvider> selected, RAGScope scope) {
        String reference = null;
        for (RAGProvider provider : selected) {
            String model = provider.model();
            if (reference == null) {
                reference = model;
            } else if (!reference.equalsIgnoreCase(model)) {
                throw new IllegalArgumentException("rag: " + scope.describe()
                        + " mixes embedding models '" + reference + "' and '" + model
                        + "' (" + provider.name() + ") - narrow the scope or rebuild the source");
            }
        }
    }

    /**
     * Sorts out duplicates (same source, same reference and offset) and cuts the list
     * at {@code maxHits}. The candidate list is expected to be sorted already.
     *
     * @param candidates the sorted candidates
     * @param maxHits    the maximum number of hits
     * @return the hits
     */
    private static List<Candidate> dedupe(List<Candidate> candidates, int maxHits) {
        Set<String> seen = new LinkedHashSet<>();
        List<Candidate> hits = new ArrayList<>(Math.min(maxHits, candidates.size()));
        for (Candidate candidate : candidates) {
            String identity = candidate.provider().name() + '\u0000'
                    + candidate.chunk().referenceId() + '\u0000' + candidate.chunk().offsetStart();
            if (seen.add(identity)) {
                hits.add(candidate);
                if (hits.size() >= maxHits) {
                    break;
                }
            }
        }
        return hits;
    }

    /**
     * Builds the hit of a candidate, resolving its text within the budget.
     *
     * @param candidate  the candidate
     * @param options    the options of the call
     * @param textDeadline nano-time after which no further text is resolved
     * @param warnings   collector for text problems
     * @return the hit
     */
    private RAGHit toHit(Candidate candidate, RAGOptions options, long textDeadline,
                         List<String> warnings) {
        EmbeddingChunk chunk = candidate.chunk();
        String text = chunk.text();
        TextStatus status = text != null ? TextStatus.AVAILABLE : TextStatus.NOT_PROVIDED;

        if (text == null && options.withText()) {
            text = resolveText(candidate.provider(), chunk, textDeadline, warnings);
            status = text != null ? TextStatus.AVAILABLE : TextStatus.UNAVAILABLE;
        }
        if (text != null && options.maxChars() > 0 && text.length() > options.maxChars()) {
            text = text.substring(0, options.maxChars());
            status = TextStatus.TRUNCATED;
        }

        Map<String, String> metadata = chunk.metadata();
        return new RAGHit(candidate.provider().name(), chunk.referenceId(),
                metadata.getOrDefault(EmbeddingChunk.META_TITLE, ""),
                metadata.getOrDefault(EmbeddingChunk.META_SOURCE, ""),
                candidate.score(),
                chunk.offsetStart(),
                chunk.offsetEnd(),
                chunk.numberTokens(),
                status,
                text,
                metadata);
    }

    /**
     * Resolves the text of a chunk with a timeout, giving up when the total budget of
     * the search is exhausted.
     *
     * @param provider     the source of the chunk
     * @param chunk        the chunk
     * @param textDeadline nano-time after which no further text is resolved
     * @param warnings     collector for the failure
     * @return the text, or {@code null}
     */
    private String resolveText(RAGProvider provider, EmbeddingChunk chunk, long textDeadline,
                               List<String> warnings) {
        long remainingMillis = TimeUnit.NANOSECONDS.toMillis(textDeadline - System.nanoTime());
        if (remainingMillis <= 0) {
            warnings.add("text budget exhausted before '" + chunk.referenceId()
                    + "' (" + provider.name() + ")");
            return null;
        }
        long timeout = Math.min(remainingMillis, limits.textTimeoutMillis());
        Future<String> future = TEXT_EXECUTOR.submit(() -> provider.text(chunk));
        try {
            String text = future.get(timeout, TimeUnit.MILLISECONDS);
            if (text == null) {
                // The provider answered, but has no text (e.g. the referenced file is gone).
                warnings.add("source unavailable: no text for '" + chunk.referenceId()
                        + "' (" + provider.name() + ")");
            }
            return text;
        } catch (TimeoutException e) {
            future.cancel(true);
            warnings.add("source unavailable: text of '" + chunk.referenceId() + "' ("
                    + provider.name() + ") not delivered within " + timeout + " ms");
            return null;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            warnings.add("source unavailable: text of '" + chunk.referenceId() + "' ("
                    + provider.name() + ") failed: " + cause.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return null;
        }
    }

    /**
     * Describes the available sources for an error message.
     *
     * @return a text like {@code "News/DLF-News, Java/Spring Framework Documentation"}
     */
    private String describeAvailable() {
        if (providers.isEmpty()) {
            return "(no source registered)";
        }
        StringBuilder sb = new StringBuilder();
        for (RAGProvider provider : providers) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(provider.topic()).append('/').append(provider.name());
        }
        return sb.toString();
    }

    /**
     * Caps a warning list at {@link RagLimits#maxWarnings()} entries.
     *
     * @param warnings the warnings
     * @return the capped, immutable list
     */
    private List<String> capWarnings(List<String> warnings) {
        int max = limits.maxWarnings();
        if (max <= 0 || warnings.isEmpty()) {
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
     * Shortens a query for a log line.
     *
     * @param query the query
     * @return the first 40 characters, ellipsized
     */
    private static String abbreviate(String query) {
        String oneLine = query.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() <= 40 ? oneLine : oneLine.substring(0, 37) + "...";
    }

    // ---- Types ----

    /**
     * A chunk with its similarity score.
     *
     * @param chunk the chunk
     * @param score the cosine similarity
     */
    public record ScoredChunk(EmbeddingChunk chunk, double score) {

        /** Order: best score first, ties broken by reference and offset (reproducible). */
        public static final Comparator<ScoredChunk> BEST_FIRST = Comparator
                .comparingDouble(ScoredChunk::score).reversed()
                .thenComparing(scored -> scored.chunk().referenceId())
                .thenComparingInt(scored -> scored.chunk().offsetStart());
    }

    /**
     * A candidate during the merge: the chunk together with the source it came from.
     *
     * @param provider the source
     * @param chunk    the chunk
     * @param score    the cosine similarity
     */
    private record Candidate(RAGProvider provider, EmbeddingChunk chunk, double score) {

        /** Order: best score first, ties broken by source name, reference and offset. */
        static final Comparator<Candidate> BEST_FIRST = Comparator
                .comparingDouble(Candidate::score).reversed()
                .thenComparing(candidate -> candidate.provider().name(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(candidate -> candidate.chunk().referenceId())
                .thenComparingInt(candidate -> candidate.chunk().offsetStart());
    }

    /**
     * Returns a one-line summary of the database for log lines.
     *
     * @return a description
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("EmbeddingDb{");
        for (RAGProvider provider : providers) {
            if (sb.length() > "EmbeddingDb{".length()) {
                sb.append(", ");
            }
            sb.append(provider.topic()).append('/').append(provider.name());
        }
        return sb.append('}').toString();
    }

}
