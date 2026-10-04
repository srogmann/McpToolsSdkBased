package org.rogmann.mcp2sdk.browser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * The endpoints of the bridge - <b>one</b> implementation, two front doors.
 *
 * <p>In path mode they hang under {@code /browserctl/…} in the normal Spring webapp, in port mode
 * under {@code /.browserproxy/…} in a bare Tomcat listener with a single servlet. Both call
 * {@link #handle} with their prefix; the difference stays confined to the path resolution (path mode:
 * the session stands in the URL, port mode: the port <em>is</em> the session).</p>
 *
 * <p>Why this is its own class and not two implementations: a channel you build twice drifts. The
 * bridge is the place where sessions get connected, where snapshots land and where write commands are
 * turned away - accuracy matters more there than the question of who does the routing.</p>
 */
final class BrowserBridge {

    private static final Logger LOG = LoggerFactory.getLogger(BrowserBridge.class);

    /** The one place the bridge script lies on the classpath. */
    private static final String SCRIPT_RESOURCE = "browser/bridge.js";

    private static volatile String scriptCache;

    private BrowserBridge() {
    }

    /**
     * A finished response, so that both front doors can share without servlet assumptions.
     *
     * @param status      HTTP status
     * @param contentType content type or {@code null}
     * @param body        the text body
     */
    record Response(int status, String contentType, String body) {

        /**
         * @return a JSON response
         */
        static Response json(int status, String payload) {
            return new Response(status, "application/json;charset=UTF-8", payload);
        }

        /**
         * @return a text response with status
         */
        static Response text(int status, String payload) {
            return new Response(status, "text/plain;charset=UTF-8", payload);
        }
    }

    /**
     * Carries out a bridge request.
     *
     * @param root         the prefix of the caller ({@link BrowserOrigin#PATH_BRIDGE_ROOT} or
     *                     {@link BrowserOrigin#PORT_BRIDGE_ROOT})
     * @param path         the requested track (without context path, without query), starts with
     *                     {@code root}
     * @param query        the query of the request or {@code null} - {@code poll} waits according to
     *                     {@code ?wait=}
     * @param method       HTTP method
     * @param body         the request body, {@code null} on GET
     * @param boundSession the session of the caller, or {@code null} when the session has to be read
     *                     out of the path (path mode)
     * @return the response
     */
    static Response handle(String root, String path, String query, String method, String body,
            BrowserSession boundSession) {
        String tail = path.length() > root.length() ? path.substring(root.length()) : "/";
        if (tail.equals("/__bridge.js") || tail.equals("__bridge.js")) {
            return script();
        }
        // Path mode: /browserctl/session/<id>/poll   Port mode: /.browserproxy/poll
        String sessionId = boundSession == null ? "" : boundSession.id();
        String operation = trimSlash(tail);
        boolean sessionInPath = false;
        if (operation.startsWith("session/")) {
            sessionInPath = true;
            String rest = operation.substring("session/".length());
            int slash = rest.indexOf('/');
            sessionId = slash < 0 ? rest : rest.substring(0, slash);
            operation = slash < 0 ? "" : rest.substring(slash + 1);
        }
        final String bridgeBase = sessionInPath ? root + "/session/" + sessionId : root;
        BrowserSession session = boundSession != null ? boundSession
                : BrowserSessions.get().session(sessionId).orElse(null);
        if (session == null) {
            return Response.json(410, "{\"ok\":false,\"error\":\"session-gone\"}");
        }
        // Which session the document thinks it belongs to, said by the document itself (it reads the id
        // out of its own script URL). A different id means a tab from a previous server start: in port
        // mode the path carries no session at all, so without this check that tab would report into the
        // fresh session's log and take its commands. 410 is the answer the bridge already understands -
        // it stops the loop and shows "disconnected" on the badge, which is the honest thing to show.
        String launched = queryValue(query, "s");
        if (!launched.isEmpty() && !launched.equals(session.id())) {
            session.noteStaleChannel(operation, launched);
            return Response.json(410, "{\"ok\":false,\"error\":\"stale-session\",\"session\":\""
                    + session.id() + "\",\"askedFor\":\"" + launched + "\",\"hint\":\"this document "
                    + "was loaded before the session existed - reload it in the browser (the session "
                    + "lives in memory, the tab does not know about a restart)\"}");
        }
        if (launched.isEmpty() && boundSession != null) {
            // A port-mode channel request that names no session: a bridge from before this parameter
            // existed, or a hand-written URL. Not punished (nothing proves it is stale), but counted -
            // so that "whoever wrote this line was not the document you are looking at" stays sayable.
            session.noteUnnamedChannel(operation);
        }
        boolean get = "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
        switch (operation) {
            case "hello":
                return Response.json(200, session.bridgeConfig(bridgeBase).toString());
            case "poll":
                // The bridge waits along; capped at the top so that a rigged value cannot bind a
                // servlet worker for minutes.
                return Response.json(200, session.pollCommands(waitMillis(query)));
            case "post":
                return get ? Response.text(405, "POST expected") : reports(session, body);
            case "command":
                return get ? Response.text(405, "POST expected") : commands(session, body);
            default:
                return Response.text(404, "known bridge endpoints: hello, poll, post, command");
        }
    }

