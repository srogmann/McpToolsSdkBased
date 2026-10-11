package org.rogmann.mcp2sdk.pdf.ops;

import org.rogmann.mcp2sdk.pdf.PdfName;
import org.rogmann.mcp2sdk.pdf.PdfString;
import org.rogmann.mcp2sdk.pdf.state.PdfGraphicsState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The fifth sink: the marked-content sequences of a stream, the list behind
 * {@code pdf.markedContent()} — increment D4 (docs/js/pdf-content-plan.md, Aufgabe 4).
 *
 * <h2>What 14.6.2 asks a reader to do</h2>
 *
 * A page can mark part of its content with {@code BMC}/{@code BDC} … {@code EMC}, give it a tag and an
 * {@code /MCID}, and the structure tree then names those numbers. Answering "which sequence is here,
 * how deep is it nested, what does it contain" is four stream facts, and none of them is a document
 * question:
 * <ul>
 *   <li><b>nest</b> — a {@code BMC} opens, an {@code EMC} closes the innermost open sequence, and an
 *       {@code EMC} that finds nothing open is a finding rather than a reset (14.6.2 nests the two, and
 *       the old loop said so at its offset; the walk still does);</li>
 *   <li><b>read the operands as the table prints them</b> — "{@code tag properties DP}",
 *       "{@code properties} is either an inline dictionary … or a name object associated with it in the
 *       Properties subdictionary" (Table 10.7, S. 851): the property list is the last operand and the
 *       tag precedes it, so a producer that forgot the list leaves the tag where a one-operand
 *       sequence has it anyway;</li>
 *   <li><b>cut the slice</b> — the text of a sequence is the bytes between its opening operator and the
 *       {@code EMC} that closed it, and only outermost sequences are cut, because a nested one would
 *       count its text twice;</li>
 *   <li><b>hand the slice over in the state it started in</b> — this is what D4 changed. The loop
 *       remembered the last {@code Tf} it had passed and handed over that <em>name</em>; the sink hands
 *       over the {@link PdfGraphicsState state} at the sequence's first byte. A slice that opens inside
 *       a {@code q/Q} pair or inside a text object then starts where the stream left it, and the answer
 *       no longer depends on searching the tokens to the left of the slice (the old
 *       {@code PdfContentStream.fontInEffect}).</li>
 * </ul>
 *
 * <h2>Where the document knowledge stays</h2>
 *
 * What an {@code /MCID} claims in the structure tree, what a property name resolves to under
 * {@code /Resources}, and how a character limit is cut are not stream facts. The sink reports
 * {@link Sequence}s carrying their raw operands, their state and their slice, and {@link SliceReader}
 * lets the caller — the one that owns the document — turn bytes into text. Findings are announced
 * through {@link Listener} at the moment they are found, so a page's note list keeps the document
 * order the old loop produced: a note that moved to another position is a different answer.
 */
public final class MarkedContentSink implements PdfContentSink {

    /**
     * How the caller reads the bytes of one closed sequence.
     * <p>
     * The sink knows the slice and the state in front of it; only the caller knows which page the bytes
     * belong to and which fonts that page names. {@code state} is the graphics state at the slice's
     * first byte — it carries the font the slice starts in and, unlike the resource name the loop
     * passed, also says whether a font was set at all.
     * </p>
     */
    public interface SliceReader {
        /**
         * @param slice the bytes from just after the opening operator to just before its {@code EMC}
         * @param state the state in effect at the first byte of the slice, never null
         * @return the text of the slice, untrimmed — trimming and clipping is the caller's rule
         */
        String read(byte[] slice, PdfGraphicsState state);
    }

    /** What the page has to say about a sequence, announced at the operator that caused it. */
    public interface Listener {

        /**
         * A sequence was opened. Called for every one — whether it will be listed is on the record —
         * so that a caller can resolve what the operands point at and add its own sentences to its
         * note list while the stream is still being read, which is what keeps notes in document order.
         */
        default void opened(Sequence sequence) {
            // not interested
        }

        /** The {@code EMC} that closed a sequence, with the slice it made. */
        default void closed(Sequence sequence) {
            // not interested
        }

