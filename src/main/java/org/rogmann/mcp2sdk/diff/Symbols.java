package org.rogmann.mcp2sdk.diff;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Estimates which symbol a line belongs to: the {@code def}/{@code class} of Python, the method or
 * type of a brace language, the {@code SECTION} or paragraph of COBOL.
 *
 * <p>
 * <b>An estimate, not a parser.</b> Three scanners cover the target languages because they need no
 * grammar: indentation (Python, YAML), brace counting over a masked line (C, C++, Java, JavaScript)
 * and the Area A column (COBOL, which brings its own block boundaries). A real parser would need a
 * grammar per language and a version per grammar; a scope that is occasionally wrong is worth more
 * to a reader of a diff than no scope at all - as long as it is never invented: a line outside every
 * symbol reports {@code null}.
 * </p>
 *
 * <p>
 * The scan runs once over the whole file per side, because a hunk in the middle of a function
 * cannot know that it is one.
 * </p>
 */
public final class Symbols {

    /** Declaration keywords that introduce a type. */
    private static final Set<String> TYPE_KEYWORDS = Set.of("class", "interface", "enum", "record",
            "struct", "union", "namespace");

    /** Words that look like a call but are control flow. */
    private static final Set<String> NOT_A_NAME = Set.of("if", "for", "while", "switch", "catch",
            "return", "else", "do", "try", "sizeof", "new", "delete", "case", "when", "with");

    private Symbols() {
    }

    /**
     * @param language language name, may be {@code null}
     * @return true if a scanner exists for the language
     */
    public static boolean isKnownLanguage(String language) {
        return language != null && switch (language.trim().toLowerCase(Locale.ROOT)) {
            case "python", "yaml", "java", "c", "javascript", "cobol" -> true;
            default -> false;
        };
    }

    /**
     * Estimates the enclosing symbol of every line.
     *
     * @param index    lines of the file
     * @param language language name
     * @return one scope per line, {@code null} where no symbol encloses the line
     */
    public static DiffResult.Scope[] scopes(LineIndex index, String language) {
        DiffResult.Scope[] scopes = new DiffResult.Scope[index.size()];
        if (!isKnownLanguage(language)) {
            return scopes;
        }
        switch (language.trim().toLowerCase(Locale.ROOT)) {
            case "python" -> indentScopes(index, scopes, false);
            case "yaml" -> indentScopes(index, scopes, true);
            case "cobol" -> cobolScopes(index, scopes);
            default -> braceScopes(index, scopes);
        }
        return scopes;
    }

    // ========================================================================
    // indentation (Python, YAML)
    // ========================================================================

