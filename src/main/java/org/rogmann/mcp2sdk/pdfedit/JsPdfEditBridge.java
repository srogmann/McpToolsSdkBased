package org.rogmann.mcp2sdk.pdfedit;

import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.rogmann.mcp2sdk.js.GraalProxies;
import org.rogmann.mcp2sdk.js.JsModuleInterface;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bridge between GraalVM JavaScript and {@link JsPdfEdit}.
 * <p>
 * Creates the {@code pdfedit} namespace: {@code create}, {@code edit}, {@code verify},
 * {@code status} and {@code help}. The op list is converted from JavaScript to plain Java
 * structures before it reaches the module - an op is data, not code - and the result's
 * {@code data} leaves as a real {@code Uint8Array} (the byte contract of the JS tool family).
 * </p>
 *
 * <h3>Usage in JavaScript</h3>
 * <pre>{@code
 * var r = pdfedit.edit('bericht.pdf', [{op:'page.remove', page:7}]);
 * fs.writeBytes('bericht-v2.pdf', r.data);
 * pdfedit.verify(r.data);
 * }</pre>
 */
public class JsPdfEditBridge implements JsModuleInterface {

    @Override
    public String getNamespace() {
        return "pdfedit";
    }

    @Override
    public String getSummary() {
        return "`pdfedit.help()` creates and changes PDFs (PDFBox): pages, content streams,"
                + " annotations, forms, merges of other documents, embedded fonts, and verify()"
                + " with the project's own reader";
    }

    @Override
    public String getHelpTip() {
        return "pdfedit.help() (create/edit PDFs, verify)";
    }

    @Override
    public AutoCloseable wireApi(Value jsBindings) {
        jsBindings.putMember(getNamespace(), createNamespace(jsBindings.getMember("Uint8Array")));
        return null;
    }

    /**
     * Creates the {@code pdfedit} namespace.
     * @param uint8ArrayCtor the JS {@code Uint8Array} constructor, used for the result's bytes
     * @return the namespace as a proxy object
     */
    public static ProxyObject createNamespace(Value uint8ArrayCtor) {
        Map<String, Object> methods = new LinkedHashMap<>();

        methods.put("create", (ProxyExecutable) args -> {
            requireArgs(args, 1, "create(ops[, options])");
            PdfEditOptions options = PdfEditOptions.parse(optionsArg(args, 1));
            return result(JsPdfEdit.create(toJava(argOrNull(args, 0), "ops"), options),
                    uint8ArrayCtor);
        });

        methods.put("edit", (ProxyExecutable) args -> {
            requireArgs(args, 2, "edit(input, ops[, options])");
            PdfEditOptions options = PdfEditOptions.parse(optionsArg(args, 2));
            Object ops = toJava(argOrNull(args, 1), "ops");
            Value input = argOrNull(args, 0);
            if (input == null) {
                throw PdfEditException.usage("edit: the input is missing",
                        "edit(input, ops) takes a project path or a Uint8Array of PDF bytes");
            }
            if (input.isString()) {
                return result(JsPdfEdit.edit(input.asString(), ops, options), uint8ArrayCtor);
            }
            if (input.hasArrayElements()) {
                String name = input.hasMembers() && input.hasMember("path")
                        ? String.valueOf(GraalProxies.fromValue(input.getMember("path"))) : null;
                return result(JsPdfEdit.edit(GraalProxies.toByteArray(input), name, ops, options),
                        uint8ArrayCtor);
            }
            throw PdfEditException.usage("edit: the input is "
                    + GraalProxies.describeType(input),
                    "edit(input, ops) takes a project path (string) or a Uint8Array of PDF bytes");
        });

        methods.put("verify", (ProxyExecutable) args -> {
            requireArgs(args, 1, "verify(input[, {strict}])");
            Map<String, Object> options = optionsArg(args, 1);
            for (String key : options.keySet()) {
                if (!"strict".equals(key)) {
                    throw PdfEditException.usage("verify: unknown option '" + key + "'",
                            "verify takes {strict:true} or nothing");
                }
            }
            Object strictValue = options.get("strict");
            if (strictValue != null && !(strictValue instanceof Boolean)) {
                throw PdfEditException.usage("verify: strict is "
                        + PdfEditOp.describe(strictValue), "strict is true or false");
            }
            boolean strict = Boolean.TRUE.equals(strictValue);
            Value input = argOrNull(args, 0);
            if (input == null) {
                throw PdfEditException.usage("verify: the input is missing",
                        "verify(input) takes a project path or a Uint8Array of PDF bytes");
            }
            if (input.isString()) {
                return GraalProxies.toProxyObject(JsPdfEdit.verify(input.asString(), strict));
            }
            if (input.hasArrayElements()) {
                String name = input.hasMembers() && input.hasMember("path")
                        ? String.valueOf(GraalProxies.fromValue(input.getMember("path"))) : null;
                return GraalProxies.toProxyObject(
                        JsPdfEdit.verify(GraalProxies.toByteArray(input), name, strict));
            }
            throw PdfEditException.usage("verify: the input is "
                    + GraalProxies.describeType(input),
                    "verify(input) takes a project path (string) or a Uint8Array of PDF bytes");
        });

        methods.put("status", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(JsPdfEdit.status()));

        methods.put("help", (ProxyExecutable) args -> JsPdfEdit.help());

        return ProxyObject.fromMap(methods);
    }

