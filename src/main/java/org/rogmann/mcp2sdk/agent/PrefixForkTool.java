package org.rogmann.mcp2sdk.agent;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import org.rogmann.mcp2sdk.ToolSpecWithState;
import org.rogmann.mcp2sdk.ToolState;
import org.rogmann.mcp2sdk.agent.ChatContextStore.ContextSnapshot;
import org.rogmann.mcp2sdk.agent.SubAgentRunner.Outcome;
import org.rogmann.mcp2sdk.agent.SubAgentRunner.Task;
import org.rogmann.mcp2sdk.agent.SubAgentRunner.Usage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * MCP tool {@code prefix_fork}: takes 1..4 tasks the model is starting anyway and runs them in
 * their own threads, each beginning at the <em>unmodified prefix</em> of the main chat, so the
 * engine reuses the KV blocks it has already computed (docs/webui.md §3).
 *
 * <p>What comes back is one tool result with a short report per task - the reading that a fork is
 * supposed to do <em>for</em> the main window stays out of that window. The reports are claims, and
 * the {@code usage} block is the receipt: {@code cachedTokens / promptTokens} near 1 is the proof
 * that the prefix was reused, a value near 0 means the premise of this tool is not holding and
 * docs/webui.md §3.3 (digest diagnosis) is the way to find out why.</p>
 *
 * <p>Scope of v1 is exactly the plan's: synchronous, no resume, no file lock, no fork inside a
 * fork, no per-thread model, no UI view of running threads.</p>
 */
public class PrefixForkTool {

    private static final Logger LOG = LoggerFactory.getLogger(PrefixForkTool.class);

    /** MCP name of the tool. */
    public static final String NAME = SubAgentRunner.FORK_TOOL_NAME;

    /**
     * MCP name of the JavaScript tool, named here because it is the second way a thread can reach
     * the board (through the {@code board} module, docs/subagent.md §14) and the warning about an
     * unreachable board has to be true about both.
     */
    static final String JAVASCRIPT_TOOL_NAME = "javascript_tool";

    /** What the model sees. The order is the order of docs/webui.md §3.4 (1..4), then the "when not". */
    private static final String DESCRIPTION = """
            Starts one to four short-lived threads, each beginning at the current prefix of this \
            conversation, and returns all reports in a single tool result.
            (1) A thread sees this conversation frozen exactly up to here: describe what is to be done \
            from here on, never repeat context. And know that it reads the last turns as an example of \
            what to do - if the conversation was just exploring something, the thread keeps exploring \
            unless you name its first tool call.
            (2) A thread cannot ask back and cannot see its siblings. A task that needs a decision from \
            you is not a task, and a wrong premise is then produced by every thread.
            (3) Put every task of one round into ONE call - a second call pays for the prefix again.
            (4) A report is a claim, not a fact: verifying it stays your job. Use the 'file:line' \
            references and the 'rejected:' lines to do that cheaply.
            (5) With "board": true the threads share a message board for this run: they post findings \
            and comments, the result carries a digest (docs/subagent.md). Off by default.
            Use it for independent, read-mostly work that differs only below facts you already share. \
            Do not use it for sequential steps, for a single lookup, for a number that \
            javascript_tool would count exactly, or for threads that would edit the same file. \
            It saves window space and wall-clock, not tokens: a thread generates what you would have \
            generated, plus its report.""";