    private static void indentScopes(LineIndex index, DiffResult.Scope[] scopes, boolean yaml) {
        Deque<Integer> indents = new ArrayDeque<>();
        Deque<String> names = new ArrayDeque<>();
        for (int i = 0; i < index.size(); i++) {
            String line = index.line(i);
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                scopes[i] = scope(names);
                continue;
            }
            int indent = indentation(line);
            while (!indents.isEmpty() && indent <= indents.peek()) {
                indents.pop();
                names.pop();
            }
            String name = yaml ? yamlKey(trimmed) : pythonDeclaration(trimmed);
            if (name != null) {
                indents.push(indent);
                names.push(name);
            }
            scopes[i] = scope(names);
        }
    }

    /** {@code def name}, {@code async def name}, {@code class name}. */
    private static String pythonDeclaration(String trimmed) {
        String rest = trimmed.startsWith("async ") ? trimmed.substring(6).trim() : trimmed;
        if (rest.startsWith("def ")) {
            return identifierAfter(rest, 4);
        }
        if (rest.startsWith("class ")) {
            return identifierAfter(rest, 6);
        }
        return null;
    }

    /** A top-level YAML key ({@code name: ...}) counts as a symbol. */
    private static String yamlKey(String trimmed) {
        int colon = trimmed.indexOf(':');
        if (colon <= 0 || trimmed.startsWith("-")) {
            return null;
        }
        String name = trimmed.substring(0, colon).trim();
        return name.matches("[\\w.\\-/]+") ? name : null;
    }

    private static String identifierAfter(String text, int from) {
        int i = from;
        while (i < text.length() && text.charAt(i) == ' ') {
            i++;
        }
        int start = i;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') {
                break;
            }
            i++;
        }
        return i > start ? text.substring(start, i) : null;
    }

    /** Indentation of a line; a tab counts as four columns. */
    private static int indentation(String line) {
        int columns = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ' ') {
                columns++;
            } else if (c == '\t') {
                columns += 4;
            } else {
                break;
            }
        }
        return columns;
    }

    // ========================================================================
    // braces (C, C++, Java, JavaScript)
    // ========================================================================

    private static void braceScopes(LineIndex index, DiffResult.Scope[] scopes) {
        Deque<Integer> targetDepths = new ArrayDeque<>();
        Deque<String> names = new ArrayDeque<>();
        int depth = 0;
        boolean inBlockComment = false;
        for (int i = 0; i < index.size(); i++) {
            String[] masked = mask(index.line(i), inBlockComment);
            inBlockComment = Boolean.parseBoolean(masked[1]);
            String code = masked[0];
            while (!targetDepths.isEmpty() && depth < targetDepths.peek()) {
                targetDepths.pop();
                names.pop();
            }
            int delta = 0;
            for (int c = 0; c < code.length(); c++) {
                char ch = code.charAt(c);
                if (ch == '{') {
                    delta++;
                } else if (ch == '}') {
                    delta--;
                }
            }
            String name = declaration(code.trim());
            if (name != null) {
                String lower = name.toLowerCase(Locale.ROOT);
                if (TYPE_KEYWORDS.contains(lower)) {
                    name = null;
                }
            }
            if (name != null) {
                targetDepths.push(Math.max(depth + 1, depth + delta));
                names.push(name);
            }
            scopes[i] = scope(names);
            depth = Math.max(0, depth + delta);
        }
    }

    /**
     * Names the declaration of a line: a type keyword followed by a name, or the identifier in
     * front of the first parenthesis (which covers {@code void f(...)}, {@code int main(...)} and
     * {@code function f(...)}). Control-flow words are not names.
     */
    private static String declaration(String code) {
        if (code.isEmpty() || code.endsWith(";")) {
            return null;
        }
        int paren = code.indexOf('(');
        String head = paren > 0 ? code.substring(0, paren).trim() : code;
        String[] words = head.split("[\\s\\*]+");
        for (int w = 0; w < words.length; w++) {
            if (!TYPE_KEYWORDS.contains(words[w])) {
                continue;
            }
            for (int n = w + 1; n < words.length; n++) {
                String candidate = stripGeneric(words[n]);
                if (candidate.matches("[A-Za-z_]\\w*")) {
                    return candidate;
                }
            }
        }
        if (paren <= 0) {
            return null;
        }
        String before = code.substring(0, paren).trim();
        int end = before.length();
        int start = end;
        while (start > 0 && (Character.isLetterOrDigit(before.charAt(start - 1))
                || before.charAt(start - 1) == '_')) {
            start--;
        }
        if (start == end) {
            return null;
        }
        String candidate = before.substring(start, end);
        if (NOT_A_NAME.contains(candidate) || Character.isDigit(candidate.charAt(0))) {
            return null;
        }
        return candidate;
    }

    private static String stripGeneric(String word) {
        int angle = word.indexOf('<');
        return angle > 0 ? word.substring(0, angle) : word;
    }

    /**
     * Masks string literals and comments of one line.
     *
     * @param line the source line
     * @param inBlockComment whether the previous line ended inside a block comment
     * @return {@code {maskedLine, stillInBlockComment}}
     */
    private static String[] mask(String line, boolean inBlockComment) {
        StringBuilder out = new StringBuilder(line.length());
        boolean block = inBlockComment;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (block) {
                if (c == '*' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                    block = false;
                    out.append("  ");
                    i++;
                } else {
                    out.append(' ');
                }
                continue;
            }
            if (quote != 0) {
                if (c == '\\' && i + 1 < line.length()) {
                    out.append("  ");
                    i++;
                } else if (c == quote) {
                    quote = 0;
                    out.append(' ');
                } else {
                    out.append(' ');
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                out.append(' ');
            } else if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                while (out.length() < line.length()) {
                    out.append(' ');
                }
                break;
            } else if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '*') {
                block = true;
                out.append("  ");
                i++;
            } else {
                out.append(c);
            }
        }
        return new String[]{out.toString(), Boolean.toString(block)};
    }

    // ========================================================================
    // COBOL (Area A)
    // ========================================================================

    private static void cobolScopes(LineIndex index, DiffResult.Scope[] scopes) {
        String division = null;
        String section = null;
        String paragraph = null;
        boolean free = false;
        for (int i = 0; i < index.size(); i++) {
            String line = index.line(i);
            String trimmed = line.trim();
            if (trimmed.startsWith(">>SOURCE") && trimmed.toUpperCase(Locale.ROOT).contains("FREE")) {
                free = true;
            }
            boolean comment = trimmed.startsWith("*>")
                    || (!free && line.length() > 6 && line.charAt(6) == '*');
            if (!comment && !trimmed.isEmpty()) {
                int column = firstNonBlankColumn(line);
                boolean areaA = free ? column <= 4 : column >= 8 && column <= 11;
                if (areaA) {
                    String upper = trimmed.toUpperCase(Locale.ROOT);
                    if (upper.endsWith(" DIVISION.")) {
                        division = cobolName(trimmed);
                        section = null;
                        paragraph = null;
                    } else if (upper.endsWith(" SECTION.")) {
                        section = cobolName(trimmed);
                        paragraph = null;
                    } else if (trimmed.matches("[A-Za-z0-9-]+\\.")) {
                        paragraph = cobolName(trimmed);
                    }
                }
            }
            scopes[i] = scopeOf(division, section, paragraph);
        }
    }

    /** 1-based column of the first non-blank character, 0 for a blank line. */
    private static int firstNonBlankColumn(String line) {
        for (int i = 0; i < line.length(); i++) {
            if (!Character.isWhitespace(line.charAt(i))) {
                return i + 1;
            }
        }
        return 0;
    }

    private static String cobolName(String trimmed) {
        String name = trimmed.endsWith(".") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        int space = name.indexOf(' ');
        return space > 0 ? name.substring(0, space) : name;
    }

    // ========================================================================
    // helpers
    // ========================================================================

    /** Builds the scope of the innermost name (deque head) with its chain as path. */
    private static DiffResult.Scope scope(Deque<String> names) {
        if (names.isEmpty()) {
            return null;
        }
        List<String> path = new ArrayList<>(names);
        Collections.reverse(path);
        return new DiffResult.Scope(names.peek(), List.copyOf(path));
    }

    private static DiffResult.Scope scopeOf(String division, String section, String paragraph) {
        String name = paragraph != null ? paragraph : section != null ? section : division;
        if (name == null) {
            return null;
        }
        List<String> path = new ArrayList<>(3);
        if (division != null) {
            path.add(division);
        }
        if (section != null) {
            path.add(section);
        }
        if (paragraph != null) {
            path.add(paragraph);
        }
        return new DiffResult.Scope(name, List.copyOf(path));
    }
}
