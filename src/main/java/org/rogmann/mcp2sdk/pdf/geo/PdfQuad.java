package org.rogmann.mcp2sdk.pdf.geo;

import org.rogmann.mcp2sdk.pdf.PdfException;

import java.util.List;

/**
 * Four points in one named space, taken in order and closed by the segment from the last vertex
 * back to the first - the shape a rectangle becomes once a matrix has moved it.
 * <p>
 * {@link PdfMatrix#map(PdfRect, String)} answers with this and not with a {@link PdfRect} on
 * purpose. A page that is rotated, a form whose {@code /Matrix} turns 90 degrees, a text block in a
 * {@code T_m} that skews: in all of them the image of a box is a box <em>in its own space</em> and
 * only <em>some</em> box in the space behind the matrix. Reporting the axis-aligned envelope as the
 * answer would claim a bigger object than the file drew (plan decision 12: a path's bounding box
 * out of control points is called {@code control-polygon}, not {@code bbox}), so the four vertices
 * are the answer and {@link #bbox()} is the envelope - a separate question with a separate name.
 * </p>
 * <p>
 * Clip regions are the reason this class exists at all: the check document of the plan carries 794
 * {@code W*} against 63 {@code re}, so clips arrive as polygons and a clip that is only ever stored
 * as a rectangle loses the shape the producer chose (5.2.3, S. 234 - the clipping path is the
 * closed path itself, and {@code W} and {@code W*} differ in the fill rule, not in the box).
 * </p>
 *
 * @param points the four vertices in order (clockwise or counter-clockwise - the direction is kept,
 *               because it is the direction the producer walked)
 * @param space  the space all four share, one of {@link PdfSpace#ALL}
 */
public record PdfQuad(List<PdfPoint> points, String space) {

    /** The four vertices, unmodifiable, all in the given space. */
    public PdfQuad {
        PdfSpace.require(space);
        if (points == null || points.size() != 4) {
            throw new PdfException("A quadrilateral has four vertices, this one carries "
                    + (points == null ? "none" : points.size()));
        }
        points = List.copyOf(points);
        for (PdfPoint point : points) {
            PdfPoint.sameSpace(space, point.space(), "naming the space of a quadrilateral");
        }
    }

    /** Four vertices of one space, in order. */
    public static PdfQuad of(PdfPoint a, PdfPoint b, PdfPoint c, PdfPoint d) {
        return new PdfQuad(List.of(a, b, c, d), a.space());
    }

    /** The vertex at {@code index} (0..3). */
    public PdfPoint point(int index) {
        if (index < 0 || index >= 4) {
            throw new PdfException("A quadrilateral has vertices 0..3, asked for " + index);
        }
        return points.get(index);
    }

    /**
     * The axis-aligned envelope of the four vertices in the same space. A question of its own:
     * a rotated 100 × 20 box has a wider envelope than it is long, and a caller who wants the
     * object must read {@link #points()}, a caller who wants the region reads this.
     */
    public PdfRect bbox() {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (PdfPoint point : points) {
            minX = Math.min(minX, point.x());
            minY = Math.min(minY, point.y());
            maxX = Math.max(maxX, point.x());
            maxY = Math.max(maxY, point.y());
        }
        return PdfRect.of(minX, minY, maxX, maxY, space);
    }

    /**
     * Whether the quadrilateral is an axis-aligned rectangle within {@code epsilon} - the test that
     * decides whether a result may <em>say</em> "rectangle" instead of showing four vertices. The
     * test is a one-to-one match between the four vertices and the four corners of the envelope:
     * "every vertex lies somewhere on the border" would already be true for a hexagon's shadow or a
     * quad that walks a corner twice.
     */
    public boolean isAxisAligned(double epsilon) {
        PdfRect box = bbox();
        double[][] corners = {
                {box.minX(), box.minY()}, {box.maxX(), box.minY()},
                {box.maxX(), box.maxY()}, {box.minX(), box.maxY()},
        };
        boolean[] taken = new boolean[4];
        int matched = 0;
        for (PdfPoint point : points) {
            for (int i = 0; i < 4; i++) {
                if (taken[i] || Math.abs(point.x() - corners[i][0]) > epsilon
                        || Math.abs(point.y() - corners[i][1]) > epsilon) {
                    continue;
                }
                taken[i] = true;
                matched++;
                break;
            }
        }
        return matched == 4;
    }

    /**
     * Area enclosed by the four vertices (shoelace, order-independent sign removed), in the square
     * units of the space. Zero says "this encloses nothing" - a clip made of a horizontal line, say.
     */
    public double area() {
        double sum = 0;
        for (int i = 0; i < 4; i++) {
            PdfPoint p = points.get(i);
            PdfPoint q = points.get((i + 1) % 4);
            sum += p.x() * q.y() - q.x() * p.y();
        }
        return Math.abs(sum) / 2;
    }

    /** Whether the enclosed area is zero within {@code epsilon} (a line or a point in disguise). */
    public boolean isDegenerate(double epsilon) {
        return area() <= epsilon;
    }

    /** Length of the four sides, in the units of the space. */
    public double perimeter() {
        double sum = 0;
        for (int i = 0; i < 4; i++) {
            sum += points.get(i).distance(points.get((i + 1) % 4));
        }
        return sum;
    }

    /** The four vertices through a matrix, landing in {@code targetSpace}. */
    public PdfQuad map(PdfMatrix matrix, String targetSpace) {
        return new PdfQuad(points.stream().map(p -> matrix.map(p, targetSpace)).toList(),
                targetSpace);
    }

    /** Whether all eight coordinates differ by at most {@code epsilon}. */
    public boolean isCloseTo(PdfQuad other, double epsilon) {
        if (!space.equals(other.space())) {
            return false;
        }
        for (int i = 0; i < 4; i++) {
            if (!points.get(i).isCloseTo(other.points.get(i), epsilon)) {
                return false;
            }
        }
        return true;
    }

    /** {@code (54, 48 in page) (666, 48 in page) ...} - the vertices, in order. */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (PdfPoint point : points) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(point);
        }
        return sb.toString();
    }
}
