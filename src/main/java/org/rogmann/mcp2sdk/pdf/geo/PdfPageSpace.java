package org.rogmann.mcp2sdk.pdf.geo;

import org.rogmann.mcp2sdk.pdf.PdfException;

import java.util.Optional;

/**
 * One page's own frame: where its visible box sits in default user space, how its unit is
 * defined and which way round it is displayed - and therefore the one matrix that leads from
 * default user space into {@link PdfSpace#PAGE page space} (PDF 1.7 4.2.1 "Coordinate Spaces",
 * S. 201; Table 3.27 "Entries in a page object", S. 145-147; 3.8.4 "Rectangles", S. 161).
 * <p>
 * D1 shipped the six numbers and refused to compose this one, because nothing in the corpus
 * exercises it: 876 pages, every MediaBox {@code [0 0 612 792]} with origin {@code (0, 0)},
 * {@code Rotate} absent or 0 on every one of them, {@code UserUnit} named nowhere. A matrix that
 * no file demands is exactly the matrix that gets typed from memory, so this class builds it from
 * the primitives of {@link PdfMatrix} - translate, scale, rotate, flip - and every case is checked
 * against the corners of the box rather than against a hand-written six-tuple.
 * </p>
 *
 * <h2>What the page of the specification is made of (S. 201, read verbatim)</h2>
 *
 * "The {@code CropBox} entry in the page dictionary specifies the rectangle of user space
 * corresponding to the visible area of the intended output medium (display window or printed
 * page). The positive x axis extends horizontally to the right and the positive y axis vertically
 * upward, as in standard mathematical practice (subject to alteration by the {@code Rotate} entry
 * in the page dictionary). The length of a unit along both the x and y axes is set by the
 * {@code UserUnit} entry (PDF 1.6) in the page dictionary … If that entry is not present or
 * supported, the default value of 1/72 inch is used."
 * <p>
 * Three consequences, and this class is built around them:
 * </p>
 * <ul>
 *   <li><b>The visible box is the CropBox, else the MediaBox.</b> Table 3.27, S. 145: CropBox is
 *       "defining the visible region of default user space. … Default value: the value of
 *       MediaBox". A page that crops therefore has its page-space origin at the <em>crop</em>
 *       corner, not at the physical medium's - {@link #basis()} says which of the two the frame
 *       used, because "we assumed the MediaBox" must never be an unspoken assumption.</li>
 *   <li><b>The origin need not be (0, 0).</b> "In PostScript, the origin of default user space
 *       always corresponds to the lower-left corner of the output medium. While this convention is
 *       common in PDF documents as well, it is not required; the page dictionary's CropBox entry
 *       can specify any rectangle of default user space to be made visible" (Note, S. 201). So the
 *       box's lower-left corner is subtracted, not the page height.</li>
 *   <li><b>{@code UserUnit} scales the unit.</b> It is a number, not necessarily an integer; a
 *       {@code UserUnit} of 2 doubles every distance from default user space into pt.</li>
 * </ul>
 *
 * <h2>Page space, the space this module answers in</h2>
 *
 * Origin at the top-left of the visible box, x to the right, <b>y downwards</b>, unit one point -
 * the space a caller means by "where on the page", reached by rotating the page {@code Rotate}
 * degrees <em>clockwise</em> (Table 3.27, S. 146: "the number of degrees by which the page should
 * be rotated clockwise when displayed or printed. The value must be a multiple of 90. Default
 * value: 0") and turning the y axis around. The four rotations are derived in
 * {@link #defaultUserToPage()} corner by corner; the check {@code 1 0 0 -1 0 792 cm} that opens all
 * 875 content streams of the check document is this matrix for the unrotated case, which is why
 * the {@code Höhe − y} shortcut and a correct CTM chain disagree (plan decision 5 stores clip
 * regions in this space precisely so that no later {@code cm} can move them).
 * <p>
 * This class maps between <em>default user space</em> and page space. The CTM is not involved and
 * cannot be: the CTM maps user space (as the operands stand) into default user space, and
 * {@link PdfMatrix#multiply(PdfMatrix)} is how the two halves are joined - see
 * {@code PdfGraphicsState.userToPageMatrix(…)}, which does exactly that.
 * </p>
 */
public final class PdfPageSpace {

    /**
     * Points per inch in a space whose unit is 1/72 inch (4.2.1, S. 201: "the default value of
     * 1/72 inch is used", with the note that this is approximately, not exactly, a point).
     */
    public static final double POINTS_PER_INCH = 72.0;

    /** The frame used the CropBox, because the page names one (Table 3.27, S. 145). */
    public static final String BASIS_CROP_BOX = "cropBox";

