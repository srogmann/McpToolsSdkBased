package org.rogmann.mcp2sdk.agent;

import org.rogmann.mcp2sdk.util.BoundedText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The board of one {@link PrefixForkTool} batch: the {@link AgentTask} entries the caller seeded
 * and the threads added, every change under one lock (docs/subagent.md).
 *
 * <p><b>Stage 1</b> (this build): threads post and comment; nobody pulls. Claiming exists as an
 * operation and is refused unless the system property {@value #PROP_PULL} is set - and then only
 * for a thread whose own (seeded) task has already ended, because "you carry out only the following
 * task" is the strongest lever the measured runs left (docs/subagent.md §8). The caller reads the
 * board after the batch, in the digest of the tool result and in the artifact; what becomes work is
 * its decision.</p>
 *
 * <p><b>Why one lock</b> instead of a concurrent structure: claiming is a check-then-act
 * (OPEN -&gt; IN_PROGRESS plus claimer), and a batch runs at most four threads - contention is not
 * a property of this object, correctness of the transition is. A {@code ConcurrentMap} alone would
 * let two threads claim the same entry (decision B7). {@link ReentrantLock} with a
 * {@link Condition} rather than {@code synchronized}: the waiting surface
 * ({@link #awaitMessages}) needs to park on the same object that guards the state, and a
 * {@code Condition} says that out loud where {@code wait()/notifyAll()} would hide it in the
 * keyword. The lock is one for all boards and never held across I/O.</p>
 *
 * <p><b>Lifetime</b>: born in {@code PrefixForkTool.call}, frozen when the batch ends - also on the
 * error path - and written to disk only then (the transcripts' rule, learned in run b-4391: while
 * the batch runs, a thread finds nothing about its siblings in the project; the board is the one,
 * opt-in exception, and only inside the tool).</p>
 *
 * <p><b>One renderer, two surfaces.</b> Whatever the board shows is built once as plain
 * {@code Map}/{@code List} structures ({@link #jsView()}, {@link #entryView(AgentTask, int)}) and
 * the JSON of the MCP tool is converted from those same maps. The board is reachable as a tool and
 * as a JavaScript module (docs/subagent.md §14); two renderers would be two truths about one board,
 * which is exactly what the comparison M4 must not measure. The package stays free of Graal: the
 * JavaScript side converts the maps in {@code JsAgentBoardBridge}, this class imports none of it.</p>
 */
final class AgentBoard {

    private static final Logger LOG = LoggerFactory.getLogger(AgentBoard.class);

    /** System property that enables pulling (stage 2); off by default (stage 1). */
    static final String PROP_PULL = "agent.fork.board.pull";

    /** Ceilings: a board is a bounded object, its size is part of the protection (decision B4). */
    static final int MAX_ENTRIES = 16;
    /** Ceilings: a board is a bounded object, its size is part of the protection (decision B4). */
    static final int MAX_MESSAGES_PER_ENTRY = 8;

    /** A wait nobody sized (decision D4: 60 s, and less when the thread has less). */
    static final long DEFAULT_WAIT_MS = 60_000L;
    /** Hard ceiling of a single wait; a thread's own deadline can only lower it. */
    static final long MAX_WAIT_MS = 120_000L;

    private static final int TEXT_MAX_CHARS = 2_000;
    private static final int DIGEST_TEXT_CHARS = 120;

    /** Guards every board's state; one lock for the whole board machinery, held for microseconds. */
    private static final Lock LOCK = new ReentrantLock();

    /** The one wake-up channel: every write signals, every sleeper re-checks its own entry. */
    private static final Condition CHANGED = LOCK.newCondition();

    /** The live boards by batch id; an entry is removed the moment its batch ends. */
    private static final Map<String, AgentBoard> LIVE = new ConcurrentHashMap<>();

    private final String batchId;
    /** MCP session of the {@code prefix_fork} call; the one session the board refuses. */
    private final String parentSessionId;
    private final List<AgentTask> entries = new ArrayList<>();
    private final Map<String, AgentTask> byId = new HashMap<>();
    private final Map<String, AgentTask> byOrigin = new HashMap<>();
    /** Where each running thread has to stop, so a wait cannot outlive the thread that asked. */
    private final Map<String, Long> childDeadlineNano = new ConcurrentHashMap<>();
    private int counter = 0;
    private boolean frozen = false;

    AgentBoard(String batchId, String parentSessionId) {
        this.batchId = batchId;
        this.parentSessionId = parentSessionId;
    }

    // --- the live registry: the tool finds the board of a thread's batch through the runner tag ---

    static void register(AgentBoard board) {
        LIVE.put(board.batchId, board);
    }

    static void remove(String batchId) {
        LIVE.remove(batchId);
    }

    /** @return the live board of the batch, or {@code null} (never a frozen one) */
    static AgentBoard live(String batchId) {
        return (batchId == null) ? null : LIVE.get(batchId);
    }

    String batchId() {
        return batchId;
    }

    String parentSessionId() {
        return parentSessionId;
    }

    boolean isFrozen() {
        LOCK.lock();
        try {
            return frozen;
        } finally {
            LOCK.unlock();
        }
    }

    // --- writes; every method under the lock, every refusal a RuntimeException with a usable text ---

    /**
     * Seeds one task of the caller's round as an OPEN entry ({@code origin} names the task id), so
     * the board starts as the queue the fork call already is.
     */
    AgentTask seed(String originTaskId, String instruction) {
        LOCK.lock();
        try {
            requireWritable();
            return add(AgentTaskKind.TASK, instruction,
                    new AgentId(batchId, AgentId.PARENT_TASK_ID, AgentId.PARENT_CHILD_INDEX),
                    originTaskId);
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * Puts a new entry on the board: kind TASK (proposed work) or INFO (announcement, not
     * claimable). The id space is the board's own - m1, m2, ... - never t1..t4, which the caller's
     * round already uses; a seeded entry names its origin instead (decision B2).
     */
    AgentTask post(AgentId author, String kindText, String subject) {
        LOCK.lock();
        try {
            requireWritable();
            if (entries.size() >= MAX_ENTRIES) {
                throw new IllegalStateException("the board of batch " + batchId + " holds at most "
                        + MAX_ENTRIES + " entries; the caller reads what is there after the batch");
            }
            AgentTask task = add(parseKind(kindText), subject, author, null);
            signalWaiters();
            return task;
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * Adds a line under an entry - a comment is a message on the entry, not an entry of its own
     * (decision B1). Records contact in {@code lastContact}.
     */
    AgentTask comment(AgentId author, String taskId, String text) {
        LOCK.lock();
        try {
            requireWritable();
            AgentTask task = existing(taskId);
            requireText(text, "text");
            if (task.messages().size() >= MAX_MESSAGES_PER_ENTRY) {
                throw new IllegalStateException("entry " + taskId + " holds at most "
                        + MAX_MESSAGES_PER_ENTRY + " messages; post a fresh entry if it is a new thought");
            }
            task.addMessage(new AgentMessage(Instant.now(), author, bounded(text, TEXT_MAX_CHARS)));
            task.touch(author, Instant.now());
            signalWaiters();
            return task;
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * Takes an OPEN TASK entry: OPEN -&gt; IN_PROGRESS plus claimer, atomically under the lock.
     * Refused while pulling is disabled (stage 1), for INFO entries, for entries that are not OPEN,
     * and - with pulling enabled - for a thread whose own task has not ended yet (stage 2 rule).
     */
    AgentTask claim(AgentId author, String taskId) {
        LOCK.lock();
        try {
            requireWritable();
            if (!Boolean.getBoolean(PROP_PULL)) {
                throw new IllegalStateException("claiming is not enabled (stage 1): threads post, the"
                        + " caller decides after the batch what becomes a task");
            }
            AgentTask task = existing(taskId);
            if (task.kind() != AgentTaskKind.TASK) {
                throw new IllegalStateException("INFO entries are announcements, not work: '" + taskId
                        + "' cannot be claimed");
            }
            if (task.status() != AgentTaskStatus.OPEN) {
                throw new IllegalStateException("entry '" + taskId + "' is " + task.status()
                        + ", not OPEN - read the board again (action list)");
            }
            AgentTask own = byOrigin.get(author.taskId());
            if (own != null && own.status() != AgentTaskStatus.CLOSED) {
                throw new IllegalStateException("your own task '" + author.taskId() + "' has not ended"
                        + " yet; pulling starts after your own work (stage 2)");
            }
            task.setStatus(AgentTaskStatus.IN_PROGRESS);
            task.setClaimedBy(author);
            task.touch(author, Instant.now());
            signalWaiters();
            LOG.info("Board {}: '{}' claimed by {}", batchId, taskId, author);
            return task;
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * Closes a TASK entry. Allowed for its claimer, its creator, or the thread that owns the seeded
     * task; the closing line becomes the entry's last message. INFO entries are not closed.
     */
    AgentTask close(AgentId author, String taskId, String outcomeText) {
        LOCK.lock();
        try {
            requireWritable();
            AgentTask task = existing(taskId);
            if (task.kind() == AgentTaskKind.INFO) {
                throw new IllegalStateException("INFO entries are announcements; the batch ends them,"
                        + " nobody closes '" + taskId + "' by hand");
            }
            if (task.status() == AgentTaskStatus.CLOSED) {
                throw new IllegalStateException("entry '" + taskId + "' is already CLOSED");
            }
            boolean allowed = author.equals(task.claimedBy()) || author.equals(task.createdBy())
                    || (task.origin() != null && task.origin().equals(author.taskId()));
            if (!allowed) {
                throw new IllegalStateException("only the claimer, the creator or the owner of the"
                        + " seeded task may close '" + taskId + "'");
            }
            task.setStatus(AgentTaskStatus.CLOSED);
            String line = (outcomeText == null || outcomeText.isBlank()) ? "closed" : outcomeText;
            task.addMessage(new AgentMessage(Instant.now(), author, bounded(line, TEXT_MAX_CHARS)));
            task.touch(author, Instant.now());
            signalWaiters();
            return task;
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * The runner reports a thread's end: its own (seeded) task closes with the thread's status, and
     * every claim the thread held goes back to OPEN with a note - a thread that died mid-claim must
     * not strand the entry as IN_PROGRESS.
     */
    void finishChild(AgentId author, String status, String note) {
        LOCK.lock();
        try {
            childDeadlineNano.remove(author.taskId());
            if (frozen) {
                signalWaiters();
                return;
            }
            AgentTask own = byOrigin.get(author.taskId());
            if (own != null && own.status() != AgentTaskStatus.CLOSED) {
                own.setStatus(AgentTaskStatus.CLOSED);
                String line = "thread ended: " + status
                        + ((note == null || note.isBlank()) ? "" : " - " + oneLine(note));
                own.addMessage(new AgentMessage(Instant.now(), author, bounded(line, TEXT_MAX_CHARS)));
                own.touch(author, Instant.now());
            }
            for (AgentTask entry : entries) {
                if (author.equals(entry.claimedBy()) && entry.status() == AgentTaskStatus.IN_PROGRESS) {
                    entry.setStatus(AgentTaskStatus.OPEN);
                    entry.setClaimedBy(null);
                    entry.addMessage(new AgentMessage(Instant.now(), author, bounded(
                            "claim released: the thread that claimed this ended (" + status + ")",
                            TEXT_MAX_CHARS)));
                }
            }
            signalWaiters();
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * The runner reports where a thread's own deadline is, so {@link #awaitMessages} can refuse a
     * wait that would outlive the thread instead of blocking past it (docs/subagent.md §14, P1).
     *
     * @param author       the thread that started
     * @param deadlineNano {@link System#nanoTime()} reading this thread has to stop at
     */
    void startChild(AgentId author, long deadlineNano) {
        LOCK.lock();
        try {
            if (frozen) {
                return;
            }
            childDeadlineNano.put(author.taskId(), deadlineNano);
        } finally {
            LOCK.unlock();
        }
    }

    /** Closes the board for writes; the caller of the fork freezes it when the batch ends. */
    void freeze() {
        LOCK.lock();
        try {
            frozen = true;
            signalWaiters();
        } finally {
            LOCK.unlock();
        }
    }

    // --- waiting: the one thing a board can offer a script that it cannot offer a model turn ---

    /**
     * What a sleep found.
     *
     * @param changed     the entry moved (a message arrived, a claim, a close)
     * @param waitedMillis how long the call actually slept
     * @param limitMillis the ceiling the sleep was granted under
     * @param task        the entry as it stands when the sleep ends
     */
    record Awaited(boolean changed, long waitedMillis, long limitMillis, AgentTask task) {
    }

    /**
     * Sleeps <em>inside one call</em> until the entry changes - the reason the JavaScript surface
     * exists at all (docs/subagent.md §14): in {@code b-505c} a glance at the board cost a model
     * turn, and three glances cost 822k prompt tokens; a sleep inside a script costs the thread's
     * own time and nothing else.
     *
     * <p>The thread's own deadline is the ceiling. A wait <em>asked for</em> beyond it is refused
     * rather than truncated: sleeping past the deadline of the thread would hand back "nothing
     * happened" as if it were an answer. A wait nobody sized is clamped - {@value #DEFAULT_WAIT_MS}
     * ms, less when the thread has less.</p>
     *
     * <p>Parked on {@link #CHANGED}, the condition of the lock that guards the entries, so a write
     * and the wake-up cannot pass each other: the sleeper holds the lock until it parks, and every
     * writer signals while still holding it.</p>
     *
     * @param author          the waiting thread (whose deadline caps the sleep; the read marks contact)
     * @param taskId          the entry to watch
     * @param requestedMillis how long to sleep, or {@code null} for the default
     * @return what the sleep found
     */
    Awaited awaitMessages(AgentId author, String taskId, Long requestedMillis) {
        LOCK.lock();
        try {
            requireWritable();
            AgentTask task = existing(taskId);
            long left = remainingMillisOfLocked(author);
            long requested;
            if (requestedMillis == null) {
                requested = Math.min(DEFAULT_WAIT_MS, left);
            } else {
                requested = requestedMillis;
                if (requested > MAX_WAIT_MS) {
                    throw new IllegalStateException("a wait is capped at " + MAX_WAIT_MS
                            + " ms, asked for " + requestedMillis + "; wait again if it is still not enough");
                }
            }
            if (requested <= 0L || requested > left) {
                throw new IllegalStateException("this thread has " + describeMillis(left)
                        + " left; a wait of " + requested + " ms would outlive it - wait for less,"
                        + " or report");
            }
            long seen = task.revision();
            long startedNano = System.nanoTime();
            long deadlineNano = startedNano + TimeUnit.MILLISECONDS.toNanos(requested);
            while (task.revision() == seen && !frozen) {
                long nanosLeft = deadlineNano - System.nanoTime();
                if (nanosLeft <= 0L) {
                    break;
                }
                try {
                    CHANGED.awaitNanos(nanosLeft);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            boolean changed = task.revision() != seen;
            long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNano);
            task.touch(author, Instant.now());
            LOG.debug("Board {}: {} waited {} ms on '{}' for {}", batchId, author, waited, taskId,
                    changed ? "an answer" : "nothing");
            return new Awaited(changed, waited, requested, task);
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * @param author the thread to ask for
     * @return milliseconds this thread has left, {@link Long#MAX_VALUE} when the runner never said
     */
    long remainingMillisOf(AgentId author) {
        LOCK.lock();
        try {
            return remainingMillisOfLocked(author);
        } finally {
            LOCK.unlock();
        }
    }

    /** {@link #remainingMillisOf} without the lock; call only while holding it. */
    private long remainingMillisOfLocked(AgentId author) {
        Long deadline = childDeadlineNano.get(author.taskId());
        if (deadline == null) {
            return Long.MAX_VALUE;
        }
        long nanosLeft = deadline - System.nanoTime();
        return (nanosLeft <= 0L) ? 0L : TimeUnit.NANOSECONDS.toMillis(nanosLeft);
    }

    /** Wakes everyone sleeping in {@link #awaitMessages}; called while holding the lock. */
    private static void signalWaiters() {
        CHANGED.signalAll();
    }

    // --- reads; the tool's answers, the digest (fork.board) and the JavaScript module all come here ---

    /**
     * The short form for the tool result: counts plus the entries that are not CLOSED (the artifact
     * holds the full board).
     */
    ObjectNode digest(JsonMapper mapper) {
        LOCK.lock();
        try {
            Map<String, Object> root = summaryMap();
            List<Object> items = new ArrayList<>();
            for (AgentTask entry : entries) {
                if (entry.status() != AgentTaskStatus.CLOSED) {
                    items.add(entryView(entry, DIGEST_TEXT_CHARS, false));
                }
            }
            root.put("items", items);
            return node(root, mapper);
        } finally {
            LOCK.unlock();
        }
    }

    /** The full board, for the {@code list} action and for the artifact file. */
    ObjectNode artifact(JsonMapper mapper) {
        LOCK.lock();
        try {
            return node(viewMap(), mapper);
        } finally {
            LOCK.unlock();
        }
    }

    /** The full board as plain structures - what the JavaScript module hands to a script. */
    Map<String, Object> jsView() {
        LOCK.lock();
        try {
            return viewMap();
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * One entry as plain structures, thread included; reading an entry is contact (decision B3).
     *
     * @param reader who asked (marks contact on the entry)
     * @param taskId entry id
     * @return the counts of the board plus the entry
     */
    Map<String, Object> jsGet(AgentId reader, String taskId) {
        LOCK.lock();
        try {
            AgentTask task = existing(taskId);
            task.touch(reader, Instant.now());
            Map<String, Object> root = summaryMap();
            root.put("entry", entryView(task, TEXT_MAX_CHARS, true));
            return root;
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * Registers a read as contact for every entry that came back in the answer (decision B3:
     * contact, not a proof of reading - a model that remembers an entry without calling is nowhere).
     *
     * @param reader who asked
     */
    void noteRead(AgentId reader) {
        LOCK.lock();
        try {
            Instant now = Instant.now();
            for (AgentTask entry : entries) {
                entry.touch(reader, now);
            }
        } finally {
            LOCK.unlock();
        }
    }

    /** @return the counts of the board, the frame the tool's answers and the digest render into */
    ObjectNode summary(JsonMapper mapper) {
        LOCK.lock();
        try {
            return node(summaryMap(), mapper);
        } finally {
            LOCK.unlock();
        }
    }

    /** @return the same counts as plain structures, for the JavaScript surface (see {@link #summary}) */
    Map<String, Object> counts() {
        LOCK.lock();
        try {
            return summaryMap();
        } finally {
            LOCK.unlock();
        }
    }

    /** @return one entry with its whole thread, for {@code board.get()} and a finished {@code wait} */
    Map<String, Object> entryWithThread(AgentTask entry) {
        LOCK.lock();
        try {
            return entryView(entry, TEXT_MAX_CHARS, true);
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * @param taskId task id of the caller's round
     * @return the entry seeded from that task, or {@code null} when nothing was seeded for it
     */
    AgentTask seededFor(String taskId) {
        LOCK.lock();
        try {
            return byOrigin.get(taskId);
        } finally {
            LOCK.unlock();
        }
    }

    /** @return one entry, thread omitted - what both surfaces answer a write with */
    Map<String, Object> entryView(AgentTask entry, int textLimit) {
        LOCK.lock();
        try {
            return entryView(entry, textLimit, false);
        } finally {
            LOCK.unlock();
        }
    }

    /** @return the text ceiling a thread's own writing is kept to */
    static int textCeiling() {
        return TEXT_MAX_CHARS;
    }

    // --- internals; call these only while holding the lock ---

    private Map<String, Object> viewMap() {
        Map<String, Object> root = summaryMap();
        List<Object> items = new ArrayList<>();
        for (AgentTask entry : entries) {
            items.add(entryView(entry, TEXT_MAX_CHARS, true));
        }
        root.put("entries", items);
        return root;
    }

    private Map<String, Object> summaryMap() {
        int open = 0;
        int inProgress = 0;
        int closed = 0;
        for (AgentTask entry : entries) {
            switch (entry.status()) {
                case OPEN -> open++;
                case IN_PROGRESS -> inProgress++;
                case CLOSED -> closed++;
            }
        }
        Map<String, Object> byStatus = new LinkedHashMap<>();
        byStatus.put("open", open);
        byStatus.put("inProgress", inProgress);
        byStatus.put("closed", closed);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("batch", batchId);
        root.put("entries", entries.size());
        root.put("byStatus", byStatus);
        return root;
    }

    private Map<String, Object> entryView(AgentTask entry, int textLimit, boolean withThread) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", entry.id());
        view.put("kind", entry.kind().name());
        view.put("status", entry.status().name());
        view.put("subject", bounded(entry.subject(), textLimit));
        view.put("createdBy", entry.createdBy().toString());
        if (entry.origin() != null) {
            view.put("origin", entry.origin());
        }
        if (entry.claimedBy() != null) {
            view.put("claimedBy", entry.claimedBy().toString());
        }
        view.put("createdAt", entry.createdAt().toString());
        Map<String, Object> contact = new LinkedHashMap<>();
        for (Map.Entry<AgentId, Instant> touch : entry.lastContact().entrySet()) {
            contact.put(touch.getKey().toString(), touch.getValue().toString());
        }
        view.put("lastContact", contact);
        List<AgentMessage> messages = entry.messages();
        view.put("messages", messages.size());
        if (withThread) {
            List<Object> thread = new ArrayList<>();
            for (AgentMessage message : messages) {
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("at", message.at().toString());
                line.put("author", message.author().toString());
                line.put("text", bounded(message.text(), textLimit));
                thread.add(line);
            }
            view.put("thread", thread);
        } else if (!messages.isEmpty()) {
            AgentMessage last = messages.get(messages.size() - 1);
            view.put("last", bounded(last.author() + ": " + last.text(), textLimit));
        }
        return view;
    }

    private AgentTask add(AgentTaskKind kind, String subject, AgentId author, String origin) {
        requireText(subject, "subject");
        counter++;
        AgentTask task = new AgentTask("m" + counter, origin, kind, bounded(subject, TEXT_MAX_CHARS),
                Instant.now(), author);
        entries.add(task);
        byId.put(task.id(), task);
        if (origin != null) {
            byOrigin.put(origin, task);
        }
        task.touch(author, task.createdAt());
        return task;
    }

    private void requireWritable() {
        if (frozen) {
            throw new IllegalStateException("the board of batch " + batchId
                    + " is closed: the batch has ended");
        }
    }

    private AgentTask existing(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("an entry id is required (m1, m2, ...)");
        }
        AgentTask task = byId.get(taskId.trim());
        if (task == null) {
            throw new IllegalArgumentException("no entry '" + taskId.trim() + "' on the board of"
                    + " batch " + batchId + " (known: " + byId.keySet() + ")");
        }
        return task;
    }

    private static AgentTaskKind parseKind(String text) {
        if (text == null || text.isBlank()) {
            return AgentTaskKind.TASK;
        }
        try {
            return AgentTaskKind.valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("kind must be TASK or INFO, got: " + text);
        }
    }

    private static void requireText(String text, String what) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("a non-blank '" + what + "' is required");
        }
    }

    private static String bounded(String text, int max) {
        BoundedText bounded = new BoundedText(Math.max(1, max));
        bounded.append(text == null ? "" : text);
        return bounded.text();
    }

    private static String oneLine(String text) {
        return text.replace("\r", "").replace("\n", " | ");
    }

    private static String describeMillis(long millis) {
        if (millis == Long.MAX_VALUE) {
            return "an unknown amount of time";
        }
        if (millis >= 2_000L) {
            return (millis / 1_000L) + " s";
        }
        return millis + " ms";
    }

    /**
     * Converts one of the board's plain views into the JSON the MCP tool answers with. Exists so the
     * two surfaces cannot disagree: one renderer above this line, one conversion below it - and the
     * conversion knows nothing about boards.
     *
     * @param view   the plain {@code Map}/{@code List} view
     * @param mapper the mapper to build the tree with
     * @return the same content as a JSON object
     */
    static ObjectNode node(Map<String, ?> view, JsonMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        for (Map.Entry<String, ?> entry : view.entrySet()) {
            put(root, entry.getKey(), entry.getValue(), mapper);
        }
        return root;
    }

    private static void put(ObjectNode target, String key, Object value, JsonMapper mapper) {
        if (value == null) {
            target.putNull(key);
        } else if (value instanceof Map<?, ?> map) {
            ObjectNode child = target.putObject(key);
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                put(child, String.valueOf(entry.getKey()), entry.getValue(), mapper);
            }
        } else if (value instanceof List<?> list) {
            ArrayNode array = target.putArray(key);
            for (Object item : list) {
                add(array, item, mapper);
            }
        } else if (value instanceof Integer number) {
            target.put(key, number.intValue());
        } else if (value instanceof Long number) {
            target.put(key, number.longValue());
        } else if (value instanceof Boolean flag) {
            target.put(key, flag.booleanValue());
        } else {
            target.put(key, String.valueOf(value));
        }
    }

    private static void add(ArrayNode target, Object value, JsonMapper mapper) {
        if (value == null) {
            target.addNull();
        } else if (value instanceof Map<?, ?> map) {
            ObjectNode child = target.addObject();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                put(child, String.valueOf(entry.getKey()), entry.getValue(), mapper);
            }
        } else if (value instanceof List<?> list) {
            ArrayNode array = target.addArray();
            for (Object item : list) {
                add(array, item, mapper);
            }
        } else if (value instanceof Integer number) {
            target.add(number.intValue());
        } else if (value instanceof Long number) {
            target.add(number.longValue());
        } else if (value instanceof Boolean flag) {
            target.add(flag.booleanValue());
        } else {
            target.add(String.valueOf(value));
        }
    }
}
