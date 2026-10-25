package org.rogmann.mcp2sdk.browser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * An application earmarked for proxying - the entry in {@code browser-apps.json}.
 *
 * <p>The registry is the trust boundary: <em>only</em> what stands here may the proxy reach. "Internal
 * and trustworthy" is thereby a property of the list and not of the URL being called, and the proxy
 * does not become an open doorman into the company network (SSRF).</p>
 *
 * @param id                short name, appears in the path {@code /browser/<id>/<session>/...}
 * @param upstream          base URL ({@code https://sample1.internal/}) or {@code self:/path} for an
 *                          application embedded on the same server (self-test)
 * @param port              own listener port ({@code 9100}..{@code 9199}) or {@code 0} for path mode on
 *                          the normal port. A port means: the application occupies the root of an
 *                          origin of its own, our control surfaces ({@code /mcp}, {@code /review},
 *                          {@code /chat}) are not reachable there - and the rewriting work of the
 *                          bridge shrinks (docs/browser.md §2).
 * @param note              what the application is; shown for humans in the list and in the tool
 * @param auth              sign-in kind: {@code form}, {@code saml}, {@code oidc}, {@code none},
 *                          {@code negotiate}. {@code negotiate} is <em>not</em> operable by this route
 *                          (Firefox only sends the ticket to approved hosts, which would be an
 *                          {@code about:config} setting) - the value documents the case.
 * @param sensitivePaths    paths whose request bodies are never logged (login, token endpoint)
 * @param sensitiveKeys     field names considered secret, in addition to the predefined ones
 * @param irreversiblePaths paths whose requests only pass in write mode with a hard approval
 * @param rewriteRules      response rewrite rules (docs/browser.md §3a) - today only
 *                          {@code queryParamUrl}. A rule instance is data (proposable from the
 *                          capture), its type is reviewed code; unknown types are dropped with a
 *                          registry complaint, the entry stays valid.
 */
public record BrowserApp(String id, String upstream, int port, String note, String auth,
        List<String> sensitivePaths, List<String> sensitiveKeys, List<String> irreversiblePaths,
        List<BrowserRewriteRule> rewriteRules) {

    private static final Logger LOG = LoggerFactory.getLogger(BrowserApp.class);

    /** Applications whose unreadable host has already been reported (the warning should stay one-off). */
    private static final Set<String> WARNED_HOSTS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Allowed pattern of an application id (path segment, therefore strict). */
    private static final String ID_PATTERN = "[a-z0-9][a-z0-9._-]{0,40}";

    /**
     * Checks the entry and normalizes the base URL to exactly one trailing slash.
     *
     * @throws IllegalArgumentException on an unusable id or target address
     */
    public BrowserApp {
        if (id == null || !id.matches(ID_PATTERN)) {
            throw new IllegalArgumentException("application id has to match " + ID_PATTERN
                    + " (lowercase, no slash): " + id);
        }
        if (upstream == null || upstream.isBlank()) {
            throw new IllegalArgumentException("application '" + id + "' has no upstream target.");
        }
        String normalized = upstream.trim();
        if (normalized.startsWith("self:")) {
            String path = normalized.substring("self:".length());
            if (!path.startsWith("/")) {
                throw new IllegalArgumentException("a self: target has to begin with '/': " + normalized);
            }
        } else {
            URI uri;
            try {
                uri = new URI(normalized);
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("the upstream of '" + id + "' is not a URL: "
                        + normalized, e);
            }
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null) {
                throw new IllegalArgumentException("the upstream of '" + id + "' has to be http(s) with"
                        + " a host (or self:/path): " + normalized);
            }
        }
        while (normalized.endsWith("//")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (!normalized.endsWith("/")) {
            normalized = normalized + "/";
        }
        upstream = normalized;
        if (port < 0 || (port > 0 && port < 1024)) {
            throw new IllegalArgumentException("the port of '" + id + "' has to be 0 (path mode on the"
                    + " normal port) or a service port from 1024, was " + port);
        }
        note = note == null ? "" : note.trim();
        auth = (auth == null || auth.isBlank()) ? "unknown" : auth.trim().toLowerCase(Locale.ROOT);
        sensitivePaths = List.copyOf(sensitivePaths == null ? List.of() : sensitivePaths);
        sensitiveKeys = List.copyOf(sensitiveKeys == null ? List.of() : sensitiveKeys);
        irreversiblePaths = List.copyOf(irreversiblePaths == null ? List.of() : irreversiblePaths);
        rewriteRules = List.copyOf(rewriteRules == null ? List.of() : rewriteRules);
    }

    /**
     * Convenience for callers that do not deal with rewrite rules (most entries, tests).
     */
    public BrowserApp(String id, String upstream, int port, String note, String auth,
            List<String> sensitivePaths, List<String> sensitiveKeys, List<String> irreversiblePaths) {
        this(id, upstream, port, note, auth, sensitivePaths, sensitiveKeys, irreversiblePaths, List.of());
    }

    /**
     * @return true if the target is an application built into the same server (self-test)
     */
    public boolean isSelf() {
        return upstream.startsWith("self:");
    }

    /**
     * @return on {@link #isSelf()} the local path, otherwise empty
     */
    public String selfPath() {
        return isSelf() ? upstream.substring("self:".length()) : "";
    }

    /**
     * Host name of the upstream target in lowercase; empty on {@link #isSelf()}.
     *
     * <p>The {@code catch} is here because {@link #upstream} was checked at construction while the host
     * is read from many places (also per response) and an exception would make the pass-through
     * unusable. Staying silent is not allowed either: a missing host means that the application's
     * absolute URLs are <em>not</em> rewritten and the capture gets holes - so warn visibly at least
     * once, afterwards only on DEBUG, so that one broken entry does not log every request.</p>
     *
     * @return only the host (without port), for assigning incoming absolute URLs
     */
    public String host() {
        if (isSelf()) {
            return "";
        }
        try {
            String host = new URI(upstream).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (URISyntaxException e) {
            if (WARNED_HOSTS.add(id)) {
                LOG.warn("browser: application '{}' has no readable upstream URL '{}' despite the check "
                        + "- absolute URLs of this application cannot be rewritten: {}",
                        id, upstream, e.getMessage());
            } else {
                LOG.debug("browser: host of '{}' not readable again: {}", id, e.getMessage());
            }
            return "";
        }
    }

    /**
     * Checks whether an upstream path belongs to the secret paths of this application.
     *
     * @param path path without query (e.g. {@code /sso/login})
     * @return true if request body and response body may not be logged
     */
    public boolean isSensitivePath(String path) {
        return matches(sensitivePaths, path);
    }

    /**
     * Checks whether an upstream path has an effect that cannot be taken back.
     *
     * @param path path without query
     * @return true for {@code irreversiblePaths}
     */
    public boolean isIrreversiblePath(String path) {
        return matches(irreversiblePaths, path);
    }

    private static boolean matches(List<String> prefixes, String path) {
        if (path == null || prefixes.isEmpty()) {
            return false;
        }
        String needle = path.toLowerCase(Locale.ROOT);
        for (String prefix : prefixes) {
            if (!prefix.isBlank() && needle.contains(prefix.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
