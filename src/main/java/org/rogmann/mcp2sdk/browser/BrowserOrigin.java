package org.rogmann.mcp2sdk.browser;

import java.util.Locale;

/**
 * Which provenance an operated application has - the one decision that separates path and port mode.
 *
 * <p>In <b>path mode</b> the application and our control surfaces share one origin
 * ({@code http://localhost:9091}); the application sits under {@code /browser/<app>/<session>} and
 * everything the page writes root-absolute ({@code /api/x}) has to be rewritten and chased by the
 * script. In <b>port mode</b> the listener owns the <em>whole</em> origin: the application breathes
 * under {@code /}, our endpoints lie behind a reserved prefix, and the rewriting work shrinks to the
 * absolute URLs the application writes to its real host on its own.</p>
 *
 * <p>Port mode is not cosmetics: it is the answer to the question of what should be reachable on an
 * application port. A second, bare Tomcat listener with exactly one servlet has no routes one could
 * forget - on a shared webapp that would need a maintenance list (and every new
 * {@code @RequestMapping} of this application would be a case somebody has to think of).</p>
 */
public interface BrowserOrigin {

    /**
     * Prefix of our endpoints in <b>path mode</b> (normal port, Spring webapp). Deliberately a
     * different value than {@link #PORT_BRIDGE_ROOT}: both modes run in parallel, and a merged prefix
     * does not mean "doesn't matter" but that path mode addresses a channel into nowhere - the bridge
     * never reports in, and the session stays empty for the LLM.
     */
    String PATH_BRIDGE_ROOT = "/browserctl";

    /**
     * Prefix of our endpoints on an application port. Deliberately with a leading dot: practically
     * unoccupied by line-of-business applications, recognizable as ours in every log immediately, and
     * the {@code /.well-known-*} neighbours show that this pattern is common.
     */
    String PORT_BRIDGE_ROOT = "/.browserproxy";

    /**
     * @return the prefix under which this provenance offers our endpoints
     */
    String bridgeRoot();

    /**
     * @return the base path for {@code hello}/{@code poll}/{@code post}/{@code command}
     *         (path mode: including the session section)
     */
    String bridgeBase();

    /**
     * @return the prefix under which the application appears in the browser; {@code ""} in port mode
     */
    String entryPrefix();

    /**
     * The part of the requested URI the application is supposed to answer.
     *
     * @param uri         {@code HttpServletRequest.getRequestURI()}
     * @param contextPath {@code HttpServletRequest.getContextPath()}
     * @return path with a leading {@code /}, without our session prefix
     */
    String remainder(String uri, String contextPath);

    /**
     * Checks whether a URI belongs to us (and is not passed on to the application).
     *
     * @param uri the requested URI
     * @return true for our endpoints
     */
    default boolean isBridge(String uri) {
        return uri != null && uri.toLowerCase(Locale.ROOT).startsWith(bridgeRoot());
    }

    /**
     * Path mode: application under {@code /browser/<app>/<session>}.
     *
     * @param appId     application id
     * @param sessionId session id
     */
    record Path(String appId, String sessionId) implements BrowserOrigin {

        @Override
        public String bridgeRoot() {
            return PATH_BRIDGE_ROOT;
        }

        @Override
        public String bridgeBase() {
            return PATH_BRIDGE_ROOT + "/session/" + sessionId;
        }

        @Override
        public String entryPrefix() {
            return BrowserRewriter.prefixFor(appId, sessionId);
        }

        @Override
        public String remainder(String uri, String contextPath) {
            String path = withoutContext(uri, contextPath);
            return path.startsWith(entryPrefix()) ? path.substring(entryPrefix().length()) : path;
        }
    }

    /**
     * Port mode: the application occupies the root, the session follows from the listener.
     *
     * @param sessionId the session belonging to this port (only of interest for the bridge URL)
     */
    record Port(String sessionId) implements BrowserOrigin {

        @Override
        public String bridgeRoot() {
            return PORT_BRIDGE_ROOT;
        }

        @Override
        public String bridgeBase() {
            return PORT_BRIDGE_ROOT;
        }

        @Override
        public String entryPrefix() {
            return "";
        }

        @Override
        public String remainder(String uri, String contextPath) {
            String path = withoutContext(uri, contextPath);
            // What starts under the bridge prefix is never application content - the caller
            // (BrowserProxyServlet) decides that beforehand, this line is only the safety remainder.
            return path.startsWith(PORT_BRIDGE_ROOT) ? "/" : path;
        }
    }

    /**
     * @param uri         the requested URI
     * @param contextPath the context path of the webapp (normally "")
     * @return the path relative to the context, with a leading {@code /}
     */
    static String withoutContext(String uri, String contextPath) {
        String path = uri == null || uri.isEmpty() ? "/" : uri;
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return path.isEmpty() ? "/" : path;
    }
}
