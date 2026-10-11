package org.rogmann.mcp2sdk.pdf.state;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The <em>graphics state stack</em> of 4.3.1 (PDF 1.7, S. 214/215) - the two operators
 *
 * <blockquote>"The {@code q} operator pushes a copy of the entire graphics state onto the stack.
 * The {@code Q} operator restores the entire graphics state to its former value by popping it from
 * the stack."</blockquote>
 * <p>
 * Since {@link PdfGraphicsState} cannot change after it was built, pushing <em>the reference</em>
 * is the copy the specification asks for, and it is cheap: the check document pushes and pops
 * 32 059 times across 875 streams, and {@code PdfStateStackTest} pins that the object a
 * {@code Q} hands back is the very object the matching {@code q} stored
 * ({@link java.util.Objects#requireNonNull} would not catch a stale copy, {@code assertSame} does).
 * </p>
 *
 * <h2>What this class is not</h2>
 *
 * It is not a repair shop. The specification requires the operators to balance - "Occurrences of
 * the q and Q operators must be balanced within a given content stream (or within the sequence of
 * streams specified in a page dictionary's Contents array)" (S. 215) - and when a file does not
 * balance, this stack counts it and says so, and hands back what it has:
 * a {@code Q} with nothing on the stack returns the state that was in effect, unchanged, and marks
 * an underflow (plan decision 8: "Unbalanciertes q/Q wird gemeldet, nicht korrigiert"). The
 * alternative - synthesising an initial state - would report a CTM and a clip that the stream never
 * established, in a document where the {@code Q} was plainly meant for a {@code q} that is not
 * there.
 *
 * <h2>What travels on it, and what does not</h2>
 *
 * A {@link PdfGraphicsState} carries the CTM, the clip, the colours and the text state, so all of
 * that is saved and restored - the clip included, which 4.4.3, S. 235 spells out: "since the
 * clipping path is part of the graphics state, its effect can be localized … Execution of the Q
 * operator causes the clipping path to revert to the value that was saved by the q operator before
 * the clipping path was modified." The one thing that never comes near this class is the
 * {@link PdfPath path}: 4.4.1, S. 226 keeps it out of the graphics state, so a {@code Q} neither
 * restores an older path nor discards the one being built.
 */
public final class PdfStateStack {

    /**
     * How deep the stack may go before {@code q} stops saving. Real pages nest a handful of levels
     * (the check document's deepest nesting is 6); a stream that nests thousands is a broken loop,
     * and an unbounded stack turns one bad stream into an out of memory error in the middle of a
     * listing. The number is {@code pdf.status().maxStateDepth} (in the answer, the nesting a walk
     * really reached is {@code walk.deepestState}).
     */
    public static final int MAX_DEPTH = 1024;

    /**
     * What a {@code Q} produced.
     *
     * @param state     the state that is now in effect - the popped one, or the one handed in when
     *                  the stack was empty
     * @param underflow whether nothing was on the stack to restore
     */
    public record Restored(PdfGraphicsState state, boolean underflow) {

        /** The sentence a caller puts in its notes for an underflow, or null. */
        public String note() {
            if (!underflow) {
                return null;
            }
            return "a Q operator ran with an empty graphics state stack - 4.3.1, S. 214 lets Q"
                    + " restore only what a q saved, so the state stays as it is and the operator"
                    + " counts as unbalanced rather than silently re-initialising the page";
        }
    }

    private final List<PdfGraphicsState> saved = new ArrayList<>();
    private final int maxDepth;
    private long savedCount;
    private long restoredCount;
    private long underflowCount;
    private int deepest;
    private boolean truncated;

    /** The stack with the default depth budget. */
    public PdfStateStack() {
        this(MAX_DEPTH);
    }

    /** The stack with an explicit depth budget. */
    public PdfStateStack(int maxDepth) {
        this.maxDepth = Math.max(1, maxDepth);
    }

    /**
     * {@code q} - saves the state.
     *
     * @param state the state in effect at the operator; the reference is stored, not a copy
     * @return a note when the budget refused the save, else {@code null}
     */
    public String save(PdfGraphicsState state) {
        if (state == null) {
            throw new IllegalArgumentException("save(null) - the state in effect is never null");
        }
        savedCount++;
        if (saved.size() >= maxDepth) {
            truncated = true;
            return "the graphics state stack reached " + maxDepth + " levels, so this q did not save"
                    + " - the states below it stay valid, and the Q operators that come back for"
                    + " this one will report an underflow";
        }
        saved.add(state);
        deepest = Math.max(deepest, saved.size());
        return null;
    }

    /**
     * {@code Q} - restores the most recently saved state.
     *
     * @param current the state in effect at the operator, kept as the answer when the stack is empty
     * @return the state to use now, and whether the stack was empty
     */
    public Restored restore(PdfGraphicsState current) {
        if (saved.isEmpty()) {
            underflowCount++;
            return new Restored(current, true);
        }
        restoredCount++;
        return new Restored(saved.remove(saved.size() - 1), false);
    }

    /** The state a {@code Q} would restore now, or {@code null} when the stack is empty. */
    public PdfGraphicsState peek() {
        return saved.isEmpty() ? null : saved.get(saved.size() - 1);
    }

    /** What is on the stack, bottom first - the references {@code q} stored. */
    public List<PdfGraphicsState> layers() {
        return Collections.unmodifiableList(saved);
    }

    /** How many states are on the stack right now. */
    public int depth() {
        return saved.size();
    }

    /** The deepest the stack ever got - the {@code clipDepth}-like number of the nesting itself. */
    public int deepest() {
        return deepest;
    }

    /** How many {@code q} operators saved (an {@code q} refused by the budget counted too, and said so). */
    public long saved() {
        return savedCount;
    }

    /** How many {@code Q} operators actually restored something - an underflow is not counted here. */
    public long restored() {
        return restoredCount;
    }

    /** How many {@code Q} operators found the stack empty. */
    public long underflows() {
        return underflowCount;
    }

    /**
     * Whether every save has been restored and none was missing - the balance S. 215 asks for.
     * True only with an empty stack, no underflow and equal counts; a stream that ends with
     * three unclosed {@code q}s is unbalanced even though nothing went wrong operator by operator.
     */
    public boolean isBalanced() {
        return saved.isEmpty() && underflowCount == 0 && savedCount == restoredCount;
    }

    /** Whether the depth budget refused a save. */
    public boolean isTruncated() {
        return truncated;
    }

    /**
     * The sentence about the balance, for a walk summary - {@code null} when there is nothing to
     * report, so a caller can add it conditionally.
     */
    public String imbalanceNote() {
        if (isBalanced() && !truncated) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (underflowCount > 0) {
            sb.append(underflowCount).append(" Q operator(s) ran with an empty stack");
        }
        if (!saved.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(saved.size()).append(" q level(s) were never closed")
                    .append(" - 4.3.1, S. 215 requires the two operators to balance within a")
                    .append(" content stream");
        }
        if (savedCount != restoredCount && underflowCount == 0 && saved.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(savedCount).append(" save(s) against ").append(restoredCount)
                    .append(" restore(s)");
        }
        if (truncated) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append("the stack was capped at ").append(maxDepth).append(" levels");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** A short line for a listing: saved, restored, depth. */
    public String describe() {
        return "state stack: " + savedCount + " saved, " + restoredCount + " restored, depth "
                + saved.size() + " (deepest " + deepest + ")"
                + (underflowCount > 0 ? ", " + underflowCount + " underflow(s)" : "")
                + (isBalanced() ? ", balanced" : "");
    }

    @Override
    public String toString() {
        return describe();
    }
}
