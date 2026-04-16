package org.rogmann.mcp2sdk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

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
        SpringApplication.run(McpServerMain.class, args);
    }
}
