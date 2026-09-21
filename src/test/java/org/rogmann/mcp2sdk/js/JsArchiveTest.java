package org.rogmann.mcp2sdk.js;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link JsArchive} (ZIP and tar listing/extraction, ZIP writing and security).
 */
class JsArchiveTest {

    private static final int TAR_BLOCK_SIZE = 512;

    @TempDir
    Path tempDir;

    private String oldProjectDir;
    private String oldReadonly;

    @BeforeEach
    void setUp() {
        oldProjectDir = System.getProperty("IDE_PROJECT_DIR");
        System.setProperty("IDE_PROJECT_DIR", tempDir.toString());
        // ZIP writing is off by default; tests that need it call enableZipWrite().
        oldReadonly = System.getProperty(JsArchive.PROP_READONLY);
        System.clearProperty(JsArchive.PROP_READONLY);
    }

    @AfterEach
    void tearDown() {
        restoreProperty("IDE_PROJECT_DIR", oldProjectDir);
        restoreProperty(JsArchive.PROP_READONLY, oldReadonly);
    }

    private static void restoreProperty(String key, String value) {
        if (value != null) {
            System.setProperty(key, value);
        } else {
            System.clearProperty(key);
        }
    }

    /** Enables the gated ZIP write operations for the current test. */
    private void enableZipWrite() {
        System.setProperty(JsArchive.PROP_READONLY, "false");
    }

    // ========================================================================
    // ZIP
    // ========================================================================

    @Test
    void zipEntriesAndExtract() throws IOException {
        writeZip("app.war", List.of(
                new Object[]{"dir/", new byte[0]},
                new Object[]{"dir/a.txt", "AA".getBytes(StandardCharsets.UTF_8)},
                new Object[]{"b.txt", "BBB".getBytes(StandardCharsets.UTF_8)}));

        List<Map<String, Object>> entries = JsArchive.zipEntries("app.war");
        List<String> names = entries.stream().map(e -> (String) e.get("name")).toList();
        assertEquals(List.of("b.txt", "dir/", "dir/a.txt"), names);

        Map<String, Object> b = entries.get(0);
        assertEquals(3L, b.get("size"));
        assertEquals(Boolean.FALSE, b.get("isDirectory"));
        assertEquals("DEFLATED", b.get("method"));

        Map<String, Object> dir = entries.get(1);
        assertEquals(Boolean.TRUE, dir.get("isDirectory"));

        assertArrayEquals("AA".getBytes(StandardCharsets.UTF_8),
                JsArchive.zipEntry("app.war", "dir/a.txt"));
        assertArrayEquals("BBB".getBytes(StandardCharsets.UTF_8),
                JsArchive.zipEntry("app.war", "b.txt"));
        assertArrayEquals(new byte[0], JsArchive.zipEntry("app.war", "dir/"));
        assertNull(JsArchive.zipEntry("app.war", "not/there.txt"));
    }

    @Test
    void zipWithUtf8Names() throws IOException {
        Object[] aObj = {"gr\u00fc\u00dfe/\u00e4.txt", "abc".getBytes(StandardCharsets.UTF_8)};
        List<Object[]> list = new ArrayList<>();
        list.add(aObj);
        writeZip("utf.zip", list);
        assertEquals("abc", new String(JsArchive.zipEntry("utf.zip", "gr\u00fc\u00dfe/\u00e4.txt"),
                StandardCharsets.UTF_8));
    }

    // ========================================================================
    // TAR (ustar + GNU longname + POSIX pax)
    // ========================================================================

    @Test
    void tarEntriesAndExtract() throws IOException {
        String longName = "sub/" + "1234567890".repeat(10) + ".txt"; // > 100 chars -> GNU longname
        writeTar("arch.tar", out -> {
            // 1) normal file
            byte[] hello = "Hello tar\n".getBytes(StandardCharsets.UTF_8);
            writeTarHeader(out, '0', "hello.txt", hello.length, null, 0644);
            writeTarData(out, hello);

            // 2) directory
            writeTarHeader(out, '5', "sub/", 0, null, 0755);

            // 3) file in subdirectory
            byte[] data = "1234567890\n".getBytes(StandardCharsets.UTF_8);
            writeTarHeader(out, '0', "sub/data.txt", data.length, null, 0644);
            writeTarData(out, data);

            // 4) GNU long name ('L' + real file)
            byte[] longContent = "long content\n".getBytes(StandardCharsets.UTF_8);
            byte[] lname = longName.getBytes(StandardCharsets.UTF_8);
            writeTarHeader(out, 'L', "GNU-longname", lname.length, null, 0);
            writeTarData(out, lname);
            writeTarHeader(out, '0', "sub/placeholder", longContent.length, null, 0644);
            writeTarData(out, longContent);

            // 5) POSIX pax 'path' override ('x' + real file)
            byte[] paxContent = "pax data\n".getBytes(StandardCharsets.UTF_8);
            byte[] paxData = "20 path=sub/pax.txt\n20 mtime=1234567890\n"
                    .getBytes(StandardCharsets.UTF_8);
            writeTarHeader(out, 'x', "pax", paxData.length, null, 0);
            writeTarData(out, paxData);
            writeTarHeader(out, '0', "sub/tmpname", paxContent.length, null, 0644);
            writeTarData(out, paxContent);

            writeTarEnd(out);
        });

        List<Map<String, Object>> entries = JsArchive.tarEntries("arch.tar");
        List<String> names = entries.stream().map(e -> (String) e.get("name")).toList();
        assertEquals(List.of("hello.txt", "sub", "sub/" + "1234567890".repeat(10) + ".txt",
                "sub/data.txt", "sub/pax.txt"), names);

        Map<String, Object> helloEntry = entries.get(0);
        assertEquals("file", helloEntry.get("type"));
        assertEquals(Boolean.TRUE, helloEntry.get("isFile"));
        assertEquals(Boolean.FALSE, helloEntry.get("isDirectory"));
        assertEquals(10L, helloEntry.get("size"));

        Map<String, Object> sub = entries.get(1);
        assertEquals("directory", sub.get("type"));
        assertEquals(Boolean.TRUE, sub.get("isDirectory"));

        Map<String, Object> paxEntry = entries.get(4);
        assertEquals("sub/pax.txt", paxEntry.get("name"));
        assertEquals(9L, paxEntry.get("size"));
        assertEquals(Boolean.TRUE, paxEntry.get("isFile"));
        assertEquals("0644", paxEntry.get("mode"));

        assertArrayEquals("Hello tar\n".getBytes(StandardCharsets.UTF_8),
                JsArchive.tarEntry("arch.tar", "hello.txt"));
        assertArrayEquals("long content\n".getBytes(StandardCharsets.UTF_8),
                JsArchive.tarEntry("arch.tar", longName));
        assertArrayEquals("pax data\n".getBytes(StandardCharsets.UTF_8),
                JsArchive.tarEntry("arch.tar", "sub/pax.txt"));
        assertArrayEquals(new byte[0], JsArchive.tarEntry("arch.tar", "sub"));
        assertArrayEquals("1234567890\n".getBytes(StandardCharsets.UTF_8),
                JsArchive.tarEntry("arch.tar", "sub/data.txt"));
        assertNull(JsArchive.tarEntry("arch.tar", "not/there.txt"));
    }

