package org.rogmann.mcp2sdk.review;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the render models of the review pages, so that one page can show every tool.
 * <p>
 * <b>Why the frame is built here and not in the tool:</b> if each tool assembled its own view map,
 * two tools would render the same status differently, {@code expiresAt} would appear in one tool's
 * detail page and be missing in another's, and the list page would break on the second tool. So the
 * frame - id, tool, title, status, timestamps, attributes, response, open/terminal markers, the
 * persistence note - is built here once, and a tool contributes only attributes. A tool that wants
 * to add something (a diff section, a report table) does it through
 * {@link ReviewService#extendDetailView}; it may add keys, never redefine the frame.
 * </p>
 * <p>
 * The models are plain maps of strings, numbers and booleans, ready for a template: no tool types
 * cross into the page beyond {@link ReviewAttribute} and {@link ReviewCheck}.
 * </p>
 */
public final class ReviewView {

    /**
     * Number of <em>finished</em> requests shown per tool on the list page. Open requests are
     * always shown; a long-running server would otherwise bury the one the reviewer has to act on
     * under a hundred completed ones. Nothing is deleted - only not displayed.
     */
    public static final int MAX_HISTORY_PER_TOOL = 25;

    /** How many attributes the list page shows before pointing at the detail page. */
    public static final int MAX_LIST_ATTRIBUTES = 3;

    /** Characters of the reason shown in the list page. */
    public static final int MAX_LIST_REASON_CHARS = 160;

    private ReviewView() {
        // Utility class
    }

    // ========================================================================
    // Rules
    // ========================================================================

    /**
     * Whether the list page may offer approve/deny buttons for this request.
     * <p>
     * Two independent conditions, both mechanical, because "the title is enough to decide" is not
     * something the review area can judge:
     * </p>
     * <ol>
     *   <li><b>the tool opted in</b> - {@link ReviewEntry#enableQuickDecision(String)} - because the
     *       tool knows whether its requests are one-line affairs;</li>
     *   <li><b>no {@link AttributeKind#CODE} attribute is present</b> - where source code or a diff
     *       is what is being approved, a title is provably not the decision basis, and a
     *       one-click approve would turn the four-eyes page into a rubber stamp. This one is not
     *       negotiable by the tool.</li>
     * </ol>
     *
     * @param entry the request
     * @return true if the list page may render the two buttons
     */
    public static boolean allowsQuickDecision(ReviewEntry entry) {
        if (entry == null || !entry.pending() || !entry.quickDecision()) {
            return false;
        }
        if (entry.shortSummary().isEmpty()) {
            return false;
        }
        for (ReviewAttribute attribute : entry.attributes()) {
            if (attribute.kind() == AttributeKind.CODE) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reduces the raw list to what the page shows: every open request, and for each tool the last
     * {@link #MAX_HISTORY_PER_TOOL} finished ones. Newest first overall.
     *
     * @param entries the store's entries (newest first)
     * @return the visible subset
     */
    public static List<ReviewEntry> visibleEntries(List<ReviewEntry> entries) {
        List<ReviewEntry> visible = new ArrayList<>();
        Map<String, Integer> historyByTool = new LinkedHashMap<>();
        for (ReviewEntry entry : entries) {
            if (entry.open()) {
                visible.add(entry);
                continue;
            }
            int shown = historyByTool.merge(entry.toolId(), 1, Integer::sum);
            if (shown <= MAX_HISTORY_PER_TOOL) {
                visible.add(entry);
            }
        }
        return visible;
    }

    // ========================================================================
    // List page
    // ========================================================================

    /**
     * One row of the list page.
     *
     * @param entry the request
     * @return the row model
     */
    public static Map<String, Object> row(ReviewEntry entry) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", entry.id());
        row.put("toolId", entry.toolId());
        row.put("toolTitle", entry.toolTitle());
        row.put("title", entry.title());
        row.put("shortSummary", entry.shortSummary());
        row.put("reason", ReviewAttributes.abbreviate(entry.reason(), MAX_LIST_REASON_CHARS));
        row.put("status", entry.status().display());
        row.put("open", entry.open());
        row.put("pending", entry.pending());
        row.put("terminal", entry.status().terminal());
        row.put("submittedAt", entry.submittedAt());
        row.put("decidedAt", entry.decidedAt());
        row.put("comment", entry.comment());
        row.put("renewCount", entry.renewCount());
        // Countdown only while the request is actually open, so a decided or expired request never
        // shows a running timer.
        row.put("expiresAt", entry.pending() ? entry.expiresAtMillis() : 0L);
        ReviewResponseEntry response = entry.response();
        row.put("ok", response == null ? null : (Boolean) response.ok());
        row.put("resultSummary", response == null ? "" : response.summary());
        row.put("quickDecision", allowsQuickDecision(entry));
        row.put("attributes", rowAttributes(entry));
        row.put("persistence", entry.persistence().name());
        row.put("runDir", entry.runDir() == null ? "" : entry.runDir());
        return row;
    }

    /**
     * The attributes the list page shows: the first few, as single-line values, because the list is
     * for finding the request that needs a decision, not for reading it.
     *
     * @param entry the request
     * @return list of {@code {key, label, kind, value}} maps
     */
    private static List<Map<String, Object>> rowAttributes(ReviewEntry entry) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ReviewAttribute attribute : entry.attributes()) {
            if (list.size() >= MAX_LIST_ATTRIBUTES) {
                break;
            }
            if (attribute.kind() == AttributeKind.CODE) {
                continue;                       // a source block belongs to the detail page
            }
            // The budget counts what is SHOWN. Skipping a code block must not consume a slot,
            // otherwise a request whose first attribute is its source loses one of its three
            // readable fields in the list - the tool did declare three displayable attributes.
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", attribute.key());
            item.put("label", attribute.label());
            item.put("kind", attribute.kind().name());
            item.put("value", firstLine(attribute.value(), 120));
            item.put("checks", checkRows(List.of(attribute)));
            list.add(item);
        }
        return list;
    }

    // ========================================================================
    // Detail page
    // ========================================================================

    /**
     * The detail page frame: everything a reviewer needs to decide, and - once the work reported
     * back - what became of the approval.
     *
     * @param entry the request
     * @return the detail model
     */
    public static Map<String, Object> detail(ReviewEntry entry) {
        Map<String, Object> vm = new LinkedHashMap<>();
        vm.put("id", entry.id());
        vm.put("toolId", entry.toolId());
        vm.put("toolTitle", entry.toolTitle());
        vm.put("title", entry.title());
        vm.put("status", entry.status().display());
        vm.put("open", entry.open());
        vm.put("pending", entry.pending());
        vm.put("terminal", entry.status().terminal());
        vm.put("submittedAt", entry.submittedAt());
        vm.put("decidedAt", entry.decidedAt());
        vm.put("comment", entry.comment());
        vm.put("reason", entry.reason());
        vm.put("waitSeconds", entry.waitSeconds());
        vm.put("expiresAt", entry.pending() ? entry.expiresAtMillis() : 0L);
        vm.put("remainingSeconds", entry.pending() ? entry.remainingSeconds() : 0L);
        vm.put("renewCount", entry.renewCount());
        vm.put("renewNote", entry.renewNote());
        vm.put("quickDecision", allowsQuickDecision(entry));
        vm.put("shortSummary", entry.shortSummary());
        vm.put("attributes", attributeViews(entry.attributes()));

        // Artifacts: the page may only promise what the tool actually keeps (P8/P6).
        vm.put("persistence", entry.persistence().name());
        vm.put("runDir", entry.runDir() == null ? "" : entry.runDir());
        vm.put("artifactsOnDisk", entry.persistence() == ReviewPersistenceType.FILESYSTEM);
        vm.put("memoryOnlyNote", entry.persistence() == ReviewPersistenceType.MEMORY
                ? "This request and everything it captured live in memory only - a server restart"
                        + " discards them."
                : "");

        ReviewResponseEntry response = entry.response();
        if (response == null) {
            vm.put("response", null);
            vm.put("unmetChecks", 0);
        } else {
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("status", response.status().display());
            rm.put("ok", response.ok());
            rm.put("summary", response.summary());
            rm.put("comment", response.comment());
            rm.put("durationMs", response.durationMs());
            rm.put("attributes", attributeViews(response.attributes()));
            rm.put("unmetChecks", response.unmetChecks());
            vm.put("response", rm);
            vm.put("unmetChecks", response.unmetChecks());
        }
        return vm;
    }

    /**
     * Kind-aware attribute models for the detail table. The template branches on {@code kind}; every
     * kind brings the same fields so that a new tool cannot produce a half-populated row.
     *
     * @param attributes the attributes
     * @return list of maps, in the order the tool declared
     */
    public static List<Map<String, Object>> attributeViews(List<ReviewAttribute> attributes) {
        List<Map<String, Object>> views = new ArrayList<>();
        for (ReviewAttribute attribute : attributes) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("key", attribute.key());
            view.put("label", attribute.label());
            view.put("kind", attribute.kind().name());
            view.put("code", attribute.kind() == AttributeKind.CODE);
            view.put("value", attribute.value());
            view.put("truncated", attribute.truncated());
            view.put("valueBytes", attribute.valueBytes());
            view.put("artifact", attribute.artifact() == null ? "" : attribute.artifact());
            view.put("note", attribute.note());
            view.put("checks", checkRows(List.of(attribute)));
            views.add(view);
        }
        return views;
    }

    /**
     * The rows of every {@link AttributeKind#CHECKLIST} attribute in the given list, flattened with
     * the attribute they belong to.
     *
     * @param attributes the attributes to scan
     * @return list of {@code {attribute, expectation, met, detail}} maps
     */
    public static List<Map<String, Object>> checkRows(List<ReviewAttribute> attributes) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ReviewAttribute attribute : attributes) {
            if (attribute.checks().isEmpty()) {
                continue;
            }
            for (ReviewCheck check : attribute.checks()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("attribute", attribute.key());
                row.put("expectation", check.expectation());
                row.put("met", check.met());
                row.put("detail", check.detail());
                rows.add(row);
            }
        }
        return rows;
    }

    // ========================================================================
    // Small helpers
    // ========================================================================

    /**
     * First line of a value, shortened - what a list row can show without growing.
     *
     * @param value the value
     * @param max   maximum characters
     * @return one line, never null
     */
    static String firstLine(String value, int max) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        int newline = value.indexOf('\n');
        String line = (newline < 0) ? value : value.substring(0, newline);
        return line.length() <= max ? line : line.substring(0, max) + "…";
    }
}
