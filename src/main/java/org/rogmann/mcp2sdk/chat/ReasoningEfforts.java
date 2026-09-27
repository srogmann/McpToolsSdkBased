package org.rogmann.mcp2sdk.chat;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translation of the Web UI's thinking-budget into a {@code reasoning_effort} label, taking the
 * vocabulary of the model's chat template into account.
 *
 * <p>The Web UI (chat.service.ts) maps the user's effort choice onto a token budget
 * ({@code thinking_budget_tokens}) and never forwards {@code reasoning_effort} itself, so the proxy
 * has to reconstruct the label. Models disagree on that vocabulary: DeepSeek-V4 branches on
 * {@code high}/{@code max}, while Qwen3.8-Flash-Next only accepts {@code xhigh} (default),
 * {@code medium} and {@code low} and rejects everything else with</p>
 * <pre>Unexpected reasoning effort high. Supported types are xhigh (default), medium, and low.</pre>
 *
 * <p>{@link #parseTemplateEfforts(String)} reads the labels a chat template actually accepts out of
 * that template, {@link #effortForBudget(int, Map, List)} maps a budget onto one of them. All
 * methods are pure functions without I/O so they can be verified without a running server.</p>
 */
public final class ReasoningEfforts {

    /**
     * Known reasoning-effort labels in ascending order of thinking depth. Doubles as the closed
     * vocabulary of {@link #parseTemplateEfforts(String)} - a template can only contribute labels
     * from this list - and as the ranking used to pick the closest substitute when a template does
     * not accept the preferred label.
     */
    static final List<String> EFFORT_ORDER = List.of("minimal", "low", "medium", "high", "xhigh", "max");

    /**
     * Names of the chat-template variables that carry the reasoning effort, matched case-insensitively.
     * Only labels attached to these variables are considered (see {@link #EFFORT_PATTERN}); modern
     * templates often work with a local copy, hence {@code resolved_reasoning_effort}.
     */
    private static final Set<String> EFFORT_VARIABLES = Set.of("reasoning_effort", "resolved_reasoning_effort");

    /**
     * Matches the effort labels a chat template branches on. Recognized forms (the effort variable is
     * captured so unrelated text can be filtered out):
     * <ul>
     *   <li>membership test: {@code resolved_reasoning_effort not in ('xhigh', 'medium', 'low')}</li>
     *   <li>jinja default filter: {@code reasoning_effort|default('xhigh')}</li>
     *   <li>string comparison: {@code reasoning_effort == 'max'}</li>
     * </ul>
     * The alternatives require straight quotes on purpose: documentation dumps embedded in a template
     * carry HTML-escaped quotes ({@code &amp;#x27;}) and therefore never match.
     */
    private static final Pattern EFFORT_PATTERN = Pattern.compile(
            "\\b([A-Za-z_][A-Za-z0-9_]*)\\s*(?:"
                    + "(?:not\\s+in|in)\\s*\\(([^)]*)\\)"      // group 2: membership label list
                    + "|\\|\\s*default\\s*\\(\\s*'([^']*)'"    // group 3: default filter argument
                    + "|==\\s*'([^']*)'"                       // group 4: comparison operand
                    + ")");

    /**
     * Effort label per thinking-budget level: the vocabulary of the llama.cpp path, which is also
     * what is sent when the chat template declares no labels of its own.
     * The budgets match {@code REASONING_EFFORT_TOKENS} in the Web UI (chat.service.ts):
     * low=512, medium=2048, high=8192, max=-1/unlimited - the UI omits the budget field for max,
     * so an absent budget resolves to -1 here.
     */
    public static final Map<Integer, String> EFFORT_BY_BUDGET = Map.of(
            512, "low",
            2048, "medium",
            8192, "high",
            -1, "max");

    /**
     * Preferred label per thinking-budget level for a vLLM (DeepSeek OpenAI-format) backend, which
     * conventionally knows {@code low}, {@code high} and {@code max} but has no {@code medium}, so
     * those levels are raised to {@code high} (per convention only {@code max} reaches {@code max}).
     */
    public static final Map<Integer, String> VLLM_EFFORT_BY_BUDGET = Map.of(
            512, "low",
            2048, "high",
            8192, "high",
            -1, "max");

    /** Utility class */
    private ReasoningEfforts() {
    }

    /**
     * Extracts the reasoning-effort labels a chat template branches on, e.g. {@code [high, max]} for
     * DeepSeek-V4 or {@code [xhigh, medium, low]} for Qwen3.8-Flash-Next. Recognized are membership
     * tests, the jinja default filter and string comparisons (see {@link #EFFORT_PATTERN}); only
     * conditions on the variables named in {@link #EFFORT_VARIABLES} count, so unrelated text is
     * ignored - HTML-documentation dumps embedded in a template contribute nothing.
     *
     * <p>Labels are returned in the order the template mentions them, de-duplicated and limited to
     * the known vocabulary ({@link #EFFORT_ORDER}). An empty list means the template declares no
     * effort vocabulary, in which case {@link #effortForBudget} falls back to the backend labels.</p>
     *
     * @param template chat template source (may be null)
     * @return declared labels in template order, de-duplicated; empty if the template names none
     */
    public static List<String> parseTemplateEfforts(String template) {
        if (template == null || template.isBlank()) {
            return List.of();
        }
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = EFFORT_PATTERN.matcher(template);
        while (matcher.find()) {
            if (!EFFORT_VARIABLES.contains(matcher.group(1).toLowerCase(Locale.ROOT))) {
                continue;
            }
            String labels = matcher.group(2) != null ? matcher.group(2)
                    : matcher.group(3) != null ? matcher.group(3)
                    : matcher.group(4);
            for (String label : labels.split(",")) {
                String candidate = label.trim().replace("'", "").replace("\"", "");
                // Only the known vocabulary counts, so words from unrelated comparisons
                // (e.g. message['role'] == 'user') can never become an effort label.
                if (EFFORT_ORDER.contains(candidate)) {
                    found.add(candidate);
                }
            }
        }
        return List.copyOf(found);
    }

    /**
     * Resolves the effort label for a token budget against the labels the model's chat template
     * declares.
     *
     * <p>Without template information the backend's convention label is returned, preserving the
     * previous hard-coded behaviour. With template information a label the template rejects is
     * substituted: the level the Web UI offered (see {@link #EFFORT_BY_BUDGET}) wins whenever the
     * template accepts it - a backend raising such as DeepSeek's {@code medium -> high} only exists
     * for templates without that level - otherwise the accepted label closest to the convention
     * label is used, ties decided in template order.</p>
     *
     * @param budget             raw budget value ({@code -1} when the UI sent none)
     * @param conventionByBudget label per budget level of the backend vocabulary
     * @param declared           labels declared by the chat template, empty if the template names none
     * @return the effort label, or {@code null} if the budget is not a known level
     */
    public static String effortForBudget(int budget, Map<Integer, String> conventionByBudget,
                                         List<String> declared) {
        String levelLabel = EFFORT_BY_BUDGET.get(budget);        // the level the Web UI offered
        String conventionLabel = conventionByBudget.get(budget); // what this backend prefers for it
        if (conventionLabel == null && levelLabel == null) {
            return null; // not a budget level the Web UI produces
        }
        if (declared == null || declared.isEmpty()) {
            return (conventionLabel != null) ? conventionLabel : levelLabel;
        }
        // The level the UI offered outranks the backend convention, but only if the template has it.
        if (levelLabel != null && declared.contains(levelLabel)) {
            return levelLabel;
        }
        return closestEffort((conventionLabel != null) ? conventionLabel : levelLabel, declared);
    }

    /**
     * Picks the accepted label closest to the preferred effort level, ranked by
     * {@link #EFFORT_ORDER}. The preferred label is returned as-is when accepted. A declared label
     * outside that known order is ranked behind all known ones (template order as tie-breaker), so a
     * known preferred level is never silently mapped onto an unknown word while a template with an
     * exotic vocabulary still receives a label it declared.
     *
     * @param preferred the desired label (e.g. {@code high})
     * @param accepted  labels the chat template accepts, in template order
     * @return the label to send
     */
    static String closestEffort(String preferred, List<String> accepted) {
        if (accepted.contains(preferred)) {
            return preferred;
        }
        int preferredRank = EFFORT_ORDER.indexOf(preferred);
        if (preferredRank < 0) {
            // Unknown preferred level: fall back to the label the template names first.
            return accepted.get(0);
        }
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < accepted.size(); i++) {
            int rank = EFFORT_ORDER.indexOf(accepted.get(i));
            // A label outside the known order ranks behind all known ones, template order as tie-break.
            int distance = (rank < 0) ? EFFORT_ORDER.size() + i : Math.abs(rank - preferredRank);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = accepted.get(i);
            }
        }
        return best != null ? best : preferred;
    }
}
