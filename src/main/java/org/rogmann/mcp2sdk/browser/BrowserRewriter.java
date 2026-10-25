package org.rogmann.mcp2sdk.browser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Rewrites URLs so that the browser renders unchanged while sending everything through us.
 *
 * <p><b>Why scanning by hand instead of an HTML parser library</b>: a parser reconstructs the document
 * and, when serializing it back, changes things a line-of-business application very much does read
 * (attribute case, attributes without a value, CDATA, conditional comments, whitespace in
 * {@code <pre>}). A proxy may not do that: it may only change what it <em>has</em> to change. So the
 * text is scanned tag by tag, and every region that is not rewritten goes into the output byte for
 * byte.</p>
 *
 * <p>In path mode (decision L1) the session prefix {@code /browser/<app>/<session>} is the new
 * "root". Four things have to come along for that to hold:</p>
 * <ol>
 *   <li><b>attributes</b> ({@code href}, {@code src}, {@code action}, {@code srcset}, inline
 *       {@code style}, {@code <meta http-equiv=refresh>}) - including the <em>root-absolute</em> ones
 *       ({@code /api/x}), which without rewriting would bypass the session prefix and go straight to
 *       our origin.</li>
 *   <li><b>{@code <base href> }</b>: replaced or set, so that nodes created <em>later</em> by a script
 *       ( {@code img.src = "x.png"} ) resolve correctly.</li>
 *   <li><b>absolute URLs to the real host</b> in the text of HTML, CSS, JS and JSON - otherwise the
 *       browser loads past us and the capture gets holes (made visible by the bridge's leak
 *       detection).</li>
 *   <li><b>the bridge</b> itself, as the first script in the {@code <head>}: {@code fetch}/{@code XHR}/
 *       {@code history}/{@code WebSocket} can only be patched <em>before</em> the application code
 *       runs.</li>
 * </ol>
 */
public final class BrowserRewriter {

    private static final Logger LOG = LoggerFactory.getLogger(BrowserRewriter.class);

    /** Attributes whose value is a URL. */
    private static final Set<String> URL_ATTRIBUTES = Set.of(
            "href", "src", "action", "formaction", "poster", "data", "background", "cite",
            "manifest", "xlink:href", "data-src", "data-href", "data-original", "data-action",
            "data-bg", "srcset");

    /** Schemes that are never rewritten. */
    private static final List<String> PASS_THROUGH_SCHEMES = List.of(
            "javascript:", "mailto:", "tel:", "sms:", "data:", "blob:", "about:", "file:",
            "chrome-extension:", "moz-extension:", "ftp:");

    /**
     * Version of the bridge; also stands in {@code bridge.js} and appears in the {@code hello}.
     *
     * <p>It is the cache key of the script URL, so it moves whenever the script changes something the
     * <em>server</em> relies on: a stale cached bridge must not be able to talk as if it were current.
     * v4 is the channel parameter {@code ?s=<session>} (§2b) - an old script would silently keep sending
     * requests without it and be counted as "nameless" instead of being checked.</p>
     */
    public static final int BRIDGE_VERSION = 4;

    private final BrowserApp app;
    private final BrowserApps registry;
    private final String sessionId;
    private final BrowserOrigin origin;
    private final String prefix;
    private final boolean relaxCsp;
    private final boolean injectBridge;
    private final String cspNonce;
    /**
     * The origin the <b>browser</b> used to reach the proxy ({@code scheme://authority}), or {@code null}
     * without a rewrite-rule context. The pair with {@link #upstreamAuthority} is what a
     * {@code queryParamUrl} rule swaps (docs/browser.md §3a) - both addresses a return URL can name.
     */
    private final String proxyOrigin;
    /** The authority the proxy addresses the application at - what {@code X-Forwarded-Host} carries. */
    private final String upstreamAuthority;

    /**
     * @param app          the application being called
     * @param registry     the complete registry (for absolute URLs to other registered hosts)
     * @param sessionId    id of the browser session
     * @param origin       provenance: path mode (prefix in the URL) or port mode (application on
     *                     {@code /})
     * @param relaxCsp     when true, a {@code script-src} that would otherwise block the bridge is removed
     * @param injectBridge inject the bridge at all (off switches the LLM access away)
     * @param cspNonce     a usable nonce from this response's CSP, otherwise {@code null}
     */
    public BrowserRewriter(BrowserApp app, BrowserApps registry, String sessionId,
            BrowserOrigin origin, boolean relaxCsp, boolean injectBridge, String cspNonce) {
        this(app, registry, sessionId, origin, relaxCsp, injectBridge, cspNonce, null, null);
    }

