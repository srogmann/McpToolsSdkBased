package org.rogmann.mcp2sdk.pdf.geo;

import org.rogmann.mcp2sdk.pdf.PdfException;

import java.util.List;

/**
 * A rectangle as a PDF file writes one (PDF 1.7 3.8.4 "Rectangles", S. 161) in one named space.
 * <p>
 * The specification describes it as "an array of four numbers giving the coordinates of a pair of
 * diagonally opposite corners", typically {@code [llx lly urx ury]} - and then adds the sentence
 * this class is built around: "Although rectangles are conventionally specified by their
 * lower-left and upper-right corners, it is acceptable to specify any two diagonally opposite
 * corners. Applications that process PDF should be prepared to normalize such rectangles in
 * situations where specific corners are required."
 * </p>
 * <p>
 * So this record keeps <em>both</em>: {@link #x1()}/{@link #y1()}/{@link #x2()}/{@link #y2()} are
 * the four numbers as the file wrote them - a page whose MediaBox is {@code [0 792 612 0]} stays
 * exactly that, because "the reader does not normalise anything" is this module's standing promise -
 * while {@link #minX()}, {@link #height()} and {@link #normalized()} are the normalised view the
 * specification tells a consumer to be prepared to form. {@link #fromExtent(double, double, double,
 * double, String)} is the {@code re} form of 4.4.1, S. 227, where a negative height is legal: the
 * equivalence printed there ({@code x y m}, {@code (x+width) y l}, {@code (x+width) (y+height) l},
 * {@code x (y+height) l}, {@code h}) simply walks the rectangle the other way round.
 * </p>
 * <p>
 * A rectangle whose width or height is zero is <em>not</em> empty - it is the degenerate case the
 * path section keeps as a line (plan, task D6: "{@code re} with height 0 stays a line and does not
 * become a rectangle"), reported by {@link #isDegenerate()} rather than silently dropped.
 * </p>
 *
 * @param x1    x of the first written corner (not necessarily the smaller one)
 * @param y1    y of the first written corner
 * @param x2    x of the diagonally opposite corner
 * @param y2    y of the diagonally opposite corner
 * @param space the space these four numbers belong to, one of {@link PdfSpace#ALL}
 */
public record PdfRect(double x1, double y1, double x2, double y2, String space) {

    /** A rectangle whose four corners are checked; the space label is required. */
    public PdfRect {
        PdfSpace.require(space);
        for (double v : new double[]{x1, y1, x2, y2}) {
            if (!Double.isFinite(v)) {
                throw new PdfException("A rectangle of space '" + space + "' carries a coordinate"
                        + " that is not a number: " + List.of(x1, y1, x2, y2));
            }
        }
    }

    /** The four numbers of a PDF rectangle array, as the file wrote them. */
    public static PdfRect of(double x1, double y1, double x2, double y2, String space) {
        return new PdfRect(x1, y1, x2, y2, space);
    }

    /**
     * A rectangle given as a point plus extent - the {@code re} operand form {@code x y width
     * height} (4.4.1, S. 227). A negative extent is kept, not turned around: it says which way the
     * producer walked the subpath.
     */
    public static PdfRect fromExtent(double x, double y, double width, double height,
                                     String space) {
        return new PdfRect(x, y, x + width, y + height, space);
    }

    /**
     * A rectangle array read from a file (MediaBox, CropBox, {@code /Rect}, ...).
     *
     * @param values exactly four numbers ({@link Long} or {@link Double} as the parser produces)
     * @throws PdfException when the array holds anything but four numbers
     */
    public static PdfRect fromArray(List<?> values, String space) {
        if (values == null || values.size() != 4) {
            throw new PdfException("A PDF rectangle is an array of four numbers, this one holds "
                    + (values == null ? "nothing" : values.size() + " value(s)")
                    + " (3.8.4, S. 161)");
        }
        double[] four = new double[4];
        for (int i = 0; i < 4; i++) {
            four[i] = number(values.get(i), i);
        }
        return new PdfRect(four[0], four[1], four[2], four[3], space);
    }

    /** The smaller x - the left edge whichever corner the file wrote first. */
    public double minX() {
        return Math.min(x1, x2);
    }

    /** The smaller y - the bottom edge in a space whose y points up. */
    public double minY() {
        return Math.min(y1, y2);
    }

    /** The larger x. */
    public double maxX() {
        return Math.max(x1, x2);
    }

    /** The larger y. */
    public double maxY() {
        return Math.max(y1, y2);
    }

    /** Extension along x, always &ge; 0 (the written sign is in {@link #signedWidth()}). */
    public double width() {
        return Math.abs(x2 - x1);
    }

    /** Extension along y, always &ge; 0. */
    public double height() {
        return Math.abs(y2 - y1);
    }

