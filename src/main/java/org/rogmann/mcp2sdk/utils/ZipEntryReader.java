package org.rogmann.mcp2sdk.utils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;

/**
 * Read-only ZIP access on a byte array: the in-memory counterpart of {@code java.util.zip.ZipFile}.
 *
 * <p><strong>Why not {@link java.util.zip.ZipInputStream}?</strong> {@code ZipFile} needs a real
 * file, and {@code ZipInputStream} only sees the <em>local</em> headers. Writers that put their sizes
 * behind the data (the JDK's {@code ZipOutputStream}, assembly plugins, Spring Boot's repackager) set
 * the flag {@code 0x0008} and leave crc and both sizes at zero there, so a streamed listing reports
 * {@code size=0, compressedSize=-1, crc32=null} for exactly the archives this reader exists for: the
 * {@code BOOT-INF/lib/*.jar} of a fat jar, the {@code WEB-INF/lib/*.jar} of a war inside an ear. This
 * class therefore reads the <em>central directory</em>, which every writer fills in, and seeks to the
 * local header of the requested entry. Listing stays metadata-only (nothing is inflated), one entry
 * is readable without touching the entries before it, and nothing is written to disk - which is what
 * lets a caller walk an archive-in-archive level by level.</p>
 *
 * <h3>Supported layout</h3>
 * <ul>
 *   <li>central directory with and without ZIP64 (end-of-central-directory record, locator and the
 *       {@code 0x0001} extended-information extra field of a record),</li>
 *   <li>entry names and comments as UTF-8 (the language encoding flag {@code 0x0800} is
 *       informational - reading UTF-8 is what {@code ZipFile} with an explicit charset does, and the
 *       JDK writer always sets the flag),</li>
 *   <li>methods {@code STORED} (0) and {@code DEFLATE} (8); anything else names itself in the
 *       message,</li>
 *   <li>the archive comment.</li>
 * </ul>
 *
 * <p>
 * Encrypted entries (flag {@code 0x0001}) are refused. Every failure is an {@link IOException} that
 * names the {@code display} given to the constructor - a relative path, a virtual path,
 * {@code <bytes:N>} - so the calling layer can build its own user-facing message around it. This
 * class knows ZIP, not the API above it: it never mentions another container format and never
 * resolves a path, so no message can contain an absolute one. A truncated archive produces a
 * message, not an {@code IndexOutOfBoundsException}.
 * </p>
 */
public final class ZipEntryReader {

    /** ZIP signature of a local file header ({@code PK\x03\x04}). */
    public static final int SIG_LOCAL = 0x04034B50;
    /** ZIP signature of a central directory record ({@code PK\x01\x02}). */
    public static final int SIG_CENTRAL = 0x02014B50;
    /** ZIP signature of a data descriptor ({@code PK\x07\x08}). */
    public static final int SIG_DATA_DESCRIPTOR = 0x08074B50;
    /** ZIP signature of the end of the central directory ({@code PK\x05\x06}). */
    public static final int SIG_END = 0x06054B50;

    private static final int SIG_ZIP64_END = 0x06064B50;
    private static final int SIG_ZIP64_LOCATOR = 0x07064B50;

    /** Fixed part of a local file header. */
    private static final int LOCAL_HEADER_SIZE = 30;
    /** Fixed part of a central directory record. */
    private static final int CENTRAL_RECORD_SIZE = 46;
    /** Fixed part of the end-of-central-directory record. */
    private static final int END_RECORD_SIZE = 22;
    /** Size of the ZIP64 end-of-central-directory locator. */
    private static final int ZIP64_LOCATOR_SIZE = 20;
    /** Fixed part of the ZIP64 end-of-central-directory record. */
    private static final int ZIP64_END_RECORD_SIZE = 56;
    /** An archive comment is at most 65535 bytes, so this is the widest backward scan. */
    private static final int END_RECORD_SCAN_BYTES = END_RECORD_SIZE + 0xFFFF;

