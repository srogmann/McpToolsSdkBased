package org.rogmann.mcp2sdk.js;

import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyArray;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bridge between GraalVM JavaScript and {@link JsArchive}.
 * <p>
 * Creates a {@link ProxyObject} namespace {@code "archive"} exposing read-only access to
 * ZIP and tar archives ({@code zipEntries}/{@code zipEntry}, {@code tarEntries}/
 * {@code tarEntry} with the first argument as file path or raw bytes) and gzip/deflate
 * byte streams ({@code gzip}/{@code gunzip}/{@code gunzipFile}/{@code deflate}/{@code inflate}).
 * Entry lists are returned as JS-traversable arrays of plain objects; entry content and
 * byte-stream results are returned as real {@code Uint8Array} typed arrays (the unified byte
 * contract of the JS tool family).
 * The resulting object is intended to be bound as {@code "archive"} in the JavaScript bindings.
 * </p>
 *
 * <h3>Usage in JavaScript</h3>
 * <pre>{@code
 * var names = archive.zipEntries("app.war").map(e => e.name);
 * var webXml = archive.zipEntry("app.war", "WEB-INF/web.xml");
 * var cfg = archive.tarEntry("backup.tar", "etc/config.txt");
 * var raw = archive.gunzipFile("log.gz");                       // gunzip a file
 * var list2 = archive.tarEntries(archive.gunzip("src.tar.gz")); // read a .tar.gz in memory
 * var gz = archive.gzip(new Uint8Array([1, 2, 3]));             // compress bytes
 * fs.writeBytes("out.bin", cfg);                                // persist via fs
 * }</pre>
 */
public class JsArchiveBridge implements JsModuleInterface {

    public JsArchiveBridge() {
        // Utility class
    }

    @Override
    public String getNamespace() {
        return "archive";
    }

    @Override
    public String getSummary() {
        return "`archive.help()` explains ZIP/tar/gzip access";
    }

    @Override
    public String getHelpTip() {
        return "archive.help() (ZIP/tar, gzip)";
    }

    @Override
    public AutoCloseable wireApi(Value jsBindings) {
        jsBindings.putMember("archive", createArchiveNamespace(jsBindings.getMember("Uint8Array")));
        return null;
    }

    /**
     * Creates a ProxyObject representing the archive namespace for JavaScript.
     * The returned object can be bound to a JavaScript context as {@code "archive"}.
     *
     * @param uint8ArrayCtor the JS {@code Uint8Array} constructor from the bindings,
     *                        used to return real typed arrays for extracted entries
     * @return ProxyObject with archive methods
     */
    public static ProxyObject createArchiveNamespace(Value uint8ArrayCtor) {
        Map<String, Object> methods = new HashMap<>();

        methods.put("zipEntries", (ProxyExecutable) args -> {
            requireArgs(args, 1, "zipEntries(path)");
            return toEntryArray(JsArchive.zipEntries(args[0].asString()));
        });

        methods.put("zipEntry", (ProxyExecutable) args -> {
            requireArgs(args, 2, "zipEntry(path, entryName)");
            return toU8(uint8ArrayCtor, JsArchive.zipEntry(args[0].asString(), args[1].asString()));
        });

        methods.put("tarEntries", (ProxyExecutable) args -> {
            requireArgs(args, 1, "tarEntries(pathOrBytes)");
            Value v = args[0];
            if (v.isString()) {
                return toEntryArray(JsArchive.tarEntries(v.asString()));
            }
            return toEntryArray(JsArchive.tarEntries(GraalProxies.toByteArray(v)));
        });

        methods.put("tarEntry", (ProxyExecutable) args -> {
            requireArgs(args, 2, "tarEntry(pathOrBytes, entryName)");
            Value v = args[0];
            if (v.isString()) {
                return toU8(uint8ArrayCtor, JsArchive.tarEntry(v.asString(), args[1].asString()));
            }
            return toU8(uint8ArrayCtor, JsArchive.tarEntry(GraalProxies.toByteArray(v), args[1].asString()));
        });

        // ---- gzip / deflate (byte streams) ----
        methods.put("gzip", (ProxyExecutable) args -> {
            requireArgs(args, 1, "gzip(data)");
            return toU8(uint8ArrayCtor, JsArchive.gzip(GraalProxies.toByteArray(args[0])));
        });

        methods.put("gunzip", (ProxyExecutable) args -> {
            requireArgs(args, 1, "gunzip(data)");
            return toU8(uint8ArrayCtor, JsArchive.gunzip(GraalProxies.toByteArray(args[0])));
        });

        methods.put("gunzipFile", (ProxyExecutable) args -> {
            requireArgs(args, 1, "gunzipFile(path)");
            return toU8(uint8ArrayCtor, JsArchive.gunzipFile(args[0].asString()));
        });

        methods.put("deflate", (ProxyExecutable) args -> {
            requireArgs(args, 1, "deflate(data)");
            return toU8(uint8ArrayCtor, JsArchive.deflate(GraalProxies.toByteArray(args[0])));
        });

        methods.put("inflate", (ProxyExecutable) args -> {
            requireArgs(args, 1, "inflate(data)");
            return toU8(uint8ArrayCtor, JsArchive.inflate(GraalProxies.toByteArray(args[0])));
        });

        methods.put("help", (ProxyExecutable) args -> JsArchive.help());

        return ProxyObject.fromMap(methods);
    }

    /**
     * Wraps a list of entry maps as a mutable, JS-traversable array
     * (recursively converts nested Maps).
     */
    private static ProxyArray toEntryArray(List<Map<String, Object>> entries) {
        return new GraalProxies.NestedProxyArray(entries);
    }

    /**
     * Converts a Java byte array to a real JS Uint8Array (null stays null, e.g. "not found").
     */
    private static Value toU8(Value uint8ArrayCtor, byte[] data) {
        return data != null ? GraalProxies.toUint8Array(uint8ArrayCtor, data) : null;
    }

    private static void requireArgs(Value[] args, int min, String signature) {
        if (args == null || args.length < min) {
            throw new IllegalArgumentException("Usage: archive." + signature);
        }
    }
}
