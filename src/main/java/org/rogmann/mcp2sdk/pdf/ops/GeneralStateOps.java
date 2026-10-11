package org.rogmann.mcp2sdk.pdf.ops;

import org.rogmann.mcp2sdk.pdf.PdfException;
import org.rogmann.mcp2sdk.pdf.PdfName;
import org.rogmann.mcp2sdk.pdf.PdfResources;
import org.rogmann.mcp2sdk.pdf.geo.PdfMatrix;
import org.rogmann.mcp2sdk.pdf.state.PdfGraphicsState;

import java.util.ArrayList;
import java.util.List;

/**
 * The general graphics state family — 4.3.3, Table 4.7 (PDF 1.7, S. 219).
 *
 * <p>
 * {@code q} and {@code Q} are deliberately <b>not</b> here: they move the {@link
 * org.rogmann.mcp2sdk.pdf.state.PdfStateStack}, and the stack belongs to the walk that has to count
 * the balance (4.3.1, S. 215 requires the two operators to balance within a stream, and only the
 * walk knows whether they did). Everything else of the table takes operands and hands back a new
 * {@link PdfGraphicsState}.
 * </p>
 *
 * <table>
 *   <caption>The operators and what they touch</caption>
 *   <tr><td>{@code cm}</td><td>premultiplies onto the CTM — {@code M' = M_T × M} (4.2.3, S. 209)</td></tr>
 *   <tr><td>{@code w J j M i}</td><td>line width, cap, join, miter limit, flatness</td></tr>
 *   <tr><td>{@code d}</td><td>the dash pattern ({@link PdfGraphicsState.Dash})</td></tr>
 *   <tr><td>{@code ri}</td><td>the colour rendering intent (a name, stored as written)</td></tr>
 *   <tr><td>{@code gs}</td><td>a graphics state parameter dictionary, looked up through {@link
 *       PdfResources} and applied by {@link PdfGraphicsState#withExtGState}</td></tr>
 * </table>
 *
 * <h2>Why {@code gs} needs a resource dictionary and what happens without one</h2>
 *
 * {@code gs} names its dictionary ({@code /GS1 gs}), and a name only means something through the
 * stream's resources (D0b). A walk without {@link Options#resources()} — which is what {@code
 * pdf.operations()} and {@code pdf.contentStreams()} run, because they list a stream without
 * reading a page — therefore reports the operator and its name and leaves the state alone. That is
 * a statement the caller can check, not a silent no-op: the name lands in the diagnostic, and the
 * operator counts as seen.
 */
public final class GeneralStateOps {

    private GeneralStateOps() {
        // static family
    }

