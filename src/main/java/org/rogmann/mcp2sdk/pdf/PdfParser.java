package org.rogmann.mcp2sdk.pdf;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recursive-descent parser for PDF objects (ISO 32000-1 chapter 7) over the raw file bytes.
 * <p>
 * The parser is a cursor over a byte array: it starts at a caller-chosen offset (the
 * cross-reference table says where an object begins) and reads one value. Stream data is
 * <em>not</em> copied while parsing - a stream object becomes a {@link PdfStream} that
 * records the data offset and the declared length, so reading the structure of a 200 MB
 * PDF with embedded images stays cheap.
 * </p>
 *
 * <h3>Value mapping</h3>
 * <table border="1">
 *   <caption>PDF types to Java types</caption>
 *   <tr><th>PDF</th><th>Java</th></tr>
 *   <tr><td>null</td><td>{@code null}</td></tr>
 *   <tr><td>boolean</td><td>{@link Boolean}</td></tr>
 *   <tr><td>integer</td><td>{@link Long}</td></tr>
 *   <tr><td>real number</td><td>{@link Double}</td></tr>
 *   <tr><td>string (literal or hex)</td><td>{@link PdfString} (byte string)</td></tr>
 *   <tr><td>name</td><td>{@link PdfName}</td></tr>
 *   <tr><td>array</td><td>{@code ArrayList<Object>}</td></tr>
 *   <tr><td>dictionary</td><td>{@code LinkedHashMap<String,Object>} (insertion order)</td></tr>
 *   <tr><td>indirect reference</td><td>{@link PdfReference}</td></tr>
 *   <tr><td>stream</td><td>{@link PdfStream}</td></tr>
 * </table>
 *
 * <h3>Robustness (PDFs in the wild are damaged)</h3>
 * <ul>
 *   <li>Whitespace and comments are skipped everywhere a token may appear; the byte offset
 *       of a skipped comment is reported so callers can keep it (content streams).</li>
 *   <li>Unbalanced closing delimiters end the surrounding array/dictionary with a warning
 *       instead of failing the whole file.</li>
 *   <li>A truncated file raises {@link PdfException} mentioning {@code truncated=true}.</li>
 *   <li>Nesting depth is bounded ({@link #MAX_DEPTH}); a crafted file cannot exhaust
 *       the stack.</li>
 *   <li>{@code /Length} is honoured, but a wrong length is detected through the
 *       {@code endstream} marker and reported as a warning (the marker wins).</li>
 * </ul>
 */
public final class PdfParser {

    /** Maximum nesting depth of arrays and dictionaries. */
    public static final int MAX_DEPTH = 100;

    /** Maximum number of items in a single array or dictionary (guard against damage). */
    public static final int MAX_ITEMS = 2_000_000;

    /** Keyword {@code obj}. */
    public static final String KEYWORD_OBJ = "obj";

    /** Keyword {@code endobj}. */
    public static final String KEYWORD_ENDOBJ = "endobj";

    /** Keyword {@code stream}. */
    public static final String KEYWORD_STREAM = "stream";

    /** Keyword {@code endstream}. */
    public static final String KEYWORD_ENDSTREAM = "endstream";

    private final byte[] data;
    private List<String> warnings;
    private int pos;

    /**
     * Creates a parser over the whole file image.
     * @param data raw PDF bytes
     */
    public PdfParser(byte[] data) {
        this(data, 0);
    }

    /**
     * Creates a parser positioned at the given offset.
     * @param data raw PDF bytes
     * @param pos starting offset (clamped to the array)
     */
    public PdfParser(byte[] data, int pos) {
        this.data = data;
        this.pos = Math.max(0, Math.min(pos, data.length));
    }

    /**
     * Collects warnings of one parse operation (optional).
     * @param warnings target list, or {@code null} to ignore warnings
     */
    public void setWarningSink(List<String> warnings) {
        this.warnings = warnings;
    }

    /** Current offset. */
    public int position() {
        return pos;
    }

    /** Repositions the cursor (clamped to the array). */
    public void seek(int newPos) {
        this.pos = Math.max(0, Math.min(newPos, data.length));
    }

    /** The byte at the cursor, -1 at end of file. */
    public int peek() {
        return at(pos);
    }

    /** The underlying file image (read-only use). */
    public byte[] data() {
        return data;
    }

    // -------------------------------------------------------------------
    // Character classes (ISO 32000-1 table 1)
    // -------------------------------------------------------------------

    /**
     * Whether the byte terminates a keyword or number (delimiter set of the spec, plus all
     * bytes below 0x21 which are whitespace in PDF).
     * @param b unsigned byte
     * @return true if the byte is a delimiter
     */
    public static boolean isDelimiterByte(int b) {
        return b <= 0x20 || b == '(' || b == ')' || b == '<' || b == '>' || b == '[' || b == ']'
                || b == '{' || b == '}' || b == '/' || b == '%';
    }

    /**
     * PDF whitespace (0x00, tab, LF, FF, CR, space).
     * @param b unsigned byte
     * @return true if whitespace
     */
    public static boolean isWhitespaceByte(int b) {
        return b == 0x00 || b == 0x09 || b == 0x0A || b == 0x0C || b == 0x0D || b == 0x20;
    }

    /**
     * Hex digit value of a byte, -1 if it is not a hex digit.
     * @param b unsigned byte
     * @return 0..15 or -1
     */
    public static int hexDigit(int b) {
        if (b >= '0' && b <= '9') {
            return b - '0';
        }
        if (b >= 'a' && b <= 'f') {
            return b - 'a' + 10;
        }
        if (b >= 'A' && b <= 'F') {
            return b - 'A' + 10;
        }
        return -1;
    }

    /** Reads a byte without moving the cursor. */
    int at(int index) {
        return (index >= 0 && index < data.length) ? (data[index] & 0xFF) : -1;
    }

    /** Reads a byte and advances the cursor (-1 at end of file). */
    int read() {
        if (pos >= data.length) {
            return -1;
        }
        return data[pos++] & 0xFF;
    }

    /** Skips whitespace only. */
    public void skipWhitespace() {
        while (pos < data.length && isWhitespaceByte(data[pos] & 0xFF)) {
            pos++;
        }
    }

    /**
     * Skips whitespace and comments.
     * @return offset of the first skipped comment, or -1 when nothing was skipped
     */
    public int skipSpaceAndComments() {
        int firstComment = -1;
        while (pos < data.length) {
            int b = data[pos] & 0xFF;
            if (isWhitespaceByte(b)) {
                pos++;
                continue;
            }
            if (b == '%') {
                if (firstComment < 0) {
                    firstComment = pos;
                }
                while (pos < data.length) {
                    int c = data[pos] & 0xFF;
                    pos++;
                    if (c == 0x0A || c == 0x0D) {
                        break;
                    }
                }
                continue;
            }
            break;
        }
        return firstComment;
    }

    /**
     * Whether the given ASCII keyword starts at the cursor (followed by a delimiter).
     * @param keyword ASCII keyword
     * @return true if it matches
     */
    public boolean lookingAt(String keyword) {
        int len = keyword.length();
        if (pos + len > data.length) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            if ((data[pos + i] & 0xFF) != keyword.charAt(i)) {
                return false;
            }
        }
        int after = pos + len;
        return after >= data.length || isDelimiterByte(data[after] & 0xFF);
    }

    /**
     * Skips whitespace/comments and consumes the keyword if present.
     * @param keyword ASCII keyword
     * @return true if consumed
     */
    public boolean matchKeyword(String keyword) {
        skipSpaceAndComments();
        if (!lookingAt(keyword)) {
            return false;
        }
        pos += keyword.length();
        return true;
    }

    /**
     * Finds an ASCII marker in the file image.
     * @param marker ASCII marker
     * @param from start offset (inclusive)
     * @param to end offset (exclusive)
     * @return offset of the first occurrence or -1
     */
    public int indexOf(String marker, int from, int to) {
        byte[] needle = marker.getBytes(StandardCharsets.ISO_8859_1);
        int max = Math.min(to, data.length) - needle.length;
        for (int i = Math.max(0, from); i <= max; i++) {
            int j = 0;
            while (j < needle.length && data[i + j] == needle[j]) {
                j++;
            }
            if (j == needle.length) {
                return i;
            }
        }
        return -1;
    }

    // -------------------------------------------------------------------
    // Object header
    // -------------------------------------------------------------------

    /**
     * Reads an object header {@code num gen obj} at the cursor.
     * @return {@code {objNum, gen}} or {@code null} when no header is there (cursor restored)
     */
    public int[] readObjectHeader() {
        int saved = pos;
        skipSpaceAndComments();
        Long num = tryReadIntegerToken();
        if (num == null) {
            pos = saved;
            return null;
        }
        Long gen = tryReadIntegerToken();
        if (gen == null) {
            pos = saved;
            return null;
        }
        if (!matchKeyword(KEYWORD_OBJ)) {
            pos = saved;
            return null;
        }
        return new int[]{num.intValue(), gen.intValue()};
    }

    /**
     * Reads a plain (non-negative-friendly) integer token, skipping whitespace/comments.
     * @return the value, or {@code null} when the next token is not an integer
     *         (cursor restored)
     */
    public Long tryReadIntegerToken() {
        int saved = pos;
        skipSpaceAndComments();
        int start = pos;
        while (pos < data.length) {
            int b = data[pos] & 0xFF;
            if (b >= '0' && b <= '9') {
                pos++;
            } else {
                break;
            }
        }
        if (pos == start) {
            pos = saved;
            return null;
        }
        try {
            return Long.valueOf(new String(data, start, pos - start, StandardCharsets.ISO_8859_1));
        } catch (NumberFormatException e) {
            pos = saved;
            return null;
        }
    }

    // -------------------------------------------------------------------
    // Values
    // -------------------------------------------------------------------

    /**
     * Parses one PDF value at the cursor.
     * @return the value (see the class documentation for the mapping)
     * @throws PdfException on malformed or truncated input
     */
    public Object parseValue() {
        return parseValue(0);
    }

    /**
     * Parses one PDF value at the cursor.
     * @param depth current nesting depth
     * @return the value
     */
    public Object parseValue(int depth) {
        skipSpaceAndComments();
        int b = peek();
        if (b < 0) {
            throw new PdfException("Unexpected end of file while reading a PDF object "
                    + "(file may be truncated)");
        }
        if (b == '/') {
            return parseName();
        }
        if (b == '(') {
            return parseLiteralString();
        }
        if (b == '[') {
            return parseArray(depth);
        }
        if (b == '<') {
            if (at(pos + 1) == '<') {
                return parseDictionaryOrStream(depth);
            }
            return parseHexString();
        }
        if (b == ']' || b == '>') {
            throw new PdfException("Unexpected '" + (char) b + "' at offset " + pos
                    + " (closing delimiter without an open array/dictionary)");
        }
        return parseTokenValue(depth);
    }

    /**
     * Parses a token that is not a name, string, array or dictionary: numbers, the keywords
     * {@code true}/{@code false}/{@code null} and indirect references.
     */
    private Object parseTokenValue(int depth) {
        int start = pos;
        while (pos < data.length && !isDelimiterByte(data[pos] & 0xFF)) {
            pos++;
        }
        String token = new String(data, start, pos - start, StandardCharsets.ISO_8859_1);
        if (token.isEmpty()) {
            throw new PdfException("Unexpected byte 0x" + Integer.toHexString(peek())
                    + " at offset " + start + " (expected a PDF object)");
        }
        if (token.equals("true")) {
            return Boolean.TRUE;
        }
        if (token.equals("false")) {
            return Boolean.FALSE;
        }
        if (token.equals("null")) {
            return null;
        }
        char first = token.charAt(0);
        boolean numeric = first == '-' || first == '+' || first == '.'
                || (first >= '0' && first <= '9');
        if (!numeric) {
            throw new PdfException("Unexpected keyword '" + token + "' at offset " + start
                    + " (expected a PDF object)");
        }
        Object number = parseNumber(token, start);
        return numberOrReference(number);
    }

    /**
     * Turns {@code 12 0} into a reference when the following token is {@code R}.
     */
    private Object numberOrReference(Object number) {
        if (!(number instanceof Long)) {
            return number;
        }
        int saved = pos;
        Long gen = tryReadIntegerToken();
        if (gen == null || gen < 0 || gen > 65535) {
            pos = saved;
            return number;
        }
        skipSpaceAndComments();
        if (!lookingAt("R")) {
            pos = saved;
            return number;
        }
        long objNum = (Long) number;
        if (objNum < 0 || objNum > Integer.MAX_VALUE) {
            pos = saved;
            return number;
        }
        pos += 1;
        return new PdfReference((int) objNum, gen.intValue());
    }

    /**
     * Parses an integer or real number string (PDF number syntax: {@code 123}, {@code -5},
     * {@code 3.14}, {@code .5}, {@code 6.02}).
     */
    private Object parseNumber(String token, int tokenStart) {
        boolean real = token.indexOf('.') >= 0;
        if (!real) {
            try {
                return Long.valueOf(token);
            } catch (NumberFormatException e) {
                addWarning("Integer '" + token + "' at offset " + tokenStart
                        + " is out of range or malformed, read as a real number");
            }
        }
        try {
            double value = Double.parseDouble(token);
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                throw new NumberFormatException("not finite");
            }
            return value;
            // A real number that Java cannot read stays a real number: 0.0 would be a lie.
        } catch (NumberFormatException e) {
            throw new PdfException("Malformed number '" + token + "' at offset " + tokenStart);
        }
    }

    /**
     * Parses a name object {@code /Name} (with {@code #XX} escapes).
     * @return the name
     */
    public PdfName parseName() {
        int start = pos;
        pos++; // '/'
        ByteArrayOutputStream out = new ByteArrayOutputStream(16);
        while (pos < data.length) {
            int b = data[pos] & 0xFF;
            if (isDelimiterByte(b)) {
                break;
            }
            if (b == '#' && pos + 2 < data.length) {
                int hi = hexDigit(data[pos + 1] & 0xFF);
                int lo = hexDigit(data[pos + 2] & 0xFF);
                if (hi >= 0 && lo >= 0) {
                    out.write((hi << 4) | lo);
                    pos += 3;
                    continue;
                }
            }
            out.write(b);
            pos++;
        }
        if (out.size() == 0) {
            throw new PdfException("Empty name at offset " + start);
        }
        return PdfName.fromRawBytes(out.toByteArray());
    }

    /**
     * Parses a literal string {@code (...)} with the escapes of the spec.
     * @return the string (hex flag false)
     */
    public PdfString parseLiteralString() {
        int start = pos;
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        int depth = 1;
        pos++; // '('
        while (true) {
            if (pos >= data.length) {
                throw new PdfException("Unterminated literal string starting at offset " + start
                        + " (file may be truncated)");
            }
            int b = data[pos++] & 0xFF;
            if (b == '(') {
                depth++;
                out.write(b);
            } else if (b == ')') {
                depth--;
                if (depth == 0) {
                    return new PdfString(out.toByteArray(), false);
                }
                out.write(b);
            } else if (b == '\\') {
                readEscape(out, start);
            } else {
                out.write(b);
            }
        }
    }

    /**
     * Reads one escape sequence of a literal string (the backslash is already consumed).
     */
    private void readEscape(ByteArrayOutputStream out, int stringStart) {
        if (pos >= data.length) {
            throw new PdfException("Unterminated escape sequence in string at offset " + stringStart);
        }
        int b = data[pos++] & 0xFF;
        switch (b) {
            case 'n' -> out.write('\n');
            case 'r' -> out.write('\r');
            case 't' -> out.write('\t');
            case 'b' -> out.write('\b');
            case 'f' -> out.write('\f');
            case '(' -> out.write('(');
            case ')' -> out.write(')');
            case '\\' -> out.write('\\');
            case '\n' -> { /* line continuation: nothing to add */ }
            case '\r' -> {
                if (peek() == '\n') {
                    pos++;
                }
            }
            default -> {
                if (b >= '0' && b <= '7') {
                    int value = b - '0';
                    for (int i = 0; i < 2; i++) {
                        int next = peek();
                        if (next >= '0' && next <= '7') {
                            pos++;
                            value = value * 8 + (next - '0');
                        } else {
                            break;
                        }
                    }
                    out.write(value & 0xFF);
                } else {
                    // Unknown escape: keep the character itself (spec: ignore the backslash).
                    out.write(b);
                }
            }
        }
    }

    /**
     * Parses a hexadecimal string {@code <414243>} (whitespace ignored, a missing fourth
     * digit of the last byte counts as zero).
     * @return the string (hex flag true)
     */
    public PdfString parseHexString() {
        int start = pos;
        ByteArrayOutputStream out = new ByteArrayOutputStream(32);
        pos++; // '<'
        int high = -1;
        while (true) {
            if (pos >= data.length) {
                throw new PdfException("Unterminated hex string starting at offset " + start
                        + " (file may be truncated)");
            }
            int b = data[pos++] & 0xFF;
            if (b == '>') {
                if (high >= 0) {
                    out.write((high << 4) & 0xFF);
                }
                return new PdfString(out.toByteArray(), true);
            }
            int digit = hexDigit(b);
            if (digit < 0) {
                if (isWhitespaceByte(b)) {
                    continue;
                }
                throw new PdfException("Invalid character 0x" + Integer.toHexString(b)
                        + " inside hex string at offset " + (pos - 1));
            }
            if (high < 0) {
                high = digit;
            } else {
                out.write((high << 4) | digit);
                high = -1;
            }
        }
    }

    /**
     * Parses an array {@code [ ... ]}.
     */
    private List<Object> parseArray(int depth) {
        if (depth >= MAX_DEPTH) {
            throw new PdfException("Maximum nesting depth " + MAX_DEPTH + " exceeded at offset "
                    + pos + " (damaged or hostile file)");
        }
        List<Object> list = new ArrayList<>();
        int start = pos;
        pos++; // '['
        while (true) {
            skipSpaceAndComments();   // comments are legal between items
            if (pos >= data.length) {
                throw new PdfException("Unterminated array starting at offset " + start
                        + " (file may be truncated)");
            }
            int b = peek();
            if (b == ']') {
                pos++;
                return list;
            }
            if (b == '>' && at(pos + 1) == '>') {
                addWarning("Expected ']' but found '>>' at offset " + pos
                        + " - the array was closed anyway");
                return list;
            }
            Object item = parseValue(depth + 1);
            list.add(item);
            if (list.size() >= MAX_ITEMS) {
                throw new PdfException("Array at offset " + pos + " exceeds " + MAX_ITEMS
                        + " items (damaged file)");
            }
        }
    }

    /**
     * Parses a dictionary {@code << ... >>} and, when the {@code stream} keyword follows,
     * the stream data of an indirect stream object.
     */
    private Object parseDictionaryOrStream(int depth) {
        if (depth >= MAX_DEPTH) {
            throw new PdfException("Maximum nesting depth " + MAX_DEPTH + " exceeded at offset "
                    + pos + " (damaged or hostile file)");
        }
        Map<String, Object> dict = new LinkedHashMap<>();
        int start = pos;
        pos += 2; // '<<'
        while (true) {
            skipSpaceAndComments();   // comments are legal between entries
            if (pos >= data.length) {
                throw new PdfException("Unterminated dictionary starting at offset " + start
                        + " (file may be truncated)");
            }
            int b = peek();
            if (b == '>' && at(pos + 1) == '>') {
                pos += 2;
                break;
            }
            if (b == ']') {
                addWarning("Expected '>>' but found ']' at offset " + pos
                        + " - the dictionary was closed anyway");
                pos++;
                break;
            }
            if (b != '/') {
                throw new PdfException("Expected a name as dictionary key at offset " + pos
                        + " but found 0x" + Integer.toHexString(b));
            }
            PdfName key = parseName();
            Object value = parseValue(depth + 1);
            if (dict.size() >= MAX_ITEMS) {
                throw new PdfException("Dictionary exceeds " + MAX_ITEMS + " entries (damaged file)");
            }
            if (dict.containsKey(key.value())) {
                addWarning("Duplicate dictionary key /" + key.value() + " at offset " + pos
                        + " - the last value wins (as in PDF)");
            }
            dict.put(key.value(), value);
        }
        int saved = pos;
        skipSpaceAndComments();
        if (lookingAt(KEYWORD_STREAM)) {
            return parseStreamData(dict);
        }
        pos = saved;
        return dict;
    }

    /**
     * Reads the data part of a stream object; the cursor must sit on the {@code stream}
     * keyword. {@code /Length} is honoured, the {@code endstream} marker is the check.
     */
    private PdfStream parseStreamData(Map<String, Object> dict) {
        pos += KEYWORD_STREAM.length();
        long dataOffset = pos;
        // The single line ending after the keyword is not part of the data.
        int b = at((int) dataOffset);
        if (b == 0x0D) {
            dataOffset++;
            if (at((int) dataOffset) == 0x0A) {
                dataOffset++;
            }
        } else if (b == 0x0A) {
            dataOffset++;
        }
        Object rawLength = dict.get("Length");
        Long declared = plainLength(rawLength);
        long marker = indexOf(KEYWORD_ENDSTREAM, (int) Math.min(dataOffset, data.length),
                data.length);
        long endOffset;
        boolean approximate;
        if (declared != null && declared >= 0 && dataOffset + declared <= data.length) {
            endOffset = dataOffset + declared;
            approximate = false;
        } else if (marker >= 0) {
            endOffset = trimOneEndOfLine(marker, dataOffset);
            approximate = true;
            addWarning(rawLength == null
                    ? "Stream at offset " + dataOffset + " has no /Length: the size was derived"
                        + " from the endstream marker"
                    : "Stream /Length " + rawLength + " (offset " + dataOffset + ") does not fit"
                        + " the file: the size was derived from the endstream marker");
        } else {
            throw new PdfException("Stream at offset " + dataOffset + " has no usable /Length"
                    + " and no endstream marker (file may be truncated)");
        }
        pos = (int) Math.min(endOffset, data.length);
        skipWhitespace();
        if (!matchKeyword(KEYWORD_ENDSTREAM)) {
            if (approximate) {
                throw new PdfException("No endstream marker for the stream at offset " + dataOffset);
            }
            addWarning("Stream at offset " + dataOffset + ": /Length " + declared
                    + " does not match the endstream marker at "
                    + (marker >= 0 ? marker : "n/a") + " - the marker wins");
            endOffset = marker >= 0 ? trimOneEndOfLine(marker, dataOffset) : dataOffset;
            pos = (int) Math.min(endOffset, data.length);
            skipWhitespace();
            if (!matchKeyword(KEYWORD_ENDSTREAM)) {
                throw new PdfException("No endstream marker for the stream at offset " + dataOffset);
            }
        }
        skipWhitespace();
        if (!matchKeyword(KEYWORD_ENDOBJ)) {
            addWarning("Missing endobj after the stream at offset " + dataOffset);
        }
        return new PdfStream(dict, dataOffset, endOffset, rawLength, approximate);
    }

    /**
     * Interprets a {@code /Length} value; indirect lengths are handled by the caller
     * (they need the cross-reference table), a real number is tolerated.
     */
    static Long plainLength(Object rawLength) {
        if (rawLength instanceof Long number) {
            return number;
        }
        if (rawLength instanceof Double real && real >= 0 && !real.isNaN()) {
            return real.longValue();
        }
        return null;
    }

    /**
     * Strips at most one line ending before the {@code endstream} marker.
     */
    private long trimOneEndOfLine(long marker, long dataOffset) {
        long end = marker;
        if (end > dataOffset && at((int) (end - 1)) == 0x0A) {
            end--;
            if (end > dataOffset && at((int) (end - 1)) == 0x0D) {
                end--;
            }
        }
        return end;
    }

    /**
     * Parses an object body that is expected to be a dictionary (used for object streams and
     * other known-dictionary positions).
     * @return the dictionary
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> parseDictionary() {
        Object value = parseValue();
        if (!(value instanceof Map<?, ?> map)) {
            throw new PdfException("Expected a dictionary at offset " + pos + " but found "
                    + kindOf(value));
        }
        return (Map<String, Object>) map;
    }

    /**
     * Determines the PDF type name of a parsed value.
     * @param value parsed value
     * @return one of {@code null}, {@code boolean}, {@code integer}, {@code real},
     *         {@code string}, {@code name}, {@code array}, {@code dictionary},
     *         {@code stream}, {@code reference}
     */
    public static String kindOf(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Long) {
            return "integer";
        }
        if (value instanceof Double || value instanceof Float) {
            return "real";
        }
        if (value instanceof PdfString) {
            return "string";
        }
        if (value instanceof PdfName) {
            return "name";
        }
        if (value instanceof List) {
            return "array";
        }
        if (value instanceof Map) {
            return "dictionary";
        }
        if (value instanceof PdfStream) {
            return "stream";
        }
        if (value instanceof PdfReference) {
            return "reference";
        }
        return value.getClass().getSimpleName();
    }

    /**
     * Adds a degradable problem to the warning sink.
     * @param message warning text
     */
    void addWarning(String message) {
        if (warnings != null) {
            warnings.add(message);
        }
    }
}
