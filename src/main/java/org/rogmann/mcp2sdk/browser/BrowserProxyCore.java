package org.rogmann.mcp2sdk.browser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The actual pass-through: write the request out, rewrite the response, log both.
 *
 * <p>Its own class because two front doors need the same body: {@link BrowserProxyController}
 * (path mode, Spring) and {@link BrowserProxyServlet} (port mode, bare Tomcat listener). What
 * distinguishes them is only the <b>provenance</b> - the {@link BrowserOrigin}, which is simply handed
 * through here.</p>
 *
 * <p>Flow of a response, in this order and with a reason:</p>
 * <ol>
 *   <li>target address from the upstream base and the remaining path ({@link #buildTarget}) -
 *       root-absolute means the server root of the application, not one of its sub-paths.</li>
 *   <li>headers cleaned on the way up ({@link BrowserProxyClient}), body buffered.</li>
 *   <li>rewrite, but only what is textual: HTML (attributes, base, bridge), CSS ({@code url()}),
 *       JS/JSON (absolute URLs). Binaries stay byte for byte.</li>
 *   <li>response head: status (301→302), {@code Set-Cookie} (Domain gone, Path → prefix),
 *       {@code Location}, CSP, {@code X-Frame-Options}.</li>
 *   <li>last comes the capture - redacted on the way in ({@link BrowserSecrets}).</li>
 * </ol>
 */
final class BrowserProxyCore {

    private static final Logger LOG = LoggerFactory.getLogger(BrowserProxyCore.class);

    /**
     * Application id + host name that arrived unexpectedly - each pair is warned about once, not on
     * every request of the session. The information stays in the capture regardless, because the
     * capture is where the two cookie histories meet.
     */
    private static final Set<String> surprisedHosts =
            ConcurrentHashMap.newKeySet();

    private BrowserProxyCore() {
    }

    /**
     * @param app      the application being called
     * @param session  the browser session
     * @param origin   provenance (path or port mode)
     * @param request  the browser's request
     * @param response the response that is written here
     * @throws IOException when the browser gives up
     */
    static void handle(BrowserApp app, BrowserSession session, BrowserOrigin origin,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        String rest = origin.remainder(request.getRequestURI(), request.getContextPath());
        if (rest.isEmpty()) {
            // The entry without a path: /browser/<app>/<session> -> /browser/<app>/<session>/, so that
            // the page's relative entries resolve correctly. In port mode rest is always "/".
            if (!origin.entryPrefix().isEmpty()) {
                response.sendRedirect(request.getContextPath() + origin.entryPrefix() + "/");
                return;
            }
            rest = "/";
        }
        URI upstreamBase = upstreamBaseOf(app);
        URI target = buildTarget(upstreamBase, rest, request.getQueryString());
        String upstreamPath = target.getPath();

        byte[] requestBody = readBody(request, response);
        if (requestBody == null) {
            return;                                       // too large, the 413 answer is already out
        }
        long started = System.currentTimeMillis();
        BrowserProxyClient.UpstreamResponse upstream;
        try {
            upstream = BrowserProxyClient.get().send(request.getMethod(), target,
                    headersOf(request), requestBody, upstreamBase, request.getRemoteAddr());
        } catch (IOException e) {
            LOG.warn("browser: {} {} not reachable: {}", request.getMethod(), target, e.toString());
            session.recordExchange(request.getMethod(), rest, upstreamPath, request.getQueryString(),
                    0, request.getContentType(), textOf(requestBody, StandardCharsets.UTF_8),
                    "text/plain", "Upstream not reachable: " + e.getMessage(), 0L,
                    System.currentTimeMillis() - started, List.of("upstream-error"));
            sendText(response, 502, "Application not reachable",
                    "<p>" + escape(app.upstream()) + " does not answer: " + escape(e.toString())
                            + "</p><p>The proxy has changed nothing about the sign-in - check the VPN,"
                            + " the host name and whether the application itself is reachable.</p>");
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            response.sendError(504, "Aborted while proxying");
            return;
        }
        long duration = System.currentTimeMillis() - started;
        byte[] outBody = upstream.body() == null ? new byte[0] : upstream.body();
        String contentType = upstream.first("Content-Type");
        boolean textual = isTextual(contentType);
        Charset charset = charsetOf(contentType);

        String nonce = BrowserRewriter.extractCspNonce(upstream.first("Content-Security-Policy"));
        BrowserRewriter rewriter = new BrowserRewriter(app, BrowserSessions.get().apps(),
                session.id(), origin, BrowserSessions.get().settings().relaxCsp(),
                BrowserSessions.get().settings().injectBridge(), nonce);
        String rewritten = null;
        if (textual) {
            try {
                rewritten = rewriteAs(contentType, rewriter, new String(outBody, charset), upstreamPath);
            } catch (RuntimeException e) {
                LOG.warn("browser: rewriting {} failed, passed through unchanged: {}",
                        upstreamPath, e.toString());
            }
        }
        byte[] outBytes = rewritten == null ? outBody : rewritten.getBytes(charset);

        writeResponseHeaders(response, upstream, rewriter, origin, upstreamPath, outBytes.length);
        try {
            response.getOutputStream().write(outBytes);
        } catch (IOException e) {
            LOG.debug("browser: client aborted {}: {}", upstreamPath, e.getMessage());
        }

        String requestText = textualOrForm(request.getContentType())
                ? textOf(requestBody, StandardCharsets.UTF_8) : summaryOf(requestBody);
        session.recordExchange(request.getMethod(), rest, upstreamPath, request.getQueryString(),
                upstream.status(), request.getContentType(), requestText, contentType,
                textual ? rewritten : summaryOf(outBody), outBytes.length, duration,
                notesFor(upstream, app, request, upstreamPath, session));
    }

    // ========================================================================
    // Address and headers
    // ========================================================================

    /**
     * @param app the registry entry
     * @return the real base URL; {@code self:} targets point at the <b>normal port</b> of this webapp
     */
    private static URI upstreamBaseOf(BrowserApp app) {
        if (app.isSelf()) {
            // Deliberately not request.getLocalPort(): an application listener forwarding to itself
            // turns in a circle - and that is exactly what happens with a self: application on its own
            // port if you take the port that is asking.
            return URI.create("http://127.0.0.1:" + BrowserSessions.get().localPort() + app.selfPath());
        }
        return URI.create(app.upstream());
    }

    /**
     * Assembles the target address from the application's origin, the remaining path and the query.
     *
     * <p>In path mode the remaining path is <em>root-absolute on our origin</em> and therefore exactly
     * what would be the root of the application's origin without us - so it is <b>not</b> appended to
     * the base path of the registration (an application on {@code https://host/portal/} that sends
     * {@code /api/x} means {@code /api/x}, not {@code /portal/api/x}). The base path only applies on
     * the entry. In port mode the matter is simpler: the remaining path <em>is</em> the application
     * path.</p>
     *
     * @param base  the base URL of the application (including its base path)
     * @param rest  the path behind the session prefix
     * @param query the query, may be {@code null}
     * @return the target address
     */
    static URI buildTarget(URI base, String rest, String query) {
        String basePath = base.getPath() == null || base.getPath().isEmpty() ? "/" : base.getPath();
        String path = (rest.isEmpty() || rest.equals("/")) ? basePath
                : (rest.startsWith("/") ? rest : "/" + rest);
        String scheme = base.getScheme();
        String authority = base.getAuthority();
        try {
            return new URI(scheme + "://" + authority + path + (query == null ? "" : "?" + query));
        } catch (URISyntaxException e) {
            try {
                // Characters real applications show in query values that URI rejects (spaces, {}, "|"):
                // the many-argument constructor masks them.
                return new URI(scheme, authority, path, query, null);
            } catch (URISyntaxException | IllegalArgumentException fallbackFailure) {
                throw new IllegalArgumentException("target address cannot be built: " + path, e);
            }
        }
    }

    private static Map<String, List<String>> headersOf(HttpServletRequest request) {
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name, Collections.list(request.getHeaders(name)));
        }
        return headers;
    }

    /**
     * Reads the request body - M0 buffers (limit {@code browser.proxy.maxBodyBytes}).
     *
     * @param request  the request
     * @param response for the 413 answer
     * @return the body, or {@code null} if it was too large (the answer is written then)
     * @throws IOException read failure
     */
    private static byte[] readBody(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        int max = BrowserSessions.get().settings().maxBodyBytes();
        long declared = request.getContentLengthLong();
        if (declared > max) {
            tooLarge(response, declared, max);
            return null;
        }
        byte[] body = request.getInputStream().readAllBytes();
        if (body.length > max) {
            tooLarge(response, body.length, max);
            return null;
        }
        return body;
    }

    private static void tooLarge(HttpServletResponse response, long actual, int max) throws IOException {
        LOG.info("browser: request body {} bytes over the limit {} - not passed through", actual, max);
        sendText(response, 413, "Request too large",
                "<p>The body of this request is " + actual + " bytes, the proxy's limit is "
                        + max + ". M0 buffers requests (docs/browser.md §8); please keep doing file"
                        + " uploads directly in the application.</p>");
    }

    /**
     * Writes status and headers so that the browser stays with us.
     *
     * @param response     the response
     * @param upstream     the upstream response
     * @param rewriter     the rewriter of this session
     * @param origin       the provenance (decides the cookie path)
     * @param upstreamPath the upstream path of the request (for the relative {@code Location})
     * @param bodyLength   the actual length of the body
     */
    private static void writeResponseHeaders(HttpServletResponse response,
            BrowserProxyClient.UpstreamResponse upstream, BrowserRewriter rewriter,
            BrowserOrigin origin, String upstreamPath, int bodyLength) {
        int status = upstream.status();
        if (status == 301) {
            // A redirect that is remembered permanently outlives the browser, even when the registry or
            // the port changes. 301 becomes 302: same result, no aftermath.
            status = 302;
        } else if (status == 308) {
            status = 307;                                  // method preservation stays, durability does not
        }
        response.setStatus(status);
        for (Map.Entry<String, List<String>> entry : upstream.headers().entrySet()) {
            String name = entry.getKey();
            if (name == null || isHandled(name)) {
                continue;
            }
            for (String value : entry.getValue()) {
                response.addHeader(name, value);
            }
        }
        for (String cookie : cookiesOf(upstream)) {
            response.addHeader("Set-Cookie", rewriteCookie(cookie, origin));
        }
        String location = upstream.first("Location");
        if (location != null) {
            response.setHeader("Location", rewriter.location(location, upstreamPath));
        }
        String csp = upstream.first("Content-Security-Policy");
        if (csp != null) {
            String rewrittenCsp = rewriter.rewriteCsp(csp, BrowserRewriter.extractCspNonce(csp));
            if (rewrittenCsp != null && !rewrittenCsp.isBlank()) {
                response.setHeader("Content-Security-Policy", rewrittenCsp);
            }
        }
        if (upstream.first("X-Frame-Options") != null) {
            // With us frames are same-origin; DENY locks the application out as soon as it embeds
            // itself in one of its own frames.
            response.setHeader("X-Frame-Options", "SAMEORIGIN");
        }
        response.setContentLength(bodyLength);
    }

    private static List<String> cookiesOf(BrowserProxyClient.UpstreamResponse upstream) {
        for (Map.Entry<String, List<String>> entry : upstream.headers().entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase("Set-Cookie")) {
                return entry.getValue();
            }
        }
        return List.of();
    }

    private static boolean isHandled(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals("content-length") || lower.equals("transfer-encoding")
                || lower.equals("set-cookie") || lower.equals("location")
                || lower.equals("content-security-policy") || lower.equals("x-frame-options")
                || lower.equals("connection") || lower.equals("date")
                || lower.startsWith("access-control-");
    }

    /**
     * A {@code Set-Cookie}, rewritten so that the browser keeps it and sends it back to us:
     * {@code Domain} gone (otherwise it would belong to a foreign origin), {@code Path} set to the
     * session prefix (in port mode simply {@code /}, i.e. unchanged) and {@code Secure} only with real
     * HTTPS. The cookie stays in the <b>browser</b> - the proxy keeps no jar (decision L3).
     *
     * @param cookie the value we received
     * @param origin the provenance of this session
     * @return the rewritten value
     */
    private static String rewriteCookie(String cookie, BrowserOrigin origin) {
        String path = origin.entryPrefix().isEmpty() ? "/" : origin.entryPrefix();
        StringBuilder out = new StringBuilder(cookie.length() + 16);
        boolean hasPath = false;
        for (String part : cookie.split(";")) {
            String item = part.trim();
            if (item.isEmpty()) {
                continue;
            }
            String name = item.split("=", 2)[0].trim();
            if (name.equalsIgnoreCase("Domain")) {
                continue;
            }
            if (name.equalsIgnoreCase("Path")) {
                item = "Path=" + path;
                hasPath = true;
            }
            if (name.equalsIgnoreCase("Secure") && BrowserSessions.get().settings().dropSecure()) {
                LOG.debug("browser: removed the Secure flag of a cookie (HTTP operation)");
                continue;
            }
            out.append(out.isEmpty() ? "" : "; ").append(item);
        }
        if (!hasPath) {
            out.append(out.isEmpty() ? "" : "; ").append("Path=").append(path);
        }
        return out.toString();
    }

    private static String rewriteAs(String contentType, BrowserRewriter rewriter, String body,
            String upstreamPath) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (type.contains("html")) {
            return rewriter.html(body, upstreamPath);
        }
        if (type.contains("css")) {
            return rewriter.css(body, upstreamPath);
        }
        return rewriter.textish(body);
    }

    private static boolean isTextual(String contentType) {
        if (contentType == null) {
            return false;
        }
        String type = contentType.toLowerCase(Locale.ROOT);
        return type.contains("html") || type.contains("xml") || type.contains("css")
                || type.contains("javascript") || type.contains("json") || type.contains("text/")
                || type.contains("svg");
    }

    private static boolean textualOrForm(String contentType) {
        if (contentType == null) {
            return false;
        }
        String type = contentType.toLowerCase(Locale.ROOT);
        return type.contains("json") || type.contains("xml") || type.contains("text")
                || type.contains("form-urlencoded") || type.contains("javascript");
    }

    /**
     * The declared character set of a response - or UTF-8. Anyone guessing here destroys umlauts:
     * decoding on the wrong assumption is not reversible.
     *
     * @param contentType the content type
     * @return the character set
     */
    private static Charset charsetOf(String contentType) {
        if (contentType != null) {
            String lower = contentType.toLowerCase(Locale.ROOT);
            int at = lower.indexOf("charset=");
            if (at >= 0) {
                String name = contentType.substring(at + 8).trim();
                int end = name.indexOf(';');
                if (end >= 0) {
                    name = name.substring(0, end);
                }
                name = name.replace("\"", "").replace("'", "").trim();
                try {
                    return Charset.forName(name);
                } catch (IllegalArgumentException e) {
                    // UnsupportedCharsetException is an IllegalArgumentException
                    LOG.debug("browser: unknown character set {}, using UTF-8", name);
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static String textOf(byte[] body, Charset charset) {
        return body == null || body.length == 0 ? "" : new String(body, charset);
    }

    private static String summaryOf(byte[] body) {
        return body == null || body.length == 0 ? "" : "‹" + body.length + " bytes, not textual›";
    }

    private static List<String> notesFor(BrowserProxyClient.UpstreamResponse upstream, BrowserApp app,
            HttpServletRequest request, String upstreamPath, BrowserSession session) {
        List<String> notes = new ArrayList<>();
        List<String> cookies = cookiesOf(upstream);
        if (!cookies.isEmpty()) {
            notes.add(cookies.size() + " Set-Cookie rewritten");
        }
        if (upstream.first("Content-Security-Policy") != null) {
            notes.add("CSP adjusted");
        }
        String dest = request.getHeader("Sec-Fetch-Dest");
        if (dest != null && !dest.equals("document")) {
            notes.add("dest=" + dest);
        }
        if (app.isSensitivePath(upstreamPath)) {
            notes.add("sensitive path");
        }
        // Reached under another name than the one we hand out? Then this application now has two cookie
        // jars (cookies belong to a host, never to a port) and a session that was signed in under the
        // first name looks broken under the second. Named once in the log and in the capture, because
        // the symptom - "it forgets my login" - points at nothing else.
        String offeredAs = BrowserHost.of(app.id());
        String cameInAs = request.getServerName();
        if (cameInAs != null && !cameInAs.equalsIgnoreCase(offeredAs)) {
            notes.add("host=" + cameInAs + ", offered as " + offeredAs);
            if (surprisedHosts.add(app.id() + "/" + cameInAs)) {
                LOG.warn("browser: application '{}' is reached as '{}' but offered as '{}' - the two "
                                + "names keep separate cookies, so a sign-in made under one is missing "
                                + "under the other. Use the address action=open names (registry mode:"
                                + " browser.host.suffix={})",
                        app.id(), cameInAs, offeredAs, BrowserSessions.get().settings().hostSuffix());
            }
        }
        // Attribution of a navigational form submit (docs/browser.md §7.5): the gate sits in the
        // tool (L8) - this note only tells the capture apart who pressed the button. Best effort:
        // an approved LLM submit is consumed once, everything else counts as implicit consent.
        if ("POST".equalsIgnoreCase(request.getMethod())
                && "navigate".equalsIgnoreCase(request.getHeader("Sec-Fetch-Mode"))) {
            String reviewId = session.matchLlmSubmit();
            notes.add(reviewId != null
                    ? "review=" + reviewId + " (llm submit)"
                    : "review=implicit (user submit)");
        }
        return notes;
    }

    static void sendText(HttpServletResponse response, int status, String title, String bodyHtml)
            throws IOException {
        response.setStatus(status);
        response.setContentType("text/html;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("<!DOCTYPE html><html lang=\"en\"><meta charset=\"utf-8\"><title>"
                + escape(title) + "</title>"
                + "<body style=\"font:14px/1.5 system-ui,sans-serif;margin:2rem;max-width:44rem\">"
                + "<h1 style=\"font-size:1.2rem\">" + escape(title) + "</h1>" + bodyHtml
                + "<p><a href=\"/browser/\">Application list</a></p></body></html>");
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
