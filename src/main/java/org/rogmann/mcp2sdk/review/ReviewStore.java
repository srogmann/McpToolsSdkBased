package org.rogmann.mcp2sdk.review;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The single list behind {@code /review}: the requests of every tool, in one place.
 * <p>
 * <b>It stores references, not snapshots.</b> A tool creates its request with
 * {@link ReviewService#createRequestEntry} and hands that very object to {@link #add(ReviewEntry)}
 * while keeping it in its own bookkeeping. There is exactly one status field per request, so a tool
 * that expires a request in its window reaper does not "update the review UI" - the review UI reads
 * the same field and simply shows the new state on its next render. This is the property that keeps
 * the page from ever claiming "pending" for a run that finished; a copied record would need
 * synchronization with a second truth, and that is a bug that only appears in production.
 * </p>
 * <p>
 * <b>No tool registration.</b> Nothing announces itself here. A tool id appears in the list with the
 * first request that carries it, and a tool that was never called contributes nothing. That keeps
 * the review area free of a tool catalogue it would only ever have to keep from drifting.
 * </p>
 * <p>
 * Requests live in memory only: a restart clears them (as it does today). Nothing is deleted while
 * the process runs, because the request and its artifacts are the history of an approval - what is
 * hidden from the page is decided by {@link ReviewView#visibleEntries(List)}, not by dropping data.
 * </p>
 * <p>
 * Singleton for the same reason {@code PythonExecReviewService} is one: the callers are JS module
 * bridges and tool services that are not Spring-managed, so this is reachable without a
 * {@code ApplicationContext} (unit tests included). {@code McpConfig} publishes it as a bean so that
 * the controller receives it by injection instead of reaching for a static.
 * </p>
 */
public final class ReviewStore {

    private static final Logger LOG = LoggerFactory.getLogger(ReviewStore.class);

    /** The one instance (see class javadoc). */
    private static final ReviewStore INSTANCE = new ReviewStore();

    /** Requests by id. */
    private final Map<String, ReviewEntry> entries = new ConcurrentHashMap<>();

    private ReviewStore() {
        // Singleton
    }

    /**
     * @return the shared list
     */
    public static ReviewStore getInstance() {
        return INSTANCE;
    }

    // ========================================================================
    // Submission
    // ========================================================================

    /**
     * Puts a request on the list.
     *
     * @param entry the request as created by {@link ReviewService#createRequestEntry}
     * @return the entry, for chaining
     * @throws IllegalArgumentException if the id is already taken (it must not be, and silently
     *         replacing another tool's request would hide a request from its reviewer)
     */
    public ReviewEntry add(ReviewEntry entry) {
        if (entry == null) {
            throw new IllegalArgumentException("Cannot add a null review entry.");
        }
        ReviewEntry previous = entries.putIfAbsent(entry.id(), entry);
        if (previous != null) {
            throw new IllegalArgumentException("A request with id '" + entry.id() + "' is already"
                    + " on the review list (tool: " + previous.toolId() + ", this one: "
                    + entry.toolId() + "). Ids are allocated by ReviewRequests.newId() -"
                    + " submitting twice with the same id would replace a request a reviewer"
                    + " may already be looking at.");
        }
        LOG.info("Review request submitted: {} (tool {}, title: {}, {} attributes, persistence {})",
                entry.id(), entry.toolId(), entry.title(), entry.attributes().size(),
                entry.persistence());
        return entry;
    }

    /**
     * Looks a request up by id.
     *
     * @param id the request id
     * @return the entry, or {@code null} when unknown (a stale link, a request cleared by a restart)
     */
    public ReviewEntry get(String id) {
        return (id == null) ? null : entries.get(id.trim());
    }

    /**
     * All requests, newest first. Ids carry their creation timestamp
     * ({@code yyyyMMdd'T'HHmmss-<random>}), so ordering by id is ordering by time and is stable
     * across concurrent submissions - the same rule the Python list uses today.
     *
     * @return an immutable snapshot of the current entries
     */
    public List<ReviewEntry> entries() {
        List<ReviewEntry> all = new ArrayList<>(entries.values());
        all.sort(Comparator.comparing(ReviewEntry::id).reversed());
        return List.copyOf(all);
    }

    /**
     * The requests of one tool, newest first.
     *
     * @param toolId the tool id to filter by
     * @return an immutable snapshot
     */
    public List<ReviewEntry> entriesOf(String toolId) {
        List<ReviewEntry> mine = new ArrayList<>();
        for (ReviewEntry entry : entries.values()) {
            if (entry.toolId().equals(toolId)) {
                mine.add(entry);
            }
        }
        mine.sort(Comparator.comparing(ReviewEntry::id).reversed());
        return List.copyOf(mine);
    }

    /**
     * @return number of requests on the list (all tools, including finished ones)
     */
    public int size() {
        return entries.size();
    }

    /**
     * Removes every request. Only for tests - production has no purge: the list is the memory of
     * this process, a restart is the purge.
     */
    public void clear() {
        entries.clear();
    }

    // ========================================================================
    // Guarded state changes shared by every tool
    // ========================================================================

    /**
     * Reports the outcome of the approved work.
     * <p>
     * The first report wins: {@link ReviewEntry#setResponseIfAbsent(ReviewResponseEntry)} is a
     * compare-and-set, so a late retry is logged and ignored instead of quietly rewriting the
     * history a reviewer approved.
     * </p>
     * <p>
     * A tool that owns a lock and a wait/notify protocol (the Python workflow does, because a
     * blocking {@code exec} may be sitting on the transition) performs the change itself inside that
     * lock, using the same two primitives - {@code setResponseIfAbsent} and
     * {@code setStatus} - and then wakes its waiter. This is the convenience path for everything
     * else, and both paths end in the same state.
     * </p>
     *
     * @param entry    the request
     * @param response what the work produced
     * @return true if this call recorded the response
     */
    public boolean submitResult(ReviewEntry entry, ReviewResponseEntry response) {
        if (entry == null || response == null) {
            throw new IllegalArgumentException("submitResult needs the request and its response.");
        }
        if (!entry.setResponseIfAbsent(response)) {
            LOG.warn("Review request {} already reported a result ({}); the second report"
                            + " ({}, ok={}) is ignored - a request has one result.",
                    entry.id(), entry.response().status().display(),
                    response.status().display(), response.ok());
            return false;
        }
        entry.setStatus(response.status());
        entry.setExpiresAtMillis(0L);
        LOG.info("Review request {} reported {}: {}{}", entry.id(), response.status().display(),
                response.summary(), response.ok() ? "" : " (FAILED)");
        return true;
    }

    /**
     * Moves a still-pending request to a final status, guarded against a decision that arrives at
     * the same moment.
     * <p>
     * This is the "nobody is waiting anymore" path: the approval window ran out, or the tool call
     * that asked has ended and can no longer deliver the outcome. A tool with its own lock and
     * reaper performs the transition there instead (it also has to wake up a blocked caller); both
     * paths end in the same state.
     * </p>
     *
     * @param entry   the request
     * @param toFinal the final status to move to (typically {@link ReviewStatus#EXPIRED})
     * @param comment what to tell the reviewer about the reason
     * @return true if this call moved the request (it was pending)
     */
    public boolean transitionIfPending(ReviewEntry entry, ReviewStatus toFinal, String comment) {
        if (entry == null || toFinal == null) {
            throw new IllegalArgumentException("A guarded transition needs the request and a status.");
        }
        if (toFinal.open()) {
            throw new IllegalArgumentException("transitionIfPending moves a request out of"
                    + " pending, not into an open state (" + toFinal.display() + ").");
        }
        // One compare-and-set decides who wins: the reviewer who approved a moment ago, or this
        // call that found nobody had decided. The loser changes nothing and is told so.
        if (!entry.moveFromPending(toFinal, comment, ReviewRequests.now())) {
            LOG.debug("Review request {} was already {} - the guarded transition to {} is skipped",
                    entry.id(), entry.status().display(), toFinal.display());
            return false;
        }
        LOG.info("Review request {} is now {}: {}", entry.id(), toFinal.display(),
                (comment == null || comment.isEmpty()) ? "(no note)" : comment);
        return true;
    }

    /**
     * Expires a still-pending request because the caller that asked can no longer deliver the
     * outcome (its call ended, was cancelled or timed out) - leaving it open would strand it on the
     * reviewer's page forever.
     * <p>
     * An id that is not on the list is <b>logged as a warning</b>, not ignored and not an error:
     * after a server restart every id is gone, and a tool that quietly forgets about it would hide
     * exactly the case where an approval was pending and nobody will ever see it again. The return
     * value lets the caller tell "expired" from "was not there" and from "somebody had already
     * decided".
     * </p>
     *
     * @param id     the request id
     * @param toolId the tool asking; a request of another tool is left alone
     * @return true if the request was found here, belonged to this tool, was still pending and is
     *         now {@link ReviewStatus#EXPIRED}
     */
    public boolean expireIfPending(String id, String toolId) {
        ReviewEntry entry = get(id);
        if (entry == null) {
            LOG.warn("Cannot expire review request '{}': it is not on the review list"
                    + " (unknown id, or cleared by a server restart while a request was pending)", id);
            return false;
        }
        if (!entry.toolId().equals(toolId)) {
            LOG.warn("Refusing to expire review request {}: it belongs to tool '{}', not to '{}'",
                    id, entry.toolId(), toolId);
            return false;
        }
        return transitionIfPending(entry, ReviewStatus.EXPIRED,
                "expired: the request ended before anyone decided");
    }

    /**
     * Finds the request of a caller that only knows its id, throwing when it is gone.
     *
     * @param id the request id
     * @return the entry, never null
     * @throws IllegalArgumentException if the id is unknown to the review area
     */
    public ReviewEntry require(String id) {
        ReviewEntry entry = get(id);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown review request id: " + id
                    + " (requests live in memory and are cleared by a server restart).");
        }
        return entry;
    }
}
