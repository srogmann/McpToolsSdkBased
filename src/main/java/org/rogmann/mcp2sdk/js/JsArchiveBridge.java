package org.rogmann.mcp2sdk.js;

import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyArray;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;

import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bridge between GraalVM JavaScript and {@link JsArchive}.
 * <p>
 * Creates a {@link ProxyObject} namespace {@code "archive"} exposing ZIP and tar archives
 * ({@code zipEntries}/{@code zipEntry}, {@code tarEntries}/{@code tarEntry} with the first
 * argument as file path or raw bytes; a gzipped tarball {@code .tar.gz}/{@code .tgz} is
 * decompressed automatically, detected by its magic bytes), gzip/deflate byte streams
 * ({@code gzip}, {@code gunzip}, {@code gunzipFile}, {@code deflate}, {@code inflate}) and -
 * if enabled by configuration - the ZIP write operations {@code zipCreate},
 * {@code zipEntryWrite} and
 * {@code zipEntryDelete}. Entry lists are returned as JS-traversable arrays of plain objects;
 * entry content and byte-stream results are returned as real {@code Uint8Array} typed arrays
 * (the unified byte contract of the JS tool family). Write operations return a summary object
 * including a {@code warnings} array.
 * The resulting object is intended to be bound as {@code "archive"} in the JavaScript bindings.
 * </p>
 *
 * <h3>ZIP writing is opt-in</h3>
 * <p>
 * {@code zipCreate}, {@code zipEntryWrite} and {@code zipEntryDelete} require the system
 * property {@code mcp.js.archive.readonly=false}; if it is absent the methods exist but fail
 * with an explanatory error, so callers get a clear message
 * instead of "is not a function". {@code archive.status()} reports the current state and
 * {@code archive.help()} renders the write section accordingly. tar has no write support.
 * </p>
 *
 * <h3>Usage in JavaScript</h3>
 * <pre>{@code
 * var names = archive.zipEntries("app.war").map(e => e.name);
 * var webXml = archive.zipEntry("app.war", "WEB-INF/web.xml");
 * var cfg = archive.tarEntry("backup.tar", "etc/config.txt");
 * var raw = archive.gunzipFile("log.gz");                       // gunzip a file
 * var list2 = archive.tarEntries("src.tar.gz");                 // .tgz in one call
 * var oneFile = archive.tarEntry("src.tar.gz", list2[0].name);  // no gunzip step needed
 * var gz = archive.gzip(new Uint8Array([1, 2, 3]));             // compress bytes
 * fs.writeBytes("out.bin", cfg);                                // persist via fs
 *
 * archive.status();                                             // { readonly: true|false, ... }
 * archive.zipCreate("out.zip", [{name: "a.txt", text: "hello"}]);
 * archive.zipEntryWrite("app.jar", "config/app.yml", newYaml);   // upsert one entry
 * archive.zipEntryDelete("out.zip", "a.txt");
 * }</pre>
 */
public class JsArchiveBridge implements JsModuleInterface {

    /** Supported options of {@code zipCreate(path, entries, options)} (strict). */
    private static final Set<String> CREATE_OPTIONS = Set.of("overwrite", "comment", "level");

    /** Supported options of {@code zipEntryWrite(path, name, data, options)} (strict). */
    private static final Set<String> ENTRY_WRITE_OPTIONS = Set.of("method", "mtime", "encoding");

    /** Supported members of one entry object of {@code zipCreate} (strict). */
    private static final Set<String> ENTRY_MEMBERS = Set.of("name", "data", "text", "method", "mtime",
            "comment", "encoding");

    public JsArchiveBridge() {
        // Utility class
    }

    @Override
    public String getNamespace() {
        return "archive";
    }

    @Override
    public String getSummary() {
        return "`archive.help()` explains ZIP/tar/gzip access (.tar.gz/.tgz are read in one call,"
                + " gzip is auto-detected; ZIP writing is opt-in, see `archive.status()`)";
    }

    @Override
    public String getHelpTip() {
        return "archive.help() (ZIP/tar incl. .tar.gz/.tgz, gzip, ZIP write)";
    }

    @Override
    public AutoCloseable wireApi(Value jsBindings) {
        jsBindings.putMember("archive", createArchiveNamespace(jsBindings.getMember("Uint8Array")));
        return null;
    }

