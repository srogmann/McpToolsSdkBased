package org.rogmann.mcp2sdk.xml;

import org.rogmann.mcp2sdk.js.JsFileSystem;
import org.rogmann.mcp2sdk.js.JsUserRuntimeException;
import org.rogmann.mcp2sdk.xml.JsXmlErrors.Parse;
import org.rogmann.mcp2sdk.xml.JsXmlErrors.TooLarge;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.DocumentType;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DOM engine behind the {@code xml} module: parsing with a local-only entity gate, node
 * descriptors, canonical/readable paths, XPath 1.0 queries with a compiled-expression
 * cache, namespace reporting and the zero-hit hints.
 *
 * <p>
 * A parsed document is a {@link Loaded}: the DOM plus the per-document state the API needs
 * (node ids, line numbers when requested, prolog report, statistics, compiled XPath
 * expressions). Instances live in the per-call cache of {@link JsXml} and are released
 * when the JavaScript call ends.
 * </p>
 *
 * <h3>No network, ever</h3>
 * <p>
 * {@code ACCESS_EXTERNAL_DTD}/{@code ACCESS_EXTERNAL_SCHEMA} are set to the empty list and
 * the registered {@code EntityResolver} is the only way an external resource can be read -
 * as a regular file inside a permitted directory. Anything else (URL, absolute path,
 * missing file) is reported as a finding and treated as empty, so a document with a
 * {@code DOCTYPE} stays analysable.
 * </p>
 */
final class JsXmlDom {

    /** XPath 1.0 function names that do not exist in the JDK's implementation. */
    private static final Pattern XPATH_UNSUPPORTED = Pattern.compile(
            "\\b(matches|replace|tokenize|index-of|upper-case|lower-case|deep-equal|format-number"
                    + "|for-each|let|some|every|serialize|json-to-xml|parse-json)\\s*\\(");

    /** Attribute references inside an XPath predicate. */
    private static final Pattern XPATH_ATTR = Pattern.compile("@([A-Za-z_][\\w:.-]*)");

    /** A location step with a positional predicate, e.g. {@code box[7]}. */
    private static final Pattern XPATH_INDEX = Pattern.compile("^([A-Za-z_][\\w:.-]*)\\[(\\d+)]$");

    /** A bare positional predicate after a matched step, e.g. {@code [7]}. */
    private static final Pattern BARE_INDEX = Pattern.compile("^\\[(\\d+)]$");

    /** Maximum number of nodes inspected while deriving a hint. */
    private static final int HINT_NODE_LIMIT = 20;

    private JsXmlDom() {
        // Utility class
    }

    // ========================================================================
    // Loaded document
    // ========================================================================

    /** A parsed DOM with its per-document state. */
    static final class Loaded {
        final String id;
        final String display;
        final JsXmlSource.Resolved source;
        final Document document;
        final long bytes;
        final String encoding;
        final String encodingSource;
        final Map<String, Object> prolog;
        final List<Map<String, Object>> findings;
        final Map<Integer, Node> nodeById = new HashMap<>();
        final Map<Node, Integer> idByNode = new IdentityHashMap<>();
        final Map<Integer, Integer> lineByNode = new HashMap<>();
        final Map<String, XPathExpression> xpathCache = new HashMap<>();
        boolean linesAttached;
        int elementCount;
        int commentCount;
        int piCount;
        int cdataSections;
        int maxDepth;
        private int nextNodeId = 1;

        Loaded(String id, String display, JsXmlSource.Resolved source, Document document, long bytes,
               String encoding, String encodingSource, Map<String, Object> prolog,
               List<Map<String, Object>> findings) {
            this.id = id;
            this.display = display;
            this.source = source;
            this.document = document;
            this.bytes = bytes;
            this.encoding = encoding;
            this.encodingSource = encodingSource;
            this.prolog = prolog;
            this.findings = findings;
        }

        /** Stable node id of this document (assigned on first use). */
        int nodeId(Node node) {
            Integer existing = idByNode.get(node);
            if (existing != null) {
                return existing;
            }
            int assigned = nextNodeId++;
            idByNode.put(node, assigned);
            nodeById.put(assigned, node);
            return assigned;
        }

        /** Node of an id, or {@code null}. */
        Node nodeOf(int nodeId) {
            return nodeById.get(nodeId);
        }

        /** Node count reported to the caller (elements + comments + processing instructions). */
        int nodeCount() {
            return elementCount + commentCount + piCount;
        }

        int errorFindings() {
            int errors = 0;
            for (Map<String, Object> finding : findings) {
                if ("error".equals(finding.get("severity"))) {
                    errors++;
                }
            }
            return errors;
        }
    }

    /** Raised when an XPath expression cannot be compiled (XPath 1.0 limitation). */
    static final class XPathProblem extends RuntimeException {
        private final String detail;

        XPathProblem(String detail) {
            super(detail);
            this.detail = detail;
        }

        String detail() {
            return detail;
        }
    }

    // ========================================================================
    // Parsing
    // ========================================================================

