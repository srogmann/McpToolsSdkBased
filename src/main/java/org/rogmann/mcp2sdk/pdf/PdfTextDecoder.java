package org.rogmann.mcp2sdk.pdf;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Cuts a byte string into <em>codes</em> and asks {@link PdfGlyphMapper} what each of them means -
 * the step between "the content stream holds these bytes" and "the page says this".
 * <p>
 * The cut is the whole danger of this class, so it takes it from the {@link PdfEncoding} and never
 * decides it itself: {@code /Identity-H} cuts every two bytes, a simple font every one, and an
 * encoding that cannot say (a predefined non-UCS CMap, a {@code /Type0} without {@code /Encoding})
 * leaves the string uncut - reported as it is, with the bytes still in hand, rather than guessed
 * at. A leftover of fewer bytes than one code is a {@link Result#tail()}, not a dropped byte: that
 * is the signature of a broken string or of a wrong code space, and both are statements to make.
 * </p>
 * <p>
 * The result is a list of {@link Item}s (one per code, with its hex, its CID, its GID and the
 * source of its characters) plus the text itself. Keeping both is what makes the answer checkable:
 * a reader can see <em>which</em> code produced <em>which</em> character instead of trusting a
 * string, and a code that stayed a code shows up twice - as {@code unicode: null} in its item and
 * in {@link Result#unmapped()}.
 * </p>
 */
public final class PdfTextDecoder {

    /** Note key of a string whose length is not a whole number of codes. */
    public static final String NOTE_STRING_TAIL = "string-tail";

    /**
     * Note key of a code that ends as a single surrogate half. The key is the plan's catalog key
     * and it predates the general guard: the case was first seen with a UCS-encoded string, but a
     * {@code /ToUnicode} destination cut in the middle shows the same thing, so the guard (and
     * this note) runs on every path - see {@link #hasCharacter(int[])}.
     */
    public static final String NOTE_UNPAIRED_SURROGATE = "ucs-surrogate";

    /**
     * Codes one string is read and listed at most when the caller names no limit of its own
     * ({@code pdf.status().maxGlyphQueries}). A page runs over many strings, so the work of a
     * whole page is bounded separately by {@link PdfFontScope.Budget}.
     */
    public static final int MAX_STRING_CODES = 2_000;

    /** Note key of a listing that the item budget stopped. */
    public static final String NOTE_ITEMS_CUT = "decode-cut";

    /** Note key of a code read as U+0020 without an entry (raised once per string). */
    public static final String NOTE_SPACE_FALLBACK = "space-fallback";

    /**
     * One code of the string.
     *
     * @param index      position in the string (0-based, counted in codes)
     * @param hex        the code as uppercase hex, in the width the code space says
     * @param code       the numeric value of the code (a CID for a CID font)
     * @param gid        the glyph index the CID selects, or null when the font does not say
     * @param codePoints the characters this code means (empty when nothing mapped it)
     * @param unicode    those characters as text, or null (unmapped, or a surrogate half that only
     *                   the other half completes)
     * @param mappedBy   who answered: {@code toUnicode:bfchar}, {@code unicode-code},
     *                   {@code differences:agl}, {@code space-fallback}, … or null
     * @param continues  true when this code is the low half of a surrogate pair whose character is
     *                   shown at the preceding code
     */
    public record Item(int index, String hex, long code, Long gid, int[] codePoints, String unicode,
                       String mappedBy, boolean continues) {
    }

    /**
     * The decoded string.
     *
     * @param items         the codes read (at most the item budget)
     * @param unicode       the text those codes produce
     * @param unmapped      hex of the codes nothing could map (bounded like the items)
     * @param codeBytes     bytes per code, 0 when the encoding could not say
     * @param codes         codes the string holds (all of them, not only the ones read)
     * @param read          codes actually mapped
     * @param mapped        codes that became characters
     * @param unmappedCount codes that stayed codes
     * @param spaceFallback codes read as U+0020 without an entry
     * @param tail          leftover bytes (fewer than one code) as hex, or null
     * @param truncated     true when the item budget stopped the reading
     */
    public record Result(List<Item> items, String unicode, List<String> unmapped, int codeBytes,
                         long codes, long read, long mapped, long unmappedCount, long spaceFallback,
                         String tail, boolean truncated) {
    }

    private PdfTextDecoder() {
        // static access only
    }

    /**
     * Decodes a string in the context of one font.
     * @param bytes    the string bytes (a content stream's literal or hex string)
     * @param mapper   the font's mapping chain
     * @param maxItems codes read and listed (0 = no limit)
     * @param notes    note/warning sink (never null)
     * @return the result - never null; an uncuttable string comes back with its bytes as
     *         {@link Result#tail()} and no items
     */
    public static Result decode(byte[] bytes, PdfGlyphMapper mapper, int maxItems,
                                List<String> notes) {
        byte[] data = bytes == null ? new byte[0] : bytes;
        Integer width = mapper.codeBytes();
        if (width == null) {
            // The encoding cannot say where a code ends; cutting it anyway would invent a text.
            return new Result(List.of(), "", List.of(), 0, 0, 0, 0, 0, 0, PdfCMap.hex(data), false);
        }
        int codeBytes = width;
        List<Item> items = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        long codes = (data.length + codeBytes - 1L) / codeBytes;
        long spaceFallback = 0;
        boolean truncated = false;
        int index = 0;
        for (int offset = 0; offset + codeBytes <= data.length; offset += codeBytes) {
            if (maxItems > 0 && index >= maxItems) {
                truncated = true;
                break;
            }
            byte[] slice = Arrays.copyOfRange(data, offset, offset + codeBytes);
            String hex = PdfCMap.hex(slice);
            long code = value(slice);
            PdfGlyphMapper.Mapping mapping = mapper.map(hex);
            int[] codePoints = mapping == null ? new int[0] : mapping.codePoints();
            String mappedBy = mapping == null ? null : mapping.mappedBy();
            if (PdfGlyphMapper.MAPPED_BY_SPACE_FALLBACK.equals(mappedBy)) {
                spaceFallback++;
            }
            Long gid = mapper.isCidFont() ? mapper.gidOf(code) : null;
            // Half a code point is no character - on every path, not only the UCS one: a producer
            // that cuts a surrogate pair out of a /ToUnicode destination leaves the same half here.
            String shown = hasCharacter(codePoints) ? text(codePoints) : null;
            items.add(new Item(index, hex, code, gid, codePoints, shown, mappedBy, false));
            if (shown != null) {
                text.append(shown);
            }
            index++;
        }

        String tail = null;
        int whole = data.length - data.length % codeBytes;
        if (whole < data.length) {
            tail = PdfCMap.hex(Arrays.copyOfRange(data, whole, data.length));
            addNote(notes, tailSentence(data.length, codeBytes, data.length - whole));
        }

        if (mapper.encoding().isUcs()) {
            List<Item> merged = mergeSurrogates(items);
            if (merged != items) {
                items = merged;
                text.setLength(0);
                for (Item item : items) {
                    if (item.unicode() != null && !item.continues()) {
                        text.append(item.unicode());
                    }
                }
            }
        }
        if (truncated) {
            addNote(notes, "only the first " + maxItems + " code(s) of the string were read"
                    + " (maxItems): split.codes counts all of them");
        }
        long halves = 0;
        String firstHalf = null;
        for (Item item : items) {
            if (item.continues() || item.unicode() != null || !hasSurrogate(item.codePoints())) {
                continue;
            }
            halves++;
            if (firstHalf == null) {
                firstHalf = item.hex();
            }
        }
        if (halves > 0) {
            addNote(notes, halves + " code(s) of this string end as a single surrogate half"
                    + " (first: " + firstHalf + "): half a code point is no character, so it stays"
                    + " unmapped instead of becoming text");
        }
        if (spaceFallback > 0) {
            addNote(notes, "code 20 has no mapping and was read as U+0020 "
                    + "(" + spaceFallback + " time(s)): a word gap is the only reading of a space"
                    + " code");
        }
        // Counted from the items that are reported, so that "mapped" always means "this code
        // became a character": a surrogate half or a code the CMap maps to nothing is not a
        // character, whatever the map holds for it.
        long mapped = 0;
        List<String> unmapped = new ArrayList<>();
        for (Item item : items) {
            if (item.continues() || item.unicode() != null) {
                mapped++;
            } else if (maxItems <= 0 || unmapped.size() < maxItems) {
                unmapped.add(item.hex());
            }
        }
        return new Result(List.copyOf(items), text.toString(), List.copyOf(unmapped), codeBytes,
                codes, index, mapped, index - mapped, spaceFallback, tail, truncated);
    }

    /** The sentence for a string that is not a whole number of codes. */
    public static String tailSentence(int bytes, int codeBytes, int leftover) {
        return bytes + " bytes are not a whole number of " + codeBytes + "-byte codes - " + leftover
                + " byte(s) are left over, which means a broken string or a wrong code space";
    }

    /**
     * Joins a UCS high surrogate with the low surrogate behind it into one character. The pair's
     * character is shown at the high code (that is where it belongs in reading order) and the low
     * code stays listed, marked with {@link Item#continues()} - dropping it from the listing would
     * hide a code the string really holds. A half without its pair stays without a character.
     *
     * @param items the items in string order
     * @return the merged list, or the same instance when nothing had to be merged
     */
    private static List<Item> mergeSurrogates(List<Item> items) {
        boolean needed = false;
        for (Item item : items) {
            if (isSurrogateUnit(item.codePoints())) {
                needed = true;
                break;
            }
        }
        if (!needed) {
            return items;
        }
        List<Item> out = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            if (isSurrogateUnit(item.codePoints()) && isHigh(item.codePoints()[0])
                    && i + 1 < items.size()
                    && isSurrogateUnit(items.get(i + 1).codePoints())
                    && isLow(items.get(i + 1).codePoints()[0])) {
                Item next = items.get(i + 1);
                int merged = Character.toCodePoint((char) item.codePoints()[0],
                        (char) next.codePoints()[0]);
                out.add(rebuild(item, new int[]{merged}, ":surrogate-pair", false));
                out.add(rebuild(next, next.codePoints(), ":surrogate-pair", true));
                i++;
                continue;
            }
            if (isSurrogateUnit(item.codePoints())) {
                // A half without its pair: no character, but the code stays listed.
                out.add(rebuild(item, item.codePoints(), "", false));
                continue;
            }
            out.add(item);
        }
        return out;
    }

    /** Rebuilds an item with other code points (and the pair's marker on {@code mappedBy}). */
    private static Item rebuild(Item item, int[] codePoints, String suffix, boolean continues) {
        return new Item(item.index(), item.hex(), item.code(), item.gid(), codePoints,
                !continues && hasCharacter(codePoints) ? text(codePoints) : null,
                item.mappedBy() == null ? null : item.mappedBy() + suffix, continues);
    }

    /**
     * True when the code points can be shown. A surrogate unit may only stand in the text next to
     * its partner (a high directly before a low is one character, written as a Java pair); a unit
     * without its partner is half a code point and would be invalid UTF-16 the moment the answer
     * leaves this process - so it is answered with {@code null} here rather than written anyway.
     */
    private static boolean hasCharacter(int[] codePoints) {
        if (codePoints.length == 0) {
            return false;
        }
        for (int i = 0; i < codePoints.length; i++) {
            int codePoint = codePoints[i];
            if (codePoint > 0xFFFF || !Character.isSurrogate((char) codePoint)) {
                continue;
            }
            if (isHigh(codePoint) && i + 1 < codePoints.length
                    && codePoints[i + 1] <= 0xFFFF && isLow(codePoints[i + 1])) {
                i++;                                                       // the pair is one char
                continue;
            }
            return false;
        }
        return true;
    }

    /** True when the code points hold at least one surrogate unit. */
    private static boolean hasSurrogate(int[] codePoints) {
        for (int codePoint : codePoints) {
            if (codePoint <= 0xFFFF && Character.isSurrogate((char) codePoint)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSurrogateUnit(int[] codePoints) {
        return codePoints.length == 1 && Character.isSurrogate((char) codePoints[0]);
    }

    private static boolean isHigh(int unit) {
        return unit >= 0xD800 && unit <= 0xDBFF;
    }

    private static boolean isLow(int unit) {
        return unit >= 0xDC00 && unit <= 0xDFFF;
    }

    /**
     * Reads a string the way a caller types it: {@code <00480065>} (hex) or {@code (Hi)} (literal,
     * escapes included) - the two forms a content stream uses, read by the module's own parser so
     * that {@code \(} or an octal escape means what it means in a file.
     *
     * @param text what the caller wrote
     * @return the string's raw bytes
     * @throws PdfException when the text is not a PDF string - a bare word is an error and not an
     *                      empty string, because answering "" to a typo is the worst possible
     *                      answer to "what do these bytes mean"
     */
    public static byte[] stringBytes(String text) {
        if (text == null) {
            throw new PdfException("pdf.decodeString: a string is required, written as hex"
                    + " '<00480065>' or literal '(Hi)'");
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return new byte[0];
        }
        char first = trimmed.charAt(0);
        if (first != '<' && first != '(') {
            throw new PdfException("pdf.decodeString: '" + text + "' is not a PDF string - write"
                    + " it as hex '<00480065>' or as a literal string '(Hi)'");
        }
        byte[] raw = trimmed.getBytes(StandardCharsets.ISO_8859_1);
        PdfParser parser = new PdfParser(raw);
        Object value;
        try {
            value = parser.parseValue();
        } catch (PdfException e) {
            throw new PdfException("pdf.decodeString: the string '" + text + "' cannot be read ("
                    + e.getMessage() + ")");
        }
        if (!(value instanceof PdfString string)) {
            throw new PdfException("pdf.decodeString: '" + text + "' reads as "
                    + PdfParser.kindOf(value) + ", not as a string");
        }
        parser.skipSpaceAndComments();
        if (parser.position() < raw.length) {
            throw new PdfException("pdf.decodeString: '" + text + "' holds more than one value - a"
                    + " single string is expected");
        }
        return string.bytes();
    }

    /** Code points as text; a value Java cannot show reads as the replacement char. */
    private static String text(int[] codePoints) {
        StringBuilder sb = new StringBuilder(codePoints.length);
        for (int codePoint : codePoints) {
            sb.appendCodePoint(Character.isValidCodePoint(codePoint) ? codePoint : 0xFFFD);
        }
        return sb.toString();
    }

    /** One sentence per statement (the same cut is said once, wherever it is found). */
    private static void addNote(List<String> notes, String sentence) {
        if (!notes.contains(sentence)) {
            notes.add(sentence);
        }
    }

    /** Big-endian value of a code. */
    private static long value(byte[] bytes) {
        long value = 0;
        for (byte b : bytes) {
            value = (value << 8) | (b & 0xFFL);
        }
        return value;
    }

    /**
     * The value of one code, read big-endian out of a string's bytes — the same reading
     * {@link #decode(byte[], PdfGlyphMapper, int, List)} performs, and the reason it lives here:
     * a showing operator that counts advances has to cut a string into codes exactly like the
     * decoder that turns them into characters, and a second copy of "where a code ends and which
     * byte is the high one" is a second place that can drift (5.6.4 cuts by code space, the
     * dictionary widths are keyed by that same value).
     *
     * @param bytes  the string's bytes
     * @param offset first byte of the code
     * @param length bytes per code, as the encoding or code space states
     * @return the code value (a CID for a CID font)
     * @throws PdfException when the slice lies outside the bytes - a caller that cuts past the end
     *                      of a string has misread the string, which is not a fact about a file
     */
    public static long codeValue(byte[] bytes, int offset, int length) {
        if (bytes == null || length <= 0 || offset < 0 || offset + length > bytes.length) {
            throw new PdfException("cannot read a " + length + "-byte code at offset " + offset
                    + " of " + (bytes == null ? 0 : bytes.length) + " byte(s)");
        }
        return value(Arrays.copyOfRange(bytes, offset, offset + length));
    }

    /** The code at {@code offset} as uppercase hex of exactly {@code length} bytes. */
    public static String codeHex(byte[] bytes, int offset, int length) {
        return PdfCMap.hex(Arrays.copyOfRange(bytes, offset, offset + length));
    }
}
