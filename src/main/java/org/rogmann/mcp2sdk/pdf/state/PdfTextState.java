package org.rogmann.mcp2sdk.pdf.state;

import org.rogmann.mcp2sdk.pdf.PdfException;
import org.rogmann.mcp2sdk.pdf.geo.PdfMatrix;

import java.util.List;
import java.util.Locale;

/**
 * The <em>text state</em> - the nine parameters of Table 5.1 (PDF 1.7 5.2, S. 397) plus the two
 * matrices a text object keeps (5.3, S. 405: "three additional text-related parameters are defined
 * only within a text object: T_m, the text matrix; T_lm, the text line matrix; and T_rm, the text
 * rendering matrix").
 * <p>
 * This is where the module stops reading {@code Tm} as "the two numbers before the last" - what
 * {@code PdfContentStream.textInternal()} does today to decide when a line break belongs between
 * two strings - and starts holding the matrix the specification defines. That matters because the
 * check document writes {@code 1 0 0 -1 0 32.64 Tm} on a CTM that had already folded the page's y
 * axis: measured over 876 pages, <b>all 17 731</b> {@code Tm} operands have {@code d < 0}, and all
 * of them sit on a folded CTM. A number read out of that matrix is only right if the matrix is
 * composed, and a {@code Höhe − y} on top of it is right 30 times out of 58 802.
 * </p>
 * <p>
 * Instances are immutable, every operator returns a new one, and the transition methods are the
 * specification's own assignments - each carries the printed formula, because the direction of the
 * matrix product is the one thing here that must not be guessed.
 * </p>
 *
 * <h2>Lifetime, per the specification</h2>
 *
 * <ul>
 *   <li>Parameters set outside a text object survive it: "The text state operators can appear
 *       outside text objects, and the values they set are retained across text objects in a single
 *       content stream. Like other graphics state parameters, these parameters are initialized to
 *       their default values at the beginning of each page" (S. 397). They are therefore part of
 *       the {@link PdfGraphicsState graphics state} and saved by {@code q}.</li>
 *   <li>{@code BT} "initializing the text matrix, T_m, and the text line matrix, T_lm, to the
 *       identity matrix" (Table 5.4, S. 405) - <b>only those two</b>, so a {@code Tz} set before
 *       the {@code BT} still stands.</li>
 *   <li>{@code ET} ends the object, "discarding the text matrix" (same table). Both matrices go
 *       back to the identity here; see {@link #atTextObjectEnd()} for why that is the honest
 *       reading of one word.</li>
 *   <li>{@code Tf} has no default: "There is no initial value for either font or size; they must be
 *       specified explicitly by using Tf before any text is shown" (Table 5.2, S. 398). So both
 *       start {@code null} and {@link #missingFontNote()} is the sentence a show operator without a
 *       font gets - the failure mode of a consumer that assumes a font.</li>
 * </ul>
 */
public final class PdfTextState {

    /** {@code T_h} is {@code scale ÷ 100} (Table 5.2, S. 398: "Initial value: 100 (normal width)"). */
    public static final double INITIAL_SCALE_PERCENT = 100.0;

    /** Table 5.3, S. 402: "Fill text." */
    public static final int MODE_FILL = 0;
    /** Table 5.3, S. 402: "Stroke text." */
    public static final int MODE_STROKE = 1;
    /** Table 5.3, S. 402: "Fill, then stroke text." */
    public static final int MODE_FILL_THEN_STROKE = 2;
    /** Table 5.3, S. 402: "Neither fill nor stroke text (invisible)." */
    public static final int MODE_INVISIBLE = 3;
    /** Table 5.3, S. 402: "Fill text and add to path for clipping." */
    public static final int MODE_FILL_CLIP = 4;
    /** Table 5.3, S. 402: "Stroke text and add to path for clipping." */
    public static final int MODE_STROKE_CLIP = 5;
    /** Table 5.3, S. 402: "Fill, then stroke text and add to path for clipping." */
    public static final int MODE_FILL_STROKE_CLIP = 6;
    /** Table 5.3, S. 402: "Add text to path for clipping." */
    public static final int MODE_CLIP_ONLY = 7;