    /** The frame used the MediaBox, because no CropBox is named (its documented default). */
    public static final String BASIS_MEDIA_BOX = "mediaBox";

    private final PdfRect mediaBox;
    private final PdfRect cropBox;
    private final double userUnit;
    private final int requestedRotate;
    private final int rotate;
    private final String defect;

    private PdfPageSpace(PdfRect mediaBox, PdfRect cropBox, double userUnit, int requestedRotate,
                         int rotate, String defect) {
        this.mediaBox = mediaBox;
        this.cropBox = cropBox;
        this.userUnit = userUnit;
        this.requestedRotate = requestedRotate;
        this.rotate = rotate;
        this.defect = defect;
    }

    /**
     * The smallest frame there is: a MediaBox, nothing else named. Every default of Table 3.27
     * applies - CropBox inherits the MediaBox, {@code Rotate} defaults to 0, and the unit stays at
     * 1/72 inch (S. 201).
     *
     * @throws PdfException when the box is missing or lives in another space than default user
     *                      space (a MediaBox is "expressed in default user space units", S. 145)
     */
    public static PdfPageSpace of(PdfRect mediaBox) {
        return of(mediaBox, null, null, null);
    }

    /**
     * The frame of one page, from the four values its dictionary names.
     *
     * @param mediaBox  the MediaBox (required, Table 3.27: "Required; inheritable")
     * @param cropBox   the CropBox, or {@code null} when the page names none - then the MediaBox
     *                  is the visible region, which is the default the table prints
     * @param userUnit  the {@code UserUnit} value, or {@code null} for "not named" (= 1)
     * @param rotate    the {@code Rotate} value in degrees, or {@code null} for "not named" (= 0);
     *                  negative values and values ≥ 360 are normalised into 0/90/180/270
     * @throws PdfException for a missing MediaBox or a box in a space other than default user
     *                      space - those are call errors, unlike a file's odd values, which are
     *                      reported by {@link #defect()}
     */
    public static PdfPageSpace of(PdfRect mediaBox, PdfRect cropBox, Double userUnit,
                                  Integer rotate) {
        if (mediaBox == null) {
            throw new PdfException("A page frame needs the MediaBox - it is the one box a page"
                    + " dictionary must have, inheritable at that (Table 3.27, S. 145)");
        }
        requireDefaultUser(mediaBox, "MediaBox");
        if (cropBox != null) {
            requireDefaultUser(cropBox, "CropBox");
        }
        double unit = userUnit == null ? 1.0 : userUnit;
        int requested = rotate == null ? 0 : rotate;
        int normalised = Math.floorMod(requested, 360);
        String problem = null;
        if (!Double.isFinite(unit)) {
            problem = "the page names a UserUnit that is not a number (" + userUnit
                    + ") - the length of a unit has to be a number for any coordinate to be"
                    + " measured in it (4.2.1, S. 201)";
        } else if (unit <= 0) {
            problem = "the page names UserUnit " + PdfMatrix.format(unit) + ", and a unit that is"
                    + " zero or negative measures nothing - 4.2.1, S. 201 takes the entry to set"
                    + " the length of a unit, default 1/72 inch";
        } else if (normalised != 0 && normalised != 90 && normalised != 180 && normalised != 270) {
            problem = "the page names Rotate " + requested + ", which is not a multiple of 90"
                    + " (Table 3.27, S. 146) - the module does not guess a page frame out of an"
                    + " angle the specification calls invalid";
        }
        if (problem == null) {
            PdfRect visible = cropBox != null ? cropBox : mediaBox;
            if (visible.width() == 0 || visible.height() == 0) {
                problem = "the visible box of the page has no extent (" + visible
                        + ") - there is no region to place a page space on (3.8.4, S. 161 keeps"
                        + " such a rectangle as a line, but a page of it clips everything away)";
            }
        }
        return new PdfPageSpace(mediaBox, cropBox, unit, requested, normalised, problem);
    }

    // ===================================================================
    // What the page is
    // ===================================================================

    /** The MediaBox as the page writes it, in default user space. */
    public PdfRect mediaBox() {
        return mediaBox;
    }

    /** The CropBox, or {@code null} when the page names none - then {@link #visibleBox()} is the MediaBox. */
    public PdfRect cropBox() {
        return cropBox;
    }

    /**
     * The box the page is displayed in: CropBox when named, MediaBox otherwise (Table 3.27,
     * S. 145: CropBox's default value "the value of MediaBox"), in default user space and with the
     * numbers as the file wrote them.
     */
    public PdfRect visibleBox() {
        return cropBox != null ? cropBox : mediaBox;
    }

    /** {@code cropBox} or {@code mediaBox} - which of the two {@link #visibleBox()} answered with. */
    public String basis() {
        return cropBox != null ? BASIS_CROP_BOX : BASIS_MEDIA_BOX;
    }

