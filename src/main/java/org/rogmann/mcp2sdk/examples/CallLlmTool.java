package org.rogmann.mcp2sdk.examples;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;
import org.rogmann.mcp2sdk.ToolSpecWithState;
import org.rogmann.mcp2sdk.ToolState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP tool for calling an LLM to send a prompt or conduct a conversation within an MCP session.
 * This tool uses the OpenAI-compatible Responses API to interact with a language model.
 * It manages separate conversation histories per session using the session ID provided by the MCP exchange.
 */
public class CallLlmTool {

    private static final Logger LOG = LoggerFactory.getLogger(CallLlmTool.class);

    private static final String NAME = "call_llm_tool";
    private static final String DESCRIPTION = "Invokes an LLM to send a prompt or conduct a conversation with multiple prompts within an MCP session. The invoked LLM processes the request and returns the response.";

    // Configuration Properties
    private static final String PROP_MODEL_NAME = "llmtool.model.name";
    private static final String PROP_MODEL_URL = "llmtool.model.url";
    private static final String PROP_MAX_TOKENS = "llmtool.max.tokens";

    /** tool state (active-flag, statistics) */
    private final ToolState state;

    /** Session storage to separate conversation histories per user/session */
    private final Map<String, CallLlmSession> sessions = new ConcurrentHashMap<>();

    /** LLM configuration */
    private final String modelName;
    private final String modelUrl;
    private final Integer maxTokens;

    /** JsonMapper for JSON processing */
    private final JsonMapper jsonMapper;

    /**
     * Helper class to maintain session-specific state.
     */
    private static class CallLlmSession {
        final List<ObjectNode> conversationHistory = new ArrayList<>();
    }

    private CallLlmTool(String modelName, String modelUrl, Integer maxTokens) {
        this.state = new ToolState();
        this.modelName = modelName;
        this.modelUrl = modelUrl;
        this.maxTokens = maxTokens;
        this.jsonMapper = JsonMapper.builder().build();
    }

