package org.rogmann.mcp2sdk;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import org.rogmann.mcp2sdk.examples.*;
import org.rogmann.mcp2sdk.examples.ReadDependencyClassSourceTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring configuration class for the Model Context Protocol (MCP) server.
 * <p>
 * Sets up JSON mapping, HTTP Streamable and SSE transports,
 * and registers tools via the ToolRegistry.
 * </p>
 */
@Configuration
public class McpConfig {
    /** Logger */
    private static final Logger LOG = LoggerFactory.getLogger(McpConfig.class);

    @Bean
    public ToolRegistry toolRegistry() {
        return new ToolRegistry();
    }

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

    // Helper method to populate the registry with available tools
    private void populateToolRegistry(ToolRegistry registry, Environment environment) {
        registry.registerToolDefinition(CreateNewFileTool.createToolInstance());
        registry.registerToolDefinition(ReadTextFileTool.createToolInstance());
        registry.registerToolDefinition(EditFileTool.createToolInstance());
        registry.registerToolDefinition(FindFilesByGlobTool.createToolInstance());

        // Set Environment for ReadDependencyClassSourceTool before creating instance
        ReadDependencyClassSourceTool.setEnvironment(environment);
        registry.registerToolDefinition(ReadDependencyClassSourceTool.createToolInstance());

        registry.registerToolDefinition(GlossaryTool.createToolInstance());

        //registry.registerToolDefinition(VideoPlayerTool.createToolInstance());
        //registry.registerToolDefinition(VideoSearchTool.createToolInstance());
    }

    // Streamable HTTP Server instance
    @Bean
    public McpSyncServer streamableMcpServer(
            HttpServletStreamableServerTransportProvider transportProvider, 
            ToolRegistry registry,
            Environment environment) {
        LOG.info("Building Streamable HTTP MCP Server on /mcp");

        var capabilities = McpSchema.ServerCapabilities.builder()
                .tools(true)
                .build();

        McpSyncServer server = McpServer.sync(transportProvider)
                .capabilities(capabilities)
                .build();

        // Populate registry if not already done (assuming single context)
        // In a real scenario, ensure populateToolRegistry is called only once globally.
        // Here we assume ToolRegistry is a singleton and we might need a flag or PostConstruct there.
        // For this config, we rely on ToolRegistry's PostConstruct or a separate initializer.
        // To ensure tools exist before server starts using them:
        if (registry.getMapTools().isEmpty()) {
            populateToolRegistry(registry, environment);
        }
        
        registry.registerServer(server);
        
        return server;
    }

    // SSE Server instance (separate instance for SSE clients)
    @Bean
    public McpSyncServer sseMcpServer(
            HttpServletSseServerTransportProvider transportProvider, 
            ToolRegistry registry) {
        LOG.info("Building SSE MCP Server on /mcp/sse");

        var capabilities = McpSchema.ServerCapabilities.builder()
                .tools(true)
                .build();

        McpSyncServer server = McpServer.sync(transportProvider)
                .capabilities(capabilities)
                .build();

        // Register this server with the registry to receive tool updates
        registry.registerServer(server);
        
        return server;
    }
}
