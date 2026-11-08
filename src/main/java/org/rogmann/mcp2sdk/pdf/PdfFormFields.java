package org.rogmann.mcp2sdk.pdf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The interactive form of a PDF, field by field: the fully qualified name, the object number of
 * the field dictionary, the widgets that draw it - and the value each field holds.
 * <p>
 * {@link PdfDocumentStructure#overview(int)} answers a form with a <em>summary</em> (how many
 * fields, which types, {@code /NeedAppearances}, {@code /DA}, whether an XFA packet is there).
 * That summary is enough to say "this document is a form"; it is not enough to fill one. This
 * class answers the question a filler has: <b>which name does a field have, which object is it,
 * and which objects draw it</b> - the addresses {@code pdfedit}'s {@code form.fill} writes to.
 * </p>
 * <p>
 * The rules are the ones of ISO 32000-1, 12.7.4 and they are not optional:
 * </p>
 * <ul>
 *   <li>{@code /AcroForm /Fields} is a <b>tree</b>. A {@code /Kids} entry carrying a {@code /T}
 *       is a child field, one without is a widget of its parent - both forms occur, and a
 *       dictionary may be field and widget at once (then it carries {@code /Subtype /Widget}).</li>
 *   <li>{@code /FT}, {@code /Ff}, {@code /V}, {@code /DV}, {@code /DA}, {@code /Q}, {@code /Opt},
 *       {@code /MaxLen}, {@code /TI}, {@code /I} and {@code /RV} are <b>inherited</b> from the
 *       parent field (a radio group is the normal case: the children name no {@code /FT}).
 *       {@code /T} is not inherited but <b>concatenated</b>: {@code person} + {@code name} reads
 *       {@code person.name}.</li>
 *   <li>The type comes from {@code /FT} plus the flag bits of table 226/227/228 - and a field
 *       without {@code /FT} is <b>non-terminal</b>: it is reported as such instead of guessing a
 *       type from its children.</li>
 *   <li>The value is {@code /V} <b>as written</b>. {@code value} is that value prepared for a
 *       reader (a choice shows the display text of its {@code /Opt} pair, a button its export
 *       name); {@code rawValue} stays the raw one. A field without {@code /V} answers
 *       {@code value: null} - it does <b>not</b> fall back to {@code /DV}, which is reported
 *       separately.</li>
 *   <li>Nothing is interpreted: {@code /AA} (JavaScript) is counted and named, never executed;
 *       {@code /CO} is an object list; and an <b>XFA</b> form answers an empty field list with
 *       the note that its fields stand in the packet and not in the AcroForm.</li>
 * </ul>
 * <p>
 * The listing is bounded like every other listing of this module: {@code maxFields},
 * {@code maxDepth} and {@code maxValueChars} cut the answer, and a cut is marked
 * ({@code truncated: true} plus a note) while {@code count} keeps the full number.
 * </p>
 */
public final class PdfFormFields {

    /** Fields {@link #listing(Integer, int, int, int)} reports by default. */
    public static final int DEFAULT_MAX_FIELDS = 2_000;

    /** Nesting depth of the field tree walked by default (a producer that loops stops here). */
    public static final int DEFAULT_MAX_DEPTH = 16;

    /** Characters of a value, a default value or an option shown per entry. */
    public static final int DEFAULT_MAX_VALUE_CHARS = 200;

    /** Upper bound of the field dictionaries one walk touches (a broken tree stops here). */
    private static final int MAX_FIELD_SCAN = 20_000;

    // ---- Field flags (ISO 32000-1, tables 226/227/228), written as the specification counts ---
    // The specification numbers the bits from 1, so bit 13 is the mask 1 << 12. The names below are
    // the ones a caller reads in flagsParsed; the numbers in the comments are the table's.

    /** Common to all fields: the field shall not be changed (bit 1). */
    private static final int FLAG_READ_ONLY = 1 << 0;

    /** Common to all fields: the field shall be filled in (bit 2). */
    private static final int FLAG_REQUIRED = 1 << 1;

    /** Common to all fields: the field shall not be exported (bit 3). */
    private static final int FLAG_NO_EXPORT = 1 << 2;

    /** Text field: the text may span several lines (bit 13). */
    private static final int FLAG_MULTILINE = 1 << 12;

    /** Text field: the text is a password (bit 14). */
    private static final int FLAG_PASSWORD = 1 << 13;

    /** Text field: the value is a file path (bit 21). */
    private static final int FLAG_FILE_SELECT = 1 << 20;

    /** Text field: the text is laid out in combs of {@code /MaxLen} cells (bit 25). */
    private static final int FLAG_COMB = 1 << 24;

    /** Text field: the value is rich text ({@code /RV} carries the markup, bit 26). */
    private static final int FLAG_RICH_TEXT = 1 << 25;

