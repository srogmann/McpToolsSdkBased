package org.rogmann.mcp2sdk.pdf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Stream filters of the PDF specification (ISO 32000-1 section 7.4) - the decoders that
 * turn a stream's stored bytes into the bytes the document means.
 * <p>
 * Nothing here is encrypted or application-specific: {@code pdf.stream(path, objNum)} uses
 * this class to deliver a {@code /FlateDecode} content stream uncompressed, exactly as the
 * {@code crypto} module ({@code crypto.inflate}) does for a bare zlib stream. The
 * {@code /DecodeParms} of a filter chain (predictors, {@code /Colors}, {@code /Columns},
 * {@code /EarlyChange}) are honoured because a cross-reference stream is normally stored
 * with a PNG predictor - ignoring them would produce garbage instead of offsets.
 * </p>
 *
 * <h3>Supported filters</h3>
 * <ul>
 *   <li>{@code /FlateDecode} ({@code /Fl}) - zlib stream; a missing zlib header
 *       (broken producer) is retried as raw deflate and reported as a note.</li>
 *   <li>{@code /LZWDecode} ({@code /LZW}) - PDF flavour with {@code /EarlyChange}
 *       (default 1, the value PDF producers use) and TIFF/PNG predictors.</li>
 *   <li>{@code /RunLengthDecode} ({@code /RL}), {@code /ASCIIHexDecode} ({@code /AHx}),
 *       {@code /ASCII85Decode} ({@code /A85}).</li>
 *   <li>{@code /Crypt} - only {@code /Identity} (no encryption) passes through.</li>
 *   <li>{@code /DCTDecode}, {@code /JPXDecode}, {@code /JBIG2Decode},
 *       {@code /CCITTFaxDecode} - image encodings that are passed through unchanged with a
 *       note: the bytes are already a complete JPEG/JPEG2000/fax image, so
 *       {@code pdf.stream()} can hand them to {@code fs.writeBytes} for further analysis.
 *       There is no pixel decoder here.</li>
 * </ul>
 *
 * <h3>Predictors</h3>
 * <p>
 * {@code /Predictor} 1 (none), 2 (TIFF horizontal differencing, MSB-first samples) and
 * 10..15 (PNG: None/Sub/Up/Average/Paeth, per-row filter byte). A predictor that is
 * unsupported is an <em>error</em>, never a silent pass-through - decoded garbage would be
 * worse than a refusal.
 * </p>
 */
public final class PdfFilters {

    /** Upper bound for the decoded size of a single stream (guard against zip bombs). */
    public static final long MAX_DECODED_BYTES = 256L * 1024 * 1024;

    /** Result of a filter chain: the decoded bytes and the notes worth showing. */
    public record Result(byte[] data, List<String> notes) {
    }

    /** Resolves an indirect object (needed for indirect {@code /DecodeParms}). */
    public interface Resolver {
        /**
         * Resolves an indirect reference.
         * @param ref the reference
         * @return the referenced value
         */
        Object resolve(PdfReference ref);
    }

    /** Growable byte buffer with a hard size ceiling. */
    private static final class ByteSink {
        private byte[] buf;
        private int size;

        ByteSink(int capacity) {
            this.buf = new byte[(int) Math.min(Math.max(capacity, 32), MAX_DECODED_BYTES)];
        }

        void write(int b) {
            ensure(1);
            buf[size++] = (byte) b;
        }

        void ensure(int extra) {
            long needed = (long) size + extra;
            if (needed <= buf.length) {
                return;
            }
            if (needed > MAX_DECODED_BYTES) {
                throw new PdfException("Decoded stream would exceed " + MAX_DECODED_BYTES
                        + " bytes (zip-bomb guard) - refusing to decompress further");
            }
            long cap = buf.length;
            while (cap < needed) {
                cap = Math.min(MAX_DECODED_BYTES, Math.max(cap * 2, needed));
            }
            buf = Arrays.copyOf(buf, (int) cap);
        }

        byte[] toByteArray() {
            return Arrays.copyOf(buf, size);
        }

        int size() {
            return size;
        }
    }

    private PdfFilters() {
        // Utility class
    }

    // -------------------------------------------------------------------
    // Filter chains
    // -------------------------------------------------------------------

