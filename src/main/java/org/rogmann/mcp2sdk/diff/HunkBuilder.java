package org.rogmann.mcp2sdk.diff;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns an edit script into hunks: groups the changed lines, adds the context around them and
 * hands every hunk to a {@link Sink}.
 *
 * <p>
 * The hunks are <em>emitted</em>, not collected: a sink can reject a hunk, and then that hunk is
 * never built into the result - which is the difference between "report the interesting change" and
 * "build 5000 hunk objects and throw 4999 away". The counts stay complete either way, because they
 * are computed from the script and from every built hunk, not from what the sink kept.
 * </p>
 *
 * <p>
 * With a {@link ProseAnalysis} a hunk also learns whether it is only re-wrapped (same words, other
 * line breaks) and which words changed. That analysis runs per change <em>block</em>, never over the
 * whole file: a word comparison across a whole document aligns common words ("the", "and") across
 * sentence boundaries and reports nonsense.
 * </p>
 */
public final class HunkBuilder {

    /**
     * Largest change block (old + new lines) that gets a word analysis. Above this the block is a
     * rewrite or an insertion, where a word diff helps nobody and costs time.
     */
    static final int MAX_ANALYSED_LINES = 200;

    /** Decides per hunk whether it becomes part of the result. */
    public interface Sink {
        /**
         * Keeps nothing: the result carries the complete statistics but no hunk.
         *
         * <p>
         * This is the "give me the numbers, not the text" mode - the same promise as a filter,
         * just with the answer "none". It runs before a hunk is built, so a 5000-hunk diff costs
         * no hunk objects at all.
         * </p>
         */
        Sink NONE = hunk -> false;

        /**
         * @param hunk the hunk as it would be reported
         * @return true to keep it, false to drop it
         */
        boolean accept(DiffResult.Hunk hunk);
    }

    /**
     * Analysis settings of one comparison: which lines are prose, how wide a word gap may be, and
     * whether single changed lines get a word diff.
     *
     * @param oldProse one flag per old line, or {@code null} when no language was given
     * @param newProse one flag per new line, or {@code null} when no language was given
     * @param maxGap   unchanged words that may sit between two differences of one change site
     * @param intraLineWords true to attach a word diff to every paired changed line
     * @param oldScopes estimated symbol per old line, or {@code null} without a language
     * @param newScopes estimated symbol per new line, or {@code null} without a language
     */
    public record ProseAnalysis(boolean[] oldProse, boolean[] newProse, int maxGap,
            boolean intraLineWords, DiffResult.Scope[] oldScopes, DiffResult.Scope[] newScopes) {
    }

    /**
     * What a build produced.
     *
     * @param hunks       the kept hunks
     * @param totalHunks  number of hunks found
     * @param kept        number of hunks in {@code hunks}
     * @param truncated   true if a limit dropped hunks that the sink wanted
     * @param changeSites number of word diff sites over all built hunks
     * @param rewrapped   number of hunks that are only re-wraps
     * @param symbols     the changes per symbol, largest first
     */
    public record Outcome(List<DiffResult.Hunk> hunks, int totalHunks, int kept, boolean truncated,
            int changeSites, int rewrapped, List<DiffResult.SymbolSummary> symbols) {
    }

    private HunkBuilder() {
    }

