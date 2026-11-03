package org.rogmann.mcp2sdk.git.forge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The one door to the configured server - and it only has a handle that turns.
 *
 * <p>
 * <b>Read-only by type.</b> There is no method that takes an HTTP method: this class knows
 * {@code GET} and nothing else. "Review pull requests but never approve them" is therefore not a
 * rule somebody has to remember; it is the absence of a method.
 * </p>
 *
 * <p>
 * <b>No URL from the caller.</b> A caller names a path below the configured API base
 * ({@code /repos/owner/name}), the base itself is a property. Together with the repository grammar
 * ({@link ForgeRepo}) that keeps the module from becoming an open proxy.
 * </p>
 *
 * <p>
 * <b>The key never leaves.</b> The token goes into the request header and is replaced by
 * {@code <redacted>} in every message and log line this class produces - an error message travels
 * to a model, a log line to a file, and neither is a place for a credential.
 * </p>
 *
 * <p>
 * <b>Cheap answers stay cheap.</b> A {@code GET} is cached for a few seconds with its ETag, so the
 * same question in the same conversation does not spend the rate limit twice; the counters from the
 * response headers are kept for {@code git.hub.info()}.
 * </p>
 */
public final class ForgeHttp {

    private static final Logger LOG = LoggerFactory.getLogger(ForgeHttp.class);

    /** Upper bound of one response body. */
    private static final int MAX_BODY_CHARS = 8 * 1024 * 1024;

    /** Characters of an error body quoted in the message. */
    private static final int ERROR_BODY_CHARS = 300;

    /** Replace marker for the configured key. */
    private static final String REDACTED = "<redacted>";

    private final HttpClient client;
    private final String apiBase;
    private final String token;
    private final String apiVersion;
    private final Duration timeout;
    private final int cacheSeconds;
    private final int maxItems;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final AtomicReference<String> rateLimit = new AtomicReference<>();

    /**
     * One cached response.
     *
     * @param etag      the ETag the server sent
     * @param body      the body
     * @param fetchedAt when it was fetched (epoch millis)
     */
    private record CacheEntry(String etag, String body, long fetchedAt) {
    }

