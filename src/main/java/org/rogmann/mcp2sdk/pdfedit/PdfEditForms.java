package org.rogmann.mcp2sdk.pdfedit;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDChoice;
import org.apache.pdfbox.pdmodel.interactive.form.PDComboBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDNonTerminalField;
import org.apache.pdfbox.pdmodel.interactive.form.PDRadioButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTerminalField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.pdfbox.pdmodel.interactive.form.PDVariableText;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code form.fill} and {@code form.flatten}: the values of an interactive form.
 * <p>
 * The addresses come from the reader ({@code pdf.formFields()}): a field is named by its
 * <b>fully qualified</b> name - the {@code /T} chain joined with '.' - and nothing else. The ops
 * here are the writer's half of that agreement, and three rules run through them:
 * </p>
 * <ul>
 *   <li><b>{@code setValue} writes {@code /V}, not the picture.</b> Without
 *       {@code refreshAppearances()} the value stands in the file and no viewer shows it - the
 *       classic "my form is still empty" mistake. The op therefore refreshes by default and says
 *       it in {@code notes} when it was switched off.</li>
 *   <li><b>An unknown name is an error.</b> A typo in a field name would otherwise be an
 *       invisible empty form; the refusal names the fields the form <em>does</em> have.</li>
 *   <li><b>A value the field cannot hold is an error.</b> A choice value outside
 *       {@code /Opt}, a radio value that names no on state, a text longer than
 *       {@code /MaxLen}: viewers discard such a value, so the answer would be a document that
 *       nowhere says what was asked for.</li>
 * </ul>
 * <p>
 * An XFA form is refused ({@code code:'xfa'}): its fields live in the packet, and the AcroForm
 * dictionaries underneath are not what a viewer shows. A signature field is never written -
 * signing is not part of this module (E4).
 * </p>
 */
final class PdfEditForms {

    /** {@code /Tf} inside a default appearance string: a resource name, a size, the operator. */
    private static final Pattern DA_FONT = Pattern.compile(
            "/([^\\s/\\[\\]()<>{}%]+)\\s+[-+]?[0-9]*\\.?[0-9]+\\s+Tf");

    /** The font name a form gets when its {@code /DA} names none (the AcroForm convention). */
    private static final String FALLBACK_FONT = "Helv";

    /** The appearance string a form gets when it has none (auto size, black). */
    private static final String FALLBACK_DA = "/" + FALLBACK_FONT + " 0 Tf 0 g";

    private PdfEditForms() {
        // Utility class
    }

    // -------------------------------------------------------------------
    // form.fill
    // -------------------------------------------------------------------

    /**
     * Sets field values and refreshes the appearances.
     * @param ctx the call context
     * @param op the op
     */
    static void fill(PdfEditContext ctx, PdfEditOp op) {
        op.allow("fields", "refresh", "flatten");
        Map<String, Object> values = op.map("fields");
        boolean refresh = op.bool("refresh", true);
        boolean flatten = op.bool("flatten", false);
        if (values.isEmpty()) {
            throw PdfEditException.usage(op.where() + ": 'fields' is empty",
                    "fields maps a fully qualified field name to its value, e.g."
                            + " {fields:{'person.name':'R. Ogmann'}} - pdf.formFields() lists the"
                            + " names");
        }
        PDAcroForm form = requireForm(ctx, op);
        refuseXfa(op, form);
        Map<String, PDField> byName = fieldsByName(form);
        List<String> unknown = new ArrayList<>();
        for (String name : values.keySet()) {
            if (!byName.containsKey(name)) {
                unknown.add(name);
            }
        }
        if (!unknown.isEmpty()) {
            throw new PdfEditException("unknown-field", op.where() + ": unknown field(s) "
                    + unknown,
                    "the form names " + nameList(byName.keySet()) + " - pdf.formFields(path)"
                            + " lists them with their type and value");
        }

        List<PDField> touched = new ArrayList<>();
        List<Object> written = new ArrayList<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String name = entry.getKey();
            PDField field = byName.get(name);
            setValue(field, name, entry.getValue(), op);
            touched.add(field);
            Map<String, Object> done = new LinkedHashMap<>();
            done.put("name", name);
            done.put("type", typeOf(field));
            done.put("value", describeValue(entry.getValue()));
            written.add(done);
            if (field.isReadOnly()) {
                ctx.note("field '" + name + "' is read-only in the file (/Ff bit 1): its value is"
                        + " written, but a viewer will not let anyone change it afterwards");
            }
            int maxLen = field instanceof PDTextField text ? text.getMaxLen() : -1;
            if (maxLen > 0) {
                Object value = entry.getValue();
                if (value instanceof String string && string.length() > maxLen) {
                    throw PdfEditException.usage(op.where() + ": '" + name + "' holds "
                            + string.length() + " characters",
                            "the field allows " + maxLen + " (/MaxLen) - a longer value would be"
                                    + " dropped by the viewer");
                }
            }
        }

