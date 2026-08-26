package org.rogmann.mcp2sdk;

import ch.qos.logback.classic.spi.*;
import ch.qos.logback.core.status.WarnStatus;
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
                WarnStatus.class,
                // Spring
                GracefulShutdownCallback.class,
                GracefulShutdownResult.class,
                reactor.core.Exceptions.class
        };
        String[] sClasses = {
                "ch.qos.logback.classic.spi.ClassPackagingData",
                "org.apache.catalina.Lifecycle$SingleUse",
                "org.apache.catalina.util.RequestUtil",
                "org.apache.catalina.webresources.WarResourceSet",
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
        // Load and INITIALISE the reactor classes (and all their nested classes) eagerly.
        // A plain loadClass does NOT initialise; a failed initialisation is cached by the JVM
        // and re-thrown as NoClassDefFoundError on later access (hence the whack-a-mole at
        // shutdown). Initialising every nested class at startup prevents this reliably, and
        // covers all current and future inner classes without manual bookkeeping.
        preloadReactorClasses(cl,
                "io.modelcontextprotocol.spec.McpServerSession",
                "reactor.core.Exceptions",
                "reactor.core.publisher.FluxFlatMap",
                "reactor.core.publisher.FluxIterable",
                "reactor.core.publisher.FluxOnErrorReturn",
                "reactor.core.publisher.LambdaMonoSubscriber",
                "reactor.core.publisher.MonoPeekTerminal",
                "reactor.core.publisher.MonoIgnoreElements",
                "reactor.core.publisher.MonoRunnable",
                "reactor.core.publisher.MonoSink",
                "reactor.core.publisher.Operators");

        SpringApplication.run(McpServerMain.class, args);
    }

    /**
     * Loads and initialises the given reactor classes together with all of their nested classes.
     * Initialisation (which {@code loadClass} does not perform) is what ensures the JVM does
     * not cache a failed init as a {@link NoClassDefFoundError} for later access, e.g. during
     * Spring bean destruction on shutdown.
     *
     * @param cl          the {@link ClassLoader} to load from (usually the app class loader)
     * @param baseClasses fully-qualified names of the classes to preload (with all nested classes)
     */
    private static void preloadReactorClasses(ClassLoader cl, String... baseClasses) {
        for (String base : baseClasses) {
            int[] n = {0};
            try {
                Class<?> type = Class.forName(base, true, cl);
                for (Class<?> nested : type.getDeclaredClasses()) {
                    Class.forName(nested.getName(), true, cl);
                    n[0]++;
                }
                LOG.debug("Preloaded {} + {} nested classes of {}", type.getSimpleName(), n[0], base);
            } catch (Throwable t) {
                LOG.warn("Could not preload {} ({} nested): {}", base, n[0], t.toString());
            }
        }
    }
}
