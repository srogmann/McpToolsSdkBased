package org.rogmann.mcp2sdk.git.forge;

import org.rogmann.mcp2sdk.git.Config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The configured code-hosting server (github.com or an on-premises GitHub Enterprise instance).
 *
 * <p>
 * <b>The server is an operator decision, never a model decision.</b> A caller names
 * {@code owner/repo}; scheme, host, API base and token come from properties only. That is what keeps
 * the module from becoming a proxy for arbitrary URLs (and a prompt injection from reaching an
 * internal host that happens to answer HTTP).
 * </p>
 *
 * <p>
 * <b>One adapter, two operations modes.</b> github.com and GitHub Enterprise speak the same REST
 * API; they differ in the base URL ({@code https://api.github.com} versus
 * {@code https://<host>/api/v3}) and, on older installations, in whether they know the
 * {@code X-GitHub-Api-Version} header - which is why {@code apiVersion} can be emptied. A
 * {@code kind} other than {@code github} (e.g. {@code gitlab}) is rejected with the supported
 * values, not silently treated as GitHub.
 * </p>
 *
 * <p>
 * An invalid value aborts with the property name and the value quoted ({@link Config}); a missing
 * URL is not an error but the normal state of an installation that only wants local git access -
 * then only {@code git.hub.info()} exists and says which property is missing.
 * </p>
 */
public final class ForgeConfig {

    /** Base URL of the server, e.g. {@code https://github.com} or {@code https://git.firma.intern}. */
    public static final String PROP_URL = "mcp2sdk.git.remote.url";

    /** API key; without one, only public repositories and a small request budget are available. */
    public static final String PROP_TOKEN = "mcp2sdk.git.remote.token";

    /** Adapter kind; only {@code github} exists in the first draft. */
    public static final String PROP_KIND = "mcp2sdk.git.remote.kind";

    /** Overrides the derived API base (e.g. {@code https://ghe.firma.intern/api/v3}). */
    public static final String PROP_API_BASE = "mcp2sdk.git.remote.apiBase";

    /** Value of {@code X-GitHub-Api-Version}; empty means "send no such header" (older GHE). */
    public static final String PROP_API_VERSION = "mcp2sdk.git.remote.apiVersion";

    /** Optional allowlist of owners the model may address. */
    public static final String PROP_ORGS = "mcp2sdk.git.remote.orgs";

    /** HTTP budget of one request, in seconds. */
    public static final String PROP_TIMEOUT_SECONDS = "mcp2sdk.git.remote.timeoutSeconds";

    /** Upper bound of items per list. */
    public static final String PROP_MAX_ITEMS = "mcp2sdk.git.remote.maxItems";

    /** Lifetime of a cached GET response, in seconds. */
    public static final String PROP_CACHE_SECONDS = "mcp2sdk.git.remote.cacheSeconds";

    /** The one adapter implemented in the first draft. */
    public static final String KIND_GITHUB = "github";

    /** Default API version, understood by github.com and current GHE installations. */
    public static final String DEFAULT_API_VERSION = "2022-11-28";

    private static final String PUBLIC_HOST = "github.com";

    private ForgeConfig() {
    }

    /**
     * @return true if a server URL is configured, i.e. the server functions are usable
     */
    public static boolean isConfigured() {
        return Config.text(PROP_URL) != null;
    }

    /**
     * @return the configured adapter kind ({@code github})
     * @throws IllegalStateException if another kind is configured
     */
    public static String kind() {
        String kind = Config.text(PROP_KIND, KIND_GITHUB).toLowerCase(Locale.ROOT);
        if (!KIND_GITHUB.equals(kind)) {
            throw Config.invalid(PROP_KIND, kind, "'" + KIND_GITHUB + "'");
        }
        return kind;
    }

    /**
     * @return the server base URL without a trailing slash
     * @throws IllegalStateException if the property is unset or unusable
     */
    public static String serverUrl() {
        String raw = Config.text(PROP_URL);
        if (raw == null) {
            throw new IllegalStateException("No server configured: set the property '" + PROP_URL
                    + "' (e.g. -D" + PROP_URL + "=https://github.com) to browse a code-hosting "
                    + "server. Local git commands work without it.");
        }
        String withoutSlash = raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
        URI uri;
        try {
            uri = new URI(withoutSlash);
        } catch (URISyntaxException e) {
            throw Config.invalid(PROP_URL, raw, "a URL like https://github.com");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !("http".equals(scheme) || "https".equals(scheme))) {
            throw Config.invalid(PROP_URL, raw, "an http:// or https:// URL");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw Config.invalid(PROP_URL, raw, "a URL with a host");
        }
        if (uri.getUserInfo() != null) {
            throw Config.invalid(PROP_URL, raw, "a URL without credentials (use '" + PROP_TOKEN
                    + "' for the API key)");
        }
        if (uri.getQuery() != null) {
            throw Config.invalid(PROP_URL, raw, "a URL without a query");
        }
        if (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath())) {
            throw Config.invalid(PROP_URL, raw, "the server URL only - the API path belongs in '"
                    + PROP_API_BASE + "'");
        }
        return withoutSlash;
    }

    /**
     * @return the API base: {@code https://api.github.com} for github.com,
     *         {@code <server>/api/v3} for an enterprise instance, unless configured explicitly
     */
    public static String apiBase() {
        String configured = Config.text(PROP_API_BASE);
        if (configured != null) {
            String withoutSlash = configured.endsWith("/")
                    ? configured.substring(0, configured.length() - 1) : configured;
            return withoutSlash;
        }
        String server = serverUrl();
        if (PUBLIC_HOST.equalsIgnoreCase(hostOf(server))) {
            return "https://api.github.com";
        }
        return server + "/api/v3";
    }

    /**
     * @return the API key, or {@code null} if none is configured
     */
    public static String token() {
        return Config.text(PROP_TOKEN);
    }

    /**
     * @return true if an API key is configured (never returns the key itself)
     */
    public static boolean tokenPresent() {
        return token() != null;
    }

    /**
     * @return the value of {@code X-GitHub-Api-Version}, or an empty string to send no header
     */
    public static String apiVersion() {
        return Config.text(PROP_API_VERSION, DEFAULT_API_VERSION);
    }

    /**
     * @return the configured owner allowlist (empty = no restriction)
     */
    public static List<String> orgs() {
        return Config.list(PROP_ORGS);
    }

    /** @return HTTP budget of one request, in seconds */
    public static int timeoutSeconds() {
        return Config.intValue(PROP_TIMEOUT_SECONDS, 15, 1, 60);
    }

    /** @return upper bound of items per list */
    public static int maxItems() {
        return Config.intValue(PROP_MAX_ITEMS, 100, 1, 1000);
    }

    /** @return lifetime of a cached GET response, in seconds (0 switches the cache off) */
    public static int cacheSeconds() {
        return Config.intValue(PROP_CACHE_SECONDS, 60, 0, 3600);
    }

    /**
     * Checks the owner allowlist.
     *
     * @param owner the owner as the caller named it
     * @return true if the owner may be addressed
     */
    public static boolean ownerAllowed(String owner) {
        List<String> allowed = orgs();
        if (allowed.isEmpty()) {
            return true;
        }
        for (String entry : allowed) {
            if (entry.equalsIgnoreCase(owner)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The answer of {@code git.hub.info()}: what the module is pointed at, what a caller may use
     * and how a link to this server is built.
     *
     * @return the description; when no server is configured it carries the missing property and a
     *         hint instead of guessing a default
     */
    public static Map<String, Object> info() {
        Map<String, Object> info = new LinkedHashMap<>();
        if (!isConfigured()) {
            info.put("configured", Boolean.FALSE);
            info.put("property", PROP_URL);
            info.put("hint", "Set -D" + PROP_URL + "=<server> (and -D" + PROP_TOKEN
                    + "=<api key>) to browse projects, branches, files and pull requests of a "
                    + "GitHub or GitHub Enterprise server. Without it only the local git commands "
                    + "are available.");
            return info;
        }
        info.put("configured", Boolean.TRUE);
        info.put("kind", kind());
        info.put("serverUrl", serverUrl());
        info.put("apiBase", apiBase());
        info.put("tokenPresent", tokenPresent());
        info.put("orgs", orgs());
        info.put("links", links(serverUrl()));
        info.put("rateLimit", null);
        return info;
    }

    /**
     * The URL conventions of this server, as data - the caller builds a link by substitution
     * instead of guessing a path scheme (GitHub Enterprise uses the same one as github.com, a
     * different product does not).
     *
     * @param server the server base URL
     * @return placeholders to path templates
     */
    public static Map<String, Object> links(String server) {
        Map<String, Object> links = new LinkedHashMap<>();
        links.put("project", server + "/{owner}/{repo}");
        links.put("blob", server + "/{owner}/{repo}/blob/{ref}/{path}");
        links.put("commit", server + "/{owner}/{repo}/commit/{sha}");
        links.put("compare", server + "/{owner}/{repo}/compare/{from}...{to}");
        links.put("pull", server + "/{owner}/{repo}/pull/{number}");
        links.put("user", server + "/{login}");
        return links;
    }

    /** Host of a URL, or the URL itself if it cannot be parsed. */
    private static String hostOf(String url) {
        try {
            String host = new URI(url).getHost();
            return (host == null) ? url : host;
        } catch (URISyntaxException e) {
            return url;
        }
    }
}
