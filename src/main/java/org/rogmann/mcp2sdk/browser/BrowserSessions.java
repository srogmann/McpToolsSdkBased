package org.rogmann.mcp2sdk.browser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The administration behind the proxy: registry, sessions, settings.
 *
 * <p>Singleton, not a Spring bean - for the same reason {@code ReviewStore} is one: the writers are
 * mixed (the MCP {@link BrowserTool} runs without a Spring context, the
 * {@link BrowserProxyController} with one), and a single truth about "which sessions exist" is exactly
 * what two instances would break.</p>
 *
 * <p>Settings come from system properties ({@code -Dbrowser.*}) and not from
 * {@code application.yml}, because the tool object needs them as well and is not an
 * {@code @Value}-injection participant. The property names are listed in {@link Settings}.</p>
 */
public final class BrowserSessions {

    private static final Logger LOG = LoggerFactory.getLogger(BrowserSessions.class);

    private static final BrowserSessions INSTANCE = new BrowserSessions();

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ConcurrentMap<String, BrowserSession> sessions = new ConcurrentHashMap<>();
    /** Application id -> session of the matching port (port mode: one session per application). */
    private final ConcurrentMap<String, String> bound = new ConcurrentHashMap<>();
    private final AtomicInteger sessionSeq = new AtomicInteger();
    /**
     * Application id -&gt; field model (M2). Built on first use: most sessions never ask for one, and
     * building one means reading a file.
     */
    private final ConcurrentMap<String, BrowserFieldModel> fieldModels = new ConcurrentHashMap<>();

    private final Settings settings;
    private volatile BrowserApps apps;
    private volatile int localPort;
    /**
     * Absolute base the session logs hang under ({@code <project>/runs/browser}), or {@code null}
     * when it could not be anchored - then nothing is written to disk and {@link #loggingProblem()}
     * says why.
     */
    private final Path runBase;
    private final String loggingProblem;

    private BrowserSessions() {
        this.settings = Settings.fromSystemProperties();
        // Data paths are anchored at the project base like everywhere else in this service
        // (WorkProject, ReviewRequests.resolveInProject) - never at the working directory, which is
        // $HOME when the server is started from there.
        DataPath appsFile = anchorDataPath(settings.appsPath(), "browser.apps.path");
        if (appsFile.problem() != null) {
            LOG.error("browser: {}", appsFile.problem());
        }
        this.apps = BrowserApps.load(appsFile.path());
        DataPath runDir = anchorDataPath(settings.runDir(), "browser.run.dir");
        this.runBase = runDir.path();
        // Nothing to anchor when nothing is meant to be written: browser.persist=false is a decision,
        // not a misconfiguration - only the combination "should write but has no location" is one.
        this.loggingProblem = settings.persist() ? runDir.problem() : null;
        if (this.loggingProblem != null) {
            LOG.error("browser: {}", this.loggingProblem);
        }
        this.localPort = settings.localPort();
        List<String> ids = apps.all().stream().map(BrowserApp::id).toList();
        LOG.info("browser: ready - registry {}, writing {}, bridge {}, applications {}",
                apps.source(),
                settings.writeEnabled() ? "ALLOWED" : "off",
                settings.injectBridge() ? "injected" : "switched off", ids);
        LOG.info("browser: session logs {}", runBase == null
                ? "NOT on disk (in memory only)" : "under " + runBase);
        apps.loadErrors().forEach(problem -> LOG.warn("browser: registry complaint: {}", problem));
        // Decision L9: the review bypass is a development setting and must not be invisible - not at
        // startup, not on the index page, not in the action log of the submits it skips.
        for (String bypassed : settings.withoutReviews()) {
            boolean registered = apps.byId(bypassed).isPresent();
            LOG.warn("browser: review bypass for application '{}' ({}): submits run WITHOUT four-eyes"
                            + " approval - development setting browser.apps.withoutReviews",
                    bypassed, registered ? "registered" : "NOT registered");
        }
        // M2 is a decision, not a side effect: the default is off, and every mode says in the start log
        // what it does to a session (docs/browser.md §7a).
        LOG.info("browser: field model browser.fields.mode={}: {}", settings.fieldsMode(),
                settings.fieldMode().display());
        if (settings.fieldsStrict() && !settings.withoutReviews().isEmpty()) {
            LOG.warn("browser: strict field model together with a review bypass ({}) - the write guard"
                    + " of M2 is built from a handful of observations, and here no human looks at the"
                    + " submit either. Development only.", settings.withoutReviews());
        }
        // The host naming is about cookie isolation, so it belongs in the same log block as the other
        // switches that change what two applications share (docs/browser.md §2b).
        if (settings.perApplicationHost()) {
            LOG.info("browser: applications are reached under their own host name (suffix {}) - one"
                    + " cookie jar and one HSTS entry per application instead of one per machine."
                    + " Names come from the registry ids, and the Host header never chooses an upstream.",
                    settings.hostSuffix());
            // The pure overload, deliberately: inside this constructor BrowserSessions.get() still
            // answers null (INSTANCE is assigned by the very initializer that runs here), and an
            // ExceptionInInitializerError would take the whole module down at startup.
            String advice = BrowserHost.resolverAdvice(settings.hostSuffix(),
                    System.getProperty("os.name", ""));
            if (!advice.isEmpty()) {
                LOG.warn("browser: {}", advice);
            }
        } else {
            LOG.info("browser: applications share the host localhost (browser.host.suffix unset) - so"
                    + " they share one cookie jar: cookies are scoped to the host, not to the port"
                    + " (docs/browser.md §2b). For more than one application on this machine,"
                    + " -Dbrowser.host.suffix=" + BrowserHost.SUGGESTED_SUFFIX + " separates them.");
        }
    }

