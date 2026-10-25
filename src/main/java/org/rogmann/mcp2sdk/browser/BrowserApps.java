package org.rogmann.mcp2sdk.browser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The list of applications the proxy is allowed to reach - loaded from {@code browser-apps.json}.
 *
 * <p>Why a file and not configuration in code: the registry entry is the trust decision ("this
 * application is internal and may be operated with the user's session"), and a human makes it,
 * traceably, versionably. A broken entry is no reason to make the service unusable - the faulty entry
 * is dropped and <em>shown</em> ({@link #loadErrors()}), the others stay valid.</p>
 */
public final class BrowserApps {

    private static final Logger LOG = LoggerFactory.getLogger(BrowserApps.class);

    /**
     * Id of the built-in self-test application (see {@link BrowserDemoApp}). Deliberately not
     * {@code "demo"}: that name would be the first candidate for a real template of a line-of-business
     * application in every registry, and then it would no longer be decidable whether the built-in
     * glasses are being tested or an actual application is being operated.
     */
    public static final String DEMO_ID = "browserdemo";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final Map<String, BrowserApp> apps = new LinkedHashMap<>();
    /**
     * True while the entry under {@link #DEMO_ID} is still the built-in one from {@link #defaultDemo()}
     * - the registry is allowed to replace exactly that one without being called a duplication.
     */
    private boolean builtInDemoInPlace;
    private final List<String> loadErrors = new ArrayList<>();
    /**
     * Hints that are no error but demand a decision (see {@link #advices()}).
     */
    private final List<String> advices = new ArrayList<>();
    private final Path source;

    private BrowserApps(Path source) {
        this.source = source;
    }

    /**
     * Loads the registry. A missing file is no error (then only the self-test application is
     * reachable), a present file is read as far as possible.
     *
     * @param file path to {@code browser-apps.json}, may be {@code null} or absent
     * @return the registry that was read, always usable
     */
    public static BrowserApps load(Path file) {
        BrowserApps registry = new BrowserApps(file);
        registry.add(defaultDemo());
        registry.builtInDemoInPlace = true;
        if (file == null) {
            // Not "file not found": there is no location to look at. The distinction matters because
            // the file often does exist - in the project - while the server was started elsewhere.
            registry.loadErrors.add("no registry location: the path is not anchored, set "
                    + "-DIDE_PROJECT_DIR=<project root> or -Dbrowser.apps.path=<absolute path>. "
                    + "Only '" + DEMO_ID + "' is reachable.");
            LOG.warn("browser: registry is not anchored - only {} reachable", DEMO_ID);
            return registry;
        }
        if (!Files.isReadable(file)) {
            registry.loadErrors.add("registry file not found: " + file + " - only '" + DEMO_ID
                    + "' is reachable. Template: browser-apps.json in the project root "
                    + "(IDE_PROJECT_DIR), or point browser.apps.path at it.");
            LOG.warn("browser: no registry file under {} - only {} reachable", file, DEMO_ID);
            return registry;
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            JsonNode root = MAPPER.readTree(text);
            JsonNode list = root.path("apps");
            if (!list.isArray()) {
                registry.loadErrors.add("the array \"apps\" is missing in " + file + ".");
            } else {
                for (JsonNode node : list) {
                    registry.addEntry(node, file);
                }
            }
            LOG.info("browser: {} application(s) registered from {}", registry.apps.size(), file);
        } catch (IOException | RuntimeException e) {
            registry.loadErrors.add("registry " + file + " could not be read: "
                    + e.getMessage());
            LOG.error("browser: registry {} unreadable", file, e);
        }
        registry.advise();
        return registry;
    }

    /**
     * The entry of the built-in target application: a small bug tracker with a sign-in, a list of
     * reports with search, an editor for new and existing reports, comments and the workflow
     * {@code open -> in progress -> fixed} - exactly the cases M0 can be checked on, without having to
     * involve a real line-of-business application.
     *
     * <p>The sign-in is a sensitive path ({@code /login} - the password is posted to it). The second
     * factor lives on the IdP simulation's own listener (docs/browser.md §3a) and is therefore never
     * proxied - no sensitive path of its own, the capture cannot see it anyway. What the entry
     * <em>does</em> carry is the rewrite rule of the 2FA excursion: the application names its return
     * URL ({@code ru}) from what it sees, and without the rule the IdP sends the browser back to the
     * original server instead of the session - the case from the field that the fixture reproduces
     * deliberately, rule off.</p>
     *
     * @return registry entry with a {@code self:} target
     */
    public static BrowserApp defaultDemo() {
        return new BrowserApp(DEMO_ID, "self:" + BrowserDemoApp.PATH + "/", 0,
                "Built-in target application (bug tracker): sign-in, report list, search, editor,"
                        + " comments, workflow, and the 2FA excursion to the IdP simulation on its own"
                        + " listener",
                "form", List.of(BrowserDemoApp.PATH + "/login"),
                List.of(),
                List.of(BrowserDemoApp.PATH + "/save", BrowserDemoApp.PATH + "/comment",
                        BrowserDemoApp.PATH + "/state"),
                List.of(new BrowserRewriteRule(BrowserRewriteRule.TYPE_QUERY_PARAM_URL, "ru",
                        BrowserRewriteRule.FROM_UPSTREAM, BrowserRewriteRule.TO_SESSION,
                        BrowserDemoApp.PATH + "(/login)?/?")));
    }

    private void addEntry(JsonNode node, Path file) {
        String id = node.path("id").asString("");
        if (id.isBlank()) {
            loadErrors.add("entry without \"id\" in " + file);
            return;
        }
        try {
            add(new BrowserApp(id,
                    node.path("upstream").asString(""),
                    node.path("port").asInt(0),
                    node.path("note").asString(""),
                    node.path("auth").asString("unknown"),
                    stringList(node.path("sensitivePaths")),
                    stringList(node.path("sensitiveKeys")),
                    stringList(node.path("irreversiblePaths")),
                    rewriteRules(node.path("rewriteRules"), id)));
        } catch (IllegalArgumentException e) {
            loadErrors.add("entry '" + id + "' dropped: " + e.getMessage());
            LOG.warn("browser: {}", loadErrors.get(loadErrors.size() - 1));
        }
    }

    /**
     * Parses the rewrite rules of an entry (docs/browser.md §3a). A broken rule is dropped with a
     * complaint - the entry stays valid, because one wrong rule must not make the whole application
     * unreachable. An unknown type is the normal case of a registry written for a newer proxy: it is
     * named, not guessed at.
     *
     * @param node the {@code rewriteRules} array of the entry
     * @param id   the application id (for the complaint text)
     * @return the usable rules, in file order
     */
    private List<BrowserRewriteRule> rewriteRules(JsonNode node, String id) {
        if (!node.isArray() || node.isEmpty()) {
            return List.of();
        }
        List<BrowserRewriteRule> out = new ArrayList<>();
        for (JsonNode item : node) {
            BrowserRewriteRule rule = new BrowserRewriteRule(
                    item.path("type").asString(""),
                    item.path("param").asString(""),
                    item.path("from").asString(""),
                    item.path("to").asString(""),
                    item.path("pageFilter").asString(""));
            String problem = rule.validationError();
            if (problem != null) {
                loadErrors.add("rewrite rule of '" + id + "' dropped: " + problem);
                LOG.warn("browser: {}", loadErrors.get(loadErrors.size() - 1));
                continue;
            }
            out.add(rule);
        }
        return List.copyOf(out);
    }

    private void add(BrowserApp app) {
        BrowserApp previous = apps.get(app.id());
        if (previous != null) {
            if (app.id().equals(DEMO_ID) && builtInDemoInPlace) {
                // Not a duplication: the registry replaces our own built-in glasses (§2b - that is how
                // the self-test gets a port of its own). Intended, so it is said once in the log and
                // not shown as a complaint on every start. What it protects comes along: the file
                // names a port, not the routes of the demo, and losing the login suppression or the
                // irreversible routes because someone wrote the entry shorter is the wrong direction.
                builtInDemoInPlace = false;
                app = keepingProtectionOf(app, previous);
                LOG.info("browser: the registry replaces the built-in self-test '{}' (upstream {},"
                        + " port {})", app.id(), app.upstream(), app.port());
            } else {
                // A real duplication in the file: the last entry wins, but it is called out instead of
                // being the first or the last one quietly.
                loadErrors.add("application '" + app.id() + "' entered more than once - the last entry"
                        + " is valid: " + app.upstream());
            }
        }
        if (app.port() > 0) {
            // Two applications on one port would not be an error in the text but a listener quietly
            // taking one application for the other. That belongs before the start, not after it.
            for (BrowserApp other : apps.values()) {
                if (other.port() == app.port() && !other.id().equals(app.id())) {
                    loadErrors.add("port " + app.port() + " is occupied twice (" + other.id() + " and "
                            + app.id() + ") - '" + app.id() + "' therefore runs in path mode");
                    apps.put(app.id(), new BrowserApp(app.id(), app.upstream(), 0, app.note(),
                            app.auth(), app.sensitivePaths(), app.sensitiveKeys(),
                            app.irreversiblePaths()));
                    return;
                }
            }
        }
        apps.put(app.id(), app);
    }

    /**
     * What {@code replacing} left open, {@code replaced} already knew: only lists the new entry does
     * not carry on its own are taken over, never the other way round - a registry entry that names its
     * own paths stays the decision it is. The rewrite rules follow the same direction: an entry
     * without rules of its own keeps the demo's rule, an entry with rules decides them.
     *
     * @param replacing the entry from the registry file
     * @param replaced  the entry it replaces (the built-in self-test)
     * @return the entry to register
     */
    private static BrowserApp keepingProtectionOf(BrowserApp replacing, BrowserApp replaced) {
        List<String> sensitivePaths = replacing.sensitivePaths().isEmpty()
                ? replaced.sensitivePaths() : replacing.sensitivePaths();
        List<String> irreversiblePaths = replacing.irreversiblePaths().isEmpty()
                ? replaced.irreversiblePaths() : replacing.irreversiblePaths();
        List<BrowserRewriteRule> rewriteRules = replacing.rewriteRules().isEmpty()
                ? replaced.rewriteRules() : replacing.rewriteRules();
        List<String> sensitiveKeys = new ArrayList<>(replaced.sensitiveKeys());
        replacing.sensitiveKeys().stream().filter(key -> !sensitiveKeys.contains(key))
                .forEach(sensitiveKeys::add);
        return new BrowserApp(replacing.id(), replacing.upstream(), replacing.port(),
                replacing.note(), replacing.auth(), sensitivePaths, sensitiveKeys, irreversiblePaths,
                rewriteRules);
    }

    private static List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                // asString("") returns the replacement for non-text - leaving it out is intended: an
                // object in the place of a path is a registry error, not an entry.
                String value = item.asString("");
                if (!value.isBlank()) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    /**
     * @param id application id (case-insensitive, because humans type it)
     * @return the entry, if registered
     */
    public Optional<BrowserApp> byId(String id) {
        return id == null ? Optional.empty()
                : Optional.ofNullable(apps.get(id.trim().toLowerCase(Locale.ROOT)));
    }

    /**
     * Finds the application belonging to a host name - the question that comes up when rewriting an
     * absolute URL found in the text.
     *
     * @param host host name without port
     * @return the application whose upstream lies on this host
     */
    public Optional<BrowserApp> byHost(String host) {
        if (host == null || host.isBlank()) {
            return Optional.empty();
        }
        String needle = host.toLowerCase(Locale.ROOT);
        return all().stream().filter(app -> needle.equals(app.host())).findFirst();
    }

    /**
     * @return all entries in the order of the file (self-test first)
     */
    public List<BrowserApp> all() {
        return List.copyOf(apps.values());
    }

    /**
     * @return the file that was read (it may be missing)
     */
    public Path source() {
        return source;
    }

    /**
     * Checks the finished registry for constellations that demand a decision. Deliberately one pass
     * <em>after</em> the load and not while inserting: otherwise the built-in self-test (port 0,
     * irreversible test track) would produce a hint on every start that the file has long resolved.
     */
    private void advise() {
        for (BrowserApp app : apps.values()) {
            if (app.port() == 0 && !app.irreversiblePaths().isEmpty()) {
                advices.add("application '" + app.id() + "' runs in path mode (shared origin) and "
                        + "has irreversiblePaths " + app.irreversiblePaths() + ". On a shared "
                        + "origin a script of this application can call /review/{id}/decide itself; "
                        + "the four-eyes approval would then no longer be a boundary between human "
                        + "and machine. For applications that need approvals: set port "
                        + "(docs/browser.md §2b).");
            }
        }
    }

    /**
     * @return the complaints from loading, for the index page and the tool output
     */
    public List<String> loadErrors() {
        return List.copyOf(loadErrors);
    }

    /**
     * Hints about the provenance: what an application could do on the shared origin that it probably
     * should not. Not an error (the configuration works), but a decision a human has to make and know
     * about - which is why it shows on the index page and in the log.
     *
     * @return the hints, in registry order
     */
    public List<String> advices() {
        return List.copyOf(advices);
    }
}
