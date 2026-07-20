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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Controller for the great <a href="https://github.com/ggml-org/llama.cpp/tree/master/tools/server/">llama.cpp Web UI</a>.
 * Provides a web interface and forwards LLM requests to an OpenAI-compatible endpoint.
 *
 * <p>Endpoint structure (all relative to /chat):
 * - /chat/*              : Static web resources (HTML, CSS, JS, images)
 * - /chat/props          : Model properties (llama.cpp compatible)
 * - /chat/v1/models      : List of available models
 * - /chat/v1/chat/completions: LLM request forwarding (supports Streaming)
 * - /chat/cors-proxy     : CORS proxy placeholder
 * </p>
 */
@RestController
@RequestMapping("/chat")
public class WebUiProxy {

    /** Logger */
    private static final Logger LOG = LoggerFactory.getLogger(WebUiProxy.class);

    // --- Configuration Property Keys ---
    private static final String PROP_WEBUI_PUBLIC_PATH = "webui.public.path";
    private static final String PROP_MODEL_NAME = "webui.model.name";
    private static final String PROP_MODEL_URL = "webui.model.url";
    private static final String PROP_HAS_VISION = "webui.hasVision";
    private static final String PROP_HAS_AUDIO = "webui.hasAudio";
    private static final String PROP_MAX_TOKENS = "webui.max.tokens";
    private static final String PROP_LOG_FILE = "webui.stats.file";

    /**
     * Records usage statistics for a single LLM request/response cycle.
     *
     * @param tsStart         timestamp when the request started
     * @param millisPP        milliseconds for prompt processing (time-to-first-token)
     * @param millisTG        milliseconds for token generation (after first token until completion)
     * @param model           model name as reported by the server
     * @param promptTokens    number of prompt tokens sent
     * @param completionTokens number of completion tokens generated
     * @param totalTokens     total tokens (prompt + completion)
     * @param cachedTokens    number of cached/prompt tokens reused (0 if not reported)
     * @param ppTPS           prompt processing tokens per second
     * @param tgTPS           token generation tokens per second
     */
    public record LlmUsage(LocalDateTime tsStart, long millisPP, long millisTG, String model,
                           long promptTokens, long completionTokens, long totalTokens,
                           long cachedTokens, float ppTPS, float tgTPS) {}

    /** Collected usage statistics for all LLM requests */
    private final List<LlmUsage> usages = Collections.synchronizedList(new ArrayList<>());

    /** Path to the JSONL statistics file (set via property {@value #PROP_LOG_FILE}) */
    @Value("${" + PROP_LOG_FILE + ":}")
    private String statsFilePath;

    /** Path to static web content (file system) */
    @Value("${" + PROP_WEBUI_PUBLIC_PATH + ":}")
    private String publicPath;

    /** Flag to indicate if classpath resources should be used */
    private boolean useClasspathResources = false;

    /** Name of the LLM model */
    @Value("${" + PROP_MODEL_NAME + ":unknown}")
    private String modelName;

    /** URL of the LLM endpoint */
    @Value("${" + PROP_MODEL_URL + ":http://localhost:8080}")
    private String modelUrl;

    /** Vision capabilities */
    @Value("${" + PROP_HAS_VISION + ":false}")
    private boolean hasVision;

    /** Audio capabilities */
    @Value("${" + PROP_HAS_AUDIO + ":false}")
    private boolean hasAudio;

    /** Maximum tokens (only set if explicitly configured) */
    @Value("${" + PROP_MAX_TOKENS + ":}")
    private Integer maxTokens;

    /** Pattern for detecting JSON responses (possibly Tool-Call) */
    private static final Pattern REG_EXP_JSON = Pattern.compile("[{].*\".*[}]", Pattern.DOTALL);

    /** JsonMapper for JSON processing (Jackson 3) */
    private final JsonMapper jsonMapper;

    /**
     * Constructor with JsonMapper (Jackson 3)
     */
    public WebUiProxy() {
        // Jackson 3: ObjectMapper is immutable, must be created via Builder
        this.jsonMapper = JsonMapper.builder().build();
    }

