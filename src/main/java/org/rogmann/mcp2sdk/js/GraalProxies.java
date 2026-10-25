package org.rogmann.mcp2sdk.js;

import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyArray;
import org.graalvm.polyglot.proxy.ProxyObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility methods for converting between GraalVM Polyglot values and
 * Java structures, using GraalVM's {@link ProxyObject} and {@link ProxyArray}.
 * <p>
 * These helpers are used to expose complex nested Java objects (Maps, Lists, arrays)
 * to JavaScript running in a GraalVM Polyglot context such that they remain
 * fully traversable from JavaScript.
 * </p>
 */
public class GraalProxies {

    private GraalProxies() {
        // Utility class
    }

    /**
     * Converts a Java Map (with nested Maps/Lists/arrays) to a ProxyObject
     * that is fully traversable from JavaScript.
     */
    public static ProxyObject toProxyObject(Map<String, Object> map) {
        return new NestedProxyObject(map);
    }

    /**
     * Converts a GraalVM Value to a Java object suitable for POI or other operations.
     */
    public static Object fromValue(Value value) {
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
            if (value.fitsInInt()) {
                return value.asInt();
            }
            if (value.fitsInLong()) {
                return value.asLong();
            }
            return value.asDouble();
        }
        if (value.isDate()) {
            return value.asDate();
        }
        // Fallback: return as string
        return value.asString();
    }

    /**
     * Converts a Java array (possibly nested) into a {@link ProxyArray} that is
     * fully traversable from JavaScript.
     * <p>
     * A plain Java array returned to JavaScript is seen as an opaque host object:
     * a sandbox whose HostAccess denies array access rejects element access, and
     * {@code JSON.stringify} renders it as an empty object {@code {}} - which is
     * the common way an LLM inspects structured results. Wrapping the array in a
     * {@link ProxyArray} gives it native JavaScript array semantics
     * ({@code Array.isArray(...) === true}, {@code .length}, {@code .join()},
     * {@code JSON.stringify}).
     * </p>
     * <p>
     * Elements are converted recursively on access: nested arrays, {@link List}s
     * and {@link Map}s become {@link NestedProxyArray}s / {@link NestedProxyObject}s.
     * </p>
     * @param array the Java array (e.g. {@code Object[]} or nested {@code Object[][]})
     * @return a ProxyArray with recursive element conversion
     */
    public static ProxyArray toProxyArray(Object[] array) {
        return new NestedProxyArray(Arrays.asList(array));
    }

    /**
     * Converts a Java list (possibly with nested structures) into a {@link ProxyArray}
     * that is fully traversable from JavaScript.
     * @see #toProxyArray(Object[]) for the rationale
     * @param list the Java list
     * @return a ProxyArray with recursive element conversion
     */
    public static ProxyArray toProxyArray(List<?> list) {
        return new NestedProxyArray(list);
    }

    /**
     * Creates a JavaScript {@code Uint8Array} (ArrayBuffer-backed, unsigned 0-255)
     * from a Java byte array.
     * <p>
     * A plain Java {@code byte[]} is seen as signed (-128..127) through interop;
     * building a real typed array keeps ECMAScript {@code Uint8Array} semantics
     * (the unified byte contract of the JS tool family).
     * </p>
     * @param uint8ArrayCtor the JS {@code Uint8Array} constructor (from the bindings)
     * @param data bytes to copy (must not be null)
     * @return a JS Uint8Array value
     */
    public static Value toUint8Array(Value uint8ArrayCtor, byte[] data) {
        // Constructing a typed array requires new (Value.newInstance), not a plain call.
        Value u8 = uint8ArrayCtor.newInstance(data.length);
        for (int i = 0; i < data.length; i++) {
            u8.setArrayElement(i, data[i] & 0xFF);
        }
        return u8;
    }

    /**
     * Converts a GraalVM Value (Uint8Array or array of numbers 0-255) into a Java byte array.
     * @param value the JS value to convert
     * @return byte array (unsigned values preserved)
     * @throws IllegalArgumentException if the value is not a byte-like array
     */
    public static byte[] toByteArray(Value value) {
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException(
                    "data must not be null (pass a Uint8Array or an array of numbers 0-255)");
        }
        if (!value.hasArrayElements()) {
            throw new IllegalArgumentException("data must be an array (Uint8Array or array of numbers 0-255)");
        }
        long size = value.getArraySize();
        if (size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("data too large: " + size + " elements");
        }
        byte[] data = new byte[(int) size];
        for (int i = 0; i < data.length; i++) {
            Value el = value.getArrayElement(i);
            if (el == null || !el.isNumber()) {
                throw new IllegalArgumentException("element " + i + " of data is not a number ("
                        + describeElement(el) + ") - data must be a Uint8Array or an array of"
                        + " numbers 0-255; a nested array (a block list) is not accepted");
            }
            data[i] = (byte) (el.asInt() & 0xFF);
        }
        return data;
    }

    /**
     * Names what arrived where a number was expected, so the type hint says what the caller
     * actually passed instead of only blaming "element 0". Especially the nested-array case
     * (a block list) gets its own wording, because that is what callers pass when they try to
     * stream data into an API that takes one byte array.
     */
    private static String describeElement(Value el) {
        if (el == null || el.isNull()) {
            return "null element";
        }
        if (el.isString()) {
            return "a string";
        }
        if (el.hasArrayElements()) {
            return "a nested array";
        }
        if (el.isBoolean()) {
            return "a boolean";
        }
        return "a non-numeric value";
    }

    // ---------------------------------------------------------------
    // Nested ProxyObject implementation
    // ---------------------------------------------------------------

    /**
     * A ProxyObject implementation that recursively converts nested
     * Maps, Lists, and arrays so that JavaScript can traverse them.
     */
    public static class NestedProxyObject implements ProxyObject {
        private final Map<String, Object> data;

        public NestedProxyObject(Map<String, Object> data) {
            this.data = data;
        }

        @Override
        public Object getMember(String key) {
            Object val = data.get(key);
            return convertToInterop(val);
        }

        @Override
        public Object getMemberKeys() {
            // Deliberately a ProxyArray and not a String[]/Collection: the polyglot layer wraps
            // a Java array (or collection) as a *host* object, and a sandbox whose HostAccess
            // denies array access then rejects getMemberKeys() as a whole. That breaks
            // Object.keys(), for-in and JSON.stringify() on every proxy object - which is the
            // common way an LLM inspects structured results. A ProxyArray has native interop
            // array semantics and is accepted independently of the HostAccess policy.
            return new KeyArray(data.keySet().toArray(new String[0]));
        }

        @Override
        public boolean hasMember(String key) {
            return data.containsKey(key);
        }

        @Override
        public void putMember(String key, Value value) {
            throw new UnsupportedOperationException("Read-only proxy");
        }

        @Override
        public boolean removeMember(String key) {
            throw new UnsupportedOperationException("Read-only proxy");
        }

        private Object convertToInterop(Object val) {
            if (val == null) {
                return null;
            }
            if (val instanceof Map<?, ?> mapVal) {
                Map<String, Object> strMap = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : mapVal.entrySet()) {
                    strMap.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                return new NestedProxyObject(strMap);
            }
            if (val instanceof List<?> listVal) {
                return new NestedProxyArray(listVal);
            }
            if (val instanceof Object[] arrVal) {
                return new NestedProxyArray(Arrays.asList(arrVal));
            }
            return val;
        }
    }

    // ---------------------------------------------------------------
    // Key list with native interop array semantics
    // ---------------------------------------------------------------

    /**
     * Immutable list of member keys used by {@link NestedProxyObject#getMemberKeys()}.
     *
     * @see NestedProxyObject#getMemberKeys() for why this is not a plain {@code String[]}
     */
    private static final class KeyArray implements ProxyArray {

        private final String[] keys;

        KeyArray(String[] keys) {
            this.keys = keys;
        }

        @Override
        public Object get(long index) {
            return index >= 0 && index < keys.length ? keys[(int) index] : null;
        }

        @Override
        public long getSize() {
            return keys.length;
        }

        @Override
        public void set(long index, Value value) {
            throw new UnsupportedOperationException("Read-only key list");
        }
    }

    // ---------------------------------------------------------------
    // Nested ProxyArray implementation
    // ---------------------------------------------------------------

    /**
     * A ProxyArray implementation for recursive JS array access.
     */
    public static class NestedProxyArray implements ProxyArray {
        private final List<Object> data;

        @SuppressWarnings("unchecked")
        public NestedProxyArray(List<?> data) {
            this.data = (List<Object>) data;
        }

        @Override
        public Object get(long index) {
            if (index < 0 || index >= data.size()) {
                return null;
            }
            Object val = data.get((int) index);
            // Recursively convert nested structures
            if (val instanceof Map<?, ?> mapVal) {
                Map<String, Object> strMap = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : mapVal.entrySet()) {
                    strMap.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                return new NestedProxyObject(strMap);
            }
            if (val instanceof List<?> listVal) {
                return new NestedProxyArray(listVal);
            }
            if (val instanceof Object[] arrVal) {
                return new NestedProxyArray(Arrays.asList(arrVal));
            }
            return val;
        }

        @Override
        public long getSize() {
            return data.size();
        }

        @Override
        public void set(long index, Value value) {
            throw new UnsupportedOperationException("Read-only proxy");
        }
    }

    // ---------------------------------------------------------------
    // "Unknown identifier" hints (did you mean)
    // ---------------------------------------------------------------

    /** Matches the "Unknown identifier: x" part of a polyglot member-access failure. */
    private static final Pattern UNKNOWN_IDENTIFIER =
            Pattern.compile("Unknown identifier: ([A-Za-z_$][\\w$]*)");

    /** Closer hints for members that are near misses of a different, real member. */
    private static final Map<String, String> MEMBER_HINTS = Map.of(
            "fs.readBytes", "a single read is capped at 1 MiB - pass offset and length or use"
                    + " fs.createBlockReader for a whole stream");

    /**
     * Builds a "Did you mean ...?" hint for an {@code Unknown identifier: x} failure, searched
     * over the member names of the wired sandbox modules (module name -> member names).
     * <p>
     * The polyglot layer answers a failed member access on a module proxy with a bare
     * "Unknown identifier: read" - it names neither the module nor the close matches. The hint
     * adds both, with qualified candidates ("fs.readFile"), because the exception does not say
     * which object the failed access belonged to. A candidate matches when its edit distance to
     * the unknown name is at most 2 or when one is a prefix of the other (at least 3 characters),
     * so {@code read} finds {@code readFile}/{@code readLines}/{@code readBytes}.
     * </p>
     * @param message the (polyglot) exception message to inspect, may be null
     * @param moduleMembers member names of the wired modules, module name -> member names
     * @return the hint, or "" if the message does not name an unknown identifier or no module
     *         member is close enough
     */
    public static String unknownIdentifierHint(String message,
                                               Map<String, List<String>> moduleMembers) {
        if (message == null || moduleMembers == null) {
            return "";
        }
        Matcher m = UNKNOWN_IDENTIFIER.matcher(message);
        if (!m.find()) {
            return "";
        }
        String name = m.group(1);
        List<String> qualified = new ArrayList<>();
        List<Integer> distances = new ArrayList<>();
        List<String> modules = new ArrayList<>();
        for (Map.Entry<String, List<String>> module : moduleMembers.entrySet()) {
            for (String member : module.getValue()) {
                int distance = editDistance(name, member);
                boolean near = distance <= 2
                        || (member.startsWith(name) && name.length() >= 3)
                        || (name.startsWith(member) && member.length() >= 3);
                if (near) {
                    qualified.add(module.getKey() + "." + member);
                    distances.add(distance);
                    modules.add(module.getKey());
                }
            }
        }
        if (qualified.isEmpty()) {
            return "";
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < qualified.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.<Integer>comparingInt(distances::get).thenComparing(qualified::get));

        StringBuilder sb = new StringBuilder("Did you mean ");
        Set<String> helpModules = new TreeSet<>();
        for (int i = 0; i < order.size() && i < 4; i++) {
            int idx = order.get(i);
            if (i > 0) {
                sb.append(i == Math.min(4, order.size()) - 1 ? " or " : ", ");
            }
            String candidate = qualified.get(idx);
            sb.append('\'').append(candidate).append('\'');
            String hint = MEMBER_HINTS.get(candidate);
            if (hint != null) {
                sb.append(" (").append(hint).append(')');
            }
            if (moduleMembers.getOrDefault(modules.get(idx), List.of()).contains("help")) {
                helpModules.add(modules.get(idx));
            }
        }
        sb.append('?');
        for (String helpModule : helpModules) {
            sb.append(" See ").append(helpModule).append(".help().");
        }
        return sb.toString();
    }

    /** Levenshtein edit distance of two names, for the did-you-mean suggestions. */
    static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                curr[j] = Math.min(substitution, Math.min(prev[j] + 1, curr[j - 1] + 1));
            }
            int[] swap = prev;
            prev = curr;
            curr = swap;
        }
        return prev[b.length()];
    }
}
