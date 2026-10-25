package org.rogmann.mcp2sdk.agent;

import io.modelcontextprotocol.server.McpSyncServerExchange;

import java.util.concurrent.TimeUnit;

/**
 * The thread-local identity of the call being served (docs/subagent.md §14, P0.3): "who is the
 * fork thread whose script is running right now", answered on the thread that runs the script.
 *
 * <p><b>Why passed in and not inherited.</b> {@code JavaScriptTool} wires and evaluates on a worker
 * thread of its own pool; a {@code ThreadLocal} set on the request thread is invisible there, and
 * an inheritable one would be inherited by whatever the pool creates next. So the caller is
 * resolved on the request thread from {@link ForkIdentity} and <em>handed</em> to the worker, which
 * opens the scope around the module wiring and closes it in its {@code finally}. A pooled worker
 * thread therefore never carries a leftover of an earlier call: {@link #current()} is this call's
 * caller or null.</p>
 *
 * <p><b>What the scope is good for.</b> A JS module asks {@code ForkCallers.current() != null} and
 * gets an answer that came from outside the script - which is the whole point of P0 (docs/
 * subagent.md §14: an identity a script could bind for itself is no identity).</p>
 */
public final class ForkCallers {

    private static final ThreadLocal<Caller> CURRENT = new ThreadLocal<>();

    private ForkCallers() {
        // static access only
    }

    /**
     * The thread a call belongs to, plus the time it has left.
     *
     * @param batchId      batch of the fork
     * @param taskId       task the thread runs
     * @param childIndex   1-based position in the batch
     * @param deadlineNano {@link System#nanoTime()} reading the call has to stop at, or 0 when the
     *                     caller does not know one (a board method then has no ceiling to offer
     *                     and says so rather than blocking forever)
     */
    public record Caller(String batchId, String taskId, int childIndex, long deadlineNano) {

        /** @return {@code batch/task/child}, the form logs and the board tag use */
        public String tag() {
            return batchId + "/" + taskId + "/" + childIndex;
        }

        /** @return {@code batch/task} */
        public String label() {
            return batchId + "/" + taskId;
        }

        /** @return the identity the registry would have answered for this caller */
        public ForkIdentity.Entry entry() {
            return new ForkIdentity.Entry(batchId, taskId, childIndex);
        }

        /** @return true when this caller carries a deadline at all */
        public boolean hasDeadline() {
            return deadlineNano > 0L;
        }

        /** @return milliseconds left, {@link Long#MAX_VALUE} when no deadline is known */
        public long remainingMillis() {
            if (!hasDeadline()) {
                return Long.MAX_VALUE;
            }
            long nanosLeft = deadlineNano - System.nanoTime();
            return (nanosLeft <= 0L) ? 0L : TimeUnit.NANOSECONDS.toMillis(nanosLeft);
        }
    }

    /**
     * Resolves one MCP call to the thread behind it.
     *
     * @param exchange       the exchange of the call (its session is what the child's transport
     *                       registered under {@value ForkIdentity#HEADER}), may be null
     * @param deadlineNano   ceiling of this single call, 0 when unknown
     * @return the caller, or {@code null} when the session belongs to no fork thread
     */
    public static Caller callerOf(McpSyncServerExchange exchange, long deadlineNano) {
        String sessionId = (exchange == null) ? null : exchange.sessionId();
        ForkIdentity.Entry entry = ForkIdentity.getInstance().lookup(sessionId);
        if (entry == null) {
            return null;
        }
        return new Caller(entry.batchId(), entry.taskId(), entry.childIndex(), deadlineNano);
    }

    /**
     * Makes {@code caller} the identity of the current thread until the returned scope is closed.
     * A {@code null} caller opens an inert scope - the common case (an ordinary conversation), which
     * is why callers never have to test before opening.
     *
     * @param caller who is speaking, may be null
     * @return the scope to close in a {@code finally}
     */
    public static Scope open(Caller caller) {
        return new Scope(CURRENT.get(), caller);
    }

    /** @return the caller of the current call, or {@code null} outside a fork thread */
    public static Caller current() {
        return CURRENT.get();
    }

    /** Restores what was current before, so a reused worker thread cannot inherit an identity. */
    public static final class Scope implements AutoCloseable {

        private final Caller previous;
        private final Caller caller;
        private boolean closed = false;

        private Scope(Caller previous, Caller caller) {
            this.previous = previous;
            this.caller = caller;
            if (caller != null) {
                CURRENT.set(caller);
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
