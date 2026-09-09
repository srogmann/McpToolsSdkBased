package org.rogmann.mcp2sdk.js;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyArray;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bridge between GraalVM JavaScript and {@link JsSearch}.
 * <p>
 * Creates a {@link ProxyObject} namespace {@code "search"} exposing grep-like search over
 * the controlled project file system (plain files, directories, ZIP-like archives, tar and
 * gzip streams). The resulting object is intended to be bound as {@code "search"} in the
 * JavaScript bindings and is also reachable as {@code require("search")}.
 * </p>
 *
 * <h3>Usage in JavaScript</h3>
 * <pre>{@code
 * var out    = search.grep("TODO", "src", { recursive: true });
 * var result = search.find(/password/i, "config", { recursive: true, archives: true });
 * var paths  = search.files("servlet", "example.ear#admin.war#WEB-INF/web.xml");
 * var hits   = search.grep(new Uint8Array([0x4d, 0x5a]), "boot.bin",
 *                          { binary: "bytes", mode: "offsets" });
 * var hex    = search.hexgrep("4d5a .. 9000", "image.bin", { mode: "counts" });
 * console.log(search.help());
 * }</pre>
 *
 * <h3>Pattern handling</h3>
 * <p>
 * The specification requires JavaScript regular expression semantics, so matching is
 * delegated to a real JS {@code RegExp}: {@link #wireApi(Value)} evaluates a small helper
 * object once per call inside the JavaScript context (see {@link RegExpSupport}), and
 * {@link #toPattern(RegExpSupport, Value)} builds the {@link JsSearch.SearchPattern} either
 * from a string pattern (plus {@code flags} / {@code caseInsensitive}), from a RegExp object
 * or from a byte array ({@code Uint8Array}, byte search only). The engine asks the
 * {@link JsSearch.PatternFactory} of this bridge to compile the expression, which is where
 * the flags of the byte scan ({@code g} and {@code d}) come in. For text matching the flags
 * {@code g} and {@code y} never change the result: a span lookup sets {@code lastIndex}
 * explicitly before every {@code exec} (the same applies to RegExp items of
 * include/exclude).
 * </p>
 *
 * <h3>Why {@link RegExpSupport} instead of {@code Value.isRegExp()}</h3>
 * <p>
 * There is no {@code isRegExp()} on {@link Value} (the polyglot API has no regular-expression
 * type), so detection is done where it is actually defined: inside the guest language, with
 * {@code Object.prototype.toString.call(v) === '[object RegExp]'}. That test is realm-safe and
 * also reports {@code true} for subclasses of {@code RegExp}. Should the helper be
 * unavailable, {@link #looksLikeRegExp(Value)} provides a conservative fallback based on the
 * metaobject name and RegExp-like members.
 * </p>
 */
public class JsSearchBridge implements JsModuleInterface {

    /**
     * Source of the per-call helper object that compiles {@code RegExp}s inside the JavaScript
     * context and recognises them, so patterns keep guest-side (ECMAScript) semantics.
     */
    private static final String REGEXP_HELPERS_SOURCE =
            "(function searchRegExpHelpers() {"
            + "  var tag = Object.prototype.toString;"
            + "  return {"
            + "    create: function (source, flags) { return new RegExp(source, flags); },"
            + "    isRegExp: function (value) { return tag.call(value) === '[object RegExp]'; },"
            + "    typeName: function (value) {"
            + "      var s = tag.call(value); return s.slice(8, s.length - 1);"
            + "    }"
            + "  };"
            + "})";

    @Override
    public String getNamespace() {
        return "search";
    }

    @Override
    public String getSummary() {
        return "`search.help()` explains grep-like search (files, directories, archives)";
    }

    @Override
    public String getHelpTip() {
        return "search.help() (grep/search)";
    }

    @Override
    public AutoCloseable wireApi(Value jsBindings) {
        jsBindings.putMember("search",
                createSearchNamespace(RegExpSupport.create(jsBindings.getContext())));
        return null;
    }

    /**
     * Creates a ProxyObject representing the search namespace for JavaScript.
     *
     * @param regexps the context's RegExp helpers (creation and RegExp detection)
     * @return ProxyObject with the search methods
     */
    public static ProxyObject createSearchNamespace(RegExpSupport regexps) {
        final RegExpSupport re = regexps != null ? regexps : new RegExpSupport(null, null, null);
        final JsSearch.PatternFactory factory = spanFactory(re);
        Map<String, Object> methods = new HashMap<>();

        methods.put("help", (ProxyExecutable) args -> JsSearch.help());

        methods.put("grep", (ProxyExecutable) args -> {
            requireArgs(args, 2, "grep(pattern, target[, options])");
            Map<String, Object> options = toOptionMap(re, args, 2);
            JsSearch.SearchPattern pattern = toPattern(re, factory, args[0]);
            return JsSearch.grep(pattern, toTarget(args[1]), options);
        });

        methods.put("find", (ProxyExecutable) args -> {
            requireArgs(args, 2, "find(pattern, target[, options])");
            Map<String, Object> options = toOptionMap(re, args, 2);
            JsSearch.SearchPattern pattern = toPattern(re, factory, args[0]);
            return GraalProxies.toProxyObject(JsSearch.find(pattern, toTarget(args[1]), options));
        });

        methods.put("files", (ProxyExecutable) args -> {
            requireArgs(args, 2, "files(pattern, target[, options])");
            Map<String, Object> options = toOptionMap(re, args, 2);
            JsSearch.SearchPattern pattern = toPattern(re, factory, args[0]);
            return createStringProxyArray(JsSearch.files(pattern, toTarget(args[1]), options));
        });

        // Hex is never guessed: a string that looks like hex is still regex source in grep(),
        // so the hex form lives in its own function (spec 18.3).
        methods.put("hexgrep", (ProxyExecutable) args -> {
            requireArgs(args, 2, "hexgrep(hexPattern, target[, options])");
            Map<String, Object> options = toOptionMap(re, args, 2);
            return JsSearch.hexgrep(requireHexPattern(args[0]), toTarget(args[1]), options, factory);
        });

        return ProxyObject.fromMap(methods);
    }

    /** The pattern factory of this bridge: compiles real JavaScript {@code RegExp}s. */
    static JsSearch.PatternFactory spanFactory(RegExpSupport regexps) {
        return (source, flags) -> new JsRegExpSpanMatcher(regexps.create(source, flags));
    }

    /** Reads the hex-pattern argument of {@code search.hexgrep()}. */
    private static String requireHexPattern(Value value) {
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("hex pattern is required, e.g. \"4d5a 9000\""
                    + " (use search.grep() for regular expressions)");
        }
        if (!value.isString()) {
            throw new IllegalArgumentException("hex pattern must be a string such as \"4d5a 9000\""
                    + " (use search.grep() for regular expressions or a Uint8Array for bytes)");
        }
        return value.asString();
    }

    // ========================================================================
    // RegExp support of one JavaScript context
    // ========================================================================

    /**
     * RegExp helpers of a single JavaScript context: compiles patterns with guest-side
     * (ECMAScript) semantics and recognises RegExp values.
     * <p>
     * The polyglot {@link Value} API has no {@code isRegExp()}, so both jobs are done by tiny
     * functions evaluated in the context itself. Instances never hold a {@link Context}
     * reference; they only keep the two guest functions, so a stale instance degrades into
     * clear error messages instead of leaking a context.
     * </p>
     */
    public static final class RegExpSupport {

        /** Guest function {@code (source, flags) => RegExp} (may be {@code null}). */
        private final Value createFn;
        /** Guest function {@code (value) => boolean}, {@code true} for RegExps (may be {@code null}). */
        private final Value isRegExpFn;
        /** Guest function {@code (value) => "Uint8Array"|"Array"|...} (may be {@code null}). */
        private final Value typeNameFn;

        private RegExpSupport(Value createFn, Value isRegExpFn, Value typeNameFn) {
            this.createFn = createFn;
            this.isRegExpFn = isRegExpFn;
            this.typeNameFn = typeNameFn;
        }

        /**
         * Evaluates {@link #REGEXP_HELPERS_SOURCE} in the given context.
         * <p>
         * Never throws: if the helper object cannot be created, the plain {@code RegExp}
         * constructor of the context is used as factory and detection falls back to
         * {@link JsSearchBridge#looksLikeRegExp(Value)}. A context without any usable RegExp
         * still wires the namespace; the error then appears where a pattern is compiled.
         * </p>
         * @param context the JavaScript context of the caller
         * @return the helpers (never {@code null})
         */
        static RegExpSupport create(Context context) {
            if (context == null) {
                return new RegExpSupport(null, null, null);
            }
            Value createFn = null;
            Value isRegExpFn = null;
            Value typeNameFn = null;
            try {
                Value helpers = context.eval("js", REGEXP_HELPERS_SOURCE).execute();
                if (helpers != null && !helpers.isNull()) {
                    createFn = asCallable(helpers, "create");
                    isRegExpFn = asCallable(helpers, "isRegExp");
                    typeNameFn = asCallable(helpers, "typeName");
                }
            } catch (RuntimeException e) {
                // no (or a restricted) JS realm: work without the guest helpers
                createFn = null;
                isRegExpFn = null;
                typeNameFn = null;
            }
            if (createFn == null) {
                // Fallback: calling RegExp(source, flags) as a function equals new RegExp(...).
                createFn = asCallable(context.getBindings("js"), "RegExp");
            }
            return new RegExpSupport(createFn, isRegExpFn, typeNameFn);
        }

        /**
         * Reads a member that is expected to be executable.
         * No {@code hasMember()} pre-check: interop reads resolve inherited members (e.g.
         * {@code RegExp.prototype.test}), member listings do not necessarily.
         * @return the member as executable value, or {@code null} if absent/not executable
         */
        static Value asCallable(Value owner, String name) {
            try {
                Value member = owner.getMember(name);
                return member != null && !member.isNull() && member.canExecute() ? member : null;
            } catch (RuntimeException e) {
                return null;
            }
        }

        /**
         * Compiles a pattern inside the JavaScript context.
         * @param source regular expression source
         * @param flags ECMAScript flags
         * @return the compiled RegExp value
         * @throws IllegalArgumentException if the pattern is invalid or unavailable
         */
        Value create(String source, String flags) {
            if (createFn == null) {
                throw new IllegalStateException("search: no JavaScript RegExp is available in this"
                        + " context, so patterns cannot be compiled");
            }
            Value regex;
            try {
                regex = createFn.execute(source, flags);
            } catch (PolyglotException e) {
                if (isMissingRegexLanguage(e)) {
                    // The pattern is fine - GraalJS compiles RegExp via the internal TRegex
                    // ("regex") language, which is not available in this server at all. Do NOT
                    // report this as an invalid pattern; that would mislead script authors
                    // (and LLMs) into debugging the pattern instead of the deployment.
                    throw new IllegalStateException("The internal GraalVM 'regex' language (TRegex), "
                            + "which GraalJS needs to compile any regular expression, is not "
                            + "available in this server: " + e.getMessage()
                            + " This is a deployment/classpath problem (e.g. the artifact"
                            + " org.graalvm.regex:regex is missing, or a fat JAR did not merge the"
                            + " META-INF/services/com.oracle.truffle.api.TruffleLanguage$Provider"
                            + " entries), not an invalid pattern: /" + source + "/" + flags);
                }
                throw new IllegalArgumentException("Invalid regular expression /" + source + "/"
                        + flags + ": " + e.getMessage()
                        + " (patterns use JavaScript, not Java, syntax)");
            }
            if (regex == null || regex.isNull()) {
                throw new IllegalArgumentException("Could not compile the pattern /" + source
                        + "/" + flags);
            }
            // Verified with the guest test when it is available; without it the value is kept
            // as it is - a value that is not callable as RegExp would have failed already.
            if (isRegExpFn != null && !isRegExp(regex)) {
                throw new IllegalArgumentException("Could not compile the pattern /" + source
                        + "/" + flags);
            }
            return regex;
        }

        /**
         * Detects a {@link PolyglotException} caused by the internal TRegex language being
         * unavailable. GraalJS delegates RegExp compilation to the internal language
         * {@code regex}; if that language is missing from the server's classpath (or its
         * service-loader registration was lost, e.g. in a non-merging fat JAR), the polyglot
         * runtime reports {@code IllegalStateException: No language for id regex found}.
         * This is an environment error and must not be reported as an invalid pattern.
         * @param e the exception thrown while compiling the pattern
         * @return true if the exception indicates the missing 'regex' language
         */
        static boolean isMissingRegexLanguage(PolyglotException e) {
            if (!e.isHostException()) {
                return false;
            }
            Throwable host = e.asHostException();
            if (!(host instanceof IllegalStateException)) {
                return false;
            }
            String message = host.getMessage();
            return message != null && message.startsWith("No language for id ");
        }

        /**
         * @param v a value from the context
         * @return true if the value is a JavaScript RegExp
         */
        boolean isRegExp(Value v) {
            if (v == null || v.isNull()) {
                return false;
            }
            if (isRegExpFn != null) {
                try {
                    Value result = isRegExpFn.execute(v);
                    if (result != null && result.isBoolean()) {
                        return result.asBoolean();
                    }
                } catch (RuntimeException e) {
                    // fall through to the structural check
                }
            }
            return looksLikeRegExp(v);
        }

        /**
         * Names a value the way JavaScript sees it ({@code "Uint8Array"}, {@code "Array"},
         * {@code "RegExp"}, ...). Realm-safe, because it uses {@code Object.prototype.toString}.
         * @param v the value to name
         * @return the type name, or {@code null} if the helper is unavailable or fails
         */
        String typeName(Value v) {
            if (typeNameFn == null || v == null) {
                return null;
            }
            try {
                Value name = typeNameFn.execute(v);
                return name != null && name.isString() ? name.asString() : null;
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /**
     * Conservative fallback RegExp detection, used when the guest helper is not available.
     * Accepts a value if its metaobject is named {@code RegExp}, or if it behaves like one
     * (string {@code source}, string {@code flags}, callable {@code test}).
     */
    static boolean looksLikeRegExp(Value v) {
        try {
            if (v == null || v.isNull()
                    || v.isString() || v.isNumber() || v.isBoolean()
                    || v.isDate() || v.isTime() || v.isTimeZone() || v.isDuration()
                    || v.isHostObject() || v.isProxyObject() || v.isNativePointer()
                    || v.isException() || v.hasArrayElements()) {
                return false;
            }
            if (v.getMetaObject() != null) {
                Value meta = v.getMetaObject();
                if (meta != null && !meta.isNull()
                        && ("RegExp".equals(meta.getMetaQualifiedName())
                            || "RegExp".equals(meta.getMetaSimpleName()))) {
                    return true;
                }
            }
            return memberOrNullAsString(v, "source") != null
                    && memberOrNullAsString(v, "flags") != null
                    && RegExpSupport.asCallable(v, "test") != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Reads a string member without pre-checks (interop reads also resolve inherited members
     * such as {@code RegExp.prototype.source}).
     * @return the member as string, or {@code null} if absent, unreadable or not a string
     */
    private static String memberOrNullAsString(Value value, String member) {
        try {
            Object raw = value.getMember(member);
            if (raw instanceof String s) {
                return s;
            }
            if (raw instanceof Value v && v.isString()) {
                return v.asString();
            }
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ========================================================================
    // Pattern handling
    // ========================================================================

    /**
     * Builds a {@link JsSearch.SearchPattern} from the JS pattern argument.
     * <p>
     * Three forms are accepted: a string (regular expression source), a RegExp object (its own
     * {@code source} and {@code flags}, spec 10.2) and a byte array - {@code Uint8Array} or an
     * array of numbers 0..255 - which is a byte literal for the byte search (spec 10.4). The
     * pattern-level options {@code flags} and {@code caseInsensitive} are <em>not</em> applied
     * here: the engine applies them through
     * {@link JsSearch.SearchPattern#withPatternOptions(String, boolean)}, so their semantics
     * live in exactly one place.
     * </p>
     *
     * @param regexps the context's RegExp helpers
     * @param factory compiles the expression when the engine needs a matcher
     * @param pattern the JS pattern value
     * @return the pattern of the search
     */
    static JsSearch.SearchPattern toPattern(RegExpSupport regexps, JsSearch.PatternFactory factory,
                                            Value pattern) {
        if (pattern == null || pattern.isNull()) {
            throw new IllegalArgumentException("pattern is required (string, RegExp or byte array)");
        }
        if (regexps.isRegExp(pattern)) {
            return JsSearch.SearchPattern.regExp(memberAsString(pattern, "source"),
                    memberAsString(pattern, "flags"), true, factory);
        }
        if (pattern.isString()) {
            String source = pattern.asString();
            if (source.isEmpty()) {
                throw new IllegalArgumentException("pattern must not be empty");
            }
            return JsSearch.SearchPattern.regExp(source, "", false, factory);
        }
        if (pattern.hasArrayElements()) {
            return JsSearch.SearchPattern.byteLiteral(toByteArray(pattern, regexps));
        }
        throw new IllegalArgumentException("pattern must be a string, a RegExp or a byte array"
                + " (Uint8Array), but was " + describe(pattern, regexps));
    }

    /**
     * Converts a {@code Uint8Array} (or an array of numbers) into bytes with the errors an agent
     * needs: element index, kind and range. {@link GraalProxies#toByteArray(Value)} accepts any
     * number and truncates silently, which would turn {@code [0x1ff]} into {@code [0xff]}.
     *
     * @param value the JS array value
     * @param regexps helpers for naming the offending element
     * @return the bytes
     * @throws IllegalArgumentException for an empty array, a non-number, a non-integer or a
     *         value outside {@code 0..255}
     */
    static byte[] toByteArray(Value value, RegExpSupport regexps) {
        long size = value.getArraySize();
        if (size == 0) {
            throw new IllegalArgumentException("pattern must not be empty"
                    + " (a byte array pattern needs at least one byte)");
        }
        if (size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("byte array pattern is too large: " + size + " bytes");
        }
        byte[] data = new byte[(int) size];
        for (int i = 0; i < data.length; i++) {
            Value el = value.getArrayElement(i);
            if (el == null || el.isNull() || !el.isNumber()) {
                throw new IllegalArgumentException("element " + i + " of the byte array pattern is"
                        + " not a number (" + describe(el, regexps) + "); expected bytes 0..255");
            }
            if (!el.fitsInLong()) {
                throw new IllegalArgumentException("element " + i + " of the byte array pattern is"
                        + " not an integer; expected bytes 0..255");
            }
            long v = el.asLong();
            if (v < 0 || v > 255) {
                throw new IllegalArgumentException("element " + i + " of the byte array pattern is"
                        + " out of range: " + v + " (expected 0..255)");
            }
            data[i] = (byte) v;
        }
        return data;
    }

    /**
     * Span matcher backed by a real JavaScript RegExp of the owning context.
     * <p>
     * {@link #find(String, int)} sets {@code lastIndex} explicitly before every {@code exec},
     * so scanning stays stateless across windows and lines even though the byte scan adds
     * {@code g} (iterate all matches). The span is derived from {@code result.index} plus the
     * length of {@code result[0]} - both defined by ECMAScript and identical to
     * {@code RegExp.lastIndex} after the match.
     * <p>
     * Deliberately <em>not</em> derived from {@code result.indices}: measured on this GraalVM,
     * {@code indices} is {@code [[start, end]]} (one nested pair) instead of the flat
     * {@code [start, end, ...]} of the specification, so reading element 0 as a number would
     * produce nonsense. The carrier holds one character per byte, so the string length of the
     * match is its byte length.
     * </p>
     */
    private static final class JsRegExpSpanMatcher implements JsSearch.SpanMatcher {

        private final Value regex;

        JsRegExpSpanMatcher(Value regex) {
            this.regex = regex;
        }

        @Override
        public JsSearch.MatchSpan find(String window, int from) {
            Value result;
            try {
                regex.putMember("lastIndex", from);
                result = regex.invokeMember("exec", window);
            } catch (PolyglotException e) {
                throw new JsUserRuntimeException("Regular expression failed at offset " + from
                        + " of a " + window.length() + "-byte window: " + e.getMessage(), e);
            }
            if (result == null || result.isNull()) {
                return null;
            }
            try {
                long start = result.getMember("index").asLong();
                if (start < 0) {
                    return null;
                }
                Value whole = result.getArrayElement(0);
                if (whole == null || whole.isNull() || !whole.isString()) {
                    throw new JsUserRuntimeException("The regular expression returned a match"
                            + " without a match string, so its length is unknown");
                }
                long length = whole.asString().length();
                if (length > Integer.MAX_VALUE) {
                    throw new JsUserRuntimeException("A match of " + length
                            + " bytes is too long to report");
                }
                return new JsSearch.MatchSpan((int) start, (int) length);
            } catch (JsUserRuntimeException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new JsUserRuntimeException("Could not read the match of the regular"
                        + " expression: " + e.getMessage(), e);
            }
        }
    }

    /** Removes the stateful flags {@code g} and {@code y} (path filters must be stateless). */
    private static String stripStatefulFlags(String flags) {
        StringBuilder sb = new StringBuilder(flags.length());
        for (int i = 0; i < flags.length(); i++) {
            char c = flags.charAt(i);
            if (c != 'g' && c != 'y') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Compiles a RegExp inside the JavaScript context, translating errors into user messages. */
    private static Value createRegex(RegExpSupport regexps, String source, String flags) {
        return regexps.create(source, flags);
    }

    /** Reads a string member of a JS object (used for {@code RegExp.source}/{@code .flags}). */
    private static String memberAsString(Value value, String member) {
        String text = memberOrNullAsString(value, member);
        if (text == null) {
            throw new IllegalArgumentException("Cannot read '" + member + "' of the given RegExp");
        }
        return text;
    }

    // ========================================================================
    // Option conversion
    // ========================================================================

    /**
     * Converts the optional options argument into a plain Java map.
     * <p>
     * Values become {@code Boolean}, {@code Long}/{@code Double}, {@code String},
     * {@code List} or {@link JsSearch.PathFilter} (for RegExp items in include/exclude).
     * Semantic validation (ranges, unknown names, modes) is done by
     * {@link JsSearch#parseOptions(Map, String)}.
     * </p>
     * @param regexps the context's RegExp helpers (used for RegExp filters)
     * @param args the JS arguments
     * @param index index of the options argument
     * @return the option map, or {@code null} if the argument is absent (treated as {@code {}})
     */
    static Map<String, Object> toOptionMap(RegExpSupport regexps, Value[] args, int index) {
        if (args == null || args.length <= index) {
            return null;
        }
        Value options = args[index];
        if (options == null || options.isNull()) {
            return null; // undefined and null are treated as {}
        }
        if (options.hasArrayElements() || options.isString() || options.isNumber()
                || options.isBoolean() || regexps.isRegExp(options)) {
            throw new IllegalArgumentException("search options must be an object"
                    + " (e.g. { recursive: true }), see search.help()");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        if (!options.hasMembers()) {
            return map; // exotic values (e.g. a function) behave like {}
        }
        for (String key : options.getMemberKeys()) {
            Value v;
            try {
                v = options.getMember(key);
            } catch (PolyglotException e) {
                throw new IllegalArgumentException("Cannot read search option '" + key + "': "
                        + e.getMessage());
            }
            map.put(key, toOptionValue(regexps, key, v));
        }
        return map;
    }

    /** Converts one option value; RegExp values are only allowed in include/exclude. */
    private static Object toOptionValue(RegExpSupport regexps, String key, Value v) {
        return toPlainValue(regexps, key, v, "include".equals(key) || "exclude".equals(key));
    }

    private static Object toPlainValue(RegExpSupport regexps, String key, Value v,
                                       boolean regExpAllowed) {
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isBoolean()) {
            return v.asBoolean();
        }
        if (v.isNumber()) {
            return v.fitsInLong() ? (Object) v.asLong() : (Object) v.asDouble();
        }
        if (v.isString()) {
            return v.asString();
        }
        if (v.hasArrayElements()) {
            List<Object> list = new ArrayList<>();
            for (long i = 0; i < v.getArraySize(); i++) {
                list.add(toPlainValue(regexps, key + "[" + i + "]", v.getArrayElement(i),
                        regExpAllowed));
            }
            return list;
        }
        if (regexps.isRegExp(v)) {
            if (!regExpAllowed) {
                throw new IllegalArgumentException("Option '" + key + "' must not be a RegExp"
                        + " (RegExp filters are allowed in include/exclude only)");
            }
            return toPathFilter(regexps, v);
        }
        throw new IllegalArgumentException("Option '" + key + "' has an unsupported value ("
                + describe(v, regexps) + "); allowed are boolean, number, string, array and RegExp"
                + " in include/exclude");
    }

    /** Wraps a JS RegExp as a path filter (recompiled without the stateful flags {@code g}/{@code y}). */
    static JsSearch.PathFilter toPathFilter(RegExpSupport regexps, Value regExpValue) {
        String source = memberAsString(regExpValue, "source");
        String flags = stripStatefulFlags(memberAsString(regExpValue, "flags"));
        final Value regex = createRegex(regexps, source, flags);
        return new JsSearch.PathFilter() {
            @Override
            public boolean test(String path) {
                try {
                    return regex.invokeMember("test", path).asBoolean();
                } catch (PolyglotException e) {
                    throw new JsUserRuntimeException("Include/exclude RegExp failed on path \""
                            + path + "\": " + e.getMessage(), e);
                }
            }
        };
    }

    // ========================================================================
    // Target conversion
    // ========================================================================

    /**
     * Converts the JS target argument into the plain structure expected by {@link JsSearch}:
     * {@code String}, {@code List<Object>} or {@code Map<String,Object>}.
     * @param v the JS target value
     * @return the plain target
     */
    static Object toTarget(Value v) {
        if (v == null || v.isNull()) {
            throw new IllegalArgumentException("target is required");
        }
        if (v.isString()) {
            return v.asString();
        }
        if (v.hasArrayElements()) {
            List<Object> list = new ArrayList<>();
            for (long i = 0; i < v.getArraySize(); i++) {
                list.add(toTarget(v.getArrayElement(i)));
            }
            return list;
        }
        if (v.hasMembers()) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (String key : v.getMemberKeys()) {
                map.put(key, toTargetMemberValue(key, v.getMember(key)));
            }
            return map;
        }
        throw new IllegalArgumentException("target must be a string, an array of targets or an object"
                + " {path[, entry][, archiveChain]} (but was " + describe(v, null) + ")");
    }

    /** Converts a member of an object target (strings and arrays of strings only). */
    private static Object toTargetMemberValue(String key, Object raw) {
        Value v = raw instanceof Value value ? value : null;
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isString()) {
            return v.asString();
        }
        if (v.hasArrayElements()) {
            List<Object> list = new ArrayList<>();
            for (long i = 0; i < v.getArraySize(); i++) {
                Object item = toTargetMemberValue(key + "[" + i + "]", v.getArrayElement(i));
                if (!(item instanceof String)) {
                    throw new IllegalArgumentException("Target property '" + key
                            + "' must be an array of strings");
                }
                list.add(item);
            }
            return list;
        }
        throw new IllegalArgumentException("Target property '" + key
                + "' must be a string or an array of strings, but was " + describe(v, null)
                + " (see search.help())");
    }

    // ========================================================================
    // Result and misc helpers
    // ========================================================================

    /**
     * Creates a mutable {@link ProxyArray} backed by a list of strings, so that scripts may
     * sort or filter the returned array in place just like a plain JavaScript array.
     */
    private static ProxyArray createStringProxyArray(List<String> values) {
        Object[] data = values.toArray();
        return new ProxyArray() {
            @Override
            public Object get(long index) {
                return index >= 0 && index < data.length ? data[(int) index] : null;
            }

            @Override
            public void set(long index, Value value) {
                if (index >= 0 && index < data.length) {
                    data[(int) index] = value == null || value.isNull() ? null : value.asString();
                }
            }

            @Override
            public long getSize() {
                return data.length;
            }
        };
    }

    /** Throws an IllegalArgumentException with a usage hint if too few arguments are given. */
    private static void requireArgs(Value[] args, int min, String signature) {
        if (args == null || args.length < min) {
            throw new IllegalArgumentException("Usage: search." + signature);
        }
    }

    /**
     * Describes a JS value type for error messages. Typed arrays are named the way JavaScript
     * names them ({@code Uint8Array}, {@code Int8Array}, ...), because that is the word the
     * caller used and the one that explains what to pass instead.
     */
    private static String describe(Value v, RegExpSupport regexps) {
        if (v == null || v.isNull()) {
            return "null";
        }
        if (v.isString()) {
            return "string";
        }
        if (v.isNumber()) {
            return "number";
        }
        if (v.isBoolean()) {
            return "boolean";
        }
        if (regexps != null ? regexps.isRegExp(v) : looksLikeRegExp(v)) {
            return "RegExp";
        }
        if (v.hasArrayElements()) {
            if (regexps != null) {
                String name = regexps.typeName(v);
                if (name != null && name.endsWith("Array") && !"Array".equals(name)) {
                    return name;
                }
            }
            return "array";
        }
        if (v.hasMembers()) {
            return "object";
        }
        return "value";
    }
}
