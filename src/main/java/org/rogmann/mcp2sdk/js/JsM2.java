package org.rogmann.mcp2sdk.js;

import org.rogmann.mcp2sdk.m2.M2Resolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only Maven repository (M2) access for the JavaScript sandbox
 * (namespace {@code m2}, core: {@link M2Resolver}).
 *
 * <h3>Public JavaScript API</h3>
 * <ul>
 *   <li>{@code m2.help()} - help text</li>
 *   <li>{@code m2.list(pom[, options])} - artifact list of the compile classpath</li>
 *   <li>{@code m2.classpath(pom)} - array of JAR paths</li>
 *   <li>{@code m2.tree(pom[, {verbose}])} - output of {@code mvn dependency:tree}</li>
 *   <li>{@code m2.findClass(fqcn, pom)} - artifact and entry containing a class</li>
 *   <li>{@code m2.listEntries(handle[, {glob, maxEntries}])} - entry names of a JAR</li>
 *   <li>{@code m2.readEntry(handle, entry[, {maxBytes}])} - entry bytes (Uint8Array)</li>
 *   <li>{@code m2.sources(handle[, pom])} - ensure the -sources.jar is present</li>
 *   <li>{@code m2.clearCache()}</li>
 * </ul>
 *
 * <h3>Design</h3>
 * <p>
 * Everything is <em>read-only</em>: there is no write API at all, JARs are opened with
 * {@link java.util.jar.JarFile}. The single operation that touches the repository with a
 * write is {@code m2.sources()}, which asks Maven to download a sources JAR (disable with
 * {@code tools.m2.allow-sources-download=false}).
 * </p>
 * <p>
 * JAR access takes a <em>handle</em>: either the GAV string {@code groupId:artifactId:version}
 * or the {@code jar} path returned by {@code m2.list} / {@code m2.findClass}. Both are
 * validated to stay inside the configured repository, so the module cannot be steered to
 * arbitrary files on the machine - this is the sandbox boundary in place of giving
 * {@code fs} access to the repository directory.
 * </p>
 * <p>
 * The point of the module is composition: the full artifact list (often hundreds of
 * entries) stays in the script, only the filtered/aggregated result travels back to the
 * caller. Entry bytes can be handed straight to {@code javap.disassemble} or
 * {@code archive.open} style consumers.
 * </p>
 */
public final class JsM2 {

    private JsM2() {
        // Utility class
    }

    /** Default and upper bound of the {@code maxEntries} option of m2.list. */
    private static final int DEFAULT_MAX_LIST_ENTRIES = 200;
    private static final int MAX_LIST_ENTRIES = 2000;

    // ========================================================================
    // Public entry points (polyglot-free; the bridge only adapts values)
    // ========================================================================

