package org.rogmann.mcp2sdk.js;

import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.rogmann.mcp2sdk.diff.DiffEngine;
import org.rogmann.mcp2sdk.diff.DiffResult;
import org.rogmann.mcp2sdk.diff.EditScript;
import org.rogmann.mcp2sdk.diff.HunkBuilder;
import org.rogmann.mcp2sdk.diff.HunkFilter;
import org.rogmann.mcp2sdk.diff.LineIndex;
import org.rogmann.mcp2sdk.diff.Prose;
import org.rogmann.mcp2sdk.diff.UnifiedRenderer;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Bridge between GraalVM JavaScript and {@link DiffEngine}: the {@code diff} namespace of the
 * JavaScript tool (specification: docs/js/diff.md).
 *
 * <h3>Usage in JavaScript</h3>
 * <pre>{@code
 * var r = diff.text("a\nb\n", "a\nX\n");
 * console.log(r.stats.changed, r.hunks[0].lines[0].op);
 *
 * var f = diff.files("tmp/old.py", "tmp/new.py", {language: 'python'});
 * console.log(diff.patch("a\nb\n", "a\nX\n", {oldName: 'a/x.txt', newName: 'b/x.txt'}));
 *
 * // the filter runs on the Java side: the file text never becomes a script string
 * var g = diff.files("tmp/old.py", "tmp/new.py", {language: 'python', symbols: ['main']});
 * console.log(g.stats.hunks, g.stats.kept);
 * }</pre>
 *
 * <h3>Why the result is a proxy and not a Java object</h3>
 * <p>
 * A plain Java map or list handed back to JavaScript is an opaque host object: {@code
 * JSON.stringify} renders it as {@code {}} and the caller concludes "broken". Everything returned
 * here goes through {@link GraalProxies}, which converts nested maps and lists into native-looking
 * JavaScript structures.
 * </p>
 *
 * <h3>The engine is the API, this class is one door</h3>
 * <p>
 * A Java caller that already holds the content - a future {@code git} module reading blobs, the
 * review diff, {@code archive} - builds a {@link LineIndex} and calls {@link DiffEngine#diff}
 * directly: no JavaScript, no string copy, no path resolution. This class only adds what a script
 * needs (path rules, proxies, option parsing, help).
 * </p>
 *
 * <h3>Paths and limits</h3>
 * <p>
 * {@code diff.files} uses the same path rules as {@code fs} (project-relative, add-on prefixes, no
 * {@code ..}, no symbolic links leaving the permitted directory) and the same strict decoding:
 * malformed bytes are an error with the byte offset, not a silent U+FFFD. Files above
 * {@value #MAX_FILE_BYTES} bytes per side are refused with the limit in the message.
 * </p>
 */
public class JsDiffBridge implements JsModuleInterface {

    /** Largest file size accepted per side by {@code diff.files}. */
    static final long MAX_FILE_BYTES = 8L * 1024 * 1024;

    /** Number of bytes read per call: the file system module caps a single read. */
    private static final int READ_CHUNK_BYTES = 1024 * 1024;

    /** Number of bytes inspected for a NUL byte before a file is called binary. */
    private static final int BINARY_SNIFF_BYTES = 8 * 1024;

    /** Option names accepted by every function, named in error messages. */
    private static final String VALID_OPTIONS = "algorithm, context, maxCost, maxHunks, maxLines,"
            + " blockMinLines, encoding, errors, language, intraLine, detail, oldName, newName,"
            + " symbol, symbols, minChanged, style, oldId, newId, mode, oldExists, newExists";

    /** The help text returned by {@code diff.help()}. */
    private static final String HELP = """
            diff - structured line diffs (docs/js/diff.md)

            diff.text(a, b[, options])         diff two strings
            diff.files(pathA, pathB[, options]) diff two files (fs path rules, strict decoding,
                                               sha256 fast path, binary detection); takes the
                                               filter options below, so only the selected hunks
                                               are built
            diff.blocks(a, b[, options])       only the segment projection (unchanged/changed)
            diff.filter(a, b[, options])       diff two strings, keep only the hunks that match
                                               symbol/symbols/minChanged
            diff.patch(a, b[, options])        unified diff text ("---"/"+++"/"@@"), patch(1)
                                               shaped; style 'git' adds diff --git/index and
                                               renders a missing side as /dev/null
            diff.help()                        this text

            Result of text()/files():
              { same, binary, approximated, truncated, notes[],
                stats: {added, removed, changed, hunks, kept, blocks, changeSites, rewrapped},
                hunks: [{oldStart, oldLines, newStart, newLines, oldOffset, newOffset,
                         rewrapped, symbol, symbolPath, changes: [{old, new}],
                         lines: [{op: ' '|'-'|'+', text, oldNo, newNo, words?: [{op, text}]}]}],
                blocks: [{kind: 'equal'|'change', oldStart, oldLines, newStart, newLines}],
                symbols: [{name, path, hunks, deleted, inserted}],   (with language)
                files: {old: {...}, new: {...}}      (files() only; a side that does not
                                                     exist is null) }
              each side: {path, id, bytes, lines, sha256, eol, finalNewline, bom}
                         (id: the identity the source uses - a file reports its sha256;
                          lines is -1 for binary content)

            Options:
              algorithm    'histogram' (default), 'minimal' (alias 'myers'), 'patience'
              context      unchanged lines around a change (default 3)
              maxCost      budget in comparison cells (default 200000000); when it is
                           exhausted the region is reported as a replacement, approximated=true
              maxHunks     hunks in the result (default 200), maxLines its lines (default 20000)
              blockMinLines  shorter unchanged runs are absorbed into a change (default 3)
              detail       'full' (default) or 'stats': 'stats' returns the complete statistics
                           but no hunks and no blocks - same numbers, no text
                           (diff.text, diff.filter, diff.files)
              language     'python', 'java', 'c', 'javascript', 'yaml', 'shell' or 'cobol':
                           classifies comments and docstrings, so a change block whose words are
                           equal is reported as rewrapped (a re-wrap, no change sites) and a
                           changed comment block names the words in changes[]. It also estimates
                           the enclosing symbol of every hunk (symbol, symbolPath) by indentation,
                           brace counting or COBOL's Area A - an estimate, not a parser. Code
                           blocks are not word-analysed, blocks above 200 lines neither.
                           Punctuation does not count as a word.
              encoding     charset of files() (default UTF-8), errors 'strict' (default) or 'replace'
              intraLine    'none' (default) or 'word': a word diff per changed line pair, attached
                           as line.words = [{op: ' '|'-'|'+', text}]; 'char' is not implemented yet
              symbol, symbols    diff.filter and diff.files only: keep the hunks inside these
                                 symbols (needs language)
              minChanged   diff.filter and diff.files only: keep hunks with at least N changed
                           lines (a replaced line counts once)
              oldName, newName   diff.patch only: labels of the patch header (default 'a', 'b')
              style        diff.patch only: 'plain' (default, ---/+++) or 'git' (diff --git a/… b/…,
                           index <old>..<new> <mode>, new file mode / deleted file mode)
              oldId, newId diff.patch only: identities for the git index line; the line is
                           omitted when they are unknown, never filled with an invented hash
              mode         diff.patch only: file mode in the git header (default 100644)
              oldExists, newExists   diff.patch only: false renders that side as /dev/null
                           (a new or deleted file) and requires its text to be empty

            Not implemented (deliberately): parsePatch, merge (a three-way merge is a different
            algorithm), diffChars/diffWords (this module diffs lines; word diffs follow).
            """;

    @Override
    public String getNamespace() {
        return "diff";
    }

    @Override
    public String getSummary() {
        return "`diff.help()` explains line diffs (hunks, blocks, unified patch)";
    }

    @Override
    public String getHelpTip() {
        return "diff.help() (hunks, blocks, patch)";
    }

    @Override
    public AutoCloseable wireApi(Value jsBindings) {
        Map<String, Object> methods = new LinkedHashMap<>();
        methods.put("text", (ProxyExecutable) args -> {
            requireArgs(args, 2, "text(a, b[, options])");
            Options options = parseOptions(args, 2, "diff.text");
            HunkBuilder.Sink hunkSink = sink(options, "diff.text");
            DiffResult result = DiffEngine.diff(LineIndex.of(args[0].asString()),
                    LineIndex.of(args[1].asString()), null, null, options.toEngine(), hunkSink);
            return GraalProxies.toProxyObject(toMap(result, options.statsOnly));
        });
        methods.put("files", (ProxyExecutable) args -> {
            requireArgs(args, 2, "files(pathA, pathB[, options])");
            Options options = parseOptions(args, 2, "diff.files");
            // Validated before the first byte is read: a script that filters by symbol without
            // naming the language hears that, not "file not found".
            HunkBuilder.Sink hunkSink = sink(options, "diff.files");
            Read oldRead = read(args[0].asString(), "first");
            Read newRead = read(args[1].asString(), "second");
            if (oldRead.sha256().equals(newRead.sha256())) {
                // The normal case in a change set: most files are untouched. Nothing is decoded.
                return GraalProxies.toProxyObject(identicalResult(oldRead, newRead, options));
            }
            if (oldRead.binary() || newRead.binary()) {
                return GraalProxies.toProxyObject(binaryResult(oldRead, newRead));
            }
            Loaded oldSide = decode(oldRead, options);
            Loaded newSide = decode(newRead, options);
            DiffResult result = DiffEngine.diff(oldSide.index(), newSide.index(),
                    oldSide.source(), newSide.source(), options.toEngine(), hunkSink);
            return GraalProxies.toProxyObject(toMap(result, options.statsOnly));
        });
        methods.put("blocks", (ProxyExecutable) args -> {
            requireArgs(args, 2, "blocks(a, b[, options])");
            Options options = parseOptions(args, 2, "diff.blocks");
            DiffResult result = DiffEngine.diff(args[0].asString(), args[1].asString(),
                    options.toEngine());
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("same", result.stats().added() == 0 && result.stats().removed() == 0
                    && result.stats().changed() == 0);
            map.put("stats", statsMap(result.stats()));
            map.put("blocks", blocksList(result.blocks()));
            map.put("notes", List.copyOf(result.notes()));
            return GraalProxies.toProxyObject(map);
        });
        methods.put("patch", (ProxyExecutable) args -> {
            requireArgs(args, 2, "patch(a, b[, options])");
            Options options = parseOptions(args, 2, "diff.patch");
            String oldText = args[0].asString();
            String newText = args[1].asString();
            if (!options.oldExists() && !oldText.isEmpty()) {
                throw new IllegalArgumentException("diff.patch: oldExists:false renders the old side"
                        + " as /dev/null, so the first text must be empty, but it has "
                        + oldText.length() + " characters");
            }
            if (!options.newExists() && !newText.isEmpty()) {
                throw new IllegalArgumentException("diff.patch: newExists:false renders the new side"
                        + " as /dev/null, so the second text must be empty, but it has "
                        + newText.length() + " characters");
            }
            String oldName = options.oldName() != null ? options.oldName() : "a";
            String newName = options.newName() != null ? options.newName() : "b";
            DiffResult.Source oldSource = options.oldExists() ? null
                    : DiffResult.Source.absent(oldName);
            DiffResult.Source newSource = options.newExists() ? null
                    : DiffResult.Source.absent(newName);
            DiffResult result = DiffEngine.diff(LineIndex.of(oldText), LineIndex.of(newText),
                    oldSource, newSource, options.toEngine(), null);
            return UnifiedRenderer.render(result, oldName, newName, options.style(), options.oldId(),
                    options.newId(), options.mode());
        });
        methods.put("filter", (ProxyExecutable) args -> {
            requireArgs(args, 2, "filter(a, b[, options])");
            Options options = parseOptions(args, 2, "diff.filter");
            HunkBuilder.Sink hunkSink = sink(options, "diff.filter");
            DiffResult result = DiffEngine.diff(LineIndex.of(args[0].asString()),
                    LineIndex.of(args[1].asString()), null, null, options.toEngine(), hunkSink);
            return GraalProxies.toProxyObject(toMap(result, options.statsOnly));
        });
        methods.put("help", (ProxyExecutable) args -> HELP);
        jsBindings.putMember("diff", GraalProxies.toProxyObject(methods));
        return null;
    }

    // ========================================================================
    // Result mapping
    // ========================================================================

    /**
     * Maps a result to the JavaScript shape described in the help text.
     *
     * <p>
     * Package-private on purpose: {@code git.diff} returns the very same shape, so a caller learns
     * it once ({@code diff.*} and {@code git.diff} are two doors to one result).
     * </p>
     *
     * @param result    the result to map
     * @param statsOnly true to leave out the block list ({@code detail:'stats'}): the block count
     *                  stays in {@code stats}, the 172 segment objects do not travel along
     * @return nested map, converted to a proxy by the caller
     */
    static Map<String, Object> toMap(DiffResult result, boolean statsOnly) {
        Map<String, Object> map = new LinkedHashMap<>();
        DiffResult.Stats stats = result.stats();
        map.put("same", stats.added() == 0 && stats.removed() == 0 && stats.changed() == 0);
        map.put("binary", false);
        map.put("approximated", result.approximated());
        map.put("truncated", result.truncated());
        map.put("stats", statsMap(stats));
        map.put("hunks", hunksList(result.hunks()));
        map.put("blocks", statsOnly ? List.of() : blocksList(result.blocks()));
        List<Object> symbols = new ArrayList<>(result.symbols().size());
        for (DiffResult.SymbolSummary summary : result.symbols()) {
            Map<String, Object> symbolMap = new LinkedHashMap<>();
            symbolMap.put("name", summary.name());
            symbolMap.put("path", List.copyOf(summary.path()));
            symbolMap.put("hunks", summary.hunks());
            symbolMap.put("deleted", summary.deleted());
            symbolMap.put("inserted", summary.inserted());
            symbols.add(symbolMap);
        }
        map.put("symbols", symbols);
        map.put("notes", List.copyOf(result.notes()));
        if (result.oldSource() != null || result.newSource() != null) {
            // A side that does not exist is null, not an object with zero bytes: "the file is new"
            // and "the file is empty" are different answers. The note in notes[] names it.
            Map<String, Object> files = new LinkedHashMap<>();
            files.put("old", presentSource(result.oldSource()));
            files.put("new", presentSource(result.newSource()));
            map.put("files", files);
        }
        return map;
    }

    /**
     * @param source one side, may be {@code null} or absent
     * @return the mapped side, or {@code null} when the side does not exist or was not described
     */
    private static Map<String, Object> presentSource(DiffResult.Source source) {
        if (source == null || source.missing()) {
            return null;
        }
        return sourceMap(source);
    }

    private static Map<String, Object> statsMap(DiffResult.Stats stats) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("added", stats.added());
        map.put("removed", stats.removed());
        map.put("changed", stats.changed());
        map.put("hunks", stats.hunks());
        map.put("kept", stats.kept());
        map.put("blocks", stats.blocks());
        map.put("changeSites", stats.changeSites());
        map.put("rewrapped", stats.rewrapped());
        return map;
    }

    private static Map<String, Object> sourceMap(DiffResult.Source source) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("path", source.path());
        map.put("id", source.id());
        map.put("bytes", source.bytes());
        map.put("lines", source.lines());
        map.put("sha256", source.sha256());
        map.put("eol", source.eol());
        map.put("finalNewline", source.finalNewline());
        map.put("bom", source.bom());
        return map;
    }

    private static List<Object> hunksList(List<DiffResult.Hunk> hunks) {
        List<Object> list = new ArrayList<>(hunks.size());
        for (DiffResult.Hunk hunk : hunks) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("oldStart", hunk.oldStart());
            map.put("oldLines", hunk.oldLines());
            map.put("newStart", hunk.newStart());
            map.put("newLines", hunk.newLines());
            map.put("oldOffset", hunk.oldOffset());
            map.put("newOffset", hunk.newOffset());
            map.put("rewrapped", hunk.rewrapped());
            map.put("symbol", hunk.scope() != null ? hunk.scope().name() : null);
            map.put("symbolPath", hunk.scope() != null ? List.copyOf(hunk.scope().path()) : List.of());
            List<Object> changes = new ArrayList<>(hunk.changes().size());
            for (DiffResult.Change change : hunk.changes()) {
                Map<String, Object> changeMap = new LinkedHashMap<>();
                changeMap.put("old", change.oldText());
                changeMap.put("new", change.newText());
                changes.add(changeMap);
            }
            map.put("changes", changes);
            List<Object> lines = new ArrayList<>(hunk.lines().size());
            for (DiffResult.Line line : hunk.lines()) {
                Map<String, Object> lineMap = new LinkedHashMap<>();
                lineMap.put("op", opChar(line.kind()));
                lineMap.put("text", line.text());
                lineMap.put("oldNo", line.oldNumber());
                lineMap.put("newNo", line.newNumber());
                if (line.words() != null) {
                    List<Object> words = new ArrayList<>(line.words().size());
                    for (DiffResult.Word word : line.words()) {
                        Map<String, Object> wordMap = new LinkedHashMap<>();
                        wordMap.put("op", String.valueOf(word.op()));
                        wordMap.put("text", word.text());
                        words.add(wordMap);
                    }
                    lineMap.put("words", words);
                }
                lines.add(lineMap);
            }
            map.put("lines", lines);
            list.add(map);
        }
        return list;
    }

    private static List<Object> blocksList(List<EditScript.Block> blocks) {
        List<Object> list = new ArrayList<>(blocks.size());
        for (EditScript.Block block : blocks) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("kind", block.kind() == EditScript.BlockKind.EQUAL ? "equal" : "change");
            map.put("oldStart", block.oldStart());
            map.put("oldLines", block.oldLines());
            map.put("newStart", block.newStart());
            map.put("newLines", block.newLines());
            list.add(map);
        }
        return list;
    }

    private static String opChar(EditScript.Kind kind) {
        return switch (kind) {
            case EQUAL -> " ";
            case DELETE -> "-";
            case INSERT -> "+";
        };
    }

    // ========================================================================
    // File loading
    // ========================================================================

    /**
     * One side as read from disk: the bytes, their identity and whether they look binary.
     *
     * <p>
     * Reading and decoding are separate steps, because the equality check needs a hash, not a
     * text: "is this file unchanged?" is the normal question in a change set (a pull request
     * touches 5 of 2000 files), and the answer should cost two reads and two hashes - not two
     * decodes and two line indexes.
     * </p>
     *
     * @param path   display path
     * @param data   the bytes
     * @param sha256 hex digest of the bytes: the identity {@code files} reports
     * @param binary true if the content contains a NUL byte in the sniffed prefix
     */
    private record Read(String path, byte[] data, String sha256, boolean binary) {
    }

    /**
     * One side, decoded.
     *
     * @param index  the line index
     * @param source the description for the result
     */
    private record Loaded(LineIndex index, DiffResult.Source source) {
    }

    /**
     * Reads one side. Size first: a file above the limit is refused with the limit in the message,
     * not read first and regretted later.
     */
    private static Read read(String path, String side) {
        long size = JsFileSystem.size(path);
        if (size > MAX_FILE_BYTES) {
            throw new IllegalArgumentException(side + " file '" + path + "' has " + size
                    + " bytes, but diff.files() reads at most " + MAX_FILE_BYTES
                    + " bytes per side; compare a part or use search/fs instead");
        }
        byte[] data = readAll(path, (int) size);
        return new Read(path, data, JsCrypto.sha256(data), isBinary(data));
    }

    /**
     * Decodes one side; malformed bytes stay an error with the byte offset, as in {@code fs}.
     */
    private static Loaded decode(Read input, Options options) {
        Charset charset = JsFileSystem.resolveCharset(options.encoding());
        String text = JsFileSystem.decode(input.data(), charset, options.errors(), input.path());
        LineIndex index = LineIndex.of(text);
        DiffResult.Source side = source(input.path(), input.sha256(), input.data().length,
                index.size(), index.eol(), index.finalNewline(), index.bom());
        return new Loaded(index, side);
    }

    /**
     * Describes a side that exists.
     *
     * <p>
     * The identity is the SHA-256 here, because that is what a file system has; a caller with a
     * better id (git knows the blob id without hashing) passes it to {@link DiffResult.Source}
     * directly. {@code lines} may be {@code -1} when the caller did not decode the content.
     * </p>
     */
    private static DiffResult.Source source(String path, String id, int bytes, int lines,
            String eol, boolean finalNewline, boolean bom) {
        return new DiffResult.Source(path, id, bytes, lines, id, eol, finalNewline, bom, true);
    }

    /** Describes a binary side: the content is not decoded, so it has no line count. */
    private static DiffResult.Source binarySource(Read input) {
        return source(input.path(), input.sha256(), input.data().length, -1, null, false, false);
    }

    /** Reads a file in chunks; the file system module caps a single read. */
    private static byte[] readAll(String path, int size) {
        byte[] data = new byte[size];
        int offset = 0;
        while (offset < size) {
            int chunk = Math.min(READ_CHUNK_BYTES, size - offset);
            byte[] part = JsFileSystem.readBytes(path, offset, chunk);
            if (part.length == 0) {
                break;
            }
            System.arraycopy(part, 0, data, offset, part.length);
            offset += part.length;
        }
        return data;
    }

    /** True if a NUL byte occurs in the sniffed prefix. */
    private static boolean isBinary(byte[] data) {
        int limit = Math.min(data.length, BINARY_SNIFF_BYTES);
        for (int i = 0; i < limit; i++) {
            if (data[i] == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Result of a comparison with binary content: no line diff, but the sizes and hashes.
     *
     * @param oldRead the old side as read
     * @param newRead the new side as read
     * @return the JavaScript result
     */
    private static Map<String, Object> binaryResult(Read oldRead, Read newRead) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("same", false);
        map.put("binary", true);
        map.put("approximated", false);
        map.put("truncated", false);
        map.put("stats", statsMap(new DiffResult.Stats(0, 0, 0, 0, 0, 0, 0, 0)));
        map.put("hunks", null);
        map.put("blocks", List.of());
        map.put("notes", List.of("binary content (NUL byte in the first " + BINARY_SNIFF_BYTES
                + " bytes) - no line diff"));
        Map<String, Object> files = new LinkedHashMap<>();
        files.put("old", sourceMap(binarySource(oldRead)));
        files.put("new", sourceMap(binarySource(newRead)));
        map.put("files", files);
        return map;
    }

    /**
     * Result of a comparison whose two sides have the same SHA-256: identical, no line work.
     *
     * <p>
     * The metadata is content metadata, so it is decoded at most <em>once</em> (the paths differ,
     * the content does not) - and not at all when the content is binary. Binary content with the
     * same hash is {@code same:true}: reporting "different" for identical bytes because they are
     * not text would be the wrong answer to the question that was asked.
     * </p>
     *
     * @param oldRead the old side as read
     * @param newRead the new side as read
     * @param options the parsed options (charset and error policy for the one decode)
     * @return the JavaScript result
     */
    private static Map<String, Object> identicalResult(Read oldRead, Read newRead,
            Options options) {
        boolean binary = oldRead.binary();
        DiffResult.Source oldSource;
        DiffResult.Source newSource;
        if (binary) {
            oldSource = binarySource(oldRead);
            newSource = binarySource(newRead);
        } else {
            DiffResult.Source decoded = decode(oldRead, options).source();
            oldSource = source(oldRead.path(), oldRead.sha256(), oldRead.data().length,
                    decoded.lines(), decoded.eol(), decoded.finalNewline(), decoded.bom());
            newSource = source(newRead.path(), newRead.sha256(), newRead.data().length,
                    decoded.lines(), decoded.eol(), decoded.finalNewline(), decoded.bom());
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("same", true);
        map.put("binary", binary);
        map.put("approximated", false);
        map.put("truncated", false);
        map.put("stats", statsMap(new DiffResult.Stats(0, 0, 0, 0, 0, 0, 0, 0)));
        map.put("hunks", binary ? null : List.of());
        map.put("blocks", List.of());
        map.put("notes", List.of(binary
                ? "both sides are identical (same sha256); binary content, no line diff"
                : "both sides have the same sha256 - no line work was done"));
        Map<String, Object> files = new LinkedHashMap<>();
        files.put("old", sourceMap(oldSource));
        files.put("new", sourceMap(newSource));
        map.put("files", files);
        return map;
    }

    // ========================================================================
    // Option parsing
    // ========================================================================

    /** Options of one call, with the defaults of {@link DiffEngine.Options#defaults()}. */
    private static final class Options {

        private EditScript.Algorithm algorithm = EditScript.Algorithm.HISTOGRAM;
        private int context = DiffEngine.DEFAULT_CONTEXT;
        private long maxCost = DiffEngine.DEFAULT_MAX_COST;
        private int maxHunks = DiffEngine.DEFAULT_MAX_HUNKS;
        private int maxLines = DiffEngine.DEFAULT_MAX_LINES;
        private int blockMinLines = DiffEngine.DEFAULT_BLOCK_MIN_LINES;
        private String encoding;
        private String errors;
        private String oldName;
        private String newName;
        private String language;
        private DiffEngine.IntraLine intraLine = DiffEngine.IntraLine.NONE;
        private final List<String> filterSymbols = new ArrayList<>();
        private int minChanged;
        private boolean statsOnly;
        private UnifiedRenderer.Style style = UnifiedRenderer.Style.PLAIN;
        private String oldId;
        private String newId;
        private String mode;
        private boolean oldExists = true;
        private boolean newExists = true;

        HunkFilter filter() {
            return new HunkFilter(filterSymbols, minChanged);
        }

        String encoding() {
            return encoding;
        }

        String errors() {
            return errors;
        }

        String oldName() {
            return oldName;
        }

        String newName() {
            return newName;
        }

        UnifiedRenderer.Style style() {
            return style;
        }

        String oldId() {
            return oldId;
        }

        String newId() {
            return newId;
        }

        String mode() {
            return mode;
        }

        boolean oldExists() {
            return oldExists;
        }

        boolean newExists() {
            return newExists;
        }

        DiffEngine.Options toEngine() {
            return new DiffEngine.Options(algorithm, context, maxCost, maxHunks, maxLines,
                    blockMinLines, language, intraLine);
        }
    }

    /**
     * Parses the optional options object.
     *
     * @param args     the call arguments
     * @param index    index the options object would be at
     * @param function name of the function, used in error messages
     * @return the options, never {@code null}
     */
    private static Options parseOptions(Value[] args, int index, String function) {
        Options options = new Options();
        if (args.length <= index) {
            return options;
        }
        Value value = args[index];
        if (value == null || value.isNull()) {
            return options;
        }
        if (!value.hasMembers()) {
            throw new IllegalArgumentException(function + ": options must be an object {"
                    + VALID_OPTIONS + "}, but was " + GraalProxies.describeType(value));
        }
        Set<String> keys = value.getMemberKeys();
        for (String key : keys) {
            Value member = value.getMember(key);
            switch (key) {
                case "algorithm" -> options.algorithm = toAlgorithm(stringValue(member, key,
                        function), function);
                case "context" -> options.context = numberValue(member, key, function, 0,
                        Integer.MAX_VALUE);
                case "maxCost" -> options.maxCost = longValue(member, key, function);
                case "maxHunks" -> options.maxHunks = numberValue(member, key, function, 1,
                        Integer.MAX_VALUE);
                case "maxLines" -> options.maxLines = numberValue(member, key, function, 1,
                        Integer.MAX_VALUE);
                case "blockMinLines" -> options.blockMinLines = numberValue(member, key, function,
                        0, Integer.MAX_VALUE);
                case "encoding" -> {
                    requireOption(function, key, "diff.files");
                    options.encoding = stringValue(member, key, function);
                }
                case "errors" -> {
                    requireOption(function, key, "diff.files");
                    options.errors = JsFileSystem.normalizeErrorPolicy(
                            stringValue(member, key, function));
                }
                case "language" -> options.language = language(stringValue(member, key, function),
                        function);
                case "intraLine" -> options.intraLine = intraLine(stringValue(member, key, function),
                        function);
                case "detail" -> {
                    requireOption(function, key, "diff.text", "diff.filter", "diff.files");
                    options.statsOnly = statsOnly(stringValue(member, key, function), function);
                }
                case "symbol" -> {
                    requireOption(function, key, "diff.filter", "diff.files");
                    options.filterSymbols.add(stringValue(member, key, function));
                }
                case "symbols" -> {
                    requireOption(function, key, "diff.filter", "diff.files");
                    if (member == null || member.isNull() || !member.hasArrayElements()) {
                        throw new IllegalArgumentException(function + ": option 'symbols' must be an"
                                + " array of names, but was " + GraalProxies.describeType(member));
                    }
                    for (long i = 0; i < member.getArraySize(); i++) {
                        options.filterSymbols.add(stringValue(member.getArrayElement(i), key,
                                function));
                    }
                }
                case "minChanged" -> {
                    requireOption(function, key, "diff.filter", "diff.files");
                    options.minChanged = numberValue(member, key, function, 1, Integer.MAX_VALUE);
                }
                case "oldName" -> {
                    requireOption(function, key, "diff.patch");
                    options.oldName = stringValue(member, key, function);
                }
                case "newName" -> {
                    requireOption(function, key, "diff.patch");
                    options.newName = stringValue(member, key, function);
                }
                case "style" -> {
                    requireOption(function, key, "diff.patch");
                    options.style = style(stringValue(member, key, function), function);
                }
                case "oldId" -> {
                    requireOption(function, key, "diff.patch");
                    options.oldId = stringValue(member, key, function);
                }
                case "newId" -> {
                    requireOption(function, key, "diff.patch");
                    options.newId = stringValue(member, key, function);
                }
                case "mode" -> {
                    requireOption(function, key, "diff.patch");
                    options.mode = stringValue(member, key, function);
                }
                case "oldExists" -> {
                    requireOption(function, key, "diff.patch");
                    options.oldExists = booleanValue(member, key, function);
                }
                case "newExists" -> {
                    requireOption(function, key, "diff.patch");
                    options.newExists = booleanValue(member, key, function);
                }
                default -> throw new IllegalArgumentException(function + ": unknown option '" + key
                        + "'. Valid options: " + VALID_OPTIONS);
            }
        }
        return options;
    }

    private static String stringValue(Value member, String key, String function) {
        if (member == null || member.isNull() || !member.isString()) {
            throw new IllegalArgumentException(function + ": option '" + key + "' must be a string,"
                    + " but was " + GraalProxies.describeType(member));
        }
        return member.asString();
    }

    private static int numberValue(Value member, String key, String function, long min, long max) {
        if (member == null || member.isNull() || !member.isNumber()) {
            throw new IllegalArgumentException(function + ": option '" + key + "' must be a number,"
                    + " but was " + GraalProxies.describeType(member));
        }
        long value = member.asLong();
        if (value < min || value > max) {
            throw new IllegalArgumentException(function + ": option '" + key + "' must be between "
                    + min + " and " + max + ", but was " + value);
        }
        return (int) value;
    }

    /** Reads a number that may exceed the int range (the cost budget). */
    private static long longValue(Value member, String key, String function) {
        if (member == null || member.isNull() || !member.isNumber()) {
            throw new IllegalArgumentException(function + ": option '" + key + "' must be a number,"
                    + " but was " + GraalProxies.describeType(member));
        }
        long value = member.asLong();
        if (value < 1) {
            throw new IllegalArgumentException(function + ": option '" + key
                    + "' must be positive, but was " + value);
        }
        return value;
    }

    /** Reads a boolean; a string like {@code 'false'} is refused, not read as true. */
    private static boolean booleanValue(Value member, String key, String function) {
        if (member == null || member.isNull() || !member.isBoolean()) {
            throw new IllegalArgumentException(function + ": option '" + key + "' must be true or"
                    + " false, but was " + GraalProxies.describeType(member));
        }
        return member.asBoolean();
    }

    /**
     * Maps the {@code detail} option.
     *
     * @param name     {@code 'full'} or {@code 'stats'}
     * @param function the called function, used in the error message
     * @return true for {@code 'stats'} ("the numbers, not the text")
     */
    private static boolean statsOnly(String name, String function) {
        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "full" -> false;
            case "stats" -> true;
            default -> throw new IllegalArgumentException(function + ": unknown detail '" + name
                    + "'; valid values are 'full' and 'stats'");
        };
    }

    /**
     * Maps the {@code style} option of {@code diff.patch}.
     *
     * @param name     {@code 'plain'} or {@code 'git'}
     * @param function the called function, used in the error message
     * @return the style
     */
    private static UnifiedRenderer.Style style(String name, String function) {
        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "plain" -> UnifiedRenderer.Style.PLAIN;
            case "git" -> UnifiedRenderer.Style.GIT;
            default -> throw new IllegalArgumentException(function + ": unknown style '" + name
                    + "'; valid values are 'plain' and 'git'");
        };
    }

    private static EditScript.Algorithm toAlgorithm(String name, String function) {
        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "histogram" -> EditScript.Algorithm.HISTOGRAM;
            case "minimal", "myers" -> EditScript.Algorithm.MINIMAL;
            case "patience" -> EditScript.Algorithm.PATIENCE;
            default -> throw new IllegalArgumentException(function + ": unknown algorithm '" + name
                    + "'; valid values are 'histogram', 'minimal' (alias 'myers') and 'patience'");
        };
    }

    /** Validates the language name; an unknown one is an error, never a silent "no analysis". */
    private static String language(String name, String function) {
        if (!Prose.isKnownLanguage(name)) {
            throw new IllegalArgumentException(function + ": unknown language '" + name
                    + "'; valid values are " + String.join(", ", Prose.languages()));
        }
        return name.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Builds the hunk sink of a call: the filter, the "numbers only" sink, or none.
     *
     * <p>
     * {@code diff.files} filters like {@code diff.filter}, only without the detour over a
     * JavaScript string. The point is not the input - measured on the 657 KB pair of
     * docs/js/diff.md §4.2, two strings cost 7 ms and two paths 9 ms, so copying the text is
     * cheap. The point is the output: the sink sees every hunk before it is built, so a filtered
     * result carries 3 hunks instead of 172 (131 KB of JSON instead of 432 KB).
     * </p>
     *
     * @param options  the parsed options
     * @param function name of the function, used in error messages
     * @return the sink, or {@code null} when the call asked for no selection
     * @throws IllegalArgumentException if symbols are asked for without a language
     */
    private static HunkBuilder.Sink sink(Options options, String function) {
        if (!options.filterSymbols.isEmpty() && options.language == null) {
            // An empty result would look like "no changes there", so the combination is refused
            // instead of silently keeping nothing.
            throw new IllegalArgumentException(function + ": symbols need a language, because only"
                    + " then a hunk knows its symbol - e.g. {language: 'python', symbols:"
                    + " ['main']}");
        }
        if (options.statsOnly) {
            return HunkBuilder.Sink.NONE;
        }
        HunkFilter filter = options.filter();
        return filter.isActive() ? filter : null;
    }

    /**
     * Refuses an option that belongs to other functions, naming the ones that understand it.
     *
     * <p>
     * An option is not accepted "just in case": a script that passes {@code encoding} to
     * {@code diff.text} has misunderstood something, and a silent no-op would hide it. The
     * message names the functions instead of the option alone.
     * </p>
     *
     * @param function the called function, e.g. {@code diff.text}
     * @param key      the option name
     * @param allowed  the functions that accept it
     */
    private static void requireOption(String function, String key, String... allowed) {
        for (String candidate : allowed) {
            if (candidate.equals(function)) {
                return;
            }
        }
        throw new IllegalArgumentException(function + ": option '" + key + "' only applies to "
                + joinFunctions(allowed));
    }

    /**
     * @param names function names
     * @return "a", "a and b" or "a, b and c"
     */
    private static String joinFunctions(String... names) {
        if (names.length == 1) {
            return names[0];
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < names.length; i++) {
            if (i > 0) {
                text.append(i == names.length - 1 ? " and " : ", ");
            }
            text.append(names[i]);
        }
        return text.toString();
    }

    /** Validates the intra-line granularity. */
    private static DiffEngine.IntraLine intraLine(String name, String function) {        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "none" -> DiffEngine.IntraLine.NONE;
            case "word" -> DiffEngine.IntraLine.WORD;
            case "char" -> throw new IllegalArgumentException(function
                    + ": intraLine 'char' is not implemented yet; use 'word' or 'none'");
            default -> throw new IllegalArgumentException(function + ": unknown intraLine '" + name
                    + "'; valid values are 'none' and 'word'");
        };
    }

    /** Throws a usage error if fewer than {@code min} arguments were passed. */
    private static void requireArgs(Value[] args, int min, String signature) {
        if (args == null || args.length < min) {
            throw new IllegalArgumentException("Usage: diff." + signature + " - see diff.help()");
        }
    }
}
