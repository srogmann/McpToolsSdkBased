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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * The field model of one application (M2): where the value of a field comes from.
 *
 * <p><b>Informative, not regulatory.</b> A slot answers a question about the <em>application</em>,
 * never the question what the model is <em>allowed</em> to do. That stays the decision of
 * {@code browser.write.enabled} (L6) and of the four-eyes gate (L8). Everything here is switched off by
 * default ({@code browser.fields.mode=off}): an M1 session with it off runs exactly like before - no
 * value pass, no annotation, no guard.</p>
 *
 * <p><b>What one piece of evidence is.</b> The bridge reads the writable fields of a page as a keyed
 * map (the value pass, §3b of {@code bridge.js}) before and after an operation. Something that changed
 * although nobody wrote into it is computed by the application - that single observation is worth more
 * than any heuristic over field names, and it is what {@link Slot#COMPUTED} means here. The comparison
 * runs in two shapes: around a write (the diff the operation reports) and <em>passively</em> between two
 * reads of one page view - because the actions that make a page recalculate are usually taken by the
 * person in front of the screen, and a human action never runs a value pass ({@link
 * #compareWithinLoad}). The rest is counting: typed by a hand ⇒ {@link Slot#PARAMETER}, a datalist on the
 * control ⇒ {@link Slot#LOOKUP}, one and the same value over two separate loads <b>of two different
 * records</b> ⇒ {@link Slot#CONSTANT} (see {@link #isConstantCandidate} - both conditions came out of
 * live runs that got it wrong), a rule on the control itself ⇒ {@link Slot#POLICY}. Everything else stays
 * {@link Slot#UNKNOWN}, and saying so is the point: in a development setting there are three sessions
 * of a form, not three thousand, and a model that guesses across that gap is worse than one that
 * admits it.</p>
 *
 * <p><b>Values never stand in it.</b> This is the first artifact of the package that outlives a
 * session, so it is the first one worth leaking. A value enters as {@code sha256:<16 hex>/<length>} -
 * enough to tell "always the same" from "varies", not enough to read a customer number out of a file
 * somebody commits by accident. Where a literal constant is really wanted (M3 replay), the operator
 * declares it in the registry - it is not something this class infers into a long-lived file.</p>
 *
 * <p><b>The payload side (M2b).</b> A page does not only show fields, it also <em>sends</em> names -
 * and the two sets are not the same in either direction. {@code bridge.js} skips
 * {@code <input type=hidden>} in the snapshot and in the value pass on purpose, so
 * {@code noteText}/{@code userid}/{@code id}/{@code action} reach the application without ever
 * appearing in a view of the page; the other way round, a field whose value the application hands out
 * (a decoder's output box, a counter) arrives with a <em>new</em> page view, so no comparison inside
 * one document can call it produced - that was the case the passive reading of two reads cannot reach.
 * {@link #noteSent} therefore feeds the model what {@link BrowserPayload} reads out of the redacted
 * request body of every proxied exchange: names joined to the rows they belong to, unknown names kept
 * as rows of their own under {@link #PAYLOAD_PREFIX}, and - the only inference drawn from
 * <em>absence</em> anywhere here - a field of a form whose payloads were all readable and which no
 * payload ever carried is {@link Slot#COMPUTED} as well. Absence counts only where presence was
 * provable: a payload we were not allowed to read ({@code sensitivePaths}) or could not parse marks
 * its page and switches that claim off there, a name that is a secret name never claims anything, and
 * a control the browser only sends when it is chosen (checkbox, radio, a {@code contenteditable} that
 * is not a form control at all) is excluded by its role.</p>
 *
 * <p><b>Storage.</b> {@code <run base>/fields/<appId>.jsonl}, append-only, anchored in the project base
 * like everything else (L10). One line per <em>slot change</em> of a field, carrying that field's
 * evidence at the moment of the change; reading the file back therefore reproduces the model exactly.
 * Beside them one line per <em>payload</em> an exchange carried ({@code kind: "payload"}): the names
 * this application has been seen sending and, per page, whether its payloads were readable. Those
 * presence facts are the ground of the absence claim above - a restart that lost them would let a
 * field that is very much submitted look never-sent, which is the one wrong {@code computed} this
 * model must not produce. Without an anchored location the model lives in memory only and
 * {@link #location()} says why instead of naming a guessed directory.</p>
 */
public final class BrowserFieldModel {

    private static final Logger LOG = LoggerFactory.getLogger(BrowserFieldModel.class);

    /** At most this many fields of one application are kept in memory (the file holds what it holds). */
    static final int KEPT_FIELDS = 4000;

    /**
     * Where the value of a field comes from.
     */
    public enum Slot {
        /** always the same value on this application - needs two observations, never inferred from one */
        CONSTANT("the same value on at least two separate page loads, and nobody writes it"),
        /** a hand decides it per case - the user or the model types it */
        PARAMETER("someone typed into it (user or model)"),
        /** the application offers candidates on the field itself (datalist); endpoint correlation is M2b */
        LOOKUP("the application offers this value as a suggestion list on the field"),
        /**
         * The application produces the value - either <em>seen</em> doing it (a recalculation inside
         * one page view, the strongest evidence in this model) or from the payload side: no request of
         * this application ever carried the name, while every page view of the form shows a value in
         * the field. Both mean the same for a write: pointless at best, a lie at worst.
         */
        COMPUTED("the application changed this value on its own while the page rested"),
        /** a rule applies to it (mandatory, pattern, length, range) - stated by the control */
        POLICY("the page states a rule for this field"),
        /** nothing observed yet */
        UNKNOWN("no evidence in this application so far");

        private final String basis;

        Slot(String basis) {
            this.basis = basis;
        }

        /**
         * @return why this slot was chosen, in one honest sentence (shown to the model and to the
         *         reviewer, who reads it under time pressure)
         */
        public String basis() {
            return basis;
        }

        /**
         * @param name slot text as it appears in a log line (case-insensitive, may be {@code null})
         * @return the slot or {@link #UNKNOWN} - nothing here is guessed from a lookalike
         */
        public static Slot of(String name) {
            if (name == null) {
                return UNKNOWN;
            }
            String wanted = name.trim().toUpperCase(Locale.ROOT);
            for (Slot slot : values()) {
                if (slot.name().equals(wanted)) {
                    return slot;
                }
            }
            return UNKNOWN;
        }
    }

    private final String appId;
    private final Path file;
    /**
     * Protects {@link #fields}. A {@link ReentrantLock} and not {@code synchronized}, because the
     * methods here nest (a reader takes the lock and derives from the node it holds) and because the
     * file write deliberately sits <em>outside</em> it: a hanging disk must not stop a page from being
     * classified - the same split {@link BrowserSession} practices with its second {@code FILE_LOCK}.
     */
    private final Lock LOCK = new ReentrantLock();

    /** identity ({@code page + " " + field key}) -&gt; the evidence collected for that field. */
    private final Map<String, ObjectNode> fields = new LinkedHashMap<>();

    /**
     * Prefix of the identity of a name that <b>only the payload knows</b> (M2b, docs/browser.md §7a):
     * a hidden input is in no snapshot and in no value pass, so the request that carries it is the
     * only place it shows itself. The prefix is not a §4a field key - it says "no control of the page
     * explains this name", and that is exactly the information a reader needs to stop looking for a ref
     * to write into.
     */
    public static final String PAYLOAD_PREFIX = "sent:";

    /**
     * Why a {@code computed} claim made from the payload side is not the same sentence as the observed
     * one, and must not pretend to be: nothing was ever seen changing here, the field is only never
     * seen <em>going away</em>. A constant, because the tool answer, the log line, the review entry and
     * the tests all quote it - and prose that is retyped is prose that drifts.
     */
    static final String PRODUCED_BASIS = "no payload of this application ever carried this field while"
            + " every page view of the form shows a value in it - the application produces it (not one"
            + " recalculation was actually observed)";

    /** The strict mode's refusal for a value it <em>saw</em> being produced. */
    static final String GUARD_OBSERVED = "this field is computed by the application (observed changing"
            + " on its own) - writing into it is probably not what was meant.";

    /** The strict mode's refusal for a value the payloads say the application produces. */
    static final String GUARD_PRODUCED = "this field is produced by the application: no payload of this"
            + " form ever carried it, while the page shows a value in it - writing into it is probably"
            + " not what was meant.";

    /** Both refusals end the same way - a block that does not name its way out trains a reflex. */
    static final String GUARD_WAY_OUT = " Ask again with force:true on this entry if you want it"
            + " anyway.";

    /**
     * Controls the browser leaves out of a payload on purpose (a box that is not ticked, a radio that
     * is not chosen) or that are no form controls at all (the {@code contenteditable} of a note). For
     * them absence from a payload says nothing about who produced the value.
     */
    private static final List<String> NOT_ALWAYS_SENT = List.of("checkbox", "radio", "switch",
            "richtext", "button", "passwordbox", "file");

    /**
     * Names this application has been seen sending (normalized name -&gt; payloads carrying it,
     * M2b). <b>Presence</b> is what the absence claim of {@link #isProduced} needs, so this is a set of
     * facts, not a counter of the last page view - and it is rebuilt from the {@code payload} lines of
     * the file, because a restart that forgot it would invent a {@code computed} field.
     */
    private final Map<String, Integer> sentNames = new LinkedHashMap<>();

    /**
     * Per page of this application: how many payloads went there, how many of them carried a name of a
     * field known on that page ({@code joined} - the proof that this page's form really has been
     * submitted in front of us) and how many could not be read ({@code gaps}). Guard of every claim
     * built on absence.
     */
    private final Map<String, ObjectNode> payloadPages = new LinkedHashMap<>();

    /** Field-name matchers of this application (its registry entry plus the predefined ones). */
    private final BrowserSecrets secrets;

    BrowserFieldModel(String appId, Path file) {
        this.appId = appId;
        this.file = file;
        // The application's own secret field names belong to this decision (a field called `pin` is
        // secret in one application and ordinary in the next), so the registry is asked - and asked
        // with a fallback, because inside the BrowserSessions constructor get() still answers null
        // (§2b, the same trap named there) and an unregistered id is the normal case in a test.
        BrowserSessions backend = BrowserSessions.get();
        List<String> extraKeys = (backend == null) ? List.of() : backend.app(appId)
                .map(BrowserApp::sensitiveKeys).orElse(List.of());
        this.secrets = new BrowserSecrets(extraKeys);
        load();
    }

    // ========================================================================
    // Identity
    // ========================================================================

    /**
     * The page part of a field's identity: the path as the application sees it, query and fragment cut
     * away. {@code /report?id=DID-0013} and {@code /report?id=DID-0014} are one form, and their fields
     * have to count as observations <em>of it</em> - otherwise the model would learn nothing across two
     * reports of the same tracker, which is the whole point of collecting evidence.
     *
     * @param path a path with or without query
     * @return the path alone, never empty
     */
    public static String pageOf(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String clean = path.trim();
        int cut = clean.indexOf('?');
        if (cut < 0) {
            cut = clean.indexOf('#');
        }
        if (cut == 0) {
            return "/";
        }
        return cut > 0 ? clean.substring(0, cut) : clean;
    }

    private static String identity(String page, String key) {
        return pageOf(page) + " " + key;
    }

    /**
     * A value as it may stand in a file that outlives the session: a digest and a length, never text.
     *
     * @param value the value the page holds (may be empty, may be {@link BrowserSecrets#MASK})
     * @return {@code sha256:<16 hex>/<characters>}
     */
    static String digest(String value) {
        String text = value == null ? "" : value;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8 && i < hash.length; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return "sha256:" + hex + "/" + text.length();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JDK. A runtime without it is a broken runtime, and the
            // broken thing has to say so: quietly storing something else instead of a digest would
            // leave the model looking intact while its "constant" claim rests on nothing.
            throw new IllegalStateException("browser: this JVM has no SHA-256, so the field model "
                    + "cannot store a value without storing the value - fix the runtime, or run with"
                    + " browser.fields.mode=off", e);
        }
    }

    // ========================================================================
    // Evidence
    // ========================================================================

    /**
     * Records one value pass (the {@code observe} command, or the map a write reported).
     *
     * @param session   the session the pass ran in
     * @param page      the page of the pass, {@code null} to use the session's
     * @param via       what asked for it: {@code observe}, {@code fill}, {@code click}, {@code undo}
     * @param entries   the {@code fields[]} array of the bridge
     * @param truncated true when the budget cut the pass short - presence is evidence, absence is not
     */
    public void noteValues(BrowserSession session, String page, String via, JsonNode entries,
            boolean truncated) {
        if (entries == null || !entries.isArray()) {
            return;
        }
        String cleanPage = pageOf(page != null && !page.isBlank() ? page : session.pageForDisplay());
        String document = (session == null) ? "single document" : session.loadId();
        String border = (session == null) ? "single border" : session.pageBorderId();
        // The record this observation belongs to - the URL with its query, which is exactly what the
        // page half of the identity throws away. Two reads of one record say nothing about a form;
        // two records that agree do.
        String origin = (page != null && !page.isBlank()) ? page
                : (session != null ? session.pageForDisplay() : "");
        int touched = 0;
        for (JsonNode entry : entries) {
            String key = entry.path("key").asString("");
            if (key.isEmpty()) {
                continue;
            }
            boolean secret = entry.path("secret").asBoolean(false);
            String valueDigest = digest(entry.path("value").asString(""));
            String label = entry.path("label").asString("");
            String role = entry.path("role").asString("");
            JsonNode given = entry.path("hints");
            update(cleanPage, key, field -> {
                if (!label.isEmpty()) {
                    field.put("label", label);
                }
                if (!role.isEmpty()) {
                    field.put("role", role);
                }
                bump(field, "observations");
                noteLoad(field, document);
                noteOrigin(field, origin);
                if (!secret) {
                    addDigest(field, valueDigest);
                }
                // Two reads inside one page view that disagree, with no event to explain it, are the
                // application having produced the value itself - the same statement a write diff makes,
                // only from looking instead of from acting.
                compareWithinLoad(field, border, secret ? null : valueDigest, session, key);
                for (String name : List.of("required", "readonly", "disabled", "maxlength", "pattern",
                        "min", "max", "datalist", "optionCount")) {
                    JsonNode value = given.path(name);
                    if (!value.isMissingNode() && !value.isNull()) {
                        // set(), not put(): the hint may be a boolean, a number or a string and the
                        // node it comes from is not ours to keep.
                        hints(field).set(name, value.deepCopy());
                    }
                }
            }, via);
            touched++;
        }
        if (truncated) {
            LOG.debug("browser: value pass on {} of {} reached the budget - {} field(s) seen, the rest"
                    + " proves nothing", cleanPage, appId, touched);
        }
    }

    /**
     * Feeds the model from a snapshot instead of from a value pass (M2). A snapshot element that
     * carries a {@code key} is the same statement as a line of the value map: this field exists on this
     * page and this holds in it now - so the ordinary looking of an LLM at a page is already evidence,
     * and nobody has to run a classification first. What a snapshot cannot say is the rule text of a
     * control beyond required/readonly/disabled, so those hints come from the value pass alone.
     *
     * @param session  the session the snapshot came from (its page is the identity)
     * @param snapshot the stored snapshot node, frames and elements included
     */
    public void noteSnapshot(BrowserSession session, JsonNode snapshot) {
        String page = pageOf(session.pageForDisplay());
        String document = session.loadId();
        String border = session.pageBorderId();
        String origin = session.pageForDisplay();
        for (JsonNode frame : snapshot.path("frames")) {
            for (JsonNode element : frame.path("ax")) {
                String key = element.path("key").asString("");
                if (key.isEmpty()) {
                    continue;               // not a field: a link, a cell, a heading
                }
                boolean secret = element.path("secret").asBoolean(false)
                        || "password".equalsIgnoreCase(element.path("type").asString(""));
                String valueDigest = digest(element.path("value").asString(""));
                String label = element.path("label").asString("");
                String role = element.path("role").asString("");
                boolean required = element.path("required").asBoolean(false);
                boolean readonly = element.path("readonly").asBoolean(false);
                boolean disabled = element.path("disabled").asBoolean(false);
                boolean hasOptions = element.path("options").isArray()
                        && element.path("options").size() > 0;
                update(page, key, field -> {
                    if (!label.isEmpty()) {
                        field.put("label", label);
                    }
                    if (!role.isEmpty()) {
                        field.put("role", role);
                    }
                    bump(field, "observations");
                    noteLoad(field, document);
                    noteOrigin(field, origin);
                    if (!secret) {
                        addDigest(field, valueDigest);
                    }
                    if (required) {
                        hints(field).put("required", true);
                    }
                    if (readonly) {
                        hints(field).put("readonly", true);
                    }
                    if (disabled) {
                        hints(field).put("disabled", true);
                    }
                    if (hasOptions) {
                        hints(field).put("optionCount", element.path("options").size());
                    }
                    compareWithinLoad(field, border, secret ? null : valueDigest, session, key);
                }, "snapshot");
            }
        }
    }

    /**
     * Records the diff a write operation reported (§7a): what the operation wrote counts as a
     * parameter, what changed <em>beyond</em> it as computed. An inconclusive diff - the page did not
     * come to rest, the pass failed, the budget cut it - is not evidence; it is named in the debug log
     * instead of quietly becoming a wrong slot.
     *
     * @param session the session the operation ran in
     * @param page    the page of the operation
     * @param via     {@code fill}, {@code click} or {@code undo}
     * @param diff    the bridge's {@code diff} node
     */
    public void noteDiff(BrowserSession session, String page, String via, JsonNode diff) {
        if (diff == null || diff.isMissingNode() || !diff.isObject()) {
            return;
        }
        String inconclusive = diff.path("inconclusive").asString("");
        if (!inconclusive.isEmpty()) {
            LOG.debug("browser: {} diff on {} not used as evidence ({})", via, pageOf(page),
                    inconclusive);
            return;
        }
        String cleanPage = pageOf(page != null && !page.isBlank() ? page : session.pageForDisplay());
        int written = 0;
        int recalculated = 0;
        for (JsonNode change : diff.path("changed")) {
            String key = change.path("key").asString("");
            if (key.isEmpty()) {
                continue;
            }
            boolean byUs = change.path("written").asBoolean(false);
            String label = change.path("label").asString("");
            String to = change.path("to").asString("");
            update(cleanPage, key, field -> {
                if (!label.isEmpty()) {
                    field.put("label", label);
                }
                bump(field, byUs ? "written" : "recalculated");
                // The value the application produced counts as an observation of the field; the value
                // the operation wrote does not (that would let a model-set value mature into a
                // "constant"), and a redacted one is no value at all.
                if (!byUs && !BrowserSecrets.MASK.equals(to)) {
                    addDigest(field, digest(to));
                }
            }, via);
            if (byUs) {
                written++;
            } else {
                recalculated++;
            }
        }
        if (written + recalculated > 0) {
            LOG.debug("browser: {} diff on {}: {} field(s) written, {} recalculated by the application",
                    via, cleanPage, written, recalculated);
        }
    }

    /**
     * A field a hand typed into ({@code input}/{@code change} event carrying a field key) - the
     * evidence that separates "someone decides this" from "the page decides this".
     *
     * @param session the session the event came from
     * @param key     field key as the bridge reported it
     * @param label   label of the control, may be empty
     * @param value   the typed value - hashed, never stored
     */
    public void noteUserInput(BrowserSession session, String key, String label, String value) {
        if (key == null || key.isEmpty()) {
            return;
        }
        String page = pageOf(session.pageForDisplay());
        String valueDigest = digest(value);
        update(page, key, field -> {
            if (label != null && !label.isEmpty()) {
                field.put("label", label);
            }
            bump(field, "userTyped");
            addDigest(field, valueDigest);
        }, "event");
    }

    /**
     * @param session the session the write ran in (unused today, kept for the log line it will carry)
     * @param page    page of the form
     * @param key     field key
     * @param via     what wrote into it ({@code fill}, {@code undo})
     */
    public void noteWritten(BrowserSession session, String page, String key, String via) {
        if (key == null || key.isEmpty()) {
            return;
        }
        update(pageOf(page), key, field -> bump(field, "written"), via);
    }

    /**
     * Records what one request of this application carried (M2b, docs/browser.md §7a) - the payload
     * side of the field model, joined to the fields the page side already knows.
     *
     * <p>Three things happen here and each of them answers a question the DOM cannot:</p>
     * <ol>
     *   <li><b>a name meets the field it came from.</b> {@code summary} in a body and
     *       {@code f0|#reportForm|summary|…} on the page are one field; the row gains a
     *       {@code sent} counter. A payload only ever says <em>names</em>, never frames or forms, so
     *       this join is by name and says so - the two sides agree because applications name their
     *       controls after what the server reads, not because anything proves it.</li>
     *   <li><b>a name nobody can show.</b> A hidden input is in no snapshot on purpose; the request
     *       carrying it is the only place it appears. Such a name becomes a row of its own under
     *       {@link #PAYLOAD_PREFIX}, which is what tells a reader "the form sends this and you cannot
     *       fill it" apart from "this form has no more fields".</li>
     *   <li><b>what is missing.</b> A page whose payloads were all readable, and whose form really has
     *       been submitted in front of us ({@code joined} ≥ 1), is a page where absence means
     *       something: the field no payload carried is produced by the application. That is the only
     *       inference in this model drawn from <em>not</em> seeing something, so every way it can be
     *       wrong is closed here - a gap on the page ({@code sensitivePaths}, an unparsable body), a
     *       secret name, a control the browser legitimately leaves out (checkbox, radio, a
     *       {@code contenteditable}), a field a hand has moved, a field that never held a value.</li>
     * </ol>
     *
     * @param session the session the exchange ran in (its page is the fallback identity)
     * @param page    the path the payload was sent to - which is where the names belong, and not
     *                always where the form was rendered: a form posting to another endpoint puts its
     *                names there, and the row says so under that page
     * @param reading what {@link BrowserPayload} read out of the <b>redacted</b> body
     */
    public void noteSent(BrowserSession session, String page, BrowserPayload.Reading reading) {
        if (reading == null || reading.silent()) {
            return;
        }
        String cleanPage = pageOf(page != null && !page.isBlank() ? page
                : (session == null ? "/" : session.pageForDisplay()));
        Set<String> carried = new LinkedHashSet<>();
        Map<String, String> valuesByName = new LinkedHashMap<>();
        for (BrowserPayload.Field field : reading.fields()) {
            String normalized = BrowserSecrets.normalize(field.name());
            carried.add(normalized);
            valuesByName.putIfAbsent(normalized, field.value());
        }
        List<ObjectNode> slotLines = new ArrayList<>();
        List<BrowserPayload.Field> births = new ArrayList<>();
        ObjectNode line;
        boolean joined;
        LOCK.lock();
        try {
            ObjectNode record = payloadRecord(cleanPage);
            Set<String> matched = new LinkedHashSet<>();
            if (!reading.gap().isEmpty()) {
                // "we could not read it" is not "it was not in there": the page gets a gap and no name,
                // because the difference between "never sent" and "not allowed to see whether it was
                // sent" is the whole point - collapsing them would make the one claim built on absence
                // a guess about redaction.
                joined = false;
                record.put("gaps", record.path("gaps").asInt(0) + 1);
                addToList(array(record, "gapReasons"), reading.gap(), 4);
            } else {
                record.put("payloads", record.path("payloads").asInt(0) + 1);
                boolean anyJoined = false;
                for (Map.Entry<String, ObjectNode> entry : fields.entrySet()) {
                    ObjectNode field = entry.getValue();
                    String key = field.path("key").asString("");
                    if (key.isEmpty() || key.startsWith(PAYLOAD_PREFIX)) {
                        continue;                   // a name of a payload: nothing to join to
                    }
                    String name = nameOf(key);
                    String normalized = BrowserSecrets.normalize(name);
                    if (name.isEmpty() || !carried.contains(normalized)) {
                        continue;
                    }
                    matched.add(normalized);
                    String rowPage = identityPage(entry.getKey());
                    Slot before = deriveSlot(rowPage, field);
                    bump(field, "sent");
                    noteSentValue(field, name, valuesByName.get(normalized));
                    Slot after = deriveSlot(rowPage, field);
                    if (before != after) {
                        slotLines.add(slotLine(rowPage, key, field, before, after, "sent"));
                    }
                    if (rowPage.equals(cleanPage)) {
                        anyJoined = true;
                    }
                }
                joined = anyJoined;
                if (joined) {
                    record.put("joined", record.path("joined").asInt(0) + 1);
                }
                if (reading.formish()) {
                    for (BrowserPayload.Field field : reading.fields()) {
                        String normalized = BrowserSecrets.normalize(field.name());
                        if (!matched.add(normalized)) {
                            continue;   // joined to a field of the page, or the second value of one name
                        }
                        births.add(field);
                    }
                }
                carried.forEach(name -> sentNames.merge(name, 1, Integer::sum));
            }
            line = payloadLine(cleanPage, reading, joined);
        } finally {
            LOCK.unlock();
        }
        // Everything that reaches the disk runs outside the lock (a hanging volume must not stop the
        // next page from being classified), so a new name goes through update() - which takes the lock
        // itself, records the first sight and writes the line.
        for (BrowserPayload.Field field : births) {
            String name = field.name();
            update(cleanPage, PAYLOAD_PREFIX + name, row -> {
                row.put("label", name);
                bump(row, "sent");
                noteSentValue(row, name, field.value());
            }, "sent");
        }
        append(line);
        slotLines.forEach(this::append);
        if (!reading.gap().isEmpty()) {
            LOG.debug("browser: payload on {} of {} not readable ({}): no claim may rest on it",
                    cleanPage, appId, reading.gap());
        }
    }

    /**
     * One change of one field's evidence, wrapped so that the moment something is <em>worth writing
     * down</em> is the moment it is written: a slot that was reached, and the first time a field is
     * seen at all. Both go to the file, because the observation counter is what a `constant` claim
     * needs two sessions later - a restart that dropped the quiet fields would make the model
     * silently dumber while still naming a file as its source.
     */
    private void update(String page, String key, Consumer<ObjectNode> mutation, String via) {
        String identity = identity(page, key);
        String cleanPage = pageOf(page);
        ObjectNode line = null;
        Slot after;
        LOCK.lock();
        try {
            boolean born = !fields.containsKey(identity);
            ObjectNode field = field(identity, key);
            Slot before = deriveSlot(cleanPage, field);
            mutation.accept(field);
            if (born && !key.startsWith(PAYLOAD_PREFIX)) {
                // The page side can arrive after the payload side (a first submit before the page was
                // ever read). The join has to be completed here too, or the field would keep looking
                // "never sent" while the application has demonstrably been receiving its name.
                String name = nameOf(key);
                Integer seen = name.isEmpty() ? null : sentNames.get(BrowserSecrets.normalize(name));
                if (seen != null && seen > 0) {
                    field.put("sent", seen);
                    addSource(field, "sent");
                }
            }
            field.put("lastSeen", Instant.now().toString());
            addSource(field, via);
            after = deriveSlot(cleanPage, field);
            if (before == after && !born) {
                return;
            }
            line = slotLine(cleanPage, key, field, before, after, via);
            line.put("kind", born && before == after ? "seen" : "slot");
        } finally {
            LOCK.unlock();
        }
        // The file write runs outside the lock: a disk that hangs must not hold up the next page.
        append(line);
        if (after != null && !"slot".equals(line.path("kind").asString(""))) {
            LOG.debug("browser: field model {} first saw '{}' on {} ({})", appId,
                    line.path("field").path("label").asString(key), pageOf(page), via);
            return;
        }
        LOG.info("browser: field model {}: '{}' on {} is {} (evidence: {})", appId,
                line.path("field").path("label").asString(key), pageOf(page),
                after.name().toLowerCase(Locale.ROOT), via);
    }

    /**
     * The audit line of one slot change: the field's whole evidence at that moment, so that the last
     * line of a field is the truth about it and the lines before are its history.
     */
    private ObjectNode slotLine(String page, String key, ObjectNode field, Slot before, Slot after,
            String via) {
        ObjectNode line = BrowserSessions.get().mapper().createObjectNode();
        line.put("ts", Instant.now().toString());
        line.put("app", appId);
        line.put("kind", "slot");
        line.put("page", pageOf(page));
        line.put("from", before.name().toLowerCase(Locale.ROOT));
        line.put("slot", after.name().toLowerCase(Locale.ROOT));
        line.put("because", basis(page, field, after));
        line.put("via", via);
        line.set("field", field.deepCopy());
        return line;
    }

    /**
     * The passive half of {@link Slot#COMPUTED}: two reads inside <b>one page view</b> that disagree, with
     * nothing to explain the difference. The write diff says the same thing from acting, this says it
     * from looking - which matters because the actions that recalculate a field are usually taken by the
     * person in front of the screen, and those never run a value pass.
     *
     * <p>Three things keep it honest: a page border (new document <em>or</em> route change, the same line
     * the fill journal uses) is no comparison, a first read has nothing to compare against, and a key that
     * reported an {@code input}/{@code change} event within this view was moved by a hand - the event path
     * already called that a {@code parameter}. What stays invisible by design: a value that changed and
     * changed back between two reads.</p>
     *
     * @param field       the evidence node (already inside the model's lock)
     * @param border      the page view these two reads belong to ({@link BrowserSession#pageBorderId()})
     * @param valueDigest the value now, or {@code null} when the field is a secret one (then we know
     *                    nothing about its content and may not compare what we do not have)
     * @param session     the session, asked whether a hand was in this field
     * @param key         the field key
     */
    private void compareWithinLoad(ObjectNode field, String border, String valueDigest,
            BrowserSession session, String key) {
        if (valueDigest == null || session == null || border == null || border.isEmpty()) {
            return;
        }
        String previous = field.path("lastValue").asString("");
        String previousBorder = field.path("lastView").asString("");
        field.put("lastValue", valueDigest);
        field.put("lastView", border);
        if (previous.isEmpty() || !border.equals(previousBorder) || previous.equals(valueDigest)) {
            return;
        }
        if (session.wasTypedSinceLoad(key)) {
            return;
        }
        bump(field, "recalculated");
        addSource(field, "observed");
        LOG.info("browser: field model {}: '{}' changed itself between two reads of one page view -"
                + " the application computes it", appId, field.path("label").asString(key));
    }

    // ========================================================================
    // Reading
    // ========================================================================

    /**
     * The slot of one field, derived from its evidence and from nothing else.
     *
     * @param page page of the form
     * @param key  field key
     * @return the slot, {@link Slot#UNKNOWN} without evidence
     */
    public Slot slotOf(String page, String key) {
        LOCK.lock();
        try {
            ObjectNode field = fields.get(identity(page, key));
            return field == null ? Slot.UNKNOWN : deriveSlot(pageOf(page), field);
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * The derivation with the payload side in it. Kept apart from {@link #slotOf(ObjectNode)} because
     * the one rule that reasons from <em>absence</em> needs more than the field's own evidence: it
     * needs to know what this application has ever sent and whether the payloads of this page were
     * readable at all.
     *
     * @param page   page of the form (the identity half the payload records hang on)
     * @param field  the evidence of one field
     * @return the slot
     */
    Slot deriveSlot(String page, ObjectNode field) {
        Slot domSide = slotOf(field);
        if (domSide == Slot.UNKNOWN && isProduced(page, field)) {
            return Slot.COMPUTED;
        }
        // A field the page hands out and never sends is not a constant of the form either, whatever
        // the counters say: two records agreeing on a value the application produced says only that
        // the application is reliable. The reverse case is deliberately left alone - `lookup` and
        // `policy` are stated by the control itself, and an inference from absence does not outrank a
        // fact on the page. That keeps the strict-mode guard from blocking a write on the strength of
        // the weakest evidence in this model.
        if (domSide == Slot.CONSTANT && isProduced(page, field)) {
            return Slot.COMPUTED;
        }
        return domSide;
    }

    /**
     * Why a slot was reached - in the honest sentence for the evidence that actually carries it. A
     * {@code computed} claim from the payload side did not see anything change and must not claim it
     * did (docs/browser.md §7a: a model that stays quiet about its gaps gets believed).
     *
     * @param page  page of the form
     * @param field the evidence of one field
     * @param slot  the slot that was derived
     * @return one sentence for the tool answer, the log line and the review entry
     */
    String basis(String page, ObjectNode field, Slot slot) {
        if (slot == Slot.COMPUTED && field.path("recalculated").asInt(0) == 0
                && isProduced(page, field)) {
            return PRODUCED_BASIS;
        }
        return slot.basis();
    }

    /**
     * The payload side of {@link Slot#COMPUTED}. Every condition closes one way this could be wrong,
     * because this is the only claim in the model that rests on something <b>not</b> being seen:
     *
     * <ul>
     *   <li>a name of a control on the page (a {@link #PAYLOAD_PREFIX} row is a name the page does
     *       send, and a control without a {@code name} attribute can never be in a payload at all);</li>
     *   <li>the name is not a secret name - its values are masked by design, so the only thing we
     *       know about it is that we may not look;</li>
     *   <li>the role is one the browser always submits: an unchecked checkbox, an unchosen radio and
     *       a {@code contenteditable} are absent from every payload without anything being produced;</li>
     *   <li>no payload of this application carried the name, ever - not only not on this page, since
     *       a form may post to another endpoint;</li>
     *   <li>the field holds a value the application handed out (an empty input that nobody typed in
     *       proves nothing) and no hand or write moved it;</li>
     *   <li><b>and the page has coverage:</b> at least one payload of this page carried a name of a
     *       field known on this page (so we have really seen its form go out) and no payload of it
     *       was unreadable. A login page whose body the operator excluded has no coverage in this
     *       sense, and correctly stays unknown.</li>
     * </ul>
     *
     * @param page  page of the row
     * @param field the evidence of one field
     * @return true when the payload side says "the application produces this value"
     */
    private boolean isProduced(String page, ObjectNode field) {
        String key = field.path("key").asString("");
        if (key.isEmpty() || key.startsWith(PAYLOAD_PREFIX)) {
            return false;
        }
        String name = nameOf(key);
        if (name.isEmpty() || secrets.isKey(name)) {
            return false;
        }
        if (field.path("sent").asInt(0) > 0
                || sentNames.containsKey(BrowserSecrets.normalize(name))) {
            return false;
        }
        if (field.path("written").asInt(0) > 0 || field.path("userTyped").asInt(0) > 0
                || field.path("recalculated").asInt(0) > 0) {
            return false;
        }
        if (!handsOutAValue(field) || !isAlwaysSubmitted(key, field)) {
            return false;
        }
        return hasPayloadCoverage(page);
    }

    /**
     * @param field the evidence of one field
     * @return true when at least one read found something in the field (an empty value is the default
     *         of every control and says nothing about who fills it)
     */
    private static boolean handsOutAValue(ObjectNode field) {
        String empty = digest("");
        for (JsonNode seen : digests(field)) {
            if (!empty.equals(seen.asString(""))) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param key   field key (§4a: frame, form, name, id, label, role, type)
     * @param field the evidence, which knows the role the value pass saw
     * @return true for the controls a browser puts into a payload even when nobody touches them
     */
    private static boolean isAlwaysSubmitted(String key, ObjectNode field) {
        String role = segment(key, 5);
        if (role.isEmpty()) {
            role = field.path("role").asString("");
        }
        String type = segment(key, 6);
        for (String exception : NOT_ALWAYS_SENT) {
            if (exception.equalsIgnoreCase(role) || exception.equalsIgnoreCase(type)) {
                return false;
            }
        }
        return !role.isEmpty();
    }

    /**
     * @param page page of the row
     * @return true when the payloads of this page were read and one of them carried a name of a field
     *         of this page - the only ground on which "never sent" may be said about it
     */
    private boolean hasPayloadCoverage(String page) {
        ObjectNode record = payloadPages.get(pageOf(page));
        if (record == null) {
            return false;
        }
        return record.path("gaps").asInt(0) == 0 && record.path("joined").asInt(0) > 0;
    }

    /**
     * The name a payload can carry for this field: the third segment of the §4a key. Not the id - a
     * control is submitted under its {@code name}, and an input that only has an id is not submitted
     * at all (which is a fact worth knowing, so it stays without a join instead of borrowing one).
     *
     * @param key field key as the bridge composes it, or a {@link #PAYLOAD_PREFIX} name
     * @return the name, empty when the key holds none
     */
    static String nameOf(String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        if (key.startsWith(PAYLOAD_PREFIX)) {
            return key.substring(PAYLOAD_PREFIX.length());
        }
        return segment(key, 2);
    }

    /**
     * @param key   field key
     * @param index which of the seven {@code |}-separated parts (0 = frame)
     * @return the part, empty when the key does not have that many
     */
    private static String segment(String key, int index) {
        int from = 0;
        for (int seen = 0; seen < index; seen++) {
            int next = key.indexOf('|', from);
            if (next < 0) {
                return "";
            }
            from = next + 1;
        }
        int end = key.indexOf('|', from);
        return (end < 0 ? key.substring(from) : key.substring(from, end)).trim();
    }

    /**
     * @param identity {@code page + " " + key} as the map holds it
     * @return the page half of an identity (the key may contain spaces, so this cuts at the first one)
     */
    private static String identityPage(String identity) {
        int space = identity.indexOf(' ');
        return space < 0 ? identity : identity.substring(0, space);
    }

    /**
     * The derivation, in this order and each step for a reason: an observed recalculation outranks
     * everything, because it is the only fact here. A hand that typed outranks a suggestion list,
     * because the list only says where a value <em>could</em> come from. {@code constant} needs two
     * observations - one observation of one value is not a pattern, it is a coincidence.
     *
     * @param field the evidence of one field
     * @return the slot
     */
    static Slot slotOf(ObjectNode field) {
        if (field.path("recalculated").asInt(0) > 0) {
            return Slot.COMPUTED;
        }
        if (field.path("written").asInt(0) > 0 || field.path("userTyped").asInt(0) > 0) {
            return Slot.PARAMETER;
        }
        if (!hints(field).path("datalist").isMissingNode()) {
            return Slot.LOOKUP;
        }
        if (isConstantCandidate(field)) {
            return Slot.CONSTANT;
        }
        if (hasRule(field)) {
            return Slot.POLICY;
        }
        return Slot.UNKNOWN;
    }

    /**
     * What a {@code constant} claim has to be worth. Every one of these conditions was added after a
     * live run called a field constant that was not:
     *
     * <ul>
     *   <li><b>two separate page loads</b>, not two passes over one load - a snapshot and a value pass
     *       of the same document say the same thing twice, which is one fact;</li>
     *   <li><b>two different records</b> ({@code origins}: the URL with its query). Opening report
     *       DID-0012 twice proves nothing about the form - it proves that DID-0012 has that title.
     *       This condition is what a live run of four sessions got wrong: {@code title},
     *       {@code summary}, {@code application} and {@code state} all stood as {@code constant}
     *       because the same report was looked at twice; the values were the data of one record.</li>
     *   <li><b>one value</b> across those records (the digests, never the text);</li>
     *   <li><b>not the empty string</b> - an untouched input is always empty and that says nothing
     *       about what belongs in it;</li>
     *   <li><b>not a field the application hands out</b> ({@code readonly}/{@code disabled}): its value
     *       is derived, so it belongs to the computed family, and calling it constant would freeze a
     *       report id for the whole application. The payload side of the same thought - a field no
     *       payload ever carried - is decided in {@link #deriveSlot(String, ObjectNode)}, because it needs
     *       the application's payload knowledge and not just this row's.</li>
     * </ul>
     *
     * <p>Older evidence simply has no {@code origins} list, so a claim made before this rule stops
     * being made the next time the field is read - nothing has to be deleted for the model to become
     * honest again.</p>
     *
     * @param field the evidence of one field
     * @return true when the evidence supports "always the same on this application"
     */
    static boolean isConstantCandidate(ObjectNode field) {
        if (field.path("written").asInt(0) > 0 || field.path("recalculated").asInt(0) > 0
                || field.path("userTyped").asInt(0) > 0) {
            return false;
        }
        JsonNode hints = hints(field);
        if (!hints.path("readonly").isMissingNode() || !hints.path("disabled").isMissingNode()) {
            return false;
        }
        if (loads(field).size() < 2 || origins(field).size() < 2) {
            return false;
        }
        ArrayNode values = digests(field);
        if (values.size() != 1) {
            return false;
        }
        // The empty field: always empty until somebody types - which is a promise about nothing.
        return !values.get(0).asString("").equals(digest(""));
    }

    /**
     * @param field the evidence of one field
     * @return true when the control itself states a rule (mandatory, pattern, length, range)
     */
    static boolean hasRule(ObjectNode field) {
        JsonNode hints = hints(field);
        for (String rule : List.of("required", "pattern", "maxlength", "min", "max")) {
            if (!hints.path(rule).isMissingNode()) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param page page of the form
     * @param key  field key
     * @return a copy of the field's evidence, or {@code null} when nothing was observed
     */
    public ObjectNode evidence(String page, String key) {
        LOCK.lock();
        try {
            ObjectNode field = fields.get(identity(page, key));
            return field == null ? null : (ObjectNode) field.deepCopy();
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * @param page  only rows of this page ({@code null} or empty for every page of the application)
     * @param text  optional substring of key or label
     * @param limit maximum number of rows
     * @return the rows, most recently seen first; each carries {@code slot}, {@code because} and the
     *         counters behind it
     */
    public List<ObjectNode> rows(String page, String text, int limit) {
        String needle = text == null ? "" : text.toLowerCase(Locale.ROOT);
        String wantedPage = page == null || page.isBlank() ? null : pageOf(page);
        List<ObjectNode> all = allRows();
        Map<String, List<ObjectNode>> byKey = groupByKey(all);
        for (ObjectNode built : all) {
            putSiblings(built, byKey);
        }
        List<ObjectNode> found = new ArrayList<>();
        for (ObjectNode built : all) {
            if (wantedPage != null && !wantedPage.equals(built.path("page").asString(""))) {
                continue;
            }
            String key = built.path("key").asString("");
            String label = built.path("evidence").path("label").asString("");
            if (!needle.isEmpty() && !key.toLowerCase(Locale.ROOT).contains(needle)
                    && !label.toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            found.add(built);
        }
        found.sort((a, b) -> b.path("evidence").path("lastSeen").asString("")
                .compareTo(a.path("evidence").path("lastSeen").asString("")));
        int max = Math.max(1, limit);
        return found.size() > max ? new ArrayList<>(found.subList(0, max)) : found;
    }

    /**
     * Adds {@code alsoKnownOn} to rows built outside {@link #rows} - {@code classify} asks about the
     * page it is looking at, and that answer should still say when the same field is known elsewhere
     * under a different URL (the model has to be able to tell "never seen" from "seen, just not here").
     *
     * @param rows the rows to annotate, in any order
     */
    public void annotateSiblings(List<ObjectNode> rows) {
        Map<String, List<ObjectNode>> byKey = groupByKey(allRows());
        for (ObjectNode built : rows) {
            putSiblings(built, byKey);
        }
    }

    /** Every field of this application as a row, newest identity first (the map keeps that order). */
    private List<ObjectNode> allRows() {
        List<ObjectNode> all = new ArrayList<>();
        LOCK.lock();
        try {
            for (Map.Entry<String, ObjectNode> entry : fields.entrySet()) {
                String identity = entry.getKey();
                int space = identity.indexOf(' ');
                all.add(row(identity.substring(0, space), identity.substring(space + 1),
                        entry.getValue()));
            }
        } finally {
            LOCK.unlock();
        }
        return all;
    }

    private static Map<String, List<ObjectNode>> groupByKey(List<ObjectNode> rows) {
        Map<String, List<ObjectNode>> byKey = new LinkedHashMap<>();
        for (ObjectNode built : rows) {
            byKey.computeIfAbsent(built.path("key").asString(""), k -> new ArrayList<>()).add(built);
        }
        return byKey;
    }

    /**
     * The same field key on the other pages of this application - page, slot and count only. The
     * decision stays per page (a refusal the user cannot reproduce on the screen in front of them
     * trains the reflex to answer {@code force:true}, and that is how a guard dies), but nobody reading
     * an answer should have to guess whether a field simply is not known or only not known here.
     */
    private static void putSiblings(ObjectNode built, Map<String, List<ObjectNode>> byKey) {
        List<ObjectNode> sameKey = byKey.get(built.path("key").asString(""));
        if (sameKey == null || sameKey.size() < 2) {
            return;
        }
        String ownPage = built.path("page").asString("");
        ArrayNode elsewhere = built.putArray("alsoKnownOn");
        for (ObjectNode other : sameKey) {
            if (ownPage.equals(other.path("page").asString(""))) {
                continue;
            }
            ObjectNode brief = elsewhere.addObject();
            brief.put("page", other.path("page").asString(""));
            brief.put("slot", other.path("slot").asString(""));
            brief.put("n", other.path("n").asInt(0));
        }
        if (elsewhere.size() == 0) {
            built.remove("alsoKnownOn");
        }
    }

    /**
     * @param page  page of the form
     * @param key   field key
     * @param field the evidence (may be {@code null})
     * @return one row as the tool shows it
     */
    ObjectNode row(String page, String key, ObjectNode field) {
        ObjectNode out = BrowserSessions.get().mapper().createObjectNode();
        out.put("page", pageOf(page));
        out.put("key", key);
        if (field == null) {
            out.put("slot", Slot.UNKNOWN.name().toLowerCase(Locale.ROOT));
            out.put("because", Slot.UNKNOWN.basis());
            out.put("n", 0);
            return out;
        }
        Slot slot = deriveSlot(page, field);
        out.put("slot", slot.name().toLowerCase(Locale.ROOT));
        out.put("because", basis(page, field, slot));
        out.put("n", total(field));
        if (key.startsWith(PAYLOAD_PREFIX)) {
            // A name the page sends and no control of it shows: without this line a reader asks for a
            // ref to write into, does not find one, and concludes the tool is broken.
            out.put("fromPayload", true);
            out.put("inSnapshot", false);
        }
        int sent = field.path("sent").asInt(0);
        if (sent > 0) {
            out.put("sent", sent);
        }
        out.set("evidence", field.deepCopy());
        return out;
    }

    /**
     * The names this page sends that no control of it explains (M2b): the hidden inputs of its form.
     * Read-only, for {@code state}, {@code classify} and the review entry.
     *
     * @param page page of the form
     * @return the names, most recently seen first
     */
    public List<String> payloadOnly(String page) {
        List<String> names = new ArrayList<>();
        for (ObjectNode row : payloadRows(page)) {
            names.add(nameOf(row.path("key").asString("")));
        }
        return names;
    }

    /**
     * @param page page of the form ({@code null} for every page of the application)
     * @return the rows of {@link #PAYLOAD_PREFIX} - names that only the payload side knows. A name that
     *         is a field of this page as well is left out even when the payload arrived first and a
     *         {@code sent:} row was made from it then: listing it twice, once as a hidden name and once
     *         as the field it joined to, would be a small lie about what the page can show.
     */
    public List<ObjectNode> payloadRows(String page) {
        List<ObjectNode> all = rows(page, null, KEPT_FIELDS);
        Set<String> knownHere = new LinkedHashSet<>();
        for (ObjectNode row : all) {
            String key = row.path("key").asString("");
            if (key.startsWith(PAYLOAD_PREFIX)) {
                continue;
            }
            String name = nameOf(key);
            if (!name.isEmpty()) {
                knownHere.add(BrowserSecrets.normalize(name));
            }
        }
        List<ObjectNode> found = new ArrayList<>();
        for (ObjectNode row : all) {
            String key = row.path("key").asString("");
            if (!key.startsWith(PAYLOAD_PREFIX)
                    || knownHere.contains(BrowserSecrets.normalize(nameOf(key)))) {
                continue;
            }
            found.add(row);
        }
        return found;
    }

    /**
     * @param page page of the form
     * @return how many payloads of this page were readable, joined to a field of the page, and how
     *         many could not be read - the ground every absence claim stands on (may be empty)
     */
    public ObjectNode payloadEvidence(String page) {
        LOCK.lock();
        try {
            ObjectNode record = payloadPages.get(pageOf(page));
            return record == null ? null : (ObjectNode) record.deepCopy();
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * Adds what the model knows about a field to an element of the snapshot (the annotation of
     * {@code state} with {@code annotate:true}, and of the strict mode).
     *
     * @param element the compacted element - it has to carry {@code key} for this to say anything
     * @param page    the page the snapshot came from
     */
    public void annotate(ObjectNode element, String page) {
        String key = element.path("key").asString("");
        if (key.isEmpty()) {
            return;
        }
        ObjectNode field = evidence(page, key);
        if (field == null) {
            element.put("slot", Slot.UNKNOWN.name().toLowerCase(Locale.ROOT));
            element.put("n", 0);
            return;
        }
        Slot slot = deriveSlot(page, field);
        element.put("slot", slot.name().toLowerCase(Locale.ROOT));
        element.put("n", total(field));
        int sent = field.path("sent").asInt(0);
        if (sent > 0) {
            // "and the application really receives this one" - the difference between a field that is
            // on the page and a field that is on the page *and* in the request.
            element.put("sent", sent);
        }
        if (slot != Slot.UNKNOWN) {
            // "no evidence in this application so far" on a hundred elements is noise, not an answer.
            element.put("because", basis(page, field, slot));
        }
    }

    /**
     * The guard of the strict mode: writing into a field the application recalculates stays possible -
     * the decision is the caller's - but it has to be asked for by name, because in every honest
     * session of this application the value came back different.
     *
     * <p>The two ways to {@code computed} are refused with their own sentence. Telling a caller that
     * a value was "seen changing on its own" when what was seen is that it is never sent would be a
     * lie about the evidence, and a refusal nobody can reproduce on their own screen is how
     * {@code force:true} becomes a reflex.</p>
     *
     * @param page  page of the form
     * @param key   field key of the element to write
     * @param force true when the caller put {@code force:true} on this entry
     * @return the reason to hold the write back, or {@code null} when it goes through
     */
    public String writeGuard(String page, String key, boolean force) {
        if (key == null || key.isEmpty() || force) {
            return null;
        }
        LOCK.lock();
        ObjectNode field;
        Slot slot;
        boolean observed;
        try {
            field = fields.get(identity(page, key));
            if (field == null) {
                return null;
            }
            slot = deriveSlot(page, field);
            observed = field.path("recalculated").asInt(0) > 0;
        } finally {
            LOCK.unlock();
        }
        if (slot != Slot.COMPUTED) {
            return null;
        }
        return (observed ? GUARD_OBSERVED : GUARD_PRODUCED) + GUARD_WAY_OUT;
    }

    /**
     * @param page page to look at ({@code null} for the whole application)
     * @return the labels of the fields known to be computed - the reviewer's line on a submit. The two
     *         ways to that slot are told apart here too: a value that was seen changing is a different
     *         thing to approve than a value the page never sends.
     */
    public List<String> computedFields(String page) {
        List<String> computed = new ArrayList<>();
        for (ObjectNode row : rows(page, null, KEPT_FIELDS)) {
            if (!row.path("slot").asString("").equals("computed")) {
                continue;
            }
            String label = row.path("evidence").path("label").asString(row.path("key").asString(""));
            if (PRODUCED_BASIS.equals(row.path("because").asString(""))) {
                label += " (no payload of this page ever carried it)";
            }
            computed.add(label);
        }
        return computed;
    }

    /**
     * @return number of fields with evidence in this application
     */
    public int size() {
        LOCK.lock();
        try {
            return fields.size();
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * @return where this model lies, or the honest reason it lies nowhere (L10)
     */
    public String location() {
        BrowserSessions backend = BrowserSessions.get();
        if (!backend.settings().persist()) {
            return "in memory only (browser.persist=false)";
        }
        if (file == null) {
            String why = backend.loggingProblem();
            return "in memory only (" + (why == null ? "no run directory anchored" : why) + ")";
        }
        return file.toString();
    }

    /**
     * @return every field's evidence as an array - for a test, or for a caller wanting the raw model
     */
    public ArrayNode snapshot() {
        ArrayNode array = BrowserSessions.get().mapper().createArrayNode();
        LOCK.lock();
        try {
            fields.values().forEach(field -> array.add(field.deepCopy()));
        } finally {
            LOCK.unlock();
        }
        return array;
    }

    // ========================================================================
    // Detail work
    // ========================================================================

    private static int total(ObjectNode field) {
        return field.path("observations").asInt(0) + field.path("written").asInt(0)
                + field.path("recalculated").asInt(0) + field.path("userTyped").asInt(0)
                + field.path("sent").asInt(0);
    }

    /**
     * The payload record of one page, created empty on first use: how many payloads went there, how
     * many of them carried a name of a field of that page, how many could not be read.
     */
    private ObjectNode payloadRecord(String page) {
        return payloadPages.computeIfAbsent(pageOf(page),
                p -> BrowserSessions.get().mapper().createObjectNode());
    }

    /**
     * One payload as it enters the file (M2b): the presence facts of the payload side. Append-only
     * like the field lines and read the same way - the whole set of these lines is what a restart may
     * say about what this application sends.
     */
    private ObjectNode payloadLine(String page, BrowserPayload.Reading reading, boolean joined) {
        ObjectNode line = BrowserSessions.get().mapper().createObjectNode();
        line.put("ts", Instant.now().toString());
        line.put("app", appId);
        line.put("kind", "payload");
        line.put("page", pageOf(page));
        ObjectNode record = line.putObject("payload");
        if (!reading.gap().isEmpty()) {
            record.put("gap", reading.gap());
        } else {
            record.put("joined", joined);
            ArrayNode names = record.putArray("names");
            Set<String> added = new LinkedHashSet<>();
            for (BrowserPayload.Field field : reading.fields()) {
                if (added.add(BrowserSecrets.normalize(field.name()))) {
                    names.add(field.name());
                }
            }
        }
        return line;
    }

    /**
     * A value the application <em>received</em>, kept apart from the values the page <em>showed</em>:
     * a select shows its option text and sends its option value, and mixing the two into one list
     * would make every {@code constant} claim about a select impossible. So this goes into
     * {@code sentValues} - digests like everything else (L12), and never a digest of a mask: hashing
     * {@code ‹redacted›(12)} would be a length oracle for a password, and hashing the value of a field
     * named like a credential is the same mistake with better manners.
     *
     * @param field  the row being joined to a payload
     * @param name   the field name (a secret name gets no digest at all)
     * @param value  the value text as it stands in the <b>redacted</b> capture, may be empty
     */
    private void noteSentValue(ObjectNode field, String name, String value) {
        if (value == null || value.isEmpty() || value.contains(BrowserSecrets.MASK)
                || secrets.isKey(name)) {
            return;
        }
        ArrayNode list = field.path("sentValues").isArray() ? (ArrayNode) field.path("sentValues")
                : field.putArray("sentValues");
        String hash = digest(value);
        for (JsonNode seen : list) {
            if (hash.equals(seen.asString(""))) {
                return;
            }
        }
        if (list.size() < 8) {
            list.add(hash);
        }
    }

    /** @return an array of a node, created when the node does not have one yet */
    private static ArrayNode array(ObjectNode node, String name) {
        JsonNode existing = node.path(name);
        return existing.isArray() ? (ArrayNode) existing : node.putArray(name);
    }

    /** Adds a text to a list once, with a ceiling: a page with four gap reasons has made its point. */
    private static void addToList(ArrayNode list, String text, int max) {
        for (JsonNode seen : list) {
            if (text.equals(seen.asString(""))) {
                return;
            }
        }
        if (list.size() < max) {
            list.add(text);
        }
    }

    private static void bump(ObjectNode field, String name) {
        field.put(name, field.path(name).asInt(0) + 1);
    }

    /** The field of this identity, least recently seen first so that the cap drops the stale ones. */
    private ObjectNode field(String identity, String key) {
        ObjectNode field = fields.get(identity);
        if (field != null) {
            fields.remove(identity);              // seen again: to the back of the recency order
        } else {
            while (fields.size() >= KEPT_FIELDS) {
                fields.remove(fields.keySet().iterator().next());
            }
            field = BrowserSessions.get().mapper().createObjectNode();
            field.put("key", key);
        }
        fields.put(identity, field);
        return field;
    }

    private static ObjectNode hints(ObjectNode field) {
        JsonNode existing = field.path("hints");
        if (existing.isObject()) {
            return (ObjectNode) existing;
        }
        return field.putObject("hints");
    }

    private static ArrayNode digests(ObjectNode field) {
        JsonNode existing = field.path("values");
        if (existing.isArray()) {
            return (ArrayNode) existing;
        }
        return field.putArray("values");
    }

    /** The page loads this field was observed on - the difference between "seen twice" and "known". */
    private static ArrayNode loads(ObjectNode field) {
        JsonNode existing = field.path("loads");
        if (existing.isArray()) {
            return (ArrayNode) existing;
        }
        return field.putArray("loads");
    }

    /** The records (URLs with their query) this field was observed on - see {@link #isConstantCandidate}. */
    private static ArrayNode origins(ObjectNode field) {
        JsonNode existing = field.path("origins");
        if (existing.isArray()) {
            return (ArrayNode) existing;
        }
        return field.putArray("origins");
    }

    private static void noteOrigin(ObjectNode field, String origin) {
        if (origin == null || origin.isBlank()) {
            return;
        }
        ArrayNode list = origins(field);
        for (JsonNode seen : list) {
            if (origin.equals(seen.asString(""))) {
                return;
            }
        }
        if (list.size() < 8) {
            list.add(origin);
        }
    }

    private static void noteLoad(ObjectNode field, String document) {
        if (document == null || document.isEmpty()) {
            return;
        }
        ArrayNode list = loads(field);
        for (JsonNode seen : list) {
            if (document.equals(seen.asString(""))) {
                return;
            }
        }
        if (list.size() < 8) {
            list.add(document);
        }
    }

    private static void addDigest(ObjectNode field, String digest) {
        ArrayNode list = digests(field);
        for (JsonNode seen : list) {
            if (digest.equals(seen.asString(""))) {
                return;
            }
        }
        if (list.size() < 8) {
            list.add(digest);
        }
        // Past eight different values "varies" is the whole truth, and a longer list would only grow.
    }

    private static void addSource(ObjectNode field, String via) {
        ArrayNode sources = field.path("sources").isArray() ? (ArrayNode) field.path("sources")
                : field.putArray("sources");
        for (JsonNode seen : sources) {
            if (via.equals(seen.asString(""))) {
                return;
            }
        }
        sources.add(via);
    }

    // ========================================================================
    // Filing away and reading back
    // ========================================================================

    private void append(ObjectNode line) {
        Path target = file;
        if (target == null || !BrowserSessions.get().settings().persist()) {
            return;
        }
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, line.toString() + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.warn("browser: field model {} could not be written: {}", target, e.getMessage());
        }
    }

    /**
     * Rebuilds the model from its file. Each slot line carries the field's evidence at that moment, so
     * the <b>last</b> line of a field is the truth about it and the lines before are its history - no
     * merging, no replay of the passes themselves.
     *
     * <p>The {@code payload} lines are the exception and have to be <em>accumulated</em>: each of them
     * is one exchange that carried names, so none of them can be dropped without losing a presence
     * fact, and a lost presence fact is an invented {@code computed} field. They are read into the page
     * records and the name set, and then applied to the fields a second time so that the {@code sent}
     * counters are the same after a restart as before it (docs/browser.md §7a, the payload side).</p>
     */
    private void load() {
        if (file == null || !Files.isReadable(file)) {
            return;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("browser: field model {} unreadable, starting empty: {}", file, e.getMessage());
            return;
        }
        int restored = 0;
        int payloads = 0;
        List<SentEvent> events = new ArrayList<>();
        LOCK.lock();
        try {
            for (String line : lines) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonNode node = BrowserSessions.get().mapper().readTree(line);
                    String kind = node.path("kind").asString("");
                    if ("payload".equals(kind)) {
                        events.add(restorePayload(node));
                        payloads++;
                        continue;
                    }
                    // Both shapes carry the field's whole evidence at that moment, so the last line of a
                    // field is the truth - "seen" (first sight) and "slot" (a slot was reached) differ
                    // only in what is worth telling the log at runtime.
                    if (!"slot".equals(kind) && !"seen".equals(kind)) {
                        continue;           // an audit line of another shape: nothing to restore from it
                    }
                    JsonNode field = node.path("field");
                    String key = field.path("key").asString("");
                    if (key.isEmpty()) {
                        continue;
                    }
                    String identity = identity(node.path("page").asString("/"), key);
                    fields.remove(identity);
                    fields.put(identity, (ObjectNode) field.deepCopy());
                    restored++;
                } catch (RuntimeException e) {
                    LOG.debug("browser: field model line skipped in {}: {}", file, e.getMessage());
                }
            }
            int joined = 0;
            for (SentEvent event : events) {
                joined += applySent(event);
            }
            if (joined > 0) {
                LOG.debug("browser: field model {}: {} payload line(s) rejoined to {} field(s)", appId,
                        payloads, joined);
            }
        } finally {
            LOCK.unlock();
        }
        if (restored > 0 || payloads > 0) {
            LOG.info("browser: field model of {} restored from {} ({} field line(s), {} payload line(s),"
                    + " {} name(s) this application has sent)", appId, file, restored, payloads,
                    sentNames.size());
        }
    }

    /** One {@code payload} line, kept for the second pass that joins its names to the fields. */
    private record SentEvent(String page, List<String> names) {
    }

    /**
     * Reads one {@code payload} line into the page record and the name set.
     *
     * @param node the line
     * @return the event, for the second pass that joins names to fields
     */
    private SentEvent restorePayload(JsonNode node) {
        String page = pageOf(node.path("page").asString("/"));
        JsonNode record = node.path("payload");
        ObjectNode target = payloadRecord(page);
        String gap = record.path("gap").asString("");
        List<String> names = new ArrayList<>();
        if (!gap.isEmpty()) {
            target.put("gaps", target.path("gaps").asInt(0) + 1);
            addToList(array(target, "gapReasons"), gap, 4);
            return new SentEvent(page, names);
        }
        target.put("payloads", target.path("payloads").asInt(0) + 1);
        if (record.path("joined").asBoolean(false)) {
            target.put("joined", target.path("joined").asInt(0) + 1);
        }
        for (JsonNode name : record.path("names")) {
            String clean = name.asString("");
            if (clean.isEmpty()) {
                continue;
            }
            names.add(clean);
            sentNames.merge(BrowserSecrets.normalize(clean), 1, Integer::sum);
        }
        return new SentEvent(page, names);
    }

    /**
     * Second pass over the restored fields: the {@code sent} counters a restart would otherwise lose.
     * Values are not replayed (they are digests of text the file does not hold), which is why
     * {@code sentValues} can come back shorter than it was - a counter of how often a name went out is
     * what the derivation reads, not the list of variants.
     *
     * @param event one payload of this application
     * @return how many rows it joined to
     */
    private int applySent(SentEvent event) {
        if (event.names().isEmpty()) {
            return 0;
        }
        Set<String> names = new LinkedHashSet<>();
        for (String name : event.names()) {
            names.add(BrowserSecrets.normalize(name));
        }
        int touched = 0;
        for (ObjectNode field : fields.values()) {
            String key = field.path("key").asString("");
            String name = nameOf(key);
            if (key.isEmpty() || key.startsWith(PAYLOAD_PREFIX) || name.isEmpty()
                    || !names.contains(BrowserSecrets.normalize(name))) {
                continue;
            }
            bump(field, "sent");
            touched++;
        }
        return touched;
    }
}