    /** The eight modes' names, indexed by the {@code Tr} operand. */
    private static final List<String> MODE_NAMES = List.of("fill", "stroke", "fill-then-stroke",
            "invisible", "fill-and-clip", "stroke-and-clip", "fill-stroke-and-clip", "clip-only");

    /** Resource name of the current font as the content stream names it ({@code "/F3"}), or null. */
    private final String fontResourceName;
    /** Object number of the current font when an {@code ExtGState} set it, or null (Table 4.8, S. 221). */
    private final Integer fontObjNum;
    /** {@code T_fs} - null means no {@code Tf} and no {@code gs} font has run yet (S. 398). */
    private final Double fontSize;
    /** What set the font: {@code Tf} or {@code ExtGState} - the two differ in how the font is named. */
    private final String fontSetBy;
    /** {@code T_c}, unscaled text space units (S. 398: "Initial value: 0"). */
    private final double characterSpacing;
    /** {@code T_w}, unscaled text space units (S. 398: "Initial value: 0"). */
    private final double wordSpacing;
    /** {@code T_z} as written - the operand of {@code Tz}; {@code T_h} is this ÷ 100. */
    private final double scalePercent;
    /** {@code T_l}, unscaled text space units (S. 398: "Initial value: 0"; used by {@code T*}, {@code '}, {@code "}). */
    private final double leading;
    /** {@code T_rise}, unscaled text space units (S. 398: "Initial value: 0"). */
    private final double textRise;
    /** {@code T_mode}, the {@code Tr} operand (S. 398: "Initial value: 0"). */
    private final int renderingMode;
    /**
     * {@code T_k}, the text knockout flag - only ever set by the {@code TK} entry of an
     * {@code ExtGState} (S. 397 Note: "There is no specific operator for setting this parameter").
     * Null while no dictionary named it; the specification's entry at S. 223 prints no default in
     * the text this module read, so the module keeps "not named" instead of asserting one.
     */
    private final Boolean textKnockout;
    /** {@code T_m}. */
    private final PdfMatrix textMatrix;
    /** {@code T_lm} - "captures the value of T_m at the beginning of a line of text" (S. 407). */
    private final PdfMatrix textLineMatrix;

    private PdfTextState(String fontResourceName, Integer fontObjNum, Double fontSize,
                         String fontSetBy, double characterSpacing, double wordSpacing,
                         double scalePercent, double leading, double textRise, int renderingMode,
                         Boolean textKnockout, PdfMatrix textMatrix, PdfMatrix textLineMatrix) {
        this.fontResourceName = fontResourceName;
        this.fontObjNum = fontObjNum;
        this.fontSize = fontSize;
        this.fontSetBy = fontSetBy;
        this.characterSpacing = characterSpacing;
        this.wordSpacing = wordSpacing;
        this.scalePercent = scalePercent;
        this.leading = leading;
        this.textRise = textRise;
        this.renderingMode = renderingMode;
        this.textKnockout = textKnockout;
        this.textMatrix = textMatrix;
        this.textLineMatrix = textLineMatrix;
    }

    /**
     * The state at the beginning of a page: the initial values of Table 5.2 (S. 398) - no font,
     * {@code T_h} = 1, everything else 0 - and {@code T_m} = {@code T_lm} = identity ("the initial
     * value for T_m and T_lm is the identity matrix, {@code [1 0 0 1 0 0]}", S. 406).
     */
    public static PdfTextState initial() {
        return new PdfTextState(null, null, null, null, 0, 0, INITIAL_SCALE_PERCENT, 0, 0,
                MODE_FILL, null, PdfMatrix.identity(), PdfMatrix.identity());
    }

    // ===================================================================
    // The text object's two ends
    // ===================================================================

    /**
     * {@code BT} - only the two matrices are initialised. Everything else the stream set before the
     * {@code BT} - a {@code Tz 150}, a {@code Tc 2}, a font - survives, which is why the text state
     * is not rebuilt here (S. 397: the parameters "are retained across text objects in a single
     * content stream").
     */
    public PdfTextState atTextObjectStart() {
        if (textMatrix.isIdentity() && textLineMatrix.isIdentity()) {
            return this;
        }
        return copyWithMatrices(PdfMatrix.identity(), PdfMatrix.identity());
    }

