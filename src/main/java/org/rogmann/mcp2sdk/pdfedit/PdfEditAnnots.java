package org.rogmann.mcp2sdk.pdfedit;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationCircle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFreeText;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationHighlight;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationInk;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationRubberStamp;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationSquare;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationSquareCircle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationStrikeout;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationUnderline;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination;
import org.rogmann.mcp2sdk.pdf.geo.PdfPageSpace;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code annot.add} and {@code link.add}: annotations, and with them the appearance streams that
 * make a viewer show them.
 * <p>
 * An annotation is a dictionary in a page's {@code /Annots} - and that alone is half of the
 * truth: without an {@code /AP} appearance stream a viewer may draw nothing at all (the file
 * carries a note nobody sees). Both ops therefore end with
 * {@link PDAnnotation#constructAppearances(PDDocument)}, i.e. with PDFBox's appearance handlers,
 * and the answer says which type got a picture and which did not (a rubber stamp has no handler;
 * xpdf, gv and mupdf draw their own symbol there, Firefox draws nothing without an /AP and the
 * drawing with one - a stamp may bring its appearance as {@code ap:{stream, value}}, and the help
 * of {@code annot.add} names the two-call way for a stamp that already exists, §20.2).
 * </p>
 * <p>
 * The rectangle of an annotation is <b>default user space</b> by definition (12.5.2), so
 * {@code space} has the default {@code 'user'} here - the one place where the module's
 * "no default for the space" rule does not apply, and the help says why. {@code space:'page'} is
 * accepted all the same: it converts every point through the page frame
 * {@code pdf.placement()} prints and echoes the result, exactly like {@code draw.rect}.
 * </p>
 * <p>
 * Three rules the answer keeps: a {@code javascript:} URI is refused (this module executes
 * nothing), {@code /M} comes from the call's {@code dates} and never from the clock (a document
 * without dates stays without them), and in a tagged document the annotation's
 * {@code /StructParent} is <em>not</em> maintained - the answer says it (E4).
 * </p>
 */
final class PdfEditAnnots {

    /** Annotation types {@code annot.add} knows. */
    private static final List<String> TYPES = List.of("text", "freetext", "square", "circle",
            "highlight", "underline", "strikeout", "ink", "stamp");

    /** The symbols a text annotation may name (the handler of PDFBox draws these). */
    private static final List<String> TEXT_ICONS = List.of("Comment", "Key", "Note", "Help",
            "NewParagraph", "Paragraph", "Insert", "Circle", "Cross", "Star", "Check",
            "RightArrow", "RightPointer", "UpArrow", "UpLeftArrow", "CrossHairs");

    /** The three text markup types: their appearance handler draws nothing without a colour. */
    private static final Set<String> MARKUP_TYPES =
            Set.of("highlight", "underline", "strikeout");

    /** The two closed-shape types: they draw from /C (border) or /IC (interior). */
    private static final Set<String> SQUARE_LIKE = Set.of("square", "circle");

    /** Border width of an ink annotation that names none - the usual {@code [0 0 1]} of /Border. */
    private static final float DEFAULT_INK_WIDTH = 1f;

    /** The names a rubber stamp may carry (12.5.6.14, table 184). */
    private static final List<String> STAMP_NAMES = List.of("Approved", "Experimental",
            "NotApproved", "AsIs", "Expired", "NotForPublicRelease", "ForPublicRelease", "Draft",
            "ForComment", "TopSecret", "Departmental", "Confidential", "Final", "Sold");

    /** The highlight modes of a link (12.5.6.5, table 173). */
    private static final List<String> LINK_HIGHLIGHTS = List.of("none", "invert", "outline",
            "push");

    /** The annotation flag "Print" (bit 3) - without it a viewer need not print the annotation. */
    private static final int FLAG_PRINT = 4;

    /** Font size of a free-text annotation when the op does not name one (points). */
    private static final double DEFAULT_FREE_TEXT_SIZE = 10d;

    /** The keys a stamp's {@code ap} object may carry. */
    private static final Set<String> STAMP_AP_KEYS = Set.of("stream", "value", "filter");

    private PdfEditAnnots() {
        // Utility class
    }

    // -------------------------------------------------------------------
    // annot.add
    // -------------------------------------------------------------------

