package org.rogmann.mcp2sdk.pdf.ops;

import org.rogmann.mcp2sdk.pdf.PdfFontScope;
import org.rogmann.mcp2sdk.pdf.PdfString;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The third sink: the page text behind {@code pdf.text()} — increment D4 (docs/js/pdf-content-plan.md,
 * Aufgabe 4), the same readable string the hand-written loop of {@code PdfContentStream.textInternal()}
 * used to build.
 *
 * <h2>What moved and what did not</h2>
 *
 * What moved is <em>where the facts come from</em>. The old loop kept its own operand list, searched
 * the {@code Tf} of a string out of that list and remembered the font in a local variable that no
 * {@code Q} could take back. Here the operands come from {@link Operation#operands()} — parsed once,
 * with their real offsets — and the font comes from {@link Operation#stateBefore()}, so a {@code q/Q}
 * pair around a showing operator, a {@code Tf} whose operands do not fit its registry row, and the
 * font that was already in effect when a marked-content sequence opened are all answered by the walk
 * instead of by a guess at the operator's left-hand side.
 *
 * What did <em>not</em> move is the line logic, on purpose (plan decision 10: "{@code pdf.text()}
 * bleibt, wie es ist, bis {@code placement()} nachweislich die bessere Wortlücken-Rechnung liefert").
 * The new-line rules below are the old ones, kept as a documented heuristic:
 * <ul>
 *   <li>{@code BT}/{@code ET} end the current line,</li>
 *   <li>{@code T*}, {@code '} and {@code "} always start a new line (Table 5.6, S. 407 prints them as
 *       {@code T* string Tj}),</li>
 *   <li>a {@code Td}/{@code TD} whose {@code ty} is not zero starts a new line, one whose {@code ty}
 *       is zero and whose {@code tx} is not inserts a space,</li>
 *   <li>a {@code Tm} that moves {@code f} starts a new line,</li>
 *   <li>a number in a {@code TJ} array at or below {@value #WORD_GAP_THRESHOLD} is a space.</li>
 * </ul>
 * Each of those is a stand-in for a real displacement computation, and each is the same stand-in the
 * answer has always carried. {@code docs/js/pdf-content-plan.md} decision 10 lets it be replaced only
 * once the computed answer has been written next to it; increment D5 wrote that pair down
 * ({@code target/scratch/pdf-d5-pair.js}), increment D7 read it and <em>kept</em> the rule — on this
 * corpus the heuristic is not wrong, it is <em>blind</em>: a number it ignores opens a gap it cannot
 * see, and a number it honours is scaled by {@code Tz}, {@code Tc}, the CTM and {@code UserUnit},
 * none of which the rule reads. Replacing it would move an answer users compare in order to gain a
 * precision this module would then have to earn back per document. What D7 did instead is count the
 * blindness where it bites: {@link #wordGaps()} says how many numbers the rule was given, how many
 * became a space and how many stayed an opening the text does not show, and {@code pdf.text()}
 * prints that as {@code wordGaps}.
 *
 * <h2>Reading operands from the operator backwards</h2>
 *
 * A content stream is postfix, and the old loop read its operands from the end ({@code ty} is the last
 * number, {@code tx} the one before it). The sink still does, for one reason: it answers a malformed
 * stream exactly as the loop answered it. With a well-formed {@code Td} both readings are the same
 * operand, and with a malformed one the registry has already said what it thinks
 * ({@link PdfOperandStack#defectFor}) — the text is then whatever the old extract was, and the
 * finding is in the walk's diagnostics rather than in a different string.
 *
 * <h2>What a scope does to the characters</h2>
 *
 * Given a {@link PdfFontScope}, every string is read through the font that was in effect at its
 * operator, and what the mapping chain cannot answer stays out of the text while the scope counts it
 * ({@code mapping.unmapped}, {@code unmappedSample}). Without a scope the strings read by the document
 * convention — the raw view, unchanged since this module could read a page.
 */
public final class TextSink implements PdfContentSink {

    /**
     * A {@code TJ} adjustment at or below this value is read as a space.
     * <p>
     * A rule of thumb, not a measurement: Table 5.6, S. 407 says only that the number is
     * <em>subtracted</em> from the coordinate in thousandths of the font size, and where a word gap
     * begins is nowhere decided by the specification. It is documented here because it is the number
     * that decides whether the extracted text has spaces in it, and because Aufgabe 5 may only
     * replace it with the computed advance after writing both answers down next to each other.
     */
    public static final double WORD_GAP_THRESHOLD = -120;

    /** The font answers per string, or null for the document convention (the raw view). */
    private final PdfFontScope scope;
    private final StringBuilder out = new StringBuilder();
    private final StringBuilder line = new StringBuilder();

    /**
     * What the threshold below did to this page — counted from D7 so that the heuristic names its
     * own blind spot where it is used (plan decision 10 says {@code pdf.text()} keeps the rule;
     * saying so is not the same as being quiet about what the rule cannot see).
     *
     * @param numbers          every number a {@code TJ} array offered
     * @param spaces           how many of them became a space in the text
     * @param ignoredOpenings  negative numbers the threshold passed over — each one opens a gap the
     *                         extracted text does not show
     * @param widestIgnoredOpening the widest of those, as the file wrote it (thousandths of the size),
     *                         or null when every ignored number was positive and so closed a gap
     *                         instead of opening one
     */
    public record WordGaps(long numbers, long spaces, long ignoredOpenings,
                           Double widestIgnoredOpening) {

        /** Whether this page's spaces came from the threshold at all. */
        public boolean asked() {
            return numbers > 0;
        }

        /**
         * The page's {@code wordGaps} block: the numbers, and one sentence where the threshold
         * actually passed an opening over. Null for a page no {@code TJ} array touched, so the text
         * extract of an ordinary page keeps exactly the fields it always had.
         */
        public Map<String, Object> block() {
            if (!asked()) {
                return null;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("threshold", WORD_GAP_THRESHOLD);
            out.put("numbers", numbers);
            out.put("spaces", spaces);
            out.put("ignoredOpenings", ignoredOpenings);
            if (widestIgnoredOpening != null) {
                out.put("widestIgnoredOpening", widestIgnoredOpening);
                // Singular or plural, never "1 number(s)": the sentence names the count, so it has to
                // read like one instead of like a template. The fact it carries is the same either way.
                out.put("note", (ignoredOpenings == 1
                        ? "the one number the threshold ignored is an operand value in thousandths of"
                          + " the font size (Table 5.6, S. 407), and it opens a gap this text does not"
                          + " show"
                        : "the " + ignoredOpenings + " numbers the threshold ignored are operand"
                          + " values in thousandths of the font size (Table 5.6, S. 407), and each"
                          + " opens a gap this text does not show")
                        + "; the threshold is applied to that raw number, so Tz, Tc, the CTM and"
                        + " UserUnit are not in it - pdf.placement() answers the same operand as a"
                        + " computed distance in a named space (5.3.3, S. 410)");
            }
            return out;
        }
    }

    private long tjNumbers;
    private long wordGaps;
    private long ignoredOpenings;
    private Double widestIgnored;

    /**
     * The line the last positioning operator moved — the heuristic's whole memory, and the reason
     * this sink is not a pure function of one operation.
     */
    private double lineX = Double.NaN;
    private double lineY = Double.NaN;
    /** The font the operator being handled showed in, read from the walk's state. */
    private String font;

    /** Text through the page's fonts. @param scope the page's fonts, or null for the document convention */
    public TextSink(PdfFontScope scope) {
        this.scope = scope;
    }

    /** The raw view: every string by the document convention. */
    public TextSink() {
        this(null);
    }

    @Override
    public void onOperation(PdfContentInterpreter.Operation operation) {
        List<Object> operands = operation.operands();
        // The font this operator showed in — the state's, not a local memory of the last Tf.
        this.font = operation.fontResourceName();
        switch (operation.name()) {
            case "BT", "ET" -> flushLine();
            // The font of a showing operator is the one the walk carries (stateBefore), not the one
            // this sink remembers: a Q takes a Tf back, and a Tf with unusable operands sets nothing.
            case "Tf" -> {
                // nothing to do — the interpreter's state is the font
            }
            case "T*", "'", "\"" -> {
                flushLine();
                out.append('\n');
                if (!"T*".equals(operation.name())) {
                    appendString(fromEnd(operands, 1));
                }
            }
            case "Td", "TD" -> {
                double tx = numberAt(operands, 2);
                double ty = numberAt(operands, 1);
                if (Double.isNaN(lineY)) {
                    lineX = tx;
                    lineY = ty;
                } else {
                    lineX += tx;
                    lineY += ty;
                    if (ty != 0) {
                        flushLine();
                        out.append('\n');
                    } else if (tx != 0 && line.length() > 0) {
                        line.append(' ');
                    }
                }
            }
            case "Tm" -> {
                double e = numberAt(operands, 2);
                double f = numberAt(operands, 1);
                if (!Double.isNaN(lineY) && f != lineY) {
                    flushLine();
                    out.append('\n');
                }
                lineX = e;
                lineY = f;
            }
            case "Tj" -> appendString(fromEnd(operands, 1));
            case "TJ" -> appendArray(fromEnd(operands, 1));
            default -> {
                // not a text operator: the walk still read it (state!), the extract never saw it
            }
        }
    }

    /**
     * The extracted text, exactly as {@code PdfContentStream.text()} returns it: one line per text
     * line, each ending in {@code \n}, the last one included when the stream showed anything on it.
     */
    public String text() {
        flushLine();
        return out.toString();
    }

    /** The text is finished when the stream is — the trailing line is flushed here, not by a getter. */
    @Override
    public void endStream(PdfContentInterpreter.Summary summary) {
        flushLine();
    }

    /** How many characters the text has so far (a caller that wants to cut, cuts). */
    public int length() {
        return out.length() + line.length();
    }

    @Override
    public String toString() {
        return length() + " character(s) of text";
    }

    // =============================================================================================
    // the pieces of the extract, each the old one
    // =============================================================================================

    private void flushLine() {
        if (line.length() > 0) {
            out.append(line).append('\n');
            line.setLength(0);
        }
    }

    private void appendString(Object value) {
        if (value instanceof PdfString string) {
            line.append(showString(string));
        }
    }

    /**
     * One string of the stream as characters: through its font when a scope was given, through the
     * document convention otherwise — which is what a scope does too for a font that has no source of
     * its own, so the raw and the mapped view agree wherever the font has nothing to say.
     */
    private String showString(PdfString string) {
        if (scope == null) {
            return string.text();
        }
        // The scope takes the string itself: its bytes are the codes, and handing them out without a
        // copy is a privilege of the package PdfString lives in.
        return scope.show(string, font);
    }

    /**
     * A {@code TJ} array: strings appended, numbers consulted. A number is an adjustment in
     * thousandths of the type size and subtracts (Table 5.6, S. 407), so a negative one opens a gap —
     * and a gap of {@value #WORD_GAP_THRESHOLD} or more is what this module calls a space.
     */
    private void appendArray(Object value) {
        if (!(value instanceof List<?> list)) {
            return;
        }
        for (Object item : list) {
            if (item instanceof PdfString string) {
                line.append(showString(string));
            } else if (item instanceof Long number) {
                countAdjustment(number);
                if (number <= WORD_GAP_THRESHOLD) {
                    line.append(' ');
                }
            } else if (item instanceof Double real) {
                countAdjustment(real);
                if (real <= WORD_GAP_THRESHOLD) {
                    line.append(' ');
                }
            }
        }
    }

    /**
     * One number of a {@code TJ} array, booked before the threshold decides anything: what the file
     * offered, what became a space, and what stayed an opening the extract will not show. The last
     * two are the whole point of the count — a page whose every ignored number is positive closes
     * gaps rather than opening them, and says so with {@code ignoredOpenings: 0}.
     */
    private void countAdjustment(double number) {
        tjNumbers++;
        if (number <= WORD_GAP_THRESHOLD) {
            wordGaps++;
        } else if (number < 0) {
            ignoredOpenings++;
            if (widestIgnored == null || number < widestIgnored) {
                widestIgnored = number;
            }
        }
    }

    /**
     * What the word-gap rule did to this page (D7). {@code threshold} is the rule, the rest is what
     * it was given and what it passed over — the measurement {@code docs/js/pdf-content-plan.md}
     * calls the {@code -120} pair, now readable per page instead of only in a scratch run.
     */
    public WordGaps wordGaps() {
        return new WordGaps(tjNumbers, wordGaps, ignoredOpenings, widestIgnored);
    }

    /** The operand {@code fromEnd} positions before the operator ({@code 1} = the last one). */
    private static Object fromEnd(List<Object> operands, int fromEnd) {
        int index = operands.size() - fromEnd;
        return index >= 0 && index < operands.size() ? operands.get(index) : null;
    }

    /** The operand {@code fromEnd} positions before the operator as a number, or NaN. */
    private static double numberAt(List<Object> operands, int fromEnd) {
        Object value = fromEnd(operands, fromEnd);
        if (value instanceof Double real) {
            return real;
        }
        if (value instanceof Long number) {
            return number.doubleValue();
        }
        return Double.NaN;
    }
}
