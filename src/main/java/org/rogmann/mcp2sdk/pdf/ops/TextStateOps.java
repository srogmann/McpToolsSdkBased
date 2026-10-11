package org.rogmann.mcp2sdk.pdf.ops;

import org.rogmann.mcp2sdk.pdf.geo.PdfMatrix;
import org.rogmann.mcp2sdk.pdf.state.PdfGraphicsState;
import org.rogmann.mcp2sdk.pdf.state.PdfTextState;

/**
 * The text families of chapter 5 — the object brackets of Table 5.4, the nine parameters of
 * Table 5.2 (S. 397/398), the four positioning operators of Table 5.5 (S. 406), and the one half of
 * the showing operators of Table 5.6 (S. 407) that this increment can honestly compute.
 *
 * <h2>The arithmetic is the specification's, written down</h2>
 *
 * Table 5.5 prints its operators as assignments, and they are implemented as printed:
 * <ul>
 *   <li>{@code tx ty Td} → {@code T_m = T_lm = [1 0 0 1 tx ty] × T_lm}, which in code is
 *       {@link PdfTextState#moveToNextLine(double, double)} and reads
 *       {@code textLineMatrix.premultiply(translation)} — premultiplied, because 4.2.3, S. 209 makes
 *       the operand of a state transition apply <em>first</em>;</li>
 *   <li>{@code tx ty TD} → "{@code −ty TL; tx ty Td}" — the negation included, which is why
 *       {@link PdfTextState#moveToNextLineSettingLeading(double, double)} exists and a caller cannot
 *       forget the sign;</li>
 *   <li>{@code a b c d e f Tm} → "the matrix specified by the operands is <b>not</b> concatenated
 *       onto the current text matrix, but <b>replaces</b> it";</li>
 *   <li>{@code T*} → "{@code 0 −T_l Td}" — the leading negated, because 5.2.4 (S. 400) makes
 *       {@code T_l} the distance to the baseline of the <em>next</em> line, which in a text space
 *       whose y axis points up lies below. Read together with the {@code TD} row it is one mechanism
 *       and not two: {@code TD} stores "{@code −ty TL}" so that the {@code T*} after it steps
 *       exactly the {@code TD}'s own way again; see {@link PdfTextState#nextLine()}.</li>
 * </ul>
 *
 * <h2>Where D5 begins</h2>
 *
 * A showing operator also moves {@code T_m} by the displacement of the glyphs it painted
 * (5.3.3, S. 410: {@code t_x = ((w_0 − T_j/1000) × T_fs + T_c + T_w) × T_h}). That number needs the
 * glyph width and the kerning entries of the array, and D3 has no business guessing either, so
 * {@code Tj} and {@code TJ} change no state here at all. What a showing operator does <em>without</em>
 * a width is applied — and Table 5.6 (S. 407) prints those parts as code:
 * <ul>
 *   <li>{@code '} is "{@code T* string Tj}", so the line moves by the leading;</li>
 *   <li>{@code "} is "{@code aw Tw ac Tc string '}", so the two spacing parameters are <b>set</b> —
 *       and nothing in that sentence resets them. (An earlier draft of this class called them
 *       temporary; the printed definition does not, and a text-state change needs no glyph
 *       width.)</li>
 * </ul>
 * The advance of the string itself stays in D5, where the widths are readable.
 */
public final class TextStateOps {

    /**
     * What one text operator did.
     *
     * @param state  the state that follows
     * @param defect what the operator could not do (a show with no font, an out-of-range Tr), or null
     */
    public record Result(PdfGraphicsState state, String defect) { }

    private TextStateOps() {
        // static family
    }

