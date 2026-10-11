package org.rogmann.mcp2sdk.pdf;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The file structure of a PDF document (ISO 32000-1 section 7.5): header, body,
 * cross-reference table and trailer - what the specification calls the "logical structure"
 * of the file, independent of what the document means.
 * <p>
 * This class answers the structural questions a caller needs before reading content:
 * </p>
 * <ul>
 *   <li><b>Header</b>: offset, {@code %PDF-} version line, presence of the binary comment
 *       line (the bytes &ge; 128 that mark a binary file).</li>
 *   <li><b>Body</b>: the objects and where each of them lives (file offset or inside an
 *       object stream) - see {@link PdfCrossReference}.</li>
 *   <li><b>Cross-reference table / stream</b>: every section of the chain with
 *       {@code /Size}, {@code /W}, {@code /Index} and {@code /Prev}.</li>
 *   <li><b>Trailer</b>: the active trailer plus the trailers of the earlier sections, so
 *       incremental updates stay visible.</li>
 *   <li><b>EOF</b>: {@code startxref} value(s) and the position of {@code %%EOF}.</li>
 *   <li><b>Encryption</b>: described (algorithm, version, crypt filters) but never
 *       decrypted; {@code /Linearized} is recognised as well.</li>
 * </ul>
 * <p>
 * Damaged files are handled deliberately: a broken cross-reference never hides the rest of
 * the file. The entries that can be read are kept, the problem is reported as a warning, and
 * a file whose cross-reference cannot be used at all is <em>salvaged</em> by scanning for
 * {@code num gen obj} markers (reported through {@link PdfCrossReference#salvaged()}).
 * </p>
 */
public final class PdfFileStructure {

    /** Largest supported file image (protects the heap; PDFs are usually far below this). */
    public static final long MAX_PDF_BYTES = 512L * 1024 * 1024;

    /** Largest accepted number of entries in one cross-reference subsection. */
    private static final long MAX_XREF_ENTRIES = 20_000_000;

    /** Largest accepted {@code /Prev} chain (more means a loop or a pathological file). */
    private static final int MAX_SECTIONS = 4096;

    /** Window at the end of the file that is searched for {@code startxref}. */
    private static final int STARTXREF_WINDOW = 8192;

    private final String displayName;
    private final byte[] data;
    private final long headerOffset;
    private final String headerLine;
    private final String version;
    private final long binaryCommentOffset;
    private final long eofOffset;
    private final List<Long> startxrefs;
    private final PdfCrossReference crossReference;
    private final Map<String, Object> linearized;
    private final List<String> warnings;

    /** Set after construction: needed to resolve indirect values such as {@code /Encrypt}. */
    private PdfDocument document;

    private PdfFileStructure(String displayName, byte[] data, long headerOffset,
                             String headerLine, String version, long binaryCommentOffset,
                             long eofOffset, List<Long> startxrefs,
                             PdfCrossReference crossReference, Map<String, Object> linearized,
                             List<String> warnings) {
        this.displayName = displayName;
        this.data = data;
        this.headerOffset = headerOffset;
        this.headerLine = headerLine;
        this.version = version;
        this.binaryCommentOffset = binaryCommentOffset;
        this.eofOffset = eofOffset;
        this.startxrefs = List.copyOf(startxrefs);
        this.crossReference = crossReference;
        this.linearized = linearized;
        this.warnings = List.copyOf(warnings);
    }

    /**
     * Attaches the document that provides object access (for {@code /Encrypt} details).
     * @param document the owning document
     */
    void attach(PdfDocument document) {
        this.document = document;
    }

    // ===================================================================
    // Analysis
    // ===================================================================

    /**
     * Analyses the file structure of a PDF image.
     * @param displayName display name used in messages (project-relative path)
     * @param data raw file bytes
     * @return the structure
     * @throws PdfException when the file is not a PDF at all
     */
    public static PdfFileStructure analyze(String displayName, byte[] data) {
        if (data == null || data.length == 0) {
            throw new PdfException("Empty file: " + displayName);
        }
        if (data.length > MAX_PDF_BYTES) {
            throw new PdfException("PDF too large: " + displayName + " has " + data.length
                    + " bytes (limit " + MAX_PDF_BYTES + ")");
        }
        List<String> warnings = new ArrayList<>();

        long headerOffset = findHeader(data);
        if (headerOffset < 0) {
            throw new PdfException("Not a PDF file: " + displayName + " has no %PDF- header"
                    + " (first bytes: " + PdfString.preview(head(data, 16), 16) + ")");
        }
        String headerLine = readLine(data, headerOffset);
        String version = parseVersion(headerLine);
        if (version == null) {
            warnings.add("The %PDF- header line has no readable version: '" + headerLine + "'");
        }
        long binaryCommentOffset = findBinaryComment(data, headerOffset + headerLine.length());

        long eofOffset = lastIndexOf(data, "%%EOF");
        if (eofOffset < 0) {
            warnings.add("No %%EOF marker: the file is truncated or was written by a repairer");
        }

        List<Long> startxrefs = findStartxrefs(data, eofOffset);
        if (startxrefs.isEmpty()) {
            warnings.add("No startxref found near the end of the file");
        }

        PdfCrossReference crossReference = buildCrossReference(data, startxrefs, warnings);
        Map<String, Object> linearized = detectLinearized(data, headerOffset + headerLine.length());

        return new PdfFileStructure(displayName, data, headerOffset, headerLine, version,
                binaryCommentOffset, eofOffset, startxrefs, crossReference, linearized, warnings);
    }

    /**
     * Returns at most maxLength bytes of an array.
     * @param data input array
     * @param maxLength maximum number of bytes
     * @return head of array
     */
    private static byte[] head(byte[] data, int maxLength) {
        if (data == null) {
            return null;
        }
        return Arrays.copyOf(data, maxLength);
    }

    /**
     * Finds the {@code %PDF-} header (the spec wants it at offset 0; prefixed junk such as a
     * mail envelope is tolerated within the first 4 KiB).
     */
    private static long findHeader(byte[] data) {
        int limit = (int) Math.min(data.length - 5L, 4096L);
        byte[] marker = {'%', 'P', 'D', 'F', '-'};
        for (int i = 0; i <= limit; i++) {
            int j = 0;
            while (j < marker.length && data[i + j] == marker[j]) {
                j++;
            }
            if (j == marker.length) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Reads a line (without the line ending).
     */
    static String readLine(byte[] data, long offset) {
        int start = (int) offset;
        int end = start;
        while (end < data.length && data[end] != 0x0A && data[end] != 0x0D) {
            end++;
        }
        return new String(data, start, end - start, StandardCharsets.ISO_8859_1);
    }

    /**
     * Extracts the version from a header line such as {@code %PDF-1.7}.
     * @return the version or {@code null}
     */
    static String parseVersion(String headerLine) {
        int dash = headerLine.indexOf('%');
        if (dash < 0) {
            return null;
        }
        String rest = headerLine.substring(dash + 1);
        if (!rest.startsWith("PDF-")) {
            return null;
        }
        String tail = rest.substring(4);
        int end = 0;
        while (end < tail.length() && (Character.isDigit(tail.charAt(end)) || tail.charAt(end) == '.')) {
            end++;
        }
        return end > 0 ? tail.substring(0, end) : null;
    }

    /**
     * Finds the binary comment line after the header (a comment containing bytes &ge; 128,
     * which tells tools that the file is binary).
     * @return its offset or -1
     */
    private static long findBinaryComment(byte[] data, long headerEnd) {
        int pos = (int) headerEnd;
        // skip the line ending
        while (pos < data.length && (data[pos] == 0x0A || data[pos] == 0x0D)) {
            pos++;
        }
        if (pos >= data.length || data[pos] != '%') {
            return -1;
        }
        int end = pos;
        int highBytes = 0;
        while (end < data.length && data[end] != 0x0A && data[end] != 0x0D) {
            if ((data[end] & 0xFF) >= 0x80) {
                highBytes++;
            }
            end++;
        }
        return highBytes >= 1 ? pos : -1;
    }

    /**
     * Finds the {@code startxref} keyword(s) in the tail of the file, newest first.
     */
    private static List<Long> findStartxrefs(byte[] data, long eofOffset) {
        List<Long> found = new ArrayList<>();
        int from = Math.max(0, (eofOffset >= 0 ? (int) eofOffset : data.length) - STARTXREF_WINDOW);
        int to = eofOffset >= 0 ? (int) eofOffset : data.length;
        int pos = from;
        while (true) {
            int index = indexOfDelimited(data, "startxref", pos, to);
            if (index < 0) {
                break;
            }
            found.add((long) index);
            pos = index + "startxref".length();
        }
        // Newest first (the last occurrence is the one the spec points at).
        List<Long> offsets = new ArrayList<>();
        PdfParser parser = new PdfParser(data);
        for (int i = found.size() - 1; i >= 0; i--) {
            long index = found.get(i);
            if (index < 0 || index > Integer.MAX_VALUE) {
                throw new IllegalStateException("Invalid index %d at index %d".formatted(index, i));
            }
            int iIndex = (int) index;
            parser.seek(iIndex + "startxref".length());
            Long value = parser.tryReadIntegerToken();
            if (value != null) {
                offsets.add(value);
            }
        }
        return offsets;
    }

    /**
     * Finds a delimited ASCII token (both neighbours must be delimiters).
     */
    static int indexOfDelimited(byte[] data, String token, int from, int to) {
        byte[] needle = token.getBytes(StandardCharsets.ISO_8859_1);
        int max = Math.min(to, data.length) - needle.length;
        for (int i = Math.max(0, from); i <= max; i++) {
            int j = 0;
            while (j < needle.length && data[i + j] == needle[j]) {
                j++;
            }
            if (j < needle.length) {
                continue;
            }
            int before = i - 1;
            int after = i + needle.length;
            boolean beforeOk = before < 0 || PdfParser.isDelimiterByte(data[before] & 0xFF);
            boolean afterOk = after >= data.length
                    || PdfParser.isDelimiterByte(data[after] & 0xFF);
            if (beforeOk && afterOk) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Last occurrence of a plain ASCII marker.
     */
    private static long lastIndexOf(byte[] data, String marker) {
        byte[] needle = marker.getBytes(StandardCharsets.ISO_8859_1);
        for (int i = data.length - needle.length; i >= 0; i--) {
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

    // ===================================================================
    // Cross-reference chain
    // ===================================================================

    /**
     * Walks the {@code startxref} / {@code /Prev} chain and merges the sections.
     */
    private static PdfCrossReference buildCrossReference(byte[] data, List<Long> startxrefs,
                                                        List<String> warnings) {
        List<PdfCrossReference.Section> sections = new ArrayList<>();
        Map<Integer, PdfCrossReference.Entry> entries = new LinkedHashMap<>();
        Map<String, Object> trailer = null;
        Set<Long> visited = new HashSet<>();
        boolean salvaged = false;
        long usedStartxref = startxrefs.isEmpty() ? -1 : startxrefs.get(0);

        Long next = startxrefs.isEmpty() ? null : startxrefs.get(0);
        int sectionIndex = 0;
        while (next != null) {
            long offset = next;
            if (offset < 0 || offset >= data.length) {
                warnings.add("Cross-reference offset " + offset + " points outside the file"
                        + " (" + data.length + " bytes) - the chain stops here");
                break;
            }
            if (!visited.add(offset)) {
                warnings.add("/Prev chain loops back to offset " + offset
                        + " - the chain stops here");
                break;
            }
            PdfCrossReference.Section section = new PdfCrossReference.Section();
            section.sectionIndex = sectionIndex;
            section.offset = offset;
            section.startxref = offset;
            Map<String, Object> sectionTrailer;
            try {
                sectionTrailer = readSection(data, section, entries);
            } catch (PdfException e) {
                if (sections.isEmpty()) {
                    warnings.add("The cross-reference at offset " + offset + " cannot be read: "
                            + e.getMessage());
                    break;
                }
                warnings.add("Older cross-reference at offset " + offset + " cannot be read: "
                        + e.getMessage());
                break;
            }
            section.trailer = sectionTrailer;
            sections.add(section);
            if (trailer == null) {
                trailer = sectionTrailer;
            }
            Object prevValue = sectionTrailer.get("Prev");
            next = prevValue instanceof Long prev ? prev : null;
            section.prev = next;
            sectionIndex++;
            if (sectionIndex >= MAX_SECTIONS) {
                warnings.add("More than " + MAX_SECTIONS + " cross-reference sections"
                        + " (incremental updates) - the chain stops here");
                break;
            }
        }

        if (sections.isEmpty() || entries.isEmpty()) {
            if (!sections.isEmpty()) {
                warnings.add("The cross-reference chain registers no usable object; the file was"
                        + " scanned for object markers instead");
            }
            Map<Integer, PdfCrossReference.Entry> recovered = scanForObjects(data, warnings);
            entries.putAll(recovered);
            salvaged = !recovered.isEmpty();
        }

        checkSizeAgainstEntries(data, sections, entries, warnings);
        return new PdfCrossReference(sections, entries,
                trailer != null ? trailer : Map.of(), usedStartxref, salvaged);
    }

    /**
     * Reads one section: a classic table or a cross-reference stream.
     * @return the trailer dictionary of the section
     */
    private static Map<String, Object> readSection(byte[] data, PdfCrossReference.Section section,
                                                   Map<Integer, PdfCrossReference.Entry> entries) {
        PdfParser parser = new PdfParser(data, (int) section.offset);
        parser.setWarningSink(section.warnings());
        if (parser.matchKeyword("xref")) {
            section.kind = "table";
            return readClassicTable(parser, data, section, entries);
        }
        section.kind = "stream";
        return readCrossReferenceStream(parser, data, section, entries);
    }

    /**
     * Reads a classic cross-reference table (tolerant about the 20-byte entry layout) and
     * the {@code trailer} dictionary that follows it.
     */
    private static Map<String, Object> readClassicTable(PdfParser parser, byte[] data,
            PdfCrossReference.Section section, Map<Integer, PdfCrossReference.Entry> entries) {
        int parsedEntries = 0;
        while (true) {
            Long first = parser.tryReadIntegerToken();
            if (first == null) {
                break;      // no further subsection header: 'trailer' follows
            }
            Long count = parser.tryReadIntegerToken();
            if (count == null) {
                section.warnings().add("Cross-reference subsection header at offset "
                        + parser.position() + " has no entry count");
                break;
            }
            // Plausibility: every entry costs at least a few bytes ("0 0 n\n"), so a
            // subsection claiming more entries than the file can physically hold is damage.
            if (count < 0 || count > MAX_XREF_ENTRIES || count * 6L > data.length) {
                section.warnings().add("Cross-reference subsection " + first + " " + count
                        + " claims more entries than the file can hold - it was skipped");
                break;
            }
            section.indexRanges.add(new int[]{first.intValue(), count.intValue()});
            for (int i = 0; i < count; i++) {
                parser.skipSpaceAndComments();
                int entryStart = parser.position();
                Long entryOffset = parser.tryReadIntegerToken();
                Long entryGen = parser.tryReadIntegerToken();
                parser.skipSpaceAndComments();
                int typeChar = parser.read();
                if (entryOffset == null || entryGen == null
                        || (typeChar != 'n' && typeChar != 'f')) {
                    parser.seek(entryStart);
                    section.warnings().add("Cross-reference entry " + (first + i)
                            + " at offset " + entryStart + " is malformed - the table was read"
                            + " as far as it parsed");
                    section.entryCount = parsedEntries;
                    return readTrailer(parser, section, data);
                }
                parsedEntries++;
                int objNum = first.intValue() + i;
                if (entries.containsKey(objNum)) {
                    continue;   // a newer section already defined this object
                }
                if (typeChar == 'n') {
                    entries.put(objNum, new PdfCrossReference.Entry(objNum, entryGen.intValue(),
                            PdfCrossReference.TYPE_UNCOMPRESSED, entryOffset, -1, -1));
                } else {
                    entries.put(objNum, new PdfCrossReference.Entry(objNum, entryGen.intValue(),
                            PdfCrossReference.TYPE_FREE, -1, -1, -1));
                }
            }
        }
        section.entryCount = parsedEntries;
        return readTrailer(parser, section, data);
    }

    /**
     * Reads the {@code trailer} dictionary of a classic cross-reference section.
     */
    private static Map<String, Object> readTrailer(PdfParser parser,
                                                   PdfCrossReference.Section section,
                                                   byte[] data) {
        if (!parser.matchKeyword("trailer")) {
            section.warnings().add("Missing 'trailer' keyword after the cross-reference table at"
                    + " offset " + section.offset);
            return Map.of();
        }
        Object value = parser.parseValue();
        if (!(value instanceof Map<?, ?> dict)) {
            section.warnings().add("The trailer at offset " + section.offset + " is not a"
                    + " dictionary but " + PdfParser.kindOf(value));
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) dict;
        Object size = typed.get("Size");
        if (size instanceof Long sizeValue) {
            section.size = sizeValue;
        }
        return typed;
    }

    /**
     * Reads a cross-reference stream (PDF 1.5+): the stream dictionary is the trailer, the
     * stream data holds the encoded entries ({@code /W} field widths, {@code /Index}
     * subsections, usually a PNG predictor).
     */
    private static Map<String, Object> readCrossReferenceStream(PdfParser parser, byte[] data,
            PdfCrossReference.Section section, Map<Integer, PdfCrossReference.Entry> entries) {
        int[] header = parser.readObjectHeader();
        if (header == null) {
            throw new PdfException("Cross-reference data at offset " + section.offset
                    + " is neither a table ('xref') nor a cross-reference stream"
                    + " ('num gen obj ...') - the file is damaged");
        }
        section.streamObjNum = header[0];
        Object value = parser.parseValue();
        if (!(value instanceof PdfStream stream)) {
            throw new PdfException("The cross-reference stream object " + header[0]
                    + " is not a stream but " + PdfParser.kindOf(value));
        }
        Map<String, Object> dict = stream.dict();
        PdfName type = PdfName.ofDict(dict, "Type");
        if (type != null && !type.equalsName("XRef")) {
            section.warnings().add("The cross-reference stream is /Type " + type
                    + " instead of /XRef");
        }
        int[] w = intArray(dict.get("W"));
        if (w == null || w.length < 2) {
            throw new PdfException("Cross-reference stream " + header[0]
                    + " has no usable /W array (expected 1..3 integers)");
        }
        section.w = w;
        if (w.length > 3) {
            throw new PdfException("Cross-reference stream " + header[0] + " has " + w.length
                    + " /W fields (at most 3 are allowed)");
        }
        int[] w2 = intArray(dict.get("W2"));
        if (w2 != null) {
            section.warnings().add("/W2 (PDF 2.0 cross-reference fields) is not supported;"
                    + " those bytes were skipped");
        }
        // /W lists Field2 and Field3; a third entry (or its absence) decides the type field.
        // With only two widths there is NO type field and every entry is of type 1.
        int typeWidth = w.length >= 3 ? w[0] : 0;
        int field2Width = w.length >= 3 ? w[1] : w[0];
        int field3Width = w.length >= 3 ? w[2] : w[1];
        int entrySize = typeWidth + field2Width + field3Width;
        int w2Size = 0;
        if (w2 != null) {
            for (int width : w2) {
                w2Size += Math.max(0, width);
            }
        }
        int fullEntrySize = entrySize + w2Size;

        Object sizeValue = dict.get("Size");
        if (sizeValue instanceof Long sizeLong) {
            section.size = sizeLong;
        }
        List<int[]> ranges = indexRanges(dict.get("Index"), section.size, section.warnings());
        section.indexRanges.addAll(ranges);

        byte[] raw = rawBytes(data, stream, entries, section.warnings());
        byte[] plain;
        try {
            plain = PdfFilters.decodeChain(raw, dict.get("Filter"), firstNonNull(
                    dict.get("DecodeParms"), dict.get("DP")), ref -> null).data();
        } catch (PdfException e) {
            throw new PdfException("The cross-reference stream " + header[0]
                    + " cannot be decoded: " + e.getMessage(), e);
        }
        long needed = 0;
        for (int[] range : ranges) {
            needed += (long) range[1] * fullEntrySize;
        }
        if (needed > plain.length) {
            section.warnings().add("The cross-reference stream holds " + plain.length
                    + " decoded bytes but /Index declares " + needed
                    + " - it was read as far as the data goes");
        }
        int pos = 0;
        int parsedEntries = 0;
        for (int[] range : ranges) {
            int first = range[0];
            long count = range[1];
            for (long i = 0; i < count; i++) {
                if (pos + fullEntrySize > plain.length) {
                    break;
                }
                int entryType = typeWidth == 0 ? 1 : (int) readBigEndian(plain, pos, typeWidth);
                pos += typeWidth;
                long field2 = readBigEndian(plain, pos, field2Width);
                pos += field2Width;
                long field3 = readBigEndian(plain, pos, field3Width);
                pos += field3Width;
                if (w2 != null) {
                    for (int width : w2) {
                        pos += Math.max(0, width);
                    }
                }
                parsedEntries++;
                int objNum = first + (int) i;
                if (entries.containsKey(objNum)) {
                    continue;
                }
                int gen = (int) field3;
                if (entryType == PdfCrossReference.TYPE_UNCOMPRESSED) {
                    entries.put(objNum, new PdfCrossReference.Entry(objNum, gen,
                            PdfCrossReference.TYPE_UNCOMPRESSED, field2, -1, -1));
                } else if (entryType == PdfCrossReference.TYPE_COMPRESSED) {
                    entries.put(objNum, new PdfCrossReference.Entry(objNum, gen,
                            PdfCrossReference.TYPE_COMPRESSED, -1, (int) field2, (int) field3));
                } else {
                    entries.put(objNum, new PdfCrossReference.Entry(objNum, gen,
                            PdfCrossReference.TYPE_FREE, -1, -1, -1));
                }
            }
        }
        section.entryCount = parsedEntries;
        section.w2 = w2;
        Object prev = dict.get("Prev");
        if (prev instanceof Long prevLong) {
            section.prev = prevLong;
        }
        return dict;
    }

    /**
     * Normalises {@code /Index} (defaulting to {@code [0 /Size]}).
     */
    private static List<int[]> indexRanges(Object indexValue, long size, List<String> warnings) {
        List<int[]> ranges = new ArrayList<>();
        if (indexValue instanceof List<?> list) {
            for (int i = 0; i + 1 < list.size(); i += 2) {
                Object first = list.get(i);
                Object count = list.get(i + 1);
                if (first instanceof Long firstLong && count instanceof Long countLong) {
                    ranges.add(new int[]{firstLong.intValue(), countLong.intValue()});
                } else {
                    warnings.add("/Index contains a non-integer pair at position " + i
                            + " and was ignored");
                }
            }
        }
        if (ranges.isEmpty()) {
            if (size <= 0) {
                warnings.add("The cross-reference has neither /Index nor a usable /Size;"
                        + " no entries could be located");
                return ranges;
            }
            ranges.add(new int[]{0, (int) Math.min(size, MAX_XREF_ENTRIES)});
        }
        return ranges;
    }

    /**
     * Reads an unsigned big-endian integer of up to 8 bytes.
     */
    static long readBigEndian(byte[] data, int offset, int length) {
        long value = 0;
        for (int i = 0; i < length; i++) {
            value = (value << 8) | (data[offset + i] & 0xFFL);
        }
        return value;
    }

    /**
     * Converts a value that should be an array of integers.
     */
    static int[] intArray(Object value) {
        if (!(value instanceof List<?> list)) {
            return null;
        }
        int[] out = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (!(item instanceof Long number)) {
                return null;
            }
            out[i] = number.intValue();
        }
        return out;
    }

    private static Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    // ===================================================================
    // Raw stream bytes (also used while the cross-reference is still being built)
    // ===================================================================

    /**
     * Reads the stored (still encoded) bytes of a stream. An indirect {@code /Length} is
     * resolved through the entries known so far; when that is impossible the length derived
     * from the {@code endstream} marker is used and reported.
     * @param data file image
     * @param stream the stream object
     * @param knownEntries entries available for reference resolution
     * @param warnings warning sink
     * @return the stored bytes
     */
    static byte[] rawBytes(byte[] data, PdfStream stream,
                           Map<Integer, PdfCrossReference.Entry> knownEntries,
                           List<String> warnings) {
        long dataOffset = stream.dataOffset();
        if (dataOffset < 0 || dataOffset >= data.length) {
            warnings.add("Stream data offset " + dataOffset + " lies outside the file -"
                    + " the stream is empty");
            return new byte[0];
        }
        Long length = PdfParser.plainLength(stream.rawLength());
        if (length == null && stream.rawLength() instanceof PdfReference reference) {
            length = resolveLengthReference(data, reference, knownEntries, warnings);
        }
        long end;
        if (length != null && length >= 0) {
            end = dataOffset + length;
            if (end > data.length) {
                warnings.add("Stream at offset " + dataOffset + " declares " + length
                        + " bytes but the file ends after " + (data.length - dataOffset)
                        + " - it was cut to the end of the file");
                end = data.length;
            }
        } else {
            end = stream.endOffset();
            if (end <= dataOffset) {
                warnings.add("Stream at offset " + dataOffset + " has no determinable length;"
                        + " it is reported as empty");
                return new byte[0];
            }
        }
        return Arrays.copyOfRange(data, (int) dataOffset, (int) end);
    }

    /**
     * Resolves an indirect {@code /Length} while the cross-reference chain is still open.
     */
    private static Long resolveLengthReference(byte[] data, PdfReference reference,
                                               Map<Integer, PdfCrossReference.Entry> knownEntries,
                                               List<String> warnings) {
        PdfCrossReference.Entry entry = knownEntries.get(reference.objNum());
        if (entry == null || entry.type() != PdfCrossReference.TYPE_UNCOMPRESSED) {
            warnings.add("The indirect /Length " + reference + " cannot be resolved while the"
                    + " cross-reference chain is still open; the endstream marker decides the size");
            return null;
        }
        try {
            PdfParser parser = new PdfParser(data, (int) entry.offset());
            parser.readObjectHeader();
            Object value = parser.parseValue();
            if (value instanceof Long number) {
                return number;
            }
            warnings.add("The object " + reference + " used as /Length is "
                    + PdfParser.kindOf(value) + " instead of a number");
        } catch (PdfException e) {
            warnings.add("The indirect /Length " + reference + " cannot be read: " + e.getMessage());
        }
        return null;
    }

    // ===================================================================
    // Salvage
    // ===================================================================

    /**
     * Scans the file for {@code num gen obj} markers (the recovery of last resort when the
     * cross-reference is unusable). The newest definition of an object number wins, as in an
     * incremental update.
     * @param data file image
     * @param warnings warning sink
     * @return recovered entries
     */
    public static Map<Integer, PdfCrossReference.Entry> scanForObjects(byte[] data,
                                                                      List<String> warnings) {
        Map<Integer, PdfCrossReference.Entry> recovered = new LinkedHashMap<>();
        int pos = 0;
        int collisions = 0;
        while (true) {
            int keyword = indexOfDelimited(data, "obj", pos, data.length);
            if (keyword < 0) {
                break;
            }
            pos = keyword + 3;
            int cursor = keyword - 1;
            while (cursor >= 0 && PdfParser.isWhitespaceByte(data[cursor] & 0xFF)) {
                cursor--;
            }
            int genEnd = cursor + 1;
            while (cursor >= 0 && isDigit(data[cursor])) {
                cursor--;
            }
            int genStart = cursor + 1;
            if (genStart >= genEnd) {
                continue;
            }
            while (cursor >= 0 && PdfParser.isWhitespaceByte(data[cursor] & 0xFF)) {
                cursor--;
            }
            int numEnd = cursor + 1;
            while (cursor >= 0 && isDigit(data[cursor])) {
                cursor--;
            }
            int numStart = cursor + 1;
            if (numStart >= numEnd || numEnd - numStart > 10) {
                continue;
            }
            try {
                int objNum = Integer.parseInt(new String(data, numStart, numEnd - numStart,
                        StandardCharsets.ISO_8859_1));
                int gen = Integer.parseInt(new String(data, genStart,
                        Math.min(genEnd - genStart, 5), StandardCharsets.ISO_8859_1));
                if (recovered.put(objNum, new PdfCrossReference.Entry(objNum, gen,
                        PdfCrossReference.TYPE_UNCOMPRESSED, numStart, -1, -1)) != null) {
                    collisions++;
                }
            } catch (NumberFormatException e) {
                // not a real object header (binary data that happens to contain "obj")
            }
        }
        if (collisions > 0) {
            warnings.add("The object scan found " + collisions + " object number(s) defined more"
                    + " than once; the last definition wins");
        }
        return recovered;
    }

    private static boolean isDigit(byte b) {
        return b >= '0' && b <= '9';
    }

    // ===================================================================
    // Consistency checks
    // ===================================================================

    /**
     * Compares {@code /Size} with the entries that were actually read.
     */
    private static void checkSizeAgainstEntries(byte[] data,
                                                List<PdfCrossReference.Section> sections,
                                                Map<Integer, PdfCrossReference.Entry> entries,
                                                List<String> warnings) {
        for (PdfCrossReference.Section section : sections) {
            if (section.size() <= 0 || section.sectionIndex() != 0) {
                continue;
            }
            int highest = 0;
            int used = 0;
            for (PdfCrossReference.Entry entry : entries.values()) {
                highest = Math.max(highest, entry.objNum());
                if (entry.type() != PdfCrossReference.TYPE_FREE) {
                    used++;
                }
            }
            if (section.size() < highest + 1) {
                warnings.add("/Size is " + section.size() + " but object " + highest
                        + " is registered in the cross-reference (the value is too small)");
            }
            if (section.size() > used + (long) (highest + 1) / 2L) {
                warnings.add("/Size is " + section.size() + " but only " + used
                        + " object(s) are in use - the file probably contains incremental"
                        + " updates or free slots");
            }
        }
    }

    /**
     * Detects the optional {@code /Linearized} marker in the first object of the file.
     * @return a small map or {@code null}
     */
    private static Map<String, Object> detectLinearized(byte[] data, long afterHeader) {
        try {
            PdfParser parser = new PdfParser(data, (int) Math.min(afterHeader, data.length));
            parser.skipSpaceAndComments();
            int[] header = parser.readObjectHeader();
            if (header == null) {
                return null;
            }
            Object value = parser.parseValue();
            if (value instanceof Map<?, ?> dict && dict.containsKey("Linearized")) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("present", true);
                map.put("firstObjNum", (long) header[0]);
                map.put("hint", PdfValues.toPlain(dict.get("Linearized"), new PdfValues.Ctx()));
                Object firstPage = dict.get("FirstPage");
                if (firstPage instanceof Long number) {
                    map.put("firstPage", number);
                }
                return map;
            }
        } catch (PdfException e) {
            // A file that does not start with a readable object is simply not linearized.
        }
        return null;
    }

    // ===================================================================
    // Accessors
    // ===================================================================

    /** Display name of the file (project-relative). */
    public String displayName() {
        return displayName;
    }

    /** The file image (read-only use). */
    public byte[] data() {
        return data;
    }

    /** Offset of the {@code %PDF-} header. */
    public long headerOffset() {
        return headerOffset;
    }

    /** The full header line, e.g. {@code %PDF-1.7}. */
    public String headerLine() {
        return headerLine;
    }

    /** The PDF version from the header, e.g. {@code 1.7}. */
    public String version() {
        return version;
    }

    /** Offset of the binary comment line after the header, or -1. */
    public long binaryCommentOffset() {
        return binaryCommentOffset;
    }

    /** Offset of the last {@code %%EOF} marker, or -1. */
    public long eofOffset() {
        return eofOffset;
    }

    /** {@code startxref} values found near the end of the file, newest first. */
    public List<Long> startxrefs() {
        return startxrefs;
    }

    /** The merged cross-reference data. */
    public PdfCrossReference crossReference() {
        return crossReference;
    }

    /** Warnings collected while analysing the structure. */
    public List<String> warnings() {
        return warnings;
    }

    /** The {@code /Linearized} marker or {@code null}. */
    public Map<String, Object> linearized() {
        return linearized;
    }

    /**
     * Describes the encryption of the document (the trailer's {@code /Encrypt} dictionary).
     * @return a summary or {@code null} when the document is not encrypted
     */
    public Map<String, Object> encryption() {
        Object raw = crossReference.trailer().get("Encrypt");
        if (raw == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("present", true);
        Object value = raw;
        if (value instanceof PdfReference reference) {
            map.put("objNum", (long) reference.objNum());
            if (document == null) {
                map.put("note", "the encryption dictionary is indirect object " + reference);
                return map;
            }
            try {
                value = document.objectValue(reference.objNum());
            } catch (PdfException e) {
                map.put("unreadable", e.getMessage());
                return map;
            }
        }
        if (value instanceof Map<?, ?> dict) {
            PdfValues.Ctx ctx = new PdfValues.Ctx().resolveRefs(true)
                    .resolver(ref -> document.objectValue(ref.objNum()));
            map.put("spec", PdfValues.toPlain(dict, ctx));
            String note = "This reader describes the encryption but does not decrypt the"
                    + " document; strings and streams stay as stored.";
            if (!ctx.notes().isEmpty()) {
                note = note + " " + String.join("; ", ctx.notes());
            }
            map.put("note", note);
        }
        return map;
    }

    /**
     * Plain view of the whole file structure - the result of {@code pdf.structure()}.
     * @return structure map
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("path", displayName);
        map.put("size", (long) data.length);
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("offset", headerOffset);
        header.put("line", headerLine);
        header.put("version", version != null ? version : "unknown");
        header.put("binaryComment", binaryCommentOffset >= 0);
        if (binaryCommentOffset >= 0) {
            header.put("binaryCommentOffset", binaryCommentOffset);
        }
        map.put("header", header);

        Map<String, Object> eof = new LinkedHashMap<>();
        eof.put("markerOffset", eofOffset >= 0 ? eofOffset : null);
        List<Object> startxrefList = new ArrayList<>();
        for (Long value : startxrefs) {
            startxrefList.add(value);
        }
        eof.put("startxref", startxrefs.isEmpty() ? null : startxrefs.get(0));
        eof.put("startxrefCandidates", startxrefList);
        map.put("eof", eof);

        map.put("crossReference", crossReference.sectionsAsList());
        map.put("trailer", PdfValues.toPlain(crossReference.trailer(), new PdfValues.Ctx()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("objects", (long) crossReference.objectCount());
        int free = 0;
        int uncompressed = 0;
        int compressed = 0;
        for (PdfCrossReference.Entry entry : entriesSnapshot()) {
            switch (entry.type()) {
                case PdfCrossReference.TYPE_FREE -> free++;
                case PdfCrossReference.TYPE_UNCOMPRESSED -> uncompressed++;
                case PdfCrossReference.TYPE_COMPRESSED -> compressed++;
                default -> { }
            }
        }
        body.put("free", (long) free);
        body.put("uncompressed", (long) uncompressed);
        body.put("compressed", (long) compressed);
        body.put("incrementalUpdates", (long) crossReference.sections().size());
        body.put("recoveredByScan", crossReference.salvaged());
        map.put("body", body);

        if (linearized != null) {
            map.put("linearized", linearized);
        }
        Map<String, Object> encryption = encryption();
        if (encryption != null) {
            map.put("encryption", encryption);
        }
        if (!warnings.isEmpty()) {
            map.put("warnings", new ArrayList<Object>(warnings));
        }
        return map;
    }

    /** Snapshot of all entries (used for the body statistics). */
    private List<PdfCrossReference.Entry> entriesSnapshot() {
        List<PdfCrossReference.Entry> list = new ArrayList<>();
        for (Integer objNum : crossReference.objectNumbers()) {
            PdfCrossReference.Entry entry = crossReference.entry(objNum);
            if (entry != null) {
                list.add(entry);
            }
        }
        return list;
    }
}