        if (refresh) {
            ensureAppearanceResources(ctx, form, touched);
            try {
                form.refreshAppearances(touched);
            } catch (IOException e) {
                throw broken(op, e);
            } catch (IllegalArgumentException e) {
                throw new PdfEditException("usage", op.where() + ": the appearance of a field"
                        + " could not be built: " + e.getMessage(),
                        "a value the form's font cannot draw is refused instead of written -"
                                + " the field's /DA names a font, and that font has to be able to"
                                + " encode the value (Base-14 covers WinAnsi, no Cyrillic or CJK)");
            }
            markTouched(ctx, form, touched);
            noteButtonAppearances(ctx, touched);
        } else {
            ctx.note("refresh:false: /V is written but the appearance streams are not rebuilt -"
                    + " the value is in the file, and a viewer that trusts /AP shows the old"
                    + " picture (that is the classic 'the form is still empty' result)");
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("fields", written);
        details.put("refreshed", refresh);
        ctx.changed(op, "form filled: " + written.size() + " field(s)", details);

        if (flatten) {
            flattenFields(ctx, op, form, touched, refresh);
        }
    }

    // -------------------------------------------------------------------
    // form.flatten
    // -------------------------------------------------------------------

    /**
     * Flattens the form: the appearance of every widget becomes part of the page content, and the
     * fields are removed (the document is no longer fillable).
     * @param ctx the call context
     * @param op the op
     */
    static void flatten(PdfEditContext ctx, PdfEditOp op) {
        op.allow("fields", "refresh");
        boolean refresh = op.bool("refresh", true);
        PDAcroForm form = requireForm(ctx, op);
        refuseXfa(op, form);
        List<PDField> selected = List.of();
        if (op.params().get("fields") != null) {
            List<?> names = op.list("fields");
            Map<String, PDField> byName = fieldsByName(form);
            List<PDField> resolved = new ArrayList<>();
            List<String> unknown = new ArrayList<>();
            for (Object raw : names) {
                String name = raw instanceof String text ? text : String.valueOf(raw);
                PDField field = byName.get(name);
                if (field == null) {
                    unknown.add(name);
                } else {
                    resolved.add(field);
                }
            }
            if (!unknown.isEmpty()) {
                throw new PdfEditException("unknown-field", op.where() + ": unknown field(s) "
                        + unknown,
                        "the form names " + nameList(byName.keySet()));
            }
            selected = resolved;
        }
        flattenFields(ctx, op, form, selected, refresh);
    }