    /** General purpose bit flag: the entry data is encrypted. */
    private static final int FLAG_ENCRYPTED = 0x0001;
    /** Header id of the ZIP64 extended information extra field. */
    private static final int ZIP64_EXTRA_ID = 0x0001;
    /** 32-bit placeholder meaning "the real number is in the ZIP64 field". */
    private static final long UINT32_MAX = 0xFFFFFFFFL;
    /** 16-bit placeholder meaning "the real number is in the ZIP64 record". */
    private static final int UINT16_MAX = 0xFFFF;
    /** Initial inflate buffer; capped so that a lying size cannot preallocate gigabytes. */
    private static final int INFLATE_INITIAL_BYTES = 1024 * 1024;

    /**
     * One entry of the central directory.
     *
     * @param name              entry name, '/' separated, exactly as stored
     * @param size              uncompressed size in bytes, -1 when the record is silent
     * @param compressedSize    stored size in bytes, -1 when the record is silent
     * @param method            compression method ({@link ZipEntry#STORED}, {@link ZipEntry#DEFLATED})
     * @param crc               crc32 of the uncompressed content, -1 when not stored
     * @param directory         true for a name ending in '/'
     * @param comment           comment on the entry, {@code null} when there is none
     * @param mtimeMillis       modification time as epoch milliseconds, -1 when unusable
     * @param localHeaderOffset offset of the local header inside the archive bytes
     * @param flags             general purpose bit flags of the record
     */
    public record Entry(String name, long size, long compressedSize, int method, long crc,
                        boolean directory, String comment, long mtimeMillis, long localHeaderOffset,
                        int flags) {
    }

    private final byte[] data;
    private final String display;
    private final long maxEntryBytes;
    private final String comment;
    private final List<Entry> entries;
    private final Map<String, Entry> byName;

    /**
     * Reads the central directory of a ZIP archive held in memory.
     *
     * @param data          the archive bytes (zip, jar, war or ear)
     * @param display       name used in messages: a relative path, a virtual path or
     *                      {@code <bytes:N>}; {@code null} prints as {@code <bytes>}
     * @param maxEntryBytes upper bound for one decompressed entry (compression-bomb guard)
     * @throws IOException if the data holds no readable ZIP central directory
     */
    public ZipEntryReader(byte[] data, String display, long maxEntryBytes) throws IOException {
        if (data == null) {
            throw new IllegalArgumentException("zip data must not be null");
        }
        if (maxEntryBytes <= 0) {
            throw new IllegalArgumentException("maxEntryBytes must be positive");
        }
        this.data = data;
        this.display = display == null ? "<bytes>" : display;
        this.maxEntryBytes = maxEntryBytes;
        if (data.length < END_RECORD_SIZE) {
            throw notAZip(data.length + " bytes cannot hold an end-of-central-directory record");
        }
        int endRecord = findEndOfCentralDirectory();
        this.comment = readEndComment(endRecord);
        long recordCount = readUInt16(endRecord + 10);
        long directorySize = readUInt32(endRecord + 12);
        long directoryOffset = readUInt32(endRecord + 16);
        if (recordCount == UINT16_MAX || directorySize == UINT32_MAX || directoryOffset == UINT32_MAX) {
            long[] zip64 = readZip64End(endRecord, recordCount, directorySize, directoryOffset);
            recordCount = zip64[0];
            directorySize = zip64[1];
            directoryOffset = zip64[2];
        }
        this.entries = readCentralDirectory(directoryOffset, recordCount);
        Map<String, Entry> index = new LinkedHashMap<>();
        for (Entry entry : this.entries) {
            // The first record of a duplicated name wins, as java.util.zip.ZipFile does.
            index.putIfAbsent(entry.name(), entry);
        }
        this.byName = index;
    }

    // ========================================================================
    // Access
    // ========================================================================

    /** @return every entry, in central directory order */
    public List<Entry> entries() {
        return entries;
    }

    /** @return the named entry, or {@code null} when the archive has no such entry */
    public Entry entry(String entryName) {
        return entryName == null ? null : byName.get(entryName);
    }