    /**
     * @return the bridge script, read once and then held (it hangs in every document)
     */
    static Response script() {
        String script = scriptCache;
        if (script == null) {
            // The loader of THIS class, and only as a fallback the thread context one. On an
            // application port the request runs on a thread of the bare second Tomcat
            // (BrowserListener), and that thread does not carry Spring Boot's launcher loader - in the
            // repackaged jar the script sits in BOOT-INF/classes, which the plain application loader
            // never sees. The result was a silent 500 on every port-mode document: the page proxied
            // fine, the bridge never arrived, and the path-mode tests could not show it.
            InputStream found = open(SCRIPT_RESOURCE, BrowserBridge.class.getClassLoader());
            if (found == null) {
                found = open(SCRIPT_RESOURCE, Thread.currentThread().getContextClassLoader());
            }
            if (found == null) {
                LOG.error("browser: {} is not on the classpath of {} nor of the context loader - no "
                                + "document can be bridged", SCRIPT_RESOURCE,
                        BrowserBridge.class.getClassLoader());
                return Response.text(500, "bridge script missing from the classpath: " + SCRIPT_RESOURCE);
            }
            try (InputStream in = found) {
                script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                LOG.error("browser: {} could not be read", SCRIPT_RESOURCE, e);
                return Response.text(500, "bridge script not readable");
            }
            scriptCache = script;
            LOG.info("browser: bridge script loaded ({} bytes, v{})", script.length(),
                    BrowserRewriter.BRIDGE_VERSION);
        }
        return new Response(200, "text/javascript;charset=UTF-8", script);
    }

    /**
     * @param resource classpath resource to look up
     * @param loader   the loader to ask, may be {@code null}
     * @return the stream, or {@code null} when this loader does not know the resource
     */
    private static InputStream open(String resource, ClassLoader loader) {
        return loader == null ? null : loader.getResourceAsStream(resource);
    }

    private static Response reports(BrowserSession session, String body) {
        JsonNode parsed;
        try {
            parsed = BrowserSessions.get().mapper().readTree(body == null ? "{}" : body);
        } catch (RuntimeException e) {
            return Response.json(400, "{\"ok\":false,\"error\":\"bad-json\"}");
        }
        JsonNode reply;
        if (parsed.isArray()) {
            ObjectNode last = BrowserSessions.get().mapper().createObjectNode();
            for (JsonNode item : parsed) {
                last = (ObjectNode) session.handleBridgeMessage(item);
            }
            reply = last;
        } else {
            reply = session.handleBridgeMessage(parsed);
        }
        return Response.json(200, reply.toString());
    }

