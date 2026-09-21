package org.rogmann.mcp2sdk.xml;

import org.rogmann.mcp2sdk.js.JsArchive;
import org.rogmann.mcp2sdk.js.JsFileSystem;
import org.rogmann.mcp2sdk.js.JsUserRuntimeException;
import org.rogmann.mcp2sdk.xml.JsXmlErrors.NotFound;
import org.rogmann.mcp2sdk.xml.JsXmlErrors.TooLarge;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Resolves the <em>source</em> of an XML read into something re-openable.
 *
 * <h3>Supported sources</h3>
 * <ul>
 *   <li>project-relative path ({@code conf/settings.xml}, {@code /addon/…}) - resolved with
 *       the {@link JsFileSystem} rules (no {@code ..}, no symlink escape);</li>
 *   <li>archive virtual path with the {@code search} syntax
 *       ({@code release.tar#docs/a.xml}, {@code app.jar#WEB-INF/web.xml}, nested
 *       {@code outer.zip#inner.tar#dir/x.xml}) - entries are never extracted to disk;</li>
 *   <li>raw bytes (a {@code Uint8Array} from {@code fs.readBytes});</li>
 *   <li>a text string (an XML fragment taken out of another document).</li>
 * </ul>
 *
 * <h3>Re-openable streams</h3>
 * <p>
 * A resolved source can be opened several times. Discovery runs several streaming passes
 * over the same source (profile, then optionally a line pass), and the DOM parse opens its
 * own stream, so nothing depends on a single consumable stream. Each opened stream is
 * wrapped in a byte guard ({@link #bounded}) that aborts at the configured limit, which
 * also caps decompression bombs coming out of an archive entry.
 * </p>
 *
 * <h3>Entity injection</h3>
 * <p>
 * {@link #injectEntities(byte[], Map)} implements the {@code {entities: {…}}} option by
 * adding declarations to the internal DTD subset (creating a DOCTYPE when the document has
 * none). ISO-8859-1 is used as a byte-preserving fallback encoding, so documents in an
 * unknown single-byte encoding are not damaged by the rewrite.
 * </p>
 */
final class JsXmlSource {

    /** Display name of a source that is not a file. */
    static final String TEXT_SOURCE = "<text>";
    static final String BYTES_SOURCE = "<bytes>";

    /** Stream buffer size. */
    private static final int BUFFER_BYTES = 64 * 1024;

    /** Bytes read for archive type detection (tar's {@code ustar} magic sits at offset 257). */
    private static final int MAGIC_PROBE_BYTES = 512;

    private JsXmlSource() {
        // Utility class
    }

    /** Opens a stream over the same content again and again. */
    interface StreamOpener {
        InputStream open() throws IOException;
    }

    /** A resolved XML source: display path, optional size hint, re-openable stream. */
    static final class Resolved {
        private final String display;
        private final long sizeHint;
        private final StreamOpener opener;
        private final byte[] inlineBytes;
        private final Path absolute;

        Resolved(String display, long sizeHint, StreamOpener opener) {
            this(display, sizeHint, opener, null, null);
        }

        Resolved(String display, long sizeHint, StreamOpener opener, byte[] inlineBytes) {
            this(display, sizeHint, opener, inlineBytes, null);
        }

        Resolved(String display, long sizeHint, StreamOpener opener, byte[] inlineBytes, Path absolute) {
            this.display = display;
            this.sizeHint = sizeHint;
            this.opener = opener;
            this.inlineBytes = inlineBytes;
            this.absolute = absolute;
        }

        /** Absolute path of a file source (never returned to the caller; used for DTD resolution and message sanitizing). */
        Path absoluteOrNull() {
            return absolute;
        }

        /**
         * Resolves a relative DTD/entity reference against the directory of this source.
         * The reference is checked with the normal {@link JsFileSystem} rules, so it can
         * never leave a permitted directory; absolute or URL references are refused here.
         *
         * @param reference relative system identifier as written in the document
         * @return the local file, or {@code null} when not resolvable as a local file
         */
        Path resolveSibling(String reference) {
            if (absolute == null || reference == null || reference.isBlank()) {
                return null;
            }
            try {
                String base = JsFileSystem.toRelative(absolute);
                int slash = base.lastIndexOf('/');
                String dir = (slash < 0) ? "" : base.substring(0, slash + 1);
                return JsFileSystem.resolveSafePath(dir + reference);
            } catch (RuntimeException e) {
                return null;
            }
        }

        /** Display path (relative path, {@code archive#entry}, {@code <bytes>} or {@code <text>}). */
        String display() {
            return display;
        }

        /** Known content size in bytes, or {@code -1} when unknown. */
        long sizeHint() {
            return sizeHint;
        }

        /** True for sources whose bytes are already in memory (bytes/text/inlined archive entry). */
        boolean hasInlineBytes() {
            return inlineBytes != null;
        }

        /** The in-memory bytes (only when {@link #hasInlineBytes()}). */
        byte[] inlineBytes() {
            return inlineBytes;
        }

        /** Opens a fresh, byte-guarded stream over the content. */
        InputStream open() throws IOException {
            return bounded(opener.open(), JsXmlLimits.MAX_STREAM_BYTES, display);
        }
    }

    // ========================================================================
    // Resolution
    // ========================================================================

    /**
     * Resolves a path or archive virtual path.
     *
     * @param source project-relative path or {@code archive#entry} (nesting allowed)
     * @return the resolved source
     * @throws NotFound            if the file or an archive entry does not exist
     * @throws JsUserRuntimeException if the path leaves the permitted directories or an
     *                                archive level is not a ZIP/tar
     */
    static Resolved resolve(String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source must be a path, an archive path"
                    + " 'archive.zip#entry' or bytes, but was empty");
        }
        String trimmed = source.trim();
        if (trimmed.indexOf('#') < 0) {
            return fromFile(trimmed);
        }
        return fromArchive(trimmed);
    }

    /** Resolves raw bytes (e.g. a {@code Uint8Array} from {@code fs.readBytes}). */
    static Resolved ofBytes(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        return ofBytes(data, BYTES_SOURCE + ":" + data.length);
    }

    /**
     * Resolves raw bytes under an explicit display name (used when a source is rewritten,
     * e.g. with injected entity declarations, and the display path must stay the original).
     */
    static Resolved ofBytes(byte[] data, String display) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        return new Resolved(display, data.length, () -> new ByteArrayInputStream(data), data);
    }

    /** Resolves an XML string. */
    static Resolved ofText(String text) {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        return new Resolved(TEXT_SOURCE, data.length, () -> new ByteArrayInputStream(data), data);
    }

    private static Resolved fromFile(String path) {
        Path file = existingFile(path);
        String display = JsFileSystem.toRelative(file);
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            size = -1;
        }
        return new Resolved(display, size,
                () -> new BufferedInputStream(Files.newInputStream(file), BUFFER_BYTES), null, file);
    }

    private static Resolved fromArchive(String source) {
        String[] parts = source.split("#", -1);
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) {
                throw new JsUserRuntimeException("Invalid archive path '" + source
                        + "': empty segment (expected 'archive.zip#dir/entry.xml')");
            }
        }
        Path container = existingFile(parts[0]);
        byte[] nested = null;
        for (int i = 1; i < parts.length; i++) {
            boolean last = (i == parts.length - 1);
            byte[] data = (i == 1)
                    ? extractFromPath(container, parts[i], source)
                    : extractFromBytes(nested, parts[i], source);
            if (data == null) {
                throw new NotFound("Entry '" + parts[i] + "' not found in " + source
                        + " (existing entry names can be listed with archive.zipEntries/archive.tarEntries)");
            }
            if (last) {
                final byte[] content = data;
                return new Resolved(source, content.length,
                        () -> new ByteArrayInputStream(content), content);
            }
            nested = data;
        }
        throw new JsUserRuntimeException("Invalid archive path '" + source + "'");
    }

    private static Path existingFile(String path) {
        Path file = JsFileSystem.resolveSafePath(path);
        if (!Files.isRegularFile(file)) {
            throw new NotFound("File not found: " + JsFileSystem.toRelative(file));
        }
        return file;
    }

    // ========================================================================
    // Archive levels
    // ========================================================================

    /**
     * Extracts one entry of an on-disk container (ZIP or tar, gzip detected by content).
     * @return the entry bytes, or {@code null} when the entry does not exist
     */
    private static byte[] extractFromPath(Path container, String entryName, String display) {
        byte[] probe = probeMagic(container, display);
        String relative = JsFileSystem.toRelative(container);
        if (isZip(probe)) {
            return JsArchive.zipEntry(relative, entryName);
        }
        if (isGzip(probe) || isTar(probe)) {
            return JsArchive.tarEntry(relative, entryName);
        }
        throw new JsUserRuntimeException("Not a ZIP or tar archive: " + relative
                + " (in '" + display + "') - archive paths need a ZIP/tar container"
                + " and an entry name after '#'");
    }

    /**
     * Extracts one entry of an in-memory container (nested archives).
     * @return the entry bytes, or {@code null} when the entry does not exist
     */
    private static byte[] extractFromBytes(byte[] container, String entryName, String display) {
        if (container == null) {
            throw new JsUserRuntimeException("Cannot read nested entry '" + entryName + "' in " + display);
        }
        if (isZip(container)) {
            return zipExtract(container, entryName, display);
        }
        if (isTar(container)) {
            return JsArchive.tarEntry(container, entryName);
        }
        if (isGzip(container)) {
            byte[] raw = gunzip(container, display);
            if (isZip(raw)) {
                return zipExtract(raw, entryName, display);
            }
            if (isTar(raw)) {
                return JsArchive.tarEntry(container, entryName);
            }
        }
        throw new JsUserRuntimeException("Nested archive level '" + entryName + "' in " + display
                + " is not a ZIP or tar archive");
    }

    /**
     * Scans a ZIP byte array for one entry (the on-disk path uses {@link JsArchive} and
     * streams a single entry instead).
     */
    private static byte[] zipExtract(byte[] data, String entryName, String display) {
        String wanted = normalizeEntryName(entryName);
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(data), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (!normalizeEntryName(entry.getName()).equals(wanted)) {
                    continue;
                }
                if (entry.isDirectory()) {
                    return new byte[0];
                }
                if (entry.getSize() > JsXmlLimits.MAX_STREAM_BYTES) {
                    throw new TooLarge("Entry '" + entryName + "' in " + display + " expands to "
                            + entry.getSize() + " bytes, limit is " + JsXmlLimits.MAX_STREAM_BYTES);
                }
                return readAll(zin, JsXmlLimits.MAX_STREAM_BYTES, display);
            }
            return null;
        } catch (IOException e) {
            throw new JsUserRuntimeException("Failed to read ZIP entry '" + entryName + "' in "
                    + display + ": " + e.getMessage(), e);
        }
    }

    /** Strips a leading "./" so entry names compare the same across writers. */
    private static String normalizeEntryName(String name) {
        String n = name.replace('\\', '/');
        while (n.startsWith("./")) {
            n = n.substring(2);
        }
        return n;
    }

    private static byte[] probeMagic(Path file, String display) {
        byte[] buffer = new byte[MAGIC_PROBE_BYTES];
        try (InputStream in = Files.newInputStream(file)) {
            int read = 0;
            while (read < buffer.length) {
                int n = in.read(buffer, read, buffer.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            if (read < buffer.length) {
                byte[] shorter = new byte[Math.max(read, 0)];
                System.arraycopy(buffer, 0, shorter, 0, shorter.length);
                return shorter;
            }
            return buffer;
        } catch (IOException e) {
            throw new JsUserRuntimeException("Cannot read " + display + ": " + e.getMessage(), e);
        }
    }

    static boolean isZip(byte[] data) {
        return data != null && data.length >= 4 && data[0] == 'P' && data[1] == 'K'
                && ((data[2] == 3 && data[3] == 4) || (data[2] == 5 && data[3] == 6)
                    || (data[2] == 7 && data[3] == 8));
    }

    static boolean isGzip(byte[] data) {
        return data != null && data.length >= 3 && data[0] == 0x1f && data[1] == (byte) 0x8b
                && data[2] == 0x08;
    }

    static boolean isTar(byte[] data) {
        return data != null && data.length >= 262
                && data[257] == 'u' && data[258] == 's' && data[259] == 't'
                && data[260] == 'a' && data[261] == 'r';
    }

    /** Gunzips with a byte guard (used for gzip-wrapped nested archives). */
    private static byte[] gunzip(byte[] data, String display) {
        try (GZIPInputStream gin = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return readAll(gin, JsXmlLimits.MAX_STREAM_BYTES, display);
        } catch (IOException e) {
            throw new JsUserRuntimeException("Failed to gunzip " + display + ": " + e.getMessage(), e);
        }
    }

    // ========================================================================
    // Byte guard
    // ========================================================================

    /**
     * Wraps a stream with a byte counter that throws {@link TooLarge} past {@code limit}
     * bytes. Guards both oversized documents and decompression bombs.
     */
    static InputStream bounded(InputStream in, long limit, String display) {
        return new FilterInputStream(in) {
            private long count;

            private void counted(long n) {
                if (n <= 0) {
                    return;
                }
                count += n;
                if (count > limit) {
                    throw new TooLarge("Source '" + display + "' exceeds the limit of " + limit
                            + " bytes (read " + count + " so far); raise "
                            + JsXmlLimits.PROP_PREFIX + "maxStreamBytes or stream a smaller part");
                }
            }

            @Override
            public int read() throws IOException {
                int b = super.read();
                if (b >= 0) {
                    counted(1);
                }
                return b;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int n = super.read(b, off, len);
                counted(n);
                return n;
            }
        };
    }

    /** Reads a stream completely, aborting with {@link TooLarge} past {@code limit} bytes. */
    static byte[] readAll(InputStream in, long limit, String display) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[BUFFER_BYTES];
        long total = 0;
        while (true) {
            int n = in.read(buffer);
            if (n < 0) {
                break;
            }
            total += n;
            if (total > limit) {
                throw new TooLarge("Content of '" + display + "' exceeds the limit of " + limit + " bytes");
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    /**
     * Reads the first bytes of a stream (for BOM/encoding reporting), leaving it readable.
     * @return up to {@code n} bytes actually readable
     */
    static byte[] peek(InputStream in, int n) {
        try {
            if (!in.markSupported()) {
                in = new BufferedInputStream(in, Math.max(n, BUFFER_BYTES));
            }
            in.mark(n);
            byte[] buffer = new byte[n];
            int read = 0;
            while (read < n) {
                int got = in.read(buffer, read, n - read);
                if (got < 0) {
                    break;
                }
                read += got;
            }
            in.reset();
            if (read == buffer.length) {
                return buffer;
            }
            byte[] shorter = new byte[Math.max(read, 0)];
            System.arraycopy(buffer, 0, shorter, 0, shorter.length);
            return shorter;
        } catch (IOException e) {
            return new byte[0];
        }
    }

    /**
     * Reports the encoding of a byte-order mark, or {@code null} when there is none.
     */
    static String bomEncoding(byte[] prefix) {
        if (prefix == null || prefix.length < 2) {
            return null;
        }
        if (prefix.length >= 3 && (prefix[0] & 0xFF) == 0xEF && (prefix[1] & 0xFF) == 0xBB
                && (prefix[2] & 0xFF) == 0xBF) {
            return "UTF-8-BOM";
        }
        if ((prefix[0] & 0xFF) == 0xFF && (prefix[1] & 0xFF) == 0xFE) {
            return "UTF-16LE";
        }
        if ((prefix[0] & 0xFF) == 0xFE && (prefix[1] & 0xFF) == 0xBE) {
            return "UTF-16BE";
        }
        return null;
    }

    // ========================================================================
    // Entity injection ({entities: {…}})
    // ========================================================================

    /**
     * Adds {@code <!ENTITY name "value">} declarations for the given map to the internal
     * DTD subset, creating a DOCTYPE when the document has none.
     *
     * @param data     original document bytes
     * @param entities entity name to replacement text (values are escaped)
     * @return modified document bytes
     */
    static byte[] injectEntities(byte[] data, Map<String, String> entities) {
        if (entities == null || entities.isEmpty()) {
            return data;
        }
        Charset encoding = pickRoundTripEncoding(data);
        String text = new String(data, encoding);
        String declarations = buildDeclarations(entities);
        int doctype = text.indexOf("<!DOCTYPE");
        if (doctype >= 0) {
            int end = findDoctypeEnd(text, doctype);
            if (end < 0) {
                throw new JsUserRuntimeException("Cannot add entities: unterminated <!DOCTYPE ...> declaration");
            }
            int bracket = text.indexOf('[', doctype);
            if (bracket >= 0 && bracket < end) {
                return (text.substring(0, bracket + 1) + declarations + text.substring(bracket + 1))
                        .getBytes(encoding);
            }
            return (text.substring(0, end) + " [" + declarations + "]" + text.substring(end))
                    .getBytes(encoding);
        }
        int insert = prologInsertPosition(text);
        return (text.substring(0, insert) + "<!DOCTYPE __jsextent [" + declarations + "]>"
                + text.substring(insert)).getBytes(encoding);
    }

    /** Builds well-formed entity declarations, rejecting names that are not XML names. */
    private static String buildDeclarations(Map<String, String> entities) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : entities.entrySet()) {
            String name = entry.getKey();
            if (name == null || !isXmlName(name)) {
                throw new JsUserRuntimeException("Invalid entity name '" + name
                        + "' (expected an XML name such as 'reg')");
            }
            sb.append("<!ENTITY ").append(name).append(" \"")
                    .append(escapeEntityValue(entry.getValue())).append("\">");
        }
        return sb.toString();
    }

    private static String escapeEntityValue(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static boolean isXmlName(String name) {
        if (name.isEmpty()) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == ':'
                    || (i > 0 && ((c >= '0' && c <= '9') || c == '.' || c == '-'));
            if (!ok && c < 0xC0) {
                return false; // ASCII characters that are not name characters
            }
        }
        return true;
    }

    /** Index of the closing angle bracket of a DOCTYPE declaration (respecting the subset). */
    private static int findDoctypeEnd(String text, int start) {
        boolean inSubset = false;
        char quote = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '[') {
                inSubset = true;
            } else if (c == ']') {
                inSubset = false;
            } else if (c == '>' && !inSubset) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Position where a DOCTYPE may be inserted: after the XML declaration, processing
     * instructions and comments (the prolog order requires exactly that).
     */
    private static int prologInsertPosition(String text) {
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (text.startsWith("<!--", i)) {
                int end = text.indexOf("-->", i + 4);
                if (end < 0) {
                    return i;
                }
                i = end + 3;
                continue;
            }
            if (text.startsWith("<?", i)) {
                int end = text.indexOf("?>", i + 2);
                if (end < 0) {
                    return i;
                }
                i = end + 2;
                continue;
            }
            return i; // start of the root element
        }
        return i;
    }

    /**
     * Chooses an encoding that survives a decode/encode round trip: UTF-8 when the bytes
     * are valid UTF-8, ISO-8859-1 otherwise (byte-preserving for every single-byte
     * encoding), UTF-16 when a UTF-16 BOM says so.
     */
    private static Charset pickRoundTripEncoding(byte[] data) {
        if (data.length >= 2) {
            String bom = bomEncoding(data);
            if ("UTF-16LE".equals(bom)) {
                return StandardCharsets.UTF_16LE;
            }
            if ("UTF-16BE".equals(bom)) {
                return StandardCharsets.UTF_16BE;
            }
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(java.nio.ByteBuffer.wrap(data));
            return StandardCharsets.UTF_8;
        } catch (CharacterCodingException e) {
            return StandardCharsets.ISO_8859_1;
        }
    }

    /**
     * Copies the entity map of an options object into a plain map (used by {@code open}).
     */
    static Map<String, String> copyEntities(Map<String, String> entities) {
        return entities == null ? new LinkedHashMap<>() : new LinkedHashMap<>(entities);
    }
}