    /**
     * Adds an annotation of one of nine types to a page.
     * @param ctx the call context
     * @param op the op
     */
    static void add(PdfEditContext ctx, PdfEditOp op) {
        op.allow("page", "type", "rect", "space", "unit", "contents", "author", "subject",
                "color", "fill", "open", "icon", "quads", "inkList", "flags", "replyTo", "size",
                "lineWidth", "ap");
        int pageNum = op.page("page");
        String type = op.string("type").trim().toLowerCase(Locale.ROOT);
        if (!TYPES.contains(type)) {
            throw PdfEditException.usage(op.where() + ": unknown annotation type '" + type + "'",
                    "types are " + String.join(", ", TYPES) + " - the help shows one example per"
                            + " type");
        }
        PDPage page = PdfEditPages.requirePage(ctx, pageNum);
        String space = space(op);
        double factor = PdfEditGeometry.unitFactor(op);
        String contents = contents(op);
        PDColor color = PdfEditContent.colorOrNull(op, "color");
        // The handlers decide by colour whether there is anything to draw: the three text markups
        // and an ink stroke return early on a null /C (PDInkAppearanceHandler additionally on a
        // zero border width), and a square/circle always writes an /AP but paints nothing into it
        // when it has neither /C nor /IC. All of those leave an annotation that is in the file and
        // invisible - refused here instead of shipped.
        boolean squareLike = SQUARE_LIKE.contains(type);
        PDColor interior = squareLike ? PdfEditContent.colorOrNull(op, "fill") : null;
        if (color == null && (MARKUP_TYPES.contains(type) || "ink".equals(type))) {
            throw PdfEditException.usage(op.where() + ": a " + type + " annotation needs a"
                    + " 'color'",
                    "without a colour the appearance handler draws nothing, so the mark would be"
                            + " invisible - write color:'yellow' (the usual highlight) or another"
                            + " colour name, '#rrggbb' or an array of components");
        }
        if (squareLike && color == null && interior == null) {
            throw PdfEditException.usage(op.where() + ": a " + type + " annotation needs a"
                    + " 'color' or a 'fill'",
                    "the appearance handler paints the border from /C and the interior from /IC -"
                            + " with neither, the /AP stream it writes stays empty and the box is"
                            + " invisible; write color:'red' (the border), fill:'yellow' (the"
                            + " interior) or both");
        }
        Integer flagValue = op.intOrNull("flags");
        int flags = flagValue == null ? FLAG_PRINT : flagValue;

        PDAnnotation annotation = newAnnotation(type, op);
        double[] given = new double[4];
        double[] rect = rect(op, type, space, factor, page, pageNum, given);
        annotation.setRectangle(new PDRectangle((float) rect[0], (float) rect[1],
                (float) (rect[2] - rect[0]), (float) (rect[3] - rect[1])));
        annotation.setAnnotationFlags(flags);
        annotation.setPage(page);
        if (contents != null) {
            annotation.setContents(contents);
        }
        if (color != null) {
            annotation.setColor(color);
        }
        applyPayload(annotation, type, op);
        if (op.params().get("ap") != null) {
            if (!"stamp".equals(type)) {
                throw PdfEditException.usage(op.where() + ": 'ap' is the appearance of a stamp",
                        "every other type gets its appearance from PDFBox'"
                                + " constructAppearances(); a stamp has no handler - give it one"
                                + " with ap:{stream, value}");
            }
            applyStampAppearance(ctx, op, annotation, rect);
        }
        if (annotation instanceof PDAnnotationMarkup markup) {
            String author = op.stringOrNull("author");
            if (author != null) {
                markup.setTitlePopup(author);
            }
            String subject = op.stringOrNull("subject");
            if (subject != null) {
                markup.setSubject(subject);
            }
        }
        Double lineWidth = op.doubleOrNull("lineWidth");
        if (annotation instanceof PDAnnotationInk ink) {
            // A width of 0 is a silent no-op in PDFBox (its ink handler returns on a zero border
            // width) - and an ink annotation nobody paints is the one thing the op must not write
            // without saying it. The op names none = 1 pt, which is the usual /Border value.
            if (lineWidth != null && lineWidth <= 0) {
                throw PdfEditException.usage(op.where() + ": lineWidth is " + lineWidth
                        + " and an ink stroke of that width paints nothing",
                        "PDFBox' ink appearance handler returns on a zero border width; leave"
                                + " lineWidth out for the usual " + DEFAULT_INK_WIDTH + " pt");
            }
            // PDInkAppearanceHandler returns on a zero border width as well as on a missing
            // colour, and a file without /Border or /BS reads width 0 - so an ink annotation
            // always gets a /BS, with the usual one point when the op names none.
            PDBorderStyleDictionary border = new PDBorderStyleDictionary();
            border.setWidth(lineWidth == null ? DEFAULT_INK_WIDTH : lineWidth.floatValue());
            ink.setBorderStyle(border);
        } else if (lineWidth != null && annotation instanceof PDAnnotationSquareCircle circle) {
            PDBorderStyleDictionary border = new PDBorderStyleDictionary();
            border.setWidth(lineWidth.floatValue());
            circle.setBorderStyle(border);
        }
        applyModifiedDate(ctx, annotation);
        applyReplyTo(ctx, annotation, op);

        boolean appearance;
        try {
            annotation.constructAppearances(ctx.document());
            appearance = annotation.getNormalAppearanceStream() != null;
        } catch (IllegalArgumentException e) {
            throw PdfEditException.usage(op.where() + ": the appearance could not be built: "
                    + e.getMessage(),
                    "an annotation's text is drawn with a font - the Base-14 fonts cover WinAnsi,"
                            + " so a Cyrillic or CJK character has to stay out of contents");
        }
        // The appearance handlers do not only write /AP: a text annotation's symbol is 18 x 20
        // points anchored at the upper-left corner, so PDFBox moves /Rect, and a square or circle
        // is padded by half its border width. The answer names the rectangle the file now holds.
        double[] writtenRect = rectOf(annotation);

        addToPage(op, page, annotation);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("page", (long) pageNum);
        details.put("type", type);
        details.put("rect", numbers(writtenRect));
        details.put("hasAppearance", appearance);
        ctx.changed(op, type + " annotation added to page " + pageNum, details);
        if (differs(writtenRect, rect)) {
            details.put("rectRequested", numbers(rect));
            ctx.note("the appearance handler adjusted /Rect from " + numbers(rect) + " to "
                    + numbers(writtenRect) + ": the picture of a " + type + " annotation dictates"
                    + " its box (a text symbol is 18 x 20 points anchored at the upper-left corner,"
                    + " a square or circle is padded by half its border width, an ink stroke by twice"
                    + " its width). The answer names the"
                    + " rectangle the file holds - flags with NoZoom (bit 9, value 256) leave a text"
                    + " annotation's /Rect untouched");
        }
        if (!PdfEditGeometry.SPACE_USER.equals(space)) {
            ctx.putGeometry(PdfEditGeometry.echo(op, pageNum, space,
                    new double[]{given[0], given[1]}, new double[]{rect[0], rect[1]},
                    PdfEditGeometry.frame(page, op, pageNum), PdfEditGeometry.unitName(op)));
        }
        if (!appearance) {
            ctx.note("stamp".equals(type)
                    ? "a rubber stamp has no appearance handler in PDFBox: /Rect and /Name carry"
                            + " the annotation and the viewer draws its own symbol - that is what"
                            + " the specification allows; Firefox (pdf.js) draws nothing without an"
                            + " /AP and the drawing with one - give the stamp one with"
                            + " ap:{stream, value} (or object.add + object.set afterwards)"
                    : "the " + type + " annotation got no /AP appearance stream: the handler drew"
                            + " nothing, because one of its requirements is not met (a text markup"
                            + " needs /QuadPoints and a colour, a square/circle a box with area, a"
                            + " free text contents and a size) - a viewer that trusts /AP shows"
                            + " nothing there");
        }
        if (ctx.document().getDocumentCatalog().getStructureTreeRoot() != null) {
            ctx.note("the document is tagged: the annotation's /StructParent is not maintained"
                    + " (tag structure is E4) - a structure-aware viewer may report it as"
                    + " unclaimed");
        }
    }

