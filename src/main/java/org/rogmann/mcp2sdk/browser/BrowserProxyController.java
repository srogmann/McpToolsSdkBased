package org.rogmann.mcp2sdk.browser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * The human entry point and the front door of path mode.
 *
 * <p>Two prefixes, cleanly separated:</p>
 * <ul>
 *   <li>{@code /browser/<appId>/<sessionId>/...} - <b>foreign content</b>, proxied and rewritten.
 *       Everything below it is the application.</li>
 *   <li>{@code /browserctl/...} - <b>our endpoints</b>: bridge script, long-poll, reports, session
 *       administration. Deliberately not a pattern under {@code /browser/}, because a path like
 *       {@code /browser/api/status} would otherwise look like a session ({@code appId=api},
 *       {@code sessionId=status}).</li>
 * </ul>
 *
 * <p>The code here is thin because everything essential lies elsewhere and is <em>there</em> used by
 * port mode as well: proxying in {@link BrowserProxyCore}, the bridge in {@link BrowserBridge},
 * provenance in {@link BrowserOrigin}. What remains is the index page and the Spring mapping for path
 * mode.</p>
 *
 * <p><b>About the provenance</b>: on this port - the normal one - our control surfaces
 * ({@code /mcp}, {@code /review}, {@code /chat}, {@code /tools}) share the origin with proxied pages
 * as long as an application runs in path mode. For applications that are disturbed by that (or where
 * the rewriting work is), port mode exists: set {@code port} in the registry, and the application gets
 * a listener of its own on which these surfaces do not exist ({@link BrowserListener}).</p>
 */
@Controller
public class BrowserProxyController {

    private static final Logger LOG = LoggerFactory.getLogger(BrowserProxyController.class);

    private final BrowserSessions backend = BrowserSessions.get();

    // ========================================================================
    // Path mode: the application
    // ========================================================================

    /**
     * Passes a request through in path mode.
     *
     * @param appId     application id from the path
     * @param sessionId session id from the path
     * @param request   the browser's request
     * @param response  the response
     * @throws IOException if the browser gives up in the middle of the response
     */
    @RequestMapping({"/browser/{appId}/{sessionId}", "/browser/{appId}/{sessionId}/**"})
    public void proxy(@PathVariable String appId, @PathVariable String sessionId,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        backend.setLocalPort(request.getLocalPort());
        BrowserSession session = backend.session(sessionId).orElse(null);
        if (session == null) {
            BrowserProxyCore.sendText(response, 404, "Session closed",
                    "<p>The session <code>" + escape(sessionId) + "</code> does not exist any more - it"
                            + " lives in memory and ends with a server restart.</p>");
            return;
        }
        BrowserApp app = backend.app(appId).orElse(null);
        if (app == null) {
            BrowserProxyCore.sendText(response, 403, "Not approved",
                    "<p><code>" + escape(appId) + "</code> is not registered in "
                            + escape(String.valueOf(backend.apps().source())) + ". Only"
                            + " registered, internal applications are operated - that is the trust"
                            + " boundary of this procedure, not the URL.</p>");
            return;
        }
        BrowserProxyCore.handle(app, session, new BrowserOrigin.Path(appId, sessionId), request, response);
    }

    // ========================================================================
    // Path mode: the bridge
    // ========================================================================

    /**
     * Every bridge endpoint under {@code /browserctl/}. The decision which of these is the bridge and
     * which is the application is made by {@link BrowserBridge} - the same class port mode
     * ({@link BrowserProxyServlet}) uses.
     *
     * @param request  the request
     * @param response the response
     * @throws IOException write failure or abort
     */
    @RequestMapping("/browserctl/**")
    public void bridge(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = BrowserOrigin.withoutContext(request.getRequestURI(), request.getContextPath());
        String body = isRead(request.getMethod()) ? null
                : new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        write(response, BrowserBridge.handle(BrowserOrigin.PATH_BRIDGE_ROOT, path,
                request.getQueryString(), request.getMethod(), body, null));
    }

    // ========================================================================
    // The human
    // ========================================================================

    /**
     * Redirects {@code /browser} without a slash to the index page (the links are relative).
     *
     * @param request  for the context path
     * @param response for the redirect
     * @throws IOException if the browser does not want to follow
     */
    @GetMapping("/browser")
    public void indexRedirect(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        response.sendRedirect(request.getContextPath() + "/browser/");
    }

