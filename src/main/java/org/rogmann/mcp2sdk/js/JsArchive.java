package org.rogmann.mcp2sdk.js;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controlled read access to the contents of ZIP and tar archives plus gzip/deflate byte
 * streams for JavaScript.
 * <p>
 * The archive and byte-stream operations are <em>read/transform</em> only: entries can be
 * listed, extracted and bytes can be compressed/decompressed (gzip, raw deflate); results
 * are returned as byte arrays (a {@code Uint8Array} in JavaScript). Nothing is written to
 * the file system directly, so archive-internal path traversal ("zip slip") is harmless by
 * construction; to persist a result the caller uses {@code fs.writeBytes(...)}.
 * </p>
 * <p>
 * The archive file itself must live inside the project base directory and is resolved
 * with the same security checks as {@link JsFileSystem} (system property
 * {@code IDE_PROJECT_DIR}): no {@code ..}, no absolute paths outside the base, no
 * symbolic links leaving the base. Results and error messages contain only relative paths.
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
 *
 * <h3>Limits</h3>
 * <p>
 * A single extracted entry is limited to {@link #MAX_ENTRY_BYTES} bytes (256 MiB) to keep
 * the JS engine heap safe. Listing an archive never loads entry data.
 * </p>
 *
 * <h3>Usage in JavaScript</h3>
 * <pre>{@code
 * var names = archive.zipEntries("app.war").map(e => e.name);
 * var webXml = archive.zipEntry("app.war", "WEB-INF/web.xml");
 * var list = archive.tarEntries("backup.tar");
 * var content = archive.tarEntry("backup.tar", "etc/config.txt");
 * var gz = archive.gzip(data);                       // gzip-compress bytes
 * var raw = archive.gunzipFile("log.gz");            // gunzip a file (streamed)
 * var list2 = archive.tarEntries(archive.gunzip("src.tar.gz")); // read a .tar.gz in memory
 * fs.writeBytes("out.bin", content);                 // persist an extracted entry
 * }</pre>
 */
public class JsArchive {

    private static final Logger LOG = LoggerFactory.getLogger(JsArchive.class);

    /** Upper bound for a single extracted entry (bytes); protects the JS engine heap. */
    public static final long MAX_ENTRY_BYTES = 256L * 1024 * 1024;

    /** Upper bound for one gzip/inflate decompression result (bytes). */
    public static final long MAX_DECOMPRESSED_BYTES = 256L * 1024 * 1024;

    /** tar record/block size in bytes. */
    private static final int TAR_BLOCK_SIZE = 512;

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
     * Lists all entries of a tar archive (ustar/GNU/basic POSIX pax).
     * <p>
     * Headers are parsed in a single pass; entry data is skipped, so listing is possible
     * with constant memory no matter how large the individual entries are.
     * </p>
     * @param filePath archive path relative to the base directory
     * @return list of maps
     *         {@code {name, size, type, isFile, isDirectory, isSymbolicLink, isHardLink, linkName, mode, mtime}},
     *         sorted by name
     * @throws JsUserRuntimeException if the archive is missing or not a supported tar
     */
    public static List<Map<String, Object>> tarEntries(String filePath) {
        Path path = requireRegularFile(filePath);
        try (InputStream in = new BufferedInputStream(Files.newInputStream(path), STREAM_BUFFER_SIZE)) {
            return new TarScanner(in).listEntries();
        } catch (IOException e) {
            LOG.error("Failed to read tar archive: " + path, e);
            throw new JsUserRuntimeException("Failed to read tar archive: " + JsFileSystem.toRelative(path), e);
        }
    }

    /**
     * Extracts a single entry of a tar archive as a byte array.
     * <p>
     * The archive is scanned in a single pass and reading stops once the matching entry
     * has been found. Names are resolved through GNU long-name and POSIX pax headers.
     * </p>
     * @param filePath archive path relative to the base directory
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
        try (InputStream in = new BufferedInputStream(Files.newInputStream(path), STREAM_BUFFER_SIZE)) {
            return new TarScanner(in).readEntry(entryName);
        } catch (IOException e) {
            LOG.error("Failed to read tar archive: " + path, e);
            throw new JsUserRuntimeException("Failed to read tar archive: " + JsFileSystem.toRelative(path), e);
        }
    }

    /**
     * Lists all entries of an in-memory tar archive (e.g. the result of {@link #gunzip(byte[])}
     * on a {@code .tar.gz}). Semantics identical to {@link #tarEntries(String)}.
     * @param data raw tar bytes (must not be null, e.g. from fs.readBytes or archive.gunzip)
     * @return list of entry maps, sorted by name
     * @throws IllegalArgumentException if data is null
     * @throws JsUserRuntimeException if the data is not a supported tar
     */
    public static List<Map<String, Object>> tarEntries(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("tar data must not be null");
        }
        try {
            return new TarScanner(new ByteArrayInputStream(data)).listEntries();
        } catch (IOException e) {
            // Cannot happen for an in-memory stream; kept to satisfy the checked signature.
            throw new JsUserRuntimeException("Failed to read tar data: " + e.getMessage(), e);
        }
    }

    /**
     * Extracts a single entry of an in-memory tar archive (e.g. the result of
     * {@link #gunzip(byte[])} on a {@code .tar.gz}). Semantics identical to
     * {@link #tarEntry(String, String)}.
     * @param data raw tar bytes (must not be null)
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
        try {
            return new TarScanner(new ByteArrayInputStream(data)).readEntry(entryName);
        } catch (IOException e) {
            // Cannot happen for an in-memory stream; kept to satisfy the checked signature.
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
     * @return help text as a multi-line string
     */
    public static String help() {
        return """
                JS Archive API (namespace 'archive')
                ====================================

                Read access to ZIP/tar archives and gzip/deflate byte streams (.zip, .jar, .war,
                .ear, .tar, .gz, .tar.gz). Archive files must live inside the project base
                directory; security is identical to fs.* (no '..', no absolute paths outside
                the base, no symbolic links leaving the base). Entries are never extracted to
                disk (archive-internal path traversal is harmless by construction); to persist
                bytes use fs.writeBytes(path, data).

                --- ZIP (java.util.zip; .jar/.war/.ear) ---
                archive.zipEntries(path)              - List all entries as
                                                         [{name, size, compressedSize, method, crc32,
                                                           isDirectory, comment}], sorted by name.
                archive.zipEntry(path, entryName)     - Extract one entry as a real Uint8Array
                                                         (0-255), null if not found, empty array for
                                                         a directory.

                --- tar (ustar / GNU / basic POSIX pax) ---
                archive.tarEntries(pathOrBytes)       - List all entries as
                                                         [{name, size, type, isFile, isDirectory,
                                                           isSymbolicLink, isHardLink, linkName,
                                                           mode, mtime}], sorted by name.
                archive.tarEntry(pathOrBytes, name)   - Extract one entry as a real Uint8Array
                                                         (0-255), null if not found, empty array
                                                         for directories/links/devices.
                pathOrBytes is a file path or raw tar bytes (e.g. the result of gunzip or
                fs.readBytes). GNU long names and pax 'path'/'linkpath'/'size' overrides are
                honored.

                --- gzip / deflate (byte streams) ---
                archive.gzip(data)                    - gzip-compress bytes (RFC 1952, like gzip -c).
                archive.gunzip(data)                  - gzip-decompress bytes -> Uint8Array.
                archive.gunzipFile(path)              - gzip-decompress a .gz/.tar.gz file
                                                         (streamed) -> Uint8Array; feed the result
                                                         to tarEntries to read a .tar.gz.
                archive.deflate(data)                 - raw DEFLATE compress (RFC 1951, zip method).
                archive.inflate(data)                 - raw DEFLATE decompress -> Uint8Array.
                data is a Uint8Array or an array of numbers 0-255.

                Limits: a single extracted/decompressed result is capped at %d bytes (%.0f MiB);
                listing an archive loads only metadata. Sparse tar files and base-256 sizes are
                rejected with a clear error message.

                --- Help ---
                archive.help()                        - This help text.

                Examples:
                    var warNames = archive.zipEntries("app.war").map(e => e.name);
                    var webXml  = archive.zipEntry("app.war", "WEB-INF/web.xml");
                    var cfg     = archive.tarEntry("backup.tar", "etc/config.txt");
                    var tarBytes = archive.gunzipFile("sources.tar.gz"); // decompress
                    var files    = archive.tarEntries(tarBytes);         // parse the tar.gz
                    fs.writeBytes("extracted.xml", webXml);              // persist via fs
                """.formatted(MAX_ENTRY_BYTES, MAX_ENTRY_BYTES / (1024.0 * 1024.0));
    }
}
