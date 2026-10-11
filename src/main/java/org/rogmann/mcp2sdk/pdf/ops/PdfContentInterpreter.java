package org.rogmann.mcp2sdk.pdf.ops;

import org.rogmann.mcp2sdk.pdf.PdfContentStream;
import org.rogmann.mcp2sdk.pdf.PdfContentStream.Token;
import org.rogmann.mcp2sdk.pdf.PdfException;
import org.rogmann.mcp2sdk.pdf.PdfName;
import org.rogmann.mcp2sdk.pdf.PdfResources;
import org.rogmann.mcp2sdk.pdf.geo.PdfMatrix;
import org.rogmann.mcp2sdk.pdf.geo.PdfPageSpace;
import org.rogmann.mcp2sdk.pdf.state.PdfClipPath;
import org.rogmann.mcp2sdk.pdf.state.PdfGraphicsState;
import org.rogmann.mcp2sdk.pdf.state.PdfPath;
import org.rogmann.mcp2sdk.pdf.state.PdfStateStack;
import org.rogmann.mcp2sdk.pdf.state.PdfTextState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * One pass over a content stream that <em>understands</em> what it reads — the state machine of
 * increment D3 (docs/js/pdf-content-plan.md, Aufgabe 3).
 *
 * <h2>Why this exists</h2>
 *
 * The module used to run five loops over the same tokens, each carrying its own belief about the
 * stream (plan table: "Fünf Glaubenssätze, fünf Heuristiken, keine Geometrie"). This is the loop
 * that replaces them, and everything a stream can be asked afterwards is a {@link PdfContentSink}
 * hanging off it. Two of those sinks are in this increment — {@link CountingSink} (the histogram of
 * {@code pdf.contentStreams}) and {@link ListingSink} (the listing of {@code pdf.operations}) — and
 * their acceptance is that both answers stay <b>byte-identical</b> to the ones the two hand-written
 * loops produced, because those answers are the only oracle available before the module starts
 * claiming coordinates (decision 2).
 *
 * <h2>What it maintains, and where each piece lives</h2>
 * <ul>
 *   <li>the graphics state — {@link PdfGraphicsState} (D2), transitions from Table 4.7 and 8.6,</li>
 *   <li>the {@code q}/{@code Q} stack — {@link PdfStateStack} (D2), counted and reported, never
 *       repaired (decision 8),</li>
 *   <li>the current path — {@link PdfPath} (D2), kept <em>outside</em> the state because 4.4.1,
 *       S. 226 keeps it out, and cleared at every operator carrying {@link
 *       PdfContentOperator.Effect#ENDS_PATH},</li>
 *   <li>the clipping path — {@link PdfClipPath} (D2) through {@link ClipOps}, which needs the page
 *       frame: without an {@link Options#pageSpace()} the clip is counted and named, not
 *       computed — an intersection of invented coordinates is exactly the claim this module has
 *       never made,</li>
 *   <li>the text state — {@link PdfTextState} (D2): {@code Tf} keeps the font the stream named,
 *       {@code Td}/{@code TD}/{@code Tm}/{@code T*}/{@code BT}/{@code ET} move the two matrices. The
 *       <em>displacement</em> a showing operator causes (5.3.3, S. 410) needs the widths and the
 *       kerning values and is D5.</li>
 * </ul>
 *
 * <h2>What it deliberately does not do</h2>
 *
 * Nothing is painted, nothing is rendered, nothing is executed: no font program, no JavaScript, no
 * form XObject content (a {@code Do} is counted and its name resolved, its stream is D6's problem),
 * no colour semantics (operands are kept raw, decision 11), no Bézier flattening (decision 12).
 * Reading the state transitions <em>is</em> interpretation of a state machine — the plan's
 * "Nichts ausführen" rule is about what a document wants to <em>do</em>, and this class never
 * follows such an intent.
 *
 * <h2>Nothing throws on a file's account</h2>
 *
 * An operand underflow, a {@code Q} with an empty stack, an {@code l} before any {@code m}, a clip
 * that narrowed to nothing: each is a {@link Summary#diagnostics()} entry naming the operator and
 * its offset, and the walk continues. The one exception is a failure of this module, which stays an
 * exception — a sink that throws, for instance, is a bug in the caller, not a fact about a file.
 */
public final class PdfContentInterpreter {

    /**
     * Operators one stream may contain before the walk stops. The default is deliberately the
     * tokenizer's own budget: a stream cannot hold more operators than tokens, so the default cut
     * can never truncate a listing that the tokenizer itself would have read to the end — that is
     * what keeps D3's "identical output" promise true on every file, and the number is only a
     * ceiling a caller may lower. It is {@code pdf.status().maxOps} (named after what it bounds,
     * increment D7; the walk's own answer calls it {@code diagnostics.operatorBudget}).
     */
    public static final int DEFAULT_MAX_OPERATORS = PdfContentStream.MAX_TOKENS;

    /**
     * How many diagnostic sentences one walk keeps. A stream with ten thousand operand defects
     * should still produce an answer; the count of the rest is in
     * {@link Diagnostics#operandDefects()} and named in the cut note. This is
     * {@code pdf.status().maxDiagnostics}, and {@code pdf.placement()}'s {@code diagnostics} block
     * lists at most this many sentences per walked stream (a descent is a walk of its own).
     */
    public static final int MAX_DIAGNOSTICS = 50;

    // =============================================================================================
    // Options
    // =============================================================================================

    /**
     * What the walk is asked to know.
     *
     * @param maxOperators operators read at most before the walk cuts itself (0 = {@link #DEFAULT_MAX_OPERATORS})
     * @param pageSpace    the page's frame, needed to put a clip into page space; null means "the
     *                     page geometry is not on the table", and clips are then counted, not computed
     * @param resources    the stream's resource dictionary, needed to apply {@code gs} (and, in D6,
     *                     {@code Do}); null means those operators are named and skipped
     * @param initialState the state the stream starts in, or null for {@link #initialState()}
     * @param textWidths   where a showing operator asks for a glyph width, or null for "do not
     *                     measure". This is a deliberate second face: {@code pdf.text()} asks for the
     *                     characters and pays nothing for widths, {@code pdf.placement()} asks for
     *                     positions and pays for them — while both read the <em>same</em> walk. What a
     *                     reader has to be able to check is {@link Walk#textAdvances()}: when it is
     *                     false, a showing operator left {@code T_m} where it was, and nobody may
     *                     read a position out of that.
     * @param glyphDetails whether a showing operator lists every code it drew (the detail
     *                     {@code pdf.placement({glyphs: true})} asks for)
     */
    public record Options(int maxOperators, PdfPageSpace pageSpace, PdfResources resources,
                          PdfGraphicsState initialState, PdfTextWidths textWidths,
                          boolean glyphDetails) {

        /** The plain walk: read everything, no page frame, no resources, a page that starts untransformed. */
        public static Options defaults() {
            return new Options(0, null, null, null, null, false);
        }

        /** A walk that may cut after {@code value} operators (0 = no practical limit). */
        public Options withMaxOperators(int value) {
            return new Options(value, pageSpace, resources, initialState, textWidths, glyphDetails);
        }

        /** A walk that knows the page and can therefore keep the clip in page space. */
        public Options withPageSpace(PdfPageSpace value) {
            return new Options(maxOperators, value, resources, initialState, textWidths,
                    glyphDetails);
        }

        /** A walk that can look resource names up (so {@code gs} changes the state). */
        public Options withResources(PdfResources value) {
            return new Options(maxOperators, pageSpace, value, initialState, textWidths,
                    glyphDetails);
        }

        /** A walk that starts from a state the caller established (a form XObject inherits one). */
        public Options withInitialState(PdfGraphicsState value) {
            return new Options(maxOperators, pageSpace, resources, value, textWidths, glyphDetails);
        }

        /** A walk that measures what a showing operator's glyphs displace (5.3.3, S. 410). */
        public Options withTextWidths(PdfTextWidths value) {
            return new Options(maxOperators, pageSpace, resources, initialState,
                    value == null ? PdfTextWidths.NONE : value, glyphDetails);
        }

        /** A walk that also lists the codes a showing operator drew. */
        public Options withGlyphDetails(boolean value) {
            return new Options(maxOperators, pageSpace, resources, initialState, textWidths, value);
        }

        /** The bound actually used. */
        public int maxOperatorsOrDefault() {
            return maxOperators > 0 ? maxOperators : DEFAULT_MAX_OPERATORS;
        }

        /** Whether showing operators are measured (false: {@code T_m} stays where it was). */
        public boolean measuresText() {
            return textWidths != null;
        }
    }

    // =============================================================================================
    // What a sink sees
    // =============================================================================================

    /**
     * One operator of the stream, as it happened.
     *
     * @param offset       byte offset of the operator token — the same offset {@code pdf.operations()}
     *                     prints, so an answer can be traced back into {@code pdf.operations(…, {after: offset})}
     * @param name         the operator as written
     * @param operator     its row in {@link PdfContentOperator} (an unknown-operator row for a name
     *                     outside the registry, never {@code null})
     * @param operands     the operands that were waiting, in document order (may contain {@code null},
     *                     which is a legal PDF operand, so this is a plain list)
     * @param commentOffset a {@code %} comment introducing the operation, or -1
     * @param stateBefore  the graphics state <em>as the operator saw it</em> — the CTM a {@code cm}
     *                     multiplies onto, the clip a {@code W} intersects
     * @param state        the graphics state <em>after</em> the operator, i.e. what the next operator
     *                     sees; for a showing operator the two are the same, and for {@code Tf} this
     *                     is the one carrying the new font
     * @param path         the current path <em>as the operator saw it</em> — at {@code S} the one just
     *                     built, at the operator after it an empty one (4.4.1, S. 226)
     * @param markedDepth  how deep the marked content nesting was when the operator ran
     * @param defect       what the operator could not do — operands that did not fit their registry
     *                     row, or a family that refused the operator (an {@code l} without a current
     *                     point, a clip whose coordinates cannot be mapped); null when it went through
     * @param advance      for a showing operator, how far its operand displaced the pen
     *                     ({@link TextShowOps.Advance}, 5.3.3, S. 410); null for every other operator
     *                     <b>and</b> for a walk that was given no way to ask for a width — see
     *                     {@link Options#measuresText()} and {@link Walk#textAdvances()}
     * @param clips        the {@code W}/{@code W*} operators this operator established (D6): 4.4.3,
     *                     S. 234 puts the clip on the <em>painting</em> operator, not on the
     *                     {@code W} itself, so this is where a clip's region first exists. Empty for
     *                     every operator that is not of Table 4.10, and for one that had no pending
     *                     {@code W} in front of it.
     */
    public record Operation(int offset, String name, PdfContentOperator operator,
                            List<Object> operands, int commentOffset,
                            PdfGraphicsState stateBefore, PdfGraphicsState state, PdfPath path,
                            int markedDepth, String defect, TextShowOps.Advance advance,
                            List<ClipEstablished> clips) {

        /**
         * One clipping operator, established here rather than where it was written.
         *
         * @param operator      {@code W} or {@code W*}
         * @param operatorOffset its byte offset — the number a caller quotes, because that is the
         *                       byte the stream wrote
         * @param layer         the clip layer it added (rings, rule, box), or {@code null} when no
         *                       region could be computed (no page frame on the table — D3's rule:
         *                       named, never invented)
         * @param note          what the operator had to say, or {@code null}
         * @param emptied       whether this left the clip enclosing nothing
         */
        public record ClipEstablished(String operator, int operatorOffset, PdfClipPath.Layer layer,
                                      String note, boolean emptied) {

            /** Whether a region was computed for it (false: counted and named, D3's reflex). */
            public boolean computed() {
                return layer != null;
            }
        }

        /** Whether the registry knows this operator. */
        public boolean isKnown() {
            return operator.isKnown();
        }

        /** The family of Table 4.7/4.9/5.5/… this operator belongs to. */
        public String category() {
            return operator.category();
        }

        /** Whether the operands were unusable, so the operator changed nothing but its own stack. */
        public boolean hasDefect() {
            return defect != null;
        }

        /** The CTM in effect at the operator. */
        public PdfMatrix ctm() {
            return stateBefore.ctm();
        }

        /** The text state in effect at the operator. */
        public PdfTextState textState() {
            return stateBefore.textState();
        }

        /** The font resource name in effect, or null when no {@code Tf} had named one yet. */
        public String fontResourceName() {
            return stateBefore.textState().fontResourceName();
        }

        /** How many clip layers were in effect at the operator. */
        public int clipDepth() {
            return stateBefore.clip().depth();
        }

        /** Whether this operator paints glyphs. */
        public boolean showsText() {
            return operator.showsText();
        }

        /** Whether a pending {@code W}/{@code W*} got its region here (4.4.3, S. 234). */
        public boolean establishesClip() {
            return clips != null && !clips.isEmpty();
        }

        /** The clipping operators established here, never null. */
        public List<ClipEstablished> clips() {
            return clips == null ? List.of() : clips;
        }

        /**
         * Whether this walk measured this showing operator's displacement. False for every operator
         * of a walk without {@link Options#textWidths()} — and then {@code T_m} of {@link #state()}
         * is the matrix <em>before</em> the string, not after it, which a consumer has to know before
         * it quotes a position.
         */
        public boolean hasAdvance() {
            return advance != null;
        }

        /** The operand at {@code index}, or null. */
        public Object operand(int index) {
            return index >= 0 && index < operands.size() ? operands.get(index) : null;
        }

        /** The first name operand ({@code /F39 12 Tf} → {@code "/F39"}), or null. */
        public PdfName nameOperand() {
            for (Object operand : operands) {
                if (operand instanceof PdfName name) {
                    return name;
                }
            }
            return null;
        }

        /** How many operands the operator was given. */
        public int operandCount() {
            return operands.size();
        }

        /** The operation as one line of PDF source, the way the listing writes it. */
        public String describe() {
            return PdfContentStream.formatOperation(operands, name);
        }

        @Override
        public String toString() {
            return "offset " + offset + ": " + describe();
        }
    }

    // =============================================================================================
    // What the walk reports
    // =============================================================================================

    /**
     * The counts of a walk — the block the plan's {@code pdf.placement()} answer calls {@code walk},
     * and the reason the module can say "this page saved and restored 39 times" without a second loop.
     *
     * @param tokens     tokens the tokenizer produced
     * @param operators  operator tokens seen (a {@code BI…EI} counts once, as its {@code EI})
     * @param operands   operand tokens seen
     * @param inlineImages inline-image tokens folded by the tokenizer
     * @param textObjects {@code BT} operators
     * @param showOperators showing operators (Table 5.6's four)
     * @param textAdvances  whether those showing operators <em>displaced the pen</em> in this walk —
     *                      false whenever the walk was given no {@link Options#textWidths()}, which
     *                      is the honest way of saying that {@code T_m} of the state a sink saw is
     *                      the matrix before the string rather than after it
     * @param paintedPaths paths ended by an operator of Table 4.10
     * @param clipOperators {@code W} and {@code W*} operators
     * @param clipDepth  the deepest clip nesting the walk reached
     * @param clipComputed whether the clip could be computed at all (false without a page frame)
     * @param saved      {@code q} operators that saved
     * @param restored   {@code Q} operators that restored
     * @param balanced   whether the two balanced out (4.3.1, S. 215 requires it)
     * @param deepestState the deepest the state stack went
     * @param xobjects   {@code Do} operators
     * @param xobjectNames the names they named, in first-seen order (D6 resolves them through
     *                     {@link org.rogmann.mcp2sdk.pdf.PdfResources#resolve})
     * @param markedSequences {@code BMC}/{@code BDC} operators
     * @param deepestMarked   how deep the marked content nesting went
     * @param longestPath the most segments one path reached
     * @param unknownOperators names the registry does not carry, in first-seen order
     * @param notInterpreted names the registry knows but does not interpret, in first-seen order
     */
    public record Walk(long tokens, long operators, long operands, long inlineImages,
                       long textObjects, long showOperators, boolean textAdvances,
                       long paintedPaths, long clipOperators,
                       int clipDepth, boolean clipComputed, long saved, long restored,
                       boolean balanced, int deepestState, long xobjects, List<String> xobjectNames,
                       long markedSequences, int deepestMarked, int longestPath,
                       List<String> unknownOperators, List<String> notInterpreted) {

        /** The walk as a result map — the {@code walk} block of {@code pdf.placement()}'s answer. */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("operators", operators);
            map.put("operands", operands);
            map.put("tokens", tokens);
            if (inlineImages > 0) {
                map.put("inlineImages", inlineImages);
            }
            map.put("textObjects", textObjects);
            map.put("showOperators", showOperators);
            map.put("textAdvances", textAdvances);
            map.put("paths", paintedPaths);
            map.put("clipOperators", clipOperators);
            map.put("clipDepth", (long) clipDepth);
            map.put("clipComputed", clipComputed);
            map.put("saved", saved);
            map.put("restored", restored);
            map.put("balanced", balanced);
            map.put("deepestState", (long) deepestState);
            map.put("xobjects", xobjects);
            if (!xobjectNames.isEmpty()) {
                map.put("xobjectNames", new ArrayList<Object>(xobjectNames));
            }
            map.put("markedSequences", markedSequences);
            map.put("deepestMarked", (long) deepestMarked);
            map.put("longestPath", (long) longestPath);
            if (!unknownOperators.isEmpty()) {
                map.put("unknownOperators", new ArrayList<Object>(unknownOperators));
            }
            if (!notInterpreted.isEmpty()) {
                map.put("notInterpreted", new ArrayList<Object>(notInterpreted));
            }
            return map;
        }
    }

    /**
     * What went wrong, and how far it got.
     *
     * @param operandDefects  operators whose operands did not fit their registry row
     * @param stateUnderflows {@code Q} operators that found an empty stack, and the {@code EMC} that
     *                        closed a sequence nobody had opened
     * @param emptyClips      clip operators after which nothing could be painted any more
     * @param pathErrors      path operators the path refused (an {@code l} before any {@code m}, …)
     * @param otherReports    every other report — a show without a font, a {@code Tr} outside 0..7, a
     *                        {@code gs} whose dictionary could not be read, a clip that needed a page
     *                        frame it was not given. Counted separately because they are not defects of
     *                        an operand, and a caller deciding what to show must be able to tell the
     *                        two families apart.
     * @param entries         the sentences, capped at {@link #MAX_DIAGNOSTICS}
     * @param truncated       whether the operator budget cut the walk
     * @param maxOperators    the budget that cut it (or would have)
     */
    public record Diagnostics(long operandDefects, long stateUnderflows, long emptyClips,
                              long pathErrors, long otherReports, List<String> entries,
                              boolean truncated, int maxOperators) {

        /** Whether the walk had nothing to complain about. */
        public boolean isClean() {
            return total() == 0 && !truncated && entries.isEmpty();
        }

        /** The sentences, each naming its offset. */
        public List<String> entries() {
            return entries;
        }

        /** How many reports there were in total, including the ones the cap dropped. */
        public long total() {
            return operandDefects + stateUnderflows + emptyClips + pathErrors + otherReports;
        }
    }

    /**
     * The whole walk: counts, reports, and the state the stream left behind.
     *
     * @param walk             the counts
     * @param diagnostics      the reports
     * @param trailingOperands operands at the end of the stream that no operator consumed (the case
     *                         {@code pdf.operations()} notes as "N operand(s) at the end…")
     * @param state            the graphics state after the last operator
     * @param path             the path after the last operator — not empty when the stream ended without
     *                         painting what it built
     * @param stack            the state stack itself, so a caller can ask what a {@code Q} would restore
     */
    public record Summary(Walk walk, Diagnostics diagnostics, int trailingOperands,
                          PdfGraphicsState state, PdfPath path, PdfStateStack stack) {

        /** A one-line summary for a probe or a listing. */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append(walk.tokens()).append(" tokens, ").append(walk.operators()).append(" operators")
                    .append(", ").append(walk.operands()).append(" operands");
            if (walk.inlineImages() > 0) {
                sb.append(", ").append(walk.inlineImages()).append(" inline image(s)");
            }
            sb.append("; ").append(walk.saved()).append('/').append(walk.restored())
                    .append(walk.balanced() ? " q/Q balanced" : " q/Q UNBALANCED");
            if (walk.clipOperators() > 0) {
                sb.append("; ").append(walk.clipOperators()).append(" clip operator(s), depth ")
                        .append(walk.clipDepth()).append(walk.clipComputed() ? "" : " (not computed)");
            }
            if (!walk.unknownOperators().isEmpty()) {
                sb.append("; unknown: ").append(String.join(", ", walk.unknownOperators()));
            }
            long defects = diagnostics.total();
            if (defects > 0) {
                sb.append("; ").append(defects).append(" report(s)");
            }
            if (diagnostics.truncated()) {
                sb.append("; cut after ").append(diagnostics.maxOperators()).append(" operators");
            }
            return sb.toString();
        }

        @Override
        public String toString() {
            return describe();
        }
    }

    // =============================================================================================
    // Reports
    // =============================================================================================

    /**
     * The sink for everything the walk could not use. Kept apart from the {@code notes} list a
     * caller passes in, because those notes are the existing output of {@code pdf.contentStreams()}
     * and {@code pdf.operations()} — a defect the module only started noticing in D3 must not
     * silently rewrite an answer users compare against last week's run. Since D7 the reports are
     * also visible: {@code pdf.placement()} answers them as its {@code diagnostics} block
     * ({@link PlacementSink#diagnosticsBlock()}), which is the function that asks for a walk, while
     * the two answers above keep the output they have always had.
     */
    static final class Issues {
        private final List<String> entries = new ArrayList<>();
        private final PdfContentSink sink;
        private long total;

        Issues(PdfContentSink sink) {
            this.sink = sink;
        }

        void add(int offset, String operator, String message) {
            total++;
            sink.onDiagnostic(offset, operator, message);
            if (entries.size() >= MAX_DIAGNOSTICS) {
                return;
            }
            entries.add((offset >= 0 ? "offset " + offset + ": " : "") + message);
            if (entries.size() == MAX_DIAGNOSTICS) {
                entries.add("…further reports are counted, not listed (" + MAX_DIAGNOSTICS
                        + " shown of " + total + " so far)");
            }
        }

        List<String> entries() {
            return Collections.unmodifiableList(entries);
        }

        long total() {
            return total;
        }
    }

    // =============================================================================================
    // Running
    // =============================================================================================

    // The only constructor is private (see below): a walk is created by run(...) and nothing else,
    // which is also why there is no "utility class" no-arg constructor here - it would have to
    // initialise the final per-walk fields to satisfy the compiler.

    /**
     * Walks a decoded content stream with the default options.
     *
     * @param content the decoded bytes
     * @param notes   the tokenizer's note sink (never null) — parse failures and budget cuts land
     *                here, exactly as they did before this class existed
     * @param sink    what reports to (never null; {@link PdfContentSink#noop()} for the counts alone)
     * @return the walk's summary
     */
    public static Summary run(byte[] content, List<String> notes, PdfContentSink sink) {
        return run(content, Options.defaults(), notes, sink);
    }

    /**
     * Walks a decoded content stream, tokenizing it first.
     *
     * @param content the decoded bytes
     * @param options what the walk may know
     * @param notes   the tokenizer's note sink (never null)
     * @param sink    what reports to (never null)
     * @return the walk's summary
     */
    public static Summary run(byte[] content, Options options, List<String> notes,
                              PdfContentSink sink) {
        return run(PdfContentStream.tokenize(content, notes),
                options == null ? Options.defaults() : options, notes, sink);
    }

    /**
     * Walks tokens the caller already has — the entry point a caller that keeps its tokens uses,
     * and the one the marked-content slice of {@code PdfStructTree} will use in D4.
     *
     * @param tokens  tokens in document order (never null)
     * @param options what the walk may know
     * @param notes   note sink for what is a <em>stream</em> problem (a budget cut); never null
     * @param sink    what reports to; never null
     * @return the summary
     */
    public static Summary run(List<Token> tokens, Options options, List<String> notes,
                              PdfContentSink sink) {
        return new PdfContentInterpreter(tokens == null ? List.of() : tokens,
                options == null ? Options.defaults() : options,
                notes == null ? new ArrayList<>() : notes,
                sink == null ? PdfContentSink.noop() : sink).execute();
    }

    /**
     * The state a stream starts in when the caller does not hand one in: the identity CTM (4.2.3,
     * S. 209 — "user space is the default user space" until a {@code cm} says otherwise) and the
     * page's own box as clip, or an unknown clip when the page frame is not on the table.
     */
    public static PdfGraphicsState initialState(Options options) {
        PdfPageSpace pageSpace = options.pageSpace();
        PdfClipPath clip = pageSpace != null && pageSpace.isUsable()
                ? PdfClipPath.initial(pageSpace.pageBox())
                : PdfClipPath.unknown();
        return PdfGraphicsState.initial(PdfMatrix.identity(), clip);
    }

    private final List<Token> tokens;
    private final Options options;
    private final List<String> notes;
    private final PdfContentSink sink;
    private final Issues issues;
    private final PdfStateStack stateStack;
    /**
     * The operands waiting for the next operator. Operands <em>and inline-image values</em> go on
     * it, which is what makes the listing come out the way it always did: the tokenizer folds
     * {@code BI … ID <data> EI} into one value token, and {@code pdf.operations()} has always shown
     * that value as the operand of {@code EI}.
     */
    private final PdfOperandStack stack;

    private PdfGraphicsState state;
    private PdfPath path = PdfPath.empty();
    /**
     * The {@code W}/{@code W*} operators whose path has not been painted yet (D6). 4.4.3, S. 234:
     * "Although the clipping path operator appears before the painting operator, it does <b>not</b>
     * alter the clipping path at the point where it appears. Rather, it modifies the effect of the
     * succeeding painting operator. After the path has been painted, the clipping path in the
     * graphics state is set to the intersection of the current clipping path and the newly
     * constructed path." So the intersection happens at the operator carrying
     * {@link PdfContentOperator.Effect#ENDS_PATH}, from the path <em>as that operator saw it</em> —
     * which is also why a {@code W} whose path is never painted clips nothing, and why path
     * construction after a {@code W} belongs to the clip.
     * <p>
     * The queue survives {@code q}/{@code Q} exactly as the path it refers to does (4.4.1, S. 226:
     * the path is not saved and restored), which is the only reading that keeps {@code W} and its
     * path together.
     */
    private final List<PendingClip> pendingClips = new ArrayList<>();
    /**
     * The displacement the operator just measured, handed to the sink with the {@link Operation} and
     * cleared for the next one. A field rather than a return value because a sink must see it at the
     * same moment it sees the state around it.
     */
    private TextShowOps.Advance advance;

    private long tokenCount;
    private long operatorCount;
    private long operandCount;
    private long inlineImageCount;
    private long textObjectCount;
    private long showOperatorCount;
    private long paintedPathCount;
    private long clipOperatorCount;
    private int deepestClip;
    private long xobjectCount;
    private long markedSequenceCount;
    private int markedDepth;
    private int deepestMarkedDepth;
    private int longestPath;
    private long operandDefects;
    private long stateUnderflows;
    private long emptyClips;
    private long pathErrors;
    private boolean truncated;
    private boolean cutNoted;
    private final LinkedHashSet<String> unknownNames = new LinkedHashSet<>();
    private final LinkedHashSet<String> notInterpretedNames = new LinkedHashSet<>();
    private final LinkedHashSet<String> xobjectNames = new LinkedHashSet<>();

    private PdfContentInterpreter(List<Token> tokens, Options options, List<String> notes,
                                  PdfContentSink sink) {
        this.tokens = tokens;
        this.options = options;
        this.notes = notes;
        this.sink = sink;
        // Assigned here rather than as field initialisers: Issues needs the sink, and the sink is a
        // constructor parameter.
        this.issues = new Issues(sink);
        this.stateStack = new PdfStateStack();
        this.stack = new PdfOperandStack();
        this.state = options.initialState() != null
                ? options.initialState() : initialState(options);
    }

    private Summary execute() {
        for (Token token : tokens) {
            if (token.isOperator() && operatorCount >= options.maxOperatorsOrDefault()) {
                cut();
                break;
            }
            tokenCount++;
            sink.onToken(token);
            if (token.isOperand()) {
                operandCount++;
                stack.push(token.value(), token.commentOffset());
                continue;
            }
            if (token.isInlineImage()) {
                inlineImageCount++;
                stack.push(token.value(), token.commentOffset());
                continue;
            }
            operatorCount++;
            apply(token);
        }
        return summary();
    }

    /**
     * The operator budget cut the walk. The cut belongs in the caller's notes — unlike a diagnostic
     * it <em>changes the answer</em> (the listing stops), so a caller that does not say so would be
     * reporting a truncated stream as a complete one.
     */
    private void cut() {
        truncated = true;
        if (cutNoted) {
            return;
        }
        cutNoted = true;
        notes.add("Content stream stopped after " + options.maxOperatorsOrDefault()
                + " operators (the rest of the stream was read by the tokenizer but not walked"
                + ")");
    }

    // ---------------------------------------------------------------------------------------------
    // One operator
    // ---------------------------------------------------------------------------------------------

    private void apply(Token token) {
        String name = (String) token.value();
        PdfContentOperator operator = PdfContentOperator.lookup(name);
        if (!operator.isKnown()) {
            unknownNames.add(name);
        } else if (!operator.isInterpreted()) {
            notInterpretedNames.add(name);
        }
        if (operator.has(PdfContentOperator.Effect.SHOWS_TEXT)) {
            showOperatorCount++;
        }
        if (PdfContentOperator.TEXT_OBJECT.equals(operator.category()) && "BT".equals(name)) {
            textObjectCount++;
        }
        if (operator.has(PdfContentOperator.Effect.RUNS_XOBJECT)) {
            xobjectCount++;
            PdfName named = nameOperand();
            if (named != null) {
                xobjectNames.add(named.toString());
            }
        }

        // Marked content nests structurally, whatever its operands do: the file wrote BMC, so a
        // matching EMC belongs to it, and losing the nesting over a missing tag would move every
        // later EMC's report to the wrong place.
        int depthAsSeen = markedDepth;
        boolean opensSequence = "BMC".equals(name) || "BDC".equals(name);
        if (opensSequence) {
            markedSequenceCount++;
            markedDepth++;
            deepestMarkedDepth = Math.max(deepestMarkedDepth, markedDepth);
        }

        PdfGraphicsState stateBefore = state;
        PdfPath pathAsSeen = path;
        advance = null;
        List<Operation.ClipEstablished> clips = List.of();
        String defect = stack.defectFor(operator);
        if (defect != null) {
            operandDefects++;
            issues.add(token.offset(), name, defect + " - the operator was not applied and its"
                    + " operands were dropped, so the walk continues with an empty operand stack");
        } else {
            try {
                run(operator, token, name);
            } catch (PdfException e) {
                // A family that could not honour the operator - a coordinate it cannot map, a
                // resource dictionary it cannot read. Told at the offset, and the walk continues:
                // the specification's "an error is generated" is not "the page is lost", and one
                // broken operator may not cost the reader the other four thousand.
                defect = name + ": " + e.getMessage();
                issues.add(token.offset(), name, defect + " - the operator changed nothing");
            }
        }

        // Structural closers, independent of the operands.
        if (operator.endsPath()) {
            // 4.4.3, S. 234: the pending W/W* gets its region here, from the path as this operator
            // saw it - before the path is thrown away below.
            clips = establishClips(pathAsSeen, token.offset(), name);
            if (!path.isEmpty()) {
                paintedPathCount++;
                longestPath = Math.max(longestPath, path.segments().size());
            }
            path = path.painted();
        }
        if ("EMC".equals(name)) {
            markedDepth--;
            if (markedDepth < 0) {
                stateUnderflows++;
                markedDepth = 0;
                issues.add(token.offset(), name, "an EMC operator closed a marked content sequence"
                        + " that no BMC or BDC had opened - 14.6.2 nests the two, and the module"
                        + " counts the extra EMC rather than assuming which sequence it meant");
            }
        }

        // A comment belongs to the operation it introduces, and the tokenizer attaches it to the
        // token after it - which is the first operand whenever the stream wrote operands. That is how
        // pdf.operations() has always placed a comment, so the earlier of the two wins.
        int commentOffset = stack.commentOffset() >= 0 ? stack.commentOffset()
                : token.commentOffset();
        sink.onOperation(new Operation(token.offset(), name, operator, stack.snapshot(),
                commentOffset, stateBefore, state, pathAsSeen, depthAsSeen, defect, advance, clips));
        stack.clear();
    }

    private void run(PdfContentOperator operator, Token token, String name) {
        switch (operator.category()) {
            case PdfContentOperator.GENERAL_STATE -> {
                if ("q".equals(name)) {
                    String note = stateStack.save(state);
                    if (note != null) {
                        issues.add(token.offset(), name, note);
                    }
                } else if ("Q".equals(name)) {
                    PdfStateStack.Restored restored = stateStack.restore(state);
                    state = restored.state();
                    if (restored.underflow()) {
                        stateUnderflows++;
                        issues.add(token.offset(), name, restored.note());
                    }
                } else {
                    state = GeneralStateOps.apply(state, operator, stack, options, issues,
                            token.offset());
                }
            }
            case PdfContentOperator.COLOUR -> state = ColourOps.apply(state, operator, stack,
                    issues, token.offset());
            case PdfContentOperator.PATH_CONSTRUCTION -> {
                PathBuildOps.Result built = PathBuildOps.apply(path, operator, stack, state.ctm());
                if (built.defect() != null) {
                    pathErrors++;
                    issues.add(token.offset(), name, built.defect());
                }
                if (built.path().isTruncated() && !path.isTruncated()) {
                    issues.add(token.offset(), name, built.path().truncatedNote());
                }
                path = built.path();
            }
            case PdfContentOperator.CLIPPING -> {
                clipOperatorCount++;
                // Not applied here: 4.4.3, S. 234 lets a W modify the *succeeding painting operator*.
                // The region is computed at that operator, from the path as it stands there - so a
                // W whose path is never painted establishes no clip at all, and a path built after
                // the W belongs to the clip. See establishClips(...) and the pendingClips field.
                if (!pendingClips.isEmpty()) {
                    issues.add(token.offset(), name, "a second clipping operator ran before the"
                            + " path was painted (the first was "
                            + pendingClips.get(0).operator() + " at offset "
                            + pendingClips.get(0).offset() + ") - " + name + " at this offset joins"
                            + " the queue, and both are established on the same path, the one the"
                            + " painting operator finds: 4.4.3 speaks of \"the newly constructed"
                            + " path\", singular, so the path the first W saw is not a second region");
                }
                pendingClips.add(new PendingClip(operator, token.offset()));
            }
            case PdfContentOperator.TEXT_OBJECT, PdfContentOperator.TEXT_STATE,
                    PdfContentOperator.TEXT_POSITION -> {
                TextStateOps.Result applied = TextStateOps.apply(state, operator, stack);
                state = applied.state();
                if (applied.defect() != null) {
                    issues.add(token.offset(), name, applied.defect());
                }
            }
            case PdfContentOperator.TEXT_SHOWING -> {
                if (options.measuresText()) {
                    // D5: the same printed code of S. 407 that TextStateOps applies, plus the
                    // displacement of S. 410 - and the displacement is what a width is needed for.
                    TextShowOps.Result shown = TextShowOps.apply(state, operator, stack,
                            options.textWidths(), options.glyphDetails(),
                            TextShowOps.MAX_DETAILS_PER_OPERATOR);
                    state = shown.state();
                    advance = shown.advance();
                    if (shown.defect() != null) {
                        issues.add(token.offset(), name, shown.defect());
                    }
                } else {
                    // Asked for the characters, not for the geometry: the operator still runs its
                    // printed code (T* of the quotes, the two spacings), it just does not move T_m.
                    TextStateOps.Result applied = TextStateOps.apply(state, operator, stack);
                    state = applied.state();
                    if (applied.defect() != null) {
                        issues.add(token.offset(), name, applied.defect());
                    }
                }
            }
            default -> {
                // path-painting (the path ends below), marked content (nesting above), XObject,
                // inline image, Type 3, compatibility, shading and unknown operators: the operands
                // leave the stack with the operation, and the counters above are what the walk
                // learned. Painting a form or interpreting a shading is D6's, not this increment's.
            }
        }
    }

    /**
     * Establishes every pending clipping operator on the path a painting operator found.
     *
     * <p>
     * The moment is the specification's, not a convenience: "it does not alter the clipping path at
     * the point where it appears. Rather, it modifies the effect of the succeeding painting
     * operator" (4.4.3, S. 234). Two consequences a reader of a clip item has to be able to see:
     * a {@code W} whose path is never painted clips nothing, and path construction <em>after</em>
     * the {@code W} belongs to the clip. Both are reported rather than smoothed over.
     * </p>
     *
     * <p>
     * Several {@code W}s in front of one painting operator each get a layer of their own (Table
     * 4.11 gives each its own rule, and the module counts what the file wrote), all of them from the
     * same path — which is what the singular "the newly constructed path" of S. 234 says, and what
     * the queue note above names at the finding site.
     * </p>
     */
    private List<Operation.ClipEstablished> establishClips(PdfPath painted, int paintOffset,
                                                           String paintOperator) {
        if (pendingClips.isEmpty()) {
            return List.of();
        }
        List<Operation.ClipEstablished> established = new ArrayList<>();
        for (PendingClip pending : pendingClips) {
            ClipOps.Result clipped = ClipOps.apply(state, pending.operator(), painted, options,
                    pending.offset());
            state = clipped.state();
            if (clipped.emptied()) {
                emptyClips++;
            }
            for (String note : clipped.notes()) {
                issues.add(paintOffset, paintOperator, note);
            }
            deepestClip = Math.max(deepestClip, state.clip().depth());
            PdfClipPath.Layer layer = state.clip().layers().isEmpty() ? null
                    : state.clip().layers().get(state.clip().layers().size() - 1);
            established.add(new Operation.ClipEstablished(pending.operator().name(),
                    pending.offset(), clipped.computed() ? layer : null,
                    String.join(" ", clipped.notes()), clipped.emptied()));
        }
        pendingClips.clear();
        return established;
    }

    /**
     * The {@code W}/{@code W*} operators a stream wrote whose path was never painted. 4.4.3, S. 234
     * makes them modify "the succeeding painting operator", and when no such operator follows there
     * is nothing to modify: no clip is established, and the file is told so at the offset of the
     * {@code W} instead of being credited with a region it never claimed.
     */
    private void reportUnpaintedClips() {
        for (PendingClip pending : pendingClips) {
            issues.add(pending.offset(), pending.operator().name(),
                    "a " + pending.operator().name() + " ran whose path was never painted - 4.4.3,"
                            + " S. 234: the operator \"modifies the effect of the succeeding painting"
                            + " operator\", and this stream has none after it, so no clipping path"
                            + " was established (the clip stays as it was)");
        }
    }

    /** One clipping operator waiting for the painting operator it modifies (S. 234). */
    private record PendingClip(PdfContentOperator operator, int offset) {
    }


    /** The first name among the pending operands, used for the {@code Do} list. */
    private PdfName nameOperand() {
        for (int i = 0; i < stack.size(); i++) {
            if (stack.get(i) instanceof PdfName name) {
                return name;
            }
        }
        return null;
    }

    private Summary summary() {
        // The operands the stream left behind are the caller's note, not a diagnostic: the listing
        // has said "N operand(s) at the end…" since before this class existed.
        reportUnpaintedClips();
        int trailing = stack.size();
        PdfPageSpace frame = options.pageSpace();
        boolean clipComputable = frame != null && frame.isUsable();
        Walk walk = new Walk(tokenCount, operatorCount, operandCount, inlineImageCount,
                textObjectCount, showOperatorCount, options.measuresText(), paintedPathCount,
                clipOperatorCount,
                deepestClip, clipComputable, stateStack.saved(), stateStack.restored(),
                stateStack.isBalanced(), stateStack.deepest(), xobjectCount,
                List.copyOf(xobjectNames), markedSequenceCount, deepestMarkedDepth, longestPath,
                List.copyOf(unknownNames), List.copyOf(notInterpretedNames));
        long counted = operandDefects + stateUnderflows + emptyClips + pathErrors;
        Diagnostics defects = new Diagnostics(operandDefects, stateUnderflows, emptyClips,
                pathErrors, Math.max(0, issues.total() - counted), issues.entries(), truncated,
                options.maxOperatorsOrDefault());
        Summary summary = new Summary(walk, defects, trailing, state, path, stateStack);
        sink.endStream(summary);
        return summary;
    }

    /** A listing of the families the interpreter dispatches to — for {@code pdf.help()} and tests. */
    public static Map<String, List<String>> families() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (String category : PdfContentOperator.CATEGORIES) {
            List<String> names = PdfContentOperator.namesOf(category);
            if (!names.isEmpty()) {
                map.put(category, names);
            }
        }
        return map;
    }
}
