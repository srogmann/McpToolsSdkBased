package org.rogmann.mcp2sdk.pdf;

import java.util.Map;

/**
 * A PDF stream object (ISO 32000-1 section 7.3.8): a dictionary plus the binary data
 * between {@code stream} and {@code endstream}.
 * <p>
 * A stream object always is the value of an indirect object. The reader does not copy the
 * data while parsing: it records where the data starts and how long it is, so a large
 * image stream costs nothing until the caller asks for the bytes
 * (see {@code pdf.stream(path, objNum)}).
 * </p>
 * <p>
 * {@code /Length} may itself be an indirect reference; {@link #rawLength()} keeps the value
 * as parsed, {@link #length()} returns the plain number if there is one.
 * </p>
 */
public final class PdfStream {

    /** Sentinel for a missing or unusable {@code /Length}. */
    public static final long LENGTH_UNKNOWN = -1;

    private final Map<String, Object> dict;
    private final long dataOffset;
    private final long endOffset;
    private final Object rawLength;
    private final boolean lengthApproximate;

    /**
     * Creates a stream object.
     * @param dict stream dictionary (insertion-ordered)
     * @param dataOffset absolute file offset of the first data byte
     * @param endOffset absolute file offset just after the last data byte
     *                  (equal to {@code dataOffset} when the length is unknown)
     * @param rawLength the parsed {@code /Length} value ({@link Long}, {@link PdfReference}
     *                  or {@code null})
     * @param lengthApproximate true if the length was derived from the {@code endstream}
     *                          marker because {@code /Length} was missing or wrong
     */
    public PdfStream(Map<String, Object> dict, long dataOffset, long endOffset,
                     Object rawLength, boolean lengthApproximate) {
        this.dict = dict;
        this.dataOffset = dataOffset;
        this.endOffset = endOffset;
        this.rawLength = rawLength;
        this.lengthApproximate = lengthApproximate;
    }

    /**
     * The stream dictionary (contains {@code /Length}, {@code /Filter}, {@code /Type}, ...).
     * @return dictionary (never null)
     */
    public Map<String, Object> dict() {
        return dict;
    }

    /**
     * File offset of the first stream data byte.
     * @return absolute offset
     */
    public long dataOffset() {
        return dataOffset;
    }

    /**
     * File offset just after the stream data (as declared by {@code /Length}).
     * @return absolute offset
     */
    public long endOffset() {
        return endOffset;
    }

    /**
     * The declared length as parsed ({@link Long} or {@link PdfReference}).
     * @return raw {@code /Length} value or {@code null}
     */
    public Object rawLength() {
        return rawLength;
    }

    /**
     * The declared length as a plain number.
     * @return length or {@code null} if it is a reference or missing
     */
    public Long length() {
        return rawLength instanceof Long number ? number : null;
    }

    /**
     * Whether the length had to be derived from the {@code endstream} marker.
     * @return true for an approximate length
     */
    public boolean lengthApproximate() {
        return lengthApproximate;
    }

    @Override
    public String toString() {
        return "stream@" + dataOffset + " len=" + rawLength;
    }
}
