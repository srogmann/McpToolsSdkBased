package org.rogmann.mcp2sdk.chat;

import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Value;
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
import java.time.LocalDateTime;
import java.util.*;
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

    /** Maximum tokens (default 16000) */
    @Value("${" + PROP_MAX_TOKENS + ":16000}")
    private int maxTokens;

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
        if (!llmRequest.has("max_tokens") && maxTokens > 0) {
            llmRequest.put("max_tokens", maxTokens);
        }

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
                        copyStream(errorStream, clientResponse.getOutputStream());
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
                copyStream(is, os);
                // Ensure flush happens at the end
                os.flush();
            }

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
     * Legacy method for non-streaming requests.
     * Buffers the response to allow post-processing (SSE wrapping).
     */
    private String forwardRequestToLLMBuffered(JsonNode requestNode, String cookie, String requestPath) throws IOException {
        ObjectNode llmRequest = (ObjectNode) requestNode.deepCopy();
        llmRequest.put("model", modelName);

        // Force non-streaming for buffered mode
        llmRequest.put("stream", false);

        if (!llmRequest.has("max_tokens") && maxTokens > 0) {
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

        try (OutputStream os = connection.getOutputStream();
             OutputStreamWriter osw = new OutputStreamWriter(os, StandardCharsets.UTF_8)) {
            osw.write(requestOut);
        }

        int responseCode = connection.getResponseCode();
        if (responseCode >= HttpURLConnection.HTTP_BAD_REQUEST) {
            LOG.error("HTTP error accessing {}: {}", url, responseCode);
            return null;
        }

        try (InputStream is = connection.getInputStream()) {
            return readResponse(is);
        } finally {
            connection.disconnect();
        }
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
     */
    private void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[4096];
        int bytesRead;
        while ((bytesRead = in.read(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);
            out.flush(); // Critical for SSE: push chunks immediately
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