    /**
     * Full constructor with the rewrite-rule context (docs/browser.md §3a): the origin the
     * <b>browser</b> used and the authority the proxy addresses the application at. Without the context
     * (tests, callers before the pass-through ran) the rules of the registry stay inert.
     *
     * @param proxyOrigin        the browser's origin for this session, e.g.
     *                           {@code http://browserdemo.localhost:9101}
     * @param upstreamAuthority  the authority of the upstream base, e.g. {@code 127.0.0.1:9091}
     */
    public BrowserRewriter(BrowserApp app, BrowserApps registry, String sessionId,
            BrowserOrigin origin, boolean relaxCsp, boolean injectBridge, String cspNonce,
            String proxyOrigin, String upstreamAuthority) {
        this.app = app;
        this.registry = registry;
        this.sessionId = sessionId;
        this.origin = origin;
        this.prefix = origin.entryPrefix();
        this.relaxCsp = relaxCsp;
        this.injectBridge = injectBridge;
        this.cspNonce = cspNonce;
        this.proxyOrigin = proxyOrigin == null || proxyOrigin.isBlank() ? null
                : (proxyOrigin.endsWith("/") ? proxyOrigin.substring(0, proxyOrigin.length() - 1)
                        : proxyOrigin);
        this.upstreamAuthority = upstreamAuthority == null || upstreamAuthority.isBlank()
                ? null : upstreamAuthority;
    }

    /**
     * Convenient entry point for path mode (tests and callers with no port-mode ambition).
     *
     * @param app          the application being called
     * @param registry     the registry
     * @param sessionId    session id
     * @param relaxCsp     relax the CSP when needed
     * @param injectBridge inject the bridge
     * @param cspNonce     nonce from the CSP or {@code null}
     */
    public BrowserRewriter(BrowserApp app, BrowserApps registry, String sessionId,
            boolean relaxCsp, boolean injectBridge, String cspNonce) {
        this(app, registry, sessionId, new BrowserOrigin.Path(app.id(), sessionId),
                relaxCsp, injectBridge, cspNonce);
    }

    /**
     * The prefix under which an application is reachable in this session.
     *
     * @param appId     application id
     * @param sessionId session id
     * @return path without a trailing slash
     */
    public static String prefixFor(String appId, String sessionId) {
        return "/browser/" + appId + "/" + sessionId;
    }

    /**
     * @return the prefix of this session for the application being called
     */
    public String prefix() {
        return prefix;
    }

    /**
     * Rewrites an HTML text and injects the bridge.
     *
     * @param body     the response text (characters, already decoded)
     * @param pagePath the upstream path of the response (e.g. {@code /auftrag/list.html}), for
     *                 relative resolution without a {@code <base>}
     * @return rewritten HTML
     */
    public String html(String body, String pagePath) {
        StringBuilder out = new StringBuilder(body.length() + 512);
        String injected = injectBridge ? bootstrap(pagePath, cspNonce) : "";
        boolean headSeen = false;
        int i = 0;
        int length = body.length();
        while (i < length) {
            char c = body.charAt(i);
            if (c != '<') {
                out.append(c);
                i++;
                continue;
            }
            if (regionEqualsIgnoreCase(body, i, "<!--")) {
                int end = body.indexOf("-->", i + 4);
                end = end < 0 ? length : end + 3;
                out.append(body, i, end);
                i = end;
                continue;
            }
            int tagEnd = findTagEnd(body, i);
            if (tagEnd < 0) {
                out.append(body, i, length);
                break;
            }
            String tag = body.substring(i, tagEnd + 1);
            String name = tagName(tag);
            if (name.equals("script") || name.equals("style")) {
                int close = findCloseTag(body, i, name);
                if (close >= length) {
                    out.append(rewriteTag(body.substring(i, tagEnd + 1), pagePath));
                    out.append(absoluteUrls(body.substring(tagEnd + 1)));
                    break;
                }
                int contentEnd = tag.endsWith("/") ? -1 : body.indexOf('>', i);
                if (contentEnd < 0 || contentEnd > close) {
                    out.append(rewriteTag(tag, pagePath));
                    i = tagEnd + 1;
                    continue;
                }
                String open = body.substring(i, contentEnd + 1);
                String content = body.substring(contentEnd + 1, close);
                String closing = body.substring(close, findTagEnd(body, close) + 1);
                out.append(rewriteTag(open, pagePath));
                out.append(name.equals("script") ? absoluteUrls(content) : cssUrls(content, pagePath));
                out.append(closing);
                i = close + closing.length();
                continue;
            }
            String rewritten = rewriteTag(tag, pagePath);
            out.append(rewritten);
            if (!headSeen && !injected.isEmpty() && name.equals("head")) {
                out.append(injected);
                headSeen = true;
            }
            i = tagEnd + 1;
        }
        if (!headSeen && !injected.isEmpty()) {
            // No <head> (framesets, broken or partially delivered pages): put it in front. The
            // order does not matter as long as the bridge runs before the application code.
            out.insert(0, injected);
        }
        return out.toString();
    }

