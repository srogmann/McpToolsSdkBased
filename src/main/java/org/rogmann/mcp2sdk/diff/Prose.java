package org.rogmann.mcp2sdk.diff;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Prose handling: which lines are prose (comments and documentation), what the words of a region
 * are, and which places of a change are the actual differences.
 *
 * <p>
 * <b>Why this exists.</b> A paragraph that is re-wrapped differs in every line while saying the
 * same thing, and a paragraph with two new words differs in every line while saying almost the
 * same thing. A line diff can express neither. This class answers the two questions a reader
 * actually has: <em>is this block only re-wrapped?</em> (same word sequence) and <em>which words
 * changed?</em> (change sites).
 * </p>
 *
 * <p>
 * <b>Words, not characters.</b> A token is a run of letters, digits and underscores. Punctuation is
 * therefore not part of the comparison: a re-wrapped paragraph that also gained a comma counts as
 * re-wrapped. That is a deliberate simplification - it makes the common case right and can hide an
 * edit that only touches punctuation, which the help text says.
 * </p>
 */
public final class Prose {

    /** Default number of unchanged words that may sit between two differences of one site. */
    public static final int DEFAULT_MAX_GAP = 1;

    /** Budget for one word comparison; word sequences are short, this is a safety net. */
    private static final long WORD_DIFF_MAX_COST = 4_000_000L;

    /** Languages whose comment syntax is known. */
    private static final Set<String> LANGUAGES = Set.of("python", "yaml", "shell", "java", "c",
            "javascript", "cobol");

    private Prose() {
    }

