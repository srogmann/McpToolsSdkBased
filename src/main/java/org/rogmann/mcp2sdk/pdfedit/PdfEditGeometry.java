package org.rogmann.mcp2sdk.pdfedit;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageTree;
import org.rogmann.mcp2sdk.pdf.PdfException;
import org.rogmann.mcp2sdk.pdf.geo.PdfMatrix;
import org.rogmann.mcp2sdk.pdf.geo.PdfPageSpace;
import org.rogmann.mcp2sdk.pdf.geo.PdfRect;
import org.rogmann.mcp2sdk.pdf.geo.PdfSpace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The coordinate frame of the structured ops: which of the two spaces a caller writes in, how a
 * length unit becomes points, and the echo that makes the conversion visible in the answer.
 * <p>
 * There is no default space. "y=0 at the top" and "y=0 at the bottom" are both in use, and a
 * silent choice turns a title at y=70 into a title at the bottom of the page. So every op that
 * places something names its space, and every answer echoes what the numbers became.
 * </p>
 * <p>
 * The conversion itself is not computed here: {@link PdfPageSpace} is the frame of the reading
 * module ({@code pdf.placement().frame}) and it is built from the same four page entries
 * (MediaBox, CropBox, Rotate, UserUnit). Using it is the promise of §3.4 - {@code pdfedit} does not
 * calculate a second page frame that could disagree with the one {@code pdf} prints.
 * </p>
 */
final class PdfEditGeometry {

    /** Page space: origin at the top-left of the visible box, y downwards, unit one point. */
    static final String SPACE_PAGE = "page";

    /** Default user space: the space the page's own content stream is written in, y upwards. */
    static final String SPACE_USER = "user";

    /** Length units an op may name, mapped to points per unit. */
    private static final Map<String, Double> UNITS = Map.of(
            "pt", 1d,
            "point", 1d,
            "points", 1d,
            "mm", 72d / 25.4d,
            "cm", 72d / 2.54d,
            "in", 72d,
            "inch", 72d);

    private PdfEditGeometry() {
        // Utility class
    }

    // -------------------------------------------------------------------
    // Space and unit
    // -------------------------------------------------------------------

    /**
     * Reads the mandatory {@code space} parameter.
     * @param op the op
     * @return {@link #SPACE_PAGE} or {@link #SPACE_USER}
     */
    static String space(PdfEditOp op) {
        String value = op.stringOrNull("space");
        if (value == null) {
            throw PdfEditException.usage(op.where() + ": 'space' is missing",
                    "'space' is 'page' (origin top-left, y downwards) or 'user' (default user"
                            + " space, y upwards) - there is no default, because the two differ"
                            + " by a mirror");
        }
        String space = value.trim().toLowerCase(Locale.ROOT);
        if (SPACE_PAGE.equals(space) || SPACE_USER.equals(space)) {
            return space;
        }
        throw PdfEditException.usage(op.where() + ": space reads '" + value + "'",
                "'space' is 'page' (origin top-left, y downwards) or 'user' (default user"
                        + " space, y upwards)");
    }

    /**
     * Reads the optional {@code unit} parameter and returns points per unit.
     * <p>
     * The unit applies to the lengths of the op (positions and sizes); a font size and a line
     * width stay in points, because they belong to the glyph and the pen, not to the page.
     * </p>
     * @param op the op
     * @return points per unit (1 for a missing parameter)
     */
    static double unitFactor(PdfEditOp op) {
        String unit = op.stringOrNull("unit");
        if (unit == null) {
            return 1d;
        }
        Double factor = UNITS.get(unit.trim().toLowerCase(Locale.ROOT));
        if (factor == null) {
            throw PdfEditException.usage(op.where() + ": unknown unit '" + unit + "'",
                    "unit is 'pt' (the default), 'mm', 'cm' or 'in'");
        }
        return factor;
    }

    /** @return the unit name an op names, normalized to {@code pt}, {@code mm}, {@code cm} or {@code in} */
    static String unitName(PdfEditOp op) {
        String unit = op.stringOrNull("unit");
        if (unit == null) {
            return "pt";
        }
        String value = unit.trim().toLowerCase(Locale.ROOT);
        if (value.equals("mm") || value.equals("cm")) {
            return value;
        }
        if (value.equals("in") || value.equals("inch")) {
            return "in";
        }
        return "pt";
    }

    // -------------------------------------------------------------------
    // The frame of a page
    // -------------------------------------------------------------------

