package org.rogmann.mcp2sdk.poi;

import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.rogmann.mcp2sdk.js.GraalProxies;
import org.rogmann.mcp2sdk.js.JsModuleInterface;

import java.util.HashMap;
import java.util.Map;

/**
 * Bridge between GraalVM JavaScript and {@link PptxToolBox}.
 * <p>
 * Exposes PowerPoint {@code .pptx} operations to JavaScript as the {@code "pptx"} namespace.
 * Each call to {@link #wireApi(Value)} creates a fresh {@link PptxToolBox} instance and
 * captures it in the proxy methods (per-call isolation). The returned {@link AutoCloseable}
 * closes all presentations of that instance when the call ends.
 * </p>
 *
 * <h3>Usage in JavaScript</h3>
 * <pre>{@code
 * var show = pptx.openFile("demo.pptx");
 * var slides = pptx.getSlides(show);
 * var sp = slides.slides[0].shapes[0].h;
 * pptx.setShapeText(sp, "Neuer Titel");
 * pptx.save(show, "demo.pptx");   // must save explicitly
 * }</pre>
 */
public class PptxToolBoxJsBridge implements JsModuleInterface {

    public PptxToolBoxJsBridge() {
        // Utility class
    }

    @Override
    public String getNamespace() {
        return "pptx";
    }

    @Override
    public String getSummary() {
        return "`pptx.help()` explains PowerPoint .pptx-support";
    }

    @Override
    public String getHelpTip() {
        return "pptx.help() (.pptx)";
    }

    @Override
    public AutoCloseable wireApi(Value jsBindings) {
        PptxToolBox box = new PptxToolBox();
        jsBindings.putMember("pptx", createPptxNamespace(box));
        return box::closeAllPresentations;
    }

    /**
     * Creates a ProxyObject representing the pptx toolbox namespace for JavaScript.
     * @param box the per-call PptxToolBox instance
     * @return ProxyObject with pptx methods
     */
    public static ProxyObject createPptxNamespace(PptxToolBox box) {
        Map<String, Object> methods = new HashMap<>();

        methods.put("openFile", (ProxyExecutable) args -> box.openFile(args[0].asString()));
        methods.put("createPresentation", (ProxyExecutable) args -> box.createPresentation());
        methods.put("save", (ProxyExecutable) args -> {
            box.save(args[0].asString(), args[1].asString());
            return null;
        });
        methods.put("close", (ProxyExecutable) args -> {
            box.close(args[0].asString());
            return null;
        });

        methods.put("getSlides", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(box.getSlides(args[0].asString())));
        methods.put("getPageSize", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(box.getPageSize(args[0].asString())));
        methods.put("createSlide", (ProxyExecutable) args -> box.createSlide(args[0].asString()));
        methods.put("createBlankSlide", (ProxyExecutable) args -> box.createBlankSlide(args[0].asString()));
        methods.put("getLayouts", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(box.getLayouts(args[0].asString())));
        methods.put("createSlideFromLayout", (ProxyExecutable) args ->
                box.createSlideFromLayout(args[0].asString(), args[1].asString()));
        methods.put("createSlideLike", (ProxyExecutable) args ->
                box.createSlideLike(args[0].asString(), args[1].asString()));
        methods.put("duplicateSlide", (ProxyExecutable) args ->
                box.duplicateSlide(args[0].asString(), args[1].asString()));
        methods.put("createTextBox", (ProxyExecutable) args -> {
            String text = args.length > 1 && !args[1].isNull() ? args[1].asString() : "";
            return box.createTextBox(args[0].asString(), text);
        });
        methods.put("getShapeText", (ProxyExecutable) args -> box.getShapeText(args[0].asString()));
        methods.put("setShapeText", (ProxyExecutable) args -> {
            box.setShapeText(args[0].asString(), args[1].asString());
            return null;
        });
        methods.put("appendParagraph", (ProxyExecutable) args -> {
            box.appendParagraph(args[0].asString(), args[1].asString());
            return null;
        });

        methods.put("addTextBox", (ProxyExecutable) args -> {
            String text = args.length > 1 && !args[1].isNull() ? args[1].asString() : "";
            return box.addTextBox(args[0].asString(), text,
                    args[2].asDouble(), args[3].asDouble(), args[4].asDouble(), args[5].asDouble());
        });
        methods.put("createShape", (ProxyExecutable) args -> {
            String text = args.length > 1 && !args[1].isNull() ? args[1].asString() : "";
            return box.createShape(args[0].asString(), text,
                    args[2].asDouble(), args[3].asDouble(), args[4].asDouble(), args[5].asDouble());
        });
        methods.put("setShapePosition", (ProxyExecutable) args -> {
            box.setShapePosition(args[0].asString(), args[1].asDouble(), args[2].asDouble());
            return null;
        });
        methods.put("setShapeSize", (ProxyExecutable) args -> {
            box.setShapeSize(args[0].asString(), args[1].asDouble(), args[2].asDouble());
            return null;
        });
        methods.put("setShapeRotation", (ProxyExecutable) args -> {
            box.setShapeRotation(args[0].asString(), args[1].asDouble());
            return null;
        });
        methods.put("setShapeGeometry", (ProxyExecutable) args -> {
            box.setShapeGeometry(args[0].asString(), args[1].asString());
            return null;
        });
        methods.put("removeShape", (ProxyExecutable) args -> {
            box.removeShape(args[0].asString());
            return null;
        });
        methods.put("setShapeFillColor", (ProxyExecutable) args -> {
            box.setShapeFillColor(args[0].asString(), args[1].asString());
            return null;
        });
        methods.put("setShapeLineColor", (ProxyExecutable) args -> {
            box.setShapeLineColor(args[0].asString(), args[1].asString());
            return null;
        });
        methods.put("setShapeLineWidth", (ProxyExecutable) args -> {
            box.setShapeLineWidth(args[0].asString(), args[1].asDouble());
            return null;
        });
        methods.put("createLine", (ProxyExecutable) args -> box.createLine(args[0].asString(),
                args[1].asDouble(), args[2].asDouble(), args[3].asDouble(), args[4].asDouble()));

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
            box.setRunFontSize(args[0].asString(), args[1].asDouble());
            return null;
        });
        methods.put("setRunFontFamily", (ProxyExecutable) args -> {
            box.setRunFontFamily(args[0].asString(), args[1].asString());
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
