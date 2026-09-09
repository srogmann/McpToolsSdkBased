package org.rogmann.mcp2sdk.js;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.rogmann.mcp2sdk.WorkProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controlled access to a directory and its subdirectories for JavaScript.
 * <p>
 * This toolbox provides a synchronous, Node.js-{@code fs}-like API for text files:
 * reading, writing, listing, inspecting and streaming line by line.
 * All paths are relative to the project base directory
 * (system property {@code IDE_PROJECT_DIR}).
 * </p>
 *
 * <h3>Security</h3>
 * <ul>
 *   <li>Paths are resolved relative to the project base directory.</li>
 *   <li>Parent-directory traversal ({@code ..}) and absolute paths outside the base are rejected.</li>
 *   <li>Optionally configured add-on directories (see {@link org.rogmann.mcp2sdk.WorkProject},
 *       system property {@code IDE_PROJECT_ADDON_DIR.N}) are addressed with a
 *       {@code /addonName/...} prefix; they are validated like the base directory (containment
 *       and symbolic-link checks per mount).</li>
 *   <li>Symbolic links are only followed if the real path stays inside the selected directory.</li>
 *   <li>Results and error messages contain only relative (or add-on-prefixed) paths, never absolute paths.</li>
 * </ul>
 *
 * <h3>Streaming large files</h3>
 * <p>
 * {@link #createLineReader(String)} returns a {@link LineReader} that reads a file
 * line by line, so files larger than the LLM context window can be processed in chunks.
 * </p>
 *
 * <h3>Character sets</h3>
 * <p>
 * The text-oriented methods ({@link #readFile(String)}, {@link #readLines(String, int, int)},
 * {@link #createLineReader(String)}, {@link #writeFile(String, String)},
 * {@link #appendFile(String, String)}) default to UTF-8 and accept an explicit
 * {@code encoding} (e.g. {@code ISO-8859-1}, {@code CP-1252}) plus an {@code errors} policy.
 * Decoding is <em>strict by default</em>: malformed input is reported with the offending byte
 * offset instead of being silently replaced by U+FFFD, because silent replacement corrupts
 * round-trips ({@code readFile} -&gt; {@code writeFile}). Use {@link #detectCharset(String)} to
 * get a heuristic candidate list and {@link #decode(byte[], String)} / {@link #encode(String, String)}
 * to convert byte arrays without hand-written per-byte loops.
 * </p>
 * <p>
 * Byte-exact methods ({@link #readBytes}, {@link #readHex}, {@link #writeBytes},
 * {@link #createBlockReader}) never interpret anything and stay the canonical way to handle
 * binary data; a BOM is never added and never removed by the text API.
 * </p>
 */
public class JsFileSystem {

    private static final Logger LOG = LoggerFactory.getLogger(JsFileSystem.class);

    /** Default number of lines returned by {@link #readLines(String, int, int)} if endLine is omitted. */
    public static final int DEFAULT_MAX_LINES = 500;

    /** Default block size (bytes) for {@link #createBlockReader(String, int)}. */
    public static final int DEFAULT_BLOCK_SIZE = 64 * 1024;

    /** Upper bound for a single {@link #readBytes(String, long, int)} / {@link #readHex(String, long, int)} call. */
    public static final int MAX_READ_BYTES = 1024 * 1024;

    private JsFileSystem() {
        // Utility class
    }

    // ========================================================================
    // Executable lookup (PATH)
    // ========================================================================

    /**
     * Locates an executable on the {@code PATH} environment variable.
     *
     * @param command the command name (e.g. {@code javap})
     * @return the path of the executable, or null if not found
     */
    public static Path findOnPath(String command) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank()) {
            return null;
        }
        for (String dir : pathEnv.split(Pattern.quote(File.pathSeparator))) {
            if (dir == null || dir.isBlank()) {
                continue;
            }
            Path base = Paths.get(dir);
            for (String candidate : new String[]{command, command + ".exe"}) {
                Path exe = base.resolve(candidate);
                if (Files.isRegularFile(exe) && Files.isExecutable(exe)) {
                    return exe;
                }
            }
        }
        return null;
    }

    // ========================================================================
    // Path resolution / security
    // ========================================================================

    /**
     * Returns the project base directory.
     * @return the resolved base path
     * @throws JsUserRuntimeException if {@code IDE_PROJECT_DIR} is not available
     */
    private static Path getBasePath() {
        Map<String, Object> result = new HashMap<>();
        WorkProject workProject = WorkProject.lookupProject(result);
        if (workProject == null) {
            String error = (String) result.get("error");
            throw new JsUserRuntimeException(
                    "No project base directory available. "
                    + (error != null ? error : ""));
        }
        Path projectBaseDir = workProject.projectBaseDir();
        LOG.debug("Using project base directory as JS file system base: {}", projectBaseDir);
        return projectBaseDir;
    }

    /**
     * Resolves a file path against the selected directory and validates it.
     * <p>
     * A leading {@code /addonName} segment (e.g. {@code /repository/...}) addresses an
     * optionally configured add-on directory (see {@link WorkProject}); any other path is
     * resolved relative to the project base directory. Rejects parent-directory traversal
     * ({@code ..}), unknown absolute prefixes and symbolic links whose real path leaves the
     * selected directory.
     * </p>
     * @param filePath path relative to the base directory (or {@code /addonName/...})
     * @return the resolved absolute path
     * @throws JsUserRuntimeException if the path is invalid or outside the permitted directory
     */
    public static Path resolveSafePath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new JsUserRuntimeException("File path must not be empty.");
        }
        // Optional add-on mount prefix: "/addonName[/...]" addresses an add-on root.
        String rest = filePath;
        Path mountRoot = getBasePath();
        if (filePath.startsWith("/")) {
            String withoutLeading = filePath.substring(1);
            int slash = withoutLeading.indexOf('/');
            String mountName = slash < 0 ? withoutLeading : withoutLeading.substring(0, slash);
            if (mountName.isEmpty()) {
                throw new JsUserRuntimeException("Access denied: invalid path: " + filePath);
            }
            Path addonRoot = WorkProject.addonPath(mountName);
            if (addonRoot == null) {
                throw unknownAddonFolder(mountName);
            }
            mountRoot = addonRoot;
            rest = slash < 0 ? "" : withoutLeading.substring(slash + 1);
        }
        // Normalize to prevent path traversal.
        Path resolved = mountRoot.resolve(rest.isEmpty() ? "." : rest).normalize();
        if (!resolved.startsWith(mountRoot)) {
            LOG.warn("Path traversal attempt detected: filePath='{}' resolved to '{}'", filePath, resolved);
            throw new JsUserRuntimeException(
                    "Access denied: the specified path is not within the permitted directory.");
        }
        // Symlink check: resolve the nearest existing ancestor and verify it stays in the mount.
        try {
            Path realRoot = mountRoot.toRealPath();
            Path existing = resolved;
            while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                existing = existing.getParent();
            }
            if (existing == null) {
                throw new JsUserRuntimeException("Access denied: cannot resolve path: " + filePath);
            }
            Path realExisting = existing.toRealPath();
            if (!realExisting.startsWith(realRoot)) {
                LOG.warn("Symbolic link escapes base directory: filePath='{}'", filePath);
                throw new JsUserRuntimeException(
                        "Access denied: symbolic links outside the permitted directory are not allowed.");
            }
        } catch (IOException e) {
            throw new JsUserRuntimeException("Access denied: cannot resolve path: " + filePath, e);
        }
        return resolved;
    }

    /**
     * Returns the given path relative to its mount with forward slashes.
     * <p>
     * Paths inside the project base directory are rendered without a prefix (e.g.
     * {@code src/main/...}); paths inside an add-on directory are rendered with their
     * mount prefix (e.g. {@code /repository/org/slf4j/...}) so that results can be passed
     * back as inputs. Never exposes an absolute path to the caller.
     * </p>
     * @param path an absolute path inside a permitted directory
     * @return relative path (or ".". for the base directory itself)
     */
    public static String toRelative(Path path) {
        Path base = getBasePath();
        // Project-base paths keep the previous, prefix-less representation.
        if (path.startsWith(base)) {
            String s = base.relativize(path).toString().replace('\\', '/');
            return s.isEmpty() ? "." : s;
        }
        // Add-on paths are rendered with their mount prefix.
        for (Map.Entry<String, Path> mount : addonMounts().entrySet()) {
            Path addonRoot = mount.getValue();
            if (path.startsWith(addonRoot)) {
                String s = addonRoot.relativize(path).toString().replace('\\', '/');
                String prefix = "/" + mount.getKey();
                return s.isEmpty() ? prefix : prefix + "/" + s;
            }
        }
        // Safety fallback (should not happen after resolveSafePath).
        String s = base.relativize(path).toString().replace('\\', '/');
        return s.isEmpty() ? "." : s;
    }

    /**
     * Returns the configured add-on mounts as {@code name -&gt; canonical root}.
     * @return add-on mounts (empty if none configured)
     */
    private static Map<String, Path> addonMounts() {
        Map<String, Path> mounts = new LinkedHashMap<>();
        for (String name : WorkProject.addonNames()) {
            Path root = WorkProject.addonPath(name);
            if (root != null) {
                mounts.put(name, root);
            }
        }
        return mounts;
    }

    /**
     * Builds the exception for an unknown add-on mount prefix.
     * @param mountName the unknown first path segment
     * @return an access-denied exception
     */
    private static JsUserRuntimeException unknownAddonFolder(String mountName) {
        if (WorkProject.hasAddonDirectories()) {
            return new JsUserRuntimeException(
                    "Unknown add-on folder '/" + mountName + "'. Available add-on folders: " + WorkProject.addonNames());
        }
        return new JsUserRuntimeException(
                "Access denied: absolute paths are not permitted (no add-on folders configured).");
    }

    // ========================================================================
    // Character sets (encoding)
    // ========================================================================

    /** Canonical name of the default character set used by the text-oriented API. */
    public static final String DEFAULT_ENCODING = "UTF-8";

    /** Error policy: report malformed / unmappable input as an error (default). */
    public static final String ERRORS_STRICT = "strict";

    /** Error policy: replace malformed bytes / unmappable characters with U+FFFD. */
    public static final String ERRORS_REPLACE = "replace";

    /** Error policy: drop malformed bytes / unmappable characters silently. */
    public static final String ERRORS_IGNORE = "ignore";

    /** Policies accepted by the {@code errors} parameter. */
    public static final List<String> ERROR_POLICIES = List.of(ERRORS_STRICT, ERRORS_REPLACE, ERRORS_IGNORE);

    /** Default number of bytes inspected by {@link #detectCharset(String)}. */
    public static final int DEFAULT_SNIFF_BYTES = 64 * 1024;

    /** Maximum number of characters returned per candidate sample by {@link #detectCharset(String)}. */
    private static final int SNIFF_SAMPLE_CHARS = 160;

    /** Byte values that are undefined in CP-1252 (a strong hint for raw ISO-8859-1 or binary data). */
    private static final int[] CP1252_UNDEFINED = {0x81, 0x8D, 0x8F, 0x90, 0x9D};

    /**
     * Penalty added to a UTF-16 candidate whose byte order does not match the NUL alignment.
     * Both byte orders decode ASCII text without illegal characters, so this is what breaks the tie.
     */
    private static final int UTF16_ALIGN_PENALTY = 50;

    /** Names mentioned when an encoding is rejected. */
    private static final List<String> COMMON_ENCODINGS = List.of(
            "UTF-8", "UTF-16", "UTF-16LE", "UTF-16BE", "UTF-32", "ISO-8859-1 (latin1)",
            "CP-1252 (windows-1252)", "US-ASCII", "CP-437", "CP-850", "Shift_JIS", "EUC-JP",
            "GB18030", "KOI8-R", "IBM037");

    /**
     * Friendly names accepted by the {@code encoding} parameter
     * (normalized name -&gt; canonical JVM charset name).
     * <p>
     * Names that are not listed are passed to {@link Charset#forName(String)}, so any character
     * set known to the JVM works as well. Normalization drops case, spaces and
     * {@code -}, {@code _}, {@code .}, so {@code cp1252}, {@code CP-1252} and
     * {@code Windows_1252} are the same.
     * </p>
     */
    private static final Map<String, String> ENCODING_ALIASES = encodingAliases();

    /** Pattern removing the separators of an encoding name during normalization. */
    private static final Pattern ENCODING_NAME_SEPARATORS = Pattern.compile("[\\-_. ]");

    /**
     * Builds the alias table of the {@code encoding} parameter.
     * @return alias map (normalized name -&gt; canonical charset name)
     */
    private static Map<String, String> encodingAliases() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("UTF8", "UTF-8");
        m.put("UTF16", "UTF-16");
        m.put("UCS2", "UTF-16");
        m.put("UNICODE", "UTF-16");
        m.put("UTF16LE", "UTF-16LE");
        m.put("UTF16BE", "UTF-16BE");
        m.put("UTF32", "UTF-32");
        m.put("UCS4", "UTF-32");
        m.put("UTF32LE", "UTF-32LE");
        m.put("UTF32BE", "UTF-32BE");
        m.put("LATIN1", "ISO-8859-1");
        m.put("LATIN", "ISO-8859-1");
        m.put("ISO88591", "ISO-8859-1");
        m.put("88591", "ISO-8859-1");
        m.put("CP819", "ISO-8859-1");
        m.put("CP1252", "windows-1252");
        m.put("WINDOWS1252", "windows-1252");
        m.put("WIN1252", "windows-1252");
        m.put("ANSI", "windows-1252");
        m.put("ANSI1252", "windows-1252");
        m.put("ASCII", "US-ASCII");
        m.put("USASCII", "US-ASCII");
        m.put("ISO646", "US-ASCII");
        m.put("SJIS", "Shift_JIS");
        m.put("SHIFTJIS", "Shift_JIS");
        m.put("EUCJP", "EUC_JP");
        m.put("GB2312", "GB18030");
        m.put("MACINTOSH", "MacRoman");
        m.put("UTF7", "UTF-7");
        return Map.copyOf(m);
    }

    /**
     * Normalizes an encoding name for alias lookup (trimmed, uppercase, separators removed).
     * @param encoding user-supplied encoding name
     * @return normalized key
     */
    private static String normalizedEncodingName(String encoding) {
        return ENCODING_NAME_SEPARATORS.matcher(encoding.trim().toUpperCase(Locale.ROOT)).replaceAll("");
    }

    /**
     * Resolves the {@code encoding} argument of the text API to a {@link Charset}.
     * <p>
     * {@code null} and blank mean UTF-8; {@code utf8}, {@code latin1}, {@code cp1252} /
     * {@code windows-1252} / {@code ANSI}, {@code ascii} and similar spellings are accepted as
     * aliases. Any other name known to the JVM is accepted unchanged.
     * </p>
     * @param encoding encoding name (may be null for the default)
     * @return the resolved charset, never null
     * @throws IllegalArgumentException if the name is unknown or unsupported
     */
    static Charset resolveCharset(String encoding) {
        if (encoding == null || encoding.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        String alias = ENCODING_ALIASES.get(normalizedEncodingName(encoding));
        String candidate = alias != null ? alias : encoding.trim();
        try {
            return Charset.forName(candidate);
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            throw new IllegalArgumentException("Unsupported encoding '" + encoding + "'. Common values: "
                    + String.join(", ", COMMON_ENCODINGS)
                    + ". Any character set known to this JVM is accepted (besides UTF-8, UTF-16*,"
                    + " UTF-32 and ISO-8859-1 that depends on the installed charset providers);"
                    + " see fs.help().");
        }
    }

    /**
     * Normalizes the {@code errors} parameter of the text API.
     * @param errors policy name (may be null for {@link #ERRORS_STRICT}); the aliases
     *               {@code report}/{@code fail}/{@code error} (strict) and {@code skip}/{@code drop}
     *               (ignore) are accepted
     * @return one of {@link #ERRORS_STRICT}, {@link #ERRORS_REPLACE}, {@link #ERRORS_IGNORE}
     * @throws IllegalArgumentException for an unknown policy
     */
    static String normalizeErrorPolicy(String errors) {
        if (errors == null || errors.isBlank()) {
            return ERRORS_STRICT;
        }
        switch (errors.trim().toLowerCase(Locale.ROOT)) {
            case "strict", "report", "fail", "error":
                return ERRORS_STRICT;
            case "replace", "substitute", "fffd":
                return ERRORS_REPLACE;
            case "ignore", "skip", "drop":
                return ERRORS_IGNORE;
            default:
                throw new IllegalArgumentException("Unsupported errors policy '" + errors + "'. Valid values: "
                        + String.join(", ", ERROR_POLICIES) + " (default: " + ERRORS_STRICT + ").");
        }
    }

    /**
     * Maps the {@code errors} parameter to a {@link CodingErrorAction}.
     * @param errors policy name (may be null)
     * @return the corresponding action
     */
    private static CodingErrorAction errorAction(String errors) {
        return switch (normalizeErrorPolicy(errors)) {
            case ERRORS_REPLACE -> CodingErrorAction.REPLACE;
            case ERRORS_IGNORE -> CodingErrorAction.IGNORE;
            default -> CodingErrorAction.REPORT;
        };
    }

    /**
     * Creates a decoder for the given charset and error policy.
     * @param cs charset to use
     * @param errors error policy (may be null for strict)
     * @return a new decoder
     */
    private static CharsetDecoder newDecoder(Charset cs, String errors) {
        CodingErrorAction action = errorAction(errors);
        return cs.newDecoder().onMalformedInput(action).onUnmappableCharacter(action);
    }

    /**
     * Opens a buffered reader that decodes a file with the given charset and error policy.
     * @param path file to read (already resolved)
     * @param cs charset to use
     * @param errors error policy (may be null for strict)
     * @return a buffered reader for the file
     * @throws IOException if the file cannot be opened
     */
    private static BufferedReader newReader(Path path, Charset cs, String errors) throws IOException {
        return new BufferedReader(new InputStreamReader(Files.newInputStream(path), newDecoder(cs, errors)));
    }

    /**
     * Decodes bytes with the given charset and error policy.
     * <p>
     * In strict mode a malformed sequence is reported as {@link IllegalArgumentException}
     * including the byte offset and a hex window around it, instead of silently becoming U+FFFD.
     * </p>
     * @param data bytes to decode (must not be null)
     * @param cs charset to use
     * @param errors error policy (may be null for strict)
     * @param pathForError relative path used in error messages (may be null)
     * @return decoded text
     * @throws IllegalArgumentException on malformed input in strict mode
     */
    static String decode(byte[] data, Charset cs, String errors, String pathForError) {
        CharsetDecoder decoder = newDecoder(cs, errors);
        ByteBuffer in = ByteBuffer.wrap(data);
        CharBuffer out = CharBuffer.allocate(Math.max(64, Math.min(8192, data.length + 4)));
        StringBuilder sb = new StringBuilder((int) Math.min(data.length, 1L << 20));
        CoderResult r;
        do {
            r = decoder.decode(in, out, true);
            sb.append(out.array(), 0, out.position());
            out.clear();
            // With CodingErrorAction.REPORT the streaming decode() does not throw - it reports the
            // problem as a CoderResult and leaves in.position() at the start of the bad sequence.
            if (r.isMalformed() || r.isUnmappable()) {
                throw malformedInput(data, in.position(), cs, pathForError);
            }
        } while (r.isOverflow());
        r = decoder.flush(out);
        sb.append(out.array(), 0, out.position());
        out.clear();
        while (r.isOverflow()) {
            r = decoder.flush(out);
            sb.append(out.array(), 0, out.position());
            out.clear();
        }
        // A file that ends inside an incomplete multi-byte sequence is reported by flush(), not by
        // decode(): without this check the trailing bytes would silently disappear from the result.
        if (r.isMalformed() || r.isUnmappable()) {
            throw malformedInput(data, tailOffset(data.length, r), cs, pathForError);
        }
        return sb.toString();
    }

    /**
     * Computes the offset of an incomplete sequence at the end of the input.
     * <p>
     * {@code flush()} reports an unterminable tail with the number of bytes involved, so the
     * offset is {@code length - r.length()}.
     * </p>
     * @param length total length of the input (bytes when decoding, characters when encoding)
     * @param r the malformed / unmappable coder result of {@code flush()}
     * @return the 0-based offset of the incomplete tail
     */
    private static int tailOffset(int length, CoderResult r) {
        return Math.max(0, length - Math.max(1, r.length()));
    }

    /**
     * Encodes text with the given charset and error policy.
     * @param content text to encode (null is treated as empty)
     * @param cs charset to use
     * @param errors error policy (may be null for strict)
     * @param pathForError relative path used in error messages (may be null)
     * @return encoded bytes
     * @throws IllegalArgumentException if a character cannot be represented in strict mode
     */
    static byte[] encode(String content, Charset cs, String errors, String pathForError) {
        String text = content != null ? content : "";
        CharsetEncoder encoder = cs.newEncoder()
                .onMalformedInput(errorAction(errors))
                .onUnmappableCharacter(errorAction(errors));
        CharBuffer in = CharBuffer.wrap(text);
        ByteArrayOutputStream sink = new ByteArrayOutputStream(
                (int) Math.min((long) text.length() * 4L + 8L, 1L << 22));
        ByteBuffer out = ByteBuffer.allocate(Math.max(64, Math.min(8192, text.length() * 4 + 8)));
        CoderResult r;
        do {
            r = encoder.encode(in, out, true);
            out.flip();
            sink.write(out.array(), 0, out.remaining());
            out.clear();
            // Same as in decode(): with REPORT the problem arrives as a CoderResult, and in.position()
            // is the index of the character that has no representation in the target charset.
            if (r.isMalformed() || r.isUnmappable()) {
                throw unmappableCharacter(text, in.position(), cs, pathForError);
            }
        } while (r.isOverflow());
        r = encoder.flush(out);
        out.flip();
        sink.write(out.array(), 0, out.remaining());
        out.clear();
        while (r.isOverflow()) {
            r = encoder.flush(out);
            out.flip();
            sink.write(out.array(), 0, out.remaining());
            out.clear();
        }
        // Unpaired surrogate at the end of the text: reported by flush(), not by encode().
        if (r.isMalformed() || r.isUnmappable()) {
            throw unmappableCharacter(text, tailOffset(text.length(), r), cs, pathForError);
        }
        return sink.toByteArray();
    }

    /**
     * Decodes a byte array (e.g. a JS {@code Uint8Array}) as UTF-8 text.
     * @param data bytes to decode
     * @return decoded text
     * @throws IllegalArgumentException on malformed input
     */
    public static String decode(byte[] data) {
        return decode(data, DEFAULT_ENCODING, null);
    }

    /**
     * Decodes a byte array with an explicit charset (strict).
     * <p>
     * This is the supported way to turn raw bytes from {@link #readBytes(String, long, int)} into
     * text; hand-written {@code String.fromCharCode} loops are not needed (and are easy to get
     * wrong for multi-byte charsets).
     * </p>
     * @param data bytes to decode (must not be null)
     * @param encoding charset name (null means UTF-8)
     * @return decoded text
     * @throws IllegalArgumentException on malformed input or an unknown charset
     */
    public static String decode(byte[] data, String encoding) {
        return decode(data, encoding, null);
    }

    /**
     * Decodes a byte array with an explicit charset and error policy.
     * @param data bytes to decode (must not be null)
     * @param encoding charset name (null means UTF-8)
     * @param errors error policy: {@code strict} (default), {@code replace} or {@code ignore}
     * @return decoded text
     * @throws IllegalArgumentException on malformed input in strict mode
     */
    public static String decode(byte[] data, String encoding, String errors) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        Charset cs = resolveCharset(encoding);
        String policy = normalizeErrorPolicy(errors);
        return decode(data, cs, policy, null);
    }

    /**
     * Decodes a hex string (as produced by {@link #readHex(String, long, int)}) with a charset.
     * @param hex hexadecimal digits (whitespace and {@code 0x} prefixes are ignored)
     * @param encoding charset name (null means UTF-8)
     * @return decoded text
     * @throws IllegalArgumentException for malformed input or an invalid hex string
     */
    public static String decodeHex(String hex, String encoding) {
        return decode(fromHex(hex), encoding, null);
    }

    /**
     * Encodes a string as UTF-8 bytes.
     * @param text text to encode (null is treated as empty)
     * @return UTF-8 bytes
     */
    public static byte[] encode(String text) {
        return encode(text, DEFAULT_ENCODING, null);
    }

    /**
     * Encodes a string with an explicit charset (strict).
     * @param text text to encode (null is treated as empty)
     * @param encoding charset name (null means UTF-8)
     * @return encoded bytes
     * @throws IllegalArgumentException if a character cannot be represented
     */
    public static byte[] encode(String text, String encoding) {
        return encode(text, encoding, null);
    }

    /**
     * Encodes a string with an explicit charset and error policy.
     * @param text text to encode (null is treated as empty)
     * @param encoding charset name (null means UTF-8)
     * @param errors error policy: {@code strict} (default), {@code replace} or {@code ignore}
     * @return encoded bytes
     * @throws IllegalArgumentException if a character cannot be represented in strict mode
     */
    public static byte[] encode(String text, String encoding, String errors) {
        Charset cs = resolveCharset(encoding);
        String policy = normalizeErrorPolicy(errors);
        return encode(text, cs, policy, null);
    }

    /**
     * Parses a hex string into bytes (whitespace and leading {@code 0x} are ignored).
     * @param hex hexadecimal string
     * @return the decoded bytes
     * @throws IllegalArgumentException if the string is not valid hex
     */
    public static byte[] fromHex(String hex) {
        if (hex == null) {
            throw new IllegalArgumentException("hex must not be null");
        }
        String s = hex.replaceAll("0[xX]", "").replaceAll("\\s", "");
        if (s.length() % 2 != 0) {
            throw new IllegalArgumentException("hex string has an odd length (" + s.length()
                    + "); it must contain an even number of hex digits");
        }
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(2 * i), 16);
            int lo = Character.digit(s.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("invalid hex digit at index " + (2 * i) + " of the hex string");
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /**
     * Returns the byte offset of the first sequence that the charset cannot decode.
     * @param data bytes to check
     * @param cs charset to check against
     * @return the offset of the first malformed sequence, or -1 if the input is valid
     */
    private static int firstMalformed(byte[] data, Charset cs) {
        CharsetDecoder decoder = cs.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(data);
        CharBuffer out = CharBuffer.allocate(8192);
        CoderResult r;
        do {
            r = decoder.decode(in, out, true);
            out.clear();
            if (r.isMalformed() || r.isUnmappable()) {
                return in.position();
            }
        } while (r.isOverflow());
        r = decoder.flush(out);
        if (r.isMalformed() || r.isUnmappable()) {
            return tailOffset(data.length, r);
        }
        return -1;
    }

    /**
     * Builds the error for a malformed byte sequence (strict decoding).
     * @param data the decoded bytes
     * @param offset byte offset of the malformed sequence
     * @param cs charset that was used
     * @param pathForError relative path used in the message (may be null)
     * @return the exception to throw
     */
    private static IllegalArgumentException malformedInput(byte[] data, int offset, Charset cs, String pathForError) {
        int from = Math.max(0, offset - 8);
        int to = Math.min(data.length, offset + 24);
        StringBuilder hex = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (i > from) {
                hex.append(' ');
            }
            hex.append(hexByte(data[i]));
        }
        String where = pathForError != null ? " in " + pathForError : "";
        return new IllegalArgumentException("Invalid " + cs.name() + " sequence" + where + " at byte offset "
                + offset + " (bytes at 0x" + Integer.toHexString(from) + ": " + hex + ")."
                + " If the file uses another character set, pass it explicitly"
                + " (fs.readFile(path, 'CP-1252') or 'ISO-8859-1'), check fs.detectCharset(path),"
                + " pass errors 'replace' to read it anyway, or inspect the raw bytes with"
                + " fs.readHex(path, offset, length).");
    }

    /**
     * Builds the error for a character that cannot be represented (strict encoding).
     * @param text the encoded text
     * @param index character index of the offending character
     * @param cs charset that was used
     * @param pathForError relative path used in the message (may be null)
     * @return the exception to throw
     */
    private static IllegalArgumentException unmappableCharacter(String text, int index, Charset cs,
            String pathForError) {
        String where = pathForError != null ? " in " + pathForError : "";
        StringBuilder what = new StringBuilder();
        if (index >= 0 && index < text.length()) {
            char c = text.charAt(index);
            what.append(' ').append(printableChar(c))
                    .append(" (U+").append(String.format(Locale.ROOT, "%04X", (int) c)).append(')');
        }
        return new IllegalArgumentException("Text" + where + " contains a character without a " + cs.name()
                + " representation at char index " + index + what
                + ". Write UTF-8 (the default), pick a charset that can represent the text"
                + " (e.g. 'CP-1252' for Windows Latin-1 text), or pass errors 'replace'.");
    }

    /**
     * Formats a single byte as two lowercase hex digits.
     * @param b byte value
     * @return hex representation
     */
    private static String hexByte(byte b) {
        int v = b & 0xFF;
        return "" + HEX_DIGITS[v >>> 4] + HEX_DIGITS[v & 0x0F];
    }

    /**
     * Renders a character for an error message (non-printables as {@code \\uXXXX}).
     * @param c character to render
     * @return printable representation
     */
    private static String printableChar(char c) {
        if (c >= 0x20 && c < 0x7F) {
            return "'" + c + "'";
        }
        return "'\\u" + String.format(Locale.ROOT, "%04x", (int) c) + "'";
    }

    // ========================================================================
    // Character-set sniffing
    // ========================================================================

    /**
     * Inspects the beginning of a file and suggests a character set (heuristic).
     * <p>
     * The result reports the byte order mark, NUL / high-byte counts, whether the sample decodes
     * as valid UTF-8 (with the exact offset if not) and a ranked list of candidate charsets, each
     * with a printable sample so that the caller can <em>see</em> the difference instead of
     * guessing. The recommendation is a heuristic, not a proof: for pure 7-bit files every
     * ASCII-superset decodes identically, and CP-1252 and ISO-8859-1 differ only in
     * {@code 0x80-0x9F}.
     * </p>
     * @param filePath path relative to the base directory
     * @return analysis result (path, size, bom, nulBytes, highBytes, utf8, candidates,
     *         recommended, confidence, note, sample)
     * @throws JsUserRuntimeException if the file does not exist or an I/O error occurs
     */
    public static Map<String, Object> detectCharset(String filePath) {
        return detectCharset(filePath, DEFAULT_SNIFF_BYTES);
    }

    /**
     * Inspects the beginning of a file and suggests a character set (heuristic).
     * @param filePath path relative to the base directory
     * @param maxBytes number of bytes to inspect (values above {@link #MAX_READ_BYTES} are clamped)
     * @return analysis result (see {@link #detectCharset(String)})
     * @throws JsUserRuntimeException if the file does not exist or an I/O error occurs
     */
    public static Map<String, Object> detectCharset(String filePath, int maxBytes) {
        Path path = resolveSafePath(filePath);
        if (!Files.isRegularFile(path)) {
            throw new JsUserRuntimeException("File not found: " + toRelative(path));
        }
        long fileSize;
        try {
            fileSize = Files.size(path);
        } catch (IOException e) {
            LOG.error("Failed to get size of file: " + path, e);
            throw new JsUserRuntimeException("Failed to get size of file: " + toRelative(path), e);
        }
        int limit = maxBytes <= 0 ? DEFAULT_SNIFF_BYTES : (int) Math.min(maxBytes, MAX_READ_BYTES);
        byte[] data = readBytes(filePath, 0, limit);
        return analyzeCharset(path, data, fileSize);
    }

    /**
     * Analyzes a byte sample and ranks the candidate charsets.
     * @param path file the sample comes from (already resolved)
     * @param data sample bytes
     * @param fileSize total file size (to detect a truncated sample)
     * @return analysis result
     */
    private static Map<String, Object> analyzeCharset(Path path, byte[] data, long fileSize) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", toRelative(path));
        result.put("size", fileSize);
        result.put("sampleBytes", (long) data.length);
        boolean truncated = data.length < fileSize;
        result.put("sampleTruncated", truncated);
        String bom = bomOf(data);
        result.put("bom", bom);

        int nul = 0;
        int high = 0;
        int nulEven = 0;
        int nulOdd = 0;
        for (int i = 0; i < data.length; i++) {
            int v = data[i] & 0xFF;
            if (v == 0) {
                nul++;
                if ((i & 1) == 0) {
                    nulEven++;
                } else {
                    nulOdd++;
                }
            } else if (v >= 0x80) {
                high++;
            }
        }
        result.put("nulBytes", (long) nul);
        result.put("highBytes", (long) high);

        int badUtf8 = firstMalformed(data, StandardCharsets.UTF_8);
        boolean cutMidSequence = badUtf8 >= 0 && truncated && badUtf8 >= data.length - 4;
        boolean utf8Ok = badUtf8 < 0 || cutMidSequence;
        result.put("utf8", badUtf8 < 0 ? "ok"
                : (cutMidSequence
                        ? "ok (the sample ends inside a multi-byte sequence at byte " + badUtf8 + ")"
                        : "invalid at byte " + badUtf8));

        boolean asciiOnly = high == 0 && nul == 0;
        // UTF-16 text (without a BOM) has a NUL byte in every second byte; the side it sits on
        // reveals the byte order: odd offsets -> little endian, even offsets -> big endian.
        int nulTotal = nulEven + nulOdd;
        boolean utf16Aligned = nulTotal > 0 && Math.max(nulEven, nulOdd) >= nulTotal * 0.8;
        boolean utf16Plausible = nul > 0 && nul >= data.length / 16 && utf16Aligned;
        boolean utf16LittleEndian = nulOdd >= nulEven;
        boolean looksBinary = nul > 0 && !utf16Plausible;
        result.put("looksBinary", looksBinary);

        List<Map<String, Object>> candidates = new ArrayList<>();
        if (utf8Ok && !looksBinary) {
            addCandidate(candidates, "UTF-8", data,
                    high == 0 ? "valid UTF-8, 7-bit clean" : "valid UTF-8", 0);
        }
        if (utf16Plausible) {
            // Both byte orders decode without illegal characters for ASCII text, so the NUL
            // alignment decides (penalty below) - otherwise the recommendation would be a coin flip.
            addCandidate(candidates, "UTF-16LE", data,
                    utf16LittleEndian ? "NUL bytes sit on odd offsets, as expected for little endian"
                            : "NUL bytes do not fit little-endian text",
                    utf16LittleEndian ? 0 : UTF16_ALIGN_PENALTY);
            addCandidate(candidates, "UTF-16BE", data,
                    utf16LittleEndian ? "NUL bytes do not fit big-endian text"
                            : "NUL bytes sit on even offsets, as expected for big endian",
                    utf16LittleEndian ? UTF16_ALIGN_PENALTY : 0);
        }
        if (high > 0 && !looksBinary) {
            addCandidate(candidates, "ISO-8859-1", data,
                    "raw Latin-1; bytes 0x80-0x9F decode as C1 control characters", 0);
            // Windows Latin-1 is only a candidate if this JVM provides it (it lives in the
            // jdk.charsets module); ISO-8859-1 above is always available.
            addCandidate(candidates, "CP-1252", data,
                    "Windows Latin-1; differs from ISO-8859-1 only in the bytes 0x80-0x9F", 0);
        }

        String recommended;
        String confidence;
        String note;
        if (bom != null) {
            recommended = bom;
            confidence = "high";
            note = "byte order mark present; the text API keeps the BOM as the first character "
                    + "of readFile() - strip it with s.replace(/^\\uFEFF/, '') if a parser is strict";
        } else if (looksBinary) {
            recommended = null;
            confidence = "high";
            note = "the file contains NUL bytes and is not UTF-16: it looks binary. Do not decode it "
                    + "as text; use fs.readBytes(path, offset, length) / fs.readHex(path, offset, length)";
        } else if (utf16Plausible) {
            // NUL bytes on every other byte: UTF-16 wins over UTF-8 here even though the ASCII part
            // of a UTF-16 file is also valid UTF-8 - decoded as UTF-8 it is full of U+0000 characters.
            Map<String, Object> best = bestCandidate(candidates, "UTF-16LE", "UTF-16BE", "UTF-8");
            recommended = best != null ? String.valueOf(best.get("encoding")) : null;
            boolean utf16Chosen = recommended != null && recommended.startsWith("UTF-16");
            confidence = utf16Chosen ? "medium" : "low";
            note = utf16Chosen
                    ? "the sample looks like UTF-16 (NUL bytes aligned on 2-byte boundaries); check the"
                    + " sample text for the byte order, then read fs.readFile(path, '" + recommended + "')"
                    : "the sample contains NUL bytes; compare the candidate samples before decoding";
        } else if (utf8Ok) {
            recommended = "UTF-8";
            confidence = asciiOnly ? "medium" : "high";
            note = asciiOnly
                    ? "the sample is 7-bit only, so UTF-8 and any ASCII superset (ISO-8859-1, CP-1252) "
                    + "read identically - non-ASCII bytes later in the file decide"
                    : "the sample decodes as valid UTF-8";
        } else {
            Map<String, Object> best = bestCandidate(candidates);
            recommended = best != null ? String.valueOf(best.get("encoding")) : null;
            long bestPenalty = best != null ? ((Number) best.get("penalty")).longValue() : Long.MAX_VALUE;
            confidence = bestPenalty == 0 ? "medium" : "low";
            note = "the file is not valid UTF-8" + (recommended != null
                    ? "; '" + recommended + "' is the least surprising reading - check the sample and"
                    + " then read with fs.readFile(path, '" + recommended + "')"
                    : "; inspect the raw bytes with fs.readHex(path, offset, length)");
        }
        result.put("candidates", candidates);
        result.put("recommended", recommended);
        result.put("confidence", confidence);
        result.put("note", note);
        if (recommended != null) {
            result.put("howToRead", "fs.readFile(" + quoteIfNeeded(toRelative(path)) + ", '" + recommended + "')");
        }
        return result;
    }

    /**
     * Picks the candidate with the lowest penalty (the first one wins a tie).
     * @param candidates candidate list of {@link #analyzeCharset(Path, byte[], long)}
     * @param allowed if non-empty, only candidates with these encoding names are considered
     * @return the best candidate, or null if none matches
     */
    private static Map<String, Object> bestCandidate(List<Map<String, Object>> candidates, String... allowed) {
        Map<String, Object> best = null;
        long bestPenalty = Long.MAX_VALUE;
        for (Map<String, Object> candidate : candidates) {
            String name = String.valueOf(candidate.get("encoding"));
            if (allowed.length > 0) {
                boolean ok = false;
                for (String a : allowed) {
                    if (a.equals(name)) {
                        ok = true;
                        break;
                    }
                }
                if (!ok) {
                    continue;
                }
            }
            long penalty = ((Number) candidate.getOrDefault("penalty", Long.MAX_VALUE)).longValue();
            if (penalty < bestPenalty) {
                best = candidate;
                bestPenalty = penalty;
            }
        }
        return best;
    }

    /**
     * Adds a candidate if the charset is available in this JVM.
     * <p>
     * Only UTF-8, ISO-8859-1 and the UTF-16/UTF-32 variants are part of {@code java.base};
     * everything else (CP-1252, Shift_JIS, EBCDIC code pages, ...) comes from the
     * {@code jdk.charsets} module and may be missing. Detection must not fail because of that,
     * such charsets simply do not become candidates.
     * </p>
     * @param candidates candidate list to add to
     * @param encoding name used in the result (also used for the CP-1252 special case)
     * @param data sample bytes
     * @param note optional explanation
     * @param extraPenalty penalty added on top of the character analysis
     * @return true if the charset was available and the candidate was added
     */
    private static boolean addCandidate(List<Map<String, Object>> candidates, String encoding, byte[] data,
            String note, int extraPenalty) {
        Charset cs;
        try {
            cs = resolveCharset(encoding);
        } catch (IllegalArgumentException e) {
            return false;
        }
        candidates.add(candidateEntry(encoding, cs, data, note, extraPenalty));
        return true;
    }

    /**
     * Builds one candidate entry of {@link #analyzeCharset(Path, byte[], long)}.
     * @param encoding name used in the result
     * @param cs charset to decode with
     * @param data sample bytes
     * @param note optional explanation
     * @return candidate map (encoding, penalty, plausible, sample, note?)
     */
    private static Map<String, Object> candidateEntry(String encoding, Charset cs, byte[] data, String note) {
        return candidateEntry(encoding, cs, data, note, 0);
    }

    /**
     * Builds one candidate entry with an additional (alignment) penalty.
     * @param encoding name used in the result
     * @param cs charset to decode with
     * @param data sample bytes
     * @param note optional explanation
     * @param extraPenalty penalty added on top of the character analysis (e.g. wrong byte order)
     * @return candidate map
     */
    private static Map<String, Object> candidateEntry(String encoding, Charset cs, byte[] data, String note,
            int extraPenalty) {
        String decoded = decode(data, cs, ERRORS_REPLACE, null);
        int penalty = textPenalty(decoded, encoding, data) + extraPenalty;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("encoding", encoding);
        m.put("penalty", (long) penalty);
        m.put("plausible", penalty == 0);
        if (note != null) {
            m.put("note", note);
        }
        m.put("sample", printableSample(decoded, SNIFF_SAMPLE_CHARS));
        return m;
    }

    /**
     * Scores a decoded candidate; lower is more plausible.
     * <p>
     * Penalties: U+FFFD (100), C1 control characters (40), C0 controls other than TAB/LF/CR (20),
     * and for CP-1252 each byte that is undefined in that code page (200). This separates
     * "CP-1252 smart punctuation" (0x93 decodes as a curly quote) from "raw Latin-1 / binary"
     * (0x93 decodes as an invisible C1 control character).
     * </p>
     * @param decoded candidate text
     * @param encoding candidate name (used for the CP-1252 special case)
     * @param data sample bytes
     * @return penalty score
     */
    private static int textPenalty(String decoded, String encoding, byte[] data) {
        int penalty = 0;
        for (int i = 0; i < decoded.length(); i++) {
            char c = decoded.charAt(i);
            if (c == '\uFFFD') {
                penalty += 100;
            } else if (c >= 0x80 && c <= 0x9F) {
                penalty += 40;
            } else if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') {
                penalty += 20;
            }
        }
        if ("CP-1252".equals(encoding)) {
            for (byte b : data) {
                int v = b & 0xFF;
                for (int bad : CP1252_UNDEFINED) {
                    if (v == bad) {
                        penalty += 200;
                        break;
                    }
                }
            }
        }
        return penalty;
    }

    /**
     * Detects a byte order mark.
     * @param data sample bytes
     * @return charset name of the BOM, or null
     */
    private static String bomOf(byte[] data) {
        if (startsWith(data, 0xEF, 0xBB, 0xBF)) {
            return "UTF-8";
        }
        if (startsWith(data, 0xFF, 0xFE, 0x00, 0x00)) {
            return "UTF-32LE";
        }
        if (startsWith(data, 0xFE, 0xFF, 0x00, 0x00)) {
            return "UTF-32BE";
        }
        if (startsWith(data, 0xFF, 0xFE)) {
            return "UTF-16LE";
        }
        if (startsWith(data, 0xFE, 0xFF)) {
            return "UTF-16BE";
        }
        return null;
    }

    /**
     * Checks a byte prefix.
     * @param data bytes to check
     * @param prefix expected values (0-255 each)
     * @return true if the data starts with the prefix
     */
    private static boolean startsWith(byte[] data, int... prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((data[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Renders a printable sample of decoded text (control characters escaped).
     * @param text decoded text
     * @param maxChars maximum number of characters
     * @return escaped sample
     */
    private static String printableSample(String text, int maxChars) {
        int limit = Math.min(text.length(), maxChars);
        StringBuilder sb = new StringBuilder(limit + 16);
        for (int i = 0; i < limit; i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\uFEFF' -> sb.append("<BOM>");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || (c >= 0x7F && c <= 0x9F)) {
                        sb.append('<').append(String.format(Locale.ROOT, "%02X", (int) c)).append('>');
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        if (text.length() > limit) {
            sb.append("...");
        }
        return sb.toString();
    }

    /**
     * Quotes a path for a generated code snippet if it contains characters that need it.
     * @param path relative path
     * @return code snippet path literal
     */
    private static String quoteIfNeeded(String path) {
        return "'" + path.replace("'", "\\'") + "'";
    }

    // ========================================================================
    // Read operations
    // ========================================================================

    /**
     * Reads a complete text file (UTF-8, strict) into a String.
     * <p>
     * Note: for files larger than the LLM context window use
     * {@link #readLines(String, int, int)} or {@link #createLineReader(String)}.
     * Malformed UTF-8 is reported as an error (with byte offset and hex context) instead of being
     * replaced by U+FFFD, because a silent replacement corrupts a read-modify-write round-trip.
     * A byte order mark is kept as the first character.
     * </p>
     * @param filePath path relative to the base directory
     * @return the file content as String
     * @throws JsUserRuntimeException if the file does not exist or an I/O error occurs
     * @throws IllegalArgumentException if the content is not valid UTF-8
     */
    public static String readFile(String filePath) {
        return readFile(filePath, DEFAULT_ENCODING, ERRORS_STRICT);
    }

    /**
     * Reads a complete text file with an explicit charset into a String (strict).
     * @param filePath path relative to the base directory
     * @param encoding charset name, e.g. {@code UTF-8}, {@code ISO-8859-1}/{@code latin1},
     *                 {@code CP-1252}/{@code windows-1252}, {@code UTF-16LE} (null means UTF-8)
     * @return the file content as String
     * @throws JsUserRuntimeException if the file does not exist or an I/O error occurs
     * @throws IllegalArgumentException for an unknown charset or malformed input
     */
    public static String readFile(String filePath, String encoding) {
        return readFile(filePath, encoding, ERRORS_STRICT);
    }

    /**
     * Reads a complete text file with an explicit charset and error policy.
     * @param filePath path relative to the base directory
     * @param encoding charset name (null means UTF-8)
     * @param errors error policy: {@code strict} (default), {@code replace} (U+FFFD) or
     *               {@code ignore} (drop the bytes)
     * @return the file content as String
     * @throws JsUserRuntimeException if the file does not exist or an I/O error occurs
     * @throws IllegalArgumentException for an unknown charset, an unknown policy or, in strict
     *               mode, malformed input
     */
    public static String readFile(String filePath, String encoding, String errors) {
        Charset cs = resolveCharset(encoding);
        String policy = normalizeErrorPolicy(errors);
        Path path = resolveSafePath(filePath);
        if (!Files.isRegularFile(path)) {
            throw new JsUserRuntimeException("File not found: " + toRelative(path));
        }
        byte[] data;
        try {
            data = Files.readAllBytes(path);
        } catch (IOException e) {
            LOG.error("Failed to read file: " + path, e);
            throw new JsUserRuntimeException("Failed to read file: " + toRelative(path), e);
        }
        return decode(data, cs, policy, toRelative(path));
    }

    /**
     * Wraps an I/O error that happened while reading text: decoding errors get an actionable hint.
     * @param action description of the operation (used as message prefix)
     * @param path the file (already resolved)
     * @param cs charset that was used
     * @param lineNo 1-based line number where reading stopped, or null if unknown
     * @param e the caught exception
     * @return the exception to throw
     */
    private static JsUserRuntimeException decodingFailure(String action, Path path, Charset cs, Long lineNo,
            IOException e) {
        if (!isCodingError(e)) {
            return new JsUserRuntimeException(action + ": " + toRelative(path), e);
        }
        String where = lineNo != null && lineNo > 0 ? " near line " + lineNo : "";
        return new JsUserRuntimeException(action + ": " + toRelative(path) + where + " - the file is not valid "
                + cs.name() + ". Pass the real charset (e.g. \"CP-1252\" or \"ISO-8859-1\"),"
                + " check fs.detectCharset(path), or pass errors 'replace' to read it anyway.", e);
    }

    /**
     * Checks whether an exception (or one of its causes) is a charset coding error.
     * @param e throwable to inspect
     * @return true if a {@link CharacterCodingException} is in the cause chain
     */
    private static boolean isCodingError(Throwable e) {
        Throwable t = e;
        for (int depth = 0; t != null && depth < 10; depth++) {
            if (t instanceof CharacterCodingException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
            t = t.getCause();
        }
        return false;
    }

    /**
     * Reads a 1-based line range (inclusive) of a text file (UTF-8, strict).
     * <p>
     * If {@code startLine} is less than 1 it is clamped to 1; if {@code endLine} is
     * smaller than {@code startLine} it is set to {@code startLine + DEFAULT_MAX_LINES - 1}.
     * </p>
     * @param filePath path relative to the base directory
     * @param startLine first line to read (1-based, inclusive)
     * @param endLine last line to read (1-based, inclusive)
     * @return the requested lines joined with '\n' (empty string if the range is empty)
     * @throws JsUserRuntimeException if the file does not exist or an I/O error occurs
     */
    public static String readLines(String filePath, int startLine, int endLine) {
        return readLines(filePath, startLine, endLine, DEFAULT_ENCODING, ERRORS_STRICT);
    }

    /**
     * Reads a 1-based line range (inclusive) of a text file with an explicit charset.
     * @param filePath path relative to the base directory
     * @param startLine first line to read (1-based, inclusive)
     * @param endLine last line to read (1-based, inclusive)
     * @param encoding charset name (null means UTF-8)
     * @return the requested lines joined with '\n'
     * @throws JsUserRuntimeException if the file does not exist or an I/O error occurs
     * @throws IllegalArgumentException for an unknown charset or malformed input
     */
    public static String readLines(String filePath, int startLine, int endLine, String encoding) {
        return readLines(filePath, startLine, endLine, encoding, ERRORS_STRICT);
    }

    /**
     * Reads a 1-based line range (inclusive) with an explicit charset and error policy.
     * @param filePath path relative to the base directory
     * @param startLine first line to read (1-based, inclusive)
     * @param endLine last line to read (1-based, inclusive)
     * @param encoding charset name (null means UTF-8)
     * @param errors error policy: {@code strict} (default), {@code replace} or {@code ignore}
     * @return the requested lines joined with '\n'
     * @throws JsUserRuntimeException if the file does not exist or an I/O error occurs
     */
    public static String readLines(String filePath, int startLine, int endLine, String encoding, String errors) {
        Charset cs = resolveCharset(encoding);
        String policy = normalizeErrorPolicy(errors);
        Path path = resolveSafePath(filePath);
        if (!Files.isRegularFile(path)) {
            throw new JsUserRuntimeException("File not found: " + toRelative(path));
        }
        if (startLine < 1) {
            startLine = 1;
        }
        if (endLine < startLine) {
            endLine = startLine + DEFAULT_MAX_LINES - 1;
        }
        StringBuilder sb = new StringBuilder();
        int currentLine = 0;
        try (BufferedReader reader = newReader(path, cs, policy)) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                currentLine++;
                if (currentLine >= startLine && currentLine <= endLine) {
                    if (!first) {
                        sb.append('\n');
                    }
                    sb.append(line);
                    first = false;
                }
                if (currentLine >= endLine) {
                    break;
                }
            }
        } catch (IOException e) {
            LOG.error("Failed to read lines from file: " + path, e);
            throw decodingFailure("Failed to read lines from file", path, cs, (long) currentLine, e);
        }
        return sb.toString();
    }

    /**
     * Opens a text file (UTF-8, strict) for line-by-line streaming.
     * <p>
     * The returned reader is automatically closed at end of file; it can also be
     * closed early via {@link LineReader#close()}.
     * </p>
     * @param filePath path relative to the base directory
     * @return a LineReader for streaming access
     * @throws JsUserRuntimeException if the file does not exist or cannot be opened
     */
    public static LineReader createLineReader(String filePath) {
        return createLineReader(filePath, DEFAULT_ENCODING, ERRORS_STRICT);
    }

    /**
     * Opens a text file with an explicit charset for line-by-line streaming.
     * @param filePath path relative to the base directory
     * @param encoding charset name (null means UTF-8)
     * @return a LineReader for streaming access
     * @throws JsUserRuntimeException if the file does not exist or cannot be opened
     */
    public static LineReader createLineReader(String filePath, String encoding) {
        return createLineReader(filePath, encoding, ERRORS_STRICT);
    }

    /**
     * Opens a text file with an explicit charset and error policy for line-by-line streaming.
     * @param filePath path relative to the base directory
     * @param encoding charset name (null means UTF-8)
     * @param errors error policy: {@code strict} (default), {@code replace} or {@code ignore}
     * @return a LineReader for streaming access
     * @throws JsUserRuntimeException if the file does not exist or cannot be opened
     */
    public static LineReader createLineReader(String filePath, String encoding, String errors) {
        Charset cs = resolveCharset(encoding);
        String policy = normalizeErrorPolicy(errors);
        Path path = resolveSafePath(filePath);
        if (!Files.isRegularFile(path)) {
            throw new JsUserRuntimeException("File not found: " + toRelative(path));
        }
        try {
            return new LineReader(path, newReader(path, cs, policy), cs);
        } catch (IOException e) {
            LOG.error("Failed to open file for line reading: " + path, e);
            throw new JsUserRuntimeException("Failed to open file: " + toRelative(path), e);
        }
    }

    // ========================================================================
    // List / inspect operations
    // ========================================================================

    /**
     * Lists the entry names of a directory (sorted alphabetically).
     * @param dirPath path relative to the base directory (e.g. "." for the base)
     * @return sorted list of entry names (without directory prefix)
     * @throws JsUserRuntimeException if the path is not a directory or an I/O error occurs
     */
    public static List<String> readdir(String dirPath) {
        Path dir = resolveSafePath(dirPath);
        if (!Files.isDirectory(dir)) {
            throw new JsUserRuntimeException("Not a directory: " + toRelative(dir));
        }
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                names.add(entry.getFileName().toString());
            }
        } catch (IOException e) {
            LOG.error("Failed to list directory: " + dir, e);
            throw new JsUserRuntimeException("Failed to list directory: " + toRelative(dir), e);
        }
        Collections.sort(names);
        return names;
    }

    /**
     * Lists the entries of a directory as relative paths (sorted alphabetically).
     * @param dirPath path relative to the base directory (e.g. "." for the base)
     * @return sorted list of relative paths (e.g. "sub/a.txt")
     * @throws JsUserRuntimeException if the path is not a directory or an I/O error occurs
     */
    public static List<String> listFiles(String dirPath) {
        Path dir = resolveSafePath(dirPath);
        if (!Files.isDirectory(dir)) {
            throw new JsUserRuntimeException("Not a directory: " + toRelative(dir));
        }
        List<String> paths = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                paths.add(toRelative(entry));
            }
        } catch (IOException e) {
            LOG.error("Failed to list directory: " + dir, e);
            throw new JsUserRuntimeException("Failed to list directory: " + toRelative(dir), e);
        }
        Collections.sort(paths);
        return paths;
    }

    /**
     * Returns file attributes for a path (without following symbolic links).
     * @param filePath path relative to the base directory
     * @return Map with keys: name, path, size, isFile, isDirectory, isSymbolicLink, lastModified, created
     * @throws JsUserRuntimeException if the path does not exist or an I/O error occurs
     */
    public static Map<String, Object> stat(String filePath) {
        Path path = resolveSafePath(filePath);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new JsUserRuntimeException("Path does not exist: " + toRelative(path));
        }
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            Map<String, Object> result = new LinkedHashMap<>();
            Path fileName = path.getFileName();
            result.put("name", fileName != null ? fileName.toString() : ".");
            result.put("path", toRelative(path));
            result.put("isFile", attrs.isRegularFile());
            result.put("isDirectory", attrs.isDirectory());
            result.put("isSymbolicLink", attrs.isSymbolicLink());
            result.put("size", attrs.size());
            result.put("lastModified", Instant.ofEpochMilli(attrs.lastModifiedTime().toMillis()).toString());
            result.put("created", Instant.ofEpochMilli(attrs.creationTime().toMillis()).toString());
            return result;
        } catch (IOException e) {
            LOG.error("Failed to read attributes: " + path, e);
            throw new JsUserRuntimeException("Failed to read attributes: " + toRelative(path), e);
        }
    }

    /**
     * Checks whether a path exists.
     * @param filePath path relative to the base directory
     * @return true if the path exists (path-traversal attempts throw instead)
     * @throws JsUserRuntimeException if the path is outside the base directory
     */
    public static boolean exists(String filePath) {
        Path path = resolveSafePath(filePath);
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Checks whether a path is a regular file.
     * @param filePath path relative to the base directory
     * @return true if the path is a regular file
     * @throws JsUserRuntimeException if the path is outside the base directory
     */
    public static boolean isFile(String filePath) {
        Path path = resolveSafePath(filePath);
        return Files.isRegularFile(path);
    }

    /**
     * Checks whether a path is a directory.
     * @param filePath path relative to the base directory
     * @return true if the path is a directory
     * @throws JsUserRuntimeException if the path is outside the base directory
     */
    public static boolean isDirectory(String filePath) {
        Path path = resolveSafePath(filePath);
        return Files.isDirectory(path);
    }

    // ========================================================================
    // Write / edit operations
    // ========================================================================

    /**
     * Creates or overwrites a text file (UTF-8).
     * Missing parent directories are created automatically.
     * @param filePath path relative to the base directory
     * @param content text content to write (null is treated as empty string)
     * @throws JsUserRuntimeException if an I/O error occurs
     */
    public static void writeFile(String filePath, String content) {
        writeFile(filePath, content, DEFAULT_ENCODING, ERRORS_STRICT);
    }

    /**
     * Creates or overwrites a text file with an explicit charset.
     * @param filePath path relative to the base directory
     * @param content text content to write (null is treated as empty string)
     * @param encoding charset name, e.g. {@code CP-1252} or {@code ISO-8859-1} (null means UTF-8)
     * @throws JsUserRuntimeException if an I/O error occurs
     * @throws IllegalArgumentException for an unknown charset or unrepresentable characters
     */
    public static void writeFile(String filePath, String content, String encoding) {
        writeFile(filePath, content, encoding, ERRORS_STRICT);
    }

    /**
     * Creates or overwrites a text file with an explicit charset and error policy.
     * @param filePath path relative to the base directory
     * @param content text content to write (null is treated as empty string)
     * @param encoding charset name (null means UTF-8)
     * @param errors error policy: {@code strict} (default), {@code replace} or {@code ignore}
     * @throws JsUserRuntimeException if an I/O error occurs
     */
    public static void writeFile(String filePath, String content, String encoding, String errors) {
        Charset cs = resolveCharset(encoding);
        String policy = normalizeErrorPolicy(errors);
        Path path = resolveSafePath(filePath);
        byte[] data = encode(content, cs, policy, toRelative(path));
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(path, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException e) {
            LOG.error("Failed to write file: " + path, e);
            throw new JsUserRuntimeException("Failed to write file: " + toRelative(path), e);
        }
    }

    /**
     * Appends text to a file (UTF-8); creates the file if it does not exist.
     * Missing parent directories are created automatically.
     * @param filePath path relative to the base directory
     * @param content text content to append (null is treated as empty string)
     * @throws JsUserRuntimeException if an I/O error occurs
     */
    public static void appendFile(String filePath, String content) {
        appendFile(filePath, content, DEFAULT_ENCODING, ERRORS_STRICT);
    }

    /**
     * Appends text to a file with an explicit charset; creates the file if it does not exist.
     * @param filePath path relative to the base directory
     * @param content text content to append (null is treated as empty string)
     * @param encoding charset name (null means UTF-8)
     * @throws JsUserRuntimeException if an I/O error occurs
     * @throws IllegalArgumentException for an unknown charset or unrepresentable characters
     */
    public static void appendFile(String filePath, String content, String encoding) {
        appendFile(filePath, content, encoding, ERRORS_STRICT);
    }

    /**
     * Appends text to a file with an explicit charset and error policy.
     * <p>
     * The charset must match the existing content: appending UTF-8 text to a CP-1252 file
     * produces mixed-encoding garbage (the tool cannot know better - it writes what it is told).
     * </p>
     * @param filePath path relative to the base directory
     * @param content text content to append (null is treated as empty string)
     * @param encoding charset name (null means UTF-8)
     * @param errors error policy: {@code strict} (default), {@code replace} or {@code ignore}
     * @throws JsUserRuntimeException if an I/O error occurs
     */
    public static void appendFile(String filePath, String content, String encoding, String errors) {
        Charset cs = resolveCharset(encoding);
        String policy = normalizeErrorPolicy(errors);
        Path path = resolveSafePath(filePath);
        byte[] data = encode(content, cs, policy, toRelative(path));
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(path, data, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.error("Failed to append to file: " + path, e);
            throw new JsUserRuntimeException("Failed to append to file: " + toRelative(path), e);
        }
    }

    /**
     * Creates a directory (including missing parent directories).
     * @param dirPath path relative to the base directory
     * @throws JsUserRuntimeException if an I/O error occurs
     */
    public static void mkdir(String dirPath) {
        Path dir = resolveSafePath(dirPath);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOG.error("Failed to create directory: " + dir, e);
            throw new JsUserRuntimeException("Failed to create directory: " + toRelative(dir), e);
        }
    }

    /**
     * Deletes a file or a directory (recursively).
     * Symbolic links are deleted as links, their targets are never followed.
     * @param filePath path relative to the base directory
     * @throws JsUserRuntimeException if the path does not exist or an I/O error occurs
     */
    public static void rm(String filePath) {
        Path path = resolveSafePath(filePath);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new JsUserRuntimeException("Path does not exist: " + toRelative(path));
        }
        try {
            if (Files.isDirectory(path) && !Files.isSymbolicLink(path)) {
                try (var stream = Files.walk(path)) {
                    stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ex) {
                            throw new UncheckedIOException(ex);
                        }
                    });
                }
            } else {
                Files.delete(path);
            }
        } catch (IOException e) {
            LOG.error("Failed to delete: " + path, e);
            throw new JsUserRuntimeException("Failed to delete: " + toRelative(path), e);
        }
    }

    /**
     * Moves / renames a file or directory.
     * Missing parent directories of the target are created automatically.
     * @param sourcePath source path relative to the base directory
     * @param targetPath target path relative to the base directory
     * @throws JsUserRuntimeException if the source does not exist or an I/O error occurs
     */
    public static void rename(String sourcePath, String targetPath) {
        Path src = resolveSafePath(sourcePath);
        Path dst = resolveSafePath(targetPath);
        if (!Files.exists(src, LinkOption.NOFOLLOW_LINKS)) {
            throw new JsUserRuntimeException("Source does not exist: " + toRelative(src));
        }
        try {
            Path parent = dst.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOG.error("Failed to rename " + src + " to " + dst, e);
            throw new JsUserRuntimeException(
                    "Failed to rename: " + toRelative(src) + " -> " + toRelative(dst), e);
        }
    }

    /**
     * Copies a file.
     * Missing parent directories of the target are created automatically.
     * @param sourcePath source path relative to the base directory
     * @param targetPath target path relative to the base directory
     * @throws JsUserRuntimeException if the source does not exist or an I/O error occurs
     */
    public static void copyFile(String sourcePath, String targetPath) {
        Path src = resolveSafePath(sourcePath);
        Path dst = resolveSafePath(targetPath);
        if (!Files.isRegularFile(src)) {
            throw new JsUserRuntimeException("Source file does not exist: " + toRelative(src));
        }
        try {
            Path parent = dst.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOG.error("Failed to copy " + src + " to " + dst, e);
            throw new JsUserRuntimeException(
                    "Failed to copy: " + toRelative(src) + " -> " + toRelative(dst), e);
        }
    }

    // ========================================================================
    // Binary read / stream / write
    // ========================================================================

    /** Lowercase hex digits for {@link #toHex(byte[])}. */
    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    /**
     * Returns the file size in bytes.
     * @param filePath path relative to the base directory
     * @return file size in bytes
     * @throws JsUserRuntimeException if the file does not exist or an I/O error occurs
     */
    public static long size(String filePath) {
        Path path = resolveSafePath(filePath);
        if (!Files.isRegularFile(path)) {
            throw new JsUserRuntimeException("File not found: " + toRelative(path));
        }
        try {
            return Files.size(path);
        } catch (IOException e) {
            LOG.error("Failed to get size of file: " + path, e);
            throw new JsUserRuntimeException("Failed to get size of file: " + toRelative(path), e);
        }
    }

    /**
     * Reads a byte range of a file (pread semantics, 0-based offset).
     * <p>
     * {@code length} is clamped to the end of the file; an {@code offset} at/behind the
     * end of the file yields an empty array. A single read is limited to
     * {@link #MAX_READ_BYTES} bytes; larger files must be processed with
     * {@link #createBlockReader(String, int)}.
     * </p>
     * @param filePath path relative to the base directory
     * @param offset 0-based byte offset (must be &ge; 0)
     * @param length number of bytes to read (must be &ge; 0)
     * @return the requested bytes
     * @throws IllegalArgumentException if offset/length are negative or the range exceeds MAX_READ_BYTES
     * @throws JsUserRuntimeException if the file does not exist or an I/O error occurs
     */
    public static byte[] readBytes(String filePath, long offset, int length) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0 (was: " + offset + ")");
        }
        if (length < 0) {
            throw new IllegalArgumentException("length must be >= 0 (was: " + length + ")");
        }
        Path path = resolveSafePath(filePath);
        if (!Files.isRegularFile(path)) {
            throw new JsUserRuntimeException("File not found: " + toRelative(path));
        }
        long fileSize;
        try {
            fileSize = Files.size(path);
        } catch (IOException e) {
            LOG.error("Failed to get size of file: " + path, e);
            throw new JsUserRuntimeException("Failed to get size of file: " + toRelative(path), e);
        }
        if (offset >= fileSize) {
            return new byte[0];
        }
        int actualLength = (int) Math.min(length, fileSize - offset);
        if (actualLength > MAX_READ_BYTES) {
            throw new IllegalArgumentException("Requested " + actualLength + " bytes exceeds the single-read "
                    + "limit of " + MAX_READ_BYTES + " bytes; pass an explicit in-range length or use "
                    + "fs.createBlockReader for streaming.");
        }
        byte[] buffer = new byte[actualLength];
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            ByteBuffer bb = ByteBuffer.wrap(buffer);
            int read = 0;
            while (read < actualLength) {
                int n = channel.read(bb, offset + read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            return read < actualLength ? Arrays.copyOf(buffer, read) : buffer;
        } catch (IOException e) {
            LOG.error("Failed to read bytes from file: " + path, e);
            throw new JsUserRuntimeException("Failed to read bytes from file: " + toRelative(path), e);
        }
    }

    /**
     * Reads a byte range of a file and returns it as a lowercase hex string (2 chars per byte).
     * @param filePath path relative to the base directory
     * @param offset 0-based byte offset (must be &ge; 0)
     * @param length number of bytes to read (must be &ge; 0)
     * @return hex string of the requested range
     * @throws IllegalArgumentException if offset/length are negative or the range exceeds MAX_READ_BYTES
     * @throws JsUserRuntimeException if the file does not exist or an I/O error occurs
     */
    public static String readHex(String filePath, long offset, int length) {
        return toHex(readBytes(filePath, offset, length));
    }

    /**
     * Formats bytes as a lowercase hex string.
     * @param data bytes (must not be null)
     * @return hex string (empty for an empty array)
     */
    private static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            int v = b & 0xFF;
            sb.append(HEX_DIGITS[v >>> 4]).append(HEX_DIGITS[v & 0x0F]);
        }
        return sb.toString();
    }

    /**
     * Creates/overwrites a file with binary content (e.g. a generated PNG or PPM).
     * Missing parent directories are created automatically.
     * @param filePath path relative to the base directory
     * @param data binary content (must not be null)
     * @throws IllegalArgumentException if data is null
     * @throws JsUserRuntimeException if an I/O error occurs
     */
    public static void writeBytes(String filePath, byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        Path path = resolveSafePath(filePath);
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(path, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException e) {
            LOG.error("Failed to write file: " + path, e);
            throw new JsUserRuntimeException("Failed to write file: " + toRelative(path), e);
        }
    }

    /**
     * Patches/overwrites bytes at an offset in an existing file (in-place).
     * <p>
     * {@code offset == 0} on a not-yet-existing file creates it (equivalent to
     * {@link #writeBytes(String, byte[])}); for {@code offset > 0} the file must exist and
     * {@code offset} must be within {@code [0, size]}; writing may extend the file beyond its
     * current size (e.g. at {@code offset == size}).
     * </p>
     * @param filePath path relative to the base directory
     * @param data bytes to write (must not be null)
     * @param offset 0-based byte offset (must be &ge; 0 and &le; file size)
     * @throws IllegalArgumentException if data is null or offset is out of range
     * @throws JsUserRuntimeException if the file does not exist or an I/O error occurs
     */
    public static void writeBytes(String filePath, byte[] data, long offset) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0 (was: " + offset + ")");
        }
        if (data.length == 0) {
            return;
        }
        Path path = resolveSafePath(filePath);
        if (!Files.isRegularFile(path)) {
            if (offset == 0) {
                // Offset 0 on a not-yet-existing file is equivalent to creating it
                // (LLMs often pass the offset out of habit, e.g. from Node's fs.write).
                writeBytes(filePath, data);
                return;
            }
            throw new JsUserRuntimeException("File not found: " + toRelative(path)
                    + " (fs.writeBytes(path, data, offset) patches an EXISTING file at an offset;"
                    + " to create a new file use fs.writeBytes(path, data) without an offset)");
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            long fileSize = channel.size();
            if (offset > fileSize) {
                throw new IllegalArgumentException("offset (" + offset + ") is beyond end of file (size "
                        + fileSize + ")");
            }
            int written = 0;
            while (written < data.length) {
                int n = channel.write(ByteBuffer.wrap(data, written, data.length - written), offset + written);
                if (n <= 0) {
                    throw new IOException("Failed to write at offset " + (offset + written));
                }
                written += n;
            }
        } catch (IOException e) {
            LOG.error("Failed to write file: " + path, e);
            throw new JsUserRuntimeException("Failed to write file: " + toRelative(path), e);
        }
    }

    /**
     * Opens a file for block-wise (binary) streaming.
     * <p>
     * Each call to {@link BinaryBlockReader#next()} returns the next block (at most
     * {@code blockSize} bytes) as a byte array, or null at end of file. Java streams
     * through a {@link FileChannel}, so files far larger than the JS engine's heap can
     * be processed; JavaScript only ever holds one block at a time.
     * </p>
     * @param filePath path relative to the base directory
     * @param blockSize maximum block size in bytes (must be &gt; 0)
     * @return a block reader for streaming
     * @throws IllegalArgumentException if blockSize is not positive
     * @throws JsUserRuntimeException if the file does not exist or cannot be opened
     */
    public static BinaryBlockReader createBlockReader(String filePath, int blockSize) {
        if (blockSize <= 0) {
            throw new IllegalArgumentException("blockSize must be > 0 (was: " + blockSize + ")");
        }
        Path path = resolveSafePath(filePath);
        if (!Files.isRegularFile(path)) {
            throw new JsUserRuntimeException("File not found: " + toRelative(path));
        }
        try {
            return new BinaryBlockReader(path, blockSize);
        } catch (IOException e) {
            LOG.error("Failed to open file for block reading: " + path, e);
            throw new JsUserRuntimeException("Failed to open file: " + toRelative(path), e);
        }
    }

    /**
     * Block-wise binary reader for streaming large files.
     * <p>
     * Usage from JavaScript (via the {@code fs} namespace):
     * <pre>{@code
     * var r = fs.createBlockReader("big.bin", 64 * 1024);
     * var hex;
     * while ((hex = r.nextHex()) !== null) {
     *     // process one block as a hex string
     * }
     * r.close();
     * }</pre>
     * The reader is closed automatically at end of file.
     * </p>
     */
    public static final class BinaryBlockReader implements AutoCloseable {

        private final Path path;
        private final int blockSize;
        private final FileChannel channel;
        private long position = 0;
        private long blockNumber = 0;
        private boolean closed = false;
        private boolean eof = false;

        private BinaryBlockReader(Path path, int blockSize) throws IOException {
            this.path = path;
            this.blockSize = blockSize;
            this.channel = FileChannel.open(path, StandardOpenOption.READ);
        }

        /**
         * Returns the next block as a byte array (at most blockSize bytes), or null at EOF.
         * @return next block or null at EOF
         * @throws JsUserRuntimeException if the reader is closed or an I/O error occurs
         */
        public byte[] next() {
            if (eof) {
                return null;
            }
            if (closed) {
                throw new JsUserRuntimeException("Block reader is already closed.");
            }
            ByteBuffer bb = ByteBuffer.allocate(blockSize);
            int total = 0;
            try {
                while (bb.hasRemaining()) {
                    int n = channel.read(bb, position + total);
                    if (n < 0) {
                        break;
                    }
                    total += n;
                }
            } catch (IOException e) {
                LOG.error("Failed to read block from: " + path, e);
                throw new JsUserRuntimeException("Failed to read block from: " + toRelative(path), e);
            }
            int got = bb.position();
            if (got == 0) {
                eof = true;
                close();
                return null;
            }
            byte[] result = new byte[got];
            System.arraycopy(bb.array(), 0, result, 0, got);
            position += got;
            blockNumber++;
            if (got < blockSize) {
                eof = true;
                close();
            }
            return result;
        }

        /**
         * Returns the next block as a lowercase hex string, or null at EOF.
         * @return hex string of the next block or null at EOF
         * @throws JsUserRuntimeException if the reader is closed or an I/O error occurs
         */
        public String nextHex() {
            byte[] block = next();
            return block != null ? toHex(block) : null;
        }

        /**
         * Returns the 0-based byte offset of the next block.
         * @return byte offset of the next block
         */
        public long position() {
            return position;
        }

        /**
         * Returns the 0-based index of the next block.
         * @return block index of the next block
         */
        public long blockNumber() {
            return blockNumber;
        }

        /**
         * Returns whether this reader has been closed.
         * @return true if closed
         */
        public boolean isClosed() {
            return closed;
        }

        /**
         * Closes the reader (idempotent).
         */
        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                channel.close();
            } catch (IOException e) {
                LOG.warn("Error closing block reader for " + path, e);
            }
        }
    }

    // ========================================================================
    // Help / Documentation
    // ========================================================================

    /**
     * Returns a help text describing the JS file system API with usage examples.
     * @return help text as a multi-line string
     */
    public static String help() {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                JS FileSystem API (namespace 'fs')
                ==================================

                Controlled access to the project base directory (system property IDE_PROJECT_DIR).
                All paths are relative to the base directory. '..' is not allowed; absolute paths
                and symbolic links leaving the base directory are rejected.
                Results and error messages contain only relative (or add-on-prefixed) paths.

                --- Add-on directories ---
                A path starting with '/addonName/...' addresses an optionally configured add-on
                directory (e.g. a local Maven repository with dependency sources), e.g.
                    fs.listFiles("/repository")                 - list the add-on root
                    fs.readdir("/repository/org/slf4j")         - list a subdirectory
                    fs.readBytes("/repository/....jar", 0, 64)  - inspect a binary JAR
                Add-on mounts are validated like the base directory. Available add-on folders:
                """);
        Map<String, String> addons = WorkProject.listAddons();
        if (addons.isEmpty()) {
            sb.append("                    (none configured)\n");
        } else {
            for (Map.Entry<String, String> addon : addons.entrySet()) {
                sb.append("                    /").append(addon.getKey());
                if (addon.getValue() != null && !addon.getValue().isBlank()) {
                    sb.append(" - ").append(addon.getValue());
                }
                sb.append('\n');
            }
        }
        sb.append("""
                --- Read ---
                fs.readFile(path[, encoding|options])     - Read a complete text file as String.
                                                             encoding: 'UTF-8' (default), 'ISO-8859-1'
                                                             ('latin1'), 'CP-1252' ('windows-1252',
                                                             'ANSI'), 'UTF-16LE', 'US-ASCII', ... or an
                                                             options object {encoding, errors}.
                                                             errors: 'strict' (default), 'replace',
                                                             'ignore' - strict means: malformed bytes
                                                             are an ERROR (with byte offset + hex),
                                                             not a silent U+FFFD.
                                                             'buffer' returns raw bytes (Uint8Array).
                fs.readLines(path, startLine, endLine[, encoding|options])
                                                          - Read a 1-based line range (inclusive).
                                                             Defaults: startLine=1, endLine=startLine+499.
                                                             Also fs.readLines(path, {startLine,
                                                             endLine, encoding, errors}).
                fs.createLineReader(path[, encoding|options])
                                                          - Open a streaming line reader for large files:
                    var r = fs.createLineReader("big.csv");
                    var line;
                    while ((line = r.next()) !== null) {  // next() returns null at end of file
                        // process one line
                    }
                    r.close();                            // optional; auto-closed at end of file
                    r.readLines(maxLines)                 - read up to maxLines lines as one String (null at EOF)
                    r.lineNumber()                        - 1-based line number of the next line
                    r.encoding()                          - charset in use (e.g. "windows-1252")
                    r.close()                             - close the reader early

                --- List / inspect ---
                fs.readdir(path)                          - List entry names of a directory (default ".").
                fs.listFiles(path)                        - List entries as relative paths.
                fs.stat(path)                             - {name, path, size, isFile, isDirectory,
                                                             isSymbolicLink, lastModified, created}
                fs.exists(path)                           - true if the path exists.
                fs.isFile(path)                           - true if the path is a regular file.
                fs.isDirectory(path)                      - true if the path is a directory.

                --- Write / edit ---
                fs.writeFile(path, content[, encoding|options])
                                                          - Create or overwrite a file (creates parent
                                                            dirs). encoding/errors as in readFile;
                                                            writing UTF-8 by default. Strict mode
                                                            rejects characters that the target charset
                                                            cannot represent (e.g. U+20AC in Latin-1).
                fs.appendFile(path, content[, encoding|options])
                                                          - Append to a file (creates it if missing).
                                                            Keep the charset of the existing file!
                fs.mkdir(path)                            - Create a directory (recursive).
                fs.rm(path)                               - Delete a file or a directory (recursive).
                fs.rename(oldPath, newPath)               - Move / rename a file or directory.
                fs.copyFile(sourcePath, targetPath)       - Copy a file.

                --- Binary read / stream ---
                fs.size(path)                             - File size in bytes.
                fs.readBytes(path, offset, length)        - Read length bytes at byte-offset as a real
                                                             Uint8Array (0-255); length is clamped to EOF.
                                                             Require offset>=0, length>=0; single read capped
                                                             at 1 MiB.
                fs.readHex(path, offset, length)          - Same range as lowercase hex string (2 chars/byte).
                fs.createBlockReader(path, blockSize)     - Stream a large file block by block:
                    var r = fs.createBlockReader("big.bin", 64 * 1024);
                    var b;
                    while ((b = r.next()) !== null) {     // b: array of unsigned bytes, null at EOF
                        // process one block
                    }
                    r.nextHex()                           - next block as hex string (null at EOF)
                    r.position()                          - byte offset of the next block
                    r.blockNumber()                       - index of the next block
                    r.close()                             - close early (auto-closed at EOF)

                --- Binary write ---
                fs.writeBytes(path, data)                 - Create/overwrite a file with binary data.
                                                            data: Uint8Array or array of numbers 0-255
                                                            (e.g. a generated PNG/PPM).
                fs.writeBytes(path, data, offset)         - Patch bytes at an offset in an EXISTING file
                                                            (offset 0..size; extends the file if needed).
                                                            offset 0 on a missing file creates it; for
                                                            offset > 0 the file must already exist.

                --- Character sets ---
                Text methods default to UTF-8 and are STRICT: bytes that are not valid for the
                chosen charset are an error (reporting the byte offset and a hex window), not a
                silent U+FFFD - a silent replacement would corrupt readFile -> writeFile round-trips.
                fs.detectCharset(path[, maxBytes])         - Heuristic analysis of a file:
                    {path, size, bom, nulBytes, highBytes, utf8, looksBinary,
                     candidates: [{encoding, penalty, plausible, sample, note}],
                     recommended, confidence, note, howToRead}
                    Use it when readFile() reports invalid UTF-8; the samples show the difference.
                    A recommendation is a heuristic: CP-1252 and ISO-8859-1 differ only in 0x80-0x9F,
                    and a 7-bit file reads the same in every ASCII superset.
                fs.decode(bytes[, encoding|options])       - Uint8Array (e.g. from fs.readBytes) ->
                    String. No hand-written String.fromCharCode loops needed for UTF-8/UTF-16.
                fs.decodeHex(hex[, encoding|options])      - fs.readHex output -> String.
                fs.encode(text[, encoding|options])        - String -> Uint8Array (e.g. to write a
                    CP-1252/ISO-8859-1 file byte-exactly with fs.writeBytes).
                BOM policy: the text API never writes a BOM and never removes one; a BOM appears as
                the first character U+FEFF of readFile() (strip with s.replace(/^\\uFEFF/, '')).

                --- Help ---
                fs.help()                                 - This help text.

                Example (stream a large file line by line):
                    var reader = fs.createLineReader("data.csv");
                    var line;
                    var n = 0;
                    while ((line = reader.next()) !== null) {
                        if (n % 1000 === 0) console.log("Zeile " + reader.lineNumber() + ": " + line);
                        n++;
                    }
                    reader.close();

                Example (read a section of a file):
                    var chunk = fs.readLines("data.csv", 1, 500); // first 500 lines

                Example (legacy Latin-1 / CP-1252 file):
                    var info = fs.detectCharset("alt/protokoll.txt");   // check first
                    var text = fs.readFile("alt/protokoll.txt", "CP-1252");
                    fs.writeFile("neu/protokoll-utf8.txt", text);       // convert to UTF-8
                    var raw = fs.readHex("alt/protokoll.txt", 0, 64);   // raw bytes stay canonical

                Rule of thumb: binary / crypto analysis always goes through readBytes/readHex -
                never through a decoded String, because U+FFFD substitution is lossy.
                """);
        return sb.toString();
    }

    // ========================================================================
    // Line reader for streaming
    // ========================================================================

    /**
     * Streaming line reader for text files.
     * <p>
     * Usage from JavaScript (via the {@code fs} namespace):
     * <pre>{@code
     * var r = fs.createLineReader("big.csv");
     * var line;
     * while ((line = r.next()) !== null) {
     *     // process one line
     * }
     * r.close();
     * }</pre>
     * The reader is closed automatically at end of file.
     * </p>
     */
    public static final class LineReader implements AutoCloseable {

        private final Path path;
        private final BufferedReader reader;
        private final Charset charset;
        private long linesRead = 0;
        private boolean closed = false;
        private boolean eof = false;

        private LineReader(Path path, BufferedReader reader, Charset charset) {
            this.path = path;
            this.reader = reader;
            this.charset = charset;
        }

        /**
         * Returns the next line (without line terminator), or null at end of file.
         * @return next line or null at EOF
         * @throws JsUserRuntimeException if the reader is closed, the input is malformed for the
         *         selected charset, or an I/O error occurs
         */
        public String next() {
            if (eof) {
                // End of file already reached: further reads stay null.
                return null;
            }
            if (closed) {
                throw new JsUserRuntimeException("Line reader is already closed.");
            }
            try {
                String line = reader.readLine();
                if (line == null) {
                    eof = true;
                    close();
                    return null;
                }
                linesRead++;
                return line;
            } catch (IOException e) {
                LOG.error("Failed to read line from: " + path, e);
                throw decodingFailure("Failed to read line from", path, charset, getLineNumber(), e);
            }
        }

        /**
         * Reads up to {@code maxLines} lines and returns them joined with '\n'.
         * @param maxLines maximum number of lines to read (must be &gt; 0)
         * @return the lines as a single String, or null if end of file was already reached
         * @throws JsUserRuntimeException if the reader is closed or an I/O error occurs
         */
        public String readLines(int maxLines) {
            if (maxLines <= 0) {
                throw new JsUserRuntimeException("maxLines must be greater than 0.");
            }
            StringBuilder sb = new StringBuilder();
            int count = 0;
            while (count < maxLines) {
                String line = next();
                if (line == null) {
                    break;
                }
                if (count > 0) {
                    sb.append('\n');
                }
                sb.append(line);
                count++;
            }
            return count == 0 ? null : sb.toString();
        }

        /**
         * Returns the 1-based line number that the next call to {@link #next()}
         * will return (1 before any line has been read).
         * @return line number of the next line
         */
        public long getLineNumber() {
            return linesRead + 1;
        }

        /**
         * Returns the canonical name of the charset this reader decodes with.
         * @return charset name (e.g. {@code UTF-8}, {@code windows-1252})
         */
        public String getEncoding() {
            return charset != null ? charset.name() : DEFAULT_ENCODING;
        }

        /**
         * Returns whether this reader has been closed.
         * @return true if closed
         */
        public boolean isClosed() {
            return closed;
        }

        /**
         * Closes the reader (idempotent).
         */
        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                reader.close();
            } catch (IOException e) {
                LOG.warn("Error closing line reader for " + path, e);
            }
        }
    }
}
