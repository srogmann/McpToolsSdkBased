package org.rogmann.mcp2sdk.review;

/**
 * How a {@link ReviewAttribute} is presented in the review UI.
 * <p>
 * The kind is not decoration: it is what keeps one page able to show every tool. A free
 * {@code Map<String, Object>} would render as a JSON dump that a reviewer clicks through instead
 * of reads - and a rubber stamp is the failure mode this whole workflow exists to avoid. With a
 * kind the page can decide: a multi-line source becomes a {@code <pre>} block, a path becomes
 * inline code, a hash gets the note that the approval binds to exactly that content, a checklist
 * becomes a table.
 * </p>
 * <p>
 * {@link #CODE} has a second, mechanical meaning: the presence of a code attribute disables the
 * quick approve/deny buttons in the list page
 * ({@link ReviewView#allowsQuickDecision(ReviewEntry)}). Where source code or a diff is the
 * subject of the approval, a one-line title is provably not enough to decide.
 * </p>
 */
public enum AttributeKind {

    /** Free text, possibly several sentences (reason, summary). */
    TEXT,

    /**
     * Multi-line source-like content in a {@code <pre>} block: the script snapshot, a patch, a
     * generated document. Implies "the reviewer has to read this to decide".
     */
    CODE,

    /** A project-relative path (script, working directory, write target). */
    PATH,

    /**
     * A content digest (lowercase hex). The UI explains that the approval covers exactly this
     * content, because that is the security promise of the workflow.
     */
    HASH,

    /** Several short values: arguments, declared write targets, declared expectations. */
    LIST,

    /** A plain number (exit code, byte count). */
    NUMBER,

    /** A duration rendered with a unit (timeout, elapsed milliseconds). */
    DURATION,

    /**
     * A table of expectation / outcome / detail. How an expectation is <em>checked</em> stays
     * with the tool (grammar, files, hashes); the review area only knows the outcome triple.
     */
    CHECKLIST,

    /**
     * A log excerpt: a bounded preview plus the byte and line counters of the complete output
     * and the path to it. The {@code artifact} of the attribute names that file.
     */
    LOG
}
