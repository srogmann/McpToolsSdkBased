package org.rogmann.mcp2sdk.poi;

import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyArray;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.rogmann.mcp2sdk.js.GraalProxies;
import org.rogmann.mcp2sdk.js.JsModuleInterface;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
 * File access is subject to the restrictions configured in {@link PoiToolBox}
 * (project base directory from system property {@code IDE_PROJECT_DIR}).
 * </p>
 */
public class PoiToolBoxJsBridge implements JsModuleInterface {

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
                poi.openFile(args[0].asString()));

        methods.put("createWorkbook", (ProxyExecutable) args -> {
            if (args.length > 0 && !args[0].isNull()) {
                return poi.createWorkbook(args[0].asString());
            }
            return poi.createWorkbook();
        });

        methods.put("save", (ProxyExecutable) args -> {
            poi.save(args[0].asString(), args[1].asString());
            return null;
        });

        methods.put("close", (ProxyExecutable) args -> {
            poi.close(args[0].asString());
            return null;
        });

        // ---- Sheet-level ----
        methods.put("getSheetNames", (ProxyExecutable) args ->
                ProxyArray.fromArray((Object[]) poi.getSheetNames(args[0].asString())));

        methods.put("getSheet", (ProxyExecutable) args ->
                poi.getSheet(args[0].asString(), args[1].asString()));

        methods.put("getSheetByIndex", (ProxyExecutable) args ->
                poi.getSheetByIndex(args[0].asString(), args[1].asInt()));

        methods.put("createSheet", (ProxyExecutable) args ->
                poi.createSheet(args[0].asString(), args[1].asString()));

        methods.put("removeSheet", (ProxyExecutable) args -> {
            poi.removeSheet(args[0].asString(), args[1].asString());
            return null;
        });

        methods.put("getSheetName", (ProxyExecutable) args ->
                poi.getSheetName(args[0].asString()));

        // ---- Cell-level ----
        methods.put("getCellValue", (ProxyExecutable) args ->
                poi.getCellValue(args[0].asString(), args[1].asInt(), args[2].asInt()));

        methods.put("setCellValue", (ProxyExecutable) args -> {
            String sheetHandle = args[0].asString();
            int row = args[1].asInt();
            int col = args[2].asInt();
            Object value = args.length > 3 ? GraalProxies.fromValue(args[3]) : null;
            poi.setCellValue(sheetHandle, row, col, value);
            return null;
        });

        methods.put("getCellType", (ProxyExecutable) args ->
                poi.getCellType(args[0].asString(), args[1].asInt(), args[2].asInt()));

        methods.put("getCellFormula", (ProxyExecutable) args ->
                poi.getCellFormula(args[0].asString(), args[1].asInt(), args[2].asInt()));

        // ---- Range / Data ----
        methods.put("getUsedRange", (ProxyExecutable) args -> {
            Map<String, Object> range = poi.getUsedRange(args[0].asString());
            return range != null ? ProxyObject.fromMap(range) : null;
        });

        methods.put("getRangeAsJson", (ProxyExecutable) args -> {
            Map<String, Object> rangeMap = poi.getRangeAsJson(args[0].asString(),
                    args[1].asInt(), args[2].asInt(), args[3].asInt(), args[4].asInt());
            return rangeMap != null ? GraalProxies.toProxyObject(rangeMap) : null;
        });

        methods.put("getRowCount", (ProxyExecutable) args ->
                poi.getRowCount(args[0].asString()));

        methods.put("getAllData", (ProxyExecutable) args ->
                poi.getAllData(args[0].asString()));

        methods.put("setAllData", (ProxyExecutable) args -> {
            String sheetHandle = args[0].asString();
            Value dataVal = args[1];
            int rows = (int) dataVal.getArraySize();
            Object[][] data = new Object[rows][];
            for (int r = 0; r < rows; r++) {
                Value rowVal = dataVal.getArrayElement(r);
                if (rowVal != null && rowVal.hasArrayElements()) {
                    int cols = (int) rowVal.getArraySize();
                    data[r] = new Object[cols];
                    for (int c = 0; c < cols; c++) {
                        data[r][c] = GraalProxies.fromValue(rowVal.getArrayElement(c));
                    }
                } else {
                    data[r] = new Object[0];
                }
            }
            int startRow = args.length > 2 ? args[2].asInt() : 0;
            int startCol = args.length > 3 ? args[3].asInt() : 0;
            poi.setAllData(sheetHandle, data, startRow, startCol);
            return null;
        });

        // ---- Style / Format ----
        methods.put("getCellStyle", (ProxyExecutable) args -> {
            Map<String, Object> style = poi.getCellStyle(args[0].asString(),
                    args[1].asInt(), args[2].asInt());
            return style != null ? ProxyObject.fromMap(style) : null;
        });

        methods.put("setCellBold", (ProxyExecutable) args -> {
            poi.setCellBold(args[0].asString(), args[1].asInt(), args[2].asInt(), args[3].asBoolean());
            return null;
        });

        methods.put("setCellItalic", (ProxyExecutable) args -> {
            poi.setCellItalic(args[0].asString(), args[1].asInt(), args[2].asInt(), args[3].asBoolean());
            return null;
        });

        methods.put("setCellFontSize", (ProxyExecutable) args -> {
            poi.setCellFontSize(args[0].asString(), args[1].asInt(), args[2].asInt(), (short) args[3].asInt());
            return null;
        });

        methods.put("setCellFontName", (ProxyExecutable) args -> {
            poi.setCellFontName(args[0].asString(), args[1].asInt(), args[2].asInt(), args[3].asString());
            return null;
        });

        methods.put("setCellFontColor", (ProxyExecutable) args -> {
            poi.setCellFontColor(args[0].asString(), args[1].asInt(), args[2].asInt(), args[3].asString());
            return null;
        });

        methods.put("setCellBackgroundColor", (ProxyExecutable) args -> {
            poi.setCellBackgroundColor(args[0].asString(), args[1].asInt(), args[2].asInt(), args[3].asString());
            return null;
        });

        methods.put("setCellDataFormat", (ProxyExecutable) args -> {
            poi.setCellDataFormat(args[0].asString(), args[1].asInt(), args[2].asInt(), args[3].asString());
            return null;
        });

        // ---- Advanced ----
        methods.put("getMergedRegions", (ProxyExecutable) args -> {
            Map<String, Object>[] regions = poi.getMergedRegions(args[0].asString());
            if (regions == null) return null;
            List<ProxyObject> result = new ArrayList<>();
            for (Map<String, Object> region : regions) {
                result.add(ProxyObject.fromMap(region));
            }
            return result.toArray();
        });

        methods.put("getFormulas", (ProxyExecutable) args -> {
            Map<String, String>[] formulas = poi.getFormulas(args[0].asString());
            if (formulas == null) return null;
            List<ProxyObject> result = new ArrayList<>();
            for (Map<String, String> formula : formulas) {
                result.add(ProxyObject.fromMap(new LinkedHashMap<>(formula)));
            }
            return result.toArray();
        });

        methods.put("setColumnWidth", (ProxyExecutable) args -> {
            poi.setColumnWidth(args[0].asString(), args[1].asInt(), args[2].asInt());
            return null;
        });

        methods.put("autoSizeColumn", (ProxyExecutable) args -> {
            poi.autoSizeColumn(args[0].asString(), args[1].asInt());
            return null;
        });

        // ---- Help ----
        methods.put("help", (ProxyExecutable) args -> poi.help());

        return ProxyObject.fromMap(methods);
    }
}
