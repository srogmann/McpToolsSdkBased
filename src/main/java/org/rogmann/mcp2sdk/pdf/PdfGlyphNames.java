package org.rogmann.mcp2sdk.pdf;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a <em>glyph name</em> into code points - the last real source of the mapping chain in
 * {@link PdfGlyphMapper} (step 5), and the reason a {@code /Differences} array is worth reading.
 * <p>
 * Three rules, in this order:
 * </p>
 * <ol>
 *   <li>the <em>Adobe Glyph List</em> ({@code glyphlist.txt}: {@code aacute;00E1},
 *       {@code bullet;2022}, {@code fi;FB01}) - a name that is listed gets what the list
 *       says;</li>
 *   <li>the <em>name patterns</em> of the AGL specification: {@code uniXXXX} (and
 *       {@code uniXXXXYYYY…}) and {@code uXXXX}..{@code uXXXXXX} carry the code point in the
 *       name itself, so they need no table entry;</li>
 *   <li>the <em>decomposition at the underscore</em>: {@code f_i} is the sequence of {@code f}
 *       and {@code i}. This is not a table lookup - {@code f_i} is in neither the AGL nor the
 *       AGLFN - it is the rule the AGL specification states for composite names, and the only
 *       reading of such a name that does not invent a ligature character.</li>
 * </ol>
 * <p>
 * A name that fits none of them stays unmapped: no U+FFFD, no "closest ASCII", no guess from the
 * font's {@code Flags}. The tables are the ones in {@code src/main/resources/pdf/agl-aglfn}
 * (source and SHA-256 in {@code SOURCE.md} next to them); they are read once per JVM on first
 * need and a missing or unreadable table is reported through {@link #loadError()} rather than
 * quietly answering "unknown name" for all 4 281 of them.
 * </p>
 * <p>
 * A name may mean <em>several</em> characters: 81 entries of the AGL list two to four Unicode
 * values ({@code dalethiriq} is {@code 05D3 05B4}, a letter and its combining mark), and the
 * decomposition rule produces more by definition. So {@link Name#codePoints()} is an array, not a
 * char, exactly like the target of a {@code bfchar} entry in a {@code /ToUnicode} map.
 * </p>
 */
public final class PdfGlyphNames {

    /** Classpath location of the Adobe Glyph List (packaged from {@code src/main/resources}). */
    public static final String AGL_RESOURCE = "/pdf/agl-aglfn/glyphlist.txt";

    /** Classpath location of the ITC Zapf Dingbats list (its built-in encoding, name by name). */
    public static final String ZAPF_RESOURCE = "/pdf/agl-aglfn/zapfdingbats.txt";

    /** How many underscore-separated parts one name may have. */
    public static final int MAX_PARTS = 32;

    /**
     * A resolved glyph name.
     *
     * @param codePoints the characters the name means
     * @param rule       which rule answered: {@code agl} (the list), {@code uni-pattern}
     *                   ({@code uniXXXX…}), {@code u-pattern} ({@code uXXXX…}), {@code underscore}
     *                   (the decomposition), {@code zapfdingbats} (that font's own list)
     */
    public record Name(int[] codePoints, String rule) {
    }

    /** The built-in list, read on first use (never again, never from disk twice). */
    private static volatile PdfGlyphNames agl;
    private static volatile PdfGlyphNames zapf;

    private final Map<String, int[]> table;
    private final int lines;
    private final int rejected;
    private final String loadError;

    private PdfGlyphNames(Map<String, int[]> table, int lines, int rejected, String loadError) {
        this.table = table;
        this.lines = lines;
        this.rejected = rejected;
        this.loadError = loadError;
    }

    /**
     * The Adobe Glyph List from the packaged resource.
     * @return the list - never null; an unreadable resource yields an empty list whose
     *         {@link #loadError()} says so
     */
    public static PdfGlyphNames builtin() {
        PdfGlyphNames local = agl;
        if (local == null) {
            synchronized (PdfGlyphNames.class) {
                local = agl;
                if (local == null) {
                    local = fromResource(AGL_RESOURCE);
                    agl = local;
                }
            }
        }
        return local;
    }

    /**
     * The ITC Zapf Dingbats list - the built-in encoding of that font family, named by the
     * specification, so {@code a100} means U+275E when the dictionary says ZapfDingbats.
     * @return the list - never null
     */
    public static PdfGlyphNames zapfDingbats() {
        PdfGlyphNames local = zapf;
        if (local == null) {
            synchronized (PdfGlyphNames.class) {
                local = zapf;
                if (local == null) {
                    local = fromResource(ZAPF_RESOURCE);
                    zapf = local;
                }
            }
        }
        return local;
    }

    /**
     * Reads a list from the classpath.
     * @param resource classpath location, e.g. {@link #AGL_RESOURCE}
     * @return the list (empty and with {@link #loadError()} set when the resource is not there)
     */
    public static PdfGlyphNames fromResource(String resource) {
        try (InputStream in = PdfGlyphNames.class.getResourceAsStream(resource)) {
            if (in == null) {
                return new PdfGlyphNames(Map.of(), 0, 0, "the glyph list " + resource
                        + " is not in the runtime: glyph names cannot be resolved");
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.ISO_8859_1));
        } catch (IOException e) {
            return new PdfGlyphNames(Map.of(), 0, 0, "the glyph list " + resource
                    + " could not be read (" + e.getMessage()
                    + "): glyph names cannot be resolved");
        }
    }

    /**
     * Parses a list in the {@code name;XXXX} form (comments with {@code #}, blank lines ignored).
     * @param text the file content
     * @return the list
     */
    public static PdfGlyphNames parse(String text) {
        Map<String, int[]> table = new LinkedHashMap<>();
        int lines = 0;
        int rejected = 0;
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            lines++;
            String[] parts = trimmed.split(";");
            if (parts.length < 2) {
                rejected++;
                continue;
            }
            String name = parts[0].trim();
            int[] codePoints = codePointsOfHex(parts[1].trim());
            if (name.isEmpty() || codePoints == null) {
                rejected++;
                continue;
            }
            // A name listed twice keeps its first entry (the lists are sorted; a repeat would be
            // a producer of the file, not of this reader, and "first wins" is the rule the CMap
            // reader uses for the same situation).
            table.putIfAbsent(name, codePoints);
        }
        return new PdfGlyphNames(table, lines, rejected, null);
    }

    /** A list that knows nothing (used when a caller wants patterns only). */
    public static PdfGlyphNames empty() {
        return new PdfGlyphNames(Map.of(), 0, 0, null);
    }

    // ===================================================================
    // Name -> code points
    // ===================================================================

    /**
     * Resolves one glyph name and says which rule answered.
     * @param name the name, with or without its PDF slash
     * @return the answer ({@code rule} is {@code agl}, {@code uni-pattern}, {@code u-pattern} or
     *         {@code underscore}) or null when the name says nothing
     */
    public Name resolve(String name) {
        String bare = bareName(name);
        if (bare.isEmpty()) {
            return null;
        }
        int[] listed = table.get(bare);
        if (listed != null) {
            return new Name(listed.clone(), "agl");
        }
        int[] pattern = fromNamePattern(bare);
        if (pattern != null) {
            return new Name(pattern, bare.startsWith("uni") ? "uni-pattern" : "u-pattern");
        }
        if (bare.indexOf('_') >= 0) {
            int[] parts = decompose(bare);
            return parts == null ? null : new Name(parts, "underscore");
        }
        return null;
    }

    /**
     * Resolves one glyph name.
     * @param name the name, with or without its PDF slash ({@code "/aacute"}, {@code "aacute"})
     * @return the code points, or null when the name says nothing
     */
    public int[] lookup(String name) {
        Name resolved = resolve(name);
        return resolved == null ? null : resolved.codePoints();
    }

    /**
     * Resolves a name through the table only - used when a second list (Zapf Dingbats) has to be
     * consulted for the same name without re-applying the patterns.
     * @param name the name (slash optional)
     * @return the code points or null
     */
    public int[] fromTable(String name) {
        int[] listed = table.get(bareName(name));
        return listed == null ? null : listed.clone();
    }

    /**
     * The name patterns of the AGL specification: {@code uniXXXX}, {@code uniXXXXYYYY…} (groups of
     * four hex digits) and {@code uXXXX}..{@code uXXXXXX}. A surrogate on its own is not a
     * character, so it is answered as "unknown" rather than as half a character.
     * @param name the bare name
     * @return the code points or null
     */
    public static int[] fromNamePattern(String name) {
        String bare = bareName(name);
        if (bare.startsWith("uni") && bare.length() >= 7 && (bare.length() - 3) % 4 == 0) {
            String digits = bare.substring(3);
            if (!isHex(digits)) {
                return null;
            }
            List<Integer> codePoints = new ArrayList<>();
            for (int i = 0; i + 4 <= digits.length(); i += 4) {
                int value = Integer.parseInt(digits.substring(i, i + 4), 16);
                if (!Character.isValidCodePoint(value) || Character.isSurrogate((char) value)
                        || value == 0) {
                    return null;
                }
                codePoints.add(value);
            }
            return toArray(codePoints);
        }
        if (bare.startsWith("u") && bare.length() >= 5 && bare.length() <= 7) {
            String digits = bare.substring(1);
            if (!isHex(digits)) {
                return null;
            }
            int value = Integer.parseInt(digits, 16);
            if (!Character.isValidCodePoint(value) || value == 0
                    || (value <= 0xFFFF && Character.isSurrogate((char) value))) {
                return null;
            }
            return new int[]{value};
        }
        return null;
    }

    /**
     * The decomposition at the underscore: every part must resolve on its own, and the characters
     * are concatenated. A single unknown part makes the whole name unknown - answering "f" for
     * {@code f_unknown} would drop a glyph without saying so.
     * @param name the bare name with at least one underscore
     * @return the code points or null
     */
    public int[] decompose(String name) {
        String bare = bareName(name);
        if (bare.isEmpty()) {
            return null;
        }
        String[] parts = bare.split("_", -1);
        if (parts.length < 2 || parts.length > MAX_PARTS) {
            return null;
        }
        List<Integer> codePoints = new ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty()) {
                return null;
            }
            int[] listed = table.get(part);
            int[] found = listed != null ? listed : fromNamePattern(part);
            if (found == null) {
                return null;
            }
            for (int codePoint : found) {
                codePoints.add(codePoint);
            }
        }
        return toArray(codePoints);
    }

    // ===================================================================
    // What the loader has to say
    // ===================================================================

    /** Names the table holds. */
    public int size() {
        return table.size();
    }

    /** Data lines read (a table that parsed cleanly has {@code size() == lines}). */
    public int lines() {
        return lines;
    }

    /** Lines that were read but not usable. */
    public int rejected() {
        return rejected;
    }

    /** Why the table is empty, or null when it is there. */
    public String loadError() {
        return loadError;
    }

    /** The names of the table, in file order (for tests and for looking one name up by hand). */
    public List<String> names() {
        return List.copyOf(table.keySet());
    }

    // ===================================================================
    // Helpers
    // ===================================================================

    /** The name without its PDF slash. */
    public static String bareName(String name) {
        if (name == null) {
            return "";
        }
        String trimmed = name.trim();
        return trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;
    }

    /**
     * Code points of the value field as the list writes it: one or more four-digit hexadecimal
     * Unicode values, separated by spaces. The multi-value entries are real - {@code dalethiriq}
     * is {@code 05D3 05B4}, a letter plus its combining mark, so a name can legitimately mean more
     * than one character (as {@code f_i} does through the decomposition rule).
     *
     * @param field the second column of a list line
     * @return the code points or null when the field is not a sequence of characters
     */
    static int[] codePointsOfHex(String field) {
        String trimmed = field.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        List<Integer> units = new ArrayList<>();
        for (String group : trimmed.split("\\s+")) {
            if (group.length() % 4 != 0 || group.length() > 8 || !isHex(group)) {
                return null;
            }
            for (int i = 0; i + 4 <= group.length(); i += 4) {
                units.add(Integer.parseInt(group.substring(i, i + 4), 16));
            }
        }
        // The values are Unicode values; two consecutive surrogate units are one supplementary
        // character (the same reading PdfCMap gives a bfchar destination).
        List<Integer> codePoints = new ArrayList<>();
        for (int i = 0; i < units.size(); i++) {
            int unit = units.get(i);
            if (isHighSurrogate(unit)) {
                if (i + 1 < units.size() && isLowSurrogate(units.get(i + 1))) {
                    codePoints.add(Character.toCodePoint((char) unit,
                            (char) units.get(i + 1).intValue()));
                    i++;
                    continue;
                }
                return null;
            }
            if (isLowSurrogate(unit) || unit == 0) {
                return null;
            }
            codePoints.add(unit);
        }
        return codePoints.isEmpty() ? null : toArray(codePoints);
    }

    private static boolean isHighSurrogate(int value) {
        return value >= 0xD800 && value <= 0xDBFF;
    }

    private static boolean isLowSurrogate(int value) {
        return value >= 0xDC00 && value <= 0xDFFF;
    }

    private static boolean isHex(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.digit(text.charAt(i), 16) < 0) {
                return false;
            }
        }
        return !text.isEmpty();
    }

    private static int[] toArray(List<Integer> values) {
        int[] out = new int[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    @Override
    public String toString() {
        return "glyph list (" + table.size() + " names of " + lines + " lines"
                + (rejected > 0 ? ", " + rejected + " unusable" : "")
                + (loadError != null ? ", " + loadError : "") + ")";
    }
}