    /**
     * {@code ET} - "End a text object, discarding the text matrix" (Table 5.4, S. 405).
     * <p>
     * "Discarding" is one word and two readings: back to the identity, or undefined until the next
     * {@code BT} re-initialises it (S. 406: "the initial value for T_m and T_lm is the identity
     * matrix"). This module takes the first reading and takes it for both matrices: leaving
     * {@code T_lm} behind would let a {@code Td} written after the {@code ET} - legal, since the
     * text state operators "are retained across text objects" (S. 397) - position text against a
     * line whose object is gone. No file in the corpus can tell the two readings apart, because
     * every {@code BT} that follows re-initialises both matrices anyway; the choice is therefore
     * recorded here rather than proven by a document.
     * </p>
     */
    public PdfTextState atTextObjectEnd() {
        return copyWithMatrices(PdfMatrix.identity(), PdfMatrix.identity());
    }

    private PdfTextState copyWithMatrices(PdfMatrix newTm, PdfMatrix newTlm) {
        Fields f = fields();
        f.textMatrix = newTm;
        f.textLineMatrix = newTlm;
        return f.build();
    }

    /**
     * The field bag. Every setter-shaped method goes through it, because a method that forwards
     * twelve positional arguments can hand on the old one - and one of them did exactly that while
     * this class was written ({@code withWordSpacing} passed {@code wordSpacing} instead of
     * {@code wordSpace}, so {@code Tw} silently changed nothing). A named field cannot make that
     * mistake, and the test that caught it ({@code PdfTextStateTest}) stays as the reason.
     */
    private static final class Fields {
        String fontResourceName;
        Integer fontObjNum;
        Double fontSize;
        String fontSetBy;
        double characterSpacing;
        double wordSpacing;
        double scalePercent;
        double leading;
        double textRise;
        int renderingMode;
        Boolean textKnockout;
        PdfMatrix textMatrix;
        PdfMatrix textLineMatrix;

        PdfTextState build() {
            return new PdfTextState(fontResourceName, fontObjNum, fontSize, fontSetBy,
                    characterSpacing, wordSpacing, scalePercent, leading, textRise, renderingMode,
                    textKnockout, textMatrix, textLineMatrix);
        }
    }

    private Fields fields() {
        Fields f = new Fields();
        f.fontResourceName = fontResourceName;
        f.fontObjNum = fontObjNum;
        f.fontSize = fontSize;
        f.fontSetBy = fontSetBy;
        f.characterSpacing = characterSpacing;
        f.wordSpacing = wordSpacing;
        f.scalePercent = scalePercent;
        f.leading = leading;
        f.textRise = textRise;
        f.renderingMode = renderingMode;
        f.textKnockout = textKnockout;
        f.textMatrix = textMatrix;
        f.textLineMatrix = textLineMatrix;
        return f;
    }

    // ===================================================================
    // Text state operators (Table 5.2, S. 398)
    // ===================================================================

    /**
     * {@code font size Tf} - "Set the text font, T_f, to font and the text font size, T_fs, to
     * size. font is the name of a font resource in the Font subdictionary of the current resource
     * dictionary" (S. 398). The name is kept as the stream wrote it; resolving it to an object
     * number is {@code PdfResources}' job, and the answer stays the slash form (D0b rule 2).
     *
     * @param size the size operand, kept exactly as written - Table 5.2 calls it "a number
     *             representing a scale factor" and prints no restriction on the sign, so nothing
     *             here corrects one. Mirroring in the check document comes from elsewhere: every
     *             one of its 17 731 {@code Tm} operands folds the y axis itself
     */
    public PdfTextState withFont(String resourceName, double size) {
        Fields f = fields();
        f.fontResourceName = resourceName;
        f.fontObjNum = null;
        f.fontSize = size;
        f.fontSetBy = "Tf";
        return f.build();
    }

