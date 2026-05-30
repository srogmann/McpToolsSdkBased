package org.rogmann.mcp2sdk;

import ch.qos.logback.classic.spi.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Arrays;

/**
 * Main entry point for the MCP Server Spring Boot application.
 * <p>
 * Initializes the Spring application context and starts the embedded server.
 * </p>
 */
@SpringBootApplication
public class McpServerMain {
    /** Logger */
    private static final Logger LOG = LoggerFactory.getLogger(McpServerMain.class);

    public static void main(String[] args) {
        // Starts the Spring context, the embedded Netty server, and scans for @Bean/@Component
        LOG.info("main: Start spring application");

        // spring shutdown-workaround
        Class<?>[] classes = {
                PackagingDataCalculator.class,
                StackTraceElementProxy.class,
                STEUtil.class,
                ThrowableProxy.class,
                ThrowableProxyUtil.class
        };
        Arrays.stream(classes).forEach(c -> LOG.debug("preload: {}", c));

        SpringApplication.run(McpServerMain.class, args);
    }
}
