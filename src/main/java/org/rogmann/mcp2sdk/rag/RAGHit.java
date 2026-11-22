package org.rogmann.mcp2sdk.rag;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One search result: a chunk with its provenance, its score and its text.
 * <p>
 * The hit is the data a caller (and an LLM) works with - it is self-contained enough
 * to be cited: {@link #provider()} names the RAG source, {@link #title()} the document
 * or section, {@link #source()} the URL or path of the original.
 * </p>
 *
 * @param provider    name of the {@link RAGProvider} the hit comes from
 * @param referenceId identifier of the referenced document (unique together with the offset)
 * @param title       document/section title (from {@link EmbeddingChunk#META_TITLE}), or {@code ""}
 * @param source      URL or path of the original source (from {@link EmbeddingChunk#META_SOURCE}), or {@code ""}
 * @param score       similarity (cosine, higher is better)
 * @param startOffset start offset of the chunk, or {@code -1}
 * @param endOffset   end offset (exclusive), or {@code -1}
 * @param numberTokens token count of the chunk (context budget), {@code 0} if unknown
 * @param textStatus  why the hit carries the text it carries
 * @param text        the text, or {@code null} if {@link #textStatus()} is not
 *                    {@link TextStatus#AVAILABLE} or {@link TextStatus#TRUNCATED}
 * @param metadata    free-form per-chunk data, never {@code null}
 */
public record RAGHit(
        String provider,
        String referenceId,
        String title,
        String source,
        double score,
        int startOffset,
        int endOffset,
        int numberTokens,
        TextStatus textStatus,
        String text,
        Map<String, String> metadata) {

    /**
     * Compact canonical constructor: normalizes the optional parts and makes the
     * metadata map immutable.
     */
    public RAGHit {
        if (provider == null) {
            throw new IllegalArgumentException("RAGHit: provider must not be null");
        }
        if (referenceId == null) {
            throw new IllegalArgumentException("RAGHit: referenceId must not be null");
        }
        title = title == null ? "" : title;
        source = source == null ? "" : source;
        textStatus = textStatus == null ? TextStatus.NOT_PROVIDED : textStatus;
        metadata = (metadata == null || metadata.isEmpty())
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    /**
     * Indicates whether the hit carries usable text.
     *
     * @return true for {@link TextStatus#AVAILABLE} and {@link TextStatus#TRUNCATED}
     */
    public boolean hasText() {
        return text != null
                && (textStatus == TextStatus.AVAILABLE || textStatus == TextStatus.TRUNCATED);
    }
}