    /**
     * The {@code Font} entry of a graphics state parameter dictionary: "An array of the form
     * {@code [font size]}, where font is an indirect reference to a font dictionary and size is a
     * number expressed in text space units … however, the first operand is an indirect object
     * reference instead of a resource name" (Table 4.8, S. 221).
     *
     * @param objNum object number the entry references, or null when the file wrote the dictionary
     *               inline (then {@code pdf.fonts()} still knows the size, only not the address)
     * @param setBy  what set it, for the answer's provenance ({@code "ExtGState"})
     */
    public PdfTextState withFontReference(Integer objNum, Double size, String setBy) {
        Fields f = fields();
        f.fontResourceName = null;
        f.fontObjNum = objNum;
        f.fontSize = size;
        f.fontSetBy = setBy;
        return f.build();
    }

    /** {@code charSpace Tc} (S. 398: "expressed in unscaled text space units"). */
    public PdfTextState withCharacterSpacing(double charSpace) {
        Fields f = fields();
        f.characterSpacing = charSpace;
        return f.build();
    }

    /**
     * {@code wordSpace Tw} - "Word spacing works the same way as character spacing but applies only
     * to the space character, code 32" (5.2.2, S. 399). The note on the same page is the part a
     * reader gets wrong: it is applied "to every occurrence of the single-byte character code 32 …
     * when using a simple font or a composite font that defines code 32 as a single-byte code. It
     * does not apply to occurrences of the byte value 32 in multiple-byte codes" - so D5 may not
     * count 32-bytes inside a two-byte CID code.
     */
    public PdfTextState withWordSpacing(double wordSpace) {
        Fields f = fields();
        f.wordSpacing = wordSpace;
        return f.build();
    }

    /**
     * {@code scale Tz} - "Set the horizontal scaling, T_h, to (scale ÷ 100)" (S. 398). The percent
     * is what the file wrote, {@link #horizontalScaling()} is what the displacement formula uses.
     */
    public PdfTextState withHorizontalScalePercent(double scale) {
        Fields f = fields();
        f.scalePercent = scale;
        return f.build();
    }

    /** {@code leading TL} - "used only by the T*, ' and '' operators" (S. 398). */
    public PdfTextState withLeading(double tl) {
        Fields f = fields();
        f.leading = tl;
        return f.build();
    }

    /** {@code rise Ts} - shifts the text rendering matrix, not the text matrix (S. 409). */
    public PdfTextState withRise(double rise) {
        Fields f = fields();
        f.textRise = rise;
        return f.build();
    }

    /**
     * {@code render Tr} - the rendering mode; {@link #renderingModeDefect()} reports an operand
     * outside 0..7 rather than clamping it.
     */
    public PdfTextState withRenderingMode(int render) {
        Fields f = fields();
        f.renderingMode = render;
        return f.build();
    }

    /** {@code TK} of a graphics state parameter dictionary (S. 223) - the only way to set it. */
    public PdfTextState withKnockout(Boolean knockout) {
        Fields f = fields();
        f.textKnockout = knockout;
        return f.build();
    }

    // ===================================================================
    // Text-positioning operators (Table 5.5, S. 406)
    // ===================================================================

    /**
     * {@code tx ty Td} - printed assignment: {@code T_m = T_lm = [1 0 0 1 tx ty] × T_lm}.
     * <p>
     * The product is read exactly in that order, which is why it is
     * {@code textLineMatrix.premultiply(PdfMatrix.translation(tx, ty))} and not the other way round:
     * the offset is in <em>unscaled text space units of the current line</em> and has to be applied
     * before the matrix that places that line.
     * </p>
     * <p>
     * <b>What this method is not:</b> the home of the leading sign. It is the {@code Td} assignment
     * and takes both operands as the stream wrote them; the negation that {@code T*} needs belongs to
     * {@link #nextLine()}, which is the only reader of {@link #leading()}. A minus here would move
     * {@code Td} and {@code TD} as well, and those two print their offset without one.
     * </p>
     */
    public PdfTextState moveToNextLine(double tx, double ty) {
        PdfMatrix line = textLineMatrix.premultiply(PdfMatrix.translation(tx, ty));
        return copyWithMatrices(line, line);
    }