    /**
     * Artifact list of the compile classpath of a POM.
     *
     * @param pomPath POM path (sandbox-relative or absolute)
     * @param options optional: {@code contains} (case-insensitive substring of
     *                {@code groupId:artifactId:version}), {@code group} (group id prefix),
     *                {@code maxEntries} (default 200, max 2000), {@code sort}
     *                ({@code gav} default, {@code jar})
     * @return {@code { pom, repository, total, matched, truncated, entries }}
     */
    public static Map<String, Object> list(String pomPath, Map<String, Object> options)
            throws IOException {
        Path pom = resolvePom(pomPath);
        Opts o = new Opts(options, "contains", "group", "maxEntries", "sort");
        String contains = o.string("contains");
        String group = o.string("group");
        int maxEntries = o.intInRange("maxEntries", DEFAULT_MAX_LIST_ENTRIES, 1, MAX_LIST_ENTRIES);
        String sort = o.string("sort");
        if (sort != null && !"gav".equals(sort) && !"jar".equals(sort)) {
            throw new IllegalArgumentException("Option 'sort' must be 'gav' or 'jar' (got '" + sort + "')");
        }

        List<M2Resolver.Artifact> all = M2Resolver.classpath(pom);
        List<Map<String, Object>> matched = new ArrayList<>();
        for (M2Resolver.Artifact a : all) {
            if (group != null && !a.groupId().startsWith(group)) {
                continue;
            }
            if (contains != null && !a.gav().toLowerCase().contains(contains.toLowerCase())) {
                continue;
            }
            matched.add(a.toMap());
        }
        Comparator<Map<String, Object>> by;
        if ("jar".equals(sort)) {
            by = Comparator.comparing(m -> String.valueOf(m.get("jar")));
        } else {
            // Both type arguments must be given explicitly: Java has no partial type
            // argument inference, and the chained thenComparing() has no assignment
            // target type to infer from.
            by = Comparator
                    .<Map<String, Object>, String>comparing(m -> String.valueOf(m.get("groupId")))
                    .thenComparing(m -> String.valueOf(m.get("artifactId")))
                    .thenComparing(m -> String.valueOf(m.get("version")));
        }
        matched.sort(by);

        boolean truncated = matched.size() > maxEntries;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pom", pom.toString());
        result.put("repository", M2Resolver.repository().toString());
        result.put("total", all.size());
        result.put("matched", matched.size());
        result.put("truncated", truncated);
        result.put("entries", truncated ? matched.subList(0, maxEntries) : matched);
        if (truncated) {
            result.put("message", "Showing " + maxEntries + " of " + matched.size()
                    + " matched artifacts; filter with {contains:'..'} or {group:'..'} or raise"
                    + " maxEntries (max " + MAX_LIST_ENTRIES + ")");
        }
        return result;
    }

    /** JAR paths of the compile classpath, in Maven order. */
    public static List<String> classpath(String pomPath) throws IOException {
        List<String> out = new ArrayList<>();
        for (M2Resolver.Artifact a : M2Resolver.classpath(resolvePom(pomPath))) {
            out.add(a.jar().toString());
        }
        return out;
    }

    /**
     * Output of {@code mvn dependency:tree} (the conflict view).
     * @param options optional: {@code verbose} (boolean), {@code maxChars} (default 40000)
     */
    public static Map<String, Object> tree(String pomPath, Map<String, Object> options)
            throws IOException {
        Opts o = new Opts(options, "verbose", "maxChars");
        String text = M2Resolver.tree(resolvePom(pomPath), o.bool("verbose"));
        int maxChars = o.intInRange("maxChars", 40000, 1000, 400000);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("verbose", o.bool("verbose"));
        result.put("truncated", text.length() > maxChars);
        result.put("text", text.length() > maxChars ? text.substring(0, maxChars) : text);
        return result;
    }

    /** Artifact and entry that provide a class on the compile classpath. */
    public static Map<String, Object> findClass(String className, String pomPath)
            throws IOException {
        return M2Resolver.findClass(resolvePom(pomPath), className);
    }

    /**
     * Entry names of a JAR.
     * @param handle {@code groupId:artifactId:version} or a {@code jar} path from m2.list
     */
    public static Map<String, Object> listEntries(String handle, Map<String, Object> options)
            throws IOException {
        Opts o = new Opts(options, "glob", "maxEntries");
        return M2Resolver.listEntries(handle, o.string("glob"),
                o.intInRange("maxEntries", 5000, 1, 50000));
    }

    /** Raw bytes of one JAR entry. */
    public static byte[] readEntry(String handle, String entryName, Map<String, Object> options)
            throws IOException {
        Opts o = new Opts(options, "maxBytes");
        long maxBytes = o.longInRange("maxBytes", M2Resolver.DEFAULT_MAX_ENTRY_BYTES,
                1, 256L * 1024 * 1024);
        return M2Resolver.readEntry(handle, entryName, maxBytes);
    }