    /** The {@code UserUnit} in effect - 1 when the page names none (4.2.1, S. 201). */
    public double userUnit() {
        return userUnit;
    }

    /** The rotation actually used: the page's {@code Rotate}, normalised into 0, 90, 180 or 270. */
    public int rotate() {
        return rotate;
    }

    /** The rotation as the page wrote it, before normalisation - for a note that quotes the file. */
    public int requestedRotate() {
        return requestedRotate;
    }

    /** Width of the visible box in default user space <em>units</em> (before UserUnit). */
    public double visibleWidthInUserUnits() {
        return visibleBox().width();
    }

    /** Height of the visible box in default user space units. */
    public double visibleHeightInUserUnits() {
        return visibleBox().height();
    }

    /** Width of the displayed page in page units (pt) - the visible height once the page is on its side. */
    public double pageWidth() {
        double w = visibleWidthInUserUnits() * userUnit;
        double h = visibleHeightInUserUnits() * userUnit;
        return rotate == 90 || rotate == 270 ? h : w;
    }

    /** Height of the displayed page in page units (pt). */
    public double pageHeight() {
        double w = visibleWidthInUserUnits() * userUnit;
        double h = visibleHeightInUserUnits() * userUnit;
        return rotate == 90 || rotate == 270 ? w : h;
    }

    /**
     * The visible page as a rectangle <em>in page space</em>: {@code [0 0 width height]} with the
     * height counted downwards. This is also the initial clipping region - "The initial clipping
     * path includes the entire page" (4.4.3, S. 234).
     */
    public PdfRect pageBox() {
        return PdfRect.of(0, 0, pageWidth(), pageHeight(), PdfSpace.PAGE);
    }

    /**
     * Whether the frame can be turned into a matrix at all. False exactly when {@link #defect()}
     * says what is wrong.
     */
    public boolean isUsable() {
        return defect == null;
    }

    /**
     * Why there is no page space, in one sentence - {@code null} when there is one. A page whose
     * {@code Rotate} is not a multiple of 90, whose unit is not positive or whose visible box has
     * no extent is a finding about the file, not a coordinate: an invented frame would put every
     * number of the page somewhere nobody chose (plan: no geometry claim without a source).
     */
    public String defect() {
        return defect;
    }

    // ===================================================================
    // The matrix
    // ===================================================================

    /**
     * Default user space → page space.
     * <p>
     * Built in two documented halves. First the visible box's lower-left corner becomes the origin
     * and the unit becomes a point: {@code Translate(−llx, −lly) × Scale(uu, uu)}. Then the page
     * is turned the way {@code Rotate} says and the y axis is flipped, each case being the corner
     * mapping of a sheet of paper - a clockwise rotation moves the sheet's lower-left corner to the
     * top-left (90°), to the top-right (180°) and to the bottom-right (270°), and a distance from
     * an edge follows the edge it lands on:
     * </p>
     * <table>
     *   <caption>page coordinates of (u, v) - u from the left edge, v from the bottom edge, both
     *   already in pt</caption>
     *   <tr><th>Rotate</th><th>page x</th><th>page y</th><th>built as</th></tr>
     *   <tr><td>0</td><td>u</td><td>h − v</td><td>flip y, then move down by h</td></tr>
     *   <tr><td>90</td><td>v</td><td>u</td><td>rotate −90°, then flip y</td></tr>
     *   <tr><td>180</td><td>w − u</td><td>v</td><td>flip x, then move right by w</td></tr>
     *   <tr><td>270</td><td>h − v</td><td>w − u</td><td>rotate +90°, flip y, move by (h, w)</td></tr>
     * </table>
     * <p>
     * For the check document's page (visible box {@code [0 0 612 792]}, no rotation) this is
     * {@code [1 0 0 -1 0 792]} - the matrix every one of its 875 content streams applies as its
     * first {@code cm}, which is how a reader can tell the composition is the module's and not the
     * file's: the producer folded the same axis here, and every {@code Tm} of that document folds
     * it a second time.
     * </p>
     *
     * @return the matrix, or empty with {@link #defect()} as the reason
     */
    public Optional<PdfMatrix> defaultUserToPage() {
        if (defect != null) {
            return Optional.empty();
        }
        PdfRect box = visibleBox();
        double w = box.width() * userUnit;
        double h = box.height() * userUnit;
        PdfMatrix toCorner = PdfMatrix.translation(-box.minX(), -box.minY())
                .multiply(PdfMatrix.scaling(userUnit));
        PdfMatrix turned = switch (rotate) {
            // v counted from the top instead of the bottom.
            case 0 -> PdfMatrix.scaling(1, -1).multiply(PdfMatrix.translation(0, h));
            // The left edge becomes the top edge (u -> page y) and the bottom edge the left
            // edge (v -> page x); rotation(-90) already yields (v, -u), the flip only undoes
            // the sign the rotation had to introduce.
            case 90 -> PdfMatrix.rotation(-90).multiply(PdfMatrix.scaling(1, -1));
            case 180 -> PdfMatrix.scaling(-1, 1).multiply(PdfMatrix.translation(w, 0));
            // rotation(+90) yields (-v, u): flip y to get (-v, -u), then push both back into
            // the positive quadrant of the displayed page, which is h wide and w high.
            case 270 -> PdfMatrix.rotation(90).multiply(PdfMatrix.scaling(1, -1))
                    .multiply(PdfMatrix.translation(h, w));
            default -> throw new PdfException("unreachable: a rotation that is not a multiple of"
                    + " 90 is refused by of(...) (Table 3.27, S. 146)");
        };
        return Optional.of(toCorner.multiply(turned));
    }

