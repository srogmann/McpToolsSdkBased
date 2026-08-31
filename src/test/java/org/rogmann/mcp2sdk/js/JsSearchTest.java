package org.rogmann.mcp2sdk.js;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests for {@link JsSearch} and {@link JsSearchBridge}, following the acceptance criteria of
 * docs/js/search.md (section 33).
 * <p>
 * Most tests drive the polyglot-free Java API (the line matcher is backed by a real JS
 * {@code RegExp}, so pattern semantics are the ones the JavaScript user gets); three tests run
 * the {@code search} namespace end-to-end inside a GraalVM JavaScript context.
 * </p>
 */
class JsSearchTest {

    @TempDir
    Path tempDir;

    private String oldProjectDir;

    /** Shared JS context: provides RegExp-backed matchers (JS semantics) for the Java tests. */
    private static Context jsContext;
    private static Value regexFactory;

    @BeforeAll
    static void setUpJsContext() {
        jsContext = newTestContext();
        regexFactory = jsContext.eval("js",
                "(function createRegExp(source, flags) { return new RegExp(source, flags); })");
    }

    @AfterAll
    static void tearDownJsContext() {
        if (jsContext != null) {
            jsContext.close();
            jsContext = null;
        }
    }

    @BeforeEach
    void setUpProject() {
        oldProjectDir = System.getProperty("IDE_PROJECT_DIR");
        System.setProperty("IDE_PROJECT_DIR", tempDir.toString());
    }

    @AfterEach
    void tearDownProject() {
        if (oldProjectDir != null) {
            System.setProperty("IDE_PROJECT_DIR", oldProjectDir);
        } else {
            System.clearProperty("IDE_PROJECT_DIR");
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private static Context newTestContext() {
        return Context.newBuilder("js")
                .out(OutputStream.nullOutputStream())
                .err(OutputStream.nullOutputStream())
                .build();
    }

    /**
     * Line matcher using real JavaScript regular expression semantics.
     * <p>
     * It implements {@link JsSearch.LineMatcher#withPatternOptions(String, boolean)} exactly the
     * way {@link JsSearchBridge} does: pattern-level options ({@code flags},
     * {@code caseInsensitive}) belong to the owner of the pattern, and here the owner is this
     * helper (source and flags are known), so the engine can apply them just like in JavaScript.
     * </p>
     */
    private static JsSearch.LineMatcher matcher(String source, String flags) {
        Value regex;
        try {
            regex = regexFactory.execute(source, flags);
        } catch (PolyglotException e) {
            // Same translation the bridge does: a JS syntax error is a user error that mentions
            // that patterns follow JavaScript, not Java, syntax.
            throw new IllegalArgumentException("Invalid regular expression /" + source + "/" + flags
                    + ": " + e.getMessage() + " (patterns use JavaScript, not Java, syntax)");
        }
        return new JsSearch.LineMatcher() {
            @Override
            public boolean test(String line) {
                return regex.invokeMember("test", line).asBoolean();
            }

            @Override
            public JsSearch.LineMatcher withPatternOptions(String extraFlags,
                                                           boolean caseInsensitive) {
                String merged = flags == null ? "" : flags;
                if (extraFlags != null && !extraFlags.isEmpty()) {
                    merged = mergeFlags(merged, extraFlags);
                }
                if (caseInsensitive) {
                    merged = mergeFlags(merged, "i");
                }
                return merged.equals(flags) ? this : matcher(source, merged);
            }
        };
    }

    /** Appends every flag of {@code extra} that {@code flags} does not carry yet. */
    private static String mergeFlags(String flags, String extra) {
        StringBuilder sb = new StringBuilder(flags);
        for (int i = 0; i < extra.length(); i++) {
            char c = extra.charAt(i);
            if (sb.indexOf(String.valueOf(c)) < 0) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static JsSearch.LineMatcher matcher(String source) {
        return matcher(source, "");
    }

    private static Map<String, Object> opts(Object... keysAndValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            map.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> matches(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("matches");
    }

    @SuppressWarnings("unchecked")
    private static List<String> filesOf(Map<String, Object> result) {
        return (List<String>) result.get("files");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> counts(Map<String, Object> result) {
        return (Map<String, Object>) result.get("counts");
    }

    private static long count(Map<String, Object> result, String name) {
        return ((Number) counts(result).get(name)).longValue();
    }

    private static void write(String path, String content) {
        JsFileSystem.writeFile(path, content);
    }

    private static Map<String, byte[]> bytes(Object... namesAndContents) {
        Map<String, byte[]> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < namesAndContents.length; i += 2) {
            Object value = namesAndContents[i + 1];
            byte[] data = value == null ? new byte[0]
                    : value instanceof byte[] raw ? raw
                    : String.valueOf(value).getBytes(StandardCharsets.UTF_8);
            map.put(String.valueOf(namesAndContents[i]), data);
        }
        return map;
    }

    /** Creates a ZIP/JAR/WAR/EAR archive from raw entry bytes (entry order is kept). */
    private void writeZip(String path, Map<String, byte[]> entries) throws IOException {
        Path target = tempDir.resolve(path);
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(target))) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
    }

    private static byte[] zipBytes(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gos = new GZIPOutputStream(bos)) {
            gos.write(data);
        }
        return bos.toByteArray();
    }

    /** Builds a minimal ustar archive (regular files only). */
    private static byte[] tar(Map<String, byte[]> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            byte[] data = e.getValue();
            out.writeBytes(tarHeader(e.getKey(), data.length, '0'));
            out.writeBytes(data);
            out.writeBytes(new byte[(512 - (data.length % 512)) % 512]);
        }
        out.writeBytes(new byte[1024]); // end-of-archive marker
        return out.toByteArray();
    }

    private static byte[] tarHeader(String name, long size, char typeFlag) {
        byte[] h = new byte[512];
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(nameBytes, 0, h, 0, Math.min(nameBytes.length, 100));
        putAscii(h, 100, "0000644\0");
        putAscii(h, 108, "0000000\0");
        putAscii(h, 116, "0000000\0");
        putAscii(h, 124, String.format("%011o\0", size));
        putAscii(h, 136, String.format("%011o\0", 1700000000L));
        for (int i = 148; i < 156; i++) {
            h[i] = ' ';
        }
        h[156] = (byte) typeFlag;
        putAscii(h, 257, "ustar\0");
        putAscii(h, 263, "00");
        long sum = 0;
        for (byte b : h) {
            sum += b & 0xFF;
        }
        putAscii(h, 148, String.format("%06o\0 ", sum));
        return h;
    }

    private static void putAscii(byte[] target, int offset, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, target, offset, bytes.length);
    }

