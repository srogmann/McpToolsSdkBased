package org.rogmann.mcp2sdk.js;

import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Bridge between GraalVM JavaScript and {@link JsJavap}.
 * <p>
 * Creates a {@link ProxyObject} namespace {@code "javap"} exposing Java class-file disassembly
 * for the JavaScript sandbox. The source data is either a file path (string), class bytes
 * (Uint8Array / array of numbers 0-255) or an archive <em>display path</em> that addresses a class
 * inside one or more archives ({@code app.ear#admin.war#WEB-INF/lib/x.jar#com/example/Foo.class});
 * all variants are handled like in {@code crypto.*} / {@code archive.*}.*}.
 * {@code classes(source[, options])} lists the compiled classes of an archive so that a caller can
 * pick a class without unpacking anything.
 * </p>
 *
 * <h3>Usage in JavaScript</h3>
 * <pre>{@code
 * var r = javap.disassemble("classes/Foo.class", "bytecode", "Foo.bytecode.txt");
 * var r2 = javap.disassemble(classBytes, "structure", "Foo.structure.txt");
 * var r3 = javap.disassemble(classBytes, null, "Foo.default.txt");   // default: bytecode
 * var r4 = javap.disassemble("app.zip#BOOT-INF/lib/x.jar#com/example/Util.class",
 *                            "bytecode", "Util.bytecode.txt");       // embedded, nothing unpacked
 * var found = javap.classes("app.zip#BOOT-INF/lib/x.jar", { contains: "Controller" });
 * }</pre>
 */
public class JsJavapBridge implements JsModuleInterface {

    public JsJavapBridge() {
        // Utility class
    }

    @Override
    public String getNamespace() {
        return "javap";
    }

    @Override
    public String getSummary() {
        return "`javap.help()` explains Java bytecode disassembly (also of classes inside"
                + " embedded archives, without unpacking)";
    }

    @Override
    public String getHelpTip() {
        return "javap.help() (Java bytecode disassembly, incl. 'a.jar#b.jar#pkg/C.class' sources)";
    }

    @Override
    public AutoCloseable wireApi(Value jsBindings) {
        jsBindings.putMember("javap", createJavapNamespace());
        return null;
    }

    /**
     * Creates a ProxyObject representing the javap namespace for JavaScript.
     * The returned object can be bound to a JavaScript context as {@code "javap"}.
     *
     * @return ProxyObject with javap methods
     */
    public static ProxyObject createJavapNamespace() {
        Map<String, Object> methods = new HashMap<>();

        methods.put("disassemble", (ProxyExecutable) args -> {
            // Overloads: disassemble(source, outputPath) and disassemble(source, command, outputPath)
            if (args == null || args.length < 2) {
                throw new IllegalArgumentException(
                        "Usage: javap.disassemble(source, [command,] outputPath)");
            }
            Value source = args[0];
            String command;
            String outputPath;
            if (args.length == 2) {
                command = null;
                outputPath = args[1].asString();
            } else {
                command = (args[1] == null || args[1].isNull()) ? null : args[1].asString();
                outputPath = args[2].asString();
            }
            Map<String, Object> result;
            if (source.isString()) {
                result = JsJavap.disassemble(source.asString(), command, outputPath);
            } else {
                result = JsJavap.disassemble(GraalProxies.toByteArray(source), command, outputPath);
            }
            return GraalProxies.toProxyObject(result);
        });

        // classes(source[, options]) - the .class entries of an archive, no unpacking.
        methods.put("classes", (ProxyExecutable) args -> {
            if (args == null || args.length < 1 || args[0] == null || args[0].isNull()) {
                throw new IllegalArgumentException("Usage: javap.classes(source[, {maxEntries,"
                        + " contains}]) - source is an archive path, a display path"
                        + " ('app.ear#admin.war#WEB-INF/lib/x.jar') or archive bytes");
            }
            Map<String, Object> options = optionMap(args, 1);
            Value source = args[0];
            Map<String, Object> result = source.isString()
                    ? JsJavap.classes(source.asString(), options)
                    : JsJavap.classes(GraalProxies.toByteArray(source), options);
            // GraalProxies converts the nested "classes" list on access, so no extra wrapping here.
            return GraalProxies.toProxyObject(result);
        });

        methods.put("help", (ProxyExecutable) args -> JsJavap.help());

        return ProxyObject.fromMap(methods);
    }

    /** Converts the option object at {@code index} (may be absent/null) into a Java map. */
    private static Map<String, Object> optionMap(Value[] args, int index) {
        if (args.length <= index || args[index] == null || args[index].isNull()) {
            return Map.of();
        }
        Value obj = args[index];
        if (!obj.hasMembers()) {
            throw new IllegalArgumentException("options must be an object (see javap.help())");
        }
        Map<String, Object> map = new HashMap<>();
        for (String key : obj.getMemberKeys()) {
            Value value = obj.getMember(key);
            if (value == null || value.isNull()) {
                map.put(key, null);
            } else if (value.isBoolean()) {
                map.put(key, value.asBoolean());
            } else if (value.isNumber()) {
                map.put(key, value.asLong());
            } else if (value.isString()) {
                map.put(key, value.asString());
            } else {
                throw new IllegalArgumentException("Option '" + key
                        + "' must be a string, number or boolean");
            }
        }
        return map;
    }
}
