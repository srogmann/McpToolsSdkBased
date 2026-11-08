package org.rogmann.mcp2sdk.pdfedit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * One operation of an op list: its name, its parameters and the strict readers for those
 * parameters.
 * <p>
 * Every op is written the way JavaScript writes data - {@code {op:'page.add', size:'A4'}} - and
 * is validated before anything is written: an unknown parameter is an error that names the
 * supported ones, a missing one is an error that shows the shape. The index of the op travels in
 * every message ({@code ops[3] (page.add): …}), because an op list of fifty entries is useless
 * when the error does not say which entry it means.
 * </p>
 */
public final class PdfEditOp {

    /** Position in the op list, 0-based - only used in messages. */
    private final int index;

    /** The op name, e.g. {@code "page.add"}. */
    private final String name;

    /** The parameters (already converted to Java structures). */
    private final Map<String, Object> params;

    private PdfEditOp(int index, String name, Map<String, Object> params) {
        this.index = index;
        this.name = name;
        this.params = params;
    }

    /**
     * Parses the op list of a call.
     * @param ops the value the caller passed (a list of objects), may be {@code null}
     * @return the ops in call order
     * @throws PdfEditException with code {@code usage} when the list has the wrong shape
     */
    public static List<PdfEditOp> parse(Object ops) {
        if (ops == null) {
            return List.of();
        }
        if (!(ops instanceof List<?> list)) {
            throw PdfEditException.usage("ops is " + describe(ops),
                    "ops is an array of operations, e.g. [{op:'page.add', size:'A4'}]");
        }
        if (list.size() > PdfEditLimits.MAX_OPS) {
            throw new PdfEditException("limit", list.size() + " ops in one call",
                    "at most " + PdfEditLimits.MAX_OPS + " ops per call");
        }
        List<PdfEditOp> result = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (!(item instanceof Map<?, ?> map)) {
                throw PdfEditException.usage("ops[" + i + "] is " + describe(item),
                        "each op is an object with an 'op' member, e.g. {op:'doc.info', set:{…}}");
            }
            Map<String, Object> params = new LinkedHashMap<>();
            String name = null;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if ("op".equals(key)) {
                    if (!(entry.getValue() instanceof String text)) {
                        throw PdfEditException.usage("ops[" + i + "].op is "
                                + describe(entry.getValue()), "op is a string, e.g. 'page.add'");
                    }
                    name = text.trim();
                } else {
                    params.put(key, entry.getValue());
                }
            }
            if (name == null || name.isEmpty()) {
                throw PdfEditException.usage("ops[" + i + "] has no 'op'",
                        "each op names itself, e.g. {op:'page.add', size:'A4'} -"
                                + " pdfedit.help() lists the operations");
            }
            result.add(new PdfEditOp(i, name, params));
        }
        return result;
    }

    /** @return the 0-based position of this op in the call */
    public int index() {
        return index;
    }

    /** @return the op name, e.g. {@code "content.append"} */
    public String name() {
        return name;
    }

    /** @return the raw parameters */
    public Map<String, Object> params() {
        return params;
    }

    /**
     * Prefix of every error message of this op: {@code "ops[3] (page.add)"}.
     * @return the prefix
     */
    public String where() {
        return "ops[" + index + "] (" + name + ")";
    }

    /**
     * Rejects every parameter that is not in the given list.
     * @param allowed the parameter names this op understands
     * @throws PdfEditException with code {@code usage} naming the unknown and the supported ones
     */
    public void allow(String... allowed) {
        Set<String> known = new LinkedHashSet<>(Arrays.asList(allowed));
        Set<String> unknown = new TreeSet<>(params.keySet());
        unknown.removeAll(known);
        if (!unknown.isEmpty()) {
            throw PdfEditException.usage(where() + ": unknown parameter(s) " + unknown,
                    "supported: " + known + " - pdfedit.help() shows an example per op");
        }
    }

    /**
     * Reads a required page number (1-based, as {@code pdf.pages()} prints it).
     * @param key the parameter name
     * @return the page number
     */
    public int page(String key) {
        Integer value = intOrNull(key);
        if (value == null) {
            throw PdfEditException.usage(where() + ": '" + key + "' is missing",
                    "'" + key + "' is a 1-based page number, as pdf.pages() prints it");
        }
        if (value < 1) {
            throw new PdfEditException("out-of-range", where() + ": " + key + " is " + value,
                    "pages are counted from 1 (pdf.pages() shows the numbers)");
        }
        return value;
    }

    /**
     * Reads an optional integer parameter.
     * @param key the parameter name
     * @return the value or {@code null}
     */
    public Integer intOrNull(String key) {
        Object value = params.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            if (n.doubleValue() != Math.rint(n.doubleValue())) {
                throw PdfEditException.usage(where() + ": '" + key + "' is " + n,
                        "'" + key + "' is a whole number");
            }
            return n.intValue();
        }
        throw PdfEditException.usage(where() + ": '" + key + "' is " + describe(value),
                "'" + key + "' is a whole number");
    }

    /**
     * Reads an optional number with fractions.
     * @param key the parameter name
     * @return the value or {@code null}
     */
    public Double doubleOrNull(String key) {
        Object value = params.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        throw PdfEditException.usage(where() + ": '" + key + "' is " + describe(value),
                "'" + key + "' is a number");
    }

    /**
     * Reads an optional boolean parameter.
     * @param key the parameter name
     * @param fallback the value used when the parameter is absent
     * @return the value
     */
    public boolean bool(String key, boolean fallback) {
        Object value = params.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        throw PdfEditException.usage(where() + ": '" + key + "' is " + describe(value),
                "'" + key + "' is true or false");
    }

    /**
     * Reads an optional string parameter.
     * @param key the parameter name
     * @return the value or {@code null}
     */
    public String stringOrNull(String key) {
        Object value = params.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        throw PdfEditException.usage(where() + ": '" + key + "' is " + describe(value),
                "'" + key + "' is a string");
    }

    /**
     * Reads a required string parameter.
     * @param key the parameter name
     * @return the value
     */
    public String string(String key) {
        String value = stringOrNull(key);
        if (value == null) {
            throw PdfEditException.usage(where() + ": '" + key + "' is missing",
                    "'" + key + "' is a string");
        }
        return value;
    }

    /**
     * Reads a required list parameter.
     * @param key the parameter name
     * @return the list (never {@code null})
     */
    public List<?> list(String key) {
        Object value = params.get(key);
        if (value == null) {
            throw PdfEditException.usage(where() + ": '" + key + "' is missing",
                    "'" + key + "' is an array");
        }
        if (value instanceof List<?> items) {
            return items;
        }
        throw PdfEditException.usage(where() + ": '" + key + "' is " + describe(value),
                "'" + key + "' is an array");
    }

    /**
     * Reads a required object parameter.
     * @param key the parameter name
     * @return the map (never {@code null})
     */
    public Map<String, Object> map(String key) {
        Object value = params.get(key);
        if (value == null) {
            throw PdfEditException.usage(where() + ": '" + key + "' is missing",
                    "'" + key + "' is an object");
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }
        throw PdfEditException.usage(where() + ": '" + key + "' is " + describe(value),
                "'" + key + "' is an object");
    }

    /**
     * Reads an optional object parameter.
     * @param key the parameter name
     * @return the map or {@code null}
     */
    public Map<String, Object> mapOrNull(String key) {
        return params.get(key) == null ? null : map(key);
    }

    /**
     * Reads raw bytes: the bridge converts a {@code Uint8Array} to a {@code byte[]} already, so
     * this only has to reject everything else - with the hint that a text form exists.
     * @param key the parameter name
     * @return the bytes
     */
    public byte[] bytes(String key) {
        Object value = params.get(key);
        if (value == null) {
            throw PdfEditException.usage(where() + ": '" + key + "' is missing",
                    "'" + key + "' is a Uint8Array of bytes");
        }
        if (value instanceof byte[] data) {
            return data;
        }
        throw PdfEditException.usage(where() + ": '" + key + "' is " + describe(value),
                "'" + key + "' is a Uint8Array (or an array of numbers 0-255)");
    }

    /**
     * Names the type of a value for an error message.
     * @param value the value
     * @return a short description
     */
    static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return "'" + text + "'";
        }
        return value.getClass().getSimpleName() + " " + value;
    }
}