    /**
     * Creates a ProxyObject representing the archive namespace for JavaScript.
     * The returned object can be bound to a JavaScript context as {@code "archive"}.
     *
     * @param uint8ArrayCtor the JS {@code Uint8Array} constructor from the bindings,
     *                        used to return real typed arrays for extracted entries
     * @return ProxyObject with archive methods
     */
    public static ProxyObject createArchiveNamespace(Value uint8ArrayCtor) {
        Map<String, Object> methods = new HashMap<>();

        methods.put("zipEntries", (ProxyExecutable) args -> {
            requireArgs(args, 1, "zipEntries(path)");
            return toEntryArray(JsArchive.zipEntries(args[0].asString()));
        });

        methods.put("zipEntry", (ProxyExecutable) args -> {
            requireArgs(args, 2, "zipEntry(path, entryName)");
            return toU8(uint8ArrayCtor, JsArchive.zipEntry(args[0].asString(), args[1].asString()));
        });

        // ---- ZIP write (gated by the mcp.js.archive.readonly system property) ----
        methods.put("zipCreate", (ProxyExecutable) args -> {
            requireArgs(args, 2, "zipCreate(path, entries[, options])");
            String path = args[0].asString();
            List<JsArchive.ZipEntrySpec> entries = toEntrySpecs(args[1]);
            CreateOptions options = CreateOptions.parse(argOrNull(args, 2));
            return GraalProxies.toProxyObject(JsArchive.zipCreate(path, entries,
                    options.overwrite, options.comment, options.level));
        });

        methods.put("zipEntryWrite", (ProxyExecutable) args -> {
            requireArgs(args, 2, "zipEntryWrite(path, name, data[, options])");
            String path = args[0].asString();
            String name = args[1].asString();
            EntryWriteOptions options = EntryWriteOptions.parse(argOrNull(args, 3));
            byte[] data = toEntryData(argOrNull(args, 2), options.encoding);
            return GraalProxies.toProxyObject(
                    JsArchive.zipEntryWrite(path, name, data, options.method, options.mtimeMillis));
        });

        methods.put("zipEntryDelete", (ProxyExecutable) args -> {
            requireArgs(args, 2, "zipEntryDelete(path, name)");
            return GraalProxies.toProxyObject(JsArchive.zipEntryDelete(args[0].asString(), args[1].asString()));
        });

        methods.put("status", (ProxyExecutable) args -> GraalProxies.toProxyObject(JsArchive.status()));

        // tar: the first argument is a path or bytes; JsArchive detects a gzip stream by its
        // magic bytes, so a .tar.gz/.tgz needs no gunzip step here (or in the caller's script).
        methods.put("tarEntries", (ProxyExecutable) args -> {
            requireArgs(args, 1, "tarEntries(pathOrBytes)");
            Value v = args[0];
            if (v.isString()) {
                return toEntryArray(JsArchive.tarEntries(v.asString()));
            }
            return toEntryArray(JsArchive.tarEntries(GraalProxies.toByteArray(v)));
        });

        methods.put("tarEntry", (ProxyExecutable) args -> {
            requireArgs(args, 2, "tarEntry(pathOrBytes, entryName)");
            Value v = args[0];
            if (v.isString()) {
                return toU8(uint8ArrayCtor, JsArchive.tarEntry(v.asString(), args[1].asString()));
            }
            return toU8(uint8ArrayCtor, JsArchive.tarEntry(GraalProxies.toByteArray(v), args[1].asString()));
        });

        // ---- gzip / deflate (byte streams) ----
        methods.put("gzip", (ProxyExecutable) args -> {
            requireArgs(args, 1, "gzip(data)");
            return toU8(uint8ArrayCtor, JsArchive.gzip(GraalProxies.toByteArray(args[0])));
        });

        methods.put("gunzip", (ProxyExecutable) args -> {
            requireArgs(args, 1, "gunzip(data)");
            return toU8(uint8ArrayCtor, JsArchive.gunzip(GraalProxies.toByteArray(args[0])));
        });

        methods.put("gunzipFile", (ProxyExecutable) args -> {
            requireArgs(args, 1, "gunzipFile(path)");
            return toU8(uint8ArrayCtor, JsArchive.gunzipFile(args[0].asString()));
        });

        methods.put("deflate", (ProxyExecutable) args -> {
            requireArgs(args, 1, "deflate(data)");
            return toU8(uint8ArrayCtor, JsArchive.deflate(GraalProxies.toByteArray(args[0])));
        });

        methods.put("inflate", (ProxyExecutable) args -> {
            requireArgs(args, 1, "inflate(data)");
            return toU8(uint8ArrayCtor, JsArchive.inflate(GraalProxies.toByteArray(args[0])));
        });

        methods.put("help", (ProxyExecutable) args -> JsArchive.help());

        return ProxyObject.fromMap(methods);
    }

