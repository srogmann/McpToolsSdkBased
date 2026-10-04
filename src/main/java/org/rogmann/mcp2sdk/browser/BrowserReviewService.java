package org.rogmann.mcp2sdk.browser;

import org.rogmann.mcp2sdk.review.DecideConsumer;
import org.rogmann.mcp2sdk.review.ReviewAttribute;
import org.rogmann.mcp2sdk.review.ReviewEntry;
import org.rogmann.mcp2sdk.review.ReviewPersistenceType;
import org.rogmann.mcp2sdk.review.ReviewService;
import org.rogmann.mcp2sdk.review.ReviewStatus;
import org.rogmann.mcp2sdk.review.ReviewStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The review side of {@code browser.submit} - the four-eyes gate of the browser write path (M1,
 * decision L8 in docs/browser.md §2).
 *
 * <p><b>The gate sits in the tool, not in the proxy.</b> The tool has filled the fields (free -
 * reversible, invisible to the application), and now wants to press the save button. This service
 * takes the diff from the session's fill journal, puts the request on the shared review list and
 * <b>blocks</b> the tool call until a human decides - the same pattern the Python {@code exec} uses
 * (monitor + {@code notifyAll}, the CAS of {@link ReviewEntry#moveFromPending} decides between a late
 * approval and the expiry). Only after APPROVED does the tool send the click command; the proxy never
 * holds a request for this, and a user who presses the button themselves never sees this class
 * (implicit consent, logged as {@code review:"implicit"}).</p>
 *
 * <p>The tool id appears in {@code /review} with the first request; there is no registration
 * (see {@link ReviewService}). Requests live in memory; the entry itself is the one object the page
 * renders and the tool reads - no second truth about the status.</p>
 */
public final class BrowserReviewService implements ReviewService {

    private static final Logger LOG = LoggerFactory.getLogger(BrowserReviewService.class);

    /** The tool id under which the requests appear on the review list. */
    public static final String TOOL_ID = "browser.submit";

    /** Waiting time when the tool does not name one: long enough to decide, short enough to notice. */
    private static final int DEFAULT_WAIT_SECONDS = 300;

    @Override
    public String getToolId() {
        return TOOL_ID;
    }

    @Override
    public String getToolTitle() {
        return "Browser-Aktion";
    }

    @Override
    public ReviewPersistenceType defaultPersistence() {
        // The diff is in the attributes; no artifacts of its own.
        return ReviewPersistenceType.MEMORY;
    }

    /**
     * The outcome of a blocking wait: the status the request actually has (the CAS may have turned an
     * intended approval into an expiry), the reviewer's comment, and the entry itself so the caller
     * can report the result of the approved work.
     *
     * @param status  the current status, never {@code null}
     * @param comment reviewer comment, may be empty
     * @param entry   the request this decision belongs to
     */
    public record Decision(ReviewStatus status, String comment, ReviewEntry entry) {
    }

    /**
     * Puts a submit on the review list and waits for the decision.
     *
     * <p>The request is pending while this call blocks. When nobody decides within the window, the
     * request expires - honestly: {@link ReviewStore#transitionIfPending} CAS-decides against a
     * reviewer who clicked at the same moment, and the loser's change is dropped.</p>
     *
     * @param title       one-line subject (button, page) - what the reviewer reads first
     * @param reason      why the model wants this (may be empty)
     * @param attributes  the decision-relevant facts: page, button, the changed fields, the
     *                    validation messages, one line about what was not checked
     * @param waitSeconds approval window in seconds; {@code null} or below 1 uses
     *                    {@link #DEFAULT_WAIT_SECONDS}
     * @return the decision (never {@code null}); check {@link Decision#status()} against
     *         {@link ReviewStatus#APPROVED}
     */
    public Decision awaitDecision(String title, String reason, List<ReviewAttribute> attributes,
            Integer waitSeconds) {
        final Object monitor = new Object();
        final Decision[] box = new Decision[1];
        final ReviewEntry[] holder = new ReviewEntry[1];
        ReviewEntry entry = createRequestEntry(defaultPersistence(), title, attributes, reason,
                waitSeconds, (DecideConsumer) decision -> {
                    ReviewStatus target = decision.approved()
                            ? ReviewStatus.APPROVED : ReviewStatus.DENIED;
                    String at = decision.decidedAt() == null || decision.decidedAt().isEmpty()
                            ? org.rogmann.mcp2sdk.review.ReviewRequests.now() : decision.decidedAt();
                    // The CAS decides: a request that was moved (expired, denied a moment ago)
                    // keeps its status - the consumer reports the truth, never the intention.
                    holder[0].moveFromPending(target, decision.comment(), at);
                    ReviewStatus now = holder[0].status();
                    synchronized (monitor) {
                        box[0] = new Decision(now, decision.comment(), holder[0]);
                        monitor.notifyAll();
                    }
                    return now;
                });
        holder[0] = entry;
        submit(entry);
        int wait = (waitSeconds == null || waitSeconds < 1) ? DEFAULT_WAIT_SECONDS : waitSeconds;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(wait);
        synchronized (monitor) {
            while (box[0] == null) {
                long rest = deadline - System.nanoTime();
                if (rest <= 0) {
                    break;
                }
                try {
                    monitor.wait(Math.min(rest, TimeUnit.MILLISECONDS.toNanos(500)) / 1_000_000 + 1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (box[0] != null) {
            LOG.info("browser: review request {} decided: {}", entry.id(), box[0].status().display());
            return box[0];
        }
        // The window ran out without a decision. The CAS in transitionIfPending decides against a
        // reviewer who clicked in the same instant: the loser's transition is dropped.
        ReviewStore.getInstance().transitionIfPending(entry, ReviewStatus.EXPIRED,
                "expired: no decision within " + wait + " s");
        return new Decision(entry.status(), entry.comment(), entry);
    }
}