    /**
     * @return the index page with applications, running sessions and the LLM trail
     */
    @GetMapping(value = "/browser/", produces = "text/html;charset=UTF-8")
    @ResponseBody
    public String index() {
        StringBuilder html = new StringBuilder(8192);
        html.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta http-equiv=\"refresh\" content=\"6\">")
                .append("<title>Browser sessions</title><style>")
                .append("body{font:14px/1.5 system-ui,sans-serif;margin:2rem auto;max-width:62rem;color:#1c1c1e}")
                .append("h1{font-size:1.35rem}h2{font-size:1.05rem;margin-top:1.7rem;color:#3a3a3c}")
                .append("table{border-collapse:collapse;width:100%}th,td{text-align:left;")
                .append("border-bottom:1px solid #ddd;padding:.4rem .5rem;vertical-align:top}")
                .append("code{background:#f2f2f4;padding:.05rem .25rem;border-radius:3px}")
                .append(".on{color:#137333;font-weight:600}.off{color:#b3261e}")
                .append(".muted{color:#6e6e73}.small{font-size:.85rem}")
                .append("</style></head><body>");
        html.append("<h1>Browser sessions</h1>")
                .append("<p class=\"muted\">Open the application here and operate it as always. What ")
                .append("the browser draws, the LLM sees - and the other way round. As of M1 the LLM ")
                .append("can also write: fields are filled directly (undoable), saving goes through ")
                .append("the four-eyes review (browser.submit).</p>");

        html.append("<h2>Approved applications</h2><table><tr><th>Open</th><th>Upstream</th>")
                .append("<th>Port</th><th>Sign-in</th><th>Note</th></tr>");
        for (BrowserApp app : backend.apps().all()) {
            html.append("<tr><td><a href=\"/browserctl/open/").append(escape(app.id())).append("\">")
                    .append(escape(app.id())).append("</a></td><td><code>")
                    .append(escape(app.upstream())).append("</code></td><td>")
                    .append(app.port() > 0
                            ? "<a href=\"" + BrowserHost.url(app.id(), app.port(), "/") + "\">"
                              + escape(BrowserHost.of(app.id()) + ":" + app.port())
                              + "</a> <span class=\"small muted\">own listener</span>"
                            : "<span class=\"muted\">path mode</span>")
                    .append("</td><td>").append(escape(app.auth())).append("</td><td class=\"muted\">")
                    .append(escape(app.note()));
            // Active rewrite rules are an intervention in what the browser receives - they stay
            // named where the operator reads what the proxy does to an application (L9 pattern).
            if (!app.rewriteRules().isEmpty()) {
                html.append(" <span class=\"small\">rewrite: ");
                app.rewriteRules().forEach(rule ->
                        html.append(escape(rule.display())).append("&rarr;").append(escape(rule.to()))
                                .append(" "));
                html.append("</span>");
            }
            html.append("</td></tr>");
        }
        html.append("</table>");
        if (!backend.apps().loadErrors().isEmpty()) {
            html.append("<p class=\"small\" style=\"color:#b3261e\">Registry: ");
            backend.apps().loadErrors().forEach(problem -> html.append(escape(problem)).append("<br>"));
            html.append("</p>");
        }
        if (!backend.apps().advices().isEmpty()) {
            html.append("<h2>To decide: provenance</h2><ul class=\"small\" style=\"color:#8a6d00\">");
            backend.apps().advices().forEach(advice ->
                    html.append("<li>").append(escape(advice)).append("</li>"));
            html.append("</ul><p class=\"small muted\">Port mode = own listener, the application "
                    + "occupies the root there and our control surfaces do not exist on this "
                    + "origin. Details: <code>docs/browser.md</code> §2b.</p>");
        }

