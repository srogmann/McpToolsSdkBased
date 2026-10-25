package org.rogmann.mcp2sdk.browser;

import jakarta.annotation.PreDestroy;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The built-in IdP simulation (docs/browser.md §3a): a bare listener of its own, playing the second
 * factor the way a real IdP plays it - on an <b>origin of its own</b> that no proxy session can follow
 * onto and that no registry entry makes proxied.
 *
 * <p>Deliberately <b>no</b> Spring context and no registry entry: the IdP is not an application the
 * proxy may operate, it is the other end of the excursion - reached by the browser directly, answered
 * without rewriting, without a bridge and without capture. One servlet, one page, one form that posts
 * back to exactly the {@code ru} the application handed over (built from what the application's
 * request named - X-Forwarded-Host, docs/browser.md §3a).</p>
 *
 * <p>The same pattern as {@link BrowserListener}: the container is the Tomcat that
 * {@code spring-boot-starter-web} brings along anyway, bound to {@code 127.0.0.1} explicitly - a
 * second Tomcat does not inherit {@code server.address}, and an IdP simulation open to the network
 * would be a door nobody asked for.</p>
 *
 * <p><b>Configuration:</b> {@code browser.idp.port} (default 9102, {@code 0} = off),
 * {@code browser.demo.idp.host} (default {@code idp} plus {@code browser.host.suffix}),
 * {@code browser.demo.idp.origin} (a full origin for a real external IdP - the listener then only
 * serves as the default naming of the excursion, it is not started for another origin's sake).</p>
 */
@Component
public class BrowserIdpServer {

    private static final Logger LOG = LoggerFactory.getLogger(BrowserIdpServer.class);

    /** System property for the IdP port; {@code 0} or absent-as-0 means: no IdP listener. */
    public static final String PORT_PROPERTY = "browser.idp.port";

    /** System property for the IdP host name (without scheme, without port). */
    public static final String HOST_PROPERTY = "browser.demo.idp.host";

    /** The port the excursion names when nothing else is configured. */
    static final int DEFAULT_PORT = 9102;

    private Tomcat tomcat;
    private int configuredPort;

    /**
     * Starts the IdP listener after the webapp is up - the same gate as the application listeners:
     * without the browser feature no simulation, on a taken port a warning instead of an abort.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void start(ApplicationReadyEvent event) {
        BrowserSessions backend = BrowserSessions.get();
        if (!backend.settings().enabled()) {
            LOG.info("browser: browser.enabled=false - no IdP listener");
            return;
        }
        configuredPort = port();
        if (configuredPort <= 0) {
            LOG.info("browser: {}={} - no IdP listener (the 2FA excursion of the demo points at"
                    + " {})", PORT_PROPERTY, configuredPort, idpOrigin());
            return;
        }
        if (configuredPort == backend.localPort()) {
            LOG.warn("browser: {}={} is the normal port - no IdP listener (the excursion must not"
                    + " land on the server the session's upstream lives on)", PORT_PROPERTY, configuredPort);
            return;
        }
        for (BrowserApp app : backend.apps().all()) {
            if (app.port() == configuredPort) {
                LOG.warn("browser: {}={} is registered for application '{}' - no IdP listener on a"
                        + " port a proxy session is bound to (the excursion would stay in the session)",
                        PORT_PROPERTY, configuredPort, app.id());
                return;
            }
        }
        try {
            Path base = Files.createTempDirectory("mcp-idp-");
            tomcat = new Tomcat();
            tomcat.setBaseDir(base.toString());
            Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
            connector.setPort(configuredPort);
            connector.setProperty("address", "127.0.0.1");
            connector.setProperty("maxThreads", "8");
            connector.setProperty("minSpareThreads", "2");
            connector.setProperty("acceptCount", "16");
            connector.setProperty("relaxedQueryChars", "[]{}|");
            tomcat.setConnector(connector);
            tomcat.getHost().setAppBase(base.toString());
            tomcat.getHost().setAutoDeploy(false);
            tomcat.getEngine().setName("browser-idp");
            Context context = tomcat.addContext("", base.toString());
            Tomcat.addServlet(context, "idp", new BrowserIdpServlet());
            context.addServletMappingDecoded("/", "idp");
            tomcat.start();
            LOG.info("browser: IdP simulation listens on http://{}:{} (2FA excursion target,"
                            + " docs/browser.md §3a)", host(),
                    tomcat.getConnector().getLocalPort());
        } catch (IOException | LifecycleException | RuntimeException e) {
            LOG.warn("browser: IdP listener not started on port {} ({}) - the 2FA excursion of the"
                    + " demo has no target until the port is free", configuredPort, e.toString());
        }
    }

    /**
     * @return the port the excursion names: {@code browser.idp.port}, default 9102
     */
    public static int port() {
        String raw = System.getProperty(PORT_PROPERTY, "");
        if (raw.isBlank()) {
            return DEFAULT_PORT;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            LOG.warn("browser: {}='{}' is not a port - the default {} stays",
                    PORT_PROPERTY, raw, DEFAULT_PORT);
            return DEFAULT_PORT;
        }
    }

    /**
     * @return the host name of the IdP: {@code browser.demo.idp.host}, else {@code idp} plus the
     *         configured {@code browser.host.suffix} ({@code idp.localhost} when the suffix is off)
     */
    public static String host() {
        String configured = System.getProperty(HOST_PROPERTY, "");
        if (!configured.isBlank()) {
            return configured.trim();
        }
        String suffix = BrowserSessions.get().settings().hostSuffix();
        return "idp" + (suffix.isEmpty() ? BrowserHost.SUGGESTED_SUFFIX : suffix);
    }

    /**
     * @return the origin the excursion leaves to - the address the 2FA form of the demo points at
     */
    public static String idpOrigin() {
        String configured = System.getProperty(BrowserDemoApp.IDP_ORIGIN_PROPERTY, "");
        if (!configured.isBlank()) {
            String origin = configured.trim();
            return origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin;
        }
        return "http://" + host() + ":" + port();
    }

    /**
     * Shuts the listener down (Spring administration, same as {@link BrowserListeners}).
     */
    @PreDestroy
    public void close() {
        if (tomcat == null) {
            return;
        }
        try {
            try {
                tomcat.stop();
            } finally {
                tomcat.destroy();
            }
            LOG.info("browser: IdP listener on port {} closed", configuredPort);
        } catch (LifecycleException | RuntimeException e) {
            LOG.warn("browser: IdP listener could not be closed cleanly: {}", e.toString());
        } finally {
            tomcat = null;
        }
    }
}