    /**
     * @return the shared administration
     */
    public static BrowserSessions get() {
        return INSTANCE;
    }

    /**
     * @return the JSON mapper of this administration
     */
    public JsonMapper mapper() {
        return mapper;
    }

    /**
     * @return the settings
     */
    public Settings settings() {
        return settings;
    }

    /**
     * @return why the session logs are not on disk, or {@code null} when they are - the answer to
     *         "where did my capture go" without reading the start log
     */
    public String loggingProblem() {
        return loggingProblem;
    }

    /**
     * @return the absolute base the session logs hang under, or {@code null} when unanchored
     */
    public Path runBase() {
        return runBase;
    }

    /**
     * The outcome of {@link #anchorDataPath}: the absolute path, or the reason there is none.
     *
     * @param path    the anchored path, {@code null} when the location could not be determined
     * @param problem one honest sentence naming the property to set, {@code null} on success
     */
    record DataPath(Path path, String problem) {
    }

    /**
     * Resolves a browser data path the way the rest of this service does: relative values hang in the
     * project base ({@code IDE_PROJECT_DIR}), absolute ones are taken as given.
     *
     * <p>Nothing here ever falls back to the working directory. That fallback was the bug: started
     * from {@code $HOME}, the session logs and the demo store landed there and the registry read
     * &ldquo;file not found&rdquo; although the file lies in the project. A location that cannot be
     * anchored is reported as a problem naming the property - it is never guessed.</p>
     *
     * @param configured the value of the property
     * @param property   property name, for the message
     * @return path or problem, never both {@code null}
     */
    static DataPath anchorDataPath(String configured, String property) {
        if (configured == null || configured.isBlank()) {
            return new DataPath(null, property + " is empty - nothing to write or read; set it to a "
                    + "path inside the project or to an absolute one.");
        }
        Path wanted = Paths.get(configured.trim());
        if (wanted.isAbsolute()) {
            return new DataPath(wanted.normalize(), null);
        }
        String base = System.getProperty("IDE_PROJECT_DIR");
        if (base == null || base.isBlank()) {
            return new DataPath(null, "'" + configured.trim() + "' is relative but no project base is "
                    + "given, and the working directory is not a storage location - start the server "
                    + "with -DIDE_PROJECT_DIR=<project root> or set -D" + property
                    + "=<absolute path>.");
        }
        Path root = Paths.get(base).toAbsolutePath().normalize();
        Path resolved = root.resolve(wanted).normalize();
        if (!resolved.startsWith(root)) {
            return new DataPath(null, "'" + configured.trim() + "' leads outside the project base "
                    + "given by IDE_PROJECT_DIR - refusing to use it.");
        }
        return new DataPath(resolved, null);
    }

    /**
     * @return the loaded registry
     */
    public BrowserApps apps() {
        return apps;
    }

