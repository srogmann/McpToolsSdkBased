package org.rogmann.mcp2sdk.poi;

import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.rogmann.mcp2sdk.js.GraalProxies;
import org.rogmann.mcp2sdk.js.JsModuleInterface;

import java.util.HashMap;
import java.util.Map;

/**
 * Bridge between GraalVM JavaScript and {@link PoiToolBox}.
 * <p>
 * Creates a {@link ProxyObject} namespace that exposes all POI toolbox
 * operations to JavaScript running in a GraalVM Polyglot context.
 * The resulting object is intended to be bound as {@code "poi"} in the
 * JavaScript bindings.
 * </p>
 *
 * <h3>Argument conversion</h3>
 * <p>
 * Arguments are read through {@link PoiJsArgs.Call}, so a wrong argument names the function,
 * the argument and the valid range. The raw {@code args[1].asInt()} it replaces was silent in
 * three ways: a fractional or quoted index ({@code 1.5}, {@code "B2"}) failed with Graal's own
 * coercion text, a negative one reached Apache POI (which either throws an
 * {@code IllegalArgumentException} or reads nothing at all), and {@code (short) asInt()} wrapped
 * a font size of 70000 into a different size without a word.
 * </p>
 *
 * <h3>Per-call isolation</h3>
 * <p>
 * Each call to {@link #wireApi(Value)} creates a fresh {@link PoiToolBox} instance and
 * captures it in the exposed proxy methods. This keeps all workbook/sheet handles
 * isolated per JavaScript tool-call (and per user thread), so parallel users and calls
 * do not interfere with each other.
 * </p>
 * <p>
 * The {@link AutoCloseable} returned by {@link #wireApi(Value)} closes all workbooks of
 * that instance when the JavaScript execution ends (via the {@code callResources} lifecycle
 * in the tool). No open file/resources leak across calls.
 * </p>
 *
 * <h3>Usage in JavaScript</h3>
 * <pre>{@code
 * var wb = poi.openFile("demo.xlsx");
 * var sh = poi.getSheet(wb, poi.getSheetNames(wb)[0]);
 * var value = poi.getCellValue(sh, 0, 0);
 * poi.setCellValue(sh, 0, 0, "Hello");
 * poi.save(wb, "demo.xlsx");   // must save explicitly before the run ends
 * }</pre>
 *
 * <h3>Security</h3>
 * <p>
 * File access is subject to the restrictions configured in {@link PoiPaths}
 * (project base directory from system property {@code IDE_PROJECT_DIR}).
 * </p>
 */
public class PoiToolBoxJsBridge implements JsModuleInterface {

    /** Shared conversion rules of the three POI namespaces. */
    private static final PoiJsArgs A = PoiJsArgs.XLSX;

    public PoiToolBoxJsBridge() {
        // Utility class
    }

    @Override
    public String getNamespace() {
        return "poi";
    }

    @Override
    public String getSummary() {
        return "`poi.help()` explains .xlsx-support";
    }

    @Override
    public String getHelpTip() {
        return "poi.help() (.xlsx)";
    }

    @Override
    public AutoCloseable wireApi(Value jsBindings) {
        // Fresh, isolated instance per tool-call. All proxy methods capture this instance,
        // so no shared (static) state is used and parallel users cannot interfere.
        PoiToolBox poi = new PoiToolBox();
        jsBindings.putMember("poi", createPoiNamespace(poi));
        // Auto-close all workbooks of this per-call instance when the JS execution ends.
        return poi::closeAllWorkbooks;
    }

    /**
     * Creates a ProxyObject representing the POI toolbox namespace for JavaScript.
     * All proxy methods operate on the given (per-call) {@link PoiToolBox} instance.
     *
     * @param poi the per-call PoiToolBox instance to bind
     * @return ProxyObject with POI toolbox methods
     */
    public static ProxyObject createPoiNamespace(PoiToolBox poi) {
        Map<String, Object> methods = new HashMap<>();

        // ---- Workbook-level ----
        methods.put("openFile", (ProxyExecutable) args ->
                poi.openFile(A.bind("openFile", args).string("path", 0)));

        methods.put("createWorkbook", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("createWorkbook", args);
            Value nameArg = c.optionalValue(0);
            if (nameArg == null || nameArg.isNull()) {
                return poi.createWorkbook();
            }
            return poi.createWorkbook(c.string("initialSheetName", 0));
        });

