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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.time.Duration;
import java.time.LocalDateTime;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpClientTransport;
import org.rogmann.mcp2sdk.ToolRegistry;
import org.rogmann.mcp2sdk.ToolSpecWithState;
import org.rogmann.mcp2sdk.agent.ChatContextStore;
import org.rogmann.mcp2sdk.browser.BrowserReviewService;
import org.rogmann.mcp2sdk.m2.MavenGuard;
import org.rogmann.mcp2sdk.tools.JavaScriptTool;

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
 * affordance, deliberately not a security boundary, and review-backed tools,
 * {@link MavenGuard}, {@link BrowserReviewService}) are
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
     * The head of the original content that stands in when a summary request fails — the
     * truncation fallback of /compact (docs/acp.md §6 S8).
     */
    private static final int COMPACTION_HEAD_CHARS = 1_000;

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

    /**
     * Base of the LLM backend ({@code webui.model.url}) for the context-window detection
     * (see {@link AcpContextWindow}); may be blank — then only the configured budget counts.
     */
    private final String modelUrl;

    /** The model the proxy overrides to ({@code webui.model.name}); may be blank. */
    private final String modelName;

    /** The tool registry — {@code /tools} reads the live active state from it, in-process. */
    private final ToolRegistry toolRegistry;

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
     * The backend's own token accounting of the last loopback request per chat id — the
     * ground truth the {@code usage_update} rides on and the /chat list header quotes.
     */
    private static final Map<String, Measured> lastMeasured = new ConcurrentHashMap<>();

    /**
     * Highest usage band already hinted per ACP session id (0 none, 1 = 75%, 2 = 90%,
     * 3 = 95%): one hint per band, re-armed when the usage falls below the lowest band.
     */
    private static final Map<String, Integer> usageBandWarned = new ConcurrentHashMap<>();

    /**
     * The chat a session currently prompts into — the /compact fork redirects it (S3
     * default: the chat id is the session id). A restart forgets the redirect together with
     * the histories; session/load then finds the root chat or errors honestly.
     */
    private static final Map<String, String> activeChatBySession = new ConcurrentHashMap<>();

    /** Compaction-fork counter per session — child chat ids read {@code session + "_c" + n}. */
    private static final Map<String, Integer> compactSeqBySession = new ConcurrentHashMap<>();

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

    private AcpChat(String selfBaseUrl, String modelUrl, String modelName, ToolRegistry toolRegistry) {
        this.selfBaseUrl = selfBaseUrl;
        this.modelUrl = modelUrl;
        this.modelName = modelName;
        this.toolRegistry = toolRegistry;
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
     * Prompt and completion tokens of one loopback request, taken from the final usage
     * chunk ({@code stream_options.include_usage} — LlmLogProxy sets it unconditionally).
     */
    private record Measured(long promptTokens, long completionTokens) {
    }

    /**
     * The ChatIds of a prompt: the session's live chat — the /compact fork redirects it
     * (S3 default: the chat id is the session id). A restart forgets the redirect together
     * with the histories; session/load then finds the root chat or errors honestly.
     */
    private static ChatIds chatIdsFor(String sessionId) {
        return new ChatIds(sessionId, activeChatBySession.getOrDefault(sessionId, sessionId));
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
     * @param modelUrl the LLM backend base ({@code webui.model.url}), may be blank
     * @param modelName the model the proxy overrides to ({@code webui.model.name}), may be blank
     * @param toolRegistry the tool registry (the {@code /tools} command lists from it)
     * @return a new agent, not started
     */
    static AcpSyncAgent build(AcpAgentTransport transport, String selfBaseUrl, String modelUrl,
                              String modelName, ToolRegistry toolRegistry) {
        AcpChat chat = new AcpChat(selfBaseUrl, modelUrl, modelName, toolRegistry);
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
                chat.advertiseCommands(sessionId);
                chat.sendUsageUpdate(ChatIds.forSession(sessionId), null, null);
                return new AcpSchema.NewSessionResponse(sessionId);
            })
            .promptHandler(chat::onPrompt)
            .loadSessionHandler(chat::onLoadSession)
            .build();
        chat.agent = agent;
        return agent;
    }

    private AcpSchema.PromptResponse onPrompt(AcpSchema.PromptRequest request, SyncPromptContext context) {
        ChatIds ids = chatIdsFor(request.sessionId());
        String userText = extractText(request.prompt());
        LOG.info("AcpChat prompt in session {} (chat {}): {}", ids.sessionId(), ids.chatId(), summarize(userText));
        String commandText = userText.strip();
        if (commandText.startsWith("/") && commandText.length() > 1) {
            AcpSchema.PromptResponse commandResponse = handleCommand(ids, context, commandText);
            if (commandResponse != null) {
                return commandResponse;
            }
            // Unknown "/..." — not ours (a path?), the model sees the text as usual.
        }
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
        String chatId = activeChatBySession.getOrDefault(sessionId, sessionId);
        List<ObjectNode> history = histories.get(chatId);
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

    // --- Commands (S7) and context usage ---------------------------------------------

    /**
     * Advertises the chat's slash commands right after session/new, before the response —
     * the same channel the load replay uses — so the client renders them in its "/" menu.
     * Commands are answered locally: they never reach the model or the history.
     */
    private void advertiseCommands(String sessionId) {
        agent.sendSessionUpdate(sessionId, new AcpSchema.AvailableCommandsUpdate(null, List.of(
                new AcpSchema.AvailableCommand("help",
                        "Show the commands of this ACP chat", null),
                new AcpSchema.AvailableCommand("tools",
                        "List the active and the disabled tools", null),
                new AcpSchema.AvailableCommand("modules",
                        "List the JS modules of the js tool", null),
                new AcpSchema.AvailableCommand("chat",
                        "Show the numbered entries of this conversation (the numbers /compact"
                                + " consumes)",
                        new AcpSchema.AvailableCommandInput("list")),
                new AcpSchema.AvailableCommand("compact",
                        "Compact chat entries into summaries and continue in a forked chat"
                                + " (the parent stays untouched)",
                        new AcpSchema.AvailableCommandInput("4-7 12 | top 3"))), null));
    }

    /**
     * Handles a prompt that starts with "/". Returns null when the text is not one of our
     * commands — the prompt then flows to the model unchanged (a path like "/etc/hosts" is
     * not a command). A command is answered locally: nothing enters the history. The one
     * exception is /compact, whose summaries come from a tools-less loopback request of
     * their own (still no history entry, still not the model's turn).
     */
    private AcpSchema.PromptResponse handleCommand(ChatIds ids, SyncPromptContext context,
                                                   String text) {
        String body = text.substring(1).strip();
        int space = body.indexOf(' ');
        String name = space < 0 ? body : body.substring(0, space);
        String args = space < 0 ? "" : body.substring(space + 1).strip();
        try {
            switch (name) {
                case "help" -> context.sendMessage(commandHelpText());
                case "tools" -> context.sendMessage(commandToolsText());
                case "modules" -> context.sendMessage(commandModulesText());
                case "chat" -> {
                    if (!args.isEmpty() && !"list".equals(args)) {
                        context.sendMessage("(Unknown /chat argument '" + args
                                + "' — try /chat list.)");
                    }
                    else {
                        context.sendMessage(chatListText(ids));
                    }
                }
                case "compact" -> context.sendMessage(compactChat(ids, context, args));
                default -> {
                    return null;
                }
            }
        }
        catch (RuntimeException e) {
            LOG.warn("AcpChat command /{} failed", name, e);
            context.sendMessage("(The command /" + name + " failed: " + e.getMessage() + ")");
        }
        LOG.info("AcpChat command /{} in session {}", name, ids.sessionId());
        return AcpSchema.PromptResponse.endTurn();
    }

    private static String commandHelpText() {
        return """
                **Commands of this ACP chat:**

                - `/help` — this text
                - `/tools` — the active and the disabled tools (live ToolRegistry state)
                - `/modules` — the JS modules of the js tool
                - `/chat list` — the numbered entries of this conversation, with sizes and previews
                - `/compact 4-7 12` or `/compact top 3` — compact entries into summaries and continue in a forked chat

                Commands are answered locally: they neither reach the model nor the history,
                and an unknown `/...` passes through to the model as a normal prompt.

                The context usage is reported after every request (`usage_update`); hints appear
                when the conversation passes 75%, 90% and 95% of the window —
                `-Dacp.chat.context-window=<tokens>` caps the window deliberately.""";
    }

    private String commandToolsText() {
        StringBuilder active = new StringBuilder();
        StringBuilder disabled = new StringBuilder();
        int activeCount = 0;
        int disabledCount = 0;
        for (Map.Entry<String, ToolSpecWithState> entry : toolRegistry.getMapTools().entrySet()) {
            McpSchema.Tool tool = entry.getValue().spec().tool();
            boolean isActive = entry.getValue().state().isActive().get();
            if (isActive) {
                active.append(markdownListItem(tool.name(), tool.description()));
                activeCount++;
            }
            else {
                disabled.append(markdownListItem(tool.name(), tool.description()));
                disabledCount++;
            }
        }
        return "**Tools (ToolRegistry) — active (" + activeCount + "):**\n\n"
                + (active.isEmpty() ? "_none_\n\n" : active + "\n")
                + "**Disabled (" + disabledCount + "):**\n\n"
                + (disabled.isEmpty() ? "_none_\n" : disabled)
                + "\nThe model sees only the active ones; the toggles live behind `/tools` of the web UI.";
    }

    private static String commandModulesText() {
        StringBuilder sb = new StringBuilder("**JS modules of the js tool:**\n\n");
        for (JavaScriptTool.JsModuleListing module : JavaScriptTool.moduleListing()) {
            sb.append("- `").append(module.namespace()).append("` — ")
                    .append(excerpt(module.summary().replace('\n', ' '), 100));
            if (!module.advertised()) {
                sb.append(" _[not advertised]_");
            }
            if (!module.enabled()) {
                sb.append(" _[disabled]_");
            }
            sb.append('\n');
        }
        sb.append("\nEvery module documents itself in the tool description (e.g. `fs.help()`, `archive.help()`).");
        return sb.toString();
    }

    /**
     * The /chat list: every history entry on one line — number, kind, size (chars/4
     * estimate) and a one-line preview. The numbers are what a later /compact consumes; the
     * header quotes the backend's exact accounting of the last request when it arrived.
     */
    private String chatListText(ChatIds ids) {
        List<ObjectNode> snapshot;
        LOCK.lock();
        try {
            snapshot = List.copyOf(histories.getOrDefault(ids.chatId(), new ArrayList<>()));
        }
        finally {
            LOCK.unlock();
        }
        if (snapshot.isEmpty()) {
            return "(The conversation is empty.)";
        }
        Map<String, String> toolNames = new TreeMap<>();
        for (ObjectNode message : snapshot) {
            JsonNode calls = message.get("tool_calls");
            if (calls != null && calls.isArray()) {
                for (JsonNode call : calls) {
                    String callId = call.path("id").asString("");
                    if (!callId.isEmpty()) {
                        toolNames.put(callId, call.path("function").path("name").asString("?"));
                    }
                }
            }
        }
        StringBuilder lines = new StringBuilder();
        long totalChars = 0;
        int index = 1;
        for (ObjectNode message : snapshot) {
            String role = message.path("role").asString("");
            String content = message.path("content").asString("");
            JsonNode calls = message.get("tool_calls");
            String kind;
            String preview;
            long chars = content.length();
            if ("tool".equals(role)) {
                String callId = message.path("tool_call_id").asString("");
                kind = "tool ← " + toolNames.getOrDefault(callId, callId);
                preview = content;
            }
            else if ("assistant".equals(role) && calls != null && calls.isArray() && !calls.isEmpty()) {
                StringBuilder names = new StringBuilder();
                for (JsonNode call : calls) {
                    if (names.length() > 0) {
                        names.append(", ");
                    }
                    String callArgs = call.path("function").path("arguments").asString("");
                    chars += callArgs.length();
                    names.append(call.path("function").path("name").asString("?"))
                            .append('(').append(excerpt(callArgs.replace('\n', ' '), 40)).append(')');
                }
                kind = "assistant → " + calls.size() + " call(s)";
                preview = content.isEmpty() ? names.toString() : content + " | " + names;
            }
            else {
                kind = role.isEmpty() ? "?" : role;
                preview = content;
            }
            totalChars += chars;
            lines.append(String.format(Locale.ROOT, "%3d. %s ~%s tok  %s%n", index++,
                    padTo(kind, 30), grouped(Math.max(1, chars / 4)),
                    excerpt(preview.replace('\n', ' '), 80)));
        }
        StringBuilder sb = new StringBuilder("**Chat ")
                .append(shortChatId(ids.chatId()))
                .append("** — ").append(snapshot.size()).append(" entries, ~")
                .append(grouped(totalChars / 4)).append(" tok (chars/4 estimate)\n\n");
        Measured measured = lastMeasured.get(ids.chatId());
        if (measured != null) {
            sb.append("Last measured request: **").append(grouped(measured.promptTokens()))
                    .append("** prompt + **").append(grouped(measured.completionTokens()))
                    .append("** completion tokens (backend accounting)\n\n");
        }
        long window = AcpContextWindow.effectiveWindow(modelUrl, modelName);
        if (window > 0) {
            sb.append("Context window: **").append(grouped(window)).append(" tokens**");
            if (measured != null) {
                double percent = (measured.promptTokens() + measured.completionTokens()) * 100.0 / window;
                sb.append(" (**").append(String.format(Locale.ROOT, "%.1f", percent)).append("% used**)");
            }
            sb.append("\n\n");
        }
        else {
            sb.append("Context window: unknown (`-Dacp.chat.context-window=<tokens>`, or make")
                    .append(" `webui.model.url` reachable)\n\n");
        }
        // The listing rides in a fenced code block: the IDE renders message chunks as
        // Markdown, and plain lines would lose their single breaks (soft breaks) while the
        // leading "1." "2." "3." would start an ordered list — the first runtime round's
        // two rendering effects, both avoided by fence.
        String table = lines.toString();
        String fence = fenceFor(table);
        sb.append(fence).append('\n').append(table);
        if (!table.endsWith("\n")) {
            sb.append('\n');
        }
        sb.append(fence).append("\n\nReasoning is streamed as thought chunks and is not part of this")
                .append(" history; the model re-sends every entry above with the next request,")
                .append(" and the entry numbers are the ids `/compact` consumes.");
        return sb.toString();
    }

    /**
     * Sends the context-window usage as a {@code usage_update} session update. The spec
     * requires both values and says to send nothing without a meaningful size — so an
     * unresolvable window (no config, no reachable backend) means no update. The used value
     * is the backend's own accounting (prompt + completion of the last request) when the
     * final usage chunk arrived, a chars/4 estimate of the history otherwise.
     */
    private void sendUsageUpdate(ChatIds ids, SyncPromptContext context, Measured measured) {
        long size = AcpContextWindow.effectiveWindow(modelUrl, modelName);
        if (size <= 0) {
            return;
        }
        long used = measured != null && measured.promptTokens() > 0
                ? measured.promptTokens() + measured.completionTokens()
                : estimateHistoryTokens(ids);
        if (used <= 0) {
            return;
        }
        try {
            agent.sendSessionUpdate(ids.sessionId(),
                    new AcpSchema.UsageUpdate(null, used, size, null, null));
        }
        catch (RuntimeException e) {
            LOG.debug("AcpChat usage_update failed: {}", e.getMessage());
        }
        if (context != null) {
            maybeUsageHint(context, used, size);
        }
    }

    /**
     * The usage RFD's warning bands, one hint per band per session. The hint rides as a
     * display-only message chunk (the S3 error-note pattern): visible in the IDE chat, never
     * in the history — the model must not read our gauge. Re-arms below 75% (a compaction).
     */
    private void maybeUsageHint(SyncPromptContext context, long used, long size) {
        int percent = (int) Math.round(used * 100.0 / size);
        int band = percent >= 95 ? 3 : percent >= 90 ? 2 : percent >= 75 ? 1 : 0;
        String sessionId = context.getSessionId();
        int previous = usageBandWarned.getOrDefault(sessionId, 0);
        if (band == 0) {
            usageBandWarned.remove(sessionId);
            return;
        }
        if (band <= previous) {
            return;
        }
        usageBandWarned.put(sessionId, band);
        String detail = switch (band) {
            case 2 -> " /chat list shows the largest entries.";
            case 3 -> " The next prompts may fail at the backend — open a new session"
                    + " (compaction follows in a later throw).";
            default -> "";
        };
        context.sendMessage("(Context usage " + percent + "%: " + grouped(used) + " of "
                + grouped(size) + " tokens." + detail + ")");
    }

    /** The chars/4 token estimate over the whole history (content plus tool-call arguments). */
    private static long estimateHistoryTokens(ChatIds ids) {
        List<ObjectNode> history = histories.get(ids.chatId());
        if (history == null) {
            return 0;
        }
        LOCK.lock();
        try {
            long chars = 0;
            for (ObjectNode message : history) {
                chars += message.path("content").asString("").length();
                JsonNode calls = message.get("tool_calls");
                if (calls != null && calls.isArray()) {
                    for (JsonNode call : calls) {
                        chars += call.path("function").path("arguments").asString("").length()
                                + call.path("function").path("name").asString("").length();
                    }
                }
            }
            return chars / 4;
        }
        finally {
            LOCK.unlock();
        }
    }

    // --- /compact (S8): the second throw ----------------------------------------------

    /**
     * The compaction fork: shrinks the selected entries in place and continues the
     * conversation in a fork child — the S3 fork pattern (chat id {@code session + "_c" + n},
     * flat from the session id; the parent chat untouched; {@code x-session-id} unchanged,
     * so the backend's warm prefix survives up to the first compacted entry). Every entry
     * keeps its role, its position and its tool pairing — a tool result remains the result
     * of its call — and only the content becomes a marker that names timestamp and sizes,
     * "[Compacted 2026-10-28T23:22:44: was ~31,497 tok, now ~250 tok]", followed by the
     * summary: no one mistakes it for the original output.
     *
     * <p>The summaries come from a tools-less loopback request without the conversation
     * headers, exactly how the fork children's own completions reach the store (key
     * "default"); a SubAgentRunner child was deliberately not used — it would run with
     * tools and inherit the conversation's occupation (docs/webui.md §3.8 point 16),
     * while a summary needs neither. When a summary request fails, the head of the
     * original content stands in (the truncation the command is named after) — the fork
     * still happens, and the parent chat still holds the original.</p>
     *
     * <p>Syntax: {@code /compact 4-7 12} (the /chat list numbers) or {@code /compact top N}
     * (the N largest entries). Every entry keeps its position, so the numbering is stable
     * across compactions; the summaries are generated highest-position first ("von
     * hinten"). An entry that already carries a marker stays as it is.</p>
     */
    private String compactChat(ChatIds ids, SyncPromptContext context, String args) {
        String what = args.strip();
        if (what.isEmpty() || "help".equals(what)) {
            return compactHelpText();
        }
        List<ObjectNode> snapshot;
        LOCK.lock();
        try {
            snapshot = List.copyOf(histories.getOrDefault(ids.chatId(), new ArrayList<>()));
        }
        finally {
            LOCK.unlock();
        }
        if (snapshot.isEmpty()) {
            return "(The conversation is empty — nothing to compact.)";
        }
        boolean systemSeeded = "system".equals(snapshot.get(0).path("role").asString(""));
        boolean[] selected = new boolean[snapshot.size()];
        if (what.startsWith("top")) {
            int count;
            try {
                count = Integer.parseInt(what.substring(3).strip());
            }
            catch (NumberFormatException e) {
                return compactUsage("'top' needs the entry count: /compact top 3");
            }
            if (count < 1) {
                return compactUsage("The entry count must be at least 1.");
            }
            List<Integer> rank = new ArrayList<>();
            for (int i = systemSeeded ? 1 : 0; i < snapshot.size(); i++) {
                rank.add(i);
            }
            rank.sort((a, b) -> Long.compare(spanTokens(snapshot, b, b), spanTokens(snapshot, a, a)));
            for (int r = 0; r < Math.min(count, rank.size()); r++) {
                selected[rank.get(r)] = true;
            }
        }
        else {
            for (String token : what.split("[,\\s]+")) {
                if (token.isEmpty()) {
                    continue;
                }
                int from;
                int to;
                int dash = token.indexOf('-');
                try {
                    if (dash > 0) {
                        from = Integer.parseInt(token.substring(0, dash));
                        to = Integer.parseInt(token.substring(dash + 1));
                    }
                    else {
                        from = to = Integer.parseInt(token);
                    }
                }
                catch (NumberFormatException e) {
                    return compactUsage("'" + token + "' is not an entry number or range.");
                }
                if (from < 1 || to > snapshot.size() || from > to) {
                    return compactUsage("Entries " + token + " are out of range 1-"
                            + snapshot.size() + ".");
                }
                for (int i = from; i <= to; i++) {
                    selected[i - 1] = true;
                }
            }
        }
        if (systemSeeded && selected[0]) {
            return "(Entry 1 is the system seed (AGENTS.md) — it stays; compact from entry 2 on.)";
        }
        Map<String, String> toolNames = new TreeMap<>();
        for (ObjectNode message : snapshot) {
            JsonNode calls = message.get("tool_calls");
            if (calls != null && calls.isArray()) {
                for (JsonNode call : calls) {
                    String callId = call.path("id").asString("");
                    if (!callId.isEmpty()) {
                        toolNames.put(callId, call.path("function").path("name").asString("?"));
                    }
                }
            }
        }
        // "Von hinten": the summaries are generated highest-position first, so the /chat
        // list numbering stays meaningful across the whole operation. An entry that already
        // carries a marker stays as it is — re-compacting a summary would burn a request
        // for nothing.
        Map<Integer, String> summaries = new TreeMap<>();
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            if (!selected[i]
                    || snapshot.get(i).path("content").asString("").startsWith("[Compacted")) {
                continue;
            }
            summaries.put(i, summarizeEntry(snapshot, i, toolNames));
        }
        List<ObjectNode> child = new ArrayList<>(snapshot.size());
        List<String> reportLines = new ArrayList<>();
        int shrunkEntries = 0;
        long beforeTokens = 0;
        long afterTokens = 0;
        String timestamp = LocalDateTime.now().withNano(0).toString();
        for (int i = 0; i < snapshot.size(); i++) {
            ObjectNode entry = snapshot.get(i);
            if (!selected[i]) {
                child.add(entry);
                continue;
            }
            String kind = entryKind(entry, toolNames);
            if (!summaries.containsKey(i)) {
                // already compacted — the marker stays as it is
                child.add(entry);
                reportLines.add("- Entry " + (i + 1) + " (" + kind + "): already compacted");
                continue;
            }
            long before = spanTokens(snapshot, i, i);
            String summary = summaries.get(i);
            String body = summary != null
                    ? summary
                    : head(entry.path("content").asString(""), COMPACTION_HEAD_CHARS);
            long after = Math.max(1, body.length() / 4);
            String content = "[Compacted " + timestamp + ": was ~" + grouped(before) + " tok, now ~"
                    + grouped(after) + " tok"
                    + (summary != null ? ""
                        : "; the summary request failed, the head of the original stands in")
                    + "] " + body;
            // The copy matters: the snapshot shares its nodes with the live parent history,
            // and only the child's content changes.
            ObjectNode shrunk = entry.deepCopy();
            shrunk.put("content", content);
            child.add(shrunk);
            shrunkEntries++;
            beforeTokens += before;
            afterTokens += after;
            reportLines.add("- Entry " + (i + 1) + " (" + kind + ", ~" + grouped(before) + " → ~"
                    + grouped(after) + " tok): "
                    + (summary != null ? summary : "_truncated head — summary request failed_"));
        }
        if (shrunkEntries == 0) {
            return "(Nothing to do — the selected entries are already compacted.)";
        }
        int seq = compactSeqBySession.merge(ids.sessionId(), 1, Integer::sum);
        String childChatId = ids.sessionId() + "_c" + seq;
        histories.put(childChatId, child);
        activeChatBySession.put(ids.sessionId(), childChatId);
        LOG.info("AcpChat compact: session {} forked chat {} -> {} ({} of {} entries shrunk,"
                        + " ~{} -> ~{} tok)",
                ids.sessionId(), ids.chatId(), childChatId, shrunkEntries, snapshot.size(),
                beforeTokens, afterTokens);
        sendUsageUpdate(new ChatIds(ids.sessionId(), childChatId), context, null);
        StringBuilder report = new StringBuilder("**Compacted ")
                .append(shrunkEntries)
                .append(shrunkEntries == 1 ? " entry" : " entries")
                .append(" (~").append(grouped(beforeTokens)).append(" → ~")
                .append(grouped(afterTokens)).append(" tok)** — the conversation continues in chat `")
                .append(shortChatId(ids.sessionId())).append("_c").append(seq)
                .append("`; the parent chat `").append(shortChatId(ids.chatId()))
                .append("` is untouched.\n\n");
        for (String line : reportLines) {
            report.append(line).append('\n');
        }
        report.append("\nEach entry keeps its role, its position and its tool pairing — only the content")
                .append(" became the marked summary, so nothing is mistaken for the original output.")
                .append(" `x-session-id` is unchanged, the backend's warm prefix survives up to the first")
                .append(" compacted entry. The next prompt and `/chat list` run in the child.");
        return report.toString();
    }

    private static String compactUsage(String problem) {
        return "(" + problem + " Usage: `/compact 4-7 12` or `/compact top 3` — numbers from"
                + " `/chat list`.)";
    }

    private static String compactHelpText() {
        return """
                **/compact — shrink chat entries to summaries in a forked continuation**

                - `/compact 4-7 12` — the listed entries (numbers from `/chat list`)
                - `/compact top 3` — the 3 largest entries

                Each selected entry shrinks in place: role, position and tool pairing stay,
                and the content becomes a marker with timestamp and sizes plus a summary —
                when the summary request fails, the head of the original stands in. The
                system seed (entry 1) stays. The child chat keeps the prefix up to the first
                compacted entry (warm backend cache); the parent chat is untouched.""";
    }

    /**
     * One summary per entry, from a tools-less loopback request without the conversation
     * headers: no {@code X-Conversation-Id} (the store keys it "default" — the fork
     * children's own requests do the same) and no {@code x-session-id} (a foreign prompt
     * under the chat's routing key could displace the warm prefix it is meant to preserve).
     * Null when the request failed — the marker then carries the head of the original.
     */
    private String summarizeEntry(List<ObjectNode> snapshot, int index,
                                  Map<String, String> toolNames) {
        ObjectNode entry = snapshot.get(index);
        String text = entry.path("content").asString("");
        StringBuilder labeled = new StringBuilder("[entry ").append(index + 1).append(" | ");
        JsonNode calls = entry.get("tool_calls");
        if (calls != null && calls.isArray() && !calls.isEmpty()) {
            labeled.append("assistant tool call(s)");
            for (JsonNode call : calls) {
                labeled.append(' ').append(call.path("function").path("name").asString("?"))
                        .append('(').append(call.path("function").path("arguments").asString(""))
                        .append(')');
            }
            labeled.append(']');
            if (!text.isBlank()) {
                labeled.append(' ').append(text);
            }
        }
        else if ("tool".equals(entry.path("role").asString(""))) {
            labeled.append("tool result for ")
                    .append(toolNames.getOrDefault(entry.path("tool_call_id").asString(""),
                            entry.path("tool_call_id").asString("")))
                    .append("] ").append(text);
        }
        else {
            labeled.append(entry.path("role").asString("")).append("] ").append(text);
        }
        ObjectNode body = JSON.createObjectNode();
        body.put("stream", false);
        body.put("max_tokens", 400);
        ArrayNode messages = body.putArray("messages");
        messages.add(message("system",
                "You compress excerpts of a work chat into summaries. Reply with a dense"
                        + " summary of at most 80 words. Preserve file paths, identifiers,"
                        + " numbers and outcomes. No preamble."));
        messages.add(message("user",
                "This entry of a work chat will be replaced by your summary:\n\n"
                        + labeled
                        + "\n\nSummarize what happened in it and what matters for the ongoing work."));
        HttpURLConnection connection = null;
        try {
            URL url = URI.create(selfBaseUrl + COMPLETIONS_PATH).toURL();
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            try (OutputStream out = connection.getOutputStream();
                 OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
                writer.write(JSON.writeValueAsString(body));
                writer.flush();
            }
            int status = connection.getResponseCode();
            if (status >= 400) {
                LOG.warn("AcpChat compact summary: HTTP {} ({})", status,
                        excerpt(readBody(connection.getErrorStream()), 200));
                return null;
            }
            JsonNode root = completionResult(readBody(connection.getInputStream()));
            String summary = root.path("choices").path(0).path("message").path("content")
                    .asString("").strip();
            if (summary.isEmpty()) {
                LOG.warn("AcpChat compact summary: the model answered without content");
                return null;
            }
            return summary;
        }
        catch (IOException | RuntimeException e) {
            LOG.warn("AcpChat compact summary failed: {}", e.toString());
            return null;
        }
        finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** The chars/4 estimate over one entry (the same metric the /chat list shows). */
    private static long spanTokens(List<ObjectNode> snapshot, int start, int end) {
        long chars = 0;
        for (int i = start; i <= end; i++) {
            ObjectNode message = snapshot.get(i);
            chars += message.path("content").asString("").length();
            JsonNode calls = message.get("tool_calls");
            if (calls != null && calls.isArray()) {
                for (JsonNode call : calls) {
                    chars += call.path("function").path("arguments").asString("").length()
                            + call.path("function").path("name").asString("").length();
                }
            }
        }
        return Math.max(1, chars / 4);
    }

    /** The kind label of a history entry as the listings show it ("tool ← get_file_text_by_path"). */
    private static String entryKind(ObjectNode message, Map<String, String> toolNames) {
        String role = message.path("role").asString("");
        JsonNode calls = message.get("tool_calls");
        if ("tool".equals(role)) {
            String callId = message.path("tool_call_id").asString("");
            return "tool ← " + toolNames.getOrDefault(callId, callId);
        }
        if ("assistant".equals(role) && calls != null && calls.isArray() && !calls.isEmpty()) {
            return "assistant → " + calls.size() + " call(s)";
        }
        return role.isEmpty() ? "?" : role;
    }

    /** The verbatim head of a text, keeping the line breaks — /compact's no-LLM fallback. */
    private static String head(String text, int chars) {
        if (text.length() <= chars) {
            return text;
        }
        return text.substring(0, chars) + "\n… (truncated)";
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
                try (InputStream is = connection.getInputStream();
                     InputStreamReader isr = new InputStreamReader(is, StandardCharsets.UTF_8);
                     BufferedReader reader = new BufferedReader(isr)) {
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
                        JsonNode usageNode = chunk.get("usage");
                        if (usageNode != null && usageNode.isObject()) {
                            long promptTokens = usageNode.path("prompt_tokens").asLong(0);
                            if (promptTokens > 0) {
                                lastMeasured.put(ids.chatId(), new Measured(promptTokens,
                                        usageNode.path("completion_tokens").asLong(0)));
                            }
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

            // The final usage chunk names what the backend counted for this request — the
            // gauge the usage_update rides on (sent per completion request, so a tool loop
            // shows the fill level growing turn by turn).
            sendUsageUpdate(ids, context, lastMeasured.get(ids.chatId()));

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

    /**
     * The completion result as JSON. The proxy answers a non-streaming request in its
     * "legacy" SSE shape — one {@code data:} line holding the whole response object, then
     * {@code [DONE]} (the WebUI expects SSE even for stream:false) — and the
     * SubAgentRunner unwraps the same shape for its children ({@code unwrapSse}); a plain
     * JSON body passes through unchanged.
     */
    private static JsonNode completionResult(String body) {
        String trimmed = body == null ? "" : body.stripLeading();
        if (!trimmed.startsWith("data:")) {
            return JSON.readTree(body);
        }
        for (String line : trimmed.split("\n")) {
            String payload = line.strip();
            if (!payload.startsWith("data:")) {
                continue;
            }
            payload = payload.substring("data:".length()).strip();
            if (!payload.isEmpty() && !"[DONE]".equals(payload)) {
                return JSON.readTree(payload);
            }
        }
        return JSON.readTree(body);
    }

    /** The chat label for listings: the first 8 characters, plus a "_cN" suffix a fork left. */
    private static String shortChatId(String chatId) {
        String head = chatId.substring(0, Math.min(8, chatId.length()));
        int fork = chatId.indexOf("_c");
        return fork > 0 ? head + chatId.substring(fork) : head;
    }

    private static String summarize(String text) {
        String oneLine = text.replace('\n', ' ');
        return oneLine.length() > 200 ? oneLine.substring(0, 200) + "…" : oneLine;
    }

    private static String excerpt(String text, int max) {
        String oneLine = text.replace('\n', ' ');
        return oneLine.length() > max ? oneLine.substring(0, max) + "…" : oneLine;
     }

    /** Thousand-separated number for the chat listings ("12,345"). */
    private static String grouped(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    /** Left-aligned column for the /chat list kinds, cut with an ellipsis when longer. */
    private static String padTo(String text, int len) {
        String oneLine = text.replace('\n', ' ');
        if (oneLine.length() >= len) {
            return oneLine.substring(0, Math.max(1, len - 1)) + "…";
        }
        StringBuilder sb = new StringBuilder(oneLine);
        while (sb.length() < len) {
            sb.append(' ');
        }
        return sb.toString();
    }

    /**
     * A fence longer than any backtick run in the content — a preview that quotes Markdown
     * must not be able to close the /chat list code block early (CommonMark: a run of N
     * backticks ends a fence of length ≤ N).
     */
    private static String fenceFor(String content) {
        int maxRun = 0;
        int run = 0;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '`') {
                run++;
                maxRun = Math.max(maxRun, run);
            }
            else {
                run = 0;
            }
        }
        return "`".repeat(Math.max(3, maxRun + 1));
    }

    /**
     * One Markdown bullet for a name plus an optional description. The command listings go
     * through the IDE's Markdown renderer, where a bare line break is a soft break (a space)
     * and a line starting "N." starts an ordered list — only real Markdown structure keeps
     * items apart, and the backticks keep names like edit_file_tool unitalicized.
     */
    private static String markdownListItem(String name, String description) {
        String desc = description == null ? "" : description.strip();
        StringBuilder sb = new StringBuilder("- `").append(name).append('`');
        if (!desc.isEmpty()) {
            sb.append(" — ").append(excerpt(desc.replace('\n', ' '), 90));
        }
        return sb.append('\n').toString();
    }
}
