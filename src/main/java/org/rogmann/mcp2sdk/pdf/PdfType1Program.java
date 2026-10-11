package org.rogmann.mcp2sdk.pdf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the <em>names and numbers</em> of a Type 1 program - and executes nothing.
 *
 * <h2>What the file is</h2>
 * A {@code /FontFile} stream is a text header of {@code /Length1} bytes, an encrypted section of
 * {@code /Length2} bytes and, rarely, a third section. The header ends with {@code currentfile
 * eexec}; the four bytes behind it are a seed that is decrypted with the rest and thrown away.
 * What follows is plain PostScript <em>notation</em> again - the cipher hides a format, not a
 * document (Adobe <em>Type 1 Font Format</em>, "Encrypting CharStrings": {@code c = byte ^ (r >> 8)},
 * {@code r = (c + r) * 52845 + 22719} in sixteen bits, start value 55665). A binary
 * {@code .pfb}-style wrapper is split at its own markers ({@code 80 01} text, {@code 80 02}
 * encrypted section), so the same reading works for both shapes.
 *
 * <h2>What is taken, what is left</h2>
 * The reader tokenises (names, numbers, {@code [ ]}, {@code { }}, literal strings, comments) and
 * recognises declarations without evaluating them:
 * <ul>
 *   <li>{@code /FontMatrix}, {@code /FontBBox} - a Type 1 program has no {@code unitsPerEm}: the
 *       matrix <em>is</em> its statement about units, and {@link #scaledFontBBox()} applies it to
 *       the 1000-unit base the dictionary uses;</li>
 *   <li>{@code /FontName}, {@code /FullName}, {@code /lenIV} - the last one only when the file names
 *       it, because the spec's default of 4 is not a fact about this file;</li>
 *   <li>{@code /Encoding} - a base table name, or the array with its
 *       {@code 0 1 255 {1 index exch /.notdef put} for} fill and its {@code dup 65 /A put} entries.
 *       Those entries are the point of this class: they are the font's own statement of which glyph
 *       a one-byte code selects, which makes them step 5 of the mapping chain for a simple font
 *       whose dictionary says nothing (and not a guess from {@code /Flags});</li>
 *   <li>{@code /CharStrings N dict} with its declarations {@code /name LEN RD <LEN bytes> ND}: the
 *       name is read, the {@code LEN} bytes of charstring are <strong>skipped</strong> and counted.
 *       No charstring is decoded, no subr is called - so no width comes out of a Type 1 program and
 *       {@code /Widths} stays the answer (decision 6 of the plan).</li>
 * </ul>
 *
 * <h2>Measured at the fixture (so a test can hold this to it)</h2>
 * {@code latex_sample.pdf} font 4 ({@code /WUVYWP+CMBX12}, object 16, 11 253 bytes,
 * {@code Length1} 1668, {@code Length2} 9585): the decrypted section begins
 * {@code dup /Private 18 dict dup begin}; {@code /FontMatrix [0.001 0 0 0.001 0 0]};
 * {@code /FontBBox {-53 -251 1139 750}}; an {@code /Encoding} array of 256, filled with
 * {@code .notdef}, with 21 {@code dup … put} entries (codes 45…119: {@code 65 → A},
 * {@code 49 → one}); {@code /CharStrings 22 dict}; {@code /Subrs 15 array}; and <em>no</em>
 * {@code /lenIV} - so {@link #lenIV()} is null here although the spec default is 4.
 */
public final class PdfType1Program {

    /** Glyph names listed at most ({@code pdf.status().maxGlyphNames}); counts stay complete. */
    public static final int MAX_GLYPH_NAMES = 1000;

    /** The cipher's start value for the section behind {@code eexec} (T1-SPEC). */
    public static final int EEXEC_KEY = 55665;

    /** How many decrypted bytes the seed swallows before the text begins (T1-SPEC). */
    public static final int SEED_BYTES = 4;

    /** Token cap: a 10 KiB private section is a few thousand tokens; more means a broken file. */
    private static final int MAX_TOKENS = 200_000;

    private final List<String> notes = new ArrayList<>();
    private final Map<Long, String> codeNames = new LinkedHashMap<>();
    private final Set<String> glyphNames = new LinkedHashSet<>();

    private int clearTextBytes;
    private boolean decrypted;
    private Integer lenIV;
    private String fontName;
    private String fullName;
    private double[] fontMatrix;
    private double[] fontBBox;
    private Integer subrsCount;
    private Integer declaredGlyphs;
    private long charstringCount;
    private long charstringBytes;
    private Integer encodingSize;
    private String encodingBase;
    private String encodingFill;
    private long encodingEntries;
    private boolean namesCut;
    private boolean encodingCut;

    private PdfType1Program() {
        // through read()
    }

    // ===================================================================
    // Reading
    // ===================================================================

    /**
     * Reads a Type 1 program.
     *
     * @param data    the decoded {@code /FontFile} stream
     * @param length1 the stream dictionary's {@code /Length1}, may be null
     * @param length2 the stream dictionary's {@code /Length2}, may be null
     * @param length3 the stream dictionary's {@code /Length3}, may be null
     * @return the program - never null; every shortfall is a note, never an exception
     */
    public static PdfType1Program read(byte[] data, Integer length1, Integer length2,
                                       Integer length3) {
        PdfType1Program program = new PdfType1Program();
        if (data == null || data.length == 0) {
            program.notes.add("The Type 1 program is empty: nothing can be read from it");
            return program;
        }
        boolean wrapper = (data[0] & 0xFF) == 0x80 && (data[1] & 0xFF) == 0x01;
        int clearFrom = 0;
        int clearTo = length1 != null && length1 > 0 && length1 <= data.length ? length1
                : Math.min(data.length, 8192);
        if (wrapper) {
            // The wrapper announces its own text section; that number is next to the bytes and
            // therefore better believed than a dictionary entry that could be missing.
            long declared = data.length >= 6 ? u32(data, 2) : -1;
            if (declared > 0 && declared + 6 <= data.length) {
                clearFrom = 6;
                clearTo = (int) (6 + declared);
            }
        }
        program.clearTextBytes = clearTo - clearFrom;
        program.match(iso(data, clearFrom, clearTo));

        int eexec = lastIndexOf(data, "eexec", clearFrom, clearTo);
        if (eexec < 0) {
            program.notes.add("The Type 1 program has no eexec marker"
                    + (length1 != null ? " within its /Length1 of " + length1 + " bytes" : "")
                    + ": its /Encoding and its glyph names stay unread, only the header is read");
            return program;
        }
        int at = skipSpaces(data, eexec + "eexec".length());
        int section = length2 != null && length2 > 0 ? length2 : data.length - at;
        if (wrapper && at + 6 <= data.length && (data[at] & 0xFF) == 0x80
                && (data[at + 1] & 0xFF) == 0x02) {
            section = (int) u32(data, at + 2);
            at += 6;
        }
        if (at + section > data.length) {
            program.notes.add("The program announces " + section + " encrypted bytes at offset " + at
                    + " of a stream of " + data.length + ": the section was cut to "
                    + Math.max(0, data.length - at) + " bytes");
            section = Math.max(0, data.length - at);
        }
        if (section <= SEED_BYTES) {
            program.notes.add("The encrypted section of the Type 1 program is " + section
                    + " bytes, which is not more than the seed: only the header is read");
            return program;
        }
        // The seed is part of the encrypted stream: the cipher's state depends on every byte, so
        // the four bytes are decrypted with the rest and only then dropped. Skipping them while
        // decrypting would desynchronise the chain and give a few kilobytes of noise.
        byte[] decrypted = rDecrypt(data, at, section, EEXEC_KEY);
        byte[] plain = new byte[Math.max(0, decrypted.length - SEED_BYTES)];
        System.arraycopy(decrypted, SEED_BYTES, plain, 0, plain.length);
        program.decrypted = true;
        program.match(iso(plain, 0, plain.length));
        program.notes.add("The " + program.charstringCount
                + " charstrings of this program were skipped by their announced length ("
                + program.charstringBytes + " bytes), not executed: no width and no outline comes"
                + " out of a Type 1 program, so the dictionary's /Widths stays the answer");
        if (length3 != null && length3 > 0) {
            program.notes.add("The program announces a third section of " + length3
                    + " bytes: charstrings, and those are not executed here");
        }
        return program;
    }

    /** Decrypting and encrypting are the same walk - that is what makes the roundtrip testable. */
    public static byte[] rDecrypt(byte[] data, int offset, int length, int key) {
        byte[] out = new byte[Math.max(0, length)];
        int r = key & 0xFFFF;
        for (int i = 0; i < out.length; i++) {
            int c = data[offset + i] & 0xFF;
            out[i] = (byte) (c ^ (r >> 8));
            r = ((c + r) * 52845 + 22719) & 0xFFFF;
        }
        return out;
    }

    /**
     * Tokenises one text section and recognises the declarations in it. {@code RD} is honoured
     * during tokenising, because its charstring is binary: without skipping the announced bytes the
     * rest of the section would be nonsense.
     */
    private void match(String text) {
        List<String> tokens = tokens(text);
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if ("RD".equals(token)) {
                String length = i > 0 ? tokens.get(i - 1) : "";
                String name = i > 1 ? tokens.get(i - 2) : "";
                Long bytes = asLong(length);
                if (bytes != null && name.startsWith("/")) {
                    charstringCount++;
                    charstringBytes += bytes;
                    addGlyphName(name.substring(1));
                }
                continue;
            }
            if ("ND".equals(token) || "NDP".equals(token)) {
                continue;                                    // the definition of that charstring
            }
            if (token.startsWith("/")) {
                declaration(tokens, i);
                continue;
            }
            if ("dup".equals(token) && i + 3 < tokens.size() && "put".equals(tokens.get(i + 3))) {
                Long code = asLong(tokens.get(i + 1));
                String name = tokens.get(i + 2);
                if (code != null && name.startsWith("/")) {
                    addCodeName(code, name.substring(1));
                }
            }
        }
    }

    /** One {@code /Key value …} declaration. Unknown keys are passed over in silence. */
    private void declaration(List<String> tokens, int key) {
        String name = tokens.get(key);
        switch (name) {
            case "/FontMatrix" -> fontMatrix = numbersAfter(tokens, key + 1, 6);
            case "/FontBBox" -> fontBBox = numbersAfter(tokens, key + 1, 4);
            case "/FontName" -> {
                String next = at(tokens, key + 1);
                if (fontName == null && next != null && next.startsWith("/")) {
                    fontName = next.substring(1);
                }
            }
            case "/FullName" -> {
                // The tokeniser keeps a literal string as "(" + content (its own token marker), so
                // the value starts behind it.
                String next = at(tokens, key + 1);
                if (fullName == null && next != null && next.startsWith("(")) {
                    fullName = next.substring(1);
                }
            }
            case "/lenIV" -> {
                Long value = asLong(at(tokens, key + 1));
                if (value != null) {
                    lenIV = (int) (long) value;
                }
            }
            case "/Subrs" -> {
                Long value = asLong(at(tokens, key + 1));
                if (value != null) {
                    subrsCount = (int) (long) value;
                }
            }
            case "/CharStrings" -> {
                Long value = asLong(at(tokens, key + 1));
                if (value != null) {
                    declaredGlyphs = (int) (long) value;
                }
            }
            case "/Encoding" -> encoding(tokens, key);
            default -> {
                // nothing to take
            }
        }
    }

    /** The two shapes of {@code /Encoding}: a base table, or an array with its {@code dup} puts. */
    private void encoding(List<String> tokens, int key) {
        String next = at(tokens, key + 1);
        if (next == null) {
            return;
        }
        if (next.startsWith("/")) {
            if (encodingBase == null) {
                encodingBase = next.substring(1);
            }
            return;
        }
        Long size = asLong(next);
        if (size != null && encodingSize == null) {
            encodingSize = (int) (long) size;
        }
        // the fill:   <lo> <hi> { 1 index exch /.notdef put } for
        for (int i = key + 1; i + 7 < tokens.size() && encodingFill == null; i++) {
            if ("{".equals(tokens.get(i)) && "index".equals(tokens.get(i + 2))
                    && "exch".equals(tokens.get(i + 3)) && tokens.get(i + 4).startsWith("/")) {
                encodingFill = tokens.get(i + 4).substring(1);
            }
        }
    }

    private void addCodeName(long code, String name) {
        encodingEntries++;
        if (codeNames.size() >= MAX_GLYPH_NAMES) {
            encodingCut = true;
            return;
        }
        codeNames.put(code, name);
    }

    private void addGlyphName(String name) {
        if (glyphNames.size() >= MAX_GLYPH_NAMES) {
            namesCut = true;
            return;
        }
        if (!".notdef".equals(name)) {
            glyphNames.add(name);
        }
    }

    // ===================================================================
    // What it says
    // ===================================================================

    /** The {@code /FontName} of the program, subset prefix included. */
    public String fontName() {
        return fontName;
    }

    /** The {@code /FullName} literal, or null. */
    public String fullName() {
        return fullName;
    }

    /** Bytes of text the header gave up. */
    public int clearTextBytes() {
        return clearTextBytes;
    }

    /** True when a section behind {@code eexec} was decrypted. */
    public boolean decrypted() {
        return decrypted;
    }

    /** {@code /lenIV} as the file names it; null when the file does not name it. */
    public Integer lenIV() {
        return lenIV;
    }

    /** The program's own scaling ({@code /FontMatrix}), or null. */
    public double[] fontMatrix() {
        return fontMatrix == null ? null : fontMatrix.clone();
    }

    /** The program's box in its own units ({@code /FontBBox}), or null. */
    public double[] fontBBox() {
        return fontBBox == null ? null : fontBBox.clone();
    }

    /**
     * The box on the 1000-unit base the dictionary uses, through the program's own matrix. A matrix
     * of {@code [0.001 …]} (the fixture's) leaves the numbers as they are; anything else changes
     * them, which is why the matrix is reported next to the box.
     *
     * @return the scaled box, or null when matrix or box are missing
     */
    public double[] scaledFontBBox() {
        if (fontBBox == null || fontMatrix == null || fontMatrix.length < 4) {
            return null;
        }
        double sx = fontMatrix[0];
        double sy = fontMatrix[3];
        return new double[]{round(fontBBox[0] * sx * 1000), round(fontBBox[1] * sy * 1000),
                round(fontBBox[2] * sx * 1000), round(fontBBox[3] * sy * 1000)};
    }

    /** {@code /Subrs N array}, or null. */
    public Integer subrsCount() {
        return subrsCount;
    }

    /** What {@code /CharStrings N dict} announces. */
    public Integer declaredGlyphs() {
        return declaredGlyphs;
    }

    /** Charstring declarations actually found. */
    public long charstringCount() {
        return charstringCount;
    }

    /** Sum of their announced lengths - bytes seen, never interpreted. */
    public long charstringBytes() {
        return charstringBytes;
    }

    /** Glyph names of the charstring declarations (bounded, order of appearance). */
    public List<String> glyphNames(int maxItems) {
        List<String> out = new ArrayList<>();
        for (String name : glyphNames) {
            if (out.size() >= Math.max(1, maxItems)) {
                break;
            }
            out.add(name);
        }
        return out;
    }

    /** True when {@link #glyphNames(int)} could not list all of them. */
    public boolean glyphNamesTruncated() {
        return namesCut;
    }

    /** A base encoding the program names ({@code StandardEncoding}, …), or null. */
    public String encodingBase() {
        return encodingBase;
    }

    /** The name the fill loop gives the codes nobody puts. */
    public String encodingFill() {
        return encodingFill;
    }

    /** Size of the {@code /Encoding} array the program builds. */
    public Integer encodingSize() {
        return encodingSize;
    }

    /** How many {@code dup <code> /name put} entries the program holds (complete count). */
    public long encodingEntries() {
        return encodingEntries;
    }

    /** True when {@link #encodingNames(int)} could not list all entries. */
    public boolean encodingTruncated() {
        return encodingCut;
    }

    /**
     * The glyph a one-byte code selects according to the program's own {@code /Encoding} - step 5 of
     * the chain for a simple font whose dictionary says nothing. {@code .notdef} is no name to
     * resolve and answers null.
     *
     * @param code the code (0…255)
     * @return the glyph name, or null
     */
    public String nameOfCode(long code) {
        String name = codeNames.get(code);
        return name == null || ".notdef".equals(name) ? null : name;
    }

    /** The program's encoding entries, listed (bounded). */
    public List<Map<String, Object>> encodingNames(int maxItems) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<Long, String> entry : codeNames.entrySet()) {
            if (out.size() >= Math.max(1, maxItems)) {
                break;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", entry.getKey());
            item.put("glyph", entry.getValue());
            out.add(item);
        }
        return out;
    }

    /** What this reader noticed. */
    public List<String> notes() {
        return List.copyOf(notes);
    }

    /** The {@code type1} part of a {@code program} block; the caller adds {@code kind}/{@code size}. */
    public Map<String, Object> block(int maxItems) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("read", true);
        out.put("clearTextBytes", (long) clearTextBytes);
        out.put("eexecDecrypted", decrypted);
        if (fontName != null) {
            out.put("programFontName", fontName);
        }
        if (fullName != null) {
            out.put("programFullName", fullName);
        }
        if (lenIV != null) {
            out.put("lenIV", (long) (int) lenIV);
        }
        if (fontMatrix != null) {
            out.put("fontMatrix", numbers(fontMatrix));
        }
        if (fontBBox != null) {
            out.put("fontBBox", numbers(fontBBox));
            double[] scaled = scaledFontBBox();
            if (scaled != null) {
                out.put("fontBBoxScaled", numbers(scaled));
            }
        }
        if (encodingBase != null) {
            out.put("encodingBase", encodingBase);
        }
        if (encodingSize != null) {
            out.put("encodingSize", (long) (int) encodingSize);
        }
        if (encodingFill != null) {
            out.put("encodingFill", encodingFill);
        }
        out.put("encodingEntries", encodingEntries);
        if (!codeNames.isEmpty()) {
            out.put("encodingNames", new ArrayList<Object>(encodingNames(maxItems)));
            if (encodingCut) {
                out.put("encodingNamesTruncated", true);
            }
        }
        if (declaredGlyphs != null) {
            out.put("declaredGlyphs", (long) (int) declaredGlyphs);
        }
        out.put("charstrings", charstringCount);
        out.put("charstringBytes", charstringBytes);
        if (!glyphNames.isEmpty()) {
            out.put("glyphNames", new ArrayList<Object>(glyphNames(maxItems)));
            if (namesCut) {
                out.put("glyphNamesTruncated", true);
            }
        }
        if (subrsCount != null) {
            out.put("subrs", (long) (int) subrsCount);
        }
        return out;
    }

    // ===================================================================
    // Notation helpers
    // ===================================================================

    /**
     * The tokeniser of the Type 1 notation: comments, {@code /name}s, numbers, literal strings
     * (kept without their parentheses, capped), {@code [ ] { } < >} as their own tokens, and words.
     * A charstring behind {@code RD} is skipped by its announced length and replaced by one token,
     * so binary data cannot pose as notation. Package-visible because a test for the notation is
     * easier to read than a test through a whole program.
     */
    static List<String> tokens(String text) {
        List<String> tokens = new ArrayList<>();
        int i = 0;
        int n = text.length();
        while (i < n && tokens.size() < MAX_TOKENS) {
            char c = text.charAt(i);
            if (c <= 0x20) {
                i++;
                continue;
            }
            if (c == '%') {
                while (i < n && text.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/') {
                int start = ++i;
                while (i < n && !isDelimiter(text.charAt(i))) {
                    i++;
                }
                tokens.add("/" + text.substring(start, i));
                continue;
            }
            if (c == '(') {
                int end = skipBalanced(text, i);
                String value = text.substring(Math.min(i + 1, n), Math.max(i + 1, Math.min(end - 1, n)));
                tokens.add("(" + (value.length() > 120 ? value.substring(0, 120) : value));
                i = end;
                continue;
            }

            if (c == '<') {
                int close = text.indexOf('>', i);
                i = close < 0 ? n : close + 1;
                tokens.add("<>");
                continue;
            }
            if (c == '[' || c == ']' || c == '{' || c == '}') {
                tokens.add(String.valueOf(c));
                i++;
                continue;
            }
            if (isNumberStart(c)) {
                int start = i;
                while (i < n && isNumberChar(text.charAt(i))) {
                    i++;
                }
                tokens.add(text.substring(start, i));
                continue;
            }
            int start = i;
            while (i < n && !isDelimiter(text.charAt(i))) {
                i++;
            }
            if (i == start) {
                i++;
                continue;
            }
            String word = text.substring(start, i);
            tokens.add(word);
            if ("RD".equals(word) && tokens.size() >= 2) {
                Long bytes = asLong(tokens.get(tokens.size() - 2));
                if (bytes != null && bytes > 0) {
                    i += (int) Math.min(bytes, n - i);
                    tokens.add("<charstring>");
                }
            }
        }
        return tokens;
    }

    private static String at(List<String> tokens, int index) {
        return index >= 0 && index < tokens.size() ? tokens.get(index) : null;
    }

    /** The numbers behind a key until its closing bracket - {@code [ ]} and {@code { }} both. */
    private static double[] numbersAfter(List<String> tokens, int from, int count) {
        List<Double> values = new ArrayList<>();
        for (int i = from; i < tokens.size() && values.size() < count; i++) {
            String token = tokens.get(i);
            if ("]".equals(token) || "}".equals(token) || "def".equals(token)) {
                break;
            }
            Double value = asDouble(token);
            if (value != null) {
                values.add(value);
            }
        }
        if (values.size() < count) {
            return null;
        }
        double[] out = new double[count];
        for (int i = 0; i < count; i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    private static List<Object> numbers(double[] values) {
        List<Object> out = new ArrayList<>();
        for (double value : values) {
            out.add(value == Math.rint(value) ? (Object) (long) value : (Object) value);
        }
        return out;
    }

    private static Long asLong(String token) {
        Double value = asDouble(token);
        return value == null ? null : (long) (double) value;
    }

    private static Double asDouble(String token) {
        if (token == null || token.isEmpty() || !isNumberStart(token.charAt(0))) {
            return null;
        }
        try {
            return Double.valueOf(token);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isNumberStart(char c) {
        return (c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.';
    }

    private static boolean isNumberChar(char c) {
        return (c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E';
    }

    private static boolean isDelimiter(char c) {
        return c <= 0x20 || c == '/' || c == '(' || c == ')' || c == '<' || c == '>'
                || c == '[' || c == ']' || c == '{' || c == '}' || c == '%';
    }

    private static int skipBalanced(String text, int from) {
        int depth = 0;
        int i = from;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
            i++;
        }
        return text.length();
    }

    private static int lastIndexOf(byte[] data, String word, int from, int to) {
        int found = -1;
        int start = Math.max(0, from);
        for (int i = start; i + word.length() <= Math.min(to, data.length); i++) {
            boolean same = true;
            for (int k = 0; k < word.length(); k++) {
                if (data[i + k] != word.charAt(k)) {
                    same = false;
                    break;
                }
            }
            if (same && (i + word.length() >= data.length || isDelimiter((char) (data[i + word.length()] & 0xFF)))) {
                found = i;
            }
        }
        return found;
    }

    private static int skipSpaces(byte[] data, int from) {
        int i = from;
        while (i < data.length && (data[i] == '\r' || data[i] == '\n' || data[i] == ' '
                || data[i] == '\t' || data[i] == 0)) {
            i++;
        }
        return i;
    }

    private static long u32(byte[] data, int at) {
        return ((long) (data[at] & 0xFF) << 24) | ((long) (data[at + 1] & 0xFF) << 16)
                | ((long) (data[at + 2] & 0xFF) << 8) | (long) (data[at + 3] & 0xFF);
    }

    private static double round(double value) {
        return Math.round(value * 10_000d) / 10_000d;
    }

    /** One byte, one character - so an index into the string is an index into the bytes. */
    private static String iso(byte[] data, int from, int to) {
        int start = Math.max(0, Math.min(from, data.length));
        int end = Math.max(start, Math.min(to, data.length));
        StringBuilder sb = new StringBuilder(end - start);
        for (int i = start; i < end; i++) {
            sb.append((char) (data[i] & 0xFF));
        }
        return sb.toString();
    }
}