    private static byte[] text(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Snapshot of all paths below the project base (for the "no disk extraction" test). */
    private TreeSet<String> snapshotFiles() throws IOException {
        TreeSet<String> names = new TreeSet<>();
        try (var stream = Files.walk(tempDir)) {
            stream.forEach(p -> names.add(tempDir.relativize(p).toString().replace('\\', '/')));
        }
        return names;
    }

    // ========================================================================
    // 33.1 Module registration (JavaScript end-to-end)
    // ========================================================================

    @Test
    void jsNamespaceIsRegisteredAndHelpMentionsTheApi() {
        try (Context ctx = newTestContext()) {
            new JsSearchBridge().wireApi(ctx.getBindings("js"));
            Value search = ctx.getBindings("js").getMember("search");
            assertTrue(search.hasMembers(), "namespace 'search' must be bound");
            assertTrue(search.hasMember("help") && search.hasMember("grep")
                    && search.hasMember("find") && search.hasMember("files"));
            String help = search.invokeMember("help").asString();
            assertFalse(help.isBlank(), "help must not be empty");
            assertTrue(help.contains("search.grep"), "help mentions search.grep");
            assertTrue(help.contains("search.find"), "help mentions search.find");
            assertTrue(help.contains("search.files"), "help mentions search.files");
        }
    }

    @Test
    void jsEndToEndGrepFindAndFiles() {
        write("e2e.txt", "one\nTODO: fix this\nthree");
        try (Context ctx = newTestContext()) {
            new JsSearchBridge().wireApi(ctx.getBindings("js"));
            assertEquals("e2e.txt:2:TODO: fix this",
                    ctx.eval("js", "search.grep('TODO', 'e2e.txt')").asString());

            // join() renders null members as empty strings, hence the empty slot.
            String structured = ctx.eval("js", "var r = search.find(/todo/i, 'e2e.txt');"
                    + "[r.matches.length, r.matches[0].line, r.matches[0].text, r.files[0],"
                    + " r.counts.filesScanned, r.truncated, r.truncatedReason, r.warnings.length].join('|')")
                    .asString();
            assertEquals("1|2|TODO: fix this|e2e.txt|1|false||0", structured);

            Value files = ctx.eval("js", "search.files('TODO', 'e2e.txt')");
            assertTrue(files.hasArrayElements());
            assertEquals(1, files.getArraySize());
            assertEquals("e2e.txt", files.getArrayElement(0).asString());

            // undefined and null options behave like {}
            assertEquals("e2e.txt:1:one",
                    ctx.eval("js", "search.grep('one', 'e2e.txt', undefined)").asString());
            assertEquals("e2e.txt:3:three",
                    ctx.eval("js", "search.grep('three', 'e2e.txt', null)").asString());
        }
    }

    @Test
    void jsErrorsCarryActionableMessages() {
        write("err.txt", "some text");
        try (Context ctx = newTestContext()) {
            new JsSearchBridge().wireApi(ctx.getBindings("js"));

            PolyglotException unknown = assertThrows(PolyglotException.class,
                    () -> ctx.eval("js", "search.grep('text', 'err.txt', { foo: true })"));
            assertTrue(unknown.getMessage().contains("Unknown search option"), unknown.getMessage());
            assertTrue(unknown.getMessage().contains("foo"), unknown.getMessage());

            PolyglotException badType = assertThrows(PolyglotException.class,
                    () -> ctx.eval("js", "search.grep('text', 'err.txt', 'yes')"));
            assertTrue(badType.getMessage().contains("search options must be an object"), badType.getMessage());

            PolyglotException badRegex = assertThrows(PolyglotException.class,
                    () -> ctx.eval("js", "search.grep('([', 'err.txt')"));
            assertTrue(badRegex.getMessage().contains("Invalid regular expression"), badRegex.getMessage());

            PolyglotException empty = assertThrows(PolyglotException.class,
                    () -> ctx.eval("js", "search.grep('', 'err.txt')"));
            assertTrue(empty.getMessage().contains("pattern must not be empty"), empty.getMessage());

            PolyglotException missing = assertThrows(PolyglotException.class,
                    () -> ctx.eval("js", "search.grep('text', 'nope.txt')"));
            assertTrue(missing.getMessage().contains("File not found"), missing.getMessage());

            PolyglotException noTarget = assertThrows(PolyglotException.class,
                    () -> ctx.eval("js", "search.grep('text')"));
            assertTrue(noTarget.getMessage().contains("Usage: search.grep"), noTarget.getMessage());
        }
    }

    @Test
    void jsRegExpPatternsAndGlobalFlagAreStateless() {
        write("state.txt", "TODO one\nTODO two\nTODO three");
        try (Context ctx = newTestContext()) {
            new JsSearchBridge().wireApi(ctx.getBindings("js"));
            assertEquals(3, ctx.eval("js", "search.find(/TODO/g, 'state.txt').matches.length").asInt());
            assertEquals(3, ctx.eval("js", "search.find(/todo/i, 'state.txt').matches.length").asInt());
            assertEquals(3, ctx.eval("js", "search.find('todo', 'state.txt', { caseInsensitive: true })"
                    + ".matches.length").asInt());
            assertEquals(3, ctx.eval("js", "search.find('TODO', 'state.txt', { flags: 'i' })"
                    + ".matches.length").asInt());
            assertEquals(0, ctx.eval("js", "search.find('todo', 'state.txt').matches.length").asInt());
        }
    }

    // ========================================================================
    // 33.2 - 33.4 Options, patterns, targets
    // ========================================================================

    @Test
    void unknownOptionIsRejectedWithTheOptionList() {
        write("a.txt", "TODO one");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> JsSearch.find(matcher("TODO"), "a.txt", opts("unknown", true)));
        assertTrue(e.getMessage().contains("Unknown search option 'unknown'"), e.getMessage());
        assertTrue(e.getMessage().contains("maxMatches"), "valid options are listed: " + e.getMessage());
    }

    @Test
    void optionValueTypesAreStrict() {
        write("a.txt", "TODO one");
        assertThrows(IllegalArgumentException.class,
                () -> JsSearch.find(matcher("TODO"), "a.txt", opts("recursive", "yes")));
        assertThrows(IllegalArgumentException.class,
                () -> JsSearch.find(matcher("TODO"), "a.txt", opts("before", -1)));
        assertThrows(IllegalArgumentException.class,
                () -> JsSearch.find(matcher("TODO"), "a.txt", opts("exclude", "*.dat")));
        assertThrows(IllegalArgumentException.class,
                () -> JsSearch.find(matcher("TODO"), "a.txt", opts("exclude", List.of(42))));
        assertThrows(IllegalArgumentException.class,
                () -> JsSearch.find(matcher("TODO"), "a.txt", opts("encoding", "ISO-8859-1")));
        assertThrows(IllegalArgumentException.class,
                () -> JsSearch.find(matcher("TODO"), "a.txt", opts("binary", "treatAsText")));
        assertThrows(IllegalArgumentException.class,
                () -> JsSearch.find(matcher("TODO"), "a.txt", opts("flags", "q")));
        assertThrows(IllegalArgumentException.class,
                () -> JsSearch.find(matcher("TODO"), "a.txt", opts("maxArchiveDepth", 0)));
        assertThrows(IllegalArgumentException.class,
                () -> JsSearch.find(matcher("TODO"), "a.txt", opts("recursiveArchives", true)));
        // valid: explicit depth needs archives
        assertNotNull(JsSearch.find(matcher("TODO"), "a.txt",
                opts("maxArchiveDepth", 2, "recursiveArchives", true, "archives", true)));
    }

    @Test
    void nullAndEmptyOptionsAreAccepted() {
        write("a.txt", "TODO one");
        assertEquals(1, matches(JsSearch.find(matcher("TODO"), "a.txt", null)).size());
        assertEquals(1, matches(JsSearch.find(matcher("TODO"), "a.txt", Map.of())).size());
    }

