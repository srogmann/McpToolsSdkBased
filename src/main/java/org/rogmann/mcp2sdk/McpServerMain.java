package org.rogmann.mcp2sdk;

import ch.qos.logback.classic.spi.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.server.GracefulShutdownCallback;
import org.springframework.boot.web.server.GracefulShutdownResult;

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
                // Logger
                PackagingDataCalculator.class,
                StackTraceElementProxy.class,
                STEUtil.class,
                ThrowableProxy.class,
                ThrowableProxyUtil.class,
                // Spring
                GracefulShutdownCallback.class,
                GracefulShutdownResult.class
        };
        String[] sClasses = {
                "ch.qos.logback.classic.spi.ClassPackagingData",
                "org.apache.catalina.Lifecycle$SingleUse",
                "reactor.core.publisher.LambdaMonoSubscriber"
        };
        Arrays.stream(classes).forEach(c -> LOG.debug("preload: {}", c));
        final ClassLoader cl = McpServerMain.class.getClassLoader();
        Arrays.stream(sClasses).forEach(name -> {
            try {
                Class<?> clazz = cl.loadClass(name);
                LOG.debug("preload: {}", clazz);
            } catch (ClassNotFoundException e) {
                LOG.warn("missing preload-class: " + e);
            }
        });

        SpringApplication.run(McpServerMain.class, args);
    }
}
