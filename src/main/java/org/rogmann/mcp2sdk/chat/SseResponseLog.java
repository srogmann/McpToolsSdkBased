package org.rogmann.mcp2sdk.chat;

import org.rogmann.mcp2sdk.util.BoundedText;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Collector for the {@code webui.logResponses} DEBUG dump of an LLM response: it merges the
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
 * the reasoning-output analysis of {@code WebUiProxy} without holding a long chain of thought in memory.</p>
 *
 * <p>It is instantiated only when {@code WebUiProxy.responseLogEnabled()} answers true, so a disabled
 * switch means no buffering and no per-chunk JSON work at all. Captured text is bounded
 * ({@value #RESPONSE_LOG_MAX_CHARS_PER_PART} characters per part) - a long chain of thought
 * must not be held in memory for a debug line; the dropped characters are named in the output
 * and the whole line is shortened by {@link ResponseLogFormat#shortenResponseBody} anyway.</p>
 */
final class SseResponseLog {

    /** Max. characters captured per response part (chain-of-thought / answer) for the response
     * DEBUG log. Beyond that the remaining characters are only counted, so a long answer is not
     * held in memory just for a debug line. */
    private static final int RESPONSE_LOG_MAX_CHARS_PER_PART = 4000;

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
     * {@link ResponseLogFormat#shortenResponseBody}. Fields that never appeared in the stream are omitted.
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
