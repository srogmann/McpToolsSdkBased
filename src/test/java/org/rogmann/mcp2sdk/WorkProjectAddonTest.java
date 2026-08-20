package org.rogmann.mcp2sdk;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the add-on directory support in {@link WorkProject}:
 * resolution order, startup validation and the LLM-facing project list.
 */
class WorkProjectAddonTest {

    @TempDir
    Path projectBase;

    @TempDir
    Path addonParent1;

    @TempDir
    Path addonParent2;

    /** The add-on directory with folder name "repository". */
    Path repository;

    private String oldProjectDir;
    private String oldFilter;

    @BeforeEach
    void setUp() throws Exception {
        oldProjectDir = System.getProperty("IDE_PROJECT_DIR");
        oldFilter = System.getProperty("IDE_PROJECT_FILTER");

        System.setProperty("IDE_PROJECT_DIR", projectBase.toString());
        repository = addonParent1.resolve("repository");
        Files.createDirectories(repository);
        // WorkProject stores canonical addon paths (toRealPath); canonicalize here so
        // path equality assertions hold on all platforms (e.g. /var -> /private/var).
        repository = repository.toRealPath();

        // Clear any stale cache from previous tests.
        WorkProject.resetAddonConfiguration();
    }

    @AfterEach
    void tearDown() {
        restore("IDE_PROJECT_DIR", oldProjectDir);
        restore("IDE_PROJECT_FILTER", oldFilter);
        clearAddonProperties();
        WorkProject.resetAddonConfiguration();
    }

    /** Clears all add-on properties (any index), so that tests stay independent. */
    private static void clearAddonProperties() {
        for (String name : System.getProperties().stringPropertyNames()) {
            if (name.startsWith("IDE_PROJECT_ADDON_DIR.")
                    || name.startsWith("IDE_PROJECT_ADDON_DESC.")) {
                System.clearProperty(name);
            }
        }
    }

    private static void restore(String key, String value) {
        if (value != null) {
            System.setProperty(key, value);
        } else {
            System.clearProperty(key);
        }
    }

    private static Map<String, Object> result() {
        return new HashMap<>();
    }

    // ------------------------------------------------------------------
    // Resolution order
    // ------------------------------------------------------------------

    @Test
    void blankNameWithoutFilterResolvesToBase() {
        WorkProject wp = WorkProject.lookupProject(result());
        assertNotNull(wp);
        assertEquals(projectBase, wp.projectBaseDir());
        assertEquals(projectBase, wp.projectDir());
        assertEquals(projectBase.getFileName().toString(), wp.projectName());
    }

    @Test
    void blankNameWithFilterFails() {
        System.setProperty("IDE_PROJECT_FILTER", ".*");
        WorkProject.resetAddonConfiguration();

        Map<String, Object> r = result();
        assertNull(WorkProject.lookupProject(r));
        assertEquals("Project name is required.", r.get("error"));
    }

    @Test
    void blankNameResolvesToBaseEvenWithAddonsConfigured() {
        configureRepository();
        WorkProject wp = WorkProject.lookupProject(result());
        assertNotNull(wp);
        assertEquals(projectBase, wp.projectDir());
        assertEquals(projectBase.getFileName().toString(), wp.projectName());
    }

    @Test
    void addonNameResolvesToAddon() {
        configureRepository();
        Map<String, Object> r = result();
        WorkProject wp = WorkProject.lookupProject("repository", r);
        assertNotNull(wp);
        assertEquals(repository, wp.projectBaseDir());
        assertEquals(repository, wp.projectDir());
        assertEquals("repository", wp.projectName());
        assertFalse(r.containsKey("error"));
    }

    @Test
    void addonWinsEvenWhenFilterWouldRejectTheName() {
        configureRepository();
        System.setProperty("IDE_PROJECT_FILTER", "proj.*");
        WorkProject.resetAddonConfiguration();

        WorkProject wp = WorkProject.lookupProject("repository", result());
        assertNotNull(wp);
        assertEquals("repository", wp.projectName());
    }

