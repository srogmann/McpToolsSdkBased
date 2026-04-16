package org.rogmann.mcp2sdk.client;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Main entry point for the MCP Client example.
 * <p>
 * Connects to a local MCP server via HTTP Streamable transport
 * and lists available tools.
 * </p>
 */
public class ClientMain {
    /** Logger */
    private static final Logger LOG = LoggerFactory.getLogger(ClientMain.class);

    public static void main(String[] args) {
        McpClientTransport transport = HttpClientStreamableHttpTransport
                .builder("http://localhost:9091")
                .endpoint("/mcp")
                .build();

        var capabilities = McpSchema.ClientCapabilities.builder()
                .roots(false)       // Enable filesystem roots support with list changes notifications
                //.sampling()        // Enable LLM sampling support
                //.elicitation()     // Enable elicitation support (form and URL modes)
                .build();

        try (McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(10))
                .capabilities(capabilities)
                // .sampling(request -> new CreateMessageResult(response))
                // .elicitation(request -> new ElicitResult(ElicitResult.Action.ACCEPT, content))
                .build()) {
            McpSchema.ListToolsResult tools = client.listTools();
            for (McpSchema.Tool tool : tools.tools()) {
                LOG.info("Tool: {} - {}", tool.name(), tool.title());
            }
        }

    }
}
