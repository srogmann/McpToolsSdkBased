package org.rogmann.mcp2sdk.js;

import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyArray;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bridge between GraalVM JavaScript and {@link JsFileSystem}.
 * <p>
 * Creates a {@link ProxyObject} namespace that exposes a synchronous, Node.js-{@code fs}-like
 * API for text files to JavaScript running in a GraalVM Polyglot context.
 * The resulting object is intended to be bound as {@code "fs"} in the JavaScript bindings.
 * </p>
 *
 * <h3>Usage in JavaScript</h3>
 * <pre>{@code
 * var content = fs.readFile("demo.txt");
 * var names = fs.readdir(".");
 * var st = fs.stat("demo.txt");
 * fs.writeFile("out.txt", "hello");
 *
 * // Streaming large files line by line:
 * var r = fs.createLineReader("big.csv");
 * var line;
 * while ((line = r.next()) !== null) {
 *     // process one line
 * }
 * r.close();
 * }</pre>
 *
 * <h3>Security</h3>
 * <p>
 * File access is restricted to the project base directory (system property
 * {@code IDE_PROJECT_DIR}) and, optionally, to add-on directories addressed via a
 * {@code /addonName/...} prefix, see {@link JsFileSystem}.
 * </p>
 */
public class JsFileSystemBridge implements JsModuleInterface {

    public JsFileSystemBridge() {
        // Utility class
    }

    @Override
    public String getNamespace() {
        return "fs";
    }

    @Override
    public String getSummary() {
        return "`fs.help()` explains controlled file access (incl. text encodings)";
    }

    @Override
    public String getHelpTip() {
        return "fs.help() (files, encodings)";
    }

    @Override
    public AutoCloseable wireApi(Value jsBindings) {
        jsBindings.putMember("fs", createFsNamespace(jsBindings.getMember("Uint8Array")));
        return null;
    }

