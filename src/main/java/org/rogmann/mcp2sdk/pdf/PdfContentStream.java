package org.rogmann.mcp2sdk.pdf;

import org.rogmann.mcp2sdk.pdf.ops.CountingSink;
import org.rogmann.mcp2sdk.pdf.ops.ListingSink;
import org.rogmann.mcp2sdk.pdf.ops.PdfContentInterpreter;
import org.rogmann.mcp2sdk.pdf.ops.PdfContentSink;
import org.rogmann.mcp2sdk.pdf.ops.TextSink;
import org.rogmann.mcp2sdk.pdf.ops.UseSink;
import org.rogmann.mcp2sdk.pdf.state.PdfGraphicsState;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses a PDF <em>content stream</em> (ISO 32000-1 section 8): the sequence of operands and
 * operators that draws a page.
 * <p>
 * A content stream is a small postfix language: operands, then the operator that consumes
 * them. This class tokenizes that sequence and offers three views:
 * </p>
 * <ul>
 *   <li>{@link #tokenize(byte[], List)} - the raw tokens, each with its byte offset and the
 *       offset of a comment that precedes it (comments are legal between any two tokens and
 *       are kept, because in hand-written or repaired PDFs they often explain the drawing),</li>
 *   <li>{@link #analyse(byte[], int)} - a readable listing plus an operator histogram, which
 *       tells at a glance what a page does (how much text, how many images, which colour
 *       operations),</li>
 *   <li>{@link #text(byte[])} - a best-effort text extraction.</li>
 * </ul>
 * <p>
 * Text extraction comes in two flavours, and the difference is exactly the limit a parser cannot
 * cross on its own: a content stream stores <em>glyph codes</em>, and only the font (its encoding,
 * its {@code /ToUnicode} map, its CID layout) says which characters they are.
 * {@link #text(byte[])} reads every string by the document convention (UTF-16BE after a BOM,
 * PDFDocEncoding otherwise) - the raw view, unchanged since this module could read a page.
 * {@link #text(byte[], PdfFontScope, List)} takes each string's font from the state the walk carried
 * at its operator and asks {@link PdfGlyphMapper} what the codes mean; {@link PdfFontScope} keeps the
 * counts that turn "here is some text" into "this much of it is characters and this much stayed
 * codes", and {@link PdfFont#inventory} reports which fonts are involved either way.
 * </p>
 * <p>
 * Since increment D4 nothing in this class loops over the tokens by hand. The counts come from
 * {@link CountingSink}, the listing from {@link ListingSink}, the text from {@link TextSink} and the
 * use counts from {@link UseSink}, all of them hanging off one {@link PdfContentInterpreter} run - the
 * five hand-written loops of the plan's opening table are one walk with five readers (decision 2).
 * What the sinks deliberately kept is the line and word-gap <em>heuristic</em> they were written with:
 * computing the real displacement is Aufgabe 5, and until it is computed and the two answers have been
 * written down next to each other, an answer must not change because the code around it got tidier.
 * </p>
 */
public final class PdfContentStream {

    /** Upper bound for the number of tokens of one content stream. */
    public static final int MAX_TOKENS = 5_000_000;

    /** Maximum characters of one formatted listing line. */
    private static final int MAX_LINE_CHARS = 400;

    /**
     * One token of a content stream.
     *
     * @param kind {@code operand}, {@code operator} or {@code inlineImage}
     * @param value the operand value, the operator name, or the inline-image summary
     * @param offset byte offset of the token
     * @param commentOffset byte offset of a comment directly in front of the token, else -1
     */
    public record Token(String kind, Object value, int offset, int commentOffset) {

        /** Whether this token is an operand (a value). */
        public boolean isOperand() {
            return "operand".equals(kind);
        }

        /** Whether this token is an operator. */
        public boolean isOperator() {
            return "operator".equals(kind);
        }

        /** Whether this token is an inline image ({@code BI ... ID <data> EI}). */
        public boolean isInlineImage() {
            return "inlineImage".equals(kind);
        }
    }

    private PdfContentStream() {
        // Utility class
    }

    // ===================================================================
    // Tokenizer
    // ===================================================================

    /**
     * Splits a content stream into tokens.
     * @param content the (already decoded) content bytes
     * @param notes note/warning sink (never null)
     * @return the tokens in document order
     */
    public static List<Token> tokenize(byte[] content, List<String> notes) {
        List<Token> tokens = new ArrayList<>();
        PdfParser parser = new PdfParser(content);
        while (true) {
            int commentOffset = parser.skipSpaceAndComments();
            if (parser.position() >= content.length) {
                return tokens;
            }
            if (tokens.size() >= MAX_TOKENS) {
                notes.add("Content stream stopped after " + MAX_TOKENS + " tokens");
                return tokens;
            }
            int start = parser.position();
            int b = parser.peek();
            if (b == ']') {
                parser.read();
                notes.add("Unexpected ']' at offset " + start + " (no open array) - skipped");
                continue;
            }
            if (b == '>' && parser.at(start + 1) == '>') {
                parser.read();
                parser.read();
                notes.add("Unexpected '>>' at offset " + start + " (no open dictionary) - skipped");
                continue;
            }
            if (b == '>') {
                parser.read();
                notes.add("Unexpected '>' at offset " + start + " - skipped");
                continue;
            }
            String word = wordAt(content, start);
            if (!word.isEmpty() && isOperatorWord(word)) {
                parser.seek(start + word.length());
                if (word.equals("BI")) {
                    readInlineImage(parser, content, start, commentOffset, tokens, notes);
                } else {
                    tokens.add(new Token("operator", word, start, commentOffset));
                }
                continue;
            }
            Object value;
            try {
                value = parser.parseValue();
            } catch (PdfException e) {
                notes.add("Content stream could not be parsed beyond offset " + start + ": "
                        + e.getMessage());
                return tokens;
            }
            if (parser.position() <= start) {
                // No progress (should not happen) - avoid an endless loop.
                parser.seek(start + 1);
                notes.add("Skipped an unreadable byte at offset " + start);
                continue;
            }
            tokens.add(new Token("operand", value, start, commentOffset));
        }
    }

    /**
     * Reads an inline image: {@code BI <keys and values> ID <binary data> EI}.
     */
    private static void readInlineImage(PdfParser parser, byte[] content, int start,
                                        int commentOffset, List<Token> tokens, List<String> notes) {
        Map<String, Object> dict = new LinkedHashMap<>();
        while (true) {
            parser.skipSpaceAndComments();
            if (parser.position() >= content.length) {
                notes.add("Inline image at offset " + start + " has no ID operator"
                        + " - the rest of the stream was skipped");
                tokens.add(new Token("inlineImage", inlineImageInfo(dict, -1, -1), start,
                        commentOffset));
                return;
            }
            if (parser.peek() == '/') {
                PdfName key = parser.parseName();
                Object value = parser.parseValue();
                dict.put(key.value(), value);
                continue;
            }
            int wordStart = parser.position();
            String word = wordAt(content, wordStart);
            parser.seek(wordStart + word.length());
            if (word.equals("ID")) {
                break;
            }
            notes.add("Unexpected keyword '" + word + "' inside the inline-image dictionary at"
                    + " offset " + wordStart + " - the image dictionary ends here");
            break;
        }
        int dataStart = parser.position();
        int first = parser.at(dataStart);
        if (first == 0x0D) {
            dataStart++;
            if (parser.at(dataStart) == 0x0A) {
                dataStart++;
            }
        } else if (first == 0x0A) {
            dataStart++;
        }
        int endMarker = PdfFileStructure.indexOfDelimited(content, "EI", dataStart, content.length);
        if (endMarker < 0) {
            notes.add("Inline image at offset " + start + " has no EI terminator; "
                    + (content.length - dataStart) + " data bytes were skipped");
            parser.seek(content.length);
            tokens.add(new Token("inlineImage", inlineImageInfo(dict, -1, -1), start, commentOffset));
            return;
        }
        parser.seek(endMarker + 2);
        tokens.add(new Token("inlineImage", inlineImageInfo(dict, endMarker - dataStart,
                sampleBytes(content, dataStart, endMarker)), start, commentOffset));
        tokens.add(new Token("operator", "EI", endMarker, -1));
    }

    /**
     * The bytes of an inline image that are <em>its own data</em>, as opposed to
     * {@code dataBytes} — the length of what the stream holds between the two delimiters.
     *
     * <p>
     * 4.10.4, S. 204: "There must be a white-space deliverer both before and after EI". The one in
     * front of {@code EI} belongs to the delimiter, not to the picture, and is subtracted here; the
     * one behind {@code ID} never entered {@code dataBytes} at all (the reader above steps over the
     * end-of-line that follows {@code ID}). What a producer wrote <em>beyond</em> the single
     * required character stays counted: a trailing byte of image data and a second newline cannot
     * be told apart without decoding the sample stream, and decoding is not this module's question
     * (the plan's P5 — "sampleBytes is only 'what stood between the delimiters'").
     * </p>
     *
     * @param content   the stream's bytes
     * @param dataStart first byte of the data (after the end-of-line behind {@code ID})
     * @param endMarker offset of the {@code EI} keyword
     * @return the data length without the mandatory white-space deliverer in front of {@code EI}
     */
    private static long sampleBytes(byte[] content, int dataStart, int endMarker) {
        long stored = (long) endMarker - dataStart;
        if (stored <= 0) {
            return stored;
        }
        return isWhiteByte(content[endMarker - 1]) ? stored - 1 : stored;
    }

    /** Whether a byte is one of the white-space characters of 7.2 (Table 1). */
    private static boolean isWhiteByte(byte value) {
        int b = value & 0xFF;
        return b == 0x00 || b == 0x09 || b == 0x0A || b == 0x0C || b == 0x0D || b == 0x20;
    }

    /**
     * The folded value of one inline image — the operand {@code pdf.operations()} has always shown
     * at its {@code EI}.
     *
     * @param dict        the image dictionary as the keys between {@code BI} and {@code ID} wrote it
     * @param dataBytes   bytes between the delimiters, delimiters included; negative when unterminated
     * @param sampleBytes the same without the white-space deliverer in front of {@code EI}
     */
    private static Map<String, Object> inlineImageInfo(Map<String, Object> dict, long dataBytes,
                                                       long sampleBytes) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("dict", dict);
        if (dataBytes >= 0) {
            info.put("dataBytes", dataBytes);
            info.put("sampleBytes", sampleBytes);
        } else {
            info.put("unterminated", true);
        }
        return info;
    }

    /**
     * The regular-character run at the given offset (a keyword, number or bare word).
     */
    private static String wordAt(byte[] data, int offset) {
        int end = offset;
        while (end < data.length && !PdfParser.isDelimiterByte(data[end] & 0xFF)) {
            end++;
        }
        return new String(data, offset, end - offset, StandardCharsets.ISO_8859_1);
    }

    /**
     * Whether a bare word is an operator rather than a number or one of the keywords
     * {@code true}/{@code false}/{@code null}.
     * @param word the word
     * @return true if it must be an operator
     */
    public static boolean isOperatorWord(String word) {
        if (word.isEmpty()) {
            return false;
        }
        if (word.equals("true") || word.equals("false") || word.equals("null")) {
            return false;
        }
        char first = word.charAt(0);
        return !(first == '-' || first == '+' || first == '.' || (first >= '0' && first <= '9'));
    }

    // ===================================================================
    // Listing and histogram
    // ===================================================================

    /**
     * Analyses a content stream: readable listing (first operations), operator histogram and
     * the token count.
     * <p>
     * Since increment D3 the numbers come out of {@link PdfContentInterpreter} through
     * {@link CountingSink} — the same walk that now keeps the graphics state, the path and the clip.
     * The answer itself is unchanged, on purpose: this histogram is the oracle the plan uses to
     * prove that one interpreter can replace five hand-written loops (decision 2), so a change here
     * is a finding to be explained, never a side effect.
     * </p>
     * @param content the decoded content bytes
     * @param histogramLimit number of operators in the histogram (0 = none)
     * @return analysis map
     */
    public static Map<String, Object> analyse(byte[] content, int histogramLimit) {
        return analyse(content, histogramLimit, 0);
    }

    /**
     * Same, with an operator budget.
     *
     * @param content the decoded content bytes
     * @param histogramLimit number of operators in the histogram (0 = none)
     * @param maxOperators operators walked at most (0 = the tokenizer's own budget, i.e. no practical
     *                     limit — the default exists so that no listing is shorter than it was before
     *                     budgets existed)
     * @return analysis map
     */
    public static Map<String, Object> analyse(byte[] content, int histogramLimit, int maxOperators) {
        List<String> notes = new ArrayList<>();
        CountingSink sink = new CountingSink();
        PdfContentInterpreter.run(content,
                PdfContentInterpreter.Options.defaults().withMaxOperators(maxOperators), notes, sink);
        return countedMap(sink, notes, histogramLimit);
    }

    /**
     * The two answers of this class from <b>one</b> walk: the counts of {@link #analyse(byte[], int)}
     * and the listing of {@link #operations(byte[], int, int)}.
     * <p>
     * {@code pdf.operations()} needs both, and asking for them one after the other would walk the
     * stream twice — a second full price for a second opinion, which is exactly what this increment's
     * single-walk design was for. Both maps are built by the same private helpers the single-purpose
     * methods use, so the combined answer cannot drift from the two separate ones;
     * {@code PdfContentWalkOracleTest} pins that they are equal.
     * </p>
     *
     * @param content the decoded content bytes
     * @param histogramLimit number of operators in the histogram (0 = none)
     * @param maxItems maximum number of listed operations
     * @param maxOffset only operations at or after this byte offset (0 = from the start)
     * @return the two maps, in the shapes {@code analyse()} and {@code operations()} return
     */
    public static Walked analyseAndList(byte[] content, int histogramLimit, int maxItems,
                                        int maxOffset) {
        List<String> notes = new ArrayList<>();
        CountingSink counting = new CountingSink();
        ListingSink listing = new ListingSink(content, maxItems, maxOffset);
        PdfContentInterpreter.Summary walk = PdfContentInterpreter.run(content,
                PdfContentInterpreter.Options.defaults(), notes,
                PdfContentSink.all(counting, listing));
        // The notes of the stream (tokenizer problems, a budget cut) belong to both answers; the
        // trailing-operand note belongs to the listing only - analyse() has never carried it.
        return new Walked(countedMap(counting, new ArrayList<>(notes), histogramLimit),
                listedMap(listing, notes, walk.trailingOperands()));
    }

    /** The two maps one walk produced — {@link #analyseAndList(byte[], int, int, int)}. */
    public record Walked(Map<String, Object> counted, Map<String, Object> listed) { }

    /** The analysis map, built the one way. */
    private static Map<String, Object> countedMap(CountingSink sink, List<String> notes,
                                                  int histogramLimit) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tokens", sink.tokens());
        result.put("operatorCount", sink.operators());
        result.put("operandCount", sink.operands());
        if (sink.inlineImages() > 0) {
            result.put("inlineImages", sink.inlineImages());
        }
        if (histogramLimit > 0) {
            result.put("histogram", sink.histogram(histogramLimit));
        }
        if (!notes.isEmpty()) {
            result.put("notes", new ArrayList<Object>(notes));
        }
        return result;
    }

    /**
     * Formats the content stream as readable operations, one per element.
     * <p>
     * The listing is {@link ListingSink} on the walk of {@link #analyse(byte[], int)} — one pass,
     * same offsets, same operand texts, same comment attachment as before the interpreter existed.
     * What the walk additionally noticed (an operand underflow, a {@code Q} with an empty stack) is
     * <em>not</em> added to {@code notes}: those are reports about files the old loop never looked at,
     * and they stay in the walk's
     * {@link PdfContentInterpreter.Summary#diagnostics()} — since D7 they reach the caller through
     * {@code pdf.placement()}'s {@code diagnostics} block, the function that asks for a walk, while
     * this listing answers exactly what it answered before the interpreter existed.
     * </p>
     * @param content the decoded content bytes
     * @param maxItems maximum number of lines
     * @param maxOffset only operations at or after this byte offset (0 = from the start)
     * @return {@code {items: [{offset, op, operandsText, commentOffset?}], skipped, notes}}
     */
    public static Map<String, Object> operations(byte[] content, int maxItems, int maxOffset) {
        return operations(content, maxItems, maxOffset, 0);
    }

    /**
     * Same, with an operator budget on top of the listing budget.
     *
     * @param content the decoded content bytes
     * @param maxItems maximum number of lines
     * @param maxOffset only operations at or after this byte offset (0 = from the start)
     * @param maxOperators operators walked at most (0 = no practical limit); a cut says so in
     *                     {@code notes}, because it shortens the answer
     * @return the operations map
     */
    public static Map<String, Object> operations(byte[] content, int maxItems, int maxOffset,
                                                 int maxOperators) {
        List<String> notes = new ArrayList<>();
        ListingSink listing = new ListingSink(content, maxItems, maxOffset);
        PdfContentInterpreter.Summary walk = PdfContentInterpreter.run(content,
                PdfContentInterpreter.Options.defaults().withMaxOperators(maxOperators), notes,
                listing);
        return listedMap(listing, notes, walk.trailingOperands());
    }

    /** The listing map, built the one way — including the note about operands without an operator. */
    private static Map<String, Object> listedMap(ListingSink listing, List<String> notes,
                                                 int trailingOperands) {
        if (trailingOperands > 0) {
            notes.add(trailingOperands + " operand(s) at the end of the stream are not"
                    + " followed by an operator");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", listing.items());
        result.put("skipped", listing.skipped());
        if (!notes.isEmpty()) {
            result.put("notes", new ArrayList<Object>(notes));
        }
        return result;
    }

    /**
     * Reads the text of a comment (without the leading {@code %}).
     * @param data content bytes
     * @param offset offset of the {@code %}
     * @return the comment text
     */
    public static String readComment(byte[] data, int offset) {
        int end = offset + 1;
        while (end < data.length && data[end] != 0x0A && data[end] != 0x0D) {
            end++;
        }
        return PdfString.decodePdfDocEncoding(java.util.Arrays.copyOfRange(data, offset + 1, end));
    }

    /**
     * Renders one operation as PDF source text, e.g. {@code /F1 16 Tf}.
     */
    public static String formatOperation(List<Object> operands, String operator) {
        StringBuilder sb = new StringBuilder();
        for (Object operand : operands) {
            sb.append(formatOperand(operand, 0)).append(' ');
        }
        sb.append(operator);
        if (sb.length() > MAX_LINE_CHARS) {
            return sb.substring(0, MAX_LINE_CHARS) + " …";
        }
        return sb.toString();
    }

    /**
     * Renders a single operand as PDF source text.
     * @param value the operand
     * @param depth current nesting depth (nesting is cut after three levels)
     * @return PDF-ish text
     */
    public static String formatOperand(Object value, int depth) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Boolean bool) {
            return bool ? "true" : "false";
        }
        if (value instanceof Long number) {
            return number.toString();
        }
        if (value instanceof Double number) {
            return formatReal(number);
        }
        if (value instanceof PdfName name) {
            return name.toString();
        }
        if (value instanceof PdfString string) {
            return formatTextLiteral(string);
        }
        if (value instanceof PdfReference reference) {
            return reference.toString();
        }
        if (depth >= 3) {
            return PdfParser.kindOf(value) + "…";
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            int shown = 0;
            for (Object item : list) {
                if (shown >= 24) {
                    sb.append("… ").append(list.size() - shown).append(" more");
                    break;
                }
                sb.append(formatOperand(item, depth + 1)).append(' ');
                shown++;
            }
            sb.append(']');
            return sb.toString();
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("<<");
            int shown = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (shown >= 12) {
                    sb.append(" …").append(map.size() - shown).append(" more");
                    break;
                }
                sb.append('/').append(entry.getKey()).append(' ')
                        .append(formatOperand(entry.getValue(), depth + 1)).append(' ');
                shown++;
            }
            sb.append(">>");
            return sb.toString();
        }
        if (value instanceof PdfStream stream) {
            return "<<stream dict of " + stream.dict().size() + " entries>>";
        }
        if (value instanceof Map.Entry<?, ?>) {
            return String.valueOf(value);
        }
        return String.valueOf(value);
    }

    /**
     * Formats a real number the way a content stream writes it (no exponent, no trailing zeros).
     * @param value the number
     * @return text
     */
    static String formatReal(Double value) {
        if (value == value.longValue() && Math.abs(value) < 1e15) {
            return Long.toString(value.longValue());
        }
        String text = value.toString();
        if (text.contains("E") || text.contains("e")) {
            text = String.format(java.util.Locale.ROOT, "%.6f", value);
        }
        while (text.endsWith("0")) {
            text = text.substring(0, text.length() - 1);
        }
        if (text.endsWith(".")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    /**
     * Renders a string operand as a literal string with the PDF escapes.
     * @param string the string
     * @return {@code (...)} or {@code <hex>} representation
     */
    static String formatTextLiteral(PdfString string) {
        if (string.isHex()) {
            return '<' + string.hex() + '>';
        }
        String text = string.text();
        StringBuilder sb = new StringBuilder(text.length() + 2);
        sb.append('(');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '(' , ')' , '\\' -> sb.append('\\').append(c);
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c > 0x7E) {
                        sb.append(String.format("\\%03o", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append(')');
        return sb.toString();
    }

    // ===================================================================
    // Text extraction
    // ===================================================================

    /**
     * Extracts text from a content stream on a best-effort base, reading every string the way this
     * module has always read a string (the document convention).
     * <p>
     * This is the documented raw view: {@link #text(byte[], PdfFontScope, List)} is the variant that
     * asks each string's font what its codes mean.
     * </p>
     * @param content the decoded content bytes
     * @return the extracted text (possibly empty)
     */
    public static String text(byte[] content) {
        return text(content, null, null, new ArrayList<>());
    }

    /**
     * Extracts text from a content stream <em>through its fonts</em>.
     * <p>
     * A content stream stores glyph codes, and only the font that the {@code Tf} in effect at a
     * text-showing operator names says which characters they are: this variant walks the stream once
     * through {@link PdfContentInterpreter}, takes each string's font from the state at its operator
     * (never from a search backwards through the tokens), resolves each name through {@code scope}
     * (lazily - only the fonts a {@code Tf} really names are ever read) and asks the mapping chain per
     * string. What the chain cannot answer stays out of the text and is counted in the scope, so
     * {@code pdf.text()} can say how much of a page is characters and how much is still codes.
     * </p>
     * @param content the decoded content bytes
     * @param scope   the page's fonts, or null for the document convention
     * @param notes   note/warning sink (may be null, then parse notes are dropped as they always
     *                were)
     * @return the extracted text (possibly empty)
     */
    public static String text(byte[] content, PdfFontScope scope, List<String> notes) {
        return text(content, scope, null, notes);
    }

    /**
     * Extracts text from a content stream through its fonts, starting from a known state.
     * <p>
     * The extra parameter is for a <em>slice</em> of a stream - the text of one marked-content
     * sequence, say. The {@code Tf} that applies to the slice's first string stands outside the slice,
     * so the caller hands over the graphics state its own walk of the whole stream found at the
     * slice's first byte (see {@link org.rogmann.mcp2sdk.pdf.ops.MarkedContentSink}, which is exactly
     * that walk). Since D4 this is a state and not a font name: a slice that opens inside a
     * {@code q/Q} pair then starts where the stream left it, and the {@code q/Q} balance of the slice
     * is the stream's own rather than one this method had to assume.
     * </p>
     * <p>
     * A null {@code initialState} means "start as a page starts": the strings before the first
     * {@code Tf} read by the document convention, and the caller says so in a note.
     * </p>
     * @param content      the decoded content bytes (a whole stream or a slice of one)
     * @param scope        the page's fonts, or null for the document convention
     * @param initialState the state at the first byte of the slice, or null
     * @param notes        note/warning sink (may be null)
     * @return the extracted text (possibly empty)
     */
    public static String text(byte[] content, PdfFontScope scope, PdfGraphicsState initialState,
                              List<String> notes) {
        return textSink(content, scope, initialState, notes).text();
    }

    /**
     * Extracts text the way {@link #text(byte[], PdfFontScope, PdfGraphicsState, List)} does and hands
     * out the sink instead of only the string.
     * <p>
     * One caller needs more than the characters: {@code pdf.text()} also reports what the word-gap
     * rule was given and what it passed over ({@link TextSink#wordGaps()}, increment D7). Kept as its
     * own method rather than folded into {@code text()} because the four callers of {@code text()}
     * want a string and one of them wants the counters — a sink would then be a return value nobody
     * uses in three places. The line logic is the same object either way, so the two answers cannot
     * drift apart.
     * </p>
     * @param content      the decoded content bytes (a whole stream or a slice of one)
     * @param scope        the page's fonts, or null for the document convention
     * @param initialState the state at the first byte of the slice, or null
     * @param notes        note/warning sink (may be null)
     * @return the finished sink — its {@link TextSink#text()} is exactly what {@code text()} returns
     */
    public static TextSink textSink(byte[] content, PdfFontScope scope,
                                    PdfGraphicsState initialState, List<String> notes) {
        List<String> tokenNotes = notes != null ? notes : new ArrayList<>();
        TextSink sink = new TextSink(scope);
        PdfContentInterpreter.run(content,
                PdfContentInterpreter.Options.defaults().withInitialState(initialState),
                tokenNotes, sink);
        return sink;
    }

    /**
     * Which font drew how often - the {@code pages}/{@code showTextOps} of {@code pdf.fonts()} for one
     * stream. {@link org.rogmann.mcp2sdk.pdf.ops.UseSink} on the same walk; see
     * {@link PdfFont#textUsage} for what the page's fonts are credited with.
     *
     * @param content the decoded content bytes
     * @param notes   note/warning sink for stream problems (may be null)
     * @return resource name (with slash) to number of text-showing operators
     */
    public static Map<String, Long> textUsage(byte[] content, List<String> notes) {
        UseSink sink = new UseSink();
        PdfContentInterpreter.run(content, PdfContentInterpreter.Options.defaults(),
                notes != null ? notes : new ArrayList<>(), sink);
        return sink.usage();
    }
}
