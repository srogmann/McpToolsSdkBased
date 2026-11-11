package org.rogmann.mcp2sdk.pdfedit;

import org.apache.pdfbox.util.Version;
import org.rogmann.mcp2sdk.js.JsFileSystem;
import org.rogmann.mcp2sdk.js.JsUserRuntimeException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * The {@code pdfedit} module: PDFs create and change, with PDFBox.
 * <p>
 * {@code pdf} explains a document and never rewrites one; {@code pdfedit} writes a new one and
 * has the result checked by the explainer. Both are stateless - one call, one answer, no handle -
 * and both share one vocabulary: a page is a 1-based number as {@code pdf.pages()} prints it, an
 * object is a number as {@code pdf.objects()} prints it, a stream's bytes are what
 * {@code pdf.decodeStream()} returns.
 * </p>
 * <p>
 * Writing is gated: the module refuses {@code create} and {@code edit} unless the server was
 * started with {@code -Dmcp.js.pdfedit.readonly=false}. Reading ({@code verify}, {@code status},
 * {@code help}) works either way - so a deployment can hand out the checker without handing out
 * the writer.
 * </p>
 */
public final class JsPdfEdit {

    /** System property that frees the write path: {@code -Dmcp.js.pdfedit.readonly=false}. */
    public static final String PROP_READONLY = "mcp.js.pdfedit.readonly";

    private JsPdfEdit() {
        // Utility class
    }

    /**
     * Tells whether this server may write PDFs.
     * <p>
     * The property is read on every call and validated strictly: a value that is neither
     * {@code true} nor {@code false} aborts with the property name and the value, because a typo
     * must not decide whether a deployment can write files.
     * </p>
     * @return true when {@value #PROP_READONLY} is {@code false}
     */
    public static boolean isWriteEnabled() {
        String raw = System.getProperty(PROP_READONLY, "false").trim().toLowerCase(Locale.ROOT);
        if (raw.equals("true")) {
            return false;
        }
        if (raw.equals("false")) {
            return true;
        }
        throw new PdfEditException("readonly", "the property " + PROP_READONLY + " reads '" + raw
                + "'", "set it to true (the default, writing disabled) or false (writing allowed)");
    }

    // -------------------------------------------------------------------
    // create / edit
    // -------------------------------------------------------------------

    /**
     * Creates a new document.
     * @param ops the op list
     * @param options the parsed options
     * @return the answer
     */
    public static Map<String, Object> create(Object ops, PdfEditOptions options) {
        requireWriteAccess();
        return PdfEditDoc.create(ops, options);
    }

    /**
     * Applies ops to a document given by path.
     * @param filePath project-relative path
     * @param ops the op list
     * @param options the parsed options
     * @return the answer
     */
    public static Map<String, Object> edit(String filePath, Object ops, PdfEditOptions options) {
        requireWriteAccess();
        Source source = read(filePath);
        return PdfEditDoc.edit(source.displayName(), source.data(), ops, options);
    }

    /**
     * Applies ops to a document given as bytes.
     * @param data the bytes
     * @param ops the op list
     * @param options the parsed options
     * @return the answer
     */
    public static Map<String, Object> edit(byte[] data, Object ops, PdfEditOptions options) {
        requireWriteAccess();
        return PdfEditDoc.edit(name(null), data, ops, options);
    }

    /**
     * Applies ops to a document given as bytes.
     * @param data the bytes
     * @param displayName the name used in messages, may be {@code null}
     * @param ops the op list
     * @param options the parsed options
     * @return the answer
     */
    public static Map<String, Object> edit(byte[] data, String displayName, Object ops,
                                           PdfEditOptions options) {
        requireWriteAccess();
        return PdfEditDoc.edit(name(displayName), data, ops, options);
    }

    /**
     * Verifies a document given by path (works in read-only mode).
     * @param filePath project-relative path
     * @param strict true to turn every note into a problem
     * @return {@code {ok, problems, notes, metrics}}
     */
    public static Map<String, Object> verify(String filePath, boolean strict) {
        Source source = read(filePath);
        return PdfEditVerify.run(source.displayName(), source.data(), strict);
    }

    /**
     * Verifies a document given as bytes (works in read-only mode).
     * @param data the bytes
     * @param displayName the name used in messages, may be {@code null}
     * @param strict true to turn every note into a problem
     * @return {@code {ok, problems, notes, metrics}}
     */
    public static Map<String, Object> verify(byte[] data, String displayName, boolean strict) {
        return PdfEditVerify.run(name(displayName), data, strict);
    }

    // -------------------------------------------------------------------
    // status / help
    // -------------------------------------------------------------------