    /**
     * Creates the annotation object of one type and applies its type-specific entries.
     * @param type the type name
     * @param op the op (for messages)
     * @return the annotation
     */
    private static PDAnnotation newAnnotation(String type, PdfEditOp op) {
        switch (type) {
            case "text": {
                PDAnnotationText text = new PDAnnotationText();
                String icon = op.stringOrNull("icon");
                if (icon != null) {
                    if (!TEXT_ICONS.contains(icon)) {
                        throw PdfEditException.usage(op.where() + ": unknown icon '" + icon + "'",
                                "a text annotation names one of " + String.join(", ", TEXT_ICONS));
                    }
                    text.setName(icon);
                }
                if (op.params().get("open") != null) {
                    text.setOpen(op.bool("open", false));
                }
                return text;
            }
            case "freetext": {
                PDAnnotationFreeText free = new PDAnnotationFreeText();
                Double givenSize = op.doubleOrNull("size");
                double size = givenSize == null ? DEFAULT_FREE_TEXT_SIZE : givenSize;
                if (size <= 0) {
                    throw PdfEditException.usage(op.where() + ": size is " + size,
                            "a free-text annotation is written at a font size greater than zero");
                }
                free.setDefaultAppearance("/Helv " + format(size) + " Tf 0 g");
                free.setQ(0);
                return free;
            }
            case "square":
                return new PDAnnotationSquare();
            case "circle":
                return new PDAnnotationCircle();
            case "highlight":
                return new PDAnnotationHighlight();
            case "underline":
                return new PDAnnotationUnderline();
            case "strikeout":
                return new PDAnnotationStrikeout();
            case "ink":
                return new PDAnnotationInk();
            case "stamp": {
                PDAnnotationRubberStamp stamp = new PDAnnotationRubberStamp();
                String givenName = op.stringOrNull("icon");
                String name = givenName == null ? "Draft" : givenName;
                if (!STAMP_NAMES.contains(name)) {
                    throw PdfEditException.usage(op.where() + ": unknown stamp '" + name + "'",
                            "a rubber stamp names one of " + String.join(", ", STAMP_NAMES));
                }
                stamp.setName(name);
                return stamp;
            }
            default:
                throw PdfEditException.usage(op.where() + ": unknown annotation type '" + type
                        + "'", "types are " + String.join(", ", TYPES));
        }
    }