    @Test
    void tarRejectsNonTarFile() throws IOException {
        Files.writeString(tempDir.resolve("garbage.txt"), "definitely not a tar archive\n");
        assertThrows(JsUserRuntimeException.class, () -> JsArchive.tarEntries("garbage.txt"));
        assertThrows(JsUserRuntimeException.class, () -> JsArchive.tarEntry("garbage.txt", "x"));
    }

    // ========================================================================
    // gzip / deflate
    // ========================================================================

    @Test
    void gzipRoundTrip() {
        byte[] data = "Hello GZIP: \u00e4\u00f6\u00fc \u00e9\u00e8 \u00e0 Test 1 2 3"
                .getBytes(StandardCharsets.UTF_8);
        byte[] gz = JsArchive.gzip(data);
        // gzip magic 1F 8B
        assertEquals(0x1f, gz[0] & 0xFF);
        assertEquals(0x8b, gz[1] & 0xFF);
        assertArrayEquals(data, JsArchive.gunzip(gz));
        assertArrayEquals(new byte[0], JsArchive.gunzip(JsArchive.gzip(new byte[0])));
    }

    @Test
    void gunzipFileRoundTrip() throws IOException {
        byte[] data = "line1\nline2\nline3\n".getBytes(StandardCharsets.UTF_8);
        Files.write(tempDir.resolve("t.txt"), data);
        Files.write(tempDir.resolve("t.txt.gz"), JsArchive.gzip(data));
        assertArrayEquals(data, JsArchive.gunzipFile("t.txt.gz"));
    }

    @Test
    void deflateRoundTrip() {
        byte[] data = "abcde0123456789".repeat(50).getBytes(StandardCharsets.UTF_8);
        byte[] deflated = JsArchive.deflate(data);
        assertArrayEquals(data, JsArchive.inflate(deflated));
        assertArrayEquals(new byte[0], JsArchive.inflate(JsArchive.deflate(new byte[0])));
    }

