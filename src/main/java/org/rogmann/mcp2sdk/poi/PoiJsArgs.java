package org.rogmann.mcp2sdk.poi;

import org.graalvm.polyglot.Value;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Argument conversion shared by the POI JavaScript bridges ({@code poi}, {@code docx}, {@code pptx}).
 * <p>
 * Every conversion names the function, the argument and the valid range. GraalVM's own message
 * ({@code Cannot convert '9.5' … Invalid or lossy primitive coercion}) names none of the three,
 * and a raw {@code args[5].asDouble()} on a short call ends in an
 * {@link ArrayIndexOutOfBoundsException} instead of an explanation. Both error classes were
 * measured in the docx bridge (plan docs/js/docx.md, L3/A-04) and the same shape exists in the
 * other two bridges; the helpers are here so that a namespace cannot forget them again.
 * </p>
 *
 * <h3>Two ways to read an argument</h3>
 * <ul>
 *   <li>{@code stringArg("setRunText", "text", arg(args, 1))} — the explicit form used by the
 *       docx bridge, whose call sites predate this class.</li>
 *   <li>{@code bind("addTextBox", args).string("text", 1)} — the {@link Call} form, which also
 *       knows the arity and reports a missing argument instead of throwing
 *       {@code ArrayIndexOutOfBoundsException}.</li>
 * </ul>
 * Both produce the same message:
 * {@code docx.setRunText: argument 'text' must be a string (quote the value), but was: '42'}.
 *
 * <h3>Errors</h3>
 * <p>
 * Everything thrown here is a {@link PoiUserRuntimeException}: user-facing text, no stack trace,
 * no host paths. A {@link ClassCastException} from GraalVM would surface the Java side instead.
 * </p>
 */
public final class PoiJsArgs {

    /** Helper set of the xlsx namespace (its JS name is {@code poi}). */
    public static final PoiJsArgs XLSX = new PoiJsArgs("poi");

    /** Helper set of the Word namespace. */
    public static final PoiJsArgs DOCX = new PoiJsArgs("docx");

    /** Helper set of the PowerPoint namespace. */
    public static final PoiJsArgs PPTX = new PoiJsArgs("pptx");

    /** Longest guest text an error message quotes back; see {@link #describe(Value)}. */
    private static final int MAX_DESCRIBED_TEXT = 120;

    private final String namespace;

    private PoiJsArgs(String namespace) {
        this.namespace = namespace;
    }

    /** The help call named at the end of every error message, e.g. {@code "docx.help()"}. */
    public String helpCall() {
        return namespace + ".help()";
    }

    /**
     * The "Arguments and errors" paragraph of a help text. All three POI help texts quote this
     * block so that the rules - and the shape of an error message - read the same everywhere.
     * @return help text fragment, ending with a blank line
     */
    public String helpNotes() {
        return """
                --- Arguments and errors ---
                A wrong or missing argument always names the function, the argument and its
                valid range, e.g.:
                  %1$s.setCellValue: argument 'row' must be an integer index (0-based),
                  but was: '1.5'. See %1$s.help().
                - indices (row, column, sheet, table) are 0-based whole numbers; 1.5 is refused
                - text arguments must be quoted: a bare number is an error, not a silent
                  conversion to "42"
                - flags must be true or false, not 0, 1 or "yes"
                - numbers are checked against the range named in the message (NaN, Infinity and
                  values far outside a document are errors)
                - colors are 6 hex digits, with or without a leading '#' ("FF0000", "#FF0000")
                - an unknown option key is an error, so a typo cannot pass silently

                """.formatted(namespace);
    }

    /**
     * Binds one call: the function name for the error text and its argument array.
     * @param fn public function name, without the namespace
     * @param args the argument array of the {@link org.graalvm.polyglot.proxy.ProxyExecutable}
     * @return a call view, cheap to create
     */
    public Call bind(String fn, Value[] args) {
        return new Call(this, fn, args);
    }

    // ========================================================================
    // Explicit form (function name and value passed by the caller)
    // ========================================================================

    /**
     * The argument at {@code index}, or null when the caller passed fewer arguments.
     * The {@code …Arg} helpers turn that null into a proper message.
     */
    public static Value arg(Value[] args, int index) {
        return args != null && index < args.length ? args[index] : null;
    }

