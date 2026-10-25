package org.rogmann.mcp2sdk.agent;

/**
 * Lifecycle of an {@link AgentTask} of kind {@link AgentTaskKind#TASK}. INFO entries stay OPEN
 * until the batch ends; their status is decoration, their content is the point.
 *
 * <ul>
 *   <li>{@link #OPEN} - nobody has taken it. The initial state, also the state a claim returns to
 *       when the claiming thread ends without closing.</li>
 *   <li>{@link #IN_PROGRESS} - a thread holds it ({@code AgentTask.claimedBy()}). Set atomically
 *       under the board's monitor, never by check-then-act.</li>
 *   <li>{@link #CLOSED} - finished, by the claimer, the creator or the thread that owns the seeded
 *       task; the closing line becomes the last message of the entry.</li>
 * </ul>
 */
enum AgentTaskStatus {
    OPEN,
    IN_PROGRESS,
    CLOSED
}
