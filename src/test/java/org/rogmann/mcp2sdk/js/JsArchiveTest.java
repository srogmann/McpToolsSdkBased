package org.rogmann.mcp2sdk.js;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link JsArchive} (ZIP and tar listing/extraction and security).
 */
class JsArchiveTest {

    private static final int TAR_BLOCK_SIZE = 512;

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
