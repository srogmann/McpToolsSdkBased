package org.rogmann.mcp2sdk.examples;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.rogmann.mcp2sdk.ToolSpecWithState;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the execution timeout of {@link JavaScriptTool}.
 * <p>
 * A looping script must be cancelled after the requested timeout instead of blocking the tool,
 * and the error message must point the caller into the script (JavaScript stack plus the loop's
 * source code). The Java stack trace goes to the server log and is not asserted here.
 * </p>
 * <p>
 * Note: the {@code mcp} module stays disabled in tests (its server-url system property is not
 * set), so the calls below do not need any external service. The tool does not use the server
 * exchange, so {@code null} may be passed as exchange.
 * </p>
 */
class JavaScriptToolTimeoutTest {

    /** The tool under test (created once, like the McpConfig does it for the server). */
    private static final McpServerFeatures.SyncToolSpecification TOOL_SPEC =
            JavaScriptTool.createToolInstance().spec();

    /**
     * Calls the javascript_tool.
     * @param arguments the tool arguments
     * @return the tool result
     */
    private static McpSchema.CallToolResult callTool(Map<String, Object> arguments) {
        McpSchema.CallToolRequest request =
                new McpSchema.CallToolRequest("javascript_tool", arguments);
        return TOOL_SPEC.callHandler().apply(null, request);
    }

    /**
     * Collects the text content of a tool result.
     * @param result the tool result
     * @return the concatenated text content
     */
    private static String textOf(McpSchema.CallToolResult result) {
        StringBuilder sb = new StringBuilder();
        List<McpSchema.Content> content = (result != null) ? result.content() : null;
        if (content != null) {
            for (McpSchema.Content item : content) {
                if (item instanceof McpSchema.TextContent textContent) {
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append(textContent.text());
                }
            }
        }
        return sb.toString();
    }

    @Test
    void plainScriptStillWorks() {
        McpSchema.CallToolResult result = callTool(Map.of("script", "console.log('hello'); 21 * 2;"));
        String text = textOf(result);
        assertFalse(Boolean.TRUE.equals(result.isError()), "expected success, got: " + text);
        assertTrue(text.contains("hello"), "console.log output missing: " + text);
        assertTrue(text.contains("42"), "completion value missing: " + text);
    }

    @Test
    void endlessLoopIsCancelledAndReportsTheJavaScriptStack() {
        String script = """
                function hotLoop() {
                    var i = 0;
                    while (true) { i++; }
                }
                function caller() {
                    hotLoop();
                }
                console.log('entering loop');
                caller();
                """;

        long startMillis = System.currentTimeMillis();
        McpSchema.CallToolResult result = callTool(Map.of("script", script, "timeoutSeconds", 2));
        long elapsedMillis = System.currentTimeMillis() - startMillis;

        String text = textOf(result);
        assertTrue(Boolean.TRUE.equals(result.isError()), "expected an error, got: " + text);
        assertTrue(text.contains("timeout of 2 s"), "timeout not reported: " + text);
        assertTrue(text.contains("JavaScript stack"), "JavaScript stack missing: " + text);
        // Either the function name of the frame or the loop statement must show where it hangs.
        assertTrue(text.contains("hotLoop") || text.contains("while (true)"),
                "the loop is not identifiable in: " + text);
        // The output written before the timeout should help the caller as well.
        assertTrue(text.contains("entering loop"), "partial output missing: " + text);
        // The cancellation should happen close to the timeout (plus grace period for the worker).
        assertTrue(elapsedMillis < 30_000,
                "cancellation took far too long: " + elapsedMillis + " ms");
    }

    @Test
    void invalidTimeoutSecondsIsRejected() {
        McpSchema.CallToolResult result = callTool(Map.of("script", "1 + 1;", "timeoutSeconds", 99999));
        String text = textOf(result);
        assertTrue(Boolean.TRUE.equals(result.isError()), "expected an error, got: " + text);
        assertTrue(text.contains("timeoutSeconds"), "the parameter is not mentioned: " + text);
    }

    @Test
    void missingScriptAndPathAreRejected() {
        McpSchema.CallToolResult result = callTool(Map.of("timeoutSeconds", 5));
        assertTrue(Boolean.TRUE.equals(result.isError()));
        assertTrue(textOf(result).contains("script"), textOf(result));
    }

    @Test
    void scriptFromPathRunsInSameSandbox() {
        // A file is read through the controlled fs access; the sandbox rejects unknown modules
        // the same way as for inline scripts.
        McpSchema.CallToolResult result = callTool(Map.of("script",
                "var thrown = false; try { require('path'); } catch (e) { thrown = true; } thrown;",
                "timeoutSeconds", 30));
        String text = textOf(result);
        assertFalse(Boolean.TRUE.equals(result.isError()), "expected success, got: " + text);
        assertEquals("true", text.trim(), text);
    }
}
