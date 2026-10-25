package org.rogmann.mcp2sdk.poi;

import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.rogmann.mcp2sdk.js.GraalProxies;
import org.rogmann.mcp2sdk.js.JsModuleInterface;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * Bridge between GraalVM JavaScript and {@link DocxToolBox}.
 * <p>
 * Exposes Word {@code .docx} operations to JavaScript as the {@code "docx"} namespace.
 * Each call to {@link #wireApi(Value)} creates a fresh {@link DocxToolBox} instance and
 * captures it in the proxy methods (per-call isolation). The returned {@link AutoCloseable}
 * closes all documents of that instance when the call ends.
 * </p>
 *
 * <h3>Argument conversion</h3>
 * <p>
 * Every argument goes through the {@link PoiJsArgs} helpers (shimmed below), so a wrong
 * argument names the function, the argument and the valid range. GraalVM's own message
 * ({@code Cannot convert '9.5' … Invalid or lossy primitive coercion}) names none of the
 * three and used to surface raw {@link ClassCastException}s.
 * </p>
 *
 * <h3>Usage in JavaScript</h3>
 * <pre>{@code
 * var doc = docx.openFile("demo.docx");
 * var info = docx.getDocumentInfo(doc);
 * var paras = docx.getParagraphs(doc, {props: true});
 * docx.setRunFontSize(paras.paragraphs[0].runs[0].h, 9.5);
 * docx.save(doc, "demo.docx");   // must save explicitly
 * }</pre>
 */
public class DocxToolBoxJsBridge implements JsModuleInterface {

    public DocxToolBoxJsBridge() {
        // Utility class
    }

    @Override
    public String getNamespace() {
        return "docx";
    }

    @Override
    public String getSummary() {
        return "`docx.help()` explains Word .docx-support";
    }

    @Override
    public String getHelpTip() {
        return "docx.help() (.docx)";
    }

    @Override
    public AutoCloseable wireApi(Value jsBindings) {
        DocxToolBox box = new DocxToolBox();
        jsBindings.putMember("docx", createDocxNamespace(box));
        return box::closeAllDocuments;
    }

    /**
     * Creates a ProxyObject representing the docx toolbox namespace for JavaScript.
     * @param box the per-call DocxToolBox instance
     * @return ProxyObject with docx methods
     */
    public static ProxyObject createDocxNamespace(DocxToolBox box) {
        Map<String, Object> methods = new HashMap<>();

        // --- document ---
        methods.put("openFile", (ProxyExecutable) args ->
                box.openFile(stringArg("openFile", "path", arg(args, 0))));
        methods.put("createDocument", (ProxyExecutable) args -> box.createDocument());
        methods.put("save", (ProxyExecutable) args -> {
            box.save(stringArg("save", "docHandle", arg(args, 0)),
                    stringArg("save", "path", arg(args, 1)));
            return null;
        });
        methods.put("close", (ProxyExecutable) args -> {
            box.close(stringArg("close", "docHandle", arg(args, 0)));
            return null;
        });

        // --- document attachments: page setup and core properties (docs/js/docx.md §9.2/§9.3) ---
        methods.put("setPageSetup", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(box.setPageSetup(
                        stringArg("setPageSetup", "docHandle", arg(args, 0)),
                        optionsArg("setPageSetup", arg(args, 1)))));
        methods.put("setDocumentMeta", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(box.setDocumentMeta(
                        stringArg("setDocumentMeta", "docHandle", arg(args, 0)),
                        optionsArg("setDocumentMeta", arg(args, 1)))));

        // --- reading / inspection ---
        methods.put("getDocumentInfo", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(box.getDocumentInfo(stringArg("getDocumentInfo", "docHandle", arg(args, 0)))));
        methods.put("getBodyElements", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(box.getBodyElements(stringArg("getBodyElements", "docHandle", arg(args, 0)))));
        methods.put("getParagraphs", (ProxyExecutable) args -> {
            String docHandle = stringArg("getParagraphs", "docHandle", arg(args, 0));
            boolean props = optionBool("getParagraphs", arg(args, 1), "props",
                    Set.of("props", "detailed"), false);
            return GraalProxies.toProxyObject(box.getParagraphs(docHandle, props));
        });
        methods.put("getParagraphProps", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(box.getParagraphProps(stringArg("getParagraphProps", "paragraphHandle", arg(args, 0)))));
        methods.put("getRuns", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(box.getRuns(stringArg("getRuns", "paragraphHandle", arg(args, 0)))));
        methods.put("getRunProps", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(box.getRunProps(stringArg("getRunProps", "runHandle", arg(args, 0)))));
        methods.put("getFields", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(box.getFields(stringArg("getFields", "paragraphHandle", arg(args, 0)))));
        methods.put("getStylesInfo", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(box.getStylesInfo(stringArg("getStylesInfo", "docHandle", arg(args, 0)))));
        methods.put("getNumberingInfo", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(box.getNumberingInfo(stringArg("getNumberingInfo", "docHandle", arg(args, 0)))));
        methods.put("getHeadersFooters", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(box.getHeadersFooters(stringArg("getHeadersFooters", "docHandle", arg(args, 0)))));
        methods.put("getPictures", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(box.getPictures(stringArg("getPictures", "docHandle", arg(args, 0)))));
        methods.put("getShapes", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(box.getShapes(stringArg("getShapes", "docHandle", arg(args, 0)))));
        methods.put("getHyperlinks", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(box.getHyperlinks(stringArg("getHyperlinks", "docHandle", arg(args, 0)))));
        methods.put("getTables", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(box.getTables(stringArg("getTables", "docHandle", arg(args, 0)))));
        methods.put("getDocumentText", (ProxyExecutable) args -> {
            String docHandle = stringArg("getDocumentText", "docHandle", arg(args, 0));
            Value options = arg(args, 1);
            boolean tables = optionBool("getDocumentText", options, "tables", Set.of("tables", "sdt"), false);
            boolean sdt = optionBool("getDocumentText", options, "sdt", Set.of("tables", "sdt"), false);
            return box.getDocumentText(docHandle, tables, sdt);
        });
        methods.put("getParagraphText", (ProxyExecutable) args ->
                box.getParagraphText(stringArg("getParagraphText", "paragraphHandle", arg(args, 0))));

        // --- writing: paragraphs and runs ---
        methods.put("createParagraph", (ProxyExecutable) args -> {
            String docHandle = stringArg("createParagraph", "docHandle", arg(args, 0));
            String text = optionalString("createParagraph", "text", arg(args, 1));
            return box.createParagraph(docHandle, text);
        });
        methods.put("addRun", (ProxyExecutable) args -> {
            String paragraphHandle = stringArg("addRun", "paragraphHandle", arg(args, 0));
            String text = optionalString("addRun", "text", arg(args, 1));
            return box.addRun(paragraphHandle, text);
        });

        methods.put("setRunText", (ProxyExecutable) args -> {
            box.setRunText(stringArg("setRunText", "runHandle", arg(args, 0)),
                    stringArg("setRunText", "text", arg(args, 1)));
            return null;
        });
        methods.put("setRunBold", (ProxyExecutable) args -> {
            box.setRunBold(stringArg("setRunBold", "runHandle", arg(args, 0)),
                    boolArg("setRunBold", "bold", arg(args, 1)));
            return null;
        });
        methods.put("setRunItalic", (ProxyExecutable) args -> {
            box.setRunItalic(stringArg("setRunItalic", "runHandle", arg(args, 0)),
                    boolArg("setRunItalic", "italic", arg(args, 1)));
            return null;
        });
        methods.put("setRunFontSize", (ProxyExecutable) args -> {
            box.setRunFontSize(stringArg("setRunFontSize", "runHandle", arg(args, 0)),
                    numberArg("setRunFontSize", "sizePoints", arg(args, 1),
                            DocxToolBox.MIN_FONT_PT, DocxToolBox.MAX_FONT_PT));
            return null;
        });
        methods.put("setRunFontName", (ProxyExecutable) args -> {
            box.setRunFontName(stringArg("setRunFontName", "runHandle", arg(args, 0)),
                    stringArg("setRunFontName", "fontName", arg(args, 1)));
            return null;
        });
        methods.put("setRunFontColor", (ProxyExecutable) args -> {
            box.setRunFontColor(stringArg("setRunFontColor", "runHandle", arg(args, 0)),
                    stringArg("setRunFontColor", "hexColor", arg(args, 1)));
            return null;
        });

        // --- writing: paragraph formatting (M1, Aufgabe 2 - setter part) ---
        methods.put("setParagraphStyle", (ProxyExecutable) args -> {
            box.setParagraphStyle(stringArg("setParagraphStyle", "paragraphHandle", arg(args, 0)),
                    stringArg("setParagraphStyle", "style", arg(args, 1)));
            return null;
        });
        methods.put("setParagraphAlignment", (ProxyExecutable) args -> {
            box.setParagraphAlignment(stringArg("setParagraphAlignment", "paragraphHandle", arg(args, 0)),
                    stringArg("setParagraphAlignment", "alignment", arg(args, 1)));
            return null;
        });
        methods.put("setParagraphIndent", (ProxyExecutable) args -> {
            box.setParagraphIndent(stringArg("setParagraphIndent", "paragraphHandle", arg(args, 0)),
                    optionsArg("setParagraphIndent", arg(args, 1)));
            return null;
        });
        methods.put("setParagraphSpacing", (ProxyExecutable) args -> {
            box.setParagraphSpacing(stringArg("setParagraphSpacing", "paragraphHandle", arg(args, 0)),
                    optionsArg("setParagraphSpacing", arg(args, 1)));
            return null;
        });
        methods.put("listStyleKeys", (ProxyExecutable) args ->
                GraalProxies.toProxyArray(box.listStyleKeys(
                        stringArg("listStyleKeys", "docHandle", arg(args, 0)))));

        // --- writing: styles and numbering parts (M1, Aufgabe 2 and 3) ---
        methods.put("ensureStyles", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(box.ensureStyles(
                        stringArg("ensureStyles", "docHandle", arg(args, 0)))));
        methods.put("ensureNumbering", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(box.ensureNumbering(
                        stringArg("ensureNumbering", "docHandle", arg(args, 0)))));
        methods.put("setParagraphNumbering", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(box.setParagraphNumbering(
                        stringArg("setParagraphNumbering", "paragraphHandle", arg(args, 0)),
                        optionsArg("setParagraphNumbering", arg(args, 1)))));
        methods.put("removeParagraphNumbering", (ProxyExecutable) args -> {
            box.removeParagraphNumbering(stringArg("removeParagraphNumbering", "paragraphHandle",
                    arg(args, 0)));
            return null;
        });

        // --- writing: tables (M1, Aufgabe 1) ---
        methods.put("createTable", (ProxyExecutable) args -> box.createTable(
                stringArg("createTable", "docHandle", arg(args, 0)),
                indexArg("createTable", "rows", arg(args, 1)),
                indexArg("createTable", "cols", arg(args, 2)),
                optionsArg("createTable", arg(args, 3))));
        methods.put("setCellText", (ProxyExecutable) args -> {
            String fn = "setCellText";
            String docHandle = stringArg(fn, "docHandle", arg(args, 0));
            Value tableRef = arg(args, 1);
            int rowIndex = indexArg(fn, "rowIndex", arg(args, 2));
            int colIndex = indexArg(fn, "colIndex", arg(args, 3));
            String text = optionalString(fn, "text", arg(args, 4));
            Map<String, Object> options = optionsArg(fn, arg(args, 5));
            if (isTableHandle(tableRef)) {
                box.setCellTextByHandle(docHandle, tableRef.asString(), rowIndex, colIndex, text, options);
            } else {
                box.setCellText(docHandle, indexArg(fn, "tableIndex", tableRef),
                        rowIndex, colIndex, text, options);
            }
            return null;
        });
        methods.put("getCellProps", (ProxyExecutable) args -> {
            String fn = "getCellProps";
            String docHandle = stringArg(fn, "docHandle", arg(args, 0));
            Value tableRef = arg(args, 1);
            int rowIndex = indexArg(fn, "rowIndex", arg(args, 2));
            int colIndex = indexArg(fn, "colIndex", arg(args, 3));
            Map<String, Object> props = isTableHandle(tableRef)
                    ? box.getCellPropsByHandle(docHandle, tableRef.asString(), rowIndex, colIndex)
                    : box.getCellProps(docHandle, indexArg(fn, "tableIndex", tableRef), rowIndex, colIndex);
            return GraalProxies.toProxyObject(props);
        });
        methods.put("addRow", (ProxyExecutable) args -> {
            String fn = "addRow";
            String docHandle = stringArg(fn, "docHandle", arg(args, 0));
            Value tableRef = arg(args, 1);
            String[] cellTexts = stringArrayArg(fn, "cellTexts", arg(args, 2));
            if (isTableHandle(tableRef)) {
                box.addRow(docHandle, tableRef.asString(), cellTexts);
            } else {
                box.addRow(docHandle, indexArg(fn, "tableIndex", tableRef), cellTexts);
            }
            return null;
        });
        methods.put("removeRow", (ProxyExecutable) args -> {
            box.removeRow(stringArg("removeRow", "docHandle", arg(args, 0)),
                    indexArg("removeRow", "tableIndex", arg(args, 1)),
                    indexArg("removeRow", "rowIndex", arg(args, 2)));
            return null;
        });
        methods.put("setRowFontColor", (ProxyExecutable) args -> {
            box.setRowFontColor(stringArg("setRowFontColor", "docHandle", arg(args, 0)),
                    indexArg("setRowFontColor", "tableIndex", arg(args, 1)),
                    indexArg("setRowFontColor", "rowIndex", arg(args, 2)),
                    stringArg("setRowFontColor", "hexColor", arg(args, 3)));
            return null;
        });
        methods.put("setCellFontColor", (ProxyExecutable) args -> {
            box.setCellFontColor(stringArg("setCellFontColor", "docHandle", arg(args, 0)),
                    indexArg("setCellFontColor", "tableIndex", arg(args, 1)),
                    indexArg("setCellFontColor", "rowIndex", arg(args, 2)),
                    indexArg("setCellFontColor", "colIndex", arg(args, 3)),
                    stringArg("setCellFontColor", "hexColor", arg(args, 4)));
            return null;
        });

        // --- writing: table fine-tuning (M2, Aufgabe 7 - docs/js/docx.md §9.8) ---
        // Every call takes a table reference (index or "t-n" handle) and answers with the
        // table properties, so the caller sees what was written without reopening the file.
        methods.put("getTableProps", (ProxyExecutable) args -> {
            String fn = "getTableProps";
            String doc = stringArg(fn, "docHandle", arg(args, 0));
            return tableCall(fn, args, i -> box.getTableProps(doc, i),
                    h -> box.getTablePropsByHandle(doc, h));
        });
        methods.put("setTableAlignment", (ProxyExecutable) args -> {
            String fn = "setTableAlignment";
            String doc = stringArg(fn, "docHandle", arg(args, 0));
            String alignment = stringArg(fn, "alignment", arg(args, 2));
            return tableCall(fn, args, i -> box.setTableAlignment(doc, i, alignment),
                    h -> box.setTableAlignmentByHandle(doc, h, alignment));
        });
        methods.put("setTableWidth", (ProxyExecutable) args -> {
            String fn = "setTableWidth";
            String doc = stringArg(fn, "docHandle", arg(args, 0));
            String spec = stringArg(fn, "spec", arg(args, 2));
            return tableCall(fn, args, i -> box.setTableWidth(doc, i, spec),
                    h -> box.setTableWidthByHandle(doc, h, spec));
        });
        methods.put("setTableLayout", (ProxyExecutable) args -> {
            String fn = "setTableLayout";
            String doc = stringArg(fn, "docHandle", arg(args, 0));
            String layout = stringArg(fn, "layout", arg(args, 2));
            return tableCall(fn, args, i -> box.setTableLayout(doc, i, layout),
                    h -> box.setTableLayoutByHandle(doc, h, layout));
        });
        methods.put("setTableBorders", (ProxyExecutable) args -> {
            String fn = "setTableBorders";
            String doc = stringArg(fn, "docHandle", arg(args, 0));
            Map<String, Object> edges = nestedOptionsArg(fn, arg(args, 2));
            return tableCall(fn, args, i -> box.setTableBorders(doc, i, edges),
                    h -> box.setTableBordersByHandle(doc, h, edges));
        });
        methods.put("setRowHeight", (ProxyExecutable) args -> {
            String fn = "setRowHeight";
            String doc = stringArg(fn, "docHandle", arg(args, 0));
            int rowIndex = indexArg(fn, "rowIndex", arg(args, 2));
            Map<String, Object> options = optionsArg(fn, arg(args, 3));
            return tableCall(fn, args, i -> box.setRowHeight(doc, i, rowIndex, options),
                    h -> box.setRowHeightByHandle(doc, h, rowIndex, options));
        });
        methods.put("setRowCantSplit", (ProxyExecutable) args -> {
            String fn = "setRowCantSplit";
            String doc = stringArg(fn, "docHandle", arg(args, 0));
            int rowIndex = indexArg(fn, "rowIndex", arg(args, 2));
            boolean cantSplit = boolArg(fn, "cantSplit", arg(args, 3));
            return tableCall(fn, args, i -> box.setRowCantSplit(doc, i, rowIndex, cantSplit),
                    h -> box.setRowCantSplitByHandle(doc, h, rowIndex, cantSplit));
        });
        methods.put("setRepeatHeaderRows", (ProxyExecutable) args -> {
            String fn = "setRepeatHeaderRows";
            String doc = stringArg(fn, "docHandle", arg(args, 0));
            int rows = indexArg(fn, "rows", arg(args, 2));
            return tableCall(fn, args, i -> box.setRepeatHeaderRows(doc, i, rows),
                    h -> box.setRepeatHeaderRowsByHandle(doc, h, rows));
        });
        methods.put("setCellBorders", (ProxyExecutable) args -> {
            String fn = "setCellBorders";
            String doc = stringArg(fn, "docHandle", arg(args, 0));
            CellRef cell = cellRef(fn, arg(args, 2), arg(args, 3));
            Map<String, Object> edges = nestedOptionsArg(fn, arg(args, 4));
            return tableCall(fn, args, i -> box.setCellBorders(doc, i, cell.row(), cell.col(), edges),
                    h -> box.setCellBordersByHandle(doc, h, cell.row(), cell.col(), edges));
        });
        methods.put("setCellWidth", (ProxyExecutable) args -> {
            String fn = "setCellWidth";
            String doc = stringArg(fn, "docHandle", arg(args, 0));
            CellRef cell = cellRef(fn, arg(args, 2), arg(args, 3));
            String spec = stringArg(fn, "spec", arg(args, 4));
            return tableCall(fn, args, i -> box.setCellWidth(doc, i, cell.row(), cell.col(), spec),
                    h -> box.setCellWidthByHandle(doc, h, cell.row(), cell.col(), spec));
        });
        methods.put("setCellShading", (ProxyExecutable) args -> {
            String fn = "setCellShading";
            String doc = stringArg(fn, "docHandle", arg(args, 0));
            CellRef cell = cellRef(fn, arg(args, 2), arg(args, 3));
            String color = stringArg(fn, "shading", arg(args, 4));
            return tableCall(fn, args,
                    i -> box.setCellShading(doc, i, cell.row(), cell.col(), color),
                    h -> box.setCellShadingByHandle(doc, h, cell.row(), cell.col(), color));
        });
        methods.put("setCellValign", (ProxyExecutable) args -> {
            String fn = "setCellValign";
            String doc = stringArg(fn, "docHandle", arg(args, 0));
            CellRef cell = cellRef(fn, arg(args, 2), arg(args, 3));
            String valign = stringArg(fn, "valign", arg(args, 4));
            return tableCall(fn, args,
                    i -> box.setCellValign(doc, i, cell.row(), cell.col(), valign),
                    h -> box.setCellValignByHandle(doc, h, cell.row(), cell.col(), valign));
        });
        methods.put("mergeCells", (ProxyExecutable) args -> {
            String fn = "mergeCells";
            String doc = stringArg(fn, "docHandle", arg(args, 0));
            int fromRow = indexArg(fn, "fromRow", arg(args, 2));
            int fromCol = indexArg(fn, "fromCol", arg(args, 3));
            int toRow = indexArg(fn, "toRow", arg(args, 4));
            int toCol = indexArg(fn, "toCol", arg(args, 5));
            return tableCall(fn, args,
                    i -> box.mergeCells(doc, i, fromRow, fromCol, toRow, toCol),
                    h -> box.mergeCellsByHandle(doc, h, fromRow, fromCol, toRow, toCol));
        });

        // --- writing: drawings (docs/js/docx.md §1.4) ---
        methods.put("createDrawing", (ProxyExecutable) args -> box.createDrawing(
                stringArg("createDrawing", "paragraphHandle", arg(args, 0)),
                optionsArg("createDrawing", arg(args, 1))));
        methods.put("addShape", (ProxyExecutable) args -> box.addShape(
                stringArg("addShape", "targetHandle", arg(args, 0)),
                optionsArg("addShape", arg(args, 1))));
        methods.put("addShapeGroup", (ProxyExecutable) args -> box.addShapeGroup(
                stringArg("addShapeGroup", "targetHandle", arg(args, 0)),
                optionsArg("addShapeGroup", arg(args, 1))));
        methods.put("createShapes", (ProxyExecutable) args -> {
            String fn = "createShapes";
            String paragraphHandle = stringArg(fn, "paragraphHandle", arg(args, 0));
            Map<String, Object> options = optionsArg(fn, arg(args, 1));
            return box.createShapes(paragraphHandle, options, optionsArray(fn, "shapes", arg(args, 2)));
        });
        methods.put("setShapeFill", (ProxyExecutable) args -> {
            box.setShapeFill(stringArg("setShapeFill", "shapeHandle", arg(args, 0)),
                    stringArg("setShapeFill", "color", arg(args, 1)));
            return null;
        });
        methods.put("setShapeLine", (ProxyExecutable) args -> {
            box.setShapeLine(stringArg("setShapeLine", "shapeHandle", arg(args, 0)),
                    optionsArg("setShapeLine", arg(args, 1)));
            return null;
        });
        methods.put("removeShape", (ProxyExecutable) args -> {
            box.removeShape(stringArg("removeShape", "shapeHandle", arg(args, 0)));
            return null;
        });

        // --- comments: read and remove; writing new ones is an open point (docs/js/docx.md §9.7) ---
        methods.put("getComments", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(box.getComments(
                        stringArg("getComments", "docHandle", arg(args, 0)))));
        methods.put("removeComment", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(box.removeComment(
                        stringArg("removeComment", "docHandle", arg(args, 0)),
                        stringArg("removeComment", "commentId", arg(args, 1)))));

        methods.put("help", (ProxyExecutable) args -> box.help());

        return ProxyObject.fromMap(methods);
    }

    // ========================================================================
    // Argument helpers - implemented once for all POI namespaces in PoiJsArgs
    // ========================================================================

    /**
     * The shared conversion rules. These shims only pin the namespace and keep the call
     * sites above short; the behaviour (and every error text) lives in {@link PoiJsArgs}.
     */
    private static final PoiJsArgs A = PoiJsArgs.DOCX;

    private static Value arg(Value[] args, int index) {
        return PoiJsArgs.arg(args, index);
    }

    private static String stringArg(String fn, String name, Value value) {
        return A.stringArg(fn, name, value);
    }

    private static String optionalString(String fn, String name, Value value) {
        return A.optionalString(fn, name, value);
    }

    private static boolean boolArg(String fn, String name, Value value) {
        return A.boolArg(fn, name, value);
    }

    private static int indexArg(String fn, String name, Value value) {
        return A.indexArg(fn, name, value);
    }

    private static double numberArg(String fn, String name, Value value, double min, double max) {
        return A.numberArg(fn, name, value, min, max);
    }

    private static boolean optionBool(String fn, Value options, String key, Set<String> knownKeys,
                                      boolean fallback) {
        return A.optionBool(fn, options, key, knownKeys, fallback);
    }

    private static String[] stringArrayArg(String fn, String name, Value value) {
        return A.stringArrayArg(fn, name, value);
    }

    private static Map<String, Object> optionsArg(String fn, Value value) {
        return A.optionsArg(fn, value);
    }

    /**
     * An array of option objects - the shapes of {@code docx.createShapes(doc, opts, shapes)}.
     * {@link PoiJsArgs#toPlain} stops at flat values on purpose, so each element is flattened
     * here with the very same rules.
     */
    private static List<Map<String, Object>> optionsArray(String fn, Value value) {
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (!value.hasArrayElements()) {
            throw A.badArg(fn, "shapes", value, "an array of objects, e.g. [{shape: \"ellipse\", "
                    + "widthCm: 2, heightCm: 2}]");
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int i = 0; i < value.getArraySize(); i++) {
            Value element = value.getArrayElement(i);
            if (element == null || element.isNull() || !element.hasMembers()) {
                throw A.badArg(fn, "shapes[" + i + "]", element, "an object ({shape: ..., ...})");
            }
            entries.add(A.optionalOptionsArg(fn, element));
        }
        return entries;
    }

    /**
     * Reads an array of option objects, e.g. the shapes of
     * {@code docx.createShapes(par, {widthCm…}, [{shape…}, …])}. {@link PoiJsArgs#toPlain}
     * stops at flat values on purpose, so every element is flattened with the same rules here.
     */
    private static List<Map<String, Object>> optionsArray(String fn, String name, Value value) {
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (!value.hasArrayElements()) {
            throw A.badArg(fn, name, value, "an array of objects, e.g. [{shape: \"ellipse\", "
                    + "widthCm: 2, heightCm: 2}]");
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int i = 0; i < value.getArraySize(); i++) {
            Value element = value.getArrayElement(i);
            if (element == null || element.isNull() || !element.hasMembers()) {
                throw A.badArg(fn, name + "[" + i + "]", element, "an object ({shape: …, …})");
            }
            entries.add(A.optionalOptionsArg(fn, element));
        }
        return entries;
    }

    /** A table reference may be an index (0, 1, …) or a "t-n" handle - docx only. */
    private static boolean isTableHandle(Value value) {
        return value != null && value.isString() && value.asString().startsWith("t-");
    }

    /** Row and column of a cell call, converted once instead of in two branches. */
    private record CellRef(int row, int col) { }

    private static CellRef cellRef(String fn, Value rowIndex, Value colIndex) {
        return new CellRef(indexArg(fn, "rowIndex", rowIndex), indexArg(fn, "colIndex", colIndex));
    }

    /**
     * Dispatches a call that addresses one table by index or by "t-n" handle, and answers with
     * the properties of that table (so the caller reads back what it just wrote).
     * <p>
     * Only the taken branch runs, which matters: {@link #indexArg} would refuse a handle and
     * the handle overload would refuse an index.
     * </p>
     */
    private static Object tableCall(String fn, Value[] args, IntFunction<Map<String, Object>> byIndex,
                                    Function<String, Map<String, Object>> byHandle) {
        Value tableRef = arg(args, 1);
        Map<String, Object> result = isTableHandle(tableRef)
                ? byHandle.apply(tableRef.asString())
                : byIndex.apply(indexArg(fn, "tableIndex", tableRef));
        return GraalProxies.toProxyObject(result);
    }

    /**
     * Options with one nesting level - {@code docx.setTableBorders(doc, t, {top: {style:
     * "single", widthPt: 1}})}. {@link PoiJsArgs#toPlain} stops at flat values on purpose (its
     * own error says so), so the objects inside are flattened here with the same rules:
     * strings, numbers, booleans and arrays stay what they are, a host object stays an error.
     * A null value is dropped, which the toolbox documents as "leave that edge alone" - to
     * switch a border off, the style name {@code "none"} is the one that means it.
     */
    private static Map<String, Object> nestedOptionsArg(String fn, Value value) {
        if (value == null || value.isNull()) {
            return Map.of();
        }
        if (!value.hasMembers() || value.hasArrayElements()) {
            throw A.badArg(fn, "options", value, "an object ({top: {style: \"single\"}, ...})");
        }
        Map<String, Object> options = new LinkedHashMap<>();
        for (String key : value.getMemberKeys()) {
            Object plain = plainOrNested(fn, key, value.getMember(key));
            if (plain != null) {
                options.put(key, plain);
            }
        }
        return options;
    }

    /** One value of {@link #nestedOptionsArg}: a flat value, or one more object of them. */
    private static Object plainOrNested(String fn, String name, Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isNumber()) {
            return value.asDouble();
        }
        if (value.isString()) {
            return value.asString();
        }
        if (value.hasArrayElements()) {
            List<Object> entries = new ArrayList<>();
            for (int i = 0; i < value.getArraySize(); i++) {
                entries.add(plainOrNested(fn, name + "[" + i + "]", value.getArrayElement(i)));
            }
            return entries;
        }
        if (value.hasMembers()) {
            return nestedOptionsArg(fn + "." + name, value);
        }
        throw A.badArg(fn, name, value, "a string, number, boolean, array or object");
    }
}