    @Test
    void patternVariantsWork() {
        write("p.txt", "todo lower\nTODO upper\nx");
        assertEquals(1, matches(JsSearch.find(matcher("TODO"), "p.txt")).size());
        assertEquals(2, matches(JsSearch.find(matcher("TODO", "i"), "p.txt")).size());
        assertEquals(2, matches(JsSearch.find(matcher("TODO"), "p.txt", opts("flags", "i"))).size());
        assertEquals(2, matches(JsSearch.find(matcher("TODO"), "p.txt",
                opts("caseInsensitive", true))).size());
        assertEquals(1, matches(JsSearch.find(matcher("TODO"), "p.txt",
                opts("caseInsensitive", false))).size(), "caseInsensitive: false does not force -i");
        // Java-only syntax must be rejected (JavaScript semantics, not Java's). Note that \h,
        // [[:alpha:]] and \p{Alpha} are legal JavaScript (Annex B / identity escapes), so they
        // cannot serve as "Java syntax"; (?i) is a Java flag group and a JavaScript SyntaxError.
        IllegalArgumentException jsSyntax = assertThrows(IllegalArgumentException.class,
                () -> matcher("(?i)TODO"));
        assertTrue(jsSyntax.getMessage().contains("Invalid regular expression"),
                jsSyntax.getMessage());
    }

    @Test
    void modeIsCheckedPerEntryPoint() {
        write("m.txt", "TODO");
        assertThrows(IllegalArgumentException.class,
                () -> JsSearch.find(matcher("TODO"), "m.txt", opts("mode", "content")));
        assertThrows(IllegalArgumentException.class,
                () -> JsSearch.grep(matcher("TODO"), "m.txt", opts("mode", "structured")));
        assertThrows(IllegalArgumentException.class,
                () -> JsSearch.files(matcher("TODO"), "m.txt", opts("mode", "content")));
        assertEquals("m.txt:1:TODO", JsSearch.grep(matcher("TODO"), "m.txt", opts("mode", "content")));
        assertEquals("m.txt", JsSearch.grep(matcher("TODO"), "m.txt", opts("mode", "filesWithMatches")));
        assertEquals(List.of("m.txt"), JsSearch.files(matcher("TODO"), "m.txt",
                opts("mode", "filesWithMatches")));
    }

    @Test
    void targetValidation() throws IOException {
        write("t.txt", "TODO");
        Files.createDirectories(tempDir.resolve("d"));
        write("d/inner.txt", "TODO");
        writeZip("arc.zip", bytes("a.txt", "TODO"));

        assertThrows(IllegalArgumentException.class, () -> JsSearch.find(matcher("TODO"), (Object) null));
        assertThrows(JsUserRuntimeException.class, () -> JsSearch.find(matcher("TODO"), "missing.txt"));
        assertThrows(JsUserRuntimeException.class,
                () -> JsSearch.find(matcher("TODO"), List.of("t.txt", "missing.txt")));
        IllegalArgumentException dir = assertThrows(IllegalArgumentException.class,
                () -> JsSearch.find(matcher("TODO"), "d"));
        assertTrue(dir.getMessage().contains("Cannot search directory without recursive: true"),
                dir.getMessage());
        IllegalArgumentException archive = assertThrows(IllegalArgumentException.class,
                () -> JsSearch.find(matcher("TODO"), "arc.zip"));
        assertTrue(archive.getMessage().contains("Cannot search archive without archives: true"),
                archive.getMessage());

        assertEquals(1, matches(JsSearch.find(matcher("TODO"), Map.of("path", "t.txt"))).size());
        assertThrows(IllegalArgumentException.class,
                () -> JsSearch.find(matcher("TODO"), Map.of("entry", "x.txt")));
        assertThrows(IllegalArgumentException.class,
                () -> JsSearch.find(matcher("TODO"), Map.of("path", "t.txt", "entry", 5)));
        assertThrows(IllegalArgumentException.class,
                () -> JsSearch.find(matcher("TODO"), Map.of("path", "t.txt", "archiveChain", "x")));
        assertThrows(JsUserRuntimeException.class,
                () -> JsSearch.find(matcher("TODO"), "../outside.txt"));
    }

    // ========================================================================
    // 33.5 / 33.6 Plain and recursive search
    // ========================================================================

    @Test
    void plainFileSearchResultShape() {
        write("file.txt", "line 1\nTODO: fix one\nline 3");
        Map<String, Object> result = JsSearch.find(matcher("TODO"), "file.txt");
        List<Map<String, Object>> ms = matches(result);
        assertEquals(1, ms.size());
        assertEquals(2, ms.get(0).get("line"));
        assertEquals("TODO: fix one", ms.get(0).get("text"));
        assertEquals("file.txt", ms.get(0).get("displayPath"));
        assertTrue(((List<?>) ms.get(0).get("before")).isEmpty());
        assertTrue(((List<?>) ms.get(0).get("after")).isEmpty());
        assertEquals(List.of("file.txt"), filesOf(result));
        assertEquals(List.of("file.txt"), JsSearch.files(matcher("TODO"), "file.txt"));
        assertEquals("file.txt:2:TODO: fix one", JsSearch.grep(matcher("TODO"), "file.txt"));

        assertEquals(1, count(result, "filesScanned"));
        assertEquals(1, count(result, "filesMatched"));
        assertEquals(1, count(result, "matches"));
        assertEquals(0, count(result, "errors"));
        assertFalse((Boolean) result.get("truncated"));
        assertNull(result.get("truncatedReason"));
    }

    @Test
    void lineSeparatorsAndMissingTrailingNewline() {
        write("crlf.txt", "a\r\nTODO two\r\n");
        write("cr.txt", "a\rTODO three\r");
        write("nonl.txt", "a\nTODO four");
        write("empty.txt", "");
        assertEquals(2, matches(JsSearch.find(matcher("TODO"), List.of("crlf.txt", "cr.txt"))).size());
        assertEquals(2, matches(JsSearch.find(matcher("TODO"), "nonl.txt")).get(0).get("line"));
        assertTrue(matches(JsSearch.find(matcher("TODO"), "empty.txt")).isEmpty());
        // A trailing separator must not produce an extra (empty) line.
        write("trail.txt", "TODO x\n");
        assertEquals(1, matches(JsSearch.find(matcher("TODO"), "trail.txt")).size());
    }

    @Test
    void recursiveSearchOnlyVisitsTheTargetDirectory() {
        write("src/a.txt", "TODO one");
        write("src/sub/b.txt", "TODO two");
        write("other/c.txt", "TODO three");

        assertEquals(List.of("src/a.txt", "src/sub/b.txt"),
                filesOf(JsSearch.find(matcher("TODO"), "src", opts("recursive", true))));

        List<String> all = filesOf(JsSearch.find(matcher("TODO"), ".", opts("recursive", true)));
        assertTrue(all.containsAll(List.of("src/a.txt", "src/sub/b.txt", "other/c.txt")), all.toString());
    }

    @Test
    void deterministicSortingByCodePoint() {
        write("b.txt", "x");
        write("a.txt", "x");
        write("C.txt", "x");
        assertEquals(List.of("C.txt", "a.txt", "b.txt"),
                JsSearch.files(matcher("x"), ".", opts("recursive", true)));
        assertEquals("C.txt:1:x\na.txt:1:x\nb.txt:1:x",
                JsSearch.grep(matcher("x"), ".", opts("recursive", true)));
    }

    // ========================================================================
    // 33.7 Output formatting
    // ========================================================================

    @Test
    void contentOutputPrefixes() {
        write("a.txt", "TODO one");
        write("b.txt", "TODO two");
        assertEquals("a.txt:1:TODO one\nb.txt:1:TODO two",
                JsSearch.grep(matcher("TODO"), ".", opts("recursive", true)));
        assertEquals("1:TODO one\n1:TODO two",
                JsSearch.grep(matcher("TODO"), ".", opts("recursive", true, "filename", false)));
        assertEquals("a.txt:TODO one\nb.txt:TODO two",
                JsSearch.grep(matcher("TODO"), ".", opts("recursive", true, "lineNumbers", false)));
        assertEquals("TODO one\nTODO two",
                JsSearch.grep(matcher("TODO"), ".",
                        opts("recursive", true, "filename", false, "lineNumbers", false)));
    }

