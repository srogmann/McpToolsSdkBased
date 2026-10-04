package org.rogmann.mcp2sdk.browser;

import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A bare Tomcat listener: one port, one context, one servlet.
 *
 * <p>The container is not a new dependency but the same Tomcat {@code spring-boot-starter-web} brings
 * along anyway - only without the webapp around it. A deliberate choice over
 * {@code com.sun.net.httpserver.HttpServer}: the handler stays with
 * {@code HttpServletRequest}/{@code HttpServletResponse} (the pass-through code moves unchanged),
 * repeated header values, chunked bodies, multipart, keep-alive and the header limits are the
 * container's job instead of our own, and should an application still need a WebSocket, the access to
 * the upgrade that takes is present in a servlet container but not in {@code HttpServer.exchange()}.</p>
 *
 * <p><b>Binding:</b> {@code address=127.0.0.1} is set <em>explicitly</em> here and does not rely on
 * {@code server.address} from {@code application.yml} - that one counts for the main connector of the
 * Spring Boot app, not automatically for a second Tomcat. A reverse proxy accidentally bound to the
 * open, writing inward with the user's session cookies, is the most unpleasant door one can open on an
 * office network.</p>
 */
final class BrowserListener implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(BrowserListener.class);

    private final Tomcat tomcat;
    private final BrowserApp app;
    private final int port;

    /**
     * Starts the listener for an application.
     *
     * @param app     the application (its {@code port} has to be set)
     * @param session the session operated on this port
     * @throws IOException        if the working directory cannot be created or the port is taken
     * @throws LifecycleException if Tomcat does not come up (port taken, permissions, binding)
     */
    BrowserListener(BrowserApp app, BrowserSession session) throws IOException, LifecycleException {
        this.app = app;
        this.port = app.port();
        Path base = Files.createTempDirectory("mcp-browser-");
        tomcat = new Tomcat();
        tomcat.setBaseDir(base.toString());
        // Port and address sit on the connector; setPort alone would create a default connector.
        Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
        connector.setPort(port);
        connector.setProperty("address", "127.0.0.1");
        connector.setProperty("maxThreads", "16");
        connector.setProperty("minSpareThreads", "2");
        connector.setProperty("acceptCount", "16");
        connector.setProperty("relaxedQueryChars", "[]{}|");
        tomcat.setConnector(connector);
        tomcat.getHost().setAppBase(base.toString());
        tomcat.getHost().setAutoDeploy(false);      // no HostConfig scanning over a temp directory
        tomcat.getEngine().setName("browser-" + app.id());

        Context context = tomcat.addContext("", base.toString());
        Tomcat.addServlet(context, "browserProxy", new BrowserProxyServlet(app, session));
        context.addServletMappingDecoded("/", "browserProxy");
        tomcat.start();
        int bound = tomcat.getConnector().getLocalPort();
        LOG.info("browser: {} listens on {} (session {}, upstream {})",
                app.id(), BrowserHost.url(app.id(), bound, ""), session.id(), app.upstream());
    }

    /**
     * @return the port that was actually bound (with {@code port=0} the assigned one)
     */
    int port() {
        return tomcat.getConnector().getLocalPort();
    }

    /**
     * @return the application of this listener
     */
    BrowserApp app() {
        return app;
    }

    /**
     * @return true while the container still accepts requests
     */
    boolean isRunning() {
        return tomcat.getServer() != null && tomcat.getServer().getState().isAvailable();
    }

    /**
     * Shuts the listener down (breakfast of the Spring administration: {@code @PreDestroy}).
     */
    @Override
    public void close() {
        try {
            try {
                tomcat.stop();
            } finally {
                tomcat.destroy();
            }
            LOG.info("browser: listener for {} on port {} closed", app.id(), port);
        } catch (LifecycleException | RuntimeException e) {
            LOG.warn("browser: listener for {} could not be closed cleanly: {}", app.id(), e.toString());
        }
    }
}
