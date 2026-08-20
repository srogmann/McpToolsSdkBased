package org.rogmann.mcp2sdk.js;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
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
import java.util.Map;

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
    static Path resolveSafePath(String filePath) {
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
    static String toRelative(Path path) {
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
    // Read operations
    // ========================================================================

    /**
     * Reads a complete text file (UTF-8) into a String.
     * <p>
     * Note: for files larger than the LLM context window use
     * {@link #readLines(String, int, int)} or {@link #createLineReader(String)}.
     * </p>
     * @param filePath path relative to the base directory
     * @return the file content as String
     * @throws JsUserRuntimeException if the file does not exist or an I/O error occurs
     */
    public static String readFile(String filePath) {
        Path path = resolveSafePath(filePath);
        if (!Files.isRegularFile(path)) {
            throw new JsUserRuntimeException("File not found: " + toRelative(path));
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.error("Failed to read file: " + path, e);
            throw new JsUserRuntimeException("Failed to read file: " + toRelative(path), e);
        }
    }

    /**
     * Reads a 1-based line range (inclusive) of a text file.
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
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            int currentLine = 0;
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
            throw new JsUserRuntimeException("Failed to read lines from file: " + toRelative(path), e);
        }
        return sb.toString();
    }

    /**
     * Opens a text file for line-by-line streaming.
     * <p>
     * The returned reader is automatically closed at end of file; it can also be
     * closed early via {@link LineReader#close()}.
     * </p>
     * @param filePath path relative to the base directory
     * @return a LineReader for streaming access
     * @throws JsUserRuntimeException if the file does not exist or cannot be opened
     */
    public static LineReader createLineReader(String filePath) {
        Path path = resolveSafePath(filePath);
        if (!Files.isRegularFile(path)) {
            throw new JsUserRuntimeException("File not found: " + toRelative(path));
        }
        try {
            BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
            return new LineReader(path, reader);
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
        Path path = resolveSafePath(filePath);
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, content != null ? content : "", StandardCharsets.UTF_8);
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
        Path path = resolveSafePath(filePath);
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, content != null ? content : "",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
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
     * The file must exist and {@code offset} must be within {@code [0, size]};
     * writing may extend the file beyond its current size (e.g. at {@code offset == size}).
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
            throw new JsUserRuntimeException("File not found: " + toRelative(path));
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
                fs.readFile(path)                         - Read a complete text file as String (UTF-8).
                fs.readLines(path, startLine, endLine)    - Read a 1-based line range (inclusive).
                                                             Defaults: startLine=1, endLine=startLine+499.
                fs.createLineReader(path)                 - Open a streaming line reader for large files:
                    var r = fs.createLineReader("big.csv");
                    var line;
                    while ((line = r.next()) !== null) {  // next() returns null at end of file
                        // process one line
                    }
                    r.close();                            // optional; auto-closed at end of file
                    r.readLines(maxLines)                 - read up to maxLines lines as one String (null at EOF)
                    r.lineNumber()                        - 1-based line number of the next line
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
                fs.writeFile(path, content)               - Create or overwrite a file (creates parent dirs).
                fs.appendFile(path, content)              - Append to a file (creates it if missing).
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
                fs.writeBytes(path, data, offset)         - Patch bytes at an offset in an existing file
                                                            (offset 0..size; extends the file if needed).

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
        private long linesRead = 0;
        private boolean closed = false;
        private boolean eof = false;

        private LineReader(Path path, BufferedReader reader) {
            this.path = path;
            this.reader = reader;
        }

        /**
         * Returns the next line (without line terminator), or null at end of file.
         * @return next line or null at EOF
         * @throws JsUserRuntimeException if the reader is closed or an I/O error occurs
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
                throw new JsUserRuntimeException("Failed to read line from: " + toRelative(path), e);
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
