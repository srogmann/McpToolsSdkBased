package org.rogmann.mcp2sdk.diff;

import java.util.List;

/**
 * The structured outcome of a diff: the hunks, the statistics and - for file diffs - what was
 * compared.
 *
 * <p>
 * Everything a caller needs is data, not text: a hunk carries line numbers, byte offsets and its
 * lines, so "show me hunk 4" and "read bytes 41234..." work without parsing a patch. The unified
 * rendering is one method on top of it ({@link UnifiedRenderer}), not the only output.
 * </p>
 *
 * <p>
 * The result keeps the {@link LineIndex} of both sides: the symbol annotation has to look up which
 * {@code def}/{@code class}/{@code SECTION} a changed line belongs to, and re-splitting the text
 * for that would be a second parsing of the same input.
 * </p>
 *
 * @param oldIndex  lines and offsets of the old side
 * @param newIndex  lines and offsets of the new side
 * @param oldSource what was compared on the left side, or {@code null} if the caller did not say
 *                  (a side that does not exist is described with {@link Source#absent(String)},
 *                  not with {@code null})
 * @param newSource what was compared on the right side, or {@code null} if the caller did not say
 * @param hunks     the hunks that were materialised (may be fewer than {@link Stats#hunks()})
 * @param stats     the counts over the whole diff
 * @param blocks    the segment projection of the edit script
 * @param symbols   the changes per symbol, largest first; empty without a language
 * @param truncated true if a limit kept hunks out of {@code hunks}
 * @param approximated true if the cost budget forced a replacement block
 * @param notes     human-readable remarks (limits, approximations), never {@code null}
 */
