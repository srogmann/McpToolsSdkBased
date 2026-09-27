package org.rogmann.mcp2sdk.m2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.locks.ReentrantLock;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Core (polyglot-free) resolver for the local Maven repository (M2).
 * <p>
 * Resolves the classpath of a {@code pom.xml} via {@code mvn dependency:build-classpath},
 * caches the result per POM (size + mtime + TTL), and provides <em>read-only</em> access
 * to the JAR files in the repository. There is deliberately no write API: JAR content is
 * opened with {@link JarFile} in read mode only. The single exception is
 * {@link #fetchSources(String, String)}, which asks Maven to download a
 * {@code -sources.jar} into the repository (can be disabled via system property
 * {@code tools.m2.allow-sources-download=false}).
 * </p>
 * <h3>Maven process gate</h3>
 * <p>
 * At most one Maven process is run by this class at a time. A competing caller waits up to
 * {@code tools.m2.lock-wait-seconds} (default 120) and then fails with an error that names
 * the PID of the running Maven process, so the user can kill it from a shell if needed.
 * </p>
 * <h3>Configuration (system properties)</h3>
 * <ul>
 *   <li>{@code tools.m2.repository} - repository root (default {@code ~/.m2/repository})</li>
 *   <li>{@code tools.m2.maven-command} - Maven executable (default {@code mvn})</li>
 *   <li>{@code tools.m2.timeout-seconds} - per-process timeout (default 180)</li>
 *   <li>{@code tools.m2.lock-wait-seconds} - max wait for the Maven gate (default 120)</li>
 *   <li>{@code tools.m2.cache-ttl-seconds} - classpath cache TTL (default 300)</li>
 *   <li>{@code tools.m2.allow-sources-download} - allow fetchSources (default true)</li>
 * </ul>
 */
public final class M2Resolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(M2Resolver.class);

    /** Path to a Maven artifact inside the repository: group/path/artifactId/version/file. */
    private static final Pattern REPO_PATH_PATTERN =
            Pattern.compile("(.+)/([^/]+)/([^/]+)/([^/]+)\\.jar$");

    /** ANSI escape sequences (Maven colours its output even when redirected to a file). */
    private static final Pattern ANSI_PATTERN =
            Pattern.compile("\u001B\\[[0-9;]*m");

    /** Default maximum size of an entry returned by {@link #readEntry}. */
    public static final long DEFAULT_MAX_ENTRY_BYTES = 32L * 1024 * 1024;

    // ---------------------------------------------------------------
    // Configuration
    // ---------------------------------------------------------------

    private M2Resolver() {
        // Static utility
    }

    /** Reads a configuration property (system property, default if unset/blank). */
    public static String getProperty(String key, String defaultValue) {
        String v = System.getProperty(key);
        return v == null || v.isBlank() ? defaultValue : v;
    }

    /** Root of the local M2 repository. */
    public static Path repository() {
        return Path.of(getProperty("tools.m2.repository",
                System.getProperty("user.home") + "/.m2/repository")).toAbsolutePath().normalize();
    }

    private static String mavenCommand() {
        return getProperty("tools.m2.maven-command", "mvn");
    }

    private static long processTimeoutSeconds() {
        return Long.parseLong(getProperty("tools.m2.timeout-seconds", "180"));
    }

    private static long lockWaitSeconds() {
        return Long.parseLong(getProperty("tools.m2.lock-wait-seconds", "120"));
    }

    private static long cacheTtlMillis() {
        return Long.parseLong(getProperty("tools.m2.cache-ttl-seconds", "300")) * 1000L;
    }

    /** True if {@link #fetchSources} may download into the repository. */
    public static boolean sourcesDownloadAllowed() {
        return Boolean.parseBoolean(getProperty("tools.m2.allow-sources-download", "true"));
    }

    // ---------------------------------------------------------------
    // Artifact model
    // ---------------------------------------------------------------

    /**
     * A resolved artifact on the classpath.
     *
     * @param groupId Maven group id
     * @param artifactId Maven artifact id
     * @param version Maven version
     * @param jar absolute path of the JAR file
     */
    public record Artifact(String groupId, String artifactId, String version, Path jar) {

        /** Coordinate string {@code groupId:artifactId:version}. */
        public String gav() {
            return groupId + ":" + artifactId + ":" + version;
        }

        /** Map view for JavaScript (see {@code m2.list}). */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("groupId", groupId);
            m.put("artifactId", artifactId);
            m.put("version", version);
            m.put("gav", gav());
            m.put("jar", jar.toString());
            return m;
        }
    }

    /** One cached classpath resolution of a POM. */
    private record CacheEntry(long pomSize, long pomMtime, long resolvedAtMillis,
                              List<Artifact> artifacts) {
        boolean stillValid(long pomSize, long pomMtime, long now) {
            return pomSize == this.pomSize && pomMtime == this.pomMtime
                    && now - resolvedAtMillis < cacheTtlMillis();
        }
    }

    private static final Map<Path, CacheEntry> CACHE = new ConcurrentHashMap<>();

    // ---------------------------------------------------------------
    // Maven process gate (at most one mvn at a time)
    // ---------------------------------------------------------------

    private static final ReentrantLock MAVEN_GATE = new ReentrantLock(true);
    private static volatile long runningMavenPid = -1;

    /**
     * Runs a Maven command in the directory of {@code pomPath} and returns its merged output.
     * Serialized by {@link #MAVEN_GATE}; on contention the error names the PID of the running
     * Maven process so that the user can terminate it from a shell.
     */
    private static String runMaven(Path pomPath, String... args) throws IOException {
        Path dir = pomPath.getParent();
        return runMavenInDir(dir != null ? dir : Path.of("."), args);
    }

    /** Runs a Maven command in the given working directory (see {@link #runMaven}). */
    private static String runMavenInDir(Path workingDir, String... args) throws IOException {
        boolean locked;
        try {
            locked = MAVEN_GATE.tryLock(lockWaitSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the Maven process gate", e);
        }
        if (!locked) {
            long pid = runningMavenPid;
            throw new IOException("Another Maven process is already running (PID "
                    + (pid > 0 ? pid : "unknown") + "); waited " + lockWaitSeconds()
                    + " seconds. You can terminate it from a shell (kill " + (pid > 0 ? pid : "<pid>")
                    + ") and retry.");
        }
        // Output goes to a temp file, not a pipe: a full 'dependency:tree' can exceed the pipe
        // buffer, which would block the process while this side waits in waitFor() (deadlock).
        Path outFile = Files.createTempFile("mcp-m2-mvn-", ".log");
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(mavenCommand());
            cmd.addAll(List.of(args));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.to(outFile.toFile()));
            pb.directory(workingDir.toFile());
            Process process = pb.start();
            runningMavenPid = process.pid();
            try {
                boolean finished;
                try {
                    finished = process.waitFor(processTimeoutSeconds(), TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                    throw new IOException("Interrupted while waiting for Maven", e);
                }
                if (!finished) {
                    process.destroyForcibly();
                    throw new IOException("Maven command timed out after " + processTimeoutSeconds()
                            + " seconds: " + String.join(" ", cmd));
                }
                String output = Files.readString(outFile, StandardCharsets.UTF_8);
                if (process.exitValue() != 0) {
                    throw new IOException("Maven command failed with exit code " + process.exitValue()
                            + ": " + String.join(" ", cmd) + "\n" + abbreviateTail(output));
                }
                return output;
            } finally {
                runningMavenPid = -1;
            }
        } finally {
            Files.deleteIfExists(outFile);
            MAVEN_GATE.unlock();
        }
    }

    /** Last lines of a failed Maven output, so the real error stays visible. */
    private static String abbreviateTail(String output) {
        String s = output.strip();
        if (s.length() <= 4000) {
            return s;
        }
        return "...(truncated)...\n" + s.substring(s.length() - 4000);
    }

    // ---------------------------------------------------------------
    // Classpath resolution
    // ---------------------------------------------------------------

    /**
     * Resolves the artifact list of a POM (cached).
     * @param pomPath absolute path of the pom.xml
     * @return artifacts on the compile classpath, in Maven order
     */
    public static List<Artifact> classpath(Path pomPath) throws IOException {
        if (!Files.isRegularFile(pomPath)) {
            throw new IOException("pom.xml not found: " + pomPath);
        }
        long size = Files.size(pomPath);
        long mtime = Files.getLastModifiedTime(pomPath).toMillis();
        long now = System.currentTimeMillis();
        CacheEntry cached = CACHE.get(pomPath);
        if (cached != null && cached.stillValid(size, mtime, now)) {
            LOGGER.debug("m2: classpath cache hit for {}", pomPath);
            return cached.artifacts();
        }

        Path tempDir = Files.createTempDirectory("mcp-m2-");
        Path cpFile = tempDir.resolve("cp.txt");
        List<Artifact> artifacts;
        try {
            runMaven(pomPath, "dependency:build-classpath",
                    "-Dmdep.outputFile=" + cpFile, "-q");
            if (!Files.exists(cpFile)) {
                throw new IOException("Maven did not write the classpath file");
            }
            String classpath = Files.readString(cpFile).trim();
            artifacts = parseClasspath(classpath);
        } finally {
            Files.deleteIfExists(cpFile);
            Files.deleteIfExists(tempDir);
        }

        CACHE.put(pomPath, new CacheEntry(size, mtime, System.currentTimeMillis(), artifacts));
        LOGGER.info("m2: resolved {} artifacts for {}", artifacts.size(), pomPath);
        return artifacts;
    }

    /** Parses a path-separator separated classpath into artifacts. */
    private static List<Artifact> parseClasspath(String classpath) {
        List<Artifact> out = new ArrayList<>();
        if (classpath.isEmpty()) {
            return out;
        }
        Path repo = repository();
        for (String entry : classpath.split(File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            Path jar = Path.of(entry).toAbsolutePath().normalize();
            Artifact a = artifactFromJarPath(jar, repo);
            if (a != null) {
                out.add(a);
            } else {
                LOGGER.debug("m2: classpath entry outside repository or unparsable: {}", jar);
            }
        }
        return out;
    }

    /**
     * Builds the artifact coordinates from a JAR path inside the repository, or {@code null}
     * if the path does not follow the repository layout.
     */
    public static Artifact artifactFromJarPath(Path jar, Path repoRoot) {
        if (!Files.isRegularFile(jar)) {
            return null;
        }
        // Containment must be checked explicitly: relativize() happily produces
        // "../elsewhere/..." for paths on the same volume, which the repository-layout
        // regex below would otherwise accept with a ".." groupId.
        if (!jar.startsWith(repoRoot)) {
            return null;
        }
        String rel = repoRoot.relativize(jar).toString().replace(File.separatorChar, '/');
        Matcher m = REPO_PATH_PATTERN.matcher(rel);
        if (!m.matches()) {
            return null;
        }
        String groupId = m.group(1).replace('/', '.');
        String artifactId = m.group(2);
        String version = m.group(3);
        String fileName = m.group(4);
        if (!fileName.startsWith(artifactId + "-" + version)) {
            return null;
        }
        return new Artifact(groupId, artifactId, version, jar);
    }

    // ---------------------------------------------------------------
    // Dependency tree (conflict view)
    // ---------------------------------------------------------------

    /**
     * Runs {@code mvn dependency:tree} and returns its output with the {@code [INFO]} prefixes
     * stripped.
     * @param pomPath absolute pom path
     * @param verbose include the conflict/omitted lines (requires maven-dependency-plugin 3.2+)
     */
    public static String tree(Path pomPath, boolean verbose) throws IOException {
        List<String> args = new ArrayList<>();
        args.add("dependency:tree");
        if (verbose) {
            args.add("-Dverbose");
        }
        String output = runMaven(pomPath, args.toArray(new String[0]));
        StringBuilder sb = new StringBuilder();
        for (String raw : output.split("\n")) {
            // Maven colourizes even when redirected to a file; the escape codes would
            // break the [INFO]-prefix handling below (the line starts with ESC, not '[').
            String s = ANSI_PATTERN.matcher(raw).replaceAll("").stripTrailing();
            if (s.contains("[WARNING]") || s.contains("[ERROR]")) {
                continue; // plugin noise is not part of the tree
            }
            int idx = s.indexOf("[INFO] ");
            if (idx >= 0) {
                s = s.substring(idx + 7);
            } else if (s.startsWith("[")) {
                continue; // other log lines
            }
            if (!s.isBlank()) {
                sb.append(s).append('\n'); // indented tree lines keep their indentation
            }
        }
        return sb.isEmpty() ? output : sb.toString();
    }

    // ---------------------------------------------------------------
    // Handle resolution (GAV or repository path) -> JAR
    // ---------------------------------------------------------------

    /**
     * Resolves an artifact handle to a JAR path inside the repository. Accepted handles:
     * <ul>
     *   <li>{@code groupId:artifactId:version}</li>
     *   <li>a {@code jar} path string previously returned by {@code m2.list} / {@code m2.findClass}
     *       (must stay inside the configured repository and end with {@code .jar})</li>
     * </ul>
     * @param handle the GAV or path
     * @param classifier optional classifier such as {@code sources} (GAV form only), may be null
     * @return the JAR path
     * @throws IOException if the handle is invalid or the JAR does not exist
     */
    public static Path resolveJar(String handle, String classifier) throws IOException {
        if (handle == null || handle.isBlank()) {
            throw new IOException("artifact handle is required (groupId:artifactId:version or jar path)");
        }
        Path repo = repository();
        if (isCoordinateForm(handle)) {
            String[] parts = handle.split(":");
            if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
                throw new IOException("Invalid GAV '" + handle
                        + "' (expected groupId:artifactId:version)");
            }
            String groupPath = parts[0].replace('.', '/');
            String file = parts[1] + "-" + parts[2]
                    + (classifier == null || classifier.isEmpty() ? "" : "-" + classifier) + ".jar";
            Path jar = repo.resolve(groupPath).resolve(parts[1]).resolve(parts[2]).resolve(file)
                    .normalize();
            requireInsideRepository(jar, repo);
            if (!Files.isRegularFile(jar)) {
                throw new IOException("JAR not found: " + jar
                        + ("sources".equals(classifier)
                            ? " (use m2.sources('" + handle + "') to download it)" : ""));
            }
            return jar;
        }
        // Path form: must be inside the repository (containment check is the control).
        Path jar = Path.of(handle).toAbsolutePath().normalize();
        requireInsideRepository(jar, repo);
        if (!jar.getFileName().toString().endsWith(".jar") || !Files.isRegularFile(jar)) {
            throw new IOException("Not a JAR file in the repository: " + jar);
        }
        return jar;
    }

    /**
     * Distinguishes the coordinate form {@code groupId:artifactId:version} from a file path:
     * a path may also contain a colon (Windows drive letter, {@code C:\...}), so the
     * coordinate form is only assumed when the text before the first colon is a plain Java
     * identifier sequence (dots, no slashes/backslashes) and there are exactly three parts.
     */
    static boolean isCoordinateForm(String handle) {
        String h = handle.trim();
        String[] parts = h.split(":");
        if (parts.length != 3) {
            return false;
        }
        String group = parts[0];
        if (group.isEmpty() || group.indexOf('/') >= 0 || group.indexOf('\\') >= 0) {
            return false;
        }
        // groupId: segments of letters/digits/dot/dash, separated by dots (no path syntax)
        return group.matches("[A-Za-z0-9_\\-.]+(\\.[A-Za-z0-9_\\-]+)*");
    }

    private static void requireInsideRepository(Path jar, Path repo) throws IOException {
        if (!jar.startsWith(repo)) {
            throw new IOException("Path is outside the configured Maven repository (" + repo
                    + "): " + jar);
        }
    }

    /** Resolves a JAR and, if it is a plain JAR, returns the artifact coordinates (may be null). */
    public static Artifact resolveArtifact(String handle, String classifier) throws IOException {
        Path jar = resolveJar(handle, classifier);
        return artifactFromJarPath(jar, repository());
    }

    // ---------------------------------------------------------------
    // Read-only entry access
    // ---------------------------------------------------------------

    /**
     * Reads one entry from a JAR (read-only - {@link JarFile} is never opened for writing).
     * @param handle GAV or repository JAR path (see {@link #resolveJar})
     * @param entryName entry path inside the JAR, e.g. {@code com/acme/Foo.class}
     * @param maxBytes maximum entry size, values &lt;= 0 use {@link #DEFAULT_MAX_ENTRY_BYTES}
     * @return the entry bytes
     */
    public static byte[] readEntry(String handle, String entryName, long maxBytes) throws IOException {
        Path jar = resolveJar(handle, null);
        String name = normalizeEntryName(entryName);
        long limit = maxBytes > 0 ? maxBytes : DEFAULT_MAX_ENTRY_BYTES;
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            JarEntry entry = jarFile.getJarEntry(name);
            if (entry == null) {
                throw new IOException("Entry not found in " + jar.getFileName() + ": " + name
                        + " (use m2.listEntries to list entries)");
            }
            long declared = entry.getSize();
            if (declared > limit) {
                throw new IOException("Entry " + name + " is too large (" + declared
                        + " bytes > limit " + limit + ")");
            }
            try (InputStream in = jarFile.getInputStream(entry)) {
                byte[] data = in.readNBytes((int) Math.min(limit + 1, Integer.MAX_VALUE - 8L));
                if (data.length > limit) {
                    throw new IOException("Entry " + name + " is larger than the limit of " + limit
                            + " bytes");
                }
                return data;
            }
        }
    }

    /**
     * Lists the entry names of a JAR, optionally filtered by a glob, sorted.
     * @param maxEntries maximum number of names, values &lt;= 0 default to 5000
     * @return map with {@code entries} (sorted, truncated view), {@code total}, {@code truncated}
     */
    public static Map<String, Object> listEntries(String handle, String glob, int maxEntries)
            throws IOException {
        Path jar = resolveJar(handle, null);
        int limit = maxEntries > 0 ? maxEntries : 5000;
        List<String> all = new ArrayList<>();
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            var e = jarFile.entries();
            while (e.hasMoreElements()) {
                String name = e.nextElement().getName();
                if (glob == null || glob.isEmpty() || globMatches(glob, name)) {
                    all.add(name);
                }
            }
        }
        all.sort(Comparator.naturalOrder());
        boolean truncated = all.size() > limit;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jar", jar.toString());
        result.put("entries", truncated ? all.subList(0, limit) : all);
        result.put("total", all.size());
        result.put("truncated", truncated);
        return result;
    }

    /** Simple glob for entry names: {@code *} (no slash), {@code **} (anything), {@code ?}. */
    static boolean globMatches(String glob, String name) {
        StringBuilder re = new StringBuilder(glob.length() * 2 + 4);
        int n = glob.length();
        for (int i = 0; i < n; i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> {
                    if (i + 1 < n && glob.charAt(i + 1) == '*') {
                        re.append(".*");
                        i++;
                        if (i + 1 < n && glob.charAt(i + 1) == '/') {
                            i++; // consume the slash after **
                        }
                    } else {
                        re.append("[^/]*");
                    }
                }
                case '?' -> re.append("[^/]");
                default -> re.append(".+^${}()|[]?\\/*".indexOf(c) >= 0 ? "\\" + c : String.valueOf(c));
            }
        }
        return Pattern.compile(re.toString()).matcher(name).matches();
    }

    /** Validates an entry name: no absolute path, no traversal, no backslash. */
    static String normalizeEntryName(String entryName) throws IOException {
        if (entryName == null || entryName.isBlank()) {
            throw new IOException("entry name is required (e.g. 'com/example/Foo.class')");
        }
        String s = entryName.replace('\\', '/');
        if (s.startsWith("/") || s.contains("../") || s.equals("..") || s.contains("//")) {
            throw new IOException("Invalid entry name (must be a plain relative path inside the JAR): "
                    + entryName);
        }
        return s;
    }

    // ---------------------------------------------------------------
    // Class lookup on the classpath
    // ---------------------------------------------------------------

    /**
     * Finds the first classpath JAR containing a class (binary {@code .class} or
     * {@code .java} entry). Only the JARs of the resolved classpath are searched - the
     * repository is never walked.
     * @return map with {@code found}, and for a hit {@code artifact}, {@code jar}, {@code entry}
     */
    public static Map<String, Object> findClass(Path pomPath, String className) throws IOException {
        if (className == null || className.isBlank()) {
            throw new IOException("class name is required (fully qualified, e.g. java.util.List)");
        }
        String base = className.replace('.', '/');
        String classEntry = base + ".class";
        String sourceEntry = base + ".java";
        for (Artifact a : classpath(pomPath)) {
            try (JarFile jarFile = new JarFile(a.jar().toFile())) {
                if (jarFile.getJarEntry(classEntry) != null) {
                    return foundResult(a, classEntry);
                }
                if (jarFile.getJarEntry(sourceEntry) != null) {
                    return foundResult(a, sourceEntry);
                }
            } catch (IOException e) {
                LOGGER.debug("m2: unreadable JAR {}", a.jar(), e);
            }
        }
        Map<String, Object> miss = new LinkedHashMap<>();
        miss.put("found", false);
        miss.put("className", className);
        miss.put("message", "Class not found on the compile classpath of " + pomPath);
        return miss;
    }

    private static Map<String, Object> foundResult(Artifact a, String entry) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("found", true);
        m.put("artifact", a.toMap());
        m.put("entry", entry);
        return m;
    }

    // ---------------------------------------------------------------
    // Sources download (the single write path, can be disabled)
    // ---------------------------------------------------------------

    /**
     * Downloads the {@code -sources.jar} of an artifact via {@code mvn dependency:get}
     * (idempotent - a no-op when the JAR already exists). This is the only operation that
     * writes into the repository; disable it with
     * {@code tools.m2.allow-sources-download=false}.
     * @return map with {@code jar} and {@code alreadyPresent}
     */
    public static Map<String, Object> fetchSources(String handle, String pomDirHint)
            throws IOException {
        if (!sourcesDownloadAllowed()) {
            throw new IOException("Sources download is disabled (tools.m2.allow-sources-download=false)");
        }
        Artifact a = resolveArtifact(handle, null);
        if (a == null) {
            throw new IOException("Cannot determine coordinates of " + handle);
        }
        Path sourcesJar = sourcesJarPath(a);
        boolean already = Files.isRegularFile(sourcesJar);
        if (!already) {
            // dependency:get needs no POM; run in the directory of the hint POM (settings.xml
            // context) or in a neutral temp directory.
            Path workDir;
            boolean tempDir = false;
            if (pomDirHint != null && !pomDirHint.isBlank()) {
                Path hint = Path.of(pomDirHint).toAbsolutePath().normalize();
                Path parent = Files.isDirectory(hint) ? hint : hint.getParent();
                workDir = parent != null ? parent : Path.of(".").toAbsolutePath();
            } else {
                workDir = Files.createTempDirectory("mcp-m2-get-");
                tempDir = true;
            }
            try {
                runMavenInDir(workDir, "dependency:get",
                        "-Dartifact=" + a.gav() + ":jar:sources", "-Dtransitive=false", "-q");
            } finally {
                if (tempDir) {
                    Files.deleteIfExists(workDir);
                }
            }
            if (!Files.isRegularFile(sourcesJar)) {
                throw new IOException("Maven reported success but " + sourcesJar + " still does not exist");
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jar", sourcesJar.toString());
        m.put("alreadyPresent", already);
        return m;
    }

    /** Expected repository path of the sources JAR of an artifact. */
    public static Path sourcesJarPath(Artifact a) {
        Path repo = repository();
        return repo.resolve(a.groupId().replace('.', '/'))
                .resolve(a.artifactId())
                .resolve(a.version())
                .resolve(a.artifactId() + "-" + a.version() + "-sources.jar");
    }

    // ---------------------------------------------------------------
    // Cache control
    // ---------------------------------------------------------------

    /** Clears the classpath cache (next call re-runs Maven). */
    public static void clearCache() {
        CACHE.clear();
    }
}
