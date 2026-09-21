package org.rogmann.mcp2sdk.xml;

import org.rogmann.mcp2sdk.js.JsUserRuntimeException;
import org.rogmann.mcp2sdk.xml.JsXmlDom.Loaded;
import org.rogmann.mcp2sdk.xml.JsXmlDom.TextCollect;
import org.rogmann.mcp2sdk.xml.JsXmlDom.XPathProblem;
import org.rogmann.mcp2sdk.xml.JsXmlErrors.NotFound;
import org.rogmann.mcp2sdk.xml.JsXmlErrors.Parse;
import org.rogmann.mcp2sdk.xml.JsXmlErrors.TooLarge;

import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Controlled XML reading for the JavaScript sandbox (namespace {@code xml}).
 *
 * <h3>Two engines, one result shape</h3>
 * <p>
 * Discovery ({@code profile}, {@code attrNames}, {@code tree}, {@code sample},
 * {@code count}, {@code scan}, {@code each}, {@code grep}) runs on the streaming engine
 * ({@link JsXmlScan}): constant memory, works on any size, and always knows the line
 * number. Queries ({@code find}, {@code get}, {@code text}, {@code attrs},
 * {@code children}, {@code ancestors}) run on the DOM engine ({@link JsXmlDom}) behind
 * size limits, because XPath needs a tree. Every result says which engine produced it
 * ({@code engine: "stax" | "dom-xpath"}).
 * </p>
 *
 * <h3>Design goal: no silent empty result</h3>
 * <p>
 * A query that finds nothing returns a {@code hint} with the failing step and the names
 * that actually exist at that point; a list result always carries {@code total},
 * {@code returned} and {@code truncated}; attribute and text values are returned as
 * strings and never coerced into numbers, so there is no {@code NaN} path inside the
 * module. Nothing is ever fetched from the network: a {@code DOCTYPE} is resolved as a
 * local file if one exists, and reported as a finding otherwise, so documents with an
 * unreachable DTD stay analysable.
 * </p>
 *
 * <h3>Lifetime</h3>
 * <p>
 * One {@code JsXml} instance belongs to one JavaScript call: {@code JsXmlBridge.wireApi()}
 * creates it and returns {@link #closeAll()} as the {@code AutoCloseable}, so every parsed
 * DOM is released when the script ends. Document ids never survive a tool call.
 * </p>
 */
public final class JsXml {

    /** Option keys accepted by all functions (unknown keys are refused). */
    private static final Set<String> ALLOWED_OPTIONS = Set.of(
            "max", "maxText", "attrs", "text", "values", "maxElements", "maxAttrs", "depth",
            "maxLines", "scope", "name", "attr", "mode", "dtd", "entities", "ns", "lines",
            "strict", "trim", "collapse", "cdata", "maxBytes", "maxNodes", "encoding", "comments");

    /** Values accepted for {@code {mode}}. */
    private static final Set<String> MODES = Set.of("auto", "stax", "xpath", "dom");

    /** Values accepted for {@code {dtd}}. */
    private static final Set<String> DTD_MODES = Set.of("local", "ignore", "entities");

    /** Prefix of generated document ids. */
    private static final String DOC_PREFIX = "doc";

    /** Documents open in this JavaScript call, in open order. */
    private final Map<String, Loaded> documents = new LinkedHashMap<>();

    /** Cache key (display + dtd mode + encoding) to document id, so repeated queries reuse one DOM. */
    private final Map<String, String> byKey = new LinkedHashMap<>();

    /** Sum of document bytes currently held as DOM. */
    private long cachedBytes;

    /** Number of generated document ids. */
    private int documentCounter;

    public JsXml() {
        // per-call instance
    }

    // ========================================================================
    // Documents
    // ========================================================================

    /**
     * Parses a source into a DOM and returns its handle plus a summary of what it is.
     *
     * @param source  path, archive path ({@code archive.zip#entry}), bytes or a handle
     * @param options normalized option map (may be {@code null})
     */
    public Map<String, Object> open(Object source, Map<String, Object> options) {
        Options opts = Options.from(options);
        String display = displayHint(source);
        try {
            Loaded loaded = documentFor(source, opts);
            Map<String, Object> result = envelope("ok", loaded.display, "dom");
            result.put("id", loaded.id);
            result.put("bytes", loaded.bytes);
            result.put("encoding", loaded.encoding);
            result.put("encodingSource", loaded.encodingSource);
            result.put("root", loaded.document.getDocumentElement().getNodeName());
            result.put("prolog", loaded.prolog);
            result.put("stats", statsOf(loaded));
            result.put("limits", limitsOf(opts));
            result.put("findings", loaded.findings);
            result.put("errors", loaded.errorFindings());
            result.put("machineLine", "XMLOPEN file=" + loaded.display + " nodes=" + loaded.nodeCount()
                    + " depth=" + loaded.maxDepth + " encoding=" + loaded.encoding
                    + " doctype=" + doctypeToken(loaded) + " errors=" + loaded.errorFindings());
            return result;
        } catch (RuntimeException e) {
            return failed("XMLOPEN", display, e);
        }
    }

    /** Parses an XML string (a fragment taken out of another document). */
    public Map<String, Object> openText(String text, Map<String, Object> options) {
        if (text == null) {
            throw new IllegalArgumentException("Usage: xml.openText(text[, options])");
        }
        return open(JsXmlSource.ofText(text), options);
    }

    /** Lists the documents currently open in this call. */
    public Map<String, Object> docs() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Loaded loaded : documents.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", loaded.id);
            entry.put("file", loaded.display);
            entry.put("bytes", loaded.bytes);
            entry.put("elements", loaded.elementCount);
            entry.put("nodes", loaded.nodeCount());
            entry.put("lines", loaded.linesAttached);
            list.add(entry);
        }
        Map<String, Object> result = envelope("ok", null, null);
        result.put("docs", list);
        result.put("total", list.size());
        result.put("returned", list.size());
        result.put("truncated", false);
        result.put("cachedBytes", cachedBytes);
        result.put("cachedLimit", JsXmlLimits.MAX_CACHED_BYTES);
        result.put("machineLine", "XMLDOCS open=" + list.size() + " cachedBytes=" + cachedBytes);
        return result;
    }

    /**
     * Closes one or more documents. Idempotent; unknown ids are reported, not fatal.
     *
     * @param idOrIds a document id or a list of ids
     */
    public Map<String, Object> close(Object idOrIds) {
        List<String> ids = new ArrayList<>();
        if (idOrIds instanceof Collection<?> collection) {
            for (Object item : collection) {
                ids.add(String.valueOf(item));
            }
        } else if (idOrIds != null) {
            ids.add(String.valueOf(idOrIds));
        }
        List<String> closed = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String id : ids) {
            Loaded removed = documents.remove(id);
            if (removed == null) {
                unknown.add(id);
                continue;
            }
            cachedBytes = Math.max(0, cachedBytes - removed.bytes);
            byKey.values().removeIf(value -> value.equals(id));
            closed.add(id);
        }
        Map<String, Object> result = envelope(unknown.isEmpty() ? "ok" : "partial", null, null);
        result.put("closed", closed);
        result.put("unknown", unknown);
        result.put("machineLine", "XMLCLOSE closed=" + closed.size() + " unknown=" + unknown.size());
        return result;
    }

    /** Releases every DOM. Called at the end of the JavaScript execution. */
    public void closeAll() {
        documents.clear();
        byKey.clear();
        cachedBytes = 0;
    }

    // ========================================================================
    // Discovery (streaming)
    // ========================================================================

    /**
     * The "what is this document?" answer: element census with attribute names and counts.
     */
    public Map<String, Object> profile(Object source, Map<String, Object> options) {
        Options opts = Options.from(options);
        return withSource(source, "XMLPROFILE", resolved -> {
            JsXmlScan.Profile profile = JsXmlScan.profile(resolved, opts.maxElements, opts.values,
                    cdataDetection());
            List<Map<String, Object>> elements = JsXmlScan.censusList(profile, opts.maxElements,
                    opts.maxAttrs, opts.values);
            Map<String, Object> result = envelope("ok", resolved.display(), "stax");
            result.put("root", profile.root);
            result.put("namespaces", namespacesOf(profile));
            result.put("prolog", profileProlog(profile));
            result.put("elements", elements);
            result.put("stats", profileStats(profile));
            int total = profile.census.size();
            result.put("total", total);
            result.put("returned", elements.size());
            result.put("truncated", profile.truncated);
            result.put("findings", profileFindings(profile));
            result.put("errors", 0);
            result.put("machineLine", "XMLPROFILE file=" + resolved.display() + " elements=" + total
                    + " attrs=" + profile.attrNamesTotal + " nodes=" + profile.elementCount
                    + " truncated=" + (profile.truncated ? 1 : 0));
            return result;
        });
    }

    /** Attribute names of every element with the given name (quick spelling check). */
    public Map<String, Object> attrNames(Object source, String elementName) {
        if (elementName == null || elementName.isBlank()) {
            throw new IllegalArgumentException("Usage: xml.attrNames(source, elementName)");
        }
        return withSource(source, "XMLATTRNAMES", resolved -> {
            JsXmlScan.Profile profile = JsXmlScan.profile(resolved, 0, false, cdataDetection());
            Map<String, Object> answer = JsXmlScan.attrNames(profile, elementName.trim());
            Map<String, Object> result = envelope("ok", resolved.display(), "stax");
            result.putAll(answer);
            result.put("machineLine", "XMLATTRNAMES file=" + resolved.display()
                    + " element=" + answer.get("element") + " attrs=" + answer.get("total"));
            return result;
        });
    }

    /** Counts elements matching a selector (no materialization). */
    public Map<String, Object> count(Object source, Map<String, Object> selector) {
        JsXmlScan.Selector parsed = JsXmlScan.parseSelector(selector);
        return withSource(source, "XMLCOUNT", resolved -> {
            Map<String, Object> counted = JsXmlScan.count(resolved, parsed);
            Map<String, Object> result = envelope("ok", resolved.display(), "stax");
            result.putAll(counted);
            result.put("machineLine", "XMLCOUNT file=" + resolved.display()
                    + " matched=" + counted.get("matched") + " visited=" + counted.get("visited"));
            return result;
        });
    }

    /** Structure skeleton as text: names, counts, attribute names. */
    public String tree(Object source, Map<String, Object> options) {
        Options opts = Options.from(options);
        Map<String, Object> result = withSource(source, "XMLTREE", resolved -> {
            List<String> lines = JsXmlScan.tree(resolved, opts.depth, opts.maxLines, opts.attrs);
            Map<String, Object> inner = envelope("ok", resolved.display(), "stax");
            inner.put("lines", lines);
            resultLines(inner, lines, opts.maxLines);
            return inner;
        });
        Object lines = result.get("lines");
        if (lines instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object line : list) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
        return "[xml.tree] " + result.get("status") + ": " + result.get("message");
    }

    /** Full tree result (same data as {@link #tree} but as an envelope). */
    public Map<String, Object> treeResult(Object source, Map<String, Object> options) {
        Options opts = Options.from(options);
        return withSource(source, "XMLTREE", resolved -> {
            List<String> lines = JsXmlScan.tree(resolved, opts.depth, opts.maxLines, opts.attrs);
            Map<String, Object> result = envelope("ok", resolved.display(), "stax");
            result.put("lines", lines);
            resultLines(result, lines, opts.maxLines);
            return result;
        });
    }

    /** Namespace report of a document. */
    public Map<String, Object> namespaces(Object source, Map<String, Object> options) {
        Options opts = Options.from(options);
        if (isOpenDocument(source)) {
            return withDocument(source, opts, "XMLNS", loaded -> {
                List<Map<String, Object>> namespaces = JsXmlDom.namespaces(loaded);
                Map<String, Object> result = envelope("ok", loaded.display, "dom-xpath");
                result.put("namespaces", namespaces);
                String defaultUri = loaded.document.getDocumentElement().getNamespaceURI();
                result.put("defaultNamespace", defaultUri);
                result.put("total", namespaces.size());
                result.put("returned", namespaces.size());
                result.put("truncated", false);
                result.put("machineLine", "XMLNS file=" + loaded.display + " namespaces="
                        + namespaces.size());
                return result;
            });
        }
        return withSource(source, "XMLNS", resolved -> {
            JsXmlScan.Profile profile = JsXmlScan.profile(resolved, 0, false, cdataDetection());
            List<Map<String, Object>> namespaces = namespacesOf(profile);
            Map<String, Object> result = envelope("ok", resolved.display(), "stax");
            result.put("namespaces", namespaces);
            result.put("total", namespaces.size());
            result.put("returned", namespaces.size());
            result.put("truncated", false);
            result.put("machineLine", "XMLNS file=" + resolved.display() + " namespaces="
                    + namespaces.size());
            return result;
        });
    }

    // ========================================================================
    // Query and iteration
    // ========================================================================

    /**
     * XPath 1.0 query over a DOM, capped and explained.
     *
     * @param source     document handle, path, archive path or bytes
     * @param expression XPath 1.0 expression
     */
    public Map<String, Object> find(Object source, String expression, Map<String, Object> options) {
        Options opts = Options.from(options);
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("Usage: xml.find(source, xpath[, options])");
        }
        if (opts.lines) {
            // line numbers are attached before the descriptors are built
            try {
                JsXmlDom.attachLines(documentFor(source, opts));
            } catch (RuntimeException e) {
                return failed("XMLFIND", displayHint(source), e);
            }
        }
        return withDocument(source, opts, "XMLFIND", loaded -> {
            Map<String, String> namespaceMap = JsXmlDom.namespaceMap(loaded, opts.ns);
            String nsKey = namespaceKey(namespaceMap);
            List<Node> nodes;
            XPathProblem problem = null;
            try {
                nodes = JsXmlDom.evaluateNodes(loaded, expression, namespaceMap, nsKey);
            } catch (XPathProblem e) {
                nodes = List.of();
                problem = e;
            }
            int preview = previewChars(opts);
            List<Map<String, Object>> described = new ArrayList<>();
            for (int i = 0; i < Math.min(nodes.size(), opts.max); i++) {
                described.add(nodeDescriptor(loaded, nodes.get(i), preview));
            }
            Map<String, Object> result = envelope("ok", loaded.display, "dom-xpath");
            result.put("query", expression);
            result.put("total", nodes.size());
            result.put("returned", described.size());
            result.put("truncated", nodes.size() > described.size());
            result.put("nodes", described);
            if (nodes.isEmpty()) {
                result.put("hint", JsXmlDom.zeroHitHint(loaded, expression, namespaceMap, nsKey, problem));
            }
            result.put("machineLine", "XMLFIND file=" + loaded.display + " total=" + nodes.size()
                    + " returned=" + described.size());
            return result;
        });
    }

    /**
     * Iterates matches without materializing them. A string selector is XPath 1.0 (DOM);
     * a map selector is the streaming selector (StAX). The visitor returns {@code false}
     * to stop early.
     */
    public Map<String, Object> each(Object source, Object selectorOrExpression,
                                    JsXmlScan.Visitor visitor, Map<String, Object> options) {
        Options opts = Options.from(options);
        if (visitor == null) {
            throw new IllegalArgumentException("Usage: xml.each(source[, xpathOrSelector], fn[, options])");
        }
        if (selectorOrExpression instanceof Map<?, ?> rawSelector) {
            JsXmlScan.Selector selector = JsXmlScan.parseSelector(toObjectMap("selector", rawSelector));
            return withSource(source, "XMLEACH", resolved -> {
                // streaming is unlimited unless {max} is given explicitly: a silent default
                // cap would be exactly the false green this module exists to prevent
                Map<String, Object> streamed = JsXmlScan.each(resolved, selector, visitor,
                        opts.maxSet ? opts.max : 0, previewChars(opts));
                Map<String, Object> result = envelope("ok", resolved.display(), "stax");
                result.putAll(streamed);
                result.put("machineLine", "XMLEACH file=" + resolved.display()
                        + " matched=" + streamed.get("matched") + " visited=" + streamed.get("visited")
                        + " stopped=" + (Boolean.TRUE.equals(streamed.get("stoppedEarly")) ? 1 : 0));
                return result;
            });
        }
        if (selectorOrExpression instanceof CharSequence expression) {
            if (opts.lines) {
                try {
                    JsXmlDom.attachLines(documentFor(source, opts));
                } catch (RuntimeException e) {
                    return failed("XMLEACH", displayHint(source), e);
                }
            }
            return withDocument(source, opts, "XMLEACH", loaded -> {
                Map<String, String> namespaceMap = JsXmlDom.namespaceMap(loaded, opts.ns);
                List<Node> nodes;
                XPathProblem problem = null;
                try {
                    nodes = JsXmlDom.evaluateNodes(loaded, expression.toString(), namespaceMap,
                            namespaceKey(namespaceMap));
                } catch (XPathProblem e) {
                    nodes = List.of();
                    problem = e;
                }
                int preview = previewChars(opts);
                int cap = opts.maxSet ? opts.max : 0;   // streaming: unlimited unless {max}
                int visited = 0;
                boolean stopped = false;
                for (Node node : nodes) {
                    if (cap > 0 && visited >= cap) {
                        break;
                    }
                    visited++;
                    if (!visitor.visit(nodeDescriptor(loaded, node, preview))) {
                        stopped = true;
                        break;
                    }
                }
                Map<String, Object> result = envelope("ok", loaded.display, "dom-xpath");
                result.put("query", expression.toString());
                result.put("matched", nodes.size());
                result.put("visited", visited);
                result.put("stoppedEarly", stopped);
                result.put("stoppedBy", stopped ? "handler" : null);
                if (nodes.isEmpty() && problem != null) {
                    result.put("hint", JsXmlDom.zeroHitHint(loaded, expression.toString(), namespaceMap,
                            namespaceKey(namespaceMap), problem));
                }
                result.put("machineLine", "XMLEACH file=" + loaded.display + " matched=" + nodes.size()
                        + " visited=" + visited + " stopped=" + (stopped ? 1 : 0));
                return result;
            });
        }
        // no selector at all: stream every element (StAX), unlimited unless {max} is given
        return withSource(source, "XMLEACH", resolved -> {
            Map<String, Object> streamed = JsXmlScan.each(resolved, new JsXmlScan.Selector(), visitor,
                    opts.maxSet ? opts.max : 0, previewChars(opts));
            Map<String, Object> result = envelope("ok", resolved.display(), "stax");
            result.putAll(streamed);
            result.put("machineLine", "XMLEACH file=" + resolved.display()
                    + " matched=" + streamed.get("matched") + " visited=" + streamed.get("visited")
                    + " stopped=" + (Boolean.TRUE.equals(streamed.get("stoppedEarly")) ? 1 : 0));
            return result;
        });
    }

    /** Streaming selector with a capped materialized result. */
    public Map<String, Object> scan(Object source, Map<String, Object> selector,
                                    Map<String, Object> options) {
        Options opts = Options.from(options);
        JsXmlScan.Selector parsed = JsXmlScan.parseSelector(selector);
        return withSource(source, "XMLSCAN", resolved -> {
            Map<String, Object> scanned = JsXmlScan.scan(resolved, parsed, opts.max, previewChars(opts));
            Map<String, Object> result = envelope("ok", resolved.display(), "stax");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> matches = (List<Map<String, Object>>) scanned.get("matches");
            result.put("matches", matches);
            result.put("total", scanned.get("matched"));
            result.put("visited", scanned.get("visited"));
            result.put("returned", matches.size());
            result.put("truncated", scanned.get("matched") instanceof Integer total
                    && total > matches.size());
            if (matches.isEmpty()) {
                result.put("hint", selectorHint(parsed));
            }
            result.put("machineLine", "XMLSCAN file=" + resolved.display() + " matched="
                    + scanned.get("matched") + " returned=" + matches.size());
            return result;
        });
    }

    /**
     * Content/attribute search that reports the element hierarchy of every hit - the part
     * a bytescan cannot deliver.
     */
    public Map<String, Object> grep(Object source, Object pattern, Map<String, Object> options) {
        Options opts = Options.from(options);
        Pattern compiled = JsXmlScan.compilePattern("pattern", pattern);
        return withSource(source, "XMLGREP", resolved -> {
            Map<String, Object> grepped = JsXmlScan.grep(resolved, compiled, opts.scope, opts.name,
                    opts.attr, opts.max, previewChars(opts));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> matches = (List<Map<String, Object>>) grepped.get("matches");
            for (Map<String, Object> match : matches) {
                match.put("display", resolved.display() + ":" + match.get("line") + ": "
                        + match.get("pathDotted")
                        + (match.get("attr") == null ? "" : " @" + match.get("attr"))
                        + ": " + match.get("text"));
            }
            Map<String, Object> result = envelope("ok", resolved.display(), "stax");
            result.put("pattern", pattern == null ? "" : String.valueOf(pattern));
            result.put("scope", opts.scope);
            result.put("matches", matches);
            result.put("total", grepped.get("matched"));
            result.put("visited", grepped.get("visited"));
            result.put("returned", matches.size());
            result.put("truncated", grepped.get("matched") instanceof Integer total
                    && total > matches.size());
            result.put("machineLine", "XMLGREP file=" + resolved.display() + " pattern="
                    + result.get("pattern") + " matched=" + grepped.get("matched")
                    + " returned=" + matches.size());
            return result;
        });
    }

    /** First occurrences of an element in full (the fastest way to see real attribute spellings). */
    public Map<String, Object> sample(Object source, String elementName, Map<String, Object> options) {
        Options opts = Options.from(options);
        if (elementName == null || elementName.isBlank()) {
            throw new IllegalArgumentException("Usage: xml.sample(source, elementName[, options])");
        }
        return withSource(source, "XMLSAMPLE", resolved -> {
            Map<String, Object> sampled = JsXmlScan.sample(resolved, elementName.trim(), opts.max,
                    previewChars(opts));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> nodes = (List<Map<String, Object>>) sampled.get("nodes");
            Map<String, Object> result = envelope("ok", resolved.display(), "stax");
            result.put("nodes", nodes);
            result.put("total", sampled.get("matched"));
            result.put("visited", sampled.get("visited"));
            result.put("returned", nodes.size());
            result.put("truncated", false);
            if (nodes.isEmpty()) {
                result.put("hint", hintUnknownElement(
                        JsXmlScan.elementNames(resolved), elementName.trim()));
            }
            result.put("machineLine", "XMLSAMPLE file=" + resolved.display() + " element="
                    + elementName + " matched=" + sampled.get("matched"));
            return result;
        });
    }

    // ========================================================================
    // Node access
    // ========================================================================

    /** Attributes of a node as a plain map of strings. */
    public Map<String, Object> attrs(Object source, Object node) {
        Options opts = Options.from(null);
        return withDocument(source, opts, "XMLATTRS", loaded -> {
            Element element = requireElement(loaded, node);
            Map<String, Object> result = envelope("ok", loaded.display, "dom-xpath");
            result.put("path", JsXmlDom.pathOf(element));
            result.put("attrs", JsXmlDom.attrsOf(element));
            result.put("machineLine", "XMLATTRS file=" + loaded.display + " attrs="
                    + JsXmlDom.attrsOf(element).size());
            return result;
        });
    }

    /**
     * Text content of a node.
     *
     * <p>
     * Non-strict (default) truncates at {@code {max}} and says so inside the returned
     * string; {@code {strict: true}} delivers the complete text or refuses, which is what
     * a check against a specification needs.
     * </p>
     */
    public String text(Object source, Object node, Map<String, Object> options) {
        Options opts = Options.from(options);
        Map<String, Object> result = withDocument(source, opts, "XMLTEXT", loaded -> {
            Element element = requireElement(loaded, node);
            int limit = textLimit(opts);
            TextCollect collect = JsXmlDom.collectText(element, limit, "skip".equals(opts.cdata));
            boolean truncated = collect.chars > collect.preview.length();
            String value = JsXmlDom.applyTextOptions(collect.preview.toString(), opts.trim, opts.collapse);
            if (truncated && opts.strict) {
                throw new JsUserRuntimeException("xml.text: " + JsXmlDom.pathOf(element) + " holds "
                        + collect.chars + " characters, more than the " + limit + " character limit"
                        + " - {strict:true} delivers all or nothing; raise {max} (ceiling "
                        + JsXmlLimits.MAX_TEXT_CHARS + ") or stream the document with xml.each()");
            }
            if (truncated) {
                value = value + "…[truncated: " + collect.preview.length() + " of " + collect.chars
                        + " chars - use xml.text(doc, node, {strict: true}) for all or nothing]";
            }
            Map<String, Object> inner = new LinkedHashMap<>();
            inner.put("text", value);
            return inner;
        });
        Object value = result.get("text");
        return (value == null) ? String.valueOf(result.get("message")) : String.valueOf(value);
    }

    /** Text of the CDATA sections inside a node (empty string when there are none). */
    public String cdata(Object source, Object node, Map<String, Object> options) {
        Options opts = Options.from(options);
        Map<String, Object> result = withDocument(source, opts, "XMLCDATA", loaded -> {
            Element element = requireElement(loaded, node);
            StringBuilder collected = new StringBuilder();
            int[] chars = new int[1];
            int limit = textLimit(opts);
            collectCdata(element, collected, chars, limit);
            String value = collected.toString();
            if (chars[0] > collected.length()) {
                if (opts.strict) {
                    throw new JsUserRuntimeException("xml.cdata: " + JsXmlDom.pathOf(element)
                            + " holds " + chars[0] + " CDATA characters, more than the limit of "
                            + limit + " - {strict:true} delivers all or nothing");
                }
                value = value + "…[truncated: " + collected.length() + " of " + chars[0] + " chars]";
            }
            Map<String, Object> inner = new LinkedHashMap<>();
            inner.put("text", value);
            return inner;
        });
        Object value = result.get("text");
        return (value == null) ? String.valueOf(result.get("message")) : String.valueOf(value);
    }

    /** Element children of a node. */
    public Map<String, Object> children(Object source, Object node, Map<String, Object> options) {
        Options opts = Options.from(options);
        if (opts.lines) {
            try {
                JsXmlDom.attachLines(documentFor(source, opts));
            } catch (RuntimeException e) {
                return failed("XMLCHILDREN", displayHint(source), e);
            }
        }
        return withDocument(source, opts, "XMLCHILDREN", loaded -> {
            Element element = requireElement(loaded, node);
            List<Element> children = JsXmlDom.childElements(element);
            List<Map<String, Object>> described = new ArrayList<>();
            int preview = previewChars(opts);
            for (int i = 0; i < Math.min(children.size(), opts.max); i++) {
                described.add(JsXmlDom.describe(loaded, children.get(i), preview, opts.lines));
            }
            Map<String, Object> result = envelope("ok", loaded.display, "dom-xpath");
            result.put("path", JsXmlDom.pathOf(element));
            result.put("total", children.size());
            result.put("returned", described.size());
            result.put("truncated", children.size() > described.size());
            result.put("nodes", described);
            result.put("machineLine", "XMLCHILDREN file=" + loaded.display + " total="
                    + children.size());
            return result;
        });
    }

    /** Ancestors of a node, document element first. */
    public Map<String, Object> ancestors(Object source, Object node) {
        Options opts = Options.from(null);
        return withDocument(source, opts, "XMLANCESTORS", loaded -> {
            Element element = requireElement(loaded, node);
            List<Element> ancestors = JsXmlDom.ancestorsOf(element);
            List<Map<String, Object>> described = new ArrayList<>();
            for (Element ancestor : ancestors) {
                described.add(JsXmlDom.describe(loaded, ancestor, 0, opts.lines));
            }
            Map<String, Object> result = envelope("ok", loaded.display, "dom-xpath");
            result.put("path", JsXmlDom.pathOf(element));
            result.put("total", ancestors.size());
            result.put("returned", ancestors.size());
            result.put("truncated", false);
            result.put("nodes", described);
            result.put("machineLine", "XMLANCESTORS file=" + loaded.display + " total="
                    + ancestors.size());
            return result;
        });
    }

    /** Re-fetches a node by descriptor, node id or path. */
    public Map<String, Object> get(Object source, Object node, Map<String, Object> options) {
        Options opts = Options.from(options);
        if (opts.lines) {
            try {
                JsXmlDom.attachLines(documentFor(source, opts));
            } catch (RuntimeException e) {
                return failed("XMLGET", displayHint(source), e);
            }
        }
        return withDocument(source, opts, "XMLGET", loaded -> {
            Element element = requireElement(loaded, node);
            Map<String, Object> result = envelope("ok", loaded.display, "dom-xpath");
            result.put("node", JsXmlDom.describe(loaded, element, previewChars(opts), opts.lines));
            result.put("machineLine", "XMLGET file=" + loaded.display + " node="
                    + JsXmlDom.pathOf(element));
            return result;
        });
    }

    // ========================================================================
    // Internals: sources and documents
    // ========================================================================

    /** A streaming action over a resolved source. */
    private interface SourceAction {
        Map<String, Object> run(JsXmlSource.Resolved source);
    }

    /** A DOM action over an open document. */
    private interface DocumentAction {
        Map<String, Object> run(Loaded loaded);
    }

    private Map<String, Object> withSource(Object source, String command, SourceAction action) {
        String display = displayHint(source);
        JsXmlSource.Resolved resolved;
        try {
            resolved = resolvedOf(source);
            display = resolved.display();
        } catch (RuntimeException e) {
            return failed(command, display, e);
        }
        try {
            return action.run(resolved);
        } catch (RuntimeException e) {
            return failed(command, display, e);
        }
    }

    private Map<String, Object> withDocument(Object source, Options opts, String command,
                                             DocumentAction action) {
        String display = displayHint(source);
        Loaded loaded;
        try {
            loaded = documentFor(source, opts);
            display = loaded.display;
        } catch (RuntimeException e) {
            return failed(command, display, e);
        }
        try {
            return action.run(loaded);
        } catch (RuntimeException e) {
            return failed(command, display, e);
        }
    }

    /** True when the given source is the id of an already-open document. */
    private boolean isOpenDocument(Object source) {
        return source instanceof String id && documents.containsKey(id);
    }

    /** Resolves a source, reusing the source of an already-open document handle. */
    private JsXmlSource.Resolved resolvedOf(Object source) {
        if (source instanceof String id) {
            Loaded loaded = documents.get(id);
            if (loaded != null) {
                return loaded.source;
            }
        }
        return toSource(source);
    }

    /** Returns the DOM of a source, opening and caching it when needed. */
    private Loaded documentFor(Object source, Options opts) {
        if (source instanceof String id) {
            Loaded known = documents.get(id);
            if (known != null) {
                if (opts.lines) {
                    JsXmlDom.attachLines(known);
                }
                return known;
            }
        }
        JsXmlSource.Resolved resolved = toSource(source);
        String key = resolved.display() + "|" + opts.dtd + "|" + (opts.encoding == null ? "" : opts.encoding);
        String existingId = byKey.get(key);
        if (existingId != null) {
            Loaded existing = documents.get(existingId);
            if (existing != null) {
                if (opts.lines) {
                    JsXmlDom.attachLines(existing);
                }
                return existing;
            }
        }
        if (cachedBytes + Math.max(resolved.sizeHint(), 0) > JsXmlLimits.MAX_CACHED_BYTES) {
            throw new TooLarge("Keeping " + resolved.display() + " open as DOM would exceed the cache"
                    + " limit of " + JsXmlLimits.MAX_CACHED_BYTES + " bytes (used: " + cachedBytes
                    + ") - close documents with xml.close(id) or stream with xml.each()/xml.scan()");
        }
        String id = DOC_PREFIX + (++documentCounter);
        Loaded loaded = JsXmlDom.parse(resolved, id, opts.dtd, opts.entities, opts.encoding,
                opts.maxBytes, opts.maxNodes);
        documents.put(id, loaded);
        byKey.put(key, id);
        cachedBytes += loaded.bytes;
        if (opts.lines) {
            JsXmlDom.attachLines(loaded);
        }
        return loaded;
    }

    private static JsXmlSource.Resolved toSource(Object source) {
        if (source instanceof JsXmlSource.Resolved resolved) {
            return resolved;
        }
        if (source instanceof String path) {
            return JsXmlSource.resolve(path);
        }
        if (source instanceof byte[] bytes) {
            return JsXmlSource.ofBytes(bytes);
        }
        throw new IllegalArgumentException("source must be a path, an archive path"
                + " 'archive.zip#entry', bytes (Uint8Array) or an open document id");
    }

    /** Resolves a node argument (descriptor, node id or path) to an element. */
    private static Element requireElement(Loaded loaded, Object node) {
        if (node == null) {
            throw new IllegalArgumentException("a node is required: a descriptor from xml.find(),"
                    + " a node id or a path such as '/root/node[1]'");
        }
        if (node instanceof Element element) {
            return element;
        }
        if (node instanceof Number number) {
            Node found = loaded.nodeOf(number.intValue());
            if (found instanceof Element element) {
                return element;
            }
            throw new JsUserRuntimeException("unknown node id " + number
                    + " for document " + loaded.id + " (ids are valid for the current call only,"
                    + " and only for nodes that were returned before)");
        }
        if (node instanceof CharSequence text) {
            String spec = text.toString().trim();
            if (spec.isEmpty()) {
                throw new IllegalArgumentException("node path must not be empty");
            }
            // a descriptor printed as an object: use its path or id member
            Element resolved = JsXmlDom.resolve(loaded, spec);
            if (resolved == null) {
                throw new JsUserRuntimeException("path '" + spec + "' does not exist in "
                        + loaded.display + " - xml.tree(source) shows the structure,"
                        + " xml.find(source, xpath) lists matches with their path");
            }
            return resolved;
        }
        if (node instanceof Map<?, ?> map) {
            Object id = map.get("id");
            if (id instanceof Number number) {
                Node found = loaded.nodeOf(number.intValue());
                if (found instanceof Element element) {
                    return element;
                }
            }
            Object path = map.get("path");
            if (path == null) {
                path = map.get("pathDotted");
            }
            if (path instanceof String spec) {
                Element resolved = JsXmlDom.resolve(loaded, spec);
                if (resolved != null) {
                    return resolved;
                }
            }
            throw new JsUserRuntimeException("the given descriptor does not resolve to an element of "
                    + loaded.display + " (descriptors are valid within one call and one document)");
        }
        throw new IllegalArgumentException("node must be a descriptor from xml.find(), a node id"
                + " or a path string, but was " + node.getClass().getSimpleName());
    }

    // ========================================================================
    // Internals: results
    // ========================================================================

    static Map<String, Object> finding(String severity, Integer line, String message) {
        Map<String, Object> finding = new LinkedHashMap<>();
        if (line != null && line > 0) {
            finding.put("line", line);
        }
        finding.put("severity", severity);
        finding.put("message", message);
        return finding;
    }

    private static Map<String, Object> envelope(String status, String file, String engine) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        if (file != null) {
            result.put("file", file);
        }
        if (engine != null) {
            result.put("engine", engine);
        }
        return result;
    }

    /**
     * Maps the expected failure modes onto an explicit {@code status}. Everything else
     * (usage errors, access violations) is rethrown so the tool reports it as an error.
     */
    private static Map<String, Object> failed(String command, String file, RuntimeException e) {
        if (e instanceof Parse parse) {
            Map<String, Object> result = envelope("parseError", file, null);
            result.put("parseError", parse.toMap());
            result.put("message", parse.getMessage());
            List<Map<String, Object>> findings = new ArrayList<>();
            findings.add(finding("error", parse.line(), parse.getMessage()));
            result.put("findings", findings);
            result.put("errors", 1);
            result.put("machineLine", command + " file=" + file + " status=parseError line="
                    + parse.line() + " errors=1");
            return result;
        }
        if (e instanceof TooLarge tooLarge) {
            Map<String, Object> result = envelope("tooLarge", file, null);
            result.put("message", tooLarge.getMessage());
            result.put("machineLine", command + " file=" + file + " status=tooLarge errors=1");
            return result;
        }
        if (e instanceof NotFound notFound) {
            Map<String, Object> result = envelope("notFound", file, null);
            result.put("message", notFound.getMessage());
            result.put("machineLine", command + " file=" + file + " status=notFound errors=1");
            return result;
        }
        throw e;
    }

    /** Descriptor for any XPath result node (elements, attributes and text included). */
    private static Map<String, Object> nodeDescriptor(Loaded loaded, Node node, int previewChars) {
        if (node instanceof Element element) {
            return JsXmlDom.describe(loaded, element, previewChars, loaded.linesAttached);
        }
        Map<String, Object> descriptor = new LinkedHashMap<>();
        if (node instanceof Attr attribute) {
            Node owner = attribute.getOwnerElement();
            descriptor.put("name", attribute.getName());
            descriptor.put("kind", "attribute");
            descriptor.put("path", (owner instanceof Element ownerElement)
                    ? JsXmlDom.pathOf(ownerElement) + "/@" + attribute.getName() : null);
            descriptor.put("value", attribute.getValue());
            descriptor.put("text", attribute.getValue());
            return descriptor;
        }
        descriptor.put("name", node.getNodeName());
        descriptor.put("kind", "text");
        String value = node.getNodeValue();
        descriptor.put("text", (value == null) ? "" : truncate(value, previewChars));
        descriptor.put("textChars", (value == null) ? 0 : value.length());
        return descriptor;
    }

    /** Hint for a selector that found nothing (streaming side, no DOM available). */
    private static Map<String, Object> selectorHint(JsXmlScan.Selector selector) {
        Map<String, Object> hint = new LinkedHashMap<>();
        hint.put("reason", (selector.name != null) ? "element-unknown" : "predicate-empty");
        hint.put("selector", selectorDescription(selector));
        hint.put("try", "xml.profile(source) lists every element with its attribute names;"
                + " xml.attrNames(source, \"" + (selector.name == null ? "name" : selector.name)
                + "\") lists the attributes of one element");
        return hint;
    }

    /** Hint for an element name that does not exist, listing what does. */
    static Map<String, Object> hintUnknownElement(Collection<String> known, String queried) {
        Map<String, Object> hint = new LinkedHashMap<>();
        hint.put("reason", "element-unknown");
        hint.put("queried", queried);
        List<String> names = new ArrayList<>(known);
        names.sort(null);
        hint.put("elements", names.size() > 40 ? names.subList(0, 40) : names);
        List<String> similar = new ArrayList<>();
        for (String name : names) {
            if (similar.size() >= 5) {
                break;
            }
            if (isSimilar(name, queried)) {
                similar.add(name);
            }
        }
        if (!similar.isEmpty()) {
            hint.put("similar", similar);
        }
        return hint;
    }

    /** Cheap similarity: shared prefix or substring (never a guess, only candidates). */
    private static boolean isSimilar(String candidate, String queried) {
        String a = candidate.toLowerCase(java.util.Locale.ROOT);
        String b = queried.toLowerCase(java.util.Locale.ROOT);
        if (a.equals(b) || a.contains(b) || b.contains(a)) {
            return true;
        }
        int common = 0;
        while (common < a.length() && common < b.length() && a.charAt(common) == b.charAt(common)) {
            common++;
        }
        return common >= 3;
    }

    private static Map<String, Object> selectorDescription(JsXmlScan.Selector selector) {
        Map<String, Object> description = new LinkedHashMap<>();
        if (selector.name != null) {
            description.put("name", selector.name);
        }
        if (selector.ancestor != null) {
            description.put("ancestor", selector.ancestor);
        }
        if (!selector.attrs.isEmpty()) {
            description.put("attrs", selector.attrs);
        }
        if (!selector.hasAttrs.isEmpty()) {
            description.put("hasAttrs", selector.hasAttrs);
        }
        if (selector.depth != null) {
            description.put("depth", selector.depth);
        }
        return description;
    }

    private static void collectCdata(Node node, StringBuilder out, int[] chars, int cap) {
        if (node.getNodeType() == Node.CDATA_SECTION_NODE) {
            String value = node.getNodeValue();
            if (value != null) {
                chars[0] += value.length();
                if (out.length() < cap) {
                    out.append(value, 0, Math.min(value.length(), cap - out.length()));
                }
            }
            return;
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            collectCdata(child, out, chars, cap);
        }
    }

    private static Map<String, Object> statsOf(Loaded loaded) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("elements", loaded.elementCount);
        stats.put("nodes", loaded.nodeCount());
        stats.put("maxDepth", loaded.maxDepth);
        stats.put("comments", loaded.commentCount);
        stats.put("processingInstructions", loaded.piCount);
        stats.put("cdataSections", loaded.cdataSections);
        return stats;
    }

    private Map<String, Object> limitsOf(Options opts) {
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("maxBytes", opts.maxBytes);
        limits.put("maxNodes", opts.maxNodes);
        limits.put("cachedBytes", cachedBytes);
        limits.put("cachedLimit", JsXmlLimits.MAX_CACHED_BYTES);
        return limits;
    }

    private static String doctypeToken(Loaded loaded) {
        Object doctype = loaded.prolog.get("doctype");
        if (!(doctype instanceof Map<?, ?> map) || map.get("name") == null) {
            return "none";
        }
        return Boolean.TRUE.equals(map.get("loaded")) ? "local" : "declared";
    }

    private static Map<String, Object> profileProlog(JsXmlScan.Profile profile) {
        Map<String, Object> prolog = new LinkedHashMap<>();
        prolog.put("doctype", profile.doctype);
        prolog.put("processingInstructions", new ArrayList<>(profile.piTargets));
        return prolog;
    }

    private static List<Map<String, Object>> profileFindings(JsXmlScan.Profile profile) {
        List<Map<String, Object>> findings = new ArrayList<>();
        if (profile.stoppedByLimit) {
            findings.add(finding("warning", null,
                    "profiling stopped after the element limit of the aggregation pass;"
                            + " the census is complete only for the visited part"));
        }
        Object doctype = profile.doctype;
        if (doctype instanceof Map<?, ?> map && map.get("systemId") != null) {
            findings.add(finding("info", null, "DOCTYPE references '" + map.get("systemId")
                    + "' - the streaming pass never loads or resolves a DTD"
                    + " (use xml.open() with {dtd: 'local'} to resolve it as a local file)"));
        }
        return findings;
    }

    private static Map<String, Object> profileStats(JsXmlScan.Profile profile) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("elements", profile.elementCount);
        stats.put("maxDepth", profile.maxDepth);
        stats.put("comments", profile.comments);
        stats.put("processingInstructions", profile.pis);
        stats.put("cdataSections", profile.cdataSections);
        stats.put("cdataDetection", profile.cdataDetection);
        return stats;
    }

    /**
     * Namespace report from the streaming pass. The StAX reader is namespace-aware, so a
     * prefix map is maintained per branch while scanning.
     */
    private static List<Map<String, Object>> namespacesOf(JsXmlScan.Profile profile) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : profile.namespaceUses.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("uri", entry.getKey());
            item.put("usedBy", entry.getValue());
            result.add(item);
        }
        return result;
    }

    /** Sorted, stable cache key for a namespace map. */
    private static String namespaceKey(Map<String, String> namespaces) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : new TreeMap<>(namespaces).entrySet()) {
            sb.append(entry.getKey()).append('=').append(entry.getValue()).append(';');
        }
        return sb.toString();
    }

    /** Effective preview length of descriptors. */
    private static int previewChars(Options opts) {
        if ("none".equals(opts.text)) {
            return 0;
        }
        if ("full".equals(opts.text)) {
            return JsXmlLimits.MAX_TEXT_CHARS;
        }
        return opts.maxText;
    }

    /**
     * Character limit of {@code xml.text()} and {@code xml.cdata()}: an explicit
     * {@code {max}} wins ({@code 0} means up to the ceiling), otherwise the default of
     * 4000 characters. Without an explicit {@code max}, {@code {strict: true}} raises the
     * limit to the ceiling - strict means "all or nothing", so it must not shrink the
     * answer on its own.
     */
    private static int textLimit(Options opts) {
        if (opts.maxSet) {
            return opts.max == 0 ? JsXmlLimits.MAX_TEXT_CHARS
                    : (int) Math.min(opts.max, JsXmlLimits.MAX_TEXT_CHARS);
        }
        return opts.strict ? JsXmlLimits.MAX_TEXT_CHARS : JsXmlLimits.DEFAULT_TEXT_CHARS;
    }

    static String truncate(String value, int max) {
        if (max <= 0 || value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "…";
    }

    private static void resultLines(Map<String, Object> result, List<String> lines, int maxLines) {
        result.put("total", lines.size());
        result.put("returned", lines.size());
        result.put("truncated", lines.size() >= maxLines);
        result.put("machineLine", "XMLTREE lines=" + lines.size());
    }

    private static String displayHint(Object source) {
        if (source instanceof String text) {
            return text;
        }
        if (source instanceof JsXmlSource.Resolved resolved) {
            return resolved.display();
        }
        if (source instanceof byte[] bytes) {
            return JsXmlSource.BYTES_SOURCE + ":" + bytes.length;
        }
        return "<source>";
    }

    // ========================================================================
    // Value helpers (also used by JsXmlScan)
    // ========================================================================

    static String requireString(String what, Object value) {
        if (value == null) {
            throw new IllegalArgumentException(what + " must be a string");
        }
        if (value instanceof CharSequence text) {
            return text.toString();
        }
        throw new IllegalArgumentException(what + " must be a string, but was "
                + value.getClass().getSimpleName());
    }

    static long requireNumber(String what, Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalArgumentException(what + " must be a number, but was "
                + (value == null ? "null" : value.getClass().getSimpleName()));
    }

    static Map<String, String> toStringMap(String what, Object value) {
        Map<String, String> map = new LinkedHashMap<>();
        if (value == null) {
            return map;
        }
        if (!(value instanceof Map<?, ?> source)) {
            throw new IllegalArgumentException(what + " must be an object of string values");
        }
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            map.put(String.valueOf(entry.getKey()),
                    entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
        }
        return map;
    }

    static List<String> toStringList(String what, Object value) {
        List<String> list = new ArrayList<>();
        if (value == null) {
            return list;
        }
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                list.add(String.valueOf(item));
            }
            return list;
        }
        if (value instanceof CharSequence text) {
            list.add(text.toString());
            return list;
        }
        throw new IllegalArgumentException(what + " must be an array of strings (or a single string)");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> toObjectMap(String what, Map<?, ?> map) {
        for (Object key : map.keySet()) {
            if (!(key instanceof String)) {
                throw new IllegalArgumentException(what + " must use string keys");
            }
        }
        return (Map<String, Object>) map;
    }

    // ========================================================================
    // Options
    // ========================================================================

    /** Normalized options of one call. */
    static final class Options {
        int max = JsXmlLimits.DEFAULT_CAP_FIND;
        int maxText = JsXmlLimits.DEFAULT_PREVIEW_CHARS;
        boolean attrs = true;
        String text = "preview";
        boolean values = false;
        int maxElements = JsXmlLimits.DEFAULT_MAX_ELEMENTS;
        int maxAttrs = JsXmlLimits.DEFAULT_MAX_ATTRS;
        int depth = JsXmlLimits.DEFAULT_TREE_DEPTH;
        int maxLines = JsXmlLimits.DEFAULT_TREE_LINES;
        String scope = "any";
        String name;
        String attr;
        String mode = "auto";
        String dtd = "local";
        Map<String, String> entities = new LinkedHashMap<>();
        Map<String, String> ns = new LinkedHashMap<>();
        boolean lines = false;
        boolean strict = false;
        boolean trim = true;
        boolean collapse = false;
        String cdata = "keep";
        long maxBytes = JsXmlLimits.MAX_BYTES;
        int maxNodes = JsXmlLimits.MAX_NODES;
        String encoding;
        boolean comments = false;
        /** True when {@code max} was given explicitly (text functions then use it as the character limit). */
        boolean maxSet = false;

        static Options from(Map<String, Object> options) {
            Options opts = new Options();
            if (options == null || options.isEmpty()) {
                return opts;
            }
            for (Map.Entry<String, Object> entry : options.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (value == null) {
                    continue;
                }
                if (!ALLOWED_OPTIONS.contains(key)) {
                    throw new IllegalArgumentException("unknown option '" + key + "' (supported: "
                            + String.join(", ", new java.util.TreeSet<>(ALLOWED_OPTIONS)) + ")");
                }
                opts.set(key, value);
            }
            return opts;
        }

        private void set(String key, Object value) {
            switch (key) {
                case "max" -> {
                    max = cappedInt(key, value, JsXmlLimits.HARD_RESULT_CAP);
                    maxSet = true;
                }
                case "maxText" -> maxText = (int) cappedLong(key, value, JsXmlLimits.MAX_TEXT_CHARS);
                case "attrs" -> attrs = bool(key, value);
                case "text" -> text = enumerated(key, value, Set.of("none", "preview", "full"));
                case "values" -> values = bool(key, value);
                case "maxElements" -> maxElements = (int) requireNumber(key, value);
                case "maxAttrs" -> maxAttrs = (int) requireNumber(key, value);
                case "depth" -> depth = (int) requireNumber(key, value);
                case "maxLines" -> maxLines = (int) cappedLong(key, value, 5000);
                case "scope" -> scope = enumerated(key, value, Set.of("text", "attr", "any"));
                case "name" -> name = requireString(key, value);
                case "attr" -> attr = requireString(key, value);
                case "mode" -> mode = enumerated(key, value, MODES);
                case "dtd" -> dtd = enumerated(key, value, DTD_MODES);
                case "entities" -> entities = toStringMap("entities", value);
                case "ns" -> ns = toStringMap("ns", value);
                case "lines" -> lines = bool(key, value);
                case "strict" -> strict = bool(key, value);
                case "trim" -> trim = bool(key, value);
                case "collapse" -> collapse = bool(key, value);
                case "cdata" -> cdata = enumerated(key, value, Set.of("keep", "skip"));
                case "maxBytes" -> maxBytes = Math.min(cappedLong(key, value, JsXmlLimits.MAX_BYTES),
                        JsXmlLimits.MAX_BYTES);
                case "maxNodes" -> maxNodes = (int) Math.min(cappedLong(key, value, JsXmlLimits.MAX_NODES),
                        JsXmlLimits.MAX_NODES);
                case "encoding" -> encoding = requireString(key, value);
                case "comments" -> comments = bool(key, value);
                default -> throw new IllegalArgumentException("unknown option '" + key + "'");
            }
        }

        private boolean bool(String key, Object value) {
            if (value instanceof Boolean flag) {
                return flag;
            }
            if (value instanceof CharSequence text) {
                return Boolean.parseBoolean(text.toString());
            }
            throw new IllegalArgumentException("option '" + key + "' must be a boolean");
        }

        private String enumerated(String key, Object value, Set<String> allowed) {
            String text = requireString("option '" + key + "'", value);
            if (!allowed.contains(text)) {
                throw new IllegalArgumentException("option '" + key + "' must be one of "
                        + new java.util.TreeSet<>(allowed) + ", but was '" + text + "'");
            }
            return text;
        }

        private int cappedInt(String key, Object value, int ceiling) {
            return (int) cappedLong(key, value, ceiling);
        }

        private long cappedLong(String key, Object value, long ceiling) {
            long number = requireNumber("option '" + key + "'", value);
            if (number < 0) {
                throw new IllegalArgumentException("option '" + key + "' must not be negative");
            }
            return Math.min(number, ceiling);
        }
    }

    // ========================================================================
    // Help
    // ========================================================================

    /** True when the streaming implementation reports CDATA sections as own events. */
    private static boolean cdataDetection() {
        return JsXmlScan.cdataEventsSupported();
    }

    /**
     * Help text of the {@code xml} namespace.
     *
     * @return help text
     */
    public static String help() {
        return """
                JS XML API (namespace 'xml')
                ============================

                Controlled XML reading with two engines and one result shape.
                Discovery runs on a streaming pass (constant memory, any size, line numbers
                for free); queries run on a DOM behind size limits because XPath needs a tree.
                Every result says which engine produced it: engine: "stax" | "dom-xpath".

                Nothing is ever fetched: external DTDs and entities resolve ONLY as files
                inside the permitted directories; <?xml-stylesheet?> is reported, not followed.
                Attribute and text values are returned as strings - the module never coerces
                a value into a number, so there is no silent NaN inside it.

                Documents (DOM, capped, live for this JavaScript call only)
                  xml.open(source[, opts])    -> {id, root, encoding, prolog, stats, limits, findings}
                  xml.openText(text[, opts])  - same for an XML string
                  xml.docs()                  - open documents + memory use
                  xml.close(id|[ids])         - release a DOM (automatic at the end of the call)

                Discovery (streaming, no DOM, any size)
                  xml.profile(source[, opts])     element census: names, counts, attribute names
                  xml.attrNames(source, "box")    the quick "what does this element carry" answer
                  xml.tree(source[, opts])        skeleton text: names + counts + @attrNames
                  xml.sample(source, "box"[, opts]) 1-2 real nodes in full
                  xml.count(source, selector)     count matches
                  xml.ns(source)                  namespace report

                Query and iteration
                  xml.find(source, xpath[, opts])  XPath 1.0 -> {total, returned, truncated, nodes, hint?}
                  xml.each(source[, xpath|selector], fn[, opts])
                                                   streams matches; fn returns false to stop
                  xml.scan(source, selector[, opts]) streaming selector, capped list
                  xml.grep(source, /regex/[, opts]) content search WITH the element path
                  xml.sample / xml.count as above

                Node access (DOM)
                  xml.attrs(source, node)          plain object of strings
                  xml.text(source, node[, opts])   String; {strict: true} = all or nothing
                  xml.cdata(source, node[, opts])  CDATA content only
                  xml.children(source, node) / xml.ancestors(source, node) / xml.get(source, node)

                A "node" is a descriptor from xml.find()/xml.each(), a node id or a path
                ("/root/node[2]/code[1]" or the readable "root.node[2].code").

                Selectors (streaming): {name, path, ancestor, attrs: {k: v}, hasAttrs: [...],
                text: /regex/, depth} - all optional.

                Options (unknown keys are refused):
                  max, maxText, text: "none"|"preview"|"full", attrs, values, maxElements,
                  maxAttrs, depth, maxLines, scope: "text"|"attr"|"any", name, attr,
                  dtd: "local"|"ignore"|"entities", entities: {name: "value"}, ns: {p: "uri"},
                  lines, strict, trim, collapse, cdata: "keep"|"skip", maxBytes, maxNodes, encoding

                Zero hits are explained, never silent: a result with total: 0 carries
                hint.reason = element-unknown | attribute-unknown | step-unknown |
                index-out-of-range | default-namespace | unsupported-xpath, plus the names
                that actually exist at the point where the query failed.

                Sources: project-relative path, archive path "file.zip#dir/entry.xml"
                (nested: "a.zip#b.tar#c.xml"), bytes (Uint8Array) or an open document id.

                XPath is 1.0 (JDK): no let/some/every, no matches()/replace(), no "=>" -
                filter in JS (xml.find(...).nodes.filter(...)) or use xml.each()/xml.grep().

                For corpus-wide byte scanning you may use search.find - a DOM could be slower.

                --- Help ---
                xml.help()       - This help text.
                """;
    }
}
