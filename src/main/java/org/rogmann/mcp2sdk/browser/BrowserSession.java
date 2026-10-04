package org.rogmann.mcp2sdk.browser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One browser session: the frame in which user and LLM operate the same application.
 *
 * <p>The session holds exactly four things:</p>
 * <ol>
 *   <li><b>the channel to the bridge</b> - long-poll (decision L2): the bridge fetches commands with
 *       {@link #pollCommands(long)} and delivers results with {@link #handleBridgeMessage(JsonNode)}.
 *       A command that needs an answer ({@code snapshot}) hangs on a {@link CompletableFuture} which
 *       the arriving report completes.</li>
 *   <li><b>the last view</b> - snapshot revision, frames, page location. The snapshot arrives as JSON
 *       <em>unchanged</em> and is only filed away; the filtering happens on the way out, so that the
 *       LLM can see the raw view when it needs to.</li>
 *   <li><b>the capture</b> - requests and events, bounded rings plus JSONL on disk.</li>
 *   <li><b>the action log</b> - every LLM tool call as one line. That is the trail which is reason
 *       enough to run this procedure at all, and the corpus M2 learns from.</li>
 * </ol>
 *
 * <p>The session holds <b>no</b> cookies (decision L3): the browser owns the session, we only rewrite
 * {@code Path} and {@code Domain} so that it sends them back to us correctly.</p>
 */
public final class BrowserSession {

    private static final Logger LOG = LoggerFactory.getLogger(BrowserSession.class);

    /** How many captures stay in memory (on disk everything lies, see writeJsonl). */
    static final int KEPT_EXCHANGES = 150;

    private final String id;
    private final String appId;
    private final BrowserApp app;
    private final String prefix;
    private final BrowserSecrets secrets;
    private final Instant createdAt = Instant.now();

    /** Bridge state. */
    private final AtomicLong lastSeenMillis = new AtomicLong(0L);
    private final AtomicBoolean bridgeReady = new AtomicBoolean(false);
    private final AtomicInteger helloCount = new AtomicInteger();
    /**
     * Counts page borders as the bridge reports them: a new document ({@code hello}) and a route change
     * ({@code nav} over pushState/replaceState/popstate). The fill journal already treats both as the
     * end of what it knows (a page border makes every diff obsolete), and M2's field evidence needs the
     * same line for a different reason: two reads across a border are not one comparison, the
     * application may simply be showing something else.
     */
    private final AtomicInteger pageBorders = new AtomicInteger();
    private volatile String pageUrl = "";
    private volatile String pageTitle = "";
    private volatile int frameCount;
    private volatile String bridgeError = "";

    /** View. */
    private final AtomicLong rev = new AtomicLong();
    private final AtomicLong snapshotAt = new AtomicLong();
    private final AtomicBoolean snapshotQuiescent = new AtomicBoolean(true);
    private volatile JsonNode snapshot;

    /** Channel. */
    private final LinkedBlockingQueue<ObjectNode> toBridge = new LinkedBlockingQueue<>();
    private final Map<String, CompletableFuture<JsonNode>> awaiting = new ConcurrentHashMap<>();
    private final AtomicInteger commandSeq = new AtomicInteger();

    /**
     * Histories. <b>Deliberately an {@link ArrayDeque} under a {@link Lock}, not a ConcurrentDeque:</b>
     * every recording is a sequence of "append, trim overflow, read the window", and that is only
     * atomic if both happen together - with a ConcurrentDeque the ring would briefly grow larger than
     * allowed after a concurrent {@code eventsSince()} + {@code addEvent()}, and the readers
     * ({@link #eventsSince}, {@link #recentExchanges}, {@link #exchange}) would still need protection
     * because they need {@code descendingIterator()} and a size comparison. The ring is also only the
     * fast view for the tool; everything lands in the JSONL file in full. Where things are really
     * handed over (commands to the bridge, waiting snapshot calls), {@link LinkedBlockingQueue} and
     * {@link ConcurrentHashMap} are used instead.
     */
    private static final int KEPT_EVENTS = 400;
    private static final int KEPT_ACTIONS = 200;
    private final Deque<ObjectNode> events = new ArrayDeque<>();
    private final Deque<BrowserExchange> exchanges = new ArrayDeque<>();
    private final Deque<ObjectNode> actions = new ArrayDeque<>();
    private final AtomicInteger eventSeq = new AtomicInteger();
    private final AtomicInteger exchangeSeq = new AtomicInteger();
    private final AtomicLong bytesDown = new AtomicLong();
    private final AtomicLong requestCount = new AtomicLong();

    /**
     * Fill journal (M1): what the LLM wrote on the current page, in order - the diff the review entry
     * shows and the basis of the "what I could not check" line. One page's worth: cleared on
     * navigation and on a fresh hello (page load), deliberately <b>not</b> on a new snapshot revision
     * (a refresh undoes nothing). The values come from the bridge's readback, so secret fields are
     * already redacted here.
     */
    private static final int KEPT_JOURNAL = 200;
    private final Deque<ObjectNode> journal = new ArrayDeque<>();

    /**
     * Value diff of the last write operation (M2), guarded by {@link #LOCK}. Feeds the field model and
     * the reviewer's "these fields the application recalculates" line.
     */
    private ObjectNode lastDiff;

    /**
     * Attribution of a pending LLM submit (M1): {@code {reviewId, atMillis}} or {@code null}. Set when
     * an approved submit's click is sent, consumed once by the next navigational POST - a best-effort
     * note in the capture (docs/browser.md §7.5), never a gate: the gate sits in the tool (L8).
     */
    private static final long SUBMIT_ATTRIBUTION_TTL_MILLIS = 60_000;
    private String[] llmSubmit;                     // guarded by LOCK

    /**
     * Set when the session is closed. A command that can no longer reach a bridge must not sit in the
     * queue for its whole ceiling - the wait loop reads this flag between its slices.
     */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Channel requests turned away because the document named another session than this one (a tab from
     * before the last server start, docs/browser.md §2b), and channel requests that named no session at
     * all. Both belong in the status: they are the difference between "the capture shows what this
     * document did" and a sentence nobody should believe.
     */
    private final AtomicInteger staleChannels = new AtomicInteger();
    private final AtomicInteger namelessChannels = new AtomicInteger();
    private final AtomicReference<String> lastStaleChannel = new AtomicReference<>("");

    private final Path runDir;

    /** Protects the three rings (see {@link #events}). */
    private final Lock LOCK = new ReentrantLock();

    /**
     * Protects appending to the JSONL files. Deliberately a second lock: a hanging disk (network
     * drive, virus scanner) must not stop the ring from being read.
     */
    private final Lock FILE_LOCK = new ReentrantLock();

    /**
     * @param app     the application being operated
     * @param id      session id (appears in the path and in the log entries)
     * @param runDir  target directory for JSONL ({@code runs/browser/<id>})
     */
    BrowserSession(BrowserApp app, String id, Path runDir) {
        this.app = app;
        this.id = id;
        this.appId = app.id();
        // Port mode: the application lives on its own origin root, there is no prefix.
        this.prefix = app.port() > 0 ? "" : BrowserRewriter.prefixFor(appId, id);
        this.secrets = new BrowserSecrets(app.sensitiveKeys());
        this.runDir = runDir;
    }

    // ========================================================================
    // Channel: long-poll
    // ========================================================================

    /**
     * Delivers pending commands to the bridge and waits up to {@code waitMillis} for them.
     *
     * <p>A poll that returned {@code []} immediately would send the bridge into a busy loop with
     * server load; waiting is the whole point here. This occupies one servlet worker per session -
     * the right choice for one session, and the reason for WebSocket with many.</p>
     *
     * @param waitMillis maximum waiting time
     * @return a JSON array of commands (possibly empty)
     */
    public String pollCommands(long waitMillis) {
        lastSeenMillis.set(System.currentTimeMillis());
        List<ObjectNode> taken = new ArrayList<>();
        toBridge.drainTo(taken);
        if (taken.isEmpty() && waitMillis > 0) {
            try {
                ObjectNode first = toBridge.poll(waitMillis, TimeUnit.MILLISECONDS);
                if (first != null) {
                    taken.add(first);
                    toBridge.drainTo(taken);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        ArrayNode array = BrowserSessions.get().mapper().createArrayNode();
        taken.forEach(array::add);
        return array.toString();
    }

    /**
     * Handles a report from the bridge ({@code hello}, {@code snapshot}, {@code event},
     * {@code nav}, {@code error}) and wakes a waiting {@link #requestSnapshot}.
     *
     * @param message the report
     * @return a short acknowledgement for the bridge
     */
    public JsonNode handleBridgeMessage(JsonNode message) {
        String type = message.path("t").asString("");
        lastSeenMillis.set(System.currentTimeMillis());
        bridgeReady.set(true);
        bridgeError = "";
        switch (type) {
            case "hello" -> {
                helloCount.incrementAndGet();
                pageBorders.incrementAndGet();
                pageUrl = message.path("url").asString("");
                pageTitle = message.path("title").asString("");
                frameCount = message.path("frames").asInt(1);
                clearJournal("page load");
                LOG.info("browser: bridge connected (session {}, page {})", id, pageUrl);
            }
            case "snapshot" -> storeSnapshot(message);
            case "result" -> completeResult(message);
            case "event" -> addEvent(message);
            case "nav" -> {
                pageBorders.incrementAndGet();
                pageUrl = message.path("url").asString(pageUrl);
                frameCount = message.path("frames").asInt(frameCount);
                clearJournal("navigation");
                ObjectNode event = BrowserSessions.get().mapper().createObjectNode();
                event.put("k", "nav");
                event.put("frame", message.path("frame").asString("f0"));
                event.put("url", pageUrl);
                addEvent(event);
            }
            case "leak" -> {
                JsonNode reported = message.path("entries");
                int count = reported.isArray() ? reported.size() : 0;
                LOG.warn("browser: {} request(s) in session {} went straight to a foreign host "
                        + "- the capture is incomplete there: {}", count, id, reported);
                ObjectNode event = BrowserSessions.get().mapper().createObjectNode();
                event.put("k", "leak");
                event.set("entries", reported.deepCopy());
                event.put("count", count);
                addEvent(event);
            }
            case "error" -> {
                bridgeError = message.path("message").asString("unknown error");
                LOG.warn("browser: bridge reports an error in {}: {}", id, bridgeError);
            }
            default -> LOG.debug("browser: unknown bridge report {} in {}", type, id);
        }
        ObjectNode reply = BrowserSessions.get().mapper().createObjectNode();
        reply.put("ok", true);
        reply.put("rev", rev.get());
        return reply;
    }

    private void storeSnapshot(JsonNode message) {
        long newRev = rev.incrementAndGet();
        LOG.debug("browser: session {} snapshot revision {} ({} frames, quiescent={})", id, newRev,
                message.path("frames").isArray() ? message.path("frames").size() : 0,
                message.path("quiescent").asBoolean(false));
        snapshotAt.set(System.currentTimeMillis());
        snapshotQuiescent.set(message.path("quiescent").asBoolean(false));
        // M2 reads the log, not the live session, and a snapshot line was unreadable on its own: the
        // revision is a server fact the bridge cannot know, and without session and application the
        // lines of several runs cannot be told apart once they lie next to each other in
        // runs/browser/. Same for the page - the action log names a rev, and the pair (rev, page) is
        // what ties "the model wrote here" to "the page looked like this".
        ObjectNode stored = (ObjectNode) message.deepCopy();
        stored.put("rev", newRev);
        stored.put("session", id);
        stored.put("app", appId);
        stored.put("page", pageForDisplay());
        snapshot = stored;
        frameCount = message.path("frames").isArray() ? message.path("frames").size() : frameCount;
        if (!message.path("leaks").isMissingNode() && message.path("leaks").size() > 0) {
            LOG.info("browser: {} direct foreign requests in session {}", message.path("leaks").size(), id);
        }
        String commandId = message.path("id").asString("");
        if (!commandId.isEmpty()) {
            CompletableFuture<JsonNode> waiter = awaiting.remove(commandId);
            if (waiter != null) {
                waiter.complete(stored.deepCopy());
            }
        }
        writeJsonl("snapshots.jsonl", stored);
        // Every snapshot already carries the field keys and the values of the page, so feeding them to
        // the model is the evidence a value pass would produce - for free, and on every page the LLM
        // looks at. This is what lets a field reach the two observations a `constant` claim needs.
        if (BrowserSessions.get().settings().fieldsOn()) {
            BrowserSessions.get().fieldModel(appId).noteSnapshot(this, stored);
        }
    }

    private void addEvent(JsonNode event) {
        // deepCopy() returns the node type of the base class, but the runtime instance is that of the
        // calling report (always an ObjectNode) - without the cast this would not be an object.
        ObjectNode copy = (ObjectNode) event.deepCopy();
        copy.put("seq", eventSeq.incrementAndGet());
        copy.put("ts", Instant.now().toString());
        // Which session and which application an event belongs to is not in the bridge's message (it
        // does not know them) and it is not in the file name either once someone joins two sessions to
        // one corpus - so it goes into every line (M2, docs/browser.md §4a).
        copy.put("session", id);
        copy.put("app", appId);
        LOCK.lock();
        try {
            events.addLast(copy);
            while (events.size() > KEPT_EVENTS) {
                events.removeFirst();
            }
        } finally {
            LOCK.unlock();
        }
        // M2 evidence, cheap and on the spot: a field that changes because a *hand* typed into it is a
        // parameter - the counterpart of the field the application recalculates on its own. Only what
        // the bridge already tagged with a field key can be used, and only when M2 was asked for at all
        // (with the mode off nothing here runs - an M1 session collects no knowledge, decision L11).
        String kind = copy.path("k").asString("");
        String fieldKey = copy.path("key").asString("");
        if (!fieldKey.isEmpty() && (kind.equals("input") || kind.equals("change"))
                && BrowserSessions.get().settings().fieldsOn()) {
            noteTypedKey(fieldKey);
            BrowserSessions.get().fieldModel(appId)
                    .noteUserInput(this, fieldKey, copy.path("label").asString(""),
                            copy.path("value").asString(""));
        }
        writeJsonl("events.jsonl", copy);
    }

    /**
     * Asks for a fresh snapshot and waits for the bridge's answer.
     *
     * @param scope      optional frame ({@code "f0"}), otherwise everything
     * @param full       {@code true} for the complete tree instead of the short form
     * @param waitMillis maximum waiting time; the bridge only answers after its next poll
     * @return the bridge's report, or an error node if it did not answer
     */
    public JsonNode requestSnapshot(String scope, boolean full, long waitMillis) {
        ObjectNode command = BrowserSessions.get().mapper().createObjectNode();
        String commandId = "c-" + commandSeq.incrementAndGet();
        command.put("id", commandId);
        command.put("t", "snapshot");
        command.put("full", full);
        if (scope != null && !scope.isBlank()) {
            command.put("scope", scope);
        }
        command.put("budget", BrowserSessions.get().settings().snapshotBudget());
        command.put("waitQuiescentMs", BrowserSessions.get().settings().quiesceMillis());
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        awaiting.put(commandId, future);
        toBridge.add(command);
        return awaitAnswer(future, commandId, waitMillis);
    }

    /**
     * Sends a command that needs an answer and waits for the bridge's report - the write path's twin
     * of {@link #requestSnapshot} (M1: {@code fill}, {@code click}, {@code undo}).
     *
     * <p>The command is queued; the bridge picks it up with its next poll and posts the answer back as
     * {@code {"t":"result","id":…}}, which {@link #handleBridgeMessage} routes here. An answer can be
     * lost - a click that navigates the page away does not always survive the unload - the caller then
     * receives {@code no-answer-from-bridge} and checks the events for a {@code nav} itself.</p>
     *
     * @param command    the command (the answer id is added to it)
     * @param waitMillis maximum waiting time
     * @return the bridge's report, or an error node
     */
    public JsonNode requestResult(ObjectNode command, long waitMillis) {
        String commandId = "c-" + commandSeq.incrementAndGet();
        command.put("id", commandId);
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        awaiting.put(commandId, future);
        toBridge.add(command);
        return awaitAnswer(future, commandId, waitMillis);
    }

    /**
     * Asks for the value pass over the writable fields of this page (M2): the keyed map of values plus
     * what the page says about each field on its own (required, maxlength, pattern, datalist, options).
     * Reads and changes nothing, so it works with the write switch off - it is a question, not an
     * action, and its answer is what {@link BrowserFieldModel} is built from.
     *
     * @param waitMillis maximum waiting time
     * @return the bridge's report ({@code fields[], page, truncated, quiescent}) or an error node
     */
    public JsonNode requestObserve(long waitMillis) {
        ObjectNode command = BrowserSessions.get().mapper().createObjectNode();
        command.put("t", "observe");
        command.put("waitMs", Math.max(600, BrowserSessions.get().settings().quiesceMillis() * 4));
        return requestResult(command, waitMillis);
    }

    /**
     * Keeps the value diff the bridge reported with the last write operation. The diff is the evidence
     * the field model grows from and the line a reviewer reads before approving a submit, so it
     * carries the revision and the page it belongs to: after a navigation it explains nothing.
     *
     * @param diff the {@code diff} node of a {@code fill}/{@code click}/{@code undo} result
     */
    public void recordDiff(JsonNode diff) {
        if (diff == null || diff.isMissingNode() || !diff.isObject()) {
            return;
        }
        ObjectNode copy = (ObjectNode) diff.deepCopy();
        copy.put("rev", rev.get());
        copy.put("page", pageForDisplay());
        copy.put("at", Instant.now().toString());
        LOCK.lock();
        try {
            lastDiff = copy;
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * @return the last value diff of this session, or {@code null} when no write reported one
     */
    public JsonNode lastDiff() {
        LOCK.lock();
        try {
            return lastDiff;
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * Waits for the answer of a queued command and turns every failure into an error node - the one
     * shape both channel users ({@link #requestSnapshot}, {@link #requestResult}) hand to their
     * callers.
     *
     * <p>The wait runs in slices, not in one long block: a command whose bridge is gone (session
     * closed, tab navigated away and never back) would otherwise sit silent for its whole ceiling -
     * which reads exactly like a hang. Early exits: session closed, or a bridge that reported in
     * once and has been silent past the bridge timeout.</p>
     */
    private JsonNode awaitAnswer(CompletableFuture<JsonNode> future, String commandId, long waitMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis);
        try {
            while (true) {
                long restNanos = deadline - System.nanoTime();
                if (restNanos <= 0) {
                    awaiting.remove(commandId);
                    return errorNode("no-answer-from-bridge", "the bridge does not answer within "
                            + waitMillis + " ms - is the page open and the session active? ("
                            + describeBridge() + ")");
                }
                try {
                    return future.get(Math.min(restNanos, TimeUnit.MILLISECONDS.toNanos(400)),
                            TimeUnit.NANOSECONDS);
                } catch (java.util.concurrent.TimeoutException slice) {
                    if (closed.get()) {
                        awaiting.remove(commandId);
                        return errorNode("session-closed", "the session was closed while the "
                                + "command waited - ask the user to open a new one (action=open)");
                    }
                    long lastSeen = lastSeenMillis.get();
                    if (lastSeen != 0 && System.currentTimeMillis() - lastSeen
                            > BrowserSessions.get().settings().bridgeTimeoutMillis()) {
                        awaiting.remove(commandId);
                        return errorNode("bridge-gone", "the page stopped reporting - it was "
                                + "probably closed or navigated away (" + describeBridge() + ")");
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            awaiting.remove(commandId);
            return errorNode("interrupted", null);
        } catch (java.util.concurrent.ExecutionException e) {
            awaiting.remove(commandId);
            return errorNode(String.valueOf(e.getMessage()), null);
        }
    }

    private ObjectNode errorNode(String error, String hint) {
        ObjectNode node = BrowserSessions.get().mapper().createObjectNode();
        node.put("ok", false);
        node.put("error", error);
        if (hint != null) {
            node.put("hint", hint);
        }
        return node;
    }

    /**
     * Completes a waiting command with a generic result report from the bridge. Snapshots are
     * completed in {@link #storeSnapshot}; every write command answers as
     * {@code {"t":"result","id":…}}.
     */
    private void completeResult(JsonNode message) {
        String commandId = message.path("id").asString("");
        if (commandId.isEmpty()) {
            return;
        }
        CompletableFuture<JsonNode> waiter = awaiting.remove(commandId);
        if (waiter != null) {
            waiter.complete(message.deepCopy());
        }
    }

    // ========================================================================
    // Fill journal and submit attribution (M1)
    // ========================================================================

    /**
     * Records one written field in the fill journal - the diff the review entry of
     * {@code browser.submit} presents. Values come from the bridge's readback: secret fields are
     * already redacted, the journal never sees them.
     *
     * @param entry {@code {ref, label, oldValue, value, secret?, validationMessage?}}
     */
    public void journalAdd(ObjectNode entry) {
        ObjectNode copy = (ObjectNode) entry.deepCopy();
        copy.put("ts", Instant.now().toString());
        LOCK.lock();
        try {
            journal.addLast(copy);
            while (journal.size() > KEPT_JOURNAL) {
                journal.removeFirst();
            }
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * @return the fill journal of the current page, oldest first
     */
    public List<ObjectNode> journal() {
        LOCK.lock();
        try {
            return new ArrayList<>(journal);
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * Clears the fill journal. A page border makes every diff obsolete: after a navigation (or a fresh
     * page load) the values the LLM entered are either sent or gone.
     *
     * @param reason why, for the debug log
     */
    public void clearJournal(String reason) {
        LOCK.lock();
        try {
            if (!journal.isEmpty()) {
                LOG.debug("browser: fill journal of session {} cleared ({})", id, reason);
                journal.clear();
            }
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * Marks an approved LLM submit: the next navigational POST of this session is noted with the
     * review id (best-effort attribution, docs/browser.md §7.5).
     *
     * @param reviewId id of the approved review entry
     */
    public void beginLlmSubmit(String reviewId) {
        LOCK.lock();
        try {
            llmSubmit = new String[] {reviewId, String.valueOf(System.currentTimeMillis())};
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * Consumes a pending LLM-submit attribution - once: the second navigational POST counts as a user
     * submit again. The note is a hint in the capture, not proof; the gate is the tool's (L8).
     *
     * @return the review id, or {@code null} when no approved LLM submit is pending
     */
    public String matchLlmSubmit() {
        LOCK.lock();
        try {
            if (llmSubmit == null) {
                return null;
            }
            String[] pending = llmSubmit;
            llmSubmit = null;
            if (System.currentTimeMillis() - Long.parseLong(pending[1]) > SUBMIT_ATTRIBUTION_TTL_MILLIS) {
                return null;
            }
            return pending[0];
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * Sends a command that needs no answer (M1: scrolling, highlight).
     *
     * @param command the command
     */
    public void enqueueCommand(ObjectNode command) {
        toBridge.add(command);
    }

    // ========================================================================
    // Capture
    // ========================================================================

    /**
     * Logs one proxied exchange - redacted before it becomes visible anywhere.
     *
     * @param method       method
     * @param proxiedPath  the path the browser saw
     * @param upstreamPath path at the application
     * @param query        query or {@code null}
     * @param status       response status
     * @param requestType  content type of the request
     * @param requestBody  raw request text (may be {@code null})
     * @param responseType content type of the response
     * @param responseBody raw response text (may be {@code null})
     * @param bytesDown    bytes received
     * @param durationMillis runtime
     * @param extraNotes   notes
     * @return the stored entry
     */
    public BrowserExchange recordExchange(String method, String proxiedPath, String upstreamPath,
            String query, int status, String requestType, String requestBody, String responseType,
            String responseBody, long bytesDown, long durationMillis, List<String> extraNotes) {
        boolean sensitive = app.isSensitivePath(upstreamPath);
        int clip = BrowserSessions.get().settings().captureBytes();
        List<String> notes = new ArrayList<>(extraNotes == null ? List.of() : extraNotes);
        String redactedRequest;
        String redactedResponse;
        if (sensitive) {
            redactedRequest = "‹body not logged: sensitive path " + upstreamPath + "›";
            redactedResponse = "‹body not logged: sensitive path " + upstreamPath + "›";
            notes.add("sensitivePath");
        } else {
            BrowserSecrets.Redaction in = secrets.redactStructured(requestBody == null ? "" : requestBody);
            BrowserSecrets.Redaction out = secrets.redactStructured(responseBody == null ? "" : responseBody);
            redactedRequest = in.text();
            redactedResponse = out.text();
            if (in.redacted() || out.redacted()) {
                notes.add("redacted");
            }
        }
        BrowserExchange exchange = new BrowserExchange(exchangeSeq.incrementAndGet(),
                Instant.now().toString(), method, proxiedPath, upstreamPath, query == null ? "" : query,
                status, requestType == null ? "" : requestType, BrowserExchange.clip(redactedRequest, clip),
                responseType == null ? "" : responseType,
                BrowserExchange.clip(redactedResponse, clip), 0L, bytesDown, durationMillis, sensitive,
                List.copyOf(notes));
        LOCK.lock();
        try {
            exchanges.addLast(exchange);
            while (exchanges.size() > KEPT_EXCHANGES) {
                exchanges.removeFirst();
            }
        } finally {
            LOCK.unlock();
        }
        this.bytesDown.addAndGet(bytesDown);
        requestCount.incrementAndGet();
        // The payload side of the field model (M2b, docs/browser.md §7a): which names leave the page.
        // This is the one place both front doors meet, and the one place the request text still exists
        // un-clipped - clipping would cut the tail of a form and a cut-off name would read as "never
        // sent". Runs behind the mode switch, so an M1 session pays nothing for it (decision L11), and
        // on the redacted text only: a body the operator excluded stays excluded, and becomes a gap.
        if (BrowserSessions.get().settings().fieldsOn() && status != 0) {
            BrowserPayload.Reading carried = BrowserPayload.read(method, requestType,
                    redactedRequest, query == null ? "" : query, sensitive);
            if (!carried.silent()) {
                BrowserSessions.get().fieldModel(appId).noteSent(this, upstreamPath, carried);
            }
        }
        ObjectNode line = exchange.toFullNode();
        line.put("session", id);
        line.put("app", appId);
        writeJsonl("requests.jsonl", line);
        return exchange;
    }

    /**
     * Notes a tool call of the LLM (the audit trail, §9 in docs/browser.md).
     *
     * @param tool      the action that was called
     * @param arguments the arguments as they arrived
     * @param outcome   the result in one word
     * @param detail    longer note, may be {@code null}
     * @return the log line (also usable as an answer to the tool's user)
     */
    public ObjectNode recordAction(String tool, JsonNode arguments, String outcome, String detail) {
        ObjectNode node = BrowserSessions.get().mapper().createObjectNode();
        node.put("ts", Instant.now().toString());
        node.put("session", id);
        node.put("app", appId);
        node.put("actor", "llm");
        node.put("tool", tool);
        node.set("arguments", arguments == null
                ? BrowserSessions.get().mapper().createObjectNode() : arguments.deepCopy());
        node.put("rev", rev.get());
        node.put("outcome", outcome);
        if (detail != null && !detail.isBlank()) {
            node.put("detail", BrowserExchange.clip(detail, 2000));
        }
        LOCK.lock();
        try {
            actions.addLast(node);
            while (actions.size() > KEPT_ACTIONS) {
                actions.removeFirst();
            }
        } finally {
            LOCK.unlock();
        }
        writeJsonl("actions.jsonl", node);
        return node;
    }

    // ========================================================================
    // Reading
    // ========================================================================

    /**
     * @return the last snapshot, or {@code null} if the bridge has delivered nothing yet
     */
    public JsonNode snapshot() {
        return snapshot;
    }

    /**
     * @return the current snapshot revision (the reference for {@code ref}s)
     */
    public long rev() {
        return rev.get();
    }

    /**
     * @return events since a sequence number (exclusive), at most the size of the ring
     */
    public List<ObjectNode> eventsSince(int sinceSeq, int limit) {
        List<ObjectNode> newestFirst = new ArrayList<>();
        LOCK.lock();
        try {
            for (Iterator<ObjectNode> it = events.descendingIterator(); it.hasNext(); ) {
                ObjectNode event = it.next();
                if (event.path("seq").asInt(0) <= sinceSeq) {
                    break;
                }
                newestFirst.add(event);
                if (newestFirst.size() > Math.max(1, limit)) {
                    break;
                }
            }
        } finally {
            LOCK.unlock();
        }
        Collections.reverse(newestFirst);
        return newestFirst;
    }

    /**
     * @param limit number of exchanges, counting from the newest
     * @return the newest captures, oldest first
     */
    public List<BrowserExchange> recentExchanges(int limit) {
        List<BrowserExchange> all;
        LOCK.lock();
        try {
            all = new ArrayList<>(exchanges);
        } finally {
            LOCK.unlock();
        }
        int from = Math.max(0, all.size() - Math.max(1, limit));
        return all.subList(from, all.size());
    }

    /**
     * @param seq sequence number of a capture
     * @return the entry or {@code null}
     */
    public BrowserExchange exchange(int seq) {
        LOCK.lock();
        try {
            return exchanges.stream().filter(item -> item.seq() == seq).findFirst().orElse(null);
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * @return the recent action log entries, oldest first
     */
    public List<ObjectNode> recentActions(int limit) {
        List<ObjectNode> all;
        LOCK.lock();
        try {
            all = new ArrayList<>(actions);
        } finally {
            LOCK.unlock();
        }
        int from = Math.max(0, all.size() - Math.max(1, limit));
        return all.subList(from, all.size());
    }

    /**
     * Searches elements in the last snapshot. The search runs over every field, because the LLM can
     * never guess whether a text stands in the label, in a column heading or in the value.
     *
     * @param query search text (substring, case-insensitive)
     * @param limit maximum number of hits
     * @return the places found as nodes with {@code ref}, {@code role}, {@code label}, {@code value}
     */
    public List<ObjectNode> find(String query, int limit) {
        List<ObjectNode> hits = new ArrayList<>();
        JsonNode current = snapshot;
        if (current == null || query == null || query.isBlank()) {
            return hits;
        }
        String needle = query.toLowerCase(Locale.ROOT);
        for (JsonNode frame : current.path("frames")) {
            for (JsonNode element : frame.path("ax")) {
                String haystack = (element.path("role").asString("") + " "
                        + element.path("label").asString("") + " "
                        + element.path("value").asString("") + " "
                        + element.path("name").asString("") + " "
                        + element.path("near").asString("") + " "
                        + element.path("rowHead").asString("") + " "
                        + element.path("colHead").asString("")).toLowerCase(Locale.ROOT);
                if (haystack.contains(needle)) {
                    ObjectNode hit = BrowserSessions.get().mapper().createObjectNode();
                    hit.put("frame", frame.path("frame").asString(""));
                    hit.put("ref", element.path("ref").asString(""));
                    hit.put("role", element.path("role").asString(""));
                    hit.put("label", element.path("label").asString(""));
                    hit.put("value", element.path("value").asString(""));
                    if (element.has("rowHead")) {
                        hit.put("rowHead", element.path("rowHead").asString(""));
                    }
                    if (element.has("colHead")) {
                        hit.put("colHead", element.path("colHead").asString(""));
                    }
                    hits.add(hit);
                    if (hits.size() >= limit) {
                        return hits;
                    }
                }
            }
        }
        return hits;
    }

    /**
     * @param ref a ref like {@code f0/e12}
     * @return the element including its locator bundle, or {@code null}
     */
    /**
     * @param ref a ref like {@code f0/e12}
     * @return the element including its locator bundle, or {@code null}
     */
    public JsonNode element(String ref) {
        JsonNode current = snapshot;
        if (current == null || ref == null) {
            return null;
        }
        String wanted = ref.trim();
        if (wanted.isEmpty()) {
            return null;
        }
        // The snapshot carries the FULL address - bridge.js appends the local ref to the frame id
        // ("f0/e12", nested "f0/f1/e3"). Comparing that against the tail of the request found nothing
        // for every prefixed ref, which quietly made this lookup (and with it the locator action)
        // useless until the write path started asking for the element behind a ref.
        String frameId = wanted.contains("/") ? wanted.substring(0, wanted.lastIndexOf('/')) : "f0";
        String tail = wanted.substring(wanted.lastIndexOf('/') + 1);
        for (JsonNode frame : current.path("frames")) {
            if (!frame.path("frame").asString("").equals(frameId)) {
                continue;
            }
            for (JsonNode element : frame.path("ax")) {
                String stored = element.path("ref").asString("");
                if (stored.equals(wanted) || stored.equals(tail)) {
                    // the second form is leniency for a bare local name, not the documented one
                    return element;
                }
            }
        }
        return null;
    }

    /**
     * @param frameId frame id
     * @return the elements of this frame (empty if there is no snapshot or the frame is unknown)
     */
    public List<JsonNode> frameElements(String frameId) {
        List<JsonNode> out = new ArrayList<>();
        JsonNode current = snapshot;
        if (current == null) {
            return out;
        }
        for (JsonNode frame : current.path("frames")) {
            if (frameId == null || frameId.isBlank() || frame.path("frame").asString("").equals(frameId)) {
                for (JsonNode element : frame.path("ax")) {
                    out.add(element);
                }
            }
        }
        return out;
    }

    /**
     * @return state for the index page and for {@code sessions}
     */
    public ObjectNode statusNode() {
        ObjectNode node = BrowserSessions.get().mapper().createObjectNode();
        node.put("sessionId", id);
        node.put("appId", appId);
        node.put("prefix", prefix);
        node.put("port", app.port());
        node.put("url", entryUrl());
        node.put("created", createdAt.toString());
        node.put("bridge", bridgeReady.get() && System.currentTimeMillis() - lastSeenMillis.get()
                < BrowserSessions.get().settings().bridgeTimeoutMillis());
        node.put("bridgeSeenMsAgo", lastSeenMillis.get() == 0 ? -1
                : System.currentTimeMillis() - lastSeenMillis.get());
        node.put("page", pageUrl);
        node.put("title", pageTitle);
        node.put("frames", frameCount);
        node.put("rev", rev.get());
        node.put("snapshotAgeMs", snapshotAt.get() == 0 ? -1
                : System.currentTimeMillis() - snapshotAt.get());
        node.put("quiescent", snapshotQuiescent.get());
        node.put("requests", requestCount.get());
        node.put("events", eventSeq.get());
        if (closed.get()) {
            node.put("closed", true);
        }
        LOCK.lock();
        try {
            node.put("journal", journal.size());
        } finally {
            LOCK.unlock();
        }
        if (!bridgeError.isEmpty()) {
            node.put("bridgeError", bridgeError);
        }
        if (!bridgeReady.get() && lastSeenMillis.get() != 0) {
            // "bridge: false" alone reads as "the script is broken"; half the time a tab from before the
            // restart is polling the new session. The distinction belongs in the status, not in the code.
            node.put("bridgeNote", "it polls, but never reported a page - reload the document "
                    + "(sessions live in memory, the browser tab does not know about a restart)");
        }
        // Provenance of the capture: who was turned away, and who was let in without saying. Both
        // numbers say something about the lines in the JSONL, so they belong next to the session, not
        // only in the log the start of the server scrolled past.
        int stale = staleChannels.get();
        int nameless = namelessChannels.get();
        if (stale > 0) {
            node.put("staleChannelRequests", stale);
            node.put("staleChannelLast", lastStaleChannel.get());
        }
        if (nameless > 0) {
            node.put("channelRequestsWithoutSession", nameless);
        }
        node.put("auth", app.auth());
        return node;
    }

    /**
     * Marks the session as closed; the command wait loops stop early on this (docs/browser.md §7).
     */
    public void markClosed() {
        this.closed.set(true);
    }

    /**
     * A bridge that asked for another session than this one: answered with 410, counted here. Warned
     * about once, because such a tab polls every few seconds and a log full of the same warning says
     * less than one that is followed by a number.
     *
     * @param operation the endpoint it asked for ({@code poll}, {@code post}, {@code hello})
     * @param askedFor  the session id the document believes it belongs to
     */
    public void noteStaleChannel(String operation, String askedFor) {
        int seen = staleChannels.incrementAndGet();
        lastStaleChannel.set(operation + " as " + askedFor);
        if (seen == 1) {
            LOG.warn("browser: session {} got a channel request ({}) from a document that says it "
                            + "belongs to {} - that is not this session, so the tab was loaded before "
                            + "this server started. Answered with 410; the document has to be reloaded.",
                    id, operation, askedFor.isEmpty() ? "(no session)" : askedFor);
        } else {
            LOG.debug("browser: session {}, stale channel {} ({} in total)", id,
                    lastStaleChannel.get(), seen);
        }
    }

    /**
     * A channel request without the session parameter - a bridge from before this parameter existed, or
     * a hand-written URL. Passed through (nothing proves it is stale) but never unnoticed.
     *
     * @param operation the endpoint that was asked for
     */
    public void noteUnnamedChannel(String operation) {
        int seen = namelessChannels.incrementAndGet();
        if (seen == 1) {
            LOG.info("browser: session {} took a channel request ({}) that named no session - an old "
                            + "bridge script, or a request that did not come from one (docs/browser.md"
                            + " \u00a72b)", id, operation);
        }
    }

    /**
     * @return true once {@code BrowserSessions.closeSession} has taken this session out of service
     */
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * @return one line describing the bridge state for error messages
     */
    public String describeBridge() {
        long lastSeen = lastSeenMillis.get();
        if (lastSeen == 0) {
            return "the bridge has never reported in";
        }
        if (!bridgeReady.get()) {
            // The distinction that costs an hour otherwise: something polls this session, so the
            // listener and the script are fine - but no document ever said hello. That is the tab of a
            // previous server start (sessions live in memory, the browser does not know that).
            return "it polls, but never reported a page - this document was loaded before the server "
                    + "started; reload it in the browser (no hello yet)";
        }
        return "last seen " + (System.currentTimeMillis() - lastSeen) / 1000 + " s ago, "
                + helloCount.get() + " hello(es)";
    }

    /**
     * @return the session prefix under which the browser reaches this session; {@code ""} in
     *         port mode (there the application is the root of its own origin)
     */
    public String prefix() {
        return prefix;
    }

    /**
     * @return the address on which the user operates this session
     */
    public String entryUrl() {
        if (app.port() > 0) {
            return BrowserHost.url(app.id(), app.port(), "/");
        }
        return BrowserHost.url(app.id(), BrowserSessions.get().localPort(), prefix + "/");
    }

    /**
     * @return the page the user is on, without our session prefix
     */
    public String pageForDisplay() {
        if (pageUrl.startsWith(prefix)) {
            String rest = pageUrl.substring(prefix.length());
            return rest.isEmpty() ? "/" : rest;
        }
        return pageUrl;
    }

    /**
     * @return the page title as the bridge reported it
     */
    public String title() {
        return pageTitle;
    }

    /**
     * @return the load this session's document is on - a fresh {@code hello} means a new page load, and
     *         two passes over one load are <b>one</b> observation, not two (M2, see
     *         {@link BrowserFieldModel}: a `constant` claim needs evidence from separate documents,
     *         while a recalculation claim needs two reads of the <em>same</em> page view - see
     *         {@link #pageBorderId()})
     */
    public String loadId() {
        return id + "#" + helloCount.get();
    }

    /**
     * The line between two page views: a new document ({@code hello}) <em>or</em> a route change inside
     * one ({@code nav} over pushState/replaceState/popstate). Two different things for two different
     * claims, and the module has to keep them apart:
     *
     * <ul><li>{@link #loadId()} counts documents and is what a `constant` claim has to grow over -
     * a route change is not a second chance to see the same thing.</li>
     * <li>this is what ends the right to compare two reads for {@link BrowserFieldModel}, because a page
     * that moved on its own between two views may simply be showing another record. It is the same border
     * on which the fill journal is cleared - one line, two consumers, and an SPA route change handled the
     * way a page load is.</li></ul>
     *
     * @return an id that changes on every page border
     */
    public String pageBorderId() {
        return id + "#p" + pageBorders.get();
    }

    /**
     * Field keys that reported an {@code input} or {@code change} event during the current document.
     * This is what tells "the value moved because a hand moved it" from "the value moved on its own"
     * when two reads of one document disagree ({@link BrowserFieldModel}) - and it is a set of keys, not
     * of values, so nothing typed joins the list twice with different content.
     */
    private final Set<String> typedKeys = ConcurrentHashMap.newKeySet();
    private volatile String typedKeysLoad = "";

    /**
     * @param key field key of an {@code input}/{@code change} event
     */
    public void noteTypedKey(String key) {
        if (key == null || key.isEmpty()) {
            return;
        }
        alignTypedKeys();
        if (typedKeys.size() < 500) {
            typedKeys.add(key);
        }
    }

    /**
     * @param key field key to ask about
     * @return true when a hand (or a write recipe, which dispatches real {@code input} events) was in
     *         this field during the page view that is currently shown
     */
    public boolean wasTypedSinceLoad(String key) {
        alignTypedKeys();
        return key != null && typedKeys.contains(key);
    }

    /** A new page view starts an empty list: what was typed before a border explains nothing after. */
    private void alignTypedKeys() {
        String current = pageBorderId();
        if (!current.equals(typedKeysLoad)) {
            synchronized (typedKeys) {
                if (!current.equals(typedKeysLoad)) {
                    typedKeys.clear();
                    typedKeysLoad = current;
                }
            }
        }
    }

    /**
     * @return the number of events noted so far
     */
    public int eventCount() {
        return eventSeq.get();
    }

    /**
     * @return the id
     */
    public String id() {
        return id;
    }

    /**
     * @return the application being operated
     */
    public BrowserApp app() {
        return app;
    }

    /**
     * @return the redaction rules of this application (the bridge gets the same list)
     */
    public BrowserSecrets secrets() {
        return secrets;
    }

    /**
     * Configuration for the bridge's {@code hello} exchange.
     *
     * @param bridgeBase the base path under which <em>this</em> front door offers the channel endpoints
     *                   ({@code /browserctl/session/<id>} in path mode,
     *                   {@code /.browserproxy} on an application port)
     * @return the configuration
     */
    public ObjectNode bridgeConfig(String bridgeBase) {
        ObjectNode node = BrowserSessions.get().mapper().createObjectNode();
        node.put("base", prefix);
        node.put("session", id);
        node.put("app", appId);
        node.put("entryUrl", entryUrl());
        node.put("poll", bridgeBase + "/poll");
        node.put("post", bridgeBase + "/post");
        node.put("write", BrowserSessions.get().settings().writeEnabled());
        node.put("quiesceMs", BrowserSessions.get().settings().quiesceMillis());
        node.put("budget", BrowserSessions.get().settings().snapshotBudget());
        node.put("bridgeVersion", BrowserRewriter.BRIDGE_VERSION);
        node.put("pollSeconds", BrowserSessions.get().settings().pollSeconds());
        ArrayNode secretKeys = node.putArray("secrets");
        new java.util.LinkedHashSet<>(BrowserSecrets.DEFAULT_KEYS).forEach(secretKeys::add);
        app.sensitiveKeys().forEach(secretKeys::add);
        ArrayNode hosts = node.putArray("hosts");
        BrowserSessions.get().apps().all().forEach(known -> {
            if (known.host().isEmpty()) {
                return;
            }
            ObjectNode entry = hosts.addObject();
            ArrayNode forms = entry.putArray("upstreams");
            forms.add("https://" + known.host());
            forms.add("http://" + known.host());
            forms.add("//" + known.host());
            entry.put("prefix", BrowserRewriter.prefixFor(known.id(), id));
        });
        ArrayNode sensitivePaths = node.putArray("sensitivePaths");
        app.sensitivePaths().forEach(sensitivePaths::add);
        return node;
    }

    // ========================================================================
    // Filing away on disk
    // ========================================================================

    private void writeJsonl(String name, JsonNode node) {
        if (!BrowserSessions.get().settings().persist() || runDir == null) {
            // No run directory means no anchored storage location - the reason is told by
            // BrowserSessions.loggingProblem(), here nothing is guessed or forced.
            return;
        }
        String line = node.toString() + System.lineSeparator();
        FILE_LOCK.lock();
        try {
            Files.createDirectories(runDir);
            Files.writeString(runDir.resolve(name), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.warn("browser: {} could not be written ({} bytes): {}",
                    runDir.resolve(name), line.length(), e.getMessage());
        } finally {
            FILE_LOCK.unlock();
        }
    }

    /**
     * @return the point in time at which the session was opened
     */
    public java.time.Instant createdAt() {
        return createdAt;
    }

    /**
     * @return the directory in which this session files its logs
     */
    /**
     * Where a log file of this session lies - or the honest reason there is none. Shown to the LLM
     * and on the index page; it never names a path that was guessed from the working directory.
     *
     * @param name file name inside the run directory (e.g. {@code requests.jsonl})
     * @return the absolute path as text, or a note naming the setting to fix
     */
    public String logsLocation(String name) {
        BrowserSessions backend = BrowserSessions.get();
        if (!backend.settings().persist()) {
            return "in memory only (browser.persist=false)";
        }
        if (runDir == null) {
            String why = backend.loggingProblem();
            return "in memory only (" + (why == null ? "no run directory anchored" : why) + ")";
        }
        return runDir.resolve(name).toString();
    }

    /**
     * @return the directory this session's JSONL lies in, or {@code null} when no data location could
     *         be anchored (L10) - callers that show a path use {@link #logsLocation(String)}
     */
    public Path runDir() {
        return runDir;
    }
}
