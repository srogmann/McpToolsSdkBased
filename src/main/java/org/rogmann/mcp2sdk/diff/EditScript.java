package org.rogmann.mcp2sdk.diff;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The edit script of two sequences of comparison keys: which lines are equal, which exist only on
 * the left and which only on the right.
 *
 * <p>
 * <b>Two strategies, one result type.</b> {@link Algorithm#MINIMAL} computes a script with the
 * fewest possible changes; {@link Algorithm#HISTOGRAM} and {@link Algorithm#PATIENCE} first split
 * both sides at <em>rare</em> common lines and diff the gaps minimally. The difference is not the
 * minimality but the choice among equally short scripts: without anchors a script tends to align
 * repeated lines (closing braces, blank lines, {@code import}) instead of the line a reader uses
 * as the block boundary.
 * </p>
 *
 * <p>
 * <b>Minimal, but not Myers.</b> The minimal strategy is implemented as an LCS with linear space
 * (Hirschberg), not as Myers' middle-snake divide and conquer. Both produce the same number of
 * changes; only the tie-break differs. Hirschberg was chosen because it is short enough to be
 * reviewed, because it keeps the rule the existing review diff uses (so replacing that
 * implementation does not change the patches a reviewer sees) and because its cost is easy to
 * bound: every recursive call costs {@code (aTo-aFrom)*(bTo-bFrom)} comparisons, and the sum is
 * the budget {@code maxCost}. When the budget is exhausted the region is reported as
 * "old lines out, new lines in" and {@link #approximated()} says so.
 * </p>
 *
 * <p>
 * Instances are immutable; the script is built once and then read.
 * </p>
 */
public final class EditScript {

    /** What one line does in the script. */
    public enum Kind {
        /** Present on both sides. */
        EQUAL,
        /** Present on the left side only. */
        DELETE,
        /** Present on the right side only. */
        INSERT
    }

    /** How the script is computed. */
    public enum Algorithm {
        /** Fewest possible changes (LCS with linear space). */
        MINIMAL,
        /** Split at the rarest common lines, diff the gaps minimally. */
        HISTOGRAM,
        /** Split at lines that occur exactly once on both sides, diff the gaps minimally. */
        PATIENCE
    }

    /**
     * One step of the script.
     *
     * @param kind      what the step does
     * @param oldIndex  0-based index in the old sequence, or the position where the step sits for
     *                  an {@link Kind#INSERT}
     * @param newIndex  0-based index in the new sequence, or the position where the step sits for
     *                  a {@link Kind#DELETE}
     */
    public record Op(Kind kind, int oldIndex, int newIndex) {
    }

    /** Kind of a segment reported by {@link EditScript#blocks(int)}. */
    public enum BlockKind {
        /** Unchanged run. */
        EQUAL,
        /** Changed run, possibly with lines on both sides. */
        CHANGE
    }

    /**
     * A contiguous segment of both files.
     *
     * @param kind     unchanged or changed
     * @param oldStart 0-based first line of the segment in the old file
     * @param oldLines number of old lines in the segment
     * @param newStart 0-based first line of the segment in the new file
     * @param newLines number of new lines in the segment
     */
    public record Block(BlockKind kind, int oldStart, int oldLines, int newStart, int newLines) {
    }

    private final List<Op> ops;
    private final boolean approximated;
    private final long cost;

    private EditScript(List<Op> ops, boolean approximated, long cost) {
        this.ops = ops;
        this.approximated = approximated;
        this.cost = cost;
    }

    /**
     * Computes the script of two key sequences.
     *
     * @param oldKeys comparison keys of the old side
     * @param newKeys comparison keys of the new side
     * @param algorithm strategy to use
     * @param maxCost budget in comparison cells, must be positive
     * @return the script, never {@code null}
     * @throws IllegalArgumentException if keys are null or the budget is not positive
     */
    public static EditScript compute(String[] oldKeys, String[] newKeys, Algorithm algorithm,
            long maxCost) {
        if (oldKeys == null || newKeys == null) {
            throw new IllegalArgumentException("oldKeys and newKeys are required");
        }
        if (maxCost <= 0) {
            throw new IllegalArgumentException("maxCost must be positive, but was " + maxCost);
        }
        Differ differ = new Differ(oldKeys, newKeys, maxCost);
        if (algorithm == Algorithm.MINIMAL) {
            differ.diffMinimal(0, oldKeys.length, 0, newKeys.length);
        } else {
            differ.diffAnchored(0, oldKeys.length, 0, newKeys.length,
                    algorithm == Algorithm.PATIENCE);
        }
        return new EditScript(List.copyOf(differ.out), differ.approximated, differ.cost);
    }

    /**
     * @return the script in ascending order of both sides
     */
    public List<Op> ops() {
        return ops;
    }

    /**
     * @return true if the budget forced a region to be reported as a replacement block
     */
    public boolean approximated() {
        return approximated;
    }

    /**
     * @return the consumed comparison cells
     */
    public long cost() {
        return cost;
    }

    /**
     * Number of lines of the script that are not equal.
     *
     * @return {@code deletions + insertions}
     */
    public int differingLines() {
        int count = 0;
        for (Op op : ops) {
            if (op.kind() != Kind.EQUAL) {
                count++;
            }
        }
        return count;
    }

    /**
     * Projects the script into segments, so that a caller sees "unchanged 1..120, changed 121..129,
     * unchanged 130.." instead of single lines. Unchanged runs shorter than
     * {@code minEqualLines} are merged into the surrounding change, which is what makes
     * "one insertion of 900 lines" stay one segment instead of being cut by a line that happens to
     * occur in both files.
     *
     * @param minEqualLines minimum length an unchanged run must have to be reported as its own
     *                      segment; {@code 0} reports every run
     * @return segments in file order, covering both files completely
     */
    public List<Block> blocks(int minEqualLines) {
        List<Block> raw = new ArrayList<>();
        int i = 0;
        while (i < ops.size()) {
            Kind kind = ops.get(i).kind();
            int j = i;
            while (j < ops.size() && ops.get(j).kind() == kind) {
                j++;
            }
            raw.add(toBlock(i, j, kind));
            i = j;
        }

        List<Block> result = new ArrayList<>();
        int currentOldStart = -1;
        int currentNewStart = -1;
        int currentOld = 0;
        int currentNew = 0;
        for (int n = 0; n < raw.size(); n++) {
            Block block = raw.get(n);
            boolean interior = n > 0 && n < raw.size() - 1;
            boolean shortEqual = block.kind() == BlockKind.EQUAL && block.oldLines() < minEqualLines;
            if (block.kind() == BlockKind.EQUAL && !(interior && shortEqual)) {
                if (currentOldStart >= 0) {
                    result.add(new Block(BlockKind.CHANGE, currentOldStart, currentOld,
                            currentNewStart, currentNew));
                    currentOldStart = -1;
                }
                result.add(block);
            } else {
                if (currentOldStart < 0) {
                    currentOldStart = block.oldStart();
                    currentNewStart = block.newStart();
                    currentOld = 0;
                    currentNew = 0;
                }
                currentOld += block.oldLines();
                currentNew += block.newLines();
            }
        }
        if (currentOldStart >= 0) {
            result.add(new Block(BlockKind.CHANGE, currentOldStart, currentOld, currentNewStart,
                    currentNew));
        }
        return result;
    }

    /** Builds one raw segment from the op range {@code [from, to)}. */
    private Block toBlock(int from, int to, Kind kind) {
        Op first = ops.get(from);
        int count = to - from;
        if (kind == Kind.EQUAL) {
            return new Block(BlockKind.EQUAL, first.oldIndex(), count, first.newIndex(), count);
        }
        if (kind == Kind.DELETE) {
            return new Block(BlockKind.CHANGE, first.oldIndex(), count, first.newIndex(), 0);
        }
        return new Block(BlockKind.CHANGE, first.oldIndex(), 0, first.newIndex(), count);
    }

    /**
     * Applies the script to a sequence, used by the tests and by "did the script really describe
     * this change" checks.
     *
     * @param oldLines the old lines
     * @param newLines the new lines, used to materialise insertions
     * @return the lines the script produces
     */
    public List<String> apply(String[] oldLines, String[] newLines) {
        List<String> result = new ArrayList<>();
        for (Op op : ops) {
            switch (op.kind()) {
                case EQUAL, DELETE -> {
                    if (op.oldIndex() >= 0 && op.oldIndex() < oldLines.length) {
                        if (op.kind() == Kind.EQUAL) {
                            result.add(oldLines[op.oldIndex()]);
                        }
                    }
                }
                case INSERT -> {
                    if (op.newIndex() >= 0 && op.newIndex() < newLines.length) {
                        result.add(newLines[op.newIndex()]);
                    }
                }
            }
        }
        return result;
    }

    // ========================================================================
    // The differ
    // ========================================================================

    /** Working state of one computation: the keys, the budget, the output list. */
    private static final class Differ {

        /** The old keys. */
        private final String[] a;
        /** The new keys. */
        private final String[] b;
        /** Budget in comparison cells. */
        private final long maxCost;
        /** Consumed comparison cells. */
        private long cost;
        /** True once the budget forced a replacement block. */
        private boolean approximated;
        /** The script being built. */
        private final List<Op> out = new ArrayList<>();

        /** Key id per old line. */
        private final int[] aId;
        /** Key id per new line. */
        private final int[] bId;
        /** Number of distinct keys. */
        private final int idCount;
        /** Occurrence counts per key, old side (valid where {@link #stampA} matches). */
        private final int[] countA;
        /** Occurrence counts per key, new side (valid where {@link #stampB} matches). */
        private final int[] countB;
        /** Region stamp of the old side counts. */
        private final int[] stampA;
        /** Region stamp of the new side counts. */
        private final int[] stampB;
        /** Current region stamp. */
        private int stamp;

        Differ(String[] a, String[] b, long maxCost) {
            this.a = a;
            this.b = b;
            this.maxCost = maxCost;
            Map<String, Integer> ids = new HashMap<>();
            this.aId = idsOf(a, ids);
            this.bId = idsOf(b, ids);
            this.idCount = ids.size();
            this.countA = new int[idCount];
            this.countB = new int[idCount];
            this.stampA = new int[idCount];
            this.stampB = new int[idCount];
        }

        private static int[] idsOf(String[] keys, Map<String, Integer> ids) {
            int[] result = new int[keys.length];
            for (int i = 0; i < keys.length; i++) {
                Integer id = ids.get(keys[i]);
                if (id == null) {
                    id = ids.size();
                    ids.put(keys[i], id);
                }
                result[i] = id;
            }
            return result;
        }

        // ---- minimal strategy ------------------------------------------------

        /**
         * Diffs one region minimally: common prefix and suffix are emitted directly, the middle
         * is split in half and solved recursively (Hirschberg).
         */
        void diffMinimal(int aLo, int aHi, int bLo, int bHi) {
            while (aLo < aHi && bLo < bHi && a[aLo].equals(b[bLo])) {
                out.add(new Op(Kind.EQUAL, aLo, bLo));
                aLo++;
                bLo++;
            }
            int aEnd = aHi;
            int bEnd = bHi;
            while (aEnd > aLo && bEnd > bLo && a[aEnd - 1].equals(b[bEnd - 1])) {
                aEnd--;
                bEnd--;
            }
            if (aLo == aEnd) {
                for (int j = bLo; j < bEnd; j++) {
                    out.add(new Op(Kind.INSERT, aLo, j));
                }
            } else if (bLo == bEnd) {
                for (int i = aLo; i < aEnd; i++) {
                    out.add(new Op(Kind.DELETE, i, bLo));
                }
            } else if (aEnd - aLo == 1) {
                diffSingleOldLine(aLo, bLo, bEnd);
            } else if (bEnd - bLo == 1) {
                diffSingleNewLine(aLo, aEnd, bLo);
            } else {
                long cells = (long) (aEnd - aLo) * (long) (bEnd - bLo);
                if (cost + cells > maxCost) {
                    approximated = true;
                    for (int i = aLo; i < aEnd; i++) {
                        out.add(new Op(Kind.DELETE, i, bLo));
                    }
                    for (int j = bLo; j < bEnd; j++) {
                        out.add(new Op(Kind.INSERT, aEnd, j));
                    }
                } else {
                    cost += cells;
                    int aMid = (aLo + aEnd) >>> 1;
                    int[] left = lcsPrefix(aLo, aMid, bLo, bEnd);
                    int[] right = lcsSuffix(aMid, aEnd, bLo, bEnd);
                    int bestJ = bLo;
                    int best = -1;
                    for (int j = 0; j <= bEnd - bLo; j++) {
                        int value = left[j] + right[j];
                        if (value > best) {
                            best = value;
                            bestJ = bLo + j;
                        }
                    }
                    diffMinimal(aLo, aMid, bLo, bestJ);
                    diffMinimal(aMid, aEnd, bestJ, bEnd);
                }
            }
            for (int i = aEnd; i < aHi; i++) {
                out.add(new Op(Kind.EQUAL, i, bEnd + (i - aEnd)));
            }
        }

        /**
         * Diffs one region with exactly one old line against a range of new lines. The halving
         * above cannot split this (the left half would be the whole region again), so the single
         * line is searched once - which is also the minimal answer: {@code k-1} insertions if the
         * line occurs among the {@code k} new lines, {@code k+1} changes if it does not.
         */
        private void diffSingleOldLine(int aIndex, int bFrom, int bTo) {
            int match = -1;
            for (int j = bFrom; j < bTo; j++) {
                if (a[aIndex].equals(b[j])) {
                    match = j;
                    break;
                }
            }
            if (match < 0) {
                out.add(new Op(Kind.DELETE, aIndex, bFrom));
                for (int j = bFrom; j < bTo; j++) {
                    out.add(new Op(Kind.INSERT, aIndex, j));
                }
                return;
            }
            for (int j = bFrom; j < match; j++) {
                out.add(new Op(Kind.INSERT, aIndex, j));
            }
            out.add(new Op(Kind.EQUAL, aIndex, match));
            for (int j = match + 1; j < bTo; j++) {
                out.add(new Op(Kind.INSERT, aIndex + 1, j));
            }
        }

        /**
         * Diffs one new line against a range of old lines; the mirror case of
         * {@link #diffSingleOldLine(int, int, int)}.
         */
        private void diffSingleNewLine(int aFrom, int aTo, int bIndex) {
            int match = -1;
            for (int i = aFrom; i < aTo; i++) {
                if (a[i].equals(b[bIndex])) {
                    match = i;
                    break;
                }
            }
            if (match < 0) {
                for (int i = aFrom; i < aTo; i++) {
                    out.add(new Op(Kind.DELETE, i, bIndex));
                }
                out.add(new Op(Kind.INSERT, aTo, bIndex));
                return;
            }
            for (int i = aFrom; i < match; i++) {
                out.add(new Op(Kind.DELETE, i, bIndex));
            }
            out.add(new Op(Kind.EQUAL, match, bIndex));
            for (int i = match + 1; i < aTo; i++) {
                out.add(new Op(Kind.DELETE, i, bIndex + 1));
            }
        }

        /** {@code result[j] = LCS(a[aFrom..aTo), b[bFrom..bFrom+j))} for {@code j in 0..bTo-bFrom}. */
        private int[] lcsPrefix(int aFrom, int aTo, int bFrom, int bTo) {
            int width = bTo - bFrom;
            int[] prev = new int[width + 1];
            int[] cur = new int[width + 1];
            for (int i = aFrom; i < aTo; i++) {
                String key = a[i];
                cur[0] = 0;
                for (int j = 1; j <= width; j++) {
                    if (key.equals(b[bFrom + j - 1])) {
                        cur[j] = prev[j - 1] + 1;
                    } else {
                        cur[j] = Math.max(prev[j], cur[j - 1]);
                    }
                }
                int[] swap = prev;
                prev = cur;
                cur = swap;
            }
            return prev;
        }

        /** {@code result[j] = LCS(a[aFrom..aTo), b[bFrom+j..bTo))} for {@code j in 0..bTo-bFrom}. */
        private int[] lcsSuffix(int aFrom, int aTo, int bFrom, int bTo) {
            int width = bTo - bFrom;
            int[] next = new int[width + 1];
            int[] cur = new int[width + 1];
            for (int i = aTo - 1; i >= aFrom; i--) {
                String key = a[i];
                cur[width] = 0;
                for (int j = width - 1; j >= 0; j--) {
                    if (key.equals(b[bFrom + j])) {
                        cur[j] = next[j + 1] + 1;
                    } else {
                        cur[j] = Math.max(next[j], cur[j + 1]);
                    }
                }
                int[] swap = next;
                next = cur;
                cur = swap;
            }
            return next;
        }

        // ---- anchored strategies ---------------------------------------------

        /**
         * Diffs one region by anchoring at rare common lines and diffing the gaps minimally. One
         * pass, no recursion into the gaps: a nested anchor search buys little and makes the depth
         * of the recursion depend on the input.
         */
        void diffAnchored(int aLo, int aHi, int bLo, int bHi, boolean uniqueOnly) {
            while (aLo < aHi && bLo < bHi && a[aLo].equals(b[bLo])) {
                out.add(new Op(Kind.EQUAL, aLo, bLo));
                aLo++;
                bLo++;
            }
            int aEnd = aHi;
            int bEnd = bHi;
            while (aEnd > aLo && bEnd > bLo && a[aEnd - 1].equals(b[bEnd - 1])) {
                aEnd--;
                bEnd--;
            }

            List<int[]> anchors = findAnchors(aLo, aEnd, bLo, bEnd, uniqueOnly);
            if (anchors.isEmpty()) {
                diffMinimal(aLo, aEnd, bLo, bEnd);
            } else {
                int aPos = aLo;
                int bPos = bLo;
                for (int[] anchor : anchors) {
                    diffMinimal(aPos, anchor[0], bPos, anchor[1]);
                    out.add(new Op(Kind.EQUAL, anchor[0], anchor[1]));
                    aPos = anchor[0] + 1;
                    bPos = anchor[1] + 1;
                }
                diffMinimal(aPos, aEnd, bPos, bEnd);
            }

            for (int i = aEnd; i < aHi; i++) {
                out.add(new Op(Kind.EQUAL, i, bEnd + (i - aEnd)));
            }
        }

        /**
         * Finds the anchors of a region: pairs {@code (aIndex, bIndex)} of equally rare keys whose
         * b-indices form an increasing subsequence in a-order.
         *
         * @return anchors in ascending order of both indices
         */
        private List<int[]> findAnchors(int aLo, int aHi, int bLo, int bHi, boolean uniqueOnly) {
            List<int[]> result = new ArrayList<>();
            if (aHi - aLo < 2 || bHi - bLo < 2) {
                return result;
            }
            stamp++;
            countRegion(aId, aLo, aHi, countA, stampA, stamp);
            countRegion(bId, bLo, bHi, countB, stampB, stamp);

            int rarest = Integer.MAX_VALUE;
            for (int i = aLo; i < aHi; i++) {
                int id = aId[i];
                if (stampB[id] != stamp) {
                    continue;
                }
                if (uniqueOnly && (countA[id] != 1 || countB[id] != 1)) {
                    continue;
                }
                int weight = countA[id] + countB[id];
                if (weight < rarest) {
                    rarest = weight;
                }
            }
            if (rarest == Integer.MAX_VALUE) {
                return result;
            }

            // Candidates: first occurrence of every key with the rarest weight, in a-order.
            int[] candidateA = new int[aHi - aLo];
            int[] candidateB = new int[aHi - aLo];
            int count = 0;
            int[] seenA = new int[idCount];
            int[] seenB = new int[idCount];
            for (int i = aLo; i < aHi; i++) {
                int id = aId[i];
                if (seenA[id] != stamp) {
                    seenA[id] = stamp;
                } else {
                    continue;
                }
                if (stampB[id] != stamp || countA[id] + countB[id] != rarest) {
                    continue;
                }
                int bIndex = firstOccurrence(bId, bLo, bHi, id, seenB, stamp);
                if (bIndex < 0) {
                    continue;
                }
                candidateA[count] = i;
                candidateB[count] = bIndex;
                count++;
            }
            if (count == 0) {
                return result;
            }

            int[] lis = longestIncreasing(candidateB, count);
            for (int index : lis) {
                result.add(new int[]{candidateA[index], candidateB[index]});
            }
            return result;
        }

        /** Notes one occurrence of the region count; the first call for a key sets the count. */
        private void countRegion(int[] ids, int from, int to, int[] counts, int[] stamps, int mark) {
            for (int i = from; i < to; i++) {
                int id = ids[i];
                if (stamps[id] != mark) {
                    stamps[id] = mark;
                    counts[id] = 1;
                } else {
                    counts[id]++;
                }
            }
        }

        /** First index of {@code id} in {@code [from, to)}, or -1. */
        private int firstOccurrence(int[] ids, int from, int to, int id, int[] seen, int mark) {
            for (int i = from; i < to; i++) {
                if (ids[i] == id && seen[id] != mark) {
                    seen[id] = mark;
                    return i;
                }
            }
            return -1;
        }

        /**
         * Indices of one longest strictly increasing subsequence of {@code values[0..count)}
         * (patience sorting with back-pointers).
         */
        private static int[] longestIncreasing(int[] values, int count) {
            if (count <= 0) {
                return new int[0];
            }
            int[] tails = new int[count];
            int[] parent = new int[count];
            int length = 0;
            for (int i = 0; i < count; i++) {
                int value = values[i];
                int low = 0;
                int high = length;
                while (low < high) {
                    int mid = (low + high) >>> 1;
                    if (values[tails[mid]] < value) {
                        low = mid + 1;
                    } else {
                        high = mid;
                    }
                }
                parent[i] = low > 0 ? tails[low - 1] : -1;
                tails[low] = i;
                if (low == length) {
                    length++;
                }
            }
            int[] result = new int[length];
            int index = tails[length - 1];
            for (int n = length - 1; n >= 0; n--) {
                result[n] = index;
                index = parent[index];
            }
            return result;
        }
    }
}
