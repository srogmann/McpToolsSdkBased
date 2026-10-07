package org.rogmann.mcp2sdk.js;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rogmann.mcp2sdk.utils.ZipFixtures;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests for {@link JsJavap}: command mapping, class-magic validation, class-file version
 * parsing, output-path security and (guarded by the presence of javap) a real disassembly of
 * the compiled fixture {@code /js/JsUserRuntimeException.class}.
 */
class JsJavapTest {

    /** Fixture resource: a compiled .class file in src/test/resources/js. */
    private static final String FIXTURE_RESOURCE = "/js/JsUserRuntimeException.class";

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

    // ========================================================================
    // Command mapping (no external tool required)
    // ========================================================================

    @Test
    void commandMapping() {
        assertEquals(List.of("-c", "-p", "-constants"), JsJavap.resolveFlags("bytecode"));
        assertEquals(List.of("-protected"), JsJavap.resolveFlags("structure"));
        assertEquals(List.of("-verbose", "-p"), JsJavap.resolveFlags("verbose"));
        // default and normalization
        assertEquals(JsJavap.resolveFlags("bytecode"), JsJavap.resolveFlags(null));
        assertEquals(JsJavap.resolveFlags("bytecode"), JsJavap.resolveFlags("  "));
        assertEquals(JsJavap.resolveFlags("bytecode"), JsJavap.resolveFlags("Bytecode"));
        assertEquals(JsJavap.resolveFlags("structure"), JsJavap.resolveFlags(" STRUCTURE "));
    }

    @Test
    void unknownCommandIsRejected() {
        JsUserRuntimeException e = assertThrows(JsUserRuntimeException.class,
                () -> JsJavap.resolveFlags("decompile"));
        assertTrue(e.getMessage().contains("Unknown command"), e.getMessage());
    }

    // ========================================================================
    // Class-file version (no external tool required)
    // ========================================================================

    @Test
    void classVersionFormatting() {
        assertEquals("52.0 (Java 8)", JsJavap.formatClassVersion(52, 0));
        assertEquals("61.0 (Java 17)", JsJavap.formatClassVersion(61, 0));
        assertEquals("65.0 (Java 21)", JsJavap.formatClassVersion(65, 0));
        // 49 is the first "Java X" version (Java 5), not 1.5
        assertEquals("49.0 (Java 5)", JsJavap.formatClassVersion(49, 0));
        assertEquals("48.0 (Java 1.4)", JsJavap.formatClassVersion(48, 0));
        assertEquals("46.0 (Java 1.2)", JsJavap.formatClassVersion(46, 0));
        assertEquals("45.3 (Java 1.1)", JsJavap.formatClassVersion(45, 3));
        assertEquals("44.0 (Java 1.0)", JsJavap.formatClassVersion(44, 0));
    }