        /** A sequence whose tag operand is neither a name nor a string (Table 10.7, S. 851). */
        void tagIsNotAName(int offset, Object tagOperand);

        /** An {@code EMC} that found no open sequence. */
        void strayEmc(int offset);

        /** A listener that says nothing — for a caller that only wants the sequences. */
        Listener SILENT = new Listener() {
            @Override
            public void tagIsNotAName(int offset, Object tagOperand) {
                // not interested
            }

            @Override
            public void strayEmc(int offset) {
                // not interested
            }

            @Override
            public String toString() {
                return "MarkedContentSink.Listener.silent";
            }
        };
    }

    /**
     * One {@code BMC}/{@code BDC} the walk found, and what its {@code EMC} added to it.
     *
     * @param index           1-based number on this page, counting every sequence found
     * @param operator        {@code BMC} or {@code BDC}
     * @param depth           nesting depth — 1 for an outermost sequence
     * @param offset          byte offset of the opening operator
     * @param tagOperand      the operand the tag stands in (a name, or whatever the file wrote), or null
     * @param propertyOperand the property-list operand of a {@code BDC} — an inline dictionary or a name
     *                        in the {@code Properties} resources — or null when the file wrote none
     * @param stateAtStart    the graphics state at the sequence's first byte, which carries its font
     * @param startOffset     offset of the slice's first byte, just past the opening operator
     * @param endOffset       offset of the {@code EMC} that closed it, -1 when the stream ended first
     * @param topLevel        true for an outermost sequence — the only ones whose text is cut
     * @param listed          whether the listing limit let this one into {@link #sequences()}
     *                        ({@link #sequencesFound()} counts all of them either way)
     * @param text            the slice read through {@link SliceReader}, or null when no text was asked
     *                        for and when the sequence was never closed
     */
    public record Sequence(long index, String operator, int depth, int offset, Object tagOperand,
                           Object propertyOperand, PdfGraphicsState stateAtStart, int startOffset,
                           int endOffset, boolean topLevel, boolean listed, String text) {

        /** Whether a font was in effect at the sequence's first byte (a slice can start without one). */
        public boolean startsWithFont() {
            return stateAtStart != null && stateAtStart.textState().hasFont();
        }

        /** The resource name of that font, or null. */
        public String fontAtStart() {
            return stateAtStart == null ? null : stateAtStart.textState().fontResourceName();
        }
    }

    private final byte[] content;
    private final int maxListed;
    private final SliceReader reader;
    private final Listener listener;

    private final List<Sequence> listed = new ArrayList<>();
    private final List<Open> open = new ArrayList<>();

    private long total;
    private long strayEmcs;
    private long unmarkedTextOperators;
    private int deepest;
    private boolean truncated;

    /**
     * @param content   the stream's bytes — a slice of them is a sequence's text, and only the caller's
     *                  reader knows what to make of them (null: never cut a slice)
     * @param maxListed sequences kept (0 = every one); all of them are still counted
     * @param reader    how to read a slice's text, or null to leave {@code text} unasked
     * @param listener  findings about operands and unbalanced closers (null: silent)
     */
    public MarkedContentSink(byte[] content, int maxListed, SliceReader reader, Listener listener) {
        this.content = content == null ? new byte[0] : content;
        this.maxListed = maxListed;
        this.reader = reader;
        this.listener = listener == null ? Listener.SILENT : listener;
    }

    /** The structure only: sequences counted and nested, no slice cut, nothing listed. */
    public MarkedContentSink() {
        this(null, 0, null, Listener.SILENT);
    }

    @Override
    public void onOperation(PdfContentInterpreter.Operation operation) {
        String name = operation.name();
        if ("BMC".equals(name) || "BDC".equals(name)) {
            openSequence(operation, name);
            return;
        }
        if ("EMC".equals(name)) {
            closeSequence(operation);
            return;
        }
        if (operation.showsText() && open.isEmpty()) {
            // Text outside every sequence is invisible to the structure tree, and this is the one
            // place that knows the nesting while the stream is still being read.
            unmarkedTextOperators++;
        }
    }

