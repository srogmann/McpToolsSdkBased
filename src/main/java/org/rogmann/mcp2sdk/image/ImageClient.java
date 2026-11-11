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
 * ({@code POST /v1/images/edits}, {@code multipart/form-data} or JSON with a data URL) - the
 * counterpart of {@link VisionClient} for the drawing side, with the same rules: one request, no
 * session, no state across calls, plain {@link HttpURLConnection}, no new dependency.
 * <p>
 * The <em>native</em> request shape follows the reference server this project talks to
 * ({@code server_qwen_image21.py}, mirrored by {@code image_edit_client.py}): the generation
 * body carries {@code model}, {@code prompt}, {@code output_format} and optionally
 * {@code size}, {@code seed} and {@code num_inference_steps}; the edit is a multipart form whose
 * {@code image} fields hold <strong>bare base64 strings</strong> (no {@code filename}, no
 * {@code Content-Type} part - the server parses them as plain form values, and a data URL would
 * be rejected). The answer is {@code {data: [{b64_json}], usage: {width, height, seed}}}; a
 * {@code data[0].url} answer is downloaded here (a {@code data:} URL in that field is decoded
 * inline, see below) - through the host rule, which compares an answered URL with the configured
 * endpoint's host.
 * </p>
 *
 * <h3>Two wire dialects, and why both are here</h3>
 * <p>
 * The <em>legacy</em> dialect is the one a parallel fork of this module used, and the only one
 * that has been proven against a live gateway: the generation body always carries {@code n},
 * {@code seed}, {@code steps}, {@code cfg_scale}, {@code disable_safety_checker} and
 * {@code negative_prompt} (and never {@code output_format}; {@code size} only when the caller
 * names one), and the edit is a JSON body whose single {@code image} field holds a
 * {@code data:image/…;base64,…} URL. The two dialects are <strong>not interchangeable</strong>:
 * each server answers one of them with a rejection of the other (the reference server parses
 * {@code image} as a plain form value and refuses multipart, and a data URL is not a form value
 * at all).
 * </p>
 * <p>
 * {@value #PROP_DIALECT} and {@value #PROP_EDIT_DIALECT} pick the dialect per call, and both
 * <strong>default to {@code legacy}</strong> - the variant that demonstrably works - while
 * {@code native} stays opt-in until it has had its first live run (docs/js/image.md §11). A
 * caller that reads the dialect sees it in the messages: options a dialect cannot carry are
 * refused by name, never dropped in silence.
 * </p>
 *
 * <h3>Configuration (application.yml or {@code -D}, resolved through the Spring
 * {@link Environment})</h3>
 * <ul>
 *   <li>{@value #PROP_MODEL} / {@value #PROP_EDIT_MODEL} - the diffusion model; there is
 *       deliberately <em>no</em> default (servers differ: {@code Qwen/Qwen-Image-2.1}, flux
 *       variants, …), so an unconfigured model is a clear message and not a wrong guess,
 *       {@value #PROP_EDIT_MODEL} falls back to {@value #PROP_MODEL},</li>
 *   <li>{@value #PROP_URL} - base URL of the diffusion endpoint; the default is the current
 *       model's gateway ({@code webui.model.url}), completed with
 *       {@code /v1/images/generations} resp. {@code /v1/images/edits} the way the Web UI proxy
 *       completes its paths. The property is the way to give the diffusion models their own
 *       server,</li>
 *   <li>{@value #PROP_SIZE} - default {@code size} ({@code "WxH"}, e.g. {@code 1024x1024}); unset
 *       means "send no size", so the server's own default applies (the {@code legacy} edit body
 *       never carries one),</li>
 *   <li>{@value #PROP_STEPS} - default step count ({@code num_inference_steps} resp.
 *       {@code steps}); unset means "send nothing", so the server's own default applies (4 for a
 *       distilled flux, 40 for Qwen - no number of ours can be right for both). The legacy
 *       generation body always carries {@code steps}, so an unset value means the fork's
 *       {@value #LEGACY_STEPS} there,</li>
 *   <li>{@value #PROP_DIALECT} / {@value #PROP_EDIT_DIALECT} - the wire dialect of
 *       {@code image.generate} resp. {@code image.edit}: {@code legacy} (default) or
 *       {@code native}; {@value #PROP_EDIT_DIALECT} falls back to {@value #PROP_DIALECT}, any
 *       other value aborts with the property name,</li>
 *   <li>{@value #PROP_ALLOW_EXTERNAL_HOST} - the safety switch for a {@code url} in an
 *       <em>answer</em>: without it that URL must point at the configured endpoint's host (the
 *       endpoint itself is configuration and is used as set - that is how the diffusion models
 *       get a server of their own),</li>
 *   <li>{@value #PROP_TOKEN} - the API key of a diffusion server of its own, sent as an
 *       {@code Authorization: Bearer} header; unset means "no key" (but see the next bullet),</li>
 *   <li>{@code webui.llm.token} - the gateway's key, the one the Web UI proxy sends. It goes out
 *       only while the endpoint <em>is</em> the gateway: an operator who points
 *       {@value #PROP_URL} at another host must not hand that credential to it by accident.
 *       Such a request then carries no {@code Authorization} header, and a 401/403 answer says
 *       why instead of leaving the operator guessing.</li>
 * </ul>
 * <p>
 * The timeout is {@link ImageLimits#timeoutSeconds()} (default 55 s, below the sandbox's 60 s
 * script timeout) unless the call passes {@code timeoutSeconds}; a diffusion run that needs
 * longer needs both raised - the help text says so.
 * </p>
 */
public class ImageClient {

    private static final Logger LOG = LoggerFactory.getLogger(ImageClient.class);

    /** Base URL of the diffusion endpoint (default: the current model's gateway). */
    static final String PROP_URL = "mcp.js.image.url";
    /** Diffusion model of {@code image.generate} - no default, servers differ. */
    static final String PROP_MODEL = "mcp.js.image.model";
    /** Diffusion model of {@code image.edit} (default: {@value #PROP_MODEL}). */
    static final String PROP_EDIT_MODEL = "mcp.js.image.edit.model";
    /** Default {@code size} of a request ("WxH"); unset means "send no size". */
    static final String PROP_SIZE = "mcp.js.image.size";
    /** Default step count; unset means "send nothing" ({@code native}) resp. 4 ({@code legacy}). */
    static final String PROP_STEPS = "mcp.js.image.steps";
    /** Wire dialect: {@code legacy} (default) or {@code native}. */
    static final String PROP_DIALECT = "mcp.js.image.dialect";
    /** Wire dialect of {@code image.edit} (default: {@value #PROP_DIALECT}). */
    static final String PROP_EDIT_DIALECT = "mcp.js.image.edit.dialect";
    /** Allow an answered {@code url} that is not on the configured endpoint's host. */
    static final String PROP_ALLOW_EXTERNAL_HOST = "mcp.js.image.allowExternalHost";
    /** Dialect value: the fork's wire format (all fields always sent, JSON edit with a data URL). */
    static final String DIALECT_LEGACY = "legacy";
    /** Dialect value: the reference server's wire format ({@code output_format}, multipart). */
    static final String DIALECT_NATIVE = "native";
    /** The current model's gateway - the base the endpoints fall back to. */
    static final String PROP_GATEWAY_URL = "webui.model.url";
    /** API key for a diffusion server of its own (default: the gateway's key, see below). */
    static final String PROP_TOKEN = "mcp.js.image.token";
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
    /** {@code steps} the legacy generation dialect sends when nothing else is configured. */
    private static final int LEGACY_STEPS = 4;
    /** {@code cfg_scale} of the legacy generation dialect (a distilled model needs no guidance). */
    private static final double LEGACY_CFG_SCALE = 1.0d;

    /** The Spring environment, injected by McpConfig (same pattern as the other tools). */
    private static volatile Environment environment;

    private final String genEndpoint;
    private final String editEndpoint;
    private final String model;
    private final String editModel;
    private final Integer steps;
    private final String size;
    private final boolean legacyGeneration;
    private final boolean legacyEdit;
    private final boolean allowExternalHost;
    private final String endpointHost;
    /** The key sent to the endpoint, or {@code null} for "no Authorization header". */
    private final String endpointToken;
    /** True when {@value #PROP_LLM_TOKEN} is configured but withheld (another host). */
    private final boolean gatewayTokenWithheld;
    private final int defaultTimeoutSeconds;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    /** One text-to-image request as the caller built it. */
    public record GenerateRequest(String prompt, String model, String size, Integer seed,
                                  Integer steps, String outputFormat, String negativePrompt,
                                  Integer timeoutSeconds) {
    }

    /** One edit request: prompt plus the source picture(s) - bare base64 in the multipart form,
     *  a single {@code data:} URL in the legacy JSON body. */
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
        this.model = trimToNull(get(PROP_MODEL));
        this.editModel = trimToNull(get(PROP_EDIT_MODEL));
        this.steps = intProperty(PROP_STEPS, 1, 1000);
        this.size = sizeProperty(PROP_SIZE);
        String dialect = dialectProperty(PROP_DIALECT, DIALECT_LEGACY);
        this.legacyGeneration = DIALECT_LEGACY.equals(dialect);
        this.legacyEdit = DIALECT_LEGACY.equals(dialectProperty(PROP_EDIT_DIALECT, dialect));
        this.allowExternalHost = booleanProperty(PROP_ALLOW_EXTERNAL_HOST, false);
        this.defaultTimeoutSeconds = ImageLimits.current().timeoutSeconds();
        String gateway = trimToNull(get(PROP_GATEWAY_URL));
        String base = firstNonBlank(get(PROP_URL), gateway);
        // The endpoint is configuration, not caller input - so its host is the one an answered
        // url has to match; a diffusion server of its own (any host) is just a property.
        this.endpointHost = hostOf(base);
        this.genEndpoint = base == null ? null : endpoint(base, GEN_PATH);
        // Both dialects address the edit below the same base; the legacy one completes the
        // path the same way (the fork's "complete URL" reading is gone with its property name).
        this.editEndpoint = base == null ? null : endpoint(base, EDIT_PATH);
        // The key follows the same split as the URL: a server of its own gets its own key, and
        // the gateway's key goes to the gateway only - an operator who points the module at a
        // foreign host must not hand it the credential by accident (the mirror image of the
        // answer-side host rule above).
        String ownToken = trimToNull(get(PROP_TOKEN));
        String gatewayToken = trimToNull(get(PROP_LLM_TOKEN));
        if (ownToken != null) {
            this.endpointToken = ownToken;
            this.gatewayTokenWithheld = false;
        } else if (gatewayToken != null && endpointHost != null
                && endpointHost.equals(hostOf(gateway))) {
            this.endpointToken = gatewayToken;
            this.gatewayTokenWithheld = false;
        } else {
            this.endpointToken = null;
            this.gatewayTokenWithheld = gatewayToken != null;
            if (gatewayTokenWithheld) {
                LOG.debug("image: the diffusion endpoint ({}) is not the gateway ({}) - {} is not"
                        + " sent; set {} for a server of its own", endpointHost, hostOf(gateway),
                        PROP_LLM_TOKEN, PROP_TOKEN);
            }
        }
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
     * @return whether {@code image.generate} uses the legacy dialect, i.e. whether the request
     *         carries a {@code negative_prompt} (the native body has no such field)
     */
    public boolean sendsNegativePrompt() {
        return legacyGeneration;
    }

    /**
     * @return whether {@code image.edit} uses the legacy dialect (a JSON body with one data URL,
     *         so exactly one source picture and no {@code size}/{@code seed}/{@code steps}/
     *         {@code output_format})
     */
    public boolean usesLegacyEdit() {
        return legacyEdit;
    }

    /**
     * Display-only check for the module summary: is a generation endpoint resolvable and a model
     * configured? Lenient by design (it runs while the tool is being set up - the strict
     * validation happens in the constructor).
     * @return true when {@code image.generate} would be offered
     */
    public static boolean isConfigured() {
        if (trimToNull(get(PROP_MODEL)) == null) {
            return false;
        }
        // The URL is configuration: a diffusion server of its own is a legitimate setting, so
        // the availability probe only asks whether one is configured at all.
        return firstNonBlank(get(PROP_URL), get(PROP_GATEWAY_URL)) != null;
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
     * Why generation is not available here - the sentence the module puts into its message. This
     * is the configuration probe (no request): a model that only the <em>call</em> names is not
     * considered here, but {@code generate} does consider it.
     * @param fn function name for the message ({@code null} for a plain probe)
     * @return the reason, or {@code null} when the endpoint may be used
     */
    public String generationUnavailableReason(String fn) {
        return generationUnavailableReason(fn, null);
    }

    /**
     * Why a generation request cannot run - the sentence the module puts into its message.
     * @param fn function name for the message ({@code null} for a plain probe)
     * @param requestModel the model the request names, may be null (a per-call model is enough:
     *                     the message promises {@code {model: '…'}})
     * @return the reason, or {@code null} when the request may be sent
     */
    private String generationUnavailableReason(String fn, String requestModel) {
        String prefix = fn == null ? "image" : "image." + fn;
        if (firstNonBlank(requestModel, model) == null) {
            return prefix + ": no diffusion model configured - set " + PROP_MODEL
                    + " (e.g. 'Qwen/Qwen-Image-2.1') or pass {model: '…'}";
        }
        if (genEndpoint == null) {
            return prefix + ": image generation is not available in this environment - neither "
                    + PROP_URL + " nor " + PROP_GATEWAY_URL + " is set";
        }
        return null;
    }

    /**
     * Why editing is not available here (the configuration probe, no request).
     * @param fn function name for the message ({@code null} for a plain probe)
     * @return the reason, or {@code null} when the endpoint may be used
     */
    public String editUnavailableReason(String fn) {
        return editUnavailableReason(fn, null);
    }

    /**
     * Why an edit request cannot run.
     * @param fn function name for the message ({@code null} for a plain probe)
     * @param requestModel the model the request names, may be null
     * @return the reason, or {@code null} when the request may be sent
     */
    private String editUnavailableReason(String fn, String requestModel) {
        String prefix = fn == null ? "image" : "image." + fn;
        if (firstNonBlank(requestModel, editModel, model) == null) {
            return prefix + ": no diffusion model configured - set " + PROP_MODEL + " or "
                    + PROP_EDIT_MODEL + " (e.g. 'Qwen/Qwen-Image-2.1') or pass {model: '…'}";
        }
        if (editEndpoint == null) {
            return prefix + ": image editing is not available in this environment - neither "
                    + PROP_URL + " nor " + PROP_GATEWAY_URL + " is set";
        }
        return null;
    }

    /**
     * POSTs one text-to-image request and returns the picture.
     * @param request the request
     * @return the result
     */
    public Result generate(GenerateRequest request) {
        String reason = generationUnavailableReason("generate", request.model());
        if (reason != null) {
            throw new JsUserRuntimeException(reason);
        }
        String effectiveModel = firstNonBlank(request.model(), model);
        Integer effectiveSteps = request.steps() != null ? request.steps() : steps;
        String effectiveSize = request.size() != null ? request.size() : size;
        String requestBody = legacyGeneration
                ? legacyGenerateBody(request, effectiveModel, effectiveSteps, effectiveSize)
                : nativeGenerateBody(request, effectiveModel, effectiveSteps, effectiveSize);
        LOG.debug("image.generate: POST {} ({} dialect, model {}, size {}, steps {})", genEndpoint,
                legacyGeneration ? DIALECT_LEGACY : DIALECT_NATIVE, effectiveModel, effectiveSize,
                effectiveSteps);
        HttpResponse response = post("generate", genEndpoint, "application/json",
                requestBody.getBytes(StandardCharsets.UTF_8), timeoutOf(request.timeoutSeconds()));
        return parseResponse("generate", response, effectiveModel);
    }

    /**
     * The body of the native dialect: {@code model}, {@code prompt}, {@code output_format} and
     * only those of {@code num_inference_steps}, {@code size}, {@code seed} the caller (or a
     * property) actually sets - the server's own defaults win over invented numbers.
     * @param request the request
     * @param model the model to send
     * @param steps the effective step count, may be null
     * @param size the effective size, may be null
     * @return the JSON body
     */
    private String nativeGenerateBody(GenerateRequest request, String model, Integer steps,
                                      String size) {
        ObjectNode body = jsonMapper.createObjectNode();
        body.put("model", model);
        body.put("prompt", request.prompt());
        body.put("output_format", outputFormat(request.outputFormat()));
        if (steps != null) {
            body.put("num_inference_steps", steps.intValue());
        }
        if (size != null) {
            body.put("size", size);
        }
        if (request.seed() != null) {
            body.put("seed", request.seed().intValue());
        }
        return jsonMapper.writeValueAsString(body);
    }

    /**
     * The body of the legacy dialect: the fork sent every field every time, with fixed values for
     * the ones a caller usually leaves alone ({@code n: 1}, {@code cfg_scale: 1.0},
     * {@code disable_safety_checker: true}, a {@code steps} default of {@value #LEGACY_STEPS},
     * {@code seed: 0}, an empty {@code negative_prompt}) and no {@code output_format}. The
     * {@code size} field is sent when the caller or {@value #PROP_SIZE} names one - the old body
     * always carried a size, and that is what the property is for; without it the field stays out
     * instead of inventing a value.
     * @param request the request
     * @param model the model to send
     * @param steps the effective step count, may be null
     * @param size the effective size, may be null
     * @return the JSON body
     */
    private String legacyGenerateBody(GenerateRequest request, String model, Integer steps,
                                      String size) {
        requireNoFormat("generate", request.outputFormat(), PROP_DIALECT);
        ObjectNode body = jsonMapper.createObjectNode();
        body.put("prompt", request.prompt());
        body.put("model", model);
        body.put("n", 1);
        body.put("seed", request.seed() != null ? request.seed().intValue() : 0);
        if (size != null) {
            body.put("size", size);
        }
        body.put("steps", steps != null ? steps.intValue() : LEGACY_STEPS);
        body.put("cfg_scale", LEGACY_CFG_SCALE);
        body.put("disable_safety_checker", true);
        String negativePrompt = request.negativePrompt() == null ? "" : request.negativePrompt();
        body.put("negative_prompt", negativePrompt);
        return jsonMapper.writeValueAsString(body);
    }

    /**
     * POSTs one edit request - the shape depends on the dialect: a JSON body with one
     * {@code data:} URL (legacy) or a {@code multipart/form-data} body with bare base64 image
     * fields (native).
     * @param request the request
     * @return the result
     */
    public Result edit(EditRequest request) {
        String reason = editUnavailableReason("edit", request.model());
        if (reason != null) {
            throw new JsUserRuntimeException(reason);
        }
        String effectiveModel = firstNonBlank(request.model(), editModel, model);
        Integer effectiveSteps = request.steps() != null ? request.steps() : steps;
        String effectiveSize = request.size() != null ? request.size() : size;
        int sources = request.images() == null ? 0 : request.images().size();
        String contentType;
        byte[] body;
        if (legacyEdit) {
            contentType = "application/json";
            body = legacyEditBody(request, effectiveModel);
        } else {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("model", effectiveModel);
            fields.put("prompt", request.prompt());
            fields.put("output_format", outputFormat(request.outputFormat()));
            if (effectiveSize != null) {
                fields.put("size", effectiveSize);
            }
            if (effectiveSteps != null) {
                fields.put("num_inference_steps", effectiveSteps.toString());
            }
            if (request.seed() != null) {
                fields.put("seed", request.seed().toString());
            }
            // The reference server reads "image" as plain form values (base64, no file name, no
            // content type) and accepts the field more than once - one value per source picture.
            String boundary = "----mcpimage" + UUID.randomUUID().toString().replace("-", "");
            body = multipartBody(boundary, fields, "image",
                    request.images() == null ? List.of() : request.images());
            contentType = "multipart/form-data; boundary=" + boundary;
        }
        LOG.debug("image.edit: POST {} ({} dialect, model {}, {} source image(s))", editEndpoint,
                legacyEdit ? DIALECT_LEGACY : DIALECT_NATIVE, effectiveModel, sources);
        HttpResponse response = post("edit", editEndpoint, contentType, body,
                timeoutOf(request.timeoutSeconds()));
        return parseResponse("edit", response, effectiveModel);
    }

    /**
     * The body of the legacy dialect: {@code {prompt, model, image}} with a single
     * {@code data:image/…;base64,…} URL - the older endpoint had no size, seed, step count or
     * output format and no second picture, so asking for one of those is refused here instead of
     * being dropped on the way out. {@value #PROP_SIZE} and {@value #PROP_STEPS} are global
     * settings, not per-call wishes, so they do not turn into a refusal: this body simply has no
     * such field (they reach the generation and the native edit).
     * @param request the request
     * @param model the model to send
     * @return the JSON body
     */
    private byte[] legacyEditBody(EditRequest request, String model) {
        requireNoFormat("edit", request.outputFormat(), PROP_EDIT_DIALECT);
        requireNotSent("edit", "size", request.size() != null);
        requireNotSent("edit", "steps", request.steps() != null);
        requireNotSent("edit", "seed", request.seed() != null);
        List<byte[]> images = request.images() == null ? List.of() : request.images();
        if (images.size() != 1) {
            throw new JsUserRuntimeException("image.edit: the " + DIALECT_LEGACY + " dialect"
                    + " (" + PROP_EDIT_DIALECT + "=" + DIALECT_LEGACY + ") sends exactly one"
                    + " source picture in a JSON body, but " + images.size() + " were passed -"
                    + " pass one, or set " + PROP_EDIT_DIALECT + "=" + DIALECT_NATIVE
                    + " for the multipart form");
        }
        ObjectNode body = jsonMapper.createObjectNode();
        body.put("prompt", request.prompt());
        body.put("model", model);
        body.put("image", dataUrl(images.get(0)));
        return jsonMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
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
     * Refuses {@code format} in a dialect that has no {@code output_format} field - an option
     * that cannot travel is named, not swallowed.
     * @param fn function name
     * @param format the caller's format, may be null
     * @param dialectProperty the property that would change the dialect
     */
    private static void requireNoFormat(String fn, String format, String dialectProperty) {
        if (format != null && !format.isBlank()) {
            throw new JsUserRuntimeException("image." + fn + ": 'format' cannot be sent - the"
                    + " wire dialect in use (" + dialectProperty + "=" + DIALECT_LEGACY
                    + ") has no output_format field; drop the option or set " + dialectProperty
                    + "=" + DIALECT_NATIVE);
        }
    }

    /**
     * Refuses an option the legacy edit body cannot carry ({@code size}, {@code seed},
     * {@code steps}).
     * @param fn function name
     * @param option the option name
     * @param given whether the caller passed it
     */
    private static void requireNotSent(String fn, String option, boolean given) {
        if (given) {
            throw new JsUserRuntimeException("image." + fn + ": '" + option + "' cannot be sent -"
                    + " the " + DIALECT_LEGACY + " edit body (" + PROP_EDIT_DIALECT + "="
                    + DIALECT_LEGACY + ") carries prompt, model and image only; drop the option"
                    + " or set " + PROP_EDIT_DIALECT + "=" + DIALECT_NATIVE);
        }
    }

    /**
     * The source picture as a {@code data:} URL of the legacy dialect (one line, base64).
     * @param image the picture
     * @return {@code data:<type>;base64,<bytes>}
     */
    static String dataUrl(byte[] image) {
        return "data:" + mimeType(image) + ";base64,"
                + Base64.getEncoder().encodeToString(image);
    }

    /**
     * Sniffs the container type of a picture - the data URL needs one, and the caller hands over
     * bytes only. Unknown containers are sent as {@code image/png} (a server that decodes the
     * base64 does not depend on the type).
     * @param image the picture
     * @return the MIME type
     */
    static String mimeType(byte[] image) {
        if (image.length >= 4 && (image[0] & 0xFF) == 0x89 && image[1] == 'P' && image[2] == 'N'
                && image[3] == 'G') {
            return "image/png";
        }
        if (image.length >= 2 && (image[0] & 0xFF) == 0xFF && (image[1] & 0xFF) == 0xD8) {
            return "image/jpeg";
        }
        if (startsWithAscii(image, 0, "GIF8")) {
            return "image/gif";
        }
        if (startsWithAscii(image, 0, "RIFF") && startsWithAscii(image, 8, "WEBP")) {
            return "image/webp";
        }
        if (startsWithAscii(image, 0, "BM")) {
            return "image/bmp";
        }
        return "image/png";
    }

    /** Whether the bytes at an offset are the given ASCII text. */
    private static boolean startsWithAscii(byte[] bytes, int offset, String text) {
        if (bytes.length < offset + text.length()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if ((bytes[offset + i] & 0xFF) != text.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Decodes a {@code data:…;base64,…} URL from the answer - the older dialect handed the
     * picture back that way, and a server that inlines it must not send the module off to a
     * {@code GET} on a URL without a host.
     * @param fn function name for messages
     * @param url the data URL
     * @return the decoded bytes
     */
    private byte[] decodeDataUrl(String fn, String url) {
        int comma = url.indexOf(',');
        String meta = comma < 0 ? "" : url.substring(0, comma);
        if (comma < 0 || !meta.toLowerCase(Locale.ROOT).endsWith(";base64")) {
            throw new JsUserRuntimeException("image." + fn + ": the endpoint answered with a data"
                    + " URL that is not base64-encoded: " + shorten(url));
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(url.substring(comma + 1).trim());
        } catch (IllegalArgumentException e) {
            throw new JsUserRuntimeException("image." + fn + ": the endpoint answered with a data"
                    + " URL whose base64 cannot be decoded: " + e.getMessage(), e);
        }
        long max = ImageLimits.current().maxOutputBytes();
        if (bytes.length > max) {
            throw new JsUserRuntimeException("image." + fn + ": the picture in the answer has "
                    + bytes.length + " bytes, exceeding " + max + " bytes (property "
                    + ImageLimits.PROP_MAX_OUTPUT_BYTES + ")");
        }
        return bytes;
    }

    /**
     * Parses the answer document: {@code data[0].b64_json} (or {@code data[0].url}, which is
     * downloaded here, or decoded inline when it is a {@code data:} URL) plus {@code usage}.
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
            bytes = url.trim().toLowerCase(Locale.ROOT).startsWith("data:")
                    ? decodeDataUrl(fn, url.trim()) : download(fn, url);
        }
        JsonNode usage = root.path("usage");
        return new Result(bytes, null, model, intOrNull(usage.path("seed")),
                intOrNull(usage.path("width")), intOrNull(usage.path("height")),
                response.requestId());
    }

    /**
     * Downloads the picture of a {@code url} answer - through the host rule (a gateway that
     * answered with a foreign URL would otherwise turn the tool into a fetcher for any host).
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
            if (endpointToken != null) {
                connection.setRequestProperty("Authorization", "Bearer " + endpointToken);
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
            if (endpointToken != null) {
                connection.setRequestProperty("Authorization", "Bearer " + endpointToken);
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
                "image.%s: HTTP %d %s from %s: %s%s%s", fn, code, reason, endpoint, quoted,
                detail == null ? "" : " (" + detail + ")", withheldTokenHint(code)));
    }

    /**
     * The sentence that explains an authentication refusal when the request went out without a
     * header on purpose - a 401 the operator cannot explain is worse than a longer message.
     * @param code the HTTP status of the answer
     * @return the hint, or an empty string
     */
    private String withheldTokenHint(int code) {
        if (!gatewayTokenWithheld || (code != HttpURLConnection.HTTP_UNAUTHORIZED
                && code != HttpURLConnection.HTTP_FORBIDDEN)) {
            return "";
        }
        return "; the request carried no Authorization header - " + PROP_LLM_TOKEN
                + " is only sent to the gateway (" + PROP_GATEWAY_URL + "), set " + PROP_TOKEN
                + " for a diffusion server of its own";
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
     * The host rule for a URL that came out of an <em>answer</em>: without
     * {@value #PROP_ALLOW_EXTERNAL_HOST} it must point at the host of the configured endpoint -
     * otherwise a gateway's answer could turn the tool into a fetcher for any host. The endpoint
     * itself needs no check: it is configuration ({@value #PROP_URL} or the gateway), not
     * something a script can influence.
     * @param prefix message prefix, e.g. {@code image.generate}
     * @param url the answered URL to check
     * @return the refusal sentence, or {@code null} when the host is allowed
     */
    private String refusalSentence(String prefix, String url) {
        if (allowExternalHost) {
            return null;
        }
        String host = hostOf(url);
        if (endpointHost == null) {
            return prefix + ": the answered URL '" + url + "' cannot be checked - no endpoint is"
                    + " configured; set " + PROP_ALLOW_EXTERNAL_HOST + "=true only for a trusted"
                    + " host, or configure " + PROP_URL;
        }
        if (!endpointHost.equalsIgnoreCase(host)) {
            return prefix + ": the answered URL points at '" + host + "', not at the configured"
                    + " image endpoint '" + endpointHost + "' (set " + PROP_ALLOW_EXTERNAL_HOST
                    + "=true only for a trusted host)";
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
        String normalised = parseSize(size);
        if (normalised == null) {
            throw new JsUserRuntimeException(String.format(Locale.ROOT, "image.%s: size '%s' is"
                    + " not usable - expected 'WxH' like '1024x1024', each side between 16 and %d"
                    + " pixels", fn, size, MAX_SIDE));
        }
        return normalised;
    }

    /**
     * Parses a {@code WxH} size string.
     * @param size the value
     * @return the value normalised to {@code WxH}, or {@code null} when it is not usable
     */
    private static String parseSize(String size) {
        String[] parts = size.trim().toLowerCase(Locale.ROOT).split("x", -1);
        if (parts.length == 2) {
            try {
                int width = Integer.parseInt(parts[0].trim());
                int height = Integer.parseInt(parts[1].trim());
                if (width >= 16 && height >= 16 && width <= MAX_SIDE && height <= MAX_SIDE) {
                    return width + "x" + height;
                }
            } catch (NumberFormatException e) {
                // not a WxH size - the caller says what it expected
            }
        }
        return null;
    }

    /**
     * Reads the configured default size ({@value #PROP_SIZE}), validated like a caller's value;
     * unset means "send no size and let the server decide".
     * @param key property name
     * @return the normalised size, or {@code null} when the property is not set
     */
    private static String sizeProperty(String key) {
        String value = trimToNull(get(key));
        if (value == null) {
            return null;
        }
        String normalised = parseSize(value);
        if (normalised == null) {
            throw invalidProperty(key, value, "'WxH' like '1024x1024', each side between 16 and "
                    + MAX_SIDE + " pixels");
        }
        return normalised;
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

    /**
     * Reads a dialect property: {@code legacy} or {@code native}; an unset property yields the
     * fallback ({@value #DIALECT_LEGACY} for the generation, the generation's value for the
     * edit). Anything else aborts with the property name and the quoted value.
     * @param key property name
     * @param fallback the value an unset property means
     * @return the dialect value in lower case
     */
    private static String dialectProperty(String key, String fallback) {
        String value = trimToNull(get(key));
        if (value == null) {
            return fallback;
        }
        String dialect = value.toLowerCase(Locale.ROOT);
        if (DIALECT_LEGACY.equals(dialect) || DIALECT_NATIVE.equals(dialect)) {
            return dialect;
        }
        throw invalidProperty(key, value, "'" + DIALECT_LEGACY + "' or '" + DIALECT_NATIVE + "'");
    }

    /** Reads a boolean property strictly, aborting on anything else. */
    private static boolean booleanProperty(String key, boolean fallback) {        String value = get(key);
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
