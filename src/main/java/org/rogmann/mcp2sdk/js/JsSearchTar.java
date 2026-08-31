package org.rogmann.mcp2sdk.js;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Minimal streaming tar reader used by {@link JsSearch}.
 * <p>
 * The archive is read in a single pass from an {@link InputStream}: one record after the
 * other. Entry data is exposed as a bounded {@link InputStream} (see {@link #data()}); the
 * reader skips whatever the caller did not read, so traversal stays sequential and memory
 * stays constant even for huge entries. Nothing is ever written to disk.
 * </p>
 *
 * <h3>Supported formats</h3>
 * <ul>
 *   <li>ustar (including the {@code prefix} field)</li>
 *   <li>GNU long names ({@code L}) and long link names ({@code K})</li>
 *   <li>POSIX pax extended headers ({@code x}, {@code g}) for {@code path}, {@code linkpath}
 *       and {@code size}</li>
 *   <li>end-of-archive marker (zero block); truncated data is reported as an error</li>
 * </ul>
 *
 * <h3>Security</h3>
 * <p>
 * Entry names are returned exactly as stored (they may contain {@code ..}); they are virtual
 * display names only and are never resolved against the file system - extracting tar entries
 * to disk is not supported at all (see the "no disk extraction" rule in docs/js/search.md).
 * </p>
 */
final class JsSearchTar {

    /** Size of one tar block. */
    private static final int BLOCK_SIZE = 512;

    /** Upper bound for meta records (GNU long name, pax header). */
    private static final int MAX_META_BYTES = 8 * 1024 * 1024;

    private final InputStream in;
    private final byte[] block = new byte[BLOCK_SIZE];

    /** Pending GNU long name (from an 'L' record), applies to the next real entry. */
    private String pendingLongName;
    /** Pending pax key/value overrides (from 'x'/'g' records), apply to the next real entry. */
    private Map<String, String> pendingPax;

    /** Name of the current entry. */
    private String currentName;
    /** Declared size of the current entry in bytes. */
    private long currentSize;
    /** Type flag of the current entry. */
    private char currentType;
    /** Bytes left in the current entry's data section, including 512-byte padding. */
    private long paddedLeft;
    /** True once the end of the archive has been reached. */
    private boolean eof;

    /**
     * Creates a tar reader streaming from the given (uncompressed) stream.
     * @param in stream over tar data
     */
    JsSearchTar(InputStream in) {
        this.in = in;
    }

    /**
     * Reads the next real entry record.
     * <p>
     * Data of the previous entry that was not consumed by the caller is skipped
     * automatically, so entries may be processed one by one without caring about leftovers.
     * </p>
     * @return true if another entry was read, false at end of archive
     * @throws IOException on I/O errors
     * @throws JsUserRuntimeException on malformed tar data (bad checksum, sparse entries)
     */
    boolean nextEntry() throws IOException {
        skipRemaining();
        while (!eof) {
            if (!readHeaderBlock()) {
                eof = true;
                return false;
            }
            byte[] b = block;
            String name = textField(b, 0, 100);
            long size = parseOctal(b, 124, 12);
            String magic = textField(b, 257, 6);
            // The ustar "prefix" field (offset 345) may only be used for ustar/pax headers.
            // GNU tars write their realsize there, so the prefix is ignored for them (GNU
            // stores long names in 'L' records instead).
            boolean ustarFormat = magic.startsWith("ustar") && b[263] == '0' && b[264] == '0';
            String prefix = ustarFormat ? textField(b, 345, 155) : "";
            char type = (char) (b[156] & 0xFF);
            if (type == 0) {
                type = '0';
            }
            validateChecksum(b);

            if (size < 0) {
                throw new JsUserRuntimeException("tar header has no readable entry size");
            }
            long dataBytes = (size + BLOCK_SIZE - 1) / BLOCK_SIZE * BLOCK_SIZE;

            switch (type) {
                case 'L' -> { // GNU long name of the following entry
                    pendingLongName = stripTrailingNul(readMeta(size));
                }
                case 'K' -> { // GNU long link name (irrelevant for searching, but consume it)
                    readMeta(size);
                }
                case 'x', 'g' -> { // POSIX pax extended header (local / global)
                    if (pendingPax == null) {
                        pendingPax = new HashMap<>();
                    }
                    parsePax(new String(readMeta(size), StandardCharsets.UTF_8), pendingPax);
                }
                case 'S' -> throw new JsUserRuntimeException(
                        "tar sparse files ('S' type flag) are not supported");
                default -> {
                    String paxPath = pendingPax != null ? pendingPax.get("path") : null;
                    String resolvedName;
                    if (paxPath != null) {
                        resolvedName = paxPath;
                    } else if (pendingLongName != null) {
                        resolvedName = pendingLongName;
                    } else if (!prefix.isEmpty()) {
                        resolvedName = prefix + "/" + name;
                    } else {
                        resolvedName = name;
                    }
                    long effectiveSize = size;
                    String paxSize = pendingPax != null ? pendingPax.get("size") : null;
                    if (paxSize != null) {
                        try {
                            effectiveSize = Long.parseLong(paxSize.trim());
                        } catch (NumberFormatException ignored) {
                            // keep the header size
                        }
                    }
                    pendingLongName = null;
                    pendingPax = null;
                    currentName = resolvedName;
                    currentSize = Math.max(0, effectiveSize);
                    currentType = type;
                    paddedLeft = dataBytes;
                    return true;
                }
            }
        }
        return false;
    }

    /** @return entry name exactly as stored (never resolved against the file system) */
    String name() {
        return currentName;
    }

    /** @return declared entry size in bytes (0 or more) */
    long size() {
        return currentSize;
    }

    /** @return the raw tar type flag ('0' file, '5' directory, '2' symbolic link, ...) */
    char type() {
        return currentType;
    }

    /** @return true if the entry is a plain file whose content may be searched */
    boolean isRegularFile() {
        return currentType == '0' || currentType == '7';
    }

    /** @return true if the entry is a directory (or its name ends with a slash) */
    boolean isDirectory() {
        return currentType == '5' || (currentName != null && currentName.endsWith("/"));
    }

    /** @return true if the entry is a symbolic or hard link */
    boolean isLink() {
        return currentType == '1' || currentType == '2';
    }

    /**
     * @return a stream over the data of the current entry (EOF after {@link #size()} bytes).
     *         Must be used before the next {@link #nextEntry()} call; unconsumed bytes are
     *         skipped automatically.
     */
    InputStream data() {
        return new EntryInputStream(in);
    }

    // ========================================================================
    // Internals
    // ========================================================================

    /** Reads one header block; false at a clean end of archive (zero block or EOF). */
    private boolean readHeaderBlock() throws IOException {
        int off = 0;
        while (off < BLOCK_SIZE) {
            int n = in.read(block, off, BLOCK_SIZE - off);
            if (n < 0) {
                if (off == 0) {
                    return false; // clean end between records
                }
                throw new JsUserRuntimeException("tar archive is truncated (incomplete header block)");
            }
            off += n;
        }
        return !isZeroBlock(block);
    }

    /** Skips data and padding of the current entry that the caller did not read. */
    private void skipRemaining() throws IOException {
        if (eof) {
            return;
        }
        long left = paddedLeft;
        paddedLeft = 0;
        skipRaw(left);
    }

    /**
     * Reads a complete meta record (long name / pax header) and consumes its padding.
     * @param size record size in bytes
     * @return the record bytes
     */
    private byte[] readMeta(long size) throws IOException {
        if (size <= 0) {
            return new byte[0];
        }
        if (size > MAX_META_BYTES) {
            throw new JsUserRuntimeException(
                    "tar meta record is too large to be interpreted (" + size + " bytes)");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        long left = size;
        while (left > 0) {
            int chunk = (int) Math.min(buf.length, left);
            int r = in.read(buf, 0, chunk);
            if (r < 0) {
                throw new JsUserRuntimeException("tar archive is truncated (incomplete meta record)");
            }
            out.write(buf, 0, r);
            left -= r;
        }
        // The data section of the record occupies whole 512-byte blocks.
        skipRaw((BLOCK_SIZE - (size % BLOCK_SIZE)) % BLOCK_SIZE);
        return out.toByteArray();
    }

    /** Skips raw bytes of the underlying stream. */
    private void skipRaw(long n) throws IOException {
        byte[] sink = new byte[8192];
        long left = n;
        while (left > 0) {
            int chunk = (int) Math.min(sink.length, left);
            int r = in.read(sink, 0, chunk);
            if (r < 0) {
                throw new JsUserRuntimeException("tar archive is truncated");
            }
            left -= r;
        }
    }

    private static String stripTrailingNul(byte[] data) {
        int len = 0;
        while (len < data.length && data[len] != 0) {
            len++;
        }
        return new String(data, 0, len, StandardCharsets.UTF_8);
    }

    private static boolean isZeroBlock(byte[] b) {
        for (byte value : b) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Verifies the header checksum. Both the unsigned and the signed (GNU) sum are accepted;
     * the checksum field itself counts as spaces.
     */
    private static void validateChecksum(byte[] b) {
        long stored = parseOctal(b, 148, 8);
        if (stored < 0) {
            return; // unreadable checksum field: tolerate, broken headers fail elsewhere
        }
        long unsignedSum = 0;
        long signedSum = 0;
        for (int i = 0; i < BLOCK_SIZE; i++) {
            boolean checksumField = i >= 148 && i < 156;
            unsignedSum += checksumField ? ' ' : (b[i] & 0xFF);
            signedSum += checksumField ? ' ' : b[i];
        }
        if (stored != unsignedSum && stored != signedSum) {
            throw new JsUserRuntimeException("invalid tar header checksum");
        }
    }

    /**
     * Parses an octal (or GNU base-256) numeric field.
     * @return the value, or -1 if the field is empty or not parseable
     */
    private static long parseOctal(byte[] b, int off, int len) {
        if (len > 0 && (b[off] & 0x80) != 0) {
            long value = b[off] & 0x7F; // GNU base-256 encoding
            for (int i = off + 1; i < off + len; i++) {
                value = (value << 8) | (b[i] & 0xFF);
            }
            return value;
        }
        long value = 0;
        boolean any = false;
        for (int i = off; i < off + len; i++) {
            char c = (char) (b[i] & 0xFF);
            if (c == ' ' || c == 0) {
                if (any) {
                    break;
                }
                continue;
            }
            if (c < '0' || c > '7') {
                return -1;
            }
            value = (value << 3) + (c - '0');
            any = true;
        }
        return any ? value : -1;
    }

    /** Reads a NUL-terminated text field (bytes interpreted as UTF-8). */
    private static String textField(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) {
            end++;
        }
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }

    /**
     * Parses a pax extended header ("&lt;length&gt; &lt;key&gt;=&lt;value&gt;\n" records).
     * @param text header text
     * @param target map receiving the key/value pairs
     */
    private static void parsePax(String text, Map<String, String> target) {
        int pos = 0;
        int n = text.length();
        while (pos < n) {
            int space = text.indexOf(' ', pos);
            if (space < 0) {
                break;
            }
            int recordLength;
            try {
                recordLength = Integer.parseInt(text.substring(pos, space));
            } catch (NumberFormatException e) {
                break; // not a pax record any more
            }
            if (recordLength <= 0 || pos + recordLength > n) {
                break;
            }
            String record = text.substring(pos, pos + recordLength);
            int keyStart = space - pos + 1; // the key starts after "<len> "
            int eq = record.indexOf('=', keyStart);
            if (eq > keyStart) {
                String value = record.substring(eq + 1);
                if (value.endsWith("\n")) {
                    value = value.substring(0, value.length() - 1);
                }
                target.put(record.substring(keyStart, eq), value);
            }
            pos += recordLength;
        }
    }

    /**
     * Bounded view on the data section of the current entry. Bytes read through this stream
     * are also deducted from {@code paddedLeft}, so the reader always knows how much of the
     * entry (including padding) still has to be skipped.
     * <p>
     * The underlying stream is passed to {@code super(...)} as a parameter: an inner class may
     * not read fields of its outer instance (directly or implicitly) before the supertype
     * constructor has run.
     * </p>
     */
    private final class EntryInputStream extends FilterInputStream {

        private long left = currentSize;

        private EntryInputStream(InputStream source) {
            super(source);
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) {
                return -1;
            }
            int v = super.read();
            if (v < 0) {
                left = 0;
                return -1;
            }
            left--;
            paddedLeft--;
            return v;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            if (len <= 0) {
                return left > 0 ? 0 : -1;
            }
            if (left <= 0) {
                return -1;
            }
            int n = super.read(buf, off, (int) Math.min(len, left));
            if (n > 0) {
                left -= n;
                paddedLeft -= n;
            }
            return n;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(left, super.available());
        }
    }
}
