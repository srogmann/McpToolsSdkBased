package org.rogmann.mcp2sdk.pdf.ops;

import org.rogmann.mcp2sdk.pdf.PdfContentStream;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The first sink: count what a stream contains, without deciding anything about it.
 * <p>
 * This is the sink behind the histogram of {@code pdf.contentStreams()} and the counts of
 * {@code pdf.operations()} — the numbers an operator census is built from (the plan's own census of
 * the check document, {@code l} 170 941 · {@code cm} 69 994 · …, was read out of exactly this
 * shape). It reads tokens rather than operations on purpose: an operand count and a token total are
 * statements about the token stream, and the walk must not be trusted to re-derive them from what it
 * grouped them into.
 * </p>
 * <p>
 * The two properties that make it a replacement for the hand-written loop and not merely a lookalike
 * are that the histogram is keyed in <b>document order</b> ({@link LinkedHashMap}, so a tie in the
 * sort resolves the same way it always did) and that an inline image counts as <b>one</b> image and
 * — through the {@code EI} token the tokenizer emits — as one operator, which is how
 * {@code analyse()} has always counted it.
 * </p>
 */
public final class CountingSink implements PdfContentSink {

    /**
     * How many operators a histogram lists. The listing's own default; 0 means "no histogram".
     */
    public static final int DEFAULT_HISTOGRAM_LIMIT = 20;

    private long tokenCount;
    private long operatorCount;
    private long operandCount;
    private long inlineImageCount;
    private final Map<String, Long> counts = new LinkedHashMap<>();

    @Override
    public void onToken(PdfContentStream.Token token) {
        tokenCount++;
        if (token.isOperator()) {
            operatorCount++;
            counts.merge((String) token.value(), 1L, Long::sum);
        } else if (token.isOperand()) {
            operandCount++;
        } else {
            inlineImageCount++;
        }
    }

    /** Tokens the tokenizer produced. */
    public long tokens() {
        return tokenCount;
    }

    /** Operator tokens, {@code EI} of an inline image included. */
    public long operators() {
        return operatorCount;
    }

    /** Operand tokens, an inline image's folded value not included. */
    public long operands() {
        return operandCount;
    }

    /** Inline-image tokens. */
    public long inlineImages() {
        return inlineImageCount;
    }

    /** Operator → count, in order of first appearance. */
    public Map<String, Long> counts() {
        return new LinkedHashMap<>(counts);
    }

    /** How many distinct operators the stream used. */
    public int distinctOperators() {
        return counts.size();
    }

    /** The count of one operator, named or not. */
    public long countOf(String operator) {
        return counts.getOrDefault(operator, 0L);
    }

    /**
     * The most frequent operators first, ties broken by name — the order and the shape
     * {@code pdf.contentStreams()} has always returned.
     *
     * @param limit entries listed at most (0 = nothing)
     * @return {@code [{operator, count}]}
     */
    public List<Map<String, Object>> histogram(int limit) {
        // Count descending, then the name — a tie must not depend on the document order of a stream
        // that happened to use two operators equally often.
        List<Map.Entry<String, Long>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((a, b) -> {
            int byCount = Long.compare(b.getValue(), a.getValue());
            return byCount != 0 ? byCount : a.getKey().compareTo(b.getKey());
        });
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, Long> entry : entries) {
            if (list.size() >= limit) {
                break;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("operator", entry.getKey());
            item.put("count", entry.getValue());
            list.add(item);
        }
        return list;
    }

    /** A one-line census: what the stream is mostly made of. */
    public String describe() {
        return tokenCount + " tokens, " + operatorCount + " operators, " + operandCount
                + " operands, " + counts.size() + " distinct operator(s)"
                + (inlineImageCount > 0 ? ", " + inlineImageCount + " inline image(s)" : "");
    }

    @Override
    public String toString() {
        return describe();
    }
}
