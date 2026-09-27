package org.rogmann.mcp2sdk.review;

import java.util.Locale;

/**
 * The status vocabulary of the four-eyes review workflow.
 * <p>
 * Every tool that needs an approval uses <b>these</b> names - a tool must not invent its own
 * status constants. That is the whole point of the enum: the review UI (list page, detail page,
 * polling, CSS) knows one set of states, and the rules that depend on them
 * ({@link #open()}, {@link #terminal()}) exist exactly once instead of being re-derived from
 * strings in every template and every service.
 * </p>
 * <p>
 * The set is chosen so that the states of the existing Python execution workflow map onto it
 * <em>exhaustively</em> - see {@link #display()} for the UI spelling and the mapping note in
 * {@code docs/js/approval-workflow-plan.md} &sect;2.2:
 * </p>
 * <table border="1">
 *   <caption>python.exec status &rarr; ReviewStatus</caption>
 *   <tr><th>{@code PythonExecResult.status()} (string, unchanged)</th><th>{@link ReviewStatus}</th></tr> *   <tr><td>{@code pending}</td><td>{@link #PENDING}</td></tr>
 *   <tr><td>{@code approved}</td><td>{@link #APPROVED}</td></tr>
 *   <tr><td>{@code executing}</td><td>{@link #RUNNING}</td></tr>
 *   <tr><td>{@code executed}</td><td>{@link #COMPLETED}</td></tr>
 *   <tr><td>{@code denied}</td><td>{@link #DENIED}</td></tr>
 *   <tr><td>{@code expired}</td><td>{@link #EXPIRED}</td></tr>
 *   <tr><td>{@code hashMismatch}</td><td>{@link #VOIDED}</td></tr>
 *   <tr><td>{@code execError}</td><td>{@link #ERROR}</td></tr>
 * </table>
 * <p>
 * The strings in the table stay as they are: they are written to {@code result.yaml} and returned
 * to the LLM, where they are pinned by tests and documentation. Only the <em>review UI</em> speaks
 * this enum. A tool that reports both keeps the translation at <b>one</b> place in the tool.
 * </p>
 */
public enum ReviewStatus {

    /** The request waits for a human decision. */
    PENDING,

    /** A human approved it; the work has not started (or not finished starting) yet. */
    APPROVED,

    /** The tool is working on the approved request. */
    RUNNING,

    /**
     * The request ran to an end. Green or red is <em>not</em> part of the status - that is
     * {@link ReviewResponseEntry#ok()}, so a completed-but-failed run is still
     * {@code COMPLETED + ok=false}, which keeps "what happened" apart from "was it good".
     */
    COMPLETED,

    /** A human denied it. Final: a denial is a decision, a model cannot renew its way past it. */
    DENIED,

    /** Nobody decided within the approval window. Re-armable while the tool allows it. */
    EXPIRED,

    /**
     * The approval lost its validity: the subject changed after it was approved (a changed
     * script, a changed target file), so executing it now would run something nobody looked at.
     */
    VOIDED,

    /** The approved work could not be performed (or not completely): it never became a result. */
    ERROR;

    /**
     * True while the request may still move on, i.e. while a reviewer or the tool can change
     * the outcome. Replaces the scattered {@code "pending".equals(status)} /
     * {@code "executing".equals(status)} checks.
     *
     * @return true if the request is not final
     */
    public boolean open() {
        return this == PENDING || this == APPROVED || this == RUNNING;
    }

    /**
     * True for a final status. This is the one definition of "final" - the review UI derives its
     * {@code TERMINAL} set from it instead of maintaining a hand-edited list of strings, which
     * is how a new tool state used to end up unpinned.
     *
     * @return true if no further transition is possible
     */
    public boolean terminal() {
        return !open();
    }

    /**
     * The spelling used by the review UI (CSS class, {@code data-status} attribute, log lines).
     * Lowercase, so a status can be dropped into an attribute without further processing.
     *
     * @return the lowercase name, e.g. {@code "voided"}
     */
    public String display() {
        return name().toLowerCase(Locale.ROOT);
    }
}