    @Test
    void filesWithMatchesModeHasNoPrefixes() {
        write("a.txt", "TODO one\nTODO two");
        write("b.txt", "TODO three");
        write("c.txt", "nothing");
        assertEquals("a.txt\nb.txt", JsSearch.grep(matcher("TODO"), ".",
                opts("recursive", true, "mode", "filesWithMatches", "before", 2, "after", 2)));
        assertEquals(List.of("a.txt", "b.txt"),
                JsSearch.files(matcher("TODO"), ".", opts("recursive", true)));
    }

    // ========================================================================
    // 33.8 - 33.11 Context
    // ========================================================================

    @Test
    void contextInTextAndStructuredOutput() {
        write("ctx.txt", "before1\nbefore2\nmatch\nafter1\nafter2");
        Map<String, Object> options = opts("before", 2, "after", 2);
        assertEquals("ctx.txt-1-before1\nctx.txt-2-before2\nctx.txt:3:match\nctx.txt-4-after1\nctx.txt-5-after2",
                JsSearch.grep(matcher("match"), "ctx.txt", options));

        List<Map<String, Object>> ms = matches(JsSearch.find(matcher("match"), "ctx.txt", options));
        assertEquals(2, ((List<?>) ms.get(0).get("before")).size());
        assertEquals(2, ((List<?>) ms.get(0).get("after")).size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> before = (List<Map<String, Object>>) ms.get(0).get("before");
        assertEquals(1, before.get(0).get("line"));
        assertEquals("before1", before.get(0).get("text"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> after = (List<Map<String, Object>>) ms.get(0).get("after");
        assertEquals(4, after.get(0).get("line"));
        assertEquals("after1", after.get(0).get("text"));
    }

    @Test
    void contextAliases() {
        write("alias.txt", "1\n2\nmatch\n4\n5");
        String expected = "alias.txt-1-1\nalias.txt-2-2\nalias.txt:3:match\nalias.txt-4-4\nalias.txt-5-5";
        assertEquals(expected, JsSearch.grep(matcher("match"), "alias.txt", opts("before", 2, "after", 2)));
        assertEquals(expected, JsSearch.grep(matcher("match"), "alias.txt", opts("B", 2, "A", 2)));
        assertEquals(expected, JsSearch.grep(matcher("match"), "alias.txt", opts("context", 2)));
        assertEquals(expected, JsSearch.grep(matcher("match"), "alias.txt", opts("C", 2)));
        assertEquals(expected, JsSearch.grep(matcher("match"), "alias.txt",
                opts("before", 2, "B", 2, "after", 2, "A", 2)));
        IllegalArgumentException conflict = assertThrows(IllegalArgumentException.class,
                () -> JsSearch.grep(matcher("match"), "alias.txt", opts("before", 2, "B", 3)));
        assertTrue(conflict.getMessage().contains("Conflicting options: 'before' and 'B'"), conflict.getMessage());
    }

    @Test
    void overlappingContextIsEmittedOnce() {
        write("over.txt", "a\nb\nmatch1\ncontext\nmatch2\nc");
        assertEquals("over.txt-1-a\nover.txt-2-b\nover.txt:3:match1\nover.txt-4-context\nover.txt:5:match2"
                        + "\nover.txt-6-c",
                JsSearch.grep(matcher("match"), "over.txt", opts("before", 2, "after", 2)));
    }

    @Test
    void separatorBetweenNonContiguousGroups() {
        StringBuilder sb = new StringBuilder("match1\ncontext\n");
        for (int i = 3; i < 100; i++) {
            sb.append("filler ").append(i).append('\n');
        }
        sb.append("match100\n");
        write("far.txt", sb.toString());

        // Two groups (with context 2): lines 1-3 and 98-100, separated by a '--' line.
        String[] lines = JsSearch.grep(matcher("match"), "far.txt", opts("context", 2)).split("\n");
        assertEquals("far.txt:1:match1", lines[0]);
        assertEquals("far.txt-2-context", lines[1]);
        assertEquals("far.txt-3-filler 3", lines[2]);
        assertEquals("--", lines[3]);
        assertEquals("far.txt-98-filler 98", lines[4]);
        assertEquals("far.txt-99-filler 99", lines[5]);
        assertEquals("far.txt:100:match100", lines[6]);
        // Without context no separator is emitted (grep behaviour).
        assertFalse(JsSearch.grep(matcher("match"), "far.txt").contains("--"));
    }

    // ========================================================================
    // 33.13 - 33.17 Include / exclude
    // ========================================================================

    @Test
    void excludeByBasenameGlob() {
        write("data/a.dat", "TODO");
        write("data/b.txt", "TODO");
        Map<String, Object> result = JsSearch.find(matcher("TODO"), ".",
                opts("recursive", true, "exclude", List.of("*.dat")));
        assertEquals(List.of("data/b.txt"), filesOf(result));
        assertEquals(1, count(result, "excluded"));
    }

    @Test
    void excludeByPathGlob() {
        write("src/generated/Foo.java", "TODO");
        write("src/main/Bar.java", "TODO");
        assertEquals(List.of("src/main/Bar.java"), filesOf(JsSearch.find(matcher("TODO"), ".",
                opts("recursive", true, "exclude", List.of("src/generated/**")))));
    }

    @Test
    void excludeByRegExpFilter() {
        write("data/a.dat", "TODO");
        write("data/b.txt", "TODO");
        assertEquals(List.of("data/b.txt"), filesOf(JsSearch.find(matcher("TODO"), ".",
                opts("recursive", true, "exclude",
                        List.of((JsSearch.PathFilter) p -> p.endsWith(".dat"))))));
    }

    @Test
    void includeFiltersAndExcludeWins() {
        write("src/a.java", "TODO");
        write("src/skip.java", "TODO");
        write("src/b.txt", "TODO");
        // include only filters, it does not order or pick: both .java files match the glob,
        // b.txt is filtered out (spec 33.16 has no skip.java, this fixture adds it for 33.17).
        assertEquals(List.of("src/a.java", "src/skip.java"), filesOf(JsSearch.find(matcher("TODO"), ".",
                opts("recursive", true, "include", List.of("*.java")))));
        assertEquals(List.of("src/a.java"), filesOf(JsSearch.find(matcher("TODO"), ".",
                opts("recursive", true, "include", List.of("*.java"),
                        "exclude", List.of("skip.java")))));
    }

    // ========================================================================
    // 33.18 - 33.25 ZIP / WAR / EAR
    // ========================================================================

    @Test
    void zipArchiveSearch() throws IOException {
        writeZip("archive.zip", bytes("hello.txt", "TODO zip"));
        Map<String, Object> result = JsSearch.find(matcher("TODO"), "archive.zip", opts("archives", true));
        assertEquals(List.of("archive.zip#hello.txt"), filesOf(result));
        assertEquals("archive.zip#hello.txt:1:TODO zip",
                JsSearch.grep(matcher("TODO"), "archive.zip", opts("archives", true)));
        assertEquals(1, count(result, "archivesOpened"));
        assertEquals(1, count(result, "archiveEntriesScanned"));
        assertThrows(IllegalArgumentException.class, () -> JsSearch.find(matcher("TODO"), "archive.zip"));
    }

    @Test
    void zipDirectoryEntriesAreSkipped() throws IOException {
        writeZip("dir.zip", bytes("dir/", null, "dir/file.txt", "TODO"));
        Map<String, Object> result = JsSearch.find(matcher("TODO"), "dir.zip", opts("archives", true));
        assertEquals(List.of("dir.zip#dir/file.txt"), filesOf(result));
        assertEquals(1, count(result, "archiveEntriesScanned"));
    }

    @Test
    void nestedWarInsideEar() throws IOException {
        byte[] war = zipBytes(bytes("WEB-INF/web.xml", "<servlet-class>X</servlet-class>"));
        writeZip("example.ear", bytes("ExampleWebApplication.war", war));

        String display = "example.ear#ExampleWebApplication.war#WEB-INF/web.xml";
        assertEquals(List.of(display), filesOf(JsSearch.find(matcher("servlet"), "example.ear",
                opts("archives", true, "recursiveArchives", true))));

        // Without recursiveArchives the nested archive stays closed.
        Map<String, Object> flat = JsSearch.find(matcher("servlet"), "example.ear", opts("archives", true));
        assertTrue(filesOf(flat).isEmpty(), "nested archive must not be searched: " + filesOf(flat));
        assertTrue(count(flat, "skipped") >= 1);
    }

    @Test
    void nestedZipKeepsTheOuterArchiveInSync() throws IOException {
        // docs/js/search.md section 38.4 gap A: a nested archive is opened directly on the
        // entry view of the outer ZipInputStream. The entries written *after* the nested
        // archive have to be found as well (they would be lost if the outer stream position
        // ever crossed an entry boundary).
        byte[] inner = zipBytes(bytes("inner/deep.txt", "TODO deep"));
        writeZip("sync.zip", bytes(
                "before.txt", "TODO before",
                "nested.zip", inner,
                "after1.txt", "TODO after one",
                "after2.txt", "TODO after two"));

        assertEquals(List.of("sync.zip#after1.txt", "sync.zip#after2.txt",
                        "sync.zip#before.txt", "sync.zip#nested.zip#inner/deep.txt"),
                filesOf(JsSearch.find(matcher("TODO"), "sync.zip",
                        opts("archives", true, "recursiveArchives", true))));
    }

    @Test
    void archiveTargetByStringAndObject() throws IOException {
        byte[] war = zipBytes(bytes("WEB-INF/web.xml", "<servlet-class>X</servlet-class>"));
        writeZip("chain.ear", bytes("admin.war", war));
        String display = "chain.ear#admin.war#WEB-INF/web.xml";
        Map<String, Object> options = opts("archives", true, "recursiveArchives", true);

        assertEquals(display + ":1:<servlet-class>X</servlet-class>",
                JsSearch.grep(matcher("servlet"), display, options));

        Map<String, Object> objectTarget = new LinkedHashMap<>();
        objectTarget.put("path", "chain.ear");
        objectTarget.put("archiveChain", List.of("admin.war"));
        objectTarget.put("entry", "WEB-INF/web.xml");
        assertEquals(List.of(display), filesOf(JsSearch.find(matcher("servlet"), objectTarget, options)));

        assertThrows(JsUserRuntimeException.class, () -> JsSearch.find(matcher("servlet"),
                "chain.ear#admin.war#WEB-INF/missing.xml", options));
        assertThrows(JsUserRuntimeException.class, () -> JsSearch.find(matcher("servlet"),
                "missing.ear#x", options));

        // Entry names containing '#' work through the object target and the escaped display path.
        writeZip("hash.zip", bytes("foo#bar.txt", "TODO hash"));
        Map<String, Object> hashTarget = new LinkedHashMap<>();
        hashTarget.put("path", "hash.zip");
        hashTarget.put("entry", "foo#bar.txt");
        assertEquals(List.of("hash.zip#foo%23bar.txt"),
                filesOf(JsSearch.find(matcher("TODO"), hashTarget, opts("archives", true))));
        assertEquals(List.of("hash.zip#foo%23bar.txt"),
                filesOf(JsSearch.find(matcher("TODO"), "hash.zip#foo%23bar.txt", opts("archives", true))));
    }

    @Test
    void excludeInsideArchives() throws IOException {
        writeZip("excl.zip", bytes("data/keep.txt", "TODO", "data/skip.dat", "TODO"));
        assertEquals(List.of("excl.zip#data/keep.txt"), filesOf(JsSearch.find(matcher("TODO"), "excl.zip",
                opts("archives", true, "exclude", List.of("*.dat")))));

        writeZip("excl.war", bytes("WEB-INF/skip.xml", "TODO", "WEB-INF/keep.properties", "TODO"));
        assertEquals(List.of("excl.war#WEB-INF/keep.properties"),
                filesOf(JsSearch.find(matcher("TODO"), "excl.war",
                        opts("archives", true, "exclude", List.of("WEB-INF/*.xml")))));
    }

    @Test
    void excludedArchiveIsNotOpenedAtAll() throws IOException {
        writeZip("outer.zip", bytes("inner.txt", "TODO"));
        writeZip("plain.zip", bytes("other.txt", "TODO"));
        write("plain.txt", "TODO");
        assertEquals(List.of("plain.txt"), JsSearch.files(matcher("TODO"), ".",
                opts("recursive", true, "archives", true, "exclude", List.of("*.zip"))));
    }

    // ========================================================================
    // 33.26 - 33.30 tar / tar.gz / tgz / gz
    // ========================================================================

    @Test
    void tarAndGzipArchives() throws IOException {
        Files.write(tempDir.resolve("archive.tar"), tar(bytes("hello.txt", "TODO tar")));
        assertEquals(List.of("archive.tar#hello.txt"),
                filesOf(JsSearch.find(matcher("TODO"), "archive.tar", opts("archives", true))));

        Files.write(tempDir.resolve("archive.tar.gz"), gzip(tar(bytes("hello.txt", "TODO targz"))));
        assertEquals(List.of("archive.tar.gz#hello.txt"),
                filesOf(JsSearch.find(matcher("TODO"), "archive.tar.gz", opts("archives", true))));

        Files.write(tempDir.resolve("archive.tgz"), gzip(tar(bytes("hello.txt", "TODO tgz"))));
        assertEquals(List.of("archive.tgz#hello.txt"),
                filesOf(JsSearch.find(matcher("TODO"), "archive.tgz", opts("archives", true))));

        // A plain .gz is searched as text; the display path gets no '#' suffix.
        Files.write(tempDir.resolve("log.txt.gz"), gzip(text("start\nTODO gz\nend")));
        Map<String, Object> gz = JsSearch.find(matcher("TODO"), "log.txt.gz", opts("archives", true));
        assertEquals(List.of("log.txt.gz"), filesOf(gz));
        assertEquals(2, matches(gz).get(0).get("line"));
    }

    @Test
    void nestedArchiveInsideTarGz() throws IOException {
        byte[] zip = zipBytes(bytes("secret.txt", "TODO nested"));
        Files.write(tempDir.resolve("outer.tar.gz"), gzip(tar(bytes("inner.zip", zip))));

        assertEquals(List.of("outer.tar.gz#inner.zip#secret.txt"),
                filesOf(JsSearch.find(matcher("TODO"), "outer.tar.gz",
                        opts("archives", true, "recursiveArchives", true))));
        assertTrue(filesOf(JsSearch.find(matcher("TODO"), "outer.tar.gz", opts("archives", true))).isEmpty(),
                "without recursiveArchives the nested zip must stay closed");
    }

    /**
     * Searches a <em>real</em> GNU {@code tar.gz} (created with {@code tar cvvfz}, stored as
     * {@code src/test/resources/js/sample.tar.gz}) instead of a hand-built one: checks header
     * parsing, padding, the trailing zero blocks of a real archive and that a binary entry
     * inside an archive is skipped instead of being searched as text.
     */
    @Test
    void realGnuTarGzFixture() throws IOException {
        copyRealFixture();

        String display = "sample.tar.gz#src/main/java/org/rogmann/mcp2sdk/js/"
                + "JsUserRuntimeException.java";
        Map<String, Object> result = JsSearch.find(matcher("class JsUserRuntimeException"),
                "sample.tar.gz", opts("archives", true));
        assertEquals(List.of(display), filesOf(result));
        assertEquals(1, matches(result).size());
        assertTrue(String.valueOf(matches(result).get(0).get("text"))
                        .contains("class JsUserRuntimeException"),
                String.valueOf(matches(result).get(0)));
        // entry 1 of the fixture is a .class file: binary, therefore skipped and never matched
        assertEquals(1, count(result, "filesScanned"));
        assertEquals(1, count(result, "archiveEntriesScanned"));
        assertEquals(1, count(result, "skipped"));
        assertEquals(0, count(result, "errors"));
        assertEquals(0, ((List<?>) result.get("warnings")).size());

        String out = JsSearch.grep(matcher("class JsUserRuntimeException"), "sample.tar.gz",
                opts("archives", true));
        assertTrue(out.startsWith(display + ":"), out);
    }

    /**
     * Copies the real GNU {@code tar cvvfz} fixture into the temporary project base.
     * @return the copied file
     */
    private Path copyRealFixture() throws IOException {
        URL fixture = JsSearchTest.class.getResource("/js/sample.tar.gz");
        assumeTrue(fixture != null,
                "test resource src/test/resources/js/sample.tar.gz is missing");
        Path target = tempDir.resolve("sample.tar.gz");
        try (InputStream in = fixture.openStream()) {
            Files.copy(in, target);
        }
        return target;
    }

    /**
     * Explicit {@code #} targets on the real fixture: the addressed entry is searched on its
     * own, an explicitly named binary entry is a hard error (section 30.1) and the
     * {@code archives} requirement applies to entry targets as well.
     */
    @Test
    void realGnuTarGzFixtureEntryTargets() throws IOException {
        copyRealFixture();
        String javaEntry = "sample.tar.gz#src/main/java/org/rogmann/mcp2sdk/js/"
                + "JsUserRuntimeException.java";
        String classEntry = "sample.tar.gz#target/classes/org/rogmann/mcp2sdk/js/"
                + "JsUserRuntimeException.class";

        assertTrue(JsSearch.grep(matcher("extends"), javaEntry, opts("archives", true))
                .contains("extends RuntimeException"));
        // a directory-like prefix is not an entry, and a missing entry names the archive content
        JsUserRuntimeException missing = assertThrows(JsUserRuntimeException.class,
                () -> JsSearch.find(matcher("x"), "sample.tar.gz#src/main/java", opts("archives", true)));
        assertTrue(missing.getMessage().contains("Entry not found"), missing.getMessage());
        // explicitly binary -> hard error instead of a silent skip
        JsUserRuntimeException binary = assertThrows(JsUserRuntimeException.class,
                () -> JsSearch.find(matcher("Class"), classEntry, opts("archives", true)));
        assertTrue(binary.getMessage().contains("binary"), binary.getMessage());
        // entry targets are archive targets: archives: true is required
        assertThrows(IllegalArgumentException.class, () -> JsSearch.find(matcher("extends"), javaEntry));
    }

    // ========================================================================
    // Explicit archive chains (cursor lifetime, error hints, option requirements)
    // ========================================================================

    /** Options of the chain tests: archives on, nesting on, deep enough for three levels. */
    private static Map<String, Object> chainOptions() {
        return opts("archives", true, "recursiveArchives", true, "maxArchiveDepth", 4);
    }

    /**
     * Writes {@code three.tar.gz} as tar.gz &rarr; tar &rarr; tar with entries <em>before</em>
     * and <em>after</em> every nested archive, so a desynchronised outer stream would lose them.
     */
    private void writeThreeLevelTarGz() throws IOException {
        byte[] inner = tar(bytes("deep/file.txt", "TODO deep line\nsecond deep line\n",
                "deep/other.txt", "TODO other\n"));
        byte[] mid = tar(bytes("mid_note.txt", "TODO mid\n",
                "inner.tar", inner,
                "mid_after.txt", "TODO mid after\n"));
        Files.write(tempDir.resolve("three.tar.gz"), gzip(tar(bytes(
                "before.txt", "TODO before\n",
                "mid.tar", mid,
                "after.txt", "TODO after\n"))));
    }

    @Test
    void nestedTarInsideTarKeepsTheOuterArchiveInSync() throws IOException {
        writeThreeLevelTarGz();
        Map<String, Object> result = JsSearch.find(matcher("TODO"), "three.tar.gz", chainOptions());
        assertEquals(List.of("three.tar.gz#after.txt", "three.tar.gz#before.txt",
                        "three.tar.gz#mid.tar#inner.tar#deep/file.txt",
                        "three.tar.gz#mid.tar#inner.tar#deep/other.txt",
                        "three.tar.gz#mid.tar#mid_after.txt",
                        "three.tar.gz#mid.tar#mid_note.txt"),
                filesOf(result));
        assertEquals(0, count(result, "errors"));
        assertTrue(((List<?>) result.get("warnings")).isEmpty(), String.valueOf(result.get("warnings")));
    }

    /**
     * Regression (interactive smoke test): the data of an explicitly addressed entry is a view on
     * the stream of <em>every</em> archive of the chain, so all cursors must stay open until the
     * entry was scanned. Closing them when the lookup returned failed the read with
     * "Stream closed".
     */
    @Test
    void explicitDeepChainKeepsEveryCursorOpen() throws IOException {
        writeThreeLevelTarGz();
        String display = "three.tar.gz#mid.tar#inner.tar#deep/file.txt";

        assertEquals(display + ":2:second deep line",
                JsSearch.grep(matcher("second"), display, chainOptions()));
        // the addressed element may itself be an archive: everything below it is searched
        assertEquals(List.of(display, "three.tar.gz#mid.tar#inner.tar#deep/other.txt"),
                JsSearch.files(matcher("TODO"), "three.tar.gz#mid.tar#inner.tar", chainOptions()));
        // and the parent levels stay readable: a sibling entry resolves as well
        assertEquals(List.of("three.tar.gz#mid.tar#mid_note.txt"),
                JsSearch.files(matcher("TODO"), "three.tar.gz#mid.tar#mid_note.txt", chainOptions()));
    }

    @Test
    void severalExplicitEntryTargetsShareOneScan() throws IOException {
        writeThreeLevelTarGz();
        assertEquals(List.of("three.tar.gz#after.txt", "three.tar.gz#before.txt",
                        "three.tar.gz#mid.tar#inner.tar#deep/file.txt"),
                JsSearch.files(matcher("TODO"), List.of(
                        "three.tar.gz#before.txt",
                        "three.tar.gz#mid.tar#inner.tar#deep/file.txt",
                        "three.tar.gz#after.txt"), chainOptions()));

        // a target listed twice is scanned twice but reported once (display paths are unique)
        Map<String, Object> duplicated = JsSearch.find(matcher("TODO"),
                List.of("three.tar.gz#before.txt", "three.tar.gz#before.txt"), chainOptions());
        assertEquals(1, filesOf(duplicated).size(), String.valueOf(filesOf(duplicated)));
        assertEquals(2, count(duplicated, "matches"));
        assertEquals(2, count(duplicated, "filesScanned"));
    }

    /** The "entries of the last archive" hint must describe the deepest archive that was opened. */
    @Test
    void chainErrorsDescribeTheDeepestArchive() throws IOException {
        writeThreeLevelTarGz();

        JsUserRuntimeException missingLeaf = assertThrows(JsUserRuntimeException.class,
                () -> JsSearch.find(matcher("TODO"),
                        "three.tar.gz#mid.tar#inner.tar#missing.txt", chainOptions()));
        assertTrue(missingLeaf.getMessage().contains("Entry not found"), missingLeaf.getMessage());
        assertTrue(missingLeaf.getMessage().contains("deep/file.txt"),
                "the hint lists the innermost archive: " + missingLeaf.getMessage());
        assertFalse(missingLeaf.getMessage().contains("before.txt"),
                "outer entries must not be mixed into the hint: " + missingLeaf.getMessage());

        JsUserRuntimeException missingMiddle = assertThrows(JsUserRuntimeException.class,
                () -> JsSearch.find(matcher("TODO"),
                        "three.tar.gz#mid.tar#nope.tar#x.txt", chainOptions()));
        assertTrue(missingMiddle.getMessage().contains("inner.tar"),
                "the hint lists mid.tar: " + missingMiddle.getMessage());
        assertFalse(missingMiddle.getMessage().contains("deep/file.txt"),
                "entries below the failing level must not appear: " + missingMiddle.getMessage());
    }

    /**
     * A nested chain needs {@code recursiveArchives}. The error must say so instead of reporting
     * the effective depth limit of 1, which the user never set, and it must not be wrapped into
     * "Failed to open archive" (which suggests a broken file).
     */
    @Test
    void nestedChainRequiresRecursiveArchives() throws IOException {
        writeThreeLevelTarGz();
        String display = "three.tar.gz#mid.tar#inner.tar#deep/file.txt";

        IllegalArgumentException withoutNesting = assertThrows(IllegalArgumentException.class,
                () -> JsSearch.find(matcher("TODO"), display, opts("archives", true)));
        assertTrue(withoutNesting.getMessage().contains("recursiveArchives"), withoutNesting.getMessage());
        assertFalse(withoutNesting.getMessage().contains("maxArchiveDepth"),
                "no limit the user never set: " + withoutNesting.getMessage());
        assertFalse(withoutNesting.getMessage().contains("Failed to open archive"),
                "the real reason must survive the lookup: " + withoutNesting.getMessage());

        // maxArchiveDepth alone does not enable nesting
        IllegalArgumentException depthAlone = assertThrows(IllegalArgumentException.class,
                () -> JsSearch.find(matcher("TODO"), display,
                        opts("archives", true, "maxArchiveDepth", 4)));
        assertTrue(depthAlone.getMessage().contains("recursiveArchives"), depthAlone.getMessage());
    }

    @Test
    void maxArchiveDepthLimitsExplicitChains() throws IOException {
        writeThreeLevelTarGz();
        String display = "three.tar.gz#mid.tar#inner.tar#deep/file.txt";

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> JsSearch.find(matcher("TODO"), display,
                        opts("archives", true, "recursiveArchives", true, "maxArchiveDepth", 2)));
        assertTrue(e.getMessage().contains("maxArchiveDepth (2)"), e.getMessage());
        assertTrue(e.getMessage().contains(display), e.getMessage());

        assertEquals(1, matches(JsSearch.find(matcher("second"), display,
                opts("archives", true, "recursiveArchives", true, "maxArchiveDepth", 3))).size());
    }

    @Test
    void targetsThatCannotHaveEntries() throws IOException {
        writeThreeLevelTarGz();
        write("plain.txt", "TODO plain");
        Files.createDirectories(tempDir.resolve("sub"));

        JsUserRuntimeException insideArchive = assertThrows(JsUserRuntimeException.class,
                () -> JsSearch.find(matcher("TODO"), "three.tar.gz#mid.tar#mid_note.txt#x",
                        chainOptions()));
        assertTrue(insideArchive.getMessage().contains("cannot contain entries"),
                insideArchive.getMessage());

        JsUserRuntimeException plainFile = assertThrows(JsUserRuntimeException.class,
                () -> JsSearch.find(matcher("TODO"), "plain.txt#x", opts("archives", true)));
        assertTrue(plainFile.getMessage().contains("only archives have entries"), plainFile.getMessage());

        JsUserRuntimeException directory = assertThrows(JsUserRuntimeException.class,
                () -> JsSearch.find(matcher("TODO"), "sub#x", opts("archives", true)));
        assertTrue(directory.getMessage().contains("inside a directory"), directory.getMessage());
    }

    /** The same chain resolution through the JavaScript namespace (bridge value conversion). */
    @Test
    void jsExplicitChainTargetsWorkThroughTheBridge() throws IOException {
        writeThreeLevelTarGz();
        try (Context ctx = newTestContext()) {
            new JsSearchBridge().wireApi(ctx.getBindings("js"));

            assertEquals("three.tar.gz#mid.tar#inner.tar#deep/file.txt:2:second deep line",
                    ctx.eval("js", "search.grep('second',"
                            + " 'three.tar.gz#mid.tar#inner.tar#deep/file.txt',"
                            + " { archives: true, recursiveArchives: true })").asString());

            assertEquals("three.tar.gz#mid.tar#inner.tar#deep/other.txt",
                    ctx.eval("js", "search.files('TODO', { path: 'three.tar.gz',"
                            + " archiveChain: ['mid.tar', 'inner.tar'],"
                            + " entry: 'deep/other.txt' },"
                            + " { archives: true, recursiveArchives: true }).join(',')").asString());

            assertEquals(2, ctx.eval("js", "search.files('TODO', ["
                    + " 'three.tar.gz#before.txt',"
                    + " 'three.tar.gz#mid.tar#inner.tar#deep/file.txt'],"
                    + " { archives: true, recursiveArchives: true }).length").asInt());

            PolyglotException hint = assertThrows(PolyglotException.class,
                    () -> ctx.eval("js", "search.grep('TODO',"
                            + " 'three.tar.gz#mid.tar#inner.tar#missing.txt',"
                            + " { archives: true, recursiveArchives: true })"));
            assertTrue(hint.getMessage().contains("Entry not found"), hint.getMessage());
            assertTrue(hint.getMessage().contains("deep/other.txt"),
                    "the hint lists the innermost archive: " + hint.getMessage());

            PolyglotException needsNesting = assertThrows(PolyglotException.class,
                    () -> ctx.eval("js", "search.grep('TODO',"
                            + " 'three.tar.gz#mid.tar#inner.tar#deep/file.txt',"
                            + " { archives: true })"));
            assertTrue(needsNesting.getMessage().contains("recursiveArchives"),
                    needsNesting.getMessage());
        }
    }

    // ========================================================================
    // 33.32 Truncation, 33.33 binary, 33.34 size limits
    // ========================================================================

    @Test
    void truncationByMaxMatches() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            sb.append("TODO ").append(i).append('\n');
        }
        write("many.txt", sb.toString());

        Map<String, Object> result = JsSearch.find(matcher("TODO"), "many.txt", opts("maxMatches", 3));
        assertEquals(3, matches(result).size());
        assertTrue((Boolean) result.get("truncated"));
        assertEquals("maxMatches", result.get("truncatedReason"));

        String[] lines = JsSearch.grep(matcher("TODO"), "many.txt", opts("maxMatches", 3)).split("\n");
        assertEquals(4, lines.length, "three matches plus the marker");
        assertEquals("many.txt:1:TODO 1", lines[0]);
        assertEquals("-- truncated by search limit: maxMatches", lines[3]);
    }

