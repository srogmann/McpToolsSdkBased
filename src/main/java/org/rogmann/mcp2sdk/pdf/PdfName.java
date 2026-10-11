package org.rogmann.mcp2sdk.pdf;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * A PDF name object (ISO 32000-1 section 7.3.5), e.g. {@code /Type} or {@code /FlateDecode}.
 * <p>
 * Names are byte strings written as {@code /} followed by regular characters; {@code #XX}
 * escapes any byte (including delimiters and whitespace). This class stores the
 * <em>decoded</em> value without the leading slash and renders it with the slash again
 * ({@link #toString()}), which is the representation used in the JavaScript results -
 * so a name is distinguishable from a string in the output.
 * </p>
 * <p>
 * The decoded bytes are rendered with the PDFDocEncoding fallback of
 * {@link PdfString#decodePdfDocEncoding(byte[])}, which never fails and keeps
 * non-ASCII bytes readable (producers that write raw UTF-8 into names are rare; the
 * raw bytes stay available through {@link #bytes()}).
 * </p>
 */
public final class PdfName implements Comparable<PdfName> {

    private final String value;

    /**
     * Creates a name from its decoded value (without leading slash).
     * @param value decoded name, e.g. {@code "Type"}
     */
    public PdfName(String value) {
        this.value = Objects.requireNonNull(value, "name value");
    }

    /**
     * Decodes the bytes after a {@code /} delimiter into a name.
     * @param rawBytes name bytes without the leading slash (may contain {@code #XX} escapes)
     * @return the decoded name
     */
    public static PdfName fromRawBytes(byte[] rawBytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(rawBytes.length);
        for (int i = 0; i < rawBytes.length; i++) {
            int b = rawBytes[i] & 0xFF;
            if (b == '#' && i + 2 < rawBytes.length) {
                int hi = PdfParser.hexDigit(rawBytes[i + 1] & 0xFF);
                int lo = PdfParser.hexDigit(rawBytes[i + 2] & 0xFF);
                if (hi >= 0 && lo >= 0) {
                    out.write((hi << 4) | lo);
                    i += 2;
                    continue;
                }
            }
            out.write(b);
        }
        return new PdfName(PdfString.decodePdfDocEncoding(out.toByteArray()));
    }

    /**
     * Gets the decoded name value without the leading slash, e.g. {@code "Type"}.
     * @return name value
     */
    public String value() {
        return value;
    }

    /**
     * Gets the name bytes (after {@code #XX} decoding).
     * @return raw name bytes
     */
    public byte[] bytes() {
        return value.getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * Tests whether this name equals the given string (ignoring a leading slash).
     * @param other name as plain string, with or without leading slash
     * @return true if equal
     */
    public boolean equalsName(String other) {
        if (other == null) {
            return false;
        }
        return other.startsWith("/") ? value.equals(other.substring(1)) : value.equals(other);
    }

    /**
     * Convenience check for a dictionary value: is {@code dict[key]} the name {@code name}?
     * @param dict dictionary (may be null)
     * @param key dictionary key (e.g. {@code "Type"})
     * @param name expected name (e.g. {@code "Page"})
     * @return true if the dictionary holds that name
     */
    public static boolean dictHas(Object dict, String key, String name) {
        if (!(dict instanceof java.util.Map<?, ?> map)) {
            return false;
        }
        Object value = map.get(key);
        return value instanceof PdfName pname && pname.equalsName(name);
    }

    /**
     * Reads a name value of a dictionary.
     * @param dict dictionary (may be null)
     * @param key dictionary key
     * @return the name value or {@code null}
     */
    public static PdfName ofDict(Object dict, String key) {
        if (!(dict instanceof java.util.Map<?, ?> map)) {
            return null;
        }
        Object value = map.get(key);
        return value instanceof PdfName pname ? pname : null;
    }

    @Override
    public String toString() {
        return '/' + value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PdfName name && name.value.equals(value);
    }

    @Override
    public int hashCode() {
        return value.hashCode() * 31;
    }

    @Override
    public int compareTo(PdfName other) {
        return value.compareTo(other.value);
    }
}
