package org.rogmann.mcp2sdk.image;

import org.rogmann.mcp2sdk.js.JsUserRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One-shot vision request to the <em>current</em> model - the one the Web UI / ACP chat talks
 * to ({@code webui.model.url}, {@code webui.model.name}), gated by {@code webui.hasVision}:
 * the call only happens when the current model is marked as vision-capable. This is the
 * deliberate counterpart of {@code org.rogmann.mcp2sdk.tools.CallLlmTool} (which uses the
 * separately configured {@code llmtool.model.*}): no session, no SSE, no tool calls, no
 * conversation history, and that class is not touched.
 * <p>
 * One plain POST with {@code stream: false}. Default is the <strong>chat-completions</strong>
 * shape the Web UI proxy uses for the current model ({@code modelUrl + "v1/chat/completions"}):
 * one user message whose content is an {@code image_url} part (the base64 data URL) plus a
 * {@code text} part (the prompt). With {@code webui.vision.api=responses} the same call goes to
 * {@code modelUrl + "v1/responses"} in the Responses-API shape ({@code input_text}/
 * {@code input_image}). The endpoint is completed with {@code /v1/...} like the proxy does it -
 * appending a bare {@code /responses} to a base without {@code /v1} was the 404 of the first
 * live run.</p>
 * <p>
 * The response is one JSON document; the parser reads {@code choices[].message.content} plus
 * {@code usage} (with the responses shape as a tolerant fallback) - a fixed, small grammar.</p>
 *
 * <h3>Configuration (application.yml or {@code -D}, resolved through the Spring
 * {@link Environment})</h3>
 * <ul>
 *   <li>{@code webui.model.url} / {@code webui.model.name} - the current model, same source
 *       as the chat (the URL gets {@code /responses} appended),</li>
 *   <li>{@value #PROP_HAS_VISION} - the gate: the call only happens when the current model is
 *       marked as vision-capable,</li>
 *   <li>{@value #PROP_VISION_URL} / {@value #PROP_VISION_MODEL} - optional overrides when the
 *       vision model lives somewhere else,</li>
 *   <li>{@value #PROP_ENABLED} - {@code false} switches the feature off entirely,</li>
 *   <li>{@value #PROP_TIMEOUT} - HTTP read timeout in seconds (default 120),</li>
 *   <li>{@value #PROP_MAX_IMAGE_BYTES} - cap of the encoded image payload (default 5 MiB),</li>
 *   <li>{@code webui.llm.token} - optional {@code Authorization: Bearer} header, the same
 *       token the Web UI proxy sends,</li>
 *   <li>{@value #PROP_API} - {@code 'chat'} (default) or {@code 'responses'}.</li>
 * </ul>
 * <p>
 * The Spring {@link Environment} is injected via {@link #setEnvironment(Environment)} from
 * {@code McpConfig} (the same pattern as {@code ReadDependencyClassSourceTool}); before the
 * injection the plain system properties are used.</p>
 *
 * @see JsImage#vision(byte[], String, String, Map)
 */
public class VisionClient {

    private static final Logger LOG = LoggerFactory.getLogger(VisionClient.class);

    /** Gate: the current model must be marked as vision-capable. */
    static final String PROP_HAS_VISION = "webui.hasVision";
    /** The current model's gateway URL (same source as the chat). */
    static final String PROP_URL = "webui.model.url";
    /** The current model's name (same source as the chat). */
    static final String PROP_MODEL = "webui.model.name";
    /** Optional gateway URL override when the vision model lives somewhere else. */
    static final String PROP_VISION_URL = "webui.vision.url";
    /** Optional model-name override for the vision call. */
    static final String PROP_VISION_MODEL = "webui.vision.model";
    /** {@code webui.vision.enabled=false} switches the feature off entirely. */
    static final String PROP_ENABLED = "webui.vision.enabled";
    /** HTTP read timeout in seconds. */
    static final String PROP_TIMEOUT = "webui.vision.timeoutSeconds";
    /** Cap of the encoded image payload in bytes. */
    static final String PROP_MAX_IMAGE_BYTES = "webui.vision.maxImageBytes";
    /** Optional API key, sent as an {@code Authorization: Bearer} header. */
    static final String PROP_LLM_TOKEN = "webui.llm.token";
    /**
     * Which API the vision call speaks: {@code chat} (default - {@code /v1/chat/completions},
     * the API the Web UI proxy uses for the current model) or {@code responses}
     * ({@code /v1/responses}, OpenAI's newer endpoint).
     */
    static final String PROP_API = "webui.vision.api";

    /** Connect timeout of the vision request (a local gateway answers fast). */
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    /** How much of an error body is quoted in the exception message. */
    private static final int ERROR_BODY_QUOTE = 500;

    /** The Spring environment, injected by McpConfig (same pattern as the other tools). */
    private static volatile Environment environment;

    /**
     * Per-call overrides of the configured request - what {@code image.describe}/{@code image.ocr}
     * pass through (the configured values stay the defaults).
     * @param model model name to use instead of the configured one, or {@code null}
     * @param maxTokens upper bound of the answer in tokens ({@code max_tokens} /
     *                  {@code max_output_tokens}), or {@code null} to send nothing
     * @param timeoutSeconds read timeout instead of {@code webui.vision.timeoutSeconds}, or
     *                       {@code null}
     */
    public record AskOptions(String model, Integer maxTokens, Integer timeoutSeconds) {
    }

    private final boolean enabled;
    private final boolean hasVision;
    private final String apiMode;
    private final String endpointUrl;
    private final String model;
    private final String llmToken;
    private final int timeoutSeconds;
    private final long maxImageBytes;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    /**
     * Creates the client and reads the configuration (one instance per call). Boolean and
     * numeric properties are validated strictly: an invalid value aborts here with the property
     * name and value quoted - a silent fallback to the default would take far too long to
     * notice (AGENTS.md).
     */
    public VisionClient() {
        this.enabled = booleanProperty(PROP_ENABLED, true);
        this.hasVision = booleanProperty(PROP_HAS_VISION, false);
        this.apiMode = apiMode();
        String url = firstNonBlank(get(PROP_VISION_URL), get(PROP_URL));
        this.endpointUrl = endpoint(url, apiMode);
        this.model = firstNonBlank(get(PROP_VISION_MODEL), get(PROP_MODEL), "unknown");
        this.llmToken = firstNonBlank(get(PROP_LLM_TOKEN));
        this.timeoutSeconds = intProperty(PROP_TIMEOUT, 120, 1, 3600);
        this.maxImageBytes = longProperty(PROP_MAX_IMAGE_BYTES, 5L * 1024 * 1024);
    }

    /**
     * Reads and validates {@code webui.vision.api} strictly ({@code chat} or
     * {@code responses}).
     * @return {@code 'chat'} or {@code 'responses'}
     */
    private static String apiMode() {
        String value = get(PROP_API);
        if (value == null || value.isBlank()) {
            return "chat";
        }
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        if ("chat".equals(trimmed) || "responses".equals(trimmed)) {
            return trimmed;
        }
        throw invalidProperty(PROP_API, value, "'chat' or 'responses'");
    }

    /**
     * Injects the Spring environment (called from {@code McpConfig}, the same pattern as
     * {@code ReadDependencyClassSourceTool}); before that, plain system properties apply.
     * @param env the Spring environment of the running server
     */
    public static void setEnvironment(Environment env) {
        environment = env;
    }

    /**
     * Display-only check for the module summary: is the feature switched on, the current model
     * marked as vision-capable and a gateway URL configured? Lenient by design (it runs while
     * the tool is being set up); the strict validation happens in the constructor.
     * @return true when {@code image.vision} would be offered
     */
    public static boolean isConfigured() {
        if (!"true".equalsIgnoreCase(get(PROP_ENABLED, "true"))) {
            return false;
        }
        if (!"true".equalsIgnoreCase(get(PROP_HAS_VISION, "false"))) {
            return false;
        }
        return firstNonBlank(get(PROP_VISION_URL), get(PROP_URL)) != null;
    }

    /**
     * @return whether the feature is switched on ({@code webui.vision.enabled} is not false)
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * @return whether the current model is marked as vision-capable ({@code webui.hasVision})
     */
    public boolean hasVisionEnabled() {
        return hasVision;
    }

    /**
     * @return whether an endpoint URL is configured
     */
    public boolean hasEndpoint() {
        return endpointUrl != null;
    }

    /**
     * @return the cap of the encoded image payload in bytes
     */
    public long maxImageBytes() {
        return maxImageBytes;
    }

    /**
     * Sends one vision request and parses the single JSON response.
     * @param prompt the question about the image
     * @param imageDataUrl the image as {@code data:image/...;base64,...}
     * @return map {@code {text, model, usage: {inputTokens, outputTokens, totalTokens}}}
     * @throws JsUserRuntimeException on configuration, transport or protocol errors (with the
     *         hint for a non-vision model mapped onto the message)
     */
    public Map<String, Object> ask(String prompt, String imageDataUrl) {
        return ask(prompt, imageDataUrl, null);
    }

    /**
     * Sends one vision request with per-call overrides and parses the single JSON response.
     * @param prompt the question about the image
     * @param imageDataUrl the image as {@code data:image/...;base64,...}
     * @param overrides model/maxTokens/timeout of this call, or {@code null} for the properties
     * @return map {@code {text, model, usage: {inputTokens, outputTokens, totalTokens}}}
     * @throws JsUserRuntimeException on configuration, transport or protocol errors (with the
     *         hint for a non-vision model mapped onto the message)
     */
    public Map<String, Object> ask(String prompt, String imageDataUrl, AskOptions overrides) {
        if (!enabled) {
            throw new JsUserRuntimeException("image.vision: the feature is switched off ("
                    + PROP_ENABLED + "=false) - remove the property or set it to 'true'");
        }
        if (!hasVisionEnabled()) {
            throw new JsUserRuntimeException("image.vision: the current model is not marked as"
                    + " vision-capable (" + PROP_HAS_VISION + "=false) - mark it in the"
                    + " configuration or use image.analyze() / image.preview() instead"
                    + " (image.status() names what is missing)");
        }
        if (!hasEndpoint()) {
            throw new JsUserRuntimeException("image.vision: no vision model configured - set "
                    + PROP_URL + " (the current model's gateway) or " + PROP_VISION_URL
                    + " (image.status() names what is missing)");
        }
        String modelName = overrides != null && overrides.model() != null
                && !overrides.model().isBlank() ? overrides.model().trim() : model;
        Integer maxTokens = overrides != null ? overrides.maxTokens() : null;
        int requestTimeout = overrides != null && overrides.timeoutSeconds() != null
                ? overrides.timeoutSeconds() : timeoutSeconds;
        String requestBody = buildRequest(prompt, imageDataUrl, modelName, maxTokens);
        LOG.debug("Vision request to {} (api {}, model {}, maxTokens {}, image {} chars)",
                endpointUrl, apiMode, modelName, maxTokens, imageDataUrl.length());

        String responseBody = post(requestBody, requestTimeout);
        return parseResponse(responseBody, modelName);
    }

    /**
     * Builds the non-streaming request body for the configured API mode: {@code chat} uses the
     * shape the Web UI proxy speaks ({@code messages[].content} with {@code text} and
     * {@code image_url} parts), {@code responses} uses the Responses-API shape
     * ({@code input[].content} with {@code input_text} and {@code input_image} parts).
     * @param prompt the question
     * @param imageDataUrl the base64 data URL
     * @return the JSON string
     */
    private String buildRequest(String prompt, String imageDataUrl, String modelName,
                                Integer maxTokens) {
        ObjectNode request = jsonMapper.createObjectNode();
        request.put("model", modelName);
        if ("responses".equals(apiMode)) {
            if (maxTokens != null) {
                request.put("max_output_tokens", maxTokens.intValue());
            }
            ArrayNode input = request.putArray("input");
            ObjectNode message = input.addObject();
            message.put("role", "user");
            ArrayNode content = message.putArray("content");
            ObjectNode textPart = content.addObject();
            textPart.put("type", "input_text");
            textPart.put("text", prompt);
            ObjectNode imagePart = content.addObject();
            imagePart.put("type", "input_image");
            imagePart.put("image_url", imageDataUrl);
        } else {
            ArrayNode messages = request.putArray("messages");
            ObjectNode message = messages.addObject();
            message.put("role", "user");
            ArrayNode content = message.putArray("content");
            ObjectNode textPart = content.addObject();
            textPart.put("type", "text");
            textPart.put("text", prompt);
            ObjectNode imagePart = content.addObject();
            imagePart.put("type", "image_url");
            ObjectNode imageUrl = imagePart.putObject("image_url");
            imageUrl.put("url", imageDataUrl);
        }
        if (maxTokens != null && !"responses".equals(apiMode)) {
            request.put("max_tokens", maxTokens.intValue());
        }
        request.put("stream", false);
        return jsonMapper.writeValueAsString(request);
    }

    /**
     * POSTs the request body and returns the response body, mapping HTTP errors onto
     * {@link JsUserRuntimeException} (the error body is quoted and logged, a non-vision model
     * gets the configuration hint).
     * @param requestBody JSON request
     * @param timeoutSeconds read timeout of this request
     * @return response body
     */
    private String post(String requestBody, int timeoutSeconds) {
        HttpURLConnection connection;
        try {
            URL url = URI.create(endpointUrl).toURL();
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(timeoutSeconds * 1000);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            if (llmToken != null) {
                connection.setRequestProperty("Authorization", "Bearer " + llmToken);
            }
            connection.setDoOutput(true);
        } catch (IOException e) {
            throw new JsUserRuntimeException("image.vision: cannot open a connection to "
                    + endpointUrl + ": " + e.getMessage(), e);
        }
        try {
            try (OutputStream os = connection.getOutputStream();
                 OutputStreamWriter osw = new OutputStreamWriter(os, StandardCharsets.UTF_8)) {
                osw.write(requestBody);
                osw.flush();
            }
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw httpError(responseCode, connection.getResponseMessage(),
                        readErrorBody(connection));
            }
            try (InputStream is = connection.getInputStream()) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new JsUserRuntimeException("image.vision: request to " + endpointUrl
                    + " failed: " + e.getMessage(), e);
        } finally {
            connection.disconnect();
        }
    }

    /** Reads the error stream of a non-200 response (bounded, may be null). */
    private static String readErrorBody(HttpURLConnection connection) {
        try (InputStream errorStream = connection.getErrorStream()) {
            if (errorStream == null) {
                return "";
            }
            return new String(errorStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("image.vision: failed to read the error stream: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Maps a non-200 response onto the user-facing exception; a body that talks about images or
     * modalities gets the "model is not vision-capable" hint. The response body is logged - it
     * usually carries the actual reason of the gateway.
     * @param code HTTP status code
     * @param reason HTTP reason phrase
     * @param errorBody the response body (already read)
     * @return the exception to throw
     */
    private JsUserRuntimeException httpError(int code, String reason, String errorBody) {
        String quoted = shorten(errorBody);
        LOG.error("image.vision: HTTP {} {} from {} (model {}): {}", code, reason, endpointUrl,
                model, quoted);
        String lower = errorBody.toLowerCase(Locale.ROOT);
        boolean looksLikeVisionRefusal = (code == HttpURLConnection.HTTP_BAD_REQUEST
                || code == HttpURLConnection.HTTP_NOT_FOUND
                || code == 415 || code == 422)
                && (lower.contains("image") || lower.contains("vision")
                || lower.contains("modal") || lower.contains("content"));
        String hint = "";
        if (looksLikeVisionRefusal) {
            hint = String.format(Locale.ROOT, " The current model '%s' rejected the image"
                    + " input (vision not available?) - mark it as vision-capable only if it"
                    + " supports input_image (%s), or use image.analyze() / image.preview()"
                    + " instead.", model, PROP_HAS_VISION);
        }
        return new JsUserRuntimeException(String.format(Locale.ROOT,
                "image.vision: HTTP %d %s from %s: %s%s", code, reason, endpointUrl, quoted, hint));
    }

    /**
     * Parses the single JSON response document: chat-completions ({@code choices[].message})
     * first, with the Responses-API shape ({@code output[].message}) as a tolerant fallback;
     * {@code usage} accepts both token namings.
     * @param responseBody the response body
     * @param modelName the model the request named (reported when the answer names none)
     * @return map {@code {text, model, usage: {inputTokens, outputTokens, totalTokens}}}
     */
    private Map<String, Object> parseResponse(String responseBody, String modelName) {
        JsonNode root;
        try {
            root = jsonMapper.readTree(responseBody);
        } catch (RuntimeException e) {
            throw new JsUserRuntimeException("image.vision: the gateway answered with invalid"
                    + " JSON: " + shorten(responseBody) + " (" + e.getMessage() + ")", e);
        }
        StringBuilder text = new StringBuilder();
        boolean chatFormat = false;
        for (JsonNode choice : root.path("choices")) {
            chatFormat = true;
            JsonNode content = choice.path("message").path("content");
            if (content.isString()) {
                text.append(content.asString());
            } else if (content.isArray()) {
                for (JsonNode part : content) {
                    if ("text".equals(part.path("type").asString())) {
                        text.append(part.path("text").asString());
                    }
                }
            }
        }
        if (!chatFormat) {
            for (JsonNode item : root.path("output")) {
                if (!"message".equals(item.path("type").asString())) {
                    continue;
                }
                for (JsonNode part : item.path("content")) {
                    if ("output_text".equals(part.path("type").asString())) {
                        text.append(part.path("text").asString());
                    }
                }
            }
        }
        JsonNode usageNode = root.path("usage");
        long inputTokens = usageNode.path("input_tokens").asLong();
        if (inputTokens == 0) {
            inputTokens = usageNode.path("prompt_tokens").asLong();
        }
        long outputTokens = usageNode.path("output_tokens").asLong();
        if (outputTokens == 0) {
            outputTokens = usageNode.path("completion_tokens").asLong();
        }
        long totalTokens = usageNode.path("total_tokens").asLong();
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("inputTokens", inputTokens);
        usage.put("outputTokens", outputTokens);
        usage.put("totalTokens", totalTokens);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("text", text.toString());
        String reportedModel = root.path("model").asString();
        result.put("model", reportedModel.isEmpty() ? modelName : reportedModel);
        result.put("usage", usage);
        return result;
    }

    /**
     * Builds the endpoint URL for the chosen API mode. The base URL is the model's gateway
     * ({@code webui.model.url}, which carries no {@code /v1} in the chat's own configuration),
     * so the path is completed the way the Web UI proxy completes it
     * ({@code modelUrl + "v1/..."}); a URL that already names the endpoint is taken as it is.
     * @param baseUrl configured base URL, may be null
     * @param mode {@code 'chat'} or {@code 'responses'}
     * @return the endpoint URL, or null
     */
    private static String endpoint(String baseUrl, String mode) {
        if (baseUrl == null) {
            return null;
        }
        String path = "responses".equals(mode) ? "responses" : "chat/completions";
        if (baseUrl.endsWith("/" + path)) {
            return baseUrl;
        }
        if (baseUrl.endsWith("/v1") || baseUrl.endsWith("/v1/")) {
            return baseUrl.endsWith("/") ? baseUrl + path : baseUrl + "/" + path;
        }
        return baseUrl.endsWith("/") ? baseUrl + "v1/" + path
                : baseUrl + "/v1/" + path;
    }

    /** First non-blank value of the given values. */
    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    /**
     * Resolves a property through the injected Spring environment (application.yml, -D,
     * environment) with a system-property fallback before the injection.
     * @param key the property key
     * @return the value, or null
     */
    private static String get(String key) {
        Environment env = environment;
        if (env != null) {
            return env.getProperty(key);
        }
        return System.getProperty(key);
    }

    /**
     * Resolves a property with a default through the injected Spring environment.
     * @param key the property key
     * @param defaultValue the fallback
     * @return the value or the fallback
     */
    private static String get(String key, String defaultValue) {
        String value = get(key);
        return value != null ? value : defaultValue;
    }

    /** Reads a boolean property strictly ({@code true}/{@code false}), aborting on anything else. */
    private static boolean booleanProperty(String key, boolean fallback) {
        String value = get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim();
        if ("true".equalsIgnoreCase(trimmed)) {
            return true;
        }
        if ("false".equalsIgnoreCase(trimmed)) {
            return false;
        }
        throw invalidProperty(key, value, "'true' or 'false'");
    }

    /** Reads an int property with a range check; an invalid value aborts (no silent fallback). */
    private static int intProperty(String key, int fallback, int min, int max) {
        String value = get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < min || parsed > max) {
                throw invalidProperty(key, value, "an integer between " + min + " and " + max);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw invalidProperty(key, value, "an integer between " + min + " and " + max);
        }
    }

    /** Reads a positive long property; an invalid value aborts (no silent fallback). */
    private static long longProperty(String key, long fallback) {
        String value = get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed <= 0) {
                throw invalidProperty(key, value, "a positive number of bytes");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw invalidProperty(key, value, "a positive number of bytes");
        }
    }

    /**
     * The strict-validation error: names the property and quotes the offending value
     * (AGENTS.md: never fall back to a default silently).
     * @param key the property name
     * @param value the offending value
     * @param expected what the property expects
     * @return the exception to throw
     */
    private static JsUserRuntimeException invalidProperty(String key, String value,
                                                          String expected) {
        return new JsUserRuntimeException(String.format(Locale.ROOT,
                "image.vision: property %s = '%s' is invalid - expected %s (refusing to"
                        + " guess a default)", key, value, expected));
    }

    /** Shortens a body for messages. */
    private static String shorten(String body) {
        return body.length() > ERROR_BODY_QUOTE ? body.substring(0, ERROR_BODY_QUOTE) + "..."
                : body;
    }
}
