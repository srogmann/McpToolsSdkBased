package org.rogmann.mcp2sdk.poi;

import org.apache.poi.sl.draw.DrawPaint;
import org.apache.poi.sl.usermodel.PaintStyle;
import org.apache.poi.sl.usermodel.PlaceableShape;
import org.apache.poi.sl.usermodel.ShapeType;
import org.openxmlformats.schemas.drawingml.x2006.main.CTLineEndProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.CTLineProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.CTShapeProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.STLineEndLength;
import org.openxmlformats.schemas.drawingml.x2006.main.STLineEndType;
import org.openxmlformats.schemas.drawingml.x2006.main.STLineEndWidth;
import org.openxmlformats.schemas.presentationml.x2006.main.CTShape;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFAutoShape;
import org.apache.poi.xslf.usermodel.XSLFConnectorShape;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSimpleShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFSlideLayout;
import org.apache.poi.xslf.usermodel.XSLFSlideMaster;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.apache.poi.xslf.usermodel.XSLFTextShape;

import java.awt.geom.Rectangle2D;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Toolbox for accessing PowerPoint {@code .pptx} files via Apache POI (XSLF).
 * <p>
 * Like the xlsx-{@link PoiToolBox}, this class provides a handle-based API that can be
 * exposed to JavaScript via GraalVM ProxyExecutable bindings.
 * </p>
 *
 * <h3>Handle system</h3>
 * <ul>
 *   <li>{@code show-&lt;n&gt;} – handle for an {@link XMLSlideShow}</li>
 *   <li>{@code slide-&lt;n&gt;} – handle for an {@link XSLFSlide}</li>
 *   <li>{@code sp-&lt;n&gt;} – handle for a {@link XSLFShape}</li>
 *   <li>{@code tr-&lt;n&gt;} – handle for an {@link XSLFTextRun}</li>
 * </ul>
 * <p>
 * Shapes carry stable IDs/names ({@code getShapeId()}/{@code getShapeName()}) which are
 * shown when enumerating slides, so a shape can be addressed reproducibly.
 * </p>
 *
 * <h3>Instance / concurrency</h3>
 * <p>
 * All handle state lives at instance level. The bridge creates a fresh instance per
 * JavaScript tool-call, so parallel users/calls are isolated. Presentations are released
 * via {@link #closeAllPresentations()}.
 * </p>
 */
public class PptxToolBox {

    private static final Logger LOG = LoggerFactory.getLogger(PptxToolBox.class);

    /** Map: presentation-handle -> XMLSlideShow */
    private final Map<String, XMLSlideShow> shows = new ConcurrentHashMap<>();

    /** Map: slide-handle -> XSLFSlide */
    private final Map<String, XSLFSlide> slides = new ConcurrentHashMap<>();

    /** Map: shape-handle -> XSLFShape */
    private final Map<String, XSLFShape> shapes = new ConcurrentHashMap<>();

    /** Map: run-handle -> XSLFTextRun */
    private final Map<String, XSLFTextRun> runs = new ConcurrentHashMap<>();

    /** Map: slide-handle -> show-handle (for cleanup on close) */
    private final Map<String, String> slideShow = new ConcurrentHashMap<>();

    /** Map: shape-handle -> show-handle (for cleanup on close) */
    private final Map<String, String> shapeShow = new ConcurrentHashMap<>();

    /** Map: run-handle -> show-handle (for cleanup on close) */
    private final Map<String, String> runShow = new ConcurrentHashMap<>();

    /** Map: layout-handle -> XSLFSlideLayout */
    private final Map<String, XSLFSlideLayout> layouts = new ConcurrentHashMap<>();

    /** Map: layout-handle -> show-handle (for cleanup on close) */
    private final Map<String, String> layoutShow = new ConcurrentHashMap<>();

    /** Counter for generating unique handles */
    private final AtomicLong handleCounter = new AtomicLong();

    /**
     * Creates a PptxToolBox with isolated handle state (one per tool-call).
     */
    public PptxToolBox() {
        // Per-call instance
    }

    // ========================================================================
    // Presentation-level operations
    // ========================================================================

    /**
     * Opens an existing .pptx file and returns a presentation handle.
     * @param filePath path relative to the base directory
     * @return presentation handle (e.g. "show-1")
     */
    public String openFile(String filePath) {
        Path path = PoiToolBox.resolveSafePath(filePath);
        if (!Files.exists(path)) {
            throw new PoiUserRuntimeException("File not found: " + filePath);
        }
        if (!Files.isRegularFile(path)) {
            throw new PoiUserRuntimeException("Not a file: " + filePath);
        }
        try (FileInputStream fis = new FileInputStream(path.toFile())) {
            XMLSlideShow show = new XMLSlideShow(fis);
            String handle = generateHandle("show");
            shows.put(handle, show);
            LOG.info("Opened pptx: {} -> {}", filePath, handle);
            return handle;
        } catch (IOException e) {
            LOG.error("Failed to open pptx: " + filePath, e);
            throw new PoiUserRuntimeException("Failed to open file: " + filePath, e);
        }
    }

    /**
     * Creates a new empty presentation and returns a handle.
     * @return presentation handle (e.g. "show-2")
     */
    public String createPresentation() {
        XMLSlideShow show = new XMLSlideShow();
        String handle = generateHandle("show");
        shows.put(handle, show);
        LOG.info("Created new pptx -> {}", handle);
        return handle;
    }

    /**
     * Saves a presentation to the given file path.
     * @param showHandle presentation handle
     * @param filePath target path relative to the base directory
     */
    public void save(String showHandle, String filePath) {
        XMLSlideShow show = shows.get(showHandle);
        if (show == null) {
            throw new PoiUserRuntimeException("Presentation not found (invalid handle): " + showHandle);
        }
        Path path = PoiToolBox.resolveSafePath(filePath);
        try {
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            try (FileOutputStream fos = new FileOutputStream(path.toFile())) {
                show.write(fos);
            }
            LOG.info("Saved pptx {} to {}", showHandle, filePath);
        } catch (IOException e) {
            LOG.error("Failed to save pptx " + showHandle + " to " + filePath, e);
            throw new PoiUserRuntimeException("Failed to save presentation to: " + filePath, e);
        }
    }

    /**
     * Closes a presentation and releases resources (also invalidates its slide/shape/run handles).
     * @param showHandle presentation handle
     */
    public void close(String showHandle) {
        XMLSlideShow show = shows.remove(showHandle);
        if (show == null) {
            LOG.warn("Presentation not found (already closed?): {}", showHandle);
            return;
        }
        removeMapped(showHandle);
        try {
            show.close();
        } catch (IOException e) {
            LOG.warn("Error closing presentation " + showHandle, e);
        }
        LOG.info("Closed pptx: {}", showHandle);
    }

    /**
     * Closes all open presentations of this instance.
     * <p>
     * Called automatically at the end of a JavaScript tool-call. Presentations that were
     * modified but not saved via {@link #save(String, String)} lose their changes.
     * </p>
     */
    public void closeAllPresentations() {
        for (String handle : Set.copyOf(shows.keySet())) {
            close(handle);
        }
    }

    // ========================================================================
    // Slide / shape / run operations
    // ========================================================================

    /**
     * Enumerates the slides of a presentation with their shapes (and text runs for text shapes).
     * <p>
     * Each slide entry contains {@code slideNumber}, {@code h} and {@code shapes} (list of
     * shape maps with {@code h}, {@code name}, {@code shapeId}, {@code shapeType}, and for text
     * shapes also {@code text} and {@code paragraphs} containing {@code runs} with {@code h}
     * and {@code text}).
     * </p>
     * @param showHandle presentation handle
     * @return JSON-compatible Map with key "slides"
     */
    public Map<String, Object> getSlides(String showHandle) {
        XMLSlideShow show = shows.get(showHandle);
        if (show == null) {
            throw new PoiUserRuntimeException("Presentation not found (invalid handle): " + showHandle);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("presentation", showHandle);
        List<Map<String, Object>> slidesList = new ArrayList<>();
        for (XSLFSlide slide : show.getSlides()) {
            Map<String, Object> sm = new LinkedHashMap<>();
            String sh = generateHandle("slide");
            slides.put(sh, slide);
            slideShow.put(sh, showHandle);
            sm.put("slideNumber", slide.getSlideNumber());
            sm.put("h", sh);
            List<Map<String, Object>> shapesList = new ArrayList<>();
            for (XSLFShape shape : slide.getShapes()) {
                shapesList.add(shapeToMap(shape, showHandle, 0));
            }
            sm.put("shapes", shapesList);
            slidesList.add(sm);
        }
        result.put("slides", slidesList);
        return result;
    }

    /**
     * Returns the page (slide) size of a presentation.
     * @param showHandle presentation handle
     * @return Map with "width" and "height" (in points)
     */
    public Map<String, Object> getPageSize(String showHandle) {
        XMLSlideShow show = getShow(showHandle);
        java.awt.Dimension dim = show.getPageSize();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("width", (double) dim.getWidth());
        m.put("height", (double) dim.getHeight());
        return m;
    }

    /**
     * Creates a new slide at the end of the presentation.
     * @param showHandle presentation handle
     * @return slide handle
     */
    public String createSlide(String showHandle) {
        XMLSlideShow show = getShow(showHandle);
        XSLFSlide slide = show.createSlide();
        return registerSlide(slide, showHandle);
    }

    /**
     * Creates a new slide without a standard layout (no placeholders).
     * <p>
     * A slide is created via the default layout and then all placeholder shapes are
     * removed, yielding a clean, empty slide (no outline/list templates).
     * </p>
     * @param showHandle presentation handle
     * @return slide handle
     */
    public String createBlankSlide(String showHandle) {
        String slideHandle = createSlide(showHandle);
        XSLFSlide slide = slides.get(slideHandle);
        List<XSLFShape> placeholders = new ArrayList<>();
        for (XSLFShape shape : slide.getShapes()) {
            if (shape.isPlaceholder()) {
                placeholders.add(shape);
            }
        }
        for (XSLFShape shape : placeholders) {
            slide.removeShape(shape);
        }
        return slideHandle;
    }

    /**
     * Lists the available slide layouts (templates) of a presentation.
     * @param showHandle presentation handle
     * @return JSON-compatible Map with key "layouts" (each with "h" and "name")
     */
    public Map<String, Object> getLayouts(String showHandle) {
        XMLSlideShow show = getShow(showHandle);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("presentation", showHandle);
        List<Map<String, Object>> list = new ArrayList<>();
        for (XSLFSlideMaster master : show.getSlideMasters()) {
            for (XSLFSlideLayout layout : master.getSlideLayouts()) {
                Map<String, Object> lm = new LinkedHashMap<>();
                String lh = generateHandle("ly");
                layouts.put(lh, layout);
                layoutShow.put(lh, showHandle);
                lm.put("h", lh);
                lm.put("name", layout.getName());
                list.add(lm);
            }
        }
        result.put("layouts", list);
        return result;
    }

    /**
     * Creates a new slide based on a chosen layout (template).
     * @param showHandle presentation handle
     * @param layoutHandle layout handle (from {@link #getLayouts(String)})
     * @return slide handle
     */
    public String createSlideFromLayout(String showHandle, String layoutHandle) {
        XMLSlideShow show = getShow(showHandle);
        XSLFSlideLayout layout = layouts.get(layoutHandle);
        if (layout == null) {
            throw new PoiUserRuntimeException("Layout not found (invalid handle): " + layoutHandle);
        }
        XSLFSlide slide = show.createSlide(layout);
        return registerSlide(slide, showHandle);
    }

    /**
     * Creates a new slide that uses the same layout (template) as an existing slide.
     * @param showHandle presentation handle
     * @param slideHandle handle of an existing slide whose layout should be reused
     * @return slide handle
     */
    public String createSlideLike(String showHandle, String slideHandle) {
        XSLFSlide src = getSlide(slideHandle);
        XSLFSlideLayout layout = src.getSlideLayout();
        if (layout == null) {
            throw new PoiUserRuntimeException("The slide has no layout: " + slideHandle);
        }
        XMLSlideShow show = getShow(showHandle);
        XSLFSlide slide = show.createSlide(layout);
        return registerSlide(slide, showHandle);
    }

    /**
     * Duplicates an existing slide: creates a new slide with the same layout and copies
     * all shapes (content) from the source slide.
     * @param showHandle presentation handle
     * @param slideHandle handle of the slide to duplicate
     * @return slide handle of the new (copied) slide
     */
    public String duplicateSlide(String showHandle, String slideHandle) {
        XMLSlideShow show = getShow(showHandle);
        XSLFSlide src = getSlide(slideHandle);
        XSLFSlide newSlide = show.createSlide(src.getSlideLayout());
        newSlide.importContent(src);
        return registerSlide(newSlide, showHandle);
    }

    /**
     * Creates a new text box on a slide.
     * @param slideHandle slide handle
     * @param text initial text
     * @return shape handle
     */
    public String createTextBox(String slideHandle, String text) {
        XSLFSlide slide = slides.get(slideHandle);
        if (slide == null) {
            throw new PoiUserRuntimeException("Slide not found (invalid handle): " + slideHandle);
        }
        XSLFTextBox tb = slide.createTextBox();
        tb.setText(text == null ? "" : text);
        String spH = generateHandle("sp");
        shapes.put(spH, tb);
        shapeShow.put(spH, slideShow.get(slideHandle));
        return spH;
    }

    /**
     * Returns the text of a text shape (or null if the shape holds no text).
     * @param shapeHandle shape handle
     * @return shape text or null
     */
    public String getShapeText(String shapeHandle) {
        XSLFTextShape textShape = asTextShape(shapeHandle);
        return textShape != null ? textShape.getText() : null;
    }

    /**
     * Reads the content of a table shape as a JSON-compatible structure.
     * <p>
     * The returned map contains {@code rowCount}, {@code colCount} and {@code rows}
     * (each row with {@code rowNum} and {@code cells}). Each cell is a text-shape map
     * with {@code text} and {@code paragraphs}/{@code runs} (including run handles and
     * styling), plus its {@code col} index.
     * </p>
     * @param shapeHandle handle of a table shape (shapeType "TABLE")
     * @return Map with the table content
     * @throws PoiUserRuntimeException if the shape is not a table
     */
    public Map<String, Object> getTableData(String shapeHandle) {
        XSLFShape shape = getShape(shapeHandle);
        if (!(shape instanceof XSLFTable table)) {
            throw new PoiUserRuntimeException(
                    "Shape is not a table: " + shapeHandle
                    + " (type: " + shape.getClass().getSimpleName() + ")");
        }
        String showHandle = shapeShow.get(shapeHandle);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("table", shapeHandle);
        result.put("rowCount", table.getNumberOfRows());
        result.put("colCount", table.getNumberOfColumns());

        List<XSLFTableRow> tableRows = table.getRows();
        List<Map<String, Object>> rowsList = new ArrayList<>();
        for (int r = 0; r < tableRows.size(); r++) {
            XSLFTableRow row = tableRows.get(r);
            List<XSLFTableCell> rowCells = row.getCells();
            Map<String, Object> rowMap = new LinkedHashMap<>();
            rowMap.put("rowNum", r);
            List<Map<String, Object>> cellsList = new ArrayList<>();
            for (int c = 0; c < table.getNumberOfColumns(); c++) {
                XSLFTableCell cell = c < rowCells.size() ? rowCells.get(c) : null;
                Map<String, Object> cellMap;
                if (cell != null) {
                    cellMap = textShapeToMap(cell, showHandle);
                } else {
                    cellMap = new LinkedHashMap<>();
                    cellMap.put("text", "");
                }
                cellMap.put("col", c);
                cellsList.add(cellMap);
            }
            rowMap.put("cells", cellsList);
            rowsList.add(rowMap);
        }
        result.put("rows", rowsList);
        return result;
    }

    /**
     * Reads only the cell texts of a table shape as a 2D structure.
     * <p>
     * The returned map contains {@code rowCount}, {@code colCount} and {@code rows}
     * (a list of rows, each a list of cell texts). Empty/merged cells are represented
     * as empty strings.
     * </p>
     * @param shapeHandle handle of a table shape (shapeType "TABLE")
     * @return Map with the cell texts as a 2D list under key "rows"
     * @throws PoiUserRuntimeException if the shape is not a table
     */
    public Map<String, Object> getTableText(String shapeHandle) {
        XSLFShape shape = getShape(shapeHandle);
        if (!(shape instanceof XSLFTable table)) {
            throw new PoiUserRuntimeException(
                    "Shape is not a table: " + shapeHandle
                    + " (type: " + shape.getClass().getSimpleName() + ")");
        }
        List<List<String>> data = new ArrayList<>();
        List<XSLFTableRow> tableRows = table.getRows();
        for (XSLFTableRow row : tableRows) {
            List<XSLFTableCell> rowCells = row.getCells();
            List<String> rowList = new ArrayList<>();
            for (int c = 0; c < table.getNumberOfColumns(); c++) {
                XSLFTableCell cell = c < rowCells.size() ? rowCells.get(c) : null;
                rowList.add(cell != null && cell.getText() != null ? cell.getText() : "");
            }
            data.add(rowList);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rowCount", table.getNumberOfRows());
        result.put("colCount", table.getNumberOfColumns());
        result.put("rows", data);
        return result;
    }

    /**
     * Replaces the whole text of a text shape.
     * @param shapeHandle shape handle
     * @param text new text
     */
    public void setShapeText(String shapeHandle, String text) {
        XSLFTextShape textShape = asTextShapeChecked(shapeHandle);
        textShape.setText(text == null ? "" : text);
    }

    /**
     * Appends a text paragraph to a text shape.
     * @param shapeHandle shape handle
     * @param text text to append
     */
    public void appendParagraph(String shapeHandle, String text) {
        XSLFTextShape textShape = asTextShapeChecked(shapeHandle);
        textShape.appendText(text == null ? "" : text, true);
    }

    /**
     * Adds a new text box at the given position/size.
     * @param slideHandle slide handle
     * @param text initial text
     * @param x x-position in points
     * @param y y-position in points
     * @param width width in points
     * @param height height in points
     * @return shape handle
     */
    public String addTextBox(String slideHandle, String text, double x, double y, double width, double height) {
        XSLFSlide slide = getSlide(slideHandle);
        XSLFTextBox tb = slide.createTextBox();
        tb.setAnchor(new Rectangle2D.Double(x, y, width, height));
        tb.setText(text == null ? "" : text);
        return registerShape(tb, slideShow.get(slideHandle));
    }

    /**
     * Adds a new auto shape (e.g. a rectangle) at the given position/size.
     * @param slideHandle slide handle
     * @param text initial text
     * @param x x-position in points
     * @param y y-position in points
     * @param width width in points
     * @param height height in points
     * @return shape handle
     */
    public String createShape(String slideHandle, String text, double x, double y, double width, double height) {
        XSLFSlide slide = getSlide(slideHandle);
        XSLFAutoShape shape = slide.createAutoShape();
        shape.setAnchor(new Rectangle2D.Double(x, y, width, height));
        shape.setText(text == null ? "" : text);
        return registerShape(shape, slideShow.get(slideHandle));
    }

    /**
     * Creates an arrow line from a start point to an end point.
     * <p>
     * The line is drawn as a {@code LINE} auto shape spanning the bounding box of the two
     * points, with horizontal/vertical flips applied so that it always points from
     * {@code (x1,y1)} to {@code (x2,y2)}, plus an arrowhead at the end.
     * </p>
     * @param slideHandle slide handle
     * @param x1 start x
     * @param y1 start y
     * @param x2 end x
     * @param y2 end y
     * @return shape handle
     */
    public String createLine(String slideHandle, double x1, double y1, double x2, double y2) {
        XSLFSlide slide = getSlide(slideHandle);
        double dx = x2 - x1;
        double dy = y2 - y1;
        double minX = Math.min(x1, x2);
        double minY = Math.min(y1, y2);
        XSLFAutoShape shape = slide.createAutoShape();
        shape.setShapeType(ShapeType.LINE);
        shape.setAnchor(new Rectangle2D.Double(minX, minY, Math.abs(dx), Math.abs(dy)));
        // A LINE shape draws from top-left to bottom-right by default; flips reverse the direction.
        shape.setFlipHorizontal(dx < 0);
        shape.setFlipVertical(dy < 0);
        // Arrowhead at the end of the line (set directly in the XML a:ln/a:tailEnd)
        CTShape ct = (CTShape) shape.getXmlObject();
        CTShapeProperties spPr = ct.getSpPr();
        if (spPr == null) {
            spPr = ct.addNewSpPr();
        }
        CTLineProperties ln = spPr.isSetLn() ? spPr.getLn() : spPr.addNewLn();
        CTLineEndProperties tailEnd = CTLineEndProperties.Factory.newInstance();
        tailEnd.setType(STLineEndType.Enum.forInt(STLineEndType.INT_TRIANGLE));
        tailEnd.setW(STLineEndWidth.Enum.forInt(STLineEndWidth.INT_MED));
        tailEnd.setLen(STLineEndLength.Enum.forInt(STLineEndLength.INT_MED));
        ln.setTailEnd(tailEnd);
        return registerShape(shape, slideShow.get(slideHandle));
    }

    /**
     * Moves a shape to a new position (keeps its size).
     * @param shapeHandle shape handle
     * @param x x-position in points
     * @param y y-position in points
     */
    public void setShapePosition(String shapeHandle, double x, double y) {
        PlaceableShape<?, ?> ps = getPlaceable(shapeHandle);
        Rectangle2D a = ps.getAnchor();
        ps.setAnchor(new Rectangle2D.Double(x, y, a.getWidth(), a.getHeight()));
    }

    /**
     * Resizes a shape (keeps its position).
     * @param shapeHandle shape handle
     * @param width width in points
     * @param height height in points
     */
    public void setShapeSize(String shapeHandle, double width, double height) {
        PlaceableShape<?, ?> ps = getPlaceable(shapeHandle);
        Rectangle2D a = ps.getAnchor();
        ps.setAnchor(new Rectangle2D.Double(a.getX(), a.getY(), width, height));
    }

    /**
     * Rotates a shape by the given angle (degrees).
     * @param shapeHandle shape handle
     * @param degrees rotation in degrees
     */
    public void setShapeRotation(String shapeHandle, double degrees) {
        PlaceableShape<?, ?> ps = getPlaceable(shapeHandle);
        ps.setRotation(degrees);
    }

    /**
     * Changes the geometry of an auto shape (e.g. "RECT", "OVAL", "ROUND_RECT").
     * @param shapeHandle shape handle
     * @param typeName geometry type name
     */
    public void setShapeGeometry(String shapeHandle, String typeName) {
        XSLFShape shape = getShape(shapeHandle);
        if (!(shape instanceof XSLFAutoShape auto)) {
            throw new PoiUserRuntimeException(
                    "Shape is not an auto shape (geometry cannot be changed): " + shapeHandle);
        }
        try {
            auto.setShapeType(ShapeType.valueOf(typeName.trim().toUpperCase().replace('-', '_')));
        } catch (IllegalArgumentException e) {
            throw new PoiUserRuntimeException("Unknown shape geometry: '" + typeName + "'. "
                    + "Known types include e.g. RECT, ROUND_RECT, OVAL, LINE.");
        }
    }

    /**
     * Removes a shape from its parent (slide or group).
     * @param shapeHandle shape handle
     */
    public void removeShape(String shapeHandle) {
        XSLFShape shape = getShape(shapeHandle);
        if (shape.getParent() instanceof XSLFSlide slide) {
            slide.removeShape(shape);
        } else if (shape.getParent() instanceof XSLFGroupShape group) {
            group.removeShape(shape);
        } else {
            throw new PoiUserRuntimeException(
                    "Cannot remove shape: unsupported parent (already removed?): " + shapeHandle);
        }
        shapes.remove(shapeHandle);
        shapeShow.remove(shapeHandle);
    }

    /**
     * Sets the filling color of a shape.
     * @param shapeHandle shape handle
     * @param hexColor hex color (e.g. "729FCF" or "#729FCF")
     */
    public void setShapeFillColor(String shapeHandle, String hexColor) {
        XSLFSimpleShape simple = asSimple(shapeHandle);
        simple.setFillColor(PoiColorUtil.parseHexColor(hexColor));
    }

    /**
     * Sets the line (outline) color of a shape.
     * @param shapeHandle shape handle
     * @param hexColor hex color (e.g. "7F59AE" or "#7F59AE")
     */
    public void setShapeLineColor(String shapeHandle, String hexColor) {
        XSLFSimpleShape simple = asSimple(shapeHandle);
        simple.setLineColor(PoiColorUtil.parseHexColor(hexColor));
    }

    /**
     * Sets the line width of a shape (in points).
     * @param shapeHandle shape handle
     * @param width width in points
     */
    public void setShapeLineWidth(String shapeHandle, double width) {
        XSLFSimpleShape simple = asSimple(shapeHandle);
        simple.setLineWidth(width);
    }

    // ========================================================================
    // Run formatting
    // ========================================================================

    /**
     * Sets the text of a run.
     * @param runHandle run handle
     * @param text new text
     */
    public void setRunText(String runHandle, String text) {
        XSLFTextRun run = getRun(runHandle);
        run.setText(text);
    }

    /**
     * Sets the bold property of a run.
     * @param runHandle run handle
     * @param bold true for bold
     */
    public void setRunBold(String runHandle, boolean bold) {
        XSLFTextRun run = getRun(runHandle);
        run.setBold(bold);
    }

    /**
     * Sets the italic property of a run.
     * @param runHandle run handle
     * @param italic true for italic
     */
    public void setRunItalic(String runHandle, boolean italic) {
        XSLFTextRun run = getRun(runHandle);
        run.setItalic(italic);
    }

    /**
     * Sets the font size of a run (in points).
     * @param runHandle run handle
     * @param sizePoints font size in points
     */
    public void setRunFontSize(String runHandle, double sizePoints) {
        XSLFTextRun run = getRun(runHandle);
        run.setFontSize(sizePoints);
    }

    /**
     * Sets the font name of a run (e.g. "Arial", "Calibri").
     * @param runHandle run handle
     * @param fontName font name
     */
    public void setRunFontFamily(String runHandle, String fontName) {
        XSLFTextRun run = getRun(runHandle);
        run.setFontFamily(fontName);
    }

    /**
     * Sets the font color of a run.
     * @param runHandle run handle
     * @param hexColor hex color (e.g. "FF0000" or "#FF0000")
     */
    public void setRunFontColor(String runHandle, String hexColor) {
        XSLFTextRun run = getRun(runHandle);
        run.setFontColor(PoiColorUtil.parseHexColor(hexColor));
    }

    // ========================================================================
    // Help / Documentation
    // ========================================================================

    /**
     * Returns a help text describing the pptx toolbox API.
     * @return help text
     */
    public String help() {
        return """
                PPTX Toolbox API for .pptx access (Apache POI XSLF)
                ====================================================

                All file paths are relative to the project base directory.

                NOTE: pptx.save(showHandle, path) MUST be called explicitly to persist
                changes. At the end of the JavaScript run all presentations of this tool
                call are automatically closed and unsaved changes are lost.

                --- Presentation operations ---
                pptx.openFile(path)            - Open .pptx file, returns showHandle
                pptx.createPresentation()      - Create a new presentation, returns showHandle
                pptx.save(showHandle, path)    - Save presentation to file
                pptx.close(showHandle)         - Close a presentation

                --- Slides / shapes ---
                pptx.getSlides(show)           - List slides; shapes include x/y/width/height,
                                                   rotation, geometry, colors and text runs
                                                   (runs also carry fontSize/bold/italic/
                                                   fontFamily/fontColor)
                pptx.getPageSize(show)         - Page/slide size (width and height in points)
                pptx.createSlide(show)         - Append a slide (default layout), returns slideHandle
                pptx.createBlankSlide(show)    - Append a slide WITHOUT the default layout
                pptx.getLayouts(show)          - List available slide layouts (templates)
                pptx.createSlideFromLayout(show,ly) - Append a slide using a chosen layout
                pptx.createSlideLike(show,slide)    - Append a slide using an existing slide's layout
                pptx.duplicateSlide(show,slide)     - Append a slide as a copy of an existing slide
                                                       (same layout AND content)
                pptx.createTextBox(slide, txt) - Create a text box, returns shapeHandle
                pptx.getShapeText(sp)          - Text of a text shape (null if none)
                pptx.setShapeText(sp, txt)     - Replace the text of a text shape
                pptx.appendParagraph(sp, txt)  - Append a text paragraph
                pptx.getTableData(sp)          - Read a table (sp of shapeType "TABLE") as
                                                   {rowCount, colCount, rows:[{rowNum,cells:
                                                   [{col,text,paragraphs,runs}]}]}
                pptx.getTableText(sp)          - Read only the cell texts of a table as
                                                   {rowCount, colCount, rows:[[...],...]}
                Note: shapes expose a semantic "shapeType" (TABLE, GROUP, PICTURE, CHART,
                CONNECTOR, TEXT_BOX, AUTO_SHAPE, SIMPLE_SHAPE) plus the raw "javaClass".
                For tables, combine getSlides (find shapeType "TABLE") with getTableData/getTableText.

                --- More shape operations ---
                pptx.addTextBox(slide,t,w,h,h)  - Create a positioned text box
                pptx.createShape(slide,t,w,h,h) - Create a positioned auto shape
                pptx.setShapePosition(sp,x,y)   - Move a shape
                pptx.setShapeSize(sp,w,h)       - Resize a shape
                pptx.setShapeRotation(sp,deg)   - Rotate a shape
                pptx.setShapeGeometry(sp,type)  - Set geometry (RECT, OVAL, ROUND_RECT, ...)
                pptx.createLine(slide,x1,y1,x2,y2)- Create an arrow line between two points
                pptx.setShapeFillColor(sp,c)    - Set filling color of a shape
                pptx.setShapeLineColor(sp,c)    - Set line (outline) color
                pptx.setShapeLineWidth(sp,w)    - Set line width in points
                pptx.removeShape(sp)            - Remove a shape

                --- Run formatting (runs from getSlides) ---
                pptx.setRunText(run, t)        - Set run text
                pptx.setRunBold(run, b)        - Set bold (true/false)
                pptx.setRunItalic(run, i)      - Set italic (true/false)
                pptx.setRunFontSize(run, s)    - Set font size in points
                pptx.setRunFontFamily(run, fn) - Set font name (e.g. "Arial")
                pptx.setRunFontColor(run, c)   - Set font color (hex e.g. "FF0000")

                Typical workflow (find-then-edit):
                1. var slides = pptx.getSlides(show);   // read slide/shape/run handles
                2. var sp = slides.slides[0].shapes[0].h;
                3. pptx.setShapeText(sp, "Neuer Titel");
                4. pptx.save(show, "out.pptx");
                """;
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    /**
     * Classifies a shape into a stable, language-independent semantic type name.
     * <p>
     * The raw Java class name remains available via the {@code javaClass} field.
     * </p>
     */
    private static String classifyShapeType(XSLFShape shape) {
        if (shape instanceof XSLFTable) {
            return "TABLE";
        }
        if (shape instanceof XSLFGroupShape) {
            return "GROUP";
        }
        if (shape instanceof XSLFPictureShape) {
            return "PICTURE";
        }
        if (shape instanceof XSLFConnectorShape) {
            return "CONNECTOR";
        }
        if (shape instanceof XSLFTextBox) {
            return "TEXT_BOX";
        }
        if (shape instanceof XSLFAutoShape) {
            return "AUTO_SHAPE";
        }
        if (shape instanceof XSLFSimpleShape) {
            return "SIMPLE_SHAPE";
        }
        return shape.getClass().getSimpleName();
    }

    /**
     * Extracts the text content of a text shape (including paragraphs and runs with
     * styling) into a JSON-compatible map with keys {@code text} and {@code paragraphs}.
     * <p>
     * Shared by {@link #shapeToMap(XSLFShape, String, int)} (for text shapes) and
     * {@link #getTableData(String)} (for table cells, since a cell is a text shape).
     * </p>
     */
    private Map<String, Object> textShapeToMap(XSLFTextShape textShape, String showHandle) {
        Map<String, Object> textMap = new LinkedHashMap<>();
        textMap.put("text", textShape.getText());
        List<Map<String, Object>> paraList = new ArrayList<>();
        for (XSLFTextParagraph para : textShape.getTextParagraphs()) {
            List<Map<String, Object>> runList = new ArrayList<>();
            for (XSLFTextRun run : para.getTextRuns()) {
                Map<String, Object> rm = new LinkedHashMap<>();
                String rH = generateHandle("tr");
                runs.put(rH, run);
                runShow.put(rH, showHandle);
                rm.put("h", rH);
                rm.put("text", run.getRawText());
                // Font / color info for comparing and copying run styling
                Double fontSize = run.getFontSize();
                if (fontSize != null) {
                    rm.put("fontSize", fontSize);
                }
                boolean bold = run.isBold();
                rm.put("bold", bold);
                boolean italic = run.isItalic();
                rm.put("italic", italic);
                String fontFamily = run.getFontFamily();
                if (fontFamily != null) {
                    rm.put("fontFamily", fontFamily);
                }
                PaintStyle fontPaint = run.getFontColor();
                if (fontPaint instanceof PaintStyle.SolidPaint solid) {
                    java.awt.Color fontColor =
                            DrawPaint.applyColorTransform(solid.getSolidColor());
                    if (fontColor != null) {
                        rm.put("fontColor", PoiColorUtil.toHex(fontColor));
                    }
                }
                runList.add(rm);
            }
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("runs", runList);
            paraList.add(pm);
        }
        textMap.put("paragraphs", paraList);
        return textMap;
    }

    /**
     * Builds a JSON-compatible map for a shape, including position/size/rotation,
     * geometry, colors and (for text shapes) paragraphs/runs.
     */
    private Map<String, Object> shapeToMap(XSLFShape shape, String showHandle, int depth) {
        Map<String, Object> shapeMap = new LinkedHashMap<>();
        String spH = generateHandle("sp");
        shapes.put(spH, shape);
        shapeShow.put(spH, showHandle);
        shapeMap.put("h", spH);
        shapeMap.put("name", shape.getShapeName());
        shapeMap.put("shapeId", shape.getShapeId());
        shapeMap.put("shapeType", classifyShapeType(shape));
        shapeMap.put("javaClass", shape.getClass().getSimpleName());

        // Position / size / rotation (only shapes implementing PlaceableShape)
        if (shape instanceof PlaceableShape<?, ?> ps) {
            Rectangle2D anchor = ps.getAnchor();
            if (anchor != null) {
                shapeMap.put("x", anchor.getX());
                shapeMap.put("y", anchor.getY());
                shapeMap.put("width", anchor.getWidth());
                shapeMap.put("height", anchor.getHeight());
            }
            shapeMap.put("rotation", ps.getRotation());
        }

        // Geometry and colors (for simple/auto shapes)
        if (shape instanceof XSLFSimpleShape simple) {
            if (simple instanceof XSLFAutoShape auto) {
                shapeMap.put("geometry", String.valueOf(auto.getShapeType()));
            }
            java.awt.Color fill = simple.getFillColor();
            if (fill != null) {
                shapeMap.put("fillColor", PoiColorUtil.toHex(fill));
            }
            java.awt.Color line = simple.getLineColor();
            if (line != null) {
                shapeMap.put("lineColor", PoiColorUtil.toHex(line));
            }
        }
        if (shape instanceof XSLFTable) {
            shapeMap.put("table", true);
        }

        // Text content
        if (shape instanceof XSLFTextShape textShape) {
            shapeMap.putAll(textShapeToMap(textShape, showHandle));
        }

        // Nested shapes of groups (bounded depth)
        if (shape instanceof XSLFGroupShape group && depth < 3) {
            List<Map<String, Object>> subList = new ArrayList<>();
            for (XSLFShape sub : group.getShapes()) {
                subList.add(shapeToMap(sub, showHandle, depth + 1));
            }
            shapeMap.put("subShapes", subList);
        }
        return shapeMap;
    }

    /**
     * Registers a shape handle and returns the handle.
     */
    private String registerShape(XSLFShape shape, String showHandle) {
        String spH = generateHandle("sp");
        shapes.put(spH, shape);
        shapeShow.put(spH, showHandle);
        return spH;
    }

    private XSLFSlide getSlide(String slideHandle) {
        XSLFSlide slide = slides.get(slideHandle);
        if (slide == null) {
            throw new PoiUserRuntimeException("Slide not found (invalid handle): " + slideHandle);
        }
        return slide;
    }

    private XMLSlideShow getShow(String showHandle) {
        XMLSlideShow show = shows.get(showHandle);
        if (show == null) {
            throw new PoiUserRuntimeException("Presentation not found (invalid handle): " + showHandle);
        }
        return show;
    }

    private String registerSlide(XSLFSlide slide, String showHandle) {
        String sh = generateHandle("slide");
        slides.put(sh, slide);
        slideShow.put(sh, showHandle);
        return sh;
    }

    private XSLFSimpleShape asSimple(String shapeHandle) {
        XSLFShape shape = getShape(shapeHandle);
        if (!(shape instanceof XSLFSimpleShape simple)) {
            throw new PoiUserRuntimeException("Shape does not support fill/line styling: " + shapeHandle);
        }
        return simple;
    }

    private PlaceableShape<?, ?> getPlaceable(String shapeHandle) {
        XSLFShape shape = getShape(shapeHandle);
        if (!(shape instanceof PlaceableShape<?, ?> ps)) {
            throw new PoiUserRuntimeException(
                    "Shape is not placeable (cannot be moved/resized/rotated): " + shapeHandle);
        }
        return ps;
    }

    private XSLFTextRun getRun(String runHandle) {
        XSLFTextRun run = runs.get(runHandle);
        if (run == null) {
            throw new PoiUserRuntimeException("Run not found (invalid handle): " + runHandle);
        }
        return run;
    }

    private XSLFShape getShape(String shapeHandle) {
        XSLFShape shape = shapes.get(shapeHandle);
        if (shape == null) {
            throw new PoiUserRuntimeException("Shape not found (invalid handle): " + shapeHandle);
        }
        return shape;
    }

    private XSLFTextShape asTextShape(String shapeHandle) {
        XSLFShape shape = getShape(shapeHandle);
        return shape instanceof XSLFTextShape textShape ? textShape : null;
    }

    private XSLFTextShape asTextShapeChecked(String shapeHandle) {
        XSLFTextShape textShape = asTextShape(shapeHandle);
        if (textShape == null) {
            throw new PoiUserRuntimeException(
                    "Shape is not a text shape (invalid handle or no text): " + shapeHandle);
        }
        return textShape;
    }

    /**
     * Removes slide/shape/run handles belonging to a presentation.
     */
    private void removeMapped(String showHandle) {
        Set<String> slideHandles = new HashSet<>();
        slideShow.entrySet().removeIf(e -> {
            if (showHandle.equals(e.getValue())) {
                slideHandles.add(e.getKey());
                return true;
            }
            return false;
        });
        slides.keySet().removeAll(slideHandles);

        Set<String> shapeHandles = new HashSet<>();
        shapeShow.entrySet().removeIf(e -> {
            if (showHandle.equals(e.getValue())) {
                shapeHandles.add(e.getKey());
                return true;
            }
            return false;
        });
        shapes.keySet().removeAll(shapeHandles);

        Set<String> runHandles = new HashSet<>();
        runShow.entrySet().removeIf(e -> {
            if (showHandle.equals(e.getValue())) {
                runHandles.add(e.getKey());
                return true;
            }
            return false;
        });
        runs.keySet().removeAll(runHandles);

        Set<String> layoutHandles = new HashSet<>();
        layoutShow.entrySet().removeIf(e -> {
            if (showHandle.equals(e.getValue())) {
                layoutHandles.add(e.getKey());
                return true;
            }
            return false;
        });
        layouts.keySet().removeAll(layoutHandles);
    }

    private String generateHandle(String prefix) {
        return prefix + "-" + handleCounter.incrementAndGet();
    }
}
