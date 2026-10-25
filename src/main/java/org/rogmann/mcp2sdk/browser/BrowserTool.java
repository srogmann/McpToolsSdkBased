package org.rogmann.mcp2sdk.browser;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import org.rogmann.mcp2sdk.ToolSpecWithState;
import org.rogmann.mcp2sdk.ToolState;
import org.rogmann.mcp2sdk.review.ReviewAttribute;
import org.rogmann.mcp2sdk.review.ReviewAttributes;
import org.rogmann.mcp2sdk.review.ReviewEntry;
import org.rogmann.mcp2sdk.review.ReviewResponseEntry;
import org.rogmann.mcp2sdk.review.ReviewStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The LLM's view of a browser session: <b>read, understand, log - and, since M1, write</b>.
 *
 * <p>One tool with an {@code action} discriminator instead of twelve tools, because the answers share
 * the same context (one session, one revision, one capture) and because a model looking at an unknown
 * application asks the same question first anyway: "what is here?".</p>
 *
 * <p><b>The write path follows the four-eyes rule of docs/browser.md §7 (decision L8):</b> filling
 * fields ({@code fill}) is free - reversible, invisible to the application, undoable ({@code undo}).
 * Submitting is the gate: {@code submit} (and {@code click} on a {@code type=submit} button) takes the
 * diff from the session's fill journal, creates a four-eyes request via
 * {@link org.rogmann.mcp2sdk.browser.BrowserReviewService} and sends the click only after a human
 * approved. Applications listed in {@code browser.apps.withoutReviews} (development bypass, L9)
 * submit without the entry - visibly, in the log and at startup. Secret fields are writable but never
 * readable: their values are redacted in snapshot, readback, diff and this tool's own log.</p>
 *
 * <p><b>M2 adds knowledge, not permission</b> (docs/browser.md §7a): with
 * {@code browser.fields.mode=observe|strict} every write operation reports which fields the application
 * recalculated on its own, and {@code classify}/{@code fields} say where a value comes from
 * ({@link BrowserFieldModel}). The mode is off by default - a session then runs exactly as M1 built it,
 * spontaneous work included. Only {@code strict} holds a write back, and only for a field that was
 * <em>seen</em> changing by itself, and only until the caller asks for it by name
 * ({@code force:true}).</p>
 */
public final class BrowserTool {

    private static final Logger LOG = LoggerFactory.getLogger(BrowserTool.class);

    /** Tool name. */
    public static final String NAME = "browser_proxy_tool";

    private static final String DESCRIPTION = """
            Operates a session of an internal web application running through the browser proxy - \
            the user sees the very document you are reading. Writing (M1): fill, undo, click; \
            submits go through a four-eyes review unless the app is listed in \
            browser.apps.withoutReviews (development bypass). Knowing the page (M2, needs \
            browser.fields.mode=observe|strict): where a field's value comes from.

            Flow: the user opens the index page of the proxy (its address is printed in the start \
            log and shown by action=apps - the service port comes from the environment, so it is \
            not always 9091) and signs in to the application. After that "sessions" lists the open \
            sessions (with sessionId) and "state" gives the page content as the browser has \
            rendered it. Applications may be offered under a host name of their own \
            (browser.host.suffix=.localhost) - then the address action=open names is the one to \
            use, because cookies belong to a host and not to a port.

            Read actions:
              apps      - registered applications (and the registry's complaints about them)
              open      - open a new session; returns the URL the user should click
              sessions  - status of every session: bridge connected? revision, page, frames, counters
              state     - last snapshot (frames, roles, labels, values). ref addresses like f0/e12
              refresh   - request a new snapshot and wait for the page to settle (slower, current)
              find      - search the snapshot (label, value, column heading, role text)
              locator   - the locator bundle + fingerprint of a ref (diagnostics, the basis of M1)
              events    - what the user and the page have done since a sequence number
              requests  - capture of the HTTP traffic: one line per exchange, bodies clipped
              request   - one capture in full (method, paths, redacted bodies, notes)
              log       - this session's recent tool calls (self-report)

            Knowledge actions (M2, only with browser.fields.mode=observe|strict):
              classify  - read the writable fields of the current page (value pass) and report per \
                field where its value comes from: constant | parameter | lookup | computed | policy, \
                with the counters behind the claim. Records evidence, changes nothing on the page. \
                Two classify calls (or one state refresh) around a change are enough for the model to \
                notice that a value moved on its own - no write by the tool is needed for that. \
                `alsoSent` names what the page sends without showing a control for it (hidden inputs); \
                both sides feed on the proxied payloads, so submitting a form once is what teaches the \
                model which fields the application only ever hands out.
              fields    - the accumulated field model of the application (per page): slot, why, how \
                often it was seen, and for a page what its payloads showed (readable, joined, gaps). \
                Reads no browser at all - it answers "what did earlier sessions teach us about this \
                form?". Rows with `fromPayload:true` are names no snapshot explains.
              state     - with annotate=true (default in strict mode) every element additionally \
                carries its field `key`, its `slot`, and `sent` when a payload carried that name; the \
                answer lists the page's `alsoSent` names.

            Write actions (need browser.write.enabled=true; the bridge rejects them otherwise):
              fill      - set values: `values:[{ref,value,why}]`, one entry per form control. \
                Recipes per control type, fingerprint check against the snapshot, undo journal, \
                readback with validation messages. The diff lands in the session journal. In M2 \
                modes the answer also carries `diff`: fields the application recalculated on its own \
                while the page rested. In strict mode an entry whose field is known to be computed \
                is held back unless the entry says `force:true`.
              undo      - restore what fill changed on this page since it was loaded. Fields the \
                application recalculated as a consequence of the write stay as the application left \
                them - undo undoes the keystrokes, not the page's reaction to them.
              click     - click a button or link. A button with type=submit is routed through the \
                review gate like submit; a plain <button> (implicit submit) is NOT recognized - \
                prefer submit for saving.
              submit    - the gated save: a review entry (tool id browser.submit) with the field \
                diff, the validation messages and one line "what I could not check"; the click \
                runs only after a human APPROVED it (blocks this call, window `waitSeconds`, \
                default 300 s).

            Important:
              * ref addresses only hold for the revision (rev) delivered with them. Pull "state" \
                again after a navigation or a reload - the application has otherwise rebuilt its DOM.
              * fill is free (reversible, invisible to the app), submit is the gate. Do not work \
                around it: when the user should press the button themselves, say so instead of \
                calling submit - their click counts as implicit consent and is logged as such.
              * ‹redacted› is deliberate: passwords, session cookies and fields named in \
                sensitiveKeys never stand in the capture. They are writable but never readable - \
                the value you wrote is not echoed back to you.
              * <input type=file> cannot be set from JS - tell the user instead.
              * quiescent=false means: the page was not finished yet (requests in flight, mutations). \
                Values may still change.
              * Tables: before you read 200 rows of HTML, look in "requests" - the application has \
                almost always loaded the data as JSON already, which is cheaper and lossless.
              * M2 knowledge is descriptive: a `computed` slot says the application recalculates \
                that field, it says nothing about whether you may write it. Allowed vs. not allowed \
                stays the review's decision (and yours, with force:true).
              * A form can hold fields the snapshot never shows and the value pass never reads: \
                `type=hidden` inputs. They appear as `alsoSent` names (and as `sent` on the field they \
                belong to), because what the application receives is decided by the payload, not by \
                the page. There is no ref for them - do not look for one.
              * `computed` has two proofs and the answer says which one it is: seen changing inside \
                one page view, or never carried by any payload of a page whose payloads were all \
                readable. The second one is an inference from absence and stops the moment a body \
                could not be read (`sensitivePaths`) - that is a gap, not an empty payload.
            """;

