package org.rogmann.mcp2sdk.agent;

/**
 * What an {@link AgentTask} is: work or announcement.
 *
 * <ul>
 *   <li>{@link #TASK} - proposed work. The caller seeds every task of a round as a TASK; a thread's
 *       finding that is not its own to act on is posted as one too (run b-fc24 lesson: without a
 *       legitimate channel, a thread edits the source instead). Claimable and closeable.</li>
 *   <li>{@link #INFO} - an announcement, readable and commentable, never claimable and never
 *       closed by hand; the batch ends it. An INFO that turns out to be work is re-posted by the
 *       caller as a TASK in the next round.</li>
 * </ul>
 */
enum AgentTaskKind {
    TASK,
    INFO
}
