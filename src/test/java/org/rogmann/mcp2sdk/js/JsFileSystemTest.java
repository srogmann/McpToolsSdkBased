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
}
