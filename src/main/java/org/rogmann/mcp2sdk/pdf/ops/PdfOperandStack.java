package org.rogmann.mcp2sdk.pdf.ops;

import org.rogmann.mcp2sdk.pdf.PdfName;
import org.rogmann.mcp2sdk.pdf.PdfParser;
import org.rogmann.mcp2sdk.pdf.PdfString;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The operands waiting for the operator that consumes them.
 *
 * <p>
 * A content stream is postfix — "operands precede the operator" (PDF 1.7 4.1, S. 198) — so a walk
 * needs a stack, and this one is the reason the module no longer has to fish operands out of the
 * bytes behind an operator. The requirement test named in docs/js/pdf-content-plan.md recovers them
 * with a regular expression over the previous hundred characters and comments that
 * {@code -?[\d.]+} splits {@code 44.5} into {@code ['44', '.', '5']}; here the operand is the value
 * the tokenizer already parsed, at its real offset, and an operator with too few of them is a
 * <em>finding</em> rather than a silently wrong number.
 * </p>
 *
 * <h2>Two ways an operand list can be wrong, and why they are reported differently</h2>
 *
 * <ul>
 *   <li><b>Too few or too many</b> — {@code 1 0 0 cm}. {@link #defectFor(PdfContentOperator)} says
 *       <em>"cm needs 6 numbers, found 3"</em> at that operator's offset.</li>
 *   <li><b>The right count of the wrong thing</b> — {@code 12 /F39 Tf}. The count is two, so a
 *       count-only check would pass and the font would come out as nothing. The message therefore
 *       names the position and what stood there: <em>"Tf needs a name and a number, found a number
 *       where a name belongs (operand 1)"</em>.</li>
 * </ul>
 *
 * Neither aborts the walk. The specification's wording for these cases is "an error is generated",
 * not "the page is lost": the operator is skipped, its operands are dropped like any other
 * operator's, and the next operator is read with a stack that is empty again — which is the only
 * way the rest of the stream keeps meaning what it says.
 *
 * <h2>{@code null} is a legal operand</h2>
 *
 * The keyword {@code null} parses to a Java {@code null} (see {@link PdfParser#parseValue()}) and so
 * can sit between two operators. That is why {@link #snapshot()} copies into an {@link ArrayList}
 * rather than {@code List.copyOf}, which would throw on it: a file that writes {@code null 12 Tf}
 * is broken, but it is broken in a way the listing has always shown and the walk has always survived.
 */
public final class PdfOperandStack {

    private final List<Object> values = new ArrayList<>();
    private int commentOffset = -1;

    /**
     * Takes one token's value.
     *
     * @param value         the parsed operand, an inline-image summary, or {@code null}
     * @param commentOffset a comment directly in front of the token, or -1
     */
    public void push(Object value, int commentOffset) {
        values.add(value);
        // The comment belongs to the operation, and the listing has always attached the first one it
        // saw — a producer writing a comment per operand would otherwise move it to the last.
        if (commentOffset >= 0 && this.commentOffset < 0) {
            this.commentOffset = commentOffset;
        }
    }

    /** The operator consumed its operands: the stack is empty again, comment included. */
    public void clear() {
        values.clear();
        commentOffset = -1;
    }

    /** How many operands are waiting. */
    public int size() {
        return values.size();
    }

    /** Whether nothing is waiting. */
    public boolean isEmpty() {
        return values.isEmpty();
    }

    /** The comment that introduces this operation, or -1 — the first operand's, as the listing shows it. */
    public int commentOffset() {
        return commentOffset;
    }

    /** The operands in document order, live — for reading, not for keeping. */
    public List<Object> values() {
        return Collections.unmodifiableList(values);
    }

    /**
     * A copy the interpreter can hand to a sink: the stack is cleared after every operator, and a
     * sink that keeps the operation (a listing holds hundreds of them) must not look at an empty
     * stack later. A plain {@link ArrayList} copy, not {@code List.copyOf} — see the class note on
     * {@code null} operands.
     */
    public List<Object> snapshot() {
        return new ArrayList<>(values);
    }

    /** The operand at {@code index} from the left, or {@code null} beyond the end. */
    public Object get(int index) {
        return index >= 0 && index < values.size() ? values.get(index) : null;
    }

    /** The last operand, or {@code null} for an empty stack. */
    public Object last() {
        return values.isEmpty() ? null : values.get(values.size() - 1);
    }

    /** The operand {@code index} from the <em>end</em> ({@code 0} = the last one). */
    public Object fromEnd(int indexFromEnd) {
        int index = values.size() - 1 - indexFromEnd;
        return index >= 0 && index < values.size() ? values.get(index) : null;
    }

    /** The operand at {@code index} as a number, or {@code null} when it is not one. */
    public Double number(int index) {
        Object value = get(index);
        return value instanceof Number number ? number.doubleValue() : null;
    }

    /** The last operand as a number, or {@code null}. */
    public Double lastNumber() {
        Object value = last();
        return value instanceof Number number ? number.doubleValue() : null;
    }

    /** The operand at {@code index} as an integer operand, or {@code null}. */
    public Long integer(int index) {
        Object value = get(index);
        if (value instanceof Long number) {
            return number;
        }
        // A real operand with an integral value is still what the operator wanted; the reference
        // writes these parameters as numbers, and a producer that prints "1.0" where it means 1 is
        // not a defect worth a note.
        if (value instanceof Double real && real == Math.rint(real) && !real.isInfinite()) {
            return (long) real.doubleValue();
        }
        return null;
    }

    /** The operand at {@code index} as a name, or {@code null}. */
    public PdfName name(int index) {
        return get(index) instanceof PdfName name ? name : null;
    }

    /** The operand at {@code index} as a string, or {@code null}. */
    public PdfString string(int index) {
        return get(index) instanceof PdfString string ? string : null;
    }

    /** The operand at {@code index} as an array, or {@code null}. */
    @SuppressWarnings("unchecked")
    public List<Object> array(int index) {
        return get(index) instanceof List<?> list ? (List<Object>) list : null;
    }

    /** The operand at {@code index} as a boolean, or {@code null}. */
    public Boolean bool(int index) {
        return get(index) instanceof Boolean flag ? flag : null;
    }

    /** The last {@code count} operands, left to right — the tail a fixed-shape operator reads. */
    public List<Object> tail(int count) {
        int from = Math.max(0, values.size() - count);
        return new ArrayList<>(values.subList(from, values.size()));
    }

    // =============================================================================================
    // The arity check
    // =============================================================================================

    /**
     * What the stack holds that the operator cannot use, or {@code null} when it fits.
     *
     * <p>
     * Count first, then type: with three operands a {@code cm} has nothing that could be checked
     * per position, and reporting both would be two sentences about one mistake. {@code MIXED}
     * operators are not checked at all — for {@code scn} the operand count is a property of the
     * colour space the module does not interpret (decision 11), and for an unknown operator nobody
     * knows the arity.
     * </p>
     *
     * @param operator the operator the stack belongs to
     * @return the message, phrased so that it can stand alone in a diagnostics list
     */
    public String defectFor(PdfContentOperator operator) {
        if (operator == null || !operator.checksOperands()) {
            return null;
        }
        int size = values.size();
        if (size < operator.minOperands()
                || (operator.maxOperands() != null && size > operator.maxOperands())) {
            return operator.name() + " needs " + operator.arity() + ", found " + size;
        }
        return switch (operator.shape()) {
            case NUMBER, NUMBERS, MATRIX -> firstNonNumber(operator, 0, size);
            case INTEGER -> integer(0) == null
                    ? mismatch(operator, "an integer", 0) : null;
            case NAME -> name(0) == null ? mismatch(operator, "a name", 0) : null;
            case BOOLEAN -> bool(0) == null ? mismatch(operator, "true or false", 0) : null;
            case STRING -> string(size - 1) == null ? mismatch(operator, "a string", size - 1) : null;
            case ARRAY -> array(size - 1) == null ? mismatch(operator, "an array", size - 1) : null;
            case NAME_THEN_NUMBER -> name(0) == null ? mismatch(operator, "a name", 0)
                    : (number(size - 1) == null ? mismatch(operator, "a number", size - 1) : null);
            case ARRAY_AND_NUMBER -> array(0) == null ? mismatch(operator, "an array", 0)
                    : (number(size - 1) == null ? mismatch(operator, "a number", size - 1) : null);
            case NUMBERS_THEN_STRING -> {
                String defect = firstNonNumber(operator, 0, size - 1);
                if (defect != null) {
                    yield defect;
                }
                yield string(size - 1) == null ? mismatch(operator, "a string", size - 1) : null;
            }
            case NOTHING, MIXED -> null;
        };
    }

    private String firstNonNumber(PdfContentOperator operator, int from, int to) {
        for (int i = from; i < to; i++) {
            if (!(values.get(i) instanceof Number)) {
                return mismatch(operator, "a number", i);
            }
        }
        return null;
    }

    /** The sentence for "the operand at this position is of the wrong kind". */
    private String mismatch(PdfContentOperator operator, String wanted, int index) {
        Object value = values.get(index);
        return operator.name() + " needs " + operator.arity() + ", found "
                + PdfParser.kindOf(value) + " where " + wanted + " belongs (operand "
                + (index + 1) + " of " + values.size() + ")";
    }

    /** A short line for a listing or a test. */
    public String describe() {
        return values.size() + " operand(s) waiting" + (commentOffset >= 0 ? ", with a comment" : "");
    }

    @Override
    public String toString() {
        return describe();
    }
}
