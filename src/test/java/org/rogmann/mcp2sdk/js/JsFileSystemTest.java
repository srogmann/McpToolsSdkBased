package org.rogmann.mcp2sdk.js;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link JsFileSystem}.
 */
class JsFileSystemTest {

    @TempDir
    Path tempDir;

    private String oldProjectDir;

    @BeforeEach
    void setUp() {
        oldProjectDir = System.getProperty("IDE_PROJECT_DIR");
        System.setProperty("IDE_PROJECT_DIR", tempDir.toString());
    }

    @AfterEach
    void tearDown() {
        if (oldProjectDir != null) {
            System.setProperty("IDE_PROJECT_DIR", oldProjectDir);
        } else {
            System.clearProperty("IDE_PROJECT_DIR");
        }
    }

    @Test
    void writeAndReadFile() {
        JsFileSystem.writeFile("a.txt", "Hello\nWorld");
        assertEquals("Hello\nWorld", JsFileSystem.readFile("a.txt"));
        assertTrue(JsFileSystem.exists("a.txt"));
        assertTrue(JsFileSystem.isFile("a.txt"));
        assertFalse(JsFileSystem.isDirectory("a.txt"));
    }

    @Test
    void writeFileCreatesParentDirectories() {
        JsFileSystem.writeFile("sub/dir/b.txt", "content");
        assertTrue(Files.exists(tempDir.resolve("sub/dir/b.txt")));
        assertEquals("content", JsFileSystem.readFile("sub/dir/b.txt"));
    }

    @Test
    void appendFile() {
        JsFileSystem.writeFile("log.txt", "one");
        JsFileSystem.appendFile("log.txt", "\ntwo");
        assertEquals("one\ntwo", JsFileSystem.readFile("log.txt"));
    }

    @Test
    void readdirAndListFiles() throws IOException {
        Files.createDirectories(tempDir.resolve("sub"));
        JsFileSystem.writeFile("a.txt", "a");
        JsFileSystem.writeFile("sub/b.txt", "b");

        List<String> names = JsFileSystem.readdir(".");
        assertEquals(List.of("a.txt", "sub"), names);

        List<String> paths = JsFileSystem.listFiles(".");
        assertEquals(List.of("a.txt", "sub"), paths);

        List<String> subPaths = JsFileSystem.listFiles("sub");
        assertEquals(List.of("sub/b.txt"), subPaths);
    }

    @Test
    void stat() {
        JsFileSystem.writeFile("s.txt", "12345");
        Map<String, Object> stat = JsFileSystem.stat("s.txt");
        assertEquals("s.txt", stat.get("name"));
        assertEquals("s.txt", stat.get("path"));
        assertEquals(5L, stat.get("size"));
        assertEquals(Boolean.TRUE, stat.get("isFile"));
        assertEquals(Boolean.FALSE, stat.get("isDirectory"));
        assertNotNull(stat.get("lastModified"));
    }

    @Test
    void mkdirAndRm() {
        JsFileSystem.mkdir("d1/d2");
        assertTrue(Files.isDirectory(tempDir.resolve("d1/d2")));

        JsFileSystem.writeFile("d1/d2/x.txt", "x");
        JsFileSystem.rm("d1");
        assertFalse(Files.exists(tempDir.resolve("d1")));
    }

    @Test
    void renameAndCopyFile() {
        JsFileSystem.writeFile("src.txt", "data");
        JsFileSystem.copyFile("src.txt", "dst.txt");
        assertEquals("data", JsFileSystem.readFile("dst.txt"));

        JsFileSystem.rename("dst.txt", "moved.txt");
        assertFalse(JsFileSystem.exists("dst.txt"));
        assertTrue(JsFileSystem.exists("moved.txt"));
    }

    @Test
    void readLinesRange() {
        JsFileSystem.writeFile("lines.txt", "l1\nl2\nl3\nl4\nl5");
        assertEquals("l2\nl3", JsFileSystem.readLines("lines.txt", 2, 3));
        assertEquals("l1", JsFileSystem.readLines("lines.txt", 1, 1));
        // Default range (endLine < startLine -> startLine + DEFAULT_MAX_LINES - 1)
        String all = JsFileSystem.readLines("lines.txt", 1, 0);
        assertEquals("l1\nl2\nl3\nl4\nl5", all);
    }

