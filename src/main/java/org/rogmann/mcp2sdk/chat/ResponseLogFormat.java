package org.rogmann.mcp2sdk.chat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Shortens request and response bodies for the DEBUG log lines of the Web UI proxy: long string
 * values are trimmed first (keeping the JSON structure readable), then the whole line is capped.
 * A body that cannot be parsed as JSON is truncated as plain text.
 *
 * <p>Moved out of {@code WebUiProxy} unchanged (docs/webui.md R1). The proxy keeps private
 * one-line delegates under the original names, so both the log lines and their wording are
 * unaffected. Request and response use different limits on purpose: the answer (and its chain of
 * thought) is the reason {@code webui.logResponses} gets enabled at all, so it is cut later than
 * the request.</p>
 */
final class ResponseLogFormat {

    /** Logger */
    private static final Logger LOG = LoggerFactory.getLogger(ResponseLogFormat.class);

    /** Mapper used to parse and re-serialize the body. */
    private final JsonMapper jsonMapper;

    /**
     * @param jsonMapper mapper used for the JSON round-trip of a body
     */
    ResponseLogFormat(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    // --- Limits of the request log line ---

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
    String shortenRequestBody(String requestBody) {
        return shortenBody(requestBody, LOG_MAX_STRING_LENGTH, LOG_STRING_PREFIX, LOG_STRING_SUFFIX,
                LOG_BODY_MAX_LENGTH, LOG_BODY_PREFIX, LOG_BODY_SUFFIX);
    }

    /**
     * Prepares a response body for logging, mirroring {@link #shortenRequestBody(String)} but with the
     * larger limits of the {@code webui.logResponses} DEBUG log: the answer (and its chain of
     * thought) is the reason the switch gets enabled, so single values are cut later than in the
     * request log.
     *
     * @param responseBody raw response body or the response summary built from the SSE chunks
     * @return shortened representation suitable for logging
     */
    String shortenResponseBody(String responseBody) {
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
}
