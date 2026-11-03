package org.rogmann.mcp2sdk.git;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The one door for every git process this server starts: argument vector, environment, gate,
 * budget.
 *
 * <p>
 * There is no shell anywhere in this class. A caller hands over the arguments <em>after</em> the
 * global options ({@code ["status", "--porcelain=v2", "-z"]}), and this class puts them in front of
 * a fixed prelude - so the command line is a list, never a string, and no value can turn into an
 * option by accident (see {@link GitCommand} for the value grammar).
 * </p>
 *
 * <h2>The prelude</h2>
 * <pre>{@code
 * git --no-pager -c core.quotepath=false -c color.ui=false -c log.showSignature=false
 *     -c advice.detachedHead=false -C <repository root> --literal-pathspecs <arguments...>
 * }</pre>
 * <p>
 * {@code -C} instead of {@code ProcessBuilder.directory()} so that the directory named in an error
 * message is the one git uses. {@code --literal-pathspecs} because a glob written by a caller is
 * meant literally (the {@code search} module is the place for patterns). {@code --no-pager}
 * because a pager on a pipe would block forever.
 * </p>
 *
 * <h2>The environment</h2>
 * <p>
 * An inherited {@code GIT_DIR}, {@code GIT_WORK_TREE}, {@code GIT_INDEX_FILE},
 * {@code GIT_CONFIG_PARAMETERS} or {@code GIT_EXTERNAL_DIFF} would silently redirect the command to
 * another repository or another program, so <em>every</em> {@code GIT_*} variable is removed and
 * only a fixed set is set again: {@code LC_ALL}/{@code LANG} for stable output,
 * {@code GIT_TERMINAL_PROMPT=0} so nothing ever waits for a password, {@code GIT_OPTIONAL_LOCKS=0}
 * so that a reading command such as {@code status} does not rewrite the index, and
 * {@code GIT_CEILING_DIRECTORIES} so that repository discovery cannot climb above the project
 * directory. The single exception to the ceiling is {@link #discover(Path, List)}, which exists to
 * <em>explain</em> why there is no repository here (see {@link GitRepo#detect(Path)}).
 * </p>
 *
 * <h2>Gate, budget, output</h2>
 * <p>
 * At most one git process runs at a time ({@link ReentrantLock}, fair): two concurrent
 * {@code status} calls would not corrupt anything, but a competing {@code commit} would fail on
 * {@code index.lock}, and "the other call had it" is a poor error message. A timeout kills the
 * process ({@code destroyForcibly} plus a grace period, like {@code MavenProcess}).
 * </p>
 * <p>
 * Three output paths, each for what it is good at: {@link #run(Path, List)} redirects into a
 * temporary <em>file</em> (a large {@code status} or {@code ls-tree} exceeds the pipe buffer and
 * would deadlock against {@code waitFor}) and decodes the text; {@link #runBytes(Path, List, int)}
 * reads the same way but keeps the bytes (a blob is not text); {@link #stream(Path, List,
 * ChunkConsumer)} reads the <em>pipe</em> while the process runs, because the whole point of
 * {@code log} over a long history is to stop in the middle - and then the process is killed.
 * stdout over the character budget is reported as truncated, and the parsers refuse to interpret a
 * truncated machine format.
 * </p>
 */
public final class GitProcess {

    private static final Logger LOG = LoggerFactory.getLogger(GitProcess.class);

    /** Program name or path of the git executable. */
    public static final String PROP_BINARY = "mcp2sdk.git.binary";

    /** Wall-clock budget of one git process, in seconds. */
    public static final String PROP_TIMEOUT_SECONDS = "mcp2sdk.git.timeoutSeconds";

    /** How long a caller waits for the gate while another git process runs, in seconds. */
    public static final String PROP_LOCK_WAIT_SECONDS = "mcp2sdk.git.lockWaitSeconds";

    /** Upper bound of characters (or bytes) kept from stdout of one call. */
    public static final String PROP_MAX_OUTPUT_CHARS = "mcp2sdk.git.maxOutputChars";

    /** Switches the writing operations (commit, restore) off when {@code true}. */
    public static final String PROP_READ_ONLY = "mcp2sdk.git.readOnly";

    private static final long DEFAULT_TIMEOUT_SECONDS = 20L;
    private static final long MIN_TIMEOUT_SECONDS = 1L;
    private static final long MAX_TIMEOUT_SECONDS = 300L;

    private static final long DEFAULT_LOCK_WAIT_SECONDS = 30L;
    private static final long MAX_LOCK_WAIT_SECONDS = 600L;

    /** 1 MiB - enough for a {@code git log --raw} over a large history, still a ceiling. */
    private static final int DEFAULT_MAX_OUTPUT_CHARS = 1_048_576;
    private static final int MIN_MAX_OUTPUT_CHARS = 1_000;
    /**
     * 4 MiB. This ceiling is also a heap bound: {@link #readBoundedBytes(Path)} reads up to four
     * times the budget (bytes per character), so a runaway output must not be allowed to allocate
     * more than that before it is cut.
     */
    private static final int MAX_MAX_OUTPUT_CHARS = 4_194_304;

    /** Characters of stderr kept for a failure message. */
    private static final int ERROR_TAIL_CHARS = 4_000;

    /** How long a forcibly killed process may take to disappear before the error says so. */
    private static final long KILL_GRACE_SECONDS = 5L;

    /** Options in front of every subcommand - see the class javadoc. */
    private static final List<String> PRELUDE = List.of(
            "--no-pager",
            "-c", "core.quotepath=false",
            "-c", "color.ui=false",
            "-c", "log.showSignature=false",
            "-c", "advice.detachedHead=false");

    /** Size of one chunk read from a streaming stdout. */
    private static final int STREAM_CHUNK_CHARS = 8_192;

    /** At most one git process at a time, for the whole server. */
    private static final ReentrantLock GATE = new ReentrantLock(true);

    /** No git process is running - a real PID is always positive. */
    private static final long NO_PID = -1L;

    /**
     * PID of the running git process, or {@link #NO_PID}. Read only to name the process in the
     * contention error and in {@link #runningPid()}; the exclusion itself is the gate's job.
     */
    private static final AtomicLong RUNNING_PID = new AtomicLong(NO_PID);

    private GitProcess() {
    }

    /**
     * The outcome of one git call whose stdout is read as text.
     *
     * @param command          the executed argument vector (without secrets - it never contains
     *                         any)
     * @param exitCode         the exit code
     * @param stdout           stdout, decoded and possibly truncated to the character budget
     * @param stderr           stderr, decoded and possibly truncated
     * @param stdoutTruncated  true if stdout exceeded the character budget
     * @param durationMs       wall-clock duration
     */
    public record Result(List<String> command, int exitCode, String stdout, String stderr,
                         boolean stdoutTruncated, long durationMs) {

        /**
         * @return the subcommand, e.g. {@code status} (empty for a call without arguments)
         */
        public String subcommand() {
            for (int i = 0; i < command.size(); i++) {
                if ("--literal-pathspecs".equals(command.get(i))) {
                    return (i + 1 < command.size()) ? command.get(i + 1) : "";
                }
            }
            return "";
        }
    }

    /**
     * The outcome of one git call whose stdout is kept as bytes.
     *
     * @param data      the bytes, at most the requested maximum
     * @param size      number of bytes produced by git (may exceed {@code data.length})
     * @param truncated true if {@code data} holds fewer bytes than git produced
     */
    public record Bytes(byte[] data, long size, boolean truncated) {
    }

    /**
     * The outcome of one streamed git call.
     *
     * @param exitCode  the exit code as the JVM reports it - after a kill it is derived from the
     *                  signal (137 for SIGKILL) and says nothing about git
     * @param stopped   true if the consumer asked to stop (the process was killed)
     * @param truncated true if the character budget ended the stream (the process was killed)
     * @param chars     number of characters handed to the consumer (at most the budget)
     * @param durationMs wall-clock duration
     */
    public record StreamResult(int exitCode, boolean stopped, boolean truncated, long chars,
                               long durationMs) {
    }

    /**
     * Receives stdout while the process runs.
     */
    @FunctionalInterface
    public interface ChunkConsumer {

        /**
         * @param chunk the next piece of stdout (character boundaries are respected, record
         *              boundaries are not - the caller assembles its records)
         * @return true to continue, false to stop (the process is killed)
         */
        boolean accept(String chunk);
    }

    // ------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------

    /**
     * The configured git executable.
     *
     * @return the program name or path as configured
     * @throws IllegalStateException if the property is set but blank
     */
    public static String binary() {
        String value = System.getProperty(PROP_BINARY, "git");
        if (value.isBlank()) {
            throw new IllegalStateException("Property '" + PROP_BINARY + "' has invalid value '"
                    + value + "' (expected a program name or path).");
        }
        return value.strip();
    }

    /**
     * Locates the configured executable without starting it.
     *
     * @return the path of the executable, or {@code null} if it is not found
     */
    public static Path locateBinary() {
        String command = binary();
        if (command.indexOf('/') >= 0 || command.indexOf(File.separatorChar) >= 0) {
            Path path = Path.of(command);
            return (Files.isRegularFile(path) && Files.isExecutable(path)) ? path : null;
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank()) {
            return null;
        }
        for (String dir : pathEnv.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            for (String candidate : new String[]{command, command + ".exe"}) {
                Path exe = Path.of(dir).resolve(candidate);
                if (Files.isRegularFile(exe) && Files.isExecutable(exe)) {
                    return exe;
                }
            }
        }
        return null;
    }

    /** @return true if the writing operations are switched off by property */
    public static boolean readOnlyMode() {
        return Config.bool(PROP_READ_ONLY, false);
    }

    private static long timeoutSeconds() {
        return Config.longValue(PROP_TIMEOUT_SECONDS, DEFAULT_TIMEOUT_SECONDS,
                MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS);
    }

    private static long lockWaitSeconds() {
        return Config.longValue(PROP_LOCK_WAIT_SECONDS, DEFAULT_LOCK_WAIT_SECONDS,
                MIN_TIMEOUT_SECONDS, MAX_LOCK_WAIT_SECONDS);
    }

    /** @return the output budget of one call, in characters (or bytes for {@link #runBytes}) */
    public static int maxOutputChars() {
        return Config.intValue(PROP_MAX_OUTPUT_CHARS, DEFAULT_MAX_OUTPUT_CHARS,
                MIN_MAX_OUTPUT_CHARS, MAX_MAX_OUTPUT_CHARS);
    }

    // ------------------------------------------------------------------
    // Running: text
    // ------------------------------------------------------------------

    /**
     * Runs one git command in the given repository root and treats every non-zero exit code and
     * every truncated machine format as an error.
     *
     * @param repositoryRoot the repository root ({@code git -C <root>}); must be an absolute path
     * @param arguments      the arguments after the prelude, e.g. {@code ["status", "-z"]}
     * @return the outcome of a successful run
     * @throws IOException if git cannot be started, the gate stays busy, the budget runs out, the
     *         process exits non-zero, or the output exceeds the character budget
     */
    public static Result run(Path repositoryRoot, List<String> arguments) throws IOException {
        Result result = execute(repositoryRoot, arguments, true);
        if (result.exitCode() != 0) {
            throw failure(result);
        }
        if (result.stdoutTruncated()) {
            throw new IOException("git " + result.subcommand() + " produced more output than the "
                    + "budget of " + maxOutputChars() + " characters allows; narrow the request "
                    + "(e.g. maxFiles/max) or raise '" + PROP_MAX_OUTPUT_CHARS + "'.");
        }
        return result;
    }

    /**
     * Like {@link #run(Path, List)} but hands a non-zero exit code back to the caller instead of
     * throwing - for questions where "no" is a normal answer ({@code rev-parse HEAD} in a
     * repository without a commit yet).
     *
     * @param repositoryRoot the repository root
     * @param arguments      the arguments after the prelude
     * @return the outcome, with the exit code set
     * @throws IOException if git cannot be started, the gate stays busy or the budget runs out
     */
    public static Result runLenient(Path repositoryRoot, List<String> arguments)
            throws IOException {
        return execute(repositoryRoot, arguments, true);
    }

    /**
     * Answers the one question "which repository is here?", with repository discovery left free to
     * climb above the given directory.
     *
     * <p>
     * This is the single exception to {@code GIT_CEILING_DIRECTORIES}, and it exists for the
     * diagnosis, not for data access: with the ceiling in place, a project that sits inside
     * another repository would be answered "not a git repository" - which hides the real reason
     * (a repository above the project, deliberately not used). The result of this call is compared
     * with the project directory and turned into an error when it does not match; nothing else is
     * read through it.
     * </p>
     *
     * @param directory the directory to ask about
     * @param arguments the arguments after the prelude (a {@code rev-parse} query)
     * @return the outcome, with the exit code set
     * @throws IOException if git cannot be started, the gate stays busy or the budget runs out
     */
    public static Result discover(Path directory, List<String> arguments) throws IOException {
        return execute(directory, arguments, false);
    }

    // ------------------------------------------------------------------
    // Running: bytes
    // ------------------------------------------------------------------

    /**
     * Runs one git command and returns its stdout as bytes - for a blob, which is not text.
     *
     * @param repositoryRoot the repository root
     * @param arguments      the arguments after the prelude
     * @param maxBytes       maximum number of bytes returned
     * @return the bytes, their true size and whether they were cut
     * @throws IOException if git cannot be started, the gate stays busy or the process exits
     *         non-zero
     */
    public static Bytes runBytes(Path repositoryRoot, List<String> arguments, int maxBytes)
            throws IOException {
        List<String> command = buildCommand(repositoryRoot, arguments);
        long timeout = timeoutSeconds();
        Path outFile = Files.createTempFile("mcp-git-", ".out");
        Path errFile = Files.createTempFile("mcp-git-", ".err");
        try {
            acquire();
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectOutput(ProcessBuilder.Redirect.to(outFile.toFile()));
            pb.redirectError(ProcessBuilder.Redirect.to(errFile.toFile()));
            prepareEnvironment(pb.environment(), repositoryRoot, true);
            Process process = start(pb, command);
            waitForProcess(process, timeout, command);
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                String stderr = decode(readBoundedBytes(errFile));
                throw new IOException("git " + subcommandOf(command) + " failed with exit code "
                        + exitCode + ": " + String.join(" ", command) + "\n"
                        + abbreviateTail(stderr));
            }
            long size = Files.size(outFile);
            byte[] data;
            try (InputStream in = Files.newInputStream(outFile)) {
                data = in.readNBytes(maxBytes);
            }
            return new Bytes(data, size, size > data.length);
        } finally {
            deleteQuietly(outFile);
            deleteQuietly(errFile);
            releaseGate();
        }
    }

    // ------------------------------------------------------------------
    // Running: streaming
    // ------------------------------------------------------------------

    /**
     * Runs one git command and hands its stdout to the consumer while the process runs.
     *
     * <p>
     * Streaming is what makes "look through the history" possible without materialising it: the
     * consumer stops when it has seen enough, and the process is killed then - a {@code log} over
     * ten years of history does not have to finish for a question about the last three commits.
     * A consumer that throws (e.g. because a JavaScript callback failed) also ends the process;
     * the exception is passed on unchanged.
     * </p>
     *
     * @param repositoryRoot the repository root
     * @param arguments      the arguments after the prelude
     * @param consumer       receives stdout in chunks; {@code false} stops
     * @return what happened (exit code, stopped, truncated, characters)
     * @throws IOException if git cannot be started, the gate stays busy, the budget runs out into
     *         a timeout, or the process exits non-zero without having been stopped by the consumer
     *         or cut by the character budget - a cut is an outcome ({@link
     *         StreamResult#truncated()}), not an error
     */
    public static StreamResult stream(Path repositoryRoot, List<String> arguments,
                                      ChunkConsumer consumer) throws IOException {
        List<String> command = buildCommand(repositoryRoot, arguments);
        long timeout = timeoutSeconds();
        int budget = maxOutputChars();
        Path errFile = Files.createTempFile("mcp-git-", ".err");
        try {
            acquire();
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectError(ProcessBuilder.Redirect.to(errFile.toFile()));
            prepareEnvironment(pb.environment(), repositoryRoot, true);
            long startedAt = System.nanoTime();
            Process process = start(pb, command);
            AtomicBoolean timedOut = new AtomicBoolean(false);
            Thread watchdog = startWatchdog(process, timeout, timedOut, command);

            boolean stopped = false;
            boolean truncated = false;
            long chars = 0L;
            RuntimeException consumerFailure = null;
            try (InputStream in = process.getInputStream();
                 Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                char[] buffer = new char[STREAM_CHUNK_CHARS];
                int read;
                while ((read = reader.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    chars += read;
                    if (chars > budget) {
                        // The budget cuts INSIDE this chunk: hand over the part that still fits, so
                        // a small budget is a cut and not a mute. The rest is dropped - and the
                        // consumer must not interpret a half record (GitLog.stream skips it).
                        truncated = true;
                        int keep = (int) Math.max(0L, budget - (chars - read));
                        if (keep > 0 && !consumer.accept(new String(buffer, 0, keep))) {
                            stopped = true;
                        }
                        chars -= read - keep;
                        break;
                    }
                    if (!consumer.accept(new String(buffer, 0, read))) {
                        stopped = true;
                        break;
                    }
                }
            } catch (RuntimeException e) {
                // A failing consumer must not leave a git process behind.
                consumerFailure = e;
            } catch (IOException e) {
                if (!timedOut.get() && !stopped && !truncated) {
                    throw e;
                }
                // the stream was cut on purpose - the outcome says so
            } finally {
                if (stopped || truncated || timedOut.get() || consumerFailure != null) {
                    process.destroyForcibly();
                }
            }
            boolean gone = awaitGone(process, timedOut.get() ? KILL_GRACE_SECONDS : 1L);
            watchdog.interrupt();
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000L;
            if (consumerFailure != null) {
                throw consumerFailure;
            }
            if (timedOut.get()) {
                throw new IOException("git " + subcommandOf(command) + " timed out after " + timeout
                        + " seconds while streaming; the process was "
                        + (gone ? "killed" : "left running (PID " + process.pid() + ")")
                        + ". Narrow the request (max, maxScan, path, since) or raise '"
                        + PROP_TIMEOUT_SECONDS + "'.");
            }
            int exitCode = process.exitValue();
            if (exitCode != 0 && !stopped && !truncated) {
                // Not judged after a cut: when the budget ended the stream, THIS code killed the
                // process - the exit code is then the signal (137 = SIGKILL), not a git verdict.
                // Reporting it as a failure would hide the real reason behind an empty stderr.
                String stderr = decode(readBoundedBytes(errFile));
                throw new IOException("git " + subcommandOf(command) + " failed with exit code "
                        + exitCode + ": " + String.join(" ", command) + "\n"
                        + abbreviateTail(stderr));
            }
            return new StreamResult(exitCode, stopped, truncated, chars, durationMs);
        } finally {
            deleteQuietly(errFile);
            releaseGate();
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** Takes the gate and runs the process, reading stdout as text. */
    private static Result execute(Path repositoryRoot, List<String> arguments,
                                  boolean isolateDiscovery) throws IOException {
        List<String> command = buildCommand(repositoryRoot, arguments);
        long timeout = timeoutSeconds();
        Path outFile = Files.createTempFile("mcp-git-", ".out");
        Path errFile = Files.createTempFile("mcp-git-", ".err");
        try {
            acquire();
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectOutput(ProcessBuilder.Redirect.to(outFile.toFile()));
            pb.redirectError(ProcessBuilder.Redirect.to(errFile.toFile()));
            prepareEnvironment(pb.environment(), repositoryRoot, isolateDiscovery);
            long startedAt = System.nanoTime();
            Process process = start(pb, command);
            waitForProcess(process, timeout, command);
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000L;
            int exitCode = process.exitValue();
            String stderr = decode(readBoundedBytes(errFile));
            int budget = maxOutputChars();
            long size = Files.size(outFile);
            byte[] raw = readBoundedBytes(outFile);
            String full = decode(raw);
            String stdout = (full.length() <= budget) ? full : full.substring(0, budget);
            boolean truncated = full.length() > budget || size > raw.length;
            if (truncated) {
                LOG.warn("git {} produced {} bytes, more than the budget of {} characters",
                        subcommandOf(command), size, budget);
            }
            return new Result(List.copyOf(command), exitCode, stdout, stderr, truncated, durationMs);
        } finally {
            deleteQuietly(outFile);
            deleteQuietly(errFile);
            releaseGate();
        }
    }

    /** Builds the full argument vector: executable, prelude, {@code -C <root>}, arguments. */
    private static List<String> buildCommand(Path repositoryRoot, List<String> arguments) {
        List<String> command = new ArrayList<>();
        command.add(binary());
        command.addAll(PRELUDE);
        command.add("-C");
        command.add(repositoryRoot.toString());
        command.add("--literal-pathspecs");
        command.addAll(arguments);
        return command;
    }

    /**
     * Acquires the gate or explains who holds it.
     *
     * @throws IOException if the gate stays busy for the configured lock wait
     */
    private static void acquire() throws IOException {
        long lockWait = lockWaitSeconds();
        boolean locked;
        try {
            locked = GATE.tryLock(lockWait, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the git gate.", e);
        }
        if (!locked) {
            long pid = RUNNING_PID.get();
            throw new IOException("Another git process is already running (PID "
                    + (pid != NO_PID ? pid : "unknown") + "); waited " + lockWait + " seconds.");
        }
    }

    /**
     * Releases the gate if this thread holds it, and forgets the PID then.
     *
     * <p>
     * Called from the {@code finally} of every run method - also when the gate was never taken
     * (because it was busy, or a temporary file could not be created): an
     * {@link IllegalMonitorStateException} in a cleanup path would replace the real error with a
     * confusing one, and clearing the PID of a <em>concurrent</em> process would lose the name of
     * the process the caller is actually waiting for.
     * </p>
     */
    private static void releaseGate() {
        if (GATE.isHeldByCurrentThread()) {
            RUNNING_PID.set(NO_PID);
            GATE.unlock();
        }
    }

    /** Starts the process and remembers its PID. */
    private static Process start(ProcessBuilder pb, List<String> command) throws IOException {
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            LOG.error("Failed to start git: {}", command.get(0), e);
            throw new IOException("Failed to start git ('" + command.get(0) + "'). Check the "
                    + "property '" + PROP_BINARY + "' and that git is installed.", e);
        }
        RUNNING_PID.set(process.pid());
        return process;
    }

    /** Waits for the process within the budget and reports a timeout with the PID. */
    private static void waitForProcess(Process process, long timeout, List<String> command)
            throws IOException {
        try {
            if (process.waitFor(timeout, TimeUnit.SECONDS)) {
                return;
            }
            process.destroyForcibly();
            boolean gone = awaitGone(process, KILL_GRACE_SECONDS);
            throw new IOException("git " + subcommandOf(command) + " timed out after " + timeout
                    + " seconds; the process was "
                    + (gone ? "killed" : "left running (PID " + process.pid() + ")")
                    + ". Narrow the request or raise '" + PROP_TIMEOUT_SECONDS + "'.");
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for git; a kill signal was sent to "
                    + "PID " + process.pid() + " (not waited for).", e);
        }
    }

    /**
     * Starts a daemon thread that kills the process when the budget runs out - a blocked
     * {@code read()} on the pipe cannot be interrupted, so the process has to go away instead.
     */
    private static Thread startWatchdog(Process process, long timeout, AtomicBoolean timedOut,
                                        List<String> command) {
        Thread watchdog = new Thread(() -> {
            try {
                if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                    timedOut.set(true);
                    LOG.warn("git {} exceeded its budget of {} s, killing PID {}",
                            subcommandOf(command), timeout, process.pid());
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "git-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        return watchdog;
    }

    /** Waits for a forcibly killed process to disappear. */
    private static boolean awaitGone(Process process, long graceSeconds) {
        try {
            return process.waitFor(graceSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return !process.isAlive();
        }
    }

    /**
     * Builds the process environment: every {@code GIT_*} variable is dropped (it could redirect
     * the command to another repository or program), then the fixed set is applied.
     *
     * @param environment      the environment to prepare (mutable)
     * @param repositoryRoot   the repository root
     * @param isolateDiscovery true to stop repository discovery below this directory; false only
     *                         for the diagnostic question "which repository is here?"
     */
    private static void prepareEnvironment(Map<String, String> environment, Path repositoryRoot,
                                           boolean isolateDiscovery) {
        List<String> inherited = new ArrayList<>(environment.keySet());
        for (String key : inherited) {
            if (key.startsWith("GIT_") || "LC_ALL".equals(key) || "LANG".equals(key)
                    || "LANGUAGE".equals(key)) {
                environment.remove(key);
            }
        }
        environment.put("LC_ALL", "C");
        environment.put("LANG", "C");
        // Nothing may ever wait for a password, and a reading command must not write the index.
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put("GIT_OPTIONAL_LOCKS", "0");
        if (isolateDiscovery) {
            // Repository discovery must not climb above the project directory: a project that
            // happens to live inside another repository would otherwise be answered by that
            // repository. Only GitProcess.discover() lifts this, and only to explain why.
            Path absoluteRoot = repositoryRoot.toAbsolutePath().normalize();
            Path parent = absoluteRoot.getParent();
            environment.put("GIT_CEILING_DIRECTORIES", (parent != null ? parent : absoluteRoot)
                    .toString());
        }
    }

    /** @return the subcommand of a full argument vector, for error messages */
    private static String subcommandOf(List<String> command) {
        int index = command.indexOf("--literal-pathspecs");
        return (index >= 0 && index + 1 < command.size()) ? command.get(index + 1) : "?";
    }

    /** Builds the message of a failed run. */
    private static IOException failure(Result result) {
        return new IOException("git " + result.subcommand() + " failed with exit code "
                + result.exitCode() + ": " + String.join(" ", result.command()) + "\n"
                + abbreviateTail(result.stderr()));
    }

    /**
     * Reads at most the bytes needed for the character budget (plus a little slack), so a runaway
     * output cannot exhaust the heap before it is cut.
     */
    private static byte[] readBoundedBytes(Path file) throws IOException {
        long limit = (long) maxOutputChars() * 4L + 4L;
        try (InputStream in = Files.newInputStream(file)) {
            return in.readNBytes((int) Math.min(limit, Integer.MAX_VALUE));
        }
    }

    /**
     * Decodes git output: UTF-8, and - because a path may carry bytes that are not UTF-8 - a
     * fallback to ISO-8859-1, which keeps every byte value visible instead of replacing it.
     *
     * @param bytes the raw bytes (bounded by {@link #readBoundedBytes(Path)})
     * @return the decoded text
     */
    private static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            LOG.debug("git output is not valid UTF-8, decoding as ISO-8859-1");
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }

    /** Last lines of a failed output, so the real error stays visible. */
    private static String abbreviateTail(String text) {
        String s = (text == null) ? "" : text.strip();
        if (s.length() <= ERROR_TAIL_CHARS) {
            return s;
        }
        return "...(truncated)...\n" + s.substring(s.length() - ERROR_TAIL_CHARS);
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.warn("Cannot delete temporary file {}: {}", file, e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Status of the gate
    // ------------------------------------------------------------------

    /** @return true if a git process holds the gate right now */
    public static boolean busy() {
        return RUNNING_PID.get() != NO_PID;
    }

    /** @return the PID of the running git process, or {@link #NO_PID} */
    public static long runningPid() {
        return RUNNING_PID.get();
    }
}