    /** Button: the field is a set of radio buttons (bit 16). */
    private static final int FLAG_RADIO = 1 << 15;

    /** Button: the field is a push button - it holds no value (bit 17). */
    private static final int FLAG_PUSHBUTTON = 1 << 16;

    /** Button: radio buttons with the same on state switch on and off in unison (bit 26). */
    private static final int FLAG_RADIOS_IN_UNISON = 1 << 25;

    /** Choice: the field is a combo box and not a list box (bit 18). */
    private static final int FLAG_COMBO = 1 << 17;

    /** Choice: a combo box whose text may be edited (bit 19). */
    private static final int FLAG_EDIT = 1 << 18;

    /** Choice: more than one option may be selected (bit 22). */
    private static final int FLAG_MULTI_SELECT = 1 << 21;

    /** The document being read. */
    private final PdfDocument document;

    /** Sentences about this answer (a field skipped, a value cut, an XFA packet). */
    private final List<String> notes = new ArrayList<>();

    /** Page object number → 1-based page number (the page tree walk). */
    private final Map<Integer, Integer> pageNumbers = new HashMap<>();

    /** Annotation object number → 1-based page number (from the pages' {@code /Annots}). */
    private final Map<Integer, Integer> annotPages = new HashMap<>();

    /**
     * Creates the form view of one opened document.
     * @param document the document
     */
    public PdfFormFields(PdfDocument document) {
        this.document = document;
    }

    /**
     * Convenience factory mirroring {@link PdfDocument#open(String, byte[])}.
     * @param document the document
     * @return the form view
     */
    public static PdfFormFields of(PdfDocument document) {
        return new PdfFormFields(document);
    }

    /**
     * Lists the form fields of the document.
     *
     * @param pageIndex 1-based page number to filter by (only fields with a widget on that page),
     *                  or {@code null} for every field
     * @param maxFields fields reported (0 = {@link #DEFAULT_MAX_FIELDS})
     * @param maxDepth  nesting depth of the field tree (0 = {@link #DEFAULT_MAX_DEPTH})
     * @param maxValueChars characters of a value shown per entry (0 = no cut)
     * @return {@code {present, objNum?, fields, count, total?, truncated?, xfa?,
     *         needAppearances?, defaultAppearance?, calculationOrder?, notes?}} - each field
     *         {@code {name, objNum?, type, partialName?, value, rawValue, defaultValue?,
     *         rawDefaultValue?, valueTruncated?, flags?, flagsParsed?, maxLen?,
     *         defaultAppearance?, quadding?, options?, optionsDisplay?, page?, rect?,
     *         widgetObjNums?, widgets?, pages?, hasAppearance, appearanceState?, onValues?,
     *         filled?}}
     */
    public Map<String, Object> listing(Integer pageIndex, int maxFields, int maxDepth,
                                       int maxValueChars) {
        int fieldBudget = maxFields > 0 ? maxFields : DEFAULT_MAX_FIELDS;
        int depthBudget = maxDepth > 0 ? maxDepth : DEFAULT_MAX_DEPTH;
        int chars = maxValueChars > 0 ? maxValueChars : DEFAULT_MAX_VALUE_CHARS;
        this.valueChars = chars;
        readPageNumbers();

        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> catalog = catalog();
        Map<String, Object> acroForm = catalog == null ? null : dict(catalog.get("AcroForm"));
        if (acroForm == null) {
            result.put("present", false);
            result.put("fields", new ArrayList<Object>());
            result.put("count", 0L);
            notePageWalk();
            if (!notes.isEmpty()) {
                result.put("notes", new ArrayList<Object>(notes));
            }
            return result;
        }
        result.put("present", true);
        Integer acroFormObjNum = objNumOf(catalog.get("AcroForm"));
        if (acroFormObjNum != null) {
            result.put("objNum", (long) (int) acroFormObjNum);
        }
        if (acroForm.get("XFA") != null) {
            // An XFA form is drawn from the packet, not from the AcroForm fields: listing the
            // AcroForm children of a hybrid form here would hand a filler addresses that change
            // nothing a viewer shows.
            result.put("xfa", true);
            result.put("fields", new ArrayList<Object>());
            result.put("count", 0L);
            notes.add("The form carries an /XFA packet: the fields a viewer shows stand in that"
                    + " packet (pdf.streamText shows it) and not in /AcroForm /Fields, so no field"
                    + " list is answered - pdfedit.form.fill refuses such a form (code 'xfa')");
            result.put("notes", new ArrayList<Object>(notes));
            return result;
        }
        if (Boolean.TRUE.equals(acroForm.get("NeedAppearances"))) {
            result.put("needAppearances", true);
        }
        if (acroForm.get("DA") instanceof PdfString da) {
            result.put("defaultAppearance", clip(da.text(), chars));
        }
        List<Object> calcOrder = new ArrayList<>();
        List<Object> co = asList(acroForm.get("CO"));
        if (co != null) {
            for (Object item : co) {
                Integer objNum = objNumOf(item);
                if (objNum != null) {
                    calcOrder.add((long) (int) objNum);
                }
            }
            if (!calcOrder.isEmpty()) {
                result.put("calculationOrder", calcOrder);
            }
        }

        List<Map<String, Object>> all = new ArrayList<>();
        walk(acroForm.get("Fields"), 1, depthBudget, "", Inherited.NONE, all);
        int total = all.size();
        List<Map<String, Object>> shown = all;
        if (pageIndex != null) {
            shown = new ArrayList<>();
            for (Map<String, Object> field : all) {
                if (fieldPages(field).contains(pageIndex)) {
                    shown.add(field);
                }
            }
            result.put("total", (long) total);
            if (shown.isEmpty()) {
                notes.add("No form field names page " + pageIndex + " as the page of one of its"
                        + " widgets - pageOf() reads /P of the widget and, where that is missing,"
                        + " the /Annots of the pages");
            }
        }
        boolean truncated = false;
        List<Object> fields = new ArrayList<>();
        for (Map<String, Object> field : shown) {
            if (fields.size() >= fieldBudget) {
                truncated = true;
                break;
            }
            fields.add(field);
        }
        result.put("fields", fields);
        result.put("count", (long) fields.size());
        if (truncated) {
            result.put("truncated", true);
            notes.add("only the first " + fields.size() + " of " + shown.size() + " field(s) are"
                    + " listed (maxFields " + fieldBudget + ") - the rest is read with"
                    + " pdf.formFields(path, {page: n}) or object by object with pdf.object()");
        }
        noteCounts(all);
        notePageWalk();
        if (!notes.isEmpty()) {
            result.put("notes", new ArrayList<Object>(notes));
        }
        return result;
    }

