package org.rogmann.mcp2sdk.acp;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.time.Duration;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpClientTransport;
import org.rogmann.mcp2sdk.agent.ChatContextStore;
import org.rogmann.mcp2sdk.browser.BrowserReviewService;
import org.rogmann.mcp2sdk.m2.MavenGuard;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpSyncAgent;
import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * The real ACP agent (S4, docs/acp.md §6): every prompt becomes a chat completion against
 * this very server's loopback ({@code <selfBaseUrl>/chat/v1/chat/completions}) — exactly
 * the seam {@code SubAgentRunner} uses for its prefix-fork children. Content deltas stream
 * into the IDE as {@code agent_message_chunk}, reasoning deltas ({@code reasoning_content})
 * as thought chunks, and a cancelled turn ends with {@code stop_reason=cancelled}.
 *
 * <p><b>Tool loop (S4)</b>: {@code delta.tool_calls} fragments are merged per index (the
 * {@code LlmLogProxy} parsing pattern), executed over a lazily created MCP client that
 * talks to this server's own {@code /mcp} endpoint — the {@code CallLlmTool} pattern, which
 * makes the call respect the active state of the {@code ToolRegistry} automatically. Every
 * call surfaces in the IDE as {@code tool_call} + {@code tool_call_update} notifications,
 * the results re-enter the history as {@code role=tool} messages, and the next completion
 * turn continues until the model answers without tool calls or the turn's budget runs out
 * ({@value #MAX_TOOL_CALLS} tool calls, {@value #TURN_DEADLINE_MINUTES} minutes — the
 * {@code SubAgentRunner} guardrails, simplified). Each unexecuted call still gets a
 * synthetic {@code role=tool} result, so the history stays valid for the backend.</p>
 *
 * <p>The proxy prepares the request body itself — model override from {@code webui.model.*},
 * reasoning translation, {@code max_tokens} default, {@code stream_options} — so this class
 * sends a plain OpenAI body with {@code messages}, {@code stream:true} and the active tools'
 * function definitions. Two headers ride on the loopback request: {@code X-Conversation-Id}
 * (= the chat id, the anchor the prefix-fork story will dock onto in S6) and
 * {@code x-session-id} (backend routing), which {@code WebUiProxy} forwards upstream.</p>
 *
 * <p><b>Session id vs. chat id</b> ({@link ChatIds}): the session id is the routing key for
 * the LLM backend ({@code x-session-id}) and stays constant across a prefix fork, so the
 * backend's token-prefix cache keeps hitting; the chat id names one conversation instance —
 * the key of the in-memory history here and of the {@code ChatContextStore} snapshots
 * ({@code X-Conversation-Id}) — so a fork child never overwrites its parent's snapshots.</p>
 *
 * <p>One agent instance serves all the sessions of its connection; the conversation history
 * is per chat id and in memory only (a restart forgets it — the Web-UI history stays
 * untouched, and the proxy's own statistics keep flowing for the loopback requests).</p>
 *
 * <p><b>Permission route (S6)</b>: a tool named in {@code acp.permission.tools} asks the IDE
 * user via {@code session/request_permission} before it executes — options allow once /
 * allow always / reject, "allow always" remembered per ACP session. The list is empty by
 * default and re-read on every call (the {@code acp.ide-diff} pattern); it is a UX
 * affordance, deliberately not a security boundary, and review-backed tools
 * ({@link PythonReviewService}, {@link MavenGuard}, {@link BrowserReviewService}) are
 * never asked twice — their decision path is the four-eyes queue.</p>
 */
final class AcpChat {

    private static final Logger LOG = LoggerFactory.getLogger(AcpChat.class);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String COMPLETIONS_PATH = "/chat/v1/chat/completions";

    private static final int CONNECT_TIMEOUT_MS = 5_000;

    /** Generous: a reasoning model streams tokens continuously; 2 min of silence means trouble. */
    private static final int READ_TIMEOUT_MS = 120_000;

    /** Per-call timeout of the MCP tool client (system property {@code acp.mcp.request-timeout-seconds}). */
    private static final int MCP_REQUEST_TIMEOUT_SECONDS =
            Integer.getInteger("acp.mcp.request-timeout-seconds", 300);

    /** Tool calls per prompt turn at most (the SubAgentRunner guardrail, simplified). */
    private static final int MAX_TOOL_CALLS = 20;

    /** Wall-clock budget of one prompt turn, tool calls included. */
    private static final long TURN_DEADLINE_MINUTES = 10;

    /**
     * Tools whose approval lives in the four-eyes {@code ReviewService} — their decision
     * path exists, so the ACP dialog never asks them twice (docs/acp.md §6, S6). Keep in
     * sync when a review-backed tool appears; there is no registry to ask (the review
     * area holds no catalogue of tools by design).
     */
    private static final Set<String> REVIEWED_TOOLS = Set.of(
            MavenGuard.TOOL_ID, BrowserReviewService.TOOL_ID);

    /** Option ids of the S6 permission dialog — the ids are ours, the labels face the user. */
    private static final String OPT_ALLOW_ONCE = "allow_once";
    private static final String OPT_ALLOW_ALWAYS = "allow_always";
    private static final String OPT_REJECT = "reject_once";

    /**
     * Serializes the history updates and snapshots across all connections. A ReentrantLock
     * instead of a monitor: the build targets Java 17, but the same code runs on a JDK 21
     * runtime, where the SDK's transports execute on virtual threads — and a virtual
     * thread blocked on a monitor pins its carrier thread. The lock is neutral on Java 17
     * and pinning-safe on 21+.
     */
    private static final Lock LOCK = new ReentrantLock();

    private final String selfBaseUrl;

    /** Conversation history per chat id (see {@link ChatIds}). */
    /**
     * Process-wide, not per connection: the IDE opens one connection per conversation and
     * rebuilds them on every reconnect (an IDE restart, a dropped WebSocket) — a new
     * {@code AcpChat} with an instance-owned map would forget every conversation on the
     * first reconnect, and the {@code session/load} that arrives on the fresh connection
     * has to find the replay there. The {@code ReviewStore}/{@code ForkIdentity} pattern:
     * the map is static, every handler reads the same object. (Found by the S6 load round:
     * the reconnect after an IDE restart answered "unknown session" for a conversation the
     * same server process had held.)
     */
    private static final Map<String, List<ObjectNode>> histories = new ConcurrentHashMap<>();

    /** MCP client for in-process tool execution; lazily created per connection. */
    private McpSyncClient mcpClient;

    /** The active tools as of this connection's first tool listing. */
    private volatile List<McpSchema.Tool> activeTools;

    /**
     * Tool names the IDE user freed with "allow always", per ACP session id (S6). In
     * memory only — a restart asks again, which is the honest default for a permission.
     * Process-wide like {@link #histories}: the session ids outlive the connections that
     * created them, and "always" must not expire on a reconnect.
     */
    private static final Map<String, Set<String>> alwaysAllowedTools = new ConcurrentHashMap<>();

    /**
     * The chat id of the tool loop running right now, read by the transport-context
     * provider on the thread that makes the call (the prompt thread) — a thread-local
     * inside the transport customizer would be against the SDK's own advice, because the
     * request is built on whatever thread the reactor chain reaches; the provider runs
     * before that, and the context travels with the request.
     */
    private static final ThreadLocal<String> CURRENT_CONVERSATION = new ThreadLocal<>();

    /** Key under which the tool loop puts the chat id into the {@link McpTransportContext}. */
    private static final String CONTEXT_CONVERSATION_ID = "acpConversationId";

    /**
     * The agent this chat serves, set by {@link #build} right after construction. The load
     * handler needs it: the sync load handler takes no agent parameter, and the session
     * updates of the replay go through the agent — there is no prompt turn to attach a
     * prompt context to.
     */
    private AcpSyncAgent agent;

    private AcpChat(String selfBaseUrl) {
        this.selfBaseUrl = selfBaseUrl;
    }

    /**
     * The two conversation keys of one ACP turn.
     *
     * @param sessionId the ACP session id — constant for the whole chat and every fork of it;
     *                  the backend routing key ({@code x-session-id})
     * @param chatId    names one conversation instance — the in-memory history key and the
     *                  {@code X-Conversation-Id} the {@code ChatContextStore} snapshots use
     */
    private record ChatIds(String sessionId, String chatId) {

        /** S3 has one chat per session; a prefix-fork child gets {@code sessionId + '_' + childId}. */
        static ChatIds forSession(String sessionId) {
            return new ChatIds(sessionId, sessionId);
        }
    }

    /**
     * One tool call assembled from streamed {@code delta.tool_calls} fragments: the id and
     * the function name usually arrive with the first fragment, the arguments string splits
     * across all of them (the {@code LlmLogProxy} parsing pattern).
     */
    private static final class ToolCallAcc {
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();

        private String callId(int fallbackIndex) {
            return (id == null || id.isEmpty()) ? "call_" + fallbackIndex : id;
        }

        private String title() {
            String args = arguments.toString();
            return (name == null ? "tool" : name) + (args.isEmpty() ? "" : " (" + excerpt(args, 80) + ")");
        }
    }

    /**
     * Builds one agent on the given transport, unstarted — the connection starts it.
     * @param transport the connection's transport
     * @param selfBaseUrl this server's base URL for the loopback completion,
     *                    e.g. {@code http://127.0.0.1:9092}
     * @return a new agent, not started
     */
    static AcpSyncAgent build(AcpAgentTransport transport, String selfBaseUrl) {
        AcpChat chat = new AcpChat(selfBaseUrl);
        AcpSyncAgent agent = AcpAgent.sync(transport)
            .initializeHandler(request -> {
                LOG.info("AcpChat initialize: protocolVersion={}, clientInfo={}, clientCapabilities={}",
                        request.protocolVersion(), request.clientInfo(), request.clientCapabilities());
                // Our own handler replaces the SDK default, and InitializeResponse.ok()
                // advertises an empty capability set — loadSession goes in by hand (S6).
                return AcpSchema.InitializeResponse.ok(
                        AcpSchema.AgentCapabilities.builder().loadSession(true).build());
            })
            .newSessionHandler(request -> {
                String sessionId = UUID.randomUUID().toString();
                chat.startHistory(sessionId, request.cwd());
                LOG.info("AcpChat session/new: cwd={}, mcpServers={}", request.cwd(),
                        request.mcpServers() == null ? 0 : request.mcpServers().size());
                return new AcpSchema.NewSessionResponse(sessionId);
            })
            .promptHandler(chat::onPrompt)
            .loadSessionHandler(chat::onLoadSession)
            .build();
        chat.agent = agent;
        return agent;
    }

    private AcpSchema.PromptResponse onPrompt(AcpSchema.PromptRequest request, SyncPromptContext context) {
        ChatIds ids = ChatIds.forSession(request.sessionId());
        String userText = extractText(request.prompt());
        LOG.info("AcpChat prompt in session {} (chat {}): {}", ids.sessionId(), ids.chatId(), summarize(userText));
        List<ObjectNode> history = histories.computeIfAbsent(ids.chatId(), id -> new ArrayList<>());
        LOCK.lock();
        try {
            history.add(message("user", userText));
        }
        finally {
            LOCK.unlock();
        }
        try {
            return completionLoop(ids, history, context);
        }
        catch (IOException | RuntimeException e) {
            LOG.warn("AcpChat completion failed for session {}", ids.sessionId(), e);
            context.sendMessage("\n\n(The LLM call failed: " + e.getMessage() + ")");
            return AcpSchema.PromptResponse.endTurn();
        }
    }

    /**
     * Serves {@code session/load} (S6): the client reopens a session, and this agent replays
     * its conversation as session updates before the response — text turns only, the
     * tool-call entries of the history stay out (the IDE saw them live, and a full
     * tool-call replay is a later throw). The history is in memory only: a session unknown
     * to this process is an honest protocol error, not an empty conversation — a restart
     * forgets it (docs/acp.md §3; the serialized second throw stays open).
     */
    private AcpSchema.LoadSessionResponse onLoadSession(AcpSchema.LoadSessionRequest request) {
        String sessionId = request.sessionId();
        List<ObjectNode> history = histories.get(sessionId);
        if (history == null) {
            throw new IllegalStateException("Unknown session " + sessionId
                    + ": conversations are kept in memory only, and a restart forgets them.");
        }
        LOCK.lock();
        List<ObjectNode> snapshot;
        try {
            snapshot = List.copyOf(history);
        }
        finally {
            LOCK.unlock();
        }
        int replayed = 0;
        for (ObjectNode message : snapshot) {
            String text = message.path("content").asString("");
            if (text.isEmpty()) {
                continue;
            }
            String role = message.path("role").asString("");
            AcpSchema.SessionUpdate update = "user".equals(role)
                    ? new AcpSchema.UserMessageChunk(new AcpSchema.TextContent(text))
                    : "assistant".equals(role)
                            ? new AcpSchema.AgentMessageChunk(new AcpSchema.TextContent(text))
                            : null;
            if (update != null) {
                agent.sendSessionUpdate(sessionId, update);
                replayed++;
            }
        }
        LOG.info("AcpChat load: session {} replayed {} of {} history entr(ies)", sessionId,
                replayed, snapshot.size());
        return new AcpSchema.LoadSessionResponse(null);
    }

    private AcpSchema.PromptResponse completionLoop(ChatIds ids, List<ObjectNode> history,
                                                    SyncPromptContext context) throws IOException {
        List<McpSchema.Tool> tools = listActiveTools();
        int toolCallsExecuted = 0;
        long deadline = System.currentTimeMillis() + TURN_DEADLINE_MINUTES * 60_000L;

        while (true) {
            if (context.isCancelled()) {
                LOG.info("AcpChat prompt in session {} cancelled", ids.sessionId());
                return AcpSchema.PromptResponse.cancelled();
            }
            if (System.currentTimeMillis() > deadline) {
                context.sendMessage("\n\n(Stopped: the turn exceeded its "
                        + TURN_DEADLINE_MINUTES + " minute budget after " + toolCallsExecuted
                        + " tool call(s).)");
                return AcpSchema.PromptResponse.endTurn();
            }

            ObjectNode body = completionBody(history, tools);
            HttpURLConnection connection = openCompletion(body, ids);

            StringBuilder assistantContent = new StringBuilder();
            Map<Integer, ToolCallAcc> callAccumulators = new TreeMap<>();
            String finishReason = null;
            int status;
            try {
                status = connection.getResponseCode();
                if (status >= 400) {
                    String detail = "HTTP " + status + " from " + selfBaseUrl + COMPLETIONS_PATH
                            + ": " + excerpt(readBody(connection.getErrorStream()), 300);
                    connection.disconnect();
                    LOG.warn("AcpChat completion failed: {}", detail);
                    context.sendMessage("\n\n(The LLM call failed: " + detail + ")");
                    return AcpSchema.PromptResponse.endTurn();
                }
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (context.isCancelled()) {
                            connection.disconnect();
                            LOG.info("AcpChat prompt in session {} cancelled", ids.sessionId());
                            return AcpSchema.PromptResponse.cancelled();
                        }
                        if (!line.startsWith("data:")) {
                            continue;
                        }
                        String payload = line.substring(5).trim();
                        if ("[DONE]".equals(payload)) {
                            break;
                        }
                        JsonNode chunk;
                        try {
                            chunk = JSON.readTree(payload);
                        }
                        catch (RuntimeException e) {
                            LOG.warn("AcpChat skipping a non-JSON SSE line: {}", e.getMessage());
                            continue;
                        }
                        JsonNode choice = chunk.path("choices").path(0);
                        JsonNode delta = choice.path("delta");
                        String reasoning = delta.path("reasoning_content").asString("");
                        if (!reasoning.isEmpty()) {
                            context.sendThought(reasoning);
                        }
                        String content = delta.path("content").asString("");
                        if (!content.isEmpty()) {
                            assistantContent.append(content);
                            context.sendMessage(content);
                        }
                        JsonNode streamedCalls = delta.get("tool_calls");
                        if (streamedCalls instanceof ArrayNode streamed) {
                            for (JsonNode fragment : streamed) {
                                int index = fragment.path("index").asInt(0);
                                ToolCallAcc acc = callAccumulators.computeIfAbsent(index,
                                        i -> new ToolCallAcc());
                                JsonNode idNode = fragment.get("id");
                                if (idNode != null && !idNode.asString("").isEmpty()) {
                                    acc.id = idNode.asString();
                                }
                                JsonNode function = fragment.get("function");
                                if (function != null) {
                                    JsonNode nameNode = function.get("name");
                                    if (nameNode != null && !nameNode.asString("").isEmpty()) {
                                        acc.name = nameNode.asString();
                                    }
                                    JsonNode argsNode = function.get("arguments");
                                    if (argsNode != null && !argsNode.asString("").isEmpty()) {
                                        acc.arguments.append(argsNode.asString());
                                    }
                                }
                            }
                        }
                        JsonNode finishNode = choice.get("finish_reason");
                        if (finishNode != null && !finishNode.asString("").isEmpty()) {
                            finishReason = finishNode.asString();
                        }
                    }
                }
            }
            catch (IOException e) {
                LOG.warn("AcpChat completion stream failed for session {}", ids.sessionId(), e);
                context.sendMessage("\n\n(The LLM stream failed: " + e.getMessage() + ")");
                return AcpSchema.PromptResponse.endTurn();
            }

            if (callAccumulators.isEmpty()) {
                if (assistantContent.length() > 0) {
                    LOCK.lock();
                    try {
                        history.add(message("assistant", assistantContent.toString()));
                    }
                    finally {
                        LOCK.unlock();
                    }
                }
                return AcpSchema.PromptResponse.endTurn();
            }

            // The model wants tools: the assistant message with its calls enters the history,
            // then each call executes in-process and its result re-enters as role=tool.
            List<ToolCallAcc> calls = List.copyOf(callAccumulators.values());
            LOCK.lock();
            try {
                history.add(assistantWithToolCalls(assistantContent.toString(), calls));
            }
            finally {
                LOCK.unlock();
            }
            if (finishReason != null && !"tool_calls".equals(finishReason)) {
                LOG.info("AcpChat executes tool calls with finish_reason={}", finishReason);
            }

            toolCallsExecuted = executeToolCalls(history, context, calls, ids.chatId(),
                    deadline, toolCallsExecuted);
            // The next completion turn sees the assistant call and the tool results in its history.
        }
    }

    /**
     * Runs one batch of tool calls under this conversation's identity: every MCP request
     * below carries the chat id in {@code X-Conversation-Id} (S6) — supplied per call out
     * of the transport context, so {@code prefix_fork} can tie a call to the session that
     * made it even though all ACP sessions of this connection share one MCP client.
     * Returns the updated tool-call count.
     */
    private int executeToolCalls(List<ObjectNode> history, SyncPromptContext context,
                                 List<ToolCallAcc> calls, String chatId, long deadline,
                                 int toolCallsExecuted) {
        CURRENT_CONVERSATION.set(chatId);
        try {
            for (int i = 0; i < calls.size(); i++) {
                ToolCallAcc call = calls.get(i);
                String toolCallId = call.callId(toolCallsExecuted + i);
                if (toolCallsExecuted >= MAX_TOOL_CALLS) {
                    appendSyntheticToolResult(history, toolCallId,
                            "Not executed: the turn's tool-call budget (" + MAX_TOOL_CALLS
                                    + " calls) is exhausted. Answer from what you have.");
                    continue;
                }
                if (System.currentTimeMillis() > deadline) {
                    appendSyntheticToolResult(history, toolCallId,
                            "Not executed: the turn exceeded its time budget. Answer from what you have.");
                    continue;
                }
                Path diffTarget = null;
                String diffBefore = null;
                Map<String, Object> callArgs = parseArgumentsQuietly(call.arguments.toString());
                if ("edit_file_tool".equals(call.name) && ideDiffEnabled() && callArgs != null) {
                    // S5: capture the before-state; the tool then writes locally (the normal
                    // case, no review), and the change is surfaced to the IDE as a
                    // ToolCallDiff content block — the IDE's fs/write_text_file route is
                    // applied silently by the IntelliJ client (verified 2026-10-25).
                    try {
                        diffTarget = AcpFileAccess.resolveWorkFile(
                                (String) callArgs.get("projectName"), (String) callArgs.get("pathInProject"));
                        diffBefore = AcpFileAccess.readFile(diffTarget);
                    }
                    catch (RuntimeException e) {
                        LOG.warn("AcpChat ide-diff pre-read failed for {}: {}", diffTarget, e.getMessage());
                    }
                }
                boolean executed = executeToolCall(history, context, toolCallId, call);
                if (diffTarget != null && executed) {
                    sendFileDiffUpdate(context, toolCallId, diffTarget.toString(), diffBefore);
                }
                toolCallsExecuted++;
            }
        }
        finally {
            CURRENT_CONVERSATION.remove();
        }
        return toolCallsExecuted;
    }

    private boolean executeToolCall(List<ObjectNode> history, SyncPromptContext context,
                                    String toolCallId, ToolCallAcc call) {
        AcpSchema.ToolKind kind = kindOf(call.name);
        String title = call.title();
        context.sendSessionUpdate(new AcpSchema.ToolCall(null, toolCallId, title, call.name,
                kind, AcpSchema.ToolCallStatus.IN_PROGRESS, null, null, null, null, null));

        // S6: the permission route sits between the announce and the execution. The IDE
        // user decides on the tools listed in acp.permission.tools (empty by default);
        // everything else runs silently, as it did before S6 and as the web UI does.
        PermissionDecision permission = permissionFor(context, call, toolCallId, title, kind);
        if (!permission.granted()) {
            context.sendSessionUpdate(new AcpSchema.ToolCallUpdateNotification(null, toolCallId,
                    null, null, null, AcpSchema.ToolCallStatus.FAILED,
                    List.of(new AcpSchema.ToolCallContentBlock(null,
                            new AcpSchema.TextContent(permission.note()))), null, null, null, null));
            appendSyntheticToolResult(history, toolCallId, permission.note());
            LOG.info("AcpChat tool call {} ({}) skipped: {}", call.name, toolCallId,
                    permission.note());
            return false;
        }

        McpSchema.CallToolResult result;
        boolean failed = false;
        try {
            Map<String, Object> arguments = parseArguments(call.arguments.toString());
            result = mcpClient().callTool(new McpSchema.CallToolRequest(call.name, arguments));
            failed = Boolean.TRUE.equals(result.isError());
        }
        catch (RuntimeException e) {
            LOG.warn("AcpChat tool call {} failed", call.name, e);
            failed = true;
            result = McpSchema.CallToolResult.builder()
                    .isError(true)
                    .addTextContent("Tool call failed: " + e.getMessage())
                    .build();
        }

        String resultText = textOf(result);
        AcpSchema.ToolCallStatus status = failed
                ? AcpSchema.ToolCallStatus.FAILED
                : AcpSchema.ToolCallStatus.COMPLETED;
        List<AcpSchema.ToolCallContent> content = List.of(new AcpSchema.ToolCallContentBlock(null,
                new AcpSchema.TextContent(excerpt(resultText, 500))));
        context.sendSessionUpdate(new AcpSchema.ToolCallUpdateNotification(null, toolCallId,
                null, null, null, status, content, null, null, null, null));

        LOCK.lock();
        try {
            history.add(toolMessage(toolCallId, resultText));
        }
        finally {
            LOCK.unlock();
        }
        LOG.info("AcpChat tool call {} ({}) -> {}", call.name, toolCallId,
                (failed ? "error" : "ok"));
        return true;
    }

    /**
     * The S6 permission step for one tool call: returns whether it may execute, and asks
     * the IDE user when the tool is listed in {@code acp.permission.tools}. The request
     * rides on the already sent {@code tool_call} announce and carries this very
     * {@code toolCallId} — not the {@code askPermission} convenience, which would announce
     * a second tool call of its own with a random id. The user's answer counts against the
     * agent's request timeout; a prompt turn cancelled meanwhile must come back as a
     * {@code PermissionCancelled} outcome (schema v1 requires the client to answer pending
     * permission requests of the cancelled turn that way) and skips the call. A client
     * without a handler answers {@code -32601}; either way, no answer means no execution —
     * the safe side.
     */
    private PermissionDecision permissionFor(SyncPromptContext context, ToolCallAcc call,
                                             String toolCallId, String title, AcpSchema.ToolKind kind) {
        if (!permissionTools().contains(call.name) || REVIEWED_TOOLS.contains(call.name)) {
            return PermissionDecision.GRANTED;
        }
        String sessionId = context.getSessionId();
        if (allowedAlways(sessionId).contains(call.name)) {
            return PermissionDecision.GRANTED;
        }
        List<AcpSchema.PermissionOption> options = List.of(
                new AcpSchema.PermissionOption(OPT_ALLOW_ONCE, "Allow once",
                        AcpSchema.PermissionOptionKind.ALLOW_ONCE),
                new AcpSchema.PermissionOption(OPT_ALLOW_ALWAYS, "Allow always",
                        AcpSchema.PermissionOptionKind.ALLOW_ALWAYS),
                new AcpSchema.PermissionOption(OPT_REJECT, "Reject",
                        AcpSchema.PermissionOptionKind.REJECT_ONCE));
        AcpSchema.RequestPermissionResponse response;
        try {
            response = context.client().requestPermission(new AcpSchema.RequestPermissionRequest(
                    sessionId,
                    new AcpSchema.ToolCallUpdate(toolCallId, title, kind,
                            AcpSchema.ToolCallStatus.IN_PROGRESS),
                    options));
        }
        catch (RuntimeException e) {
            LOG.warn("AcpChat permission request for {} failed: {}", call.name, e.toString());
            return PermissionDecision.declined("Not executed: the permission dialog failed ("
                    + e.getMessage() + ").");
        }
        if (response.outcome() instanceof AcpSchema.PermissionSelected selected) {
            if (OPT_ALLOW_ALWAYS.equals(selected.optionId())) {
                allowedAlways(sessionId).add(call.name);
                LOG.info("AcpChat: {} allowed for the rest of session {} (allow always)",
                        call.name, sessionId);
            }
            if (OPT_ALLOW_ONCE.equals(selected.optionId())
                    || OPT_ALLOW_ALWAYS.equals(selected.optionId())) {
                return PermissionDecision.GRANTED;
            }
            // An unoffered option id is treated like a rejection — the safe side.
            return PermissionDecision.declined("Not executed: the user rejected this tool call"
                    + " in the IDE permission dialog.");
        }
        return PermissionDecision.declined("Not executed: the prompt turn was cancelled while"
                + " the tool call awaited approval.");
    }

    /** The "allow always" memory of one ACP session, created on first use. */
    private Set<String> allowedAlways(String sessionId) {
        return alwaysAllowedTools.computeIfAbsent(sessionId, id -> ConcurrentHashMap.newKeySet());
    }

    /** Either the call runs, or it is skipped with a note that reaches model and IDE. */
    private record PermissionDecision(boolean granted, String note) {
        static final PermissionDecision GRANTED = new PermissionDecision(true, null);

        static PermissionDecision declined(String note) {
            return new PermissionDecision(false, note);
        }
    }

    private void appendSyntheticToolResult(List<ObjectNode> history, String toolCallId, String text) {
        LOCK.lock();
        try {
            history.add(toolMessage(toolCallId, text));
        }
        finally {
            LOCK.unlock();
        }
    }

    /**
     * Seeds the chat history for a new session: AGENTS.md of the workspace as the system
     * message (S5), so the model knows how to work in this project from the first prompt.
     */
    private void startHistory(String sessionId, String cwd) {
        List<ObjectNode> history = histories.computeIfAbsent(sessionId, id -> new ArrayList<>());
        String agentsMd = readAgentsMd(cwd);
        if (agentsMd != null) {
            LOCK.lock();
            try {
                history.add(message("system", agentsMd));
            }
            finally {
                LOCK.unlock();
            }
        }
    }

    /** Reads AGENTS.md from the session cwd, size-bounded; null when absent or unreadable. */
    private static String readAgentsMd(String cwd) {
        if (cwd == null || cwd.isBlank()) {
            return null;
        }
        try {
            Path file = Path.of(cwd, "AGENTS.md");
            if (!Files.isRegularFile(file)) {
                return null;
            }
            String content = Files.readString(file);
            if (content.length() > 64 * 1024) {
                LOG.warn("AGENTS.md is too large ({} chars), truncated", content.length());
                content = content.substring(0, 64 * 1024) + "\n… (truncated)";
            }
            return "AGENTS.md of the workspace:\n\n" + content;
        }
        catch (IOException e) {
            LOG.debug("No readable AGENTS.md in {}: {}", cwd, e.getMessage());
            return null;
        }
    }

    /**
     * Whether edits from the ACP chat are presented to the IDE as diffs: system property
     * {@code acp.ide-diff}, default true, re-read on every edit so it can change at
     * runtime (e.g. later via a toggle in the {@code ToolController}). The choice is
     * deliberately not in the LLM's hands.
     */
    private static boolean ideDiffEnabled() {
        return Boolean.parseBoolean(System.getProperty("acp.ide-diff", "true"));
    }

    /**
     * The tools that ask the IDE user before executing (S6): system property
     * {@code acp.permission.tools}, comma-separated, default empty — re-read on every
     * call, the {@code acp.ide-diff} pattern, so a test entry is a one-liner without a
     * restart (a toggle in the {@code ToolController} can follow later). A UX affordance,
     * deliberately not a security boundary; the choice is not in the LLM's hands.
     */
    private static List<String> permissionTools() {
        String raw = System.getProperty("acp.permission.tools", "");
        if (raw.isBlank()) {
            return List.of();
        }
        List<String> tools = new ArrayList<>();
        for (String name : raw.split(",")) {
            String trimmed = name.trim();
            if (!trimmed.isEmpty()) {
                tools.add(trimmed);
            }
        }
        return List.copyOf(tools);
    }

    /**
     * Surfaces a local file edit to the IDE as a {@code ToolCallDiff} content block
     * (before → after): the IDE renders it as the file's diff in the chat, click to open
     * the editor diff. The fs/write_text_file route is not used for this — the IntelliJ
     * client applies it silently without rendering a diff (verified 2026-10-25 via
     * acp-transport-memory.log).
     */
    private void sendFileDiffUpdate(SyncPromptContext context, String toolCallId, String path,
                                    String oldText) {
        // The after-text is the file content after the local write — read it from disk.
        String after = AcpFileAccess.readFile(Path.of(path));
        List<AcpSchema.ToolCallContent> content = List.of(
                new AcpSchema.ToolCallDiff(null, path, oldText, after));
        context.sendSessionUpdate(new AcpSchema.ToolCallUpdateNotification(null, toolCallId,
                null, null, null, AcpSchema.ToolCallStatus.COMPLETED, content, null, null, null, null));
    }

    /** parseArguments without the exception path — null when the arguments are not valid JSON. */
    private static Map<String, Object> parseArgumentsQuietly(String argumentsJson) {
        try {
            return JSON.readValue(argumentsJson, new TypeReference<Map<String, Object>>() { });
        }
        catch (RuntimeException e) {
            return null;
        }
    }

    private HttpURLConnection openCompletion(ObjectNode body, ChatIds ids) throws IOException {
        URL url = URI.create(selfBaseUrl + COMPLETIONS_PATH).toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "text/event-stream");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setDoOutput(true);
        // The chat id anchors the ChatContextStore snapshots (prefix forks dock there in S6);
        // the session id is the routing key the backend's load balancer sees.
        connection.setRequestProperty(ChatContextStore.HEADER_CONVERSATION_ID, ids.chatId());
        connection.setRequestProperty("x-session-id", ids.sessionId());
        try (OutputStream out = connection.getOutputStream();
             OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            writer.write(JSON.writeValueAsString(body));
            writer.flush();
        }
        return connection;
    }

    private ObjectNode completionBody(List<ObjectNode> history, List<McpSchema.Tool> tools) {
        ObjectNode body = JSON.createObjectNode();
        body.put("stream", true);
        ArrayNode messageArray = body.putArray("messages");
        LOCK.lock();
        try {
            for (ObjectNode message : history) {
                messageArray.add(message.deepCopy());
            }
        }
        finally {
            LOCK.unlock();
        }
        if (!tools.isEmpty()) {
            ArrayNode toolsArray = body.putArray("tools");
            for (McpSchema.Tool tool : tools) {
                ObjectNode functionTool = toolsArray.addObject();
                functionTool.put("type", "function");
                ObjectNode function = functionTool.putObject("function");
                function.put("name", tool.name());
                function.put("description", tool.description());
                function.set("parameters", JSON.valueToTree(tool.inputSchema()));
            }
        }
        return body;
    }

    /**
     * The MCP client for in-process tool execution (the {@code CallLlmTool} pattern): a
     * streamable-HTTP client against this server's own {@code /mcp} endpoint, so every
     * call respects the {@code ToolRegistry}'s active state and runs with a real server
     * exchange. Created lazily on the first tool use, never closed (one per connection,
     * the connection outlives it). Every call carries its conversation id in
     * {@code X-Conversation-Id} — supplied per call out of the transport context and put
     * on the request by the customizer, so {@code prefix_fork} can tie a call to the
     * session that made it (S6): all ACP sessions of this connection share this one
     * client, and the model never names the conversation itself.
     */
    private McpSyncClient mcpClient() {
        LOCK.lock();
        try {
            if (mcpClient == null) {
                McpClientTransport transport = HttpClientStreamableHttpTransport
                        .builder(selfBaseUrl)
                        .endpoint("/mcp")
                        .addHttpRequestCustomizer((builder, method, endpoint, body, context) -> {
                            Object conversation = (context == null) ? null
                                    : context.get(CONTEXT_CONVERSATION_ID);
                            if (conversation != null) {
                                builder.header(ChatContextStore.HEADER_CONVERSATION_ID,
                                        String.valueOf(conversation));
                            }
                        })
                        .build();
                McpSyncClient client = McpClient.sync(transport)
                        .requestTimeout(Duration.ofSeconds(MCP_REQUEST_TIMEOUT_SECONDS))
                        .transportContextProvider(() -> {
                            String conversation = CURRENT_CONVERSATION.get();
                            return (conversation == null) ? McpTransportContext.EMPTY
                                    : McpTransportContext.create(Map.of(
                                            CONTEXT_CONVERSATION_ID, conversation));
                        })
                        .build();
                client.initialize();
                mcpClient = client;
                LOG.info("AcpChat MCP client initialized against {}", selfBaseUrl + "/mcp");
            }
            return mcpClient;
        }
        finally {
            LOCK.unlock();
        }
    }

    /** The active tools as of this connection's first look (the /tools toggles need a new session to apply). */
    private List<McpSchema.Tool> listActiveTools() {
        if (activeTools == null) {
            LOCK.lock();
            try {
                if (activeTools == null) {
                    List<McpSchema.Tool> tools = mcpClient().listTools().tools();
                    activeTools = List.copyOf(tools);
                    StringBuilder names = new StringBuilder();
                    for (McpSchema.Tool tool : activeTools) {
                        if (names.length() > 0) {
                            names.append(", ");
                        }
                        names.append(tool.name());
                    }
                    LOG.info("AcpChat active tools ({}): {}", activeTools.size(), names);
                }
            }
            finally {
                LOCK.unlock();
            }
        }
        return activeTools;
    }

    private static Map<String, Object> parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(argumentsJson, new TypeReference<Map<String, Object>>() { });
        }
        catch (RuntimeException e) {
            throw new IllegalArgumentException("The tool-call arguments are not valid JSON: "
                    + excerpt(argumentsJson, 200), e);
        }
    }

    private static String textOf(McpSchema.CallToolResult result) {
        StringBuilder sb = new StringBuilder();
        for (McpSchema.Content content : result.content()) {
            if (content instanceof McpSchema.TextContent textContent) {
                sb.append(textContent.text()).append('\n');
            }
        }
        String text = sb.toString().trim();
        return text.isEmpty() ? "(empty result)" : text;
    }

    private static AcpSchema.ToolKind kindOf(String toolName) {
        if (toolName == null) {
            return AcpSchema.ToolKind.OTHER;
        }
        if (toolName.startsWith("get_") || toolName.startsWith("read_")) {
            return AcpSchema.ToolKind.READ;
        }
        if (toolName.startsWith("edit_") || toolName.startsWith("write_") || toolName.startsWith("create_")) {
            return AcpSchema.ToolKind.EDIT;
        }
        if (toolName.startsWith("find_") || toolName.startsWith("search_")) {
            return AcpSchema.ToolKind.SEARCH;
        }
        if (toolName.contains("llm") || toolName.startsWith("think_")) {
            return AcpSchema.ToolKind.THINK;
        }
        return AcpSchema.ToolKind.OTHER;
    }

    private static ObjectNode assistantWithToolCalls(String content, List<ToolCallAcc> calls) {
        ObjectNode assistant = JSON.createObjectNode();
        assistant.put("role", "assistant");
        if (!content.isEmpty()) {
            assistant.put("content", content);
        }
        ArrayNode toolCalls = assistant.putArray("tool_calls");
        for (ToolCallAcc call : calls) {
            ObjectNode callNode = toolCalls.addObject();
            callNode.put("id", call.id == null ? "call" : call.id);
            callNode.put("type", "function");
            ObjectNode function = callNode.putObject("function");
            function.put("name", call.name == null ? "unknown" : call.name);
            function.put("arguments", call.arguments.toString());
        }
        return assistant;
    }

    private static ObjectNode toolMessage(String toolCallId, String content) {
        ObjectNode toolMessage = JSON.createObjectNode();
        toolMessage.put("role", "tool");
        toolMessage.put("tool_call_id", toolCallId);
        toolMessage.put("content", content);
        return toolMessage;
    }

    private static ObjectNode message(String role, String content) {
        ObjectNode message = JSON.createObjectNode();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private static String extractText(List<AcpSchema.ContentBlock> prompt) {
        StringBuilder sb = new StringBuilder();
        for (AcpSchema.ContentBlock block : prompt) {
            if (block instanceof AcpSchema.TextContent textContent && textContent.text() != null) {
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append(textContent.text());
            }
        }
        return sb.toString();
    }

    private static String readBody(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        try (InputStream in = stream; ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            in.transferTo(buffer);
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }

    private static String summarize(String text) {
        String oneLine = text.replace('\n', ' ');
        return oneLine.length() > 200 ? oneLine.substring(0, 200) + "…" : oneLine;
    }

    private static String excerpt(String text, int max) {
        String oneLine = text.replace('\n', ' ');
        return oneLine.length() > max ? oneLine.substring(0, max) + "…" : oneLine;
    }
}