    /**
     * {@code tx ty TD} - "As a side effect, this operator sets the leading parameter in the text
     * state. This operator has the same effect as the following code: {@code −ty TL; tx ty Td}"
     * (S. 406). Note the sign: the leading becomes the <em>negated</em> operand.
     * <p>
     * That negation exists for one reader: {@link #nextLine()}. A {@code TD} that stepped the line
     * down by 15 stores a leading of 15 precisely so that the following {@code T*} repeats that
     * step instead of undoing it - see there.
     * </p>
     */
    public PdfTextState moveToNextLineSettingLeading(double tx, double ty) {
        return withLeading(-ty).moveToNextLine(tx, ty);
    }

    /**
     * {@code a b c d e f Tm} - "Set the text matrix, T_m, and the text line matrix, T_lm … The
     * matrix specified by the operands is <b>not</b> concatenated onto the current text matrix, but
     * replaces it" (S. 406, emphasis theirs). This is the one text operator that does not compose.
     */
    public PdfTextState setMatrices(PdfMatrix operand) {
        return copyWithMatrices(operand, operand);
    }

    /**
     * {@code T*} - "Move to the start of the next line, using the text leading, {@code T_l}, in the
     * text state" (5.3.1, S. 406), and the leading is applied <b>negated</b>:
     * {@code T_m = T_lm = [1 0 0 1 0 -T_l] × T_lm}.
     *
     * <h2>Why the minus, from the reference itself</h2>
     *
     * <p>
     * 5.2.4 (S. 400) says what the parameter <em>is</em>: "It specifies the vertical distance between
     * the baselines of adjacent lines of text, as shown in Figure 5.9" — and Figure 5.9 draws that
     * distance from the upper baseline down to the next one ("This is 12-point text with 14.5-point
     * leading"). A distance to the line <em>below</em> cannot be applied as a positive step along an
     * axis that points upward: text space has its y axis up like default user space (4.2.1, S. 201),
     * so the next line sits at {@code -T_l}. That is also the only reading under which the two rows
     * of Table 5.5 fit each other: {@code TD} stores "{@code -ty TL}", so its own step
     * {@code (0, ty)} and the following line move {@code (0, -T_l)} = {@code (0, ty)} are the same
     * displacement — a paragraph written as one {@code TD} and a row of {@code T*}s (the pattern of
     * the check document: 27 486 {@code TD}, 68 087 {@code T*}, {@code TL} never) keeps its even
     * spacing. Without the minus, the {@code T*} walks back onto the line the {@code TD} just left.
     * </p>
     *
     * <p>
     * The direction is therefore decided in text space and by nothing else: a folded {@code T_m} or
     * a folded CTM turns the step on the sheet, and it turns the {@code TD} step with it, so the two
     * stay together either way. Whether {@code T_l} itself is positive or negative is the producer's
     * choice (5.2.4 prints no sign restriction, and {@code TD} writes a negative leading whenever it
     * stepped upward) — a negative leading moves the next line against the usual direction, and that
     * is what {@code TD 120 15} followed by {@code T*} asks for.
     * </p>
     *
     * <p>
     * Not here: the negation is <em>not</em> in {@link #moveToNextLine(double, double)}, which is the
     * {@code Td} assignment and is shared by {@code Td} and {@code TD} — those two take their
     * operands as written. One operator reads the leading back, one operator therefore negates it.
     * </p>
     */
    public PdfTextState nextLine() {
        return moveToNextLine(0, -leading);
    }

    /**
     * What a text-showing operator does to {@code T_m} after painting a glyph: 5.3.3, S. 410 prints
     * {@code T_m = [1 0 0 1 tx ty] × T_m}, and S. 407 says the show operators "update T_m (by
     * altering its e and f translation components)". {@code T_lm} is deliberately untouched - it
     * stays at the line's start, which is what makes {@code Td} mean "relative to the line".
     * <p>
     * The displacement itself - {@code t_x = ((w0 − T_j/1000) × T_fs + T_c + T_w) × T_h} and its
     * vertical twin - is the showing operator's arithmetic and belongs with the widths and the
     * kerning values (task D5); this method only applies the result.
     * </p>
     */
    public PdfTextState advanceBy(double tx, double ty) {
        return copyWithMatrices(textMatrix.premultiply(PdfMatrix.translation(tx, ty)),
                textLineMatrix);
    }