    /**
     * The module's status: what it is, whether it may write, and every budget it obeys.
     * @return the status map
     */
    public static Map<String, Object> status() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("module", "pdfedit");
        map.put("library", "Apache PDFBox " + Version.getVersion());
        map.put("scope", "writing PDFs: create, edit pages, write text and shapes, place images,"
                + " cut and insert operators, set bookmarks, add annotations and links, fill and"
                + " flatten an interactive form, merge another document, embed a TrueType font,"
                + " and verify the result with the project's own reader");
        // status() must answer even when the property is broken - that is when it is called.
        boolean writeEnabled;
        try {
            writeEnabled = isWriteEnabled();
        } catch (PdfEditException e) {
            writeEnabled = false;
            map.put("propertyError", e.getMessage());
        }
        map.put("readonly", !writeEnabled);
        map.put("writeEnabled", writeEnabled);
        map.put("property", PROP_READONLY);
        map.put("ops", new ArrayList<Object>(PdfEditOps.IMPLEMENTED));
        List<Object> planned = new ArrayList<>();
        for (Map.Entry<String, String> entry : PdfEditOps.PLANNED.entrySet()) {
            planned.add(entry.getKey() + " (increment " + entry.getValue() + ")");
        }
        map.put("plannedOps", planned);
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("maxInputBytes", PdfEditLimits.MAX_INPUT_BYTES);
        limits.put("maxOutputBytes", PdfEditLimits.MAX_OUTPUT_BYTES);
        limits.put("maxIncrementalBytes", PdfEditLimits.MAX_INCREMENTAL_BYTES);
        limits.put("maxOps", (long) PdfEditLimits.MAX_OPS);
        limits.put("maxPagesAdded", (long) PdfEditLimits.MAX_PAGES_ADDED);
        limits.put("maxStreamBytes", (long) PdfEditLimits.MAX_STREAM_BYTES);
        limits.put("maxCutOperators", (long) PdfEditLimits.MAX_CUT_OPERATORS);
        limits.put("maxFontBytes", (long) PdfEditLimits.MAX_FONT_BYTES);
        limits.put("maxTextChars", (long) PdfEditLimits.MAX_TEXT_CHARS);
        limits.put("maxMeasureTexts", (long) PdfEditLimits.MAX_MEASURE_TEXTS);
        limits.put("maxOutlineItems", (long) PdfEditLimits.MAX_OUTLINE_ITEMS);
        limits.put("maxAnnotationChars", (long) PdfEditLimits.MAX_ANNOTATION_CHARS);
        limits.put("maxFieldValueChars", (long) PdfEditLimits.MAX_FIELD_VALUE_CHARS);
        limits.put("maxVerifyPages", (long) PdfEditLimits.MAX_VERIFY_PAGES);
        limits.put("maxVerifyObjects", (long) PdfEditLimits.MAX_VERIFY_OBJECTS);
        map.put("limits", limits);
        map.put("options", new ArrayList<Object>(new TreeSet<>(PdfEditOptions.NAMES)));
        map.put("pageSizes", List.of("A0", "A1", "A2", "A3", "A4", "A5", "A6", "LETTER", "LEGAL",
                "TABLOID"));
        map.put("fonts", new ArrayList<Object>(PdfEditFonts.names()));
        map.put("fontForms", List.of(
                "'Helvetica' - a standard font, nothing is embedded",
                "{file:'fonts/x.ttf', subset:true} - a TrueType file is embedded (subset by"
                        + " default)",
                "{objNum:12} - a simple font the page already uses"));
        map.put("colors", List.of("black", "white", "red", "green", "blue", "yellow", "orange",
                "purple", "brown", "pink", "gray", "lightgray", "darkgray", "navy", "teal",
                "lime", "#rrggbb", "[gray]", "[r,g,b]", "[c,m,y,k]"));
        map.put("spaces", List.of("page (origin top-left, y downwards, the frame"
                + " pdf.placement() reports)", "user (default user space, y upwards)"));
        map.put("annotationTypes", new ArrayList<Object>(PdfEditAnnots.types()));
        map.put("formValueForms", new ArrayList<Object>(PdfEditForms.valueForms()));
        map.put("units", List.of("pt", "mm", "cm", "in"));
        map.put("determinism", "the /ID is the caller's id or a hash of input and ops; without a"
                + " dates option no date is written - the same call writes the same bytes");
        map.put("numbering", "a full save writes the objects in traversal order, so the object"
                + " numbers of the result differ from the input; incremental:true keeps them");
        map.put("notImplemented", List.of(
                "encryption and decryption",
                "signing (an incremental update keeps an old signature's bytes, the new revision"
                        + " would be reported by a DocMDP-aware viewer)",
                "repaired tag structure (/StructTreeRoot is reported, not rebuilt)",
                "filling an XFA form (its fields stand in the packet, not in the AcroForm -"
                        + " form.fill refuses with code 'xfa')",
                "rendering, layout (no tables, no justification, no hyphenation)",
                "redaction as a command: removing content is content.cut or stream.set, and"
                        + " covering it is draw.rect - the help shows the recipe",
                "linearization"));
        return map;
    }

    /**
     * The help text: the calls, the ops, the limits and the recipes that are not ops.
     * <p>
     * The limits are filled in from {@link PdfEditLimits}: a number that is written twice drifts
     * apart, and a help text that names a limit the code does not obey is worse than none.
     * </p>
     * @return the help text
     */
    public static String help() {
        return HELP
                .replace("{{maxInputBytes}}", Long.toString(PdfEditLimits.MAX_INPUT_BYTES))
                .replace("{{maxOutputBytes}}", Long.toString(PdfEditLimits.MAX_OUTPUT_BYTES))
                .replace("{{maxIncrementalBytes}}",
                        Long.toString(PdfEditLimits.MAX_INCREMENTAL_BYTES))
                .replace("{{maxOps}}", Integer.toString(PdfEditLimits.MAX_OPS))
                .replace("{{maxPagesAdded}}", Integer.toString(PdfEditLimits.MAX_PAGES_ADDED))
                .replace("{{maxStreamBytes}}", Integer.toString(PdfEditLimits.MAX_STREAM_BYTES))
                .replace("{{maxCutOperators}}", Integer.toString(PdfEditLimits.MAX_CUT_OPERATORS))
                .replace("{{maxFontBytes}}", Integer.toString(PdfEditLimits.MAX_FONT_BYTES))
                .replace("{{maxTextChars}}", Integer.toString(PdfEditLimits.MAX_TEXT_CHARS))
                .replace("{{maxMeasureTexts}}",
                        Integer.toString(PdfEditLimits.MAX_MEASURE_TEXTS))
                .replace("{{maxOutlineItems}}",
                        Integer.toString(PdfEditLimits.MAX_OUTLINE_ITEMS))
                .replace("{{maxAnnotationChars}}",
                        Integer.toString(PdfEditLimits.MAX_ANNOTATION_CHARS))
                .replace("{{maxFieldValueChars}}",
                        Integer.toString(PdfEditLimits.MAX_FIELD_VALUE_CHARS))
                .replace("{{fonts}}", String.join(", ", PdfEditFonts.names()))
                .replace("{{maxVerifyPages}}", Integer.toString(PdfEditLimits.MAX_VERIFY_PAGES))
                .replace("{{maxVerifyObjects}}",
                        Integer.toString(PdfEditLimits.MAX_VERIFY_OBJECTS));
    }

    /** The help text of the module (also the source of examples the tests pin). */
    private static final String HELP = """
            pdfedit - create and change PDFs (Apache PDFBox)
            ================================================
            `pdf` explains a document and never rewrites one; `pdfedit` writes a new document and
            has it checked by the explainer. Both are stateless: one call, one answer, no handle.
            Every address is a number `pdf` printed - a page is 1-based (`pdf.pages()`), an object
            is what `pdf.objects()` shows, a stream's bytes are what `pdf.decodeStream()` returns,
            an operator offset is what `pdf.operations()` prints.

            CALLS
            -----
              pdfedit.create(ops[, options])           -> RESULT (a new document)
              pdfedit.edit(input, ops[, options])      -> RESULT (input: path or Uint8Array)
              pdfedit.verify(input[, {strict}])        -> {ok, problems, notes, metrics}
              pdfedit.status()                         -> limits, ops, whether writing is enabled
              pdfedit.help()                           -> this text

            The result of create/edit:
              {data: Uint8Array, bytes, bytesBefore, changed[], measured[], notes[],
               geometry[], verify?}
            `pdfedit` never writes a file: the caller does, e.g. fs.writeBytes('out.pdf', r.data).
            `changed` lists what the file holds, `measured` what a measuring op answered
            (text.measure) - an op list may do both.

            OPTIONS (strict - an unknown name is an error that names the supported ones)
              incremental:false       true appends a new revision to the file instead of rewriting
                                      it; not possible for page.add/remove/move and not for create
              compress:false          true packs objects into object streams (writes an xref stream)
              id:<long>               seeds the trailer's /ID; without it the ID is a hash of
                                      input and ops, so the same call writes the same bytes
              dates:{created,modified}  ISO-8601 strings; without dates no date is written
              verify:true             verify the result with the project's own reader
              maxOutputBytes:<n>      refuse a result larger than n bytes

            COORDINATES, UNITS AND THE ECHO
            -------------------------------
            Every op that places something names its space - there is no default, because the two
            differ by a mirror:
              space:'page'   origin at the TOP-LEFT of the visible box, y downwards, unit one point.
                            This is the frame pdf.placement() prints as `frame` - CropBox,
                            /Rotate and /UserUnit included, so a rotated page needs no arithmetic.
              space:'user'   default user space, y UPWARDS - the space the page's own content uses.
            unit:'pt' (default) | 'mm' | 'cm' | 'in' scales every length of the op; a font size
            and a line width stay in points. The answer echoes what the numbers became:
              geometry:[{op:'text.put', page:3, space:'page', given:[72,700], user:[72,92],
                         unit:'pt', pageWidth:595.3, pageHeight:842, rotation:0}]
            A y of 700 in page space is a y of pageHeight - 700 in user space; the echo shows it.

            OPS (applied from front to back; each op sees the result of the one before it)
            --------------------------------------------------------------------------------
            doc.info
              {op:'doc.info', set:{title:'Bericht', author:'R. Ogmann'}}
              Fields: title, author, subject, keywords, creator, producer. Only the named fields
              are written; dates come from the `dates` option (object.set reaches any other field).

            doc.version
              {op:'doc.version', value:1.7}
              Sets the version in the header and (from 1.4) in the catalog. 1.0 … 1.7 and 2.0.
              A downgrade writes the entries but converts nothing - verify() then says whether the
              file still reads (PDFBox itself refuses a downgrade, so the op does it explicitly).

            doc.outline
              {op:'doc.outline', set:[{title:'Kapitel 1', page:1, y:760, open:true,
                                        children:[{title:'Anhang', page:7}]}]}
              {op:'doc.outline', prune:true}
              set replaces the whole bookmark tree (the old one is gone - that is in notes);
              a bookmark without y jumps to the top of the page, open:false shows it collapsed.
              prune keeps the tree and removes only the entries whose destination leads nowhere
              (an entry with no destination at all - a separator or a URI - is not touched).
              For "page 7 is gone, drop what pointed there" this is the op next to page.remove.

            doc.merge
              {op:'doc.merge', from:'anhang.pdf', formFields:'drop', outlines:'append'}
              {op:'doc.merge', from:'anhang.pdf', pages:[2,3], at:1, formFields:'keep'}
              Appends another document - its pages and, unless 'pages' says otherwise, its form
              fields, its bookmarks and its page labels. from is a project path or a Uint8Array.
              formFields decides about the source's form:
                drop (default)  the /AcroForm of the source is not merged; its widgets stay as
                                annotations that still show their value, the fields are gone
                keep            the fields come along and KEEP their names - a name that exists
                                in both documents is reported in `conflicts` and in a note per
                                collision (PDFBox would rename it to dummyFieldName0, silently,
                                which breaks every /AA that names the field)
              outlines:'append' (default) adds the source's bookmarks after the existing ones,
              outlines:'drop' leaves them out. Page labels are repaired: the appended pages either
              continue a decimal numbering or get a decimal range of their own - the answer lists
              every range it added, and verify() reports a page without a label as a problem.
              at:<page> puts the block at a position (default: append). pages:[…] takes a
              selection: the pages, their fields (with formFields:'keep') and their bookmarks -
              the source's named destinations, attachments, open action, page labels and tag
              structure stay behind, and a link that leads to a page outside the selection is
              removed and counted. A merge changes the page tree, so incremental:true is refused.

            page.import
              {op:'page.import', from:'anhang.pdf', pages:[2,3], at:1}
              Takes pages and nothing else: no form fields, no bookmarks, no named destinations,
              no page labels, no metadata. The order of `pages` is the order in the result, at is
              the position of the first imported page (default: append). For the whole document
              with everything it carries, use doc.merge.

            page.add
              {op:'page.add', size:'A4'}
              {op:'page.add', width:210, height:297, unit:'mm', at:1, rotation:90}
              size is A0..A6, LETTER, LEGAL, TABLOID, or width/height with unit pt|mm|cm|in.
              Position: at (1-based; count+1 appends), before:<page>, after:<page> - or nothing
              for "append". rotation is 0, 90, 180 or 270.

            page.remove
              {op:'page.remove', pages:[40,41,42], refs:'prune'}
              refs decides what happens to the pointers that lead to the page:
                prune (default)  remove them and count what was removed (pruned)
                keep             leave them and list them (orphans)
                error            refuse when anything points at the page
              Pruned are bookmarks, links to the page, named destinations, the open action, form
              fields whose widget sits on the page, and page labels that no longer match. The tag
              structure is reported, not repaired. A full save drops the removed pages' objects.

            page.move
              {op:'page.move', page:3, at:1}       also before:<page> / after:<page>

            page.set
              {op:'page.set', page:1, rotation:90}
              {op:'page.set', page:1, mediaBox:[0,0,595.3,841.9], cropBox:[20,20,575.3,821.9]}
              Writes /MediaBox, /CropBox (null removes it), /Rotate or /UserUnit - the four
              entries that decide where page coordinates stand. Everything already on the page
              keeps its user-space coordinates, so it moves with the frame; the answer says so.

            text.put
              {op:'text.put', page:1, space:'page', x:57, y:70, text:'Prüfbericht',
               font:'Helvetica-Bold', size:18, color:'black'}
              ONE line at a coordinate; the anchor is the start of the baseline, not the top of
              the glyphs, and a line break in the text is refused (text.block wraps a paragraph).
              Optional: rotate (degrees counterclockwise on the page), charSpacing
              (Tc), wordSpacing (Tw - or its TJ equivalent: on an embedded TrueType font, a
              Type 0 font whose codes are two bytes wide, a Tw would have no effect, so the gap
              is written as TJ adjustments; changed[].wordSpacingMode says which way). Three
              forms of font:
                'Helvetica-Bold'                     a standard font - {{fonts}} - nothing is
                                                     embedded, so the text works everywhere and
                                                     takes no space
                {file:'fonts/x.ttf', subset:true}    a TrueType file of the project is embedded;
                                                     subset (the default) keeps only the glyphs
                                                     the call writes, subset:false the whole file
                {objNum:12}                          a font the page already uses - "the same
                                                     font as the rest of the page" (simple fonts
                                                     only: a Type 0/CID font codes glyphs, not
                                                     text, and is refused with that reason)
              A character the font cannot show (Cyrillic, CJK, a symbol) is refused instead of
              becoming a question mark: a standard font covers WinAnsi, an embedded file whatever
              its glyphs have. Every text op names the font in changed[].font and where it came
              from in changed[].fontSource.

            text.block
              {op:'text.block', page:1, space:'page', x:57, y:95, width:480, text:'Die Anlage 3
               stimmt nicht mit der Aufstellung überein …', font:'Helvetica', size:10,
               leading:13, align:'left'}
              Wraps at word boundaries into ONE column: no justification, no hyphenation, no
              flowing around anything. A word wider than the column is not broken - it runs over,
              and the answer says how wide the widest line became. leading defaults to 1.2 × size;
              a line break in the text starts a new line. align is left|center|right.
              For a whole page built from these ops - headings, justified paragraphs, tables and
              a flowchart - the server ships a library: loadShipped('pdftext'). Its manual is
              loadShipped('pdftext').help(): the calls, the unit of a column width, and what a run
              reports in notes/dropped. It measures with text.measure and writes nothing but these
              ops.

            text.measure
              {op:'text.measure', text:'Leistung', font:'Helvetica', size:9}
              {op:'text.measure', texts:['Nr.','Leistung','Betrag'], size:9}
              Answers how wide text is and writes nothing. The numbers are the ones text.put
              produces: the same font object, the same width source (PDFBox' AFM table for a
              standard font, the programme's hmtx for an embedded file) and the same
              charSpacing/wordSpacing. That is the op a table layout needs - a cell's width has to
              be known before the cell is written, and a second implementation of "how wide is
              this word" would put the line elsewhere.
              `text` is one line, `texts` is an array of lines (at most {{maxMeasureTexts}}).
              The answer lands in `measured[]` and not in `changed[]`, because a width is
              arithmetic and not something the file holds:
                {op:'text.measure', what, font, fontSource, size, count, chars,
                 widths:[…], widest, ascent?, descent?, capHeight?, xHeight?}
              widths are in points, in the order the strings were passed; widest is the widest of
              them; the four vertical metrics are the font's, scaled to `size` - a key is missing
              where the font has no such value (never an invented 0). An empty string measures 0.
              Measuring embeds nothing: an embedded file is loaded into a scratch document that is
              closed at the end of the op, and a standard font needs no document at all. {objNum:…}
              needs `page` - a font of the page is found through the page.

            draw.rect
              {op:'draw.rect', page:3, space:'page', x:120, y:700, width:96, height:11,
               fill:'black'}
              fill, stroke, both or neither (then a black outline). lineWidth is in points.
              fill:'black' COVERS - the text below stays copyable and searchable; removing it is
              content.cut. Colours: a name, '#rrggbb', or [gray] / [r,g,b] / [c,m,y,k].

            draw.line
              {op:'draw.line', page:1, space:'page', x1:57, y1:120, x2:538, y2:120,
               color:'gray', lineWidth:0.5, dash:[3,2]}
              A straight line - that is all a line is here (curves are content.append with c/v/y).

            image.put
              {op:'image.put', page:1, space:'page', x:57, y:140, width:120, height:80,
               path:'logo.png'}
              data:<Uint8Array> or path, format 'png' (default when the header says so) or
              'jpeg' (embedded as it is, no re-encoding). The image becomes an /XObject of the
              page; opacity (0..1) goes into an /ExtGState, rotate turns it around its centre.
              page space puts the image upright on the displayed page, even when /Rotate is 90.

            content.append / content.prepend
              {op:'content.append', page:1, text:'0 0 1 rg 72 72 100 20 re f'}
              The text is written as given into a NEW stream at the end (append) or the beginning
              (prepend) of the page's /Contents array - no q/Q is wrapped around it and no
              /Resources entry is created. The text is ASCII (a content stream is); write a
              non-ASCII character as a PDF string, e.g. <00FC> inside a Tj operand. prepend is
              dangerous when the page's own stream leaves a q open - the original content would
              run in a changed graphics state.

            content.cut
              {op:'content.cut', page:3, offsets:[11706]}
              Removes WHOLE operations - the offsets are the ones pdf.operations() prints, in the
              DECODED bytes that pdf.decodeStream() shows. The span of an operation runs from the
              end of the previous one to the end of its operator, so cutting a Tj takes its string
              operand with it (that is "remove this word"). Refused: an offset that is not an
              operator (the nearest one is named), and a cut that would leave BT/ET, q/Q or
              BMC/EMC unbalanced (code:'imbalanced'). The answer lists every cut with its
              `start` - the offset the removed operation began at, which is where the text that
              followed now stands.

            content.insert
              {op:'content.insert', page:3, at:11702, text:'1 0 0 1 -34 0 Tm'}
              Inserts operator text at a byte offset of the decoded content. The offset has to be
              a position between two tokens - 0, the end of the content, the `start` a cut
              reported, or an operator boundary; a position inside an operand is refused (that
              would split a number or a string). A separating space or line ending is added when
              the text would otherwise merge with its neighbour, and the answer says so.

            annot.add
              {op:'annot.add', page:2, type:'text', rect:[480,500,18,18], contents:'Rückfrage offen',
               author:'R. Ogmann', subject:'Prüfvermerk', color:'yellow', open:true}
              {op:'annot.add', page:2, type:'freetext', rect:[300,500,180,40],
               contents:'siehe Anlage 3', author:'R. Ogmann', size:9, color:'blue'}
              {op:'annot.add', page:2, type:'highlight', quads:[72,652,120,652,72,640,120,640],
               contents:'hier', color:'yellow'}
              Nine types: text, freetext, square, circle, highlight, underline, strikeout, ink,
              stamp. rect is [x1,y1,x2,y2]; an annotation's /Rect is in default user space by
              definition (12.5.2), so space:'user' is the default HERE - the one place the
              "no default for the space" rule does not apply. space:'page' is accepted and
              echoed like draw.rect does it. The marks (highlight/underline/strikeout) need
              quads: eight numbers per mark, [x1,y1, x2,y2, x3,y3, x4,y4] - the box around them
              is the /Rect when none is given. A mark NEEDS a color (color:'yellow' is the usual
              highlight), and so does an ink stroke (its width is lineWidth, default 1 pt) - without
              one the appearance handler draws nothing and the annotation would be invisible, so
              the op refuses it instead. A square or circle needs color (the border) or fill (the
              interior) for the same reason. ink needs inkList - one array of points per stroke,
              written flat ([x1,y1,x2,y2, …]) or as pairs ([[x,y],[x,y]]); where more than one
              stroke is meant, one array per stroke. icon names the symbol of a text annotation (Note, Comment, Check, …) or
              the name of a stamp (Draft, Approved, Confidential, …). open:true shows the note
              unfolded. flags defaults to 4 (Print); replyTo:<objNum> makes the annotation a
              reply to an existing one (/IRT + /RT /R).
              Every annotation gets its appearance from PDFBox' constructAppearances(); a
              stamp has no handler there, so without more it gets hasAppearance:false and the
              viewer draws its own symbol - xpdf, gv and mupdf do, Firefox (pdf.js) draws
              nothing without an /AP (measured in docs/js/pdfedit.md §20.2). A stamp may
              bring its appearance: ap:{stream:<Uint8Array>, value:{…}} writes the stream as
              the /AP /N form XObject (/Type /XObject /Subtype /Form /FormType 1, /BBox the
              size of the /Rect, identity /Matrix; value adds or overrides entries, e.g.
              Resources:{Font:{Helv:{ref:'$font'}}} with a font from object.add in the same
              call) - one call, and every viewer shows the same picture. For a stamp that
              already exists the two-call way stays: pdf.pages({annotations:true}) names its
              object number, object.add writes the form XObject, object.set {AP:{N:{ref:'$ap'}}}
              attaches it (tools/pdfedit-e2-visual.js builds target/e2-visual-4-stamp-ap.pdf
              that way). /M is written only when the call names dates.

            link.add
              {op:'link.add', page:1, rect:[72,700,200,714], url:'https://example.org'}
              {op:'link.add', page:1, rect:[72,680,200,694], target:7}
              {op:'link.add', page:1, rect:[72,660,200,674], dest:'kapitel-7'}
              Exactly one destination: url (a URI action - a javascript: URI is refused, this
              module executes nothing), target (a 1-based page number, /Fit) or dest (the name of
              a named destination, not checked here - pdf.names(path, 'Dests') answers whether it
              exists). highlight is none|invert|outline|push, color is the border colour,
              lineWidth its width.

            form.fill
              {op:'form.fill', fields:{'person.name':'R. Ogmann', 'ja':true, 'land':'DE'},
               refresh:true, flatten:false}
              The field names are the FULLY QUALIFIED ones pdf.formFields() prints (the /T chain
              joined with '.'), and an unknown name is an error (code:'unknown-field') that lists
              the names the form has - a typo would otherwise be an invisibly empty form.
              Values by type: text = a string; checkbox = true/false (or '/On' and '/Off');
              radio = the export value of one button; choice = one export value or an array of
              them where the field allows more than one. A value the field cannot hold is
              refused (outside /Opt, no such button, longer than /MaxLen), a signature field and
              a push button are refused, and an XFA form is refused with code:'xfa' - its fields
              stand in the packet, not in the AcroForm.
              setValue writes /V, which no viewer shows by itself: refresh:true (the default)
              rebuilds every appearance with refreshAppearances(), and adds a Base-14 Helvetica
              to /DR when /DA names a font the form does not have (the answer says so).
              refresh:false leaves the value in the file and the old picture on the page.
              flatten:true flattens the whole form after filling it.

            form.flatten
              {op:'form.flatten'}
              {op:'form.flatten', fields:['person.name', 'land']}
              Writes each widget's appearance into the page content and removes the fields: the
              document looks the same everywhere and is no longer fillable. A widget without
              /AP has nothing to write - fill first (refresh:true builds the appearance). When
              the last field is gone the empty /AcroForm is removed from the catalog.

            object.set
              {op:'object.set', objNum:12, value:{Rotate:90}}
              Sets entries of an existing indirect object (a dictionary, or a stream's dictionary).
              A dictionary or an array given as a value is written INLINE: it becomes an entry of
              the object it belongs to, not an object of its own (object.add plus {ref:'$0'} is the
              way to an indirect object). That is the shape a producer writes, and it matters for
              form fields: a viewer like xpdf follows /AP into a reference but not /AP /N, so a
              widget whose state dictionary stands as its own object is drawn as nothing there.
              A value is written as a PDF writes it: '/Name' is a name, 'text' is a string,
              {ref:12} is a reference, {hex:'00FC'} a byte string, an array an array, an object a
              dictionary. `null` removes the entry (that is how an /AA is deleted).
              Careful with the two kinds of string: a value that BEGINS with '/' is a NAME. A
              default appearance begins with a font name (/Helv 9 Tf 0 g), so it has to be written
              as {DA:{hex:'2f48656c76203920546620302067'}} - written plain it becomes a name, and
              PDFBox then refuses to fill the field ("/DA is a required entry").

            object.add
              {op:'object.add', as:'$0', value:{Type:'EmbeddedFile'}, stream:<Uint8Array>}
              A NEW indirect object (a dictionary, or a stream when `stream` is given; filter is
              'none' (default) or 'flate'). Its object number is assigned while writing, so it is
              addressed in the same call as {ref:'$0'} (or any other name you gave it, e.g.
              {ref:'$outline'}) and found afterwards with pdf.objects() on the result.

            object.delete
              {op:'object.delete', objNum:12}
              Writes the object as null: the number stays taken and a reference to it reads null.
              A full save drops it entirely when nothing points at it any more.

            stream.set
              {op:'stream.set', objNum:12, data:<Uint8Array>, filter:'flate'}
              Replaces a stream's bytes completely - the raw way next to content.append, and the
              way to rewrite a content stream that pdf.decodeStream() showed.

            THE THREE LEVELS OF "APPENDING" (they are not the same)
            ------------------------------------------------------
              1. bytes in one stream     object.set / stream.set replace the content of one object
              2. a new stream on the page  content.prepend / content.append - the old stream stays
              3. a new revision of the file  edit(..., {incremental:true}) - the old bytes stay
            Level 3 keeps the previous revision in the file, so removed content stays readable
            there. For "remove" use incremental:false: that is the only level that really drops it.

            WHY "REMOVE" IS A RECIPE AND NOT AN OP
            --------------------------------------
            Removing a word is three steps, and pdfedit has no command for it on purpose:
              1. find it:   pdf.text(path, {page:3}) and pdf.operations(path, 12, {after:<offset>})
                            name the show operator and its byte offset
              2. change it: {op:'content.cut', page:3, offsets:[11706]} - and if the rest of the
                            line has to stay, {op:'content.insert', page:3, at:<start of the
                            cut>, text:'1 0 0 1 -34 0 Tm'}; the raw way is stream.set with the
                            bytes of pdf.decodeStream(path, 12)
              3. save with incremental:false, then pdf.text() on the result: a hit means "not gone"
            Covering a word (draw.rect with fill:'black') is allowed and reported as "covered",
            never as "removed" - the text stays copyable and searchable. What neither does: a word
            inside a form XObject (the page says Do) is not in the page's own stream - edit that
            stream by its object number with stream.set, or ask pdf.objects() where the text is.

            LIMITS (also in pdfedit.status().limits)
            ----------------------------------------
              maxInputBytes {{maxInputBytes}}, maxOutputBytes {{maxOutputBytes}},
              maxIncrementalBytes {{maxIncrementalBytes}}, maxOps {{maxOps}},
              maxPagesAdded {{maxPagesAdded}}, maxStreamBytes {{maxStreamBytes}},
              maxCutOperators {{maxCutOperators}}, maxFontBytes {{maxFontBytes}},
              maxTextChars {{maxTextChars}}, maxMeasureTexts {{maxMeasureTexts}},
              maxOutlineItems {{maxOutlineItems}}, maxAnnotationChars {{maxAnnotationChars}},
              maxFieldValueChars {{maxFieldValueChars}}.
              verify() walks at most maxVerifyPages {{maxVerifyPages}} pages and reads at most
              maxVerifyObjects {{maxVerifyObjects}} objects.
            A limit names itself and its value in the error, because a silently truncated document
            is worse than a refused call.

            ERRORS carry [code: …] and a hint: encrypted, broken, unsupported, out-of-range,
            imbalanced (a cut would break BT/ET, q/Q or BMC/EMC), orphans (refs:'error' found a
            pointer), unknown-field (form.fill: the name is not in the form), xfa (the form's
            fields live in the /XFA packet), usage, limit, readonly. `readonly` means the server runs without
            -Dmcp.js.pdfedit.readonly=false; verify/status/help work there either way.

            OBJECT NUMBERS: a full save writes the objects in traversal order (PDFBox), so the
            numbers of the result differ from the input - `changed[].objNum` names the input
            object, pdf.objects() on the result names the new one. With incremental:true the
            numbers stay, because the old cross-reference is still in force.
            """;


    // -------------------------------------------------------------------
    // Sources
    // -------------------------------------------------------------------

    /** A PDF source: a display name and its bytes. */
    record Source(String displayName, byte[] data) {
    }

    /**
     * Reads a PDF from the project directory (or an add-on mount).
     * @param filePath the path
     * @return the source
     */
    private static Source read(String filePath) {
        Path path = JsFileSystem.resolveSafePath(filePath);
        if (!Files.isRegularFile(path)) {
            throw new JsUserRuntimeException("File not found: " + JsFileSystem.toRelative(path));
        }
        try {
            long size = Files.size(path);
            if (size > PdfEditLimits.MAX_INPUT_BYTES) {
                throw new PdfEditException("limit", JsFileSystem.toRelative(path) + " has " + size
                        + " bytes",
                        "at most " + PdfEditLimits.MAX_INPUT_BYTES + " bytes are accepted");
            }
            return new Source(JsFileSystem.toRelative(path), Files.readAllBytes(path));
        } catch (IOException e) {
            throw new JsUserRuntimeException("Failed to read PDF: "
                    + JsFileSystem.toRelative(path), e);
        }
    }

    private static String name(String displayName) {
        return displayName == null || displayName.isBlank() ? "<bytes>" : displayName;
    }

    /**
     * Refuses the write path when the module is read-only.
     */
    private static void requireWriteAccess() {
        if (!isWriteEnabled()) {
            throw new PdfEditException("readonly", "writing is disabled (property "
                    + PROP_READONLY + " is true)",
                    "start the server with -Dmcp.js.pdfedit.readonly=false to enable"
                            + " create() and edit(); verify() works without it");
        }
    }
}