    @Test
    void gzipAndDeflateRejectInvalidData() {
        assertThrows(JsUserRuntimeException.class, () -> JsArchive.gunzip(
                new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9}));
        // a stored block whose NLEN does not match ~LEN is definitely invalid deflate
        assertThrows(JsUserRuntimeException.class, () -> JsArchive.inflate(
                new byte[]{0, 0, 1, (byte) 0xFF, (byte) 0xFF}));
        assertThrows(IllegalArgumentException.class, () -> JsArchive.gzip(null));
        assertThrows(IllegalArgumentException.class, () -> JsArchive.inflate(null));
        assertThrows(JsUserRuntimeException.class, () -> JsArchive.gunzipFile("missing.gz"));
    }

    @Test
    void tarFromBytes() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] content = "tar-from-bytes\n".getBytes(StandardCharsets.UTF_8);
        writeTarHeader(baos, '0', "a.txt", content.length, null, 0644);
        writeTarData(baos, content);
        writeTarEnd(baos);

        List<Map<String, Object>> entries = JsArchive.tarEntries(baos.toByteArray());
        assertEquals(List.of("a.txt"), entries.stream().map(e -> (String) e.get("name")).toList());
        assertArrayEquals(content, JsArchive.tarEntry(baos.toByteArray(), "a.txt"));
        assertNull(JsArchive.tarEntry(baos.toByteArray(), "nope.txt"));

        assertThrows(IllegalArgumentException.class,
                () -> JsArchive.tarEntries((byte[]) null));
        assertThrows(IllegalArgumentException.class,
                () -> JsArchive.tarEntry(baos.toByteArray(), ""));
    }

    // ========================================================================
    // Real sample archives (src/test/resources)
    // ========================================================================

    /**
     * Expected SHA-256 of the common real file inside both sample archives
     * (src/test/resources/test.zip and test.tar.gz). Verified against the actual
     * project file org/rogmann/mcp2sdk/js/JsUserRuntimeException.java (961 bytes).
     */
    private static final String REAL_FILE_SHA256 =
            "734a0cfdac895446b7873f29c9c6c670668c9b47372e45b84c0bc4d6a8a0cedc";

    /** Expected SHA-256 of the uncompressed tar inside src/test/resources/test.tar.gz. */
    private static final String REAL_TAR_SHA256 =
            "5aaa094e42ebc4fb20140d87a318adcd2af9d7294207c44cffe42068cf829955";

    @Test
    void realZipResource() throws IOException {
        Files.write(tempDir.resolve("test.zip"), readResource("/test.zip"));

        List<Map<String, Object>> entries = JsArchive.zipEntries("test.zip");
        assertEquals(1, entries.size());
        Map<String, Object> e = entries.get(0);
        assertEquals("org/rogmann/mcp2sdk/js/JsUserRuntimeException.java", e.get("name"));
        assertEquals(961L, e.get("size"));
        assertEquals(393L, e.get("compressedSize"));
        assertEquals("DEFLATED", e.get("method"));
        assertEquals("939d0f1c", e.get("crc32"));
        assertEquals(Boolean.FALSE, e.get("isDirectory"));

        byte[] content = JsArchive.zipEntry("test.zip",
                "org/rogmann/mcp2sdk/js/JsUserRuntimeException.java");
        assertNotNull(content);
        assertEquals(961, content.length);
        String text = new String(content, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("package org.rogmann.mcp2sdk.js;"));
        assertEquals(REAL_FILE_SHA256, JsCrypto.sha256(content));
    }

    @Test
    void realTarGzResource() throws IOException {
        Files.write(tempDir.resolve("test.tar.gz"), readResource("/test.tar.gz"));

        byte[] tarBytes = JsArchive.gunzipFile("test.tar.gz");
        assertEquals(10240, tarBytes.length);
        assertEquals(REAL_TAR_SHA256, JsCrypto.sha256(tarBytes));

        // The decompressed tar must be readable (in-memory tar API) with exact metadata.
        List<Map<String, Object>> entries = JsArchive.tarEntries(tarBytes);
        assertEquals(List.of("org/rogmann/mcp2sdk/js/JsUserRuntimeException.java"),
                entries.stream().map(x -> (String) x.get("name")).toList());
        Map<String, Object> entry = entries.get(0);
        assertEquals("file", entry.get("type"));
        assertEquals(Boolean.TRUE, entry.get("isFile"));
        assertEquals(Boolean.FALSE, entry.get("isDirectory"));
        assertEquals(961L, entry.get("size"));
        assertEquals("0664", entry.get("mode"));

        // The extracted file is byte-identical to the file inside test.zip.
        byte[] extracted = JsArchive.tarEntry(tarBytes,
                "org/rogmann/mcp2sdk/js/JsUserRuntimeException.java");
        assertEquals(961, extracted.length);
        assertEquals(REAL_FILE_SHA256, JsCrypto.sha256(extracted));

        // E2E round-trip on the real compressed data.
        assertArrayEquals(tarBytes, JsArchive.gunzip(JsArchive.gzip(tarBytes)));
    }

    /** Entry name inside src/test/resources/test.tar.gz. */
    private static final String TGZ_ENTRY = "org/rogmann/mcp2sdk/js/JsUserRuntimeException.java";

    /**
     * The one-shot case: a {@code .tar.gz} (or {@code .tgz}) is read with a single call,
     * without gunzip and without an option &ndash; gzip is recognized from the magic bytes
     * {@code 1F 8B 08}, not from the name.
     */
    @Test
    void tgzOneShotRead() throws IOException {
        Files.write(tempDir.resolve("test.tar.gz"), readResource("/test.tar.gz"));

        List<Map<String, Object>> entries = JsArchive.tarEntries("test.tar.gz");
        assertEquals(List.of(TGZ_ENTRY), names(entries));
        Map<String, Object> entry = entries.get(0);
        assertEquals("file", entry.get("type"));
        assertEquals(Boolean.TRUE, entry.get("isFile"));
        assertEquals(Boolean.FALSE, entry.get("isDirectory"));
        assertEquals(961L, entry.get("size"));
        assertEquals("0664", entry.get("mode"));

        byte[] content = JsArchive.tarEntry("test.tar.gz", TGZ_ENTRY);
        assertNotNull(content);
        assertEquals(961, content.length);
        assertEquals(REAL_FILE_SHA256, JsCrypto.sha256(content));
        assertTrue(new String(content, StandardCharsets.UTF_8)
                .startsWith("package org.rogmann.mcp2sdk.js;"));
        assertNull(JsArchive.tarEntry("test.tar.gz", "not/there.txt"));

        // Same result as the explicit two-step route (decompress first, then read bytes).
        byte[] tarBytes = JsArchive.gunzipFile("test.tar.gz");
        assertEquals(names(entries), names(JsArchive.tarEntries(tarBytes)));
        assertArrayEquals(content, JsArchive.tarEntry(tarBytes, TGZ_ENTRY));
    }

    /** The byte-array variant must decide from the same magic bytes as the file variant. */
    @Test
    void tgzFromBytesAndPathAgree() throws IOException {
        byte[] gz = readResource("/test.tar.gz");
        Files.write(tempDir.resolve("test.tar.gz"), gz);

        assertEquals(names(JsArchive.tarEntries("test.tar.gz")), names(JsArchive.tarEntries(gz)));
        assertArrayEquals(JsArchive.tarEntry("test.tar.gz", TGZ_ENTRY),
                JsArchive.tarEntry(gz, TGZ_ENTRY));
        assertNull(JsArchive.tarEntry(gz, "not/there.txt"));
    }

    /**
     * Detection is content-based: the name is never trusted, in neither direction.
     */
    @Test
    void gzipIsDetectedByMagicNotByName() throws IOException {
        byte[] gz = readResource("/test.tar.gz");
        byte[] plainTar = JsArchive.gunzip(gz);

        // (a) A tarball that hides behind a neutral name is still read.
        Files.write(tempDir.resolve("payload.bin"), gz);
        assertEquals(List.of(TGZ_ENTRY), names(JsArchive.tarEntries("payload.bin")));
        assertEquals(REAL_FILE_SHA256, JsCrypto.sha256(JsArchive.tarEntry("payload.bin", TGZ_ENTRY)));

        // (b) The opposite lie: name says .tar.gz, content is an uncompressed tar. Reading it
        //     must not be attempted as gzip (that would fail with a confusing gzip error).
        Files.write(tempDir.resolve("misleading.tar.gz"), plainTar);
        assertEquals(List.of(TGZ_ENTRY), names(JsArchive.tarEntries("misleading.tar.gz")));
        assertEquals(REAL_FILE_SHA256,
                JsCrypto.sha256(JsArchive.tarEntry("misleading.tar.gz", TGZ_ENTRY)));

        // (c) .tgz as such, both compressed and uncompressed.
        Files.write(tempDir.resolve("pack.tgz"), gz);
        assertEquals(List.of(TGZ_ENTRY), names(JsArchive.tarEntries("pack.tgz")));
        Files.write(tempDir.resolve("plain.tgz"), plainTar);
        assertEquals(List.of(TGZ_ENTRY), names(JsArchive.tarEntries("plain.tgz")));
    }

    /**
     * A second gzip-compressed tar from the resources, read in one call: more than one entry,
     * so the listing and the extraction of a middle entry are covered for a gzipped source.
     */
    @Test
    void tgzWithSeveralEntries() throws IOException {
        Files.write(tempDir.resolve("sample.tar.gz"), readResource("/js/sample.tar.gz"));

        List<Map<String, Object>> entries = JsArchive.tarEntries("sample.tar.gz");
        assertEquals(List.of("src/main/java/org/rogmann/mcp2sdk/js/JsUserRuntimeException.java",
                "target/classes/org/rogmann/mcp2sdk/js/JsUserRuntimeException.class"), names(entries));
        assertEquals(List.of(961L, 630L), entries.stream().map(e -> e.get("size")).toList());

        // Extracting the first entry stops the scan early, before the gzip trailer.
        byte[] first = JsArchive.tarEntry("sample.tar.gz",
                "src/main/java/org/rogmann/mcp2sdk/js/JsUserRuntimeException.java");
        assertEquals(REAL_FILE_SHA256, JsCrypto.sha256(first));
        // ... and the entry behind it is still found.
        assertEquals(630, JsArchive.tarEntry("sample.tar.gz",
                "target/classes/org/rogmann/mcp2sdk/js/JsUserRuntimeException.class").length);
    }

    /**
     * A broken gzip source fails with a clear error instead of quietly returning a partial
     * archive. Both break points are covered: a gzip stream that ends too early and a tar
     * stream that is cut inside an entry while the gzip layer is intact.
     */
    @Test
    void truncatedGzTarFailsCleanly() throws IOException {
        byte[] gz = readResource("/test.tar.gz");

        // (a) gzip header cut after a few bytes
        Files.write(tempDir.resolve("stub.tar.gz"), Arrays.copyOf(gz, 6));
        JsUserRuntimeException stub = assertThrows(JsUserRuntimeException.class,
                () -> JsArchive.tarEntries("stub.tar.gz"));
        assertTrue(stub.getMessage().contains("stub.tar.gz"), stub.getMessage());

        // (b) valid gzip around a tar that stops inside the data of the first entry
        byte[] cutTar = Arrays.copyOf(JsArchive.gunzip(gz), 1024); // header + half of the data
        Files.write(tempDir.resolve("half.tar.gz"), JsArchive.gzip(cutTar));
        assertThrows(JsUserRuntimeException.class, () -> JsArchive.tarEntries("half.tar.gz"));
        assertThrows(JsUserRuntimeException.class, () -> JsArchive.tarEntry("half.tar.gz", TGZ_ENTRY));
        assertThrows(JsUserRuntimeException.class, () -> JsArchive.tarEntries(JsArchive.gzip(cutTar)));
    }

    /**
     * Concatenated gzip members are decompressed (the JDK reader resets the inflater for each
     * member), while the tar scan stops at the first end-of-archive marker - the same verdict
     * GNU tar gives for {@code cat one.tar.gz one.tar.gz}.
     */
    @Test
    void concatenatedGzipMembersReadAsFirstTar() throws IOException {
        byte[] gz = readResource("/test.tar.gz");
        byte[] twice = new byte[gz.length * 2];
        System.arraycopy(gz, 0, twice, 0, gz.length);
        System.arraycopy(gz, 0, twice, gz.length, gz.length);
        Files.write(tempDir.resolve("twice.tar.gz"), twice);

        assertEquals(List.of(TGZ_ENTRY), names(JsArchive.tarEntries("twice.tar.gz")));
        assertEquals(REAL_FILE_SHA256,
                JsCrypto.sha256(JsArchive.tarEntry("twice.tar.gz", TGZ_ENTRY)));
    }

    /** The help text has to mention the one-shot usage (it is the case callers look for). */
    @Test
    void helpMentionsGzippedTarballs() {
        String help = JsArchive.help();
        assertTrue(help.contains(".tgz"), "help must name .tgz");
        assertTrue(help.contains("1F 8B 08"), "help must state how gzip is detected: " + help);
        assertTrue(help.contains("tarEntries(\"sources.tar.gz\")"),
                "help must show the one-shot call: " + help);
    }

    // ========================================================================
    // Security
    // ========================================================================

    @Test
    void pathTraversalIsRejected() throws IOException {
        Files.writeString(tempDir.resolve("ok.zip"), "PK\u0003\u0004dummy");
        assertThrows(JsUserRuntimeException.class, () -> JsArchive.zipEntries("../evil.zip"));
        assertThrows(JsUserRuntimeException.class, () -> JsArchive.zipEntries("/etc/evil.zip"));
        assertThrows(JsUserRuntimeException.class, () -> JsArchive.zipEntry("ok.zip", "a"));
        assertThrows(JsUserRuntimeException.class, () -> JsArchive.tarEntry("/etc/passwd", "x"));
    }

    @Test
    void missingArchiveIsRejected() {
        assertThrows(JsUserRuntimeException.class, () -> JsArchive.zipEntries("missing.zip"));
        assertThrows(JsUserRuntimeException.class, () -> JsArchive.zipEntry("missing.zip", "a"));
        assertThrows(JsUserRuntimeException.class, () -> JsArchive.tarEntries("missing.tar"));
        assertThrows(JsUserRuntimeException.class, () -> JsArchive.tarEntry("missing.tar", "a"));
    }

    @Test
    void emptyEntryNameIsRejected() throws IOException {
        Object[] aObj = new Object[]{"a.txt", "x".getBytes(StandardCharsets.UTF_8)};
        List<Object[]> list = new ArrayList<>();
        list.add(aObj);

        writeZip("app.war", list);
        assertThrows(IllegalArgumentException.class, () -> JsArchive.zipEntry("app.war", "  "));
        assertThrows(IllegalArgumentException.class, () -> JsArchive.tarEntry("app.war", ""));
    }

    @Test
    void helpContainsUsage() {
        String help = JsArchive.help();
        assertTrue(help.contains("zipEntries"));
        assertTrue(help.contains("tarEntries"));
        assertTrue(help.contains("archive.help()"));
    }

    // ========================================================================
    // ZIP write access (gated by the mcp.js.archive.readonly system property)
    // ========================================================================

    @Test
    void writeOperationsAreDisabledByDefault() throws IOException {
        writeZip("ro.zip", List.<Object[]>of(new Object[]{"a.txt", bytes("A")}));
        assertTrue(JsArchive.isReadOnly());
        assertEquals(Boolean.TRUE, JsArchive.status().get("readonly"));

        JsUserRuntimeException refused = assertThrows(JsUserRuntimeException.class,
                () -> JsArchive.zipCreate("new.zip", List.of(), false, null, null));
        assertTrue(refused.getMessage().contains("read-only"), refused.getMessage());
        assertTrue(refused.getMessage().contains(JsArchive.PROP_READONLY), refused.getMessage());
        assertThrows(JsUserRuntimeException.class,
                () -> JsArchive.zipEntryWrite("ro.zip", "a.txt", bytes("B"), null, null));
        assertThrows(JsUserRuntimeException.class, () -> JsArchive.zipEntryDelete("ro.zip", "a.txt"));

        // Nothing was written, and reading is unaffected.
        assertFalse(Files.exists(tempDir.resolve("new.zip"), LinkOption.NOFOLLOW_LINKS));
        assertEquals(1, JsArchive.zipEntries("ro.zip").size());
        assertArrayEquals(bytes("A"), JsArchive.zipEntry("ro.zip", "a.txt"));
        assertTrue(JsArchive.help().contains("DISABLED"));
    }

    @Test
    void statusReportsWriteStateAndLimits() {
        Map<String, Object> closed = JsArchive.status();
        assertEquals(Boolean.FALSE, closed.get("writeEnabled"));
        assertEquals(JsArchive.PROP_READONLY, closed.get("property"));
        assertNull(closed.get("propertyValue"));
        assertEquals(Boolean.FALSE, closed.get("tarWriteSupported"));
        @SuppressWarnings("unchecked")
        Map<String, Object> limits = (Map<String, Object>) closed.get("limits");
        assertEquals(JsArchive.MAX_ENTRY_BYTES, limits.get("maxEntryBytes"));
        assertEquals(JsArchive.MAX_ZIP_ENTRIES, limits.get("maxZipEntries"));
        assertEquals(JsArchive.MAX_ARCHIVE_BYTES, limits.get("maxArchiveBytes"));

        enableZipWrite();
        Map<String, Object> open = JsArchive.status();
        assertEquals(Boolean.TRUE, open.get("writeEnabled"));
        assertEquals(Boolean.FALSE, open.get("readonly"));
        assertEquals("false", open.get("propertyValue"));
        assertFalse(JsArchive.isReadOnly());
        assertTrue(JsArchive.help().contains("ENABLED"));
    }

    @Test
    void zipCreateWritesReadableArchive() {
        enableZipWrite();
        Map<String, Object> result = JsArchive.zipCreate("out.zip", List.of(
                JsArchive.ZipEntrySpec.of("dir/", new byte[0]),
                JsArchive.ZipEntrySpec.ofText("dir/a.txt", "AA"),
                JsArchive.ZipEntrySpec.of("b.txt", bytes("BBB"))), false, "made by test", null);

        assertEquals("out.zip", result.get("path"));
        assertEquals(Boolean.TRUE, result.get("created"));
        assertEquals(Boolean.FALSE, result.get("replaced"));
        assertEquals(3, result.get("entries"));
        assertEquals(5L, result.get("uncompressedBytes"));
        assertNotNull(result.get("archiveBytes"));
        assertTrue(((List<?>) result.get("warnings")).isEmpty(), String.valueOf(result.get("warnings")));

        List<Map<String, Object>> entries = JsArchive.zipEntries("out.zip");
        assertEquals(List.of("b.txt", "dir/", "dir/a.txt"), names(entries));
        assertEquals("DEFLATED", entries.get(0).get("method"));
        assertEquals(3L, entries.get(0).get("size"));
        assertEquals(Boolean.TRUE, entries.get(1).get("isDirectory"));
        assertArrayEquals(bytes("AA"), JsArchive.zipEntry("out.zip", "dir/a.txt"));
    }

    @Test
    void zipCreateWritesEmptyArchiveAndHonoursStoreMethodMtimeAndComments() throws IOException {
        enableZipWrite();
        Map<String, Object> empty = JsArchive.zipCreate("empty.zip", List.of(), false, null, null);
        assertEquals(0, empty.get("entries"));
        assertEquals(List.of(), JsArchive.zipEntries("empty.zip"));

        JsArchive.zipCreate("stored.zip", List.of(new JsArchive.ZipEntrySpec("raw.bin", bytes("rawdata"),
                "store", 1_600_000_000_000L, "entry comment")), false, "archive comment", 9);
        try (ZipFile zf = new ZipFile(tempDir.resolve("stored.zip").toFile(), StandardCharsets.UTF_8)) {
            ZipEntry entry = zf.getEntry("raw.bin");
            assertNotNull(entry);
            assertEquals(ZipEntry.STORED, entry.getMethod());
            assertEquals(7L, entry.getSize());
            assertEquals("entry comment", entry.getComment());
            assertEquals(1_600_000_000L, entry.getLastModifiedTime().toMillis() / 1000);
            assertEquals("archive comment", zf.getComment());
        }
        assertArrayEquals(bytes("rawdata"), JsArchive.zipEntry("stored.zip", "raw.bin"));
    }

    @Test
    void zipCreateRefusesExistingFileWithoutOverwrite() {
        enableZipWrite();
        JsArchive.zipCreate("twice.zip", List.of(JsArchive.ZipEntrySpec.ofText("a.txt", "1")), false, null, null);
        JsUserRuntimeException refused = assertThrows(JsUserRuntimeException.class,
                () -> JsArchive.zipCreate("twice.zip", List.of(JsArchive.ZipEntrySpec.ofText("a.txt", "2")),
                        false, null, null));
        assertTrue(refused.getMessage().contains("File exists"), refused.getMessage());
        assertArrayEquals(bytes("1"), JsArchive.zipEntry("twice.zip", "a.txt"));

        Map<String, Object> replaced = JsArchive.zipCreate("twice.zip",
                List.of(JsArchive.ZipEntrySpec.ofText("a.txt", "2")), true, null, null);
        assertEquals(Boolean.TRUE, replaced.get("replaced"));
        assertArrayEquals(bytes("2"), JsArchive.zipEntry("twice.zip", "a.txt"));
    }

    @Test
    void zipCreateRejectsUnsafeNamesAndBadOptions() {
        enableZipWrite();
        assertThrows(IllegalArgumentException.class, () -> JsArchive.zipCreate("bad.zip",
                List.of(JsArchive.ZipEntrySpec.ofText("../escape.txt", "x")), false, null, null));
        assertThrows(IllegalArgumentException.class, () -> JsArchive.zipCreate("bad.zip",
                List.of(JsArchive.ZipEntrySpec.ofText("/abs.txt", "x")), false, null, null));
        assertThrows(IllegalArgumentException.class, () -> JsArchive.zipCreate("bad.zip",
                List.of(JsArchive.ZipEntrySpec.ofText("back\\slash.txt", "x")), false, null, null));
        assertThrows(IllegalArgumentException.class, () -> JsArchive.zipCreate("bad.zip",
                List.of(JsArchive.ZipEntrySpec.ofText("C:/drive.txt", "x")), false, null, null));
        assertThrows(IllegalArgumentException.class, () -> JsArchive.zipCreate("bad.zip",
                List.of(JsArchive.ZipEntrySpec.ofText("a/../b.txt", "x")), false, null, null));
        assertThrows(IllegalArgumentException.class, () -> JsArchive.zipCreate("bad.zip",
                List.of(JsArchive.ZipEntrySpec.ofText("   ", "x")), false, null, null));
        assertThrows(IllegalArgumentException.class, () -> JsArchive.zipCreate("bad.zip",
                List.of(JsArchive.ZipEntrySpec.ofText("same.txt", "1"),
                        JsArchive.ZipEntrySpec.ofText("same.txt", "2")), false, null, null));
        assertThrows(IllegalArgumentException.class, () -> JsArchive.zipCreate("bad.zip",
                List.of(new JsArchive.ZipEntrySpec("dir/", bytes("not empty"), null, null, null)),
                false, null, null));
        assertThrows(IllegalArgumentException.class, () -> JsArchive.zipCreate("bad.zip",
                List.of(JsArchive.ZipEntrySpec.ofText("a.txt", "x")), false, null, 42));
        assertFalse(Files.exists(tempDir.resolve("bad.zip"), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void writtenEntryNamesAlwaysCarryTheUtf8Flag() throws IOException {
        enableZipWrite();
        JsArchive.zipCreate("utf.zip", List.of(
                JsArchive.ZipEntrySpec.ofText("plain.txt", "a"),
                JsArchive.ZipEntrySpec.ofText("gr\u00fc\u00dfe/\u00e4.txt", "b")), false, null, null);
        List<Integer> flags = localHeaderFlags(Files.readAllBytes(tempDir.resolve("utf.zip")));
        assertEquals(2, flags.size());
        for (int flag : flags) {
            // JDK ZipCoder sets the EFS flag for every UTF-8 name, ASCII ones included.
            assertEquals(0x800, flag & 0x800, "EFS flag missing: 0x" + Integer.toHexString(flag));
        }
        assertEquals(List.of("gr\u00fc\u00dfe/\u00e4.txt", "plain.txt"), names(JsArchive.zipEntries("utf.zip")));
    }

    @Test
    void zipEntryWriteReplacesEntryAndKeepsEverythingElse() throws IOException {
        enableZipWrite();
        writeStructuredZip(tempDir.resolve("keep.zip"));

        Map<String, Object> result = JsArchive.zipEntryWrite("keep.zip", "media/blob.bin",
                bytes("new content"), null, null);
        assertEquals("replaced", result.get("action"));
        assertEquals("media/blob.bin", result.get("name"));
        assertEquals(4, result.get("entriesBefore"));
        assertEquals(4, result.get("entriesAfter"));
        assertEquals(11, result.get("entryBytes"));
        assertEquals("STORED", result.get("method")); // the replaced entry was STORED and stays STORED
        assertEquals(crcHex("new content"), result.get("crc32"));

        try (ZipFile zf = new ZipFile(tempDir.resolve("keep.zip").toFile(), StandardCharsets.UTF_8)) {
            assertEquals(List.of("one.txt", "media/blob.bin", "dir/", "two.txt"), physicalNames(zf));
            assertArrayEquals(bytes("one"), readEntry(zf, "one.txt"));
            assertArrayEquals(bytes("two"), readEntry(zf, "two.txt"));
            assertArrayEquals(bytes("new content"), readEntry(zf, "media/blob.bin"));
            assertEquals(ZipEntry.STORED, zf.getEntry("media/blob.bin").getMethod());
            assertEquals(ZipEntry.DEFLATED, zf.getEntry("one.txt").getMethod());
            assertEquals(Instant.parse("2020-01-01T10:00:00Z").getEpochSecond(),
                    zf.getEntry("one.txt").getLastModifiedTime().toMillis() / 1000);
            assertTrue(zf.getEntry("dir/").isDirectory());
            assertEquals("keep me", zf.getComment());
        }
    }

    @Test
    void zipEntryWriteAppendsNewEntries() throws IOException {
        enableZipWrite();
        writeZip("grow.zip", List.<Object[]>of(new Object[]{"a.txt", bytes("A")}));

        Map<String, Object> added = JsArchive.zipEntryWrite("grow.zip", "new.txt", bytes("N"), null, null);
        assertEquals("added", added.get("action"));
        assertEquals(1, added.get("entriesBefore"));
        assertEquals(2, added.get("entriesAfter"));
        assertEquals("DEFLATED", added.get("method"));

        JsArchive.zipEntryWrite("grow.zip", "raw/new.bin", bytes("R"), "store", 1_600_000_000_000L);
        try (ZipFile zf = new ZipFile(tempDir.resolve("grow.zip").toFile(), StandardCharsets.UTF_8)) {
            assertEquals(3, physicalNames(zf).size());
            assertEquals(ZipEntry.STORED, zf.getEntry("raw/new.bin").getMethod());
            assertEquals(1_600_000_000L, zf.getEntry("raw/new.bin").getLastModifiedTime().toMillis() / 1000);
            assertArrayEquals(bytes("A"), readEntry(zf, "a.txt"));
        }
    }

    @Test
    void zipEntryWriteRefusesContentInDirectoryEntryAndMissingFile() throws IOException {
        enableZipWrite();
        writeZip("dirmix.zip", List.<Object[]>of(new Object[]{"dir/", new byte[0]}));
        assertThrows(IllegalArgumentException.class,
                () -> JsArchive.zipEntryWrite("dirmix.zip", "dir/", bytes("x"), null, null));
        assertThrows(JsUserRuntimeException.class,
                () -> JsArchive.zipEntryWrite("missing.zip", "a.txt", bytes("x"), null, null));
        assertThrows(IllegalArgumentException.class,
                () -> JsArchive.zipEntryWrite("dirmix.zip", "../out.txt", bytes("x"), null, null));
    }

    @Test
    void zipEntryDeleteRemovesOneEntry() throws IOException {
        enableZipWrite();
        writeStructuredZip(tempDir.resolve("del.zip"));

        Map<String, Object> result = JsArchive.zipEntryDelete("del.zip", "media/blob.bin");
        assertEquals("deleted", result.get("action"));
        assertEquals(1, result.get("deleted"));
        assertEquals(4, result.get("entriesBefore"));
        assertEquals(3, result.get("entriesAfter"));
        try (ZipFile zf = new ZipFile(tempDir.resolve("del.zip").toFile(), StandardCharsets.UTF_8)) {
            assertEquals(List.of("one.txt", "dir/", "two.txt"), physicalNames(zf));
            assertNull(zf.getEntry("media/blob.bin"));
            assertArrayEquals(bytes("two"), readEntry(zf, "two.txt"));
        }
    }

    @Test
    void zipEntryDeleteOfUnknownNameFailsWithSuggestionsAndKeepsArchive() throws IOException {
        enableZipWrite();
        writeZip("keepme.zip", List.<Object[]>of(new Object[]{"dir/a.txt", bytes("A")}));
        byte[] before = Files.readAllBytes(tempDir.resolve("keepme.zip"));

        JsUserRuntimeException e = assertThrows(JsUserRuntimeException.class,
                () -> JsArchive.zipEntryDelete("keepme.zip", "dir/a.tx"));
        assertTrue(e.getMessage().contains("not found"), e.getMessage());
        assertTrue(e.getMessage().contains("dir/a.txt"), e.getMessage());
        assertTrue(e.getMessage().contains("zipEntries"), e.getMessage());
        assertArrayEquals(before, Files.readAllBytes(tempDir.resolve("keepme.zip")));
        assertNoTempArchiveFiles();
    }

    @Test
    void rewriteOfArchiveWithDuplicateNamesIsRefused() throws IOException {
        enableZipWrite();
        writeZip("dup.zip", List.<Object[]>of(
                new Object[]{"a.txt", bytes("first")},
                new Object[]{"b.txt", bytes("second")},
                new Object[]{"c.txt", bytes("third")}));
        duplicateEntryName(tempDir.resolve("dup.zip"), "b.txt", "a.txt");
        byte[] before = Files.readAllBytes(tempDir.resolve("dup.zip"));

        // The JDK resolves entry data by name, so two records named 'a.txt' cannot be told
        // apart: copying them could silently write the wrong content.
        JsUserRuntimeException e = assertThrows(JsUserRuntimeException.class,
                () -> JsArchive.zipEntryWrite("dup.zip", "c.txt", bytes("x"), null, null));
        assertTrue(e.getMessage().contains("duplicate entry names"), e.getMessage());
        assertTrue(e.getMessage().contains("a.txt"), e.getMessage());
        assertArrayEquals(before, Files.readAllBytes(tempDir.resolve("dup.zip")));
        assertNoTempArchiveFiles();
    }

    @Test
    void rewriteOfSignedArchiveWarnsAboutTheSignature() throws IOException {
        enableZipWrite();
        writeZip("signed.zip", List.<Object[]>of(
                new Object[]{"META-INF/MANIFEST.MF", bytes("Manifest-Version: 1.0\n")},
                new Object[]{"META-INF/TEST.SF", bytes("Name: a.txt\n")},
                new Object[]{"META-INF/TEST.RSA", bytes("not-a-real-signature")},
                new Object[]{"a.txt", bytes("A")}));

        Map<String, Object> result = JsArchive.zipEntryWrite("signed.zip", "a.txt", bytes("B"), null, null);
        String warnings = String.valueOf(result.get("warnings"));
        assertTrue(warnings.contains("signed"), warnings);
        assertTrue(warnings.contains("META-INF/TEST.SF"), warnings);
        assertArrayEquals(bytes("B"), JsArchive.zipEntry("signed.zip", "a.txt"));
    }

    @Test
    void failedRewriteLeavesOriginalUntouchedAndNoTempFile() throws IOException {
        enableZipWrite();
        byte[] broken = writeArchiveWithBrokenStoredSize();
        Path zip = tempDir.resolve("corrupt.zip");

        JsUserRuntimeException e = assertThrows(JsUserRuntimeException.class,
                () -> JsArchive.zipEntryWrite("corrupt.zip", "other.txt", bytes("new"), null, null));
        List<String> leftover = tempArchiveFiles();
        assertTrue(e.getMessage().contains("corrupt archive"), e.getMessage());
        assertEquals(List.of(), leftover, "temporary archive files left behind");
        assertArrayEquals(broken, Files.readAllBytes(zip));
    }

    @Test
    void rewrittenWorkbookStaysReadableByPoi() throws IOException {
        enableZipWrite();
        Path xlsx = tempDir.resolve("book.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(xlsx)) {
            Sheet sheet = workbook.createSheet("Sheet1");
            sheet.createRow(0).createCell(0).setCellValue("Sales");
            sheet.createRow(1).createCell(0).setCellValue("Region");
            workbook.write(out);
        }
        String part = "xl/sharedStrings.xml";
        byte[] shared = JsArchive.zipEntry("book.xlsx", part);
        assertNotNull(shared, "POI is expected to use a shared string table");

        String xml = new String(shared, StandardCharsets.UTF_8);
        assertTrue(xml.contains(">Sales<"), xml);
        Map<String, Object> result = JsArchive.zipEntryWrite("book.xlsx", part,
                xml.replace(">Sales<", ">Revenue<").getBytes(StandardCharsets.UTF_8), null, null);
        assertEquals("replaced", result.get("action"));

        try (XSSFWorkbook reopened = new XSSFWorkbook(Files.newInputStream(xlsx))) {
            Cell first = reopened.getSheetAt(0).getRow(0).getCell(0);
            Cell second = reopened.getSheetAt(0).getRow(1).getCell(0);
            assertEquals("Revenue", first.getStringCellValue());
            assertEquals("Region", second.getStringCellValue());
        }
    }

    // ========================================================================
    // Test helpers
    // ========================================================================

    /**
     * Reads a classpath resource (e.g. a sample archive in src/test/resources) into a byte array.
     */
    private static byte[] readResource(String name) throws IOException {
        try (InputStream in = JsArchiveTest.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException("classpath resource not found: " + name);
            }
            return in.readAllBytes();
        }
    }

    private void writeZip(String name, List<Object[]> entries) throws IOException {
        Path zip = tempDir.resolve(name);
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip), StandardCharsets.UTF_8)) {
            for (Object[] entry : entries) {
                zos.putNextEntry(new ZipEntry((String) entry[0]));
                zos.write((byte[]) entry[1]);
                zos.closeEntry();
            }
        }
    }

    // ---- helpers for the ZIP write tests -----------------------------------

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> names(List<Map<String, Object>> entries) {
        return entries.stream().map(e -> (String) e.get("name")).toList();
    }

    /** Entry names in the order they appear in the central directory. */
    private static List<String> physicalNames(ZipFile zf) {
        return zf.stream().map(ZipEntry::getName).toList();
    }

    private static byte[] readEntry(ZipFile zf, String name) throws IOException {
        ZipEntry entry = zf.getEntry(name);
        assertNotNull(entry, "entry not found: " + name);
        try (InputStream in = zf.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    private static String crcHex(String text) {
        CRC32 crc = new CRC32();
        crc.update(text.getBytes(StandardCharsets.UTF_8));
        return String.format("%08x", crc.getValue());
    }

    /**
     * Writes an archive holding everything a rewrite has to preserve: entry order, mixed
     * compression methods, a directory entry, an explicit modification time and a comment
     * on the archive itself.
     */
    private static void writeStructuredZip(Path zip) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry one = new ZipEntry("one.txt");
            one.setLastModifiedTime(FileTime.from(Instant.parse("2020-01-01T10:00:00Z")));
            zos.putNextEntry(one);
            zos.write(bytes("one"));
            zos.closeEntry();

            byte[] blob = bytes("binary-blob-content");
            CRC32 crc = new CRC32();
            crc.update(blob);
            ZipEntry stored = new ZipEntry("media/blob.bin");
            stored.setMethod(ZipEntry.STORED);
            stored.setSize(blob.length);
            stored.setCrc(crc.getValue());
            zos.putNextEntry(stored);
            zos.write(blob);
            zos.closeEntry();

            ZipEntry dir = new ZipEntry("dir/");
            dir.setMethod(ZipEntry.STORED);
            dir.setSize(0);
            dir.setCrc(0);
            zos.putNextEntry(dir);
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("two.txt"));
            zos.write(bytes("two"));
            zos.closeEntry();

            zos.setComment("keep me");
        }
    }

    /**
     * Writes an archive whose central directory declares 5000 bytes for a 5 byte STORED
     * entry, so copying that entry must fail. Returns the (broken but valid ZIP structure)
     * file content for the "original unchanged" comparison.
     */
    private byte[] writeArchiveWithBrokenStoredSize() throws IOException {
        Path zip = tempDir.resolve("corrupt.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip), StandardCharsets.UTF_8)) {
            byte[] raw = bytes("12345");
            CRC32 crc = new CRC32();
            crc.update(raw);
            ZipEntry stored = new ZipEntry("blob.bin");
            stored.setMethod(ZipEntry.STORED);
            stored.setSize(raw.length);
            stored.setCrc(crc.getValue());
            zos.putNextEntry(stored);
            zos.write(raw);
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("other.txt"));
            zos.write(bytes("O"));
            zos.closeEntry();
        }
        patchCentralDirectorySizes(zip, "blob.bin", 5000);
        return Files.readAllBytes(zip);
    }

    /** Overwrites compressed and uncompressed size of a central directory entry. */
    private static void patchCentralDirectorySizes(Path zip, String name, long size) throws IOException {
        byte[] data = Files.readAllBytes(zip);
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        boolean patched = false;
        for (int i = 0; i + 46 <= data.length; i++) {
            if (isCentralHeader(data, i)) {
                int nameLength = littleEndian16(data, i + 28);
                if (nameLength == nameBytes.length && matches(data, i + 46, nameBytes)) {
                    writeLittleEndian32(data, i + 20, size); // compressed size
                    writeLittleEndian32(data, i + 24, size); // uncompressed size
                    patched = true;
                }
            }
        }
        assertTrue(patched, "central directory entry not found: " + name);
        Files.write(zip, data);
    }

    /**
     * Renames an entry in both its local and its central header, producing an archive that
     * holds the same name twice (something {@code ZipOutputStream} itself refuses to write).
     */
    private static void duplicateEntryName(Path zip, String from, String to) throws IOException {
        byte[] data = Files.readAllBytes(zip);
        byte[] fromBytes = from.getBytes(StandardCharsets.UTF_8);
        byte[] toBytes = to.getBytes(StandardCharsets.UTF_8);
        assertEquals(fromBytes.length, toBytes.length, "renaming requires equal name length");
        int changed = 0;
        for (int i = 0; i + 30 <= data.length; i++) {
            int nameOffset;
            int nameLength;
            if (isLocalHeader(data, i)) {
                nameOffset = i + 30;
                nameLength = littleEndian16(data, i + 26);
            } else if (isCentralHeader(data, i)) {
                nameOffset = i + 46;
                nameLength = littleEndian16(data, i + 28);
            } else {
                continue;
            }
            if (nameLength == fromBytes.length && matches(data, nameOffset, fromBytes)) {
                System.arraycopy(toBytes, 0, data, nameOffset, toBytes.length);
                changed++;
            }
        }
        assertEquals(2, changed, "expected one local and one central header to be renamed");
        Files.write(zip, data);
    }

    /** General purpose flags of all local file headers. */
    private static List<Integer> localHeaderFlags(byte[] data) {
        List<Integer> flags = new ArrayList<>();
        for (int i = 0; i + 30 <= data.length; i++) {
            if (isLocalHeader(data, i)) {
                flags.add(littleEndian16(data, i + 6));
            }
        }
        return flags;
    }

    private static boolean isLocalHeader(byte[] data, int i) {
        return data[i] == 0x50 && data[i + 1] == 0x4b && data[i + 2] == 0x03 && data[i + 3] == 0x04;
    }

    private static boolean isCentralHeader(byte[] data, int i) {
        return data[i] == 0x50 && data[i + 1] == 0x4b && data[i + 2] == 0x01 && data[i + 3] == 0x02;
    }

    private static boolean matches(byte[] data, int offset, byte[] expected) {
        if (offset + expected.length > data.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (data[offset + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private static int littleEndian16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static void writeLittleEndian32(byte[] data, int offset, long value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >> 8) & 0xFF);
        data[offset + 2] = (byte) ((value >> 16) & 0xFF);
        data[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private List<String> tempArchiveFiles() throws IOException {
        try (var stream = Files.list(tempDir)) {
            return stream.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".ziptmp"))
                    .sorted()
                    .toList();
        }
    }

    private void assertNoTempArchiveFiles() throws IOException {
        assertEquals(List.of(), tempArchiveFiles(), "temporary archive files left behind");
    }

    private void writeTar(String name, TarWriter writer) throws IOException {
        try (OutputStream out = Files.newOutputStream(tempDir.resolve(name))) {
            writer.write(out);
        }
    }

    @FunctionalInterface
    private interface TarWriter {
        void write(OutputStream out) throws IOException;
    }

    private static void writeTarEnd(OutputStream out) throws IOException {
        out.write(new byte[TAR_BLOCK_SIZE * 2]);
    }

    private static void writeTarHeader(OutputStream out, char type, String name, long size,
                                       String linkName, int mode) throws IOException {
        byte[] h = new byte[TAR_BLOCK_SIZE];
        putAscii(h, 0, 100, name);
        putOctal(h, 100, 8, mode);
        putOctal(h, 108, 8, 0);      // uid
        putOctal(h, 116, 8, 0);      // gid
        putOctal(h, 124, 12, size);
        putOctal(h, 136, 12, 1700000000L); // mtime
        h[156] = (byte) type;
        putAscii(h, 157, 100, linkName != null ? linkName : "");
        putAscii(h, 257, 6, "ustar\0");
        putAscii(h, 263, 2, "00");

        // Checksum over the header with the checksum field set to spaces.
        Arrays.fill(h, 148, 156, (byte) ' ');
        long sum = 0;
        for (byte x : h) {
            sum += (x & 0xFF);
        }
        byte[] chk = String.format("%06o", sum).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(chk, 0, h, 148, chk.length);
        h[154] = 0;
        h[155] = ' ';
        out.write(h);
    }

    private static void writeTarData(OutputStream out, byte[] data) throws IOException {
        out.write(data);
        int pad = (TAR_BLOCK_SIZE - (data.length % TAR_BLOCK_SIZE)) % TAR_BLOCK_SIZE;
        if (pad > 0) {
            out.write(new byte[pad]);
        }
    }

    private static void putAscii(byte[] h, int off, int len, String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, h, off, Math.min(len, b.length));
    }

    private static void putOctal(byte[] h, int off, int len, long value) {
        String s = String.format("%0" + (len - 1) + "o", value);
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, h, off, b.length);
        h[off + len - 1] = 0;
    }
}
