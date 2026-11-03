package org.rogmann.mcp2sdk.diff;

import java.util.ArrayList;
import java.util.List;

/**
 * Entry point of the diff engine: two texts in, one {@link DiffResult} out.
 *
 * <p>
 * This class is the whole public surface for the JavaScript module and for the review diff: it
 * splits the texts ({@link LineIndex}), computes the edit script ({@link EditScript}), projects it
 * into segments, builds the hunks ({@link HunkBuilder}) and counts. It knows nothing about
 * GraalVM, files or paths - those belong to the caller, so the engine stays testable without a
 * JavaScript context and without touching the file system.
 * </p>
 */
public final class DiffEngine {

    /** Default context lines around a change. */
    public static final int DEFAULT_CONTEXT = 3;
    /** Default budget in comparison cells. */
    public static final long DEFAULT_MAX_COST = 200_000_000L;
    /** Default maximum number of hunks to materialise. */
    public static final int DEFAULT_MAX_HUNKS = 200;
    /** Default maximum number of hunk lines to materialise. */
    public static final int DEFAULT_MAX_LINES = 20_000;
    /** Unchanged runs shorter than this are absorbed into the surrounding change segment. */
    public static final int DEFAULT_BLOCK_MIN_LINES = 3;

    /** Granularity of the word diff inside a changed line. */
    public enum IntraLine {
        /** No word diff (default). */
        NONE,
        /** Word diff per paired line, attached to both lines of the pair. */
        WORD
    }

    /**
     * Options of one comparison.
     *
     * @param algorithm      strategy for the edit script
     * @param context        unchanged lines kept around a change
     * @param maxCost        budget in comparison cells
     * @param maxHunks       maximum number of hunks to materialise
     * @param maxLines       maximum number of hunk lines to materialise
     * @param blockMinLines  minimum length of an unchanged run reported as its own segment
     * @param language       language for the prose analysis (re-wrap detection, change sites),
     *                       {@code null} to skip it
     * @param intraLine      word diff inside changed lines
     */
    public record Options(EditScript.Algorithm algorithm, int context, long maxCost, int maxHunks,
            int maxLines, int blockMinLines, String language, IntraLine intraLine) {

        /**
         * @return the defaults: histogram, 3 context lines, 200 million cells, 200 hunks,
         *         20000 hunk lines, 3 lines for a segment, no prose analysis, no intra-line diff
         */
        public static Options defaults() {
            return new Options(EditScript.Algorithm.HISTOGRAM, DEFAULT_CONTEXT, DEFAULT_MAX_COST,
                    DEFAULT_MAX_HUNKS, DEFAULT_MAX_LINES, DEFAULT_BLOCK_MIN_LINES, null,
                    IntraLine.NONE);
        }
    }

    private DiffEngine() {
    }

    /**
     * Compares two texts.
     *
     * @param oldText old content, {@code null} is treated as empty
     * @param newText new content, {@code null} is treated as empty
     * @param options comparison options, {@code null} means {@link Options#defaults()}
     * @return the result, never {@code null}
     */
    public static DiffResult diff(String oldText, String newText, Options options) {
        LineIndex oldIndex = LineIndex.of(oldText);
        LineIndex newIndex = LineIndex.of(newText);
        return diff(oldIndex, newIndex, null, null, options, null);
    }

    /**
     * Compares two indexed texts and hands every hunk to a sink before it is materialised.
     *
     * @param oldIndex  lines of the old side
     * @param newIndex  lines of the new side
     * @param oldSource description of the old side for the result, may be {@code null}
     * @param newSource description of the new side for the result, may be {@code null}
     * @param options   comparison options, {@code null} means {@link Options#defaults()}
     * @param sink      decides per hunk, or {@code null} to keep every hunk
     * @return the result, never {@code null}
     */
    public static DiffResult diff(LineIndex oldIndex, LineIndex newIndex, DiffResult.Source oldSource,
            DiffResult.Source newSource, Options options, HunkBuilder.Sink sink) {
        Options opts = options == null ? Options.defaults() : options;
        if (opts.context() < 0) {
            throw new IllegalArgumentException("context must not be negative, but was "
                    + opts.context());
        }
        EditScript script = EditScript.compute(oldIndex.keys(), newIndex.keys(), opts.algorithm(),
                opts.maxCost());
        HunkBuilder.ProseAnalysis prose = proseAnalysis(oldIndex, newIndex, opts.language(),
                opts.intraLine());
        HunkBuilder.Outcome outcome = HunkBuilder.build(script, oldIndex, newIndex, opts.context(),
                opts.maxHunks(), opts.maxLines(), sink, prose);
        List<EditScript.Block> blocks = script.blocks(opts.blockMinLines());
        DiffResult.Stats stats = count(script, blocks, outcome);
        List<String> notes = new ArrayList<>();
        if (script.approximated()) {
            notes.add("changed region too large for an exact comparison (maxCost "
                    + opts.maxCost() + ") - it is reported as a replacement block");
        }
        if (outcome.truncated()) {
            notes.add("output limited to " + opts.maxHunks() + " hunks / " + opts.maxLines()
                    + " lines; " + outcome.totalHunks() + " hunks were found");
        }
        if (!script.approximated() && script.differingLines() == 0
                && (oldIndex.finalNewline() != newIndex.finalNewline())) {
            notes.add("content is equal; only the line ending at the end of the file differs");
        }
        if (oldSource != null && oldSource.missing()) {
            notes.add("the old side does not exist - this is a new file"
                    + labelSuffix(newSource));
        }
        if (newSource != null && newSource.missing()) {
            notes.add("the new side does not exist - the file was deleted"
                    + labelSuffix(oldSource));
        }
        return new DiffResult(oldIndex, newIndex, oldSource, newSource, outcome.hunks(), stats,
                blocks, outcome.symbols(), outcome.truncated(), script.approximated(),
                List.copyOf(notes));
    }

