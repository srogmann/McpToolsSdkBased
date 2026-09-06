package org.rogmann.mcp2sdk.js;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Disassembly of Java class files (bytecode) for JavaScript by invoking the {@code javap} tool
 * of the JDK.
 *
 * <p>The supplied source is either a file path relative to the project base directory or a raw
 * byte array (e.g. an entry extracted with {@code archive}). For byte arrays an intermediate
 * {@code .class} file (output name stem) is created next to the output file and kept on success,
 * so the disassembly stays re-runnable and diffable.</p>
 *
 * <h3>Commands</h3>
 * <ul>
 *   <li>{@code bytecode} (default): {@code javap -c -p -constants} — full bytecode including
 *       private members and constant values.</li>
 *   <li>{@code structure}: {@code javap -protected} — the public/protected API surface
 *       (signatures only, no bytecode).</li>
 *   <li>{@code verbose}: {@code javap -verbose -p} — constant pool, flags, StackMapTable,
 *       line number table etc.</li>
 * </ul>
 *
 * <h3>Security</h3>
 * <ul>
 *   <li>The executable is resolved from a fixed name ({@code javap} on the {@code PATH}, with a
 *       fallback to {@code java.home/bin}); the caller can never inject a command or extra
 *       arguments. The command parameter selects only from a fixed flag allow-list.</li>
 *   <li>The process is started with {@link ProcessBuilder} and an explicit argument list; no
 *       shell is involved.</li>
 *   <li>All file paths (output, intermediate class file, source file) are validated with
 *       {@link JsFileSystem#resolveSafePath(String)}: no {@code ..}, no absolute paths outside
 *       the base directory, no symbolic links leaving the base.</li>
 *   <li>The input must start with the class magic {@code 0xCAFEBABE};</li>
 *   <li>User-facing error messages contain only relative paths; absolute paths are logged.</li>
 * </ul>
 *
 * <h3>Usage in JavaScript (via the {@code javap} namespace)</h3>
 * <pre>{@code
 * var r = javap.disassemble("classes/Foo.class", "bytecode", "Foo.bytecode.txt");
 * var r2 = javap.disassemble(classBytes, "structure", "Foo.structure.txt");
 * var r3 = javap.disassemble(classBytes, null, "Foo.default.txt");   // default: bytecode
 * }</pre>
 */
public class JsJavap {

    private static final Logger LOG = LoggerFactory.getLogger(JsJavap.class);

    /** Upper bound for a class-file input; protects the JS engine heap. */
    public static final long MAX_INPUT_BYTES = 64L * 1024 * 1024;

    /** Timeout for a single javap invocation. */
    public static final long JAVAP_TIMEOUT_SECONDS = 60;

    /** Number of stderr bytes kept for a user-facing failure message. */
    private static final int MAX_STDERR_BYTES = 4096;

    /** Java class magic bytes ({@code 0xCAFEBABE}). */
    private static final byte[] CLASS_MAGIC = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE};

    /** Command used when the caller passes null/blank. */
    private static final String DEFAULT_COMMAND = "bytecode";

    /** Fixed allow-list of commands mapping to fixed javap flag sets. */
    private static final Map<String, List<String>> COMMAND_FLAGS = new LinkedHashMap<>();

    static {
        COMMAND_FLAGS.put("bytecode", List.of("-c", "-p", "-constants"));
        COMMAND_FLAGS.put("structure", List.of("-protected"));
        COMMAND_FLAGS.put("verbose", List.of("-verbose", "-p"));
    }

    private JsJavap() {
        // Utility class
    }

    // ========================================================================
    // Public API: disassemble
    // ========================================================================

    /**
     * Disassembles a Java class file into the requested output file.
     *
     * @param sourcePath path of the {@code .class} file, relative to the base directory
     * @param command {@code "bytecode"} (default), {@code "structure"} or {@code "verbose"}
     *                (case-insensitive; null/blank means default)
     * @param outputPath name of the generated disassembly file, relative to the base directory
     * @return a map with keys {@code outputFile}, {@code classFile}, {@code command},
     *         {@code javap}, {@code exitCode}, {@code bytesWritten} and {@code classVersion}
     * @throws JsUserRuntimeException if the source file is missing or not a Java class file, the
     *         command is unknown, javap is not available, the disassembly fails or any path is
     *         outside the base directory
     */
    public static Map<String, Object> disassemble(String sourcePath, String command, String outputPath) {
        Path source = JsFileSystem.resolveSafePath(sourcePath);
        if (!Files.isRegularFile(source)) {
            throw new JsUserRuntimeException("Source file not found: " + JsFileSystem.toRelative(source));
        }
        requireClassMagic(readHeader(source), JsFileSystem.toRelative(source));
        Path outputPathSafe = JsFileSystem.resolveSafePath(outputPath);
        return runJavap(source, outputPathSafe, command, false);
    }

    /**
     * Disassembles Java class bytes (e.g. an entry extracted with {@code archive}) into the
     * requested output file. The bytes are written to an intermediate {@code .class} file (the
     * output name stem) which is kept on success.
     *
     * @param data Java class bytes (must start with {@code 0xCAFEBABE})
     * @param command {@code "bytecode"} (default), {@code "structure"} or {@code "verbose"}
     *                (case-insensitive; null/blank means default)
     * @param outputPath name of the generated disassembly file, relative to the base directory
     * @return a map with keys {@code outputFile}, {@code classFile}, {@code command},
     *         {@code javap}, {@code exitCode}, {@code bytesWritten} and {@code classVersion}
     * @throws IllegalArgumentException if {@code data} is null
     * @throws JsUserRuntimeException if the data is empty/too large or not a Java class file, the
     *         command is unknown, javap is not available, the disassembly fails or any path is
     *         outside the base directory
     */
    public static Map<String, Object> disassemble(byte[] data, String command, String outputPath) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        if (data.length == 0) {
            throw new JsUserRuntimeException("Source data is empty; nothing to disassemble.");
        }
        if (data.length > MAX_INPUT_BYTES) {
            throw new JsUserRuntimeException("Source data is too large (" + data.length
                    + " bytes; limit is " + MAX_INPUT_BYTES + " bytes).");
        }
        // Validate the output direction before writing any intermediate file.
        Path outputPathSafe = JsFileSystem.resolveSafePath(outputPath);
        requireClassMagic(data, "input bytes");
        Path classPath = deriveClassPath(outputPathSafe);
        writeClassFile(classPath, data);
        return runJavap(classPath, outputPathSafe, command, true);
    }

    /**
     * Writes a byte array to an intermediate class-file path (creating parent directories).
     *
     * @param classPath target path (already validated)
     * @param data bytes to write
     * @throws JsUserRuntimeException if an I/O error occurs
     */
    private static void writeClassFile(Path classPath, byte[] data) {
        try {
            Path parent = classPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(classPath, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException e) {
            LOG.error("Failed to write intermediate class file: {}", classPath, e);
            throw new JsUserRuntimeException(
                    "Failed to create intermediate class file '" + JsFileSystem.toRelative(classPath) + "'.", e);
        }
    }

    // ========================================================================
    // Disassembly core
    // ========================================================================

    /**
     * Runs javap on the given class file and writes the output to the output path.
     *
     * @param classPath resolved path of the class file (genuine or generated intermediate file)
     * @param outputPath resolved path of the disassembly output
     * @param command requested command (see {@link #resolveFlags(String)})
     * @param temporaryClass whether the class path is a generated file that should be removed
     *                       together with a partial output on failure
     * @return the result map for the caller
     */
    private static Map<String, Object> runJavap(Path classPath, Path outputPath, String command,
            boolean temporaryClass) {
        boolean success = false;
        try {
            Path parent = outputPath.getParent();
            if (parent != null) {
                try {
                    Files.createDirectories(parent);
                } catch (IOException e) {
                    LOG.error("Failed to create output directory: {}", parent, e);
                    throw new JsUserRuntimeException("Failed to create output directory for '"
                            + JsFileSystem.toRelative(outputPath) + "'.", e);
                }
            }
            List<String> flags = resolveFlags(command);
            String normalizedCommand = normalizeCommand(command);
            Path toolPath = findJavap();
            if (toolPath == null) {
                throw new JsUserRuntimeException("javap was not found on PATH or in java.home/bin. "
                        + "javap is part of a JDK (not a plain JRE); install a JDK or add its bin "
                        + "directory to the PATH.");
            }

            List<String> args = new ArrayList<>();
            args.add(toolPath.toString());
            args.addAll(flags);
            args.add(classPath.toString());

            runJavapProcess(args, outputPath);

            long bytesWritten;
            try {
                bytesWritten = Files.size(outputPath);
            } catch (IOException e) {
                bytesWritten = 0L;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("outputFile", JsFileSystem.toRelative(outputPath));
            result.put("classFile", JsFileSystem.toRelative(classPath));
            result.put("command", normalizedCommand);
            result.put("javap", "javap");
            result.put("exitCode", 0);
            result.put("bytesWritten", bytesWritten);
            result.put("classVersion", readClassVersion(classPath));
            success = true;
            return result;
        } finally {
            // Cleanup only on failure - in a finally block, so that even unexpected Throwables
            // (e.g. OutOfMemoryError) leave no partial output and no intermediate file behind.
            // On success the disassembly output and the generated intermediate class file are
            // kept for the caller.
            if (!success) {
                deleteQuietly(outputPath);
                if (temporaryClass) {
                    deleteQuietly(classPath);
                }
            }
        }
    }

    /**
     * Starts the javap process (argument list, no shell), redirects stdout into the output file
     * and hands a non-zero exit code or a timeout back as a {@link JsUserRuntimeException}.
     *
     * @param args explicit process arguments (command first)
     * @param outputPath output file receiving javap's stdout
     */
    private static void runJavapProcess(List<String> args, Path outputPath) {
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectOutput(ProcessBuilder.Redirect.to(outputPath.toFile()));
        // stderr is kept as a pipe; javap writes little to stderr on success.
        pb.redirectErrorStream(false);
        LOG.info("Running javap: {}", args);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            LOG.error("Failed to start javap: {}", args.get(0), e);
            throw new JsUserRuntimeException("Failed to start the javap tool '" + args.get(0) + "'.", e);
        }
        try {
            boolean finished = process.waitFor(JAVAP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new JsUserRuntimeException("javap timed out after "
                        + JAVAP_TIMEOUT_SECONDS + " seconds. The class file may be too large or "
                        + "not a file that javap can process.");
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                String stderr = readStderr(process, outputPath);
                LOG.error("javap failed (exit code {}) for {}: {}", exitCode, outputPath, stderr);
                String detail = stderr.isBlank() ? "" : " Details: " + stderr.trim();
                throw new JsUserRuntimeException("javap failed for '"
                        + JsFileSystem.toRelative(outputPath) + "' (exit code " + exitCode + ")."
                        + detail);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new JsUserRuntimeException("Interrupted while waiting for javap.", e);
        }
    }

    /**
     * Reads up to {@link #MAX_STDERR_BYTES} bytes from the process' stderr stream.
     *
     * @param process the finished javap process
     * @param outputPath output path (only used for logging)
     * @return stderr snippet (UTF-8), may be empty
     */
    private static String readStderr(Process process, Path outputPath) {
        try (InputStream err = process.getErrorStream()) {
            byte[] bytes = err.readNBytes(MAX_STDERR_BYTES);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("Failed to read stderr of javap for {}", outputPath, e);
            return "";
        }
    }

    // ========================================================================
    // Command mapping / tool resolution
    // ========================================================================

    /**
     * Maps a command name to the fixed javap flag set (see {@link #COMMAND_FLAGS}).
     *
     * @param command command name (case-insensitive); blank means the default command
     * @return the fixed flag list
     * @throws JsUserRuntimeException if the command is unknown
     */
    static List<String> resolveFlags(String command) {
        List<String> flags = COMMAND_FLAGS.get(normalizeCommand(command));
        if (flags == null) {
            throw new JsUserRuntimeException("Unknown command '" + command + "'. Supported commands: "
                    + String.join(", ", COMMAND_FLAGS.keySet()));
        }
        return flags;
    }

    /**
     * Normalizes a command name (null/blank maps to the default command).
     *
     * @param command command name
     * @return the normalized command name
     * @throws JsUserRuntimeException if the command is unknown
     */
    private static String normalizeCommand(String command) {
        if (command == null || command.isBlank()) {
            return DEFAULT_COMMAND;
        }
        String normalized = command.trim().toLowerCase(Locale.ROOT);
        if (!COMMAND_FLAGS.containsKey(normalized)) {
            throw new JsUserRuntimeException("Unknown command '" + command + "'. Supported commands: "
                    + String.join(", ", COMMAND_FLAGS.keySet()));
        }
        return normalized;
    }

    /**
     * Locates the {@code javap} executable: first on the {@code PATH}, then as a fallback in
     * {@code java.home/bin} (javap ships with every JDK that runs this server).
     *
     * @return the path of the executable, or null if not found
     */
    static Path findJavap() {
        Path onPath = JsFileSystem.findOnPath("javap");
        if (onPath != null) {
            return onPath;
        }
        String javaHome = System.getProperty("java.home", "");
        if (!javaHome.isBlank()) {
            for (String candidate : new String[]{"javap", "javap.exe"}) {
                Path exe = Paths.get(javaHome, "bin", candidate);
                if (Files.isRegularFile(exe) && Files.isExecutable(exe)) {
                    return exe;
                }
            }
        }
        return null;
    }

    // ========================================================================
    // Class-file detection / version
    // ========================================================================

    /**
     * Verifies that the given bytes start with the Java class magic {@code 0xCAFEBABE}.
     *
     * @param header the first bytes of the input
     * @param what source description for the error message (relative path or "input bytes")
     * @throws JsUserRuntimeException if the magic is missing
     */
    private static void requireClassMagic(byte[] header, String what) {
        if (header.length < CLASS_MAGIC.length) {
            throw new JsUserRuntimeException("Not a valid Java class file: " + what
                    + " (too short). javap only processes .class files.");
        }
        for (int i = 0; i < CLASS_MAGIC.length; i++) {
            if (header[i] != CLASS_MAGIC[i]) {
                throw new JsUserRuntimeException("Not a valid Java class file: " + what
                        + " (the class magic 0xCAFEBABE is missing). javap only processes .class.");
            }
        }
    }

    /**
     * Reads the first bytes of a file.
     *
     * @param file the resolved file path
     * @return the first bytes (at most 8)
     * @throws JsUserRuntimeException if the header cannot be read
     */
    private static byte[] readHeader(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return in.readNBytes(8);
        } catch (IOException e) {
            LOG.error("Failed to read file header: {}", file, e);
            throw new JsUserRuntimeException(
                    "Failed to read file header of " + JsFileSystem.toRelative(file) + ".", e);
        }
    }

    /**
     * Reads the class-file version ({@code major.minor}) from the given class file.
     *
     * @param classPath resolved class-file path
     * @return a formatted version string (e.g. {@code "61.0 (Java 17)"}), or null if the header
     *         is too short
     */
    private static String readClassVersion(Path classPath) {
        byte[] header = readHeader(classPath);
        if (header.length < 8) {
            return null;
        }
        int minor = readUInt16BE(header, 4);
        int major = readUInt16BE(header, 6);
        return formatClassVersion(major, minor);
    }

    /**
     * Formats a class-file version ({@code major.minor}) together with the Java release.
     * Mapping: 45..48 &rarr; Java 1.1..1.4, 49 &rarr; Java 5, 52 &rarr; Java 8, 61 &rarr; Java 17.
     *
     * @param major major version (e.g. 61 for Java 17)
     * @param minor minor version
     * @return a formatted version string (e.g. {@code "61.0 (Java 17)"})
     */
    static String formatClassVersion(int major, int minor) {
        String release;
        if (major >= 49) {
            release = "Java " + (major - 44);
        } else if (major >= 45) {
            release = "Java 1." + (major - 44);
        } else {
            release = "Java 1.0";
        }
        return major + "." + minor + " (" + release + ")";
    }

    /**
     * Reads a 16-bit unsigned big-endian value (class files are big-endian).
     *
     * @param b byte array (at least {@code offset + 2})
     * @param offset start index
     * @return the unsigned 16-bit value
     */
    private static int readUInt16BE(byte[] b, int offset) {
        return ((b[offset] & 0xFF) << 8) | (b[offset + 1] & 0xFF);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /**
     * Derives the intermediate class-file path from the output path by replacing the extension
     * with {@code .class} (or appending {@code .class} if the name has no dot).
     *
     * @param outputPath the resolved output path
     * @return the sibling class-file path
     */
    private static Path deriveClassPath(Path outputPath) {
        String name = outputPath.getFileName().toString();
        Path parent = outputPath.getParent();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String className = stem + ".class";
        return parent != null ? parent.resolve(className) : Paths.get(className);
    }

    /**
     * Deletes a file quietly (ignoring failures; only logged).
     *
     * @param path path to delete
     */
    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOG.warn("Failed to delete {}: {}", path, e.getMessage());
        }
    }

    // ========================================================================
    // Help / documentation
    // ========================================================================

    /**
     * Returns a help text describing the JS javap API with usage examples.
     *
     * @return help text as a multi-line string
     */
    public static String help() {
        return """
                JS Javap API (namespace 'javap')
                ================================

                Disassembles Java class files (.class) with the javap tool of the JDK.
                The source is either a
                file path relative to the project base directory or a byte array (e.g. an entry
                extracted with archive.*).

                javap.disassemble(source, command, outputPath)
                    source       - .class file path (string) or bytes (Uint8Array / array 0-255).
                                   The input must start with the class magic 0xCAFEBABE; native
                                   binaries are rejected.
                    command      - 'bytecode' (default): javap -c -p -constants (full bytecode,
                                   incl. private members and constant values)
                                   'structure'          : javap -protected (public/protected API)
                                   'verbose'            : javap -verbose -p (constant pool,
                                                          flags, StackMapTable, line numbers)
                    outputPath   - generated disassembly file (e.g. "Foo.bytecode.txt"),
                                   relative to the project base directory.

                Returns a map: {outputFile, classFile, command, javap, exitCode, bytesWritten,
                classVersion} - 'classVersion' is e.g. "61.0 (Java 17)". For a byte-array source
                an intermediate '<stem>.class' is created next to the output file and kept on
                success.

                Examples:
                    javap.disassemble("classes/Foo.class", "bytecode", "Foo.bytecode.txt");
                    javap.disassemble(classBytes, "structure", "Foo.structure.txt");
                    javap.disassemble(classBytes, null, "Foo.bytecode.txt");  // default: bytecode
                    fs.readFile("Foo.bytecode.txt", ...)  // afterwards read with fs.*

                Requirements / notes:
                    javap is part of a JDK (not a plain JRE). It is looked up on the PATH and,
                    as a fallback, in java.home/bin of the running server JVM. The command is
                    started without a shell and only with fixed flags (no caller arguments), and
                    all paths are validated like in fs.* (no '..', no paths outside the base).
                """;
    }
}
