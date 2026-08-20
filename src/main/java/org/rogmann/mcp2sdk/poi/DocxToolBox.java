package org.rogmann.mcp2sdk.poi;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBorder;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTc;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcBorders;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STBorder;

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

    /** Map: document-handle -> XWPFDocument */
    private final Map<String, XWPFDocument> documents = new ConcurrentHashMap<>();

    /** Map: paragraph-handle -> XWPFParagraph */
    private final Map<String, XWPFParagraph> paragraphs = new ConcurrentHashMap<>();

    /** Map: run-handle -> XWPFRun */
    private final Map<String, XWPFRun> runs = new ConcurrentHashMap<>();

    /** Map: paragraph-handle -> document-handle (for cleanup on close) */
    private final Map<String, String> paragraphDoc = new ConcurrentHashMap<>();

    /** Map: run-handle -> document-handle (for cleanup on close) */
    private final Map<String, String> runDoc = new ConcurrentHashMap<>();

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
        Path path = PoiToolBox.resolveSafePath(filePath);
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
        Path path = PoiToolBox.resolveSafePath(filePath);
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
     * (list of run entries with {@code h} and {@code text}).
     * </p>
     * @param docHandle document handle
     * @return JSON-compatible Map with key "paragraphs"
     */
    public Map<String, Object> getParagraphs(String docHandle) {
        XWPFDocument doc = documents.get(docHandle);
        if (doc == null) {
            throw new PoiUserRuntimeException("Document not found (invalid handle): " + docHandle);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("document", docHandle);
        List<Map<String, Object>> parasList = new ArrayList<>();
        for (XWPFParagraph p : doc.getParagraphs()) {
            Map<String, Object> pm = new LinkedHashMap<>();
            String ph = generateHandle("p");
            paragraphs.put(ph, p);
            paragraphDoc.put(ph, docHandle);
            pm.put("h", ph);
            pm.put("text", p.getText());
            List<Map<String, Object>> runList = new ArrayList<>();
            for (XWPFRun run : p.getRuns()) {
                Map<String, Object> rm = new LinkedHashMap<>();
                String rh = generateHandle("r");
                runs.put(rh, run);
                runDoc.put(rh, docHandle);
                rm.put("h", rh);
                rm.put("text", run.text());
                runList.add(rm);
            }
            pm.put("runs", runList);
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
        XWPFDocument doc = documents.get(docHandle);
        if (doc == null) {
            throw new PoiUserRuntimeException("Document not found (invalid handle): " + docHandle);
        }
        StringBuilder sb = new StringBuilder();
        for (XWPFParagraph p : doc.getParagraphs()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(p.getText());
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
     * Each table entry contains {@code index}, {@code rowCount}, {@code colCount} and
     * {@code rows} (list of lists of cell texts).
     * </p>
     * @param docHandle document handle
     * @return JSON-compatible Map with key "tables"
     */
    public Map<String, Object> getTables(String docHandle) {
        XWPFDocument doc = documents.get(docHandle);
        if (doc == null) {
            throw new PoiUserRuntimeException("Document not found (invalid handle): " + docHandle);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("document", docHandle);
        List<Map<String, Object>> tablesList = new ArrayList<>();
        List<XWPFTable> tables = doc.getTables();
        for (int ti = 0; ti < tables.size(); ti++) {
            XWPFTable table = tables.get(ti);
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("index", ti);
            List<List<String>> rowsList = new ArrayList<>();
            List<List<String>> bordersList = new ArrayList<>();
            for (XWPFTableRow row : table.getRows()) {
                List<String> cellTexts = new ArrayList<>();
                List<String> cellBorders = new ArrayList<>();
                for (XWPFTableCell cell : row.getTableCells()) {
                    cellTexts.add(cell.getText());
                    cellBorders.add(cellBorders(cell));
                }
                rowsList.add(cellTexts);
                bordersList.add(cellBorders);
            }
            tm.put("rowCount", table.getRows().size());
            tm.put("colCount", table.getRows().isEmpty() ? 0
                    : table.getRows().get(0).getTableCells().size());
            tm.put("rows", rowsList);
            tm.put("cellBorders", bordersList);
            tablesList.add(tm);
        }
        result.put("tables", tablesList);
        return result;
    }

    /**
     * Appends a row to a table of the document.
     * @param docHandle document handle
     * @param tableIndex 0-based index of the table (from {@link #getTables(String)})
     * @param cellTexts texts for the new row's cells (may be shorter than the column count)
     */
    public void addRow(String docHandle, int tableIndex, String[] cellTexts) {
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
        List<XWPFTableRow> existingRows = table.getRows();
        XWPFTableRow template = existingRows.isEmpty() ? null : existingRows.get(existingRows.size() - 1);
        XWPFTableRow row = table.createRow();
        int cols = row.getTableCells().size();
        for (int i = 0; i < cols; i++) {
            XWPFTableCell cell = row.getCell(i);
            if (cell == null) {
                cell = row.addNewTableCell();
            }
            // Copy cell formatting (e.g. tcBorders) from the corresponding template cell.
            if (template != null && i < template.getTableCells().size()) {
                XWPFTableCell templateCell = template.getTableCells().get(i);
                CTTcPr templatePr = templateCell.getCTTc().getTcPr();
                if (templatePr != null) {
                    cell.getCTTc().setTcPr((CTTcPr) templatePr.copy());
                }
            }
            setCellText(cell, i < cellTexts.length ? cellTexts[i] : "");
        }
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
     * Sets the font size of a run (in points).
     * @param runHandle run handle
     * @param sizePoints font size in points
     */
    public void setRunFontSize(String runHandle, int sizePoints) {
        XWPFRun run = getRun(runHandle);
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

                --- Paragraphs / runs ---
                docx.getParagraphs(doc)      - List paragraphs [{h, text, runs:[{h, text}]}]
                docx.getDocumentText(doc)    - Full text of the document
                docx.getTables(doc)          - List tables [{index, rowCount, colCount,
                                                   rows, cellBorders (per cell T/B/L/R)}]
                docx.addRow(doc, tabIdx, arr)- Append a row (cloned style from last row)
                docx.removeRow(doc, tabIdx, r)- Remove a row from a table
                docx.setRowFontColor(doc,ti,r,hex)- Set font color of all runs in a table row
                docx.setCellFontColor(doc,ti,r,c,hex)- Set font color of a single cell
                docx.createParagraph(doc,t)  - Append a paragraph with text, returns paragraphHandle
                docx.addRun(paraHandle, t)   - Append a run to a paragraph, returns runHandle
                docx.getParagraphText(para)  - Text of a paragraph

                --- Run formatting ---
                docx.setRunText(run, t)      - Set run text
                docx.setRunBold(run, b)      - Set bold (true/false)
                docx.setRunItalic(run, i)    - Set italic (true/false)
                docx.setRunFontSize(run, s)  - Set font size in points
                docx.setRunFontName(run, fn) - Set font name (e.g. "Arial")
                docx.setRunFontColor(run, c) - Set font color (hex e.g. "FF0000")

                Typical workflow (find-then-edit):
                1. var paras = docx.getParagraphs(doc);  // read handles + text
                2. var ph = paras.paragraphs[2].h;       // pick a paragraph
                3. docx.addRun(ph, "Neu");               // or edit an existing run
                4. docx.save(doc, "out.docx");
                """;
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
     * Sets the text of a table cell, clearing any existing content first
     * (used after cloning a template row, whose cell text must be replaced).
     */
    private void setCellText(XWPFTableCell cell, String text) {
        while (cell.getParagraphs().size() > 1) {
            cell.removeParagraph(cell.getParagraphs().size() - 1);
        }
        XWPFParagraph p = cell.getParagraphs().isEmpty()
                ? cell.addParagraph() : cell.getParagraphs().get(0);
        for (int i = p.getRuns().size() - 1; i >= 0; i--) {
            p.removeRun(i);
        }
        XWPFRun run = p.createRun();
        run.setText(text == null ? "" : text);
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