    /**
     * Creates the synchronous tool specification for calling an LLM.
     * @return the tool specification and its state
     */
    public static ToolSpecWithState createToolInstance() {
        // Define Input Schema properties
        Map<String, Object> properties = new HashMap<>();

        // Define 'prompt' property
        Map<String, Object> promptProp = new HashMap<>();
        promptProp.put("type", "string");
        promptProp.put("description", "Übergabe eines Prompts an das LLM.");
        properties.put("prompt", promptProp);

        // Define 'show_reasoning' property
        Map<String, Object> reasoningProp = new HashMap<>();
        reasoningProp.put("type", "boolean");
        reasoningProp.put("description", "Send the LLM's reasoning in the response, default is false.");
        properties.put("show_reasoning", reasoningProp);

        List<String> requiredFields = List.of("prompt");

        JsonSchema inputSchema = new JsonSchema("object", properties, requiredFields, null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(NAME)
            .description(DESCRIPTION)
            .inputSchema(inputSchema)
            .build();

        // Load configuration from system properties
        String modelName = System.getProperty(PROP_MODEL_NAME);
        if (modelName == null) {
            throw new RuntimeException("Required property is not set: " + PROP_MODEL_NAME);
        }

        String modelUrl = System.getProperty(PROP_MODEL_URL);
        if (modelUrl == null) {
            throw new RuntimeException("Required property is not set: " + PROP_MODEL_URL);
        }

        Integer maxTokens = Integer.getInteger(PROP_MAX_TOKENS);

        CallLlmTool toolImpl = new CallLlmTool(modelName, modelUrl, maxTokens);

        return new ToolSpecWithState(McpServerFeatures.SyncToolSpecification.builder()
                    .tool(tool)
                    .callHandler(toolImpl::call)
                    .build(),
                toolImpl.state);
    }

    /**
     * Handles the tool call request.
     * @param exchange the server exchange
     * @param request the tool call request
     * @return the tool call result
     */
    McpSchema.CallToolResult call(McpSyncServerExchange exchange, CallToolRequest request) {
        state.callCount().incrementAndGet();

        // Use the session ID managed by the MCP SDK
        String sessionId = exchange.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            LOG.warn("MCP SDK provided a null or blank session ID.");
            // Fallback to a random ID to avoid crashes, although SDK should provide one
            sessionId = UUID.randomUUID().toString();
        }

        // Retrieve or create the session
        CallLlmSession session = sessions.computeIfAbsent(sessionId, k -> {
            LOG.info("new MCP-session #{}: {}", sessions.size(), k);
            return new CallLlmSession();
        });
        List<ObjectNode> conversationHistory = session.conversationHistory;

        Map<String, Object> arguments = request.arguments();
        String prompt = (String) arguments.get("prompt");
        boolean showReasoning = Boolean.TRUE.equals(arguments.get("show_reasoning"));

        if (prompt == null || prompt.isBlank()) {
            return CallToolResult.builder()
                .isError(true)
                .addTextContent("Missing prompt in tool-call")
                .build();
        }

        try {
            // Update conversation history for the specific session with user prompt
            ObjectNode userMsg = jsonMapper.createObjectNode();
            userMsg.put("role", "user");
            userMsg.put("type", "message");
            ArrayNode aContent = userMsg.putArray("content");
            ObjectNode textNode = aContent.addObject();
            textNode.put("text", prompt);
            textNode.put("type", "input_text");
            conversationHistory.add(userMsg);
            LOG.info("User-Prompt: {}", prompt);

            // Prepare the LLM request for the Responses API
            ObjectNode llmRequest = jsonMapper.createObjectNode();
            llmRequest.put("model", modelName);
            
            ArrayNode inputNode = jsonMapper.createArrayNode();
            inputNode.addAll(conversationHistory);
            llmRequest.set("input", inputNode);

            if (maxTokens != null) {
                llmRequest.put("max_tokens", maxTokens);
            }

            String requestBody = jsonMapper.writeValueAsString(llmRequest);
            LOG.debug("Request to LLM for session {}: {}", sessionId, requestBody);

            // Execute HTTP request
            String responseBody = sendHttpRequest(requestBody);
            if (responseBody == null) {
                return CallToolResult.builder()
                    .isError(true)
                    .addTextContent("No response received from the LLM service.")
                    .build();
            }

            // Parse the Responses API response
            LOG.debug("Response in session {}: {}", sessionId, responseBody);
            JsonNode root = jsonMapper.readTree(responseBody);
            JsonNode output = root.get("output");

            if (output == null || !output.isArray()) {
                return CallToolResult.builder()
                    .isError(true)
                    .addTextContent("LLM response output was empty or invalid.")
                    .build();
            }

            ObjectNode assistantMsgNode = null;
            ObjectNode reasoningNode = null;

            StringBuilder reasoningBuilder = new StringBuilder();
            String assistantText = null;
            for (JsonNode itemNode : output) {
                if (!(itemNode instanceof ObjectNode item)) {
                    continue;
                }
                String type = item.has("type") ? item.get("type").asString() : "";
                if ("reasoning".equals(type)) {
                    if (showReasoning) {
                        JsonNode content = item.get("content");
                        if (content != null && content.isArray()) {
                            reasoningNode = item;
                            for (JsonNode contentItem : content) {
                                if ("reasoning_text".equals(contentItem.path("type").asString())) {
                                    reasoningBuilder.append(contentItem.path("text").asString()).append("\n");
                                }
                            }
                        }
                    }
                } else if ("message".equals(type)) {
                    JsonNode content = item.get("content");
                    if (content != null && content.isArray()) {
                        assistantMsgNode = item;
                        for (JsonNode contentItem : content) {
                            if ("output_text".equals(contentItem.path("type").asString())) {
                                assistantText = contentItem.path("text").asString();
                                break;
                            }
                        }
                    }
                }
                if (assistantText != null && (!showReasoning || !reasoningBuilder.isEmpty())) break;
            }

            if (assistantText == null) {
                return CallToolResult.builder()
                    .isError(true)
                    .addTextContent("Could not find output text in the LLM response.")
                    .build();
            }

            String finalResponse = assistantText;
            if (showReasoning && !reasoningBuilder.isEmpty()) {
                finalResponse = "Reasoning:\n" + reasoningBuilder.toString().trim() + "\n\nAnswer:\n" + assistantText;
            }

            if (reasoningNode != null) {
                conversationHistory.add(reasoningNode);
            }
            conversationHistory.add(assistantMsgNode);

            state.callsOk().incrementAndGet();
            return CallToolResult.builder()
                .isError(false)
                .addTextContent(finalResponse)
                .build();

        } catch (Exception e) {
            LOG.error("Error calling LLM tool for session " + sessionId, e);
            return CallToolResult.builder()
                .isError(true)
                .addTextContent("Internal error: " + e.getMessage())
                .build();
        }
    }

    /**
     * Sends a POST request to the configured LLM Responses API endpoint.
     * @param requestBody the JSON request body
     * @return the raw response body as a string, or null if the request failed
     * @throws IOException if an I/O error occurs
     */
    private String sendHttpRequest(String requestBody) throws IOException {
        String targetUrl = modelUrl;
        if (!targetUrl.endsWith("/responses")) {
            if (targetUrl.endsWith("/")) {
                targetUrl += "responses";
            } else {
                targetUrl += "/responses";
            }
        }

        URL url = URI.create(targetUrl).toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        connection.setDoOutput(true);

        try (OutputStream os = connection.getOutputStream();
             OutputStreamWriter osw = new OutputStreamWriter(os, StandardCharsets.UTF_8)) {
            osw.write(requestBody);
            osw.flush();
        }

        int responseCode = connection.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            LOG.error(String.format("HTTP error accessing %s: %d - %s", targetUrl, responseCode, connection.getResponseMessage()));
            return null;
        }

        try (InputStream is = connection.getInputStream();
             InputStreamReader isr = new InputStreamReader(is, StandardCharsets.UTF_8);
             BufferedReader br = new BufferedReader(isr)) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } finally {
            connection.disconnect();
        }
    }
}