    // --- Configuration properties (system properties, as in CallLlmTool) ---
    /**
     * MCP endpoint the children run their tool calls against. Empty (the default) means: <b>this
     * process' own server</b>, addressed through the base URL the snapshot was recorded from - the
     * same loopback the completion itself uses. A fork whose children have no tools is not a fork,
     * and the right server is known without configuration (docs/webui.md §3.5).
     */
    static final String PROP_MCP_URL = "agent.fork.mcp.url";
    /** Fallback for {@value #PROP_MCP_URL}, before the loopback default. */
    static final String PROP_MCP_URL_FALLBACK = "llmtool.mcp.url";
    /** Highest {@code maxItems} of the schema, and the number of tasks one call may carry. */
    static final String PROP_MAX_TASKS = "agent.fork.maxTasks";
    /** Default tool-call budget per thread. */
    static final String PROP_MAX_TOOL_CALLS = "agent.fork.maxToolCalls";
    /** Default report length per thread, in characters. */
    static final String PROP_REPORT_MAX_CHARS = "agent.fork.reportMaxChars";
    /** Default deadline per thread, in seconds. */
    static final String PROP_DEADLINE_SECONDS = "agent.fork.deadlineSeconds";
    /** Wall-clock ceiling of the whole call, in seconds; must stay below the MCP client timeout. */
    static final String PROP_BATCH_DEADLINE_SECONDS = "agent.fork.batchDeadlineSeconds";
    /** How the child instruction is attached: {@code tool} (default) or {@code user}. */
    static final String PROP_TAIL = "agent.fork.tail";
    /** Directory of the transcripts, relative to the project directory. */
    static final String PROP_ARTIFACT_DIR = "agent.fork.artifactDir";
    /** Conversation key whose snapshot to fork from. */
    static final String PROP_CONVERSATION = "agent.fork.conversation";

    /** Hard ceiling of {@code deadlineSeconds} of one call (docs/webui.md §3.4). */
    private static final int MAX_DEADLINE_SECONDS = 270;

    /** tool state (active-flag, statistics) */
    private final ToolState state;

    private final int maxTasks;
    private final int defaultMaxToolCalls;
    private final int defaultReportMaxChars;
    private final int defaultDeadlineSeconds;
    private final long batchDeadlineMillis;
    private final String mcpUrl;
    private final String tailStyle;
    private final String artifactDir;
    private final String conversationKey;

    /** Mapper for the schema-independent JSON of arguments and result. */
    private final JsonMapper jsonMapper;

    private PrefixForkTool(int maxTasks, int defaultMaxToolCalls, int defaultReportMaxChars,
                           int defaultDeadlineSeconds, int batchDeadlineSeconds, String mcpUrl,
                           String tailStyle, String artifactDir, String conversationKey) {
        this.state = new ToolState();
        this.jsonMapper = JsonMapper.builder().build();
        this.maxTasks = maxTasks;
        this.defaultMaxToolCalls = defaultMaxToolCalls;
        this.defaultReportMaxChars = defaultReportMaxChars;
        this.defaultDeadlineSeconds = defaultDeadlineSeconds;
        this.batchDeadlineMillis = Math.max(1_000L, batchDeadlineSeconds * 1_000L);
        this.mcpUrl = mcpUrl;
        this.tailStyle = tailStyle;
        this.artifactDir = artifactDir;
        this.conversationKey = conversationKey;
    }

    /**
     * @return the tool specification and its state
     */
    public static ToolSpecWithState createToolInstance() {
        int maxTasks = Math.max(1, Integer.getInteger(PROP_MAX_TASKS, 4));
        int defaultMaxToolCalls = Math.max(1, Integer.getInteger(PROP_MAX_TOOL_CALLS, 12));
        int defaultReportMaxChars = clamp(Integer.getInteger(PROP_REPORT_MAX_CHARS, 2000), 200, 4000);
        int defaultDeadlineSeconds = clamp(Integer.getInteger(PROP_DEADLINE_SECONDS, 300), 10, MAX_DEADLINE_SECONDS);
        int batchDeadlineSeconds = Math.max(defaultDeadlineSeconds,
                Integer.getInteger(PROP_BATCH_DEADLINE_SECONDS, 240));
        String mcpUrl = System.getProperty(PROP_MCP_URL, System.getProperty(PROP_MCP_URL_FALLBACK));
        String tailStyle = System.getProperty(PROP_TAIL, "plain");
        String artifactDir = System.getProperty(PROP_ARTIFACT_DIR, SubAgentRunner.DEFAULT_ARTIFACT_DIR);
        String conversationKey = System.getProperty(PROP_CONVERSATION,
                ChatContextStore.DEFAULT_CONVERSATION_ID);

        PrefixForkTool tool = new PrefixForkTool(maxTasks, defaultMaxToolCalls, defaultReportMaxChars,
                defaultDeadlineSeconds, batchDeadlineSeconds, mcpUrl, tailStyle, artifactDir, conversationKey);
        LOG.info("prefix_fork ready: maxTasks={}, mcpUrl={},"
                        + " tail={}, artifacts={}, batch deadline {} s",
                maxTasks, (mcpUrl == null || mcpUrl.isBlank() ? "(own server)" : mcpUrl),
                tailStyle, artifactDir, batchDeadlineSeconds);

        return new ToolSpecWithState(McpServerFeatures.SyncToolSpecification.builder()
                        .tool(buildToolSpec(maxTasks))
                        .callHandler(tool::call)
                        .build(),
                tool.state);
    }