    // ===================================================================
    // Out of text space (5.3.3, S. 409)
    // ===================================================================

    /**
     * The first factor of the text rendering matrix - {@code [T_fs×T_h, 0, 0, T_fs, 0, T_rise]}:
     * "T_rm = [T_fs×T_h 0 0; 0 T_fs 0; 0 T_rise 1] × T_m × CTM" (S. 409). Reading the 3 × 3 back
     * into six numbers puts {@code T_fs×T_h} on x, {@code T_fs} on y and {@code T_rise} in
     * {@code f}, which is also why a rise moves the glyph without moving the baseline.
     *
     * @throws PdfException when no font size has been set - there is then no scale to build, and
     *                      Table 5.2 (S. 398) is explicit that {@code Tf} must have run
     */
    public PdfMatrix glyphScaleMatrix() {
        if (fontSize == null) {
            throw new PdfException(missingFontNote());
        }
        return PdfMatrix.of(fontSize * horizontalScaling(), 0, 0, fontSize, 0, textRise);
    }

    /** Text space → user space: {@code [T_fs×T_h 0 0 T_fs 0 T_rise] × T_m} (the S. 409 product without the CTM). */
    public PdfMatrix textToUserMatrix() {
        return textMatrix.premultiply(glyphScaleMatrix());
    }

    /**
     * The text rendering matrix {@code T_rm} itself - text space to <em>default user</em> space,
     * because in this module the CTM maps user to default user space and the page frame maps
     * default user to page space.
     *
     * @param ctm the CTM in effect where the glyph is painted
     */
    public PdfMatrix textRenderingMatrix(PdfMatrix ctm) {
        return textToUserMatrix().multiply(ctm);
    }

    // ===================================================================
    // Reading it
    // ===================================================================

    /** The font's resource name as the stream wrote it, or null when the font came from elsewhere or never came. */
    public String fontResourceName() {
        return fontResourceName;
    }

    /** The font's object number when an {@code ExtGState} named it by reference, else null. */
    public Integer fontObjNum() {
        return fontObjNum;
    }

    /** How the font arrived: {@code Tf}, {@code ExtGState}, or null when none has. */
    public String fontSetBy() {
        return fontSetBy;
    }

    /** Whether a font has been named at all - false means "the stream showed text without a font". */
    public boolean hasFont() {
        return fontResourceName != null || fontObjNum != null;
    }

    /** {@code T_fs} as written, or null when no font was set. */
    public Double fontSize() {
        return fontSize;
    }

    /** {@code T_c}. */
    public double characterSpacing() {
        return characterSpacing;
    }

    /** {@code T_w}. */
    public double wordSpacing() {
        return wordSpacing;
    }

    /** The {@code Tz} operand as written. */
    public double scalePercent() {
        return scalePercent;
    }

    /** {@code T_h} = {@code Tz ÷ 100} (S. 398). */
    public double horizontalScaling() {
        return scalePercent / 100.0;
    }

    /**
     * {@code T_fs × T_h} - the size a glyph occupies along x in text space, the first element of the
     * rendering matrix's scale factor (S. 409). {@code null} while no {@code Tf} has run: there is
     * then no size to scale, and answering 0 would let a caller multiply it into a coordinate.
     */
    public Double effectiveFontSize() {
        return fontSize == null ? null : fontSize * horizontalScaling();
    }

    /** {@code T_l}. */
    public double leading() {
        return leading;
    }

    /** {@code T_rise}. */
    public double textRise() {
        return textRise;
    }

    /** {@code T_mode}, the {@code Tr} operand. */
    public int renderingMode() {
        return renderingMode;
    }

    /** {@code T_k}, or null when no {@code TK} entry named it. */
    public Boolean textKnockout() {
        return textKnockout;
    }

    /** {@code T_m}. */
    public PdfMatrix textMatrix() {
        return textMatrix;
    }

