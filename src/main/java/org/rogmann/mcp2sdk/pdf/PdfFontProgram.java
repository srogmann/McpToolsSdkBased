package org.rogmann.mcp2sdk.pdf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Looks at an embedded font program and says <em>what it is</em> - the dispatcher the plan's
 * decision 4 asks for: this class recognises, and only the reader of a format touches the bytes of
 * that format ({@link PdfTrueTypeProgram} today, {@code PdfType1Program} and the CFF reader with
 * the next step).
 * <p>
 * Identification reads the file, not the dictionary's hope: a {@code /FontFile3} whose
 * {@code /Subtype} says {@code /OpenType} can still hold anything, so the first bytes decide and
 * the name is only reported next to them.
 * </p>
 * <ul>
 *   <li>{@code 00 01 00 00} or {@code true} → {@value #KIND_TRUETYPE}; {@code OTTO} →
 *       {@value #KIND_OPENTYPE}. Both are sfnt files, so {@link PdfTrueTypeProgram} reads their
 *       tables - a {@code CFF}-flavoured sfnt still carries {@code head}/{@code hhea}/{@code hmtx}/
 *       {@code cmap}, which means its widths are readable and its outlines are not;</li>
 *   <li>{@code 01 00 hh mm} → {@value #KIND_CFF}: read by {@link PdfCffProgram} as far as its DICTs
 *       reach - the Top DICT, the {@code Charset}, the font families of the {@code FDArray} and the
 *       widths its {@code Private} DICTs announce; a charstring INDEX is counted, never parsed;</li>
 *   <li>{@code %!PS-AdobeFont}, a clear-text header with an {@code eexec} marker, or the binary
 *       {@code 80 01} prefix → {@value #KIND_TYPE1}: read by {@link PdfType1Program}, which decrypts
 *       the {@code eexec} section (the cipher hides a format, not a document) and reads its
 *       declarations - {@code /FontMatrix}, {@code /FontBBox}, the {@code /Encoding} names and the
 *       {@code /CharStrings} names. Charstrings are skipped by their announced length: nothing is
 *       executed, so no width and no outline comes out of a program (decision 6 of the plan);</li>
 *   <li>anything else → {@value #KIND_UNKNOWN}, named by its first bytes instead of guessed at.</li>
 * </ul>
 * <p>
 * A program is <em>read</em> where it is asked for, and its sentences land where the question was
 * asked: in the {@code notes} of the {@code program} block when somebody opened a font sheet, in
 * the caller's note list when the mapping chain needed the program to answer a code - see
 * {@link #read}.
 * </p>
 */
public final class PdfFontProgram {

    /** sfnt with TrueType outlines (the usual {@code /FontFile2}). */
    public static final String KIND_TRUETYPE = "truetype";

    /** sfnt carrying a {@code CFF} table ({@code /FontFile3} with {@code /Subtype /OpenType}). */
    public static final String KIND_OPENTYPE = "opentype";

    /** CFF or CFF-CID program ({@code /FontFile3}). */
    public static final String KIND_CFF = "cff";

    /** Type 1 program ({@code /FontFile}), clear text or encrypted. */
    public static final String KIND_TYPE1 = "type1";

    /** None of the formats this module knows. */
    public static final String KIND_UNKNOWN = "unknown";

    /** Table directory entries read at most ({@code pdf.status().maxProgramTables}). */
    public static final int MAX_TABLES = 256;

    /**
     * The dictionary entries a program can come from, in the order they are asked. A
     * {@code /CIDSet} is deliberately not among them: it says which CIDs a font claims to have, it
     * holds no glyphs.
     */
    public static final List<String> PROGRAM_KEYS = List.of("fontFile2", "fontFile3", "fontFile");

    private PdfFontProgram() {
        // static access only
    }

    /**
     * What kind of program these bytes are.
     * @param data the decoded stream
     * @return one of the {@code KIND_*} constants - never null
     */
    public static String identify(byte[] data) {
        if (data == null || data.length < 4) {
            return KIND_UNKNOWN;
        }
        long version = ((data[0] & 0xFFL) << 24) | ((data[1] & 0xFFL) << 16)
                | ((data[2] & 0xFFL) << 8) | (data[3] & 0xFFL);
        if (version == 0x00010000L || version == 0x74727565L) {            // "true"
            return KIND_TRUETYPE;
        }
        if (version == 0x4f54544fL) {                                      // "OTTO"
            return KIND_OPENTYPE;
        }
        if ((data[0] & 0xFF) == 1 && (data[1] & 0xFF) <= 0x0F && (data[2] & 0xFF) >= 3
                && (data[2] & 0xFF) <= 15 && (data[3] & 0xFF) <= 0x0F) {
            return KIND_CFF;
        }
        return isType1(data) ? KIND_TYPE1 : KIND_UNKNOWN;
    }

    /** True when the bytes are an sfnt, whose tables {@link PdfTrueTypeProgram} reads. */
    public static boolean isSfnt(String kind) {
        return KIND_TRUETYPE.equals(kind) || KIND_OPENTYPE.equals(kind);
    }

    /** True when a reader of this module can say anything about that program's tables. */
    public static boolean isRead(String kind) {
        return isSfnt(kind) || KIND_TYPE1.equals(kind) || KIND_CFF.equals(kind);
    }

    /** The clear-text header of a Type 1 program, or the prefix of its binary form. */
    private static boolean isType1(byte[] data) {
        String head = ascii(data, Math.min(data.length, 4096));
        if (head.startsWith("%!PS-AdobeFont") || head.startsWith("%!PS-Adobe-Font")) {
            return true;
        }
        if (head.startsWith("%!PS") && head.contains("eexec")) {
            return true;
        }
        if ((data[0] & 0xFF) == 0x80 && (data[1] & 0xFF) == 0x01) {
            return true;
        }
        return data.length > 12 && ascii(data, 16).contains("pfh ");
    }

    /** The program's first bytes as hex, for the sentence that says "this is none of ours". */
    public static String firstBytes(byte[] data, int count) {
        if (data == null || data.length == 0) {
            return "nothing";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(count, data.length); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format(Locale.ROOT, "%02X", data[i]));
        }
        if (data.length > count) {
            sb.append(" …");
        }
        return sb.toString();
    }

    /**
     * Which program a font draws with, out of the {@code fontFiles} block of its sheet: the first
     * of {@code fontFile2}, {@code fontFile3}, {@code fontFile}.
     *
     * @param fontFiles the {@code fontFiles} map (camelCase keys, values are object numbers)
     * @return {@code [key, objNum]}, or null when the font embeds nothing
     */
    public static Object[] pick(Map<String, Object> fontFiles) {
        if (fontFiles == null) {
            return null;
        }
        for (String key : PROGRAM_KEYS) {
            Object value = fontFiles.get(key);
            if (value instanceof Number number) {
                return new Object[]{key, number.longValue()};
            }
        }
        return null;
    }

    // ===================================================================
    // The program of one font
    // ===================================================================

    /**
     * One font's program, read - or only identified, for the formats whose reader is the next
     * step. Held together so that a single stream read answers both the {@code program} block of a
     * font sheet and the mapping chain's question "does this program hold a usable {@code cmap}".
     */
    public static final class Read {
        private final Integer objNum;
        private final String fileKey;
        private final String streamSubtype;
        private final byte[] data;
        private final String kind;
        private final PdfTrueTypeProgram sfnt;
        private final PdfType1Program type1;
        private final PdfCffProgram cff;
        private final List<String> notes;
        private final boolean sharedNotes;

        private Read(Integer objNum, String fileKey, String streamSubtype, byte[] data,
                     String kind, PdfTrueTypeProgram sfnt, PdfType1Program type1,
                     PdfCffProgram cff, List<String> notes, boolean sharedNotes) {
            this.objNum = objNum;
            this.fileKey = fileKey;
            this.streamSubtype = streamSubtype;
            this.data = data;
            this.kind = kind;
            this.sfnt = sfnt;
            this.type1 = type1;
            this.cff = cff;
            this.notes = notes;
            this.sharedNotes = sharedNotes;
        }

        /** Object number of the program stream. */
        public Integer objNum() {
            return objNum;
        }

        /** Which dictionary entry it came from: {@code fontFile2}, {@code fontFile3},
         * {@code fontFile}. */
        public String fileKey() {
            return fileKey;
        }

        /** The stream's own {@code /Subtype} - a CFF for a CID font names
         * {@code /CIDFontType0C}. */
        public String subtype() {
            return streamSubtype;
        }

        /** What the bytes are. */
        public String kind() {
            return kind;
        }

        /** The decoded bytes ({@code program: 'bytes'} hands them out). */
        public byte[] data() {
            return data;
        }

        /** The sfnt reader, or null when the program is not an sfnt. */
        public PdfTrueTypeProgram sfnt() {
            return sfnt;
        }

        /** The Type 1 reader, or null when the program is not a Type 1 program. */
        public PdfType1Program type1() {
            return type1;
        }

        /** The CFF reader, or null when the program is not a CFF program. */
        public PdfCffProgram cff() {
            return cff;
        }

        /**
         * Whether the program can name the glyph a <em>byte code</em> selects - the {@code /Encoding}
         * array a Type 1 program builds itself. That is step 5 of the chain for a simple font whose
         * dictionary names no {@code /Differences}.
         */
        public boolean canNameCodes() {
            return type1 != null && type1.encodingEntries() > 0;
        }

        /** Whether the program can name a glyph by its index (a plain CFF's {@code Charset}). */
        public boolean canNameGlyphs() {
            return cff != null && cff.hasNames();
        }

        /** The name this program's own {@code /Encoding} gives a code, or null. */
        public String nameOfCode(long code) {
            return type1 == null ? null : type1.nameOfCode(code);
        }

        /** The name this program's {@code Charset} gives a glyph index, or null. */
        public String nameOfGlyph(long glyph) {
            return cff == null ? null : cff.nameOfGlyph(glyph);
        }

        /** True when a CID-keyed CFF's charset holds this CID. */
        public boolean coversCid(long cid) {
            return cff != null && cff.coversCid(cid);
        }

        /** Whether the program can turn a glyph index into a character (step 4 of the chain). */
        public boolean canMapGlyphs() {
            return sfnt != null && sfnt.canMapGlyphs();
        }

        /** Whether the program can answer a width (its {@code hmtx}). */
        public boolean canMeasure() {
            return sfnt != null && sfnt.hasMetrics();
        }

        /** What this reader noticed about the program. */
        public List<String> notes() {
            return List.copyOf(notes);
        }

        /**
         * The {@code program} block of a font sheet.
         * @param mode     {@code "tables"} adds the table directory, {@code "bytes"} adds the raw
         *                 program under {@code data}, anything else is the summary
         * @param maxItems entries listed per list
         * @return the block
         */
        public Map<String, Object> block(String mode, int maxItems) {
            Map<String, Object> out = new LinkedHashMap<>();
            if (objNum != null) {
                out.put("objNum", (long) (int) objNum);
            }
            if (fileKey != null) {
                out.put("file", fileKey);
            }
            out.put("kind", kind);
            if (streamSubtype != null) {
                out.put("subtype", streamSubtype);
            }
            if (sfnt != null) {
                out.putAll(sfnt.block(mode, maxItems));
            } else if (type1 != null) {
                out.putAll(type1.block(maxItems));
            } else if (cff != null) {
                out.putAll(cff.block(maxItems));
            } else {
                out.put("read", false);
            }
            out.put("size", (long) (data == null ? 0 : data.length));
            if (!sharedNotes && sfnt == null && !notes.isEmpty()) {
                // An sfnt prints its own sentences inside its own block; the Type 1 and CFF readers
                // hand theirs to this wrapper, which is where a font sheet reads them from.
                out.put("notes", new ArrayList<Object>(notes));
            }
            if ("bytes".equals(mode) && data != null) {
                // A Java byte[] would depend on the HostAccess policy, so the bridge turns this
                // entry into a real Uint8Array (the byte contract of the JS tool family).
                out.put("data", data);
            }
            return out;
        }
    }

    /**
     * Reads the program of a font.
     *
     * @param document    the document (the stream goes through its filter chain)
     * @param objNum      object number of the program stream
     * @param fileKey     the dictionary entry it came from ({@code fontFile2}, …)
     * @param sharedNotes whether the sentences belong to the caller's note list (the mapping chain
     *                    asks) or to the {@code notes} of the block (a font sheet asks)
     * @param notes       note sink (never null)
     * @return the read program - never null; what it could not do is said in its notes
     */
    public static Read read(PdfDocument document, Integer objNum, String fileKey,
                            boolean sharedNotes, List<String> notes) {
        List<String> sink = sharedNotes ? (notes == null ? new ArrayList<>() : notes)
                : new ArrayList<>();
        byte[] data;
        try {
            data = document.stream(objNum).data();
        } catch (RuntimeException e) {
            // A program that cannot be opened is a statement, not a failure: the font sheet stays
            // readable, only its tables are missing.
            add(sink, "The program in object " + objNum + " of " + document.displayName()
                    + " could not be read (" + e.getMessage() + "): its tables stay unread");
            return new Read(objNum, fileKey, null, null, KIND_UNKNOWN, null, null, null,
                    sink, sharedNotes);
        }
        String subtype = null;
        PdfName name = PdfName.ofDict(document.dictOf(objNum), "Subtype");
        if (name != null) {
            subtype = name.toString();
        }
        String kind = identify(data);
        if (isSfnt(kind)) {
            return new Read(objNum, fileKey, subtype, data, kind,
                    PdfTrueTypeProgram.read(data, sink), null, null, sink, sharedNotes);
        }
        if (KIND_TYPE1.equals(kind)) {
            Map<String, Object> streamDict = document.dictOf(objNum);
            PdfType1Program program = PdfType1Program.read(data,
                    lengthOf(streamDict, "Length1"), lengthOf(streamDict, "Length2"),
                    lengthOf(streamDict, "Length3"));
            List<String> target = sharedNotes ? new ArrayList<>() : new ArrayList<>();
            for (String sentence : program.notes()) {
                target.add(sentence);
                if (sharedNotes && isDefect(sentence)) {
                    // A page that reads its text through this font learns about a broken program,
                    // not about the module's general refusal to execute charstrings - that belongs
                    // to the font sheet, not to every note list that ever opened the font.
                    add(sink, sentence);
                }
            }
            if (program.decrypted()) {
                target.add("The Type 1 program was read as far as its declarations go (its encoding"
                        + " names and its glyph names); its charstrings are not executed, so no"
                        + " width and no outline comes from it and /Widths stays the answer");
            }
            return new Read(objNum, fileKey, subtype, data, kind, null, program, null,
                    sharedNotes ? sink : target, sharedNotes);
        }
        if (KIND_CFF.equals(kind)) {
            PdfCffProgram program = PdfCffProgram.read(data);
            List<String> target = new ArrayList<>();
            for (String sentence : program.notes()) {
                target.add(sentence);
                if (sharedNotes && isDefect(sentence)) {
                    add(sink, sentence);
                }
            }
            target.add("The CFF program was read as far as its DICTs go (its charset and its font"
                    + " families); which glyph carries its own width operand stands inside the"
                    + " charstrings, and those are not interpreted - so /W stays the answer"
                    + " for a width");
            return new Read(objNum, fileKey, subtype, data, kind, null, null, program,
                    sharedNotes ? sink : target, sharedNotes);
        }
        add(sink, notReadSentence(objNum, kind, subtype, data));
        return new Read(objNum, fileKey, subtype, data, kind, null, null, null, sink, sharedNotes);
    }

    /**
     * Whether a sentence names something wrong with <em>this</em> program (a cut section, a charset
     * that points behind the stream) rather than a limit of the module. Only the first kind reaches
     * the note list of a caller that is busy mapping text.
     */
    private static boolean isDefect(String sentence) {
        return sentence.contains("no eexec marker") || sentence.contains("was cut to")
                || sentence.contains("not more than the seed") || sentence.contains("is empty")
                || sentence.contains("outside the stream") || sentence.contains("no Top DICT");
    }

    /** A {@code /Length1}-style number of a stream dictionary, or null. */
    private static Integer lengthOf(Map<String, Object> dict, String key) {
        Object value = dict == null ? null : dict.get(key);
        return value instanceof Number number ? number.intValue() : null;
    }

    /** The sentence of a program that is none of the formats this module reads. */
    private static String notReadSentence(Integer objNum, String kind, String subtype,
                                          byte[] data) {
        String what = objNum != null ? "The program in object " + objNum : "This program";
        if (KIND_TYPE1.equals(kind)) {
            return what + " is a Type 1 program: its clear-text header says so, and what follows"
                    + " eexec is encrypted - its glyph names, its /FontMatrix and its charstrings"
                    + " are the next step of this module, so no width and no name comes out of it";
        }
        if (KIND_CFF.equals(kind)) {
            return what + " is a CFF program"
                    + ("/CIDFontType0C".equals(subtype)
                    ? " for a CID font (/Subtype /CIDFontType0C)" : "")
                    + ": its Top DICT, its Charset names and its Private widths are the next step"
                    + " of this module, so no width and no name comes out of it";
        }
        return what + " is none of the formats this module reads (it starts with "
                + firstBytes(data, 4) + "): no width and no glyph name is taken from it";
    }

    /** One sentence, once. */
    private static void add(List<String> notes, String sentence) {
        if (!notes.contains(sentence)) {
            notes.add(sentence);
        }
    }

    /** The first {@code count} bytes as ISO-8859-1 characters, for a magic-number test. */
    private static String ascii(byte[] data, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count && i < data.length; i++) {
            sb.append((char) (data[i] & 0xFF));
        }
        return sb.toString();
    }
}
