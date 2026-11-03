package org.rogmann.mcp2sdk.security;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Locks the MCP endpoints down to Web UI sessions while {@link WebUiLockdown} is active.
 *
 * <p><b>What it refuses.</b> Every request to {@code /mcp}, {@code /mcp/*}, {@code /mcp/sse}
 * or {@code /mcp/sse/*} whose session is not a live, Web-UI-marked session gets {@code 403}
 * without further detail. That covers, on a shared host (Citrix and the like), a user who
 * points a client at the instance of another user, and any local process that talks to
 * {@code localhost:909x} directly. An invented session id does not pass: the guard looks the
 * id up in the servlet container, it does not compare strings from the wire.</p>
 *
 * <p><b>What it accepts.</b> The browser of the very user who owns the instance: its
 * {@code /mcp} calls are same-origin requests carrying the {@code JSESSIONID} cookie that
 * {@link WebUiSessionFilter} handed out on {@code /chat/*}. The cookie is the only secret on
 * such a host that belongs to one user alone (the cookie jar lives in that user's Windows
 * profile), so the session is the identity.</p>
 *
 * <p><b>Fail-closed, no exceptions.</b> The SDK's own initialize/listTools/callTool never see
 * a request this filter has refused, and there is no fallback onto the MCP session id or any
 * other value: a fallback would silently reopen the hole this filter exists for. External
 * MCP clients (Cline, opencode, scripts) are refused by definition of the mode - without the
 * lockdown they work as always.</p>
 *
 * <p>Registered by {@code McpConfig} on the four MCP URL patterns, ordered in front of the
 * observing {@code ForkIdentityFilter} (refusing before observing).</p>
 */
public class McpLockdownFilter implements Filter {

    private static final Logger LOG = LoggerFactory.getLogger(McpLockdownFilter.class);

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (WebUiLockdown.isActive() && request instanceof HttpServletRequest httpRequest) {
            HttpSession session = httpRequest.getSession(false);
            if (!WebUiLockdown.isMarked(session)) {
                HttpServletResponse httpResponse = (HttpServletResponse) response;
                // No detail in the response: the reason would only help an attacker.
                httpResponse.setStatus(HttpServletResponse.SC_FORBIDDEN);
                // Logged at debug: on a shared host the refusal itself is the interesting
                // event, but it can be produced trivially, so it must not flood INFO.
                LOG.debug("MCP lockdown: refused {} {} (no live Web UI session)",
                        httpRequest.getMethod(), httpRequest.getRequestURI());
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
