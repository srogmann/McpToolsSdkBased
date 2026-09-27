package org.rogmann.mcp2sdk.review;

import java.util.List;
import java.util.Map;

/**
 * The review side of a tool: it creates requests, receives the reviewer's decision through the
 * request's {@link DecideConsumer}, and reports what the approved work produced.
 * <p>
 * <b>There is no registration.</b> A tool does not announce itself to the review area; a tool id
 * appears in {@code /review} with the first request that carries it, and a tool that was never
 * called contributes nothing. The review area therefore holds no catalogue of tools that could
 * drift, and the list shows exactly what is there to decide.
 * </p>
 * <p>
 * Most methods have a default implementation, so a simple tool only names itself and hands over
 * attributes. A tool with its own state machine (the Python workflow: approval windows, a reaper,
 * a blocking {@code exec} that waits on a lock) overrides what it must do under its own lock -
 * which is precisely why these are instance methods on the tool and not statics on the review area:
 * the transitions belong to whoever owns the state, the review area provides the page.
 * </p>
 */
public interface ReviewService {

    /**
     * @return the tool id shown in the list ({@code python.exec}); a free name, not registered
     *         anywhere, and stable enough that a reviewer recognizes it the next day
     */
    String getToolId();

    /**
     * A readable name for the reviewer, used in headings, in the grouping of the list and in
     * notifications. Mandatory on purpose: a tool id like {@code python.exec} is precise but is not
     * what a human wants to read between two requests, and a page full of ids reads like a log.
     * Keep it short and in the reviewer's language, e.g. {@code "Python-Ausführung"}.
     *
     * @return the display name of this tool
     */
    String getToolTitle();

    /**
     * @return where this tool keeps its artifacts unless a request says otherwise
     */
    default ReviewPersistenceType defaultPersistence() {
        return ReviewPersistenceType.MEMORY;
    }

    // ========================================================================
    // Creating and submitting a request
    // ========================================================================

    /**
     * Builds a request: allocates the id and, for {@link ReviewPersistenceType#FILESYSTEM}, the run
     * directory - centrally, so a tool never invents ids and only ever <em>adds</em> artifacts to a
     * directory it was given.
     *
     * @param type       where the artifacts live (null = {@link #defaultPersistence()})
     * @param title      one-line subject; for a quick decision it is the only thing read
     * @param attributes the decision-relevant facts, see {@link ReviewAttributes}
     * @param reason     why the request is needed (may be null)
     * @param waitSeconds approval window in seconds, or null for "stays pending until decided"
     * @param consumer   how the decision comes back to the tool
     * @return a request in {@link ReviewStatus#PENDING}, not yet on the list
     */
    default ReviewEntry createRequestEntry(ReviewPersistenceType type, String title,
            List<ReviewAttribute> attributes, String reason, Integer waitSeconds,
            DecideConsumer consumer) {
        ReviewPersistenceType persistence = (type == null) ? defaultPersistence() : type;
        String id = ReviewRequests.newId();
        String runDir = null;
        if (persistence == ReviewPersistenceType.FILESYSTEM) {
            runDir = runDirFor(id);
            ReviewRequests.createRunDir(runDir);
        }
        return new ReviewEntry(id, getToolId(), getToolTitle(), title, reason, attributes,
                persistence, runDir, ReviewRequests.now(), waitSeconds, consumer);
    }

    /**
     * The directory this tool uses for a request's artifacts. Defaults to
     * {@code runs/approvals/<toolId>/<id>}; a tool with an established layout returns its own so
     * that existing run directories and documentation stay valid.
     *
     * @param id the freshly allocated request id
     * @return project-relative directory path
     */
    default String runDirFor(String id) {
        return ReviewRequests.defaultRunDir(getToolId(), id);
    }