    @Test
    void lineReaderStreamsAllLines() {
        JsFileSystem.writeFile("big.txt", "a\nb\nc");
        try (JsFileSystem.LineReader reader = JsFileSystem.createLineReader("big.txt")) {
            assertEquals(1L, reader.getLineNumber());
            assertEquals("a", reader.next());
            assertEquals("b", reader.next());
            assertEquals(3L, reader.getLineNumber());
            assertEquals("c", reader.next());
            assertNull(reader.next()); // EOF
            assertTrue(reader.isClosed()); // auto-closed at EOF
        }
    }

    @Test
    void lineReaderReadLinesBatch() {
        JsFileSystem.writeFile("batch.txt", "1\n2\n3\n4\n5");
        try (JsFileSystem.LineReader reader = JsFileSystem.createLineReader("batch.txt")) {
            assertEquals("1\n2", reader.readLines(2));
            assertEquals("3\n4\n5", reader.readLines(10));
            assertNull(reader.readLines(10)); // EOF
        }
    }

    @Test
    void pathTraversalIsRejected() {
        assertThrows(JsUserRuntimeException.class, () -> JsFileSystem.readFile("../outside.txt"));
        assertThrows(JsUserRuntimeException.class, () -> JsFileSystem.readFile("/etc/passwd"));
        assertThrows(JsUserRuntimeException.class, () -> JsFileSystem.readFile("sub/../../outside.txt"));
        assertThrows(JsUserRuntimeException.class, () -> JsFileSystem.writeFile("", "x"));
    }

    @Test
    void symlinkOutsideBaseIsRejected() throws IOException {
        Path outside = tempDir.getParent().resolve("outside-target-" + System.nanoTime() + ".txt");
        Files.writeString(outside, "secret");
        try {
            Path link = tempDir.resolve("link.txt");
            try {
                Files.createSymbolicLink(link, outside);
            } catch (UnsupportedOperationException | IOException e) {
                org.junit.jupiter.api.Assumptions.assumeTrue(false,
                        "Symbolic links not supported on this platform");
            }
            assertThrows(JsUserRuntimeException.class, () -> JsFileSystem.readFile("link.txt"));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    // ========================================================================
    // Character sets (encoding)
    // ========================================================================

    /** "Gruesse" written as ISO-8859-1 / CP-1252: G r ue sz e. */
    private static final byte[] LATIN1_GRUESSE = {0x47, 0x72, (byte) 0xFC, (byte) 0xDF, 0x65};

    /** The same word as Java text (escapes keep the test source ASCII-only). */
    private static final String GRUESSE = "Gr\u00fc\u00dfe";

    @Test
    void readFileWithExplicitEncoding() {
        JsFileSystem.writeBytes("latin1.txt", LATIN1_GRUESSE);
        assertEquals(GRUESSE, JsFileSystem.readFile("latin1.txt", "ISO-8859-1"));
        assertEquals(GRUESSE, JsFileSystem.readFile("latin1.txt", "latin1"));
        assertEquals(GRUESSE, JsFileSystem.readFile("latin1.txt", "iso 8859 1"));
        // null / blank keeps the UTF-8 default - which fails loudly on these bytes
        assertThrows(IllegalArgumentException.class, () -> JsFileSystem.readFile("latin1.txt", null));
    }

    @Test
    void readLinesAndLineReaderWithEncoding() {
        JsFileSystem.writeBytes("latin1-lines.txt",
            concat(LATIN1_GRUESSE, new byte[]{'\n'}, LATIN1_GRUESSE));
        assertEquals(GRUESSE + "\n" + GRUESSE,
                JsFileSystem.readLines("latin1-lines.txt", 1, 10, "ISO-8859-1"));
        try (JsFileSystem.LineReader reader = JsFileSystem.createLineReader("latin1-lines.txt", "latin1")) {
            assertEquals("ISO-8859-1", reader.getEncoding());
            assertEquals(GRUESSE, reader.next());
            assertEquals(GRUESSE, reader.next());
            assertNull(reader.next());
        }
    }

    @Test
    void strictUtf8RejectsLegacyBytesWithActionableMessage() {
        JsFileSystem.writeBytes("legacy.txt", new byte[]{'a', (byte) 0xE4, 'b'});
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> JsFileSystem.readFile("legacy.txt"));
        String msg = e.getMessage();
        assertTrue(msg.contains("Invalid UTF-8"), msg);
        assertTrue(msg.contains("byte offset 1"), msg);
        assertTrue(msg.contains("detectCharset"), msg);
    }

    @Test
    void errorPoliciesReplaceAndIgnore() {
        JsFileSystem.writeBytes("legacy.txt", new byte[]{'a', (byte) 0xE4, 'b'});
        assertEquals("a\uFFFDb", JsFileSystem.readFile("legacy.txt", "UTF-8", "replace"));
        assertEquals("ab", JsFileSystem.readFile("legacy.txt", "UTF-8", "ignore"));
        assertEquals(GRUESSE, JsFileSystem.readFile(
                writeBytes("ok.txt", LATIN1_GRUESSE), "ISO-8859-1", "strict"));
    }

    @Test
    void truncatedUtf8SequenceAtEndIsReported() {
        // 'A' followed by the lead byte of a two-byte sequence: the file ends mid-character
        JsFileSystem.writeBytes("tail.txt", new byte[]{'A', (byte) 0xC3});
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> JsFileSystem.readFile("tail.txt"));
        assertTrue(e.getMessage().contains("byte offset 1"), e.getMessage());
    }

