package org.rogmann.mcp2sdk.m2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the polyglot-free parts of {@link M2Resolver}: handle forms, repository
 * path parsing, entry-name validation, glob matching and JAR reads (read-only).
 * No Maven process is started.
 */
class M2ResolverTest {

    // ---------------------------------------------------------------
    // isCoordinateForm
    // ---------------------------------------------------------------

    @Test
    void coordinateFormAccepted() {
        assertTrue(M2Resolver.isCoordinateForm("org.springframework:spring-core:7.0.8"));
        assertTrue(M2Resolver.isCoordinateForm("io.modelcontextprotocol.sdk:mcp-core:1.1.3"));
    }

    @Test
    void windowsPathIsNotACoordinate() {
        assertFalse(M2Resolver.isCoordinateForm("C:\\m2\\org\\spring\\spring-core-7.0.8.jar"));
    }

    @Test
    void unixPathIsNotACoordinate() {
        assertFalse(M2Resolver.isCoordinateForm("/home/x/.m2/repository/a/b/c.jar"));
    }

    @Test
    void wrongPartCountIsNotACoordinate() {
        assertFalse(M2Resolver.isCoordinateForm("a:b"));
        assertFalse(M2Resolver.isCoordinateForm("a:b:c:d"));
    }

    // ---------------------------------------------------------------
    // entry name validation
    // ---------------------------------------------------------------

    @Test
    void entryNamesAreValidated() throws IOException {
        assertEquals("a/b/C.class", M2Resolver.normalizeEntryName("a/b/C.class"));
        assertEquals("a/b/C.class", M2Resolver.normalizeEntryName("a\\b\\C.class"));
        assertThrows(IOException.class, () -> M2Resolver.normalizeEntryName("../secret"));
        assertThrows(IOException.class, () -> M2Resolver.normalizeEntryName("/abs/C.class"));
        assertThrows(IOException.class, () -> M2Resolver.normalizeEntryName("a//C.class"));
        assertThrows(IOException.class, () -> M2Resolver.normalizeEntryName(null));
    }

    // ---------------------------------------------------------------
    // glob matching of entry names
    // ---------------------------------------------------------------

    @Test
    void globSemantics() {
        assertTrue(M2Resolver.globMatches("**/SpringVersion.class",
                "org/springframework/core/SpringVersion.class"));
        assertTrue(M2Resolver.globMatches("*.class", "Foo.class"));
        assertFalse(M2Resolver.globMatches("*.class", "a/Foo.class"));
        assertTrue(M2Resolver.globMatches("WEB-INF/*.xml", "WEB-INF/web.xml"));
        assertTrue(M2Resolver.globMatches("**", "a/b/c.txt"));
        assertTrue(M2Resolver.globMatches("a/b/C.class", "a/b/C.class")); // dot is literal
        assertFalse(M2Resolver.globMatches("aXb", "axb")); // case sensitive
    }

    // ---------------------------------------------------------------
    // artifactFromJarPath
    // ---------------------------------------------------------------

    @Test
    void artifactFromRepositoryLayout(@TempDir Path tmp) throws IOException {
        Path repo = tmp.resolve("repository");
        Path jar = repo.resolve("org/springframework/spring-core/7.0.8/spring-core-7.0.8.jar");
        Files.createDirectories(jar.getParent());
        Files.write(jar, new byte[] { 'P', 'K' });

        M2Resolver.Artifact a = M2Resolver.artifactFromJarPath(jar, repo);
        if (a == null) {
            // Only expected on systems where the temp dir cannot contain the layout (never
            // in practice); keep the test honest instead of passing silently.
            throw new AssertionError("artifact not parsed from " + jar);
        }
        assertEquals("org.springframework", a.groupId());
        assertEquals("spring-core", a.artifactId());
        assertEquals("7.0.8", a.version());
        assertEquals("org.springframework:spring-core:7.0.8", a.gav());
    }