    /**
     * Puts the request on the review list. A tool that owns its own bookkeeping registers the
     * request there as well - the <em>same object</em>, never a copy, so there is one status per
     * request (see {@link ReviewEntry}).
     *
     * @param entry the request from {@link #createRequestEntry}
     * @return the entry
     * @throws IllegalArgumentException if the entry belongs to another tool (a request cannot be
     *         submitted under somebody else's name - the reviewer would be sent to the wrong page)
     */
    default ReviewEntry submit(ReviewEntry entry) {
        if (entry == null) {
            throw new IllegalArgumentException("submit() needs a request.");
        }
        if (!entry.toolId().equals(getToolId())) {
            throw new IllegalArgumentException("Request " + entry.id() + " belongs to tool '"
                    + entry.toolId() + "', not to '" + getToolId() + "'.");
        }
        if (entry.waitSeconds() != null && entry.expiresAtMillis() <= 0) {
            // An explicitly requested window is honoured even when nobody is blocking on the
            // request: it must end, otherwise the countdown shown to the caller is a lie.
            entry.armWindow(entry.waitSeconds());
        }
        return ReviewStore.getInstance().add(entry);
    }

    /**
     * Reports the outcome of the approved work - the account a reviewer can read afterwards.
     *
     * @param entry    the request
     * @param response what became of it (failures are data here, see {@link ReviewResponseEntry})
     */
    default void submitResult(ReviewEntry entry, ReviewResponseEntry response) {
        ReviewStore.getInstance().submitResult(entry, response);
    }

    /**
     * Moves a still-pending request of this tool to {@link ReviewStatus#EXPIRED}: the caller that
     * asked can no longer deliver the outcome (its call ended, was cancelled or timed out), so
     * leaving the request open would strand it on the reviewer's page.
     * <p>
     * An unknown id is logged as a warning by the store, not swallowed: after a restart every id is
     * gone, and silently ignoring that would hide the case where an approval was pending and nobody
     * will ever see it. A tool with its own reaper and lock performs the transition there instead -
     * same resulting state, and it also has to wake up a caller that is blocked waiting.
     * </p>
     *
     * @param id the request id
     * @return true if the request was found, belonged to this tool, was still pending and is now
     *         expired
     */
    default boolean expireIfPending(String id) {
        return ReviewStore.getInstance().expireIfPending(id, getToolId());
    }

    /**
     * Re-arms a request whose approval window ran out, so a model does not have to transmit the
     * whole request again - the reviewer sees the same subject with a fresh window.
     * <p>
     * The default does nothing and reports the current status: a tool that has no windows has
     * nothing to re-arm. A tool that supports it (the Python workflow does, with its limit, its
     * renewal log and its "a denial is final" rule) overrides this and keeps its own rules.
     * </p>
     *
     * @param id          the request id
     * @param waitSeconds new window in seconds, null to keep the previous one
     * @param note        why the model asks again (shown to the reviewer)
     * @return the status the request has now
     */
    default ReviewStatus renewPending(String id, Integer waitSeconds, String note) {
        ReviewEntry entry = ReviewStore.getInstance().get(id);
        return (entry == null) ? null : entry.status();
    }

    // ========================================================================
    // Reading (used by the tool and, through the store, by the pages)
    // ========================================================================

    /**
     * @param id the request id
     * @return this tool's request of that id
     * @throws IllegalArgumentException if it does not exist or belongs to another tool
     */
    default ReviewEntry getEntry(String id) {
        ReviewEntry entry = ReviewStore.getInstance().require(id);
        if (!entry.toolId().equals(getToolId())) {
            throw new IllegalArgumentException("Request " + id + " belongs to tool '"
                    + entry.toolId() + "', not to '" + getToolId() + "'.");
        }
        return entry;
    }

    /**
     * @return this tool's requests, newest first
     */
    default List<ReviewEntry> listEntries() {
        return ReviewStore.getInstance().entriesOf(getToolId());
    }

    /**
     * Last chance to add tool-specific sections to the detail page (a diff, a report table).
     * <p>
     * Additive only: the frame that {@link ReviewView#detail(ReviewEntry)} built is passed in and
     * keys may be added, but redefining a frame key would give this tool a page that behaves
     * differently from every other tool's - which is the drift this class exists to prevent.
     * </p>
     *
     * @param id    the request id
     * @param frame the model built by {@link ReviewView#detail(ReviewEntry)} (mutable)
     * @return the model to render
     */
    default Map<String, Object> extendDetailView(String id, Map<String, Object> frame) {
        return frame;
    }
}
