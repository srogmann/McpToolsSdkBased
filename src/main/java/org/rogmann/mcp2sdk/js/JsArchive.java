package org.rogmann.mcp2sdk.js;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controlled access to the contents of ZIP and tar archives plus gzip/deflate byte
 * streams for JavaScript.
 * <p>
 * Reading and transforming is always available: entries can be listed and extracted and
 * bytes can be compressed/decompressed (gzip, raw deflate); results are returned as byte
 * arrays (a {@code Uint8Array} in JavaScript). Entries are never extracted to disk, so
 * archive-internal path traversal ("zip slip") cannot touch the file system; to persist a
 * result the caller uses {@code fs.writeBytes(...)}.
 * </p>
 * <p>
 * <strong>ZIP writing is disabled by default.</strong> The modifying operations
 * {@link #zipCreate}, {@link #zipEntryWrite} and {@link #zipEntryDelete} require the system
 * property {@value #PROP_READONLY}{@code =false}; an absent property means
 * <em>read-only</em> and the operations fail with an explanatory error.
 * tar is read-only in every configuration.
 * </p>
 * <p>
 * Archive files must live inside the project base directory and are resolved with the same
 * security checks as {@link JsFileSystem} (system property {@code IDE_PROJECT_DIR}): no
 * {@code ..}, no absolute paths outside the base, no symbolic links leaving the base.
 * Results and error messages contain only relative paths.
 * </p>
 *
 * <h3>ZIP</h3>
 * <p>
 * Built on {@link ZipFile} (UTF-8 entry names). Listing reads only the central directory
 * (no decompression); extraction streams a single entry through an {@link InputStream}.
 * Zips cover {@code .zip}, {@code .jar}, {@code .war} and {@code .ear}. ({@code .har} is
 * JSON and is read with {@code fs.readFile} + {@code JSON.parse}, not an archive.)
 * </p>
 *
 * <h3>ZIP write access (opt-in, see {@value #PROP_READONLY})</h3>
 * <p>
 * {@link #zipEntryWrite} and {@link #zipEntryDelete} rewrite the whole archive: the result
 * is written to a temporary file next to the target, verified (entry count and CRC of every
 * written entry) and then moved into place atomically, so a failing rewrite never leaves a
 * half-written archive behind. Everything not touched keeps its entry name, order,
 * compression method, timestamp and comment; the archive comment is preserved too.
 * </p>
 * <p>
 * A rewrite is <em>content-preserving, not byte-preserving</em>: DEFLATE output is not
 * canonical, so unchanged entries can have different compressed bytes, and ZIP extra fields
 * (NTFS timestamps, zipalign padding, ...) are not copied. Archives that depend on exact
 * bytes &ndash; signed JARs ({@code META-INF/*.SF}/{@code *.RSA}), aligned APKs &ndash; stop
 * working after a rewrite; such archives are reported in the {@code warnings} of the result.
 * </p>
 *
 * <h3>tar</h3>
 * <p>
 * A small self-contained parser handles the common formats:
 * </p>
 * <ul>
 *   <li>ustar / POSIX headers (including the <em>prefix</em> field),</li>
 *   <li>GNU long names ({@code L} type flag),</li>
 *   <li>POSIX pax extended headers ({@code x}/{@code g}) honoring the
 *       {@code path}, {@code linkpath} and {@code size} overrides.</li>
 * </ul>
 * <p>
 * Not supported (rejected with a clear error message) are tar sparse files
 * ({&#064;code S}) and base-256 encoded sizes.
 * </p>
 * <p>
 * <strong>A gzipped tarball is a tar archive for this API.</strong> {@link #tarEntries(String)},
 * {@link #tarEntry(String, String)} and the byte-array variants look at the first bytes of the
 * data and decompress on the fly when they see the gzip magic {@code 1F 8B 08} (RFC 1952), so
 * {@code .tar.gz} and {@code .tgz} need no separate {@code gunzip} step and no extra flag. The
 * decision is made on content, never on the file name: a tarball saved as {@code .bin} is still
 * recognized and a plain tar called {@code .tar.gz} is still read as tar. Decompression of a
 * whole tarball is capped at {@link #MAX_DECOMPRESSED_BYTES}.
 * </p>
 *
 * <h3>Limits</h3>
 * <p>
 * A single extracted entry is limited to {@link #MAX_ENTRY_BYTES} bytes (256 MiB) to keep
 * the JS engine heap safe. Listing an archive never loads entry data. Writing is limited to
 * {@link #MAX_ENTRY_BYTES} bytes per entry, {@link #MAX_ZIP_ENTRIES} entries per archive and
 * {@link #MAX_ARCHIVE_BYTES} total uncompressed bytes, which keeps every result below the
 * ZIP64 thresholds.
 * </p>
 *
 * <h3>Usage in JavaScript</h3>
 * <pre>{@code
 * var names = archive.zipEntries("app.war").map(e => e.name);
 * var webXml = archive.zipEntry("app.war", "WEB-INF/web.xml");
 * var list = archive.tarEntries("backup.tar");
 * var content = archive.tarEntry("backup.tar", "etc/config.txt");
 * var gzList = archive.tarEntries("src.tar.gz"); // .tar.gz/.tgz: one call, gzip is detected
 * var gzOne = archive.tarEntry("src.tgz", gzList.get(0).get("name").toString());
 * var gz = archive.gzip(data);                       // gzip-compress bytes
 * var raw = archive.gunzipFile("log.gz");            // gunzip a file (streamed)
 * fs.writeBytes("out.bin", content);                 // persist an extracted entry
 *
 * archive.status();                                  // { readonly: true|false, ... }
 * archive.zipCreate("fixtures/book.xlsx", [          // requires -Dmcp.js.archive.readonly=false
 *     {name: "[Content_Types].xml", text: '<?xml ...?>'},
 *     {name: "xl/workbook.xml", data: someBytes}]);
 * archive.zipEntryWrite("fixtures/book.xlsx", "xl/sharedStrings.xml", newXmlText);
 * archive.zipEntryDelete("app.jar", "old/Stale.class");
 * }</pre>
 */
public class JsArchive {

    private static final Logger LOG = LoggerFactory.getLogger(JsArchive.class);

    /** Upper bound for a single extracted entry (bytes); protects the JS engine heap. */
    public static final long MAX_ENTRY_BYTES = 256L * 1024 * 1024;

    /** Upper bound for one gzip/inflate decompression result (bytes). */
    public static final long MAX_DECOMPRESSED_BYTES = 256L * 1024 * 1024;

    /**
     * System property that gates the ZIP write operations. Absent means read-only
     * (the safe default); writing has to be enabled explicitly with
     * {@code -Dmcp.js.archive.readonly=false}. Read operations are never affected.
     */
    public static final String PROP_READONLY = "mcp.js.archive.readonly";

    /**
     * Maximum number of entries per written archive. Deliberately the non-ZIP64 limit:
     * staying below it keeps the result readable by every tool (Excel, POI, unzip,
     * jarsigner) without ZIP64 end-of-central-directory records.
     */
    public static final int MAX_ZIP_ENTRIES = 65535;

    /** Upper bound for the total uncompressed content of one written archive (bytes). */
    public static final long MAX_ARCHIVE_BYTES = 2L * 1024 * 1024 * 1024;

    /** Number of individual warnings reported in one result (further ones are summarized). */
    private static final int MAX_WARNINGS = 12;

    /** Suffix of the temporary file used for the atomic rewrite. */
    private static final String TMP_SUFFIX = ".ziptmp";

    /**
     * Limitations paragraph of {@link #help()}. Built by concatenation instead of format
     * placeholders, so numeric limits can never collide with {@code formatted()} arguments.
     */
    private static final String HELP_LIMITS = "Limits: a single extracted, decompressed or"
            + " written entry is capped at " + MAX_ENTRY_BYTES + " bytes ("
            + (MAX_ENTRY_BYTES / (1024 * 1024)) + " MiB); listing an archive loads only metadata."
            + " A gzipped tar may decompress to at most " + MAX_DECOMPRESSED_BYTES + " bytes ("
            + (MAX_DECOMPRESSED_BYTES / (1024 * 1024)) + " MiB) - reading fails with a clear"
            + " message beyond that, so a compressed bomb cannot turn into endless work."
            + " A written archive holds at most " + MAX_ZIP_ENTRIES + " entries and "
            + MAX_ARCHIVE_BYTES + " uncompressed bytes, which keeps it below the ZIP64 thresholds."
            + " Entry timestamps keep the ZIP resolution (DOS time, two seconds). Archives that"
            + " hold the same entry name twice are refused for rewriting, because those entries"
            + " cannot be told apart when reading. Sparse tar files and base-256 sizes are"
            + " rejected with a clear error message. A gzipped tar is decompressed transparently,"
            + " concatenated gzip members included; the tar scan ends at the first end-of-archive"
            + " marker, so a tarball made of two tars reads as its first one (as GNU tar does).";

    /** tar record/block size in bytes. */
    private static final int TAR_BLOCK_SIZE = 512;

    /**
     * gzip magic (RFC 1952): {@code 1F 8B} followed by the compression method CM, which is
     * {@code 08} for DEFLATE. The tar reader compares these three bytes, so a gzip stream is
     * recognized by its content and not by a {@code .gz}/{@code .tar.gz}/{@code .tgz} name -
     * a tarball named {@code data.bin} is still read, and a file that merely <em>claims</em>
     * to be a tarball is not. Three bytes are also safe the other way round: a tar header
     * starts with the entry name, and a name beginning with the bytes {@code 1F 8B 08}
     * (a control character plus two non-UTF-8 bytes) is not something tar writers produce.
     */
    private static final byte[] GZIP_MAGIC = {0x1f, (byte) 0x8b, 0x08};

    /** Buffer size for streaming reads (data block size is not limited by this). */
    private static final int STREAM_BUFFER_SIZE = 64 * 1024;

    private JsArchive() {
        // Utility class
    }

    // ========================================================================
    // Path resolution / security
    // ========================================================================

    /**
     * Resolves an archive path and verifies that it is a regular file inside the base directory.
     * @param filePath path relative to the base directory
     * @return the resolved absolute path
     * @throws JsUserRuntimeException if the path is outside the base, missing or not a regular file
     */
    private static Path requireRegularFile(String filePath) {
        Path path = JsFileSystem.resolveSafePath(filePath);
        if (!Files.isRegularFile(path)) {
            throw new JsUserRuntimeException("File not found: " + JsFileSystem.toRelative(path));
        }
        return path;
    }

    // ========================================================================
    // ZIP
    // ========================================================================

    /**
     * Lists all entries of a ZIP archive (central directory, no decompression).
     * @param filePath archive path relative to the base directory
     * @return list of maps {@code {name, size, compressedSize, method, crc32, isDirectory, comment}},
     *         sorted by name
     * @throws JsUserRuntimeException if the archive is missing or not a valid ZIP
     */
    public static List<Map<String, Object>> zipEntries(String filePath) {
        Path path = requireRegularFile(filePath);
        try (ZipFile zf = openZip(path)) {
            List<Map<String, Object>> entries = new ArrayList<>();
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", e.getName());
                m.put("size", e.getSize());
                m.put("compressedSize", e.getCompressedSize());
                m.put("method", methodToString(e.getMethod()));
                long crc = e.getCrc();
                m.put("crc32", crc >= 0 ? String.format("%08x", crc) : null);
                m.put("isDirectory", e.isDirectory() || e.getName().endsWith("/"));
                m.put("comment", e.getComment());
                entries.add(m);
            }
            entries.sort(Comparator.comparing((Map<String, Object> e) -> (String) e.get("name")));
            return entries;
        } catch (IOException e) {
            LOG.error("Failed to read zip archive: " + path, e);
            throw new JsUserRuntimeException("Failed to read zip archive: " + JsFileSystem.toRelative(path), e);
        }
    }

    /**
     * Extracts a single entry of a ZIP archive as a byte array.
     * <p>
     * Only the requested entry is decompressed (streamed); it is limited to
     * {@link #MAX_ENTRY_BYTES} bytes.
     * </p>
     * @param filePath archive path relative to the base directory
     * @param entryName name of the entry within the archive
     * @return entry content, {@code null} if the entry does not exist, an empty array for a directory
     * @throws JsUserRuntimeException if the archive is missing, not a valid ZIP or the entry is too large
     */
    public static byte[] zipEntry(String filePath, String entryName) {
        if (entryName == null || entryName.isBlank()) {
            throw new IllegalArgumentException("entry name must not be empty");
        }
        Path path = requireRegularFile(filePath);
        try (ZipFile zf = openZip(path)) {
            ZipEntry e = zf.getEntry(entryName);
            if (e == null) {
                return null;
            }
            if (e.isDirectory() || e.getName().endsWith("/")) {
                return new byte[0];
            }
            long size = e.getSize();
            if (size > MAX_ENTRY_BYTES) {
                throw new JsUserRuntimeException(
                        "Entry '" + entryName + "' has " + size + " bytes, exceeding the single-entry "
                        + "extraction limit of " + MAX_ENTRY_BYTES + " bytes");
            }
            try (InputStream in = zf.getInputStream(e)) {
                return readBounded(in, MAX_ENTRY_BYTES);
            }
        } catch (IOException e) {
            LOG.error("Failed to read zip archive: " + path, e);
            throw new JsUserRuntimeException("Failed to read zip archive: " + JsFileSystem.toRelative(path), e);
        }
    }

    /**
     * Opens a ZIP file with UTF-8 entry names.
     */
    private static ZipFile openZip(Path path) throws IOException {
        return new ZipFile(path.toFile(), StandardCharsets.UTF_8);
    }

    /**
     * Human-readable compression method name.
     */
    private static String methodToString(int method) {
        return switch (method) {
            case ZipEntry.STORED -> "STORED";
            case ZipEntry.DEFLATED -> "DEFLATED";
            default -> Integer.toString(method);
        };
    }

    // ========================================================================
    // ZIP write access (gated by the PROP_READONLY system property)
    // ========================================================================

    /**
     * One entry of a new archive (see {@link #zipCreate}).
     *
     * @param name        entry name, archive-relative with '/' separators (no '..')
     * @param data        entry content; {@code null} means empty (e.g. a directory entry)
     * @param method      {@code "deflate"} (default) or {@code "store"}; {@code null} = default
     * @param mtimeMillis modification time as epoch milliseconds; {@code null} = now
     * @param comment     entry comment, may be {@code null}
     */
    public record ZipEntrySpec(String name, byte[] data, String method, Long mtimeMillis, String comment) {

        /** Entry with binary content, default method and current timestamp. */
        public static ZipEntrySpec of(String name, byte[] data) {
            return new ZipEntrySpec(name, data, null, null, null);
        }

        /** Entry with UTF-8 text content, default method and current timestamp. */
        public static ZipEntrySpec ofText(String name, String text) {
            return new ZipEntrySpec(name, text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8),
                    null, null, null);
        }
    }

    /**
     * One entry of the rewritten archive: either copied from an entry of the source archive
     * ({@code source != null}, streamed through without going through the JS heap) or newly
     * written from a byte array.
     */
    private record WriteItem(String name, byte[] data, int method, Long mtimeMillis, String comment,
                             ZipEntry source) {

        static WriteItem ofNew(String name, byte[] data, int method, Long mtimeMillis, String comment) {
            int effective = name.endsWith("/") ? ZipEntry.STORED : method;
            return new WriteItem(name, data, effective, mtimeMillis, comment, null);
        }

        static WriteItem ofCopy(ZipEntry source) {
            return new WriteItem(source.getName(), null, source.getMethod(), null, null, source);
        }
    }

    /**
     * Returns whether the archive namespace may modify the file system: {@code true} unless
     * {@value #PROP_READONLY} is set to {@code false} (absent property means read-only).
     *
     * @return true if {@code zipCreate}/{@code zipEntryWrite}/{@code zipEntryDelete} are refused
     */
    public static boolean isReadOnly() {
        return !isWriteEnabled();
    }

    /**
     * Counterpart of {@link #isReadOnly()}.
     *
     * @return true if the ZIP write operations are enabled by configuration
     */
    public static boolean isWriteEnabled() {
        String value = readReadonlyProperty();
        return value != null && "false".equalsIgnoreCase(value.trim());
    }

    /**
     * Reads the read-only flag.
     */
    private static String readReadonlyProperty() {
        return System.getProperty(PROP_READONLY);
    }

    /**
     * Module status for JavaScript: whether writing is enabled and which limits apply.
     * Cheap, no file is touched.
     *
     * @return map with {@code readonly}, {@code writeEnabled}, the property names and the limits
     */
    public static Map<String, Object> status() {
        boolean writeEnabled = isWriteEnabled();
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("maxEntryBytes", MAX_ENTRY_BYTES);
        limits.put("maxZipEntries", MAX_ZIP_ENTRIES);
        limits.put("maxArchiveBytes", MAX_ARCHIVE_BYTES);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("readonly", !writeEnabled);
        result.put("writeEnabled", writeEnabled);
        result.put("property", PROP_READONLY);
        result.put("propertyValue", readReadonlyProperty());
        result.put("zipWriteSupported", writeEnabled);
        result.put("tarWriteSupported", false);
        result.put("limits", limits);
        return result;
    }

    /**
     * Refuses a write operation while the module is read-only.
     * @param usage signature of the rejected operation, for the error message
     * @throws JsUserRuntimeException always (when the property does not enable writing)
     */
    private static void requireWriteEnabled(String usage) {
        if (!isWriteEnabled()) {
            throw new JsUserRuntimeException("archive is read-only: " + usage + " is disabled. "
                    + "Enable ZIP writing on the server with -D" + PROP_READONLY + "=false and restart. Reading "
                    + "(zipEntries, zipEntry, tarEntries, tarEntry, gzip, inflate, ...) is unaffected; "
                    + "tar has no write support at all. See archive.help().");
        }
    }

    /**
     * Creates a new ZIP archive (read-only mode: see {@link #isReadOnly()}).
     * @param filePath  archive path relative to the base directory
     * @param specs     entries to write, in the order they should appear (may be empty)
     * @param overwrite replace an existing file instead of failing
     * @param comment   archive comment, may be {@code null}
     * @param level     DEFLATE level (-1 = default, 0..9), may be {@code null}
     * @return summary map {@code {path, created, replaced, entries, archiveBytes,
     *         uncompressedBytes, warnings}}
     * @throws JsUserRuntimeException if writing is disabled, the file exists without
     *         {@code overwrite}, or an entry exceeds the limits
     */
    public static Map<String, Object> zipCreate(String filePath, List<ZipEntrySpec> specs,
            boolean overwrite, String comment, Integer level) {
        requireWriteEnabled("zipCreate(path, entries[, options])");
        Path path = JsFileSystem.resolveSafePath(filePath);
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new JsUserRuntimeException("Target is a directory: " + JsFileSystem.toRelative(path));
        }
        boolean replaced = Files.exists(path, LinkOption.NOFOLLOW_LINKS);
        if (replaced && !overwrite) {
            throw new JsUserRuntimeException("File exists: " + JsFileSystem.toRelative(path)
                    + " (pass {overwrite: true} to replace it)");
        }
        validateCompressionLevel(level);
        List<String> warnings = new ArrayList<>();
        List<WriteItem> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        long total = 0;
        if (specs != null) {
            for (ZipEntrySpec spec : specs) {
                if (spec == null) {
                    throw new IllegalArgumentException("zipCreate: entries must not contain null "
                            + "(each entry is {name, data|text, method?, mtime?, comment?})");
                }
                validateEntryName(spec.name());
                byte[] data = spec.data() == null ? new byte[0] : spec.data();
                validateEntryContent(spec.name(), data);
                if (!seen.add(spec.name())) {
                    throw new IllegalArgumentException(
                            "zipCreate: duplicate entry name '" + spec.name() + "'");
                }
                total += data.length;
                checkArchiveBudget(total);
                items.add(WriteItem.ofNew(spec.name(), data, resolveMethod(spec.method(), ZipEntry.DEFLATED),
                        spec.mtimeMillis(), spec.comment()));
            }
        }
        checkEntryCount(items.size());
        Map<String, Object> summary = writeArchive(null, path, items, comment, level, warnings);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", JsFileSystem.toRelative(path));
        result.put("created", true);
        result.put("replaced", replaced);
        result.putAll(summary);
        return result;
    }

    /**
     * Replaces one entry of an existing ZIP archive or appends it if it is not present yet
     * (upsert; read-only mode: see {@link #isReadOnly()}).
     * <p>
     * The archive is rewritten as a whole (temporary file next to the target, verified,
     * then moved atomically). Untouched entries keep name, order, compression method,
     * timestamp and comment; so does the archive comment. Compressed bytes are
     * <em>not</em> reproduced bit-for-bit and ZIP extra fields are not copied - see the
     * {@code warnings} of the result for archives that depend on exact bytes (signed JARs).
     * </p>
     * @param filePath    archive path relative to the base directory
     * @param entryName   entry name to replace or add
     * @param data        new content; {@code null} means empty
     * @param method      {@code "deflate"} or {@code "store"}; {@code null} keeps the method of
     *                    the replaced entry and defaults to DEFLATE for a new one
     * @param mtimeMillis modification time as epoch milliseconds; {@code null} = now
     *                    (ZIP stores DOS time, so only whole seconds, rounded to 2 s)
     * @return summary map {@code {path, action, name, entriesBefore, entriesAfter, entryBytes,
     *         method, crc32, mtime, mtimeIso, entries, archiveBytes, uncompressedBytes, warnings}}
     * @throws JsUserRuntimeException if writing is disabled, the archive is missing/invalid or
     *         a limit is exceeded
     */
    public static Map<String, Object> zipEntryWrite(String filePath, String entryName, byte[] data,
            String method, Long mtimeMillis) {
        requireWriteEnabled("zipEntryWrite(path, name, data[, options])");
        validateEntryName(entryName);
        byte[] payload = data == null ? new byte[0] : data;
        validateEntryContent(entryName, payload);
        Integer requestedMethod = method == null ? null : resolveMethod(method, ZipEntry.DEFLATED);
        Path path = requireRegularFile(filePath);
        List<String> warnings = new ArrayList<>();
        try (ZipFile zf = openZip(path)) {
            List<ZipEntry> all = entryList(zf);
            rejectDuplicateEntryNames(all);
            List<WriteItem> items = new ArrayList<>();
            boolean found = false;
            int methodToUse = requestedMethod != null ? requestedMethod : ZipEntry.DEFLATED;
            for (ZipEntry e : all) {
                if (entryName.equals(e.getName())) {
                    found = true;
                    if (isDirectoryEntry(e) && payload.length > 0) {
                        throw new JsUserRuntimeException("Entry '" + entryName + "' is a directory entry; "
                                + "writing " + payload.length + " bytes into it is refused");
                    }
                    if (requestedMethod == null && isSupportedMethod(e.getMethod())) {
                        methodToUse = e.getMethod(); // keep STORED entries STORED
                    }
                    items.add(WriteItem.ofNew(entryName, payload, methodToUse, mtimeMillis, null));
                    continue;
                }
                items.add(copyItem(e));
            }
            if (!found) {
                items.add(WriteItem.ofNew(entryName, payload, methodToUse, mtimeMillis, null));
            }
            warnAboutExtraFields(all, warnings);
            warnAboutSignatures(all, warnings);
            checkEntryCount(items.size());
            long mtime = mtimeMillis != null ? mtimeMillis : System.currentTimeMillis();
            Map<String, Object> summary = writeArchive(zf, path, items, zf.getComment(), null, warnings);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("path", JsFileSystem.toRelative(path));
            result.put("action", found ? "replaced" : "added");
            result.put("name", entryName);
            result.put("entriesBefore", all.size());
            result.put("entriesAfter", items.size());
            result.put("entryBytes", payload.length);
            result.put("method", methodToString(entryName.endsWith("/") ? ZipEntry.STORED : methodToUse));
            result.put("crc32", crcHex(payload));
            result.put("mtime", mtime);
            result.put("mtimeIso", Instant.ofEpochMilli(mtime).toString());
            result.putAll(summary);
            return result;
        } catch (IOException e) {
            LOG.error("Failed to rewrite zip archive: " + path, e);
            throw new JsUserRuntimeException("Failed to read zip archive: " + JsFileSystem.toRelative(path), e);
        }
    }

    /**
     * Deletes one entry of an existing ZIP archive (read-only mode: see {@link #isReadOnly()}).
     * <p>
     * Fails with a list of similar names if the entry does not exist - a silent no-op would
     * leave the caller believing the archive had been changed.
     * </p>
     * @param filePath  archive path relative to the base directory
     * @param entryName entry name to delete (exactly as reported by {@code zipEntries})
     * @return summary map {@code {path, action, name, deleted, entriesBefore, entriesAfter,
     *         entries, archiveBytes, uncompressedBytes, warnings}}
     * @throws JsUserRuntimeException if writing is disabled, the archive is missing/invalid
     *         or the entry does not exist
     */
    public static Map<String, Object> zipEntryDelete(String filePath, String entryName) {
        requireWriteEnabled("zipEntryDelete(path, name)");
        if (entryName == null || entryName.isBlank()) {
            throw new IllegalArgumentException("entry name must not be empty");
        }
        Path path = requireRegularFile(filePath);
        List<String> warnings = new ArrayList<>();
        try (ZipFile zf = openZip(path)) {
            List<ZipEntry> all = entryList(zf);
            rejectDuplicateEntryNames(all);
            List<ZipEntry> matches = new ArrayList<>();
            for (ZipEntry e : all) {
                if (entryName.equals(e.getName())) {
                    matches.add(e);
                }
            }
            if (matches.isEmpty()) {
                throw entryNotFound(entryName, path, all);
            }
            List<WriteItem> items = new ArrayList<>();
            for (ZipEntry e : all) {
                if (!entryName.equals(e.getName())) {
                    items.add(copyItem(e));
                }
            }
            if (isDirectoryEntry(matches.get(0))) {
                int children = 0;
                for (ZipEntry e : all) {
                    if (!entryName.equals(e.getName()) && e.getName().startsWith(entryName)) {
                        children++;
                    }
                }
                if (children > 0) {
                    warn(warnings, "'" + entryName + "' was a directory entry; " + children
                            + " entries under it remain (ZIP has no real directories - delete them by name too)");
                }
            }
            warnAboutExtraFields(all, warnings);
            warnAboutSignatures(all, warnings);
            Map<String, Object> summary = writeArchive(zf, path, items, zf.getComment(), null, warnings);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("path", JsFileSystem.toRelative(path));
            result.put("action", "deleted");
            result.put("name", entryName);
            result.put("deleted", matches.size());
            result.put("entriesBefore", all.size());
            result.put("entriesAfter", items.size());
            result.putAll(summary);
            return result;
        } catch (IOException e) {
            LOG.error("Failed to rewrite zip archive: " + path, e);
            throw new JsUserRuntimeException("Failed to read zip archive: " + JsFileSystem.toRelative(path), e);
        }
    }

    /**
     * Writes {@code items} to a temporary file next to {@code target}, verifies the result and
     * moves it into place atomically. Never leaves a partial archive behind: on any failure the
     * temporary file is removed and {@code target} keeps its previous content.
     *
     * @param source         archive the untouched entries come from (streamed from here),
     *                       {@code null} when everything is written anew
     * @param target         archive path to replace
     * @param items          final entry list, in the order to write
     * @param archiveComment archive comment, may be {@code null}
     * @param level          DEFLATE level or {@code null}
     * @param warnings       warning collector of the calling operation
     * @return summary map {@code {entries, archiveBytes, uncompressedBytes, warnings}}
     */
    private static Map<String, Object> writeArchive(ZipFile source, Path target, List<WriteItem> items,
            String archiveComment, Integer level, List<String> warnings) {
        Path dir = target.getParent();
        Path tmp = null;
        long uncompressed = 0;
        try {
            if (dir != null) {
                Files.createDirectories(dir);
            }
            tmp = Files.createTempFile(dir, "." + target.getFileName() + ".", TMP_SUFFIX);
            copyPosixPermissions(target, tmp);
            try (OutputStream fos = new BufferedOutputStream(Files.newOutputStream(tmp));
                    ZipOutputStream zos = new ZipOutputStream(fos, StandardCharsets.UTF_8)) {
                // No ZIP64 knob in java.util.zip: the JDK switches automatically when a single
                // entry exceeds 4 GiB, which MAX_ENTRY_BYTES / MAX_ARCHIVE_BYTES prevent.
                if (level != null) {
                    zos.setLevel(level);
                }
                if (archiveComment != null && !archiveComment.isEmpty()) {
                    zos.setComment(archiveComment);
                }
                for (WriteItem item : items) {
                    uncompressed += writeItem(zos, source, item);
                    checkArchiveBudget(uncompressed);
                }
                zos.finish();
            }
            verifyRewrite(tmp, items);
            moveIntoPlace(tmp, target);
            tmp = null;
        } catch (IOException e) {
            LOG.error("Failed to write zip archive: " + target, e);
            throw new JsUserRuntimeException("Failed to write zip archive: " + JsFileSystem.toRelative(target), e);
        } finally {
            deleteQuietly(tmp);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("entries", items.size());
        try {
            summary.put("archiveBytes", Files.size(target));
        } catch (IOException e) {
            summary.put("archiveBytes", null); // cosmetic only, the archive is in place
        }
        summary.put("uncompressedBytes", uncompressed);
        summary.put("warnings", warnings);
        return summary;
    }

    /**
     * Writes one entry (either freshly from a byte array or copied from the source archive).
     * @return number of uncompressed bytes written
     */
    private static long writeItem(ZipOutputStream zos, ZipFile source, WriteItem item) throws IOException {
        ZipEntry out = new ZipEntry(item.name());
        out.setMethod(item.method());
        long written;
        ZipEntry src = item.source();
        if (src != null) {
            if (src.getComment() != null) {
                out.setComment(src.getComment());
            }
            FileTime mtime = sourceModifiedTime(src);
            if (mtime != null) {
                out.setLastModifiedTime(mtime);
            }
            boolean stored = item.method() == ZipEntry.STORED;
            long declaredSize = src.getSize();
            long declaredCrc = src.getCrc();
            if (stored && declaredSize >= 0 && declaredSize == src.getCompressedSize() && declaredCrc >= 0) {
                // The usual case: metadata from the central directory is trustworthy, so the
                // entry is streamed through without buffering (STORED bytes are identical).
                out.setSize(declaredSize);
                out.setCrc(declaredCrc);
                zos.putNextEntry(out);
                try (InputStream in = source.getInputStream(src)) {
                    written = pipe(in, zos, declaredSize);
                }
                if (written != declaredSize) {
                    throw new JsUserRuntimeException("Entry '" + item.name() + "' declares " + declaredSize
                            + " bytes but the archive holds " + written + " (corrupt archive?)");
                }
            } else {
                byte[] buffer;
                try (InputStream in = source.getInputStream(src)) {
                    buffer = readBounded(in, MAX_ENTRY_BYTES);
                }
                if (stored) {
                    CRC32 crc = new CRC32();
                    crc.update(buffer);
                    out.setSize(buffer.length);
                    out.setCrc(crc.getValue());
                }
                zos.putNextEntry(out);
                zos.write(buffer);
                written = buffer.length;
            }
        } else {
            byte[] data = item.data() == null ? new byte[0] : item.data();
            if (item.method() == ZipEntry.STORED) {
                // STORED requires size and CRC up front.
                CRC32 crc = new CRC32();
                crc.update(data);
                out.setSize(data.length);
                out.setCrc(crc.getValue());
            }
            if (item.mtimeMillis() != null) {
                // No clamping: timestamps outside the DOS range are written by the JDK through
                // the extended timestamp extra field. Resolution stays at the ZIP granularity.
                out.setLastModifiedTime(FileTime.from(item.mtimeMillis(), TimeUnit.MILLISECONDS));
            }
            if (item.comment() != null) {
                out.setComment(item.comment());
            }
            zos.putNextEntry(out);
            zos.write(data);
            written = data.length;
        }
        zos.closeEntry();
        return written;
    }

    /**
     * Opens the rewritten archive again and checks that it is a valid ZIP holding exactly
     * the expected entries with the expected content for every newly written entry.
     * <p>
     * Entry names are compared exactly over the full entry list on purpose:
     * {@link ZipFile#getEntry(String)} also matches a directory entry (looking up
     * {@code name} finds {@code name/}), which would let a missing entry pass as written.
     * </p>
     */
    private static void verifyRewrite(Path tmp, List<WriteItem> items) {
        try (ZipFile zf = openZip(tmp)) {
            List<ZipEntry> written = entryList(zf);
            if (written.size() != items.size()) {
                throw new JsUserRuntimeException("Rewrite check failed: the new archive holds " + written.size()
                        + " entries instead of " + items.size() + " (original file unchanged)");
            }
            Map<String, ZipEntry> byName = new HashMap<>();
            for (ZipEntry e : written) {
                byName.putIfAbsent(e.getName(), e);
            }
            for (WriteItem item : items) {
                if (item.data() == null) {
                    continue;
                }
                ZipEntry e = byName.get(item.name());
                if (e == null) {
                    throw new JsUserRuntimeException("Rewrite check failed: entry '" + item.name()
                            + "' is missing in the new archive (original file unchanged)");
                }
                CRC32 crc = new CRC32();
                crc.update(item.data());
                if (e.getSize() != item.data().length || e.getCrc() != crc.getValue()) {
                    throw new JsUserRuntimeException("Rewrite check failed: entry '" + item.name()
                            + "' was written with a different size or CRC (original file unchanged)");
                }
            }
        } catch (IOException e) {
            throw new JsUserRuntimeException(
                    "Rewrite check failed: the new archive is not a valid ZIP (original file unchanged)", e);
        }
    }

    /**
     * Replaces the target with the temporary file, atomically where the file system allows it.
     */
    private static void moveIntoPlace(Path tmp, Path target) {
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e2) {
                e2.addSuppressed(e);
                throw replaceFailed(target, e2);
            }
        } catch (IOException e) {
            throw replaceFailed(target, e);
        }
    }

    private static JsUserRuntimeException replaceFailed(Path target, IOException cause) {
        LOG.error("Failed to move the rewritten archive into place: " + target, cause);
        return new JsUserRuntimeException("Failed to replace " + JsFileSystem.toRelative(target)
                + ": the rewritten archive could not be moved into place (is the archive open in another"
                + " program such as Excel?). The original file is unchanged", cause);
    }

    /**
     * Copies the POSIX permissions of the original file to the temporary file so that the
     * rewritten archive does not end up with the restrictive permissions of a fresh temp file.
     * Silently ignored on platforms without POSIX permissions.
     */
    private static void copyPosixPermissions(Path from, Path to) {
        try {
            if (!Files.exists(from, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            PosixFileAttributes attrs = Files.readAttributes(from, PosixFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            Files.setPosixFilePermissions(to, attrs.permissions());
        } catch (IOException | RuntimeException e) {
            // Best effort only (Windows, or the file appeared between the calls).
        }
    }

    /**
     * Modification time of a source entry, {@code null} when it does not report one.
     * Timestamps outside the DOS range are handled by the JDK itself (extended timestamp
     * extra field), so no clamping is needed here.
     */
    private static FileTime sourceModifiedTime(ZipEntry src) {
        try {
            return src.getLastModifiedTime();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Rejects compression methods that cannot be rewritten.
     * <p>
     * Normally unreachable: {@link ZipFile} refuses archives with other methods
     * ("invalid CEN header (bad compression method)") while parsing the central
     * directory, so such an archive never reaches this point. Kept as a guard
     * because {@code ZipOutputStream} would fail with a far less helpful message.
     * </p>
     */
    private static WriteItem copyItem(ZipEntry entry) {
        if (!isSupportedMethod(entry.getMethod())) {
            throw new JsUserRuntimeException("Entry '" + entry.getName() + "' uses compression method "
                    + methodToString(entry.getMethod())
                    + "; only STORED and DEFLATED entries can be rewritten");
        }
        return WriteItem.ofCopy(entry);
    }

    /**
     * Rejects archives that hold the same entry name more than once.
     * <p>
     * {@link ZipFile#getInputStream(ZipEntry)} resolves the entry data <em>by name</em>, so
     * when several central directory records share a name the JDK cannot tell which bytes
     * belong to which record: a rewrite would silently mix contents or fail with a size
     * mismatch. Refusing is the only sound answer; rebuilding with {@code zipCreate} keeps
     * one entry per name.
     * </p>
     */
    private static void rejectDuplicateEntryNames(List<ZipEntry> entries) {
        Set<String> seen = new HashSet<>();
        Set<String> duplicates = new LinkedHashSet<>();
        for (ZipEntry e : entries) {
            if (!seen.add(e.getName())) {
                duplicates.add(e.getName());
            }
        }
        if (duplicates.isEmpty()) {
            return;
        }
        List<String> shown = duplicates.stream().limit(5).toList();
        throw new JsUserRuntimeException("The archive holds duplicate entry names (" + String.join(", ", shown)
                + (duplicates.size() > shown.size() ? ", ..." : "") + "). Several entries with one name cannot be"
                + " told apart when reading, so this archive is not rewritten. Rebuild it with"
                + " archive.zipCreate(path, entries) keeping one entry per name"
                + " (original file unchanged)");
    }

    /**
     * Warns once that ZIP extra fields of existing entries are not reproduced. They are the
     * main reason a rewrite is content-preserving but not byte-preserving (NTFS timestamps,
     * zipalign padding, ...).
     */
    private static void warnAboutExtraFields(List<ZipEntry> entries, List<String> warnings) {
        int count = 0;
        for (ZipEntry e : entries) {
            try {
                byte[] extra = e.getExtra();
                if (extra != null && extra.length > 0) {
                    count++;
                }
            } catch (RuntimeException ignored) {
                count++;
            }
        }
        if (count > 0) {
            warn(warnings, count + " existing entries carry ZIP extra fields (e.g. NTFS timestamps,"
                    + " zipalign padding); a rewrite does not preserve them - content is preserved,"
                    + " byte identity is not");
        }
    }

    /**
     * Warns when the archive holds signature entries: any rewrite invalidates them.
     */
    private static void warnAboutSignatures(List<ZipEntry> entries, List<String> warnings) {
        List<String> signatures = new ArrayList<>();
        for (ZipEntry e : entries) {
            if (isSignatureEntry(e.getName())) {
                signatures.add(e.getName());
            }
        }
        if (signatures.isEmpty()) {
            return;
        }
        String shown = String.join(", ", signatures.subList(0, Math.min(3, signatures.size())));
        warn(warnings, "Archive is signed (" + shown
                + (signatures.size() > 3 ? " and " + (signatures.size() - 3) + " more" : "")
                + "): the rewrite invalidates the signature, 'jarsigner -verify' and signature"
                + " checks will fail");
    }

    private static boolean isSignatureEntry(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("META-INF/")) {
            return false;
        }
        return upper.endsWith(".SF") || upper.endsWith(".DSA") || upper.endsWith(".RSA") || upper.endsWith(".EC");
    }

    /**
     * Error for a missing entry name, with similar names to recover from a typo.
     */
    private static JsUserRuntimeException entryNotFound(String entryName, Path path, List<ZipEntry> all) {
        String needle = entryName.toLowerCase(Locale.ROOT);
        List<String> similar = all.stream()
                .map(ZipEntry::getName)
                .filter(name -> {
                    String lower = name.toLowerCase(Locale.ROOT);
                    return lower.contains(needle) || needle.contains(lower);
                })
                .sorted()
                .limit(10)
                .toList();
        StringBuilder message = new StringBuilder("Entry not found in ")
                .append(JsFileSystem.toRelative(path)).append(": '").append(entryName)
                .append("' (the archive has ").append(all.size()).append(" entries)");
        if (!similar.isEmpty()) {
            message.append(". Similar names: ").append(String.join(", ", similar));
        }
        message.append(". List all entries with archive.zipEntries(")
                .append(JsFileSystem.toRelative(path)).append(")");
        return new JsUserRuntimeException(message.toString());
    }

    /**
     * Validates an entry name (the archive-internal path). Extraction is never performed, so
     * this is not a zip-slip guard but a guard against names no other tool can read back.
     */
    private static void validateEntryName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("entry name must not be empty");
        }
        if (name.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("entry name must use '/' as separator, not '\\\\': " + name);
        }
        if (name.startsWith("/")) {
            throw new IllegalArgumentException("entry name must be relative to the archive root: " + name);
        }
        if (name.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("entry name must not start with a drive letter: " + name);
        }
        for (int i = 0; i < name.length(); i++) {
            if (name.charAt(i) < 0x20) {
                throw new IllegalArgumentException("entry name must not contain control characters: " + name);
            }
        }
        for (String segment : name.split("/")) {
            if ("..".equals(segment)) {
                throw new IllegalArgumentException("entry name must not contain '..': " + name);
            }
        }
        if (name.getBytes(StandardCharsets.UTF_8).length > 65535) {
            throw new IllegalArgumentException("entry name is longer than 65535 bytes (ZIP limit): " + name);
        }
    }

    /**
     * Validates entry content: single-entry size limit and the "directory entries are empty" rule.
     */
    private static void validateEntryContent(String name, byte[] data) {
        if (data.length > MAX_ENTRY_BYTES) {
            throw new JsUserRuntimeException("Entry '" + name + "' has " + data.length + " bytes, exceeding the "
                    + "single-entry limit of " + MAX_ENTRY_BYTES + " bytes");
        }
        if (name.endsWith("/") && data.length > 0) {
            throw new IllegalArgumentException("A directory entry (name ending in '/') must be empty, but '"
                    + name + "' has " + data.length + " bytes");
        }
    }

    /**
     * Maps the {@code method} option to a {@link ZipEntry} method constant.
     */
    private static int resolveMethod(String method, int fallback) {
        if (method == null || method.isBlank()) {
            return fallback;
        }
        return switch (method.trim().toLowerCase(Locale.ROOT)) {
            case "store", "stored", "0" -> ZipEntry.STORED;
            case "deflate", "deflated", "8" -> ZipEntry.DEFLATED;
            default -> throw new IllegalArgumentException("method must be 'deflate' or 'store' (got '" + method + "')");
        };
    }

    private static void validateCompressionLevel(Integer level) {
        if (level != null && (level < -1 || level > 9)) {
            throw new IllegalArgumentException("level must be -1 (default) or 0..9 (got " + level + ")");
        }
    }

    private static void checkEntryCount(int count) {
        if (count > MAX_ZIP_ENTRIES) {
            throw new JsUserRuntimeException("The rewritten archive would hold " + count + " entries, exceeding "
                    + "the limit of " + MAX_ZIP_ENTRIES + " (ZIP64 is deliberately not produced)");
        }
    }

    private static void checkArchiveBudget(long uncompressedBytes) {
        if (uncompressedBytes > MAX_ARCHIVE_BYTES) {
            throw new JsUserRuntimeException("The rewritten archive would hold " + uncompressedBytes
                    + " uncompressed bytes, exceeding the limit of " + MAX_ARCHIVE_BYTES);
        }
    }

    private static boolean isSupportedMethod(int method) {
        return method == ZipEntry.STORED || method == ZipEntry.DEFLATED;
    }

    private static boolean isDirectoryEntry(ZipEntry entry) {
        return entry.isDirectory() || entry.getName().endsWith("/");
    }

    /**
     * Entry list of an open archive (central directory only, no entry data is read).
     */
    private static List<ZipEntry> entryList(ZipFile zf) {
        List<ZipEntry> list = new ArrayList<>();
        Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            list.add(en.nextElement());
        }
        return list;
    }

    /**
     * CRC-32 of a byte array as the eight lowercase hex digits used by {@code zipEntries}.
     */
    private static String crcHex(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return String.format("%08x", crc.getValue());
    }

    /**
     * Copies {@code in} to {@code out} up to {@code limit} bytes.
     * @return number of bytes copied
     */
    private static long pipe(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buffer = new byte[STREAM_BUFFER_SIZE];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) > 0) {
            total += n;
            if (total > limit) {
                throw new JsUserRuntimeException("Entry data exceeds the expected " + limit
                        + " bytes (corrupt archive?)");
            }
            out.write(buffer, 0, n);
        }
        return total;
    }

    /**
     * Adds a warning, capped at {@link #MAX_WARNINGS} entries.
     */
    private static void warn(List<String> warnings, String message) {
        if (warnings.size() < MAX_WARNINGS) {
            warnings.add(message);
        } else if (warnings.size() == MAX_WARNINGS) {
            warnings.add("(more warnings suppressed)");
        }
    }

    /**
     * Deletes a leftover temporary file, ignoring any failure.
     */
    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOG.warn("Could not delete temporary archive file: {}", path, e);
        }
    }

    // ========================================================================
    // GZIP / DEFLATE (byte streams, java.util.zip)
    // ========================================================================

    /**
     * Compresses bytes with the gzip format (RFC 1952), as produced by {@code gzip -c}.
     * @param data bytes to compress (must not be null)
     * @return gzip-compressed bytes
     * @throws IllegalArgumentException if data is null
     */
    public static byte[] gzip(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(data);
        } catch (IOException e) {
            throw new IllegalStateException("gzip failed", e);
        }
        return out.toByteArray();
    }

    /**
     * Decompresses bytes of the gzip format (RFC 1952), as produced by {@code gzip -c}.
     * @param data gzip-compressed bytes
     * @return decompressed bytes
     * @throws IllegalArgumentException if data is null
     * @throws JsUserRuntimeException if the data is not valid gzip or the result exceeds the limit
     */
    public static byte[] gunzip(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return readBounded(gz, MAX_DECOMPRESSED_BYTES);
        } catch (IOException e) {
            throw new JsUserRuntimeException(
                    "invalid gzip data (not a gzip stream or corrupt): " + e.getMessage());
        }
    }

    /**
     * Decompresses a gzip file (e.g. {@code .gz}, {@code .tar.gz}) by streaming; the result
     * is returned as bytes and fits in memory (bounded by {@link #MAX_DECOMPRESSED_BYTES}).
     * @param filePath path relative to the base directory
     * @return decompressed bytes
     * @throws JsUserRuntimeException if the file is missing, not valid gzip or the result is too large
     */
    public static byte[] gunzipFile(String filePath) {
        Path path = requireRegularFile(filePath);
        try (InputStream raw = Files.newInputStream(path);
             GZIPInputStream gz = new GZIPInputStream(raw)) {
            return readBounded(gz, MAX_DECOMPRESSED_BYTES);
        } catch (IOException e) {
            LOG.error("Failed to gunzip file: " + path, e);
            throw new JsUserRuntimeException("Failed to gunzip file: " + JsFileSystem.toRelative(path), e);
        }
    }

    /**
     * Compresses bytes with the raw DEFLATE algorithm (RFC 1951), as used inside ZIP entries.
     * @param data bytes to compress (must not be null)
     * @return raw deflate bytes
     * @throws IllegalArgumentException if data is null
     */
    public static byte[] deflate(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true); // nowrap = raw deflate
        try {
            deflater.setInput(data);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            while (!deflater.finished()) {
                int n = deflater.deflate(buf);
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /**
     * Decompresses bytes of the raw DEFLATE format (RFC 1951), as used inside ZIP entries.
     * @param data raw deflate bytes
     * @return decompressed bytes
     * @throws IllegalArgumentException if data is null
     * @throws JsUserRuntimeException if the data is not valid deflate or the result exceeds the limit
     */
    public static byte[] inflate(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        Inflater inflater = new Inflater(true); // nowrap = raw deflate
        try {
            inflater.setInput(data);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            long total = 0;
            while (!inflater.finished() && inflater.getRemaining() > 0) {
                int n;
                try {
                    n = inflater.inflate(buf);
                } catch (DataFormatException e) {
                    throw new JsUserRuntimeException("invalid deflate data: " + e.getMessage());
                }
                if (n == 0) {
                    // No progress: either all input was consumed without a final marker
                    // (return what we have) or the stream is genuinely incomplete.
                    if (inflater.needsInput()) {
                        break;
                    }
                    throw new JsUserRuntimeException("invalid deflate data (incomplete stream)");
                }
                total += n;
                if (total > MAX_DECOMPRESSED_BYTES) {
                    throw new JsUserRuntimeException(
                            "Decompressed data exceeds the limit of " + MAX_DECOMPRESSED_BYTES + " bytes");
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            inflater.end();
        }
    }

    // ========================================================================
    // TAR
    // ========================================================================

    /**
     * Checks the three gzip magic bytes ({@code 1F 8B 08}, see {@link #GZIP_MAGIC}).
     * @param data bytes to inspect (may be shorter than three bytes)
     * @param length number of valid bytes at the start of {@code data}
     * @return true when the data starts with a gzip stream (DEFLATE method)
     */
    private static boolean hasGzipMagic(byte[] data, int length) {
        if (data == null || length < GZIP_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < GZIP_MAGIC.length; i++) {
            if (data[i] != GZIP_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Wraps a tar byte source so that a gzip-compressed tarball is decompressed on the fly.
     * <p>
     * The decision is made on the first three bytes of the stream, never on the file name:
     * {@code .tar.gz}, {@code .tgz} and a plain {@code .tar} all go through this method and
     * only the ones that really start with {@code 1F 8B 08} are decompressed. That also
     * covers a tarball stored under a misleading name and rules out a plain tar whose name
     * merely ends in {@code .gz}.
     * </p>
     * @param raw uncompressed source (file or in-memory stream), ownership is transferred
     * @return a stream over the archive content: the decompressed gzip stream when the source
     *         starts with {@code 1F 8B 08} (capped at {@link #MAX_DECOMPRESSED_BYTES}),
     *         otherwise the bytes as they are
     * @throws IOException when the header cannot be read
     */
    private static InputStream gzipAwareStream(InputStream raw) throws IOException {
        PushbackInputStream in = new PushbackInputStream(raw, GZIP_MAGIC.length);
        byte[] head = in.readNBytes(GZIP_MAGIC.length);
        if (head.length > 0) {
            in.unread(head);
        }
        return hasGzipMagic(head, head.length) ? new GzipLimitedInputStream(in) : in;
    }

    /**
     * A {@link GZIPInputStream} that caps the number of <em>decompressed</em> bytes.
     * <p>
     * The tar reader walks a tarball sequentially, so every byte of a gzipped tar goes
     * through this stream &ndash; including the data of entries that are only skipped while
     * listing. Without a cap a small compressed file could turn into endless decompression
     * work, which is exactly what {@link #gunzip(byte[])} already refuses.
     * </p>
     * <p>
     * All accounting happens in {@link #read(byte[], int, int)}: the {@code skip} of a
     * DEFLATE stream inflates the bytes anyway, so skipping is done by reading and cannot
     * escape the counter. {@code skip} and {@code available} never report more than what is
     * left below the limit, otherwise a caller computing "skip the rest" would step over it
     * unnoticed.
     * </p>
     * <p>
     * Nothing is needed at the two ends of the stream. {@code GZIPInputStream} verifies CRC
     * and size inside {@code read()} when a member finishes &ndash; and restarts the inflater
     * for concatenated members, which therefore work here as well &ndash; whereas
     * {@code close()} only releases the inflater and closes the source. The early stop of
     * {@link #tarEntry(String, String)} after the matching entry thus neither triggers a
     * bogus trailer error nor needs an overridden {@code close()}.
     * </p>
     */
    private static final class GzipLimitedInputStream extends GZIPInputStream {

        /** Maximum number of decompressed bytes. */
        private final long limit;
        /** Number of decompressed bytes so far. */
        private long total;
        /** Reused sink for skipping. */
        private byte[] skipSink;

        /**
         * @param in the raw (possibly gzipped) source; the larger-than-default inflate buffer
         *        keeps the number of reads on it reasonable for big tarballs
         */
        GzipLimitedInputStream(InputStream in) throws IOException {
            super(in, STREAM_BUFFER_SIZE);
            this.limit = MAX_DECOMPRESSED_BYTES;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            if (n <= 0) {
                return 0;
            }
            long allowed = Math.min(n, remaining());
            if (allowed == 0) {
                count(n); // reports the limit instead of silently stepping over it
            }
            if (skipSink == null) {
                skipSink = new byte[STREAM_BUFFER_SIZE];
            }
            long done = 0;
            while (done < allowed) {
                int got = read(skipSink, 0, (int) Math.min(skipSink.length, allowed - done));
                if (got <= 0) {
                    break; // end of stream: the tar reader reports the truncation
                }
                done += got; // read() has already accounted for these bytes
            }
            return done;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(super.available(), remaining());
        }

        /** Bytes still allowed to be decompressed. */
        private long remaining() {
            return Math.max(0, limit - total);
        }

        private void count(long n) throws IOException {
            total += n;
            if (total > limit) {
                throw new IOException("gzip-compressed tar exceeds the decompression limit of "
                        + limit + " bytes");
            }
        }
    }

    /**
     * Lists all entries of a tar archive (ustar/GNU/basic POSIX pax).
     * <p>
     * Headers are parsed in a single pass; entry data is skipped, so listing is possible
     * with constant memory no matter how large the individual entries are. A gzip-compressed
     * tarball ({@code .tar.gz}, {@code .tgz}) is decompressed transparently &ndash; detected
     * by the magic bytes {@code 1F 8B 08}, not by the file name &ndash; so one call is enough.
     * </p>
     * @param filePath archive path relative to the base directory; a plain tar or a gzipped tar
     * @return list of maps
     *         {@code {name, size, type, isFile, isDirectory, isSymbolicLink, isHardLink, linkName, mode, mtime}},
     *         sorted by name
     * @throws JsUserRuntimeException if the archive is missing or not a supported tar
     */
    public static List<Map<String, Object>> tarEntries(String filePath) {
        Path path = requireRegularFile(filePath);
        try (InputStream file = new BufferedInputStream(Files.newInputStream(path), STREAM_BUFFER_SIZE);
                InputStream in = gzipAwareStream(file)) {
            return new TarScanner(in).listEntries();
        } catch (IOException e) {
            LOG.error("Failed to read tar archive: " + path, e);
            throw new JsUserRuntimeException("Failed to read tar archive: " + JsFileSystem.toRelative(path)
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()), e);
        }
    }

    /**
     * Extracts a single entry of a tar archive as a byte array.
     * <p>
     * The archive is scanned in a single pass and reading stops once the matching entry
     * has been found. Names are resolved through GNU long-name and POSIX pax headers.
     * A gzip-compressed tarball is decompressed transparently, exactly as in
     * {@link #tarEntries(String)}.
     * </p>
     * @param filePath archive path relative to the base directory; a plain tar or a gzipped tar
     * @param entryName name of the entry within the archive
     * @return entry content, {@code null} if the entry does not exist, an empty array
     *         for a non-regular entry (directory, symlink, hard link, device, fifo)
     * @throws JsUserRuntimeException if the archive is missing, not a supported tar or the entry is too large
     */
    public static byte[] tarEntry(String filePath, String entryName) {
        if (entryName == null || entryName.isBlank()) {
            throw new IllegalArgumentException("entry name must not be empty");
        }
        Path path = requireRegularFile(filePath);
        try (InputStream file = new BufferedInputStream(Files.newInputStream(path), STREAM_BUFFER_SIZE);
                InputStream in = gzipAwareStream(file)) {
            return new TarScanner(in).readEntry(entryName);
        } catch (IOException e) {
            LOG.error("Failed to read tar archive: " + path, e);
            throw new JsUserRuntimeException("Failed to read tar archive: " + JsFileSystem.toRelative(path)
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()), e);
        }
    }

    /**
     * Lists all entries of an in-memory tar archive. Semantics identical to
     * {@link #tarEntries(String)}, including the transparent gzip handling: bytes that
     * start with {@code 1F 8B 08} are decompressed first, so the raw content of a
     * {@code .tar.gz}/{@code .tgz} file (from {@code fs.readBytes} or {@link #gunzip(byte[])})
     * can be handed over directly.
     * @param data tar bytes or gzipped tar bytes (must not be null)
     * @return list of entry maps, sorted by name
     * @throws IllegalArgumentException if data is null
     * @throws JsUserRuntimeException if the data is not a supported tar
     */
    public static List<Map<String, Object>> tarEntries(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("tar data must not be null");
        }
        try (InputStream in = gzipAwareStream(new ByteArrayInputStream(data))) {
            return new TarScanner(in).listEntries();
        } catch (IOException e) {
            // Only reachable for a gzipped source (truncated stream or decompression limit).
            throw new JsUserRuntimeException("Failed to read tar data: " + e.getMessage(), e);
        }
    }

    /**
     * Extracts a single entry of an in-memory tar archive. Semantics identical to
     * {@link #tarEntry(String, String)}, including the transparent gzip handling for
     * gzipped tar bytes.
     * @param data tar bytes or gzipped tar bytes (must not be null)
     * @param entryName name of the entry within the archive
     * @return entry content, {@code null} if not found, empty array for a non-regular entry
     * @throws IllegalArgumentException if data is null or the entry name is empty
     * @throws JsUserRuntimeException if the data is not a supported tar
     */
    public static byte[] tarEntry(byte[] data, String entryName) {
        if (data == null) {
            throw new IllegalArgumentException("tar data must not be null");
        }
        if (entryName == null || entryName.isBlank()) {
            throw new IllegalArgumentException("entry name must not be empty");
        }
        try (InputStream in = gzipAwareStream(new ByteArrayInputStream(data))) {
            return new TarScanner(in).readEntry(entryName);
        } catch (IOException e) {
            // Only reachable for a gzipped source (truncated stream or decompression limit).
            throw new JsUserRuntimeException("Failed to read tar data: " + e.getMessage(), e);
        }
    }

    /**
     * Streams an InputStream up to {@code maxBytes} into a byte array.
     */
    private static byte[] readBounded(InputStream in, long maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[STREAM_BUFFER_SIZE];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > maxBytes) {
                throw new JsUserRuntimeException(
                        "Data exceeds the size limit of " + maxBytes + " bytes");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    // ========================================================================
    // Minimal tar parser (ustar / GNU / basic POSIX pax)
    // ========================================================================

    /**
     * A parsed 512-byte tar header.
     */
    private static final class TarHeader {
        String name;
        String prefix;
        String linkname;
        String magic;
        String uname;
        String gname;
        long mode = -1;
        long uid = -1;
        long gid = -1;
        long size = -1;
        long mtime = -1;
        long chksum = -1;
        char typeflag;
    }

    /**
     * An effective (real) tar entry: name/size resolved through GNU long names and pax.
     */
    private static final class TarRecord {
        String name;
        long size;
        long mode;
        long mtime;
        String linkName;
        char type;
        boolean isRegular;
        boolean directory;
    }

    /**
     * Minimal, single-pass tar reader.
     */
    private static final class TarScanner {

        private final InputStream in;
        private final byte[] block = new byte[TAR_BLOCK_SIZE];

        /** Pending GNU long name (from an 'L' record), applies to the next real entry. */
        private String longName;
        /** Pending pax key/value overrides (from 'x'/'g' records), apply to the next real entry. */
        private Map<String, String> pax;

        TarScanner(InputStream in) {
            this.in = in;
        }

        // ----------------------------------------------------------------
        // Public API
        // ----------------------------------------------------------------

        List<Map<String, Object>> listEntries() throws IOException {
            List<Map<String, Object>> entries = new ArrayList<>();
            TarRecord rec;
            while ((rec = nextRecord()) != null) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", rec.name);
                m.put("size", rec.size);
                m.put("type", typeName(rec.type));
                m.put("isFile", rec.isRegular);
                m.put("isDirectory", rec.directory);
                m.put("isSymbolicLink", rec.type == '2');
                m.put("isHardLink", rec.type == '1');
                m.put("linkName", rec.linkName);
                m.put("mode", rec.mode >= 0 ? String.format("%04o", rec.mode & 07777) : null);
                m.put("mtime", rec.mtime >= 0 ? rec.mtime : null);
                entries.add(m);
                skipData(rec.size);
            }
            entries.sort(Comparator.comparing((Map<String, Object> e) -> (String) e.get("name")));
            return entries;
        }

        byte[] readEntry(String requestedName) throws IOException {
            TarRecord rec;
            while ((rec = nextRecord()) != null) {
                if (requestedName.equals(rec.name)) {
                    if (rec.isRegular) {
                        return readData(rec.size);
                    }
                    skipData(rec.size);
                    return new byte[0]; // directory / link / device / fifo: no content
                }
                skipData(rec.size);
            }
            return null; // not found
        }

        // ----------------------------------------------------------------
        // Record loop
        // ----------------------------------------------------------------

        private TarRecord nextRecord() throws IOException {
            while (true) {
                if (!readHeaderBlock()) {
                    return null; // clean end of archive
                }
                TarHeader h = parseHeader(block);
                validateChecksum(block, h);
                if (h.size < 0) {
                    throw new JsUserRuntimeException(
                            "tar entry in an unsupported size encoding (base-256 sizes are not handled)");
                }
                switch (h.typeflag) {
                    case 'L' -> { // GNU long name
                        longName = readNulString(h.size);
                    }
                    case 'K' -> { // GNU long link target (not needed for byte extraction)
                        skipData(h.size);
                    }
                    case 'x', 'g' -> { // POSIX pax extended header (local / global)
                        if (pax == null) {
                            pax = new HashMap<>();
                        }
                        parsePax(readData(h.size), pax);
                    }
                    case 'S' -> throw new JsUserRuntimeException(
                            "tar sparse files ('S' type flag) are not supported");
                    default -> {
                        TarRecord rec = new TarRecord();
                        rec.type = h.typeflag;
                        rec.mode = h.mode;
                        rec.mtime = h.mtime;
                        rec.linkName = paxLinkPath(h);
                        rec.name = effectiveName(h);
                        rec.directory = h.typeflag == '5' || rec.name.endsWith("/");
                        if (rec.directory && rec.name.endsWith("/")) {
                            rec.name = rec.name.substring(0, rec.name.length() - 1);
                        }
                        rec.isRegular = h.typeflag == '0' || h.typeflag == '7';
                        rec.size = paxSize(h.size);
                        longName = null;
                        pax = null;
                        return rec;
                    }
                }
            }
        }

        private String effectiveName(TarHeader h) {
            String name;
            String paxPath = pax != null ? pax.get("path") : null;
            if (paxPath != null) {
                name = paxPath;
            } else if (longName != null) {
                name = longName;
            } else if (h.prefix != null && !h.prefix.isEmpty()) {
                name = h.prefix + "/" + h.name;
            } else {
                name = h.name;
            }
            return name != null ? name : "";
        }

        private long paxSize(long headerSize) {
            if (pax != null && pax.get("size") != null) {
                try {
                    return Long.parseLong(pax.get("size"));
                } catch (NumberFormatException ignored) {
                    // fall through to the header size
                }
            }
            return headerSize;
        }

        private String paxLinkPath(TarHeader h) {
            if (pax != null && pax.get("linkpath") != null) {
                return pax.get("linkpath");
            }
            return h.linkname;
        }

        private static String typeName(char type) {
            return switch (type) {
                case '0' -> "file";
                case '1' -> "hardlink";
                case '2' -> "symlink";
                case '3' -> "char";
                case '4' -> "block";
                case '5' -> "directory";
                case '6' -> "fifo";
                case '7' -> "contiguous";
                default -> "other(" + type + ")";
            };
        }

        // ----------------------------------------------------------------
        // Low-level reading
        // ----------------------------------------------------------------

        private boolean readHeaderBlock() throws IOException {
            int off = 0;
            while (off < TAR_BLOCK_SIZE) {
                int n = in.read(block, off, TAR_BLOCK_SIZE - off);
                if (n < 0) {
                    if (off == 0) {
                        return false; // clean EOF between records
                    }
                    throw new JsUserRuntimeException("tar archive is truncated (incomplete header block)");
                }
                off += n;
            }
            return !isZeroBlock(block);
        }

        private byte[] readData(long size) throws IOException {
            if (size > MAX_ENTRY_BYTES) {
                throw new JsUserRuntimeException(
                        "Entry has " + size + " bytes, exceeding the single-entry extraction limit of "
                        + MAX_ENTRY_BYTES + " bytes");
            }
            if (size == 0) {
                return new byte[0];
            }
            byte[] data = new byte[(int) size];
            readFully(data);
            skipPadding(size);
            return data;
        }

        private String readNulString(long size) throws IOException {
            byte[] data = readData(size);
            int len = 0;
            while (len < data.length && data[len] != 0) {
                len++;
            }
            return new String(data, 0, len, StandardCharsets.UTF_8);
        }

        private void readFully(byte[] data) throws IOException {
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n < 0) {
                    throw new JsUserRuntimeException("tar archive is truncated (incomplete entry data)");
                }
                off += n;
            }
        }

        private void skipData(long size) throws IOException {
            skipFully(size);
            skipPadding(size);
        }

        private void skipPadding(long size) throws IOException {
            long pad = (TAR_BLOCK_SIZE - (size % TAR_BLOCK_SIZE)) % TAR_BLOCK_SIZE;
            skipFully(pad);
        }

        private void skipFully(long n) throws IOException {
            long remaining = n;
            while (remaining > 0) {
                long s = in.skip(remaining);
                if (s > 0) {
                    remaining -= s;
                } else if (in.read() < 0) {
                    throw new JsUserRuntimeException("tar archive is truncated");
                } else {
                    remaining--;
                }
            }
        }

        // ----------------------------------------------------------------
        // Header parsing
        // ----------------------------------------------------------------

        private static TarHeader parseHeader(byte[] b) {
            TarHeader h = new TarHeader();
            h.name = trimAscii(b, 0, 100);
            h.mode = parseOctal(b, 100, 8);
            h.uid = parseOctal(b, 108, 8);
            h.gid = parseOctal(b, 116, 8);
            h.size = parseOctal(b, 124, 12);
            h.mtime = parseOctal(b, 136, 12);
            h.chksum = parseOctal(b, 148, 8);
            h.typeflag = b[156] == 0 ? '0' : (char) (b[156] & 0xFF);
            h.linkname = trimAscii(b, 157, 100);
            h.magic = trimAscii(b, 257, 6);
            h.uname = trimAscii(b, 265, 32);
            h.gname = trimAscii(b, 297, 32);
            h.prefix = trimAscii(b, 345, 155);
            return h;
        }

        private static void validateChecksum(byte[] b, TarHeader h) {
            // Valid ustar/GNU headers always carry a parseable octal checksum that matches
            // the header bytes (with the checksum field treated as spaces). An unparseable
            // or mismatching checksum means this is not a (supported) tar header.
            long unsigned = checkSum(b, true);
            long signed = checkSum(b, false);
            if (h.chksum < 0 || (h.chksum != unsigned && h.chksum != signed)) {
                throw new JsUserRuntimeException("invalid tar archive (header checksum mismatch)");
            }
        }

        private static long checkSum(byte[] b, boolean unsigned) {
            long sum = 0;
            for (int i = 0; i < b.length; i++) {
                int v = (i >= 148 && i < 156) ? ' ' : (b[i] & 0xFF);
                sum += unsigned ? v : (byte) v;
            }
            return sum;
        }

        /**
         * Parses a NUL/space padded octal field; returns -1 for empty or base-256 fields.
         */
        private static long parseOctal(byte[] b, int off, int len) {
            int i = off;
            int end = off + len;
            while (i < end && (b[i] == ' ' || b[i] == 0)) {
                i++;
            }
            if (i < end && (b[i] & 0x80) != 0) {
                return -1; // base-256 encoding is not supported
            }
            long value = 0;
            boolean any = false;
            while (i < end) {
                byte c = b[i];
                if (c == 0 || c == ' ' || c == 0x7f) {
                    break;
                }
                if (c < '0' || c > '7') {
                    break;
                }
                value = (value << 3) | (c - '0');
                any = true;
                i++;
            }
            return any ? value : -1;
        }

        private static String trimAscii(byte[] b, int off, int len) {
            int end = off + len;
            while (end > off && (b[end - 1] == 0 || b[end - 1] == ' ')) {
                end--;
            }
            return new String(b, off, end - off, StandardCharsets.UTF_8);
        }

        private static boolean isZeroBlock(byte[] b) {
            for (byte x : b) {
                if (x != 0) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Parses pax records ("{len} key=value\n") into a map.
         * <p>
         * The key starts right after the space that terminates the length token, the
         * value is everything up to the trailing newline.
         * </p>
         */
        private static void parsePax(byte[] data, Map<String, String> map) {
            String text = new String(data, StandardCharsets.UTF_8);
            int idx = 0;
            int n = text.length();
            while (idx < n) {
                int sp = text.indexOf(' ', idx);
                if (sp < 0) {
                    break;
                }
                int recLen;
                try {
                    recLen = Integer.parseInt(text.substring(idx, sp));
                } catch (NumberFormatException e) {
                    break;
                }
                if (recLen <= 0 || idx + recLen > n) {
                    break;
                }
                String rec = text.substring(idx, idx + recLen);
                int keyStart = sp - idx + 1; // key starts after "<len> "
                int eq = rec.indexOf('=', keyStart);
                if (eq > keyStart) {
                    String value = rec.substring(eq + 1);
                    if (value.endsWith("\n")) {
                        value = value.substring(0, value.length() - 1);
                    }
                    map.put(rec.substring(keyStart, eq), value);
                }
                idx += recLen;
            }
        }
    }

    // ========================================================================
    // Help / Documentation
    // ========================================================================

    /**
     * Returns a help text describing the JS archive API with usage examples.
     * The ZIP write section reflects the current configuration: while
     * {@value #PROP_READONLY} is unset (the default) it is reported as disabled and the
     * write operations refuse to run.
     * @return help text as a multi-line string
     */
    public static String help() {
        String writeState = isWriteEnabled()
                ? "ZIP write - ENABLED via -D" + PROP_READONLY + "=false"
                : "ZIP write - DISABLED (read-only). Enable on the server with -D" + PROP_READONLY
                        + "=false and restart";
        String writeSection = """
                --- %s ---
                archive.zipCreate(path, entries[, options])
                                        - Create a new ZIP. entries = [{name, data|text, method?,
                                          mtime?, comment?}]: data is a Uint8Array/number array,
                                          text a string (option encoding, default UTF-8); a name
                                          ending in '/' writes a directory entry.
                                          options: {overwrite: false, comment, level: -1..9}.
                                          Fails if the file exists unless overwrite is true.
                                        -> {path, created, replaced, entries, archiveBytes,
                                            uncompressedBytes, warnings}
                archive.zipEntryWrite(path, name, data[, options])
                                        - Replace one entry, or append it if it is missing (upsert).
                                          data: Uint8Array, string (UTF-8) or null/omitted for
                                          empty. options: {method: 'deflate'|'store' (default: the
                                          method of the replaced entry), mtime (epoch millis, ISO
                                          string or Date), encoding (for text)}.
                                          The whole archive is rewritten: name, order, method,
                                          timestamp and comment of every other entry are kept, as
                                          is the archive comment. Writing goes to a temporary file,
                                          is verified (entry count + CRC) and then moved into place
                                          atomically - a failure never leaves a partial archive and
                                          the original file stays untouched.
                                          Content-preserving, NOT byte-preserving: DEFLATE output is
                                          not canonical and ZIP extra fields (NTFS timestamps,
                                          zipalign padding) are not copied. Signed archives
                                          (META-INF/*.SF) stop verifying - see the warnings result.
                                        -> {path, action: 'replaced'|'added', name, entriesBefore,
                                            entriesAfter, entryBytes, method, crc32, mtime, warnings}
                archive.zipEntryDelete(path, name)
                                        - Delete one entry (exact name as reported by zipEntries).
                                          A name that does not exist is an error listing similar
                                          names - never a silent no-op.
                                        -> {path, action: 'deleted', name, deleted, entriesBefore,
                                            entriesAfter, warnings}
                archive.status()        - {readonly, writeEnabled, property, propertyValue, limits}

                """.formatted(writeState);
        return """
                JS Archive API (namespace 'archive')
                ====================================

                Access to ZIP/tar archives and gzip/deflate byte streams (.zip, .jar, .war,
                .ear, .tar, .tar.gz, .tgz, .gz). A gzipped tarball is read by tarEntries/
                tarEntry in a single call - gzip is detected from the magic bytes 1F 8B 08,
                never from the file name. Archive files must live inside the project base
                directory; security is identical to fs.* (no '..', no absolute paths outside
                the base, no symbolic links leaving the base). Reading never extracts entries to
                disk (archive-internal path traversal cannot touch the file system); to persist
                extracted bytes use fs.writeBytes(path, data). Modifying operations exist for ZIP
                only and are disabled by default - see the write section and archive.status().

                --- ZIP (java.util.zip; .jar/.war/.ear) ---
                archive.zipEntries(path)              - List all entries as
                                                         [{name, size, compressedSize, method, crc32,
                                                           isDirectory, comment}], sorted by name.
                archive.zipEntry(path, entryName)     - Extract one entry as a real Uint8Array
                                                         (0-255), null if not found, empty array for
                                                         a directory.

                """
                + writeSection
                + """
                --- tar (ustar / GNU / basic POSIX pax; read-only) ---
                archive.tarEntries(pathOrBytes)       - List all entries as
                                                         [{name, size, type, isFile, isDirectory,
                                                           isSymbolicLink, isHardLink, linkName,
                                                           mode, mtime}], sorted by name.
                archive.tarEntry(pathOrBytes, name)   - Extract one entry as a real Uint8Array
                                                         (0-255), null if not found, empty array
                                                         for directories/links/devices.
                pathOrBytes is a file path or raw tar bytes (gzipped ones included). A gzipped
                tarball (.tar.gz, .tgz) is decompressed transparently, so ONE call is enough:
                    var files = archive.tarEntries("backup.tar.gz");
                    var data  = archive.tarEntry("backup.tar.gz", files[0].name);
                No gunzip() step, no flag and no extension to remember: the stream is inspected
                and gzip is assumed exactly when it starts with the magic bytes 1F 8B 08. A name
                is never trusted - neither "backup.bin" that is really a tarball, nor "x.tar.gz"
                that is really an uncompressed tar, is misread.
                GNU long names and pax 'path'/'linkpath'/'size' overrides are honored. tar has
                no write API - use ZIP when a file has to be modified.

                --- gzip / deflate (byte streams) ---
                archive.gzip(data)                    - gzip-compress bytes (RFC 1952, like gzip -c).
                archive.gunzip(data)                  - gzip-decompress bytes -> Uint8Array.
                archive.gunzipFile(path)              - gzip-decompress a .gz file (streamed)
                                                         -> Uint8Array. Needed for a plain .gz
                                                         (text, logs) or when the raw tar bytes
                                                         are wanted - to READ a .tar.gz it is
                                                         not needed, tarEntries does it alone.
                archive.deflate(data)                 - raw DEFLATE compress (RFC 1951, zip method).
                archive.inflate(data)                 - raw DEFLATE decompress -> Uint8Array.
                data is a Uint8Array or an array of numbers 0-255.

                """
                + HELP_LIMITS
                + """

                --- Help ---
                archive.help()                        - This help text.
                archive.status()                      - Current read-only state and limits.

                Examples:
                    var warNames = archive.zipEntries("app.war").map(e => e.name);
                    var webXml  = archive.zipEntry("app.war", "WEB-INF/web.xml");
                    var cfg     = archive.tarEntry("backup.tar", "etc/config.txt");
                    var gzFiles = archive.tarEntries("sources.tar.gz");   // .tgz: one call,
                    var gzOne   = archive.tarEntry("sources.tgz",         //   no gunzip needed
                                        gzFiles[0].name);
                    var tarBytes = archive.gunzipFile("sources.tar.gz"); // only raw tar bytes
                    fs.writeBytes("extracted.xml", webXml);              // persist via fs

                    // Writing (requires -D%s=false): patch a single entry of an existing
                    // archive, e.g. an OOXML part of a workbook or a resource inside a jar.
                    var s = archive.zipEntry("book.xlsx", "xl/sharedStrings.xml");
                    var t = new TextDecoder().decode(s).replace('Sales', 'Revenue');
                    archive.zipEntryWrite("book.xlsx", "xl/sharedStrings.xml", t);
                    archive.zipCreate("fixtures/empty.zip", []);
                    archive.zipEntryDelete("app.jar", "old/Stale.class");
                """.formatted(PROP_READONLY);
    }
}