    /**
     * Applies one general-state operator.
     *
     * @param state   the state in effect at the operator
     * @param operator the registry row (a general-state one)
     * @param stack   the operands, already checked against the row's arity
     * @param options what the walk knows (resources)
     * @param issues  where a report about the operands goes
     * @param offset  byte offset of the operator, for the reports
     * @return the state that follows
     */
    public static PdfGraphicsState apply(PdfGraphicsState state, PdfContentOperator operator,
                                         PdfOperandStack stack, PdfContentInterpreter.Options options,
                                         PdfContentInterpreter.Issues issues, int offset) {
        switch (operator.name()) {
            case "cm" -> {
                PdfMatrix operand = PdfMatrix.fromOperands(stack.values());
                PdfGraphicsState next = state.transformedBy(operand);
                if (!state.ctm().isSingular() && next.ctm().isSingular()) {
                    // Kept, not refused - decision 9: the stream wrote it, and the refusal belongs
                    // where a number would be claimed from it.
                    issues.add(offset, operator.name(), next.ctm().singularNote());
                }
                return next;
            }
            case "w" -> {
                double width = stack.number(0);
                if (width < 0) {
                    issues.add(offset, operator.name(), "the line width is "
                            + PdfMatrix.format(width) + " - 4.3.2, S. 215 describes it as a"
                            + " non-negative number expressed in user space units; the value is kept"
                            + " as written");
                }
                return state.withLineWidth(width);
            }
            case "J" -> {
                int cap = stack.integer(0).intValue();
                PdfGraphicsState next = state.withLineCap(cap);
                if (next.lineCapDefect() != null) {
                    issues.add(offset, operator.name(), next.lineCapDefect());
                }
                return next;
            }
            case "j" -> {
                int join = stack.integer(0).intValue();
                PdfGraphicsState next = state.withLineJoin(join);
                if (next.lineJoinDefect() != null) {
                    issues.add(offset, operator.name(), next.lineJoinDefect());
                }
                return next;
            }
            case "M" -> {
                return state.withMiterLimit(stack.number(0));
            }
            case "i" -> {
                double flatness = stack.number(0);
                PdfGraphicsState next = state.withFlatness(flatness);
                if (flatness < 0 || flatness > 100) {
                    issues.add(offset, operator.name(), "the flatness tolerance is "
                            + PdfMatrix.format(flatness) + ", and Table 4.7, S. 219 describes it as"
                            + " a number in the range 0 to 100 (0 meaning the output device's own"
                            + " tolerance)");
                }
                return next;
            }
            case "d" -> {
                List<Object> array = stack.array(0);
                List<Double> lengths = new ArrayList<>();
                for (Object item : array) {
                    if (item instanceof Number number) {
                        lengths.add(number.doubleValue());
                    }
                }
                double phase = stack.number(1);
                PdfGraphicsState.Dash dash = PdfGraphicsState.Dash.of(lengths, phase);
                if (dash.defect() != null) {
                    issues.add(offset, operator.name(), dash.defect());
                }
                return state.withDash(dash);
            }
            case "ri" -> {
                PdfName intent = stack.name(0);
                return state.withRenderingIntent(intent.toString());
            }
            case "gs" -> {
                return applyExtGState(state, stack, options, issues, offset);
            }
            default -> {
                // A general-state row this family does not apply: the operands leave the stack with
                // the operation, and nothing else may change. q/Q are handled by the interpreter.
                return state;
            }
        }
    }

    /**
     * {@code dictName gs} — look the name up in {@code ExtGState}, then let the state apply the
     * dictionary it names (Table 4.8, S. 220-223, including its own {@code Font} and {@code TK}
     * entries, which reach the text state).
     */
    private static PdfGraphicsState applyExtGState(PdfGraphicsState state, PdfOperandStack stack,
                                                   PdfContentInterpreter.Options options,
                                                   PdfContentInterpreter.Issues issues, int offset) {
        PdfName name = stack.name(0);
        if (options.resources() == null) {
            issues.add(offset, "gs", "the gs operator names " + name + ", but this walk was given no"
                    + " resource dictionary, so the graphics state parameters it sets were read and"
                    + " not applied - a name is only an address through the resources (PdfResources)"
                    + " and a listing of a stream has no page to ask");
            return state;
        }
        PdfResources.Entry entry;
        try {
            entry = options.resources().resolve("ExtGState", name.toString());
        } catch (PdfException e) {
            // A name the resource layer refuses (an empty name, a dictionary written oddly): the
            // operator is still read, and the reason is still told at its offset.
            issues.add(offset, "gs", "the gs operator names " + name + " and the resource lookup"
                    + " answers: " + e.getMessage());
            return state;
        }
        if (entry.missing() || entry.dict() == null) {
            issues.add(offset, "gs", "the gs operator names " + name + " in the ExtGState resources"
                    + " and the dictionary answers '" + entry.shape() + "' - 4.3.4, S. 220 expects a"
                    + " dictionary of graphics state parameters, so the state stays as it is"
                    + (entry.objNum() != null ? " (object " + entry.objNum() + ")" : ""));
            return state;
        }
        PdfGraphicsState.ExtGStateResult result = state.withExtGState(entry.dict(), name.toString());
        for (String note : result.notes()) {
            issues.add(offset, "gs", note);
        }
        return result.state();
    }
}
