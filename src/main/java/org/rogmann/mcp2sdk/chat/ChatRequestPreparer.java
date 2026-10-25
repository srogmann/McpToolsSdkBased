package org.rogmann.mcp2sdk.chat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The seam between "the body that arrived" and "the body that goes out" (docs/webui.md R4).
 *
 * <p>Both forwarding paths of {@code WebUiProxy} ran the same six steps inline, in the same order,
 * differing in exactly two fields (docs/webui.md §1.1): the buffered path forces {@code stream} to
 * {@code false}, the streaming path adds {@code stream_options}. Two inline copies of one sequence
 * are a convention, one method is an invariant - and the invariant is what the prefix fork of
 * {@code agent/PrefixForkTool} depends on: a forked child request has to run through the very same
 * transformations as its parent, otherwise the engine sees a different prompt and the whole prefix
 * cache is worthless (decision F3).</p>
 *
 * <p>What deliberately stays with the caller: the serialization ({@code writeValueAsString}) - that
 * is no semantics - and everything transport-related.</p>
 *
 * <p>Which fields are prompt-relevant and which are not is documented in docs/webui.md §3.3:
 * {@code model}, the reasoning translation and the message content decide the token prefix,
 * {@code stream_options}, {@code max_tokens} and {@code stream} only shape the answer.</p>
 */
final class ChatRequestPreparer {

    /**
     * Which forwarding path the body is prepared for. The mode decides only about the two fields the
     * paths disagree on; everything else is identical by construction.
     */
    enum Mode {
        /** Server-sent events: {@code stream} stays as the client sent it, {@code stream_options} is added. */
        STREAMING,
        /** Buffered: {@code stream} is forced to {@code false}, {@code stream_options} is left out. */
        BUFFERED
    }

    /** Backend vLLM (same spelling as the value of {@code webui.model.backend}) */
    private static final String BACKEND_VLLM = "vllm";

    /** Mapper used to build the nodes the preparation adds. */
    private final JsonMapper jsonMapper;

    /** Chat template of the configured model, the source of the reasoning translation. */
    private final ChatTemplateInfo chatTemplateInfo;

    /** Model name the proxy overwrites the client's with. */
    private final String modelName;

    /** Backend type ({@code llamacpp} default, {@code vllm}), decides the reasoning spelling. */
    private final String backend;

    /** Default {@code max_tokens} (property {@code webui.max.tokens}); {@code null} leaves the request alone. */
    private final Integer maxTokens;

    /**
     * @param jsonMapper       mapper for the nodes the preparation adds
     * @param chatTemplateInfo chat template of the configured model (shared with the proxy, so the
     *                         template is parsed once for parent and child requests alike)
     * @param modelName        value of {@code webui.model.name}
     * @param backend          value of {@code webui.model.backend}
     * @param maxTokens        value of {@code webui.max.tokens}, may be null
     */
    ChatRequestPreparer(JsonMapper jsonMapper, ChatTemplateInfo chatTemplateInfo, String modelName,
                        String backend, Integer maxTokens) {
        this.jsonMapper = jsonMapper;
        this.chatTemplateInfo = chatTemplateInfo;
        this.modelName = modelName;
        this.backend = backend;
        this.maxTokens = maxTokens;
    }

    /**
     * Prepares the incoming body for one forwarding path: deep-copy, model override, reasoning
     * translation of the backend, {@code stream} handling of the mode, {@code max_tokens} default,
     * {@code stream_options} (streaming only) and the tool-call history repair.
     *
     * <p>The incoming node is never mutated - the fork stores it as it arrived and prepares it
     * again for its own children (decision F4: the reasoning translation is not idempotent).</p>
     *
     * @param incoming parsed body of the client request
     * @param mode     target forwarding path
     * @return the deep-copied, prepared request to serialize and send
     */
    ObjectNode prepare(JsonNode incoming, Mode mode) {
        // Deep-Copy von requestNode, um alle Parameter zu übernehmen.
        ObjectNode llmRequest = (ObjectNode) incoming.deepCopy();

        // Set model name (overwrite if present)
        llmRequest.put("model", modelName);

        // Backend-specific reasoning translation:
        // - llamacpp (default): reconstruct reasoning_effort into chat_template_kwargs so
        //   template branches like DeepSeek's high/max prompt injections actually trigger.
        // - vllm: translate enable_thinking + budget into {"thinking":{"type":...}} and a
        //   top-level reasoning_effort, dropping the llama.cpp-only fields.
        if (BACKEND_VLLM.equalsIgnoreCase(backend)) {
            chatTemplateInfo.applyVllmReasoning(llmRequest, modelName);
        } else {
            chatTemplateInfo.injectReasoningEffortFromBudget(llmRequest, modelName);
        }

        // Force non-streaming for buffered mode; the streaming path passes the client's value
        // through untouched (it is what made the client choose this path in the first place).
        if (mode == Mode.BUFFERED) {
            llmRequest.put("stream", false);
        }

        // Set default max_tokens if not present
        if (!llmRequest.has("max_tokens") && maxTokens != null) {
            llmRequest.put("max_tokens", maxTokens);
        }

        // Add stream_options to include usage info (token statistics) in the streaming response.
        // include_usage       : final usage object in the last SSE chunk (OpenAI-compatible, supported by llama.cpp and vLLM)
        // continuous_usage_stats: vLLM extension - reports usage in EVERY SSE chunk so per-chunk token statistics
        //                        are available even before the stream finishes; llama.cpp ignores this field.
        // The buffered path never sends it: it reads one JSON body, not a chunk stream.
        if (mode == Mode.STREAMING) {
            ObjectNode streamOptions = jsonMapper.createObjectNode();
            streamOptions.put("include_usage", true);
            if (BACKEND_VLLM.equalsIgnoreCase(backend)) {
                streamOptions.put("continuous_usage_stats", true);
            }
            llmRequest.set("stream_options", streamOptions);
        }

        // Repair invalid tool-call arguments before forwarding so backends that validate
        // tool-call JSON (e.g. vLLM) accept the request despite a poisoned chat history.
        ToolCallHistorySanitizer.sanitize(llmRequest);

        return llmRequest;
    }
}
