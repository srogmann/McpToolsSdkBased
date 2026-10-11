package org.rogmann.mcp2sdk.pdf.ops;

import org.rogmann.mcp2sdk.pdf.PdfException;
import org.rogmann.mcp2sdk.pdf.PdfName;
import org.rogmann.mcp2sdk.pdf.PdfReference;
import org.rogmann.mcp2sdk.pdf.PdfResources;
import org.rogmann.mcp2sdk.pdf.geo.PdfMatrix;
import org.rogmann.mcp2sdk.pdf.geo.PdfPoint;
import org.rogmann.mcp2sdk.pdf.geo.PdfQuad;
import org.rogmann.mcp2sdk.pdf.geo.PdfRect;
import org.rogmann.mcp2sdk.pdf.geo.PdfSpace;
import org.rogmann.mcp2sdk.pdf.state.PdfClipPath;
import org.rogmann.mcp2sdk.pdf.state.PdfGraphicsState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The {@code Do} family — 4.9.3, S. 357 and Table 4.45, S. 358 (PDF 1.7), read for increment D6.
 *
 * <h2>The one question this class answers first: descend, or name?</h2>
 *
 * A {@code Do} is the only operator whose operand is a whole second content stream. The plan
 * (docs/js/pdf-content-plan.md, D6, Punkt 3) settles it: <b>the normal answer names</b> — object
 * number, {@code /Subtype}, {@code /BBox} in the space the caller asked for, {@code /Matrix},
 * {@code FormType}, where the resources come from, how deep this one sits, and whether the walk has
 * been here before. <b>Descending happens only on an explicit {@code {includeXObjects: true}}</b>:
 * an unasked-for descent hides its budget behind a second stream and multiplies the answer, and a
 * caller who did not ask cannot tell a form's items from the page's own.
 *
 * <p>
 * Both halves are here, and they are deliberately separate: {@link #resolve} and {@link #item}
 * produce the named answer for every subtype (a form, an image, a PostScript XObject — the last one
 * discouraged but legal), and {@link #openDescent} builds the state a nested walk needs, following
 * the five tasks the specification lists for {@code Do} (S. 357): save as if {@code q}, concatenate
 * the {@code /Matrix} onto the CTM, clip to {@code /BBox}, paint, restore as if {@code Q}.
 * Everything else the form sees <em>is inherited</em> — "Except as described above, the initial
 * graphics state for the form is inherited from the graphics state that is in effect at the time
 * {@code Do} is invoked."
 * </p>
 *
 * <h2>Three traps the printed text sets, and this class has to step around</h2>
 *
 * <ul>
 *   <li><b>{@code /Matrix} is premultiplied, not appended.</b> "Concatenates the matrix from the
 *       form dictionary's {@code Matrix} entry with the current transformation matrix" — in the
 *       row-vector convention of 4.2.3 that is {@code ctm.premultiply(matrix)}: form space → the
 *       caller's user space → everything above it. The other way round paints the form somewhere
 *       else <em>and</em> clips it with the wrong box.</li>
 *   <li><b>{@code /BBox} is Required</b> (Table 4.45, S. 358). A form without one is not repaired:
 *       the item says {@code bboxMissing: true} and the descent refuses to invent a clip. And the
 *       box is <em>form space</em>, not user space — S. 357: "The BBox entry in the form dictionary
 *       is expressed in form space, as are any coordinates used in the form XObject's content
 *       stream" — which is why {@link org.rogmann.mcp2sdk.pdf.geo.PdfSpace} has a {@code form} and
 *       an {@code image} space at all (decision 7: every number names its space).</li>
 *   <li><b>A form without {@code /Resources} looks its names up on the page</b> — 3.7.2, S. 153:
 *       "in which case resources are looked up in the {@code Resources} entry of the page on which
 *       the form or font is used. This practice is not recommended." Not in the calling form.
 *       {@link PdfResources#ofStream} implements exactly that and labels the answer
 *       {@code stream→page}, which is what {@code resourcesFrom} reports.</li>
 * </ul>
 *
 * <h2>An image is not a form, and its box is not its pixels</h2>
 *
 * For an {@code /Image} XObject the box is the unit square of image space
 * (4.2.1, S. 203: "All images are 1 unit wide by 1 unit high in user space, regardless of their
 * dimensions in sample data values"), mapped through the CTM alone — an image dictionary has no
 * {@code /Matrix}. Its {@code /Width} and {@code /Height} are sample counts and are reported as
 * what they are, never as a box.
 *
 * <h2>Cycles are named, not survived</h2>
 *
 * Nothing in the specification forbids a form from invoking itself. A descent that follows such a
 * name walks until memory runs out, so {@link #openDescent} takes the chain of object numbers it has
 * already entered: a repeat is reported by name and depth and the walk continues with the named
 * item — the same reflex {@code PdfDocument} has for a {@code /Parent} loop.
 */
public final class XObjectOps {

    /** How deep a descent may go, counted in forms (0 = the page's own stream). */
    public static final int MAX_DEPTH = 8;

    /** The three subtypes the answer distinguishes; anything else is named as written. */
    public static final String SUBTYPE_FORM = "/Form";
    public static final String SUBTYPE_IMAGE = "/Image";
    public static final String SUBTYPE_PS = "/PS";

    /**
     * One {@code Do} operand, resolved through the stream's resources.
     *
     * @param resourceName  the name as the stream wrote it ({@code "/Fm3"})
     * @param objNum        object number of the XObject, or null when nothing stands behind the name
     * @param subtype       {@code /Subtype} as written, or null
     * @param dict          the XObject's own dictionary (a form's form dictionary), or null
     * @param bbox          {@code /BBox} in <b>form space</b> (Table 4.45: "four numbers in the form
     *                      coordinate system … left, bottom, right, and top edges"), or null
     * @param bboxMissing   whether a form omits the entry Table 4.45 marks <i>Required</i>
     * @param matrix        {@code /Matrix}, or identity when the form names none (the printed default)
     * @param matrixDefaulted whether that identity is the module's default rather than the file's word
     * @param formType      {@code /FormType} as written, or null; the printed default is 1
     * @param width         {@code /Width} of an image, in samples, or null
     * @param height        {@code /Height} of an image, in samples, or null
     * @param resourcesFrom where a name <b>inside this XObject</b> resolves — its own
     *                      {@code /Resources}, {@code "stream→page (…)"} when it names none
     *                      (3.7.2, S. 153) — or, until a caller with a document fills it in, where
     *                      the {@code Do}'s own name resolved: {@link PdfResources#FROM_STREAM},
     *                      {@code "page"}, {@link PdfResources#FROM_NONE}
     * @param missing       whether the name is not in the {@code XObject} subdictionary, or its object
     *                      is lost
     * @param note          what the resolution had to say (missing name, missing {@code /BBox}, …)
     */
    public record Target(String resourceName, Integer objNum, String subtype,
                         Map<String, Object> dict, PdfRect bbox, boolean bboxMissing,
                         PdfMatrix matrix, boolean matrixDefaulted, Integer formType,
                         Integer width, Integer height, String resourcesFrom,
                         boolean missing, String note) {

        /**
         * The same target, asked of a caller that can read the form's own dictionary: what
         * {@code resourcesFrom} then says is where the form's <em>own</em> names resolve, which is
         * the fact a reader of a diagram's XObject is after (3.7.2: without {@code /Resources} the
         * page's apply, and that is why listing a form alone never finds its fonts).
         */
        public Target withResourcesFrom(String value) {
            return value == null || value.equals(resourcesFrom) ? this
                    : new Target(resourceName, objNum, subtype, dict, bbox, bboxMissing, matrix,
                            matrixDefaulted, formType, width, height, value, missing, note);
        }

        /** Whether this is a form XObject — the only subtype a descent can open. */
        public boolean isForm() {
            return SUBTYPE_FORM.equals(subtype);
        }

        /** Whether this is an image XObject (its box is the image space, not a {@code /BBox}). */
        public boolean isImage() {
            return SUBTYPE_IMAGE.equals(subtype);
        }

        /** The {@code FormType} the file names, or the printed default 1 for a form. */
        public Integer formTypeOrDefault() {
            if (formType != null || !isForm()) {
                return formType;
            }
            // Table 4.45, S. 358: "FormType integer (Required; must be an integer) … The current
            // documented version is 1" - a default the specification names, so naming it as one.
            return 1;
        }
    }

    /**
     * Everything a nested walk needs, built from the {@code Do} and the state it ran in.
     *
     * @param content   the form's decoded bytes
     * @param state     the state the form starts in: inherited, with the {@code /Matrix} in the CTM
     *                  and the {@code /BBox} as one more clip layer (the {@code q … Q} of S. 357,
     *                  steps 1 and 5, is what a separate walk gives for free)
     * @param resources the form's own resources, or the page's when it names none (3.7.2, S. 153)
     * @param depth     how deep this walk is (the page's own stream is 0)
     */
    public record Descent(byte[] content, PdfGraphicsState state, PdfResources resources, int depth) {
    }

    /**
     * Reads a stream's content and resources from the outside — the seam that keeps this package
     * free of {@code PdfDocument}. The document supplies it for a real file; a test can supply a
     * map of bytes.
     */
    public interface Source {

        /**
         * Decoded content of the object, or null when it cannot be read (the note says why).
         *
         * @param objNum object number of the XObject
         * @param notes  where to tell what went wrong
         * @return decoded bytes, or null
         */
        byte[] content(int objNum, List<String> notes);

        /**
         * The resources a form's names resolve against: its own {@code /Resources}, else the page's
         * (3.7.2, S. 153).
         *
         * @param objNum object number of the form
         * @param label  what to name it in the note ({@code "form XObject /Fm1"})
         * @param notes  where to tell what went wrong
         * @return the resolver, never null
         */
        PdfResources resourcesOf(int objNum, String label, List<String> notes);

        /**
         * Reads a value a dictionary holds as a reference (a {@code /BBox} written as an indirect
         * array). The identity by default: a caller that cannot read objects simply reports the box
         * as unreadable instead of inventing one.
         *
         * @param value the value as the dictionary holds it
         * @return the value behind it, or the value itself
         */
        default Object deref(Object value) {
            return value;
        }

        /**
         * Where <em>the XObject's own</em> names resolve — its {@code /Resources}, or the page's when
         * it names none (3.7.2, S. 153). This is the answer a reader of {@code resourcesFrom} wants:
         * it is the reason a listing of a form alone never finds the fonts of a diagram. Null when
         * the caller cannot tell (a test that only maps bytes).
         *
         * @param objNum object number of the XObject
         * @param label  what to name it in a note
         * @return {@code stream}, {@code stream→page (…)}, {@code none}, or null
         */
        default String resourcesFrom(int objNum, String label) {
            return null;
        }
    }

    private XObjectOps() {
        // static family
    }

    /**
     * Resolves one {@code Do} operand against a resource dictionary, without dereferencing anything.
     *
     * @param resources the resources of the stream the {@code Do} ran in (its own, per 3.7.2)
     * @param name      the operand name, with or without its slash
     * @return the target
     */
    public static Target resolve(PdfResources resources, String name) {
        return resolve(resources, name, value -> value);
    }

    /**
     * Resolves one {@code Do} operand.
     *
     * @param resources the resources of the stream the {@code Do} ran in (its own, per 3.7.2)
     * @param name      the operand name, with or without its slash
     * @param deref     how to read a reference the dictionary holds inline (a {@code /BBox} written
     *                  as an indirect array); the identity for a caller that has nothing to ask
     * @return the target — {@link Target#missing()} when the name resolves to nothing, which is a
     *         statement the answer carries rather than an exception the walk dies on
     */
    public static Target resolve(PdfResources resources, String name,
                                 UnaryOperator<Object> deref) {
        String written = name == null ? "" : (name.startsWith("/") ? name : "/" + name);
        UnaryOperator<Object> read = deref == null ? value -> value : deref;
        if (resources == null || name == null || name.isEmpty()) {
            return new Target(written, null, null, null, null, false, PdfMatrix.identity(), true,
                    null, null, null, null, true,
                    "the stream was walked without resources, so the name '" + written
                            + "' of a Do operator cannot be resolved to an object");
        }
        PdfResources.Entry entry = resources.resolve("XObject", name);
        if (entry.missing()) {
            return new Target(written, entry.objNum(), null, null, null, false, PdfMatrix.identity(),
                    true, null, null, null, resources.resourcesFrom(), true,
                    "the Do at this offset names '" + written + "', which the XObject subdictionary"
                            + " of " + resources.owner() + " does not name (or its object cannot be"
                            + " read) - the name is reported, nothing is assumed");
        }
        Map<String, Object> dict = entry.dict();
        String subtype = entry.subtype();
        PdfRect bbox = rectOf(read.apply(dict == null ? null : dict.get("BBox")));
        boolean form = SUBTYPE_FORM.equals(subtype);
        Integer formType = integerOf(read.apply(dict == null ? null : dict.get("FormType")));
        Integer width = integerOf(read.apply(dict == null ? null : dict.get("Width")));
        Integer height = integerOf(read.apply(dict == null ? null : dict.get("Height")));
        List<Object> matrixValues = listOf(read.apply(dict == null ? null : dict.get("Matrix")));
        PdfMatrix matrix;
        boolean defaulted;
        if (matrixValues != null && matrixValues.size() == 6 && allNumbers(matrixValues)) {
            matrix = PdfMatrix.fromOperands(matrixValues);
            defaulted = false;
        } else {
            // Table 4.45: "Default value: the identity matrix [1 0 0 1 0 0]" - a default, and said
            // as one rather than presented as the file's own numbers.
            matrix = PdfMatrix.identity();
            defaulted = true;
        }
        String note = null;
        if (form && bbox == null) {
            note = "the form XObject " + written + " names no /BBox - Table 4.45, S. 358 marks the"
                    + " entry Required (\"An array of four numbers in the form coordinate system …"
                    + " used to clip the form XObject and to determine its size for caching\"), so"
                    + " there is no box to report and no clip to apply";
        }
        return new Target(written, entry.objNum(), subtype, dict, bbox, form && bbox == null,
                matrix, defaulted, formType, width, height, resources.resourcesFrom(), false, note);
    }

    /**
     * The matrix that leads a form's (or an image's) space into the space the walk reports in:
     * the {@code /Matrix} <b>pre</b>-multiplied onto the CTM (S. 357, step 2), then the page's own
     * frame when there is one.
     *
     * @param target the resolved operand
     * @param ctm    the CTM in effect at the {@code Do}
     * @param toPage the page's matrix, or null to stop in default user space
     * @return the route, empty when it cannot be built (a matrix holding no finite numbers)
     */
    public static Optional<PdfMatrix> route(Target target, PdfMatrix ctm, PdfMatrix toPage) {
        if (ctm == null) {
            return Optional.empty();
        }
        PdfMatrix route = ctm.premultiply(target == null ? PdfMatrix.identity() : target.matrix());
        if (toPage != null) {
            route = route.multiply(toPage);
        }
        return route.isFinite() && !route.isSingular() ? Optional.of(route) : Optional.empty();
    }

    /**
     * The box of a form or an image in the space the caller reports in.
     *
     * @param target the resolved operand
     * @param ctm    the CTM in effect at the {@code Do}
     * @param toPage the page's own matrix, or null when the walk is not in page space
     * @param space  what to label the answer ({@link org.rogmann.mcp2sdk.pdf.geo.PdfSpace#PAGE} or
     *               {@link org.rogmann.mcp2sdk.pdf.geo.PdfSpace#DEFAULT_USER}) — the caller decides,
     *               because the answer's space is the caller's decision and not this class's
     * @return the quad, empty when there is no box to map or the matrix cannot be undone
     */
    public static Optional<PdfQuad> bboxInSpace(Target target, PdfMatrix ctm, PdfMatrix toPage,
                                                String space) {
        if (target == null) {
            return Optional.empty();
        }
        // An image's box is the unit square of image space; a form's is its /BBox in form space.
        PdfRect box = target.isImage() ? imageSpaceUnit() : target.bbox();
        if (box == null) {
            return Optional.empty();
        }
        Optional<PdfMatrix> route = route(target, ctm, toPage);
        if (route.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(route.get().map(box, space));
    }

    /** The named answer for one {@code Do} — what the plan calls "benennen". */
    public static Map<String, Object> item(Target target, Optional<PdfQuad> quad, String space,
                                           int depth, String inName, PdfContentInterpreter.Operation
                                                   operation) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("kind", target.isImage() ? "image" : "xobject");
        item.put("offset", (long) operation.offset());
        item.put("op", operation.name());
        item.put("space", space);
        item.put("name", target.resourceName());
        item.put("objNum", target.objNum() == null ? null : (long) (int) target.objNum());
        item.put("subtype", target.subtype());
        if (target.isForm()) {
            item.put("formType", (long) (int) target.formTypeOrDefault());
            item.put("matrix", numbers(target.matrix()));
            if (target.matrixDefaulted()) {
                item.put("matrixSource", "default");    // Table 4.45: identity is the printed default
            }
        }
        if (target.isImage()) {
            item.put("inline", false);
            item.put("width", target.width() == null ? null : (long) (int) target.width());
            item.put("height", target.height() == null ? null : (long) (int) target.height());
        }
        if (quad != null && quad.isPresent()) {
            PdfQuad q = quad.get();
            item.put("bbox", numbers(q.bbox()));
            item.put("bboxSource", target.isImage() ? "image-space" : "form-bbox");
            if (!q.isAxisAligned(1e-6)) {
                // A rotated page turns a box into a parallelogram; the envelope is then the box
                // *around* the form, and saying so is cheaper than letting a reader assume it.
                item.put("bboxRotated", true);
                item.put("corners", points(q));
            }
        } else {
            item.put("bbox", null);
            item.put("bboxSource", target.bboxMissing() ? "missing"
                    : target.missing() ? "missing" : "none");
        }
        item.put("resourcesFrom", target.resourcesFrom());
        if (target.missing()) {
            item.put("missing", true);
        }
        if (target.bboxMissing()) {
            item.put("bboxMissing", true);
        }
        if (depth > 0) {
            item.put("depth", (long) depth);
            item.put("in", inName);
        }
        PdfMatrix ctm = operation.ctm();
        if (ctm.isSingular()) {
            item.put("singularNote", ctm.singularNote());
        }
        if (target.note() != null) {
            item.put("note", target.note());
        }
        return item;
    }

    /**
     * Builds the nested walk for a form, or explains why there is none.
     *
     * @param target    the resolved {@code Do} operand
     * @param stateAtDo the graphics state in effect at the {@code Do} (inherited, per S. 357)
     * @param toPage    the page matrix, or null when the walk is not in page space
     * @param depth     the depth of the <em>caller</em>; the form runs one deeper
     * @param entered   the object numbers already on the descent path (cycle watch)
     * @param source    where to read the form's bytes and resources from
     * @param notes     where the reason lands (never null)
     * @return the descent, empty when it is refused — the caller then keeps the named answer
     */
    public static Optional<Descent> openDescent(Target target, PdfGraphicsState stateAtDo,
                                                PdfMatrix toPage, int depth, List<Integer> entered,
                                                Source source, List<String> notes) {
        if (target == null || target.missing() || !target.isForm()) {
            return Optional.empty();
        }
        if (depth + 1 > MAX_DEPTH) {
            notes.add("the descent stopped at " + target.resourceName() + " (object "
                    + target.objNum() + "): " + MAX_DEPTH + " levels of forms is the ceiling"
                    + " (XObjectOps.MAX_DEPTH), so the items below it stay unnamed rather than"
                    + " costing the whole answer");
            return Optional.empty();
        }
        if (target.objNum() != null && entered != null && entered.contains(target.objNum())) {
            notes.add("the Do inside the descent names " + target.resourceName() + " again (object "
                    + target.objNum() + " already on the path " + entered + ") - the specification"
                    + " forbids neither a self-calling form nor a pair of forms calling each other,"
                    + " so the cycle is named and the descent stops there; the named item is still"
                    + " reported");
            return Optional.empty();
        }
        if (source == null) {
            notes.add("the descent was asked for, but this walk was given no way to read a form's"
                    + " content (XObjectOps.Source) - only the named answer is produced");
            return Optional.empty();
        }
        byte[] content = source.content(target.objNum(), notes);
        if (content == null) {
            return Optional.empty();
        }
        PdfResources resources = source.resourcesOf(target.objNum(),
                "form XObject " + target.resourceName(), notes);
        // S. 357, step 2: the /Matrix is concatenated onto the CTM, i.e. premultiplied with it.
        PdfGraphicsState next = stateAtDo.withCtm(stateAtDo.ctm().premultiply(target.matrix()));
        // S. 357, step 3: clip to the /BBox. In page space (decision 5), through the same route the
        // item's box took - which means: only when a page frame exists to put it into.
        if (target.bbox() != null) {
            Optional<PdfQuad> quad = toPage == null ? Optional.empty()
                    : bboxInSpace(target, stateAtDo.ctm(), toPage, PdfSpace.PAGE);
            if (quad.isPresent()) {
                PdfClipPath.Added added = next.clip().clipToBox(quad.get().bbox(),
                        "form XObject " + target.resourceName());
                next = next.withClip(added.clip());
                if (added.note() != null) {
                    notes.add(added.note());
                }
            } else if (toPage == null) {
                notes.add("the form XObject " + target.resourceName() + " was walked without its"
                        + " /BBox clip - a clip is stored in page space (4.4.3, S. 234/235) and this"
                        + " walk has no page frame to store it in");
            } else {
                notes.add("the /BBox of " + target.resourceName() + " could not be mapped into"
                        + " the walk's space, so the form was walked without its clip - the CTM is"
                        + " singular or the box holds no finite numbers");
            }
        }
        return Optional.of(new Descent(content, next, resources, depth + 1));
    }

    /** The name a {@code Do} names, or null when its operand is not a name. */
    public static String nameOf(List<Object> operands) {
        if (operands == null) {
            return null;
        }
        for (Object operand : operands) {
            if (operand instanceof PdfName name) {
                return name.toString();
            }
        }
        return null;
    }

    /** The unit square of the image space (4.2.1, S. 203), as a rectangle in that space. */
    public static PdfRect imageSpaceUnit() {
        return PdfRect.of(0, 0, 1, 1, PdfSpace.IMAGE);
    }

    /** The four corners of a quad as flat numbers, for a listing that wants numbers, not structure. */
    public static List<Object> points(PdfQuad quad) {
        List<Object> out = new ArrayList<>();
        if (quad == null) {
            return out;
        }
        for (PdfPoint point : quad.points()) {
            out.add(round(point.x()));
            out.add(round(point.y()));
        }
        return out;
    }

    /** The unit square of image space mapped like a box, for an inline image's item. */
    public static Optional<PdfQuad> imageUnitIn(PdfMatrix ctm, PdfMatrix toPage, String space) {
        return route(null, ctm, toPage).map(matrix -> matrix.map(imageSpaceUnit(), space));
    }

    // ---------------------------------------------------------------------------------------------
    // operand readers
    // ---------------------------------------------------------------------------------------------

    /** The array behind a dictionary value, or null. */
    static List<Object> listOf(Object value) {
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return null;
    }

    /** Whether every entry of a matrix operand is a number ({@code null} is a legal PDF operand). */
    private static boolean allNumbers(List<Object> values) {
        for (Object value : values) {
            if (!(value instanceof Number)) {
                return false;
            }
        }
        return true;
    }

    private static Integer integerOf(Object value) {
        if (value instanceof Long number) {
            return number > Integer.MIN_VALUE && number < Integer.MAX_VALUE
                    ? (int) (long) number : null;
        }
        if (value instanceof Integer number) {
            return number;
        }
        if (value instanceof PdfReference) {
            // A reference the caller chose not to dereference: no value, and no invented one.
            return null;
        }
        return null;
    }

    /** A rectangle written as four numbers, or null (a foreign kind, a short array, a lost object). */
    static PdfRect rectOf(Object value) {
        List<Object> numbers = listOf(value);
        if (numbers == null || numbers.size() != 4) {
            return null;
        }
        double[] read = new double[4];
        for (int i = 0; i < 4; i++) {
            Object each = numbers.get(i);
            if (!(each instanceof Number number)) {
                return null;
            }
            read[i] = number.doubleValue();
        }
        try {
            // Form space, not user space (4.9.3, S. 357) - the label is the whole point of reading
            // the box here instead of grabbing four numbers.
            return PdfRect.of(read[0], read[1], read[2], read[3], PdfSpace.FORM);
        } catch (PdfException e) {
            return null;
        }
    }

    private static List<Object> numbers(PdfRect rect) {
        List<Object> out = new ArrayList<>(4);
        out.add(round(rect.x1()));
        out.add(round(rect.y1()));
        out.add(round(rect.x2()));
        out.add(round(rect.y2()));
        return out;
    }

    private static List<Object> numbers(PdfMatrix matrix) {
        List<Object> out = new ArrayList<>(6);
        for (double value : matrix.toArray()) {
            out.add(round(value));
        }
        return out;
    }

    /** A coordinate to a millionth of a unit - the same rounding {@link PlacementSink} prints. */
    private static Double round(double value) {
        return Math.round(value * PlacementSink.ROUNDING) / PlacementSink.ROUNDING;
    }
}