        methods.put("save", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("save", args);
            poi.save(c.string("wbHandle", 0), c.string("path", 1));
            return null;
        });

        methods.put("close", (ProxyExecutable) args -> {
            poi.close(A.bind("close", args).string("wbHandle", 0));
            return null;
        });

        // ---- Sheet-level ----
        methods.put("getSheetNames", (ProxyExecutable) args ->
                GraalProxies.toProxyArray((Object[]) poi.getSheetNames(workbook("getSheetNames", args))));

        methods.put("getSheet", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("getSheet", args);
            return poi.getSheet(c.string("wbHandle", 0), c.string("sheetName", 1));
        });

        methods.put("getSheetByIndex", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("getSheetByIndex", args);
            return poi.getSheetByIndex(c.string("wbHandle", 0), c.index("sheetIndex", 1));
        });

        methods.put("createSheet", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("createSheet", args);
            return poi.createSheet(c.string("wbHandle", 0), c.string("sheetName", 1));
        });

        methods.put("removeSheet", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("removeSheet", args);
            poi.removeSheet(c.string("wbHandle", 0), c.string("sheetName", 1));
            return null;
        });

        methods.put("getSheetName", (ProxyExecutable) args ->
                poi.getSheetName(sheet("getSheetName", args)));

        // ---- Cell-level ----
        methods.put("getCellValue", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("getCellValue", args);
            return poi.getCellValue(c.string("sheetHandle", 0), c.index("row", 1), c.index("col", 2));
        });

        methods.put("setCellValue", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("setCellValue", args);
            Value raw = c.optionalValue(3);
            Object value = raw == null ? null : GraalProxies.fromValue(raw);
            poi.setCellValue(c.string("sheetHandle", 0), c.index("row", 1), c.index("col", 2), value);
            return null;
        });

        methods.put("getCellType", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("getCellType", args);
            return poi.getCellType(c.string("sheetHandle", 0), c.index("row", 1), c.index("col", 2));
        });

        methods.put("getCellFormula", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("getCellFormula", args);
            return poi.getCellFormula(c.string("sheetHandle", 0), c.index("row", 1), c.index("col", 2));
        });

        // ---- Range / Data ----
        // toProxyObject (not ProxyObject.fromMap) on all result maps: fromMap hands member
        // values through unchanged, so a nested Map or List would arrive as a Java host object
        // that the CONSTRAINED sandbox cannot read. Today these two maps are flat, but the
        // wrapper must not decide that a future nested field silently becomes unreadable.
        methods.put("getUsedRange", (ProxyExecutable) args -> {
            Map<String, Object> range = poi.getUsedRange(sheet("getUsedRange", args));
            return range != null ? GraalProxies.toProxyObject(range) : null;
        });

        methods.put("getRangeAsJson", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("getRangeAsJson", args);
            Map<String, Object> rangeMap = poi.getRangeAsJson(c.string("sheetHandle", 0),
                    c.index("startRow", 1), c.index("endRow", 2), c.index("startCol", 3),
                    c.index("endCol", 4));
            return rangeMap != null ? GraalProxies.toProxyObject(rangeMap) : null;
        });

        methods.put("getRowCount", (ProxyExecutable) args ->
                poi.getRowCount(sheet("getRowCount", args)));

        methods.put("getAllData", (ProxyExecutable) args ->
                GraalProxies.toProxyArray(poi.getAllData(sheet("getAllData", args))));

        methods.put("setAllData", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("setAllData", args);
            String sheetHandle = c.string("sheetHandle", 0);
            Object[][] data = matrixArg("setAllData", c.value("data", 1,
                    "an array of rows (array of arrays)"));
            poi.setAllData(sheetHandle, data, c.optionalIndex("startRow", 2, 0),
                    c.optionalIndex("startCol", 3, 0));
            return null;
        });

        // ---- Style / Format ----
        methods.put("getCellStyle", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("getCellStyle", args);
            Map<String, Object> style = poi.getCellStyle(c.string("sheetHandle", 0),
                    c.index("row", 1), c.index("col", 2));
            return style != null ? GraalProxies.toProxyObject(style) : null;
        });

        methods.put("setCellBold", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("setCellBold", args);
            poi.setCellBold(c.string("sheetHandle", 0), c.index("row", 1), c.index("col", 2),
                    c.bool("bold", 3));
            return null;
        });

        methods.put("setCellItalic", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("setCellItalic", args);
            poi.setCellItalic(c.string("sheetHandle", 0), c.index("row", 1), c.index("col", 2),
                    c.bool("italic", 3));
            return null;
        });

        methods.put("setCellFontSize", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("setCellFontSize", args);
            poi.setCellFontSize(c.string("sheetHandle", 0), c.index("row", 1), c.index("col", 2),
                    (short) c.wholeNumber("sizePoints", 3, PoiToolBox.MIN_FONT_PT, PoiToolBox.MAX_FONT_PT));
            return null;
        });

        methods.put("setCellFontName", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("setCellFontName", args);
            poi.setCellFontName(c.string("sheetHandle", 0), c.index("row", 1), c.index("col", 2),
                    c.string("fontName", 3));
            return null;
        });

        methods.put("setCellFontColor", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("setCellFontColor", args);
            poi.setCellFontColor(c.string("sheetHandle", 0), c.index("row", 1), c.index("col", 2),
                    c.string("hexColor", 3));
            return null;
        });

        methods.put("setCellBackgroundColor", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("setCellBackgroundColor", args);
            poi.setCellBackgroundColor(c.string("sheetHandle", 0), c.index("row", 1),
                    c.index("col", 2), c.string("hexColor", 3));
            return null;
        });

        methods.put("setCellDataFormat", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("setCellDataFormat", args);
            poi.setCellDataFormat(c.string("sheetHandle", 0), c.index("row", 1), c.index("col", 2),
                    c.string("formatString", 3));
            return null;
        });

        // ---- Advanced ----
        methods.put("getMergedRegions", (ProxyExecutable) args ->
                GraalProxies.toProxyArray(poi.getMergedRegions(sheet("getMergedRegions", args))));

        methods.put("getFormulas", (ProxyExecutable) args ->
                GraalProxies.toProxyArray(poi.getFormulas(sheet("getFormulas", args))));

        methods.put("setColumnWidth", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("setColumnWidth", args);
            poi.setColumnWidth(c.string("sheetHandle", 0), c.index("col", 1),
                    c.wholeNumber("widthChars", 2, 0, PoiToolBox.MAX_COLUMN_WIDTH_CHARS));
            return null;
        });

        methods.put("autoSizeColumn", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("autoSizeColumn", args);
            poi.autoSizeColumn(c.string("sheetHandle", 0), c.index("col", 1));
            return null;
        });

        // ---- Conditional Formatting ----
        methods.put("addConditionalFormatCellIs", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("addConditionalFormatCellIs", args);
            return poi.addConditionalFormatCellIs(c.string("sheetHandle", 0), c.string("range", 1),
                    c.string("operator", 2), c.string("f1", 3), c.optionalString("f2", 4),
                    c.optionalOptions(5));
        });

        methods.put("addConditionalFormatExpression", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("addConditionalFormatExpression", args);
            return poi.addConditionalFormatExpression(c.string("sheetHandle", 0),
                    c.string("range", 1), c.string("formula", 2), c.optionalOptions(3));
        });

        methods.put("addConditionalFormatColorScale", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("addConditionalFormatColorScale", args);
            return poi.addConditionalFormatColorScale(c.string("sheetHandle", 0),
                    c.string("range", 1), c.stringArray("colors", 2));
        });

        methods.put("addConditionalFormatDataBar", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("addConditionalFormatDataBar", args);
            return poi.addConditionalFormatDataBar(c.string("sheetHandle", 0),
                    c.string("range", 1), c.string("color", 2));
        });

        methods.put("addConditionalFormatIconSet", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("addConditionalFormatIconSet", args);
            return poi.addConditionalFormatIconSet(c.string("sheetHandle", 0),
                    c.string("range", 1), c.string("iconSet", 2));
        });

        methods.put("getConditionalFormattings", (ProxyExecutable) args ->
                GraalProxies.toProxyArray(poi.getConditionalFormattings(
                        sheet("getConditionalFormattings", args))));

        methods.put("removeConditionalFormat", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("removeConditionalFormat", args);
            poi.removeConditionalFormat(c.string("sheetHandle", 0), c.string("cfHandle", 1));
            return null;
        });

        methods.put("removeAllConditionalFormats", (ProxyExecutable) args ->
                poi.removeAllConditionalFormats(sheet("removeAllConditionalFormats", args)));

        // ---- Help ----
        methods.put("help", (ProxyExecutable) args -> poi.help());

        return ProxyObject.fromMap(methods);
    }

    /**
     * The workbook handle of a one-argument call, named in the message when it is missing or
     * not a string.
     */
    private static String workbook(String fn, Value[] args) {
        return A.bind(fn, args).string("wbHandle", 0);
    }

    /** The sheet handle of a one-argument call. */
    private static String sheet(String fn, Value[] args) {
        return A.bind(fn, args).string("sheetHandle", 0);
    }

    /**
     * Reads the 2D data of {@code poi.setAllData}. A row that is not an array is an error:
     * silently writing an empty row in its place loses the caller's data without a word.
     */
    private static Object[][] matrixArg(String fn, Value data) {
        if (!data.hasArrayElements()) {
            throw A.badArg(fn, "data", data, "an array of rows (each an array of cell values)");
        }
        int rows = (int) data.getArraySize();
        Object[][] matrix = new Object[rows][];
        for (int r = 0; r < rows; r++) {
            Value rowValue = data.getArrayElement(r);
            if (rowValue == null || rowValue.isNull()) {
                matrix[r] = new Object[0];
                continue;
            }
            if (!rowValue.hasArrayElements()) {
                throw A.badArg(fn, "data[" + r + "]", rowValue,
                        "an array of cell values (or null for an empty row)");
            }
            int cols = (int) rowValue.getArraySize();
            Object[] row = new Object[cols];
            for (int col = 0; col < cols; col++) {
                row[col] = GraalProxies.fromValue(rowValue.getArrayElement(col));
            }
            matrix[r] = row;
        }
        return matrix;
    }
}
