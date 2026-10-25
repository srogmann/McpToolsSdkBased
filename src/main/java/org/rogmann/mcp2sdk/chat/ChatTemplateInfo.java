package org.rogmann.mcp2sdk.chat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The chat template of the configured model and the reasoning translations that depend on it:
 * which template {@code /props} serves, whether it declares thinking support, which
 * {@code reasoning_effort} labels it accepts, and how a thinking budget of the Web UI becomes a
 * label the backend understands (llama.cpp via {@code chat_template_kwargs}, vLLM via the
 * DeepSeek OpenAI format).
 *
 * <p>Moved out of {@code WebUiProxy} unchanged (docs/webui.md R2); the caches are the same ones,
 * including "empty means the template declares no labels, null means not looked up yet". The
 * model name is a parameter instead of a field read, because the same preparation code has to
 * run for the parent request and for a forked child request
 * ({@code ChatRequestPreparer}).</p>
 */
final class ChatTemplateInfo {

    /** Logger */
    private static final Logger LOG = LoggerFactory.getLogger(ChatTemplateInfo.class);

    /** Mapper for the reasoning fields of a request body. */
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

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
     * {@link #templateEfforts(String)}, mirroring {@link #chatTemplateCache}.
     */
    private List<String> templateEffortsCache = null;

    /** Map from model-name to reasoning-mode */
    private final ConcurrentMap<String, String> mapReasoning = new ConcurrentHashMap<>();

    /**
     * The reasoning-effort labels the chat template accepts - the cached result of
     * {@link #templateEfforts(String)}, available for components that show or check the labels
     * without translating a request (docs/webui.md R2).
     *
     * @param modelName the configured model name
     * @return the declared labels in template order, empty if none are declared
     */
    List<String> efforts(String modelName) {
        return templateEfforts(modelName);
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
     * @param modelName     value of the model-name property, the key the template is looked up under
     * @param reasoningMode value of {@code webui.model.reasoning} (on/off/auto)
     * @return the chat template to serve, or null to omit it (reasoning detection off)
     */
    String propsChatTemplate(String modelName, String reasoningMode) {
        String stored = loadChatTemplate(modelName);
        boolean storedThinks = templateSupportsThinking(stored, modelName);
        return switch (normalizeReasoningMode(reasoningMode)) {
            case "on" -> storedThinks ? stored : DEFAULT_THINKING_TEMPLATE;
            case "off" -> storedThinks ? DEFAULT_NON_THINKING_TEMPLATE : stored;
            default -> stored; // auto
        };
    }

    /**
     * Normalizes the reasoning mode property to on/off/auto (unknown values fall back to auto).
     */
    private static String normalizeReasoningMode(String mode) {
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
     * @param modelName the configured model name, normalized to the resource key
     * @return the template content, or null if no template matches the model
     */
    private String loadChatTemplate(String modelName) {
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
    private static String normalizeModelName(String name) {
        String n = (name == null ? "" : name);
        n = n.replaceAll("[^A-Za-z0-9-]+", "_").replaceAll("^_+|_+$", "");
        return n;
    }

    /**
     * Heuristic check whether a chat template signals thinking/reasoning support,
     * mirroring the detection used by the Web UI frontend.
     *
     * @param template template to inspect, may be null
     * @param modelName  the configured model name, key of the marker log line
     */
    private boolean templateSupportsThinking(String template, String modelName) {
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
     * @param modelName  the configured model name, the key the chat template is looked up under
     */
    void injectReasoningEffortFromBudget(ObjectNode llmRequest, String modelName) {
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
        String effort = reasoningEffortForBudget(budget, modelName);
        if (effort == null) {
            return;
        }

        kwargsObj.put("reasoning_effort", effort);
        LOG.debug("Injected reasoning_effort '{}' from budget {} (template labels {})",
                effort, budget, templateEfforts(modelName));
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
     * @param modelName  the configured model name, the key the chat template is looked up under
     */
    void applyVllmReasoning(ObjectNode llmRequest, String modelName) {
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
            effort = enableThinking ? vllmEffortForBudget(reasoningBudget(llmRequest), modelName) : null;
        }


        ObjectNode thinking = jsonMapper.createObjectNode();
        thinking.put("type", enableThinking ? "enabled" : "disabled");
        llmRequest.set("thinking", thinking);

        if (effort != null && !effort.isEmpty()) {
            llmRequest.put("reasoning_effort", effort);
            LOG.debug("Set vLLM reasoning_effort '{}' (template labels {})", effort, templateEfforts(modelName));
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
     * {@link #templateEfforts(String)}), so a template that uses a different vocabulary is served a label
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
     * @param budget    the raw budget value (-1 when not present)
     * @param modelName the configured model name, whose template declares the labels
     * @return the effort label, or {@code null} if the budget is not a known level
     */
    private String reasoningEffortForBudget(int budget, String modelName) {
        return ReasoningEfforts.effortForBudget(budget, ReasoningEfforts.EFFORT_BY_BUDGET,
                templateEfforts(modelName));
    }

    /**
     * Maps a thinking/reasoning token budget to the {@code reasoning_effort} label for a vLLM
     * (DeepSeek OpenAI-format) backend. DeepSeek conventionally distinguishes {@code low},
     * {@code high} and {@code max} and has no {@code medium}, so the UI's {@code medium} and
     * {@code high} are both raised to {@code high} (per convention only {@code max} reaches
     * {@code max}).
     *
     * <p>As in {@link #reasoningEffortForBudget(int, String)} the labels actually declared by the model's
     * chat template take precedence over that convention: a template that accepts {@code medium}
     * gets {@code medium}, and one that has no {@code max} (Qwen3.8-Flash-Next:
     * {@code xhigh}/{@code medium}/{@code low}) receives its top level instead of a rejected
     * {@code max}.</p>
     *
     * @param budget    the raw budget value (-1 when not present)
     * @param modelName the configured model name, whose template declares the labels
     * @return the DeepSeek effort label, or {@code null} if the budget is not a known level
     */
    private String vllmEffortForBudget(int budget, String modelName) {
        return ReasoningEfforts.effortForBudget(budget, ReasoningEfforts.VLLM_EFFORT_BY_BUDGET,
                templateEfforts(modelName));
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
     * @param modelName the configured model name
     * @return the declared labels in template order, empty if none are declared
     */
    private synchronized List<String> templateEfforts(String modelName) {
        if (templateEffortsCache == null) {
            String template = loadChatTemplate(modelName);
            templateEffortsCache = ReasoningEfforts.parseTemplateEfforts(template);
            if (templateEffortsCache.isEmpty()) {
                LOG.info("Model {}: chat template declares no reasoning_effort labels, keeping the "
                        + "backend default labels", modelName);
            } else {
                LOG.info("Model {}: chat template accepts reasoning effort labels {}",
                        modelName, templateEffortsCache);
            }
        }
        return templateEffortsCache;
    }
}