    private static Response commands(BrowserSession session, String body) {
        if (!BrowserSessions.get().settings().writeEnabled()) {
            ObjectNode denied = BrowserSessions.get().mapper().createObjectNode();
            denied.put("ok", false);
            denied.put("error", "write-ops-disabled");
            denied.put("hint", "Writing is switched off (browser.write.enabled=false). When it is "
                    + "on: fill/undo run directly, submits go through the four-eyes review "
                    + "(docs/browser.md §7).");
            return Response.json(200, denied.toString());
        }
        JsonNode parsed;
        try {
            parsed = BrowserSessions.get().mapper().readTree(body == null ? "[]" : body);
        } catch (RuntimeException e) {
            return Response.json(400, "{\"ok\":false,\"error\":\"bad-json\"}");
        }
        ObjectNode reply = BrowserSessions.get().mapper().createObjectNode();
        ArrayNode accepted = reply.putArray("accepted");
        Iterable<JsonNode> operations = parsed.isArray() ? parsed : List.of(parsed);
        for (JsonNode operation : operations) {
            ObjectNode command = BrowserSessions.get().mapper().createObjectNode();
            String id = "op-" + System.nanoTime();
            command.put("id", id);
            if (operation.isObject()) {
                for (Map.Entry<String, JsonNode> field : operation.properties()) {
                    command.set(field.getKey(), field.getValue());
                }
            }
            session.enqueueCommand(command);
            session.recordAction("command:" + command.path("t").asString(""), command, "queued", id);
            accepted.add(id);
        }
        reply.put("ok", true);
        reply.put("note", "commands are queued for the bridge; feedback arrives over post");
        return Response.json(200, reply.toString());
    }

    /**
     * One value out of the query string, without pretending to be a URL parser.
     *
     * @param query the query of the request (no leading {@code ?}), may be {@code null}
     * @param name  parameter to look for
     * @return the value, empty when the parameter is absent or blank
     */
    static String queryValue(String query, String name) {
        if (query == null || query.isBlank()) {
            return "";
        }
        for (String pair : query.split("&")) {
            int at = pair.indexOf('=');
            if (at <= 0) {
                continue;
            }
            if (name.equals(pair.substring(0, at))) {
                String raw = pair.substring(at + 1);
                try {
                    return URLDecoder.decode(raw, StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    // A value that is not valid percent-encoding ("%", a stray "%zz"). Handing back the
                    // raw text is right here - the only callers ask for a session id or a number, and for
                    // both the raw form is what a working bridge would have sent - but a query we could
                    // not decode is worth one line: it says somebody other than the bridge is talking.
                    LOG.info("browser: query parameter {} is not percent-encoded ('{}'), taken as it "
                            + "stands: {}", name, raw, e.getMessage());
                    return raw;
                }
            }
        }
        return "";
    }

    /**
     * The wanted waiting time out of {@code ?wait=8000}.
     *
     * @param query the query of the request, may be {@code null}
     * @return the waiting time, capped at the top
     */
    private static long waitMillis(String query) {
        long wanted = 8000L;
        String at = query == null ? "" : query;
        int start = at.indexOf("wait=");
        if (start >= 0) {
            String digits = at.substring(start + 5).replaceAll("[^0-9].*$", "");
            if (!digits.isEmpty()) {
                try {
                    wanted = Long.parseLong(digits);
                } catch (NumberFormatException ignored) {
                    // stays at the default; a nonsense value must not lengthen the poll
                }
            }
        }
        long cap = BrowserSessions.get().settings().pollSeconds() * 1000L;
        return Math.min(Math.max(0L, wanted), cap);
    }

    private static String trimSlash(String value) {
        String out = value;
        while (out.startsWith("/")) {
            out = out.substring(1);
        }
        int query = out.indexOf('?');
        return query < 0 ? out : out.substring(0, query);
    }
}
