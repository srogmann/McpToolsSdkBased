package org.rogmann.mcp2sdk.pdf;

/**
 * An indirect reference to another object (ISO 32000-1 section 7.3.10), e.g. {@code 12 0 R}.
 * <p>
 * References are the reason a PDF reader needs the cross-reference table: the value of a
 * dictionary entry may be a reference that only the file structure can resolve. The
 * JavaScript view keeps references explicit ({@code {ref: "12 0 R", objNum: 12, gen: 0}})
 * and resolves them on demand ({@code {resolveRefs: true}} or {@code pdf.object()}).
 * </p>
 * @param objNum object number (the {@code num} part)
 * @param gen generation number (the {@code gen} part)
 */
public record PdfReference(int objNum, int gen) {

    /**
     * Formats the reference the way it is written in the file, e.g. {@code "12 0 R"}.
     * @return reference text
     */
    @Override
    public String toString() {
        return objNum + " " + gen + " R";
    }

    /**
     * Gets a map key for the reference, e.g. {@code "12 0"}.
     * @return key string
     */
    public String key() {
        return objNum + " " + gen;
    }
}
