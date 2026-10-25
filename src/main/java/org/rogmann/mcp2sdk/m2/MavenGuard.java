package org.rogmann.mcp2sdk.m2;

import org.rogmann.mcp2sdk.review.DecideConsumer;
import org.rogmann.mcp2sdk.review.ReviewAttribute;
import org.rogmann.mcp2sdk.review.ReviewAttributes;
import org.rogmann.mcp2sdk.review.ReviewEntry;
import org.rogmann.mcp2sdk.review.ReviewPersistenceType;
import org.rogmann.mcp2sdk.review.ReviewRequests;
import org.rogmann.mcp2sdk.review.ReviewResponseEntry;
import org.rogmann.mcp2sdk.review.ReviewService;
import org.rogmann.mcp2sdk.review.ReviewStatus;
import org.rogmann.mcp2sdk.review.ReviewStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The decision in front of a {@code mvn} run whose inputs the model may have changed: run it, ask
 * first, or refuse.
 *
 * <p>
 * <b>What {@link PomWatch} knows and what this class does with it.</b> PomWatch fingerprints the
 * project's Maven inputs at startup and answers one question - "is this still the POM the operator
 * started with?" - and nothing else. This class turns that answer into a step a model can take:
 * unchanged means the Maven run goes through exactly as it did before the guard existed (no
 * request, no wait, no extra ceremony - <b>reading what is already there must not need approval</b>),
 * and changed means a human is asked, with the diff in front of them, before anything is spawned.
 * </p>
 *
 * <p>
 * <b>Approval re-baselines; that is the whole loop.</b> A yes adopts exactly the files the request
 * listed as the new baseline, so the next run of the same shape passes without asking again. Without
 * that, a legitimate dependency change would raise a request for every single Maven call until the
 * server restarts, and the page would become a thing to click through. Without the reverse
 * (approval being the only path to a new baseline) a model could edit a POM, retire the check, and
 * run - which would make the guard decorative. See {@link PomWatch#rebaseline(java.util.Collection,
 * String)} for why that call must not be reachable from a tool.
 * </p>
 *
 * <p>
 * <b>Two shapes of the same call.</b> {@code wait=true} (the default) blocks the tool call until a
 * decision or until the window ends - what {@code python.exec} and {@code browser.submit} do, and
 * what a model uses when it can sit on the answer. {@code wait=false} puts the request on the list
 * and returns its id at once, so the model can answer the user "I am waiting for approval of
 * {@code rv-…}" instead of holding an open tool call; {@link #statusOf(String)} collects the result
 * later. A pending request can be re-armed with {@link #renew(String, Integer, String)}; one that
 * has expired cannot be revived, because "expired" is already a decision the reviewer's page has
 * shown - the follow-up then reads as a fresh request, which is the honest thing to file.
 * </p>
 *
 * <p>
 * <b>One object, one status.</b> The {@link ReviewEntry} in the store is the only record of the
 * state; the consumer performs the transition with {@link ReviewEntry#moveFromPending} so a late
 * approval and the expiry cannot both win, and this class keeps nothing but the file list and the
 * command a request was about. A restart clears that, as it clears the review list.
 * </p>
 *
 * <p>
 * <b>What an approval does not say.</b> "Approved" means the human recognised this POM content. It
 * does not mean they read the plugin code Maven is about to execute, and it does not make the
 * resolved classpath correct (profiles, reactor scope, {@code -o} semantics - see
 * {@code docs/maven-guard.md} §5). The command shown in the request is the command that will be
 * run, character for character; that is the promise this class can keep.
 * </p>
 *
 * <p>
 * The tool id {@value #TOOL_ID} appears on {@code /review} with the first request; there is no
 * registration (see {@link ReviewService}).
 * </p>
 */
public final class MavenGuard implements ReviewService {

    private static final Logger LOG = LoggerFactory.getLogger(MavenGuard.class);

    /** The tool id shown on the review list. */
    public static final String TOOL_ID = "maven.exec";

    /** Approval window of a blocking call when the caller names none. */
    public static final int DEFAULT_WAIT_SECONDS = 300;

    /** Ceiling for a named window, blocking or not (a request nobody decides should leave the page). */
    public static final int MAX_WAIT_SECONDS = 3600;

    /**
     * How long a request filed without a waiting caller stays open. Not forever: an undecided
     * request on the page is a question the reviewer still owes somebody, and the next
     * {@link #statusOf(String)} turns an ended window into {@link ReviewStatus#EXPIRED} instead of
     * pretending it is still open.
     */
    public static final int DEFAULT_PENDING_WINDOW_SECONDS = 1800;

    /** The singleton behind the static API (the callers are not Spring-managed). */
    private static final MavenGuard INSTANCE = new MavenGuard();

    /**
     * What a request is about, kept next to the entry so that an approval knows which files it
     * adopts and a follow-up call can tell the model what was asked.
     *
     * @param entry      the entry on the review list (the one truth about the status)
     * @param pomPath    the POM the run targets
     * @param files      the changed inputs the approval adopts
     * @param command    the command line that would run
     * @param workingDir directory it would run in
     * @param monitor    the one object waiters block on and the consumer notifies - two monitors
     *                   here would mean a decision that only arrives after the next poll
     * @param renewals   what was re-armed and when, newest last
     * @param networkRun true when the command reaches the network and writes into the repository;
     *                   such a run is asked about even when the watched inputs did not move, and
     *                   there is then nothing to adopt and nothing to re-check
     */
    private record Request(ReviewEntry entry, Path pomPath, List<Path> files, List<String> command,
                           Path workingDir, Object monitor, List<String> renewals,
                           boolean networkRun) {

        /** A copy with one more renewal line (records are immutable on purpose). */
        Request withRenewal(String line) {
            List<String> out = new ArrayList<>(renewals);
            out.add(line);
            return new Request(entry, pomPath, files, command, workingDir, monitor,
                    List.copyOf(out), networkRun);
        }

        /** True when the approval is the whole answer: nothing compared, nothing adopted. */
        boolean withoutProjectInputs() {
            return networkRun && files.isEmpty();
        }
    }

    private static final Map<String, Request> REQUESTS = new ConcurrentHashMap<>();

    private MavenGuard() {
    }

    /** @return the shared instance */
    public static MavenGuard getInstance() {
        return INSTANCE;
    }

    // ========================================================================
    // ReviewService
    // ========================================================================

    @Override
    public String getToolId() {
        return TOOL_ID;
    }

    @Override
    public String getToolTitle() {
        return "Maven run after a POM change";
    }

    @Override
    public ReviewPersistenceType defaultPersistence() {
        // The decision-relevant content is in the attributes (diff, digests, command); keeping a
        // copy of the project's POMs under runs/ would duplicate what git already holds.
        return ReviewPersistenceType.MEMORY;
    }

    // ========================================================================
    // The outcome
    // ========================================================================

    /**
     * Answer to "may this Maven run happen?".
     *
     * @param allowed   whether the caller may spawn the process
     * @param reason    why, as an enum a caller can switch on (the map view spells it lower case)
     * @param requestId the review request id, or null when nothing was asked
     * @param status    the request's current status as a lower-case word, or null
     * @param message   one sentence for the caller (and for the model)
     * @param changes   the differing inputs, empty unless something changed
     */
    public record Approval(boolean allowed, Reason reason, String requestId, String status,
                           String message, List<PomWatch.Change> changes) {

        /** @return the result map for a tool response (relative paths only, no server paths) */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("allowed", allowed);
            m.put("reason", (reason == null) ? null : reason.name().toLowerCase(Locale.ROOT));
            if (requestId != null) {
                m.put("requestId", requestId);
            }
            if (status != null) {
                m.put("status", status);
            }
            m.put("message", message);
            if (!changes.isEmpty()) {
                List<Map<String, Object>> list = new ArrayList<>();
                for (PomWatch.Change change : changes) {
                    list.add(change.toMap());
                }
                m.put("changes", list);
            }
            return m;
        }
    }

    /** The reasons an answer reads the way it does. */
    public enum Reason {
        /** Inputs unchanged since the baseline - the ordinary path, no request. */
        UNCHANGED,
        /** Nothing the run reads lies in a writable root. */
        OUTSIDE,
        /**
         * The inputs changed and {@code pomGuard=warn} let the run through anyway. Its own reason
         * because "pending" would name a request that was never filed and "unchanged" would deny
         * what the reviewer would have seen.
         */
        CHANGED_BUT_WARN,
        /** {@code tools.mvn.pomGuard=off}. */
        GUARD_OFF,
        /** A decision came in during this call and it was yes. */
        APPROVED,
        /** An earlier request (by id) was approved. */
        APPROVED_EARLIER,
        /** A request is on the list and nobody has decided yet. */
        PENDING,
        /**
         * Nothing could be compared at all (baseline still running, failed, or no project root) -
         * so no request was filed, because a request would show the reviewer nothing. Not a yes,
         * and not a "wait for approval" either: the message names what to fix.
         */
        NOT_COMPARED,
        /** The reviewer said no. */
        DENIED,
        /** The approval window ended without a decision. */
        EXPIRED,
        /** Unknown request id, or one belonging to another tool. */
        UNKNOWN_REQUEST,
        /** The caller asked about a request whose decision has no stored context anymore. */
        FORGOTTEN
    }

    // ========================================================================
    // Asking
    // ========================================================================

    /**
     * Asks whether a Maven run may happen, and files a request when the inputs moved.
     *
     * @param pomPath the POM the run would use (also decides the working directory)
     * @param command the exact command line that would be spawned - shown verbatim in the request
     * @param options {@code wait} (boolean, default true), {@code waitSeconds} (int),
     *                {@code reason} (the model's own words), {@code online} (boolean: whether the
     *                command would reach the network and write into the repository),
     *                {@code workingDir} (the directory the process is started in, when the caller
     *                knows one - otherwise the Maven base of {@code pomPath} is shown)
     * @return the answer, never null
     */
    public static Approval requestRun(Path pomPath, List<String> command,
                                      Map<String, Object> options) {
        Map<String, Object> opts = (options == null) ? Map.of() : options;
        boolean block = boolOption(opts, "wait", true);
        int namedWindow = intOption(opts, "waitSeconds", 0);
        int wait = clampWait(namedWindow == 0 ? DEFAULT_WAIT_SECONDS : namedWindow);
        // A blocking caller waits for `wait`; a non-blocking one names how long the question stays
        // open on the page, and the default there is longer on purpose - nobody is sitting on it.
        int window = block ? wait
                : clampWait(namedWindow == 0 ? DEFAULT_PENDING_WINDOW_SECONDS : namedWindow);
        String reason = stringOption(opts, "reason");
        boolean online = boolOption(opts, "online", false);
        String namedDir = stringOption(opts, "workingDir");
        // One anchor for everything this method shows and compares: a relative POM means the
        // watched project directory, so the request names the file the run would read
        // (PomWatch.anchor, docs/open-points.md §5).
        Path pomAbsolute = PomWatch.anchor(pomPath);
        Path workingDir = (namedDir != null && !namedDir.isBlank()) ? Path.of(namedDir)
                : (pomAbsolute == null) ? null : PomWatch.mavenBaseDir(pomAbsolute);

        PomWatch.Verdict verdict = PomWatch.check(pomPath);
        // "Is this still the operator's POM?" and "may this fetch bytes into the repository?" are
        // different questions: an approved POM says nothing about what dependency:get pulls, and
        // -o is hygiene, not execution control (docs/maven-guard.md §5 Nr. 3). So an online run is
        // asked about even when every watched input is unchanged - and a fetch that names no POM
        // at all is asked about too, because silence there would read as "nothing to decide".
        boolean noPomNamed = (pomPath == null);
        boolean askAlthoughUnchanged = online && PomWatch.mode() == PomWatch.Mode.ENFORCE
                && (verdict.allowed() || (noPomNamed && verdict.changes().isEmpty()));
        if (verdict.allowed() && !askAlthoughUnchanged) {
            return new Approval(true, reasonCodeOf(verdict.code()), null, null,
                    verdict.message(), verdict.changes());
        }
        if (verdict.changes().isEmpty() && !askAlthoughUnchanged) {
            // Nothing was compared, so nothing can be shown: a request here would be a coin flip
            // for the reviewer. The message names what to fix; this is not a yes.
            return new Approval(false, Reason.NOT_COMPARED, null, null, verdict.message(),
                    verdict.changes());
        }

        Request request = file(pomPath, workingDir, command, verdict, reason, online, window,
                askAlthoughUnchanged);
        ReviewEntry entry = request.entry();
        if (!block) {
            return new Approval(false, Reason.PENDING, entry.id(), entry.status().display(),
                    "request " + entry.id() + " is on /review (" + subjectOf(verdict,
                            askAlthoughUnchanged) + "). Ask here again once it is decided; with "
                            + "wait=false this call does not block.",
                    verdict.changes());
        }
        ReviewStatus outcome = await(request, wait);
        return afterDecision(outcome, entry, request, verdict, wait);
    }

    private static String subjectOf(PomWatch.Verdict verdict, boolean networkRun) {
        return (verdict.changes().isEmpty() && networkRun)
                ? "no changed input, but the command would write into the repository"
                : verdict.changes().size() + " changed input(s)";
    }

    /**
     * Puts the request on the list. The consumer is the only path on which the baseline moves:
     * approval adopts the listed files, denial adopts nothing.
     */
    private static Request file(Path pomPath, Path workingDir, List<String> command,
                                PomWatch.Verdict verdict, String reason, boolean online,
                                Integer windowSeconds, boolean networkRun) {
        List<Path> files = new ArrayList<>();
        for (PomWatch.Change change : verdict.changes()) {
            files.add(change.path());
        }
        List<ReviewAttribute> attributes = buildAttributes(pomPath, workingDir, command, verdict,
                online, networkRun);
        Object monitor = new Object();
        Request[] holder = new Request[1];
        boolean fetchOnly = networkRun && verdict.changes().isEmpty();
        String title = fetchOnly
                ? "Maven run reaching the repository" + ((pomPath == null) ? "" : " of '"
                    + PomWatch.rootLabel(rootOf(pomPath)) + "'")
                : "Maven after a POM change in '" + PomWatch.rootLabel(rootOf(pomPath)) + "' ("
                    + verdict.changes().size() + " changed input(s))";
        String why = (reason != null && !reason.isBlank()) ? reason
                : fetchOnly
                    ? "The command would download into the local repository. The project's Maven "
                        + "inputs are unchanged, so this request is about the fetch, not about a "
                        + "POM diff."
                    : "The model wants to run mvn, but this project's Maven inputs are no longer "
                        + "the state the server started with.";
        ReviewEntry entry = INSTANCE.createRequestEntry(ReviewPersistenceType.MEMORY, title,
                attributes, why, windowSeconds, (DecideConsumer) decision -> {
                    ReviewStatus target = decision.approved()
                            ? ReviewStatus.APPROVED : ReviewStatus.DENIED;
                    String at = (decision.decidedAt() == null || decision.decidedAt().isEmpty())
                            ? ReviewRequests.now() : decision.decidedAt();
                    // The CAS decides between a late approval and an expiry; the consumer reports
                    // the truth, never the intention.
                    Request request = holder[0];
                    // The status change and the wake-up belong in the same monitor block:
                    // otherwise the waiter checks entry.pending(), enters wait() and the decision
                    // lands between those two steps - a notify into nobody, and the caller holds
                    // until its timeout (exactly the failure ReviewEntry documents for
                    // moveFromPending).
                    synchronized (request.monitor()) {
                        request.entry().moveFromPending(target, decision.comment(), at);
                        ReviewStatus now = request.entry().status();
                        if (now == ReviewStatus.APPROVED) {
                            // Before the wake-up, so that the waiter's re-check sees exactly the
                            // state the approver has just released.
                            adoptBaseline(request);
                        }
                        request.monitor().notifyAll();
                        return now;
                    }
                });
        Request request = new Request(entry, pomPath, List.copyOf(files), List.copyOf(command),
                workingDir, monitor, List.of(), networkRun);
        holder[0] = request;
        REQUESTS.put(entry.id(), request);
        INSTANCE.submit(entry);
        LOG.info("MavenGuard: request {} ({}) of '{}' is on /review", entry.id(),
                subjectOf(verdict, networkRun), PomWatch.rootLabel(rootOf(pomPath)));
        return request;
    }

    /** What the reviewer sees: the command, the working directory, the files, the diff, the digests. */
    private static List<ReviewAttribute> buildAttributes(Path pomPath, Path workingDir,
                                                         List<String> command,
                                                         PomWatch.Verdict verdict, boolean online,
                                                         boolean networkRun) {
        List<ReviewAttribute> list = new ArrayList<>();
        list.add(ReviewAttributes.code("command", "Command that would run",
                String.join(" ", command), null));
        list.add(ReviewAttributes.path("workingDir", "Working directory",
                (workingDir == null ? "?" : workingDir.toString())));
        list.add(ReviewAttributes.path("pom", "Target POM",
                (pomPath == null ? "?" : PomWatch.anchor(pomPath).toString())));

        List<String> changed = new ArrayList<>();
        List<String> digests = new ArrayList<>();
        StringBuilder diff = new StringBuilder();
        for (PomWatch.Change change : verdict.changes()) {
            changed.add(change.kind().name().toLowerCase(Locale.ROOT) + ": "
                    + change.relativePath());
            digests.add(change.relativePath() + "  " + short12(change.baseline()) + " -> "
                    + short12(change.current()));
            if (change.patch() != null && !change.patch().isEmpty()) {
                diff.append(change.patch()).append('\n');
            }
        }
        list.add(ReviewAttributes.list("changed", "Changed Maven inputs", changed));
        list.add(ReviewAttributes.list("digests", "Digests baseline -> now", digests));
        if (diff.length() > 0) {
            list.add(ReviewAttributes.code("diff", "What has changed", diff.toString(), null));
        } else if (networkRun && verdict.changes().isEmpty()) {
            list.add(ReviewAttributes.text("noChange", "What this request is about",
                    "Nothing of the project changed - the watched inputs still match the baseline. "
                            + "What is asked here is the fetch itself: this command writes into the "
                            + "local repository" + ((pomPath == null)
                            ? " and runs without a project POM." : ".")));
        } else {
            list.add(ReviewAttributes.text("noDiff", "Why there is no diff",
                    "The previous content was not captured (write paths report it to "
                            + "FileChangeWatch since step 2 - this file was not reported, or its "
                            + "content was too large). Please compare the file in an editor against "
                            + "the baseline digest - the approval is about the content, not about "
                            + "this line."));
        }
        PomWatch.Summary state = PomWatch.summary();
        list.add(ReviewAttributes.text("guard", "State of the POM watch",
                "mode=" + state.mode().name().toLowerCase(Locale.ROOT) + ", phase="
                        + state.phase().name().toLowerCase(Locale.ROOT) + ", roots="
                        + state.roots().size() + ", watched files=" + state.fileCount()
                        + (state.problem() == null ? "" : ", problem: " + state.problem())));
        list.add(ReviewAttributes.text("network", "Network access",
                online ? "this command reaches the network and writes the artifact into the local "
                        + "repository (" + repositoryLabel() + "); the approval covers this run, it "
                        + "does not whitelist the artifact"
                        : "offline is planned (-o), nothing would be loaded that is not already "
                                + "in the local repository"));
        list.add(ReviewAttributes.text("scope", "What this approval means",
                "What is approved is that these POM inputs are the intended ones. Maven then runs "
                        + "the plugin code the POM chain names, and that is not covered here. The "
                        + "approval adopts the listed files as the new baseline."));
        return list;
    }

    /**
     * Blocks until the request has a decision or its window ends. The monitor is the one the
     * consumer notifies, and the horizon is read from the entry on every turn, so a
     * {@link #renew(String, Integer, String)} from another call extends this wait instead of
     * running out on a deadline taken at the start. The 2 s cap is a safety net for transitions
     * made without this call (the reviewer's page expiring a request), not the mechanism.
     */
    private static ReviewStatus await(Request request, int waitSeconds) {
        ReviewEntry entry = request.entry();
        long fallbackDeadline = System.currentTimeMillis() + waitSeconds * 1000L;
        synchronized (request.monitor()) {
            while (entry.pending()) {
                long horizon = (entry.expiresAtMillis() > 0L) ? entry.expiresAtMillis()
                        : fallbackDeadline;
                long rest = horizon - System.currentTimeMillis();
                if (rest <= 0) {
                    break;
                }
                try {
                    request.monitor().wait(Math.min(rest, 2000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (entry.pending()) {
            ReviewStore.getInstance().transitionIfPending(entry, ReviewStatus.EXPIRED,
                    "expired: no decision within " + waitSeconds + " s");
            synchronized (request.monitor()) {
                request.monitor().notifyAll();
            }
        }
        return entry.status();
    }

    /**
     * Turns a status into an answer. On approval the baseline has already moved (in the consumer),
     * so the caller can go ahead - but the check is done again, because a third change in the
     * meantime must not slip through on the strength of an older approval.
     */
    private static Approval afterDecision(ReviewStatus outcome, ReviewEntry entry, Request request,
                                          PomWatch.Verdict verdict, int waitSeconds) {
        if (outcome == ReviewStatus.APPROVED && request.withoutProjectInputs()) {
            return new Approval(true, Reason.APPROVED, entry.id(), outcome.display(),
                    "approved (network run)" + commentSuffix(entry) + ": " + oneWord(request),
                    List.of());
        }
        if (outcome == ReviewStatus.APPROVED) {
            PomWatch.Verdict recheck = PomWatch.check(request.pomPath());
            if (recheck.allowed()) {
                return new Approval(true, Reason.APPROVED, entry.id(), outcome.display(),
                        "approved" + commentSuffix(entry) + ": " + recheck.message(),
                        recheck.changes());
            }
            return new Approval(false, Reason.DENIED, entry.id(), outcome.display(),
                    "the approval is there, but the inputs changed again since then ("
                            + recheck.message() + ") - please ask again",
                    recheck.changes());
        }
        if (outcome == ReviewStatus.DENIED) {
            return new Approval(false, Reason.DENIED, entry.id(), outcome.display(),
                    "denied" + commentSuffix(entry), verdict.changes());
        }
        return new Approval(false, Reason.EXPIRED, entry.id(), outcome.display(),
                "no decision within " + waitSeconds + " s - renew('" + entry.id()
                        + "', ...) opens the window again", verdict.changes());
    }

    /** What a network run would have done - for these requests the command is the statement. */
    private static String oneWord(Request request) {
        return "the command was allowed to run once: " + String.join(" ", request.command());
    }

    private static String commentSuffix(ReviewEntry entry) {
        return (entry.comment() == null || entry.comment().isBlank())
                ? "" : " (comment: " + entry.comment() + ")";
    }

    /** The approval adopts its files; a failure to do so is said out loud, not swallowed. */
    private static void adoptBaseline(Request request) {
        if (request.files().isEmpty()) {
            return;                             // nothing was compared, so nothing can be adopted
        }
        try {
            int files = PomWatch.rebaseline(request.files(),
                    "approved review " + request.entry().id());
            LOG.info("MavenGuard: request {} approved, the baseline now covers {} watched file(s)",
                    request.entry().id(), files);
        } catch (RuntimeException e) {
            LOG.warn("MavenGuard: the baseline could not be adopted after {}",
                    request.entry().id(), e);
        }
    }

    // ========================================================================
    // Following up
    // ========================================================================

    /**
     * The state of an earlier request, so a non-blocking call can come back for the result.
     *
     * @param requestId the id from {@link #requestRun}
     * @return the answer; {@link Approval#allowed()} is true only for an approved request whose
     *         inputs are still the approved ones
     */
    public static Approval statusOf(String requestId) {
        Request request = known(requestId);
        if (request == null) {
            return unknown(requestId);
        }
        ReviewEntry entry = request.entry();
        if (request.pomPath() == null && !request.networkRun()) {
            // The request is known but its subject is not (a decision taken after a restart of the
            // guard's own bookkeeping). Saying "approved, go ahead" would promise a check nobody can
            // redo, so the answer is honest about what is missing.
            return new Approval(false, Reason.FORGOTTEN, entry.id(), entry.status().display(),
                    "request " + entry.id() + " is known, but its POM check is not - please "
                            + "ask again", List.of());
        }
        ReviewStatus status = entry.status();
        if (status == ReviewStatus.PENDING) {
            if (entry.waitSeconds() != null && entry.expiresAtMillis() > 0
                    && System.currentTimeMillis() > entry.expiresAtMillis()) {
                ReviewStore.getInstance().transitionIfPending(entry, ReviewStatus.EXPIRED,
                        "expired: no decision (asked again for " + entry.id() + ")");
                status = entry.status();
            }
        }
        if (status == ReviewStatus.APPROVED && request.withoutProjectInputs()) {
            return new Approval(true, Reason.APPROVED_EARLIER, entry.id(), status.display(),
                    "approved (network run)" + commentSuffix(entry) + ": " + oneWord(request),
                    List.of());
        }
        if (status == ReviewStatus.APPROVED) {
            PomWatch.Verdict recheck = PomWatch.check(request.pomPath());
            if (recheck.allowed()) {
                return new Approval(true, Reason.APPROVED_EARLIER, entry.id(),
                        status.display(), "approved" + commentSuffix(entry) + ": "
                                + recheck.message(), recheck.changes());
            }
            return new Approval(false, Reason.PENDING, entry.id(), status.display(),
                    "the inputs changed again since the approval: " + recheck.message(),
                    recheck.changes());
        }
        return new Approval(false, codeOf(status), entry.id(), status.display(),
                "request " + entry.id() + " has the status '" + status.display() + "'"
                        + commentSuffix(entry), List.of());
    }

    /**
     * Re-arms a pending request's window so the model does not have to file it again. A request
     * that already expired or was decided is left as it is - that state was shown on the page.
     *
     * @param requestId   the request
     * @param waitSeconds new window, null to keep the previous one
     * @param note        why the model asks again (goes to the log and the follow-up answer)
     * @return the answer with the request's current status
     */
    public static Approval renew(String requestId, Integer waitSeconds, String note) {
        Request request = known(requestId);
        if (request == null) {
            return unknown(requestId);
        }
        ReviewEntry entry = request.entry();
        if (!entry.pending()) {
            return new Approval(entry.status() == ReviewStatus.APPROVED,
                    codeOf(entry.status()), entry.id(), entry.status().display(),
                    "no longer open (" + entry.status().display() + ") - renew only concerns "
                            + "pending requests", List.of());
        }
        int seconds = clampWait(waitSeconds == null
                ? (entry.waitSeconds() == null ? DEFAULT_WAIT_SECONDS : entry.waitSeconds())
                : waitSeconds);
        entry.armWindow(seconds);
        String line = "window re-armed " + ReviewRequests.now() + " for " + seconds + " s"
                + ((note == null || note.isBlank()) ? "" : ": " + note.trim());
        Request renewed = request.withRenewal(line);
        REQUESTS.put(entry.id(), renewed);
        LOG.info("MavenGuard: request {} {}", entry.id(), line);
        synchronized (renewed.monitor()) {
            renewed.monitor().notifyAll();      // a waiter reads the new horizon from the entry
        }
        return new Approval(false, Reason.PENDING, entry.id(), entry.status().display(),
                "window re-armed (" + seconds + " s)" + ((note == null || note.isBlank())
                        ? "" : " - " + note.trim()), List.of());
    }

    /**
     * Reports what the approved work produced, so the reviewer can read the outcome next to the
     * decision they made. Called by the Maven invocation itself (step 3), not by a model.
     *
     * @param requestId  the approved request
     * @param ok         whether the run succeeded
     * @param summary    one line ("exit 0, 99 classpath entries")
     * @param detail     outcome attributes (exit code, duration, entry count), may be empty
     * @param durationMs how long it ran
     */
    public static void reportRun(String requestId, boolean ok, String summary,
                                 List<ReviewAttribute> detail, long durationMs) {
        Request request = known(requestId);
        if (request == null) {
            LOG.warn("MavenGuard: a result was reported for unknown request {}", requestId);
            return;
        }
        ReviewResponseEntry response = ok
                ? ReviewResponseEntry.completed(summary, detail, durationMs)
                : ReviewResponseEntry.failed(summary, "see the output", detail, durationMs);
        INSTANCE.submitResult(request.entry(), response);
        REQUESTS.remove(requestId);
    }

    /**
     * Moves a still-pending request to {@link ReviewStatus#EXPIRED}: the caller that asked can no
     * longer deliver the outcome (its call was cancelled or timed out), so leaving it on the page
     * would strand a decision nobody will ever read.
     *
     * @param requestId the request
     * @return true if it was pending and is expired now
     */
    public static boolean expireIfOpen(String requestId) {
        boolean moved = INSTANCE.expireIfPending(requestId);
        if (moved) {
            REQUESTS.remove(requestId);
        }
        return moved;
    }

    /** Drop the follow-up bookkeeping. Tests only; the review list keeps its own history. */
    static void resetForTests() {
        REQUESTS.clear();
    }

    /** Requests this guard has filed, newest first (for tests and a later admin view). */
    public List<ReviewEntry> openRequests() {
        return new ArrayList<>(INSTANCE.listEntries());
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private static Request known(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return null;
        }
        Request request = REQUESTS.get(requestId.trim());
        if (request != null) {
            return request;
        }
        // After a restart the request is gone from the store too; a request of another tool is not
        // ours to answer either way.
        ReviewEntry entry = ReviewStore.getInstance().get(requestId.trim());
        if (entry == null || !TOOL_ID.equals(entry.toolId())) {
            return null;
        }
        // The entry is known but its context is not (a re-baseline happened through the page). The
        // status is still honest; only a follow-up run cannot be reconstructed from it.
        return new Request(entry, null, List.of(), List.of(), null, new Object(), List.of(),
                false);
    }

    private static Approval unknown(String requestId) {
        return new Approval(false, Reason.UNKNOWN_REQUEST, requestId, null,
                "no request '" + requestId + "' of this tool - requestRun(..., {wait:false}) "
                        + "files one", List.of());
    }

    private static Reason codeOf(ReviewStatus status) {
        return switch (status) {
            case APPROVED -> Reason.APPROVED;
            case DENIED -> Reason.DENIED;
            case EXPIRED, VOIDED -> Reason.EXPIRED;
            case PENDING, RUNNING -> Reason.PENDING;
            case COMPLETED -> Reason.APPROVED_EARLIER;
            case ERROR -> Reason.FORGOTTEN;
        };
    }

    private static Reason reasonCodeOf(PomWatch.Code code) {
        return switch (code) {
            case UNCHANGED -> Reason.UNCHANGED;
            case GUARD_OFF -> Reason.GUARD_OFF;
            case OUTSIDE_WATCHED_ROOTS -> Reason.OUTSIDE;
            // reached only with pomGuard=warn: there is a change, and it was let through
            case CHANGED -> Reason.CHANGED_BUT_WARN;
            default -> Reason.NOT_COMPARED;
        };
    }

    /** The repository a fetch would write into, or a placeholder when it cannot be resolved. */
    private static String repositoryLabel() {
        try {
            return M2Resolver.repository().toString();
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    private static Path rootOf(Path pomPath) {
        if (pomPath == null) {
            return null;
        }
        // anchored like every other input of the guard, so a relative POM is attributed to the
        // project it belongs to and not to the directory this server was started in
        Path absolute = PomWatch.anchor(pomPath).toAbsolutePath().normalize();
        for (Path root : PomWatch.roots()) {
            if (absolute.startsWith(root)) {
                return root;
            }
        }
        return absolute.getParent();
    }

    private static String short12(PomWatch.Finger finger) {
        if (finger == null || finger.sha256() == null) {
            return "(none)";
        }
        String sha = finger.sha256();
        return sha.length() <= 12 ? sha : sha.substring(0, 12);
    }

    private static List<String> appended(List<String> list, String line) {
        List<String> out = new ArrayList<>(list);
        out.add(line);
        return List.copyOf(out);
    }

    private static int clampWait(int seconds) {
        if (seconds < 1) {
            return DEFAULT_WAIT_SECONDS;
        }
        return Math.min(seconds, MAX_WAIT_SECONDS);
    }

    private static boolean boolOption(Map<String, Object> opts, String key, boolean def) {
        Object value = opts.get(key);
        if (value == null) {
            return def;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        throw new IllegalArgumentException("Option '" + key + "' must be a boolean");
    }

    private static int intOption(Map<String, Object> opts, String key, int def) {
        Object value = opts.get(key);
        if (value == null) {
            return def;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        throw new IllegalArgumentException("Option '" + key + "' must be a number");
    }

    private static String stringOption(Map<String, Object> opts, String key) {
        Object value = opts.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String s)) {
            throw new IllegalArgumentException("Option '" + key + "' must be a string");
        }
        return s;
    }
}
