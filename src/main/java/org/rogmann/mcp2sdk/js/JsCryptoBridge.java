package org.rogmann.mcp2sdk.js;

import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Bridge between GraalVM JavaScript and {@link JsCrypto}.
 * <p>
 * Creates a {@link ProxyObject} namespace {@code "crypto"} exposing hashing functions
 * ({@code md5}, {@code sha1}, {@code sha256}). A single string argument is treated as a
 * file path (hashed by streaming); a byte array (Uint8Array or array of numbers 0-255)
 * is treated as in-memory data. Return values are lowercase hex strings.
 * The resulting object is intended to be bound as {@code "crypto"} in the JavaScript bindings.
 * </p>
 *
 * <h3>Usage in JavaScript</h3>
 * <pre>{@code
 * var h1 = crypto.sha256("out.bin");                      // hash a file
 * var h2 = crypto.sha256(new Uint8Array([1, 2, 3]));      // hash a byte array
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
        return "`crypto.help()` explains hashing";
    }

    @Override
    public String getHelpTip() {
        return "crypto.help() (hashing)";
    }

    @Override
    public AutoCloseable wireApi(Value jsBindings) {
        jsBindings.putMember("crypto", createCryptoNamespace());
        return null;
    }

    /**
     * Creates a ProxyObject representing the crypto namespace for JavaScript.
     * The returned object can be bound to a JavaScript context as {@code "crypto"}.
     *
     * @return ProxyObject with crypto methods
     */
    public static ProxyObject createCryptoNamespace() {
        Map<String, Object> methods = new HashMap<>();

        methods.put("md5", (ProxyExecutable) args -> hash("md5", args));
        methods.put("sha1", (ProxyExecutable) args -> hash("sha1", args));
        methods.put("sha256", (ProxyExecutable) args -> hash("sha256", args));

        methods.put("help", (ProxyExecutable) args -> JsCrypto.help());

        return ProxyObject.fromMap(methods);
    }

    /**
     * Dispatches a hash call. The single required argument is either a file path (string)
     * or byte data (array of numbers 0-255 / Uint8Array).
     */
    private static Object hash(String name, Value[] args) {
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
}
