package org.rogmann.mcp2sdk.chat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Repairs the tool-call history of an outgoing chat request: a tool-call whose
 * {@code function.arguments} is no valid JSON (typically truncated when the backend hit its
 * output-token limit) is replaced by a placeholder, and the matching {@code role:"tool"}
 * message gets a dummy error answer naming the parse cause.
 *
 * <p>Moved out of {@code WebUiProxy} unchanged (docs/webui.md R3). The signature is deliberately
 * the old one - mutate the deep-copied request in place, return the number of repairs, write the
 * same error log line word for word - so both forwarding paths keep working exactly as before.</p>
 */
final class ToolCallHistorySanitizer {

    /** Logger */
    private static final Logger LOG = LoggerFactory.getLogger(ToolCallHistorySanitizer.class);

    /**
     * Mapper for the JSON checks and the dummy tool result. One of its own, because the sanitizer
     * is called from both forwarding paths and needs no request-specific configuration.
     */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Placeholder {@code arguments} used to replace an invalid assistant tool-call in the
     * forwarded request. The client-side history keeps its local copy; only the outgoing request
     * is repaired so backends that validate tool-call arguments (e.g. vLLM) accept it. */
    private static final String WRONG_INPUT_PLACEHOLDER = "{\"error\":\"[wrong input removed by WebUI.\"}";

    /** Max. length of invalid tool-call arguments written to the error log by {@link #sanitize}. */
    private static final int SANITIZE_LOG_ARGUMENTS_MAX = 1000;

    /**
     * Validates the {@code function.arguments} JSON of every assistant tool-call in the outgoing
     * request and repairs entries that became invalid (typically a tool-call whose arguments were
     * truncated mid-string when the backend hit its output-token limit).
     *
     * <p>A broken tool-call triggers two repairs so the forwarded request stays consistent for
     * backends that validate tool-call arguments (e.g. vLLM):</p>
     * <ul>
     *   <li>the invalid {@code arguments} are replaced by a safe placeholder
     *       ({@value #WRONG_INPUT_PLACEHOLDER}),</li>
     *   <li>the matching {@code role:"tool"} message (same {@code tool_call_id}) gets a dummy
     *       error answer that names the JSON parse cause.</li>
     * </ul>
     * <p>The faulty arguments are written to the error log (escaped, capped at
     * {@value #SANITIZE_LOG_ARGUMENTS_MAX} characters) so the original content stays traceable
     * even though it is no longer forwarded.</p>
     *
     * @param llmRequest the deep-copied request to forward to the LLM (mutated in place)
     * @return the number of repaired tool-calls
     */
    static int sanitize(ObjectNode llmRequest) {
        JsonNode messages = llmRequest.get("messages");
        if (messages == null || !messages.isArray()) {
            return 0;
        }
        int repaired = 0;
        // tool_call_id -> JSON parse error message, used to repair the matching tool results.
        Map<String, String> repairedToolCallIds = new HashMap<>();
        for (JsonNode message : messages) {
            if (message == null || !message.isObject()) {
                continue;
            }
            if (!"assistant".equals(message.path("role").asString(""))) {
                continue;
            }
            JsonNode toolCalls = message.get("tool_calls");
            if (toolCalls == null || !toolCalls.isArray()) {
                continue;
            }
            for (JsonNode toolCall : toolCalls) {
                if (toolCall == null || !toolCall.isObject()) {
                    continue;
                }
                JsonNode functionNode = toolCall.get("function");
                if (functionNode == null || !functionNode.isObject()) {
                    continue;
                }
                String id = toolCall.path("id").asString("<unknown-id>");
                String toolName = functionNode.path("name").asString("<unknown-tool>");
                JsonNode argumentsNode = functionNode.get("arguments");
                String arguments = (argumentsNode != null && argumentsNode.isString())
                        ? argumentsNode.asString() : "";
                String jsonError = firstJsonError(arguments);
                if (jsonError == null) {
                    continue;
                }
                ((ObjectNode) functionNode).put("arguments", WRONG_INPUT_PLACEHOLDER);
                repairedToolCallIds.put(id, jsonError);
                repaired++;
                String logArguments = arguments.length() <= SANITIZE_LOG_ARGUMENTS_MAX
                        ? arguments
                        : arguments.substring(0, SANITIZE_LOG_ARGUMENTS_MAX) + "...";
                LOG.error("Invalid tool-call arguments in chat history; replaced with placeholder "
                                + "(tool='{}', id='{}', jsonError={}): {}",
                        toolName, id, jsonError, escapeJsonString(logArguments));
            }
        }
        // Repair the matching tool-result messages so the assistant answer stays consistent.
        if (!repairedToolCallIds.isEmpty()) {
            for (JsonNode message : messages) {
                if (message == null || !message.isObject()) {
                    continue;
                }
                if (!"tool".equals(message.path("role").asString(""))) {
                    continue;
                }
                String jsonError = repairedToolCallIds.get(message.path("tool_call_id").asString(""));
                if (jsonError == null) {
                    continue;
                }
                ((ObjectNode) message).put("content", buildRefusedToolResult(jsonError));
            }
        }
        return repaired;
    }

    /**
     * Returns the JSON parse error message for the given text, or {@code null} if it is valid JSON.
     * Blank or missing text is treated as a parse error ("empty or missing arguments").
     *
     * @param json text to check
     * @return the parse error message, or {@code null} if the text is valid JSON
     */
    private static String firstJsonError(String json) {
        if (json == null || json.isBlank()) {
            return "empty or missing arguments";
        }
        try {
            JSON.readTree(json);
            return null;
        } catch (RuntimeException e) {
            String msg = e.getMessage();
            return (msg == null || msg.isBlank()) ? e.getClass().getSimpleName() : msg;
        }
    }

    /**
     * Escapes a text so it can be embedded safely into a single-line log message.
     * Quotes, backslashes and control characters become JSON escapes.
     *
     * @param text text to escape
     * @return the escaped text
     */
    private static String escapeJsonString(String text) {
        try {
            String literal = JSON.writeValueAsString(text);
            return literal.substring(1, literal.length() - 1);
        } catch (RuntimeException e) {
            return text.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
        }
    }

    /**
     * Builds the dummy tool-result JSON used to answer a repaired tool-call.
     *
     * @param cause the JSON parse error message to report
     * @return a JSON string for the tool message {@code content}
     */
    private static String buildRefusedToolResult(String cause) {
        ObjectNode node = JSON.createObjectNode();
        node.put("error", "Invalid input, refused tool call: Cause: " + cause);
        return JSON.writeValueAsString(node);
    }
}