    /**
     * Creates a ProxyObject representing the file system namespace for JavaScript.
     * The returned object can be bound to a JavaScript context as {@code "fs"}.
     *
     * @param uint8ArrayCtor the JS {@code Uint8Array} constructor from the bindings,
     *                        used to return real typed arrays for binary reads
     * @return ProxyObject with file system methods
     */
    public static ProxyObject createFsNamespace(Value uint8ArrayCtor) {
        Map<String, Object> methods = new HashMap<>();

        // ---- Read ----
        methods.put("readFile", (ProxyExecutable) args -> {
            requireArgs(args, 1, "readFile(path[, encoding|options])");
            String path = args[0].asString();
            EncodingArg enc = encodingArg(args.length > 1 ? args[1] : null);
            if (enc.isBuffer()) {
                // Node habit: fs.readFileSync(path, 'buffer')
                return GraalProxies.toUint8Array(uint8ArrayCtor,
                        JsFileSystem.readBytes(path, 0, wholeFileReadLength(path)));
            }
            return JsFileSystem.readFile(path, enc.encoding(), enc.errors());
        });

        methods.put("readLines", (ProxyExecutable) args -> {
            requireArgs(args, 1, "readLines(path[, startLine[, endLine[, encoding|options]]])");
            String path = args[0].asString();
            int startLine = 1;
            int endLine = 0; // 0 lets JsFileSystem apply the default range
            EncodingArg enc = EncodingArg.DEFAULT;
            if (args.length > 1 && !args[1].isNull()) {
                Value second = args[1];
                if (second.isNumber()) {
                    startLine = second.asInt();
                } else if (second.hasMembers()) {
                    Integer start = memberInt(second, "startLine");
                    if (start == null) {
                        start = memberInt(second, "start");
                    }
                    if (start != null) {
                        startLine = start;
                    }
                    Integer end = memberInt(second, "endLine");
                    if (end == null) {
                        end = memberInt(second, "end");
                    }
                    if (end != null) {
                        endLine = end;
                    }
                    enc = encodingArg(second);
                } else {
                    throw new IllegalArgumentException("Usage: fs.readLines(path[, startLine[, endLine"
                            + "[, encoding|options]]]) - startLine must be a number or an options object"
                            + " {startLine, endLine, encoding, errors}");
                }
            }
            if (args.length > 2 && !args[2].isNull()) {
                endLine = args[2].asInt();
            }
            if (args.length > 3 && !args[3].isNull()) {
                enc = encodingArg(args[3]);
            }
            return JsFileSystem.readLines(path, startLine, endLine, enc.encoding(), enc.errors());
        });

        methods.put("createLineReader", (ProxyExecutable) args -> {
            requireArgs(args, 1, "createLineReader(path[, encoding|options])");
            EncodingArg enc = encodingArg(args.length > 1 ? args[1] : null);
            JsFileSystem.LineReader reader = JsFileSystem.createLineReader(args[0].asString(),
                    enc.encoding(), enc.errors());
            return createLineReaderProxy(reader);
        });

        // ---- Character sets ----
        methods.put("detectCharset", (ProxyExecutable) args -> {
            requireArgs(args, 1, "detectCharset(path[, maxBytes])");
            int maxBytes = args.length > 1 && !args[1].isNull()
                    ? args[1].asInt() : JsFileSystem.DEFAULT_SNIFF_BYTES;
            return GraalProxies.toProxyObject(JsFileSystem.detectCharset(args[0].asString(), maxBytes));
        });

        methods.put("decode", (ProxyExecutable) args -> {
            requireArgs(args, 1, "decode(bytes[, encoding|options])");
            byte[] data = GraalProxies.toByteArray(args[0]);
            EncodingArg enc = encodingArg(args.length > 1 ? args[1] : null);
            return JsFileSystem.decode(data, enc.encoding(), enc.errors());
        });

        methods.put("decodeHex", (ProxyExecutable) args -> {
            requireArgs(args, 1, "decodeHex(hex[, encoding|options])");
            EncodingArg enc = encodingArg(args.length > 1 ? args[1] : null);
            return JsFileSystem.decode(JsFileSystem.fromHex(args[0].asString()), enc.encoding(), enc.errors());
        });
        methods.put("encode", (ProxyExecutable) args -> {
            requireArgs(args, 1, "encode(text[, encoding|options])");
            EncodingArg enc = encodingArg(args.length > 1 ? args[1] : null);
            return GraalProxies.toUint8Array(uint8ArrayCtor,
                    JsFileSystem.encode(toStringValue(args[0]), enc.encoding(), enc.errors()));
        });

        // ---- Binary read / stream ----
        methods.put("size", (ProxyExecutable) args -> {
            requireArgs(args, 1, "size(path)");
            return JsFileSystem.size(args[0].asString());
        });

        methods.put("readBytes", (ProxyExecutable) args -> {
            requireArgs(args, 2, "readBytes(path, offset[, length])");
            String path = args[0].asString();
            long offset = toLongValue(args[1]);
            int length = resolveReadLength(path, offset, args, 2);
            return GraalProxies.toUint8Array(uint8ArrayCtor, JsFileSystem.readBytes(path, offset, length));
        });

        methods.put("readHex", (ProxyExecutable) args -> {
            requireArgs(args, 2, "readHex(path, offset[, length])");
            String path = args[0].asString();
            long offset = toLongValue(args[1]);
            int length = resolveReadLength(path, offset, args, 2);
            return JsFileSystem.readHex(path, offset, length);
        });

        methods.put("createBlockReader", (ProxyExecutable) args -> {
            requireArgs(args, 1, "createBlockReader(path[, blockSize])");
            int blockSize = args.length > 1 && !args[1].isNull()
                    ? args[1].asInt() : JsFileSystem.DEFAULT_BLOCK_SIZE;
            JsFileSystem.BinaryBlockReader reader = JsFileSystem.createBlockReader(args[0].asString(), blockSize);
            return createBlockReaderProxy(reader, uint8ArrayCtor);
        });

        // ---- List / inspect ----
        methods.put("readdir", (ProxyExecutable) args -> {
            String dir = args.length > 0 && !args[0].isNull() ? args[0].asString() : ".";
            List<String> names = JsFileSystem.readdir(dir);
            return createStringProxyArray(names);
        });

        methods.put("listFiles", (ProxyExecutable) args -> {
            String dir = args.length > 0 && !args[0].isNull() ? args[0].asString() : ".";
            List<String> paths = JsFileSystem.listFiles(dir);
            return createStringProxyArray(paths);
        });

        methods.put("stat", (ProxyExecutable) args -> {
            requireArgs(args, 1, "stat(path)");
            Map<String, Object> stat = JsFileSystem.stat(args[0].asString());
            return stat != null ? ProxyObject.fromMap(stat) : null;
        });

        methods.put("exists", (ProxyExecutable) args -> {
            requireArgs(args, 1, "exists(path)");
            return JsFileSystem.exists(args[0].asString());
        });

        methods.put("isFile", (ProxyExecutable) args -> {
            requireArgs(args, 1, "isFile(path)");
            return JsFileSystem.isFile(args[0].asString());
        });

        methods.put("isDirectory", (ProxyExecutable) args -> {
            requireArgs(args, 1, "isDirectory(path)");
            return JsFileSystem.isDirectory(args[0].asString());
        });

        // ---- Write / edit ----
        methods.put("writeFile", (ProxyExecutable) args -> {
            requireArgs(args, 2, "writeFile(path, content[, encoding|options])");
            EncodingArg enc = encodingArg(args.length > 2 ? args[2] : null);
            JsFileSystem.writeFile(args[0].asString(), toStringValue(args[1]), enc.encoding(), enc.errors());
            return null;
        });

        methods.put("appendFile", (ProxyExecutable) args -> {
            requireArgs(args, 2, "appendFile(path, content[, encoding|options])");
            EncodingArg enc = encodingArg(args.length > 2 ? args[2] : null);
            JsFileSystem.appendFile(args[0].asString(), toStringValue(args[1]), enc.encoding(), enc.errors());
            return null;
        });

        methods.put("mkdir", (ProxyExecutable) args -> {
            requireArgs(args, 1, "mkdir(path)");
            JsFileSystem.mkdir(args[0].asString());
            return null;
        });

        methods.put("rm", (ProxyExecutable) args -> {
            requireArgs(args, 1, "rm(path)");
            JsFileSystem.rm(args[0].asString());
            return null;
        });

        methods.put("rename", (ProxyExecutable) args -> {
            requireArgs(args, 2, "rename(oldPath, newPath)");
            JsFileSystem.rename(args[0].asString(), args[1].asString());
            return null;
        });

        methods.put("copyFile", (ProxyExecutable) args -> {
            requireArgs(args, 2, "copyFile(sourcePath, targetPath)");
            JsFileSystem.copyFile(args[0].asString(), args[1].asString());
            return null;
        });

        // ---- Binary write ----
        methods.put("writeBytes", (ProxyExecutable) args -> {
            requireArgs(args, 2, "writeBytes(path, data[, offset])");
            String path = args[0].asString();
            byte[] data = GraalProxies.toByteArray(args[1]);
            if (args.length > 2 && !args[2].isNull()) {
                JsFileSystem.writeBytes(path, data, toLongValue(args[2]));
            } else {
                JsFileSystem.writeBytes(path, data);
            }
            return null;
        });

        // ---- Help ----
        methods.put("help", (ProxyExecutable) args -> JsFileSystem.help());

        // ---- Node.js-compatible *Sync aliases ----
        // LLMs often write Node.js-style code like
        //   const fs = require('fs'); fs.readFileSync("a.txt");
        // Map the synchronous Node fs API names onto the same implementations so such
        // scripts run unchanged. Encoding arguments work as in Node - fs.readFileSync(p, 'latin1'),
        // fs.readFileSync(p, {encoding: 'cp1252'}), fs.readFileSync(p, 'buffer') - and other
        // options (mode, flag, flush) are ignored.
        methods.put("readFileSync", methods.get("readFile"));
        methods.put("readdirSync", methods.get("readdir"));
        methods.put("statSync", methods.get("stat"));
        methods.put("existsSync", methods.get("exists"));
        methods.put("writeFileSync", methods.get("writeFile"));
        methods.put("appendFileSync", methods.get("appendFile"));
        methods.put("mkdirSync", methods.get("mkdir"));
        methods.put("rmSync", methods.get("rm"));
        methods.put("renameSync", methods.get("rename"));
        methods.put("copyFileSync", methods.get("copyFile"));

        return ProxyObject.fromMap(methods);
    }

