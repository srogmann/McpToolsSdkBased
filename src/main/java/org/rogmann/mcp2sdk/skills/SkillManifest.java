package org.rogmann.mcp2sdk.skills;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The YAML frontmatter of a {@code SKILL.md} file (docs/skills.md §3).
 *
 * <p>Deliberately <em>not</em> parsed with the strict YAML subset of the {@code yaml} JavaScript
 * module: that module rejects flow collections ({@code metadata: {a: b}}) and anchors, and skills
 * come from foreign ecosystems ({@code ~/.claude/skills}, {@code .agents/skills}) where exactly
 * those forms appear. Strictness would turn a foreign file into a hard error while the fields we
 * interpret are two dozen bytes of {@code key: value}. What this reader does reject - loudly, as
 * a problem in the parse result - is everything it does not understand, so nothing is guessed.</p>
 *
 * <p>Interpreted keys:</p>
 * <ul>
 *   <li>{@code name} - display name (defaults to the path-derived id)</li>
 *   <li>{@code description} - summary used to advertise the skill; without it the skill is
 *       registered but not advertised</li>
 *   <li>{@code metadata/opencode/autoinvoke} - {@code false} hides the skill from the advertised
 *       list; wins over {@code disable-model-invocation}</li>
 *   <li>{@code disable-model-invocation} - {@code true} hides the skill from the advertised list</li>
 * </ul>
 *
 * @param name                     display name, {@code null} when absent
 * @param description              one-line summary, {@code null} when absent
 * @param modelInvocationDisabled  {@code true} when the skill must not be advertised
 */
public record SkillManifest(String name, String description, boolean modelInvocationDisabled) {

    /** An empty manifest - the file has no frontmatter at all, which is legal. */
    static final SkillManifest EMPTY = new SkillManifest(null, null, false);

    /**
     * Result of {@link SkillManifest#parse(String)}.
     *
     * @param manifest the interpreted frontmatter (never {@code null})
     * @param body     the file content with the frontmatter block removed
     * @param problems everything the reader did not understand; empty when the file is clean
     */
    public record Parsed(SkillManifest manifest, String body, List<String> problems) {

        /**
         * Defensive copy of the problem list.
         */
        public Parsed {
            problems = List.copyOf(problems);
        }
    }

    /**
     * Parses the frontmatter block of a skill file.
     *
     * @param text the file content
     * @return the manifest, the remaining body and the problems found
     */
    public static Parsed parse(String text) {
        String content = text.startsWith("\uFEFF") ? text.substring(1) : text;
        String[] lines = content.split("\n", -1);
        int first = 0;
        while (first < lines.length && lines[first].isBlank()) {
            first++;
        }
        if (first >= lines.length || !lines[first].strip().equals("---")) {
            return new Parsed(EMPTY, content, List.of());
        }
        int end = -1;
        for (int i = first + 1; i < lines.length; i++) {
            if (lines[i].strip().equals("---")) {
                end = i;
                break;
            }
        }
        if (end < 0) {
            return new Parsed(EMPTY, content, List.of("frontmatter starts with '---' but is never closed"));
        }
        Map<String, String> values = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        readBlock(lines, first + 1, end, values, problems);
        SkillManifest manifest = interpret(values, problems);
        String body = String.join("\n", Arrays.copyOfRange(lines, end + 1, lines.length));
        return new Parsed(manifest, body, problems);
    }

