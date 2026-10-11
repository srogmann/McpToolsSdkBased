package org.rogmann.mcp2sdk.pdf.state;

import org.rogmann.mcp2sdk.pdf.PdfContentStream;
import org.rogmann.mcp2sdk.pdf.PdfException;
import org.rogmann.mcp2sdk.pdf.PdfName;
import org.rogmann.mcp2sdk.pdf.PdfReference;
import org.rogmann.mcp2sdk.pdf.geo.PdfMatrix;
import org.rogmann.mcp2sdk.pdf.geo.PdfPageSpace;
import org.rogmann.mcp2sdk.pdf.geo.PdfPoint;
import org.rogmann.mcp2sdk.pdf.geo.PdfRect;
import org.rogmann.mcp2sdk.pdf.geo.PdfSpace;
import org.rogmann.mcp2sdk.pdf.geo.PdfVector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The <em>graphics state</em>: the parameters a PDF consumer carries along while it reads a content
 * stream (PDF 1.7 4.3 "Graphics State", S. 210-223). Immutable, one instance per state, every
 * operator that changes one parameter returns a new instance - which is what lets
 * {@link PdfStateStack#save} push <em>the reference</em> for {@code q} instead of copying anything
 * ("The q operator pushes a copy of the entire graphics state onto the stack", Table 4.7, S. 219:
 * a copy that cannot change is the same thing as the original, and the identity of the object is
 * what {@code PdfStateStackTest} asserts).
 *
 * <h2>What is in it</h2>
 *
 * The device-independent parameters of Table 4.2 (S. 210/211) - CTM, clipping path, colour space
 * and colour for stroking and for non-stroking, the text state, line width, cap, join, miter limit,
 * dash pattern, rendering intent, stroke adjustment, blend mode - plus the three device-dependent
 * ones the plan named and {@code gs} can set anyway (soft mask, alpha constants, alpha source) and
 * the overprint pair. The rest of Table 4.3 (black generation, undercolor removal, transfer
 * functions, halftone, smoothness, flatness) is device calibration: flatness and smoothness are
 * kept because they are numbers with an explicit range, the function-valued ones are
 * <em>named</em> as uninterpreted rather than kept, because showing a function object adds nothing a
 * caller can use and {@code pdf.object} already shows it (decision 11).
 *
 * <h2>What is not in it</h2>
 *
 * <b>The path.</b> "In PDF (unlike PostScript), the current path is not part of the graphics state
 * and is not saved and restored along with the other graphics state parameters" (4.4.1, S. 226) - so
 * this class has no path field and {@link PdfPath} lives beside it, which is the one difference
 * between a PDF reader and a PostScript one, and the difference the requirement test of the plan
 * gets wrong. <b>The page frame.</b> The MediaBox, {@code Rotate} and {@code UserUnit} do not change
 * from operator to operator, so they are not state: see {@link PdfPageSpace}, and
 * {@link #userToPageMatrix(PdfPageSpace)} for how the two halves join. <b>Colour semantics.</b> The
 * colour and colour space parameters are kept as the operands that set them, in the structure of
 * {@link PdfColour} (numbers, the space the operator named, the byte that wrote them) and never
 * decoded (decision 11, form decided by decision 16): they have to leave the operand stack, or
 * everything after them is misaligned, and a colour this module cannot verify it understands would
 * still be printed as if it could. Halftone, transfer and the rest of Table 4.3 stay named-as-raw
 * for the same reason.
 *
 * <h2>The CTM of this module</h2>
 *
 * Table 4.2 calls the CTM the matrix that maps user coordinates to <em>device</em> coordinates, and
 * the reference is explicit that a consumer may adjust it to the device's resolution (S. 201).
 * A module without a device has no device space to map into, so the CTM here maps user space into
 * <b>default user space</b> and starts at the identity; the page space is reached through
 * {@link #userToPageMatrix(PdfPageSpace)}. Every number this class produces names its space, so
 * the substitution is visible rather than smuggled in.
 */
public final class PdfGraphicsState {

    /** Table 4.2, S. 211: line width "Initial value: 1.0". */
    public static final double INITIAL_LINE_WIDTH = 1.0;

    /** Table 4.2, S. 211: line cap "Initial value: 0, for square butt caps". */
    public static final int INITIAL_LINE_CAP = 0;

    /** Table 4.2, S. 211: line join "Initial value: 0, for mitered joins". */
    public static final int INITIAL_LINE_JOIN = 0;

    /** Table 4.2, S. 211: miter limit "Initial value: 10.0, for a miter cutoff below approximately 11.5 degrees". */
    public static final double INITIAL_MITER_LIMIT = 10.0;

    /** Table 4.2, S. 211: rendering intent "Initial value: RelativeColorimetric". */
    public static final String INITIAL_RENDERING_INTENT = "/RelativeColorimetric";

    /** Table 4.3, S. 213: flatness "Initial value: 1.0". */
    public static final double INITIAL_FLATNESS = 1.0;

    /** Table 4.2, S. 211: stroke adjustment "Initial value: false". */
    public static final boolean INITIAL_STROKE_ADJUSTMENT = false;

    /** Table 4.3, S. 212: alpha constants "Initial value: 1.0". */
    public static final double INITIAL_ALPHA = 1.0;

    /** Table 4.3, S. 212: alpha source "Initial value: false". */
    public static final boolean INITIAL_ALPHA_SOURCE = false;

    /** Table 4.2, S. 211: blend mode "Initial value: Normal". */
    public static final String INITIAL_BLEND_MODE = "/Normal";

    /** Table 4.3, S. 212: soft mask "Initial value: None". */
    public static final String INITIAL_SOFT_MASK = "/None";

    /** Table 4.3, S. 212: overprint mode "Initial value: 0". */
    public static final int INITIAL_OVERPRINT_MODE = 0;

    /** The keys of Table 4.8 (S. 220-223) this class reads, in the table's order. */
    public static final List<String> GS_APPLIED_KEYS = List.of("LW", "LC", "LJ", "ML", "D", "RI",
            "FL", "SM", "SA", "AIS", "CA", "ca", "OP", "op", "OPM", "Font", "TK", "BM", "SMask");

    /**
     * The keys of Table 4.8 this class names but does not interpret: device calibration functions and
     * the halftone (decision 11/13 - recognised, swallowed, reported).
     */
    public static final List<String> GS_UNINTERPRETED_KEYS = List.of("BG", "BG2", "UCR", "UCR2",
            "TR", "TR2", "HT", "IP");

    // --- the parameters -------------------------------------------------------------------
    private final PdfMatrix ctm;
    private final PdfClipPath clip;
    private final PdfTextState textState;
    private final double lineWidth;
    private final int lineCap;
    private final int lineJoin;
    private final double miterLimit;
    private final Dash dash;
    private final String renderingIntent;
    private final boolean strokeAdjustment;
    private final double flatness;
    private final Double smoothness;
    private final Raw blendMode;
    private final Raw softMask;
    private final double alphaStroking;
    private final double alphaNonStroking;
    private final boolean alphaSource;
    private final boolean overprintStroke;
    private final boolean overprintNonStroke;
    private final int overprintMode;
    private final PdfColour.Space strokeColourSpace;
    private final PdfColour.Space nonStrokeColourSpace;
    private final PdfColour strokeColour;
    private final PdfColour nonStrokeColour;

    private PdfGraphicsState(Fields f) {
        this.ctm = f.ctm;
        this.clip = f.clip;
        this.textState = f.text;
        this.lineWidth = f.lineWidth;
        this.lineCap = f.lineCap;
        this.lineJoin = f.lineJoin;
        this.miterLimit = f.miterLimit;
        this.dash = f.dash;
        this.renderingIntent = f.renderingIntent;
        this.strokeAdjustment = f.strokeAdjustment;
        this.flatness = f.flatness;
        this.smoothness = f.smoothness;
        this.blendMode = f.blendMode;
        this.softMask = f.softMask;
        this.alphaStroking = f.alphaStroking;
        this.alphaNonStroking = f.alphaNonStroking;
        this.alphaSource = f.alphaSource;
        this.overprintStroke = f.overprintStroke;
        this.overprintNonStroke = f.overprintNonStroke;
        this.overprintMode = f.overprintMode;
        this.strokeColourSpace = f.strokeColourSpace;
        this.nonStrokeColourSpace = f.nonStrokeColourSpace;
        this.strokeColour = f.strokeColour;
        this.nonStrokeColour = f.nonStrokeColour;
    }

    /** The field bag - the one place that knows all fields, so the {@code with…} methods stay short. */
    private static final class Fields {
        PdfMatrix ctm;
        PdfClipPath clip;
        PdfTextState text;
        double lineWidth;
        int lineCap;
        int lineJoin;
        double miterLimit;
        Dash dash;
        String renderingIntent;
        boolean strokeAdjustment;
        double flatness;
        Double smoothness;
        Raw blendMode;
        Raw softMask;
        double alphaStroking;
        double alphaNonStroking;
        boolean alphaSource;
        boolean overprintStroke;
        boolean overprintNonStroke;
        int overprintMode;
        PdfColour.Space strokeColourSpace;
        PdfColour.Space nonStrokeColourSpace;
        PdfColour strokeColour;
        PdfColour nonStrokeColour;

        PdfGraphicsState build() {
            return new PdfGraphicsState(this);
        }
    }

    private Fields fields() {
        Fields f = new Fields();
        f.ctm = ctm;
        f.clip = clip;
        f.text = textState;
        f.lineWidth = lineWidth;
        f.lineCap = lineCap;
        f.lineJoin = lineJoin;
        f.miterLimit = miterLimit;
        f.dash = dash;
        f.renderingIntent = renderingIntent;
        f.strokeAdjustment = strokeAdjustment;
        f.flatness = flatness;
        f.smoothness = smoothness;
        f.blendMode = blendMode;
        f.softMask = softMask;
        f.alphaStroking = alphaStroking;
        f.alphaNonStroking = alphaNonStroking;
        f.alphaSource = alphaSource;
        f.overprintStroke = overprintStroke;
        f.overprintNonStroke = overprintNonStroke;
        f.overprintMode = overprintMode;
        f.strokeColourSpace = strokeColourSpace;
        f.nonStrokeColourSpace = nonStrokeColourSpace;
        f.strokeColour = strokeColour;
        f.nonStrokeColour = nonStrokeColour;
        return f;
    }

    /**
     * The state a page starts in: the initial values of Tables 4.2 and 4.3, the given clip and the
     * text state of {@link PdfTextState#initial()}.
     *
     * @param ctm  the CTM in effect at the first operator - the identity means "user space is still
     *             the page's own default user space"; a producer that opens its stream with
     *             {@code 1 0 0 -1 0 792 cm} has already replaced it before the first operator that
     *             this is asked about
     * @param clip the page's initial clip, usually {@link PdfClipPath#initial} of the page box, or
     *             {@link PdfClipPath#unknown()} when the page geometry was not read
     */
    public static PdfGraphicsState initial(PdfMatrix ctm, PdfClipPath clip) {
        Fields f = new Fields();
        f.ctm = ctm == null ? PdfMatrix.identity() : ctm;
        f.clip = clip == null ? PdfClipPath.unknown() : clip;
        f.text = PdfTextState.initial();
        f.lineWidth = INITIAL_LINE_WIDTH;
        f.lineCap = INITIAL_LINE_CAP;
        f.lineJoin = INITIAL_LINE_JOIN;
        f.miterLimit = INITIAL_MITER_LIMIT;
        f.dash = Dash.solid();
        f.renderingIntent = INITIAL_RENDERING_INTENT;
        f.strokeAdjustment = INITIAL_STROKE_ADJUSTMENT;
        f.flatness = INITIAL_FLATNESS;
        f.smoothness = null;
        // Names without the slash, as PdfName stores them; toString() puts the slash back, which is
        // the form every result of this module shows.
        f.blendMode = new Raw("initial", List.of(new PdfName("Normal")));
        f.softMask = new Raw("initial", List.of(new PdfName("None")));
        f.alphaStroking = INITIAL_ALPHA;
        f.alphaNonStroking = INITIAL_ALPHA;
        f.alphaSource = INITIAL_ALPHA_SOURCE;
        f.overprintStroke = false;
        f.overprintNonStroke = false;
        f.overprintMode = INITIAL_OVERPRINT_MODE;
        f.strokeColourSpace = null;
        f.nonStrokeColourSpace = null;
        f.strokeColour = null;
        f.nonStrokeColour = null;
        return f.build();
    }

    /** The state at the very beginning of a page, with nothing transformed yet. */
    public static PdfGraphicsState initial(PdfClipPath clip) {
        return initial(PdfMatrix.identity(), clip);
    }

    /**
     * The state at the beginning of a page whose frame is known: the clip is that page's box, which
     * is what 4.4.3, S. 234 says a page starts with.
     *
     * @param ctm     the CTM in effect at the first operator (usually the identity)
     * @param pageBox the page box in page space, {@link
     *                org.rogmann.mcp2sdk.pdf.geo.PdfPageSpace#pageBox()}
     */
    public static PdfGraphicsState initial(PdfMatrix ctm, PdfRect pageBox) {
        return initial(ctm, PdfClipPath.initial(pageBox));
    }

    // ===================================================================
    // The transitions the operators make (Table 4.7, S. 219)
    // ===================================================================

    /**
     * {@code cm} - "Modify the current transformation matrix (CTM) by concatenating the specified
     * matrix … Although the operands specify a matrix, they are written as six separate numbers, not
     * as an array" (Table 4.7, S. 219). Concatenation is premultiplication (4.2.3, S. 209:
     * {@code M' = M_T × M}): the operand applies <em>first</em>, so coordinates written after the
     * operator are read in the space the operand describes.
     * <p>
     * A singular operand is stored, not refused: the stream did write it, and the refusal belongs
     * where a number would be claimed - {@link #userToPageMatrix} then says there is no way back
     * rather than inventing one (decision 9).
     * </p>
     */
    public PdfGraphicsState transformedBy(PdfMatrix operand) {
        Fields f = fields();
        f.ctm = ctm.premultiply(operand);
        return f.build();
    }

    /** A state with a different CTM, wholesale - what {@code Q} effectively hands back. */
    public PdfGraphicsState withCtm(PdfMatrix newCtm) {
        Fields f = fields();
        f.ctm = newCtm;
        return f.build();
    }

    /** {@code lineWidth w} - stored as written; {@link #lineWidthDefect()} reports a negative one. */
    public PdfGraphicsState withLineWidth(double value) {
        Fields f = fields();
        f.lineWidth = value;
        return f.build();
    }

    /** {@code lineCap J} (Table 4.4, S. 216: 0 butt, 1 round, 2 projecting square). */
    public PdfGraphicsState withLineCap(int value) {
        Fields f = fields();
        f.lineCap = value;
        return f.build();
    }

    /** {@code lineJoin j} (Table 4.5, S. 216: 0 miter, 1 round, 2 bevel). */
    public PdfGraphicsState withLineJoin(int value) {
        Fields f = fields();
        f.lineJoin = value;
        return f.build();
    }

    /** {@code miterLimit M} - the ratio of miter length to line width (S. 217). */
    public PdfGraphicsState withMiterLimit(double value) {
        Fields f = fields();
        f.miterLimit = value;
        return f.build();
    }

    /** {@code dashArray dashPhase d}. */
    public PdfGraphicsState withDash(Dash value) {
        Fields f = fields();
        f.dash = value;
        return f.build();
    }

    /** {@code intent ri} - stored in slash form, as the stream writes names. */
    public PdfGraphicsState withRenderingIntent(String value) {
        Fields f = fields();
        f.renderingIntent = value;
        return f.build();
    }

    /** {@code flatness i} - Table 4.7, S. 219: "a number in the range 0 to 100; a value of 0 specifies the output device's default flatness tolerance". */
    public PdfGraphicsState withFlatness(double value) {
        Fields f = fields();
        f.flatness = value;
        return f.build();
    }

    // --- colour and the transparency parameters, raw ------------------------------------------------

    /**
     * A colour operator ({@code G}/{@code RG}/{@code K}/{@code SCN} and their non-stroking twins).
     * The value is the operands plus the space the operator named — never a colour this module
     * understood ({@link PdfColour}, decision 16).
     */
    public PdfGraphicsState withStrokeColour(PdfColour value) {
        Fields f = fields();
        f.strokeColour = value;
        return f.build();
    }

    /** The non-stroking twin ({@code g}/{@code rg}/{@code k}/{@code scn}). */
    public PdfGraphicsState withNonStrokeColour(PdfColour value) {
        Fields f = fields();
        f.nonStrokeColour = value;
        return f.build();
    }

    /**
     * {@code CS} — the colour space a stroking colour is interpreted in (Table 4.2: two of them).
     * Setting a space resets the colour with it (S. 287); that belongs to the colour family and is
     * done by {@link org.rogmann.mcp2sdk.pdf.ops.ColourOps}, not here.
     */
    public PdfGraphicsState withStrokeColourSpace(PdfColour.Space value) {
        Fields f = fields();
        f.strokeColourSpace = value;
        return f.build();
    }

    /** {@code cs} family - the non-stroking colour space. */
    public PdfGraphicsState withNonStrokeColourSpace(PdfColour.Space value) {
        Fields f = fields();
        f.nonStrokeColourSpace = value;
        return f.build();
    }

    /** A transparency parameter that only {@code gs} can set (BM, SMask) - kept raw, never decoded. */
    public PdfGraphicsState withBlendMode(Raw value) {
        Fields f = fields();
        f.blendMode = value;
        return f.build();
    }

    /** The soft mask, kept raw - "altering it with the gs operator completely replaces the old value" (Note, S. 222). */
    public PdfGraphicsState withSoftMask(Raw value) {
        Fields f = fields();
        f.softMask = value;
        return f.build();
    }

    /** {@code CA} / {@code ca} of a graphics state dictionary. */
    public PdfGraphicsState withAlpha(boolean stroking, double value) {
        Fields f = fields();
        if (stroking) {
            f.alphaStroking = value;
        } else {
            f.alphaNonStroking = value;
        }
        return f.build();
    }

    /** {@code AIS} - shape or opacity (S. 223). */
    public PdfGraphicsState withAlphaSource(boolean value) {
        Fields f = fields();
        f.alphaSource = value;
        return f.build();
    }

    /** {@code SA} - automatic stroke adjustment (Table 4.2, S. 211). */
    public PdfGraphicsState withStrokeAdjustment(boolean value) {
        Fields f = fields();
        f.strokeAdjustment = value;
        return f.build();
    }

    /** {@code SM} - smoothness tolerance; null restores "the installation decides". */
    public PdfGraphicsState withSmoothness(Double value) {
        Fields f = fields();
        f.smoothness = value;
        return f.build();
    }

    /**
     * {@code OP}/{@code op} - the two overprint flags. Table 4.8, S. 221 keeps the distinction:
     * "Specifying an OP entry sets both parameters unless there is also an op entry in the same
     * graphics state parameter dictionary, in which case the OP entry sets only the overprint
     * parameter for stroking." That is a question about the dictionary, so it is decided in
     * {@link #withExtGState}, not here.
     */
    public PdfGraphicsState withOverprint(boolean stroke, boolean nonStroke) {
        Fields f = fields();
        f.overprintStroke = stroke;
        f.overprintNonStroke = nonStroke;
        return f.build();
    }

    /** {@code OPM} - overprint mode (Table 4.3, S. 212: "Initial value: 0"). */
    public PdfGraphicsState withOverprintMode(int value) {
        Fields f = fields();
        f.overprintMode = value;
        return f.build();
    }

    /** A state with a new clip - what {@code W}/{@code W*} produce through {@link PdfClipPath.Added}. */
    public PdfGraphicsState withClip(PdfClipPath value) {
        Fields f = fields();
        f.clip = value;
        return f.build();
    }

    /** A state with a new text state - every text operator ends here. */
    public PdfGraphicsState withTextState(PdfTextState value) {
        Fields f = fields();
        f.text = value;
        return f.build();
    }

    // ===================================================================
    // gs (4.3.4, Table 4.8, S. 220-223)
    // ===================================================================

    /**
     * What one {@code gs} did.
     *
     * @param state        the state that follows
     * @param applied      the keys that reached a parameter, in the dictionary's order
     * @param uninterpreted the keys that were read and named but not interpreted - a colour
     *                      calibration this module does not claim to understand is listed, not
     *                      swallowed (decision 11)
     * @param notes        the sentences about malformed values
     */
    public record ExtGStateResult(PdfGraphicsState state, List<String> applied,
                                 List<String> uninterpreted, List<String> notes) {

        /** Whether the dictionary changed nothing this module tracks (all keys foreign, or empty). */
        public boolean isNoChange() {
            return applied.isEmpty();
        }
    }

    /**
     * {@code dictName gs} - looks the name up in {@code ExtGState} (that lookup is
     * {@link org.rogmann.mcp2sdk.pdf.PdfResources}'s job) and applies the dictionary it names.
     * "The results of gs are cumulative; parameter values established in previous invocations
     * persist until explicitly overridden" (S. 220), so every key not present leaves the state
     * exactly as it was.
     *
     * @param dict         the graphics state parameter dictionary
     * @param resourceName the name the stream used, for the notes ({@code "/GS1"})
     * @return the new state, which keys landed, which were foreign, and what was odd about the
     *         values
     */
    public ExtGStateResult withExtGState(Map<String, Object> dict, String resourceName) {
        List<String> applied = new ArrayList<>();
        List<String> foreign = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Fields f = fields();
        if (dict == null || dict.isEmpty()) {
            return new ExtGStateResult(this, applied, foreign, notes);
        }
        boolean hasOp = dict.containsKey("op");
        for (Map.Entry<String, Object> entry : dict.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if ("Type".equals(key)) {
                continue;    // structural, not a state parameter (Table 4.8, S. 220)
            }
            switch (key) {
                case "LW" -> {
                    Double number = numberOf(value);
                    if (number == null) {
                        notes.add(badValue(key, resourceName, "a number"));
                    } else {
                        f.lineWidth = number;
                        applied.add(key);
                        if (number < 0) {
                            notes.add("the ExtGState " + key + " of " + resourceName + " is"
                                    + " negative - 4.3.2, S. 215 describes the line width as a"
                                    + " non-negative number; the value is kept as written");
                        }
                    }
                }
                case "LC" -> applyInt(f, applied, notes, key, resourceName, value, 0, 2,
                        "Table 4.4, S. 216 lists three line cap styles (0, 1, 2)", true);
                case "LJ" -> applyInt(f, applied, notes, key, resourceName, value, 0, 2,
                        "Table 4.5, S. 216 lists three line join styles (0, 1, 2)", false);
                case "ML" -> {
                    Double number = numberOf(value);
                    if (number == null) {
                        notes.add(badValue(key, resourceName, "a number"));
                    } else {
                        f.miterLimit = number;
                        applied.add(key);
                    }
                }
                case "D" -> {
                    Dash dashValue = dashFromDictionary(value, key, resourceName, notes);
                    if (dashValue != null) {
                        f.dash = dashValue;
                        applied.add(key);
                    }
                }
                case "RI" -> {
                    String name = nameOf(value);
                    if (name == null) {
                        notes.add(badValue(key, resourceName, "a name"));
                    } else {
                        f.renderingIntent = name;
                        applied.add(key);
                    }
                }
                case "FL" -> {
                    Double number = numberOf(value);
                    if (number == null) {
                        notes.add(badValue(key, resourceName, "a number"));
                    } else {
                        f.flatness = number;
                        applied.add(key);
                        if (number < 0 || number > 100) {
                            notes.add("the ExtGState FL of " + resourceName + " is "
                                    + PdfMatrix.format(number) + ", and Table 4.7, S. 219 describes"
                                    + " the flatness as a number in the range 0 to 100");
                        }
                    }
                }
                case "SM" -> {
                    Double number = numberOf(value);
                    if (number == null) {
                        notes.add(badValue(key, resourceName, "a number"));
                    } else {
                        f.smoothness = number;
                        applied.add(key);
                    }
                }
                case "SA" -> {
                    Boolean flag = booleanOf(value);
                    if (flag == null) {
                        notes.add(badValue(key, resourceName, "true or false"));
                    } else {
                        f.strokeAdjustment = flag;
                        applied.add(key);
                    }
                }
                case "AIS" -> {
                    Boolean flag = booleanOf(value);
                    if (flag == null) {
                        notes.add(badValue(key, resourceName, "true or false"));
                    } else {
                        f.alphaSource = flag;
                        applied.add(key);
                    }
                }
                case "CA", "ca" -> {
                    Double number = numberOf(value);
                    if (number == null) {
                        notes.add(badValue(key, resourceName, "a number"));
                    } else {
                        if ("CA".equals(key)) {
                            f.alphaStroking = number;
                        } else {
                            f.alphaNonStroking = number;
                        }
                        applied.add(key);
                        if (number < 0 || number > 1) {
                            notes.add("the ExtGState " + key + " of " + resourceName + " is"
                                    + " " + PdfMatrix.format(number) + ", outside what a constant"
                                    + " shape or opacity value can be (Table 4.8, S. 222: the"
                                    + " constant value used for painting operations)");
                        }
                    }
                }
                case "OP" -> {
                    Boolean flag = booleanOf(value);
                    if (flag == null) {
                        notes.add(badValue(key, resourceName, "true or false"));
                    } else {
                        f.overprintStroke = flag;
                        if (!hasOp) {
                            f.overprintNonStroke = flag;    // the sentence of S. 221
                        }
                        applied.add(key);
                    }
                }
                case "op" -> {
                    Boolean flag = booleanOf(value);
                    if (flag == null) {
                        notes.add(badValue(key, resourceName, "true or false"));
                    } else {
                        f.overprintNonStroke = flag;
                        applied.add(key);
                    }
                }
                case "OPM" -> applyInt(f, applied, notes, key, resourceName, value, 0, 1,
                        "Table 4.3, S. 212 gives the overprint mode 0 or 1", false);
                case "Font" -> {
                    PdfTextState text = textFromExtGState(f.text, value, resourceName, notes);
                    if (text != null) {
                        f.text = text;
                        applied.add(key);
                    }
                }
                case "TK" -> {
                    Boolean flag = booleanOf(value);
                    if (flag == null) {
                        notes.add(badValue(key, resourceName, "true or false"));
                    } else {
                        // f.text, not textState: a dictionary may set Font before TK, and the
                        // earlier of its own entries has to survive the later one.
                        f.text = f.text.withKnockout(flag);
                        applied.add(key);
                    }
                }
                case "BM" -> {
                    f.blendMode = new Raw("gs:" + key, List.of(value == null ? "null" : value));
                    applied.add(key);
                }
                case "SMask" -> {
                    f.softMask = new Raw("gs:" + key, List.of(value == null ? "null" : value));
                    applied.add(key);
                }
                default -> {
                    if (GS_UNINTERPRETED_KEYS.contains(key)) {
                        foreign.add(key);
                    } else {
                        foreign.add(key + "?");
                    }
                }
            }
        }
        PdfGraphicsState built = f.build();
        if (!foreign.isEmpty()) {
            notes.add("the ExtGState " + resourceName + " also sets "
                    + String.join(", ", foreign) + " - read off the operand stack, named here,"
                    + " not interpreted: colour calibration, halftone and unknown keys have no"
                    + " meaning in a module that paints nothing");
        }
        return new ExtGStateResult(built, applied, foreign, notes);
    }

    // ===================================================================
    // Out of user space
    // ===================================================================

    /**
     * User space (as the operands of the stream stand) → page space: the CTM, followed by the page's
     * own frame - {@code ctm.multiply(pageSpace.defaultUserToPage())}, the composition of 4.2.3
     * S. 209 read along the chain of spaces rather than as a state transition.
     * <p>
     * The plan calls the frame field of a placement answer {@code userToPage}; this is the half of
     * that product the frame can carry on its own (one matrix for every item of a page), and the CTM
     * is the per-item half, which is why the two are kept apart.
     * </p>
     *
     * @return the matrix, or empty when the page has no frame or the CTM has no inverse - with
     *         {@link PdfMatrix#singularNote()} as the sentence for the second case
     */
    public java.util.Optional<PdfMatrix> userToPageMatrix(PdfPageSpace pageSpace) {
        return pageSpace.defaultUserToPage().map(pageSpaceMatrix -> ctm.multiply(pageSpaceMatrix));
    }

    /** A point of user space in default user space - the CTM applied. */
    public PdfPoint userToDefaultUser(PdfPoint userPoint) {
        if (!PdfSpace.USER.equals(userPoint.space())) {
            throw new PdfException("The CTM maps user space, this point is in '"
                    + userPoint.space() + "' space (4.2.1, S. 199-204) - one of them needs a"
                    + " matrix, not an assumption");
        }
        return ctm.map(userPoint, PdfSpace.DEFAULT_USER);
    }

    /**
     * How far a distance in user space reaches in the space a matrix maps into - the line width's
     * route out of user space.
     * <p>
     * A <em>displacement</em>, so only the linear part of the matrix counts; running the width
     * through {@link PdfMatrix#mapPoint(double, double)} would add the CTM's translation to a
     * thickness and report "the line is 792 points wide" for a page whose origin sits at
     * {@code (0, 792)}.
     * </p>
     * <p>
     * The answer is a <em>span</em> because a CTM with different factors on the two axes makes the
     * thickness depend on the direction of the line - S. 215 says exactly that ("If the CTM specifies
     * scaling by different factors in the horizontal and vertical dimensions, the thickness of
     * stroked lines in device space will vary according to their orientation"), and reporting one
     * number would pick a direction the stream never chose. The two numbers are the extreme scale
     * factors of the linear part (the singular values of {@code [[a, b], [c, d]]}).
     * </p>
     */
    public double[] widthSpanIn(PdfMatrix m) {
        double aa = m.a() * m.a() + m.b() * m.b();
        double cc = m.c() * m.c() + m.d() * m.d();
        double bb = m.a() * m.c() + m.b() * m.d();
        double half = (aa + cc) / 2;
        double root = Math.sqrt(Math.pow((aa - cc) / 2, 2) + bb * bb);
        double max = Math.sqrt(Math.max(0, half + root));
        double min = Math.sqrt(Math.max(0, half - root));
        return new double[]{lineWidth * min, lineWidth * max};
    }

    /** The line width as a displacement of default user space (the CTM's linear part only). */
    public PdfVector lineWidthInDefaultUserSpace() {
        double[] mapped = ctm.mapVector(0, lineWidth);
        return new PdfVector(mapped[0], mapped[1], PdfSpace.DEFAULT_USER);
    }

    // ===================================================================
    // Reading it
    // ===================================================================

    /** The CTM in effect (user space → default user space in this module, see the class note). */
    public PdfMatrix ctm() {
        return ctm;
    }

    /** The clipping path. */
    public PdfClipPath clip() {
        return clip;
    }

    /** The text state. */
    public PdfTextState textState() {
        return textState;
    }

    /** {@code w}. */
    public double lineWidth() {
        return lineWidth;
    }

    /** {@code J}. */
    public int lineCap() {
        return lineCap;
    }

    /** {@code j}. */
    public int lineJoin() {
        return lineJoin;
    }

    /** {@code M}. */
    public double miterLimit() {
        return miterLimit;
    }

    /** {@code d}. */
    public Dash dash() {
        return dash;
    }

    /** {@code ri} - the colour rendering intent, in slash form. */
    public String renderingIntent() {
        return renderingIntent;
    }

    /** {@code i}. */
    public double flatness() {
        return flatness;
    }

    /** {@code gs/SM}, or null when the installation decides. */
    public Double smoothness() {
        return smoothness;
    }

    /** {@code gs/SA}. */
    public boolean strokeAdjustment() {
        return strokeAdjustment;
    }

    /** {@code gs/BM}, raw. */
    public Raw blendMode() {
        return blendMode;
    }

    /** {@code gs/SMask}, raw. */
    public Raw softMask() {
        return softMask;
    }

    /** {@code gs/CA}. */
    public double alphaStroking() {
        return alphaStroking;
    }

    /** {@code gs/ca}. */
    public double alphaNonStroking() {
        return alphaNonStroking;
    }

    /** {@code gs/AIS}. */
    public boolean alphaSource() {
        return alphaSource;
    }

    /** Overprint for stroking operations. */
    public boolean overprintStroke() {
        return overprintStroke;
    }

    /** Overprint for everything else. */
    public boolean overprintNonStroke() {
        return overprintNonStroke;
    }

    /** {@code gs/OPM}. */
    public int overprintMode() {
        return overprintMode;
    }

    /**
     * The stroking colour as the operator that set it wrote it (numbers, named space, byte), or
     * {@code null} when none ran — in which case Table 4.2's DeviceGray/black is what paints, and
     * {@link #colourMap()} says so with {@code defaulted: true}.
     */
    public PdfColour strokeColour() {
        return strokeColour;
    }

    /** The non-stroking colour — the one a {@code f} fills with. */
    public PdfColour nonStrokeColour() {
        return nonStrokeColour;
    }

    /** The stroking colour space, as named. */
    public PdfColour.Space strokeColourSpace() {
        return strokeColourSpace;
    }

    /** The non-stroking colour space, as named. */
    public PdfColour.Space nonStrokeColourSpace() {
        return nonStrokeColourSpace;
    }

    /** A line width the file wrote that the specification calls non-negative; null when it is fine. */
    public String lineWidthDefect() {
        if (lineWidth >= 0) {
            return null;
        }
        return "the line width is " + PdfMatrix.format(lineWidth) + ", and 4.3.2, S. 215 describes"
                + " it as a non-negative number expressed in user space units";
    }

    /** A {@code J} outside the three of Table 4.4; null when it is inside. */
    public String lineCapDefect() {
        return lineCap < 0 || lineCap > 2 ? "the line cap style is " + lineCap
                + ", Table 4.4 (S. 216) defines 0 (butt), 1 (round) and 2 (projecting square)" : null;
    }

    /** A {@code j} outside the three of Table 4.5; null when it is inside. */
    public String lineJoinDefect() {
        return lineJoin < 0 || lineJoin > 2 ? "the line join style is " + lineJoin
                + ", Table 4.5 (S. 216) defines 0 (miter), 1 (round) and 2 (bevel)" : null;
    }

    /**
     * The state in one block of text, the way {@code {state: 'full'}} of a placement answer would
     * show it: every parameter that differs from its initial value of Tables 4.2/4.3, plus the CTM,
     * the clip and the text state.
     */
    public String describe() {
        StringBuilder sb = new StringBuilder("graphics state: CTM ").append(ctm);
        if (lineWidth != INITIAL_LINE_WIDTH) {
            sb.append(", w ").append(PdfMatrix.format(lineWidth));
        }
        if (lineCap != INITIAL_LINE_CAP) {
            sb.append(", J ").append(lineCap);
        }
        if (lineJoin != INITIAL_LINE_JOIN) {
            sb.append(", j ").append(lineJoin);
        }
        if (miterLimit != INITIAL_MITER_LIMIT) {
            sb.append(", M ").append(PdfMatrix.format(miterLimit));
        }
        if (!dash.isSolid()) {
            sb.append(", d ").append(dash);
        }
        if (strokeColour != null) {
            sb.append(", stroke colour ").append(strokeColour.describe());
        }
        if (nonStrokeColour != null) {
            sb.append(", fill colour ").append(nonStrokeColour.describe());
        }
        if (!INITIAL_RENDERING_INTENT.equals(renderingIntent)) {
            sb.append(", ri ").append(renderingIntent);
        }
        if (flatness != INITIAL_FLATNESS) {
            sb.append(", i ").append(PdfMatrix.format(flatness));
        }
        if (alphaStroking != INITIAL_ALPHA || alphaNonStroking != INITIAL_ALPHA) {
            sb.append(", CA/ca ").append(PdfMatrix.format(alphaStroking)).append('/')
                    .append(PdfMatrix.format(alphaNonStroking));
        }
        sb.append(" | ").append(clip.describe());
        sb.append(" | ").append(textState.describe());
        return sb.toString();
    }

    @Override
    public String toString() {
        return describe();
    }

    // ===================================================================
    // Raw settings and the dash
    // ===================================================================

    /**
     * A graphics state value the module stores but does not interpret - the raw operands of the
     * operator that set it, or the value a {@code gs} dictionary holds.
     *
     * @param setBy     the operator that set it ({@code "RG"}, {@code "scn"}) or
     *                  {@code "gs:KEY"} for a graphics state parameter dictionary
     * @param operands  the operands as the parser read them, unmodifiable
     */
    public record Raw(String setBy, List<Object> operands) {

        /** The raw operands, never null. */
        public Raw {
            operands = operands == null ? List.of() : List.copyOf(operands);
        }

        /** The operands rendered the way {@code pdf.operations()} renders them. */
        public String operandsText() {
            StringBuilder sb = new StringBuilder();
            for (Object operand : operands) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(PdfContentStream.formatOperand(operand, 0));
            }
            return sb.toString();
        }

        /** The setting as a line of PDF source when an operator set it, or as {@code gs:KEY value}. */
        public String describe() {
            if (setBy != null && setBy.startsWith("gs:")) {
                return setBy + " " + operandsText();
            }
            return PdfContentStream.formatOperation(operands, setBy);
        }
    }

    /**
     * The line dash pattern - {@code dashArray dashPhase d} (4.3.2, S. 217/218).
     *
     * @param array the alternating dash and gap lengths, in user space units; empty means solid
     * @param phase how far into the pattern stroking starts, also in user space units
     */
    public record Dash(List<Double> array, double phase) {

        /** {@code [ ] 0 d} - "an empty dash array and zero phase can be used to restore the dash pattern to a solid line" (S. 218). */
        public static Dash solid() {
            return new Dash(List.of(), 0);
        }

        /** The two operands of {@code d}. */
        public static Dash of(List<Double> lengths, double dashPhase) {
            return new Dash(lengths == null ? List.of() : List.copyOf(lengths), dashPhase);
        }

        /**
         * Whether the pattern paints a solid line: the empty array, or an array whose lengths are
         * all zero (which the specification forbids but a producer can write, and which then
         * behaves like the solid line of {@code [ ] 0 d}).
         */
        public boolean isSolid() {
            return array.isEmpty() || array.stream().allMatch(v -> v == 0);
        }

        /**
         * What the file wrote that S. 217/218 does not allow - "the numbers must be nonnegative and
         * not all zero" - or null when the pattern is fine. Reported rather than corrected: the
         * module does not know which of the numbers the producer meant.
         */
        public String defect() {
            for (Double length : array) {
                if (length != null && length < 0) {
                    return "the dash array holds a negative length (" + PdfMatrix.format(length)
                            + ") - 4.3.2, S. 217: the numbers must be nonnegative and not all zero";
                }
            }
            if (!array.isEmpty() && isSolid()) {
                return "the dash array is not empty but holds only zeros - 4.3.2, S. 217 excludes"
                        + " that; it is reported as the solid line it will paint";
            }
            return null;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("[");
            for (Double length : array) {
                sb.append(PdfMatrix.format(length == null ? 0 : length)).append(' ');
            }
            sb.append(']');
            return sb.append(' ').append(PdfMatrix.format(phase)).toString();
        }
    }

    // ===================================================================
    // Value helpers (a dictionary holds what the parser read: Long, Double, PdfName, …)
    // ===================================================================

    private static Double numberOf(Object value) {
        if (value instanceof Number number) {
            double d = number.doubleValue();
            return Double.isFinite(d) ? d : null;
        }
        return null;
    }

    private static Boolean booleanOf(Object value) {
        return value instanceof Boolean flag ? flag : null;
    }

    private static String nameOf(Object value) {
        if (value instanceof PdfName name) {
            return name.toString();
        }
        return null;
    }

    private void applyInt(Fields f, List<String> applied, List<String> notes, String key,
                          String resourceName, Object value, int low, int high, String source,
                          boolean capStyle) {
        Double number = numberOf(value);
        if (number == null || number != Math.rint(number)) {
            notes.add(badValue(key, resourceName, "an integer"));
            return;
        }
        int intValue = (int) Math.rint(number);
        if (capStyle) {
            f.lineCap = intValue;
        } else if ("LJ".equals(key)) {
            f.lineJoin = intValue;
        } else {
            f.overprintMode = intValue;
        }
        applied.add(key);
        if (intValue < low || intValue > high) {
            notes.add("the ExtGState " + key + " of " + resourceName + " is " + intValue + " - "
                    + source);
        }
    }

    private static Dash dashFromDictionary(Object value, String key, String resourceName,
                                           List<String> notes) {
        if (!(value instanceof List<?> outer) || outer.size() != 2
                || !(outer.get(0) instanceof List<?> inner)) {
            notes.add(badValue(key, resourceName, "an array of the form [dashArray dashPhase]"));
            return null;
        }
        List<Double> lengths = new ArrayList<>();
        for (Object item : inner) {
            Double number = numberOf(item);
            if (number == null) {
                notes.add(badValue(key, resourceName, "numbers in the dash array"));
                return null;
            }
            lengths.add(number);
        }
        double phase = 0;
        if (outer.get(1) != null) {
            Double number = numberOf(outer.get(1));
            if (number == null) {
                notes.add(badValue(key, resourceName, "a number as the dash phase"));
                return null;
            }
            phase = number;
        }
        return Dash.of(lengths, phase);
    }

    private static PdfTextState textFromExtGState(PdfTextState current, Object value,
                                                 String resourceName, List<String> notes) {
        if (!(value instanceof List<?> array) || array.size() != 2) {
            notes.add(badValue("Font", resourceName, "an array of the form [font size] (Table 4.8,"
                    + " S. 221)"));
            return null;
        }
        Integer objNum = null;
        Object font = array.get(0);
        if (font instanceof PdfReference reference) {
            objNum = reference.objNum();
        } else if (font != null) {
            notes.add("the Font entry of the ExtGState " + resourceName + " names the font as a "
                    + org.rogmann.mcp2sdk.pdf.PdfParser.kindOf(font) + " instead of an indirect"
                    + " reference - Table 4.8, S. 221: \"the first operand is an indirect object"
                    + " reference instead of a resource name\"; the size is still applied, the"
                    + " address is not invented");
        }
        Double size = numberOf(array.get(1));
        if (size == null) {
            notes.add(badValue("Font", resourceName, "a number as the font size"));
            return null;
        }
        return current.withFontReference(objNum, size, "ExtGState");
    }

    private static String badValue(String key, String resourceName, String expected) {
        return "the ExtGState " + key + " of " + resourceName + " is not " + expected
                + " (Table 4.8, S. 220-223) - the parameter keeps the value it had";
    }

    /** A map of every parameter that differs from its initial value, for a {@code state: 'full'} answer. */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ctm", ctm.toString());
        map.put("lineWidth", lineWidth);
        map.put("lineCap", (long) lineCap);
        map.put("lineJoin", (long) lineJoin);
        map.put("miterLimit", miterLimit);
        map.put("dash", dash.toString());
        map.put("renderingIntent", renderingIntent);
        map.put("flatness", flatness);
        if (smoothness != null) {
            map.put("smoothness", smoothness);
        }
        map.put("alphaStroking", alphaStroking);
        map.put("alphaNonStroking", alphaNonStroking);
        map.put("blendMode", blendMode == null ? null : blendMode.describe());
        map.put("softMask", softMask == null ? null : softMask.describe());
        // D8c: the two colour parameters of Table 4.2 as the structure they are (decision 16). The
        // source line they used to be is still there - inside the object, as `text`.
        if (strokeColour != null) {
            map.put("strokeColour", strokeColour.toMap());
        }
        if (nonStrokeColour != null) {
            map.put("nonStrokeColour", nonStrokeColour.toMap());
        }
        return map;
    }

    /**
     * The four colour parameters of Table 4.2 on their own — {@code pdf.placement()}'s
     * {@code state: 'colour'}. Asked as a block rather than inside {@link #toMap()} because
     * "womit ist an dieser Stelle gemalt worden" is a question a caller asks without wanting the
     * CTM, the dash and the flatness with it.
     *
     * <p>
     * Unlike {@link #toMap()}, this answers when the file wrote nothing: Table 4.2 gives both
     * parameters an initial value (DeviceGray, black — S. 210/211), and a page that never named a
     * colour is painted with that, not with nothing. The object then reads {@code defaulted: true},
     * which is the difference between the specification's value and one this module made up.
     * </p>
     */
    public Map<String, Object> colourMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("strokeColour", (strokeColour == null
                ? PdfColour.initialDeviceGray() : strokeColour).toMap());
        map.put("nonStrokeColour", (nonStrokeColour == null
                ? PdfColour.initialDeviceGray() : nonStrokeColour).toMap());
        map.put("strokeColourSpace", (strokeColourSpace == null
                ? PdfColour.Space.initial() : strokeColourSpace).toMap());
        map.put("nonStrokeColourSpace", (nonStrokeColourSpace == null
                ? PdfColour.Space.initial() : nonStrokeColourSpace).toMap());
        return map;
    }
}
