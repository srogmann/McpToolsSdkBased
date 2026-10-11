package org.rogmann.mcp2sdk.pdf.ops;

import org.rogmann.mcp2sdk.pdf.PdfException;
import org.rogmann.mcp2sdk.pdf.PdfFontScope;
import org.rogmann.mcp2sdk.pdf.PdfName;
import org.rogmann.mcp2sdk.pdf.PdfResources;
import org.rogmann.mcp2sdk.pdf.PdfString;
import org.rogmann.mcp2sdk.pdf.geo.PdfMatrix;
import org.rogmann.mcp2sdk.pdf.geo.PdfPageSpace;
import org.rogmann.mcp2sdk.pdf.geo.PdfPoint;
import org.rogmann.mcp2sdk.pdf.geo.PdfQuad;
import org.rogmann.mcp2sdk.pdf.geo.PdfRect;
import org.rogmann.mcp2sdk.pdf.geo.PdfSpace;
import org.rogmann.mcp2sdk.pdf.state.PdfClipPath;
import org.rogmann.mcp2sdk.pdf.state.PdfGraphicsState;
import org.rogmann.mcp2sdk.pdf.state.PdfPath;
import org.rogmann.mcp2sdk.pdf.state.PdfTextState;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;

/**
 * The fifth sink: where a page's text, paths, clips, external objects and images stand —
 * {@code pdf.placement()} (increments D5 and D6 of docs/js/pdf-content-plan.md).
 *
 * <h2>The two matrices it must not mix up</h2>
 *
 * <ul>
 *   <li><b>A position comes out of {@code T_rm}</b> — {@code [T_fs·T_h 0 0; 0 T_fs 0; 0 T_rise 1] ×
 *       T_m × CTM} (5.3.3, S. 409), because that is the matrix the specification defines as mapping
 *       <em>glyph space</em> outwards: the point a glyph starts from is {@code T_rm} applied to
 *       {@code (0, 0)}. The rise is inside it, which is why a {@code Ts} moves the answer.</li>
 *   <li><b>A distance comes out of {@code T_m × CTM}</b>, never through {@code T_rm}: the
 *       displacement of S. 410 already contains {@code T_fs}, and {@code T_rm} contains it again.
 *       Doubled, a width of 500 at 10&nbsp;pt reads as 50&nbsp;pt instead of 5.</li>
 * </ul>
 *
 * Both then continue through the page's own frame ({@link PdfPageSpace#defaultUserToPage()}) when the
 * caller gave one — and when it did not, the answer says {@code space: 'user'} and stops. That is the
 * same reflex as a clip without a page frame (D3) or a glyph without a width (A5): a space nobody
 * handed in is <em>named</em>, not computed from an invented box.
 *
 * <h2>Every number names its space</h2>
 *
 * The item's {@code space} is {@code 'page'} (origin top-left, y downwards, unit one point) or
 * {@code 'user'} (the operands as they stand, the CTM applied); {@code x}, {@code y},
 * {@code advance}, {@code advanceVector}, every element and glyph advance and every {@code bbox} are
 * in that one space, and the page-space case is reached by the frame matrix in the {@code frame}
 * block plus the item's own CTM in {@code at} — so a reader can redo the product without trusting
 * this class. The label {@code user} therefore always means <em>"not page space — do not compare
 * this with a page coordinate"</em>; it is D5's name for the frameless case and D6 reuses it for the
 * other kinds so that one answer cannot mix two names for one situation.
 *
 * <h2>Which item kinds exist, and when (D6)</h2>
 *
 * {@code what} chooses: {@code 'text'} (the default, D5), {@code 'clips'}, {@code 'paths'},
 * {@code 'xobjects'}, {@code 'images'}. Unchosen kinds are still <em>read</em> — a {@code cm} or a
 * {@code W} changes the state whether or not anyone asked about it — they are only not printed, and
 * the counters in {@code walk} name them either way.
 *
 * <ul>
 *   <li><b>{@code path}</b> — one item per operator of Table 4.10, carrying the path <em>as that
 *       operator saw it</em> (4.4.1, S. 226: after it, there is none). {@code n} gets an item too,
 *       with {@code painted: false}: it ended a path and painted nothing, which is a fact.</li>
 *   <li><b>{@code clip}</b> — one item per {@code W}/{@code W*}, at <em>its own</em> offset, but
 *       emitted where its region first exists: 4.4.3, S. 234 puts the clip on the succeeding painting
 *       operator. {@code paintOffset}/{@code paintOp} name that byte, and
 *       {@code established: false} is the answer for a {@code W} whose path was never painted.</li>
 *   <li><b>{@code xobject}</b> and <b>{@code image}</b> — the named answer for a {@code Do}
 *       ({@link XObjectOps}) and the unit square of an inline image. Descending into a form happens
 *       only on {@code {includeXObjects: true}}, and its items then carry {@code depth}.</li>
 * </ul>
 *
 * <h2>What D8 added, in one paragraph each</h2>
 *
 * <p>
 * <b>The budget is spent on the answer (decision 17).</b> Every {@code addXxxItem} used to ask
 * {@link Budget#take} at its top and test the caller's {@code bbox} at its bottom, so on the plan's
 * page 21 the 48 items the caller had excluded burned the budget of the 4 items inside the band and
 * the page called itself {@code truncated}. {@code take(...)} now stands where the item is kept.
 * <b>A path can name its segments.</b> {@code {drawing: true}} prints them through
 * {@link PathBuildOps#placed} — one entry per {@code m}/{@code l}/{@code c}/{@code re}/{@code h},
 * each point mapped with the CTM of its own operator. <b>Every kind answers the state of its byte</b>
 * (decision 18): {@code state} is {@code 'key'}, {@code 'full'} or {@code 'colour'}, and the
 * colour parameters are the structured {@link org.rogmann.mcp2sdk.pdf.state.PdfColour} — numbers,
 * the named space, the byte — never a colour this module believes it understands (decision 16).
 * </p>
 *
 * <h2>What D9a added: a position per code</h2>
 *
 * <p>
 * {@code {glyphs: true}} already answered the width of every code and the displacement it caused;
 * what it did not answer was <em>where</em> the code stands. Since D9a an entry carries {@code x}/{@code y}
 * (its foot, in the space the item names), {@code piece} (which element of {@code elements} it is
 * part of) and {@code kernBefore} (the {@code TJ} number written in front of it). The occasion was a
 * measurement, not an API wish: the plan's bit-rulers came back as {@code 0 62 02 42 8 1} because a
 * reader had grouped by showing operator, and in this corpus a string is not where text hangs
 * together — {@code (01)} spans 72 pt, a {@code Td} lands a digit 2.7 pt beside it, and a {@code TJ}
 * number pulls two digits to 0.02 pt. Positions are the only answer that survives all three, and
 * they are computed from the very per-code displacements this sink already prints, from the item's
 * own origin, so a position and an advance cannot drift apart.
 * </p>
 *
 * <h2>What it never claims</h2> *
 * No text box. A text item's rectangle needs the glyph outlines, and reading {@code glyf} boxes per
 * code is a different piece of work (B3 does it for {@code pdf.glyphMetrics()} per queried code). D5
 * reports the pen position, the pen's move and where the widths came from, and says
 * {@code bboxSource: 'advance-only'} — the extent the item has is its advance — or
 * {@code 'none'} where not even that is computable. A path's box is the envelope of its points and is
 * labelled {@code 'control-polygon'} as soon as a curve is in it: flattening a Bézier is rendering,
 * which this module does not do (decision 12). Nothing is painted, and nothing is measured that the
 * file does not state.
 */
public final class PlacementSink implements PdfContentSink {

    /**
     * Items one stream contributes by default — {@code pdf.status().maxItems}, the default of
     * {@code pdf.placement()}'s {@code maxItems} option.
     */
    public static final int DEFAULT_MAX_ITEMS = 1000;

    /**
     * How many digits a coordinate keeps. A placement answer is a claim about a file, and a file
     * writes two decimals at most; {@code 80.64000000000001} would advertise a precision the
     * producer never had, while a millionth of a point is far below anything a viewer draws.
     */
    public static final double ROUNDING = 1e6;

    // The five kinds an item can have. The word a caller writes in `what` is the plural of these.
    /** A showing operator: position, advance, font (D5). */
    public static final String KIND_TEXT = "text";
    /** A {@code W}/{@code W*}: a clipping region in page space (D6). */
    public static final String KIND_CLIP = "clip";
    /** An operator of Table 4.10: the path it ended (D6). */
    public static final String KIND_PATH = "path";
    /** A {@code Do} naming a form or a PostScript XObject: named, not entered (D6). */
    public static final String KIND_XOBJECT = "xobject";
    /** An image: inline ({@code BI…EI}) or an {@code /Image} XObject (D6). */
    public static final String KIND_IMAGE = "image";

    /** The kinds, in the order the plan lists them. */
    public static final List<String> KINDS =
            List.of(KIND_TEXT, KIND_CLIP, KIND_PATH, KIND_XOBJECT, KIND_IMAGE);

    // ---------------------------------------------------------------------------------------------
    // the three answers to "what was the state here" (option `state`)
    // ---------------------------------------------------------------------------------------------

    /** The default: the item's own {@code at} block (CTM, text matrix, clip depth) and nothing more. */
    public static final String STATE_KEY = "key";

    /** Every graphics-state parameter that differs from its initial value. */
    public static final String STATE_FULL = "full";

    /**
     * Only the four colour parameters of Table 4.2 (S. 210/211) — {@code state: 'colour'}, increment
     * D8c. A third value of an existing option rather than a second option: the question is the same
     * one {@code 'full'} answers, asked smaller, and two options answering one question is the shape
     * of mistake decision 10 refuses.
     */
    public static final String STATE_COLOUR = "colour";

    /** The values {@code state} accepts, in the order the help prints them. */
    public static final List<String> STATE_VALUES = List.of(STATE_KEY, STATE_FULL, STATE_COLOUR);

    /**
     * Reads a caller's {@code state} option. Case-insensitive, unknown on purpose, with the accepted
     * words in the message — an answer that quietly fell back to {@code 'key'} because someone wrote
     * {@code colors} would read as "this page has no colour".
     *
     * @param value what the caller wrote, or null for the default
     * @return one of {@link #STATE_VALUES}
     * @throws PdfException when the word names no state view
     */
    public static String stateOption(String value) {
        if (value == null || value.isBlank()) {
            return STATE_KEY;
        }
        String word = value.trim().toLowerCase(java.util.Locale.ROOT);
        for (String known : STATE_VALUES) {
            if (known.equals(word)) {
                return known;
            }
        }
        throw new PdfException("pdf.placement: option 'state' reads '" + STATE_KEY + "' (the"
                + " default: the item's own state in at), '" + STATE_FULL + "' (the whole graphics"
                + " state at the operator) or '" + STATE_COLOUR + "' (only the four colour"
                + " parameters of Table 4.2), not '" + value + "'");
    }

    /**
     * What {@code what} accepts, as ask -&gt; kind. Each ask is the plural of a kind, so a caller who
     * writes {@code clips} reads items of {@code kind: 'clip'}. {@code 'marks'} is deliberately
     * absent: a marked-content span is a structure question, and {@code pdf.markedContent()} is the
     * function that answers it (with the MCID a {@code BDC}'s operand alone does not carry).
     */
    public static final Map<String, String> WHAT_NAMES = whatNames();

