package org.rogmann.mcp2sdk.poi;

import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.rogmann.mcp2sdk.js.GraalProxies;
import org.rogmann.mcp2sdk.js.JsModuleInterface;

import java.util.HashMap;
import java.util.Map;

/**
 * Bridge between GraalVM JavaScript and {@link DocxToolBox}.
 * <p>
 * Exposes Word {@code .docx} operations to JavaScript as the {@code "docx"} namespace.
 * Each call to {@link #wireApi(Value)} creates a fresh {@link DocxToolBox} instance and
 * captures it in the proxy methods (per-call isolation). The returned {@link AutoCloseable}
 * closes all documents of that instance when the call ends.
 * </p>
 *
 * <h3>Usage in JavaScript</h3>
 * <pre>{@code
 * var doc = docx.openFile("demo.docx");
 * var paras = docx.getParagraphs(doc);
 * var ph = paras.paragraphs[0].h;
 * docx.setRunBold(paras.paragraphs[0].runs[0].h, true);
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

        methods.put("openFile", (ProxyExecutable) args -> box.openFile(args[0].asString()));
        methods.put("createDocument", (ProxyExecutable) args -> box.createDocument());
        methods.put("save", (ProxyExecutable) args -> {
            box.save(args[0].asString(), args[1].asString());
            return null;
        });
        methods.put("close", (ProxyExecutable) args -> {
            box.close(args[0].asString());
            return null;
        });

        methods.put("getParagraphs", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(box.getParagraphs(args[0].asString())));
        methods.put("getDocumentText", (ProxyExecutable) args -> box.getDocumentText(args[0].asString()));
        methods.put("getTables", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(box.getTables(args[0].asString())));
        methods.put("addRow", (ProxyExecutable) args -> {
            String docHandle = args[0].asString();
            int tableIndex = args[1].asInt();
            Value textArr = args[2];
            int len = (int) textArr.getArraySize();
            String[] cellTexts = new String[len];
            for (int i = 0; i < len; i++) {
                cellTexts[i] = textArr.getArrayElement(i).asString();
            }
            box.addRow(docHandle, tableIndex, cellTexts);
            return null;
        });
        methods.put("removeRow", (ProxyExecutable) args -> {
            box.removeRow(args[0].asString(), args[1].asInt(), args[2].asInt());
            return null;
        });
        methods.put("setRowFontColor", (ProxyExecutable) args -> {
            box.setRowFontColor(args[0].asString(), args[1].asInt(), args[2].asInt(), args[3].asString());
            return null;
        });
        methods.put("setCellFontColor", (ProxyExecutable) args -> {
            box.setCellFontColor(args[0].asString(), args[1].asInt(), args[2].asInt(), args[3].asInt(), args[4].asString());
            return null;
        });
        methods.put("createParagraph", (ProxyExecutable) args -> {
            String text = args.length > 1 && !args[1].isNull() ? args[1].asString() : "";
            return box.createParagraph(args[0].asString(), text);
        });
        methods.put("addRun", (ProxyExecutable) args -> {
            String text = args.length > 1 && !args[1].isNull() ? args[1].asString() : "";
            return box.addRun(args[0].asString(), text);
        });
        methods.put("getParagraphText", (ProxyExecutable) args -> box.getParagraphText(args[0].asString()));

        methods.put("setRunText", (ProxyExecutable) args -> {
            box.setRunText(args[0].asString(), args[1].asString());
            return null;
        });
        methods.put("setRunBold", (ProxyExecutable) args -> {
            box.setRunBold(args[0].asString(), args[1].asBoolean());
            return null;
        });
        methods.put("setRunItalic", (ProxyExecutable) args -> {
            box.setRunItalic(args[0].asString(), args[1].asBoolean());
            return null;
        });
        methods.put("setRunFontSize", (ProxyExecutable) args -> {
            box.setRunFontSize(args[0].asString(), args[1].asInt());
            return null;
        });
        methods.put("setRunFontName", (ProxyExecutable) args -> {
            box.setRunFontName(args[0].asString(), args[1].asString());
            return null;
        });
        methods.put("setRunFontColor", (ProxyExecutable) args -> {
            box.setRunFontColor(args[0].asString(), args[1].asString());
            return null;
        });

        methods.put("help", (ProxyExecutable) args -> box.help());

        return ProxyObject.fromMap(methods);
    }
}
