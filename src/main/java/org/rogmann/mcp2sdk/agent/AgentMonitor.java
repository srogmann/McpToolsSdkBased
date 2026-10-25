package org.rogmann.mcp2sdk.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory live view of the sub-agents of {@link PrefixForkTool} - the data behind the
 * {@code /agents} page ({@code AgentMonitorController}, template {@code agents.html}).
 *
 * <p>The tool call that starts a batch is synchronous: the parent conversation waits until
 * {@link SubAgentRunner} has finished all children, so nobody sees what the threads are doing -
 * except here. Every child registers itself in {@link SubAgentRunner#runChild} and publishes
 * excerpts of its turns; the browser polls {@link AgentMonitorController} for a snapshot.</p>
 *
 * <p>The unit of the view is the <b>turn</b>: one model call and everything it produced - the
 * request preview, the reasoning, the answer, and the tool calls born from it. A child's state is
 * therefore a timeline, not a "current value", because the interesting question is what happened
 * in which order.</p>
 *
 * <p><b>Memory-only, by the same rule that keeps the transcripts buffered until the end of a
 * batch</b> (see the javadoc of {@code SubAgentRunner.Transcript}): while a batch runs, nothing
 * about a thread may be discoverable in the project directory, because a child that can list files
 * could read a sibling's live state. This monitor writes nothing to disk, ever - it is a ring of
 * bounded excerpts in the heap, swept after a retention time.</p>
 *
 * <p><b>Fire-and-forget by contract</b>: every hook is a best-effort update that must never
 * influence the run it observes. A child thread only ever writes its own state; readers get an
 * immutable snapshot under the same lock.</p>
 */
public final class AgentMonitor {

    private static final AgentMonitor INSTANCE = new AgentMonitor();

    /** Hard cap of stored children; finished ones are swept first. */
    private static final int MAX_AGENTS = 64;
    /** How long a finished child stays visible on the page. */
    private static final long RETENTION_MS = 30 * 60_000L;
    /** Cap of one stored text field (request/reasoning/answer excerpt). */
    private static final int FIELD_LIMIT = 8_000;
    /** Cap of one stored tool argument or tool result. */
    private static final int TOOL_FIELD_LIMIT = 4_000;

    /** One executed or still running tool call, a child of the turn that asked for it. */
    public record ToolCallView(int index, String name, String arguments, String result,
                               boolean error, long startedAt, long ms) {
    }

    /**
     * One model call of a child and its output. The token counters are the deltas of this turn
     * against the previous one; {@code cachedTokensKnown} is the child's cumulative flag - once a
     * backend has reported cached tokens, later turns without details inherit "known" with a 0.
     */
    public record TurnView(int index, String request, String reasoning, String content,
                           String finishReason, long promptTokens, long cachedTokens,
                           boolean cachedTokensKnown, long completionTokens, long ms,
                           List<ToolCallView> toolCalls) {
    }

    /** Everything the browser needs to render one child. */
    public record AgentView(String batchId, String taskId, int childIndex, String instruction,
                            String status, long startedAt, long deadlineRemainingMs, Long finishedAt,
                            int modelCalls, long promptTokens, long cachedTokens,
                            boolean cachedTokensKnown, long completionTokens, long elapsedMs,
                            List<TurnView> turns, List<String> notes) {
    }

    private final Map<String, State> agents = new ConcurrentHashMap<>();

    private AgentMonitor() {
    }

    /** @return the singleton, the same style as {@link ChatContextStore} and {@link ForkIdentity} */
    public static AgentMonitor getInstance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------- hooks, called by SubAgentRunner

    /**
     * Registers a child. Called at the top of {@link SubAgentRunner#runChild}, so even a child that
     * is refused immediately (fork inside a fork, decision F7) appears on the page with its status.
     *
     * @param batchId      batch label
     * @param taskId       task id within the batch
     * @param childIndex   1-based position of the task within the round
     * @param instruction  what the child is asked to do
     * @param deadlineNano {@link System#nanoTime()} reading the child has to stop at
     */
    public void childStarted(String batchId, String taskId, int childIndex, String instruction,
                             long deadlineNano) {
        if (batchId == null || taskId == null) {
            return;
        }
        sweep();
        long remainingMs = Math.max(0L, (deadlineNano - System.nanoTime()) / 1_000_000L);
        agents.put(key(batchId, taskId),
                new State(batchId, taskId, childIndex, instruction, remainingMs));
    }

    /**
     * Closes one turn of a child: what it saw (request preview), what it produced (reasoning,
     * answer, finish reason) and the cumulative token counters the monitor splits into per-turn
     * deltas. The tool calls of this turn arrive afterwards, one by one.
     */
    public void modelTurn(String batchId, String taskId, String requestPreview, String content,
                          String reasoning, String finishReason, long promptTokens,
                          long cachedTokens, boolean cachedTokensKnown, long completionTokens,
                          long ms) {
        State state = agents.get(key(batchId, taskId));
        if (state != null) {
            state.modelTurn(requestPreview, content, reasoning, finishReason, promptTokens,
                    cachedTokens, cachedTokensKnown, completionTokens, ms);
        }
    }

    /** Marks a tool call of a child as started; a long-running call stays visible as such. */
    public void toolStarted(String batchId, String taskId, String name, String arguments) {
        State state = agents.get(key(batchId, taskId));
        if (state != null) {
            state.toolStarted(name, arguments);
        }
    }

    /** Fills in the result of the child's last still-running tool call. */
    public void toolFinished(String batchId, String taskId, String result, boolean error, long ms) {
        State state = agents.get(key(batchId, taskId));
        if (state != null) {
            state.toolFinished(result, error, ms);
        }
    }

    /**
     * Closes a child with its final status and token bookkeeping - called from the {@code finally}
     * of the child loop, so even deadline, error and interruption paths never leave a ghost
     * "running" entry behind.
     */
    public void childFinished(String batchId, String taskId, String status, List<String> notes,
                              int modelCalls, long promptTokens, long cachedTokens,
                              boolean cachedTokensKnown, long completionTokens, long elapsedMs) {
        State state = agents.get(key(batchId, taskId));
        if (state != null) {
            state.finished(status, notes, modelCalls, promptTokens, cachedTokens, cachedTokensKnown,
                    completionTokens, elapsedMs);
        }
    }

    // ------------------------------------------------------------------------- read side

    /**
     * @return immutable snapshot of all known children, oldest batch first - what the browser polls
     */
    public List<AgentView> snapshot() {
        List<AgentView> views = new ArrayList<>(agents.size());
        for (State state : agents.values()) {
            views.add(state.view());
        }
        views.sort(Comparator.comparingLong(AgentView::startedAt)
                .thenComparingInt(AgentView::childIndex));
        return List.copyOf(views);
    }

    private static String key(String batchId, String taskId) {
        return batchId + "/" + taskId;
    }

    /**
     * Drops finished children after the retention time and enforces the hard cap. Called when a new
     * child registers, i.e. at most a few times per batch - not worth a background sweeper.
     */
    private void sweep() {
        long now = System.currentTimeMillis();
        agents.values().removeIf(state -> {
            Long finishedAt = state.finishedAt();
            return finishedAt != null && now - finishedAt > RETENTION_MS;
        });
        while (agents.size() > MAX_AGENTS) {
            State oldest = null;
            for (State state : agents.values()) {
                if (state.isFinished()
                        && (oldest == null || state.finishedAt() < oldest.finishedAt())) {
                    oldest = state;
                }
            }
            if (oldest == null) {
                break;
            }
            agents.remove(key(oldest.batchId(), oldest.taskId()));
        }
    }

    /**
     * @param text any text, may be null
     * @param limit hard cap
     * @return the stripped text, cut with a marker when longer; empty becomes null
     */
    private static String trunc(String text, int limit) {
        if (text == null) {
            return null;
        }
        String stripped = text.strip();
        if (stripped.isEmpty()) {
            return null;
        }
        return stripped.length() > limit
                ? stripped.substring(0, limit) + " …[cut]" : stripped;
    }

    /**
     * The mutable state of one child: identity, usage totals and the turn timeline. All writes come
     * from the child's own thread (plus the registration), all reads go through {@link #view()} -
     * one lock covers both directions.
     */
    private static final class State {

        private final String batchId;
        private final String taskId;
        private final int childIndex;
        private final String instruction;
        private final long startedAt;
        private final long deadlineRemainingMs;
        private final List<TurnState> turns = new ArrayList<>();

        private int toolCallCount;
        private long prevPromptTokens;
        private long prevCachedTokens;
        private long prevCompletionTokens;
        private String status;
        private Long finishedAt;
        private int modelCalls;
        private long promptTokens;
        private long cachedTokens;
        private boolean cachedTokensKnown;
        private long completionTokens;
        private long elapsedMs;
        private List<String> notes = List.of();

        private State(String batchId, String taskId, int childIndex, String instruction,
                      long deadlineRemainingMs) {
            this.batchId = batchId;
            this.taskId = taskId;
            this.childIndex = childIndex;
            this.instruction = trunc(instruction, FIELD_LIMIT);
            this.startedAt = System.currentTimeMillis();
            this.deadlineRemainingMs = deadlineRemainingMs;
        }

        private synchronized AgentView view() {
            List<TurnView> turnViews = new ArrayList<>(turns.size());
            for (TurnState turn : turns) {
                turnViews.add(new TurnView(turn.index, turn.request, turn.reasoning, turn.content,
                        turn.finishReason, turn.promptTokens, turn.cachedTokens,
                        turn.cachedTokensKnown, turn.completionTokens, turn.ms,
                        List.copyOf(turn.toolCalls)));
            }
            return new AgentView(batchId, taskId, childIndex, instruction, status, startedAt,
                    deadlineRemainingMs, finishedAt, modelCalls, promptTokens, cachedTokens,
                    cachedTokensKnown, completionTokens, elapsedMs, List.copyOf(turnViews), notes);
        }

        private synchronized void modelTurn(String requestPreview, String content, String reasoning,
                                            String finishReason, long promptTokens,
                                            long cachedTokens, boolean cachedTokensKnown,
                                            long completionTokens, long ms) {
            this.modelCalls++;
            TurnState turn = new TurnState(turns.size());
            turn.request = trunc(requestPreview, FIELD_LIMIT);
            turn.reasoning = trunc(reasoning, FIELD_LIMIT);
            turn.content = trunc(content, FIELD_LIMIT);
            turn.finishReason = finishReason == null || finishReason.isEmpty() ? null : finishReason;
            turn.promptTokens = Math.max(0L, promptTokens - prevPromptTokens);
            turn.cachedTokens = Math.max(0L, cachedTokens - prevCachedTokens);
            turn.completionTokens = Math.max(0L, completionTokens - prevCompletionTokens);
            turn.cachedTokensKnown = cachedTokensKnown;
            turn.ms = ms;
            prevPromptTokens = promptTokens;
            prevCachedTokens = cachedTokens;
            prevCompletionTokens = completionTokens;
            turns.add(turn);
        }

        private synchronized void toolStarted(String name, String arguments) {
            if (turns.isEmpty()) {
                return;
            }
            TurnState turn = turns.get(turns.size() - 1);
            turn.toolCalls.add(new ToolCallView(toolCallCount++, name,
                    trunc(arguments, TOOL_FIELD_LIMIT), null, false,
                    System.currentTimeMillis(), 0L));
        }

        private synchronized void toolFinished(String result, boolean error, long ms) {
            for (int t = turns.size() - 1; t >= 0; t--) {
                List<ToolCallView> calls = turns.get(t).toolCalls;
                for (int i = calls.size() - 1; i >= 0; i--) {
                    ToolCallView call = calls.get(i);
                    if (call.result() == null) {
                        calls.set(i, new ToolCallView(call.index(), call.name(), call.arguments(),
                                trunc(result, TOOL_FIELD_LIMIT), error, call.startedAt(), ms));
                        return;
                    }
                }
            }
        }

        private synchronized void finished(String status, List<String> notes, int modelCalls,
                                           long promptTokens, long cachedTokens,
                                           boolean cachedTokensKnown, long completionTokens,
                                           long elapsedMs) {
            this.status = status;
            this.finishedAt = System.currentTimeMillis();
            this.modelCalls = modelCalls;
            this.promptTokens = promptTokens;
            this.cachedTokens = cachedTokens;
            this.cachedTokensKnown = cachedTokensKnown;
            this.completionTokens = completionTokens;
            this.elapsedMs = elapsedMs;
            List<String> trimmed = new ArrayList<>();
            for (String note : notes) {
                if (note != null && !note.isBlank()) {
                    trimmed.add(trunc(note, 500));
                }
            }
            this.notes = List.copyOf(trimmed);
        }

        private boolean isFinished() {
            return finishedAt != null;
        }

        private Long finishedAt() {
            return finishedAt;
        }

        private String batchId() {
            return batchId;
        }

        private String taskId() {
            return taskId;
        }

        /** One turn in the making: written by the child's thread only, read under the state lock. */
        private static final class TurnState {
            private final int index;
            private final List<ToolCallView> toolCalls = new ArrayList<>();
            private String request;
            private String reasoning;
            private String content;
            private String finishReason;
            private long promptTokens;
            private long cachedTokens;
            private boolean cachedTokensKnown;
            private long completionTokens;
            private long ms;

            private TurnState(int index) {
                this.index = index;
            }
        }
    }
}
