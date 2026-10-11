package org.rogmann.mcp2sdk.pdf.state;

import org.rogmann.mcp2sdk.pdf.PdfException;
import org.rogmann.mcp2sdk.pdf.geo.PdfMatrix;
import org.rogmann.mcp2sdk.pdf.geo.PdfPoint;
import org.rogmann.mcp2sdk.pdf.geo.PdfRect;
import org.rogmann.mcp2sdk.pdf.geo.PdfSpace;

import java.util.ArrayList;
import java.util.List;

/**
 * The <em>current clipping path</em> - the graphics state parameter that 4.4.3 (PDF 1.7, S. 234)
 * describes, kept in <b>page space</b> so that no later {@code cm} can move it:
 *
 * <blockquote>"The graphics state contains a current clipping path that limits the regions of the
 * page affected by painting operators. The closed subpaths of this path define the area that can be
 * painted. … After the path has been painted, the clipping path in the graphics state is set to the
 * intersection of the current clipping path and the newly constructed path."</blockquote>
 * <p>
 * Two sentences of that section decide the whole design. <b>Intersection, never union</b> - S. 235
 * is blunt: "There is no way to enlarge the current clipping path or to set a new clipping path
 * without reference to the current one." So {@link #intersectWith} can only shrink, and a region
 * that stops overlapping what was already clipped is reported as {@link #isEmpty()}: the honest
 * answer for "what does this page paint inside here" is then <em>nothing</em>, not the newest
 * polygon. <b>Device-fixed</b> - because the clip is established in the space of the page and not
 * in the user space of the moment, a {@code cm} afterwards cannot slide it, and a consumer does not
 * have to know which CTM was current three operators earlier (plan decision 5).
 * </p>
 *
 * <h2>Why there are layers, and what "the bounding box of a clip" honestly is</h2>
 *
 * Each {@code W}/{@code W*} adds a layer with its own rings and its own fill rule - the rules
 * differ per operator (Table 4.11, S. 235: {@code W} uses the nonzero winding number rule,
 * {@code W*} the even-odd one), so a single "the rule" of a nested clip would be a guess.
 * <p>
 * The region of the whole clip is reported as the intersection of the layers' <em>bounding
 * boxes</em> ({@link PdfRect#intersect(PdfRect)}). That is exact for the rectangular clips a
 * producer normally writes and an <em>over-estimate</em> for two interlocking non-rectangular ones;
 * computing the true intersection polygon is clipping geometry, i.e. rendering work, which this
 * module does not do. {@link #bboxSource()}'s {@code "intersection-of-boxes"} says so wherever the
 * box is quoted, and the shape itself is never thrown away - every layer keeps its rings.
 * </p>
 */
public final class PdfClipPath {

    /** {@code W} - "using the nonzero winding number rule" (Table 4.11, S. 235). */
    public static final String RULE_NONZERO = "nonzero";

    /** {@code W*} - "using the even-odd rule" (Table 4.11, S. 235). */
    public static final String RULE_EVEN_ODD = "even-odd";

    /** The two rules, in the order of the table. */
    public static final List<String> RULES = List.of(RULE_NONZERO, RULE_EVEN_ODD);

    /** The clip is still the page itself, no {@code W} has run. */
    public static final String SOURCE_PAGE_BOX = "page-box";

    /** The box came out of the rings of one {@code W}/{@code W*}. */
    public static final String SOURCE_PATH = "path";

    /**
     * Boxes a clip may accumulate before it is cut. Every {@code W} is permanent until a {@code Q}
     * undoes it, so an unbounded loop of {@code W}s in a broken stream would grow without ever being
     * read again; the number is {@code pdf.status().maxClipLayers}, and the depth a page really
     * reached is {@code walk.clipDepth}.
     */
    public static final int MAX_LAYERS = 64;

    /** Points one layer may keep - the check document's clips are hexagons of six points. */
    public static final int MAX_POINTS_PER_LAYER = 16_384;

    /** Rings one layer may keep - one ring per subpath of the clipped path. */
    public static final int MAX_RINGS_PER_LAYER = 512;

