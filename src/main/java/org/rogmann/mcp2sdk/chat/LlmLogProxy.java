package org.rogmann.mcp2sdk.chat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Logging proxy for LLM requests (streaming / non-streaming) against an
 * OpenAI-compatible endpoint.
 *
 * <p>Unlike {@link WebUiProxy} this controller is a plain analysis proxy: it serves
 * {@code POST /llmproxy/v1/chat/completions} and forwards every request unchanged
 * (apart from {@code stream_options.include_usage} on streaming requests) to the
 * target server configured via the system property {@code llmproxy.url}.</p>
 *
 * <p>Logging:</p>
 * <ul>
 *   <li>every incoming request (INFO): URL, client IP/port and HTTP agent name</li>
 *   <li>end of request (INFO): size in bytes, requested model and - if the system
 *       property {@code llmproxy.showMsgStructure} is {@code true} - an overview of the
 *       message contents (system-prompt, tool definitions, user-requests, reasonings,
 *       answers) with length and a shortened preview
 *       ({@code <first 60 chars>(...N chars...)<last 20 chars>})</li>
 *   <li>response arrival (INFO): TTFT, #input-tokens, #output-tokens, PP t/s, TG t/s</li>
 *   <li>shutdown (INFO): aggregate statistics (avg TTFT, sum #input-tokens,
 *       sum #output-tokens, avg PP, avg TG) - only if the proxy was used</li>
 * </ul>
 */
@RestController
@RequestMapping("/llmproxy")
public class LlmLogProxy {

    /** Logger */
    private static final Logger LOG = LoggerFactory.getLogger(LlmLogProxy.class);

    /** System property holding the base URL of the LLM target server */
    private static final String PROP_URL = "llmproxy.url";
    /** System property enabling the message-structure overview (true/false) */
    private static final String PROP_SHOW_MSG_STRUCTURE = "llmproxy.showMsgStructure";
    /** OpenAI completions endpoint served and forwarded by this proxy */
    private static final String COMPLETIONS_PATH = "/v1/chat/completions";

    /** Preview: number of leading characters of a shortened text */
    private static final int PREVIEW_PREFIX = 60;
    /** Preview: number of trailing characters of a shortened text */
    private static final int PREVIEW_SUFFIX = 20;
    /** Preview: texts at or below this length are logged unshortened */
    private static final int PREVIEW_MAX_UNSHORTENED = 80;

    /** Base URL of the LLM target server (system property {@value #PROP_URL}) */
    @Value("${" + PROP_URL + ":http://localhost:8080}")
    private String targetUrl;

    /** JsonMapper for JSON processing (Jackson 3) */
    private final JsonMapper jsonMapper;

    /** Collected per-response statistics for the shutdown summary */
    private final List<ResponseStats> stats = Collections.synchronizedList(new ArrayList<>());

    /**
     * Statistics of a single proxied LLM request/response cycle.
     *
     * @param tsStart      timestamp when the request arrived
     * @param model        requested model name
     * @param ttftMs       time-to-first-token in ms (client-side; 0 if not measurable)
     * @param inputTokens  prompt tokens as reported by the server
     * @param outputTokens completion tokens as reported by the server
     * @param ppTPS        prompt-processing tokens per second (0 if unknown)
     * @param tgTPS        token-generation tokens per second (0 if unknown)
     */
    record ResponseStats(LocalDateTime tsStart, String model, long ttftMs,
                         long inputTokens, long outputTokens, float ppTPS, float tgTPS) {}

    /**
     * Token usage and optional server-side timings extracted from a response.
     *
     * @param inputTokens  prompt tokens (0 if not reported)
     * @param outputTokens completion tokens (0 if not reported)
     * @param ttftServerMs server-reported prompt time (llama.cpp {@code timings.prompt_ms}), or null
     * @param ppServerTPS  server-reported prompt rate, or null
     * @param tgServerTPS  server-reported generation rate, or null
     */
    private record Usage(long inputTokens, long outputTokens,
                         Long ttftServerMs, Float ppServerTPS, Float tgServerTPS) {}

    /**
     * Result of parsing a response into ordered content segments.
     *
     * @param segments     content segments (reasoning / answer / tool-call) in arrival order
     * @param finishReason finish reason of the response (null if not reported)
     */
    private record ParsedResponse(List<ContentSegment> segments, String finishReason) {}

    /**
     * One contiguous content segment of a response, e.g. a reasoning block, the answer
     * text or the arguments of a single tool call. Contiguous same-type content is
     * merged into one segment (SSE deltas arrive in small chunks).
     */
    private static final class ContentSegment {
        final String type; // "reasoning", "answer" or "tool-call"
        String toolName; // only for type "tool-call" (may arrive in a later delta)
        final StringBuilder text = new StringBuilder();

        ContentSegment(String type, String toolName) {
            this.type = type;
            this.toolName = toolName;
        }
    }

    /**
     * Constructor (Jackson 3: immutable mapper created via builder)
     */
    public LlmLogProxy() {
        this.jsonMapper = JsonMapper.builder().build();
    }

    /**
     * Forwarding of LLM requests (streaming / non-streaming).
     * Accessible at: /llmproxy/v1/chat/completions
     *
     * @param requestBody raw JSON request body
     * @param request     servlet request (client address, headers)
     * @param response    servlet response the LLM answer is written to
     */
    @PostMapping(value = COMPLETIONS_PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    public void completions(@RequestBody String requestBody,
                            HttpServletRequest request,
                            HttpServletResponse response) {
        final LocalDateTime tsStart = LocalDateTime.now();
        final long tsStartNano = System.nanoTime();

        // INFO log for every incoming request: URL, client IP/port, HTTP agent name.
        LOG.info("LLM request: POST {}, client={}:{}, agent={}, bytes={}",
                request.getRequestURI(),
                request.getRemoteAddr(), request.getRemotePort(),
                request.getHeader("User-Agent"),
                requestBody.getBytes(StandardCharsets.UTF_8).length);

        try {
            JsonNode requestNode = jsonMapper.readTree(requestBody);
            String model = requestNode.path("model").asString("unknown");
            boolean streaming = requestNode.path("stream").asBoolean(false);

            // Optional overview of the message structure.
            logMessageStructure(requestNode, model);

            if (streaming) {
                handleStreaming(requestNode, response, tsStart, tsStartNano, model);
            } else {
                handleNonStreaming(requestNode, response, tsStart, tsStartNano, model);
            }
        } catch (RuntimeException | IOException e) {
            LOG.error("Error processing LLM request", e);
            writeJsonError(response, 500, "Internal server error: " + e.getMessage());
        }
    }

    /**
     * Streams the LLM answer (SSE) directly to the client while collecting the data
     * needed for the response statistics (usage of the final chunk, first-token time).
     */
    private void handleStreaming(JsonNode requestNode, HttpServletResponse response,
                                 LocalDateTime tsStart, long tsStartNano, String model)
            throws IOException {
        // Deep copy: only the forwarded request is enriched with stream_options so the
        // final SSE chunk carries the usage (token counts) needed for the statistics.
        ObjectNode llmRequest = (ObjectNode) requestNode.deepCopy();
        ObjectNode streamOptions = jsonMapper.createObjectNode();
        streamOptions.put("include_usage", true);
        llmRequest.set("stream_options", streamOptions);
        String requestOut = jsonMapper.writeValueAsString(llmRequest);

        // SSE headers to the client
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no"); // Disable Nginx buffering if applicable

        HttpURLConnection connection = null;
        List<String> sseDataLines = new ArrayList<>();
        // nanoTime of the first output token (reasoning or answer content), 0 until captured
        AtomicLong firstTokenNano = new AtomicLong(0L);
        long responseBytes;
        try {
            connection = openConnection();

            try (OutputStream os = connection.getOutputStream()) {
                os.write(requestOut.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int responseCode = connection.getResponseCode();
            response.setStatus(responseCode);
            if (responseCode >= 300) {
                String errorBody = readErrorBody(connection);
                LOG.error("HTTP error accessing {} (configured llmproxy.url={}: {}): {} - {}: {}",
                        connection.getURL(), PROP_URL, resolveTargetUrl(),
                        responseCode, connection.getResponseMessage(), errorBody);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                try (OutputStream os = response.getOutputStream()) {
                    os.write(errorBody.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
                LOG.warn("LLM error response (streaming): status={}, target={}, bytes={}",
                        responseCode, connection.getURL(), errorBody.length());
                return;
            }

            String contentType = connection.getContentType();
            if (contentType != null) {
                response.setContentType(contentType);
            }

            try (InputStream is = connection.getInputStream();
                 OutputStream os = response.getOutputStream()) {
                responseBytes = copySseStream(is, os, sseDataLines, firstTokenNano);
                os.flush();
            }
        } catch (IOException e) {
            LOG.error("IO-error while calling LLM ({}): {}", connection != null ? connection.getURL() : "?",
                    e.getMessage(), e);
            if (!response.isCommitted()) {
                writeJsonError(response, 502, "Error proxying request to LLM: " + e.getMessage());
            }
            throw e;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }

        // --- RESPONSE STRUCTURE ---
        if (Boolean.parseBoolean(System.getProperty(PROP_SHOW_MSG_STRUCTURE, "false"))) {
            logResponseStructure(parseStreamingResponse(sseDataLines), model);
        }

        // --- RESPONSE STATISTICS ---
        long totalMs = millisElapsed(tsStartNano, System.nanoTime());
        long ttftMs = firstTokenNano.get() > 0L ? millisElapsed(tsStartNano, firstTokenNano.get()) : 0L;
        Usage usage = extractUsage(sseDataLines);

        long ppMs = usage.ttftServerMs() != null && usage.ttftServerMs() > 0 ? usage.ttftServerMs() : ttftMs;
        long tgMs = (ppMs > 0 && totalMs > ppMs) ? totalMs - ppMs : 0L;
        float ppTPS = usage.ppServerTPS() != null && usage.ppServerTPS() > 0
                ? usage.ppServerTPS()
                : (ppMs > 0 && usage.inputTokens() > 0 ? usage.inputTokens() * 1000f / ppMs : 0f);
        float tgTPS = usage.tgServerTPS() != null && usage.tgServerTPS() > 0
                ? usage.tgServerTPS()
                : (tgMs > 0 && usage.outputTokens() > 0 ? usage.outputTokens() * 1000f / tgMs : 0f);

        LOG.info("LLM response (streaming): model={}, bytes={}, TTFT={} ms, inputTokens={}, outputTokens={}, PP={} t/s, TG={} t/s",
                model, responseBytes, ttftMs, usage.inputTokens(), usage.outputTokens(),
                formatRate(ppTPS), formatRate(tgTPS));
        stats.add(new ResponseStats(tsStart, model, ttftMs,
                usage.inputTokens(), usage.outputTokens(), ppTPS, tgTPS));
    }

    /**
     * Buffers the non-streaming LLM answer, forwards it to the client and extracts
     * the usage/timings data for the response statistics.
     */
    private void handleNonStreaming(JsonNode requestNode, HttpServletResponse response,
                                    LocalDateTime tsStart, long tsStartNano, String model)
            throws IOException {
        String requestOut = jsonMapper.writeValueAsString(requestNode);

        HttpURLConnection connection = null;
        long responseBytes = 0;
        try {
            connection = openConnection();

            try (OutputStream os = connection.getOutputStream()) {
                os.write(requestOut.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int responseCode = connection.getResponseCode();
            response.setStatus(responseCode);
            if (responseCode >= 300) {
                String errorBody = readErrorBody(connection);
                LOG.error("HTTP error accessing {} (configured llmproxy.url={}: {}): {} - {}: {}",
                        connection.getURL(), PROP_URL, resolveTargetUrl(),
                        responseCode, connection.getResponseMessage(), errorBody);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                try (OutputStream os = response.getOutputStream()) {
                    os.write(errorBody.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
                LOG.warn("LLM error response (non-streaming): status={}, target={}, bytes={}",
                        responseCode, connection.getURL(), errorBody.length());
                return;
            }

            String contentType = connection.getContentType();
            if (contentType != null) {
                response.setContentType(contentType);
            }

            String responseBody;
            try (InputStream is = connection.getInputStream()) {
                responseBody = readResponse(is);
            }
            responseBytes = responseBody.getBytes(StandardCharsets.UTF_8).length;
            try (OutputStream os = response.getOutputStream()) {
                os.write(responseBody.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            // --- RESPONSE STRUCTURE / STATISTICS ---
            JsonNode responseNode = null;
            try {
                responseNode = jsonMapper.readTree(responseBody);
            } catch (RuntimeException e) {
                LOG.debug("Non-streaming response is not JSON, no structure/usage statistics: {}", e.getMessage());
            }
            Usage usage = responseNode != null ? extractUsageFromNode(responseNode)
                    : new Usage(0, 0, null, null, null);
            if (responseNode != null) {
                logResponseStructure(parseNonStreamingResponse(responseNode), model);
            }

            long totalMs = millisElapsed(tsStartNano, System.nanoTime());
            // Non-streaming: no client-side TTFT measurable - only the server timings help.
            long ttftMs = usage.ttftServerMs() != null ? usage.ttftServerMs() : 0L;
            long ppMs = ttftMs;
            long tgMs = 0L;
            Float ppServer = usage.ppServerTPS();
            Float tgServer = usage.tgServerTPS();
            if (usage.ttftServerMs() == null && ppServer == null && tgServer == null
                    && usage.inputTokens() > 0 && usage.outputTokens() > 0 && totalMs > 0) {
                // Fallback without any server timings: only the total wall-clock time is known,
                // so a combined rate over the whole duration is reported on the TG figure.
                tgServer = usage.outputTokens() * 1000f / totalMs;
                tgMs = totalMs;
            } else if (usage.ttftServerMs() != null) {
                // Derive the generation time from prompt_ms + predicted_ms if available.
                tgMs = Math.max(0L, totalMs - ppMs);
            }
            float ppTPS = ppServer != null && ppServer > 0
                    ? ppServer
                    : (ppMs > 0 && usage.inputTokens() > 0 ? usage.inputTokens() * 1000f / ppMs : 0f);
            float tgTPS = tgServer != null && tgServer > 0
                    ? tgServer
                    : (tgMs > 0 && usage.outputTokens() > 0 ? usage.outputTokens() * 1000f / tgMs : 0f);

            LOG.info("LLM response (non-streaming): model={}, bytes={}, TTFT={} ms, inputTokens={}, outputTokens={}, PP={} t/s, TG={} t/s",
                    model, responseBytes, ttftMs, usage.inputTokens(), usage.outputTokens(),
                    formatRate(ppTPS), formatRate(tgTPS));
            stats.add(new ResponseStats(tsStart, model, ttftMs,
                    usage.inputTokens(), usage.outputTokens(), ppTPS, tgTPS));
        } catch (IOException e) {
            LOG.error("IO-error while calling LLM ({}): {}", connection != null ? connection.getURL() : "?",
                    e.getMessage(), e);
            if (!response.isCommitted()) {
                writeJsonError(response, 502, "Error proxying request to LLM: " + e.getMessage());
            }
            throw e;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Resolves the full target URL of the completions endpoint of the configured target
     * server (system property {@value #PROP_URL}), e.g.
     * {@code http://192.168.178.41:7681/v1/chat/completions}.
     *
     * @return the full target URL
     */
    private String resolveTargetUrl() {
        String target = targetUrl;
        if (target.endsWith("/")) {
            target = target.substring(0, target.length() - 1);
        }
        return target + COMPLETIONS_PATH;
    }

    /**
     * Opens a POST connection to the completions endpoint of the configured target server
     * (system property {@value #PROP_URL}).
     *
     * @return an open, unconnected HttpURLConnection
     * @throws IOException if the URL is invalid
     */
    private HttpURLConnection openConnection() throws IOException {
        String target = resolveTargetUrl();

        URL url;
        try {
            url = URI.create(target).toURL();
        } catch (MalformedURLException e) {
            throw new RuntimeException("Invalid llmproxy.url (%s)".formatted(target), e);
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json, text/event-stream");
        connection.setDoOutput(true);
        return connection;
    }

    /**
     * Copies the SSE stream from the LLM to the client, counts the forwarded bytes,
     * collects the {@code data:} payloads for the usage extraction and records the
     * time of the first output token (reasoning or answer content).
     *
     * @param in             source stream (LLM)
     * @param out            target stream (client)
     * @param sseDataLines   collector for the SSE data payloads (JSON strings)
     * @param firstTokenNano atomic long receiving the {@link System#nanoTime()} of the first output token
     * @return number of bytes forwarded to the client
     */
    private long copySseStream(InputStream in, OutputStream out, List<String> sseDataLines,
                               AtomicLong firstTokenNano) throws IOException {
        long bytes = 0;
        StringBuilder pending = new StringBuilder();
        byte[] buffer = new byte[4096];
        int bytesRead;
        while ((bytesRead = in.read(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);
            out.flush(); // critical for SSE: push chunks immediately
            bytes += bytesRead;

            pending.append(new String(buffer, 0, bytesRead, StandardCharsets.UTF_8));
            int lineEnd;
            while ((lineEnd = pending.indexOf("\n")) >= 0) {
                String line = pending.substring(0, lineEnd);
                pending.delete(0, lineEnd + 1);
                handleSseLine(line, sseDataLines, firstTokenNano);
            }
        }
        return bytes;
    }

    /**
     * Processes a single SSE line: collects the payload of {@code data:} lines and
     * captures the first-token timestamp.
     */
    private void handleSseLine(String line, List<String> sseDataLines, AtomicLong firstTokenNano) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("data:")) {
            return;
        }
        String data = trimmed.substring("data:".length()).trim();
        if (data.isEmpty() || "[DONE]".equals(data)) {
            return;
        }
        sseDataLines.add(data);
        if (firstTokenNano.get() == 0L && hasOutputToken(data)) {
            firstTokenNano.compareAndExchange(0L, System.nanoTime());
        }
    }

    /**
     * Returns whether the given SSE payload contains an output token, i.e. a first-choice
     * delta carrying a non-empty {@code content}, {@code reasoning} or
     * {@code reasoning_content} string.
     */
    private boolean hasOutputToken(String dataLine) {
        JsonNode node;
        try {
            node = jsonMapper.readTree(dataLine);
        } catch (RuntimeException e) {
            return false; // not decodable JSON (e.g. keep-alive frame)
        }
        JsonNode choices = node.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            return false;
        }
        JsonNode delta = choices.get(0).get("delta");
        return delta != null && (isNonEmptyString(delta, "content")
                || isNonEmptyString(delta, "reasoning")
                || isNonEmptyString(delta, "reasoning_content"));
    }

    /**
     * Extracts the token usage (and optional llama.cpp server timings) from the collected
     * SSE data payloads. The last payload carrying a {@code usage} object wins.
     */
    private Usage extractUsage(List<String> sseDataLines) {
        for (int i = sseDataLines.size() - 1; i >= 0; i--) {
            try {
                JsonNode node = jsonMapper.readTree(sseDataLines.get(i));
                if (node.has("usage") && node.get("usage").isObject()) {
                    return extractUsageFromNode(node);
                }
            } catch (RuntimeException e) {
                // ignore non-JSON payloads
            }
        }
        return new Usage(0, 0, null, null, null);
    }

    /**
     * Extracts the token usage (and optional llama.cpp server timings) from a parsed
     * response node.
     */
    private Usage extractUsageFromNode(JsonNode node) {
        JsonNode usageNode = node.get("usage");
        long inputTokens = usageNode != null ? usageNode.path("prompt_tokens").asLong(0) : 0;
        long outputTokens = usageNode != null ? usageNode.path("completion_tokens").asLong(0) : 0;

        Long ttftServerMs = null;
        Float ppServerTPS = null;
        Float tgServerTPS = null;
        JsonNode timings = node.get("timings");
        if (timings != null && timings.isObject()) {
            double promptMs = timings.path("prompt_ms").asDouble(0);
            if (promptMs > 0) {
                ttftServerMs = Math.round(promptMs);
            }
            double pp = timings.path("prompt_per_second").asDouble(0);
            if (pp > 0) {
                ppServerTPS = (float) pp;
            }
            double tg = timings.path("predicted_per_second").asDouble(0);
            if (tg > 0) {
                tgServerTPS = (float) tg;
            }
        }
        return new Usage(inputTokens, outputTokens, ttftServerMs, ppServerTPS, tgServerTPS);
    }

    /**
     * Logs an overview of the request contents (INFO) if the system property
     * {@value #PROP_SHOW_MSG_STRUCTURE} is {@code true}.
     *
     * <p>Listed per entry: length and a shortened preview of the text
     * ({@code <first 60 chars>(...N chars...)<last 20 chars>}) for the system-prompt(s),
     * tool definitions, user-requests, reasonings and answers.</p>
     *
     * @param requestNode parsed request
     * @param model       requested model name
     */
    private void logMessageStructure(JsonNode requestNode, String model) {
        if (!Boolean.parseBoolean(System.getProperty(PROP_SHOW_MSG_STRUCTURE, "true"))) {
            return;
        }
        StringBuilder sb = new StringBuilder("Message structure (model=").append(model).append("):");

        // Tool definitions
        JsonNode tools = requestNode.get("tools");
        if (tools instanceof ArrayNode toolsArray) {
            int idx = 0;
            for (JsonNode tool : toolsArray) {
                JsonNode function = tool.get("function");
                String name = function != null ? function.path("name").asString("unknown") : "unknown";
                String description = function != null ? function.path("description").asString("") : "";
                sb.append("\n  tool-definition #%d: name=%s, length=%d, \"%s\"".formatted(
                        idx++, name, description.length(), preview(description)));
            }
        }

        // Messages: system-prompt, user-requests, reasonings, answers
        JsonNode messages = requestNode.get("messages");
        if (messages instanceof ArrayNode messagesArray) {
            int idxSystem = 0;
            int idxUser = 0;
            int idxReasoning = 0;
            int idxAnswer = 0;
            for (JsonNode message : messagesArray) {
                String role = message.path("role").asString("");
                String content = contentAsString(message.get("content"));
                String reasoning = message.path("reasoning_content").asString("");
                if (reasoning.isEmpty()) {
                    reasoning = message.path("reasoning").asString("");
                }
                switch (role) {
                    case "system" -> sb.append("\n  system-prompt #%d: length=%d, \"%s\"".formatted(
                            idxSystem++, content.length(), preview(content)));
                    case "user" -> sb.append("\n  user-request #%d: length=%d, \"%s\"".formatted(
                            idxUser++, content.length(), preview(content)));
                    case "assistant" -> {
                        if (!reasoning.isEmpty()) {
                            sb.append("\n  reasoning #%d: length=%d, \"%s\"".formatted(
                                    idxReasoning++, reasoning.length(), preview(reasoning)));
                        }
                        if (!content.isEmpty()) {
                            sb.append("\n  answer #%d: length=%d, \"%s\"".formatted(
                                    idxAnswer++, content.length(), preview(content)));
                        }
                        JsonNode toolCalls = message.get("tool_calls");
                        if (content.isEmpty() && reasoning.isEmpty()
                                && toolCalls instanceof ArrayNode calls && !calls.isEmpty()) {
                            for (JsonNode call : calls) {
                                String toolName = call.path("function").path("name").asString("unknown");
                                sb.append("\n  answer (tool-call): name=").append(toolName);
                            }
                        }
                    }
                    default -> {
                        // e.g. role=tool results - listed for completeness
                        if (!content.isEmpty()) {
                            sb.append("\n  tool-result (role=%s): length=%d, \"%s\"".formatted(
                                    role, content.length(), preview(content)));
                        }
                    }
                }
            }
        }

        LOG.info("{}", sb);
    }

    /**
     * Logs an overview of the response contents (INFO) if the system property
     * {@value #PROP_SHOW_MSG_STRUCTURE} is {@code true}.
     *
     * <p>The response is listed as ordered segments (reasonings, answers, tool-calls),
     * each with length and a shortened preview - so e.g. the answer following the last
     * tool call is visible in the log.</p>
     *
     * @param parsedResponse parsed response segments
     * @param model          requested model name
     */
    private void logResponseStructure(ParsedResponse parsedResponse, String model) {
        if (!Boolean.parseBoolean(System.getProperty(PROP_SHOW_MSG_STRUCTURE, "false"))) {
            return;
        }
        StringBuilder sb = new StringBuilder("Response structure (model=").append(model);
        if (parsedResponse.finishReason() != null && !parsedResponse.finishReason().isEmpty()) {
            sb.append(", finishReason=").append(parsedResponse.finishReason());
        }
        sb.append("):");
        int idxReasoning = 0;
        int idxAnswer = 0;
        int idxToolCall = 0;
        for (ContentSegment segment : parsedResponse.segments()) {
            String text = segment.text.toString();
            switch (segment.type) {
                case "reasoning" -> sb.append("\n  reasoning #%d: length=%d, \"%s\"".formatted(
                        idxReasoning++, text.length(), preview(text)));
                case "answer" -> sb.append("\n  answer #%d: length=%d, \"%s\"".formatted(
                        idxAnswer++, text.length(), preview(text)));
                case "tool-call" -> sb.append("\n  tool-call #%d: name=%s, args-length=%d, args=\"%s\"".formatted(
                        idxToolCall++, segment.toolName, text.length(), preview(text)));
                default -> { /* unknown segment type - skip */ }
            }
        }
        LOG.info("{}", sb);
    }

    /**
     * Parses the collected SSE data payloads of a streaming response into ordered
     * content segments. Contiguous same-type content (e.g. consecutive answer deltas)
     * is merged; tool-call deltas are merged per tool-call index.
     *
     * @param sseDataLines collected SSE data payloads (JSON strings)
     * @return parsed segments and the finish reason
     */
    private ParsedResponse parseStreamingResponse(List<String> sseDataLines) {
        List<ContentSegment> segments = new ArrayList<>();
        Map<Integer, ContentSegment> toolCallSegments = new HashMap<>();
        String finishReason = null;
        for (String dataLine : sseDataLines) {
            JsonNode node;
            try {
                node = jsonMapper.readTree(dataLine);
            } catch (RuntimeException e) {
                continue; // not decodable JSON (e.g. keep-alive frame)
            }
            JsonNode choices = node.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                continue;
            }
            JsonNode choice = choices.get(0);
            if (choice.hasNonNull("finish_reason")) {
                finishReason = choice.path("finish_reason").asString("");
            }
            JsonNode delta = choice.get("delta");
            if (delta == null) {
                continue;
            }
            String reasoning = firstNonEmptyText(delta, "reasoning_content", "reasoning");
            if (!reasoning.isEmpty()) {
                appendSegment(segments, "reasoning", null, reasoning);
            }
            String content = firstNonEmptyText(delta, "content");
            if (!content.isEmpty()) {
                appendSegment(segments, "answer", null, content);
            }
            JsonNode toolCalls = delta.get("tool_calls");
            if (toolCalls instanceof ArrayNode calls) {
                for (JsonNode call : calls) {
                    int index = call.path("index").asInt(0);
                    JsonNode function = call.get("function");
                    String name = function != null ? function.path("name").asString(null) : null;
                    String arguments = function != null ? function.path("arguments").asString("") : "";
                    ContentSegment segment = toolCallSegments.get(index);
                    if (segment == null) {
                        segment = new ContentSegment("tool-call", name);
                        segments.add(segment);
                        toolCallSegments.put(index, segment);
                    } else if (segment.toolName == null && name != null) {
                        segment.toolName = name; // name may arrive in a later delta
                    }
                    segment.text.append(arguments);
                }
            }
        }
        return new ParsedResponse(segments, finishReason);
    }

    /**
     * Parses a non-streaming response node (choices[0].message) into ordered content
     * segments.
     *
     * @param responseNode parsed response JSON
     * @return parsed segments and the finish reason
     */
    private ParsedResponse parseNonStreamingResponse(JsonNode responseNode) {
        List<ContentSegment> segments = new ArrayList<>();
        String finishReason = null;
        JsonNode choices = responseNode.get("choices");
        if (choices != null && choices.isArray() && !choices.isEmpty()) {
            JsonNode choice = choices.get(0);
            if (choice.hasNonNull("finish_reason")) {
                finishReason = choice.path("finish_reason").asString("");
            }
            JsonNode message = choice.get("message");
            if (message != null) {
                String reasoning = firstNonEmptyText(message, "reasoning_content", "reasoning");
                if (!reasoning.isEmpty()) {
                    appendSegment(segments, "reasoning", null, reasoning);
                }
                String content = firstNonEmptyText(message, "content");
                if (!content.isEmpty()) {
                    appendSegment(segments, "answer", null, content);
                }
                JsonNode toolCalls = message.get("tool_calls");
                if (toolCalls instanceof ArrayNode calls) {
                    for (JsonNode call : calls) {
                        JsonNode function = call.get("function");
                        String name = function != null ? function.path("name").asString("unknown") : "unknown";
                        String arguments = function != null ? function.path("arguments").asString("") : "";
                        appendSegment(segments, "tool-call", name, arguments);
                    }
                }
            }
        }
        return new ParsedResponse(segments, finishReason);
    }

    /**
     * Appends text to the last segment if it matches type and tool name, otherwise
     * starts a new segment.
     */
    private static void appendSegment(List<ContentSegment> segments, String type, String toolName, String text) {
        ContentSegment last = segments.isEmpty() ? null : segments.get(segments.size() - 1);
        if (last == null || !last.type.equals(type) || !Objects.equals(last.toolName, toolName)) {
            segments.add(new ContentSegment(type, toolName));
        }
        segments.get(segments.size() - 1).text.append(text);
    }

    /**
     * Returns the first non-empty string value among the given fields, or "".
     */
    private static String firstNonEmptyText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isString() && !value.asString().isEmpty()) {
                return value.asString();
            }
        }
        return "";
    }

    /**
     * Shortens a text for the message-structure overview: the first
     * {@value #PREVIEW_PREFIX} and last {@value #PREVIEW_SUFFIX} characters are kept,
     * separated by a marker naming the total length, e.g.
     * {@code "Du bist ein hilfreicher Assi(...1234 Zeichen ...)Fragen zu antworten."}.
     * Line breaks and tabs are escaped so the log line stays single-line.
     *
     * @param text text to shorten (may be null)
     * @return the single-line preview
     */
    private static String preview(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replace("\\", "\\\\")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
        if (oneLine.length() <= PREVIEW_MAX_UNSHORTENED) {
            return oneLine;
        }
        return oneLine.substring(0, PREVIEW_PREFIX)
                + "(..." + oneLine.length() + " Zeichen ...)"
                + oneLine.substring(oneLine.length() - PREVIEW_SUFFIX);
    }

    /**
     * Flattens the {@code content} field of a message to a plain string. OpenAI-compatible
     * APIs may send either a string or an array of content parts ({@code text} fields).
     */
    private static String contentAsString(JsonNode content) {
        if (content == null || content.isNull()) {
            return "";
        }
        if (content.isString()) {
            return content.asString();
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                if (part.has("text")) {
                    sb.append(part.path("text").asString(""));
                }
            }
            return sb.toString();
        }
        return content.asString("");
    }

    /**
     * Returns whether the given JSON object holds a non-empty string under the field name.
     */
    private static boolean isNonEmptyString(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isString() && !value.asString().isEmpty();
    }

    /**
     * Reads the whole content of an InputStream (UTF-8).
     */
    private String readResponse(InputStream inputStream) throws IOException {
        return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    }

    /**
     * Reads the error body of a failed (status &gt;= 400) HTTP connection.
     */
    private String readErrorBody(HttpURLConnection connection) {
        try (InputStream errorStream = connection.getErrorStream()) {
            return errorStream != null ? readResponse(errorStream) : "";
        } catch (IOException e) {
            LOG.debug("Failed to read error body: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Writes a plain JSON error to the client response.
     */
    private void writeJsonError(HttpServletResponse response, int status, String message) {
        try {
            response.setStatus(status);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            try (OutputStream os = response.getOutputStream()) {
                os.write(jsonMapper.writeValueAsString(
                        jsonMapper.createObjectNode().put("error", message))
                        .getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        } catch (IOException e) {
            LOG.error("Failed to write error response", e);
        }
    }

    /**
     * Formats a token rate for the log output ("0" for unknown rates).
     */
    private static String formatRate(float tps) {
        return tps > 0 ? String.format(java.util.Locale.ROOT, "%.1f", tps) : "0";
    }

    /**
     * Returns the elapsed milliseconds between two {@link System#nanoTime()} readings.
     */
    private static long millisElapsed(long startNano, long endNano) {
        long nanos = endNano - startNano;
        return nanos > 0 ? nanos / 1_000_000L : 0L;
    }

    /**
     * Shutdown hook: logs the aggregate statistics (avg TTFT, sum #input-tokens,
     * sum #output-tokens, avg PP, avg TG) - but only if the llmproxy was used at all.
     * Called by Spring when the application context is closed.
     */
    @PreDestroy
    public void onShutdown() {
        List<ResponseStats> snapshot;
        synchronized (stats) {
            snapshot = new ArrayList<>(stats);
        }
        if (snapshot.isEmpty()) {
            LOG.info("Shutdown: llmproxy was not used - no statistics.");
            return;
        }
        long sumInputTokens = snapshot.stream().mapToLong(ResponseStats::inputTokens).sum();
        long sumOutputTokens = snapshot.stream().mapToLong(ResponseStats::outputTokens).sum();
        double avgTtftMs = snapshot.stream().mapToLong(ResponseStats::ttftMs)
                .filter(ms -> ms > 0).average().orElse(0.0);
        double avgPpTps = snapshot.stream().mapToDouble(ResponseStats::ppTPS)
                .filter(tps -> tps > 0).average().orElse(0.0);
        double avgTgTps = snapshot.stream().mapToDouble(ResponseStats::tgTPS)
                .filter(tps -> tps > 0).average().orElse(0.0);
        LOG.info("Shutdown: llmproxy statistics - #Requests={}, avgTTFT={} ms, sumInputTokens={}, sumOutputTokens={}, avgPP={} t/s, avgTG={} t/s",
                snapshot.size(), Math.round(avgTtftMs), sumInputTokens, sumOutputTokens,
                formatRate((float) avgPpTps), formatRate((float) avgTgTps));
    }
}
