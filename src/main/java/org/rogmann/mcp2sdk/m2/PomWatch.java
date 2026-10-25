package org.rogmann.mcp2sdk.m2;

import org.rogmann.mcp2sdk.WorkProject;
import org.rogmann.mcp2sdk.util.TextDiff;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Fingerprint of everything a {@code mvn} invocation is going to read, taken before the model
 * could have touched it - the precondition that lets {@code mvn} stay allowed.
 *
 * <h2>Why this class exists</h2>
 * <p>
 * Re-implementing Maven's classpath resolution (parent POMs, {@code dependencyManagement},
 * imported BOMs, property inheritance) is not worth it, so the classpath keeps coming from
 * {@code mvn dependency:build-classpath}. But "the pom.xml describes the build" and "the pom.xml
 * decides which code runs" are the same sentence: Maven executes the plugin whose version the POM
 * chain names, loads the extensions the POM declares, and honours the plugin configuration the POM
 * contains - and the POM is a file the model may edit. The way out is not to parse the POM
 * ourselves but to <b>know whether it is still the POM the operator started with</b>. If it is,
 * running Maven is the same act the operator performs by hand. If it is not, a human decides -
 * through the review workflow, with the diff in front of them.
 * </p>
 * <p>
 * The check is deliberately about <em>inputs</em>, not about the answer Maven gives. What this
 * guard does <b>not</b> cover is stated in {@code docs/maven-guard.md} and belongs in the same
 * decision: profile activation depends on the environment, a multi-module run of
 * {@code dependency:build-classpath} with one {@code -Dmdep.outputFile} reports the last module,
 * and an approved POM still means Maven runs plugin code nobody read.
 * </p>
 *
 * <h2>What is watched</h2>
 * <p>
 * Every {@code pom.xml} inside the roots a model can write to ({@code IDE_PROJECT_DIR} plus the
 * add-on directories of {@link WorkProject}), every file below a {@code .mvn} directory at any
 * depth, plus {@code settings.xml}, {@code settings-security.xml} and {@code toolchains.xml}
 * directly under a {@code .m2} directory, because those are read on <em>every</em> Maven run.
 * {@code .mvn/extensions.xml} is the reason the {@code .mvn} half is not optional: core extensions
 * are loaded and executed <b>before</b> the POM is interpreted, so "no pom.xml changed" would not
 * be a statement about the executed code without it
 * (<a href="https://maven.apache.org/configure.html">maven.apache.org/configure.html</a>).
 * Build output directories are skipped - they are written by builds, not inputs to one.
 * </p>
 * <p>
 * The invariant that makes this enough: <b>a file Maven will read during a guarded run and that a
 * model can change is inside one of the watched roots, and is therefore in the comparison.</b>
 * Inputs above every root (a parent POM in a sibling checkout, the real {@code ~/.m2} when only
 * {@code ~/.m2/repository} is an add-on) are outside the model's write reach and are reported as
 * such instead of being silently trusted.
 * </p>
 *
 * <h2>Baseline capture is asynchronous</h2>
 * <p>
 * {@link #startAsync()} runs at server startup, before the first tool call could have written
 * anything, and a worker thread fills the map - a large reactor means many directories to walk.
 * Three phases, not two: {@link Phase#RUNNING} is <b>not</b> {@link Phase#READY}. A guard that
 * treats "not scanned yet" as "unchanged" lets the schedule decide about safety, so an unresolved
 * phase denies the run (fail closed) and the message names the fix. {@link #scanNow()} exists for
 * tests and for the operator path that wants the walk synchronously.
 * </p>
 *
 * <h2>Comparison is by content</h2>
 * <p>
 * Size and modification time are recorded and shown, but the decision uses the SHA-256 of the
 * bytes. A {@code touch}, a checkout, an IDE save or a rebuild with identical content must not
 * raise an approval request - and a file edited back to its original content is, correctly,
 * unchanged. Anything that would make the hash lie is out of the model's reach: rewriting the
 * hash map needs the process. The map may be <em>restored</em> from a file the operator names
 * ({@code tools.mvn.pomGuard.baseline}, see {@link #baselineFile()}), and only from outside every
 * watched root - a history the guarded party can write is not a history, it is an input.
 * </p>
 *
 * <h2>Configuration</h2>
 * <p>
 * Read from <b>system properties only</b>, never from the Spring {@code Environment}: the
 * {@code application.yml} of a host project sits in the model's working directory, and a switch
 * the guarded party can write is not a switch. (The {@code Environment} would also honour it from
 * {@code -D}, but it would give the file a vote as well.) All values are read on each call, so the
 * operator can change them only by restarting with different arguments - which is the point.
 * </p>
 * <ul>
 *   <li>{@code tools.mvn.pomGuard} = {@code enforce} (default) | {@code warn} | {@code off} -
 *       an unparsable value keeps {@code enforce} and is reported in the log, in
 *       {@link Summary#warnings()} and in every verdict message; nothing is reinterpreted quietly</li>
 *   <li>{@code tools.mvn.pomGuard.maxFiles} - watched files per root, default 4000</li>
 *   <li>{@code tools.mvn.pomGuard.maxDepth} - directory depth, default 24</li>
 *   <li>{@code tools.mvn.pomGuard.skipDirs} - additional directory names out of the comparison,
 *       comma separated; the answer to the one folder in a project that holds twenty thousand
 *       entries. A target POM inside such a folder is refused, not allowed
 *       ({@link Code#POM_NOT_WATCHED})</li>
 *   <li>{@code tools.mvn.pomGuard.rescan} - {@code known} (default: hash the known inputs, list a
 *       directory only when its modification time moved) or {@code full} (walk the tree on every
 *       check, for file systems whose directory times cannot be trusted)</li>
 *   <li>{@code tools.mvn.pomGuard.scanMillis} - wall-clock budget per root, default 20000; the
 *       startup walk runs in its own thread but {@link #check(Path)} walks again inside the tool
 *       call, and a root that was not read to the end is not an "unchanged"</li>
 *   <li>{@code tools.mvn.pomGuard.baseline} - absolute path of a file that keeps the baseline over
 *       a restart; unset means memory only. A path inside a watched root, or a relative one, is
 *       refused and reported ({@link #baselineFile()}) - and a file that exists but cannot be read
 *       denies, because the alternative would adopt whatever changed while the server was down</li>
 *   <li>{@code tools.mvn.pomGuard.captureMaxBytes} - per-file cap for keeping content for the
 *       diff, default 262144</li>
 *   <li>{@code tools.mvn.pomGuard.maxPatchFiles} / {@code .maxPatchLines} - diff budget,
 *       defaults 20 / 400</li>
 * </ul>
 *
 * <h2>What cannot be read cannot be called unchanged</h2>
 * <p>
 * A watched file whose bytes are unreadable, a root that stopped the walk at its file cap or its
 * time budget, a configured root that does not resolve: all of these make the comparison
 * <em>incomplete</em>, and an incomplete comparison denies the run instead of allowing it. That is
 * the whole point of the guard, so the case is loud (a warning with the exception) rather than a
 * debug line - and {@link #reset()} may be public while {@link #rebaseline(Collection, String)}
 * must never be reachable from a tool: clearing the baseline closes the gate, adopting one opens it.
 * </p>
 *
 * <h2>Where this stands (step 2 of 4)</h2>
 * <p>
 * Step 1 was the watch, step 2 added the decision in front of it ({@code MavenGuard}) and the write
 * paths that announce a content before it is lost - they call
 * {@link org.rogmann.mcp2sdk.watch.FileChangeWatch}, which reaches this class through
 * {@link #capturePreviousContent(Path)}. Step 3 is still open: no Maven invocation asks
 * {@code MavenGuard.requestRun} yet, so nothing is gated in practice even though the answer is
 * computed and tested. {@code docs/maven-guard.md} §4 keeps the current list.
 * </p>
 */
public final class PomWatch {

    private static final Logger LOG = LoggerFactory.getLogger(PomWatch.class);

    /** File name of a Maven project descriptor. */
    static final String POM_FILE = "pom.xml";

    /** Directories that are build output or tool state, never an input to a Maven run. */
    private static final Set<String> SKIPPED_DIRS = Set.of(
            "target", "build", "out", "bin", "dist", "node_modules", ".git", ".svn", ".hg",
            ".idea", ".vscode", ".gradle", ".m2repo", "runs", "logs", "tmp");

    /** Maven user configuration file names, watched when they sit under a {@code .m2} directory. */
    private static final Set<String> M2_CONFIG_FILES = Set.of(
            "settings.xml", "settings-security.xml", "toolchains.xml");

    // ------------------------------------------------------------------
    // Public vocabulary
    // ------------------------------------------------------------------

    /**
     * How the guard behaves. The value is a system property, not a project file - see the class
     * javadoc.
     */
    public enum Mode {
        /** Maven may run when the inputs match the baseline; otherwise a decision is needed. */
        ENFORCE,
        /** Everything is reported like {@link #ENFORCE}, but {@link Verdict#allowed()} stays
         * true. For a development setup in which the operator accepts the risk. */
        WARN,
        /** No comparison at all. Visible in every verdict and in the startup log. */
        OFF
    }

    /** Progress of the baseline capture. */
    public enum Phase {
        /** {@link #startAsync()} was never called (a unit test, or a caller that skipped it). */
        NOT_STARTED,
        /** The worker is walking the roots. Not a "yes". */
        RUNNING,
        /** The baseline is complete for every watched root. */
        READY,
        /** The capture failed; {@link Summary#problem()} names why. */
        FAILED
    }

    /** Outcome of {@link #check(Path)}. */
    public enum Code {
        /** Watched inputs of this run are byte-identical to the baseline. */
        UNCHANGED,
        /** At least one watched input differs - the list is in {@link Verdict#changes()}. */
        CHANGED,
        /** Neither the POM nor the Maven base directory lies in a root the model can write to. */
        OUTSIDE_WATCHED_ROOTS,
        /** {@code tools.mvn.pomGuard=off}. */
        GUARD_OFF,
        /** Baseline capture still running - treated as "unknown", i.e. denied. */
        BASELINE_RUNNING,
        /** Baseline capture failed - denied, with the recorded problem. */
        BASELINE_FAILED,
        /** Nothing to compare against: no watched root is configured. */
        NO_ROOTS,
        /** The affected root appeared after the capture - denied until it is baselined. */
        ROOT_NOT_BASELINED,
        /**
         * The POM the run targets is not in the compared set: it sits under a skipped directory
         * ({@code target/}, {@code bin/}, ...), below {@code tools.mvn.pomGuard.maxDepth}, or in a
         * directory the repeat visit does not list. Maven would still read exactly that file when it
         * is passed with {@code -f}, which is why this is its own code and not a silent
         * "unchanged".
         */
        POM_NOT_WATCHED
    }

    /** How a watched file differs from the baseline. */
    public enum ChangeKind {
        /** In the baseline, not on disk anymore. */
        DELETED,
        /** On disk, not in the baseline - including a newly created {@code .mvn} directory. */
        ADDED,
        /** On both sides with different content. */
        MODIFIED
    }

    /**
     * One watched file as recorded.
     *
     * @param sha256        content digest, or {@link #UNHASHABLE} for a file that could not be read
     * @param size          byte size at capture time (information for the reviewer)
     * @param mtimeMillis   modification time at capture time (information for the reviewer)
     */
    public record Finger(String sha256, long size, long mtimeMillis) {

        /**
         * Two captures are the same when the <b>content</b> is. Size and timestamp are compared
         * only as a fallback for files that could not be hashed at all, so that a rebuild touching
         * a POM never counts as a change.
         */
        boolean sameContentAs(Finger other) {
            if (other == null) {
                return false;
            }
            if (UNHASHABLE.equals(sha256) || UNHASHABLE.equals(other.sha256)) {
                return size == other.size && mtimeMillis == other.mtimeMillis;
            }
            return sha256.equals(other.sha256) && size == other.size;
        }
    }

    /** Marker digest for a file that could not be read during the capture. */
    static final String UNHASHABLE = "unreadable";

    /**
     * A difference between baseline and disk.
     *
     * @param path     absolute path on the server
     * @param root     watched root the file belongs to
     * @param kind     added, modified, deleted or unreadable
     * @param baseline capture of the file (null when {@code kind} is {@link ChangeKind#ADDED})
     * @param current  capture now (null when {@code kind} is {@link ChangeKind#DELETED})
     * @param patch    unified diff, empty when it could not be produced
     * @param patchNote why there is no patch (null if there is one and the file is unchanged-free)
     */
    public record Change(Path path, Path root, ChangeKind kind, Finger baseline, Finger current,
                         String patch, String patchNote) {

        /** @return path relative to its watched root - the form that is safe to show a model */
        public String relativePath() {
            if (!path.startsWith(root)) {
                // Cannot happen for a change produced by differences() (both sides come from one
                // root scan) - if it ever does, the baseline and the scan disagree, and that is a
                // bug worth reading about rather than a reason to print a pretty name.
                LOG.warn("PomWatch: {} is not below the watched root {} - baseline and root do "
                        + "not belong together", path, root);
                return path.getFileName().toString();
            }
            return root.relativize(path).toString().replace('\\', '/');
        }

        /**
         * Model-facing view: root label and relative path only. Absolute server paths stay out of
         * the tool result (the rule {@link WorkProject} follows for add-on descriptions); the
         * review page, which a human reads, uses {@link #path()}.
         */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("root", PomWatch.rootLabel(root));
            m.put("file", relativePath());
            m.put("change", kind.name().toLowerCase(Locale.ROOT));
            m.put("oldSha256", (baseline == null) ? null : baseline.sha256());
            m.put("newSha256", (current == null) ? null : current.sha256());
            m.put("size", (current != null) ? current.size()
                    : (baseline != null ? baseline.size() : 0L));
            if (patch != null && !patch.isEmpty()) {
                m.put("patchLines", patch.split("\n", -1).length);
            }
            if (patchNote != null) {
                m.put("patchNote", patchNote);
            }
            return m;
        }
    }

    /**
     * Answer to "may this run go ahead?".
     *
     * @param allowed       whether Maven may be invoked
     * @param code          the reason, machine-readable
     * @param mavenBaseDir  directory Maven treats as project base (where {@code .mvn} is looked
     *                      for), null when it could not be determined
     * @param changes       differences, empty unless {@link Code#CHANGED}
     * @param message       one sentence for the caller, naming the way forward
     * @param watchedRoots  roots the comparison covered
     */
    public record Verdict(boolean allowed, Code code, Path mavenBaseDir, List<Change> changes,
                          String message, List<Path> watchedRoots) {

        /** @return result map for a tool response (relative paths only) */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("allowed", allowed);
            m.put("code", code.name().toLowerCase(Locale.ROOT));
            m.put("guard", mode().name().toLowerCase(Locale.ROOT));
            m.put("message", message);
            if (!changes.isEmpty()) {
                List<Map<String, Object>> list = new ArrayList<>();
                for (Change c : changes) {
                    list.add(c.toMap());
                }
                m.put("changes", list);
            }
            return m;
        }
    }

    /**
     * State of the watch, for the log, the web UI and the review request.
     *
     * @param phase      capture progress
     * @param problem    why the capture failed or is incomplete (null if fine)
     * @param mode       effective guard mode
     * @param roots      watched roots
     * @param fileCount  watched files in the baseline
     * @param startedAt  capture start (epoch millis, 0 when never)
     * @param finishedAt capture end (epoch millis, 0 when not finished)
     * @param warnings   what the operator should know about this setup
     */
    public record Summary(Phase phase, String problem, Mode mode, List<Path> roots, int fileCount,
                          long startedAt, long finishedAt, List<String> warnings) {

        /** @return map for {@code m2.info()} and tool results */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("phase", phase.name().toLowerCase(Locale.ROOT));
            m.put("mode", mode.name().toLowerCase(Locale.ROOT));
            m.put("files", fileCount);
            m.put("roots", roots.size());
            m.put("elapsedMs", (startedAt > 0 && finishedAt > 0) ? (finishedAt - startedAt) : 0L);
            if (problem != null) {
                m.put("problem", problem);
            }
            if (!warnings.isEmpty()) {
                m.put("warnings", warnings);
            }
            Map<String, Object> persistence = baselineStatus();
            if (!persistence.isEmpty()) {
                // only when the operator configured a file - the shape m2.info() had before stays
                m.put("baseline", persistence);
            }
            return m;
        }
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    /**
     * Everything one watched root contributes to the baseline: its captures and the directories
     * they were found in.
     *
     * @param files watched file -&gt; capture
     * @param dirTimes modification time per directory the startup walk reached below this root.
     *              This is what makes {@link #check(Path)} cheap: an unchanged directory time means
     *              no entry was created, removed or renamed there, so the repeat visit neither lists
     *              it nor stats its files. The map is updated in place and gains whatever a later
     *              visit finds (a module directory created after the startup).
     */
    private record RootState(Map<Path, Finger> files, Map<Path, Long> dirTimes) {
    }

    /** watched root -&gt; its baseline. */
    private static final Map<Path, RootState> BASELINE = new ConcurrentHashMap<>();

    /**
     * Content a write path announced, kept only to render the diff.
     *
     * @param text       decoded content (UTF-8, else ISO-8859-1 - see {@link #readText(Path)})
     * @param byteSha256 SHA-256 of the <b>bytes</b> on disk at that moment, never of the decoded
     *                   text: only this matches the baseline digest, and matching those two is what
     *                   decides whether a patch may be shown at all. Hashing the text would break
     *                   on a BOM and on every ISO-8859-1 POM and would then suppress the patch with
     *                   a wrong explanation.
     * @param atMillis   when it was taken
     */
    private record Captured(String text, String byteSha256, long atMillis) {
    }

    private static final Map<Path, Captured> CAPTURED = new ConcurrentHashMap<>();

    /**
     * Guards the start/reset transitions. A {@link ReentrantLock} rather than
     * {@code synchronized} on the methods: the state is process-wide static, and the same lock has
     * to cover the flags that {@link #scanNow()} writes without making that (long) walk exclusive
     * for every caller - a monitor on a static method would do exactly that.
     */
    private static final Lock STATE_LOCK = new ReentrantLock();

    private static final AtomicReference<Phase> PHASE = new AtomicReference<>(Phase.NOT_STARTED);
    private static final AtomicReference<String> PROBLEM = new AtomicReference<>(null);
    /** The raw value of the last unparsable {@code tools.mvn.pomGuard}, null when it parsed. */
    private static final AtomicReference<String> BAD_MODE = new AtomicReference<>(null);
    private static final AtomicLong startedAtMillis = new AtomicLong(0L);
    private static final AtomicLong finishedAtMillis = new AtomicLong(0L);
    private static final AtomicBoolean workerStarted = new AtomicBoolean(false);
    private static final AtomicBoolean assessmentLogged = new AtomicBoolean(false);

    private PomWatch() {
    }

    // ------------------------------------------------------------------
    // Configuration (system properties only, on every call)
    // ------------------------------------------------------------------

    /**
     * Effective guard mode. An unparsable value keeps {@link Mode#ENFORCE} - the safe direction -
     * but is never treated as if it had been asked for: the finding goes to the log once and into
     * {@link Summary#warnings()} and the message of every verdict, so nobody reads a denial (or an
     * allowance) without seeing that a different mode was configured.
     */
    public static Mode mode() {
        String raw = System.getProperty("tools.mvn.pomGuard", "enforce");
        try {
            Mode mode = Mode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            BAD_MODE.set(null);
            return mode;
        } catch (IllegalArgumentException e) {
            String previous = BAD_MODE.getAndSet(raw);
            if (!raw.equals(previous)) {
                LOG.warn("PomWatch: tools.mvn.pomGuard='{}' is not a valid mode "
                        + "(expected enforce|warn|off) - enforce stays in effect", raw, e);
            }
            return Mode.ENFORCE;
        }
    }

    private static int intProperty(String name, int def) {
        String raw = System.getProperty(name);
        if (raw == null || raw.isBlank()) {
            return def;
        }
        try {
            int v = Integer.parseInt(raw.trim());
            return (v > 0) ? v : def;
        } catch (NumberFormatException e) {
            LOG.warn("PomWatch: {}='{}' is not a number, the default {} is used", name, raw, def);
            return def;
        }
    }

    private static int maxFiles() {
        return intProperty("tools.mvn.pomGuard.maxFiles", 4000);
    }

    /**
     * Whether a directory name is out of the comparison: the fixed build-output list, extended by
     * what the operator declares ({@code tools.mvn.pomGuard.skipDirs=db2,generated,...}).
     *
     * <p>
     * The knob exists for the shape a real deployment has: a directory with twenty thousand entries
     * inside a project. Listing it is what a repeat visit must not do over and over, and skipping it
     * is safe <em>as long as</em> a POM named as the target of a run is still refused - which it is,
     * by {@link Code#POM_NOT_WATCHED}. Skipping is a statement about what Maven reads as an input,
     * not about what a tool call may point at.
     * </p>
     */
    static boolean skipped(String name) {
        return SKIPPED_DIRS.contains(name) || skipDirs().contains(name);
    }

    /** The operator-declared skip list, parsed per call (system properties are the only source). */
    private static Set<String> skipDirs() {
        String raw = System.getProperty("tools.mvn.pomGuard.skipDirs", "");
        if (raw.isBlank()) {
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String name = part.trim();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    private static int maxDepth() {
        return intProperty("tools.mvn.pomGuard.maxDepth", 24);
    }

    private static long captureMaxBytes() {
        return intProperty("tools.mvn.pomGuard.captureMaxBytes", 262144);
    }

    private static int maxPatchFiles() {
        return intProperty("tools.mvn.pomGuard.maxPatchFiles", 20);
    }

    private static int maxPatchLines() {
        return intProperty("tools.mvn.pomGuard.maxPatchLines", 400);
    }

    /**
     * Wall-clock budget for walking one root. The startup walk runs in its own thread and may take
     * its time, but {@link #check(Path)} re-scans synchronously inside a tool call - a surprise
     * file system (network mount, a {@code tmpfs} with millions of inodes) must not turn a compile
     * check into an unbounded stall. Running out is a <b>problem</b>, not a partial answer: a root
     * that was not walked through cannot be called unchanged.
     */
    private static int scanMillis() {
        return intProperty("tools.mvn.pomGuard.scanMillis", 20000);
    }

    // ------------------------------------------------------------------
    // Roots
    // ------------------------------------------------------------------

    /**
     * Directories the model may write to and which therefore hold Maven's inputs:
     * {@code IDE_PROJECT_DIR} plus every add-on directory of {@link WorkProject}. Nested roots are
     * reduced to the outer one, so a tree is walked once.
     *
     * @return watched roots in configuration order (possibly empty)
     */
    public static List<Path> roots() {
        LinkedHashSet<Path> found = new LinkedHashSet<>();
        addExistingDir(found, System.getProperty("IDE_PROJECT_DIR"));
        try {
            for (String name : WorkProject.addonNames()) {
                Path addon = WorkProject.addonPath(name);
                if (addon != null) {
                    addExistingDir(found, addon.toString());
                }
            }
        } catch (RuntimeException e) {
            // A broken add-on configuration is WorkProject's error to raise; the guard must not
            // swallow it here, because "no roots" would otherwise look like "nothing to protect".
            // The stack trace is the useful part: it names the property that WorkProject rejected.
            noteProblem("add-on configuration is unusable: " + e.getMessage());
            LOG.warn("PomWatch: the add-on configuration is unusable, the watched roots are "
                    + "incomplete", e);
        }

        List<Path> ordered = new ArrayList<>(found);
        ordered.sort(Comparator.comparingInt((Path p) -> p.getNameCount()).thenComparing(p -> p));
        List<Path> outermost = new ArrayList<>();
        for (Path candidate : ordered) {
            boolean covered = false;
            for (Path kept : outermost) {
                if (candidate.startsWith(kept)) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                outermost.add(candidate);
            }
        }
        return List.copyOf(outermost);
    }

    private static void addExistingDir(LinkedHashSet<Path> set, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        try {
            Path p = Path.of(raw.trim());
            if (Files.isDirectory(p)) {
                set.add(p.toRealPath());
            }
        } catch (IOException | RuntimeException e) {
            // A configured root that cannot be resolved is a misconfiguration the operator has to
            // see: with the root missing, nothing in it is compared and Maven would run unchecked.
            LOG.warn("PomWatch: configured project directory '{}' is unusable as a root",
                    raw, e);
            noteProblem("configured directory is unusable as a root: " + raw);
        }
    }

    /**
     * The directory a relative Maven input belongs to: {@code IDE_PROJECT_DIR} when it is
     * configured and watched, otherwise the first watched root, otherwise {@code null}.
     *
     * <p>
     * This is <em>the</em> anchor, and the process directory of the server is deliberately not one.
     * Four Maven call paths answered "no directory named" with {@code Path.of(".")}, which is
     * wherever the operator happened to start the JVM; a caller outside the sandbox path (a test, a
     * future tool alias, a bare {@code "pom.xml"}) then made Maven read one file while the guard
     * compared another. That is not a cosmetic split: the comparison <em>is</em> the answer, so both
     * sides have to mean the same path. See {@code docs/open-points.md} §5.
     * </p>
     *
     * <p>
     * An add-on root is only a fallback, never the first choice: {@link #roots()} orders the watched
     * directories by depth, so the configured project is not reliably their first entry, and an
     * add-on (a {@code lib/} folder, a mounted repository) is a poor place to read "pom.xml" from.
     * </p>
     *
     * @return the canonical project directory, or null when nothing is configured as a root
     */
    public static Path projectDirectory() {
        List<Path> watched = roots();
        if (watched.isEmpty()) {
            return null;
        }
        Path configured = canonicalDirectory(System.getProperty("IDE_PROJECT_DIR"));
        return (configured != null && watched.contains(configured)) ? configured : watched.get(0);
    }

    /**
     * Absolute form of a Maven input: a relative spelling is anchored at
     * {@link #projectDirectory()}, never at the process directory of the server. An absolute path
     * comes back unchanged. A relative one with <em>no</em> configured root still resolves against
     * the process directory - there is no anchor to prefer then, and nothing to be inconsistent
     * with either: {@link #check(Path)} answers {@link Code#NO_ROOTS} for every mode but
     * {@code warn}/{@code off}, where no comparison happens in the first place.
     *
     * @param file the input as the caller named it, may be null
     * @return the anchored path, or null for null input
     */
    public static Path anchor(Path file) {
        if (file == null || file.isAbsolute()) {
            return file;
        }
        Path project = projectDirectory();
        return (project == null) ? Path.of("").toAbsolutePath().resolve(file) : project.resolve(file);
    }

    /** Canonical path of a configured directory, or null when it is unset, missing or unusable. */
    private static Path canonicalDirectory(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            Path candidate = Path.of(raw.trim());
            return Files.isDirectory(candidate) ? candidate.toRealPath() : null;
        } catch (IOException | RuntimeException e) {
            // addExistingDir() is the place that reports a broken root; this only asks for the
            // project directory and must not raise a second complaint about the same value.
            return null;
        }
    }

    /** Deepest watched root containing {@code path}, or null. */
    static Path rootOf(Path path, List<Path> watched) {
        Path best = null;
        for (Path root : watched) {
            if (path.startsWith(root) && (best == null || root.getNameCount() > best.getNameCount())) {
                best = root;
            }
        }
        return best;
    }

    /**
     * Absolute path with every symlink of the <em>containing directory</em> resolved.
     *
     * <p>
     * The watched roots are canonicalized ({@code toRealPath()}), so a caller-side path that only
     * normalized would not match them wherever a temp directory is a link ({@code /tmp} on macOS) -
     * the comparison would then report "outside the watched roots" for a file that is inside, which
     * is the one wrong answer this guard may not give. The file name stays as it is: a symlinked
     * <em>file</em> is not a watched input, and {@link #capturePreviousContent(Path)} skips it.
     * </p>
     *
     * <p>
     * A relative input goes through {@link #anchor(Path)} first, so it means the same file here as
     * it does in the process that is started with it: both end up under
     * {@link #projectDirectory()}. Absolute inputs (every call the sandbox makes) are unaffected.
     * </p>
     */
    static Path resolvedInput(Path file) {
        Path absolute = anchor(file).toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null) {
            return absolute;
        }
        try {
            return parent.toRealPath().resolve(absolute.getFileName());
        } catch (IOException | RuntimeException e) {
            return absolute;
        }
    }

    /**
     * Short, path-free label of a root for a model-facing message: the add-on name if it is one,
     * else the directory's own name. Never an absolute path (the {@link WorkProject} rule).
     */
    public static String rootLabel(Path root) {
        if (root == null) {
            return "unknown";
        }
        try {
            for (String name : WorkProject.addonNames()) {
                // addonPath() is already canonical (WorkProject resolves it with toRealPath())
                Path addon = WorkProject.addonPath(name);
                if (addon != null && root.equals(addon)) {
                    return name;
                }
            }
        } catch (RuntimeException ignored) {
            // label is decoration; the absolute path must not leak because of it
        }
        Path name = root.getFileName();
        return (name == null) ? "/" : name.toString();
    }

    // ------------------------------------------------------------------
    // Capture
    // ------------------------------------------------------------------

    /**
     * Starts the baseline walk in a daemon thread. Idempotent, and safe to call before the first
     * tool request: the phase stays {@link Phase#RUNNING} until the walk is through, which denies
     * rather than allows.
     */
    public static void startAsync() {
        if (!workerStarted.compareAndSet(false, true)) {
            return;                                  // idempotent: more than one server bean starts
        }
        startedAtMillis.set(System.currentTimeMillis());
        PHASE.set(Phase.RUNNING);
        Thread worker = new Thread(() -> {
            boolean ok = false;
            try {
                ok = startNow().phase() == Phase.READY;
            } catch (Throwable t) {
                // Throwable, not RuntimeException: an Error swallowed here would leave the phase at
                // RUNNING, and that denies every Maven run for the rest of this process without
                // ever telling anybody why.
                noteProblem("baseline run failed: " + t);
                LOG.warn("PomWatch: baseline run failed", t);
            } finally {
                if (!ok) {
                    PHASE.set(Phase.FAILED);
                }
                logBaselineResult();
            }
        }, "pom-watch");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Walks every watched root and (re)builds its baseline. Synchronous; used by
     * {@link #startAsync()}, by the approval path when a root is re-baselined, and by tests.
     *
     * @return the resulting summary
     */
    public static Summary scanNow() {
        startedAtMillis.set(System.currentTimeMillis());
        PHASE.set(Phase.RUNNING);
        PROBLEM.set(null);
        List<Path> watched = roots();
        Map<Path, RootState> fresh = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        String fromRoots = PROBLEM.getAndSet(null);
        if (fromRoots != null) {
            // A configured root that could not be resolved: fewer files compared than the operator
            // believes, which is a finding about this baseline, not a detail for the debug log.
            problems.add(fromRoots);
        }

        for (Path root : watched) {
            ScanResult scan = scanRoot(root);
            if (scan.incomplete()) {
                problems.add(scan.problem(rootLabel(root)));
            }
            fresh.put(root, new RootState(scan.files(), scan.dirTimes()));
        }

        BASELINE.clear();
        BASELINE.putAll(fresh);
        finishedAtMillis.set(System.currentTimeMillis());

        int count = 0;
        for (RootState state : BASELINE.values()) {
            count += state.files().size();
        }
        if (watched.isEmpty()) {
            noteProblem("no watched project directory: IDE_PROJECT_DIR is not set and no "
                    + "add-on directory is configured, there is nothing to compare");
        } else if (!problems.isEmpty()) {
            for (String p : problems) {
                noteProblem(p);
            }
        }
        PHASE.set((watched.isEmpty() || !problems.isEmpty()) ? Phase.FAILED : Phase.READY);
        if (LOG.isDebugEnabled()) {
            LOG.debug("PomWatch: baseline {} files in {} roots ({} ms)", count, watched.size(),
                    finishedAtMillis.get() - startedAtMillis.get());
        }
        persist("walk");
        return summary();
    }

    /**
     * Outcome of walking one root.
     *
     * @param files      watched files with their capture
     * @param dirTimes   modification time per reached directory - what a repeat visit compares
     *                   against to decide "nothing new can be here" without listing it
     * @param limitHit   the file cap stopped the walk
     * @param timedOut   the time budget stopped the walk
     * @param unhashable watched files whose content could not be read
     * @param error      why the walk failed, null when it went through
     */
    private record ScanResult(Map<Path, Finger> files, Map<Path, Long> dirTimes,
                              boolean limitHit,
                              boolean timedOut, int unhashable, String error) {

        /**
         * True when the root was <em>not</em> walked to the end, or holds a file nobody read.
         * A half-known baseline is not a no-change proof, so this is a denial, not a warning.
         */
        boolean incomplete() {
            return limitHit || timedOut || error != null || unhashable > 0;
        }

        /** One sentence for the log, the summary and the verdict. */
        String problem(String label) {
            if (error != null) {
                return label + ": " + error;
            }
            if (limitHit) {
                return label + ": more than " + maxFiles() + " watched files - no baseline is "
                        + "guessed for this root (tools.mvn.pomGuard.maxFiles)";
            }
            if (timedOut) {
                return label + ": reading stopped after " + scanMillis()
                        + " ms - root was not captured completely (tools.mvn.pomGuard.scanMillis)";
            }
            return label + ": " + unhashable + " watched file(s) are unreadable - no content, no "
                    + "digest, so no \"unchanged\" either";
        }
    }

    /**
     * Startup walk: one complete pass over a root, recording its watched files and the modification
     * time of every directory it reached.
     *
     * @param root the watched root
     * @return the files of this root and the directory times it consists of
     */
    private static ScanResult scanRoot(Path root) {
        return walkRoot(root, true, null, List.of());
    }

    /**
     * Repeat visit for {@link #check(Path)}. Cost per call: one hash per <em>known input</em> and
     * one {@code stat} per <em>known directory</em> - never a listing per directory, which is what
     * a project with 20000 files in one folder would charge for every single check.
     *
     * <p>
     * Two steps. The first re-hashes exactly the files the baseline holds: content changes and
     * deletions of known inputs are answered there, without touching any directory. The second asks
     * which directories could hide something <em>new</em>, and that question is answered by the
     * directory's own modification time: a directory's mtime moves when an entry is created,
     * removed or renamed, so an unchanged mtime means the listing would show nothing we do not
     * already know. Only those directories are listed - plus the ones this run names (target POM
     * directory, Maven base). A directory that is gone is not listed: its POMs were already reported
     * as deleted by the first step.
     * </p>
     *
     * @param root  the watched root
     * @param state its baseline; its directory times are updated in place
     * @param extra directories the run itself names, listed whatever their mtime says
     * @return the files as they are now, keyed exactly like the baseline
     */
    private static ScanResult rescanRoot(Path root, RootState state, Collection<Path> extra) {
        return walkRoot(root, fullRescan(), state, extra);
    }

    /**
     * Whether {@link #check(Path)} re-walks the whole tree ({@code -Dtools.mvn.pomGuard.rescan=full})
     * or takes the cheap route ({@code known}, the default).
     *
     * <p>
     * The cheap route trusts directory modification times for "nothing new here", which is the
     * normal case on a working tree. {@code full} is there for the file systems where that trust is
     * wrong (some network mounts keep stale or coarse directory times) and for the case a new module
     * directory should be noticed on a check that does not target it.
     * </p>
     */
    private static boolean fullRescan() {
        return "full".equalsIgnoreCase(System.getProperty("tools.mvn.pomGuard.rescan", "known"));
    }

    /**
     * Walks a root - completely (startup) or in the two steps of {@link #rescanRoot}.
     *
     * <p>
     * Symlinks are never followed: a link out of the root would let a "watched" tree stand for
     * content the guard never compares, which is the same hole as not watching {@code .mvn} at all.
     * Build output is skipped because Maven does not read it as an input - and a POM that is
     * <em>named</em> as the target of a run is then refused by {@link Code#POM_NOT_WATCHED} rather
     * than covered by a general "unchanged", because skipping is not the same as checking.
     * </p>
     */
    private static ScanResult walkRoot(Path root, boolean deep, RootState state,
                                       Collection<Path> extra) {
        Map<Path, Finger> files = new LinkedHashMap<>();
        Map<Path, Long> dirTimes = (state == null) ? new ConcurrentHashMap<>() : state.dirTimes();
        int limit = maxFiles();
        int baseDepth = root.getNameCount();
        int depth = maxDepth();
        long deadline = System.currentTimeMillis() + scanMillis();
        boolean[] limitHit = {false};
        boolean[] timedOut = {false};
        int[] unhashable = {0};
        String[] error = {null};

        // Step 1 (repeat visit): the known inputs by themselves. A file that is gone is left out of
        // the result, which is how differences() reads it as deleted; a file that is there but
        // unreadable is a hole in the comparison and counts as one.
        if (!deep && state != null) {
            for (Path known : state.files().keySet()) {
                if (System.currentTimeMillis() > deadline) {
                    timedOut[0] = true;
                    break;
                }
                if (!Files.isRegularFile(known)) {
                    continue;
                }
                addFinger(files, known, unhashable);
            }
        }

        // Step 2: the directories that could hold something new.
        Deque<Path> queue = new ArrayDeque<>();
        if (deep) {
            queue.add(root);
        } else {
            for (Map.Entry<Path, Long> entry : dirTimes.entrySet()) {
                long now = directoryTime(entry.getKey());
                if (now < 0) {
                    continue;                       // gone - step 1 already reported its POMs
                }
                if (now != entry.getValue()) {
                    queue.add(entry.getKey());
                }
            }
        }
        for (Path dir : (extra == null) ? List.<Path>of() : extra) {
            if (dir != null && dir.startsWith(root) && !queue.contains(dir)) {
                queue.add(dir);
            }
        }

        while (!queue.isEmpty()) {
            if (System.currentTimeMillis() > deadline) {
                timedOut[0] = true;
                break;
            }
            Path dir = queue.poll();
            if (dir.getNameCount() - baseDepth > depth) {
                continue;                       // deeper than tools.mvn.pomGuard.maxDepth allows
            }
            // Read the time BEFORE listing: a change that lands between the listing and the record
            // then looks like "moved again" on the next visit rather than like "nothing happened".
            long timeBefore = directoryTime(dir);
            if (timeBefore < 0) {
                dirTimes.remove(dir);
                continue;
            }
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
                for (Path entry : entries) {
                    String name = (entry.getFileName() == null) ? ""
                            : entry.getFileName().toString();
                    if (POM_FILE.equals(name)) {
                        if (files.size() >= limit) {
                            limitHit[0] = true;
                            break;
                        }
                        addFinger(files, entry, unhashable);
                    } else if (".mvn".equals(name)) {
                        collectProjectConfig(files, entry, limit, limitHit, unhashable);
                    } else if (".m2".equals(name)) {
                        collectMavenUserConfig(files, entry, unhashable);
                    } else if (!skipped(name)) {
                        boolean knownDir = dirTimes.containsKey(entry);
                        if (deep) {
                            if (isRealDirectory(entry)) {
                                queue.add(entry);               // the startup sees everything
                            }
                        } else if (!knownDir && looksLikeDirectory(name) && isRealDirectory(entry)) {
                            dirTimes.put(entry, -1L);           // forces a listing of the new child
                            queue.add(entry);
                        }
                    }
                }
            } catch (NoSuchFileException e) {
                // The directory went away while being listed: not a failure of the guard, the POMs
                // inside it are reported as deleted by step 1 of the next visit.
                dirTimes.remove(dir);
                continue;
            } catch (IOException e) {
                // An unreadable directory is a hole in the comparison: denied rather than a shrug.
                // The text reaches the model through the verdict, so it names no path beyond this.
                error[0] = "failed while reading (" + messageOf(e) + ")";
                LOG.warn("PomWatch: directory {} could not be read", dir, e);
                break;
            }
            dirTimes.put(dir, (timeBefore >= 0) ? timeBefore : -1L);
        }
        return new ScanResult(files, dirTimes, limitHit[0], timedOut[0], unhashable[0], error[0]);
    }

    /** Modification time of a directory, or -1 when it is gone or unreadable. */
    private static long directoryTime(Path dir) {
        try {
            return Files.getLastModifiedTime(dir).toMillis();
        } catch (IOException | RuntimeException e) {
            return -1L;
        }
    }

    /**
     * Whether an entry name is worth the {@code stat} that decides "directory?".
     *
     * <p>
     * Only reached while listing a directory whose time moved. Known children are skipped without
     * any file-system call, files with an extension are never directories in the sense that matters
     * here (a module directory is named like {@code sales-vm}, not like {@code pom.xml}), and what
     * Maven reads by name - {@code pom.xml}, {@code .mvn}, {@code settings.xml},
     * {@code toolchains.xml} - was handled before this test. The residue is a directory with a dot
     * in its name created after the startup: {@code tools.mvn.pomGuard.rescan=full} covers it.
     * </p>
     */
    private static boolean looksLikeDirectory(String name) {
        return name.indexOf('.') < 0;
    }

    /** Fingerprints one watched file, counting the ones nobody can read. */
    private static void addFinger(Map<Path, Finger> files, Path file, int[] unhashable) {
        try {
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file)) {
                return;
            }
        } catch (SecurityException e) {
            LOG.warn("PomWatch: access to {} was denied", file, e);
            unhashable[0]++;
            return;
        }
        Finger finger = fingerOf(file, -1L);
        if (UNHASHABLE.equals(finger.sha256())) {
            unhashable[0]++;
        }
        files.put(file.toAbsolutePath().normalize(), finger);
    }

    /**
     * Collects a {@code .mvn} directory: its own files plus {@code .mvn/wrapper/}. Nothing deeper -
     * Maven does not read deeper either, and the point of this directory is that its files take
     * effect before the POM is even interpreted.
     */
    private static void collectProjectConfig(Map<Path, Finger> files, Path mvnDir, int limit,
                                             boolean[] limitHit, int[] unhashable) {
        if (!isRealDirectory(mvnDir)) {
            return;
        }
        List<Path> levels = new ArrayList<>();
        levels.add(mvnDir);
        Path wrapper = mvnDir.resolve("wrapper");
        if (isRealDirectory(wrapper)) {
            levels.add(wrapper);
        }
        for (Path level : levels) {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(level)) {
                for (Path entry : entries) {
                    if (files.size() >= limit) {
                        limitHit[0] = true;
                        return;
                    }
                    addFinger(files, entry, unhashable);
                }
            } catch (IOException e) {
                LOG.warn("PomWatch: {} could not be read", level, e);
                unhashable[0]++;
            }
        }
    }

    /**
     * Collects Maven's own user configuration where it sits inside a watched root
     * ({@code settings.xml}, {@code toolchains.xml}): read on every run, so it is an input.
     */
    private static void collectMavenUserConfig(Map<Path, Finger> files, Path m2Dir,
                                               int[] unhashable) {
        if (!isRealDirectory(m2Dir)) {
            return;
        }
        for (String name : M2_CONFIG_FILES) {
            Path candidate = m2Dir.resolve(name);
            if (Files.exists(candidate)) {
                addFinger(files, candidate, unhashable);
            }
        }
    }

    /** True for a real directory, never for a symlink standing in for one. */
    private static boolean isRealDirectory(Path path) {
        try {
            return !Files.isSymbolicLink(path) && Files.isDirectory(path);
        } catch (SecurityException e) {
            LOG.warn("PomWatch: access to {} was denied", path, e);
            return false;
        }
    }

    private static String messageOf(IOException e) {
        return (e.getMessage() == null) ? e.toString() : e.getMessage();
    }

    /**
     * Whether a file inside {@code root} is an input Maven would read.
     *
     * @param root watched root
     * @param file file below that root
     * @return true when the file belongs in the baseline
     */
    static boolean isWatched(Path root, Path file) {
        Path rel;
        try {
            rel = root.relativize(file);
        } catch (IllegalArgumentException e) {
            return false;
        }
        int count = rel.getNameCount();
        if (count == 0 || underSkippedDirectory(rel)) {
            return false;
        }
        String name = rel.getFileName().toString();
        if (POM_FILE.equals(name)) {
            return true;
        }
        for (int i = 0; i < count - 1; i++) {
            if (".mvn".equals(rel.getName(i).toString())) {
                return true;
            }
        }
        return count >= 2 && M2_CONFIG_FILES.contains(name)
                && ".m2".equals(rel.getName(count - 2).toString());
    }

    /**
     * True when the file sits below a directory that the walk skips.
     *
     * <p>
     * Skipping build output is what keeps the walk affordable, but it also means those files are
     * <em>not compared</em> - so a POM that Maven would still read there (passed with {@code -f})
     * has to be refused by name instead of being covered by a general "unchanged". See
     * {@link Code#POM_NOT_WATCHED}.
     * </p>
     */
    static boolean underSkippedDirectory(Path relativeToRoot) {
        int count = relativeToRoot.getNameCount();
        for (int i = 0; i < count - 1; i++) {
            if (skipped(relativeToRoot.getName(i).toString())) {
                return true;
            }
        }
        return false;
    }

    /** Captures one file. */
    private static Finger fingerOf(Path file, long knownSize) {
        long size = knownSize;
        long mtime = 0L;
        try {
            if (knownSize < 0) {
                size = Files.size(file);
            }
            mtime = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            size = Math.max(size, 0L);
        }
        try {
            return new Finger(sha256(file), size, mtime);
        } catch (IOException e) {
            // No digest means no "unchanged" claim for this file: it is counted as a hole in the
            // walk (ScanResult.incomplete()) and the run is denied, so say so loudly here too.
            LOG.warn("PomWatch: the content of {} is unreadable, so the file cannot be "
                    + "shown to be unchanged", file, e);
            return new Finger(UNHASHABLE, size, mtime);
        }
    }

    /** Streaming SHA-256 of a file. */
    static String sha256(Path file) throws IOException {
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[8192];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Maven input guard", e);
        }
    }

    // ------------------------------------------------------------------
    // Content capture for the diff
    // ------------------------------------------------------------------

    /**
     * Hook for the write paths: call this <b>before</b> touching a file, and the content that is
     * about to be lost is kept so that {@link #check(Path)} can show a real diff instead of two
     * digests.
     *
     * <p>
     * The patch has to be taken here, not at check time: after the write, the previous content is
     * gone, and a reviewer comparing "sha 9f2a... with sha 41bc..." approves blind. Files over
     * {@code captureMaxBytes}, binary reads and unwatched names are skipped - a missing patch is
     * always named as such in {@link Change#patchNote()}.
     * </p>
     *
     * <p>
     * <b>Called by {@link org.rogmann.mcp2sdk.watch.FileChangeWatch}; write paths call that, not
     * this.</b> The name says what it is - the capture of a previous content, kept by whoever can
     * judge whether it is worth keeping. It cannot be package-private, because the central watch
     * lives in its own package by design, and moving the capture into the watch would mean holding
     * every written file's content in memory instead of the handful of Maven inputs this class
     * filters for. So the single door is convention plus coverage: the write-chain probe asserts
     * for every write path that the watch heard about it, and a path that skipped the watch loses
     * both the list entry and its diff.
     * </p>
     *
     * @param file the file a write is about to modify
     */
    public static void capturePreviousContent(Path file) {
        if (file == null || mode() == Mode.OFF) {
            return;
        }
        try {
            List<Path> watched = roots();
            Path abs = resolvedInput(file);
            Path root = rootOf(abs, watched);
            if (root == null || !isWatched(root, abs) || !Files.isRegularFile(abs)
                    || Files.isSymbolicLink(abs) || Files.size(abs) > captureMaxBytes()) {
                return;
            }
            String text = readText(abs);
            if (text == null) {
                return;
            }
            if (CAPTURED.size() > 4 * maxPatchFiles()) {
                CAPTURED.clear(); // a bounded cache, never a slow leak of project content
            }
            String byteSha = sha256(abs);
            Captured prior = CAPTURED.get(abs);
            Finger baseline = baselineFingerOf(abs);
            if (prior != null && baseline != null && baseline.sha256().equals(prior.byteSha256())) {
                // What is stored already IS the baseline content. Overwriting it with this
                // intermediate state would leave a capture that matches nothing, and the reviewer
                // of a POM written twice would get two digests instead of the one diff that matters
                // (found by runWriteChain: writeFile then appendFile).
                return;
            }
            CAPTURED.put(abs, new Captured(text, byteSha, System.currentTimeMillis()));
        } catch (IOException | RuntimeException e) {
            // Not fatal - the change is still reported, only without a patch - but worth reading:
            // it means the reviewer will get digests instead of a diff for this file.
            LOG.warn("PomWatch: the previous content of {} was not captured for the diff",
                    file, e);
        }
    }

    /**
     * The baseline capture of a file, or null when it is not part of any baseline.
     *
     * <p>
     * Needed because the map is keyed by the walked path, and a caller hands in a path resolved
     * through {@link #resolvedInput(Path)} - the two agree in practice, but comparing them is the
     * kind of assumption that silently turns "unchanged" into "not compared".
     * </p>
     */
    private static Finger baselineFingerOf(Path file) {
        for (RootState state : BASELINE.values()) {
            Finger direct = state.files().get(file);
            if (direct != null) {
                return direct;
            }
            for (Map.Entry<Path, Finger> entry : state.files().entrySet()) {
                if (entry.getKey().endsWith(file.getFileName())
                        && resolvedInput(entry.getKey()).equals(file)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    /**
     * Reads a file as text for the diff: strict UTF-8 first, and - because a POM in another
     * encoding is a real thing in older projects - ISO-8859-1 as fallback, with a warning either
     * way. A file with NUL bytes is treated as binary: it is named in the log, and no text is
     * returned, so the caller reports digests instead of showing a screen of replacement boxes.
     */
    private static String readText(Path file) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            LOG.warn("PomWatch: the content of {} is unreadable, there is no diff for it",
                    file, e);
            return null;
        }
        for (byte b : bytes) {
            if (b == 0) {
                LOG.warn("PomWatch: {} contains NUL bytes and is not text - no diff is shown "
                        + "for it (content {} bytes)", file.getFileName(), bytes.length);
                return null;
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            LOG.warn("PomWatch: {} is not valid UTF-8 - the diff reads it character by "
                    + "character in ISO-8859-1 (check the encoding declaration of the POM)",
                    file.getFileName());
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }

    // ------------------------------------------------------------------
    // The check
    // ------------------------------------------------------------------

    /**
     * Decides whether a Maven run against {@code pomPath} may go ahead without a decision.
     *
     * <p>
     * The compared set is derived from the <b>run</b>, not from the file name: Maven reads the POM,
     * its parents (possibly above it), its modules (below it) and the {@code .mvn} directory of the
     * project base, which it finds by walking up from the POM's directory until a
     * {@code .mvn}/{@code mvnw} marker. So the base directory is resolved first, and a change
     * anywhere in the root that contains either side of that pair denies the run.
     * </p>
     *
     * @param pomPath the POM the run would use (absolute, or relative to
     *        {@link #projectDirectory()} - the watched project directory, never the process
     *        directory of this server, which is what {@link #anchor(Path)} guarantees)
     * @return the verdict, never null
     */
    public static Verdict check(Path pomPath) {
        Mode mode = mode();
        List<Path> watched = roots();
        Path pomFile = (pomPath == null) ? null : resolvedInput(pomPath);
        Path baseDir = (pomFile == null) ? null : mavenBaseDir(pomFile);

        if (mode == Mode.OFF) {
            return verdict(true, Code.GUARD_OFF, baseDir, List.of(),
                    "pomGuard=off: Maven runs are allowed without any comparison", watched);
        }
        if (pomPath == null) {
            return deny(Code.BASELINE_FAILED, watched, baseDir,
                    "no POM path given - nothing can be checked");
        }
        if (watched.isEmpty()) {
            return deny(Code.NO_ROOTS, watched, baseDir,
                    "no watched project directory (IDE_PROJECT_DIR/add-ons), so the POM "
                            + "state is unknown");
        }

        Path pomRoot = rootOf(pomFile, watched);
        Path baseRoot = (baseDir == null) ? null : rootOf(baseDir, watched);
        if (pomRoot == null && baseRoot == null) {
            return verdict(true, Code.OUTSIDE_WATCHED_ROOTS, baseDir, List.of(),
                    "the POM and the Maven base directory are outside the writable project "
                            + "roots - the model cannot change them there", watched);
        }
        if (pomRoot != null && underSkippedDirectory(pomRoot.relativize(pomFile))) {
            // Skipped directories keep the walk affordable, but a POM named on the command line is
            // read by Maven whatever directory it stands in. Comparing "everything else" and
            // reporting "unchanged" would be the wrong certificate for exactly that file.
            return deny(Code.POM_NOT_WATCHED, watched, baseDir,
                    "the target POM sits under a skipped directory (e.g. target/, bin/, "
                            + "out/) and is therefore not captured");
        }

        Phase phase = PHASE.get();
        if (phase == Phase.NOT_STARTED || phase == Phase.RUNNING) {
            return deny(Code.BASELINE_RUNNING, watched, baseDir,
                    "the POM baseline is still being captured ("
                            + phase.name().toLowerCase(Locale.ROOT)
                            + "); try again later or set pomGuard=warn");
        }
        if (phase == Phase.FAILED) {
            return deny(Code.BASELINE_FAILED, watched, baseDir,
                    "incomplete POM baseline: " + (PROBLEM.get() == null ? "unknown reason"
                            : PROBLEM.get()));
        }

        List<Change> changes = new ArrayList<>();
        boolean incomplete = false;
        boolean targetSeen = false;
        LinkedHashSet<Path> affected = new LinkedHashSet<>();
        if (pomRoot != null) {
            affected.add(pomRoot);
        }
        if (baseRoot != null) {
            affected.add(baseRoot);
        }
        // What this run reads besides the directories the baseline knows: the target POM's own
        // directory (it may have been created after the startup) and the Maven base, whose .mvn is
        // read before the POM. Everything else is looked at through the known-directory list.
        List<Path> namedByThisRun = new ArrayList<>();
        if (pomFile.getParent() != null) {
            namedByThisRun.add(pomFile.getParent());
        }
        if (baseDir != null) {
            namedByThisRun.add(baseDir);
        }
        for (Path root : affected) {
            RootState state = BASELINE.get(root);
            if (state == null) {
                incomplete = true;
                continue;
            }
            ScanResult now = rescanRoot(root, state, namedByThisRun);
            if (now.incomplete()) {
                incomplete = true;
                noteProblem(now.problem(rootLabel(root)));
                continue;
            }
            if (root.equals(pomRoot)) {
                // The file this run is about has to be in the compared set - from the baseline or
                // from this visit. Anything else (below tools.mvn.pomGuard.maxDepth, in a directory
                // the repeat visit does not list) means nobody looked at it, and "unchanged" would
                // be a certificate for a file nobody read.
                targetSeen = state.files().containsKey(pomFile) || now.files().containsKey(pomFile);
            }
            changes.addAll(differences(root, state.files(), now.files()));
        }

        if (!changes.isEmpty()) {
            attachPatches(changes);
            changes.sort(Comparator.comparing(Change::relativePath));
            StringBuilder names = new StringBuilder();
            for (Change change : changes) {
                if (names.length() > 0) {
                    names.append(", ");
                }
                names.append(change.relativePath());
            }
            String text = "changed Maven inputs in project '" + rootLabel(
                    (pomRoot != null) ? pomRoot : baseRoot) + "': " + names;
            return verdict(mode == Mode.WARN, Code.CHANGED, baseDir, List.copyOf(changes),
                    (mode == Mode.WARN) ? text + " (pomGuard=warn: allowed anyway)"
                            : text + " - approval request required", watched);
        }
        if (incomplete) {
            return deny(Code.ROOT_NOT_BASELINED, watched, baseDir,
                    "one of the affected project directories is not captured completely"
                            + (PROBLEM.get() == null ? "" : ": " + PROBLEM.get()));
        }
        if (pomRoot != null && !targetSeen) {
            return deny(Code.POM_NOT_WATCHED, watched, baseDir,
                    "the target POM is not in the compared set (skipped directory or deeper "
                            + "than tools.mvn.pomGuard.maxDepth)");
        }
        // The Maven base may sit above every writable root (the reactor root one level over the
        // project). Its .mvn is then read by the run but compared by nobody: not writable by the
        // model, and worth naming rather than leaving as an unspoken assumption.
        String outsideBase = (baseRoot == null && pomRoot != null)
                ? " (the Maven base directory lies above the watched project directory; its "
                    + ".mvn is not compared, but it is outside the writable roots)"
                : "";
        return verdict(true, Code.UNCHANGED, baseDir, List.of(),
                "POM and .mvn unchanged since the server start" + outsideBase, watched);
    }

    private static Verdict verdict(boolean allowed, Code code, Path baseDir, List<Change> changes,
                                   String message, List<Path> watched) {
        return new Verdict(allowed, code, baseDir, changes, withConfigNote(message), watched);
    }

    private static Verdict deny(Code code, List<Path> watched, Path baseDir, String message) {
        return verdict(mode() == Mode.WARN, code, baseDir, List.of(), message, watched);
    }

    /**
     * A configuration the operator did not get is named in the answer, not only in the log: an
     * unparsable {@code tools.mvn.pomGuard} reads as "enforce" in the verdict, and without this
     * line the caller cannot tell a deliberate {@code enforce} from a typo.
     */
    private static String withConfigNote(String message) {
        String bad = BAD_MODE.get();
        return (bad == null) ? message
                : message + " (note: tools.mvn.pomGuard='" + bad + "' is not a valid "
                        + "mode, enforce is in effect)";
    }

    /**
     * Diffs two captures of the same root.
     *
     * @param root     the root (for labels and relative paths)
     * @param baseline captured at startup
     * @param current  captured now
     * @return the changes, in a stable order
     */
    static List<Change> differences(Path root, Map<Path, Finger> baseline,
                                    Map<Path, Finger> current) {
        List<Change> changes = new ArrayList<>();
        for (Map.Entry<Path, Finger> entry : baseline.entrySet()) {
            Finger now = current.get(entry.getKey());
            if (now == null) {
                changes.add(new Change(entry.getKey(), root, ChangeKind.DELETED, entry.getValue(),
                        null, "", "the file is not there anymore"));
            } else if (!entry.getValue().sameContentAs(now)) {
                changes.add(new Change(entry.getKey(), root, ChangeKind.MODIFIED, entry.getValue(),
                        now, "", null));
            }
        }
        for (Map.Entry<Path, Finger> entry : current.entrySet()) {
            if (!baseline.containsKey(entry.getKey())) {
                String note = entry.getKey().getFileName().toString().startsWith("pom.")
                        ? "new POM file" : "new file in the watched area";
                changes.add(new Change(entry.getKey(), root, ChangeKind.ADDED, null,
                        entry.getValue(), "", note));
            }
        }
        return changes;
    }

    /**
     * Fills {@link Change#patch()} from the content the write path handed over, and names the
     * reason wherever it cannot.
     *
     * <p>
     * A patch is only shown when the captured content still hashes to the baseline digest: if it
     * does not, something changed in between that was never captured, and a diff against the wrong
     * previous state would be worse than no diff - it would look authoritative.
     * </p>
     */
    private static void attachPatches(List<Change> changes) {
        int budget = maxPatchFiles();
        int shown = 0;
        for (int i = 0; i < changes.size(); i++) {
            Change change = changes.get(i);
            Captured captured = CAPTURED.get(change.path());
            if (captured == null) {
                if (change.kind() == ChangeKind.MODIFIED && change.patchNote() == null) {
                    changes.set(i, new Change(change.path(), change.root(), change.kind(),
                            change.baseline(), change.current(), "",
                            "previous content was not captured (the write path does not "
                                    + "announce it, or the file is too large) - compare it in an "
                                    + "editor"));
                }
                continue;
            }
            if (change.kind() != ChangeKind.MODIFIED && change.kind() != ChangeKind.DELETED) {
                continue;
            }
            if (change.baseline() != null && !UNHASHABLE.equals(change.baseline().sha256())
                    && !change.baseline().sha256().equals(captured.byteSha256())) {
                changes.set(i, new Change(change.path(), change.root(), change.kind(),
                        change.baseline(), change.current(), "",
                        "the captured content does not match the baseline - there was a "
                                + "change that nobody announced"));
                continue;
            }
            if (shown >= budget) {
                changes.set(i, new Change(change.path(), change.root(), change.kind(),
                        change.baseline(), change.current(), "",
                        "diff budget reached (maxPatchFiles=" + budget + ")"));
                continue;
            }
            String now = (change.kind() == ChangeKind.DELETED) ? null
                    : readTextQuietly(change.path());
            if (change.kind() == ChangeKind.MODIFIED && now == null) {
                // The new content could not be read as text. Rendering "every line deleted" would
                // be a wrong picture, so the digests stand alone here and say why.
                changes.set(i, new Change(change.path(), change.root(), change.kind(),
                        change.baseline(), change.current(), "",
                        "the new content is unreadable or not text - no diff is shown"));
                continue;
            }
            TextDiff.Result result = TextDiff.unified(captured.text(), now,
                    change.relativePath() + " (baseline)", change.relativePath() + " (current)",
                    new TextDiff.Options(3, 10, maxPatchLines()));
            shown++;
            changes.set(i, new Change(change.path(), change.root(), change.kind(),
                    change.baseline(), change.current(), result.patch(),
                    result.note()));
        }
    }

    private static String readTextQuietly(Path file) {
        try {
            return (Files.size(file) > captureMaxBytes()) ? null : readText(file);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Persistence of the baseline (tools.mvn.pomGuard.baseline)
    // ------------------------------------------------------------------

    /**
     * First line of a baseline file. A file that does not say what it is does not get trusted.
     *
     * <p>
     * The format is line based and tab separated on purpose: it is written by a tool that guards
     * digests, so the file has to be readable with an editor and diffable by the operator, and a
     * JSON or Java-serialised file would either drag a library into this class or produce
     * something nobody can check. One {@code root} line opens a section, {@code file} lines below
     * it are relative to that root (so a moved checkout reads as "this root is unknown" and is
     * walked again rather than silently half-matching), {@code dir} lines are absolute.
     * </p>
     */
    private static final String BASELINE_HEADER = "pomwatch-baseline v1";

    /** Serialises writes of the file; the state itself is {@link #STATE_LOCK}'s and {@link #BASELINE}'s. */
    private static final Lock BASELINE_IO_LOCK = new ReentrantLock();

    /** When the loaded baseline was written (ISO-8601), null when nothing was loaded. */
    private static final AtomicReference<String> loadedAt = new AtomicReference<>(null);
    /** The reason the loaded baseline records for its last change. */
    private static final AtomicReference<String> loadedReason = new AtomicReference<>(null);
    /** Why the configured file is not used (relative, inside a root, unreadable) - readable, not only logged. */
    private static final AtomicReference<String> baselineRefused = new AtomicReference<>(null);
    /** The last failure to write the file: a lost history has to be visible after the restart. */
    private static final AtomicReference<String> baselineWriteProblem = new AtomicReference<>(null);
    /** Why the file could not be written atomically here (a downgrade, not a loss - see {@link #writeBaselineBytes}). */
    private static final AtomicReference<String> atomicWriteProblem = new AtomicReference<>(null);

    /**
     * The configured baseline file, or null when persistence is off.
     *
     * <p>
     * A baseline is worth nothing if the guarded party can write it. Every watched root is
     * writable by the model - that is the premise of this class, not an oversight - so a file
     * inside one of them would be an <em>input</em> the model controls and not a history to compare
     * against. Such a path is refused (with the reason in {@link #baselineStatus()} and in the
     * warnings) and the guard runs from memory as before. A relative path is refused for the same
     * reason in disguise: relative inputs are anchored at the project directory
     * ({@link #anchor(Path)}), which is inside a root.
     * </p>
     *
     * @return the absolute, canonicalised target path, or null when persistence does not apply
     */
    static Path baselineFile() {
        String raw = System.getProperty("tools.mvn.pomGuard.baseline", "").trim();
        if (raw.isEmpty()) {
            baselineRefused.set(null);
            return null;
        }
        Path given = Path.of(raw);
        if (!given.isAbsolute()) {
            baselineRefused.set("is relative - persistence has to name an absolute path outside "
                    + "every watched root (a relative input means the project directory here)");
            return null;
        }
        Path parent = given.getParent();
        Path target;
        try {
            target = (parent == null) ? given
                    : parent.toRealPath().resolve(given.getFileName());
        } catch (IOException | RuntimeException e) {
            target = given.toAbsolutePath().normalize();
        }
        for (Path root : roots()) {
            if (target.startsWith(root)) {
                baselineRefused.set("lies inside the watched root '" + rootLabel(root)
                        + "': the model can write that directory, so the file would be an input "
                        + "and not a history");
                return null;
            }
        }
        baselineRefused.set(null);
        return target;
    }

    /**
     * The startup, synchronously: restore what the last process wrote, walk only what the file does
     * not know, then leave a file behind.
     *
     * <p>
     * Without {@code tools.mvn.pomGuard.baseline} (or with {@code pomGuard=off}, or with no file
     * yet) this <b>is</b> {@link #scanNow()} and nothing else. With a file, a POM that was edited
     * <em>while this server was down</em> arrives as a change instead of arriving as the new
     * baseline. That is the point: an in-memory baseline is only as trustworthy as the last start,
     * and the restart window is exactly where {@code application.yml} and
     * {@code logback-spring.xml} get edited ({@code docs/maven-guard.md} §5 Nr. 8).
     * </p>
     *
     * @return the resulting summary
     */
    public static Summary startNow() {
        List<Path> watched = roots();
        Path file = baselineFile();
        if (file == null || mode() == Mode.OFF || !Files.isRegularFile(file)) {
            return scanNow();                       // first start with persistence on: walk and write
        }
        startedAtMillis.set(System.currentTimeMillis());
        PHASE.set(Phase.RUNNING);
        PROBLEM.set(null);

        Map<Path, RootState> merged = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        try {
            readBaselineInto(file, merged);
        } catch (IOException | RuntimeException e) {
            // A baseline nobody can read is not a baseline nobody ever had. Walking now would
            // adopt whatever the downtime produced, quietly, and the operator would learn about it
            // from a green compile check instead of from a denial.
            noteProblem("the configured baseline file is unusable: " + e);
            PHASE.set(Phase.FAILED);
            LOG.warn("PomWatch: the baseline file {} is unusable; mvn runs stay denied until it is "
                    + "removed, fixed, or persistence is switched off", file, e);
            return summary();
        }

        int unknown = 0;
        for (Path root : watched) {
            if (merged.containsKey(root)) {
                continue;
            }
            unknown++;
            ScanResult scan = scanRoot(root);
            if (scan.incomplete()) {
                problems.add(scan.problem(rootLabel(root)));
            }
            merged.put(root, new RootState(scan.files(), scan.dirTimes()));
        }
        // Roots the file knows and nobody watches any more stay out of the comparison - keeping
        // them would report file counts the operator cannot find.
        merged.keySet().retainAll(watched);

        BASELINE.clear();
        BASELINE.putAll(merged);
        finishedAtMillis.set(System.currentTimeMillis());
        for (String problem : problems) {
            noteProblem(problem);
        }
        if (watched.isEmpty()) {
            noteProblem("no watched project directory: IDE_PROJECT_DIR is not set and no "
                    + "add-on directory is configured, there is nothing to compare");
        }
        int count = 0;
        for (RootState state : BASELINE.values()) {
            count += state.files().size();
        }
        PHASE.set((watched.isEmpty() || !problems.isEmpty()) ? Phase.FAILED : Phase.READY);
        LOG.info("PomWatch: baseline restored from {} ({} of {} roots walked anew, {} files)",
                file, unknown, watched.size(), count);
        persist("startup");
        return summary();
    }

    /**
     * Reads a baseline file into {@code into}. Anything that is not a complete, well-formed
     * baseline is an {@link IOException}: a half-parsed history is the one answer this class may
     * not give, because the alternative (walking) would silently adopt the change it missed.
     */
    private static void readBaselineInto(Path file, Map<Path, RootState> into) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        Path root = null;
        Map<Path, Finger> files = null;
        Map<Path, Long> dirs = null;
        int lineNumber = 0;
        int fileCount = 0;
        for (String line : lines) {
            lineNumber++;
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (lineNumber == 1) {
                if (!BASELINE_HEADER.equals(line.strip())) {
                    throw new IOException("line 1 is not \"" + BASELINE_HEADER + "\"");
                }
                continue;
            }
            if (line.startsWith("written=")) {
                int tab = line.indexOf('\t');
                String written = (tab < 0) ? line.substring("written=".length())
                        : line.substring("written=".length(), tab);
                loadedAt.set(written);
                if (tab >= 0 && line.startsWith("reason=", tab + 1)) {
                    loadedReason.set(line.substring(tab + "reason=".length() + 1));
                }
                continue;
            }
            String[] parts = line.split("\t", -1);
            switch (parts[0]) {
                case "root" -> {
                    if (parts.length != 2 || parts[1].isBlank()) {
                        throw new IOException("line " + lineNumber + ": incomplete root entry");
                    }
                    if (files != null) {
                        into.put(root, new RootState(files, dirs));
                    }
                    root = Path.of(parts[1]);
                    files = new LinkedHashMap<>();
                    dirs = new ConcurrentHashMap<>();
                    fileCount = 0;
                }
                case "file" -> {
                    if (files == null) {
                        throw new IOException("line " + lineNumber + ": a file before any root");
                    }
                    if (parts.length != 5) {
                        throw new IOException("line " + lineNumber + ": expected 5 fields");
                    }
                    files.put(root.resolve(parts[1]),
                            new Finger(parts[2], Long.parseLong(parts[3]),
                                    Long.parseLong(parts[4])));
                    fileCount++;
                }
                case "dir" -> {
                    if (dirs == null) {
                        throw new IOException("line " + lineNumber + ": a directory before any root");
                    }
                    if (parts.length != 3) {
                        throw new IOException("line " + lineNumber + ": expected 3 fields");
                    }
                    dirs.put(Path.of(parts[1]), Long.parseLong(parts[2]));
                }
                default -> throw new IOException("line " + lineNumber + ": unknown entry "
                        + parts[0]);
            }
        }
        if (files != null) {
            into.put(root, new RootState(files, dirs));
        }
        if (into.isEmpty()) {
            throw new IOException("the file holds no root section");
        }
    }

    /**
     * Writes the current baseline to {@link #baselineFile()}, atomically where the file system
     * allows it. Never throws: a lost history is worse than a noisy log line, but a failed write
     * that nobody reports is exactly the silent weakening this class argues against, so the
     * problem is kept in {@link #baselineWriteProblem()} and shows up in {@code m2.info()}.
     *
     * @param reason one line for the file header ("startup", "walk", "approved review ...")
     */
    private static void persist(String reason) {
        Path file = baselineFile();
        if (file == null || mode() == Mode.OFF) {
            return;
        }
        if (PHASE.get() != Phase.READY) {
            // A half-read walk must not become tomorrow's trusted past.
            if (LOG.isDebugEnabled()) {
                LOG.debug("PomWatch: the baseline is not persisted (phase={})", PHASE.get());
            }
            return;
        }
        BASELINE_IO_LOCK.lock();
        try {
            StringBuilder text = new StringBuilder(8192);
            text.append(BASELINE_HEADER).append('\n');
            String written = Instant.now().toString();
            text.append("written=").append(written)
                    .append("\treason=").append(oneLine(reason)).append('\n');
            for (Map.Entry<Path, RootState> entry : BASELINE.entrySet()) {
                Path root = entry.getKey();
                text.append("root\t").append(root).append('\n');
                for (Map.Entry<Path, Finger> file2 : entry.getValue().files().entrySet()) {
                    Finger finger = file2.getValue();
                    text.append("file\t").append(root.relativize(file2.getKey()))
                            .append('\t').append(finger.sha256())
                            .append('\t').append(finger.size())
                            .append('\t').append(finger.mtimeMillis()).append('\n');
                }
                for (Map.Entry<Path, Long> dir : entry.getValue().dirTimes().entrySet()) {
                    text.append("dir\t").append(dir.getKey())
                            .append('\t').append(dir.getValue()).append('\n');
                }
            }
            byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
            try {
                writeBaselineBytes(file, bytes);
            } catch (IOException e) {
                baselineWriteProblem.set("the baseline could not be written to " + file + ": " + e);
                LOG.warn("PomWatch: {}", (Object) baselineWriteProblem.get(), e);
                return;
            }
            baselineWriteProblem.set(null);
            loadedAt.set(written);
            loadedReason.set(oneLine(reason));
            if (LOG.isDebugEnabled()) {
                LOG.debug("PomWatch: baseline written to {} ({})", file, reason);
            }
        } finally {
            BASELINE_IO_LOCK.unlock();
        }
    }

    /**
     * Writes the baseline bytes to {@code file}, atomically where the file system offers it.
     *
     * <p>
     * The temporary file is in the same directory, because {@code ATOMIC_MOVE} across directories
     * is exactly what many file systems refuse. When the atomic path is unavailable - a rename the
     * mount will not do, an {@code UnsupportedOperationException} from the provider, or the sandbox
     * guest whose native layer fails on these calls ({@code docs/js/espresso.md}) - the same bytes
     * go out with a plain write.
     * </p>
     *
     * <p>
     * <b>That is a downgrade, and it is deliberately neither thrown nor whispered.</b> Throwing
     * would deny persistence on a file system that merely lacks the option, and would fail a
     * baseline walk whose in-memory result is perfectly usable. {@code debug} alone would let the
     * operator believe their history is crash-proof when it is not: on such a mount a kill between
     * truncate and last byte can lose the file, and a lost file means the next start walks a fresh
     * baseline - the exact hole this section exists to close. So the cause is warned <b>once per
     * distinct reason</b> (a network mount would otherwise repeat it on every approval) and kept in
     * {@link #baselineStatus()} as {@code atomicWriteUnsupported}, which reaches
     * {@code m2.info().pomGuard}. What still fails the caller is the plain write itself: there the
     * history really is gone, and that lands in {@code writeProblem}.
     * </p>
     */
    private static void writeBaselineBytes(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = null;
        try {
            temp = Files.createTempFile(file.getParent(), file.getFileName() + "-", ".tmp");
            Files.write(temp, bytes);
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            atomicWriteProblem.set(null);        // this mount can do it; say so again if it stops
            return;                                   // the good case: the temp file is the file now
        } catch (IOException | RuntimeException atomicPathUnavailable) {
            reportAtomicFallback(atomicPathUnavailable);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);       // already moved in the good case
                } catch (IOException ignored) {
                    // a leftover temp file beside the baseline is noise, not a finding
                }
            }
        }
        Files.write(file, bytes);
    }

    /**
     * Warns once per distinct cause that {@code ATOMIC_MOVE} was not usable here; a repeat of the
     * same cause goes to debug, because the fallback is not an event that needs the operator's
     * attention every time a baseline is written.
     */
    private static void reportAtomicFallback(Exception cause) {
        String reason = cause.getClass().getSimpleName() + ": "
                + ((cause.getMessage() == null) ? "no message" : cause.getMessage());
        String previous = atomicWriteProblem.getAndSet(reason);
        if (reason.equals(previous)) {
            LOG.debug("PomWatch: still writing the baseline without ATOMIC_MOVE ({})", reason);
        } else {
            LOG.warn("PomWatch: the baseline cannot be written atomically here ({}) - the plain "
                    + "write is used instead, so a crash during a write can lose the file and the "
                    + "next start would walk a fresh baseline (warned once per cause)", reason);
        }
    }

    /** Tab and newline are the field separators of the format - neither may appear in a reason. */
    private static String oneLine(String text) {
        if (text == null || text.isBlank()) {
            return "no reason given";
        }
        return text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    /**
     * What persistence is doing, for {@code m2.info()}, the web page and the tests: whether a file
     * is configured, whether it is in use and why not, where the loaded baseline came from, and
     * what the last write attempt said. Absolute paths appear here because this is the operator's
     * view - never in a verdict handed to a model.
     *
     * @return a map, empty when persistence is not configured and nothing went wrong
     */
    public static Map<String, Object> baselineStatus() {
        String configured = System.getProperty("tools.mvn.pomGuard.baseline", "").trim();
        Map<String, Object> m = new LinkedHashMap<>();
        if (configured.isEmpty()) {
            return m;
        }
        m.put("configured", configured);
        Path file = baselineFile();
        m.put("inUse", file != null);
        String refused = baselineRefused.get();
        if (refused != null) {
            m.put("refusedBecause", refused);
        }
        if (file != null) {
            m.put("exists", Files.isRegularFile(file));
        }
        if (loadedAt.get() != null) {
            m.put("loadedFrom", (file == null) ? "?" : file.toString());
            m.put("writtenAt", loadedAt.get());
            if (loadedReason.get() != null) {
                m.put("writtenReason", loadedReason.get());
            }
        }
        if (baselineWriteProblem.get() != null) {
            m.put("writeProblem", baselineWriteProblem.get());
        }
        if (atomicWriteProblem.get() != null) {
            m.put("atomicWriteUnsupported", atomicWriteProblem.get());
        }
        return m;
    }

    /**
     * The findings about persistence that belong in every warning list: a configured file that is
     * refused, and a write that failed. Both mean "the next restart has less history than the
     * operator believes", which is a sentence for the log and {@code m2.info()}, not for a debug
     * line.
     */
    private static void addPersistenceWarnings(List<String> warnings) {
        baselineFile();   // refreshes why the configured file is or is not in use
        String refused = baselineRefused.get();
        if (refused != null) {
            warnings.add("tools.mvn.pomGuard.baseline " + refused + "; the baseline is kept in "
                    + "memory only, so a change made while the server was down stays invisible");
        }
        String writeProblem = baselineWriteProblem.get();
        if (writeProblem != null) {
            warnings.add(writeProblem);
        }
    }

    // ------------------------------------------------------------------
    // Re-baselining
    // ------------------------------------------------------------------

    /**
     * Adopts the current content of the given files (or, for a deleted one, drops it) as the new
     * baseline.
     *
     * <p>
     * <b>Callers: the approved decision only.</b> Every re-baseline widens what the guard accepts
     * without asking, so an entry point reachable from a tool call or a tool argument would let a
     * model change a POM and then retire the check that noticed - which is the whole guard turned
     * into decoration. Restarting the server is the other legitimate way, and step 1 has no
     * operator button yet on purpose.
     * </p>
     *
     * @param files  paths the decision covered (absolute)
     * @param reason short text for the log ("approved review id ...")
     * @return number of watched files in the affected roots afterwards
     */
    public static int rebaseline(Collection<Path> files, String reason) {
        List<Path> watched = roots();
        LinkedHashSet<Path> affected = new LinkedHashSet<>();
        for (Path file : (files == null) ? List.<Path>of() : files) {
            Path root = rootOf(resolvedInput(file), watched);
            if (root != null) {
                affected.add(root);
            }
        }
        int count = 0;
        for (Path root : affected) {
            ScanResult scan = scanRoot(root);
            if (!scan.incomplete()) {
                BASELINE.put(root, new RootState(scan.files(), scan.dirTimes()));
            } else {
                // Adopting a half-read walk would retire the check for the files nobody read.
                LOG.warn("PomWatch: the new baseline for '{}' was thrown away: {}",
                        rootLabel(root), scan.problem(rootLabel(root)));
            }
            count += scan.files().size();
        }
        if (files != null) {
            for (Path file : files) {
                CAPTURED.remove(resolvedInput(file));
            }
        }
        PHASE.set(BASELINE.isEmpty() ? Phase.FAILED : Phase.READY);
        LOG.info("PomWatch: baseline updated for {} root(s), {} files ({})", affected.size(), count,
                (reason == null ? "no reason given" : reason));
        // The approval is the moment the history changes, so it is the moment the file has to know.
        persist((reason == null) ? "rebaseline" : reason);
        return count;
    }

    /**
     * Re-walks every watched root. Same restriction as {@link #rebaseline(Collection, String)}.
     *
     * @param reason short text for the log
     * @return the resulting summary
     */
    public static Summary rebaselineAll(String reason) {
        LOG.info("PomWatch: full re-baseline requested ({})",
                (reason == null ? "no reason given" : reason));
        CAPTURED.clear();
        Summary summary = scanNow();
        logBaselineResult();
        return summary;
    }

    // ------------------------------------------------------------------
    // Reporting
    // ------------------------------------------------------------------

    /**
     * Directory Maven treats as the project base of this POM: the nearest ancestor holding
     * {@code .mvn} or {@code mvnw}, else the POM's own directory. Maven looks in that directory for
     * {@code extensions.xml}, {@code maven.config} and {@code jvm.config} - the files that are read
     * before the POM itself, which is why the base belongs in the comparison and not just the POM.
     *
     * @param pomPath absolute POM path
     * @return base directory (never null for a non-null POM path)
     */
    public static Path mavenBaseDir(Path pomPath) {
        Path dir = pomPath.getParent();
        if (dir == null) {
            return pomPath;
        }
        Path fallback = dir;
        for (Path current = dir; current != null; current = current.getParent()) {
            if (Files.isDirectory(current.resolve(".mvn")) || Files.isRegularFile(current.resolve("mvnw"))) {
                return current;
            }
        }
        return fallback;
    }

    /**
     * Everything an operator or the web UI needs to know about the guard.
     *
     * @return current summary
     */
    public static Summary summary() {
        List<Path> watched = roots();
        int count = 0;
        for (RootState state : BASELINE.values()) {
            count += state.files().size();
        }
        List<String> warnings = List.copyOf(assess(watched, count, PHASE.get()));
        return new Summary(PHASE.get(), PROBLEM.get(), mode(), watched, count,
                startedAtMillis.get(), finishedAtMillis.get(), warnings);
    }

    /**
     * Setup findings; computed at startup and shown in the log, not per call.
     *
     * @param watched   watched roots
     * @param fileCount baseline size
     * @param phase     capture phase - an empty baseline while the walk still runs is not a finding
     */
    private static List<String> assess(List<Path> watched, int fileCount, Phase phase) {
        List<String> warnings = new ArrayList<>();
        Mode mode = mode();
        if (mode == Mode.OFF) {
            warnings.add("tools.mvn.pomGuard=off: every mvn runs without a comparison of "
                    + "the POM inputs");
        } else if (mode == Mode.WARN) {
            warnings.add("tools.mvn.pomGuard=warn: changed POMs are reported, not blocked");
        }
        if (watched.isEmpty()) {
            warnings.add("no watched directory: without IDE_PROJECT_DIR and without add-ons "
                    + "the unchanged state of a POM cannot be shown");
        }

        Path home = homeDirectory();
        for (Path root : watched) {
            if (home != null && (root.equals(home) || home.startsWith(root))) {
                warnings.add("project directory '" + rootLabel(root) + "' is $HOME or contains "
                        + "it: ~/.m2/settings.xml and every .mvn marker then sit inside the area "
                        + "the model may write to");
            }
            if (phase == Phase.READY && fileCount == 0) {
                warnings.add("no pom.xml found under '" + rootLabel(root)
                        + "' - the root probably misses the project");
            }
        }

        Path repository = repositoryQuietly();
        if (repository != null) {
            for (Path root : watched) {
                if (root.startsWith(repository) || repository.startsWith(root)) {
                    // Verified against WorkProject.java:31-38 - add-on roots are documented as
                    // "additional read/write roots" with the m2 repository as their example, and
                    // there is no read-only switch for them in this code base (an earlier draft of
                    // this warning assumed m2.readOnlyForFs(); that method does not exist). So the
                    // finding is stated as what it is: the guard compares poms, not JARs.
                    warnings.add("one watched root is the Maven repository (or lies inside "
                            + "it): add-on roots are read/write roots, the model can replace plugin "
                            + "JARs and parent POMs there, and no digest comparison guards against "
                            + "that - keep the repository out of the writable roots (or land the "
                            + "read/write split of the JS tools)");
                    break;
                }
            }
        }
        String badMode = BAD_MODE.get();
        if (badMode != null) {
            warnings.add("tools.mvn.pomGuard='" + badMode + "' is not a valid mode, enforce "
                    + "is in effect (expected: enforce|warn|off, as a system property of the "
                    + "start)");
        }
        addPersistenceWarnings(warnings);
        return warnings;
    }

    private static Path homeDirectory() {
        try {
            String home = System.getProperty("user.home");
            return (home == null) ? null : Path.of(home).toRealPath();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Path repositoryQuietly() {
        try {
            return M2Resolver.repository();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Logs the mode, the roots and every finding that is known <em>before</em> the walk finished
     * (an empty root configuration is a finding of its own; "no pom.xml found" is not, and only the
     * worker can tell that).
     */
    public static void logStartupAssessment() {
        if (assessmentLogged.get()) {
            // McpConfig may build more than one server bean; the findings do not change by asking
            if (LOG.isDebugEnabled()) {
                LOG.debug("PomWatch: (already reported at startup) mode={}, phase={}", mode(),
                        PHASE.get());
            }
            return;
        }
        assessmentLogged.set(true);
        report("startup");
    }

    /** What the walk produced: logged by the worker, and by every later re-baseline. */
    private static void logBaselineResult() {
        report("baseline");
    }

    private static void report(String stage) {
        Summary s = summary();
        LOG.info("PomWatch ({}): mode={}, phase={}, roots={}, watched files={}", stage, s.mode(),
                s.phase(), s.roots().size(), s.fileCount());
        for (String warning : s.warnings()) {
            LOG.warn("PomWatch ({}): {}", stage, warning);
        }
        if (s.phase() == Phase.FAILED) {
            LOG.warn("PomWatch ({}): baseline incomplete ({}): mvn runs are denied until "
                    + "that is resolved or tools.mvn.pomGuard=warn|off is set", stage, s.problem());
        }
    }

    private static void noteProblem(String message) {
        String previous = PROBLEM.get();
        PROBLEM.set((previous == null || previous.isBlank()) ? message : previous + "; " + message);
    }

    /** Drops all state: the guard goes back to {@link Phase#NOT_STARTED}, which <b>denies</b>.
     * Public because a caller outside the package may need it (tests, a later administrative
     * reload) - and safe to offer, unlike {@link #rebaseline(Collection, String)}: clearing the
     * baseline widens nothing, it closes everything until a new walk has run. */
    public static void reset() {
        STATE_LOCK.lock();
        try {
            BASELINE.clear();
            CAPTURED.clear();
            PHASE.set(Phase.NOT_STARTED);
            PROBLEM.set(null);
            BAD_MODE.set(null);
            loadedAt.set(null);
            loadedReason.set(null);
            baselineRefused.set(null);
            baselineWriteProblem.set(null);
            startedAtMillis.set(0L);
            finishedAtMillis.set(0L);
            workerStarted.set(false);
            assessmentLogged.set(false);
        } finally {
            STATE_LOCK.unlock();
        }
    }

    /**
     * Whether the previous content of {@code file} is being kept for a diff. Public as a read-only
     * query for {@link org.rogmann.mcp2sdk.watch.FileChangeWatch}, which reports it with the change
     * so an operator can tell "no diff because it was a binary" from "no diff because nobody
     * announced the write".
     *
     * @param file the path a write was announced for
     * @return true when {@link #capturePreviousContent(Path)} stored content for it
     */
    public static boolean hasCapturedContent(Path file) {
        if (file == null) {
            return false;
        }
        return CAPTURED.containsKey(resolvedInput(file));
    }

    /** Number of files currently held for diffing. For tests. */
    static int capturedCount() {
        return CAPTURED.size();
    }
}
