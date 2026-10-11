package org.rogmann.mcp2sdk.pdf.geo;

import org.rogmann.mcp2sdk.pdf.PdfException;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * A PDF transformation matrix - six numbers, immutable, and one rule about the order.
 * (PDF 1.7 4.2.2/4.2.3, S. 204-209; ISO 32000-2 8.3.23, which is what the plan's "§8.3.2-8.3.23"
 * refers to; {@code cm} in 4.3.3, Table 4.7, S. 219.)
 *
 * <h2>What the six numbers are</h2>
 *
 * "A transformation matrix in PDF is specified by six numbers, usually in the form of an array
 * containing six elements. In its most general form, this array is denoted [{@code a b c d e f}]"
 * (S. 205). Written as the 3 × 3 matrix of S. 208 and applied to a point as a row vector
 * {@code [x y 1]}, it maps coordinates as
 *
 * <pre>x' = a·x + c·y + e      y' = b·x + d·y + f</pre>
 *
 * which is printed exactly like that on S. 208. So {@link #a() a} and {@link #d() d} stretch along
 * x and y, {@link #b() b} and {@link #c() c} turn and shear, {@link #e() e} and {@link #f() f}
 * shift, and the pairs that belong together are (a, b) - the image of the x axis - and (c, d) -
 * the image of the y axis. Getting those two pairs apart is the classic mistake in this corner of
 * PDF; the check document's own first probe run computed a CTM of
 * {@code [0 0 0 -1 54 576]} for a page that is {@code [1 0 0 -1 54 744]}, out by a factor of 1e10
 * within a dozen operators. Both index orders are therefore pinned by name in
 * {@link PdfMatrixTest}, not by a single example.
 *
 * <h2>What a matrix is <em>for</em> - the two points of S. 207</h2>
 *
 * "Transformations alter coordinate systems, not graphics objects", and "transformation matrices
 * specify the transformation from the new (transformed) coordinate system to the original
 * (untransformed) coordinate system" (S. 207). A matrix is thus a way <em>out</em> of a space: it
 * takes coordinates as written in the space that is currently in effect and states where they lie
 * in the space that was in effect before.
 *
 * <h2>Why the composition method is called {@code premultiply}</h2>
 *
 * Because the specification says so, in so many words (S. 209):
 *
 * <pre>X_D = X_U · M_C = (X_S · M_S) · M_C</pre>
 *
 * "This shows that when a new transformation is concatenated with an existing one, the matrix
 * representing it must be multiplied <em>before</em> ({@em premultiplied} with) the existing
 * transformation matrix", i.e. {@code M' = M_T × M}. Hence:
 *
 * <pre>{@code
 * ctm = ctm.premultiply(cmOperand);      // cm: the operand is applied first - M' = M_T x M
 * }</pre>
 *
 * {@link #multiply(PdfMatrix)} exists as well, because walking <em>along</em> the chain of spaces
 * (glyph → text → user → default user → page) reads left to right and needs the other direction -
 * the text line of 5.3.3, S. 410 is one such chain:
 *
 * <pre>{@code
 * PdfMatrix textToDevice = textMatrix.premultiply(fontScale).multiply(ctm);   // T_fs x T_m x CTM
 * }</pre>
 *
 * The two are the same product seen from opposite ends - {@code x.premultiply(y)} equals
 * {@code y.multiply(x)} - and neither is "the wrong one". What is wrong is composing them without
 * deciding which space the operand lives in, which is why every mapping method of this class names
 * the space it lands in.
 *
 * <h2>What this class refuses</h2>
 *
 * A singular matrix ({@code a·d − b·c = 0}) has no inverse. The specification describes the case
 * by example rather than by formula - "if a matrix contains a, b, c, and d elements that are all
 * zero, all user coordinates map to the same device coordinates and there is no unique inverse
 * transformation ... Use of a noninvertible matrix when painting graphics objects can result in
 * unpredictable behavior" (Note, S. 209) - and the criterion follows from the two equations of
 * S. 208: solving {@code x' = ax + cy + e}, {@code y' = bx + dy + f} for x and y divides by
 * {@code a·d − b·c}. So {@link #invert()} returns empty, {@link #singularNote()} is the sentence
 * that explains it in a result, and nothing is ever inverted "approximately": a coordinate from a
 * fake inverse would be a number nobody can vouch for (plan decision 9).
 * <p>
 * Nothing here renders, rasterises or flattens. A rectangle mapped through a matrix stays four
 * points ({@link #map(PdfRect, String)} answers {@link PdfQuad}), a curve's extent is never computed
 * from its control points, and a matrix never says what a viewer would paint.
 * </p>
 */
public final class PdfMatrix {

    /** The identity matrix {@code [1 0 0 1 0 0]} - "no transformation" (4.2.1: the state before). */
    public static final PdfMatrix IDENTITY = new PdfMatrix(1, 0, 0, 1, 0, 0);

    /**
     * Tolerance of the "is this the same" questions ({@link #isIdentity()},
     * {@link #isOrthogonal()}, {@link Decomposition}). Chosen for a space whose unit is a point on
     * a page of a few hundred units: far below what any producer writes deliberately, far above
     * what double rounding of a handful of products accumulates.
     */
    public static final double EPS = 1e-9;

    private final double a;
    private final double b;
    private final double c;
    private final double d;
    private final double e;
    private final double f;
    /** a·d − b·c, computed once: it decides invertibility, orientation and the decomposition. */
    private final double determinant;

    private PdfMatrix(double a, double b, double c, double d, double e, double f) {
        this.a = a;
        this.b = b;
        this.c = c;
        this.d = d;
        this.e = e;
        this.f = f;
        this.determinant = a * d - b * c;
    }

    // ===================================================================
    // Building
    // ===================================================================

    /** The identity matrix. */
    public static PdfMatrix identity() {
        return IDENTITY;
    }

    /**
     * The six numbers as a content stream or a dictionary writes them: {@code [a b c d e f]}.
     *
     * @throws PdfException when a number is not finite (a matrix of NaN maps nothing anywhere)
     */
    public static PdfMatrix of(double a, double b, double c, double d, double e, double f) {
        for (double v : new double[]{a, b, c, d, e, f}) {
            if (!Double.isFinite(v)) {
                throw new PdfException("A transformation matrix carries a number that is not"
                        + " finite: " + Arrays.toString(new double[]{a, b, c, d, e, f})
                        + " - 4.2.2, S. 205 specifies six numbers");
            }
        }
        // A -0 is the same number as 0 and prints as 0, but it would make two matrices that mean
        // the same thing unequal - and an inverse of a flip produces exactly that (-0/-1).
        return new PdfMatrix(clean(a), clean(b), clean(c), clean(d), clean(e), clean(f));
    }

    /**
     * The six numbers of an array, in PDF order.
     *
     * @throws PdfException for an array of other than six numbers - the message names what was
     *                      found, because the caller's note has to say the same thing
     */
    public static PdfMatrix of(double[] values) {
        if (values == null || values.length != 6) {
            throw new PdfException("A transformation matrix needs 6 numbers, found "
                    + (values == null ? "none" : values.length) + " (4.2.2, S. 205)");
        }
        return of(values[0], values[1], values[2], values[3], values[4], values[5]);
    }

    /**
     * A matrix from the operands of an operator - the numbers as the parser produced them
     * ({@link Long} for an integer operand, {@link Double} for a real one). An integer operand
     * stays exactly representable, so {@code 1 0 0 -1 0 792 cm} reads back as {@code 792}.
     *
     * @throws PdfException for the wrong count or an operand that is not a number
     */
    public static PdfMatrix fromOperands(List<?> operands) {
        if (operands == null || operands.size() != 6) {
            throw new PdfException("A transformation matrix needs 6 numbers, found "
                    + (operands == null ? "none" : operands.size())
                    + " - cm writes them as six separate numbers, not as an array (Table 4.7,"
                    + " S. 219)");
        }
        double[] six = new double[6];
        for (int i = 0; i < 6; i++) {
            Object value = operands.get(i);
            if (value instanceof Number number) {
                six[i] = number.doubleValue();
            } else {
                throw new PdfException("A transformation matrix needs 6 numbers; operand "
                        + (i + 1) + " is a " + (value == null ? "null"
                        : value.getClass().getSimpleName()) + " (4.2.2, S. 205)");
            }
        }
        return of(six);
    }

    /**
     * Translation by {@code tx}, {@code ty} - {@code [1 0 0 1 tx ty]}, "the distances to translate
     * the origin of the coordinate system in the horizontal and vertical dimensions" (S. 205).
     */
    public static PdfMatrix translation(double tx, double ty) {
        return of(1, 0, 0, 1, tx, ty);
    }

    /**
     * Scaling - {@code [sx 0 0 sy 0 0]}: "scales the coordinates so that 1 unit in the horizontal
     * and vertical dimensions of the new coordinate system is the same size as sx and sy units,
     * respectively, in the previous coordinate system" (S. 205). A negative component is a
     * reflection, not an error: that is how a page turns its y axis around.
     */
    public static PdfMatrix scaling(double sx, double sy) {
        return of(sx, 0, 0, sy, 0, 0);
    }

    /** Scaling by the same factor on both axes. */
    public static PdfMatrix scaling(double s) {
        return scaling(s, s);
    }

    /**
     * Rotation of the coordinate system by {@code degrees} counterclockwise -
     * {@code [cos sin -sin cos 0 0]} (S. 205). Applied to coordinates, the image of (1, 0) is
     * (cos, sin) and the image of (0, 1) is (−sin, cos); the page's own {@code /Rotate} is
     * documented the other way round, as a rotation <em>of the page</em> clockwise (Table 3.27,
     * S. 146), so a caller building the page space converts the sign deliberately rather than by
     * accident.
     */
    public static PdfMatrix rotation(double degrees) {
        int quarter = quarterTurnOf(degrees);
        if (quarter >= 0) {
            // cos and sin of an exact multiple of 90° are 0 and ±1; Math.cos(Math.toRadians(90))
            // answers 6.12e-17 instead, and that residue would ride along into every coordinate the
            // matrix maps - including a page frame, whose Rotate the specification *requires* to be
            // a multiple of 90 (Table 3.27, S. 146). D2's PdfPageSpace is why this is exact here.
            double[] pair = QUARTER_TURNS[quarter];
            return of(pair[0], pair[1], -pair[1], pair[0], 0, 0);
        }
        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return of(clean(cos), clean(sin), clean(-sin), clean(cos), 0, 0);
    }

    /** The cos/sin pairs of 0°, 90°, 180° and 270° - the four angles with exact values. */
    private static final double[][] QUARTER_TURNS = {
        {1, 0}, {0, 1}, {-1, 0}, {0, -1},
    };

    /**
     * The index into {@link #QUARTER_TURNS} when the angle is an exact multiple of 90 degrees, and
     * -1 otherwise. Checked on the degree measure, not on the computed cosine: only the exact
     * multiples have exactly-known values, and an angle like 90.000001° must keep its own numbers.
     */
    private static int quarterTurnOf(double degrees) {
        if (!Double.isFinite(degrees) || degrees != Math.rint(degrees)) {
            return -1;
        }
        long whole = (long) degrees;
        if (whole % 90 != 0) {
            return -1;
        }
        return (int) Math.floorMod(whole, 360) / 90;
    }

    /**
     * Skew - {@code [1 tan(alpha) tan(beta) 1 0 0]}, "skews the x axis by an angle alpha and the y
     * axis by an angle beta" (S. 205).
     *
     * @throws PdfException at 90° + k·180°, where the tangent has no value - {@code Math.tan}
     *                      would answer with a huge number instead of "undefined", and a matrix
     *                      built on that would be a claim, not a measurement
     */
    public static PdfMatrix skew(double alphaDegrees, double betaDegrees) {
        double tanAlpha = tangentOf(alphaDegrees);
        double tanBeta = tangentOf(betaDegrees);
        return of(1, clean(tanAlpha), clean(tanBeta), 1, 0, 0);
    }

    /** The tangent of a degree measure, refusing the angles that have none. */
    private static double tangentOf(double degrees) {
        double radians = Math.toRadians(degrees);
        // Math.cos(Math.toRadians(90)) is 6.1e-17 rather than 0, so a test for a zero cosine needs
        // the same tolerance the rest of the class works with - the angle is what the file wrote,
        // and 90 degrees simply has no tangent.
        if (Math.abs(Math.cos(radians)) < EPS) {
            throw new PdfException("Skew of " + format(degrees) + " degrees asks for a tangent that"
                    + " has no value at 90 degrees (mod 180) - no matrix can be built from it"
                    + " (4.2.2, S. 205)");
        }
        double tan = Math.tan(radians);
        if (!Double.isFinite(tan)) {
            throw new PdfException("Skew of " + format(degrees) + " degrees has no finite tangent"
                    + " (4.2.2, S. 205)");
        }
        return tan;
    }

    // ===================================================================
    // Composition - the direction matters
    // ===================================================================

    /**
     * The product {@code m × this}: <em>this</em>, with {@code m} applied <b>first</b>.
     * <p>
     * This is the state transition of a content stream: {@code cm} "modifies the current
     * transformation matrix (CTM) by concatenating the specified matrix" (Table 4.7, S. 219), and
     * concatenation means premultiplication (S. 209: {@code M' = M_T × M}) - coordinates written
     * after the operator are expressed in the space {@code m} describes, so {@code m} has to be
     * applied before the matrix that was already in effect.
     * </p>
     * <pre>{@code
     * PdfMatrix ctm = PdfMatrix.identity();
     * ctm = ctm.premultiply(PdfMatrix.of(1, 0, 0, -1, 0, 792));   // page: y axis down
     * ctm = ctm.premultiply(PdfMatrix.translation(54, 48));       // a block inside that page
     * }</pre>
     *
     * @param m the transformation applied first (the operand of {@code cm}, a form's
     *          {@code /Matrix}, a pattern matrix)
     * @return the combined matrix, mapping the new space into the space {@code this} mapped into
     */
    public PdfMatrix premultiply(PdfMatrix m) {
        return m.multiply(this);
    }

    /**
     * The product {@code this × m}: <em>this</em>, with {@code m} applied <b>afterwards</b>.
     * <p>
     * Needed when a chain of spaces is read left to right - the text line of 5.3.3, S. 410,
     * {@code T_rm = [T_fs·T_h 0 0 T_fs 0 0] × T_m × CTM}, or the composition of S. 209,
     * {@code X_D = X_S · (M_S · M_C)}: a point travels from the innermost space outwards and each
     * step is post-multiplied by the matrix of the space behind it.
     * </p>
     * <p>
     * A graphics-state change is <em>not</em> expressed this way; use {@link #premultiply(PdfMatrix)}
     * for that. Both products exist because one without the other forces a caller to write the
     * chain backwards, and backwards is where the mistakes are.
     * </p>
     */
    public PdfMatrix multiply(PdfMatrix m) {
        return of(a * m.a + b * m.c,
                a * m.b + b * m.d,
                c * m.a + d * m.c,
                c * m.b + d * m.d,
                e * m.a + f * m.c + m.e,
                e * m.b + f * m.d + m.f);
    }

    // ===================================================================
    // Inverse
    // ===================================================================

    /**
     * The inverse mapping - the way back from the target space to the space the operands were
     * written in. The specification's note on why this is not always possible (S. 209): all user
     * coordinates may land on the same device coordinates, and then "there is no unique inverse
     * transformation".
     *
     * @return the inverse, or empty when {@link #isSingular()} (or when the inverse would not be
     *         expressible in finite numbers) - never a guessed approximation
     */
    public Optional<PdfMatrix> invert() {
        if (isSingular()) {
            return Optional.empty();
        }
        PdfMatrix inverse = of(d / determinant, -b / determinant,
                -c / determinant, a / determinant,
                (c * f - e * d) / determinant,
                (b * e - a * f) / determinant);
        // A determinant far from zero can still drive the quotient past the largest double; that
        // is the same "no number to report" case as the exact zero, and gets the same answer.
        return inverse.isSingular() || !inverse.isFinite() ? Optional.empty() : Optional.of(inverse);
    }

    /**
     * One sentence for the caller's notes when {@link #invert()} came back empty - the statement
     * that replaces the number, so a result says why it has none. Returns {@code null} for a matrix
     * that does have an inverse.
     */
    public String singularNote() {
        if (!isSingular()) {
            return null;
        }
        return "the matrix " + this + " has a*d - b*c = " + format(determinant)
                + ": no inverse exists, so no coordinate can be traced back through it"
                + " (4.2.3, S. 209 calls a non-invertible matrix unpredictable when painting)";
    }

    // ===================================================================
    // Mapping
    // ===================================================================

    /** x of the image of (x, y): {@code ax + cy + e} (4.2.3, S. 208). */
    public double mapX(double x, double y) {
        return a * x + c * y + e;
    }

    /** y of the image of (x, y): {@code bx + dy + f} (4.2.3, S. 208). */
    public double mapY(double x, double y) {
        return b * x + d * y + f;
    }

    /** The image of a point as a plain pair, for arithmetic that does not need the label. */
    public double[] mapPoint(double x, double y) {
        return new double[]{mapX(x, y), mapY(x, y)};
    }

    /**
     * The image of a <em>displacement</em>: {@code ax + cy}, {@code bx + dy} - the point formula
     * without {@code e} and {@code f}.
     * <p>
     * A shift does not depend on where the origin sits, so a line width, a leading or a kern value
     * travels through the linear part only. Running a vector through {@link #mapPoint(double, double)}
     * instead would add the translation to a difference and turn "10 units apart" into "10 units
     * apart plus wherever the page starts".
     * </p>
     */
    public double[] mapVector(double x, double y) {
        return new double[]{a * x + c * y, b * x + d * y};
    }

    /**
     * A point through this matrix, landing in {@code targetSpace}.
     *
     * @param targetSpace the space the caller says this matrix maps <em>into</em> - the matrix does
     *                    not know that itself, and the answer has to name it (decision 7)
     */
    public PdfPoint map(PdfPoint point, String targetSpace) {
        PdfSpace.require(targetSpace);
        return new PdfPoint(mapX(point.x(), point.y()), mapY(point.x(), point.y()), targetSpace);
    }

    /** A displacement through this matrix, without the translation part. */
    public PdfVector map(PdfVector vector, String targetSpace) {
        PdfSpace.require(targetSpace);
        double[] image = mapVector(vector.x(), vector.y());
        return new PdfVector(image[0], image[1], targetSpace);
    }

    /**
     * A rectangle through this matrix - as <b>four points</b>, deliberately not as a rectangle.
     * <p>
     * Only a diagonal (or axis-preserving) matrix keeps an axis-aligned box axis-aligned. Returning
     * an envelope here would quietly report a rotated object as the larger box drawn around it, so
     * the vertices are the answer; {@link PdfQuad#bbox()} is the envelope for whoever needs it,
     * named as such.
     * </p>
     */
    public PdfQuad map(PdfRect rect, String targetSpace) {
        return rect.corners().map(this, targetSpace);
    }

    /** Four points through this matrix. */
    public PdfQuad map(PdfQuad quad, String targetSpace) {
        return quad.map(this, targetSpace);
    }

    // ===================================================================
    // What kind of matrix this is
    // ===================================================================

    /** The {@code a} element (first number). */
    public double a() {
        return a;
    }

    /** The {@code b} element (second number). */
    public double b() {
        return b;
    }

    /** The {@code c} element (third number). */
    public double c() {
        return c;
    }

    /** The {@code d} element (fourth number). */
    public double d() {
        return d;
    }

    /** The {@code e} element (fifth number) - horizontal shift of the origin. */
    public double e() {
        return e;
    }

    /** The {@code f} element (sixth number) - vertical shift of the origin. */
    public double f() {
        return f;
    }

    /** The six numbers in PDF order, {@code [a b c d e f]}. */
    public double[] toArray() {
        return new double[]{a, b, c, d, e, f};
    }

    /**
     * {@code a·d − b·c}: the area factor of the transformation and the number that decides whether
     * an inverse exists. Zero means the whole space collapses onto a line or a point.
     */
    public double determinant() {
        return determinant;
    }

    /** Whether the matrix has no inverse ({@code a·d − b·c = 0}). */
    public boolean isSingular() {
        return determinant == 0;
    }

    /** Whether all six numbers are finite (a matrix built through {@link #of} always is). */
    public boolean isFinite() {
        return Double.isFinite(a) && Double.isFinite(b) && Double.isFinite(c)
                && Double.isFinite(d) && Double.isFinite(e) && Double.isFinite(f);
    }

    /** Whether this is {@code [1 0 0 1 0 0]} - "nothing has happened to this space yet". */
    public boolean isIdentity() {
        return a == 1 && b == 0 && c == 0 && d == 1 && e == 0 && f == 0;
    }

    /** Whether {@code b} and {@code c} are zero: no turning, no shearing, axes stay on axes. */
    public boolean isDiagonal() {
        return b == 0 && c == 0;
    }

    /** Whether this is a pure shift: {@code [1 0 0 1 e f]}, nothing else changed. */
    public boolean isTranslation() {
        return a == 1 && b == 0 && c == 0 && d == 1;
    }

    /** Whether the two axes are still at right angles (possibly with different lengths). */
    public boolean isOrthogonal() {
        return isOrthogonal(EPS);
    }

    /** Whether the axes are at right angles within {@code epsilon} (relative to their lengths). */
    public boolean isOrthogonal(double epsilon) {
        double dot = a * c + b * d;
        double lengths = Math.hypot(a, b) * Math.hypot(c, d);
        if (lengths == 0) {
            return false;
        }
        return Math.abs(dot) <= epsilon * lengths;
    }

    /**
     * Whether the transformation keeps the handedness of the space (determinant &gt; 0). False for a
     * mirrored space - which is what a page with {@code [1 0 0 -1 0 height]} is: the y axis points
     * down, and a caller who assumes the printed "y up" convention without asking reads the page
     * upside down.
     */
    public boolean isOrientationPreserving() {
        return determinant > 0;
    }

    /** Length of the image of the x axis, in the units of the target space. */
    public double xAxisLength() {
        return Math.hypot(a, b);
    }

    /** Length of the image of the y axis, in the units of the target space. */
    public double yAxisLength() {
        return Math.hypot(c, d);
    }

    /** The image of the x axis as a displacement of the target space. */
    public PdfVector xAxis(String targetSpace) {
        return new PdfVector(a, b, targetSpace);
    }

    /** The image of the y axis as a displacement of the target space. */
    public PdfVector yAxis(String targetSpace) {
        return new PdfVector(c, d, targetSpace);
    }

    /** Where the origin of this space lands: {@code (e, f)}. */
    public PdfPoint origin(String targetSpace) {
        return new PdfPoint(e, f, targetSpace);
    }

    /**
     * The matrix split into the four effects the specification lists (4.2.2, S. 205) - scale,
     * rotation, shear, translation - for a note or a listing that has to say <em>what</em> a matrix
     * does instead of printing six numbers.
     * <p>
     * The two scales are the lengths of the two image axes, the rotation is the direction of the
     * x axis, and the shear is how far the two axes have moved apart from a right angle. For a
     * matrix whose axes are perpendicular that is exact; for a sheared one a decomposition into
     * "rotate, then scale, then shear" is a matter of convention, and the {@code orthogonal} flag
     * of the result says that a caller should read the six numbers rather than the four names.
     * </p>
     */
    public Decomposition decompose(String space) {
        double scaleX = xAxisLength();
        double scaleY = yAxisLength();
        double rotation = Math.toDegrees(Math.atan2(b, a));
        // Signed angle from the x axis to the y axis: +90 for a right-handed pair, -90 for a
        // mirrored one (a page whose y axis points down).
        double axisAngle = Math.toDegrees(Math.atan2(determinant, a * c + b * d));
        double shear = axisAngle - 90;
        if (shear > 180) {
            shear -= 360;
        }
        if (shear < -180) {
            shear += 360;
        }
        boolean orthogonal = isOrthogonal();
        boolean uniform = scaleX > 0 && Math.abs(scaleX - scaleY) <= EPS * Math.max(1, scaleX);
        // In the order the record declares them - a scrambled argument list here would read as a
        // rotation of 3 degrees instead of a scale of 3, and no test would notice but this one.
        return new Decomposition(clean(scaleX), clean(scaleY), clean(rotation), clean(axisAngle),
                clean(shear), clean(e), clean(f), determinant < 0, orthogonal, uniform, space);
    }

    /**
     * A matrix described by effects instead of elements - see {@link #decompose(String)}.
     *
     * @param scaleX              length of the image of the x axis
     * @param scaleY              length of the image of the y axis
     * @param rotationDegrees     direction of the image of the x axis, degrees
     * @param axisAngleDegrees    signed angle from x axis to y axis (+90 is a right angle)
     * @param shearDegrees        how far that angle is from a right angle (0 is perpendicular)
     * @param translationX        where the origin lands, x
     * @param translationY        where the origin lands, y
     * @param reflection          whether handedness is reversed (determinant &lt; 0)
     * @param orthogonal          whether the axes are still perpendicular
     * @param uniform             whether both axes carry the same factor
     * @param space               the space the translation is measured in
     */
    public record Decomposition(double scaleX, double scaleY, double rotationDegrees,
                                double axisAngleDegrees, double shearDegrees, double translationX,
                                double translationY, boolean reflection, boolean orthogonal,
                                boolean uniform, String space) {

        /** Where the origin of the transformed space lands. */
        public PdfPoint translation() {
            return new PdfPoint(translationX, translationY, space);
        }

        /** One line of readable text, e.g. for a note: what this matrix does to a space. */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append("scale ").append(PdfMatrix.format(scaleX)).append(" x ")
                    .append(PdfMatrix.format(scaleY));
            if (Math.abs(rotationDegrees) > EPS) {
                sb.append(", x axis at ").append(PdfMatrix.format(rotationDegrees)).append("°");
            }
            if (!orthogonal) {
                sb.append(", axes ").append(PdfMatrix.format(axisAngleDegrees))
                        .append("° apart (sheared)");
            }
            if (reflection) {
                sb.append(", mirrored (y axis reversed: the space runs the other way)");
            }
            if (translationX != 0 || translationY != 0) {
                sb.append(", origin at (").append(PdfMatrix.format(translationX)).append(", ")
                        .append(PdfMatrix.format(translationY)).append(" in ").append(space)
                        .append(')');
            }
            if (!uniform) {
                sb.append(", unequal axis factors");
            }
            return sb.toString();
        }
    }

    // ===================================================================
    // Comparing and printing
    // ===================================================================

    /**
     * Whether every element differs by at most {@code epsilon} - the comparison a caller wants when
     * two chains of products should describe the same space but were computed by different routes
     * (the check document's probe compares matrix products against mapped points this way).
     */
    public boolean isCloseTo(PdfMatrix other, double epsilon) {
        return Math.abs(a - other.a) <= epsilon
                && Math.abs(b - other.b) <= epsilon
                && Math.abs(c - other.c) <= epsilon
                && Math.abs(d - other.d) <= epsilon
                && Math.abs(e - other.e) <= epsilon
                && Math.abs(f - other.f) <= epsilon;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof PdfMatrix other)) {
            return false;
        }
        return Double.compare(a, other.a) == 0 && Double.compare(b, other.b) == 0
                && Double.compare(c, other.c) == 0 && Double.compare(d, other.d) == 0
                && Double.compare(e, other.e) == 0 && Double.compare(f, other.f) == 0;
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(new double[]{a, b, c, d, e, f});
    }

    /**
     * The canonical text form of a matrix, as a note or a listing shows it:
     * {@code [1 0 0 -1 0 792]}. Integers keep no decimal point, so the text of a matrix that a
     * stream wrote as six integers reads back as six integers.
     */
    @Override
    public String toString() {
        return "[" + format(a) + " " + format(b) + " " + format(c) + " " + format(d) + " "
                + format(e) + " " + format(f) + "]";
    }

    /**
     * The shortest decimal that reads back as the same {@code double}, plain notation where that
     * stays short ({@code 792}, {@code 32.64}, {@code -0.21552}) and exponential only for values
     * whose plain form would run past a line.
     */
    public static String format(double value) {
        if (value == 0) {
            return "0";
        }
        if (!Double.isFinite(value)) {
            return String.valueOf(value);
        }
        double magnitude = Math.abs(value);
        if (magnitude >= 1e21 || magnitude < 1e-6) {
            return String.valueOf(value);
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /** Turns a −0 that a cosine or a product can produce into the 0 a listing should print. */
    private static double clean(double value) {
        return value == 0 ? 0 : value;
    }
}