    /**
     * One {@code W}/{@code W*} as it was established.
     *
     * @param rings      the closed subpaths, in page space, in the order the path built them
     * @param rule       {@link #RULE_NONZERO} ({@code W}) or {@link #RULE_EVEN_ODD} ({@code W*})
     * @param bbox       envelope of the rings in page space, or {@code null} for a {@code W} that
     *                   clipped an empty path
     * @param bboxSource {@link #SOURCE_PATH} or {@code "control-polygon"} when the clipped path
     *                   contained curves (decision 12: control points are not the curve)
     * @param ctm        the CTM that was in effect when the operator ran - kept so that the answer
     *                   can be traced back to the operands the stream wrote
     * @param offset     byte offset of the {@code W} in its stream, the same offset
     *                   {@code pdf.operations()} shows
     */
    public record Layer(List<List<PdfPoint>> rings, String rule, PdfRect bbox, String bboxSource,
                        PdfMatrix ctm, int offset) {

        /** How many points the layer's rings hold in total. */
        public int pointCount() {
            int count = 0;
            for (List<PdfPoint> ring : rings) {
                count += ring.size();
            }
            return count;
        }

        /** Whether the layer encloses nothing (no rings at all, or rings without extent). */
        public boolean isDegenerate() {
            return bbox == null || bbox.isDegenerate();
        }
    }

    /**
     * The result of one {@code W}/{@code W*}: the clip that follows and the sentence about it.
     *
     * @param clip the new clip (unchanged when the layer did not fit the budget)
     * @param note what happened - {@code null} when it was an ordinary intersection
     */
    public record Added(PdfClipPath clip, String note) {

        /** Whether the operation reported something a caller should see. */
        public boolean hasNote() {
            return note != null;
        }
    }

    private final List<Layer> layers;
    private final PdfRect bbox;
    private final boolean established;
    private final boolean empty;
    private final boolean truncated;
    private final String source;

    private PdfClipPath(List<Layer> layers, PdfRect bbox, boolean established, boolean empty,
                        boolean truncated, String source) {
        this.layers = layers;
        this.bbox = bbox;
        this.established = established;
        this.empty = empty;
        this.truncated = truncated;
        this.source = source;
    }

    /**
     * The clip a page starts with: "The initial clipping path includes the entire page" (4.4.3,
     * S. 234) - Table 4.2, S. 210 words it as "Initial value: the boundary of the entire imageable
     * portion of the output page", which in a module that never picks a device is the page box.
     *
     * @param pageBox the page's visible box in page space, e.g. {@link
     *                org.rogmann.mcp2sdk.pdf.geo.PdfPageSpace#pageBox()}
     */
    public static PdfClipPath initial(PdfRect pageBox) {
        requirePage(pageBox.space(), "the initial clipping region");
        return new PdfClipPath(List.of(), pageBox, true, false, false, SOURCE_PAGE_BOX);
    }

    /**
     * A clip whose outer boundary nobody established - the honest starting point for walking a
     * stream whose page geometry was not read (a slice of a stream, a Type 3 glyph, a form seen on
     * its own). Not the same claim as {@link #initial(PdfRect)}: "we do not know the page's edge"
     * is not "the page's edge is everywhere", which is why {@link #isEstablished()} exists and
     * {@link #bbox()} stays {@code null}.
     */
    public static PdfClipPath unknown() {
        return new PdfClipPath(List.of(), null, false, false, false, "unknown");
    }

    // ===================================================================
    // W / W*
    // ===================================================================

