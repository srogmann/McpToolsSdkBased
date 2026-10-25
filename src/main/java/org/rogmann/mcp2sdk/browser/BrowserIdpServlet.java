package org.rogmann.mcp2sdk.browser;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * The one page of the IdP simulation (docs/browser.md §3a): the second factor, reached as
 * {@code GET /?state=…&user=…&ru=…} on the IdP's own origin.
 *
 * <p>The form posts to exactly the {@code ru} the application handed over - the address the
 * application built from what <em>its</em> request named (X-Forwarded-Host). That is the whole point
 * of the fixture: without a rewrite rule the return lands on the original server, outside the proxy
 * session; with the rule ({@code queryParamUrl}, docs/browser.md §3a) it lands back in the session.
 * The IdP itself does not judge the address: it is the application's declaration, as with a real
 * front channel.</p>
 *
 * <p>Plain servlet on purpose: the IdP is not an application the proxy operates, so it shares nothing
 * with the webapp - no Spring, no bridge, no capture, no cookie of the tracker (a cookie belongs to a
 * host, never to a port). The round-trip state is in the same JVM by construction of the simulation;
 * the IdP mints ({@link BrowserDemoApp.SecondFactor#issue}), the tracker redeems
 * ({@link BrowserDemoApp.SecondFactor#redeem}) - the two halves a real setup would separate.</p>
 */
final class BrowserIdpServlet extends HttpServlet {

    static final String PARAM_STATE = "state";
    static final String PARAM_USER = "user";
    static final String PARAM_RU = "ru";

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String state = trim(request.getParameter(PARAM_STATE));
        String user = trim(request.getParameter(PARAM_USER));
        String ru = trim(request.getParameter(PARAM_RU));
        if (ru == null || !(ru.startsWith("http://") || ru.startsWith("https://"))) {
            // An IdP does not guess where its clients live: without a return address there is no
            // round trip, and a relative or non-http address is not one.
            response.setStatus(400);
            response.setContentType("text/html;charset=UTF-8");
            response.getWriter().write(page("Demo IdP - no return address", """
                    <h1>Demo IdP <span class="hint">second factor for the bug tracker</span></h1>
                    <p class="notice">The IdP sends the browser back to the address the application
                    handed over (the <code>ru</code> parameter). Without it there is nothing to return
                    to - call the second factor from the sign-in page of the tracker.</p>"""));
            return;
        }
        response.setContentType("text/html;charset=UTF-8");
        response.getWriter().write(handle(state, user, ru));
    }

    /**
     * The servlet body without the container - doGet wraps this, and the tests render the pages
     * through it, exactly like they did through the old controller method.
     *
     * @param state the round trip opened on the sign-in page (may be empty or unknown)
     * @param user  who is signing in, carried over by the sign-in page's script (may be empty)
     * @param ru    the absolute return address the application handed over
     * @return the page (HTML) the browser gets
     */
    String handle(String state, String user, String ru) {
        String token = BrowserDemoApp.SecondFactor.issue(state);
        return token == null ? noOpenSignIn(state, ru) : secondFactorPage(user, state, token, ru);
    }

    /**
     * The second factor: one field (named {@code otp}, so {@code BrowserSecrets} treats it as a secret
     * wherever the name is seen) and the button that answers the round trip - a POST to the {@code ru}
     * with the token as a hidden field, never in a query.
     */
    String secondFactorPage(String user, String state, String token, String ru) {
        String name = user == null || user.isBlank() ? "guest" : user.trim();
        String askedFor = name.equals("guest") ? "" : ", asked for by <b>" + esc(name) + "</b>";
        return page("Demo IdP - Second factor", """
                <h1>Demo IdP <span class="hint">second factor for the bug tracker</span></h1>
                <p class="muted">The browser is on <code>%1$s</code> now - the <b>IdP's own origin</b>:
                this page reached it past every proxy, unrewritten and without a bridge. The cookie of
                the tracker does not follow it here, because a cookie belongs to a host and never to a
                port, so the state of the round trip is what travels: <code>%4$s</code>%2$s</p>
                <form id="factor" method="post" action="%3$s">
                  <fieldset><legend>Second factor for %5$s</legend>
                    <input type="hidden" name="state" value="%4$s">
                    <input type="hidden" name="user" value="%5$s">
                    <input type="hidden" name="token" value="%6$s">
                    <div><label for="factorCode">2nd factor</label>
                         <input id="factorCode" name="otp" type="text" autocomplete="off"
                                placeholder="any digits, the value is not checked"></div>
                    <div><button type="submit" id="submitFactor" name="action" value="submit">submit</button>
                         <span class="hint">the token of this form is a hidden field - hidden fields
                         stand in no snapshot, and the answer of the tracker is a list</span></div>
                  </fieldset>
                </form>
                <p class="muted"><b>submit</b> posts to <code>%3$s</code>: back to the sign-in of the
                tracker with the token this IdP minted for that state - the address the application
                handed over, built from what its request named (X-Forwarded-Host). Without a rewrite
                rule that is the original server, outside the proxy session; with the rule
                (queryParamUrl, docs/browser.md §3a) it is the session origin. The token goes in the
                <em>body</em> of the POST and never in a query.</p>
                """.formatted(esc(BrowserIdpServer.idpOrigin()), askedFor, esc(ru), esc(state),
                esc(name), esc(token)));
    }

    /**
     * What somebody sees who types the URL by hand, or whose round trip has expired: the IdP mints a
     * token for an open sign-in of the tracker and for nothing else. The way back is the {@code ru}
     * the caller brought - the IdP does not know a second address.
     */
    String noOpenSignIn(String state, String ru) {
        String asked = state == null ? "" : state.trim();
        return page("Demo IdP - No open sign-in", """
                <h1>Demo IdP <span class="hint">second factor for the bug tracker</span></h1>
                <p class="notice">Nothing is waiting for the state <code>%1$s</code>: it was never
                issued, was already used, or is older than %2$d minutes. An IdP mints a token for an
                open sign-in of its own and for nothing else.</p>
                <p><a href="%3$s">back to where the round trip came from</a></p>
                """.formatted(asked.isEmpty() ? "(none was given)" : esc(asked),
                BrowserDemoApp.ROUND_TRIP_TTL.toMinutes(), esc(ru)));
    }

    /**
     * The page frame of the IdP - deliberately plainer than the tracker's pages: a different
     * application looks like a different application.
     */
    private static String page(String title, String body) {
        return """
                <!DOCTYPE html><html lang="en"><head><meta charset="utf-8"><title>%1$s</title>
                <style>body{font:14px/1.5 system-ui,sans-serif;margin:2rem;max-width:44rem;color:#1a1a2e}
                h1{font-size:1.2rem} .hint{font-size:.8rem;color:#666;font-weight:normal}
                .muted{color:#444} .notice{background:#fff3cd;border:1px solid #e0c878;
                padding:.6rem .9rem;border-radius:4px} label{display:block;margin-top:.8rem}
                input[type=text]{padding:.3rem .4rem;border:1px solid #999;border-radius:3px}
                button{margin-top:.8rem;padding:.3rem .9rem} fieldset{margin:1rem 0;padding:.8rem 1rem}
                </style></head><body>%2$s</body></html>""".formatted(esc(title), body);
    }

    private static String esc(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