    /** @return the archive comment, or {@code null} when the archive carries none */
    public String comment() {
        return comment;
    }

    /** @return number of entries of the central directory */
    public int size() {
        return entries.size();
    }

    /**
     * Reads (and inflates) one entry by name, seeking straight to its local header - the entries
     * before it are never touched.
     *
     * @param entryName name exactly as reported by {@link #entries()}
     * @return the entry content, or {@code null} when there is no such entry; an empty array for a
     *         directory entry
     * @throws IOException if the entry is encrypted, uses an unsupported method, is corrupt
     *                     (truncated, size or crc mismatch) or exceeds {@code maxEntryBytes}
     */
    public byte[] read(String entryName) throws IOException {
        Entry entry = entry(entryName);
        return entry == null ? null : read(entry);
    }

    /**
     * Reads (and inflates) the given entry.
     *
     * @param entry an entry of this archive
     * @return the entry content, an empty array for a directory entry
     * @throws IOException as in {@link #read(String)}
     */
    public byte[] read(Entry entry) throws IOException {
        if (entry.directory() || entry.name().endsWith("/")) {
            return new byte[0];
        }
        if ((entry.flags() & FLAG_ENCRYPTED) != 0) {
            throw new IOException("Entry '" + entry.name() + "' in " + display + " is encrypted;"
                    + " password-protected ZIP entries cannot be read.");
        }
        long compressedSize = entry.compressedSize();
        if (compressedSize < 0) {
            throw new IOException("Entry '" + entry.name() + "' in " + display
                    + " has no size in its central directory record (corrupt archive).");
        }
        if (compressedSize > maxEntryBytes) {
            throw new IOException("Entry '" + entry.name() + "' in " + display + " has "
                    + compressedSize + " bytes, exceeding the limit of " + maxEntryBytes + " bytes.");
        }
        long dataStart = dataStartOf(entry);
        if (dataStart + compressedSize > data.length) {
            throw new IOException("Corrupt ZIP archive: " + display + " (entry '" + entry.name()
                    + "' claims " + compressedSize + " bytes at offset " + dataStart
                    + ", but the archive ends at " + data.length + " bytes).");
        }
        byte[] content;
        if (entry.method() == ZipEntry.STORED) {
            content = copyStored(dataStart, compressedSize, entry);
        } else if (entry.method() == ZipEntry.DEFLATED) {
            content = inflateEntry(dataStart, compressedSize, entry);
        } else {
            throw new IOException("Entry '" + entry.name() + "' in " + display + " uses compression"
                    + " method " + entry.method() + "; only STORED (0) and DEFLATE (8) can be read.");
        }
        verifyCrc(entry, content);
        return content;
    }

    // ========================================================================
    // Entry data
    // ========================================================================

    /**
     * Offset of the entry data. The <em>local</em> header has to be read for this: its name and extra
     * fields are allowed to differ from the central directory record.
     */
    private long dataStartOf(Entry entry) throws IOException {
        long offset = entry.localHeaderOffset();
        requireRange(offset, LOCAL_HEADER_SIZE, "local file header of entry '" + entry.name() + "'");
        if (readUInt32(offset) != SIG_LOCAL) {
            throw new IOException("Corrupt ZIP archive: " + display + " (the local file header of"
                    + " entry '" + entry.name() + "' at offset " + offset + " is missing).");
        }
        long nameLength = readUInt16(offset + 26);
        long extraLength = readUInt16(offset + 28);
        return offset + LOCAL_HEADER_SIZE + nameLength + extraLength;
    }

    private byte[] copyStored(long start, long length, Entry entry) throws IOException {
        byte[] out = new byte[(int) length];
        System.arraycopy(data, (int) start, out, 0, (int) length);
        if (entry.size() >= 0 && entry.size() != length) {
            throw new IOException("Corrupt ZIP archive: " + display + " (entry '" + entry.name()
                    + "' is stored with " + length + " bytes but declared as " + entry.size() + ").");
        }
        return out;
    }