    private void openSequence(PdfContentInterpreter.Operation operation, String name) {
        total++;
        deepest = Math.max(deepest, open.size() + 1);
        // Postfix, read back from the operator: the property list is the last operand and the tag the
        // one before it (Table 10.7, S. 851). A BDC that wrote no list — or one operand too many —
        // leaves the tag where the old loop read it, so the answer of a broken file does not move.
        List<Object> operands = operation.operands();
        Object tagOperand;
        Object propertyOperand = null;
        if ("BDC".equals(name) && operands.size() >= 2) {
            tagOperand = operands.get(operands.size() - 2);
            propertyOperand = operands.get(operands.size() - 1);
        } else {
            tagOperand = operands.isEmpty() ? null : operands.get(operands.size() - 1);
        }
        if (tagOperand != null && !(tagOperand instanceof PdfName)
                && !(tagOperand instanceof PdfString)) {
            listener.tagIsNotAName(operation.offset(), tagOperand);
        }

        // The record is created where the old loop put its map: at the opening operator, so the list
        // is in document order even when sequences close inside each other. Its end offset and its
        // text are added when the EMC closes it — the loop mutated the map it had already handed out,
        // here the position is remembered and the record replaced.
        boolean keep = maxListed <= 0 || listed.size() < maxListed;
        int position = -1;
        if (keep) {
            position = listed.size();
        } else {
            truncated = true;
        }
        int depth = open.size() + 1;
        int startOffset = operation.offset() + name.length();
        Sequence openedRecord = new Sequence(total, name, depth, operation.offset(), tagOperand,
                propertyOperand, operation.state(), startOffset, -1, open.isEmpty(), keep, null);
        if (keep) {
            listed.add(openedRecord);
        }
        open.add(new Open(total, name, depth, operation.offset(), tagOperand, propertyOperand,
                operation.state(), startOffset, open.isEmpty(), position));
        listener.opened(openedRecord);
    }

    private void closeSequence(PdfContentInterpreter.Operation operation) {
        if (open.isEmpty()) {
            strayEmcs++;
            listener.strayEmc(operation.offset());
            return;
        }
        Open opened = open.remove(open.size() - 1);
        String text = null;
        if (opened.topLevel && reader != null) {
            int from = Math.min(opened.startOffset, content.length);
            int to = Math.min(Math.max(operation.offset(), from), content.length);
            text = reader.read(Arrays.copyOfRange(content, from, to), opened.stateAtStart);
        }
        Sequence closed = new Sequence(opened.index, opened.operator, opened.depth, opened.offset,
                opened.tagOperand, opened.propertyOperand, opened.stateAtStart, opened.startOffset,
                operation.offset(), opened.topLevel, opened.position >= 0, text);
        if (opened.position >= 0) {
            listed.set(opened.position, closed);
        }
        listener.closed(closed);
    }

    /** The sequences of the walk, in document order and capped by the listing limit. */
    public List<Sequence> sequences() {
        return Collections.unmodifiableList(listed);
    }

    /** How many {@code BMC}/{@code BDC} the stream wrote, listed or not. */
    public long sequencesFound() {
        return total;
    }

    /** How deep the sequences were nested (0 when the page marks nothing). */
    public int deepestNesting() {
        return deepest;
    }

    /** {@code EMC} operators that found no open sequence. */
    public long strayEmcs() {
        return strayEmcs;
    }

    /** Sequences the stream left open — the page says so once, after the walk. */
    public int openAtEnd() {
        return open.size();
    }

    /** Text-showing operators outside every sequence. */
    public long unmarkedTextOperators() {
        return unmarkedTextOperators;
    }

    /** Whether the listing limit cut the list ({@link #sequencesFound()} stays complete). */
    public boolean truncated() {
        return truncated;
    }

    @Override
    public String toString() {
        return total + " marked-content sequence(s), " + listed.size() + " listed, "
                + unmarkedTextOperators + " text operator(s) outside";
    }

    /** One open sequence — the nesting 14.6.2 asks for, with everything its {@code EMC} owes it. */
    private record Open(long index, String operator, int depth, int offset, Object tagOperand,
                        Object propertyOperand, PdfGraphicsState stateAtStart, int startOffset,
                        boolean topLevel, int position) { }
}