    /**
     * Builds the hunks of a script.
     *
     * @param script   the edit script
     * @param oldIndex lines of the old side
     * @param newIndex lines of the new side
     * @param context  unchanged lines kept around each change, at least 0
     * @param maxHunks maximum number of hunks to materialise, at least 1
     * @param maxLines maximum number of hunk lines to materialise, at least 1
     * @param sink     decides per hunk, or {@code null} to keep every hunk
     * @param prose    prose classification, or {@code null} to skip the word analysis
     * @return the outcome, never {@code null}
     */
    public static Outcome build(EditScript script, LineIndex oldIndex, LineIndex newIndex,
            int context, int maxHunks, int maxLines, Sink sink, ProseAnalysis prose) {
        List<int[]> ranges = groupRanges(script.ops(), Math.max(0, context));
        List<DiffResult.Hunk> kept = new ArrayList<>();
        int lineBudget = Math.max(1, maxLines);
        int hunkBudget = Math.max(1, maxHunks);
        int used = 0;
        int changeSites = 0;
        int rewrapped = 0;
        boolean truncated = false;
        Map<String, int[]> symbolCounts = new LinkedHashMap<>();
        Map<String, DiffResult.Scope> symbolScopes = new LinkedHashMap<>();
        for (int[] range : ranges) {
            DiffResult.Hunk hunk = buildHunk(script.ops(), range[0], range[1], oldIndex, newIndex,
                    prose);
            changeSites += hunk.changes().size();
            if (hunk.rewrapped()) {
                rewrapped++;
            }
            DiffResult.Scope scope = hunk.scope();
            if (scope != null) {
                String key = String.join("\u0000", scope.path());
                int[] counts = symbolCounts.computeIfAbsent(key, ignored -> new int[3]);
                counts[0]++;
                counts[1] += hunk.deletedLines();
                counts[2] += hunk.insertedLines();
                symbolScopes.putIfAbsent(key, scope);
            }
            if (sink != null && !sink.accept(hunk)) {
                continue;
            }
            if (kept.size() >= hunkBudget || used + hunk.lines().size() > lineBudget) {
                truncated = true;
                continue;
            }
            kept.add(hunk);
            used += hunk.lines().size();
        }
        return new Outcome(List.copyOf(kept), ranges.size(), kept.size(), truncated, changeSites,
                rewrapped, summarise(symbolCounts, symbolScopes));
    }

    /**
     * Builds the per-symbol summary, largest change first (ties by name, so the order is
     * deterministic).
     *
     * @param counts insertion-ordered counts {@code {hunks, deleted, inserted}} per symbol key
     * @param scopes the scope behind each key
     * @return the summary, never {@code null}
     */
    private static List<DiffResult.SymbolSummary> summarise(Map<String, int[]> counts,
            Map<String, DiffResult.Scope> scopes) {
        List<DiffResult.SymbolSummary> summary = new ArrayList<>(counts.size());
        for (Map.Entry<String, int[]> entry : counts.entrySet()) {
            DiffResult.Scope scope = scopes.get(entry.getKey());
            int[] count = entry.getValue();
            summary.add(new DiffResult.SymbolSummary(scope.name(), scope.path(), count[0],
                    count[1], count[2]));
        }
        summary.sort(Comparator.comparingInt(
                        (DiffResult.SymbolSummary s) -> s.deleted() + s.inserted()).reversed()
                .thenComparing(DiffResult.SymbolSummary::name));
        return List.copyOf(summary);
    }

