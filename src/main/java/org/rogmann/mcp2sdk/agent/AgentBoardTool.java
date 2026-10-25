package org.rogmann.mcp2sdk.agent;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import org.rogmann.mcp2sdk.ToolSpecWithState;
import org.rogmann.mcp2sdk.ToolState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * MCP tool {@code agent_board}: the message board of a running {@code prefix_fork} batch
 * (docs/subagent.md).
 *
 * <p>Only threads of a batch with {@code board:true} get in, and they get in identified. The
 * identity is resolved from the MCP session the call arrives on ({@link ForkIdentity}, P0): the
 * child's transport carries a header on every request, a filter paired it with the session, and
 * nothing a caller types can change what the server already knows. The injected argument
 * {@value #FORK_TAG_ARGUMENT} is still accepted - as the rollback path for a server without that
 * filter - but only while the registry knows nothing about the session, so a thread cannot put a
 * sibling's address where the session's own stands (docs/subagent.md §12.6, closed here). The tool
 * additionally refuses the one session that called {@code prefix_fork} itself: the caller reads the
 * board in the result digest and the artifact, not through this tool.</p>
 *
 * <p>Stage 1 (this build): {@code post} and {@code comment} work, {@code list} reads the board,
 * and {@code claim}/{@code close} answer with the stage rule - the caller decides what becomes
 * work.</p>
 */
public class AgentBoardTool {

    private static final Logger LOG = LoggerFactory.getLogger(AgentBoardTool.class);

    /** MCP name of the tool. */
    public static final String NAME = "agent_board";

    /**
     * Argument the runner used to inject into every board call of a thread:
     * {@code batchId/taskId/childIndex} (see {@link AgentId#tag()}). Since P0 the session answers
     * that question first and this argument is the fallback for a deployment without
     * {@link ForkIdentityFilter}; the runner still writes it, so both paths agree.
     */
    public static final String FORK_TAG_ARGUMENT = "_fork";

    /** What the model sees. Terse on purpose: the board is machinery, and machinery named is a subject. */
    private static final String DESCRIPTION = """
            Message board of the running fork (docs/subagent.md). The server knows which thread of \
            the batch this call arrives on; you cannot and need not state your identity.
            post: put an entry on the board - a finding that is not yours to act on belongs here, \
            not in an edit. kind INFO for an announcement (never worked on), TASK for proposed work.
            comment: add a line under an entry. list: read the board.
            claim/close: taking or finishing an entry is refused in stage 1; the caller decides \
            what becomes work. Keep entries short: the caller reads the board after the batch, \
            your siblings read it now.""";

    private final ToolState state = new ToolState();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    /** @return the tool specification and its state */
    public static ToolSpecWithState createToolInstance() {
        AgentBoardTool tool = new AgentBoardTool();
        return new ToolSpecWithState(McpServerFeatures.SyncToolSpecification.builder()
                .tool(buildToolSpec())
                .callHandler(tool::call)
                .build(),
                tool.state);
    }

    private static McpSchema.Tool buildToolSpec() {
        Map<String, Object> actionProp = new java.util.HashMap<>();
        actionProp.put("type", "string");
        actionProp.put("enum", List.of("post", "comment", "claim", "close", "list"));
        actionProp.put("description", "What to do on the board.");

        Map<String, Object> kindProp = new java.util.HashMap<>();
        kindProp.put("type", "string");
        kindProp.put("enum", List.of("TASK", "INFO"));
        kindProp.put("description", "post only: TASK (proposed work, the default) or INFO"
                + " (announcement, never claimed).");

        Map<String, Object> subjectProp = new java.util.HashMap<>();
        subjectProp.put("type", "string");
        subjectProp.put("description", "post: what the entry is about, one sentence.");

        Map<String, Object> taskIdProp = new java.util.HashMap<>();
        taskIdProp.put("type", "string");
        taskIdProp.put("description", "comment/claim/close: the entry id, m1, m2, ... (action list"
                + " shows them).");

        Map<String, Object> textProp = new java.util.HashMap<>();
        textProp.put("type", "string");
        textProp.put("description", "comment: the line to add. close: the outcome (optional).");

        Map<String, Object> properties = new java.util.HashMap<>();
        properties.put("action", actionProp);
        properties.put("kind", kindProp);
        properties.put("subject", subjectProp);
        properties.put("taskId", taskIdProp);
        properties.put("text", textProp);

        JsonSchema inputSchema = new JsonSchema("object", properties, List.of("action"), null, null, null);
        return McpSchema.Tool.builder()
                .name(NAME)
                .description(DESCRIPTION)
                .inputSchema(inputSchema)
                .build();
    }

    /**
     * Handles one {@code agent_board} call.
     *
     * @param exchange the server exchange: its session id keeps the fork's caller out
     * @param request  the tool-call request
     * @return a JSON answer: {@code ok}, {@code refused} or {@code error}
     */
    public CallToolResult call(McpSyncServerExchange exchange, CallToolRequest request) {
        state.callCount().incrementAndGet();
        Map<String, Object> arguments = (request.arguments() == null) ? Map.of() : request.arguments();
        ObjectNode answer = handle(exchange.sessionId(), arguments);
        return CallToolResult.builder()
                .isError(false)
                .addTextContent(answer.toString())
                .build();
    }

    /**
     * The whole behavior, reachable without an exchange so a unit test can drive it.
     *
     * @param sessionId MCP session of the caller, may be null
     * @param arguments arguments of the call, with the runner's {@value #FORK_TAG_ARGUMENT}
     * @return the answer as JSON
     */
    ObjectNode handle(String sessionId, Map<String, Object> arguments) {
        Identified identified = identify(sessionId, arguments);
        if (identified.refusal() != null) {
            return error(identified.refusal());
        }
        AgentId author = identified.author();
        AgentBoard board = AgentBoard.live(author.batchId());
        if (board == null) {
            return refused("no live board for batch '" + author.batchId() + "': board:true was not set"
                    + " for this fork, or the batch has already ended");
        }
        if (Objects.equals(sessionId, board.parentSessionId())) {
            return refused("the caller is the fork's caller, not one of its threads: read the board"
                    + " in the fork result (fork.board) and its artifact, not through this tool");
        }
        String action = string(arguments.get("action"));
        String taskId = string(arguments.get("taskId"));
        try {
            return switch (action == null ? "" : action) {
                case "post" -> done(board.post(author, string(arguments.get("kind")),
                        firstNonBlank(arguments.get("subject"), arguments.get("text"))), board, "posted");
                case "comment" -> done(board.comment(author, taskId,
                        firstNonBlank(arguments.get("text"), arguments.get("subject"))), board, "commented");
                case "claim" -> done(board.claim(author, taskId), board, "claimed");
                case "close" -> done(board.close(author, taskId, string(arguments.get("text"))),
                        board, "closed");
                case "list" -> {
                    // Reading the board is contact with every entry the answer carries (decision B3).
                    board.noteRead(author);
                    yield board.artifact(jsonMapper);
                }
                default -> error("action must be one of post, comment, claim, close, list");
            };
        } catch (RuntimeException e) {
            // The board's refusals are answers, not defects: stage rules, closed entries, ceilings.
            LOG.debug("Board {}: {} by {}: {}", board.batchId(), action, author, e.getMessage());
            return refused(e.getMessage());
        }
    }

    /** Either the caller or the reason the call is nobody - never a guessed middle. */
    private record Identified(AgentId author, String refusal) {
    }

    /**
     * Who is speaking, session first (docs/subagent.md §14, P0.4).
     *
     * <p>The registry knows the sessions of a fork's threads because the child's transport carries
     * the fork header on every request and a filter paired it with the session. Where it knows
     * something, the argument cannot override it - that is what closes the residual risk of §12.6
     * (a thread giving itself a sibling's address). Where it knows nothing (a server without the
     * filter, a unit test), the runner's injected argument is still the identity, so a deployment
     * can drop the filter without dropping the board.</p>
     */
    private static Identified identify(String sessionId, Map<String, Object> arguments) {
        ForkIdentity.Entry bySession = ForkIdentity.getInstance().lookup(sessionId);
        Object tagObject = arguments.get(FORK_TAG_ARGUMENT);
        String tag = (tagObject instanceof String text && !text.isBlank()) ? text.trim() : null;
        if (bySession != null) {
            if (tag != null && !tag.equals(bySession.tag())) {
                LOG.warn("agent_board: the session speaks for {}, the arguments claim '{}' - the"
                        + " session wins", bySession.tag(), tag);
            }
            return new Identified(new AgentId(bySession.batchId(), bySession.taskId(),
                    bySession.childIndex()), null);
        }
        if (tag == null) {
            return new Identified(null, "this call carries no board identity (" + FORK_TAG_ARGUMENT
                    + "): it did not come from a thread of a running fork");
        }
        ForkIdentity.Entry parsed = ForkIdentity.parseTag(tag);
        if (parsed == null) {
            return new Identified(null, "malformed board identity: " + tag);
        }
        return new Identified(new AgentId(parsed.batchId(), parsed.taskId(), parsed.childIndex()), null);
    }

    private ObjectNode done(AgentTask task, AgentBoard board, String verb) {
        ObjectNode root = board.summary(jsonMapper);
        root.put("ok", true);
        root.put("action", verb);
        ObjectNode entry = root.putObject("entry");
        entry.put("id", task.id());
        entry.put("kind", task.kind().name());
        entry.put("status", task.status().name());
        entry.put("messages", task.messages().size());
        return root;
    }

    private ObjectNode refused(String message) {
        ObjectNode root = jsonMapper.createObjectNode();
        root.put("refused", message);
        return root;
    }

    private ObjectNode error(String message) {
        ObjectNode root = jsonMapper.createObjectNode();
        root.put("error", message);
        return root;
    }

    private static String string(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private static String firstNonBlank(Object first, Object second) {
        String a = string(first);
        return (a != null) ? a : string(second);
    }
}
