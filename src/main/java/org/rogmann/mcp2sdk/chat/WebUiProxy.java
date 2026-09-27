package org.rogmann.mcp2sdk.chat;

import org.rogmann.mcp2sdk.utils.LlmUsage;
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
    /** Switches the DEBUG logging of LLM responses on (request logging is not affected). */
    private static final String PROP_LOG_RESPONSES = "webui.logResponses";
    /**
     * Drops blank answer content that a model emits <em>between two tool calls</em> before the
     * SSE line reaches the Web UI (see {@link #stripToolCallInterleaveContent}). Off by default;
     * enable it only for the model/backend combination that shows the broken tool-call history.
     */
    private static final String PROP_SUPPRESS_TOOLCALL_INTERLEAVE = "webui.suppressToolCallInterleave";

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

    /**
     * The reasoning-effort labels the chat template of the configured model accepts, as parsed by
     * {@link ReasoningEfforts#parseTemplateEfforts(String)}; {@code null} until the first lookup. An
     * empty list records "the template declares none", which is not the same as "not looked up yet" -
     * it avoids re-parsing a template of several hundred kB on every request. Guarded by
     * {@link #templateEfforts()}, mirroring {@link #chatTemplateCache}.
     */
    private List<String> templateEffortsCache = null;

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
    /** Max. length of the whole body when it cannot be parsed as JSON. */
    private static final int LOG_FALLBACK_MAX_LENGTH = 600;
    /** Number of leading characters kept for a fallback-shortened body. */
    private static final int LOG_FALLBACK_PREFIX = 500;
    /** Number of trailing characters kept for a fallback-shortened body. */
    private static final int LOG_FALLBACK_SUFFIX = 100;

    /** Overall max. length of the logged request body, even if it contains many (individually
     * short) values such as tool calls. Applied after per-value shortening. */
    private static final int LOG_BODY_MAX_LENGTH = 2000;
    /** Number of leading characters kept in the overall request-body log line. */
    private static final int LOG_BODY_PREFIX = 1800;
    /** Number of trailing characters kept in the overall request-body log line. */
    private static final int LOG_BODY_SUFFIX = 200;

    /** Max. length of a single string value of a logged response before shortening. Larger than
     * the request limit on purpose: the answer/reasoning text is what the switch is enabled for. */
    private static final int LOG_RESPONSE_MAX_STRING_LENGTH = 1200;
    /** Number of leading characters kept for a shortened response string value. */
    private static final int LOG_RESPONSE_STRING_PREFIX = 1000;
    /** Number of trailing characters kept for a shortened response string value. */
    private static final int LOG_RESPONSE_STRING_SUFFIX = 100;
    /** Overall max. length of the logged response line, applied after per-value shortening. */
    private static final int LOG_RESPONSE_BODY_MAX_LENGTH = 5000;
    /** Number of leading characters kept in the overall response log line. */
    private static final int LOG_RESPONSE_BODY_PREFIX = 4500;
    /** Number of trailing characters kept in the overall response log line. */
    private static final int LOG_RESPONSE_BODY_SUFFIX = 300;

    /** Max. characters captured per response part (chain-of-thought / answer) for the response
     * DEBUG log. Beyond that the remaining characters are only counted, so a long answer is not
     * held in memory just for a debug line. */
    private static final int RESPONSE_LOG_MAX_CHARS_PER_PART = 4000;

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
        return shortenBody(requestBody, LOG_MAX_STRING_LENGTH, LOG_STRING_PREFIX, LOG_STRING_SUFFIX,
                LOG_BODY_MAX_LENGTH, LOG_BODY_PREFIX, LOG_BODY_SUFFIX);
    }

    /**
     * Prepares a response body for logging, mirroring {@link #shortenRequestBody} but with the
     * larger limits of the {@code webui.logResponses} DEBUG log: the answer (and its chain of
     * thought) is the reason the switch gets enabled, so single values are cut later than in the
     * request log.
     *
     * @param responseBody raw response body or the response summary built from the SSE chunks
     * @return shortened representation suitable for logging
     */
    private String shortenResponseBody(String responseBody) {
        return shortenBody(responseBody, LOG_RESPONSE_MAX_STRING_LENGTH, LOG_RESPONSE_STRING_PREFIX,
                LOG_RESPONSE_STRING_SUFFIX, LOG_RESPONSE_BODY_MAX_LENGTH,
                LOG_RESPONSE_BODY_PREFIX, LOG_RESPONSE_BODY_SUFFIX);
    }

    /**
     * Shortens a JSON body for logging with the given per-value and overall limits:
     * long string values are trimmed first (keeping the JSON structure readable), then the whole
     * line is capped. A body that cannot be parsed as JSON is truncated as plain text.
     *
     * @param body             raw body
     * @param maxStringLength  length above which a single string value is shortened
     * @param stringPrefix     leading characters kept for a shortened string value
     * @param stringSuffix     trailing characters kept for a shortened string value
     * @param bodyMaxLength    overall length above which the whole line is truncated
     * @param bodyPrefix       leading characters kept for the overall line
     * @param bodySuffix       trailing characters kept for the overall line
     * @return shortened representation suitable for logging
     */
    private String shortenBody(String body, int maxStringLength, int stringPrefix, int stringSuffix,
                               int bodyMaxLength, int bodyPrefix, int bodySuffix) {
        String shortened;
        try {
            JsonNode node = jsonMapper.readTree(body);
            shortened = jsonMapper.writeValueAsString(
                    shortenLongStrings(node, maxStringLength, stringPrefix, stringSuffix));
        } catch (RuntimeException e) {
            LOG.debug("Cannot parse body as JSON, fall back to plain truncation: {}", e.getMessage());
            shortened = shortenText(body, LOG_FALLBACK_MAX_LENGTH,
                    LOG_FALLBACK_PREFIX, LOG_FALLBACK_SUFFIX);
        }
        // Per-value shortening keeps the JSON readable, but a body with many short values
        // (e.g. many tool calls) can still grow large - cap the overall log line.
        return truncateLogText(shortened, bodyMaxLength, bodyPrefix, bodySuffix);
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
     * @param node            node to process
     * @param maxStringLength length above which a string value is shortened
     * @param prefix          number of leading characters kept for a shortened value
     * @param suffix          number of trailing characters kept for a shortened value
     * @return a new node with the long string values shortened
     */
    private JsonNode shortenLongStrings(JsonNode node, int maxStringLength, int prefix, int suffix) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            ObjectNode src = (ObjectNode) node;
            ObjectNode dst = jsonMapper.createObjectNode();
            for (Map.Entry<String, JsonNode> entry : src.properties()) {
                dst.set(entry.getKey(), shortenLongStrings(entry.getValue(),
                        maxStringLength, prefix, suffix));
            }
            return dst;
        } else if (node.isArray()) {
            ArrayNode dst = jsonMapper.createArrayNode();
            for (JsonNode child : node) {
                dst.add(shortenLongStrings(child, maxStringLength, prefix, suffix));
            }
            return dst;
        } else if (node.isString()) {
            String text = node.asString();
            if (text.length() > maxStringLength) {
                String shortened = text.substring(0, prefix)
                        + "[...]"
                        + text.substring(text.length() - suffix);
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
        mapProps.put("model_path", getModelName());

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
        String key = normalizeModelName(getModelName());
        String resourcePath = "chat-templates/" + key + ".jinja";
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                LOG.info("No chat template for model '{}' (looked for {}), reasoning detection disabled",
                        getModelName(), resourcePath);
                return null;
            }
            chatTemplateCache = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            LOG.info("Loaded chat template for model '{}' from {}", getModelName(), resourcePath);
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
                String markerNew = mapReasoning.putIfAbsent(getModelName(), marker);
                if (markerNew != null) {
                    LOG.info("Model {}: Reasoning-marker {}", getModelName(), marker);
                }
                return true;
            }
        }
        LOG.info("Model {}: found no reasoning marker in template of length {}", getModelName(), template.length());
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
        LOG.debug("Injected reasoning_effort '{}' from budget {} (template labels {})",
                effort, budget, templateEfforts());
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
            LOG.debug("Set vLLM reasoning_effort '{}' (template labels {})", effort, templateEfforts());
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
     * Maps a thinking/reasoning token budget to the effort label accepted by the current model.
     *
     * <p>The label set is taken from the model's chat template whenever it declares one (see
     * {@link #templateEfforts()}), so a template that uses a different vocabulary is served a label
     * it actually accepts. DeepSeek-V4 for example branches on {@code high}/{@code max}, while
     * Qwen3.8-Flash-Next only accepts {@code xhigh} (default), {@code medium} and {@code low} and
     * rejects everything else with
     * {@code Unexpected reasoning effort high. Supported types are xhigh (default), medium, and low.}</p>
     *
     * <p>Matches {@code REASONING_EFFORT_TOKENS} in the Web UI (chat.service.ts):
     * low=512, medium=2048, high=8192, max=-1 (unlimited; the UI omits the budget field for max, so
     * an absent budget resolves to -1 here). Without template information the llama.cpp label set
     * {@code low/medium/high/max} is used.</p>
     *
     * @param budget the raw budget value (-1 when not present)
     * @return the effort label, or {@code null} if the budget is not a known level
     */
    private String reasoningEffortForBudget(int budget) {
        return ReasoningEfforts.effortForBudget(budget, ReasoningEfforts.EFFORT_BY_BUDGET,
                templateEfforts());
    }

    /**
     * Maps a thinking/reasoning token budget to the {@code reasoning_effort} label for a vLLM
     * (DeepSeek OpenAI-format) backend. DeepSeek conventionally distinguishes {@code low},
     * {@code high} and {@code max} and has no {@code medium}, so the UI's {@code medium} and
     * {@code high} are both raised to {@code high} (per convention only {@code max} reaches
     * {@code max}).
     *
     * <p>As in {@link #reasoningEffortForBudget(int)} the labels actually declared by the model's
     * chat template take precedence over that convention: a template that accepts {@code medium}
     * gets {@code medium}, and one that has no {@code max} (Qwen3.8-Flash-Next:
     * {@code xhigh}/{@code medium}/{@code low}) receives its top level instead of a rejected
     * {@code max}.</p>
     *
     * @param budget the raw budget value (-1 when not present)
     * @return the DeepSeek effort label, or {@code null} if the budget is not a known level
     */
    private String vllmEffortForBudget(int budget) {
        return ReasoningEfforts.effortForBudget(budget, ReasoningEfforts.VLLM_EFFORT_BY_BUDGET,
                templateEfforts());
    }

    /**
     * The reasoning-effort labels the chat template of the configured model accepts, e.g.
     * {@code [high, max]} for DeepSeek-V4 or {@code [xhigh, medium, low]} for Qwen3.8-Flash-Next.
     * The parsing itself lives in {@link ReasoningEfforts#parseTemplateEfforts(String)}; here the
     * result is cached because a template can be several hundred kB and is read on every request.
     *
     * <p>An empty list means the template declares no effort vocabulary (or no template was found),
     * in which case the backend default labels are used.</p>
     *
     * @return the declared labels in template order, empty if none are declared
     */
    private synchronized List<String> templateEfforts() {
        if (templateEffortsCache == null) {
            String template = loadChatTemplate();
            templateEffortsCache = ReasoningEfforts.parseTemplateEfforts(template);
            if (templateEffortsCache.isEmpty()) {
                LOG.info("Model {}: chat template declares no reasoning_effort labels, keeping the "
                        + "backend default labels", getModelName());
            } else {
                LOG.info("Model {}: chat template accepts reasoning effort labels {}",
                        getModelName(), templateEffortsCache);
            }
        }
        return templateEffortsCache;
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
     * Forwards a request to the LLM server and PIPES the response stream directly.
     * Used for Streaming (SSE).
     */
    private void forwardRequestToLLM(JsonNode requestNode, String cookie, String requestPath,
                                     HttpServletResponse clientResponse, boolean streaming) throws IOException {
        // Deep-Copy von requestNode, um alle Parameter zu übernehmen.
        ObjectNode llmRequest = (ObjectNode) requestNode.deepCopy();

        // Set model name (overwrite if present)
        llmRequest.put("model", getModelName());

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
    private String forwardRequestToLLMBuffered(JsonNode requestNode, String cookie, String requestPath) throws IOException {
        ObjectNode llmRequest = (ObjectNode) requestNode.deepCopy();
        llmRequest.put("model", getModelName());

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
                // Non-streaming responses carry prompt_tokens_details.cached_tokens natively,
                // so no extra /metrics sampling is needed here. No per-chunk timing is available,
                // hence the firstOutputNano/tsStartNano/tsEndNano are left null/0 (ttftClientMs=0).
                recordUsageStatistics(tsStart, dataLines, false, -1, null, 0L, 0L);
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
     * Collector for the {@value #PROP_LOG_RESPONSES} DEBUG dump of an LLM response: it merges the
     * SSE chunks into one compact JSON object so an answer appears as a <em>single</em> log line
     * next to the "LLM Request" line instead of hundreds of chunk lines.
     *
     * <p>{@link #accept(String)} is fed the raw {@code data:} payloads; reasoning and answer
     * deltas are concatenated, the last reported {@code finish_reason}, {@code model} and
     * {@code usage} are kept and the number of chunks is counted. A buffered (non-streaming)
     * payload keeps its text in {@code choices[].message} instead of {@code choices[].delta} and
     * is handled the same way, as is the vLLM spelling {@code reasoning} (llama.cpp and the Web UI
     * use {@code reasoning_content}).</p>
     *
     * <p>Tool calls are captured too, keyed by the streamed {@code index}: a turn that ends with
     * {@code finish_reason=tool_calls} carries its whole payload in {@code delta.tool_calls}, so
     * without them the dump would show the finish reason while hiding both the invoked tool and
     * its arguments - often the larger part of the generated tokens.</p>
     *
     * <p>Per-part character totals ({@link #reasoningCharsTotal()} and friends) are tallied over
     * <em>every</em> chunk and are not subject to the capture cap, which makes them usable for
     * {@link #analyzeReasoningOutput} without holding a long chain of thought in memory.</p>
     *
     * <p>It is instantiated only when {@link #responseLogEnabled()} answers true, so a disabled
     * switch means no buffering and no per-chunk JSON work at all. Captured text is bounded
     * ({@value #RESPONSE_LOG_MAX_CHARS_PER_PART} characters per part) - a long chain of thought
     * must not be held in memory for a debug line; the dropped characters are named in the output
     * and the whole line is shortened by {@link #shortenResponseBody} anyway.</p>
     */
    private static final class SseResponseLog {

        /** Mapper for reading the chunks and rendering the summary. */
        private final JsonMapper jsonMapper;
        /** Concatenated chain-of-thought deltas. */
        private final BoundedText reasoning = new BoundedText(RESPONSE_LOG_MAX_CHARS_PER_PART);
        /** Concatenated answer deltas. */
        private final BoundedText answer = new BoundedText(RESPONSE_LOG_MAX_CHARS_PER_PART);
        /**
         * Tool-call accumulators keyed by the streamed {@code index}
         * ({@code choices[].delta.tool_calls[]}). Insertion order is kept, which matches the
         * order in which the model emitted the calls.
         */
        private final Map<Integer, ToolCallLog> toolCalls = new LinkedHashMap<>();
        /** Chain-of-thought characters seen in total, counted before the {@link BoundedText} cap. */
        private long reasoningChars = 0L;
        /** Answer characters seen in total, counted before the {@link BoundedText} cap. */
        private long contentChars = 0L;
        /** {@code function.arguments} characters seen in total, across all tool calls. */
        private long toolCallChars = 0L;
        /** Model name as reported by the server (first chunk naming one). */
        private String model;
        /** Last non-empty {@code finish_reason} (stop, length, tool_calls, ...). */
        private String finishReason;
        /** {@code usage} object of the last chunk that carried one. */
        private JsonNode usage;
        /** Number of chunks fed in. */
        private int chunks = 0;
        /** Number of chunks whose payload was not decodable JSON (keep-alive frames and alike). */
        private int unparsableChunks = 0;

        /**
         * @param jsonMapper mapper used to parse the chunk payloads and to render the summary
         */
        SseResponseLog(JsonMapper jsonMapper) {
            this.jsonMapper = jsonMapper;
        }

        /**
         * Consumes a single SSE data payload (without the {@code data:} prefix). A payload that
         * is not JSON only bumps the counters - the dump must never break a running stream.
         *
         * @param dataLine raw chunk payload
         */
        void accept(String dataLine) {
            chunks++;
            JsonNode node;
            try {
                node = jsonMapper.readTree(dataLine);
            } catch (RuntimeException e) {
                unparsableChunks++;
                return;
            }
            if (node == null || !node.isObject()) {
                return;
            }
            JsonNode modelNode = node.get("model");
            if (model == null && modelNode != null && modelNode.isString()) {
                model = modelNode.asString();
            }
            JsonNode usageNode = node.get("usage");
            if (usageNode != null && usageNode.isObject()) {
                // Last chunk carrying usage wins (stream_options.include_usage / continuous stats).
                // Detached copy: the summary owns everything it renders.
                usage = usageNode.deepCopy();
            }
            JsonNode choices = node.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                return;
            }
            JsonNode choice = choices.get(0);
            JsonNode finishNode = choice.get("finish_reason");
            if (finishNode != null && finishNode.isString()) {
                finishReason = finishNode.asString();
            }
            // Streaming sends the deltas in "delta", a buffered response in "message".
            JsonNode part = choice.get("delta");
            if (part == null || !part.isObject()) {
                part = choice.get("message");
            }
            if (part == null || !part.isObject()) {
                return;
            }
            String reasoningContent = textOf(part, "reasoning_content");
            String reasoningVllm = textOf(part, "reasoning");
            String answerDelta = textOf(part, "content");
            reasoning.append(reasoningContent);
            reasoning.append(reasoningVllm);
            answer.append(answerDelta);
            // Tally over the whole stream, deliberately taken before the BoundedText cap: a
            // longer chain of thought is truncated in the dump but must still be countable here.
            reasoningChars += lengthOf(reasoningContent) + lengthOf(reasoningVllm);
            contentChars += lengthOf(answerDelta);
            collectToolCalls(part.get("tool_calls"));
        }

        /**
         * Folds a {@code tool_calls} array into {@link #toolCalls}, for both shapes it occurs in:
         * streamed {@code delta.tool_calls} fragments of one call spread over many chunks, and the
         * complete {@code choices[].message.tool_calls} array of a buffered response.
         *
         * <p>Streaming opens a call with the fragment carrying {@code id} and
         * {@code function.name}; later fragments append to {@code function.arguments} only. A
         * captured {@code id}/{@code name} is therefore never overwritten by a later null, while
         * the argument fragments are concatenated in arrival order. Arguments are kept as the
         * JSON string the backend sends and are not re-parsed, so a stream that breaks mid-call
         * still shows the part that arrived.</p>
         *
         * @param toolCallsNode the {@code tool_calls} node of a delta/message; null and non-array
         *                      values are ignored, so an unexpected shape cannot break the stream
         */
        private void collectToolCalls(JsonNode toolCallsNode) {
            if (toolCallsNode == null || !toolCallsNode.isArray()) {
                return;
            }
            for (int position = 0; position < toolCallsNode.size(); position++) {
                JsonNode entry = toolCallsNode.get(position);
                if (entry == null || !entry.isObject()) {
                    continue;
                }
                String id = textOf(entry, "id");
                JsonNode function = entry.get("function");
                boolean hasFunction = function != null && function.isObject();
                String name = hasFunction ? textOf(function, "name") : null;
                String arguments = hasFunction ? textOf(function, "arguments") : null;
                if (id == null && name == null && arguments == null) {
                    // Element carries nothing (some backends emit empty tool_calls placeholders);
                    // registering it would put a nameless phantom call into the dump.
                    continue;
                }
                // The streamed "index" identifies a call; buffered payloads commonly omit it,
                // then the array position is the best available key.
                int index = entry.path("index").asInt(position);
                ToolCallLog call = toolCalls.computeIfAbsent(index, key -> new ToolCallLog());
                if (id != null) {
                    call.id = id;
                }
                if (name != null) {
                    call.name = name;
                }
                if (arguments != null) {
                    toolCallChars += arguments.length();
                    call.arguments.append(arguments);
                }
            }
        }

        /**
         * Renders what was collected as a compact JSON object, ready for
         * {@link #shortenResponseBody}. Fields that never appeared in the stream are omitted.
         *
         * @return the response summary as JSON
         */
        String toLogString() {
            ObjectNode summary = jsonMapper.createObjectNode();
            if (model != null) {
                summary.put("model", model);
            }
            summary.put("chunks", chunks);
            if (unparsableChunks > 0) {
                summary.put("unparsableChunks", unparsableChunks);
            }
            if (finishReason != null) {
                summary.put("finish_reason", finishReason);
            }
            if (!reasoning.isEmpty()) {
                summary.put("reasoning", reasoning.text());
            }
            if (!answer.isEmpty()) {
                summary.put("content", answer.text());
            }
            if (!toolCalls.isEmpty()) {
                ArrayNode toolCallArray = jsonMapper.createArrayNode();
                for (ToolCallLog call : toolCalls.values()) {
                    ObjectNode callNode = jsonMapper.createObjectNode();
                    if (call.id != null) {
                        callNode.put("id", call.id);
                    }
                    // Absent on a stream that was cut before the opening fragment arrived.
                    callNode.put("name", call.name != null ? call.name : "<incomplete>");
                    callNode.put("arguments", call.arguments.text());
                    toolCallArray.add(callNode);
                }
                summary.set("tool_calls", toolCallArray);
            }
            if (usage != null) {
                summary.set("usage", usage);
            }
            return jsonMapper.writeValueAsString(summary);
        }

        /**
         * @return number of SSE chunk payloads fed in, including the {@code [DONE]} frame's
         *         siblings; put smaller line counts (usage collection keeps only the tail) in
         *         perspective
         */
        int chunkCount() {
            return chunks;
        }

        /**
         * @return last reported {@code finish_reason}, or null if the stream never named one
         */
        String finishReasonValue() {
            return finishReason;
        }

        /**
         * @return chain-of-thought characters of the whole stream, unaffected by the capture cap
         */
        long reasoningCharsTotal() {
            return reasoningChars;
        }

        /**
         * @return answer-content characters of the whole stream, unaffected by the capture cap
         */
        long contentCharsTotal() {
            return contentChars;
        }

        /**
         * @return {@code function.arguments} characters of the whole stream, across all tool calls
         */
        long toolCallCharsTotal() {
            return toolCallChars;
        }

        /**
         * Reads a text field of a delta/message node. Absent fields and explicit nulls - which
         * OpenAI-compatible streams send while thinking, and again for everything but the changed
         * field - yield null.
         *
         * @param part  the delta or message node
         * @param field field name
         * @return the text value or null
         */
        private static String textOf(JsonNode part, String field) {
            JsonNode value = part.get(field);
            return (value != null && value.isString()) ? value.asString() : null;
        }

        /**
         * @param text text or null
         * @return length of the text, 0 for null
         */
        private static int lengthOf(String text) {
            return text == null ? 0 : text.length();
        }

        /**
         * Accumulation slot of a single tool call within {@link SseResponseLog}: the identity
         * scalars taken from the opening fragment plus the bounded argument text.
         */
        private static final class ToolCallLog {
            /** Call id, from the fragment that opened this call. */
            String id;
            /** Function name, from the fragment that opened this call. */
            String name;
            /** Concatenated {@code function.arguments} fragments. */
            final BoundedText arguments = new BoundedText(RESPONSE_LOG_MAX_CHARS_PER_PART);
        }
    }

    /**
     * Text accumulator with a hard character limit, used to capture the answer and the reasoning
     * of a streamed response for the DEBUG dump. Once the limit is reached, further text is only
     * counted; {@link #text()} then names the dropped characters so the log line does not look
     * like the complete answer.
     */
    private static final class BoundedText {
        /** Cap for the captured characters. */
        private final int maxChars;
        /** Captured characters. */
        private final StringBuilder sb;
        /** Characters seen beyond the cap. */
        private long droppedChars = 0L;

        /**
         * @param maxChars maximum number of characters to keep
         */
        BoundedText(int maxChars) {
            this.maxChars = maxChars;
            this.sb = new StringBuilder(Math.min(256, maxChars));
        }

        /**
         * Appends a delta; null, empty and over-limit text are ignored (and counted).
         *
         * @param text delta text or null
         */
        void append(String text) {
            if (text == null || text.isEmpty()) {
                return;
            }
            int free = maxChars - sb.length();
            if (free <= 0) {
                droppedChars += text.length();
            } else if (text.length() > free) {
                sb.append(text, 0, free);
                droppedChars += text.length() - free;
            } else {
                sb.append(text);
            }
        }

        /**
         * @return true if nothing was captured
         */
        boolean isEmpty() {
            return sb.isEmpty();
        }

        /**
         * @return the captured text, suffixed by the number of dropped characters if any
         */
        String text() {
            if (droppedChars == 0L) {
                return sb.toString();
            }
            return sb.append(" [...").append(droppedChars).append(" more chars]").toString();
        }
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