    /**
     * Page space → default user space, the way back. Exists because a caller holding page
     * coordinates (a filter box of {@code pdf.placement()}, say) has to ask whether a point of the
     * page is inside a clip that was measured in another space.
     *
     * @return the inverse, or empty when there is no page space to come back from
     */
    public Optional<PdfMatrix> pageToDefaultUser() {
        return defaultUserToPage().flatMap(PdfMatrix::invert);
    }

    /**
     * A point of default user space in page space.
     *
     * @throws PdfException when the point is in another space, or when this page has no frame -
     *                      the message is {@link #defect()}, because a caller that maps has to
     *                      have read the page first
     */
    public PdfPoint toPage(PdfPoint defaultUserPoint) {
        PdfPoint.sameSpace(PdfSpace.DEFAULT_USER, defaultUserPoint.space(),
                "putting a point into the page space");
        return defaultUserToPage().map(m -> m.map(defaultUserPoint, PdfSpace.PAGE))
                .orElseThrow(() -> new PdfException("This page has no page space: " + defect));
    }

    /**
     * A rectangle of default user space (a box, a BBox, a clip) in page space - as four points,
     * because a rotated page turns every axis-aligned box into a walking box.
     */
    public PdfQuad toPage(PdfRect defaultUserRect) {
        PdfPoint.sameSpace(PdfSpace.DEFAULT_USER, defaultUserRect.space(),
                "putting a rectangle into the page space");
        return defaultUserToPage().map(m -> m.map(defaultUserRect, PdfSpace.PAGE))
                .orElseThrow(() -> new PdfException("This page has no page space: " + defect));
    }

    /**
     * Pixels per page unit for a caller-supplied resolution - the {@code deviceScale} of a result.
     * <p>
     * A page unit is one point (1/72 inch, S. 201) and {@code UserUnit} is already inside
     * {@link #defaultUserToPage()}, so the answer is {@code dpi / 72} and nothing else. It is
     * printed because it is <em>the caller's</em> number: the file never names a resolution, which
     * is why device space is the one space of {@link PdfSpace} that is not from the file
     * ({@link PdfSpace#isViewerDependent(String)}).
     * </p>
     *
     * @param dpi dots per inch as the caller chose them
     * @throws PdfException for a resolution that is not a positive number
     */
    public double deviceScale(double dpi) {
        if (!Double.isFinite(dpi) || dpi <= 0) {
            throw new PdfException("A device scale needs a resolution in dots per inch, this call"
                    + " passed " + dpi + " - the file itself never names one (4.2.1, S. 199/200)");
        }
        return dpi / POINTS_PER_INCH;
    }

    /**
     * One line naming the frame - for the {@code frame} block of a placement answer and for notes:
     * what box it stands on, how big the page is, how it is turned.
     */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(basis()).append(" ").append(visibleBox());
        sb.append(", page ").append(PdfMatrix.format(pageWidth())).append(" ×")
                .append(PdfMatrix.format(pageHeight())).append(" pt");
        if (userUnit != 1) {
            sb.append(", UserUnit ").append(PdfMatrix.format(userUnit));
        }
        if (rotate != 0) {
            sb.append(", rotated ").append(rotate).append("° clockwise");
        }
        if (defect != null) {
            sb.append(" - no page space: ").append(defect);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return describe();
    }

    private static void requireDefaultUser(PdfRect box, String what) {
        if (!PdfSpace.DEFAULT_USER.equals(box.space())) {
            throw new PdfException("A page dictionary's " + what + " is expressed in default user"
                    + " space units (Table 3.27, S. 145), this one is labelled '" + box.space()
                    + "' - a box in another space needs a matrix before it can frame a page");
        }
    }
}