public record DiffResult(LineIndex oldIndex, LineIndex newIndex, Source oldSource,
        Source newSource, List<Hunk> hunks, Stats stats, List<EditScript.Block> blocks,
        List<SymbolSummary> symbols, boolean truncated, boolean approximated,
        List<String> notes) {

    /**
     * One side of a comparison: what was compared, not how it was reached.
     *
     * <p>
     * A caller that already knows the content - a file system, a git blob, an archive entry -
     * describes it here, so a result can say "this is what I compared" without the engine knowing
     * anything about paths or revisions. {@code path} is a label for output, never an access path;
     * {@code id} is the identity the source itself uses (a file system passes its SHA-256, git its
     * blob id), so a caller never has to compute a second hash just to fill the field.
     * </p>
     *
     * <p>
     * A side that <em>does not exist</em> is not an empty side: {@link #absent(String)} says so.
     * An empty file has {@code bytes = 0} and a line count; an added or deleted file has neither,
     * and a result that confused the two would read as "an empty file became a 400-line file".
     * </p>
     *
     * @param path         display path, or {@code null} when the caller has none
     * @param id           opaque identity of the content as the source knows it, or {@code null}
     * @param bytes        size in bytes, {@code -1} if not determined
     * @param lines        number of lines, {@code -1} if not determined (binary content)
     * @param sha256       hex digest of the content, or {@code null} if not computed
     * @param eol          kind of line ending, or {@code null} if not determined
     * @param finalNewline true if the content ends with a line terminator
     * @param bom          true if the content starts with a byte order mark
     * @param present      false if this side does not exist (added or deleted file)
     */
    public record Source(String path, String id, int bytes, int lines, String sha256, String eol,
            boolean finalNewline, boolean bom, boolean present) {

        /**
         * A side that does not exist: the file was added or deleted.
         *
         * @param path display path of the side, may be {@code null}
         * @return the marker; {@link #present()} is false, size and line count are {@code -1}
         */
        public static Source absent(String path) {
            return new Source(path, null, -1, -1, null, null, false, false, false);
        }

        /**
         * @return true if this side exists; the inverse of {@code present}
         */
        public boolean missing() {
            return !present;
        }
    }

    /**
     * One word of an intra-line diff.
     *
     * @param op   {@code ' '} unchanged, {@code '-'} removed, {@code '+'} added
     * @param text the word
     */
    public record Word(char op, String text) {
    }

    /**
     * One line of a hunk.
     *
     * @param kind      unchanged, deleted or inserted
     * @param text      the line text without terminator
     * @param oldNumber 1-based line number in the old file, 0 for an insertion
     * @param newNumber 1-based line number in the new file, 0 for a deletion
     * @param words     word diff against the paired line of the other side, or {@code null};
     *                  the same list appears on both lines of a pair
     */
    public record Line(EditScript.Kind kind, String text, int oldNumber, int newNumber,
            List<Word> words) {
    }

    /**
     * One place where the words changed: the words that disappeared and the words that arrived.
     * Either side may be empty (a pure insertion or a pure deletion of words).
     *
     * @param oldText the removed words, space separated, possibly empty
     * @param newText the inserted words, space separated, possibly empty
     */
    public record Change(String oldText, String newText) {
    }

    /**
     * The estimated enclosing symbol of a hunk: the innermost function, type or section a changed
     * line sits in, plus the chain of symbols that contain it - so a hunk can say "in method
     * {@code run} of type {@code Server}" instead of only naming a line number.
     *
     * @param name innermost symbol name
     * @param path the chain from outermost to innermost, e.g. {@code ["Server", "run"]}
     */
    public record Scope(String name, List<String> path) {
    }

    /**
     * One hunk: a changed region with its context.
     *
     * @param oldStart  1-based first old line of the hunk
     * @param oldLines  number of old lines in the hunk
     * @param newStart  1-based first new line of the hunk
     * @param newLines  number of new lines in the hunk
     * @param oldOffset UTF-8 byte offset of {@code oldStart} in the old content
     * @param newOffset UTF-8 byte offset of {@code newStart} in the new content
     * @param rewrapped true if every change of the hunk keeps the words and only moves line breaks
     * @param changes   the places where words changed, in order; empty for a pure re-wrap
     * @param lines     the hunk's lines in file order
     * @param scope     the enclosing symbol of the first changed line, or {@code null}
     */
    public record Hunk(int oldStart, int oldLines, int newStart, int newLines, int oldOffset,
            int newOffset, boolean rewrapped, List<Change> changes, List<Line> lines, Scope scope) {

        /**
         * @return number of deleted lines in this hunk
         */
        public int deletedLines() {
            int count = 0;
            for (Line line : lines) {
                if (line.kind() == EditScript.Kind.DELETE) {
                    count++;
                }
            }
            return count;
        }

        /**
         * @return number of inserted lines in this hunk
         */
        public int insertedLines() {
            int count = 0;
            for (Line line : lines) {
                if (line.kind() == EditScript.Kind.INSERT) {
                    count++;
                }
            }
            return count;
        }
    }

    /**
     * The changes that fall into one symbol, for the question "which function carries the change".
     *
     * <p>
     * {@code deleted} and {@code inserted} count every line of the symbol's hunks, paired or not -
     * the "+N/-M" a reader expects for a function. Attribution happens per <em>hunk</em>: a hunk
     * that touches several symbols is counted for the symbol of its first changed line, and hunks
     * outside every symbol are not listed at all - so the sums can be smaller than {@link Stats}.
     * </p>
     *
     * @param name     the symbol's name
     * @param path     the chain from outermost to innermost
     * @param hunks    number of hunks attributed to this symbol
     * @param deleted  deleted lines in those hunks
     * @param inserted inserted lines in those hunks
     */
    public record SymbolSummary(String name, List<String> path, int hunks, int deleted,
            int inserted) {
    }

    /**
     * Counts over the whole diff.
     *
     * <p>
     * {@code added} and {@code removed} count the lines that have no partner in their change block;
     * {@code changed} counts the pairs. A hunk that replaces eight lines by eight reports
     * {@code changed: 8}, not {@code added: 8, removed: 8} - otherwise "8 lines changed" reads as
     * "16 changes".
     * </p>
     *
     * @param added       inserted lines without a deleted partner
     * @param removed     deleted lines without an inserted partner
     * @param changed     deleted/inserted pairs within a change block
     * @param hunks       number of hunks found (independent of the output limit)
     * @param kept        number of hunks materialised into the result
     * @param blocks      number of change segments in the block projection
     * @param changeSites number of word-level change sites over all hunks, 0 without {@code language}
     * @param rewrapped   number of hunks whose changes are only re-wraps
     */
    public record Stats(int added, int removed, int changed, int hunks, int kept, int blocks,
            int changeSites, int rewrapped) {
    }
}
