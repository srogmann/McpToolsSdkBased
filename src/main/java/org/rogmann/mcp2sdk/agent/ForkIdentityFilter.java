package org.rogmann.mcp2sdk.agent;

import io.modelcontextprotocol.spec.HttpHeaders;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Reads the identity of a fork thread off the request and writes it into {@link ForkIdentity}
 * (docs/subagent.md §14, P0.2), and the conversation of an MCP session into
 * {@link McpConversations} (docs/acp.md §6, S6 — {@code prefix_fork} ties a call to the
 * session that made it). Registered in front of {@code /mcp/*} by {@code McpConfig}.
 *
 * <p>The filter is deliberately stupid: three headers in, two map entries out, then straight through.
 * It never refuses a request - a thread whose identity did not arrive is not a threat, it is
 * simply someone the board does not know, and the tool that needs the identity says so. Refusing
 * here would also mean deciding for every MCP client on this port, which is not this filter's
 * business.</p>
 *
 * <p>Why not a {@code ThreadLocal}: the tool call is not served on the thread that read the
 * request (the transport hands it over), so the pairing has to live somewhere the request thread
 * and the call thread both see. That is the registry, and {@link ForkCallers} carries it the last
 * step - into the worker thread of the call that needs it.</p>
 */
public class ForkIdentityFilter implements Filter {

    private static final Logger LOG = LoggerFactory.getLogger(ForkIdentityFilter.class);

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest httpRequest) {
            try {
                String sessionId = httpRequest.getHeader(HttpHeaders.MCP_SESSION_ID);
                ForkIdentity.getInstance().register(sessionId,
                        httpRequest.getHeader(ForkIdentity.HEADER));
                McpConversations.getInstance().register(sessionId,
                        httpRequest.getHeader(ChatContextStore.HEADER_CONVERSATION_ID));
            } catch (RuntimeException e) {
                // Identity is an observation, never a reason to break a request.
                LOG.warn("Fork identity could not be recorded", e);
            }
        }
        chain.doFilter(request, response);
    }
}