    @Test
    void truncationByMaxOutputBytes() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 500; i++) {
            sb.append("TODO line ").append(i).append('\n');
        }
        write("big.txt", sb.toString());
        String out = JsSearch.grep(matcher("TODO"), "big.txt", opts("maxOutputBytes", 200));
        assertTrue(out.length() < 1000, "output must be cut, was " + out.length());
        assertTrue(out.endsWith("-- truncated by search limit: maxOutputBytes"), out);
    }

    @Test
    void binaryFilesAreSkipped() throws IOException {
        Files.write(tempDir.resolve("bin.dat"), new byte[]{'T', 'O', 'D', 'O', 0, 'x'});
        write("text.txt", "TODO");
        Map<String, Object> result = JsSearch.find(matcher("TODO"), ".", opts("recursive", true));
        assertEquals(List.of("text.txt"), filesOf(result));
        assertTrue(count(result, "skipped") >= 1, "the binary file is counted as skipped");

        Files.write(tempDir.resolve("explicit.bin"), new byte[]{'a', 'b', 0, 'T', 'O', 'D', 'O'});
        assertThrows(JsUserRuntimeException.class, () -> JsSearch.find(matcher("TODO"), "explicit.bin"));
    }

    @Test
    void oversizedFilesAreSkippedOrReported() throws IOException {
        byte[] large = new byte[200];
        Arrays.fill(large, (byte) 'a');
        Files.write(tempDir.resolve("large.txt"), large);
        write("small.txt", "TODO");

        Map<String, Object> result = JsSearch.find(matcher("TODO"), ".",
                opts("recursive", true, "maxFileBytes", 100));
        assertEquals(List.of("small.txt"), filesOf(result));
        assertTrue(count(result, "skipped") >= 1);
        assertThrows(JsUserRuntimeException.class,
                () -> JsSearch.find(matcher("TODO"), "large.txt", opts("maxFileBytes", 100)));
    }

    // ========================================================================
    // 33.35 Broken archives, 33.37 no extraction, 33.38 security
    // ========================================================================

    @Test
    void brokenArchiveInRecursiveScanIsANonFatalWarning() throws IOException {
        Files.write(tempDir.resolve("broken.zip"), text("this is not a zip file"));
        write("good.txt", "TODO");
        Map<String, Object> result = JsSearch.find(matcher("TODO"), ".",
                opts("recursive", true, "archives", true));
        assertTrue(filesOf(result).contains("good.txt"), filesOf(result).toString());
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) result.get("warnings");
        assertFalse(warnings.isEmpty(), "a warning is expected");
        assertTrue(warnings.get(0).contains("broken.zip"), warnings.toString());
        assertTrue(count(result, "errors") >= 1 || count(result, "skipped") >= 1);
    }

    @Test
    void archiveSearchNeverWritesToDisk() throws IOException {
        writeZip("noextract.zip", bytes("a.txt", "TODO", "dir/b.txt", "TODO"));
        Files.write(tempDir.resolve("data.tar.gz"), gzip(tar(bytes("c.txt", "TODO"))));
        TreeSet<String> before = snapshotFiles();
        JsSearch.find(matcher("TODO"), ".", opts("recursive", true, "archives", true,
                "recursiveArchives", true));
        assertEquals(before, snapshotFiles(), "archive search must not create or change files");
    }

    @Test
    void traversalOutsideTheProjectBaseIsRejected() {
        assertThrows(JsUserRuntimeException.class,
                () -> JsSearch.find(matcher("x"), "../secret.txt", opts("recursive", true)));
        assertThrows(JsUserRuntimeException.class, () -> JsSearch.find(matcher("x"), "/etc/passwd"));
    }

    @Test
    void archiveEntryNamesStayVirtual() throws IOException {
        // A tar entry with a path-traversal name is only a virtual display name.
        Files.write(tempDir.resolve("evil.tar"), tar(bytes("../../evil.txt", "TODO")));
        assertEquals(List.of("evil.tar#../../evil.txt"),
                filesOf(JsSearch.find(matcher("TODO"), "evil.tar", opts("archives", true))));
        assertFalse(Files.exists(tempDir.getParent().resolve("evil.txt")),
                "entry names must never become file system paths");
    }

    // ========================================================================
    // Helpers of the engine: glob compiler and display paths
    // ========================================================================

    @Test
    void globCompilerMatchesDocumentedCases() {
        assertTrue(JsSearch.compileGlob("*.dat", false).matcher("file.dat").matches());
        assertFalse(JsSearch.compileGlob("*.dat", false).matcher("dir/file.dat").matches());
        assertTrue(JsSearch.compileGlob("src/**/*.java", false).matcher("src/main/Foo.java").matches());
        assertFalse(JsSearch.compileGlob("src/**/*.java", false).matcher("other/src/main/Foo.java").matches());
        assertTrue(JsSearch.compileGlob("**/WEB-INF/*.xml", false).matcher("a/b/WEB-INF/web.xml").matches());
        assertTrue(JsSearch.compileGlob("[!a-z]*", false).matcher("Abc").matches());
        assertFalse(JsSearch.compileGlob("[!a-z]*", false).matcher("abc").matches());
        assertTrue(JsSearch.compileGlob("a?c", false).matcher("abc").matches());
        assertFalse(JsSearch.compileGlob("a?c", false).matcher("a/c").matches());
        assertTrue(JsSearch.compileGlob("A.TXT", true).matcher("a.txt").matches());
        assertThrows(IllegalArgumentException.class, () -> JsSearch.compileGlob("[abc", false));

        JsSearch.PathFilter filter = JsSearch.globFilter("WEB-INF/*.xml", false);
        assertTrue(filter.test("app.war#WEB-INF/web.xml"));
        assertTrue(filter.test("x/y.war#a.war#WEB-INF/web.xml"));
        assertFalse(filter.test("WEB-INF/web.html"));
    }

    @Test
    void displayPathEscapingIsReversible() {
        assertEquals("foo%23bar.txt", JsSearch.escapeDisplaySegment("foo#bar.txt"));
        assertEquals("50%25.zip", JsSearch.escapeDisplaySegment("50%.zip"));
        assertEquals("foo#bar.txt", JsSearch.unescapeDisplaySegment("foo%23bar.txt"));
        assertEquals("50%.zip", JsSearch.unescapeDisplaySegment("50%25.zip"));
        assertEquals(List.of("a.zip/b/c.txt", "b/c.txt"), JsSearch.collectMatchingPaths("a.zip#b/c.txt"));
        assertEquals("c.txt", JsSearch.basenameOf("a.zip#b/c.txt"));
        assertEquals("tar", JsSearch.archiveKindOf("x.tar"));
        assertEquals("targz", JsSearch.archiveKindOf("x.tar.gz"));
        assertEquals("targz", JsSearch.archiveKindOf("x.TGZ"));
        assertEquals("gzip", JsSearch.archiveKindOf("x.txt.gz"));
        assertEquals("zip", JsSearch.archiveKindOf("x.jar"));
        assertNull(JsSearch.archiveKindOf("x.java"));
    }
}
