package org.rogmann.mcp2sdk.pdf.geo;

import org.rogmann.mcp2sdk.pdf.PdfException;

import java.util.List;

/**
 * The coordinate <em>space</em> a number belongs to (PDF 1.7 4.2.1 "Coordinate Spaces", S. 199-204;
 * ISO 32000-2 8.3.2).
 * <p>
 * The specification is blunt about why this matters: a coordinate space is determined by the
 * location of its origin, the orientation of its axes and the length of its units (S. 199), and
 * "what may appear to be absolute coordinates in a content stream are not absolute with respect to
 * the current page because they are expressed in a coordinate system that may slide around and
 * shrink or expand" (S. 202, about {@code cm}). A bare number therefore answers no question at all
 * - {@code 48} is a different place in every one of the spaces below. Every point, vector,
 * rectangle and quadrilateral of this package carries the space it lives in, and every number a
 * result reports names it (plan decision 7: "ein Feld ohne Raum ist ein Fehler im Design").
 * </p>
 * <p>
 * The eight names are the spaces of the specification (4.2.1, S. 199-204 and 4.9.3,
 * S. 357) plus the one this module <em>asserts</em>:
 * </p>
 * <table>
 *   <caption>spaces, their axes and the matrix that reaches the next one</caption>
 *   <tr><th>name</th><th>axes / origin</th><th>reached through</th><th>from the file</th></tr>
 *   <tr><td>{@code glyph}</td><td>Type 3 glyph space; for every other font 1000 units per unit of
 *       text space</td><td>font matrix (5.1.3, S. 393; 5.5.4 for Type 3)</td><td>yes</td></tr>
 *   <tr><td>{@code text}</td><td>baseline of the font size</td><td>text matrix plus {@code T_fs},
 *       {@code T_h} (5.3.1, 5.3.3)</td><td>yes</td></tr>
 *   <tr><td>{@code user}</td><td><b>as the operands of the stream stand</b> - after every
 *       {@code cm}</td><td>current transformation matrix</td><td>yes</td></tr>
 *   <tr><td>{@code defaultUser}</td><td>page's own basis before any {@code cm}: CropBox as the
 *       visible rectangle, positive x right, positive y up (subject to {@code Rotate}), unit
 *       {@code UserUnit} × 1/72 inch (S. 201)</td><td>page geometry</td><td>yes</td></tr>
 *   <tr><td>{@code page}</td><td><b>this module's claim</b>: origin top-left, y pointing down,
 *       unit = 1/72 inch</td><td>MediaBox origin, {@code Rotate}, {@code UserUnit}</td>
 *       <td>yes</td></tr>
 *   <tr><td>{@code device}</td><td>pixels of one output device</td><td>page space × dpi</td>
 *       <td><b>no</b> - the viewer decides it (S. 199/200)</td></tr>
 * </table>
 * <p>
 * {@code device} is listed because a caller may ask for it - and because it is the one space this
 * module can only reach through an input the file does not contain, which is why
 * {@link #isViewerDependent(String)} exists and a {@code dpi} that appears in a result is always
 * labelled as the caller's own number.
 * </p>
 */
public final class PdfSpace {

    /** Glyph space: the space a Type 3 glyph description is written in (5.1.3, S. 393). */
    public static final String GLYPH = "glyph";

    /** Text space: the baseline the font size is measured on (5.3.1, S. 406). */
    public static final String TEXT = "text";

    /**
     * User space <em>as the operands stand</em>: what the numbers of a content stream mean after
     * every {@code cm} that was applied to them (4.2.1, S. 202).
     */
    public static final String USER = "user";

    /**
     * Default user space: the page's own basis, before any {@code cm} - CropBox, x right, y up,
     * unit {@code UserUnit} × 1/72 inch (4.2.1, S. 201, Table 3.27).
     */
    public static final String DEFAULT_USER = "defaultUser";

    /**
     * Page space: the space this module claims to answer in - origin top-left, y down, unit pt.
     * Derived from the page, not from a device: MediaBox origin, {@code Rotate}, {@code UserUnit}.
     */
    public static final String PAGE = "page";