    /**
     * Wraps a list of entry maps as a mutable, JS-traversable array
     * (recursively converts nested Maps).
     */
    private static ProxyArray toEntryArray(List<Map<String, Object>> entries) {
        return new GraalProxies.NestedProxyArray(entries);
    }

    /**
     * Converts a Java byte array to a real JS Uint8Array (null stays null, e.g. "not found").
     */
    private static Value toU8(Value uint8ArrayCtor, byte[] data) {
        return data != null ? GraalProxies.toUint8Array(uint8ArrayCtor, data) : null;
    }

    private static void requireArgs(Value[] args, int min, String signature) {
        if (args == null || args.length < min) {
            throw new IllegalArgumentException("Usage: archive." + signature);
        }
    }

    /**
     * Returns an optional argument, {@code null} when missing, undefined or null.
     */
    private static Value argOrNull(Value[] args, int index) {
        if (args == null || index >= args.length) {
            return null;
        }
        Value value = args[index];
        return (value == null || value.isNull()) ? null : value;
    }

    /**
     * Converts the JS entry array of {@code zipCreate} into entry specs.
     * Each element is {@code {name, data|text, method?, mtime?, comment?, encoding?}}.
     */
    private static List<JsArchive.ZipEntrySpec> toEntrySpecs(Value value) {
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("zipCreate: entries must be an array of "
                    + "{name, data|text, method?, mtime?, comment?} (use [] for an empty archive)");
        }
        if (!value.hasArrayElements()) {
            throw new IllegalArgumentException("zipCreate: entries must be an array of "
                    + "{name, data|text, method?, mtime?, comment?} objects");
        }
        List<JsArchive.ZipEntrySpec> specs = new ArrayList<>();
        for (long i = 0; i < value.getArraySize(); i++) {
            Value element = value.getArrayElement(i);
            if (element == null || element.isNull() || !element.hasMembers()) {
                throw new IllegalArgumentException("zipCreate: entries[" + i + "] must be an object "
                        + "{name, data|text, method?, mtime?, comment?}");
            }
            rejectUnknownMembers(element, ENTRY_MEMBERS, "entries[" + i + "]",
                    "name, data, text, method, mtime, comment, encoding");
            Value nameValue = member(element, "name");
            if (nameValue == null || !nameValue.isString()) {
                throw new IllegalArgumentException("zipCreate: entries[" + i + "].name is required (string)");
            }
            String name = nameValue.asString();
            String encoding = stringMember(element, "encoding", "entries[" + i + "]");
            byte[] data = toEntryData(firstNonNull(member(element, "data"), member(element, "text")), encoding);
            String method = stringMember(element, "method", "entries[" + i + "]");
            Long mtime = toMtimeMillis(member(element, "mtime"), "entries[" + i + "].mtime");
            String comment = stringMember(element, "comment", "entries[" + i + "]");
            specs.add(new JsArchive.ZipEntrySpec(name, data, method, mtime, comment));
        }
        return specs;
    }

    /**
     * Converts entry content to bytes: a string is encoded with the given charset (UTF-8 by
     * default), an array becomes bytes as-is, {@code null} becomes empty content.
     */
    private static byte[] toEntryData(Value value, String encoding) {
        if (value == null || value.isNull()) {
            return new byte[0];
        }
        if (value.isString()) {
            return value.asString().getBytes(charset(encoding));
        }
        if (value.hasArrayElements()) {
            return GraalProxies.toByteArray(value);
        }
        throw new IllegalArgumentException("entry data must be a string (encoded as UTF-8 by default) or a "
                + "Uint8Array / array of numbers 0-255; pass null or omit it for an empty entry");
    }

    /**
     * Resolves a charset name; {@code null} means UTF-8.
     */
    private static Charset charset(String encoding) {
        if (encoding == null || encoding.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(encoding.trim());
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            throw new IllegalArgumentException("unknown encoding '" + encoding + "' (use 'UTF-8', 'UTF-16LE',"
                    + " 'ISO-8859-1', 'CP-1252', ...)", e);
        }
    }

    /**
     * Converts an {@code mtime} option to epoch milliseconds: a number is taken as-is, a date
     * value through its instant (a JS {@code Date} maps to {@link java.time.Instant}, a
     * date-only value to local midnight), a string as ISO-8601 with or without time part.
     */
    private static Long toMtimeMillis(Value value, String what) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asLong();
        }
        if (value.isInstant()) {
            return value.asInstant().toEpochMilli();
        }
        if (value.isDate()) {
            // Value.asDate() yields a LocalDate (no time of day): use local midnight.
            return value.asDate().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        if (value.isString()) {
            String text = value.asString().trim();
            try {
                return Instant.parse(text).toEpochMilli();
            } catch (RuntimeException ignored) {
                // try the local forms below
            }
            try {
                return LocalDateTime.parse(text).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            } catch (RuntimeException ignored) {
                // try a plain date below
            }
            try {
                return LocalDate.parse(text).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
            } catch (RuntimeException ignored) {
                throw new IllegalArgumentException(what + " must be epoch milliseconds, a Date or an ISO-8601"
                        + " string such as '2026-05-14T10:30:00Z' (got '" + text + "')");
            }
        }
        throw new IllegalArgumentException(what + " must be epoch milliseconds, a Date or an ISO-8601 string");
    }

    /**
     * Rejects unknown members of an options or entry object.
     */
    private static void rejectUnknownMembers(Value object, Set<String> allowed, String what, String supported) {
        Set<String> unknown = new HashSet<>();
        for (Object key : object.getMemberKeys()) {
            String name = String.valueOf(key);
            if (!allowed.contains(name)) {
                unknown.add(name);
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(what + ": unknown option(s) " + unknown
                    + " (supported: " + supported + ")");
        }
    }

    private static Value member(Value object, String name) {
        if (!object.hasMember(name)) {
            return null;
        }
        Value value = object.getMember(name);
        return (value == null || value.isNull()) ? null : value;
    }

    private static String stringMember(Value object, String name, String what) {
        Value value = member(object, name);
        if (value == null) {
            return null;
        }
        if (!value.isString()) {
            throw new IllegalArgumentException(what + "." + name + " must be a string");
        }
        return value.asString();
    }

    private static Value firstNonNull(Value first, Value second) {
        return first != null ? first : second;
    }

    /**
     * Options of {@code archive.zipCreate(path, entries, options)}:
     * {@code {overwrite: false, comment, level}} (strict).
     */
    private record CreateOptions(boolean overwrite, String comment, Integer level) {

        static CreateOptions parse(Value options) {
            if (options == null) {
                return new CreateOptions(false, null, null);
            }
            if (!options.hasMembers()) {
                throw new IllegalArgumentException("zipCreate: options must be an object "
                        + "{overwrite, comment, level} or omitted");
            }
            rejectUnknownMembers(options, CREATE_OPTIONS, "zipCreate options", "overwrite, comment, level");
            Value overwriteValue = member(options, "overwrite");
            boolean overwrite = overwriteValue != null && (overwriteValue.isBoolean()
                    ? overwriteValue.asBoolean() : Boolean.parseBoolean(overwriteValue.asString()));
            Value levelValue = member(options, "level");
            Integer level = null;
            if (levelValue != null) {
                if (!levelValue.isNumber()) {
                    throw new IllegalArgumentException("zipCreate options.level must be a number (-1..9)");
                }
                level = (int) levelValue.asLong();
            }
            return new CreateOptions(overwrite, stringMember(options, "comment", "zipCreate options"), level);
        }
    }

    /**
     * Options of {@code archive.zipEntryWrite(path, name, data, options)}:
     * {@code {method, mtime, encoding}} (strict).
     */
    private record EntryWriteOptions(String method, Long mtimeMillis, String encoding) {

        static EntryWriteOptions parse(Value options) {
            if (options == null) {
                return new EntryWriteOptions(null, null, null);
            }
            if (!options.hasMembers()) {
                throw new IllegalArgumentException("zipEntryWrite: options must be an object "
                        + "{method, mtime, encoding} or omitted");
            }
            rejectUnknownMembers(options, ENTRY_WRITE_OPTIONS, "zipEntryWrite options",
                    "method, mtime, encoding");
            return new EntryWriteOptions(stringMember(options, "method", "zipEntryWrite options"),
                    toMtimeMillis(member(options, "mtime"), "zipEntryWrite options.mtime"),
                    stringMember(options, "encoding", "zipEntryWrite options"));
        }
    }
}