    /**
     * The shared flattening path of {@code form.flatten} and {@code form.fill}.
     * @param ctx the call context
     * @param op the op
     * @param form the form
     * @param selected the fields to flatten, empty for all of them
     * @param refresh whether the appearances are rebuilt before they are written into the page
     */
    private static void flattenFields(PdfEditContext ctx, PdfEditOp op, PDAcroForm form,
                                      List<PDField> selected, boolean refresh) {
        List<PDField> fields = new ArrayList<>();
        if (selected.isEmpty()) {
            for (Iterator<PDField> iterator = form.getFieldIterator(); iterator.hasNext();) {
                PDField field = iterator.next();
                if (field instanceof PDTerminalField) {
                    fields.add(field);
                }
            }
        } else {
            for (PDField field : selected) {
                if (field instanceof PDTerminalField) {
                    fields.add(field);
                }
            }
        }
        if (fields.isEmpty()) {
            throw new PdfEditException("unsupported", op.where() + ": the form holds no field"
                    + " that could be flattened",
                    "pdf.formFields(path) shows what the form has; a form with fields only in an"
                            + " XFA packet cannot be flattened at all");
        }
        if (form.hasXFA()) {
            ctx.note("the form carries an /XFA packet; flattening removes it (the pages then show"
                    + " the AcroForm appearances, and the packet is gone)");
        }
        if (!refresh) {
            ctx.note("refresh:false: the appearances are NOT rebuilt before flattening - a widget"
                    + " that never had one is removed without a mark on the page (fill with"
                    + " refresh:true first)");
        }
        try {
            form.flatten(fields, refresh);
        } catch (IOException e) {
            throw broken(op, e);
        } catch (IllegalArgumentException e) {
            throw new PdfEditException("usage", op.where() + ": the form could not be flattened: "
                    + e.getMessage(),
                    "flattening writes each widget's appearance into the page - a widget without"
                            + " /AP has nothing to write (fill first, refresh:true builds it)");
        }
        form.getCOSObject().setNeedToBeUpdated(true);
        if (form.getFields().isEmpty()) {
            // Nothing is fillable any more: leaving an empty /AcroForm would make pdf.document()
            // report a form with zero fields, which reads like a form nobody can see.
            ctx.document().getDocumentCatalog().getCOSObject().removeItem(COSName.ACRO_FORM);
            ctx.document().getDocumentCatalog().getCOSObject().setNeedToBeUpdated(true);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("fields", (long) fields.size());
        details.put("all", selected.isEmpty());
        ctx.changed(op, "form flattened: " + fields.size() + " field(s) became page content",
                details);
        ctx.note(selected.isEmpty()
                ? "flattened: the widgets are part of the page content now - the document is no"
                        + " longer fillable, and the values can no longer be read with"
                        + " pdf.formFields() (their marks stay visible)"
                : "flattened: " + fields.size() + " field(s) are page content now and are gone from"
                        + " the form - the remaining fields stay fillable, and pdf.formFields() no"
                        + " longer names the flattened ones");
    }

    // -------------------------------------------------------------------
    // The addresses
    // -------------------------------------------------------------------

    /**
     * The form of the document, or a refusal that says which of the two reasons applies.
     */
    private static PDAcroForm requireForm(PdfEditContext ctx, PdfEditOp op) {
        PDAcroForm form = ctx.document().getDocumentCatalog().getAcroForm();
        if (form == null) {
            throw new PdfEditException("unsupported", op.where() + ": the document has no"
                    + " interactive form (/AcroForm)",
                    "pdf.formFields(path).present says the same; annotations are added with"
                            + " annot.add, not with a form op");
        }
        return form;
    }

    /**
     * Refuses an XFA form: a viewer shows the packet, not the AcroForm fields - writing /V there
     * would change a file that nothing displays and that a filler cannot address.
     */
    private static void refuseXfa(PdfEditOp op, PDAcroForm form) {
        if (!form.hasXFA()) {
            return;
        }
        throw new PdfEditException("xfa", op.where() + ": the form carries an /XFA packet",
                "a viewer draws such a form from the packet, so its fields have no addresses in"
                        + " the AcroForm - pdf.formFields() answers fields: [] for the same reason;"
                        + " filling an XFA form needs a renderer this module does not have");
    }

    /** The terminal fields of the form by their fully qualified name. */
    private static Map<String, PDField> fieldsByName(PDAcroForm form) {
        Map<String, PDField> byName = new LinkedHashMap<>();
        for (Iterator<PDField> iterator = form.getFieldIterator(); iterator.hasNext();) {
            PDField field = iterator.next();
            String name = field.getFullyQualifiedName();
            if (name != null && !name.isEmpty()) {
                byName.putIfAbsent(name, field);
            }
        }
        return byName;
    }

    /** The names of a form as one bounded sentence ("a, b, c … (12 in total)"). */
    private static String nameList(Set<String> names) {
        if (names.isEmpty()) {
            return "no field at all";
        }
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (String name : names) {
            if (shown == 12) {
                sb.append(", … (").append(names.size()).append(" in total)");
                break;
            }
            if (shown > 0) {
                sb.append(", ");
            }
            sb.append('\'').append(name).append('\'');
            shown++;
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------
    // Values
    // -------------------------------------------------------------------

    /**
     * Writes one value into one field, in the form the field type allows.
     * @param field the field
     * @param name its fully qualified name (for messages)
     * @param value the value the caller wrote
     * @param op the op (for messages)
     */
    private static void setValue(PDField field, String name, Object value, PdfEditOp op) {
        if (value == null) {
            throw PdfEditException.usage(op.where() + ": '" + name + "' is null",
                    "a value is a string, true/false (checkbox), an export name (radio) or a"
                            + " string/array (choice) - an empty string clears a text field");
        }
        try {
            if (field instanceof PDNonTerminalField) {
                throw new PdfEditException("usage", op.where() + ": '" + name + "' is a branch"
                        + " field, not a field with a value",
                        "fill its children instead - pdf.formFields(path) lists them by their"
                                + " fully qualified names");
            }
            if (field instanceof PDTextField text) {
                text.setValue(textValue(name, value, op));
            } else if (field instanceof PDCheckBox box) {
                setCheckBox(box, name, value, op);
            } else if (field instanceof PDRadioButton radio) {
                setRadio(radio, name, value, op);
            } else if (field instanceof PDChoice choice) {
                setChoice(choice, name, value, op);
            } else if (field instanceof PDSignatureField) {
                throw new PdfEditException("unsupported", op.where() + ": '" + name
                        + "' is a signature field",
                        "signing is not implemented (E4) - the field is left untouched, and a"
                                + " signature that exists keeps its bytes");
            } else if (field instanceof PDButton button) {
                throw new PdfEditException("unsupported", op.where() + ": '" + name
                        + "' is a push button (/Ff bit 17)",
                        "a push button holds no value - it runs an action, and this module"
                                + " executes nothing" + describeButtons(button));
            } else {
                throw new PdfEditException("unsupported", op.where() + ": '" + name
                        + "' is a field of type " + typeOf(field),
                        "pdf.formFields(path) names the type; supported are text, checkbox, radio"
                                + " and choice");
            }
        } catch (IOException e) {
            throw new PdfEditException("broken", op.where() + ": '" + name + "' could not be"
                    + " written: " + e.getMessage(),
                    "the field dictionary may be unusual - pdf.object(path, objNum) reads it", e);
        } catch (IllegalArgumentException e) {
            throw PdfEditException.usage(op.where() + ": '" + name + "': " + e.getMessage(),
                    "pdf.formFields(path) shows the allowed values of this field");
        } catch (IllegalStateException e) {
            throw new PdfEditException("broken", op.where() + ": '" + name + "' could not be"
                    + " written: " + e.getMessage(),
                    "the form's /DA or /DR may be missing - refresh:true repairs a missing default"
                            + " font, a field-level /DA still has to name a font of /DR", e);
        }
    }

    /** A text value: a string (a number is accepted as its text, everything else is not). */
    private static String textValue(String name, Object value, PdfEditOp op) {
        String text = value instanceof String string ? string
                : value instanceof Number number ? String.valueOf(number) : null;
        if (text == null) {
            throw PdfEditException.usage(op.where() + ": '" + name + "' is "
                    + PdfEditOp.describe(value),
                    "a text field takes a string (a number is written as its decimal text)");
        }
        if (text.length() > PdfEditLimits.MAX_FIELD_VALUE_CHARS) {
            throw new PdfEditException("limit", op.where() + ": '" + name + "' holds "
                    + text.length() + " characters",
                    "at most " + PdfEditLimits.MAX_FIELD_VALUE_CHARS + " characters per field are"
                            + " accepted");
        }
        return text;
    }

    /** A checkbox: {@code true}/{@code false}, or the on state of the widget. */
    private static void setCheckBox(PDCheckBox box, String name, Object value, PdfEditOp op)
            throws IOException {
        String on = box.getOnValue();
        if (value instanceof Boolean flag) {
            if (flag) {
                box.check();
            } else {
                box.unCheck();
            }
            return;
        }
        String text = value instanceof String string ? string : null;
        if (text == null) {
            throw PdfEditException.usage(op.where() + ": '" + name + "' is "
                    + PdfEditOp.describe(value),
                    "a checkbox takes true/false, or its on state '/" + on + "' and '/Off'");
        }
        String bare = stripSlash(text);
        if ("Off".equalsIgnoreCase(bare) || "false".equalsIgnoreCase(bare)) {
            box.unCheck();
        } else if (on.equals(bare)) {
            box.check();
        } else {
            throw PdfEditException.usage(op.where() + ": '" + name + "' has no on state '" + text
                    + "'",
                    "this checkbox switches to '/" + on + "' - write true/false or '/" + on
                            + "' and '/Off'");
        }
    }

    /** A radio group: the export value (or the on state name) of one button. */
    private static void setRadio(PDRadioButton radio, String name, Object value, PdfEditOp op)
            throws IOException {
        String text = value instanceof String string ? string : null;
        if (text == null) {
            throw PdfEditException.usage(op.where() + ": '" + name + "' is "
                    + PdfEditOp.describe(value),
                    "a radio group takes the export value of one button: "
                            + radioValues(radio));
        }
        Set<String> allowed = new LinkedHashSet<>();
        allowed.addAll(radio.getOnValues());
        allowed.addAll(radio.getExportValues());
        String bare = stripSlash(text);
        if (!allowed.contains(bare) && !allowed.contains(text)) {
            throw PdfEditException.usage(op.where() + ": '" + name + "' has no button '" + text
                    + "'", "this group knows " + radioValues(radio));
        }
        // PDFBox checks and writes the bare name ("Red", not "/Red"); the slash is this
        // module's spelling for a name in an answer, not something the field stores.
        radio.setValue(bare);
    }

    private static String radioValues(PDRadioButton radio) {
        Set<String> all = new LinkedHashSet<>();
        all.addAll(radio.getExportValues());
        all.addAll(radio.getOnValues());
        List<String> listed = new ArrayList<>();
        for (String value : all) {
            listed.add("'/" + value + "'");
        }
        return listed.isEmpty() ? "no on state at all (the widgets have no /AP)"
                : String.join(", ", listed);
    }

    /** A choice: one export value or a list of them, checked against {@code /Opt}. */
    private static void setChoice(PDChoice choice, String name, Object value, PdfEditOp op)
            throws IOException {
        List<String> options = choice.getOptions();
        List<String> wanted = new ArrayList<>();
        if (value instanceof List<?> items) {
            for (Object item : items) {
                if (!(item instanceof String text)) {
                    throw PdfEditException.usage(op.where() + ": '" + name + "' holds "
                            + PdfEditOp.describe(item),
                            "a choice takes a string or an array of strings (one per selected"
                                    + " option)");
                }
                wanted.add(text);
            }
        } else if (value instanceof String text) {
            wanted.add(text);
        } else {
            throw PdfEditException.usage(op.where() + ": '" + name + "' is "
                    + PdfEditOp.describe(value),
                    "a choice takes a string or an array of strings - it knows " + optionList(options));
        }
        if (wanted.isEmpty()) {
            throw PdfEditException.usage(op.where() + ": '" + name + "' is an empty array",
                    "write at least one option; an empty selection is not a value a choice field"
                            + " holds");
        }
        boolean editable = choice instanceof PDComboBox combo && combo.isEdit();
        if (!editable && !options.isEmpty()) {
            List<String> unknown = new ArrayList<>();
            for (String one : wanted) {
                if (!options.contains(one)) {
                    unknown.add(one);
                }
            }
            if (!unknown.isEmpty()) {
                throw PdfEditException.usage(op.where() + ": '" + name + "' has no option "
                        + unknown,
                        "this field knows " + optionList(options) + " - a value outside /Opt is"
                                + " dropped by every viewer");
            }
        }
        if (wanted.size() > 1 && !choice.isMultiSelect()) {
            throw PdfEditException.usage(op.where() + ": '" + name + "' got " + wanted.size()
                    + " values", "the field allows one selection (/Ff bit 22 is not set) -"
                    + " write a single string");
        }
        for (String one : wanted) {
            if (one.length() > PdfEditLimits.MAX_FIELD_VALUE_CHARS) {
                throw new PdfEditException("limit", op.where() + ": a value of '" + name
                        + "' holds " + one.length() + " characters",
                        "at most " + PdfEditLimits.MAX_FIELD_VALUE_CHARS + " characters are"
                                + " accepted");
            }
        }
        if (wanted.size() == 1) {
            choice.setValue(wanted.get(0));
        } else {
            choice.setValue(wanted);
        }
    }

    private static String optionList(List<String> options) {
        if (options.isEmpty()) {
            return "no option at all (the field has no /Opt)";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < options.size() && i < 12; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('\'').append(options.get(i)).append('\'');
        }
        if (options.size() > 12) {
            sb.append(", … (").append(options.size()).append(" in total)");
        }
        return sb.toString();
    }

    private static String stripSlash(String text) {
        return text.startsWith("/") ? text.substring(1) : text;
    }

    private static String describeButtons(PDButton button) {
        return button.getOnValues().isEmpty() ? ""
                : " (it knows the states " + button.getOnValues() + ")";
    }

    /** The type name of a field, in the words {@code pdf.formFields()} uses. */
    private static String typeOf(PDField field) {
        if (field instanceof PDTextField) {
            return "text";
        }
        if (field instanceof PDCheckBox) {
            return "checkbox";
        }
        if (field instanceof PDRadioButton) {
            return "radio";
        }
        if (field instanceof PDChoice) {
            return "choice";
        }
        if (field instanceof PDSignatureField) {
            return "signature";
        }
        if (field instanceof PDButton) {
            return "pushbutton";
        }
        if (field instanceof PDNonTerminalField) {
            return "non-terminal";
        }
        return field.getFieldType() != null ? field.getFieldType() : "unknown";
    }

    private static Object describeValue(Object value) {
        if (value instanceof String text) {
            return text.length() <= 200 ? text : text.substring(0, 200) + "...";
        }
        return value;
    }

    // -------------------------------------------------------------------
    // Appearances
    // -------------------------------------------------------------------

    /**
     * Buttons are the one field family whose appearance PDFBox does not build: it only moves the
     * {@code /AS} state, so a widget without {@code /AP} stays blank however the value is set.
     * The answer says it instead of letting a caller wonder.
     */
    private static void noteButtonAppearances(PdfEditContext ctx, List<PDField> fields) {
        int withoutAppearance = 0;
        String first = null;
        for (PDField field : fields) {
            if (!(field instanceof PDButton button)) {
                continue;
            }
            // PDButton is a PDTerminalField, so the widgets are reached without a second test.
            for (PDAnnotationWidget widget : button.getWidgets()) {
                if (widget.getAppearance() == null) {
                    withoutAppearance++;
                    if (first == null) {
                        first = field.getFullyQualifiedName();
                    }
                }
            }
        }
        if (withoutAppearance > 0) {
            ctx.note(withoutAppearance + " button widget(s) carry no /AP appearance stream (first:"
                    + " '" + first + "'): the value and the /AS state are written, but PDFBox does"
                    + " not draw a checkbox or radio mark - the viewer shows its own symbol, and a"
                    + " viewer that trusts /AP shows an empty field");
        }
    }

    /**
     * Makes sure the form can build appearances at all: {@code /DA} names a font and
     * {@code /DR} holds it. A form that names neither gets a Base-14 Helvetica - and the answer
     * says so, because "the font was added" is a change nobody asked for.
     * <p>
     * This is the exact place PDFBox refuses to work: {@code refreshAppearances()} reads
     * {@code /DA} and looks its font up in {@code /DR}; a missing one throws
     * {@code IOException("Could not find font: /Helv")} and no appearance is written.
     * </p>
     */
    private static void ensureAppearanceResources(PdfEditContext ctx, PDAcroForm form,
                                                  List<PDField> fields) {
        PDResources resources = form.getDefaultResources();
        if (resources == null) {
            resources = new PDResources();
            form.setDefaultResources(resources);
            form.getCOSObject().setNeedToBeUpdated(true);
            ctx.note("the form had no /DR (default resources) - refreshAppearances() needs it for"
                    + " the font of /DA, so a Base-14 Helvetica was added under the name it is"
                    + " asked for");
        }
        if (form.getDefaultAppearance() == null || form.getDefaultAppearance().isBlank()) {
            form.setDefaultAppearance(FALLBACK_DA);
            form.getCOSObject().setNeedToBeUpdated(true);
            ctx.note("the form had no /DA (default appearance) - '/" + FALLBACK_FONT + " 0 Tf 0 g'"
                    + " was written, which is what a viewer assumes anyway");
        }
        Set<String> wanted = new LinkedHashSet<>();
        addFontNames(form.getDefaultAppearance(), wanted);
        if (wanted.isEmpty()) {
            // A /DA without a Tf names no font at all; PDFBox would build an appearance with a
            // null font. The form gets the conventional appearance string instead - it is what a
            // viewer assumes, and the answer says it.
            form.setDefaultAppearance(FALLBACK_DA);
            form.getCOSObject().setNeedToBeUpdated(true);
            ctx.note("the form's /DA names no font ('Tf' is missing) - '" + FALLBACK_DA
                    + "' was written, because an appearance cannot be built without one");
            addFontNames(FALLBACK_DA, wanted);
        }
        for (PDField field : fields) {
            if (!(field instanceof PDVariableText text)) {
                continue;
            }
            String da = text.getDefaultAppearance();
            if (da == null || da.isBlank()) {
                // A field without a /DA of its own inherits the form's (PDField looks up the
                // AcroForm as the last parent), so nothing has to be copied here.
                continue;
            }
            Set<String> fieldNames = new LinkedHashSet<>();
            addFontNames(da, fieldNames);
            if (fieldNames.isEmpty()) {
                text.setDefaultAppearance(form.getDefaultAppearance());
                field.getCOSObject().setNeedToBeUpdated(true);
                ctx.note("field '" + field.getFullyQualifiedName() + "' had a /DA without a Tf"
                        + " operator - the form's default appearance was written in its place");
            } else {
                wanted.addAll(fieldNames);
            }
        }
        boolean added = false;
        for (String name : wanted) {
            COSName cosName = COSName.getPDFName(name);
            PDFont font;
            try {
                font = resources.getFont(cosName);
            } catch (IOException e) {
                font = null;
            }
            if (font == null) {
                resources.put(cosName, new PDType1Font(standardFont(name)));
                added = true;
            }
        }
        if (added) {
            form.setDefaultResources(resources);
            // An incremental save writes what is marked: the AcroForm dict carries the /DR entry,
            // and the /DR dictionary itself is an object of its own when the file made it one.
            resources.getCOSObject().setNeedToBeUpdated(true);
            form.getCOSObject().setNeedToBeUpdated(true);
            ctx.note("a font named in /DA was missing from /DR - a Base-14 Helvetica was added"
                    + " under that name (" + String.join(", ", wanted) + "), because"
                    + " refreshAppearances() cannot build an appearance without it");
        }
    }

    /** The font a missing {@code /DA} name is filled with: the standard 14, by its usual names. */
    private static Standard14Fonts.FontName standardFont(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.startsWith("times")) {
            return lower.contains("bold") ? Standard14Fonts.FontName.TIMES_BOLD
                    : Standard14Fonts.FontName.TIMES_ROMAN;
        }
        if (lower.startsWith("courier")) {
            return Standard14Fonts.FontName.COURIER;
        }
        if (lower.contains("bold")) {
            return Standard14Fonts.FontName.HELVETICA_BOLD;
        }
        return Standard14Fonts.FontName.HELVETICA;
    }

    /** Collects the {@code /Name} operands of the {@code Tf} operators of a {@code /DA} string. */
    private static void addFontNames(String defaultAppearance, Set<String> names) {
        if (defaultAppearance == null) {
            return;
        }
        Matcher matcher = DA_FONT.matcher(defaultAppearance);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
    }

    /**
     * Marks the objects an incremental save has to write: there must be a path of
     * {@code setNeedToBeUpdated()} objects from the catalog down to every change.
     */
    private static void markTouched(PdfEditContext ctx, PDAcroForm form, List<PDField> fields) {
        ctx.document().getDocumentCatalog().getCOSObject().setNeedToBeUpdated(true);
        form.getCOSObject().setNeedToBeUpdated(true);
        PDResources resources = form.getDefaultResources();
        if (resources != null) {
            resources.getCOSObject().setNeedToBeUpdated(true);
        }
        Set<COSDictionary> pages = new LinkedHashSet<>();
        for (PDField field : fields) {
            field.getCOSObject().setNeedToBeUpdated(true);
            PDField parent = field.getParent();
            while (parent != null) {
                parent.getCOSObject().setNeedToBeUpdated(true);
                parent = parent.getParent();
            }
            if (field instanceof PDTerminalField terminal) {
                for (PDAnnotationWidget widget : terminal.getWidgets()) {
                    widget.getCOSObject().setNeedToBeUpdated(true);
                    PDPage page = widget.getPage();
                    if (page != null) {
                        pages.add(page.getCOSObject());
                    }
                }
            }
        }
        if (!pages.isEmpty()) {
            for (COSDictionary page : pages) {
                page.setNeedToBeUpdated(true);
            }
        } else {
            // A widget without /P still hangs in a page's /Annots; marking every page is the
            // honest fallback (a few kilobytes in the incremental update, a missing /AP otherwise).
            for (PDPage page : ctx.document().getPages()) {
                page.getCOSObject().setNeedToBeUpdated(true);
            }
        }
    }

    private static PdfEditException broken(PdfEditOp op, IOException e) {
        return new PdfEditException("broken", op.where() + ": " + e.getMessage(),
                "the appearance could not be built - pdf.formFields(path) says whether the form"
                        + " has /DA and /DR, pdfedit.refresh repairs a missing default font", e);
    }

    /** The value forms a caller may use, for the status answer. */
    static List<String> valueForms() {
        return List.of("text: a string (a number is written as its decimal text)",
                "checkbox: true or false, or '/On' and '/Off'",
                "radio: the export value (or on state) of one button",
                "choice: one export value, or an array of them where /Ff bit 22 allows it");
    }
}