    @Test
    void fixtureClassVersionIsParsed() throws IOException {
        byte[] data = readFixture();
        // major version at offsets 6..7 (big-endian)
        int major = ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);
        int minor = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);
        String expected = JsJavap.formatClassVersion(major, minor);
        assertNotNull(expected);
        // the fixture is a Java 8+ class, not something exotic
        assertTrue(major >= 52, "unexpected class file version: " + major);
    }

    // ========================================================================
    // Validation / security (no external tool required)
    // ========================================================================

    @Test
    void emptyBytesAreRejected() {
        JsUserRuntimeException e = assertThrows(JsUserRuntimeException.class,
                () -> JsJavap.disassemble(new byte[0], "bytecode", "x.txt"));
        assertTrue(e.getMessage().contains("empty"), e.getMessage());
    }

    @Test
    void nullBytesAreRejected() {
        // explicit cast: a bare null is ambiguous between the String and byte[] overloads
        assertThrows(IllegalArgumentException.class,
                () -> JsJavap.disassemble((byte[]) null, "bytecode", "x.txt"));
    }

    @Test
    void nonClassMagicIsRejected() {
        // ELF header of a native binary -> must point the caller to objdump
        byte[] elf = {0x7F, 'E', 'L', 'F', 2, 1, 1, 0, 0, 0};
        JsUserRuntimeException e = assertThrows(JsUserRuntimeException.class,
                () -> JsJavap.disassemble(elf, "bytecode", "x.txt"));
        assertTrue(e.getMessage().contains("Not a valid Java class file"), e.getMessage());
        assertTrue(e.getMessage().contains("objdump"), e.getMessage());

        JsUserRuntimeException e2 = assertThrows(JsUserRuntimeException.class,
                () -> JsJavap.disassemble(new byte[]{1, 2, 3}, "bytecode", "x.txt"));
        assertTrue(e2.getMessage().contains("too short"), e2.getMessage());
    }

    @Test
    void outputPathTraversalIsRejected() {
        byte[] data = validClassPrefix();
        assertThrows(JsUserRuntimeException.class,
                () -> JsJavap.disassemble(data, "bytecode", "../evil.txt"));
        assertThrows(JsUserRuntimeException.class,
                () -> JsJavap.disassemble(data, "bytecode", "sub/../../evil.txt"));
    }

    @Test
    void sourceFileMissingIsReported() {
        JsUserRuntimeException e = assertThrows(JsUserRuntimeException.class,
                () -> JsJavap.disassemble("no/such/Foo.class", "bytecode", "x.txt"));
        assertTrue(e.getMessage().contains("Source file not found"), e.getMessage());
        // relative path only, no absolute path leak
        assertTrue(!e.getMessage().contains(tempDir.toString()), e.getMessage());
    }

    // ========================================================================
    // Integration (requires javap of the running JDK)
    // ========================================================================

    @Test
    void disassembleFixtureBytes() throws IOException {
        assumeJavap();
        byte[] data = readFixture();

        Map<String, Object> result = JsJavap.disassemble(data, "bytecode", "out/dump.txt");
        assertEquals("out/dump.txt", result.get("outputFile"));
        assertEquals("out/dump.class", result.get("classFile"));
        assertEquals("bytecode", result.get("command"));
        assertEquals(Integer.valueOf(0), result.get("exitCode"));
        assertTrue((Long) result.get("bytesWritten") > 0);
        assertNotNull(result.get("classVersion"));

        // The intermediate class file is kept on success.
        assertTrue(Files.isRegularFile(tempDir.resolve("out/dump.txt")), "output file should exist");
        assertTrue(Files.isRegularFile(tempDir.resolve("out/dump.class")), "intermediate class should exist");

        String content = Files.readString(tempDir.resolve("out/dump.txt"));
        assertTrue(content.contains("org.rogmann.mcp2sdk.js.JsUserRuntimeException"), content);
    }

    @Test
    void disassembleFixtureFile() throws IOException {
        assumeJavap();
        Files.write(tempDir.resolve("fixture.class"), readFixture());

        Map<String, Object> result = JsJavap.disassemble("fixture.class", "structure", "structure.txt");
        // Genuine file input is used directly, no intermediate copy.
        assertEquals("fixture.class", result.get("classFile"));
        assertEquals("structure", result.get("command"));
        assertEquals(Integer.valueOf(0), result.get("exitCode"));

        String content = Files.readString(tempDir.resolve("structure.txt"));
        assertTrue(content.contains("public class org.rogmann.mcp2sdk.js.JsUserRuntimeException"), content);
    }

    @Test
    void disassembleFixtureVerbose() throws IOException {
        assumeJavap();
        Files.write(tempDir.resolve("fixture.class"), readFixture());

        Map<String, Object> result = JsJavap.disassemble("fixture.class", "verbose", "verbose.txt");
        assertEquals(Integer.valueOf(0), result.get("exitCode"));

        String content = Files.readString(tempDir.resolve("verbose.txt"));
        assertTrue(content.contains("minor version"), content);
        assertTrue(content.contains("Constant pool"), content);
    }

    @Test
    void defaultCommandIsBytecode() throws IOException {
        assumeJavap();
        Files.write(tempDir.resolve("fixture.class"), readFixture());

        // null command -> default 'bytecode'
        Map<String, Object> result = JsJavap.disassemble("fixture.class", null, "default.txt");
        assertEquals("bytecode", result.get("command"));
        String content = Files.readString(tempDir.resolve("default.txt"));
        assertTrue(content.contains("Code:"), content);
    }

    @Test
    void truncatedClassIsReportedAndCleanedUp() throws IOException {
        assumeJavap();
        // Valid class magic, but truncated: javap must fail with a non-zero exit code.
        byte[] data = Arrays.copyOf(readFixture(), 60);

        JsUserRuntimeException e = assertThrows(JsUserRuntimeException.class,
                () -> JsJavap.disassemble(data, "bytecode", "trunc.txt"));
        assertTrue(e.getMessage().contains("javap failed"), e.getMessage());
        // partial output and the generated intermediate class file are removed
        assertTrue(!Files.exists(tempDir.resolve("trunc.txt")), "partial output should be removed");
        assertTrue(!Files.exists(tempDir.resolve("trunc.class")), "intermediate class should be removed");
    }

    // ========================================================================
    // Classes inside archives (no unpacking)
    // ========================================================================

    @Test
    void classNameDerivation() {
        assertEquals("com.example.Foo", JsJavap.toClassName("com/example/Foo.class"));
        // inner classes keep their '$', packages their dots
        assertEquals("com.example.Foo$Bar", JsJavap.toClassName("com/example/Foo$Bar.class"));
        assertEquals("module-info", JsJavap.toClassName("module-info.class"));
        assertEquals("Foo", JsJavap.toClassName("Foo.class"));
        // resources are not classes and stay out of the listing (see classesOf)
        assertEquals("a/b/c.txt", JsJavap.toClassName("a/b/c.txt"));
    }

    @Test
    void classesOfAnEmbeddedArchiveAreListedInOneCall() throws IOException {
        installChainFixture();

        Map<String, Object> result = JsJavap.classes(EAR + "#" + ZIP + "#" + JAR, Map.of());
        assertEquals(EAR + "#" + ZIP + "#" + JAR, result.get("source"));
        assertEquals(1L, result.get("total"));
        assertEquals(1, ((List<?>) result.get("classes")).size());
        Map<?, ?> first = (Map<?, ?>) ((List<?>) result.get("classes")).get(0);
        assertEquals(CLASS, first.get("entry"));
        assertEquals("org.rogmann.mcp2sdk.js.JsJavapTest", first.get("className"));
        assertEquals(Boolean.FALSE, result.get("truncated"));

        // the same archive as bytes (what archive.zipEntry returns for an embedded jar)
        byte[] jarBytes = JsArchive.entryBytes(EAR + "#" + ZIP + "#" + JAR);
        assertEquals(1L, JsJavap.classes(jarBytes, Map.of()).get("total"));

        // filter and limit
        assertEquals(0L, JsJavap.classes(EAR + "#" + ZIP + "#" + JAR,
                Map.of("contains", "Controller")).get("total"));
        assertEquals(1L, JsJavap.classes(EAR + "#" + ZIP + "#" + JAR,
                Map.of("contains", "javaptest")).get("total"), "contains is case-insensitive");

        // truncation: two classes, one requested
        byte[] classBytes = JsArchive.entryBytes(FULL_CHAIN);
        byte[] twoClassJar = ZipFixtures.zipWithEntries(
                "com/example/One.class", classBytes, "com/example/Two.class", classBytes);
        Map<String, Object> limited = JsJavap.classes(twoClassJar, Map.of("maxEntries", 1));
        assertEquals(2L, limited.get("total"));
        assertEquals(1, ((List<?>) limited.get("classes")).size());
        assertEquals(Boolean.TRUE, limited.get("truncated"));
        assertEquals("com.example.One", ((Map<?, ?>) ((List<?>) limited.get("classes")).get(0)).get("className"));

        assertOnlyGeneratedFilesExist();
    }

    @Test
    void classOptionsAreCheckedStrictly() throws IOException {
        installChainFixture();
        assertThrows(IllegalArgumentException.class,
                () -> JsJavap.classes(EAR, Map.of("glob", "*")));
        assertThrows(IllegalArgumentException.class,
                () -> JsJavap.classes(EAR, Map.of("contains", 7)));
        assertThrows(IllegalArgumentException.class,
                () -> JsJavap.classes(EAR, Map.of("maxEntries", "viele")));
        assertThrows(IllegalArgumentException.class,
                () -> JsJavap.classes(EAR, Map.of("maxEntries", 0)));
        assertThrows(IllegalArgumentException.class,
                () -> JsJavap.classes(EAR, Map.of("maxEntries", 999999)));
        assertThrows(JsUserRuntimeException.class, () -> JsJavap.classes("", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> JsJavap.classes((byte[]) null, Map.of()));
    }

    @Test
    void classInsideNestedArchivesIsDisassembledWithoutExtracting() throws IOException {
        assumeJavap();
        installChainFixture();

        Map<String, Object> result = JsJavap.disassemble(FULL_CHAIN, "structure", "nested.txt");
        assertEquals(0, result.get("exitCode"));
        assertEquals(FULL_CHAIN, result.get("source"), "the display path is what was read");
        assertEquals("nested.class", result.get("classFile"));
        assertNotNull(result.get("classVersion"));
        assertTrue((Long) result.get("sourceBytes") > 0);

        String content = Files.readString(tempDir.resolve("nested.txt"));
        assertTrue(content.contains("org.rogmann.mcp2sdk.js.JsJavapTest"), content);
        // only the intermediate .class and the listing: no ear, zip or jar was written out
        assertOnlyGeneratedFilesExist("nested.txt", "nested.class");
    }

    @Test
    void anArchiveInsteadOfAClassNamesTheWayToAddressIt() throws IOException {
        installChainFixture();

        // the outer container is a ZIP, not a class: the error has to explain the display path
        JsUserRuntimeException e = assertThrows(JsUserRuntimeException.class,
                () -> JsJavap.disassemble(JsArchive.entryBytes(EAR), "bytecode", "x.txt"));
        assertTrue(e.getMessage().contains("is a ZIP archive"), e.getMessage());
        assertTrue(e.getMessage().contains("javap.classes"), e.getMessage());

        // a display path whose entry does not exist stays a clear message
        JsUserRuntimeException missing = assertThrows(JsUserRuntimeException.class,
                () -> JsJavap.disassemble(EAR + "#" + ZIP + "#nope/Missing.class", "bytecode", "y.txt"));
        assertTrue(missing.getMessage().contains("nope/Missing.class"), missing.getMessage());
    }

    @Test
    void nestedPathOutsideTheBaseIsRejected() {
        assertThrows(JsUserRuntimeException.class,
                () -> JsJavap.disassemble("../../etc/passwd#x.class", "bytecode", "z.txt"));
        assertThrows(JsUserRuntimeException.class,
                () -> JsJavap.classes("../../etc/passwd", Map.of()));
    }

    @Test
    void helpMentionsTheArchiveSource() {
        String help = JsJavap.help();
        assertTrue(help.contains("javap.classes("), help);
        assertTrue(help.contains("#"), help);
        assertTrue(help.contains("BOOT-INF/lib"), help);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /** Checked-in chain: ear &rarr; zip &rarr; jar &rarr; class (the embedded-archive case). */
    private static final String EAR = "js-javap.dummy.ear";
    private static final String EAR_RESOURCE = "/js/js-javap.dummy.ear";
    private static final String ZIP = "js-javap.zip";
    private static final String JAR = "js-javap-test.jar";
    private static final String CLASS = "org/rogmann/mcp2sdk/js/JsJavapTest.class";
    private static final String FULL_CHAIN = EAR + "#" + ZIP + "#" + JAR + "#" + CLASS;

    /** Copies the outer container into the temporary project directory. */
    private void installChainFixture() throws IOException {
        try (InputStream in = JsJavapTest.class.getResourceAsStream(EAR_RESOURCE)) {
            assertNotNull(in, "test resource " + EAR_RESOURCE + " not present");
            Files.write(tempDir.resolve(EAR), in.readAllBytes());
        }
    }

    /**
     * Asserts that the project directory holds only the fixture plus the named generated files -
     * i.e. that reading a nested archive never extracts anything.
     */
    private void assertOnlyGeneratedFilesExist(String... generated) throws IOException {
        List<String> expected = new ArrayList<>();
        expected.add(EAR);
        expected.addAll(Arrays.asList(generated));
        expected.sort(String::compareTo);
        List<String> actual;
        try (var stream = Files.list(tempDir)) {
            actual = stream.map(p -> p.getFileName().toString()).sorted().toList();
        }
        assertEquals(expected, actual, "unexpected files in the project directory");
    }

    /**
     * Skips the test unless javap is available (PATH or java.home/bin).
     */
    private static void assumeJavap() {
        assumeTrue(JsJavap.findJavap() != null, "javap not found on PATH or in java.home/bin");
    }

    /**
     * Reads the compiled fixture class from src/test/resources/js.
     */
    private static byte[] readFixture() throws IOException {
        try (InputStream in = JsJavapTest.class.getResourceAsStream(FIXTURE_RESOURCE)) {
            assertNotNull(in, "test resource " + FIXTURE_RESOURCE + " not present");
            return in.readAllBytes();
        }
    }

    /**
     * Builds a byte array with a valid class magic (for path-validation tests that must fail
     * before the magic check).
     */
    private static byte[] validClassPrefix() {
        return new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 61};
    }
}