    /**
     * Applies one clipping operator.
     *
     * @param rings      the rings of the path that was just built, <b>already in page space</b> -
     *                   mapping them is the caller's job because it needs the CTM of that moment
     *                   and the page frame; empty when the path was empty, which clips everything
     *                   away ("The closed subpaths of this path define the area that can be
     *                   painted", S. 234 - no subpaths, no area)
     * @param rule       {@link #RULE_NONZERO} for {@code W}, {@link #RULE_EVEN_ODD} for {@code W*}
     * @param bboxSource where the rings' box comes from ({@link #SOURCE_PATH} or
     *                   {@code "control-polygon"})
     * @param ctm        the CTM in effect at the operator
     * @param offset     byte offset of the operator
     * @return the new clip and the note that goes with it
     */
    public Added intersectWith(List<List<PdfPoint>> rings, String rule, String bboxSource,
                              PdfMatrix ctm, int offset) {
        requireRule(rule);
        if (empty) {
            return new Added(this, "the clipping path was already empty, so this W"
                    + ("even-odd".equals(rule) ? "*" : "") + " cannot make it paint anything again"
                    + " (4.4.3, S. 235: there is no way to enlarge the current clipping path)");
        }
        if (layers.size() >= MAX_LAYERS) {
            return new Added(withTruncated(), "the clipping path was not extended further after "
                    + MAX_LAYERS + " layers - the W at offset " + offset + " was read but not"
                    + " applied (every W is permanent until a Q undoes it, so a runaway loop has to"
                    + " stop somewhere)");
        }
        List<List<PdfPoint>> kept = rings == null ? List.of() : rings;
        String cutNote = null;
        boolean cut = false;
        if (kept.size() > MAX_RINGS_PER_LAYER) {
            cutNote = "the clip at offset " + offset + " keeps " + MAX_RINGS_PER_LAYER + " of its "
                    + kept.size() + " rings - the rest were read but not stored";
            kept = kept.subList(0, MAX_RINGS_PER_LAYER);
            cut = true;
        }
        List<List<PdfPoint>> points = new ArrayList<>();
        int total = 0;
        for (List<PdfPoint> ring : kept) {
            int room = MAX_POINTS_PER_LAYER - total;
            if (room <= 0) {
                cut = true;
                continue;
            }
            if (ring.size() > room) {
                points.add(List.copyOf(ring.subList(0, room)));
                total += room;
                cut = true;
            } else {
                points.add(copyRing(ring));
                total += ring.size();
            }
        }
        if (cut && cutNote == null) {
            cutNote = "the clip at offset " + offset + " keeps " + total + " of its points - the"
                    + " limit of one layer is " + MAX_POINTS_PER_LAYER;
        } else if (cut) {
            cutNote = cutNote + ", and its points were cut at " + MAX_POINTS_PER_LAYER;
        }
        PdfRect layerBox = envelope(points);
        PdfRect merged = mergeBox(bbox, layerBox, established);
        // Empty either way: a W over a path with no closed subpaths defines no area, and an
        // intersection that no longer overlaps what was already clipped defines none either.
        boolean becameEmpty = layerBox == null || (established && merged == null);
        boolean nowEstablished = established || layerBox != null;
        boolean nowTruncated = truncated || cut;
        PdfClipPath next = new PdfClipPath(concat(new Layer(points, rule, layerBox, bboxSource,
                ctm, offset)), merged, nowEstablished, becameEmpty, nowTruncated, SOURCE_PATH);
        String note = cutNote;
        if (next.empty) {
            note = join(note, "the clip at offset " + offset + " encloses nothing - a W over an"
                    + " empty path, or an intersection that no longer overlaps the clip in effect,"
                    + " leaves no area to paint in (4.4.3, S. 234: the closed subpaths define the"
                    + " area that can be painted)");
        } else if (!established && layerBox != null) {
            note = join(note, "the clip at offset " + offset + " is the first region known for this"
                    + " stream - the page's own edge was never established, so nothing above it is"
                    + " claimed");
        }
        return new Added(next, note);
    }

    /**
     * Narrows the clip by a rectangle in page space - what a {@code /BBox} of a form XObject does to
     * what it may paint (4.9.3: a form is clipped to its bounding box). Same rule as {@code W}: the
     * result can only be smaller, and a box that misses the current clip empties it.
     *
     * @param box  the box, in page space
     * @param what what to name in the note ("form XObject /Fm3")
     * @return the new clip and its note
     */
    public Added clipToBox(PdfRect box, String what) {
        requirePage(box.space(), "a clipping box");
        if (empty) {
            return new Added(this, null);
        }
        PdfRect narrowed = established && bbox != null ? bbox.intersect(box) : box;
        if (narrowed == null) {
            return new Added(new PdfClipPath(layers, null, established, true, truncated, source),
                    "the " + what + " bounds lie outside the current clipping path - nothing of it"
                            + " can reach the page");
        }
        return new Added(new PdfClipPath(layers, narrowed, true, false, truncated, source),
                "the " + what + " bounds narrowed the clipping path to " + narrowed);
    }

    // ===================================================================
    // Reading it
    // ===================================================================

    /** The layers, oldest first - one per {@code W}/{@code W*} that got through. */
    public List<Layer> layers() {
        return layers;
    }

    /** How many clipping operators built this clip; 0 means the page is still clipped to itself. */
    public int depth() {
        return layers.size();
    }

    /**
     * The region in page space, as far as it is known - the intersection of every layer's box, so
     * the answer can only have shrunk since the last {@code W}. {@code null} when nothing is known
     * (a clip built from {@link #unknown()} that no {@code W} has filled) or when the clip is
     * {@link #isEmpty()}.
     */
    public PdfRect bbox() {
        return empty ? null : bbox;
    }

    /** {@link #SOURCE_PAGE_BOX}, {@link #SOURCE_PATH}, {@code "unknown"} - how far this box is trusted. */
    public String bboxSource() {
        if (!layers.isEmpty() && layers.size() > 1) {
            return "intersection-of-boxes";
        }
        return source;
    }

