package org.rogmann.mcp2sdk.js;

import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.rogmann.mcp2sdk.image.JsImage;
import org.rogmann.mcp2sdk.image.VisionClient;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bridge between GraalVM JavaScript and {@link JsImage}.
 * <p>
 * Exposes image analysis and bitmap generation to JavaScript as the {@code "image"} namespace.
 * Each call to {@link #wireApi(Value)} creates a fresh {@link JsImage} instance and captures it
 * in the proxy methods (per-call isolation); the returned {@link AutoCloseable} releases all
 * open rasters when the call ends. The namespace is bound only when the module is enabled
 * ({@value #PROP_ENABLED}, default: enabled).</p>
 *
 * <h3>Usage in JavaScript</h3>
 * <pre>{@code
 * var info = image.info("img/shot.png");           // format, dimensions, EXIF orientation, ...
 * var a = image.analyze("img/shot.png");           // colours, entropy, guess.kind + reasons
 * var img = image.create({width: 640, height: 400, background: '#ffffff'});
 * fs.writeBytes("out/blank.png", image.encode(img));
 * }</pre>
 */
public class JsImageBridge implements JsModuleInterface {

    /** System property to switch the module off: {@code -Dmcp.js.image.enabled=false}. */
    static final String PROP_ENABLED = "mcp.js.image.enabled";

    /** Whether a vision model is configured; only then is {@code image.vision} advertised. */
    private final boolean visionConfigured;

    /**
     * Creates the bridge and reads the vision configuration (the summary then advertises
     * {@code image.vision} only when a gateway URL is set).
     */
    public JsImageBridge() {
        this.visionConfigured = VisionClient.isConfigured();
    }

    @Override
    public String getNamespace() {
        return "image";
    }

    @Override
    public String getSummary() {
        String summary = "`image.help()` explains image metadata, colour/texture analysis"
                + " (kind guess), bitmap generation and annotation";
        return visionConfigured
                ? summary + ", plus `image.vision()` for a visual-LLM question"
                : summary;
    }

    @Override
    public String getHelpTip() {
        return "image.help() (images: analysis, drawing)";
    }

    @Override
    public boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty(PROP_ENABLED, "true"));
    }

    @Override
    public AutoCloseable wireApi(Value jsBindings) {
        if (!isEnabled()) {
            return null;
        }
        JsImage box = new JsImage();
        jsBindings.putMember(getNamespace(), createNamespace(box, jsBindings.getMember("Uint8Array")));
        return box;
    }

    /**
     * Creates a ProxyObject representing the image namespace for JavaScript.
     * @param box the per-call JsImage instance
     * @param uint8ArrayCtor the JS {@code Uint8Array} constructor from the bindings, used to
     *                       return real typed arrays for encoded bytes
     * @return ProxyObject with image methods
     */
    public static ProxyObject createNamespace(JsImage box, Value uint8ArrayCtor) {
        Map<String, Object> methods = new HashMap<>();

        methods.put("info", (ProxyExecutable) args -> {
            requireArgs(args, "info(pathOrBytes, options?)");
            PathOrBytes pb = pathOrBytes(box, args[0]);
            return GraalProxies.toProxyObject(box.info(pb.bytes, pb.display,
                    optionsMap("info", arg(args, 1))));
        });

        methods.put("analyze", (ProxyExecutable) args -> {
            requireArgs(args, "analyze(pathOrBytes, options?)");
            PathOrBytes pb = pathOrBytes(box, args[0]);
            return GraalProxies.toProxyObject(box.analyze(pb.bytes, pb.display,
                    optionsMap("analyze", arg(args, 1))));
        });

        methods.put("open", (ProxyExecutable) args -> {
            requireArgs(args, "open(pathOrBytes, options?)");
            PathOrBytes pb = pathOrBytes(box, args[0]);
            return box.open(pb.bytes, pb.display, optionsMap("open", arg(args, 1)));
        });

        methods.put("meta", (ProxyExecutable) args -> {
            requireArgs(args, "meta(handle)");
            return GraalProxies.toProxyObject(box.meta(stringArg("meta", "handle", args[0])));
        });

        methods.put("create", (ProxyExecutable) args -> {
            requireArgs(args, "create(options)");
            return box.create(optionsMap("create", args[0]));
        });

        methods.put("encode", (ProxyExecutable) args -> {
            requireArgs(args, "encode(handle, options?)");
            byte[] bytes = box.encode(stringArg("encode", "handle", args[0]),
                    optionsMap("encode", arg(args, 1)));
            return GraalProxies.toUint8Array(uint8ArrayCtor, bytes);
        });

        methods.put("save", (ProxyExecutable) args -> {
            requireArgs(args, "save(handle, path, options?)");
            String handle = stringArg("save", "handle", args[0]);
            String path = stringArg("save", "path", args[1]);
            return GraalProxies.toProxyObject(box.save(handle, path,
                    optionsMap("save", arg(args, 2))));
        });

        methods.put("setPixel", (ProxyExecutable) args -> {
            requireArgs(args, 4, "setPixel(handle, x, y, color)");
            box.setPixel(stringArg("setPixel", "handle", args[0]),
                    intArg("setPixel", "x", args[1]), intArg("setPixel", "y", args[2]),
                    stringArg("setPixel", "color", args[3]));
            return null;
        });

        methods.put("line", (ProxyExecutable) args -> {
            requireArgs(args, 5, "line(handle, x1, y1, x2, y2, options?)");
            box.line(stringArg("line", "handle", args[0]),
                    intArg("line", "x1", args[1]), intArg("line", "y1", args[2]),
                    intArg("line", "x2", args[3]), intArg("line", "y2", args[4]),
                    optionsMap("line", arg(args, 5)));
            return null;
        });

        methods.put("rect", (ProxyExecutable) args -> {
            requireArgs(args, 5, "rect(handle, x, y, width, height, options?)");
            box.rect(stringArg("rect", "handle", args[0]),
                    intArg("rect", "x", args[1]), intArg("rect", "y", args[2]),
                    intArg("rect", "width", args[3]), intArg("rect", "height", args[4]),
                    optionsMap("rect", arg(args, 5)));
            return null;
        });

        methods.put("ellipse", (ProxyExecutable) args -> {
            requireArgs(args, 5, "ellipse(handle, x, y, width, height, options?)");
            box.ellipse(stringArg("ellipse", "handle", args[0]),
                    intArg("ellipse", "x", args[1]), intArg("ellipse", "y", args[2]),
                    intArg("ellipse", "width", args[3]), intArg("ellipse", "height", args[4]),
                    optionsMap("ellipse", arg(args, 5)));
            return null;
        });

        methods.put("point", (ProxyExecutable) args -> {
            requireArgs(args, 3, "point(handle, x, y, options?)");
            box.point(stringArg("point", "handle", args[0]),
                    intArg("point", "x", args[1]), intArg("point", "y", args[2]),
                    optionsMap("point", arg(args, 3)));
            return null;
        });

        methods.put("text", (ProxyExecutable) args -> {
            requireArgs(args, 4, "text(handle, x, y, text, options?)");
            box.text(stringArg("text", "handle", args[0]),
                    intArg("text", "x", args[1]), intArg("text", "y", args[2]),
                    stringArg("text", "text", args[3]), optionsMap("text", arg(args, 4)));
            return null;
        });

        methods.put("getPixels", (ProxyExecutable) args -> {
            requireArgs(args, 1, "getPixels(handle, options?)");
            Map<String, Object> result = box.getPixels(
                    stringArg("getPixels", "handle", args[0]), optionsMap("getPixels", arg(args, 1)));
            if (result.get("data") instanceof byte[] rgba) {
                result.put("data", GraalProxies.toUint8Array(uint8ArrayCtor, rgba));
            }
            return GraalProxies.toProxyObject(result);
        });

        methods.put("putPixels", (ProxyExecutable) args -> {
            requireArgs(args, 6, "putPixels(handle, x, y, width, height, rgba)");
            box.putPixels(stringArg("putPixels", "handle", args[0]),
                    intArg("putPixels", "x", args[1]), intArg("putPixels", "y", args[2]),
                    intArg("putPixels", "width", args[3]), intArg("putPixels", "height", args[4]),
                    GraalProxies.toByteArray(args[5]));
            return null;
        });

        methods.put("crop", (ProxyExecutable) args -> {
            requireArgs(args, 5, "crop(handle, x, y, width, height)");
            return box.crop(stringArg("crop", "handle", args[0]),
                    intArg("crop", "x", args[1]), intArg("crop", "y", args[2]),
                    intArg("crop", "width", args[3]), intArg("crop", "height", args[4]));
        });

        methods.put("resize", (ProxyExecutable) args -> {
            requireArgs(args, 1, "resize(handle, options?)");
            return box.resize(stringArg("resize", "handle", args[0]),
                    optionsMap("resize", arg(args, 1)));
        });

        methods.put("toDataUrl", (ProxyExecutable) args -> {
            requireArgs(args, 1, "toDataUrl(handleOrPathOrBytes, options?)");
            Map<String, Object> options = optionsMap("toDataUrl", arg(args, 1));
            if (args[0].isString()) {
                return box.toDataUrl(args[0].asString(), options);
            }
            return box.toDataUrl(GraalProxies.toByteArray(args[0]), "<bytes>", options);
        });

        methods.put("preview", (ProxyExecutable) args -> {
            requireArgs(args, 1, "preview(pathOrBytes, options?)");
            PathOrBytes pb = pathOrBytes(box, args[0]);
            return box.preview(pb.bytes, pb.display, optionsMap("preview", arg(args, 1)));
        });

        methods.put("vision", (ProxyExecutable) args -> {
            requireArgs(args, 2, "vision(handleOrPathOrBytes, prompt, options?)");
            String prompt = stringArg("vision", "prompt", args[1]);
            Map<String, Object> options = optionsMap("vision", arg(args, 2));
            Map<String, Object> result;
            if (args[0].isString()) {
                result = box.vision(args[0].asString(), prompt, options);
            } else {
                result = box.vision(GraalProxies.toByteArray(args[0]), "<bytes>", prompt,
                        options);
            }
            return GraalProxies.toProxyObject(result);
        });

        methods.put("fonts", (ProxyExecutable) args ->
                GraalProxies.toProxyArray(box.fonts()));

        methods.put("help", (ProxyExecutable) args -> box.help());

        return ProxyObject.fromMap(methods);
    }

    /** A resolved image source: raw bytes plus the display path used in messages. */
    private record PathOrBytes(byte[] bytes, String display) {
    }

    /**
     * Interprets the source argument: a path (or archive display path, read through the
     * controlled rules) or raw bytes (Uint8Array, the unified byte contract).
     * @param box the per-call toolbox (path resolution)
     * @param value the JS argument
     * @return raw bytes plus display name
     */
    private static PathOrBytes pathOrBytes(JsImage box, Value value) {
        if (value != null && value.isString()) {
            String path = value.asString();
            return new PathOrBytes(box.readSourceBytes(path), path);
        }
        return new PathOrBytes(GraalProxies.toByteArray(value), "<bytes>");
    }

    /**
     * Converts the options argument into a flat map; nested objects and arrays are rejected
     * (the image options are all flat values).
     * @param fn function name for error messages
     * @param value the JS options argument, may be null/undefined
     * @return a mutable flat map (empty when the argument is absent)
     */
    private static Map<String, Object> optionsMap(String fn, Value value) {
        if (value == null || value.isNull()) {
            return new LinkedHashMap<>();
        }
        if (value.hasArrayElements() || !value.hasMembers()) {
            throw new IllegalArgumentException("Usage: image." + fn
                    + "(pathOrBytes, options?) - options must be an object like {chunks: true}");
        }
        Map<String, Object> options = new LinkedHashMap<>();
        for (String key : value.getMemberKeys()) {
            Value member = value.getMember(key);
            if (member == null || member.isNull()) {
                continue;
            }
            if (member.isBoolean()) {
                options.put(key, member.asBoolean());
            } else if (member.isNumber()) {
                options.put(key, GraalProxies.fromValue(member));
            } else if (member.isString()) {
                options.put(key, member.asString());
            } else {
                throw new IllegalArgumentException("image." + fn + ": option '" + key
                        + "' must be a flat value (string, number, boolean) - got an object"
                        + " or array");
            }
        }
        return options;
    }

    private static Value arg(Value[] args, int index) {
        return args != null && index < args.length ? args[index] : null;
    }

    private static void requireArgs(Value[] args, String signature) {
        if (args == null || args.length == 0 || args[0] == null) {
            throw new IllegalArgumentException("Usage: image." + signature);
        }
    }

    /**
     * Checks that the first {@code required} arguments are present (non-null) - trailing
     * optional arguments (usually the options object) are not covered by this check.
     * @param args the JS arguments
     * @param required how many arguments the function needs
     * @param signature the usage signature for the error message
     */
    private static void requireArgs(Value[] args, int required, String signature) {
        if (args == null || args.length < required) {
            throw new IllegalArgumentException("Usage: image." + signature);
        }
        for (int i = 0; i < required; i++) {
            if (args[i] == null) {
                throw new IllegalArgumentException("Usage: image." + signature);
            }
        }
    }

    /**
     * Converts a whole-number argument (fractional pixel coordinates are a common mistake and
     * are rejected instead of being truncated).
     * @param fn function name for the error message
     * @param name argument name for the error message
     * @param value the JS argument
     * @return the int value
     */
    private static int intArg(String fn, String name, Value value) {
        if (value == null || !value.isNumber()) {
            throw new IllegalArgumentException("image." + fn + ": '" + name
                    + "' must be a number (got " + describeValue(value) + ")");
        }
        double asDouble = value.asDouble();
        if (asDouble != Math.rint(asDouble)) {
            throw new IllegalArgumentException("image." + fn + ": '" + name
                    + "' must be a whole number (got " + describeValue(value) + ")");
        }
        return value.asInt();
    }

    /**
     * Describes a JS value for an error message without coercing it:
     * {@link Value#asString()} throws for non-strings, which once turned a clean
     * "must be a whole number" into a raw polyglot conversion error.
     * @param value the JS value, may be null
     * @return the description
     */
    private static String describeValue(Value value) {
        if (value == null || value.isNull()) {
            return "null";
        }
        if (value.isString()) {
            return "'" + value.asString() + "'";
        }
        return value.toString();
    }

    private static String stringArg(String fn, String name, Value value) {
        if (value == null || !value.isString()) {
            throw new IllegalArgumentException("image." + fn + ": '" + name
                    + "' must be a string (got " + (value == null ? "null" : "another type") + ")");
        }
        return value.asString();
    }
}