    /**
     * Groups the changed operations into ranges, extending each by {@code context} unchanged lines
     * and merging ranges whose context would touch.
     *
     * @return pairs {@code {from, to}} of inclusive op indices
     */
    private static List<int[]> groupRanges(List<EditScript.Op> ops, int context) {
        List<int[]> ranges = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) {
            if (ops.get(i).kind() == EditScript.Kind.EQUAL) {
                continue;
            }
            int from = Math.max(0, i - context);
            int to = Math.min(ops.size() - 1, i + context);
            if (!ranges.isEmpty() && from <= ranges.get(ranges.size() - 1)[1] + 1) {
                ranges.get(ranges.size() - 1)[1] = to;
            } else {
                ranges.add(new int[]{from, to});
            }
        }
        return ranges;
    }

    /** Builds one hunk from the op range {@code [from, to]} (inclusive). */
    private static DiffResult.Hunk buildHunk(List<EditScript.Op> ops, int from, int to,
            LineIndex oldIndex, LineIndex newIndex, ProseAnalysis prose) {
        int oldLines = 0;
        int newLines = 0;
        int oldFirst = -1;
        int newFirst = -1;
        List<DiffResult.Line> lines = new ArrayList<>(to - from + 1);
        for (int i = from; i <= to; i++) {
            EditScript.Op op = ops.get(i);
            switch (op.kind()) {
                case EQUAL -> {
                    if (oldFirst < 0) {
                        oldFirst = op.oldIndex();
                    }
                    if (newFirst < 0) {
                        newFirst = op.newIndex();
                    }
                    oldLines++;
                    newLines++;
                    lines.add(new DiffResult.Line(EditScript.Kind.EQUAL, oldIndex.line(op.oldIndex()),
                            op.oldIndex() + 1, op.newIndex() + 1, null));
                }
                case DELETE -> {
                    if (oldFirst < 0) {
                        oldFirst = op.oldIndex();
                    }
                    oldLines++;
                    lines.add(new DiffResult.Line(EditScript.Kind.DELETE, oldIndex.line(op.oldIndex()),
                            op.oldIndex() + 1, 0, null));
                }
                case INSERT -> {
                    if (newFirst < 0) {
                        newFirst = op.newIndex();
                    }
                    newLines++;
                    lines.add(new DiffResult.Line(EditScript.Kind.INSERT, newIndex.line(op.newIndex()),
                            0, op.newIndex() + 1, null));
                }
            }
        }
        // A hunk that only inserts has no old line of its own: the header names the position it
        // sits at (the number of old lines before it), which is what diff -u does.
        EditScript.Op first = ops.get(from);
        int oldStart = oldFirst >= 0 ? oldFirst + 1 : first.oldIndex();
        int newStart = newFirst >= 0 ? newFirst + 1 : first.newIndex();
        int oldOffset = oldFirst >= 0 ? oldIndex.byteOffset(oldFirst)
                : oldIndex.byteOffset(Math.min(first.oldIndex(), oldIndex.size()));
        int newOffset = newFirst >= 0 ? newIndex.byteOffset(newFirst)
                : newIndex.byteOffset(Math.min(first.newIndex(), newIndex.size()));

        List<DiffResult.Change> changes = new ArrayList<>();
        boolean rewrapped = prose != null && analyseChanges(ops, from, to, oldIndex, newIndex, prose,
                changes);
        List<DiffResult.Line> hunkLines = List.copyOf(lines);
        if (prose != null && prose.intraLineWords()) {
            hunkLines = withIntraLineWords(lines, ops, from, to);
        }
        return new DiffResult.Hunk(oldStart, oldLines, newStart, newLines, oldOffset, newOffset,
                rewrapped, List.copyOf(changes), hunkLines, firstScope(ops, from, to, prose));
    }

    /**
     * Looks up the symbol of the first changed line: on the old side if a line was removed there,
     * otherwise on the new side.
     *
     * @return the scope, or {@code null} without a language or outside every symbol
     */
    private static DiffResult.Scope firstScope(List<EditScript.Op> ops, int from, int to,
            ProseAnalysis analysis) {
        if (analysis == null || analysis.oldScopes() == null || analysis.newScopes() == null) {
            return null;
        }
        for (int i = from; i <= to; i++) {
            EditScript.Op op = ops.get(i);
            if (op.kind() == EditScript.Kind.DELETE) {
                return analysis.oldScopes()[op.oldIndex()];
            }
            if (op.kind() == EditScript.Kind.INSERT) {
                return analysis.newScopes()[op.newIndex()];
            }
        }
        return null;
    }

    /**
     * Pairs deleted and inserted lines of every change block (first with first, and so on) and
     * attaches the word diff of the pair to both lines.
     *
     * @param lines the hunk's lines, one per op of the range
     * @param ops   the script
     * @param from  first op index of the hunk
     * @param to    last op index of the hunk
     * @return the lines with their word diffs
     */
    private static List<DiffResult.Line> withIntraLineWords(List<DiffResult.Line> lines,
            List<EditScript.Op> ops, int from, int to) {
        DiffResult.Line[] result = lines.toArray(new DiffResult.Line[0]);
        int i = from;
        while (i <= to) {
            if (ops.get(i).kind() == EditScript.Kind.EQUAL) {
                i++;
                continue;
            }
            int blockStart = i;
            while (i <= to && ops.get(i).kind() != EditScript.Kind.EQUAL) {
                i++;
            }
            List<Integer> deleted = new ArrayList<>();
            List<Integer> inserted = new ArrayList<>();
            for (int n = blockStart; n < i; n++) {
                if (ops.get(n).kind() == EditScript.Kind.DELETE) {
                    deleted.add(n - from);
                } else {
                    inserted.add(n - from);
                }
            }
            int pairs = Math.min(deleted.size(), inserted.size());
            for (int p = 0; p < pairs; p++) {
                DiffResult.Line oldLine = result[deleted.get(p)];
                DiffResult.Line newLine = result[inserted.get(p)];
                List<DiffResult.Word> words = Prose.wordsOfLine(oldLine.text(), newLine.text());
                result[deleted.get(p)] = new DiffResult.Line(oldLine.kind(), oldLine.text(),
                        oldLine.oldNumber(), oldLine.newNumber(), words);
                result[inserted.get(p)] = new DiffResult.Line(newLine.kind(), newLine.text(),
                        newLine.oldNumber(), newLine.newNumber(), words);
            }
        }
        return List.of(result);
    }

    /**
     * Adds the word-level change sites of every change block in the hunk.
     *
     * @return true if the hunk has at least one change block and every one of them is a pure
     *         re-wrap (same words on both sides)
     */
    private static boolean analyseChanges(List<EditScript.Op> ops, int from, int to,
            LineIndex oldIndex, LineIndex newIndex, ProseAnalysis prose,
            List<DiffResult.Change> changes) {
        if (prose.oldProse() == null || prose.newProse() == null) {
            return false;
        }
        boolean sawBlock = false;
        boolean allRewrapped = true;
        int i = from;
        while (i <= to) {
            if (ops.get(i).kind() == EditScript.Kind.EQUAL) {
                i++;
                continue;
            }
            int blockStart = i;
            while (i <= to && ops.get(i).kind() != EditScript.Kind.EQUAL) {
                i++;
            }
            List<String> deleted = new ArrayList<>();
            List<String> inserted = new ArrayList<>();
            boolean allProse = true;
            for (int n = blockStart; n < i; n++) {
                EditScript.Op op = ops.get(n);
                if (op.kind() == EditScript.Kind.DELETE) {
                    deleted.add(oldIndex.line(op.oldIndex()));
                    allProse &= op.oldIndex() < prose.oldProse().length
                            && prose.oldProse()[op.oldIndex()];
                } else {
                    inserted.add(newIndex.line(op.newIndex()));
                    allProse &= op.newIndex() < prose.newProse().length
                            && prose.newProse()[op.newIndex()];
                }
            }
            sawBlock = true;
            if (deleted.size() + inserted.size() > MAX_ANALYSED_LINES) {
                // A rewrite or an insertion: the words would just be the lines again.
                allRewrapped = false;
                continue;
            }
            boolean bothSides = !deleted.isEmpty() && !inserted.isEmpty();
            if (!bothSides || !allProse) {
                // Change sites are a reading aid for prose, where a line diff is unreadable. In
                // code the line diff already names the change; a word diff there is opt-in
                // (intraLine) and would otherwise drown the result - measured on a 6600-line
                // Python file: 362 sites, of which about ten were prose.
                allRewrapped = false;
                continue;
            }
            List<DiffResult.Change> sites = Prose.sites(Prose.words(deleted),
                    Prose.words(inserted), prose.maxGap());
            if (sites.isEmpty()) {
                continue;
            }
            allRewrapped = false;
            changes.addAll(sites);
        }
        return sawBlock && allRewrapped;
    }
}
