package org.rogmann.mcp2sdk.xml;

import org.rogmann.mcp2sdk.js.JsUserRuntimeException;
import org.rogmann.mcp2sdk.xml.JsXmlErrors.Parse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.namespace.QName;
import javax.xml.stream.Location;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Streaming XML engine (StAX) behind the {@code xml} module: the functions that need no
 * tree - {@code profile}, {@code attrNames}, {@code tree}, {@code sample}, {@code count},
 * {@code scan}, {@code each}, {@code grep}.
 *
 * <p>
 * One driver loop ({@link #drive}) walks the document once and reports events to a
 * {@link ScanHandler}; handlers keep aggregates or the currently open element only. Memory
 * is proportional to the document <em>depth</em>, not to its size, and every element gets
 * its line number for free (the StAX locator) - which is why streaming results always
 * carry {@code line} while DOM results need {@code {lines: true}}.
 * </p>
 *
 * <h3>Paths</h3>
 * <p>
 * Two forms are produced for every element: {@code path} is the canonical, fully indexed
 * XPath ({@code /root/node[2]/code[1]}, round-trips into {@code xml.get()}) and
 * {@code pathDotted} is the readable form that omits the index of first occurrences
 * ({@code root.node[2].code}) - lossless, because "the first one" is what an index-less
 * reference means.
 * </p>
 *
 * <h3>Security</h3>
 * <p>
 * {@code IS_SUPPORTING_EXTERNAL_ENTITIES=false} always; {@code SUPPORT_DTD=true} so
 * internal-subset entity declarations still work and the DOCTYPE stays visible for
 * reporting. Nothing is fetched from anywhere.
 * </p>
 */
final class JsXmlScan {
    /** logger */
    private static final Logger LOGGER = LoggerFactory.getLogger(JsXmlScan.class);

    /** Attribute values collected per attribute name when {@code {values: true}}. */
    private static final int VALUE_SAMPLES = 3;

    /** Element limit of the passes that aggregate (their result is built after the scan). */
    private static final int AGGREGATE_SCAN_LIMIT = 200_000;

    /** Implementation-specific properties that enable CDATA events (best effort). */
    private static final String[] CDATA_PROPERTIES = {
            "http://java.sun.com/xml/stream/properties/report-cdata-event",
            "javax.xml.stream.isReportCData"};

    /** Set when the StAX implementation accepted a CDATA reporting property. */
    private static boolean cdataEventsSupported;

    private JsXmlScan() {
        // Utility class
    }

    // ========================================================================
    // Driver
    // ========================================================================

    /** Event sink of the streaming driver. */
    interface ScanHandler {
        default void doctype(String text, int line) {
        }

        default void processingInstruction(String target, String data, int line) {
        }

        default void comment(int line) {
        }

        /** A non-empty namespace URI used by the current element or one of its attributes. */
        default void namespace(String uri) {
        }

        void startElement(Frame frame);

        void endElement(Frame frame);

        /** @return true to stop the scan (early abort) */
        default boolean done() {
            return false;
        }
    }

    /** Raw character data of the current element (used for CDATA reporting). */
    interface TextSink {
        void text(Frame frame, String value, boolean cdata, int line);
    }

    /** One open element: name, path forms, attributes and accumulated own text. */
    static final class Frame {
        /** Parent element or {@code null} for the document element. */
        Frame parent;
        String name;
        String qname;
        int depth;
        int index;
        String path = "";
        String dotted = "";
        String namePath = "";
        String dirKey = "";
        int startLine;
        LinkedHashMap<String, String> attrs;
        final StringBuilder text = new StringBuilder();
        int textChars;
        boolean hasCdata;
        int cdataChars;
        int childElements;
        private Map<String, Integer> childCounts;

        /** Counts one child occurrence and returns the child's index among same-name siblings. */
        int countChild(String childName) {
            if (childCounts == null) {
                childCounts = new LinkedHashMap<>(4);
            }
            Integer previous = childCounts.get(childName);
            int next = (previous == null) ? 1 : previous + 1;
            childCounts.put(childName, next);
            return next;
        }

        /** Appends own text, buffering at most {@code cap} characters but counting all of them. */
        void appendText(String value, int cap, boolean cdata) {
            textChars += value.length();
            if (cdata) {
                hasCdata = true;
                cdataChars += value.length();
            }
            if (text.length() < cap) {
                int room = cap - text.length();
                text.append(value, 0, Math.min(value.length(), room));
            }
        }
    }

    /**
     * One streaming pass over the source.
     *
     * @param source      resolved source (opened here, closed at the end of the pass)
     * @param handler     event sink
     * @param textCap     maximum own-text characters buffered per element (0 = none)
     * @param textSink    optional raw-text sink (may be {@code null})
     */
    static void drive(JsXmlSource.Resolved source, ScanHandler handler, int textCap,
                      TextSink textSink) {
        XMLInputFactory factory = newFactory();
        try (InputStream in = source.open()) {
            XMLStreamReader reader = factory.createXMLStreamReader(source.display(), in);
            Deque<Frame> stack = new ArrayDeque<>();
            try {
                while (reader.hasNext()) {
                    int event = reader.next();
                    int line = reader.getLocation().getLineNumber();
                    switch (event) {
                        case XMLStreamConstants.START_ELEMENT -> {
                            Frame parent = stack.peek();
                            Frame frame = new Frame();
                            QName qname = reader.getName();
                            frame.parent = parent;
                            frame.name = qname.getLocalPart();
                            frame.qname = qualifiedName(qname);
                            frame.depth = (parent == null) ? 1 : parent.depth + 1;
                            frame.index = (parent == null) ? 1 : parent.countChild(frame.name);
                            if (parent != null) {
                                parent.childElements++;
                            }
                            // the document element is unique: no index (same rule as the DOM path)
                            frame.path = ((parent == null) ? "" : parent.path)
                                    + "/" + frame.name
                                    + ((parent == null) ? "" : "[" + frame.index + "]");
                            frame.dotted = ((parent == null) ? "" : parent.dotted + ".") + frame.name
                                    + (frame.index > 1 ? "[" + frame.index + "]" : "");
                            frame.namePath = ((parent == null) ? "" : parent.namePath) + "/" + frame.name;
                            frame.dirKey = ((parent == null) ? "" : parent.dirKey + "/") + frame.name;
                            frame.startLine = line;
                            frame.attrs = readAttributes(reader);
                            reportNamespaces(reader, handler);
                            stack.push(frame);
                            handler.startElement(frame);
                        }
                        case XMLStreamConstants.END_ELEMENT -> {
                            Frame frame = stack.poll();
                            if (frame != null) {
                                handler.endElement(frame);
                            }
                        }
                        case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                            Frame top = stack.peek();
                            if (top != null) {
                                String value = reader.getText();
                                // XMLStreamReader has no isCData(): a CDATA section arrives
                                // as its own event type when the implementation reports them
                                boolean cdata = (event == XMLStreamConstants.CDATA);
                                top.appendText(value, textCap, cdata);
                                if (textSink != null) {
                                    textSink.text(top, value, cdata, line);
                                }
                            }
                        }
                        case XMLStreamConstants.COMMENT -> handler.comment(line);
                        case XMLStreamConstants.PROCESSING_INSTRUCTION ->
                                handler.processingInstruction(reader.getPITarget(), reader.getPIData(), line);
                        case XMLStreamConstants.DTD -> handler.doctype(reader.getText(), line);
                        default -> {
                            // START_DOCUMENT, END_DOCUMENT, ENTITY, NAMESPACE, …: not needed here
                        }
                    }
                    if (handler.done()) {
                        break;
                    }
                }
            } finally {
                closeQuietly(reader);
            }
        } catch (XMLStreamException e) {
            throw translate(e, source.display());
        } catch (IOException e) {
            throw new JsUserRuntimeException("Cannot read " + source.display() + ": " + e.getMessage(), e);
        }
    }

    /** Convenience overload without a raw-text sink. */
    static void drive(JsXmlSource.Resolved source, ScanHandler handler, int textCap) {
        drive(source, handler, textCap, null);
    }

    private static void closeQuietly(XMLStreamReader reader) {
        try {
            reader.close();
        } catch (XMLStreamException ignored) {
            // closing never changes a result
        }
    }

    /** Qualified name as written: {@code prefix:localPart}, or just the local part. */
    private static String qualifiedName(QName qname) {
        String prefix = qname.getPrefix();
        return (prefix == null || prefix.isEmpty())
                ? qname.getLocalPart() : prefix + ":" + qname.getLocalPart();
    }

    private static LinkedHashMap<String, String> readAttributes(XMLStreamReader reader) {
        int count = reader.getAttributeCount();
        if (count == 0) {
            return null;
        }
        LinkedHashMap<String, String> attrs = new LinkedHashMap<>(count * 2);
        for (int i = 0; i < count; i++) {
            QName name = reader.getAttributeName(i);
            String prefix = name.getPrefix();
            String key = (prefix == null || prefix.isEmpty())
                    ? name.getLocalPart() : prefix + ":" + name.getLocalPart();
            attrs.put(key, reader.getAttributeValue(i));
        }
        return attrs;
    }

    /** Reports the namespace of the current element and of its attributes. */
    private static void reportNamespaces(XMLStreamReader reader, ScanHandler handler) {
        String elementUri = reader.getNamespaceURI();
        if (elementUri != null && !elementUri.isEmpty()) {
            handler.namespace(elementUri);
        }
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            String attributeUri = reader.getAttributeNamespace(i);
            if (attributeUri != null && !attributeUri.isEmpty()
                    && !attributeUri.equals(elementUri)) {
                handler.namespace(attributeUri);
            }
        }
    }

    private static XMLInputFactory newFactory() {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        setPropertyQuietly(factory, XMLInputFactory.SUPPORT_DTD, Boolean.TRUE);
        setPropertyQuietly(factory, XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        for (String property : CDATA_PROPERTIES) {
            if (setPropertyQuietly(factory, property, Boolean.TRUE)) {
                cdataEventsSupported = true;
            }
        }
        // XMLInputFactory has no setNamespaceAware(): namespace awareness is a property
        setPropertyQuietly(factory, XMLInputFactory.IS_NAMESPACE_AWARE, Boolean.TRUE);
        return factory;
    }

    /**
     * True when the StAX implementation reports CDATA sections as own events, which is what
     * {@code profile.stats.cdataSections} depends on. Reported to the caller instead of
     * silently returning 0.
     */
    static boolean cdataEventsSupported() {
        return cdataEventsSupported;
    }

    private static boolean setPropertyQuietly(XMLInputFactory factory, String name, Object value) {
        try {
            factory.setProperty(name, value);
            return true;
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Unsupported property: " + name, e);
            // Property unsupported by this implementation: best effort. CDATA reporting then
            // yields 0 in the streaming stats; the DOM side detects CDATA sections reliably.
            return false;
        }
    }

    /** Converts a StAX parse error into a structured {@link Parse} with line/column. */
    static Parse translate(XMLStreamException e, String display) {
        int line = 0;
        int column = 0;
        Location location = e.getLocation();
        if (location != null) {
            line = location.getLineNumber();
            column = location.getColumnNumber();
        }
        String message = (e.getMessage() == null) ? e.getClass().getSimpleName() : e.getMessage();
        return new Parse(display, line, column, message);
    }

    // ========================================================================
    // Selector
    // ========================================================================

    /** Element filter of {@code count}/{@code scan}/{@code each} (all members optional). */
    static final class Selector {
        String name;
        Pattern pathPattern;
        boolean pathAbsolute;
        String ancestor;
        Map<String, String> attrs = new LinkedHashMap<>();
        List<String> hasAttrs = new ArrayList<>();
        Pattern text;
        Integer depth;

        boolean matches(Frame frame) {
            if (depth != null && frame.depth != depth) {
                return false;
            }
            if (name != null) {
                if (name.indexOf(':') >= 0) {
                    if (!name.equals(frame.qname)) {
                        return false;
                    }
                } else if (!"*".equals(name) && !name.equals(frame.name)) {
                    return false;
                }
            }
            if (ancestor != null && !frame.namePath.contains("/" + ancestor + "/")) {
                return false;
            }
            if (pathPattern != null) {
                Matcher matcher = pathPattern.matcher(frame.namePath);
                if (pathAbsolute ? !matcher.matches() : !matcher.find()) {
                    return false;
                }
            }
            if (!attrs.isEmpty()) {
                for (Map.Entry<String, String> entry : attrs.entrySet()) {
                    String value = (frame.attrs == null) ? null : frame.attrs.get(entry.getKey());
                    if (!entry.getValue().equals(value)) {
                        return false;
                    }
                }
            }
            for (String required : hasAttrs) {
                if (frame.attrs == null || !frame.attrs.containsKey(required)) {
                    return false;
                }
            }
            if (text != null && !text.matcher(frame.text).find()) {
                return false;
            }
            return true;
        }
    }

    /**
     * Parses a selector map (strict: unknown members are refused).
     *
     * @param selector members {@code name}, {@code path}, {@code ancestor}, {@code attrs},
     *                 {@code hasAttrs}, {@code text}, {@code depth}
     */
    static Selector parseSelector(Map<String, Object> selector) {
        Selector result = new Selector();
        if (selector == null || selector.isEmpty()) {
            return result;
        }
        for (Map.Entry<String, Object> entry : selector.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            switch (key) {
                case "name" -> result.name = JsXml.requireString("selector.name", value);
                case "path" -> {
                    String pattern = JsXml.requireString("selector.path", value);
                    result.pathAbsolute = pattern.startsWith("/");
                    String normalized = result.pathAbsolute ? pattern.substring(1) : pattern;
                    String regex = pathRegex(normalized).replace("\\*", "[^/]*");
                    result.pathPattern = Pattern.compile(
                            result.pathAbsolute ? "^" + regex + "$" : "/" + regex + "$");
                }
                case "ancestor" -> result.ancestor = JsXml.requireString("selector.ancestor", value);
                case "attrs" -> result.attrs = JsXml.toStringMap("selector.attrs", value);
                case "hasAttrs" -> result.hasAttrs = JsXml.toStringList("selector.hasAttrs", value);
                case "text" -> result.text = compilePattern("selector.text", value);
                case "depth" -> result.depth = (int) JsXml.requireNumber("selector.depth", value);
                default -> throw new IllegalArgumentException("selector: unknown member '" + key
                        + "' (supported: name, path, ancestor, attrs, hasAttrs, text, depth)");
            }
        }
        return result;
    }

    /** Escapes a name path for regex use, keeping {@code *} as a literal token for replacement. */
    private static String pathRegex(String pattern) {
        StringBuilder sb = new StringBuilder(pattern.length() * 2);
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*') {
                sb.append("\\*");
            } else if ("\\.[]{}()+-?^$|".indexOf(c) >= 0) {
                sb.append('\\').append(c);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static Pattern compilePattern(String what, Object value) {
        String source = JsXml.requireString(what, value);
        try {
            return Pattern.compile(unwrapRegexLiteral(source));
        } catch (PatternSyntaxException e) {
            throw new JsUserRuntimeException(what + " is not a valid regular expression: "
                    + e.getDescription(), e);
        }
    }

    /**
     * Accepts a JS {@code RegExp} where a pattern string is expected: {@code String(re)} is
     * {@code /source/flags}, so such a literal is unwrapped to its source.
     */
    private static String unwrapRegexLiteral(String source) {
        if (source.length() > 2 && source.startsWith("/")) {
            int last = source.lastIndexOf('/');
            if (last > 0 && source.substring(last + 1).matches("[a-z]*")) {
                return source.substring(1, last);
            }
        }
        return source;
    }

    // ========================================================================
    // Descriptors
    // ========================================================================

    /**
     * Node descriptor of a frame (streaming form: no DOM node id).
     *
     * @param line line number ({@code null} to omit)
     */
    static Map<String, Object> describe(Frame frame, Integer line) {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("name", frame.qname);
        descriptor.put("path", frame.path);
        descriptor.put("pathDotted", frame.dotted);
        if (line != null) {
            descriptor.put("line", line);
        }
        descriptor.put("attrs", (frame.attrs == null)
                ? new LinkedHashMap<String, String>() : new LinkedHashMap<>(frame.attrs));
        descriptor.put("childCount", frame.childElements);
        descriptor.put("textChars", frame.textChars);
        String preview = frame.text.toString();
        descriptor.put("text", preview);
        descriptor.put("textTruncated", frame.textChars > preview.length());
        if (frame.hasCdata) {
            descriptor.put("hasCdata", true);
        }
        return descriptor;
    }

    // ========================================================================
    // profile / attrNames
    // ========================================================================

    /** Census of one element name. */
    private static final class ElemCensus {
        String name;
        int count;
        int maxDepth;
        boolean hasText;
        boolean hasCdata;
        final LinkedHashMap<String, Integer> attrs = new LinkedHashMap<>();
        final LinkedHashSet<String> childNames = new LinkedHashSet<>();
        final Map<String, LinkedHashSet<String>> attrValues = new LinkedHashMap<>();
    }

    /** Aggregated result of one profiling pass (the envelope is added by {@link JsXml}). */
    static final class Profile {
        String root;
        int rootLine;
        int elementCount;
        int attrNamesTotal;
        int comments;
        int pis;
        int cdataSections;
        int maxDepth;
        boolean truncated;
        boolean cdataDetection;
        boolean stoppedByLimit;
        int visited;
        Map<String, Object> doctype;
        final LinkedHashMap<String, ElemCensus> census = new LinkedHashMap<>();
        final LinkedHashMap<String, Integer> namespaceUses = new LinkedHashMap<>();
        final LinkedHashSet<String> piTargets = new LinkedHashSet<>();
    }

    /**
     * Runs one profiling pass.
     *
     * @param maxElements    distinct element names collected (0 = unlimited)
     * @param values         collect up to 3 example values per attribute name
     * @param cdataDetection whether the StAX implementation reports CDATA events
     */
    static Profile profile(JsXmlSource.Resolved source, int maxElements, boolean values,
                           boolean cdataDetection) {
        Profile profile = new Profile();
        profile.cdataDetection = cdataDetection;
        drive(source, new ScanHandler() {
            @Override
            public void doctype(String text, int line) {
                profile.doctype = JsXmlDom.parseDoctype(text);
            }

            @Override
            public void processingInstruction(String target, String data, int line) {
                profile.pis++;
                if (target != null) {
                    profile.piTargets.add(target);
                }
            }

            @Override
            public void comment(int line) {
                profile.comments++;
            }

            @Override
            public void namespace(String uri) {
                profile.namespaceUses.merge(uri, 1, Integer::sum);
            }

            @Override
            public void startElement(Frame frame) {
                profile.visited++;
                profile.elementCount++;
                if (profile.root == null) {
                    profile.root = frame.qname;
                    profile.rootLine = frame.startLine;
                }
                if (frame.depth > profile.maxDepth) {
                    profile.maxDepth = frame.depth;
                }
                ElemCensus census = profile.census.get(frame.qname);
                if (census == null) {
                    if (maxElements > 0 && profile.census.size() >= maxElements) {
                        profile.truncated = true;
                        return;
                    }
                    census = new ElemCensus();
                    census.name = frame.qname;
                    profile.census.put(frame.qname, census);
                }
                census.count++;
                if (frame.depth > census.maxDepth) {
                    census.maxDepth = frame.depth;
                }
                if (frame.attrs != null) {
                    for (Map.Entry<String, String> attribute : frame.attrs.entrySet()) {
                        Integer seen = census.attrs.get(attribute.getKey());
                        census.attrs.put(attribute.getKey(), seen == null ? 1 : seen + 1);
                        if (values) {
                            LinkedHashSet<String> samples = census.attrValues
                                    .computeIfAbsent(attribute.getKey(), k -> new LinkedHashSet<>());
                            if (samples.size() < VALUE_SAMPLES) {
                                samples.add(attribute.getValue());
                            }
                        }
                    }
                }
                if (frame.parent != null) {
                    ElemCensus parentCensus = profile.census.get(frame.parent.qname);
                    if (parentCensus != null) {
                        parentCensus.childNames.add(frame.qname);
                    }
                }
            }

            @Override
            public void endElement(Frame frame) {
                ElemCensus census = profile.census.get(frame.qname);
                if (census == null) {
                    return;
                }
                if (frame.textChars > 0) {
                    census.hasText = true;
                }
                if (frame.hasCdata) {
                    census.hasCdata = true;
                }
            }

            @Override
            public boolean done() {
                if (profile.visited >= AGGREGATE_SCAN_LIMIT) {
                    profile.stoppedByLimit = true;
                    profile.truncated = true;
                    return true;
                }
                return false;
            }
        }, 0, (frame, value, cdata, line) -> {
            if (cdata) {
                profile.cdataSections++;
            }
        });
        int attrTotal = 0;
        for (ElemCensus census : profile.census.values()) {
            attrTotal += census.attrs.size();
        }
        profile.attrNamesTotal = attrTotal;
        return profile;
    }

    /** Renders the census of a profile as the {@code elements} list of the result. */
    static List<Map<String, Object>> censusList(Profile profile, int maxElements, int maxAttrs,
                                                boolean values) {
        List<Map<String, Object>> list = new ArrayList<>();
        int shown = 0;
        for (ElemCensus census : profile.census.values()) {
            if (maxElements > 0 && shown >= maxElements) {
                profile.truncated = true;
                break;
            }
            shown++;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", census.name);
            entry.put("count", census.count);
            entry.put("maxDepth", census.maxDepth);
            entry.put("hasText", census.hasText);
            entry.put("hasCdata", census.hasCdata);
            entry.put("childNames", new ArrayList<>(census.childNames));
            List<Map<String, Object>> attrList = new ArrayList<>();
            int attrShown = 0;
            for (Map.Entry<String, Integer> attribute : census.attrs.entrySet()) {
                if (maxAttrs > 0 && attrShown >= maxAttrs) {
                    profile.truncated = true;
                    break;
                }
                attrShown++;
                Map<String, Object> attr = new LinkedHashMap<>();
                attr.put("name", attribute.getKey());
                attr.put("count", attribute.getValue());
                if (values) {
                    LinkedHashSet<String> samples = census.attrValues.get(attribute.getKey());
                    attr.put("examples", (samples == null) ? List.of() : new ArrayList<>(samples));
                }
                attrList.add(attr);
            }
            entry.put("attrs", attrList);
            list.add(entry);
        }
        return list;
    }

    /** Census entry for an element name, tolerating local-name vs. qualified-name lookups. */
    static Map<String, Object> attrNames(Profile profile, String elementName) {
        ElemCensus census = findCensus(profile, elementName);
        List<String> names = new ArrayList<>();
        int count = 0;
        if (census != null) {
            count = census.count;
            names.addAll(census.attrs.keySet());
            names.sort(null);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("element", (census == null) ? elementName : census.name);
        result.put("count", count);
        result.put("names", names);
        result.put("total", names.size());
        result.put("returned", names.size());
        result.put("truncated", false);
        if (census == null) {
            result.put("hint", JsXml.hintUnknownElement(profile.census.keySet(), elementName));
        }
        return result;
    }

    /** Looks a census entry up by qualified name, then by local name. */
    static ElemCensus findCensus(Profile profile, String elementName) {
        ElemCensus exact = profile.census.get(elementName);
        if (exact != null) {
            return exact;
        }
        for (ElemCensus census : profile.census.values()) {
            int colon = census.name.indexOf(':');
            String local = (colon < 0) ? census.name : census.name.substring(colon + 1);
            if (local.equals(elementName)) {
                return census;
            }
        }
        return null;
    }

    /** Names of all element types seen in a pass (hint source when no DOM is open). */
    static Set<String> elementNames(JsXmlSource.Resolved source) {
        Set<String> names = new LinkedHashSet<>();
        drive(source, new ScanHandler() {
            @Override
            public void startElement(Frame frame) {
                names.add(frame.qname);
            }

            @Override
            public void endElement(Frame frame) {
                // nothing to unwind
            }
        }, 0);
        return names;
    }

    // ========================================================================
    // tree
    // ========================================================================

    /** One directory entry of the skeleton: an element name under one parent path. */
    static final class DirNode {
        String key;
        String name;
        int count;
        int minLine = Integer.MAX_VALUE;
        final LinkedHashSet<String> attrs = new LinkedHashSet<>();
    }

    /**
     * Builds the structure skeleton: element names with counts and attribute names,
     * indented by depth, in document order.
     */
    static List<String> tree(JsXmlSource.Resolved source, int maxDepth, int maxLines,
                             boolean withAttrs) {
        LinkedHashMap<String, DirNode> directory = new LinkedHashMap<>();
        int[] visited = new int[1];
        drive(source, new ScanHandler() {
            @Override
            public void startElement(Frame frame) {
                visited[0]++;
                if (frame.depth > maxDepth) {
                    return;
                }
                DirNode node = directory.get(frame.dirKey);
                if (node == null) {
                    node = new DirNode();
                    node.key = frame.dirKey;
                    node.name = frame.name;
                    directory.put(frame.dirKey, node);
                }
                node.count++;
                node.minLine = Math.min(node.minLine, frame.startLine);
                if (withAttrs && frame.attrs != null) {
                    node.attrs.addAll(frame.attrs.keySet());
                }
            }

            @Override
            public void endElement(Frame frame) {
                // nothing to unwind
            }

            @Override
            public boolean done() {
                return visited[0] >= AGGREGATE_SCAN_LIMIT;
            }
        }, 0);
        return renderTree(directory, maxLines, withAttrs);
    }

    /**
     * Renders a directory map in tree order. Insertion order of the map is document order
     * (pre-order), so a linear pass with the depth taken from the key is a correct
     * depth-first rendering.
     */
    static List<String> renderTree(LinkedHashMap<String, DirNode> directory, int maxLines,
                                   boolean withAttrs) {
        List<String> lines = new ArrayList<>();
        for (DirNode node : directory.values()) {
            if (lines.size() >= maxLines) {
                lines.add("… (truncated: " + (directory.size() - lines.size())
                        + " more element types - raise {maxLines})");
                break;
            }
            int depth = node.key.isEmpty() ? 0 : node.key.split("/").length - 1;
            StringBuilder sb = new StringBuilder();
            sb.append("  ".repeat(Math.max(0, depth))).append(node.name)
                    .append(" [").append(node.count).append("]");
            if (withAttrs && !node.attrs.isEmpty()) {
                sb.append("  @").append(String.join(" @", node.attrs));
            }
            lines.add(sb.toString());
        }
        return lines;
    }

    // ========================================================================
    // count / scan / each / grep / sample
    // ========================================================================

    /** Visitor of {@link #each} (returning false stops the iteration). */
    interface Visitor {
        boolean visit(Map<String, Object> descriptor);
    }

    /** Counts elements matching a selector. */
    static Map<String, Object> count(JsXmlSource.Resolved source, Selector selector) {
        int[] matched = new int[1];
        int[] visited = new int[1];
        drive(source, new ScanHandler() {
            @Override
            public void startElement(Frame frame) {
                visited[0]++;
                if (selector.matches(frame)) {
                    matched[0]++;
                }
            }

            @Override
            public void endElement(Frame frame) {
                // text predicates are only decidable at end element - count() documents
                // that it matches on the start tag (name/path/ancestor/attrs/depth)
            }
        }, 0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("matched", matched[0]);
        result.put("visited", visited[0]);
        result.put("total", matched[0]);
        return result;
    }

    /** Collects up to {@code max} matching descriptors (matching at end element). */
    static Map<String, Object> scan(JsXmlSource.Resolved source, Selector selector, int max,
                                    int previewChars) {
        List<Map<String, Object>> matches = new ArrayList<>();
        int[] visited = new int[1];
        int[] matched = new int[1];
        drive(source, new ScanHandler() {
            @Override
            public void startElement(Frame frame) {
                visited[0]++;
            }

            @Override
            public void endElement(Frame frame) {
                if (!selector.matches(frame)) {
                    return;
                }
                matched[0]++;
                if (matches.size() < max) {
                    matches.add(describe(frame, frame.startLine));
                }
            }

            @Override
            public boolean done() {
                return matches.size() >= max;
            }
        }, Math.max(previewChars, 64));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("matches", matches);
        result.put("matched", matched[0]);
        result.put("visited", visited[0]);
        return result;
    }

    /** Streams matching descriptors to a visitor (constant memory, early abort). */
    static Map<String, Object> each(JsXmlSource.Resolved source, Selector selector, Visitor visitor,
                                    int max, int previewChars) {
        int[] visited = new int[1];
        int[] matched = new int[1];
        String[] stoppedBy = new String[1];
        drive(source, new ScanHandler() {
            @Override
            public void startElement(Frame frame) {
                visited[0]++;
            }

            @Override
            public void endElement(Frame frame) {
                if (!selector.matches(frame)) {
                    return;
                }
                matched[0]++;
                if (!visitor.visit(describe(frame, frame.startLine))) {
                    stoppedBy[0] = "handler";
                }
            }

            @Override
            public boolean done() {
                if (stoppedBy[0] != null) {
                    return true;
                }
                if (max > 0 && matched[0] >= max) {
                    stoppedBy[0] = "max";
                    return true;
                }
                return false;
            }
        }, Math.max(previewChars, 64));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("visited", visited[0]);
        result.put("matched", matched[0]);
        result.put("stoppedEarly", stoppedBy[0] != null);
        result.put("stoppedBy", stoppedBy[0]);
        return result;
    }

    /**
     * Content and attribute search reporting the element hierarchy of every hit.
     *
     * @param scope {@code "text"}, {@code "attr"} or {@code "any"}
     */
    static Map<String, Object> grep(JsXmlSource.Resolved source, Pattern pattern, String scope,
                                    String nameFilter, String attrFilter, int max, int previewChars) {
        List<Map<String, Object>> matches = new ArrayList<>();
        int[] visited = new int[1];
        int[] matched = new int[1];
        boolean wantText = !"attr".equals(scope);
        boolean wantAttr = !"text".equals(scope);
        drive(source, new ScanHandler() {
            @Override
            public void startElement(Frame frame) {
                visited[0]++;
                if (!wantAttr || frame.attrs == null || !nameMatches(nameFilter, frame)) {
                    return;
                }
                for (Map.Entry<String, String> attribute : frame.attrs.entrySet()) {
                    if (attrFilter != null && !attrFilter.equals(attribute.getKey())) {
                        continue;
                    }
                    String value = attribute.getValue();
                    if (value == null || !pattern.matcher(value).find()) {
                        continue;
                    }
                    matched[0]++;
                    if (matches.size() < max) {
                        matches.add(grepMatch(frame, attribute.getKey(), value, previewChars));
                    }
                }
            }

            @Override
            public void endElement(Frame frame) {
                if (!wantText || !nameMatches(nameFilter, frame)) {
                    return;
                }
                String text = frame.text.toString();
                if (text.isEmpty() || !pattern.matcher(text).find()) {
                    return;
                }
                matched[0]++;
                if (matches.size() < max) {
                    matches.add(grepMatch(frame, null, text, previewChars));
                }
            }

            @Override
            public boolean done() {
                return matches.size() >= max;
            }
        }, Math.max(previewChars, 64));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("matches", matches);
        result.put("matched", matched[0]);
        result.put("visited", visited[0]);
        return result;
    }

    private static boolean nameMatches(String nameFilter, Frame frame) {
        return nameFilter == null || nameFilter.equals(frame.qname) || nameFilter.equals(frame.name);
    }

    /** One grep hit (the preformatted {@code display} line is added by {@link JsXml}). */
    private static Map<String, Object> grepMatch(Frame frame, String attr, String value, int previewChars) {
        Map<String, Object> match = new LinkedHashMap<>();
        match.put("line", frame.startLine);
        match.put("path", frame.path);
        match.put("pathDotted", frame.dotted);
        match.put("name", frame.qname);
        match.put("attr", attr);
        match.put("text", JsXml.truncate(value, previewChars));
        return match;
    }

    /** Collects the first {@code max} elements with the given name in full. */
    static Map<String, Object> sample(JsXmlSource.Resolved source, String elementName, int max,
                                      int previewChars) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        int[] visited = new int[1];
        int[] seen = new int[1];
        drive(source, new ScanHandler() {
            @Override
            public void startElement(Frame frame) {
                visited[0]++;
            }

            @Override
            public void endElement(Frame frame) {
                if (!nameMatches(elementName, frame)) {
                    return;
                }
                seen[0]++;
                if (nodes.size() < max) {
                    nodes.add(describe(frame, frame.startLine));
                }
            }

            @Override
            public boolean done() {
                return nodes.size() >= max;
            }
        }, Math.max(previewChars, 64));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", nodes);
        result.put("matched", seen[0]);
        result.put("visited", visited[0]);
        return result;
    }
}
