package org.rogmann.mcp2sdk.browser;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The built-in target application: a small <b>bug tracker</b> - "Demo bug tracker". Sign-in, list of
 * reports, search, editor for new and existing reports, a comment list per report and a three-stage
 * workflow ({@code open -> in progress -> fixed}). Deliberately stocked with exactly the things M0 has
 * to be checked on, so that the self-test does not depend on a real application: a tracker is the least
 * abstract thing a company usually has, and it still holds a form, a grid, a frame, an autocompletion
 * and a password field.
 *
 * <p>Reachable directly under {@code /browserdemo/} ({@link #PATH}), for the proxy test under
 * {@code /browser/browserdemo/<session>/} (registry entry {@code self:/browserdemo/}). The path is
 * deliberately not {@code /demo/} - that one is common enough to be taken by a real application. What
 * is checked with it:</p>
 * <ul>
 *   <li><b>form fields</b>: {@code select} (the status - and it only ever offers the values the
 *       workflow leaves open), {@code date}, {@code number}, {@code checkbox}, {@code textarea},
 *       {@code contenteditable} (the note, which the page's own script copies into a hidden field
 *       before the submit), a skipped mandatory field ({@code title} with {@code required}), disabled
 *       and readonly fields (the generated ID, the generated creation date, the computed counter),
 *       a field the page calculates from another one and that stays writable ({@code reference} -
 *       M2's {@code computed} case, because M1 cannot refuse it), {@code label} connection over
 *       {@code for} and over wrapping;</li>
 *   <li><b>iframe</b> ({@code /browserdemo/frame.html}) - same-origin after the rewrite, so the bridge
 *       can traverse it (decision L5);</li>
 *   <li><b>{@code fetch} to root-absolute paths</b> ({@code /browserdemo/api/applications},
 *       {@code /browserdemo/api/open}, {@code /browserdemo/api/report}) - the proof that the bridge
 *       patch takes effect: without it the request would run past the session prefix and the proxy
 *       would have seen a 404;</li>
 *   <li><b>late DOM changes</b> - the frame fills three fields after 400 ms, the "load counters"
 *       button writes into a field once the answer arrives, the comment button appends a row, the
 *       submit writes the note into a hidden field. That is the proof for the settle detection: a
 *       snapshot pulled too early shows empty fields and {@code quiescent:false};</li>
 *   <li><b>password field, sensitive path and a cookie</b> ({@code /browserdemo/login}) - the proof for
 *       the redaction (the capture holds the field name, never the value) and for the cookie rewriting
 *       of decision L3: the sign-in hands the user name back as a {@code Set-Cookie} whose path the
 *       proxy bends onto the session prefix;</li>
 *   <li><b>the SSO excursion to a second host</b> - the {@code 2FA} button of the sign-in runs to
 *       {@code http://idp.localhost:<port>/browserdemo/idp} (see {@link #IDP_HOST_PROPERTY}), the
 *       IdP's {@code submit} runs back to {@code /browserdemo/login} with a token this application
 *       minted for that round trip ({@link SecondFactor}). That is the case docs/browser.md §8 names as
 *       <em>not bent</em>: the URL keeps its foreign host, so the browser really leaves the origin of
 *       the session and comes back. Checked with it: whether the session survives the hop (port mode:
 *       the listener answers under every host name, the host has never routed anything - §2b), whether
 *       the bridge re-attaches on the other name, what the cookie jar of the second host sees (nothing
 *       of the tracker), and whether the token of the front channel reaches the capture. It is also the
 *       stand-in for the SPNEGO/Kerberos dance, which the same excursion would have to survive. In
 *       <b>port mode</b> the hop stays inside the session, because the listener answers under every
 *       host name; in <b>path mode</b> the same URL runs past the proxy into the direct route of this
 *       application - the page still works for the human and is blind for the model, which is the
 *       finding and not something to hide here (docs/browser.md §8, §10 step 10).</li>
 *   <li><b>grids with row headers</b> - the report list keyed by its ID, the comment table by its
 *       number, with "add comment row". That is the case in which the column heading has to become
 *       {@code colHead} and the row's first cell {@code rowHead};</li>
 *   <li><b>answers that are not a plain success</b> - a missing mandatory field, an illegal workflow
 *       step and an unknown report ID each come back as the page with a visible message, which is what
 *       M1's readback ("the application's validation text") has to find.</li>
 * </ul>
 *
 * <p><b>Reports live in a JSONL file</b>: {@value #STORE_FILE} in the project base
 * ({@code IDE_PROJECT_DIR}), {@code -Dbrowser.demo.store=...} for another one. One report per line,
 * the whole file rewritten on every change - the file is the application's database and has to stay
 * readable and editable by hand, which append-only would not be (every edited report would stand in it
 * twice, and reading it would mean replaying a log). Reading never writes: the file appears with the
 * first save, seed included. A broken line is dropped with a warning instead of making the application
 * unusable - the same rule that lets {@link BrowserApps} drop a single faulty registry entry.</p>
 *
 * <p><b>Not the working directory.</b> The file used to hang there, which meant: started from
 * {@code $HOME}, the test data and the session logs of the proxy scattered through the home directory
 * and the registry read "file not found" although it lies in the project. It is anchored like every
 * other data path here (see {@link BrowserSessions#anchorDataPath}); with no project base and no
 * explicit path the tracker still works, in memory only, and says so on the page instead of writing to
 * a guessed location.</p>
 *
 * <p><b>The pages are English.</b> This class is not our output but a stand-in for the proxied
 * application, so its labels and field names are the data the snapshot, the search and the redaction
 * are exercised on. Redaction works on <em>field names</em> and not on displayed text, so an English
 * fixture does not lose the German case: {@link BrowserSecrets} keeps the German spellings
 * ({@code passwort}, {@code kennwort}, {@code geheimnis}, {@code tan}, …) because the applications that
 * are really proxied here are German, and {@code BrowserRewriterTest} carries exactly those names as
 * test data. Two of them stay visible in the pages: the second factor of the sign-in is named
 * {@code tan}, the one of the IdP {@code otp} - both are on the German list, not on the English one.</p>
 */
@RestController
@RequestMapping(BrowserDemoApp.PATH)
public class BrowserDemoApp {

    /**
     * The path under which this target application runs directly. Deliberately not {@code /demo} - that
     * path is common enough that a real application on this server could claim it. The pages below
     * reference it as literal text ({@code /browserdemo/...}), so a change here has to change those too
     * ({@code BrowserRewriterTest#demoPagesStayOnTheDemoPath()} keeps that honest).
     */
    public static final String PATH = "/browserdemo";

    /** Name of the file this application keeps its reports in, see {@link #storeFile()}. */
    public static final String STORE_FILE = "browser_demo_app.jsonl";

    /** System property that moves the report file somewhere else. */
    static final String STORE_PROPERTY = "browser.demo.store";

    /** The cookie this application hands its signed-in user to the browser. */
    private static final String USER_COOKIE = "browserdemo_user";

    /** The user that stands in the sign-in form and in the seed comments. */
    private static final String DEFAULT_USER = "user1024";

    /**
     * Field name of the second factor on the IdP page. It is called {@code otp} and not
     * {@code factor} on purpose: {@link BrowserSecrets} knows the name, so the value is writable for
     * the model but never readable - which is the only right behaviour for a second factor that a
     * machine is allowed to type in.
     */
    static final String FACTOR_FIELD = "otp";

    /**
     * Label of the built-in IdP host. The excursion runs to {@code idp} + the configured
     * {@code browser.host.suffix}, which is {@code idp.localhost} by default - a second name of the
     * same machine, not a second machine (the listener binds 127.0.0.1 and answers under every host
     * name, because the host has never been a routing criterion, see {@link BrowserHost}).
     */
    static final String IDP_HOST_LABEL = "idp";

    /**
     * System property that names the IdP host outright, e.g. when {@code *.localhost} does not resolve
     * on the platform (Windows, {@link BrowserHost#needsHostsEntry}). Only the host, no scheme, no port
     * - the port comes from {@link #browserPort()} either way.
     */
    static final String IDP_HOST_PROPERTY = "browser.demo.idp.host";

    /** Prefix of a token the tracker accepts as "this one came back from our IdP". */
    private static final String TOKEN_PREFIX = "2FA-";

    /** How long an open round trip waits for its return, and how many may be open at once. */
    private static final Duration ROUND_TRIP_TTL = Duration.ofMinutes(10);
    private static final int ROUND_TRIPS_MAX = 64;

    /** The states of the workflow, in the order it walks through them. */
    static final String STATE_OPEN = "open";
    static final String STATE_IN_PROGRESS = "in progress";
    static final String STATE_FIXED = "fixed";
    static final List<String> STATES = List.of(STATE_OPEN, STATE_IN_PROGRESS, STATE_FIXED);

    /** Prefix of a generated report ID: {@code DID-0013}. */
    private static final String ID_PREFIX = "DID-";

    /** Applications the autocompletion suggests - a fixed catalog */
    private static final List<String> APPLICATION_CATALOG =
            List.of("browser-proxy");

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final Logger LOG = LoggerFactory.getLogger(BrowserDemoApp.class);

    /** The reports - the whole state of this application. */
    private static final IssueStore STORE = new IssueStore();

    // ========================================================================
    // Sign-in
    // ========================================================================

    /**
     * The entry point: the sign-in. The list is reachable without it as well - a session that only ever
     * sees this page would prove too little, and the report list is what the model gets asked about.
     *
     * @return HTML
     */
    @GetMapping(value = {"/", ""}, produces = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
    public String index() {
        return page("Demo bug tracker - Sign in", signInBody(""), SIGNIN_SCRIPT);
    }

    /**
     * The sign-in, and with it the application's first function: <b>logon -&gt; list of bug
     * reports</b>. The password has to be <em>there</em>, its value is not looked at: nobody but the
     * user knows it, and the capture must not see it either. That is deliberate - an M1 model can fill
     * this form up to the password and then has to hand over to the human.
     *
     * <p>The user name comes back as a cookie, which makes the sign-in the demonstration of decision
     * L3 at the same time: the proxy rewrites the {@code Path} of this header onto the session
     * prefix.</p>
     *
     * <p>The second half of the method answers the <b>return of the IdP excursion</b>: when a
     * {@code token} comes in, no password is asked for - the tracker believes the token because it
     * handed it out itself for an open round trip ({@link SecondFactor}), which is what an application
     * does with an assertion of its IdP. Everything about that round trip - one time only, unknown
     * state, mismatched token - is in {@link #afterSecondFactor}.</p>
     *
     * @param user     user name
     * @param password checked for presence only
     * @param tan      second factor - a second field with a secret-sounding name
     * @param state    the round trip the IdP is answering for
     * @param token    the token the IdP brought back
     * @param response for the cookie
     * @return HTML: the list of reports
     */
    @PostMapping(value = "/login", produces = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
    public String login(@RequestParam(name = "user", defaultValue = "") String user,
            @RequestParam(name = "password", defaultValue = "") String password,
            @RequestParam(name = "tan", defaultValue = "") String tan,
            @RequestParam(name = "state", defaultValue = "") String state,
            @RequestParam(name = "token", defaultValue = "") String token,
            HttpServletResponse response) {
        if (!token.isBlank()) {
            return afterSecondFactor(user, state, token, response);
        }
        if (password.isBlank()) {
            return page("Demo bug tracker - Sign in", signInBody(
                    "No sign-in without a password. The value is not checked - it only may not be"
                    + " empty. (Or take the <b>2FA</b> button below and come back with a token.)"),
                    SIGNIN_SCRIPT);
        }
        String name = user.isBlank() ? "guest" : user.trim();
        response.addHeader("Set-Cookie", USER_COOKIE + "="
                + URLEncoder.encode(name, StandardCharsets.UTF_8) + "; SameSite=Lax");
        return listPage(name, Filters.empty(), "Signed in as <b>" + esc(name) + "</b> at "
                + LocalTime.now().format(CLOCK) + " - that is the list of bug reports. The capture"
                + " holds the field name <code>password</code>, not its value.");
    }

    /**
     * The sign-in the <b>IdP excursion</b> ends with: the same path, the same cookie, the same list -
     * only that the credential that got here is a token and not a password.
     *
     * @param user     who the IdP says is standing in front of it (empty without the page's script)
     * @param state    the round trip the tracker opened on its sign-in page
     * @param token    the token the IdP minted for that round trip
     * @param response for the cookie
     * @return HTML: the list of reports, or the sign-in with the reason it was not believed
     */
    private String afterSecondFactor(String user, String state, String token,
            HttpServletResponse response) {
        if (!SecondFactor.redeem(state, token)) {
            return page("Demo bug tracker - Sign in", signInBody(
                    "The token was <b>not accepted</b>: no open sign-in for state <code>"
                    + esc(tidyState(state)) + "</code>, or the token does not belong to it. A token this"
                    + " application did not hand out is worth nothing, and one it did is good exactly"
                    + " once - sign in with the password, or start the second factor again."),
                    SIGNIN_SCRIPT);
        }
        String name = user == null || user.isBlank() ? "guest" : user.trim();
        response.addHeader("Set-Cookie", USER_COOKIE + "="
                + URLEncoder.encode(name, StandardCharsets.UTF_8) + "; SameSite=Lax");
        return listPage(name, Filters.empty(), "Signed in as <b>" + esc(name) + "</b> after the second"
                + " factor at <code>" + esc(idpHost()) + "</code> (" + LocalTime.now().format(CLOCK)
                + ") - the round trip over the second host survived, the password never left this"
                + " origin and the token came back in the body of a POST.");
    }

    /**
     * The <b>other host of the excursion</b>: what the {@code 2FA} button of the sign-in opens, under
     * {@code idp.localhost:<port>} (or {@link #IDP_HOST_PROPERTY}) on the very same listener. Reached
     * by the browser as a normal navigation, so the proxy sees a request whose {@code Host} it was never
     * offered under - and answers it anyway, because the host has never chosen an upstream
     * ({@link BrowserHost}). That is what this page is for: to have something to hop to.
     *
     * <p>Nothing of the tracker arrives here but what stands in the URL: the cookie stays on its own
     * host (a cookie belongs to a host, never to a port) and the session is the one the listener was
     * opened with. The page therefore shows the state it was called with and mints the token for it -
     * a token for a state that is not waiting is not minted at all, which is the answer to somebody
     * typing this URL by hand.</p>
     *
     * @param state the round trip opened on the sign-in page
     * @param user  who is signing in, carried over by the page's script (empty without it)
     * @return HTML: the second-factor form, or the reason there is nothing to answer
     */
    @GetMapping(value = "/idp", produces = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
    public String idp(@RequestParam(name = "state", defaultValue = "") String state,
            @RequestParam(name = "user", defaultValue = "") String user) {
        String token = SecondFactor.issue(state);
        if (token == null) {
            String asked = tidyState(state);
            return page("Demo IdP - No open sign-in", """
                    <h1>Demo IdP <span class="hint">second factor for the bug tracker</span></h1>
                    <p class="notice">Nothing is waiting for the state <code>%1$s</code>: it was never
                    issued, was already used, or is older than %2$d minutes. An IdP mints a token for an
                    open sign-in of its own and for nothing else.</p>
                    <p><a href="%3$s%4$s/">back to the sign in of the tracker</a></p>
                    """.formatted(asked.isEmpty() ? "(none was given)" : esc(asked),
                            ROUND_TRIP_TTL.toMinutes(), esc(appOrigin()), PATH), null);
        }
        return page("Demo IdP - Second factor", idpBody(user, tidyState(state), token), null);
    }

    /**
     * Ends the sign-in by letting the browser drop the cookie.
     *
     * @param response for the expired cookie
     * @return HTML: the sign-in page
     */
    @GetMapping(value = "/logout", produces = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
    public String logout(HttpServletResponse response) {
        response.addHeader("Set-Cookie", USER_COOKIE + "=; Max-Age=0; SameSite=Lax");
        return page("Demo bug tracker - Sign in", signInBody("Signed out - the list stays open."),
                SIGNIN_SCRIPT);
    }

    // ------------------------------------------------------------------ the excursion (SSO test track)

    /**
     * @return the port the browser reaches this application on: the listener the registry names for the
     *         demo, else the normal port of the service (path mode)
     */
    static int browserPort() {
        int port = BrowserSessions.get().apps().byId(BrowserApps.DEMO_ID)
                .map(BrowserApp::port).orElse(0);
        return port > 0 ? port : BrowserSessions.get().localPort();
    }

    /**
     * @return host name of the built-in IdP: {@link #IDP_HOST_PROPERTY}, else {@code idp} plus the
     *         configured {@code browser.host.suffix} ({@code idp.localhost} when the suffix is off)
     */
    static String idpHost() {
        String configured = System.getProperty(IDP_HOST_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        String suffix = BrowserSessions.get().settings().hostSuffix();
        return IDP_HOST_LABEL + (suffix.isEmpty() ? BrowserHost.SUGGESTED_SUFFIX : suffix);
    }

    /**
     * @return the origin the excursion leaves to, e.g. {@code http://idp.localhost:9101}
     */
    static String idpOrigin() {
        return "http://" + idpHost() + ":" + browserPort();
    }

    /**
     * The origin of the tracker itself, as it is <em>offered</em> - which is not what the application
     * can read from its own request: {@link BrowserProxyClient} drops the browser's {@code Host} and
     * sets {@code X-Forwarded-Host} to the <b>upstream</b> authority, so a page that has to name itself
     * absolutely can only take this from the administration. It is the reason an IdP hop of a real
     * application is a problem of its own (docs/browser.md §8).
     *
     * @return e.g. {@code http://browserdemo.localhost:9101}
     */
    static String appOrigin() {
        return "http://" + BrowserHost.of(BrowserApps.DEMO_ID) + ":" + browserPort();
    }

    /**
     * @return the absolute URL of the IdP page - absolute on purpose, the excursion is the case where
     *         the browser is <em>meant</em> to leave this origin. The state of the round trip is not in
     *         it: the 2FA form carries the state as its own field, so the browser builds one query
     *         instead of adding a second one to ours.
     */
    static String idpEntryUrl() {
        return idpOrigin() + PATH + "/idp";
    }

    /**
     * @return the absolute URL the IdP posts its answer back to (the sign-in of the tracker)
     */
    static String loginCallbackUrl() {
        return appOrigin() + PATH + "/login";
    }

    /**
     * An open round trip of the sign-in: the state the tracker put into the URL of the IdP and the
     * token it handed out for it. Held in memory - the excursion is a test track, not a session
     * storage, and a value that has to outlive the process would have to be signed instead.
     *
     * <p>The two steps are apart because that is how the real thing works: the service opens the round
     * trip when it renders its sign-in ({@link #newState()}), the IdP mints the token when it is asked
     * ({@link #issue(String)}, idempotent, so reloading the IdP page does not invalidate it) and the
     * service redeems it on the way back ({@link #redeem(String, String)}, which closes the round trip -
     * a token good twice is no token).</p>
     */
    static final class SecondFactor {

        /** One open round trip: the token handed out for it (empty while none was) and when it opened. */
        private record Trip(String token, Instant openedAt) {
        }

        private static final Map<String, Trip> OPEN = new ConcurrentHashMap<>();
        private static final SecureRandom RANDOM = new SecureRandom();

        private SecondFactor() {
        }

        /**
         * Opens a round trip for the sign-in page that is being rendered.
         *
         * @return the state to put into the URL of the IdP
         */
        static String newState() {
            prune();
            String state = "st-" + secret(6);
            OPEN.put(state, new Trip("", Instant.now()));
            return state;
        }

        /**
         * @param state what the browser brought back from the sign-in page
         * @return the token this IdP hands out for that state, {@code null} when no open sign-in waits
         *         for it (never issued, already redeemed, or too old)
         */
        static String issue(String state) {
            prune();
            String key = tidyState(state);
            if (key.isEmpty()) {
                return null;
            }
            // computeIfPresent and not compute: a state that prune() has just dropped must not be
            // resurrected here, because the sign-in would then redeem a trip nobody opened.
            Trip trip = OPEN.computeIfPresent(key, (name, open) -> new Trip(
                    open.token().isEmpty() ? TOKEN_PREFIX + secret(8) : open.token(), open.openedAt()));
            return trip == null ? null : trip.token();
        }

        /**
         * Redeems a token - the round trip is closed either way, so a second attempt with the same
         * token fails. An empty token is not worth a lookup: the sign-in form of the tracker does not
         * carry the field at all.
         *
         * @param state the round trip
         * @param token what the IdP says it minted for it
         * @return true when state and token belong to one open round trip
         */
        static boolean redeem(String state, String token) {
            String key = tidyState(state);
            if (key.isEmpty() || token == null || token.isBlank()) {
                return false;
            }
            Trip trip = OPEN.remove(key);
            return trip != null && !trip.token().isEmpty() && trip.token().equals(token.trim());
        }

        /**
         * @return the number of open round trips - the counter behind {@link #ROUND_TRIPS_MAX}, and the
         *         one line that says how many sign-in pages have been rendered without being used
         */
        static int open() {
            return OPEN.size();
        }

        /**
         * Forgets what nobody will redeem any more: expired trips first, and the oldest half when the
         * map is full. Every rendering of the sign-in page opens one, so a page that is only looked at
         * would otherwise leave a trail behind. Synchronized because reading the timestamps to order
         * them and removing the oldest of them is one sequence, not two.
         */
        private static synchronized void prune() {
            Instant cutoff = Instant.now().minus(ROUND_TRIP_TTL);
            OPEN.entrySet().removeIf(entry -> entry.getValue().openedAt().isBefore(cutoff));
            if (OPEN.size() < ROUND_TRIPS_MAX) {
                return;
            }
            List<String> states = new ArrayList<>(OPEN.keySet());
            states.sort(Comparator.comparing(state -> {
                Trip trip = OPEN.get(state);
                return trip == null ? Instant.EPOCH : trip.openedAt();
            }));
            states.subList(0, states.size() / 2).forEach(OPEN::remove);
        }

        private static String secret(int bytes) {
            byte[] raw = new byte[bytes];
            RANDOM.nextBytes(raw);
            return HexFormat.of().formatHex(raw);
        }
    }

    /**
     * @param state whatever came in as the state - trimmed, and empty for {@code null}
     * @return the state in the shape {@link SecondFactor} stores it under
     */
    private static String tidyState(String state) {
        return state == null ? "" : state.trim();
    }


    // ========================================================================
    // List and search
    // ========================================================================

    /**
     * The <b>list of bug reports</b>, which is also the answer of a <b>search</b> - one page, the
     * search form stands on it. Unusable query values (a date like {@code 32.13.}) are dropped instead
     * of answered with an error: a search box is typed into.
     *
     * @param q             search text over title, summary, application, ID and the comments
     * @param application   application name, partial, case-insensitive
     * @param state         one of {@link #STATES}, empty for all
     * @param since         ISO date: reports created on or after that day
     * @param olderThanDays only reports whose creation is at least this many days back
     * @param withComments  {@code true}/{@code on}: only reports with at least one comment
     * @param userCookie    the signed-in user, may be missing
     * @return HTML
     */
    @GetMapping(value = "/reports", produces = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
    public String reports(@RequestParam(name = "q", defaultValue = "") String q,
            @RequestParam(name = "application", defaultValue = "") String application,
            @RequestParam(name = "state", defaultValue = "") String state,
            @RequestParam(name = "since", defaultValue = "") String since,
            @RequestParam(name = "olderThanDays", defaultValue = "") String olderThanDays,
            @RequestParam(name = "withComments", defaultValue = "") String withComments,
            @CookieValue(name = USER_COOKIE, required = false, defaultValue = "") String userCookie) {
        return listPage(signedInUser(userCookie),
                new Filters(q, application, state, since, olderThanDays, isYes(withComments)), "");
    }

    // ========================================================================
    // One report
    // ========================================================================

    /**
     * One report: its attributes, its comments, the workflow and the editor for the report itself - so
     * "edit existing report" is one page.
     *
     * @param id         report ID ({@code DID-0013}, case and padding are taken lightly)
     * @param userCookie the signed-in user, may be missing
     * @return HTML
     */
    @GetMapping(value = "/report", produces = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
    public String report(@RequestParam(name = "id", defaultValue = "") String id,
            @CookieValue(name = USER_COOKIE, required = false, defaultValue = "") String userCookie) {
        String user = signedInUser(userCookie);
        Optional<Issue> found = STORE.find(id);
        return found.map(issue -> detailPage(user, issue, "")).orElseGet(() -> unknownReport(user, id));
    }

    /**
     * The empty form for a <b>new report</b>. ID and creation date are generated when saving, so they
     * stand here as disabled fields - the case "field is there, the snapshot reads it, nobody can write
     * it".
     *
     * @param userCookie the signed-in user, may be missing
     * @return HTML
     */
    @GetMapping(value = "/new", produces = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
    public String newReport(@CookieValue(name = USER_COOKIE, required = false, defaultValue = "")
            String userCookie) {
        return editPage(signedInUser(userCookie), null, "");
    }

    /**
     * Creates or changes a report - the path with effect ({@code irreversiblePaths} in the registry).
     * A refusal renders the form <em>with what was typed</em>: an answer that throws away an entered
     * title teaches a model that trying again is pointless.
     *
     * @param id         ID of the report to change, empty for a new one
     * @param title      mandatory
     * @param application mandatory, free text - the datalist only suggests
     * @param state      has to be the current state or the next one of the workflow
     * @param summary    what happened, may be empty
     * @param noteText   the {@code contenteditable} note, copied into this field by the page's script
     * @param userid     who signs the change
     * @param userCookie the signed-in user
     * @return HTML: the report as it now stands
     */
    @PostMapping(value = "/save", produces = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
    public String save(@RequestParam(name = "id", defaultValue = "") String id,
            @RequestParam(name = "title", defaultValue = "") String title,
            @RequestParam(name = "application", defaultValue = "") String application,
            @RequestParam(name = "state", defaultValue = STATE_OPEN) String state,
            @RequestParam(name = "summary", defaultValue = "") String summary,
            @RequestParam(name = "noteText", defaultValue = "") String noteText,
            @RequestParam(name = "userid", defaultValue = "") String userid,
            @CookieValue(name = USER_COOKIE, required = false, defaultValue = "") String userCookie) {
        String user = signedInUser(userCookie);
        String actor = actor(user, userid);
        SaveResult result = STORE.upsert(id, title, application, state, summary);
        if (!result.saved()) {
            Issue draft = result.issue();
            return draft == null || draft.id.isEmpty()
                    ? editPage(actor, draft, message(result.message(), true))
                    : detailPage(actor, draft, message(result.message(), true));
        }
        Issue saved = result.issue();
        String notice = message(result.message(), false);
        if (!noteText.isBlank()) {
            SaveResult note = STORE.addComment(saved.id, actor, "Note: " + noteText);
            notice = message(result.message() + " " + note.message(), !note.saved());
            saved = STORE.find(saved.id).orElse(saved);
        }
        return detailPage(actor, saved, notice);
    }

    /**
     * Adds a comment to a report; the list grows by {@code (date, userid, comment)} and the date comes
     * from the server.
     *
     * @param id         report ID
     * @param userid     who wrote it
     * @param comment    the text, mandatory
     * @param userCookie the signed-in user
     * @return HTML: the report with its comments
     */
    @PostMapping(value = "/comment", produces = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
    public String comment(@RequestParam(name = "id", defaultValue = "") String id,
            @RequestParam(name = "userid", defaultValue = "") String userid,
            @RequestParam(name = "comment", defaultValue = "") String comment,
            @CookieValue(name = USER_COOKIE, required = false, defaultValue = "") String userCookie) {
        String user = signedInUser(userCookie);
        SaveResult result = STORE.addComment(id, actor(user, userid), comment);
        if (result.issue() == null) {
            return unknownReport(user, id);
        }
        return detailPage(user, result.issue(), message(result.message(), !result.saved()));
    }

    /**
     * One step of the workflow: {@code open -> in progress -> fixed}. The step is its own request
     * instead of a field of the form, because it is the thing an application would ask a human about -
     * and the thing M1 will not dare to press alone.
     *
     * @param id         report ID
     * @param next       the state that is asked for
     * @param userCookie the signed-in user
     * @return HTML: the report
     */
    @PostMapping(value = "/state", produces = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
    public String state(@RequestParam(name = "id", defaultValue = "") String id,
            @RequestParam(name = "next", defaultValue = "") String next,
            @CookieValue(name = USER_COOKIE, required = false, defaultValue = "") String userCookie) {
        String user = signedInUser(userCookie);
        SaveResult result = STORE.transition(id, next);
        if (result.issue() == null) {
            return unknownReport(user, id);
        }
        return detailPage(user, result.issue(), message(result.message(), !result.saved()));
    }

    // ========================================================================
    // The frame and the JSON answers
    // ========================================================================

    /**
     * The frame file. It writes its values <em>late</em> - the case in which a snapshot pulled
     * immediately would be wrong and the settle detection has to show itself.
     *
     * @return HTML
     */
    @GetMapping(value = "/frame.html", produces = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
    public String frame() {
        return """
                <!DOCTYPE html>
                <html lang="en"><head><meta charset="utf-8"><title>Test environment</title>
                <style>body{font:14px/1.45 system-ui,sans-serif;margin:.6rem}</style></head><body>
                <div><label for="system">Operating system</label>
                     <input id="system" name="system" value=""></div>
                <div><label for="browser">Browser</label>
                     <input id="browser" name="browser" value=""></div>
                <div><label for="build">Build of the application</label>
                     <input id="build" name="build" value="set later"></div>
                <script>
                setTimeout(() => { document.getElementById('system').value = 'Windows 11 23H2';
                                   document.getElementById('browser').value = 'Firefox 131.0';
                                   document.getElementById('build').value = '2026.10.04-beta'; }, 400);
                </script>
                </body></html>
                """;
    }

    /**
     * Applications for the autocompletion, each with the number of reports that are not fixed yet.
     *
     * @param q search text, may be empty
     * @return a JSON list
     */
    @GetMapping(value = "/api/applications", produces = MediaType.APPLICATION_JSON_VALUE)
    public String applications(@RequestParam(name = "q", defaultValue = "") String q) {
        String needle = q.trim().toLowerCase(Locale.ROOT);
        ArrayNode out = MAPPER.createArrayNode();
        for (String name : STORE.applicationNames()) {
            if (needle.isEmpty() || name.toLowerCase(Locale.ROOT).contains(needle)) {
                out.addObject().put("name", name).put("open", STORE.countOpen(name));
            }
        }
        return out.toString();
    }

    /**
     * The counters of the whole stock; with a timestamp so that the capture shows the request really
     * went through the proxy.
     *
     * @return a JSON object
     */
    @GetMapping(value = "/api/open", produces = MediaType.APPLICATION_JSON_VALUE)
    public String counters() {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("open", STORE.countState(STATE_OPEN));
        out.put("inProgress", STORE.countState(STATE_IN_PROGRESS));
        out.put("fixed", STORE.countState(STATE_FIXED));
        out.put("total", STORE.all().size());
        out.put("file", STORE.fileName());
        out.put("time", LocalTime.now().format(CLOCK));
        return out.toString();
    }

    /**
     * One report exactly as it stands in the file - the same JSON as the JSONL line, so that the answer
     * of the API and the content of the database can be compared by eye.
     *
     * @param id report ID
     * @return JSON, or a 404 with the reason
     */
    @GetMapping(value = "/api/report", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> reportJson(@RequestParam(name = "id", defaultValue = "") String id) {
        Optional<Issue> found = STORE.find(id);
        if (found.isEmpty()) {
            ObjectNode error = MAPPER.createObjectNode();
            error.put("error", "no report with the ID " + tidyId(id));
            error.put("asked", id == null ? "" : id);
            return ResponseEntity.status(404).body(error.toString());
        }
        return ResponseEntity.ok(found.get().toJson().toString());
    }

    // ========================================================================
    // Pages - built without request or response, so that a test can render them
    // ========================================================================

    /**
     * @param notice inner HTML of the message above the form, may be empty
     * @return the body of the sign-in page
     */
    String signInBody(String notice) {
        String state = SecondFactor.newState();
        return """
                <h1>Demo bug tracker <span class="hint">demo target for docs/browser.md</span></h1>
                %1$s
                <form id="login" method="post" action="/browserdemo/login">
                  <fieldset><legend>Sign in</legend>
                    <div><label for="user">User</label><input id="user" name="user" value="%2$s"></div>
                    <div><label for="pw">Password</label><input id="pw" name="password" type="password"
                         placeholder="never logged"></div>
                    <div><label for="tan">Token (2FA)</label><input id="tan" name="tan" type="password"></div>
                    <div><button type="submit" name="action" value="login">Sign in</button>
                         <a href="/browserdemo/reports">to the list without signing in</a></div>
                  </fieldset>
                </form>
                <form id="twofa" method="get" action="%3$s">
                  <fieldset><legend>Second factor at the IdP</legend>
                    <input type="hidden" name="state" value="%4$s">
                    <input type="hidden" id="twofaUser" name="user" value="">
                    <div><button type="submit" id="twofaButton" name="action" value="2fa">2FA</button>
                         <span class="hint">this one leaves the origin: <code>%3$s</code> is a second
                         host name on the same listener</span></div>
                  </fieldset>
                </form>
                <p class="muted">Signing in shows the list of bug reports. The sign-in is the sensitive
                path of this target: the capture holds the field <code>password</code>, not its value.
                The answer also sets a cookie, whose path the proxy bends onto the session prefix.</p>
                <p class="muted"><b>2FA</b> is the excursion: the button hands the browser to the IdP
                under its own host name and the IdP hands it back with a token. The URL of the hop is
                <em>absolute and stays absolute</em> - the proxy does not bend a host it does not know
                (docs/browser.md §8), so what has to be watched here is whether the session survives the
                move, whether the bridge comes back on the other name and whether anything secret comes
                along for the ride. The state of this page is <code>%4$s</code>; nothing else of the
                sign-in travels but what the page's own script puts into the form.</p>
                """.formatted(notice == null || notice.isBlank() ? "" : notice(notice), DEFAULT_USER,
                idpEntryUrl(), esc(state));
    }

    /**
     * The IdP's own page: one field for the second factor and the button that answers the round trip.
     * Built like the rest of the pages without a request, so that a test can render it.
     *
     * @param user  who is asking, as the tracker's page carried it over
     * @param state the open round trip
     * @param token the token the IdP mints for it (a hidden field, like a SAML response or an
     *              OpenID Connect front-channel id token - the browser is the transport)
     * @return the body of the IdP page
     */
    String idpBody(String user, String state, String token) {
        String name = user == null || user.isBlank() ? "guest" : user.trim();
        return """
                <h1>Demo IdP <span class="hint">second factor for the bug tracker</span></h1>
                <p class="muted">The browser is on <code>%1$s</code> now - another host name, the same
                listener, the same session of the proxy. The cookie of the tracker does not follow it
                here, because a cookie belongs to a host and never to a port, so the state of the round
                trip is what travels: <code>%4$s</code>%2$s</p>
                <form id="factor" method="post" action="%3$s">
                  <fieldset><legend>Second factor for %5$s</legend>
                    <input type="hidden" name="state" value="%4$s">
                    <input type="hidden" name="user" value="%5$s">
                    <input type="hidden" name="token" value="%6$s">
                    <div><label for="factorCode">2nd factor</label>
                         <input id="factorCode" name="otp" type="text" autocomplete="off"
                                placeholder="any digits, the value is not checked"></div>
                    <div><button type="submit" id="submitFactor" name="action" value="submit">submit</button>
                         <span class="hint">the token of this form is a hidden field - the snapshot does
                         not show hidden fields, and the answer of the tracker is a list</span></div>
                  </fieldset>
                </form>
                <p class="muted"><b>submit</b> posts to <code>%3$s</code>: back to the sign-in of the
                tracker with the token this IdP minted for that state. The token goes in the <em>body</em>
                of a POST to a sensitive path and never in a query, because the query of an exchange is
                captured as it comes. This page is a sensitive path of its own as well, so the token
                stands in neither the request nor the response of the capture.</p>
                """.formatted(idpOrigin(),
                name.equals("guest") ? "" : ", asked for by <b>" + esc(name) + "</b>",
                loginCallbackUrl(), esc(state), esc(name), esc(token));
    }

    /**
     * @param user    signed-in user, may be empty
     * @param filters what stands in the search form
     * @param notice  inner HTML of the message, may be empty
     * @return the list page
     */
    String listPage(String user, Filters filters, String notice) {
        List<Issue> found = STORE.search(filters);
        StringBuilder body = new StringBuilder(4096);
        body.append(header(user)).append(notice(notice));
        body.append(searchForm(filters));
        body.append("<h2>Hits: ").append(found.size()).append(" of ").append(STORE.all().size())
                .append(" reports</h2>");
        body.append(reportTable(found));
        body.append("""
                <fieldset><legend>Counters</legend>
                  <div><label for="openCount">Open reports (computed)</label>
                       <input id="openCount" name="openCount" type="text" value="" disabled></div>
                  <div><button type="button" id="loadCount">Load counters (fetch)</button></div>
                </fieldset>
                """);
        body.append(frameFieldset());
        body.append("<h2>Later</h2><p>Action log: <span id=\"log\">-</span></p>");
        body.append(stockNote());
        return page("Demo bug tracker - Reports", body.toString(), LIST_SCRIPT);
    }

    /**
     * @param user   signed-in user, may be empty
     * @param issue  the report to show
     * @param notice inner HTML of the message, may be empty
     * @return the detail page with comments, workflow and editor
     */
    String detailPage(String user, Issue issue, String notice) {
        StringBuilder body = new StringBuilder(4096);
        body.append(header(user)).append(notice(notice));
        body.append("<h2>Report ").append(esc(issue.id)).append("</h2>");
        body.append(reportTable(List.of(issue)));
        body.append("<p class=\"flow\">Workflow: ").append(workflowLine(issue.state)).append("</p>");
        body.append("<h2>Comments (").append(issue.comments.size()).append(")</h2>");
        body.append(commentTable(issue));
        body.append(commentForm(user, issue.id));
        body.append(stateForm(issue));
        body.append(reportForm(user, issue));
        body.append("""
                <fieldset><legend>Raw data</legend>
                  <div><button type="button" id="rawJson" data-id="%1$s">Load the JSON of this report (fetch)</button>
                       <a href="/browserdemo/api/report?id=%2$s">the same answer as a link</a></div>
                  <pre id="rawdata">-</pre>
                </fieldset>
                """.formatted(esc(issue.id), esc(issue.id)));
        body.append(frameFieldset());
        body.append("<h2>Later</h2><p>Action log: <span id=\"log\">-</span></p>");
        body.append(stockNote());
        return page("Demo bug tracker - " + issue.id, body.toString(), LIST_SCRIPT + DETAIL_SCRIPT);
    }

    /**
     * @param user   signed-in user, may be empty
     * @param draft  what was typed before (a refusal), or {@code null} for a plain new report
     * @param notice inner HTML of the message, may be empty
     * @return the page with the report form for a new report
     */
    String editPage(String user, Issue draft, String notice) {
        StringBuilder body = new StringBuilder(4096);
        body.append(header(user)).append(notice(notice));
        body.append("<h2>New report</h2>");
        body.append(reportForm(user, draft));
        body.append("<p>Action log: <span id=\"log\">-</span></p>");
        body.append(stockNote());
        return page("Demo bug tracker - New report", body.toString(), LIST_SCRIPT);
    }

    /**
     * @param user signed-in user, may be empty
     * @param id   what was asked for
     * @return the answer to an unknown report ID
     */
    String unknownReport(String user, String id) {
        return page("Demo bug tracker - Report not found",
                header(user) + "<p class=\"notice\">There is no report with the ID <code>"
                        + esc(id) + "</code>. <a href=\"/browserdemo/reports\">to the list</a>"
                        + " · <a href=\"/browserdemo/new\">New report</a></p>" + stockNote(), null);
    }

    // ------------------------------------------------------------------ parts

    private static String header(String user) {
        return "<h1>Demo bug tracker <span class=\"hint\">demo target browser_proxy_tool</span></h1>"
                + "<nav class=\"top\">" + (user == null || user.isBlank()
                        ? "not signed in - comments are drawn as <code>guest</code>"
                        : "signed in as <b>" + esc(user) + "</b> · <a href=\"/browserdemo/logout\">"
                          + "sign out</a>")
                + " · <a href=\"/browserdemo/\">sign in</a>"
                + " · <a href=\"/browserdemo/reports\">reports</a>"
                + " · <a href=\"/browserdemo/new\">new report</a></nav>";
    }

    private static String notice(String innerHtml) {
        return innerHtml == null || innerHtml.isBlank() ? ""
                : "<p class=\"notice\">" + innerHtml + "</p>";
    }

    /**
     * A plain message from the application, as it appears above the page.
     *
     * @param text    what the application has to say
     * @param failure true renders it as a refusal
     * @return inner HTML of the notice
     */
    private static String message(String text, boolean failure) {
        return failure
                ? "<span class=\"hint\">Refused:</span> " + esc(text)
                : esc(text);
    }

    private static String searchForm(Filters filters) {
        StringBuilder options = new StringBuilder("<option value=\"\">all</option>");
        for (String state : STATES) {
            options.append("<option value=\"").append(esc(state)).append('"')
                    .append(state.equals(filters.state()) ? " selected" : "").append('>')
                    .append(esc(state)).append("</option>");
        }
        return ("""
                <form id="search" method="get" action="/browserdemo/reports">
                  <fieldset><legend>Search</legend>
                    <div><label for="q">Search text</label>
                         <input id="q" name="q" type="text" size="30" value="%1$s"
                                placeholder="title, summary, comment"></div>
                    <div><label for="application">Application</label>
                         <input id="application" name="application" list="applications" value="%2$s"
                                placeholder="type for autocompletion"></div>
                    <datalist id="applications"></datalist>
                    <div><label for="state">Status</label><select id="state" name="state">%3$s</select></div>
                    <div><label for="since">created since</label>
                         <input id="since" name="since" type="date" value="%4$s"></div>
                    <div><label for="olderThanDays">only older than (days)</label>
                         <input id="olderThanDays" name="olderThanDays" type="number" min="0" step="1"
                                value="%5$s"></div>
                    <div><label>only reports with comments
                         <input type="checkbox" name="withComments" value="true"%6$s></label></div>
                    <div><button type="submit" name="action" value="search">Search</button>
                         <a href="/browserdemo/reports">all reports</a></div>
                  </fieldset>
                </form>
                """).formatted(esc(filters.q()), esc(filters.application()), options.toString(),
                esc(filters.since()), esc(filters.olderThanDays()),
                filters.withComments() ? " checked" : "");
    }

    private static String reportTable(List<Issue> issues) {
        StringBuilder out = new StringBuilder("""
                <table id="reports">
                  <thead><tr><th>ID</th><th>Title</th><th>Status</th><th>Application</th>
                  <th>Created</th><th>Comments</th></tr></thead>
                  <tbody>
                """);
        if (issues.isEmpty()) {
            out.append("<tr><td colspan=\"6\" class=\"muted\">no hits</td></tr>");
        }
        for (Issue issue : issues) {
            out.append("<tr><th scope=\"row\"><a href=\"/browserdemo/report?id=").append(esc(issue.id))
                    .append("\">").append(esc(issue.id)).append("</a></th><td>")
                    .append(esc(issue.title)).append("</td><td class=\"").append(stateClass(issue.state))
                    .append("\">").append(esc(issue.state)).append("</td><td>")
                    .append(esc(issue.application)).append("</td><td>")
                    .append(esc(printable(issue.created))).append("</td><td>")
                    .append(issue.comments.size()).append("</td></tr>");
        }
        return out.append("</tbody></table>").toString();
    }

    private static String commentTable(Issue issue) {
        StringBuilder out = new StringBuilder("""
                <table id="comments">
                  <thead><tr><th>No.</th><th>Date</th><th>User</th><th>Comment</th></tr></thead>
                  <tbody>
                """);
        if (issue.comments.isEmpty()) {
            out.append("<tr><td colspan=\"4\" class=\"muted\">no comment yet</td></tr>");
        }
        int number = 1;
        for (Comment comment : issue.comments) {
            out.append("<tr><th scope=\"row\">").append(number++).append("</th><td>")
                    .append(esc(printable(comment.date()))).append("</td><td>")
                    .append(esc(comment.userid())).append("</td><td>")
                    .append(esc(comment.comment())).append("</td></tr>");
        }
        return out.append("</tbody></table>").toString();
    }

    /**
     * The editor: one form for a new and for an existing report. The differences are the ID (generated
     * here, carried in a hidden field there), the creation date and which states the workflow leaves
     * open.
     *
     * @param user  who signs the change
     * @param draft an existing report (or what was typed before a refusal), {@code null} for a new one
     * @return HTML
     */
    private static String reportForm(String user, Issue draft) {
        boolean isNew = draft == null || draft.id.isEmpty();
        Issue shown = draft == null ? new Issue() : draft;
        String idField = isNew
                ? "<input id=\"id\" type=\"text\" value=\"generated when saving\" disabled>"
                : "<input type=\"hidden\" name=\"id\" value=\"" + esc(shown.id) + "\">"
                  + "<input id=\"id\" type=\"text\" value=\"" + esc(shown.id) + "\" readonly>";
        return ("""
                <form id="reportForm" method="post" action="/browserdemo/save">
                  <fieldset><legend>Report</legend>
                    <div><label for="id">Report ID (generated)</label>%1$s</div>
                    <div><label for="created">Created on (generated)</label>
                         <input id="created" type="text" value="%2$s" disabled></div>
                    <div><label for="title">Title</label>
                         <input id="title" name="title" type="text" required maxlength="120" value="%3$s"></div>
                    <div><label for="application">Application</label>
                         <input id="application" name="application" list="applications" required value="%4$s"
                                placeholder="type first, then pick"></div>
                    <datalist id="applications"></datalist>
                    <div><label for="state">Status</label><select id="state" name="state">%5$s</select>
                         <span class="hint">the status follows the workflow</span></div>
                    <div><label for="summary">Summary</label><br>
                         <textarea id="summary" name="summary" rows="4" cols="60">%6$s</textarea></div>
                    <div><label for="reference">Reference (calculated from the title, but writable)</label>
                         <input id="reference" name="reference" type="text" value=""
                                placeholder="the page fills this in"></div>
                    <label for="note">Note (free text, contenteditable)</label>
                    <div id="note" name="note" contenteditable="true"
                         data-placeholder="whatever stands here becomes a comment"
                         style="border:1px solid #dadce0;min-height:2rem;padding:.3rem"></div>
                    <input type="hidden" id="noteText" name="noteText" value="">
                    <input type="hidden" name="userid" value="%7$s">
                    <div><button type="submit" name="action" value="save">Save</button>
                         <a href="/browserdemo/reports">to the list</a></div>
                  </fieldset>
                </form>
                """).formatted(idField, isNew ? "-" : esc(printable(shown.created)), esc(shown.title),
                esc(shown.application), stateOptions(isNew ? null : shown), esc(shown.summary),
                esc(user.isBlank() ? "guest" : user));
    }

    /**
     * @param issue the report being edited, {@code null} for a new one
     * @return the {@code <option>} list the workflow allows - the reason a wrong state has to be
     *         refused by the server as well: a hidden field or a hand-typed query gets here anyway
     */
    private static String stateOptions(Issue issue) {
        List<String> allowed = new ArrayList<>();
        if (issue == null || issue.state.isBlank()) {
            allowed.add(STATE_OPEN);
        } else {
            allowed.add(issue.state);
            String next = nextState(issue.state);
            if (next != null) {
                allowed.add(next);
            }
        }
        StringBuilder out = new StringBuilder();
        for (String state : allowed) {
            out.append("<option value=\"").append(esc(state)).append("\">").append(esc(state))
                    .append("</option>");
        }
        return out.toString();
    }

    private static String commentForm(String user, String id) {
        return ("""
                <form id="commentForm" method="post" action="/browserdemo/comment">
                  <fieldset><legend>Add a comment</legend>
                    <input type="hidden" name="id" value="%1$s">
                    <div><label for="userid">User</label><input id="userid" name="userid" value="%2$s"></div>
                    <div><label for="comment">Comment</label><br>
                         <textarea id="comment" name="comment" rows="3" cols="60" required></textarea></div>
                    <div><button type="button" id="addRow">add comment row</button>
                         <button type="submit" name="action" value="comment">Save comment</button></div>
                  </fieldset>
                </form>
                """).formatted(esc(id), esc(user.isBlank() ? "guest" : user));
    }

    private static String stateForm(Issue issue) {
        String next = nextState(issue.state);
        if (next == null) {
            return "<p class=\"muted\">No further step: <code>" + esc(issue.state)
                    + "</code> is the end of the workflow.</p>";
        }
        return ("""
                <form id="workflow" method="post" action="/browserdemo/state">
                  <fieldset><legend>Further in the workflow</legend>
                    <input type="hidden" name="id" value="%1$s">
                    <input type="hidden" name="next" value="%2$s">
                    <button type="submit" name="action" value="next">%3$s</button>
                  </fieldset>
                </form>
                """).formatted(esc(issue.id), esc(next), esc(stepLabel(next)));
    }

    private static String frameFieldset() {
        return """
                <fieldset><legend>Frame: test environment</legend>
                  <iframe id="frame" src="/browserdemo/frame.html" width="100%" height="140"
                          title="test environment"></iframe>
                </fieldset>
                """;
    }

    private static String stockNote() {
        return "<p class=\"muted\">Reports of this application: <code>" + esc(STORE.fileName()) + "</code>"
                + (STORE.lastError() == null ? "" : " · <span class=\"hint\">writing failed: "
                  + esc(STORE.lastError()) + "</span>") + "</p>";
    }

    private static String workflowLine(String state) {
        StringBuilder out = new StringBuilder();
        for (String candidate : STATES) {
            out.append(out.isEmpty() ? "" : " → ");
            out.append(candidate.equals(state)
                    ? "<b class=\"" + stateClass(candidate) + "\">" + esc(candidate) + "</b>"
                    : "<span class=\"muted\">" + esc(candidate) + "</span>");
        }
        return out.toString();
    }

    private static String stepLabel(String next) {
        return STATE_IN_PROGRESS.equals(next) ? "take in progress"
                : STATE_FIXED.equals(next) ? "close as fixed" : next;
    }

    private static String stateClass(String state) {
        return switch (state == null ? "" : state) {
            case STATE_IN_PROGRESS -> "state-progress";
            case STATE_FIXED -> "state-fixed";
            default -> "state-open";
        };
    }

    private static String page(String title, String body, String script) {
        return "<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\"><title>" + esc(title)
                + "</title>" + STYLE + "</head><body>" + body
                + (script == null || script.isBlank() ? "" : "<script>\n" + script + "\n</script>")
                + "</body></html>";
    }

    private static final String STYLE = """
            <style>
              body{font:14px/1.45 system-ui,sans-serif;margin:1.5rem;color:#202124;max-width:56rem}
              fieldset{border:1px solid #dadce0;margin:0 0 1rem;padding:.8rem}
              legend{font-weight:600;color:#5f6368}
              label{display:inline-block;min-width:11rem}
              input,select,textarea{font:inherit;padding:.2rem .35rem;margin:.15rem 0}
              table{border-collapse:collapse}
              th,td{border:1px solid #dadce0;padding:.25rem .4rem;text-align:left;vertical-align:top}
              thead th{background:#f6f7f9}
              #log{font:12px ui-monospace,monospace;color:#5f6368;white-space:pre-wrap}
              .hint{color:#b3261e;font-size:.85rem}
              .muted{color:#5f6368;font-size:.85rem}
              .notice{background:#e6f4ea;border:1px solid #b7dfc0;padding:.4rem .6rem}
              .flow{font:12px ui-monospace,monospace;color:#5f6368}
              .state-open{color:#b3261e}.state-progress{color:#8a6d00}.state-fixed{color:#137333}
              pre{background:#f6f7f9;border:1px solid #e3e5e8;padding:.4rem;overflow:auto}
              nav.top{margin:0 0 1rem;color:#5f6368}
              [contenteditable]:empty:before{content:attr(data-placeholder);color:#9aa0a6}
            </style>
            """;

    /**
     * The sign-in page's own script: the excursion runs to another host and carries only what stands in
     * <em>its</em> form, so the user name that was typed into the other form has to be copied over by
     * hand - the same idiom as the {@code contenteditable} note of the report editor, and the reason a
     * late DOM change has to be waited for before a snapshot is trusted.
     *
     * <p>No script, no user name: the IdP then signs in a <code>guest</code>, which is visible on the
     * list page instead of being silently right. The password is deliberately not carried - it stays on
     * this origin, as it would with a real IdP.</p>
     */
    private static final String SIGNIN_SCRIPT = """
            const twofaForm = document.getElementById('twofa');
            if (twofaForm) twofaForm.addEventListener('submit', () => {
              const typed = document.getElementById('user');
              const carried = document.getElementById('twofaUser');
              if (typed && carried) carried.value = typed.value.trim();
            });
            """;

    /** Every page with a form: the autocompletion over a root-absolute path, the note, the log. */
    private static final String LIST_SCRIPT = """
            // Root-absolute on purpose, so that the bridge's fetch patch is exercised.
            function log(text) {
              const feld = document.getElementById('log');
              if (feld) feld.textContent = text + ' at ' + new Date().toLocaleTimeString('en-GB');
            }
            function applications(fieldId, listId) {
              const field = document.getElementById(fieldId);
              const list = document.getElementById(listId);
              if (!field || !list) return;
              field.addEventListener('input', async (event) => {
                const answer = await fetch('/browserdemo/api/applications?q='
                    + encodeURIComponent(event.target.value));
                const data = await answer.json();
                list.replaceChildren();
                data.forEach(application => {
                  const option = document.createElement('option');
                  option.value = application.name;
                  option.label = application.name + ' (' + application.open + ' open)';
                  list.append(option);
                });
                log('Autocomplete: ' + data.length + ' applications');
              });
            }
            applications('application', 'applications');
            const counters = document.getElementById('loadCount');
            if (counters) counters.addEventListener('click', async () => {
              const answer = await fetch('/browserdemo/api/open');
              const data = await answer.json();
              document.getElementById('openCount').value = data.open + ' of ' + data.total + ' open';
              log('Counters loaded: ' + data.open + ' open, stock ' + data.file);
            });
            // A field the page calculates out of another one, and it stays writable. That is the case
            // M2 exists for: openCount is disabled (M1 answers element-locked), this one takes a write
            // and hands back its own value a moment later - only the value pass around the write sees
            // that, and only a computed slot can warn about it (docs/browser.md §7a).
            const titleField = document.getElementById('title');
            const reference = document.getElementById('reference');
            if (titleField && reference) titleField.addEventListener('input', () => {
              reference.value = (titleField.value || '').trim().toUpperCase()
                  .replace(/\\s+/g, '-').substring(0, 24);
            });
            const form = document.getElementById('reportForm');
            if (form) form.addEventListener('submit', () => {
              // A contenteditable field is not part of the form data, so the page writes it into a
              // hidden field - a late DOM change of its own, and the reason the note really arrives.
              const value = document.getElementById('note').innerText;
              document.getElementById('noteText').value = value;
              log('Note taken over: ' + value.length + ' characters');
            });
            """;

    /** Only on the report page: the extra row of the comment grid and the report's own JSON. */
    private static final String DETAIL_SCRIPT = """
            const row = document.getElementById('addRow');
            if (row) row.addEventListener('click', () => {
              const blank = document.createElement('tr');
              blank.innerHTML = '<th scope="row"></th><td></td><td></td><td></td>';
              document.querySelector('#comments tbody').append(blank);
              log('Row inserted');
            });
            const raw = document.getElementById('rawJson');
            if (raw) raw.addEventListener('click', async () => {
              const answer = await fetch('/browserdemo/api/report?id='
                  + encodeURIComponent(raw.dataset.id));
              const data = await answer.json();
              document.getElementById('rawdata').textContent = JSON.stringify(data, null, 2);
              log('Raw data loaded: ' + raw.dataset.id + ' (HTTP ' + answer.status + ')');
            });
            """;

    // ========================================================================
    // Odds and ends
    // ========================================================================

    /**
     * @param state current state
     * @return the one state the workflow allows from here, {@code null} at the end of the line
     */
    static String nextState(String state) {
        return switch (state == null ? "" : state) {
            case STATE_OPEN -> STATE_IN_PROGRESS;
            case STATE_IN_PROGRESS -> STATE_FIXED;
            default -> null;
        };
    }

    private static String signedInUser(String cookie) {
        if (cookie == null || cookie.isBlank()) {
            return "";
        }
        try {
            return URLDecoder.decode(cookie, StandardCharsets.UTF_8).trim();
        } catch (IllegalArgumentException e) {
            return cookie.trim();
        }
    }

    /**
     * Who signs a change: what the form sent, else who is signed in, else a guest.
     *
     * @param user     the signed-in user, may be empty
     * @param fromForm the {@code userid} field, may be empty
     * @return the id that ends up in the comment
     */
    private static String actor(String user, String fromForm) {
        if (fromForm != null && !fromForm.isBlank()) {
            return fromForm.trim();
        }
        return user == null || user.isBlank() ? "guest" : user.trim();
    }

    private static boolean isYes(String value) {
        String text = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return text.equals("true") || text.equals("on") || text.equals("yes") || text.equals("1");
    }

    /** ISO in the file, readable on the page. */
    private static String printable(String timestamp) {
        return timestamp == null ? "" : timestamp.replace('T', ' ');
    }

    private static String clean(String text, int max) {
        String value = text == null ? "" : text.replace("\r\n", "\n").trim();
        return value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * Brings a submitted state into the shape the workflow uses: no case, no underscores, single
     * spaces, and nothing at all means the beginning of the line. An unknown value is <em>kept</em> -
     * the refusal has to be able to say what was asked for.
     */
    private static String normalizeState(String state) {
        String value = (state == null ? "" : state).trim().replace('_', ' ')
                .replaceAll(" +", " ").toLowerCase(Locale.ROOT);
        return value.isEmpty() ? STATE_OPEN : value;
    }

    /**
     * {@code did-10}, {@code 10} and {@code DID-0010} mean the same report - a tracker does not make a
     * religion out of the padding, and the link the page writes is not always the one a human types.
     *
     * @param id what was typed or linked
     * @return the ID as the file holds it, empty when there was nothing
     */
    static String tidyId(String id) {
        String value = id == null ? "" : id.trim().toUpperCase(Locale.ROOT);
        if (value.isEmpty()) {
            return "";
        }
        String digits = value.startsWith(ID_PREFIX) ? value.substring(ID_PREFIX.length()) : value;
        if (digits.matches("\\d{1,6}")) {
            return String.format(Locale.ROOT, "%s%04d", ID_PREFIX, Integer.parseInt(digits));
        }
        return value;
    }

    private static String esc(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    // ========================================================================
    // Data
    // ========================================================================

    /** The search form of the list page, exactly as it was submitted. */
    record Filters(String q, String application, String state, String since, String olderThanDays,
            boolean withComments) {

        static Filters empty() {
            return new Filters("", "", "", "", "", false);
        }
    }

    /** The outcome of a change: did it happen, what does the application say, which report to show. */
    private record SaveResult(boolean saved, String message, Issue issue) {

        static SaveResult ok(String message, Issue issue) {
            return new SaveResult(true, message, issue);
        }

        static SaveResult fail(String message, Issue draft) {
            return new SaveResult(false, message, draft);
        }
    }

    /** One entry of a report's comment list. */
    private record Comment(String date, String userid, String comment) {

        ObjectNode toJson() {
            return MAPPER.createObjectNode().put("date", date).put("userid", userid)
                    .put("comment", comment);
        }

        static Comment fromJson(JsonNode node) {
            return new Comment(node.path("date").asString(""), node.path("userid").asString(""),
                    node.path("comment").asString(""));
        }
    }

    /**
     * A bug report: the generated ID and creation date, then title, state, application, summary and the
     * list of comments. Mutable on purpose - this is the application's record and not a transport
     * object, and the store keeps one instance per ID.
     */
    private static final class Issue {

        String id = "";
        String created = "";
        String state = STATE_OPEN;
        String title = "";
        String application = "";
        String summary = "";
        final List<Comment> comments = new ArrayList<>();

        /** @return a copy, so that a refused change can render a page without having made the change */
        Issue copy() {
            Issue other = new Issue();
            other.id = id;
            other.created = created;
            other.state = state;
            other.title = title;
            other.application = application;
            other.summary = summary;
            other.comments.addAll(comments);
            return other;
        }

        ObjectNode toJson() {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("id", id).put("created", created).put("title", title).put("state", state)
                    .put("application", application).put("summary", summary);
            ArrayNode list = node.putArray("comments");
            comments.forEach(comment -> list.add(comment.toJson()));
            return node;
        }

        static Issue fromJson(JsonNode node) {
            Issue issue = new Issue();
            issue.id = tidyId(node.path("id").asString(""));
            issue.created = node.path("created").asString("");
            issue.title = node.path("title").asString("");
            issue.state = normalizeState(node.path("state").asString(STATE_OPEN));
            issue.application = node.path("application").asString("");
            issue.summary = node.path("summary").asString("");
            JsonNode list = node.path("comments");
            if (list.isArray()) {
                for (JsonNode comment : list) {
                    issue.comments.add(Comment.fromJson(comment));
                }
            }
            return issue;
        }

        boolean holds(String needle) {
            return title.toLowerCase(Locale.ROOT).contains(needle)
                    || summary.toLowerCase(Locale.ROOT).contains(needle)
                    || application.toLowerCase(Locale.ROOT).contains(needle)
                    || id.toLowerCase(Locale.ROOT).contains(needle)
                    || comments.stream().anyMatch(comment ->
                            comment.comment().toLowerCase(Locale.ROOT).contains(needle)
                                    || comment.userid().toLowerCase(Locale.ROOT).contains(needle));
        }
    }

    /**
     * The reports, held in memory and filed in {@link #STORE_FILE}: one JSON object per line, the whole
     * file rewritten on every change (the class documentation says why that is not an append log).
     */
    private static final class IssueStore {

        private final Map<String, Issue> byId = new LinkedHashMap<>();

        private boolean loaded;
        private String lastError;

        synchronized List<Issue> all() {
            load();
            return List.copyOf(byId.values());
        }

        synchronized Optional<Issue> find(String id) {
            load();
            String key = tidyId(id);
            return key.isEmpty() ? Optional.empty() : Optional.ofNullable(byId.get(key));
        }

        /**
         * @param filters what stands in the search form; every empty criterion is left out
         * @return the reports that match, ordered by ID
         */
        synchronized List<Issue> search(Filters filters) {
            load();
            String needle = filters.q() == null ? "" : filters.q().trim().toLowerCase(Locale.ROOT);
            String application = filters.application() == null ? ""
                    : filters.application().trim().toLowerCase(Locale.ROOT);
            // The empty state of the select is "all" - it must not be read as one of the states.
            String state = filters.state() == null ? "" : filters.state().trim();
            LocalDate since = parseDate(filters.since());
            Integer days = parseNumber(filters.olderThanDays());
            LocalDateTime cutoff = days == null ? null : LocalDateTime.now().minusDays(Math.max(days, 0));
            List<Issue> found = new ArrayList<>();
            for (Issue issue : byId.values()) {
                if (!needle.isEmpty() && !issue.holds(needle)) {
                    continue;
                }
                if (!application.isEmpty()
                        && !issue.application.toLowerCase(Locale.ROOT).contains(application)) {
                    continue;
                }
                if (!state.isEmpty() && !state.equalsIgnoreCase(issue.state)) {
                    continue;
                }
                if (since != null) {
                    LocalDateTime created = parseDateTime(issue.created);
                    if (created == null || created.toLocalDate().isBefore(since)) {
                        continue;
                    }
                }
                if (cutoff != null) {
                    LocalDateTime created = parseDateTime(issue.created);
                    if (created == null || created.isAfter(cutoff)) {
                        continue;
                    }
                }
                if (filters.withComments() && issue.comments.isEmpty()) {
                    continue;
                }
                found.add(issue);
            }
            found.sort(Comparator.comparing(issue -> issue.id));
            return found;
        }

        synchronized Set<String> applicationNames() {
            load();
            Set<String> names = new LinkedHashSet<>(APPLICATION_CATALOG);
            byId.values().forEach(issue -> {
                if (!issue.application.isBlank()) {
                    names.add(issue.application);
                }
            });
            return names;
        }

        synchronized int countOpen(String application) {
            load();
            return (int) byId.values().stream()
                    .filter(issue -> issue.application.equalsIgnoreCase(application))
                    .filter(issue -> !STATE_FIXED.equals(issue.state))
                    .count();
        }

        synchronized int countState(String state) {
            load();
            return (int) byId.values().stream().filter(issue -> issue.state.equals(state)).count();
        }

        String lastError() {
            return lastError;
        }

        String fileName() {
            Path file = storeFile();
            return file == null
                    ? "(in memory only - set -D" + STORE_PROPERTY + " or -DIDE_PROJECT_DIR=<project root>)"
                    : file.toString();
        }

        /**
         * Creates a report or changes an existing one - and refuses what the workflow does not allow.
         *
         * @param rawId       ID of the report to change, empty for a new one
         * @param title       mandatory
         * @param application mandatory
         * @param state       the current state or the next one
         * @param summary     may be empty
         * @return the outcome; on a refusal the issue in it is the draft with what was typed
         */
        synchronized SaveResult upsert(String rawId, String title, String application, String state,
                String summary) {
            load();
            Issue existing = rawId == null || rawId.isBlank() ? null : byId.get(tidyId(rawId));
            Issue draft = existing == null ? new Issue() : existing.copy();
            draft.title = clean(title, 120);
            draft.application = clean(application, 60);
            draft.state = normalizeState(state);
            draft.summary = clean(summary, 4000);
            if (draft.title.isEmpty()) {
                return refuse("Mandatory field 'title' is empty - no title, no report.", draft, existing);
            }
            if (draft.application.isEmpty()) {
                return refuse("Mandatory field 'application' is empty.", draft, existing);
            }
            if (existing == null) {
                if (!STATE_OPEN.equals(draft.state)) {
                    return refuse("A new report starts at 'open', not at '" + draft.state + "'.",
                            draft, existing);
                }
                Issue created = new Issue();
                created.title = draft.title;
                created.application = draft.application;
                created.summary = draft.summary;
                created.state = STATE_OPEN;
                created.id = nextId();
                created.created = LocalDateTime.now().format(TIMESTAMP);
                byId.put(created.id, created);
                persist();
                return SaveResult.ok("Report " + created.id + " created, status: " + STATE_OPEN + ".",
                        created);
            }
            if (!existing.state.equals(draft.state)) {
                String allowed = nextState(existing.state);
                if (allowed == null || !allowed.equals(draft.state)) {
                    return refuse("Status '" + draft.state + "' is not reachable from '" + existing.state
                            + "'. Workflow: " + String.join(" -> ", STATES) + ".", draft, existing);
                }
            }
            existing.title = draft.title;
            existing.application = draft.application;
            existing.summary = draft.summary;
            existing.state = draft.state;
            persist();
            return SaveResult.ok("Report " + existing.id + " changed, status: " + existing.state + ".",
                    existing);
        }

        /**
         * A refusal. The typed values stay, because the form has to show them; the state stays the one
         * that is really stored, because a page that shows the refused state would claim a change that
         * never happened.
         *
         * @param why      what the application has to say about it
         * @param draft    what was typed
         * @param existing the report as it stands, {@code null} while the report is new
         * @return the outcome
         */
        private static SaveResult refuse(String why, Issue draft, Issue existing) {
            if (existing != null) {
                draft.state = existing.state;
            }
            return SaveResult.fail(why, draft);
        }

        /**
         * @param rawId   report ID
         * @param userid  who wrote it
         * @param comment the text
         * @return the outcome, with the report in it either way
         */
        synchronized SaveResult addComment(String rawId, String userid, String comment) {
            load();
            Issue issue = byId.get(tidyId(rawId));
            if (issue == null) {
                return SaveResult.fail("There is no report " + tidyId(rawId) + ".", null);
            }
            String note = clean(comment, 4000);
            if (note.isEmpty()) {
                return SaveResult.fail("Empty comment - nothing changes.", issue);
            }
            String who = clean(userid, 40);
            issue.comments.add(new Comment(LocalDateTime.now().format(TIMESTAMP),
                    who.isEmpty() ? "guest" : who, note));
            persist();
            return SaveResult.ok("Comment " + issue.comments.size() + " of " + issue.id + " saved.",
                    issue);
        }

        /**
         * @param rawId report ID
         * @param next  the asked-for state
         * @return the outcome, with the report in it either way
         */
        synchronized SaveResult transition(String rawId, String next) {
            load();
            Issue issue = byId.get(tidyId(rawId));
            if (issue == null) {
                return SaveResult.fail("There is no report " + tidyId(rawId) + ".", null);
            }
            String allowed = nextState(issue.state);
            if (allowed == null) {
                return SaveResult.fail("Report " + issue.id + " stands at '" + issue.state
                        + "' - the workflow does not continue from there.", issue);
            }
            if (!allowed.equals(normalizeState(next))) {
                return SaveResult.fail("Illegal transition: from '" + issue.state + "' exactly one step"
                        + " leads to '" + allowed + "'.", issue);
            }
            issue.state = allowed;
            persist();
            return SaveResult.ok("Status of " + issue.id + " is now '" + allowed + "'."
                    + (STATE_FIXED.equals(allowed) ? " Report closed." : ""), issue);
        }

        // ----------------------------------------------------------- filing

        private void load() {
            if (loaded) {
                return;
            }
            loaded = true;
            Path file = storeFile();
            if (file == null) {
                seed();
                LOG.warn("browser: demo tracker has no anchored store file - seeded {} report(s) in "
                        + "memory (set -D{} or -DIDE_PROJECT_DIR=<project root>)", byId.size(),
                        STORE_PROPERTY);
                return;
            }
            if (!Files.isReadable(file)) {
                seed();
                LOG.info("browser: the demo tracker has no {} yet - seeded {} report(s) in memory,"
                        + " the file appears with the first save", file, byId.size());
                return;
            }
            int broken = 0;
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    try {
                        Issue issue = Issue.fromJson(MAPPER.readTree(line));
                        if (issue.id.isEmpty()) {
                            broken++;
                        } else {
                            byId.put(issue.id, issue);
                        }
                    } catch (Exception unusable) {
                        // One broken line is not a reason for an application to stand still.
                        broken++;
                    }
                }
            } catch (IOException e) {
                lastError = e.getMessage();
                LOG.warn("browser: the demo tracker could not read {}: {}", file, e.toString());
            }
            if (broken > 0) {
                LOG.warn("browser: {} line(s) in {} are not a report and were dropped", broken, file);
            }
            if (byId.isEmpty()) {
                seed();
            }
        }

        private void persist() {
            Path file = storeFile();
            if (file == null) {
                // Degraded, not broken: the change lives in memory and the page names the setting.
                LOG.info("browser: demo tracker keeps this change in memory (no anchored store file)");
                return;
            }
            StringBuilder text = new StringBuilder(4096);
            all().forEach(issue -> text.append(issue.toJson()).append('\n'));
            try {
                Path parent = file.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(tmp, text.toString(), StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException | UnsupportedOperationException roughMove) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
                lastError = null;
                LOG.info("browser: demo tracker wrote {} report(s) to {}", byId.size(), file);
            } catch (IOException e) {
                lastError = e.getMessage();
                LOG.warn("browser: the demo tracker could not write {} ({} bytes): {}", file,
                        text.length(), e.toString());
            }
        }

        private void seed() {
            byId.clear();
            for (Issue issue : seedReports()) {
                byId.put(issue.id, issue);
            }
            lastError = null;
        }

        /**
         * Four reports, one per case the tracker has: two comments and a closed one, a report whose
         * state is in the middle of the workflow, one without a single comment and the newest - which
         * is the one a new ID continues after ({@code DID-0013} is the last seed, so the first report
         * created here is {@code DID-0014}).
         *
         * @return the reports in ID order
         */
        private static List<Issue> seedReports() {
            return List.of(
                    report("DID-0010", "2026-09-21T09:14:00", STATE_FIXED, "web-ui",
                            "Umlauts in the title line come back wrongly decoded",
                            "The form sends the title without a charset in its Content-Type, the browser"
                            + " guesses and turns a ü into a character nobody ordered. The capture shows"
                            + " it, because the capture shows what the application answered.",
                            List.of(new Comment("2026-09-21T10:02:00", DEFAULT_USER,
                                            "Affects the search as well: the value stands in the field"
                                            + " decoded twice afterwards."),
                                    new Comment("2026-09-28T16:20:00", "user2048",
                                            "Fixed - the application delivers charset=utf-8 now. Please"
                                            + " re-check it with an umlaut in the title."))),
                    report("DID-0011", "2026-09-24T15:02:00", STATE_IN_PROGRESS, "web-ui",
                            "Snapshot shows empty fields too early",
                            "The frame fills its fields 400 ms after loading. Without the quiescence"
                            + " detection the snapshot loses the race: three empty fields and no hint"
                            + " that they are about to be full.",
                            List.of(new Comment("2026-09-25T09:31:00", DEFAULT_USER,
                                    "quiescent:false is delivered now whenever the 300 ms of quiet were"
                                    + " not reached."))),
                    report("DID-0012", "2026-10-01T11:41:00", STATE_OPEN, "browser-proxy",
                            "Password field readable in the capture",
                            "The field name stood in the capture, and right next to it the value."
                            + " Masking happens on the way into the capture and not on the way out -"
                            + " otherwise the JSONL corpus would have been leaky for a long time.",
                            List.of()),
                    report("DID-0013", "2026-10-02T08:05:00", STATE_OPEN, "browser-proxy",
                            "Row heading of the table is not recognized",
                            "The first cell of a row is a th. It belongs in the snapshot as rowHead, the"
                            + " column headings as colHead - otherwise a search over the list finds no"
                            + " report any more.",
                            List.of(new Comment("2026-10-02T08:20:00", "user2048",
                                    "Affects the report list and the comment table here at once, which"
                                    + " is why the demo exists for exactly this."))));
        }

        private static Issue report(String id, String created, String state, String application,
                String title, String summary, List<Comment> comments) {
            Issue issue = new Issue();
            issue.id = id;
            issue.created = created;
            issue.state = state;
            issue.application = application;
            issue.title = title;
            issue.summary = summary;
            issue.comments.addAll(comments);
            return issue;
        }

        private String nextId() {
            int highest = 9;
            for (String id : byId.keySet()) {
                if (id.startsWith(ID_PREFIX)) {
                    try {
                        highest = Math.max(highest, Integer.parseInt(id.substring(ID_PREFIX.length())));
                    } catch (NumberFormatException handTyped) {
                        // an ID that is not DID-nnnn simply does not take part in the counting
                    }
                }
            }
            return String.format(Locale.ROOT, "%s%04d", ID_PREFIX, highest + 1);
        }
    }

    private static LocalDateTime parseDateTime(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(text.trim());
        } catch (RuntimeException unparsable) {
            return null;
        }
    }

    private static LocalDate parseDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(text.trim());
        } catch (RuntimeException unparsable) {
            return null;
        }
    }

    private static Integer parseNumber(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(text.trim());
        } catch (NumberFormatException unparsable) {
            return null;
        }
    }

    /**
     * @return the report file: {@link #STORE_FILE} in the project base ({@code IDE_PROJECT_DIR}),
     *         or what {@code -Dbrowser.demo.store} says - {@code null} when neither is available.
     *         Never the working directory: started from {@code $HOME} that would scatter test data
     *         there (docs/browser.md §2, same rule as the session logs).
     */
    static Path storeFile() {
        String configured = System.getProperty(STORE_PROPERTY);
        String wanted = configured == null || configured.isBlank() ? STORE_FILE : configured.trim();
        return BrowserSessions.anchorDataPath(wanted, STORE_PROPERTY).path();
    }
}
