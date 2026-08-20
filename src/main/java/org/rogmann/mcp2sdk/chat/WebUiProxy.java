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
import org.springframework.http.HttpStatus;
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
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;

/**
 * Controller for the great <a href="https://github.com/ggml-org/llama.cpp/tree/master/tools/server/">llama.cpp Web UI</a>.
 * Provides a web interface and forwards LLM requests to an OpenAI-compatible endpoint.
 *
 * <p>Endpoint structure (all relative to /chat):
 * - /chat/*              : Static web resources (HTML, CSS, JS, images)
 * - /chat/props          : Model properties (llama.cpp compatible)
 * - /chat/tools          : Server-specific tools (currently disabled)
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
    private static final String PROP_REASONING = "webui.model.reasoning";
    private static final String PROP_BACKEND = "webui.model.backend";
    private static final String PROP_FETCH_METRICS = "webui.fetchMetrics";

    /** Backend vLLM */
    private static final String BACKEND_VLLM = "vllm";

    /**
     * Plausibility ceiling for the token rates synthesized on the vLLM path. Rates above this
     * value (e.g. measured over a near-zero millisecond span) are treated as timing artifacts
     * and the per-chunk timings enrichment is skipped for that chunk.
     */
    private static final double MAX_PLAUSIBLE_TOKENS_PER_SECOND = 20_000.0;

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
     * @param ppUncachedTPS   prompt processing tokens per second for the tokens NOT served
     *                        from the KV cache (0 if no uncached tokens or no timing data)
     * @param ppTPS           prompt processing tokens per second (all prompt tokens)
     * @param tgTPS           token generation tokens per second
     */
    public record LlmUsage(LocalDateTime tsStart, long millisPP, long millisTG, String model,
                           long promptTokens, long completionTokens, long totalTokens,
                           long cachedTokens, float ppUncachedTPS, float ppTPS, float tgTPS) {}

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

    /** Reasoning mode: on, off, auto (auto = derive from the chat template) */
    @Value("${" + PROP_REASONING + ":auto}")
    private String reasoningMode;

    /** Backend type: llamacpp (default) or vllm (deepseek-compatible thinking/reasoning_effort) */
    @Value("${" + PROP_BACKEND + ":llamacpp}")
    private String backend;

    /** Whether to fetch per-request cache metrics from the upstream vLLM /metrics endpoint */
    @Value("${" + PROP_FETCH_METRICS + ":false}")
    private boolean fetchMetrics;

    /** Vision capabilities */
    @Value("${" + PROP_HAS_VISION + ":false}")
    private boolean hasVision;

    /** Audio capabilities */
    @Value("${" + PROP_HAS_AUDIO + ":false}")
    private boolean hasAudio;

    /** Maximum tokens (only set if explicitly configured) */
    @Value("${" + PROP_MAX_TOKENS + ":}")
    private Integer maxTokens;

    /** Cached chat template for the configured model (loaded once from the classpath) */
    private String chatTemplateCache;

    /** Whether the chat template lookup already ran (distinguishes "not found" from "not loaded") */
    private boolean chatTemplateLoaded = false;

    /** Minimal chat template that signals thinking/reasoning support (used when reasoning=on and no model template is found) */
    private static final String DEFAULT_THINKING_TEMPLATE =
            "{%- if enable_thinking -%}{{- ' thinking' -}}{{- ' response' -}}{%- endif -%}{{ content }}";

    /** Minimal chat template without thinking support (used to force reasoning off) */
    private static final String DEFAULT_NON_THINKING_TEMPLATE = "{{ content }}";

    /** Marker substrings that indicate a chat template supports thinking/reasoning */
    private static final String[] THINKING_MARKERS = {
            "enable_thinking", "reasoning_effort", "thinking_budget",
            " thinking", " response", "<|think|>"
    };

    /** Counter of served static resources (first N logged on INFO, rest on DEBUG) */
    private final AtomicLong staticResourceCount = new AtomicLong();

    /** JsonMapper for JSON processing (Jackson 3) */
    private final JsonMapper jsonMapper;

    /** Map from model-name to reasoning-mode */
    private final ConcurrentMap<String, String> mapReasoning = new ConcurrentHashMap<>();

    /** Max. length of a string value that is logged unshortened. */
    private static final int LOG_MAX_STRING_LENGTH = 100;
    /** Number of leading characters kept for a shortened string value. */
    private static final int LOG_STRING_PREFIX = 80;
    /** Number of trailing characters kept for a shortened string value. */
    private static final int LOG_STRING_SUFFIX = 20;
    /** Max. length of the whole request body when it cannot be parsed as JSON. */
    private static final int LOG_FALLBACK_MAX_LENGTH = 600;
    /** Number of leading characters kept for a fallback-shortened request body. */
    private static final int LOG_FALLBACK_PREFIX = 500;
    /** Number of trailing characters kept for a fallback-shortened request body. */
    private static final int LOG_FALLBACK_SUFFIX = 100;

    /** Overall max. length of the logged request body, even if it contains many (individually
     * short) values such as tool calls. Applied after per-value shortening. */
    private static final int LOG_BODY_MAX_LENGTH = 2000;
    /** Number of leading characters kept in the overall request-body log line. */
    private static final int LOG_BODY_PREFIX = 1800;
    /** Number of trailing characters kept in the overall request-body log line. */
    private static final int LOG_BODY_SUFFIX = 200;

    /** Placeholder {@code arguments} used to replace an invalid assistant tool-call in the
     * forwarded request. The client-side history keeps its local copy; only the outgoing request
     * is repaired so backends that validate tool-call arguments (e.g. vLLM) accept it. */
    private static final String WRONG_INPUT_PLACEHOLDER = "{\"error\":\"[wrong input removed by WebUI.\"}";

    /** Max. length of invalid tool-call arguments written to the error log by {@link #sanitizeToolCalls}. */
    private static final int SANITIZE_LOG_ARGUMENTS_MAX = 1000;

    /**
     * Cache-Control value for responses that must not be stored anywhere
     * (dynamic/sensitive content such as LLM answers): no-store.
     */
    private static final String CACHE_CONTROL_NO_STORE = "no-store";

    /**
     * Cache-Control value applied to static web resources (max-age in seconds).
     * Central constant so the static caching strategy can be tuned in one place.
     */
    private static final String CACHE_CONTROL_STATIC_MAX_AGE = "max-age=3600";

    /**
     * Applies cache-disabling headers to an {@link HttpHeaders} instance.
     *
     * <p>Central entry point for the "no caching" strategy of dynamic and sensitive
     * responses (model properties, tool list, model list, LLM completions). Any
     * caching refinement for these endpoints should be applied here in this single
     * place instead of per endpoint.</p>
     *
     * @param headers headers to modify
     */
    private static void applyNoStoreCacheHeaders(HttpHeaders headers) {
        headers.set(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_NO_STORE);
        headers.set(HttpHeaders.PRAGMA, "no-cache"); // HTTP/1.0 fallback
        headers.set(HttpHeaders.EXPIRES, "0");       // mark as already expired
    }

    /**
     * Applies cache-disabling headers to a servlet response.
     *
     * <p>See {@link #applyNoStoreCacheHeaders(HttpHeaders)}. Used for the streaming and
     * non-streaming LLM completions which are written directly to the servlet response
     * instead of being built via {@link ResponseEntity}.</p>
     *
     * @param response servlet response to modify
     */
    private static void applyNoStoreCacheHeaders(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_NO_STORE);
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        response.setHeader(HttpHeaders.EXPIRES, "0");
    }

    /**
     * Applies explicit caching headers for static web resources.
     *
     * <p>Static assets may be cached with an explicit max-age, instead of leaving the
     * cache behaviour up to heuristics. The value is centralized in
     * {@link #CACHE_CONTROL_STATIC_MAX_AGE}.</p>
     *
     * @param headers headers to modify
     */
    private static void applyStaticCacheHeaders(HttpHeaders headers) {
        headers.set(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_STATIC_MAX_AGE);
    }

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

        if (staticResourceCount.getAndIncrement() < 5) {
            LOG.info("{} GET static resource: {}", LocalDateTime.now(), path);
        } else {
            LOG.debug("{} GET static resource: {}", LocalDateTime.now(), path);
        }

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
            applyStaticCacheHeaders(headers);

            return ResponseEntity.ok().headers(headers).body(content);
        } catch (IOException e) {
            LOG.error("Error reading resource: {}", path, e);
            return ResponseEntity.status(500).body("Error reading resource".getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Redirects a request to the chat root {@code /chat} without a trailing slash to
     * {@code /chat/}.
     *
     * <p>The static frontend uses relative links (e.g. {@code ./_app/...}, {@code favicon.ico})
     * which only resolve correctly when the browser URL ends with a trailing slash. Accessing
     * {@code /chat} (without the trailing slash) would resolve those relative links against the
     * parent path instead, breaking the page. Redirecting to {@code /chat/} keeps them working.</p>
     *
     * @return a 301 (moved permanently) redirect response
     */
    @GetMapping(value = "")
    public ResponseEntity<Void> redirectChatToChatSlash() {
        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create("/chat/"));
        headers.set(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_NO_STORE);
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY).headers(headers).build();
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
        applyNoStoreCacheHeaders(headers);

        return ResponseEntity.ok().headers(headers).body(response);
    }

    /**
     * Delivers server-specific tools.
     * Accessible at: /chat/tools
     * Currently disabled; may be extended to expose server-specific tools in the future.
     */
    @GetMapping(value = "/tools", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getTools() {
        LOG.info("{} GET /chat/tools", LocalDateTime.now());

        ObjectNode root = jsonMapper.createObjectNode();
        ObjectNode error = jsonMapper.createObjectNode();
        error.put("message", "this feature is disabled");
        error.put("type", "feature_disabled");
        root.set("error", error);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        applyNoStoreCacheHeaders(headers);

        try {
            return ResponseEntity.ok().headers(headers).body(jsonMapper.writeValueAsString(root));
        } catch (RuntimeException e) {
            LOG.error("Error serializing tools response", e);
            return ResponseEntity.status(500).body("{\"error\": \"Internal server error\"}");
        }
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
        applyNoStoreCacheHeaders(headers);

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
        applyNoStoreCacheHeaders(headers);

        try {
            return ResponseEntity.ok().headers(headers).body(jsonMapper.writeValueAsString(root));
        } catch (RuntimeException e) {
            LOG.error("Error serializing models", e);
            return ResponseEntity.status(500).body("{\"error\": \"Internal server error\"}");
        }
    }

    /**
     * Prepares the request body for logging so that long log lines are avoided.
     * <p>String values longer than {@value #LOG_MAX_STRING_LENGTH} characters are shortened
     * to the first {@value #LOG_STRING_PREFIX} and the last {@value #LOG_STRING_SUFFIX}
     * characters, separated by {@code [...]}. This keeps the JSON structure readable while
     * trimming long contents (e.g. pasted files, tool descriptions or script sources).</p>
     * <p>If the body cannot be parsed as JSON it is truncated to the first
     * {@value #LOG_FALLBACK_PREFIX} and the last {@value #LOG_FALLBACK_SUFFIX} characters.</p>
     * <p>Finally the result is capped to an overall {@value #LOG_BODY_MAX_LENGTH} characters
     * ({@value #LOG_BODY_PREFIX} leading + {@value #LOG_BODY_SUFFIX} trailing) so bodies with
     * many short values (e.g. many tool calls) never produce oversized log lines.</p>
     *
     * @param requestBody raw request body
     * @return shortened representation suitable for logging
     */
    private String shortenRequestBody(String requestBody) {
        String shortened;
        try {
            JsonNode node = jsonMapper.readTree(requestBody);
            shortened = jsonMapper.writeValueAsString(shortenLongStrings(node));
        } catch (RuntimeException e) {
            LOG.debug("Cannot parse request body as JSON, fall back to plain truncation: {}",
                    e.getMessage());
            shortened = shortenText(requestBody, LOG_FALLBACK_MAX_LENGTH,
                    LOG_FALLBACK_PREFIX, LOG_FALLBACK_SUFFIX);
        }
        // Per-value shortening keeps the JSON readable, but a body with many short values
        // (e.g. many tool calls) can still grow large - cap the overall log line.
        return truncateLogText(shortened, LOG_BODY_MAX_LENGTH, LOG_BODY_PREFIX, LOG_BODY_SUFFIX);
    }

    /**
     * Caps a text to an overall maximum length for logging: the first {@code prefix} and the
     * last {@code suffix} characters are kept, separated by a marker naming how many leading
     * characters and the total length, e.g. {@code "[... 1800 of 314159 ...]"}. Applied when
     * the text still exceeds {@code maxLength} after per-value shortening.
     *
     * @param text      text to truncate
     * @param maxLength overall length above which the text is truncated
     * @param prefix    number of leading characters to keep
     * @param suffix    number of trailing characters to keep
     * @return the original text or a truncated variant
     */
    private static String truncateLogText(String text, int maxLength, int prefix, int suffix) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        String marker = "[... " + prefix + " of " + text.length() + " ...]";
        // Keep the marker within the requested budget by trimming the leading part if needed.
        int safePrefix = Math.max(0, Math.min(prefix, maxLength - marker.length() - suffix));
        return text.substring(0, safePrefix) + marker
                + text.substring(text.length() - suffix);
    }

    /**
     * Recursively shortens long string values in a JSON tree.
     *
     * @param node node to process
     * @return a new node with the long string values shortened
     */
    private JsonNode shortenLongStrings(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            ObjectNode src = (ObjectNode) node;
            ObjectNode dst = jsonMapper.createObjectNode();
            for (Map.Entry<String, JsonNode> entry : src.properties()) {
                dst.set(entry.getKey(), shortenLongStrings(entry.getValue()));
            }
            return dst;
        } else if (node.isArray()) {
            ArrayNode dst = jsonMapper.createArrayNode();
            for (JsonNode child : node) {
                dst.add(shortenLongStrings(child));
            }
            return dst;
        } else if (node.isString()) {
            String text = node.asString();
            if (text.length() > LOG_MAX_STRING_LENGTH) {
                String shortened = text.substring(0, LOG_STRING_PREFIX)
                        + "[...]"
                        + text.substring(text.length() - LOG_STRING_SUFFIX);
                return jsonMapper.stringNode(shortened);
            }
        }
        return node;
    }

    /**
     * Shortens a text to the first {@code prefix} and the last {@code suffix} characters
     * if its length exceeds {@code maxLength}.
     *
     * @param text      text to shorten
     * @param maxLength length above which the text is shortened
     * @param prefix    number of leading characters to keep
     * @param suffix    number of trailing characters to keep
     * @return the original text or a shortened variant
     */
    private static String shortenText(String text, int maxLength, int prefix, int suffix) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, prefix) + "[...]" + text.substring(text.length() - suffix);
    }

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
    private int sanitizeToolCalls(ObjectNode llmRequest) {
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
    private String firstJsonError(String json) {
        if (json == null || json.isBlank()) {
            return "empty or missing arguments";
        }
        try {
            jsonMapper.readTree(json);
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
    private String escapeJsonString(String text) {
        try {
            String literal = jsonMapper.writeValueAsString(text);
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
    private String buildRefusedToolResult(String cause) {
        ObjectNode node = jsonMapper.createObjectNode();
        node.put("error", "Invalid input, refused tool call: Cause: " + cause);
        return jsonMapper.writeValueAsString(node);
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
        LOG.debug("Request body: {}", shortenRequestBody(requestBody));

        // Never let any cache (browser, proxy, intermediary) store LLM request/response
        // data - applied to streaming, non-streaming and error responses alike.
        applyNoStoreCacheHeaders(response);

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
                // Set SSE headers immediately (Cache-Control no-store is already
                // applied above via applyNoStoreCacheHeaders(response)).
                response.setContentType("text/event-stream");
                response.setCharacterEncoding("UTF-8");
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

        // Deliver a chat_template so the Web UI can detect reasoning/thinking support
        // and offer the "Reasoning" menu (see buildChatTemplate / web.model.reasoning).
        String chatTemplate = buildChatTemplate();
        if (chatTemplate != null) {
            mapProps.put("chat_template", chatTemplate);
        }

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
     * Builds the chat_template to deliver via /props based on the configured reasoning mode.
     * <ul>
     *   <li>auto (default): the stored model template decides - reasoning is shown iff the
     *       template contains thinking markers</li>
     *   <li>on: always signal reasoning support (falls back to a minimal thinking template
     *       if the stored model template has no thinking markers)</li>
     *   <li>off: never signal reasoning support (delivers a non-thinking template)</li>
     * </ul>
     *
     * @return the chat template to serve, or null to omit it (reasoning detection off)
     */
    private String buildChatTemplate() {
        String stored = loadChatTemplate();
        boolean storedThinks = templateSupportsThinking(stored);
        return switch (normalizeReasoningMode(reasoningMode)) {
            case "on" -> storedThinks ? stored : DEFAULT_THINKING_TEMPLATE;
            case "off" -> storedThinks ? DEFAULT_NON_THINKING_TEMPLATE : stored;
            default -> stored; // auto
        };
    }

    /**
     * Normalizes the reasoning mode property to on/off/auto (unknown values fall back to auto).
     */
    private String normalizeReasoningMode(String mode) {
        String m = (mode == null ? "auto" : mode).trim().toLowerCase(Locale.ROOT);
        if (!"on".equals(m) && !"off".equals(m)) {
            m = "auto";
        }
        return m;
    }

    /**
     * Loads the chat template for the configured model from the classpath directory
     * {@code chat-templates/<normalized-model-name>.jinja}. Loaded once and cached.
     *
     * @return the template content, or null if no template matches the model
     */
    private String loadChatTemplate() {
        if (chatTemplateLoaded) {
            return chatTemplateCache;
        }
        chatTemplateLoaded = true;
        String key = normalizeModelName(modelName);
        String resourcePath = "chat-templates/" + key + ".jinja";
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                LOG.info("No chat template for model '{}' (looked for {}), reasoning detection disabled",
                        modelName, resourcePath);
                return null;
            }
            chatTemplateCache = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            LOG.info("Loaded chat template for model '{}' from {}", modelName, resourcePath);
            return chatTemplateCache;
        } catch (IOException e) {
            LOG.warn("Failed to read chat template {}: {}", resourcePath, e.getMessage());
            return null;
        }
    }

    /**
     * Normalizes a model name to a resource key.
     */
    private String normalizeModelName(String name) {
        String n = (name == null ? "" : name);
        n = n.replaceAll("[^A-Za-z0-9-]+", "_").replaceAll("^_+|_+$", "");
        return n;
    }

    /**
     * Heuristic check whether a chat template signals thinking/reasoning support,
     * mirroring the detection used by the Web UI frontend.
     */
    private boolean templateSupportsThinking(String template) {
        if (template == null || template.isBlank()) {
            return false;
        }
        for (String marker : THINKING_MARKERS) {
            if (template.contains(marker)) {
                String markerNew = mapReasoning.putIfAbsent(modelName, marker);
                if (markerNew != null) {
                    LOG.info("Model {}: Reasoning-marker {}", modelName, marker);
                }
                return true;
            }
        }
        LOG.info("Model {}: found no reasoning marker in template of length {}", modelName, template.length());
        return false;
    }

    /**
     * If the client only sent a thinking/reasoning token budget without an explicit
     * {@code reasoning_effort}, derive the effort label from the budget and inject it as a
     * {@code chat_template_kwargs.reasoning_effort} entry.
     *
     * <p>The Web UI (chat.service.ts) maps the user's effort choice onto a token budget
     * ({@code thinking_budget_tokens}) and only ever passes {@code enable_thinking} as a
     * template kwarg; {@code reasoning_effort} itself is never forwarded. Templates that
     * branch on {@code reasoning_effort} (e.g. DeepSeek's {@code high}/{@code max} prompt
     * injections) therefore never see the chosen level. This method closes that gap by
     * reconstructing the effort label from the budget the UI did send.</p>
     *
     * <p>It is a no-op (and leaves the request untouched) when thinking is not enabled,
     * when the client already supplied an explicit {@code reasoning_effort}, or when the
     * budget does not correspond to a known effort level.</p>
     *
     * @param llmRequest the deep-copied request forwarded to the LLM (mutated in place)
     */
    private void injectReasoningEffortFromBudget(ObjectNode llmRequest) {
        JsonNode kwargs = llmRequest.get("chat_template_kwargs");
        if (kwargs == null || !kwargs.isObject()) {
            return;
        }
        ObjectNode kwargsObj = (ObjectNode) kwargs;

        // An explicit reasoning_effort provided by the client always wins.
        if (kwargsObj.has("reasoning_effort")) {
            return;
        }

        // Only act on requests that explicitly enable thinking.
        JsonNode enableThinking = kwargsObj.get("enable_thinking");
        if (enableThinking == null || !enableThinking.asBoolean(false)) {
            return;
        }

        int budget = reasoningBudget(llmRequest);
        String effort = reasoningEffortForBudget(budget);
        if (effort == null) {
            return;
        }

        kwargsObj.put("reasoning_effort", effort);
        LOG.debug("Injected reasoning_effort '{}' from budget {}", effort, budget);
    }

    /**
     * Applies backend-specific thinking/reasoning translations for a vLLM (DeepSeek-compatible)
     * backend. Translates the llama.cpp-style parameters the Web UI sends into the DeepSeek
     * OpenAI-format equivalents the vLLM backend understands:
     * <ul>
     *   <li>{@code {"thinking": {"type": "enabled"|"disabled"}}} instead of
     *       {@code chat_template_kwargs.enable_thinking}</li>
     *   <li>top-level {@code reasoning_effort} (low/high/max) instead of the token budget, because
     *       DeepSeek maps effort directly and ignores the raw budget</li>
     * </ul>
     *
     * <p>To avoid confusing a vLLM/DeepSeek backend with fields it does not understand, the
     * llama.cpp-specific request fields are removed once they have been translated.</p>
     *
     * <p>It is a no-op when thinking is not explicitly addressed by the request.</p>
     *
     * @param llmRequest the deep-copied request forwarded to the LLM (mutated in place)
     */
    private void applyVllmReasoning(ObjectNode llmRequest) {
        JsonNode kwargs = llmRequest.get("chat_template_kwargs");
        boolean enableThinking = false;
        if (kwargs != null && kwargs.isObject()) {
            JsonNode enableThinkingNode = kwargs.get("enable_thinking");
            if (enableThinkingNode != null && enableThinkingNode.asBoolean(false)) {
                enableThinking = true;
            }
        }

        boolean hasExplicitEffort = llmRequest.has("reasoning_effort");

        // Derive the DeepSeek effort label: an explicit reasoning_effort wins, otherwise map the
        // budget the UI sent. When thinking is enabled and no budget was sent, DeepSeek defaults
        // to "high" (matches the UI's max being unlimited, and high being the DeepSeek default).
        String effort;
        if (hasExplicitEffort) {
            effort = llmRequest.get("reasoning_effort").asString();
        } else {
            effort = enableThinking ? vllmEffortForBudget(reasoningBudget(llmRequest)) : null;
        }

        ObjectNode thinking = jsonMapper.createObjectNode();
        thinking.put("type", enableThinking ? "enabled" : "disabled");
        llmRequest.set("thinking", thinking);

        if (effort != null && !effort.isEmpty()) {
            llmRequest.put("reasoning_effort", effort);
        } else if (!hasExplicitEffort) {
            // No known level: still leave reasoning_effort absent so the backend default applies.
            llmRequest.remove("reasoning_effort");
        }

        // The Web UI never uses an explicit reasoning_effort, so in practice this branch sets it.

        // Strip llama.cpp-specific controls a vLLM/DeepSeek backend would not interpret.
        llmRequest.remove("chat_template_kwargs");
        llmRequest.remove("thinking_budget_tokens");
        llmRequest.remove("reasoning_budget_tokens");
        llmRequest.remove("reasoning_control");
    }

    /**
     * Reads the thinking/reasoning token budget the Web UI sent, normalized to -1 when absent.
     */
    private static int reasoningBudget(ObjectNode llmRequest) {
        return llmRequest.has("thinking_budget_tokens")
                ? llmRequest.get("thinking_budget_tokens").asInt(-1)
                : llmRequest.has("reasoning_budget_tokens")
                        ? llmRequest.get("reasoning_budget_tokens").asInt(-1)
                        : -1;
    }

    /**
     * Maps a thinking/reasoning token budget to the corresponding effort label for llama.cpp.
     * Matches {@code REASONING_EFFORT_TOKENS} in the Web UI (chat.service.ts):
     * low=512, medium=2048, high=8192, max=-1 (unlimited; the UI omits the budget field
     * for max, so the absent budget resolves to -1 here).
     *
     * @param budget the raw budget value (-1 when not present)
     * @return the effort label, or {@code null} if the budget is not a known level
     */
    private static String reasoningEffortForBudget(int budget) {
        return switch (budget) {
            case 512 -> "low";
            case 2048 -> "medium";
            case 8192 -> "high";
            case -1 -> "max";
            default -> null;
        };
    }

    /**
     * Maps a thinking/reasoning token budget to the DeepSeek OpenAI-format {@code reasoning_effort}
     * label for a vLLM backend. DeepSeek only distinguishes {@code low}, {@code high} and
     * {@code max}; there is no {@code medium}, so the UI's {@code medium} is raised to
     * {@code high}. The UI's {@code high} maps to {@code high} (per convention, only {@code max}
     * reaches {@code max}).
     *
     * @param budget the raw budget value (-1 when not present)
     * @return the DeepSeek effort label, or {@code null} if the budget is not a known level
     */
    private static String vllmEffortForBudget(int budget) {
        return switch (budget) {
            case 512 -> "low";
            case 2048, 8192 -> "high";
            case -1 -> "max";
            default -> null;
        };
    }

    /**
     * Reads the value of the {@code vllm:prompt_tokens_cached_total} counter from the upstream
     * vLLM {@code /metrics} endpoint. Returns -1 when the metric cannot be fetched or parsed
     * (e.g. endpoints not reachable, counter missing, non-vLLM backend).
     *
     * @return the current counter value, or -1 on failure
     */
    private long readVllmCachedTokensMetric() {
        String base = modelUrl;
        if (!base.endsWith("/")) {
            base += "/";
        }
        String metricsUrl = base + "metrics";
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(metricsUrl).toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            connection.setRequestProperty("Accept", "text/plain");
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                LOG.debug("Metrics endpoint answered HTTP {} for {}", code, metricsUrl);
                return -1;
            }
            String body;
            try (InputStream is = connection.getInputStream()) {
                body = readResponse(is);
            }
            for (String line : body.split("\n")) {
                line = line.trim();
                if (line.startsWith("vllm:prompt_tokens_cached_total")) {
                    int space = line.lastIndexOf(' ');
                    if (space >= 0) {
                        try {
                            return (long) Double.parseDouble(line.substring(space + 1));
                        } catch (NumberFormatException e) {
                            LOG.debug("Could not parse metrics value in: {}", line);
                        }
                    }
                }
            }
            LOG.debug("Metric vllm:prompt_tokens_cached_total not found in {}", metricsUrl);
            return -1;
        } catch (IOException e) {
            LOG.debug("Failed to read vLLM metrics from {}: {}", metricsUrl, e.getMessage());
            return -1;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
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

        // Backend-specific reasoning translation:
        // - llamacpp (default): reconstruct reasoning_effort into chat_template_kwargs so
        //   template branches like DeepSeek's high/max prompt injections actually trigger.
        // - vllm: translate enable_thinking + budget into {"thinking":{"type":...}} and a
        //   top-level reasoning_effort, dropping the llama.cpp-only fields.
        if (BACKEND_VLLM.equalsIgnoreCase(backend)) {
            applyVllmReasoning(llmRequest);
        } else {
            injectReasoningEffortFromBudget(llmRequest);
        }

        // Set default max_tokens if not present
        if (!llmRequest.has("max_tokens") && maxTokens != null) {
            llmRequest.put("max_tokens", maxTokens);
        }

        // Add stream_options to include usage info (token statistics) in the streaming response.
        // include_usage       : final usage object in the last SSE chunk (OpenAI-compatible, supported by llama.cpp and vLLM)
        // continuous_usage_stats: vLLM extension - reports usage in EVERY SSE chunk so per-chunk token statistics
        //                        are available even before the stream finishes; llama.cpp ignores this field.
        ObjectNode streamOptions = jsonMapper.createObjectNode();
        streamOptions.put("include_usage", true);
        if (BACKEND_VLLM.equalsIgnoreCase(backend)) {
            streamOptions.put("continuous_usage_stats", true);
        }
        llmRequest.set("stream_options", streamOptions);

        // Repair invalid tool-call arguments before forwarding so backends that validate
        // tool-call JSON (e.g. vLLM) accept the request despite a poisoned chat history.
        sanitizeToolCalls(llmRequest);

        String requestOut = jsonMapper.writeValueAsString(llmRequest);
        LOG.debug("LLM Request: {}", shortenRequestBody(requestOut));

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
        // Monotonic counterpart of tsStart, used as the reference for the per-chunk token rates
        // synthesized on the vLLM path (request-send time -> chunk arrival times).
        final long tsStartNano = System.nanoTime();
        // When enabled, sample the upstream vLLM cached-tokens counter before the request so the
        // delta after the stream reveals how many prompt tokens were served from the prefix cache.
        boolean sampleMetrics = BACKEND_VLLM.equalsIgnoreCase(backend) && fetchMetrics;
        long metricsBefore = sampleMetrics ? readVllmCachedTokensMetric() : -1;
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
                // For status >= 400 the response body is only exposed via getErrorStream()
                // (getInputStream() throws). Read it once, log it, and forward it to the client
                // as application/json. The SSE parser in copyStream would otherwise swallow the
                // body, because an error body is not formatted as "data:" SSE lines.
                String errorBody = "";
                try (InputStream errorStream = connection.getErrorStream()) {
                    if (errorStream != null) {
                        errorBody = readResponse(errorStream);
                    }
                }
                LOG.error("HTTP error accessing {}: {} - {}: {}", url, responseCode,
                        connection.getResponseMessage(),
                        errorBody.length() > 2000 ? errorBody.substring(0, 2000) + "..." : errorBody);
                // Forward the error body to the client (plain JSON, not SSE-wrapped).
                clientResponse.setContentType("application/json");
                clientResponse.setCharacterEncoding("UTF-8");
                try (Writer writer = clientResponse.getWriter()) {
                    writer.write(errorBody);
                    writer.flush();
                }
                return;
            }

            // --- HEADER FORWARDING ---
            // Copy Content-Type and other relevant headers from LLM to Client
            String contentType = connection.getContentType();
            if (contentType != null) {
                clientResponse.setContentType(contentType);
            }

            // Do NOT copy the backeshorten log-lines of requestsnd's Cache-Control header: the client-facing cache
            // policy (no-store, see applyNoStoreCacheHeaders) must not be overridden or
            // weakened by a header the LLM backend happens to send.

            // Copy Connection header
            String connectionHeader = connection.getHeaderField("Connection");
            if (connectionHeader != null) {
                clientResponse.setHeader("Connection", connectionHeader);
            }

            // --- STREAM PUMPING ---
            // Read the response and write immediately to client
            try (InputStream is = connection.getInputStream();
                 OutputStream os = clientResponse.getOutputStream()) {
                copyStream(is, os, sseDataLines, firstContentTime,
                        BACKEND_VLLM.equalsIgnoreCase(backend), tsStartNano);
                // Ensure flush happens at the end
                os.flush();
            }

            // --- USAGE STATISTICS ---
            recordUsageStatistics(tsStart, firstContentTime.get(), sseDataLines, sampleMetrics,
                    metricsBefore);

            // Diagnostic: report how much of the streamed output was reasoning vs answer.
            // Confirms whether the backend actually engaged thinking mode.
            analyzeReasoningOutput(sseDataLines);

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
     * Effective prompt-processing rate for the tokens that actually had to be computed
     * (i.e. not reused from the KV cache). With a large cached share the raw ppTPS is
     * inflated by the "free" cache hits, so this is the meaningful throughput figure.
     * Returns 0 when there is no timing data ({@code millisPP <= 0}) or no uncached tokens.
     *
     * @param millisPP      prompt processing / time-to-first-token milliseconds
     * @param promptTokens  total prompt tokens
     * @param cachedTokens  prompt tokens reused from the KV cache
     * @return tokens per second for the uncached prompt share
     */
    private static float computePpUncachedTPS(long millisPP, long promptTokens, long cachedTokens) {
        long uncachedTokens = Math.max(0, promptTokens - cachedTokens);
        return (millisPP > 0 && uncachedTokens > 0)
                ? (uncachedTokens * 1000f / millisPP) : 0;
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
                                       List<String> sseDataLines, boolean sampleMetrics,
                                       long metricsBefore) {
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
            // vLLM reports detailed server-side timing metrics in the final chunk (enabled via
            // --enable-prompt-tokens-details). When present they are authoritative and must take
            // precedence over the wall-clock/rate heuristics used for other backends.
            JsonNode metricsNode = dataNode.get("metrics");

            String model = dataNode.has("model") ? dataNode.get("model").asString() : modelName;

            long promptTokens = usageNode.has("prompt_tokens") ? usageNode.get("prompt_tokens").asLong() : 0;
            long completionTokens = usageNode.has("completion_tokens") ? usageNode.get("completion_tokens").asLong() : 0;
            long totalTokens = usageNode.has("total_tokens") ? usageNode.get("total_tokens").asLong() : 0;
            long cachedTokens = 0;
            if (usageNode.has("prompt_tokens_details") && usageNode.get("prompt_tokens_details").has("cached_tokens")) {
                cachedTokens = usageNode.get("prompt_tokens_details").get("cached_tokens").asLong();
            }

            // If metric sampling is enabled, the upstream vLLM /metrics counter delta is a reliable
            // per-request cache indicator and also covers backends/streams that do not (or not yet)
            // report prompt_tokens_details in the usage object.
            if (sampleMetrics && metricsBefore >= 0) {
                long metricsAfter = readVllmCachedTokensMetric();
                if (metricsAfter >= 0) {
                    cachedTokens = metricsAfter - metricsBefore;
                }
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
                                + "millisPP={}, millisTG={}, ppUncachedTPS={}, ppTPS={} (computed: {}), tgTPS={} (computed: {})",
                        promptTokens, completionTokens, totalTokens, cachedTokens,
                        millisPP, millisTG, computePpUncachedTPS(millisPP, promptTokens, cachedTokens),
                        ppTPS, computedPpTPS, tgTPS, computedTgTPS);

            } else if (metricsNode != null && metricsNode.has("time_to_first_token_ms")
                    && metricsNode.get("time_to_first_token_ms").asDouble() > 0) {
                // vLLM provides authoritative server-side timing metrics (with
                // --enable-prompt-tokens-details), which are far more accurate than the wall-clock
                // split below, so the rate heuristic must not be applied in this case.
                double ttftMs = metricsNode.get("time_to_first_token_ms").asDouble();
                double genMs = metricsNode.has("generation_time_ms")
                        ? metricsNode.get("generation_time_ms").asDouble() : 0;
                millisPP = Math.round(ttftMs);
                millisTG = Math.round(genMs);

                double serverTgTPS = metricsNode.has("tokens_per_second")
                        ? metricsNode.get("tokens_per_second").asDouble() : 0;
                tgTPS = (float) serverTgTPS;
                if (tgTPS <= 0 && millisTG > 0 && completionTokens > 0) {
                    tgTPS = completionTokens * 1000f / millisTG;
                }
                ppTPS = (millisPP > 0 && promptTokens > 0)
                        ? (promptTokens * 1000f / millisPP) : 0;

                LOG.info("Usage stats (vLLM metrics): promptTokens={}, completionTokens={}, totalTokens={}, cachedTokens={}, "
                                + "millisPP={}, millisTG={}, ppUncachedTPS={}, ppTPS={}, tgTPS={}",
                        promptTokens, completionTokens, totalTokens, cachedTokens,
                        millisPP, millisTG, computePpUncachedTPS(millisPP, promptTokens, cachedTokens),
                        ppTPS, tgTPS);

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
                                + "millisPP={}, millisTG={}, ppUncachedTPS={}, ppTPS={}, tgTPS={}",
                        promptTokens, completionTokens, totalTokens, cachedTokens,
                        millisPP, millisTG, computePpUncachedTPS(millisPP, promptTokens, cachedTokens),
                        ppTPS, tgTPS);

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
                                + "totalMillis={}, millisPP={}, millisTG={}, ppUncachedTPS={}, ppTPS={}, tgTPS={}",
                        promptTokens, completionTokens, totalTokens, cachedTokens,
                        totalMillis, millisPP, millisTG,
                        computePpUncachedTPS(millisPP, promptTokens, cachedTokens),
                        ppTPS, tgTPS);
            }

            // Effective prompt-processing rate for the tokens that actually had to be computed
            // (i.e. not reused from the KV cache). With a large cached share the raw ppTPS is
            // inflated by the "free" cache hits, so this is the meaningful throughput figure.
            float ppUncachedTPS = computePpUncachedTPS(millisPP, promptTokens, cachedTokens);

            LlmUsage usage = new LlmUsage(tsStart, millisPP, millisTG, model,
                    promptTokens, completionTokens, totalTokens, cachedTokens,
                    ppUncachedTPS, ppTPS, tgTPS);
            usages.add(usage);

            // Write to JSONL file if configured
            writeStatsJsonl(usage, usageNode);

        } catch (RuntimeException e) {
            LOG.warn("Failed to parse usage statistics from SSE data: {}", e.getMessage(), e);
        }
    }

    /**
     * Diagnostic: counts how much of the streamed output was reasoning vs answer content.
     *
     * <p>The DeepSeek API returns the chain-of-thought in the {@code reasoning_content} field at
     * the same level as {@code content}. This method inspects the collected SSE data lines and
     * totals the lengths of {@code reasoning_content} and {@code content} deltas, so it is
     * possible to confirm whether the backend actually engaged thinking mode instead of guessing
     * from the token count alone.</p>
     *
     * @param sseDataLines the SSE data line JSON strings collected during the stream (may be empty)
     */
    private void analyzeReasoningOutput(List<String> sseDataLines) {
        if (sseDataLines == null || sseDataLines.isEmpty()) {
            return;
        }
        int reasoningChars = 0;
        int contentChars = 0;
        for (String line : sseDataLines) {
            try {
                JsonNode node = jsonMapper.readTree(line);
                JsonNode choice = !node.path("choices").isEmpty() ? node.path("choices").get(0) : null;
                if (choice == null) {
                    continue;
                }
                JsonNode delta = choice.get("delta");
                if (delta != null) {
                    JsonNode reasoning = delta.get("reasoning_content");
                    JsonNode content = delta.get("content");
                    if (reasoning != null && reasoning.isString()) {
                        reasoningChars += reasoning.asString().length();
                    }
                    if (content != null && content.isString()) {
                        contentChars += content.asString().length();
                    }
                }
            } catch (Exception e) {
                // ignore non-JSON / parse errors in diagnostic
            }
        }
        LOG.info("Reasoning-output analysis: reasoningChars={}, contentChars={}, totalSseLines={}",
                reasoningChars, contentChars, sseDataLines.size());
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
            record.put("ppUncachedTPS", usage.ppUncachedTPS());
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
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);

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
        long totalCachedTokens = usages.stream().mapToLong(LlmUsage::cachedTokens).sum();
        // #TokenInUncached reflects the prompt tokens that actually had to be computed
        // (i.e. not served from the KV/prefix cache).
        long totalUncachedTokens = Math.max(0, totalPromptTokens - totalCachedTokens);
        LOG.info("Shutdown: LLM usage statistics - #Requests={}, #TokenIn={}, #TokenInCached={}, #TokenInUncached={}, #TokenOut={}, #TotalTokens={}",
                requestCount, totalPromptTokens, totalCachedTokens, totalUncachedTokens,
                totalCompletionTokens, totalTokens);
    }

    /**
     * Legacy method for non-streaming requests.
     * Buffers the response to allow post-processing (SSE wrapping).
     */
    private String forwardRequestToLLMBuffered(JsonNode requestNode, String cookie, String requestPath) throws IOException {
        ObjectNode llmRequest = (ObjectNode) requestNode.deepCopy();
        llmRequest.put("model", modelName);

        // Keep the reasoning translation consistent with the streaming path.
        if (BACKEND_VLLM.equalsIgnoreCase(backend)) {
            applyVllmReasoning(llmRequest);
        } else {
            injectReasoningEffortFromBudget(llmRequest);
        }

        // Force non-streaming for buffered mode
        llmRequest.put("stream", false);

        if (!llmRequest.has("max_tokens") && maxTokens != null) {
            llmRequest.put("max_tokens", maxTokens);
        }

        // Repair invalid tool-call arguments before forwarding (see sanitizeToolCalls).
        sanitizeToolCalls(llmRequest);

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
            // For status >= 400 the response body is only exposed via getErrorStream().
            // Log it so the backend's error detail (e.g. vLLM's JSON error) is visible.
            String errorBody = "";
            try (InputStream errorStream = connection.getErrorStream()) {
                if (errorStream != null) {
                    errorBody = readResponse(errorStream);
                }
            }
            LOG.error("HTTP error accessing {}: {} - {}: {}", url, responseCode,
                    connection.getResponseMessage(),
                    errorBody.length() > 2000 ? errorBody.substring(0, 2000) + "..." : errorBody);
            return null;
        }

        String responseBody;
        try (InputStream is = connection.getInputStream()) {
            responseBody = readResponse(is);
        } finally {
            connection.disconnect();
        }

        // For a vLLM/DeepSeek backend, non-streaming responses carry the chain-of-thought in
        // "choices[].message.reasoning". Rewrite it to "reasoning_content" so the Web UI
        // renders the thinking block (mirrors the streaming rewrite in copyStream).
        if (BACKEND_VLLM.equalsIgnoreCase(backend)) {
            responseBody = rewriteReasoningFieldInBody(responseBody);
        }

        // Try to parse usage from the non-streaming JSON response
        try {
            JsonNode responseNode = jsonMapper.readTree(responseBody);
            if (responseNode.has("usage")) {
                List<String> dataLines = new ArrayList<>();
                dataLines.add(jsonMapper.writeValueAsString(responseNode));
                // Non-streaming responses carry prompt_tokens_details.cached_tokens natively,
                // so no extra /metrics sampling is needed here.
                recordUsageStatistics(tsStart, null, dataLines, false, -1);
            }
        } catch (RuntimeException e) {
            LOG.warn("Could not parse usage from non-streaming response: {}", e.getMessage());
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
     * <p>When {@code rewriteFlashReasoning} is {@code true} (vLLM/DeepSeek backend), each SSE
     * {@code data:} JSON is transformed on the fly: a vLLM {@code reasoning} field (in
     * {@code choices[].delta} for streaming, or {@code choices[].message} for a non-streaming
     * response) is copied to {@code reasoning_content}, which is the field the Web UI and the
     * usage/reasoning analysis expect. Fields the client already provides are left untouched.</p>
     *
     * <p>In addition, for the vLLM backend every chunk carrying a {@code usage} object (enabled
     * via {@code stream_options.continuous_usage_stats}) is enriched with a synthesized
     * llama.cpp-style {@code timings} node. vLLM only reports the token counters per chunk but
     * no rates, so the Web UI cannot show live token/s statistics while streaming. The proxy
     * fills this gap by computing the prompt and generation rates from the cumulative token
     * counts and the wall-clock time of the incoming SSE chunks (plus the request send time),
     * mirroring the {@code timings} shape llama.cpp sends.</p>
     *
     * @param in               source input stream
     * @param out              target output stream
     * @param sseDataLines     collector for SSE data line JSON strings (may be null)
     * @param firstContentTime atomic reference to store the timestamp of the first content token (may be null)
     * @param rewriteFlashReasoning whether to apply the vLLM-specific SSE enrichment (reasoning rewrite + live timings)
     * @param tsStartNano      {@link System#nanoTime()} of the request start, reference for the prompt rate
     * @throws IOException if an I/O error occurs
     */
    private void copyStream(InputStream in, OutputStream out, List<String> sseDataLines,
                            AtomicReference<LocalDateTime> firstContentTime,
                            boolean rewriteFlashReasoning, long tsStartNano) throws IOException {
        // Buffer for a partial SSE data line that was split across chunk boundaries.
        StringBuilder pendingData = new StringBuilder();
        byte[] buffer = new byte[4096];
        int bytesRead;
        int lineCount = 0;
        List<String> lastTwoLines = new ArrayList<>();
        // vLLM per-chunk timing state (only consumed while rewriteFlashReasoning is active).
        VllmTimingState vllmTiming = new VllmTimingState();

        while ((bytesRead = in.read(buffer)) != -1) {
            String chunk = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);

            // Collect SSE data lines for usage extraction
            // and detect the first content token for PP/TG separation
            if (sseDataLines != null || firstContentTime != null || rewriteFlashReasoning) {
                // Prepend any pending data from a previous partial line
                String parseText;
                if (!pendingData.isEmpty()) {
                    parseText = pendingData + chunk;
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
                    if (!dataLine.isEmpty()) {
                        boolean isDoneLine = "[DONE]".equals(dataLine);
                        // The line forwarded to the client: for vLLM this is the reasoning rewrite
                        // plus a synthesized llama.cpp-style "timings" node (live token/s rates).
                        String clientLine = dataLine;
                        // The line collected for the internal statistics below stays free of the
                        // synthesized timings so recordUsageStatistics keeps its vLLM classification;
                        // the reasoning rewrite is still applied for analyzeReasoningOutput.
                        String collectedLine = dataLine;
                        if (rewriteFlashReasoning && !isDoneLine) {
                            clientLine = enrichVllmSseLine(dataLine, tsStartNano, vllmTiming);
                            collectedLine = rewriteReasoningField(dataLine);
                        }
                        if (lineCount < 2) {
                            LOG.debug("SSE line (early): {}", clientLine);
                        } else if (!isDoneLine) {
                            lastTwoLines.add(collectedLine);
                            if (lastTwoLines.size() > 2) {
                                lastTwoLines.remove(0);
                            }
                        }
                        lineCount++;

                        // Forward the (possibly enriched) line to the client.
                        out.write(("data: " + clientLine + "\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();

                        if (isDoneLine) {
                            if (sseDataLines != null) {
                                // Add the last two lines (which include usage statistics) before the [DONE] line
                                sseDataLines.addAll(lastTwoLines);
                                sseDataLines.add(dataLine);
                            }
                            // Check for first content token (streaming: choices[0].delta.content != null)
                            if (firstContentTime != null && firstContentTime.get() == null
                                    && dataLine.contains("\"content\"")) {
                                firstContentTime.compareAndExchange(null, LocalDateTime.now());
                            }
                        }
                    }
                    idx = lineEnd + 1;
                }
                continue;
            }
            out.write(buffer, 0, bytesRead);
            out.flush(); // Critical for SSE: push chunks immediately
        }

        if (!lastTwoLines.isEmpty()) {
            LOG.debug("SSE last two lines: {}", lastTwoLines);
        }
    }

    /**
     * Copies a vLLM {@code reasoning} field to {@code reasoning_content} within a single SSE
     * data JSON line, for both the streaming ({@code choices[].delta}) and the non-streaming
     * ({@code choices[].message}) shapes. If the line already carries a {@code reasoning_content},
     * it is left untouched. Non-JSON lines are returned unchanged.
     *
     * @param dataLine the raw SSE data payload
     * @return the possibly rewritten data payload
     */
    private String rewriteReasoningField(String dataLine) {
        JsonNode node;
        try {
            node = jsonMapper.readTree(dataLine);
        } catch (RuntimeException e) {
            // payload is not decodable JSON (e.g. a keep-alive frame); forward it unchanged.
            LOG.warn("Skipping non-JSON SSE data line: {}", e.getMessage());
            return dataLine;
        }
        if (node == null || !node.isObject()) {
            return dataLine;
        }
        if (!rewriteReasoningNode(node)) {
            return dataLine;
        }
        try {
            return jsonMapper.writeValueAsString(node);
        } catch (RuntimeException e) {
            // Parsing succeeded but re-serialization failed - an internal inconsistency in the
            // rewritten node. Falling back to the raw payload must not hide that.
            LOG.warn("Failed to re-serialize rewritten SSE data line, forwarding raw payload back: {}",
                    e.getMessage(), e);
            return dataLine;
        }
    }

    /**
     * Copies a vLLM {@code reasoning} field to {@code reasoning_content} within a parsed JSON
     * node, for both the streaming ({@code choices[].delta}) and the non-streaming
     * ({@code choices[].message}) shapes. The node is mutated in place.
     *
     * @param node the parsed JSON node to modify
     * @return {@code true} if any field was rewritten, {@code false} otherwise
     */
    private boolean rewriteReasoningNode(JsonNode node) {
        JsonNode choices = node.get("choices");
        if (choices == null || !choices.isArray()) {
            return false;
        }
        boolean changed = false;
        for (JsonNode choice : choices) {
            if (choice == null || !choice.isObject()) {
                continue;
            }
            // streaming shape
            JsonNode delta = choice.get("delta");
            if (delta != null && delta.isObject()
                    && !delta.has("reasoning_content")
                    && delta.has("reasoning")) {
                ((ObjectNode) delta).put("reasoning_content", delta.get("reasoning").asString());
                changed = true;
            }
            // non-streaming shape
            JsonNode message = choice.get("message");
            if (message != null && message.isObject()
                    && !message.has("reasoning_content")
                    && message.has("reasoning")) {
                ((ObjectNode) message).put("reasoning_content", message.get("reasoning").asString());
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Enriches a single SSE data line received from a vLLM/DeepSeek backend so the Web UI can
     * show live token/s statistics while the stream is still running.
     *
     * <p>vLLM (with {@code stream_options.continuous_usage_stats}) reports the cumulative token
     * counters in every chunk ({@code usage.prompt_tokens}/{@code usage.completion_tokens}) but
     * - unlike llama.cpp - neither {@code timings} nor {@code prompt_progress}. The Web UI reads
     * the llama.cpp-style {@code timings} node per chunk to render the live generation/token
     * rates, so without it only the final statistics are available.</p>
     *
     * <p>This method synthesizes that node from the usage counters and wall-clock time:
     * <ul>
     *   <li>{@code prompt_per_second} - prompt tokens processed per second, based on the
     *       request send time (reference passed in) and frozen once the first completion
     *       token arrives (after that llama.cpp semantics apply),</li>
     *   <li>{@code predicted_per_second} - running average of completion tokens per second
     *       since the first completion token,</li>
     *   <li>{@code predicted_per_token_ms} - milliseconds per generated token.</li>
     * </ul>
     * If either computed rate exceeds {@link #MAX_PLAUSIBLE_TOKENS_PER_SECOND} the timings
     * enrichment is skipped for this chunk (the counters alone would otherwise present an
     * artefact as a believable speed, e.g. measured over a near-zero millisecond span). The
     * reasoning rewrite of {@link #rewriteReasoningField} is still applied in the same parse
     * pass.</p>
     *
     * @param dataLine    the raw SSE data payload
     * @param tsStartNano {@link System#nanoTime()} of the request start (prompt rate reference)
     * @param timing      per-stream timing state (generation-start time)
     * @return the enriched data payload, or the original line if it is not a JSON object
     */
    private String enrichVllmSseLine(String dataLine, long tsStartNano, VllmTimingState timing) {
        JsonNode node;
        try {
            node = jsonMapper.readTree(dataLine);
        } catch (RuntimeException e) {
            // payload is not decodable JSON (e.g. a keep-alive frame); forward it unchanged.
            LOG.warn("Skipping non-JSON SSE data line: {}", e.getMessage());
            return dataLine;
        }
        if (node == null || !node.isObject()) {
            return dataLine;
        }

        boolean changed = rewriteReasoningNode(node);

        JsonNode usageNode = node.get("usage");
        if (usageNode != null && usageNode.isObject() && usageNode.has("completion_tokens")) {
            long now = System.nanoTime();

            long promptN = usageNode.has("prompt_tokens") ? usageNode.get("prompt_tokens").asLong() : 0L;
            long completionN = usageNode.get("completion_tokens").asLong();

            // The generation phase starts with the first completion token (covers both
            // reasoning and answer tokens). From then on the prompt time stays frozen.
            if (!timing.genStarted && completionN > 0L) {
                timing.genStarted = true;
                timing.genStartNano = now;
            }

            // prompt time: request send -> first completion token (or the current chunk while
            // the prompt is still being processed)
            long promptMs = timing.genStarted
                    ? millisElapsed(tsStartNano, timing.genStartNano)
                    : millisElapsed(tsStartNano, now);
            // generation time: first completion token -> current chunk
            long genMs = timing.genStarted ? millisElapsed(timing.genStartNano, now) : 0L;

            double promptPerSecond = promptMs > 0 ? promptN * 1000.0 / promptMs : 0.0;
            double predictedPerSecond = genMs > 0 ? completionN * 1000.0 / genMs : 0.0;

            // Implausible rates are timing artefacts, skip the timings enrichment for this chunk.
            if (promptPerSecond <= MAX_PLAUSIBLE_TOKENS_PER_SECOND
                    && predictedPerSecond <= MAX_PLAUSIBLE_TOKENS_PER_SECOND) {
                ObjectNode timings = jsonMapper.createObjectNode();
                timings.put("prompt_n", promptN);
                timings.put("prompt_ms", promptMs);
                timings.put("prompt_per_second", promptPerSecond);
                timings.put("predicted_n", completionN);
                timings.put("predicted_ms", genMs);
                timings.put("predicted_per_token_ms", genMs > 0 && completionN > 0 ? (double) genMs / completionN : 0.0);
                timings.put("predicted_per_second", predictedPerSecond);

                ((ObjectNode) node).set("timings", timings);
                changed = true;
            }
        }

        if (!changed) {
            return dataLine;
        }
        try {
            return jsonMapper.writeValueAsString(node);
        } catch (RuntimeException e) {
            // Parsing and enrichment succeeded but re-serialization failed - an internal
            // inconsistency in the modified node. Falling back to the raw payload must not hide it.
            LOG.warn("Failed to re-serialize enriched vLLM SSE data line, forwarding raw payload back: {}",
                    e.getMessage(), e);
            return dataLine;
        }
    }

    /**
     * Per-stream timing state for synthesizing llama.cpp-style {@code timings} on the vLLM path.
     * Lives for the duration of a single {@link #copyStream} invocation.
     */
    private static final class VllmTimingState {
        /** nanoTime of the first chunk carrying a completion token (generation start). */
        long genStartNano = 0L;
        /** Whether the generation phase has started. */
        boolean genStarted = false;
    }

    /**
     * Returns the elapsed milliseconds between two {@link System#nanoTime()} readings.
     *
     * @param startNano earlier reading
     * @param endNano   later reading (may equal the previous read)
     * @return elapsed milliseconds, 0 for a non-positive span
     */
    private static long millisElapsed(long startNano, long endNano) {
        long nanos = endNano - startNano;
        return nanos > 0 ? nanos / 1_000_000L : 0L;
    }

    /**
     * Rewrites the vLLM {@code reasoning} field to {@code reasoning_content} in a complete
     * non-streaming JSON response body. If the body is valid JSON with a {@code choices[]}
     * array, it delegates to {@link #rewriteReasoningField}; otherwise it is returned unchanged.
     *
     * @param body the raw response body (a single JSON object)
     * @return the possibly rewritten body
     */
    private String rewriteReasoningFieldInBody(String body) {
        if (body == null || body.isBlank()) {
            return body;
        }
        return rewriteReasoningField(body);
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