package org.rogmann.mcp2sdk.js;

import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bridge between GraalVM JavaScript and {@link JsSQLite}.
 * <p>
 * Creates a {@link ProxyObject} namespace {@code "sqlite"} exposing read-only access to
 * SQLite database files ({@code tables}/{@code rows} with a path or raw database bytes,
 * plus {@code forEachRow} as a streaming variant with an early-abort handler). Rows are
 * returned as JS-traversable maps ({@code rowid} plus one entry per column); BLOB values
 * are returned as real {@code Uint8Array} typed arrays (the unified byte contract of the
 * JS tool family). The resulting object is intended to be bound as {@code "sqlite"} in the
 * JavaScript bindings.
 * </p>
 *
 * <h3>Usage in JavaScript</h3>
 * <pre>{@code
 * var names = sqlite.tables("data/app.db").map(t => t.name);
 * var rows  = sqlite.rows("data/app.db", "animals");
 * sqlite.forEachRow("data/app.db", "animals", row => {
 *   if (row.name == "bird") return false;   // return false to stop early
 *   return true;
 * });
 * }</pre>
 */
public class JsSQLiteBridge implements JsModuleInterface {

    public JsSQLiteBridge() {
        // Utility class
    }

    @Override
    public String getNamespace() {
        return "sqlite";
    }

    @Override
    public String getSummary() {
        return "`sqlite.help()` explains read-only SQLite database access";
    }

    @Override
    public String getHelpTip() {
        return "sqlite.help() (read-only SQLite)";
    }

    @Override
    public AutoCloseable wireApi(Value jsBindings) {
        jsBindings.putMember("sqlite", createNamespace(jsBindings.getMember("Uint8Array")));
        return null;
    }

    /**
     * Creates a ProxyObject representing the sqlite namespace for JavaScript.
     * The returned object can be bound to a JavaScript context as {@code "sqlite"}.
     *
     * @param uint8ArrayCtor the JS {@code Uint8Array} constructor from the bindings,
     *                        used to return real typed arrays for BLOB values
     * @return ProxyObject with sqlite methods
     */
    public static ProxyObject createNamespace(Value uint8ArrayCtor) {
        Map<String, Object> methods = new HashMap<>();

        methods.put("tables", (ProxyExecutable) args -> {
            requireArgs(args, 1, "tables(pathOrBytes)");
            return new GraalProxies.NestedProxyArray(
                    convertBlobs(JsSQLite.tables(resolveDb(args[0])), uint8ArrayCtor));
        });

        methods.put("rows", (ProxyExecutable) args -> {
            requireArgs(args, 2, "rows(pathOrBytes, tableName)");
            return new GraalProxies.NestedProxyArray(
                    convertBlobs(JsSQLite.rows(resolveDb(args[0]), args[1].asString()), uint8ArrayCtor));
        });

        methods.put("forEachRow", (ProxyExecutable) args -> {
            requireArgs(args, 3, "forEachRow(pathOrBytes, tableName, function(row) {...})");
            Value handler = args[2];
            if (handler == null || !handler.canExecute()) {
                throw new IllegalArgumentException(
                        "Usage: sqlite.forEachRow(pathOrBytes, tableName, function(row) {...})");
            }
            byte[] db = resolveDb(args[0]);
            String tableName = args[1].asString();
            return JsSQLite.forEachRow(db, tableName, row ->
                    isProceed(handler.execute(
                            GraalProxies.toProxyObject(convertRowBlobs(row, uint8ArrayCtor)))));
        });

        methods.put("help", (ProxyExecutable) args -> JsSQLite.help());

        return ProxyObject.fromMap(methods);
    }

    /**
     * Interprets the result of a JS row handler: only an explicit {@code false} stops
     * the iteration.
     * @param result JS handler result (may be null/undefined)
     * @return {@code true} to continue the iteration
     */
    private static boolean isProceed(Value result) {
        return !(result != null && result.isBoolean() && !result.asBoolean());
    }

    /**
     * Resolves the first argument to raw database bytes (file path inside the project base
     * directory or raw bytes, e.g. from fs.readBytes).
     * @param arg JS value (string path or Uint8Array/array of numbers)
     * @return raw database bytes
     */
    private static byte[] resolveDb(Value arg) {
        if (arg != null && arg.isString()) {
            return JsSQLite.readDbBytes(arg.asString());
        }
        return GraalProxies.toByteArray(arg);
    }

    /**
     * Converts all BLOB values (byte arrays) of row maps into JS Uint8Array values.
     * @param rows row maps
     * @param uint8ArrayCtor JS Uint8Array constructor
     * @return converted row maps (new maps, BLOB values replaced)
     */
    private static List<Map<String, Object>> convertBlobs(List<Map<String, Object>> rows, Value uint8ArrayCtor) {
        return rows.stream().map(row -> convertRowBlobs(row, uint8ArrayCtor)).toList();
    }

    /**
     * Converts BLOB values (byte arrays) of a single row map into JS Uint8Array values.
     * @param row row map
     * @param uint8ArrayCtor JS Uint8Array constructor
     * @return converted row map (new map, BLOB values replaced)
     */
    private static Map<String, Object> convertRowBlobs(Map<String, Object> row, Value uint8ArrayCtor) {
        Map<String, Object> converted = new LinkedHashMap<>(row.size());
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof byte[] blob) {
                value = GraalProxies.toUint8Array(uint8ArrayCtor, blob);
            }
            converted.put(entry.getKey(), value);
        }
        return converted;
    }

    private static void requireArgs(Value[] args, int min, String signature) {
        if (args == null || args.length < min) {
            throw new IllegalArgumentException("Usage: sqlite." + signature);
        }
    }
}
