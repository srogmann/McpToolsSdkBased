package org.rogmann.mcp2sdk.poi;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Color;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.rogmann.mcp2sdk.WorkProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Toolbox for accessing .xlsx files via Apache POI.
 * <p>
 * This class provides a handle-based API that can be exposed to JavaScript
 * via GraalVM Polyglot ProxyExecutable bindings.
 * </p>
 *
 * <h3>Security</h3>
 * <p>
 * File access is restricted to the project base directory (from system property {@code IDE_PROJECT_DIR}).
 * Only files within this directory may be opened, created, or saved.
 * Relative paths are resolved against this base directory.
 * </p>
 *
 * <h3>Handle system</h3>
 * <ul>
 *   <li>{@code wb-&lt;n&gt;} – handle for an {@link XSSFWorkbook}</li>
 *   <li>{@code sh-&lt;n&gt;} – handle for a {@link Sheet}</li>
 * </ul>
 *
 * <h3>Error handling</h3>
 * <p>
 * On errors, a {@link PoiUserRuntimeException} is thrown with a user-friendly message
 * suitable for display to the LLM. Detailed technical information (including stack traces)
 * is written to the SLF4J log.
 * </p>
 *
 * <h3>Instance / concurrency</h3>
 * <p>
 * A {@code PoiToolBox} holds all its handle state ({@code workbooks}, {@code sheets}) at
 * instance level. The bridge ({@link PoiToolBoxJsBridge}) creates a fresh instance per
 * JavaScript tool-call, so parallel users/calls are isolated from each other and workbooks
 * do not leak between calls. Use {@link #closeAllWorkbooks()} to release the workbooks of an
 * instance (e.g. at the end of a call).
 * </p>
 */
public class PoiToolBox {

    private static final Logger LOG = LoggerFactory.getLogger(PoiToolBox.class);

    /** Map: workbook-handle -> XSSFWorkbook */
    private final Map<String, XSSFWorkbook> workbooks = new ConcurrentHashMap<>();

    /** Map: sheet-handle -> Sheet */
    private final Map<String, Sheet> sheets = new ConcurrentHashMap<>();

    /** Counter for generating unique handles */
    private final AtomicLong handleCounter = new AtomicLong();

    /**
     * Creates a PoiToolBox instance with its own isolated handle state.
     * <p>
     * A fresh instance should be created per JavaScript tool-call so that parallel
     * users and calls do not share workbook/sheet handles. All workbooks of an
     * instance are released via {@link #closeAllWorkbooks()}.
     * </p>
     */
    public PoiToolBox() {
        // Per-call instance with isolated state
    }

    /**
     * Returns the project base directory for file access.
     * <p>
     * The base directory is determined via {@link WorkProject#lookupProject(Map)}.
     * </p>
     * @return the resolved base path
     * @throws PoiUserRuntimeException if {@code IDE_PROJECT_DIR} is not available
     */
    static Path getBasePath() {
        Map<String, Object> result = new HashMap<>();
        WorkProject workProject = WorkProject.lookupProject(result);
        if (workProject == null) {
            String error = (String) result.get("error");
            throw new PoiUserRuntimeException(
                    "No project base directory available. Ensure IDE_PROJECT_DIR is set. "
                    + (error != null ? error : ""));
        }
        Path projectBaseDir = workProject.projectBaseDir();
        LOG.debug("Using project base directory as POI base path: {}", projectBaseDir);
        return projectBaseDir;
    }

    /**
     * Resolves a file path against the base directory and validates it.
     * @param filePath relative path (or absolute within base directory)
     * @return the resolved absolute path
     * @throws PoiUserRuntimeException if the path is invalid or outside the base directory
     */
    static Path resolveSafePath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new PoiUserRuntimeException("File path must not be empty.");
        }
        // Add-on mount syntax: "/addonName/path/within/addon"
        if (filePath.startsWith("/")) {
            int firstSlash = filePath.indexOf('/', 1);
            if (firstSlash > 1) {
                String addonName = filePath.substring(1, firstSlash);
                Path addonRoot = WorkProject.addonPath(addonName);
                if (addonRoot != null) {
                    String remainder = filePath.substring(firstSlash);
                    String rel = remainder.startsWith("/") ? remainder.substring(1) : remainder;
                    Path resolved = addonRoot.resolve(rel).normalize();
                    if (!resolved.startsWith(addonRoot)) {
                        LOG.warn("Path traversal attempt in add-on '{}': filePath='{}'", addonName, filePath);
                        throw new PoiUserRuntimeException(
                                "Access denied: the specified path is not within the permitted add-on directory.");
                    }
                    return resolved;
                }
                throw new PoiUserRuntimeException("Add-on directory not configured: '" + addonName
                        + "'. Available add-ons: " + String.join(", ", WorkProject.addonNames()));
            }
        }
        Path basePath = getBasePath();
        // Normalize to prevent path traversal
        Path resolved = basePath.resolve(filePath).normalize();
        if (!resolved.startsWith(basePath)) {
            LOG.warn("Path traversal attempt detected: filePath='{}' resolved to '{}'", filePath, resolved);
            throw new PoiUserRuntimeException("Access denied: the specified path is not within the permitted directory.");
        }
        return resolved;
    }

    // ========================================================================
    // Workbook-level operations
    // ========================================================================

    /**
     * Opens an existing .xlsx file and returns a workbook handle.
     * @param filePath path relative to the base directory (or absolute within base)
     * @return workbook handle (e.g. "wb-1")
     * @throws PoiUserRuntimeException if the file cannot be opened or is not found
     */
    public String openFile(String filePath) {
        Path path = resolveSafePath(filePath);
        if (!Files.exists(path)) {
            throw new PoiUserRuntimeException("File not found: " + filePath);
        }
        if (!Files.isRegularFile(path)) {
            throw new PoiUserRuntimeException("Not a file: " + filePath);
        }
        try (FileInputStream fis = new FileInputStream(path.toFile())) {
            XSSFWorkbook wb = new XSSFWorkbook(fis);
            String handle = generateWorkbookHandle();
            workbooks.put(handle, wb);
            LOG.info("Opened workbook: {} -> {}", getBasePath().relativize(path), handle);
            return handle;
        } catch (IOException e) {
            LOG.error("Failed to open file: " + getBasePath().relativize(path), e);
            throw new PoiUserRuntimeException("Failed to open file: " + filePath, e);
        }
    }

    /**
     * Creates a new empty workbook and returns a handle.
     * <p>
     * The workbook is created with a default sheet named "Sheet1".
     * Use {@link #createWorkbook(String)} to specify a custom initial sheet name.
     * </p>
     * @return workbook handle (e.g. "wb-2")
     */
    public String createWorkbook() {
        XSSFWorkbook wb = new XSSFWorkbook();
        wb.createSheet("Sheet1");
        String handle = generateWorkbookHandle();
        workbooks.put(handle, wb);
        LOG.info("Created new workbook -> {}", handle);
        return handle;
    }

    /**
     * Creates a new empty workbook with a named initial sheet and returns a handle.
     * @param initialSheetName name for the first sheet
     * @return workbook handle (e.g. "wb-2")
     * @throws PoiUserRuntimeException if the sheet name is invalid
     */
    public String createWorkbook(String initialSheetName) {
        if (initialSheetName == null || initialSheetName.isBlank()) {
            throw new PoiUserRuntimeException("Sheet name must not be empty.");
        }
        XSSFWorkbook wb = new XSSFWorkbook();
        wb.createSheet(initialSheetName);
        String handle = generateWorkbookHandle();
        workbooks.put(handle, wb);
        LOG.info("Created new workbook with initial sheet '{}' -> {}", initialSheetName, handle);
        return handle;
    }

    /**
     * Saves a workbook to the given file path.
     * @param wbHandle workbook handle
     * @param filePath target path relative to the base directory
     * @throws PoiUserRuntimeException if the workbook handle is invalid or saving fails
     */
    public void save(String wbHandle, String filePath) {
        XSSFWorkbook wb = workbooks.get(wbHandle);
        if (wb == null) {
            throw new PoiUserRuntimeException("Workbook not found (invalid handle): " + wbHandle);
        }
        Path path = resolveSafePath(filePath);
        try {
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            try (FileOutputStream fos = new FileOutputStream(path.toFile())) {
                wb.write(fos);
            }
            LOG.info("Saved workbook {} to {}", wbHandle, getBasePath().relativize(path));
        } catch (IOException e) {
            LOG.error("Failed to save workbook " + wbHandle + " to " + getBasePath().relativize(path), e);
            throw new PoiUserRuntimeException("Failed to save workbook to: " + filePath, e);
        }
    }

    /**
     * Closes a workbook and releases resources.
     * Also invalidates all sheet handles that belong to this workbook.
     * @param wbHandle workbook handle
     */
    public void close(String wbHandle) {
        XSSFWorkbook wb = workbooks.remove(wbHandle);
        if (wb == null) {
            LOG.warn("Workbook not found (already closed?): {}", wbHandle);
            return;
        }
        // Remove associated sheet handles
        sheets.entrySet().removeIf(entry -> {
            Sheet sh = entry.getValue();
            return sh.getWorkbook() == wb;
        });
        try {
            wb.close();
        } catch (IOException e) {
            LOG.warn("Error closing workbook " + wbHandle, e);
        }
        LOG.info("Closed workbook: {}", wbHandle);
    }

    /**
     * Closes all open workbooks of this instance.
     * <p>
     * Called automatically at the end of a JavaScript tool-call (via the bridge's
     * {@link AutoCloseable}) to release native resources. Note: any workbook that
     * was modified but not saved via {@link #save(String, String)} will lose its
     * changes.
     * </p>
     */
    public void closeAllWorkbooks() {
        for (String handle : Set.copyOf(workbooks.keySet())) {
            close(handle);
        }
    }

    // ========================================================================
    // Sheet-level operations
    // ========================================================================

    /**
     * Returns the names of all sheets in a workbook.
     * @param wbHandle workbook handle
     * @return array of sheet names
     * @throws PoiUserRuntimeException if the workbook handle is invalid
     */
    public String[] getSheetNames(String wbHandle) {
        XSSFWorkbook wb = workbooks.get(wbHandle);
        if (wb == null) {
            throw new PoiUserRuntimeException("Workbook not found (invalid handle): " + wbHandle);
        }
        int count = wb.getNumberOfSheets();
        String[] names = new String[count];
        for (int i = 0; i < count; i++) {
            names[i] = wb.getSheetName(i);
        }
        return names;
    }

    /**
     * Gets a sheet handle by name from a workbook.
     * @param wbHandle workbook handle
     * @param sheetName name of the sheet
     * @return sheet handle (e.g. "sh-1")
     * @throws PoiUserRuntimeException if the workbook or sheet is not found
     */
    public String getSheet(String wbHandle, String sheetName) {
        XSSFWorkbook wb = workbooks.get(wbHandle);
        if (wb == null) {
            throw new PoiUserRuntimeException("Workbook not found (invalid handle): " + wbHandle);
        }
        Sheet sh = wb.getSheet(sheetName);
        if (sh == null) {
            throw new PoiUserRuntimeException("Sheet not found: '" + sheetName
                    + "'. Available sheets: " + String.join(", ", getSheetNames(wbHandle)));
        }
        String handle = generateSheetHandle();
        sheets.put(handle, sh);
        return handle;
    }

    /**
     * Gets a sheet handle by index from a workbook.
     * @param wbHandle workbook handle
     * @param sheetIndex 0-based index of the sheet
     * @return sheet handle (e.g. "sh-2")
     * @throws PoiUserRuntimeException if the workbook is not found or the index is out of range
     */
    public String getSheetByIndex(String wbHandle, int sheetIndex) {
        XSSFWorkbook wb = workbooks.get(wbHandle);
        if (wb == null) {
            throw new PoiUserRuntimeException("Workbook not found (invalid handle): " + wbHandle);
        }
        int maxIndex = wb.getNumberOfSheets() - 1;
        if (sheetIndex < 0 || sheetIndex > maxIndex) {
            throw new PoiUserRuntimeException("Sheet index out of range: " + sheetIndex
                    + " (valid range: 0.." + maxIndex + ", total sheets: " + wb.getNumberOfSheets() + ")");
        }
        Sheet sh = wb.getSheetAt(sheetIndex);
        String handle = generateSheetHandle();
        sheets.put(handle, sh);
        return handle;
    }

    /**
     * Creates a new sheet in a workbook.
     * @param wbHandle workbook handle
     * @param sheetName name for the new sheet
     * @return sheet handle for the new sheet
     * @throws PoiUserRuntimeException if the workbook is not found
     */
    public String createSheet(String wbHandle, String sheetName) {
        XSSFWorkbook wb = workbooks.get(wbHandle);
        if (wb == null) {
            throw new PoiUserRuntimeException("Workbook not found (invalid handle): " + wbHandle);
        }
        if (wb.getSheet(sheetName) != null) {
            LOG.warn("Sheet '{}' already exists, returning existing handle", sheetName);
            return getSheet(wbHandle, sheetName);
        }
        Sheet sh = wb.createSheet(sheetName);
        String handle = generateSheetHandle();
        sheets.put(handle, sh);
        LOG.info("Created sheet '{}' -> {}", sheetName, handle);
        return handle;
    }

    /**
     * Removes a sheet from a workbook by name.
     * @param wbHandle workbook handle
     * @param sheetName name of the sheet to remove
     * @throws PoiUserRuntimeException if the workbook or sheet is not found
     */
    public void removeSheet(String wbHandle, String sheetName) {
        XSSFWorkbook wb = workbooks.get(wbHandle);
        if (wb == null) {
            throw new PoiUserRuntimeException("Workbook not found (invalid handle): " + wbHandle);
        }
        int idx = wb.getSheetIndex(sheetName);
        if (idx < 0) {
            throw new PoiUserRuntimeException("Sheet not found: '" + sheetName
                    + "'. Available sheets: " + String.join(", ", getSheetNames(wbHandle)));
        }
        wb.removeSheetAt(idx);
        // Remove associated sheet handles
        sheets.entrySet().removeIf(entry -> {
            Sheet sh = entry.getValue();
            return sheetName.equals(sh.getSheetName()) && sh.getWorkbook() == wb;
        });
        LOG.info("Removed sheet '{}' from workbook {}", sheetName, wbHandle);
    }

    /**
     * Returns the name of a sheet given its handle.
     * @param sheetHandle sheet handle
     * @return sheet name
     * @throws PoiUserRuntimeException if the sheet handle is invalid
     */
    public String getSheetName(String sheetHandle) {
        Sheet sh = sheets.get(sheetHandle);
        if (sh == null) {
            throw new PoiUserRuntimeException("Sheet not found (invalid handle): " + sheetHandle);
        }
        return sh.getSheetName();
    }

    // ========================================================================
    // Cell-level operations
    // ========================================================================

    /**
     * Reads the value of a cell.
     * @param sheetHandle sheet handle
     * @param row 0-based row index
     * @param col 0-based column index
     * @return the cell value as an appropriate Java type (String, Double, Boolean, Date, null)
     * @throws PoiUserRuntimeException if the sheet handle is invalid
     */
    public Object getCellValue(String sheetHandle, int row, int col) {
        Sheet sh = getSheetByHandle(sheetHandle);
        Row r = sh.getRow(row);
        if (r == null) {
            return null;
        }
        Cell cell = r.getCell(col);
        if (cell == null) {
            return null;
        }
        return readCellValue(cell);
    }

    /**
     * Sets the value of a cell.
     * <p>
     * Supported value types:
     * <ul>
     *   <li>String -> sets as string cell</li>
     *   <li>Number (Integer, Long, Double, Float) -> sets as numeric cell</li>
     *   <li>Boolean -> sets as boolean cell</li>
     *   <li>null -> sets cell blank</li>
     *   <li>String starting with '=' -> sets as formula cell</li>
     * </ul>
     * @param sheetHandle sheet handle
     * @param row 0-based row index
     * @param col 0-based column index
     * @param value the value to set
     * @throws PoiUserRuntimeException if the sheet handle is invalid
     */
    public void setCellValue(String sheetHandle, int row, int col, Object value) {
        Sheet sh = getSheetByHandle(sheetHandle);
        Row r = sh.getRow(row);
        if (r == null) {
            r = sh.createRow(row);
        }
        Cell cell = r.getCell(col);
        if (cell == null) {
            cell = r.createCell(col);
        }
        writeCellValue(cell, value);
    }

    /**
     * Returns the cell type as a human-readable string.
     * @param sheetHandle sheet handle
     * @param row 0-based row index
     * @param col 0-based column index
     * @return cell type: "STRING", "NUMERIC", "DATE", "BOOLEAN", "FORMULA", "BLANK", "ERROR", or "EMPTY"
     * @throws PoiUserRuntimeException if the sheet handle is invalid
     */
    public String getCellType(String sheetHandle, int row, int col) {
        Sheet sh = getSheetByHandle(sheetHandle);
        Row r = sh.getRow(row);
        if (r == null) {
            return "EMPTY";
        }
        Cell cell = r.getCell(col);
        if (cell == null) {
            return "EMPTY";
        }
        CellType type = cell.getCellType();
        return switch (type) {
            case STRING -> "STRING";
            case NUMERIC -> DateUtil.isCellDateFormatted(cell) ? "DATE" : "NUMERIC";
            case BOOLEAN -> "BOOLEAN";
            case FORMULA -> "FORMULA";
            case BLANK -> "BLANK";
            case ERROR -> "ERROR";
            default -> type.name();
        };
    }

    /**
     * Returns the formula string of a cell (if it contains a formula).
     * @param sheetHandle sheet handle
     * @param row 0-based row index
     * @param col 0-based column index
     * @return the formula string (e.g. "SUM(A1:A12)"), or null if not a formula cell
     * @throws PoiUserRuntimeException if the sheet handle is invalid
     */
    public String getCellFormula(String sheetHandle, int row, int col) {
        Sheet sh = getSheetByHandle(sheetHandle);
        Row r = sh.getRow(row);
        if (r == null) {
            return null;
        }
        Cell cell = r.getCell(col);
        if (cell == null || cell.getCellType() != CellType.FORMULA) {
            return null;
        }
        return cell.getCellFormula();
    }

    // ========================================================================
    // Range / dimension operations
    // ========================================================================

    /**
     * Returns detailed information about a rectangular range of cells as a JSON-compatible Map.
     * <p>
     * The returned map contains:
     * <ul>
     *   <li>{@code sheetName} – Name of the sheet</li>
     *   <li>{@code range} – Map with keys: {@code firstRow}, {@code lastRow}, {@code firstCol}, {@code lastCol}</li>
     *   <li>{@code rows} – Array of row maps, each containing:
     *     <ul>
     *       <li>{@code rowNum} – 0-based row index</li>
     *       <li>{@code height} – Row height in points (or -1 if default)</li>
     *       <li>{@code cells} – Array of cell maps, each containing:
     *         <ul>
     *           <li>{@code col} – 0-based column index</li>
     *           <li>{@code ref} – Cell reference (e.g. "A1")</li>
     *           <li>{@code value} – The cell value (String, Double, Boolean, Date as ISO string, or null)</li>
     *           <li>{@code cellType} – Type string: "STRING", "NUMERIC", "DATE", "BOOLEAN", "FORMULA", "BLANK", "ERROR", or "EMPTY"</li>
     *           <li>{@code formula} – Formula string if cell type is FORMULA, otherwise absent</li>
     *           <li>{@code style} – Map of style properties (see {@link #getCellStyle}), or empty map if no style</li>
     *         </ul>
     *       </li>
     *     </ul>
     *   </li>
     * </ul>
     * </p>
     * <p>
     * This method is designed for LLM / JavaScript consumers that need to evaluate
     * values, formatting, and data types across a range in a single call.
     * </p>
     *
     * @param sheetHandle sheet handle
     * @param startRow    first row (0-based, inclusive)
     * @param endRow      last row (0-based, inclusive)
     * @param startCol    first column (0-based, inclusive)
     * @param endCol      last column (0-based, inclusive)
     * @return a JSON-compatible Map with the structure described above
     * @throws PoiUserRuntimeException if the sheet handle is invalid
     */
    public Map<String, Object> getRangeAsJson(String sheetHandle,
            int startRow, int endRow, int startCol, int endCol) {
        Sheet sh = getSheetByHandle(sheetHandle);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sheetName", sh.getSheetName());

        Map<String, Object> rangeInfo = new LinkedHashMap<>();
        rangeInfo.put("firstRow", startRow);
        rangeInfo.put("lastRow", endRow);
        rangeInfo.put("firstCol", startCol);
        rangeInfo.put("lastCol", endCol);
        result.put("range", rangeInfo);

        List<Map<String, Object>> rowsList = new ArrayList<>();

        for (int r = startRow; r <= endRow; r++) {
            Row row = sh.getRow(r);
            Map<String, Object> rowMap = new LinkedHashMap<>();
            rowMap.put("rowNum", r);

            if (row != null) {
                rowMap.put("height", (int) (row.getHeightInPoints() + 0.5f));
            } else {
                rowMap.put("height", -1);
            }

            List<Map<String, Object>> cellsList = new ArrayList<>();

            for (int c = startCol; c <= endCol; c++) {
                Map<String, Object> cellMap = new LinkedHashMap<>();
                cellMap.put("col", c);
                cellMap.put("ref", toColumnLetter(c) + (r + 1));

                if (row == null) {
                    cellMap.put("value", null);
                    cellMap.put("cellType", "EMPTY");
                } else {
                    Cell cell = row.getCell(c);
                    if (cell == null) {
                        cellMap.put("value", null);
                        cellMap.put("cellType", "EMPTY");
                    } else {
                        // Value
                        Object val = readCellValue(cell);
                        if (val instanceof Date dateVal) {
                            // Format date as ISO string for JSON compatibility
                            cellMap.put("value", Instant.ofEpochMilli(dateVal.getTime()).toString());
                            cellMap.put("cellType", "DATE");
                        } else {
                            cellMap.put("value", val);
                            cellMap.put("cellType", getCellType(sheetHandle, r, c));
                        }

                        // Formula string (if applicable)
                        if (cell.getCellType() == CellType.FORMULA) {
                            try {
                                cellMap.put("formula", cell.getCellFormula());
                            } catch (Exception e) {
                                // ignore if formula can't be retrieved
                            }
                        }

                        // Style information
                        CellStyle style = cell.getCellStyle();
                        if (style != null) {
                            Map<String, Object> styleMap = new LinkedHashMap<>();
                            Font font = sh.getWorkbook().getFontAt(style.getFontIndex());
                            styleMap.put("bold", font.getBold());
                            styleMap.put("italic", font.getItalic());
                            styleMap.put("fontName", font.getFontName());
                            styleMap.put("fontHeightInPoints", font.getFontHeightInPoints());

                            // Font color
                            if (font instanceof XSSFFont xssfFont) {
                                XSSFColor xssfColor = xssfFont.getXSSFColor();
                                if (xssfColor != null) {
                                    styleMap.put("fontColor", colorToHex(xssfColor));
                                }
                            } else {
                                short colorIdx = font.getColor();
                                if (colorIdx != IndexedColors.AUTOMATIC.getIndex()) {
                                    styleMap.put("fontColorIndex", (int) colorIdx);
                                }
                            }

                            // Background color
                            Color bgColor = style.getFillBackgroundColorColor();
                            if (bgColor != null && style.getFillPattern() != null
                                    && style.getFillPattern() != FillPatternType.NO_FILL) {
                                styleMap.put("backgroundColor", colorToHex(bgColor));
                            } else {
                                Color fgFillColor = style.getFillForegroundColorColor();
                                if (fgFillColor != null && style.getFillPattern() != null
                                        && style.getFillPattern() != FillPatternType.NO_FILL) {
                                    styleMap.put("backgroundColor", colorToHex(fgFillColor));
                                }
                            }
                            styleMap.put("fillPattern",
                                    style.getFillPattern() != null ? style.getFillPattern().name() : "NO_FILL");

                            // Alignment
                            styleMap.put("horizontalAlignment",
                                    style.getAlignment() != null ? style.getAlignment().name() : "GENERAL");
                            styleMap.put("verticalAlignment",
                                    style.getVerticalAlignment() != null ? style.getVerticalAlignment().name() : "BOTTOM");
                            styleMap.put("wrapText", style.getWrapText());

                            // Data format
                            String dataFormatStr = style.getDataFormatString();
                            if (dataFormatStr != null && !dataFormatStr.isEmpty()) {
                                styleMap.put("dataFormatString", dataFormatStr);
                            }

                            // Border
                            styleMap.put("borderTop", style.getBorderTop().name());
                            styleMap.put("borderBottom", style.getBorderBottom().name());
                            styleMap.put("borderLeft", style.getBorderLeft().name());
                            styleMap.put("borderRight", style.getBorderRight().name());

                            cellMap.put("style", styleMap);
                        } else {
                            cellMap.put("style", new LinkedHashMap<>());
                        }
                    }
                }

                cellsList.add(cellMap);
            }
            rowMap.put("cells", cellsList);
            rowsList.add(rowMap);
        }
        result.put("rows", rowsList);
        return result;
    }

    /**
     * Converts a 0-based column index to an Excel column letter (e.g. 0-&gt;"A", 1-&gt;"B", 26-&gt;"AA").
     * @param col 0-based column index
     * @return column letter(s)
     */
    private static String toColumnLetter(int col) {
        StringBuilder sb = new StringBuilder();
        int c = col;
        while (c >= 0) {
            sb.insert(0, (char) ('A' + (c % 26)));
            c = (c / 26) - 1;
        }
        return sb.toString();
    }

    /**
     * Returns information about the used range of a sheet.
     * @param sheetHandle sheet handle
     * @return a Map with keys: "firstRow", "lastRow", "firstCol", "lastCol", "rowCount", "colCount", "isEmpty"
     * @throws PoiUserRuntimeException if the sheet handle is invalid
     */
    public Map<String, Object> getUsedRange(String sheetHandle) {
        Sheet sh = getSheetByHandle(sheetHandle);
        int firstRow = sh.getFirstRowNum();
        int lastRow = sh.getLastRowNum();
        if (firstRow < 0 || lastRow < 0) {
            // Empty sheet
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("firstRow", -1);
            result.put("lastRow", -1);
            result.put("firstCol", -1);
            result.put("lastCol", -1);
            result.put("rowCount", 0);
            result.put("colCount", 0);
            result.put("isEmpty", true);
            return result;
        }
        int firstCol = Integer.MAX_VALUE;
        int lastCol = Integer.MIN_VALUE;
        for (int r = firstRow; r <= lastRow; r++) {
            Row row = sh.getRow(r);
            if (row != null) {
                int rowFirst = row.getFirstCellNum();
                int rowLast = row.getLastCellNum() - 1; // getLastCellNum is 1-based
                if (rowFirst >= 0 && rowFirst < firstCol) firstCol = rowFirst;
                if (rowLast >= 0 && rowLast > lastCol) lastCol = rowLast;
            }
        }
        if (firstCol > lastCol) {
            // No cells found
            firstCol = -1;
            lastCol = -1;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("firstRow", firstRow);
        result.put("lastRow", lastRow);
        result.put("firstCol", firstCol);
        result.put("lastCol", lastCol);
        result.put("rowCount", lastRow - firstRow + 1);
        result.put("colCount", lastCol - firstCol + 1);
        result.put("isEmpty", firstRow < 0);
        return result;
    }

    /**
     * Returns the number of rows in a sheet (last row index + 1).
     * @param sheetHandle sheet handle
     * @return row count
     * @throws PoiUserRuntimeException if the sheet handle is invalid
     */
    public int getRowCount(String sheetHandle) {
        Sheet sh = getSheetByHandle(sheetHandle);
        return sh.getLastRowNum() + 1;
    }

    /**
     * Returns all data from a sheet as a 2D array (array of rows, each row is an array of values).
     * @param sheetHandle sheet handle
     * @return 2D array of cell values (may be empty array if sheet is empty)
     * @throws PoiUserRuntimeException if the sheet handle is invalid
     */
    public Object[][] getAllData(String sheetHandle) {
        Map<String, Object> range = getUsedRange(sheetHandle);
        if ((Boolean) range.get("isEmpty")) {
            return new Object[0][0];
        }
        int firstRow = (Integer) range.get("firstRow");
        int lastRow = (Integer) range.get("lastRow");
        int firstCol = (Integer) range.get("firstCol");
        int lastCol = (Integer) range.get("lastCol");
        int rows = lastRow - firstRow + 1;
        int cols = lastCol - firstCol + 1;
        Object[][] data = new Object[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                data[r][c] = getCellValue(sheetHandle, firstRow + r, firstCol + c);
            }
        }
        return data;
    }

    /**
     * Writes a 2D array of values to a sheet, starting at the given row/col.
     * @param sheetHandle sheet handle
     * @param data 2D array of values
     * @param startRow starting row (0-based)
     * @param startCol starting column (0-based)
     * @throws PoiUserRuntimeException if the sheet handle is invalid
     */
    public void setAllData(String sheetHandle, Object[][] data, int startRow, int startCol) {
        for (int r = 0; r < data.length; r++) {
            Object[] rowData = data[r];
            if (rowData == null) continue;
            for (int c = 0; c < rowData.length; c++) {
                setCellValue(sheetHandle, startRow + r, startCol + c, rowData[c]);
            }
        }
    }

    // ========================================================================
    // Style / Format operations
    // ========================================================================

    /**
     * Returns style information for a cell.
     * @param sheetHandle sheet handle
     * @param row 0-based row index
     * @param col 0-based column index
     * @return Map with style properties, or null if cell is empty
     * @throws PoiUserRuntimeException if the sheet handle is invalid
     */
    public Map<String, Object> getCellStyle(String sheetHandle, int row, int col) {
        Sheet sh = getSheetByHandle(sheetHandle);
        Row r = sh.getRow(row);
        if (r == null) {
            return null;
        }
        Cell cell = r.getCell(col);
        if (cell == null) {
            return null;
        }
        CellStyle style = cell.getCellStyle();
        if (style == null) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();

        // Font information
        Font font = sh.getWorkbook().getFontAt(style.getFontIndex());
        result.put("bold", font.getBold());
        result.put("italic", font.getItalic());
        result.put("fontName", font.getFontName());
        result.put("fontHeightInPoints", font.getFontHeightInPoints());
        result.put("underline", font.getUnderline());
        result.put("strikeout", font.getStrikeout());

        // Color information
        result.put("fillPattern", style.getFillPattern() != null ? style.getFillPattern().name() : "NO_FILL");

        // Foreground (font) color - XSSFFont specific
        if (font instanceof XSSFFont xssfFont) {
            XSSFColor xssfColor = xssfFont.getXSSFColor();
            if (xssfColor != null) {
                result.put("fontColor", colorToHex(xssfColor));
            }
        } else {
            // Fallback: use indexed color
            short colorIdx = font.getColor();
            if (colorIdx != IndexedColors.AUTOMATIC.getIndex()) {
                result.put("fontColorIndex", (int) colorIdx);
            }
        }

        // Background color (from fill)
        Color bgColor = style.getFillBackgroundColorColor();
        if (bgColor != null && style.getFillPattern() != FillPatternType.NO_FILL) {
            result.put("backgroundColor", colorToHex(bgColor));
        } else {
            // Try foreground fill color
            Color fgFillColor = style.getFillForegroundColorColor();
            if (fgFillColor != null && style.getFillPattern() != FillPatternType.NO_FILL) {
                result.put("backgroundColor", colorToHex(fgFillColor));
            }
        }

        // Alignment
        result.put("horizontalAlignment", style.getAlignment() != null ? style.getAlignment().name() : "GENERAL");
        result.put("verticalAlignment", style.getVerticalAlignment() != null ? style.getVerticalAlignment().name() : "BOTTOM");
        result.put("wrapText", style.getWrapText());
        result.put("rotation", style.getRotation());

        // Number format
        result.put("dataFormatString", style.getDataFormatString());
        result.put("dataFormat", style.getDataFormat());

        // Border
        result.put("borderTop", style.getBorderTop().name());
        result.put("borderBottom", style.getBorderBottom().name());
        result.put("borderLeft", style.getBorderLeft().name());
        result.put("borderRight", style.getBorderRight().name());

        // Cell type info
        result.put("cellType", getCellType(sheetHandle, row, col));

        return result;
    }

    /**
     * Sets the bold property of a cell's font.
     * @param sheetHandle sheet handle
     * @param row 0-based row index
     * @param col 0-based column index
     * @param bold true for bold, false for normal
     * @throws PoiUserRuntimeException if the sheet handle is invalid
     */
    public void setCellBold(String sheetHandle, int row, int col, boolean bold) {
        modifyCellFont(sheetHandle, row, col, font -> font.setBold(bold));
    }

    /**
     * Sets the italic property of a cell's font.
     * @param sheetHandle sheet handle
     * @param row 0-based row index
     * @param col 0-based column index
     * @param italic true for italic, false for normal
     * @throws PoiUserRuntimeException if the sheet handle is invalid
     */
    public void setCellItalic(String sheetHandle, int row, int col, boolean italic) {
        modifyCellFont(sheetHandle, row, col, font -> font.setItalic(italic));
    }

    /**
     * Sets the font size of a cell.
     * @param sheetHandle sheet handle
     * @param row 0-based row index
     * @param col 0-based column index
     * @param sizePoints font size in points
     * @throws PoiUserRuntimeException if the sheet handle is invalid
     */
    public void setCellFontSize(String sheetHandle, int row, int col, short sizePoints) {
        modifyCellFont(sheetHandle, row, col, font -> font.setFontHeightInPoints(sizePoints));
    }

    /**
     * Sets the font name (e.g. "Arial", "Calibri") of a cell.
     * @param sheetHandle sheet handle
     * @param row 0-based row index
     * @param col 0-based column index
     * @param fontName font name
     * @throws PoiUserRuntimeException if the sheet handle is invalid
     */
    public void setCellFontName(String sheetHandle, int row, int col, String fontName) {
        modifyCellFont(sheetHandle, row, col, font -> font.setFontName(fontName));
    }

    /**
     * Sets the foreground (font) color of a cell.
     * @param sheetHandle sheet handle
     * @param row 0-based row index
     * @param col 0-based column index
     * @param hexColor hex color string (e.g. "#FF0000" for red, or "FF0000")
     * @throws PoiUserRuntimeException if the sheet handle is invalid
     */
    public void setCellFontColor(String sheetHandle, int row, int col, String hexColor) {
        modifyCellFont(sheetHandle, row, col, font -> {
            short colorIndex = getColorIndex(hexColor);
            if (colorIndex >= 0) {
                font.setColor(colorIndex);
            }
        });
    }

    /**
     * Sets the background (fill) color of a cell.
     * @param sheetHandle sheet handle
     * @param row 0-based row index
     * @param col 0-based column index
     * @param hexColor hex color string (e.g. "#FFFF00" for yellow, or "FFFF00")
     * @throws PoiUserRuntimeException if the sheet handle is invalid
     */
    public void setCellBackgroundColor(String sheetHandle, int row, int col, String hexColor) {
        Sheet sh = getSheetByHandle(sheetHandle);
        Row r = sh.getRow(row);
        if (r == null) r = sh.createRow(row);
        Cell cell = r.getCell(col);
        if (cell == null) cell = r.createCell(col);

        CellStyle style = cell.getCellStyle();
        if (style == null) {
            style = sh.getWorkbook().createCellStyle();
        }
        // Need to create a new style to avoid modifying shared styles
        CellStyle newStyle = cloneCellStyle(sh.getWorkbook(), style);

        short colorIndex = getColorIndex(hexColor);
        if (colorIndex >= 0) {
            newStyle.setFillForegroundColor(colorIndex);
            newStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
        cell.setCellStyle(newStyle);
    }

    /**
     * Sets the data format (number format) of a cell.
     * Useful for changing text to numeric or setting date formats.
     * @param sheetHandle sheet handle
     * @param row 0-based row index
     * @param col 0-based column index
     * @param formatString Excel format string (e.g. "0.00", "#,##0", "dd.MM.yyyy", "@" for text, "General")
     * @throws PoiUserRuntimeException if the sheet handle is invalid
     */
    public void setCellDataFormat(String sheetHandle, int row, int col, String formatString) {
        Sheet sh = getSheetByHandle(sheetHandle);
        Row r = sh.getRow(row);
        if (r == null) r = sh.createRow(row);
        Cell cell = r.getCell(col);
        if (cell == null) cell = r.createCell(col);

        CellStyle style = cell.getCellStyle();
        Workbook wb = sh.getWorkbook();
        CellStyle newStyle = cloneCellStyle(wb, style != null ? style : wb.createCellStyle());

        short fmtIdx = wb.createDataFormat().getFormat(formatString);
        newStyle.setDataFormat(fmtIdx);
        cell.setCellStyle(newStyle);
    }

    /**
     * Returns information about merged regions in a sheet.
     * @param sheetHandle sheet handle
     * @return array of Maps with "firstRow", "lastRow", "firstCol", "lastCol" for each merged region
     * @throws PoiUserRuntimeException if the sheet handle is invalid
     */
    public Map<String, Object>[] getMergedRegions(String sheetHandle) {
        Sheet sh = getSheetByHandle(sheetHandle);
        int count = sh.getNumMergedRegions();
        @SuppressWarnings("unchecked")
        Map<String, Object>[] regions = new Map[count];
        for (int i = 0; i < count; i++) {
            CellRangeAddress range = sh.getMergedRegion(i);
            Map<String, Object> region = new LinkedHashMap<>();
            region.put("firstRow", range.getFirstRow());
            region.put("lastRow", range.getLastRow());
            region.put("firstCol", range.getFirstColumn());
            region.put("lastCol", range.getLastColumn());
            regions[i] = region;
        }
        return regions;
    }

    /**
     * Returns a list of all formulas in a sheet with their cell references.
     * @param sheetHandle sheet handle
     * @return array of Maps with "cell" (e.g. "A1") and "formula" (e.g. "SUM(B2:B10)")
     * @throws PoiUserRuntimeException if the sheet handle is invalid
     */
    public Map<String, String>[] getFormulas(String sheetHandle) {
        Sheet sh = getSheetByHandle(sheetHandle);
        List<Map<String, String>> formulaList = new ArrayList<>();
        for (Row row : sh) {
            for (Cell cell : row) {
                if (cell.getCellType() == CellType.FORMULA) {
                    Map<String, String> entry = new LinkedHashMap<>();
                    entry.put("cell", cell.getAddress().formatAsString());
                    entry.put("formula", cell.getCellFormula());
                    formulaList.add(entry);
                }
            }
        }
        @SuppressWarnings("unchecked")
        Map<String, String>[] result = formulaList.toArray(new Map[0]);
        return result;
    }

    /**
     * Sets the column width.
     * @param sheetHandle sheet handle
     * @param col 0-based column index
     * @param widthChars width in characters
     * @throws PoiUserRuntimeException if the sheet handle is invalid
     */
    public void setColumnWidth(String sheetHandle, int col, int widthChars) {
        Sheet sh = getSheetByHandle(sheetHandle);
        sh.setColumnWidth(col, widthChars * 256);
    }

    /**
     * Auto-sizes a column to fit its content.
     * @param sheetHandle sheet handle
     * @param col 0-based column index
     * @throws PoiUserRuntimeException if the sheet handle is invalid
     */
    public void autoSizeColumn(String sheetHandle, int col) {
        Sheet sh = getSheetByHandle(sheetHandle);
        sh.autoSizeColumn(col);
    }

    // ========================================================================
    // Help / Documentation
    // ========================================================================

    /**
     * Returns a help text describing the POI toolbox API with a usage example.
     * @return help text as a multi-line string
     */
    public String help() {
        return """
                POI Toolbox API for .xlsx access
                =================================
                
                All file paths are relative to the project base directory
                (configured via the system property IDE_PROJECT_DIR).

                NOTE: poi.save(wbHandle, path) MUST be called explicitly to persist
                changes to a file. At the end of the JavaScript run all workbooks of
                this tool call are automatically closed and unsaved changes are lost.
                
                --- Workbook operations ---
                poi.openFile(path)                - Open .xlsx file, returns wbHandle
                poi.createWorkbook()              - Create new workbook (with default sheet "Sheet1"), returns wbHandle
                poi.createWorkbook(name)          - Create new workbook with named initial sheet, returns wbHandle
                poi.save(wbHandle, path)          - Save workbook to file
                poi.close(wbHandle)               - Close workbook
                
                --- Sheet operations ---
                poi.getSheetNames(wbHandle)       - Get array of sheet names
                poi.getSheet(wbHandle, name)      - Get sheet handle by name
                poi.getSheetByIndex(wbHandle, idx)- Get sheet handle by index
                poi.createSheet(wbHandle, name)   - Create new sheet
                poi.removeSheet(wbHandle, name)   - Remove a sheet
                poi.getSheetName(shHandle)        - Get sheet name from handle
                
                --- Cell operations ---
                poi.getCellValue(sh, row, col)    - Read cell value (0-based row/col)
                poi.setCellValue(sh, row, col, v) - Write cell value (String, Number, Boolean, null)
                poi.getCellType(sh, row, col)     - Get cell type (STRING, NUMERIC, DATE, etc.)
                poi.getCellFormula(sh, row, col)  - Get formula string (e.g. "SUM(A1:A12)")
                
                --- Range / Data ---
                poi.getUsedRange(sh)              - Get used range {firstRow, lastRow, firstCol, lastCol, ...}
                poi.getRowCount(sh)               - Get number of rows
                poi.getRangeAsJson(sh,r1,r2,c1,c2)- Get range as JSON (values, types, styles per cell)
                poi.getAllData(sh)                - Get all data as 2D array
                poi.setAllData(sh, data, sr, sc)  - Write 2D array starting at (sr, sc)
                
                --- Style / Format ---
                poi.getCellStyle(sh, row, col)    - Get style info (bold, italic, colors, etc.)
                poi.setCellBold(sh, row, col, b)  - Set bold (true/false)
                poi.setCellItalic(sh, row, col, i)- Set italic (true/false)
                poi.setCellFontSize(sh, r, c, s)  - Set font size in points
                poi.setCellFontName(sh, r, c, fn) - Set font name (e.g. "Arial")
                poi.setCellFontColor(sh,r,c,hex)  - Set font color (hex e.g. "FF0000" or name "RED")
                poi.setCellBackgroundColor(...)   - Set background color
                poi.setCellDataFormat(sh,r,c,fmt) - Set data format (e.g. "0.00", "dd.MM.yyyy", "@" for text)
                
                --- Advanced ---
                poi.getMergedRegions(sh)          - Get merged regions
                poi.getFormulas(sh)               - Get all formulas with cell references
                poi.setColumnWidth(sh, col, w)    - Set column width in characters
                poi.autoSizeColumn(sh, col)       - Auto-fit column width
                
                Note: row/col are 0-based. On errors a PoiUserRuntimeException is thrown.
                
                --- Example: Open demo.xlsx, read A1, copy to D4, add label in D3 ---
                (Execute the following JavaScript code:)
                
                ```javascript
                // Open workbook
                var wb = poi.openFile("demo.xlsx");
                console.log("Opened workbook: demo.xlsx");
                
                // Get first sheet name
                var sheetNames = poi.getSheetNames(wb);
                console.log("Sheet names: " + sheetNames.join(", "));
                var sh = poi.getSheet(wb, sheetNames[0]);
                console.log("Using sheet: " + poi.getSheetName(sh));
                
                // Read cell A1 (row 0, col 0)
                var a1Value = poi.getCellValue(sh, 0, 0);
                console.log("A1 value: " + a1Value);
                
                // Write label in D3 (row 2, col 3) in bold
                poi.setCellValue(sh, 2, 3, "Copy of A1:");
                poi.setCellBold(sh, 2, 3, true);
                
                // Copy A1 value to D4 (row 3, col 3)
                poi.setCellValue(sh, 3, 3, a1Value);
                
                // Save changes
                poi.save(wb, "demo.xlsx");
                
                // Close workbook
                poi.close(wb);
                ```
                """;
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    /**
     * Returns a sheet by handle, throwing a user-friendly exception if not found.
     */
    private Sheet getSheetByHandle(String sheetHandle) {
        Sheet sh = sheets.get(sheetHandle);
        if (sh == null) {
            throw new PoiUserRuntimeException("Sheet not found (invalid handle): " + sheetHandle);
        }
        return sh;
    }

    private String generateWorkbookHandle() {
        return "wb-" + handleCounter.incrementAndGet();
    }

    private String generateSheetHandle() {
        return "sh-" + handleCounter.incrementAndGet();
    }

    /**
     * Reads a cell's value and returns it as a Java object.
     */
    private static Object readCellValue(Cell cell) {
        CellType type = cell.getCellType();
        return switch (type) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getDateCellValue();
                }
                yield cell.getNumericCellValue();
            }
            case BOOLEAN -> cell.getBooleanCellValue();
            case FORMULA -> {
                // Try to evaluate the formula
                try {
                    CellType resultType = cell.getCachedFormulaResultType();
                    yield switch (resultType) {
                        case STRING -> cell.getStringCellValue();
                        case NUMERIC -> {
                            if (DateUtil.isCellDateFormatted(cell)) {
                                yield cell.getDateCellValue();
                            }
                            yield cell.getNumericCellValue();
                        }
                        case BOOLEAN -> cell.getBooleanCellValue();
                        default -> "=" + cell.getCellFormula();
                    };
                } catch (Exception e) {
                    yield "=" + cell.getCellFormula();
                }
            }
            case BLANK -> null;
            case ERROR -> "ERROR:" + cell.getErrorCellValue();
            default -> null;
        };
    }

    /**
     * Writes a value to a cell, auto-detecting the type.
     */
    private static void writeCellValue(Cell cell, Object value) {
        if (value == null) {
            cell.setBlank();
            return;
        }
        if (value instanceof String str) {
            if (str.startsWith("=")) {
                // Formula
                cell.setCellFormula(str.substring(1));
            } else {
                cell.setCellValue(str);
            }
        } else if (value instanceof Number num) {
            cell.setCellValue(num.doubleValue());
        } else if (value instanceof Boolean bool) {
            cell.setCellValue(bool);
        } else if (value instanceof Date date) {
            cell.setCellValue(date);
            // Auto-format as date if not already formatted
            if (cell.getCellStyle() == null || cell.getCellStyle().getDataFormat() == 0) {
                Workbook wb = cell.getSheet().getWorkbook();
                CellStyle style = wb.createCellStyle();
                style.setDataFormat(wb.createDataFormat().getFormat("yyyy-MM-dd"));
                cell.setCellStyle(style);
            }
        } else {
            cell.setCellValue(value.toString());
        }
    }

    /**
     * Modifies the font of a cell using the given font modifier function.
     */
    private void modifyCellFont(String sheetHandle, int row, int col, Consumer<Font> fontModifier) {
        Sheet sh = getSheetByHandle(sheetHandle);
        Row r = sh.getRow(row);
        if (r == null) r = sh.createRow(row);
        Cell cell = r.getCell(col);
        if (cell == null) cell = r.createCell(col);

        Workbook wb = sh.getWorkbook();
        CellStyle oldStyle = cell.getCellStyle();
        CellStyle newStyle = cloneCellStyle(wb, oldStyle != null ? oldStyle : wb.createCellStyle());

        Font oldFont = wb.getFontAt(newStyle.getFontIndex());
        Font newFont = wb.createFont();
        copyFont(oldFont, newFont);
        fontModifier.accept(newFont);
        newStyle.setFont(newFont);
        cell.setCellStyle(newStyle);
    }

    /**
     * Creates a clone of a CellStyle.
     */
    private static CellStyle cloneCellStyle(Workbook wb, CellStyle source) {
        CellStyle newStyle = wb.createCellStyle();
        newStyle.cloneStyleFrom(source);
        return newStyle;
    }

    /**
     * Copies font properties from source to target.
     */
    private static void copyFont(Font source, Font target) {
        target.setFontName(source.getFontName());
        target.setFontHeightInPoints(source.getFontHeightInPoints());
        target.setBold(source.getBold());
        target.setItalic(source.getItalic());
        target.setColor(source.getColor());
        target.setUnderline(source.getUnderline());
        target.setStrikeout(source.getStrikeout());
        target.setTypeOffset(source.getTypeOffset());
        target.setCharSet(source.getCharSet());
    }

    /**
     * Converts a POI Color to a hex string (e.g. "FF0000").
     * For XSSF, casts to XSSFColor to access RGB values.
     */
    private static String colorToHex(Color color) {
        if (color == null) return null;
        if (color instanceof XSSFColor xssfColor) {
            byte[] rgb = xssfColor.getRGB();
            if (rgb != null && rgb.length >= 3) {
                return String.format("%02X%02X%02X", rgb[0] & 0xFF, rgb[1] & 0xFF, rgb[2] & 0xFF);
            }
            // Try ARGB hex
            String argbHex = xssfColor.getARGBHex();
            if (argbHex != null && argbHex.length() >= 6) {
                return argbHex.substring(argbHex.length() - 6).toUpperCase();
            }
        }
        return null;
    }

    /**
     * Maps named colors and hex strings to POI indexed color indices.
     */
    private static final Map<String, IndexedColors> COLOR_NAME_MAP = buildColorNameMap();

    private static Map<String, IndexedColors> buildColorNameMap() {
        Map<String, IndexedColors> map = new LinkedHashMap<>();
        map.put("BLACK", IndexedColors.BLACK);
        map.put("WHITE", IndexedColors.WHITE);
        map.put("RED", IndexedColors.RED);
        map.put("BRIGHT_GREEN", IndexedColors.BRIGHT_GREEN);
        map.put("BLUE", IndexedColors.BLUE);
        map.put("YELLOW", IndexedColors.YELLOW);
        map.put("PINK", IndexedColors.PINK);
        map.put("TURQUOISE", IndexedColors.TURQUOISE);
        map.put("DARK_RED", IndexedColors.DARK_RED);
        map.put("GREEN", IndexedColors.GREEN);
        map.put("DARK_BLUE", IndexedColors.DARK_BLUE);
        map.put("DARK_YELLOW", IndexedColors.DARK_YELLOW);
        map.put("PURPLE", IndexedColors.VIOLET);
        map.put("VIOLET", IndexedColors.VIOLET);
        map.put("TEAL", IndexedColors.TEAL);
        map.put("GREY", IndexedColors.GREY_50_PERCENT);
        map.put("GRAY", IndexedColors.GREY_50_PERCENT);
        map.put("GREY_25", IndexedColors.GREY_25_PERCENT);
        map.put("GRAY_25", IndexedColors.GREY_25_PERCENT);
        map.put("LIGHTGREY", IndexedColors.GREY_25_PERCENT);
        map.put("LIGHTGRAY", IndexedColors.GREY_25_PERCENT);
        map.put("GREY_50", IndexedColors.GREY_50_PERCENT);
        map.put("GRAY_50", IndexedColors.GREY_50_PERCENT);
        map.put("GREY_80", IndexedColors.GREY_80_PERCENT);
        map.put("GRAY_80", IndexedColors.GREY_80_PERCENT);
        map.put("DARKGREY", IndexedColors.GREY_80_PERCENT);
        map.put("DARKGRAY", IndexedColors.GREY_80_PERCENT);
        map.put("MAROON", IndexedColors.MAROON);
        map.put("ORANGE", IndexedColors.ORANGE);
        map.put("GOLD", IndexedColors.GOLD);
        map.put("LIME", IndexedColors.LIME);
        map.put("AQUA", IndexedColors.AQUA);
        map.put("SKY_BLUE", IndexedColors.SKY_BLUE);
        map.put("LIGHT_BLUE", IndexedColors.LIGHT_BLUE);
        map.put("BROWN", IndexedColors.BROWN);
        map.put("INDIGO", IndexedColors.INDIGO);
        map.put("PLUM", IndexedColors.PLUM);
        map.put("CORAL", IndexedColors.CORAL);
        map.put("ROSE", IndexedColors.ROSE);
        map.put("LAVENDER", IndexedColors.LAVENDER);
        map.put("TAN", IndexedColors.TAN);
        map.put("OLIVE_GREEN", IndexedColors.OLIVE_GREEN);
        map.put("SEA_GREEN", IndexedColors.SEA_GREEN);
        map.put("DARK_GREEN", IndexedColors.DARK_GREEN);
        map.put("DARK_TEAL", IndexedColors.DARK_TEAL);
        map.put("BLUE_GREY", IndexedColors.BLUE_GREY);
        map.put("LIGHT_ORANGE", IndexedColors.LIGHT_ORANGE);
        map.put("LEMON_CHIFFON", IndexedColors.LEMON_CHIFFON);
        map.put("ORCHID", IndexedColors.ORCHID);
        map.put("ROYAL_BLUE", IndexedColors.ROYAL_BLUE);
        map.put("CORNFLOWER_BLUE", IndexedColors.CORNFLOWER_BLUE);
        map.put("LIGHT_CORNFLOWER_BLUE", IndexedColors.LIGHT_CORNFLOWER_BLUE);
        map.put("LIGHT_TURQUOISE", IndexedColors.LIGHT_TURQUOISE);
        map.put("LIGHT_GREEN", IndexedColors.LIGHT_GREEN);
        map.put("LIGHT_YELLOW", IndexedColors.LIGHT_YELLOW);
        map.put("PALE_BLUE", IndexedColors.PALE_BLUE);
        return map;
    }

    /**
     * Maps indexed colors to approximate RGB values for closest-color matching.
     */
    private static final Map<IndexedColors, int[]> INDEXED_COLOR_RGB = buildIndexedColorRgb();

    private static Map<IndexedColors, int[]> buildIndexedColorRgb() {
        Map<IndexedColors, int[]> map = new LinkedHashMap<>();
        map.put(IndexedColors.BLACK, new int[]{0, 0, 0});
        map.put(IndexedColors.WHITE, new int[]{255, 255, 255});
        map.put(IndexedColors.RED, new int[]{255, 0, 0});
        map.put(IndexedColors.BRIGHT_GREEN, new int[]{0, 255, 0});
        map.put(IndexedColors.BLUE, new int[]{0, 0, 255});
        map.put(IndexedColors.YELLOW, new int[]{255, 255, 0});
        map.put(IndexedColors.PINK, new int[]{255, 192, 203});
        map.put(IndexedColors.TURQUOISE, new int[]{64, 224, 208});
        map.put(IndexedColors.DARK_RED, new int[]{139, 0, 0});
        map.put(IndexedColors.GREEN, new int[]{0, 128, 0});
        map.put(IndexedColors.DARK_BLUE, new int[]{0, 0, 139});
        map.put(IndexedColors.DARK_YELLOW, new int[]{204, 204, 0});
        map.put(IndexedColors.VIOLET, new int[]{238, 130, 238});
        map.put(IndexedColors.TEAL, new int[]{0, 128, 128});
        map.put(IndexedColors.GREY_25_PERCENT, new int[]{192, 192, 192});
        map.put(IndexedColors.GREY_50_PERCENT, new int[]{128, 128, 128});
        map.put(IndexedColors.GREY_80_PERCENT, new int[]{51, 51, 51});
        map.put(IndexedColors.MAROON, new int[]{128, 0, 0});
        map.put(IndexedColors.ORANGE, new int[]{255, 165, 0});
        map.put(IndexedColors.GOLD, new int[]{255, 215, 0});
        map.put(IndexedColors.LIME, new int[]{0, 255, 0});
        map.put(IndexedColors.AQUA, new int[]{0, 255, 255});
        map.put(IndexedColors.SKY_BLUE, new int[]{135, 206, 235});
        map.put(IndexedColors.LIGHT_BLUE, new int[]{173, 216, 230});
        map.put(IndexedColors.BROWN, new int[]{165, 42, 42});
        map.put(IndexedColors.INDIGO, new int[]{75, 0, 130});
        map.put(IndexedColors.PLUM, new int[]{221, 160, 221});
        map.put(IndexedColors.CORAL, new int[]{255, 127, 80});
        map.put(IndexedColors.ROSE, new int[]{255, 0, 127});
        map.put(IndexedColors.LAVENDER, new int[]{230, 230, 250});
        map.put(IndexedColors.TAN, new int[]{210, 180, 140});
        map.put(IndexedColors.OLIVE_GREEN, new int[]{128, 128, 0});
        map.put(IndexedColors.SEA_GREEN, new int[]{46, 139, 87});
        map.put(IndexedColors.DARK_GREEN, new int[]{0, 100, 0});
        map.put(IndexedColors.DARK_TEAL, new int[]{0, 100, 100});
        map.put(IndexedColors.BLUE_GREY, new int[]{102, 153, 204});
        map.put(IndexedColors.LIGHT_ORANGE, new int[]{255, 200, 150});
        map.put(IndexedColors.LEMON_CHIFFON, new int[]{255, 250, 205});
        map.put(IndexedColors.ORCHID, new int[]{218, 112, 214});
        map.put(IndexedColors.ROYAL_BLUE, new int[]{65, 105, 225});
        map.put(IndexedColors.CORNFLOWER_BLUE, new int[]{100, 149, 237});
        map.put(IndexedColors.LIGHT_CORNFLOWER_BLUE, new int[]{150, 180, 255});
        map.put(IndexedColors.LIGHT_TURQUOISE, new int[]{175, 238, 238});
        map.put(IndexedColors.LIGHT_GREEN, new int[]{144, 238, 144});
        map.put(IndexedColors.LIGHT_YELLOW, new int[]{255, 255, 224});
        map.put(IndexedColors.PALE_BLUE, new int[]{175, 200, 255});
        return map;
    }

    /**
     * Converts a hex color string or color name to a POI color index.
     * @param hexColor hex string (e.g. "FF0000" or "#FF0000") or color name (e.g. "RED")
     * @return indexed color index, or -1 if not found
     */
    private static short getColorIndex(String hexColor) {
        if (hexColor == null || hexColor.isEmpty()) return -1;
        String hex = hexColor.startsWith("#") ? hexColor.substring(1) : hexColor;

        // Try standard color names first
        IndexedColors namedColor = COLOR_NAME_MAP.get(hex.toUpperCase());
        if (namedColor != null) {
            return namedColor.getIndex();
        }

        // Try to parse as hex RGB
        try {
            if (hex.length() == 6) {
                int r = Integer.parseInt(hex.substring(0, 2), 16);
                int g = Integer.parseInt(hex.substring(2, 4), 16);
                int b = Integer.parseInt(hex.substring(4, 6), 16);
                // Find the closest indexed color
                return findClosestIndexedColor(r, g, b);
            }
        } catch (NumberFormatException ignored) {
        }
        return -1;
    }

    /**
     * Finds the closest indexed color for an RGB value.
     */
    private static short findClosestIndexedColor(int r, int g, int b) {
        short bestIndex = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (Map.Entry<IndexedColors, int[]> entry : INDEXED_COLOR_RGB.entrySet()) {
            int[] rgb = entry.getValue();
            int dr = rgb[0] - r;
            int dg = rgb[1] - g;
            int db = rgb[2] - b;
            int dist = dr * dr + dg * dg + db * db;
            if (dist < bestDistance) {
                bestDistance = dist;
                bestIndex = entry.getKey().getIndex();
            }
        }
        return bestIndex;
    }
}
