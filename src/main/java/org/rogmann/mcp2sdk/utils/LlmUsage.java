package org.rogmann.mcp2sdk.utils;

import java.time.LocalDateTime;
import java.util.List;

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
 * @param cachedTokens    number of cached/prompt tokens reused. The value is only meaningful
 *                        when {@code cachedTokensKnown} is {@code true}; otherwise it is
 *                        reported as {@code 0} for compatibility but does not prove that no
 *                        tokens were cached
 * @param cachedTokensKnown {@code true} if cached-token usage was explicitly reported by the
 *                        server or sampled by the proxy; {@code false} if it is unknown
 * @param ppUncachedTPS   prompt processing tokens per second for the tokens NOT served from
 *                        the KV cache. This is only computed when {@code cachedTokensKnown}
 *                        is {@code true}; otherwise it is {@code 0} to avoid presenting a raw
 *                        prompt rate as an uncached rate
 * @param ppTPS           prompt processing tokens per second. In the llama.cpp path this is
 *                        the server-reported {@code prompt_per_second}; in the vLLM-metrics
 *                        and wall-clock paths it is cache-corrected only if
 *                        {@code cachedTokensKnown} is {@code true}, otherwise it is a raw
 *                        total-prompt-token estimate
 * @param tgTPS           token generation tokens per second
 * @param estimated       {@code true} if any of the rates/timings had to be estimated
 *                        (wall-clock, heuristics or synthesized fallbacks) instead of being
 *                        taken from authoritative server timings/metrics
 * @param ttftClientMs    client-side measured time to the first output token (reasoning or
 *                        answer content), from request send to first SSE delta. Wall-clock
 *                        and therefore an upper bound of the server TTFT; {@code 0} when not
 *                        measurable (e.g. non-streaming)
 * @param usageWarnings   machine-readable warnings about the server-reported usage object.
 *                        The raw usage object is written unchanged; warnings only mark
 *                        inconsistencies or unsupported/ambiguous details
 */
public record LlmUsage(LocalDateTime tsStart, long millisPP, long millisTG, String model,
                       long promptTokens, long completionTokens, long totalTokens,
                       long cachedTokens, boolean cachedTokensKnown,
                       float ppUncachedTPS, float ppTPS, float tgTPS,
                       boolean estimated, long ttftClientMs,
                       List<String> usageWarnings) {}