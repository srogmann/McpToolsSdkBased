package org.rogmann.mcp2sdk.pdf.ops;

import org.rogmann.mcp2sdk.pdf.PdfException;
import org.rogmann.mcp2sdk.pdf.geo.PdfMatrix;
import org.rogmann.mcp2sdk.pdf.geo.PdfPoint;
import org.rogmann.mcp2sdk.pdf.geo.PdfRect;
import org.rogmann.mcp2sdk.pdf.geo.PdfSpace;
import org.rogmann.mcp2sdk.pdf.state.PdfPath;

import java.util.ArrayList;
import java.util.List;

/**
 * The path construction family — 4.4.1, Table 4.9 (PDF 1.7, S. 226-229):
 * {@code m l c v y h re}.
 *
 * <p>
 * The family's whole job is to turn numbers into the {@link PdfPath} of increment D2, in the right
 * space and with the right CTM, and to survive the two ways a real stream breaks the rules:
 * </p>
 * <ul>
 *   <li><b>a segment without a current point.</b> {@code 100 200 l} as the first path operator, or
 *       anything after a painting operator that emptied the path. 4.4.1, S. 226 is explicit — "If the
 *       current path is empty, the current point is undefined … if the current point is undefined, an
 *       error is generated" — so the segment is <em>not</em> guessed at and the walk reports the
 *       operator instead.</li>
 *   <li><b>the two shorthands.</b> {@code v} and {@code y} are one control point short, and the
 *       missing one comes from the context: "For the v operator, the first control point coincides
 *       with initial point of the curve. For the y operator, the second control point coincides with
 *       final point" (S. 229). The expansion happens <em>here</em>, so the path only ever holds a
 *       real three-point {@code c} and a later consumer cannot mistake a shorthand for a curve that
 *       was written with two independent control points.</li>
 * </ul>
 *
 * <h2>Every operand stays in user space</h2>
 *
 * "All operands are numbers denoting coordinates in user space" (S. 226) — user space <em>as the CTM
 * of the moment defines it</em>, which is why {@link PdfPath.Segment#ctm()} is stored per segment and
 * a {@code cm} in the middle of a path does not move the segments already built
 * ({@link PdfPath#isCtmStable()}).
 */
public final class PathBuildOps {

    /**
     * What one construction operator produced.
     *
     * @param path   the path that follows
     * @param defect what the operator could not do, or null
     */
    public record Result(PdfPath path, String defect) {

        /** Whether the operator made it onto the path. */
        public boolean built() {
            return defect == null;
        }
    }

    private PathBuildOps() {
        // static family
    }

    /**
     * Applies one path construction operator.
     *
     * @param path     the path in effect
     * @param operator the registry row (a {@code path-construction} one)
     * @param stack    its operands, arity already checked
     * @param ctm      the CTM in effect at the operator — stamped onto the new segment
     * @return the new path, and the report when there was nothing to build on
     */
    public static Result apply(PdfPath path, PdfContentOperator operator, PdfOperandStack stack,
                               PdfMatrix ctm) {
        String name = operator.name();
        try {
            return switch (name) {
                case "m" -> ok(path.moveTo(point(stack, 0), ctm));
                case "l" -> needPoint(path, name, () -> ok(path.lineTo(point(stack, 0), ctm)));
                case "c" -> needPoint(path, name,
                        () -> ok(path.curveTo(point(stack, 0), point(stack, 2), point(stack, 4),
                                ctm)));
                // v: x2 y2 x3 y3 — the first control point is the current point (S. 229).
                case "v" -> needPoint(path, name, () -> {
                    PdfPoint start = path.currentPoint();
                    return ok(path.curveTo(start, point(stack, 0), point(stack, 2), ctm));
                });
                // y: x1 y1 x3 y3 — the second control point is the end point (S. 229).
                case "y" -> needPoint(path, name, () -> {
                    PdfPoint end = point(stack, 2);
                    return ok(path.curveTo(point(stack, 0), end, end, ctm));
                });
                case "h" -> needPoint(path, name, () -> ok(path.closeSubpath(ctm)));
                // The printed equivalence of S. 227 is m l l l h; PdfPath.appendRectangle walks it,
                // including the direction a negative extent takes (PdfRect.signedHeight keeps it).
                case "re" -> ok(path.appendRectangle(PdfRect.fromExtent(stack.number(0),
                        stack.number(1), stack.number(2), stack.number(3), PdfSpace.USER), ctm));
                default -> new Result(path, null);
            };
        } catch (PdfException e) {
            // A path rule the file broke (a space label that does not fit, a budget the path cut):
            // reported at the operator, never thrown at the caller of the walk.
            return new Result(path, name + ": " + e.getMessage());
        }
    }

    private static Result ok(PdfPath next) {
        return new Result(next, null);
    }

