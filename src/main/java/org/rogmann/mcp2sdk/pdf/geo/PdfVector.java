package org.rogmann.mcp2sdk.pdf.geo;

import org.rogmann.mcp2sdk.pdf.PdfException;

/**
 * A displacement in one named coordinate space: two components and no location.
 * <p>
 * The distinction is not pedantic, it is the difference between two answers. A matrix maps a point
 * through its translation part ({@code x' = ax + cy + e}, PDF 1.7 4.2.3, S. 208) and maps a vector
 * <em>without</em> it ({@code x' = ax + cy}): a shift of 48 units stays a shift of 48 units no
 * matter where the origin of the space sits. Feeding a vector through the point formula adds
 * {@code e}/{@code f} to a difference and quietly changes a line width, a leading or a kern value
 * into something else, so {@link PdfMatrix} offers both mappings as separate, named methods.
 * </p>
 * <p>
 * Which space a vector lives in decides the length it means: 10 in {@code user} space is 10 units
 * of whatever unit that space currently uses - after a {@code cm} of {@code [3 0 0 3 0 0]} it is 30
 * of the units of the space behind it (4.2.2, S. 205: scaling "scales the coordinates so that 1
 * unit ... is the same size as s units ... in the previous coordinate system").
 * </p>
 *
 * @param x     horizontal component
 * @param y     vertical component
 * @param space the space whose units these are, one of {@link PdfSpace#ALL}
 */
public record PdfVector(double x, double y, String space) {

    /** A vector with its space checked and its components verified to be numbers. */
    public PdfVector {
        PdfSpace.require(space);
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            throw new PdfException("A vector component of space '" + space + "' is not a number ("
                    + x + ", " + y + ")");
        }
    }

    /** The zero vector of a space - "no displacement", the one length that no matrix can change. */
    public static PdfVector zero(String space) {
        return new PdfVector(0, 0, space);
    }

    /** Length of the displacement in the units of its space. */
    public double length() {
        return Math.hypot(x, y);
    }

    /** Whether both components are zero. */
    public boolean isZero() {
        return x == 0 && y == 0;
    }

    /**
     * Scalar product with another vector of the same space - the test for "at right angles" and the
     * reason the space has to match: in another space the same numbers measure another length.
     *
     * @throws PdfException when the two vectors were measured in different spaces
     */
    public double dot(PdfVector other) {
        PdfPoint.sameSpace(space, other.space(), "taking the scalar product of two vectors");
        return x * other.x() + y * other.y();
    }

    /** Whether the two vectors of the same space meet at right angles within {@code epsilon}. */
    public boolean isPerpendicularTo(PdfVector other, double epsilon) {
        return Math.abs(dot(other)) <= epsilon * Math.max(1.0, length() * other.length());
    }

    /** Direction of the displacement in degrees, {@code atan2(y, x)} - 0 is the x axis. */
    public double angleDegrees() {
        return Math.toDegrees(Math.atan2(y, x));
    }

    /** Sum of two displacements of the same space. */
    public PdfVector plus(PdfVector other) {
        PdfPoint.sameSpace(space, other.space(), "adding two vectors");
        return new PdfVector(x + other.x(), y + other.y(), space);
    }

    /** This displacement, {@code factor} times as long (or shorter, for a negative factor). */
    public PdfVector scaled(double factor) {
        return new PdfVector(x * factor, y * factor, space);
    }

    /** The same displacement the other way round. */
    public PdfVector negated() {
        return new PdfVector(-x, -y, space);
    }

    /** Whether both components differ by at most {@code epsilon}. */
    public boolean isCloseTo(PdfVector other, double epsilon) {
        return space.equals(other.space())
                && Math.abs(x - other.x()) <= epsilon
                && Math.abs(y - other.y()) <= epsilon;
    }

    /**
     * This displacement through a matrix - without the translation part, see
     * {@link PdfMatrix#mapVector(double, double)}.
     */
    public PdfVector map(PdfMatrix matrix, String targetSpace) {
        return matrix.map(this, targetSpace);
    }

    /** {@code [3, 0 in user]} - components first, space after, because both are the answer. */
    @Override
    public String toString() {
        return "[" + PdfMatrix.format(x) + ", " + PdfMatrix.format(y) + " in " + space + "]";
    }
}
