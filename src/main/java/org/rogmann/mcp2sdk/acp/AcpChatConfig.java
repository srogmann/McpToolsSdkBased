package org.rogmann.mcp2sdk.acp;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpServlet;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import org.rogmann.mcp2sdk.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Mounts the ACP endpoint ({@code StreamableHttpAcpServlet} from acp-http-servlet) on the
 * running Tomcat, next to {@code /chat}, {@code /mcp} and the rest (docs/acp.md §4, §5).
 * Loopback-only like the whole server; the origin check is the SDK servlet's.
 *
 * <p>WebSocket upgrades run on the same path: the servlet upgrades itself through the
 * container's {@code ServerContainer} (Tomcat's is initialized by Spring Boot's WebSocket
 * servlet auto-configuration, since {@code tomcat-embed-websocket} is on the classpath).
 * A container without WebSocket answers an upgrade request 501 — the smoke test tells.</p>
 *
 * <p>The shutdown lifecycle follows the servlet's javadoc: close every SSE stream and
 * WebSocket before Spring Boot's graceful shutdown waits for them
 * ({@code spring.lifecycle.timeout-per-shutdown-phase}).</p>
 */
@Configuration
@ConditionalOnProperty(name = "acp.enabled", matchIfMissing = true)
public class AcpChatConfig {

    private static final Logger LOG = LoggerFactory.getLogger(AcpChatConfig.class);

    @Value("${acp.endpoint-path:/acp}")
    private String endpointPath;

    /**
     * The effective server.port (application.yml or a -D override) — the base URL of the
     * loopback completion (docs/acp.md S3). Deliberately without a default: a loopback to
     * a guessed port would hit a different instance or nothing at all, so a missing
     * property must fail the context start.
     */
    @Value("${server.port}")
    private int serverPort;

    /**
     * The LLM backend ({@code webui.model.url}) — the base the ACP chat's context-window
     * detection asks for the model list; blank disables the detection (then only the system
     * property {@code acp.chat.context-window} can name a window).
     */
    @Value("${webui.model.url:}")
    private String modelUrl;

    /** The model the proxy overrides to ({@code webui.model.name}) — the id the detection looks up. */
    @Value("${webui.model.name:}")
    private String modelName;

    /**
     * The ACP endpoint: one agent (an {@code AcpChat}) per client connection. The
     * {@code ToolRegistry} rides along — the {@code /tools} command reads the live active
     * state from it, in-process.
     * @param toolRegistry the central tool registry
     * @return the servlet, unregistered
     */
    @Bean
    public StreamableHttpAcpServlet acpServlet(ToolRegistry toolRegistry) {
        String selfBaseUrl = "http://127.0.0.1:" + serverPort;
        AcpAgentFactory factory = AcpAgentFactory.sync(transport -> AcpChat.build(transport,
                selfBaseUrl, modelUrl, modelName, toolRegistry));
        return new StreamableHttpAcpServlet(AcpJsonMapper.createDefault(), factory);
    }

    /**
     * Registers the servlet at {@code acp.endpoint-path} with async support on (the endpoint
     * answers asynchronously and holds SSE streams open).
     * @param servlet the ACP endpoint servlet
     * @return the registration
     */
    @Bean
    public ServletRegistrationBean<StreamableHttpAcpServlet> acpServletRegistration(
            StreamableHttpAcpServlet servlet) {
        ServletRegistrationBean<StreamableHttpAcpServlet> registration =
                new ServletRegistrationBean<>(servlet, endpointPath);
        registration.setAsyncSupported(true);
        LOG.info("ACP endpoint mounted at {}", endpointPath);
        return registration;
    }

    /**
     * Closes the endpoint's SSE streams and WebSockets before the container's graceful
     * shutdown would wait for them, per the servlet javadoc's SmartLifecycle recommendation.
     * @param servlet the ACP endpoint servlet
     * @return the lifecycle that closes it
     */
    @Bean
    public SmartLifecycle acpShutdownLifecycle(StreamableHttpAcpServlet servlet) {
        return new SmartLifecycle() {

            private final AtomicBoolean running = new AtomicBoolean(true);

            @Override
            public boolean isAutoStartup() {
                return true;
            }

            @Override
            public void start() {
                running.set(true);
            }

            @Override
            public void stop() {
            }

            @Override
            public void stop(Runnable callback) {
                try {
                    servlet.closeGracefully().block(Duration.ofSeconds(15));
                }
                finally {
                    running.set(false);
                    callback.run();
                }
            }

            @Override
            public boolean isRunning() {
                return running.get();
            }
        };
    }
}