    /** The fill rule of the newest layer, or {@code null} when no {@code W} has run. */
    public String rule() {
        return layers.isEmpty() ? null : layers.get(layers.size() - 1).rule();
    }

    /** Whether the page's own edge was established (a page box was known). */
    public boolean isEstablished() {
        return established;
    }

    /**
     * Whether the clip leaves no area at all. Two ways to get here: a {@code W} over an empty path,
     * or a narrowing whose boxes do not overlap. Either way, "what paints here" is answered with
     * <em>nothing</em>, and nothing afterwards can change that (S. 235).
     */
    public boolean isEmpty() {
        return empty;
    }

    /** Whether no {@code W} has run yet - the clip is the page itself. */
    public boolean isUnclipped() {
        return layers.isEmpty() && !empty;
    }

    /** Whether a budget cut this clip. */
    public boolean isTruncated() {
        return truncated;
    }

    /**
     * Whether the region may reach into {@code rect} - a bounding-box test, so {@code false} proves
     * the region is elsewhere and {@code true} proves nothing. An unknown box answers {@code true}:
     * a filter must not drop what the module cannot locate.
     */
    public boolean mayContain(PdfRect rect) {
        requirePage(rect.space(), "testing a rectangle against the clip");
        PdfRect box = bbox();
        return box == null || box.intersect(rect) != null;
    }

    /** A one-line summary for a listing or a note. */
    public String describe() {
        if (empty) {
            return "clip: empty - nothing can be painted inside it";
        }
        StringBuilder sb = new StringBuilder("clip: depth ").append(layers.size());
        if (bbox != null) {
            sb.append(", ").append(bbox).append(" (").append(bboxSource()).append(')');
        } else {
            sb.append(", region unknown");
        }
        if (!layers.isEmpty()) {
            sb.append(", rule ").append(rule()).append(" of the innermost W");
        }
        if (truncated) {
            sb.append(", cut by budget");
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return describe();
    }

    // ===================================================================
    // Helpers
    // ===================================================================

    /** A rule the module accepts - {@code W} and {@code W*} are the only two (Table 4.11, S. 235). */
    public static String requireRule(String rule) {
        if (rule == null || !RULES.contains(rule)) {
            throw new PdfException("Unknown clipping rule '" + rule + "' - 4.4.3, Table 4.11"
                    + " (S. 235) knows two: W uses " + RULE_NONZERO + ", W* uses " + RULE_EVEN_ODD);
        }
        return rule;
    }

    /** The rule an operator wrote: {@code W} → nonzero, {@code W*} → even-odd. */
    public static String ruleOf(String operator) {
        return switch (operator == null ? "" : operator) {
            case "W" -> RULE_NONZERO;
            case "W*" -> RULE_EVEN_ODD;
            default -> throw new PdfException("'" + operator + "' is not a clipping operator - W and"
                    + " W* are the two of Table 4.11 (S. 235)");
        };
    }

    private PdfClipPath withTruncated() {
        return truncated ? this : new PdfClipPath(layers, bbox, established, empty, true, source);
    }

    private List<Layer> concat(Layer layer) {
        List<Layer> next = new ArrayList<>(layers);
        next.add(layer);
        return List.copyOf(next);
    }

    /** The intersection of two boxes in page space; {@code null} means they miss, which empties the clip. */
    private static PdfRect mergeBox(PdfRect current, PdfRect added, boolean established) {
        if (added == null) {
            return null;
        }
        if (!established || current == null) {
            return added;
        }
        return current.intersect(added);
    }

    private static PdfRect envelope(List<List<PdfPoint>> rings) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        boolean any = false;
        for (List<PdfPoint> ring : rings) {
            for (PdfPoint point : ring) {
                any = true;
                minX = Math.min(minX, point.x());
                minY = Math.min(minY, point.y());
                maxX = Math.max(maxX, point.x());
                maxY = Math.max(maxY, point.y());
            }
        }
        return any ? PdfRect.of(minX, minY, maxX, maxY, PdfSpace.PAGE) : null;
    }

    private static List<PdfPoint> copyRing(List<PdfPoint> ring) {
        for (PdfPoint point : ring) {
            requirePage(point.space(), "a clipping ring");
        }
        return List.copyOf(ring);
    }

    private static void requirePage(String space, String what) {
        if (!PdfSpace.PAGE.equals(space)) {
            throw new PdfException("A clipping " + what + " is stored in page space so that no later"
                    + " cm can move it (4.4.3, S. 234/235), this one is labelled '" + space
                    + "' - map it with the CTM and the page frame first");
        }
    }

    private static String join(String first, String second) {
        return first == null ? second : first + "; " + second;
    }
}