    /**
     * Applies one text-object, text-state, text-positioning or text-showing operator.
     *
     * @param state    the state in effect
     * @param operator the registry row
     * @param stack    its operands, arity already checked
     * @return the state that follows, and the report when the operator could not be honoured
     */
    public static Result apply(PdfGraphicsState state, PdfContentOperator operator,
                               PdfOperandStack stack) {
        PdfTextState text = state.textState();
        String name = operator.name();
        switch (name) {
            // --- Table 5.4: the brackets of a text object ---------------------------------------
            case "BT" -> {
                return new Result(state.withTextState(text.atTextObjectStart()), null);
            }
            case "ET" -> {
                return new Result(state.withTextState(text.atTextObjectEnd()), null);
            }
            // --- Table 5.2: the nine parameters --------------------------------------------------
            case "Tc" -> {
                return new Result(state.withTextState(text.withCharacterSpacing(stack.number(0))),
                        null);
            }
            case "Tw" -> {
                return new Result(state.withTextState(text.withWordSpacing(stack.number(0))), null);
            }
            case "Tz" -> {
                return new Result(state.withTextState(
                        text.withHorizontalScalePercent(stack.number(0))), null);
            }
            case "TL" -> {
                return new Result(state.withTextState(text.withLeading(stack.number(0))), null);
            }
            case "Ts" -> {
                return new Result(state.withTextState(text.withRise(stack.number(0))), null);
            }
            case "Tf" -> {
                // The name keeps the slash the stream wrote, which is the form pdf.fonts() and
                // PdfTextState.fontResourceName() have always used (D0b rule 2).
                return new Result(state.withTextState(
                        text.withFont(stack.name(0).toString(), stack.number(1))), null);
            }
            case "Tr" -> {
                PdfGraphicsState next = state.withTextState(
                        text.withRenderingMode(stack.integer(0).intValue()));
                return new Result(next, next.textState().renderingModeDefect());
            }
            // --- Table 5.5: positioning ----------------------------------------------------------
            case "Td" -> {
                return new Result(state.withTextState(
                        text.moveToNextLine(stack.number(0), stack.number(1))), null);
            }
            case "TD" -> {
                return new Result(state.withTextState(text.moveToNextLineSettingLeading(
                        stack.number(0), stack.number(1))), null);
            }
            case "Tm" -> {
                // Replaces, never concatenates (S. 406) - the only text operator that does not compose.
                return new Result(state.withTextState(
                        text.setMatrices(PdfMatrix.fromOperands(stack.values()))), null);
            }
            case "T*" -> {
                return new Result(state.withTextState(text.nextLine()), null);
            }
            // --- Table 5.6: showing ----------------------------------------------------------------
            case "Tj", "TJ" -> {
                return showResult(state, text);
            }
            case "'" -> {
                // The T* half of the operator (S. 407: "' … has the same effect as the code T* string
                // Tj"); the displacement of the glyphs is D5's.
                return showResult(state, text.nextLine());
            }
            case "\"" -> {
                // S. 407 prints the definition as code:  aw Tw  ac Tc  string '  — so both spacing
                // parameters are SET, and nothing in the sentence resets them. A first draft of this
                // family called them temporary ("for the duration of the operation"); the printed
                // code says otherwise, and a text-state fact does not need a glyph width to apply.
                // What still belongs to D5 is only the advance of the glyphs themselves (S. 410).
                return showResult(state, text.withWordSpacing(stack.number(0))
                        .withCharacterSpacing(stack.number(1)).nextLine());
            }
            default -> {
                return new Result(state, null);
            }
        }
    }

    /**
     * A showing operator with no font in effect. Table 5.2, S. 398 is the bluntest sentence in the
     * chapter: "There is no initial value for either font or size; they must be specified explicitly
     * by using Tf before any text is shown." The codes stay unreadable — which the module says
     * rather than covering up with a default font.
     */
    private static Result showResult(PdfGraphicsState state, PdfTextState text) {
        PdfGraphicsState next = state.withTextState(text);
        if (!text.hasFont()) {
            return new Result(next, text.missingFontNote());
        }
        return new Result(next, null);
    }
}