    /**
     * Builds the frame of a page from the four entries a page dictionary names.
     * <p>
     * The values are read the way {@code pdf} reads them, not the way PDFBox would resolve them:
     * the CropBox is the one the file names (PDFBox clips it to the MediaBox, which would move the
     * page-space origin), and the boxes are inherited through the page tree as Table 3.27 says.
     * </p>
     * @param page the page
     * @param op the op (for the message)
     * @param pageNum the 1-based page number (for the message)
     * @return the frame
     * @throws PdfEditException with code {@code broken} when the page has no MediaBox and with
     *         code {@code unsupported} when its frame cannot be used (an odd {@code /Rotate}, a
     *         {@code UserUnit} that measures nothing, a box without extent)
     */
    static PdfPageSpace frame(PDPage page, PdfEditOp op, int pageNum) {
        COSDictionary dict = page.getCOSObject();
        PdfRect media = box(dict, COSName.MEDIA_BOX, op, pageNum);
        if (media == null) {
            throw new PdfEditException("broken", op.where() + ": page " + pageNum
                    + " names no MediaBox",
                    "the MediaBox is the one box a page must have (Table 3.27) - pdf.pages()"
                            + " shows what the reader sees; object.set can write one");
        }
        PdfRect crop = box(dict, COSName.CROP_BOX, op, pageNum);
        Double userUnit = number(PDPageTree.getInheritableAttribute(dict, COSName.USER_UNIT));
        Integer rotate = wholeNumber(PDPageTree.getInheritableAttribute(dict, COSName.ROTATE));
        PdfPageSpace frame;
        try {
            frame = PdfPageSpace.of(media, crop, userUnit, rotate);
        } catch (PdfException e) {
            throw new PdfEditException("broken", op.where() + ": page " + pageNum
                    + " has no usable page frame: " + e.getMessage(),
                    "pdf.structure() shows the page dictionary; object.set can repair a box", e);
        }
        if (!frame.isUsable()) {
            throw new PdfEditException("unsupported", op.where() + ": page " + pageNum
                    + " has no usable page frame: " + frame.defect(),
                    "the same frame makes pdf.placement() refuse its coordinates - repair"
                            + " /Rotate, /UserUnit or the box first (object.set), or write in"
                            + " space:'user'");
        }
        return frame;
    }