    /**
     * Reads the registry again (after an edit to {@code browser-apps.json}). Existing sessions
     * keep their application - a running browser must not lose the ground under its feet.
     *
     * @return the new registry including its complaints
     */
    public BrowserApps reloadApps() {
        DataPath again = anchorDataPath(settings.appsPath(), "browser.apps.path");
        if (again.problem() != null) {
            LOG.error("browser: {}", again.problem());
        }
        apps = BrowserApps.load(again.path());
        LOG.info("browser: registry reloaded: {} application(s), {} complaint(s)",
                apps.all().size(), apps.loadErrors().size());
        return apps;
    }

    /**
     * @param port the port on which this service is reached (for displayable session URLs)
     */
    public void setLocalPort(int port) {
        if (port > 0) {
            this.localPort = port;
        }
    }

    /**
     * @return the current local port
     */
    public int localPort() {
        return localPort;
    }

    /**
     * Opens a new session for a registered application.
     *
     * @param appId application id
     * @return the session
     * @throws IllegalArgumentException if the application is not registered
     */
    public BrowserSession newSession(String appId) {
        return newSession(appId, false);
    }

    /**
     * Opens the <b>one</b> session of an application port. Here the port <em>is</em> the browsing
     * context (no session section in the URL), so there may be exactly one of them - and {@link #open}
     * has to find it again instead of creating a new one on every click.
     *
     * @param appId application id
     * @return the bound session
     * @throws IllegalArgumentException if the application is not registered
     */
    public BrowserSession newBoundSession(String appId) {
        return newSession(appId, true);
    }

    /**
     * @param appId application id
     * @return the bound session of an application port, if there is one
     */
    public java.util.Optional<BrowserSession> boundSession(String appId) {
        String id = bound.get(appId == null ? "" : appId.toLowerCase(Locale.ROOT));
        return id == null ? Optional.empty() : session(id);
    }

    private BrowserSession newSession(String appId, boolean boundToPort) {
        BrowserApp app = apps.byId(appId)
                .orElseThrow(() -> new IllegalArgumentException("Application '" + appId
                        + "' is not registered in " + apps.source() + " (browser.apps.path, anchored at "
                        + "IDE_PROJECT_DIR). Only registered, internal applications may be operated; "
                        + "action=apps lists what is."));
        String id = "s" + sessionSeq.incrementAndGet() + "-"
                + Integer.toHexString((int) (Math.random() * 0xFFFF));
        // No anchored base -> no disk logging, but the session itself still works (§8: it must run
        // without writes anyway). The reason is surfaced by loggingProblem(), not swallowed.
        BrowserSession session = new BrowserSession(app, id, runBase == null ? null : runBase.resolve(id));
        sessions.put(id, session);
        if (boundToPort) {
            bound.put(app.id(), id);
        }
        LOG.info("browser: session {} opened for {}{} -> {}", id, app.id(),
                boundToPort ? " (port application)" : "", session.entryUrl());
        return session;
    }

    /**
     * @param id session id
     * @return the session, if it is still alive
     */
    public Optional<BrowserSession> session(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(sessions.get(id.trim()));
    }

    /**
     * The session belonging to a requested path - needed because the prefix
     * {@code /browser/app/session/…} contains the id while the servlet API only knows the whole path.
     *
     * @param path the full requested path
     * @return the session that was found
     */
    public Optional<BrowserSession> sessionByPath(String path) {
        String[] parts = path.split("/");
        // ["", "browser", appId, sessionId, ...]
        if (parts.length < 4) {
            return Optional.empty();
        }
        return session(parts[3]);
    }

    /**
     * @return all sessions, newest first
     */
    public List<BrowserSession> sessions() {
        return sessions.values().stream()
                .sorted((a, b) -> b.createdAt().compareTo(a.createdAt()))
                .toList();
    }

    /**
     * Closes a session: on its next poll the bridge gets a stop command and quits.
     *
     * @param id session id
     * @return true if the session existed
     */
    public boolean closeSession(String id) {
        BrowserSession session = sessions.remove(id == null ? "" : id.trim());
        if (session == null) {
            return false;
        }
        bound.values().remove(session.id());
        // The wait loops of in-flight commands read this: a command queued for a bridge that will
        // never poll again is dropped in slices, not after its whole ceiling.
        session.markClosed();
        ObjectNode stop = mapper.createObjectNode();
        stop.put("id", "bye");
        stop.put("t", "stop");
        session.enqueueCommand(stop);
        LOG.info("browser: session {} closed", id);
        return true;
    }

