package org.rogmann.mcp2sdk.js;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the {@code /addonName/...} mount prefix in {@link JsFileSystem}.
 * Verifies that optionally configured add-on directories are addressable via the
 * mount prefix while project-base files keep the previous, prefix-less behaviour,
 * and that no absolute path leaks into results/help.
 */
class JsFileSystemAddonTest {

    @TempDir
    Path projectDir;

    @TempDir
    Path addonParent;

    /** The add-on directory with folder name "repository". */
    Path repository;

    private String oldProjectDir;

    @BeforeEach
    void setUp() throws Exception {
        oldProjectDir = System.getProperty("IDE_PROJECT_DIR");

        repository = addonParent.resolve("repository");
        Files.createDirectories(repository);
        // WorkProject stores canonical addon paths (toRealPath); canonicalize here so the
        // addon mount matches exactly (e.g. /var -> /private/var).
        repository = repository.toRealPath();
        Files.createDirectories(repository.resolve("org/slf4j"));
        Files.writeString(repository.resolve("org/slf4j/api.txt"), "slf4j-api");

        System.setProperty("IDE_PROJECT_DIR", projectDir.toString());
        System.setProperty("IDE_PROJECT_ADDON_DIR.1", repository.toString());
        System.setProperty("IDE_PROJECT_ADDON_DESC.1", "test m2 repository");
        org.rogmann.mcp2sdk.WorkProject.resetAddonConfiguration();
    }

    @AfterEach
    void tearDown() {
        restore("IDE_PROJECT_DIR", oldProjectDir);
        for (String name : System.getProperties().stringPropertyNames()) {
            if (name.startsWith("IDE_PROJECT_ADDON_DIR.")
                    || name.startsWith("IDE_PROJECT_ADDON_DESC.")) {
                System.clearProperty(name);
            }
        }
        org.rogmann.mcp2sdk.WorkProject.resetAddonConfiguration();
    }

    private static void restore(String key, String value) {
        if (value != null) {
            System.setProperty(key, value);
        } else {
            System.clearProperty(key);
        }
    }

    @Test
    void addonMountWriteReadRoundTrip() {
        JsFileSystem.writeFile("/repository/dir/a.txt", "content");
        assertTrue(JsFileSystem.exists("/repository/dir/a.txt"));
        assertTrue(JsFileSystem.isFile("/repository/dir/a.txt"));
        assertEquals("content", JsFileSystem.readFile("/repository/dir/a.txt"));

        List<String> paths = JsFileSystem.listFiles("/repository");
        assertTrue(paths.contains("/repository/dir"), "expected prefixed entry, got: " + paths);
        List<String> dirPaths = JsFileSystem.listFiles("/repository/dir");
        assertEquals(List.of("/repository/dir/a.txt"), dirPaths);
    }

    @Test
    void addonReaddirAndListFilesUseMountPrefix() {
        List<String> names = JsFileSystem.readdir("/repository");
        assertTrue(names.contains("org"), "expected 'org', got: " + names);

        List<String> paths = JsFileSystem.listFiles("/repository/org/slf4j");
        assertTrue(paths.contains("/repository/org/slf4j/api.txt"), "expected prefixed path, got: " + paths);

        assertEquals("slf4j-api", JsFileSystem.readFile("/repository/org/slf4j/api.txt"));
    }

    @Test
    void statAddonRootReturnsMountPath() {
        Map<String, Object> stat = JsFileSystem.stat("/repository");
        assertEquals("/repository", stat.get("path"));
        assertEquals("repository", stat.get("name"));
        assertEquals(Boolean.TRUE, stat.get("isDirectory"));
    }

    @Test
    void unknownAddonIsRejected() {
        JsUserRuntimeException ex = assertThrows(JsUserRuntimeException.class,
                () -> JsFileSystem.readFile("/unknownAddon/x.txt"));
        assertTrue(ex.getMessage().contains("Unknown add-on folder"));
        assertTrue(ex.getMessage().contains("repository"));
    }

    @Test
    void absolutePathWithoutAddonsConfiguredIsRejected() {
        // Hide the add-on configuration: only the project base remains.
        restore("IDE_PROJECT_ADDON_DIR.1", null);
        restore("IDE_PROJECT_ADDON_DESC.1", null);
        org.rogmann.mcp2sdk.WorkProject.resetAddonConfiguration();

        JsUserRuntimeException ex = assertThrows(JsUserRuntimeException.class,
                () -> JsFileSystem.readFile("/etc/passwd"));
        assertTrue(ex.getMessage().contains("no add-on folders configured"));
    }

    @Test
    void addonTraversalIsRejected() {
        assertThrows(JsUserRuntimeException.class,
                () -> JsFileSystem.readFile("/repository/../../outside.txt"));
    }

    @Test
    void projectFilesKeepPreviousBehaviour() {
        JsFileSystem.writeFile("a.txt", "project");
        assertEquals("project", JsFileSystem.readFile("a.txt"));
        List<String> names = JsFileSystem.readdir(".");
        // Project-base entries are listed without a mount prefix.
        assertTrue(names.contains("a.txt"));
        assertFalse(names.stream().anyMatch(n -> n.startsWith("/")));
    }

    @Test
    void helpDocumentsAddonsWithoutAbsolutePath() {
        String help = JsFileSystem.help();
        assertTrue(help.contains("/repository"), "help should mention the add-on mount");
        assertTrue(help.contains("test m2 repository"), "help should mention the add-on description");
        assertFalse(help.contains(repository.toString()), "help must not contain an absolute add-on path");
    }
}
