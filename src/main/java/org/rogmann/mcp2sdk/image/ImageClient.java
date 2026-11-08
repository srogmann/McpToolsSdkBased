package org.rogmann.mcp2sdk.image;

import org.rogmann.mcp2sdk.js.JsUserRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * One-shot client of an OpenAI-compatible image endpoint: text-to-image
 * ({@code POST /v1/images/generations}, JSON) and image edit
 * ({@code POST /v1/images/edits}, {@code multipart/form-data}) - the counterpart of
 * {@link VisionClient} for the drawing side, with the same rules: one request, no session, no
 * state across calls, plain {@link HttpURLConnection}, no new dependency.
 * <p>
 * The request and response shapes follow the reference server this project talks to
 * ({@code server_qwen_image21.py}, mirrored by {@code image_edit_client.py}): the generation
 * body carries {@code model}, {@code prompt}, {@code output_format} and optionally
 * {@code size}, {@code seed} and {@code num_inference_steps}; the edit is a multipart form whose
 * {@code image} fields hold <strong>bare base64 strings</strong> (no {@code filename}, no
 * {@code Content-Type} part - the server parses them as plain form values, and a data URL would
 * be rejected). The answer is {@code {data: [{b64_json}], usage: {width, height, seed}}}; a
 * {@code data[0].url} answer is downloaded here, through the same host rule as the endpoint.
 * </p>
 *
 * <h3>Configuration (application.yml or {@code -D}, resolved through the Spring
 * {@link Environment})</h3>
 * <ul>
 *   <li>{@value #PROP_GEN_URL} / {@value #PROP_EDIT_URL} - base URL of the endpoints; the
 *       default is the current model's gateway ({@code webui.model.url}), completed with
 *       {@code /v1/images/generations} resp. {@code /v1/images/edits} the way the Web UI proxy
 *       completes its paths,</li>
 *   <li>{@value #PROP_GEN_MODEL} / {@value #PROP_EDIT_MODEL} - the diffusion model; there is
 *       deliberately <em>no</em> default (servers differ: {@code Qwen/Qwen-Image-2.1}, flux
 *       variants, …), so an unconfigured model is a clear message and not a wrong guess,</li>
 *   <li>{@value #PROP_GEN_STEPS} / {@value #PROP_EDIT_STEPS} - default for
 *       {@code num_inference_steps}; unset means "send nothing", so the server's own default
 *       applies (4 for a distilled flux, 40 for Qwen - no number of ours can be right for both),</li>
 *   <li>{@value #PROP_ALLOW_EXTERNAL_HOST} - the safety switch: without it the endpoint (and a
 *       {@code url} in the answer) must point at the configured gateway's host,</li>
 *   <li>{@code webui.llm.token} - optional {@code Authorization: Bearer} header, the same token
 *       the Web UI proxy sends.</li>
 * </ul>
 * <p>
 * The timeout is {@link ImageLimits#timeoutSeconds()} (default 55 s, below the sandbox's 60 s
 * script timeout) unless the call passes {@code timeoutSeconds}; a diffusion run that needs
 * longer needs both raised - the help text says so.
 * </p>
 */
public class ImageClient {

    private static final Logger LOG = LoggerFactory.getLogger(ImageClient.class);

    /** Base URL of the generation endpoint (default: the current model's gateway). */
    static final String PROP_GEN_URL = "mcp.js.image.gen.url";
    /** Diffusion model of {@code image.generate}. */
    static final String PROP_GEN_MODEL = "mcp.js.image.gen.model";
    /** Default {@code num_inference_steps} of {@code image.generate}. */
    static final String PROP_GEN_STEPS = "mcp.js.image.gen.steps";
    /** Allow an endpoint that is not the configured gateway's host. */
    static final String PROP_ALLOW_EXTERNAL_HOST = "mcp.js.image.gen.allowExternalHost";
    /** Base URL of the edit endpoint (default: the generation base). */
    static final String PROP_EDIT_URL = "mcp.js.image.edit.url";
    /** Diffusion model of {@code image.edit} (default: the generation model). */
    static final String PROP_EDIT_MODEL = "mcp.js.image.edit.model";
    /** Default {@code num_inference_steps} of {@code image.edit}. */
    static final String PROP_EDIT_STEPS = "mcp.js.image.edit.steps";
    /** The current model's gateway - the base the endpoints fall back to. */
    static final String PROP_GATEWAY_URL = "webui.model.url";
    /** Optional API key, sent as an {@code Authorization: Bearer} header. */
    static final String PROP_LLM_TOKEN = "webui.llm.token";

    /** Generation path below the base URL. */
    private static final String GEN_PATH = "images/generations";
    /** Edit path below the base URL. */
    private static final String EDIT_PATH = "images/edits";
    /** Connect timeout (a local gateway answers fast). */
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    /** How much of an error body is quoted in the message. */
    private static final int ERROR_BODY_QUOTE = 500;
    /** Largest side accepted in a {@code WxH} size string. */
    private static final int MAX_SIDE = 8192;

    /** The Spring environment, injected by McpConfig (same pattern as the other tools). */
    private static volatile Environment environment;

    private final String genEndpoint;
    private final String editEndpoint;
    private final String genModel;
    private final String editModel;
    private final Integer genSteps;
    private final Integer editSteps;
    private final boolean allowExternalHost;
    private final String gatewayHost;
    private final String llmToken;
    private final int defaultTimeoutSeconds;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    /** One text-to-image request as the caller built it. */
    public record GenerateRequest(String prompt, String model, String size, Integer seed,
                                  Integer steps, String outputFormat, Integer timeoutSeconds) {
    }

    /** One edit request: prompt plus the source pictures (bare base64 in the form). */
    public record EditRequest(String prompt, String model, String size, Integer seed,
                              Integer steps, String outputFormat, List<byte[]> images,
                              Integer timeoutSeconds) {
    }

    /** What the endpoint delivered: the picture plus what the answer said about it. */
    public record Result(byte[] bytes, String format, String model, Integer seed, Integer width,
                         Integer height, String requestId) {
    }

    /**
     * Creates the client and reads the configuration (one instance per call). Values are
     * validated strictly; an invalid one aborts with the property name and value.
     */
    public ImageClient() {
        this.genModel = trimToNull(get(PROP_GEN_MODEL));
        this.editModel = trimToNull(get(PROP_EDIT_MODEL));
        this.genSteps = intProperty(PROP_GEN_STEPS, 1, 1000);
        this.editSteps = intProperty(PROP_EDIT_STEPS, 1, 1000);
        this.allowExternalHost = booleanProperty(PROP_ALLOW_EXTERNAL_HOST, false);
        this.llmToken = trimToNull(get(PROP_LLM_TOKEN));
        this.defaultTimeoutSeconds = ImageLimits.current().timeoutSeconds();
        String gateway = trimToNull(get(PROP_GATEWAY_URL));
        this.gatewayHost = hostOf(gateway);
        String genBase = firstNonBlank(get(PROP_GEN_URL), gateway);
        this.genEndpoint = genBase == null ? null : endpoint(genBase, GEN_PATH);
        String editBase = firstNonBlank(get(PROP_EDIT_URL), genBase);
        this.editEndpoint = editBase == null ? null : endpoint(editBase, EDIT_PATH);
    }

    /**
     * Injects the Spring environment (called from {@code McpConfig}).
     * @param env the Spring environment of the running server
     */
    public static void setEnvironment(Environment env) {
        environment = env;
    }

    /**
     * @return the generation endpoint of this environment (base + {@code /v1/images/...}), or
     *         {@code null} when no base URL is configured - for messages and tests
     */
    public String generationEndpoint() {
        return genEndpoint;
    }

    /**
     * @return the edit endpoint of this environment, or {@code null}
     */
    public String imageEditEndpoint() {
        return editEndpoint;
    }

    /**
     * Display-only check for the module summary: is a generation endpoint resolvable and a model
     * configured? Lenient by design (it runs while the tool is being set up - the strict
     * validation happens in the constructor).
     * @return true when {@code image.generate} would be offered
     */
    public static boolean isConfigured() {
        if (trimToNull(get(PROP_GEN_MODEL)) == null) {
            return false;
        }
        String base = firstNonBlank(get(PROP_GEN_URL), get(PROP_GATEWAY_URL));
        if (base == null) {
            return false;
        }
        if ("true".equalsIgnoreCase(trimToNull(get(PROP_ALLOW_EXTERNAL_HOST)))) {
            return true;
        }
        String gateway = hostOf(trimToNull(get(PROP_GATEWAY_URL)));
        return gateway != null && gateway.equalsIgnoreCase(hostOf(base));
    }

    /**
     * @return whether {@code image.generate} can run in this environment
     */
    public boolean isGenerationAvailable() {
        return generationUnavailableReason(null) == null;
    }

    /**
     * @return whether {@code image.edit} can run in this environment
     */
    public boolean isEditAvailable() {
        return editUnavailableReason(null) == null;
    }

    /**
     * Why a generation request cannot run - the sentence the module puts into its message.
     * @param fn function name for the message ({@code null} for a plain probe)
     * @return the reason, or {@code null} when the endpoint may be used
     */
    public String generationUnavailableReason(String fn) {
        String prefix = fn == null ? "image" : "image." + fn;
        if (genModel == null) {
            return prefix + ": no image model configured - set " + PROP_GEN_MODEL
                    + " (e.g. 'Qwen/Qwen-Image-2.1') or pass {model: '…'}";
        }
        if (genEndpoint == null) {
            return prefix + ": image generation is not available in this environment - neither "
                    + PROP_GEN_URL + " nor " + PROP_GATEWAY_URL + " is set";
        }
        return refusalSentence(prefix, genEndpoint);
    }

    /**
     * Why an edit request cannot run.
     * @param fn function name for the message ({@code null} for a plain probe)
     * @return the reason, or {@code null} when the endpoint may be used
     */
    public String editUnavailableReason(String fn) {
        String prefix = fn == null ? "image" : "image." + fn;
        if (firstNonBlank(editModel, genModel) == null) {
            return prefix + ": no image model configured - set " + PROP_EDIT_MODEL + " or "
                    + PROP_GEN_MODEL + " (e.g. 'Qwen/Qwen-Image-2.1') or pass {model: '…'}";
        }
        if (editEndpoint == null) {
            return prefix + ": image editing is not available in this environment - neither "
                    + PROP_EDIT_URL + ", " + PROP_GEN_URL + " nor " + PROP_GATEWAY_URL
                    + " is set";
        }
        return refusalSentence(prefix, editEndpoint);
    }

    /**
     * POSTs one text-to-image request and returns the picture.
     * @param request the request
     * @return the result
     */
    public Result generate(GenerateRequest request) {
        String reason = generationUnavailableReason("generate");
        if (reason != null) {
            throw new JsUserRuntimeException(reason);
        }
        String model = firstNonBlank(request.model(), genModel);
        ObjectNode body = jsonMapper.createObjectNode();
        body.put("model", model);
        body.put("prompt", request.prompt());
        body.put("output_format", outputFormat(request.outputFormat()));
        Integer steps = request.steps() != null ? request.steps() : genSteps;
        if (steps != null) {
            body.put("num_inference_steps", steps.intValue());
        }
        if (request.size() != null) {
            body.put("size", request.size());
        }
        if (request.seed() != null) {
            body.put("seed", request.seed().intValue());
        }
        String requestBody = jsonMapper.writeValueAsString(body);
        LOG.debug("image.generate: POST {} (model {}, size {}, steps {})", genEndpoint, model,
                request.size(), steps);
        HttpResponse response = post("generate", genEndpoint, "application/json",
                requestBody.getBytes(StandardCharsets.UTF_8), timeoutOf(request.timeoutSeconds()));
        return parseResponse("generate", response, model);
    }

    /**
     * POSTs one edit request ({@code multipart/form-data}, bare base64 image fields).
     * @param request the request
     * @return the result
     */
    public Result edit(EditRequest request) {
        String reason = editUnavailableReason("edit");
        if (reason != null) {
            throw new JsUserRuntimeException(reason);
        }
        String model = firstNonBlank(request.model(), editModel, genModel);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("model", model);
        fields.put("prompt", request.prompt());
        fields.put("output_format", outputFormat(request.outputFormat()));
        if (request.size() != null) {
            fields.put("size", request.size());
        }
        Integer steps = request.steps() != null ? request.steps() : editSteps;
        if (steps != null) {
            fields.put("num_inference_steps", steps.toString());
        }
        if (request.seed() != null) {
            fields.put("seed", request.seed().toString());
        }
        // The reference server reads "image" as plain form values (base64, no file name, no
        // content type) and accepts the field more than once - one value per source picture.
        String boundary = "----mcpimage" + UUID.randomUUID().toString().replace("-", "");
        byte[] body = multipartBody(boundary, fields, "image",
                request.images() == null ? List.of() : request.images());
        LOG.debug("image.edit: POST {} (model {}, {} source image(s))", editEndpoint, model,
                request.images() == null ? 0 : request.images().size());
        HttpResponse response = post("edit", editEndpoint,
                "multipart/form-data; boundary=" + boundary, body,
                timeoutOf(request.timeoutSeconds()));
        return parseResponse("edit", response, model);
    }

    /** The read timeout of one request in seconds. */
    private int timeoutOf(Integer requestTimeout) {
        return requestTimeout != null ? requestTimeout : defaultTimeoutSeconds;
    }

    /**
     * Normalises {@code output_format}: {@code png} (default), {@code jpeg}, {@code webp}.
     * @param format the caller's value, may be null
     * @return the request value
     */
    static String outputFormat(String format) {
        if (format == null || format.isBlank()) {
            return "png";
        }
        String f = format.trim().toLowerCase(Locale.ROOT);
        if ("jpg".equals(f)) {
            return "jpeg";
        }
        if (!"png".equals(f) && !"jpeg".equals(f) && !"webp".equals(f)) {
            throw new JsUserRuntimeException("image: format must be 'png', 'jpeg' or 'webp'"
                    + " (got '" + format + "')");
        }
        return f;
    }

    /**
     * Parses the answer document: {@code data[0].b64_json} (or {@code data[0].url}, which is
     * downloaded here) plus {@code usage}.
     * @param fn function name for messages
     * @param response the HTTP answer
     * @param model the model the request named
     * @return the result
     */
    private Result parseResponse(String fn, HttpResponse response, String model) {
        String text = new String(response.body(), StandardCharsets.UTF_8);
        JsonNode root;
        try {
            root = jsonMapper.readTree(text);
        } catch (RuntimeException e) {
            throw new JsUserRuntimeException("image." + fn + ": the endpoint answered with"
                    + " invalid JSON: " + shorten(text) + " (" + e.getMessage() + ")", e);
        }
        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new JsUserRuntimeException("image." + fn + ": the endpoint answered without a"
                    + " picture (expected data[0].b64_json or data[0].url): " + shorten(text));
        }
        JsonNode first = data.get(0);
        byte[] bytes;
        String b64 = first.path("b64_json").asString();
        if (b64 != null && !b64.isBlank()) {
            try {
                bytes = Base64.getDecoder().decode(b64.trim());
            } catch (IllegalArgumentException e) {
                throw new JsUserRuntimeException("image." + fn + ": the endpoint answered with"
                        + " base64 that cannot be decoded: " + e.getMessage(), e);
            }
        } else {
            String url = first.path("url").asString();
            if (url == null || url.isBlank()) {
                throw new JsUserRuntimeException("image." + fn + ": the endpoint answered with"
                        + " neither b64_json nor url: " + shorten(first.toString()));
            }
            bytes = download(fn, url);
        }
        JsonNode usage = root.path("usage");
        return new Result(bytes, null, model, intOrNull(usage.path("seed")),
                intOrNull(usage.path("width")), intOrNull(usage.path("height")),
                response.requestId());
    }

    /**
     * Downloads the picture of a {@code url} answer - through the same host rule as the endpoint
     * (a gateway that answered with a foreign URL would otherwise turn the tool into a fetcher).
     * @param fn function name for messages
     * @param url the URL from the answer
     * @return the bytes
     */
    private byte[] download(String fn, String url) {
        String refusal = refusalSentence("image." + fn, url);
        if (refusal != null) {
            throw new JsUserRuntimeException("image." + fn + ": the endpoint answered with a URL"
                    + " that is not allowed - " + refusal);
        }
        long max = ImageLimits.current().maxOutputBytes();
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URI(url).toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(defaultTimeoutSeconds * 1000);
            if (llmToken != null) {
                connection.setRequestProperty("Authorization", "Bearer " + llmToken);
            }
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new JsUserRuntimeException("image." + fn + ": downloading the picture from "
                        + url + " answered HTTP " + code + " " + connection.getResponseMessage());
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                int read;
                while ((read = in.read(buffer)) > 0) {
                    total += read;
                    if (total > max) {
                        throw new JsUserRuntimeException("image." + fn + ": the picture at " + url
                                + " is larger than " + max + " bytes (property "
                                + ImageLimits.PROP_MAX_OUTPUT_BYTES + ")");
                    }
                    out.write(buffer, 0, read);
                }
                return out.toByteArray();
            }
        } catch (IOException | URISyntaxException e) {
            throw new JsUserRuntimeException("image." + fn + ": downloading the picture from "
                    + url + " failed: " + e.getMessage(), e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * POSTs a body and maps HTTP errors onto a message that names status, endpoint and the
     * (quoted, shortened) answer - a gateway's body carries the actual reason.
     * @param fn function name
     * @param endpoint the URL
     * @param contentType content type of the body
     * @param body the body
     * @param timeoutSeconds read timeout in seconds
     * @return body and the echoed request id
     */
    private HttpResponse post(String fn, String endpoint, String contentType, byte[] body,
                              int timeoutSeconds) {
        HttpURLConnection connection;
        try {
            connection = (HttpURLConnection) new URI(endpoint).toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(timeoutSeconds * 1000);
            connection.setRequestProperty("Content-Type", contentType);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("x-request-id", UUID.randomUUID().toString());
            if (llmToken != null) {
                connection.setRequestProperty("Authorization", "Bearer " + llmToken);
            }
            connection.setDoOutput(true);
        } catch (IOException | URISyntaxException e) {
            throw new JsUserRuntimeException("image." + fn + ": cannot open a connection to "
                    + endpoint + ": " + e.getMessage(), e);
        }
        try {
            try (OutputStream os = connection.getOutputStream()) {
                os.write(body);
                os.flush();
            }
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw httpError(fn, code, connection.getResponseMessage(), endpoint,
                        errorBody(connection));
            }
            byte[] responseBody;
            try (InputStream in = connection.getInputStream()) {
                responseBody = in.readAllBytes();
            }
            return new HttpResponse(responseBody, connection.getHeaderField("x-request-id"));
        } catch (IOException e) {
            throw new JsUserRuntimeException("image." + fn + ": the request to " + endpoint
                    + " failed: " + e.getMessage(), e);
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Maps a non-200 answer onto the user-facing message.
     * @param fn function name
     * @param code HTTP status
     * @param reason HTTP reason phrase
     * @param endpoint the URL
     * @param body the answer body
     * @return the exception to throw
     */
    private JsUserRuntimeException httpError(String fn, int code, String reason, String endpoint,
                                             byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8);
        String quoted = shorten(text);
        LOG.error("image.{}: HTTP {} {} from {}: {}", fn, code, reason, endpoint, quoted);
        String detail = jsonErrorMessage(text);
        return new JsUserRuntimeException(String.format(Locale.ROOT,
                "image.%s: HTTP %d %s from %s: %s%s", fn, code, reason, endpoint, quoted,
                detail == null ? "" : " (" + detail + ")"));
    }

    /** The {@code error.message} of an OpenAI-style error body, or null. */
    private String jsonErrorMessage(String body) {
        try {
            JsonNode root = jsonMapper.readTree(body);
            String message = root.path("error").path("message").asString();
            return message == null || message.isBlank() ? null : shorten(message);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Reads the error stream of a non-200 answer (bounded). */
    private static byte[] errorBody(HttpURLConnection connection) {
        try (InputStream errorStream = connection.getErrorStream()) {
            if (errorStream == null) {
                return new byte[0];
            }
            return errorStream.readAllBytes();
        } catch (IOException e) {
            LOG.warn("image: failed to read the error stream: {}", e.getMessage());
            return new byte[0];
        }
    }

    /**
     * The host rule: without {@value #PROP_ALLOW_EXTERNAL_HOST} an endpoint (or an answered URL)
     * must point at the configured gateway's host.
     * @param prefix message prefix, e.g. {@code image.generate}
     * @param url the URL to check
     * @return the refusal sentence, or {@code null} when the host is allowed
     */
    private String refusalSentence(String prefix, String url) {
        if (allowExternalHost) {
            return null;
        }
        String host = hostOf(url);
        if (gatewayHost == null) {
            return prefix + ": not available in this environment - no gateway configured to"
                    + " compare with (" + PROP_GATEWAY_URL + " is unset); set "
                    + PROP_ALLOW_EXTERNAL_HOST + "=true only for a trusted host, or configure "
                    + PROP_GATEWAY_URL;
        }
        if (!gatewayHost.equalsIgnoreCase(host)) {
            return prefix + ": not available in this environment - '" + host + "' is not the"
                    + " configured LLM gateway '" + gatewayHost + "' (set "
                    + PROP_ALLOW_EXTERNAL_HOST + "=true only for a trusted host, or point "
                    + PROP_GEN_URL + " at the gateway)";
        }
        return null;
    }

    /** {@code host:port} of a URL (default ports normalised), or null. */
    static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(url.trim());
            String host = uri.getHost();
            if (host == null) {
                return null;
            }
            int port = uri.getPort();
            if (port < 0) {
                port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
            }
            return (host + ":" + port).toLowerCase(Locale.ROOT);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /**
     * Completes an endpoint the way the Web UI proxy does it: a base without {@code /v1} gets
     * {@code /v1/<path>} appended, a base that already names the path is taken as it is.
     * @param baseUrl configured base URL
     * @param path the endpoint path, e.g. {@code images/generations}
     * @return the endpoint URL
     */
    static String endpoint(String baseUrl, String path) {
        String base = baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/" + path)) {
            return base;
        }
        if (base.endsWith("/v1")) {
            return base + "/" + path;
        }
        return base + "/v1/" + path;
    }

    /**
     * Validates a size string ({@code WxH}).
     * @param fn function name for the message ({@code generate}/{@code edit})
     * @param size the caller's value
     * @return the value, normalised to {@code WxH}
     */
    static String size(String fn, String size) {
        if (size == null || size.isBlank()) {
            throw new JsUserRuntimeException("image." + fn
                    + ": size must look like '1024x1024' (got an empty value)");
        }
        String[] parts = size.trim().toLowerCase(Locale.ROOT).split("x", -1);
        if (parts.length == 2) {
            try {
                int width = Integer.parseInt(parts[0].trim());
                int height = Integer.parseInt(parts[1].trim());
                if (width >= 16 && height >= 16 && width <= MAX_SIDE && height <= MAX_SIDE) {
                    return width + "x" + height;
                }
            } catch (NumberFormatException e) {
                // falls through to the message below
            }
        }
        throw new JsUserRuntimeException(String.format(Locale.ROOT, "image.%s: size '%s' is not"
                + " usable - expected 'WxH' like '1024x1024', each side between 16 and %d"
                + " pixels", fn, size, MAX_SIDE));
    }

    /**
     * Builds a {@code multipart/form-data} body: text fields in insertion order, then one part
     * per picture - base64, <em>without</em> {@code filename} and without {@code Content-Type},
     * because the reference server parses those as plain form values.
     * @param boundary the boundary
     * @param fields the text fields
     * @param imageField name of the repeated picture field
     * @param images the pictures
     * @return the body
     */
    static byte[] multipartBody(String boundary, Map<String, String> fields, String imageField,
                                List<byte[]> images) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            writeAscii(out, "--" + boundary + "\r\n");
            writeAscii(out, "Content-Disposition: form-data; name=\"" + field.getKey()
                    + "\"\r\n\r\n");
            out.writeBytes(field.getValue().getBytes(StandardCharsets.UTF_8));
            writeAscii(out, "\r\n");
        }
        for (byte[] image : images) {
            writeAscii(out, "--" + boundary + "\r\n");
            writeAscii(out, "Content-Disposition: form-data; name=\"" + imageField
                    + "\"\r\n\r\n");
            writeAscii(out, Base64.getEncoder().encodeToString(image));
            writeAscii(out, "\r\n");
        }
        writeAscii(out, "--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    /** A response as the parser needs it: body plus the echoed request id. */
    private record HttpResponse(byte[] body, String requestId) {
    }

    /** First non-blank value. */
    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    /** A trimmed value, or null when blank. */
    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Resolves a property through the injected Spring environment with a -D fallback. */
    private static String get(String key) {
        Environment env = environment;
        return env != null ? env.getProperty(key) : System.getProperty(key);
    }

    /** Reads a boolean property strictly, aborting on anything else. */
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

    /** Reads an optional int property with a range check; an invalid value aborts. */
    private static Integer intProperty(String key, int min, int max) {
        String value = get(key);
        if (value == null || value.isBlank()) {
            return null;
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

    /** The strict-validation error: property name plus the quoted value. */
    private static JsUserRuntimeException invalidProperty(String key, String value,
                                                          String expected) {
        return new JsUserRuntimeException(String.format(Locale.ROOT,
                "image: property %s = '%s' is invalid - expected %s (refusing to guess a"
                        + " default)", key, value, expected));
    }

    /** Shortens a text for messages. */
    private static String shorten(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > ERROR_BODY_QUOTE
                ? text.substring(0, ERROR_BODY_QUOTE) + "..." : text;
    }

    /** An integer field of the answer, or {@code null} when absent. */
    private static Integer intOrNull(JsonNode node) {
        if (node == null || !node.isNumber()) {
            return null;
        }
        return node.asInt();
    }
}