    /**
     * Creates a mutable {@link ProxyArray} backed by a list of strings.
     * <p>
     * Unlike {@link ProxyArray#fromArray(Object[])} with a typed {@code String[]},
     * this proxy converts values written back from JavaScript (e.g. during an
     * in-place {@code sort()}) into Java strings before storing them. This avoids
     * {@code ArrayStoreException: org.graalvm.polyglot.Value}, which GraalVM threw
     * when it tried to write a polyglot value into the typed {@code String[]}.
     * </p>
     * @param values list of strings to expose
     * @return mutable ProxyArray whose elements are plain Java strings
     */
    private static ProxyArray createStringProxyArray(List<String> values) {
        Object[] data = values.toArray();
        return new ProxyArray() {
            @Override
            public Object get(long index) {
                return data[(int) index];
            }

            @Override
            public void set(long index, Value value) {
                data[(int) index] = toStringValue(value);
            }

            @Override
            public long getSize() {
                return data.length;
            }
        };
    }

    /**
     * Converts a GraalVM Value into a Java long (0 or positive for offsets).
     */
    private static long toLongValue(Value value) {
        if (value == null || value.isNull() || !value.isNumber()) {
            throw new IllegalArgumentException("expected an integer number");
        }
        return value.fitsInLong() ? value.asLong() : (long) value.asDouble();
    }