    private ForgeHttp(String apiBase, String token, String apiVersion, Duration timeout,
                      int cacheSeconds, int maxItems) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.apiBase = apiBase;
        this.token = token;
        this.apiVersion = apiVersion;
        this.timeout = timeout;
        this.cacheSeconds = cacheSeconds;
        this.maxItems = maxItems;
    }

    /**
     * Builds the client from the configuration.
     *
     * @return the client
     * @throws IllegalStateException if no server is configured or a property is invalid
     */
    public static ForgeHttp create() {
        requireConfigured();
        return new ForgeHttp(ForgeConfig.apiBase(), ForgeConfig.token(), ForgeConfig.apiVersion(),
                Duration.ofSeconds(ForgeConfig.timeoutSeconds()), ForgeConfig.cacheSeconds(),
                ForgeConfig.maxItems());
    }

    /**
     * @throws IllegalStateException if no server URL is configured (the message names the property)
     */
    public static void requireConfigured() {
        // serverUrl() names the property and the alternative, so it produces the message.
        ForgeConfig.serverUrl();
    }

    /**
     * Performs one {@code GET}.
     *
     * @param path the API path below the base, e.g. {@code /repos/owner/name/branches}
     * @return the response body
     * @throws IOException if the server answers with anything but 200, the request fails or the
     *         body is too large
     */
    public String get(String path) throws IOException {
        return get(path, false);
    }

    /**
     * Performs one {@code GET} for which "there is nothing there" is an answer, not a failure.
     *
     * <p>
     * A comparison of a remote revision with a local file asks "does this file exist there?" - and
     * the server's answer to that is 404. Turning it into an exception would force every caller to
     * match on message text, so the 404 is returned as {@code null} here and only here. Every other
     * status still fails.
     * </p>
     *
     * @param path the API path below the base
     * @return the response body, or {@code null} when the server answered 404
     * @throws IOException if the server answers with anything but 200 or 404, the request fails or
     *         the body is too large
     */
    public String getOrNull(String path) throws IOException {
        return get(path, true);
    }

    private String get(String path, boolean nullOnNotFound) throws IOException {
        String key = path;
        CacheEntry cached = cache.get(key);
        long now = System.currentTimeMillis();
        boolean usable = cached != null && cacheSeconds > 0
                && (now - cached.fetchedAt()) < cacheSeconds * 1000L;

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(apiBase + path))
                .GET()
                .timeout(timeout)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "mcp2sdk-git-module");
        if (apiVersion != null && !apiVersion.isBlank()) {
            builder.header("X-GitHub-Api-Version", apiVersion);
        }
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (usable && cached.etag() != null) {
            builder.header("If-None-Match", cached.etag());
        }

        HttpResponse<String> response;
        try {
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(
                    StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while asking the server for '" + path + "'.", e);
        } catch (IOException e) {
            throw new IOException("Cannot reach the configured server (" + redact(e.getMessage())
                    + "). Check '" + ForgeConfig.PROP_URL + "' and the network.", e);
        }
        rememberRateLimit(response);

        int status = response.statusCode();
        if (status == 304 && cached != null) {
            LOG.debug("Server answered 304 for {}, using the cached body", path);
            return cached.body();
        }
        String body = response.body();
        if (status != 200) {
            if (nullOnNotFound && status == 404) {
                LOG.debug("Server answered 404 for {} - reported as 'not there'", path);
                return null;
            }
            throw new IOException(failureMessage(status, path, body, response));
        }
        if (body != null && body.length() > MAX_BODY_CHARS) {
            throw new IOException("The answer for '" + path + "' is larger than "
                    + MAX_BODY_CHARS + " characters; ask for a smaller range.");
        }
        String etag = header(response, "ETag").orElse(null);
        if (cacheSeconds > 0 && etag != null) {
            if (cache.size() >= Math.max(16, maxItems * 4)) {
                cache.clear();
            }
            cache.put(key, new CacheEntry(etag, body, now));
        }
        return body;
    }

    /**
     * @return the last seen rate-limit state, or {@code null} if the server never sent one
     */
    public String rateLimitLine() {
        return rateLimit.get();
    }

    /**
     * Removes the configured key from a text.
     *
     * @param text the text, may be {@code null}
     * @return the text with the key replaced
     */
    public String redact(String text) {
        if (text == null) {
            return null;
        }
        return (token == null) ? text : text.replace(token, REDACTED);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private static Optional<String> header(HttpResponse<String> response, String name) {
        return response.headers().firstValue(name);
    }

    /** Keeps the counters of the last answer for {@code git.hub.info()}. */
    private void rememberRateLimit(HttpResponse<String> response) {
        Optional<String> remaining = header(response, "X-RateLimit-Remaining");
        Optional<String> limit = header(response, "X-RateLimit-Limit");
        Optional<String> reset = header(response, "X-RateLimit-Reset");
        if (remaining.isEmpty() && limit.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(remaining.orElse("?")).append('/').append(limit.orElse("?"));
        reset.ifPresent(value -> {
            try {
                sb.append(", reset ").append(Instant.ofEpochSecond(Long.parseLong(value.strip())));
            } catch (NumberFormatException e) {
                sb.append(", reset ").append(value);
            }
        });
        rateLimit.set(sb.toString());
    }

    /** Builds an actionable error message for a non-200 answer. */
    private String failureMessage(int status, String path, String body,
                                  HttpResponse<String> response) {
        return switch (status) {
            case 401 -> "The server rejected the API key (401). Check the property '"
                    + ForgeConfig.PROP_TOKEN + "'.";
            case 403 -> {
                if ("0".equals(header(response, "X-RateLimit-Remaining").orElse(""))) {
                    yield "The request budget of the server is exhausted (403) until "
                            + header(response, "X-RateLimit-Reset").orElse("later")
                            + "; wait, or use an API key with a larger budget ('"
                            + ForgeConfig.PROP_TOKEN + "').";
                }
                yield "The server refused the request (403) for '" + path + "': the key may lack "
                        + "the permission, or the owner allowlist ('" + ForgeConfig.PROP_ORGS
                        + "') does not cover it.";
            }
            case 404 -> "Not found (404) on the configured server: '" + path + "'. Check "
                    + "owner/repo, the revision ('ref') and the path.";
            default -> "The server answered HTTP " + status + " for '" + path + "': "
                    + abbreviate(redact(body));
        };
    }

    private static String abbreviate(String body) {
        if (body == null || body.isBlank()) {
            return "(no body)";
        }
        String text = body.strip().replace('\n', ' ');
        return (text.length() <= ERROR_BODY_CHARS) ? text
                : text.substring(0, ERROR_BODY_CHARS) + "...";
    }
}