    /**
     * Rewrites CSS {@code url()} entries and absolute URLs (separately for external {@code .css}).
     *
     * @param body     CSS text
     * @param pagePath path of the CSS file itself
     * @return rewritten CSS
     */
    public String css(String body, String pagePath) {
        return absoluteUrls(cssUrls(body, pagePath));
    }

    /**
     * For JS and JSON: only rewrite absolute URLs to registered hosts. Paths a script builds by itself
     * ({@code "/api/x"}) are something no text substitution can handle - the bridge's
     * {@code fetch}/{@code XHR} patch takes that over.
     *
     * @param body text body
     * @return rewritten text
     */
    public String textish(String body) {
        return absoluteUrls(body);
    }

    /**
     * Rewrites the {@code Location} value of a redirect.
     *
     * @param value    the value we received
     * @param pagePath starting path of the request (for relative entries)
     * @return the value the browser should see
     */
    public String location(String value, String pagePath) {
        return toProxy(value, pagePath);
    }

    /**
     * A single URL value - also from {@code Location}, {@code <meta refresh>} or an attribute.
     *
     * <p>Two layers, deliberately separate: first the <b>rewrite rules of the registry</b>
     * (docs/browser.md §3a - they may change the URL, e.g. the return URL inside the query of a hop),
     * then the <b>standard mapping</b>, which decides about bending into the session as always. A rule
     * does not switch the provenance logic off - it feeds it: the result of a rule is mapped exactly
     * like the original would have been.</p>
     *
     * @param raw      the original value
     * @param pagePath the upstream path of the document holding the entry
     * @return the rewritten value, or the (possibly rule-changed) input when we keep our hands off it
     */
    public String toProxy(String raw, String pagePath) {
        if (raw == null) {
            return null;
        }
        return mapProxy(applyRules(raw, pagePath), pagePath);
    }