    private static Map<String, String> whatNames() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("text", KIND_TEXT);
        map.put("clips", KIND_CLIP);
        map.put("paths", KIND_PATH);
        map.put("xobjects", KIND_XOBJECT);
        map.put("images", KIND_IMAGE);
        return java.util.Collections.unmodifiableMap(map);
    }

    /**
     * Translates a caller's {@code what} into kinds. Names are case-insensitive and the singular is
     * accepted beside the plural the table prints, because {@code 'clip'} and {@code 'clips'} cannot
     * mean two different things. An unknown name is a call error naming what exists — an answer that
     * quietly left a kind out would read as "this page has none of those", which is the failure this
     * whole option exists to prevent.
     *
     * @param what the words the caller wrote, or null for the default
     * @return the kinds to collect
     * @throws PdfException when a word names no kind
     */
    public static Set<String> kindSet(List<String> what) {
        if (what == null || what.isEmpty()) {
            return Set.of(KIND_TEXT);
        }
        Set<String> kinds = new java.util.LinkedHashSet<>();
        for (String asked : what) {
            String word = asked == null ? "" : asked.trim().toLowerCase(java.util.Locale.ROOT);
            if ("all".equals(word)) {
                kinds.addAll(KINDS);
                continue;
            }
            String kind = WHAT_NAMES.get(word);
            if (kind == null) {
                // The singular as a courtesy: `what: ['clip']` cannot mean anything else.
                kind = WHAT_NAMES.get(word + "s");
            }
            if (kind == null) {
                if ("mark".equals(word) || "marks".equals(word)) {
                    throw new PdfException("pdf.placement: what: ['" + asked + "'] is not a kind this"
                            + " function collects - marked-content spans are the question"
                            + " pdf.markedContent() answers (it also knows the /ParentTree, which a"
                            + " BDC operand alone does not); placement collects "
                            + String.join(", ", WHAT_NAMES.keySet()) + " and 'all'");
                }
                throw new PdfException("pdf.placement: what: ['" + asked + "'] names no kind -"
                        + " the words are " + String.join(", ", WHAT_NAMES.keySet())
                        + " (each one the plural of the kind an item carries) plus 'all'");
            }
            kinds.add(kind);
        }
        return Set.copyOf(kinds);
    }

    /**
     * Points one item's {@code polygon} lists. A clip in the check document is a hexagon, a runaway
     * loop is a million points, and an answer is not a polygon dump — so the count of what was
     * dropped is reported next to what was kept ("truncated alone is no statement", plan D6).
     */
    public static final int MAX_POLYGON_POINTS = 256;

    /** Points all items of one page read may list together (the same argument, across items). */
    public static final int MAX_POLYGON_POINTS_PER_STREAM = 8192;

    /**
     * The default colour spaces of 4.5.4 (S. 257/258) — resource names in a page's {@code ColorSpace}
     * subdictionary that sit under every device space the operators name. They are looked up by name
     * (no {@code /DefaultRGB} in a corpus costs nothing) and only ever <em>named</em>: the array
     * behind them is a colour space, and colour spaces are not interpreted here (decision 11).
     */
    public static final List<String> DEFAULT_COLOUR_SPACES =
            List.of("DefaultGray", "DefaultRGB", "DefaultCMYK");

    /** What the sink should collect and in which space. */
    public record Options(PdfPageSpace pageSpace, PdfFontScope scope, String stateMode,
                          boolean glyphs, PdfRect bboxFilter, Double dpi, int maxItems,
                          org.rogmann.mcp2sdk.pdf.ops.PdfTextWidths widths, Set<String> kinds,
                          boolean includeXObjects, XObjectOps.Source xobjectSource,
                          PdfResources resources, boolean drawing) {

        /** Text and positions of one page, nothing filtered, the plain {@code at} block. */
        public static Options of(PdfPageSpace pageSpace, PdfFontScope scope) {
            return new Options(pageSpace, scope, STATE_KEY, false, null, null, DEFAULT_MAX_ITEMS,
                    null, Set.of(KIND_TEXT), false, null, null, false);
        }

        /** A walk whose answers are wanted in user space (no page frame on the table). */
        public static Options withoutPage(PdfFontScope scope) {
            return new Options(null, scope, STATE_KEY, false, null, null, DEFAULT_MAX_ITEMS, null,
                    Set.of(KIND_TEXT), false, null, null, false);
        }

        /**
         * The width source the walk has to be given. It belongs to the sink's options rather than to
         * the caller's walk options because the sink cannot answer a question the walk did not ask:
         * without a width source {@code T_m} never moved and {@link TextShowOps.Advance} stays null,
         * and a {@code glyphs: true} answer would then be missing its per-code list. One place knows
         * both choices, so the two cannot drift apart.
         */
        public Options withWidths(org.rogmann.mcp2sdk.pdf.ops.PdfTextWidths value) {
            return copy(b -> b.widths = value);
        }

        /**
         * Which view of the graphics state an item carries: {@code 'key'} (only its own {@code at}
         * block), {@code 'full'} (the whole state) or {@code 'colour'} (the four colour parameters).
         * Read through {@link #stateOption(String)} before it gets here.
         */
        public Options withState(String value) {
            return copy(b -> b.stateMode = value == null ? STATE_KEY : value);
        }

        /** The same choice as the D5-D7 switch: true is {@code 'full'}, false is {@code 'key'}. */
        public Options withFullState(boolean value) {
            return copy(b -> b.stateMode = value ? STATE_FULL : STATE_KEY);
        }

        /**
         * {@code {drawing: true}}: a path item also answers its segments, in the space the item
         * reports in (increment D8b). Off by default — the answer of every other reader stays the
         * size it was.
         */
        public Options withDrawing(boolean value) {
            return copy(b -> b.drawing = value);
        }

        /** {@code {glyphs: true}}: every code of the operand, listed with its width. */
        public Options withGlyphs(boolean value) {
            return copy(b -> b.glyphs = value);
        }

        /** A filter box in <b>page space</b>, applied inside the walk (not after it). */
        public Options withBbox(PdfRect value) {
            return copy(b -> b.bboxFilter = value);
        }

        /** The caller's resolution, which only buys {@code frame.deviceScale}. */
        public Options withDpi(Double value) {
            return copy(b -> b.dpi = value);
        }

        /** How many items one stream may contribute (0 = {@link #DEFAULT_MAX_ITEMS}). */
        public Options withMaxItems(int value) {
            return copy(b -> b.maxItems = value);
        }

        /** Which kinds to collect (a subset of {@link #KINDS}); null or empty means text only. */
        public Options withKinds(Set<String> value) {
            return copy(b -> b.kinds = value == null || value.isEmpty()
                    ? Set.of(KIND_TEXT) : Set.copyOf(value));
        }

        /** {@code {includeXObjects: true}}: walk into the a form a {@code Do} names. */
        public Options withXObjectDescent(boolean value) {
            return copy(b -> b.includeXObjects = value);
        }

        /** Where a descent reads a form's bytes and resources from (null: no descent possible). */
        public Options withXObjectSource(XObjectOps.Source value) {
            return copy(b -> b.xobjectSource = value);
        }

        /**
         * The resources of the stream being walked — what a {@code Do}'s name resolves against. The
         * walk is given the same dictionary ({@code PdfContentInterpreter.Options.withResources});
         * the sink needs it too because resolving a name is not a state transition.
         */
        public Options withResources(PdfResources value) {
            return copy(b -> b.resources = value);
        }

        /** The budget actually used. */
        public int maxItemsOrDefault() {
            return maxItems > 0 ? maxItems : DEFAULT_MAX_ITEMS;
        }

        /** The view of the graphics state an item carries (one of {@link #STATE_VALUES}). */
        public String stateMode() {
            return stateMode == null ? STATE_KEY : stateMode;
        }

        /** {@code {state: 'full'}} — every parameter that differs from its initial value. */
        public boolean fullState() {
            return STATE_FULL.equals(stateMode());
        }

        /** {@code {state: 'colour'}} — the four colour parameters of Table 4.2, nothing else. */
        public boolean colourState() {
            return STATE_COLOUR.equals(stateMode());
        }

        /** Whether an item carries a {@code state} block at all (anything but {@code 'key'}). */
        public boolean wantsState() {
            return !STATE_KEY.equals(stateMode());
        }

        /** {@code {drawing: true}} — a path item also answers its segments (D8b). */
        public boolean drawing() {
            return drawing;
        }

        /** Whether a kind is asked for. */
        public boolean wants(String kind) {
            return kinds != null && kinds.contains(kind);
        }

        /**
         * Whether any of the four area kinds is collected. The {@code bbox} filter's rule depends on
         * it: a text item is asked for its origin (D5), an area item for the box it covers — a path
         * that runs through the corner of the filter has no position to test, only an extent.
         */
        public boolean collectsAreas() {
            return wants(KIND_CLIP) || wants(KIND_PATH) || wants(KIND_XOBJECT) || wants(KIND_IMAGE);
        }

        /**
         * The walk this sink needs, derived from what it was asked to show: the page frame it puts
         * its coordinates into, the widths it displaces with and the code list it prints.
         *
         * @param base the options the caller already chose (resources, a state to start from, …)
         * @return the options to run this sink with
         */
        public PdfContentInterpreter.Options walkOptions(PdfContentInterpreter.Options base) {
            PdfContentInterpreter.Options out = (base == null
                    ? PdfContentInterpreter.Options.defaults() : base)
                    .withPageSpace(pageSpace)
                    .withGlyphDetails(glyphs);
            return widths == null ? out : out.withTextWidths(widths);
        }

        /**
         * Every {@code with…} goes through here. One builder, one place that knows the order of the
         * eleven components: D2's lesson was a setter that forwarded the <em>old</em> field instead
         * of its parameter — invisible to a compiler — so the copying is written once, and
         * {@code PdfPlacementSinkTest.everyOptionSetterChangesExactlyItsOwnField} holds it to that.
         */
        private Options copy(java.util.function.Consumer<Builder> change) {
            Builder b = new Builder();
            b.pageSpace = pageSpace;
            b.scope = scope;
            b.stateMode = stateMode;
            b.glyphs = glyphs;
            b.bboxFilter = bboxFilter;
            b.dpi = dpi;
            b.maxItems = maxItems;
            b.widths = widths;
            b.kinds = kinds;
            b.includeXObjects = includeXObjects;
            b.xobjectSource = xobjectSource;
            b.resources = resources;
            b.drawing = drawing;
            change.accept(b);
            return new Options(b.pageSpace, b.scope, b.stateMode, b.glyphs, b.bboxFilter, b.dpi,
                    b.maxItems, b.widths, b.kinds, b.includeXObjects, b.xobjectSource, b.resources,
                    b.drawing);
        }

        /** The writable mirror of {@link Options}, used only by {@link Options#copy}. */
        private static final class Builder {
            private PdfPageSpace pageSpace;
            private PdfFontScope scope;
            private String stateMode;
            private boolean glyphs;
            private PdfRect bboxFilter;
            private Double dpi;
            private int maxItems;
            private org.rogmann.mcp2sdk.pdf.ops.PdfTextWidths widths;
            private Set<String> kinds;
            private boolean includeXObjects;
            private XObjectOps.Source xobjectSource;
            private PdfResources resources;
            private boolean drawing;
        }
    }

    /**
     * The item budget of one page read, shared by the page's own stream and every form walked into
     * from it. Shared on purpose: {@code maxItems} bounds the <em>answer</em>, and a descent with a
     * budget of its own would let a file multiply the answer by nesting forms.
     *
     * <p>
     * <b>D8a (decision 17): only what lands in the answer is booked here.</b> Until D8 every
     * {@code addXxxItem} asked for its budget <em>first</em> and tested the caller's {@code bbox}
     * filter at the end, so on the plan's page 21 the 48 items the filter removed burned the budget
     * of the four items inside the band — {@code {bbox, maxItems: 50}} answered 2 of 4 and called
     * itself {@code truncated}, which is a statement about the caller's own question. The bound for
     * the <em>work</em> of a walk is {@code maxOps} (plus {@code maxPathSegments},
     * {@code maxClipLayers}, {@code maxStateDepth} and the two point budgets, which bound how much
     * of one item is listed); {@code maxItems}, {@code maxGlyphs} and {@code maxDiagnostics} bound
     * pieces of the answer, and a piece that was filtered away is not in it.
     * </p>
     */
    public static final class Budget {

        private final int limit;
        private int used;
        private long beyondBudget;
        private int polygonPoints;

        /** A budget of {@code limit} items in total. */
        public Budget(int limit) {
            this.limit = limit;
        }

        /**
         * Whether another item fits; a refusal is booked on the sink that asked. Call this at the
         * point an item is <em>kept</em> — after the caller's filter, next to {@code add(…)} — never
         * at the top of an {@code addXxxItem} method (decision 17).
         */
        public boolean take(PlacementSink sink) {
            if (used < limit) {
                used++;
                return true;
            }
            sink.markBeyondBudget();
            return false;
        }

        /**
         * How many of this item's polygon points may still be listed (out of {@code wanted}) — the
         * budget a clip's {@code polygon} and a path's {@code drawing} share. It bounds the
         * <em>listing</em> of one page read, which is why it is spent where the points are computed,
         * not where the item is kept (contrast {@link #take}: a cut here is named inside the item, a
         * cut there decides whether the item is in the answer at all).
         */
        public int takePolygonPoints(int wanted) {
            int room = Math.max(0, Math.min(MAX_POLYGON_POINTS,
                    MAX_POLYGON_POINTS_PER_STREAM - polygonPoints));
            int take = Math.min(room, wanted);
            polygonPoints += take;
            return take;
        }

        /** Items collected so far, across every stream of the read. */
        public int used() {
            return used;
        }

        /** Item-bearing operators that came after the cut. */
        public long beyondBudget() {
            return beyondBudget;
        }

        void addBeyond() {
            beyondBudget++;
        }
    }

    private final Options options;
    private final Budget budget;
    /** The forms already on this descent path — {@link XObjectOps}' cycle watch, per path. */
    private final List<Integer> entered;
    private final List<Map<String, Object>> items = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private final LinkedHashSet<String> raised = new LinkedHashSet<>();
    /** The {@code W}s this stream wrote, waiting for the painting operator S. 234 demands. */
    private final List<PendingClip> pendingClips = new ArrayList<>();
    private PdfContentInterpreter.Summary summary;
    private long filteredOut;
    private int descents;
    private int descentItems;
    /** What the streams a descent walked could not use — kept apart from the page's own walk (D7). */
    private final Findings descentFindings = new Findings();
    /**
     * Reports this sink adds to the walk's families (plan P4): counted like the walk's own
     * {@code otherReports}, capped together with the walk's sentences, and told here because the
     * sink is the one who saw the matrix change in the middle of a path.
     *
     * <p>
     * These are not defects of a file — a {@code cm} between two path operators is ordinary, legal
     * PDF. They are the places where the answer's own numbers are computed from two different
     * matrices, which a caller reading {@code at.ctm} as "the matrix of this item" would silently
     * get wrong. The walk keeps its findings to itself ({@code PdfContentInterpreterTest} pins
     * that); this list belongs to the sink and reaches the answer through
     * {@link #diagnosticsBlock()}.
     * </p>
     */
    private final List<String> matrixReports = new ArrayList<>();
    /** How many paths the {@link #matrixReports} stand for (a cap can drop sentences, never counts). */
    private long matrixReportCount;
    /** This sink's depth: 0 for the page's own stream, 1 for a form walked from it, and so on. */
    private int depth;
    /** The resource name of the form this sink is walking ({@code null} on the page). */
    private String inName;

    /** One {@code W}/{@code W*} as the stream wrote it, before anything establishes a region for it. */
    private record PendingClip(String operator, int offset, PdfGraphicsState state) {
    }

    /**
     * The findings of the streams {@code {includeXObjects: true}} walked, tallied apart from the
     * page's own walk and capped again (each stream is a walk of its own with its own sentence cap,
     * so the total list is bounded by the descent budget, not by one number). A form whose stream is
     * broken has to say so in the same answer that names the form — silently dropping a descent's
     * reports would be the "quietly leaves it out" this module refuses everywhere else.
     */
    private static final class Findings {
        private long total;
        private long operandDefects;
        private long stateUnderflows;
        private long emptyClips;
        private long pathErrors;
        private long otherReports;
        private boolean cut;
        private final List<String> entries = new ArrayList<>();

        void add(PdfContentInterpreter.Diagnostics walk, String label) {
            if (walk == null) {
                return;
            }
            total += walk.total();
            operandDefects += walk.operandDefects();
            stateUnderflows += walk.stateUnderflows();
            emptyClips += walk.emptyClips();
            pathErrors += walk.pathErrors();
            otherReports += walk.otherReports();
            cut |= walk.truncated();
            for (String entry : walk.entries()) {
                if (entries.size() >= PdfContentInterpreter.MAX_DIAGNOSTICS) {
                    return;
                }
                entries.add("in " + label + ": " + entry);
            }
        }

        boolean isEmpty() {
            return total == 0 && entries.isEmpty() && !cut;
        }

        /** The {@code descents} sub-block of {@link #diagnosticsBlock()}. */
        void putInto(Map<String, Object> out) {
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("operandDefects", operandDefects);
            block.put("stateUnderflows", stateUnderflows);
            block.put("emptyClips", emptyClips);
            block.put("pathErrors", pathErrors);
            block.put("otherReports", otherReports);
            block.put("total", total);
            if (cut) {
                block.put("walkCut", true);
            }
            if (!entries.isEmpty()) {
                block.put("entries", new ArrayList<Object>(entries));
            }
            out.put("descents", block);
        }
    }

    /** @param options what to collect, in which space, and how much of it */
    public PlacementSink(Options options) {
        this(options, new Budget((options == null ? Options.of(null, null) : options)
                .maxItemsOrDefault()), new ArrayList<>());
    }

    /** The sink that just counts (a probe). */
    public PlacementSink() {
        this(Options.of(null, null));
    }

    private PlacementSink(Options options, Budget budget, List<Integer> entered) {
        this.options = options == null ? Options.of(null, null) : options;
        this.budget = budget;
        this.entered = entered;
    }

    // =============================================================================================
    // the walk
    // =============================================================================================

    @Override
    public void onOperation(PdfContentInterpreter.Operation operation) {
        // 4.4.3, S. 234: a W does not alter the clip where it stands, it modifies the succeeding
        // painting operator. The walk establishes the region there (PdfContentInterpreter
        // .establishClips); this sink remembers the operator, because the item is the file's W and
        // has to carry the W's offset.
        if (operation.operator().setsClip()) {
            pendingClips.add(new PendingClip(operation.name(), operation.offset(),
                    operation.stateBefore()));
        }
        if (options.wants(KIND_CLIP) && operation.establishesClip()) {
            for (PdfContentInterpreter.Operation.ClipEstablished clip : operation.clips()) {
                addClipItem(operation, clip);
            }
        }
        if (operation.establishesClip()) {
            // Whatever was established here is no longer waiting; what stays in the list at the end
            // of the stream is the W whose path was never painted (see endStream).
            for (PdfContentInterpreter.Operation.ClipEstablished clip : operation.clips()) {
                pendingClips.removeIf(waiting -> waiting.offset() == clip.operatorOffset());
            }
        }
        if (options.wants(KIND_PATH) && operation.operator().endsPath()) {
            addPathItem(operation);
        }
        if (options.wants(KIND_TEXT) && operation.showsText()) {
            addTextItem(operation);
        }
        if ((options.wants(KIND_XOBJECT) || options.wants(KIND_IMAGE))
                && operation.operator().has(PdfContentOperator.Effect.RUNS_XOBJECT)) {
            addXObjectItem(operation);
        }
        if (options.wants(KIND_IMAGE) && operation.operator()
                .has(PdfContentOperator.Effect.SHOWS_INLINE_IMAGE)
                && "EI".equals(operation.name())) {
            addInlineImageItem(operation);
        }
    }

    @Override
    public void endStream(PdfContentInterpreter.Summary summary) {
        // A W whose path was never painted: the operator is in the file and the region is not
        // (S. 234 again), and the answer says so instead of crediting the stream with a clip.
        for (PendingClip pending : pendingClips) {
            if (!options.wants(KIND_CLIP)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kind", KIND_CLIP);
            item.put("offset", (long) pending.offset());
            item.put("op", pending.operator());
            item.put("space", spaceName());
            item.put("established", false);
            item.put("rule", ruleOf(pending.operator()));
            item.put("bbox", null);
            item.put("bboxSource", "none");
            Map<String, Object> at = new LinkedHashMap<>();
            at.put("op", pending.operator());
            if (spaceName().equals(PdfSpace.USER)) {
                at.put("clipComputed", false);
            }
            item.put("at", at);
            if (options.wantsState() && pending.state() != null) {
                // D8c: the state at the byte the W was written - an item of the same kind as every
                // other, asked the same way.
                item.put("state", options.colourState() ? pending.state().colourMap()
                        : pending.state().toMap());
            }
            if (!budget.take(this)) {
                continue;
            }
            noteOnce("unpainted-clip-" + pending.offset(), "a " + pending.operator()
                    + " at offset " + pending.offset() + " is reported with established: false -"
                    + " 4.4.3, S. 234 makes it modify the succeeding painting operator, and this"
                    + " stream never painted its path, so no clipping path was established");
            items.add(item);
        }
        pendingClips.clear();
        this.summary = summary;
        // summary.marks reads 0 because placement collects no mark item, not because the page has
        // none: a marked-content span's MCID is named in the page's /ParentTree and not in the BDC
        // operand, so pdf.markedContent() is the function that can answer one (it reads both sides
        // and can say which MCID no structure element claims). The walk counts the sequences either
        // way, so the zero here gets its sentence wherever a page actually opened one (D7).
        long marked = summary == null ? 0L : summary.walk().markedSequences();
        if (marked > 0) {
            noteOnce("marks-not-collected", "this stream opens " + marked
                    + " marked-content sequence(s) (BMC/BDC) and summary.marks still reads 0:"
                    + " placement collects no mark item - the MCID a span is identified by stands in"
                    + " the page's /ParentTree, not in the BDC operand, so pdf.markedContent()"
                    + " (or pdf.structTree()) is the function that answers a span and can say whether"
                    + " a structure element claims it");
        }
        defaultColourSpaces();
    }

    /**
     * One sentence when the page's {@code ColorSpace} resources carry a default colour space
     * ({@code DefaultGray}, {@code DefaultRGB}, {@code DefaultCMYK}) — said once per read, naming
     * what it can change and what it cannot (increment D8c).
     *
     * <p>
     * 4.5.4, S. 257/258: "When a device color space is selected, the ColorSpace subdictionary of the
     * current resource dictionary is checked for the presence of an entry designating a corresponding
     * default color space … If such an entry is present, its value is used as the color space for the
     * operation currently being performed." So a page's {@code rg} may be interpreted in a CIE-based
     * space the stream never names. The numbers still stand — S. 258: "Color values in the original
     * device color space are passed unchanged to the default color space" — which is why this is a
     * note and not a field: the answer's {@code space: "DeviceRGB"} is the space the operator named,
     * and reading the array behind {@code /DefaultRGB} would mean interpreting a colour space, which
     * is decision 11's refusal, not an oversight.
     * </p>
     */
    private void defaultColourSpaces() {
        if (options.resources() == null) {
            return;
        }
        for (String key : DEFAULT_COLOUR_SPACES) {
            try {
                PdfResources.Entry entry = options.resources().resolve("ColorSpace", key);
                if (!entry.missing()) {
                    noteOnce("default-colour-space-" + key, "this page's ColorSpace resources name"
                            + " " + key + " (object " + entry.objNum() + ") - 4.5.4, S. 257/258:"
                            + " a device space the operators name is used together with that entry,"
                            + " and its value may be a CIE-based space the stream never names. The"
                            + " colour numbers are unchanged by it (S. 258: \"Color values in the"
                            + " original device color space are passed unchanged\"), so placement"
                            + " keeps naming the space the operator named - the array behind " + key
                            + " is not read, because that would be interpreting a colour space");
                }
            } catch (PdfException e) {
                // A resources dictionary the resolver refuses is told where it is read; this note is
                // a bonus on top of that statement and must not fail the page.
                continue;
            }
        }
    }

    // =============================================================================================
    // the four kinds D6 adds
    // =============================================================================================

    /**
     * One {@code W}/{@code W*}, reported at its own offset with the region the painting operator
     * established for it (4.4.3, S. 234 — see the class note and
     * {@code PdfContentInterpreter#establishClips}).
     */
    private void addClipItem(PdfContentInterpreter.Operation operation,
                             PdfContentInterpreter.Operation.ClipEstablished clip) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("kind", KIND_CLIP);
        item.put("offset", (long) clip.operatorOffset());
        item.put("op", clip.operator());
        // The byte that established the region, named next to the byte that asked for one: they are
        // different operators, and only the second one is where the numbers come from.
        item.put("paintOffset", (long) operation.offset());
        item.put("paintOp", operation.name());
        item.put("space", spaceName());
        item.put("established", true);
        PdfClipPath.Layer layer = clip.layer();
        if (layer == null) {
            // Counted and named, no coordinates: the walk has no page frame (D3's rule).
            item.put("rule", ruleOf(clip.operator()));
            item.put("computed", false);
            item.put("polygon", null);
            item.put("bbox", null);
            item.put("bboxSource", "none");
        } else {
            int points = layer.pointCount();
            int kept = budget.takePolygonPoints(points);
            List<Object> polygon = new ArrayList<>(kept * 2);
            int listed = 0;
            for (List<PdfPoint> ring : layer.rings()) {
                for (PdfPoint point : ring) {
                    if (listed >= kept) {
                        break;
                    }
                    polygon.add(round(point.x()));
                    polygon.add(round(point.y()));
                    listed++;
                }
            }
            item.put("rule", layer.rule());
            item.put("depth", (long) layerIndex(operation, clip));
            item.put("subpaths", (long) layer.rings().size());
            item.put("points", (long) points);
            item.put("polygon", polygon);
            if (kept < points) {
                item.put("polygonTruncated", true);
                item.put("polygonPointsDropped", (long) (points - kept));
                noteOnce("polygon-cut", "a clip's polygon was cut at " + MAX_POLYGON_POINTS
                        + " points per item (" + MAX_POLYGON_POINTS_PER_STREAM
                        + " per page read): the full count stays in points, what fell out is named in"
                        + " polygonPointsDropped - truncated alone would be no statement");
            }
            item.put("bbox", layer.bbox() == null ? null : numbers(layer.bbox()));
            item.put("bboxSource", layer.bboxSource());
            if (clip.emptied()) {
                item.put("emptied", true);
            }
            // The region that survives this operator, next to the region the operator asked for.
            // 4.4.3, S. 235: "There is no way to enlarge the current clipping path" - so this box can
            // only ever be smaller than the one above, and a reader who sees it smaller knows the W
            // narrowed something instead of assuming the W's own polygon is what may still be painted.
            Map<String, Object> after = new LinkedHashMap<>();
            PdfRect region = operation.state().clip().bbox();
            after.put("bbox", region == null ? null : numbers(region));
            after.put("bboxSource", operation.state().clip().bboxSource());
            after.put("depth", (long) operation.state().clip().depth());
            if (operation.state().clip().isEmpty()) {
                after.put("empty", true);
            }
            item.put("clipAfter", after);
        }
        if (clip.note() != null && !clip.note().isEmpty()) {
            item.put("note", clip.note());
        }
        if (!insideFilter(null, clip.layer() == null || clip.layer().bbox() == null
                ? null : numbersOf(clip.layer().bbox()))) {
            filteredOut++;
            return;
        }
        // D8a: budgeted here, not at the top of the method - the caller's filter threw this item out
        // and an item the answer does not contain may not spend the budget of one it does.
        if (!budget.take(this)) {
            return;
        }
        add(item, operation, spaceName());
    }

    /**
     * How deep this layer sits in the clip that was in effect after it — the layer's own index plus
     * one, counted against the state the painting operator left (the layer is the last one the
     * operator added, earlier ones are below it).
     */
    private static int layerIndex(PdfContentInterpreter.Operation operation,
                                  PdfContentInterpreter.Operation.ClipEstablished clip) {
        List<PdfClipPath.Layer> layers = operation.state().clip().layers();
        for (int i = layers.size() - 1; i >= 0; i--) {
            if (layers.get(i).offset() == clip.operatorOffset()) {
                return i + 1;
            }
        }
        return layers.size();
    }

    /** One operator of Table 4.10: the path it ended, in the space the caller asked for. */
    private void addPathItem(PdfContentInterpreter.Operation operation) {
        PdfPath path = operation.path();
        String space = spaceName();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("kind", KIND_PATH);
        item.put("offset", (long) operation.offset());
        item.put("op", operation.name());
        item.put("space", space);
        item.put("subpaths", (long) path.subpaths());
        Map<String, Object> segments = new LinkedHashMap<>();
        path.counts().forEach((kind, count) -> segments.put(kind, count));
        item.put("segments", segments);
        if (options.drawing()) {
            // D8b: the numbers behind those counts, in the space this item reports in.
            putDrawing(item, path, space);
        }
        item.put("painted", operation.operator().has(PdfContentOperator.Effect.PAINTS));
        item.put("evenOdd", operation.operator().has(PdfContentOperator.Effect.EVEN_ODD));
        // The path is closed when its last subpath was closed by h (or by re's printed equivalence);
        // an open one is closed *implicitly* by a filling operator (4.5.2, S. 232) - two different
        // statements, so two different fields.
        item.put("closed", !path.isEmpty() && !path.hasOpenSubpath());
        if (path.hasOpenSubpath()) {
            item.put("implicitlyClosed",
                    operation.operator().has(PdfContentOperator.Effect.PAINTS));
        }
        if (path.currentPoint() != null && !singularRoute(path, space)) {
            PdfPoint here = mapped(path.currentPoint(), operation.ctm(), space);
            item.put("currentPoint", List.of(round(here.x()), round(here.y())));
        } else {
            item.put("currentPoint", null);
        }
        PdfRect box = boxOf(path, operation.ctm(), space);
        item.put("bbox", box == null ? null : numbers(box));
        item.put("bboxSource", box == null ? "none"
                : ("control-polygon".equals(path.bboxSource()) ? "control-polygon" : "path-points"));
        if (box == null && singularRoute(path, space)) {
            // Decision 9: a matrix without an inverse gets a sentence, not a coordinate.
            item.put("singularNote", singularRouteOf(path, space).singularNote());
        }
        // A line width is a distance, not a point (4.3.2, S. 215): it travels the linear part only,
        // and a CTM that scales the two axes differently makes it a direction, not a number.
        item.put("lineWidth", round(operation.stateBefore().lineWidth()));
        if (space.equals(PdfSpace.PAGE) && !singularRoute(path, space)) {
            double[] span = operation.stateBefore().widthSpanIn(widthRoute(operation.ctm()));
            if (span != null && Math.abs(span[0] - span[1]) > 1e-9) {
                item.put("lineWidthSpan", List.of(round(span[0]), round(span[1])));
            }
        }
        if (!path.isCtmStable()) {
            item.put("ctmChangedMidPath", true);
        }
        if (path.isTruncated()) {
            item.put("pathTruncated", true);
        }
        if (!insideFilter(null, box == null ? null : numbers(box))) {
            filteredOut++;
            return;
        }
        // D8a: the budget is spent on what the answer shows, after the caller's filter has spoken.
        if (!budget.take(this)) {
            return;
        }
        if (!path.isCtmStable()) {
            reportMidPathMatrices(operation, path);
        }
        add(item, operation, space);
    }

    /**
     * One report for a path whose segments were built under more than one matrix (plan P4).
     *
     * <p>
     * Every coordinate of the item is computed from the matrix of <em>its own</em> operator —
     * {@link PathBuildOps#placed} maps segment by segment — while {@code at.ctm} is, and stays, the
     * matrix at the operator that ended the path. That is the right pair of answers and the one the
     * field names have always carried, but it is also the one pair of this answer a reader can
     * combine wrongly: reconstruct the points from {@code at.ctm} and every earlier point moves by
     * however far the two matrices differ, with nothing in the page to say so. {@code ctmChangedMidPath}
     * warns whoever looks for it; a report in {@code diagnostics} is what makes the same reader who
     * does not look find it.
     * </p>
     */
    private void reportMidPathMatrices(PdfContentInterpreter.Operation operation, PdfPath path) {
        matrixReportCount++;
        if (matrixReports.size() >= PdfContentInterpreter.MAX_DIAGNOSTICS) {
            return;
        }
        matrixReports.add("offset " + operation.offset() + ": the path ended here ran under "
                + distinctMatrices(path) + " CTMs - at.ctm names the one at the painting operator,"
                + " while drawing, bbox and currentPoint carry each segment's own; recomputing the"
                + " points from at.ctm alone would move the earlier ones (ctmChangedMidPath marks"
                + " the item)");
    }

    /** How many different matrices the segments of a path were built under, in the order they ran. */
    private static int distinctMatrices(PdfPath path) {
        int runs = 0;
        PdfMatrix previous = null;
        for (PdfPath.Segment segment : path.segments()) {
            PdfMatrix here = segment.ctm();
            if (runs == 0 || (here == null ? previous != null : !here.equals(previous))) {
                runs++;
            }
            previous = here;
        }
        return runs;
    }

    /**
     * Puts the item away: {@code depth}/{@code in} first (what the item <em>is</em>), then the
     * {@code at} block (where it came from) and, on request, the state that was in effect at that
     * byte. On the page's own stream neither depth field appears, so the D5 answer keeps its exact
     * field order.
     *
     * <p>
     * The state is put here rather than in each {@code addXxxItem} because D8 decided it belongs to
     * <em>every</em> kind (decision 18): "womit ist hier gemalt worden" is answered by the fill colour
     * of a painted box, not by a {@code Tf}, and a caller that has to rebuild the state chain itself
     * is back in the five-walker world decision 2 closed.
     * </p>
     */
    private void add(Map<String, Object> item, PdfContentInterpreter.Operation operation,
                     PdfTextState text, String space) {
        if (depth > 0 && !item.containsKey("depth")) {
            item.put("depth", (long) depth);
            item.put("in", inName);
        }
        item.put("at", atBlock(operation, text, space));
        putState(item, operation);
        items.add(item);
    }

    /** The same, for an item that is not a text item (no text matrix to show). */
    private void add(Map<String, Object> item, PdfContentInterpreter.Operation operation,
                     String space) {
        add(item, operation, null, space);
    }

    /**
     * The {@code state} block: {@code 'full'} shows every parameter that differs from its initial
     * value, {@code 'colour'} only the four colour parameters of Table 4.2 (S. 210/211). Asked at
     * {@code stateBefore()} — the state <em>the operator saw</em>, the same one every other number of
     * the item comes out of.
     */
    private void putState(Map<String, Object> item, PdfContentInterpreter.Operation operation) {
        if (!options.wantsState() || operation == null) {
            return;
        }
        item.put("state", options.colourState() ? operation.stateBefore().colourMap()
                : operation.stateBefore().toMap());
    }

    /** One {@code Do}, resolved through the stream's resources — named, and entered on request. */
    private void addXObjectItem(PdfContentInterpreter.Operation operation) {
        String name = XObjectOps.nameOf(operation.operands());
        XObjectOps.Target target = XObjectOps.resolve(options.resources(), name, this::deref);
        // An /Image XObject is an IMAGE item, everything else an XOBJECT item - so a caller who asks
        // for one of the two kinds is not handed the other, and a page whose pictures are XObjects
        // rather than inline still answers what: ['images'].
        boolean image = target.isImage();
        if (!options.wants(image ? KIND_IMAGE : KIND_XOBJECT)) {
            return;
        }
        if (target.isForm() && options.xobjectSource() != null && target.objNum() != null) {
            // The reader of a form wants to know where the form's OWN names resolve (3.7.2, S. 153:
            // without its own /Resources it is the page, "not recommended" and invisible in a
            // listing of the form alone). A caller without a document leaves the field alone.
            target = target.withResourcesFrom(options.xobjectSource()
                    .resourcesFrom(target.objNum(), "form XObject " + target.resourceName()));
        }
        String space = spaceName();
        java.util.Optional<PdfQuad> quad = XObjectOps.bboxInSpace(target, operation.ctm(),
                pageRouteOrNull(), space);
        Map<String, Object> item = XObjectOps.item(target, quad, space, depth, inName, operation);
        if (!insideFilter(null, quad.isPresent() ? numbersOf(quad.get().bbox()) : null)) {
            filteredOut++;
            return;
        }
        if (target.note() != null) {
            noteOnce("xobject-" + target.resourceName(), target.note());
        }
        // D8a: named before the budget question, and a descent only follows an item that is in the
        // answer - a form the caller filtered out is not walked either.
        if (!budget.take(this)) {
            return;
        }
        add(item, operation, space);
        if (options.includeXObjects()) {
            descend(target, operation);
        }
    }

    /**
     * The optional descent of {@code {includeXObjects: true}}: the form's own walk, sharing this
     * sink's budget and adding {@code depth} to everything it finds.
     */
    private void descend(XObjectOps.Target target, PdfContentInterpreter.Operation operation) {
        if (!target.isForm()) {
            if (!target.missing() && target.subtype() != null) {
                noteOnce("no-descent-" + target.resourceName(), "the Do names "
                        + target.resourceName() + ", a " + target.subtype() + " XObject - only a Form"
                        + " has a content stream to walk, so the named answer is the whole answer"
                        + " here");
            }
            return;
        }
        java.util.Optional<XObjectOps.Descent> descent = XObjectOps.openDescent(target,
                operation.stateBefore(), pageRouteOrNull(), depth, entered,
                options.xobjectSource(), notes);
        if (descent.isEmpty()) {
            return;
        }
        XObjectOps.Descent opened = descent.get();
        List<Integer> path = new ArrayList<>(entered);
        if (target.objNum() != null) {
            path.add(target.objNum());
        }
        PlacementSink child = new PlacementSink(options, budget, path);
        child.depth = opened.depth();
        child.inName = target.resourceName();
        descents++;
        String scopeNote = "a form's font names are read against the page's font scope, so a"
                + " name only the form's own resources carry stays a code (mapping.stringsWithoutFont)"
                + " - the descent walks a form's geometry, it does not re-scope its fonts";
        noteOnce(scopeNote, scopeNote);
        PdfContentInterpreter.Options walkOptions = options.walkOptions(
                PdfContentInterpreter.Options.defaults()
                        .withResources(opened.resources())
                        .withInitialState(opened.state()));
        List<String> descentNotes = new ArrayList<>();
        PdfContentInterpreter.run(opened.content(), walkOptions, descentNotes, child);
        descentItems += child.items().size();
        items.addAll(child.items());          // the child already carries depth and in
        // What the form's stream could not use is told under the form's name (D7): the caller asked
        // for this descent, so a finding inside it belongs in the same answer as the form's box.
        descentFindings.add(child.summary() == null ? null : child.summary().diagnostics(),
                target.resourceName() + (target.objNum() == null ? "" : " (object "
                        + target.objNum() + ")"));
        for (String note : child.notes()) {
            noteOnce(note, note);             // the sentence is the key: what the page said once
        }
        for (String note : descentNotes) {    // must not be said again from the form's side
            noteOnce(note, note);
        }
    }

    /** One inline image: the unit square of image space (S. 203), never a pixel of it. */
    private void addInlineImageItem(PdfContentInterpreter.Operation operation) {
        String space = spaceName();
        Map<String, Object> info = imageInfo(operation);
        Map<String, Object> dict = info == null ? Map.of() : asDict(info.get("dict"));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("kind", KIND_IMAGE);
        item.put("offset", (long) operation.offset());
        item.put("op", operation.name());
        item.put("space", space);
        item.put("inline", true);
        item.put("objNum", null);
        // No `subtype` here on purpose: an inline image dictionary has no /Subtype (Table 4.42 lists
        // the keys, and BI…ID…EI is itself the marker) - printing "/Image" would be a file statement
        // the file never made. `inline: true` is the fact.
        item.put("width", numberOrNull(dict.get("W")));
        item.put("height", numberOrNull(dict.get("H")));
        item.put("colourSpace", textOf(dict.get("CS")));
        item.put("bitsPerComponent", numberOrNull(dict.get("BPC")));
        if (info != null && info.get("dataBytes") != null) {
            // The bytes the stream holds, which is what this module measured; the sample count would
            // need the colour space, /Decode and /AltDecode applied - that is decoding the image.
            item.put("dataBytes", numberOrNull(info.get("dataBytes")));
            // ... and beside it the length of the data itself, without the white-space deliverer
            // 4.10.4, S. 204 demands in front of `EI`. Still a length, never a sample count: which
            // samples those bytes hold is the decode question above (plan P5).
            item.put("sampleBytes", numberOrNull(info.get("sampleBytes")));
        }
        if (info != null && Boolean.TRUE.equals(info.get("unterminated"))) {
            item.put("unterminated", true);
        }
        java.util.Optional<PdfQuad> unit = XObjectOps.imageUnitIn(operation.ctm(), pageRouteOrNull(),
                space);
        item.put("bbox", unit.isPresent() ? numbersOf(unit.get().bbox()) : null);
        item.put("bboxSource", unit.isPresent() ? "image-space" : "none");
        if (!unit.isPresent() && operation.ctm().isSingular()) {
            item.put("singularNote", operation.ctm().singularNote());
        }
        if (!insideFilter(null, unit.isPresent() ? numbersOf(unit.get().bbox()) : null)) {
            filteredOut++;
            return;
        }
        // D8a: budgeted with what the answer keeps.
        if (!budget.take(this)) {
            return;
        }
        add(item, operation, space);
    }

    /**
     * One showing operator: the pen's position, the distance its operand moved it, and the state that
     * was in effect at that byte. This is D5's item, field for field unchanged — D6 only added the
     * kind check in front of it.
     */
    private void addTextItem(PdfContentInterpreter.Operation operation) {
        if (!operation.hasAdvance()) {
            noteOnce("no-advances", "this walk was given no width source, so a showing operator left"
                    + " T_m where it was: the positions below are the position of the first glyph of"
                    + " each text object, not of each showing operator"
                    + " (PdfContentInterpreter.Options.withTextWidths)");
        }

        // The state the glyphs were drawn with: for Tj/TJ it is the state before the operator, for
        // ' and " it is the state AFTER the T* they contain (and after the two spacings the double
        // quote sets) - the printed code of S. 407 puts the line move in front of the string, so a
        // position read from the operator's own stateBefore would stand one line too low.
        PdfTextState text = operation.advance() != null ? operation.advance().showState()
                : operation.stateBefore().textState();
        PdfMatrix ctm = operation.ctm();
        boolean pageSpace = options.pageSpace() != null && options.pageSpace().isUsable();
        PdfMatrix toPage = pageSpace ? options.pageSpace().defaultUserToPage().orElse(null) : null;
        String space = pageSpace ? PdfSpace.PAGE : PdfSpace.USER;

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("kind", "text");
        item.put("offset", (long) operation.offset());
        item.put("space", space);

        // ---- position: the glyph origin through T_rm (S. 409), rise included ----------------------
        double[] origin = null;
        try {
            PdfMatrix rendering = text.textRenderingMatrix(ctm);
            PdfMatrix full = toPage == null ? rendering : rendering.multiply(toPage);
            origin = full.mapPoint(0, 0);
            item.put("x", round(origin[0]));
            item.put("y", round(origin[1]));
        } catch (PdfException e) {
            // No T_fs, no T_rm, so no position - the sentence is the specification's own (S. 398).
            item.put("x", null);
            item.put("y", null);
            noteOnce("no-position", "a showing operator ran without a font size: " + e.getMessage());
        }

        // ---- distance: the displacement through T_m × CTM (S. 410), never through T_rm ----------
        PdfMatrix displacementRoute = text.textMatrix().multiply(ctm);
        if (toPage != null) {
            displacementRoute = displacementRoute.multiply(toPage);
        }
        TextShowOps.Advance advance = operation.advance();
        putAdvance(item, advance, displacementRoute);

        // ---- what is shown, and in which font ---------------------------------------------------
        // The text is decoded here, before the filter and the budget speak. That is deliberate
        // (D8a): the `mapping` block of the answer describes what this page read decoded, and a
        // number that changed because an item budget was reached would be a second thing the budget
        // decided. Measured on the fixtures, this is the one place where D8a moves an existing
        // field: a truncated read used to count only the codes of the items it kept.
        item.put("text", shownText(operation, text));
        Map<String, Object> font = new LinkedHashMap<>();
        if (text.fontResourceName() != null) {
            font.put("resourceName", text.fontResourceName());
            Integer objNum = options.scope() == null ? null
                    : options.scope().objNumOf(text.fontResourceName());
            if (objNum != null) {
                font.put("objNum", (long) (int) objNum);
            }
        } else if (text.fontObjNum() != null) {
            font.put("objNum", (long) (int) text.fontObjNum().intValue());
        } else {
            font.put("resourceName", null);
        }
        font.put("setBy", text.fontSetBy());
        font.put("size", text.fontSize() == null ? null : round(text.fontSize()));
        if (text.scalePercent() != PdfTextState.INITIAL_SCALE_PERCENT) {
            font.put("scalePercent", round(text.scalePercent()));
            font.put("sizeEffective", text.effectiveFontSize() == null ? null
                    : round(text.effectiveFontSize()));
        }
        String subtype = fontSubtype(text);
        if (subtype != null) {
            font.put("subtype", subtype);
        }
        item.put("font", font);

        // ---- visible or not, clipping or not ----------------------------------------------------
        item.put("visible", !text.isInvisible());
        if (text.renderingMode() != PdfTextState.MODE_FILL) {
            item.put("renderingMode", (long) text.renderingMode());
            item.put("renderingModeName", text.renderingModeName());
        }
        if (text.addsGlyphsToClip()) {
            // Table 5.3, S. 402 and the note on S. 235: text can clip. Cutting a clip here would be
            // rendering; naming it is what the module may do.
            item.put("clipping", true);
            noteOnce("text-clips", "a showing operator runs in a clipping text mode (Tr "
                    + text.renderingMode() + ", Table 5.3): the glyphs would narrow the clip; D5"
                    + " names it and leaves the clip as the path operators set it");
        }
        if (text.textKnockout() != null) {
            item.put("knockout", text.textKnockout());
        }

        // ---- the operand's parts: kerning numbers, per-code detail, per-code position ------------
        putParts(item, advance, displacementRoute, origin);

        // ---- the box the module does not claim --------------------------------------------------
        item.put("bbox", null);
        item.put("bboxSource", advance != null && advance.computable() ? "advance-only" : "none");

        // ---- filter, then the "vor Ort" block ---------------------------------------------------
        // The text rule is D5's and stays D5's: the item is asked for its ORIGIN. An area item is
        // asked for its box (see insideFilter) - a path running through the corner of the filter has
        // no position to test, only an extent.
        if (options.bboxFilter() != null && pageSpace && !insideFilter(item, null)) {
            filteredOut++;
            return;
        }
        // D8a: budgeted after the caller's filter - an item the filter removed is not in the answer
        // and may not spend the budget of the items that are (decision 17).
        if (!budget.take(this)) {
            return;
        }
        if (depth > 0) {
            // A text item inside a descended form names the form it came from before its at block,
            // exactly like the other kinds; on the page's own stream nothing is inserted here.
            item.put("depth", (long) depth);
            item.put("in", inName);
        }
        item.put("at", atBlock(operation, text, space));
        putState(item, operation);
        if (advance != null && advance.note() != null) {
            noteOnce("advance-" + advance.note().hashCode(), advance.note());
        }
        items.add(item);
    }

    // =============================================================================================
    // pieces of one item
    // =============================================================================================

    private void putAdvance(Map<String, Object> item, TextShowOps.Advance advance,
                            PdfMatrix route) {
        if (advance == null) {
            item.put("advance", null);
            item.put("advanceVector", null);
            // Nothing was measured at all - typically a show that ran with no font in effect, which
            // the walk reports in its own sentence. "none" is the honest width source here: not "the
            // font has no widths", but "no font was asked".
            item.put("widthSource", "none");
            return;
        }
        if (advance.tx() != null) {
            double[] delta = route.mapVector(advance.tx(), advance.ty() == null ? 0 : advance.ty());
            item.put("advance", round(Math.hypot(delta[0], delta[1])));
            item.put("advanceVector", List.of(round(delta[0]), round(delta[1])));
        } else {
            // No invented total: a missing width is a hole in the middle of the operand, and a sum
            // that leaves it out looks like a measured number.
            item.put("advance", null);
            item.put("advanceVector", null);
        }
        item.put("widthSource", widthSource(advance));
        if (advance.codesWithoutWidth() > 0) {
            item.put("widthsMissing", advance.codesWithoutWidth());
        }
        if (advance.codes() == 0 && advance.elements().isEmpty()) {
            item.put("emptyOperand", true);
        }
        if (advance.detailsTruncated()) {
            item.put("detailsTruncated", true);
        }
    }

    /** One label for the provenance of the widths: a single source, {@code mixed}, or {@code none}. */
    private static String widthSource(TextShowOps.Advance advance) {
        List<String> sources = advance.widthSources();
        if (sources.isEmpty()) {
            return "none";
        }
        return sources.size() == 1 ? sources.get(0) : "mixed";
    }

    private void putParts(Map<String, Object> item, TextShowOps.Advance advance, PdfMatrix route,
                          double[] origin) {
        if (advance == null) {
            return;
        }
        if (!advance.elements().isEmpty()) {
            putPieces(item, advance, route);
        }
        // The code list does not depend on the piece list being printed: until D9a a string long
        // enough to fill the code budget dropped its own piece, and the empty pieces list then made
        // the old combined guard skip the whole parts block - the `glyphs` answer gone with it,
        // which is a question answered by silence, the one thing this module does not do.
        if (options.glyphs() && !advance.glyphs().isEmpty()) {
            item.put("glyphs", glyphList(advance, route, origin));
        }
    }

    /** The operand's pieces: the kerning numbers as the file wrote them, and each gap they open. */
    private void putPieces(Map<String, Object> item, TextShowOps.Advance advance,
                           PdfMatrix route) {
        List<Object> adjustments = new ArrayList<>();
        List<Object> parts = new ArrayList<>();
        for (TextShowOps.Element element : advance.elements()) {
            if (element.adjustment()) {
                adjustments.add(element.adjustmentValue());
            }
            Map<String, Object> part = new LinkedHashMap<>();
            part.put("kind", element.adjustment() ? "adjust" : "string");
            if (element.adjustment()) {
                part.put("adjustment", element.adjustmentValue());
            } else {
                part.put("codes", element.codes());
            }
            Double dx = element.tx();
            Double dy = element.ty();
            if (dx == null) {
                part.put("advance", null);
                if (!element.computable()) {
                    part.put("note", "a code of this operand has no width in the font");
                }
            } else {
                double[] delta = route.mapVector(dx, dy == null ? 0 : dy);
                part.put("advance", round(Math.hypot(delta[0], delta[1])));
                part.put("advanceVector", List.of(round(delta[0]), round(delta[1])));
                // ... and the same number before the route, in the space the specification prints it
                // in. Both are here because they answer different questions: "how wide is the gap on
                // the page" and "what did the file's number mean", and a caller that has to multiply
                // the second one itself is a caller that will get the first one wrong.
                part.put("textUnits", List.of(round(dx), round(dy == null ? 0 : dy)));
            }
            parts.add(part);
        }
        if (!adjustments.isEmpty()) {
            // Table 5.6, S. 407: the numbers are subtracted, in thousandths. Shown as the file wrote
            // them, next to the gap each one opens, because that pair is the whole point of asking.
            item.put("adjustments", adjustments);
        }
        item.put("elements", parts);
    }

    /**
     * The per-code list of one showing operator — and since D9a the answer to "where does this code
     * stand", not only to "how wide is it".
     *
     * <p>
     * A glyph's foot is the <b>item's own origin</b> plus the pen's walk to that code, and that sum
     * travels the same route the item's {@code advance} travels: {@code T_m × CTM} (× the page frame),
     * never {@code T_rm}. S. 410 updates {@code T_m} by {@code [1 0 0 1 t_x t_y] × T_m} after every
     * element of the operand, so the pen of the n-th code is the text-space point
     * {@link TextShowOps.Glyph#penX() Σ t} — the {@code TJ} numbers of the pieces between included,
     * which is why the pen is read from the walk rather than added up here: an addition of widths
     * forgets the numbers, and a bit-ruler whose digits are pulled together by {@code TJ} then reads
     * as a word. The rise is already inside the item's origin, because {@code T_rm} adds it once, in
     * glyph space, before that sum.
     * </p>
     *
     * <p>
     * Starting from the item's own mapped point — instead of mapping each pen position on its own —
     * is what makes the first glyph's {@code x}/{@code y} <em>be</em> the item's {@code x}/{@code y}:
     * one matrix product, one rounding, no second arithmetic a reader could catch disagreeing.
     * </p>
     *
     * <p>
     * A code whose width the font did not name keeps its position (that is where the pen stood) and
     * moves nothing, which is exactly what the walk did to {@code T_m}
     * ({@link TextShowOps.Advance#movedTx()}).
     * </p>
     */
    private List<Object> glyphList(TextShowOps.Advance advance, PdfMatrix route, double[] origin) {
        List<Object> glyphs = new ArrayList<>();
        for (TextShowOps.Glyph glyph : advance.glyphs()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("i", (long) glyph.index());
            one.put("hex", glyph.hex());
            one.put("code", glyph.code());
            one.put("width", glyph.width());
            one.put("widthSource", glyph.widthSource());
            if (origin == null) {
                // No T_fs, so no T_rm and no origin - the item's own x/y are null for the same
                // reason, and a per-code position would be the one number invented around it.
                one.put("x", null);
                one.put("y", null);
            } else {
                double[] walked = route.mapVector(glyph.penX(), glyph.penY());
                one.put("x", round(origin[0] + walked[0]));
                one.put("y", round(origin[1] + walked[1]));
            }
            if (glyph.tx() == null) {
                one.put("advance", null);
            } else {
                double[] delta = route.mapVector(glyph.tx(), glyph.ty() == null ? 0 : glyph.ty());
                one.put("advance", round(Math.hypot(delta[0], delta[1])));
            }
            // Which piece of the operand this code is part of, and the number written in front of
            // it. Both are the file's structure, not a layout claim: 0 and 2 for the two strings of
            // `[(8) -2550 (8)]`, because piece 1 is the number (TextShowOps.Glyph#piece).
            one.put("piece", (long) glyph.piece());
            one.put("kernBefore", glyph.kernBefore() == null ? 0.0 : glyph.kernBefore());
            glyphs.add(one);
        }
        return glyphs;
    }

    /**
     * The item's text: every string of the operand, read through the font that was in effect at this
     * operator (the {@link PdfFontScope} the walk measures with) or by the document convention when
     * the caller asked for the raw view.
     */
    private String shownText(PdfContentInterpreter.Operation operation, PdfTextState text) {
        StringBuilder out = new StringBuilder();
        for (Object part : operandParts(operation)) {
            if (!(part instanceof PdfString string)) {
                continue;
            }
            out.append(options.scope() == null ? string.text()
                    : options.scope().show(string, text.fontResourceName()));
        }
        return out.toString();
    }

    /** The operand's strings and numbers: the string itself, or the array's entries. */
    private static List<Object> operandParts(PdfContentInterpreter.Operation operation) {
        Object last = null;
        for (Object operand : operation.operands()) {
            last = operand;
        }
        if (last instanceof List<?> array) {
            return new ArrayList<>(array);
        }
        return last == null ? List.of() : List.of(last);
    }

    private String fontSubtype(PdfTextState text) {
        if (options.scope() == null || !text.hasFont()) {
            return null;
        }
        PdfTextWidths.Font font = options.scope().fontFor(text.fontResourceName(),
                text.fontObjNum());
        return font == null ? null : font.subtype();
    }

    /**
     * Whether the item still belongs in the answer. Two rules, and the answer says which one ran
     * ({@code frame.filterRule}), because the two kinds of item are different questions:
     * <ul>
     *   <li>a <b>text</b> item is asked where its <em>origin</em> stands (D5's rule, unchanged) —</li>
     *   <li>an <b>area</b> item (clip, path, XObject, image) has no position, only an extent, so it
     *       is asked whether its box <em>reaches into</em> the caller's box. Testing a path's first
     *       point instead would drop every path that runs through the corner of the band, and the
     *       reader could not tell that from a page with no path there.</li>
     * </ul>
     * An item whose box the module cannot compute (a singular matrix, a form without {@code /BBox})
     * is <em>kept</em>: a filter may not throw away what the module cannot locate — the same rule
     * {@link PdfClipPath#mayContain} follows.
     */
    private boolean insideFilter(Map<String, Object> textItem, List<Object> box) {
        if (options.bboxFilter() == null) {
            return true;
        }
        if (!PdfSpace.PAGE.equals(options.bboxFilter().space())) {
            return true;    // unreachable through pdf.placement(): the box arrives in page space
        }
        if (box == null && textItem == null) {
            return true;
        }
        if (box != null) {
            PdfRect own = PdfRect.of(number(box.get(0)), number(box.get(1)),
                    number(box.get(2)), number(box.get(3)), PdfSpace.PAGE);
            return options.bboxFilter().intersect(own) != null;
        }
        Object x = textItem.get("x");
        Object y = textItem.get("y");
        if (!(x instanceof Number px) || !(y instanceof Number py)) {
            return false;
        }
        return options.bboxFilter().contains(px.doubleValue(), py.doubleValue());
    }

    /** The rule the filter applies to this answer, in the words the {@code frame} block reports. */
    private String filterRule() {
        return options.collectsAreas() ? "text:origin,area:intersects" : "text:origin";
    }

    /** Which space this answer reports in — {@code page} when the page gave a frame, else user. */
    private String spaceName() {
        return options.pageSpace() != null && options.pageSpace().isUsable()
                ? PdfSpace.PAGE : PdfSpace.USER;
    }

    /** The page's own matrix, or null when there is none (and then no page space to report in). */
    private PdfMatrix pageRouteOrNull() {
        PdfPageSpace pageSpace = options.pageSpace();
        return pageSpace == null ? null : pageSpace.defaultUserToPage().orElse(null);
    }

    /** The same matrix, and the identity where there is no page — for a distance, never a point. */
    private PdfMatrix pageRoute() {
        PdfMatrix toPage = pageRouteOrNull();
        return toPage == null ? PdfMatrix.identity() : toPage;
    }

    /**
     * The route a <em>distance</em> travels from the operands to the reported space: the CTM, then the
     * page's own matrix. {@link PdfGraphicsState#widthSpanIn} uses only the linear part, which is why
     * a line width never picks up the page's 792 - see that method's note about mapPoint.
     */
    private PdfMatrix widthRoute(PdfMatrix ctm) {
        PdfMatrix toPage = pageRouteOrNull();
        return toPage == null ? ctm : ctm.multiply(toPage);
    }

    /** A user-space point of a path or a state in the space the answer reports in. */
    private PdfPoint mapped(PdfPoint userPoint, PdfMatrix ctm, String space) {
        return space.equals(PdfSpace.PAGE)
                ? ctm.multiply(pageRouteOrNull()).map(userPoint, PdfSpace.PAGE)
                : ctm.map(userPoint, PdfSpace.USER);
    }

    /**
     * The envelope of a path in the space the answer reports in — every segment with the CTM it was
     * built under, so a {@code cm} in the middle of a path does not move the earlier segments.
     * Null when the path is empty <em>or</em> when one of the routes collapses: a singular matrix has
     * no inverse (4.2.3, S. 209 note), the viewer's behaviour is undefined there, and a number out of
     * it would be a claim the file does not support (plan decision 9 — the same rule
     * {@link XObjectOps#bboxInSpace} follows for a form's {@code /BBox}).
     */
    private PdfRect boxOf(PdfPath path, PdfMatrix ctm, String space) {
        if (path.isEmpty() || singularRoute(path, space)) {
            return null;
        }
        List<List<PdfPoint>> lines = PathBuildOps.polylines(path,
                space.equals(PdfSpace.PAGE) ? pageRouteOrNull() : PdfMatrix.identity(), space);
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        boolean any = false;
        for (List<PdfPoint> line : lines) {
            for (PdfPoint point : line) {
                any = true;
                minX = Math.min(minX, point.x());
                minY = Math.min(minY, point.y());
                maxX = Math.max(maxX, point.x());
                maxY = Math.max(maxY, point.y());
            }
        }
        return any ? PdfRect.of(minX, minY, maxX, maxY, space) : null;
    }

    /** Whether any segment of the path would be mapped through a matrix without an inverse. */
    private boolean singularRoute(PdfPath path, String space) {
        return singularRouteOf(path, space) != null;
    }

    /**
     * The singular matrix behind this path — the page's frame or one of the segments' routes — or
     * {@code null} when every route can be undone. Never used for arithmetic: it exists so an item
     * can quote {@link PdfMatrix#singularNote()} instead of a coordinate (decision 9).
     */
    private PdfMatrix singularRouteOf(PdfPath path, String space) {
        PdfMatrix out = space.equals(PdfSpace.PAGE) ? pageRouteOrNull() : PdfMatrix.identity();
        if (out != null && out.isSingular()) {
            return out;
        }
        for (PdfPath.Segment segment : path.segments()) {
            PdfMatrix route = segment.ctm() == null || out == null
                    ? segment.ctm() : segment.ctm().multiply(out);
            if (route != null && route.isSingular()) {
                return route;
            }
        }
        return null;
    }

    /** The clipping rule of an operator name, or null for a name that is not one. */
    private static String ruleOf(String operator) {
        try {
            return PdfClipPath.ruleOf(operator);
        } catch (PdfException e) {
            return null;
        }
    }

    /** The folded inline-image value of an {@code EI} operand, or null. */
    private static Map<String, Object> imageInfo(PdfContentInterpreter.Operation operation) {
        for (Object operand : operation.operands()) {
            Map<String, Object> dict = asDict(operand);
            if (dict != null && (dict.containsKey("dict") || dict.containsKey("dataBytes"))) {
                return dict;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asDict(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    /** A number as the file wrote it (an integer stays an integer), or null. */
    private static Object numberOrNull(Object value) {
        if (value instanceof Long || value instanceof Integer) {
            return value;
        }
        if (value instanceof Number number) {
            double asDouble = number.doubleValue();
            if (asDouble == Math.rint(asDouble) && !Double.isInfinite(asDouble)) {
                return (long) asDouble;
            }
            return round(asDouble);
        }
        return null;
    }

    /** A name or a number as text, for a field that only labels (a colour space, a filter). */
    private static Object textOf(Object value) {
        if (value instanceof PdfName name) {
            return name.toString();
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) {
                out.add(textOf(item));
            }
            return out;
        }
        return numberOrNull(value);
    }

    private static double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    /** A reference the resource dictionary kept inline, read if the caller can read it. */
    private Object deref(Object value) {
        XObjectOps.Source source = options.xobjectSource();
        return source == null ? value : source.deref(value);
    }

    /**
     * The "vor Ort" block: the offset a caller can paste into {@code pdf.operations(path, objNum,
     * {after: offset})}, the operator that produced the numbers, and the state that was in effect.
     * For a non-text item the text matrix is not part of the answer, so the block leaves it out
     * instead of printing the identity of a state the operator never used.
     *
     * <p>
     * <b>{@code at.ctm} is the matrix at the operator this item names — not the matrix of every
     * point of it.</b> For a text item the two are the same thing: one operator, one matrix. A path
     * can be built across a {@code cm}, and then its {@code drawing}, {@code bbox} and
     * {@code currentPoint} carry each segment's own matrix while {@code at.ctm} stays the one at the
     * operator that ended the path. {@code ctmChangedMidPath} says when that is the case, and the
     * page's {@code diagnostics} name the byte (plan P4) — multiplying such a path's operands by
     * {@code at.ctm} is wrong by however far the matrices differ, silently.
     * </p>
     */
    private Map<String, Object> atBlock(PdfContentInterpreter.Operation operation,
                                        PdfTextState text, String space) {
        Map<String, Object> at = new LinkedHashMap<>();
        at.put("op", operation.name());
        at.put("ctm", matrix(operation.ctm()));
        if (text != null) {
            at.put("textMatrix", matrix(text.textMatrix()));
            if (options.fullState()) {
                at.put("lineMatrix", matrix(text.textLineMatrix()));
                at.put("textRenderingMatrix", matrix(text.hasFont() && text.fontSize() != null
                        ? text.textRenderingMatrix(operation.ctm()) : null));
            }
        }
        at.put("clipDepth", (long) operation.clipDepth());
        if (space.equals(PdfSpace.USER)) {
            at.put("clipComputed", false);
        }
        return at;
    }

    private static List<Object> matrix(PdfMatrix matrix) {
        if (matrix == null) {
            return null;
        }
        List<Object> out = new ArrayList<>(6);
        for (double value : matrix.toArray()) {
            out.add(round(value));
        }
        return out;
    }

    /**
     * The segments of a path, mapped into the space the item reports in — {@code drawing}, asked for
     * with {@code {drawing: true}} (increment D8b).
     *
     * <p>
     * A path item used to answer its envelope, its current point and how many segments of each kind
     * it holds. For an axis-parallel stroke that is the whole shape; for anything else it is not, and
     * the reader is left with {@code pdf.operations()} as a second question and a matrix product to
     * do by hand. The points were already computed here — {@link PathBuildOps#polylines} produced
     * them for the box and threw them away — so what D8b adds is the answer, not the work.
     * </p>
     *
     * <p>
     * One entry per segment, in the stream's order, of five kinds: {@code {"m":[x,y]}},
     * {@code {"l":[x,y]}}, {@code {"c":[x1,y1,x2,y2,x,y]}}, {@code {"re":[x1,y1,w,h]}} and the plain
     * string {@code "h"}. Every entry carries the CTM its operator ran under (a {@code cm} mid-path
     * moves the later segments only — see {@link PathBuildOps#placed}).
     * </p>
     *
     * <ul>
     *   <li><b>Curves stay curves.</b> The two control points are printed as the points they are;
     *     nothing is flattened (decision 12), and {@code bboxSource: 'control-polygon'} stays the
     *     only box-statement about a path with a bend in it.</li>
     *   <li><b>A {@code re} is listed as the box it is, or as its equivalence.</b> When the four
     *     mapped corners are still an axis-parallel box — whichever matrix ran, and the page's own
     *     y-flip included — the entry is that box normalised, {@code [x1, y1, w, h]} with
     *     {@code w >= 0} and {@code h >= 0}: the same four corners {@code bbox} names, in the same
     *     shape (printing the walked extent instead made every rect on a normal page read a negative
     *     height while {@code bbox} read a positive one). When a matrix turns the rectangle, S. 227's
     *     {@code m l l l h} is what the page shows and what the entry lists; the item then says
     *     {@code drawingRotated: true}.</li>
     *   <li><b>A mirrored space is named.</b> {@code drawingMirrored: true} marks an item whose path
     *     ran under a matrix with a negative determinant — the file mirrored the space rather than
     *     only turning it, so the points run the other round. For a stroke that is invisible; for a
     *     fill rule, a signed area or a hand-drawn reconstruction it is the whole difference. The
     *     page's own flip is not it (every page has one; {@code frame.yDown} says so).</li>
     *   <li><b>The point budget cuts whole segments, and says so.</b> The same budget a clip's
     *     {@code polygon} spends ({@code maxPolygonPoints} per item, {@code maxPolygonPointsTotal}
     *     per page read); a cut leaves {@code drawingTruncated} with the points and segments that fell
     *     out, because "truncated alone is no statement" (D6).</li>
     *   <li><b>{@code segments} and {@code drawing} are two different statements.</b> The counts name
     *     what the file wrote, the list names what the path is; wherever they are not the same list
     *     (an expanded {@code re}, a cut) the item says {@code drawingExpanded}. A {@code v} or
     *     {@code y} is the exception the flag cannot see: the path already holds the {@code c} the
     *     operator means, so the counts agree — {@link PathBuildOps} is where that expansion happens.</li>
     *   <li><b>No coordinates without a route.</b> A path built on a matrix without an inverse keeps
     *     its sentence ({@code singularNote}, decision 9) and gets no {@code drawing} at all.</li>
     * </ul>
     */
    private void putDrawing(Map<String, Object> item, PdfPath path, String space) {
        if (singularRoute(path, space)) {
            return;
        }
        if (path.isEmpty()) {
            // The counts say {} and 0 subpaths; the list says the same with an empty list. Nothing
            // is invented, and the field does not quietly disappear on a painting operator that
            // ended nothing (Table 4.10 gives such an operator an item of its own).
            item.put("drawing", new ArrayList<>());
            return;
        }
        List<PathBuildOps.PlacedSegment> placed = PathBuildOps.placed(path,
                space.equals(PdfSpace.PAGE) ? pageRouteOrNull() : PdfMatrix.identity(), space);
        int wanted = 0;
        for (PathBuildOps.PlacedSegment segment : placed) {
            wanted += segment.points().size();
        }
        int room = budget.takePolygonPoints(wanted);
        List<Object> drawing = new ArrayList<>();
        int listed = 0;
        int segmentsListed = 0;
        boolean rotated = false;
        boolean mirrored = false;
        Map<String, Long> listedKinds = new LinkedHashMap<>();
        for (PathBuildOps.PlacedSegment segment : placed) {
            // Whole segments only: half a cubic is not a curve, and a list that stops mid-segment
            // would read as a shape rather than as a cut.
            if (listed + segment.points().size() > room) {
                break;
            }
            listed += segment.points().size();
            segmentsListed++;
            // Handedness of the matrix the operator ran under — before the page's own matrix, which
            // mirrors every page anyway and would make the flag say "mirrored" for a file that
            // mirrored nothing (plan P3).
            if (segment.isMirrored()) {
                mirrored = true;
            }
            if ("re".equals(segment.kind())) {
                List<Object> rectangle = rectangleExtent(segment.points());
                if (rectangle != null) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("re", rectangle);
                    drawing.add(entry);
                    listedKinds.merge("re", 1L, Long::sum);
                    continue;
                }
                rotated = true;
                // The printed equivalence of S. 227, corner by corner.
                drawing.add(pointEntry("m", segment.points().get(0)));
                listedKinds.merge("m", 1L, Long::sum);
                for (int i = 1; i < segment.points().size(); i++) {
                    drawing.add(pointEntry("l", segment.points().get(i)));
                    listedKinds.merge("l", 1L, Long::sum);
                }
                drawing.add("h");
                listedKinds.merge("h", 1L, Long::sum);
                continue;
            }
            if ("h".equals(segment.kind())) {
                drawing.add("h");
                listedKinds.merge("h", 1L, Long::sum);
                continue;
            }
            List<Object> numbers = new ArrayList<>();
            for (PdfPoint point : segment.points()) {
                numbers.add(round(point.x()));
                numbers.add(round(point.y()));
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put(segment.kind(), numbers);
            drawing.add(entry);
            listedKinds.merge(segment.kind(), 1L, Long::sum);
        }
        item.put("drawing", drawing);
        if (rotated) {
            item.put("drawingRotated", true);
            noteOnce("drawing-rotated", "a path of this read contains a `re` whose matrix turns:"
                    + " its four corners are not a rectangle in the reported space, so the entry is"
                    + " the m l l l h equivalence 4.4.1, S. 227 prints for `re` (drawingRotated"
                    + " marks such an item) - [x y w h] would name a box the page does not show");
        }
        if (mirrored) {
            item.put("drawingMirrored", true);
            noteOnce("drawing-mirrored", "a segment of a path of this read ran under a matrix with"
                    + " a negative determinant (det < 0 — the file mirrored the space, it did not"
                    + " only turn it): the drawing lists the same points either way, but their order"
                    + " runs the other round, which is what a fill rule, a signed area or a hand"
                    + " reconstruction of the shape turns on (drawingMirrored marks such an item);"
                    + " the page's own y-flip is not counted here, because every page of the format"
                    + " has it and it is stated in frame.yDown");
        }
        // P2: `segments` counts what the file wrote, `drawing` lists what the path is. Where the two
        // are not the same list — an expanded `re`, a cut — the item says so, because a reader who
        // matches them one to one would move points to the wrong operator.
        if (!listedKinds.equals(path.counts())) {
            item.put("drawingExpanded", true);
        }
        if (segmentsListed < placed.size()) {
            long pointsDropped = (long) wanted - listed;
            long segmentsDropped = (long) placed.size() - segmentsListed;
            item.put("drawingTruncated", true);
            item.put("drawingPointsDropped", pointsDropped);
            item.put("drawingSegmentsDropped", segmentsDropped);
            noteOnce("drawing-cut", "a path's drawing was cut at " + MAX_POLYGON_POINTS
                    + " points per item (" + MAX_POLYGON_POINTS_PER_STREAM
                    + " per page read): what fell out is named in drawingPointsDropped and"
                    + " drawingSegmentsDropped, the whole count stays in segments - truncated alone"
                    + " would be no statement");
        }
    }

    /**
     * The four mapped corners of a {@code re} as the box they are — {@code [x1, y1, width, height]}
     * with {@code width >= 0} and {@code height >= 0} — or {@code null} when the four points are no
     * longer an axis-parallel box.
     *
     * <p>
     * The corners come out of {@link PdfPath} in the order the equivalence of S. 227 walked them,
     * and that order carries the direction the producer chose: on a page (whose own matrix mirrors
     * y) the walk runs downwards, so a box printed with the walked extent reads a negative height
     * — while {@code bbox} of the same item, computed from the same four points, reads normalised.
     * The same box in two shapes is what P3 of the drawing plan calls the stillest of its edges: a
     * caller adding {@code y + h} gets an inverted box and no flag warns. Printing the normalised
     * box here changes no corner and invents no geometry — the corner set is what is listed — and
     * leaves the item's two boxes in one shape.
     * </p>
     *
     * <p>
     * A matrix that turns the rectangle leaves the four corners as four corners; {@code [x y w h]}
     * would then name a box the page does not show, so {@code null} falls back to the printed
     * equivalence (and the item says {@code drawingRotated}).
     * </p>
     */
    private static List<Object> rectangleExtent(List<PdfPoint> corners) {
        if (corners.size() != 4) {
            return null;
        }
        // The decision is made on the printed numbers: what a reader compares has to be the shape
        // the answer shows, so the rounding is applied before the axes are tested.
        double[] xs = new double[4];
        double[] ys = new double[4];
        for (int i = 0; i < 4; i++) {
            xs[i] = round(corners.get(i).x());
            ys[i] = round(corners.get(i).y());
        }
        // Axis-parallel means every edge of the walked quadrilateral runs along one printed axis.
        for (int i = 0; i < 4; i++) {
            int next = (i + 1) % 4;
            if (xs[i] != xs[next] && ys[i] != ys[next]) {
                return null;
            }
        }
        double minX = Math.min(Math.min(xs[0], xs[1]), Math.min(xs[2], xs[3]));
        double maxX = Math.max(Math.max(xs[0], xs[1]), Math.max(xs[2], xs[3]));
        double minY = Math.min(Math.min(ys[0], ys[1]), Math.min(ys[2], ys[3]));
        double maxY = Math.max(Math.max(ys[0], ys[1]), Math.max(ys[2], ys[3]));
        // Rounded again: two printed numbers can subtract to something that is no longer printed
        // (0.6 - 0.2), and an extent that does not survive its own printing is a third shape.
        return List.of(round(minX), round(minY), round(maxX - minX), round(maxY - minY));
    }

    /** One {@code {"m":[x,y]}} / {@code {"l":[x,y]}} entry. */
    private static Map<String, Object> pointEntry(String kind, PdfPoint point) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put(kind, List.of(round(point.x()), round(point.y())));
        return entry;
    }

    // =============================================================================================
    // what the caller reads
    // =============================================================================================

    /** What this sink was asked to collect (and the walk options it needs — {@link Options#walkOptions}). */
    public Options options() {
        return options;
    }

    /** The items collected so far, in document order. */
    public List<Map<String, Object>> items() {
        return items;
    }

    /** How many items the caller's box removed (the count of what the walk did not show). */
    public long filteredOut() {
        return filteredOut;
    }

    /** How many items came after the item budget was used up (across every stream of the read). */
    public long beyondBudget() {
        return budget.beyondBudget();
    }

    /** Whether the item budget cut the list. */
    public boolean isTruncated() {
        return budget.beyondBudget() > 0;
    }

    /** Booked by {@link Budget#take} when the budget was already full. */
    void markBeyondBudget() {
        budget.addBeyond();
    }

    /** The sentences this sink added to the page's notes (each told once). */
    public List<String> notes() {
        return notes;
    }

    /** The walk's counts, as the sink saw them at the end of the stream (null before that). */
    public PdfContentInterpreter.Summary summary() {
        return summary;
    }

    /**
     * The {@code frame} block: the page's own frame, the one matrix every item of the page shares,
     * and the two labels that tell a reader which space the numbers are in.
     */
    public Map<String, Object> frame() {
        Map<String, Object> frame = new LinkedHashMap<>();
        PdfPageSpace pageSpace = options.pageSpace();
        boolean usable = pageSpace != null && pageSpace.isUsable();
        if (usable) {
            frame.put("mediaBox", numbers(pageSpace.mediaBox()));
            if (pageSpace.cropBox() != null) {
                frame.put("cropBox", numbers(pageSpace.cropBox()));
            }
            // The two boxes are what the page dictionary names, and that is default user space
            // (Table 3.27, S. 145) - the item coordinates above are page space. Saying which is
            // which is the point of the label (plan, decision 7).
            frame.put("boxSpace", PdfSpace.DEFAULT_USER);
            frame.put("basis", pageSpace.basis());
            frame.put("rotate", (long) pageSpace.rotate());
            frame.put("userUnit", round(pageSpace.userUnit()));
            frame.put("width", round(pageSpace.pageWidth()));
            frame.put("height", round(pageSpace.pageHeight()));
            frame.put("space", PdfSpace.PAGE);
            frame.put("origin", "top-left");
            frame.put("yDown", true);
            frame.put("userToPage", matrix(pageSpace.defaultUserToPage().orElse(null)));
            if (options.dpi() != null) {
                // The caller's number, printed as such: the file never names a resolution, which is
                // why device space is the one space that is not from the file (PdfSpace).
                frame.put("dpi", round(options.dpi()));
                frame.put("deviceScale", round(pageSpace.deviceScale(options.dpi())));
            }
        } else {
            frame.put("space", PdfSpace.USER);
            frame.put("pageComputed", false);
            if (pageSpace != null) {
                frame.put("note", pageSpace.defect());
            }
        }
        if (options.bboxFilter() != null) {
            // Which test the filter ran, said where the filtered items are: a text item is asked for
            // its origin, an area item for its box (insideFilter explains the two). A caller who
            // filters twice and wonders why a path appeared can read it here instead of guessing.
            frame.put("filterRule", filterRule());
        }
        return frame;
    }

    private static List<Object> numbers(PdfRect rect) {
        return List.of(round(rect.x1()), round(rect.y1()), round(rect.x2()), round(rect.y2()));
    }

    /** A rectangle's four numbers as a fresh list (the {@code bbox} field of an item). */
    private static List<Object> numbersOf(PdfRect rect) {
        return rect == null ? null : numbers(rect);
    }

    /** The page's counts block, ready to be put next to {@link #items()}. */
    public Map<String, Object> walkBlock() {
        Map<String, Object> walk = summary == null ? new LinkedHashMap<>() : summary.walk().toMap();
        if (budget.beyondBudget() > 0) {
            walk.put("itemsBeyondBudget", budget.beyondBudget());
        }
        if (filteredOut > 0) {
            walk.put("itemsFilteredOut", filteredOut);
        }
        if (options.bboxFilter() != null) {
            walk.put("filterRule", filterRule());
        }
        if (descents > 0) {
            // A second stream was walked on request. Counted apart from the page's own walk, because
            // an answer that grew silently would be indistinguishable from a page that grew.
            walk.put("descentStreams", (long) descents);
            walk.put("descentItems", (long) descentItems);
        }
        return walk;
    }

    /**
     * The {@code diagnostics} block (increment D7): what the walk could not use, told in the answer
     * instead of only in the guest. The counts and the sentences are
     * {@link PdfContentInterpreter.Diagnostics} verbatim — the object a test has read since D3 —
     * because a finding about a file is one phrasing, not one per audience.
     *
     * <h2>Why a block of its own and not {@code notes}</h2>
     *
     * {@code notes} are the sentences this module adds <em>about its answer</em> ("this item is
     * reported without a box because 4.4.3, S. 234 gives it none"), and D3 deliberately kept the
     * walk's findings out of them so that an existing answer would not change its tune
     * ({@code PdfContentInterpreterTest} still pins that). A defect of the <em>file</em> — an operand
     * that did not fit its row, a {@code Q} with an empty stack, a clip that narrowed to nothing, a
     * second {@code W} before the path was painted — is a different family and gets its own field,
     * which also means a caller can ask it apart from the sentences of a healthy page.
     *
     * <h2>Always there, and always a count before the sentences</h2>
     *
     * A clean page reads {@code clean: true} with an empty {@code entries} list rather than no block
     * at all: "walked, nothing to report" and "nobody looked" are different statements, and the same
     * argument is why {@link #summaryBlock()} prints a kind it did not collect as 0. The sentences
     * are capped at {@link PdfContentInterpreter#MAX_DIAGNOSTICS} (
     * {@code pdf.status().maxDiagnostics}); what the cap dropped stays counted in {@code total} and
     * is named in {@code beyondList}.
     *
     * <h2>One family the walk cannot report</h2>
     *
     * A path whose segments ran under different matrices is not a defect of the file — it is the one
     * place where this answer's own numbers come from more than one matrix — so the walk never sees
     * it as a finding and {@link PlacementSink} reports it itself, in the same
     * {@code otherReports} family and under the same cap (plan P4). The counts and sentences below are
     * therefore the walk's {@link PdfContentInterpreter.Diagnostics} plus what this sink had to add;
     * the walk's part of them is quoted verbatim, because a finding about a file is one phrasing,
     * not one per audience.
     *     *
     * @return the block, empty only while no walk has finished (see {@link #summary()})
     */
    public Map<String, Object> diagnosticsBlock() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (summary == null) {
            return out;
        }
        PdfContentInterpreter.Diagnostics walk = summary.diagnostics();
        // The sink's own reports (plan P4) belong to `otherReports` and are counted like every other
        // family; only the sentence list is shared, and it is shared under the same cap.
        long fromSink = matrixReportCount;
        long total = walk.total() + fromSink;
        out.put("clean", walk.isClean() && fromSink == 0);
        out.put("operandDefects", walk.operandDefects());
        out.put("stateUnderflows", walk.stateUnderflows());
        out.put("emptyClips", walk.emptyClips());
        out.put("pathErrors", walk.pathErrors());
        out.put("otherReports", walk.otherReports() + fromSink);
        out.put("total", total);
        // `listed` counts report sentences, not lines: the walk appends one cap sentence of its own
        // once the list is full, and that sentence is not a report. `beyondList` is what the cap
        // dropped - without it a caller reading "50" could not tell a stream with fifty findings from
        // one with five hundred.
        long listed = listedSentences(walk);
        List<Object> entries = new ArrayList<Object>(walk.entries());
        for (String report : matrixReports) {
            if (listed >= PdfContentInterpreter.MAX_DIAGNOSTICS) {
                break;      // the cap holds for the sink's sentences as it does for the walk's
            }
            entries.add(report);
            listed++;
        }
        out.put("listed", listed);
        if (total > listed) {
            out.put("beyondList", total - listed);
        }
        out.put("entries", entries);
        if (walk.truncated()) {
            // The item budget has its own field (truncated at the page); this one is the operator
            // budget, which cuts the walk itself and would otherwise leave a short answer unexplained.
            out.put("walkCut", true);
            out.put("operatorBudget", (long) walk.maxOperators());
        }
        if (!descentFindings.isEmpty()) {
            descentFindings.putInto(out);
        }
        return out;
    }

    /**
     * How many of the lines in {@code entries} are reports: the walk appends one sentence of its own
     * when it reaches {@link PdfContentInterpreter#MAX_DIAGNOSTICS} ("…further reports are counted,
     * not listed"), and that sentence is not a finding about the file.
     */
    private static long listedSentences(PdfContentInterpreter.Diagnostics walk) {
        List<String> entries = walk.entries();
        int sentences = entries.size();
        if (sentences > PdfContentInterpreter.MAX_DIAGNOSTICS) {
            sentences = PdfContentInterpreter.MAX_DIAGNOSTICS;
        }
        return sentences;
    }

    /**
     * The {@code summary} block: how many items of each kind this read contributed. The keys are the
     * five kinds in a fixed order — a kind that was not asked for reads 0, which is what
     * "not asked" means here, and a caller can tell that from {@code what} rather than from a
     * missing field.
     */
    public Map<String, Object> summaryBlock() {
        Map<String, Object> summary = new LinkedHashMap<>();
        Map<String, Long> counted = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            counted.merge(String.valueOf(item.get("kind")), 1L, Long::sum);
        }
        summary.put(KIND_TEXT, counted.getOrDefault(KIND_TEXT, 0L));
        summary.put("clips", counted.getOrDefault(KIND_CLIP, 0L));
        summary.put("paths", counted.getOrDefault(KIND_PATH, 0L));
        summary.put("xobjects", counted.getOrDefault(KIND_XOBJECT, 0L));
        summary.put("images", counted.getOrDefault(KIND_IMAGE, 0L));
        // Collected marks, not marks the page has: the count is 0 for every page because no `what`
        // asks for a mark item, and endStream says so in a note as soon as the stream opened one.
        summary.put("marks", 0L);
        if (filteredOut > 0) {
            summary.put("filteredOut", filteredOut);
        }
        if (budget.beyondBudget() > 0) {
            summary.put("beyondBudget", budget.beyondBudget());
        }
        return summary;
    }

    /** One sentence per finding (the same sentence is never told twice per stream). */
    private void noteOnce(String key, String sentence) {
        if (raised.add(key)) {
            notes.add(sentence);
        }
    }

    /** A coordinate to a millionth of a unit (see {@link #ROUNDING}). */
    private static Double round(Double value) {
        return value == null ? null : Math.round(value * ROUNDING) / ROUNDING;
    }

    /** A coordinate to a millionth of a unit. */
    private static double round(double value) {
        return Math.round(value * ROUNDING) / ROUNDING;
    }

    @Override
    public String toString() {
        return items.size() + " placement item(s)";
    }
}
