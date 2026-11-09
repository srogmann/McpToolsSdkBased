package org.rogmann.mcp2sdk;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import org.rogmann.mcp2sdk.agent.AgentBoardTool;
import org.rogmann.mcp2sdk.agent.ForkIdentityFilter;
import org.rogmann.mcp2sdk.agent.PrefixForkTool;
import org.rogmann.mcp2sdk.browser.BrowserTool;
import org.rogmann.mcp2sdk.chat.PublicDirWebServer;
import org.rogmann.mcp2sdk.image.ImageClient;
import org.rogmann.mcp2sdk.image.ImageLimits;
import org.rogmann.mcp2sdk.image.JsImage;
import org.rogmann.mcp2sdk.image.VisionClient;
import org.rogmann.mcp2sdk.m2.PomWatch;
import org.rogmann.mcp2sdk.security.McpLockdownFilter;
import org.rogmann.mcp2sdk.security.WebUiSessionFilter;
import org.rogmann.mcp2sdk.tools.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
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

    /**
     * The shared review list behind {@code /review}, published as a bean so that the
     * {@code ReviewController} receives it by injection. The store is a singleton because its
     * writers - JS module bridges and tool services - are not Spring-managed; this bean is the
     * read-side bridge into the web layer, nothing more.
     *
     * @return the shared review store
     */
    @Bean
    public org.rogmann.mcp2sdk.review.ReviewStore reviewStore() {
        return org.rogmann.mcp2sdk.review.ReviewStore.getInstance();
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

    /**
     * Notes which MCP session belongs to which thread of a running {@code prefix_fork} batch, off
     * the header the child's transport carries (docs/subagent.md §14, P0.2). Everything downstream -
     * the {@code agent_board} tool and the {@code board} JavaScript module - reads that pairing and
     * never asks the caller who it is.
     *
     * <p>The filter never refuses anything; it only reads two headers. Registered on both MCP
     * endpoints, on nothing else.</p>
     *
     * @return the filter registration
     */
    @Bean
    public FilterRegistrationBean<ForkIdentityFilter> forkIdentityFilter() {
        FilterRegistrationBean<ForkIdentityFilter> registration =
                new FilterRegistrationBean<>(new ForkIdentityFilter());
        registration.setName("forkIdentityFilter");
        registration.addUrlPatterns("/mcp", "/mcp/*", "/mcp/sse", "/mcp/sse/*");
        return registration;
    }

    /**
     * Lockdown mode for shared hosts ({@code -Dwebui.lockdown=true}): on a Citrix / terminal
     * server every user runs their own instance, but the loopback interface is shared, so the
     * instance of one user is reachable from every other user's session. The HTTP session of
     * the Web UI is the only secret that stays inside one user - hence
     * {@link WebUiSessionFilter} (marks every {@code /chat/*} session) and
     * {@link McpLockdownFilter} (refuses {@code /mcp*} without such a session). Without the
     * lockdown both filters pass everything through unchanged and external MCP clients
     * (Cline, opencode, ...) work as always.
     *
     * <p>Ordering: the guard refuses in front of the observing {@code ForkIdentityFilter}.</p>
     *
     * @return the session-marking filter registration
     */
    @Bean
    public FilterRegistrationBean<WebUiSessionFilter> webUiSessionFilter() {
        FilterRegistrationBean<WebUiSessionFilter> registration =
                new FilterRegistrationBean<>(new WebUiSessionFilter());
        registration.setName("webUiSessionFilter");
        registration.addUrlPatterns("/chat/*");
        registration.setOrder(10);
        return registration;
    }

    /**
     * The refusing half of the lockdown mode, on both MCP endpoints - see
     * {@link #webUiSessionFilter()} for the threat model and the switch.
     *
     * @return the MCP guard registration
     */
    @Bean
    public FilterRegistrationBean<McpLockdownFilter> mcpLockdownFilter() {
        FilterRegistrationBean<McpLockdownFilter> registration =
                new FilterRegistrationBean<>(new McpLockdownFilter());
        registration.setName("mcpLockdownFilter");
        registration.addUrlPatterns("/mcp", "/mcp/*", "/mcp/sse", "/mcp/sse/*");
        registration.setOrder(20);
        return registration;
    }

    // Helper method to populate the registry with available tools
    private void populateToolRegistry(ToolRegistry registry, Environment environment) {
        // Fail fast on a misconfigured add-on configuration (missing description, duplicates, ...).
        WorkProject.validateAddonConfiguration();

        // Maven input guard: fingerprint every pom.xml and .mvn file in the writable project roots
        // before the first tool call could change one. Asynchronous (a large reactor means many
        // directories), and the phase stays RUNNING until it is through - an unfinished baseline
        // denies a Maven run rather than allowing it. See docs/maven-guard.md.
        PomWatch.startAsync();
        PomWatch.logStartupAssessment();

        registry.registerToolDefinition(CreateNewFileTool.createToolInstance());
        registry.registerToolDefinition(ReadTextFileTool.createToolInstance());
        registry.registerToolDefinition(EditFileTool.createToolInstance());
        registry.registerToolDefinition(FindFilesByGlobTool.createToolInstance());

        // Set Environment for ReadDependencyClassSourceTool before creating instance
        ReadDependencyClassSourceTool.setEnvironment(environment);
        registry.registerToolDefinition(ReadDependencyClassSourceTool.createToolInstance());

        // The image module's vision call resolves webui.* (current model, hasVision, token)
        // through the Spring environment - see docs/js/image.md §7.
        VisionClient.setEnvironment(environment);
        // The diffusion client resolves mcp.js.image.* (URL, model, size, steps, dialect) and the
        // gateway (webui.model.url) the same way.
        ImageClient.setEnvironment(environment);
        // Image limits: resolved once and strictly - a bad mcp.js.image.* value stops the start
        // instead of surfacing in the middle of a call (AGENTS.md: no silent defaults).
        ImageLimits.setCurrent(ImageLimits.from(environment));
        // Where 'output: demo' publishes pictures: <webui.public.path>/gen-images, served by the
        // Web UI under /chat/gen-images (a directory of the module's own, not the UI's root).
        // The port is read lazily: with server.port=0 only local.server.port knows the bound one,
        // and that property appears after the web server has started. No number of ours is used -
        // 0 means "not known yet" and the module answers with a hint instead of a guessed URL.
        JsImage.setLocalWebServer(PublicDirWebServer.of(
                environment.getProperty("webui.public.path"),
                () -> {
                    Integer bound = environment.getProperty("local.server.port", Integer.class);
                    if (bound != null && bound > 0) {
                        return bound;
                    }
                    Integer configured = environment.getProperty("server.port", Integer.class);
                    return configured != null ? configured : 0;
                }));

        registry.registerToolDefinition(GlossaryTool.createToolInstance());

        registry.registerToolDefinition(JavaScriptTool.createToolInstance());

        registry.registerToolDefinition(CallLlmTool.createToolInstance());

        // Browser session: operate an internal web application jointly (M0: read, do not write)
        registry.registerToolDefinition(BrowserTool.createToolInstance());

        // Prefix fork: child threads that start at the frozen prefix of this very conversation
        // (docs/webui.md §3). Registered but not activated by default - see tools.active.
        registry.registerToolDefinition(PrefixForkTool.createToolInstance());

        // Message board of a prefix_fork batch (docs/subagent.md): live only while a batch with
        // board:true runs, refused otherwise. Registered but not activated by default - see
        // tools.active; it must be active in the conversation for the children to reach it (F6).
        registry.registerToolDefinition(AgentBoardTool.createToolInstance());

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
