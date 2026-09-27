package org.rogmann.mcp2sdk.js;

import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bridge between GraalVM JavaScript and the core of {@link JsCrypto}.
 * <p>
 * Creates a {@link ProxyObject} namespace {@code "crypto"} exposing hashing functions
 * ({@code md5}, {@code sha1}, {@code sha256}), HMAC ({@code hmac}), the convenience block
 * ciphers ({@code aesCbcEncrypt}, {@code aesCbcDecrypt}, {@code aesCtrXor}) and the
 * compression primitives ({@code inflate}, {@code deflate}).
 * </p>
 * <p>
 * Hashing: a single string argument is treated as a file path (streamed); a byte array
 * (Uint8Array or array of numbers 0-255) is treated as in-memory data; results are
 * lowercase hex strings. All other byte-array inputs and results are real
 * {@code Uint8Array} typed arrays.
 * </p>
 *
 * <h3>Usage in JavaScript</h3>
 * <pre>{@code
 * var h1 = crypto.sha256("out.bin");                      // hash a file
 * var h2 = crypto.sha256(new Uint8Array([1, 2, 3]));      // hash a byte array
 * var ct = crypto.aesCbcEncrypt(key, iv, data);           // padded AES-CBC
 * var pt = crypto.inflate(bytes, 'zlib');                 // PDF /FlateDecode
 * }</pre>
 */
public class JsCryptoBridge implements JsModuleInterface {

    public JsCryptoBridge() {
        // Utility class
    }

    @Override
    public String getNamespace() {
        return "crypto";
    }

    @Override
    public String getSummary() {
        return "`crypto.help()` explains hashing, HMAC, the AES-CBC/CTR convenience block "
                + "ciphers and deflate/inflate (`crypto.inflate`) for compressed streams";
    }

    @Override
    public String getHelpTip() {
        return "crypto.help() (hashing, HMAC, AES-CBC/CTR, inflate)";
    }

    @Override
    public AutoCloseable wireApi(Value jsBindings) {
        jsBindings.putMember(getNamespace(),
                ProxyObject.fromMap(createMethods(jsBindings.getMember("Uint8Array"))));
        return null;
    }

