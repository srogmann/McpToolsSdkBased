package org.rogmann.mcp2sdk.js;

import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyArray;
import org.graalvm.polyglot.proxy.ProxyObject;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
                throw new IllegalArgumentException("element " + i + " of data is not a number");
            }
            data[i] = (byte) (el.asInt() & 0xFF);
        }
        return data;
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
            return data.keySet().toArray(new String[0]);
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
}
