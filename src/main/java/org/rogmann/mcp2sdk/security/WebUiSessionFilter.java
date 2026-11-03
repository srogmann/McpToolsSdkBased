package org.rogmann.mcp2sdk.security;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Creates (and marks) the HTTP session of every Web UI request - the counterpart of
 * {@link McpLockdownFilter}, which requires that session at {@code /mcp}.
 *
 * <p>Active only while {@link WebUiLockdown} is on; otherwise the filter passes everything
 * through and the servlet container creates sessions exactly as before. When the lockdown is
 * on, {@code /chat/*} requests always carry a session afterwards: the first HTML response
 * sets {@code JSESSIONID}, and every later same-origin browser request - including the
 * {@code /mcp} calls the Web UI performs - rides on that cookie. The user cannot log in
 * anywhere on this host anyway; the session is the identity, so it is created eagerly and
 * marked, not lazily.</p>
 */
public class WebUiSessionFilter implements Filter {

    private static final Logger LOG = LoggerFactory.getLogger(WebUiSessionFilter.class);

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (WebUiLockdown.isActive() && request instanceof HttpServletRequest httpRequest) {
            HttpSession session = httpRequest.getSession(true);
            WebUiLockdown.mark(session);
        }
        chain.doFilter(request, response);
    }
}
