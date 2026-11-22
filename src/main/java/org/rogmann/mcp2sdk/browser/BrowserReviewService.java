package org.rogmann.mcp2sdk.browser;

import org.rogmann.mcp2sdk.review.DecideConsumer;
import org.rogmann.mcp2sdk.review.ReviewAttribute;
import org.rogmann.mcp2sdk.review.ReviewEntry;
import org.rogmann.mcp2sdk.review.ReviewPersistenceType;
import org.rogmann.mcp2sdk.review.ReviewRequests;
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
 * takes the diff from the session's fill journal and puts the request on the shared review list; the
 * click leaves the server only after a human approved. The proxy never holds a request for this, and
 * a user who presses the button themselves never sees this class (implicit consent, logged as
 * {@code review:"implicit"}).</p>
 *
 * <p><b>Two forms, one request</b> (docs/browser.md §7.4, §13):</p>
 * <ul>
 *   <li><b>blocking</b> ({@link #awaitDecision}): the tool call waits for the decision - the form M1
 *       shipped with, kept because a short decision is the ordinary case;</li>
 *   <li><b>non-blocking</b> ({@link #fileRequest}): the request is filed and the call returns at once.
 *       The model tells the user, ends its turn, and later calls {@code browser(action=resume)} with the
 *       review id - which reads the status and, on APPROVED, presses the button. An expired request can
 *       be re-armed with {@link #renewEntry} (same id, same page, same diff; {@link #MAX_RENEWALS}
 *       times) instead of transmitting everything again.</li>
 * </ul>
 *
 * <p>That second form is the browser's version of AG-UI's {@code Interrupt}/{@code Resume} pair: a run
 * that needs something from outside <em>ends</em> and says what it waits for, instead of parking a
 * thread on a monitor. The same argument the reviewer's acoustic signal makes - a person may be away
 * from the screen - is why a request has to outlive the tool call that filed it.</p>
 *
 * <p><b>Nothing here decides anything.</b> A denial is final ({@link #renewEntry} refuses it): the model
 * has to address the comment and ask again with a new request. The transitions themselves are the
 * review area's ({@link ReviewEntry#moveFromPending}, {@link ReviewStore#transitionIfPending}), so the
 * page and the tool never disagree about a status.</p>
 */
public final class BrowserReviewService implements ReviewService {

    private static final Logger LOG = LoggerFactory.getLogger(BrowserReviewService.class);

    /** The tool id under which the requests appear on the review list. */
    public static final String TOOL_ID = "browser.submit";

    /** Waiting time when the tool does not name one: long enough to decide, short enough to notice. */
    public static final int DEFAULT_WAIT_SECONDS = 300;

    /**
     * How often one request may be re-armed.
     *
     * <p>Re-arming never approves anything - it only puts the request back into {@code pending}, so a
     * human still decides. The limit exists because a model that polls in a loop would otherwise nag the
     * reviewer forever; after it, the model has to file a new request, which is also a useful signal
     * that the submit needs a fresh look.</p>
     */
    public static final int MAX_RENEWALS = 5;

    @Override
    public String getToolId() {
        return TOOL_ID;
    }

    @Override
    public String getToolTitle() {
        return "Browser action";
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

    // ========================================================================
    // Filing a request
    // ========================================================================

    /**
     * Files a submit <b>without waiting</b> and returns the request - the interrupt form.
     *
     * <p>The caller keeps the review id and comes back later ({@link #statusOf}); the window runs from
     * now, so a request nobody looks at expires honestly instead of waiting forever. The decision is
     * applied by {@link #decider(ReviewEntry[])}, which simply moves the entry - the caller is not
     * sitting on a monitor, because there is no caller left to notify.</p>
     *
     * @param title       one-line subject (button, page) - what the reviewer reads first
     * @param reason      why the model wants this (may be empty)
     * @param attributes  the decision-relevant facts (page, button, the changed fields, validation)
     * @param waitSeconds approval window in seconds; {@code null} or below 1 uses
     *                    {@link #DEFAULT_WAIT_SECONDS}
     * @return the request, in {@link ReviewStatus#PENDING}
     */
    public ReviewEntry fileRequest(String title, String reason, List<ReviewAttribute> attributes,
            Integer waitSeconds) {
        final ReviewEntry[] holder = new ReviewEntry[1];
        ReviewEntry entry = createRequestEntry(defaultPersistence(), title, attributes, reason,
                waitSeconds, decider(holder));
        holder[0] = entry;
        submit(entry);
        LOG.info("browser: review request {} filed without blocking (window {} s)", entry.id(),
                waitSecondsOf(entry));
        return entry;
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
                            ? ReviewRequests.now() : decision.decidedAt();
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
        int wait = waitSecondsOf(entry);
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

    /**
     * The consumer for a request nobody waits on: it moves the entry and reports the status the request
     * really has.
     *
     * <p>Bound to the entry through a holder rather than looked up in the store: the request outlives the
     * call that filed it, but the object is the same one for its whole life, so there is one status and
     * no second truth. The holder is filled right after {@code createRequestEntry} and before
     * {@code submit}, so a decision can never see it empty - and if it ever did, the answer is
     * {@code PENDING}, the only status that then cannot lie (the reviewer's request would simply not have
     * taken effect, which is what the controller shows).</p>
     *
     * @param holder one-element array holding the request once it is created
     * @return the consumer the review area hands the decision to
     */
    private static DecideConsumer decider(ReviewEntry[] holder) {
        return decision -> {
            ReviewEntry entry = holder[0];
            if (entry == null) {
                LOG.warn("browser: a decision arrived for review request {} before it was filed - "
                        + "ignored", decision.id());
                return ReviewStatus.PENDING;
            }
            // The window is enforced where the decision arrives, not only where somebody reads the
            // request: a filed submit whose window ran out may no longer be answered - the model was
            // told "expired, nothing was clicked", and a late yes would make that answer false. The
            // reviewer's way on is the same as the model's: renew opens the window again, and then a
            // human still decides (docs/browser.md §7.4a).
            if (entry.pending() && entry.expiresAtMillis() > 0
                    && System.currentTimeMillis() > entry.expiresAtMillis()) {
                ReviewStore.getInstance().transitionIfPending(entry, ReviewStatus.EXPIRED,
                        "expired: the decision arrived after the window ("
                                + waitSecondsOf(entry) + " s) - renew opens it again");
                LOG.info("browser: review request {} was decided after its window - the request now "
                        + "reads '{}'", entry.id(), entry.status().display());
                return entry.status();
            }
            ReviewStatus target = decision.approved()
                    ? ReviewStatus.APPROVED : ReviewStatus.DENIED;
            String at = decision.decidedAt() == null || decision.decidedAt().isEmpty()
                    ? ReviewRequests.now() : decision.decidedAt();
            entry.moveFromPending(target, decision.comment(), at);
            LOG.info("browser: review request {} is now {}", entry.id(), entry.status().display());
            return entry.status();
        };
    }

    // ========================================================================
    // Coming back to a request
    // ========================================================================

    /**
     * Reads a request of this tool and applies the one transition nobody else may make: a pending
     * request whose window has run out becomes {@code expired} the moment somebody looks at it.
     *
     * <p>Lazy on purpose: the browser workflow has no reaper thread. A request is looked at exactly when
     * a model resumes it or a reviewer opens the page, and the review page renders {@code expiresAt} from
     * the entry - so a stale "pending" can only be read by somebody who asks, and that is the moment it
     * is corrected.</p>
     *
     * @param reviewId the review request id
     * @return the request, never {@code null}
     * @throws IllegalArgumentException if the id is unknown or belongs to another tool
     */
    public ReviewEntry statusOf(String reviewId) {
        ReviewEntry entry = getEntry(reviewId);
        if (entry.pending() && entry.expiresAtMillis() > 0
                && System.currentTimeMillis() > entry.expiresAtMillis()) {
            ReviewStore.getInstance().transitionIfPending(entry, ReviewStatus.EXPIRED,
                    "expired: no decision within " + waitSecondsOf(entry) + " s");
        }
        return entry;
    }

    /**
     * Re-arms a request so the reviewer can decide it again - same id, same page, same diff.
     *
     * <p>Allowed out of {@code pending} (nothing changes but the countdown) and out of
     * {@code expired} (the window starts again). Refused for every decided or final state: an approval
     * means "read the outcome with {@code resume}", and a denial is a human decision that must not be
     * worn down by asking again - the model has to address the comment and file a new request.</p>
     *
     * @param reviewId    the review request id
     * @param waitSeconds new window in seconds, {@code null} keeps the previous one
     * @param note        why the model asks again (shown to the reviewer)
     * @return the request, now pending again
     * @throws IllegalArgumentException if the id is unknown, the status does not allow a re-arm, or the
     *         limit {@link #MAX_RENEWALS} is reached
     */
    public ReviewEntry renewEntry(String reviewId, Integer waitSeconds, String note) {
        ReviewEntry entry = getEntry(reviewId);
        ReviewStatus status = entry.status();
        switch (status) {
            case PENDING, EXPIRED -> {
                // allowed: keep waiting, or start the window again
            }
            case DENIED -> throw new IllegalArgumentException("review " + entry.id() + " was DENIED "
                    + "by the reviewer" + (entry.comment().isEmpty() ? "" : " ('" + entry.comment()
                    + "')") + ". A denial is a human decision and cannot be re-armed - address the "
                    + "comment and file a new submit if the action is still wanted.");
            default -> throw new IllegalArgumentException("review " + entry.id() + " is already "
                    + status.display() + " - the decision was made. Use action=resume with that "
                    + "reviewId to read what happened.");
        }
        if (entry.renewCount() >= MAX_RENEWALS) {
            throw new IllegalArgumentException("review " + entry.id() + " was already re-armed "
                    + entry.renewCount() + " times (limit " + MAX_RENEWALS + "). Either the reviewer "
                    + "is unavailable or the submit needs a new look - tell the user, or file a new "
                    + "submit with an updated reason.");
        }
        int seconds = (waitSeconds == null || waitSeconds < 1)
                ? waitSecondsOf(entry) : waitSeconds;
        int attempt = entry.renewCount() + 1;
        // Re-opening is a deliberate transition back into PENDING - the counterpart of
        // moveFromPending(), which is a compare-and-set out of PENDING and cannot do this direction.
        // The order matters: the fields that described the end are cleared and the window is armed
        // *before* the status becomes PENDING again, so nobody can read "pending" with the old
        // "expired: …" comment next to it.
        if (status == ReviewStatus.EXPIRED) {
            entry.setComment("");
            entry.setDecidedAt("");
        }
        entry.armWindow(seconds);
        entry.recordRenewal(attempt, note);
        entry.setStatus(ReviewStatus.PENDING);
        LOG.info("browser: review request {} RE-ARMED (previously '{}', attempt {}/{} of the limit, "
                + "new window {} s)", entry.id(), status.display(), attempt, MAX_RENEWALS, seconds);
        return entry;
    }

    @Override
    public ReviewStatus renewPending(String reviewId, Integer waitSeconds, String note) {
        return renewEntry(reviewId, waitSeconds, note).status();
    }

    /**
     * Moves a still-pending request to {@code expired} because this session can no longer execute it
     * (the browser tab was closed): leaving it open would keep a decision on the reviewer's page that
     * nobody can honour (the "no orphaned interrupts" rule of docs/browser.md §7.4).
     *
     * @param reviewId the review request id
     * @param note     why it ended, shown to the reviewer
     * @return true if the request was pending and is expired now
     */
    public boolean expire(String reviewId, String note) {
        ReviewEntry entry = ReviewStore.getInstance().get(reviewId);
        if (entry == null || !TOOL_ID.equals(entry.toolId())) {
            return false;
        }
        boolean moved = ReviewStore.getInstance().transitionIfPending(entry, ReviewStatus.EXPIRED, note);
        if (moved) {
            LOG.info("browser: review request {} expired: {}", entry.id(), note);
        }
        return moved;
    }

    /**
     * The approval window of a request in seconds - the one place the "no window named" default lives,
     * so the countdown in an answer and the countdown in the log line cannot drift apart.
     *
     * @param entry the request
     * @return the window in seconds, never below 1
     */
    private static int waitSecondsOf(ReviewEntry entry) {
        Integer wait = entry.waitSeconds();
        return (wait == null || wait < 1) ? DEFAULT_WAIT_SECONDS : wait;
    }
}