    /**
     * @param language language name, may be {@code null}
     * @return true if the language is known (and therefore can classify prose)
     */
    public static boolean isKnownLanguage(String language) {
        return language != null && LANGUAGES.contains(language.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * @return the known language names, sorted, for error messages
     */
    public static List<String> languages() {
        List<String> names = new ArrayList<>(LANGUAGES);
        names.sort(String::compareTo);
        return names;
    }

    /**
     * Splits a text into word tokens (runs of letters, digits and underscores).
     *
     * @param text the text, may be {@code null}
     * @return the tokens in order, never {@code null}
     */
    public static String[] words(String text) {
        if (text == null || text.isEmpty()) {
            return new String[0];
        }
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (Character.isLetterOrDigit(cp) || cp == '_') {
                current.appendCodePoint(cp);
            } else if (!current.isEmpty()) {
                tokens.add(current.toString());
                current.setLength(0);
            }
            i += Character.charCount(cp);
        }
        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        return tokens.toArray(new String[0]);
    }

    /**
     * Splits a block of lines into words; the line break counts as a word boundary.
     *
     * @param lines the lines, may be {@code null}
     * @return the tokens in order, never {@code null}
     */
    public static String[] words(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return new String[0];
        }
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            text.append(line).append('\n');
        }
        return words(text.toString());
    }

    /**
     * Classifies every line of a file as prose (comment, docstring, block comment) or not.
     *
     * <p>
     * The classification is stateful: a Python docstring continues over its lines, a C block
     * comment too. That is why it is computed once per side over the whole file and not per hunk -
     * a hunk in the middle of a docstring cannot know it is one.
     * </p>
     *
     * @param index    lines of the file
     * @param language language name, see {@link #languages()}
     * @return one flag per line
     */
    public static boolean[] classify(LineIndex index, String language) {
        boolean[] prose = new boolean[index.size()];
        if (!isKnownLanguage(language)) {
            return prose;
        }
        String lang = language.trim().toLowerCase(Locale.ROOT);
        switch (lang) {
            case "python" -> classifyPython(index, prose);
            case "java", "c", "javascript" -> classifyBlockComment(index, prose);
            case "cobol" -> classifyCobol(index, prose);
            default -> classifyHashComment(index, prose);
        }
        return prose;
    }

    private static void classifyPython(LineIndex index, boolean[] prose) {
        String openQuote = null;
        for (int i = 0; i < index.size(); i++) {
            String trimmed = index.line(i).trim();
            if (openQuote != null) {
                prose[i] = true;
                if (count(trimmed, openQuote) % 2 == 1) {
                    openQuote = null;
                }
            } else if (trimmed.startsWith("#")) {
                prose[i] = true;
            } else if (trimmed.startsWith("\"\"\"") || trimmed.startsWith("'''")) {
                prose[i] = true;
                String quote = trimmed.startsWith("\"\"\"") ? "\"\"\"" : "'''";
                if (count(trimmed, quote) % 2 == 1) {
                    openQuote = quote;
                }
            }
        }
    }

    private static void classifyBlockComment(LineIndex index, boolean[] prose) {
        boolean inComment = false;
        for (int i = 0; i < index.size(); i++) {
            String trimmed = index.line(i).trim();
            if (inComment) {
                prose[i] = true;
                if (trimmed.contains("*/")) {
                    inComment = false;
                }
            } else if (trimmed.startsWith("//")) {
                prose[i] = true;
            } else if (trimmed.startsWith("/*")) {
                prose[i] = true;
                if (!trimmed.contains("*/")) {
                    inComment = true;
                }
            }
        }
    }

    private static void classifyHashComment(LineIndex index, boolean[] prose) {
        for (int i = 0; i < index.size(); i++) {
            if (index.line(i).trim().startsWith("#")) {
                prose[i] = true;
            }
        }
    }

    private static void classifyCobol(LineIndex index, boolean[] prose) {
        for (int i = 0; i < index.size(); i++) {
            String line = index.line(i);
            boolean comment = (line.length() > 6 && line.charAt(6) == '*')
                    || line.trim().startsWith("*>");
            prose[i] = comment;
        }
    }

    private static int count(String text, String needle) {
        int count = 0;
        int at = text.indexOf(needle);
        while (at >= 0) {
            count++;
            at = text.indexOf(needle, at + needle.length());
        }
        return count;
    }

    /**
     * Compares two word sequences and groups the differences into change sites.
     *
     * <p>
     * A site is a maximal run of differing words, bounded by unchanged words; up to
     * {@code maxGap} unchanged words between two differences do not end a site. An empty result
     * means "same words" - the block was only re-wrapped.
     * </p>
     *
     * @param oldWords old tokens
     * @param newWords new tokens
     * @param maxGap   unchanged words that may sit inside one site, at least 0
     * @return the sites in order, never {@code null}
     */
    public static List<DiffResult.Change> sites(String[] oldWords, String[] newWords, int maxGap) {
        List<DiffResult.Change> sites = new ArrayList<>();
        EditScript script = EditScript.compute(oldWords, newWords, EditScript.Algorithm.MINIMAL,
                WORD_DIFF_MAX_COST);
        List<String> deleted = new ArrayList<>();
        List<String> inserted = new ArrayList<>();
        int gap = 0;
        for (EditScript.Op op : script.ops()) {
            switch (op.kind()) {
                case DELETE -> {
                    deleted.add(oldWords[op.oldIndex()]);
                    gap = 0;
                }
                case INSERT -> {
                    inserted.add(newWords[op.newIndex()]);
                    gap = 0;
                }
                case EQUAL -> {
                    gap++;
                    if (gap > Math.max(0, maxGap)) {
                        flush(sites, deleted, inserted);
                    }
                }
            }
        }
        flush(sites, deleted, inserted);
        return sites;
    }

    private static void flush(List<DiffResult.Change> sites, List<String> deleted,
            List<String> inserted) {
        if (!deleted.isEmpty() || !inserted.isEmpty()) {
            sites.add(new DiffResult.Change(String.join(" ", deleted),
                    String.join(" ", inserted)));
        }
        deleted.clear();
        inserted.clear();
    }

    /**
     * Word diff of two single lines, for {@code intraLine: 'word'}.
     *
     * @param oldLine the old line, may be {@code null}
     * @param newLine the new line, may be {@code null}
     * @return the words with their operation, {@code ' '}, {@code '-'} or {@code '+'}
     */
    public static List<DiffResult.Word> wordsOfLine(String oldLine, String newLine) {
        String[] oldWords = words(oldLine);
        String[] newWords = words(newLine);
        EditScript script = EditScript.compute(oldWords, newWords, EditScript.Algorithm.MINIMAL,
                WORD_DIFF_MAX_COST);
        List<DiffResult.Word> result = new ArrayList<>(script.ops().size());
        for (EditScript.Op op : script.ops()) {
            switch (op.kind()) {
                case EQUAL -> result.add(new DiffResult.Word(' ', oldWords[op.oldIndex()]));
                case DELETE -> result.add(new DiffResult.Word('-', oldWords[op.oldIndex()]));
                case INSERT -> result.add(new DiffResult.Word('+', newWords[op.newIndex()]));
            }
        }
        return result;
    }
}
