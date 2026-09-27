package org.rogmann.mcp2sdk.review;

/**
 * The way back from the review UI to the tool that asked.
 * <p>
 * The review area holds no references to tools and offers no {@code service.decide(..)} entry
 * point: the request entry itself carries this consumer, so the controller can hand a decision to
 * whoever asked, without knowing who that was. Inside the tool the consumer performs the real
 * transition (under the tool's own lock, notifying a caller that is blocked in a blocking
 * {@code exec}, starting the work on the tool's executor when nobody is waiting) and reports the
 * result.
 * </p>
 * <p>
 * <b>It does not throw.</b> The reviewer's window is not a place for stack traces:
 * the implementation returns the status the request <em>actually</em> has now. If that differs from
 * what was asked for (already denied, already expired, already final), the controller shows the
 * same non-destructive flash message the Python workflow produces today for
 * "not pending anymore" - as a status, not as an exception. Whatever goes wrong <em>while the
 * approved work runs</em> is reported later through {@link ReviewService#submitResult} as a
 * {@link ReviewResponseEntry} with {@code ok = false}, and is displayed with the request.
 * </p>
 * <p>
 * Implementations must return quickly: this runs inside the reviewer's HTTP request. Anything that
 * takes longer belongs on the tool's executor.
 * </p>
 */
@FunctionalInterface
public interface DecideConsumer {

    /**
     * Hands the decision to the tool.
     *
     * @param decision what the reviewer chose
     * @return the status the request has now - the requested one
     *         ({@link ReviewStatus#APPROVED} / {@link ReviewStatus#DENIED}) or the earlier,
     *         already final status if the request was not open anymore. Never {@code null}.
     */
    ReviewStatus onDecide(ReviewDecision decision);
}