    /**
     * The type-specific payload: quads for the three text markup types, the ink list for an ink
     * annotation, the interior colour of a square or circle.
     */
    private static void applyPayload(PDAnnotation annotation, String type, PdfEditOp op) {
        switch (type) {
            case "highlight", "underline", "strikeout": {
                if (op.params().get("quads") == null) {
                    throw PdfEditException.usage(op.where() + ": 'quads' is missing",
                            "a " + type + " annotation marks text with /QuadPoints: one array of"
                                    + " eight numbers per mark, [x1,y1, x2,y2, x3,y3, x4,y4], in"
                                    + " user space (or in page space with space:'page')");
                }
                double[] quads = floats(op.list("quads"), op, "quads");
                if (quads.length % 8 != 0 || quads.length == 0) {
                    throw PdfEditException.usage(op.where() + ": 'quads' holds " + quads.length
                            + " number(s)",
                            "each mark is eight numbers (four points), so an array of 8, 16, 24 …"
                                    + " numbers is expected");
                }
                ((PDAnnotationTextMarkup) annotation).setQuadPoints(toFloats(quads));
                break;
            }
            case "ink": {
                if (op.params().get("inkList") == null) {
                    throw PdfEditException.usage(op.where() + ": 'inkList' is missing",
                            "an ink annotation holds one array of points per stroke:"
                                    + " [[x1,y1],[x2,y2],…] - or a flat array of coordinate pairs");
                }
                ((PDAnnotationInk) annotation).setInkList(inkList(op));
                break;
            }
            case "square":
            case "circle": {
                PDColor interior = PdfEditContent.colorOrNull(op, "fill");
                if (interior != null) {
                    ((PDAnnotationSquareCircle) annotation).setInteriorColor(interior);
                }
                break;
            }
            default:
                break;
        }
    }