    /** The error every failed conversion throws - one text for all three namespaces. */
    public PoiUserRuntimeException badArg(String fn, String name, Value value, String expected) {
        return new PoiUserRuntimeException(namespace + "." + fn + ": argument '" + name
                + "' must be " + expected + ", but was: " + describe(value)
                + ". See " + helpCall() + ".");
    }

    /** Error for an argument the caller did not pass at all (arity check). */
    public PoiUserRuntimeException missingArg(String fn, String name, String expected) {
        return new PoiUserRuntimeException(namespace + "." + fn + ": missing argument '" + name
                + "' (expected " + expected + "). See " + helpCall() + ".");
    }

    /**
     * How a value is quoted inside an error message. Never throws, always one line.
     * <p>
     * Strings are read through {@link Value#asString()}: {@code String.valueOf(value)} hands out
     * the internal {@code TruffleString} object for a guest string, so the message that exists
     * to show the caller their value would print
     * {@code com.oracle.truffle.api.strings.TruffleString} (measured live on all three
     * namespaces). Line breaks are escaped and long texts are cut - a value of the caller must
     * neither split the message into several lines nor echo a megabyte through it.
     * </p>
     * @param value the guest value to render
     * @return a short, single-line, quoted rendering
     */
    public static String describe(Value value) {
        if (value == null || value.isNull()) {
            return "null/undefined";
        }
        String rendered;
        try {
            rendered = value.isString() ? "'" + value.asString() + "'" : "'" + value + "'";
        } catch (RuntimeException e) {
            return "<unprintable value>";
        }
        return shorten(oneLine(rendered));
    }