    /**
     * Charset name and error policy as passed from JavaScript.
     * <p>
     * Both parts are optional; {@code null} means "use the default" (UTF-8 / strict).
     * {@code encoding} may also be the Node-style pseudo-name {@code "buffer"}, which asks for
     * raw bytes instead of text.
     * </p>
     * @param encoding charset name (or {@code "buffer"}), may be null
     * @param errors error policy ({@code strict}, {@code replace}, {@code ignore}), may be null
     */
    private record EncodingArg(String encoding, String errors) {

        /** The defaults: UTF-8, strict. */
        static final EncodingArg DEFAULT = new EncodingArg(null, null);

        /**
         * Whether the caller asked for raw bytes instead of decoded text.
         * @return true for {@code "buffer"} / {@code "bytes"} / {@code "uint8array"}
         */
        boolean isBuffer() {
            if (encoding == null) {
                return false;
            }
            String key = encoding.trim();
            return key.equalsIgnoreCase("buffer") || key.equalsIgnoreCase("bytes")
                    || key.equalsIgnoreCase("uint8array");
        }
    }

    /**
     * Parses an optional encoding argument.
     * <p>
     * Accepted forms: nothing / {@code null} (defaults), a charset name as string
     * ({@code fs.readFileSync(path, 'latin1')}), or an options object
     * ({@code {encoding: 'CP-1252', errors: 'replace'}}; {@code charset} and {@code onMalformed}
     * are accepted as aliases). Unknown members (e.g. Node's {@code flag}) are ignored.
     * </p>
     * @param value the JS argument value (may be null or undefined)
     * @return the parsed argument, never null
     * @throws IllegalArgumentException for a non-string, non-object value or an object without
     *         any recognized member
     */
    private static EncodingArg encodingArg(Value value) {
        if (value == null || value.isNull()) {
            return EncodingArg.DEFAULT;
        }
        if (value.isString()) {
            return new EncodingArg(value.asString(), null);
        }
        if (value.hasMembers()) {
            String enc = memberString(value, "encoding");
            if (enc == null) {
                enc = memberString(value, "charset");
            }
            String errors = memberString(value, "errors");
            if (errors == null) {
                errors = memberString(value, "onMalformed");
            }
            if (enc == null && errors == null) {
                throw new IllegalArgumentException("The options object has no 'encoding' and no 'errors' member."
                        + " Usage: fs.readFile(path, {encoding: 'CP-1252', errors: 'replace'})"
                        + " or fs.readFile(path, 'CP-1252').");
            }
            return new EncodingArg(enc, errors);
        }
        throw new IllegalArgumentException("encoding must be a charset name (a string such as 'UTF-8',"
                + " 'ISO-8859-1', 'CP-1252', 'UTF-16LE') or an options object {encoding, errors}");
    }

