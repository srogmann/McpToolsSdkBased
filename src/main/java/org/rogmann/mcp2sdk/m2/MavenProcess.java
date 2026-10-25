package org.rogmann.mcp2sdk.m2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.rogmann.mcp2sdk.review.ReviewAttribute;
import org.rogmann.mcp2sdk.review.ReviewAttributes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The one door for every Maven process this server starts: decision, gate, process, budget.
 *
 * <p>
 * Before step 3 there were two doors - {@code M2Resolver} ran Maven behind its own
 * {@code MAVEN_GATE}, and {@code ReadDependencyClassSourceTool} ran two more {@code
 * ProcessBuilder}s with no gate, a hardcoded 60 s budget and output left in a pipe nobody reads.
 * One Maven could therefore sit beside another, and a chatty one filled the pipe buffer and hung
 * until its timeout. This class is the single place where a Maven command is turned into a
 * process, and the order there is the content:
 * </p>
 *
 * <ol>
 *   <li><b>{@link MavenGuard#requestRun} first, outside the gate.</b> A changed POM makes this a
 *       wait for a human (up to the approval window), and holding the gate meanwhile would stop
 *       every other Maven user of the server for something that is not the process's fault.</li>
 *   <li><b>Then the gate</b> - one Maven at a time, with the PID of the running one named in the
 *       error, because "waited 120 seconds" without a PID is not actionable.</li>
 *   <li><b>Then the process</b>: output to a temporary file rather than a pipe (a
 *       {@code dependency:tree} of a large reactor exceeds the pipe buffer and would deadlock
 *       against {@code waitFor}), a real timeout, and {@code destroyForcibly} on both timeout and
 *       interrupt - a killed Maven that still holds the repository is worse than a failed run.</li>
 *   <li><b>Then the report</b> to the request ({@link MavenGuard#reportRun}), so the reviewer can
 *       read what the approval produced next to the decision they made.</li>
 * </ol>
 *
 * <h2>Command shape</h2>
 * <p>
 * The read paths ask for {@code -o} (offline) and {@code -nsu}: a classpath lookup should not be
 * able to pull the world in. That is hygiene and not execution control - {@code -o} stops Maven's
 * resolver, not plugin code - and it is a switch, {@code tools.m2.offline=false}. A
 * {@code dependency:get} is the opposite case on purpose: it exists to fetch, so it asks the
 * guard with {@code online=true}, which the guard treats as its own reason to ask even when no
 * watched input changed (see {@code docs/maven-guard.md} §5 Nr. 3).
 * </p>
 * <p>
 * Whether the plugin is named by full coordinate is {@code tools.mvn.dependencyPluginVersion}:
 * unset (the default) keeps {@code dependency:<goal>} and lets the POM's parent chain choose the
 * version, which is what it did before this class existed. Setting it pins
 * {@code org.apache.maven.plugins:maven-dependency-plugin:<V>:<goal>}, so the version - and with
 * it the output format that {@link M2Resolver#parseClasspath} reads - no longer depends on which
 * parent a POM happens to name. It is not the default because a pinned version that is not in the
 * local repository turns every offline read into a failure, and that is a decision for the
 * operator who knows their repository, not for a code default.
 * </p>
 *
 * <h2>What is not here</h2>
 * <p>
 * No caching (that stays {@link M2Resolver}'s), no parsing, no {@code javap}. This class only ever
 * starts the one process and hands back its merged output.
 * </p>
 */
public final class MavenProcess {

    private static final Logger LOG = LoggerFactory.getLogger(MavenProcess.class);

    /** The plugin whose goals this server runs; pinned only when the operator names a version. */
    public static final String DEPENDENCY_PLUGIN = "org.apache.maven.plugins:maven-dependency-plugin";

    /** Last lines of a failed Maven output, so the real error stays visible. */
    private static final int ERROR_TAIL_CHARS = 4000;

    /** How long a forcibly killed process may take to disappear before the error says so. */
    private static final long KILL_GRACE_SECONDS = 5;

    /**
     * One run: the command, the budget, and the POM whose inputs the guard compares.
     *
     * @param pomPath         the POM the run is about (null for a run without a project, which is
     *                        only allowed for {@code networkRun} - anything else cannot be
     *                        compared and is denied)
     * @param workingDir      directory Maven is started in (its settings/POM context); {@code null}
     *                        means {@link PomWatch#projectDirectory()} - the run is anchored at the
     *                        watched project, never at the process directory of this server
     *                        ({@code docs/open-points.md} §5)
     * @param mavenCommand    the executable, as configured by the caller
     * @param args            arguments after the executable, in the order they should appear
     * @param timeoutSeconds  wall-clock budget of the process
     * @param lockWaitSeconds how long a competing caller waits for the gate
     * @param networkRun      true when the command reaches the network and writes into the
     *                        repository - the guard then asks even without a change
     */
    public record Options(Path pomPath, Path workingDir, String mavenCommand, List<String> args,
                          long timeoutSeconds, long lockWaitSeconds, boolean networkRun) {
    }

    /** At most one Maven process at a time, for the whole server. */
    private static final ReentrantLock GATE = new ReentrantLock(true);
    /**
     * PID of the running Maven, or -1. It is only ever read to name the process in the
     * contention error and in {@link #runningPid()}, so an {@link AtomicLong} is enough - the
     * exclusion itself is the gate's job, not this field's, and a compare-and-set here would
     * suggest that the PID decides something.
     */
    private static final AtomicLong runningPid = new AtomicLong(-1L);

    private MavenProcess() {
    }

    // ------------------------------------------------------------------
    // Command helpers
    // ------------------------------------------------------------------

    /** Offline is the default for reads; {@code tools.m2.offline=false} lets them fetch again. */
    public static boolean offlineEnabled() {
        return Boolean.parseBoolean(System.getProperty("tools.m2.offline", "true"));
    }

    /**
     * The goal reference of a read path: {@code dependency:tree}, or the fully pinned coordinate
     * when {@code tools.mvn.dependencyPluginVersion} names a version.
     *
     * @param goal the goal name, without prefix ({@code build-classpath}, {@code tree},
     *             {@code get})
     */
    public static String dependencyGoal(String goal) {
        String version = System.getProperty("tools.mvn.dependencyPluginVersion", "").trim();
        return version.isEmpty()
                ? "dependency:" + goal
                : DEPENDENCY_PLUGIN + ":" + version + ":" + goal;
    }

    /** The flags that keep a read inside the machine: {@code -o} (unless switched off) + {@code -nsu}. */
    public static List<String> readFlags() {
        List<String> flags = new ArrayList<>();
        if (offlineEnabled()) {
            flags.add("-o");
        }
        flags.add("-nsu");
        return flags;
    }

    // ------------------------------------------------------------------
    // The run
    // ------------------------------------------------------------------

    /**
     * Runs Maven after the guard allowed it, and returns its merged output.
     *
     * @param o the run to start
     * @return the process output (stdout and stderr merged)
     * @throws IOException when the guard denied the run, the gate stayed busy, the budget ran out
     *         or the process exited non-zero - each with the answer or the output tail that says
     *         what to do next
     */
    public static String run(Options o) throws IOException {
        // The anchor is decided once, here: a caller that names no directory means the watched
        // project, not the process directory this server was started in (docs/open-points.md §5).
        // PomWatch.check() anchors a relative POM the same way (PomWatch.anchor), so the diff the
        // reviewer reads and the file Maven is about to read are one and the same file.
        Path workDir = (o.workingDir() != null) ? o.workingDir() : PomWatch.projectDirectory();
        if (workDir == null) {
            LOG.warn("MavenProcess: no project directory is configured (IDE_PROJECT_DIR unset and "
                    + "no watched root), so Maven starts in the process directory {}",
                    Path.of("").toAbsolutePath());
        }

        List<String> command = new ArrayList<>();
        command.add(o.mavenCommand());
        command.addAll(o.args());

        Map<String, Object> guardOptions = new HashMap<>();
        guardOptions.put("wait", true);
        guardOptions.put("online", o.networkRun());
        if (workDir != null) {
            // the reviewer should read the directory the process is really started in, not the
            // Maven base that happens to be derived from the POM
            guardOptions.put("workingDir", workDir.toString());
        }
        MavenGuard.Approval approval = MavenGuard.requestRun(o.pomPath(), command, guardOptions);
        if (!approval.allowed()) {
            throw new IOException("Maven run was not allowed (" + approval.reason()
                    + (approval.requestId() == null ? "" : ", request " + approval.requestId())
                    + "): " + approval.message());
        }

        long startedAt = System.currentTimeMillis();
        String output = null;
        String failure = null;
        try {
            output = gated(o, command, workDir);
            return output;
        } catch (IOException e) {
            failure = e.getMessage();
            throw e;
        } finally {
            if (approval.requestId() != null) {
                report(approval.requestId(), o, output, failure, System.currentTimeMillis() - startedAt);
            }
        }
    }

    /** The gate and the process itself - see the class javadoc for why they are one step. */
    private static String gated(Options o, List<String> command, Path workDir) throws IOException {
        boolean locked;
        try {
            locked = GATE.tryLock(o.lockWaitSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the Maven process gate", e);
        }
        if (!locked) {
            long pid = runningPid.get();
            throw new IOException("Another Maven process is already running (PID "
                    + (pid > 0 ? pid : "unknown") + "); waited " + o.lockWaitSeconds()
                    + " seconds. You can terminate it from a shell (kill "
                    + (pid > 0 ? pid : "<pid>") + ") and retry.");
        }
        // Output goes to a temp file, not a pipe: a large 'dependency:tree' exceeds the pipe
        // buffer, which would block the process while this side waits in waitFor() (deadlock).
        Path outFile = Files.createTempFile("mcp-mvn-", ".log");
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.to(outFile.toFile()));
            if (workDir != null) {
                pb.directory(workDir.toFile());
            }
            Process process = pb.start();
            runningPid.set(process.pid());
            try {
                boolean finished;
                try {
                    finished = process.waitFor(o.timeoutSeconds(), TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    process.destroyForcibly();
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for Maven; a kill signal was "
                            + "sent to PID " + process.pid() + " (not waited for)", e);
                }
                if (!finished) {
                    process.destroyForcibly();
                    // destroyForcibly() does not wait, so "killed" is only a claim after the
                    // process is actually gone - otherwise the error says what is still running
                    // and names the PID for a shell kill.
                    boolean gone;
                    try {
                        gone = process.waitFor(KILL_GRACE_SECONDS, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        // the wait was cut short, not the kill: look once, and say what is seen
                        Thread.currentThread().interrupt();
                        gone = !process.isAlive();
                    }
                    throw new IOException("Maven command timed out after " + o.timeoutSeconds()
                            + " seconds; the process was "
                            + (gone ? "killed" : "left running (PID " + process.pid()
                                + ", kill it from a shell)")
                            + ": " + String.join(" ", command));
                }
                String output = Files.readString(outFile, StandardCharsets.UTF_8);
                if (process.exitValue() != 0) {
                    throw new IOException("Maven command failed with exit code "
                            + process.exitValue() + ": " + String.join(" ", command) + "\n"
                            + abbreviateTail(output));
                }
                return output;
            } finally {
                runningPid.set(-1L);
            }
        } finally {
            Files.deleteIfExists(outFile);
            GATE.unlock();
        }
    }

    /** The outcome of the approved run, reported to the request - never fatal for the caller. */
    private static void report(String requestId, Options o, String output, String failure,
                               long durationMs) {
        try {
            boolean ok = failure == null;
            List<ReviewAttribute> detail = new ArrayList<>();
            detail.add(ReviewAttributes.duration("duration", "Duration", durationMs, "ms"));
            detail.add(ReviewAttributes.number("outputBytes", "Output size",
                    (output == null) ? 0L : (long) output.getBytes(StandardCharsets.UTF_8).length));
            detail.add(ReviewAttributes.list("arguments", "Maven arguments", o.args()));
            String summary = ok
                    ? "exit 0, " + (durationMs / 1000) + " s, " + ((output == null) ? 0
                        : output.split("\n", -1).length) + " output lines"
                    : "failed after " + (durationMs / 1000) + " s: " + firstLine(failure);
            MavenGuard.reportRun(requestId, ok, summary, detail, durationMs);
        } catch (RuntimeException e) {
            LOG.warn("MavenProcess: the outcome of request {} could not be reported", requestId, e);
        }
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "unknown";
        }
        int nl = text.indexOf('\n');
        return (nl < 0) ? text : text.substring(0, nl);
    }

    private static String abbreviateTail(String output) {
        String s = output.strip();
        if (s.length() <= ERROR_TAIL_CHARS) {
            return s;
        }
        return "...(truncated)...\n" + s.substring(s.length() - ERROR_TAIL_CHARS);
    }

    /** For {@code m2.info()} and the tests: is a Maven process holding the gate right now? */
    public static boolean busy() {
        return runningPid.get() > 0;
    }

    /** PID of the running Maven, or -1. */
    public static long runningPid() {
        return runningPid.get();
    }
}
