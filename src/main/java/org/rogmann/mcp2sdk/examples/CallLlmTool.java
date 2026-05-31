package org.rogmann.mcp2sdk.examples;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpClientTransport;
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
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

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
    private static final String PROP_MAX_TOOL_CALLS = "llmtool.max.toolCalls";

    private static final String PROP_MCP_URL = "llmtool.mcp.url";

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

    /** optional MCP client */
    private final McpSyncClient mcpClient;

    /** optional list of MCP tools */
    private final McpSchema.ListToolsResult mcpTools;

    /**
     * Helper class to maintain session-specific state.
     */
    private static class CallLlmSession {
        final List<ObjectNode> conversationHistory = new ArrayList<>();
    }

    private CallLlmTool(String modelName, String modelUrl, Integer maxTokens, String mcpUrl) {
        this.state = new ToolState();
        this.modelName = modelName;
        this.modelUrl = modelUrl;
        this.maxTokens = maxTokens;
        this.jsonMapper = JsonMapper.builder().build();

        if (mcpUrl != null) {
            LOG.info("Connect to MCP server: {}", mcpUrl);
            McpClientTransport transport = HttpClientStreamableHttpTransport
                    .builder(mcpUrl)
                    .endpoint("/mcp")
                    .build();
            mcpClient = McpClient.sync(transport)
                    .requestTimeout(Duration.ofSeconds(10))
                    .capabilities(McpSchema.ClientCapabilities.builder()
                            //.roots(true)       // Enable roots capability
                            //.sampling()        // Enable sampling capability
                            //.elicitation()     // Enable elicitation capability
                            .build())
                    //.sampling(request -> new McpSchema.CreateMessageResult(response))
                    //.elicitation(request -> new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT, content))
                    .build();

            // Initialize connection
            mcpClient.initialize();

            mcpTools = mcpClient.listTools();
            mcpTools.tools().forEach(tool -> LOG.info("LLM-Tool: {}", tool.name()));
        } else {
            mcpClient = null;
            mcpTools = null;
        }
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

        String mcpUrl = System.getProperty(PROP_MCP_URL);

        CallLlmTool toolImpl = new CallLlmTool(modelName, modelUrl, maxTokens, mcpUrl);

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
        String exSessionId = exchange.sessionId();
        if (exSessionId == null || exSessionId.isBlank()) {
            LOG.warn("MCP SDK provided a null or blank session ID.");
            // Fallback to a random ID to avoid crashes, although SDK should provide one
            exSessionId = UUID.randomUUID().toString();
        }
        final String sessionId = exSessionId;

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
            // Update conversation history with user prompt
            ObjectNode userMsg = jsonMapper.createObjectNode();
            userMsg.put("role", "user");
            userMsg.put("type", "message");
            ArrayNode aContent = userMsg.putArray("content");
            ObjectNode textNode = aContent.addObject();
            textNode.put("text", prompt);
            textNode.put("type", "input_text");
            conversationHistory.add(userMsg);
            LOG.info("User-Prompt: {}", prompt);

            int toolCallsCount = 0;
            int maxToolCalls = Integer.getInteger(PROP_MAX_TOOL_CALLS, 20);
            String finalAssistantText = null;
            StringBuilder finalReasoning = new StringBuilder();

            do {
                // (Re)build the LLM request body
                ObjectNode llmRequest = jsonMapper.createObjectNode();
                llmRequest.put("model", modelName);
                ArrayNode inputNode = llmRequest.putArray("input");
                inputNode.addAll(conversationHistory);

                if (mcpTools != null) {
                    ArrayNode tools = llmRequest.putArray("tools");
                    for (McpSchema.Tool tool : mcpTools.tools()) {
                        ObjectNode objTool = tools.addObject();
                        objTool.put("type", "function");
                        objTool.put("name", tool.name());
                        objTool.put("description", tool.description());
                        JsonSchema jsonSchema = tool.inputSchema();
                        if (jsonSchema != null) {
                            ObjectNode paramsNode = jsonMapper.createObjectNode();
                            paramsNode.put("type", jsonSchema.type());
                            paramsNode.set("properties", jsonMapper.valueToTree(jsonSchema.properties()));
                            List<String> required = jsonSchema.required();
                            if (required != null && !required.isEmpty()) {
                                ArrayNode requiredArray = paramsNode.putArray("required");
                                required.forEach(requiredArray::add);
                            }
                            objTool.set("parameters", paramsNode);
                        }
                    }
                    llmRequest.put("tool_choice", "auto");
                }

                if (maxTokens != null) {
                    llmRequest.put("max_tokens", maxTokens);
                }
                llmRequest.put("stream", false);

                String requestBody = jsonMapper.writeValueAsString(llmRequest);
                LOG.debug("Request to LLM (iteration {}): {}", toolCallsCount, requestBody);

                // Send HTTP request and parse response
                StringBuilder reasoningBuilder = new StringBuilder();
                StringBuilder assistantBuilder = new StringBuilder();
                StringBuilder fullResponseBody = new StringBuilder();
                AtomicReference<JsonNode> refOutput = new AtomicReference<>();

                class SseState {
                    boolean isSse = false;
                    int reasonCount = 0;
                    Instant tsLastProgress = Instant.MIN;
                }
                SseState sseState = new SseState();

                sendHttpRequest(requestBody, line -> {
                    fullResponseBody.append(line).append("\n");
                    if (!sseState.isSse && (line.startsWith("event:") || line.startsWith("data:"))) {
                        sseState.isSse = true;
                    }
                    if (sseState.isSse && line.startsWith("data: ")) {
                        try {
                            String data = line.substring(6).trim();
                            if (data.isEmpty()) return;
                            JsonNode node = jsonMapper.readTree(data);
                            String type = node.path("type").asString();
                            if ("response.reasoning_text.delta".equals(type)) {
                                reasoningBuilder.append(node.path("delta").asString());
                                sseState.reasonCount++;
                                Instant tsNow = Instant.now();
                                if (Duration.between(sseState.tsLastProgress, tsNow).getSeconds() >= 2) {
                                    String progressToken = sessionId + '-' + sseState.reasonCount;
                                    int len = reasoningBuilder.length();
                                    String msg = len < 80 ? reasoningBuilder.toString() : "[...]" + reasoningBuilder.substring(len - 80, len);
                                    String progressMsg = "Reasoning %d: %s".formatted(sseState.reasonCount, msg);
                                    McpSchema.ProgressNotification progress = new McpSchema.ProgressNotification(progressToken,
                                            sseState.reasonCount, null, progressMsg);
                                    exchange.progressNotification(progress);
                                    LOG.debug("Progress {}: {}", sessionId, progressMsg);
                                    sseState.tsLastProgress = tsNow;
                                }
                            } else if ("response.output_text.delta".equals(type)) {
                                assistantBuilder.append(node.path("delta").asString());
                            } else if ("response.completed".equals(type)) {
                                refOutput.set(node.path("response").path("output"));
                            }
                        } catch (RuntimeException e) {
                            LOG.warn("Failed to parse SSE data line: {}", line, e);
                        }
                    }
                });

                String responseBody = fullResponseBody.toString();
                if (responseBody.isBlank()) {
                    return CallToolResult.builder().isError(true)
                            .addTextContent("No response received from the LLM service.")
                            .build();
                }

                LOG.debug("Response in session {}: {}", sessionId, responseBody);

                if (!sseState.isSse) {
                    try {
                        JsonNode root = jsonMapper.readTree(responseBody);
                        refOutput.set(root.get("output"));
                    } catch (RuntimeException e) {
                        LOG.error("Failed to parse standard JSON response", e);
                    }
                }

                JsonNode output = refOutput.get();
                if (output == null || !output.isArray()) {
                    if (assistantBuilder.isEmpty()) {
                        return CallToolResult.builder().isError(true).addTextContent("LLM response output was empty or invalid.").build();
                    }
                }

                // Detect tool calls in the response
                boolean foundToolCall = false;
                for (JsonNode itemNode : (output != null ? output : jsonMapper.createArrayNode())) {
                    if (!(itemNode instanceof ObjectNode item)) continue;
                    String type = item.path("type").asString();
                    if ("function_call".equals(type)) {
                        foundToolCall = true;
                        String toolName = item.path("name").asString();
                        String callId = item.path("call_id").asString();
                        JsonNode argsNode = item.get("arguments");

                        LOG.info("LLM requests tool call: {} with args {}", toolName, argsNode);

                        if (mcpClient == null) {
                            throw new RuntimeException("LLM requested tool call but mcpClient is not connected");
                        }

                        Map<String, Object> toolArgs = convertNodeToMap(argsNode);
                        LOG.info("mcpClient request: name={}, args={}", toolName, toolArgs);
                        McpSchema.CallToolResult toolResult = mcpClient.callTool(new CallToolRequest(toolName, toolArgs));

                        // Add tool call and result to conversation history
                        conversationHistory.add(item);
                        ObjectNode resultNode = jsonMapper.createObjectNode();
                        resultNode.put("type", "function_call_output");
                        resultNode.put("call_id", callId);
                        
                        StringBuilder resText = new StringBuilder();
                        if (toolResult.content() != null) {
                            for (McpSchema.Content c : toolResult.content()) {
                                if (c instanceof McpSchema.TextContent tc) resText.append(tc.text());
                            }
                        }
                        resultNode.put("output", resText.toString());
                        LOG.info("mcpClient response: {}", resText);
                        conversationHistory.add(resultNode);
                    } else if ("reasoning".equals(type)) {
                        JsonNode content = item.get("content");
                        if (content != null && content.isArray()) {
                            for (JsonNode c : content) {
                                if ("reasoning_text".equals(c.path("type").asString())) {
                                    finalReasoning.append(c.path("text").asString());
                                }
                            }
                        }
                    } else if ("message".equals(type)) {
                        JsonNode content = item.get("content");
                        if (content != null && content.isArray()) {
                            for (JsonNode c : content) {
                                if ("output_text".equals(c.path("type").asString())) {
                                    finalAssistantText = c.path("text").asString();
                                }
                            }
                        }
                    }
                }

                if (foundToolCall) {
                    toolCallsCount++;
                } else {
                    if (!assistantBuilder.isEmpty()) {
                        finalAssistantText = assistantBuilder.toString();
                    }
                    break;
                }
            } while (toolCallsCount < maxToolCalls);

            if (finalAssistantText == null) {
                return CallToolResult.builder().isError(true).addTextContent("Could not find output text in the LLM response.").build();
            }

            String finalResponse = finalAssistantText;
            if (showReasoning && !finalReasoning.isEmpty()) {
                finalResponse = "Reasoning:\n" + finalReasoning.toString().trim() + "\n\nAnswer:\n" + finalAssistantText;
            }

            ObjectNode assistantMsgNode = jsonMapper.createObjectNode();
            assistantMsgNode.put("role", "assistant");
            assistantMsgNode.put("type", "message");
            ArrayNode finalContent = assistantMsgNode.putArray("content");
            ObjectNode finalTextNode = finalContent.addObject();
            finalTextNode.put("type", "output_text");
            finalTextNode.put("text", finalAssistantText);
            conversationHistory.add(assistantMsgNode);

            state.callsOk().incrementAndGet();
            return CallToolResult.builder().isError(false).addTextContent(finalResponse).build();

        } catch (Exception e) {
            LOG.error("Error calling LLM tool for session " + sessionId, e);
            return CallToolResult.builder().isError(true).addTextContent("Internal error: " + e.getMessage()).build();
        }
    }

    /**
     * Sends a POST request to the configured LLM Responses API endpoint.
     * @param requestBody the JSON request body
     * @param lineConsumer consumer for each line of the response
     * @throws IOException if an I/O error occurs
     */
    private void sendHttpRequest(String requestBody, java.util.function.Consumer<String> lineConsumer) throws IOException {
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
            throw new IOException("HTTP error: " + responseCode);
        }

        try (InputStream is = connection.getInputStream();
             InputStreamReader isr = new InputStreamReader(is, StandardCharsets.UTF_8);
             BufferedReader br = new BufferedReader(isr)) {
            String line;
            while ((line = br.readLine()) != null) {
                lineConsumer.accept(line);
            }
        } finally {
            connection.disconnect();
        }
    }

    private Map<String, Object> convertNodeToMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            LOG.warn("Empty args in tool-call of type {}: {}", node == null ? "null" : node.getNodeType(), node);
            return Collections.emptyMap();
        }
        Map<String, Object> map = new HashMap<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            map.put(entry.getKey(), convertNodeToValue(entry.getValue()));
        }
        return map;
    }

    private Object convertNodeToValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> map = new HashMap<>();
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                map.put(entry.getKey(), convertNodeToValue(entry.getValue()));
            }
            return map;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode item : node) {
                list.add(convertNodeToValue(item));
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
}
