package org.rogmann.mcp2sdk.pdf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a {@code /CIDToGIDMap} stream (ISO 32000-1, 9.7.4.1): a plain array of 16-bit big-endian
 * GIDs, indexed by CID. There is nothing to interpret here - the whole job is to cut the bytes in
 * the right width and to notice when the file cannot have meant that.
 * <p>
 * The stream is the exception to the rule that a CID <em>is</em> a GID: {@code /CIDToGIDMap
 * /Identity} (and its default for {@code CIDFontType2}) means {@code gid == cid}, while a stream
 * here says otherwise - which is exactly why {@code pdf.decodeString()} reports a {@code gid} that
 * differs from its {@code cid}. A stream of the wrong size is not repaired: an odd byte count or a
 * map that ends before the CID asked for stays a {@code null} GID plus one note, because inventing
 * a glyph index would put a character on screen that the file never selected.
 * </p>
 */
public final class PdfCidToGidMap {

    /** A CID is 16 bit, so no map can meaningfully hold more entries than that. */
    public static final int MAX_ENTRIES = 65_536;

    /** How many entries a listing samples. */
    public static final int DEFAULT_SAMPLE = 8;

    private final int[] gids;
    private final int bytes;
    private final boolean truncated;
    private final String warning;

    private PdfCidToGidMap(int[] gids, int bytes, boolean truncated, String warning) {
        this.gids = gids;
        this.bytes = bytes;
        this.truncated = truncated;
        this.warning = warning;
    }

    /**
     * Reads the decoded stream bytes.
     * @param data the decoded stream (may be null or empty)
     * @return the map - never null; an unusable stream yields no entries and a
     *         {@link #warning()} instead of a guess
     */
    public static PdfCidToGidMap read(byte[] data) {
        if (data == null || data.length == 0) {
            return new PdfCidToGidMap(new int[0], 0, false,
                    "the /CIDToGIDMap stream is empty: no CID has a glyph index");
        }
        int usable = data.length - (data.length % 2);
        int entries = Math.min(usable / 2, MAX_ENTRIES);
        boolean cut = data.length % 2 != 0 || usable / 2 > MAX_ENTRIES;
        int[] gids = new int[entries];
        for (int i = 0; i < entries; i++) {
            gids[i] = ((data[i * 2] & 0xFF) << 8) | (data[i * 2 + 1] & 0xFF);
        }
        String warning = null;
        if (data.length % 2 != 0) {
            warning = "the /CIDToGIDMap stream holds " + data.length + " bytes, which is not a"
                    + " whole number of two-byte glyph indices - the last byte was dropped";
        } else if (usable / 2 > MAX_ENTRIES) {
            warning = "the /CIDToGIDMap stream describes " + (usable / 2) + " CIDs, more than the"
                    + " " + MAX_ENTRIES + " a 16-bit code can address: the rest was not read";
        }
        return new PdfCidToGidMap(gids, data.length, cut, warning);
    }

    /**
     * The glyph index of one CID.
     * @param cid the CID
     * @return the GID, or null when this map does not reach that far
     */
    public Integer gidOf(long cid) {
        if (gids == null || cid < 0 || cid >= gids.length) {
            return null;
        }
        return gids[(int) cid];
    }

    /** Entries the map holds (one per CID). */
    public int entries() {
        return gids == null ? 0 : gids.length;
    }

    /** Bytes the stream held. */
    public int bytes() {
        return bytes;
    }

    /** True when the map is not the whole story (odd length or beyond {@link #MAX_ENTRIES}). */
    public boolean truncated() {
        return truncated;
    }

    /** What the reader has to say about the stream, or null when it read cleanly. */
    public String warning() {
        return warning;
    }

    /**
     * The block {@code pdf.font()} shows instead of the old "this is a stream" placeholder.
     * @param sampleCount how many leading entries are listed (0 = none)
     * @return {@code {entries, bytes, sample?, truncated?, note?}} - the caller adds
     *         {@code objNum}, it knows the address
     */
    public Map<String, Object> toMap(int sampleCount) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entries", (long) entries());
        out.put("bytes", (long) bytes);
        int shown = Math.min(sampleCount, entries());
        if (shown > 0) {
            List<Object> sample = new ArrayList<>();
            for (int cid = 0; cid < shown; cid++) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("cid", (long) cid);
                item.put("gid", (long) gids[cid]);
                sample.add(item);
            }
            out.put("sample", sample);
        }
        if (truncated) {
            out.put("truncated", true);
        }
        if (warning != null) {
            out.put("note", warning);
        }
        return out;
    }
}
