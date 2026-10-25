package org.rogmann.mcp2sdk.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The board as one fork thread sees it, in plain Java: no Graal, no Jackson, no MCP
 * (docs/subagent.md §14, P1).
 *
 * <p>This is the seam between the two surfaces. {@code AgentBoardTool} answers a model and
 * {@code JsAgentBoardBridge} answers a script, and both come through here or through the board
 * methods this class calls - the same monitor, the same ceilings, the same stage rules, the same
 * renderer. A rule added on this side would be a rule the other side does not have, and the
 * comparison M4 (tool vs. script on one board) would measure two boards.</p>
 *
 * <p><b>Refusals are exceptions.</b> The MCP surface answers "refused" as JSON because that is what
 * a schema-shaped tool result can carry; a script has {@code try/catch}, so every refusal here is a
 * {@link RuntimeException} whose message says what to do instead. The board itself already throws -
 * this class only adds the identity check in front and the envelope behind.</p>
 *
 * <p>Obtained per call: {@link #open(ForkCallers.Caller)} for the caller {@link ForkCallers}
 * carries. There is no instance worth keeping beyond the call.</p>
 */
public final class AgentBoardAccess {

    /**
     * System property that lets the JavaScript surface exist and be spoken of at all
     * (docs/subagent.md §14, decision D3). Off by default, like {@code mcp.js.mcpServer.url}: a
     * surface nobody switched on gets no word in any description.
     */
    public static final String PROP_JS_SURFACE = "agent.fork.board.js";

    /** @return true when the JavaScript surface is switched on (per call, like every property here) */
    public static boolean isJsSurfaceOn() {
        return Boolean.getBoolean(PROP_JS_SURFACE);
    }

    private final AgentBoard board;
    private final AgentId author;
    /** Ceiling of the single tool call the script runs in, 0 when nobody said one. */
    private final long scriptDeadlineNano;

    private AgentBoardAccess(AgentBoard board, AgentId author, long scriptDeadlineNano) {
        this.board = board;
        this.author = author;
        this.scriptDeadlineNano = scriptDeadlineNano;
    }

    /** @return true when the current thread belongs to a fork (a module's {@code isEnabled()}) */
    public static boolean isThreadOfAFork() {
        return ForkCallers.current() != null;
    }

    /**
     * Opens the board of the batch the caller belongs to.
     *
     * @param caller the thread that asks, {@link ForkCallers#current()}, may be null
     * @return access to that thread's board
     * @throws IllegalStateException when the caller is not a fork thread, or its batch has no live
     *         board (no {@code board:true}, or the batch already ended) - the same refusal the MCP
     *         tool answers with, in exception form
     */
    public static AgentBoardAccess open(ForkCallers.Caller caller) {
        if (caller == null) {
            throw new IllegalStateException("no board for this call: it does not come from a thread"
                    + " of a running fork. The board belongs to a prefix_fork batch whose threads"
                    + " carry it; an ordinary conversation has none.");
        }
        AgentBoard board = AgentBoard.live(caller.batchId());
        if (board == null) {
            throw new IllegalStateException("no live board for batch '" + caller.batchId() + "':"
                    + " the fork that started it did not set board:true, or the batch has already"
                    + " ended and the board is frozen (the caller reads it in the fork result).");
        }
        // The caller cannot be the fork's caller: that session never carries the fork header, so it
        // is not in ForkIdentity and never reaches this line. The board's own rule stands regardless.
        return new AgentBoardAccess(board, new AgentId(caller.batchId(), caller.taskId(),
                caller.childIndex()), caller.deadlineNano());
    }

    /** @return {@code batch/task/child} of the thread holding this access */
    public String who() {
        return author.tag();
    }

    /** @return the batch this board belongs to */
    public String batchId() {
        return board.batchId();
    }

    /** @return the ceiling of one text field, as the board enforces it */
    public static int textCeiling() {
        return AgentBoard.textCeiling();
    }

    /** @return milliseconds the thread has left - its own deadline and this call's, whichever ends first */
    public long remainingMillis() {
        long threadLeft = board.remainingMillisOf(author);
        long callLeft = Long.MAX_VALUE;
        if (scriptDeadlineNano > 0L) {
            long nanosLeft = scriptDeadlineNano - System.nanoTime();
            callLeft = (nanosLeft <= 0L) ? 0L : (nanosLeft / 1_000_000L);
        }
        return Math.min(threadLeft, callLeft);
    }

    /**
     * Puts an entry on the board.
     *
     * @param subject what it is about, one sentence
     * @param kind    {@code "TASK"} (proposed work, the default) or {@code "INFO"} (announcement)
     * @return the counts of the board plus the new entry
     */
    public Map<String, Object> post(String subject, String kind) {
        return answer("posted", board.post(author, kind, subject));
    }

    /**
     * Adds a line under an entry (a comment is a message on the entry, not an entry - decision B1).
     */
    public Map<String, Object> comment(String taskId, String text) {
        return answer("commented", board.comment(author, taskId, text));
    }

    /** @return the whole board, every entry with its thread; the read marks contact */
    public Map<String, Object> list() {
        board.noteRead(author);
        return board.jsView();
    }

    /**
     * @param taskId entry id ({@code m1}, {@code m2}, ...)
     * @return the counts plus that one entry with its thread
     */
    public Map<String, Object> get(String taskId) {
        return board.jsGet(author, taskId);
    }

    /** @return the entry seeded from this thread's own task, thread included */
    public Map<String, Object> mine() {
        AgentTask own = board.seededFor(author.taskId());
        if (own == null) {
            throw new IllegalStateException("nothing on this board is seeded from '" + author.taskId()
                    + "'; the caller seeded one entry per task of the batch - read board.list()");
        }
        Map<String, Object> root = board.counts();
        root.put("entry", board.entryWithThread(own));
        return root;
    }

    /** @return the entry after the claim (stage 1 refuses, stage 2 needs the own task ended) */
    public Map<String, Object> claim(String taskId) {
        return answer("claimed", board.claim(author, taskId));
    }

    /** @return the entry after the close; INFO entries are not closeable */
    public Map<String, Object> close(String taskId, String outcome) {
        return answer("closed", board.close(author, taskId, outcome));
    }

    /**
     * Sleeps inside this call until the entry changes - the answer to "polling costs a model turn".
     *
     * @param taskId      the entry to watch
     * @param timeoutMs   how long at most, or {@code null} for the default
     * @return the entry, plus {@code changed} and {@code waitedMs}
     */
    public Map<String, Object> waitFor(String taskId, Long timeoutMs) {
        long left = remainingMillis();
        long requested = (timeoutMs == null)
                ? Math.min(AgentBoard.DEFAULT_WAIT_MS, left)
                : timeoutMs;
        if (requested > left) {
            throw new IllegalStateException("a wait of " + requested + " ms does not fit: "
                    + describe(left) + " left on this thread. Wait for less, or answer from what"
                    + " the board already holds.");
        }
        AgentBoard.Awaited awaited = board.awaitMessages(author, taskId, requested);
        Map<String, Object> root = board.counts();
        root.put("ok", true);
        root.put("action", "waited");
        root.put("changed", awaited.changed());
        root.put("timedOut", !awaited.changed());
        root.put("waitedMs", awaited.waitedMillis());
        root.put("entry", board.entryWithThread(awaited.task()));
        return root;
    }

    /** @return what the module is, for the thread holding this access */
    public String help() {
        return helpOf(ForkCallers.current());
    }

    /**
     * The module's help text - three sentences, the shape every module's help has.
     *
     * @param caller the thread asking, may be null (a script outside a fork reads it as a promise)
     * @return the help text
     */
    public static String helpOf(ForkCallers.Caller caller) {
        return """
                The message board of the running fork batch (docs/subagent.md). You are %s on it - \
                the identity comes from the session this call arrives on, not from anything you write.
                board.list() the whole board | board.get(taskId) one entry with its thread | \
                board.mine() the entry seeded from your own task. In those answers `entries` is the \
                list; in the answers of the writing calls it is the count and `entry` is the entry \
                they touched.
                board.post(subject[, {kind: 'INFO'}]) puts an entry on the board - a finding that is \
                not yours to act on belongs here, not in an edit. board.comment(taskId, text) adds a \
                line under an entry. board.close(taskId[, outcome]) ends your own. board.claim(taskId) \
                is refused in stage 1: the caller decides after the batch what becomes work.
                board.wait(taskId[, {timeoutMs: 20000}]) sleeps inside this call until the entry \
                changes - a glance costs nothing here, so do not poll in a loop. Refusals are thrown: \
                try { board.close('m3'); } catch (e) { console.log(e.message); }.
                Ceilings: %d entries per board, 8 messages per entry, %d characters per text; the \
                ceiling of a wait is the time this thread has left. The caller reads the board after \
                the batch - a sibling reads it now. See docs/js/board.md for the whole surface."""
                .formatted((caller == null) ? "not a thread of a fork here" : caller.tag(),
                        AgentBoard.MAX_ENTRIES, AgentBoard.textCeiling());
    }

    // --- internals ---

    /** The envelope both surfaces answer a write with: the counts, the verb, the entry. */
    private Map<String, Object> answer(String verb, AgentTask task) {
        Map<String, Object> root = board.counts();
        root.put("ok", true);
        root.put("action", verb);
        root.put("entry", board.entryView(task, AgentBoard.textCeiling()));
        return root;
    }

    private static String describe(long millis) {
        if (millis == Long.MAX_VALUE) {
            return "an unknown amount of time is";
        }
        if (millis >= 2_000L) {
            return (millis / 1_000L) + " s are";
        }
        return millis + " ms are";
    }
}