    /**
     * @param maxTasks schema ceiling of the {@code tasks} array (decision F8: one call carries all
     *                 tasks of a round, because a second call pays for the prefix a second time)
     * @return the MCP tool declaration
     */
    static McpSchema.Tool buildToolSpec(int maxTasks) {
        Map<String, Object> idProp = new HashMap<>();
        idProp.put("type", "string");
        idProp.put("description", "Short label of the task, used in the report. Defaults to t1, t2, ...");

        Map<String, Object> instructionProp = new HashMap<>();
        instructionProp.put("type", "string");
        instructionProp.put("description", "What to do and in which form to report. No context "
                + "repetition: the thread sees the conversation up to here.");

        Map<String, Object> maxToolCallsProp = new HashMap<>();
        maxToolCallsProp.put("type", "integer");
        maxToolCallsProp.put("minimum", 1);
        maxToolCallsProp.put("maximum", 20);
        maxToolCallsProp.put("description", "Tool-call budget of this thread (default 12).");

        Map<String, Object> reportMaxCharsProp = new HashMap<>();
        reportMaxCharsProp.put("type", "integer");
        reportMaxCharsProp.put("minimum", 200);
        reportMaxCharsProp.put("maximum", 4000);
        reportMaxCharsProp.put("description", "How long the report of this thread may get (default 2000).");

        Map<String, Object> taskProps = new HashMap<>();
        taskProps.put("id", idProp);
        taskProps.put("instruction", instructionProp);
        taskProps.put("maxToolCalls", maxToolCallsProp);
        taskProps.put("reportMaxChars", reportMaxCharsProp);

        Map<String, Object> taskItem = new HashMap<>();
        taskItem.put("type", "object");
        taskItem.put("properties", taskProps);
        taskItem.put("required", List.of("instruction"));

        Map<String, Object> tasksProp = new HashMap<>();
        tasksProp.put("type", "array");
        tasksProp.put("minItems", 1);
        tasksProp.put("maxItems", maxTasks);
        tasksProp.put("items", taskItem);
        tasksProp.put("description", "All tasks of this round, 1 to " + maxTasks + ". One call per round.");

        Map<String, Object> commonProp = new HashMap<>();
        commonProp.put("type", "string");
        commonProp.put("description", "Applies to every thread: report format, language.");

        Map<String, Object> deadlineProp = new HashMap<>();
        deadlineProp.put("type", "integer");
        deadlineProp.put("minimum", 10);
        deadlineProp.put("maximum", MAX_DEADLINE_SECONDS);
        deadlineProp.put("description", "Deadline per thread in seconds (default 180). A thread that "
                + "reaches it reports what has.");

        Map<String, Object> boardProp = new HashMap<>();
        boardProp.put("type", "boolean");
        boardProp.put("default", false);
        boardProp.put("description", "Give the threads a message board for this run (docs/subagent.md):"
                + " they can post entries and comments, and the result carries a digest. Reachable"
                + " through the tool 'agent_board', or - when the server runs with"
                + " -Dagent.fork.board.js=true - through the 'board' module of javascript_tool;"
                + " useless unless one of both is in this conversation. Off by default.");

        Map<String, Object> properties = new HashMap<>();
        properties.put("tasks", tasksProp);
        properties.put("commonInstructions", commonProp);
        properties.put("deadlineSeconds", deadlineProp);
        properties.put("board", boardProp);

        JsonSchema inputSchema = new JsonSchema("object", properties, List.of("tasks"), null, null, null);
        return McpSchema.Tool.builder()
                .name(NAME)
                .description(DESCRIPTION)
                .inputSchema(inputSchema)
                .build();
    }