        html.append("<h2>Running sessions</h2><table><tr><th>Session</th><th>Application</th>")
                .append("<th>Bridge</th><th>Page</th><th>Rev</th><th>Frames</th><th>Requests</th>"
                        + "<th></th></tr>");
        List<BrowserSession> sessions = backend.sessions();
        if (sessions.isEmpty()) {
            html.append("<tr><td colspan=\"8\" class=\"muted\">none</td></tr>");
        }
        for (BrowserSession session : sessions) {
            ObjectNode status = session.statusNode();
            boolean alive = status.path("bridge").asBoolean(false);
            // Port-mode sessions have no prefix - their link is their own origin. Whoever sets
            // prefix + "/" here instead gets "/" and lands on the welcome page.
            String openUrl = session.prefix().isEmpty() ? session.entryUrl() : session.prefix() + "/";
            html.append("<tr><td><a href=\"").append(escape(openUrl)).append("\">")
                    .append(escape(session.id())).append("</a></td><td>")
                    .append(escape(session.app().id())).append("</td><td class=\"")
                    .append(alive ? "on\">connected" : "off\">disconnected").append("</td><td><code>")
                    .append(escape(session.pageForDisplay())).append("</code> <span class=\"muted small\">")
                    .append(escape(session.title())).append("</span></td><td>")
                    .append(status.path("rev").asInt()).append("</td><td>")
                    .append(status.path("frames").asInt()).append("</td><td>")
                    .append(status.path("requests").asLong()).append("</td><td class=\"small\">")
                    .append("<a href=\"/browserctl/session/").append(escape(session.id()))
                    .append("/close\">close</a></td></tr>");
        }
        html.append("</table>");

        if (!sessions.isEmpty()) {
            BrowserSession newest = sessions.get(0);
            html.append("<h2>LLM trail (newest session ").append(escape(newest.id()))
                    .append(")</h2><table><tr><th>Time</th><th>Tool</th><th>Outcome</th>")
                    .append("<th class=\"muted\">Arguments</th></tr>");
            List<ObjectNode> entries = newest.recentActions(12);
            if (entries.isEmpty()) {
                html.append("<tr><td colspan=\"4\" class=\"muted\">no questions asked yet</td></tr>");
            }
            for (ObjectNode entry : entries) {
                html.append("<tr><td class=\"small muted\">")
                        .append(escape(BrowserExchange.clip(entry.path("ts").asString(""), 23)))
                        .append("</td><td>").append(escape(entry.path("tool").asString("")))
                        .append("</td><td>").append(escape(entry.path("outcome").asString("")))
                        .append("</td><td class=\"small muted\"><code>")
                        .append(escape(BrowserExchange.clip(entry.path("arguments").toString(), 200)))
                        .append("</code></td></tr>");
            }
            html.append("</table><p class=\"small muted\">Capture: <code>")
                    .append(escape(newest.logsLocation("")))
                    .append("</code> - requests.jsonl, events.jsonl, snapshots.jsonl, actions.jsonl")
                    .append("</p>");
        }