    private final ToolState state;

    private BrowserTool() {
        state = new ToolState();
    }

    /**
     * @return the tool and its state
     */
    public static ToolSpecWithState createToolInstance() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", Map.of("type", "string", "enum", List.of(
                "apps", "open", "sessions", "state", "refresh", "find", "locator", "events",
                "requests", "request", "log", "fill", "undo", "click", "submit", "classify",
                "fields"),
                "description", "What is being asked"));
        properties.put("sessionId", Map.of("type", "string",
                "description", "session id, taken from 'sessions'"));
        properties.put("appId", Map.of("type", "string",
                "description", "the application id taken from 'apps' - for action=open (which "
                        + "application to open) and for action=fields (whose model to read)"));
        properties.put("frame", Map.of("type", "string",
                "description", "optional: look at (or change) only this frame, e.g. 'f0' or 'f0/f1'"));
        properties.put("ref", Map.of("type", "string",
                "description", "element address like 'f0/e12' (for locator)"));
        properties.put("query", Map.of("type", "string",
                "description", "search text for action=find"));
        properties.put("since", Map.of("type", "integer",
                "description", "only for events: sequence number to report from"));
        properties.put("seq", Map.of("type", "integer",
                "description", "only for request: sequence number taken from 'requests'"));
        properties.put("limit", Map.of("type", "integer",
                "description", "number of hits, default 40 (requests) / 60 (find)"));
        properties.put("full", Map.of("type", "boolean",
                "description", "state: also deliver the values of headings/context nodes"));
        properties.put("annotate", Map.of("type", "boolean",
                "description", "state: add the M2 field key and slot to every element (default: on "
                        + "when browser.fields.mode=strict)"));
        properties.put("force", Map.of("type", "boolean",
                "description", "fill: write this field even though the model knows it as computed "
                        + "(per entry inside values[], or for all entries at call level)"));
        properties.put("values", Map.of("type", "array",
                "description", "only for action=fill: [{ref,value,why,force?}] - one entry per form "
                        + "control, refs from action=state"));
        properties.put("reason", Map.of("type", "string",
                "description", "only for submit/click: why this action is wanted "
                        + "(shown to the reviewer)"));
        properties.put("waitSeconds", Map.of("type", "integer",
                "description", "only for submit: approval window in seconds (default 300)"));

        JsonSchema schema = new JsonSchema("object", properties, List.of("action"), null, null, null);
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(NAME)
                .title("Browser session (internal proxy)")
                .description(DESCRIPTION)
                .inputSchema(schema)
                .build();

        BrowserTool implementation = new BrowserTool();
        return new ToolSpecWithState(McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(implementation::call)
                .build(), implementation.state);
    }

    /**
     * The tool body.
     *
     * @param exchange the server side (unused)
     * @param request  the call
     * @return the result text
     */
    CallToolResult call(McpSyncServerExchange exchange, CallToolRequest request) {
        state.callCount().incrementAndGet();
        Map<String, Object> raw = request.arguments() == null ? Map.of() : request.arguments();
        String action = string(raw, "action");
        BrowserSessions backend = BrowserSessions.get();
        JsonNode arguments = asArguments(backend, raw);
        try {
            String answer = switch (action) {
                case "apps" -> describeApps(backend);
                case "open" -> openSession(backend, string(raw, "appId"));
                case "sessions" -> listSessions(backend);
                case "state", "refresh" -> showState(backend, string(raw, "sessionId"),
                        string(raw, "frame"), bool(raw, "full"), action.equals("refresh"),
                        raw.get("annotate"));
                case "find" -> find(backend, string(raw, "sessionId"), string(raw, "query"),
                        integer(raw, "limit", 60));
                case "locator" -> locator(backend, string(raw, "sessionId"), string(raw, "ref"));
                case "events" -> events(backend, string(raw, "sessionId"),
                        integer(raw, "since", 0), integer(raw, "limit", 40));
                case "requests" -> requests(backend, string(raw, "sessionId"),
                        integer(raw, "limit", 40));
                case "request" -> oneRequest(backend, string(raw, "sessionId"),
                        integer(raw, "seq", -1));
                case "log" -> actionLog(backend, string(raw, "sessionId"), integer(raw, "limit", 20));
                case "classify" -> classify(backend, string(raw, "sessionId"), arguments);
                case "fields" -> fields(backend, string(raw, "sessionId"), string(raw, "appId"),
                        string(raw, "query"), integer(raw, "limit", 40));
                case "fill" -> fill(backend, string(raw, "sessionId"), raw.get("values"),
                        bool(raw, "force"), arguments);
                case "undo" -> undo(backend, string(raw, "sessionId"), arguments);
                case "click" -> click(backend, string(raw, "sessionId"), string(raw, "ref"),
                        string(raw, "reason"), arguments);
                case "submit" -> submit(backend, string(raw, "sessionId"), string(raw, "ref"),
                        string(raw, "reason"), integer(raw, "waitSeconds", 0), arguments);
                default -> error("unknown action '" + action + "'. Allowed: apps, open, sessions, "
                        + "state, refresh, find, locator, events, requests, request, log, "
                        + "classify, fields, fill, undo, click, submit.");
            };
            // Write actions record themselves: they know their outcome (denied, expired, partial)
            // and carry redacted arguments - the generic recording below would log both wrong.
            boolean writeAction = action.equals("fill") || action.equals("undo")
                    || action.equals("click") || action.equals("submit");
            if (!action.equals("apps") && !action.equals("sessions") && !action.equals("classify")
                    && !writeAction) {
                String sessionId = string(raw, "sessionId");
                backend.session(sessionId).ifPresent(session ->
                        session.recordAction(action, arguments, "ok", null));
            }
            state.callsOk().incrementAndGet();
            return CallToolResult.builder().addTextContent(answer).build();
        } catch (IllegalArgumentException e) {
            return CallToolResult.builder().isError(true).addTextContent(e.getMessage()).build();
        } catch (RuntimeException e) {
            LOG.error("browser: tool call {} failed", action, e);
            return CallToolResult.builder().isError(true)
                    .addTextContent("browser: internal error on '" + action + "': "
                            + e.getMessage()).build();
        }
    }

    // ========================================================================
    // Actions
    // ========================================================================

    private String describeApps(BrowserSessions backend) {
        StringBuilder sb = new StringBuilder();
        sb.append("Registry: ").append(backend.apps().source())
                .append("  (only registered applications can be reached)\n\n");
        for (BrowserApp app : backend.apps().all()) {
            sb.append("  ").append(app.id())
                    .append("  ").append(app.upstream())
                    .append("  auth=").append(app.auth());
            if (!app.sensitivePaths().isEmpty()) {
                sb.append("  sensitive=").append(app.sensitivePaths());
            }
            if (!app.irreversiblePaths().isEmpty()) {
                sb.append("  irreversible=").append(app.irreversiblePaths());
            }
            // Rewrite rules change what the browser receives - they belong on the same line the
            // operator reads the other interventions from (docs/browser.md §3a).
            if (!app.rewriteRules().isEmpty()) {
                sb.append("  rewriteRules=");
                app.rewriteRules().forEach(rule ->
                        sb.append(rule.display()).append("->").append(rule.to()).append(' '));
            }
            sb.append('\n');
            if (!app.note().isEmpty()) {
                sb.append("        ").append(app.note()).append('\n');
            }
            sb.append("        open: ").append(BrowserHost.url(app.id(), backend.localPort(),
                    "/browser/" + app.id() + "/new")).append('\n');
        }
        if (!backend.apps().loadErrors().isEmpty()) {
            sb.append("\nComplaints:\n");
            backend.apps().loadErrors().forEach(problem -> sb.append("  - ").append(problem).append('\n'));
        }
        sb.append("\nSettings: writing=").append(backend.settings().writeEnabled() ? "on (M1)" : "OFF")
                .append(", review bypass (browser.apps.withoutReviews)=")
                .append(backend.settings().withoutReviews().isEmpty()
                        ? "none" : backend.settings().withoutReviews())
                .append(", field model (browser.fields.mode)=")
                .append(backend.settings().fieldMode().name().toLowerCase(Locale.ROOT))
                .append(", inject bridge=").append(backend.settings().injectBridge())
                .append(", relax CSP=").append(backend.settings().relaxCsp());
        // Where the audit trail lies is a fact the model has to know before it promises a file to
        // someone; if it is not anchored, the message names the property instead of a guessed path.
        sb.append("\nSession logs: ").append(!backend.settings().persist()
                ? "in memory only (browser.persist=false)"
                : backend.loggingProblem() == null ? backend.runBase()
                        : "not on disk - " + backend.loggingProblem());
        // M2 is a setting that changes what a session notices, so it is named where the model reads the
        // other switches - including the honest "off", which is the default and means M1 unchanged.
        sb.append("\nField model: ").append(backend.settings().fieldMode().display());
        if (backend.settings().fieldsOn()) {
            sb.append(" - one file per application under ")
                    .append(backend.fieldsBase() == null
                            ? "in memory only (no run directory anchored)" : backend.fieldsBase());
        }
        return sb.toString();
    }

    private String openSession(BrowserSessions backend, String appId) {
        if (appId.isBlank()) {
            throw new IllegalArgumentException("action=open needs an appId (see action=apps).");
        }
        BrowserSession session = backend.newSession(appId);
        String url = session.entryUrl();
        return "Session " + session.id() + " opened for " + appId
                + (session.app().port() > 0 ? " (own listener on port " + session.app().port() + ")" : "")
                + ".\nThe user has to open this address in Firefox and sign in there:\n  " + url + "\n"
                + "After that action=sessions reports the connection status and action=state the page.\n"
                + "Note: the session is only usable while the browser tab stays open - "
                + "the bridge reports in every " + backend.settings().pollSeconds() + " s.";
    }

    private String listSessions(BrowserSessions backend) {
        ArrayNode array = backend.mapper().createArrayNode();
        backend.sessions().forEach(session -> array.add(session.statusNode()));
        if (array.isEmpty()) {
            return "No open session. The user can open " + BrowserHost.indexUrl(backend.localPort())
                    + " (or action=open); until then there is nothing to see.";
        }
        ObjectNode out = backend.mapper().createObjectNode();
        out.set("sessions", array);
        out.put("writeEnabled", backend.settings().writeEnabled());
        if (!backend.settings().withoutReviews().isEmpty()) {
            out.set("reviewsBypassed", backend.mapper().valueToTree(backend.settings().withoutReviews()));
        }
        // Which mode the sessions run in belongs next to their state: "no slots were collected" has to
        // be distinguishable from "nothing on this page is computed".
        out.put("fieldsMode", backend.settings().fieldMode().name().toLowerCase(Locale.ROOT));
        if (backend.settings().fieldsOn()) {
            ArrayNode models = out.putArray("fieldModels");
            backend.sessions().forEach(session -> {
                ObjectNode entry = models.addObject();
                entry.put("app", session.app().id());
                entry.put("fields", backend.fieldModel(session.app().id()).size());
                entry.put("file", backend.fieldModel(session.app().id()).location());
            });
        }
        return out.toPrettyString();
    }

    private String showState(BrowserSessions backend, String sessionId, String frame, boolean full,
            boolean refresh, Object annotateRaw) {
        BrowserSession session = requireSession(backend, sessionId);
        if (refresh || session.snapshot() == null) {
            JsonNode fresh = session.requestSnapshot(frame, true,
                    java.time.Duration.ofSeconds(backend.settings().pollSeconds() + 12L).toMillis());
            String failure = fresh.path("error").asString("");
            if (!failure.isEmpty()) {
                return "No snapshot: " + failure + "\n" + fresh.path("hint").asString("");
            }
        }
        JsonNode snapshot = session.snapshot();
        if (snapshot == null) {
            return "Session " + session.id() + " has never delivered a snapshot ("
                    + session.describeBridge() + "). Is the tab open? Has the page finished loading?";
        }
        // The field key and its slot are noise in a normal session and the whole point in the strict
        // one - so the default follows the mode, and the caller can always say otherwise.
        boolean annotate = annotateRaw == null
                ? backend.settings().fieldsStrict()
                : (annotateRaw instanceof Boolean flag ? flag
                        : Boolean.parseBoolean(String.valueOf(annotateRaw)));
        BrowserFieldModel model = (annotate || backend.settings().fieldsOn())
                ? backend.fieldModel(session.app().id()) : null;
        String page = session.pageForDisplay();
        ObjectNode out = backend.mapper().createObjectNode();
        out.put("sessionId", session.id());
        out.put("app", session.app().id());
        out.put("page", session.pageForDisplay());
        out.put("title", session.title());
        out.put("rev", session.rev());
        out.put("quiescent", snapshot.path("quiescent").asBoolean(false));
        out.put("ageMs", System.currentTimeMillis() - snapshot.path("at").asLong(System.currentTimeMillis()));
        out.put("writesEnabled", backend.settings().writeEnabled());
        if (annotate) {
            out.put("fieldModel", model.location());
            out.put("fieldsKnown", model.size());
        } else if (!backend.settings().fieldsOn()) {
            out.put("fieldModel", "off (browser.fields.mode=off - no slots are known or collected)");
        }
        ArrayNode frames = out.putArray("frames");
        int total = 0;
        for (JsonNode frameNode : snapshot.path("frames")) {
            if (!frame.isBlank() && !frameNode.path("frame").asString("").equals(frame)) {
                continue;
            }
            ObjectNode kept = frames.addObject();
            kept.put("frame", frameNode.path("frame").asString(""));
            kept.put("url", frameNode.path("url").asString(""));
            if (frameNode.path("opaque").asBoolean(false)) {
                kept.put("opaque", true);
                continue;
            }
            ArrayNode elements = kept.putArray("elements");
            for (JsonNode element : frameNode.path("ax")) {
                if (element.path("role").asString("").equals("heading") && !full) {
                    continue;                       // context lines only on request
                }
                ObjectNode line = compact(element);
                if (annotate && !element.path("key").asString("").isEmpty()) {
                    line.put("key", element.path("key").asString(""));
                    model.annotate(line, page);
                }
                elements.add(line);
                total++;
            }
            if (frameNode.path("truncated").asBoolean(false)) {
                kept.put("truncated", true);
            }
        }
        out.put("elements", total);
        if (annotate && model != null) {
            // A form can send names the page never shows (docs/browser.md §7a, the payload side). The
            // snapshot cannot list them - hidden inputs are skipped there on purpose - so without this
            // line "these are the fields" would be a wrong promise, and a fill of a form would leave
            // out a value only the application knows.
            List<String> alsoSent = model.payloadOnly(page);
            if (!alsoSent.isEmpty()) {
                ArrayNode names = out.putArray("alsoSent");
                alsoSent.forEach(names::add);
            }
        }
        String json = out.toPrettyString();
        if (total > 500) {
            json += "\nNote: " + total + " elements. Narrow it down with frame=f0 or use action=find.";
        }
        return json;
    }

    private ObjectNode compact(JsonNode element) {
        ObjectNode out = BrowserSessions.get().mapper().createObjectNode();
        out.put("ref", element.path("ref").asString(""));
        out.put("role", element.path("role").asString(""));
        putIfPresent(out, element, "type");
        putIfPresent(out, element, "label");
        putIfPresent(out, element, "value");
        putIfPresent(out, element, "near");
        putIfPresent(out, element, "rowHead");
        putIfPresent(out, element, "colHead");
        putIfPresent(out, element, "href");
        putIfPresent(out, element, "src");
        if (element.path("required").asBoolean(false)) {
            out.put("required", true);
        }
        if (element.path("disabled").asBoolean(false)) {
            out.put("disabled", true);
        }
        if (element.path("checked").isBoolean()) {
            out.put("checked", element.path("checked").asBoolean());
        }
        if (element.path("options").isArray() && !element.path("options").isEmpty()) {
            out.set("options", element.path("options").deepCopy());
        }
        if (element.path("opaque").asBoolean(false)) {
            out.put("opaque", true);
        }
        return out;
    }

    private String find(BrowserSessions backend, String sessionId, String query, int limit) {
        BrowserSession session = requireSession(backend, sessionId);
        if (query.isBlank()) {
            throw new IllegalArgumentException("action=find needs a query.");
        }
        List<ObjectNode> hits = session.find(query, limit);
        if (hits.isEmpty()) {
            return "No hit for '" + query + "' in revision " + session.rev()
                    + ". Either the page has not been captured yet (action=refresh) or the text stands "
                    + "in an image/canvas - or in an iframe that is not registered.";
        }
        StringBuilder sb = new StringBuilder("Hits in session ").append(session.id())
                .append(" (rev ").append(session.rev()).append("):\n");
        ArrayNode array = backend.mapper().createArrayNode();
        hits.forEach(array::add);
        return sb.append(array.toPrettyString()).toString();
    }

    private String locator(BrowserSessions backend, String sessionId, String ref) {
        BrowserSession session = requireSession(backend, sessionId);
        JsonNode element = session.element(ref);
        if (element == null) {
            return "ref '" + ref + "' is not known in revision " + session.rev() + ". "
                    + "Pull 'state' again after a navigation or a reload.";
        }
        ObjectNode out = backend.mapper().createObjectNode();
        out.put("ref", ref);
        out.put("rev", session.rev());
        out.set("locator", element.path("loc").deepCopy());
        out.set("fingerprint", element.path("fp").deepCopy());
        out.put("usage", "M1 locates via locator (id → name → label → css) and compares "
                + "the fingerprint before anything is written.");
        return out.toPrettyString();
    }

    private String events(BrowserSessions backend, String sessionId, int since, int limit) {
        BrowserSession session = requireSession(backend, sessionId);
        List<ObjectNode> found = session.eventsSince(since, limit);
        if (found.isEmpty()) {
            return "No events since #" + since + " (" + session.eventCount() + " in total).";
        }
        StringBuilder sb = new StringBuilder();
        int lastSeq = since;
        for (ObjectNode event : found) {
            lastSeq = Math.max(lastSeq, event.path("seq").asInt());
            sb.append('#').append(event.path("seq").asInt()).append(' ')
                    .append(clockOf(event.path("ts").asString("")))
                    .append(' ').append(event.path("frame").asString("f0"))
                    .append(' ').append(event.path("k").asString(""))
                    .append(' ').append(event.path("ref").asString(""))
                    .append(' ').append(event.path("label").asString(""))
                    .append(event.has("value") ? " = \"" + event.path("value").asString("") + "\"" : "")
                    .append(event.has("url") ? " → " + event.path("url").asString("") : "")
                    .append(event.has("entries") ? " (leak: " + event.path("entries").size() + ")" : "")
                    .append('\n');
        }
        return sb.append("\ncontinue with since=").append(lastSeq).append('\n').toString();
    }

    private String requests(BrowserSessions backend, String sessionId, int limit) {
        BrowserSession session = requireSession(backend, sessionId);
        List<BrowserExchange> found = session.recentExchanges(limit);
        if (found.isEmpty()) {
            return "No proxied exchange in session " + session.id() + " yet.";
        }
        StringBuilder sb = new StringBuilder("Last ").append(found.size())
                .append(" exchanges (action=request seq=N for details, bodies up to ")
                .append(backend.settings().captureBytes()).append(" characters):\n");
        ArrayNode array = backend.mapper().createArrayNode();
        found.forEach(exchange -> array.add(exchange.toSummaryNode()));
        return sb.append(array.toPrettyString()).toString();
    }

    private String oneRequest(BrowserSessions backend, String sessionId, int seq) {
        BrowserSession session = requireSession(backend, sessionId);
        BrowserExchange exchange = session.exchange(seq);
        if (exchange == null) {
            return "Capture #" + seq + " is not (any longer) in " + session.id()
                    + "'s ring buffer (the newest " + BrowserSession.KEPT_EXCHANGES
                    + " are kept). In full it lies in " + session.logsLocation("requests.jsonl") + ".";
        }
        return exchange.toFullNode().toPrettyString();
    }

    private String actionLog(BrowserSessions backend, String sessionId, int limit) {
        BrowserSession session = requireSession(backend, sessionId);
        List<ObjectNode> entries = session.recentActions(limit);
        ArrayNode array = backend.mapper().createArrayNode();
        entries.forEach(array::add);
        return entries.isEmpty() ? "This session has no tool calls yet."
                : "Action log " + session.id() + " (" + session.logsLocation("actions.jsonl") + ")\n"
                        + array.toPrettyString();
    }

    // ========================================================================
    // Knowledge actions (M2, docs/browser.md §7a)
    // ========================================================================

    /**
     * The value pass over the current page (M2): which fields are there, what holds in them now, what
     * the page states about them on its own, and - out of everything observed so far - where their
     * value comes from. Reads the page and changes nothing, so it is available with the write switch
     * off, like a snapshot.
     */
    private String classify(BrowserSessions backend, String sessionId, JsonNode arguments) {
        BrowserSession session = requireSession(backend, sessionId);
        if (!backend.settings().fieldsOn()) {
            return "M2 is switched off (browser.fields.mode=off), so nothing is collected and a field"
                    + " model stays empty - that is the point of the default: a session runs as M1 built"
                    + " it. For the knowledge start the development server with"
                    + " -Dbrowser.fields.mode=observe, with =strict additionally the write guard for"
                    + " computed fields.";
        }
        JsonNode answer = session.requestObserve(waitMillis(backend));
        String failure = answer.path("error").asString("");
        if (!failure.isEmpty()) {
            session.recordAction("classify", arguments, "failed", failure);
            return "No value pass: " + failure + "\n" + answer.path("hint").asString("");
        }
        String page = answer.path("page").asString(session.pageForDisplay());
        JsonNode seen = answer.path("fields");
        BrowserFieldModel model = backend.fieldModel(session.app().id());
        model.noteValues(session, page, "observe", seen, answer.path("truncated").asBoolean(false));
        ObjectNode out = backend.mapper().createObjectNode();
        out.put("sessionId", session.id());
        out.put("app", session.app().id());
        out.put("page", BrowserFieldModel.pageOf(page));
        out.put("quiescent", answer.path("quiescent").asBoolean(false));
        ArrayNode rows = out.putArray("fields");
        List<ObjectNode> classified = new ArrayList<>();
        for (JsonNode field : seen) {
            String key = field.path("key").asString("");
            if (!key.isEmpty()) {
                classified.add(model.row(page, key, model.evidence(page, key)));
            }
        }
        // Same field, other URL - say so, instead of letting "unknown here" read as "unknown".
        model.annotateSiblings(classified);
        classified.forEach(rows::add);
        // The payload side (M2b): what this page sends that no control of it explains. Kept out of
        // `fields` because that array answers "which writable fields are here" and a hidden input is
        // neither writable from a ref nor visible in the snapshot - but leaving it out entirely would
        // let the answer promise a complete list of the form and skip exactly the names the
        // application receives.
        List<ObjectNode> alsoSent = model.payloadRows(page);
        model.annotateSiblings(alsoSent);
        if (!alsoSent.isEmpty()) {
            ArrayNode sent = out.putArray("alsoSent");
            alsoSent.forEach(sent::add);
            out.put("alsoSentNote", "the page sends these too and shows no control for them (hidden"
                    + " inputs): there is no ref to fill them with, and the application fills some of"
                    + " them in itself");
        }
        out.put("model", model.location());
        out.put("fieldsKnown", model.size());
        if (answer.path("truncated").asBoolean(false)) {
            out.put("note", "the value pass reached the element budget: a field missing from this list"
                    + " is not evidence of anything, it was simply not read");
        } else if (!answer.path("quiescent").asBoolean(true)) {
            out.put("note", "the page had not come to rest while it was read - values may still change");
        } else {
            out.put("note", "evidence recorded for " + rows.size() + " field(s). A computed field shows"
                    + " up either from a write diff, from a second read of this same document, or from"
                    + " the payload side (a field no request of this page ever carried). So looking"
                    + " again - and submitting once - is what teaches the model.");
        }
        session.recordAction("classify", arguments, "ok", rows.size() + " field(s) on "
                + BrowserFieldModel.pageOf(page));
        return out.toPrettyString();
    }

    /**
     * What earlier sessions of this application taught the model about its forms - the read side of
     * M2, working without a bridge, without the write switch and with the tab long closed.
     */
    private String fields(BrowserSessions backend, String sessionId, String appIdRaw, String query,
            int limit) {
        String appId = "";
        String page = "";
        if (!sessionId.isBlank()) {
            BrowserSession session = requireSession(backend, sessionId);
            appId = session.app().id();
            page = session.pageForDisplay();
        } else if (!appIdRaw.isBlank()) {
            appId = backend.app(appIdRaw)
                    .orElseThrow(() -> new IllegalArgumentException("Application '" + appIdRaw
                            + "' is not registered (action=apps lists what is)."))
                    .id();
        } else {
            throw new IllegalArgumentException("action=fields needs a sessionId (the page you are on) or"
                    + " an appId (every page of that application).");
        }
        BrowserFieldModel model = backend.fieldModel(appId);
        List<ObjectNode> rows = model.rows(page, query, limit);
        ObjectNode out = backend.mapper().createObjectNode();
        out.put("app", appId);
        if (!page.isBlank()) {
            out.put("page", BrowserFieldModel.pageOf(page));
            // What the payload side knows about this page, because it decides whether a `computed`
            // may rest on absence at all: no readable payload of the page, no such claim - and a gap
            // (a body the operator excluded) switches the claim off for the whole page. Said out loud,
            // so that "nothing is produced here" is never read as "nothing was ever sent here".
            JsonNode evidence = model.payloadEvidence(page);
            if (evidence != null) {
                out.set("payloads", evidence);
            } else {
                out.put("payloads", "no payload of this page captured yet");
            }
        }
        out.put("mode", backend.settings().fieldMode().name().toLowerCase(Locale.ROOT));
        out.put("model", model.location());
        out.put("fieldsKnown", model.size());
        ArrayNode array = out.putArray("fields");
        rows.forEach(array::add);
        if (rows.isEmpty()) {
            out.put("note", "nothing observed yet" + (query.isBlank() ? "" : " for '" + query + "'")
                    + ". The model grows from value passes, from the diffs of write operations and"
                    + " from the payloads of the page: action=classify on the page, then fill, click"
                    + " or submit once.");
        } else if (backend.settings().fieldsOn()) {
            out.put("note", "slots describe where a value comes from, not what you may write. Only a"
                    + " computed field is ever held back, and force:true on the entry says it was meant."
                    + " A row with fromPayload:true is a name the page sends without showing a control"
                    + " for it (a hidden input) - there is nothing to fill there.");
        }
        return out.toPrettyString();
    }

    // ========================================================================
    // Write actions (M1, docs/browser.md §7)
    // ========================================================================

    /**
     * Fills form controls: one recipe per control type, fingerprint check against the snapshot the
     * refs came from, undo journal in the bridge, readback with the app's validation messages. Free
     * by design (decision L8): a field write is reversible and invisible to the application - the
     * diff lands in the session's fill journal for the later, gated submit.
     *
     * <p>With M2 on (docs/browser.md §7a) the bridge additionally reads the writable fields before the
     * first write and again after the page rests, so the answer can name what the application
     * recalculated on its own. In the strict mode a field known to be computed is held back before the
     * command goes out - unless the caller put {@code force:true} on that entry, because then it was
     * meant. Nothing else about a write changes, and with the mode off this method is what M1 built.</p>
     */
    private String fill(BrowserSessions backend, String sessionId, Object valuesRaw, boolean forceAll,
            JsonNode arguments) {
        BrowserSession session = requireSession(backend, sessionId);
        requireWriteEnabled(backend);
        List<ObjectNode> items = parseValues(backend, session, valuesRaw);
        BrowserFieldModel model = backend.fieldModel(session.app().id());
        String page = session.pageForDisplay();
        boolean fieldsOn = backend.settings().fieldsOn();

        // The guard of the strict mode, field by field: one computed field must not take the other
        // entries with it, and the refusal has to arrive in the same shape as a bridge error.
        List<ObjectNode> going = new ArrayList<>();
        ArrayNode held = backend.mapper().createArrayNode();
        for (ObjectNode item : items) {
            JsonNode element = session.element(item.path("ref").asString(""));
            String key = element == null ? "" : element.path("key").asString("");
            if (!key.isEmpty()) {
                item.put("key", key);
            }
            if (fieldsOn && backend.settings().fieldsStrict() && !key.isEmpty()) {
                String refusal = model.writeGuard(page, key,
                        forceAll || item.path("force").asBoolean(false));
                if (refusal != null) {
                    ObjectNode blocked = backend.mapper().createObjectNode();
                    blocked.put("ref", item.path("ref").asString(""));
                    blocked.put("key", key);
                    blocked.put("label", element.path("label").asString(""));
                    blocked.put("ok", false);
                    blocked.put("error", "field-computed");
                    blocked.put("hint", refusal);
                    blocked.set("value", item.path("value").deepCopy());
                    held.add(blocked);
                    continue;
                }
            }
            going.add(item);
        }
        if (going.isEmpty()) {
            session.recordAction("fill", redactValuesForLog(session, arguments), "blocked",
                    held.size() + " entry(ies) held back: computed field(s)");
            ObjectNode refusal = backend.mapper().createObjectNode();
            refusal.put("written", false);
            refusal.set("heldBack", held);
            refusal.put("next", "browser.fields.mode=strict does not write into a field the application"
                    + " recalculates. Leave it out (the page fills it in), or add force:true to that"
                    + " entry when it really was meant.");
            return refusal.toPrettyString();
        }
        int heldBack = held.size();

        ObjectNode command = backend.mapper().createObjectNode();
        command.put("t", "fill");
        ArrayNode array = command.putArray("items");
        going.forEach(array::add);
        if (fieldsOn) {
            command.put("diff", true);
        }
        JsonNode answer = session.requestResult(command, waitMillis(backend));
        String failure = answer.path("error").asString("");
        if (!failure.isEmpty()) {
            session.recordAction("fill", redactValuesForLog(session, arguments), "failed", failure);
            return "Fill not executed: " + failure + "\n" + answer.path("hint").asString("");
        }
        for (JsonNode result : answer.path("results")) {
            if (!result.path("ok").asBoolean(false)) {
                continue;
            }
            ObjectNode entry = backend.mapper().createObjectNode();
            entry.put("ref", result.path("ref").asString(""));
            entry.put("label", result.path("label").asString(""));
            entry.set("oldValue", result.path("oldValue"));
            entry.set("value", result.path("value"));
            String key = result.path("key").asString("");
            if (!key.isEmpty()) {
                entry.put("key", key);
            }
            if (result.path("secret").asBoolean(false)) {
                entry.put("secret", true);
            }
            String validation = result.path("validationMessage").asString("");
            if (!validation.isEmpty()) {
                entry.put("validationMessage", validation);
            }
            session.journalAdd(entry);
            if (fieldsOn && !key.isEmpty()) {
                // A write is evidence on its own, whether or not the value ended up differing: a hand
                // decided it, and that is what `parameter` means. Said to the session as well, so that
                // the passive comparison of the next two reads does not mistake our own write for a
                // recalculation before the bridge's debounced input event arrives.
                session.noteTypedKey(key);
                model.noteWritten(session, page, key, "fill");
            }
        }
        JsonNode diff = answer.path("diff");
        if (diff.isObject()) {
            session.recordDiff(diff);
            model.noteDiff(session, page, "fill", diff);
        }
        boolean allOk = true;
        for (JsonNode result : answer.path("results")) {
            allOk &= result.path("ok").asBoolean(false);
        }
        allOk &= heldBack == 0;
        session.recordAction("fill", redactValuesForLog(session, arguments),
                heldBack == 0 ? (allOk ? "ok" : "partial") : "partial",
                answer.path("quiescent").asBoolean(true)
                        ? (heldBack == 0 ? null : heldBack + " entry(ies) held back as computed")
                        : "page not settled at readback - values may still change");
        ObjectNode out = backend.mapper().createObjectNode();
        out.put("sessionId", session.id());
        out.put("rev", session.rev());
        out.set("results", answer.path("results").deepCopy());
        if (heldBack > 0) {
            out.set("heldBack", held.deepCopy());
        }
        out.put("quiescent", answer.path("quiescent").asBoolean(true));
        out.put("journal", session.journal().size());
        if (diff.isObject()) {
            out.set("diff", diff.deepCopy());
            ArrayNode recalculated = out.putArray("recalculated");
            diff.path("recalculated").forEach(item -> recalculated.add(
                    item.path("label").asString(item.path("key").asString(""))));
        }
        out.put("next", "check the results; for the application's own validation messages pull "
                + "action=state, then either submit (review) or leave the saving to the user");
        return out.toPrettyString();
    }

    /**
     * Restores what the LLM changed on this page since it was loaded - the bridge's undo journal,
     * applied in reverse order.
     */
    private String undo(BrowserSessions backend, String sessionId, JsonNode arguments) {
        BrowserSession session = requireSession(backend, sessionId);
        requireWriteEnabled(backend);
        ObjectNode command = backend.mapper().createObjectNode();
        command.put("t", "undo");
        if (backend.settings().fieldsOn()) {
            command.put("diff", true);
        }
        String page = session.pageForDisplay();
        JsonNode answer = session.requestResult(command, waitMillis(backend));
        String failure = answer.path("error").asString("");
        if (!failure.isEmpty()) {
            session.recordAction("undo", arguments, "failed", failure);
            return "Undo not executed: " + failure + "\n" + answer.path("hint").asString("");
        }
        JsonNode diff = answer.path("diff");
        if (diff.isObject()) {
            session.recordDiff(diff);
            backend.fieldModel(session.app().id()).noteDiff(session, page, "undo", diff);
        }
        // What the undo put back was written by us, whatever the page then made of it: without this the
        // next two reads could read our own restoring as a recalculation of the application.
        for (ObjectNode entry : session.journal()) {
            session.noteTypedKey(entry.path("key").asString(""));
        }
        session.recordAction("undo", arguments,
                answer.path("ok").asBoolean(false) ? "ok" : "partial",
                answer.path("restored").asInt() + " value(s) restored");
        return answer.toPrettyString();
    }

    /**
     * Clicks a button or link. A {@code type=submit} button is the gate - it goes through
     * {@link #gatedSubmit} like {@code submit}. A plain {@code <button>} is an implicit submit in
     * HTML, but not recognizable from the snapshot; the description therefore says: prefer
     * {@code submit} for saving.
     */
    private String click(BrowserSessions backend, String sessionId, String ref, String reason,
            JsonNode arguments) {
        BrowserSession session = requireSession(backend, sessionId);
        requireWriteEnabled(backend);
        JsonNode element = requireElement(session, ref);
        boolean submitButton = element.path("role").asString("").equals("button")
                && "submit".equalsIgnoreCase(element.path("type").asString(""));
        if (submitButton) {
            return gatedSubmit(backend, session, ref, element, reason, 0, arguments);
        }
        int beforeSeq = session.eventCount();
        String pageBefore = session.pageForDisplay();
        JsonNode answer = sendClick(backend, session, ref, element);
        String failure = answer.path("error").asString("");
        if (!failure.isEmpty()) {
            if ("no-answer-from-bridge".equals(failure) && navigationFollowed(session, beforeSeq, pageBefore)) {
                session.recordAction("click", arguments, "ok",
                        "result lost on unload, navigation followed");
                return "Click executed - the page navigated and the bridge's answer was lost on the "
                        + "unload. Pull action=state for the new page.";
            }
            session.recordAction("click", arguments, "failed", failure);
            return "Click not executed: " + failure + "\n" + answer.path("hint").asString("");
        }
        keepDiff(backend, session, pageBefore, "click", answer);
        session.recordAction("click", arguments, "ok", null);
        return answer.toPrettyString();
    }

    /**
     * The gated save: review entry with the field diff, blocking wait, click only after APPROVED
     * (decision L8) - or directly when the application is listed in
     * {@code browser.apps.withoutReviews} (decision L9, visibly).
     */
    private String submit(BrowserSessions backend, String sessionId, String ref, String reason,
            int waitSeconds, JsonNode arguments) {
        BrowserSession session = requireSession(backend, sessionId);
        requireWriteEnabled(backend);
        JsonNode element = requireElement(session, ref);
        return gatedSubmit(backend, session, ref, element, reason, waitSeconds, arguments);
    }

    private String gatedSubmit(BrowserSessions backend, BrowserSession session, String ref,
            JsonNode element, String reason, int waitSeconds, JsonNode arguments) {
        String buttonLabel = element.path("label").asString("");
        if (buttonLabel.isEmpty()) {
            buttonLabel = ref;
        }
        // What gets audited is what the model asked for, not the command the server derived from it.
        JsonNode args = arguments;

        // Development bypass (L9): without a review entry, but not without a trace.
        if (backend.settings().reviewsBypassed(session.app().id())) {
            session.recordAction("submit", args, "ok",
                    "review skipped (browser.apps.withoutReviews): " + buttonLabel);
            String pageBefore = session.pageForDisplay();
            JsonNode answer = sendClick(backend, session, ref, element);
            keepDiff(backend, session, pageBefore, "submit", answer);
            String failure = answer.path("error").asString("");
            if (!failure.isEmpty()) {
                return "Click after the skipped review not executed: " + failure
                        + "\n" + answer.path("hint").asString("");
            }
            return "Submitted without review (browser.apps.withoutReviews for '"
                    + session.app().id() + "').\n" + answer.toPrettyString();
        }

        // The diff: what the LLM changed on this page. The values come from the bridge's readback,
        // so secret fields are already ‹redacted› here (docs/browser.md §7.6).
        List<String> changes = new ArrayList<>();
        List<String> validation = new ArrayList<>();
        for (ObjectNode item : session.journal()) {
            String label = item.path("label").asString(item.path("ref").asString(""));
            changes.add(label + ": " + item.path("oldValue").asString("") + " → "
                    + item.path("value").asString(""));
            String message = item.path("validationMessage").asString("");
            if (!message.isEmpty()) {
                validation.add(label + ": " + message);
            }
        }
        List<ReviewAttribute> attributes = new ArrayList<>();
        attributes.add(ReviewAttributes.text("page", "Page", session.pageForDisplay()));
        attributes.add(ReviewAttributes.text("button", "Button", buttonLabel + " (" + ref + ")"));
        attributes.add(ReviewAttributes.list("changes", "Changed fields", changes));
        attributes.add(ReviewAttributes.list("validation", "Validation messages", validation));
        if (backend.settings().fieldsOn()) {
            // M2 in the reviewer's line: what the model entered is the diff above, what the application
            // makes of it afterwards is this - and both stand here before anybody approves.
            BrowserFieldModel model = backend.fieldModel(session.app().id());
            List<String> recalculated = new ArrayList<>();
            JsonNode diff = session.lastDiff();
            if (diff != null) {
                for (JsonNode item : diff.path("recalculated")) {
                    recalculated.add(item.path("label").asString(item.path("key").asString("")));
                }
            }
            if (!recalculated.isEmpty()) {
                attributes.add(ReviewAttributes.list("recalculated",
                        "Recalculated by the application after the last write", recalculated));
            }
            List<String> computed = model.computedFields(session.pageForDisplay());
            if (!computed.isEmpty()) {
                attributes.add(ReviewAttributes.list("computedFields",
                        "Fields this form computes (not entered here)", computed));
            }
            // What leaves the page besides the diff above: a hidden input is invisible to the model as
            // well as to the reviewer, and an approval that only covers the visible fields is not an
            // approval of the request (docs/browser.md §7a, payload side).
            List<String> alsoSent = model.payloadOnly(session.pageForDisplay());
            if (!alsoSent.isEmpty()) {
                attributes.add(ReviewAttributes.list("payloadFields",
                        "The page sends these as well, without showing a field for them", alsoSent));
            }
        }
        attributes.add(ReviewAttributes.text("unchecked", "Not checked",
                "The model cannot see server-side effects of this action; the diff covers only what "
                        + "it entered on this page (revision " + session.rev() + ")."));
        if (reason != null && !reason.isBlank()) {
            attributes.add(ReviewAttributes.text("reason", "Reason", reason));
        }
        String title = ReviewAttributes.sanitize("Submit '" + buttonLabel + "' on "
                + session.app().id() + " - " + session.pageForDisplay(), ReviewAttributes.MAX_TITLE_CHARS);

        BrowserReviewService reviews = new BrowserReviewService();
        BrowserReviewService.Decision decision = reviews.awaitDecision(title,
                reason == null ? "" : reason, attributes,
                waitSeconds > 0 ? waitSeconds : null);
        ReviewEntry entry = decision.entry();
        if (decision.status() != ReviewStatus.APPROVED) {
            reviews.submitResult(entry, new ReviewResponseEntry(decision.status(), false,
                    "not executed: " + decision.status().display(), List.of(), decision.comment(), 0L));
            session.recordAction("submit", args, decision.status().display(),
                    "review=" + entry.id() + (decision.comment().isEmpty()
                            ? "" : ": " + decision.comment()));
            return "Not executed: the review request " + entry.id() + " was "
                    + decision.status().display() + (decision.comment().isEmpty()
                    ? "." : " - comment: " + decision.comment());
        }
        // Approved: now, and only now, does the click leave the server. The attribution note lets
        // the capture tell this POST from a user submit (best effort, §7.5).
        int beforeSeq = session.eventCount();
        String pageBefore = session.pageForDisplay();
        session.beginLlmSubmit(entry.id());
        JsonNode answer = sendClick(backend, session, ref, element);
        String failure = answer.path("error").asString("");
        // A submitting click navigates - the answer (and sometimes every report of the dying page)
        // is lost on the unload. A new page under the same session counts as the answer.
        boolean clicked = failure.isEmpty()
                || ("no-answer-from-bridge".equals(failure)
                    && navigationFollowed(session, beforeSeq, pageBefore));
        boolean answerLost = !failure.isEmpty() && clicked;
        reviews.submitResult(entry, clicked
                ? ReviewResponseEntry.completed("click executed on '" + buttonLabel + "'"
                        + (answerLost ? " (answer lost on unload, navigation followed)" : ""),
                        List.of(), 0L)
                : ReviewResponseEntry.error("click not executed", failure));
        keepDiff(backend, session, pageBefore, "submit", answer);
        session.recordAction("submit", args, clicked ? "ok" : "failed",
                "review=" + entry.id() + (clicked ? "" : ": " + failure));
        if (!clicked) {
            return "Approved (" + entry.id() + "), but the click did not get through: " + failure
                    + "\n" + answer.path("hint").asString("");
        }
        ObjectNode out = backend.mapper().createObjectNode();
        out.put("review", entry.id());
        out.put("submitted", true);
        out.set("click", answer.deepCopy());
        if (answerLost) {
            out.put("note", "the bridge's answer was lost on the unload, but the page navigated - "
                    + "pull action=state for the new page");
        } else {
            out.put("note", "if the answer lacks a navigation, check action=events for 'nav' - the "
                    + "bridge's answer can be lost on unload");
        }
        return out.toPrettyString();
    }

    /**
     * True when the click clearly navigated: either a {@code nav} event arrived after the given event
     * sequence, or the session's page changed (the new page's bridge reports in with {@code hello}).
     * The substitute answer for a click whose result was lost on the unload.
     */
    private static boolean navigationFollowed(BrowserSession session, int sinceSeq, String pageBefore) {
        if (!session.pageForDisplay().equals(pageBefore)) {
            return true;
        }
        return session.eventsSince(sinceSeq, 50).stream()
                .anyMatch(event -> event.path("k").asString("").equals("nav"));
    }

    /**
     * Sends the click command. A click that navigates can lose its answer on the unload - the
     * callers report that case in their answer text (action=events shows the {@code nav}).
     */
    private JsonNode sendClick(BrowserSessions backend, BrowserSession session, String ref,
            JsonNode element) {
        ObjectNode command = backend.mapper().createObjectNode();
        command.put("t", "click");
        command.put("ref", ref);
        attachFingerprint(command, element);
        if (backend.settings().fieldsOn()) {
            // A button that triggers a fetch is the cheapest way to learn which fields the application
            // computes - the bridge only answers with a diff when the page stayed where it was.
            command.put("diff", true);
        }
        return session.requestResult(command, waitMillis(backend));
    }

    /**
     * Files the value diff a write reported: kept on the session (the reviewer reads it) and given to
     * the field model of the application (the next session learns from it).
     */
    private void keepDiff(BrowserSessions backend, BrowserSession session, String page, String via,
            JsonNode answer) {
        JsonNode diff = answer.path("diff");
        if (!diff.isObject()) {
            return;
        }
        session.recordDiff(diff);
        backend.fieldModel(session.app().id()).noteDiff(session, page, via, diff);
    }

    /**
     * The fingerprint a write has to match: what the snapshot published as {@code fp}, i.e. the
     * fingerprint of the CONTROL - not {@code entry.label}, which for links and cells carries the text
     * content. Sending the display text made the bridge compare it against its own {@code labelOf()}
     * (empty for an unlabelled {@code <a>}) and refuse every correct ref to a link.
     *
     * @param target  the command or item the expectation is attached to
     * @param element the snapshot node the ref came from
     */
    static void attachFingerprint(ObjectNode target, JsonNode element) {
        JsonNode fp = element.path("fp");
        String role = fp.path("role").asString(element.path("role").asString(""));
        if (!role.isEmpty()) {
            target.put("role", role);
        }
        String label = fp.path("label").asString("");
        if (!label.isEmpty()) {
            target.put("label", label);
        }
    }

    /**
     * Parses the {@code values} argument: an array of {@code {ref, value, why?}} (as JSON array or as
     * JSON string), each ref checked against the current snapshot - the fingerprint (role, label)
     * travels with the command so the bridge can refuse a stale element.
     */
    private List<ObjectNode> parseValues(BrowserSessions backend, BrowserSession session,
            Object valuesRaw) {
        if (valuesRaw == null) {
            throw new IllegalArgumentException("action=fill needs values: [{ref, value, why?}] - "
                    + "refs from action=state.");
        }
        JsonNode node;
        if (valuesRaw instanceof String text && !text.isBlank()) {
            try {
                node = backend.mapper().readTree(text);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("values is not readable JSON: " + e.getMessage());
            }
        } else {
            node = backend.mapper().convertValue(valuesRaw, JsonNode.class);
        }
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw new IllegalArgumentException("action=fill needs values as an array: "
                    + "[{ref, value, why?}].");
        }
        List<ObjectNode> items = new ArrayList<>();
        for (JsonNode given : node) {
            String ref = given.path("ref").asString("");
            if (ref.isBlank()) {
                throw new IllegalArgumentException("every value needs a ref "
                        + "(like f0/e12, from action=state).");
            }
            JsonNode element = requireElement(session, ref);
            if (!given.has("value")) {
                throw new IllegalArgumentException("the entry for '" + ref + "' needs a value.");
            }
            ObjectNode item = backend.mapper().createObjectNode();
            item.put("ref", ref);
            item.set("value", given.path("value"));
            attachFingerprint(item, element);
            String why = given.path("why").asString("");
            if (!why.isBlank()) {
                item.put("why", why);
            }
            if (given.path("force").asBoolean(false)) {
                // Said out loud per entry: the strict mode holds a computed field back without it, and
                // the action log has to show that the caller knew (docs/browser.md §7a).
                item.put("force", true);
            }
            items.add(item);
        }
        return items;
    }

    /**
     * Redacts the values of secret fields before anything lands in the action log: the log is the
     * audit trail and the learning corpus - a password may be written, never stored (§7.6).
     */
    static JsonNode redactValuesForLog(BrowserSession session, JsonNode arguments) {
        String field = arguments.path("values").isArray() ? "values" : "items";
        JsonNode values = arguments.path(field);
        if (!values.isArray()) {
            return arguments;
        }
        // deepCopy() is generic and returns the static type of the receiver - on a JsonNode-typed
        // argument that is JsonNode, even though the instance is an ObjectNode (see the same cast
        // in BrowserSession.addEvent).
        ObjectNode copy = (ObjectNode) arguments.deepCopy();
        JsonNode copied = copy.path(field);
        for (int i = 0; i < values.size() && copied.isArray() && i < copied.size(); i++) {
            JsonNode item = values.get(i);
            JsonNode element = session.element(item.path("ref").asString(""));
            boolean secret = element != null
                    && (element.path("secret").asBoolean(false)
                        || "password".equalsIgnoreCase(element.path("type").asString("")));
            if (secret) {
                ((ObjectNode) copied.get(i)).put("value", BrowserSecrets.MASK);
            }
        }
        return copy;
    }

    private static void requireWriteEnabled(BrowserSessions backend) {
        if (!backend.settings().writeEnabled()) {
            throw new IllegalArgumentException("Writing is switched off (browser.write.enabled=false). "
                    + "The bridge would reject every write command - enable it on the development "
                    + "server first (docs/browser.md §7).");
        }
    }

    private JsonNode requireElement(BrowserSession session, String ref) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("the action needs a ref (from action=state).");
        }
        JsonNode element = session.element(ref);
        if (element == null) {
            throw new IllegalArgumentException("ref '" + ref + "' is not known in revision "
                    + session.rev() + ". Pull 'state' again after a navigation or a reload.");
        }
        return element;
    }

    private static long waitMillis(BrowserSessions backend) {
        return java.time.Duration.ofSeconds(backend.settings().pollSeconds() + 12L).toMillis();
    }

    // ========================================================================
    // Odds and ends
    // ========================================================================

    private static void putIfPresent(ObjectNode out, JsonNode element, String name) {
        JsonNode value = element.path(name);
        if (!value.isMissingNode() && !value.isNull() && !value.asString("").isEmpty()) {
            out.put(name, value.asString(""));
        }
    }

    /**
     * A raw argument as a node, so that the action log carries the actual request along.
     *
     * @param backend the administration (supplies the mapper)
     * @param raw     the arguments of the tool call
     * @return an object node, never {@code null}
     */
    private static JsonNode asArguments(BrowserSessions backend, Map<String, Object> raw) {
        try {
            JsonNode node = backend.mapper().convertValue(raw, JsonNode.class);
            return node != null && node.isObject() ? node : backend.mapper().createObjectNode();
        } catch (RuntimeException e) {
            ObjectNode fallback = backend.mapper().createObjectNode();
            raw.forEach((key, value) -> fallback.put(key, String.valueOf(value)));
            return fallback;
        }
    }

    private static BrowserSession requireSession(BrowserSessions backend, String id) {
        if (id == null || id.isBlank()) {
            String known = backend.sessions().stream().map(BrowserSession::id)
                    .reduce((a, b) -> a + ", " + b).orElse("none");
            throw new IllegalArgumentException("the action needs a sessionId. Open sessions: "
                    + known + " (action=sessions).");
        }
        return backend.session(id)
                .orElseThrow(() -> new IllegalArgumentException("Session '" + id + "' is not open. "
                        + "The user has probably closed the tab; action=sessions shows what is alive."));
    }

    private static String error(String text) {
        return "browser: " + text;
    }

    /**
     * @param timestamp ISO-8601 timestamp, possibly empty
     * @return the time as {@code HH:mm:ss}, or "-"
     */
    private static String clockOf(String timestamp) {
        if (timestamp == null || timestamp.length() < 20) {
            return "-";
        }
        return timestamp.substring(11, 19);
    }

    private static String string(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static boolean bool(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return value instanceof Boolean flag ? flag : value != null && Boolean.parseBoolean(value.toString());
    }

    private static int integer(Map<String, Object> arguments, String key, int fallback) {
        Object value = arguments.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
