package org.rogmann.mcp2sdk.poi;

import org.apache.poi.ooxml.POIXMLTypeLoader;
import org.apache.poi.ooxml.util.DocumentHelper;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.LineSpacingRule;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFAbstractNum;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHeaderFooter;
import org.apache.poi.xwpf.usermodel.XWPFNumbering;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFSDT;
import org.apache.poi.xwpf.usermodel.XWPFSettings;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlException;
import org.apache.xmlbeans.XmlObject;
import org.apache.xmlbeans.XmlToken;
import org.openxmlformats.schemas.drawingml.x2006.main.CTNonVisualDrawingProps;
import org.openxmlformats.schemas.drawingml.x2006.main.CTPositiveSize2D;
import org.openxmlformats.schemas.drawingml.x2006.wordprocessingDrawing.CTInline;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBorder;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTc;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcBorders;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STBorder;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STStyleType;

import javax.imageio.ImageIO;
import javax.xml.namespace.QName;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Toolbox for accessing Word {@code .docx} files via Apache POI (XWPF).
 * <p>
 * Like the xlsx-{@link PoiToolBox}, this class provides a handle-based API that can be
 * exposed to JavaScript via GraalVM ProxyExecutable bindings. A Word document is
 * modelled as an ordered sequence of body-level paragraphs; each paragraph contains
 * runs (text units with a uniform formatting).
 * </p>
 *
 * <h3>Handle system</h3>
 * <ul>
 *   <li>{@code doc-&lt;n&gt;} – handle for an {@link XWPFDocument}</li>
 *   <li>{@code p-&lt;n&gt;} – handle for an {@link XWPFParagraph}</li>
 *   <li>{@code r-&lt;n&gt;} – handle for an {@link XWPFRun}</li>
 *   <li>{@code t-&lt;n&gt;} – handle for an {@link XWPFTable} (from createTable/getTables)</li>
 *   <li>{@code tc-&lt;n&gt;} – handle for an {@link XWPFTableCell} (from getCellProps)</li>
 *   <li>{@code dwg-&lt;n&gt;} – handle for an inline drawing (the canvas of a sketch)</li>
 *   <li>{@code shp-&lt;n&gt;} – handle for a shape or a nested group inside a drawing</li>
 * </ul>
 *
 * <h3>Instance / concurrency</h3>
 * <p>
 * All handle state lives at instance level. The bridge creates a fresh instance per
 * JavaScript tool-call, so parallel users/calls are isolated. Workbooks are released
 * via {@link #closeAllDocuments()}.
 * </p>
 *
 * <h3>Error handling</h3>
 * <p>
 * On errors a {@link PoiUserRuntimeException} is thrown with a user-friendly message;
 * detailed information goes to the SLF4J log.
 * </p>
 */
public class DocxToolBox {

    private static final Logger LOG = LoggerFactory.getLogger(DocxToolBox.class);

    /** Smallest font size (pt) accepted by {@link #setRunFontSize(String, double)}. */
    public static final double MIN_FONT_PT = 1.0;

    /** Largest font size (pt) accepted by {@link #setRunFontSize(String, double)}. */
    public static final double MAX_FONT_PT = 4000.0;

    /** OOXML: twips per centimetre (1440 twips per inch). */
    private static final double TWIPS_PER_CM = 1440.0 / 2.54;

    /** OOXML: twips per point. */
    private static final double TWIPS_PER_PT = 20.0;

    /** Largest table {@link #createTable(String, int, int, Map)} will build. */
    public static final int MAX_TABLE_ROWS = 5000;

    /** OOXML allows up to 63 columns per table. */
    public static final int MAX_TABLE_COLS = 63;

    /** OOXML: EMU per centimetre - the unit every drawing setter converts to. */
    public static final double EMU_PER_CM = 360_000.0;

    /** OOXML: EMU per point (line widths are given in pt, like in pptx). */
    public static final double EMU_PER_PT = 12_700.0;

    /** Largest drawing/shape length (cm) accepted by {@link #createDrawing(String, Map)}. */
    public static final double MAX_DRAWING_CM = 500.0;

    /** Largest |offset| (cm) a shape or group may be placed at. */
    public static final double MAX_OFFSET_CM = 500.0;

    /** Largest line width (pt), same ceiling as pptx. */
    public static final double MAX_LINE_WIDTH_PT = 500.0;

    /** Largest rotation (degrees) accepted by the shape setters. */
    public static final double MAX_ROTATION_DEG = 3600.0;

    /**
     * Preset geometries a shape may use (values of {@code a:prstGeom/@prst}). This is the
     * OOXML vocabulary, not the pptx one: {@code ellipse} here, {@code OVAL} in
     * {@code pptx.setShapeGeometry(...)} - and {@link #getShapes(String)} reports exactly
     * these strings back (round-trip identity, docs/js/docx.md §4 D1).
     */
    public static final List<String> SHAPE_GEOMETRIES = List.of(
            "ellipse", "rect", "roundRect", "line", "triangle", "rightTriangle", "diamond",
            "parallelogram", "trapezoid", "pentagon", "hexagon", "octagon", "star4", "star5",
            "heart", "smileyFace", "pie", "blockArc", "arc", "donut", "plus", "minus",
            "leftArrow", "rightArrow", "upArrow", "downArrow", "leftRightArrow", "cloud");

    /** Dash patterns for {@code a:prstDash/@val}. */
    public static final List<String> LINE_DASHES = List.of(
            "solid", "dot", "dash", "lgDash", "dashDot", "sysDot", "sysDash");

    /** {@code a:} - DrawingML main (xfrm, prstGeom, solidFill, ln). */
    private static final String NS_A = "http://schemas.openxmlformats.org/drawingml/2006/main";

    /** {@code wps:} - WordProcessingShape (one shape). Neither schema class is in
     *  poi-ooxml-lite <em>or</em> poi-ooxml-full, so these elements are written as raw XML. */
    private static final String NS_WPS =
            "http://schemas.microsoft.com/office/word/2010/wordprocessingShape";

    /** {@code wpg:} - WordProcessingGroup (see {@link #NS_WPS}). */
    private static final String NS_WPG =
            "http://schemas.microsoft.com/office/word/2010/wordprocessingGroup";

    /** Map: document-handle -> XWPFDocument */
    private final Map<String, XWPFDocument> documents = new ConcurrentHashMap<>();

    /** Map: paragraph-handle -> XWPFParagraph */
    private final Map<String, XWPFParagraph> paragraphs = new ConcurrentHashMap<>();

    /** Map: run-handle -> XWPFRun */
    private final Map<String, XWPFRun> runs = new ConcurrentHashMap<>();

    /** Map: table-handle -> XWPFTable */
    private final Map<String, XWPFTable> tables = new ConcurrentHashMap<>();

    /** Map: cell-handle -> XWPFTableCell */
    private final Map<String, XWPFTableCell> cells = new ConcurrentHashMap<>();

    /** Map: table-handle -> document-handle (for cleanup on close) */
    private final Map<String, String> tableDoc = new ConcurrentHashMap<>();

    /** Map: cell-handle -> document-handle (for cleanup on close) */
    private final Map<String, String> cellDoc = new ConcurrentHashMap<>();

    /** Map: paragraph-handle -> document-handle (for cleanup on close) */
    private final Map<String, String> paragraphDoc = new ConcurrentHashMap<>();

    /** Map: run-handle -> document-handle (for cleanup on close) */
    private final Map<String, String> runDoc = new ConcurrentHashMap<>();

    /** Map: drawing-handle ("dwg-n") -> the canvas model of {@link #createDrawing}. */
    private final Map<String, Drawing> drawings = new ConcurrentHashMap<>();

    /** Map: shape/group-handle ("shp-n") -> node of a drawing model. */
    private final Map<String, ShapeNode> shapes = new ConcurrentHashMap<>();

    /** Map: drawing-handle -> document-handle (for cleanup on close) */
    private final Map<String, String> drawingDoc = new ConcurrentHashMap<>();

    /**
     * Map: document-handle -> highest drawing id handed out so far. Word insists that
     * {@code wp:docPr/@id} and the ids inside a group are unique per document.
     */
    private final Map<String, Long> shapeIdHighWater = new ConcurrentHashMap<>();

    /** Counter for generating unique handles */
    private final AtomicLong handleCounter = new AtomicLong();

    /**
     * Creates a DocxToolBox with isolated handle state (one per tool-call).
     */
    public DocxToolBox() {
        // Per-call instance
    }

    // ========================================================================
    // Document-level operations
    // ========================================================================

    /**
     * Opens an existing .docx file and returns a document handle.
     * @param filePath path relative to the base directory
     * @return document handle (e.g. "doc-1")
     */
    public String openFile(String filePath) {
        Path path = PoiPaths.resolveSafePath(filePath);
        if (!Files.exists(path)) {
            throw new PoiUserRuntimeException("File not found: " + filePath);
        }
        if (!Files.isRegularFile(path)) {
            throw new PoiUserRuntimeException("Not a file: " + filePath);
        }
        try (FileInputStream fis = new FileInputStream(path.toFile())) {
            XWPFDocument doc = new XWPFDocument(fis);
            String handle = generateHandle("doc");
            documents.put(handle, doc);
            LOG.info("Opened docx: {} -> {}", filePath, handle);
            return handle;
        } catch (IOException e) {
            LOG.error("Failed to open docx: " + filePath, e);
            throw new PoiUserRuntimeException("Failed to open file: " + filePath, e);
        }
    }

    /**
     * Creates a new empty document and returns a handle.
     * @return document handle (e.g. "doc-2")
     */
    public String createDocument() {
        XWPFDocument doc = new XWPFDocument();
        String handle = generateHandle("doc");
        documents.put(handle, doc);
        LOG.info("Created new docx -> {}", handle);
        return handle;
    }

    /**
     * Saves a document to the given file path.
     * @param docHandle document handle
     * @param filePath target path relative to the base directory
     */
    public void save(String docHandle, String filePath) {
        XWPFDocument doc = documents.get(docHandle);
        if (doc == null) {
            throw new PoiUserRuntimeException("Document not found (invalid handle): " + docHandle);
        }
        Path path = PoiPaths.resolveSafePath(filePath);
        try {
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            try (FileOutputStream fos = new FileOutputStream(path.toFile())) {
                doc.write(fos);
            }
            LOG.info("Saved docx {} to {}", docHandle, filePath);
        } catch (IOException e) {
            LOG.error("Failed to save docx " + docHandle + " to " + filePath, e);
            throw new PoiUserRuntimeException("Failed to save document to: " + filePath, e);
        }
    }

    /**
     * Closes a document and releases resources (also invalidates its paragraph/run handles).
     * @param docHandle document handle
     */
    public void close(String docHandle) {
        XWPFDocument doc = documents.remove(docHandle);
        if (doc == null) {
            LOG.warn("Document not found (already closed?): {}", docHandle);
            return;
        }
        // Remove paragraphs belonging to this document
        Set<String> paraHandles = new HashSet<>();
        paragraphDoc.entrySet().removeIf(e -> {
            if (docHandle.equals(e.getValue())) {
                paraHandles.add(e.getKey());
                return true;
            }
            return false;
        });
        paragraphs.keySet().removeAll(paraHandles);
        // Remove runs belonging to this document
        Set<String> runHandles = new HashSet<>();
        runDoc.entrySet().removeIf(e -> {
            if (docHandle.equals(e.getValue())) {
                runHandles.add(e.getKey());
                return true;
            }
            return false;
        });
        runs.keySet().removeAll(runHandles);
        // Remove tables and cells belonging to this document
        Set<String> tableHandles = new HashSet<>();
        tableDoc.entrySet().removeIf(e -> {
            if (docHandle.equals(e.getValue())) {
                tableHandles.add(e.getKey());
                return true;
            }
            return false;
        });
        tables.keySet().removeAll(tableHandles);
        Set<String> cellHandles = new HashSet<>();
        cellDoc.entrySet().removeIf(e -> {
            if (docHandle.equals(e.getValue())) {
                cellHandles.add(e.getKey());
                return true;
            }
            return false;
        });
        cells.keySet().removeAll(cellHandles);
        // Remove drawings and their shape nodes; the id counter is per document as well
        Set<String> drawingHandles = new HashSet<>();
        drawingDoc.entrySet().removeIf(e -> {
            if (docHandle.equals(e.getValue())) {
                drawingHandles.add(e.getKey());
                return true;
            }
            return false;
        });
        drawings.keySet().removeAll(drawingHandles);
        shapes.entrySet().removeIf(e -> !drawings.containsKey(e.getValue().owner.handle));
        shapeIdHighWater.remove(docHandle);
        try {
            doc.close();
        } catch (IOException e) {
            LOG.warn("Error closing document " + docHandle, e);
        }
        LOG.info("Closed docx: {}", docHandle);
    }

    /**
     * Closes all open documents of this instance.
     * <p>
     * Called automatically at the end of a JavaScript tool-call. Documents that were
     * modified but not saved via {@link #save(String, String)} lose their changes.
     * </p>
     */
    public void closeAllDocuments() {
        for (String handle : Set.copyOf(documents.keySet())) {
            close(handle);
        }
    }

    // ========================================================================
    // Paragraph / run operations
    // ========================================================================

    /**
     * Enumerates the body-level paragraphs of a document with their runs.
     * <p>
     * Each paragraph entry contains {@code h} (paragraph handle) and {@code runs}
     * (list of run entries with {@code h} and {@code text}). Tables and content
     * controls (SDT) are not paragraphs - use {@link #getBodyElements(String)} for them.
     * </p>
     * @param docHandle document handle
     * @return JSON-compatible Map with key "paragraphs"
     */
    public Map<String, Object> getParagraphs(String docHandle) {
        return getParagraphs(docHandle, false);
    }

    /**
     * Enumerates the body-level paragraphs, optionally with {@link #getParagraphProps(String)}
     * inline (style name, alignment, indents, numbering, page break).
     * @param docHandle document handle
     * @param includeProps add the paragraph properties to every entry
     * @return JSON-compatible Map with key "paragraphs"
     */
    public Map<String, Object> getParagraphs(String docHandle, boolean includeProps) {
        XWPFDocument doc = documents.get(docHandle);
        if (doc == null) {
            throw new PoiUserRuntimeException("Document not found (invalid handle): " + docHandle);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("document", docHandle);
        List<Map<String, Object>> parasList = new ArrayList<>();
        for (XWPFParagraph p : doc.getParagraphs()) {
            Map<String, Object> pm = new LinkedHashMap<>();
            String ph = registerParagraph(p, docHandle);
            pm.put("h", ph);
            pm.put("text", p.getText());
            List<Map<String, Object>> runList = new ArrayList<>();
            for (XWPFRun run : p.getRuns()) {
                Map<String, Object> rm = new LinkedHashMap<>();
                rm.put("h", registerRun(run, docHandle));
                rm.put("text", run.text());
                runList.add(rm);
            }
            pm.put("runs", runList);
            if (includeProps) {
                Map<String, Object> props = getParagraphProps(ph);
                props.remove("h");
                props.remove("text");
                props.remove("runs");
                pm.put("props", props);
            }
            parasList.add(pm);
        }
        result.put("paragraphs", parasList);
        return result;
    }

    /**
     * Returns the full text of a document (paragraph texts joined with newlines).
     * @param docHandle document handle
     * @return document text
     */
    public String getDocumentText(String docHandle) {
        return getDocumentText(docHandle, false, false);
    }

    /**
     * Returns the text of a document.
     * <p>
     * By default only body paragraphs are concatenated - which silently leaves out table
     * cells and content controls such as a table of contents. Both can be switched on.
     * List numbers never appear: they come from the numbering definition, not from the text.
     * </p>
     * @param docHandle document handle
     * @param includeTables append table text (marked {@code [table n]})
     * @param includeSdt append SDT/text of content controls (marked {@code [sdt n]})
     * @return document text
     */
    public String getDocumentText(String docHandle, boolean includeTables, boolean includeSdt) {
        XWPFDocument doc = documents.get(docHandle);
        if (doc == null) {
            throw new PoiUserRuntimeException("Document not found (invalid handle): " + docHandle);
        }
        if (!includeTables && !includeSdt) {
            StringBuilder sb = new StringBuilder();
            for (XWPFParagraph p : doc.getParagraphs()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(p.getText());
            }
            return sb.toString();
        }
        StringBuilder sb = new StringBuilder();
        int tableNo = 0;
        int sdtNo = 0;
        for (IBodyElement be : doc.getBodyElements()) {
            String text;
            if (be instanceof XWPFParagraph p) {
                text = p.getText();
            } else if (be instanceof XWPFTable table) {
                if (!includeTables) {
                    continue;
                }
                text = "[table " + (tableNo++) + "] " + table.getText();
            } else if (be instanceof XWPFSDT sdt) {
                if (!includeSdt) {
                    continue;
                }
                text = "[sdt " + (sdtNo++) + "] " + (sdt.getContent() == null ? "" : sdt.getContent().getText());
            } else {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(text);
        }
        return sb.toString();
    }

    /**
     * Creates a new paragraph at the end of the document containing a single run.
     * @param docHandle document handle
     * @param text initial text
     * @return paragraph handle
     */
    public String createParagraph(String docHandle, String text) {
        XWPFDocument doc = documents.get(docHandle);
        if (doc == null) {
            throw new PoiUserRuntimeException("Document not found (invalid handle): " + docHandle);
        }
        XWPFParagraph p = doc.createParagraph();
        XWPFRun run = p.createRun();
        run.setText(text == null ? "" : text);
        String ph = generateHandle("p");
        paragraphs.put(ph, p);
        paragraphDoc.put(ph, docHandle);
        String rh = generateHandle("r");
        runs.put(rh, run);
        runDoc.put(rh, docHandle);
        return ph;
    }

    /**
     * Returns the tables of a document (top-level body tables).
     * <p>
     * Each table entry contains {@code index}, {@code h} (table handle), {@code rowCount},
     * {@code colCount} (cells of the first row), {@code colCountMax}, {@code gridCols}
     * (columns of {@code w:tblGrid}), {@code rows} (texts, as before), {@code cellBorders}
     * and {@code cells} - one object per cell with {@code h}, {@code text}, {@code colSpan},
     * {@code vMerge}, {@code merged}, {@code shading} and {@code align}. A row that spans
     * cells is therefore visible instead of looking like a row with missing cells.
     * </p>
     * @param docHandle document handle
     * @return JSON-compatible Map with key "tables"
     */
    public Map<String, Object> getTables(String docHandle) {
        XWPFDocument doc = requireDocument(docHandle);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("document", docHandle);
        List<Map<String, Object>> tablesList = new ArrayList<>();
        List<XWPFTable> docTables = doc.getTables();
        for (int ti = 0; ti < docTables.size(); ti++) {
            XWPFTable table = docTables.get(ti);
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("index", ti);
            tm.put("h", registerTable(table, docHandle));
            List<List<String>> rowsList = new ArrayList<>();
            List<List<String>> bordersList = new ArrayList<>();
            List<List<Map<String, Object>>> cellsList = new ArrayList<>();
            int maxCells = 0;
            for (XWPFTableRow row : table.getRows()) {
                List<String> cellTexts = new ArrayList<>();
                List<String> cellBorders = new ArrayList<>();
                List<Map<String, Object>> cellInfos = new ArrayList<>();
                for (XWPFTableCell cell : row.getTableCells()) {
                    cellTexts.add(cell.getText());
                    cellBorders.add(cellBorders(cell));
                    cellInfos.add(cellInfo(cell, docHandle, false));
                }
                maxCells = Math.max(maxCells, cellTexts.size());
                rowsList.add(cellTexts);
                bordersList.add(cellBorders);
                cellsList.add(cellInfos);
            }
            tm.put("rowCount", table.getRows().size());
            tm.put("colCount", table.getRows().isEmpty() ? 0
                    : table.getRows().get(0).getTableCells().size());
            tm.put("colCountMax", maxCells);
            var ctTbl = table.getCTTbl();
            var grid = ctTbl == null ? null : ctTbl.getTblGrid();   // CTTbl has no isSetTblGrid()
            tm.put("gridCols", grid == null ? null : grid.sizeOfGridColArray());
            tm.put("style", table.getStyleID());
            tm.put("rows", rowsList);
            tm.put("cellBorders", bordersList);
            tm.put("cells", cellsList);
            tablesList.add(tm);
        }
        result.put("count", tablesList.size());
        result.put("tables", tablesList);
        return result;
    }

    /**
     * Appends a row to a table of the document.
     * <p>
     * The formatting of the new cells is taken from the <b>first row without merged cells</b>
     * (previously the last row was cloned, so a merged summary row spread {@code gridSpan}
     * and shading over every appended row). {@code gridSpan}, {@code vMerge}, {@code hMerge}
     * and {@code shd} are never copied. More cell texts than columns is an error instead of
     * silently dropping the extra values.
     * </p>
     * @param docHandle document handle
     * @param tableIndex 0-based index of the table (from {@link #getTables(String)})
     * @param cellTexts texts for the new row's cells (may be shorter than the column count)
     */
    public void addRow(String docHandle, int tableIndex, String[] cellTexts) {
        addRowInternal(requireTable(docHandle, tableIndex), cellTexts);
    }

    /** Overload of {@link #addRow(String, int, String[])} by table handle. */
    public void addRow(String docHandle, String tableHandle, String[] cellTexts) {
        addRowInternal(requireTableByHandle(tableHandle), cellTexts);
    }

    /**
     * Removes a row from a table of the document.
     * @param docHandle document handle
     * @param tableIndex 0-based index of the table
     * @param rowIndex 0-based index of the row to remove
     */
    public void removeRow(String docHandle, int tableIndex, int rowIndex) {
        XWPFDocument doc = documents.get(docHandle);
        if (doc == null) {
            throw new PoiUserRuntimeException("Document not found (invalid handle): " + docHandle);
        }
        List<XWPFTable> tables = doc.getTables();
        if (tableIndex < 0 || tableIndex >= tables.size()) {
            throw new PoiUserRuntimeException("Table index out of range: " + tableIndex
                    + " (valid range: 0.." + (tables.size() - 1) + ")");
        }
        XWPFTable table = tables.get(tableIndex);
        int n = table.getRows().size();
        if (rowIndex < 0 || rowIndex >= n) {
            throw new PoiUserRuntimeException("Row index out of range: " + rowIndex
                    + " (valid range: 0.." + (n - 1) + ")");
        }
        table.removeRow(rowIndex);
    }

    /**
     * Sets the font color of all runs in a table row (e.g. to color a header).
     * @param docHandle document handle
     * @param tableIndex 0-based index of the table
     * @param rowIndex 0-based index of the row
     * @param hexColor hex color (e.g. "FF0000" or "#FF0000")
     */
    public void setRowFontColor(String docHandle, int tableIndex, int rowIndex, String hexColor) {
        XWPFDocument doc = documents.get(docHandle);
        if (doc == null) {
            throw new PoiUserRuntimeException("Document not found (invalid handle): " + docHandle);
        }
        List<XWPFTable> tables = doc.getTables();
        if (tableIndex < 0 || tableIndex >= tables.size()) {
            throw new PoiUserRuntimeException("Table index out of range: " + tableIndex
                    + " (valid range: 0.." + (tables.size() - 1) + ")");
        }
        XWPFTable table = tables.get(tableIndex);
        int n = table.getRows().size();
        if (rowIndex < 0 || rowIndex >= n) {
            throw new PoiUserRuntimeException("Row index out of range: " + rowIndex
                    + " (valid range: 0.." + (n - 1) + ")");
        }
        String hex = PoiColorUtil.stripHash(hexColor);
        if (hex == null || hex.length() != 6) {
            throw new PoiUserRuntimeException(
                    "Invalid color: '" + hexColor + "' (expected 6 hex digits, e.g. 'FF0000').");
        }
        XWPFTableRow row = table.getRow(rowIndex);
        for (XWPFTableCell cell : row.getTableCells()) {
            for (XWPFParagraph p : cell.getParagraphs()) {
                for (XWPFRun run : p.getRuns()) {
                    run.setColor(hex);
                }
            }
        }
    }

    /**
     * Sets the font color of all runs in a single table cell.
     * @param docHandle document handle
     * @param tableIndex 0-based table index
     * @param rowIndex 0-based row index
     * @param colIndex 0-based column index
     * @param hexColor hex color (e.g. "FF0000" or "#FF0000")
     */
    public void setCellFontColor(String docHandle, int tableIndex, int rowIndex, int colIndex, String hexColor) {
        XWPFDocument doc = documents.get(docHandle);
        if (doc == null) {
            throw new PoiUserRuntimeException("Document not found (invalid handle): " + docHandle);
        }
        List<XWPFTable> tables = doc.getTables();
        if (tableIndex < 0 || tableIndex >= tables.size()) {
            throw new PoiUserRuntimeException("Table index out of range: " + tableIndex
                    + " (valid range: 0.." + (tables.size() - 1) + ")");
        }
        XWPFTable table = tables.get(tableIndex);
        int n = table.getRows().size();
        if (rowIndex < 0 || rowIndex >= n) {
            throw new PoiUserRuntimeException("Row index out of range: " + rowIndex
                    + " (valid range: 0.." + (n - 1) + ")");
        }
        XWPFTableRow row = table.getRow(rowIndex);
        if (colIndex < 0 || colIndex >= row.getTableCells().size()) {
            throw new PoiUserRuntimeException("Column index out of range: " + colIndex
                    + " (valid range: 0.." + (row.getTableCells().size() - 1) + ")");
        }
        String hex = PoiColorUtil.stripHash(hexColor);
        if (hex == null || hex.length() != 6) {
            throw new PoiUserRuntimeException(
                    "Invalid color: '" + hexColor + "' (expected 6 hex digits, e.g. 'FF0000').");
        }
        XWPFTableCell cell = row.getCell(colIndex);
        for (XWPFParagraph p : cell.getParagraphs()) {
            for (XWPFRun run : p.getRuns()) {
                run.setColor(hex);
            }
        }
    }

    /**
     * Appends a new run to a paragraph.
     * @param paragraphHandle paragraph handle
     * @param text run text
     * @return run handle
     */
    public String addRun(String paragraphHandle, String text) {
        XWPFParagraph p = paragraphs.get(paragraphHandle);
        if (p == null) {
            throw new PoiUserRuntimeException("Paragraph not found (invalid handle): " + paragraphHandle);
        }
        XWPFRun run = p.createRun();
        run.setText(text == null ? "" : text);
        String rh = generateHandle("r");
        runs.put(rh, run);
        runDoc.put(rh, paragraphDoc.get(paragraphHandle));
        return rh;
    }

    /**
     * Returns the text of a paragraph.
     * @param paragraphHandle paragraph handle
     * @return paragraph text
     */
    public String getParagraphText(String paragraphHandle) {
        XWPFParagraph p = paragraphs.get(paragraphHandle);
        if (p == null) {
            throw new PoiUserRuntimeException("Paragraph not found (invalid handle): " + paragraphHandle);
        }
        return p.getText();
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
        XWPFRun run = getRun(runHandle);
        run.setText(text);
    }

    /**
     * Sets the bold property of a run.
     * @param runHandle run handle
     * @param bold true for bold
     */
    public void setRunBold(String runHandle, boolean bold) {
        XWPFRun run = getRun(runHandle);
        run.setBold(bold);
    }

    /**
     * Sets the italic property of a run.
     * @param runHandle run handle
     * @param italic true for italic
     */
    public void setRunItalic(String runHandle, boolean italic) {
        XWPFRun run = getRun(runHandle);
        run.setItalic(italic);
    }

    /**
     * Sets the font size of a run (in points, half-point steps allowed).
     * <p>
     * POI stores the size internally in half-points, so {@code 9.5} is a valid value and
     * is written as {@code w:sz w:val="19"}.
     * </p>
     * @param runHandle run handle
     * @param sizePoints font size in points, 1.0 .. 4000.0
     */
    public void setRunFontSize(String runHandle, double sizePoints) {
        XWPFRun run = getRun(runHandle);
        if (Double.isNaN(sizePoints) || Double.isInfinite(sizePoints)) {
            throw new PoiUserRuntimeException("Invalid font size: '" + sizePoints
                    + "' (expected a number between " + MIN_FONT_PT + " and " + MAX_FONT_PT + " pt).");
        }
        if (sizePoints < MIN_FONT_PT || sizePoints > MAX_FONT_PT) {
            throw new PoiUserRuntimeException("Invalid font size: '" + sizePoints
                    + "' (valid range: " + MIN_FONT_PT + " - " + MAX_FONT_PT + " pt, half-point steps are kept).");
        }
        run.setFontSize(sizePoints);
    }

    /**
     * Sets the font name of a run (e.g. "Arial", "Calibri").
     * @param runHandle run handle
     * @param fontName font name
     */
    public void setRunFontName(String runHandle, String fontName) {
        XWPFRun run = getRun(runHandle);
        run.setFontFamily(fontName);
    }

    /**
     * Sets the font color of a run.
     * @param runHandle run handle
     * @param hexColor hex color (e.g. "FF0000" or "#FF0000")
     */
    public void setRunFontColor(String runHandle, String hexColor) {
        XWPFRun run = getRun(runHandle);
        String hex = PoiColorUtil.stripHash(hexColor);
        if (hex == null || hex.length() != 6) {
            throw new PoiUserRuntimeException(
                    "Invalid color: '" + hexColor + "' (expected 6 hex digits, e.g. 'FF0000').");
        }
        run.setColor(hex);
    }

    // ========================================================================
    // Inspection (M0: read what a document really contains)
    // ========================================================================

    /**
     * Describes one run: text plus its direct character formatting.
     * <p>
     * Sizes are reported in points ({@code w:sz} is stored in half-points), colours as
     * 6 hex digits or {@code "auto"}. Values that the document does not set are
     * reported as {@code null} rather than guessed from styles.
     * </p>
     * @param runHandle run handle
     * @return JSON-compatible Map
     */
    public Map<String, Object> getRunProps(String runHandle) {
        XWPFRun run = getRun(runHandle);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("h", runHandle);
        m.put("text", run.text());
        m.put("bold", run.isBold());
        m.put("italic", run.isItalic());
        m.put("fontSizePt", run.getFontSizeAsDouble());
        m.put("fontName", run.getFontFamily());
        m.put("color", run.getColor());
        m.put("underline", run.getUnderline() == null ? null : run.getUnderline().name().toLowerCase(Locale.ROOT));
        m.put("style", emptyToNull(run.getStyle()));
        var ctr = run.getCTR();
        var rPr = ctr.isSetRPr() ? ctr.getRPr() : null;
        if (rPr != null) {
            // CTRPr models its content group as lists: first entry wins, no isSetX()/getShd()
            var shd = firstOf(rPr.getShdList());
            if (shd != null) {
                m.put("shading", PoiColorUtil.hexOf(shd.getFill()));   // XmlBeans: byte[]
            }
            var highlight = firstOf(rPr.getHighlightList());
            if (highlight != null && highlight.getVal() != null) {
                m.put("highlight", highlight.getVal().toString());
            }
        }
        List<String> breaks = new ArrayList<>();
        for (var br : ctr.getBrList()) {
            breaks.add(br.isSetType() && br.getType() != null ? br.getType().toString() : "line");
        }
        if (!breaks.isEmpty()) {
            m.put("breaks", breaks);
        }
        int drawings = countDescendants(ctr, "drawing");
        int shapes = countDescendants(ctr, "pict");
        if (drawings > 0) {
            m.put("drawings", drawings);
        }
        if (shapes > 0) {
            m.put("vmlShapes", shapes);
        }
        return m;
    }

    /**
     * Lists the run handles of a paragraph (the run created by
     * {@link #createParagraph(String, String)} is only reachable this way).
     * @param paragraphHandle paragraph handle
     * @return JSON-compatible Map with key "runs"
     */
    public Map<String, Object> getRuns(String paragraphHandle) {
        XWPFParagraph p = paragraphs.get(paragraphHandle);
        if (p == null) {
            throw new PoiUserRuntimeException("Paragraph not found (invalid handle): " + paragraphHandle);
        }
        List<Map<String, Object>> runsList = new ArrayList<>();
        for (XWPFRun run : p.getRuns()) {
            String rh = registerRun(run, paragraphDoc.get(paragraphHandle));
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("h", rh);
            rm.put("text", run.text());
            runsList.add(rm);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("paragraph", paragraphHandle);
        result.put("runs", runsList);
        return result;
    }

    /**
     * Describes a paragraph: style (id <em>and</em> display name), alignment, indents
     * (cm), spacing (pt), numbering, page breaks and the structures the paragraph
     * contains (hyperlinks, fields, pictures).
     * <p>
     * Numbering may be set on the paragraph or inherited from its style;
     * {@code numIdSource} tells the two apart. {@code numId: 0} is LibreOffice's way of
     * switching an inherited numbering off.
     * </p>
     * @param paragraphHandle paragraph handle
     * @return JSON-compatible Map
     */
    public Map<String, Object> getParagraphProps(String paragraphHandle) {
        XWPFParagraph p = paragraphs.get(paragraphHandle);
        if (p == null) {
            throw new PoiUserRuntimeException("Paragraph not found (invalid handle): " + paragraphHandle);
        }
        String docHandle = paragraphDoc.get(paragraphHandle);
        XWPFDocument doc = docHandle == null ? null : documents.get(docHandle);
        CTP ctp = p.getCTP();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("h", paragraphHandle);
        m.put("text", p.getText());
        String styleId = p.getStyleID();
        m.put("style", styleId);
        m.put("styleName", styleName(doc, styleId));
        m.put("outlineLvl", outlineLevelOfStyle(doc, styleId));
        m.put("alignment", p.getAlignment() == null ? null : p.getAlignment().name().toLowerCase(Locale.ROOT));
        m.put("alignmentSet", p.isAlignmentSet());

        Map<String, Object> indent = new LinkedHashMap<>();
        // POI uses -1 for "not set" on indents and spacing - that must not become -0.05 pt
        indent.put("leftCm", cmOrNull(p.getIndentationLeft()));
        indent.put("rightCm", cmOrNull(p.getIndentationRight()));
        indent.put("firstLineCm", cmOrNull(p.getIndentationFirstLine()));
        indent.put("hangingCm", cmOrNull(p.getIndentationHanging()));
        m.put("indent", indent);

        Map<String, Object> spacing = new LinkedHashMap<>();
        spacing.put("beforePt", ptOrNull(p.getSpacingBefore()));
        spacing.put("afterPt", ptOrNull(p.getSpacingAfter()));
        double lineValue = p.getSpacingBetween();      // -1 when the paragraph sets no line rule
        spacing.put("line", lineValue < 0 ? null : lineValue);
        spacing.put("lineRule", p.getSpacingLineRule() == null
                ? null : p.getSpacingLineRule().name().toLowerCase(Locale.ROOT));
        m.put("spacing", spacing);

        // POI's getNumID()/getNumIlvl() already resolve the style chain, so they cannot tell
        // an own numPr from an inherited one. Read the paragraph's own pPr first, then the style.
        CTPPr pPr = ctp.isSetPPr() ? ctp.getPPr() : null;
        var ownNumPr = pPr != null && pPr.isSetNumPr() ? pPr.getNumPr() : null;
        BigInteger numId = null;
        Integer ilvl = null;
        String numIdSource = "none";
        if (ownNumPr != null) {
            if (ownNumPr.isSetNumId()) {
                numId = bigOf(ownNumPr.getNumId().getVal());
            }
            if (ownNumPr.isSetIlvl()) {
                ilvl = intOf(ownNumPr.getIlvl().getVal());
            }
            numIdSource = "paragraph";
        } else {
            var inherited = styleNumbering(doc, styleId);
            if (inherited != null) {
                numId = inherited[0];
                ilvl = inherited[1] == null ? null : inherited[1].intValue();
                numIdSource = "style";
            }
        }
        m.put("numId", numId == null ? null : numId.toString());
        m.put("ilvl", ilvl);
        m.put("numIdSource", numIdSource);
        m.put("numFmt", numFmtOf(doc, numId, ilvl));
        m.put("lvlText", lvlTextOf(doc, numId, ilvl));

        m.put("pageBreak", p.isPageBreak());
        m.put("pageBreakBefore", pPr != null && pPr.isSetPageBreakBefore());
        m.put("keepNext", p.isKeepNext());
        m.put("sectionEnd", pPr != null && pPr.isSetSectPr());
        m.put("runs", p.getRuns().size());
        m.put("hyperlinks", ctp.getHyperlinkList().size());
        List<String> breaks = breaksOf(ctp);
        if (!breaks.isEmpty()) {
            m.put("breaks", breaks);
        }
        String linkText = hyperlinkTextOf(ctp);
        if (!linkText.isEmpty()) {
            m.put("hyperlinkText", linkText);   // not part of getText() for foreign documents
        }
        m.put("fields", collectFields(ctp));
        int[] markers = countFieldMarkers(ctp);
        if (markers[0] + markers[1] + markers[2] > 0) {
            Map<String, Object> mk = new LinkedHashMap<>();
            mk.put("begin", markers[0]);
            mk.put("separate", markers[1]);
            mk.put("end", markers[2]);
            m.put("fieldMarkers", mk);         // begin>end: the field runs into the next paragraph
        }
        int drawings = countDescendants(ctp, "drawing");
        int shapes = countDescendants(ctp, "pict");
        if (drawings > 0) {
            m.put("drawings", drawings);
        }
        if (shapes > 0) {
            m.put("vmlShapes", shapes);        // reported, not silently dropped
        }
        return m;
    }

    /**
     * Returns the true body flow of a document: paragraphs, tables and content controls
     * (SDT blocks such as a LibreOffice/Word table of contents) in document order.
     * <p>
     * {@link #getParagraphs(String)} alone loses both tables and SDT blocks. POI exposes
     * only the text of an SDT block, so such an element is marked {@code opaque} and is
     * never a target of the editing functions.
     * </p>
     * @param docHandle document handle
     * @return JSON-compatible Map with key "elements"
     */
    public Map<String, Object> getBodyElements(String docHandle) {
        XWPFDocument doc = documents.get(docHandle);
        if (doc == null) {
            throw new PoiUserRuntimeException("Document not found (invalid handle): " + docHandle);
        }
        var sdtBlocks = doc.getDocument().getBody().getSdtList();
        int sdtSeen = 0;
        List<Map<String, Object>> elements = new ArrayList<>();
        for (IBodyElement be : doc.getBodyElements()) {
            Map<String, Object> em = new LinkedHashMap<>();
            em.put("pos", elements.size());
            if (be instanceof XWPFParagraph para) {
                em.put("type", "paragraph");
                String ph = registerParagraph(para, docHandle);
                em.put("h", ph);
                em.put("text", para.getText());
                em.put("style", para.getStyleID());
            } else if (be instanceof XWPFTable table) {
                em.put("type", "table");
                em.put("rowCount", table.getRows().size());
                em.put("colCount", table.getRows().isEmpty() ? 0 : table.getRows().get(0).getTableCells().size());
                em.put("text", table.getText());
            } else if (be instanceof XWPFSDT sdt) {
                em.put("type", "sdt");
                em.put("text", sdt.getContent() == null ? "" : sdt.getContent().getText());
                em.put("title", sdt.getTitle());
                em.put("tag", sdt.getTag());
                em.put("gallery", sdtSeen < sdtBlocks.size() ? galleryOf(sdtBlocks.get(sdtSeen)) : null);
                em.put("opaque", true);         // text only - not editable through this API
                sdtSeen++;
            } else {
                em.put("type", "other");
                em.put("text", "");
            }
            elements.add(em);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("document", docHandle);
        result.put("count", elements.size());
        result.put("elements", elements);
        return result;
    }

    /**
     * Lists the styles of a document: style id, display name, base style, outline level
     * and size. Both are needed because LibreOffice and Word disagree about which of
     * {@code w:styleId} and {@code w:name} carries the readable name.
     * @param docHandle document handle
     * @return JSON-compatible Map with key "styles"
     */
    public Map<String, Object> getStylesInfo(String docHandle) {
        XWPFDocument doc = documents.get(docHandle);
        if (doc == null) {
            throw new PoiUserRuntimeException("Document not found (invalid handle): " + docHandle);
        }
        List<Map<String, Object>> stylesList = new ArrayList<>();
        var styles = doc.getStyles();
        if (styles != null) {
            for (var style : styles.getStyles()) {
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.put("styleId", style.getStyleId());
                sm.put("name", style.getName());
                sm.put("type", style.getType() == null ? null : style.getType().toString());
                sm.put("basedOn", style.getBasisStyleID());
                var ct = style.getCTStyle();
                var pPr = ct != null && ct.isSetPPr() ? ct.getPPr() : null;
                sm.put("outlineLvl", pPr != null && pPr.isSetOutlineLvl() ? intOf(pPr.getOutlineLvl().getVal()) : null);
                var rPr = ct != null && ct.isSetRPr() ? ct.getRPr() : null;
                var sz = rPr == null ? null : firstOf(rPr.getSzList());
                // w:sz counts half-points, w:spacing/w:ind count twips - two different scales
                sm.put("fontSizePt", sz == null ? null : halfPointsToPt(intOf(sz.getVal())));
                stylesList.add(sm);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("document", docHandle);
        result.put("present", styles != null);
        result.put("count", stylesList.size());
        result.put("styles", stylesList);
        return result;
    }

    /**
     * Lists the numbering definitions: every {@code numId} with its abstract definition
     * and the format of each level. Ids are already taken in foreign documents, which is
     * why new lists must allocate a free id instead of claiming a fixed one.
     * @param docHandle document handle
     * @return JSON-compatible Map with key "numberings"
     */
    public Map<String, Object> getNumberingInfo(String docHandle) {
        XWPFDocument doc = documents.get(docHandle);
        if (doc == null) {
            throw new PoiUserRuntimeException("Document not found (invalid handle): " + docHandle);
        }
        var numbering = doc.getNumbering();
        List<Map<String, Object>> nums = new ArrayList<>();
        if (numbering != null) {
            for (var num : numbering.getNums()) {
                var ctNum = num.getCTNum();
                Map<String, Object> nm = new LinkedHashMap<>();
                nm.put("numId", ctNum.getNumId() == null ? null : ctNum.getNumId().toString());
                BigInteger abstractId = ctNum.getAbstractNumId() == null
                        ? null : bigOf(ctNum.getAbstractNumId().getVal());
                nm.put("abstractId", abstractId == null ? null : abstractId.toString());
                nm.put("levels", levelsOf(numbering, abstractId));
                nums.add(nm);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("document", docHandle);
        result.put("present", numbering != null);
        result.put("count", nums.size());
        result.put("numberings", nums);
        return result;
    }

    /**
     * Reads headers and footers, including their fields.
     * <p>
     * Which header/footer part is used by which section is stored in the section
     * properties, not in the part itself - POI has no {@code getType()} on a header.
     * Parts that exist but are not referenced are still reported, with the section
     * margins alongside ({@code headerMarginCm: 0} means a header would be invisible).
     * </p>
     * @param docHandle document handle
     * @return JSON-compatible Map with key "headersFooters"
     */
    public Map<String, Object> getHeadersFooters(String docHandle) {
        XWPFDocument doc = documents.get(docHandle);
        if (doc == null) {
            throw new PoiUserRuntimeException("Document not found (invalid handle): " + docHandle);
        }
        Map<String, XWPFHeaderFooter> headers = partsByName(doc.getHeaderList(), "header");
        Map<String, XWPFHeaderFooter> footers = partsByName(doc.getFooterList(), "footer");
        List<Map<String, Object>> entries = new ArrayList<>();
        Set<String> referenced = new HashSet<>();
        List<CTSectPr> sectPrs = sectPrsOf(doc);
        for (CTSectPr sectPr : sectPrs) {
            for (var ref : sectPr.getHeaderReferenceList()) {
                Map<String, Object> entry = refEntry("header", ref, headers, doc);
                entries.add(entry);
                if (entry.get("part") != null) {
                    referenced.add(String.valueOf(entry.get("part")));
                }
            }
            for (var ref : sectPr.getFooterReferenceList()) {
                Map<String, Object> entry = refEntry("footer", ref, footers, doc);
                entries.add(entry);
                if (entry.get("part") != null) {
                    referenced.add(String.valueOf(entry.get("part")));
                }
            }
        }
        // Parts nobody references (LibreOffice writes first/even variants unconditionally)
        for (Map.Entry<String, XWPFHeaderFooter> e : headers.entrySet()) {
            if (!referenced.contains(e.getKey())) {
                entries.add(unreferencedEntry("header", e.getKey(), e.getValue()));
            }
        }
        for (Map.Entry<String, XWPFHeaderFooter> e : footers.entrySet()) {
            if (!referenced.contains(e.getKey())) {
                entries.add(unreferencedEntry("footer", e.getKey(), e.getValue()));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("document", docHandle);
        result.put("count", entries.size());
        result.put("headersFooters", entries);
        var settings = settingsIfPresent(doc);
        result.put("evenAndOddHeaders", settings != null && settings.getEvenAndOddHeadings());
        return result;
    }

    /**
     * Lists the pictures of a document and how often they are placed.
     * <p>
     * {@code inline}/{@code anchor} count the placements found in the body: POI writes
     * {@code wp:inline} while LibreOffice often anchors pictures - both forms have to be
     * readable. VML fallback shapes ({@code w:pict}) are counted as {@code vmlShapes};
     * their geometry is not parsed.
     * </p>
     * @param docHandle document handle
     * @return JSON-compatible Map with key "pictures"
     */
    public Map<String, Object> getPictures(String docHandle) {
        XWPFDocument doc = documents.get(docHandle);
        if (doc == null) {
            throw new PoiUserRuntimeException("Document not found (invalid handle): " + docHandle);
        }
        List<Map<String, Object>> pics = new ArrayList<>();
        for (var pic : doc.getAllPictures()) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("fileName", pic.getFileName());
            pm.put("extension", pic.suggestFileExtension());
            pm.put("bytes", pic.getData() == null ? 0 : pic.getData().length);
            var size = pixelSize(pic.getData());
            if (size != null) {
                pm.put("widthPx", size[0]);
                pm.put("heightPx", size[1]);
            }
            pics.add(pm);
        }
        // Counted over the whole document tree: drawings and VML hide inside
        // mc:AlternateContent (mc:Choice / mc:Fallback), so run-level child accessors
        // would report nothing for anchored shapes.
        var documentElement = doc.getDocument();
        int inline = countDescendants(documentElement, "inline");
        int anchor = countDescendants(documentElement, "anchor");
        int vml = countDescendants(documentElement, "pict");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("document", docHandle);
        result.put("count", pics.size());
        result.put("pictures", pics);
        result.put("inlinePlacements", inline);
        result.put("anchorPlacements", anchor);
        result.put("vmlShapes", vml);
        return result;
    }

    /**
     * Lists the hyperlinks of the body: internal anchors ({@code w:anchor}) and external
     * targets (resolved over the document relationships).
     * @param docHandle document handle
     * @return JSON-compatible Map with key "hyperlinks"
     */
    public Map<String, Object> getHyperlinks(String docHandle) {
        XWPFDocument doc = documents.get(docHandle);
        if (doc == null) {
            throw new PoiUserRuntimeException("Document not found (invalid handle): " + docHandle);
        }
        Map<String, String> urlById = new LinkedHashMap<>();
        for (var link : doc.getHyperlinks()) {
            if (link != null && link.getId() != null) {
                urlById.put(link.getId(), link.getURL());
            }
        }
        List<Map<String, Object>> links = new ArrayList<>();
        for (XWPFParagraph p : doc.getParagraphs()) {
            for (var hlink : p.getCTP().getHyperlinkList()) {
                Map<String, Object> lm = new LinkedHashMap<>();
                lm.put("paragraphText", p.getText());
                lm.put("text", textOfRuns(hlink.getRList()));
                String anchor = hlink.getAnchor();
                lm.put("anchor", anchor == null || anchor.isEmpty() ? null : anchor);
                lm.put("url", hlink.getId() == null ? null : urlById.get(hlink.getId()));
                links.add(lm);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("document", docHandle);
        result.put("count", links.size());
        result.put("hyperlinks", links);
        return result;
    }

    /**
     * Summary of a document: what kind of elements it holds, how its sections are set up
     * and what the core properties say. The starting point for "what am I looking at?".
     * @param docHandle document handle
     * @return JSON-compatible Map
     */
    public Map<String, Object> getDocumentInfo(String docHandle) {
        XWPFDocument doc = documents.get(docHandle);
        if (doc == null) {
            throw new PoiUserRuntimeException("Document not found (invalid handle): " + docHandle);
        }
        int paragraphs = 0;
        int tables = 0;
        int sdts = 0;
        for (IBodyElement be : doc.getBodyElements()) {
            if (be instanceof XWPFParagraph) {
                paragraphs++;
            } else if (be instanceof XWPFTable) {
                tables++;
            } else if (be instanceof XWPFSDT) {
                sdts++;
            }
        }
        List<Map<String, Object>> sections = new ArrayList<>();
        List<CTSectPr> sectPrs = sectPrsOf(doc);
        for (CTSectPr sectPr : sectPrs) {
            sections.add(sectionInfo(sectPr));
        }
        Map<String, Object> core = new LinkedHashMap<>();
        var coreProps = doc.getProperties().getCoreProperties();
        core.put("title", coreProps.getTitle());
        core.put("author", coreProps.getCreator());
        core.put("subject", coreProps.getSubject());
        core.put("keywords", coreProps.getKeywords());
        var styles = doc.getStyles();
        var numbering = doc.getNumbering();
        var settings = settingsIfPresent(doc);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("document", docHandle);
        result.put("bodyElementCount", doc.getBodyElements().size());
        result.put("paragraphCount", paragraphs);
        result.put("tableCount", tables);
        result.put("sdtCount", sdts);
        result.put("sectionCount", sections.size());
        result.put("sections", sections);
        result.put("stylesPresent", styles != null);
        result.put("styleCount", styles == null ? 0 : styles.getNumberOfStyles());
        result.put("numberingPresent", numbering != null);
        result.put("evenAndOddHeaders", settings != null && settings.getEvenAndOddHeadings());
        result.put("core", core);
        return result;
    }

    /**
     * Fields of one paragraph, both spellings normalised to instruction and cached value:
     * the complex form ({@code w:fldChar} begin/separate/end, what LibreOffice writes) and
     * the simple form ({@code w:fldSimple}, what POI's {@code createFieldRun()} writes).
     * @param paragraphHandle paragraph handle
     * @return JSON-compatible Map with key "fields"
     */
    public Map<String, Object> getFields(String paragraphHandle) {
        XWPFParagraph p = paragraphs.get(paragraphHandle);
        if (p == null) {
            throw new PoiUserRuntimeException("Paragraph not found (invalid handle): " + paragraphHandle);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("paragraph", paragraphHandle);
        result.put("fields", collectFields(p.getCTP()));
        return result;
    }

    // ------------------------------------------------------------------------
    // Reading helpers
    // ------------------------------------------------------------------------

    /** Registers a paragraph under a fresh handle (used by every read method). */
    private String registerParagraph(XWPFParagraph p, String docHandle) {
        String ph = generateHandle("p");
        paragraphs.put(ph, p);
        paragraphDoc.put(ph, docHandle);
        return ph;
    }

    /** Registers a run under a fresh handle (used by every read method). */
    private String registerRun(XWPFRun run, String docHandle) {
        String rh = generateHandle("r");
        runs.put(rh, run);
        runDoc.put(rh, docHandle);
        return rh;
    }

    /**
     * XmlBeans exposes the content groups of {@code CTRPr} (and friends) as one list per
     * child type instead of singular getters - the first entry is the one in effect.
     */
    private static <T> T firstOf(List<T> values) {
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    /**
     * Counts descendant elements by local name with an {@link XmlCursor}.
     * <p>
     * Needed for {@code w:drawing}/{@code w:pict}: LibreOffice wraps them in
     * {@code mc:AlternateContent} with a {@code mc:Choice} and a {@code mc:Fallback}, so
     * they are not direct children of {@code w:r} and the generated child accessors of
     * {@code CTR} do not reach them. Counting by local name (not by type) keeps the XPath
     * engines out of the picture.
     * </p>
     */
    private static int countDescendants(XmlObject root, String localName) {
        if (root == null) {
            return 0;
        }
        int count = 0;
        XmlCursor cursor = root.newCursor();
        try {
            // The cursor walks tokens; depth keeps the END tokens of nested elements apart
            // from the one that closes the root's content.
            int depth = 0;
            XmlCursor.TokenType token = cursor.toFirstContentToken();
            while (token != null && !token.isNone()) {
                if (token.isStart()) {
                    depth++;
                    if (cursor.getName() != null && localName.equals(cursor.getName().getLocalPart())) {
                        count++;
                    }
                } else if (token.isEnd()) {
                    if (depth == 0) {
                        break;
                    }
                    depth--;
                }
                token = cursor.toNextToken();
            }
        } finally {
            cursor.dispose();
        }
        return count;
    }

    /** Empty strings that XmlBeans/POI use for "not set" become null. */
    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /** Display name of a style id, or null when the style does not exist. */
    private static String styleName(XWPFDocument doc, String styleId) {
        if (doc == null || doc.getStyles() == null || styleId == null) {
            return null;
        }
        var style = doc.getStyles().getStyle(styleId);
        return style == null ? null : style.getName();
    }

    /** Outline level of a style, following {@code basedOn} (a paragraph inherits it), or null. */
    private static Integer outlineLevelOfStyle(XWPFDocument doc, String styleId) {
        if (doc == null || doc.getStyles() == null) {
            return null;
        }
        Set<String> seen = new HashSet<>();
        String id = styleId;
        for (int depth = 0; id != null && depth < 10 && seen.add(id); depth++) {
            var style = doc.getStyles().getStyle(id);
            if (style == null) {
                return null;
            }
            var ct = style.getCTStyle();
            if (ct != null && ct.isSetPPr() && ct.getPPr().isSetOutlineLvl()) {
                return intOf(ct.getPPr().getOutlineLvl().getVal());
            }
            id = style.getBasisStyleID();
        }
        return null;
    }

    /**
     * {@code numId}/{@code ilvl} a paragraph inherits from its style, or null.
     * Follows the {@code basedOn} chain (LibreOffice puts numbering on the parent style,
     * Word often on the concrete heading style), bounded to survive a cyclic definition.
     */
    private static BigInteger[] styleNumbering(XWPFDocument doc, String styleId) {
        if (doc == null || doc.getStyles() == null) {
            return null;
        }
        Set<String> seen = new HashSet<>();
        String id = styleId;
        for (int depth = 0; id != null && depth < 10 && seen.add(id); depth++) {
            var style = doc.getStyles().getStyle(id);
            if (style == null) {
                return null;
            }
            var ct = style.getCTStyle();
            if (ct != null && ct.isSetPPr() && ct.getPPr().isSetNumPr() && ct.getPPr().getNumPr() != null) {
                var numPr = ct.getPPr().getNumPr();
                BigInteger numId = numPr.isSetNumId() ? bigOf(numPr.getNumId().getVal()) : null;
                BigInteger ilvl = numPr.isSetIlvl() ? bigOf(numPr.getIlvl().getVal()) : null;
                return numId == null ? null : new BigInteger[]{numId, ilvl};
            }
            id = style.getBasisStyleID();
        }
        return null;
    }

    /** Number format of a list level ("bullet", "decimal", "none", ...), or null. */
    private String numFmtOf(XWPFDocument doc, BigInteger numId, Integer ilvl) {
        var lvl = lvlOf(doc == null ? null : doc.getNumbering(), numId, ilvl);
        return lvl == null || !lvl.isSetNumFmt() || lvl.getNumFmt().getVal() == null
                ? null : lvl.getNumFmt().getVal().toString();
    }

    /** Level text of a list level ("${bullet}", "%1.", ...), or null. */
    private String lvlTextOf(XWPFDocument doc, BigInteger numId, Integer ilvl) {
        var lvl = lvlOf(doc == null ? null : doc.getNumbering(), numId, ilvl);
        return lvl == null || !lvl.isSetLvlText() ? null : strOf(lvl.getLvlText().getVal());
    }

    private static org.openxmlformats.schemas.wordprocessingml.x2006.main.CTLvl
            lvlOf(XWPFNumbering numbering, BigInteger numId, Integer ilvl) {
        if (numbering == null || numId == null) {
            return null;
        }
        var num = numbering.getNum(numId);
        if (num == null || num.getCTNum() == null || num.getCTNum().getAbstractNumId() == null) {
            return null;
        }
        var abstractNum = numbering.getAbstractNum(bigOf(num.getCTNum().getAbstractNumId().getVal()));
        if (abstractNum == null || abstractNum.getCTAbstractNum() == null) {
            return null;
        }
        int wanted = ilvl == null ? 0 : ilvl;
        for (var lvl : abstractNum.getCTAbstractNum().getLvlList()) {
            Integer level = lvl.getIlvl() == null ? null : intOf(lvl.getIlvl());
            if (level != null && level == wanted) {
                return lvl;
            }
        }
        return null;
    }

    /** Level definitions of one abstract numbering. */
    private static List<Map<String, Object>> levelsOf(XWPFNumbering numbering, BigInteger abstractId) {
        List<Map<String, Object>> levels = new ArrayList<>();
        if (abstractId == null) {
            return levels;
        }
        var abstractNum = numbering.getAbstractNum(abstractId);
        if (abstractNum == null || abstractNum.getCTAbstractNum() == null) {
            return levels;
        }
        for (var lvl : abstractNum.getCTAbstractNum().getLvlList()) {
            Map<String, Object> lm = new LinkedHashMap<>();
            lm.put("ilvl", lvl.getIlvl() == null ? null : intOf(lvl.getIlvl()));
            lm.put("numFmt", lvl.isSetNumFmt() && lvl.getNumFmt().getVal() != null
                    ? lvl.getNumFmt().getVal().toString() : null);
            lm.put("lvlText", lvl.isSetLvlText() ? lvl.getLvlText().getVal() : null);
            lm.put("start", lvl.isSetStart() ? intOf(lvl.getStart().getVal()) : null);
            levels.add(lm);
        }
        return levels;
    }

    /** Breaks of all runs of a paragraph: "page" for a page break, "line" otherwise. */
    private static List<String> breaksOf(CTP ctp) {
        List<String> breaks = new ArrayList<>();
        for (var ctr : ctp.getRList()) {
            for (var br : ctr.getBrList()) {
                breaks.add(br.isSetType() && br.getType() != null ? br.getType().toString() : "line");
            }
        }
        return breaks;
    }

    /** Text of {@code w:t} children of the given runs. */
    private static String textOfRuns(List<? extends org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR> ctrs) {
        StringBuilder sb = new StringBuilder();
        for (var ctr : ctrs) {
            for (var t : ctr.getTList()) {
                String v = t.getStringValue();
                if (v != null) {
                    sb.append(v);
                }
            }
        }
        return sb.toString();
    }

    /** Text inside the hyperlinks of a paragraph (not covered by {@code getText()}). */
    private static String hyperlinkTextOf(CTP ctp) {
        StringBuilder sb = new StringBuilder();
        for (var hlink : ctp.getHyperlinkList()) {
            sb.append(textOfRuns(hlink.getRList()));
        }
        return sb.toString();
    }

    /**
     * Reads the fields of a paragraph, complex ({@code w:fldChar}) and simple
     * ({@code w:fldSimple}) alike. A field that is opened but not closed in this
     * paragraph is reported with {@code unterminated: true} - LibreOffice lets a TOC
     * field run across paragraphs.
     */
    private static List<Map<String, Object>> collectFields(CTP ctp) {
        List<Map<String, Object>> fields = new ArrayList<>();
        StringBuilder instr = new StringBuilder();
        StringBuilder cache = new StringBuilder();
        boolean inField = false;
        boolean inCache = false;
        for (var ctr : ctp.getRList()) {
            for (var fldChar : ctr.getFldCharList()) {
                String type = fldChar.getFldCharType() == null ? "" : fldChar.getFldCharType().toString();
                if (type.contains("begin")) {
                    if (inField) {
                        fields.add(fieldEntry(instr.toString(), cache.toString(), true));
                    }
                    inField = true;
                    inCache = false;
                    instr.setLength(0);
                    cache.setLength(0);
                } else if (type.contains("separate")) {
                    inCache = true;
                } else if (type.contains("end")) {
                    fields.add(fieldEntry(instr.toString(), cache.toString(), false));
                    inField = false;
                    inCache = false;
                    instr.setLength(0);
                    cache.setLength(0);
                }
            }
            for (var it : ctr.getInstrTextList()) {
                String v = it.getStringValue();
                if (v == null) {
                    continue;
                }
                if (inCache) {
                    cache.append(v);
                } else if (inField) {
                    instr.append(v);
                }
            }
            if (inCache) {
                for (var t : ctr.getTList()) {
                    String v = t.getStringValue();
                    if (v != null) {
                        cache.append(v);
                    }
                }
            }
        }
        if (inField) {
            fields.add(fieldEntry(instr.toString(), cache.toString(), true));
        }
        for (var simple : ctp.getFldSimpleList()) {
            fields.add(fieldEntry(simple.getInstr(), textOfRuns(simple.getRList()), false));
        }
        return fields;
    }

    private static Map<String, Object> fieldEntry(String instr, String cached, boolean unterminated) {
        Map<String, Object> fm = new LinkedHashMap<>();
        fm.put("instr", instr == null ? "" : instr.trim());
        fm.put("cached", cached == null ? "" : cached.trim());
        if (unterminated) {
            fm.put("unterminated", true);
        }
        return fm;
    }

    /** Counts fldChar markers of a paragraph (begin, separate, end). */
    private static int[] countFieldMarkers(CTP ctp) {
        int[] counts = new int[3];
        for (var ctr : ctp.getRList()) {
            for (var fldChar : ctr.getFldCharList()) {
                String type = fldChar.getFldCharType() == null ? "" : fldChar.getFldCharType().toString();
                if (type.contains("begin")) {
                    counts[0]++;
                } else if (type.contains("separate")) {
                    counts[1]++;
                } else if (type.contains("end")) {
                    counts[2]++;
                }
            }
        }
        return counts;
    }

    /** The settings part of a document - read only, never created as a side effect. */
    private static XWPFSettings settingsIfPresent(XWPFDocument doc) {
        for (var part : doc.getRelations()) {
            if (part instanceof XWPFSettings settings) {
                return settings;
            }
        }
        return null;
    }

    /** docPartGallery of an SDT block (e.g. "Table of Contents"), or null. */
    private static String galleryOf(
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtBlock block) {
        if (block == null || !block.isSetSdtPr() || block.getSdtPr() == null) {
            return null;
        }
        var sdtPr = block.getSdtPr();
        if (!sdtPr.isSetDocPartObj() || sdtPr.getDocPartObj() == null
                || !sdtPr.getDocPartObj().isSetDocPartGallery()) {
            return null;
        }
        return strOf(sdtPr.getDocPartObj().getDocPartGallery().getVal());
    }

    /** Section properties of a document: the body-level one plus every paragraph-level one. */
    private static List<CTSectPr> sectPrsOf(XWPFDocument doc) {
        List<CTSectPr> sectPrs = new ArrayList<>();
        var body = doc.getDocument().getBody();
        if (body != null && body.isSetSectPr() && body.getSectPr() != null) {
            sectPrs.add(body.getSectPr());
        }
        for (XWPFParagraph p : doc.getParagraphs()) {
            var ctp = p.getCTP();
            if (ctp.isSetPPr() && ctp.getPPr() != null && ctp.getPPr().isSetSectPr()
                    && ctp.getPPr().getSectPr() != null) {
                sectPrs.add(ctp.getPPr().getSectPr());
            }
        }
        return sectPrs;
    }

    /** Page setup of one section: orientation, page and margins in cm, header/footer refs. */
    private static Map<String, Object> sectionInfo(CTSectPr sectPr) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", sectPr.isSetType() && sectPr.getType().getVal() != null
                ? sectPr.getType().getVal().toString() : "nextPage");
        Double widthCm = null;
        Double heightCm = null;
        if (sectPr.isSetPgSz() && sectPr.getPgSz() != null) {
            var pgSz = sectPr.getPgSz();
            widthCm = pgSz.isSetW() ? cm(intOf(pgSz.getW())) : null;
            heightCm = pgSz.isSetH() ? cm(intOf(pgSz.getH())) : null;
            if (pgSz.isSetOrient() && pgSz.getOrient() != null) {
                s.put("orientation", pgSz.getOrient().toString());
            } else if (widthCm != null && heightCm != null) {
                s.put("orientation", widthCm > heightCm ? "landscape" : "portrait");
                s.put("orientationInferred", true);   // LibreOffice omits w:orient
            }
        }
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("widthCm", widthCm);
        page.put("heightCm", heightCm);
        s.put("page", page);
        if (sectPr.isSetPgMar() && sectPr.getPgMar() != null) {
            var mar = sectPr.getPgMar();
            Map<String, Object> margins = new LinkedHashMap<>();
            // CTPageMar offers plain getters only (unset attributes come back as null)
            margins.put("topCm", cm(intOf(mar.getTop())));
            margins.put("bottomCm", cm(intOf(mar.getBottom())));
            margins.put("leftCm", cm(intOf(mar.getLeft())));
            margins.put("rightCm", cm(intOf(mar.getRight())));
            margins.put("headerCm", cm(intOf(mar.getHeader())));
            margins.put("footerCm", cm(intOf(mar.getFooter())));
            s.put("margins", margins);
        }
        List<String> headerTypes = new ArrayList<>();
        for (var ref : sectPr.getHeaderReferenceList()) {
            headerTypes.add(ref.getType() == null ? "default" : ref.getType().toString());
        }
        List<String> footerTypes = new ArrayList<>();
        for (var ref : sectPr.getFooterReferenceList()) {
            footerTypes.add(ref.getType() == null ? "default" : ref.getType().toString());
        }
        s.put("headerTypes", headerTypes);
        s.put("footerTypes", footerTypes);
        s.put("titlePg", sectPr.isSetTitlePg());
        return s;
    }

    /** Header/footer parts of one kind, keyed by part name (/word/footer2.xml). */
    private static Map<String, XWPFHeaderFooter> partsByName(
            List<? extends XWPFHeaderFooter> parts, String kind) {
        Map<String, XWPFHeaderFooter> byName = new LinkedHashMap<>();
        for (XWPFHeaderFooter part : parts) {
            String name = partNameOf(part);
            if (name != null) {
                byName.put(name, part);
            }
        }
        return byName;
    }

    private static String partNameOf(XWPFHeaderFooter part) {
        try {
            return part == null || part.getPackagePart() == null || part.getPackagePart().getPartName() == null
                    ? null : part.getPackagePart().getPartName().getName();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** One header/footer entry, described through the section reference that uses it. */
    private Map<String, Object> refEntry(String kind,
                                         org.openxmlformats.schemas.wordprocessingml.x2006.main.CTHdrFtrRef ref,
                                         Map<String, XWPFHeaderFooter> parts, XWPFDocument doc) {
        Map<String, Object> em = new LinkedHashMap<>();
        em.put("kind", kind);
        em.put("type", ref.getType() == null ? "default" : ref.getType().toString());
        String partName = null;
        try {
            var pkg = doc.getPartById(ref.getId());
            partName = pkg == null || pkg.getPartName() == null ? null : pkg.getPartName().getName();
        } catch (RuntimeException e) {
            // unresolvable relationship: reported as resolved=false below
        }
        em.put("part", partName);
        XWPFHeaderFooter part = partName == null ? null : parts.get(partName);
        if (part == null) {
            em.put("resolved", false);
            return em;
        }
        em.put("text", part.getText());
        List<Map<String, Object>> fields = new ArrayList<>();
        for (XWPFParagraph p : part.getParagraphs()) {
            fields.addAll(collectFields(p.getCTP()));
        }
        em.put("fields", fields);
        em.put("paragraphCount", part.getParagraphs().size());
        return em;
    }

    private Map<String, Object> unreferencedEntry(String kind, String partName, XWPFHeaderFooter part) {
        Map<String, Object> em = new LinkedHashMap<>();
        em.put("kind", kind);
        em.put("type", "unreferenced");
        em.put("part", partName);
        em.put("text", part.getText());
        List<Map<String, Object>> fields = new ArrayList<>();
        for (XWPFParagraph p : part.getParagraphs()) {
            fields.addAll(collectFields(p.getCTP()));
        }
        em.put("fields", fields);
        em.put("paragraphCount", part.getParagraphs().size());
        return em;
    }

    /** Pixel size of an embedded image, or null when the format cannot be read. */
    private static int[] pixelSize(byte[] data) {
        if (data == null || data.length < 24) {
            return null;
        }
        try (ByteArrayInputStream in = new ByteArrayInputStream(data)) {
            BufferedImage image = ImageIO.read(in);
            return image == null ? null : new int[]{image.getWidth(), image.getHeight()};
        } catch (IOException | RuntimeException e) {
            return null;
        }
     }

    /** half-points (w:sz) -> pt; deliberately not {@link #pt(Integer)}, that one is twips. */
    private static Double halfPointsToPt(Integer halfPoints) {
        if (halfPoints == null) {
            return null;
        }
        return Math.round(halfPoints / 2.0 * 20.0) / 20.0;   // keep the 0.05 pt resolution
    }

    /** twips -> cm, with POI's {@code -1} ("not set") becoming null. */
    private static Double cmOrNull(int twips) {
        return twips < 0 ? null : cm(twips);
    }

    /** twips -> pt, with POI's {@code -1} ("not set") becoming null. */
    private static Double ptOrNull(int twips) {
        return twips < 0 ? null : pt(twips);
    }

    /** twips -> cm, rounded to 0.01 cm. */
    private static Double cm(Integer twips) {        if (twips == null) {
            return null;
        }
        return Math.round(twips / TWIPS_PER_CM * 100.0) / 100.0;
    }

    /** twips -> pt, rounded to 0.05 pt. */
    private static Double pt(Integer twips) {
        if (twips == null) {
            return null;
        }
        return Math.round(twips / TWIPS_PER_PT * 20.0) / 20.0;
    }

    /**
     * XMLBeans returns measure and count values either as {@code BigInteger} or as
     * {@code Object} (attribute types built on anonymous simpleTypes), so the readers go
     * through this converter instead of assuming a static type.
     */
    private static Integer intOf(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return new BigInteger(String.valueOf(value).trim()).intValue();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Same as {@link #intOf(Object)} for the places that keep the {@code BigInteger}. */
    private static BigInteger bigOf(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigInteger big) {
            return big;
        }
        if (value instanceof Number number) {
            return BigInteger.valueOf(number.longValue());
        }
        try {
            return new BigInteger(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Any XMLBeans simple value as text (hex colours, level text, gallery names). */
    private static String strOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    // ========================================================================
    // Writing: tables (M1, Aufgabe 1) and paragraph formatting (Aufgabe 2)
    // ========================================================================

    /**
     * Creates a table at the end of the body and returns its handle.
     * <p>
     * Options (all optional, unknown keys are an error):
     * </p>
     * <ul>
     *   <li>{@code style} – style id or name of a table style that already exists</li>
     *   <li>{@code borders} – {@code "single"} (default) or {@code "none"}; borders are set
     *       directly on the table so that a document without a styles part still shows a grid</li>
     *   <li>{@code widths} – per column: {@code "4.5cm"}, {@code "25%"} or {@code "auto"}</li>
     *   <li>{@code header} – texts for the first row</li>
     *   <li>{@code align} – per column: {@code "left"|"right"|"center"|"both"}</li>
     * </ul>
     *
     * @param docHandle document handle
     * @param rows row count, at least 1
     * @param cols column count, 1..63
     * @param options optional settings, may be null
     * @return table handle (e.g. "t-3")
     */
    public String createTable(String docHandle, int rows, int cols, Map<String, Object> options) {
        XWPFDocument doc = requireDocument(docHandle);
        requireKnownOptions("createTable", options, "style", "borders", "widths", "header", "align");
        if (rows < 1 || rows > MAX_TABLE_ROWS) {
            throw new PoiUserRuntimeException("createTable: argument 'rows' must be between 1 and "
                    + MAX_TABLE_ROWS + ", but was: " + rows);
        }
        if (cols < 1 || cols > MAX_TABLE_COLS) {
            throw new PoiUserRuntimeException("createTable: argument 'cols' must be between 1 and "
                    + MAX_TABLE_COLS + ", but was: " + cols);
        }
        XWPFTable table = doc.createTable(rows, cols);

        String style = optString("createTable", options, "style", null);
        if (style != null) {
            table.setStyleID(resolveStyleId("createTable", doc, style));
        }
        String borders = optString("createTable", options, "borders", "single");
        if (!"none".equalsIgnoreCase(borders)) {
            STBorder.Enum borderStyle;
            if ("single".equalsIgnoreCase(borders)) {
                borderStyle = STBorder.SINGLE;
            } else if ("double".equalsIgnoreCase(borders)) {
                borderStyle = STBorder.DOUBLE;
            } else {
                throw new PoiUserRuntimeException("createTable: option 'borders' must be \"single\", "
                        + "\"double\" or \"none\", but was: '" + borders + "'");
            }
            for (int i = 0; i < table.getRows().size(); i++) {
                for (XWPFTableCell cell : table.getRows().get(i).getTableCells()) {
                    setCellBorders(cell, borderStyle);
                }
            }
        }
        List<String> widths = optStringList("createTable", options, "widths");
        if (widths != null) {
            applyWidths(table, widths);
        }
        List<String> aligns = optStringList("createTable", options, "align");
        if (aligns != null) {
            applyColumnAlignment(table, aligns);
        }
        List<String> header = optStringList("createTable", options, "header");
        if (header != null) {
            if (header.size() > cols) {
                throw new PoiUserRuntimeException("createTable: option 'header' has " + header.size()
                        + " entries, the table has " + cols + " columns");
            }
            XWPFTableRow first = table.getRows().get(0);
            for (int c = 0; c < header.size(); c++) {
                XWPFTableCell headerCell = first.getCell(c);
                writeCellText(headerCell, header.get(c));
                for (XWPFParagraph p : headerCell.getParagraphs()) {
                    for (XWPFRun run : p.getRuns()) {
                        run.setBold(true);
                    }
                }
            }
        }
        String handle = registerTable(table, docHandle);
        LOG.info("Created table {}x{} in {} -> {}", rows, cols, docHandle, handle);
        return handle;
    }

    /**
     * Writes text into one cell and applies direct character formatting.
     * <p>
     * The cell keeps a single paragraph; existing runs are replaced. Options:
     * {@code bold}, {@code italic}, {@code fontSizePt}, {@code fontName}, {@code color},
     * {@code align}, {@code valign}, {@code shading}.
     * </p>
     * @param docHandle document handle
     * @param tableIndex 0-based table index
     * @param rowIndex 0-based row index
     * @param colIndex 0-based column index
     * @param text new cell text
     * @param options optional formatting, may be null
     */
    public void setCellText(String docHandle, int tableIndex, int rowIndex, int colIndex,
                            String text, Map<String, Object> options) {
        setCellText(requireTable(docHandle, tableIndex), rowIndex, colIndex, text, options);
    }

    /** Overload of {@link #setCellText(String, int, int, int, String, Map)} by table handle. */
    public void setCellTextByHandle(String docHandle, String tableHandle, int rowIndex, int colIndex,
                                    String text, Map<String, Object> options) {
        setCellText(requireTableByHandle(tableHandle), rowIndex, colIndex, text, options);
    }

    private void setCellText(XWPFTable table, int rowIndex, int colIndex,
                             String text, Map<String, Object> options) {
        requireKnownOptions("setCellText", options, "bold", "italic", "fontSizePt", "fontName",
                "color", "align", "valign", "shading");
        XWPFTableCell cell = requireCell(table, rowIndex, colIndex);
        writeCellText(cell, text);
        Boolean bold = optBoolean("setCellText", options, "bold", null);
        Boolean italic = optBoolean("setCellText", options, "italic", null);
        Double sizePt = optNumber("setCellText", options, "fontSizePt", DocxToolBox.MIN_FONT_PT,
                DocxToolBox.MAX_FONT_PT, null);
        String fontName = optString("setCellText", options, "fontName", null);
        String color = optString("setCellText", options, "color", null);
        String align = optString("setCellText", options, "align", null);
        String valign = optString("setCellText", options, "valign", null);
        String shading = optString("setCellText", options, "shading", null);
        for (XWPFParagraph p : cell.getParagraphs()) {
            if (align != null) {
                p.setAlignment(parseAlignment("setCellText", align));
            }
            for (XWPFRun run : p.getRuns()) {
                if (bold != null) {
                    run.setBold(bold);
                }
                if (italic != null) {
                    run.setItalic(italic);
                }
                if (sizePt != null) {
                    run.setFontSize(sizePt);
                }
                if (fontName != null) {
                    run.setFontFamily(fontName);
                }
                if (color != null) {
                    run.setColor(requireHex("setCellText", "color", color));
                }
            }
        }
        if (valign != null) {
            cell.setVerticalAlignment(parseVertAlign("setCellText", valign));
        }
        if (shading != null) {
            cell.setColor(requireHex("setCellText", "shading", shading));
        }
    }

    /**
     * Describes one cell: text, merge state, shading and width.
     * @param docHandle document handle
     * @param tableIndex 0-based table index
     * @param rowIndex 0-based row index
     * @param colIndex 0-based column index
     * @return JSON-compatible Map
     */
    public Map<String, Object> getCellProps(String docHandle, int tableIndex, int rowIndex, int colIndex) {
        return cellInfo(requireCell(requireTable(docHandle, tableIndex), rowIndex, colIndex),
                docHandle, true);
    }

    /** Overload of {@link #getCellProps(String, int, int, int)} by table handle. */
    public Map<String, Object> getCellPropsByHandle(String docHandle, String tableHandle,
                                                    int rowIndex, int colIndex) {
        return cellInfo(requireCell(requireTableByHandle(tableHandle), rowIndex, colIndex),
                docHandle, true);
    }

    /**
     * Sets the paragraph style of an existing paragraph (D4: id, display name or canonical
     * match). The style must exist - a reference to a missing style renders as "Normal"
     * in Word and would fail silently, so this throws and lists the available styles.
     * @param paragraphHandle paragraph handle
     * @param style style id or name, e.g. "Heading1" or "Überschrift 1"
     */
    public void setParagraphStyle(String paragraphHandle, String style) {
        XWPFParagraph p = requireParagraph(paragraphHandle);
        XWPFDocument doc = documents.get(paragraphDoc.get(paragraphHandle));
        p.setStyle(resolveStyleId("setParagraphStyle", doc, style));
    }

    /**
     * Aligns a paragraph: "left", "right", "center" or "both" (justified).
     * @param paragraphHandle paragraph handle
     * @param alignment alignment name
     */
    public void setParagraphAlignment(String paragraphHandle, String alignment) {
        requireParagraph(paragraphHandle).setAlignment(parseAlignment("setParagraphAlignment", alignment));
    }

    /**
     * Sets indents of a paragraph in centimetres (unchanged keys stay untouched):
     * {@code leftCm}, {@code rightCm}, {@code firstLineCm}, {@code hangingCm}.
     * @param paragraphHandle paragraph handle
     * @param options indent values in cm
     */
    public void setParagraphIndent(String paragraphHandle, Map<String, Object> options) {
        requireKnownOptions("setParagraphIndent", options, "leftCm", "rightCm", "firstLineCm", "hangingCm");
        XWPFParagraph p = requireParagraph(paragraphHandle);
        Double left = optNumber("setParagraphIndent", options, "leftCm", -100.0, 100.0, null);
        Double right = optNumber("setParagraphIndent", options, "rightCm", -100.0, 100.0, null);
        Double first = optNumber("setParagraphIndent", options, "firstLineCm", -100.0, 100.0, null);
        Double hanging = optNumber("setParagraphIndent", options, "hangingCm", -100.0, 100.0, null);
        if (left != null) {
            p.setIndentationLeft(toTwips(left));
        }
        if (right != null) {
            p.setIndentationRight(toTwips(right));
        }
        if (first != null) {
            p.setIndentationFirstLine(toTwips(first));
        }
        if (hanging != null) {
            p.setIndentationHanging(toTwips(hanging));
        }
    }

    /**
     * Sets paragraph spacing: {@code beforePt}, {@code afterPt} (points) and {@code line}
     * (factor, 1.0 = single, or absolute points together with {@code lineRule}).
     * @param paragraphHandle paragraph handle
     * @param options spacing values
     */
    public void setParagraphSpacing(String paragraphHandle, Map<String, Object> options) {
        requireKnownOptions("setParagraphSpacing", options, "beforePt", "afterPt", "line", "lineRule");
        XWPFParagraph p = requireParagraph(paragraphHandle);
        Double before = optNumber("setParagraphSpacing", options, "beforePt", 0.0, 5000.0, null);
        Double after = optNumber("setParagraphSpacing", options, "afterPt", 0.0, 5000.0, null);
        Double line = optNumber("setParagraphSpacing", options, "line", 0.1, 500.0, null);
        String lineRule = optString("setParagraphSpacing", options, "lineRule", null);
        if (before != null) {
            p.setSpacingBefore((int) Math.round(before * TWIPS_PER_PT));
        }
        if (after != null) {
            p.setSpacingAfter((int) Math.round(after * TWIPS_PER_PT));
        }
        if (line != null) {
            if (lineRule == null || "auto".equalsIgnoreCase(lineRule)) {
                p.setSpacingBetween(line);
            } else {
                p.setSpacingBetween(line, parseLineRule("setParagraphSpacing", lineRule));
            }
        }
    }

    /**
     * Lists the styles of a document in the order Word would show them, with the keys that
     * resolve (D4). Errors from the style setters refer to this list.
     * @param docHandle document handle
     * @return list of {@code {styleId, name, canonical}}
     */
    public List<Map<String, Object>> listStyleKeys(String docHandle) {
        XWPFDocument doc = requireDocument(docHandle);
        List<Map<String, Object>> out = new ArrayList<>();
        var styles = doc.getStyles();
        if (styles == null) {
            return out;
        }
        for (var style : styles.getStyles()) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("styleId", style.getStyleId());
            sm.put("name", style.getName());
            sm.put("canonical", styleKeys(style.getStyleId()).toString());
            out.add(sm);
        }
        return out;
    }

    // ------------------------------------------------------------------------
    // Table / style helpers
    // ------------------------------------------------------------------------

    private XWPFDocument requireDocument(String docHandle) {
        XWPFDocument doc = documents.get(docHandle);
        if (doc == null) {
            throw new PoiUserRuntimeException("Document not found (invalid handle): " + docHandle);
        }
        return doc;
    }

    private XWPFParagraph requireParagraph(String paragraphHandle) {
        XWPFParagraph p = paragraphs.get(paragraphHandle);
        if (p == null) {
            throw new PoiUserRuntimeException("Paragraph not found (invalid handle): " + paragraphHandle);
        }
        return p;
    }

    private XWPFTable requireTable(String docHandle, int tableIndex) {
        XWPFDocument doc = requireDocument(docHandle);
        List<XWPFTable> found = doc.getTables();
        if (found.isEmpty()) {
            throw new PoiUserRuntimeException("This document contains no tables "
                    + "(use docx.createTable(doc, rows, cols) first).");
        }
        if (tableIndex < 0 || tableIndex >= found.size()) {
            throw new PoiUserRuntimeException("Table index out of range: " + tableIndex
                    + " (valid range: 0.." + (found.size() - 1) + ")");
        }
        return found.get(tableIndex);
    }

    private XWPFTable requireTableByHandle(String tableHandle) {
        XWPFTable table = tables.get(tableHandle);
        if (table == null) {
            throw new PoiUserRuntimeException("Table not found (invalid handle): " + tableHandle
                    + ". Table handles come from docx.createTable(...) or docx.getTables(...).");
        }
        return table;
    }

    private XWPFTableCell requireCell(XWPFTable table, int rowIndex, int colIndex) {
        List<XWPFTableRow> rows = table.getRows();
        if (rowIndex < 0 || rowIndex >= rows.size()) {
            throw new PoiUserRuntimeException("Row index out of range: " + rowIndex
                    + " (valid range: 0.." + (rows.size() - 1) + ")");
        }
        XWPFTableRow row = rows.get(rowIndex);
        List<XWPFTableCell> rowCells = row.getTableCells();
        if (colIndex < 0 || colIndex >= rowCells.size()) {
            throw new PoiUserRuntimeException("Column index out of range: " + colIndex
                    + " (valid range: 0.." + (rowCells.size() - 1)
                    + " in this row; merged rows can have fewer cells)");
        }
        return rowCells.get(colIndex);
    }

    private String registerTable(XWPFTable table, String docHandle) {
        String th = generateHandle("t");
        tables.put(th, table);
        tableDoc.put(th, docHandle);
        return th;
    }

    /** One cell as the reader reports it: text, merge state, shading, width. */
    private Map<String, Object> cellInfo(XWPFTableCell cell, String docHandle, boolean withHandle) {
        Map<String, Object> cm = new LinkedHashMap<>();
        if (withHandle) {
            String ch = generateHandle("tc");
            cells.put(ch, cell);
            cellDoc.put(ch, docHandle);
            cm.put("h", ch);
            // Cell text is not reachable through getParagraphs() (body flow only), so the
            // run handles come with the cell - this is the read partner of setCellText.
            List<Map<String, Object>> cellRuns = new ArrayList<>();
            for (XWPFParagraph p : cell.getParagraphs()) {
                for (XWPFRun r : p.getRuns()) {
                    cellRuns.add(getRunProps(registerRun(r, docHandle)));
                }
            }
            cm.put("runs", cellRuns);
        }
        cm.put("text", cell.getText());
        var tcPr = cell.getCTTc().isSetTcPr() ? cell.getCTTc().getTcPr() : null;
        int colSpan = 1;
        if (tcPr != null && tcPr.isSetGridSpan() && tcPr.getGridSpan() != null) {
            Integer span = intOf(tcPr.getGridSpan().getVal());
            if (span != null && span > 1) {
                colSpan = span;
            }
        }
        cm.put("colSpan", colSpan);
        if (tcPr != null && tcPr.isSetVMerge()) {
            var val = tcPr.getVMerge() == null ? null : tcPr.getVMerge().getVal();
            cm.put("vMerge", val == null ? "continue" : strOf(val));
        }
        cm.put("merged", colSpan > 1 || (tcPr != null && tcPr.isSetVMerge()));
        if (cell.getColor() != null) {
            cm.put("shading", cell.getColor());
        }
        // Bewusst keine Zellbreite gemeldet: POIs XWPFTableCell.getWidth() liest die Union
        // ST_MeasurementOrPercent und läuft in eine NullPointerException, sobald das w:tcW aus
        // XWPFTable.createRow() oder einer tcPr-Kopie stammt (in JsDocxTest beobachtet).
        // Breite kommt ins Leseprofil, wenn Aufgabe 7 sie selbst schreibt und validiert.
        if (!cell.getParagraphs().isEmpty()) {
            var alignment = cell.getParagraphs().get(0).getAlignment();
            cm.put("align", alignment == null ? null : alignment.name().toLowerCase(Locale.ROOT));
        }
        return cm;
    }

    /** Writes a cell's text into its first paragraph, replacing what was there. */
    private void writeCellText(XWPFTableCell cell, String text) {
        String safe = text == null ? "" : text;
        while (cell.getParagraphs().size() > 1) {
            cell.removeParagraph(cell.getParagraphs().size() - 1);
        }
        XWPFParagraph paragraph;
        if (cell.getParagraphs().isEmpty()) {
            paragraph = cell.addParagraph();
        } else {
            paragraph = cell.getParagraphs().get(0);
            for (int i = paragraph.getRuns().size() - 1; i >= 0; i--) {
                paragraph.removeRun(i);
            }
        }
        paragraph.createRun().setText(safe);
    }

    /** D6: never clone a merged cell - first row without gridSpan/vMerge is the template. */
    private void addRowInternal(XWPFTable table, String[] cellTexts) {
        List<XWPFTableRow> rows = table.getRows();
        XWPFTableRow template = null;
        for (XWPFTableRow candidate : rows) {
            if (!hasMergedCell(candidate)) {
                template = candidate;
                break;
            }
        }
        XWPFTableRow row = table.createRow();
        int cols = row.getTableCells().size();
        if (cellTexts != null && cellTexts.length > cols) {
            throw new PoiUserRuntimeException("addRow: " + cellTexts.length + " cell texts for a table"
                    + " with " + cols + " columns - the extra values would be dropped");
        }
        for (int i = 0; i < cols; i++) {
            XWPFTableCell cell = row.getCell(i);
            if (cell == null) {
                cell = row.addNewTableCell();
            }
            if (template != null && i < template.getTableCells().size()) {
                copyCellFormat(template.getTableCells().get(i), cell);
            }
            writeCellText(cell, cellTexts == null || i >= cellTexts.length ? "" : cellTexts[i]);
        }
    }

    /**
     * Number of columns of a table: the {@code w:tblGrid} entries, falling back to the widest
     * row (POI has no {@code getNumberOfColumns()}).
     */
    private static int columnCount(XWPFTable table) {
        var ctTbl = table.getCTTbl();
        var grid = ctTbl == null ? null : ctTbl.getTblGrid();
        if (grid != null && grid.sizeOfGridColArray() > 0) {
            return grid.sizeOfGridColArray();
        }
        int max = 0;
        for (XWPFTableRow row : table.getRows()) {
            max = Math.max(max, row.getTableCells().size());
        }
        return max;
    }

    private static boolean hasMergedCell(XWPFTableRow row) {
        for (XWPFTableCell cell : row.getTableCells()) {
            var tcPr = cell.getCTTc().isSetTcPr() ? cell.getCTTc().getTcPr() : null;
            if (tcPr == null) {
                continue;
            }
            if (tcPr.isSetGridSpan() && tcPr.getGridSpan() != null) {
                Integer span = intOf(tcPr.getGridSpan().getVal());
                if (span != null && span > 1) {
                    return true;
                }
            }
            if (tcPr.isSetVMerge()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Copies the printable formatting of a template cell (width, borders, margins) but never
     * the merge or shading flags - those made every appended row of a merged table wrong.
     */
    private static void copyCellFormat(XWPFTableCell from, XWPFTableCell to) {
        if (!from.getCTTc().isSetTcPr() || from.getCTTc().getTcPr() == null) {
            return;
        }
        CTTcPr copy = (CTTcPr) from.getCTTc().getTcPr().copy();
        if (copy.isSetGridSpan()) {
            copy.unsetGridSpan();
        }
        if (copy.isSetVMerge()) {
            copy.unsetVMerge();
        }
        if (copy.isSetHMerge()) {
            copy.unsetHMerge();
        }
        if (copy.isSetShd()) {
            copy.unsetShd();
        }
        to.getCTTc().setTcPr(copy);
    }

    /**
     * Sets the four borders of a cell. {@link XWPFTable.XWPFBorderType} is deliberately not
     * used here: its {@code toString()} is not a valid {@code ST_Border} value, and handing
     * that to {@code forString()} yields null, which XmlBeans then stores as an empty string
     * (and rejects with XmlValueOutOfRange the moment the document is written).
     */
    private static void setCellBorders(XWPFTableCell cell, STBorder.Enum style) {
        CTTcPr tcPr = cell.getCTTc().isSetTcPr() ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
        CTTcBorders borders = tcPr.isSetTcBorders() ? tcPr.getTcBorders() : tcPr.addNewTcBorders();
        applyBorder(borders.isSetTop() ? borders.getTop() : borders.addNewTop(), style);
        applyBorder(borders.isSetBottom() ? borders.getBottom() : borders.addNewBottom(), style);
        applyBorder(borders.isSetLeft() ? borders.getLeft() : borders.addNewLeft(), style);
        applyBorder(borders.isSetRight() ? borders.getRight() : borders.addNewRight(), style);
    }

    private static void applyBorder(CTBorder border, STBorder.Enum style) {
        border.setVal(style);
        border.setSz(BigInteger.valueOf(4));      // 0.5 pt, eighth-of-a-point units
        border.setSpace(BigInteger.valueOf(0));
    }

    /** Sets the column grid and every cell width from entries like "4.5cm", "25%", "auto". */
    private void applyWidths(XWPFTable table, List<String> widths) {
        int cols = columnCount(table);
        if (widths.size() != cols) {
            throw new PoiUserRuntimeException("createTable: option 'widths' has " + widths.size()
                    + " entries, the table has " + cols + " columns");
        }
        var ctTbl = table.getCTTbl();
        var grid = ctTbl.getTblGrid() != null ? ctTbl.getTblGrid() : ctTbl.addNewTblGrid();
        while (grid.sizeOfGridColArray() < cols) {
            grid.addNewGridCol();
        }
        while (grid.sizeOfGridColArray() > cols) {
            grid.removeGridCol(grid.sizeOfGridColArray() - 1);
        }
        for (int c = 0; c < cols; c++) {
            String spec = widths.get(c);
            if (spec == null || "auto".equalsIgnoreCase(spec)) {
                continue;
            }
            if (spec.trim().endsWith("%")) {
                // Percentage widths belong on the cells only; the grid keeps its column widths
                int pct = parsePercent("createTable", "widths", spec);
                for (XWPFTableRow row : table.getRows()) {
                    List<XWPFTableCell> rowCells = row.getTableCells();
                    if (c < rowCells.size()) {
                        rowCells.get(c).setWidth(pct + "%");
                    }
                }
            } else {
                int twips = toTwips(parseCm("createTable", "widths", spec));
                grid.getGridColArray(c).setW(BigInteger.valueOf(twips));
                for (XWPFTableRow row : table.getRows()) {
                    List<XWPFTableCell> rowCells = row.getTableCells();
                    if (c < rowCells.size()) {
                        rowCells.get(c).setWidth(String.valueOf(twips));
                    }
                }
            }
        }
    }

    private void applyColumnAlignment(XWPFTable table, List<String> aligns) {
        int cols = columnCount(table);
        if (aligns.size() != cols) {
            throw new PoiUserRuntimeException("createTable: option 'align' has " + aligns.size()
                    + " entries, the table has " + cols + " columns");
        }
        for (int c = 0; c < cols; c++) {
            ParagraphAlignment alignment = parseAlignment("createTable", aligns.get(c));
            for (XWPFTableRow row : table.getRows()) {
                List<XWPFTableCell> rowCells = row.getTableCells();
                if (c < rowCells.size()) {
                    for (XWPFParagraph p : rowCells.get(c).getParagraphs()) {
                        p.setAlignment(alignment);
                    }
                }
            }
        }
    }

    /** D4: exact style id, then display name, then canonical comparison. */
    private String resolveStyleId(String fn, XWPFDocument doc, String requested) {
        if (requested == null || requested.isBlank()) {
            throw new PoiUserRuntimeException(fn + ": argument 'style' must be a style id or name.");
        }
        var styles = doc == null ? null : doc.getStyles();
        if (styles == null && doc != null) {
            // Aufgabe 2: the first style access on a document without styles.xml builds the
            // built-in part instead of leaving the reference to silently render "Normal".
            ensureStylesOf(doc);
            styles = doc.getStyles();
        }
        if (styles == null) {
            throw new PoiUserRuntimeException(fn + ": this document has no styles part and none "
                    + "could be created.");
        }
        if (styles.styleExist(requested)) {
            return requested;
        }
        var byName = styles.getStyleWithName(requested);
        if (byName != null) {
            return byName.getStyleId();
        }
        Set<String> wanted = styleKeys(requested);
        for (var style : styles.getStyles()) {
            for (String candidate : List.of(nullToEmpty(style.getStyleId()), nullToEmpty(style.getName()))) {
                if (candidate.isEmpty()) {
                    continue;
                }
                Set<String> keys = styleKeys(candidate);
                for (String key : wanted) {
                    if (keys.contains(key)) {
                        return style.getStyleId();
                    }
                }
            }
        }
        throw new PoiUserRuntimeException(fn + ": unknown style '" + requested
                + "'. This document has: " + styleSummary(styles) + ". See docx.getStylesInfo(doc).");
    }

    /**
     * Canonical key of a style name: umlauts folded, brackets and punctuation dropped,
     * lower case. LibreOffice and Word build styleIds by dropping a leading umlaut
     * ("Überschrift" -&gt; "berschrift"), so the fold without its first character is added too.
     */
    private static Set<String> styleKeys(String value) {
        Set<String> keys = new LinkedHashSet<>();
        if (value == null) {
            return keys;
        }
        String folded = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        folded = folded.replaceAll("\\([^)]*\\)", "");           // "Überschrift (user)"
        folded = folded.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (!folded.isEmpty()) {
            keys.add(folded);
            if (folded.length() > 3) {
                keys.add(folded.substring(1));                   // "uberschrift" -> "berschrift"
            }
        }
        return keys;
    }

    private static String styleSummary(XWPFStyles styles) {
        StringBuilder sb = new StringBuilder();
        for (var style : styles.getStyles()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(style.getStyleId());
            String name = style.getName();
            if (name != null && !name.equals(style.getStyleId())) {
                sb.append(" (").append(name).append(")");
            }
            if (sb.length() > 400) {
                sb.append(" …");
                break;
            }
        }
        return sb.toString();
    }

    private static ParagraphAlignment parseAlignment(String fn, String value) {
        if (value == null) {
            throw new PoiUserRuntimeException(fn + ": alignment must be one of "
                    + "\"left\", \"right\", \"center\", \"both\", but was null.");
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "left" -> ParagraphAlignment.LEFT;
            case "right" -> ParagraphAlignment.RIGHT;
            case "center", "centre", "mitte" -> ParagraphAlignment.CENTER;
            case "both", "justify", "blocksatz" -> ParagraphAlignment.BOTH;
            case "distribute" -> ParagraphAlignment.DISTRIBUTE;
            default -> throw new PoiUserRuntimeException(fn + ": unknown alignment '" + value
                    + "' (valid: left, right, center, both)");
        };
    }

    private static XWPFTableCell.XWPFVertAlign parseVertAlign(String fn, String value) {
        return switch (value == null ? "" : value.toLowerCase(Locale.ROOT)) {
            case "top" -> XWPFTableCell.XWPFVertAlign.TOP;
            case "center", "centre", "middle" -> XWPFTableCell.XWPFVertAlign.CENTER;
            case "bottom" -> XWPFTableCell.XWPFVertAlign.BOTTOM;
            default -> throw new PoiUserRuntimeException(fn + ": unknown valign '" + value
                    + "' (valid: top, center, bottom)");
        };
    }

    private static LineSpacingRule parseLineRule(String fn, String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "exact" -> LineSpacingRule.EXACT;
            case "atleast", "at-least", "mindestens" -> LineSpacingRule.AT_LEAST;
            case "auto" -> LineSpacingRule.AUTO;
            default -> throw new PoiUserRuntimeException(fn + ": unknown lineRule '" + value
                    + "' (valid: auto, exact, atLeast)");
        };
    }

    private static String requireHex(String fn, String name, String value) {
        String hex = PoiColorUtil.stripHash(value);
        if (hex == null || !hex.matches("(?i)[0-9a-f]{6}")) {
            throw new PoiUserRuntimeException(fn + ": argument '" + name + "' must be 6 hex digits "
                    + "(e.g. \"D9E2F3\"), but was: '" + value + "'");
        }
        return hex;
    }

    private static int toTwips(double cm) {
        return (int) Math.round(cm * TWIPS_PER_CM);
    }

    private static double parseCm(String fn, String name, String spec) {
        String s = spec.trim().toLowerCase(Locale.ROOT);
        if (!s.endsWith("cm")) {
            throw new PoiUserRuntimeException(fn + ": option '" + name + "' expects \"4.5cm\", "
                    + "\"25%\" or \"auto\", but was: '" + spec + "'");
        }
        try {
            return Double.parseDouble(s.substring(0, s.length() - 2).trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new PoiUserRuntimeException(fn + ": option '" + name + "' has a non-numeric length: '"
                    + spec + "'");
        }
    }

    private static int parsePercent(String fn, String name, String spec) {
        String s = spec.trim().replace("%", "").trim().replace(',', '.');
        try {
            double pct = Double.parseDouble(s);
            if (pct <= 0 || pct > 100) {
                throw new NumberFormatException();
            }
            return (int) Math.round(pct);
        } catch (NumberFormatException e) {
            throw new PoiUserRuntimeException(fn + ": option '" + name + "' expects a percentage "
                    + "between 1 and 100, but was: '" + spec + "'");
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    // ------------------------------------------------------------------------
    // Option objects (JS passes {key: value}; unknown keys must not pass silently)
    // ------------------------------------------------------------------------

    private static void requireKnownOptions(String fn, Map<String, Object> options, String... known) {
        if (options == null || options.isEmpty()) {
            return;
        }
        Set<String> allowed = Set.of(known);
        for (String key : options.keySet()) {
            if (!allowed.contains(key)) {
                throw new PoiUserRuntimeException(fn + ": unknown option '" + key + "' (known: "
                        + String.join(", ", known) + "). See docx.help().");
            }
        }
    }

    private static String optString(String fn, Map<String, Object> options, String key, String fallback) {
        Object value = options == null ? null : options.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof String s) {
            return s;
        }
        throw new PoiUserRuntimeException(fn + ": option '" + key + "' must be a string, but was: '"
                + value + "'");
    }

    private static Boolean optBoolean(String fn, Map<String, Object> options, String key, Boolean fallback) {
        Object value = options == null ? null : options.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        throw new PoiUserRuntimeException(fn + ": option '" + key + "' must be true or false, but was: '"
                + value + "'");
    }

    private static Double optNumber(String fn, Map<String, Object> options, String key,
                                    double min, double max, Double fallback) {
        Object value = options == null ? null : options.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Number n)) {
            throw new PoiUserRuntimeException(fn + ": option '" + key + "' must be a number between "
                    + min + " and " + max + ", but was: '" + value + "'");
        }
        double parsed = n.doubleValue();
        if (Double.isNaN(parsed) || Double.isInfinite(parsed) || parsed < min || parsed > max) {
            throw new PoiUserRuntimeException(fn + ": option '" + key + "' must be a number between "
                    + min + " and " + max + ", but was: " + parsed);
        }
        return parsed;
    }

    private static List<String> optStringList(String fn, Map<String, Object> options, String key) {
        Object value = options == null ? null : options.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> list)) {
            throw new PoiUserRuntimeException(fn + ": option '" + key + "' must be an array of strings,"
                    + " but was: '" + value + "'");
        }
        List<String> strings = new ArrayList<>();
        for (Object element : list) {
            if (element != null && !(element instanceof String)) {
                throw new PoiUserRuntimeException(fn + ": option '" + key + "' must contain strings,"
                        + " but contained: '" + element + "'");
            }
            strings.add(element == null ? null : (String) element);
        }
        return strings;
    }

    // ========================================================================
    // Styles and numbering parts (M1, Aufgabe 2 and 3)
    // ========================================================================

    /** List kinds that {@link #ensureNumbering(String)} knows. */
    public static final List<String> LIST_KINDS = List.of("bullet", "decimal", "multilevel");

    /**
     * Makes sure the document has a styles part with the built-in set: Normal, Heading1-3
     * (with a real {@code w:outlineLvl}, which is what puts them in Word's outline view),
     * Title, Subtitle, ListParagraph, Caption and the table style TableGrid.
     * <p>
     * A document created by {@code docx.createDocument()} has no {@code styles.xml} at all,
     * and a style reference to a missing style is what Word calls a silent no-op: it renders
     * "Normal". Existing styles are never overwritten, so calling this on a foreign document
     * only fills the gaps. Templates would be the better source but are not available to this
     * tool yet (docs/js/docx.md, §4 note), hence the programmatic part.
     * </p>
     * @param docHandle document handle
     * @return JSON-compatible Map with {@code created}, {@code added}, {@code styleCount}
     */
    public Map<String, Object> ensureStyles(String docHandle) {
        return ensureStylesOf(requireDocument(docHandle));
    }

    private Map<String, Object> ensureStylesOf(XWPFDocument doc) {
        boolean created = doc.getStyles() == null;
        var styles = created ? doc.createStyles() : doc.getStyles();
        List<String> added = new ArrayList<>();
        addParagraphStyle(styles, "Normal", "Normal", null, null, 12.0, false, false, false, added);
        addParagraphStyle(styles, "Heading1", "heading 1", "Normal", 0, 16.0, true, true, false, added);
        addParagraphStyle(styles, "Heading2", "heading 2", "Normal", 1, 14.0, true, true, false, added);
        addParagraphStyle(styles, "Heading3", "heading 3", "Normal", 2, 12.0, true, true, false, added);
        addParagraphStyle(styles, "Title", "Title", "Normal", null, 28.0, true, false, false, added);
        addParagraphStyle(styles, "Subtitle", "Subtitle", "Normal", null, 16.0, false, false, true, added);
        addParagraphStyle(styles, "ListParagraph", "List Paragraph", "Normal", null, 12.0, false, false, false, added);
        addParagraphStyle(styles, "Caption", "caption", "Normal", null, 12.0, false, false, true, added);
        addTableGridStyle(styles, added);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("created", created);
        out.put("added", added);
        out.put("styleCount", styles.getNumberOfStyles());
        LOG.info("ensureStyles on new document: {} ({} added)", created ? "created" : "extended", added.size());
        return out;
    }

    private static void addParagraphStyle(XWPFStyles styles, String id, String name, String basedOn,
                                          Integer outlineLvl, double sizePt, boolean bold,
                                          boolean keepNext, boolean italic, List<String> added) {
        if (styles.styleExist(id)) {
            return;
        }
        CTStyle ct = CTStyle.Factory.newInstance();
        ct.setType(STStyleType.PARAGRAPH);
        ct.setStyleId(id);
        ct.addNewName().setVal(name);
        if (basedOn != null && styles.styleExist(basedOn)) {
            ct.addNewBasedOn().setVal(basedOn);
        }
        ct.addNewQFormat();
        var pPr = ct.addNewPPr();
        if (keepNext) {
            pPr.addNewKeepNext();
        }
        if (outlineLvl != null) {
            pPr.addNewOutlineLvl().setVal(BigInteger.valueOf(outlineLvl));
        }
        var rPr = ct.addNewRPr();
        if (bold) {
            rPr.addNewB();
        }
        if (italic) {
            rPr.addNewI();
        }
        rPr.addNewSz().setVal(BigInteger.valueOf(Math.round(sizePt * 2)));   // half-points
        styles.addStyle(new XWPFStyle(ct));
        added.add(id);
    }

    /**
     * Adds the table style "TableGrid" - but without {@code w:tblPr}: poi-ooxml-lite ships only
     * the schema types POI itself reaches, and {@code CTStyle.addNewTblPr()} fails at runtime
     * with "Could not locate compiled schema resource …cttblprbase…xsb" (measured in
     * JsDocxTest). That is harmless here because {@link #createTable} draws the grid as
     * per-cell borders anyway - the same reason the plan chose direct borders over "Table Grid".
     */
    private static void addTableGridStyle(XWPFStyles styles, List<String> added) {
        if (styles.styleExist("TableGrid")) {
            return;
        }
        CTStyle ct = CTStyle.Factory.newInstance();
        ct.setType(STStyleType.TABLE);
        ct.setStyleId("TableGrid");
        ct.addNewName().setVal("Table Grid");
        styles.addStyle(new XWPFStyle(ct));
        added.add("TableGrid");
    }

    /**
     * Makes sure the document has {@code numbering.xml} definitions for the three list kinds
     * and reports which {@code numId} each one uses.
     * <p>
     * D3: foreign documents already occupy numIds (Ribbeck 1-3, LibreOffice_Fixture1 1-5), so
     * an existing definition of the wanted shape is <em>reused</em>; if none fits, a new
     * abstract definition and the <em>first free</em> numId are allocated. Fixed ids would
     * silently attach the list to somebody else's definition.
     * </p>
     * @param docHandle document handle
     * @return JSON-compatible Map with {@code created}, {@code added}, {@code lists}
     */
    public Map<String, Object> ensureNumbering(String docHandle) {
        return ensureNumberingOf(requireDocument(docHandle));
    }

    private Map<String, Object> ensureNumberingOf(XWPFDocument doc) {
        boolean created = doc.getNumbering() == null;
        var numbering = created ? doc.createNumbering() : doc.getNumbering();
        Map<String, Object> lists = new LinkedHashMap<>();
        List<String> added = new ArrayList<>();
        for (String kind : LIST_KINDS) {
            lists.put(kind, listDefinition(numbering, kind, added));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("created", created);
        out.put("added", added);
        out.put("lists", lists);
        out.put("numCount", numbering.getNums().size());
        return out;
    }

    /** Reuse a fitting definition or add one; the returned numId is what to put on a paragraph. */
    private static Map<String, Object> listDefinition(XWPFNumbering numbering, String kind, List<String> added) {
        Map<String, Object> found = findList(numbering, kind);
        if (found != null) {
            found.put("reused", true);
            return found;
        }
        BigInteger abstractId = addListDefinition(numbering, kind);
        BigInteger numId = firstFreeNumId(numbering);
        // POI's parameter order is (abstractNumID, numID) - swapping them silently produces a
        // num that points at somebody else's definition (seen as an extra list in JsDocxTest).
        numbering.addNum(abstractId, numId);
        added.add(kind + " -> numId " + numId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("numId", numId.toString());
        out.put("abstractId", abstractId.toString());
        out.put("reused", false);
        return out;
    }

    private static Map<String, Object> findList(XWPFNumbering numbering, String kind) {
        for (var num : numbering.getNums()) {
            var ctNum = num.getCTNum();
            if (ctNum == null || ctNum.getNumId() == null || ctNum.getAbstractNumId() == null) {
                continue;
            }
            var abstractNum = numbering.getAbstractNum(bigOf(ctNum.getAbstractNumId().getVal()));
            if (abstractNum == null || abstractNum.getCTAbstractNum() == null) {
                continue;
            }
            if (matchesKind(abstractNum.getCTAbstractNum(), kind)) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("numId", ctNum.getNumId().toString());
                out.put("abstractId", ctNum.getAbstractNumId().getVal().toString());
                return out;
            }
        }
        return null;
    }

    /**
     * Shape test for a list definition: bullets are obvious, "decimal" is any level-0 decimal
     * list and "multilevel" needs a second level whose text refers to the first one
     * ({@code %1.%2}). A multilevel definition therefore also answers "decimal" - the visual
     * result at level 0 is the same.
     */
    private static boolean matchesKind(
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum ct, String kind) {
        var levels = ct.getLvlList();
        if (levels.isEmpty()) {
            return false;
        }
        String level0 = numFmtOf(levels.get(0));
        if ("bullet".equals(kind)) {
            return "bullet".equals(level0);
        }
        if (!"decimal".equals(level0)) {
            return false;
        }
        if ("multilevel".equals(kind)) {
            // needs a compound level text ("%1.%2"); a plain decimal list uses "%2." there
            return levels.size() > 1
                    && lvlTextOfCt(levels.get(1)).contains("%1")
                    && lvlTextOfCt(levels.get(1)).contains("%2");
        }
        return true;
    }

    private static BigInteger addListDefinition(XWPFNumbering numbering, String kind) {
        var ct = org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum.Factory.newInstance();
        ct.setAbstractNumId(BigInteger.valueOf(nextAbstractId(numbering)));
        for (int level = 0; level < 9; level++) {
            var lvl = ct.addNewLvl();
            lvl.setIlvl(BigInteger.valueOf(level));
            lvl.addNewStart().setVal(BigInteger.ONE);
            var fmt = lvl.addNewNumFmt();
            var text = lvl.addNewLvlText();
            if ("bullet".equals(kind)) {
                fmt.setVal(STNumberFormat.BULLET);
                text.setVal(level == 0 ? "•" : (level == 1 ? "◦" : "‣"));
            } else if ("multilevel".equals(kind)) {
                fmt.setVal(STNumberFormat.DECIMAL);
                StringBuilder prefix = new StringBuilder("%1");
                for (int i = 1; i <= level; i++) {
                    prefix.append('.').append('%').append(i + 1);
                }
                text.setVal(prefix.toString());
            } else {
                fmt.setVal(STNumberFormat.DECIMAL);
                text.setVal("%" + (level + 1) + ".");
            }
        }
        var abstractNum = new XWPFAbstractNum(ct, numbering);
        BigInteger id = numbering.addAbstractNum(abstractNum);
        return id != null ? id : numbering.getIdOfAbstractNum(abstractNum);
    }

    private static long nextAbstractId(XWPFNumbering numbering) {
        long max = -1;
        for (var abstractNum : numbering.getAbstractNums()) {
            var ct = abstractNum.getCTAbstractNum();
            if (ct != null) {
                Long candidate = longOf(ct.getAbstractNumId());
                if (candidate != null) {
                    max = Math.max(max, candidate);
                }
            }
        }
        return max + 1;
    }

    private static BigInteger firstFreeNumId(XWPFNumbering numbering) {
        for (int candidate = 1; candidate < 1000; candidate++) {
            BigInteger id = BigInteger.valueOf(candidate);
            if (!numbering.numExist(id)) {
                return id;
            }
        }
        throw new PoiUserRuntimeException("ensureNumbering: no free numId below 1000.");
    }

    private static String numFmtOf(
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTLvl lvl) {
        return lvl == null || !lvl.isSetNumFmt() || lvl.getNumFmt().getVal() == null
                ? null : lvl.getNumFmt().getVal().toString();
    }

    private static String lvlTextOfCt(
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTLvl lvl) {
        if (lvl == null || !lvl.isSetLvlText()) {
            return "";
        }
        String value = strOf(lvl.getLvlText().getVal());
        return value == null ? "" : value;
    }

    private static Long longOf(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Attaches a list to a paragraph and reports the ids actually used (D3).
     * <p>
     * {@code type} is the recommended form and allocates/reuses a definition through
     * {@link #ensureNumbering(String)}; {@code numId} takes precedence for documents that
     * already carry their own numbering. Writing "1." into the text instead is what this
     * function exists to avoid.
     * </p>
     * @param paragraphHandle paragraph handle
     * @param options {@code type} ("bullet"|"decimal"|"multilevel"), {@code level} 0..8,
     *                optional {@code numId}
     * @return JSON-compatible Map with {@code numId}, {@code ilvl}, {@code type},
     *         {@code abstractId}, {@code reused}
     */
    public Map<String, Object> setParagraphNumbering(String paragraphHandle, Map<String, Object> options) {
        requireKnownOptions("setParagraphNumbering", options, "type", "level", "numId");
        XWPFParagraph p = requireParagraph(paragraphHandle);
        String docHandle = paragraphDoc.get(paragraphHandle);
        XWPFDocument doc = docHandle == null ? null : documents.get(docHandle);
        if (doc == null) {
            throw new PoiUserRuntimeException("setParagraphNumbering: the document of paragraph '"
                    + paragraphHandle + "' is closed (invalid handle).");
        }
        String kind = optString("setParagraphNumbering", options, "type", "bullet");
        if (!LIST_KINDS.contains(kind.toLowerCase(Locale.ROOT))) {
            throw new PoiUserRuntimeException("setParagraphNumbering: option 'type' must be one of "
                    + String.join(", ", LIST_KINDS) + ", but was: '" + kind + "'");
        }
        kind = kind.toLowerCase(Locale.ROOT);
        Double levelValue = optNumber("setParagraphNumbering", options, "level", 0, 8, 0.0);
        int level = levelValue.intValue();

        Object explicitNumId = options == null ? null : options.get("numId");
        String numId;
        String abstractId = null;
        boolean reused = true;
        if (explicitNumId != null) {
            BigInteger id = bigOf(explicitNumId);
            if (id == null || id.signum() < 0) {
                throw new PoiUserRuntimeException("setParagraphNumbering: option 'numId' must be a"
                        + " non-negative integer, but was: '" + explicitNumId + "'");
            }
            numId = id.toString();
        } else {
            Map<String, Object> definition = castList(listDefinitionOf(ensureNumberingOf(doc), kind));
            numId = String.valueOf(definition.get("numId"));
            abstractId = definition.get("abstractId") == null ? null : String.valueOf(definition.get("abstractId"));
            reused = Boolean.TRUE.equals(definition.get("reused"));
        }
        p.setNumID(new BigInteger(numId));
        p.setNumILvl(BigInteger.valueOf(level));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("numId", numId);
        out.put("ilvl", level);
        out.put("type", kind);
        out.put("abstractId", abstractId);
        out.put("reused", reused);
        return out;
    }

    /**
     * Switches numbering off for one paragraph. Writes {@code w:numId w:val="0"} - the same
     * form LibreOffice uses, and the only way to stop a numbering that the <em>style</em> of
     * the paragraph provides (removing numPr from the paragraph would just restore it).
     * @param paragraphHandle paragraph handle
     */
    public void removeParagraphNumbering(String paragraphHandle) {
        XWPFParagraph p = requireParagraph(paragraphHandle);
        p.setNumID(BigInteger.ZERO);
        p.setNumILvl(BigInteger.ZERO);
    }

    private static Map<String, Object> castList(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        throw new PoiUserRuntimeException("ensureNumbering: missing definition for a list kind.");
    }

    private static Object listDefinitionOf(Map<String, Object> ensureResult, String kind) {
        Object lists = ensureResult.get("lists");
        if (lists instanceof Map<?, ?> map) {
            return map.get(kind);
        }
        return null;
    }

    // ========================================================================
    // Help / Documentation
    // ========================================================================

    /**
     * Returns a help text describing the docx toolbox API.
     * @return help text
     */
    public String help() {
        return """
                DOCX Toolbox API for .docx access (Apache POI XWPF)
                ====================================================

                All file paths are relative to the project base directory.

                NOTE: docx.save(docHandle, path) MUST be called explicitly to persist
                changes. At the end of the JavaScript run all documents of this tool
                call are automatically closed and unsaved changes are lost.

                --- Document operations ---
                docx.openFile(path)          - Open .docx file, returns docHandle
                docx.createDocument()        - Create a new empty document, returns docHandle
                docx.save(docHandle, path)   - Save document to file
                docx.close(docHandle)        - Close a document

                --- Reading / inspection ---
                docx.getDocumentInfo(doc)    - What is in here: counts, sections (page,
                                               margins in cm, header/footer refs), core props
                docx.getBodyElements(doc)    - True body flow: [{pos, type:
                                               "paragraph"|"table"|"sdt", h?, text, rowCount?}]
                                               (getParagraphs() alone loses tables and SDT)
                docx.getParagraphs(doc)      - List paragraphs [{h, text, runs:[{h, text}]}]
                docx.getParagraphs(doc, {props:true}) - the same plus style, alignment,
                                               indents (cm), spacing (pt), numId/ilvl/numFmt,
                                               pageBreak per paragraph
                docx.getParagraphProps(par)  - One paragraph in detail (same keys, plus
                                               styleName, outlineLvl, numIdSource, breaks,
                                               fields, hyperlinks, drawings, vmlShapes)
                docx.getRuns(par)            - Run handles of a paragraph (the run created
                                               by createParagraph is only reachable here)
                docx.getRunProps(run)        - {text, bold, italic, fontSizePt, fontName,
                                               color, underline, shading, style, breaks}
                docx.getFields(par)          - Fields of a paragraph, both spellings:
                                               [{instr, cached}] (w:fldChar AND w:fldSimple)
                docx.getStylesInfo(doc)      - [{styleId, name, type, basedOn, outlineLvl,
                                               fontSizePt}] - id and name differ per author
                docx.getNumberingInfo(doc)   - [{numId, abstractId, levels:[{ilvl, numFmt,
                                               lvlText}]}] - ids are taken in foreign files
                docx.getHeadersFooters(doc)  - [{kind, type, part, text, fields:[...]}] for
                                               default/first/even; parts nobody references
                                               are listed with type "unreferenced". Section
                                               margins matter: header 0 cm hides a header.
                docx.getPictures(doc)        - [{fileName, extension, bytes, widthPx,
                                               heightPx}] + inlinePlacements /
                                               anchorPlacements / vmlShapes (counts)
                docx.getShapes(doc)          - Every drawing of the body paragraphs:
                                               [{paragraphPos, paragraphText,
                                               mode: "inline"|"anchor", extentCm:{width,height},
                                               id, name, graphicUri, leftCm?, topCm?,
                                               shapes:[{kind:"shape"|"group", prst, id, name,
                                               leftCm, topCm, widthCm, heightCm, rotationDeg,
                                               flipH, flipV, fillColor, lineColor, lineWidthPt,
                                               lineDash, text?, shapes:[…]}]}]
                                               (walked with an XmlCursor, so shapes hidden in
                                               LibreOffice's mc:AlternateContent are found too.
                                               One placement of createDrawing reads back as ONE
                                               group - the canvas - with the shapes inside it)
                docx.getHyperlinks(doc)      - [{paragraphText, text, anchor?, url?}]
                docx.getTables(doc)          - List tables [{index, h, rowCount, colCount,
                                               colCountMax, gridCols, style, rows,
                                               cellBorders, cells:[[text,colSpan,vMerge,
                                               merged,shading,align]]}]
                                               (merged cells are reported, not silently short;
                                               cell handles come from getCellProps)
                docx.getDocumentText(doc)    - Body paragraph text only: NO table cells, NO
                                               table of contents, NO list numbers
                docx.getDocumentText(doc, {tables:true, sdt:true}) - include those as well
                docx.getParagraphText(para)  - Text of a paragraph

                --- Writing (paragraphs / runs) ---
                docx.createParagraph(doc,t)  - Append a paragraph with text, returns paragraphHandle
                docx.addRun(paraHandle, t)   - Append a run to a paragraph, returns runHandle
                docx.setRunText(run, t)      - Set run text
                docx.setRunBold(run, b)      - Set bold (true/false)
                docx.setRunItalic(run, i)    - Set italic (true/false)
                docx.setRunFontSize(run, s)  - Font size in points, 1.0-4000.0, half
                                               steps kept (9.5 is valid, written as w:sz 19)
                docx.setRunFontName(run, fn) - Set font name (e.g. "Arial")
                docx.setRunFontColor(run, c) - Set font color (hex e.g. "FF0000")

                --- Writing (tables) ---
                docx.createTable(doc, rows, cols, opts) -> tableHandle "t-n"
                       opts: {style, borders: "single"|"double"|"none" (default single),
                              widths: ["4.5cm"|"25%"|"auto", ...], header: ["Nr","Modul",...],
                              align: ["left"|"center"|"right"|"both", ...]}
                       widths/align must have exactly as many entries as columns; borders are
                       set per cell so a document without a styles part still shows a grid.
                docx.setCellText(doc, tabRef, r, c, text, opts)
                       tabRef = table index (0,1,...) or "t-n" handle
                       opts: {bold, italic, fontSizePt, fontName, color, align,
                              valign: "top"|"center"|"bottom", shading: "D9E2F3"}
                       Replaces the cell content with one paragraph.
                docx.getCellProps(doc, tabRef, r, c) -> {h, text, colSpan, vMerge, merged,
                       shading, align, runs:[{h,text,bold,fontSizePt,...}]}
                       (cell width is not reported yet - see docs/js/docx.md §1.3)
                docx.addRow(doc, tabRef, arr)   - Append a row. Formatting is cloned from the
                       first row WITHOUT merged cells; gridSpan/vMerge/hMerge/shd are never
                       copied and too many texts is an error (was: silent truncation).
                docx.removeRow(doc, tabIdx, r)  - Remove a row from a table
                docx.setRowFontColor(doc,ti,r,hex)- Set font color of all runs in a table row
                docx.setCellFontColor(doc,ti,r,c,hex)- Set font color of a single cell

                --- Writing (paragraph formatting) ---
                docx.setParagraphStyle(par, style) - style id OR display name ("Heading1",
                       "heading 2", "Überschrift 1"); resolution per docs/js/docx.md D4.
                       Unknown style -> error listing the styles of this document. A style
                       that does not exist is never silently ignored.
                docx.setParagraphAlignment(par, a) - "left"|"right"|"center"|"both"
                docx.setParagraphIndent(par, {leftCm, rightCm, firstLineCm, hangingCm})
                docx.setParagraphSpacing(par, {beforePt, afterPt, line, lineRule})
                       line is a factor with lineRule "auto" (1.0 = single), otherwise points
                docx.listStyleKeys(doc)        - Which style names would resolve (D4)
                docx.ensureStyles(doc)         - Build the styles part of a new document
                       (Normal, Heading1-3 with outlineLvl, Title, Subtitle, ListParagraph,
                       Caption, TableGrid). Existing styles are never overwritten.
                       setParagraphStyle calls it automatically when styles.xml is missing.
                docx.ensureNumbering(doc)      - {lists: {bullet: {numId, abstractId, reused},
                       decimal: {...}, multilevel: {...}}} - reuses a fitting definition of a
                       foreign document, otherwise allocates the first FREE numId (D3: Ribbeck
                       occupies 1-3, LibreOffice_Fixture1 occupies 1-5)
                docx.setParagraphNumbering(par, {type, level}) -> {numId, ilvl, type, abstractId, reused}
                       type: "bullet"|"decimal"|"multilevel", level 0..8; returns the ids it
                       used, so a list can be found again after reopening
                docx.removeParagraphNumbering(par) - writes w:numId 0 (LibreOffice's form),
                       which also switches off a numbering that comes from the style

                --- Writing (drawings: vector sketches) ---
                docx.createDrawing(par, {widthCm, heightCm, name?}) -> "dwg-n"
                       Opens an inline drawing (wp:inline, flows with the text) in a new run of
                       the paragraph. Its content is one wpg:wgp group, because a w:drawing
                       holds exactly one element and overlapping circles need one canvas.
                docx.addShape(target, {shape, widthCm, heightCm, leftCm?, topCm?, fillColor?,
                       fill: "none"?, lineColor?, lineWidthPt?, lineDash?, rotationDeg?,
                       flipH?, flipV?}) -> "shp-n"
                       Ein Form hat bewusst kein name-Option: wps:cNvSpPr ist der Typ für
                       txBox/spLocks, das Datei-Format sieht dort kein id/name vor. Namen tragen
                       das Placement (createDrawing) und Gruppen (addShapeGroup).
                       target is the drawing handle "dwg-n" or a group handle "shp-n".
                       shape is an OOXML preset (ellipse, rect, roundRect, line, triangle,
                       rightTriangle, diamond, parallelogram, trapezoid, pentagon, hexagon,
                       octagon, star4, star5, heart, smileyFace, pie, blockArc, arc, donut,
                       plus, minus, leftArrow, rightArrow, upArrow, downArrow, leftRightArrow,
                       cloud) - note "ellipse", not the pptx name "OVAL".
                       All lengths in cm, line width in pt, rotation in degrees.
                docx.addShapeGroup(target, {widthCm, heightCm, leftCm?, topCm?, name?}) -> "shp-n"
                       A nested coordinate system: add shapes to it in its own centimetres.
                docx.createShapes(par, {widthCm, heightCm, name?}, [ {..}, {..} ]) -> "dwg-n"
                       The whole sketch in one call; the array holds addShape options.
                docx.setShapeFill(shp, "FFC7CE"|"none")
                docx.setShapeLine(shp, {color?, widthPt?, dash?} or {none: true})
                       dash: solid, dot, dash, lgDash, dashDot, sysDot, sysDash
                docx.removeShape(shp)        - Removes a shape or a group with its content
                Formentext (wps:txbx) wird gelesen, aber nicht geschrieben: die Reihenfolge ist
                inzwischen aus dem Typsystem belegt (txbx vor bodyPr), doch kein Fixture-Fall
                deckt den Inhalt (w:txbxContent) ab - nachtraglich, wenn eine Referenzdatei da ist.

                Typical workflow (find-then-edit):
                1. var info = docx.getDocumentInfo(doc);          // what am I looking at?
                2. var paras = docx.getParagraphs(doc, {props:true});
                3. var ph = paras.paragraphs[2].h;
                4. docx.addRun(ph, "Neu");                        // or edit an existing run
                5. docx.save(doc, "out.docx");

                Not yet available (planned, see docs/js/docx.md): headers/footers, section
                breaks, cell merging as setters (gridSpan/vMerge are readable already),
                insertParagraph/insertRow, pictures, shape text, floating drawings
                (wp:anchor with wrap - only wp:inline is written so far), TOC, md2docx.
                Note: setParagraphStyle creates the styles part on demand, so a brand-new
                document can already use Heading1/Heading2 (outline levels included).
                """ + PoiJsArgs.DOCX.helpNotes();
    }

    // ========================================================================
    // Drawings: vector sketches inside a paragraph (docs/js/docx.md §1.4)
    // ========================================================================
    //
    // wps:wsp and wpg:wgp have NO compiled schema type - not in poi-ooxml-lite and not in
    // poi-ooxml-full either (measured 2026-10-15: both JARs contain 0 classes for the
    // http://schemas.microsoft.com/office/word/2010/wordprocessing{Shape,Group} namespaces;
    // the similarly named CTWordprocessingShape of the full JAR belongs to dml-wordprocessing-
    // Drawing.xsd). The a:graphicData element is an xsd:any, so the elements are written as
    // raw XML through an XmlCursor - the same route POI itself takes for charts. The model
    // below keeps every shape in cm so the XML is regenerated from one source of truth and
    // getShapes() can report what was written.

    /** One node of a drawing model: a shape or a (nested) group, lengths in cm. */
    private static final class ShapeNode {
        private String handle;
        private Drawing owner;
        private ShapeNode parent;
        private boolean group;
        private long id;
        private String name;
        private String prst;                  // a:prstGeom/@prst, shapes only
        private double leftCm;
        private double topCm;
        private double widthCm;
        private double heightCm;
        private Double rotationDeg;
        private Boolean flipH;
        private Boolean flipV;
        private String fillColor;             // null = leave to theme, "none" = a:noFill
        private String lineColor;             // null = no line at all
        private Double lineWidthPt;
        private String lineDash;
        private final List<ShapeNode> children = new ArrayList<>();   // groups only
    }

    /** A drawing: the run that carries it, plus the canvas model behind it. */
    private static final class Drawing {
        private String handle;
        private String paragraphHandle;
        private XWPFRun run;
        private double widthCm;
        private double heightCm;
        private long id;
        private String name;
        private ShapeNode root;                                  // the outermost wpg:wgp
        private final List<ShapeNode> nodes = new ArrayList<>();  // every node, for handles
    }

    /**
     * Opens an inline drawing (a vector canvas that flows with the text) in the paragraph.
     * <p>
     * The drawing is one {@code w:drawing}/{@code wp:inline} inside a new run of the
     * paragraph, and its content is one {@code wpg:wgp} group: shapes of one sketch overlap
     * inside that group, while separate placements inside one paragraph would be laid out
     * next to each other. Shapes are added with {@link #addShape(String, Map)}.
     * </p>
     * @param paragraphHandle paragraph handle ("p-n")
     * @param options         {@code widthCm} and {@code heightCm} (required), {@code name?}
     * @return drawing handle "dwg-n"
     */
    public String createDrawing(String paragraphHandle, Map<String, Object> options) {
        String fn = "createDrawing";
        XWPFParagraph paragraph = requireParagraph(paragraphHandle);
        String docHandle = paragraphDoc.get(paragraphHandle);
        if (docHandle == null) {
            throw new PoiUserRuntimeException(fn + ": paragraph " + paragraphHandle
                    + " does not belong to a known document");
        }
        Map<String, Object> opts = options == null ? Map.of() : options;
        requireKnownOptions(fn, opts, "widthCm", "heightCm", "name");
        double widthCm = requireNumber(fn, opts, "widthCm", 0.01, MAX_DRAWING_CM);
        double heightCm = requireNumber(fn, opts, "heightCm", 0.01, MAX_DRAWING_CM);
        String name = optString(fn, opts, "name", null);

        // The run stays empty until renderDrawing() fills it - a drawing is always written
        // from the model, never patched (docs/js/docx.md §1.4)
        XWPFRun run = paragraph.createRun();

        Drawing drawing = new Drawing();
        drawing.handle = generateHandle("dwg");
        drawing.paragraphHandle = paragraphHandle;
        drawing.run = run;
        drawing.widthCm = widthCm;
        drawing.heightCm = heightCm;
        drawing.id = nextShapeId(docHandle);
        drawing.name = name == null ? "Skizze " + drawing.id : name;

        drawing.root = new ShapeNode();
        drawing.root.group = true;
        drawing.root.owner = drawing;
        drawing.root.id = drawing.id;
        drawing.root.name = drawing.name;
        drawing.root.widthCm = widthCm;
        drawing.root.heightCm = heightCm;

        drawings.put(drawing.handle, drawing);
        drawingDoc.put(drawing.handle, docHandle);
        renderDrawing(drawing);
        LOG.info("Created drawing {} ({} x {} cm) in {}", drawing.handle, widthCm, heightCm,
                paragraphHandle);
        return drawing.handle;
    }

    /**
     * Adds one shape to a drawing or to a nested group.
     * @param targetHandle drawing handle "dwg-n" or the handle of a group ("shp-n")
     * @param options      {@code shape} (a {@link #SHAPE_GEOMETRIES} value, default "rect"),
     *                     {@code widthCm}/{@code heightCm} (required), {@code leftCm}/{@code topCm}
     *                     (default 0), {@code rotationDeg}, {@code flipH}/{@code flipV},
     *                     {@code fillColor} (hex) or {@code fill: "none"}, {@code lineColor},
     *                     {@code lineWidthPt}, {@code lineDash}. No {@code name}: the file has no
     *                     slot for a shape's identity (docs/js/docx.md §1.4)
     * @return shape handle "shp-n"
     */
    public String addShape(String targetHandle, Map<String, Object> options) {
        String fn = "addShape";
        Drawing drawing = requireDrawingOwner(targetHandle);
        ShapeNode parent = requireGroup(fn, targetHandle);
        ShapeNode node = shapeFromOptions(fn, drawing, parent,
                options == null ? Map.of() : options);
        renderDrawing(drawing);
        return node.handle;
    }

    /**
     * Adds a nested group: shapes added to it share its coordinate system, so a whole part
     * of a sketch can be moved by changing one {@code leftCm}/{@code topCm}.
     * @return group handle "shp-n" (use it as the target of {@link #addShape})
     */
    public String addShapeGroup(String targetHandle, Map<String, Object> options) {
        String fn = "addShapeGroup";
        Drawing drawing = requireDrawingOwner(targetHandle);
        ShapeNode parent = requireGroup(fn, targetHandle);
        Map<String, Object> opts = options == null ? Map.of() : options;
        requireKnownOptions(fn, opts, "leftCm", "topCm", "widthCm", "heightCm", "name");
        ShapeNode node = new ShapeNode();
        node.group = true;
        node.handle = generateHandle("shp");
        node.owner = drawing;
        node.parent = parent;
        node.leftCm = optNumber(fn, opts, "leftCm", -MAX_OFFSET_CM, MAX_OFFSET_CM, 0.0);
        node.topCm = optNumber(fn, opts, "topCm", -MAX_OFFSET_CM, MAX_OFFSET_CM, 0.0);
        node.widthCm = requireNumber(fn, opts, "widthCm", 0.01, MAX_DRAWING_CM);
        node.heightCm = requireNumber(fn, opts, "heightCm", 0.01, MAX_DRAWING_CM);
        node.id = nextShapeId(drawingDoc.get(drawing.handle));
        node.name = optString(fn, opts, "name", "Gruppe " + node.id);
        attach(drawing, parent, node);
        renderDrawing(drawing);
        return node.handle;
    }

    /**
     * Convenience builder: opens a drawing and fills it with shapes in one call.
     * @param paragraphHandle paragraph handle "p-n"
     * @param options         {@code widthCm}/{@code heightCm} (required), {@code name?}
     * @param shapeOptions    one options object per shape, same keys as {@link #addShape}
     * @return drawing handle "dwg-n"
     */
    public String createShapes(String paragraphHandle, Map<String, Object> options,
                              List<Map<String, Object>> shapeOptions) {
        String fn = "createShapes";
        String handle = createDrawing(paragraphHandle, options);
        Drawing drawing = drawings.get(handle);
        if (shapeOptions != null) {
            for (int i = 0; i < shapeOptions.size(); i++) {
                Map<String, Object> entry = shapeOptions.get(i);
                if (entry == null) {
                    throw new PoiUserRuntimeException(fn + ": shapes[" + i + "] is not an object");
                }
                shapeFromOptions(fn, drawing, drawing.root, entry);
            }
        }
        renderDrawing(drawing);
        return handle;
    }

    /** Sets the fill of a shape: a hex colour or "none" ({@code a:noFill}). */
    public void setShapeFill(String shapeHandle, String color) {
        String fn = "setShapeFill";
        ShapeNode node = requirePlainShape(fn, shapeHandle);
        node.fillColor = "none".equalsIgnoreCase(nullToEmpty(color))
                ? "none" : requireHex(fn, "color", color);
        renderDrawing(node.owner);
    }

    /**
     * Sets the outline of a shape.
     * @param options {@code color} (hex), {@code widthPt} (0-500), {@code dash}
     *                (a {@link #LINE_DASHES} value) or {@code none: true} to drop the line
     */
    public void setShapeLine(String shapeHandle, Map<String, Object> options) {
        String fn = "setShapeLine";
        ShapeNode node = requirePlainShape(fn, shapeHandle);
        Map<String, Object> opts = options == null ? Map.of() : options;
        requireKnownOptions(fn, opts, "color", "widthPt", "dash", "none");
        if (Boolean.TRUE.equals(optBoolean(fn, opts, "none", Boolean.FALSE))) {
            node.lineColor = null;
            node.lineWidthPt = null;
            node.lineDash = null;
        } else {
            String color = optString(fn, opts, "color", node.lineColor);
            if (color != null) {
                node.lineColor = requireHex(fn, "color", color);
            }
            node.lineWidthPt = optNumber(fn, opts, "widthPt", 0.0, MAX_LINE_WIDTH_PT,
                    node.lineWidthPt);
            String dash = optString(fn, opts, "dash", node.lineDash);
            if (dash != null) {
                node.lineDash = parseDash(fn, dash);
            }
        }
        renderDrawing(node.owner);
    }

    /** Removes a shape or a group (with everything inside it) from its drawing. */
    public void removeShape(String shapeHandle) {
        String fn = "removeShape";
        ShapeNode node = requireShape(fn, shapeHandle);
        ShapeNode parent = node.parent;
        if (parent == null) {
            throw new PoiUserRuntimeException(fn + ": " + shapeHandle + " is the canvas itself "
                    + "and cannot be removed (remove the drawing by deleting its paragraph)");
        }
        List<ShapeNode> gone = new ArrayList<>();
        collectNodes(node, gone);
        parent.children.remove(node);
        node.owner.nodes.removeAll(gone);
        shapes.keySet().removeAll(handlesOf(gone));
        renderDrawing(node.owner);
    }

    /**
     * Reads every drawing of the body paragraphs: placement, extent and the shapes inside -
     * the counterpart of the setters above, and the reason {@code docx.getPictures()} could
     * only counts shapes before (docs/js/docx.md §1.4, "Form, nicht lesbar").
     * <p>
     * Walked with an {@link XmlCursor} over the paragraph XML, so a drawing hidden inside
     * {@code mc:AlternateContent}/{@code mc:Choice} (LibreOffice) is found as well. Lengths
     * come back in cm, {@code prst} uses the same vocabulary as the {@code shape} option.
     * A drawing made by {@link #createDrawing} holds one {@code wpg:wgp} canvas, so its
     * {@code shapes} list contains that single group and the real shapes one level deeper.
     * Tables, headers and footers are not scanned.
     * </p>
     * @param docHandle document handle
     * @return JSON-compatible Map with key "placements"
     */
    public Map<String, Object> getShapes(String docHandle) {
        XWPFDocument doc = requireDocument(docHandle);
        List<Map<String, Object>> placements = new ArrayList<>();
        List<XWPFParagraph> bodyParagraphs = doc.getParagraphs();
        for (int i = 0; i < bodyParagraphs.size(); i++) {
            XWPFParagraph paragraph = bodyParagraphs.get(i);
            for (XmlNode drawingNode : drawingNodes(paragraph.getCTP())) {
                Map<String, Object> entry = placementOf(drawingNode);
                entry.put("paragraphPos", i);
                String text = paragraph.getText();
                entry.put("paragraphText", text == null || text.isEmpty() ? null : text);
                placements.add(entry);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("document", docHandle);
        result.put("count", placements.size());
        result.put("placements", placements);
        result.put("scope", "body paragraphs (tables, headers and footers are not scanned)");
        return result;
    }

    // ------------------------------------------------------------------------
    // Drawing model helpers
    // ------------------------------------------------------------------------

    /** Builds one shape node from an options object and attaches it (no rendering). */
    private ShapeNode shapeFromOptions(String fn, Drawing drawing, ShapeNode parent,
                                       Map<String, Object> opts) {
        requireKnownOptions(fn, opts, "shape", "leftCm", "topCm", "widthCm", "heightCm",
                "rotationDeg", "flipH", "flipV", "fillColor", "fill", "lineColor", "lineWidthPt",
                "lineDash");
        ShapeNode node = new ShapeNode();
        node.group = false;
        node.handle = generateHandle("shp");
        node.owner = drawing;
        node.parent = parent;
        node.prst = parseGeometry(fn, optString(fn, opts, "shape", "rect"));
        node.leftCm = optNumber(fn, opts, "leftCm", -MAX_OFFSET_CM, MAX_OFFSET_CM, 0.0);
        node.topCm = optNumber(fn, opts, "topCm", -MAX_OFFSET_CM, MAX_OFFSET_CM, 0.0);
        node.widthCm = requireNumber(fn, opts, "widthCm", 0.01, MAX_DRAWING_CM);
        node.heightCm = requireNumber(fn, opts, "heightCm", 0.01, MAX_DRAWING_CM);
        node.rotationDeg = optNumber(fn, opts, "rotationDeg", -MAX_ROTATION_DEG, MAX_ROTATION_DEG,
                null);
        node.flipH = optBoolean(fn, opts, "flipH", null);
        node.flipV = optBoolean(fn, opts, "flipV", null);
        String fill = optString(fn, opts, "fill", null);
        if (fill != null) {
            if (!"none".equalsIgnoreCase(fill)) {
                throw new PoiUserRuntimeException(fn + ": option 'fill' only knows \"none\" "
                        + "(a colour goes into 'fillColor'), but was: '" + fill + "'");
            }
            node.fillColor = "none";
        } else {
            String color = optString(fn, opts, "fillColor", null);
            if (color != null) {
                node.fillColor = requireHex(fn, "fillColor", color);
            }
        }
        String lineColor = optString(fn, opts, "lineColor", null);
        if (lineColor != null) {
            node.lineColor = requireHex(fn, "lineColor", lineColor);
        }
        node.lineWidthPt = optNumber(fn, opts, "lineWidthPt", 0.0, MAX_LINE_WIDTH_PT, null);
        String dash = optString(fn, opts, "lineDash", null);
        if (dash != null) {
            node.lineDash = parseDash(fn, dash);
        }
        node.id = nextShapeId(drawingDoc.get(drawing.handle));
        // no name on a shape: wps:cNvSpPr is the txBox/spLocks type, so the file has no slot
        // for it - see the table in docs/js/docx.md §1.4
        node.name = node.prst + " " + node.id;
        attach(drawing, parent, node);
        return node;
    }

    /** Registers a node in its parent, the drawing and the handle map. */
    private void attach(Drawing drawing, ShapeNode parent, ShapeNode node) {
        parent.children.add(node);
        drawing.nodes.add(node);
        shapes.put(node.handle, node);
    }

    private Drawing requireDrawingOwner(String handle) {
        Drawing drawing = drawings.get(handle);
        if (drawing != null) {
            return drawing;
        }
        ShapeNode node = shapes.get(handle);
        if (node != null) {
            return node.owner;
        }
        throw new PoiUserRuntimeException("Drawing not found (invalid handle): " + handle
                + ". Drawing handles come from docx.createDrawing(...)/docx.createShapes(...),"
                + " group and shape handles from docx.addShape(...)/docx.addShapeGroup(...).");
    }

    /** The group a new child goes into: the canvas of a drawing, or a nested group. */
    private ShapeNode requireGroup(String fn, String handle) {
        Drawing drawing = drawings.get(handle);
        if (drawing != null) {
            return drawing.root;
        }
        ShapeNode node = shapes.get(handle);
        if (node == null) {
            throw new PoiUserRuntimeException(fn + ": unknown handle: " + handle
                    + " (expected a drawing handle \"dwg-n\" or a group handle \"shp-n\")");
        }
        if (!node.group) {
            throw new PoiUserRuntimeException(fn + ": " + handle + " is a shape and cannot hold"
                    + " children - pass the drawing handle or the handle of a group");
        }
        return node;
    }

    private ShapeNode requireShape(String fn, String handle) {
        ShapeNode node = shapes.get(handle);
        if (node == null) {
            throw new PoiUserRuntimeException(fn + ": unknown shape handle: " + handle);
        }
        return node;
    }

    /** Fill and line belong to a shape - a group only carries its own box. */
    private ShapeNode requirePlainShape(String fn, String handle) {
        ShapeNode node = requireShape(fn, handle);
        if (node.group) {
            throw new PoiUserRuntimeException(fn + ": " + handle + " is a group; fill, line and"
                    + " geometry belong to the shapes inside it");
        }
        return node;
    }

    private static void collectNodes(ShapeNode node, List<ShapeNode> into) {
        into.add(node);
        for (ShapeNode child : node.children) {
            collectNodes(child, into);
        }
    }

    private static List<String> handlesOf(List<ShapeNode> nodes) {
        List<String> handles = new ArrayList<>();
        for (ShapeNode node : nodes) {
            handles.add(node.handle);
        }
        return handles;
    }

    /**
     * Hands out the next unique drawing id of a document. Word repairs a file whose
     * {@code wp:docPr/@id} values collide, so the counter never reuses one - the first call
     * reads the highest id already present (a foreign document may already carry shapes).
     */
    private long nextShapeId(String docHandle) {
        XWPFDocument doc = requireDocument(docHandle);
        long high = shapeIdHighWater.computeIfAbsent(docHandle, key -> maxShapeId(doc));
        long next = high + 1;
        shapeIdHighWater.put(docHandle, next);
        return next;
    }

    /** Highest numeric {@code id} attribute anywhere in the document body (0 when none). */
    private static long maxShapeId(XWPFDocument doc) {
        long max = 0;
        XmlCursor cursor = doc.getDocument().newCursor();
        try {
            XmlCursor.TokenType token = cursor.toFirstContentToken();
            while (token != null && !token.isNone()) {
                if (token.isStart()) {
                    // unqualified attribute, so an empty namespace is what the schema declares
                    String id = cursor.getAttributeText(QNAME_ID);
                    if (id != null) {
                        try {
                            max = Math.max(max, Long.parseLong(id.trim()));
                        } catch (NumberFormatException ignored) {
                            // v:oval uses names like "shape_0"; only numeric ids compete here
                        }
                    }
                }
                token = cursor.toNextToken();
            }
        } finally {
            cursor.dispose();
        }
        return max;
    }

    // ------------------------------------------------------------------------
    // Writing the raw wpg/wps XML
    // ------------------------------------------------------------------------

    /**
     * Writes the {@code w:drawing} of a canvas from its model.
     * <p>
     * The drawing is rebuilt on every change instead of patched: neither {@code wpg} nor
     * {@code wps} has a compiled schema type in any POI schema JAR (docs/js/docx.md §1.4), so
     * there is nothing to patch with, and the one call that could replace content
     * ({@code XmlObject.set}) would take {@code wp:extent} and {@code wp:docPr} along with it.
     * The sequence is POI's own from {@code XWPFRun.addPicture}: graphic as parsed string,
     * then distances, docPr and extent - XmlBeans keeps the children in schema order.
     * </p>
     */
    private void renderDrawing(Drawing drawing) {
        CTR ctr = drawing.run.getCTR();
        for (int i = ctr.sizeOfDrawingArray() - 1; i >= 0; i--) {
            ctr.removeDrawing(i);
        }
        String xml = "<a:graphic xmlns:a=\"" + NS_A + "\">"
                + "<a:graphicData uri=\"" + NS_WPG + "\">"
                + "<wpg:wgp xmlns:wpg=\"" + NS_WPG + "\" xmlns:wps=\"" + NS_WPS
                + "\" xmlns:w=\"" + NS_W + "\">"
                + groupContent(drawing.root) + "</wpg:wgp>"
                + "</a:graphicData>"
                + "</a:graphic>";
        CTInline inline;
        try {
            org.w3c.dom.Document parsed = DocumentHelper.readDocument(
                    new InputSource(new StringReader(xml)));
            inline = ctr.addNewDrawing().addNewInline();
            inline.set(XmlToken.Factory.parse(parsed.getDocumentElement(),
                    POIXMLTypeLoader.DEFAULT_XML_OPTIONS));
        } catch (SAXException | IOException | XmlException e) {
            throw new PoiUserRuntimeException("renderDrawing: the generated drawing XML could not"
                    + " be parsed (internal error, please report): " + e.getMessage(), e);
        }
        inline.setDistT(0);
        inline.setDistB(0);
        inline.setDistL(0);
        inline.setDistR(0);
        CTNonVisualDrawingProps docPr = inline.addNewDocPr();
        docPr.setId(drawing.id);
        docPr.setName(drawing.name);
        CTPositiveSize2D extent = inline.addNewExtent();
        extent.setCx(emu(drawing.widthCm));
        extent.setCy(emu(drawing.heightCm));
    }

    /** {@code wpg:nvGpPr} + {@code wpg:grpSpPr} + children, as the schema orders them. */
    private static String groupContent(ShapeNode group) {
        StringBuilder xml = new StringBuilder();
        xml.append("<wpg:nvGpPr>")
                .append("<wpg:cNvPr id=\"").append(group.id)
                .append("\" name=\"").append(xmlEscape(group.name)).append("\"/>")
                .append("<wpg:cNvGrpSpPr/>")
                .append("</wpg:nvGpPr>");
        xml.append("<wpg:grpSpPr>").append(xfrm(group, true)).append("</wpg:grpSpPr>");
        for (ShapeNode child : group.children) {
            xml.append(child.group ? "<wpg:wgp>" + groupContent(child) + "</wpg:wgp>"
                    : shapeXml(child));
        }
        return xml.toString();
    }

    /**
     * One shape. Element order and content follow what LibreOffice writes and Word reads
     * (measured in LibreOffice_Fixture1.docx): {@code cNvSpPr, spPr, style, bodyPr}; the
     * {@code idx="0"} references in {@code wps:style} keep Word from overriding the explicit
     * fill and line with theme values.
     * <p>
     * {@code wps:cNvSpPr} is written <em>empty on purpose</em>. Two schemas disagree about it:
     * POI's compiled {@code CT_WordprocessingShape} (source {@code dml-wordprocessingDrawing.xsd})
     * offers a choice of {@code cNvPr | cNvSpPr | cNvCnPr} and types {@code cNvSpPr} as
     * {@code CT_NonVisualDrawingShapeProps} ({@code txBox}, {@code spLocks} - no id, no name),
     * while the Word shape flavour may carry identity there. Attributes would be wrong under the
     * first reading and optional under the second, so leaving it out is the only form that is
     * valid in both - and it is exactly what LibreOffice emits. Identity lives in
     * {@code wp:docPr} (the placement) and in {@code wpg:cNvPr} (the canvas), which are
     * undisputed in every reading; {@link #getShapes(String)} reports a {@code cNvPr} child or
     * {@code cNvSpPr} attributes, whichever a file actually uses.
     * </p>
     */
    private static String shapeXml(ShapeNode node) {
        StringBuilder xml = new StringBuilder();
        xml.append("<wps:wsp>")
                .append("<wps:cNvSpPr/>")
                .append("<wps:spPr>")
                .append(xfrm(node, false))
                .append("<a:prstGeom prst=\"").append(node.prst).append("\"><a:avLst/></a:prstGeom>");
        if ("none".equals(node.fillColor)) {
            xml.append("<a:noFill/>");
        } else if (node.fillColor != null) {
            xml.append("<a:solidFill><a:srgbClr val=\"").append(node.fillColor).append("\"/></a:solidFill>");
        }
        if (node.lineColor != null || node.lineWidthPt != null) {
            xml.append("<a:ln");
            if (node.lineWidthPt != null) {
                xml.append(" w=\"").append(Math.round(node.lineWidthPt * EMU_PER_PT)).append("\"");
            }
            xml.append(">");
            if (node.lineColor == null) {
                xml.append("<a:noFill/>");
            } else {
                xml.append("<a:solidFill><a:srgbClr val=\"").append(node.lineColor)
                        .append("\"/></a:solidFill>");
                if (node.lineDash != null && !"solid".equals(node.lineDash)) {
                    xml.append("<a:prstDash val=\"").append(node.lineDash).append("\"/>");
                }
            }
            xml.append("</a:ln>");
        }
        xml.append("</wps:spPr>")
                .append("<wps:style>")
                .append("<a:lnRef idx=\"0\"/><a:fillRef idx=\"0\"/>")
                .append("<a:effectRef idx=\"0\"/><a:fontRef idx=\"minor\"/>")
                .append("</wps:style>")
                .append("<wps:bodyPr/>")
                .append("</wps:wsp>");
        return xml.toString();
    }

    /** {@code a:xfrm} of a node; groups also get the child space (1:1 to the box). */
    private static String xfrm(ShapeNode node, boolean group) {
        StringBuilder xml = new StringBuilder("<a:xfrm");
        if (node.rotationDeg != null && Math.abs(node.rotationDeg) > 1e-9) {
            xml.append(" rot=\"").append(Math.round(node.rotationDeg * 60000)).append("\"");
        }
        if (Boolean.TRUE.equals(node.flipH)) {
            xml.append(" flipH=\"1\"");
        }
        if (Boolean.TRUE.equals(node.flipV)) {
            xml.append(" flipV=\"1\"");
        }
        xml.append("><a:off x=\"").append(emu(node.leftCm)).append("\" y=\"").append(emu(node.topCm))
                .append("\"/>");
        xml.append("<a:ext cx=\"").append(emu(node.widthCm)).append("\" cy=\"")
                .append(emu(node.heightCm)).append("\"/>");
        if (group) {
            // chOff/chExt equal to off/ext: the children keep centimetres 1:1
            xml.append("<a:chOff x=\"").append(emu(node.leftCm)).append("\" y=\"")
                    .append(emu(node.topCm)).append("\"/>");
            xml.append("<a:chExt cx=\"").append(emu(node.widthCm)).append("\" cy=\"")
                    .append(emu(node.heightCm)).append("\"/>");
        }
        return xml.append("</a:xfrm>").toString();
    }

    private static long emu(double cm) {
        return Math.round(cm * EMU_PER_CM);
    }

    private static String parseGeometry(String fn, String value) {
        String prst = value == null ? "" : value.trim();
        for (String known : SHAPE_GEOMETRIES) {
            if (known.equalsIgnoreCase(prst)) {
                return known;
            }
        }
        throw new PoiUserRuntimeException(fn + ": unknown shape '" + value + "' (known: "
                + String.join(", ", SHAPE_GEOMETRIES) + "). See docx.help().");
    }

    private static String parseDash(String fn, String value) {
        String dash = value == null ? "" : value.trim();
        for (String known : LINE_DASHES) {
            if (known.equalsIgnoreCase(dash)) {
                return known;
            }
        }
        throw new PoiUserRuntimeException(fn + ": unknown lineDash '" + value + "' (known: "
                + String.join(", ", LINE_DASHES) + ")");
    }

    private static Double requireNumber(String fn, Map<String, Object> options, String key,
                                        double min, double max) {
        Double value = optNumber(fn, options, key, min, max, null);
        if (value == null) {
            throw new PoiUserRuntimeException(fn + ": option '" + key + "' is required "
                    + "(a number between " + min + " and " + max + ", in cm). See docx.help().");
        }
        return value;
    }

    private static String xmlEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    // ------------------------------------------------------------------------
    // Reading drawings back (XmlCursor, so mc:AlternateContent is no obstacle)
    // ------------------------------------------------------------------------

    /** Namespace of {@code w:} - needed to recognise {@code w:drawing}. */
    private static final String NS_W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    /** The unqualified {@code id} attribute (XmlCursor has no index-based attribute access). */
    private static final QName QNAME_ID = new QName("id");

    /** A token-level snapshot of an element: attributes, children, text. */
    private static final class XmlNode {
        private final String ns;
        private final String name;
        private final Map<String, String> attrs = new LinkedHashMap<>();
        private final List<XmlNode> children = new ArrayList<>();
        private String text = "";

        private XmlNode(String ns, String name) {
            this.ns = ns;
            this.name = name;
        }

        private String attr(String key) {
            return attrs.get(key);
        }

        private XmlNode first(String localName) {
            for (XmlNode child : children) {
                if (child.name.equals(localName)) {
                    return child;
                }
            }
            return null;
        }

        private List<XmlNode> all(String localName) {
            List<XmlNode> found = new ArrayList<>();
            for (XmlNode child : children) {
                if (child.name.equals(localName)) {
                    found.add(child);
                }
            }
            return found;
        }

        /** Text of all descendant {@code a:t} elements (the text of a shape). */
        private String textOf(String localName) {
            StringBuilder sb = new StringBuilder();
            for (XmlNode child : children) {
                if (child.name.equals(localName)) {
                    sb.append(child.text);
                }
                sb.append(child.textOf(localName));
            }
            return sb.toString();
        }
    }

    /**
     * Collects every {@code w:drawing} subtree below the given element.
     * <p>
     * The depth counter is not decoration: {@code toNextToken()} walks straight past the END
     * token of the paragraph into its following siblings, so without it every paragraph reports
     * the drawings of all later paragraphs too (measured live: 91 "placements" instead of the 4
     * that are in LibreOffice_Fixture1.docx). Same trap as {@link #countDescendants}, see
     * docs/js/docx.md §1.3.
     * </p>
     */
    private static List<XmlNode> drawingNodes(XmlObject root) {
        List<XmlNode> found = new ArrayList<>();
        if (root == null) {
            return found;
        }
        XmlCursor cursor = root.newCursor();
        try {
            int depth = 0;
            XmlCursor.TokenType token = cursor.toFirstContentToken();
            while (token != null && !token.isNone()) {
                if (token.isStart()) {
                    depth++;
                    QName qname = cursor.getName();
                    if (qname != null && "drawing".equals(qname.getLocalPart())
                            && NS_W.equals(qname.getNamespaceURI())) {
                        found.add(readElement(cursor));
                        // readElement() consumed the subtree and stopped on its END token
                        depth--;
                    }
                } else if (token.isEnd()) {
                    if (depth == 0) {
                        break;                       // the paragraph itself is closed
                    }
                    depth--;
                }
                token = cursor.toNextToken();
            }
        } finally {
            cursor.dispose();
        }
        return found;
    }

    /**
     * Reads the element the cursor points at into an {@link XmlNode}. The cursor is left on
     * the END token of that element, which lets the caller simply continue with the next one.
     */
    private static XmlNode readElement(XmlCursor cursor) {
        QName qname = cursor.getName();
        XmlNode node = new XmlNode(qname == null ? "" : qname.getNamespaceURI(),
                qname == null ? "" : qname.getLocalPart());
        node.attrs.putAll(attributesOf(cursor));
        StringBuilder text = new StringBuilder();
        XmlCursor.TokenType token = cursor.toNextToken();
        while (token != null && !token.isNone()) {
            if (token.isEnd()) {
                break;
            }
            if (token.isStart()) {
                node.children.add(readElement(cursor));
            } else if (token.isText()) {
                text.append(cursor.getTextValue());
            }
            token = cursor.toNextToken();
        }
        node.text = text.toString().trim();
        return node;
    }

    /**
     * The attributes of the element the given cursor points at, by local name.
     * <p>
     * {@code XmlCursor} has no index-based attribute accessors (it does not inherit
     * {@code XMLStreamReader}): attributes are tokens of their own, walked with
     * {@code toFirstAttribute()}/{@code toNextAttribute()} - on a second cursor, so the caller
     * stays on the element's START token.
     * </p>
     */
    private static Map<String, String> attributesOf(XmlCursor atElement) {
        Map<String, String> attrs = new LinkedHashMap<>();
        XmlCursor cursor = atElement.newCursor();
        try {
            if (cursor.toFirstAttribute()) {
                do {
                    QName name = cursor.getName();
                    if (name != null) {
                        attrs.put(name.getLocalPart(), cursor.getTextValue());
                    }
                } while (cursor.toNextAttribute());
            }
        } finally {
            cursor.dispose();
        }
        return attrs;
    }

    /** One {@code w:drawing} as a placement entry of {@link #getShapes(String)}. */
    private static Map<String, Object> placementOf(XmlNode drawing) {
        Map<String, Object> entry = new LinkedHashMap<>();
        XmlNode box = drawing.first("inline");
        entry.put("mode", "inline");
        if (box == null) {
            box = drawing.first("anchor");
            entry.put("mode", box == null ? null : "anchor");
        }
        if (box == null) {
            entry.put("shapes", List.of());
            return entry;
        }
        XmlNode extent = box.first("extent");
        if (extent != null) {
            entry.put("extentCm", sizeOf(extent));
        }
        XmlNode docPr = box.first("docPr");
        if (docPr != null) {
            entry.put("id", longOrNull(docPr.attr("id")));
            entry.put("name", emptyToNull(docPr.attr("name")));
        }
        XmlNode positionH = box.first("positionH");
        XmlNode positionV = box.first("positionV");
        if (positionH != null) {
            entry.put("relativeFromH", emptyToNull(positionH.attr("relativeFrom")));
            entry.put("leftCm", cmOrNull(positionH.first("posOffset") == null ? null
                    : positionH.first("posOffset").text));
        }
        if (positionV != null) {
            entry.put("relativeFromV", emptyToNull(positionV.attr("relativeFrom")));
            entry.put("topCm", cmOrNull(positionV.first("posOffset") == null ? null
                    : positionV.first("posOffset").text));
        }
        XmlNode graphic = box.first("graphic");
        XmlNode data = graphic == null ? null : graphic.first("graphicData");
        List<Map<String, Object>> shapes = new ArrayList<>();
        if (data != null) {
            entry.put("graphicUri", emptyToNull(data.attr("uri")));
            for (XmlNode child : data.children) {
                if ("wgp".equals(child.name) || "wsp".equals(child.name)) {
                    shapes.add(shapeOf(child));
                } else if ("blipFill".equals(child.name) || "pic".equals(child.name)) {
                    Map<String, Object> picture = new LinkedHashMap<>();
                    picture.put("kind", "picture");
                    shapes.add(picture);
                }
            }
        }
        entry.put("shapes", shapes);
        return entry;
    }

    /** One {@code wpg:wgp} or {@code wps:wsp} (recursively for groups). */
    private static Map<String, Object> shapeOf(XmlNode node) {
        boolean group = "wgp".equals(node.name);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("kind", group ? "group" : "shape");
        XmlNode nonVisual = group ? node.first("nvGpPr") : node.first("cNvPr");
        if (group && nonVisual == null) {
            // POI's compiled CT_WordprocessingGroup carries cNvPr/cNvGrpSpPr directly, Word's
            // wpg wraps them in nvGpPr - read either
            nonVisual = node;
        }
        if (!group && nonVisual == null) {
            // LibreOffice leaves cNvSpPr empty (it is the txBox/spLocks type) and puts no
            // identity on a standalone shape - tolerate both spellings when reading
            nonVisual = node.first("cNvSpPr");
        }
        if (nonVisual != null) {
            XmlNode identity = group ? (nonVisual == node ? node.first("cNvPr")
                    : nonVisual.first("cNvPr")) : nonVisual;
            if (identity == null) {
                identity = nonVisual;
            }
            if (identity.attr("id") != null || identity.attr("name") != null) {
                entry.put("id", longOrNull(identity.attr("id")));
                entry.put("name", emptyToNull(identity.attr("name")));
            }
        }
        XmlNode props = group ? node.first("grpSpPr") : node.first("spPr");
        XmlNode xfrm = props == null ? null : props.first("xfrm");
        if (xfrm != null) {
            XmlNode off = xfrm.first("off");
            if (off != null) {
                entry.put("leftCm", cmOrNull(off.attr("x")));
                entry.put("topCm", cmOrNull(off.attr("y")));
            }
            entry.putAll(sizeOf(xfrm.first("ext")));
            Long rot = longOrNull(xfrm.attr("rot"));
            if (rot != null) {
                entry.put("rotationDeg", round(rot / 60000.0));
            }
            if ("1".equals(xfrm.attr("flipH")) || "true".equals(xfrm.attr("flipH"))) {
                entry.put("flipH", Boolean.TRUE);
            }
            if ("1".equals(xfrm.attr("flipV")) || "true".equals(xfrm.attr("flipV"))) {
                entry.put("flipV", Boolean.TRUE);
            }
            if (group) {
                entry.put("childExtentCm", sizeOf(xfrm.first("chExt")));
            }
        }
        if (!group && props != null) {
            XmlNode geom = props.first("prstGeom");
            entry.put("prst", emptyToNull(geom == null ? null : geom.attr("prst")));
            entry.put("fillColor", colorOf(props));
            XmlNode line = props.first("ln");
            if (line != null) {
                entry.put("lineColor", colorOf(line));
                entry.put("lineWidthPt", roundOr(longOrNull(line.attr("w")), EMU_PER_PT));
                XmlNode dash = line.first("prstDash");
                entry.put("lineDash", emptyToNull(dash == null ? null : dash.attr("val")));
            }
        }
        String text = node.textOf("t");
        if (!text.isEmpty()) {
            entry.put("text", text);
        }
        if (group) {
            List<Map<String, Object>> children = new ArrayList<>();
            for (XmlNode child : node.children) {
                if ("wgp".equals(child.name) || "wsp".equals(child.name)) {
                    children.add(shapeOf(child));
                }
            }
            entry.put("shapes", children);
        }
        return entry;
    }

    /** {@code a:solidFill/a:srgbClr}, "none" for {@code a:noFill}, null when unset. */
    private static String colorOf(XmlNode owner) {
        if (owner.first("noFill") != null) {
            return "none";
        }
        XmlNode fill = owner.first("solidFill");
        XmlNode color = fill == null ? null : fill.first("srgbClr");
        return color == null ? null : emptyToNull(color.attr("val"));
    }

    private static Map<String, Object> sizeOf(XmlNode extent) {
        Map<String, Object> size = new LinkedHashMap<>();
        if (extent != null) {
            size.put("widthCm", cmOrNull(extent.attr("cx")));
            size.put("heightCm", cmOrNull(extent.attr("cy")));
        }
        return size;
    }

    private static Double cmOrNull(String emuValue) {
        Long emu = longOrNull(emuValue);
        return emu == null ? null : round(emu / EMU_PER_CM);
    }

    private static Double roundOr(Long value, double divisor) {
        return value == null ? null : round(value / divisor);
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private static Long longOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private XWPFRun getRun(String runHandle) {
        XWPFRun run = runs.get(runHandle);
        if (run == null) {
            throw new PoiUserRuntimeException("Run not found (invalid handle): " + runHandle);
        }
        return run;
    }

    /**
     * Returns a compact border summary for a table cell, e.g. "T=single B=single L=single R=single".
     */
    private String cellBorders(XWPFTableCell cell) {
        CTTc tc = cell.getCTTc();
        CTTcPr tcPr = tc.getTcPr();
        CTTcBorders borders = (tcPr != null) ? tcPr.getTcBorders() : null;
        StringBuilder sb = new StringBuilder();
        sb.append("T=").append(borderVal(borders == null ? null : borders.getTop()));
        sb.append(" B=").append(borderVal(borders == null ? null : borders.getBottom()));
        sb.append(" L=").append(borderVal(borders == null ? null : borders.getLeft()));
        sb.append(" R=").append(borderVal(borders == null ? null : borders.getRight()));
        return sb.toString();
    }

    /**
     * Returns the border style name of a border element, or "-" if absent.
     */
    private String borderVal(CTBorder border) {
        if (border == null) {
            return "-";
        }
        STBorder.Enum val = border.getVal();
        return val == null ? "-" : val.toString();
    }

    private String generateHandle(String prefix) {
        return prefix + "-" + handleCounter.incrementAndGet();
    }
}