        html.append("<h2>Settings</h2><ul class=\"small muted\">")
                .append("<li>writing: ").append(backend.settings().writeEnabled()
                        ? "<span class=\"off\">ON (M1)</span>" : "off").append("</li>")
                .append("<li>review bypass (browser.apps.withoutReviews): ")
                .append(backend.settings().withoutReviews().isEmpty()
                        ? "none"
                        : escape(backend.settings().withoutReviews().toString())).append("</li>")
                .append("<li>field model, M2 (browser.fields.mode): ")
                .append(backend.settings().fieldMode().name().toLowerCase(Locale.ROOT))
                .append(" <span class=\"muted\">&mdash; ")
                .append(escape(backend.settings().fieldMode().display())).append("</span>");
        if (backend.settings().fieldsOn()) {
            html.append("</li><li>field models:");
            java.util.Set<String> seen = new java.util.LinkedHashSet<>();
            for (BrowserApp app : backend.apps().all()) {
                if (seen.add(app.id())) {
                    html.append(" <code>").append(escape(app.id())).append("</code> (")
                            .append(backend.fieldModel(app.id()).size()).append(" field)");
                }
            }
            html.append("</li>");
        } else {
            html.append("</li>");
        }
        html.append("<li>inject bridge: ").append(backend.settings().injectBridge())
                .append("</li><li>relax CSP when needed: ").append(backend.settings().relaxCsp())
                .append("</li><li>long-poll: ").append(backend.settings().pollSeconds())
                .append(" s</li>")
                .append("<li>IdP simulation (browser.idp.port): <code>")
                .append(escape(BrowserIdpServer.idpOrigin())).append("</code>")
                .append(" <span class=\"muted\">- the 2FA excursion of the demo, own listener, no"
                        + " session of the proxy (docs/browser.md §3a)</span></li>")
                .append("<li>registry: <code>")
                .append(escape(String.valueOf(backend.apps().source()))).append("</code></li>")
                .append("<li>session logs: <code>")
                .append(backend.loggingProblem() == null
                        ? escape(String.valueOf(backend.runBase()))
                        : "<span class=\"off\">" + escape(backend.loggingProblem()) + "</span>")
                .append("</code></li>")
                .append("<li>project base (IDE_PROJECT_DIR): <code>")
                .append(escape(System.getProperty("IDE_PROJECT_DIR", "not set")))
                .append("</code></li>");
        // One cookie jar per host, and the host used to be the machine: worth showing next to the ports.
        html.append("<li>host per application (browser.host.suffix): ")
                .append(backend.settings().perApplicationHost()
                        ? "<span class=\"on\">" + escape(backend.settings().hostSuffix())
                          + "</span> <span class=\"muted\">- "
                          + escape(BrowserHost.label(BrowserApps.DEMO_ID) + backend.settings().hostSuffix()
                                  + " and its neighbours keep their cookies apart")
                          + "</span>"
                        : "off <span class=\"muted\">- all applications share the cookies of host "
                          + "localhost (docs/browser.md §2b)</span>")
                .append("</li>");
        String resolverAdvice = BrowserHost.resolverAdvice();
        if (!resolverAdvice.isEmpty()) {
            html.append("<li style=\"color:#8a6d00\">").append(escape(resolverAdvice)).append("</li>");
        }
        html.append("</ul>")
                .append("<p class=\"small muted\">Tool for the LLM: <code>browser</code> · ")
                .append("decisions: <code>docs/browser.md</code></p></body></html>");
        return html.toString();
    }

    /**
     * @return the session list as JSON (for the index page's self-refresh)
     */
    @GetMapping(value = "/browserctl/api/status", produces = "application/json")
    @ResponseBody
    public String status() {
        ObjectNode out = backend.mapper().createObjectNode();
        ArrayNode sessions = out.putArray("sessions");
        backend.sessions().forEach(session -> sessions.add(session.statusNode()));
        ArrayNode apps = out.putArray("apps");
        backend.apps().all().forEach(app -> apps.add(app.id()));
        out.put("writeEnabled", backend.settings().writeEnabled());
        out.put("fieldsMode", backend.settings().fieldMode().name().toLowerCase(Locale.ROOT));
        out.put("port", backend.localPort());
        return out.toString();
    }

    /**
     * Opens a session in path mode and sends the browser into its prefix.
     *
     * @param appId    application id
     * @param request  the request (for context path and port)
     * @param response for the redirect
     * @throws IOException if the browser does not want to follow
     */
    @GetMapping("/browserctl/open/{appId}")
    public void open(@PathVariable String appId, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        backend.setLocalPort(request.getLocalPort());
        try {
            if (backend.app(appId).map(known -> known.port() > 0).orElse(false)) {
                // Port mode: the application has exactly one session on its own listener.
                BrowserSession bound = backend.boundSession(appId)
                        .orElseGet(() -> backend.newBoundSession(appId));
                response.sendRedirect(bound.entryUrl());
                return;
            }
            BrowserSession session = backend.newSession(appId);
            response.sendRedirect(request.getContextPath() + session.prefix() + "/");
        } catch (IllegalArgumentException e) {
            BrowserProxyCore.sendText(response, 400, "Session not possible",
                    "<p>" + escape(e.getMessage()) + "</p>");
        }
    }

    /**
     * Closes a session - the emergency exit: afterwards the LLM sees nothing more, and the bridge
     * disappears from the page.
     *
     * @param sessionId session id
     * @param request   the request (for the context path)
     * @param response  for the redirect
     * @throws IOException if the browser does not want to follow
     */
    @RequestMapping("/browserctl/session/{sessionId}/close")
    public void close(@PathVariable String sessionId, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        backend.closeSession(sessionId);
        LOG.info("browser: session {} closed by the user", sessionId);
        response.sendRedirect(request.getContextPath() + "/browser/");
    }

    // ========================================================================
    // Odds and ends
    // ========================================================================

    private static boolean isRead(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method);
    }

    private static void write(HttpServletResponse response, BrowserBridge.Response answer)
            throws IOException {
        response.setStatus(answer.status());
        if (answer.contentType() != null) {
            response.setContentType(answer.contentType());
        }
        response.setHeader("Cache-Control", "no-store");
        byte[] bytes = answer.body() == null ? new byte[0] : answer.body().getBytes(StandardCharsets.UTF_8);
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
