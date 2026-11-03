package org.rogmann.mcp2sdk.diff;

import java.util.List;

/**
 * Renders a {@link DiffResult} as a unified diff: header, one {@code @@ -a,b +c,d @@} line per
 * hunk, prefixed lines and the {@code \ No newline at end of file} marker where a side ends
 * without a terminator.
 *
 * <p>
 * The output is meant to be applicable: {@code patch -p1} must accept it. That is the difference
 * to the review diff, which is for reading and says so.
 * </p>
 *
 * <p>
 * Two styles are supported. {@link Style#PLAIN} is what {@code diff -u} prints
 * ({@code ---}/{@code +++} and the hunks). {@link Style#GIT} adds the git header
 * ({@code diff --git a/… b/…}, {@code index <old>..<new> <mode>}, {@code new file mode} /
 * {@code deleted file mode}) and renders a side that does not exist as {@code /dev/null}, which is
 * how {@code git apply} learns that a file is created or removed. The hunk bodies are identical in
 * both styles - only the header differs.
 * </p>
 */
public final class UnifiedRenderer {

    /** Which header the patch carries. */
    public enum Style {
        /** {@code ---}/{@code +++} only, like {@code diff -u}. */
        PLAIN,
        /** git's header: {@code diff --git}, {@code index}, {@code new file mode}. */
        GIT
    }

    /** File mode git prints when the caller does not name one. */
    public static final String DEFAULT_MODE = "100644";

    private UnifiedRenderer() {
    }

    /**
     * Renders the result as a plain unified diff ({@code diff -u}).
     *
     * @param result   the result to render
     * @param oldLabel label of the left side, e.g. {@code a/example.txt}
     * @param newLabel label of the right side, e.g. {@code b/example.txt}
     * @return the patch text, empty lines included as context
     */
    public static String render(DiffResult result, String oldLabel, String newLabel) {
        return render(result, oldLabel, newLabel, Style.PLAIN, null, null, null);
    }

    /**
     * Renders the result in one of the supported styles.
     *
     * <p>
     * A side that the result marks as absent ({@link DiffResult.Source#missing()}) is rendered as
     * {@code /dev/null} and gets the git {@code new file mode}/{@code deleted file mode} line. The
     * {@code index} line is printed when both ids are known - the absent side is then filled with
     * zeros, as git does - and omitted otherwise: an invented hash would be worse than a missing
     * line, and {@code git apply} does not need it.
     * </p>
     *
     * @param result   the result to render
     * @param oldLabel label of the left side, e.g. {@code a/example.txt}
     * @param newLabel label of the right side, e.g. {@code b/example.txt}
     * @param style    header style, {@code null} means {@link Style#PLAIN}
     * @param oldId    identity of the old content for the git {@code index} line, or {@code null}
     * @param newId    identity of the new content for the git {@code index} line, or {@code null}
     * @param mode     file mode for the git header, {@code null} means {@link #DEFAULT_MODE}
     * @return the patch text, empty lines included as context
     */
    public static String render(DiffResult result, String oldLabel, String newLabel, Style style,
            String oldId, String newId, String mode) {
        Style effective = style == null ? Style.PLAIN : style;
        String oldName = oldLabel == null ? "a" : oldLabel;
        String newName = newLabel == null ? "b" : newLabel;
        boolean oldAbsent = result.oldSource() != null && result.oldSource().missing();
        boolean newAbsent = result.newSource() != null && result.newSource().missing();
        String fileMode = mode == null || mode.isBlank() ? DEFAULT_MODE : mode;

        StringBuilder out = new StringBuilder();
        if (effective == Style.GIT) {
            out.append("diff --git ").append(oldName).append(' ').append(newName).append('\n');
            if (oldAbsent && !newAbsent) {
                out.append("new file mode ").append(fileMode).append('\n');
            } else if (newAbsent && !oldAbsent) {
                out.append("deleted file mode ").append(fileMode).append('\n');
            }
            appendIndex(out, oldId, newId, oldAbsent, newAbsent, fileMode);
        }
        out.append("--- ").append(oldAbsent ? "/dev/null" : oldName).append('\n');
        out.append("+++ ").append(newAbsent ? "/dev/null" : newName).append('\n');

        LineIndex oldIndex = result.oldIndex();
        LineIndex newIndex = result.newIndex();
        List<DiffResult.Hunk> hunks = result.hunks();
        for (DiffResult.Hunk hunk : hunks) {
            out.append("@@ -").append(hunk.oldStart()).append(',').append(hunk.oldLines())
                    .append(" +").append(hunk.newStart()).append(',').append(hunk.newLines())
                    .append(" @@\n");
            for (DiffResult.Line line : hunk.lines()) {
                out.append(prefix(line.kind())).append(line.text()).append('\n');
                if (isLastLineWithoutNewline(line, oldIndex, newIndex)) {
                    out.append("\\ No newline at end of file\n");
                }
            }
        }
        return out.toString();
    }

    /**
     * Appends git's {@code index <old>..<new> <mode>} line when both ids are known.
     *
     * @param out       the buffer to append to
     * @param oldId     identity of the old content, may be {@code null}
     * @param newId     identity of the new content, may be {@code null}
     * @param oldAbsent true if the old side does not exist
     * @param newAbsent true if the new side does not exist
     * @param mode      the file mode printed after the ids
     */
    private static void appendIndex(StringBuilder out, String oldId, String newId,
            boolean oldAbsent, boolean newAbsent, String mode) {
        String left = oldAbsent ? zeros(newId) : oldId;
        String right = newAbsent ? zeros(oldId) : newId;
        if (left == null || right == null) {
            return;
        }
        out.append("index ").append(left).append("..").append(right).append(' ').append(mode)
                .append('\n');
    }

    /**
     * @param id the known id, may be {@code null}
     * @return an all-zero id of the same length, or {@code null} when no length is known
     */
    private static String zeros(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        return "0".repeat(id.length());
    }

    private static char prefix(EditScript.Kind kind) {
        return switch (kind) {
            case EQUAL -> ' ';
            case DELETE -> '-';
            case INSERT -> '+';
        };
    }

    /** True if the line is the last line of a side and that side has no final terminator. */
    private static boolean isLastLineWithoutNewline(DiffResult.Line line, LineIndex oldIndex,
            LineIndex newIndex) {
        if (line.oldNumber() > 0 && line.oldNumber() == oldIndex.size()
                && !oldIndex.finalNewline()) {
            return true;
        }
        return line.newNumber() > 0 && line.newNumber() == newIndex.size()
                && !newIndex.finalNewline();
    }
}
