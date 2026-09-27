package org.rogmann.mcp2sdk.review;

import java.util.List;

/**
 * What the tool reports back after the approved work was done - the record of the run.
 * <p>
 * This is the second half of the four-eyes idea and the reason a long-running tool can use the
 * review area at all: the reviewer approved an intention, and this is the account of what became of
 * it. It arrives through {@link ReviewService#submitResult(ReviewEntry, ReviewResponseEntry)}
 * whenever the tool is finished, which may be long after the request was approved and long after
 * the tool call that created it.
 * </p>
 * <p>
 * <b>Failures are data, not exceptions</b> (see {@link DecideConsumer}): a run that failed is
 * {@code status = COMPLETED} (or {@link ReviewStatus#ERROR} when it never became a result) with
 * {@link #ok()} {@code false} and an explanation in {@link #summary()} / {@link #comment()}. Only
 * then can the review page show the whole story - approved, ran, failed, why - which is what makes
 * an approval auditable afterwards instead of a remembered yes.
 * </p>
 * <p>
 * {@link #ok()} is separate from the status on purpose: "it finished" and "it was good" are
 * different facts, and collapsing them into one enum is how a failed run ends up looking done.
 * </p>
 *
 * @param status     final status, normally {@link ReviewStatus#COMPLETED} or
 *                   {@link ReviewStatus#ERROR}
 * @param ok         true only if the work succeeded <em>and</em> every declared check was met
 * @param summary    one line for the list page ("exit 0, 3 of 3 expectations met")
 * @param attributes outcome details (exit code, duration, log excerpts, produced diff); never
 *                   {@code null}, may be empty
 * @param comment    error detail or additional note for the reviewer, may be empty
 * @param durationMs wall-clock duration of the work phase
 */
public record ReviewResponseEntry(
        ReviewStatus status,
        boolean ok,
        String summary,
        List<ReviewAttribute> attributes,
        String comment,
        long durationMs) {

    /**
     * Compact constructor enforcing the invariants of the result section.
     *
     * @throws IllegalArgumentException if the status is missing or not final
     */
    public ReviewResponseEntry {
        if (status == null) {
            throw new IllegalArgumentException("A review response needs a final status.");
        }
        if (!status.terminal()) {
            throw new IllegalArgumentException("A review response reports an end state, got "
                    + status.display() + " (open). Use " + ReviewStatus.COMPLETED.display()
                    + " or " + ReviewStatus.ERROR.display() + ".");
        }
        summary = ReviewAttributes.sanitize(summary, ReviewAttributes.MAX_REASON_CHARS);
        attributes = (attributes == null) ? List.of() : List.copyOf(attributes);
        comment = ReviewAttributes.sanitize(comment, ReviewAttributes.MAX_REASON_CHARS);
        if (durationMs < 0) {
            durationMs = 0L;
        }
    }

    /**
     * A green result.
     *
     * @param summary    one-line account of the run
     * @param attributes outcome details, may be empty
     * @param durationMs duration in milliseconds
     * @return the response
     */
    public static ReviewResponseEntry completed(String summary, List<ReviewAttribute> attributes,
            long durationMs) {
        return new ReviewResponseEntry(ReviewStatus.COMPLETED, true, summary, attributes, "",
                durationMs);
    }

    /**
     * A run that finished but did not deliver what was promised.
     *
     * @param summary    one-line account of the failure
     * @param comment    the detail (error message, unmet expectations)
     * @param attributes whatever was captured before the failure, may be empty
     * @param durationMs duration in milliseconds
     * @return the response
     */
    public static ReviewResponseEntry failed(String summary, String comment,
            List<ReviewAttribute> attributes, long durationMs) {
        return new ReviewResponseEntry(ReviewStatus.COMPLETED, false, summary, attributes, comment,
                durationMs);
    }

    /**
     * A run that never became a result (the work could not be performed at all).
     *
     * @param summary one-line account
     * @param comment the reason
     * @return the response
     */
    public static ReviewResponseEntry error(String summary, String comment) {
        return new ReviewResponseEntry(ReviewStatus.ERROR, false, summary, List.of(), comment, 0L);
    }

    /**
     * Number of declared checks that were not met, summed over every
     * {@link AttributeKind#CHECKLIST} attribute.
     *
     * @return count of unmet checks (0 when the response carries no checklist)
     */
    public int unmetChecks() {
        int unmet = 0;
        for (ReviewAttribute attribute : attributes) {
            for (ReviewCheck check : attribute.checks()) {
                if (!check.met()) {
                    unmet++;
                }
            }
        }
        return unmet;
    }
}