    /**
     * Reads a string member of an options object.
     * @param obj the object value
     * @param name member name
     * @return the string value, or null if absent or null
     * @throws IllegalArgumentException if the member exists but is not a string
     */
    private static String memberString(Value obj, String name) {
        if (!obj.hasMember(name)) {
            return null;
        }
        Value v = obj.getMember(name);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isString()) {
            throw new IllegalArgumentException("option '" + name + "' must be a string (e.g. 'CP-1252')");
        }
        return v.asString();
    }

    /**
     * Reads an integer member of an options object.
     * @param obj the object value
     * @param name member name
     * @return the value, or null if absent or null
     * @throws IllegalArgumentException if the member exists but is not a number
     */
    private static Integer memberInt(Value obj, String name) {
        if (!obj.hasMember(name)) {
            return null;
        }
        Value v = obj.getMember(name);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isNumber()) {
            throw new IllegalArgumentException("option '" + name + "' must be a number");
        }
        return v.asInt();
    }

    /**
     * Returns the whole file length as an int, rejecting files above the single-read limit.
     * @param path path relative to the base directory
     * @return file size in bytes
     * @throws IllegalArgumentException if the file is larger than {@link JsFileSystem#MAX_READ_BYTES}
     */
    private static int wholeFileReadLength(String path) {
        long remaining = JsFileSystem.size(path);
        if (remaining > JsFileSystem.MAX_READ_BYTES) {
            throw new IllegalArgumentException("File is " + remaining + " bytes; that exceeds the single-read"
                    + " limit of " + JsFileSystem.MAX_READ_BYTES + " bytes. Read ranges with fs.readBytes(path,"
                    + " offset, length) or stream it with fs.createBlockReader(path, blockSize).");
        }
        return (int) remaining;
    }

    /**
     * Resolves an optional read length.
     * <p>
     * If the length argument is omitted, the remainder of the file (from the offset) is
     * read, bounded by {@link JsFileSystem#MAX_READ_BYTES}. A negative offset is passed
     * through so that {@link JsFileSystem#readBytes} reports the offset error itself.
     * </p>
     */
    private static int resolveReadLength(String path, long offset, Value[] args, int lengthIndex) {
        if (offset < 0) {
            return 0;
        }
        if (args.length > lengthIndex && !args[lengthIndex].isNull()) {
            return args[lengthIndex].asInt();
        }
        long remaining = JsFileSystem.size(path) - offset;
        if (remaining > JsFileSystem.MAX_READ_BYTES) {
            throw new IllegalArgumentException("File has " + remaining + " bytes from offset; this exceeds the "
                    + "single-read limit of " + JsFileSystem.MAX_READ_BYTES
                    + ". Pass an explicit length or use fs.createBlockReader for streaming.");
        }
        return (int) Math.max(0, remaining);
    }

    /**
     * Wraps a {@link JsFileSystem.BinaryBlockReader} as a ProxyObject for JavaScript.
     */
    private static ProxyObject createBlockReaderProxy(JsFileSystem.BinaryBlockReader reader, Value uint8ArrayCtor) {
        Map<String, Object> methods = new HashMap<>();
        methods.put("next", (ProxyExecutable) args -> {
            byte[] block = reader.next();
            return block != null ? GraalProxies.toUint8Array(uint8ArrayCtor, block) : null;
        });
        methods.put("nextHex", (ProxyExecutable) args -> reader.nextHex());
        methods.put("position", (ProxyExecutable) args -> reader.position());
        methods.put("blockNumber", (ProxyExecutable) args -> reader.blockNumber());
        methods.put("isClosed", (ProxyExecutable) args -> reader.isClosed());
        methods.put("close", (ProxyExecutable) args -> {
            reader.close();
            return null;
        });
        return ProxyObject.fromMap(methods);
    }

    /**
     * Wraps a {@link JsFileSystem.LineReader} as a ProxyObject for JavaScript.
     */
    private static ProxyObject createLineReaderProxy(JsFileSystem.LineReader reader) {
        Map<String, Object> methods = new HashMap<>();
        methods.put("next", (ProxyExecutable) args -> reader.next());
        methods.put("readLines", (ProxyExecutable) args -> {
            int maxLines = args.length > 0 && !args[0].isNull() ? args[0].asInt() : 100;
            return reader.readLines(maxLines);
        });
        methods.put("lineNumber", (ProxyExecutable) args -> reader.getLineNumber());
        methods.put("encoding", (ProxyExecutable) args -> reader.getEncoding());
        methods.put("close", (ProxyExecutable) args -> {
            reader.close();
            return null;
        });
        return ProxyObject.fromMap(methods);
    }

    /**
     * Converts a GraalVM Value to a String (for write/append content).
     */
    private static String toStringValue(Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        Object obj = GraalProxies.fromValue(value);
        return obj != null ? String.valueOf(obj) : null;
    }

    /**
     * Throws an IllegalArgumentException with a usage hint if too few arguments are given.
     */
    private static void requireArgs(Value[] args, int min, String signature) {
        if (args == null || args.length < min) {
            throw new IllegalArgumentException("Usage: fs." + signature);
        }
    }
}
