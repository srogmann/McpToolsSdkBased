package org.rogmann.mcp2sdk.pdf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Resolves the <em>named resources</em> of one content stream (ISO 32000-1, 7.8.3 "Resource
 * Dictionaries", Table 3.30): a content stream almost never names an object number - {@code Tf}
 * names a font, {@code Do} an external object, {@code gs} a graphics state, {@code sh} a shading
 * and {@code BDC}/{@code MP} a property list. This class turns one of those names into an address.
 *
 * <p>
 * Seven categories exist, and they are all the specification has: {@code ExtGState},
 * {@code ColorSpace}, {@code Pattern}, {@code Shading}, {@code XObject}, {@code Font} and
 * {@code Properties}. {@code ProcSet} is the eighth key of the same table but holds <em>names</em>
 * of procedure sets, not objects, so it is never addressed - {@link #procSets()} reads it as a list
 * of names and nothing invents an object number for it.
 * </p>
 *
 * <p>
 * Which dictionary a stream looks in is decided in 3.7.2 (S. 153) and answered by the two
 * factories: <em>a page</em> uses the {@code Resources} of the page - inheritable, so a
 * {@code /Pages} ancestor may carry it, and {@link #resourcesFrom()} then says
 * {@code pages:<objNum of the ancestor>}, because inheritance is all-or-nothing per 3.6.2 and is
 * never mixed category by category - and <em>every other stream</em> (a form, a pattern, a Type 3
 * font's glyph description, an appearance) uses the {@code Resources} of <em>its own</em>
 * dictionary. A form or a Type 3 glyph may omit that entry, in which case the resources are looked
 * up in the page's dictionary; the specification calls that practice "not recommended", and it is
 * the reason a listing of the form alone never finds the fonts of a diagram. So this reader says
 * so: {@code resourcesFrom} reads {@code stream→page (form /Fm1 names none)} and one note is added.
 * </p>
 *
 * <p>
 * What it deliberately does <em>not</em> do is interpret what it finds. A colour space, a pattern
 * and a shading are shown as the dictionary or array they are - subtype, shape, address - because
 * colour semantics is not this module's subject; {@link PdfFont} reads a font dictionary, and
 * anything else a caller follows with {@code pdf.object(path, objNum)}.
 * </p>
 *
 * <p>
 * Missing is an answer too: a name the resources do not name resolves to an entry with
 * {@code missing}, and a reference whose object cannot be read resolves to one carrying the
 * attempted address, {@code missing} and one note - never an exception, because a page with one
 * broken resource is still a page. Only a <em>call</em> that asks for a category the specification
 * does not have is refused, and the message names the seven.
 * </p>
 */
public final class PdfResources {

    /**
     * The categories of Table 3.30 (S. 154), in the order the specification lists them.
     * {@code ProcSet} is deliberately not one of them: it is an array of names.
     */
    public static final List<String> CATEGORIES = List.of("ExtGState", "ColorSpace", "Pattern",
            "Shading", "XObject", "Font", "Properties");

    /** Entries one category lists at most - a 65 000-name dictionary is not a listing. */
    public static final int MAX_ENTRIES = 512;

    /** Keys of the note catalog this class can raise. */
    public static final String NOTE_RESOURCE_UNREADABLE = "resource-unreadable";
    public static final String NOTE_ENTRIES_CUT = "resource-entries-cut";
    public static final String NOTE_STREAM_WITHOUT_RESOURCES = "resource-lookup-on-page";
    public static final String NOTE_FOREIGN_RESOURCES = "resource-dictionary-foreign";
    public static final String NOTE_LOOKUP_CHAINED = "resource-lookup-chained";
    public static final String NOTE_PROCSET_UNREADABLE = "resource-procset-unreadable";

    /** Where a page's dictionary came from (3.6.2). */
    public static final String FROM_PAGE = "page";
    /** Where a stream's dictionary came from (3.7.2). */
    public static final String FROM_STREAM = "stream";
    /** No dictionary was found, or the one the file wrote is not a dictionary. */
    public static final String FROM_NONE = "none";

    /** The {@code stream→page} answer of 3.7.2, before the label of the stream that needs it. */
    public static final String FROM_STREAM_THROUGH_PAGE = "stream→page";

    /**
     * One resource as a dictionary names it.
     *
     * @param resourceName the resource name with slash (the form a content stream writes)
     * @param category     one of {@link #CATEGORIES}
     * @param objNum       object number of the resource, or {@code null} when the dictionary holds
     *                     the value inline
     * @param dict         the resolved dictionary (a stream's dictionary for a stream object), or
     *                     {@code null} for an array, a name or a lost object
     * @param subtype      {@code /Subtype} of that dictionary, e.g. {@code "/Form"} (a PostScript
     *                     XObject reads {@code "/PS"})
     * @param shape        what the value is: {@code dictionary}, {@code stream}, {@code array},
     *                     {@code name}, … ({@link PdfParser#kindOf}), or {@code absent} /
     *                     {@code unreadable} - the answer to "why is there no objNum"
     * @param resourcesFrom where this entry's dictionary came from - see {@link #resourcesFrom()}
     * @param indirect     whether the dictionary holds a reference (rather than the value)
     * @param missing      whether nothing could be read: the name is not named, or its object is
     *                     lost
     */
    public record Entry(String resourceName, String category, Integer objNum,
                        Map<String, Object> dict, String subtype, String shape,
                        String resourcesFrom, boolean indirect, boolean missing) {

        /** Whether a dictionary stands behind the name (not for an array, a name or a lost object). */
        public boolean hasDictionary() {
            return dict != null;
        }
    }

    private final PdfDocument document;
    /** The resource dictionary itself; empty when the page or stream names none. */
    private final Map<String, Object> dict;
    /** The {@link #resourcesFrom()} answer. */
    private final String from;
    /** "page object 7" / "form XObject /Fm1" - whose dictionary this is, for the notes. */
    private final String owner;
    private final List<String> notes;
    /** Category key -> resolved sub-dictionary (absent or foreign: {@code null}). */
    private final Map<String, Map<String, Object>> subDicts = new LinkedHashMap<>();
    /** Category key -> the kind of the value the file wrote there. */
    private final Map<String, String> subKinds = new LinkedHashMap<>();
    /** Category key -> built entries. */
    private final Map<String, List<Entry>> listings = new LinkedHashMap<>();
    /** Whether these are a page's resources borrowed by a stream without its own. */
    private final boolean borrowed;
    private boolean truncated;

    private PdfResources(PdfDocument document, Map<String, Object> dict, String from, String owner,
                         boolean borrowed, List<String> notes) {
        this.document = document;
        this.dict = dict == null ? Map.of() : dict;
        this.from = from;
        this.owner = owner;
        this.borrowed = borrowed;
        this.notes = notes == null ? new ArrayList<>() : notes;
    }

    // ===================================================================
    // The two dictionaries of 3.7.2
    // ===================================================================

    /**
     * The resources of a page, following the {@code /Parent} chain like every other inheritable
     * page attribute (3.6.2: the next ancestor that names it wins, and it wins whole).
     *
     * @param document   the document
     * @param pageObjNum page object number
     * @param notes      note/warning sink (never null)
     * @return the resolver (empty when neither the page nor its ancestors name {@code Resources})
     */
    public static PdfResources ofPage(PdfDocument document, int pageObjNum, List<String> notes) {
        return ofPage(document, pageObjNum < 0 ? Map.of() : document.dictOf(pageObjNum),
                pageObjNum < 0 ? null : (Integer) pageObjNum, notes);
    }

    /**
     * The resources of a page dictionary a caller already holds - the page listing walks the tree
     * itself and has the dictionary in hand.
     *
     * @param document   the document
     * @param pageDict   the page dictionary
     * @param pageObjNum its object number, or {@code null} for an inline page object
     * @param notes      note/warning sink (never null)
     * @return the resolver
     */
    public static PdfResources ofPage(PdfDocument document, Map<String, Object> pageDict,
                                      Integer pageObjNum, List<String> notes) {
        Map<String, Object> current = pageDict;
        Integer nodeObjNum = pageObjNum;
        boolean onThePage = true;
        int hops = 0;
        while (current != null && hops++ < PdfDocument.MAX_PAGE_DEPTH) {
            Object raw = current.get("Resources");
            if (raw != null) {
                String owner = onThePage ? ownerOf(pageObjNum) : "the /Pages node " + nodeObjNum;
                Object resolved = deref(document, raw, "/Resources of " + owner, notes);
                Map<String, Object> found = asDict(resolved);
                if (found == null) {
                    if (resolved != null) {
                        note(notes, NOTE_FOREIGN_RESOURCES, "The /Resources of " + owner + " is "
                                + PdfParser.kindOf(resolved) + " instead of a dictionary - no"
                                + " resource of this page is listed");
                    }
                    return new PdfResources(document, Map.of(), FROM_NONE, owner, false, notes);
                }
                String from = onThePage ? FROM_PAGE
                        : nodeObjNum == null ? "pages" : "pages:" + nodeObjNum;
                return new PdfResources(document, found, from, owner, false, notes);
            }
            Object parent = current.get("Parent");
            if (!(parent instanceof PdfReference reference)) {
                break;
            }
            nodeObjNum = reference.objNum();
            onThePage = false;
            Object parentValue = deref(document, parent, "/Parent of " + ownerOf(pageObjNum),
                    notes);
            current = asDict(parentValue);
        }
        return new PdfResources(document, Map.of(), FROM_NONE, ownerOf(pageObjNum), false, notes);
    }

    /**
     * The resources of a stream that is not a page: a form XObject, a pattern, a Type 3 font's
     * glyph description or an annotation appearance. 3.7.2 gives such a stream its <em>own</em>
     * dictionary; without one the page's dictionary applies, which the specification discourages
     * and this reader names.
     *
     * @param document     the document
     * @param streamDict   the stream's dictionary
     * @param pageFallback the page whose resources apply when this stream names none, or
     *                     {@code null} when no page is known
     * @param notes        note/warning sink (never null)
     * @return the resolver
     */
    public static PdfResources ofStream(PdfDocument document, Map<String, Object> streamDict,
                                        PdfResources pageFallback, List<String> notes) {
        return ofStream(document, streamDict, null, pageFallback, notes);
    }

    /**
     * Same, naming the stream so the note and {@link #resourcesFrom()} can say <em>which</em>
     * stream falls back ({@code "form XObject /Fm1"}, {@code "Type-3 glyph /g2"}, …).
     *
     * @param document     the document
     * @param streamDict   the stream's dictionary
     * @param streamLabel  what the stream is, for the notes (may be {@code null})
     * @param pageFallback the page's resources, used when this stream names none (may be
     *                     {@code null})
     * @param notes        note/warning sink (never null)
     * @return the resolver
     */
    public static PdfResources ofStream(PdfDocument document, Map<String, Object> streamDict,
                                        String streamLabel, PdfResources pageFallback,
                                        List<String> notes) {
        Map<String, Object> stream = streamDict == null ? Map.of() : streamDict;
        String label = streamLabel == null || streamLabel.isEmpty() ? "this stream" : streamLabel;
        Object raw = stream.get("Resources");
        if (raw != null) {
            Object resolved = deref(document, raw, "/Resources of " + label, notes);
            Map<String, Object> found = asDict(resolved);
            if (found == null) {
                if (resolved != null) {
                    note(notes, NOTE_FOREIGN_RESOURCES, "The /Resources of " + label + " is "
                            + PdfParser.kindOf(resolved) + " instead of a dictionary - no resource"
                            + " of this stream is listed");
                }
                return new PdfResources(document, Map.of(), FROM_NONE, label, false, notes);
            }
            return new PdfResources(document, found, FROM_STREAM, label, false, notes);
        }
        if (pageFallback == null) {
            return new PdfResources(document, Map.of(), FROM_NONE, label, false, notes);
        }
        if (pageFallback.borrowed) {
            // 3.7.2 sends a stream without resources to the page. A page that is itself a borrowed
            // dictionary would make it a chain, and a chain of lookups is where a loop hides.
            note(notes, NOTE_LOOKUP_CHAINED, "The /Resources of " + label + " should be looked up"
                    + " in the page, but that dictionary is itself borrowed from another stream ("
                    + pageFallback.owner + ") - the lookup stops here");
            return new PdfResources(document, Map.of(), FROM_NONE, label, false, notes);
        }
        note(notes, NOTE_STREAM_WITHOUT_RESOURCES, "The " + label + " names no /Resources, so its"
                + " names are looked up in the page (PDF 1.7 3.7.2: \"This practice is not"
                + " recommended\")");
        return new PdfResources(document, pageFallback.dict,
                FROM_STREAM_THROUGH_PAGE + " (" + label + " names none)", label, true, notes);
    }

    // ===================================================================
    // Name -> address
    // ===================================================================

    /**
     * Resolves one name. {@code "/F0"} and {@code "F0"} mean the same resource - the slash is the
     * form results use and the form a content stream writes, so that is what an entry carries.
     *
     * @param category one of {@link #CATEGORIES}
     * @param name     the resource name, with or without its slash
     * @return the entry; {@code missing()} when the dictionary does not name that resource or its
     *         object cannot be read - a statement, not an error
     * @throws PdfException when the category is not one of the seven (the message names them)
     */
    public Entry resolve(String category, String name) {
        String required = requireCategory(category);
        String key = requireName(name);
        Map<String, Object> sub = subDict(required);
        Object raw = sub == null ? null : sub.get(key);
        if (raw == null) {
            return new Entry("/" + key, required, null, null, null, "absent", from, false, true);
        }
        return entry(required, key, raw);
    }

    /** Every resource of every category, in the specification's category order. */
    public List<Entry> list() {
        List<Entry> all = new ArrayList<>();
        for (String category : CATEGORIES) {
            all.addAll(list(category));
        }
        return all;
    }

    /**
     * Every resource of one category, in name order, budgeted by {@link #MAX_ENTRIES} (a cut sets
     * {@link #truncated()} and says so once).
     *
     * @param category one of {@link #CATEGORIES}
     * @return the entries (empty when the category is absent or foreign)
     * @throws PdfException when the category is not one of the seven
     */
    public List<Entry> list(String category) {
        return list(category, MAX_ENTRIES);
    }

    /**
     * Same with a smaller bound - a summary that shows twenty names of a five-hundred-name font
     * dictionary should not resolve four hundred and eighty objects to throw their dictionaries
     * away. A cut below {@link #MAX_ENTRIES} does not mark {@link #truncated()}: the caller chose
     * the bound and marks the cut itself.
     *
     * @param category one of {@link #CATEGORIES}
     * @param limit entries built at most (0 or more than {@link #MAX_ENTRIES} = the full budget)
     * @return the entries (empty when the category is absent or foreign)
     */
    public List<Entry> list(String category, int limit) {
        String required = requireCategory(category);
        int bound = limit <= 0 || limit > MAX_ENTRIES ? MAX_ENTRIES : limit;
        boolean wholeListing = bound == MAX_ENTRIES;
        if (wholeListing) {
            List<Entry> cached = listings.get(required);
            if (cached != null) {
                return cached;
            }
        }
        List<Entry> built = new ArrayList<>();
        Map<String, Object> sub = subDict(required);
        if (sub != null) {
            Map<String, Object> sorted = new TreeMap<>();
            sorted.putAll(sub);
            int shown = 0;
            for (Map.Entry<String, Object> pair : sorted.entrySet()) {
                if (shown++ >= bound) {
                    if (wholeListing) {
                        truncated = true;
                        note(notes, NOTE_ENTRIES_CUT, "Only the first " + MAX_ENTRIES + " of "
                                + sub.size() + " entries under /" + required + " of " + owner
                                + " are listed (maxResourceEntries)");
                    }
                    break;
                }
                built.add(entry(required, pair.getKey(), pair.getValue()));
            }
        }
        List<Entry> immutable = List.copyOf(built);
        if (wholeListing) {
            listings.put(required, immutable);
        }
        return immutable;
    }

    /**
     * The {@code ProcSet} names (Table 3.30: an array, not a namespace). These are never addressed
     * - a procedure set is not a PDF object.
     * @return the names with slash (empty when the key is absent)
     */
    public List<String> procSets() {
        Object raw = dict.get("ProcSet");
        if (raw == null) {
            return List.of();
        }
        Object resolved = deref(document, raw, "/ProcSet of " + owner, notes);
        if (resolved == null) {
            return List.of();
        }
        List<Object> items = resolved instanceof List<?> list ? castList(list)
                : List.of(resolved);
        List<String> names = new ArrayList<>();
        for (Object item : items) {
            Object value = deref(document, item, "/ProcSet of " + owner, notes);
            if (value instanceof PdfName name) {
                names.add(name.toString());
            } else if (value != null) {
                note(notes, NOTE_PROCSET_UNREADABLE, "An entry of /ProcSet in " + owner + " is "
                        + PdfParser.kindOf(value) + " instead of a name and is left out");
            }
        }
        return names;
    }

    /**
     * Where these resources came from - one of five answers:
     * <ul>
     *   <li>{@code page} - the page dictionary names {@code Resources} itself,</li>
     *   <li>{@code pages:<objNum>} - a {@code /Pages} ancestor does (3.6.2, whole dictionary),</li>
     *   <li>{@code stream} - the stream's own dictionary does (3.7.2),</li>
     *   <li>{@code stream→page (form /Fm1 names none)} - a stream without one, reading the page's
     *       (3.7.2, "not recommended"),</li>
     *   <li>{@code none} - nothing was found, or what the file wrote is not a dictionary.</li>
     * </ul>
     */
    public String resourcesFrom() {
        return from;
    }

    /** Whether a category listing was cut by {@link #MAX_ENTRIES}. */
    public boolean truncated() {
        return truncated;
    }

    /** Whether no dictionary was found at all. */
    public boolean isEmpty() {
        return dict.isEmpty();
    }

    /** The dictionary itself (name -&gt; raw value, references included); for reading, not writing. */
    public Map<String, Object> dict() {
        return dict;
    }

    /** Whose dictionary this is, in words ("page object 7", "form XObject /Fm1"). */
    public String owner() {
        return owner;
    }

    /**
     * How many names the category dictionary holds - the full number, whether or not a listing
     * was cut. A summary shows this next to the (possibly shorter) list it prints.
     *
     * @param category one of {@link #CATEGORIES}
     */
    public int size(String category) {
        Map<String, Object> sub = subDict(requireCategory(category));
        return sub == null ? 0 : sub.size();
    }

    /** The kind of the value the file wrote under a category key: {@code dictionary},
     *  {@code array}, {@code name}, {@code unreadable} or {@code absent}. A listing uses this to
     *  say why a category it asked for stays empty - "/Font is an array" is a different statement
     *  from "/Font names nothing".
     *
     * @param category one of {@link #CATEGORIES}, or {@code "ProcSet"}
     * @throws PdfException when the key is neither of those
     */
    public String categoryShape(String category) {
        if (category != null && "ProcSet".equals(category)) {
            Object raw = dict.get(category);
            return raw == null ? "absent" : PdfParser.kindOf(deref(document, raw,
                    "/ProcSet of " + owner, List.of()));
        }
        requireCategory(category);
        if (subKinds.containsKey(category)) {
            return subKinds.get(category);
        }
        subDict(category);
        return subKinds.getOrDefault(category, "absent");
    }

    // ===================================================================
    // Internals
    // ===================================================================

    /** The sub-dictionary of one category, read once; {@code null} when absent or foreign. */
    private Map<String, Object> subDict(String category) {
        if (subDicts.containsKey(category)) {
            return subDicts.get(category);
        }
        Object raw = dict.get(category);
        Map<String, Object> sub = null;
        String kind;
        if (raw == null) {
            kind = "absent";
        } else {
            Object resolved = deref(document, raw, "/Resources /" + category + " of " + owner,
                    notes);
            sub = asDict(resolved);
            if (sub != null) {
                kind = "dictionary";
            } else if (resolved == null) {
                kind = "unreadable";
            } else {
                kind = PdfParser.kindOf(resolved);
                note(notes, NOTE_RESOURCE_UNREADABLE, "/Resources /" + category + " of " + owner
                        + " is " + kind + " instead of a dictionary - no " + plain(category)
                        + " resource of this dictionary is listed");
            }
        }
        subKinds.put(category, kind);
        subDicts.put(category, sub);
        return sub;
    }

    /** One entry of one category, from the raw value the dictionary holds. */
    private Entry entry(String category, String key, Object raw) {
        Integer objNum = null;
        boolean indirect = false;
        Object value = raw;
        if (raw instanceof PdfReference reference) {
            indirect = true;
            objNum = reference.objNum();
            try {
                value = document.dereference(reference);
            } catch (PdfException e) {
                note(notes, NOTE_RESOURCE_UNREADABLE, "The " + category + " resource /" + key
                        + " of " + owner + " cannot be read (" + e.getMessage() + ")");
                return new Entry("/" + key, category, objNum, null, null, "unreadable", from,
                        true, true);
            }
        }
        Map<String, Object> dictValue = null;
        if (value instanceof PdfStream stream) {
            dictValue = stream.dict();
        } else if (value instanceof Map<?, ?> map) {
            dictValue = castDict(map);
        }
        String subtype = null;
        if (dictValue != null) {
            PdfName name = PdfName.ofDict(dictValue, "Subtype");
            subtype = name == null ? null : name.toString();
        }
        return new Entry("/" + key, category, objNum, dictValue,
                subtype, PdfParser.kindOf(value), from, indirect, false);
    }

    /** A category is one of the seven names - anything else is a call error, not an empty list. */
    private static String requireCategory(String category) {
        if (category != null && CATEGORIES.contains(category)) {
            return category;
        }
        throw new PdfException("Unknown resource category '" + category + "': a content stream"
                + " names its resources in the seven categories of PDF 1.7 Table 3.30 ("
                + String.join(", ", CATEGORIES) + "); /ProcSet is an array of names and is read"
                + " with procSets()");
    }

    /** {@code "/F0"} and {@code "F0"} are one name; results carry the slash. */
    private static String requireName(String name) {
        if (name == null || name.isEmpty() || "/".equals(name)) {
            throw new PdfException("A resource name is required (a content stream writes it as"
                    + " e.g. /F0)");
        }
        return name.startsWith("/") ? name.substring(1) : name;
    }

    /** Resolves a reference; a lost object is a finding in {@code notes}, never an exception. */
    private static Object deref(PdfDocument document, Object value, String what,
                                List<String> notes) {
        if (!(value instanceof PdfReference)) {
            return value;
        }
        try {
            return document.dereference(value);
        } catch (PdfException e) {
            note(notes, NOTE_RESOURCE_UNREADABLE, "The " + what + " (object "
                    + ((PdfReference) value).objNum() + ") cannot be read (" + e.getMessage()
                    + ")");
            return null;
        }
    }

    private static String ownerOf(Integer objNum) {
        return objNum == null ? "an inline page object" : "page object " + objNum;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asDict(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castDict(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(Object value) {
        return (List<Object>) value;
    }

    /** One sentence per statement, so a walk over 300 pages says each thing once. */
    private static void note(List<String> notes, String key, String sentence) {
        if (!notes.contains(sentence)) {
            notes.add(sentence);
        }
    }

    /** The categories lower-cased for a sentence ("no extgstate resource of this dictionary"). */
    private static String plain(String category) {
        return category.toLowerCase(Locale.ROOT);
    }
}
