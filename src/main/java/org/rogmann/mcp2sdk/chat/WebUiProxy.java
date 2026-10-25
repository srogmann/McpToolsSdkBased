package org.rogmann.mcp2sdk.chat;

import org.rogmann.mcp2sdk.agent.ChatContextStore;
import org.rogmann.mcp2sdk.utils.LlmUsage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;
import jakarta.annotation.PostConstruct;
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
 *
 * <p>Server-side Web UI settings (the replacement for llama.cpp's {@code --ui-config}, which this
 * application does not have because it serves the UI itself): {@code /chat/props} carries a
 * {@code ui_settings} object built from {@code webui.uiConfig} / {@code webui.customCss}. The Web
 * UI treats it as its default - a browser that already stores its own settings (Developer settings
 * such as "Custom CSS" included) keeps them, see {@link #buildUiSettings()}.</p>
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
    /** Switches the DEBUG logging of LLM responses on (request logging is not affected). */
    private static final String PROP_LOG_RESPONSES = "webui.logResponses";
    /**
     * Drops blank answer content that a model emits <em>between two tool calls</em> before the
     * SSE line reaches the Web UI (see {@link #stripToolCallInterleaveContent}). Off by default;
     * enable it only for the model/backend combination that shows the broken tool-call history.
     */
    private static final String PROP_SUPPRESS_TOOLCALL_INTERLEAVE = "webui.suppressToolCallInterleave";
    /**
     * Server-side UI defaults as a JSON object, served through {@code /props} in the
     * {@code ui_settings} field. This is the counterpart of llama.cpp's {@code --ui-config}
     * argument, e.g. {@code {"customCss": ".markdown-content { ... }"}}.
     */
    private static final String PROP_UI_CONFIG = "webui.uiConfig";
    /** Convenience accessor for the single {@code customCss} UI setting (the "Custom CSS" field). */
    private static final String PROP_CUSTOM_CSS = "webui.customCss";

    /** Key of the "Custom CSS" setting inside {@code ui_settings} (Web UI: {@code customCss}). */
    private static final String UI_SETTING_CUSTOM_CSS = "customCss";

    /** Field name of the server-side UI defaults in the {@code /props} response. */
    private static final String PROPS_FIELD_UI_SETTINGS = "ui_settings";

    /** Backend vLLM */
    private static final String BACKEND_VLLM = "vllm";

    /**
     * Plausibility ceiling for the token rates synthesized on the vLLM path. Rates above this
     * value (e.g. measured over a near-zero millisecond span) are treated as timing artifacts.
     *
     * <p>The ceiling is applied <em>per rate</em>, not to the synthesized {@code timings} node as
     * a whole: an implausible generation rate drops the node (the Web UI recomputes the rate from
     * {@code predicted_n}/{@code predicted_ms}, so the counters alone would bring the artefact
     * back), an implausible prompt rate drops only {@code prompt_per_second}. The prompt rate is
     * judged on the tokens that the prefix cache did <em>not</em> serve, because a warm cache
     * legitimately produces figures far above this ceiling (15k prompt tokens in 370 ms are
     * 40k token/s) - screening it uncorrected would silence the live rate of the Web UI in
     * exactly the requests that have plenty of them.</p>
     */
    private static final double MAX_PLAUSIBLE_TOKENS_PER_SECOND = 20_000.0;

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
    private String modelNameProp;

    /** URL of the LLM endpoint */
    @Value("${" + PROP_MODEL_URL + ":http://localhost:8080}")
    private String modelUrlProp;

    /** Reasoning mode: on, off, auto (auto = derive from the chat template) */
    @Value("${" + PROP_REASONING + ":auto}")
    private String reasoningMode;

    /** Backend type: llamacpp (default) or vllm (deepseek-compatible thinking/reasoning_effort) */
    @Value("${" + PROP_BACKEND + ":llamacpp}")
    private String backend;

    /** Whether to fetch per-request cache metrics from the upstream vLLM /metrics endpoint */
    @Value("${" + PROP_FETCH_METRICS + ":false}")
    private boolean fetchMetrics;

    /**
     * Whether the LLM <em>response</em> is logged on DEBUG in addition to the request body
     * (property {@value #PROP_LOG_RESPONSES}, default false).
     *
     * <p>Unlike the request log line the response cannot be handed to the logger as-is: the
     * streamed SSE chunks have to be accumulated (reasoning text, answer text, finish reason,
     * usage) and re-serialized per request, i.e. StringBuilders and JSON work that are pure
     * overhead in normal operation. That is why this is a separate switch instead of simply
     * raising the log level; it only takes effect together with active DEBUG logging
     * (see {@link #responseLogEnabled()}).</p>
     */
    @Value("${" + PROP_LOG_RESPONSES + ":false}")
    private boolean logResponses;

    /**
     * Whether blank {@code delta.content} chunks between two tool calls are removed before
     * forwarding to the Web UI (property {@value #PROP_SUPPRESS_TOOLCALL_INTERLEAVE}, default false).
     *
     * <p>Some model/backend combinations end every tool-call block with a newline token, so the
     * stream carries a content delta between two {@code delta.tool_calls} blocks. The Web UI
     * interprets any content delta as the end of a tool-call batch and shifts the next batch by
     * the number of calls collected so far, which fabricates an empty tool call in the chat
     * history. See {@link #stripToolCallInterleaveContent} for the workaround and its limits.</p>
     */
    @Value("${" + PROP_SUPPRESS_TOOLCALL_INTERLEAVE + ":false}")
    private boolean suppressToolCallInterleave;

    /**
     * Server-side UI defaults as JSON object (property {@value #PROP_UI_CONFIG}), served via
     * {@code /props} as {@code ui_settings}. Empty by default, which keeps the field out of the
     * response entirely.
     */
    @Value("${" + PROP_UI_CONFIG + ":}")
    private String uiConfigJson;

    /**
     * The "Custom CSS" of the Web UI (property {@value #PROP_CUSTOM_CSS}). Takes precedence over a
     * {@code customCss} entry of {@link #uiConfigJson} because it is the more specific switch.
     */
    @Value("${" + PROP_CUSTOM_CSS + ":}")
    private String customCss;

    /** Vision capabilities */
    @Value("${" + PROP_HAS_VISION + ":false}")
    private boolean hasVision;

    /** Audio capabilities */
    @Value("${" + PROP_HAS_AUDIO + ":false}")
    private boolean hasAudio;

    /** Maximum tokens (only set if explicitly configured) */
    @Value("${" + PROP_MAX_TOKENS + ":}")
    private Integer maxTokens;

    /** Counter of served static resources (first N logged on INFO, rest on DEBUG) */
    private final AtomicLong staticResourceCount = new AtomicLong();

    /** JsonMapper for JSON processing (Jackson 3) */
    private final JsonMapper jsonMapper;

    /**
     * Shortens the request and response bodies of the DEBUG log lines
     * (docs/webui.md R1, same limits as before the move).
     */
    private final ResponseLogFormat responseLogFormat;

    /**
     * Chat template of the configured model and the reasoning translations built on it
     * (docs/webui.md R2). Stateless apart from its caches, so one instance per proxy is enough;
     * {@code ChatRequestPreparer} is handed the same one.
     */
    private final ChatTemplateInfo chatTemplateInfo = new ChatTemplateInfo();

    /**
     * Lazily created preparation seam, see {@link #requestPreparer()}.
     */
    private ChatRequestPreparer requestPreparer;

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
        this.responseLogFormat = new ResponseLogFormat(this.jsonMapper);
    }

    /**
     * Returns the name of the LLM model.
     *
     * @return the model name
     */
    public String getModelName() {
        return modelNameProp;
    }

    /**
     * Returns the URL of the LLM endpoint.
     *
     * @return the model URL
     */
    public String getModelUrl() {
        return modelUrlProp;
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
        model.put("id", getModelName());
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
     * Prepares a request body for logging, see {@link ResponseLogFormat#shortenRequestBody}.
     *
     * <p>The formatting itself moved to {@link ResponseLogFormat} (docs/webui.md R1); this
     * delegate stays because the log call sites of both forwarding paths - and the unit test -
     * address the request log line by this name.</p>
     *
     * @param requestBody raw request body
     * @return shortened representation suitable for logging
     */
    private String shortenRequestBody(String requestBody) {
        return responseLogFormat.shortenRequestBody(requestBody);
    }

    /**
     * Prepares a response body for logging, see {@link ResponseLogFormat#shortenResponseBody}.
     *
     * @param responseBody raw response body or the response summary built from the SSE chunks
     * @return shortened representation suitable for logging
     */
    private String shortenResponseBody(String responseBody) {
        return responseLogFormat.shortenResponseBody(responseBody);
    }


    /**
     * Whether the LLM response has to be collected for the DEBUG response log, i.e. the property
     * {@value #PROP_LOG_RESPONSES} is set <em>and</em> DEBUG logging is really active. The second
     * condition keeps the collection cost (StringBuilders, JSON re-serialization per chunk) off
     * the stream when the logger would drop the line anyway.
     *
     * @return true if a response collector should be created for the current request
     */
    private boolean responseLogEnabled() {
        return logResponses && LOG.isDebugEnabled();
    }


    /**
     * Forwarding of LLM requests (streaming / non-streaming)
     * Accessible at: /chat/v1/chat/completions
     */
    @PostMapping(value = "/v1/chat/completions",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public void completions(@RequestBody String requestBody,
                            @RequestHeader(value = "Cookie", required = false) String cookie,
                            @RequestHeader(value = ChatContextStore.HEADER_CONVERSATION_ID,
                                    required = false) String conversationId,
                            @RequestHeader(value = ChatContextStore.HEADER_FORK_DEPTH,
                                    required = false) String forkDepth,
                            @RequestHeader(value = "x-session-id",
                                    required = false) String xSessionId,
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

            // Remember what arrived, before anything is prepared: a prefix fork starts its children
            // from exactly this body, so that they share the token prefix of this conversation
            // (docs/webui.md §3.2). Failures here are logged, never turned into a failed chat turn.
            ChatContextStore.getInstance().record(conversationId, forkDepth, requestNode,
                    selfBaseUrlOf(request));

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
                forwardRequestToLLM(requestNode, cookie, xSessionId, requestPath, response, true);
            } else {
                // --- NON-STREAMING MODE (Legacy Compatibility) ---
                // Keep existing behavior: Buffer response, wrap in SSE, then send.
                // This ensures the WebUI doesn't break if it expects SSE format for non-streaming.
                response.setContentType("text/event-stream");
                response.setCharacterEncoding("UTF-8");

                String llmResponse = forwardRequestToLLMBuffered(requestNode, cookie, xSessionId, requestPath);

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
     * Base URL this proxy is reachable under, derived from the request that just arrived instead
     * of a configured property: the forked children loop back through the very endpoint the caller
     * used (IPv4 versus IPv6 localhost, context path, port), which is what keeps their request on
     * the identical preparation path (docs/webui.md §3.2).
     *
     * @param request the completion request being served
     * @return e.g. {@code http://127.0.0.1:9091}, without a trailing slash
     */
    private static String selfBaseUrlOf(HttpServletRequest request) {
        String fullUrl = request.getRequestURL().toString();
        String uri = request.getRequestURI();
        String contextPath = (request.getContextPath() == null) ? "" : request.getContextPath();
        int cut = fullUrl.lastIndexOf(uri);
        if (cut < 0) {
            return fullUrl;
        }
        return fullUrl.substring(0, cut) + contextPath;
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
        mapProps.put("model_path", getModelName());

        ObjectNode modalities = jsonMapper.createObjectNode();
        modalities.put("vision", hasVision);
        modalities.put("audio", hasAudio);
        mapProps.set("modalities", modalities);

        // Server-side UI defaults (llama.cpp --ui-config counterpart). The Web UI reads this as
        // props.ui_settings and seeds its settings store from it, see buildUiSettings().
        ObjectNode uiSettings = buildUiSettings();
        if (!uiSettings.isEmpty()) {
            mapProps.set(PROPS_FIELD_UI_SETTINGS, uiSettings);
        }

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
     * Builds the {@code ui_settings} node of the {@code /props} response: the llama.cpp-compatible
     * channel for server-side UI defaults (llama.cpp fills it from its {@code --ui-config}
     * argument, here it is assembled from {@value #PROP_UI_CONFIG} and {@value #PROP_CUSTOM_CSS}).
     *
     * <p>Key naming follows the Web UI settings store: the keys are the camelCase names of the
     * settings dialog ({@code customCss}, {@code sendOnEnter}, {@code theme}, ...), so whatever the
     * "Settings" page can set can also be shipped from the server.</p>
     *
     * <p><b>It stays a default</b> (behaviour of the built Web UI, not of this proxy): a browser
     * applies {@code ui_settings} only while it has no own copy of the settings in
     * {@code localStorage}, only for entries it never changed itself, and otherwise on
     * "Reset Settings to Default". A developer who has set "Custom CSS" locally therefore keeps the
     * local value and is not disturbed by whatever the server ships.</p>
     *
     * <p>Note also that the Web UI only consults {@code ui_settings} when the generation settings
     * of {@code default_generation_settings.params} yielded at least one known parameter (its
     * sync step returns early otherwise), so those must stay part of the props response.</p>
     *
     * @return the node to serve, empty when neither property is configured
     */
    ObjectNode buildUiSettings() {
        ObjectNode uiSettings = jsonMapper.createObjectNode();

        if (uiConfigJson != null && !uiConfigJson.isBlank()) {
            try {
                JsonNode node = jsonMapper.readTree(uiConfigJson);
                if (node != null && node.isObject()) {
                    uiSettings.setAll((ObjectNode) node);
                } else {
                    LOG.warn("{} is not a JSON object, ignoring it (expected e.g. "
                            + "{\"customCss\": \"...\"})", PROP_UI_CONFIG);
                }
            } catch (RuntimeException e) {
                // A broken property must not break /props: the UI then simply uses its defaults.
                LOG.warn("Cannot parse {} as JSON, ignoring it: {}", PROP_UI_CONFIG, e.getMessage());
            }
        }

        // The dedicated property wins over a customCss entry of the JSON blob: it is the one that
        // is written in multi-line YAML style, so it is what an operator actually edits.
        if (customCss != null && !customCss.isBlank()) {
            uiSettings.put(UI_SETTING_CUSTOM_CSS, customCss);
        }

        return uiSettings;
    }

    /**
     * Logs once at startup which UI settings the server contributes, because {@code ui_settings}
     * acts invisibly from the browser side: it is neither visible in the props response of a
     * running page nor in the settings dialog once the browser has applied it.
     */
    @PostConstruct
    public void logUiSettingsConfig() {
        ObjectNode uiSettings = buildUiSettings();
        if (uiSettings.isEmpty()) {
            LOG.debug("No {} / {} configured, the Web UI keeps its own defaults.",
                    PROP_UI_CONFIG, PROP_CUSTOM_CSS);
            return;
        }
        List<String> keys = uiSettings.properties().stream().map(Map.Entry::getKey).toList();
        LOG.info("Web UI defaults via /props {}={}: {} setting(s), customCss {} chars. They act as"
                        + " defaults only: a browser with its own stored settings keeps those, they"
                        + " are applied on the first visit or after 'Reset Settings to Default'.",
                PROPS_FIELD_UI_SETTINGS, keys, keys.size(),
                customCss == null ? 0 : customCss.length());
    }

    /**
     * Builds the chat_template to deliver via /props, see
     * {@link ChatTemplateInfo#propsChatTemplate(String, String)}.
     *
     * @return the chat template to serve, or null to omit it (reasoning detection off)
     */
    private String buildChatTemplate() {
        return chatTemplateInfo.propsChatTemplate(getModelName(), reasoningMode);
    }

    /**
     * Derives the reasoning effort from the thinking budget of the request, llama.cpp spelling
     * (see {@link ChatTemplateInfo#injectReasoningEffortFromBudget}). Both forwarding paths get
     * this through {@link #requestPreparer()}; the delegate stays as the proxy-side entry
     * point the unit test of the translation drives (docs/webui.md F1).
     *
     * @param llmRequest the deep-copied request forwarded to the LLM (mutated in place)
     */
    private void injectReasoningEffortFromBudget(ObjectNode llmRequest) {
        chatTemplateInfo.injectReasoningEffortFromBudget(llmRequest, getModelName());
    }

    /**
     * Applies the vLLM (DeepSeek OpenAI-format) thinking translation
     * (see {@link ChatTemplateInfo#applyVllmReasoning}).
     *
     * @param llmRequest the deep-copied request forwarded to the LLM (mutated in place)
     */
    private void applyVllmReasoning(ObjectNode llmRequest) {
        chatTemplateInfo.applyVllmReasoning(llmRequest, getModelName());
    }

    /**
     * Reads the value of the {@code vllm:prompt_tokens_cached_total} counter from the upstream
     * vLLM {@code /metrics} endpoint. Returns -1 when the metric cannot be fetched or parsed
     * (e.g. endpoints not reachable, counter missing, non-vLLM backend).
     *
     * @return the current counter value, or -1 on failure
     */
    private long readVllmCachedTokensMetric() {
        String base = getModelUrl();
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
     * The preparation seam of both forwarding paths (docs/webui.md R4).
     *
     * <p>Built on first use, not in the constructor: the backend type, the model name and the
     * default {@code max_tokens} come from injected properties, which are only set after
     * construction. The preparer is stateless apart from those values, so the one instance also
     * serves every later request.</p>
     *
     * @return the shared {@link ChatRequestPreparer} of this proxy
     */
    private synchronized ChatRequestPreparer requestPreparer() {
        if (requestPreparer == null) {
            requestPreparer = new ChatRequestPreparer(jsonMapper, chatTemplateInfo, getModelName(),
                    backend, maxTokens);
        }
        return requestPreparer;
    }

    /**
     * Forwards a request to the LLM server and PIPES the response stream directly.
     * Used for Streaming (SSE).
     */
    private void forwardRequestToLLM(JsonNode requestNode, String cookie, String xSessionId,
                                     String requestPath, HttpServletResponse clientResponse,
                                     boolean streaming) throws IOException {
        // The seam "incoming body -> outgoing body" (docs/webui.md R4): deep-copy, model override,
        // reasoning translation, max_tokens default, stream_options and the history repair - the
        // same code the buffered path runs, so both stay identical by construction.
        ObjectNode llmRequest = requestPreparer().prepare(requestNode,
                ChatRequestPreparer.Mode.STREAMING);

        String requestOut = jsonMapper.writeValueAsString(llmRequest);
        LOG.debug("LLM Request: {}", shortenRequestBody(requestOut));

        // Optional DEBUG dump of the answer (property webui.logResponses). The collector is only
        // created when that switch is on - otherwise the streaming path neither buffers the chunks
        // nor re-serializes a summary, which is the whole point of the extra property.
        SseResponseLog responseLog = responseLogEnabled() ? new SseResponseLog(jsonMapper) : null;

        // Build target URL: modelUrl + requestPath
        String targetUrl = getModelUrl();
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
        // synthesized on the vLLM path (request-send time -> chunk arrival times) and for the
        // client-side time-to-first-output-token measurement.
        final long tsStartNano = System.nanoTime();
        // nanoTime of the first output token (reasoning or answer content), 0 until captured.
        final AtomicLong firstOutputNano = new AtomicLong(0L);
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
            // Note: do NOT send an Expect header at all. HttpURLConnection does not send
            // "Expect: 100-continue" by default; explicitly setting an empty header value
            // would still transmit "Expect:" and Tomcat rejects that with HTTP 417
            // Expectation Failed.

            if (cookie != null) {
                connection.setRequestProperty("Cookie", cookie);
            }
            // Session routing for backends behind a load balancer (docs/acp.md S3): the ACP
            // chat marks its requests with the ACP session id; the proxy forwards the header.
            if (xSessionId != null) {
                connection.setRequestProperty("x-session-id", xSessionId);
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
                copyStream(is, os, sseDataLines, firstContentTime, firstOutputNano,
                        BACKEND_VLLM.equalsIgnoreCase(backend), tsStartNano, responseLog);
                // Ensure flush happens at the end
                os.flush();
            }

            // Response dump: one line per request, assembled from the chunks (see SseResponseLog).
            if (responseLog != null) {
                LOG.debug("LLM Response (streamed): {}", shortenResponseBody(responseLog.toLogString()));
            }

            // --- USAGE STATISTICS ---
            // Capture the monotonic stream end before any post-processing/metrics sampling, so
            // the generated-token span (TG) is not inflated by cache-metric polling or JSONL work.
            final long tsEndNano = System.nanoTime();
            recordUsageStatistics(tsStart, sseDataLines, sampleMetrics,
                    metricsBefore, firstOutputNano, tsStartNano, tsEndNano);

            // Diagnostic: report how the output split over reasoning, answer and tool calls.
            // Confirms whether the backend actually engaged thinking mode. The response-log
            // collector (when active) supplies whole-stream counts, see the method javadoc.
            analyzeReasoningOutput(sseDataLines, responseLog);

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
     * Reads the time-to-first-token from a vLLM metrics node, tolerating the different field
     * names/units that vLLM versions use (explicit milliseconds or bare seconds). Returns
     * {@code 0} when the value is absent or not positive.
     *
     * @param metricsNode vLLM per-request metrics node
     * @return TTFT in milliseconds, or {@code 0}
     */
    private static double readMetricsTtftMs(JsonNode metricsNode) {
        return readMetricsMs(metricsNode, 0,
                new MetricsMsField("time_to_first_token_ms", 1.0),
                new MetricsMsField("time_to_first_token_s", 1000.0),
                new MetricsMsField("time_to_first_token", 1000.0));
    }

    /**
     * Reads the generation duration from a vLLM metrics node, tolerating different field
     * names/units (explicit milliseconds or bare seconds). When no total generation duration is
     * reported directly, it is derived from vLLM's per-output-token figure
     * ({@code time_per_output_token}, seconds per token) times the completion tokens.
     *
     * @param metricsNode       vLLM per-request metrics node
     * @param completionTokens  completion tokens generated (used for the
     *                          {@code time_per_output_token} fallback)
     * @return generation time in milliseconds, or {@code 0} if unavailable
     */
    private static double readMetricsGenMs(JsonNode metricsNode, long completionTokens) {
        double genMs = readMetricsMs(metricsNode, 0,
                new MetricsMsField("generation_time_ms", 1.0),
                new MetricsMsField("generation_time_s", 1000.0),
                new MetricsMsField("generation_time", 1000.0));
        if (genMs <= 0 && metricsNode != null) {
            JsonNode perToken = metricsNode.get("time_per_output_token");
            if (perToken != null && perToken.isNumber() && completionTokens > 0) {
                genMs = perToken.asDouble() * completionTokens * 1000.0;
            }
        }
        return genMs;
    }

    /**
     * Reads a duration from a JSON node under the first matching field name, applying the
     * field's unit-to-milliseconds factor. Returns {@code def} when no candidate matches.
     *
     * @param node       JSON node to read from
     * @param def        default value
     * @param candidates candidate field name + unit-factor pairs
     * @return the duration in milliseconds, or {@code def}
     */
    private static double readMetricsMs(JsonNode node, double def, MetricsMsField... candidates) {
        if (node == null) {
            return def;
        }
        for (MetricsMsField candidate : candidates) {
            JsonNode value = node.get(candidate.key());
            if (value != null && value.isNumber()) {
                return value.asDouble() * candidate.unitFactorToMs();
            }
        }
        return def;
    }

    /**
     * Describes a candidate duration field of a vLLM metrics node together with the factor that
     * converts its stored unit into milliseconds (1.0 for already-ms values, 1000.0 for seconds).
     */
    private record MetricsMsField(String key, double unitFactorToMs) {}

    /**
     * Parses the collected SSE data lines for usage/timing information,
     * computes statistics, logs them, and optionally writes to a JSONL file.
     *
     * <p>The fallback PP/TG split uses the first <em>output</em> token (reasoning or answer),
     * not the first visible answer-content token. For reasoning models the first answer content
     * can arrive only after a long chain-of-thought, which would otherwise be counted as prompt
     * processing and removed from token generation.</p>
     *
     * <p>The raw server {@code usage} node is preserved unchanged. Cache availability is exposed
     * via {@code cachedTokensKnown}, and server-side usage inconsistencies are emitted as
     * {@code usageWarnings} outside that raw node.</p>
     *
     * @param tsStart         timestamp when the request started
     * @param sseDataLines    collected SSE data line JSON strings
     * @param sampleMetrics   whether the upstream vLLM cache metrics were sampled
     * @param metricsBefore   cached-token counter before the request (or -1)
     * @param firstOutputNano {@link System#nanoTime()} of the first output token (reasoning/answer),
     *                        or null/0 if not measurable
     * @param tsStartNano     {@link System#nanoTime()} of the request start
     * @param tsEndNano       {@link System#nanoTime()} of the stream end, or 0 if unknown
     */
    private void recordUsageStatistics(LocalDateTime tsStart,
                                       List<String> sseDataLines, boolean sampleMetrics,
                                       long metricsBefore, AtomicLong firstOutputNano,
                                       long tsStartNano, long tsEndNano) {
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
            LOG.info("No usage statistics from server. tsStart={}, model={}", tsStart, getModelName());
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

            if (usageNode == null || !usageNode.isObject()) {
                LOG.warn("Skipping usage statistics: final usage field is missing or not an object. tsStart={}", tsStart);
                return;
            }

            String model = dataNode.has("model") ? dataNode.get("model").asString() : getModelName();

            long promptTokens = usageNode.has("prompt_tokens") ? usageNode.get("prompt_tokens").asLong() : 0;
            long completionTokens = usageNode.has("completion_tokens") ? usageNode.get("completion_tokens").asLong() : 0;
            long totalTokens = usageNode.has("total_tokens") ? usageNode.get("total_tokens").asLong() : 0;

            // Warnings are written outside the raw server "usage" node. The raw usage block stays
            // byte-for-byte semantically unchanged; these markers only flag upstream inconsistencies.
            List<String> usageWarnings = new ArrayList<>();

            long cachedTokens = 0;
            boolean cachedTokensKnown = false;
            if (usageNode.has("prompt_tokens_details") && usageNode.get("prompt_tokens_details").has("cached_tokens")) {
                JsonNode cachedTokensNode = usageNode.get("prompt_tokens_details").get("cached_tokens");
                if (cachedTokensNode.isNumber()) {
                    long reportedCachedTokens = cachedTokensNode.asLong();
                    if (reportedCachedTokens < 0) {
                        usageWarnings.add("negative_cached_tokens");
                    } else {
                        cachedTokens = reportedCachedTokens;
                        cachedTokensKnown = true;
                    }
                } else {
                    usageWarnings.add("cached_tokens_not_numeric");
                }
            }

            // If metric sampling is enabled, the upstream vLLM /metrics counter delta is a fallback
            // cache indicator for streams that do not (or not yet) report prompt_tokens_details.
            // Explicit per-request usage details take precedence over the global /metrics counter.
            if (!cachedTokensKnown && sampleMetrics && metricsBefore >= 0) {
                long metricsAfter = readVllmCachedTokensMetric();
                if (metricsAfter >= 0) {
                    long sampledCachedTokens = metricsAfter - metricsBefore;
                    if (sampledCachedTokens >= 0) {
                        cachedTokens = sampledCachedTokens;
                        cachedTokensKnown = true;
                    } else {
                        usageWarnings.add("negative_sampled_cached_token_delta");
                    }
                }
            }

            if (promptTokens < 0) {
                usageWarnings.add("negative_prompt_tokens");
            }
            if (completionTokens < 0) {
                usageWarnings.add("negative_completion_tokens");
            }
            if (totalTokens < 0) {
                usageWarnings.add("negative_total_tokens");
            }

            if (!usageNode.has("prompt_tokens")) {
                usageWarnings.add("missing_prompt_tokens");
            }
            if (!usageNode.has("completion_tokens")) {
                usageWarnings.add("missing_completion_tokens");
            }
            if (!usageNode.has("total_tokens")) {
                usageWarnings.add("missing_total_tokens");
            } else if (promptTokens >= 0 && completionTokens >= 0 && totalTokens >= 0
                    && totalTokens != promptTokens + completionTokens) {
                usageWarnings.add("total_tokens_mismatch");
            }

            if (cachedTokensKnown && cachedTokens < 0) {
                usageWarnings.add("negative_cached_tokens");
            }
            if (cachedTokensKnown && promptTokens >= 0 && cachedTokens > promptTokens) {
                usageWarnings.add("cached_tokens_gt_prompt_tokens");
            }

            JsonNode completionDetails = usageNode.get("completion_tokens_details");
            if (completionDetails != null && completionDetails.isObject()
                    && completionDetails.has("reasoning_tokens")) {
                JsonNode reasoningTokensNode = completionDetails.get("reasoning_tokens");
                if (reasoningTokensNode.isNumber()) {
                    long reasoningTokens = reasoningTokensNode.asLong();
                    if (reasoningTokens < 0) {
                        usageWarnings.add("negative_reasoning_tokens");
                    }
                    if (completionTokens >= 0 && reasoningTokens > completionTokens) {
                        usageWarnings.add("reasoning_tokens_gt_completion_tokens");
                    }
                } else {
                    usageWarnings.add("reasoning_tokens_not_numeric");
                }
            }

            if (!usageWarnings.isEmpty()) {
                LOG.warn("Server usage inconsistencies: warnings={}, promptTokens={}, completionTokens={}, "
                                + "totalTokens={}, cachedTokens={}, cachedTokensKnown={}, tsStart={}",
                        usageWarnings, promptTokens, completionTokens, totalTokens, cachedTokens,
                        cachedTokensKnown, tsStart);
            }

            // Client-side time-to-first-output-token (wall-clock, upper bound of the server
            // TTFT). This includes reasoning output, not only visible answer content. 0 when not
            // measurable (e.g. non-streaming without a streamed SSE feed).
            long ttftClientMs = (firstOutputNano != null && firstOutputNano.get() > 0L && tsStartNano > 0L)
                    ? millisElapsed(tsStartNano, firstOutputNano.get()) : 0L;

            long millisPP;
            long millisTG;
            float ppTPS;
            float tgTPS;
            // Indicates whether the reported rates/timings had to be estimated (wall-clock,
            // heuristics or synthesized fallbacks) instead of being taken from authoritative
            // server timings/metrics. Helps consumers judge the trustworthiness of a record.
            boolean estimated;

            if (timingsNode != null) {
                // llama.cpp provides detailed timings in the response.
                double promptMs = timingsNode.has("prompt_ms") ? timingsNode.get("prompt_ms").asDouble() : 0;
                double predictedMs = timingsNode.has("predicted_ms") ? timingsNode.get("predicted_ms").asDouble() : 0;
                millisPP = Math.round(promptMs);
                millisTG = Math.round(predictedMs);

                double serverPpTPS = timingsNode.has("prompt_per_second") ? timingsNode.get("prompt_per_second").asDouble() : 0;
                double serverTgTPS = timingsNode.has("predicted_per_second") ? timingsNode.get("predicted_per_second").asDouble() : 0;

                // Server-reported rates: llama.cpp's prompt_per_second is based on ALL prompt
                // tokens (uncached share). ppUncachedTPS below is only meaningful if cached-token
                // usage is actually known.
                ppTPS = (float) serverPpTPS;
                tgTPS = (float) serverTgTPS;
                estimated = false;

                LOG.info("Usage stats (llama.cpp): promptTokens={}, completionTokens={}, totalTokens={}, cachedTokens={}, "
                                + "cachedTokensKnown={}, millisPP={}, millisTG={}, ppTPS={} (server), "
                                + "ppUncachedTPS={} (cache-corrected if known), tgTPS={} (server), ttftClientMs={}, estimated={}",
                        promptTokens, completionTokens, totalTokens, cachedTokens, cachedTokensKnown,
                        millisPP, millisTG, ppTPS,
                        cachedTokensKnown ? computePpUncachedTPS(millisPP, promptTokens, cachedTokens) : 0f,
                        tgTPS, ttftClientMs, estimated);

            } else if (metricsNode != null && readMetricsTtftMs(metricsNode) > 0) {
                // vLLM provides authoritative server-side timing metrics (with
                // --enable-per-request-metrics / --enable-prompt-tokens-details). They are far
                // more accurate than the wall-clock split below, so the rate heuristic must not
                // be applied in this case. Field names/units vary across vLLM versions; the
                // helpers below read them tolerantly and normalize everything to milliseconds.
                millisPP = Math.round(readMetricsTtftMs(metricsNode));
                millisTG = Math.round(readMetricsGenMs(metricsNode, completionTokens));

                // Prompt rate: if cached-token usage is known, only the tokens that actually had
                // to be computed count. If it is unknown, do not present the raw prompt rate as a
                // cache-corrected uncached rate; keep it as a total-prompt estimate and expose
                // cachedTokensKnown=false to consumers.
                long ppRateTokens = cachedTokensKnown
                        ? Math.max(0, promptTokens - cachedTokens)
                        : Math.max(0, promptTokens);
                ppTPS = (millisPP > 0 && ppRateTokens > 0)
                        ? (ppRateTokens * 1000f / millisPP) : 0;

                double serverTgTPS = metricsNode.has("tokens_per_second")
                        ? metricsNode.get("tokens_per_second").asDouble() : 0;
                tgTPS = (float) serverTgTPS;
                // The generation rate/ time may be missing or zero; fall back to the raw values
                // and flag the record as estimated in that case.
                boolean genEstimated = tgTPS <= 0;
                if (genEstimated && millisTG > 0 && completionTokens > 0) {
                    tgTPS = completionTokens * 1000f / millisTG;
                }
                estimated = genEstimated;

                long ttftServerMs = millisPP;
                long ttftDeltaMs = (ttftClientMs > 0) ? (ttftClientMs - ttftServerMs) : 0;
                LOG.info("Usage stats (vLLM metrics): promptTokens={}, completionTokens={}, totalTokens={}, cachedTokens={}, "
                                + "cachedTokensKnown={}, millisPP={}, millisTG={}, ppTPS={} (cache-corrected if known), "
                                + "tgTPS={}, ttftServerMs={}, ttftClientMs={}, ttftDeltaMs={}, estimated={}",
                        promptTokens, completionTokens, totalTokens, cachedTokens, cachedTokensKnown,
                        millisPP, millisTG, ppTPS, tgTPS,
                        ttftServerMs, ttftClientMs, ttftDeltaMs, estimated);

            } else if (firstOutputNano != null && firstOutputNano.get() > 0L && tsStartNano > 0L) {
                // No server-side timings/metrics, but the proxy saw the first output token.
                // Use that first output token as the PP/TG boundary: for reasoning models it is
                // the first reasoning delta, while the first visible answer content may arrive much
                // later. Using firstContentTime here would count thinking time as prompt processing
                // and leave almost no time in TG for the reasoning tokens.
                // This is still only a client-side wall-clock estimate, therefore flagged.
                long firstOutputTokenNano = firstOutputNano.get();
                long streamEndNano = tsEndNano > 0L ? tsEndNano : System.nanoTime();
                millisPP = millisElapsed(tsStartNano, firstOutputTokenNano);
                millisTG = millisElapsed(firstOutputTokenNano, streamEndNano);

                long ppRateTokens = cachedTokensKnown
                        ? Math.max(0, promptTokens - cachedTokens)
                        : Math.max(0, promptTokens);
                float computedPpTPS = (millisPP > 0 && ppRateTokens > 0) ? (ppRateTokens * 1000f / millisPP) : 0;
                float computedTgTPS = (millisTG > 0 && completionTokens > 0) ? (completionTokens * 1000f / millisTG) : 0;
                ppTPS = computedPpTPS;
                tgTPS = computedTgTPS;
                estimated = true;

                LOG.info("Usage stats (client wall-clock, first output token): promptTokens={}, completionTokens={}, "
                                + "totalTokens={}, cachedTokens={}, cachedTokensKnown={}, millisPP={}, millisTG={}, "
                                + "ppTPS={}, tgTPS={}, ttftClientMs={}, estimated={}",
                        promptTokens, completionTokens, totalTokens, cachedTokens, cachedTokensKnown,
                        millisPP, millisTG, ppTPS, tgTPS, ttftClientMs, estimated);

            } else {
                // No server-side timing and no client-measured first output token (e.g. buffered
                // non-streaming, or a stream that did not expose a recognizable output delta).
                // There is no reliable way to split the request into prompt- and generation-time.
                // The former fixed ppTPS = 5 * tgTPS heuristic fabricated misleading values and has
                // been removed; the pp/tg rates are reported as unknown (0) and flagged estimated.
                long totalMillis = Duration.between(tsStart, LocalDateTime.now()).toMillis();
                millisPP = 0;
                millisTG = 0;
                ppTPS = 0;
                tgTPS = 0;
                estimated = true;

                LOG.info("Usage stats (no server metrics, no client output token): promptTokens={}, "
                                + "completionTokens={}, totalTokens={}, cachedTokens={}, cachedTokensKnown={}, "
                                + "totalMillis={}, millisPP={}, millisTG={}, ppTPS={}, tgTPS={}, "
                                + "ttftClientMs={}, estimated={}",
                        promptTokens, completionTokens, totalTokens, cachedTokens, cachedTokensKnown,
                        totalMillis, millisPP, millisTG, ppTPS, tgTPS, ttftClientMs, estimated);
            }

            // Effective prompt-processing rate for the tokens that actually had to be computed.
            // This is only valid when cached-token usage is known; otherwise reporting a raw
            // prompt rate as ppUncachedTPS would silently assume cachedTokens=0.
            float ppUncachedTPS = cachedTokensKnown
                    ? computePpUncachedTPS(millisPP, promptTokens, cachedTokens)
                    : 0f;

            LlmUsage usage = new LlmUsage(tsStart, millisPP, millisTG, model,
                    promptTokens, completionTokens, totalTokens, cachedTokens, cachedTokensKnown,
                    ppUncachedTPS, ppTPS, tgTPS, estimated, ttftClientMs, List.copyOf(usageWarnings));
            usages.add(usage);

            // Write to JSONL file if configured
            writeStatsJsonl(usage, usageNode);

        } catch (RuntimeException e) {
            LOG.warn("Failed to parse usage statistics from SSE data: {}", e.getMessage(), e);
        }
    }

    /**
     * Diagnostic: reports how the generated output is distributed over chain-of-thought, answer
     * content and tool-call arguments.
     *
     * <p>Reasoning arrives in the {@code reasoning_content} field at the same level as
     * {@code content} (vLLM spells it {@code reasoning}); the counts make it possible to confirm
     * whether the backend actually engaged thinking mode instead of guessing from the token count
     * alone.</p>
     *
     * <p>The counts prefer {@link SseResponseLog}, which sees <em>every</em> chunk. That matters:
     * {@code sseDataLines} is filled for usage extraction only and keeps just the last chunks
     * before {@code [DONE]}, so scanning the tail alone would report {@code reasoningChars=0} for
     * a long chain of thought that simply streamed earlier. Without the collector the tail scan
     * remains as a fallback and the log line says so explicitly rather than implying a zero.</p>
     *
     * <p>Tool-call arguments are counted too, because a turn ending in
     * {@code finish_reason=tool_calls} produces no answer content by design; without
     * {@code toolCallChars} and {@code finishReason} such a turn is indistinguishable from an
     * empty or failed answer.</p>
     *
     * @param sseDataLines the SSE data lines kept for usage extraction (tail of the stream)
     * @param responseLog  whole-stream collector, or null when {@value #PROP_LOG_RESPONSES} is off
     */
    private void analyzeReasoningOutput(List<String> sseDataLines, SseResponseLog responseLog) {
        if (responseLog == null && (sseDataLines == null || sseDataLines.isEmpty())) {
            return;
        }
        final long reasoningChars;
        final long contentChars;
        final long toolCallChars;
        final Object finishReason;
        final Object chunkCount;
        final String scope;
        if (responseLog != null) {
            reasoningChars = responseLog.reasoningCharsTotal();
            contentChars = responseLog.contentCharsTotal();
            toolCallChars = responseLog.toolCallCharsTotal();
            String reason = responseLog.finishReasonValue();
            finishReason = reason != null ? reason : "n/a";
            chunkCount = responseLog.chunkCount();
            scope = "";
        } else {
            long tailReasoning = 0L;
            long tailContent = 0L;
            long tailToolCalls = 0L;
            String tailFinishReason = null;
            for (String line : sseDataLines) {
                try {
                    JsonNode node = jsonMapper.readTree(line);
                    JsonNode choice = !node.path("choices").isEmpty() ? node.path("choices").get(0) : null;
                    if (choice == null) {
                        continue;
                    }
                    JsonNode reasonNode = choice.get("finish_reason");
                    if (reasonNode != null && reasonNode.isString()) {
                        tailFinishReason = reasonNode.asString();
                    }
                    JsonNode delta = choice.get("delta");
                    if (delta != null) {
                        tailReasoning += textLength(delta, "reasoning_content")
                                + textLength(delta, "reasoning");
                        tailContent += textLength(delta, "content");
                        JsonNode toolCalls = delta.get("tool_calls");
                        if (toolCalls != null && toolCalls.isArray()) {
                            for (int i = 0; i < toolCalls.size(); i++) {
                                JsonNode entry = toolCalls.get(i);
                                tailToolCalls += textLength(entry == null ? null : entry.get("function"),
                                        "arguments");
                            }
                        }
                    }
                } catch (Exception e) {
                    // ignore non-JSON / parse errors in diagnostic
                }
            }
            reasoningChars = tailReasoning;
            contentChars = tailContent;
            toolCallChars = tailToolCalls;
            finishReason = tailFinishReason != null ? tailFinishReason : "n/a";
            chunkCount = "n/a";
            scope = " (tail-only scan, " + PROP_LOG_RESPONSES + " off)";
        }
        LOG.info("Reasoning-output analysis: reasoningChars={}, contentChars={}, toolCallChars={},"
                        + " finishReason={}, chunks={}, usageSseLines={}{}",
                reasoningChars, contentChars, toolCallChars, finishReason, chunkCount,
                sseDataLines == null ? 0 : sseDataLines.size(), scope);
    }

    /**
     * @param node  JSON object to read from, may be null
     * @param field field name
     * @return length of the string value under {@code field}, 0 when absent or not a string
     */
    private static int textLength(JsonNode node, String field) {
        if (node == null) {
            return 0;
        }
        JsonNode value = node.get(field);
        return (value != null && value.isString()) ? value.asString().length() : 0;
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
            record.put("cachedTokensKnown", usage.cachedTokensKnown());
            record.put("ppUncachedTPS", usage.ppUncachedTPS());
            record.put("ppTPS", usage.ppTPS());
            record.put("tgTPS", usage.tgTPS());
            record.put("estimated", usage.estimated());
            record.put("ttftClientMs", usage.ttftClientMs());

            ArrayNode warningsNode = jsonMapper.createArrayNode();
            if (usage.usageWarnings() != null) {
                for (String warning : usage.usageWarnings()) {
                    if (warning != null && !warning.isBlank()) {
                        warningsNode.add(warning);
                    }
                }
            }
            record.set("usageWarnings", warningsNode);

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

        // Cache-corrected prompt totals must only include requests where cached-token usage was
        // actually reported/sampled. Requests with cachedTokensKnown=false are reported separately;
        // otherwise unknown cached tokens would silently be treated as uncached tokens.
        long totalPromptTokensWithCacheInfo = usages.stream()
                .filter(LlmUsage::cachedTokensKnown)
                .mapToLong(LlmUsage::promptTokens)
                .sum();
        long totalCachedTokensKnown = usages.stream()
                .filter(LlmUsage::cachedTokensKnown)
                .mapToLong(LlmUsage::cachedTokens)
                .sum();
        long totalUncachedTokensKnownCache = Math.max(0, totalPromptTokensWithCacheInfo - totalCachedTokensKnown);
        long totalPromptTokensCacheUnknown = Math.max(0, totalPromptTokens - totalPromptTokensWithCacheInfo);

        LOG.info("Shutdown: LLM usage statistics - #Requests={}, #TokenIn={}, #TokenInCachedKnown={}, "
                        + "#TokenInUncachedKnownCache={}, #TokenInCacheUnknown={}, #TokenOut={}, #TotalTokens={}",
                requestCount, totalPromptTokens, totalCachedTokensKnown, totalUncachedTokensKnownCache,
                totalPromptTokensCacheUnknown, totalCompletionTokens, totalTokens);
    }

    /**
     * Legacy method for non-streaming requests.
     * Buffers the response to allow post-processing (SSE wrapping).
     */
    private String forwardRequestToLLMBuffered(JsonNode requestNode, String cookie, String xSessionId,
                                               String requestPath) throws IOException {
        // Same seam as the streaming path, buffered variant (docs/webui.md R4).
        ObjectNode llmRequest = requestPreparer().prepare(requestNode,
                ChatRequestPreparer.Mode.BUFFERED);

        String requestOut = jsonMapper.writeValueAsString(llmRequest);

        String targetUrl = getModelUrl();
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
        if (xSessionId != null) {
            connection.setRequestProperty("x-session-id", xSessionId);
        }

        final LocalDateTime tsStart = LocalDateTime.now();
        // When enabled, sample the upstream vLLM cached-tokens counter before the request, so the
        // delta after the response shows how many prompt tokens the prefix cache served. The
        // streaming path did this already; the buffered path needs it too, because a forked child's
        // whole turn lands here (docs/webui.md F11: the usage record is the only instrument the
        // fork's premise is measured with) and vLLM's non-streaming usage node reports
        // prompt_tokens_details only when the server was started with --enable-prompt-tokens-details.
        boolean sampleMetrics = BACKEND_VLLM.equalsIgnoreCase(backend) && fetchMetrics;
        long metricsBefore = sampleMetrics ? readVllmCachedTokensMetric() : -1;
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

        // Response dump (webui.logResponses): the buffered body as the client receives it.
        if (responseLogEnabled()) {
            LOG.debug("LLM Response (buffered): {}", shortenResponseBody(responseBody));
        }

        // Try to parse usage from the non-streaming JSON response
        try {
            JsonNode responseNode = jsonMapper.readTree(responseBody);
            if (responseNode.has("usage")) {
                List<String> dataLines = new ArrayList<>();
                dataLines.add(jsonMapper.writeValueAsString(responseNode));
                // No per-chunk timing is available here, hence firstOutputNano/tsStartNano/tsEndNano
                // stay null/0 (ttftClientMs=0). The cached tokens come from the response's own
                // prompt_tokens_details when vLLM was asked for them, and otherwise from the
                // /metrics delta sampled around this request.
                recordUsageStatistics(tsStart, dataLines, sampleMetrics, metricsBefore, null, 0L, 0L);
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
     * Also collects SSE data lines for usage statistics extraction and records the timestamp of
     * the first output token (reasoning or answer). The first visible answer-content token is
     * recorded separately as a diagnostic; the fallback PP/TG split uses the first output token.
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
     * mirroring the {@code timings} shape llama.cpp sends. Implausible rates are screened per
     * rate, so a prompt rate beyond the ceiling no longer hides the generation rate the Web UI
     * renders - see {@link #enrichVllmSseLine}.</p>
     *
     * @param in               source input stream
     * @param out              target output stream
     * @param sseDataLines     collector for SSE data line JSON strings (may be null)
     * @param firstContentTime atomic reference to store the timestamp of the first answer-content token;
     *                         kept for diagnostics and not used as the fallback PP/TG boundary (may be null)
     * @param firstOutputNano  atomic long to store the {@link System#nanoTime()} of the first output
     *                         token (reasoning or answer content), used for the client TTFT and the
     *                         fallback PP/TG split (may be null)
     * @param rewriteFlashReasoning whether to apply the vLLM-specific SSE enrichment (reasoning rewrite + live timings)
     * @param tsStartNano      {@link System#nanoTime()} of the request start, reference for the prompt rate
     * <p>Independently of the backend, the {@value #PROP_SUPPRESS_TOOLCALL_INTERLEAVE} switch
     * removes blank answer content that arrives between two tool calls. The DEBUG lines below
     * ({@code ResponseDump-debug}, {@code SSE line (early)}) intentionally still show the
     * unfiltered payload, so the log keeps describing what the backend sent; the filtered line is
     * logged separately and the number of removals is summarized at the end of the stream.</p>
     *
     * @param responseLog      optional collector for the {@value #PROP_LOG_RESPONSES} DEBUG dump;
     *                         {@code null} disables the extra bookkeeping
     * @throws IOException if an I/O error occurs
     */
    private void copyStream(InputStream in, OutputStream out, List<String> sseDataLines,
                            AtomicReference<LocalDateTime> firstContentTime,
                            AtomicLong firstOutputNano,
                            boolean rewriteFlashReasoning, long tsStartNano,
                            SseResponseLog responseLog) throws IOException {
        // Buffer for a partial SSE data line that was split across chunk boundaries.
        StringBuilder pendingData = new StringBuilder();
        byte[] buffer = new byte[4096];
        int bytesRead;
        int lineCount = 0;
        List<String> lastTwoLines = new ArrayList<>();
        // vLLM per-chunk timing state (only consumed while rewriteFlashReasoning is active).
        VllmTimingState vllmTiming = new VllmTimingState();
        // State of the blank-content-between-tool-calls filter (only used when the switch is on).
        ToolCallInterleaveState interleaveState = new ToolCallInterleaveState();

        while ((bytesRead = in.read(buffer)) != -1) {
            String chunk = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);

            // Collect SSE data lines for usage extraction and detect the first output token
            // (reasoning or answer) for the client TTFT / fallback PP/TG split. The answer-content
            // token is also recorded for diagnostics only.
            if (sseDataLines != null || firstContentTime != null || firstOutputNano != null
                    || rewriteFlashReasoning || responseLog != null || suppressToolCallInterleave) {
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
                        // Record the client-side timestamps of the first output token (reasoning or
                        // answer) and of the first answer-content token, unless already captured.
                        // DONE-lines never carry a delta, so they are skipped here.
                        if (!isDoneLine) {
                            recordFirstTokenTiming(dataLine, firstContentTime, firstOutputNano);
                            // Response dump (webui.logResponses): the raw payload, so the log shows
                            // what the backend sent - not the "timings" synthesized for the client.
                            if (responseLog != null) {
                                responseLog.accept(dataLine);
                            }
                        }
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
                        //LOG.debug("ResponseDump-debug: {}", clientLine);
                        if (lineCount < 2) {
                            LOG.debug("SSE line (early): {}", clientLine);
                        } else if (!isDoneLine) {
                            lastTwoLines.add(collectedLine);
                            if (lastTwoLines.size() > 2) {
                                lastTwoLines.remove(0);
                            }
                        }

                        // Optional workaround for the Web UI tool-call assembler: drop a blank
                        // content delta that lands between two tool calls. Applied after the DEBUG
                        // lines above, which keep describing the payload as the backend sent it.
                        if (!isDoneLine) {
                            String filteredLine = stripToolCallInterleaveContent(clientLine, interleaveState);
                            if (!filteredLine.equals(clientLine)) {
                                LOG.debug("SSE line filtered for client (blank content removed): {}",
                                        filteredLine);
                                clientLine = filteredLine;
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

        if (interleaveState.suppressedDeltas > 0) {
            LOG.info("Suppressed {} blank content delta(s) ({} chars) between tool calls before"
                            + " forwarding to the Web UI (model='{}', {}=true).",
                    interleaveState.suppressedDeltas, interleaveState.suppressedChars,
                    getModelName(), PROP_SUPPRESS_TOOLCALL_INTERLEAVE);
        }

        // Only nonzero while the vLLM enrichment ran: both counters say which fields the Web UI did
        // not see. The first one is cosmetic (it drops the prompt rate), the second one removes the
        // live token/s of that chunk and is worth investigating when it is not negligible.
        if (vllmTiming.implausiblePromptRates > 0 || vllmTiming.implausibleGenerationRates > 0) {
            LOG.info("Skipped synthesized timings fields above {} token/s (model='{}'): {} chunk(s)"
                            + " without prompt_per_second, {} chunk(s) without timings. The prompt rate"
                            + " is screened without the prefix-cache tokens; only the second counter"
                            + " affects the live token/s of the Web UI.",
                    (long) MAX_PLAUSIBLE_TOKENS_PER_SECOND, getModelName(),
                    vllmTiming.implausiblePromptRates, vllmTiming.implausibleGenerationRates);
        }

        // The Web UI renders a token/s figure only for chunks carrying a timings node, so a stream
        // without a single one needs an explanation of its own: either the backend never reported
        // per-chunk counters (stream_options.continuous_usage_stats not in effect at the endpoint
        // the proxy talks to) or every rate was screened out above.
        if (rewriteFlashReasoning && vllmTiming.timingsChunks == 0) {
            LOG.info("No timings synthesized for the Web UI in this vLLM stream: {} data line(s), {}"
                            + " carrying usage counters, {} skipped as implausible (model='{}'). Without"
                            + " per-chunk usage there is nothing to compute a live token/s rate from.",
                    lineCount, vllmTiming.usageChunks, vllmTiming.implausibleGenerationRates,
                    getModelName());
        } else if (rewriteFlashReasoning) {
            LOG.debug("Synthesized timings in {} of {} vLLM data line(s) ({} carried usage counters,"
                            + " model='{}').", vllmTiming.timingsChunks, lineCount,
                    vllmTiming.usageChunks, getModelName());
        }
    }

    /**
     * Removes blank answer content that a model emits <em>between two tool calls</em>.
     *
     * <p>Workaround for the Web UI tool-call assembler, which treats <em>any</em>
     * {@code choices[].delta.content} as the end of a tool-call batch and adds the number of
     * calls collected so far as an index offset to the following batch. A model/backend pair that
     * terminates every tool-call block with a newline token therefore makes the assembler write
     * the next call to {@code index + offset} and pad the list in between with an entry that has
     * neither id, name nor arguments. The UI persists that phantom call into the chat history,
     * where it survives as a tool call that was never made and that no tool result answers.</p>
     *
     * <p>Conservative by construction: only a {@code content} field whose text is blank is
     * removed, only after a {@code tool_calls} delta appeared in the same stream, and only in the
     * line forwarded to the client. Non-blank content, reasoning deltas, everything before the
     * first tool call and any payload that cannot be parsed are forwarded unchanged. The remainder
     * of a filtered chunk is kept, so a {@code finish_reason} or the synthesized {@code timings}
     * node travelling on the same line survives.</p>
     *
     * <p>Note that the underlying assembler bug stays untouched: a model that writes <em>real</em>
     * text between two parallel tool calls still triggers the offset mix-up. Fixing that needs the
     * Web UI change (never pad an index beyond the end of the collected list).</p>
     *
     * @param clientLine the line about to be forwarded (already enriched, or {@code [DONE]})
     * @param state      bookkeeping of the current {@link #copyStream} invocation
     * @return the line to forward; identical to {@code clientLine} unless content was removed
     */
    String stripToolCallInterleaveContent(String clientLine, ToolCallInterleaveState state) {
        if (!suppressToolCallInterleave || state == null || clientLine == null
                || clientLine.isEmpty() || "[DONE]".equals(clientLine)) {
            return clientLine;
        }
        JsonNode root;
        try {
            root = jsonMapper.readTree(clientLine);
        } catch (RuntimeException e) {
            // A payload that cannot be parsed is never dropped: forward it and let the client decide.
            return clientLine;
        }
        JsonNode delta = root.path("choices").path(0).path("delta");
        if (!(delta instanceof ObjectNode deltaObject)) {
            return clientLine;
        }

        JsonNode toolCalls = deltaObject.get("tool_calls");
        if (toolCalls != null && toolCalls.isArray() && toolCalls.size() > 0) {
            // A batch is open as soon as an earlier chunk of this stream carried a tool call.
            boolean batchWasOpen = state.maxToolCallIndex >= 0;
            for (JsonNode toolCall : toolCalls) {
                int index = toolCall.path("index").asInt(-1);
                if (index > state.maxToolCallIndex) {
                    state.maxToolCallIndex = index;
                }
            }
            // Some backends put the tool calls and the stray newline into the same chunk; that
            // content closes the batch in the Web UI just the same, so it goes as well.
            return batchWasOpen ? dropBlankContent(root, deltaObject, state, clientLine) : clientLine;
        }

        // Without an open batch the newline belongs to the answer rather than to a tool-call
        // interleave, so the switch is deliberately inactive before the first tool call.
        if (state.maxToolCallIndex < 0) {
            return clientLine;
        }
        return dropBlankContent(root, deltaObject, state, clientLine);
    }

    /**
     * Removes a blank {@code content} field of a delta from the line and counts the removal. A
     * delta without a content field, or with one that is not blank, is left alone.
     *
     * @param root         chunk the delta belongs to (mutated when the content is removed)
     * @param delta        delta object of the chunk
     * @param state        bookkeeping of the current {@link #copyStream} invocation
     * @param fallbackLine line to return when nothing is removed or serialization fails
     * @return the re-serialized line, or {@code fallbackLine}
     */
    private String dropBlankContent(JsonNode root, ObjectNode delta, ToolCallInterleaveState state,
                                    String fallbackLine) {
        JsonNode contentNode = delta.get("content");
        if (contentNode == null || !contentNode.isString()) {
            return fallbackLine;
        }
        String content = contentNode.asString("");
        if (!content.isBlank()) {
            // Real answer text is never touched.
            return fallbackLine;
        }

        delta.remove("content");
        state.suppressedDeltas++;
        state.suppressedChars += content.length();
        try {
            return jsonMapper.writeValueAsString(root);
        } catch (RuntimeException e) {
            // Serialization failed after the removal: fall back to the untouched line.
            return fallbackLine;
        }
    }

    /**
     * Records the client-side timestamps of the first output token and of the first answer-content
     * token of a single SSE data payload, unless they are already captured.
     *
     * <p>The first <em>output</em> token is the first delta that carries either reasoning
     * ({@code reasoning}/{@code reasoning_content}) or answer content — it is the client-side
     * counterpart of the server {@code time_to_first_token} and the fallback PP/TG boundary.
     * The first <em>answer-content</em> token is the first delta carrying a non-null {@code content};
     * it is kept separately for diagnostics only. For a reasoning model the output token therefore
     * arrives before the answer-content token.
     * Values that are present but {@code null} (OpenAI streams often carry {@code "content": null}
     * while thinking) do not count.</p>
     *
     * @param dataLine          raw SSE data payload
     * @param firstContentTime  atomic reference for the first answer-content token (may be null)
     * @param firstOutputNano   atomic long for the first output token as {@link System#nanoTime()}
     *                          value (may be null)
     */
    private void recordFirstTokenTiming(String dataLine,
                                        AtomicReference<LocalDateTime> firstContentTime,
                                        AtomicLong firstOutputNano) {
        boolean needOutput = firstOutputNano != null && firstOutputNano.get() == 0L;
        boolean needContent = firstContentTime != null && firstContentTime.get() == null;
        if (!needOutput && !needContent) {
            return;
        }
        JsonNode node;
        try {
            node = jsonMapper.readTree(dataLine);
        } catch (RuntimeException e) {
            // payload is not decodable JSON (e.g. a keep-alive frame); nothing to record.
            return;
        }
        JsonNode choices = (node == null) ? null : node.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            return;
        }
        JsonNode delta = choices.get(0).get("delta");
        if (delta == null || !delta.isObject()) {
            return;
        }
        boolean hasReasoning = isNonNullString(delta, "reasoning")
                || isNonNullString(delta, "reasoning_content");
        boolean hasAnswerContent = isNonNullString(delta, "content");
        if (needOutput && (hasReasoning || hasAnswerContent)) {
            firstOutputNano.compareAndExchange(0L, System.nanoTime());
        }
        if (needContent && hasAnswerContent) {
            firstContentTime.compareAndExchange(null, LocalDateTime.now());
        }
    }

    /**
     * Returns whether the given JSON object has a string value (non-null) under the field name.
     *
     * @param node JSON object to inspect
     * @param field field name
     * @return {@code true} if the field exists and holds a non-null string
     */
    private static boolean isNonNullString(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() && !value.asString().isEmpty();
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
     * Both rates are screened against {@link #MAX_PLAUSIBLE_TOKENS_PER_SECOND} <em>separately</em>,
     * because they protect different fields: an implausible <em>generation</em> rate skips the whole
     * node (the Web UI recomputes the live rate from {@code predicted_n}/{@code predicted_ms}, so the
     * counters alone would present the artefact as a believable speed, and a node without them would
     * reset the counters the client already shows), while an implausible <em>prompt</em> rate only
     * drops {@code prompt_per_second} and leaves the counters plus the generation rate in place. The
     * prompt rate is judged on the tokens the prefix cache did not serve - its reported value stays
     * the llama.cpp-style rate over all prompt tokens - since a warm cache drives the raw figure far
     * past the ceiling without being an artefact. The reasoning rewrite of
     * {@link #rewriteReasoningField} is still applied in the same parse pass.</p>
     *
     * <p>Package-private instead of private so the unit test (same package) can drive the
     * enrichment without a running backend.</p>
     *
     * @param dataLine    the raw SSE data payload
     * @param tsStartNano {@link System#nanoTime()} of the request start (prompt rate reference)
     * @param timing      per-stream timing state (generation-start time, suppression counters)
     * @return the enriched data payload, or the original line if it is not a JSON object
     */
    String enrichVllmSseLine(String dataLine, long tsStartNano, VllmTimingState timing) {
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
            timing.usageChunks++;
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

            // Generation rate: the Web UI derives its live token/s figure from predicted_n and
            // predicted_ms, so an artefact rate cannot be filtered by dropping the rate field alone.
            // Without the node the client simply keeps the state of the previous chunk, which is
            // better than a partial node that zeroes the counters of the status line.
            if (predictedPerSecond > MAX_PLAUSIBLE_TOKENS_PER_SECOND) {
                timing.implausibleGenerationRates++;
            } else {
                ObjectNode timings = jsonMapper.createObjectNode();
                timings.put("prompt_n", promptN);
                timings.put("prompt_ms", promptMs);

                // Prompt rate: judged without the tokens the prefix cache served. The reported value
                // keeps the llama.cpp meaning (all prompt tokens over the prompt time), only the
                // plausibility test is corrected - otherwise a cache-warm prompt (15k tokens in
                // 370 ms = 40k token/s) would be mistaken for a timing artefact.
                long cachedN = cachedPromptTokens(usageNode, promptN);
                double promptRateCandidate = cachedN >= 0L && promptMs > 0
                        ? (promptN - cachedN) * 1000.0 / promptMs
                        : promptPerSecond;
                if (promptRateCandidate <= MAX_PLAUSIBLE_TOKENS_PER_SECOND) {
                    timings.put("prompt_per_second", promptPerSecond);
                } else {
                    // The counters stay, they feed the context display of the Web UI.
                    timing.implausiblePromptRates++;
                }

                timings.put("predicted_n", completionN);
                timings.put("predicted_ms", genMs);
                timings.put("predicted_per_token_ms",
                        genMs > 0 && completionN > 0 ? (double) genMs / completionN : 0.0);
                timings.put("predicted_per_second", predictedPerSecond);

                ((ObjectNode) node).set("timings", timings);
                timing.timingsChunks++;
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
     * Reads the prompt tokens served from the prefix cache out of a usage node
     * ({@code usage.prompt_tokens_details.cached_tokens}, reported by vLLM).
     *
     * <p>The value is only used to screen the synthesized prompt rate, never reported on its own:
     * {@code timings.prompt_n} keeps counting all prompt tokens, which is what the Web UI expects
     * for its context size (it adds its own {@code cache_n}, which stays unreported here).</p>
     *
     * @param usageNode the {@code usage} node of the chunk
     * @param promptN   total prompt tokens of the same node, used as a sanity bound
     * @return the cached prompt tokens or {@code -1} if unreported, non-numeric or out of range
     */
    private static long cachedPromptTokens(JsonNode usageNode, long promptN) {
        JsonNode cachedNode = usageNode.path("prompt_tokens_details").path("cached_tokens");
        if (!cachedNode.isNumber()) {
            return -1L;
        }
        long cachedTokens = cachedNode.asLong();
        return (cachedTokens >= 0L && cachedTokens <= promptN) ? cachedTokens : -1L;
    }

    /**
     * Per-stream timing state for synthesizing llama.cpp-style {@code timings} on the vLLM path.
     * Lives for the duration of a single {@link #copyStream} invocation.
     *
     * <p>Package-private instead of private so the unit test (same package) can drive
     * {@link #enrichVllmSseLine} directly.</p>
     */
    static final class VllmTimingState {
        /** nanoTime of the first chunk carrying a completion token (generation start). */
        long genStartNano = 0L;
        /** Whether the generation phase has started. */
        boolean genStarted = false;
        /** Data lines that carried a {@code usage} object with completion counters. */
        int usageChunks = 0;
        /** Data lines whose synthesized {@code timings} node was forwarded to the client. */
        int timingsChunks = 0;
        /** Chunks whose synthesized {@code prompt_per_second} was dropped as implausible. */
        int implausiblePromptRates = 0;
        /** Chunks whose synthesized {@code timings} node was skipped as implausible. */
        int implausibleGenerationRates = 0;
    }

    /**
     * Per-stream bookkeeping for {@link #stripToolCallInterleaveContent}. Lives for the duration
     * of a single {@link #copyStream} invocation. Package-private instead of private so the
     * unit test (same package) can drive the filter directly.
     */
    static final class ToolCallInterleaveState {
        /** Highest {@code tool_calls[].index} of this stream, {@code -1} while no tool call arrived. */
        int maxToolCallIndex = -1;
        /** Number of {@code content} fields removed from the lines forwarded to the client. */
        int suppressedDeltas = 0;
        /** Total number of characters dropped together with those {@code content} fields. */
        int suppressedChars = 0;
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