package org.rogmann.mcp2sdk.browser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The actual pass-through: put a request to the application, take the response back.
 *
 * <p>{@link java.net.http.HttpClient} instead of {@code HttpURLConnection}, because the JDK makes
 * trouble with {@code PATCH} and other methods, and because the client delivers multiple header values
 * (>1 {@code Set-Cookie}) cleanly. We do not have to fake the {@code Host}: we connect to the real
 * application directly, so it is correct by itself - and {@link BrowserRewriter} sees to it that the
 * browser never sees anything but us.</p>
 *
 * <p>Two things the user would otherwise experience as "the application does not like me":</p>
 * <ul>
 *   <li><b>{@code Origin}/{@code Referer} up top</b>: the application checks CSRF against the origin
 *       entry and would otherwise see {@code localhost:9091} from a foreign network. We set the values
 *       back to its own base URL.</li>
 *   <li><b>{@code Accept-Encoding: identity}</b>: the JDK does not decode {@code gzip}/{@code br}
 *       itself, and a rewriter that tries to rewrite compressed bytes writes nonsense. For M0 nothing is
 *       compressed - on the user's network that costs practically nothing, and the rewritability is
 *       worth it.</li>
 * </ul>
 */
final class BrowserProxyClient {

    private static final Logger LOG = LoggerFactory.getLogger(BrowserProxyClient.class);

    /** Headers that may not be carried across a redirect (RFC 9110 §7.6.1)
     *  or that the JDK client refuses outright. */
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer",
            "transfer-encoding", "upgrade", "host", "content-length", "expect");

    /**
     * Headers we set - what the browser may send, we decide.
     *
     * <p>{@code Cookie} deliberately does <b>not</b> appear here: in decision L3 the cookie jar belongs
     * to the browser, and it sends us the cookies of its origin with every request. Those have to go up
     * unchanged, otherwise no sign-in is possible. {@link BrowserSecrets} still keeps them out of the
     * <em>capture</em> - forwarding and capturing are two operations.</p>
     */
    private static final Set<String> REPLACED = Set.of(
            "accept-encoding", "origin", "referer", "x-forwarded-for", "x-forwarded-proto",
            "x-forwarded-host", "sec-fetch-dest", "sec-fetch-mode", "sec-fetch-site");

    private static final BrowserProxyClient INSTANCE = new BrowserProxyClient();

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private BrowserProxyClient() {
    }

    /**
     * @return the shared instance (the client is thread-safe, connections are reused)
     */
    static BrowserProxyClient get() {
        return INSTANCE;
    }

    /**
     * Carries out a request to the application.
     *
     * @param method       HTTP method
     * @param target       complete upstream URL
     * @param headers      the headers of the browser
     * @param body         the request body, may be {@code null}
     * @param upstreamBase the base URL of the application (for {@code Origin}/{@code Referer})
     * @param clientIp     address of the browser, for {@code X-Forwarded-For}
     * @return the response
     * @throws IOException          network failure of the application
     * @throws InterruptedException abort while waiting
     */
    UpstreamResponse send(String method, URI target, Map<String, List<String>> headers,
            byte[] body, URI upstreamBase, String clientIp) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(BrowserSessions.get().settings().requestTimeoutSeconds()));
        String verb = (method == null || method.isBlank()) ? "GET" : method.toUpperCase(Locale.ROOT);
        HttpRequest.BodyPublisher publisher = (body == null || body.length == 0)
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body);
        builder.method(verb, publisher);
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            String name = entry.getKey();
            String lower = name.toLowerCase(Locale.ROOT);
            if (HOP_BY_HOP.contains(lower) || REPLACED.contains(lower)) {
                continue;
            }
            // The JDK client throws away tell-tale headers (Content-Length, Host, Expect, Upgrade)
            // with an IllegalArgumentException - that must not make a harmless application unusable,
            // so we skip them quietly.
            for (String value : entry.getValue()) {
                try {
                    builder.header(name, value);
                } catch (IllegalArgumentException e) {
                    LOG.debug("browser: header {} cannot be taken over: {}", name, e.getMessage());
                }
            }
        }
        builder.header("Accept-Encoding", "identity");
        builder.header("X-Forwarded-For", clientIp == null ? "127.0.0.1" : clientIp);
        builder.header("X-Forwarded-Proto", "http");
        builder.header("X-Forwarded-Host", upstreamBase.getAuthority());
        // first(...) instead of List.getFirst(): the latter is Java 21, this module builds on 17.
        String origin = first(headers, "Origin");
        if (origin != null && !origin.isBlank()) {
            builder.header("Origin", baseOf(upstreamBase));
        }
        String referer = first(headers, "Referer");
        if (referer != null && !referer.isBlank()) {
            builder.header("Referer", baseOf(upstreamBase) + "/");
        }
        HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        Map<String, List<String>> out = new LinkedHashMap<>();
        response.headers().map().forEach((key, values) -> {
            if (key != null && !HOP_BY_HOP.contains(key.toLowerCase(Locale.ROOT))) {
                out.put(key, new ArrayList<>(values));
            }
        });
        return new UpstreamResponse(response.statusCode(), out, response.body());
    }

    /**
     * An upstream response, buffered completely (M0 limit, see docs/browser.md §8).
     *
     * @param status  status code
     * @param headers headers without hop-by-hop
     * @param body    the bytes of the body
     */
    record UpstreamResponse(int status, Map<String, List<String>> headers, byte[] body) {

        /**
         * @param name header name (case-insensitive)
         * @return the first value or {@code null}
         */
        String first(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)
                        && !entry.getValue().isEmpty()) {
                    return entry.getValue().get(0);
                }
            }
            return null;
        }
    }

    private static String first(Map<String, List<String>> headers, String name) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)
                    && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return null;
    }

    private static String baseOf(URI upstreamBase) {
        String scheme = upstreamBase.getScheme() == null ? "https" : upstreamBase.getScheme();
        int port = upstreamBase.getPort();
        return scheme + "://" + upstreamBase.getHost() + (port > 0 ? ":" + port : "");
    }
}
