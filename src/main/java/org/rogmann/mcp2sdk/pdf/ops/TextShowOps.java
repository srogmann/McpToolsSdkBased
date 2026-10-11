package org.rogmann.mcp2sdk.pdf.ops;

import org.rogmann.mcp2sdk.pdf.PdfFontWidths;
import org.rogmann.mcp2sdk.pdf.PdfGlyphMapper;
import org.rogmann.mcp2sdk.pdf.PdfParser;
import org.rogmann.mcp2sdk.pdf.PdfString;
import org.rogmann.mcp2sdk.pdf.PdfTextDecoder;
import org.rogmann.mcp2sdk.pdf.state.PdfGraphicsState;
import org.rogmann.mcp2sdk.pdf.state.PdfTextState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The text-showing operators' arithmetic — increment D5 (docs/js/pdf-content-plan.md, Aufgabe 5),
 * the displacement of 5.3.3, S. 410 written down once.
 *
 * <h2>What the specification prints</h2>
 *
 * <pre>
 * t_x = ((w_0 − T_j/1000) × T_fs + T_c + T_w) × T_h
 * t_y = ((w_1 − T_j/1000) × T_fs + T_c + T_w)
 * T_rm = [T_fs×T_h  0  0;  0  T_fs  0;  0  T_rise  1] × T_m × CTM
 * T_m  = [1 0 0 1 t_x t_y] × T_m                                (S. 410, after the glyph)
 * </pre>
 *
 * Every line is implemented as printed, including the three places a reader is tempted to "fix":
 * <ul>
 *   <li><b>{@code T_w} stands in both lines.</b> This plan first claimed it belonged to {@code t_x}
 *       only; S. 410 prints it in both ("if applicable"), so both are built and the open question —
 *       whether a producer ever means it vertically — stays a question about producers rather than a
 *       number this module changes (plan, "Zu verifizieren", correction 2).</li>
 *   <li><b>{@code T_h} multiplies the whole of {@code t_x} and nothing of {@code t_y}.</b> A
 *       horizontal scaling that also reached the vertical line would scale a rise twice.</li>
 *   <li><b>{@code T_j} sits inside the parenthesis</b>, next to {@code w_0}: an adjustment of
 *       {@code −250} at 10&nbsp;pt opens 2.5&nbsp;pt, not 0.25&nbsp;pt. Table 5.6, S. 407 says the
 *       amount is <em>subtracted</em>, and reading a bare array number as a zero-width glyph in the
 *       formula above is the same arithmetic — which is why one loop covers strings and numbers.</li>
 * </ul>
 *
 * <h2>Which space the result lives in — the trap of this class</h2>
 *
 * {@code (t_x, t_y)} is a displacement of <b>text space</b>. It is applied to {@code T_m} by
 * premultiplying a translation ({@link PdfTextState#advanceBy(double, double)}), i.e. in the space
 * <em>entering</em> {@code T_m}, and it has to be mapped onwards by {@code T_m × CTM} — <b>not</b>
 * by {@code T_rm}. The font size is already inside {@code t_x}; {@code T_rm} contains the size a
 * second time because it maps <em>glyph space</em> outwards (S. 409). Mapping an advance through
 * {@code T_rm} counts the size twice: at 10&nbsp;pt a width of 500 reads as 50&nbsp;pt instead of
 * 5&nbsp;pt. {@link PlacementSink} therefore asks {@code T_rm} for a position and
 * {@code T_m × CTM} for a distance, and says which is which.
 *
 * <h2>Where a number is missing</h2>
 *
 * A width comes from the font or it does not come ({@link PdfTextWidths}). When one is missing the
 * <b>total</b> advance is not reported — an advance that counts the missing widths as zero is a
 * whole-looking number built from parts, which is the rule the fonts plan wrote for a glyph metric
 * (Regel A5) and that {@link org.rogmann.mcp2sdk.pdf.PdfGlyphMapper.Width} already keeps for
 * {@code pdf.glyphMetrics()}. The elements that could be computed keep their own numbers (a kerning
 * value needs no width), and the item says {@code widthsMissing: n}.
 * <p>
 * The pen nevertheless moves, and the walk moves it by the part the file names
 * ({@link Advance#movedTx()}). Leaving {@code T_m} where it was would place every following operator
 * of the text object somewhere the stream never reached; moving it by an invented width would
 * invent exactly the number the method refuses to report. The choice is told at the item that could
 * not be measured, not hidden in this paragraph.
 * </p>
 *
 * <h2>What D9a added: which piece of the operand a code belongs to</h2>
 *
 * The displacement of a code was already there; what was missing was the two facts a reader needs to
 * use it — {@link Glyph#piece()} (which element of the operand the code is part of, counted the way
 * the {@code elements} list counts them, a {@code TJ} number included) and
 * {@link Glyph#kernBefore()} (the number written directly in front of it, if any). A showing
 * operator mixes three mechanisms into one line of text — a string of codes, the numbers between
 * them and, outside the operator, a {@code Td} — and none of the three boundaries is the boundary of
 * a word (plan D9a measures one bit-ruler written all three ways). The piece names the file's
 * structure; the <em>position</em> of a code is the walk's answer and is laid down where the item's
 * own {@code x}/{@code y} come from ({@link PlacementSink}), from these same per-code displacements —
 * so there is one width source and one arithmetic, and a position cannot drift away from the advance
 * it was computed from.
 */
public final class TextShowOps {

    /**
     * The base a dictionary width is written on: "1000 units of glyph space to 1 unit of text
     * space" (4.2.1, S. 203), and {@code /Widths} and {@code /W} count in the same units
     * (5.5, S. 396; 5.6.3, S. 439).
     */
    public static final double WIDTH_BASE = 1000.0;

    /**
     * Code 32 is the only code {@code T_w} speaks about (5.2.2, S. 399) — and only where it is a
     * code of its own: "It does not apply to occurrences of the byte value 32 in multiple-byte
     * codes". So the two-byte CID 32 of an {@code Identity-H} font is displaced by {@code T_c} like
     * every other code, which is a different answer by 25&nbsp;pt at {@code Tw 25}. Where the
     * restriction was over-read as a substitution, the space lost {@code T_c} for the step that
     * leads away from it — see the walk below and docs/js/pdf-text-advance-plan.md.
     */
    public static final long SPACE_CODE = 32L;

    /**
     * How many elements and how many glyphs one showing operator may list. A {@code TJ} array of a
     * real page runs to forty entries and a string to three hundred codes; a budget per operator
     * (not per page) is what keeps one pathological string from becoming the answer. The count of
     * what was dropped stays exact — {@link Advance#detailsTruncated()} says that it happened, and
     * {@link Advance#codes()} counts every code whether it was listed or not.
     */
    public static final int MAX_DETAILS_PER_OPERATOR = 200;

    /**
     * One code of a shown string: the width the font named for it, the displacement it caused, and
     * <b>which piece of the operand it belongs to</b> — increment D9a
     * (docs/js/pdf-content-plan.md, Aufgabe D9a).
     *
     * <h2>Why a code needs its piece</h2>
     *
     * The plan's measurement (Sonde {@code probes/hello-TD.pdf}, Spalte G/F, and the corpus rework
     * {@code [(8) -2550 (12) -1190 (15)]}): one bit-ruler is written in three different ways, and
     * <em>none of them coincides with a piece boundary</em>. One string {@code (01)} can hold two
     * codes 72 pt apart, the next code can arrive by {@code Td} two points later, and a {@code TJ}
     * number can pull two digits together to 0.02 pt. A reader who concatenates pieces therefore
     * reads {@code 01} + {@code 6} where the page shows {@code 0} and {@code 16}; naming the piece
     * is what lets a reader <em>see</em> the file's structure next to the positions (D9b is the
     * question of when two codes belong together, and it is a question about positions).
     *
     * @param index       position in the operand's code run (0-based, across the whole operand)
     * @param hex         the code as uppercase hex of its own width, the way the listings write it
     * @param code        the code value (a CID for a CID font)
     * @param width       the width on the file's 1000-unit base, or null when none is named
     * @param widthSource {@code W}, {@code Widths}, {@code hmtx}, {@code DW} or {@code none}
     * @param tx          the x displacement this code caused (its spacing included), or null when
     *                    the font named no width for it
     * @param ty          the same for y; null while the writing mode is horizontal
     * @param penX        where the code stands: the pen's x in <b>text space</b>, measured from the
     *                    operand's start and <em>including the {@code TJ} numbers before it</em> —
     *                    the number is what separates this from adding up widths (D9a; S. 410 moves
     *                    {@code T_m} by the same sum after every element, a number included)
     * @param penY        the same for y (0 while the writing mode is horizontal)
     * @param piece       index of the operand's element this code is part of, counted the way
     *                    {@link Element} entries are counted — so the {@code elements} list of the
     *                    answer is addressable by it, a {@code TJ} number included in the count
     *                    (which is why the strings of {@code [(8) -2550 (12)]} are pieces 0 and 2
     *                    and not 0 and 1: piece 1 <em>is</em> the number). The count runs over the
     *                    operand, so under {@code detailsTruncated} it can name a piece the printed
     *                    list no longer holds.
     * @param kernBefore  the {@code TJ} number written directly before this code, in thousandths as
     *                    the file wrote it, or null when the code does not stand right behind a
     *                    number — which is the case for every code of a string after its first, and
     *                    for every code of a {@code Tj}
     */
    public record Glyph(int index, String hex, long code, Number width, String widthSource,
                        Double tx, Double ty, double penX, double penY, int piece,
                        Double kernBefore) {
    }

    /**
     * One element of an operand: a string (all its codes together) or one number of a {@code TJ}
     * array.
     *
     * @param adjustment      true for a {@code TJ} number, false for a string
     * @param adjustmentValue the number as the file wrote it (thousandths, subtracted), or null
     * @param codes           codes the string holds (0 for a number)
     * @param tx              the element's x displacement in text space, or null when a width is missing
     * @param ty              the element's y displacement, or null
     * @param computable      whether every code of the element had a width
     */
    public record Element(boolean adjustment, Double adjustmentValue, long codes, Double tx,
                          Double ty, boolean computable) {
    }

    /**
     * One showing operator's effect on the pen, and the operand's parts.
     *
     * @param showState       the text state the <em>glyphs</em> were drawn with: after the {@code T*}
     *                          that {@code '} and {@code "} contain and after the two spacings the
     *                          double quote sets (S. 407 prints both as code), so a consumer placing
     *                          the operand must take {@code T_m}, {@code T_fs}, {@code T_c}, {@code T_w}
     *                          and {@code T_rise} from here and not from the state before the operator
     * @param tx                total x displacement in text space, null when a width was missing
     * @param ty                total y displacement, null in the same case (0 while horizontal)
     * @param movedTx           what {@code T_m} was actually moved by: the same as {@code tx} when it
     *                          is non-null, the computable part alone otherwise
     * @param movedTy           the same for y
     * @param codes             codes the operand holds (0 when the string could not be cut at all)
     * @param codesWithoutWidth codes the font gave no width for
     * @param computable        whether {@code tx}/{@code ty} cover the whole operand
     * @param widthSources      the sources that answered, in first-answer order (possibly empty)
     * @param elements          the operand's elements, in order (possibly cut by the detail cap)
     * @param glyphs            per-code detail, only when the caller asked for it — each with its
     *                          {@link Glyph#piece()} (index into this very list) and its
     *                          {@link Glyph#kernBefore()} (D9a)
     * @param detailsTruncated  whether the detail cap cut {@code elements} or {@code glyphs}
     * @param note              what could not be measured (an uncuttable string, a stray operand), or null
     */
    public record Advance(PdfTextState showState, Double tx, Double ty, double movedTx,
                          double movedTy, long codes, long codesWithoutWidth, boolean computable,
                          List<String> widthSources, List<Element> elements, List<Glyph> glyphs,
                          boolean detailsTruncated, String note) {
    }

    /**
     * The operator's effect.
     *
     * @param state   the state that follows, {@code T_m} moved by the computable part
     * @param defect  what the operator could not do (no font, no size), or null
     * @param advance the pen's move, or null when there was nothing to measure at all
     */
    public record Result(PdfGraphicsState state, String defect, Advance advance) {
    }

    private TextShowOps() {
        // static family
    }

    /**
     * Applies one of the four showing operators of Table 5.6 (S. 407) including its displacement.
     *
     * @param state      the state in effect at the operator
     * @param operator   its registry row
     * @param stack      the operands, whose arity {@link PdfOperandStack#defectFor} has cleared
     * @param widths     where to ask for a width — never null in the interpreter, and
     *                   {@link PdfTextWidths#NONE} answers "nothing", which leaves the advance
     *                   unmeasured rather than zero
     * @param wantGlyphs whether every code is listed on its own
     * @param maxDetails how many elements and how many glyphs one operator may list (0 = no limit)
     * @return the state that follows, the report when the operator could not be honoured, and the
     *         displacement
     */
    public static Result apply(PdfGraphicsState state, PdfContentOperator operator,
                               PdfOperandStack stack, PdfTextWidths widths, boolean wantGlyphs,
                               int maxDetails) {
        // The half that needs no width first: T* of the two quotes, the two spacings the double
        // quote sets, and the "no font in effect" sentence. TextStateOps owns the printed code of
        // S. 407, this class owns S. 410 on top of it - which is also why a spacing an operator
        // sets is already in effect while its own string is measured, exactly as the printed
        // "aw Tw ac Tc string '" reads.
        TextStateOps.Result base = TextStateOps.apply(state, operator, stack);
        PdfGraphicsState started = base.state();
        PdfTextState text = started.textState();
        if (base.defect() != null) {
            return new Result(started, base.defect(), null);
        }
        if (text.fontSize() == null) {
            // hasFont() can be true without a size: an ExtGState whose Font array held the reference
            // and no number. Table 5.2 (S. 398) wants font AND size, and without T_fs the formula
            // has no factor - so the codes are shown, and the pen is not moved.
            return new Result(started, "the font in effect carries no size: "
                    + text.missingFontNote() + ", and without T_fs the displacement of 5.3.3, S. 410"
                    + " has no factor to multiply the width by", null);
        }

        PdfTextWidths.Font font = widths == null ? null
                : widths.fontFor(text.fontResourceName(), text.fontObjNum());
        Accumulator run = new Accumulator(text, font, font == null ? null : font.codeBytes(),
                wantGlyphs, maxDetails);
        run.consume(stack.fromEnd(0));
        Advance advance = run.finish(text);

        PdfGraphicsState next = started;
        if (advance.codes() > 0 || !advance.elements().isEmpty()) {
            next = started.withTextState(
                    text.advanceBy(advance.movedTx(), advance.movedTy()));
        }
        return new Result(next, null, advance);
    }

    /**
     * One operand's worth of arithmetic. Mutable on purpose: a showing operator is read once, and a
     * record that accumulated twelve fields would only hide that.
     */
    private static final class Accumulator {
        private final PdfTextState text;
        private final PdfTextWidths.Font font;
        private final Integer codeBytes;
        private final boolean wantGlyphs;
        private final int maxDetails;

        private double tx;
        private double ty;
        private long codes;
        private long codesWithoutWidth;
        private boolean computable = true;
        private boolean detailsCut;
        private String note;
        private int detailIndex;
        /**
         * How many elements this operand has consumed, cut or not — the number a code's
         * {@link Glyph#piece()} carries (D9a). Counted rather than read off {@code elements.size()}
         * because a cut list must not silently renumber the pieces after the cut.
         */
        private int elementIndex;
        /**
         * The {@code TJ} number written before the element that comes next, or null when the last
         * element was a string. That is what makes {@code kernBefore} a fact about one code and not
         * about a piece: the number displaces the pen once, in front of the string that follows it.
         */
        private Double pendingKern;
        private final LinkedHashSet<String> sources = new LinkedHashSet<>();
        private final List<Element> elements = new ArrayList<>();
        private final List<Glyph> glyphs = new ArrayList<>();

        Accumulator(PdfTextState text, PdfTextWidths.Font font, Integer codeBytes,
                    boolean wantGlyphs, int maxDetails) {
            this.text = text;
            this.font = font;
            this.codeBytes = codeBytes;
            this.wantGlyphs = wantGlyphs;
            this.maxDetails = maxDetails;
        }

        /** The string or the array the operator was given — its last operand (Table 5.6, S. 407). */
        void consume(Object operand) {
            if (operand instanceof PdfString string) {
                showString(string);
                return;
            }
            if (operand instanceof List<?> array) {
                for (Object item : array) {
                    if (item instanceof PdfString string) {
                        showString(string);
                    } else if (item instanceof Number number) {
                        adjust(number.doubleValue());
                    }
                    // Anything else in the array (a name, a dictionary, null) is the file's mistake:
                    // the listing has always shown it, the registry has said what it thinks of the
                    // count, and it displaces nothing - the only claim this module can make there.
                }
                return;
            }
            if (operand != null) {
                note = "the operand of the showing operator is " + PdfParser.kindOf(operand)
                        + " instead of a string or an array, so no code can be read from it";
            }
        }

        /** One number of a {@code TJ} array: a zero-width glyph of the formula, and nothing else. */
        private void adjust(double number) {
            // (0 − n/1000) × T_fs × T_h, and the same without T_h when the mode is vertical (S. 410).
            double dx = -(number / WIDTH_BASE) * fontSize() * text.horizontalScaling();
            Double dy = vertical() ? -(number / WIDTH_BASE) * fontSize() : null;
            tx += dx;
            if (dy != null) {
                ty += dy;
            }
            if (!elementsFull()) {
                elements.add(new Element(true, number, 0L, dx, dy, true));
            }
            // A number is one element of the operand and it is the number the next code stands
            // behind - both counted whether or not the list still prints (D9a).
            elementIndex++;
            pendingKern = number;
        }

        private void showString(PdfString string) {
            byte[] data = string.bytes() == null ? new byte[0] : string.bytes();
            if (codeBytes == null) {
                // 5.6.4: an encoding that cannot say where a code ends leaves the string uncut. The
                // codes then have no width either, so the whole string is one element that cannot be
                // measured - and "0 per byte" would be a made-up layout.
                computable = false;
                if (note == null) {
                    note = "the encoding of this font does not say how wide a code is, so the string"
                            + " cannot be cut into codes and its advance is not computable"
                            + " (5.6.4, S. 442/449)";
                }
                // The operand still holds this piece, and the number before it still stood before it.
                elementIndex++;
                pendingKern = null;
                return;
            }
            double sum = 0;
            double sumY = 0;
            boolean elementComputable = true;
            int read = 0;
            // D9a: these codes are one piece of the operand, and a number written in front of the
            // piece displaces the pen exactly once - before its first code, not before each of them.
            int piece = elementIndex;
            Double pieceKern = pendingKern;
            for (int offset = 0; offset + codeBytes <= data.length; offset += codeBytes) {
                long code = PdfTextDecoder.codeValue(data, offset, codeBytes);
                codes++;
                read++;
                PdfGlyphMapper.Width width = font == null
                        ? new PdfGlyphMapper.Width(null, "none") : font.widthOf(code);
                Number named = width == null ? null : width.width();
                String source = width == null || width.source() == null ? "none" : width.source();
                Double w0 = named == null ? null : named.doubleValue() / WIDTH_BASE;
                if (named != null) {
                    sources.add(source);
                } else {
                    codesWithoutWidth++;
                    computable = false;
                    elementComputable = false;
                }
                // The two spacings ADD, they do not compete (S. 410):
                //     t_x = ((w0 - T_j/1000) x T_fs + T_c + T_w) x T_h
                // T_c reaches every glyph of the string - "when the glyph for each character in the
                // string is rendered" (S. 398) - and T_w is merely restricted to the single-byte
                // code 32 (S. 399: "applied to every occurrence of the single-byte character code
                // 32 ... does not apply to occurrences of the byte value 32 in multiple-byte
                // codes"). The reading this class first carried ("T_w replaces T_c for code 32")
                // confused that restriction for a substitution and dropped T_c from the step over
                // every space, so each later code of the item stood exactly T_c x T_h short:
                // measured in 14122 space cases (docs/extern/pdf_reference17.pdf S. 406-412 and the
                // z/Architecture PoO) with the difference exactly T_c x T_h in all of them, up to
                // 145,24 pt where a producer pushes a token into the next column with T_c
                // (PoO S. 2048, " B" at 18.2006 Tc), and with the step turning NEGATIVE where
                // T_w cancels T_c, which is how the figure rulers place their digits
                // (PoO S. 316 offset 1601: 10.4041 Tc, -10.4021 Tw). See
                // docs/js/pdf-text-advance-plan.md.
                double spacing = text.characterSpacing()
                        + (code == SPACE_CODE && codeBytes == 1 ? text.wordSpacing() : 0);
                Double dx = w0 == null ? null
                        : (w0 * fontSize() + spacing) * text.horizontalScaling();
                Double dy = null;
                if (vertical()) {
                    PdfFontWidths.Vertical metrics = font == null ? null : font.verticalOf(code);
                    Number w1 = metrics == null ? null : metrics.w1y();
                    dy = w1 == null ? null : (w1.doubleValue() / WIDTH_BASE) * fontSize() + spacing;
                    if (w1 == null) {
                        elementComputable = false;
                        computable = false;
                    }
                }
                // This code's own place in the line: the pen as it stands when the code is painted,
                // i.e. S. 410's running sum of every displacement before it - the TJ numbers of the
                // pieces between included, which is exactly what a reader adding widths would miss.
                double codePenX = tx;
                double codePenY = ty;
                if (dx != null) {
                    sum += dx;
                    tx += dx;
                }
                if (dy != null) {
                    sumY += dy;
                    ty += dy;
                }
                if (wantGlyphs && !glyphsFull()) {
                    glyphs.add(new Glyph(detailIndex,
                            PdfTextDecoder.codeHex(data, offset, codeBytes), code, named, source,
                            dx, dy, codePenX, codePenY, piece, read == 1 ? pieceKern : null));
                }
                detailIndex++;
            }
            int leftover = data.length - read * codeBytes;
            if (leftover > 0) {
                // Fewer bytes than one code: PdfTextDecoder calls this a tail and names the two
                // readings. They displace nothing, and the answer says that instead of dropping them.
                computable = false;
                elementComputable = false;
                if (note == null) {
                    note = PdfTextDecoder.tailSentence(data.length, codeBytes, leftover)
                            + "; the leftover bytes displace nothing";
                }
            }
            if (!elementsFull()) {
                elements.add(new Element(false, null, read,
                        elementComputable ? sum : null,
                        vertical() ? (elementComputable ? sumY : null) : null, elementComputable));
            }
            elementIndex++;
            pendingKern = null;
        }

        /** Whether the displacement uses the vertical half of the metrics (WMode 1, S. 440). */
        private boolean vertical() {
            return font != null && font.vertical();
        }

        /** {@code T_fs} as written; the caller has already refused a state without it. */
        private double fontSize() {
            return text.fontSize();
        }

        /**
         * Whether the {@code elements} list reached the detail cap (and the fact recorded once).
         *
         * <p>
         * D9a split the one cap of D5 into the two lists it bounds. Until then a long string cut
         * its own <em>element</em> out of the answer as soon as its 200 codes had filled the glyph
         * list, and a {@code glyphs} entry then pointed at a piece the answer did not print — the
         * cross-reference {@link Glyph#piece()} is the whole point of, so the two budgets are
         * counted apart. The cap per list is the same number ({@link #MAX_DETAILS_PER_OPERATOR}).
         * </p>
         */
        private boolean elementsFull() {
            return full(elements.size());
        }

        /** Whether the {@code glyphs} list reached the same cap. */
        private boolean glyphsFull() {
            return full(glyphs.size());
        }

        /** One cap, two lists: reached means listed no more, and said so once. */
        private boolean full(int listed) {
            if (maxDetails <= 0 || listed < maxDetails) {
                return false;
            }
            detailsCut = true;
            return true;
        }

        Advance finish(PdfTextState showState) {
            if (note == null && font == null) {
                note = text.fontObjNum() != null
                        ? "the font of this operand is an ExtGState reference (object "
                          + text.fontObjNum() + ") that this walk was given no way to read, so its"
                          + " displacement is not computable"
                        : "the resources do not define " + text.fontResourceName()
                          + ", so the operand has no font to take widths from and its displacement"
                          + " is not computable";
                computable = false;
            }
            return new Advance(showState, computable ? tx : null, computable ? ty : null, tx, ty,
                    codes, codesWithoutWidth, computable, List.copyOf(sources),
                    Collections.unmodifiableList(new ArrayList<>(elements)),
                    Collections.unmodifiableList(new ArrayList<>(glyphs)), detailsCut, note);
        }
    }
}
