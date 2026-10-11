package org.rogmann.mcp2sdk.pdf.geo;

import org.rogmann.mcp2sdk.pdf.PdfException;

/**
 * A point in one named coordinate space (PDF 1.7 4.2.1, S. 199: "a coordinate pair is a pair of
 * real numbers {@code x} and {@code y} that locate a point horizontally and vertically within a
 * two-dimensional coordinate space").
 * <p>
 * The space is not decoration. The same pair of numbers is a different place in every space, and a
 * content stream's numbers live in a space that "may slide around and shrink or expand" (S. 202),
 * so a point without its space cannot be compared with another point, drawn, or converted. This
 * record refuses to be built without one, and refuses a second one silently: {@link #plus},
 * {@link #minus} and {@link #distance} demand the same space on both sides and say so when they do
 * not get it.
 * </p>
 * <p>
 * Values are kept exactly as read - nothing is rounded, normalised or clamped here. A producer may
 * write {@code 44.50394} and the module answers {@code 44.50394}; a caller who wants a rounded
 * number rounds it and says so.
 * </p>
 *
 * @param x     horizontal coordinate, as the file or the calculation produced it
 * @param y     vertical coordinate, same
 * @param space one of {@link PdfSpace#ALL}; the point means nothing without it
 */
public record PdfPoint(double x, double y, String space) {

    /**
     * A point with its space checked and its coordinates verified to be numbers.
     *
     * @throws PdfException for an unknown space label or a coordinate that is not finite
     */
    public PdfPoint {
        PdfSpace.require(space);
        requireFinite(x, "x", space);
        requireFinite(y, "y", space);
    }

    /** The origin of the given space. */
    public static PdfPoint origin(String space) {
        return new PdfPoint(0, 0, space);
    }

    /**
     * A point that reaches further by a vector - stays in the same space, because a displacement
     * does not change spaces.
     *
     * @throws PdfException when point and vector were measured in different spaces
     */
    public PdfPoint plus(PdfVector vector) {
        sameSpace(space, vector.space(), "adding a vector to a point");
        return new PdfPoint(x + vector.x(), y + vector.y(), space);
    }

    /**
     * The displacement from another point to this one - a vector, because "how far apart" is not a
     * location.
     *
     * @throws PdfException when the two points are in different spaces
     */
    public PdfVector minus(PdfPoint other) {
        sameSpace(space, other.space(), "subtracting one point from another");
        return new PdfVector(x - other.x(), y - other.y(), space);
    }

    /**
     * The distance to another point in the units of the shared space.
     *
     * @throws PdfException when the two points are in different spaces
     */
    public double distance(PdfPoint other) {
        return minus(other).length();
    }

    /** Whether both coordinates differ by at most {@code epsilon} (tests and tolerance checks). */
    public boolean isCloseTo(PdfPoint other, double epsilon) {
        return space.equals(other.space())
                && Math.abs(x - other.x()) <= epsilon
                && Math.abs(y - other.y()) <= epsilon;
    }

    /**
     * Relabels the space of this point. For the caller that <em>established</em> the space itself -
     * a box read from a page dictionary, a page whose MediaBox origin was just subtracted.
     * <p>
     * It is not a conversion: moving a point from one space into another is a matrix and belongs to
     * {@link PdfMatrix#map(PdfPoint, String)}. A relabel that should have been a conversion is
     * exactly the {@code Hoehen - y} mistake this module exists to avoid, so use this sparingly and
     * say in the surrounding code why the numbers are already in the new space.
     * </p>
     */
    public PdfPoint in(String newSpace) {
        return new PdfPoint(x, y, newSpace);
    }

    /**
     * This point through a matrix, landing in {@code targetSpace} - the same step as
     * {@link PdfMatrix#map(PdfPoint, String)}, readable from the point's side.
     */
    public PdfPoint map(PdfMatrix matrix, String targetSpace) {
        return matrix.map(this, targetSpace);
    }

    /** {@code (54, 48 in page)} - the pair plus the space it belongs to. */
    @Override
    public String toString() {
        return "(" + PdfMatrix.format(x) + ", " + PdfMatrix.format(y) + " in " + space + ")";
    }

    static void sameSpace(String a, String b, String what) {
        if (!a.equals(b)) {
            throw new PdfException("Cannot do " + what + " across coordinate spaces: " + a
                    + " and " + b + " - one of them needs a matrix, not an assumption (4.2.1,"
                    + " S. 199)");
        }
    }

    private static void requireFinite(double value, String what, String space) {
        if (!Double.isFinite(value)) {
            throw new PdfException("A " + what + " coordinate of space '" + space + "' is not a"
                    + " number (" + value + ") - a coordinate that is not a number is not a"
                    + " coordinate");
        }
    }
}
