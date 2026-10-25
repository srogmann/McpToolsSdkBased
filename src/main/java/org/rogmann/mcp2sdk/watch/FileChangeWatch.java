package org.rogmann.mcp2sdk.watch;

import org.rogmann.mcp2sdk.m2.PomWatch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The one place that knows this server wrote something into a project.
 *
 * <h2>Why this is a class and not eight call sites</h2>
 * <p>
 * Step 2 of the Maven guard needed "announce the content before it is lost" at every write path
 * (see {@code docs/maven-guard.md}). Putting a {@code PomWatch} call into {@code fs.writeFile},
 * {@code fs.copyFile}, {@code create_new_file} and the rest works, but it says the wrong thing:
 * those paths do not care about Maven, they care about <em>changing files</em>. Three separate
 * wishes arrive at the same eight places -
 * </p>
 * <ul>
 *   <li>the Maven guard wants the previous content, so an approval can show a diff;</li>
 *   <li>the operator wants to see what the tools have been doing to this checkout;</li>
 *   <li>and the planned write-scope rule ("this tool may write here, that one may not") wants to
 *       be asked <em>before</em> the bytes land, at every path, once.</li>
 * </ul>
 * <p>
 * A list of eight call sites is where those three grow apart, and a rule that is missing at one of
 * them is a rule that can be bypassed through that one. So the write paths say one thing -
 * {@link #beforeWrite(Path, Kind)} - and this class decides what it means. Adding a ninth write
 * path now means adding one line here, not re-deriving the policy in a new place.
 * </p>
 *
 * <h2>The seam for the write-scope rule</h2>
 * <p>
 * {@link #beforeWrite(Path, Kind)} is called <em>before</em> writing and returns void today; it is
 * the place where a refused write will throw. That is deliberately not speculative: no caller has
 * to handle a refusal that does not exist yet, but every caller is already standing at the one
 * point where the question is asked. The eight current call sites are the complete set to audit
 * when that lands.
 * </p>
 *
 * <h2>What the list is, and what it is not</h2>
 * <p>
 * A bounded, in-memory, newest-first ring of writes performed by <em>this process</em> since it
 * started. It is an operator's overview and a debugging aid, not an audit log: it does not survive
 * a restart, it does not record who asked (the tool name would have to be passed in by every
 * caller, and half of them would forget), and it says nothing about files changed outside this
 * server. The audit trail of a decision stays where it belongs - on the review request
 * ({@link org.rogmann.mcp2sdk.review.ReviewService}) - and the history of content stays in git.
 * A monitoring page can list this; it must not be the place a security claim rests on.
 * </p>
 *
 * <p>
 * Read-only paths are not here on purpose: a watch that reports "the model read 400 files" tells
 * the operator nothing they can act on, and the {@code search}/{@code fs}-read logging is the right
 * place if that ever becomes interesting.
 * </p>
 */
public final class FileChangeWatch {

    /** How a file is being changed. Derived at the call site, where the intent is known. */
    public enum Kind {
        /** The file did not exist before this write. */
        CREATED,
        /** Existing content is replaced as a whole. */
        OVERWRITTEN,
        /** Existing content is modified in place or by a search/replace patch. */
        MODIFIED,
        /** Bytes appended to an existing file. */
        APPENDED,
        /** An existing target was replaced by a copy. */
        COPIED_OVER,
        /** An existing target was replaced by a move. */
        MOVED_OVER,
        /** The file was removed. Not used by the file tools today, kept for the day they do. */
        DELETED
    }

    /**
     * One announced write.
     *
     * @param path      absolute path on the server (for the operator's pages)
     * @param kind      what was done to it
     * @param at        when it was announced, before the bytes landed
     * @param sizeBefore byte size before the write, -1 when unknown or the file did not exist
     * @param captured  true when the previous content could be kept for a diff
     */
    public record Change(Path path, Kind kind, Instant at, long sizeBefore, boolean captured) {

        /** @return lower-case kind, the spelling the web pages and tool results use */
        public String kindName() {
            return kind.name().toLowerCase(Locale.ROOT);
        }

        /**
         * @param roots watched project roots to relate the path to (may be empty)
         * @return the map for a monitoring page or a tool result: absolute path for humans, plus a
         *         project-relative spelling so the same map can be handed to a model
         */
        public Map<String, Object> toMap(List<Path> roots) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("file", path.toString());
            String[] relative = relativeTo(roots);
            if (relative != null) {
                m.put("root", relative[0]);
                m.put("relativePath", relative[1]);
            }
            m.put("change", kindName());
            m.put("at", at.toString());
            if (sizeBefore >= 0) {
                m.put("sizeBefore", sizeBefore);
            }
            m.put("contentCaptured", captured);
            return m;
        }

        /** Root label and project-relative path, or null when the file belongs to no watched root. */
        private String[] relativeTo(List<Path> roots) {
            for (Path root : roots) {
                if (path.startsWith(root)) {
                    return new String[]{PomWatch.rootLabel(root),
                            root.relativize(path).toString().replace('\\', '/')};
                }
            }
            return null;
        }
    }

    /**
     * How many writes are kept. Twenty projects' worth of file tool calls; beyond that the oldest
     * entries fall out. Sized as a debugging aid, not as storage - see the class javadoc.
     */
    public static final int DEFAULT_HISTORY = 200;

    /**
     * Guards the ring, the counters and the limit.
     *
     * <p>
     * A {@link ReentrantLock} with {@code try/finally} rather than a monitor on a bare object, for
     * the two reasons {@code PomWatch.STATE_LOCK} and the Maven gate already give: it is what the
     * guarded statics of this server do, and the counters that a write list will want next (bytes
     * touched, writes a future scope rule refused) belong on the lock instance, not on a monitor
     * whose only other job is exclusion. Re-entrancy is used, not just allowed: {@link #info(int)}
     * takes the lock once and reads through the guarded accessors below, which makes the monitoring
     * snapshot one snapshot instead of four that happen to be near each other.
     * </p>
     *
     * <p>
     * What is deliberately <b>not</b> converted, from {@code docs/open-points.md} §2: the monitor
     * {@code MavenGuard.await()} shares with the decide consumer. There {@code wait()}/{@code
     * notifyAll()} plus the order "moveFromPending, then adoptBaseline, then signalAll" <em>is</em>
     * the tested content ({@code aBlockedCallerIsWokenByTheDecisionAndAllowedToRun}), and a
     * {@code Condition} would buy nothing but the risk of a lost wakeup. {@code ReviewStore} and
     * {@code ReviewEntry} use {@code volatile} and CAS and hold no lock at all.
     * </p>
     */
    private static final Lock LOCK = new ReentrantLock();
    private static final Deque<Change> RING = new ArrayDeque<>(DEFAULT_HISTORY);
    private static int historyLimit = DEFAULT_HISTORY;
    private static long writeCount = 0L;

    private FileChangeWatch() {
    }

    // ------------------------------------------------------------------
    // The seam
    // ------------------------------------------------------------------

    /**
     * Announce a write about to happen, with the intent the caller knows.
     *
     * <p>
     * Call this <b>before</b> the bytes land - after it, the previous content is gone and no diff
     * can be produced. Nothing here is fatal for a caller: an unreadable or enormous file simply
     * yields no capture, and the change is still recorded, because "we lost the diff" must not turn
     * into "the write silently vanished from the list".
     * </p>
     *
     * @param file the file that is about to be written
     * @param kind what is being done to it
     */
    public static void beforeWrite(Path file, Kind kind) {
        if (file == null || kind == null) {
            return;
        }
        Path absolute = file.toAbsolutePath().normalize();
        long sizeBefore = -1L;
        try {
            if (Files.isRegularFile(absolute)) {
                sizeBefore = Files.size(absolute);
            }
        } catch (IOException | RuntimeException e) {
            sizeBefore = -1L;                       // unknown is a value here, not an error
        }
        // The Maven guard keeps the previous content for its diff. This is the only place a
        // write path is expected to arrive at: PomWatch decides on its own which files are worth
        // remembering (it filters for pom.xml, .mvn and the Maven user config), so no arbitrary
        // project content ends up held here, and a path that skips this call loses its diff.
        PomWatch.capturePreviousContent(absolute);
        record(new Change(absolute, kind, Instant.now(), sizeBefore,
                PomWatch.hasCapturedContent(absolute)));
    }

    /**
     * The same, with the kind derived from "the file existed or it did not" - the form for write
     * paths that replace content as a whole.
     *
     * @param file the file that is about to be written
     */
    public static void beforeWrite(Path file) {
        boolean existed;
        try {
            existed = Files.isRegularFile(file);
        } catch (SecurityException e) {
            existed = false;
        }
        beforeWrite(file, existed ? Kind.OVERWRITTEN : Kind.CREATED);
    }

    /**
     * A delete, for the day a tool is allowed to do it. Today no file tool deletes anything, and the
     * method exists so that the one that starts doing it cannot forget to say so.
     *
     * @param file the file that is about to be removed
     */
    public static void beforeDelete(Path file) {
        beforeWrite(file, Kind.DELETED);
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /**
     * The recorded writes, newest first.
     *
     * @param limit how many to return, values outside {@code [1, history]} are clamped
     * @return an immutable snapshot
     */
    public static List<Change> recent(int limit) {
        LOCK.lock();
        try {
            List<Change> all = new ArrayList<>(RING);
            int n = Math.max(1, Math.min(limit, all.size()));
            return List.copyOf(all.subList(0, n));
        } finally {
            LOCK.unlock();
        }
    }

    /** @return every recorded write, newest first */
    public static List<Change> recent() {
        return recent(historyLimit());
    }

    /** @return number of writes announced since the process started (including dropped ones) */
    public static long writeCount() {
        LOCK.lock();
        try {
            return writeCount;
        } finally {
            LOCK.unlock();
        }
    }

    /** @return number of writes still in the list */
    public static int recorded() {
        LOCK.lock();
        try {
            return RING.size();
        } finally {
            LOCK.unlock();
        }
    }

    /** How many writes the list keeps; guarded because {@link #setHistory(int)} writes it. */
    private static int historyLimit() {
        LOCK.lock();
        try {
            return historyLimit;
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * The monitoring view: what a page or a tool result shows without knowing about this class.
     *
     * @param limit entries to include
     * @return {@code {writes, recorded, history, changed[]}} with project-relative paths alongside
     *         the absolute ones
     */
    public static Map<String, Object> info(int limit) {
        // The watched roots are resolved outside the lock (that call stats the file system, and a
        // write path has no business waiting for it). Everything read below is guarded, taken in
        // one locked section: the numbers and the entries then belong to the same moment, which
        // four separate lock passes would not promise.
        List<Path> roots = PomWatch.roots();
        List<Change> changes;
        Map<String, Object> m = new LinkedHashMap<>();
        LOCK.lock();
        try {
            m.put("writes", writeCount());
            m.put("recorded", recorded());
            m.put("history", historyLimit());
            changes = recent(limit);
        } finally {
            LOCK.unlock();
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Change change : changes) {
            entries.add(change.toMap(roots));
        }
        m.put("changed", entries);
        return m;
    }

    /** The monitoring view with the default limit. */
    public static Map<String, Object> info() {
        return info(40);
    }

    /**
     * How many writes the list keeps. Out of {@code [10, 5000]} leaves the default alone - a limit
     * an operator sets wrong should shrink the list, not empty it.
     *
     * @param entries new size
     */
    public static void setHistory(int entries) {
        LOCK.lock();
        try {
            if (entries < 10 || entries > 5000) {
                return;
            }
            historyLimit = entries;
            while (RING.size() > historyLimit) {
                RING.removeLast();
            }
        } finally {
            LOCK.unlock();
        }
    }

    /** Forgets everything. Tests only: like {@code PomWatch.reset()} this closes nothing. */
    public static void resetForTests() {
        LOCK.lock();
        try {
            RING.clear();
            writeCount = 0L;
            historyLimit = DEFAULT_HISTORY;
        } finally {
            LOCK.unlock();
        }
    }

    private static void record(Change change) {
        LOCK.lock();
        try {
            writeCount++;
            RING.addFirst(change);
            while (RING.size() > historyLimit) {
                RING.removeLast();
            }
        } finally {
            LOCK.unlock();
        }
    }
}