    /**
     * Gives a stamp the appearance the call brought: the op creates the form XObject, sets the
     * entries a form XObject needs ({@code /Type}, {@code /Subtype}, {@code /FormType},
     * {@code /BBox} the size of the {@code /Rect}, identity {@code /Matrix}) and the caller's
     * {@code value} entries, and makes it the annotation's {@code /AP /N}.
     * <p>
     * PDFBox has no appearance handler for a stamp, and without an {@code /AP} a viewer draws its
     * own symbol - or nothing at all (Firefox, §20.2 of the design). This is the one-call way to
     * give the stamp a picture of its own; the two-call way ({@code object.add} plus
     * {@code object.set}) stays for stamps that already exist in a file.
     * </p>
     * @param ctx the call context
     * @param op the op (for messages; its {@code ap} object is read here)
     * @param annotation the stamp annotation
     * @param rect the annotation rectangle in user space
     */
    private static void applyStampAppearance(PdfEditContext ctx, PdfEditOp op,
                                             PDAnnotation annotation, double[] rect) {
        Map<String, Object> appearance = op.map("ap");
        for (String key : appearance.keySet()) {
            if (!STAMP_AP_KEYS.contains(key)) {
                throw PdfEditException.usage(op.where() + ": 'ap' has no '" + key + "'",
                        "ap is {stream:<Uint8Array>, value:{…}, filter:'none'|'flate'} - the"
                                + " stream holds the operators of the appearance, value its"
                                + " dictionary");
            }
        }
        Object payload = appearance.get("stream");
        if (!(payload instanceof byte[] data)) {
            throw PdfEditException.usage(op.where() + ": 'ap.stream' is "
                            + PdfEditOp.describe(payload),
                    "'ap.stream' is a Uint8Array (or an array of numbers 0-255) with the"
                            + " operators of the appearance");
        }
        if (data.length > PdfEditLimits.MAX_STREAM_BYTES) {
            throw new PdfEditException("limit", op.where() + ": the ap stream holds "
                    + data.length + " bytes",
                    "at most " + PdfEditLimits.MAX_STREAM_BYTES + " bytes per stream");
        }
        Object givenFilter = appearance.get("filter");
        String filter = givenFilter == null ? "none"
                : String.valueOf(givenFilter).trim().toLowerCase(Locale.ROOT);
        if (!"none".equals(filter) && !"flate".equals(filter)) {
            throw PdfEditException.usage(op.where() + ": 'ap.filter' is '" + filter + "'",
                    "filter is 'none' (default) or 'flate'");
        }
        // No try-with-resources around the COSStream itself: the document owns it until the save
        // (object.add does the same); what is closed is the output stream writing into it.
        COSStream stream = ctx.document().getDocument().createCOSStream();
        try {
            try (OutputStream out = stream.createOutputStream("flate".equals(filter)
                    ? COSName.FLATE_DECODE : null)) {
                out.write(data);
            }
        } catch (IOException e) {
            throw new PdfEditException("broken", op.where() + ": the ap stream could not be"
                    + " written", "check the filter name ('none' or 'flate')", e);
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("Type", "/XObject");
        values.put("Subtype", "/Form");
        values.put("FormType", 1);
        values.put("BBox", List.of(0, 0, rect[2] - rect[0], rect[3] - rect[1]));
        values.put("Matrix", List.of(1, 0, 0, 1, 0, 0));
        Object given = appearance.get("value");
        if (given != null) {
            if (!(given instanceof Map<?, ?> map)) {
                throw PdfEditException.usage(op.where() + ": 'ap.value' is "
                                + PdfEditOp.describe(given),
                        "'ap.value' is the dictionary of the form XObject, e.g."
                                + " {Resources:{Font:{Helv:{ref:'$font'}}}}");
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                values.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            stream.setItem(PdfEditRaw.name(entry.getKey()),
                    PdfEditRaw.toCos(entry.getValue(), ctx, op, "/ap/value/" + entry.getKey()));
        }
        COSDictionary ap = new COSDictionary();
        ap.setItem(COSName.N, stream);
        ap.setDirect(true);
        annotation.getCOSObject().setItem(COSName.AP, ap);
    }

    /** {@code /M}: the call's modified date, never the clock (§6 of the design). */
    private static void applyModifiedDate(PdfEditContext ctx, PDAnnotation annotation) {
        Calendar modified = ctx.modified();
        if (modified != null) {
            annotation.setModifiedDate((Calendar) modified.clone());
        }
    }

    /** {@code /IRT} and {@code /RT}: this annotation answers another one. */
    private static void applyReplyTo(PdfEditContext ctx, PDAnnotation annotation, PdfEditOp op) {
        Integer target = op.intOrNull("replyTo");
        if (target == null) {
            return;
        }
        PDAnnotation other = findAnnotation(ctx.document(), target);
        if (other == null) {
            throw new PdfEditException("out-of-range", op.where() + ": there is no annotation "
                    + target + " in this document",
                    "pdf.pages(path, {annotations: true}) lists the annotations of every page"
                            + " with their object numbers");
        }
        if (!(annotation instanceof PDAnnotationMarkup markup)) {
            throw PdfEditException.usage(op.where() + ": replyTo needs a markup annotation",
                    "a reply is a text markup, free text, square, circle, ink or stamp annotation"
                            + " - a link has no /IRT entry");
        }
        markup.setInReplyTo(other);
        markup.setReplyType(PDAnnotationMarkup.RT_REPLY);
    }

    // -------------------------------------------------------------------
    // link.add
    // -------------------------------------------------------------------

    /**
     * Adds a link annotation: a URI action, a jump to a page or a named destination.
     * @param ctx the call context
     * @param op the op
     */
    static void link(PdfEditContext ctx, PdfEditOp op) {
        op.allow("page", "rect", "space", "unit", "url", "target", "dest", "contents",
                "highlight", "color", "lineWidth", "flags");
        int pageNum = op.page("page");
        PDPage page = PdfEditPages.requirePage(ctx, pageNum);
        String space = space(op);
        double factor = PdfEditGeometry.unitFactor(op);
        double[] given = new double[4];
        double[] rect = rect(op, "link", space, factor, page, pageNum, given);
        String url = op.stringOrNull("url");
        Integer target = op.intOrNull("target");
        String dest = op.stringOrNull("dest");
        int named = (url != null ? 1 : 0) + (target != null ? 1 : 0) + (dest != null ? 1 : 0);
        if (named != 1) {
            throw PdfEditException.usage(op.where() + ": a link needs exactly one destination",
                    "write url:'https://…', or target:<page> (1-based), or dest:'<name>' of a"
                            + " named destination - " + named + " were given");
        }
        PDAnnotationLink link = new PDAnnotationLink();
        link.setRectangle(new PDRectangle((float) rect[0], (float) rect[1],
                (float) (rect[2] - rect[0]), (float) (rect[3] - rect[1])));
        link.setPage(page);
        Integer linkFlags = op.intOrNull("flags");
        link.setAnnotationFlags(linkFlags == null ? FLAG_PRINT : linkFlags);
        String highlight = op.stringOrNull("highlight");
        if (highlight != null) {
            String mode = highlight.trim().toLowerCase(Locale.ROOT);
            if (!LINK_HIGHLIGHTS.contains(mode)) {
                throw PdfEditException.usage(op.where() + ": unknown highlight '" + highlight
                        + "'", "a link highlights with " + String.join(", ", LINK_HIGHLIGHTS));
            }
            link.setHighlightMode(switch (mode) {
                case "none" -> PDAnnotationLink.HIGHLIGHT_MODE_NONE;
                case "outline" -> PDAnnotationLink.HIGHLIGHT_MODE_OUTLINE;
                case "push" -> PDAnnotationLink.HIGHLIGHT_MODE_PUSH;
                default -> PDAnnotationLink.HIGHLIGHT_MODE_INVERT;
            });
        }
        PDColor color = PdfEditContent.colorOrNull(op, "color");
        if (color != null) {
            link.setColor(color);
        }
        Double linkWidth = op.doubleOrNull("lineWidth");
        if (linkWidth != null) {
            PDBorderStyleDictionary border = new PDBorderStyleDictionary();
            border.setWidth(linkWidth.floatValue());
            link.setBorderStyle(border);
        }
        if (op.stringOrNull("contents") != null) {
            link.setContents(contents(op));
        }
        String action;
        if (url != null) {
            String trimmed = url.trim();
            if (trimmed.regionMatches(true, 0, "javascript:", 0, "javascript:".length())) {
                throw new PdfEditException("unsupported", op.where() + ": the URI starts with"
                        + " 'javascript:'",
                        "this module executes nothing a document contains - a JavaScript URI"
                                + " would run in the viewer, so it is refused");
            }
            PDActionURI uri = new PDActionURI();
            uri.setURI(trimmed);
            link.setAction(uri);
            action = "uri";
        } else if (target != null) {
            if (target < 1 || target > ctx.document().getNumberOfPages()) {
                throw new PdfEditException("out-of-range", op.where() + ": target is " + target,
                        "the document has " + ctx.document().getNumberOfPages() + " page(s)");
            }
            PDPageFitDestination destination = new PDPageFitDestination();
            destination.setPage(ctx.document().getPage(target - 1));
            link.setDestination(destination);
            action = "page " + target;
        } else {
            link.setDestination(new PDNamedDestination(dest));
            action = "destination '" + dest + "'";
        }
        applyModifiedDate(ctx, link);
        boolean appearance;
        try {
            link.constructAppearances(ctx.document());
            appearance = link.getNormalAppearanceStream() != null;
        } catch (IllegalArgumentException e) {
            // A link's appearance is a border; a colour the handler cannot read is a refusal,
            // not a silently missing border.
            throw PdfEditException.usage(op.where() + ": the link's appearance could not be"
                    + " built: " + e.getMessage(),
                    "the border of a link is drawn from /C and /BS - write a colour name,"
                            + " '#rrggbb' or an array of components");
        }
        addToPage(op, page, link);

        double[] writtenRect = rectOf(link);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("page", (long) pageNum);
        details.put("type", "link");
        details.put("rect", numbers(writtenRect));
        details.put("hasAppearance", appearance);
        details.put("destination", action);
        if (differs(writtenRect, rect)) {
            details.put("rectRequested", numbers(rect));
            ctx.note("the appearance handler adjusted /Rect from " + numbers(rect) + " to "
                    + numbers(writtenRect) + " - the answer names the rectangle the file holds");
        }
        int objNum = objNumOf(ctx.document(), link.getCOSObject());
        if (objNum > 0) {
            details.put("objNum", (long) objNum);
        }
        ctx.changed(op, "link added to page " + pageNum + " (" + action + ")", details);
        if (!PdfEditGeometry.SPACE_USER.equals(space)) {
            ctx.putGeometry(PdfEditGeometry.echo(op, pageNum, space,
                    new double[]{given[0], given[1]}, new double[]{rect[0], rect[1]},
                    PdfEditGeometry.frame(page, op, pageNum), PdfEditGeometry.unitName(op)));
        }
        if (("destination '" + dest + "'").equals(action)) {
            ctx.note("the link points at the named destination '" + dest + "' - whether the name"
                    + " exists is not checked here (pdf.names(path, 'Dests') answers that); a"
                    + " viewport that cannot resolve it does nothing when the link is clicked");
        }
    }

    // -------------------------------------------------------------------
    // Shared readers
    // -------------------------------------------------------------------

    /** The space of an annotation op: default user space is the specification's default here. */
    private static String space(PdfEditOp op) {
        String value = op.stringOrNull("space");
        if (value == null) {
            return PdfEditGeometry.SPACE_USER;
        }
        String space = value.trim().toLowerCase(Locale.ROOT);
        if (PdfEditGeometry.SPACE_PAGE.equals(space) || PdfEditGeometry.SPACE_USER.equals(space)) {
            return space;
        }
        throw PdfEditException.usage(op.where() + ": space reads '" + value + "'",
                "'space' is 'user' (the specification's space for /Rect, the default here) or"
                        + " 'page' (origin top-left, y downwards)");
    }

    /**
     * The rectangle of the annotation in default user space, from {@code rect} - or, for a text
     * markup annotation without one, from the box around its quad points.
     * @return {@code [x1, y1, x2, y2]} with {@code x1 <= x2} and {@code y1 <= y2}
     */
    private static double[] rect(PdfEditOp op, String type, String space, double factor,
                                 PDPage page, int pageNum, double[] given) {
        List<?> raw = op.params().get("rect") == null ? null : op.list("rect");
        double[] corners;
        if (raw == null) {
            if (!List.of("highlight", "underline", "strikeout", "ink").contains(type)) {
                throw PdfEditException.usage(op.where() + ": 'rect' is missing",
                        "an annotation's /Rect is its place on the page, [x1,y1,x2,y2] in user"
                                + " space (space:'page' converts it)");
            }
            double[] points = type.equals("ink") ? flatInk(op) : floats(op.list("quads"), op,
                    "quads");
            corners = boxOf(points);
            if (corners != null) {
                System.arraycopy(corners, 0, given, 0, 4);
            }
            if (corners == null) {
                throw PdfEditException.usage(op.where() + ": 'rect' is missing and the points"
                        + " give no box",
                        "write rect:[x1,y1,x2,y2] - a mark without an area has no place on the"
                                + " page");
            }
        } else {
            if (raw.size() != 4) {
                throw PdfEditException.usage(op.where() + ": 'rect' holds " + raw.size()
                        + " number(s)", "rect is [x1, y1, x2, y2]");
            }
            corners = new double[4];
            for (int i = 0; i < 4; i++) {
                Object value = raw.get(i);
                if (!(value instanceof Number number)) {
                    throw PdfEditException.usage(op.where() + ": rect[" + i + "] is "
                            + PdfEditOp.describe(value), "rect is [x1, y1, x2, y2] in numbers");
                }
                given[i] = number.doubleValue();
                corners[i] = given[i] * factor;
            }
        }
        if (PdfEditGeometry.SPACE_PAGE.equals(space)) {
            PdfPageSpace frame = PdfEditGeometry.frame(page, op, pageNum);
            double[] first = PdfEditGeometry.toUser(frame, space, corners[0], corners[1], op);
            double[] second = PdfEditGeometry.toUser(frame, space, corners[2], corners[3], op);
            corners = new double[]{first[0], first[1], second[0], second[1]};
        }
        double x1 = Math.min(corners[0], corners[2]);
        double y1 = Math.min(corners[1], corners[3]);
        double x2 = Math.max(corners[0], corners[2]);
        double y2 = Math.max(corners[1], corners[3]);
        if (x2 - x1 <= 0 || y2 - y1 <= 0) {
            throw PdfEditException.usage(op.where() + ": the rectangle has no area",
                    "an annotation's /Rect needs a width and a height greater than zero"
                            + " ([x1,y1,x2,y2] with x1<x2 and y1<y2)");
        }
        return new double[]{x1, y1, x2, y2};
    }

    /** The contents of an annotation, bounded by {@link PdfEditLimits#MAX_ANNOTATION_CHARS}. */
    private static String contents(PdfEditOp op) {
        String contents = op.stringOrNull("contents");
        if (contents != null && contents.length() > PdfEditLimits.MAX_ANNOTATION_CHARS) {
            throw new PdfEditException("limit", op.where() + ": contents holds "
                    + contents.length() + " characters",
                    "at most " + PdfEditLimits.MAX_ANNOTATION_CHARS + " characters per"
                            + " annotation are accepted");
        }
        return contents;
    }

    /** A flat array of numbers from a nested list ({@code quads}, {@code inkList}). */
    private static double[] floats(List<?> values, PdfEditOp op, String key) {
        List<Double> numbers = new ArrayList<>();
        flatten(values, numbers, op, key);
        double[] result = new double[numbers.size()];
        for (int i = 0; i < numbers.size(); i++) {
            result[i] = numbers.get(i);
        }
        return result;
    }

    /** The same values as {@code float[]}, which is the array type PDFBox writes. */
    private static float[] toFloats(double[] values) {
        float[] result = new float[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (float) values[i];
        }
        return result;
    }

    private static void flatten(List<?> values, List<Double> numbers, PdfEditOp op, String key) {
        for (Object value : values) {
            if (value instanceof List<?> nested) {
                flatten(nested, numbers, op, key);
            } else if (value instanceof Number number) {
                numbers.add(number.doubleValue());
            } else {
                throw PdfEditException.usage(op.where() + ": " + key + " holds "
                        + PdfEditOp.describe(value), key + " holds numbers (points)");
            }
        }
    }

    /**
     * The strokes of an ink annotation. Three spellings mean the same thing and all three occur:
     * a flat list of coordinates ({@code [x1,y1,x2,y2,…]}), a list of points
     * ({@code [[x1,y1],[x2,y2],…]} - one stroke) and a list of strokes
     * ({@code [[x1,y1,x2,y2,…], [x1,y1,…]]}). The shape decides: if every entry is a pair, the
     * outer list is <em>one</em> stroke of points.
     */
    private static float[][] inkList(PdfEditOp op) {
        List<?> raw = op.list("inkList");
        boolean nested = !raw.isEmpty() && raw.get(0) instanceof List<?>;
        List<double[]> strokes = new ArrayList<>();
        if (nested && allPairs(raw)) {
            strokes.add(floats(raw, op, "inkList"));
        } else if (nested) {
            for (Object stroke : raw) {
                if (!(stroke instanceof List<?> points)) {
                    throw PdfEditException.usage(op.where() + ": inkList holds "
                            + PdfEditOp.describe(stroke),
                            "inkList is an array of strokes, each an array of points");
                }
                strokes.add(floats(points, op, "inkList"));
            }
        } else {
            strokes.add(floats(raw, op, "inkList"));
        }
        for (double[] stroke : strokes) {
            if (stroke.length < 4 || stroke.length % 2 != 0) {
                throw PdfEditException.usage(op.where() + ": a stroke holds " + stroke.length
                        + " number(s)",
                        "a stroke is a list of points, two numbers each - at least two points"
                                + " (four numbers)");
            }
        }
        float[][] result = new float[strokes.size()][];
        for (int i = 0; i < strokes.size(); i++) {
            result[i] = toFloats(strokes.get(i));
        }
        return result;
    }

    /** Whether every entry of a list is a pair of numbers (one point). */
    private static boolean allPairs(List<?> values) {
        for (Object value : values) {
            if (!(value instanceof List<?> pair) || pair.size() != 2) {
                return false;
            }
        }
        return !values.isEmpty();
    }

    /** The flat coordinate list of an ink annotation, for the box of a missing rectangle. */
    private static double[] flatInk(PdfEditOp op) {
        List<?> raw = op.list("inkList");
        List<Double> numbers = new ArrayList<>();
        flatten(raw, numbers, op, "inkList");
        double[] result = new double[numbers.size()];
        for (int i = 0; i < numbers.size(); i++) {
            result[i] = numbers.get(i);
        }
        return result;
    }

    /** The box around a flat list of coordinate pairs, or {@code null} when there is none. */
    private static double[] boxOf(double[] points) {
        if (points.length < 4) {
            return null;
        }
        double x1 = Double.MAX_VALUE;
        double y1 = Double.MAX_VALUE;
        double x2 = -Double.MAX_VALUE;
        double y2 = -Double.MAX_VALUE;
        for (int i = 0; i + 1 < points.length; i += 2) {
            x1 = Math.min(x1, points[i]);
            x2 = Math.max(x2, points[i]);
            y1 = Math.min(y1, points[i + 1]);
            y2 = Math.max(y2, points[i + 1]);
        }
        if (x2 - x1 <= 0 || y2 - y1 <= 0) {
            return null;
        }
        return new double[]{x1, y1, x2, y2};
    }

    /** The annotation with the given object number, or {@code null}. */
    private static PDAnnotation findAnnotation(PDDocument document, int objNum) {
        for (PDPage page : document.getPages()) {
            List<PDAnnotation> annotations;
            try {
                annotations = page.getAnnotations();
            } catch (IOException e) {
                continue;
            }
            for (PDAnnotation annotation : annotations) {
                if (objNumOf(document, annotation.getCOSObject()) == objNum) {
                    return annotation;
                }
            }
        }
        return null;
    }

    /** Puts an annotation into the page's {@code /Annots} and marks the page for the writer. */
    private static void addToPage(PdfEditOp op, PDPage page, PDAnnotation annotation) {
        try {
            page.getAnnotations().add(annotation);
        } catch (IOException e) {
            throw new PdfEditException("broken", op.where() + ": the page's /Annots could not be"
                    + " read: " + e.getMessage(),
                    "pdf.pages(path, {annotations: true}) shows the list; object.set can repair a"
                            + " broken /Annots", e);
        }
        page.getCOSObject().setNeedToBeUpdated(true);
    }

    /**
     * The object number of a dictionary, or {@code 0} when it has none (a new object gets its
     * number while writing - §15.1 of the design).
     */
    private static int objNumOf(PDDocument document, COSBase base) {
        for (COSObjectKey key : document.getDocument().getXrefTable().keySet()) {
            COSObject object = document.getDocument().getObjectFromPool(key);
            if (object != null && object.getObject() == base) {
                return (int) key.getNumber();
            }
        }
        return 0;
    }

    /** The {@code /Rect} an annotation holds now (the handler may have moved it). */
    private static double[] rectOf(PDAnnotation annotation) {
        PDRectangle rect = annotation.getRectangle();
        if (rect == null) {
            return new double[0];
        }
        return new double[]{rect.getLowerLeftX(), rect.getLowerLeftY(), rect.getUpperRightX(),
                rect.getUpperRightY()};
    }

    /** Whether two rectangles differ by more than a rounding step. */
    private static boolean differs(double[] first, double[] second) {
        if (first.length != second.length) {
            return first.length > 0;
        }
        for (int i = 0; i < first.length; i++) {
            if (Math.abs(first[i] - second[i]) > 1e-6) {
                return true;
            }
        }
        return false;
    }

    private static List<Object> numbers(double[] values) {
        List<Object> out = new ArrayList<>(values.length);
        for (double value : values) {
            out.add(PdfEditGeometry.round(value));
        }
        return out;
    }

    private static String format(double value) {
        if (value == Math.rint(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    /** The types {@code annot.add} knows, for {@code status()}. */
    static Set<String> types() {
        return new LinkedHashSet<>(TYPES);
    }
}