    /**
     * Handles one {@code prefix_fork} call.
     *
     * @param exchange the server exchange: its session id is recorded with the board, and that is
     *                 the one session {@code agent_board} refuses - the caller reads the board in
     *                 the result, not through the tool
     * @param request  the tool-call request
     * @return one JSON document with the batch header and a block per task
     */
    CallToolResult call(McpSyncServerExchange exchange, CallToolRequest request) {
        state.callCount().incrementAndGet();
        Map<String, Object> arguments = (request.arguments() == null) ? Map.of() : request.arguments();
        JsonNode args = jsonMapper.valueToTree(arguments);

        List<String> ignored = ignoredArguments(args);
        JsonNode tasksNode = args.get("tasks");
        if (tasksNode == null || !tasksNode.isArray() || tasksNode.isEmpty()) {
            return errorResult("prefix_fork needs a non-empty 'tasks' array, each entry with an"
                    + " 'instruction'. Nothing was started.");
        }
        if (tasksNode.size() > maxTasks) {
            return errorResult("prefix_fork takes at most " + maxTasks + " tasks per call (got "
                    + tasksNode.size() + "). Nothing was started - a call that is refused still costs"
                    + " a turn, so fix the arguments instead of splitting the round.");
        }
        String callerConversation = (exchange == null) ? null
                : McpConversations.getInstance().lookup(exchange.sessionId());
        ContextSnapshot snapshot = resolveSnapshot(callerConversation);
        ContextSnapshot newest = ChatContextStore.getInstance().latestAny();
        if (snapshot == null) {
            // Nothing human to start from. Said before the nested-fork case, because the two look
            // alike from here and are not the same finding: with no root snapshot there is no
            // "newer than the human turn" to compare against, and the honest statement is that this
            // conversation's requests never reached the store - expired (TTL), another port, another
            // process. A child of an earlier batch may well be the newest entry then; naming it is a
            // hint, not a diagnosis. (Found by a forked thread in run b-4199, which had reached this
            // branch through its own nested call and misread the message as a false refusal.)
            LOG.info("prefix_fork refused: no human snapshot, newest entry is {}",
                    (newest == null) ? "none" : newest.label() + " at depth " + newest.forkDepth());
            return errorResult("prefix_fork found no snapshot of a human request to start from, so"
                    + " nothing was started. The store" + (newest == null ? " is empty"
                        : " holds " + newest.label() + " at fork depth " + newest.forkDepth()
                          + " as its newest entry") + " - a completion request of this conversation"
                    + " has not been recorded here (it is remembered by agent/ChatContextStore when"
                    + " the request arrives). Check that the chat runs against this process"
                    + " (server.port) and that at least one turn has been answered; entries older than"
                    + " the store's TTL are gone, and a thread of a fork cannot fork either way.");
        }
        if (newest != null && newest.forkDepth() > 0 && newest.seq() > snapshot.seq()) {
            // Decision F7, second net. The first one is the execution-time refusal in
            // SubAgentRunner.executeTool, which stops a child's prefix_fork call before it ever
            // reaches this server; if one arrives anyway, a *child* request being newer than the
            // newest human request is the trace of it. A human turn always records itself before its
            // own tool call runs, so this cannot fire in normal use.
            LOG.info("prefix_fork refused: the newest recorded request belongs to a forked thread"
                    + " ({}, depth {})", newest.label(), newest.forkDepth());
            return errorResult(SubAgentRunner.FORK_REFUSAL + " (the newest recorded request is "
                    + newest.label() + " at depth " + newest.forkDepth() + ", no human turn after it)");
        }
        List<Task> tasks = readTasks(tasksNode);
        if (tasks.isEmpty()) {
            return errorResult("prefix_fork found no usable task: every entry of 'tasks' needs a"
                    + " non-blank 'instruction'.");
        }
        String commonInstructions = textOrNull(args.get("commonInstructions"));
        int deadlineSeconds = clamp(args.path("deadlineSeconds").asInt(defaultDeadlineSeconds),
                10, MAX_DEADLINE_SECONDS);
        long taskDeadlineMillis = Math.min(batchDeadlineMillis, deadlineSeconds * 1_000L);
        String batchId = newBatchId();

        // The board is the caller's opt-in (decision B6): only this call knows whether the tasks
        // are separate enough to stay silent or could use a shared surface. The seeded entries are
        // the round's tasks; the threads' own findings are posted while they run (docs/subagent.md).
        AgentBoard board = null;
        String boardWarning = null;
        if (args.path("board").asBoolean(false)) {
            board = new AgentBoard(batchId, exchange.sessionId());
            for (Task task : tasks) {
                board.seed(task.id(), task.instruction());
            }
            AgentBoard.register(board);
            // F6 makes the parent's tool list the children's permission list, and the board has two
            // ways into it: the tool 'agent_board', and the 'board' JavaScript module (reachable
            // through javascript_tool, docs/subagent.md §14). Neither present means the threads get
            // a board they cannot touch - said out loud, like the missing tools[] above, instead of
            // an empty digest to wonder about.
            java.util.Set<String> threadTools = SubAgentRunner.toolNamesOf(snapshot);
            boolean boardToolReachable = threadTools.contains(AgentBoardTool.NAME);
            boolean boardModuleReachable = AgentBoardAccess.isJsSurfaceOn()
                    && threadTools.contains(JAVASCRIPT_TOOL_NAME);
            if (!boardToolReachable && !boardModuleReachable) {
                boardWarning = "board:true was set, but the frozen request carries neither the tool '"
                        + AgentBoardTool.NAME + "' nor ('javascript_tool' with -D"
                        + AgentBoardAccess.PROP_JS_SURFACE + "=true'); the board stays out of the"
                        + " threads' reach. Activate one of them and start a new turn for it to be"
                        + " in tools[].";
            }
            LOG.info("prefix_fork {}: board on (tool {}, JavaScript module {})", batchId,
                    boardToolReachable ? "reachable" : "not in tools[]",
                    boardModuleReachable ? "reachable" : "not reachable");
        }

        String mcpEndpoint = (mcpUrl == null || mcpUrl.isBlank()) ? snapshot.selfBaseUrl() : mcpUrl;
        LOG.info("prefix_fork {}: {} task(s) from {}, deadline {} s, batch ceiling {} ms, tools via {}",
                batchId, tasks.size(), snapshot.label(), deadlineSeconds, batchDeadlineMillis,
                mcpEndpoint);
        String tailArguments = tailArgumentsOf(tasks, commonInstructions);
        // What each thread is actually asked to do, in the message shape it arrives in. Logged with
        // the same builder the runner uses, so the line cannot disagree with what is sent. Without it
        // a run like b-fc24 leaves only the thread's own (mis)reading as evidence of the shape.
        for (Task task : tasks) {
            List<ObjectNode> tail = SubAgentRunner.tailMessages(
                    tailStyle, jsonMapper, batchId, tailArguments, task, commonInstructions);
            ObjectNode addressed = tail.get(tail.size() - 1);
            LOG.info("prefix_fork {} task '{}': instruction as role={} -> {}", batchId, task.id(),
                    addressed.path("role").asString(), oneLine(addressed.path("content").asString()));
        }
        long wallStart = System.currentTimeMillis();
        List<Outcome> outcomes;
        try {
            SubAgentRunner runner = new SubAgentRunner(mcpEndpoint, tailStyle, artifactDir,
                    batchDeadlineMillis, board);
            outcomes = runner.run(batchId, snapshot, tasks, commonInstructions,
                    tailArguments, taskDeadlineMillis);
        } catch (RuntimeException e) {
            // §3.5 "Fehler": never an exception as the tool result. The caller has to see which task
            // failed, and a batch that broke as a whole still reports one block per task.
            LOG.error("prefix_fork " + batchId + ": the batch broke, reporting per task", e);
            outcomes = new ArrayList<>();
            for (Task task : tasks) {
                outcomes.add(new Outcome(task.id(), SubAgentRunner.STATUS_ERROR, "", null, null,
                        List.of("the fork broke before this task reported: " + e)));
            }
        } finally {
            // No thread is running any more, so no session needs to remember being one (P0.2). The
            // registry expires on its own too; this is what makes "the batch owns it, then nobody"
            // true without waiting for a clock.
            ForkIdentity.getInstance().removeBatch(batchId);
        }
        long wallMs = System.currentTimeMillis() - wallStart;

        // The board ends with the batch - on this path too, not only when everything went well.
        // It is written to disk once, here, never while the threads run (the transcripts' rule).
        ObjectNode boardDigest = null;
        if (board != null) {
            board.freeze();
            AgentBoard.remove(batchId);
            boardDigest = board.digest(jsonMapper);
            String boardArtifact = writeBoardArtifact(batchId, board.artifact(jsonMapper));
            if (boardArtifact != null) {
                boardDigest.put("artifact", boardArtifact);
            }
        }

        state.callsOk().incrementAndGet();
        return CallToolResult.builder()
                .isError(false)
                .addTextContent(renderResult(batchId, wallMs, snapshot, outcomes, ignored,
                        boardWarning, boardDigest).toString())
                .build();
    }

