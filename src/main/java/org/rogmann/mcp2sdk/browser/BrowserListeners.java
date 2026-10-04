package org.rogmann.mcp2sdk.browser;

import jakarta.annotation.PreDestroy;
import org.apache.catalina.LifecycleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages the application listeners: one per application with a set {@code port}.
 *
 * <p>Runs after the actual webapp has come up ({@link ApplicationReadyEvent}), so that the normal port
 * is known and occupied, and shuts down again when the service does. An application without a port
 * stays in path mode on the normal port - the listener is an option, not a precondition.</p>
 *
 * <p>What a bad port is <em>not</em> allowed to do: knock the service over. A taken or unusable port is
 * logged and the application is pulled back to path mode; the other listeners keep running. A proxy
 * that does not start because of one line in a JSON file is worse than one that does not speed up an
 * application.</p>
 */
@Component
public class BrowserListeners {

    private static final Logger LOG = LoggerFactory.getLogger(BrowserListeners.class);

    private final List<BrowserListener> running = new CopyOnWriteArrayList<>();

    /**
     * Starts the listeners of the registry.
     *
     * <p><b>The parameter is not an ingredient but the announcement.</b> {@code @EventListener} without
     * an event parameter <em>and</em> without {@code classes} is not "listen to everything" but a
     * configuration error: {@code EventListenerMethodProcessor} throws
     * {@code "Event parameter is mandatory for event listener method"} and the context does not come up
     * - the application does not start at all because one optional extra listener wanted to declare its
     * start time. Whoever comments the annotation out instead of setting the parameter gets a server
     * that appears to run <em>without</em> listeners: the port applications in the registry are then
     * dead (connection refused), and "Open" leads nowhere.</p>
     *
     * @param event the ready signal of the application (supplier of the port actually bound)
     */
    @EventListener
    public void start(ApplicationReadyEvent event) {
        BrowserSessions backend = BrowserSessions.get();
        // The bound port, not the assumed one: local.server.port is set after the web server starts,
        // and -Dbrowser.local.port is only the assumption for tools that run before this moment.
        // Without these two lines an application.yml with another port would address the
        // self: demo application against 9091.
        Integer bound = event.getApplicationContext().getEnvironment()
                .getProperty("local.server.port", Integer.class);
        if (bound != null && bound > 0) {
            backend.setLocalPort(bound);
        }
        if (!backend.settings().enabled()) {
            LOG.info("browser: browser.enabled=false - no application listeners");
            return;
        }
        if (!running.isEmpty()) {
            // Two ApplicationReadyEvents in one JVM (test contexts, renewed refresh) must not create a
            // second set of listeners on the same ports.
            LOG.info("browser: application listeners already started ({}), no second attempt",
                    running.size());
            return;
        }
        for (BrowserApp app : backend.apps().all()) {
            if (app.port() <= 0) {
                continue;
            }
            if (app.port() == backend.localPort()) {
                LOG.warn("browser: '{}' wants port {} - that is the normal port; the application runs"
                        + " in path mode", app.id(), app.port());
                continue;
            }
            try {
                BrowserSession session = backend.newBoundSession(app.id());
                running.add(new BrowserListener(app, session));
            } catch (IOException | LifecycleException | RuntimeException e) {
                // Every reason a port does not work is a warning and a fallback - never an abort: an
                // unoccupied second port must not keep the service from running.
                LOG.warn("browser: listener for '{}' on port {} not started ({}) - "
                        + "falling back to path mode on {}",
                        app.id(), app.port(), e.toString(),
                        BrowserHost.url(app.id(), backend.localPort(), "/browser/" + app.id() + "/new"));
            }
        }
        backend.apps().advices().forEach(advice -> LOG.warn("browser: {}", advice));
        if (running.isEmpty()) {
            LOG.info("browser: no application port occupied (registry: {})", backend.apps().source());
        }
    }

    /**
     * @return the running listeners (diagnostics, index page)
     */
    public List<BrowserListener> running() {
        return List.copyOf(running);
    }

    /**
     * Shuts every listener down.
     */
    @PreDestroy
    public void close() {
        for (BrowserListener listener : running) {
            listener.close();
        }
        running.clear();
    }
}
