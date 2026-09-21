package org.rogmann.mcp2sdk.xml;

import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.rogmann.mcp2sdk.js.GraalProxies;
import org.rogmann.mcp2sdk.js.JsModuleInterface;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bridge between GraalVM JavaScript and {@link JsXml}.
 * <p>
 * Creates the {@code "xml"} namespace. One {@link JsXml} instance belongs to one
 * JavaScript call and is closed when the call ends: {@link #wireApi(Value)} returns
 * {@link JsXml#closeAll()} as the {@code AutoCloseable}, so no parsed DOM survives a tool
 * call and document ids never leak across calls.
 * </p>
 *
 * <h3>Value conversion</h3>
 * <p>
 * Option and selector arguments are converted into plain Java maps/lists (strings stay
 * strings - the module never coerces a value into a number), byte sources into
 * {@code byte[]}, node arguments into descriptor maps, node ids or path strings. A JS
 * {@code RegExp} is accepted wherever a pattern is expected (its source is used).
 * Results are returned as {@link ProxyObject}s so {@code Object.keys}, {@code for}-loops
 * and {@code JSON.stringify} behave under the sandboxed {@code HostAccess}.
 * </p>
 *
 * <h3>Usage in JavaScript</h3>
 * <pre>{@code
 * console.log(xml.tree("src/test/resources/js/sample.xml"));      // structure, no DOM needed
 * xml.attrNames("spec.xml", "box");                               // -> names: hibit, width, …
 *
 * var d = xml.open("ldaprb.xml");                                 // handle for XPath queries
 * var r = xml.find("doc1", "//regdiagram//box", {attrs: ["hibit", "width"]});
 * r.nodes.forEach(n => console.log(n.path, n.attrs.hibit));
 *
 * var bits = [];
 * xml.each("release.tar#aarchmrsys/instruction/ldaprb.xml",
 *          {ancestor: "regdiagram", name: "box"},
 *          b => { bits.push({hi: b.attrs.hibit, w: b.attrs.width}); });   // streaming, any size
 *
 * xml.grep("spec.xml", /QARMA/, {scope: "text"});                 // hits WITH element path
 * xml.text("doc1", "/root/node[1]/code[1]", {strict: true});      // all or nothing
 * }</pre>
 */
public class JsXmlBridge implements JsModuleInterface {

    /** The per-call XML API instance (documents live exactly as long as this call). */
    private final JsXml xml = new JsXml();

    @Override
    public String getNamespace() {
        return "xml";
    }

    @Override
    public String getSummary() {
        return "`xml.help()` explains controlled XML reading (structure discovery, XPath 1.0,"
                + " streaming, archive paths; no network, DTDs resolve as local files only)";
    }

    @Override
    public String getHelpTip() {
        return "xml.help() (XML discovery + XPath + streaming)";
    }

    @Override
    public AutoCloseable wireApi(Value jsBindings) {
        jsBindings.putMember("xml", createNamespace());
        return xml::closeAll;
    }

    /**
     * Creates the {@code xml} namespace bound to this call's {@link JsXml} instance.
     *
     * @return ProxyObject with the xml methods
     */
    public ProxyObject createNamespace() {
        Map<String, Object> methods = new LinkedHashMap<>();

        // ---- documents ----
        methods.put("open", (ProxyExecutable) args -> {
            requireArgs(args, 1, "open(pathOrBytesOrDocId[, options])");
            return GraalProxies.toProxyObject(xml.open(sourceArg(args[0]), optionsArg(arg(args, 1))));
        });
        methods.put("openText", (ProxyExecutable) args -> {
            requireArgs(args, 1, "openText(text[, options])");
            return GraalProxies.toProxyObject(xml.openText(requireText(args[0]),
                    optionsArg(arg(args, 1))));
        });
        methods.put("docs", (ProxyExecutable) args -> GraalProxies.toProxyObject(xml.docs()));
        methods.put("close", (ProxyExecutable) args -> {
            requireArgs(args, 1, "close(id|[ids])");
            return GraalProxies.toProxyObject(xml.close(toJava(arg(args, 0))));
        });

        // ---- discovery (streaming) ----
        methods.put("profile", (ProxyExecutable) args -> {
            requireArgs(args, 1, "profile(source[, options])");
            return GraalProxies.toProxyObject(xml.profile(sourceArg(args[0]), optionsArg(arg(args, 1))));
        });
        methods.put("attrNames", (ProxyExecutable) args -> {
            requireArgs(args, 2, "attrNames(source, elementName)");
            return GraalProxies.toProxyObject(xml.attrNames(sourceArg(args[0]), requireText(args[1])));
        });
        methods.put("count", (ProxyExecutable) args -> {
            requireArgs(args, 1, "count(source[, selector])");
            return GraalProxies.toProxyObject(xml.count(sourceArg(args[0]),
                    optionsArg(isSelector(args, 1) ? arg(args, 1) : null)));
        });
        methods.put("tree", (ProxyExecutable) args -> {
            requireArgs(args, 1, "tree(source[, options])");
            return xml.tree(sourceArg(args[0]), optionsArg(arg(args, 1)));
        });
        methods.put("treeResult", (ProxyExecutable) args -> {
            requireArgs(args, 1, "treeResult(source[, options])");
            return GraalProxies.toProxyObject(xml.treeResult(sourceArg(args[0]), optionsArg(arg(args, 1))));
        });
        methods.put("ns", (ProxyExecutable) args -> {
            requireArgs(args, 1, "ns(source[, options])");
            return GraalProxies.toProxyObject(xml.namespaces(sourceArg(args[0]), optionsArg(arg(args, 1))));
        });

        // ---- query / iteration ----
        methods.put("find", (ProxyExecutable) args -> {
            requireArgs(args, 2, "find(source, xpath[, options])");
            return GraalProxies.toProxyObject(xml.find(sourceArg(args[0]), requireText(args[1]),
                    optionsArg(arg(args, 2))));
        });
        methods.put("each", (ProxyExecutable) args -> {
            if (args == null || args.length < 2) {
                throw new IllegalArgumentException(
                        "Usage: xml.each(source[, xpathOrSelector], function(node) { … }[, options])");
            }
            int index = 1;
            Object selector = null;
            if (!args[1].canExecute()) {
                selector = toJava(args[1]);
                index = 2;
            }
            if (index >= args.length || !args[index].canExecute()) {
                throw new IllegalArgumentException(
                        "Usage: xml.each(source[, xpathOrSelector], function(node) { … }[, options])"
                                + " - the last argument before options must be a function"
                                + " (return false to stop the iteration)");
            }
            Value handler = args[index];
            Map<String, Object> options = optionsArg(arg(args, index + 1));
            JsXmlScan.Visitor visitor = descriptor ->
                    isProceed(handler.execute(GraalProxies.toProxyObject(descriptor)));
            return GraalProxies.toProxyObject(xml.each(sourceArg(args[0]), selector, visitor, options));
        });
        methods.put("scan", (ProxyExecutable) args -> {
            requireArgs(args, 1, "scan(source[, selector][, options])");
            Map<String, Object> selector = isSelector(args, 1) ? optionsArg(arg(args, 1)) : null;
            return GraalProxies.toProxyObject(xml.scan(sourceArg(args[0]), selector,
                    optionsArg(isSelector(args, 1) ? arg(args, 2) : arg(args, 1))));
        });
        methods.put("grep", (ProxyExecutable) args -> {
            requireArgs(args, 2, "grep(source, /pattern/|pattern[, options])");
            return GraalProxies.toProxyObject(xml.grep(sourceArg(args[0]), toJava(args[1]),
                    optionsArg(arg(args, 2))));
        });
        methods.put("sample", (ProxyExecutable) args -> {
            requireArgs(args, 2, "sample(source, elementName[, options])");
            return GraalProxies.toProxyObject(xml.sample(sourceArg(args[0]), requireText(args[1]),
                    optionsArg(arg(args, 2))));
        });

        // ---- node access ----
        methods.put("attrs", (ProxyExecutable) args -> {
            requireArgs(args, 2, "attrs(source, node)");
            return GraalProxies.toProxyObject(xml.attrs(sourceArg(args[0]), toJava(args[1])));
        });
        methods.put("text", (ProxyExecutable) args -> {
            requireArgs(args, 2, "text(source, node[, options])");
            return xml.text(sourceArg(args[0]), toJava(args[1]), optionsArg(arg(args, 2)));
        });
        methods.put("cdata", (ProxyExecutable) args -> {
            requireArgs(args, 2, "cdata(source, node[, options])");
            return xml.cdata(sourceArg(args[0]), toJava(args[1]), optionsArg(arg(args, 2)));
        });
        methods.put("children", (ProxyExecutable) args -> {
            requireArgs(args, 2, "children(source, node[, options])");
            return GraalProxies.toProxyObject(xml.children(sourceArg(args[0]), toJava(args[1]),
                    optionsArg(arg(args, 2))));
        });
        methods.put("ancestors", (ProxyExecutable) args -> {
            requireArgs(args, 2, "ancestors(source, node)");
            return GraalProxies.toProxyObject(xml.ancestors(sourceArg(args[0]), toJava(args[1])));
        });
        methods.put("get", (ProxyExecutable) args -> {
            requireArgs(args, 2, "get(source, node[, options])");
            return GraalProxies.toProxyObject(xml.get(sourceArg(args[0]), toJava(args[1]),
                    optionsArg(arg(args, 2))));
        });

        methods.put("help", (ProxyExecutable) args -> JsXml.help());

        return ProxyObject.fromMap(methods);
    }

    // ========================================================================
    // Conversion helpers
    // ========================================================================

    /**
     * Converts the first argument of the source-taking functions: a path or document id
     * (string), raw bytes (Uint8Array or array of numbers) or an already converted source.
     */
    private static Object sourceArg(Value value) {
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("source must be a path, an archive path"
                    + " 'archive.zip#entry', bytes or an open document id");
        }
        if (value.isString()) {
            return value.asString();
        }
        if (value.hasArrayElements()) {
            return GraalProxies.toByteArray(value);
        }
        if (value.isHostObject() && value.asHostObject() instanceof JsXmlSource.Resolved resolved) {
            return resolved;
        }
        throw new IllegalArgumentException("source must be a path string, an archive path"
                + " 'archive.zip#entry', bytes (Uint8Array) or an open document id, but was "
                + value.getMetaObject());
    }

    /** Returns an optional argument or {@code null} for missing/null/undefined. */
    private static Value arg(Value[] args, int index) {
        if (args == null || index < 0 || index >= args.length) {
            return null;
        }
        Value value = args[index];
        return (value == null || value.isNull()) ? null : value;
    }

    /** True when the argument at {@code index} looks like a selector/options object. */
    private static boolean isSelector(Value[] args, int index) {
        Value value = arg(args, index);
        return value != null && value.hasMembers() && !value.canExecute();
    }

    /** Converts an options/selector object into a plain map ({@code null} when absent). */
    private static Map<String, Object> optionsArg(Value value) {
        if (value == null) {
            return null;
        }
        if (!value.hasMembers()) {
            throw new IllegalArgumentException("options must be an object (or omitted)");
        }
        Object converted = toJava(value);
        if (!(converted instanceof Map)) {
            throw new IllegalArgumentException("options must be an object of plain values");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) converted;
        return map;
    }

    /**
     * Converts a JS value into plain Java values: strings, numbers, booleans, lists and
     * maps. A {@code RegExp} is converted to its pattern source.
     */
    private static Object toJava(Value value) {
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
            return value.fitsInLong() ? (Object) value.asLong() : (Object) value.asDouble();
        }
        if (value.hasArrayElements()) {
            List<Object> list = new ArrayList<>();
            for (long i = 0; i < value.getArraySize(); i++) {
                list.add(toJava(value.getArrayElement(i)));
            }
            return list;
        }
        if (value.hasMembers()) {
            Set<String> keys = new LinkedHashSet<>();
            for (Object key : value.getMemberKeys()) {
                keys.add(String.valueOf(key));
            }
            if (keys.isEmpty()) {
                // objects without own enumerable keys: a JS RegExp exposes "source"/"flags"
                // as members (isMetaObject() is false for it, so the meta name is no option),
                // a plain empty object such as an empty selector {} must become an empty map
                // (Value.asString() would throw an Invalid coercion)
                if (isRegExp(value)) {
                    return value.getMember("source").asString();
                }
                return new LinkedHashMap<String, Object>();
            }
            Map<String, Object> map = new LinkedHashMap<>();
            for (String key : keys) {
                map.put(key, toJava(value.getMember(key)));
            }
            return map;
        }
        return value.asString();
    }

    private static String requireText(Value value) {
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("a string argument is required");
        }
        if (value.isString()) {
            return value.asString();
        }
        if (value.hasMembers() && !value.hasArrayElements()) {
            // a descriptor object was passed where a path is expected
            Object converted = toJava(value);
            if (converted instanceof Map<?, ?> map) {
                Object path = map.get("path");
                if (path == null) {
                    path = map.get("pathDotted");
                }
                if (path != null) {
                    return String.valueOf(path);
                }
            }
        }
        return value.asString();
    }

    /**
     * Interprets a JS visitor result: only an explicit {@code false} stops the iteration.
     */
    private static boolean isProceed(Value result) {
        return !(result != null && result.isBoolean() && !result.asBoolean());
    }

    /**
     * Detects a JS {@code RegExp}: it has no own enumerable keys, but exposes
     * {@code source} and {@code flags} as members.
     */
    private static boolean isRegExp(Value value) {
        try {
            Value source = value.getMember("source");
            Value flags = value.getMember("flags");
            return source != null && source.isString()
                    && flags != null && flags.isString();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void requireArgs(Value[] args, int min, String signature) {
        if (args == null || args.length < min) {
            throw new IllegalArgumentException("Usage: xml." + signature);
        }
    }
}