    /**
     * The standard mapping - the body this class shipped before the rewrite rules existed. "Keep our
     * hands off" here means: hands off <em>apart from the rules</em>, which have already run and whose
     * result arrives as {@code raw}.
     *
     * @param raw      the value after the rules
     * @param pagePath the upstream path of the document holding the entry
     * @return the mapped value, or the input when unmapped
     */
    private String mapProxy(String raw, String pagePath) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        String value = raw.trim();
        String lower = value.toLowerCase(Locale.ROOT);
        for (String scheme : PASS_THROUGH_SCHEMES) {
            if (lower.startsWith(scheme)) {
                return raw;
            }
        }
        if (value.startsWith("#")) {
            return raw;
        }
        if (value.startsWith("?")) {
            // "?a=1" means the current page with a new query - and that already lies on our path.
            String currentPage = pagePath == null || pagePath.isEmpty() ? "/" : pagePath;
            int cutPage = indexOfFirst(currentPage, '?', '#');
            return prefix + (cutPage < 0 ? currentPage : currentPage.substring(0, cutPage)) + value;
        }
        String pathPart = value;
        String suffix = "";
        int cut = indexOfFirst(value, '?', '#');
        if (cut > 0) {
            pathPart = value.substring(0, cut);
            suffix = value.substring(cut);
        }
        if (value.startsWith("//")) {
            String host = hostOf(pathPart.substring(2));
            String mapped = prefixForHost(host);
            if (mapped == null) {
                return raw;
            }
            return mapped + pathPart.substring(2 + host.length()) + suffix;
        }
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            int schemeEnd = lower.indexOf("://") + 3;
            String rest = pathPart.substring(schemeEnd);
            String host = hostOf(rest);
            String mapped = prefixForHost(host);
            if (mapped == null) {
                return raw;
            }
            String path = rest.substring(host.length());
            if (path.isEmpty()) {
                path = "/";
            }
            return mapped + (path.startsWith("/") ? path : "/" + path) + suffix;
        }
        if (pathPart.startsWith("/")) {
            if (!prefix.isEmpty() && (pathPart.equals(prefix) || pathPart.startsWith(prefix + "/"))) {
                return raw;                     // already on the right path
            }
            if (pathPart.startsWith(origin.bridgeRoot() + "/") || pathPart.equals(origin.bridgeRoot())) {
                return raw;                     // our own endpoints, never the application's path
            }
            return prefix + pathPart + suffix;
        }
        return prefix + joinPath(pagePath, pathPart) + suffix;
    }

    /**
     * The prefix for a host: ours, that of another registered application (SSO hops), or {@code null}
     * when we do not know the host.
     *
     * @param hostWithPort host entry without scheme, may contain a port
     * @return the redirection prefix or {@code null}
     */
    public String prefixForHost(String hostWithPort) {
        if (hostWithPort == null || hostWithPort.isBlank()) {
            return null;
        }
        String host = hostWithPort.toLowerCase(Locale.ROOT);
        int colon = host.indexOf(':');
        if (colon > 0) {
            host = host.substring(0, colon);
        }
        if (host.equals(app.host())) {
            return prefix;
        }
        if (prefix.isEmpty()) {
            // Port mode: another registered application lives on a port of its own, not on another
            // path of this origin. A hop to it cannot be mapped by setting a prefix - for that the
            // registry would have to know the target port and the browser would have to be redirected
            // to a new origin. Until then: run applications with IdP hops to a second host in path
            // mode (docs/browser.md §8).
            LOG.debug("browser: hop to foreign host {} not bent in port mode", host);
            return null;
        }
        return registry.byHost(host).map(other -> prefixFor(other.id(), sessionId)).orElse(null);
    }

    // ------------------------------------------------------------------ rewrite rules (docs/browser.md §3a)

    /**
     * The per-application rewrite rules of the registry, before the standard mapping. Without a context
     * (proxy origin, upstream authority - both come from the pass-through; tests can work without) the
     * rules stay inert - a rule that silently did nothing would be the audit's problem, which is why
     * the index page and {@code action=apps} name the active rules.
     */
    private String applyRules(String url, String pagePath) {
        if (url == null || app.rewriteRules().isEmpty() || proxyOrigin == null) {
            return url;
        }
        String result = url;
        for (BrowserRewriteRule rule : app.rewriteRules()) {
            result = applyRule(rule, result, pagePath);
        }
        return result;
    }

    /**
     * One rule of type {@code queryParamUrl}: walks the query of the URL and replaces the value of the
     * named parameter when it decodes to an absolute http(s) URL that names the expected authority.
     * Everything else of the URL - the other parameters, their order, their encoding - stays byte for
     * byte: a proxy may only change what it has to change.
     */
    private String applyRule(BrowserRewriteRule rule, String url, String pagePath) {
        if (!rule.appliesToPage(pagePath)) {
            return url;
        }
        int queryAt = url.indexOf('?');
        if (queryAt < 0) {
            return url;
        }
        String from = resolveFrom(rule.from());
        String[] pairs = url.substring(queryAt + 1).split("&", -1);
        StringBuilder out = new StringBuilder(url.substring(0, queryAt + 1));
        boolean changed = false;
        for (int i = 0; i < pairs.length; i++) {
            if (i > 0) {
                out.append('&');
            }
            String pair = pairs[i];
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            String rewritten = name.equals(rule.param())
                    ? rewriteParamValue(eq < 0 ? "" : pair.substring(eq + 1), from, rule.to())
                    : null;
            if (rewritten == null) {
                out.append(pair);
                continue;
            }
            out.append(name).append('=').append(rewritten);
            changed = true;
        }
        return changed ? out.toString() : url;
    }

    /**
     * The value of the return-URL parameter: decoded, checked, retargeted, encoded again - or
     * {@code null} when the rule does not apply to this value (not absolute, not the expected
     * authority, no usable target).
     */
    private String rewriteParamValue(String rawValue, String from, String to) {
        if (from == null || from.isBlank()) {
            return null;
        }
        String value;
        try {
            value = URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;                        // malformed escape: not ours to fix
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return null;                        // the rule speaks only of absolute URLs
        }
        int schemeEnd = lower.indexOf("://") + 3;
        String rest = value.substring(schemeEnd);
        String authority = hostOf(rest);        // up to the first '/', '?' or '#'
        if (!authority.equalsIgnoreCase(from)) {
            return null;
        }
        int cut = indexOfFirst(rest, '/', '?', '#');
        String tail = cut < 0 ? "" : rest.substring(cut);   // path + query + fragment, '/'-led
        String target = resolveTo(to);
        if (target == null) {
            return null;
        }
        String path = tail.isEmpty() || tail.startsWith("?") || tail.startsWith("#")
                ? "/" + tail : tail;            // "host?x" and "host#f" mean the root path
        return URLEncoder.encode(target + path, StandardCharsets.UTF_8);
    }

    /**
     * {@code "upstream"} stands for the authority the proxy addresses the application at - exactly the
     * one {@code X-Forwarded-Host} carries, and therefore the one the application's return URL names.
     */
    private String resolveFrom(String from) {
        if (BrowserRewriteRule.FROM_UPSTREAM.equalsIgnoreCase(from)) {
            return upstreamAuthority;
        }
        return from;
    }

    /**
     * {@code "session"} stands for the origin the browser used to reach us - in port mode the
     * listener's origin, in path mode additionally bent into the session prefix, exactly like a
     * self-reference of the application. A literal value is taken as it stands, one trailing slash off.
     */
    private String resolveTo(String to) {
        if (BrowserRewriteRule.TO_SESSION.equalsIgnoreCase(to)) {
            return proxyOrigin + prefix;
        }
        return to.endsWith("/") ? to.substring(0, to.length() - 1) : to;
    }

    /**
     * Replaces every occurrence of {@code scheme://host} of registered applications in the text.
     *
     * @param body text (HTML fragment, JS, JSON)
     * @return text with the host entries proxied
     */
    public String absoluteUrls(String body) {
        String result = body;
        java.util.LinkedHashSet<BrowserApp> known = new java.util.LinkedHashSet<>();
        known.add(app);                 // our own application always counts, even without a registry hit
        known.addAll(registry.all());
        for (BrowserApp target : known) {
            String host = target.host();
            if (host.isEmpty()) {
                continue;
            }
            String replacement = prefixForHost(host);
            if (replacement == null) {
                continue;                       // port mode: foreign hosts we do not bend
            }
            for (String scheme : new String[] {"https://", "http://"}) {
                String needle = scheme + host;
                if (result.contains(needle)) {
                    result = result.replace(needle, replacement);
                }
            }
            String bare = "//" + host;
            if (result.contains(bare)) {
                result = result.replace(bare, replacement);
            }
        }
        return result;
    }

    /**
     * The bootstrap block: directory {@code <base>} plus bridge script, as the <em>first</em> thing in
     * the {@code <head>}.
     *
     * <p>Deliberately <b>no inline script</b>: a {@code <script>window.__MCPB=...</script>} would be
     * blocked by every {@code script-src 'self'} and would force a weakening of the page that is not
     * needed at all. The bridge fetches its configuration from
     * {@code /browserctl/session/<id>/hello} by itself; the script {@code src} only holds the session
     * id and the bridge version. An external script URL from our own origin satisfies
     * {@code script-src 'self'} - and where the policy only knows nonces, the existing nonce is reused
     * (weakest intervention; {@link #rewriteCsp} is the last resort).</p>
     *
     * <p>The {@code <base>} points at the <em>directory of the page</em>, not at the session prefix:
     * only then does the page resolve relative URLs created later by script
     * ({@code img.src = "logo.png"} on {@code /auftrag/list.html}) exactly as it would without us.</p>
     *
     * @param pagePath upstream path of the document
     * @param nonce    a nonce taken over from the CSP, or {@code null}
     * @return the text to inject
     */
    public String bootstrap(String pagePath, String nonce) {
        String base = (prefix + directoryOf(pagePath)).replaceFirst("^/+", "/");
        String nonceAttribute = (nonce == null || nonce.isBlank()) ? "" : " nonce=\"" + nonce + "\"";
        // The prefix stands in the script URL and not in an inline script: the bridge has to attach
        // its fetch/XHR patch synchronously while parsing, otherwise the application's first requests
        // still run unpatched past the session prefix. "a" is the base of its channel endpoints,
        // "b" the application prefix ("" in port mode) - both things it needs before the first hello.
        return "<base href=\"" + base + "\">"
                + "<script src=\"" + origin.bridgeRoot() + "/__bridge.js?v=" + BRIDGE_VERSION
                + "&s=" + sessionId + "&a=" + origin.bridgeBase() + "&b=" + prefix + "\""
                + nonceAttribute + "></script>";
    }

    /**
     * Directory part of an upstream path, with a trailing slash.
     *
     * @param pagePath e.g. {@code /auftrag/list.html}
     * @return e.g. {@code /auftrag/}
     */
    static String directoryOf(String pagePath) {
        if (pagePath == null || pagePath.isEmpty()) {
            return "/";
        }
        int query = indexOfFirst(pagePath, '?', '#');
        String path = query < 0 ? pagePath : pagePath.substring(0, query);
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "/" : path.substring(0, slash + 1);
    }

    /**
     * Adapts a {@code Content-Security-Policy} to the injected bridge.
     *
     * @param csp   the policy we received (header or {@code <meta>})
     * @param nonce a usable {@code nonce-...} from the policy, otherwise {@code null}
     * @return the policy to send, or {@code null} when we drop it entirely
     */
    public String rewriteCsp(String csp, String nonce) {
        if (csp == null || csp.isBlank()) {
            return csp;
        }
        String scriptSource = scriptDirective(csp);
        if (scriptSource == null) {
            return csp;                       // nothing that could concern us
        }
        String lower = scriptSource.toLowerCase(Locale.ROOT);
        if (lower.contains("'self'") || lower.contains("localhost") || lower.contains("127.0.0.1")) {
            return csp;                       // our own origin is allowed - the normal case
        }
        if (nonce != null && !nonce.isBlank()) {
            return csp;                       // bootstrap() attaches that nonce to our script
        }
        if (!relaxCsp) {
            LOG.info("browser: the CSP blocks the bridge and is not changed "
                    + "(browser.rewrite.relaxCsp=false) - the session is blind for the LLM");
            return csp;
        }
        StringBuilder out = new StringBuilder();
        for (String directive : csp.split(";")) {
            String item = directive.trim();
            if (item.isEmpty()) {
                continue;
            }
            String name = item.split("\\s+")[0].toLowerCase(Locale.ROOT);
            if (name.equals("script-src") || name.equals("default-src")
                    || name.equals("frame-ancestors") || name.equals("x-frame-options")) {
                LOG.debug("browser: CSP directive removed: {}", item);
                continue;
            }
            out.append(out.isEmpty() ? "" : "; ").append(item);
        }
        return out.toString();
    }

    /**
     * The source that admits scripts - {@code script-src}, otherwise {@code default-src}.
     *
     * <p>Only this one matters for the bridge. A policy that names {@code img-src 'self'} but
     * {@code script-src 'nonce-…'} does <em>not</em> let us through, and admitting us because somewhere
     * {@code 'self'} stands would be a fallacy that only shows up at runtime as "the page looks empty
     * to the LLM".</p>
     *
     * @param csp the policy
     * @return the value of the script directive or {@code null}
     */
    private static String scriptDirective(String csp) {
        String fallback = null;
        for (String directive : csp.split(";")) {
            String item = directive.trim();
            if (item.isEmpty()) {
                continue;
            }
            String[] parts = item.split("\\s+", 2);
            String name = parts[0].toLowerCase(Locale.ROOT);
            if (name.equals("script-src")) {
                return parts.length > 1 ? parts[1] : "";
            }
            if (name.equals("default-src") && fallback == null) {
                fallback = parts.length > 1 ? parts[1] : "";
            }
        }
        return fallback;
    }

    /**
     * Reads a {@code nonce-...} out of a policy so that the bridge can reuse it.
     *
     * @param csp the policy
     * @return the nonce value without quotes, or {@code null}
     */
    public static String extractCspNonce(String csp) {
        if (csp == null) {
            return null;
        }
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("nonce-([A-Za-z0-9+/=_-]{8,64})").matcher(csp);
        return matcher.find() ? matcher.group(1) : null;
    }

    // ------------------------------------------------------------------ internals

    private String rewriteTag(String tag, String pagePath) {
        StringBuilder out = new StringBuilder(tag.length() + 32);
        int i = 0;
        int length = tag.length();
        // take the tag name segment over unchanged
        int nameEnd = firstWhitespace(tag, 1);
        out.append(tag, 0, nameEnd);
        i = nameEnd;
        String tagName = tag.substring(1, Math.max(1, nameEnd)).toLowerCase(Locale.ROOT);
        while (i < length) {
            while (i < length && Character.isWhitespace(tag.charAt(i))) {
                out.append(tag.charAt(i++));
            }
            if (i >= length || tag.charAt(i) == '>' || tag.charAt(i) == '/') {
                out.append(tag.substring(i));
                break;
            }
            int eq = indexOfUnquoted(tag, '=', i);
            int space = firstWhitespace(tag, i);
            int nameStop = eq < 0 ? space : Math.min(eq, space);
            if (nameStop <= i) {
                out.append(tag.substring(i));
                break;
            }
            String attrName = tag.substring(i, nameStop);
            out.append(attrName);
            i = nameStop;
            if (i >= length || tag.charAt(i) != '=') {
                continue;                                    // attribute without a value
            }
            out.append('=');
            i++;
            char quote = i < length ? tag.charAt(i) : ' ';
            String value;
            int after;
            String q;
            if (quote == '"' || quote == '\'') {
                int close = tag.indexOf(quote, i + 1);
                close = close < 0 ? length : close;
                value = tag.substring(i + 1, close);
                after = Math.min(length, close + 1);
                q = String.valueOf(quote);
            } else {
                int stop = firstWhitespace(tag, i);
                value = tag.substring(i, stop);
                after = stop;
                // values without quotes: no quotation mark may be invented here that did not stand in
                // the original - and the closing character belongs to the tag.
                q = "";
            }
            String lowerName = attrName.toLowerCase(Locale.ROOT);
            if (tagName.equals("meta") && isMetaRefresh(tag, attrName)) {
                out.append(q).append(refreshContent(value, pagePath)).append(q);
            } else if (lowerName.equals("srcset")) {
                out.append(q).append(srcset(value, pagePath)).append(q);
            } else if (lowerName.equals("style")) {
                out.append(q).append(cssUrls(value, pagePath)).append(q);
            } else if (URL_ATTRIBUTES.contains(lowerName) || (lowerName.startsWith("data-")
                    && looksUrlish(value))) {
                out.append(q).append(jsonEscapeAttribute(toProxy(value, pagePath))).append(q);
            } else {
                out.append(q).append(value).append(q);
            }
            i = after;
        }
        return out.toString();
    }

    private boolean isMetaRefresh(String tag, String attrName) {
        return attrName.equalsIgnoreCase("content")
                && tag.toLowerCase(Locale.ROOT).contains("http-equiv");
    }

    private String refreshContent(String value, String pagePath) {
        int url = value.toLowerCase(Locale.ROOT).indexOf("url=");
        if (url < 0) {
            return value;
        }
        return value.substring(0, url + 4) + toProxy(value.substring(url + 4), pagePath);
    }

    private String srcset(String value, String pagePath) {
        String[] candidates = value.split(",");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < candidates.length; i++) {
            String candidate = candidates[i].trim();
            if (candidate.isEmpty()) {
                continue;
            }
            String[] parts = candidate.split("\\s+", 2);
            out.append(out.length() == 0 ? "" : ", ").append(toProxy(parts[0], pagePath));
            if (parts.length > 1) {
                out.append(' ').append(parts[1]);
            }
        }
        return out.toString();
    }

    private String cssUrls(String css, String pagePath) {
        StringBuilder out = new StringBuilder(css.length() + 32);
        int i = 0;
        String lower = css.toLowerCase(Locale.ROOT);
        while (true) {
            int at = lower.indexOf("url(", i);
            if (at < 0) {
                out.append(css, i, css.length());
                break;
            }
            int close = css.indexOf(')', at);
            if (close < 0) {
                out.append(css, i, css.length());
                break;
            }
            out.append(css, i, at + 4);
            String raw = css.substring(at + 4, close).trim();
            char quote = raw.startsWith("\"") || raw.startsWith("'") ? raw.charAt(0) : 0;
            String inner = quote != 0 ? raw.substring(1, Math.max(1, raw.length() - 1)) : raw;
            String mapped = toProxy(inner, pagePath);
            out.append(quote != 0 ? quote + mapped + quote : mapped).append(')');
            i = close + 1;
        }
        return out.toString();
    }

    /**
     * Relative path against the upstream path of the document, with {@code ..}/{@code .} resolution.
     *
     * @param pagePath upstream path of the document
     * @param relative the entry without a scheme and without a leading {@code /}
     * @return a path that is correct directly behind the session prefix
     */
    static String joinPath(String pagePath, String relative) {
        String base = pagePath == null || pagePath.isEmpty() ? "/" : pagePath;
        if (!base.startsWith("/")) {
            base = "/" + base;
        }
        int slash = base.lastIndexOf('/');
        String dir = slash < 0 ? "/" : base.substring(0, slash + 1);
        String joined = dir + relative;
        List<String> segments = new ArrayList<>();
        for (String segment : joined.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (!segments.isEmpty()) {
                    segments.remove(segments.size() - 1);
                }
                continue;
            }
            segments.add(segment);
        }
        StringBuilder out = new StringBuilder();
        for (String segment : segments) {
            out.append('/').append(segment);
        }
        return out.length() == 0 ? "/" : out.toString();
    }

    private static String hostOf(String rest) {
        int end = indexOfFirst(rest, '/', '?', '#');
        return end < 0 ? rest : rest.substring(0, end);
    }

    private static int indexOfFirst(String text, char... chars) {
        for (int i = 0; i < text.length(); i++) {
            for (char c : chars) {
                if (text.charAt(i) == c) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int firstWhitespace(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i)) || text.charAt(i) == '>' || text.charAt(i) == '/') {
                return i;
            }
        }
        return text.length();
    }

    private static int indexOfUnquoted(String text, char needle, int from) {
        char quote = 0;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == needle) {
                return i;
            } else if (c == '>') {
                return -1;
            }
        }
        return -1;
    }

    private static int findTagEnd(String body, int from) {
        char quote = 0;
        for (int i = from + 1; i < body.length(); i++) {
            char c = body.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return i;
            }
        }
        return -1;
    }

    private static int findCloseTag(String body, int from, String name) {
        int at = indexOfIgnoreCase(body, from, "</" + name);
        return at < 0 ? body.length() : at;
    }

    private static String tagName(String tag) {
        int end = firstWhitespace(tag, 1);
        String name = tag.substring(1, Math.max(1, end)).toLowerCase(Locale.ROOT);
        return name.replace("/", "").replace(">", "");
    }

    private static boolean regionEqualsIgnoreCase(String text, int at, String needle) {
        if (at + needle.length() > text.length()) {
            return false;
        }
        return text.regionMatches(true, at, needle, 0, needle.length());
    }

    private static int indexOfIgnoreCase(String text, int from, String needle) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.indexOf(needle.toLowerCase(Locale.ROOT), from);
    }

    private static boolean looksUrlish(String value) {
        if (value == null || value.isBlank() || value.length() > 2000) {
            return false;
        }
        String v = value.trim();
        return !(v.startsWith("{") || v.startsWith("[") || v.contains(" "));
    }

    private static String jsonEscapeAttribute(String value) {
        return value == null ? "" : value.replace("\"", "&quot;");
    }
}
