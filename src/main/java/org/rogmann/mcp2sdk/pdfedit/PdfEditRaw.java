package org.rogmann.mcp2sdk.pdfedit;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNull;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.rogmann.mcp2sdk.pdf.PdfContentStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * The raw operations of {@code pdfedit}: content streams appended as text, the operator surgery
 * ({@code content.cut}, {@code content.insert}) and the object layer ({@code object.set},
 * {@code object.add}, {@code object.delete}, {@code stream.set}).
 * <p>
 * These ops write what the caller wrote - no layout, no interpretation - and they address the
 * document the way {@code pdf} describes it: an object number printed by {@code pdf.objects()},
 * and a value written the way a PDF writes it (a name is {@code '/Type'}, a string is a string,
 * {@code {ref: 12}} is an indirect reference).
 * </p>
 * <p>
 * {@code content.cut} and {@code content.insert} read a content stream with the tokenizer of the
 * reading module ({@link PdfContentStream#tokenize}), so that an offset means here exactly what
 * {@code pdf.operations()} prints there. Doing the same arithmetic twice, slightly differently, is
 * how a splice becomes a document that no viewer can paint.
 * </p>
 */
final class PdfEditRaw {

    private static final Logger LOGGER = LoggerFactory.getLogger(PdfEditRaw.class);

    private PdfEditRaw() {
        // Utility class
    }

    // -------------------------------------------------------------------
    // content.append / content.prepend
    // -------------------------------------------------------------------

    /**
     * Appends (or prepends) a content stream with the given operator text.
     * <p>
     * The text is written as given: no {@code q}/{@code Q} is wrapped around it, because the op
     * says "this text lands in the stream" and a caller who wants isolation writes it. That is
     * also why {@code content.prepend} is dangerous with an unbalanced {@code q}: the original
     * content would run in a changed graphics state.
     * </p>
     * <p>
     * The stream is created and written directly ({@link PDStream}), not through
     * {@link PDPageContentStream}: that class is a writer for <em>operators it builds itself</em>,
     * its raw-command entry point is deprecated, and its constructor also touches the page's
     * {@code /Resources}. What this op promises is "these bytes are the stream" - so the bytes
     * go in and nothing else changes.
     * </p>
     * @param ctx the call context
     * @param op the op
     * @param prepend true for {@code content.prepend}, false for {@code content.append}
     */
    static void contentStream(PdfEditContext ctx, PdfEditOp op, boolean prepend) {
        op.allow("page", "text");
        int pageNum = op.page("page");
        String text = op.string("text");
        byte[] bytes = asciiContent(op, text);
        PDPage page = PdfEditPages.requirePage(ctx, pageNum);
        PDStream stream = new PDStream(ctx.document());
        try (OutputStream out = stream.createOutputStream()) {
            out.write(bytes);
        } catch (IOException e) {
            throw new PdfEditException("broken", "the content stream of page " + pageNum
                    + " could not be written", "pdf.decodeStream() shows the page's streams"
                    + " before the call", e);
        }
        COSDictionary pageDict = page.getCOSObject();
        List<PDStream> streams = new ArrayList<>();
        try {
            Iterator<PDStream> existing = page.getContentStreams();
            while (existing.hasNext()) {
                streams.add(existing.next());
            }
        } catch (RuntimeException e) {
            throw new PdfEditException("broken", op.where() + ": the /Contents of page " + pageNum
                    + " holds something that is not a stream",
                    "pdf.contentStreams()/pdf.object() show what the reader sees, and"
                            + " stream.set can replace a stream by its object number", e);
        }
        if (prepend) {
            streams.add(0, stream);
        } else {
            streams.add(stream);
        }
        page.setContents(streams);
        pageDict.setNeedToBeUpdated(true);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("page", (long) pageNum);
        details.put("objNum", pageObjNum(page));
        details.put("bytes", (long) bytes.length);
        ctx.changed(op, prepend
                ? "content stream prepended (" + bytes.length + " bytes)"
                : "content stream appended (" + bytes.length + " bytes)", details);
        if (prepend) {
            ctx.note("content.prepend: the new stream runs before the page's own streams, so"
                    + " an existing q/Q block changes the state the original content sees");
        }
    }

    /**
     * Validates operator text: a content stream is written in US-ASCII, so anything beyond that
     * would silently become '?' - a wrong text in the file instead of an error.
     * @param op the op (for the message)
     * @param text the text
     * @return the bytes that will be written
     */
    private static byte[] asciiContent(PdfEditOp op, String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean ok = (c >= 0x20 && c <= 0x7e) || c == '\n' || c == '\r' || c == '\t';
            if (!ok) {
                throw PdfEditException.usage(op.where() + ": text contains the character "
                        + (int) c + " (position " + i + ")",
                        "a content stream is ASCII: write the character as a PDF string,"
                                + " e.g. <00FC> for 'ü' inside a Tj operand");
            }
        }
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    // -------------------------------------------------------------------
    // content.cut / content.insert
    // -------------------------------------------------------------------

    /**
     * Removes whole operations from a page's content stream.
     * <p>
     * The offsets are the ones {@code pdf.operations()} prints: the byte offset of an operator in
     * the <em>decoded</em> content - the bytes {@code pdf.decodeStream()} shows, not the
     * compressed ones. The unit of removal is the operation, not the byte range: an operation
     * starts where the previous one ended and ends after its operator, so cutting a {@code Tj}
     * takes its string operand with it. That is exactly what "remove this word" means, and it is
     * why this op exists next to {@code stream.set}: getting those boundaries right is easy to do
     * three times and wrong the fourth.
     * </p>
     * <p>
     * What it refuses: an offset that is not an operator ({@code out-of-range}, with the nearest
     * one named), and a cut that would leave {@code BT}/{@code ET}, {@code q}/{@code Q} or
     * {@code BMC}/{@code EMC} unbalanced ({@code imbalanced}, with the operator that would be left
     * open). The second one is the reason a caller does not have to read the stream: removing a
     * {@code Q} is almost never what somebody meant.
     * </p>
     * @param ctx the call context
     * @param op the op
     */
    static void contentCut(PdfEditContext ctx, PdfEditOp op) {
        op.allow("page", "offsets", "requireBalanced");
        int pageNum = op.page("page");
        List<?> raw = op.list("offsets");
        if (raw.isEmpty()) {
            throw PdfEditException.usage(op.where() + ": 'offsets' is empty",
                    "give the offsets pdf.operations() prints, e.g. offsets:[11706]");
        }
        if (raw.size() > PdfEditLimits.MAX_CUT_OPERATORS) {
            throw new PdfEditException("limit", op.where() + ": " + raw.size() + " offsets",
                    "at most " + PdfEditLimits.MAX_CUT_OPERATORS + " operators per cut");
        }
        boolean requireBalanced = op.bool("requireBalanced", true);
        PDPage page = PdfEditPages.requirePage(ctx, pageNum);
        Content content = read(ctx, page, pageNum, op);
        List<Operation> operations = operations(content.bytes());

        Set<Integer> removed = new LinkedHashSet<>();
        List<Map<String, Object>> cut = new ArrayList<>();
        for (Object value : raw) {
            if (!(value instanceof Number number)) {
                throw PdfEditException.usage(op.where() + ": offsets holds "
                        + PdfEditOp.describe(value),
                        "offsets are whole numbers, as pdf.operations() prints them under"
                                + " 'offset'");
            }
            int offset = number.intValue();
            int index = indexOf(operations, offset);
            if (index < 0) {
                throw new PdfEditException("out-of-range", op.where() + ": offset " + offset
                        + " is not an operator of page " + pageNum,
                        nearest(operations, offset) + " - pdf.operations(page, objNum) lists the"
                        + " operators with their offsets");
            }
            if (!removed.add(index)) {
                throw PdfEditException.usage(op.where() + ": offset " + offset + " is listed twice",
                        "each operator is removed once");
            }
            Operation operation = operations.get(index);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("offset", (long) operation.offset());
            entry.put("op", operation.name());
            entry.put("start", (long) operation.start());
            entry.put("end", (long) operation.end());
            cut.add(entry);
        }
        if (requireBalanced) {
            checkBalanced(operations, removed, op);
        }
        byte[] result = remove(content.bytes(), operations, removed);
        write(ctx, page, pageNum, result, content, op);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("page", (long) pageNum);
        details.put("objNum", pageObjNum(page));
        details.put("cut", cut);
        details.put("bytes", (long) result.length);
        details.put("bytesBefore", (long) content.bytes().length);
        ctx.changed(op, "removed " + cut.size() + " operation(s) from the content of page "
                + pageNum, details);
        ctx.note("content.cut: the operation(s) are gone from the stream, the offsets after them"
                + " shift - pdf.operations() on the result shows where the next operator now"
                + " stands; content.insert takes such an offset (the 'start' field above is where"
                + " the cut left off)");
        if (content.streams() > 1) {
            ctx.note("page " + pageNum + " had " + content.streams() + " content streams; offsets"
                    + " are offsets in their concatenation (that is what pdf.operations() reads)"
                    + " and the result holds one stream");
        }
    }

    /**
     * Inserts operator text at a byte offset of a page's decoded content.
     * <p>
     * The text is written as given - no {@code q}/{@code Q} around it, no resources touched. The
     * offset has to be a position where a token may start: a whitespace position, the start or the
     * end of the stream, or the position a {@code content.cut} left behind. A position inside a
     * token would split an operand - and the test for it is exact: the token sequence of the
     * result has to be the old one plus the inserted one, so a {@code /F1} cut in two or a number
     * split in two is refused instead of written.
     * </p>
     * @param ctx the call context
     * @param op the op
     */
    static void contentInsert(PdfEditContext ctx, PdfEditOp op) {
        op.allow("page", "at", "text");
        int pageNum = op.page("page");
        Integer at = op.intOrNull("at");
        if (at == null) {
            throw PdfEditException.usage(op.where() + ": 'at' is missing",
                    "'at' is a byte offset in the decoded content, as pdf.operations() prints it");
        }
        byte[] text = asciiContent(op, op.string("text"));
        PDPage page = PdfEditPages.requirePage(ctx, pageNum);
        Content content = read(ctx, page, pageNum, op);
        byte[] bytes = content.bytes();
        if (at < 0 || at > bytes.length) {
            throw new PdfEditException("out-of-range", op.where() + ": at is " + at
                    + " and the content of page " + pageNum + " has " + bytes.length + " bytes",
                    "the offset is between 0 and " + bytes.length);
        }
        byte[] inserted = framed(bytes, at, text);
        List<PdfContentStream.Token> before = tokens(bytes);
        byte[] result = new byte[bytes.length + inserted.length];
        System.arraycopy(bytes, 0, result, 0, at);
        System.arraycopy(inserted, 0, result, at, inserted.length);
        System.arraycopy(bytes, at, result, at + inserted.length, bytes.length - at);
        List<String> expected = new ArrayList<>();
        int first = 0;
        for (PdfContentStream.Token token : before) {
            if (token.offset() < at) {
                expected.add(shapeOf(token));
                first++;
            }
        }
        expected.addAll(shapeOf(tokens(inserted)));
        for (PdfContentStream.Token token : before.subList(first, before.size())) {
            expected.add(shapeOf(token));
        }
        if (!expected.equals(shapeOf(tokens(result)))) {
            throw new PdfEditException("out-of-range", op.where() + ": at " + at
                    + " lands inside a token of the content of page " + pageNum,
                    "insert at a position between two tokens (a cut reports it as 'start'), at 0"
                            + " or at the end of the content - pdf.operations() shows the"
                            + " operator offsets");
        }
        write(ctx, page, pageNum, result, content, op);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("page", (long) pageNum);
        details.put("objNum", pageObjNum(page));
        details.put("at", (long) at);
        details.put("text", new String(inserted, StandardCharsets.US_ASCII));
        details.put("bytes", (long) result.length);
        details.put("bytesBefore", (long) bytes.length);
        ctx.changed(op, "operator text inserted at offset " + at + " of page " + pageNum,
                details);
        if (inserted.length != text.length) {
            ctx.note("content.insert added a separating character: without it the text would"
                    + " have merged with the token next to it");
        }
    }

    /** One operation of a content stream: its operator, its offset and the span it occupies. */
    private record Operation(String name, int offset, int start, int end) {
    }

    /** The decoded content of a page and how many streams it came from. */
    private record Content(byte[] bytes, int streams) {
    }

    /**
     * Reads the decoded content of a page the way {@code pdf.pageContent()} does: the streams
     * concatenated with a line ending between them.
     * @param ctx the call context
     * @param page the page
     * @param pageNum the page number
     * @param op the op (for the message)
     * @return the content
     */
    private static Content read(PdfEditContext ctx, PDPage page, int pageNum, PdfEditOp op) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int streams = 0;
        try {
            Iterator<PDStream> iterator = page.getContentStreams();
            while (iterator.hasNext()) {
                PDStream stream = iterator.next();
                byte[] decoded = readAll(stream);
                if (streams > 0 && out.size() > 0) {
                    out.write('\n');
                }
                out.write(decoded);
                streams++;
            }
        } catch (IOException e) {
            throw new PdfEditException("broken", op.where() + ": the content of page " + pageNum
                    + " could not be decoded: " + e.getMessage(),
                    "pdf.decodeStream() shows the streams of the page as the reader sees them", e);
        } catch (RuntimeException e) {
            throw new PdfEditException("broken", op.where() + ": the /Contents of page " + pageNum
                    + " holds something that is not a stream",
                    "pdf.contentStreams()/pdf.object() show what the reader sees, and stream.set"
                            + " can replace a stream by its object number", e);
        }
        return new Content(out.toByteArray(), streams);
    }

    private static byte[] readAll(PDStream stream) throws IOException {
        try (InputStream in = stream.createInputStream()) {
            return in.readAllBytes();
        }
    }

    /**
     * Writes the changed content back as one stream of the page.
     * <p>
     * A single {@code FlateDecode} filter is inherited from the source (a compressed page stays
     * compressed); anything else is written uncompressed and noted, because re-encoding with a
     * filter chain nobody asked for would be a silent change of the file.
     * </p>
     * @param ctx the call context
     * @param page the page
     * @param pageNum the page number
     * @param bytes the new content
     * @param content the content before the change
     * @param op the op
     */
    private static void write(PdfEditContext ctx, PDPage page, int pageNum, byte[] bytes,
                              Content content, PdfEditOp op) {
        boolean flate = content.streams() > 0 && isPlainFlate(page);
        PDStream stream = new PDStream(ctx.document());
        try (OutputStream out = stream.createOutputStream(flate ? COSName.FLATE_DECODE : null)) {
            out.write(bytes);
        } catch (IOException e) {
            throw new PdfEditException("broken", op.where() + ": the content of page " + pageNum
                    + " could not be written", "check the stream filters with pdf.stream()", e);
        }
        page.setContents(List.of(stream));
        page.getCOSObject().setNeedToBeUpdated(true);
    }

    /**
     * Tests whether the first content stream of a page is compressed with FlateDecode alone.
     * @param page the page
     * @return true for a single {@code /FlateDecode} filter
     */
    private static boolean isPlainFlate(PDPage page) {
        Iterator<PDStream> iterator = page.getContentStreams();
        if (!iterator.hasNext()) {
            return false;
        }
        List<COSName> filters = iterator.next().getFilters();
        return filters.size() == 1 && COSName.FLATE_DECODE.equals(filters.get(0));
    }

    /**
     * The operations of a content stream with their spans, from the tokenizer of the reading
     * module - so that an offset means the same here as in {@code pdf.operations()}.
     * @param bytes the decoded content
     * @return the operations in stream order
     */
    private static List<Operation> operations(byte[] bytes) {
        List<PdfContentStream.Token> tokens = tokens(bytes);
        List<Operation> operations = new ArrayList<>();
        int previousEnd = 0;
        for (PdfContentStream.Token token : tokens) {
            if (!token.isOperator()) {
                continue;
            }
            String name = String.valueOf(token.value());
            int end = token.offset() + name.length();
            operations.add(new Operation(name, token.offset(), previousEnd, end));
            previousEnd = end;
        }
        return operations;
    }

    /**
     * Tokenizes a content stream.
     * @param bytes the decoded content
     * @return the tokens
     */
    private static List<PdfContentStream.Token> tokens(byte[] bytes) {
        List<String> notes = new ArrayList<>();
        List<PdfContentStream.Token> tokens = PdfContentStream.tokenize(bytes, notes);
        for (String note : notes) {
            LOGGER.debug("pdfedit: tokenizer: {}", note);
        }
        return tokens;
    }

    /**
     * The token sequence of a content stream as one string per token: its kind and its value.
     * Comparing two of these tells whether an insertion split or merged a token.
     * @param tokens the tokens
     * @return one entry per token
     */
    private static List<String> shapeOf(List<PdfContentStream.Token> tokens) {
        List<String> shape = new ArrayList<>(tokens.size());
        for (PdfContentStream.Token token : tokens) {
            shape.add(shapeOf(token));
        }
        return shape;
    }

    /**
     * One token as a comparable string.
     * @param token the token
     * @return kind and value
     */
    private static String shapeOf(PdfContentStream.Token token) {
        return token.kind() + ":" + token.value();
    }

    /**
     * Adds the separators an inserted text needs to stay a token sequence of its own.
     * @param content the content the text is inserted into
     * @param at the offset
     * @param text the text
     * @return the bytes to insert
     */
    private static byte[] framed(byte[] content, int at, byte[] text) {
        boolean leadingSpace = at > 0 && !isSpace(content[at - 1]);
        boolean trailingSpace = text.length > 0 && !isSpace(text[text.length - 1]);
        int length = text.length + (leadingSpace ? 1 : 0) + (trailingSpace ? 1 : 0);
        byte[] framed = new byte[length];
        int pos = 0;
        if (leadingSpace) {
            framed[pos++] = ' ';
        }
        System.arraycopy(text, 0, framed, pos, text.length);
        pos += text.length;
        if (trailingSpace) {
            framed[pos] = '\n';
        }
        return framed;
    }

    private static boolean isSpace(byte b) {
        return b == ' ' || b == '\n' || b == '\r' || b == '\t' || b == '\f' || b == 0;
    }

    /**
     * Tests whether the operations that remain keep every block operator balanced.
     * @param operations the operations of the stream
     * @param removed the indices that go
     * @param op the op (for the message)
     */
    private static void checkBalanced(List<Operation> operations, Set<Integer> removed,
                                      PdfEditOp op) {
        Deque<Operation> open = new ArrayDeque<>();
        for (int i = 0; i < operations.size(); i++) {
            if (removed.contains(i)) {
                continue;
            }
            Operation operation = operations.get(i);
            switch (operation.name()) {
                case "BT", "q", "BMC" -> open.push(operation);
                case "ET", "Q", "EMC" -> {
                    String expected = switch (operation.name()) {
                        case "ET" -> "BT";
                        case "Q" -> "q";
                        default -> "BMC";
                    };
                    if (open.isEmpty() || !open.peek().name().equals(expected)) {
                        throw imbalanced(op, operation, "closes a block that is not open"
                                + (open.isEmpty() ? "" : " (the innermost open block is "
                                + open.peek().name() + " at " + open.peek().offset() + ")"));
                    }
                    open.pop();
                }
                default -> {
                    // every other operator nests nothing
                }
            }
        }
        if (!open.isEmpty()) {
            throw imbalanced(op, open.peek(), "would stay open at the end of the stream");
        }
    }

    private static PdfEditException imbalanced(PdfEditOp op, Operation operation, String why) {
        return new PdfEditException("imbalanced", op.where() + ": removing these operators"
                + " leaves " + operation.name() + " at offset " + operation.offset() + " that "
                + why,
                "BT/ET, q/Q and BMC/EMC have to stay pairs - cut the matching operator too, or"
                        + " pass requireBalanced:false if the stream is already broken and you"
                        + " know what you are doing");
    }

    /**
     * Removes the spans of the given operations.
     * @param bytes the content
     * @param operations the operations
     * @param removed the indices that go
     * @return the new content
     */
    private static byte[] remove(byte[] bytes, List<Operation> operations, Set<Integer> removed) {
        List<int[]> spans = new ArrayList<>();
        for (int index : removed) {
            Operation operation = operations.get(index);
            spans.add(new int[]{operation.start(), operation.end()});
        }
        spans.sort(Comparator.comparingInt(a -> a[0]));
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length);
        int pos = 0;
        for (int[] span : spans) {
            if (span[0] > pos) {
                out.write(bytes, pos, span[0] - pos);
            }
            pos = Math.max(pos, span[1]);
        }
        if (pos < bytes.length) {
            out.write(bytes, pos, bytes.length - pos);
        }
        return out.toByteArray();
    }

    /**
     * Finds the operation that starts at an offset.
     * @param operations the operations
     * @param offset the offset
     * @return the index, or -1
     */
    private static int indexOf(List<Operation> operations, int offset) {
        for (int i = 0; i < operations.size(); i++) {
            if (operations.get(i).offset() == offset) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Names the operator nearest to an offset - the one a caller probably meant.
     * @param operations the operations
     * @param offset the offset
     * @return a sentence for the hint
     */
    private static String nearest(List<Operation> operations, int offset) {
        Operation nearest = null;
        for (Operation operation : operations) {
            if (nearest == null || Math.abs(operation.offset() - offset)
                    < Math.abs(nearest.offset() - offset)) {
                nearest = operation;
            }
        }
        if (nearest == null) {
            return "the page's content holds no operator at all";
        }
        return "the nearest operator is " + nearest.name() + " at offset " + nearest.offset();
    }

    // -------------------------------------------------------------------
    // object.set / object.add / object.delete
    // -------------------------------------------------------------------

    /**
     * Sets (or removes, with a {@code null} value) entries of an existing indirect object.
     * @param ctx the call context
     * @param op the op
     */
    static void objectSet(PdfEditContext ctx, PdfEditOp op) {
        op.allow("objNum", "value");
        int objNum = objNum(op);
        Map<String, Object> values = op.map("value");
        COSBase base = ctx.object(objNum).getObject();
        if (base == null) {
            throw new PdfEditException("broken", op.where() + ": object " + objNum
                    + " has no value", "pdf.object(" + objNum + ") shows what the reader sees");
        }
        COSDictionary target;
        if (base instanceof COSStream stream) {
            target = stream;
        } else if (base instanceof COSDictionary dict) {
            target = dict;
        } else {
            throw PdfEditException.usage(op.where() + ": object " + objNum + " is "
                    + kindOf(base),
                    "object.set writes dictionary entries - pdf.object(" + objNum
                            + ") shows what the object is");
        }
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            COSName key = name(entry.getKey());
            keys.add('/' + key.getName());
            Object value = entry.getValue();
            if (value == null) {
                target.removeItem(key);
            } else {
                target.setItem(key, toCos(value, ctx, op, "/" + key.getName()));
            }
        }
        target.setNeedToBeUpdated(true);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("objNum", (long) objNum);
        details.put("keys", new ArrayList<Object>(keys));
        ctx.changed(op, "entries set: " + String.join(", ", keys), details);
    }

    /**
     * Creates a new indirect object (a dictionary, or a stream when {@code stream} is given) and
     * remembers it under a {@code $n} handle.
     * @param ctx the call context
     * @param op the op
     */
    static void objectAdd(PdfEditContext ctx, PdfEditOp op) {
        op.allow("value", "as", "stream", "filter");
        String handle = op.stringOrNull("as");
        if (handle == null) {
            int free = 0;
            while (ctx.createdObject("$" + free) != null) {
                free++;
            }
            handle = "$" + free;
        }
        if (!handle.matches("\\$[A-Za-z0-9_]+")) {
            throw PdfEditException.usage(op.where() + ": 'as' is '" + handle + "'",
                    "'as' names the object for the rest of the call: a '$' followed by letters,"
                            + " digits or '_', e.g. '$0' or '$outline'");
        }
        if (ctx.createdObject(handle) != null) {
            throw PdfEditException.usage(op.where() + ": '" + handle + "' was used before",
                    "each object.add needs its own handle, e.g. '$0', '$1', …");
        }
        Map<String, Object> values = op.mapOrNull("value");
        byte[] data = op.params().get("stream") == null ? null : op.bytes("stream");
        String filter = filter(op);
        COSDictionary created;
        if (data != null) {
            if (data.length > PdfEditLimits.MAX_STREAM_BYTES) {
                throw new PdfEditException("limit", op.where() + ": stream payload of "
                        + data.length + " bytes",
                        "at most " + PdfEditLimits.MAX_STREAM_BYTES + " bytes per stream");
            }
            // No try-with-resources around the COSStream itself: closing it would release the
            // buffer the document still has to write, and the stream is not a resource of this
            // call - the document owns it. What is closed is the output stream that writes into it.
            COSStream stream = ctx.document().getDocument().createCOSStream();
            try {
                try (OutputStream out = stream.createOutputStream("flate".equals(filter)
                        ? COSName.FLATE_DECODE : null)) {
                    out.write(data);
                }
            } catch (IOException e) {
                throw new PdfEditException("broken", op.where() + ": the stream payload could not"
                        + " be written", "check the filter name ('flate' or 'none')", e);
            }
            created = stream;
        } else {
            created = new COSDictionary();
        }
        if (values != null) {
            COSDictionary dict = created;
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                dict.setItem(name(entry.getKey()),
                        toCos(entry.getValue(), ctx, op, "/" + entry.getKey()));
            }
        }
        ctx.putCreated(handle, created);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("as", handle);
        details.put("objNum", null);
        details.put("kind", created instanceof COSStream ? "stream" : "dictionary");
        ctx.changed(op, "new indirect object " + handle + " (its object number is assigned when"
                + " the file is written)", details);
        ctx.note("object.add " + handle + ": the object number does not exist before the save -"
                + " reference it as {ref:'" + handle + "'} in this call, and find it afterwards"
                + " with pdf.objects() (a full save numbers objects in write order)");
    }

    /**
     * Sets an object to {@code null} - the number stays taken and a reference to it reads null.
     * @param ctx the call context
     * @param op the op
     */
    static void objectDelete(PdfEditContext ctx, PdfEditOp op) {
        op.allow("objNum");
        int objNum = objNum(op);
        ctx.object(objNum).setToNull();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("objNum", (long) objNum);
        ctx.changed(op, "object set to null", details);
        ctx.note("object.delete " + objNum + ": the object is written as null and keeps its"
                + " number; references to it now read null - a full save drops it when nothing"
                + " points at it any more");
    }

    // -------------------------------------------------------------------
    // stream.set
    // -------------------------------------------------------------------

    /**
     * Replaces the bytes of an existing stream object.
     * @param ctx the call context
     * @param op the op
     */
    static void streamSet(PdfEditContext ctx, PdfEditOp op) {
        op.allow("objNum", "data", "filter");
        int objNum = objNum(op);
        byte[] data = op.bytes("data");
        if (data.length > PdfEditLimits.MAX_STREAM_BYTES) {
            throw new PdfEditException("limit", op.where() + ": " + data.length + " bytes",
                    "at most " + PdfEditLimits.MAX_STREAM_BYTES + " bytes per stream");
        }
        String filter = filter(op);
        COSBase base = ctx.object(objNum).getObject();
        if (base == null) {
            throw new PdfEditException("broken", op.where() + ": object " + objNum
                    + " has no value", "pdf.stream(" + objNum + ") shows what the reader sees");
        }
        if (!(base instanceof COSStream stream)) {
            throw PdfEditException.usage(op.where() + ": object " + objNum + " is " + kindOf(base),
                    "stream.set replaces a stream - pdf.stream(" + objNum
                            + ") shows its decoded bytes");
        }
        stream.removeItem(COSName.FILTER);
        stream.removeItem(COSName.DECODE_PARMS);
        try {
            try (OutputStream out = stream.createOutputStream("flate".equals(filter)
                    ? COSName.FLATE_DECODE : null)) {
                out.write(data);
            }
        } catch (IOException e) {
            throw new PdfEditException("broken", op.where() + ": the stream could not be"
                    + " rewritten", "check the object number and the filter name", e);
        }
        stream.setNeedToBeUpdated(true);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("objNum", (long) objNum);
        details.put("bytes", (long) data.length);
        details.put("filter", filter);
        ctx.changed(op, "stream replaced (" + data.length + " bytes, filter " + filter + ")",
                details);
    }

    // -------------------------------------------------------------------
    // Value conversion
    // -------------------------------------------------------------------

    /**
     * Converts a JavaScript value into a PDF object, the way a PDF writes it.
     * <p>
     * {@code '/Name'} is a name (as {@code pdf} prints names), a plain string is a PDF string,
     * {@code {ref: 12}} and {@code {ref: '$0'}} are indirect references, {@code {hex: '0041'}}
     * is a byte string written as hex, an array is an array and an object is a dictionary.
     * </p>
     * <p>
     * An array and a dictionary written as a value are <em>inline</em>: they become entries of the
     * object they belong to, not objects of their own. Only {@code object.add} creates an indirect
     * object (and {@code {ref: …}} points at one). That is what a producer writes, and a viewer
     * that does not follow such a reference would see nothing (docs/js/pdfedit.md §19.6).
     * </p>
     * @param value the value
     * @param ctx the call context (for {@code $n} handles)
     * @param op the op (for messages)
     * @param path the position in the value, for the error message
     * @return the PDF object
     */
    static COSBase toCos(Object value, PdfEditContext ctx, PdfEditOp op, String path) {
        if (value == null) {
            return COSNull.NULL;
        }
        if (value instanceof Boolean flag) {
            return COSBoolean.getBoolean(flag.booleanValue());
        }
        if (value instanceof Number number) {
            return numberToCos(number);
        }
        if (value instanceof String text) {
            if (text.startsWith("/")) {
                return COSName.getPDFName(text.substring(1));
            }
            return new COSString(text);
        }
        if (value instanceof byte[] data) {
            return new COSString(data);
        }
        if (value instanceof List<?> list) {
            COSArray array = new COSArray();
            for (int i = 0; i < list.size(); i++) {
                array.add(toCos(list.get(i), ctx, op, path + "[" + i + "]"));
            }
            return array;
        }
        if (value instanceof Map<?, ?> map) {
            if (map.size() == 1 && map.containsKey("ref")) {
                return reference(map.get("ref"), ctx, op, path);
            }
            if (map.size() == 1 && map.containsKey("hex")) {
                Object hex = map.get("hex");
                if (!(hex instanceof String text)) {
                    throw PdfEditException.usage(op.where() + ": " + path + ".hex is "
                            + PdfEditOp.describe(hex), "hex is a string of hex digits, e.g. '0041'");
                }
                try {
                    return COSString.parseHex(text);
                } catch (IOException e) {
                    throw PdfEditException.usage(op.where() + ": " + path + ".hex reads '" + text
                            + "'", "hex is an even number of hex digits, e.g. '0041'");
                }
            }
            COSDictionary dict = new COSDictionary();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                dict.setItem(name(key), toCos(entry.getValue(), ctx, op,
                        path + "/" + key));
            }
            // A dictionary written as a value belongs *into* the object it is an entry of. PDFBox
            // writes every COSDictionary that is not marked direct as an object of its own
            // (COSBase.isDirect: "set true if the dictionary should be written as a direct object
            // and not indirect"); a producer writes these dictionaries inline, and a viewer that
            // does not follow such a reference - xpdf does not follow /AP -> /N - shows nothing.
            // Who wants an object of its own uses object.add and {ref: …}.
            dict.setDirect(true);
            return dict;
        }
        throw PdfEditException.usage(op.where() + ": " + path + " is "
                + PdfEditOp.describe(value),
                "a value is a number, a string, a boolean, null, an array, an object,"
                        + " {ref: 12} or {hex: '0041'}");
    }

    /**
     * Resolves {@code {ref: …}}: an object number of this document or a {@code $n} handle of an
     * object created earlier in the same call.
     */
    private static COSBase reference(Object value, PdfEditContext ctx, PdfEditOp op,
                                     String path) {
        if (value instanceof Number number) {
            return ctx.object(number.intValue());
        }
        if (value instanceof String text && text.startsWith("$")) {
            COSBase created = ctx.createdObject(text);
            if (created == null) {
                throw PdfEditException.usage(op.where() + ": " + path + ".ref reads '" + text
                        + "'", "the handles of this call so far are " + ctx.createdHandles()
                        + " - object.add creates one");
            }
            return created;
        }
        throw PdfEditException.usage(op.where() + ": " + path + ".ref is "
                + PdfEditOp.describe(value),
                "ref is an object number (12) or the handle of an object created in this call"
                        + " ('$0')");
    }

    private static COSBase numberToCos(Number number) {
        if (number instanceof Integer || number instanceof Long || number instanceof Short
                || number instanceof Byte) {
            return COSInteger.get(number.longValue());
        }
        float f = number.floatValue();
        if (f == Math.rint(f) && Math.abs(f) < 1e7f) {
            return COSInteger.get(number.longValue());
        }
        return new COSFloat(f);
    }

    /**
     * Reads the {@code filter} parameter of the stream ops: {@code 'none'} (the default) or
     * {@code 'flate'}.
     * @param op the op
     * @return the normalised filter name
     */
    private static String filter(PdfEditOp op) {
        String filter = op.stringOrNull("filter");
        if (filter == null) {
            return "none";
        }
        String value = filter.trim().toLowerCase(Locale.ROOT);
        if (value.equals("none") || value.equals("flate") || value.equals("flatedecode")) {
            return value.startsWith("flate") ? "flate" : "none";
        }
        throw PdfEditException.usage(op.where() + ": filter reads '" + filter + "'",
                "filter is 'none' (the default, bytes as given) or 'flate'");
    }

    /**
     * Turns a dictionary key into a PDF name: {@code "Type"} and {@code "/Type"} both mean
     * {@code /Type}.
     * @param key the key as written in the op
     * @return the name
     */
    static COSName name(String key) {
        return COSName.getPDFName(key.startsWith("/") ? key.substring(1) : key);
    }

    /**
     * Reads the object number of an op that addresses an existing object.
     * @param op the op
     * @return the object number
     */
    private static int objNum(PdfEditOp op) {
        Integer objNum = op.intOrNull("objNum");
        if (objNum == null) {
            throw PdfEditException.usage(op.where() + ": 'objNum' is missing",
                    "'objNum' is an object number as pdf.objects() prints it");
        }
        return objNum;
    }

    /**
     * The object number of a page, or {@code null} when the page dictionary is not indirect.
     * @param page the page
     * @return the number or {@code null}
     */
    static Long pageObjNum(PDPage page) {
        if (page.getCOSObject().getKey() == null) {
            return null;
        }
        return page.getCOSObject().getKey().getNumber();
    }

    /**
     * Names the type of a PDF object for an error message.
     * @param base the object, may be {@code null}
     * @return a short description
     */
    private static String kindOf(COSBase base) {
        if (base == null) {
            return "null";
        }
        if (base instanceof COSStream) {
            return "a stream";
        }
        if (base instanceof COSDictionary) {
            return "a dictionary";
        }
        if (base instanceof COSArray) {
            return "an array";
        }
        return base.getClass().getSimpleName();
    }
}