    /** {@code T_lm}. */
    public PdfMatrix textLineMatrix() {
        return textLineMatrix;
    }

    /** The name of the rendering mode - {@code "invisible"} for {@code Tr 3}, {@code "clip-only"} for 7. */
    public String renderingModeName() {
        return renderingMode >= 0 && renderingMode < MODE_NAMES.size()
                ? MODE_NAMES.get(renderingMode)
                : "unknown-" + renderingMode;
    }

    /**
     * Whether the mode paints nothing - mode 3, "Neither fill nor stroke text (invisible)"
     * (Table 5.3, S. 402). The advance still counts: the glyphs are not drawn, but the pen still
     * moves, which is why a placement answer reports {@code visible: false} for a position that is
     * still a position.
     */
    public boolean isInvisible() {
        return renderingMode == MODE_INVISIBLE;
    }

    /**
     * Whether the mode adds the text to the clipping path - modes 4..7 (Table 5.3, S. 402, and the
     * note on S. 235: "In addition to path objects, text objects can also be used for clipping").
     */
    public boolean addsGlyphsToClip() {
        return renderingMode >= MODE_FILL_CLIP && renderingMode <= MODE_CLIP_ONLY;
    }

    /** A {@code Tr} operand outside the eight of Table 5.3, in one sentence; null when it is inside. */
    public String renderingModeDefect() {
        if (renderingMode >= 0 && renderingMode < MODE_NAMES.size()) {
            return null;
        }
        return "the text rendering mode is " + renderingMode + ", Table 5.3 (S. 402) defines 0 to 7";
    }

    /**
     * The sentence for a show operator that ran before any {@code Tf} - the specification's own
     * words on why there is nothing to map the codes with (Table 5.2, S. 398). Never null.
     */
    public String missingFontNote() {
        return "a text-showing operator ran with no font in effect - Table 5.2 (S. 398):"
                + " \"There is no initial value for either font or size; they must be specified"
                + " explicitly by using Tf before any text is shown\", so the codes of this string"
                + " have no font to be read by";
    }

    /** A short line for a state listing: font, size, and the parameters that differ from the defaults. */
    public String describe() {
        StringBuilder sb = new StringBuilder("text state: font ");
        sb.append(hasFont() ? (fontResourceName != null ? fontResourceName : "#" + fontObjNum)
                : "none");
        if (fontSize != null) {
            sb.append(" size ").append(PdfMatrix.format(fontSize));
        }
        sb.append(" Tm ").append(textMatrix);
        if (characterSpacing != 0) {
            sb.append(" Tc ").append(PdfMatrix.format(characterSpacing));
        }
        if (wordSpacing != 0) {
            sb.append(" Tw ").append(PdfMatrix.format(wordSpacing));
        }
        if (scalePercent != INITIAL_SCALE_PERCENT) {
            sb.append(" Tz ").append(PdfMatrix.format(scalePercent));
        }
        if (leading != 0) {
            sb.append(" TL ").append(PdfMatrix.format(leading));
        }
        if (textRise != 0) {
            sb.append(" Ts ").append(PdfMatrix.format(textRise));
        }
        if (renderingMode != MODE_FILL) {
            sb.append(" Tr ").append(renderingMode).append('(').append(renderingModeName())
                    .append(')');
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return describe();
    }

    /** The eight modes of Table 5.3, for a help text or a note that has to list them. */
    public static List<String> modeNames() {
        return MODE_NAMES;
    }

    /**
     * Whether a {@code Tr} operand is one of the eight.
     *
     * @param render the operand
     * @return true when Table 5.3 (S. 402) knows the mode
     */
    public static boolean isKnownMode(int render) {
        return render >= 0 && render < MODE_NAMES.size();
    }

    /**
     * The name of a rendering mode, for callers that only have the number.
     *
     * @param render the {@code Tr} operand
     * @return the name, or {@code unknown-n} in lower case
     */
    public static String modeName(int render) {
        return isKnownMode(render) ? MODE_NAMES.get(render)
                : ("unknown-" + render).toLowerCase(Locale.ROOT);
    }
}