    /** Escapes line breaks and replaces other control characters by spaces. */
    private static String oneLine(String text) {
        boolean needsWork = false;
        for (int i = 0; i < text.length() && !needsWork; i++) {
            needsWork = isControlChar(text.charAt(i));
        }
        if (!needsWork) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else {
                sb.append(isControlChar(c) ? ' ' : c);
            }
        }
        return sb.toString();
    }

    /** Line breaks and control characters are the ones that can break a one-line message. */
    private static boolean isControlChar(char c) {
        return c < 0x20 || c == 0x7f;
    }

    /** Cuts a rendered value to {@link #MAX_DESCRIBED_TEXT}, keeping the closing quote. */
    private static String shorten(String rendered) {
        if (rendered.length() <= MAX_DESCRIBED_TEXT) {
            return rendered;
        }
        String body = rendered.substring(0, MAX_DESCRIBED_TEXT - 2);
        return body + (rendered.endsWith("'") ? "…'" : "…");
    }

    /** A string. A number where text is expected is shown, not silently converted. */
    public String stringArg(String fn, String name, Value value) {
        if (value == null || value.isNull()) {
            throw badArg(fn, name, value, "a string");
        }
        if (value.isString()) {
            return value.asString();
        }
        if (value.isNumber() || value.isBoolean()) {
            // A number where text is expected is a mistake worth showing, not a silent toString()
            throw badArg(fn, name, value, "a string (quote the value)");
        }
        throw badArg(fn, name, value, "a string");
    }

    /**
     * A string that may be left out: null/undefined becomes the empty string, a number or
     * boolean is read as text (callers build texts from table data).
     */
    public String optionalString(String fn, String name, Value value) {
        if (value == null || value.isNull()) {
            return "";
        }
        if (value.isString()) {
            return value.asString();
        }
        if (value.isNumber() || value.isBoolean()) {
            return value.toString();
        }
        throw badArg(fn, name, value, "a string");
    }

    /** A real boolean - {@code 0} and {@code ""} are not booleans here. */
    public boolean boolArg(String fn, String name, Value value) {
        if (value != null && value.isBoolean()) {
            return value.asBoolean();
        }
        throw badArg(fn, name, value, "true or false");
    }

    /**
     * A 0-based index. Strings that hold an integer are accepted (callers build indices from
     * table data), anything lossy is not - and neither is a negative value, which POI would
     * either answer with an {@code IllegalArgumentException} or, worse, by reading nothing.
     */
    public int indexArg(String fn, String name, Value value) {
        String expectation = "an integer index (0-based)";
        if (value == null || value.isNull()) {
            throw badArg(fn, name, value, expectation);
        }
        int parsed;
        if (value.isNumber()) {
            if (!value.fitsInInt()) {
                throw badArg(fn, name, value,
                        expectation + " - fractional numbers such as 1.5 are rejected");
            }
            parsed = value.asInt();
        } else if (value.isString()) {
            try {
                parsed = Integer.parseInt(value.asString().trim());
            } catch (NumberFormatException e) {
                throw badArg(fn, name, value, expectation);
            }
        } else {
            throw badArg(fn, name, value, expectation);
        }
        if (parsed < 0) {
            throw badArg(fn, name, value, expectation + ", not negative");
        }
        return parsed;
    }

    /** A number within a range; fractional values are kept, strings are parsed (comma counts). */
    public double numberArg(String fn, String name, Value value, double min, double max) {
        String range = "a number between " + min + " and " + max + " (half steps are kept)";
        double parsed = numberFrom(fn, name, value, range);
        if (parsed < min || parsed > max) {
            throw badArg(fn, name, value, range);
        }
        return parsed;
    }

    /** A whole number within a range, e.g. a font size that the format stores without fractions. */
    public int intArg(String fn, String name, Value value, int min, int max) {
        String range = "a whole number between " + min + " and " + max;
        double parsed = numberFrom(fn, name, value, range);
        if (parsed != Math.rint(parsed)) {
            throw badArg(fn, name, value, range + " (fractional sizes are not stored by the format)");
        }
        if (parsed < min || parsed > max) {
            throw badArg(fn, name, value, range);
        }
        return (int) parsed;
    }

    /** Shared numeric read of {@link #numberArg} and {@link #intArg}. */
    private double numberFrom(String fn, String name, Value value, String range) {
        if (value == null || value.isNull()) {
            throw badArg(fn, name, value, range);
        }
        double parsed;
        if (value.isNumber()) {
            parsed = value.asDouble();
        } else if (value.isString()) {
            try {
                parsed = Double.parseDouble(value.asString().trim().replace(',', '.'));
            } catch (NumberFormatException e) {
                throw badArg(fn, name, value, range);
            }
        } else {
            throw badArg(fn, name, value, range);
        }
        if (Double.isNaN(parsed) || Double.isInfinite(parsed)) {
            throw badArg(fn, name, value, range);
        }
        return parsed;
    }

    /**
     * Reads one boolean from an options object (or accepts a bare boolean for the
     * single-option case). Unknown keys are an error, so a typo does not pass silently.
     */
    public boolean optionBool(String fn, Value options, String key, Set<String> knownKeys,
                              boolean fallback) {
        if (options == null || options.isNull()) {
            return fallback;
        }
        if (options.isBoolean()) {
            return key.equals("props") ? options.asBoolean() : fallback;
        }
        if (!options.hasMembers()) {
            throw badArg(fn, "options", options, "an object with any of " + knownKeys + " (or omitted)");
        }
        requireKnownKeys(fn, options, knownKeys);
        if (!options.hasMember(key)) {
            return fallback;
        }
        Value v = options.getMember(key);
        if (v == null || v.isNull()) {
            return fallback;
        }
        if (v.isBoolean()) {
            return v.asBoolean();
        }
        throw badArg(fn, "options." + key, v, "true or false");
    }

    /**
     * Rejects option keys that no caller reads. A misspelled option would otherwise be a
     * silently ignored argument.
     */
    private void requireKnownKeys(String fn, Value options, Set<String> knownKeys) {
        for (String member : options.getMemberKeys()) {
            if (!knownKeys.contains(member)) {
                throw new PoiUserRuntimeException(namespace + "." + fn + ": unknown option '" + member
                        + "' (known: " + new TreeSet<>(knownKeys) + "). See " + helpCall() + ".");
            }
        }
    }

    /** An array of strings; every element goes through the string rules. */
    public String[] stringArrayArg(String fn, String name, Value value) {
        if (value == null || value.isNull() || !value.hasArrayElements()) {
            throw badArg(fn, name, value, "an array of strings");
        }
        int len = (int) value.getArraySize();
        String[] out = new String[len];
        for (int i = 0; i < len; i++) {
            out[i] = optionalString(fn, name + "[" + i + "]", value.getArrayElement(i));
        }
        return out;
    }

    /**
     * Converts a JS options object into a plain Map for the toolbox. The toolbox validates the
     * keys (an unknown option is an error there), so the bridge only flattens values.
     */
    public Map<String, Object> optionsArg(String fn, Value value) {
        Map<String, Object> options = optionalOptionsArg(fn, value);
        return options != null ? options : Map.of();
    }

    /** Like {@link #optionsArg}, but an absent argument stays null instead of an empty map. */
    public Map<String, Object> optionalOptionsArg(String fn, Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.hasMembers()) {
            throw badArg(fn, "options", value, "an object ({key: value}) or omitted");
        }
        Map<String, Object> options = new LinkedHashMap<>();
        for (String key : value.getMemberKeys()) {
            Object plain = toPlain(value.getMember(key));
            if (plain != null) {
                options.put(key, plain);
            }
        }
        return options;
    }

    /** Flattens a JS value into String/Double/Boolean/List, refusing host objects. */
    public static Object toPlain(Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isNumber()) {
            return value.asDouble();
        }
        if (value.isString()) {
            return value.asString();
        }
        if (value.hasArrayElements()) {
            List<Object> list = new ArrayList<>();
            for (int i = 0; i < value.getArraySize(); i++) {
                list.add(toPlain(value.getArrayElement(i)));
            }
            return list;
        }
        throw new PoiUserRuntimeException("Options may hold strings, numbers, booleans and arrays"
                + " of them, but was: " + describe(value));
    }

    // ========================================================================
    // Call form (knows the arity)
    // ========================================================================

    /**
     * One call of one function: reads the arguments by position and names function, argument
     * and expected type when one of them is wrong - or missing.
     */
    public static final class Call {

        private final PoiJsArgs args;
        private final String fn;
        private final Value[] raw;

        private Call(PoiJsArgs args, String fn, Value[] raw) {
            this.args = args;
            this.fn = fn;
            this.raw = raw != null ? raw : new Value[0];
        }

        /** The raw value of a required argument (never null). */
        public Value value(String name, int index, String expected) {
            if (absent(index)) {
                throw args.missingArg(fn, name, expected);
            }
            return raw[index];
        }

        /** The raw value of an optional argument, null when it was not passed. */
        public Value optionalValue(int index) {
            return absent(index) ? null : raw[index];
        }

        public String string(String name, int index) {
            return args.stringArg(fn, name, value(name, index, "a string"));
        }

        /** A string the caller may leave out; absent and null both become the empty string. */
        public String optionalString(String name, int index) {
            return args.optionalString(fn, name, optionalValue(index));
        }

        public boolean bool(String name, int index) {
            return args.boolArg(fn, name, value(name, index, "true or false"));
        }

        public int index(String name, int index) {
            return args.indexArg(fn, name, value(name, index, "an integer index (0-based)"));
        }

        /** A 0-based index the caller may leave out, e.g. a start row that defaults to 0. */
        public int optionalIndex(String name, int index, int fallback) {
            Value v = optionalValue(index);
            return v == null || v.isNull() ? fallback : args.indexArg(fn, name, v);
        }

        public double number(String name, int index, double min, double max) {
            return args.numberArg(fn, name, value(name, index,
                    "a number between " + min + " and " + max), min, max);
        }

        public int wholeNumber(String name, int index, int min, int max) {
            return args.intArg(fn, name, value(name, index,
                    "a whole number between " + min + " and " + max), min, max);
        }

        public String[] stringArray(String name, int index) {
            return args.stringArrayArg(fn, name, value(name, index, "an array of strings"));
        }

        public Map<String, Object> optionalOptions(int index) {
            return args.optionalOptionsArg(fn, optionalValue(index));
        }

        /** One boolean out of an options object passed at {@code index}. */
        public boolean optionBool(int index, String key, Set<String> knownKeys, boolean fallback) {
            return args.optionBool(fn, optionalValue(index), key, knownKeys, fallback);
        }

        private boolean absent(int index) {
            return index >= raw.length || raw[index] == null;
        }
    }
}