    // -------------------------------------------------------------------
    // Argument and result handling
    // -------------------------------------------------------------------

    /**
     * Converts the result map: {@code data} becomes a real {@code Uint8Array} (a Java byte array
     * inside a proxy object would depend on the host access policy).
     * @param answer the module's answer
     * @param uint8ArrayCtor the constructor from the bindings
     * @return the proxy object the script sees
     */
    private static ProxyObject result(Map<String, Object> answer, Value uint8ArrayCtor) {
        Object data = answer.get("data");
        if (data instanceof byte[] bytes) {
            if (uint8ArrayCtor == null) {
                throw new PdfEditException("broken", "the pdfedit module needs the Uint8Array"
                        + " constructor of the JavaScript context to return bytes",
                        "this is a wiring problem, not a problem of the call");
            }
            answer.put("data", GraalProxies.toUint8Array(uint8ArrayCtor, bytes));
        }
        return GraalProxies.toProxyObject(answer);
    }

    /**
     * Reads an options argument.
     * @param args the arguments
     * @param index the position
     * @return the options map (never {@code null})
     */
    private static Map<String, Object> optionsArg(Value[] args, int index) {
        Value value = argOrNull(args, index);
        if (value == null) {
            return Map.of();
        }
        Object converted = toJava(value, "options");
        if (!(converted instanceof Map<?, ?> map)) {
            throw PdfEditException.usage("the options argument is "
                    + GraalProxies.describeType(value),
                    "options is an object, e.g. {incremental:true, verify:false}");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    /**
     * Converts a JavaScript value into plain Java structures, recursively - the op list must not
     * carry live guest objects into the module.
     * <p>
     * A {@code Uint8Array} (an array with a {@code byteLength} member) becomes a {@code byte[]},
     * a plain array becomes a {@code List} (so a list of four numbers stays a list of four
     * numbers - only a typed array means bytes).
     * </p>
     * @param value the value
     * @param what the position, for error messages
     * @return the Java value
     */
    static Object toJava(Value value, String what) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isString()) {
            return value.asString();
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isNumber()) {
            if (value.fitsInLong()) {
                return value.asLong();
            }
            return value.asDouble();
        }
        if (value.hasArrayElements() && value.hasMembers() && value.hasMember("byteLength")) {
            return GraalProxies.toByteArray(value);
        }
        if (value.hasArrayElements()) {
            List<Object> list = new ArrayList<>();
            long size = value.getArraySize();
            for (long i = 0; i < size; i++) {
                list.add(toJava(value.getArrayElement(i), what + "[" + i + "]"));
            }
            return list;
        }
        if (value.hasMembers()) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (String key : value.getMemberKeys()) {
                map.put(key, toJava(value.getMember(key), what + "." + key));
            }
            return map;
        }
        throw PdfEditException.usage(what + " is " + GraalProxies.describeType(value),
                "an op is built from strings, numbers, booleans, null, arrays, objects and"
                        + " Uint8Arrays - see pdfedit.help()");
    }

    private static void requireArgs(Value[] args, int min, String signature) {
        if (args == null || args.length < min) {
            throw PdfEditException.usage("wrong number of arguments",
                    "Usage: pdfedit." + signature + " - pdfedit.help() shows the calls");
        }
    }

    private static Value argOrNull(Value[] args, int index) {
        if (args == null || index >= args.length) {
            return null;
        }
        Value value = args[index];
        return (value == null || value.isNull()) ? null : value;
    }
}
