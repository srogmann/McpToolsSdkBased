package org.rogmann.mcp2sdk.pdf;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Converts parsed PDF objects into plain Java structures that a JavaScript caller can walk
 * ({@code String}, {@code Long}, {@code Double}, {@code Boolean}, {@code List}, {@code Map}).
 * <p>
 * The conventions of the resulting view are the ones documented in {@code pdf.help()}:
 * </p>
 * <ul>
 *   <li>a <em>name</em> keeps its slash, so {@code /Type} reads as the string
 *       {@code "/Page"} - names stay recognisable without a type wrapper;</li>
 *   <li>a <em>string</em> is the decoded text (see {@link PdfString#text()});</li>
 *   <li>an <em>indirect reference</em> becomes {@code {ref: "12 0 R", objNum: 12, gen: 0}}
 *       and, with {@code resolveRefs}, an additional {@code target} holding the referenced
 *       value - a reference is never silently replaced;</li>
 *   <li>a <em>stream</em> is its dictionary plus a {@code _stream} summary (length, filters,
 *       decoded size); the bytes themselves come from {@code pdf.stream()} only;</li>
 *   <li>an object that is referenced <em>from several places</em> (the shared
 *       {@code /Resources} of a page tree, a font used on every page) is expanded at its first
 *       occurrence in walk order; every further occurrence reads as
 *       {@code {ref: "...", shared: true}} - the result grows with the number of
 *       <em>distinct</em> objects, not with the number of references;</li>
 *   <li>cycles (a dictionary referencing itself) are cut and reported as
 *       {@code {ref: "...", cycle: true}}.</li>
 * </ul>
 * <p>
 * Termination alone is not enough for a document with hundreds of pages: a walk also has to
 * stay <em>bounded</em>. That is what {@link Ctx#maxNodes(int)} is for - at most that many
 * plain nodes are created per walk, and a branch that would need more is cut like a
 * too-deep one, with {@code {kind: "...", truncated: true}} and a note. Shared subtrees
 * keep the number small in the common case; the budget catches the pathological ones
 * (see {@code docs/js/pdf-resource-explosion-plan.md}).
 * </p>
 */
public final class PdfValues {

    /** Default nesting depth for a converted value. */
    public static final int DEFAULT_MAX_DEPTH = 40;

    /** Default maximum length of a converted string (longer text is truncated with a note). */
    public static final int DEFAULT_MAX_STRING_CHARS = 200_000;

    /**
     * Default upper bound for the plain nodes one walk may create (maps, lists, reference
     * views). It bounds the memory of the result independently of how a document shares its
     * objects; {@code 100000} nodes are a few megabytes of plain Java structures.
     */
    public static final int DEFAULT_MAX_NODES = 100_000;

    /** Shared {@code {kind, truncated}} markers, one per PDF kind (see {@link #cut(String)}). */
    private static final Map<String, Map<String, Object>> CUT_PLACEHOLDERS =
            new ConcurrentHashMap<>();

    /**
     * How often an explanation that can repeat (cycle cut, depth cut, unresolvable reference)
     * is listed before the rest is only summarised. A marked (tagged) document carries
     * hundreds of distinct back edges; naming every one of them pushes the notes that matter
     * out of the answer. The result itself keeps a marker at each single occurrence.
     */
    public static final int EXPLICIT_NOTES_PER_KIND = 5;

    /** Walk options shared by the nested conversion. */
    public static final class Ctx {
        private int maxDepth = DEFAULT_MAX_DEPTH;
        private int maxStringChars = DEFAULT_MAX_STRING_CHARS;
        private int maxNodes = DEFAULT_MAX_NODES;
        private boolean resolveRefs;
        private PdfFilters.Resolver resolver;
        private final List<String> notes = new ArrayList<>();
        /** References on the current path - the cycle detector (backward edges). */
        private final Set<String> activeRefs = new HashSet<>();
        /** References that were fully expanded in this walk - memo for shared subtrees. */
        private final Set<String> expandedRefs = new HashSet<>();
        /** Objects whose cycle cut was already explained (the marker itself repeats). */
        private final Set<String> cycleNotedKeys = new HashSet<>();
        /** How often each repeating explanation was produced - see {@link #noteRepeated}. */
        private final Map<String, NoteSlot> noteSlots = new LinkedHashMap<>();
        private int depth;
        private int nodes;
        private boolean nodeBudgetNoted;
        private boolean sharedNoted;

        /** Maximum nesting depth of the converted structure. */
        public Ctx maxDepth(int value) {
            this.maxDepth = Math.max(1, value);
            return this;
        }

        /** Maximum length of a single string value. */
        public Ctx maxStringChars(int value) {
            this.maxStringChars = Math.max(0, value);
            return this;
        }

        /**
         * Maximum number of plain nodes one walk may create. The bound is soft by one branch
         * (the children of a node that still fits are counted as they are created), but it is
         * never exceeded by more than the width of a single dictionary or array.
         */
        public Ctx maxNodes(int value) {
            this.maxNodes = Math.max(1, value);
            return this;
        }

        /** Whether indirect references are resolved into their target value. */
        public Ctx resolveRefs(boolean value) {
            this.resolveRefs = value;
            return this;
        }

        /** Resolver used to follow references. */
        public Ctx resolver(PdfFilters.Resolver value) {
            this.resolver = value;
            return this;
        }

        /** Node budget of this walk. */
        public int maxNodes() {
            return maxNodes;
        }

        /** Plain nodes created so far (a map, a list or a reference view counts as one). */
        public int nodesUsed() {
            return nodes;
        }

        int remaining() {
            return maxDepth - depth;
        }

        /** Counts one created node into the budget of this walk. */
        void countNode() {
            nodes++;
        }

        /** Whether the walk has used up its node budget. */
        boolean nodeBudgetReached() {
            return nodes >= maxNodes;
        }

        /**
         * Explains a budget cut. Added once per walk - every further cut branch would repeat
         * the same sentence thousands of times and bury the result.
         */
        void noteNodeBudget() {
            if (nodeBudgetNoted) {
                return;
            }
            nodeBudgetNoted = true;
            notes.add("Stopped after " + nodes + " node(s): the node budget of one walk ("
                    + maxNodes + ") is used up, the remaining branches read as"
                    + " {kind: ..., truncated: true}. Read the object you are interested in"
                    + " directly with pdf.object(path, objNum), or lower maxDepth.");
        }

        /**
         * Explains a {@code shared} edge, once per walk (see {@link PdfValues} for the rule).
         */
        void noteShared(PdfReference reference) {
            if (sharedNoted) {
                return;
            }
            sharedNoted = true;
            notes.add("Object " + reference + " is referenced from more than one place: it is"
                    + " expanded at its first occurrence and shown as {ref: \"...\", shared: true}"
                    + " everywhere else, so a document that shares its resources is walked once");
        }

        /**
         * Explains a cut cycle edge. Once per referenced object (the marker {@code cycle: true}
         * itself appears at every cut), and only the first
         * {@link PdfValues#EXPLICIT_NOTES_PER_KIND} objects are named - a tagged document with
         * marked tables produces several hundred distinct back edges, and several hundred
         * identical sentences hide everything else in the notes.
         */
        void noteCycle(PdfReference reference, String key) {
            if (!cycleNotedKeys.add(key)) {
                // The same object at another place: nothing new to name, but the occurrence
                // counts - the summary's number is meant to match the number of markers the
                // caller can count in the result.
                countRepeated("cycle");
                return;
            }
            noteRepeated("cycle",
                    "Reference " + reference + " is part of a reference cycle; it was not"
                            + " resolved further",
                    "", " more reference cycle(s) were cut in this walk; they are not listed one"
                            + " by one - each of them reads as {ref: \"...\", cycle: true}");
        }

        /**
         * Counts one occurrence of a repeating explanation without naming it. Only used for
         * kinds whose slot already exists (the first occurrence always creates it).
         */
        void countRepeated(String kind) {
            NoteSlot slot = noteSlots.get(kind);
            if (slot != null) {
                slot.occurrences++;
            }
        }

        /** Explains a cut at the nesting limit (see {@link #noteRepeated(String, String, String, String)}). */
        void noteDepthLimit(String kind) {
            noteRepeated("depth",
                    "Stopped at nesting depth " + maxDepth + " (" + kind + ") - raise maxDepth or"
                            + " read the object directly with pdf.object(path, objNum)",
                    "The nesting limit was hit ", " more time(s); those branches all read as"
                            + " {kind: \"...\", truncated: true}");
        }

        /** Explains an unresolvable reference (see {@link #noteRepeated(String, String, String, String)}). */
        void noteUnresolved(PdfReference reference, String reason) {
            noteRepeated("unresolved",
                    "Reference " + reference + " could not be resolved: " + reason,
                    "Another ", " reference(s) could not be resolved as well; they read as"
                            + " {ref: \"...\", unresolved: true}");
        }

        /**
         * Adds an explanation that may repeat: the first {@link PdfValues#EXPLICIT_NOTES_PER_KIND}
         * occurrences are listed one by one, the rest is summarised by {@link #notes()} when the
         * walk is read - with the exact number of hidden occurrences.
         */
        void noteRepeated(String kind, String message, String summaryStart, String summaryEnd) {
            NoteSlot slot = noteSlots.get(kind);
            if (slot == null) {
                slot = new NoteSlot();
                slot.summaryStart = summaryStart;
                slot.summaryEnd = summaryEnd;
                noteSlots.put(kind, slot);
            }
            slot.occurrences++;
            if (slot.occurrences <= EXPLICIT_NOTES_PER_KIND) {
                notes.add(message);
            }
        }

        /**
         * The explanations collected in this walk: truncations, unresolvable references,
         * cycles, shared edges and the budget note. Repeating explanations are summarised here
         * (idempotent), so the list stays short even when the walk cut several hundred branches.
         */
        public List<String> notes() {
            for (NoteSlot slot : noteSlots.values()) {
                if (!slot.summarised && slot.occurrences > EXPLICIT_NOTES_PER_KIND) {
                    slot.summarised = true;
                    notes.add(slot.summaryStart
                            + (slot.occurrences - EXPLICIT_NOTES_PER_KIND) + slot.summaryEnd);
                }
            }
            return notes;
        }
    }

    private PdfValues() {
        // Utility class
    }

    /**
     * Counter for one kind of repeating explanation: how often it occurred and whether the
     * summary was already added (so {@link Ctx#notes()} can be called more than once).
     */
    private static final class NoteSlot {
        private int occurrences;
        private boolean summarised;
        private String summaryStart = "";
        private String summaryEnd = "";
    }

    /**
     * Converts a parsed PDF value into a plain structure.
     * @param value parsed value (may be null)
     * @param ctx conversion context
     * @return plain value
     */
    public static Object toPlain(Object value, Ctx ctx) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean || value instanceof Long || value instanceof Double
                || value instanceof Integer || value instanceof String) {
            return value;
        }
        if (value instanceof PdfName name) {
            return name.toString();
        }
        if (value instanceof PdfString string) {
            return plainString(string, ctx);
        }
        if (value instanceof PdfReference reference) {
            return plainReference(reference, ctx);
        }
        if (ctx.depth >= ctx.maxDepth) {
            ctx.noteDepthLimit(PdfParser.kindOf(value));
            return cut(PdfParser.kindOf(value));
        }
        if (ctx.nodeBudgetReached()) {
            ctx.noteNodeBudget();
            return cut(PdfParser.kindOf(value));
        }
        ctx.depth++;
        try {
            if (value instanceof List<?> list) {
                ctx.countNode();
                List<Object> out = new ArrayList<>(list.size());
                for (Object item : list) {
                    out.add(toPlain(item, ctx));
                }
                return out;
            }
            if (value instanceof Map<?, ?> map) {
                ctx.countNode();
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    out.put(String.valueOf(entry.getKey()), toPlain(entry.getValue(), ctx));
                }
                return out;
            }
            if (value instanceof PdfStream stream) {
                ctx.countNode();
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : stream.dict().entrySet()) {
                    out.put(String.valueOf(entry.getKey()), toPlain(entry.getValue(), ctx));
                }
                return out;
            }
        } finally {
            ctx.depth--;
        }
        ctx.noteRepeated("unsupported",
                "Value of unsupported type " + value.getClass().getName() + " was stringified",
                "Another ", " value(s) of unsupported type(s) were stringified as well");
        return String.valueOf(value);
    }

    /**
     * The marker that replaces a cut branch: {@code {kind: "...", truncated: true}}.
     * <p>
     * Markers are shared per kind. A walk that has used up its budget still walks over every
     * remaining element of a huge array, and it must not allocate one map per element for
     * that - otherwise the budget would only move the explosion from the content to the
     * markers. The position in the parent (array index, dictionary key) still says where the
     * cut happened; the note says what to do about it.
     * </p>
     * @param kind the PDF kind the cut branch would have been
     * @return the shared placeholder
     */
    private static Map<String, Object> cut(String kind) {
        Map<String, Object> known = CUT_PLACEHOLDERS.get(kind);
        if (known != null) {
            return known;
        }
        Map<String, Object> marker = Map.of("kind", kind, "truncated", true);
        // The kinds come from PdfParser.kindOf() and are few; the size cap keeps a hostile
        // file from growing this cache.
        if (CUT_PLACEHOLDERS.size() < 32) {
            CUT_PLACEHOLDERS.putIfAbsent(kind, marker);
        }
        return marker;
    }

    /**
     * Converts a string value, truncating very long texts (with a note, never silently).
     */
    private static Object plainString(PdfString string, Ctx ctx) {
        String text = string.text();
        if (ctx.maxStringChars > 0 && text.length() > ctx.maxStringChars) {
            ctx.noteRepeated("string",
                    "A string of " + text.length() + " characters was truncated to "
                            + ctx.maxStringChars + " (raise maxStringChars to see all of it)",
                    "Another ", " string(s) were truncated the same way (see maxStringChars)");
            return text.substring(0, ctx.maxStringChars);
        }
        return text;
    }

    /**
     * Converts a reference: the reference itself, plus the target when the caller asked for
     * resolution. Cycles and unresolvable references are reported, not hidden, and an object
     * that this walk has expanded before is not expanded a second time (it reads as
     * {@code shared}).
     */
    private static Object plainReference(PdfReference reference, Ctx ctx) {
        if (ctx.nodeBudgetReached()) {
            // Checked before anything is created: after the budget ran out the walk still
            // visits the remaining references, but it must not allocate a view per reference
            // any more - that would trade the content explosion for a marker explosion.
            ctx.noteNodeBudget();
            return cut("reference");
        }
        ctx.countNode();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ref", reference.toString());
        out.put("objNum", (long) reference.objNum());
        out.put("gen", (long) reference.gen());
        if (!ctx.resolveRefs || ctx.resolver == null) {
            return out;
        }
        String key = reference.key();
        if (ctx.expandedRefs.contains(key)) {
            // Variant A of the shared-subtree rule: the first occurrence holds the content,
            // every other one only points at it. Duplicating the expanded value here would
            // rebuild exactly the explosion the memoization is meant to prevent.
            out.put("shared", true);
            ctx.noteShared(reference);
            return out;
        }
        if (!ctx.activeRefs.add(key)) {
            out.put("cycle", true);
            ctx.noteCycle(reference, key);
            return out;
        }
        try {
            Object target = ctx.resolver.resolve(reference);
            if (target instanceof PdfStream stream) {
                out.put("target", toPlain(stream.dict(), ctx));
                out.put("targetIsStream", true);
            } else {
                out.put("target", toPlain(target, ctx));
            }
            // Memoised only after the subtree is complete: while the branch is still open the
            // key belongs to activeRefs (cycle detection), and caching a half-expanded value
            // would cut the walk in the middle of its own descent.
            ctx.expandedRefs.add(key);
        } catch (PdfException e) {
            out.put("unresolved", true);
            ctx.noteUnresolved(reference, e.getMessage());
        } finally {
            ctx.activeRefs.remove(key);
        }
        return out;
    }
}