    /**
     * @param args arguments of the call
     * @return the names of arguments this tool does not take, so the answer can say what was dropped
     *         (decision F13: context does not come from an argument). Package-private instead of
     *         private so the unit test asserts exactly that.
     */
    static List<String> ignoredArguments(JsonNode args) {
        List<String> ignored = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : args.properties()) {
            String name = entry.getKey();
            if (!"tasks".equals(name) && !"commonInstructions".equals(name)
                    && !"deadlineSeconds".equals(name) && !"board".equals(name)) {
                ignored.add(name);
            }
        }
        return ignored;
    }

    /**
     * @param tasksNode the {@code tasks} argument
     * @return the tasks, numbered {@code t1..tn} when the caller gave no ids
     */
    private List<Task> readTasks(JsonNode tasksNode) {
        List<Task> tasks = new ArrayList<>();
        Set<String> usedIds = new HashSet<>();
        int index = 0;
        for (JsonNode taskNode : tasksNode) {
            index++;
            String instruction = textOrNull(taskNode.get("instruction"));
            if (instruction == null) {
                LOG.warn("prefix_fork: task #{} has no instruction and is dropped", index);
                continue;
            }
            String id = textOrNull(taskNode.get("id"));
            if (id == null) {
                id = "t" + index;
            }
            // One transcript per task: two tasks under the same id would overwrite each other's
            // artifact, and the caller could no longer tell the two threads apart.
            String uniqueId = id;
            for (int suffix = 2; !usedIds.add(uniqueId); suffix++) {
                uniqueId = id + "-" + suffix;
            }
            int maxToolCalls = clamp(taskNode.path("maxToolCalls").asInt(defaultMaxToolCalls), 1, 20);
            int reportMaxChars = clamp(taskNode.path("reportMaxChars").asInt(defaultReportMaxChars), 200, 4000);
            tasks.add(new Task(uniqueId, instruction, maxToolCalls, reportMaxChars));
        }
        return tasks;
    }

    /**
     * The frozen prefix to start from: the newest <em>human</em> snapshot of the configured
     * conversation key, falling back to the newest human snapshot of any conversation (a call whose
     * MCP session cannot be tied to a conversation has no better key, and the local single-user
     * case is exactly one conversation).
     *
     * <p>An ACP session is tied to its conversation (S6): its MCP client carries the chat id in
     * {@code X-Conversation-Id}, the filter pairs it with the session, and {@code callerConversation}
     * is that pairing read back. The configured key stays the fallback for callers the filter does
     * not know (the web UI path).</p>
     *
     * <p>Child snapshots are skipped on purpose: a forked completion arrives at the same proxy and
     * is recorded too (under {@code "default"}, the runner sends no conversation header). Forking
     * from that would make the second fork of a chat inherit the first one's thread.</p>
     *
     * @param callerConversation the conversation of the calling MCP session, or {@code null} when
     *                           the filter observed none for it
     * @return the snapshot or {@code null}
     */
    private ContextSnapshot resolveSnapshot(String callerConversation) {
        ChatContextStore store = ChatContextStore.getInstance();
        String key = (callerConversation != null) ? callerConversation : conversationKey;
        ContextSnapshot snapshot = store.latestRoot(key);
        return (snapshot != null) ? snapshot : store.latestRootAny();
    }

    /**
     * Builds the {@code arguments} of the reconstructed tail: every task of the batch, so all task
     * descriptions are inside the shared prefix and the only divergence per child is its own
     * instruction (docs/webui.md §3.3).
     *
     * @param tasks              the tasks
     * @param commonInstructions shared instructions, may be null
     * @return the arguments as a JSON string
     */
    private String tailArgumentsOf(List<Task> tasks, String commonInstructions) {
        ObjectNode tail = jsonMapper.createObjectNode();
        ArrayNode taskArray = tail.putArray("tasks");
        for (Task task : tasks) {
            ObjectNode entry = taskArray.addObject();
            entry.put("id", task.id());
            entry.put("instruction", task.instruction());
        }
        if (commonInstructions != null) {
            tail.put("commonInstructions", commonInstructions);
        }
        return jsonMapper.writeValueAsString(tail);
    }

    /**
     * Assembles the single result document: a batch header, the prefix it was taken from (its
     * digests are the shortest way to the cause of a missing cache hit), and one block per task.
     *
     * @param batchId  batch label
     * @param wallMs   wall-clock duration of the batch
     * @param snapshot snapshot the children started from
     * @param outcomes one outcome per task
     * @param ignored  arguments that were dropped
     * @param boardWarning what kept the board out of the threads' reach, or null
     * @param boardDigest digest of the board, or null when the caller asked for none
     * @return the result JSON
     */
    private ObjectNode renderResult(String batchId, long wallMs, ContextSnapshot snapshot,
                                   List<Outcome> outcomes, List<String> ignored, String boardWarning,
                                   ObjectNode boardDigest) {
        ObjectNode root = jsonMapper.createObjectNode();
        ObjectNode fork = root.putObject("fork");
        fork.put("batch", batchId);
        fork.put("tasks", outcomes.size());
        fork.put("wallMs", wallMs);
        ObjectNode prefix = fork.putObject("prefix");
        prefix.put("messages", snapshot.messageCount());
        prefix.put("approxChars", snapshot.approxChars());
        prefix.put("snapshot", snapshot.label());
        prefix.put("digests", snapshot.digests());
        int toolCount = snapshot.body().path("tools").isArray() ? snapshot.body().path("tools").size() : 0;
        prefix.put("tools", toolCount);
        if (toolCount == 0) {
            // Decision F6 makes the parent's tool list the child's permission list. A conversation
            // that arrived without tools therefore leaves the children able to call nothing - worth
            // saying out loud, because otherwise the thread just reports that it could not work.
            fork.put("warning", "the frozen request carried no tools[], so the children cannot call"
                    + " any tool; they will answer from the conversation alone");
        }
        if (boardWarning != null) {
            fork.put("boardWarning", boardWarning);
        }
        if (boardDigest != null) {
            fork.set("board", boardDigest);
        }
        if (!ignored.isEmpty()) {
            ArrayNode ignoredNode = fork.putArray("ignoredArguments");
            ignored.forEach(ignoredNode::add);
            fork.put("ignoredReason", "'context' is not an argument of prefix_fork: a thread starts at"
                    + " the frozen prefix and nothing else, because an extra context argument would"
                    + " cost a turn in the parent window on every call.");
        }
        ArrayNode results = root.putArray("results");
        for (Outcome outcome : outcomes) {
            ObjectNode entry = results.addObject();
            entry.put("id", outcome.id());
            entry.put("status", outcome.status());
            entry.put("report", outcome.report());
            if (outcome.transcript() != null) {
                entry.put("transcript", outcome.transcript());
            }
            if (!outcome.notes().isEmpty()) {
                ArrayNode notes = entry.putArray("notes");
                outcome.notes().forEach(notes::add);
            }
            Usage usage = outcome.usage();
            if (usage != null) {
                ObjectNode usageNode = entry.putObject("usage");
                usageNode.put("calls", usage.calls());
                usageNode.put("promptTokens", usage.promptTokens());
                usageNode.put("cachedTokens", usage.cachedTokens());
                usageNode.put("cachedTokensKnown", usage.cachedTokensKnown());
                usageNode.put("completionTokens", usage.completionTokens());
                usageNode.put("ms", usage.ms());
                // cacheHit only when the backend actually named the cached tokens: a 0.0 here would
                // read as "the prefix missed" while the real statement is "unreported" - and a vLLM
                // streaming response does not report them at all (webui.fetchMetrics is then the way).
                if (usage.cachedTokensKnown() && usage.promptTokens() > 0) {
                    usageNode.put("cacheHit",
                            Math.round((double) usage.cachedTokens() * 1000.0 / usage.promptTokens()) / 1000.0);
                } else {
                    usageNode.put("cacheHit", "unknown");
                }
            }
        }
        return root;
    }

    /**
     * @param message what the caller did wrong
     * @return an error result that says so
     */
    private CallToolResult errorResult(String message) {
        ObjectNode root = jsonMapper.createObjectNode();
        root.putObject("fork").put("error", message);
        return CallToolResult.builder().isError(true).addTextContent(root.toString()).build();
    }

    /**
     * Writes the full board once, at the end of the batch - the transcripts' rule (while the batch
     * runs, a thread finds nothing about its siblings in the project); the board is the opt-in
     * exception, and it lives inside the tool until this moment.
     *
     * @param batchId  batch label
     * @param artifact full board as JSON
     * @return the path relative to the project directory, or null when it could not be written
     */
    private String writeBoardArtifact(String batchId, ObjectNode artifact) {
        Path base = Paths.get(System.getProperty("IDE_PROJECT_DIR", ".")).toAbsolutePath().normalize();
        Path file = base.resolve(artifactDir).normalize().resolve(batchId).resolve("board.json");
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, artifact.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            LOG.info("prefix_fork {}: board artifact written: {}", batchId, file);
        } catch (IOException | RuntimeException e) {
            LOG.warn("prefix_fork {}: board artifact cannot be written: {}", batchId, e.getMessage());
            return null;
        }
        // relativize throws when the paths cannot be relativized against each other - here exactly
        // when artifactDir is absolute (Path.resolve replaces the base entirely) or escapes the
        // project with ".." (normalize keeps the surviving ".."s). Then the file exists, but outside
        // the project: said out loud, and the absolute path is the honest reference. With the
        // startsWith check settled, relativize itself cannot throw any more.
        if (!file.startsWith(base)) {
            LOG.warn("prefix_fork {}: agent.fork.artifactDir='{}' leaves the project directory;"
                            + " the board artifact is referenced by its absolute path: {}",
                    batchId, artifactDir, file);
            return file.toString();
        }
        return base.relativize(file).toString().replace('\\', '/');
    }

    /**
     * @param text possibly multi-line text
     * @return it on one line for the log, line breaks shown as a separator (an INFO log that spans
     *         eight lines is a log nobody greps)
     */
    private static String oneLine(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r", "").replace("\n", " | ");
    }

    /** @param node node to read @return its text, {@code null} when absent, null-valued or blank */
    private static String textOrNull(JsonNode node) {
        if (node == null || !node.isString()) {
            return null;
        }
        String text = node.asString();
        return text.isBlank() ? null : text;
    }

    /**
     * @param value value to clamp
     * @param min   lower bound
     * @param max   upper bound
     * @return the value inside the bounds
     */
    private static int clamp(Integer value, int min, int max) {
        int v = (value == null) ? min : value;
        return Math.max(min, Math.min(max, v));
    }

    /** @return a short batch label, e.g. {@code "b-7f3c"} */
    private static String newBatchId() {
        return "b-" + UUID.randomUUID().toString().substring(0, 4);
    }
}
