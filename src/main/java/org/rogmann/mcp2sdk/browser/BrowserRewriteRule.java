package org.rogmann.mcp2sdk.browser;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * One response rewrite rule of an application entry (docs/browser.md §3a) - the declarative form of
 * the intervention that real reverse-proxy products also only reach with configured rules: a return
 * URL that the application has put as a <em>value</em> into a URL and that names the upstream
 * authority, because the application can only name what it sees.
 *
 * <p>Deliberately a record of five fields and nothing more: a rule <b>instance is data</b> (the LLM
 * sees the capture with the wrong {@code ru} and can propose the rule; the human commits it into
 * {@code browser-apps.json}), and only a genuinely new <em>shape</em> becomes a new type - code,
 * reviewed. The registry drops an unknown type with a complaint instead of guessing.</p>
 *
 * <p>The rule is applied by the {@link BrowserRewriter}, server-side, before the page reaches the
 * browser - so the capture shows the form action the browser really received. Not in the bridge: a
 * client-side correction would leave the capture with the uncorrected form (§8 - being unbent and
 * being invisible are two different things).</p>
 *
 * @param type       only {@code "queryParamUrl"} today
 * @param param      name of the query parameter whose value is itself an absolute URL
 *                   ({@code ru}, {@code redirect_uri}, ...)
 * @param from       which authority the embedded URL is expected to name: {@code "upstream"} (the
 *                   authority the proxy addresses the application at - what {@code X-Forwarded-Host}
 *                   carries) or a literal {@code host[:port]}
 * @param to         what to put in its place: {@code "session"} (the origin the browser used for the
 *                   proxy; in path mode bent into the session prefix, exactly like a self-reference)
 *                   or a literal origin {@code scheme://host[:port]}
 * @param pageFilter optional regex, <b>full match</b> against the upstream path of the response, so
 *                   that the rule only fires on the pages that carry the form; empty = every page
 */
record BrowserRewriteRule(String type, String param, String from, String to, String pageFilter) {

    /** The one type this registry knows; more only as reviewed code. */
    static final String TYPE_QUERY_PARAM_URL = "queryParamUrl";

    /** Symbolic {@code from}: the authority the proxy addresses the application at. */
    static final String FROM_UPSTREAM = "upstream";

    /** Symbolic {@code to}: the origin the browser used for the proxy (plus session prefix). */
    static final String TO_SESSION = "session";

    BrowserRewriteRule {
        // The type keeps the operator's spelling: the complaint has to name what stood in the file,
        // not a normalized version of it.
        type = type == null ? "" : type.trim();
        param = param == null ? "" : param.trim();
        from = from == null ? "" : from.trim();
        to = to == null ? "" : to.trim();
        pageFilter = pageFilter == null ? "" : pageFilter.trim();
    }

    /**
     * Checks the shape so that the registry can complain about exactly what is wrong.
     *
     * @return an error text, or {@code null} when the rule is usable
     */
    String validationError() {
        if (type.isEmpty()) {
            return "rewrite rule without \"type\"";
        }
        if (!TYPE_QUERY_PARAM_URL.equalsIgnoreCase(type)) {
            return "unknown rewrite rule type '" + type + "' (known: " + TYPE_QUERY_PARAM_URL + ")";
        }
        if (param.isEmpty()) {
            return "rewrite rule without \"param\"";
        }
        if (from.isEmpty()) {
            return "rewrite rule without \"from\"";
        }
        if (to.isEmpty()) {
            return "rewrite rule without \"to\"";
        }
        if (!pageFilter.isEmpty()) {
            try {
                Pattern.compile(pageFilter);
            } catch (PatternSyntaxException e) {
                return "pageFilter is not a regex: " + e.getMessage();
            }
        }
        return null;
    }

    /**
     * @param pagePath the upstream path of the response being rewritten
     * @return true when the rule is active on this page (no filter = always)
     */
    boolean appliesToPage(String pagePath) {
        if (pageFilter.isEmpty()) {
            return true;
        }
        return pagePath != null && pagePath.matches(pageFilter);
    }

    /**
     * Short form for the index page and the tool output: the rule has to be visible where the
     * operator reads what the proxy does to an application (L9 pattern - an active intervention
     * stays named).
     *
     * @return e.g. {@code queryParamUrl:ru}
     */
    String display() {
        return type + ":" + param;
    }
}
