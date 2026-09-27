package org.rogmann.mcp2sdk.review;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One approval request - the object a tool creates, the review list displays and the decision
 * returns to.
 * <p>
 * <b>It is one object, not a copy.</b> This is the property the whole design turns on: a tool keeps
 * its own bookkeeping (its map of running requests, its approval-window reaper, its blocking
 * {@code exec}) and the review list shows <em>this very instance</em>. There is no second store of
 * status to keep in sync - the tool changes {@link #setStatus(ReviewStatus)} and the next render of
 * {@code /review} sees it, because it reads the same field. The moment somebody copies the fields
 * into a "review record", two truths about the status exist, and that is where "the list still says
 * pending, but the run finished an hour ago" comes from.
 * </p>
 * <p>
 * The request data (id, tool, title, attributes, persistence, timestamps) is final; the mutable
 * state is held in atomic types ({@link AtomicReference}, {@link AtomicLong},
 * {@link AtomicInteger}, {@link Boolean}) so that a reader - the review page on another thread -
 * always sees a coherent value per field and never a torn or stale one. Fields that belong together
 * as a <em>pair</em> are changed through {@link #moveFromPending(ReviewStatus, String, String)} or
 * under the tool's own lock: an atomic type makes one field's update visible on its own, so a
 * multi-field transition still needs either a CAS on the status (as here) or a lock, exactly as it
 * did before. What the atomics buy is that a single-field update (a reaper closing one window, a
 * tool recording its result) needs neither.
 * </p>
 * <p>
 * Instances come from {@link ReviewService#createRequestEntry}, which allocates the id and the run
 * directory centrally; the constructor is package-private so a tool cannot invent ids.
 * </p>
 */
public final class ReviewEntry {

    /** Immutable request data as the reviewer sees it. */
    private final String id;
    private final String toolId;
    private final String toolTitle;
    private final String title;
    private final String reason;
    private final List<ReviewAttribute> attributes;
    private final ReviewPersistenceType persistence;
    private final String runDir;
    private final String submittedAt;
    private final Integer waitSeconds;
    private final DecideConsumer decideConsumer;

    /** Current status; transitions happen in the owning tool, atomically or under its lock. */
    private final AtomicReference<ReviewStatus> status =
            new AtomicReference<>(ReviewStatus.PENDING);

    /** Reviewer comment (denial reason) or terminal error detail. */
    private final AtomicReference<String> comment = new AtomicReference<>("");

    /** ISO-8601 timestamp of the decision ({@code ""} while nothing was decided). */
    private final AtomicReference<String> decidedAt = new AtomicReference<>("");

    /** What the tool reported back after the work; {@code null} until then. */
    private final AtomicReference<ReviewResponseEntry> response = new AtomicReference<>();

    /** One-line text that is enough to decide in the list (only with quick decisions enabled). */
    private final AtomicReference<String> shortSummary = new AtomicReference<>("");

    /** Set by the tool when its requests can be decided from the list page. */
    private final AtomicBoolean quickDecision = new AtomicBoolean(false);

    /** Note that came with the last re-arm - why the model asked again. */
    private final AtomicReference<String> renewNote = new AtomicReference<>("");

    /** How often this request was re-armed after an expiry (0 = never). */
    private final AtomicInteger renewCount = new AtomicInteger();

    /**
     * End of the approval window as epoch millis (0 = no window armed). A window is what makes a
     * "pending" honest: the reviewer is told how long they have, and the tool enforces it.
     */
    private final AtomicLong expiresAtMillis = new AtomicLong(0L);

    /**
     * Creates an entry. Use {@link ReviewService#createRequestEntry}; this is only reachable inside
     * the package so that ids and run directories stay centrally allocated.
     *
     * @param id             unique request id
     * @param toolId         tool that asks (free name, appears in the list)
     * @param toolTitle      readable tool name for headings and grouping
     * @param title          one-line subject of the request
     * @param reason         why the request is needed
     * @param attributes     decision-relevant facts
     * @param persistence    where the artifacts live
     * @param runDir         run directory (project-relative) or {@code null} for memory-only
     * @param submittedAt    ISO-8601 submission timestamp
     * @param waitSeconds    approval window in seconds, {@code null} = no window
     * @param decideConsumer the way back to the tool
     */
    ReviewEntry(String id, String toolId, String toolTitle, String title, String reason,
            List<ReviewAttribute> attributes, ReviewPersistenceType persistence, String runDir,
            String submittedAt, Integer waitSeconds, DecideConsumer decideConsumer) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("A review request needs an id.");
        }
        if (toolId == null || toolId.isBlank()) {
            throw new IllegalArgumentException("A review request needs a tool id.");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("A review request needs a title - the reviewer reads"
                    + " it first and, for a quick decision, reads only it.");
        }
        if (decideConsumer == null) {
            throw new IllegalArgumentException("A review request needs a DecideConsumer: without a"
                    + " way back to the tool the reviewer's decision would go nowhere.");
        }
        this.id = id.trim();
        this.toolId = toolId.trim();
        // The page renders requests without holding the service that created them, so the readable
        // name has to travel with the request. Falling back to the id keeps an older or hand-built
        // entry displayable instead of blank.
        this.toolTitle = ReviewAttributes.sanitize(
                (toolTitle == null || toolTitle.isBlank()) ? toolId : toolTitle,
                ReviewAttributes.MAX_LABEL_CHARS);
        this.title = ReviewAttributes.sanitize(title, ReviewAttributes.MAX_TITLE_CHARS);
        this.reason = ReviewAttributes.sanitize(reason, ReviewAttributes.MAX_REASON_CHARS);
        this.attributes = ReviewAttributes.normalize(attributes);
        this.persistence = (persistence == null) ? ReviewPersistenceType.MEMORY : persistence;
        this.runDir = (runDir == null) ? null : runDir.trim();
        this.submittedAt = (submittedAt == null) ? "" : submittedAt.trim();
        this.waitSeconds = waitSeconds;
        this.decideConsumer = decideConsumer;
    }

    /**
     * Creates an entry for an id the tool has already allocated itself.
     * <p>
     * Normally an id comes from {@link ReviewRequests#newId()} through
     * {@link ReviewService#createRequestEntry}. A tool with its own id scheme and its own run
     * directory layout - the Python workflow uses {@code runs/python-exec/<id>/} and a second-
     * accurate id that appears in its documentation, its artefacts and its JS result - must keep
     * both, otherwise an unrelated refactoring would move files that an LLM has been told about.
     * This factory is the documented way to join such a tool to the shared list: the id, the
     * timestamp and the run directory are taken as given, everything else is validated and
     * normalized exactly as in the normal constructor.
     * </p>
     *
     * @param id             the tool's own request id (must be unique across tools)
     * @param toolId         tool that asks
     * @param toolTitle      readable tool name
     * @param title          one-line subject
     * @param reason         why the request is needed
     * @param attributes     decision-relevant facts
     * @param persistence    where the artifacts live
     * @param runDir         project-relative run directory, or {@code null}
     * @param submittedAt    the tool's own submission timestamp
     * @param waitSeconds    approval window in seconds, {@code null} = no window
     * @param consumer       the way back to the tool
     * @return the entry
     */
    public static ReviewEntry withId(String id, String toolId, String toolTitle, String title,
            String reason, List<ReviewAttribute> attributes, ReviewPersistenceType persistence,
            String runDir, String submittedAt, Integer waitSeconds, DecideConsumer consumer) {
        return new ReviewEntry(id, toolId, toolTitle, title, reason, attributes, persistence,
                runDir, submittedAt, waitSeconds, consumer);
    }

    // ========================================================================
    // Request data (immutable)
    // ========================================================================

    /**
     * @return the unique id (also the URL path under {@code /review/<id>})
     */
    public String id() {
        return id;
    }

    /**
     * @return the free tool name, e.g. {@code python.exec}
     */
    public String toolId() {
        return toolId;
    }

    /**
     * @return the readable tool name ({@code "Python-Ausführung"}), for headings and grouping
     */
    public String toolTitle() {
        return toolTitle;
    }

    /**
     * @return the one-line subject
     */
    public String title() {
        return title;
    }

    /**
     * @return the justification shown to the reviewer, may be empty
     */
    public String reason() {
        return reason;
    }

    /**
     * @return the decision-relevant facts, in the order the tool declared
     */
    public List<ReviewAttribute> attributes() {
        return attributes;
    }

    /**
     * @return where the artifacts of this request live
     */
    public ReviewPersistenceType persistence() {
        return persistence;
    }

    /**
     * @return project-relative run directory, or {@code null} for a memory-only request
     */
    public String runDir() {
        return runDir;
    }

    /**
     * @return ISO-8601 submission timestamp
     */
    public String submittedAt() {
        return submittedAt;
    }

    /**
     * @return the requested approval window in seconds, {@code null} when none was requested
     */
    public Integer waitSeconds() {
        return waitSeconds;
    }

    /**
     * @return the callback into the tool; never {@code null}, never rendered, never serialized
     */
    public DecideConsumer decideConsumer() {
        return decideConsumer;
    }

    // ========================================================================
    // Quick decision
    // ========================================================================

    /**
     * Declares that this request can be decided from the list page, and supplies the one line a
     * reviewer needs to do so. Off by default: the safe way round is that a tool opts in because it
     * knows its own requests are simple (no source code to read, see
     * {@link AttributeKind#CODE}), not that the review area guesses.
     *
     * @param summary one line, e.g. {@code "check_logs.py, 2 write targets, 3 expectations"}
     * @return this entry, for chaining
     */
    public ReviewEntry enableQuickDecision(String summary) {
        String text = ReviewAttributes.sanitize(summary, ReviewAttributes.MAX_TITLE_CHARS);
        if (text.isEmpty()) {
            throw new IllegalArgumentException("A request that can be decided from the list must"
                    + " supply the one-line summary the decision is based on.");
        }
        this.shortSummary.set(text);
        this.quickDecision.set(true);
        return this;
    }

    /**
     * @return true if the tool offered a quick decision (still subject to
     *         {@link ReviewView#allowsQuickDecision(ReviewEntry)})
     */
    public boolean quickDecision() {
        return quickDecision.get();
    }

    /**
     * @return the one-line summary for the list page, may be empty
     */
    public String shortSummary() {
        return shortSummary.get();
    }

    // ========================================================================
    // State (changed by the owning tool, read by the review UI)
    // ========================================================================

    /**
     * @return the current status, never {@code null}
     */
    public ReviewStatus status() {
        return status.get();
    }

    /**
     * Assigns the status. A transition that also changes other fields of this entry belongs in the
     * tool's lock, or goes through {@link #moveFromPending(ReviewStatus, String, String)}.
     *
     * @param newStatus the new status
     */
    public void setStatus(ReviewStatus newStatus) {
        if (newStatus == null) {
            throw new IllegalArgumentException("A review request cannot be without a status.");
        }
        status.set(newStatus);
    }

    /**
     * Moves the request out of {@link ReviewStatus#PENDING} in one atomic step - to whatever state
     * the caller has decided on: {@link ReviewStatus#APPROVED} or {@link ReviewStatus#DENIED} from
     * the reviewer, {@link ReviewStatus#EXPIRED} from an approval window that ran out.
     * <p>
     * This is the transition every tool needs and the one that races: the reviewer clicks approve at
     * the same moment the window reaper decides the request expired. The compare-and-set settles it
     * - one of the two wins, the other is told so and changes nothing. That is the whole promise, so
     * the target is deliberately not restricted to final states: approving moves a request into
     * {@link ReviewStatus#APPROVED}, which is open by {@link ReviewStatus#open()} and must still be
     * allowed here.
     * </p>
     * <p>
     * A caller that has to wake up a blocked waiter (a blocking {@code exec}) performs this inside
     * its own lock, so the state change and the {@code notifyAll()} cannot be interleaved.
     * </p>
     * <p>
     * The accompanying comment and timestamp are written after the status changed, so a reader can
     * see the new status together with the previous comment for a few instructions. That is the
     * same window a lock-free read always had; a reviewer re-rendering the page sees the pair.
     * </p>
     *
     * @param target    the final status to move to, e.g. {@link ReviewStatus#EXPIRED}
     * @param comment   what to tell the reviewer about the reason
     * @param decidedAt ISO-8601 timestamp of the change
     * @return true if this call moved the request (it was pending)
     */
    public boolean moveFromPending(ReviewStatus target, String comment, String decidedAt) {
        if (target == null) {
            throw new IllegalArgumentException("moveFromPending needs the status to move to.");
        }
        if (target == ReviewStatus.PENDING) {
            throw new IllegalArgumentException("moveFromPending cannot move a request from pending"
                    + " to pending.");
        }
        if (!status.compareAndSet(ReviewStatus.PENDING, target)) {
            return false;
        }
        this.comment.set(ReviewAttributes.sanitize(comment, ReviewAttributes.MAX_REASON_CHARS));
        this.decidedAt.set((decidedAt == null) ? ReviewRequests.now() : decidedAt.trim());
        expiresAtMillis.set(0L);
        return true;
    }

    /**
     * @return reviewer comment or terminal error detail, may be empty
     */
    public String comment() {
        return comment.get();
    }

    /**
     * @param newComment reviewer comment or error detail, may be null
     */
    public void setComment(String newComment) {
        comment.set(ReviewAttributes.sanitize(newComment, ReviewAttributes.MAX_REASON_CHARS));
    }

    /**
     * @return ISO-8601 timestamp of the decision, {@code ""} while nothing was decided
     */
    public String decidedAt() {
        return decidedAt.get();
    }

    /**
     * @param newTimestamp ISO-8601 timestamp of the decision
     */
    public void setDecidedAt(String newTimestamp) {
        decidedAt.set((newTimestamp == null) ? "" : newTimestamp.trim());
    }

    /**
     * @return what the tool reported back, {@code null} while the work is (still) pending
     */
    public ReviewResponseEntry response() {
        return response.get();
    }

    /**
     * Records the result of the approved work.
     *
     * @param newResponse the response entry
     */
    public void setResponse(ReviewResponseEntry newResponse) {
        response.set(newResponse);
    }

    /**
     * Sets the result only if none was recorded yet - the guard that keeps a late retry from
     * rewriting the history a reviewer approved.
     *
     * @param newResponse what the work produced
     * @return true if this call recorded the response
     */
    public boolean setResponseIfAbsent(ReviewResponseEntry newResponse) {
        return response.compareAndSet(null, newResponse);
    }

    /**
     * @return epoch millis at which the approval window closes, 0 when no window is armed
     */
    public long expiresAtMillis() {
        return expiresAtMillis.get();
    }

    /**
     * Arms or disarms the approval window. Enforcing it (and expiring the request when it runs out)
     * is the tool's job - only the tool can do it together with waking up a caller that is blocked
     * waiting for the decision.
     *
     * @param epochMillis end of the window, 0 to disarm
     */
    public void setExpiresAtMillis(long epochMillis) {
        this.expiresAtMillis.set(Math.max(0L, epochMillis));
    }

    /**
     * Convenience: arms the window for the given number of seconds from now.
     *
     * @param seconds length of the window; values below 1 disarm it
     */
    public void armWindow(int seconds) {
        this.expiresAtMillis.set(seconds < 1
                ? 0L
                : System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds));
    }

    /**
     * @return whole seconds left in the approval window, rounded up; 0 when there is no window
     */
    public long remainingSeconds() {
        long deadline = expiresAtMillis.get();
        if (deadline <= 0) {
            return 0L;
        }
        long leftMillis = deadline - System.currentTimeMillis();
        if (leftMillis <= 0) {
            return 0L;
        }
        return TimeUnit.MILLISECONDS.toSeconds(leftMillis - 1) + 1;
    }

    /**
     * @return number of re-arms after an expiry (0 = never)
     */
    public int renewCount() {
        return renewCount.get();
    }

    /**
     * @return note of the last re-arm, may be empty
     */
    public String renewNote() {
        return renewNote.get();
    }

    /**
     * Records a re-arm so the reviewer can see that the model asked again - nothing about the
     * approved request changed, but being asked twice is information.
     *
     * @param count number of the re-arm (1 for the first)
     * @param note  why the model re-armed it, may be null
     */
    public void recordRenewal(int count, String note) {
        this.renewCount.set(Math.max(0, count));
        this.renewNote.set(ReviewAttributes.sanitize(note, ReviewAttributes.MAX_REASON_CHARS));
    }

    // ========================================================================
    // Convenience
    // ========================================================================

    /**
     * @return true while the request may still be decided or is being worked on
     */
    public boolean open() {
        return status().open();
    }

    /**
     * @return true when the request waits for a human decision
     */
    public boolean pending() {
        return status() == ReviewStatus.PENDING;
    }

    /**
     * Looks up one attribute by key.
     *
     * @param key the machine-readable key
     * @return the attribute, or {@code null} when the tool did not declare it
     */
    public ReviewAttribute attribute(String key) {
        for (ReviewAttribute attribute : attributes) {
            if (attribute.key().equals(key)) {
                return attribute;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "ReviewEntry[" + id + ", " + toolId + ", " + status().display() + "]";
    }
}
