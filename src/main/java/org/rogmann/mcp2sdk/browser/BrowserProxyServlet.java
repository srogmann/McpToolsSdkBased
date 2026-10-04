package org.rogmann.mcp2sdk.browser;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The only handler on an application port.
 *
 * <p>Exactly two decisions, and that is the whole point of the second listener: either the path
 * belongs to us ({@link BrowserOrigin#PORT_BRIDGE_ROOT}) and is answered by {@link BrowserBridge}, or
 * it belongs to the application and goes through the proxy. There is no third possibility here and no
 * routes somebody forgets with the next feature - {@code /mcp}, {@code /review}, {@code /chat} simply
 * do not exist on this origin.</p>
 */
public final class BrowserProxyServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private final transient BrowserApp app;
    private final transient BrowserSession session;
    private final transient BrowserOrigin origin;

    /**
     * @param app     the application operated on this port
     * @param session the session of this port (port = browsing context, no session section in the URL)
     */
    BrowserProxyServlet(BrowserApp app, BrowserSession session) {
        this.app = app;
        this.session = session;
        this.origin = new BrowserOrigin.Port(session.id());
    }

    /**
     * @param request  the request
     * @param response the response
     * @throws IOException write failure or the browser's abort
     */
    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = BrowserOrigin.withoutContext(request.getRequestURI(), request.getContextPath());
        if (origin.isBridge(path)) {
            String body = isGet(request.getMethod()) ? null
                    : new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            write(response, BrowserBridge.handle(origin.bridgeRoot(), path, request.getQueryString(),
                    request.getMethod(), body, session));
            return;
        }
        BrowserProxyCore.handle(app, session, origin, request, response);
    }

    private static boolean isGet(String method) {
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
}