    /**
     * Applies a complete filter chain to the stored stream bytes.
     * @param raw stored (encoded) bytes
     * @param filterSpec the {@code /Filter} value (name, array of names or {@code null})
     * @param parmsSpec the {@code /DecodeParms}/{@code /DP} value (dictionary, array or
     *                  indirect reference; may be {@code null})
     * @param resolver resolver for indirect parameter dictionaries (may be {@code null})
     * @return decoded bytes and notes
     * @throws PdfException for an unsupported or malformed filter chain
     */
    public static Result decodeChain(byte[] raw, Object filterSpec, Object parmsSpec,
                                     Resolver resolver) {
        List<String> notes = new ArrayList<>();
        if (filterSpec == null) {
            return new Result(raw, notes);
        }
        if (filterSpec instanceof PdfName name) {
            return new Result(apply(raw, name.value(), parms(parmsSpec, 0, resolver), notes), notes);
        }
        if (filterSpec instanceof List<?> list) {
            byte[] current = raw;
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                if (!(item instanceof PdfName name)) {
                    throw new PdfException("/Filter item " + i + " is not a name but "
                            + PdfParser.kindOf(item));
                }
                current = apply(current, name.value(), parms(parmsSpec, i, resolver), notes);
            }
            return new Result(current, notes);
        }
        throw new PdfException("/Filter must be a name or an array of names but is "
                + PdfParser.kindOf(filterSpec));
    }

    /**
     * Reads the parameter dictionary belonging to one filter of the chain.
     * @param parmsSpec the raw {@code /DecodeParms} value
     * @param index index of the filter inside the chain
     * @param resolver resolver for indirect dictionaries
     * @return the parameter dictionary (never null, possibly empty)
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> parms(Object parmsSpec, int index, Resolver resolver) {
        Object value = parmsSpec;
        if (value instanceof List<?> list) {
            value = index < list.size() ? list.get(index) : null;
        }
        if (value instanceof PdfReference ref && resolver != null) {
            value = resolver.resolve(ref);
            if (value instanceof PdfStream stream) {
                value = stream.dict();
            }
        }
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new PdfException("/DecodeParms[" + index + "] must be a dictionary but is "
                + PdfParser.kindOf(value));
    }

    /**
     * Applies a single filter.
     * @param data input bytes
     * @param filterName filter name without the leading slash
     * @param parms parameter dictionary (never null)
     * @param notes note sink (never null)
     * @return filtered bytes
     */
    static byte[] apply(byte[] data, String filterName, Map<String, Object> parms,
                        List<String> notes) {
        switch (filterName) {
            case "FlateDecode", "Fl" -> {
                return predictor(flate(data, notes), parms, "FlateDecode");
            }
            case "LZWDecode", "LZW" -> {
                return predictor(lzw(data, parms), parms, "LZWDecode");
            }
            case "RunLengthDecode", "RL" -> {
                return runLength(data);
            }
            case "ASCIIHexDecode", "AHx" -> {
                return asciiHex(data, notes);
            }
            case "ASCII85Decode", "A85" -> {
                return ascii85(data, notes);
            }
            case "Crypt" -> {
                return crypt(data, parms, notes);
            }
            case "DCTDecode", "DCT" -> {
                notes.add("/DCTDecode: the bytes are a complete JPEG image and are delivered"
                        + " unchanged (no pixel decoding here)");
                return data;
            }
            case "JPXDecode" -> {
                notes.add("/JPXDecode: the bytes are a complete JPEG2000 image and are delivered"
                        + " unchanged (no pixel decoding here)");
                return data;
            }
            case "JBIG2Decode" -> {
                notes.add("/JBIG2Decode: bilevel image data is delivered unchanged"
                        + " (no JBIG2 decoder)");
                return data;
            }
            case "CCITTFaxDecode", "CCF" -> {
                notes.add("/CCITTFaxDecode: fax (Group 3/4) data is delivered unchanged"
                        + " (no fax decoder)");
                return data;
            }
            default -> throw new PdfException("Unsupported stream filter /" + filterName
                    + ". Supported: FlateDecode, LZWDecode, RunLengthDecode, ASCIIHexDecode,"
                    + " ASCII85Decode, Crypt(/Identity); DCTDecode, JPXDecode, JBIG2Decode and"
                    + " CCITTFaxDecode pass the image bytes through.");
        }
    }

    /**
     * Decodes only the parameters of {@code /Crypt}: the identity filter passes the data
     * through, a real crypt filter is refused (encrypted documents are described, not
     * decrypted).
     */
    private static byte[] crypt(byte[] data, Map<String, Object> parms, List<String> notes) {
        PdfName name = PdfName.ofDict(parms, "Name");
        if (name != null && name.equalsName("Identity")) {
            notes.add("/Crypt with /Name /Identity: no encryption, bytes pass through");
            return data;
        }
        throw new PdfException("The stream is encrypted (/Filter /Crypt, /Name "
                + (name != null ? name : "unknown") + "). This reader describes encrypted PDFs"
                + " (see pdf.structure() and its 'encryption' section) but does not decrypt them.");
    }

    // -------------------------------------------------------------------
    // Flate (zlib / deflate)
    // -------------------------------------------------------------------

    /**
     * Inflates a FlateDecode stream: zlib wrapper first (the specification), raw deflate as
     * the fallback for producers that omit the two header bytes. The fallback is reported -
     * a stream that needs it is a producer bug, and the caller should know.
     * @param data stored bytes
     * @param notes note sink (may be {@code null})
     * @return inflated bytes
     */
    static byte[] flate(byte[] data, List<String> notes) {
        IOException zlibError;
        try {
            return inflateWith(data, false);
        } catch (IOException e) {
            zlibError = e;
        }
        try {
            byte[] plain = inflateWith(data, true);
            if (notes != null) {
                notes.add("The FlateDecode stream has no zlib header (deflate data without the"
                        + " two wrapper bytes): it was inflated as raw deflate");
            }
            return plain;
        } catch (IOException rawError) {
            throw new PdfException("Cannot inflate the FlateDecode stream: "
                    + describe(zlibError) + " (raw deflate also failed: "
                    + describe(rawError) + ")", zlibError);
        }
    }

    /**
     * Inflates with {@link java.util.zip.Inflater} (bounded).
     * @param data input bytes
     * @param nowrap true for raw deflate, false for a zlib stream
     * @return the inflated bytes
     * @throws IOException for corrupt input
     */
    private static byte[] inflateWith(byte[] data, boolean nowrap) throws IOException {
        Inflater inflater = new Inflater(nowrap);
        try (InputStream in = new InflaterInputStream(
                new ByteArrayInputStream(data), inflater, 8192)) {
            return readBounded(in);
        } finally {
            inflater.end();
        }
    }

    /**
     * Reads a stream up to {@link #MAX_DECODED_BYTES}.
     * @param in input stream
     * @return the bytes
     * @throws IOException on I/O or data errors
     */
    private static byte[] readBounded(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) > 0) {
            total += read;
            if (total > MAX_DECODED_BYTES) {
                throw new PdfException("Decoded stream would exceed " + MAX_DECODED_BYTES
                        + " bytes (zip-bomb guard) - refusing to decompress further");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static String describe(IOException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return message;
    }

    // -------------------------------------------------------------------
    // LZW
    // -------------------------------------------------------------------

    /**
     * Decodes a PDF LZW stream: 9-bit codes growing to 12, clear code 256, EOD 257, MSB-first
     * bit packing, {@code /EarlyChange} 1 (default, what producers write) or 0.
     * @param data stored bytes
     * @param parms filter parameters
     * @return decoded bytes
     */
    static byte[] lzw(byte[] data, Map<String, Object> parms) {
        int earlyChange = intParam(parms, "EarlyChange", 1);
        if (earlyChange != 0 && earlyChange != 1) {
            throw new PdfException("/EarlyChange must be 0 or 1 but is " + earlyChange
                    + " (use pdf.decodeStream(data,'LZWDecode',{EarlyChange:0}) to flip it)");
        }
        ByteSink sink = new ByteSink(Math.max(64, data.length * 2));
        int[] prefix = new int[4096];
        int[] suffix = new int[4096];
        int[] first = new int[4096];
        Arrays.fill(first, -1);
        int bitPos = 0;
        int codeSize = 9;
        int dictSize = 258;
        int previous = -1;
        int totalBits = data.length * 8;
        while (bitPos + codeSize <= totalBits) {
            int code = readBits(data, data.length, bitPos, codeSize);
            bitPos += codeSize;
            if (code == 256) {
                codeSize = 9;
                dictSize = 258;
                previous = -1;
                continue;
            }
            if (code == 257) {
                break;
            }
            int firstByte;
            if (code < 256) {
                sink.write(code);
                firstByte = code;
            } else if (code == dictSize && previous >= 0) {
                // KwK: the entry is defined by this very code -> string(previous) + first(previous)
                writeSequence(sink, prefix, suffix, previous);
                firstByte = first[previous];
                sink.write(firstByte);
            } else if (code < dictSize) {
                writeSequence(sink, prefix, suffix, code);
                firstByte = first[code];
            } else {
                throw new PdfException("LZW stream uses code " + code
                        + " which is not defined at that point (corrupt stream or a different"
                        + " /EarlyChange convention)");
            }
            if (previous >= 0 && dictSize < 4096) {
                prefix[dictSize] = previous;
                suffix[dictSize] = firstByte;
                first[dictSize] = firstByte;
                dictSize++;
                boolean boundary = dictSize == ((1 << codeSize) - (earlyChange == 1 ? 1 : 0));
                if (codeSize < 12 && boundary) {
                    codeSize++;
                }
            }
            previous = code;
        }
        return sink.toByteArray();
    }

    /**
     * Writes the byte sequence of a dictionary code (walks the prefix chain, then reverses).
     */
    private static void writeSequence(ByteSink sink, int[] prefix, int[] suffix, int code) {
        byte[] tmp = new byte[4096];
        int count = 0;
        int current = code;
        while (current >= 256) {
            if (count >= tmp.length || current < 0) {
                throw new PdfException("LZW dictionary chain is too long or cyclic"
                        + " (corrupt stream)");
            }
            tmp[count++] = (byte) suffix[current];
            current = prefix[current];
        }
        tmp[count++] = (byte) current;
        for (int i = count - 1; i >= 0; i--) {
            sink.write(tmp[i] & 0xFF);
        }
    }

    // -------------------------------------------------------------------
    // Simple byte-wise filters
    // -------------------------------------------------------------------

    /**
     * Decodes a RunLengthDecode stream (length byte, 128 = end of data).
     * @param data stored bytes
     * @return decoded bytes
     */
    static byte[] runLength(byte[] data) {
        ByteSink sink = new ByteSink(data.length * 2 + 16);
        int i = 0;
        while (i < data.length) {
            int length = data[i++] & 0xFF;
            if (length == 128) {
                return sink.toByteArray();
            }
            if (length < 128) {
                int count = length + 1;
                if (i + count > data.length) {
                    throw new PdfException("RunLengthDecode stream ends inside a copy run");
                }
                sink.ensure(count);
                for (int k = 0; k < count; k++) {
                    sink.write(data[i + k] & 0xFF);
                }
                i += count;
            } else {
                if (i >= data.length) {
                    throw new PdfException("RunLengthDecode stream ends inside a repeat run");
                }
                int count = 257 - length;
                int value = data[i++] & 0xFF;
                sink.ensure(count);
                for (int k = 0; k < count; k++) {
                    sink.write(value);
                }
            }
        }
        // No EOD byte: the data simply ran out (tolerated, but the caller should know).
        return sink.toByteArray();
    }

    /**
     * Decodes an ASCIIHexDecode stream (whitespace ignored, {@code >} ends the data, a
     * missing fourth digit of the last byte counts as zero).
     * @param data stored bytes
     * @param notes note sink
     * @return decoded bytes
     */
    static byte[] asciiHex(byte[] data, List<String> notes) {
        ByteSink sink = new ByteSink(data.length / 2 + 1);
        int high = -1;
        int start = 0;
        if (data.length > 0 && (data[0] & 0xFF) == '<') {
            // A leading '<' belongs to the hex-*string* syntax, not to the filter data. It is
            // harmless to skip, so it is skipped - but reported instead of silently accepted.
            start = 1;
            notes.add("ASCIIHexDecode data started with '<' (that is hex-string syntax, not"
                    + " filter data): the byte was skipped");
        }
        for (int i = start; i < data.length; i++) {
            int c = data[i] & 0xFF;
            if (c == '>') {
                if (high >= 0) {
                    sink.write((high << 4) & 0xFF);
                }
                return sink.toByteArray();
            }
            if (PdfParser.isWhitespaceByte(c)) {
                continue;
            }
            int digit = PdfParser.hexDigit(c);
            if (digit < 0) {
                throw new PdfException("ASCIIHexDecode data contains an invalid byte 0x"
                        + Integer.toHexString(c) + " at stream offset " + i);
            }
            if (high < 0) {
                high = digit;
            } else {
                sink.write((high << 4) | digit);
                high = -1;
            }
        }
        if (high >= 0) {
            sink.write((high << 4) & 0xFF);
        }
        notes.add("ASCIIHexDecode data has no '>' end-of-data marker; the whole stream was read");
        return sink.toByteArray();
    }

    /**
     * Decodes an ASCII85Decode stream (5 characters per 4 bytes, {@code z} for four zero
     * bytes, {@code ~> } ends the data).
     * @param data stored bytes
     * @param notes note sink
     * @return decoded bytes
     */
    static byte[] ascii85(byte[] data, List<String> notes) {
        ByteSink sink = new ByteSink(data.length * 4 / 5 + 4);
        long value = 0;
        int count = 0;
        boolean endFound = false;
        int i = 0;
        while (i < data.length) {
            int c = data[i] & 0xFF;
            i++;
            if (c == '~') {
                if (i < data.length && (data[i] & 0xFF) == '>') {
                    i++;
                    endFound = true;
                    break;
                }
                throw new PdfException("ASCII85Decode data contains '~' without '>' at stream"
                        + " offset " + (i - 1));
            }
            if (PdfParser.isWhitespaceByte(c)) {
                continue;
            }
            if (c == 'z' && count == 0) {
                sink.write(0);
                sink.write(0);
                sink.write(0);
                sink.write(0);
                continue;
            }
            int digit = c - 33;
            if (digit < 0 || digit > 84) {
                throw new PdfException("ASCII85Decode data contains an invalid byte 0x"
                        + Integer.toHexString(c) + " at stream offset " + (i - 1));
            }
            value = value * 85 + digit;
            count++;
            if (count == 5) {
                sink.write((int) ((value >>> 24) & 0xFF));
                sink.write((int) ((value >>> 16) & 0xFF));
                sink.write((int) ((value >>> 8) & 0xFF));
                sink.write((int) (value & 0xFF));
                value = 0;
                count = 0;
            }
        }
        if (count == 1) {
            throw new PdfException("ASCII85Decode data ends with a single leftover character");
        }
        if (count > 1) {
            for (int k = count; k < 5; k++) {
                value = value * 85 + 84;
            }
            for (int k = 0; k < count - 1; k++) {
                sink.write((int) ((value >>> (24 - 8 * k)) & 0xFF));
            }
        }
        if (!endFound) {
            notes.add("ASCII85Decode data has no '~>' end-of-data marker; the whole stream was read");
        }
        return sink.toByteArray();
    }

    // -------------------------------------------------------------------
    // Predictors
    // -------------------------------------------------------------------

    /**
     * Applies the {@code /Predictor} of the filter parameters. Public because the full filter
     * path ({@link #decode(byte[], String, Map)}) always inflates first - this method lets a
     * caller (and the tests) reconstruct bytes that are already inflated.
     * @param data inflated bytes
     * @param parms filter parameters ({@code Predictor}, {@code Columns}, {@code Colors},
     *              {@code BitsPerComponent})
     * @return reconstructed bytes
     */
    public static byte[] applyPredictor(byte[] data, Map<String, Object> parms) {
        return predictor(data, parms, "FlateDecode/LZWDecode");
    }

    /**
     * Applies the {@code /Predictor} of the filter parameters.
     * @param data decoded bytes (before reconstruction)
     * @param parms filter parameters
     * @param filterName filter name for messages
     * @return reconstructed bytes
     */
    static byte[] predictor(byte[] data, Map<String, Object> parms, String filterName) {
        int predictor = intParam(parms, "Predictor", 1);
        if (predictor <= 1 || data.length == 0) {
            return data;
        }
        int colors = Math.max(1, intParam(parms, "Colors", 1));
        int bitsPerComponent = Math.max(1, intParam(parms, "BitsPerComponent", 8));
        int columns = Math.max(1, intParam(parms, "Columns", 1));
        long rowBits = (long) colors * columns * bitsPerComponent;
        if (rowBits / 8 > Integer.MAX_VALUE - 8) {
            throw new PdfException("/Predictor parameters of the " + filterName
                    + " stream are too large (Colors=" + colors + ", Columns=" + columns
                    + ", BitsPerComponent=" + bitsPerComponent + ")");
        }
        int rowBytes = (int) ((rowBits + 7) / 8);
        if (predictor >= 10 && predictor <= 15) {
            return pngPredictor(data, rowBytes);
        }
        if (predictor == 2) {
            return tiffPredictor(data, rowBytes, colors, columns, bitsPerComponent);
        }
        throw new PdfException("Unsupported /Predictor " + predictor + " for " + filterName
                + " (supported: 1 none, 2 TIFF horizontal differencing, 10..15 PNG)");
    }

    /**
     * PNG predictors (ISO/IEC 15948, as used by PDF {@code /Predictor} 10..15): every row is
     * prefixed by a filter-type byte (0 None, 1 Sub, 2 Up, 3 Average, 4 Paeth).
     * @param data decoded bytes with filter bytes
     * @param rowBytes bytes per row (without the filter byte)
     * @return reconstructed bytes
     */
    static byte[] pngPredictor(byte[] data, int rowBytes) {
        if (rowBytes <= 0) {
            return data;
        }
        ByteSink sink = new ByteSink(data.length);
        byte[] prev = new byte[rowBytes];
        int pos = 0;
        int row = 0;
        while (pos < data.length) {
            int type = data[pos++] & 0xFF;
            int take = Math.min(rowBytes, data.length - pos);
            byte[] cur = new byte[rowBytes];
            System.arraycopy(data, pos, cur, 0, take);
            pos += take;
            switch (type) {
                case 0 -> { /* None */ }
                case 1 -> {
                    for (int i = 0; i < rowBytes; i++) {
                        int left = i >= 1 ? cur[i - 1] & 0xFF : 0;
                        cur[i] = (byte) ((cur[i] & 0xFF) + left);
                    }
                }
                case 2 -> {
                    for (int i = 0; i < rowBytes; i++) {
                        cur[i] = (byte) ((cur[i] & 0xFF) + (prev[i] & 0xFF));
                    }
                }
                case 3 -> {
                    for (int i = 0; i < rowBytes; i++) {
                        int left = i >= 1 ? cur[i - 1] & 0xFF : 0;
                        cur[i] = (byte) ((cur[i] & 0xFF) + ((left + (prev[i] & 0xFF)) >>> 1));
                    }
                }
                case 4 -> {
                    for (int i = 0; i < rowBytes; i++) {
                        int left = i >= 1 ? cur[i - 1] & 0xFF : 0;
                        int up = prev[i] & 0xFF;
                        int upLeft = i >= 1 ? prev[i - 1] & 0xFF : 0;
                        cur[i] = (byte) ((cur[i] & 0xFF) + paeth(left, up, upLeft));
                    }
                }
                default -> throw new PdfException("PNG predictor: unknown filter type " + type
                        + " in row " + row + " (row " + (pos - take - 1) + " of the stream)");
            }
            sink.ensure(take);
            for (int i = 0; i < take; i++) {
                sink.write(cur[i] & 0xFF);
            }
            prev = cur;
            row++;
        }
        return sink.toByteArray();
    }

    /**
     * PNG Paeth predictor function.
     * @param a left
     * @param b up
     * @param c up-left
     * @return predicted byte value
     */
    static int paeth(int a, int b, int c) {
        int p = a + b - c;
        int pa = Math.abs(p - a);
        int pb = Math.abs(p - b);
        int pc = Math.abs(p - c);
        if (pa <= pb && pa <= pc) {
            return a;
        }
        return pb <= pc ? b : c;
    }

    /**
     * TIFF predictor 2 (horizontal differencing). Samples are packed MSB-first, and the
     * differencing runs per colour component over the row.
     * @param data decoded bytes
     * @param rowBytes bytes per row
     * @param colors number of colour components
     * @param columns samples per component per row
     * @param bitsPerComponent bits per sample
     * @return reconstructed bytes
     */
    static byte[] tiffPredictor(byte[] data, int rowBytes, int colors, int columns,
                                int bitsPerComponent) {
        if (rowBytes <= 0 || bitsPerComponent > 32) {
            return data;
        }
        byte[] out = new byte[data.length];
        int[] previous = new int[colors];
        int mask = (1 << bitsPerComponent) - 1;
        int samplesPerRow = colors * columns;
        for (int rowStart = 0; rowStart < data.length; rowStart += rowBytes) {
            int rowLength = Math.min(rowBytes, data.length - rowStart);
            int bitPos = 0;
            int outBit = 0;
            for (int sample = 0; sample < samplesPerRow; sample++) {
                if (bitPos + bitsPerComponent > rowLength * 8
                        || outBit + bitsPerComponent > rowLength * 8) {
                    break;
                }
                int raw = readBits(data, rowStart + rowLength, rowStart, bitPos, bitsPerComponent);
                bitPos += bitsPerComponent;
                int channel = sample % colors;
                int value = (raw + previous[channel]) & mask;
                previous[channel] = value;
                writeBits(out, rowStart + rowLength, rowStart, outBit, bitsPerComponent, value);
                outBit += bitsPerComponent;
            }
        }
        return out;
    }

    /**
     * Reads {@code n} bits (at most 32) MSB-first from a byte range.
     * @param data byte array
     * @param rangeEnd exclusive end of the readable range
     * @param rangeStart inclusive start of the readable range
     * @param bitPos bit offset inside the range
     * @param n number of bits
     * @return the value
     */
    static int readBits(byte[] data, int rangeEnd, int rangeStart, int bitPos, int n) {
        int value = 0;
        for (int i = 0; i < n; i++) {
            int bitIndex = bitPos + i;
            int byteIndex = rangeStart + (bitIndex >>> 3);
            int bit = byteIndex < rangeEnd ? (data[byteIndex] >>> (7 - (bitIndex & 7))) & 1 : 0;
            value = (value << 1) | bit;
        }
        return value;
    }

    /**
     * Reads {@code n} bits MSB-first from a whole array (LZW helper).
     */
    private static int readBits(byte[] data, int length, int bitPos, int n) {
        return readBits(data, length, 0, bitPos, n);
    }

    /**
     * Writes {@code n} bits MSB-first into a zeroed byte range.
     */
    static void writeBits(byte[] data, int rangeEnd, int rangeStart, int bitPos, int n, int value) {
        for (int i = 0; i < n; i++) {
            int bitIndex = bitPos + i;
            int byteIndex = rangeStart + (bitIndex >>> 3);
            if (byteIndex >= rangeEnd) {
                return;
            }
            if (((value >>> (n - 1 - i)) & 1) == 1) {
                data[byteIndex] |= (byte) (1 << (7 - (bitIndex & 7)));
            }
        }
    }

    /**
     * Reads an integer parameter with a default.
     * @param parms parameter dictionary
     * @param key parameter name
     * @param fallback value when absent or not a number
     * @return the integer value
     */
    static int intParam(Map<String, Object> parms, String key, int fallback) {
        Object value = parms.get(key);
        try {
            if (value instanceof Long number) {
                return Math.toIntExact(number);
            }
        } catch (ArithmeticException e) {
            throw new PdfException("Parameter /" + key + " = " + value + " is out of range");
        }
        if (value instanceof Double real) {
            return (int) real.longValue();
        }
        return fallback;
    }

    // -------------------------------------------------------------------
    // Manual decoding (pdf.decodeStream)
    // -------------------------------------------------------------------

    /**
     * Decodes raw bytes with a single filter - the escape hatch when a document nests a
     * filter in a way the automatic chain cannot express.
     * @param data stored bytes
     * @param filterName filter name with or without the leading slash
     * @param parms parameter dictionary (may be null)
     * @return decoded bytes and notes
     */
    public static Result decode(byte[] data, String filterName, Map<String, Object> parms) {
        List<String> notes = new ArrayList<>();
        String name = filterName != null && filterName.startsWith("/")
                ? filterName.substring(1) : filterName;
        if (name == null || name.isBlank()) {
            throw new PdfException("decodeStream needs a filter name, e.g. 'FlateDecode'");
        }
        byte[] decoded = apply(data, name, parms != null ? parms : Map.of(), notes);
        return new Result(decoded, notes);
    }

    /**
     * Convenience for the most common case: inflate a zlib (or raw deflate) byte array.
     * @param data zlib or raw deflate bytes
     * @return inflated bytes
     */
    public static byte[] inflate(byte[] data) {
        return flate(data, null);
    }

    /**
     * Lists the filter names this class can decode (for help texts).
     * @return supported filter names
     */
    public static List<String> supportedFilters() {
        return List.of("FlateDecode", "LZWDecode", "RunLengthDecode", "ASCIIHexDecode",
                "ASCII85Decode", "Crypt(/Identity)");
    }
}
