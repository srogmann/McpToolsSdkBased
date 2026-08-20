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
        return "`fs.help()` explains controlled file access";
    }

    @Override
    public String getHelpTip() {
        return "fs.help() (files)";
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
            requireArgs(args, 1, "readFile(path)");
            return JsFileSystem.readFile(args[0].asString());
        });

        methods.put("readLines", (ProxyExecutable) args -> {
            requireArgs(args, 1, "readLines(path, startLine, endLine)");
            String path = args[0].asString();
            int startLine = args.length > 1 && !args[1].isNull() ? args[1].asInt() : 1;
            int endLine = args.length > 2 && !args[2].isNull() ? args[2].asInt()
                    : startLine + JsFileSystem.DEFAULT_MAX_LINES - 1;
            return JsFileSystem.readLines(path, startLine, endLine);
        });

        methods.put("createLineReader", (ProxyExecutable) args -> {
            requireArgs(args, 1, "createLineReader(path)");
            JsFileSystem.LineReader reader = JsFileSystem.createLineReader(args[0].asString());
            return createLineReaderProxy(reader);
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
            requireArgs(args, 2, "writeFile(path, content)");
            JsFileSystem.writeFile(args[0].asString(), toStringValue(args[1]));
            return null;
        });

        methods.put("appendFile", (ProxyExecutable) args -> {
            requireArgs(args, 2, "appendFile(path, content)");
            JsFileSystem.appendFile(args[0].asString(), toStringValue(args[1]));
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
        // scripts run unchanged. Extra arguments (e.g. an encoding option) are ignored.
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