    // -------------------------------------------------------------------
    // The field tree
    // -------------------------------------------------------------------

    /**
     * The inheritable attributes of a field (ISO 32000-1, 12.7.4.3): a child that names none of
     * them takes the value of its parent. {@code /T} is deliberately not part of it - a name is
     * built by concatenation, not inherited.
     */
    private record Inherited(PdfName fieldType, Long flags, Object value, Object defaultValue,
                             String defaultAppearance, Object options, Long maxLen, Long quadding,
                             Object textIndex, Object richText) {
        /** No parent field: nothing is inherited. */
        static final Inherited NONE = new Inherited(null, null, null, null, null, null, null, null,
                null, null);
    }

    /**
     * Walks one level of the field tree.
     * @param value the {@code /Fields} or {@code /Kids} value
     * @param depth current depth (1 = the root fields)
     * @param maxDepth depth budget
     * @param prefix fully qualified name of the parent ("" at the root)
     * @param inherited the attributes of the parent
     * @param out field entries
     */
    private void walk(Object value, int depth, int maxDepth, String prefix, Inherited inherited,
                      List<Map<String, Object>> out) {
        if (value == null) {
            return;
        }
        if (depth > maxDepth) {
            note("The field tree is deeper than " + maxDepth + " level(s) - the fields below that"
                    + " depth are not listed (maxDepth)");
            return;
        }
        List<Object> items = asList(value);
        if (items == null) {
            note("A /Fields or /Kids entry is not an array (" + PdfParser.kindOf(value)
                    + ") - the tree stops there");
            return;
        }
        for (Object item : items) {
            if (scanned >= MAX_FIELD_SCAN) {
                note("The form holds more than " + MAX_FIELD_SCAN + " field dictionaries - the"
                        + " walk stopped there");
                return;
            }
            scanned++;
            Map<String, Object> dict = dict(item);
            if (dict == null) {
                note("A form field could not be read (" + PdfParser.kindOf(item) + ") and was"
                        + " skipped");
                continue;
            }
            Integer objNum = objNumOf(item);
            String partial = dict.get("T") instanceof PdfString title ? title.text() : null;
            String name = partial == null || partial.isEmpty() ? prefix
                    : prefix.isEmpty() ? partial : prefix + "." + partial;

            Inherited here = inherit(inherited, dict);
            List<Object> childNamed = new ArrayList<>();
            List<Object> childWidgets = new ArrayList<>();
            classifyKids(dict.get("Kids"), name, childNamed, childWidgets);

            PdfName fieldType = here.fieldType();
            boolean isWidget = PdfName.dictHas(dict, "Subtype", "Widget");
            if (fieldType == null) {
                if (partial != null && !partial.isEmpty() || !childNamed.isEmpty()
                        || !childWidgets.isEmpty()) {
                    // A field without /FT is a branch: it is reported as what it is, with its
                    // name and its object number, and its children follow as their own entries.
                    out.add(nonTerminal(name, partial, objNum, dict,
                            childEntries(childNamed, name), childWidgets));
                    walk(childNamed, depth + 1, maxDepth, name, here, out);
                } else {
                    note("A dictionary in /AcroForm /Fields holds neither /T nor /FT nor /Kids -"
                            + " it is no field and was skipped");
                }
                continue;
            }
            if (!childNamed.isEmpty()) {
                // A field with child FIELDS is a branch even when it carries a type of its own
                // (a radio group with one child per button is the normal case, and there the
                // parent names /FT /Btn and the children inherit it).
                out.add(nonTerminal(name, partial, objNum, dict,
                        childEntries(childNamed, name), childWidgets));
                walk(childNamed, depth + 1, maxDepth, name, here, out);
                continue;
            }
            out.add(terminal(name, partial, objNum, dict, here, childWidgets, isWidget));
        }
    }