    @Test
    void foreignOrUnparsablePathIsNoArtifact(@TempDir Path tmp) throws IOException {
        Path repo = tmp.resolve("repository");
        Files.createDirectories(repo);

        // Outside the repository: cannot be relativized to the repo layout.
        Path foreign = tmp.resolve("elsewhere/lib/1.0/lib-1.0.jar");
        Files.createDirectories(foreign.getParent());
        Files.write(foreign, new byte[] { 'P', 'K' });
        assertNull(M2Resolver.artifactFromJarPath(foreign, repo));

        // Inside the repo but not the 4-level layout (missing version directory).
        Path flat = repo.resolve("com/acme/odd.jar");
        Files.createDirectories(flat.getParent());
        Files.write(flat, new byte[] { 'P', 'K' });
        assertNull(M2Resolver.artifactFromJarPath(flat, repo));

        // Inside the repo, layout correct, but the file name does not start with
        // artifactId-version (e.g. a shaded or renamed JAR).
        Path renamed = repo.resolve("com/acme/odd/1.0/random-name.jar");
        Files.createDirectories(renamed.getParent());
        Files.write(renamed, new byte[] { 'P', 'K' });
        assertNull(M2Resolver.artifactFromJarPath(renamed, repo));
    }

    // ---------------------------------------------------------------
    // read-only JAR access (against a JAR built in the test)
    // ---------------------------------------------------------------

    @Test
    void readAndListEntriesReadOnly(@TempDir Path tmp) throws IOException {
        // Build a repository layout: <tmp>/repository/g/r/o/up/artifact/1.0/artifact-1.0.jar
        Path repo = tmp.resolve("repository");
        Path jar = repo.resolve("g/r/o/up/artifact/1.0/artifact-1.0.jar");
        Files.createDirectories(jar.getParent());
        byte[] payload = "hello m2".getBytes();
        writeJar(jar, "g/r/o/up/Foo.class", payload);

        // Remember current property value so the test does not leak configuration.
        String oldRepo = System.getProperty("tools.m2.repository");
        System.setProperty("tools.m2.repository", repo.toString());
        try {
            String handle = "g.r.o.up:artifact:1.0";
            assertArrayEquals(payload, M2Resolver.readEntry(handle, "g/r/o/up/Foo.class", 0));

            var listed = M2Resolver.listEntries(handle, null, 10);
            assertEquals(1, listed.get("total"));
            assertEquals(Boolean.FALSE, listed.get("truncated"));

            // Too-large entries are rejected by the declared size...
            assertThrows(IOException.class,
                    () -> M2Resolver.readEntry(handle, "g/r/o/up/Foo.class", 4));

            // ...and a miss names the entry and points to listEntries.
            IOException miss = assertThrows(IOException.class,
                    () -> M2Resolver.readEntry(handle, "g/r/o/up/Missing.class", 0));
            assertTrue(miss.getMessage().contains("listEntries"));

            // Sources classifier: not present -> helpful error pointing to m2.sources.
            IOException noSrc = assertThrows(IOException.class,
                    () -> M2Resolver.resolveJar(handle, "sources"));
            assertTrue(noSrc.getMessage().contains("m2.sources"));

            // Path form works too, and traversal outside the repository is rejected.
            assertArrayEquals(payload,
                    M2Resolver.readEntry(jar.toString(), "g/r/o/up/Foo.class", 0));
            assertThrows(IOException.class,
                    () -> M2Resolver.resolveJar(tmp.resolve("evil.jar").toString(), null));
        } finally {
            if (oldRepo == null) {
                System.clearProperty("tools.m2.repository");
            } else {
                System.setProperty("tools.m2.repository", oldRepo);
            }
        }
    }

    /** Writes a one-entry JAR with correct size/CRC so that JarFile reports the declared size. */
    private static void writeJar(Path jar, String entryName, byte[] payload) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(payload);
        JarEntry entry = new JarEntry(entryName);
        entry.setSize(payload.length);
        entry.setCompressedSize(payload.length);
        entry.setMethod(JarEntry.STORED);
        entry.setCrc(crc.getValue());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(bytes)) {
            jos.putNextEntry(entry);
            jos.write(payload);
            jos.closeEntry();
        }
        Files.write(jar, bytes.toByteArray());
    }
}
