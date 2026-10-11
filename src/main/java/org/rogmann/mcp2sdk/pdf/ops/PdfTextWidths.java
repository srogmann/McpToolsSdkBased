package org.rogmann.mcp2sdk.pdf.ops;

import org.rogmann.mcp2sdk.pdf.PdfFontWidths;
import org.rogmann.mcp2sdk.pdf.PdfGlyphMapper;

/**
 * The one question a showing operator has to ask outside the state: <em>how wide is the glyph of
 * this code</em>. Increment D5 (docs/js/pdf-content-plan.md, Aufgabe 5).
 *
 * <p>
 * 5.3.3, S. 410 gives the displacement of a glyph as
 * {@code t_x = ((w_0 − T_j/1000) × T_fs + T_c + T_w) × T_h}. Four of those five numbers are in the
 * text state the interpreter already carries; {@code w_0} is not — it is in the font, which lives
 * behind a resource name, a {@code /DescendantFonts} chain and possibly an embedded program. The
 * interpreter must not learn any of that (it would then know about pages and object numbers too),
 * so the question leaves through this interface and comes back as a width with its source.
 * </p>
 *
 * <p>
 * The single implementation is {@link org.rogmann.mcp2sdk.pdf.PdfFontScope} — the page's font
 * answers, the same lookup {@code pdf.text()} reads its characters from, so a width and a character
 * can never come from two different readings of the same {@code Tf}. A test may answer with a
 * lambda instead, which is what keeps the arithmetic of {@link TextShowOps} testable without a
 * document.
 * </p>
 *
 * <p>
 * <b>Why nothing here invents a width.</b> {@link #widthOf(long)} answers {@code null} plus the
 * source that stayed silent. The rule the fonts plan wrote for a glyph metric (Regel A5: no number
 * without a source) is the rule here: a font that names no {@code /Widths} has no known advance, and
 * a walk that filled the gap with the "usual" 1000 would report a layout the file never stated.
 * </p>
 */
public interface PdfTextWidths {

    /**
     * The font a showing operator draws with.
     *
     * @param resourceName the resource name a {@code Tf} wrote, with the slash ({@code "/F39"}), or
     *                     null when the font came from an {@code ExtGState}
     * @param fontObjNum   object number an {@code ExtGState} referenced, or null for a {@code Tf}
     *                     (Table 4.8, S. 221: the dictionary's {@code Font} entry is a reference,
     *                     not a resource name)
     * @return the font's answers, or null when the name resolves to nothing - which is a finding
     *         the walk reports, not a font of width 0
     */
    Font fontFor(String resourceName, Integer fontObjNum);

    /** A source that answers nothing at all (the walk of a stream nobody asked to measure). */
    PdfTextWidths NONE = (resourceName, fontObjNum) -> null;

    /** One font's answers about its codes. */
    interface Font {

        /**
         * Bytes per code, or null when the encoding cannot say where a code ends. Null is the case
         * 5.6.4 warns about (a predefined non-UCS CMap): the string cannot be cut into codes, so
         * not one width can be asked and the advance is not computable at all.
         */
        Integer codeBytes();

        /**
         * The width of one code with the source that answered it.
         *
         * @param code the code value (a CID for a CID font), big-endian out of the string
         * @return never null; {@link PdfGlyphMapper.Width#width()} is null exactly when the source
         *         is {@code none}
         */
        PdfGlyphMapper.Width widthOf(long code);

        /**
         * True when the writing mode is vertical ({@code WMode} 1). Which of the two widths the
         * displacement uses is decided by the CMap, not by the font dictionary (5.6.3, S. 440:
         * "This is selected by the WMode entry in the associated CMap dictionary"; 5.6.4, S. 442:
         * names ending in {@code H} are horizontal, {@code V} vertical), and the default is
         * horizontal — so a font that declares {@code /W2} metrics still displaces along x while
         * nothing says otherwise.
         */
        default boolean vertical() {
            return false;
        }

        /** Whether the dictionary declares vertical metrics at all ({@code /W2} or {@code /DW2}). */
        default boolean hasVerticalMetrics() {
            return false;
        }

        /**
         * {@code w_1} and the position vector {@code v} of a code (5.6.3, S. 440/441). Only asked
         * when {@link #vertical()} is true; null means the font says nothing, which is an answer.
         */
        default PdfFontWidths.Vertical verticalOf(long code) {
            return null;
        }

        /** The font's {@code /Subtype} as the file wrote it, or null; only for naming it in the answer. */
        default String subtype() {
            return null;
        }
    }
}
