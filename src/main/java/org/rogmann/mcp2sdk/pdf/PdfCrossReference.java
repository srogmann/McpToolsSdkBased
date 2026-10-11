package org.rogmann.mcp2sdk.pdf;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The cross-reference data of a PDF file: where every object lives, plus the sections it
 * was built from (ISO 32000-1 sections 7.5.4 and 7.5.7).
 * <p>
 * Two flavours exist and both are read here:
 * </p>
 * <ul>
 *   <li>the classic cross-reference <em>table</em> followed by {@code trailer}, and</li>
 *   <li>the cross-reference <em>stream</em> of PDF 1.5 and later (whose dictionary is the
 *       trailer).</li>
 * </ul>
 * <p>
 * Incremental updates chain several sections through {@code /Prev}. The chain is walked
 * from the last {@code startxref}; the newest definition of an object wins, which is exactly
 * how a reader must treat an updated file - {@link #sections()} therefore shows the whole
 * update history, not just the active table.
 * </p>
 */
public final class PdfCrossReference {

    /** Free (deleted) object. */
    public static final int TYPE_FREE = 0;
    /** Object stored uncompressed at a file offset. */
    public static final int TYPE_UNCOMPRESSED = 1;
    /** Object stored inside an object stream ({@code /ObjStm}). */
    public static final int TYPE_COMPRESSED = 2;

    /**
     * One cross-reference entry.
     *
     * @param objNum object number
     * @param gen generation number (for type 2: the /ObjStm generation)
     * @param type {@link #TYPE_FREE}, {@link #TYPE_UNCOMPRESSED} or {@link #TYPE_COMPRESSED}
     * @param offset file offset of the object (type 1), otherwise -1
     * @param streamObj object number of the object stream (type 2), otherwise -1
     * @param index index of the object inside the object stream (type 2), otherwise -1
     */
    public record Entry(int objNum, int gen, int type, long offset, int streamObj, int index) {

        /** Human-readable type name. */
        public String typeName() {
            return switch (type) {
                case TYPE_FREE -> "free";
                case TYPE_UNCOMPRESSED -> "uncompressed";
                case TYPE_COMPRESSED -> "compressed";
                default -> "unknown";
            };
        }

        /** Where the object lives, as a plain value. */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("objNum", (long) objNum);
            map.put("gen", (long) gen);
            map.put("kind", typeName());
            if (type == TYPE_UNCOMPRESSED) {
                map.put("offset", offset);
            } else if (type == TYPE_COMPRESSED) {
                map.put("objStm", (long) streamObj);
                map.put("index", (long) index);
            }
            return map;
        }
    }

    /**
     * One cross-reference section: a classic table or a cross-reference stream.
     */
    public static final class Section {
        // Package-private: PdfFileStructure fills the section while reading the chain.
        int sectionIndex;
        long offset;
        String kind = "unknown";
        long startxref = -1;
        long size = -1;
        Long prev;
        int[] w;
        int[] w2;
        final List<int[]> indexRanges = new ArrayList<>();
        Map<String, Object> trailer = Map.of();
        Integer streamObjNum;
        int entryCount;
        private final List<String> warnings = new ArrayList<>();

        /** Section number, 0 for the newest (active) section. */
        public int sectionIndex() {
            return sectionIndex;
        }

        /** File offset the section was read from. */
        public long offset() {
            return offset;
        }

        /** {@code table} or {@code stream}. */
        public String kind() {
            return kind;
        }

        /** The {@code startxref} value that led to this section. */
        public long startxref() {
            return startxref;
        }

        /** The {@code /Size} declared by the section (or -1). */
        public long size() {
            return size;
        }

        /** The {@code /Prev} value (file offset of the older section) or null. */
        public Long prev() {
            return prev;
        }

        /** The {@code /W} field widths of a cross-reference stream. */
        public int[] w() {
            return w;
        }

        /** The dictionary that acts as trailer (the {@code trailer} dict or the stream dict). */
        public Map<String, Object> trailer() {
            return trailer;
        }

        /** Object number of the cross-reference stream itself, or null for a table. */
        public Integer streamObjNum() {
            return streamObjNum;
        }

        /** Number of entries this section contributes. */
        public int entryCount() {
            return entryCount;
        }

        /** Warnings produced while reading this section. */
        public List<String> warnings() {
            return warnings;
        }

        /** Plain view of the section (without the trailer body). */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("section", (long) sectionIndex);
            map.put("kind", kind);
            map.put("offset", offset);
            if (startxref >= 0) {
                map.put("startxref", startxref);
            }
            if (size >= 0) {
                map.put("size", size);
            }
            if (prev != null) {
                map.put("prev", prev);
            }
            if (streamObjNum != null) {
                map.put("streamObjNum", (long) streamObjNum);
            }
            if (w != null) {
                map.put("w", toLongList(w));
            }
            if (w2 != null) {
                map.put("w2", toLongList(w2));
            }
            if (!indexRanges.isEmpty()) {
                List<Object> ranges = new ArrayList<>();
                for (int[] range : indexRanges) {
                    ranges.add(Map.of("first", (long) range[0], "count", (long) range[1]));
                }
                map.put("index", ranges);
            }
            map.put("entries", (long) entryCount);
            if (!warnings.isEmpty()) {
                map.put("warnings", new ArrayList<Object>(warnings));
            }
            return map;
        }

        private static List<Object> toLongList(int[] values) {
            List<Object> list = new ArrayList<>(values.length);
            for (int value : values) {
                list.add((long) value);
            }
            return list;
        }
    }

    private final List<Section> sections;
    private final Map<Integer, Entry> entries;
    private final Map<String, Object> trailer;
    private final long startxref;
    private final boolean salvaged;

    /**
     * Creates the cross-reference view.
     * @param sections sections in order (newest first)
     * @param entries merged entries (object number -&gt; entry)
     * @param trailer active trailer dictionary (never null)
     * @param startxref the {@code startxref} value that was used
     * @param salvaged true if entries were recovered by scanning the file
     */
    public PdfCrossReference(List<Section> sections, Map<Integer, Entry> entries,
                            Map<String, Object> trailer, long startxref, boolean salvaged) {
        this.sections = List.copyOf(sections);
        this.entries = Map.copyOf(entries);
        this.trailer = trailer;
        this.startxref = startxref;
        this.salvaged = salvaged;
    }

    /**
     * Looks up one object.
     * @param objNum object number
     * @return the entry or {@code null} when the object is not registered
     */
    public Entry entry(int objNum) {
        return entries.get(objNum);
    }

    /**
     * Whether the object is registered (in any state, including free).
     * @param objNum object number
     * @return true if there is an entry
     */
    public boolean has(int objNum) {
        return entries.containsKey(objNum);
    }

    /**
     * All registered object numbers, sorted.
     * @return object numbers
     */
    public Collection<Integer> objectNumbers() {
        return new TreeSet<>(entries.keySet());
    }

    /** Number of registered objects (including free ones). */
    public int objectCount() {
        return entries.size();
    }

    /** Sections of the update chain, newest first. */
    public List<Section> sections() {
        return sections;
    }

    /** The active trailer dictionary. */
    public Map<String, Object> trailer() {
        return trailer;
    }

    /** The {@code startxref} value the chain was read from. */
    public long startxref() {
        return startxref;
    }

    /** Whether entries were recovered by a file scan (damaged cross-reference data). */
    public boolean salvaged() {
        return salvaged;
    }

    /**
     * Plain view of the section chain.
     * @return list of section maps
     */
    public List<Map<String, Object>> sectionsAsList() {
        List<Map<String, Object>> list = new ArrayList<>(sections.size());
        for (Section section : sections) {
            list.add(section.toMap());
        }
        return list;
    }
}
