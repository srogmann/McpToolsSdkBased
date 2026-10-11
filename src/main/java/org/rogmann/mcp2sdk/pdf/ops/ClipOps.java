package org.rogmann.mcp2sdk.pdf.ops;

import org.rogmann.mcp2sdk.pdf.PdfException;
import org.rogmann.mcp2sdk.pdf.geo.PdfMatrix;
import org.rogmann.mcp2sdk.pdf.geo.PdfPageSpace;
import org.rogmann.mcp2sdk.pdf.geo.PdfPoint;
import org.rogmann.mcp2sdk.pdf.geo.PdfSpace;
import org.rogmann.mcp2sdk.pdf.state.PdfClipPath;
import org.rogmann.mcp2sdk.pdf.state.PdfGraphicsState;
import org.rogmann.mcp2sdk.pdf.state.PdfPath;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The clipping family — 4.4.3, Table 4.11 (PDF 1.7, S. 234/235): {@code W} and {@code W*}.
 *
 * <p>
 * One operator, two rules, and three sentences of the specification decide the implementation:
 * the new clip "is set to the <b>intersection</b> of the current clipping path and the newly
 * constructed path", "<b>there is no way to enlarge</b> the current clipping path", and
 * "execution of the {@code Q} operator causes the clipping path to revert to the value that was
 * saved by the {@code q} operator". Those are {@link PdfClipPath}'s and {@link
 * org.rogmann.mcp2sdk.pdf.state.PdfStateStack}'s job; what is left, and what this class is for, is
 * the one question D2 could not answer on its own: <em>which points go in.</em>
 * </p>
 *
 * <h2>When it happens: at the painting operator, not at the {@code W}</h2>
 *
 * S. 234 is unusually explicit about the moment: "Although the clipping path operator appears
 * before the painting operator, it does <b>not</b> alter the clipping path at the point where it
 * appears. Rather, it modifies the effect of the succeeding painting operator. After the path has
 * been painted, the clipping path in the graphics state is set to the intersection of the current
 * clipping path and the newly constructed path." So {@link org.rogmann.mcp2sdk.pdf.ops
 * .PdfContentInterpreter} queues the operator and calls this class at the operator carrying
 * {@link PdfContentOperator.Effect#ENDS_PATH} — with the {@code W}'s own offset for the layer, and
 * with the path <em>as the painting operator saw it</em>. For the {@code W n} pair every producer
 * writes (and that S. 235 itself names) the answer is the same as it was; it differs in the two
 * cases the sentence forces: a {@code W} whose path is never painted establishes no clip, and a
 * segment added after the {@code W} belongs to the region.
 *
 * <h2>The points are taken in page space, and only when the page is known</h2>
 *
 * D2 stores a clip in page space on purpose (plan decision 5) — a clip is device-fixed, so a later
 * {@code cm} must not be able to move it. Getting a path there means multiplying with the CTM of the
 * moment and the page frame, and that needs both:
 * <ul>
 *   <li>a {@link PdfPageSpace} — without one the operator is counted and reported and the clip stays
 *       as it was. Inventing page coordinates for a stream whose page was never read would be the
 *       kind of number this module has refused to print since increment A;</li>
 *   <li>a CTM that can be undone — a singular CTM has no inverse (4.2.3, S. 209 note) and the
 *       report is {@link PdfMatrix#singularNote()}, not a guessed rectangle.</li>
 * </ul>
 *
 * <h2>A clip built from curves is a control polygon, and says so</h2>
 *
 * The rings of a clip come out of the path's points; for a cubic segment those are its control
 * points, because finding where a Bézier actually reaches means flattening it, and flattening is
 * rendering (decision 12). The box of such a layer is therefore labelled
 * {@code "control-polygon"} — the same distinction {@link PdfPath#bboxSource()} draws, carried
 * through to the clip so that a reader can tell a 212-point polygon of {@code m}/{@code l}
 * (the check document's ordinary case, 23 650 {@code W*} against 857 {@code re}) from a curve's
 * envelope.
 */
public final class ClipOps {

    /**
     * What a {@code W}/{@code W*} did, once its painting operator arrived.
     *
     * @param state    the state that follows — unchanged when the clip could not be computed
     * @param notes    what to report at the operator's offset (never null, may be empty)
     * @param emptied  whether nothing can be painted inside the clip any more
     * @param computed whether a region was established at all — false when the walk has no page
     *                 frame, which is D3's rule: the operator is counted and named, and no page
     *                 coordinate is invented for a page nobody read
     */
    public record Result(PdfGraphicsState state, List<String> notes, boolean emptied,
                         boolean computed) { }

    private ClipOps() {
        // static family
    }

    /**
     * Applies one clipping operator.
     *
     * @param state    the state in effect — its clip is the one being narrowed
     * @param operator {@code W} or {@code W*}
     * @param path     the path that was just built (the operator consumes it, the interpreter ends it)
     * @param options  what the walk knows (the page frame)
     * @param offset   byte offset of the operator, stored on the clip layer
     * @return the new state and the reports
     */
    public static Result apply(PdfGraphicsState state, PdfContentOperator operator, PdfPath path,
                               PdfContentInterpreter.Options options, int offset) {
        List<String> notes = new ArrayList<>();
        PdfPageSpace pageSpace = options.pageSpace();
        if (pageSpace == null || !pageSpace.isUsable()) {
            notes.add(operator.name() + " narrowed the clipping path to a region of "
                    + path.subpaths() + " subpath(s), but this walk was given no page frame, so the"
                    + " region was not computed - a clip is stored in page space (4.4.3, S. 234/235:"
                    + " no later cm may move it), and page coordinates cannot be derived from user"
                    + " coordinates without the page's own box, rotation and UserUnit");
            return new Result(state, notes, false, false);
        }
        Optional<PdfMatrix> pageFrame = pageSpace.defaultUserToPage();
        if (pageFrame.isEmpty()) {
            notes.add("the page frame could not be built (" + pageSpace.defect()
                    + "), so the " + operator.name() + " at this offset is counted but not applied");
            return new Result(state, notes, false, false);
        }
        try {
            return compute(state, operator, path, pageFrame.get(), offset, notes);
        } catch (PdfException e) {
            // A coordinate that cannot be mapped (an operand the parser read as an infinite value),
            // a rule that does not fit: told at the operator, the clip stays as it was, the walk goes
            // on - the same rule the path family follows.
            notes.add("the " + operator.name() + " could not be turned into a clipping region: "
                    + e.getMessage());
            return new Result(state, notes, false, false);
        }
    }

    /**
     * A route that has no inverse (4.2.3, S. 209 note): a segment built under a singular CTM, or the
     * page frame itself. Mapping through one is defined but degenerate, and a clip whose region came
     * out of it would be a rectangle the file never drew — decision 9 says a sentence instead of a
     * coordinate.
     */
    private static PdfMatrix singularRoute(PdfPath path, PdfMatrix pageFrame) {
        if (pageFrame.isSingular()) {
            return pageFrame;
        }
        for (PdfPath.Segment segment : path.segments()) {
            PdfMatrix route = segment.ctm() == null
                    ? pageFrame : segment.ctm().multiply(pageFrame);
            if (route.isSingular()) {
                return route;
            }
        }
        return null;
    }

    /** The mapping and the intersection, once the page frame and the operator are known to be usable. */
    private static Result compute(PdfGraphicsState state, PdfContentOperator operator, PdfPath path,
                                  PdfMatrix pageFrame, int offset, List<String> notes) {
        PdfMatrix singular = singularRoute(path, pageFrame);
        if (singular != null) {
            notes.add("the path " + operator.name() + " would clip is built on a matrix that cannot be"
                    + " undone - " + singular.singularNote() + " The clip was left as it was: a region"
                    + " mapped through a singular matrix is degenerate by construction, and printing"
                    + " one would be a coordinate the file does not support (4.2.3, S. 209 note;"
                    + " plan decision 9)");
            return new Result(state, notes, false, false);
        }
        String rule = PdfClipPath.ruleOf(operator.name());
        List<List<PdfPoint>> rings = PathBuildOps.polylines(path, pageFrame, PdfSpace.PAGE);
        if (rings.isEmpty()) {
            notes.add(operator.name() + " ran on an empty path - 4.4.3, S. 234: \"the closed subpaths"
                    + " of this path define the area that can be painted\", and there is no subpath,"
                    + " so no area");
        }
        if (hasCurve(path)) {
            notes.add(curveNote(operator.name()));
        }
        if (!path.isCtmStable() && !path.isEmpty()) {
            notes.add("a cm ran in the middle of the clipped path, so its segments were mapped with"
                    + " the CTM each was built under (PdfPath.isCtmStable) - one matrix would have"
                    + " moved the earlier ones");
        }
        String bboxSource = "control-polygon".equals(path.bboxSource())
                ? "control-polygon" : PdfClipPath.SOURCE_PATH;
        PdfClipPath.Added added = state.clip().intersectWith(rings, rule, bboxSource, state.ctm(),
                offset);
        boolean wasEmpty = state.clip().isEmpty();
        if (added.note() != null) {
            notes.add(added.note());
        }
        return new Result(state.withClip(added.clip()), notes,
                added.clip().isEmpty() && !wasEmpty, true);
    }

    private static String curveNote(String operatorName) {
        return "the path clipped by " + operatorName + " contains cubic segments, so its ring is the"
                + " control polygon: where a Bézier turns is found by flattening it, and flattening"
                + " is rendering, which this module does not do (plan decision 12)";
    }

    private static boolean hasCurve(PdfPath path) {
        for (PdfPath.Segment segment : path.segments()) {
            if ("c".equals(segment.kind())) {
                return true;
            }
        }
        return false;
    }
}
