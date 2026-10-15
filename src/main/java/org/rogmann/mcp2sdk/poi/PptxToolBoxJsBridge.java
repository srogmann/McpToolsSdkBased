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
 * <h3>Argument conversion</h3>
 * <p>
 * Every argument is read through {@link PoiJsArgs.Call}, so a wrong or missing argument names
 * the function, the argument and the valid range. Reading {@code args[5].asDouble()} directly
 * answered a short call with an {@code ArrayIndexOutOfBoundsException}, and a bare
 * {@code asDouble()} accepted any number - including NaN and 1e300 pt.
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

    /** Shared conversion rules of the three POI namespaces. */
    private static final PoiJsArgs A = PoiJsArgs.PPTX;

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

        // --- presentation ---
        methods.put("openFile", (ProxyExecutable) args ->
                box.openFile(A.bind("openFile", args).string("path", 0)));
        methods.put("createPresentation", (ProxyExecutable) args -> box.createPresentation());
        methods.put("save", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("save", args);
            box.save(c.string("showHandle", 0), c.string("path", 1));
            return null;
        });
        methods.put("close", (ProxyExecutable) args -> {
            box.close(A.bind("close", args).string("showHandle", 0));
            return null;
        });

        // --- slides and layouts ---
        methods.put("getSlides", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(box.getSlides(presentation("getSlides", args))));
        methods.put("getPageSize", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(box.getPageSize(presentation("getPageSize", args))));
        methods.put("createSlide", (ProxyExecutable) args ->
                box.createSlide(presentation("createSlide", args)));
        methods.put("createBlankSlide", (ProxyExecutable) args ->
                box.createBlankSlide(presentation("createBlankSlide", args)));
        methods.put("getLayouts", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(box.getLayouts(presentation("getLayouts", args))));
        methods.put("createSlideFromLayout", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("createSlideFromLayout", args);
            return box.createSlideFromLayout(c.string("showHandle", 0), c.string("layoutHandle", 1));
        });
        methods.put("createSlideLike", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("createSlideLike", args);
            return box.createSlideLike(c.string("showHandle", 0), c.string("slideHandle", 1));
        });
        methods.put("duplicateSlide", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("duplicateSlide", args);
            return box.duplicateSlide(c.string("showHandle", 0), c.string("slideHandle", 1));
        });

        // --- shapes ---
        methods.put("createTextBox", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("createTextBox", args);
            return box.createTextBox(c.string("slideHandle", 0), c.optionalString("text", 1));
        });
        methods.put("addTextBox", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("addTextBox", args);
            return box.addTextBox(c.string("slideHandle", 0), c.optionalString("text", 1),
                    c.number("x", 2, -PptxToolBox.MAX_COORD_PT, PptxToolBox.MAX_COORD_PT),
                    c.number("y", 3, -PptxToolBox.MAX_COORD_PT, PptxToolBox.MAX_COORD_PT),
                    c.number("width", 4, 0.0, PptxToolBox.MAX_SIZE_PT),
                    c.number("height", 5, 0.0, PptxToolBox.MAX_SIZE_PT));
        });
        methods.put("createShape", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("createShape", args);
            return box.createShape(c.string("slideHandle", 0), c.optionalString("text", 1),
                    c.number("x", 2, -PptxToolBox.MAX_COORD_PT, PptxToolBox.MAX_COORD_PT),
                    c.number("y", 3, -PptxToolBox.MAX_COORD_PT, PptxToolBox.MAX_COORD_PT),
                    c.number("width", 4, 0.0, PptxToolBox.MAX_SIZE_PT),
                    c.number("height", 5, 0.0, PptxToolBox.MAX_SIZE_PT));
        });
        methods.put("createLine", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("createLine", args);
            return box.createLine(c.string("slideHandle", 0),
                    c.number("x1", 1, -PptxToolBox.MAX_COORD_PT, PptxToolBox.MAX_COORD_PT),
                    c.number("y1", 2, -PptxToolBox.MAX_COORD_PT, PptxToolBox.MAX_COORD_PT),
                    c.number("x2", 3, -PptxToolBox.MAX_COORD_PT, PptxToolBox.MAX_COORD_PT),
                    c.number("y2", 4, -PptxToolBox.MAX_COORD_PT, PptxToolBox.MAX_COORD_PT));
        });
        methods.put("getShapeText", (ProxyExecutable) args ->
                box.getShapeText(shape("getShapeText", args)));
        methods.put("setShapeText", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("setShapeText", args);
            box.setShapeText(c.string("shapeHandle", 0), c.string("text", 1));
            return null;
        });
        methods.put("appendParagraph", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("appendParagraph", args);
            box.appendParagraph(c.string("shapeHandle", 0), c.string("text", 1));
            return null;
        });
        methods.put("getTableData", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(box.getTableData(shape("getTableData", args))));
        methods.put("getTableText", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(box.getTableText(shape("getTableText", args))));
        methods.put("setShapePosition", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("setShapePosition", args);
            box.setShapePosition(c.string("shapeHandle", 0),
                    c.number("x", 1, -PptxToolBox.MAX_COORD_PT, PptxToolBox.MAX_COORD_PT),
                    c.number("y", 2, -PptxToolBox.MAX_COORD_PT, PptxToolBox.MAX_COORD_PT));
            return null;
        });
        methods.put("setShapeSize", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("setShapeSize", args);
            box.setShapeSize(c.string("shapeHandle", 0),
                    c.number("width", 1, 0.0, PptxToolBox.MAX_SIZE_PT),
                    c.number("height", 2, 0.0, PptxToolBox.MAX_SIZE_PT));
            return null;
        });
        methods.put("setShapeRotation", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("setShapeRotation", args);
            box.setShapeRotation(c.string("shapeHandle", 0),
                    c.number("degrees", 1, -PptxToolBox.MAX_ROTATION_DEG, PptxToolBox.MAX_ROTATION_DEG));
            return null;
        });
        methods.put("setShapeGeometry", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("setShapeGeometry", args);
            box.setShapeGeometry(c.string("shapeHandle", 0), c.string("typeName", 1));
            return null;
        });
        methods.put("removeShape", (ProxyExecutable) args -> {
            box.removeShape(shape("removeShape", args));
            return null;
        });
        methods.put("setShapeFillColor", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("setShapeFillColor", args);
            box.setShapeFillColor(c.string("shapeHandle", 0), c.string("hexColor", 1));
            return null;
        });
        methods.put("setShapeLineColor", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("setShapeLineColor", args);
            box.setShapeLineColor(c.string("shapeHandle", 0), c.string("hexColor", 1));
            return null;
        });
        methods.put("setShapeLineWidth", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("setShapeLineWidth", args);
            box.setShapeLineWidth(c.string("shapeHandle", 0),
                    c.number("widthPt", 1, 0.0, PptxToolBox.MAX_LINE_WIDTH_PT));
            return null;
        });

        // --- run formatting ---
        methods.put("setRunText", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("setRunText", args);
            box.setRunText(c.string("runHandle", 0), c.string("text", 1));
            return null;
        });
        methods.put("setRunBold", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("setRunBold", args);
            box.setRunBold(c.string("runHandle", 0), c.bool("bold", 1));
            return null;
        });
        methods.put("setRunItalic", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("setRunItalic", args);
            box.setRunItalic(c.string("runHandle", 0), c.bool("italic", 1));
            return null;
        });
        methods.put("setRunFontSize", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("setRunFontSize", args);
            box.setRunFontSize(c.string("runHandle", 0),
                    c.number("sizePoints", 1, PptxToolBox.MIN_FONT_PT, PptxToolBox.MAX_FONT_PT));
            return null;
        });
        methods.put("setRunFontFamily", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("setRunFontFamily", args);
            box.setRunFontFamily(c.string("runHandle", 0), c.string("fontName", 1));
            return null;
        });
        methods.put("setRunFontColor", (ProxyExecutable) args -> {
            PoiJsArgs.Call c = A.bind("setRunFontColor", args);
            box.setRunFontColor(c.string("runHandle", 0), c.string("hexColor", 1));
            return null;
        });

        methods.put("help", (ProxyExecutable) args -> box.help());

        return ProxyObject.fromMap(methods);
    }

    /**
     * The presentation handle of a one-argument call, named in the message when the caller
     * passed nothing or something that is not a string.
     */
    private static String presentation(String fn, Value[] args) {
        return A.bind(fn, args).string("showHandle", 0);
    }

    /** The shape handle of a one-argument call. */
    private static String shape(String fn, Value[] args) {
        return A.bind(fn, args).string("shapeHandle", 0);
    }
}