    /**
     * Checks an application id against the registry without opening a session.
     *
     * @param appId id taken from the URL
     * @return the entry
     */
    public Optional<BrowserApp> app(String appId) {
        return apps.byId(appId == null ? "" : appId.toLowerCase(Locale.ROOT));
    }

    /**
     * The field model of one application (M2) - where a field's value comes from. Created on first use
     * and read back from its file, so the knowledge of a test session outlives the session while the
     * raw capture stays where it is.
     *
     * @param appId application id
     * @return the model, never {@code null} (an unanchored storage location makes it memory-only)
     */
    public BrowserFieldModel fieldModel(String appId) {
        String key = appId == null ? "?" : appId.toLowerCase(Locale.ROOT);
        return fieldModels.computeIfAbsent(key, id -> new BrowserFieldModel(id, fieldsFile(id)));
    }

    /**
     * Where the field models of the applications hang (M2).
     *
     * @return the directory, or {@code null} when no data location could be anchored (L10)
     */
    public Path fieldsBase() {
        return runBase == null ? null : runBase.resolve("fields");
    }

    /**
     * Where the field model of an application hangs: under the same anchored base as the session logs
     * (L10), in a directory of its own so that a session directory keeps meaning "one session".
     *
     * @param appId application id (already lowercase)
     * @return the file, or {@code null} when nothing is anchored
     */
    public Path fieldsFile(String appId) {
        Path base = fieldsBase();
        return base == null ? null : base.resolve(appId + ".jsonl");
    }

    /**
     * How far M2 goes in a session (docs/browser.md §7a). Three settings, because the question
     * "should the tool know more about the page?" and the question "should the tool be allowed to hold
     * something back?" are two questions - and only the second one needs its own switch.
     */
    public enum Mode {
        /** no value pass, no annotation, no guard: M1 unchanged (the default) */
        OFF,
        /** knowledge only: the value pass runs, the model grows, nothing is ever held back */
        OBSERVE,
        /** as observe, plus: a write into a computed field needs {@code force:true} on the entry */
        STRICT;

        /**
         * @param value the configured text (case-insensitive, {@code null} for the default)
         * @return the mode, {@link #OFF} for anything unrecognised - a typo must not switch a guard on
         */
        public static Mode of(String value) {
            if (value == null) {
                return OFF;
            }
            for (Mode mode : values()) {
                if (mode.name().equalsIgnoreCase(value.trim())) {
                    return mode;
                }
            }
            return OFF;
        }

        /**
         * @return one line for the start log and the index page: what this mode does to a session
         */
        public String display() {
            switch (this) {
                case OFF:
                    return "off (no value pass - sessions run exactly as in M1)";
                case OBSERVE:
                    return "OBSERVE (value pass and field model recorded, nothing held back)";
                case STRICT:
                    return "STRICT (a write into a computed field needs force:true; the gate for what"
                            + " is allowed stays the review)";
                default:
                    return name().toLowerCase(Locale.ROOT);
            }
        }
    }

