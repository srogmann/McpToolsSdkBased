package org.rogmann.mcp2sdk.security;

import jakarta.servlet.http.HttpSession;

/**
 * Locked-down HTTP surface for shared hosts (Citrix / terminal server / any machine where
 * several users share the loopback interface).
 *
 * <p><b>The threat model.</b> On such a host every user starts their own instance of this
 * server on their own port, but the ports are just an addressing scheme, not a boundary:
 * {@code localhost} is shared, so user B can reach the instance of user A on
 * {@code localhost:909x}. The only secret user B cannot obtain is user A's HTTP session id -
 * it lives in A's browser process and in A's Windows user profile (cookie jar isolation).
 * The session id is therefore the user identity of this mode.</p>
 *
 * <p><b>The rule.</b> When the lockdown is active, the MCP endpoints ({@code /mcp},
 * {@code /mcp/sse}) accept requests only from a live HTTP session that the Web UI created
 * (marker attribute, see {@link #SESSION_ATTRIBUTE}). There are no exceptions: under the
 * lockdown an external MCP client (Cline, opencode, a script) is refused by definition,
 * because the port is not a boundary on such a host.</p>
 *
 * <p><b>Switch.</b> Enabled with {@code -Dwebui.lockdown=true}. The property is read on
 * every check, but the filters and the startup log treat the value at startup time as
 * binding: a mode change needs a restart.</p>
 *
 * <p>This class carries the configuration only; the two filters
 * ({@link WebUiSessionFilter}, {@link McpLockdownFilter}) apply it, and the Web UI passes
 * the check because same-origin browser requests ride on the session cookie.</p>
 */
public final class WebUiLockdown {

    /** Primary system property ({@code -Dwebui.lockdown=true}). */
    public static final String PROP_LOCKDOWN = "webui.lockdown";

    /**
     * Session attribute that marks a session as created through the Web UI. The MCP
     * guard requires it; {@link WebUiSessionFilter} sets it.
     */
    public static final String SESSION_ATTRIBUTE = WebUiLockdown.class.getName() + ".webUiSession";

    private WebUiLockdown() {
    }

    /**
     * Whether the lockdown mode is active.
     * @return {@code true} when {@code webui.lockdown=true} is set
     */
    public static boolean isActive() {
        return truthy(System.getProperty(PROP_LOCKDOWN));
    }

    /**
     * Marks an HTTP session as a Web UI session. Called by {@link WebUiSessionFilter} on
     * every {@code /chat/*} request, so a session exists (and is marked) before the browser
     * reaches {@code /mcp}.
     *
     * @param session the live session to mark, never {@code null}
     */
    public static void mark(HttpSession session) {
        session.setAttribute(SESSION_ATTRIBUTE, Boolean.TRUE);
    }

    /**
     * Whether the session was created through the Web UI ({@link #mark} ran on it).
     * @param session the session to inspect, may be {@code null}
     * @return {@code true} when the session is live and marked
     */
    public static boolean isMarked(HttpSession session) {
        return session != null && session.getAttribute(SESSION_ATTRIBUTE) != null;
    }

    private static boolean truthy(String value) {
        return value != null && value.equalsIgnoreCase("true");
    }
}
