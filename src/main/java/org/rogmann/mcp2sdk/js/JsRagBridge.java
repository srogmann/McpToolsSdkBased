package org.rogmann.mcp2sdk.js;

import java.util.HashMap;
import java.util.Map;

import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;

/**
 * Bridge between GraalVM JavaScript and {@link JsRag}.
 * <p>
 * Binds the namespace {@code rag}: semantic search in the RAG sources registered on
 * the server. The sandbox itself never opens a connection; the module calls the
 * embedding endpoint and the source texts the server is configured for - the same
 * rule as the {@code image} module's vision calls.
 * </p>
 * <p>
 * The module is bound per call and answers {@code rag.help()}. It stays cheap: no
 * source is loaded until the first {@code rag.search()}.
 * </p>
 */
public class JsRagBridge implements JsModuleInterface {

    /** System property that switches the module off. */
    public static final String PROP_ENABLED = "mcp.rag.enabled";

    @Override
    public String getNamespace() {
        return "rag";
    }

    @Override
    public String getSummary() {
        return "`rag.help()` explains semantic search in the RAG sources of this server"
                + " (query embedding, top-n hits per topic or source)";
    }

    @Override
    public String getHelpTip() {
        return "rag.help() (semantic search, RAG sources)";
    }

    @Override
    public boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty(PROP_ENABLED, "true"));
    }

    @Override
    public AutoCloseable wireApi(Value jsBindings) {
        jsBindings.putMember("rag", createRagNamespace());
        return null;
    }

    /**
     * Creates the {@code rag} namespace object for a JavaScript context.
     *
     * @return the ProxyObject with the rag methods
     */
    public static ProxyObject createRagNamespace() {
        Map<String, Object> methods = new HashMap<>();

        methods.put("help", (ProxyExecutable) args -> JsRag.help());
        methods.put("sources", (ProxyExecutable) args ->
                GraalProxies.toProxyArray(JsRag.sources()));
        methods.put("topics", (ProxyExecutable) args ->
                GraalProxies.toProxyArray(JsRag.topics()));
        methods.put("status", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(JsRag.status()));

        // search(query[, options])
        methods.put("search", (ProxyExecutable) args -> {
            if (args == null || args.length < 1 || args[0] == null || args[0].isNull()) {
                throw new IllegalArgumentException(
                        "Usage: rag.search(query[, options]) - see rag.help()");
            }
            Map<String, Object> options = optionMap(args, 1);
            return GraalProxies.toProxyObject(JsRag.search(args[0].asString(), options));
        });

        return ProxyObject.fromMap(methods);
    }

    /**
     * Converts the option object at {@code index} into a Java map; absent means empty.
     *
     * @param args  the call arguments
     * @param index the index of the options object
     * @return the options
     */
    private static Map<String, Object> optionMap(Value[] args, int index) {
        if (args.length <= index || args[index] == null || args[index].isNull()) {
            return Map.of();
        }
        Value obj = args[index];
        if (!obj.hasMembers()) {
            throw new IllegalArgumentException("options must be an object (see rag.help())");
        }
        Map<String, Object> map = new HashMap<>();
        for (String key : obj.getMemberKeys()) {
            Value value = obj.getMember(key);
            if (value == null || value.isNull()) {
                continue;
            } else if (value.isBoolean()) {
                map.put(key, value.asBoolean());
            } else if (value.isNumber()) {
                map.put(key, value.asDouble());
            } else if (value.isString()) {
                map.put(key, value.asString());
            } else {
                throw new IllegalArgumentException("Option '" + key
                        + "' must be a string, number or boolean (see rag.help())");
            }
        }
        return map;
    }
}