    /** The written x extent, sign included ({@code x2 - x1}) - what a {@code re} said. */
    public double signedWidth() {
        return x2 - x1;
    }

    /** The written y extent, sign included ({@code y2 - y1}). */
    public double signedHeight() {
        return y2 - y1;
    }

    /** Whether the file wrote the corners the conventional way (first one lower-left). */
    public boolean isNormalized() {
        return x1 <= x2 && y1 <= y2;
    }

    /** The same rectangle written {@code [llx lly urx ury]} - what 3.8.4 tells a consumer to form. */
    public PdfRect normalized() {
        if (isNormalized()) {
            return this;
        }
        return new PdfRect(minX(), minY(), maxX(), maxY(), space);
    }

    /**
     * Whether one dimension is zero: a line or a point. Degenerate is not empty - the path section
     * keeps such a rectangle as the line it is, and a clip built of it clips nothing.
     */
    public boolean isDegenerate() {
        return width() == 0 || height() == 0;
    }

    /** Area in the square units of the space (0 for a degenerate rectangle). */
    public double area() {
        return width() * height();
    }

    /** Centre of the rectangle, in the same space. */
    public PdfPoint centre() {
        return new PdfPoint((minX() + maxX()) / 2, (minY() + maxY()) / 2, space);
    }

    /** Whether a coordinate of the same space lies inside, edges included. */
    public boolean contains(double px, double py) {
        return px >= minX() && px <= maxX() && py >= minY() && py <= maxY();
    }

    /** Whether a point of the same space lies inside, edges included. */
    public boolean contains(PdfPoint point) {
        PdfPoint.sameSpace(space, point.space(), "testing whether a rectangle holds a point");
        return contains(point.x(), point.y());
    }

    /**
     * The four corners in the order the written pair implies - first the written corner, then
     * around. This is the traversal {@code re} documents (S. 227), which is what a clip path needs;
     * a consumer that only wants "the box" should use {@link #normalized()}.
     */
    public PdfQuad corners() {
        return PdfQuad.of(new PdfPoint(x1, y1, space), new PdfPoint(x2, y1, space),
                new PdfPoint(x2, y2, space), new PdfPoint(x1, y2, space));
    }

    /**
     * The rectangle through a matrix. The answer is a {@link PdfQuad}, never a rectangle: only a
     * diagonal or axis-preserving matrix keeps an axis-aligned box axis-aligned, and a rotated box
     * whose coordinates were silently re-boxed would be reported bigger than the object that
     * produced it (plan decision 12 - no claim a file does not support).
     */
    public PdfQuad map(PdfMatrix matrix, String targetSpace) {
        return matrix.map(this, targetSpace);
    }

    /** The intersection with another rectangle of the same space, or null when they miss. */
    public PdfRect intersect(PdfRect other) {
        PdfPoint.sameSpace(space, other.space(), "intersecting two rectangles");
        double left = Math.max(minX(), other.minX());
        double bottom = Math.max(minY(), other.minY());
        double right = Math.min(maxX(), other.maxX());
        double top = Math.min(maxY(), other.maxY());
        if (left > right || bottom > top) {
            return null;
        }
        return new PdfRect(left, bottom, right, top, space);
    }

    /**
     * Relabels the space of this rectangle - for a caller that established the space itself (a box
     * read out of a page dictionary, a MediaBox whose origin was subtracted). It moves no number:
     * the way from one space into another is a matrix, {@link #map(PdfMatrix, String)}.
     */
    public PdfRect in(String newSpace) {
        return new PdfRect(x1, y1, x2, y2, newSpace);
    }

    /** Whether all four numbers differ by at most {@code epsilon}. */
    public boolean isCloseTo(PdfRect other, double epsilon) {
        return space.equals(other.space())
                && Math.abs(x1 - other.x1()) <= epsilon
                && Math.abs(y1 - other.y1()) <= epsilon
                && Math.abs(x2 - other.x2()) <= epsilon
                && Math.abs(y2 - other.y2()) <= epsilon;
    }

    /** The PDF array text plus the space, e.g. {@code [0 0 612 792 in defaultUser]}. */
    @Override
    public String toString() {
        return "[" + PdfMatrix.format(x1) + " " + PdfMatrix.format(y1) + " "
                + PdfMatrix.format(x2) + " " + PdfMatrix.format(y2) + " in " + space + "]";
    }

    private static double number(Object value, int index) {
        if (value instanceof Long number) {
            return number.doubleValue();
        }
        if (value instanceof Double number) {
            return number;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        throw new PdfException("A PDF rectangle is an array of four numbers; entry " + (index + 1)
                + " is a " + (value == null ? "null" : value.getClass().getSimpleName())
                + " (PDF 1.7, §3.8.4, S. 161)");
    }
}
