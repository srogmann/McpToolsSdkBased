package org.rogmann.mcp2sdk.agent;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.rogmann.mcp2sdk.agent.ChatContextStore.ContextSnapshot;
import org.rogmann.mcp2sdk.util.BoundedText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs the child conversations of {@link PrefixForkTool} (docs/webui.md §3.5).
 *
 * <p><b>One run</b> is the loop "build request -&gt; POST -&gt; read {@code choices[0].message}
 * -&gt; execute its tool calls -&gt; append the results", until the backend stops asking for tools,
 * the tool-call budget of the task is used up, or the deadline of the task is reached.</p>
 *
 * <p><b>Where it sends</b>: the loopback into the very proxy that recorded the snapshot,
 * {@code <selfBaseUrl>/chat/v1/chat/completions} with {@code messages[]} - not the
 * {@code /responses} dialect of {@code CallLlmTool}, which would take another templating path and
 * another token start, i.e. no prefix gain (decision F5). Two consequences are handled here: the
 * non-streaming branch of the proxy wraps its body SSE-style ({@code data: {...}}), so the answer
 * is unwrapped before parsing; and every running child occupies a servlet thread of the same
 * server, which is fine for the small task counts of v1 but is worth knowing.</p>
 *
 * <p><b>What it sends</b>: the <em>incoming</em> body of the snapshot plus the reconstructed tail
 * (docs/webui.md §3.3), with {@code stream:false}. Preparation happens once - in the proxy, by
 * {@code chat/ChatRequestPreparer} - exactly as for the parent request. Preparing it here as well
 * would translate the reasoning fields twice and break prefix identity (decision F4). The tool list
 * travels byte-identically; the permission list is enforced <em>at execution time</em> with a
 * synthetic tool result, never by leaving tools out of the request (decision F6).</p>
 *
 * <p><b>Failure isolation</b>: a child never fails another one and never throws out of
 * {@link #run} - every outcome carries its own status, partial results are delivered, and an
 * expired child reports {@code partial} with what it has.</p>
 */
final class SubAgentRunner {

    private static final Logger LOG = LoggerFactory.getLogger(SubAgentRunner.class);

    /** The child stopped by itself: the backend answered without tool calls. */
    static final String STATUS_DONE = "done";
    /** Deadline or budget reached, but there is something to report. */
    static final String STATUS_PARTIAL = "partial";
    /** Deadline reached with nothing to report. */
    static final String STATUS_TIMEOUT = "timeout";
    /** Transport or backend failure. */
    static final String STATUS_ERROR = "error";
    /** The task was not started because the caller itself is a child (decision F7). */
    static final String STATUS_REFUSED = "refused";

    /** Name of this tool, spelled here because the child sees it in its own tool list. */
    static final String FORK_TOOL_NAME = "prefix_fork";

    /** Execution-time refusal of a fork inside a fork (decision F7). */
    static final String FORK_REFUSAL = "prefix_fork is not available inside a fork."
            + " Finish this task and report; the caller decides whether to fork further.";

    /** Prefix of the reconstructed tool-call id of the tail. */
    static final String TAIL_CALL_ID_PREFIX = "call_fork_";

    /** Path of the transcript artifacts, relative to the project directory. */
    static final String DEFAULT_ARTIFACT_DIR = "target/fork";

    /** The non-streaming read of the proxy can take as long as the model thinks. */
    private static final int CONNECT_TIMEOUT_MS = 5_000;

    /** Task as the caller described it. */
    record Task(String id, String instruction, int maxToolCalls, int reportMaxChars) {
    }

    /**
     * Token bookkeeping of one child. This is the measurement the whole idea stands and falls with
     * (decision F11): {@code cachedTokens / promptTokens} close to one means the prefix was reused.
     */
    record Usage(int calls, long promptTokens, long cachedTokens, boolean cachedTokensKnown,
                 long completionTokens, long ms) {
    }

    /** What one child produced. */
    record Outcome(String id, String status, String report, Usage usage, String transcript,
                   List<String> notes) {
    }

    /** MCP endpoint the children execute their tool calls against; {@code null} means no tools. */
    private final String mcpUrl;
    /** How the child instruction is attached: {@code tool} (default) or {@code user}. */
    private final String tailStyle;
    /** Directory of the transcripts. */
    private final Path artifactDir;
    /** The board of this batch, or {@code null} when the caller asked for none (no {@code board}). */
    private final AgentBoard board;
    /** Wall-clock ceiling for the whole batch, the sum of children is not allowed to exceed it. */
    private final long batchDeadlineMillis;
    /** Per-child read timeout on top of the child's own deadline, so the HTTP call never outlives it. */
    private final long httpGraceMillis;
    /** Mapper for the request and response bodies. */
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    /**
     * @param mcpUrl             MCP endpoint for the children's tool calls; {@code PrefixForkTool}
     *                           passes its own server's base URL when the property is unset, so
     *                           {@code null} only happens if the snapshot carries no base URL at all
     * @param tailStyle          value of {@code agent.fork.tail}: {@code user} (default, the task in a
     *                           user message behind a shared acknowledgement) or {@code tool} (the task
     *                           in the tool result of the fork call itself)
     * @param artifactDirValue   value of {@code agent.fork.artifactDir}
     * @param batchDeadlineMillis ceiling for the whole batch
     */
    SubAgentRunner(String mcpUrl, String tailStyle, String artifactDirValue, long batchDeadlineMillis) {
        this(mcpUrl, tailStyle, artifactDirValue, batchDeadlineMillis, null);
    }

    /**
     * @param board              the batch's board, or {@code null} when the caller set no
     *                           {@code board:true} - it is what identifies the tool calls a thread
     *                           makes onto the board, without a word about it in the child
     *                           instruction (docs/subagent.md §6)
     */
    SubAgentRunner(String mcpUrl, String tailStyle, String artifactDirValue, long batchDeadlineMillis,
                   AgentBoard board) {
        this.board = board;
        this.mcpUrl = (mcpUrl == null || mcpUrl.isBlank()) ? null : mcpUrl.trim();
        this.tailStyle = "tool".equalsIgnoreCase(tailStyle) ? "tool"
                : "user".equalsIgnoreCase(tailStyle) ? "user" : "plain";
        this.batchDeadlineMillis = Math.max(1_000L, batchDeadlineMillis);
        this.httpGraceMillis = 5_000L;
        String dir = (artifactDirValue == null || artifactDirValue.isBlank())
                ? DEFAULT_ARTIFACT_DIR : artifactDirValue.trim();
        Path base = Paths.get(System.getProperty("IDE_PROJECT_DIR", ".")).toAbsolutePath().normalize();
        this.artifactDir = base.resolve(dir).normalize();
    }

    /**
     * Runs all tasks in parallel and waits for the batch.
     *
     * @param batchId         batch label, names the transcript directory and the tail call id
     * @param snapshot        frozen prefix to start from
     * @param tasks           1..4 tasks
     * @param commonInstructions text that applies to every child, may be null
     * @param tailArguments   the {@code arguments} JSON of the reconstructed tail, identical for
     *                        every child so all task descriptions sit in the shared prefix
     * @param taskDeadlineMillis deadline per task, below {@code batchDeadlineMillis}
     * @return one outcome per task, in the order the tasks were given
     */
    List<Outcome> run(String batchId, ContextSnapshot snapshot, List<Task> tasks,
                      String commonInstructions, String tailArguments, long taskDeadlineMillis) {
        if (mcpUrl == null) {
            LOG.info("Fork {}: no tool endpoint configured, the children answer without tools", batchId);
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, tasks.size()), daemonFactory(batchId));
        List<Outcome> outcomes = new ArrayList<>();
        try {
            long batchEndsNano = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(batchDeadlineMillis);
            List<Future<Outcome>> futures = new ArrayList<>();
            for (Task task : tasks) {
                long ownDeadline = Math.min(batchEndsNano,
                        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(taskDeadlineMillis));
                futures.add(pool.submit(() -> runChild(batchId, snapshot, task,
                        commonInstructions, tailArguments, ownDeadline)));
            }
            for (int i = 0; i < futures.size(); i++) {
                Task task = tasks.get(i);
                long remainingMs = Math.max(1L,
                        TimeUnit.NANOSECONDS.toMillis(batchEndsNano - System.nanoTime()) + httpGraceMillis);
                try {
                    outcomes.add(futures.get(i).get(remainingMs, TimeUnit.MILLISECONDS));
                } catch (TimeoutException e) {
                    futures.get(i).cancel(true);
                    LOG.warn("Fork {}: task '{}' exceeded the batch deadline of {} ms",
                            batchId, task.id(), batchDeadlineMillis);
                    outcomes.add(new Outcome(task.id(), STATUS_TIMEOUT, "", null, null,
                            List.of("batch deadline of " + batchDeadlineMillis + " ms exceeded")));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    futures.get(i).cancel(true);
                    outcomes.add(new Outcome(task.id(), STATUS_ERROR, "", null, null,
                            List.of("interrupted while waiting for the child")));
                } catch (ExecutionException e) {
                    // runChild catches its own failures; this is the guard against a leftover bug.
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    LOG.error("Fork " + batchId + ": task '" + task.id() + "' failed unexpectedly", cause);
                    outcomes.add(new Outcome(task.id(), STATUS_ERROR, "", null, null,
                            List.of("unexpected failure: " + cause)));
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return outcomes;
    }

    /**
     * The loop of a single child. Never throws: everything it cannot handle becomes a status and a
     * note.
     *
     * @param batchId            batch label
     * @param snapshot           frozen prefix
     * @param task               what this child has to do
     * @param commonInstructions text for every child, may be null
     * @param tailArguments      shared {@code arguments} JSON of the tail
     * @param deadlineNano       {@link System#nanoTime()} reading this child has to stop at
     * @return the outcome of this child
     */
    private Outcome runChild(String batchId, ContextSnapshot snapshot, Task task,
                             String commonInstructions, String tailArguments, long deadlineNano) {
        List<String> notes = new ArrayList<>();
        long startMillis = System.currentTimeMillis();
        Transcript transcript = new Transcript(transcriptFile(batchId, task.id()));
        // The monitor is the live view of this run for the browser ("/agents" page). Memory-only,
        // fire-and-forget: no call below may influence the run it observes.
        AgentMonitor.getInstance().childStarted(batchId, task.id(),
                childIndexOf(jsonMapper, tailArguments, task.id()), task.instruction(), deadlineNano);
        // Safety net below the execution-time refusal (F7): a child must not clone itself.
        if (snapshot.forkDepth() > 0) {
            LOG.info("Fork {}: task '{}' refused - the caller is itself a fork (depth {})",
                    batchId, task.id(), snapshot.forkDepth());
            AgentMonitor.getInstance().childFinished(batchId, task.id(), STATUS_REFUSED,
                    List.of("refused: fork depth " + snapshot.forkDepth()), 0, 0L, 0L, false, 0L,
                    System.currentTimeMillis() - startMillis);
            return new Outcome(task.id(), STATUS_REFUSED, FORK_REFUSAL,
                    new Usage(0, 0L, 0L, false, 0L, System.currentTimeMillis() - startMillis),
                    null, List.of("refused: fork depth " + snapshot.forkDepth()));
        }
        List<ObjectNode> messages = new ArrayList<>();
        JsonNode frozen = snapshot.body().path("messages");
        if (frozen.isArray()) {
            for (JsonNode message : frozen) {
                if (message instanceof ObjectNode copy) {
                    messages.add((ObjectNode) copy.deepCopy());
                }
            }
        }
        // Ab hier gehört die Unterhaltung nur diesem Kind - und nur das kommt ins Transkript.
        int historyEnd = messages.size();

        for (ObjectNode message : tailMessages(batchId, tailArguments, task, commonInstructions)) {
            messages.add(message);
        }
        Set<String> allowedTools = toolNamesOf(snapshot);
        // The thread's own address, and not the model's to state: the runner injects it into board
        // calls (docs/subagent.md §6) and - since P0 - the thread's MCP client puts it on every
        // request as a header, so any tool the thread works with can ask the server who is speaking
        // (docs/subagent.md §14). Known whether or not this batch has a board.
        AgentId self = new AgentId(batchId, task.id(),
                childIndexOf(jsonMapper, tailArguments, task.id()));
        if (board != null) {
            // The board learns where this thread has to stop; board.wait() is capped by that, so a
            // sleep cannot outlive the thread that asked for it.
            board.startChild(self, deadlineNano);
        }
        int calls = 0;
        long promptTokens = 0L;
        long completionTokens = 0L;
        long cachedTokens = 0L;
        boolean cachedKnown = false;
        String report = "";
        String status = STATUS_TIMEOUT;

        McpSyncClient mcpClient = null;
        if (mcpUrl != null) {
            try {
                // One client per child: initialize() in the child thread, close() in the finally,
                // so session-bound state on the MCP server cannot collide between siblings. The
                // request template carries the thread's address on every request of this client
                // (P0.1): a model can write arguments, not headers, and the server pairs the header
                // with the session it handed to exactly this client (ForkIdentity).
                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                        .header(ForkIdentity.HEADER, self.tag());
                McpClientTransport transport = HttpClientStreamableHttpTransport
                        .builder(mcpUrl).endpoint("/mcp").requestBuilder(requestBuilder).build();
                mcpClient = McpClient.sync(transport)
                        .requestTimeout(Duration.ofMinutes(20))   // a javascript_tool run may take minutes
                        .capabilities(McpSchema.ClientCapabilities.builder().build())
                        .build();
                mcpClient.initialize();
            } catch (RuntimeException e) {
                LOG.warn("Fork {}: task '{}' has no tool endpoint ({}): {}",
                        batchId, task.id(), mcpUrl, e.getMessage());
                notes.add("tool endpoint unavailable: " + e.getMessage());
                mcpClient = null;
            }
        }
        try {
            while (true) {
                if (System.nanoTime() >= deadlineNano) {
                    status = report.isEmpty() ? STATUS_TIMEOUT : STATUS_PARTIAL;
                    notes.add(report.isEmpty() ? "deadline reached before the first answer"
                            : "deadline reached, reporting the last answer");
                    break;
                }
                int remainingMs = (int) Math.max(1L, TimeUnit.NANOSECONDS.toMillis(
                        deadlineNano - System.nanoTime()) + httpGraceMillis);
                JsonNode response;
                long turnStartNanos = System.nanoTime();
                try {
                    response = complete(snapshot, messages, remainingMs);
                } catch (IOException | RuntimeException e) {
                    LOG.warn("Fork {}: task '{}' failed at the model call: {}",
                            batchId, task.id(), e.toString());
                    status = report.isEmpty() ? STATUS_ERROR : STATUS_PARTIAL;
                    notes.add("model call failed: " + e.getMessage());
                    break;
                }
                calls++;
                JsonNode usageNode = response.path("usage");
                if (usageNode.isObject()) {
                    promptTokens += usageNode.path("prompt_tokens").asLong(0L);
                    completionTokens += usageNode.path("completion_tokens").asLong(0L);
                    JsonNode cachedNode = usageNode.path("prompt_tokens_details").path("cached_tokens");
                    if (cachedNode.isNumber()) {
                        cachedTokens += cachedNode.asLong();
                        cachedKnown = true;
                    }
                }
                JsonNode choice = response.path("choices").path(0);
                JsonNode message = choice.path("message");
                String finishReason = choice.path("finish_reason").asString("");
                String content = message.path("content").asString("");
                JsonNode toolCalls = message.get("tool_calls");
                boolean wantsTools = toolCalls != null && toolCalls.isArray() && !toolCalls.isEmpty();
                // Live view: what this turn asked (the last own messages) and what came back. The
                // reasoning field is read although the runner itself ignores it - F4 keeps it out
                // of the request, the response may still carry it. The counters are the cumulative
                // totals; the monitor derives the per-turn deltas itself.
                AgentMonitor.getInstance().modelTurn(batchId, task.id(),
                        previewOf(messages, historyEnd), content, reasoningOf(message), finishReason,
                        promptTokens, cachedTokens, cachedKnown, completionTokens,
                        (System.nanoTime() - turnStartNanos) / 1_000_000L);

                ObjectNode assistant = jsonMapper.createObjectNode();
                assistant.put("role", "assistant");
                assistant.put("content", content);
                if (wantsTools) {
                    assistant.set("tool_calls", toolCalls.deepCopy());
                }
                messages.add(assistant);
                if (!content.isEmpty()) {
                    report = content;
                }
                if (!wantsTools) {
                    status = STATUS_DONE;
                    if (report.isEmpty()) {
                        notes.add("the child answered without any text"
                                + (finishReason.isEmpty() ? "" : " (finish_reason=" + finishReason + ")"));
                    }
                    break;
                }
                if (!"tool_calls".equals(finishReason)) {
                    // A message that carries tool calls is executed even when the backend named
                    // another finish reason (some tool-call parsers report "stop" or "length"
                    // together with the call). Refusing to run it would end the thread with an
                    // empty report and no trace of why; the budget bound below still applies.
                    notes.add("tool_calls with finish_reason=" + finishReason + ", executed anyway");
                }
                if (calls >= task.maxToolCalls()) {
                    status = STATUS_PARTIAL;
                    notes.add("tool-call budget of " + task.maxToolCalls() + " used up");
                    break;
                }
                for (JsonNode toolCall : toolCalls) {
                    JsonNode function = toolCall.path("function");
                    String toolName = function.path("name").asString("");
                    String callId = toolCall.path("id").asString("call_" + calls + "_" + toolName);
                    String arguments = function.path("arguments").asString("");
                    AgentMonitor.getInstance().toolStarted(batchId, task.id(), toolName, arguments);
                    long toolStartNanos = System.nanoTime();
                    String result = executeTool(mcpClient, toolName, arguments,
                            allowedTools, notes, self);
                    // The runner marks refusals and failures in the result text (F6); the monitor
                    // reuses exactly those markers, it adds no semantics of its own.
                    boolean toolFailed = result.startsWith("Refused:")
                            || result.startsWith("Tool call failed:")
                            || result.startsWith(FORK_REFUSAL);
                    AgentMonitor.getInstance().toolFinished(batchId, task.id(), result, toolFailed,
                            (System.nanoTime() - toolStartNanos) / 1_000_000L);
                    ObjectNode toolMessage = jsonMapper.createObjectNode();
                    toolMessage.put("role", "tool");
                    toolMessage.put("tool_call_id", callId);
                    toolMessage.put("content", result);
                    messages.add(toolMessage);
                }
            }
        } finally {
            if (mcpClient != null) {
                try {
                    mcpClient.close();
                } catch (RuntimeException e) {
                    LOG.debug("Fork {}: closing the MCP client of task '{}' failed: {}",
                            batchId, task.id(), e.getMessage());
                }
            }
            if (board != null) {
                // The board learns the thread's end even when the thread never touched it: its own
                // (seeded) entry closes with the status, a claim it held is released - a thread
                // that died mid-claim must not strand the entry as IN_PROGRESS - and the deadline
                // the board holds for this task goes with it.
                try {
                    board.finishChild(self, status, String.join("; ", notes));
                } catch (RuntimeException e) {
                    LOG.debug("Fork {}: board update of task '{}' failed: {}",
                            batchId, task.id(), e.getMessage());
                }
            }
            // The monitor's last word, so no path out of the loop - deadline, error, interruption -
            // leaves a ghost "running" entry on the page.
            AgentMonitor.getInstance().childFinished(batchId, task.id(), status, notes, calls,
                    promptTokens, cachedTokens, cachedKnown, completionTokens,
                    System.currentTimeMillis() - startMillis);
        }
        Usage usage = new Usage(calls, promptTokens, cachedTokens, cachedKnown, completionTokens,
                System.currentTimeMillis() - startMillis);
        transcript.write(messages, historyEnd, status, usage);
        BoundedText bounded = new BoundedText(Math.max(1, task.reportMaxChars()));
        bounded.append(report);
        String text = bounded.text();
        // "unreported" on purpose: a backend that never named the cached tokens would otherwise
        // read as a missed prefix - the first measured run (2026-10-10) did report them for the
        // buffered child requests too, so the case is rare, not hypothetical (docs/webui.md §3.6).
        String cacheRatio = !cachedKnown ? "unreported"
                : (promptTokens > 0 ? (cachedTokens * 100L / promptTokens) + "%" : "n/a");
        LOG.info("Fork {}: task '{}' {} after {} call(s), prompt {} / cached {} ({}), {} ms",
                batchId, task.id(), status, calls, promptTokens, cachedTokens, cacheRatio, usage.ms());
        return new Outcome(task.id(), status, text, usage, transcript.pathAsString(), notes);
    }

    /**
     * Sends one turn of the child to the proxy and reads the answer.
     *
     * @param snapshot   frozen prefix (its body is the base of the request)
     * @param messages   conversation of the child so far
     * @param readTimeoutMs read timeout of the HTTP call
     * @return the parsed completion response
     * @throws IOException if the transport or the backend failed
     */
    private JsonNode complete(ContextSnapshot snapshot, List<ObjectNode> messages, int readTimeoutMs)
            throws IOException {
        ObjectNode request = buildChildRequest(snapshot, messages);

        String urlText = snapshot.selfBaseUrl() + "/chat/v1/chat/completions";
        URL url = URI.create(urlText).toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty(ChatContextStore.HEADER_FORK_DEPTH,
                String.valueOf(snapshot.forkDepth() + 1));
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(readTimeoutMs);
        connection.setDoOutput(true);
        try {
            try (OutputStream os = connection.getOutputStream();
                 Writer writer = new OutputStreamWriter(os, StandardCharsets.UTF_8)) {
                writer.write(jsonMapper.writeValueAsString(request));
                writer.flush();
            }
            int status = connection.getResponseCode();
            InputStream stream = (status >= HttpURLConnection.HTTP_BAD_REQUEST)
                    ? connection.getErrorStream() : connection.getInputStream();
            String body = (stream == null) ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            if (status >= HttpURLConnection.HTTP_BAD_REQUEST) {
                throw new IOException("HTTP " + status + " from " + urlText + ": "
                        + (body.length() > 500 ? body.substring(0, 500) + "..." : body));
            }
            return jsonMapper.readTree(unwrapSse(body));
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Small preview of what a child just sent to the model: the last few messages of its own
     * conversation, role plus an excerpt each - the monitor stores excerpts, not request bodies.
     *
     * @param messages   the child's whole conversation
     * @param historyEnd index where the child's own part begins (behind the frozen prefix)
     * @return the preview text for the monitor's "last request" field
     */
    private static String previewOf(List<ObjectNode> messages, int historyEnd) {
        StringBuilder text = new StringBuilder();
        int first = Math.max(historyEnd, messages.size() - 4);
        for (int i = first; i < messages.size(); i++) {
            ObjectNode message = messages.get(i);
            if (!text.isEmpty()) {
                text.append("\n---\n");
            }
            text.append(message.path("role").asString("")).append(": ");
            String content = message.path("content").asString("");
            if (content.isEmpty() && message.has("tool_calls")) {
                content = "(" + message.path("tool_calls").size() + " tool call(s))";
            }
            text.append(excerpt(content, 300));
        }
        return text.toString();
    }

    /**
     * Reasoning content of a child's answer, if the backend reports one. The runner itself ignores
     * it - only {@code content} becomes the report - but the monitor wants to show it. Two field
     * names, because the ecosystem has not settled on one ({@code reasoning_content} and
     * {@code reasoning} are both in use).
     *
     * @param message the {@code choices[0].message} of a completion response
     * @return the reasoning text, or the empty string
     */
    private static String reasoningOf(JsonNode message) {
        String reasoning = message.path("reasoning_content").asString("");
        if (reasoning.isEmpty()) {
            reasoning = message.path("reasoning").asString("");
        }
        return reasoning;
    }

    /**
     * @param text  any text, may be null
     * @param limit hard cap
     * @return the stripped text, cut with a marker when longer
     */
    private static String excerpt(String text, int limit) {
        String stripped = text == null ? "" : text.strip();
        return stripped.length() > limit
                ? stripped.substring(0, limit) + " …[cut]" : stripped;
    }

    /**
     * Builds the body a child sends: the <em>incoming</em> body of the snapshot, with the child's
     * messages, {@code stream:false} and no {@code stream_options}. Nothing else - in particular no
     * reasoning translation and no model override. That is the whole point (docs/webui.md F4): the
     * proxy prepares this body once, with the same code that prepared the parent's request, and
     * preparing it here would translate the reasoning fields a second time and kill the prefix.
     * Package-private instead of private so the unit test can assert it.
     *
     * @param snapshot frozen prefix
     * @param messages conversation of the child so far
     * @return the request body to post
     */
    static ObjectNode buildChildRequest(ContextSnapshot snapshot, List<ObjectNode> messages) {
        ObjectNode request = (ObjectNode) snapshot.body().deepCopy();
        ArrayNode messageArray = request.putArray("messages");
        for (ObjectNode message : closingForDanglingCalls(snapshot)) {
            messageArray.add(message);
        }
        for (ObjectNode message : messages) {
            messageArray.add(message.deepCopy());
        }
        request.put("stream", false);
        request.remove("stream_options");   // the proxy adds it for its streaming path only
        return request;
    }

    /** Mapper for the synthetic tool results that close the parent's in-flight calls. */
    private static final JsonMapper CLOSING_MAPPER = JsonMapper.builder().build();

    /**
     * Closes the parent's in-flight tool calls that the frozen prefix ends with.
     *
     * <p>A fork happens <em>inside</em> the parent's assistant turn: the snapshot the children
     * inherit ends with the very message that called {@code prefix_fork} - and its result is the
     * batch itself, which cannot exist before the children start. Every child therefore inherits
     * its own genesis as an unanswered tool call, and run b-bcba showed what a model does with
     * that: it completed the call it saw (a nested {@code prefix_fork}, refused by decision F7)
     * instead of its task - the tool-call shape of what b-fc24 measured as "finishing the parent's
     * in-flight sentence".</p>
     *
     * <p>The repair answers the call instead of removing it: the call did happen, and it did start
     * this batch, so the honest result is a short acknowledgement - no task text (that is the
     * tail's business, docs/webui.md §3.3), nothing batch-specific, identical for every child so
     * it stays inside the shared prefix. Calls that already have a result in the prefix stay
     * untouched.</p>
     *
     * <p>Covered shape: the prefix ends with one assistant message holding tool calls, optionally
     * followed by tool results that arrived while the turn was still executing (parallel calls,
     * one of them the still-running fork). Anything beyond that group is left as it is.</p>
     *
     * @param snapshot frozen prefix
     * @return the synthetic {@code role:"tool"} messages to insert between prefix and tail
     */
    private static List<ObjectNode> closingForDanglingCalls(ContextSnapshot snapshot) {
        JsonNode messages = snapshot.body().path("messages");
        if (!messages.isArray() || messages.isEmpty()) {
            return List.of();
        }
        // Walk back over the trailing group of tool results; their calls are answered already.
        int last = messages.size() - 1;
        java.util.Set<String> answered = new java.util.HashSet<>();
        while (last >= 0 && "tool".equals(messages.get(last).path("role").asString(""))) {
            answered.add(messages.get(last).path("tool_call_id").asString(""));
            last--;
        }
        JsonNode lastMessage = last >= 0 ? messages.get(last) : null;
        if (lastMessage == null || !lastMessage.isObject()
                || !"assistant".equals(lastMessage.path("role").asString(""))) {
            return List.of();
        }
        JsonNode toolCalls = lastMessage.get("tool_calls");
        if (toolCalls == null || !toolCalls.isArray()) {
            return List.of();
        }
        List<ObjectNode> closing = new ArrayList<>();
        for (JsonNode toolCall : toolCalls) {
            String id = toolCall.path("id").asString("");
            if (id.isEmpty() || answered.contains(id)) {
                continue;
            }
            ObjectNode answer = CLOSING_MAPPER.createObjectNode();
            answer.put("role", "tool");
            answer.put("tool_call_id", id);
            answer.put("content", "prefix_fork accepted this batch; this thread is one of its"
                    + " tasks. Do not call it again.");
            closing.add(answer);
        }
        return closing;
    }

    /**
     * Unwraps the SSE-style body the non-streaming branch of the proxy wraps its JSON answer in
     * ({@code data: {...}\n\ndata: [DONE]\n\n}). A body that is plain JSON is returned unchanged.
     *
     * @param body raw response body
     * @return the JSON of the first data payload, or the body itself
     */
    static String unwrapSse(String body) {
        if (body == null || !body.stripLeading().startsWith("data:")) {
            return body;
        }
        for (String line : body.split("\n")) {
            String trimmed = line.strip();
            if (!trimmed.startsWith("data:")) {
                continue;
            }
            String payload = trimmed.substring("data:".length()).strip();
            if (!payload.isEmpty() && !"[DONE]".equals(payload)) {
                return payload;
            }
        }
        return body;
    }

    /**
     * Runs one tool call of a child, or refuses it with a tool result that says why (decision F6:
     * the restriction is enforced here, not by trimming the {@code tools} list of the request).
     *
     * @param mcpClient  MCP client of this child, {@code null} when no endpoint is configured
     * @param toolName   name the model asked for
     * @param arguments  {@code function.arguments}, a JSON object as a string
     * @param allowed    tool names of the parent conversation
     * @param notes      collects what was refused
     * @return the text that becomes the {@code role:"tool"} answer
     */
    String executeTool(McpSyncClient mcpClient, String toolName, String arguments,
                       Set<String> allowed, List<String> notes) {
        return executeTool(mcpClient, toolName, arguments, allowed, notes, null);
    }

    /**
     * The same with the thread's identity: for a call onto the board the runner attaches it after
     * the model's arguments, so the board sees who is speaking - the model can neither omit the
     * identity nor put a sibling's in its place (docs/subagent.md §6).
     *
     * @param caller this thread's address, or {@code null} when the batch runs without a board
     */
    String executeTool(McpSyncClient mcpClient, String toolName, String arguments,
                       Set<String> allowed, List<String> notes, AgentId caller) {
        if (FORK_TOOL_NAME.equals(toolName)) {
            notes.add("refused a nested " + FORK_TOOL_NAME);
            return FORK_REFUSAL;
        }
        if (toolName.isEmpty()) {
            notes.add("refused a tool call without a name");
            return "Refused: the tool call names no tool.";
        }
        if (!allowed.contains(toolName)) {
            notes.add("refused " + toolName + " (not in the tools of this conversation)");
            return "Refused: '" + toolName + "' is not part of the tools of this conversation."
                    + " Finish and report with what you have.";
        }
        if (mcpClient == null) {
            notes.add("refused " + toolName + " (no tool endpoint configured)");
            return "Refused: this child has no tool endpoint - neither agent.fork.mcp.url nor the"
                    + " loopback base URL of this conversation is known. Answer from the conversation"
                    + " you see and report.";
        }
        Map<String, Object> argumentMap = toArgumentMap(arguments);
        if (caller != null && AgentBoardTool.NAME.equals(toolName)) {
            // Whatever the model put under the key, the runner's value wins - and only this tool
            // grows an argument, no other call of the thread carries something it cannot explain.
            argumentMap = new java.util.LinkedHashMap<>(argumentMap);
            argumentMap.put(AgentBoardTool.FORK_TAG_ARGUMENT, caller.tag());
        }
        try {
            McpSchema.CallToolResult result = mcpClient.callTool(
                    new McpSchema.CallToolRequest(toolName, argumentMap));
            StringBuilder text = new StringBuilder();
            if (result.content() != null) {
                for (McpSchema.Content content : result.content()) {
                    if (content instanceof McpSchema.TextContent textContent) {
                        text.append(textContent.text());
                    }
                }
            }
            if (Boolean.TRUE.equals(result.isError())) {
                notes.add(toolName + " answered with isError");
            }
            return text.isEmpty() ? "(the tool returned no text)" : text.toString();
        } catch (RuntimeException e) {
            String detail = describeToolFailure(toolName, e);
            LOG.warn("Child tool '{}' failed: {} ({})", toolName, e.toString(), detail);
            notes.add(toolName + " failed: " + detail);
            return "Tool call failed: " + detail;
        }
    }

    /**
     * Text describing a failed tool call of a child. The MCP SDK keeps a generic sentence in the
     * error's {@code message} and puts the actual complaint into {@code data}: an unknown tool
     * arrives as "Unknown tool: invalid_tool_name" while the thread had asked for
     * {@code get_datetime}. Reading only the message sends whoever debugs this down the wrong
     * path, so {@code data} wins and the requested name is always named.
     *
     * @param toolName the tool this thread asked for
     * @param e        the failure
     * @return a one-line description for the tool result and the notes
     */
    private static String describeToolFailure(String toolName, RuntimeException e) {
        String detail = null;
        if (e instanceof McpError mcpError) {
            McpSchema.JSONRPCResponse.JSONRPCError rpcError = mcpError.getJsonRpcError();
            if (rpcError != null) {
                Object data = rpcError.data();
                detail = (data != null && !data.toString().isBlank()) ? data.toString() : rpcError.message();
            }
        }
        if (detail == null || detail.isBlank()) {
            detail = (e.getMessage() == null) ? e.getClass().getSimpleName() : e.getMessage();
        }
        if (detail.indexOf(toolName) < 0) {
            detail = detail + " (this thread asked for '" + toolName + "')";
        }
        return detail;
    }

    /**
     * Builds the tail that turns the frozen prefix into a child conversation (docs/webui.md §3.3).
     *
     * <p>Three shapes, selected by {@code agent.fork.tail}. <b>{@code plain}</b> (the default) is a
     * single user message: a header identical for every thread, then this thread's task.
     * <b>{@code user}</b> reconstructs the assistant's fork call, answers it with an identical
     * acknowledgement and follows with the task as a user message. <b>{@code tool}</b> reconstructs
     * the call and puts the task in its result. The last two are kept because they are what §3.3
     * planned and because the next measurement compares against them - the two runs that had them
     * both produced a thread that debugged the fork instead of doing its task, which is what moved
     * {@code plain} to the front.</p>
     *
     * <p>Package-private instead of private so the unit test can look at the tail: whatever the
     * shape, the part before the divergence has to be identical for every child.</p>
     *
     * @param batchId            batch label, part of the reconstructed call id
     * @param tailArguments      {@code arguments} JSON naming every task of the batch
     * @param task               this child's task
     * @param commonInstructions text that applies to every child, may be null
     * @return the messages to append, in order
     */
    List<ObjectNode> tailMessages(String batchId, String tailArguments, Task task,
                                  String commonInstructions) {
        return tailMessages(tailStyle, jsonMapper, batchId, tailArguments, task, commonInstructions);
    }

    /**
     * The same construction, reachable without a runner: {@code PrefixForkTool} logs the instruction
     * each thread is about to receive, and that log line has to come from the code that builds it -
     * a second copy would be a second truth.
     *
     * @param tailStyle          {@code plain}, {@code user} or {@code tool}
     * @param jsonMapper         mapper for the nodes
     * @param batchId            batch label, part of the reconstructed call id
     * @param tailArguments      {@code arguments} JSON naming every task of the batch
     * @param task               this child's task
     * @param commonInstructions text that applies to every child, may be null
     * @return the messages to append, in order
     */
    static List<ObjectNode> tailMessages(String tailStyle, JsonMapper jsonMapper, String batchId,
                                         String tailArguments, Task task, String commonInstructions) {
        if ("plain".equals(tailStyle)) {
            // The default: no reconstructed call at all. Measured over three runs (b-fc24, b-f71a,
            // b-4199) that whatever the thread is told about the fork - a call it never made, a
            // result holding an instruction, or a paragraph explaining the round - it examines that
            // instead of the task, because examining the fork is exactly what the inherited turns
            // show the assistant doing. So this shape says the minimum that gives the thread its
            // place (Child n) and its job, and nothing that could serve as a subject (docs §3.3).
            ObjectNode plainChild = jsonMapper.createObjectNode();
            plainChild.put("role", "user");
            plainChild.put("content", childInstruction(jsonMapper, tailArguments, task, commonInstructions));
            return List.of(plainChild);
        }

        String callId = TAIL_CALL_ID_PREFIX + batchId;
        ObjectNode assistant = jsonMapper.createObjectNode();
        assistant.put("role", "assistant");
        ObjectNode toolCall = assistant.putArray("tool_calls").addObject();
        toolCall.put("id", callId);
        toolCall.put("type", "function");
        ObjectNode function = toolCall.putObject("function");
        function.put("name", FORK_TOOL_NAME);
        function.put("arguments", tailArguments);

        String instruction = childInstruction(jsonMapper, tailArguments, task, commonInstructions);

        ObjectNode child = jsonMapper.createObjectNode();
        if ("tool".equals(tailStyle)) {
            // The two-message form: the task arrives as the <em>result</em> of the fork call. Kept as
            // an option, not as the default - see the class javadoc: a tool result is read as an
            // observation the assistant already made, and the first measured run (b-fc24) showed the
            // child finishing the parent's in-flight sentence instead of starting its task.
            child.put("role", "tool");
            child.put("tool_call_id", callId);
            child.put("content", instruction);
            return List.of(assistant, child);
        }

        // The {@code user} shape: the fork call is answered by a short acknowledgement that is
        // <em>identical for every child</em> (so it stays inside the shared prefix and keeps the
        // assistant's tool call properly answered), and the task follows as a user message - addressed
        // to the assistant, which is the position where an instruction outranks the example that the
        // inherited turns always are. Weaker than {@code plain} only in that it still asks the thread
        // to accept a reconstructed call as its own past.
        ObjectNode ack = jsonMapper.createObjectNode();
        ack.put("role", "tool");
        ack.put("tool_call_id", callId);
        ack.put("content", "prefix_fork accepted this batch. Each task runs in its own thread and"
                + " reports back to the conversation that called the tool; nothing is shared with a"
                + " sibling. The task of this thread follows as the next message.");
        child.put("role", "user");
        child.put("content", instruction);
        return List.of(assistant, ack, child);
    }

    /**
     * What a child is told. Two sentences and the task: that this conversation was forked here, that
     * it is <b>Child <i>n</i></b>, and that it carries out only the task that follows.
     *
     * <p>Deliberately nothing else. The earlier version explained the arrangement - the round, the
     * siblings, the report format, a prohibition on looking at {@code target/fork} - and every one of
     * those sentences was a fresh object for a model whose inherited turns consist of examining such
     * objects: measured in {@code b-4199}, a child read its own instruction as an artifact of the
     * experiment and analysed it instead of working. How a report should look is the caller's business
     * and belongs in {@code commonInstructions}; the mechanism belongs in the prompt nowhere.</p>
     *
     * <p>The child number is kept for a reason beyond this run: it is the handle threads would be
     * addressed by if they ever get a message board. Nothing reads it today.</p>
     *
     * @param jsonMapper         mapper for the arguments JSON
     * @param tailArguments      {@code arguments} JSON naming every task of the round
     * @param task               this child's task
     * @param commonInstructions what the caller wants every child to have, may be null
     * @return the content of the child's instruction message
     */
    private static String childInstruction(JsonMapper jsonMapper, String tailArguments, Task task,
                                           String commonInstructions) {
        StringBuilder text = new StringBuilder();
        text.append("This conversation has been forked at this point; you are Child ")
                .append(childIndexOf(jsonMapper, tailArguments, task.id()))
                .append(" and you carry out only the following task.\n\n")
                .append("task: ").append(task.instruction() == null ? "" : task.instruction());
        if (commonInstructions != null && !commonInstructions.isBlank()) {
            text.append("\n\n").append(commonInstructions.strip());
        }
        return text.toString();
    }

    /**
     * @param jsonMapper    mapper for the arguments JSON
     * @param tailArguments the {@code arguments} JSON of the round
     * @param taskId        this child's task id
     * @return the 1-based position of that task within the round, 1 when it cannot be determined
     */
    private static int childIndexOf(JsonMapper jsonMapper, String tailArguments, String taskId) {
        try {
            JsonNode tasks = jsonMapper.readTree(tailArguments).path("tasks");
            for (int position = 0; position < tasks.size(); position++) {
                if (taskId != null && taskId.equals(tasks.get(position).path("id").asString(""))) {
                    return position + 1;
                }
            }
        } catch (RuntimeException e) {
            LOG.debug("Child number not readable from the tail arguments: {}", e.getMessage());
        }
        return 1;
    }

    /**
     * @param snapshot snapshot to read
     * @return the names of the tools the parent conversation offered (execution-time permission list)
     */
    static Set<String> toolNamesOf(ContextSnapshot snapshot) {
        Set<String> names = new HashSet<>();
        JsonNode tools = snapshot.body().path("tools");
        if (tools.isArray()) {
            for (JsonNode tool : tools) {
                String name = tool.path("function").path("name").asString("");
                if (name.isEmpty()) {
                    name = tool.path("name").asString("");
                }
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /**
     * Converts the {@code function.arguments} string of a tool call into the map the MCP client
     * expects. Invalid JSON becomes an empty argument map - the tool then answers with its own
     * complaint, which is what the model would have seen in the parent conversation too.
     *
     * @param arguments JSON object as a string, may be blank
     * @return the argument map
     */
    Map<String, Object> toArgumentMap(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode node = jsonMapper.readTree(arguments);
            if (node != null && node.isObject()) {
                return toMap(unwrapDoubledArguments(node));
            }
            LOG.debug("Tool arguments are no JSON object: {}", arguments);
        } catch (RuntimeException e) {
            LOG.debug("Tool arguments cannot be parsed ({}): {}", e.getMessage(), arguments);
        }
        return Map.of();
    }

    /**
     * Unwraps arguments a child wrapped one layer too deep: instead of
     * {@code {"script": "…"}} a thread produced {@code {"arguments": "{\"script\": \"…\"}"}} and the
     * tool answered "Provide at least one of 'script'". Observed in run b-f71a on three of five calls
     * of both children, and the shape explains itself: the thread's own conversation begins with an
     * assistant message whose {@code function.arguments} is a JSON <em>string</em>, and a model that
     * has just read that structure tends to reproduce it one level in. The plain tail removes the
     * imitation; this catches what is left of it.
     *
     * @param node the parsed {@code function.arguments} object
     * @return the node itself, or the object its single {@code arguments} field holds
     */
    private JsonNode unwrapDoubledArguments(JsonNode node) {
        if (node.size() != 1 || !node.has("arguments")) {
            return node;
        }
        JsonNode inner = node.get("arguments");
        if (inner.isObject()) {
            return inner;
        }
        if (inner.isString()) {
            try {
                JsonNode parsed = jsonMapper.readTree(inner.asString());
                if (parsed != null && parsed.isObject()) {
                    LOG.debug("Unwrapped doubled tool arguments of a child thread");
                    return parsed;
                }
            } catch (RuntimeException e) {
                LOG.debug("Doubled tool arguments are no JSON either: {}", e.getMessage());
            }
        }
        return node;
    }

    /**
     * Converts a JSON object node into the plain map the MCP client expects. Written out instead of
     * using {@code convertValue} with a raw type, so nothing relies on an unchecked cast.
     *
     * @param node object node
     * @return the map it holds
     */
    private static Map<String, Object> toMap(JsonNode node) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            map.put(entry.getKey(), toValue(entry.getValue()));
        }
        return map;
    }

    /**
     * @param node any JSON node
     * @return the equivalent Java value: map, list, String, Boolean, number or {@code null}
     */
    private static Object toValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            return toMap(node);
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode child : node) {
                list.add(toValue(child));
            }
            return list;
        }
        if (node.isString()) {
            return node.asString();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        return node.asString();
    }

    /** @param batchId batch label @param taskId task id @return path of the transcript of the child */
    private Path transcriptFile(String batchId, String taskId) {
        return artifactDir.resolve(batchId).resolve(taskId + ".jsonl");
    }

    /**
     * @param batchId batch label, part of the thread name
     * @return a factory of daemon threads, so a hung child cannot keep the JVM alive
     */
    private static ThreadFactory daemonFactory(String batchId) {
        int[] index = {0};
        return runnable -> {
            Thread thread = new Thread(runnable, "prefix-fork-" + batchId + "-" + (++index[0]));
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * JSONL transcript of one child: every message the child added beyond the frozen prefix, plus a
     * final line with status and usage. The full text lives here, which is what lets the tool report
     * cut the {@code report} field short.
     *
     * <p><b>Written once, at the end of the run.</b> Writing line by line was the first thought and
     * is what a batch of siblings must not do: the transcripts of one batch share a directory inside
     * the project, and a child that can list files can read its sibling's live transcript - the
     * promise "you cannot see your siblings" would be broken by the tool's own bookkeeping. (Seen in
     * the first real n=2 run: task t2 read <code>target/fork/&lt;batch&gt;/t1.jsonl</code>.) After
     * the batch the files are ordinary artifacts of a finished run; during it there is nothing to
     * find. The cost of buffering is one serialized copy of what the child already holds in memory.</p>
     */
    private final class Transcript {

        /** Target file of this child. */
        private final Path file;
        /** Set once the file actually exists, so a failed write is not reported as an artifact. */
        private boolean written = false;

        private Transcript(Path file) {
            this.file = file;
        }

        /**
         * Writes the child's own messages and the closing line, in one go.
         *
         * @param messages  the whole conversation of the child
         * @param fromIndex index of the first message this child added (after the frozen history)
         * @param status    final status of the run
         * @param usage     token bookkeeping of the run
         */
        private void write(List<ObjectNode> messages, int fromIndex, String status, Usage usage) {
            StringBuilder text = new StringBuilder();
            for (int i = Math.max(0, fromIndex); i < messages.size(); i++) {
                try {
                    text.append(jsonMapper.writeValueAsString(messages.get(i))).append('\n');
                } catch (RuntimeException e) {
                    LOG.debug("Fork transcript line dropped ({}): {}", file, e.getMessage());
                }
            }
            ObjectNode last = jsonMapper.createObjectNode();
            last.put("event", "result");
            last.put("status", status);
            last.put("calls", usage.calls());
            last.put("promptTokens", usage.promptTokens());
            last.put("cachedTokens", usage.cachedTokens());
            last.put("cachedTokensKnown", usage.cachedTokensKnown());
            last.put("completionTokens", usage.completionTokens());
            last.put("ms", usage.ms());
            try {
                text.append(jsonMapper.writeValueAsString(last)).append('\n');
            } catch (RuntimeException e) {
                LOG.debug("Fork transcript result line dropped ({}): {}", file, e.getMessage());
            }
            try {
                Path parent = file.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(file, text.toString(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException | RuntimeException e) {
                LOG.warn("Fork transcript {} cannot be written: {}", file, e.getMessage());
                return;
            }
            written = true;
            LOG.debug("Fork transcript written: {} ({} bytes)", file, text.length());
        }

        /** @return the path relative to the project directory, for the report; null if unwritten */
        private String pathAsString() {
            if (!written) {
                return null;
            }
            Path base = Paths.get(System.getProperty("IDE_PROJECT_DIR", ".")).toAbsolutePath().normalize();
            try {
                return base.relativize(file).toString().replace('\\', '/');
            } catch (IllegalArgumentException e) {
                // Reachable when artifactDir is absolute or escapes the project with ".." (the same
                // case PrefixForkTool.writeBoardArtifact now warns about): the file exists, but
                // outside the project - said out loud, absolute path as the reference.
                LOG.warn("Fork transcript {} lies outside the project directory; reporting its"
                        + " absolute path", file);
                return file.toString();
            }
        }
    }
}