    @Test
    void subDirectoryResolvesAsSubproject() throws Exception {
        Files.createDirectories(projectBase.resolve("sub"));
        WorkProject wp = WorkProject.lookupProject("sub", result());
        assertNotNull(wp);
        assertEquals(projectBase, wp.projectBaseDir());
        assertEquals(projectBase.resolve("sub"), wp.projectDir());
        assertEquals("sub", wp.projectName());
    }

    @Test
    void unknownProjectFails() {
        Map<String, Object> r = result();
        assertNull(WorkProject.lookupProject("doesNotExist", r));
        assertNotNull(r.get("error"));
    }

    @Test
    void baseNameWithoutFilterResolvesToBase() {
        String baseName = projectBase.getFileName().toString();
        WorkProject wp = WorkProject.lookupProject(baseName, result());
        assertNotNull(wp);
        assertEquals(projectBase, wp.projectDir());
    }

    // ------------------------------------------------------------------
    // Startup validation
    // ------------------------------------------------------------------

    @Test
    void missingAddonDescriptionAborts() {
        System.setProperty("IDE_PROJECT_ADDON_DIR.1", repository.toString());
        WorkProject.resetAddonConfiguration();
        assertThrows(WorkProject.InvalidAddonConfiguration.class,
                WorkProject::validateAddonConfiguration);
    }

    @Test
    void nonExistentAddonPathAborts() {
        System.setProperty("IDE_PROJECT_ADDON_DIR.1", projectBase.resolve("nope").toString());
        System.setProperty("IDE_PROJECT_ADDON_DESC.1", "missing folder");
        WorkProject.resetAddonConfiguration();
        assertThrows(WorkProject.InvalidAddonConfiguration.class,
                WorkProject::validateAddonConfiguration);
    }

    @Test
    void duplicateAddonFolderNameAborts() throws Exception {
        Path secondRepository = addonParent2.resolve("repository");
        Files.createDirectories(secondRepository);
        System.setProperty("IDE_PROJECT_ADDON_DIR.1", repository.toString());
        System.setProperty("IDE_PROJECT_ADDON_DESC.1", "first repo");
        System.setProperty("IDE_PROJECT_ADDON_DIR.2", secondRepository.toString());
        System.setProperty("IDE_PROJECT_ADDON_DESC.2", "second repo");
        WorkProject.resetAddonConfiguration();
        assertThrows(WorkProject.InvalidAddonConfiguration.class,
                WorkProject::validateAddonConfiguration);
    }

    // ------------------------------------------------------------------
    // LLM-facing lists
    // ------------------------------------------------------------------

    @Test
    void listProjectsContainsAddonButNoAbsolutePath() {
        configureRepository();

        Map<String, String> projects = WorkProject.listProjects();
        assertTrue(projects.containsKey("repository"));
        assertEquals("test m2 repository", projects.get("repository"));
        // No absolute path may leak to the LLM.
        String values = String.join(" ", projects.values());
        assertFalse(values.contains(repository.toString()));
        assertFalse(values.contains(projectBase.toString()));

        String description = WorkProject.projectNameDescription();
        assertTrue(description.contains("repository"));
        assertFalse(description.contains(repository.toString()));
    }

    @Test
    void listAddonsOnlyContainsAddons() {
        configureRepository();
        Map<String, String> addons = WorkProject.listAddons();
        assertEquals(Map.of("repository", "test m2 repository"), addons);
    }

    // ------------------------------------------------------------------
    // Helper
    // ------------------------------------------------------------------

    private void configureRepository() {
        System.setProperty("IDE_PROJECT_ADDON_DIR.1", repository.toString());
        System.setProperty("IDE_PROJECT_ADDON_DESC.1", "test m2 repository");
        WorkProject.resetAddonConfiguration();
    }
}
