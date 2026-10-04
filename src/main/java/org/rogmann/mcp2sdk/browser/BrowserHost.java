package org.rogmann.mcp2sdk.browser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * The host name an application is offered under - and the reason a class exists for one string.
 *
 * <p><b>What this is about: cookies ignore ports.</b> RFC 6265 §5.4 compares host and path of a
 * request, never its port, so every proxied application reached under {@code http://localhost:…}
 * shares one cookie bucket however many listeners it has. And the proxy has to pass {@code Cookie}
 * through (decision L3 - without it no sign-in works), which means the bucket is handed to whichever
 * upstream the request belongs to: the session cookie of the internal tool travels to the public page
 * next door, and that page's tracking cookies come back to the internal upstream. Giving an application
 * a port of its own looks like separation and is not one.</p>
 *
 * <p><b>What fixes it: a host name of its own.</b> {@link BrowserProxyCore} removes {@code Domain} from
 * every {@code Set-Cookie}, so a cookie is bound to the host it came from - name the host per
 * application ({@code sample1.localhost:9103}) and the bucket is per application too. The same
 * move ends the quieter neighbour problem: {@code Strict-Transport-Security} passes through unchanged
 * (§8), and one such header used to mark the whole host {@code localhost} as https-only, taking every
 * other glass on the machine down with it.</p>
 *
 * <p><b>What the host name is deliberately not: a routing decision.</b> The upstream comes from the
 * session's registry entry - the path {@code /browser/<appId>/…} in path mode, the bound listener in
 * port mode - and never from the {@code Host} header of the request. Someone knocking at 9103 with
 * {@code Host: victim.localhost} still talks to the application 9103 belongs to and to no other. Were it
 * any other way, the registry would stop being the trust boundary (L2, docs/browser.md §2). A request
 * that arrives under a different name is nevertheless noted in the capture, because two names mean two
 * cookie jars and that reads later like an application that forgets its logins.</p>
 *
 * <p><b>Where the name resolves.</b> {@code *.localhost} is loopback by definition and needs no DNS:
 * Firefox and Chrome resolve it inside the browser, glibc (Linux) and macOS resolve it for every
 * program. Windows does not - there each name wants a line in
 * {@code C:\Windows\System32\drivers\etc\hosts}, which is why the mode is opt-in
 * ({@code browser.host.suffix}) and why {@link #resolverAdvice()} says so instead of sending the user
 * after a name that never answers.</p>
 *
 * <p>The naming itself is a pure function of (app id, suffix) - {@link #host(String, String)} and
 * {@link #url(String, int, String, String)}. The overloads that read the settings are the thin adapter
 * to them, and the settings are read once at startup: a test that wants to see the mode working passes
 * the suffix, it does not fight an initialized singleton.</p>
 */
final class BrowserHost {

    private static final Logger LOG = LoggerFactory.getLogger(BrowserHost.class);

    /** The suffix that makes a per-application name: {@code .localhost}. */
    static final String SUGGESTED_SUFFIX = ".localhost";

    /** Host without any per-application naming - the default, and every old bookmark's address. */
    static final String PLAIN_HOST = "localhost";

    private BrowserHost() {
    }

    // ------------------------------------------------------------------ pure naming

    /**
     * @param appId  registry id (a path segment today, a host label here)
     * @param suffix validated suffix, empty for "mode off"
     * @return the host name this application is offered under
     */
    static String host(String appId, String suffix) {
        if (suffix == null || suffix.isEmpty()) {
            return PLAIN_HOST;
        }
        return label(appId) + suffix;
    }

    /**
     * @param appId  registry id
     * @param port   port the session answers on
     * @param path   path to open, with leading slash
     * @param suffix validated suffix, empty for "mode off"
     * @return a complete address to hand to a browser
     */
    static String url(String appId, int port, String path, String suffix) {
        String tail = path.startsWith("/") ? path : "/" + path;
        return "http://" + host(appId, suffix) + ":" + port + tail;
    }

    /**
     * An application id is a path segment today and a DNS label here - the two alphabets differ, so the
     * id is dressed down instead of being refused: host labels are letters, digits and the hyphen, and
     * {@code sample1_internal} is a perfectly legal registry id.
     *
     * @param appId registry id
     * @return a host label for it
     */
    static String label(String appId) {
        String lower = appId == null ? "" : appId.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lower.length() + 8);
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                out.append(c);
            } else if (c == '-' && !out.isEmpty()) {
                out.append(c);              // a hyphen is legal inside a label, only never as its first
            } else if (!out.isEmpty() && out.charAt(out.length() - 1) != '-') {
                out.append('-');            // ".", "_", anything else: one hyphen, never two in a row
            }
        }
        while (!out.isEmpty() && out.charAt(out.length() - 1) == '-') {
            out.setLength(out.length() - 1);       // and not as its last either (RFC 952, RFC 1123)
        }
        if (out.isEmpty()) {
            return "app";                   // an id of only punctuation still needs a name to answer on
        }
        return out.length() > 63 ? out.substring(0, 63) : out.toString();
    }

    /**
     * A configured suffix has to be a domain suffix, not a wish: leading dot, host characters only.
     *
     * @param raw the value of {@code browser.host.suffix}
     * @return the usable suffix, empty when the value is absent or unusable (said out loud once)
     */
    static String validated(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String suffix = raw.trim().toLowerCase(Locale.ROOT);
        if (!suffix.startsWith(".")) {
            suffix = "." + suffix;
        }
        String body = suffix.substring(1);
        if (body.isEmpty() || !body.matches("[a-z0-9.-]+") || body.startsWith("-") || body.endsWith("-")
                || body.startsWith(".") || body.endsWith(".")) {
            LOG.warn("browser: browser.host.suffix='{}' is not a domain suffix - per-application hosts "
                    + "stay off (suggested value: {})", raw, SUGGESTED_SUFFIX);
            return "";
        }
        return suffix;
    }

    /**
     * @param osName the value of {@code os.name}
     * @return true when {@code *.localhost} does not resolve on its own and wants a hosts entry
     */
    static boolean needsHostsEntry(String osName) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        return os.contains("windows");
    }

    /**
     * @param suffix the validated suffix
     * @param osName the value of {@code os.name}
     * @return what to tell the operator about resolving the names, empty when there is nothing to say
     */
    static String resolverAdvice(String suffix, String osName) {
        if (suffix == null || suffix.isEmpty() || !needsHostsEntry(osName)) {
            return "";
        }
        return "on " + osName + " names under *" + suffix + " do not resolve by themselves - add one "
                + "line per application to C:\\Windows\\System32\\drivers\\etc\\hosts (for example "
                + "\"127.0.0.1 " + label(BrowserApps.DEMO_ID) + suffix + "\") or run the browser "
                + "somewhere that resolves *" + suffix;
    }

    // ------------------------------------------------------------------ reading the settings

    /**
     * @param appId registry id
     * @return the host name this installation offers that application under
     */
    static String of(String appId) {
        return host(appId, BrowserSessions.get().settings().hostSuffix());
    }

    /**
     * @param appId registry id
     * @param port  port the session answers on
     * @param path  path to open
     * @return a complete address, in the naming this installation uses
     */
    static String url(String appId, int port, String path) {
        return url(appId, port, path, BrowserSessions.get().settings().hostSuffix());
    }

    /**
     * @return the advice for this platform and setting, empty when the mode is off or resolves itself
     */
    static String resolverAdvice() {
        return resolverAdvice(BrowserSessions.get().settings().hostSuffix(),
                System.getProperty("os.name", ""));
    }

    /**
     * The address of our own control surfaces (the index page). It stays on {@code localhost}: the page
     * is not an application, shares no cookie with one and is the place where applications are named
     * apart - so it should not take part in that naming.
     *
     * @param port the port the service runs on
     * @return the index address
     */
    static String indexUrl(int port) {
        return "http://" + PLAIN_HOST + ":" + port + "/browser/";
    }
}
