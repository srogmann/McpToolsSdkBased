package org.rogmann.mcp2sdk.pdf.state;

import org.rogmann.mcp2sdk.pdf.PdfException;
import org.rogmann.mcp2sdk.pdf.geo.PdfMatrix;
import org.rogmann.mcp2sdk.pdf.geo.PdfPoint;
import org.rogmann.mcp2sdk.pdf.geo.PdfRect;
import org.rogmann.mcp2sdk.pdf.geo.PdfSpace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The <em>current path</em> of a content stream - and the one object of a content stream that is
 * deliberately <b>not</b> part of the {@link PdfGraphicsState graphics state} (PDF 1.7 4.4.1
 * "Path Objects", S. 226):
 *
 * <blockquote>"The path currently under construction is called the current path. In PDF (unlike
 * PostScript), the current path is <b>not</b> part of the graphics state and is <b>not</b> saved
 * and restored along with the other graphics state parameters. PDF paths are strictly internal
 * objects with no explicit representation. Once a path has been painted, it is no longer defined;
 * there is then no current path until a new one is begun with the {@code m} or {@code re}
 * operator."</blockquote>
 * <p>
 * Two consequences this class exists to make impossible to get wrong. <b>First:</b> {@code q} and
 * {@code Q} do not touch a path, so a path built inside a saved block is still there after the
 * {@code Q} and the {@code S} that follows paints it - with the <em>restored</em> line width and
 * CTM. The plan's acceptance line for this increment claimed the opposite ("{@code q 0 0 10 10 re Q
 * S} malt nichts"); the sentence above, "unlike PostScript", is why that line was wrong and why
 * {@code PdfPathTest.aPathOutlivesTheQThatFollowedIt} pins the surviving path instead. The
 * requirement test of the plan gets this wrong too - it restores {@code path_start} on the stack.
 * <b>Second:</b> a painting operator ends the path, so a sink has to clear this object at
 * {@code S}/{@code f}/{@code n}/… and must not resurrect it later.
 * </p>
 *
 * <h2>What is stored, and in which space</h2>
 *
 * Every operand is kept as <em>user space</em> ({@link PdfSpace#USER}) - "All operands are numbers
 * denoting coordinates in user space" (S. 226, introducing Table 4.9) - together with the CTM that
 * was in effect <em>when the segment was added</em>. The CTM at painting time is a different
 * matrix whenever a {@code cm} ran in the middle of a path, and a consumer that maps the whole path
 * with the last one silently moves the earlier segments. {@link #isCtmStable()} answers that
 * question instead of assuming it away; {@link #segments()} carries the per-segment matrices.
 * <p>
 * Curves stay curves. {@link #bbox()} is the envelope of the control points and says so through
 * {@link #bboxSource()} - flattening a Bézier to find its true extent is rendering, which this
 * module does not do (plan decision 12).
 * </p>
 */
public final class PdfPath {

    /**
     * Segments one path may hold before it is cut. A polygon of 212 points is ordinary in the check
     * document (its clip regions are polygons, 23 650 {@code W*} against 857 {@code re}); a path
     * with a million segments is a broken loop, and cutting it names itself in
     * {@link #isTruncated()} rather than running the walk into memory. The number is
     * {@code pdf.status().maxPathSegments}.
     */
    public static final int MAX_SEGMENTS = 65_536;

    /** The four construction kinds plus the close, as a listing names them. */
    public static final List<String> SEGMENT_KINDS = List.of("m", "l", "c", "re", "h");

    /**
     * One constructed segment.
     *
     * @param kind          {@code m}, {@code l}, {@code c}, {@code re} or {@code h}
     * @param points        the segment's points in user space: the new point for {@code m} and
     *                      {@code l}, the two control points plus the end point for {@code c}, the
     *                      four corners in the walked order for {@code re}, the point the subpath
     *                      closes back to for {@code h}
     * @param startsSubpath whether this segment begins a subpath ({@code m}, {@code re}, or the
     *                      first segment after an {@code h})
     * @param closes        whether it closes the subpath again ({@code h}, and {@code re} - the
     *                      equivalence of S. 227 ends in {@code h})
     * @param ctm           the CTM in effect at the moment the operator ran
     */
    public record Segment(String kind, List<PdfPoint> points, boolean startsSubpath, boolean closes,
                          PdfMatrix ctm) {

        /**
         * Whether this segment encloses no area - a {@code re} with a zero width or height, which
         * stays the line it is (plan D6: "{@code re} mit Höhe 0 bleibt eine Linie und wird nicht zum
         * Rechteck") rather than becoming a region a clip could use.
         */
        public boolean isDegenerate() {
            if (points.size() < 3) {
                return true;
            }
            double sum = 0;
            for (int i = 0; i < points.size(); i++) {
                PdfPoint p = points.get(i);
                PdfPoint q = points.get((i + 1) % points.size());
                sum += p.x() * q.y() - q.x() * p.y();
            }
            return sum == 0;
        }

        /** The segment as the stream wrote it, without the operand numbers it started from. */
        public String describe() {
            StringBuilder sb = new StringBuilder(kind()).append(' ');
            for (PdfPoint point : points) {
                sb.append('(').append(PdfMatrix.format(point.x())).append(' ')
                        .append(PdfMatrix.format(point.y())).append(") ");
            }
            return sb.toString().trim();
        }
    }

    private final List<Segment> segments;
    private final PdfPoint currentPoint;
    private final PdfPoint subpathStart;
    private final boolean subpathClosed;
    private final boolean startNextSegment;
    private final int maxSegments;
    private final boolean truncated;

    private PdfPath(List<Segment> segments, PdfPoint currentPoint, PdfPoint subpathStart,
                    boolean subpathClosed, boolean startNextSegment, int maxSegments,
                    boolean truncated) {
        this.segments = segments;
        this.currentPoint = currentPoint;
        this.subpathStart = subpathStart;
        this.subpathClosed = subpathClosed;
        this.startNextSegment = startNextSegment;
        this.maxSegments = maxSegments;
        this.truncated = truncated;
    }

    /** An empty path with the default budget. */
    public static PdfPath empty() {
        return empty(MAX_SEGMENTS);
    }

    /** An empty path that gives up after {@code maxSegments} segments. */
    public static PdfPath empty(int maxSegments) {
        return new PdfPath(List.of(), null, null, false, true,
                Math.max(1, maxSegments), false);
    }

    /**
     * "Once a path has been painted, it is no longer defined; there is then no current path until a
     * new one is begun with the m or re operator" (S. 226). A sink calls this at every painting
     * operator, including {@code n}, which paints nothing but still ends the path object (Table
     * 4.10, S. 230).
     */
    public PdfPath painted() {
        return empty(maxSegments);
    }

    /** Whether nothing has been constructed yet - "If the current path is empty, the current point is undefined" (S. 226). */
    public boolean isEmpty() {
        return segments.isEmpty();
    }

    // ===================================================================
    // Construction operators (Table 4.9, S. 226/227)
    // ===================================================================

    /**
     * {@code x y m} - "Begin a new subpath by moving the current point to coordinates (x, y),
     * omitting any connecting line segment. If the previous path construction operator in the
     * current path was also m, the new m overrides it; no vestige of the previous m operation
     * remains in the path" (S. 226). The override is implemented, not merely allowed: a stream that
     * writes three {@code m}s in a row ends up with one subpath.
     */
    public PdfPath moveTo(PdfPoint point, PdfMatrix ctm) {
        requireUser(point, "m");
        List<Segment> next = new ArrayList<>(segments);
        if (!next.isEmpty() && last().kind().equals("m")) {
            // The override of S. 226: the new m replaces the old one, so the segment count does not
            // grow and no budget question arises.
            next.remove(next.size() - 1);
        } else if (outOfBudget()) {
            return cut();
        }
        next.add(new Segment("m", List.of(point), true, false, ctm));
        return new PdfPath(next, point, point, false, false, maxSegments, truncated);
    }

    /** {@code x y l} - "Append a straight line segment from the current point to the point (x, y)" (S. 226). */
    public PdfPath lineTo(PdfPoint point, PdfMatrix ctm) {
        requireUser(point, "l");
        requireCurrentPoint("l");
        return append("l", List.of(point), ctm);
    }

    /**
     * {@code x1 y1 x2 y2 x3 y3 c} - the cubic Bézier from the current point to {@code (x3, y3)}
     * with {@code (x1, y1)} and {@code (x2, y2)} as control points (S. 226).
     * <p>
     * {@code v} and {@code y} are the same segment with one control point taken from the context
     * (S. 229: "For the v operator, the first control point coincides with initial point of the
     * curve. For the y operator, the second control point coincides with final point"), so the
     * operator layer expands them here and the path never has to know that two spellings existed.
     * </p>
     */
    public PdfPath curveTo(PdfPoint control1, PdfPoint control2, PdfPoint end, PdfMatrix ctm) {
        requireUser(control1, "c");
        requireUser(control2, "c");
        requireUser(end, "c");
        requireCurrentPoint("c");
        return append("c", List.of(control1, control2, end), ctm);
    }

    /**
     * {@code h} - "Close the current subpath by appending a straight line segment from the current
     * point to the starting point of the subpath. If the current subpath is already closed, h does
     * nothing. This operator terminates the current subpath. Appending another segment to the
     * current path begins a new subpath, even if the new segment begins at the endpoint reached by
     * the h operation" (S. 227).
     */
    public PdfPath closeSubpath(PdfMatrix ctm) {
        requireCurrentPoint("h");
        if (subpathClosed) {
            return this;
        }
        if (outOfBudget()) {
            return cut();
        }
        List<Segment> next = new ArrayList<>(segments);
        next.add(new Segment("h", List.of(subpathStart), false, true, ctm));
        // The current point is the subpath's start now, and the next segment opens a new subpath -
        // both halves of the printed sentence, kept together on purpose.
        return new PdfPath(next, subpathStart, subpathStart, true, true, maxSegments, truncated);
    }

    /**
     * {@code x y width height re} - appended "as a complete subpath" through the printed
     * equivalence of S. 227 ({@code x y m}, {@code (x+width) y l}, {@code (x+width) (y+height) l},
     * {@code x (y+height) l}, {@code h}). The corners therefore come out in the order the producer
     * walked them, and a rectangle with a negative extent walks the other way round rather than
     * being normalised ({@link PdfRect#signedHeight()} keeps the sign).
     * <p>
     * The equivalence ends in {@code h}, so the subpath is closed and the current point is the
     * written corner {@code (x, y)} - the reading S. 227 implies and the reference nowhere states
     * outright, recorded here because a following {@code l} depends on it.
     * </p>
     */
    public PdfPath appendRectangle(PdfRect rect, PdfMatrix ctm) {
        if (!PdfSpace.USER.equals(rect.space())) {
            throw new PdfException("A re operand pair is a rectangle in user space (4.4.1, S. 227),"
                    + " this one is labelled '" + rect.space() + "'");
        }
        if (outOfBudget()) {
            return cut();
        }
        List<Segment> next = new ArrayList<>(segments);
        next.add(new Segment("re", rect.corners().points(), true, true, ctm));
        return new PdfPath(next, rect.corners().point(0), rect.corners().point(0), true, true,
                maxSegments, truncated);
    }

    private PdfPath append(String kind, List<PdfPoint> points, PdfMatrix ctm) {
        if (outOfBudget()) {
            return cut();
        }
        List<Segment> next = new ArrayList<>(segments);
        next.add(new Segment(kind, points, startNextSegment, false, ctm));
        PdfPoint end = points.get(points.size() - 1);
        // A segment that opens a subpath starts it at the point it was drawn *from* - the current
        // point - not at the point it lands on; getting these two mixed up is a closing line that
        // jumps back to the wrong corner.
        PdfPoint start = startNextSegment ? currentPoint : subpathStart;
        return new PdfPath(next, end, start, false, false, maxSegments, truncated);
    }

    private Segment last() {
        return segments.get(segments.size() - 1);
    }

    /** Whether the next segment would not fit. */
    private boolean outOfBudget() {
        return segments.size() >= maxSegments;
    }

    /**
     * This path with the cut marked. The segments already read stay readable - the plan's rule is
     * that a budget cut reports itself at the place it happened, so a result says "the path was cut
     * after n segments" instead of quietly showing a shorter shape.
     */
    private PdfPath cut() {
        if (truncated) {
            return this;
        }
        return new PdfPath(segments, currentPoint, subpathStart, subpathClosed, startNextSegment,
                maxSegments, true);
    }

    private void requireCurrentPoint(String kind) {
        if (currentPoint == null) {
            throw new PdfException("The '" + kind + "' operator needs a current point, and the path"
                    + " is empty - 4.4.1, S. 226: \"If the current path is empty, the current point"
                    + " is undefined … if the current point is undefined, an error is generated\"");
        }
    }

    private void requireUser(PdfPoint point, String kind) {
        if (!PdfSpace.USER.equals(point.space())) {
            throw new PdfException("The '" + kind + "' operand is in '" + point.space()
                    + "' space; path construction operands are user space coordinates (4.4.1,"
                    + " S. 226)");
        }
    }

    // ===================================================================
    // Reading it
    // ===================================================================

    /** The segments in document order. */
    public List<Segment> segments() {
        return segments;
    }

    /** The current point, or {@code null} when there is no path ("the current point is undefined", S. 226). */
    public PdfPoint currentPoint() {
        return currentPoint;
    }

    /** Where the current subpath started - what {@code h} closes back to. */
    public PdfPoint subpathStart() {
        return subpathStart;
    }

    /** Whether the current subpath is closed. */
    public boolean isClosed() {
        return subpathClosed;
    }

    /** Whether a subpath is open, i.e. whether a painting operator would implicitly close it (S. 232: "Any subpaths that are open are implicitly closed before being filled"). */
    public boolean hasOpenSubpath() {
        return !segments.isEmpty() && !subpathClosed;
    }

    /** Number of subpaths - segments that began one (S. 226: {@code m}/{@code re}, and the first segment after {@code h}). */
    public int subpaths() {
        int count = 0;
        for (Segment segment : segments) {
            if (segment.startsSubpath()) {
                count++;
            }
        }
        return count;
    }

    /** How many segments of each kind - the {@code segments: {l: 6, c: 1}} of a placement answer. */
    public Map<String, Long> counts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Segment segment : segments) {
            counts.merge(segment.kind(), 1L, Long::sum);
        }
        return counts;
    }

    /**
     * The envelope of every point of the path - endpoints <em>and</em> control points - in user
     * space, or {@code null} for an empty path. Read it together with {@link #bboxSource()}: for a
     * path containing a curve this is the box of the control polygon, which is bigger than the
     * curve and is not the curve's box.
     */
    public PdfRect bbox() {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        boolean any = false;
        for (Segment segment : segments) {
            for (PdfPoint point : segment.points()) {
                any = true;
                minX = Math.min(minX, point.x());
                minY = Math.min(minY, point.y());
                maxX = Math.max(maxX, point.x());
                maxY = Math.max(maxY, point.y());
            }
        }
        return any ? PdfRect.of(minX, minY, maxX, maxY, PdfSpace.USER) : null;
    }

    /**
     * What kind of box {@link #bbox()} is: {@code "control-polygon"} once a curve is in the path,
     * {@code "path-points"} for a path of straight segments only, {@code "none"} for an empty path.
     * Named because the three are different claims (decision 12).
     */
    public String bboxSource() {
        if (segments.isEmpty()) {
            return "none";
        }
        for (Segment segment : segments) {
            if (segment.kind().equals("c")) {
                return "control-polygon";
            }
        }
        return "path-points";
    }

    /**
     * The CTM that was in effect when the first segment was added, or {@code null} for an empty
     * path. Together with {@link #ctmAtLastConstruction()} this tells a sink whether one matrix
     * maps the whole path.
     */
    public PdfMatrix ctmAtFirstConstruction() {
        return segments.isEmpty() ? null : segments.get(0).ctm();
    }

    /** The CTM of the most recent segment. */
    public PdfMatrix ctmAtLastConstruction() {
        return segments.isEmpty() ? null : segments.get(segments.size() - 1).ctm();
    }

    /**
     * Whether every segment was added under the same CTM. False means a {@code cm} ran in the
     * middle of the path: mapping the segments with the matrix of the painting operator moves the
     * ones built before it, and a report that does not say so is wrong by however far that matrix
     * differs.
     */
    public boolean isCtmStable() {
        PdfMatrix first = ctmAtFirstConstruction();
        for (Segment segment : segments) {
            if (first == null ? segment.ctm() != null : !first.equals(segment.ctm())) {
                return false;
            }
        }
        return true;
    }

    /** Whether the segment budget cut this path. */
    public boolean isTruncated() {
        return truncated;
    }

    /** The note a sink adds when {@link #isTruncated()} is true. */
    public String truncatedNote() {
        return "the path was cut after " + maxSegments + " segments (the operator that would have"
                + " added one more is '" + (segments.isEmpty() ? "?" : segments.get(segments.size()
                - 1).kind()) + "' - the rest of the path was read but not kept)";
    }

    /** How many segments the path may hold. */
    public int maxSegments() {
        return maxSegments;
    }

    /** A one-line summary for a listing: subpaths, segment counts, current point. */
    public String describe() {
        if (segments.isEmpty()) {
            return "path: empty";
        }
        StringBuilder sb = new StringBuilder("path: ").append(subpaths()).append(" subpath(s), ");
        boolean first = true;
        for (Map.Entry<String, Long> entry : counts().entrySet()) {
            sb.append(first ? "" : ", ").append(entry.getKey()).append(' ').append(entry.getValue());
            first = false;
        }
        sb.append(", current point ").append(currentPoint == null ? "undefined" : currentPoint);
        if (!isCtmStable()) {
            sb.append(", CTM changed mid-path");
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return describe();
    }
}
