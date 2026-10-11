package org.rogmann.mcp2sdk.pdf.ops;

import org.rogmann.mcp2sdk.pdf.PdfContentStream;

import java.util.ArrayList;
import java.util.List;

/**
 * What a walk of a content stream reports to.
 * <p>
 * One walk, many readers — the structural idea of increment D3 (docs/js/pdf-content-plan.md,
 * decision 2): the module has so far run five separate loops over the same token stream, each with
 * its own belief about where a {@code Tf}'s font stops applying and when a {@code Td} starts a new
 * line. From here on there is one interpreter, and everything that wants to know something about a
 * stream implements this interface instead of writing a second loop.
 * </p>
 * <p>
 * Every method has a default, so a sink declares what it consumes: {@link CountingSink} only looks
 * at tokens, {@link ListingSink} only at operations, and a later {@code PlacementSink} will read
 * operations together with the state that was in effect at them. A sink must not change the state
 * objects it is handed — they are immutable, and the walk continues with them.
 * </p>
 *
 * <h2>Why tokens are offered at all</h2>
 *
 * An operation carries everything a text or geometry reader needs, but two of the current five
 * walkers are about the token stream itself: {@code analyse()} counts operands, operators and inline
 * images, and reports the token total. Reporting those numbers from the operations would be
 * re-deriving them from a different view — which is exactly the drift this increment is meant to
 * remove. So the raw tokens stay visible, and the two counts come from the same loop that reads the
 * state.
 */
public interface PdfContentSink {

    /** The sink that only lets the walk happen (a caller that wants the {@code Summary} alone). */
    static PdfContentSink noop() {
        return new PdfContentSink() {
            @Override
            public String toString() {
                return "PdfContentSink.noop";
            }
        };
    }

    /**
     * Several sinks on <b>one</b> walk, in the order given — the reason the walk has sinks at all.
     * <p>
     * A caller that wants two answers from one stream (the histogram <em>and</em> the listing of
     * {@code pdf.operations()}, or, later, text <em>and</em> paths from {@code pdf.placement()})
     * gets them from a single pass instead of two. That matters because a walk is not free: measured
     * on the interpreter it costs about twice what tokenizing costs, so two walks would be a second
     * full price for a second opinion.
     * </p>
     *
     * @param sinks the sinks to forward to; nulls are dropped, one sink is returned as it is
     * @return a sink forwarding every callback to each of them
     */
    static PdfContentSink all(PdfContentSink... sinks) {
        List<PdfContentSink> kept = new ArrayList<>();
        if (sinks != null) {
            for (PdfContentSink sink : sinks) {
                if (sink != null) {
                    kept.add(sink);
                }
            }
        }
        if (kept.isEmpty()) {
            return noop();
        }
        if (kept.size() == 1) {
            return kept.get(0);
        }
        return new PdfContentSink() {
            @Override
            public void onToken(PdfContentStream.Token token) {
                for (PdfContentSink each : kept) {
                    each.onToken(token);
                }
            }

            @Override
            public void onOperation(PdfContentInterpreter.Operation operation) {
                for (PdfContentSink each : kept) {
                    each.onOperation(operation);
                }
            }

            @Override
            public void onDiagnostic(int offset, String operator, String message) {
                for (PdfContentSink each : kept) {
                    each.onDiagnostic(offset, operator, message);
                }
            }

            @Override
            public void endStream(PdfContentInterpreter.Summary summary) {
                for (PdfContentSink each : kept) {
                    each.endStream(summary);
                }
            }

            @Override
            public String toString() {
                return "PdfContentSink.all(" + kept.size() + ")";
            }
        };
    }

    /**
     * Every token of the stream, in document order, exactly as
     * {@link PdfContentStream#tokenize(byte[], java.util.List)} produced it — operands, operators
     * and inline-image values alike.
     *
     * @param token the token
     */
    default void onToken(PdfContentStream.Token token) {
        // not interested
    }

    /**
     * One operator, with the operands that were waiting for it and the state around it.
     *
     * @param operation the operation
     */
    default void onOperation(PdfContentInterpreter.Operation operation) {
        // not interested
    }

    /**
     * Something the file did that the walk could not use — an operand underflow, a {@code Q} with an
     * empty stack, a clip that narrowed away. Reported at its offset; the walk continues.
     *
     * @param offset   byte offset of the operator, or -1 when the report is about the stream as a whole
     * @param operator the operator named in the report, or null
     * @param message  the sentence
     */
    default void onDiagnostic(int offset, String operator, String message) {
        // not interested
    }

    /**
     * The walk is over — the counters, the state the stream ended in, and the reports.
     *
     * @param summary what the whole walk produced
     */
    default void endStream(PdfContentInterpreter.Summary summary) {
        // not interested
    }
}
