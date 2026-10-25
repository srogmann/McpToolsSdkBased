package org.rogmann.mcp2sdk.agent;

/**
 * Address of a participant of one fork batch: the batch, the task the thread runs ({@code parent}
 * for the caller of {@code prefix_fork}), and the thread's 1-based position in the batch - the
 * number the plain tail has carried since the beginning ("you are Child n") for exactly this
 * purpose (docs/webui.md §3.8 point 16, docs/subagent.md §6).
 *
 * <p>The board never asks a thread to name itself: the runner attaches the identity to every call
 * the thread makes onto the board ({@code SubAgentRunner.executeTool}), because a field the model
 * fills is a field the model can fill wrongly or as a sibling (docs/subagent.md §6, decision B5 in
 * its built form).</p>
 */
record AgentId(String batchId, String taskId, int childIndex) {

    /** Task id of the caller of {@code prefix_fork}; it seeds the board and reads it afterwards. */
    static final String PARENT_TASK_ID = "parent";

    /** Position of the caller of {@code prefix_fork}: it has no number. */
    static final int PARENT_CHILD_INDEX = 0;

    /**
     * @return {@code batchId/taskId/childIndex}, the form the runner puts into the board calls
     */
    String tag() {
        return batchId + "/" + taskId + "/" + childIndex;
    }

    /** @return {@code batchId/taskId}, the form the log uses ("prefix_fork b-7f3c task 't2'") */
    @Override
    public String toString() {
        return batchId + "/" + taskId;
    }
}
