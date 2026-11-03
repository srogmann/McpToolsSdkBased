package org.rogmann.mcp2sdk.diff;

import java.util.ArrayList;
import java.util.List;

/**
 * A text split into lines, together with what a diff needs around the lines: the offset of each
 * line (chars and UTF-8 bytes), the kind of line ending, whether the text ends with one and
 * whether a byte order mark was present.
 *
 * <p>
 * The split is done here and nowhere else: a caller that splits on {@code "\n"} itself loses the
 * CR of a CRLF file and the answer to "did the file end with a newline" - which is exactly the
 * difference a diff has to report.
 * </p>
 *
 * <p>
 * Instances are immutable; the arrays are never handed out for modification.
 * </p>
 */
public final class LineIndex {

    /** Kind of line ending: only {@code LF}. */
    public static final String EOL_LF = "lf";
    /** Kind of line ending: only {@code CRLF}. */
    public static final String EOL_CRLF = "crlf";
    /** Kind of line ending: only {@code CR}. */
    public static final String EOL_CR = "cr";
    /** Kind of line ending: more than one kind occurs in the text. */
    public static final String EOL_MIXED = "mixed";
    /** Kind of line ending: the text has no line ending at all. */
    public static final String EOL_NONE = "none";

    /** The lines, without terminators. */
    private final String[] lines;

    /** Char offset of the first character of each line; one extra entry for the end. */
    private final int[] charOffsets;

    /** UTF-8 byte offset of the first byte of each line; one extra entry for the end. */
    private final int[] byteOffsets;

    /** One of the {@code EOL_*} constants. */
    private final String eol;

    /** True if the text ends with a line terminator. */
    private final boolean finalNewline;

    /** True if a leading byte order mark was found and removed. */
    private final boolean bom;

    private LineIndex(String[] lines, int[] charOffsets, int[] byteOffsets, String eol,
            boolean finalNewline, boolean bom) {
        this.lines = lines;
        this.charOffsets = charOffsets;
        this.byteOffsets = byteOffsets;
        this.eol = eol;
        this.finalNewline = finalNewline;
        this.bom = bom;
    }

    /**
     * Splits a text into lines and notes where each line starts.
     *
     * @param text the text, {@code null} is treated as the empty text
     * @return the index, never {@code null}
     */
    public static LineIndex of(String text) {
        if (text == null || text.isEmpty()) {
            return new LineIndex(new String[0], new int[]{0}, new int[]{0}, EOL_NONE, false,
                    false);
        }
        int start = 0;
        boolean bom = false;
        if (text.charAt(0) == '\uFEFF') {
            bom = true;
            start = 1;
        }

        List<String> parts = new ArrayList<>();
        List<Integer> charStarts = new ArrayList<>();
        List<Integer> byteStarts = new ArrayList<>();

        boolean sawLf = false;
        boolean sawCrlf = false;
        boolean sawCr = false;
        int bytes = bom ? 3 : 0;
        int lineStart = start;
        int i = start;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c != '\n' && c != '\r') {
                i++;
                continue;
            }
            int terminatorLength = (c == '\r' && i + 1 < text.length()
                    && text.charAt(i + 1) == '\n') ? 2 : 1;
            if (c == '\n') {
                sawLf = true;
            } else if (terminatorLength == 2) {
                sawCrlf = true;
            } else {
                sawCr = true;
            }
            String line = text.substring(lineStart, i);
            parts.add(line);
            charStarts.add(lineStart);
            byteStarts.add(bytes);
            bytes += utf8Length(line) + terminatorLength;
            i += terminatorLength;
            lineStart = i;
        }
        boolean finalNewline = lineStart == text.length() && !parts.isEmpty();
        if (lineStart < text.length()) {
            String line = text.substring(lineStart);
            parts.add(line);
            charStarts.add(lineStart);
            byteStarts.add(bytes);
            bytes += utf8Length(line);
        }

        int lineCount = parts.size();
        charStarts.add(text.length());
        byteStarts.add(bytes);
        int[] charOffsets = new int[lineCount + 1];
        int[] byteOffsets = new int[lineCount + 1];
        for (int n = 0; n <= lineCount; n++) {
            charOffsets[n] = charStarts.get(n);
            byteOffsets[n] = byteStarts.get(n);
        }
        return new LineIndex(parts.toArray(new String[0]), charOffsets, byteOffsets,
                eolKind(sawLf, sawCrlf, sawCr), finalNewline, bom);
    }

    private static String eolKind(boolean sawLf, boolean sawCrlf, boolean sawCr) {
        int kinds = (sawLf ? 1 : 0) + (sawCrlf ? 1 : 0) + (sawCr ? 1 : 0);
        if (kinds == 0) {
            return EOL_NONE;
        }
        if (kinds > 1) {
            return EOL_MIXED;
        }
        if (sawCrlf) {
            return EOL_CRLF;
        }
        return sawCr ? EOL_CR : EOL_LF;
    }

    /** UTF-8 length of a string, counted per code point (a surrogate pair counts as four bytes). */
    private static int utf8Length(String s) {
        int bytes = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                bytes += 1;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < s.length()
                    && Character.isLowSurrogate(s.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }

    /**
     * @return number of lines
     */
    public int size() {
        return lines.length;
    }

    /**
     * @param index 0-based line index
     * @return the line without its terminator
     */
    public String line(int index) {
        return lines[index];
    }

    /**
     * @param index 0-based line index
     * @return char offset of the first character of the line (a BOM is not part of line 0)
     */
    public int charOffset(int index) {
        return charOffsets[index];
    }

    /**
     * @param index 0-based line index
     * @return UTF-8 byte offset of the first byte of the line
     */
    public int byteOffset(int index) {
        return byteOffsets[index];
    }

    /**
     * @return one of {@link #EOL_LF}, {@link #EOL_CRLF}, {@link #EOL_CR}, {@link #EOL_MIXED},
     *         {@link #EOL_NONE}
     */
    public String eol() {
        return eol;
    }

    /**
     * @return true if the text ends with a line terminator
     */
    public boolean finalNewline() {
        return finalNewline;
    }

    /**
     * @return true if a leading byte order mark was found and removed from the lines
     */
    public boolean bom() {
        return bom;
    }

    /**
     * Creates the comparison keys: the lines themselves. Whitespace and case normalisation (and a
     * caller supplied key function) build on this in a later phase.
     *
     * @return one key per line
     */
    public String[] keys() {
        return lines.clone();
    }
}
