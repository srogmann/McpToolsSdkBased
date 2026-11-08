package org.rogmann.mcp2sdk.pdfedit;

import org.rogmann.mcp2sdk.pdf.PdfFileStructure;

/**
 * The budgets of the {@code pdfedit} module - one place, read by the checks, by
 * {@code pdfedit.status()} and by {@code pdfedit.help()}.
 * <p>
 * House rule: every number of this module is a named constant here and is printed in the status
 * answer. A limit is a statement about what the module does, so it must not be a literal that
 * only the code knows. The input limit is the one the reading module already enforces
 * ({@link PdfFileStructure#MAX_PDF_BYTES}) - the two modules see the same files, so they must not
 * disagree about how large a file may be.
 * </p>
 */
public final class PdfEditLimits {

    /** Largest input document accepted (bytes). */
    public static final long MAX_INPUT_BYTES = PdfFileStructure.MAX_PDF_BYTES;

    /**
     * Largest result accepted (bytes). A full save may grow a document (object streams are
     * expanded, xref tables rewritten), so this is a separate number and not just the input limit.
     */
    public static final long MAX_OUTPUT_BYTES = 512L * 1024 * 1024;

    /**
     * Largest input document for {@code incremental:true} (bytes). An incremental save holds the
     * old revision and the new one in memory at the same time, so the ceiling is lower.
     */
    public static final long MAX_INCREMENTAL_BYTES = 64L * 1024 * 1024;

    /** Largest number of ops in one call. */
    public static final int MAX_OPS = 10_000;

    /** Largest number of pages one call may add. */
    public static final int MAX_PAGES_ADDED = 500;

    /** Largest stream payload of {@code stream.set} and {@code object.add} (bytes). */
    public static final int MAX_STREAM_BYTES = 64 * 1024 * 1024;

    /** Largest number of operators one {@code content.cut} removes. */
    public static final int MAX_CUT_OPERATORS = 5_000;

    /**
     * Largest TrueType file one call embeds ({@code font:{file:…}}), in bytes. A font program is
     * data that is copied into the result as it is, so the limit is about the file, not about the
     * text written with it.
     */
    public static final int MAX_FONT_BYTES = 32 * 1024 * 1024;

    /**
     * Largest text one {@code text.put} or {@code text.block} writes (characters). A block of
     * text is laid out line by line, so the cost is not the string but the number of lines.
     */
    public static final int MAX_TEXT_CHARS = 100_000;

    /**
     * Largest number of strings one {@code text.measure} measures. Measured is the same work as
     * written - a word's advance - so the budget is about the answer: one number per string comes
     * back, and a caller that needs more than this is laying out a book in one call (it should
     * measure per page, which is also what keeps the answer readable).
     */
    public static final int MAX_MEASURE_TEXTS = 5_000;

    /** Largest number of bookmark entries one {@code doc.outline} creates. */
    public static final int MAX_OUTLINE_ITEMS = 5_000;

    /** Largest text of one annotation ({@code annot.add}, {@code link.add}) in characters. */
    public static final int MAX_ANNOTATION_CHARS = 4_096;

    /** Largest value one form field holds in characters ({@code form.fill}). */
    public static final int MAX_FIELD_VALUE_CHARS = 1_024;

    /** Largest number of pages {@code verify()} walks. */
    public static final int MAX_VERIFY_PAGES = 5_000;

    /** Largest number of objects {@code verify()} reads. */
    public static final int MAX_VERIFY_OBJECTS = 200_000;

    private PdfEditLimits() {
        // Utility class
    }
}
