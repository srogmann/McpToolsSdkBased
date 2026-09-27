package org.rogmann.mcp2sdk.review;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The one place that knows how big a review attribute may be and how an oversized value is
 * shortened, plus the factories a tool uses to build attributes.
 * <p>
 * A request travels three short distances: into the browser of a human, into the review list that
 * a human scans between other requests, and (as a log excerpt) possibly into a tool result that a
 * model reads. Bounding it here means a chatty tool cannot flood any of the three, while
 * {@link ReviewAttribute#truncated()} and {@link ReviewAttribute#artifact()} keep the omission
 * visible and reachable. Truncation is a display decision, never a decision about the content.
 * </p>
 * <p>
 * The constants are public and documented on purpose: {@code docs/js/review.md} quotes them and
 * {@code ReviewAttributesTest} pins the documentation against them (the pattern of
 * {@code JsSearchLimitConsistencyTest}) - a limit that silently drifted from its documentation is
 * worse than no limit at all.
 * </p>
 */
public final class ReviewAttributes {

    /** Maximum number of attributes per request. More than this is a dump, not a decision aid. */
    public static final int MAX_ATTRIBUTES = 12;

    /** Maximum characters of a single attribute value shown in the UI. */
    public static final int MAX_VALUE_CHARS = 4000;

    /**
     * Maximum characters of a {@link AttributeKind#CODE} value shown in the UI.
     * <p>
     * Deliberately far above {@link #MAX_VALUE_CHARS}: source code, a patch or a report <em>is</em>
     * the subject of the approval, so shortening it at 4000 characters would hide part of what the
     * reviewer is asked to approve - exactly what this workflow must never do. The limit still
     * bounds what a browser (and, transitively, a model context) receives from a pathological
     * input; the complete value is always the artifact file.
     * </p>
     */
    public static final int MAX_CODE_CHARS = 200_000;

    /** Characters kept at the beginning of a shortened value. */
    public static final int PREVIEW_HEAD_CHARS = 3000;

    /** Characters kept at the end of a shortened value (where a failure usually shows up). */
    public static final int PREVIEW_TAIL_CHARS = 1000;

    /** Maximum characters of the request title (one line, the quick-decision text). */
    public static final int MAX_TITLE_CHARS = 120;

    /** Maximum characters of the free-text reason. Same bound the Python workflow uses today. */
    public static final int MAX_REASON_CHARS = 2000;

    /** Maximum characters of the human-readable label of one attribute. */
    public static final int MAX_LABEL_CHARS = 80;

    /** Maximum characters of one declared expectation. */
    public static final int MAX_EXPECT_CHARS = 256;

    /** Maximum length of an artifact path. */
    public static final int MAX_ARTIFACT_CHARS = 512;

    /** Maximum number of rows of one {@link AttributeKind#CHECKLIST} attribute. */
    public static final int MAX_CHECK_ROWS = 32;

    private ReviewAttributes() {
        // Utility class
    }

    // ========================================================================
    // Factories (what a tool calls)
    // ========================================================================

    /**
     * A plain text attribute (reason, summary).
     *
     * @param key   machine-readable name
     * @param label heading shown in the UI
     * @param value the text
     * @return the attribute, shortened if necessary
     */
    public static ReviewAttribute text(String key, String label, String value) {
        return value(key, label, AttributeKind.TEXT, value, null);
    }

    /**
     * A multi-line source-like attribute (script snapshot, patch). Its presence disables the quick
     * decision buttons in the list page - see {@link AttributeKind#CODE}.
     *
     * @param key      machine-readable name
     * @param label    heading shown in the UI
     * @param value    the source
     * @param artifact path of the file holding the complete source, may be null
     * @return the attribute, shortened if necessary
     */
    public static ReviewAttribute code(String key, String label, String value, String artifact) {
        return value(key, label, AttributeKind.CODE, value, artifact, MAX_CODE_CHARS);
    }

    /**
     * A project-relative path.
     *
     * @param key   machine-readable name
     * @param label heading shown in the UI
     * @param value the path
     * @return the attribute
     */
    public static ReviewAttribute path(String key, String label, String value) {
        return value(key, label, AttributeKind.PATH, value, null);
    }

    /**
     * A content digest; the UI explains that the approval binds to exactly this content.
     *
     * @param key   machine-readable name
     * @param label heading shown in the UI
     * @param value lowercase hex digest
     * @return the attribute
     */
    public static ReviewAttribute hash(String key, String label, String value) {
        return value(key, label, AttributeKind.HASH, value, null);
    }

    /**
     * Several short values, one per line (arguments, write targets, expectations).
     *
     * @param key   machine-readable name
     * @param label heading shown in the UI
     * @param items the items; null entries and blanks are dropped
     * @return the attribute; an empty list renders as {@code (none)}
     */
    public static ReviewAttribute list(String key, String label, List<String> items) {
        List<String> kept = new ArrayList<>();
        if (items != null) {
            for (String item : items) {
                if (item != null && !item.isBlank()) {
                    kept.add(item.trim());
                }
            }
        }
        if (kept.isEmpty()) {
            return new ReviewAttribute(key, label, AttributeKind.LIST, "(none)",
                    false, 0L, null, "", List.of());
        }
        return value(key, label, AttributeKind.LIST, String.join("\n", kept), null);
    }

    /**
     * A number (exit code, byte count).
     *
     * @param key   machine-readable name
     * @param label heading shown in the UI
     * @param value the number
     * @return the attribute
     */
    public static ReviewAttribute number(String key, String label, long value) {
        return new ReviewAttribute(key, label, AttributeKind.NUMBER, Long.toString(value),
                false, -1L, null, "", List.of());
    }

    /**
     * A duration rendered with a unit.
     *
     * @param key   machine-readable name
     * @param label heading shown in the UI
     * @param value the duration
     * @param unit  unit label ({@code "s"}, {@code "ms"})
     * @return the attribute
     */
    public static ReviewAttribute duration(String key, String label, long value, String unit) {
        String suffix = (unit == null || unit.isBlank()) ? "" : " " + unit;
        return new ReviewAttribute(key, label, AttributeKind.DURATION, value + suffix,
                false, -1L, null, "", List.of());
    }

    /**
     * A table of promised checks and their outcome. The text value is only a fallback for places
     * that render plain text ("2 of 3 met").
     *
     * @param key    machine-readable name
     * @param label  heading shown in the UI
     * @param checks the rows, at most {@link #MAX_CHECK_ROWS}
     * @return the attribute
     */
    public static ReviewAttribute checklist(String key, String label, List<ReviewCheck> checks) {
        if (checks == null || checks.isEmpty()) {
            throw new IllegalArgumentException("Checklist attribute '" + key
                    + "' needs at least one check row.");
        }
        List<ReviewCheck> rows = checks.size() > MAX_CHECK_ROWS
                ? List.copyOf(checks.subList(0, MAX_CHECK_ROWS))
                : List.copyOf(checks);
        int met = 0;
        for (ReviewCheck check : rows) {
            if (check.met()) {
                met++;
            }
        }
        return new ReviewAttribute(key, label, AttributeKind.CHECKLIST,
                met + " of " + rows.size() + " met",
                checks.size() > MAX_CHECK_ROWS, checks.size(), null, "", rows);
    }

    /**
     * A log excerpt: bounded preview plus the counters of the complete output and the path to it.
     *
     * @param key       machine-readable name
     * @param label     heading shown in the UI
     * @param preview   the preview the tool produced (may itself be complete)
     * @param truncated true if the preview is smaller than the complete output
     * @param bytes     bytes of the complete output
     * @param lines     lines of the complete output
     * @param logFile   project-relative path of the complete log
     * @return the attribute with the counters as note
     */
    public static ReviewAttribute log(String key, String label, String preview, boolean truncated,
            long bytes, long lines, String logFile) {
        ReviewAttribute base = value(key, label, AttributeKind.LOG, preview, logFile);
        String note = lines + " lines, " + bytes + " bytes"
                + (truncated || base.truncated() ? ", preview only" : "")
                + (logFile == null || logFile.isEmpty() ? "" : " - complete output: " + logFile);
        return new ReviewAttribute(key, label, AttributeKind.LOG, base.value(),
                truncated || base.truncated(), Math.max(base.valueBytes(), bytes), logFile,
                sanitize(note, 300), List.of());
    }

    // ========================================================================
    // Normalization
    // ========================================================================

    /**
     * Builds one attribute, shortening an oversized value and recording its real size.
     *
     * @param key      machine-readable name
     * @param label    heading shown in the UI
     * @param kind     presentation kind
     * @param rawValue the complete value
     * @param artifact path holding the complete value, may be null
     * @return the attribute
     */
    public static ReviewAttribute value(String key, String label, AttributeKind kind,
            String rawValue, String artifact) {
        return value(key, label, kind, rawValue, artifact, MAX_VALUE_CHARS);
    }

    /**
     * Same as {@link #value(String, String, AttributeKind, String, String)} with an explicit
     * length budget - used by {@link #code(String, String, String, String)}, where the value is
     * the subject of the approval and may not be cut at the generic limit.
     *
     * @param key      machine-readable name
     * @param label    heading shown in the UI
     * @param kind     presentation kind
     * @param rawValue the complete value
     * @param artifact path holding the complete value, may be null
     * @param max      maximum characters to show
     * @return the attribute
     */
    public static ReviewAttribute value(String key, String label, AttributeKind kind,
            String rawValue, String artifact, int max) {
        String raw = (rawValue == null) ? "" : rawValue;
        String shown = abbreviate(raw, max);
        boolean shortened = shown.length() < raw.length();
        return new ReviewAttribute(key, label, kind, shown, shortened, raw.length(),
                sanitize(artifact, MAX_ARTIFACT_CHARS), "", List.of());
    }

    /**
     * Shortens a value to its first and last characters, saying how much is missing.
     *
     * @param value the complete value
     * @param max   maximum length of the result
     * @return the value itself when it fits, otherwise a head+tail excerpt with a marker
     */
    public static String abbreviate(String value, int max) {
        if (value == null) {
            return "";
        }
        if (value.length() <= max) {
            return value;
        }
        String marker = "\n... %d characters omitted, the complete value is with the request ...\n";
        int markerBudget = String.format(marker, 0).length();
        int head = Math.min(PREVIEW_HEAD_CHARS, Math.max(1, max - markerBudget - 1) / 2);
        int tail = Math.min(PREVIEW_TAIL_CHARS, Math.max(1, max - markerBudget - head));
        int missing = value.length() - head - tail;
        if (missing <= 0) {
            return value.substring(0, max);
        }
        String shortened = value.substring(0, head)
                + String.format(marker, missing)
                + value.substring(value.length() - tail);
        // The marker is part of the budget; if the number of digits pushed us over, trim the tail.
        if (shortened.length() > max) {
            return shortened.substring(0, max);
        }
        return shortened;
    }

    /**
     * Validates a tool's attribute list: bounded size, unique keys, immutable order.
     *
     * @param attributes the attributes as the tool declared them (may be null)
     * @return an immutable list, never null
     * @throws IllegalArgumentException if the list is empty, oversized, or a key is used twice
     */
    public static List<ReviewAttribute> normalize(List<ReviewAttribute> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            throw new IllegalArgumentException(
                    "A review request needs at least one attribute - a request without visible"
                            + " facts cannot be reviewed.");
        }
        if (attributes.size() > MAX_ATTRIBUTES) {
            throw new IllegalArgumentException("A review request carries at most " + MAX_ATTRIBUTES
                    + " attributes, got " + attributes.size() + ". A reviewer reads a decision aid,"
                    + " not a data dump: put the bulk into an artifact and reference it.");
        }
        Set<String> keys = new HashSet<>();
        for (ReviewAttribute attribute : attributes) {
            if (attribute == null) {
                throw new IllegalArgumentException("Attribute list contains a null entry.");
            }
            if (!keys.add(attribute.key())) {
                throw new IllegalArgumentException("Attribute key '" + attribute.key()
                        + "' is used twice; keys must be unique inside a request.");
            }
        }
        return List.copyOf(attributes);
    }

    /**
     * Trims and shortens free text for titles, reasons, labels and notes.
     *
     * @param value the text (may be null)
     * @param max   maximum length
     * @return the text, never null
     */
    public static String sanitize(String value, int max) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
