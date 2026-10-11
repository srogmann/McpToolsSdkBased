package org.rogmann.mcp2sdk.pdf.state;

import org.rogmann.mcp2sdk.pdf.PdfContentStream;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A colour as this module is allowed to know it — the numbers the file wrote, the space the operator
 * named, and the byte that wrote them, <b>without a meaning attached</b> (increment D8c of
 * {@code docs/js/pdf-content-plan.md}, decision 16).
 *
 * <h2>Why a field of its own and not a sentence</h2>
 *
 * Until D8 the two colour parameters of Table 4.2 (S. 210/211) were kept as a source line —
 * {@code "0.27451 0.41961 0.6902 rg"} — which is honest but not readable: a caller that wants the
 * blue of a heading has to parse the string it was handed, and a second field carrying the same
 * numbers as numbers would be a second claim about the same fact (decision 10's argument, applied
 * here). So the field became the structure and kept the sentence inside it as {@code text}: nothing
 * that was readable before stopped being readable.
 *
 * <h2>What the module does not do with these numbers</h2>
 *
 * Nothing. It does not convert (no CMYK→RGB, no ICC profile, no rendering intent, no Lab), it does
 * not follow a document's default colour spaces, and it does not name a colour. S. 288 is the
 * reason for the last two: {@code rg} sets the space to DeviceRGB <em>"or the DefaultRGB color
 * space"</em>, and 4.5.4 S. 257/258 lets a page's {@code ColorSpace} resources send that space
 * through a CIE-based one — the three numbers stay exactly what the file wrote either way, and what
 * a viewer finally paints with them is not this module's claim. The numbers are the file's, the
 * space is the operator's, and the {@code offset} is the reader's way back into
 * {@code pdf.operations()}.
 *
 * @param space       the colour space the operator named ({@code DeviceRGB} for {@code rg}, the
 *                    resource name {@code /CS1} for {@code /CS1 cs}), or {@code null} when the
 *                    operand names no space this module can read
 * @param spaceSource which operator named that space — {@code rg} names it beside its colour,
 *                    {@code CS}/{@code cs} name it alone, and {@code initial} is Table 4.2's
 *                    DeviceGray with no operator at all
 * @param operator    the operator that set the colour, or {@code null} for the specification's
 *                    initial value
 * @param offset      its byte offset in the stream (the number a caller pastes into
 *                    {@code pdf.operations(path, objNum, {after: offset})}), or {@code null}
 * @param components  the component values — the operands the operator wrote, or the initial values
 *                    of the space when {@code defaulted} says so
 * @param defaulted   whether these are the <b>specification's</b> initial values rather than
 *                    operands: the black of Table 4.2 (S. 211) and the reset that every {@code CS}
 *                    and {@code cs} performs (S. 287: "The CS operator also sets the current
 *                    stroking color to its initial value, which depends on the color space")
 * @param note        why a number is missing (the component count of {@code sc}/{@code scn} belongs
 *                    to the colour space, S. 288), or {@code null}
 */
public record PdfColour(String space, String spaceSource, String operator, Long offset,
                        List<Object> components, boolean defaulted, String note) {

    /** The component values, never null. */
    public PdfColour {
        components = components == null ? List.of() : List.copyOf(components);
    }

    /**
     * The components as numbers, or {@code null} when they are not all numbers. An
     * {@code scn /P1 scn} carries a pattern <em>name</em> where the components of a device space
     * would stand, and an {@code sc} under a space this module did not resolve can be any count of
     * numbers (4.5.7, S. 288: "The number of operands and their interpretation depends on the color
     * space") — no numbers is the answer, and the note says why.
     */
    public List<Double> numberComponents() {
        List<Double> numbers = new ArrayList<>(components.size());
        for (Object component : components) {
            if (!(component instanceof Number number)) {
                return null;
            }
            numbers.add(number.doubleValue());
        }
        return numbers.isEmpty() ? null : numbers;
    }

    /**
     * The setting as the stream wrote it ({@code "0 0 0 rg"}), or {@code null} when nothing was
     * written: a defaulted colour has no source line, and inventing one
     * ({@code "0 g"}) would print an operator the file never used.
     */
    public String text() {
        if (defaulted || operator == null) {
            return null;
        }
        return PdfContentStream.formatOperation(new ArrayList<>(components), operator);
    }

    /** The one-line form {@link PdfGraphicsState#describe()} prints. */
    public String describe() {
        String line = text();
        if (line != null) {
            return line;
        }
        return (space == null ? "no" : space) + " initial value"
                + (operator == null ? "" : " (reset by " + operator + ")");
    }

    /** The answer's form of this colour (decision 16: structure, with the source text inside). */
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("space", space);
        out.put("spaceSource", spaceSource);
        out.put("operator", operator);
        out.put("offset", offset);
        out.put("components", numberComponents());
        String line = text();
        if (line != null) {
            out.put("text", line);
        }
        if (defaulted) {
            out.put("defaulted", true);
        }
        if (note != null) {
            out.put("note", note);
        }
        return out;
    }

    // =============================================================================================
    // The specification's own values
    // =============================================================================================

    /** Table 4.2, S. 210/211: no operator ran — the space is DeviceGray, the colour is black. */
    public static PdfColour initialDeviceGray() {
        return new PdfColour("DeviceGray", "initial", null, null, List.of(0.0), true,
                "no colour operator ran; DeviceGray and black are the initial values of Table 4.2,"
                        + " S. 210/211 - the file never named this colour");
    }

    /** The same initial value under a space a {@code CS}/{@code cs} named (S. 287). */
    public static PdfColour initialOf(String space, String setBy, Long offset) {
        List<Object> components = initialComponentsOf(space);
        return new PdfColour(space, setBy, setBy, offset, components, true,
                components == null
                        ? "the CS operator resets the colour to the initial value of its space"
                        + " (S. 287), which depends on the space - a space this module does not"
                        + " interpret has no component count to name"
                        : null);
    }

    /**
     * The initial colour of the spaces S. 287 lists by name: 0.0 in every component for
     * Device/Cal Gray and RGB, {@code [0 0 0 1]} for DeviceCMYK, 0 for Indexed, the tint 1.0 for
     * Separation/DeviceN. Anything else (Lab, ICCased with a {@code Range}, a Pattern) keeps
     * {@code null}: guessing a component count for a space this module does not read would be the
     * invented number the plan refuses everywhere else.
     */
    public static List<Object> initialComponentsOf(String space) {
        if (space == null) {
            return null;
        }
        return switch (space) {
            case "DeviceGray", "CalGray", "Indexed" -> List.of(0.0);
            case "DeviceRGB", "CalRGB" -> List.of(0.0, 0.0, 0.0);
            case "DeviceCMYK" -> List.of(0.0, 0.0, 0.0, 1.0);
            case "Separation", "DeviceN" -> null;    // 1.0 per colorant - how many is the space's
            default -> null;
        };
    }

    /** Whether a name is one of the three device spaces, whose component count is known (S. 242). */
    public static boolean isDeviceSpace(String space) {
        return "DeviceGray".equals(space) || "DeviceRGB".equals(space)
                || "DeviceCMYK".equals(space);
    }

    /** How many components a device space has, or {@code null} for any other space. */
    public static Integer componentCountOf(String space) {
        return switch (space == null ? "" : space) {
            case "DeviceGray" -> 1;
            case "DeviceRGB" -> 3;
            case "DeviceCMYK" -> 4;
            default -> null;
        };
    }

    @Override
    public String toString() {
        return describe();
    }

    /**
     * The colour <em>space</em> parameter of Table 4.2 — the same "named, not interpreted" rule for
     * the field that decides how a colour is read.
     *
     * @param name       {@code DeviceGray}/{@code DeviceRGB}/{@code DeviceCMYK}/{@code Pattern} when
     *                   the operand named one directly (S. 287: those names "never refer to
     *                   resources in the ColorSpace subdictionary"), otherwise the resource name from
     *                   the {@code ColorSpace} subdictionary, {@code /CS1}
     * @param setBy      the operator that set it, or {@code initial}
     * @param offset     its byte offset, or {@code null}
     * @param defaulted  whether no {@code CS}/{@code cs} ran and this is Table 4.2's DeviceGray
     */
    public record Space(String name, String setBy, Long offset, boolean defaulted) {

        /** Table 4.2, S. 210: "There are two separate color space parameters … Initial value:
         * DeviceGray." */
        public static Space initial() {
            return new Space("DeviceGray", "initial", null, true);
        }

        /** The source line that set it, or null for the initial value (and for a space the
         * operand did not name — printing "null cs" would be worse than printing nothing). */
        public String text() {
            if (name == null || setBy == null || defaulted) {
                return null;
            }
            return name + " " + setBy;
        }

        /** The answer's form. */
        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("name", name);
            out.put("setBy", setBy);
            out.put("offset", offset);
            String line = text();
            if (line != null) {
                out.put("text", line);
            }
            if (defaulted) {
                out.put("defaulted", true);
            }
            return out;
        }

        @Override
        public String toString() {
            String line = text();
            return line != null ? line : name + " (initial)";
        }
    }
}
