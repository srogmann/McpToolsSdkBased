package org.rogmann.mcp2sdk.pdf.ops;

import org.rogmann.mcp2sdk.pdf.PdfContentStream;
import org.rogmann.mcp2sdk.pdf.PdfName;
import org.rogmann.mcp2sdk.pdf.PdfReference;
import org.rogmann.mcp2sdk.pdf.PdfString;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The second sink: the readable operator listing behind {@code pdf.operations()}.
 * <p>
 * One item per operator, each with its byte offset, its operands rendered back as PDF source, and
 * the comment that introduces it — the view that has been in this module the longest and the one a
 * reader uses to check any other answer ("which operator was at the offset
 * {@code pdf.placement()} just named?"). Since D3 it is a sink on the same walk that computes the
 * graphics state, and its acceptance is that the listing did not move: same offsets, same
 * {@code operandsText}, same {@code skipped} count, same comment attachment.
 * </p>
 *
 * <h2>Two budgets, and why both count as "skipped"</h2>
 * <ul>
 *   <li>{@code maxOffset} — start the listing at a byte offset (a caller paging through a long
 *       stream). Everything before it is still walked, because the state does not restart there;</li>
 *   <li>{@code maxItems} — stop filling the list after n items, keeping the rest of the stream walked
 *       and counted.</li>
 * </ul>
 * Both have always been reported as a single {@code skipped} number rather than as
 * {@code truncated}: the answer is not "the stream was too big", it is "n operators were not
 * listed", and that is what the field says. A third budget bounds one item ({@link
 * #MAX_OPERAND_VALUES}); it cuts a list, not the answer, and is named where it cut.
 *
 * <h2>The comment belongs to the operation, not to the operator</h2>
 *
 * A {@code %} comment sits between two tokens, so the tokenizer attaches it to the token that
 * follows it — usually the operation's <em>first operand</em>, not the operator. Rebuilding a listing
 * from operations alone would print the comment one operation late or drop it; the interpreter
 * carries the first comment of the pending operands onto the operation for exactly this reason (see
 * {@link PdfOperandStack#commentOffset()}).
 *
 * <h2>{@code operands} was a count under a name that reads as values (plan P1)</h2>
 *
 * The field has always held "how many operands", and every reader of a line like
 * {@code {op: "Tf", operands: 2}} expected the two values. The count keeps its number and gets its
 * own name, {@code operandCount}; the old key stays one version longer with the identical value (a
 * run somebody compares must not lose a field to a rename), and the pair is named in {@code
 * pdf.status().retiredFieldNames}. What the name promised is now there too: {@code operandValues},
 * the operands as the tokenizer saw them.
 */
public final class ListingSink implements PdfContentSink {

    /** Items one listing fills, when the caller does not say. */
    public static final int DEFAULT_MAX_ITEMS = 200;

    /**
     * Operand values one item lists. A pathological operator (a content stream that pushes a thousand
     * numbers before one {@code cm}) must not make one listing line bigger than the rest of the
     * answer; the full count stays in {@code operands}/{@code operandCount}, and a cut is named by
     * {@code operandValuesTruncated}.
     */
    public static final int MAX_OPERAND_VALUES = 24;

    /**
     * The field names this listing renamed, old &rarr; new. Printed as {@code
     * pdf.status().retiredFieldNames} for one version, in the same spirit as {@code retiredLimits}:
     * a caller reading the new name learns what it used to be called, and a caller reading the old
     * one learns that its value was never a list.
     */
    public static final Map<String, String> RETIRED_FIELD_NAMES = retiredFieldNames();

    private static Map<String, String> retiredFieldNames() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("operations.items[].operands", "operations.items[].operandCount");
        return java.util.Collections.unmodifiableMap(map);
    }

    private final byte[] content;
    private final int maxItems;
    private final int maxOffset;
    private final List<Map<String, Object>> items = new ArrayList<>();
    private long skipped;

    /**
     * @param content   the stream's bytes — the comment text itself only exists there, so reading it
     *                  is the listing's job and not something the walk carries around
     * @param maxItems  items filled at most
     * @param maxOffset only operations at or after this byte offset (0 = from the start)
     */
    public ListingSink(byte[] content, int maxItems, int maxOffset) {
        this.content = content == null ? new byte[0] : content;
        this.maxItems = Math.max(0, maxItems);
        this.maxOffset = Math.max(0, maxOffset);
    }

    /** Listing from the start, with the default number of items. */
    public ListingSink(byte[] content) {
        this(content, DEFAULT_MAX_ITEMS, 0);
    }

    @Override
    public void onOperation(PdfContentInterpreter.Operation operation) {
        if (operation.offset() < maxOffset) {
            skipped++;
            return;
        }
        if (items.size() >= maxItems) {
            skipped++;
            return;
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("offset", (long) operation.offset());
        item.put("op", operation.name());
        item.put("operandsText", PdfContentStream.formatOperation(operation.operands(),
                operation.name()));
        // The retired key first (same number, one version left), then the name that says count.
        item.put("operands", (long) operation.operandCount());
        item.put("operandCount", (long) operation.operandCount());
        putOperandValues(item, operation.operands());
        if (operation.commentOffset() >= 0) {
            item.put("commentOffset", (long) operation.commentOffset());
            item.put("comment", PdfContentStream.readComment(content, operation.commentOffset()));
        }
        items.add(item);
    }

    /**
     * The operands as values, next to the text that renders them — the part of the listing a caller
     * used to have to parse out of {@code operandsText} by hand.
     *
     * <p>
     * Each value is what the tokenizer handed the walk, in the shape the rest of this module prints
     * a value in: a number is a number, a name is {@code "/Name"}, a literal string is its text, an
     * indirect reference is {@code {ref, objNum, gen}}. What has no shape of its own — an array, a
     * dictionary, the folded {@code BI…ID…EI} value — is printed as PDF source text, because a
     * nested structure inside a listing line is a second answer, not the operand of this one.
     * Nothing here is decoded through a font: {@code pdf.placement()} is the function that reads a
     * string's bytes as characters.
     * </p>
     */
    private static void putOperandValues(Map<String, Object> item, List<Object> operands) {
        int room = Math.min(MAX_OPERAND_VALUES, operands.size());
        List<Object> values = new ArrayList<>(room);
        for (int i = 0; i < room; i++) {
            values.add(operandValue(operands.get(i)));
        }
        item.put("operandValues", values);
        if (values.size() < operands.size()) {
            item.put("operandValuesTruncated", true);
            item.put("operandValuesCount", (long) operands.size());
        }
    }

    /**
     * One operand as an answer rather than as source text (see {@link #putOperandValues}).
     *
     * @param value the operand the tokenizer produced
     * @return the value as it reads in a result
     */
    static Object operandValue(Object value) {
        if (value == null) {
            // The keyword `null`, not a missing operand: the listing counts it as an operand, so the
            // value list shows it too. The text form, because a bare null in a list reads as "lost".
            return "null";
        }
        if (value instanceof Boolean || value instanceof Long || value instanceof Double) {
            return value;
        }
        if (value instanceof PdfName name) {
            return name.toString();
        }
        if (value instanceof PdfString string) {
            // A literal string is the text it decodes to by the document convention; a hexadecimal
            // one is bytes with no text reading owed to anybody, so it keeps its written form.
            return string.isHex() ? "<" + string.hex() + ">" : string.text();
        }
        if (value instanceof PdfReference reference) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("ref", reference.toString());
            view.put("objNum", (long) reference.objNum());
            view.put("gen", (long) reference.gen());
            return view;
        }
        if (value instanceof Number number) {
            return number.toString();
        }
        // Arrays, dictionaries, stream handles, the folded inline image: the source text the listing
        // has printed for them all along.
        return PdfContentStream.formatOperand(value, 0);
    }

    /** The listing itself — one map per operation, in document order. */
    public List<Map<String, Object>> items() {
        return items;
    }

    /** The listing — {@code {items, skipped}}, the shape {@code pdf.operations()} returns. */
    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("skipped", skipped);
        return result;
    }

    /** How many items this listing holds. */
    public int size() {
        return items.size();
    }

    /** How many operators were walked but not listed. */
    public long skipped() {
        return skipped;
    }

    @Override
    public String toString() {
        return items.size() + " operation(s) listed, " + skipped + " skipped";
    }
}
