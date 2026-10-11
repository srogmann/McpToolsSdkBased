package org.rogmann.mcp2sdk.pdf.ops;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One operator a content stream may contain — a row of the <em>registry</em> the interpreter reads,
 * not a class of its own.
 *
 * <p>
 * The plan for this increment (docs/js/pdf-content-plan.md, "Registry, kein 200-Klassen-Wald")
 * settles the shape: almost every operator differs from its neighbour in nothing but how many
 * operands it takes and which field of the state one of those numbers lands in. The specification
 * itself is tabular at this point — 4.3.3 Table 4.7 (general graphics state), 4.4.1 Table 4.9 and
 * 4.4.2 Table 4.10 (path building and painting), 4.4.3 Table 4.11 (clipping), 4.5.7 Table 4.24
 * (colour), 5.2 Table 5.2 and 5.3 Tables 5.4/5.5/5.6 (text state, positioning, showing), 10.5
 * Table 10.7 (marked content), 4.8.6 Table 4.42 (inline image), Table 3.29 (compatibility) — so a
 * table in code mirrors it better than two hundred one-line classes, and a class is written only
 * where operands are <em>computed</em>
 * ({@link GeneralStateOps}, {@link ColourOps}, {@link PathBuildOps}, {@link ClipOps},
 * {@link TextStateOps}).
 * </p>
 *
 * <p>
 * The point of a registry rather than a {@code switch} with a {@code default:} branch is that
 * <b>"I do not know this operator" becomes a statement the module can make</b>:
 * {@link #lookup(String)} answers an {@link #isKnown() unknown} row for a name it does not carry,
 * and that row says what happened to the operands (they were dropped, the walk continued) and puts
 * the name on the walk's list of unknown operators. Silence about an operator the file really used
 * is the failure mode this table exists to prevent.
 * </p>
 *
 * <h2>Completeness: Appendix A is the check</h2>
 *
 * Appendix A of the same reference (S. 985-988) "lists, in alphabetical order, all the operators
 * used in PDF content streams" — 73 of them. That is the completeness test this table is held to
 * ({@code PdfContentOperatorTest} compares the two name sets), and reading it before finishing the
 * table corrected three rows this plan and a first draft had wrong:
 * <ul>
 *   <li><b>the compatibility closer is {@code EX}, not {@code EXE}</b> — Table 3.29, S. 152:
 *       "Unrecognized operators (along with their operands) are ignored without error until the
 *       balancing <i>EX</i> operator is encountered". A file writing the older spelling is reported
 *       as carrying an unknown operator, which is true and needs no invented row;</li>
 *   <li><b>there is no {@code OP}, {@code op} or {@code opm} operator</b> — overprint control is
 *       three <em>entries</em> of a graphics state parameter dictionary (Table 4.8, S. 221) and is
 *       applied by {@link org.rogmann.mcp2sdk.pdf.state.PdfGraphicsState#withExtGState}. Table 4.24
 *       (S. 287/288) lists twelve colour operators, and none of them sets overprint;</li>
 *   <li><b>{@code MP} takes a tag, not a property list</b> — Table 10.7, S. 851: "tag MP"; only
 *       {@code DP} and {@code BDC} carry a {@code properties} operand, and only they name the
 *       {@code Properties} resources.</li>
 * </ul>
 * The same reading is why there is no {@code iop} row either — it is an entry of a graphics state
 * parameter dictionary (PDF 2.0, ExtGState), and a {@code gs} dictionary holding it is already
 * reported as uninterpreted by
 * {@link org.rogmann.mcp2sdk.pdf.state.PdfGraphicsState#withExtGState}.
 *
 * <h2>What a row says</h2>
 * <ul>
 *   <li>{@link #category()} — which family handles it, and so which section of the reference it
 *       came from,</li>
 *   <li>{@link #shape()} and the two arity bounds — what the operand stack has to hold, in the words
 *       a diagnostic uses: a {@code cm} with three numbers reports
 *       <em>"cm needs 6 numbers, found 3"</em> at its offset,</li>
 *   <li>{@link #effects()} — what it does to the state, the path, the clip, and whether it paints
 *       text (the flags a sink reads instead of re-deriving the spec),</li>
 *   <li>{@link #resourceCategory()} — the {@link org.rogmann.mcp2sdk.pdf.PdfResources} category its
 *       name operand points into ({@code "Font"}, {@code "ExtGState"}, {@code "XObject"},
 *       {@code "Shading"}, {@code "ColorSpace"}, {@code "Properties"}), or null. This is where a
 *       content stream name becomes an object address (increment D0b), so the row is the place that
 *       knows the difference between {@code Tf} naming a font and {@code Tz} naming nothing.</li>
 * </ul>
 *
 * <h2>Known, read, not interpreted</h2>
 *
 * A row may carry {@link Effect#NOT_INTERPRETED}: the operands are taken off the stack (otherwise
 * everything after them would be misread) and the operator is named, but nothing is derived from it
 * — {@code sh} (a shading this module does not paint), {@code d0}/{@code d1} (Type 3 glyph
 * construction), {@code BX}/{@code EXE} (compatibility nesting), and the PDF 2.0 viewport operators
 * {@code vp}/{@code vp2}. Plan decision 13: the check document contains none of them, and "not in
 * the corpus" is not "does not exist".
 *
 * <p>
 * One correction to that list belongs here rather than in the plan: <b>{@code iop} is not an
 * operator.</b> It is an entry of a graphics state parameter dictionary (PDF 2.0, ExtGState), and a
 * {@code gs} dictionary holding it is already reported as uninterpreted by
 * {@link org.rogmann.mcp2sdk.pdf.state.PdfGraphicsState#withExtGState}. Inventing an {@code iop}
 * row would put an operator in the table that no content stream can contain.
 * </p>
 */
public record PdfContentOperator(String name, String category, Shape shape, int minOperands,
                                 Integer maxOperands, String arityText, Set<Effect> effects,
                                 String resourceCategory) {

    // ---------------------------------------------------------------------------------------------
    // Categories — the families of the reference tables
    // ---------------------------------------------------------------------------------------------

    /** 4.3.3, Table 4.7 (S. 219): {@code gs q Q cm w J j M d ri i}. */
    public static final String GENERAL_STATE = "general-state";
    /** 4.5.7, Table 4.24 (S. 287/288): twelve colour and colour-space operators. */
    public static final String COLOUR = "colour";
    /** Table 4.27 (S. 303): {@code sh}. */
    public static final String SHADING = "shading";
    /** 4.4.1, Table 4.9 (S. 226/227): {@code m l c v y h re}. */
    public static final String PATH_CONSTRUCTION = "path-construction";
    /** 4.4.2, Table 4.10 (S. 230): {@code S s f F f* B B* b b* n}. */
    public static final String PATH_PAINTING = "path-painting";
    /** 4.4.3, Table 4.11 (S. 235): {@code W W*}. */
    public static final String CLIPPING = "clipping";
    /** 5.3, Table 5.4 (S. 405): {@code BT ET}. */
    public static final String TEXT_OBJECT = "text-object";
    /** 5.2, Table 5.2 (S. 398): {@code Tc Tw Tz TL Tf Tr Ts}. */
    public static final String TEXT_STATE = "text-state";
    /** 5.3.1, Table 5.5 (S. 406): {@code Td TD Tm T*}. */
    public static final String TEXT_POSITION = "text-position";
    /** 5.3.2, Table 5.6 (S. 407/408): {@code Tj TJ ' "}. */
    public static final String TEXT_SHOWING = "text-showing";
    /** 10.5, Table 10.7 (S. 851): {@code MP DP BMC BDC EMC}. */
    public static final String MARKED_CONTENT = "marked-content";
    /** Table 4.37 (S. 332): {@code Do}. */
    public static final String XOBJECT = "XObject";
    /** 4.8.6, Table 4.42 (S. 352): {@code BI ID EI} — see the note on the three rows below. */
    public static final String INLINE_IMAGE = "inline-image";
    /** 5.5.4, Table 5.10 (S. 423): {@code d0 d1}, the glyph construction operators of a Type 3 font. */
    public static final String TYPE3_GLYPH = "type3-glyph";
    /** Table 3.29 (S. 152): {@code BX EX}. */
    public static final String COMPATIBILITY = "compatibility";
    /** Not in the table — named, operands dropped, reported. */
    public static final String UNKNOWN = "unknown";

    /** Every category the table uses, in reference order (a listing and {@code pdf.status()} name them). */
    public static final List<String> CATEGORIES = List.of(GENERAL_STATE, COLOUR, SHADING,
            PATH_CONSTRUCTION, PATH_PAINTING, CLIPPING, TEXT_OBJECT, TEXT_STATE, TEXT_POSITION,
            TEXT_SHOWING, MARKED_CONTENT, XOBJECT, INLINE_IMAGE, TYPE3_GLYPH, COMPATIBILITY,
            UNKNOWN);

    // ---------------------------------------------------------------------------------------------
    // Operand shapes
    // ---------------------------------------------------------------------------------------------

    /**
     * What kind of operands an operator takes — the type half of the arity check (the count half is
     * {@link #minOperands()}/{@link #maxOperands()}). Kept deliberately small: a shape that needs a
     * description beyond {@link #arityText()} is a shape the families check themselves.
     */
    public enum Shape {
        /** No operand at all ({@code q}, {@code S}, {@code W*}, {@code BT}). */
        NOTHING,
        /** Exactly one number ({@code w}, {@code Tc}, {@code g}). */
        NUMBER,
        /** One number that the reference gives as an integer with a listed range ({@code J j Tr opm}). */
        INTEGER,
        /** One number, several of them ({@code l}, {@code Td}, {@code c}, {@code re}). */
        NUMBERS,
        /** The six numbers of a matrix, "written as six separate numbers, not as an array" (S. 219). */
        MATRIX,
        /** One name ({@code ri}, {@code gs}, {@code Do}). */
        NAME,
        /**
         * {@code true} or {@code false} — a shape <b>no operator of PDF 1.7 uses</b>: the overprint
         * parameters {@code OP}, {@code op} and {@code OPM} are entries of a graphics state parameter
         * dictionary (Table 4.8, S. 221), not operators, and Appendix A (S. 985-988) lists no
         * boolean-taking operator. The constant stays because {@link PdfOperandStack#bool(int)} can
         * be asked, and because a dictionary-valued family may want the check; a row carrying it
         * would be an invention.
         */
        BOOLEAN,
        /** One string ({@code Tj}, {@code '}). */
        STRING,
        /** One array ({@code TJ}). */
        ARRAY,
        /** A name then a number ({@code Tf}). */
        NAME_THEN_NUMBER,
        /** An array then a number ({@code d}). */
        ARRAY_AND_NUMBER,
        /** Numbers then a string ({@code "}). */
        NUMBERS_THEN_STRING,
        /** Anything — a count-only operator, a family the module does not read, or an unknown one. */
        MIXED
    }

    // ---------------------------------------------------------------------------------------------
    // Effects
    // ---------------------------------------------------------------------------------------------

    /**
     * What an operator does, as a flag a sink can ask about instead of re-deriving it from the name.
     * The three that carry the most weight are the last three of the state group: a sink that only
     * wants text must still let {@code cm} run, because the operand of the next {@code Td} means
     * something else after it.
     *
     * <p>
     * There is deliberately no "sets overprint" flag: PDF 1.7 has no overprint <em>operator</em>.
     * Table 4.24 (S. 287/288) lists twelve colour operators and Appendix A (S. 985-988) lists every
     * content stream operator of the version — neither has {@code OP}, {@code op} or {@code opm}.
     * Those three are entries of a graphics state parameter dictionary (Table 4.8, S. 221) and reach
     * the state through {@link org.rogmann.mcp2sdk.pdf.state.PdfGraphicsState#withExtGState}.
     * </p>
     */
    public enum Effect {
        /** {@code q} — pushes the graphics state (4.3.1, S. 214). */
        SAVES_STATE,
        /** {@code Q} — pops it. */
        RESTORES_STATE,
        /** {@code cm} — concatenates onto the CTM. */
        CHANGES_CTM,
        /** A line or curve parameter: {@code w J j M d i}. */
        SETS_LINE,
        /** {@code ri} — the colour rendering intent. */
        SETS_RENDERING_INTENT,
        /** {@code gs} — a graphics state parameter dictionary. */
        SETS_EXTG_STATE,
        /** A colour operand ({@code g G rg RG k K sc SC scn SCN}). */
        SETS_COLOUR,
        /** A colour space operand ({@code cs CS}) — a colour space the module names, never decodes. */
        SETS_COLOUR_SPACE,        /** {@code m l c v y h re} — appends to the current path. */
        BUILDS_PATH,
        /** Every operator of Table 4.10, {@code n} included: "Once a path has been painted, it is no
            longer defined" (4.4.1, S. 226), and Table 4.10 lists {@code n} among them. */
        ENDS_PATH,
        /** Fills or strokes (so the path is painted, not only consumed) — everything of 4.10 but {@code n}. */
        PAINTS,
        /** {@code f* B* b* W*} — even-odd instead of nonzero winding. */
        EVEN_ODD,
        /** {@code W W*} — the path becomes the clipping path (4.4.3, S. 234). */
        SETS_CLIP,
        /** {@code BT}. */
        STARTS_TEXT_OBJECT,
        /** {@code ET}. */
        ENDS_TEXT_OBJECT,
        /** A text state parameter ({@code Tc Tw Tz TL Tf Tr Ts}). */
        SETS_TEXT_STATE,
        /** A text positioning operator ({@code Td TD Tm T*}). */
        MOVES_TEXT,
        /** A text showing operator ({@code Tj TJ ' "}). */
        SHOWS_TEXT,
        /** {@code BMC BDC EMC MP DP vp vp2}. */
        MARKS_CONTENT,
        /** {@code Do} — an external object, whose own content stream is a walk of its own (D6). */
        RUNS_XOBJECT,
        /** {@code BI ID EI} — an image painted from the stream itself. */
        SHOWS_INLINE_IMAGE,
        /** {@code d0 d1}: read, operands dropped, nothing derived (8.12.4). */
        TYPE3_GLYPH,
        /** {@code BX EXE}: recognised as a compatibility nesting, not executed (8.8). */
        COMPATIBILITY_MARKER,
        /** Operands leave the stack, and nothing else happens — the honest half of decision 11. */
        NOT_INTERPRETED
    }

    // ---------------------------------------------------------------------------------------------
    // The table
    // ---------------------------------------------------------------------------------------------

    private static final Map<String, PdfContentOperator> TABLE = build();

    private static Map<String, PdfContentOperator> build() {
        Map<String, PdfContentOperator> table = new LinkedHashMap<>();
        // --- general graphics state, 4.3.3 Table 4.7 (S. 219) ------------------------------------
        put(table, "gs", GENERAL_STATE, Shape.NAME, 1, 1, "1 name", "ExtGState",
                Effect.SETS_EXTG_STATE);
        put(table, "q", GENERAL_STATE, Shape.NOTHING, 0, 0, "no operand", null,
                Effect.SAVES_STATE);
        put(table, "Q", GENERAL_STATE, Shape.NOTHING, 0, 0, "no operand", null,
                Effect.RESTORES_STATE);
        put(table, "cm", GENERAL_STATE, Shape.MATRIX, 6, 6, "6 numbers", null,
                Effect.CHANGES_CTM);
        put(table, "w", GENERAL_STATE, Shape.NUMBER, 1, 1, "1 number", null, Effect.SETS_LINE);
        put(table, "J", GENERAL_STATE, Shape.INTEGER, 1, 1, "1 integer", null, Effect.SETS_LINE);
        put(table, "j", GENERAL_STATE, Shape.INTEGER, 1, 1, "1 integer", null, Effect.SETS_LINE);
        put(table, "M", GENERAL_STATE, Shape.NUMBER, 1, 1, "1 number", null, Effect.SETS_LINE);
        put(table, "d", GENERAL_STATE, Shape.ARRAY_AND_NUMBER, 2, 2, "an array and a number", null,
                Effect.SETS_LINE);
        put(table, "ri", GENERAL_STATE, Shape.NAME, 1, 1, "1 name", null,
                Effect.SETS_RENDERING_INTENT);
        put(table, "i", GENERAL_STATE, Shape.NUMBER, 1, 1, "1 number", null, Effect.SETS_LINE);

        // --- colour, 8.6 (the operands are kept raw, decision 11) --------------------------------
        put(table, "cs", COLOUR, Shape.NAME, 1, null, "a colour space name and optional components",
                "ColorSpace", Effect.SETS_COLOUR_SPACE);
        put(table, "CS", COLOUR, Shape.NAME, 1, null, "a colour space name and optional components",
                "ColorSpace", Effect.SETS_COLOUR_SPACE);
        put(table, "sc", COLOUR, Shape.NUMBERS, 1, null, "one or more numbers", null,
                Effect.SETS_COLOUR);
        put(table, "SC", COLOUR, Shape.NUMBERS, 1, null, "one or more numbers", null,
                Effect.SETS_COLOUR);
        // A pattern name may replace the components entirely, so scn/SCN may be empty.
        put(table, "scn", COLOUR, Shape.MIXED, 0, null, "components and an optional name",
                "ColorSpace", Effect.SETS_COLOUR);
        put(table, "SCN", COLOUR, Shape.MIXED, 0, null, "components and an optional name",
                "ColorSpace", Effect.SETS_COLOUR);
        put(table, "g", COLOUR, Shape.NUMBER, 1, 1, "1 number", null, Effect.SETS_COLOUR);
        put(table, "G", COLOUR, Shape.NUMBER, 1, 1, "1 number", null, Effect.SETS_COLOUR);
        put(table, "rg", COLOUR, Shape.NUMBERS, 3, 3, "3 numbers", null, Effect.SETS_COLOUR);
        put(table, "RG", COLOUR, Shape.NUMBERS, 3, 3, "3 numbers", null, Effect.SETS_COLOUR);
        put(table, "k", COLOUR, Shape.NUMBERS, 4, 4, "4 numbers", null, Effect.SETS_COLOUR);
        put(table, "K", COLOUR, Shape.NUMBERS, 4, 4, "4 numbers", null, Effect.SETS_COLOUR);
        // NOTE: PDF 1.7 has no OP / op / opm operator. Overprint control is set through the
        // ExtGState entries OP, op and OPM (Table 4.8, S. 221) and lands in the state there;
        // Table 4.24 (S. 287/288) and the operator summary of Appendix A (S. 985-988) list twelve
        // colour operators and none of them is an overprint parameter. The plan's decision 13 named
        // "OP/op" among the unread operators, which was the same mistake this row used to make.

        // --- shading, 8.6.8 -----------------------------------------------------------------------
        put(table, "sh", SHADING, Shape.NAME, 1, 1, "1 name", "Shading",
                Effect.NOT_INTERPRETED);

        // --- path construction, 4.4.1 Table 4.9 (S. 226-229) -------------------------------------
        put(table, "m", PATH_CONSTRUCTION, Shape.NUMBERS, 2, 2, "2 numbers", null,
                Effect.BUILDS_PATH);
        put(table, "l", PATH_CONSTRUCTION, Shape.NUMBERS, 2, 2, "2 numbers", null,
                Effect.BUILDS_PATH);
        put(table, "c", PATH_CONSTRUCTION, Shape.NUMBERS, 6, 6, "6 numbers", null,
                Effect.BUILDS_PATH);
        put(table, "v", PATH_CONSTRUCTION, Shape.NUMBERS, 4, 4, "4 numbers", null,
                Effect.BUILDS_PATH);
        put(table, "y", PATH_CONSTRUCTION, Shape.NUMBERS, 4, 4, "4 numbers", null,
                Effect.BUILDS_PATH);
        put(table, "h", PATH_CONSTRUCTION, Shape.NOTHING, 0, 0, "no operand", null,
                Effect.BUILDS_PATH);
        put(table, "re", PATH_CONSTRUCTION, Shape.NUMBERS, 4, 4, "4 numbers", null,
                Effect.BUILDS_PATH);

        // --- path painting, 4.4.2 Table 4.10 (S. 230): all ten take no operand, all ten end the path
        for (String operator : List.of("S", "s", "f", "F", "B", "B*", "b", "b*")) {
            put(table, operator, PATH_PAINTING, Shape.NOTHING, 0, 0, "no operand", null,
                    Effect.PAINTS, Effect.ENDS_PATH);
        }
        for (String operator : List.of("f*", "B*", "b*")) {
            put(table, operator, PATH_PAINTING, Shape.NOTHING, 0, 0, "no operand", null,
                    Effect.PAINTS, Effect.ENDS_PATH, Effect.EVEN_ODD);
        }
        // n "paints nothing" but Table 4.10 lists it, and S. 226 ends the path at a painting operator.
        put(table, "n", PATH_PAINTING, Shape.NOTHING, 0, 0, "no operand", null, Effect.ENDS_PATH);

        // --- clipping, 4.4.3 Table 4.11 (S. 235) --------------------------------------------------
        put(table, "W", CLIPPING, Shape.NOTHING, 0, 0, "no operand", null, Effect.SETS_CLIP);
        put(table, "W*", CLIPPING, Shape.NOTHING, 0, 0, "no operand", null, Effect.SETS_CLIP,
                Effect.EVEN_ODD);

        // --- text objects and text state, Tables 5.2 and 5.4 -------------------------------------
        put(table, "BT", TEXT_OBJECT, Shape.NOTHING, 0, 0, "no operand", null,
                Effect.STARTS_TEXT_OBJECT);
        put(table, "ET", TEXT_OBJECT, Shape.NOTHING, 0, 0, "no operand", null,
                Effect.ENDS_TEXT_OBJECT);
        put(table, "Tc", TEXT_STATE, Shape.NUMBER, 1, 1, "1 number", null, Effect.SETS_TEXT_STATE);
        put(table, "Tw", TEXT_STATE, Shape.NUMBER, 1, 1, "1 number", null, Effect.SETS_TEXT_STATE);
        put(table, "Tz", TEXT_STATE, Shape.NUMBER, 1, 1, "1 number", null, Effect.SETS_TEXT_STATE);
        put(table, "TL", TEXT_STATE, Shape.NUMBER, 1, 1, "1 number", null, Effect.SETS_TEXT_STATE);
        put(table, "Tf", TEXT_STATE, Shape.NAME_THEN_NUMBER, 2, 2, "a name and a number", "Font",
                Effect.SETS_TEXT_STATE);
        put(table, "Tr", TEXT_STATE, Shape.INTEGER, 1, 1, "1 integer", null, Effect.SETS_TEXT_STATE);
        put(table, "Ts", TEXT_STATE, Shape.NUMBER, 1, 1, "1 number", null, Effect.SETS_TEXT_STATE);

        // --- text positioning, 5.3.2 Table 5.5 (S. 406) ------------------------------------------
        put(table, "Td", TEXT_POSITION, Shape.NUMBERS, 2, 2, "2 numbers", null, Effect.MOVES_TEXT);
        put(table, "TD", TEXT_POSITION, Shape.NUMBERS, 2, 2, "2 numbers", null, Effect.MOVES_TEXT);
        put(table, "Tm", TEXT_POSITION, Shape.MATRIX, 6, 6, "6 numbers", null, Effect.MOVES_TEXT);
        put(table, "T*", TEXT_POSITION, Shape.NOTHING, 0, 0, "no operand", null,
                Effect.MOVES_TEXT);

        // --- text showing, 5.3.2 Table 5.6 (S. 407) ----------------------------------------------
        put(table, "Tj", TEXT_SHOWING, Shape.STRING, 1, 1, "1 string", null, Effect.SHOWS_TEXT);
        put(table, "TJ", TEXT_SHOWING, Shape.ARRAY, 1, 1, "1 array", null, Effect.SHOWS_TEXT);
        // ' is "T* then show text" and " is "aw ac Tc/Tw then ' ": the positioning half is real, the
        // displacement of the glyphs is the arithmetic of D5.
        put(table, "'", TEXT_SHOWING, Shape.STRING, 1, 1, "1 string", null, Effect.SHOWS_TEXT,
                Effect.MOVES_TEXT);
        put(table, "\"", TEXT_SHOWING, Shape.NUMBERS_THEN_STRING, 3, 3,
                "two numbers and a string", null, Effect.SHOWS_TEXT, Effect.MOVES_TEXT);

        // --- marked content, 14.6.2 ---------------------------------------------------------------
        put(table, "BMC", MARKED_CONTENT, Shape.NAME, 1, 1, "1 name", null, Effect.MARKS_CONTENT);
        put(table, "BDC", MARKED_CONTENT, Shape.NAME, 1, 2, "a name and optional properties",
                "Properties", Effect.MARKS_CONTENT);
        put(table, "EMC", MARKED_CONTENT, Shape.NOTHING, 0, 0, "no operand", null,
                Effect.MARKS_CONTENT);
        // MP's operand is the tag itself (Table 10.7: "tag MP"), not a property list - only DP and
        // BDC can name one, and only they point into the Properties resources.
        put(table, "MP", MARKED_CONTENT, Shape.NAME, 1, 1, "1 name", null,
                Effect.MARKS_CONTENT);
        put(table, "DP", MARKED_CONTENT, Shape.NAME, 1, 2, "a tag name and optional properties",
                "Properties", Effect.MARKS_CONTENT, Effect.NOT_INTERPRETED);
        // No vp/vp2 row. The viewport operators belong to PDF 2.0; the reference this module reads
        // (PDF 1.7) does not have them, and a row would claim an arity and a resource category that
        // nothing here can cite. A stream that uses one is named in the walk's list of unknown
        // operators instead - which is the same statement, made without inventing a specification.

        // --- external objects, 4.9.3 --------------------------------------------------------------
        put(table, "Do", XOBJECT, Shape.NAME, 1, 1, "1 name", "XObject", Effect.RUNS_XOBJECT);

        // --- inline image, 8.9.7 ------------------------------------------------------------------
        // BI and ID never reach the interpreter as tokens: the tokenizer folds "BI … ID <data> EI"
        // into one value token (see PdfContentStream.tokenize) and hands the image summary to the
        // operand stack, which is what pdf.operations() has always shown as the operand of EI.
        // That is also why EI's arity is MIXED rather than "no operand": its one operand is the
        // folded image, not a stream-level operand of EI.
        put(table, "BI", INLINE_IMAGE, Shape.MIXED, 0, null, "the image dictionary", null,
                Effect.SHOWS_INLINE_IMAGE, Effect.NOT_INTERPRETED);
        put(table, "ID", INLINE_IMAGE, Shape.MIXED, 0, null, "the image data", null,
                Effect.SHOWS_INLINE_IMAGE, Effect.NOT_INTERPRETED);
        put(table, "EI", INLINE_IMAGE, Shape.MIXED, 0, null, "the folded image operands", null,
                Effect.SHOWS_INLINE_IMAGE);

        // --- Type 3 glyph construction, 8.12.4 ----------------------------------------------------
        put(table, "d0", TYPE3_GLYPH, Shape.NUMBERS, 2, 2, "2 numbers", null,
                Effect.TYPE3_GLYPH, Effect.NOT_INTERPRETED);
        put(table, "d1", TYPE3_GLYPH, Shape.NUMBERS, 8, 8, "8 numbers", null,
                Effect.TYPE3_GLYPH, Effect.NOT_INTERPRETED);

        // --- compatibility, 3.4 (Table 3.29, S. 152) ---------------------------------------------
        // The reference names the closing operator EX: "Unrecognized operators (along with their
        // operands) are ignored without error until the balancing EX operator is encountered".
        // A file that writes the older spelling EXE is therefore reported as an unknown operator -
        // which is a statement the walk can make and act on (operands dropped, walk continued),
        // and it is more honest than carrying a row the reference does not have.
        put(table, "BX", COMPATIBILITY, Shape.NOTHING, 0, 0, "no operand", null,
                Effect.COMPATIBILITY_MARKER, Effect.NOT_INTERPRETED);
        put(table, "EX", COMPATIBILITY, Shape.NOTHING, 0, 0, "no operand", null,
                Effect.COMPATIBILITY_MARKER, Effect.NOT_INTERPRETED);
        return Collections.unmodifiableMap(table);
    }

    private static void put(Map<String, PdfContentOperator> table, String name, String category,
                            Shape shape, int min, Integer max, String arityText,
                            String resourceCategory, Effect... effects) {
        table.put(name, new PdfContentOperator(name, category, shape, min, max, arityText,
                Set.of(effects), resourceCategory));
    }

    /** The row a name means — an {@link #isKnown() unknown} row for a name the table does not carry. */
    public static PdfContentOperator lookup(String name) {
        PdfContentOperator operator = name == null ? null : TABLE.get(name);
        return operator != null ? operator : unknown(name);
    }

    /** A row for a name from outside the table. It says so, it swallows its operands, it gets named. */
    public static PdfContentOperator unknown(String name) {
        return new PdfContentOperator(name == null ? "" : name, UNKNOWN, Shape.MIXED, 0, null,
                "unknown operands", Set.of(Effect.NOT_INTERPRETED), null);
    }

    /** The operators the table carries, in table order. */
    public static List<PdfContentOperator> operators() {
        return List.copyOf(TABLE.values());
    }

    /** Every known operator name, in table order. */
    public static List<String> names() {
        return List.copyOf(TABLE.keySet());
    }

    /** How many operators the table knows — a test pins this number so a dropped row cannot pass unnoticed. */
    public static int count() {
        return TABLE.size();
    }

    /** The names of one family, for a listing of what the module reads. */
    public static List<String> namesOf(String category) {
        List<String> names = new ArrayList<>();
        for (PdfContentOperator operator : TABLE.values()) {
            if (operator.category().equals(category)) {
                names.add(operator.name());
            }
        }
        return names;
    }

    public PdfContentOperator {
        effects = effects == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(effects));
    }

    /** Whether the table carries this operator. */
    public boolean isKnown() {
        return !UNKNOWN.equals(category);
    }

    /** Whether this effect belongs to the operator. */
    public boolean has(Effect effect) {
        return effects.contains(effect);
    }

    /** Whether the operand stack is checked against {@link #shape()} at all. */
    public boolean checksOperands() {
        return shape != Shape.MIXED;
    }

    /** Whether this operator paints glyphs (Table 5.6's four). */
    public boolean showsText() {
        return has(Effect.SHOWS_TEXT);
    }

    /** Whether the current path ends here — every operator of Table 4.10, {@code n} included. */
    public boolean endsPath() {
        return has(Effect.ENDS_PATH);
    }

    /** Whether the operands extend the current path. */
    public boolean buildsPath() {
        return has(Effect.BUILDS_PATH);
    }

    /** Whether the path becomes the clipping path. */
    public boolean setsClip() {
        return has(Effect.SETS_CLIP);
    }

    /** Whether nothing is derived from the operands (they still leave the stack). */
    public boolean isInterpreted() {
        return isKnown() && !has(Effect.NOT_INTERPRETED);
    }

    /**
     * The arity in the words a diagnostic uses, e.g. {@code "6 numbers"} for {@code cm} —
     * {@link #arityText()} for a known operator, and a sentence naming the name for an unknown one.
     */
    public String arity() {
        return arityText;
    }

    /**
     * The arity bounds as text a listing can print: {@code "6"}, {@code "1..2"}, {@code "1+"}.
     */
    public String arityBounds() {
        if (maxOperands == null) {
            return minOperands + "+";
        }
        return maxOperands == minOperands ? Integer.toString(minOperands)
                : minOperands + ".." + maxOperands;
    }

    /** One line, for {@code pdf.help()} and a test listing: {@code cm  general-state  6 numbers}. */
    public String describe() {
        StringBuilder sb = new StringBuilder(name).append("  ").append(category).append("  ")
                .append(arityText);
        if (resourceCategory != null) {
            sb.append("  ->").append(resourceCategory);
        }
        if (!isKnown()) {
            sb.append("  (not in the registry)");
        }
        return sb.toString();
    }

    /** A stable, human-readable set of the effects — records print sets in hash order, this one not. */
    public List<String> effectNames() {
        List<String> names = new ArrayList<>();
        for (Effect effect : Effect.values()) {
            if (effects.contains(effect)) {
                names.add(effect.name());
            }
        }
        return names;
    }

    @Override
    public String toString() {
        return describe();
    }
}