    /**
     * Runs the operator only if a current point exists — the pre-check, so that the message names
     * the operator and its situation rather than the internals of {@link PdfPath}.
     */
    private static Result needPoint(PdfPath path, String name, java.util.function.Supplier<Result> build) {
        if (path.currentPoint() == null) {
            return new Result(path, "the '" + name + "' operator needs a current point and the path"
                    + " is empty - 4.4.1, S. 226: \"If the current path is empty, the current point"
                    + " is undefined … if the current point is undefined, an error is generated\";"
                    + " the segment was not added and the walk continued");
        }
        return build.get();
    }

    /** One {@code x y} pair of the operands, labelled user space. */
    private static PdfPoint point(PdfOperandStack stack, int index) {
        return new PdfPoint(stack.number(index), stack.number(index + 1), PdfSpace.USER);
    }

    /**
     * One segment of a path, mapped into another space. The {@link PdfPath.Segment}'s kind and
     * subpath markers are kept, the points are the ones to print.
     *
     * @param kind          {@code m}, {@code l}, {@code c}, {@code re} or {@code h}
     * @param points        the segment's points in the target space (a {@code c} carries its two
     *                      control points, a {@code re} its four corners in the walked order)
     * @param startsSubpath whether this segment began a subpath
     * @param closes        whether it closed one
     * @param ctm           the CTM the operator ran under, <b>before</b> the page's own matrix —
     *                      the matrix {@code at.ctm} names for a segment of the last operator, and
     *                      what decides whether the segment's space was mirrored
     *                      ({@link PdfMatrix#isOrientationPreserving()})
     */
    public record PlacedSegment(String kind, List<PdfPoint> points, boolean startsSubpath,
                                boolean closes, PdfMatrix ctm) {

        /** The segment's points, never null. */
        public PlacedSegment {
            points = points == null ? List.of() : List.copyOf(points);
        }

        /** Whether the matrix this segment ran under changed the handedness of the space. */
        public boolean isMirrored() {
            return ctm != null && !ctm.isOrientationPreserving();
        }
    }

    /**
     * The path's segments in another space, one entry per segment — the form {@code
     * pdf.placement()}'s {@code drawing} field prints (increment D8b) and the form {@link
     * #polylines} groups its rings from.
     *
     * <p>
     * Per-segment matrices are the whole point: a segment carries the CTM it was built under, so a
     * {@code cm} in the middle of a path moves the segments that came after it and leaves the earlier
     * ones where the file put them. Mapping the whole path with the painting operator's matrix — the
     * shortcut this class exists to prevent — is wrong by however far the two differ.
     * </p>
     *
     * @param path        the path as a painting operator saw it
     * @param toPage      the matrix out of default user space (the page's own), or the identity to
     *                    stop in default user space
     * @param targetSpace what the numbers should be labelled
     * @return one entry per segment, in document order
     */
    public static List<PlacedSegment> placed(PdfPath path, PdfMatrix toPage, String targetSpace) {
        PdfMatrix out = toPage == null ? PdfMatrix.identity() : toPage;
        List<PlacedSegment> segments = new ArrayList<>();
        for (PdfPath.Segment segment : path.segments()) {
            PdfMatrix route = segment.ctm() == null ? out : segment.ctm().multiply(out);
            List<PdfPoint> points = new ArrayList<>(segment.points().size());
            for (PdfPoint point : segment.points()) {
                points.add(route.map(point, targetSpace));
            }
            segments.add(new PlacedSegment(segment.kind(), points, segment.startsSubpath(),
                    segment.closes(), segment.ctm()));
        }
        return segments;
    }

    /**
     * The path's subpaths as polylines in another space — the one place that knows how a user-space
     * path leaves user space.
     *
     * <p>
     * Every segment carries the CTM it was built under ({@link PdfPath.Segment#ctm()}), so each is
     * mapped with its own matrix and the page frame: a {@code cm} in the middle of a path must not
     * move the segments that came before it. Every polyline is closed implicitly, because "any
     * subpaths that are open are implicitly closed before being filled" (4.5.2, S. 232) — filling is
     * what defines a region, so a clip and a path item see the same outline here and cannot drift.
     * </p>
     *
     * @param path        the path as an operator saw it
     * @param toPage      the matrix out of default user space (the page's own), or the identity to
     *                    stop in default user space
     * @param targetSpace what the numbers should be labelled
     * @return one polyline per subpath, in document order
     */
    public static List<List<PdfPoint>> polylines(PdfPath path, PdfMatrix toPage,
                                                 String targetSpace) {
        List<List<PdfPoint>> rings = new ArrayList<>();
        List<PdfPoint> ring = null;
        for (PlacedSegment segment : placed(path, toPage, targetSpace)) {
            if (segment.startsSubpath() && ring != null && !ring.isEmpty()) {
                rings.add(ring);
                ring = null;
            }
            if (ring == null) {
                ring = new ArrayList<>();
            }
            ring.addAll(segment.points());
            if (segment.closes()) {
                rings.add(ring);
                ring = null;
            }
        }
        if (ring != null && !ring.isEmpty()) {
            rings.add(ring);
        }
        return rings;
    }
}