    /**
     * The settings from system properties. Every value has a usable default so that
     * "just start it" works.
     *
     * @param enabled            the glasses as a whole on
     * @param injectBridge       inject the bridge script (off = plain pass-through without the LLM view)
     * @param writeEnabled       allow write commands (M0: false)
     * @param relaxCsp           remove {@code script-src} when it blocks the bridge
     * @param dropSecure         remove {@code Secure} from cookies (only needed for HTTP operation)
     * @param pollSeconds        long-poll horizon
     * @param bridgeTimeoutMillis when the bridge counts as disconnected
     * @param requestTimeoutSeconds time for one upstream request
     * @param quiesceMillis      quiet interval of the settle detection
     * @param snapshotBudget     maximum number of elements per snapshot
     * @param captureBytes       body limit in the capture
     * @param maxBodyBytes       body limit when proxying
     * @param appsPath           location of the registry file
     * @param runDir             base for session logs
     * @param persist            write JSONL to disk
     * @param withoutReviews     applications whose submits run without a four-eyes review
     *                           (development bypass, decision L9; {@code browser.apps.withoutReviews})
     * @param fieldsMode         how much of M2 is on ({@code browser.fields.mode}: off, observe,
     *                           strict) - off leaves the write path exactly as M1 built it
     * @param hostSuffix         suffix of the per-application host name ({@code browser.host.suffix},
     *                           e.g. {@code .localhost}); empty means off - and off is the default,
     *                           because the names have to resolve somewhere (see {@link BrowserHost})
     * @param localPort          initial guess for our own port
     */
    public record Settings(boolean enabled, boolean injectBridge, boolean writeEnabled,
            boolean relaxCsp, boolean dropSecure, int pollSeconds, int bridgeTimeoutMillis,
            int requestTimeoutSeconds, int quiesceMillis, int snapshotBudget, int captureBytes,
            int maxBodyBytes, String appsPath, String runDir, boolean persist,
            List<String> withoutReviews, String fieldsMode, String hostSuffix, int localPort) {

        /**
         * @return the settings, read from {@code -Dbrowser.*}
         */
        public static Settings fromSystemProperties() {
            return new Settings(
                    bool("browser.enabled", true),
                    bool("browser.bridge.inject", true),
                    bool("browser.write.enabled", false),
                    bool("browser.rewrite.relaxCsp", true),
                    bool("browser.cookie.dropSecure", true),
                    integer("browser.bridge.pollSeconds", 8),
                    integer("browser.bridge.timeoutMs", 25_000),
                    integer("browser.proxy.timeoutSeconds", 60),
                    integer("browser.snapshot.quiesceMs", 300),
                    integer("browser.snapshot.budget", 1200),
                    integer("browser.capture.bytes", 4000),
                    integer("browser.proxy.maxBodyBytes", 32 * 1024 * 1024),
                    System.getProperty("browser.apps.path", "browser-apps.json"),
                    System.getProperty("browser.run.dir", "runs/browser"),
                    bool("browser.persist", true),
                    stringList("browser.apps.withoutReviews"),
                    System.getProperty("browser.fields.mode", "off"),
                    // Validated here, at the one place that reads the property: a suffix that is not a
                    // domain suffix turns the mode off and says so, instead of producing names that
                    // cannot resolve and a proxy that looks broken.
                    BrowserHost.validated(System.getProperty("browser.host.suffix", "")),
                    integer("browser.local.port", 9091));
        }

        private static List<String> stringList(String name) {
            String value = System.getProperty(name, "");
            if (value == null || value.isBlank()) {
                return List.of();
            }
            return java.util.Arrays.stream(value.split("[,;\\s]+"))
                    .map(String::trim)
                    .filter(item -> !item.isEmpty())
                    .map(item -> item.toLowerCase(Locale.ROOT))
                    .toList();
        }

        /**
         * @param appId application id
         * @return true if submits of this application run without a four-eyes review (development
         *         bypass, decision L9). The master switch {@code browser.write.enabled} is checked
         *         separately and stays a precondition.
         */
        public boolean reviewsBypassed(String appId) {
            return appId != null && withoutReviews.contains(appId.toLowerCase(Locale.ROOT));
        }

        /**
         * @return the M2 setting as a mode - an unknown value is {@link Mode#OFF}, never a guess
         */
        public Mode fieldMode() {
            return Mode.of(fieldsMode);
        }

        /**
         * @return true when the value pass runs and the field model grows (observe or strict)
         */
        public boolean fieldsOn() {
            return fieldMode() != Mode.OFF;
        }

        /**
         * @return true when a write into a computed field has to be asked for by name
         */
        public boolean fieldsStrict() {
            return fieldMode() == Mode.STRICT;
        }

        /**
         * @return true when applications are offered under a host name of their own ({@code *.localhost}
         *         mode) instead of all sharing {@code localhost} - the reason is in {@link BrowserHost}:
         *         cookies are scoped to the host, never to the port
         */
        public boolean perApplicationHost() {
            return !hostSuffix.isEmpty();
        }

        private static boolean bool(String name, boolean fallback) {
            String value = System.getProperty(name);
            return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value.trim());
        }

        private static int integer(String name, int fallback) {
            String value = System.getProperty(name);
            if (value == null || value.isBlank()) {
                return fallback;
            }
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                LOG.warn("browser: {} = '{}' is not a number, using {}", name, value, fallback);
                return fallback;
            }
        }
    }
}