    /**
     * Builds the ": path" suffix of a note, or the empty string when there is no path to name.
     *
     * @param source one side of the comparison, may be {@code null}
     * @return the suffix, never {@code null}
     */
    private static String labelSuffix(DiffResult.Source source) {
        if (source == null || source.path() == null || source.path().isBlank()) {
            return "";
        }
        return ": " + source.path();
    }

    /**
     * Builds the analysis settings, or returns {@code null} when neither prose classification nor
     * an intra-line word diff was asked for.
     *
     * @param oldIndex  lines of the old side
     * @param newIndex  lines of the new side
     * @param language  language name, may be {@code null}
     * @param intraLine word diff granularity, may be {@code null} for {@link IntraLine#NONE}
     * @return the analysis or {@code null}
     * @throws IllegalArgumentException for an unknown language
     */
    private static HunkBuilder.ProseAnalysis proseAnalysis(LineIndex oldIndex, LineIndex newIndex,
            String language, IntraLine intraLine) {
        boolean words = intraLine == IntraLine.WORD;
        if (language == null) {
            return words ? new HunkBuilder.ProseAnalysis(null, null, Prose.DEFAULT_MAX_GAP, true,
                    null, null) : null;
        }
        if (!Prose.isKnownLanguage(language)) {
            throw new IllegalArgumentException("unknown language '" + language
                    + "'; valid values are " + String.join(", ", Prose.languages()));
        }
        return new HunkBuilder.ProseAnalysis(Prose.classify(oldIndex, language),
                Prose.classify(newIndex, language), Prose.DEFAULT_MAX_GAP, words,
                Symbols.scopes(oldIndex, language), Symbols.scopes(newIndex, language));
    }

    /**
     * Counts the changes: pairs of deleted and inserted lines as {@code changed}, the rest as
     * {@code added} or {@code removed}.
     */
    private static DiffResult.Stats count(EditScript script, List<EditScript.Block> blocks,
            HunkBuilder.Outcome outcome) {
        int added = 0;
        int removed = 0;
        int changed = 0;
        int deleted = 0;
        int inserted = 0;
        for (EditScript.Op op : script.ops()) {
            if (op.kind() == EditScript.Kind.DELETE) {
                deleted++;
            } else if (op.kind() == EditScript.Kind.INSERT) {
                inserted++;
            } else {
                changed += Math.min(deleted, inserted);
                removed += Math.max(0, deleted - inserted);
                added += Math.max(0, inserted - deleted);
                deleted = 0;
                inserted = 0;
            }
        }
        changed += Math.min(deleted, inserted);
        removed += Math.max(0, deleted - inserted);
        added += Math.max(0, inserted - deleted);

        int changeSegments = 0;
        for (EditScript.Block block : blocks) {
            if (block.kind() == EditScript.BlockKind.CHANGE) {
                changeSegments++;
            }
        }
        return new DiffResult.Stats(added, removed, changed, outcome.totalHunks(), outcome.kept(),
                changeSegments, outcome.changeSites(), outcome.rewrapped());
    }

    /**
     * Renders the result as a unified diff ({@code diff -u}).
     *
     * @param result   the result to render
     * @param oldLabel label of the left side, e.g. {@code a/example.txt}
     * @param newLabel label of the right side, e.g. {@code b/example.txt}
     * @return the patch text
     */
    public static String unified(DiffResult result, String oldLabel, String newLabel) {
        return UnifiedRenderer.render(result, oldLabel, newLabel);
    }
}