    /**
     * Reads {@code key: value} lines of the frontmatter block, one nested level allowed
     * ({@code metadata:} followed by indented entries).
     */
    private static void readBlock(String[] lines, int from, int to, Map<String, String> values,
                                  List<String> problems) {
        String section = null;
        int i = from;
        while (i < to) {
            String raw = lines[i];
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                i++;
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                problems.add("line " + (i + 1) + ": expected 'key: value', got \"" + line + "\"");
                i++;
                continue;
            }
            String key = line.substring(0, colon).strip();
            String value = line.substring(colon + 1).strip();
            boolean indented = !raw.isEmpty() && Character.isWhitespace(raw.charAt(0));
            String fullKey;
            if (!indented) {
                section = null;
                fullKey = key;
            }
            else if (section == null) {
                problems.add("line " + (i + 1) + ": indented entry without a block header: \""
                        + line + "\"");
                i++;
                continue;
            }
            else {
                fullKey = section + "/" + key;
            }
            if (!indented && value.isEmpty()) {
                section = key;
                i++;
                continue;
            }
            if (isBlockScalarHeader(value)) {
                int next = i + 1;
                List<String> block = new ArrayList<>();
                while (next < to && (lines[next].isBlank()
                        || Character.isWhitespace(lines[next].charAt(0)))) {
                    block.add(lines[next].strip());
                    next++;
                }
                putRaw(values, fullKey, join(block, value.startsWith(">")), i, problems);
                i = next;
                continue;
            }
            putValue(values, fullKey, value, i, problems);
            i++;
        }
    }

    /**
     * Whether a value is a block-scalar header ({@code |}, {@code >} and the chomping variants) -
     * the form foreign skills use for a longer description.
     */
    private static boolean isBlockScalarHeader(String value) {
        if (value.isEmpty() || value.length() > 2) {
            return false;
        }
        char firstChar = value.charAt(0);
        if (firstChar != '|' && firstChar != '>') {
            return false;
        }
        for (int i = 1; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch != '+' && ch != '-') {
                return false;
            }
        }
        return true;
    }

    /** Joins the lines of a block scalar: folded ({@code >}) on one line, literal ({@code |}) as is. */
    private static String join(List<String> lines, boolean folded) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (folded) {
                if (line.isEmpty()) {
                    continue;
                }
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append(line);
            }
            else {
                sb.append(line).append('\n');
            }
        }
        return sb.toString().strip();
    }

    /** Stores one value, refusing duplicates and the styles this reader does not implement. */
    private static void putValue(Map<String, String> values, String key, String value, int line,
                                 List<String> problems) {
        if (value.startsWith("{") || value.startsWith("[")) {
            problems.add("line " + (line + 1) + ": flow style (\"{}\"/\"[]\") is not read here, "
                    + "use a block mapping");
            return;
        }
        if (value.startsWith("|") || value.startsWith(">")) {
            problems.add("line " + (line + 1) + ": block scalar (\"" + value + "\") is not read "
                    + "here, write the value on one line");
            return;
        }
        putRaw(values, key, unquote(value), line, problems);
    }

    /** Stores one value, refusing duplicates (the last definition is never guessed). */
    private static void putRaw(Map<String, String> values, String key, String value, int line,
                               List<String> problems) {
        if (values.containsKey(key)) {
            problems.add("line " + (line + 1) + ": duplicate key \"" + key + "\"");
            return;
        }
        values.put(key, value);
    }

    /** Strips one pair of matching quotes; anything else is taken literally. */
    private static String unquote(String value) {
        if (value.length() >= 2) {
            char firstChar = value.charAt(0);
            char lastChar = value.charAt(value.length() - 1);
            if ((firstChar == '"' || firstChar == '\'') && firstChar == lastChar) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    /** Interprets the keys this registry knows; unknown keys stay unread (and uncomplained). */
    private static SkillManifest interpret(Map<String, String> values, List<String> problems) {
        String name = values.get("name");
        String description = values.get("description");
        Boolean autoinvoke = parseBoolean(values.get("metadata/opencode/autoinvoke"),
                "metadata/opencode/autoinvoke", problems);
        Boolean disableInvocation = parseBoolean(values.get("disable-model-invocation"),
                "disable-model-invocation", problems);
        boolean disabled;
        if (autoinvoke != null) {
            disabled = !autoinvoke;
        }
        else {
            disabled = Boolean.TRUE.equals(disableInvocation);
        }
        return new SkillManifest(name, description, disabled);
    }

    /** Parses {@code true}/{@code false}; anything else is a problem, not a silent default. */
    private static Boolean parseBoolean(String value, String key, List<String> problems) {
        if (value == null) {
            return null;
        }
        if ("true".equalsIgnoreCase(value)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(value)) {
            return Boolean.FALSE;
        }
        problems.add("\"" + key + "\" is not a boolean: \"" + value + "\"");
        return null;
    }
}