    /**
     * Parses a source into a DOM.
     *
     * @param source           resolved source
     * @param id               document handle id
     * @param dtdMode          {@code local}, {@code entities} or {@code ignore}
     * @param entities         explicit entity substitutions (may be empty)
     * @param encodingOverride explicit encoding (may be {@code null})
     * @param maxBytes         DOM size limit for this document
     * @param maxNodes         node limit for this document
     */
    static Loaded parse(JsXmlSource.Resolved source, String id, String dtdMode,
                        Map<String, String> entities, String encodingOverride,
                        long maxBytes, int maxNodes) {
        long sizeHint = source.sizeHint();
        if (sizeHint > maxBytes) {
            throw new TooLarge("Document '" + source.display() + "' is " + sizeHint
                    + " bytes, xml.open parses at most " + maxBytes + " bytes"
                    + " - stream it with xml.each()/xml.scan() instead, or pass a higher"
                    + " {maxBytes} (ceiling: " + JsXmlLimits.PROP_PREFIX + "maxBytes)");
        }
        JsXmlSource.Resolved effective = source;
        if (entities != null && !entities.isEmpty()) {
            try (InputStream in = source.open()) {
                byte[] raw = JsXmlSource.readAll(in, JsXmlLimits.MAX_STREAM_BYTES, source.display());
                effective = JsXmlSource.ofBytes(JsXmlSource.injectEntities(raw, entities),
                        source.display());
            } catch (IOException e) {
                throw new JsUserRuntimeException("Cannot read " + source.display() + ": " + e.getMessage(), e);
            }
        }

        List<Map<String, Object>> findings = new ArrayList<>();
        String[] resolvedAs = new String[1];
        String[] bomOut = new String[1];
        boolean[] dtdLoaded = new boolean[1];

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setValidating(false);
        factory.setXIncludeAware(false);
        trySetFeature(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
        if (!"local".equals(dtdMode)) {
            trySetFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        }
        trySetAttribute(factory, XMLConstants.ACCESS_EXTERNAL_DTD, "");
        trySetAttribute(factory, XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

        Document document;
        try {
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> {
                String reference = decodeSystemId(systemId);
                if (reference == null || reference.isEmpty()) {
                    findings.add(JsXml.finding("info", null,
                            "External DTD/entity reference not loaded (dtd:'" + dtdMode + "')"
                                    + (systemId == null ? "" : ": " + systemId)));
                    return emptySource();
                }
                Path local = localPermittedFile(source, reference);
                if (local == null) {
                    findings.add(JsXml.finding("warning", null, describeRejection(reference)));
                    return emptySource();
                }
                dtdLoaded[0] = true;
                resolvedAs[0] = JsFileSystem.toRelative(local);
                return new InputSource(Files.newInputStream(local));
            });
            builder.setErrorHandler(new ErrorHandler() {
                @Override
                public void warning(SAXParseException e) {
                    findings.add(JsXml.finding("warning", e.getLineNumber(),
                            sanitize(e.getMessage(), source)));
                }

                @Override
                public void error(SAXParseException e) throws SAXParseException {
                    throw e;
                }

                @Override
                public void fatalError(SAXParseException e) throws SAXParseException {
                    throw e;
                }
            });
            try (InputStream raw = effective.open()) {
                BufferedInputStream buffered = new BufferedInputStream(raw, 64 * 1024);
                byte[] prefix = JsXmlSource.peek(buffered, 4);
                bomOut[0] = JsXmlSource.bomEncoding(prefix);
                InputSource input = new InputSource(buffered);
                input.setSystemId(baseUriOf(source));
                if (encodingOverride != null) {
                    input.setEncoding(encodingOverride);
                }
                document = builder.parse(input);
            }
        } catch (SAXParseException e) {
            throw new Parse(source.display(), e.getLineNumber(), e.getColumnNumber(),
                    entityHint(sanitize(e.getMessage(), source)));
        } catch (SAXException e) {
            throw new Parse(source.display(), 0, 0, sanitize(e.getMessage(), source));
        } catch (IOException e) {
            throw new JsUserRuntimeException("Cannot read " + source.display() + ": "
                    + sanitize(e.getMessage(), source), e);
        } catch (ParserConfigurationException e) {
            throw new JsUserRuntimeException("XML parser cannot be configured: " + e.getMessage(), e);
        }

        Element root = document.getDocumentElement();
        if (root == null) {
            throw new Parse(source.display(), 0, 0, "document has no root element");
        }
        Loaded loaded = new Loaded(id, source.display(), effective, document, Math.max(sizeHint, 0),
                encodingOf(document, encodingOverride, bomOut[0]),
                encodingSourceOf(document, encodingOverride, bomOut[0]),
                prologOf(document, dtdMode, dtdLoaded[0], resolvedAs[0], findings), findings);
        walkStats(root, 1, loaded);
        if (loaded.elementCount > maxNodes) {
            throw new TooLarge("Document '" + source.display() + "' has " + loaded.elementCount
                    + " elements, the DOM limit is " + maxNodes + " - stream it with"
                    + " xml.each()/xml.scan() instead, or raise {maxNodes} (ceiling: "
                    + JsXmlLimits.PROP_PREFIX + "maxNodes)");
        }
        return loaded;
    }

    private static InputSource emptySource() {
        return new InputSource(new StringReader(""));
    }

    private static void trySetFeature(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (ParserConfigurationException | IllegalArgumentException e) {
            // parser without this feature: the entity resolver is the effective gate anyway
        }
    }

    private static void trySetAttribute(DocumentBuilderFactory factory, String name, Object value) {
        try {
            factory.setAttribute(name, value);
        } catch (IllegalArgumentException e) {
            // parser without JAXP restriction support (UnsupportedOperationException included)
        }
    }

    /** Percent-decodes a system identifier (references with spaces arrive as {@code %20}). */
    static String decodeSystemId(String systemId) {
        if (systemId == null || systemId.indexOf('%') < 0) {
            return systemId;
        }
        StringBuilder sb = new StringBuilder(systemId.length());
        for (int i = 0; i < systemId.length(); i++) {
            char c = systemId.charAt(i);
            if (c == '%' && i + 2 < systemId.length()) {
                try {
                    int code = Integer.parseInt(systemId.substring(i + 1, i + 3), 16);
                    sb.append((char) code);
                    i += 2;
                    continue;
                } catch (NumberFormatException ignored) {
                    // not an escape: keep as-is
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    static boolean hasUrlScheme(String reference) {
        return Pattern.matches("^[A-Za-z][A-Za-z0-9+.\\-]*:.*", reference);
    }

    static boolean isAbsoluteLocation(String reference) {
        return reference.startsWith("/") || reference.startsWith("\\")
                || (reference.length() > 1 && reference.charAt(1) == ':');
    }

    /**
     * Base URI of a source, as the parser sees it: the absolute {@code file:} URI for file
     * sources, so that relative DTD/entity references expand against the document's own
     * directory instead of the process working directory; the display path otherwise.
     * Absolute paths in parser messages are sanitized away afterwards (§13).
     */
    private static String baseUriOf(JsXmlSource.Resolved source) {
        Path absolute = source.absoluteOrNull();
        return (absolute != null) ? absolute.toUri().toString() : source.display();
    }

    /**
     * Resolves an external DTD/entity reference to a local file inside a permitted
     * directory. The parser hands the resolver the reference usually already expanded
     * against the document's base URI (an absolute {@code file:} URL), so both forms are
     * handled: the raw relative reference and the expanded absolute one - in both cases the
     * target must be a regular file inside the permitted directories, otherwise the
     * reference is reported and treated as empty.
     *
     * @return the local file, or {@code null} when the reference is not resolvable locally
     */
    private static Path localPermittedFile(JsXmlSource.Resolved source, String reference) {
        if (hasUrlScheme(reference)) {
            if (!reference.startsWith("file:")) {
                return null; // http(s), jar, ftp, …: never fetched, whatever they point at
            }
            return permittedFile(Paths.get(fileUriToPath(reference)));
        }
        if (isAbsoluteLocation(reference)) {
            return permittedFile(Paths.get(reference));
        }
        return source.resolveSibling(reference);
    }

    /** Strips the {@code file:} prefix and the (empty) authority of a file URL. */
    private static String fileUriToPath(String reference) {
        String path = reference.substring("file:".length());
        if (path.startsWith("//")) {
            String rest = path.substring(2);
            int slash = rest.indexOf('/');
            path = (slash < 0) ? "/" : rest.substring(slash);
        }
        return decodeSystemId(path);
    }

    /**
     * Verifies that an absolute candidate path is a regular file inside a permitted
     * directory: the round trip through {@code toRelative} + {@code resolveSafePath}
     * re-applies the full {@code JsFileSystem} rule set (containment, {@code ..},
     * symlinks) to a path the parser derived on its own.
     */
    private static Path permittedFile(Path absolute) {
        if (absolute == null || !Files.isRegularFile(absolute)) {
            return null;
        }
        try {
            Path real = absolute.toRealPath();
            String relative = JsFileSystem.toRelative(real);
            Path checked = JsFileSystem.resolveSafePath(relative);
            return Files.isRegularFile(checked) ? checked : null;
        } catch (IOException | RuntimeException e) {
            return null; // outside the permitted directories or unreadable: report, don't load
        }
    }

    /** The finding text for a reference that is not resolvable as a local file. */
    private static String describeRejection(String reference) {
        if (hasUrlScheme(reference) && !reference.startsWith("file:")) {
            return "DOCTYPE/entity reference '" + reference + "' was not loaded: the xml module"
                    + " never accesses the network (only files inside the permitted directories)";
        }
        return "DOCTYPE references '" + lastSegment(reference) + "', which was not found inside"
                + " the permitted directories: not loaded, no network access"
                + " (entities declared there stay unresolved;"
                + " pass {entities: {name: \"value\"}} to substitute them)";
    }

    /** Last segment of a reference, for readable findings ("…/dtds/Note.dtd" → "Note.dtd"). */
    private static String lastSegment(String reference) {
        String cleaned = reference.replace('\\', '/');
        int slash = cleaned.lastIndexOf('/');
        return (slash < 0) ? cleaned : cleaned.substring(slash + 1);
    }

    /** Turns the parser's "entity was referenced but not declared" into an actionable message. */
    private static String entityHint(String message) {
        if (message == null) {
            return "";
        }
        if (message.contains("entity") && (message.contains("not declared") || message.contains("undeclared"))) {
            return message + " - pass {entities: {name: \"value\"}} to declare it explicitly,"
                    + " or fix the document (the DTD is never fetched from the network)";
        }
        return message;
    }

    /** Replaces the absolute path of the source (and the base directory) inside parser texts. */
    static String sanitize(String message, JsXmlSource.Resolved source) {
        String text = (message == null) ? "" : message;
        Path absolute = source.absoluteOrNull();
        if (absolute != null) {
            String asFile = absolute.toString().replace('\\', '/');
            text = text.replace(absolute.toString(), source.display()).replace(asFile, source.display());
            String uriForm = absolute.toUri().toString();
            text = text.replace(uriForm, source.display());
        }
        return text.replace("file:", "");
    }

    /**
     * Reported encoding: explicit option, then the declaration in the document, then a
     * byte-order mark, else UTF-8 (the XML default when nothing says otherwise).
     */
    private static String encodingOf(Document document, String override, String bom) {
        if (override != null && !override.isBlank()) {
            return override;
        }
        String declared = document.getXmlEncoding();
        if (declared != null && !declared.isBlank()) {
            return declared;
        }
        if (bom != null) {
            return bom;
        }
        return "UTF-8";
    }

    /** Where the reported encoding came from: {@code option}, {@code decl}, {@code bom}, {@code default}. */
    private static String encodingSourceOf(Document document, String override, String bom) {
        if (override != null && !override.isBlank()) {
            return "option";
        }
        String declared = document.getXmlEncoding();
        if (declared != null && !declared.isBlank()) {
            return "decl";
        }
        return (bom != null) ? "bom" : "default";
    }

    /** Statistics walk (elements, comments, PIs, CDATA sections, depth). */
    private static void walkStats(Node node, int depth, Loaded loaded) {
        short type = node.getNodeType();
        if (type == Node.ELEMENT_NODE) {
            loaded.elementCount++;
            if (depth > loaded.maxDepth) {
                loaded.maxDepth = depth;
            }
        } else if (type == Node.COMMENT_NODE) {
            loaded.commentCount++;
            return;
        } else if (type == Node.PROCESSING_INSTRUCTION_NODE) {
            loaded.piCount++;
            return;
        } else if (type == Node.CDATA_SECTION_NODE) {
            loaded.cdataSections++;
            return;
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            walkStats(child, depth, loaded);
        }
    }

    /** Builds the prolog report (DOCTYPE status, processing instructions). */
    private static Map<String, Object> prologOf(Document document, String dtdMode, boolean loaded,
                                               String resolvedAs, List<Map<String, Object>> findings) {
        Map<String, Object> prolog = new LinkedHashMap<>();
        DocumentType doctype = document.getDoctype();
        if (doctype != null) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", doctype.getName());
            info.put("publicId", doctype.getPublicId());
            info.put("systemId", decodeSystemId(doctype.getSystemId()));
            info.put("internalSubset", doctype.getInternalSubset() != null);
            info.put("loaded", loaded);
            info.put("resolvedAs", resolvedAs);
            prolog.put("doctype", info);
        } else {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", null);
            info.put("loaded", false);
            info.put("resolvedAs", null);
            prolog.put("doctype", info);
        }
        List<String> pis = new ArrayList<>();
        for (Node child = document.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.PROCESSING_INSTRUCTION_NODE) {
                pis.add(child.getNodeName());
            }
        }
        prolog.put("processingInstructions", pis);
        prolog.put("dtdMode", dtdMode);
        if (!pis.isEmpty()) {
            findings.add(JsXml.finding("info", null,
                    "Processing instruction(s) " + pis + " are reported but never followed"
                            + " (no stylesheet is loaded)"));
        }
        return prolog;
    }

    /**
     * Parses the raw text of a {@code DOCTYPE} declaration (used by the streaming engine,
     * which sees the declaration as text).
     */
    static Map<String, Object> parseDoctype(String raw) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", null);
        info.put("publicId", null);
        info.put("systemId", null);
        info.put("internalSubset", false);
        info.put("loaded", false);
        info.put("resolvedAs", null);
        if (raw == null) {
            return info;
        }
        String text = raw.trim();
        int start = text.toUpperCase(java.util.Locale.ROOT).indexOf("<!DOCTYPE");
        if (start < 0) {
            return info;
        }
        String body = text.substring(start + "<!DOCTYPE".length());
        List<String> tokens = tokenizeDoctype(body);
        if (!tokens.isEmpty()) {
            info.put("name", tokens.get(0));
        }
        if (tokens.size() > 1) {
            if ("PUBLIC".equalsIgnoreCase(tokens.get(1)) && tokens.size() > 3) {
                info.put("publicId", tokens.get(2));
                info.put("systemId", decodeSystemId(tokens.get(3)));
            } else if ("SYSTEM".equalsIgnoreCase(tokens.get(1))) {
                info.put("systemId", decodeSystemId(tokens.get(2)));
            }
        }
        info.put("internalSubset", body.indexOf('[') >= 0
                && (body.indexOf('[') < body.indexOf('>')));
        return info;
    }

    /** Splits a DOCTYPE body into name/keyword/quoted-string tokens. */
    private static List<String> tokenizeDoctype(String body) {
        List<String> tokens = new ArrayList<>();
        int i = 0;
        while (i < body.length() && tokens.size() < 6) {
            char c = body.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '[') {
                break;
            }
            if (c == '"' || c == '\'') {
                int end = body.indexOf(c, i + 1);
                if (end < 0) {
                    break;
                }
                tokens.add(body.substring(i + 1, end));
                i = end + 1;
                continue;
            }
            if (c == '>') {
                break;
            }
            int end = i;
            while (end < body.length() && !Character.isWhitespace(body.charAt(end))
                    && body.charAt(end) != '>' && body.charAt(end) != '[') {
                end++;
            }
            tokens.add(body.substring(i, end));
            i = end;
        }
        return tokens;
    }

    // ========================================================================
    // Line numbers
    // ========================================================================

    /**
     * Attaches line numbers to the elements of a parsed document. The JDK DOM keeps no
     * locator, so a streaming pass over the same source is paired with the DOM walk: both
     * visit elements in document order, so the n-th START_ELEMENT belongs to the n-th
     * element node.
     */
    static void attachLines(Loaded loaded) {
        if (loaded.linesAttached) {
            return;
        }
        loaded.linesAttached = true;
        List<Element> elements = new ArrayList<>(Math.max(16, loaded.elementCount));
        collectElements(loaded.document.getDocumentElement(), elements);
        int[] index = new int[1];
        JsXmlScan.drive(loaded.source, new JsXmlScan.ScanHandler() {
            @Override
            public void startElement(JsXmlScan.Frame frame) {
                if (index[0] < elements.size()) {
                    loaded.lineByNode.put(loaded.nodeId(elements.get(index[0])), frame.startLine);
                }
                index[0]++;
            }

            @Override
            public void endElement(JsXmlScan.Frame frame) {
                // pairing happens on start elements only
            }

            @Override
            public boolean done() {
                return index[0] >= elements.size();
            }
        }, 0);
    }

    /** Collects elements in document order (pre-order). */
    static void collectElements(Node node, List<Element> out) {
        if (node == null) {
            return;
        }
        if (node.getNodeType() == Node.ELEMENT_NODE) {
            out.add((Element) node);
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            collectElements(child, out);
        }
    }

    // ========================================================================
    // Descriptors and paths
    // ========================================================================

    /** Builds the descriptor of an element (DOM form, with node id). */
    static Map<String, Object> describe(Loaded loaded, Element element, int previewChars, boolean withLine) {
        int nodeId = loaded.nodeId(element);
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("id", nodeId);
        descriptor.put("name", element.getNodeName());
        descriptor.put("path", pathOf(element));
        descriptor.put("pathDotted", dottedOf(element));
        Integer line = loaded.lineByNode.get(nodeId);
        if (withLine) {
            descriptor.put("line", line);
        }
        descriptor.put("attrs", attrsOf(element));
        descriptor.put("childCount", childElements(element).size());
        TextCollect collect = collectText(element, Math.max(previewChars, 0));
        descriptor.put("textChars", collect.chars);
        descriptor.put("text", collect.preview.toString());
        descriptor.put("textTruncated", collect.chars > collect.preview.length());
        if (collect.hasCdata) {
            descriptor.put("hasCdata", true);
        }
        return descriptor;
    }

    /** Attributes as a plain map of strings (values are never coerced). */
    static LinkedHashMap<String, String> attrsOf(Element element) {
        LinkedHashMap<String, String> attrs = new LinkedHashMap<>();
        NamedNodeMap map = element.getAttributes();
        if (map != null) {
            for (int i = 0; i < map.getLength(); i++) {
                Node attribute = map.item(i);
                String name = attribute.getNodeName();
                if ("xmlns".equals(name) || (name != null && name.startsWith("xmlns:"))) {
                    continue; // namespace declarations are reported by xml.ns(), not as data
                }
                attrs.put(name, attribute.getNodeValue());
            }
        }
        return attrs;
    }

    /** Element children (comments, PIs and text nodes excluded). */
    static List<Element> childElements(Element element) {
        List<Element> children = new ArrayList<>();
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                children.add((Element) child);
            }
        }
        return children;
    }

    /** Ancestor chain root-first (excluding the element itself). */
    static List<Element> ancestorsOf(Element element) {
        List<Element> chain = new ArrayList<>();
        Node parent = element.getParentNode();
        while (parent != null && parent.getNodeType() == Node.ELEMENT_NODE) {
            chain.add(0, (Element) parent);
            parent = parent.getParentNode();
        }
        return chain;
    }

    /**
     * Canonical XPath of an element, fully indexed below the document element:
     * {@code /root/node[2]/code[1]}. The document element itself carries no index - it is
     * unique per definition, and {@code /root[1]} is noise in every message.
     */
    static String pathOf(Element element) {
        List<Element> chain = chainIncluding(element);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chain.size(); i++) {
            Element current = chain.get(i);
            sb.append('/').append(current.getNodeName());
            if (i > 0) {
                sb.append('[').append(indexOf(current)).append(']');
            }
        }
        return sb.toString();
    }

    /** Readable path: {@code root.node[2].code} (the index of first occurrences is omitted). */
    static String dottedOf(Element element) {
        StringBuilder sb = new StringBuilder();
        for (Element current : chainIncluding(element)) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            int index = indexOf(current);
            sb.append(current.getNodeName()).append(index > 1 ? "[" + index + "]" : "");
        }
        return sb.toString();
    }

    private static List<Element> chainIncluding(Element element) {
        List<Element> chain = ancestorsOf(element);
        chain.add(element);
        return chain;
    }

    /** 1-based index of an element among its same-name siblings. */
    static int indexOf(Element element) {
        Node parent = element.getParentNode();
        if (parent == null) {
            return 1;
        }
        int index = 0;
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE
                    && element.getNodeName().equals(child.getNodeName())) {
                index++;
                if (child == element) {
                    return index;
                }
            }
        }
        return 1;
    }

    /**
     * Resolves a canonical or dotted path to an element.
     *
     * @param spec {@code /root/node[2]/code[1]}, {@code root.node[2].code} or a name list
     *             without the document element ({@code node[2].code})
     * @return the element, or {@code null} when the path does not exist
     */
    static Element resolve(Loaded loaded, String spec) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("path must not be empty");
        }
        String trimmed = spec.trim();
        boolean slashForm = trimmed.startsWith("/");
        String[] tokens = (slashForm ? trimmed.substring(1) : trimmed).split(slashForm ? "/" : "\\.");
        Element root = loaded.document.getDocumentElement();
        Element current = null;
        int start = 0;
        PathToken first = PathToken.parse(tokens[0]);
        if (first != null && nameMatches(root, first.name)) {
            current = root;
            start = 1;
        } else {
            current = root; // allow paths that omit the document element
        }
        for (int i = start; i < tokens.length; i++) {
            PathToken token = PathToken.parse(tokens[i]);
            if (token == null) {
                return null;
            }
            Element next = nthChild(current, token.name, token.index);
            if (next == null) {
                return null;
            }
            current = next;
        }
        return current;
    }

    private static boolean nameMatches(Element element, String name) {
        if ("*".equals(name)) {
            return true;
        }
        if (name.equals(element.getNodeName())) {
            return true;
        }
        String local = element.getLocalName();
        return local != null && name.equals(local);
    }

    /** The index-th element child with the given name (index 1 = first). */
    static Element nthChild(Element parent, String name, int index) {
        int seen = 0;
        for (Element child : childElements(parent)) {
            if (nameMatches(child, name)) {
                seen++;
                if (seen == index) {
                    return child;
                }
            }
        }
        return null;
    }

    /** A single path token: name plus optional positional predicate. */
    private record PathToken(String name, int index) {
        static PathToken parse(String token) {
            String text = token.trim();
            if (text.isEmpty()) {
                return null;
            }
            int open = text.indexOf('[');
            if (open < 0) {
                return new PathToken(text, 1);
            }
            String name = text.substring(0, open);
            String rest = text.substring(open + 1, text.endsWith("]") ? text.length() - 1 : text.length());
            try {
                return new PathToken(name.isEmpty() ? "*" : name, Integer.parseInt(rest.trim()));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    // ========================================================================
    // Text extraction
    // ========================================================================

    /** Collected text of a subtree. */
    static final class TextCollect {
        final StringBuilder preview = new StringBuilder();
        int chars;
        boolean hasCdata;
    }

    /** Concatenates text and CDATA of a subtree, buffering at most {@code cap} characters. */
    static TextCollect collectText(Node node, int cap) {
        return collectText(node, cap, false);
    }

    /**
     * Concatenates text (and CDATA unless {@code skipCdata}) of a subtree, buffering at
     * most {@code cap} characters while counting all of them.
     */
    static TextCollect collectText(Node node, int cap, boolean skipCdata) {
        TextCollect collect = new TextCollect();
        collectInto(node, cap, skipCdata, collect);
        return collect;
    }

    private static void collectInto(Node node, int cap, boolean skipCdata, TextCollect out) {
        short type = node.getNodeType();
        if (type == Node.CDATA_SECTION_NODE) {
            out.hasCdata = true;
            if (skipCdata) {
                return;
            }
        }
        if (type == Node.TEXT_NODE || type == Node.CDATA_SECTION_NODE) {
            String value = node.getNodeValue();
            if (value != null) {
                out.chars += value.length();
                if (out.preview.length() < cap) {
                    int room = cap - out.preview.length();
                    out.preview.append(value, 0, Math.min(value.length(), room));
                }
            }
            return;
        }
        if (type == Node.COMMENT_NODE || type == Node.PROCESSING_INSTRUCTION_NODE) {
            return;
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            collectInto(child, cap, skipCdata, out);
        }
    }

    /** True when the subtree contains at least one CDATA section. */
    static boolean hasCdata(Node node) {
        if (node.getNodeType() == Node.CDATA_SECTION_NODE) {
            return true;
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (hasCdata(child)) {
                return true;
            }
        }
        return false;
    }

    /** Applies the {@code trim}/{@code collapse} options. */
    static String applyTextOptions(String value, boolean trim, boolean collapse) {
        String text = value;
        if (collapse) {
            text = text.replaceAll("\\s+", " ");
        }
        if (trim) {
            text = text.trim();
        }
        return text;
    }

    // ========================================================================
    // Namespaces
    // ========================================================================

    /** Namespace report of a document. */
    static List<Map<String, Object>> namespaces(Loaded loaded) {
        Map<String, NamespaceInfo> byUri = new TreeMap<>();
        List<Element> elements = new ArrayList<>();
        collectElements(loaded.document.getDocumentElement(), elements);
        for (Element element : elements) {
            recordNamespace(byUri, element.getNamespaceURI(), element.getPrefix());
            NamedNodeMap attributes = element.getAttributes();
            if (attributes != null) {
                for (int i = 0; i < attributes.getLength(); i++) {
                    Attr attribute = (Attr) attributes.item(i);
                    String name = attribute.getName();
                    if ("xmlns".equals(name) || name.startsWith("xmlns:")) {
                        continue;
                    }
                    recordNamespace(byUri, attribute.getNamespaceURI(), attribute.getPrefix());
                }
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (NamespaceInfo info : byUri.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("uri", info.uri);
            entry.put("prefixes", new ArrayList<>(info.prefixes));
            entry.put("usedBy", info.uses);
            result.add(entry);
        }
        return result;
    }

    private static final class NamespaceInfo {
        String uri;
        final Set<String> prefixes = new LinkedHashSet<>();
        int uses;
    }

    private static void recordNamespace(Map<String, NamespaceInfo> byUri, String uri, String prefix) {
        if (uri == null || uri.isEmpty()) {
            return;
        }
        NamespaceInfo info = byUri.get(uri);
        if (info == null) {
            info = new NamespaceInfo();
            info.uri = uri;
            byUri.put(uri, info);
        }
        info.uses++;
        info.prefixes.add((prefix == null) ? "" : prefix);
    }

    /** A {@link NamespaceContext} over a fixed prefix map. */
    static final class SimpleNamespaceContext implements NamespaceContext {
        private final Map<String, String> prefixToUri;

        SimpleNamespaceContext(Map<String, String> prefixToUri) {
            this.prefixToUri = prefixToUri;
        }

        @Override
        public String getNamespaceURI(String prefix) {
            String uri = prefixToUri.get(prefix);
            return (uri == null) ? XMLConstants.NULL_NS_URI : uri;
        }

        @Override
        public String getPrefix(String uri) {
            for (Map.Entry<String, String> entry : prefixToUri.entrySet()) {
                if (entry.getValue().equals(uri)) {
                    return entry.getKey();
                }
            }
            return null;
        }

        @Override
        public Iterator<String> getPrefixes(String uri) {
            List<String> prefixes = new ArrayList<>();
            for (Map.Entry<String, String> entry : prefixToUri.entrySet()) {
                if (entry.getValue().equals(uri)) {
                    prefixes.add(entry.getKey());
                }
            }
            return prefixes.iterator();
        }
    }

    /** Prefix map of a document plus the caller's {@code {ns: {…}}} entries. */
    static Map<String, String> namespaceMap(Loaded loaded, Map<String, String> userNamespaces) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("xml", XMLConstants.XML_NS_URI);
        List<Element> elements = new ArrayList<>();
        collectElements(loaded.document.getDocumentElement(), elements);
        Set<String> usedPrefixes = new LinkedHashSet<>();
        for (Element element : elements) {
            String prefix = element.getPrefix();
            String uri = element.getNamespaceURI();
            if (prefix != null && !prefix.isEmpty() && uri != null) {
                usedPrefixes.add(prefix);
                map.putIfAbsent(prefix, uri);
            }
            NamedNodeMap attributes = element.getAttributes();
            if (attributes != null) {
                for (int i = 0; i < attributes.getLength(); i++) {
                    Node attribute = attributes.item(i);
                    String name = attribute.getNodeName();
                    if (name != null && name.startsWith("xmlns:")) {
                        usedPrefixes.add(name.substring(6));
                        map.putIfAbsent(name.substring(6), attribute.getNodeValue());
                    }
                }
            }
        }
        if (userNamespaces != null) {
            map.putAll(userNamespaces);
        }
        String defaultUri = loaded.document.getDocumentElement().getNamespaceURI();
        if (defaultUri != null && !defaultUri.isEmpty() && !usedPrefixes.contains("d")
                && !map.containsKey("d")) {
            map.put("d", defaultUri);
        }
        return map;
    }

    // ========================================================================
    // XPath
    // ========================================================================

    /**
     * Evaluates an XPath 1.0 expression to a node set.
     *
     * @throws XPathProblem when the expression cannot be compiled
     */
    static List<Node> evaluateNodes(Loaded loaded, String expression, Map<String, String> namespaces,
                                    String nsKey) {
        XPathExpression compiled = compile(loaded, expression, namespaces, nsKey);
        try {
            Object result = compiled.evaluate(loaded.document, XPathConstants.NODESET);
            List<Node> nodes = new ArrayList<>();
            if (result instanceof NodeList nodeList) {
                for (int i = 0; i < nodeList.getLength(); i++) {
                    nodes.add(nodeList.item(i));
                }
            }
            return nodes;
        } catch (XPathExpressionException e) {
            throw new XPathProblem(sanitize(e.getMessage(), loaded.source));
        } catch (RuntimeException e) {
            throw new XPathProblem(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /** Compiles (with cache) an expression for the given namespace map. */
    static XPathExpression compile(Loaded loaded, String expression, Map<String, String> namespaces,
                                   String nsKey) {
        String key = nsKey + "\u0000" + expression;
        XPathExpression cached = loaded.xpathCache.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            XPath xpath = XPathFactory.newInstance().newXPath();
            if (namespaces != null && !namespaces.isEmpty()) {
                xpath.setNamespaceContext(new SimpleNamespaceContext(namespaces));
            }
            XPathExpression compiled = xpath.compile(expression);
            loaded.xpathCache.put(key, compiled);
            return compiled;
        } catch (XPathExpressionException | RuntimeException e) {
            throw new XPathProblem(sanitize(e.getMessage(), loaded.source));
        }
    }

    // ========================================================================
    // Zero-hit hints
    // ========================================================================

    /**
     * Explains why a query found nothing: the failing step, what exists there instead, and
     * the namespace trap. Never guesses a "probably meant" name - it lists what is there.
     *
     * @param problem the compile problem when the expression was rejected (else {@code null})
     */
    static Map<String, Object> zeroHitHint(Loaded loaded, String expression,
                                          Map<String, String> namespaces, String nsKey,
                                          XPathProblem problem) {
        Map<String, Object> hint = new LinkedHashMap<>();
        try {
            if (problem != null) {
                hint.put("reason", "unsupported-xpath");
                hint.put("detail", problem.detail());
                Matcher unsupported = XPATH_UNSUPPORTED.matcher(expression);
                hint.put("try", unsupported.find()
                        ? "XPath 1.0 only: rewrite '" + unsupported.group(1) + "()' - filter in JS instead"
                                + " (xml.find(...).nodes.map(n => …)) or use xml.each()/xml.grep()"
                        : "simplify the expression to XPath 1.0 (no 'let', '!' or '=>', functions of XPath 2.0+)");
                return hint;
            }
            String defaultUri = loaded.document.getDocumentElement().getNamespaceURI();
            boolean queryUsesPrefix = expression.indexOf(':') >= 0;
            if (defaultUri != null && !defaultUri.isEmpty() && !queryUsesPrefix) {
                hint.put("reason", "default-namespace");
                hint.put("uri", defaultUri);
                hint.put("try", "XPath 1.0 cannot match a default namespace with unprefixed names:"
                        + " pass {ns: {d: \"" + defaultUri + "\"}} and use the prefix, e.g."
                        + " //d:" + localOf(expression) + " - or match by local name:"
                        + " \"//*[local-name()='" + localOf(expression) + "']\"");
                return hint;
            }
            return stepHint(loaded, expression, namespaces, nsKey);
        } catch (RuntimeException e) {
            hint.put("reason", "unknown");
            hint.put("detail", "the query could not be analyzed further");
            hint.put("elements", elementNameCensus(loaded, 20));
            return hint;
        }
    }

    /** Finds the first step of the expression that no longer matches. */
    private static Map<String, Object> stepHint(Loaded loaded, String expression,
                                                Map<String, String> namespaces, String nsKey) {
        Map<String, Object> hint = new LinkedHashMap<>();
        String lastGood = null;
        List<Node> lastNodes = null;
        for (String prefix : cutPoints(expression)) {
            List<Node> nodes;
            try {
                nodes = evaluateNodes(loaded, prefix, namespaces, nsKey);
            } catch (XPathProblem problem) {
                break;
            }
            if (nodes.isEmpty()) {
                break;
            }
            lastGood = prefix;
            lastNodes = nodes;
        }
        Element context = firstElement(lastNodes);
        if (context == null) {
            hint.put("reason", "element-unknown");
            hint.put("queried", firstStepName(expression));
            hint.put("elements", elementNameCensus(loaded, 20));
            hint.put("documentElement", loaded.document.getDocumentElement().getNodeName());
            return hint;
        }
        hint.put("at", pathOf(context));
        String step = remainder(expression, lastGood);
        hint.put("failedStep", step);
        List<String> attributes = attributesIn(step);
        if (!attributes.isEmpty()) {
            List<String> missing = new ArrayList<>();
            for (String attribute : attributes) {
                if (!hasAttribute(context, attribute)) {
                    missing.add(attribute);
                }
            }
            if (!missing.isEmpty()) {
                hint.put("reason", "attribute-unknown");
                hint.put("attributes", missing);
                hint.put("attrNames", new ArrayList<>(attrsOf(context).keySet()));
                return hint;
            }
        }
        Matcher bareIndex = BARE_INDEX.matcher(step);
        if (bareIndex.matches()) {
            hint.put("reason", "index-out-of-range");
            hint.put("index", Integer.parseInt(bareIndex.group(1)));
            hint.put("available", (lastNodes == null) ? 0 : lastNodes.size());
            return hint;
        }
        Matcher indexStep = XPATH_INDEX.matcher(step);
        if (indexStep.matches()) {
            String name = indexStep.group(1);
            int wanted = Integer.parseInt(indexStep.group(2));
            int available = 0;
            for (Element child : childElements(context)) {
                if (nameMatches(child, name)) {
                    available++;
                }
            }
            hint.put("reason", "index-out-of-range");
            hint.put("index", wanted);
            hint.put("available", available);
            hint.put("childNames", childNames(context));
            return hint;
        }
        hint.put("reason", "step-unknown");
        hint.put("childNames", childNames(context));
        hint.put("elements", elementNameCensus(loaded, 20));
        return hint;
    }

    private static List<String> childNames(Element element) {
        List<String> names = new ArrayList<>();
        int count = 0;
        for (Element child : childElements(element)) {
            if (count++ >= HINT_NODE_LIMIT) {
                break;
            }
            if (!names.contains(child.getNodeName())) {
                names.add(child.getNodeName());
            }
        }
        return names;
    }

    private static boolean hasAttribute(Element element, String name) {
        if (element.hasAttribute(name)) {
            return true;
        }
        NamedNodeMap map = element.getAttributes();
        if (map == null) {
            return false;
        }
        for (int i = 0; i < map.getLength(); i++) {
            Node attribute = map.item(i);
            String local = attribute.getLocalName();
            if (name.equals(local) || name.equals(attribute.getNodeName())) {
                return true;
            }
        }
        return false;
    }

    private static List<String> attributesIn(String step) {
        List<String> attributes = new ArrayList<>();
        Matcher matcher = XPATH_ATTR.matcher(step);
        while (matcher.find()) {
            attributes.add(matcher.group(1));
        }
        return attributes;
    }

    /** Positions at which the expression can be cut into a valid prefix. */
    static List<String> cutPoints(String expression) {
        List<String> points = new ArrayList<>();
        int depth = 0;
        int lastSlash = -1;
        for (int i = 1; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            } else if (c == '/' && depth == 0) {
                lastSlash = i;
                String prefix = expression.substring(0, i);
                if (!prefix.isEmpty() && !prefix.endsWith("/") && !prefix.endsWith("//")) {
                    points.add(prefix);
                }
            }
        }
        // a predicate of the last step is worth its own cut point: "//node[@lang]" fails at
        // the predicate, not at "//node", and the hint has to say which of the two it was
        if (lastSlash >= 0) {
            int bracket = expression.indexOf('[', lastSlash + 1);
            if (bracket > lastSlash) {
                String withoutPredicate = expression.substring(0, bracket);
                if (!points.contains(withoutPredicate)) {
                    points.add(withoutPredicate);
                }
            }
        }
        points.add(expression);
        // shortest first: the progressive evaluation looks for the FIRST prefix that no
        // longer matches, so longer prefixes must come last
        points.sort((a, b) -> Integer.compare(a.length(), b.length()));
        return points;
    }

    private static String remainder(String expression, String lastGood) {
        if (lastGood == null) {
            return expression;
        }
        String rest = expression.substring(Math.min(lastGood.length(), expression.length()));
        rest = rest.replaceFirst("^/+", "");
        int depth = 0;
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            } else if (c == '/' && depth == 0) {
                return rest.substring(0, i);
            }
        }
        return rest;
    }

    private static String firstStepName(String expression) {
        String trimmed = expression.replaceFirst("^/+", "");
        int slash = trimmed.indexOf('/');
        String step = (slash < 0) ? trimmed : trimmed.substring(0, slash);
        return step.replaceAll("\\[.*", "");
    }

    private static Element firstElement(List<Node> nodes) {
        if (nodes == null) {
            return null;
        }
        for (Node node : nodes) {
            if (node instanceof Element element) {
                return element;
            }
        }
        return null;
    }

    /** Element names with counts, most frequent first (bounded). */
    static List<Map<String, Object>> elementNameCensus(Loaded loaded, int limit) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<Element> elements = new ArrayList<>();
        collectElements(loaded.document.getDocumentElement(), elements);
        for (Element element : elements) {
            counts.merge(element.getNodeName(), 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((a, b) -> {
            int byCount = Integer.compare(b.getValue(), a.getValue());
            return (byCount != 0) ? byCount : a.getKey().compareTo(b.getKey());
        });
        List<Map<String, Object>> census = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : entries) {
            if (census.size() >= limit) {
                break;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", entry.getKey());
            item.put("count", entry.getValue());
            census.add(item);
        }
        return census;
    }

    /** The last name of a location path (used for the local-name() suggestion). */
    private static String localOf(String expression) {
        String trimmed = expression.replaceAll("/+$", "");
        int slash = trimmed.lastIndexOf('/');
        String step = (slash < 0) ? trimmed : trimmed.substring(slash + 1);
        step = step.replaceAll("\\[.*", "");
        int colon = step.indexOf(':');
        return (colon < 0) ? step : step.substring(colon + 1);
    }

}
