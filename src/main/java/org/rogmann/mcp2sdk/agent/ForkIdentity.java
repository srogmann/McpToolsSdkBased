package org.rogmann.mcp2sdk.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Who is speaking: the map {@code MCP session -> thread of a running fork} (docs/subagent.md §14,
 * P0.2).
 *
 * <p><b>Why a registry instead of an argument.</b> The {@code agent_board} tool used to take its
 * caller from the argument the runner injects ({@code _fork}); that works while the caller is a
 * model filling a schema, and it stops working the moment the caller is a script - the argument of
 * a JavaScript call <em>is</em> the script, so an identity passed that way is one the thread could
 * write itself. What cannot be forged is what arrives from outside: the HTTP header the child's MCP
 * client carries on every request (set by {@code SubAgentRunner}, P0.1) and the session the server
 * handed out for exactly that client. A filter in front of {@code /mcp/*} pairs the two; everything
 * downstream reads the pairing and never asks the caller again.</p>
 *
 * <p><b>Bootstrap.</b> The session id is born in the response to {@code initialize()}, so the
 * initialization request itself maps nothing - which is fine, no tool call happens before it.</p>
 *
 * <p><b>Cleanup.</b> A batch removes its entries when it ends; on top of that every entry expires
 * (a registry whose only cleanup rule is a happy path is a memory leak that looks tidy). The
 * singleton is static like {@code ReviewStore}: the filter is Spring-managed, the tools are not,
 * and the map has to be the same object for both.</p>
 */
public final class ForkIdentity {

    private static final Logger LOG = LoggerFactory.getLogger(ForkIdentity.class);

    /**
     * Request header a fork thread puts on every MCP request: {@code batchId/taskId/childIndex}.
     * A model cannot set HTTP headers - the child's transport does, once, at build time.
     */
    public static final String HEADER = "X-Fork-Child";

    /** Entries older than this are dropped on the next registration (a batch that never ended). */
    private static final long MAX_AGE_NANOS = TimeUnit.MINUTES.toNanos(30);

    /** The one registry: the filter writes, the tools read. */
    private static final ForkIdentity INSTANCE = new ForkIdentity();

    /** @return the shared registry */
    public static ForkIdentity getInstance() {
        return INSTANCE;
    }

    /**
     * The address of a thread, as the header names it.
     *
     * @param batchId    batch of the fork this thread belongs to
     * @param taskId     task id within the batch ({@code t1}, ...)
     * @param childIndex 1-based position in the task list, the number the plain tail carries
     */
    public record Entry(String batchId, String taskId, int childIndex) {

        /** @return {@code batch/task/child}, the form the header and the logs use */
        public String tag() {
            return batchId + "/" + taskId + "/" + childIndex;
        }

        /** @return {@code batch/task}, the form a board entry uses as author */
        public String label() {
            return batchId + "/" + taskId;
        }
    }

    /** An entry plus the moment it was seen, so expiry can tell a live batch from a leftover. */
    private record Stamped(Entry entry, long atNano) {
    }

    private final Map<String, Stamped> bySession = new ConcurrentHashMap<>();

    private ForkIdentity() {
        // singleton
    }

    /**
     * Records what one request said about itself. Both headers must be there: a request without a
     * session (the {@code initialize()} of a child) or without the fork header (every client that is
     * not a fork thread) maps nothing - the latter is the normal case and logs nothing.
     *
     * @param sessionId value of the MCP session header, may be null
     * @param header    value of {@value #HEADER}, may be null
     */
    public void register(String sessionId, String header) {
        if (sessionId == null || sessionId.isBlank() || header == null || header.isBlank()) {
            return;
        }
        Entry parsed = parseTag(header);
        if (parsed == null) {
            LOG.warn("Ignoring malformed '{}' header '{}' (expected batch/task/child)", HEADER, header);
            return;
        }
        pruneExpired();
        Stamped previous = bySession.put(sessionId, new Stamped(parsed, System.nanoTime()));
        if (previous == null || !previous.entry().equals(parsed)) {
            LOG.info("Fork identity: session {} speaks for {} ({})", sessionId, parsed.label(), parsed.tag());
        }
    }

    /**
     * @param sessionId MCP session of the call, may be null
     * @return the thread that call belongs to, or {@code null} for anyone else (never a guess)
     */
    public Entry lookup(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        Stamped stamped = bySession.get(sessionId);
        if (stamped == null) {
            return null;
        }
        if (System.nanoTime() - stamped.atNano() > MAX_AGE_NANOS) {
            bySession.remove(sessionId, stamped);
            return null;
        }
        return stamped.entry();
    }

    /**
     * Drops every entry of a batch. Called when the batch ends; a child's MCP session dies with it,
     * so nothing here outlives the run it describes.
     *
     * @param batchId batch that ended
     * @return how many entries were dropped
     */
    public int removeBatch(String batchId) {
        if (batchId == null) {
            return 0;
        }
        int before = bySession.size();
        bySession.entrySet().removeIf(e -> batchId.equals(e.getValue().entry().batchId()));
        int dropped = before - bySession.size();
        if (dropped > 0) {
            LOG.info("Fork identity: {} session(s) of batch {} released", dropped, batchId);
        }
        return dropped;
    }

    /** @return the number of live mappings (a test's and a log line's view of the registry) */
    public int size() {
        return bySession.size();
    }

    /** Empties the registry (tests; production ends a batch with {@link #removeBatch(String)}). */
    public void clear() {
        bySession.clear();
    }

    /**
     * @param tag value of {@value #HEADER}, e.g. {@code b-7f3c/t2/2}
     * @return the parsed address, or {@code null} if the text is not one (the child index may be
     *         missing, the batch and the task are not)
     */
    public static Entry parseTag(String tag) {
        if (tag == null) {
            return null;
        }
        String[] parts = tag.trim().split("/");
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return null;
        }
        int childIndex = 0;
        if (parts.length >= 3) {
            try {
                childIndex = Integer.parseInt(parts[2].trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return new Entry(parts[0].trim(), parts[1].trim(), childIndex);
    }

    /** Drops entries nobody refreshed; cheap, the registry holds one entry per running thread. */
    private void pruneExpired() {
        long now = System.nanoTime();
        bySession.entrySet().removeIf(e -> now - e.getValue().atNano() > MAX_AGE_NANOS);
    }
}
