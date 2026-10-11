package org.rogmann.mcp2sdk.pdf.ops;

import org.rogmann.mcp2sdk.pdf.PdfName;
import org.rogmann.mcp2sdk.pdf.geo.PdfMatrix;
import org.rogmann.mcp2sdk.pdf.state.PdfColour;
import org.rogmann.mcp2sdk.pdf.state.PdfGraphicsState;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The colour family — 4.5.7, Table 4.24 (PDF 1.7, S. 287/288): twelve operators, six for the
 * stroking side ({@code CS SC SCN G RG K}) and six for the non-stroking one ({@code cs sc scn g rg
 * k}).
 *
 * <p>
 * This module has no colour semantics and never claimed any, and plan decision 11 says what happens
 * instead: <em>the operands must leave the stack, and nothing is derived from them.</em> Both halves
 * matter. Without the first, a {@code 1 0 0 1 RG} would leave six numbers lying around and every
 * operation after it would be grouped wrongly — a colour operator that does not consume its
 * operands corrupts the whole listing. Without the second, the module would be inventing a colour
 * space it did not read: {@code 0.5 g} means different things under {@code /DeviceGray} and under a
 * separated {@code /Separation} space, and the state therefore stores the operands, not a colour.
 * </p>
 *
 * <p>
 * What is kept is {@link PdfColour}: the operands, the space the operator named, and the byte that
 * wrote them — still renderable as source text ({@code "1 0 0 RG"}), because
 * {@link org.rogmann.mcp2sdk.pdf.PdfContentStream#formatOperand} is the same formatter
 * {@code pdf.operations()} uses. Two of each live in the state, per 4.3 Table 4.2: one for stroking,
 * one for non-stroking, each with its own colour space ({@code CS}/{@code cs}).
 * </p>
 *
 * <h2>The two things Table 4.24 says that a colour field has to obey (D8c)</h2>
 *
 * <ul>
 *   <li><b>{@code G}/{@code RG}/{@code K} set the space too.</b> "Set the stroking color space to
 *       DeviceGray (or the DefaultGray color space …) <em>and</em> set the gray level" (S. 288) — so
 *       the space field is written beside the colour, and a later {@code sc} is read against
 *       DeviceGray instead of against a space nobody set. The parenthesis is also why the answer
 *       names no more than the device space: 4.5.4, S. 257/258 lets a page's {@code ColorSpace}
 *       resources carry a {@code DefaultRGB} that remaps it into a CIE-based one. The numbers stay
 *       what the file wrote either way, so the field says {@code DeviceRGB} — the space the operator
 *       named — and the page says once, in its notes, that a default may sit under it.</li>
 *   <li><b>{@code CS}/{@code cs} reset the colour.</b> "The CS operator also sets the current
 *       stroking color to its initial value, which depends on the color space" (S. 287, which then
 *       lists those values). A space change that left the old operands standing would report as
 *       current a colour the stream had just dropped, so the reset colour carries
 *       {@code defaulted: true} and, for the spaces S. 287 names, the initial values it prints.</li>
 * </ul>
 *
 * <h2>The one range the operator's own row states</h2>
 *
 * {@code G}/{@code g}, {@code RG}/{@code rg} and {@code K}/{@code k} print "each operand must be a
 * number between 0.0 … and 1.0" (S. 288). A number outside it is reported once at its offset in the
 * walk's diagnostics and stays in the answer exactly as the file wrote it: clamping would be a
 * decision about a colour this module does not understand.
 *
 * <h2>Overprint is not handled here</h2>
 *
 * The overprint parameters {@code OP}, {@code op} and {@code OPM} are entries of a graphics state
 * parameter dictionary (Table 4.8, S. 221), not operators — Appendix A (S. 985-988) and Table 4.24
 * both list twelve colour operators and none of them is one. They reach the state through
 * {@link PdfGraphicsState#withExtGState}, which is where the "OP sets both unless op is present"
 * rule of S. 221 is decided. A stream that writes {@code OP} as an operator is named in the walk's
 * list of unknown operators.
 *
 * <h2>Arity is deliberately loose here</h2>
 *
 * {@code scn} and {@code SCN} may take <em>no</em> operand at all — a pattern name replaces the
 * components entirely (4.5.5, S. 288: "For other types of patterns, these operands must not be
 * specified") — and how many numbers the others want is a property of the colour space, which is
 * exactly what the module does not interpret. So the registry gives the family
 * {@link PdfContentOperator.Shape#MIXED} for those two and a range for {@code sc}/{@code SC}: a
 * wrong component count is a colour-space question, and a module that cannot answer it must not
 * pretend to notice when it is wrong.
 */
public final class ColourOps {

    /** The operators that set the stroking side of Table 4.2 (the lower-case ones the non-stroking one). */
    private static final Set<String> STROKING = Set.of("G", "RG", "K", "SC", "SCN", "CS");

    /** The six operators whose row in Table 4.24 prints the 0.0-1.0 rule (S. 288). */
    private static final Set<String> RANGED = Set.of("G", "g", "RG", "rg", "K", "k");

    private ColourOps() {
        // static family
    }

    /**
     * Applies one colour operator.
     *
     * @param state    the state in effect
     * @param operator the registry row (a {@code colour} one)
     * @param stack    its operands
     * @param issues   where a report about the operands goes (the 0.0-1.0 rule of S. 288)
     * @param offset   byte offset of the operator, which every colour field quotes
     * @return the state that follows — the operands are consumed either way, which is the point
     */
    public static PdfGraphicsState apply(PdfGraphicsState state, PdfContentOperator operator,
                                         PdfOperandStack stack, PdfContentInterpreter.Issues issues,
                                         int offset) {
        List<Object> operands = stack.snapshot();
        String name = operator.name();
        Long at = (long) offset;
        boolean stroking = STROKING.contains(name);
        if ("CS".equals(name) || "cs".equals(name)) {
            // The operand names the space, and the space decides the colour that follows (S. 287).
            PdfColour.Space space = spaceOf(operands, name, at);
            PdfColour reset = PdfColour.initialOf(space.name(), name, at);
            return stroking ? state.withStrokeColourSpace(space).withStrokeColour(reset)
                    : state.withNonStrokeColourSpace(space).withNonStrokeColour(reset);
        }
        String device = deviceSpaceOf(name);
        if (device != null) {
            // The space and the colour in one operator (S. 288), and the range the same row states.
            String outOfRange = outOfRange(operands);
            if (outOfRange != null && issues != null) {
                issues.add(offset, name, outOfRange);
            }
            PdfColour.Space space = new PdfColour.Space(device, name, at, false);
            PdfColour colour = new PdfColour(device, name, name, at, operands, false, null);
            return stroking ? state.withStrokeColourSpace(space).withStrokeColour(colour)
                    : state.withNonStrokeColourSpace(space).withNonStrokeColour(colour);
        }
        // SC / sc / SCN / scn: no space is named here, the state carries one - and if the state never
        // saw one, Table 4.2's DeviceGray is what the operands are read against.
        PdfColour.Space current = stroking ? state.strokeColourSpace() : state.nonStrokeColourSpace();
        String spaceName = current == null ? "DeviceGray" : current.name();
        String spaceSource = current == null ? "initial" : current.setBy();
        List<Object> components = numbers(operands);
        String note = null;
        if (components == null) {
            note = operands.isEmpty()
                    ? name + " ran with no operand - 4.5.7, S. 288 allows that only for a pattern"
                    + " space (\"For other types of patterns, these operands must not be"
                    + " specified\"), so no component is claimed"
                    : "an operand of " + name + " is not a number" + (hasName(operands)
                    ? " - in a Pattern space the operand is the pattern's resource name (S. 288:"
                    + " \"name is the name of an entry in the Pattern subdictionary\"), not a"
                    + " component" : "") + ", so no component count is claimed";
            components = operands;
        } else if (PdfColour.componentCountOf(spaceName) == null) {
            note = "the space in effect is " + (current == null ? "Table 4.2's DeviceGray"
                    : String.valueOf(spaceName)) + ", and how many components " + name + " takes"
                    + (current == null ? " is that space's one number" : " depends on it")
                    + " (4.5.7, S. 288: \"The number of operands and their interpretation depends on"
                    + " the color space\") - the operands stay readable in `text`, the components"
                    + " are not claimed";
        }
        PdfColour colour = new PdfColour(spaceName, spaceSource, name, at, components, false, note);
        return stroking ? state.withStrokeColour(colour) : state.withNonStrokeColour(colour);
    }

    /** The device space {@code G/g/RG/rg/K/k} select, or {@code null} for any other operator. */
    private static String deviceSpaceOf(String operator) {
        return switch (operator) {
            case "G", "g" -> "DeviceGray";
            case "RG", "rg" -> "DeviceRGB";
            case "K", "k" -> "DeviceCMYK";
            default -> null;
        };
    }

    /** The space operand of {@code CS}/{@code cs} — a direct name, or one of the resources. */
    private static PdfColour.Space spaceOf(List<Object> operands, String operator, Long offset) {
        Object operand = operands.isEmpty() ? null : operands.get(0);
        if (!(operand instanceof PdfName name)) {
            // The registry asks for a name; a stream that wrote something else keeps its operand off
            // the stack and leaves the space unnamed rather than named after a number.
            return new PdfColour.Space(null, operator, offset, false);
        }
        // S. 287: DeviceGray, DeviceRGB, DeviceCMYK and Pattern "always identify the corresponding
        // color spaces directly; they never refer to resources in the ColorSpace subdictionary" - so
        // those four are printed as the space they are, and every other name stays the resource name
        // it is (/CS1), because resolving it would mean reading a colour space.
        String bare = name.toString().startsWith("/") ? name.toString().substring(1)
                : name.toString();
        String direct = switch (bare) {
            case "DeviceGray", "DeviceRGB", "DeviceCMYK", "Pattern" -> bare;
            default -> null;
        };
        return new PdfColour.Space(direct == null ? name.toString() : direct, operator, offset, false);
    }

    /** The operands as numbers, or {@code null} when one of them is not a number. */
    private static List<Object> numbers(List<Object> operands) {
        List<Object> out = new ArrayList<>(operands.size());
        for (Object operand : operands) {
            if (!(operand instanceof Number)) {
                return null;
            }
            out.add(operand);
        }
        return out.isEmpty() ? null : out;
    }

    private static boolean hasName(List<Object> operands) {
        for (Object operand : operands) {
            if (operand instanceof PdfName) {
                return true;
            }
        }
        return false;
    }

    /**
     * The sentence for an operand outside 0.0-1.0, or {@code null}. One sentence per operator; the
     * file's number is kept as written (S. 288 states the range, it does not authorise a reader to
     * change the operand).
     */
    private static String outOfRange(List<Object> operands) {
        List<String> offenders = new ArrayList<>();
        for (Object operand : operands) {
            if (operand instanceof Number number) {
                double value = number.doubleValue();
                if (value < 0.0 || value > 1.0) {
                    offenders.add(PdfMatrix.format(value));
                }
            }
        }
        if (offenders.isEmpty()) {
            return null;
        }
        return "a colour operand is " + String.join(", ", offenders)
                + " - 4.5.7, S. 288: \"Each operand must be a number between 0.0 … and 1.0\"; the"
                + " number stays in the answer as the file wrote it, because clamping it would be a"
                + " colour decision this module does not make";
    }
}