    /**
     * One inheritable page box as a rectangle of default user space.
     * @param dict the page dictionary
     * @param key the entry ({@code /MediaBox} or {@code /CropBox})
     * @param op the op (for the message)
     * @param pageNum the page number (for the message)
     * @return the rectangle, or {@code null} when the page names none
     */
    private static PdfRect box(COSDictionary dict, COSName key, PdfEditOp op, int pageNum) {
        COSBase value = PDPageTree.getInheritableAttribute(dict, key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof COSArray array) || array.size() != 4) {
            throw new PdfEditException("broken", op.where() + ": /" + key.getName() + " of page "
                    + pageNum + " is not an array of four numbers",
                    "pdf.object() shows the entry, object.set can write a correct box");
        }
        double[] four = new double[4];
        for (int i = 0; i < 4; i++) {
            Double number = number(array.getObject(i));
            if (number == null) {
                throw new PdfEditException("broken", op.where() + ": /" + key.getName()
                        + " of page " + pageNum + " holds a value that is not a number",
                        "a page box is an array of four numbers (3.8.4)");
            }
            four[i] = number;
        }
        return PdfRect.of(four[0], four[1], four[2], four[3], PdfSpace.DEFAULT_USER);
    }

    private static Double number(COSBase base) {
        return base instanceof COSNumber n ? (double) n.floatValue() : null;
    }

    private static Integer wholeNumber(COSBase base) {
        if (base instanceof COSNumber n) {
            return Math.round(n.floatValue());
        }
        return null;
    }

    // -------------------------------------------------------------------
    // Point and rectangle
    // -------------------------------------------------------------------

    /**
     * A point of an op, in points, converted into default user space.
     * @param frame the page frame
     * @param space {@code 'page'} or {@code 'user'}
     * @param x x in the op's unit
     * @param y y in the op's unit
     * @param op the op (for the message)
     * @return the point in default user space
     */
    static double[] toUser(PdfPageSpace frame, String space, double x, double y, PdfEditOp op) {
        if (SPACE_USER.equals(space)) {
            return new double[]{x, y};
        }
        PdfMatrix matrix = frame.pageToDefaultUser().orElseThrow(() -> new PdfEditException(
                "unsupported", op.where() + ": the page has no page space",
                "use space:'user', or repair the page frame first"));
        return matrix.mapPoint(x, y);
    }

    /**
     * The matrix that maps page coordinates into default user space - the one a content stream
     * applies as {@code cm} to draw in page coordinates.
     * @param frame the page frame
     * @param op the op (for the message)
     * @return the matrix
     */
    static PdfMatrix pageToUser(PdfPageSpace frame, PdfEditOp op) {
        return frame.pageToDefaultUser().orElseThrow(() -> new PdfEditException("unsupported",
                op.where() + ": the page has no page space",
                "use space:'user', or repair the page frame first"));
    }

    /**
     * The text matrix for a line placed at a point of the chosen space.
     * <p>
     * The chain is written in the order the point travels, and the order is the whole content of
     * this method. Text space has its y axis pointing up; page space counts y downwards, so in
     * page space the text is <b>flipped first</b> ({@code 1 0 0 -1}), then turned, then moved to
     * its anchor, and only then mapped into default user space. Skipping the flip puts every line
     * upside down, and composing the other way round (user matrix first) turns the text of a
     * rotated page the wrong way - both are one-line mistakes that a page with {@code /Rotate 0}
     * hides completely.
     * </p>
     * <p>
     * {@code rotate} is counted counterclockwise <em>on the displayed page</em>. In page space
     * that is the negative angle (its y axis is flipped), in default user space the positive one.
     * </p>
     * @param frame the page frame
     * @param space {@code 'page'} or {@code 'user'}
     * @param x anchor x in the op's unit
     * @param y anchor y in the op's unit
     * @param rotate degrees counterclockwise on the displayed page
     * @param op the op (for the message)
     * @return the matrix for {@code Tm}
     */
    static PdfMatrix textMatrix(PdfPageSpace frame, String space, double x, double y,
                                double rotate, PdfEditOp op) {
        if (!SPACE_PAGE.equals(space)) {
            PdfMatrix matrix = rotate == 0d ? PdfMatrix.identity()
                    : PdfMatrix.rotation(rotate);
            return matrix.multiply(PdfMatrix.translation(x, y));
        }
        PdfMatrix matrix = PdfMatrix.scaling(1, -1);
        if (rotate != 0d) {
            matrix = matrix.multiply(PdfMatrix.rotation(-rotate));
        }
        matrix = matrix.multiply(PdfMatrix.translation(x, y));
        return matrix.multiply(pageToUser(frame, op));
    }

    /**
     * A point in points, echoed with the conversion so that a sign error is visible in the answer
     * and not first in the document.
     * @param op the op
     * @param pageNum the page number
     * @param space {@code 'page'} or {@code 'user'}
     * @param given the point as the op wrote it (in the op's unit)
     * @param user the same point in default user space (points)
     * @param frame the page frame
     * @param unit the unit name of the op
     * @return the echo
     */
    static Map<String, Object> echo(PdfEditOp op, int pageNum, String space, double[] given,
                                    double[] user, PdfPageSpace frame, String unit) {
        Map<String, Object> echo = new LinkedHashMap<>();
        echo.put("op", op.name());
        echo.put("page", (long) pageNum);
        echo.put("space", space);
        echo.put("given", numbers(given));
        echo.put("user", numbers(user));
        echo.put("unit", unit);
        echo.put("pageWidth", round(frame.pageWidth()));
        echo.put("pageHeight", round(frame.pageHeight()));
        echo.put("rotation", (long) frame.rotate());
        if (frame.userUnit() != 1d) {
            echo.put("userUnit", round(frame.userUnit()));
        }
        if (!PdfPageSpace.BASIS_MEDIA_BOX.equals(frame.basis())) {
            echo.put("basis", frame.basis());
        }
        return echo;
    }

    private static List<Object> numbers(double[] values) {
        List<Object> list = new ArrayList<>(values.length);
        for (double value : values) {
            list.add(round(value));
        }
        return list;
    }

    /**
     * Rounds to four decimals and prints a whole number without its fraction - a page coordinate
     * is not a measurement of the universe, and {@code 792} reads better than {@code 792.0}.
     * @param value the value
     * @return a {@link Long} for a whole number, a {@link Double} otherwise
     */
    static Number round(double value) {
        double rounded = Math.round(value * 1e4) / 1e4d;
        if (Math.abs(rounded) < 1e15 && rounded == Math.rint(rounded)) {
            return (long) rounded;
        }
        return rounded;
    }
}
