package org.rogmann.mcp2sdk;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import org.rogmann.mcp2sdk.examples.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring configuration class for the Model Context Protocol (MCP) server.
 * <p>
 * Sets up JSON mapping, HTTP Streamable and SSE transports,
 * and registers tools for the MCP server instances.
 * </p>
 */
@Configuration
public class McpConfig {
    /** Logger */
    private static final Logger LOG = LoggerFactory.getLogger(McpConfig.class);

    // JsonMapper Bean for serialization
    @Bean
    public McpJsonMapper mcpJsonMapper() {
        LOG.info("<init>");
        JsonMapper mapper = JsonMapper.builder().build();
        return new JacksonMcpJsonMapper(mapper);
    }

    // Streamable HTTP Transport (for clients supporting Session headers)
    @Bean
    public HttpServletStreamableServerTransportProvider streamableTransportProvider(McpJsonMapper jsonMapper) {
        LOG.info("Creating Streamable HTTP Transport on /mcp");
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(jsonMapper)
                .mcpEndpoint("/mcp")
                .build();
    }

    @Bean
    public ServletRegistrationBean<?> streamableServlet(
            HttpServletStreamableServerTransportProvider transportProvider) {
        return new ServletRegistrationBean<>(transportProvider, "/mcp/*");
    }

    // SSE Transport (for Cline and other SSE clients)
    @Bean
    public HttpServletSseServerTransportProvider sseTransportProvider(McpJsonMapper jsonMapper) {
        LOG.info("Creating SSE Transport on /mcp/sse");
        return HttpServletSseServerTransportProvider.builder()
                .jsonMapper(jsonMapper)
                .messageEndpoint("/mcp/sse/message")
                .sseEndpoint("/mcp/sse")
                .build();
    }

    @Bean
    public ServletRegistrationBean<?> sseServlet(
            HttpServletSseServerTransportProvider transportProvider) {
        return new ServletRegistrationBean<>(transportProvider, "/mcp/sse/*");
    }

    // Helper method for tool registration (avoids code duplication)
    private void registerTools(McpSyncServer server) {
        server.addTool(CreateNewFileTool.createSpecification());
        server.addTool(FindFilesByGlobTool.createSpecification());
        server.addTool(ReadTextFileTool.createSpecification());

        server.addTool(GlossaryTool.createSpecification());

        server.addTool(VideoPlayerTool.createSpecification());
        server.addTool(VideoSearchTool.createSpecification());
    }

    // Streamable HTTP Server instance
    @Bean
    public McpSyncServer streamableMcpServer(HttpServletStreamableServerTransportProvider transportProvider) {
        LOG.info("Building Streamable HTTP MCP Server on /mcp");

        var capabilities = McpSchema.ServerCapabilities.builder()
                .tools(true)
                .build();

        McpSyncServer server = McpServer.sync(transportProvider)
                .capabilities(capabilities)
                .build();

        registerTools(server);
        return server;
    }

    // SSE Server instance (separate instance for SSE clients)
    @Bean
    public McpSyncServer sseMcpServer(HttpServletSseServerTransportProvider transportProvider) {
        LOG.info("Building SSE MCP Server on /mcp/sse");

        var capabilities = McpSchema.ServerCapabilities.builder()
                .tools(true)
                .build();

        McpSyncServer server = McpServer.sync(transportProvider)
                .capabilities(capabilities)
                .build();

        registerTools(server);
        return server;
    }
}