    /**
     * Serves static resources under /chat/*
     */
    @GetMapping(value = "/**", produces = {MediaType.TEXT_HTML_VALUE, "text/css",
            MediaType.APPLICATION_JSON_VALUE, "text/javascript",
            MediaType.IMAGE_PNG_VALUE, MediaType.IMAGE_JPEG_VALUE})
    public ResponseEntity<byte[]> serveStaticResource(HttpServletRequest request,
                                                      HttpServletResponse response) {

        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith("/chat")) {
            path = path.substring(5); // remove "/chat"
        }
        if (path.isEmpty() || path.equals("/")) {
            path = "/index.html";
        }

        LOG.info("{} GET static resource: {}", LocalDateTime.now(), path);

        // Determine if we should use classpath resources
        boolean useClasspath = shouldUseClasspathResources();

        try {
            byte[] content;
            boolean isGzipped = false;

            if (useClasspath) {
                // Load from classpath (JAR) - resources are in public/chat/ to avoid conflict with Spring Boot's static resource handling
                String resourcePath = "public/chat" + path;
                String gzResourcePath = resourcePath + ".gz";

                // Try gzipped version first
                InputStream gzStream = getClass().getClassLoader().getResourceAsStream(gzResourcePath);
                if (gzStream != null) {
                    try (GZIPInputStream gis = new GZIPInputStream(gzStream)) {
                        content = readAllBytes(gis);
                    }
                    isGzipped = true;
                    LOG.debug("Loaded gzipped resource from classpath: {}", gzResourcePath);
                } else {
                    // Try uncompressed version
                    InputStream resourceStream = getClass().getClassLoader().getResourceAsStream(resourcePath);
                    if (resourceStream == null) {
                        return ResponseEntity.status(404).body("File not found".getBytes(StandardCharsets.UTF_8));
                    }
                    content = readAllBytes(resourceStream);
                    LOG.debug("Loaded resource from classpath: {}", resourcePath);
                }
            } else {
                // Load from file system
                File requestedFile = new File(publicPath + path);
                File gzFile = new File(publicPath + path + ".gz");

                // Security check for path traversal
                File canonicalFile = requestedFile.getCanonicalFile();
                Path publicPathCanonical = Paths.get(publicPath).toRealPath();
                if (!canonicalFile.toPath().startsWith(publicPathCanonical)) {
                    LOG.warn("Forbidden path traversal attempt: {} -> {}", path, canonicalFile);
                    return ResponseEntity.status(403).body("Forbidden path".getBytes(StandardCharsets.UTF_8));
                }

                // Determine which file to serve
                File serveFile;
                if (gzFile.exists()) {
                    serveFile = gzFile;
                    isGzipped = true;
                } else if (requestedFile.exists()) {
                    serveFile = requestedFile;
                } else {
                    return ResponseEntity.status(404).body("File not found".getBytes(StandardCharsets.UTF_8));
                }

                if (isGzipped) {
                    // Decompress gz file
                    try (InputStream fis = new FileInputStream(gzFile);
                         GZIPInputStream gis = new GZIPInputStream(fis)) {
                        content = readAllBytes(gis);
                    }
                } else {
                    content = Files.readAllBytes(serveFile.toPath());
                }
            }

            // Determine content type based on extension
            String ext = path.substring(path.lastIndexOf('.') + 1);
            String contentType = "application/octet-stream";
            switch (ext.toLowerCase()) {
                case "html" -> contentType = "text/html";
                case "css" -> contentType = "text/css";
                case "js", "mjs" -> contentType = "text/javascript";
                case "ico" -> contentType = "image/x-icon";
                case "png" -> contentType = "image/png";
                case "jpg", "jpeg" -> contentType = "image/jpeg";
                case "svg" -> contentType = "image/svg+xml";
                case "json" -> contentType = "application/json";
                default -> { /* keep default */ }
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(contentType));
            headers.setContentLength(content.length);

            return ResponseEntity.ok().headers(headers).body(content);
        } catch (IOException e) {
            LOG.error("Error reading resource: {}", path, e);
            return ResponseEntity.status(500).body("Error reading resource".getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Determines whether to use classpath resources or file system.
     * Uses classpath if publicPath is empty or does not exist as a directory.
     */
    private boolean shouldUseClasspathResources() {
        if (publicPath == null || publicPath.isBlank()) {
            useClasspathResources = true;
            return true;
        }
        Path path = Paths.get(publicPath);
        if (!Files.exists(path) || !Files.isDirectory(path)) {
            useClasspathResources = true;
            return true;
        }
        useClasspathResources = false;
        return false;
    }

    /**
     * Delivers model properties (llama.cpp compatible)
     * Accessible at: /chat/props
     */
    @GetMapping(value = "/props", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getProps() {
        LOG.info("{} GET /chat/props", LocalDateTime.now());

        String response = buildJsonProps();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Keep-Alive", "timeout=5, max=100");

        return ResponseEntity.ok().headers(headers).body(response);
    }

    /**
     * Placeholder for future CORS proxy
     * Accessible at: /chat/cors-proxy
     */
    @RequestMapping(value = "/cors-proxy", method = RequestMethod.HEAD)
    public ResponseEntity<Void> corsProxyHead() {
        LOG.info("{} HEAD /chat/cors-proxy", LocalDateTime.now());

        HttpHeaders headers = new HttpHeaders();
        headers.setAccessControlAllowOrigin("*");
        headers.setAccessControlAllowMethods(List.of(HttpMethod.OPTIONS, HttpMethod.GET, HttpMethod.POST));
        headers.setAccessControlAllowHeaders(List.of("Content-Type", "Authorization"));

        return ResponseEntity.ok().headers(headers).build();
    }

    /**
     * List of known models
     * Accessible at: /chat/v1/models
     */
    @GetMapping(value = "/v1/models", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getModels() {
        LOG.info("{} GET /chat/v1/models", LocalDateTime.now());

        ObjectNode root = jsonMapper.createObjectNode();
        ArrayNode data = jsonMapper.createArrayNode();

        ObjectNode model = jsonMapper.createObjectNode();
        model.put("id", modelName);
        model.put("object", "model");
        model.put("created", System.currentTimeMillis() / 1000);
        model.put("owned_by", "user");

        data.add(model);
        root.set("data", data);
        root.put("object", "list");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            return ResponseEntity.ok().headers(headers).body(jsonMapper.writeValueAsString(root));
        } catch (RuntimeException e) {
            LOG.error("Error serializing models", e);
            return ResponseEntity.status(500).body("{\"error\": \"Internal server error\"}");
        }
    }

    /**
     * Forwarding of LLM requests (streaming / non-streaming)
     * Accessible at: /chat/v1/chat/completions
     */
    @PostMapping(value = "/v1/chat/completions",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public void completions(@RequestBody String requestBody,
                            @RequestHeader(value = "Cookie", required = false) String cookie,
                            HttpServletRequest request,
                            HttpServletResponse response) {
        LOG.info("{} POST /chat/v1/chat/completions", LocalDateTime.now());
        LOG.debug("Request body: {}", requestBody);

        try {
            // Parse incoming JSON (Jackson 3)
            JsonNode requestNode = jsonMapper.readTree(requestBody);

            // Extract path after /chat for dynamic forwarding
            String requestPath = request.getRequestURI();
            if (requestPath.contains("/chat")) {
                requestPath = requestPath.substring(requestPath.indexOf("/chat") + 5);
            }
            if (!requestPath.startsWith("/")) {
                requestPath = "/" + requestPath;
            }

            // Check if streaming is requested
            boolean isStreaming = requestNode.has("stream") && requestNode.get("stream").asBoolean(false);

            if (isStreaming) {
                // --- TRUE STREAMING MODE ---
                // Set SSE headers immediately
                response.setContentType("text/event-stream");
                response.setCharacterEncoding("UTF-8");
                response.setHeader("Cache-Control", "no-cache");
                response.setHeader("Connection", "keep-alive");
                response.setHeader("X-Accel-Buffering", "no"); // Disable Nginx buffering if applicable

                // Forward request and pipe stream directly to response
                forwardRequestToLLM(requestNode, cookie, requestPath, response, true);
            } else {
                // --- NON-STREAMING MODE (Legacy Compatibility) ---
                // Keep existing behavior: Buffer response, wrap in SSE, then send.
                // This ensures the WebUI doesn't break if it expects SSE format for non-streaming.
                response.setContentType("text/event-stream");
                response.setCharacterEncoding("UTF-8");

                String llmResponse = forwardRequestToLLMBuffered(requestNode, cookie, requestPath);

                if (llmResponse != null) {
                    // Convert to SSE format (Legacy behavior)
                    String sseResponse = String.format("data: %s\n\ndata: [DONE]\n\n", llmResponse);
                    try (PrintWriter writer = response.getWriter()) {
                        writer.write(sseResponse);
                        writer.flush();
                    }
                } else {
                    response.setStatus(500);
                    try (PrintWriter writer = response.getWriter()) {
                        writer.write("data: {\"error\": \"No LLM response\"}\n\n");
                        writer.flush();
                    }
                }
            }

        } catch (RuntimeException | IOException e) {
            LOG.error("Error processing completion request", e);
            try {
                response.setStatus(500);
                response.setContentType("text/event-stream");
                try (PrintWriter writer = response.getWriter()) {
                    writer.write("data: {\"error\": \"Internal server error: " + e.getMessage() + "\"}\n\n");
                    writer.flush();
                }
            } catch (IOException ex) {
                LOG.error("Failed to write error response", ex);
            }
        }
    }

    /**
     * Creates a llama.cpp compatible JSON with model properties
     */
    private String buildJsonProps() {
        ObjectNode mapProps = jsonMapper.createObjectNode();
        ObjectNode mapDefGenSettings = jsonMapper.createObjectNode();
        ObjectNode mapParams = jsonMapper.createObjectNode();

        mapParams.put("top_k", 20);
        mapParams.put("top_p", 0.95);
        mapDefGenSettings.set("params", mapParams);
        mapDefGenSettings.put("n_ctx", 32768);

        mapProps.set("default_generation_settings", mapDefGenSettings);
        mapProps.put("total_slots", 1);
        mapProps.put("model_path", modelName);

        ObjectNode modalities = jsonMapper.createObjectNode();
        modalities.put("vision", hasVision);
        modalities.put("audio", hasAudio);
        mapProps.set("modalities", modalities);

        mapProps.put("webui", "true");
        mapProps.put("build_info", "WebUiProxy - Spring Boot (Jackson 3)");

        try {
            return jsonMapper.writeValueAsString(mapProps);
        } catch (RuntimeException e) {
            LOG.error("Error building props JSON", e);
            return "{}";
        }
    }

    /**
     * Forwards a request to the LLM server and PIPES the response stream directly.
     * Used for Streaming (SSE).
     */
    private void forwardRequestToLLM(JsonNode requestNode, String cookie, String requestPath,
                                     HttpServletResponse clientResponse, boolean streaming) throws IOException {
        // Deep-Copy von requestNode, um alle Parameter zu übernehmen.
        ObjectNode llmRequest = (ObjectNode) requestNode.deepCopy();

        // Set model name (overwrite if present)
        llmRequest.put("model", modelName);

        // Set default max_tokens if not present
        if (!llmRequest.has("max_tokens") && maxTokens != null) {
            llmRequest.put("max_tokens", maxTokens);
        }

        // Add stream_options to include usage info (token statistics) in the streaming response
        ObjectNode streamOptions = jsonMapper.createObjectNode();
        streamOptions.put("include_usage", true);
        llmRequest.set("stream_options", streamOptions);

        String requestOut = jsonMapper.writeValueAsString(llmRequest);
        LOG.debug("LLM Request: {}", requestOut);

        // Build target URL: modelUrl + requestPath
        String targetUrl = modelUrl;
        if (!targetUrl.endsWith("/")) {
            targetUrl = targetUrl + "/";
        }
        if (requestPath.startsWith("/")) {
            targetUrl = targetUrl + requestPath.substring(1);
        } else {
            targetUrl = targetUrl + requestPath;
        }

        // Send request to LLM
        URL url;
        try {
            url = URI.create(targetUrl).toURL();
        } catch (MalformedURLException e) {
            throw new RuntimeException("Invalid model-URL (%s)".formatted(targetUrl), e);
        }

        HttpURLConnection connection = null;
        // Collect SSE data lines and first-content-token timestamp
        List<String> sseDataLines = new ArrayList<>();
        final AtomicReference<LocalDateTime> firstContentTime = new AtomicReference<>(null);
        final LocalDateTime tsStart = LocalDateTime.now();
        try {
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            // Accept both JSON and SSE depending on backend capability
            connection.setRequestProperty("Accept", "application/json, text/event-stream");
            connection.setDoOutput(true);
            // Important for streaming: disable expectation of 100-continue which can delay
            connection.setRequestProperty("Expect", "");

            if (cookie != null) {
                connection.setRequestProperty("Cookie", cookie);
            }

            try (OutputStream os = connection.getOutputStream();
                 OutputStreamWriter osw = new OutputStreamWriter(os, StandardCharsets.UTF_8)) {
                osw.write(requestOut);
                osw.flush();
            }

            int responseCode = connection.getResponseCode();

            // Forward Status Code
            clientResponse.setStatus(responseCode);

            if (responseCode >= HttpURLConnection.HTTP_BAD_REQUEST) {
                LOG.error("HTTP error accessing {}: {} - {}", url, responseCode,
                        connection.getResponseMessage());
                // Copy error stream to client
                try (InputStream errorStream = connection.getErrorStream()) {
                    if (errorStream != null) {
                        copyStream(errorStream, clientResponse.getOutputStream(), null, null);
                    }
                }
                return;
            }

            // --- HEADER FORWARDING ---
            // Copy Content-Type and other relevant headers from LLM to Client
            String contentType = connection.getContentType();
            if (contentType != null) {
                clientResponse.setContentType(contentType);
            }

            // Copy Cache-Control if present (important for SSE)
            String cacheControl = connection.getHeaderField("Cache-Control");
            if (cacheControl != null) {
                clientResponse.setHeader("Cache-Control", cacheControl);
            }

            // Copy Connection header
            String connectionHeader = connection.getHeaderField("Connection");
            if (connectionHeader != null) {
                clientResponse.setHeader("Connection", connectionHeader);
            }

            // --- STREAM PUMPING ---
            // Read the response and write immediately to client
            try (InputStream is = connection.getInputStream();
                 OutputStream os = clientResponse.getOutputStream()) {
                copyStream(is, os, sseDataLines, firstContentTime);
                // Ensure flush happens at the end
                os.flush();
            }

            // --- USAGE STATISTICS ---
            recordUsageStatistics(tsStart, firstContentTime.get(), sseDataLines);

        } catch (IOException e) {
            LOG.error("IO-error while calling LLM ({}): {}", url, e.getMessage(), e);
            // If client response is not committed yet, try to send error
            if (!clientResponse.isCommitted()) {
                clientResponse.setStatus(502);
                clientResponse.getWriter().write("Error proxying request to LLM: " + e.getMessage());
            }
            throw e;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Parses the collected SSE data lines for usage/timing information,
     * computes statistics, logs them, and optionally writes to a JSONL file.
     *
     * @param tsStart         timestamp when the request started
     * @param firstContentTime timestamp of the first content token (streaming), or null if not available
     * @param sseDataLines    collected SSE data line JSON strings
     */
    private void recordUsageStatistics(LocalDateTime tsStart, LocalDateTime firstContentTime,
                                       List<String> sseDataLines) {
        if (sseDataLines.isEmpty()) {
            LOG.info("No SSE data lines collected for usage statistics.");
            return;
        }

        // Find the last data line that contains usage information
        String lastUsageJson = null;
        for (int i = sseDataLines.size() - 1; i >= 0; i--) {
            String line = sseDataLines.get(i);
            if (line.contains("\"usage\"")) {
                lastUsageJson = line;
                break;
            }
        }

        if (lastUsageJson == null) {
            // No usage data from server, log only start time and model
            LOG.info("No usage statistics from server. tsStart={}, model={}", tsStart, modelName);
            return;
        }

        try {
            JsonNode dataNode = jsonMapper.readTree(lastUsageJson);
            JsonNode usageNode = dataNode.get("usage");
            JsonNode timingsNode = dataNode.get("timings");

            String model = dataNode.has("model") ? dataNode.get("model").asText() : modelName;

            long promptTokens = usageNode.has("prompt_tokens") ? usageNode.get("prompt_tokens").asLong() : 0;
            long completionTokens = usageNode.has("completion_tokens") ? usageNode.get("completion_tokens").asLong() : 0;
            long totalTokens = usageNode.has("total_tokens") ? usageNode.get("total_tokens").asLong() : 0;
            long cachedTokens = 0;
            if (usageNode.has("prompt_tokens_details") && usageNode.get("prompt_tokens_details").has("cached_tokens")) {
                cachedTokens = usageNode.get("prompt_tokens_details").get("cached_tokens").asLong();
            }

            long millisPP;
            long millisTG;
            float ppTPS;
            float tgTPS;

            if (timingsNode != null) {
                // llama.cpp provides detailed timings in the response.
                double promptMs = timingsNode.has("prompt_ms") ? timingsNode.get("prompt_ms").asDouble() : 0;
                double predictedMs = timingsNode.has("predicted_ms") ? timingsNode.get("predicted_ms").asDouble() : 0;
                millisPP = Math.round(promptMs);
                millisTG = Math.round(predictedMs);

                double serverPpTPS = timingsNode.has("prompt_per_second") ? timingsNode.get("prompt_per_second").asDouble() : 0;
                double serverTgTPS = timingsNode.has("predicted_per_second") ? timingsNode.get("predicted_per_second").asDouble() : 0;

                // Compute own values from the raw data for comparison
                float computedPpTPS = (millisPP > 0 && promptTokens > 0) ? (promptTokens * 1000f / millisPP) : 0;
                float computedTgTPS = (millisTG > 0 && completionTokens > 0) ? (completionTokens * 1000f / millisTG) : 0;

                ppTPS = (float) serverPpTPS;
                tgTPS = (float) serverTgTPS;

                LOG.info("Usage stats (llama.cpp): promptTokens={}, completionTokens={}, totalTokens={}, cachedTokens={}, "
                                + "millisPP={}, millisTG={}, ppTPS={} (computed: {}), tgTPS={} (computed: {})",
                        promptTokens, completionTokens, totalTokens, cachedTokens,
                        millisPP, millisTG, ppTPS, computedPpTPS, tgTPS, computedTgTPS);

            } else if (firstContentTime != null) {
                // Streaming with vLLM (no timings, but we have firstContentTime from the stream).
                LocalDateTime tsNow = LocalDateTime.now();
                millisPP = Duration.between(tsStart, firstContentTime).toMillis();
                millisTG = Duration.between(firstContentTime, tsNow).toMillis();

                float computedPpTPS = (millisPP > 0 && promptTokens > 0) ? (promptTokens * 1000f / millisPP) : 0;
                float computedTgTPS = (millisTG > 0 && completionTokens > 0) ? (completionTokens * 1000f / millisTG) : 0;
                ppTPS = computedPpTPS;
                tgTPS = computedTgTPS;

                LOG.info("Usage stats (vLLM streaming): promptTokens={}, completionTokens={}, totalTokens={}, cachedTokens={}, "
                                + "millisPP={}, millisTG={}, ppTPS={}, tgTPS={}",
                        promptTokens, completionTokens, totalTokens, cachedTokens,
                        millisPP, millisTG, ppTPS, tgTPS);

            } else {
                // Non-streaming (buffered): only totalMillis known.
                // Estimate PP/TG split using the heuristic ppTPS = 5 * tgTPS.
                long totalMillis = Duration.between(tsStart, LocalDateTime.now()).toMillis();
                // ppTPS = 5 * tgTPS  =>  promptTokens/millisPP = 5 * completionTokens/millisTG
                // millisPP + millisTG = totalMillis
                // => promptTokens / (5*tgTPS) * 1000 + completionTokens / tgTPS * 1000 = totalMillis
                // => 1000/tgTPS * (promptTokens/5 + completionTokens) = totalMillis
                // => tgTPS = 1000 * (promptTokens/5 + completionTokens) / totalMillis
                if (totalMillis > 0 && completionTokens > 0) {
                    float factor = (promptTokens / 5f + completionTokens);
                    tgTPS = 1000f * factor / totalMillis;
                    ppTPS = 5f * tgTPS;
                } else {
                    ppTPS = 0;
                    tgTPS = 0;
                }
                millisPP = (ppTPS > 0 && promptTokens > 0) ? Math.round(promptTokens * 1000f / ppTPS) : totalMillis;
                millisTG = (tgTPS > 0 && completionTokens > 0) ? Math.round(completionTokens * 1000f / tgTPS) : totalMillis;

                LOG.info("Usage stats (non-streaming): promptTokens={}, completionTokens={}, totalTokens={}, cachedTokens={}, "
                                + "totalMillis={}, millisPP={}, millisTG={}, ppTPS={}, tgTPS={}",
                        promptTokens, completionTokens, totalTokens, cachedTokens,
                        totalMillis, millisPP, millisTG, ppTPS, tgTPS);
            }

            LlmUsage usage = new LlmUsage(tsStart, millisPP, millisTG, model,
                    promptTokens, completionTokens, totalTokens, cachedTokens, ppTPS, tgTPS);
            usages.add(usage);

            // Write to JSONL file if configured
            writeStatsJsonl(usage, usageNode);

        } catch (RuntimeException e) {
            LOG.warn("Failed to parse usage statistics from SSE data: {}", e.getMessage());
        }
    }

    /**
     * Writes a usage record to the JSONL statistics file if the property {@value #PROP_LOG_FILE} is set.
     * Creates the file if it does not exist yet.
     *
     * @param usage     the usage record to write
     * @param usageNode the raw usage JSON node from the server response
     */
    private void writeStatsJsonl(LlmUsage usage, JsonNode usageNode) {
        if (statsFilePath == null || statsFilePath.isBlank()) {
            return;
        }
        try {
            Path statsFile = Paths.get(statsFilePath);

            ObjectNode record = jsonMapper.createObjectNode();
            record.put("type", "llm-request");
            record.put("tsStart", usage.tsStart().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            record.put("millisPP", usage.millisPP());
            record.put("millisTG", usage.millisTG());
            record.put("model", usage.model());
            record.put("promptTokens", usage.promptTokens());
            record.put("completionTokens", usage.completionTokens());
            record.put("totalTokens", usage.totalTokens());
            record.put("cachedTokens", usage.cachedTokens());
            record.put("ppTPS", usage.ppTPS());
            record.put("tgTPS", usage.tgTPS());
            record.set("usage", usageNode);

            String jsonLine = jsonMapper.writeValueAsString(record) + "\n";

            // Ensure parent directories exist
            Path parent = statsFile.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            Files.writeString(statsFile, jsonLine, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);

            LOG.debug("Appended usage stats to JSONL file: {}", statsFile);
        } catch (IOException e) {
            LOG.warn("Failed to write usage stats to JSONL file '{}': {}", statsFilePath, e.getMessage());
        }
    }

    /**
     * Shutdown hook: logs overall usage statistics.
     * Called by Spring when the application context is closed.
     */
    @PreDestroy
    public void onShutdown() {
        if (usages.isEmpty()) {
            LOG.info("Shutdown: no LLM usage statistics collected.");
            return;
        }
        int requestCount = usages.size();
        long totalPromptTokens = usages.stream().mapToLong(LlmUsage::promptTokens).sum();
        long totalCompletionTokens = usages.stream().mapToLong(LlmUsage::completionTokens).sum();
        long totalTokens = usages.stream().mapToLong(LlmUsage::totalTokens).sum();
        LOG.info("Shutdown: LLM usage statistics - #Requests={}, #TokenIn={}, #TokenOut={}, #TotalTokens={}",
                requestCount, totalPromptTokens, totalCompletionTokens, totalTokens);
    }

    /**
     * Legacy method for non-streaming requests.
     * Buffers the response to allow post-processing (SSE wrapping).
     */
    private String forwardRequestToLLMBuffered(JsonNode requestNode, String cookie, String requestPath) throws IOException {
        ObjectNode llmRequest = (ObjectNode) requestNode.deepCopy();
        llmRequest.put("model", modelName);

        // Force non-streaming for buffered mode
        llmRequest.put("stream", false);

        if (!llmRequest.has("max_tokens") && maxTokens != null) {
            llmRequest.put("max_tokens", maxTokens);
        }

        String requestOut = jsonMapper.writeValueAsString(llmRequest);

        String targetUrl = modelUrl;
        if (!targetUrl.endsWith("/")) targetUrl += "/";
        targetUrl += requestPath.startsWith("/") ? requestPath.substring(1) : requestPath;

        URL url = URI.create(targetUrl).toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        connection.setDoOutput(true);

        if (cookie != null) {
            connection.setRequestProperty("Cookie", cookie);
        }

        final LocalDateTime tsStart = LocalDateTime.now();
        try (OutputStream os = connection.getOutputStream();
             OutputStreamWriter osw = new OutputStreamWriter(os, StandardCharsets.UTF_8)) {
            osw.write(requestOut);
        }

        int responseCode = connection.getResponseCode();
        if (responseCode >= HttpURLConnection.HTTP_BAD_REQUEST) {
            LOG.error("HTTP error accessing {}: {}", url, responseCode);
            return null;
        }

        String responseBody;
        try (InputStream is = connection.getInputStream()) {
            responseBody = readResponse(is);
        } finally {
            connection.disconnect();
        }

        // Try to parse usage from the non-streaming JSON response
        try {
            JsonNode responseNode = jsonMapper.readTree(responseBody);
            if (responseNode.has("usage")) {
                List<String> dataLines = new ArrayList<>();
                dataLines.add(jsonMapper.writeValueAsString(responseNode));
                recordUsageStatistics(tsStart, null, dataLines);
            }
        } catch (RuntimeException e) {
            LOG.debug("Could not parse usage from non-streaming response: {}", e.getMessage());
        }

        return responseBody;
    }

    /**
     * Reads the content of an InputStream (UTF-8)
     */
    private String readResponse(InputStream inputStream) throws IOException {
        try (InputStreamReader isr = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder(500);
            char[] cBuf = new char[4096];
            while (true) {
                int len = isr.read(cBuf);
                if (len == -1) {
                    break;
                }
                sb.append(cBuf, 0, len);
            }
            return sb.toString();
        }
    }

    /**
     * Helper to copy InputStream to OutputStream with buffering.
     * Ensures data is flushed periodically for streaming.
     * Also collects SSE data lines for usage statistics extraction
     * and records the timestamp of the first content token.
     *
     * @param in               source input stream
     * @param out              target output stream
     * @param sseDataLines     collector for SSE data line JSON strings (may be null)
     * @param firstContentTime atomic reference to store the timestamp of the first content token (may be null)
     * @throws IOException if an I/O error occurs
     */
    private void copyStream(InputStream in, OutputStream out, List<String> sseDataLines,
                            AtomicReference<LocalDateTime> firstContentTime) throws IOException {
        // Buffer for a partial SSE data line that was split across chunk boundaries.
        StringBuilder pendingData = new StringBuilder();
        byte[] buffer = new byte[4096];
        int bytesRead;
        while ((bytesRead = in.read(buffer)) != -1) {
            String chunk = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
            out.write(buffer, 0, bytesRead);
            out.flush(); // Critical for SSE: push chunks immediately

            // Collect SSE data lines for usage extraction
            // and detect the first content token for PP/TG separation
            if (sseDataLines != null || firstContentTime != null) {
                // Prepend any pending data from a previous partial line
                String parseText;
                if (!pendingData.isEmpty()) {
                    parseText = pendingData.toString() + chunk;
                    pendingData.setLength(0);
                } else {
                    parseText = chunk;
                }

                int idx = 0;
                while (idx < parseText.length()) {
                    int dataStart = parseText.indexOf("data: ", idx);
                    if (dataStart < 0) {
                        break;
                    }
                    int lineEnd = parseText.indexOf('\n', dataStart);
                    if (lineEnd < 0) {
                        // Partial line: store from "data:" onward and continue with next chunk
                        pendingData.append(parseText.substring(dataStart));
                        break;
                    }
                    String dataLine = parseText.substring(dataStart + 6, lineEnd).trim();
                    if (!dataLine.isEmpty() && !"[DONE]".equals(dataLine)) {
                        if (sseDataLines != null) {
                            sseDataLines.add(dataLine);
                        }
                        // Check for first content token (streaming: choices[0].delta.content != null)
                        if (firstContentTime != null && firstContentTime.get() == null
                                && dataLine.contains("\"content\"")) {
                            firstContentTime.compareAndExchange(null, LocalDateTime.now());
                        }
                    }
                    idx = lineEnd + 1;
                }
            }
        }
    }

    /**
     * Reads all bytes from an InputStream into a byte array.
     * Uses buffered reading with 4KB buffer.
     *
     * @param inputStream the input stream to read from
     * @return byte array containing all data from the stream
     * @throws IOException if an I/O error occurs
     */
    private byte[] readAllBytes(InputStream inputStream) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                baos.write(buffer, 0, read);
            }
            return baos.toByteArray();
        }
    }
}