    /** Inflates a DEFLATE entry in its raw form (no zlib header, RFC 1951). */
    private byte[] inflateEntry(long start, long length, Entry entry) throws IOException {
        Inflater inflater = new Inflater(true);
        try (InputStream in = new InflaterInputStream(
                new ByteArrayInputStream(data, (int) start, (int) length), inflater);
                ByteArrayOutputStream out = new ByteArrayOutputStream(
                        (int) Math.min(Math.max(entry.size(), 64), INFLATE_INITIAL_BYTES))) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) > 0) {
                total += read;
                if (total > maxEntryBytes) {
                    throw new IOException("Entry '" + entry.name() + "' in " + display
                            + " expands beyond the limit of " + maxEntryBytes + " bytes.");
                }
                out.write(buffer, 0, read);
            }
            if (entry.size() >= 0 && entry.size() != total) {
                throw new IOException("Corrupt ZIP archive: " + display + " (entry '" + entry.name()
                        + "' inflates to " + total + " bytes but is declared as " + entry.size() + ").");
            }
            return out.toByteArray();
        } finally {
            inflater.end();
        }
    }

    /** Verifies the crc the central directory declares for the entry. */
    private void verifyCrc(Entry entry, byte[] content) throws IOException {
        long expected = entry.crc();
        if (expected < 0) {
            return; // no crc recorded in the archive
        }
        CRC32 crc = new CRC32();
        crc.update(content);
        if (crc.getValue() != expected) {
            throw new IOException("Corrupt ZIP archive: " + display + " (entry '" + entry.name()
                    + "' has crc " + String.format("%08x", crc.getValue()) + ", the central directory"
                    + " declares " + String.format("%08x", expected) + ").");
        }
    }

    // ========================================================================
    // Central directory parsing
    // ========================================================================

    /** Backward scan for the end-of-central-directory record. */
    private int findEndOfCentralDirectory() throws IOException {
        int lowest = Math.max(0, data.length - END_RECORD_SCAN_BYTES);
        int fallback = -1;
        for (int i = data.length - END_RECORD_SIZE; i >= lowest; i--) {
            if (readUInt32(i) != SIG_END) {
                continue;
            }
            if (fallback < 0) {
                fallback = i;
            }
            int commentLength = (int) readUInt16(i + 20);
            if (i + (long) END_RECORD_SIZE + commentLength == data.length) {
                return i; // the record whose comment reaches exactly to the end of the data
            }
        }
        if (fallback >= 0) {
            // Trailing junk after the comment (some tools append signatures): the record still
            // describes the archive, so read it instead of refusing the file.
            return fallback;
        }
        throw notAZip("no end-of-central-directory record found");
    }

    /**
     * Archive comment of the end record: it sits inside the record, right after its 22 fixed bytes.
     *
     * @return the comment, or {@code null} when the archive has none - and also when the data ends
     *         before the declared comment length (a truncated archive): half a comment would be a
     *         fabricated string, so the reader says "no comment" instead
     */
    private String readEndComment(int endRecord) throws IOException {
        int commentLength = (int) readUInt16(endRecord + 20);
        if (commentLength == 0) {
            return null;
        }
        long start = endRecord + (long) END_RECORD_SIZE;
        if (start + commentLength > data.length) {
            return null;
        }
        return new String(data, (int) start, commentLength, StandardCharsets.UTF_8);
    }

    /** Replaces the placeholders of the end record by the ZIP64 values. */
    private long[] readZip64End(int endRecord, long recordCount, long directorySize,
            long directoryOffset) throws IOException {
        long locator = (long) endRecord - ZIP64_LOCATOR_SIZE;
        if (locator < 0 || readUInt32(locator) != SIG_ZIP64_LOCATOR) {
            throw new IOException("Corrupt ZIP archive: " + display + " (ZIP64 fields are set, but the"
                    + " ZIP64 end-of-central-directory locator is missing).");
        }
        long zip64End = readUInt64(locator + 8);
        requireRange(zip64End, ZIP64_END_RECORD_SIZE, "ZIP64 end-of-central-directory record");
        if (readUInt32(zip64End) != SIG_ZIP64_END) {
            throw new IOException("Corrupt ZIP archive: " + display + " (the ZIP64 end-of-central"
                    + "-directory record at offset " + zip64End + " is missing).");
        }
        return new long[]{
                recordCount == UINT16_MAX ? readUInt64(zip64End + 32) : recordCount,
                directorySize == UINT32_MAX ? readUInt64(zip64End + 40) : directorySize,
                directoryOffset == UINT32_MAX ? readUInt64(zip64End + 48) : directoryOffset};
    }

    /** Walks the central directory and turns every record into an {@link Entry}. */
    private List<Entry> readCentralDirectory(long directoryOffset, long recordCount) throws IOException {
        if (recordCount == 0) {
            return List.of();
        }
        requireRange(directoryOffset, CENTRAL_RECORD_SIZE, "central directory");
        List<Entry> out = new ArrayList<>((int) Math.min(recordCount, 4096));
        // The walk is bounded by the data, not by the declared size: some writers understate that,
        // and a count that does not match the data has to fail with a message, not an index error.
        long pos = directoryOffset;
        for (long index = 0; index < recordCount; index++) {
            if (pos + CENTRAL_RECORD_SIZE > data.length) {
                if (index == 0) {
                    throw notAZip("the central directory does not start where the end record says");
                }
                throw new IOException("Corrupt ZIP archive: " + display + " (the central directory"
                        + " ends after " + index + " of " + recordCount + " records).");
            }
            if (readUInt32(pos) != SIG_CENTRAL) {
                throw new IOException("Corrupt ZIP archive: " + display + " (expected a central"
                        + " directory record at offset " + pos + ").");
            }
            int flags = (int) readUInt16(pos + 8);
            int method = (int) readUInt16(pos + 10);
            long dosTime = readUInt16(pos + 12);
            long dosDate = readUInt16(pos + 14);
            long crc = readUInt32(pos + 16);
            long compressedSize = readUInt32(pos + 20);
            long size = readUInt32(pos + 24);
            int nameLength = (int) readUInt16(pos + 28);
            int extraLength = (int) readUInt16(pos + 30);
            int commentLength = (int) readUInt16(pos + 32);
            long localOffset = readUInt32(pos + 42);
            requireRange(pos + CENTRAL_RECORD_SIZE, nameLength + (long) extraLength + commentLength,
                    "central directory record of entry #" + index);
            String name = new String(data, (int) (pos + CENTRAL_RECORD_SIZE), nameLength,
                    StandardCharsets.UTF_8);
            long extraStart = pos + CENTRAL_RECORD_SIZE + (long) nameLength;
            if (size == UINT32_MAX || compressedSize == UINT32_MAX || localOffset == UINT32_MAX) {
                long[] zip64 = readZip64Extra(extraStart, extraLength, size, compressedSize, localOffset);
                size = zip64[0];
                compressedSize = zip64[1];
                localOffset = zip64[2];
            }
            String entryComment = commentLength > 0
                    ? new String(data, (int) (extraStart + extraLength), commentLength,
                            StandardCharsets.UTF_8)
                    : null;
            out.add(new Entry(name, size, compressedSize, method, crc, name.endsWith("/"),
                    entryComment, dosToEpochMilli(dosDate, dosTime), localOffset, flags));
            pos += CENTRAL_RECORD_SIZE + (long) nameLength + extraLength + commentLength;
        }
        return out;
    }

    /**
     * Reads the ZIP64 extended information extra field ({@code 0x0001}) of a record. Per APPNOTE it
     * holds only the values whose 32-bit field is the placeholder, in that order.
     */
    private long[] readZip64Extra(long extraStart, int extraLength, long size, long compressedSize,
            long localOffset) throws IOException {
        long pos = extraStart;
        long end = extraStart + extraLength;
        while (pos + 4 <= end) {
            int headerId = (int) readUInt16(pos);
            int bodyLength = (int) readUInt16(pos + 2);
            long body = pos + 4;
            if (body + bodyLength > end) {
                break; // truncated extra data: the 32-bit values stay as they are
            }
            if (headerId == ZIP64_EXTRA_ID) {
                int at = 0;
                if (size == UINT32_MAX && bodyLength >= at + 8) {
                    size = readUInt64(body + at);
                    at += 8;
                }
                if (compressedSize == UINT32_MAX && bodyLength >= at + 8) {
                    compressedSize = readUInt64(body + at);
                    at += 8;
                }
                if (localOffset == UINT32_MAX && bodyLength >= at + 8) {
                    localOffset = readUInt64(body + at);
                }
                break;
            }
            pos = body + bodyLength;
        }
        return new long[]{size, compressedSize, localOffset};
    }

    private IOException notAZip(String detail) {
        return new IOException("Not a ZIP archive: " + display + " (" + detail + ").");
    }

    // ========================================================================
    // Little-endian readers (the ZIP format is little-endian throughout)
    // ========================================================================

    private int readUInt16(long pos) throws IOException {
        requireRange(pos, 2, "16-bit field");
        return (data[(int) pos] & 0xFF) | ((data[(int) pos + 1] & 0xFF) << 8);
    }

    private long readUInt32(long pos) throws IOException {
        requireRange(pos, 4, "32-bit field");
        return (data[(int) pos] & 0xFFL)
                | ((data[(int) pos + 1] & 0xFFL) << 8)
                | ((data[(int) pos + 2] & 0xFFL) << 16)
                | ((data[(int) pos + 3] & 0xFFL) << 24);
    }

    private long readUInt64(long pos) throws IOException {
        requireRange(pos, 8, "64-bit field");
        return readUInt32(pos) | (readUInt32(pos + 4) << 32);
    }

    /** Fails with a clear message instead of an index-out-of-bounds on a truncated archive. */
    private void requireRange(long pos, long length, String what) throws IOException {
        if (pos < 0 || length < 0 || pos + length > data.length) {
            throw new IOException("Corrupt ZIP archive: " + display + " (the " + what + " at offset "
                    + pos + " runs beyond the end of the archive, " + data.length + " bytes).");
        }
    }

    /** DOS date/time pair of a central directory record as epoch milliseconds (-1 when unset). */
    private static long dosToEpochMilli(long dosDate, long dosTime) {
        if (dosDate == 0) {
            return -1;
        }
        try {
            int year = (int) ((dosDate >>> 9) & 0x3F) + 1980;
            int month = (int) ((dosDate >>> 5) & 0x0F);
            int day = (int) (dosDate & 0x1F);
            int hour = (int) ((dosTime >>> 11) & 0x1F);
            int minute = (int) ((dosTime >>> 5) & 0x3F);
            int second = (int) ((dosTime & 0x1F) * 2);
            return LocalDateTime.of(year, Math.min(Math.max(month, 1), 12),
                            Math.min(Math.max(day, 1), 28),
                            Math.min(hour, 23), Math.min(minute, 59), Math.min(second, 59))
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return -1; // an impossible timestamp stays unusable, it must not fail the whole listing
        }
    }

    /**
     * True when the bytes start with a ZIP signature (local header, central directory record, data
     * descriptor or the end record of an empty archive). Content decides, never the file name - a jar
     * called {@code .bin} is still ZIP, and a {@code .zip} that is really a tar is not.
     *
     * @param data first bytes of a container
     * @return true when a ZIP signature is present
     */
    public static boolean looksLikeZip(byte[] data) {
        if (data == null || data.length < 4) {
            return false;
        }
        int signature = (data[0] & 0xFF) | ((data[1] & 0xFF) << 8) | ((data[2] & 0xFF) << 16)
                | ((data[3] & 0xFF) << 24);
        return signature == SIG_LOCAL || signature == SIG_CENTRAL || signature == SIG_END
                || signature == SIG_DATA_DESCRIPTOR;
    }
}
