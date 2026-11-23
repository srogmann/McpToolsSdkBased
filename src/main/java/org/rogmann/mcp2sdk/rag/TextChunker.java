package org.rogmann.mcp2sdk.rag;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a text into overlapping chunks with clean boundaries - the one chunking
 * implementation (M1/M2).
 * <p>
 * The split points are the ones the old indexing path used: the window ends at
 * {@code maxChars}, is moved backwards within {@code lookback} characters to a sentence
 * ending ({@code . ! ?} followed by whitespace) or a line break, and the next window
 * starts {@code overlap} characters before the end of the previous one. Keeping the
 * algorithm here (and the values at {@link ChunkOptions#defaults()}) is what keeps a
 * rebuilt index comparable to an existing one.
 * </p>
 * <p>
 * Offsets are <strong>UTF-16 code units of the decoded text</strong> (Java {@code char}),
 * the same unit as {@link EmbeddingChunk#offsetStart()} and {@code RAGProvider.text()}.
 * </p>
 */
public final class TextChunker {

    /** The options of this chunker. */
    private final ChunkOptions options;

    /**
     * Creates a chunker.
     *
     * @param options the chunk parameters
     */
    public TextChunker(ChunkOptions options) {
        if (options == null) {
            throw new IllegalArgumentException("TextChunker: options must not be null");
        }
        this.options = options;
    }

    /**
     * Returns the options of this chunker.
     *
     * @return the options
     */
    public ChunkOptions options() {
        return options;
    }

    /**
     * Splits a text into chunks.
     * <p>
     * The spans are non-empty, strictly increasing in their start, and consecutive spans
     * overlap by about {@link ChunkOptions#overlap()} characters (a clean split point may
     * move the boundary by a few characters). Together they cover the whole text.
     * </p>
     *
     * @param text the text, may be empty but not {@code null}
     * @return the spans in reading order; empty for empty text
     */
    public List<Span> split(String text) {
        if (text == null) {
            throw new IllegalArgumentException("TextChunker: text must not be null");
        }
        int length = text.length();
        if (length == 0) {
            return List.of();
        }
        int maxChars = options.maxChars();
        int overlap = options.overlap();
        int lookback = options.lookback();

        List<Span> spans = new ArrayList<>();
        int pos = 0;
        while (pos < length) {
            int end = Math.min(pos + maxChars, length);
            if (end < length) {
                end = findGoodSplitPoint(text, end, lookback);
            }
            spans.add(new Span(pos, end));
            if (end >= length) {
                break;
            }
            int idealStart = end - overlap;
            if (idealStart <= pos) {
                pos = end;
            } else {
                int nextStart = findGoodSplitPoint(text, idealStart, lookback);
                // The guarantee of progress: a split point may lie before pos.
                pos = Math.max(pos + 1, nextStart);
            }
        }
        return List.copyOf(spans);
    }

    /**
     * Finds a good split point by searching backwards from {@code maxPos} within the
     * {@code lookback} window: first a sentence ending, then a line break, finally
     * {@code maxPos} itself.
     *
     * @param text     the full text
     * @param maxPos   the maximum position (exclusive) for the split
     * @param lookback the number of characters to look back from {@code maxPos}
     * @return a character index suitable for splitting the text
     */
    private static int findGoodSplitPoint(String text, int maxPos, int lookback) {
        int startPos = Math.max(0, maxPos - lookback);
        String searchRegion = text.substring(startPos, maxPos);

        for (int i = searchRegion.length() - 1; i >= 0; i--) {
            char c = searchRegion.charAt(i);
            if (c == '.' || c == '!' || c == '?') {
                if (i + 1 >= searchRegion.length() || Character.isWhitespace(searchRegion.charAt(i + 1))) {
                    return startPos + i + 1;
                }
            }
        }
        for (int i = searchRegion.length() - 1; i >= 0; i--) {
            if (searchRegion.charAt(i) == '\n' || searchRegion.charAt(i) == '\r') {
                return startPos + i + 1;
            }
        }
        return maxPos;
    }

    /**
     * A chunk range inside the text.
     *
     * @param start start offset (inclusive), a UTF-16 code unit index
     * @param end   end offset (exclusive)
     */
    public record Span(int start, int end) {

        /**
         * Validates the span.
         */
        public Span {
            if (start < 0 || end <= start) {
                throw new IllegalArgumentException(
                        "TextChunker.Span: invalid range " + start + ".." + end);
            }
        }

        /**
         * Returns the length of the span in characters.
         *
         * @return the length
         */
        public int length() {
            return end - start;
        }

        /**
         * Returns the text of this span.
         *
         * @param text the text the span belongs to
         * @return the chunk text
         */
        public String text(String text) {
            return text.substring(start, end);
        }
    }
}
