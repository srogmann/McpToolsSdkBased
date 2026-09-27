package org.rogmann.mcp2sdk.js;

import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Bridge between GraalVM JavaScript and {@link JsM2}.
 * <p>
 * Creates a {@link ProxyObject} namespace {@code "m2"} exposing read-only Maven repository
 * (M2) access to the JavaScript sandbox. Results are converted with
 * {@link GraalProxies#toProxyObject(Map)} / {@link GraalProxies#toProxyArray}, entry bytes
 * are returned as real {@code Uint8Array} values (the unified byte contract of the JS tool
 * family, see {@code archive.*} / {@code objdump.*}).
 * </p>
 *
 * <h3>Usage in JavaScript</h3>
 * <pre>{@code
 * var deps = m2.list('pom.xml', { group: 'org.springframework' });
 * var entries = m2.listEntries('org.springframework:spring-core:7.0.8',
 *         { glob: '&#42;&#42;/SpringVersion.class' });
 * var bytes = m2.readEntry('org.springframework:spring-core:7.0.8',
 *         'org/springframework/core/SpringVersion.class');
 * var javapResult = javap.disassemble(bytes, 'structure', 'spring-version.txt');
 * }</pre>
 */
public class JsM2Bridge implements JsModuleInterface {

    @Override
    public String getNamespace() {
        return "m2";
    }

    @Override
    public String getSummary() {
        return "`m2.help()` explains read-only Maven repository access"
                + " (classpath of a pom.xml, dependency tree, JAR entry reads)";
    }

    @Override
    public String getHelpTip() {
        return "m2.help() (M2 repository, classpath, JAR entries)";
    }

    @Override
    public boolean isEnabled() {
        return Boolean.parseBoolean(
                System.getProperty("mcp2sdk.module.m2.enabled", "true"));
    }

    @Override
    public AutoCloseable wireApi(Value jsBindings) {
        jsBindings.putMember("m2", createM2Namespace(jsBindings.getMember("Uint8Array")));
        return null;
    }

    /**
     * Creates the {@code m2} namespace object for a JavaScript context.
     * @param uint8ArrayCtor the JS {@code Uint8Array} constructor from the bindings
     * @return ProxyObject with the m2 methods
     */
    public static ProxyObject createM2Namespace(Value uint8ArrayCtor) {
        Map<String, Object> methods = new HashMap<>();

        methods.put("help", (ProxyExecutable) args -> JsM2.help());

        // list(pom[, options])
        methods.put("list", (ProxyExecutable) args -> {
            requireArgs(args, 1, "m2.list(pom[, options])");
            return GraalProxies.toProxyObject(
                    unchecked(() -> JsM2.list(args[0].asString(), optionMap(args, 1))));
        });

        // classpath(pom)
        methods.put("classpath", (ProxyExecutable) args -> {
            requireArgs(args, 1, "m2.classpath(pom)");
            return GraalProxies.toProxyArray(unchecked(() -> JsM2.classpath(args[0].asString())));
        });

        // tree(pom[, options])
        methods.put("tree", (ProxyExecutable) args -> {
            requireArgs(args, 1, "m2.tree(pom[, {verbose}])");
            return GraalProxies.toProxyObject(
                    unchecked(() -> JsM2.tree(args[0].asString(), optionMap(args, 1))));
        });

        // findClass(className, pom)
        methods.put("findClass", (ProxyExecutable) args -> {
            requireArgs(args, 2, "m2.findClass(className, pom)");
            return GraalProxies.toProxyObject(
                    unchecked(() -> JsM2.findClass(args[0].asString(), args[1].asString())));
        });

        // listEntries(handle[, options])
        methods.put("listEntries", (ProxyExecutable) args -> {
            requireArgs(args, 1, "m2.listEntries(handle[, {glob, maxEntries}])");
            return GraalProxies.toProxyObject(
                    unchecked(() -> JsM2.listEntries(args[0].asString(), optionMap(args, 1))));
        });

        // readEntry(handle, entry[, options]) -> Uint8Array
        methods.put("readEntry", (ProxyExecutable) args -> {
            requireArgs(args, 2, "m2.readEntry(handle, entry[, {maxBytes}])");
            byte[] data = unchecked(() -> JsM2.readEntry(args[0].asString(), args[1].asString(),
                    optionMap(args, 2)));
            return GraalProxies.toUint8Array(uint8ArrayCtor, data);
        });

        // sources(handle[, pom])
        methods.put("sources", (ProxyExecutable) args -> {
            requireArgs(args, 1, "m2.sources(handle[, pom])");
            String pom = args.length >= 2 && args[1] != null && !args[1].isNull()
                    ? args[1].asString() : null;
            return GraalProxies.toProxyObject(unchecked(() -> JsM2.sources(args[0].asString(), pom)));
        });

        // info()
        methods.put("info", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(JsM2.info()));

        // clearCache()
        methods.put("clearCache", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(JsM2.clearCache()));

        return ProxyObject.fromMap(methods);
    }

    // ---------------------------------------------------------------
    // Argument helpers
    // ---------------------------------------------------------------

    private static void requireArgs(Value[] args, int count, String usage) {
        if (args == null || args.length < count || args[0] == null || args[0].isNull()) {
            throw new IllegalArgumentException("Usage: " + usage + " - see m2.help()");
        }
    }

    /** Converts the option object at {@code index} (may be absent/null) into a Java map. */
    private static Map<String, Object> optionMap(Value[] args, int index) {
        if (args.length <= index || args[index] == null || args[index].isNull()) {
            return Map.of();
        }
        Value obj = args[index];
        if (!obj.hasMembers()) {
            throw new IllegalArgumentException("options must be an object (see m2.help())");
        }
        Map<String, Object> map = new HashMap<>();
        for (String key : obj.getMemberKeys()) {
            Value v = obj.getMember(key);
            if (v == null || v.isNull()) {
                map.put(key, null);
            } else if (v.isBoolean()) {
                map.put(key, v.asBoolean());
            } else if (v.isNumber()) {
                map.put(key, v.asLong());
            } else if (v.isString()) {
                map.put(key, v.asString());
            } else {
                throw new IllegalArgumentException("Option '" + key
                        + "' must be a string, number or boolean");
            }
        }
        return map;
    }

    /** Runs a throwing supplier, wrapping IOException in a JS-visible runtime exception. */
    private static <T> T unchecked(IoSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (IOException e) {
            throw new JsUserRuntimeException(e.getMessage(), e);
        }
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws IOException;
    }
}
