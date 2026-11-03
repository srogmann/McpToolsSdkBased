package org.rogmann.mcp2sdk.diff;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Selects hunks by the symbol they sit in and by their size, so a caller can ask "only the changes
 * in this function, and only where something really moved".
 *
 * <p>
 * The filter is a {@link HunkBuilder.Sink}: hunks it rejects are never built into the result. The
 * counters still describe the whole diff - {@code stats.hunks} counts what was found, {@code
 * stats.kept} what survived the filter - so a filtered result cannot silently look like a small
 * change.
 * </p>
 *
 * <p>
 * Filtering by symbol needs a language, because only then does a hunk know its symbol; an empty
 * result would otherwise look like "no changes there". The bridge therefore rejects that
 * combination instead of returning nothing.
 * </p>
 */
public final class HunkFilter implements HunkBuilder.Sink {

    /** Symbol names to keep; empty keeps every symbol. */
    private final Set<String> symbols;

    /** Minimum number of changed lines a hunk must have; 0 keeps everything. */
    private final int minChangedLines;

    /**
     * @param symbols         symbol names to keep, may be {@code null} or empty for "any"
     * @param minChangedLines minimum number of changed lines, 0 for "any"; a replaced line counts
     *                        once - the larger of deleted and inserted is the number of lines the
     *                        change touches
     */
    public HunkFilter(Collection<String> symbols, int minChangedLines) {
        this.symbols = symbols == null || symbols.isEmpty() ? Set.of() : new LinkedHashSet<>(symbols);
        this.minChangedLines = Math.max(0, minChangedLines);
    }

    /**
     * @return true if this filter keeps only some hunks
     */
    public boolean isActive() {
        return !symbols.isEmpty() || minChangedLines > 0;
    }

    @Override
    public boolean accept(DiffResult.Hunk hunk) {
        if (minChangedLines > 0
                && Math.max(hunk.deletedLines(), hunk.insertedLines()) < minChangedLines) {
            return false;
        }
        if (symbols.isEmpty()) {
            return true;
        }
        DiffResult.Scope scope = hunk.scope();
        return scope != null && symbols.contains(scope.name());
    }
}