    /**
     * Image space: the unit square every sampled image is defined in. Not the caller's to change -
     * 4.2.1, S. 203: "All sampled images are defined in image space. The transformation from image
     * space to user space is predefined and cannot be changed. All images are 1 unit wide by 1 unit
     * high in user space, regardless of their dimensions in sample data values." A picture's box is
     * therefore this square mapped through the CTM, never its pixel count.
     */
    public static final String IMAGE = "image";

    /**
     * Form space: the coordinate system a form XObject is written in. 4.9.3, S. 357: "Each form
     * XObject is defined in its own coordinate system, called form space. The BBox entry in the form
     * dictionary is expressed in form space, as are any coordinates used in the form XObject's
     * content stream." The form dictionary's {@code /Matrix} is what leads out of it (default:
     * identity, Table 4.45, S. 358).
     */
    public static final String FORM = "form";

    /** Device space: pixels of one output device; needs the caller's dpi, never the file's (S. 199). */
    public static final String DEVICE = "device";

    /**
     * All eight, in the order of 4.2.1 (upstream to downstream): the four spaces a stream can be
     * written <em>in</em>, then the page's own basis, then the two the module reaches by mapping.
     * Pattern space (the ninth of S. 203) is missing on purpose - this module reads pattern
     * dictionaries but never paints one, so it has no number to label.
     */
    public static final List<String> ALL =
            List.of(GLYPH, TEXT, IMAGE, FORM, USER, DEFAULT_USER, PAGE, DEVICE);

    private PdfSpace() {
        // Utility class
    }

    /**
     * Checks a space label. A label outside the eight is a call error, not an empty answer: a result
     * that would carry an unknown space could not be read back.
     *
     * @param space the label to check
     * @return the label, unchanged
     * @throws org.rogmann.mcp2sdk.pdf.PdfException when the label is not one of {@link #ALL}
     */
    public static String require(String space) {
        if (space == null || !ALL.contains(space)) {
            throw new PdfException("Unknown coordinate space " + space + " - a coordinate belongs to"
                    + " one of " + String.join(", ", ALL) + " (4.2.1, S. 199-204)");
        }
        return space;
    }

    /** Whether a label is one of the eight. */
    public static boolean isKnown(String space) {
        return space != null && ALL.contains(space);
    }

    /**
     * Whether a space can only be reached with an input the file does not name. True for
     * {@code device} (its resolution is the viewer's, 4.2.1, S. 199/200); false for the seven
     * spaces a page or a stream defines by itself.
     */
    public static boolean isViewerDependent(String space) {
        require(space);
        return DEVICE.equals(space);
    }

    /**
     * One sentence about a space, with its specification anchor - the text a help output or a note
     * shows, so that a result can explain its own labels.
     */
    public static String describe(String space) {
        return switch (require(space)) {
            case GLYPH -> "glyph space: the space a glyph description is written in; the font"
                    + " matrix maps 1000 units of it to one unit of text space, a Type 3 font names"
                    + " its own (5.1.3, S. 393)";
            case TEXT -> "text space: the baseline a font size is measured on; T_m and T_fs lead"
                    + " out of it (5.3.1, S. 406)";
            case IMAGE -> "image space: the unit square a sampled image is defined in - predefined,"
                    + " not changeable, and 1 unit wide by 1 unit high in user space whatever the"
                    + " sample data holds (4.2.1, S. 203)";
            case FORM -> "form space: the coordinate system a form XObject is written in; its /BBox"
                    + " and its own content-stream coordinates live here, and its /Matrix leads out"
                    + " (default identity) (4.9.3, S. 357; Table 4.45, S. 358)";
            case USER -> "user space as the operands stand: what the numbers of the content stream"
                    + " mean after every cm applied to them (4.2.1, S. 202)";
            case DEFAULT_USER -> "default user space: the page's own basis before any cm - CropBox,"
                    + " x right, y up, unit UserUnit x 1/72 inch (4.2.1, S. 201)";
            case PAGE -> "page space: origin top-left, y down, unit pt - the space this module"
                    + " answers in, from MediaBox, Rotate and UserUnit";
            case DEVICE -> "device space: pixels of one output device; the file does not name its"
                    + " resolution, so a dpi in a result is the caller's own input (4.2.1, S. 199)";
            default -> throw new PdfException("unreachable");
        };
    }
}
