package org.rogmann.mcp2sdk.browser;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * One proxied HTTP exchange as it belongs in the capture: <b>already redacted</b>.
 *
 * <p>The capture is the LLM's view of what the application <em>really</em> does, and for table
 * transfers almost always the cheaper view than rendered HTML - the SPA has loaded the data as JSON
 * already before turning it into rows. Bodies are therefore taken along, but never unrestricted:
 * secrets are replaced by {@link BrowserSecrets}, length is limited to {@code settings.captureBytes()}
 * and a {@code sensitive} marker tells the reader that nothing stands here on purpose (login, token
 * endpoint).</p>
 *
 * @param seq           sequence number within the session (for "what happened since?")
 * @param timestamp     ISO-8601
 * @param method        HTTP method
 * @param proxiedPath   the path the browser saw (with session prefix)
 * @param upstreamPath  the path at the application
 * @param query         query, may be empty
 * @param status        response status, {@code 0} when the request was not answered
 * @param requestType   content type of the request
 * @param requestBody   redacted, clipped request body
 * @param responseType  content type of the response
 * @param responseBody  redacted, clipped response body
 * @param bytesUp       bytes sent
 * @param bytesDown     bytes received
 * @param durationMillis runtime
 * @param sensitive     true when the content was not logged because of {@code sensitivePaths}
 * @param notes         notes (e.g. "3 Set-Cookie rewritten", "unregistered host")
 */
public record BrowserExchange(int seq, String timestamp, String method, String proxiedPath,
        String upstreamPath, String query, int status, String requestType, String requestBody,
        String responseType, String responseBody, long bytesUp, long bytesDown, long durationMillis,
        boolean sensitive, List<String> notes) {

    /**
     * @return the short form for {@code requests}: one line per exchange, without bodies
     */
    public ObjectNode toSummaryNode() {
        ObjectNode node = BrowserSessions.get().mapper().createObjectNode();
        node.put("seq", seq);
        node.put("ts", timestamp);
        node.put("method", method);
        node.put("path", upstreamPath);
        if (query != null && !query.isEmpty()) {
            node.put("query", query.length() > 120 ? query.substring(0, 120) + "…" : query);
        }
        node.put("status", status);
        node.put("type", responseType == null ? "" : responseType);
        node.put("bytes", bytesDown);
        node.put("ms", durationMillis);
        if (sensitive) {
            node.put("sensitive", true);
        }
        if (requestBody != null && !requestBody.isEmpty()) {
            node.put("requestBytes", requestBody.length());
        }
        return node;
    }

    /**
     * @return the full view including bodies and notes
     */
    public ObjectNode toFullNode() {
        ObjectNode node = toSummaryNode();
        node.put("proxiedPath", proxiedPath);
        if (requestType != null && !requestType.isEmpty()) {
            node.put("requestType", requestType);
        }
        node.put("requestBody", requestBody == null ? "" : requestBody);
        node.put("responseBody", responseBody == null ? "" : responseBody);
        if (!notes.isEmpty()) {
            ArrayNode array = node.putArray("notes");
            notes.forEach(array::add);
        }
        return node;
    }

    /**
     * Clips a text in the middle so that head and tail survive (in JSON both are meaningful).
     *
     * @param text the text
     * @param max  maximum length
     * @return the text or a clipped version with a marker
     */
    public static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        if (text.length() <= max) {
            return text;
        }
        int head = (int) (max * 0.7);
        int tail = max - head;
        return text.substring(0, head) + "\n… " + (text.length() - max) + " characters clipped …\n"
                + text.substring(text.length() - tail);
    }
}