    /**
     * Sorts a {@code /Kids} array into child fields (an entry that names a {@code /T}) and
     * widgets (an entry that names none).
     */
    private void classifyKids(Object kidsValue, String name, List<Object> childFields,
                              List<Object> childWidgets) {
        List<Object> kids = asList(kidsValue);
        if (kids == null) {
            if (kidsValue != null) {
                note("The /Kids of field '" + name + "' is not an array ("
                        + PdfParser.kindOf(kidsValue) + ") - its children are not listed");
            }
            return;
        }
        for (Object kid : kids) {
            Map<String, Object> kidDict = dict(kid);
            if (kidDict == null) {
                note("A /Kids entry of field '" + name + "' could not be read ("
                        + PdfParser.kindOf(kid) + ") and was skipped");
                continue;
            }
            if (kidDict.get("T") instanceof PdfString) {
                childFields.add(kid);
            } else {
                childWidgets.add(kid);
            }
        }
    }

    /** The names of the child fields of a branch, as they appear under it. */
    private List<Map<String, Object>> childEntries(List<Object> childValues, String prefix) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Object child : childValues) {
            Map<String, Object> childDict = dict(child);
            Integer objNum = objNumOf(child);
            String partial = childDict != null && childDict.get("T") instanceof PdfString title
                    ? title.text() : null;
            String name = partial == null || partial.isEmpty() ? prefix
                    : prefix.isEmpty() ? partial : prefix + "." + partial;
            entries.add(childEntry(name, partial, objNum));
        }
        return entries;
    }

    /** The inheritable attributes of one field dictionary, on top of its parent's. */
    private static Inherited inherit(Inherited parent, Map<String, Object> dict) {
        PdfName fieldType = PdfName.ofDict(dict, "FT");
        Long flags = asLong(dict.get("Ff"));
        String da = dict.get("DA") instanceof PdfString text ? text.text() : null;
        return new Inherited(
                fieldType != null ? fieldType : parent.fieldType(),
                flags != null ? flags : parent.flags(),
                dict.containsKey("V") ? dict.get("V") : parent.value(),
                dict.containsKey("DV") ? dict.get("DV") : parent.defaultValue(),
                da != null ? da : parent.defaultAppearance(),
                dict.containsKey("Opt") ? dict.get("Opt") : parent.options(),
                asLong(dict.get("MaxLen")) != null ? asLong(dict.get("MaxLen")) : parent.maxLen(),
                asLong(dict.get("Q")) != null ? asLong(dict.get("Q")) : parent.quadding(),
                dict.containsKey("TI") ? dict.get("TI") : parent.textIndex(),
                dict.containsKey("RV") ? dict.get("RV") : parent.richText());
    }

    /** The entry of a branch field (one without {@code /FT}, or one whose children are fields). */
    private Map<String, Object> nonTerminal(String name, String partial, Integer objNum,
                                            Map<String, Object> dict,
                                            List<Map<String, Object>> childFields,
                                            List<Object> widgets) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        if (objNum != null) {
            item.put("objNum", (long) (int) objNum);
        }
        item.put("type", "non-terminal");
        if (partial != null && !partial.isEmpty()) {
            item.put("partialName", partial);
        }
        Long flags = asLong(dict.get("Ff"));
        if (flags != null) {
            item.put("flags", flags);
            item.put("flagsParsed", flagsParsed(flags));
        }
        List<Object> children = new ArrayList<>();
        for (Map<String, Object> child : childFields) {
            children.add(child);
        }
        if (!children.isEmpty()) {
            item.put("children", children);
        }
        List<Object> widgetNums = new ArrayList<>();
        for (Object widgetValue : widgets) {
            Integer widgetNum = objNumOf(widgetValue);
            if (widgetNum != null) {
                widgetNums.add((long) (int) widgetNum);
            }
        }
        if (!widgetNums.isEmpty()) {
            item.put("widgetObjNums", widgetNums);
        }
        return item;
    }

    /** The minimal description of a child field, as it appears under its parent. */
    private static Map<String, Object> childEntry(String name, String partial, Integer objNum) {
        Map<String, Object> child = new LinkedHashMap<>();
        child.put("name", name);
        if (objNum != null) {
            child.put("objNum", (long) (int) objNum);
        }
        if (partial != null && !partial.isEmpty()) {
            child.put("partialName", partial);
        }
        return child;
    }

    /**
     * The entry of a terminal field: its value, its flags, its options and the widgets that draw
     * it.
     */
    private Map<String, Object> terminal(String name, String partial, Integer objNum,
                                         Map<String, Object> dict, Inherited inherited,
                                         List<Object> widgetValues, boolean isWidget) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        if (objNum != null) {
            item.put("objNum", (long) (int) objNum);
        }
        PdfName fieldType = inherited.fieldType();
        long flags = inherited.flags() != null ? inherited.flags() : 0L;
        String type = typeName(fieldType, flags);
        item.put("type", type);
        if (partial != null && !partial.isEmpty()) {
            item.put("partialName", partial);
        }
        if (flags != 0) {
            item.put("flags", flags);
            item.put("flagsParsed", flagsParsed(flags));
        } else if (fieldType != null) {
            item.put("flags", 0L);
            item.put("flagsParsed", flagsParsed(0));
        }
        if (inherited.maxLen() != null) {
            item.put("maxLen", inherited.maxLen());
        }
        if (inherited.defaultAppearance() != null) {
            item.put("defaultAppearance",
                    clip(inherited.defaultAppearance(), DEFAULT_MAX_VALUE_CHARS));
        }
        if (inherited.quadding() != null) {
            item.put("quadding", inherited.quadding());
        }

        List<String> exports = new ArrayList<>();
        List<String> displays = new ArrayList<>();
        if ("choice".equals(type) && inherited.options() != null) {
            readOptions(inherited.options(), exports, displays);
            item.put("options", clipValue(new ArrayList<Object>(exports)));
            if (!displays.equals(exports)) {
                item.put("optionsDisplay", clipValue(new ArrayList<Object>(displays)));
            }
        }

        Object raw = inherited.value();
        Object prepared = prepareValue(type, raw, exports, displays);
        Object rawText = rawValue(raw);
        item.put("value", clipValue(prepared));
        item.put("rawValue", clipValue(rawText));
        Object defaultRaw = inherited.defaultValue();
        if (defaultRaw != null) {
            item.put("defaultValue", clipValue(prepareValue(type, defaultRaw, exports, displays)));
            item.put("rawDefaultValue", clipValue(rawValue(defaultRaw)));
        }
        if (isClipped(prepared) || isClipped(rawText)) {
            item.put("valueTruncated", true);
        }

        // The widgets: the /Kids entries that name no /T, or the field dictionary itself when it
        // is a widget. A field without any widget still has a value - it just draws nothing.
        List<Map<String, Object>> widgetDicts = new ArrayList<>();
        List<Integer> widgetNums = new ArrayList<>();
        for (Object widgetValue : widgetValues) {
            Map<String, Object> widgetDict = dict(widgetValue);
            if (widgetDict == null) {
                continue;
            }
            widgetDicts.add(widgetDict);
            widgetNums.add(objNumOf(widgetValue));
        }
        if (widgetDicts.isEmpty() && isWidget) {
            widgetDicts.add(dict);
            widgetNums.add(objNum);
        }
        List<Object> widgetObjNums = new ArrayList<>();
        List<Object> pages = new ArrayList<>();
        Map<String, Object> first = null;
        boolean hasAppearance = false;
        String appearanceState = null;
        Set<String> onValues = new LinkedHashSet<>();
        for (int i = 0; i < widgetDicts.size(); i++) {
            Map<String, Object> widgetDict = widgetDicts.get(i);
            Integer widgetNum = widgetNums.get(i);
            if (widgetNum != null) {
                widgetObjNums.add((long) (int) widgetNum);
            }
            Integer page = pageOf(widgetNum, widgetDict);
            if (page != null && !pages.contains((long) (int) page)) {
                pages.add((long) (int) page);
            }
            boolean ap = widgetDict.get("AP") != null;
            hasAppearance |= ap;
            if (widgetDict.get("AS") instanceof PdfName state) {
                appearanceState = state.toString();
            }
            collectOnValues(widgetDict, onValues);
            if (first == null) {
                first = new LinkedHashMap<>();
                if (widgetNum != null) {
                    first.put("objNum", (long) (int) widgetNum);
                }
                if (page != null) {
                    first.put("page", (long) (int) page);
                }
                List<Object> rect = numbers(widgetDict.get("Rect"));
                if (rect != null) {
                    first.put("rect", rect);
                }
                first.put("hasAppearance", ap);
            }
        }
        if (!widgetObjNums.isEmpty()) {
            item.put("widgetObjNums", widgetObjNums);
        }
        if (first != null) {
            if (first.get("page") != null) {
                item.put("page", first.get("page"));
            }
            if (first.get("rect") != null) {
                item.put("rect", first.get("rect"));
            }
            if (widgetDicts.size() > 1) {
                item.put("widgets", widgetEntries(widgetDicts, widgetNums));
                item.put("pages", pages);
            }
        }
        item.put("hasAppearance", hasAppearance);
        if (appearanceState != null) {
            item.put("appearanceState", appearanceState);
        }
        if ("checkbox".equals(type) || "radio".equals(type) || "pushbutton".equals(type)) {
            if (!onValues.isEmpty()) {
                item.put("onValues", new ArrayList<Object>(onValues));
            }
        }
        if ("signature".equals(type)) {
            item.put("filled", raw != null);
        }
        if (!widgetDicts.isEmpty() && !hasAppearance) {
            noAppearance++;
            if (firstNoAppearance == null) {
                firstNoAppearance = name;
            }
        }
        if (dict.get("AA") != null) {
            fieldsWithActions++;
        }
        return item;
    }

    /** One entry per widget of a field that has more than one. */
    private List<Object> widgetEntries(List<Map<String, Object>> widgetDicts,
                                       List<Integer> widgetNums) {
        List<Object> entries = new ArrayList<>();
        for (int i = 0; i < widgetDicts.size(); i++) {
            Map<String, Object> widgetDict = widgetDicts.get(i);
            Map<String, Object> entry = new LinkedHashMap<>();
            Integer widgetNum = widgetNums.get(i);
            if (widgetNum != null) {
                entry.put("objNum", (long) (int) widgetNum);
            }
            Integer page = pageOf(widgetNum, widgetDict);
            if (page != null) {
                entry.put("page", (long) (int) page);
            }
            List<Object> rect = numbers(widgetDict.get("Rect"));
            if (rect != null) {
                entry.put("rect", rect);
            }
            entry.put("hasAppearance", widgetDict.get("AP") != null);
            if (widgetDict.get("AS") instanceof PdfName state) {
                entry.put("appearanceState", state.toString());
            }
            entries.add(entry);
        }
        return entries;
    }

    /** The {@code /AP} on-state names of a button widget ({@code /N} dictionary keys). */
    private static void collectOnValues(Map<String, Object> widgetDict, Set<String> onValues) {
        Map<String, Object> ap = asMap(widgetDict.get("AP"));
        if (ap == null) {
            return;
        }
        Map<String, Object> normal = asMap(ap.get("N"));
        if (normal == null) {
            return;
        }
        for (String key : normal.keySet()) {
            if (!"Off".equals(key)) {
                onValues.add("/" + key);
            }
        }
    }

    // -------------------------------------------------------------------
    // Values, types and flags
    // -------------------------------------------------------------------

    /**
     * The type of a field from {@code /FT} and its flags (tables 227/228): {@code Tx} is a text
     * field, {@code Btn} a checkbox, radio group or push button, {@code Ch} a choice and
     * {@code Sig} a signature. A field whose {@code /FT} names none of them is reported with the
     * raw name - guessing a type from the children would be a statement the file never made.
     */
    static String typeName(PdfName fieldType, long flags) {
        if (fieldType == null) {
            return "non-terminal";
        }
        String name = fieldType.value();
        switch (name) {
            case "Tx":
                return "text";
            case "Btn":
                if ((flags & FLAG_PUSHBUTTON) != 0) {
                    return "pushbutton";
                }
                return (flags & FLAG_RADIO) != 0 ? "radio" : "checkbox";
            case "Ch":
                return "choice";
            case "Sig":
                return "signature";
            default:
                return name;
        }
    }

    /** The flag bits as a block a caller reads without a table. */
    static Map<String, Object> flagsParsed(long flags) {
        Map<String, Object> parsed = new LinkedHashMap<>();
        parsed.put("readOnly", (flags & FLAG_READ_ONLY) != 0);
        parsed.put("required", (flags & FLAG_REQUIRED) != 0);
        parsed.put("noExport", (flags & FLAG_NO_EXPORT) != 0);
        parsed.put("multiline", (flags & FLAG_MULTILINE) != 0);
        parsed.put("password", (flags & FLAG_PASSWORD) != 0);
        parsed.put("fileSelect", (flags & FLAG_FILE_SELECT) != 0);
        parsed.put("comb", (flags & FLAG_COMB) != 0);
        parsed.put("richText", (flags & FLAG_RICH_TEXT) != 0);
        parsed.put("radio", (flags & FLAG_RADIO) != 0);
        parsed.put("pushbutton", (flags & FLAG_PUSHBUTTON) != 0);
        parsed.put("radiosInUnison", (flags & FLAG_RADIOS_IN_UNISON) != 0);
        parsed.put("combo", (flags & FLAG_COMBO) != 0);
        parsed.put("edit", (flags & FLAG_EDIT) != 0);
        parsed.put("multiSelect", (flags & FLAG_MULTI_SELECT) != 0);
        return parsed;
    }

    /**
     * Reads the {@code /Opt} array of a choice field into export and display values: an entry is
     * a string (export = display) or a two-element array {@code [export display]}.
     */
    private static void readOptions(Object optionsValue, List<String> exports,
                                    List<String> displays) {
        List<Object> options = asList(optionsValue);
        if (options == null) {
            return;
        }
        for (Object option : options) {
            List<Object> pair = asList(option);
            if (pair != null) {
                String export = pair.isEmpty() ? null : textOf(pair.get(0));
                String display = pair.size() > 1 ? textOf(pair.get(1)) : export;
                if (export != null) {
                    exports.add(export);
                }
                displays.add(display != null ? display : export);
            } else {
                String text = textOf(option);
                if (text != null) {
                    exports.add(text);
                    displays.add(text);
                }
            }
        }
    }

    /** The value as it is written (names keep their slash, an array stays an array). */
    private static Object rawValue(Object value) {
        if (value instanceof PdfString text) {
            return text.text();
        }
        if (value instanceof PdfName name) {
            return name.toString();
        }
        if (value instanceof List<?> list) {
            List<Object> items = new ArrayList<>();
            for (Object item : list) {
                items.add(rawValue(item));
            }
            return items;
        }
        return value instanceof Long || value instanceof Double ? value : null;
    }

    /**
     * The value prepared for a reader: a choice shows the display text of its option (the value
     * is the export value the file writes), a multi-select answers the list of its display texts
     * as {@code values}. Everything else is what {@link #rawValue(Object)} writes.
     */
    private static Object prepareValue(String type, Object value, List<String> exports,
                                       List<String> displays) {
        if (value == null) {
            return null;
        }
        if ("choice".equals(type)) {
            List<Object> selected = asList(value);
            if (selected != null) {
                List<Object> result = new ArrayList<>();
                for (Object item : selected) {
                    result.add(displayOf(textOf(item), exports, displays));
                }
                return result;
            }
            return displayOf(textOf(value), exports, displays);
        }
        return rawValue(value);
    }

    /** The display text of one export value, or the value itself where {@code /Opt} has none. */
    private static Object displayOf(String export, List<String> exports, List<String> displays) {
        if (export == null) {
            return null;
        }
        int index = exports.indexOf(export);
        return index >= 0 && index < displays.size() ? displays.get(index) : export;
    }

    // -------------------------------------------------------------------
    // Pages and widgets
    // -------------------------------------------------------------------

    /**
     * Maps widget object numbers to page numbers: {@code /P} names the page of a widget, and a
     * widget without {@code /P} (the common case in the wild) is found in the {@code /Annots} of
     * its page.
     */
    private void readPageNumbers() {
        List<String> pageNotes = new ArrayList<>();
        List<PdfDocument.Page> pages = document.pages(PdfDocumentStructure.DEFAULT_MAX_PAGES,
                pageNotes);
        for (PdfDocument.Page page : pages) {
            pageNumbers.put(page.objNum(), page.page());
            Map<String, Object> dict = document.dictOf(page.objNum());
            List<Object> annots = asList(dict.get("Annots"));
            if (annots == null) {
                continue;
            }
            for (Object annot : annots) {
                Integer objNum = objNumOf(annot);
                if (objNum != null) {
                    annotPages.putIfAbsent(objNum, page.page());
                }
            }
        }
        this.pageWalkNotes = pageNotes;
    }

    /** What the page walk of this answer could not do (read in {@link #readPageNumbers()}). */
    private List<String> pageWalkNotes = List.of();

    /** The page a widget draws on: {@code /P}, else the page whose {@code /Annots} names it. */
    private Integer pageOf(Integer widgetNum, Map<String, Object> widgetDict) {
        Integer declared = objNumOf(widgetDict.get("P"));
        if (declared != null && pageNumbers.containsKey(declared)) {
            return pageNumbers.get(declared);
        }
        if (widgetNum != null) {
            return annotPages.get(widgetNum);
        }
        return null;
    }

    /** The pages a field's widgets sit on (used by the {@code page} filter). */
    private List<Integer> fieldPages(Map<String, Object> field) {
        List<Integer> pages = new ArrayList<>();
        Object single = field.get("page");
        if (single instanceof Long page) {
            pages.add((int) (long) page);
        }
        Object list = field.get("pages");
        if (list instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof Long page) {
                    pages.add((int) (long) page);
                }
            }
        }
        return pages;
    }

    /** Adds what the page walk reported (the page numbers of the widgets depend on it). */
    private void notePageWalk() {
        for (String pageNote : pageWalkNotes) {
            if (!notes.contains(pageNote)) {
                notes.add(pageNote);
            }
        }
    }

    /** Fields reported without an appearance stream. */
    private int noAppearance;

    /** The first field without an appearance stream (named in the note). */
    private String firstNoAppearance;

    /** Fields that carry {@code /AA} actions. */
    private int fieldsWithActions;

    /** Field dictionaries touched by the walk (bounded by {@link #MAX_FIELD_SCAN}). */
    private int scanned;

    /** Characters of a value, a default value or an option shown per entry. */
    private int valueChars = DEFAULT_MAX_VALUE_CHARS;

    /** The sentences about the whole form (a missing appearance, unexecuted actions). */
    private void noteCounts(List<Map<String, Object>> fields) {
        if (noAppearance > 0) {
            notes.add(noAppearance + " field widget(s) carry no /AP appearance stream (first: '"
                    + firstNoAppearance + "') - the value is in the file, but the viewer has to"
                    + " build the picture (/NeedAppearances makes that a viewer task);"
                    + " pdfedit.form.fill writes the appearance with refreshAppearances()");
        }
        if (fieldsWithActions > 0) {
            notes.add(fieldsWithActions + " field(s) carry additional actions (/AA, usually"
                    + " JavaScript): they are counted and named, never executed");
        }
        int branches = 0;
        for (Map<String, Object> field : fields) {
            if ("non-terminal".equals(field.get("type"))) {
                branches++;
            }
        }
        if (branches > 0) {
            notes.add(branches + " field(s) are non-terminal (a branch without /FT or with child"
                    + " fields): they hold no value of their own, their children are listed"
                    + " separately");
        }
    }

    // -------------------------------------------------------------------
    // Small helpers (the same reading rules as PdfDocumentStructure)
    // -------------------------------------------------------------------

    /** The catalogue dictionary, or {@code null}. */
    private Map<String, Object> catalog() {
        Integer root = objNumOf(document.crossReference().trailer().get("Root"));
        return root == null ? null : document.dictOf(root);
    }

    /** The value as a dictionary (references are resolved), else {@code null}. */
    private Map<String, Object> dict(Object value) {
        Object resolved = document.dereference(value);
        if (resolved instanceof PdfStream stream) {
            return stream.dict();
        }
        return asMap(resolved);
    }

    /** The object number of a value (a reference), else {@code null}. */
    private static Integer objNumOf(Object value) {
        return value instanceof PdfReference reference ? reference.objNum() : null;
    }

    /** The value as a list, else {@code null}. */
    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return value instanceof List<?> list ? (List<Object>) list : null;
    }

    /** The value as a dictionary, else {@code null}. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    /** The integer of a number, else {@code null}. */
    private static Long asLong(Object value) {
        if (value instanceof Long number) {
            return number;
        }
        if (value instanceof Integer number) {
            return (long) number;
        }
        return null;
    }

    /** The numbers of an array value (a rectangle), else {@code null}. */
    private static List<Object> numbers(Object value) {
        List<Object> items = asList(value);
        if (items == null || items.isEmpty()) {
            return null;
        }
        for (Object item : items) {
            if (!(item instanceof Long) && !(item instanceof Double) && !(item instanceof Integer)) {
                return null;
            }
        }
        return items;
    }

    /** The text of a string or name value, else {@code null}. */
    private static String textOf(Object value) {
        if (value instanceof PdfString text) {
            return text.text();
        }
        if (value instanceof PdfName name) {
            return name.toString();
        }
        return null;
    }

    /** A value (a string or a list of strings) cut to the entry's character budget. */
    private Object clipValue(Object value) {
        if (value instanceof String text) {
            return clip(text, valueChars);
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(clipValue(item));
            }
            return out;
        }
        return value;
    }

    /** Whether {@link #clipValue(Object)} removed something. */
    private boolean isClipped(Object value) {
        if (value instanceof String text) {
            return text.length() > valueChars;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (isClipped(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A text cut to {@code maxChars}, with {@code ...} where it was cut. */
    private static String clip(String text, int maxChars) {
        if (text == null || maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "...";
    }

    /** Adds a sentence to the answer (once). */
    private void note(String text) {
        if (!notes.contains(text)) {
            notes.add(text);
        }
    }
}