    /**
     * Builds the members of the {@code crypto} namespace. A fresh map per call, so no binding
     * can share mutable state with another one.
     *
     * @param uint8ArrayCtor the JS {@code Uint8Array} constructor from the bindings,
     *                        used to return real typed arrays for byte-array results
     * @return the namespace members in a stable order
     */
    protected Map<String, Object> createMethods(Value uint8ArrayCtor) {
        Map<String, Object> methods = new LinkedHashMap<>();

        methods.put("md5", (ProxyExecutable) args -> hash("md5", args));
        methods.put("sha1", (ProxyExecutable) args -> hash("sha1", args));
        methods.put("sha256", (ProxyExecutable) args -> hash("sha256", args));

        methods.put("aesCbcEncrypt", (ProxyExecutable) args -> {
            requireArgs(args, 3, "aesCbcEncrypt(key, iv, data)");
            return toU8(uint8ArrayCtor,
                    JsCrypto.aesCbcEncrypt(GraalProxies.toByteArray(args[0]),
                            GraalProxies.toByteArray(args[1]),
                            GraalProxies.toByteArray(args[2])));
        });

        methods.put("aesCbcDecrypt", (ProxyExecutable) args -> {
            requireArgs(args, 3, "aesCbcDecrypt(key, iv, ct)");
            return toU8(uint8ArrayCtor,
                    JsCrypto.aesCbcDecrypt(GraalProxies.toByteArray(args[0]),
                            GraalProxies.toByteArray(args[1]),
                            GraalProxies.toByteArray(args[2])));
        });

        methods.put("aesCtrXor", (ProxyExecutable) args -> {
            requireArgs(args, 3, "aesCtrXor(key, iv, data)");
            return toU8(uint8ArrayCtor,
                    JsCrypto.aesCtrXor(GraalProxies.toByteArray(args[0]),
                            GraalProxies.toByteArray(args[1]),
                            GraalProxies.toByteArray(args[2])));
        });

        methods.put("hmac", (ProxyExecutable) args -> {
            requireArgs(args, 3, "hmac('md5'|'sha1'|'sha256', key, data)");
            return toU8(uint8ArrayCtor, JsCrypto.hmac(args[0].asString(),
                    GraalProxies.toByteArray(args[1]),
                    GraalProxies.toByteArray(args[2])));
        });

        methods.put("inflate", (ProxyExecutable) args -> {
            requireArgs(args, 1, "inflate(data[, format]) - format: 'auto' (default), 'zlib'"
                    + " (PDF /FlateDecode), 'raw' (deflate without wrapper) or 'gzip'");
            String format = args.length >= 2 && args[1] != null && !args[1].isNull()
                    ? args[1].asString() : JsCrypto.FORMAT_AUTO;
            return toU8(uint8ArrayCtor,
                    JsCrypto.inflate(GraalProxies.toByteArray(args[0]), format));
        });

        methods.put("deflate", (ProxyExecutable) args -> {
            requireArgs(args, 1, "deflate(data[, format, level]) - format: 'zlib' (default),"
                    + " 'raw' or 'gzip'; level -1 (default) .. 9");
            String format = args.length >= 2 && args[1] != null && !args[1].isNull()
                    ? args[1].asString() : JsCrypto.FORMAT_ZLIB;
            int level = args.length >= 3 && args[2] != null && !args[2].isNull()
                    ? (int) args[2].asLong() : -1;
            return toU8(uint8ArrayCtor,
                    JsCrypto.deflate(GraalProxies.toByteArray(args[0]), format, level));
        });

        methods.put("help", (ProxyExecutable) args -> helpText());

        return methods;
    }

    /**
     * The help text behind {@code crypto.help()}; the extended bridge replaces it with the
     * full document.
     * @return help text as a multi-line string
     */
    protected String helpText() {
        return JsCrypto.helpSections();
    }

    /**
     * Converts a Java byte array to a real JS Uint8Array.
     */
    protected static Value toU8(Value uint8ArrayCtor, byte[] data) {
        return data != null ? GraalProxies.toUint8Array(uint8ArrayCtor, data) : null;
    }

    /**
     * Dispatches a hash call. The single required argument is either a file path (string)
     * or byte data (array of numbers 0-255 / Uint8Array).
     */
    protected static Object hash(String name, Value[] args) {
        if (args == null || args.length < 1 || args[0].isNull()) {
            throw new IllegalArgumentException("Usage: crypto." + name
                    + "(pathOrData) - a file path or a byte array (0-255)");
        }
        Value v = args[0];
        if (v.isString()) {
            String path = v.asString();
            return switch (name) {
                case "md5" -> JsCrypto.md5(path);
                case "sha1" -> JsCrypto.sha1(path);
                default -> JsCrypto.sha256(path);
            };
        }
        byte[] data = GraalProxies.toByteArray(v);
        return switch (name) {
            case "md5" -> JsCrypto.md5(data);
            case "sha1" -> JsCrypto.sha1(data);
            default -> JsCrypto.sha256(data);
        };
    }

    /**
     * Throws an IllegalArgumentException with a usage hint if too few arguments are given.
     *
     * @param args the argument array
     * @param min minimum number of required arguments
     * @param signature the usage signature for the message
     */
    protected static void requireArgs(Value[] args, int min, String signature) {
        if (args == null || args.length < min) {
            throw new IllegalArgumentException("Usage: crypto." + signature);
        }
    }

    /**
     * Converts an optional byte-array argument to Java bytes; JS null/undefined maps
     * to Java null (used for the IV, where null selects ECB mode).
     */
    protected static byte[] optionalBytes(Value v) {
        if (v == null || v.isNull()) {
            return null;
        }
        return GraalProxies.toByteArray(v);
    }
}