    @Test
    void writeFileWithEncodingProducesExactBytes() {
        JsFileSystem.writeFile("out-latin1.txt", GRUESSE, "ISO-8859-1");
        assertArrayEquals(LATIN1_GRUESSE, JsFileSystem.readBytes("out-latin1.txt", 0, 5));
        // The default stays UTF-8: the same text needs two bytes per umlaut
        JsFileSystem.writeFile("out-utf8.txt", GRUESSE);
        assertEquals(GRUESSE, JsFileSystem.readFile("out-utf8.txt"));
        assertTrue(JsFileSystem.size("out-utf8.txt") > JsFileSystem.size("out-latin1.txt"));
        // appendFile honours the same encoding
        JsFileSystem.appendFile("out-latin1.txt", "\n" + GRUESSE, "ISO-8859-1");
        assertEquals(GRUESSE + "\n" + GRUESSE, JsFileSystem.readFile("out-latin1.txt", "ISO-8859-1"));
    }

    @Test
    void unrepresentableCharacterIsRejectedOnWrite() {
        // U+20AC (euro sign) does not exist in ISO-8859-1
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> JsFileSystem.writeFile("euro.txt", "Preis 5 \u20AC", "ISO-8859-1"));
        assertTrue(e.getMessage().contains("20AC"), e.getMessage());
        // the policy can relax it
        JsFileSystem.writeFile("euro.txt", "Preis 5 \u20AC", "ISO-8859-1", "replace");
        assertTrue(JsFileSystem.size("euro.txt") > 0);
    }

    @Test
    void decodeEncodeAndDecodeHex() {
        assertEquals(GRUESSE, JsFileSystem.decode(LATIN1_GRUESSE, "ISO-8859-1"));
        assertEquals(GRUESSE, JsFileSystem.decodeHex("4772fcdf65", "latin1"));
        assertArrayEquals(LATIN1_GRUESSE, JsFileSystem.encode(GRUESSE, "ISO-8859-1"));
        assertArrayEquals("Hallo".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                JsFileSystem.encode("Hallo"));
        assertThrows(IllegalArgumentException.class, () -> JsFileSystem.decode(LATIN1_GRUESSE));
        assertThrows(IllegalArgumentException.class, () -> JsFileSystem.fromHex("477"));
        assertThrows(IllegalArgumentException.class, () -> JsFileSystem.fromHex("zz"));
    }

    @Test
    void unknownEncodingAndPolicyAreRejectedWithHints() {
        JsFileSystem.writeFile("a.txt", "x");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> JsFileSystem.readFile("a.txt", "bogus-123"));
        assertTrue(e.getMessage().contains("Unsupported encoding"), e.getMessage());
        IllegalArgumentException p = assertThrows(IllegalArgumentException.class,
                () -> JsFileSystem.readFile("a.txt", "UTF-8", "lenient"));
        assertTrue(p.getMessage().contains("errors policy"), p.getMessage());
    }

    @Test
    void detectCharsetFindsUtf8AndBinary() {
        JsFileSystem.writeFile("plain.txt", "hello world\nsecond line");
        Map<String, Object> ascii = JsFileSystem.detectCharset("plain.txt");
        assertEquals("UTF-8", ascii.get("recommended"));
        assertEquals("ok", ascii.get("utf8"));
        assertEquals(Boolean.FALSE, ascii.get("looksBinary"));

        JsFileSystem.writeFile("utf8.txt", "caf\u00e9 \u2013 unicode");
        Map<String, Object> utf8 = JsFileSystem.detectCharset("utf8.txt");
        assertEquals("UTF-8", utf8.get("recommended"));
        assertEquals("high", utf8.get("confidence"));

        JsFileSystem.writeBytes("bin.dat", new byte[]{0x00, 0x01, 0x02, 0x00, 'x'});
        Map<String, Object> bin = JsFileSystem.detectCharset("bin.dat");
        assertEquals(Boolean.TRUE, bin.get("looksBinary"));
        assertNull(bin.get("recommended"));
        assertNull(bin.get("howToRead"));
    }

    @Test
    void detectCharsetSuggestsALatin1FamilyCharsetForLegacyText() {
        // "cafe" with a trailing 0xE9 (e-acute) as Latin-1: not valid UTF-8, so the detector has
        // to pick a single-byte charset
        JsFileSystem.writeBytes("legacy.txt", new byte[]{'c', 'a', 'f', (byte) 0xE9});
        Map<String, Object> info = JsFileSystem.detectCharset("legacy.txt");
        String recommended = (String) info.get("recommended");
        assertNotNull(recommended, String.valueOf(info));
        assertTrue(recommended.startsWith("ISO-8859") || recommended.startsWith("CP-1252"),
                String.valueOf(info));
        assertEquals("caf\u00e9", JsFileSystem.readFile("legacy.txt", recommended));
    }

    @Test
    void detectCharsetPrefersCp1252ForWindowsPunctuation() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.charset.Charset.isSupported("windows-1252"), "CP-1252 not available in this JVM");
        // curly quotes and an en dash as CP-1252: raw Latin-1 would decode them as invisible C1 controls
        JsFileSystem.writeBytes("win.txt", new byte[]{'q', ':', ' ', (byte) 0x93, 'x', (byte) 0x94, ' ',
                (byte) 0x96, ' ', (byte) 0xE4});
        Map<String, Object> info = JsFileSystem.detectCharset("win.txt");
        assertEquals("CP-1252", info.get("recommended"), String.valueOf(info));
    }

    @Test
    void detectCharsetReportsByteOrderMarks() {
        JsFileSystem.writeBytes("bom-utf8.txt",
                new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'h', 'i'});
        Map<String, Object> bom = JsFileSystem.detectCharset("bom-utf8.txt");
        assertEquals("UTF-8", bom.get("bom"));
        assertEquals("UTF-8", bom.get("recommended"));
        // the BOM is kept as the first character (no silent content change)
        assertEquals("\uFEFFhi", JsFileSystem.readFile("bom-utf8.txt"));
    }

    @Test
    void detectCharsetFindsUtf16WithoutBom() {
        byte[] le = "Hallo".getBytes(java.nio.charset.Charset.forName("UTF-16LE"));
        byte[] be = "Hallo".getBytes(java.nio.charset.Charset.forName("UTF-16BE"));
        JsFileSystem.writeBytes("u16le.txt", le);
        JsFileSystem.writeBytes("u16be.txt", be);
        assertEquals("UTF-16LE", JsFileSystem.detectCharset("u16le.txt").get("recommended"));
        assertEquals("UTF-16BE", JsFileSystem.detectCharset("u16be.txt").get("recommended"));
        assertEquals(Boolean.FALSE, JsFileSystem.detectCharset("u16le.txt").get("looksBinary"));
        assertEquals("Hallo", JsFileSystem.readFile("u16le.txt", "UTF-16LE"));
        assertEquals("Hallo", JsFileSystem.readFile("u16be.txt", "UTF-16BE"));
    }

    @Test
    void utf16RoundTripWithWriteFile() {
        JsFileSystem.writeFile("u16.txt", GRUESSE, "UTF-16LE");
        assertEquals(GRUESSE, JsFileSystem.readFile("u16.txt", "UTF-16LE"));
        assertEquals(2 * GRUESSE.length(), JsFileSystem.size("u16.txt"));
    }

    private static String writeBytes(String path, byte[] data) {
        JsFileSystem.writeBytes(path, data);
        return path;
    }

    private static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] part : parts) {
            len += part.length;
        }
        byte[] out = new byte[len];
        int pos = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, pos, part.length);
            pos += part.length;
        }
        return out;
    }
}
