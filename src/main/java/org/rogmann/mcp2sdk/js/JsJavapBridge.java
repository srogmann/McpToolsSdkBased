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
 * for the JavaScript sandbox. The source data is either a file path (string) or class bytes
 * (Uint8Array / array of numbers 0-255).
 * </p>
 *
 * <h3>Usage in JavaScript</h3>
 * <pre>{@code
 * var r = javap.disassemble("classes/Foo.class", "bytecode", "Foo.bytecode.txt");
 * var r2 = javap.disassemble(classBytes, "structure", "Foo.structure.txt");
 * var r3 = javap.disassemble(classBytes, null, "Foo.default.txt");   // default: bytecode
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
        return "`javap.help()` explains Java bytecode disassembly";
    }

    @Override
    public String getHelpTip() {
        return "javap.help() (Java bytecode disassembly)";
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
            return ProxyObject.fromMap(result);
        });

        methods.put("help", (ProxyExecutable) args -> JsJavap.help());

        return ProxyObject.fromMap(methods);
    }
}
