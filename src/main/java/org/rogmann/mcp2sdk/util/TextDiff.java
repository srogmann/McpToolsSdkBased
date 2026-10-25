package org.rogmann.mcp2sdk.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Line diff with a unified-format rendering: the patch that a review request shows next to the
 * digests it binds to.
 *
 * <p>
 * <b>Why this exists as its own class.</b> A reviewer who is asked "may Maven run against this
 * pom.xml?" has to see <em>what changed</em>, not two hashes - approving a digest alone is
 * approving blind, and blind approval is the failure mode the review workflow exists to prevent.
 * The capability is not Maven-specific either: a Python script, an {@code application.yml} or a
 * replaced XML file all want the same view, so this lives in {@code util} next to
 * {@link BoundedText} rather than in {@code review} or {@code m2}, where every next user would
 * have to reach around a package boundary.
 * </p>
 *
 * <p>
 * <b>Scope.</b> The output is for <em>reading</em>: it is shaped like {@code diff -u} (hunk
 * headers, context lines, {@code -}/{@code +}) so it looks familiar, but no
 * {@code patch(1)}/{@code git apply} round-trip is promised - a review page has to be correct
 * about lines, not byte-reproducible. Line endings are normalized to {@code \n} before comparing,
 * so a CRLF conversion shows as "every line changed", which is honest and easy to read.
 * </p>
 *
 * <p>
 * <b>Bounded by design.</b> A diff that carries an approval cannot be unbounded:
 * {@link Options#maxHunks()} and {@link Options#maxPatchLines()} cap the rendering, and a
 * truncated result says so in {@link Result#note()} - the rule {@link BoundedText} follows, where
 * dropped text is counted and never silently missing. The comparison is capped too (see
 * {@link #MAX_LCS_CELLS}): past that size the changed middle section is reported as a replacement
 * block instead of spending quadratic time looking for the lines it shares.
 * </p>
 */
public final class TextDiff {

    /**
     * Upper bound for the quadratic LCS table ({@code n * m} cell pairs). Beyond it the changed
     * middle section is rendered as "old lines out, new lines in", which costs nothing and stays
     * truthful - only less compact than an exact diff would be.
     */
    static final int MAX_LCS_CELLS = 1_000_000;

    /** Kind marker of {@code diff -u}: unchanged. */
    private static final char EQUAL = ' ';
    /** Kind marker of {@code diff -u}: removed. */
    private static final char DEL = '-';
    /** Kind marker of {@code diff -u}: added. */
    private static final char ADD = '+';

    /**
     * Rendering limits of one diff.
     *
     * @param contextLines  unchanged lines kept around each change (unified-diff style)
     * @param maxHunks      maximum number of hunks to render
     * @param maxPatchLines maximum number of rendered patch lines, headers included
     */
    public record Options(int contextLines, int maxHunks, int maxPatchLines) {

        /** The defaults used for a review request: readable for one screen of changes. */
        public static Options defaults() {
            return new Options(3, 40, 2000);
        }
    }

    /**
     * Outcome of a comparison.
     *
     * @param patch     unified-format text, empty when there is nothing to show
     * @param added     lines present only in the new text (counted over the whole diff)
     * @param removed   lines present only in the old text (counted over the whole diff)
     * @param identical true if both sides have the same lines (then {@code patch} is empty)
     * @param truncated true if {@link Options} dropped hunks or lines from the rendering
     * @param note      what was dropped or approximated, null when nothing was
     */
    public record Result(String patch, int added, int removed, boolean identical,
                         boolean truncated, String note) {

        /** @return true if there is anything to render */
        public boolean hasPatch() {
            return !patch.isEmpty();
        }
    }

    /** One line of the edit script; {@code ai}/{@code bi} are 0-based positions in each file. */
    private record Op(char kind, String text, int ai, int bi) {
    }

    private TextDiff() {
    }

    /**
     * Diffs two texts with the default limits.
     *
     * @param oldText previous content, null for "the file did not exist"
     * @param newText current content, null for "the file is gone"
     * @param oldLabel label of the left side (usually the path or {@code <path> (baseline)})
     * @param newLabel label of the right side
     * @return the result, never null
     */
    public static Result unified(String oldText, String newText, String oldLabel, String newLabel) {
        return unified(oldText, newText, oldLabel, newLabel, Options.defaults());
    }

    /**
     * Diffs two texts.
     *
     * @param oldText previous content, null for "the file did not exist"
     * @param newText current content, null for "the file is gone"
     * @param oldLabel label of the left side
     * @param newLabel label of the right side
     * @param options  rendering limits, null for {@link Options#defaults()}
     * @return the result, never null
     */
    public static Result unified(String oldText, String newText, String oldLabel,
                                 String newLabel, Options options) {
        Options opts = (options == null) ? Options.defaults() : options;
        List<Op> ops = new ArrayList<>();
        boolean approximated = editScript(splitLines(oldText), splitLines(newText), ops);

        int added = 0;
        int removed = 0;
        for (Op op : ops) {
            if (op.kind() == ADD) {
                added++;
            } else if (op.kind() == DEL) {
                removed++;
            }
        }
        boolean identical = (added == 0 && removed == 0);

        String patch = identical ? ""
                : render(ops, oldText == null ? "/dev/null" : oldLabel,
                        newText == null ? "/dev/null" : newLabel, opts);

        List<String> notes = new ArrayList<>();
        if (approximated) {
            notes.add("content too large for an exact line comparison - the changed section is "
                    + "rendered as a replacement");
        }
        if (!identical && isTruncated(ops, opts)) {
            notes.add("patch limited to " + opts.maxHunks() + " hunks / "
                    + opts.maxPatchLines() + " lines, the rest is not shown");
        }
        return new Result(patch, added, removed, identical, !notes.isEmpty(),
                notes.isEmpty() ? null : String.join("; ", notes));
    }

    /**
     * Counts the differing lines of two texts without rendering anything.
     *
     * @param oldText previous content or null
     * @param newText current content or null
     * @return {@code int[]{removed, added}}
     */
    public static int[] countChanges(String oldText, String newText) {
        List<Op> ops = new ArrayList<>();
        editScript(splitLines(oldText), splitLines(newText), ops);
        int removed = 0;
        int added = 0;
        for (Op op : ops) {
            if (op.kind() == DEL) {
                removed++;
            } else if (op.kind() == ADD) {
                added++;
            }
        }
        return new int[]{removed, added};
    }

    // ========================================================================
    // edit script
    // ========================================================================

    /**
     * Builds the edit script of two line arrays into {@code ops}.
     *
     * @return true if the changed middle section was too large for an exact comparison
     */
    private static boolean editScript(String[] a, String[] b, List<Op> ops) {
        int n = a.length;
        int m = b.length;

        int prefix = 0;
        while (prefix < n && prefix < m && a[prefix].equals(b[prefix])) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < n - prefix && suffix < m - prefix
                && a[n - 1 - suffix].equals(b[m - 1 - suffix])) {
            suffix++;
        }

        for (int i = 0; i < prefix; i++) {
            ops.add(new Op(EQUAL, a[i], i, i));
        }

        int n1 = n - prefix - suffix;
        int m1 = m - prefix - suffix;
        boolean approximated = false;
        if ((long) n1 * (long) m1 > MAX_LCS_CELLS) {
            approximated = true;
            for (int i = 0; i < n1; i++) {
                ops.add(new Op(DEL, a[prefix + i], prefix + i, prefix));
            }
            for (int j = 0; j < m1; j++) {
                ops.add(new Op(ADD, b[prefix + j], n - suffix, prefix + j));
            }
        } else {
            lcsOps(a, b, prefix, n1, m1, ops);
        }

        for (int i = 0; i < suffix; i++) {
            ops.add(new Op(EQUAL, a[n - suffix + i], n - suffix + i, m - suffix + i));
        }
        return approximated;
    }

    /**
     * Exact LCS over the changed middle section: {@code a[off .. off+n1)} against
     * {@code b[off .. off+m1)}. The common prefix and suffix are already emitted, so the two
     * windows have nothing at their ends in common and the table stays small in practice.
     */
    private static void lcsOps(String[] a, String[] b, int off, int n1, int m1, List<Op> ops) {
        int[][] len = new int[n1 + 1][m1 + 1];
        for (int i = n1 - 1; i >= 0; i--) {
            for (int j = m1 - 1; j >= 0; j--) {
                if (a[off + i].equals(b[off + j])) {
                    len[i][j] = len[i + 1][j + 1] + 1;
                } else {
                    len[i][j] = Math.max(len[i + 1][j], len[i][j + 1]);
                }
            }
        }
        int i = 0;
        int j = 0;
        while (i < n1 && j < m1) {
            if (a[off + i].equals(b[off + j])) {
                ops.add(new Op(EQUAL, a[off + i], off + i, off + j));
                i++;
                j++;
            } else if (len[i + 1][j] >= len[i][j + 1]) {
                ops.add(new Op(DEL, a[off + i], off + i, off + j));
                i++;
            } else {
                ops.add(new Op(ADD, b[off + j], off + i, off + j));
                j++;
            }
        }
        while (i < n1) {
            ops.add(new Op(DEL, a[off + i], off + i, off + j));
            i++;
        }
        while (j < m1) {
            ops.add(new Op(ADD, b[off + j], off + i, off + j));
            j++;
        }
    }

    // ========================================================================
    // hunking and rendering
    // ========================================================================

    /**
     * Groups changed operations into hunks, merging ranges whose context would touch.
     *
     * @return list of {@code int[]{firstOpIndex, lastOpIndexInclusive}}
     */
    private static List<int[]> hunkRanges(List<Op> ops, int context) {
        List<int[]> ranges = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) {
            if (ops.get(i).kind() != EQUAL) {
                int start = Math.max(0, i - context);
                int end = Math.min(ops.size() - 1, i + context);
                if (!ranges.isEmpty() && start <= ranges.get(ranges.size() - 1)[1] + 1) {
                    ranges.get(ranges.size() - 1)[1] = end;
                } else {
                    ranges.add(new int[]{start, end});
                }
            }
        }
        return ranges;
    }

    /** True if the limits of {@code opts} would not show every hunk. */
    private static boolean isTruncated(List<Op> ops, Options opts) {
        List<int[]> hunks = hunkRanges(ops, Math.max(0, opts.contextLines()));
        if (hunks.size() > opts.maxHunks()) {
            return true;
        }
        int lines = 2; // the --- / +++ header pair
        for (int[] range : hunks) {
            lines += 1 + (range[1] - range[0] + 1);
        }
        return lines > opts.maxPatchLines();
    }

    /** Renders the header plus every hunk that fits into the limits. */
    private static String render(List<Op> ops, String oldLabel, String newLabel, Options opts) {
        List<int[]> hunks = hunkRanges(ops, Math.max(0, opts.contextLines()));
        StringBuilder out = new StringBuilder();
        out.append("--- ").append(oldLabel).append('\n')
                .append("+++ ").append(newLabel).append('\n');

        int used = 2;
        int shown = 0;
        for (int[] range : hunks) {
            if (shown >= opts.maxHunks()) {
                out.append("... (").append(hunks.size() - shown).append(" more hunks not shown, "
                        + "limit ").append(opts.maxHunks()).append(")\n");
                break;
            }
            int budget = opts.maxPatchLines() - used;
            if (budget < 4) {
                out.append("... (line limit ").append(opts.maxPatchLines())
                        .append(" reached)\n");
                break;
            }
            StringBuilder hunk = new StringBuilder();
            renderHunk(hunk, ops, range[0], range[1], budget - 1);
            used += countLines(hunk);
            shown++;
            out.append(hunk);
        }
        return out.toString();
    }

    /**
     * Appends one hunk: the {@code @@ -a,b +c,d @@} header and its lines.
     *
     * @param lineBudget maximum number of body lines (the header is always written)
     */
    private static void renderHunk(StringBuilder out, List<Op> ops, int from, int to,
                                   int lineBudget) {
        int oldCount = 0;
        int newCount = 0;
        for (int i = from; i <= to; i++) {
            char kind = ops.get(i).kind();
            if (kind != ADD) {
                oldCount++;
            }
            if (kind != DEL) {
                newCount++;
            }
        }
        out.append("@@ -").append(hunkStart(ops, from, to, ADD)).append(',').append(oldCount)
                .append(" +").append(hunkStart(ops, from, to, DEL)).append(',').append(newCount)
                .append(" @@\n");

        int written = 0;
        for (int i = from; i <= to; i++) {
            Op op = ops.get(i);
            if (written >= lineBudget) {
                out.append("... (lines of this hunk truncated, remaining: ")
                        .append(to - i + 1).append(")\n");
                return;
            }
            out.append(op.kind()).append(op.text()).append('\n');
            written++;
        }
    }

    /**
     * First line number of a hunk in one file: the position of the first operation that exists in
     * that file, or - for a hunk that only inserts or only deletes - the position it sits at.
     *
     * @param absentKind the kind that carries no line in the requested file ({@link #ADD} for the
     *                   old side, {@link #DEL} for the new side)
     */
    private static int hunkStart(List<Op> ops, int from, int to, char absentKind) {
        for (int i = from; i <= to; i++) {
            Op op = ops.get(i);
            if (op.kind() != absentKind) {
                return ((absentKind == ADD) ? op.ai() : op.bi()) + 1;
            }
        }
        Op first = ops.get(from);
        return (absentKind == ADD) ? first.ai() : first.bi();
    }

    /** Splits a text into lines, normalizing line endings; null or empty means "no content". */
    private static String[] splitLines(String text) {
        if (text == null || text.isEmpty()) {
            return new String[0];
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.endsWith("\n")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.split("\n", -1);
    }

    private static int countLines(StringBuilder sb) {
        int c = 0;
        for (int i = 0; i < sb.length(); i++) {
            if (sb.charAt(i) == '\n') {
                c++;
            }
        }
        return c;
    }
}
