package org.rogmann.mcp2sdk.pdf;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * A PDF string object (ISO 32000-1 section 7.3.6) as the <em>byte string</em> it is -
 * either a literal string {@code (...)}, possibly with escapes, or a hexadecimal string
 * {@code <48656C6C6F>}.
 * <p>
 * A PDF string is not text: the bytes only become text through the encoding of the
 * context (a document-info string uses PDFDocEncoding or UTF-16BE with BOM, a string in a
 * content stream uses the current font's encoding). {@link #text()} therefore applies the
 * document convention and never fails:
 * </p>
 * <ul>
 *   <li>leading {@code FE FF} &rarr; UTF-16BE (the Unicode convention of the spec),</li>
 *   <li>otherwise PDFDocEncoding (7-bit plus the few defined 0x80..0x9F mappings; codes
 *       without a mapping keep their numeric value, so nothing is silently dropped),</li>
 *   <li>{@link #text(String)} decodes with an explicit charset instead (lenient: invalid
 *       byte sequences become U+FFFD and are reported through {@link #undecodable()}).</li>
 * </ul>
 */
public final class PdfString {

    /** Codes 0x80..0x9F of PDFDocEncoding (PDF 1.7 appendix D, table D.2); 0 = no mapping. */
    private static final int[] PDF_DOC_ENCODING_HIGH = new int[128];

    static {
        PDF_DOC_ENCODING_HIGH[0x80 - 0x80] = 0x20AC;  // EuroSign
        PDF_DOC_ENCODING_HIGH[0x83 - 0x80] = 0x0192;  // florin
        PDF_DOC_ENCODING_HIGH[0x91 - 0x80] = 0x2018;  // quoteleft
        PDF_DOC_ENCODING_HIGH[0x92 - 0x80] = 0x2019;  // quoteright
        PDF_DOC_ENCODING_HIGH[0x93 - 0x80] = 0x201C;  // quotedblleft
        PDF_DOC_ENCODING_HIGH[0x94 - 0x80] = 0x201D;  // quotedblright
        PDF_DOC_ENCODING_HIGH[0x95 - 0x80] = 0x2022;  // bullet
        PDF_DOC_ENCODING_HIGH[0x96 - 0x80] = 0x2013;  // endash
        PDF_DOC_ENCODING_HIGH[0x97 - 0x80] = 0x2014;  // emdash
    }

    private final byte[] bytes;
    private final boolean hex;

    /**
     * Creates a string object.
     * @param bytes the string bytes (copied defensively)
     * @param hex true if the file wrote this as a hexadecimal string
     */
    public PdfString(byte[] bytes, boolean hex) {
        this.bytes = bytes.clone();
        this.hex = hex;
    }

    /**
     * The string bytes.
     * @return copy of the bytes
     */
    public byte[] bytes() {
        return bytes.clone();
    }

    /**
     * The string bytes without copying (for internal read-only use).
     * @return the bytes themselves
     */
    byte[] bytesRef() {
        return bytes;
    }

    /**
     * Whether the file wrote the string as {@code <hex>}.
     * @return true for hexadecimal strings
     */
    public boolean isHex() {
        return hex;
    }

    /**
     * Number of bytes of the string.
     * @return byte length
     */
    public int length() {
        return bytes.length;
    }

    /**
     * Decodes the string with the document convention (UTF-16BE after a {@code FE FF} BOM,
     * PDFDocEncoding otherwise).
     * @return the decoded text
     */
    public String text() {
        return decode(bytes);
    }

    /**
     * Decodes the string with an explicit charset (lenient: undecodable byte sequences
     * become U+FFFD; {@link #undecodable()} reports whether that happened).
     * @param encoding charset name, e.g. {@code "utf-8"}, {@code "windows-1252"},
     *                  {@code "utf-16be"}, {@code "pdfdoc"}
     * @return the decoded text
     */
    public String text(String encoding) {
        return decodeWith(bytes, encoding);
    }

    /**
     * Whether the bytes contain a sequence that is invalid for the given charset.
     * @param encoding charset name (as in {@link #text(String)})
     * @return true if at least one byte sequence had to be replaced
     */
    public boolean undecodable(String encoding) {
        Charset charset = resolveCharset(encoding);
        try {
            charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return false;
        } catch (CharacterCodingException e) {
            return true;
        }
    }

    /**
     * Decodes bytes with the document convention.
     * @param bytes string bytes
     * @return decoded text
     */
    public static String decode(byte[] bytes) {
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        return decodePdfDocEncoding(bytes);
    }

    /**
     * Decodes bytes with PDFDocEncoding: 7-bit ASCII plus the nine defined high mappings;
     * 0xA0 is a space (PDFDocEncoding has no NBSP) and codes without a mapping keep their
     * numeric value. Never fails, never loses a byte.
     * @param bytes input bytes
     * @return decoded text
     */
    public static String decodePdfDocEncoding(byte[] bytes) {
        char[] out = new char[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            out[i] = (char) pdfDocEncodingUnit(bytes[i] & 0xFF);
        }
        return new String(out);
    }

    /**
     * One byte of PDFDocEncoding as a code point - the same table as
     * {@link #decodePdfDocEncoding(byte[])}, asked per code instead of per string (a glyph-code
     * decoder works one code at a time).
     * @param b the byte value (0..255)
     * @return the code point the document convention gives that byte
     */
    public static int pdfDocEncodingUnit(int b) {
        if (b < 0x80) {
            return b;
        }
        if (b == 0xA0) {
            return ' ';
        }
        if (b == 0xA4) {
            return 0x2044;                             // fraction
        }
        if (b >= 0xC0) {
            return b;                                  // identical to Latin-1
        }
        int mapped = PDF_DOC_ENCODING_HIGH[b - 0x80];
        return mapped != 0 ? mapped : b;
    }

    /**
     * Decodes bytes with an explicit charset, lenient towards invalid sequences.
     * @param bytes input bytes
     * @param encoding charset name ({@code "pdfdoc"} selects PDFDocEncoding)
     * @return decoded text
     */
    public static String decodeWith(byte[] bytes, String encoding) {
        Charset charset = resolveCharset(encoding);
        if (charset == null) {
            return decode(bytes);
        }
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            // Cannot happen with REPLACE, but keep a fallback instead of losing the data.
            return decode(bytes);
        }
    }

    /**
     * Resolves a charset name; {@code null} or {@code "pdfdoc"} means the PDF document
     * convention.
     * @param encoding charset name
     * @return the charset or {@code null} for the document convention
     */
    static Charset resolveCharset(String encoding) {
        if (encoding == null || encoding.isBlank() || "pdfdoc".equalsIgnoreCase(encoding.trim())) {
            return null;
        }
        String name = encoding.trim().toLowerCase(Locale.ROOT);
        // UTF-16 without BOM: the PDF convention is big-endian.
        if (name.equals("unicode") || name.equals("utf-16")) {
            return StandardCharsets.UTF_16BE;
        }
        try {
            return Charset.forName(encoding.trim());
        } catch (RuntimeException e) {
            throw new PdfException("Unknown encoding '" + encoding + "'. Use 'pdfdoc' (document "
                    + "convention), 'utf-8', 'utf-16be', 'windows-1252', 'iso-8859-1', 'cp437', ...");
        }
    }

    /**
     * Formats the bytes as lowercase hex (grouped like a PDF hex string).
     * @return hex representation
     */
    public String hex() {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0x0F, 16));
            sb.append(Character.forDigit(b & 0x0F, 16));
        }
        return sb.toString();
    }

    /**
     * Renders a byte string as printable text with escapes (for warnings and previews).
     * @param bytes bytes to render
     * @param maxBytes maximum number of bytes to render
     * @return printable representation
     */
    public static String preview(byte[] bytes, int maxBytes) {
        int n = Math.min(bytes.length, maxBytes);
        StringBuilder sb = new StringBuilder(n + 8);
        for (int i = 0; i < n; i++) {
            int b = bytes[i] & 0xFF;
            if (b == '\\') {
                sb.append("\\\\");
            } else if (b >= 0x20 && b < 0x7F) {
                sb.append((char) b);
            } else if (b == '\n') {
                sb.append("\\n");
            } else if (b == '\r') {
                sb.append("\\r");
            } else if (b == '\t') {
                sb.append("\\t");
            } else {
                sb.append(String.format("\\x%02X", b));
            }
        }
        if (bytes.length > n) {
            sb.append("... (").append(bytes.length).append(" bytes total)");
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return text();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PdfString string && Arrays.equals(string.bytes, bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }
}