    /**
     * Ensures that the {@code -sources.jar} of an artifact is in the repository.
     * The only write operation of the module (see {@code m2.help()}).
     */
    public static Map<String, Object> sources(String handle, String pomPath) throws IOException {
        String hint = pomPath == null || pomPath.isBlank() ? null : resolvePom(pomPath).toString();
        return M2Resolver.fetchSources(handle, hint);
    }

    /** Drops the cached classpath resolutions. */
    public static Map<String, Object> clearCache() {
        M2Resolver.clearCache();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cleared", true);
        return m;
    }

    /** Repository root and current configuration (read-only view). */
    public static Map<String, Object> info() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("repository", M2Resolver.repository().toString());
        m.put("mavenCommand", M2Resolver.getProperty("tools.m2.maven-command", "mvn"));
        m.put("timeoutSeconds", M2Resolver.getProperty("tools.m2.timeout-seconds", "180"));
        m.put("lockWaitSeconds", M2Resolver.getProperty("tools.m2.lock-wait-seconds", "120"));
        m.put("cacheTtlSeconds", M2Resolver.getProperty("tools.m2.cache-ttl-seconds", "300"));
        m.put("sourcesDownloadAllowed", M2Resolver.sourcesDownloadAllowed());
        m.put("repositoryExists", Files.isDirectory(M2Resolver.repository()));
        return m;
    }

    // ========================================================================
    // POM path resolution
    // ========================================================================

    /**
     * Resolves a POM path: absolute paths are used as they are (Maven itself decides),
     * relative paths go through the sandbox path rules of {@code fs}.
     */
    static Path resolvePom(String pomPath) throws IOException {
        if (pomPath == null || pomPath.isBlank()) {
            throw new IOException("pom path is required (e.g. 'pom.xml')");
        }
        Path direct = Path.of(pomPath);
        if (direct.isAbsolute()) {
            if (!Files.isRegularFile(direct)) {
                throw new IOException("pom.xml not found: " + pomPath);
            }
            return direct.toAbsolutePath().normalize();
        }
        Path resolved = JsFileSystem.resolveSafePath(pomPath);
        if (!Files.isRegularFile(resolved)) {
            throw new IOException("pom.xml not found: " + pomPath);
        }
        return resolved;
    }

    // ========================================================================
    // Options helper (strict: unknown option names are rejected)
    // ========================================================================

    /** Parsed option map with strict name checking (unknown option names are rejected). */
    private static final class Opts {
        private final Map<String, Object> raw;

        Opts(Map<String, Object> raw, String... allowed) {
            this.raw = raw == null ? Map.of() : raw;
            for (String key : this.raw.keySet()) {
                boolean ok = false;
                for (String a : allowed) {
                    if (a.equals(key)) {
                        ok = true;
                        break;
                    }
                }
                if (!ok) {
                    throw new IllegalArgumentException("Unknown m2 option '" + key
                            + "'. Valid options here: " + String.join(", ", allowed)
                            + ". See m2.help().");
                }
            }
        }

        String string(String name) {
            Object v = raw.get(name);
            if (v == null) {
                return null;
            }
            if (!(v instanceof String s)) {
                throw new IllegalArgumentException("Option '" + name + "' must be a string");
            }
            return s.isEmpty() ? null : s;
        }

        boolean bool(String name) {
            Object v = raw.get(name);
            if (v == null) {
                return false;
            }
            if (!(v instanceof Boolean b)) {
                throw new IllegalArgumentException("Option '" + name + "' must be a boolean");
            }
            return b;
        }

        int intInRange(String name, int def, int min, int max) {
            return (int) longInRange(name, def, min, max);
        }

        long longInRange(String name, long def, long min, long max) {
            Object v = raw.get(name);
            if (v == null) {
                return def;
            }
            if (!(v instanceof Number n)) {
                throw new IllegalArgumentException("Option '" + name + "' must be a number");
            }
            long l = n.longValue();
            if (l < min || l > max) {
                throw new IllegalArgumentException("Option '" + name + "' must be between "
                        + min + " and " + max + " (got " + l + ")");
            }
            return l;
        }
    }

    // ========================================================================
    // Help
    // ========================================================================

    /** Help text of the module. */
    public static String help() {
        return """
                m2 - read-only access to the local Maven repository (M2)

                The module resolves the classpath of a pom.xml (via mvn
                dependency:build-classpath, cached) and reads JAR files inside the
                repository. It is deliberately read-only: there is no write API. JARs
                are opened read-only and every path is checked to stay inside the
                repository, so 'fs' does not need access to the repository directory.

                Entry points
                  m2.help()
                      this text

                  m2.list(pom[, options])
                      artifacts of the compile classpath. Returns
                      { pom, repository, total, matched, truncated, entries[] }.
                      options: contains (substring of groupId:artifactId:version),
                               group (group id prefix), maxEntries (default 200, max 2000),
                               sort ('gav' default | 'jar')
                      An entry is { groupId, artifactId, version, gav, jar }. Filter and
                      aggregate in the script - a project with hundreds of dependencies
                      does not have to leave the sandbox as a whole.

                  m2.classpath(pom)
                      array of JAR paths in Maven order.

                  m2.tree(pom[, options])
                      { text, verbose, truncated } of 'mvn dependency:tree'.
                      options: verbose (also show the omitted/conflict lines), maxChars
                      (default 40000).

                  m2.findClass(fqcn, pom)
                      { found, artifact, entry } of the first classpath JAR containing the
                      class (a .class or, in sources JARs, a .java entry). Only the
                      classpath is searched, the repository is never walked.

                  m2.listEntries(handle[, options])
                      { jar, entries[], total, truncated } - entry names of a JAR.
                      options: glob ('*' not across '/', '**' anything, '?'), maxEntries
                      (default 5000).

                  m2.readEntry(handle, entry[, options])
                      the entry as Uint8Array (pass it on to javap.disassemble or the
                      crypto.* helpers). options: maxBytes (default 33554432).

                  m2.sources(handle[, pom])
                      ensures that the -sources.jar of the artifact is present and returns
                      { jar, alreadyPresent }. This is the ONLY operation that writes into
                      the repository (mvn dependency:get); disable it with the system
                      property tools.m2.allow-sources-download=false.

                  m2.info()
                      repository root and effective configuration.

                  m2.clearCache()
                      drops the cached classpath resolutions (they also expire by TTL and
                      after a change of the pom.xml).

                Handles
                  A 'handle' addresses a JAR inside the repository: either the coordinate
                  string 'groupId:artifactId:version' or the 'jar' path returned by
                  m2.list / m2.findClass. Both must resolve inside the configured
                  repository; anything else is rejected.

                Maven processes
                  Resolution runs 'mvn' in a separate process. At most one such process
                  runs at a time; a competing call waits up to tools.m2.lock-wait-seconds
                  (default 120) and then reports the PID of the running Maven process, so
                  it can be terminated from a shell.

                Configuration (system properties, read on each call)
                  tools.m2.repository            default ~/.m2/repository
                  tools.m2.maven-command         default 'mvn'
                  tools.m2.timeout-seconds       default 180 (per Maven process)
                  tools.m2.lock-wait-seconds     default 120
                  tools.m2.cache-ttl-seconds     default 300
                  tools.m2.allow-sources-download default true

                Typical use
                  var l = m2.list('pom.xml', { group: 'org.springframework' });
                  var wf = l.entries.find(e => e.artifactId === 'spring-webflux');
                  var c = m2.findClass('org.springframework.web.reactive.function.server'
                          + '.RouterFunctions', 'pom.xml');
                  var bytes = m2.readEntry(c.artifact.gav, c.entry);
                  var j = javap.disassemble(bytes, 'structure', 'wf-structure.txt');
                """;
    }
}
