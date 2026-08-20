package org.rogmann.mcp2sdk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Represents a validated work project with base directory and project directory.
 * Provides a static lookup method to validate and resolve project directories.
 *
 * <p>There are three cases:</p>
 * <ul>
 *     <li>projectBaseDir == projectDir, projectName is the name of the projectBaseDir</li>
 *     <li>projectDir is a subdirectory of projectBaseDir, projectName is the name of projectDir</li>
 *     <li>projectDir is an add-on directory (see {@code IDE_PROJECT_ADDON_DIR.N}),
 *         projectBaseDir == projectDir, projectName is the add-on folder name</li>
 * </ul>
 *
 * <h3>Add-on directories ({@code IDE_PROJECT_ADDON_DIR.N})</h3>
 * <p>
 * Optionally, additional read/write roots can be configured by numbered system properties,
 * e.g. {@code -DIDE_PROJECT_ADDON_DIR.1=/path/to/.m2/repository}
 * together with a mandatory description
 * {@code -DIDE_PROJECT_ADDON_DESC.1=local m2-repository ...}.
 * The add-on folder name (basename of the configured path) is used as the project/mount name.
 * A description is required for every add-on directory so that <em>no absolute path is exposed
 * to the LLM</em>.
 * </p>
 * <p>Resolution order of {@link #lookupProject(String, Map)} is fixed, independent of any filter:</p>
 * <ol>
 *     <li>blank name and no {@code IDE_PROJECT_FILTER} &rarr; the project base directory itself</li>
 *     <li>blank name and a filter &rarr; error ("Project name is required")</li>
 *     <li>name equals an add-on folder name &rarr; the add-on directory</li>
 *     <li>otherwise &rarr; the usual project logic (filter + {@code IDE_PROJECT_DIR/name})</li>
 * </ol>
 *
 * @param projectBaseDir base directory
 * @param projectDir project directory (may be an add-on directory)
 * @param projectName project name
 */
public record WorkProject(Path projectBaseDir, Path projectDir, String projectName) {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkProject.class);

    /** Property prefix for add-on directories: {@code IDE_PROJECT_ADDON_DIR.<index>}. */
    static final String PROP_ADDON_DIR_PREFIX = "IDE_PROJECT_ADDON_DIR.";
    /** Property prefix for add-on descriptions: {@code IDE_PROJECT_ADDON_DESC.<index>}. */
    static final String PROP_ADDON_DESC_PREFIX = "IDE_PROJECT_ADDON_DESC.";

    /**
     * An add-on directory: folder name (mount / project name), canonical path and
     * a description for the LLM (never an absolute path).
     * @param name folder name (basename of the configured path)
     * @param path canonical absolute path of the add-on directory
     * @param description free-text description for the LLM
     */
    private record AddonDir(String name, Path path, String description) {}

    /** Parsed add-on configuration (name -&gt; addon), lazily initialized. */
    private static volatile Map<String, AddonDir> addonDirByName;

    /** Parsed, sorted add-on names (for stable output). */
    private static volatile List<String> addonNamesOrdered;

    /**
     * Signals a fatal misconfiguration of the add-on directories (e.g. a missing
     * description or a duplicate folder name). Thrown during startup validation.
     */
    public static class InvalidAddonConfiguration extends RuntimeException {
        /**
         * Creates the exception.
         * @param message error message
         */
        public InvalidAddonConfiguration(String message) {
            super(message);
        }
    }

    /**
     * Looks up the base directory.
     * Validates system properties, directory existence and path traversal.
     *
     * @param result a map to store error messages if validation fails
     * @return a WorkProject instance if validation succeeds, null otherwise
     */
    public static WorkProject lookupProject(Map<String, Object> result) {
        return lookupProject(null, result);
    }

    /**
     * Determines whether a caller-supplied project name is required to identify the project.
     * <p>
     * This is the case when {@code IDE_PROJECT_DIR} points to a container directory with
     * several possible projects, i.e. when an {@code IDE_PROJECT_FILTER} is configured:
     * in that configuration {@link #lookupProject(Map)} fails without a name.
     * In a single-project setup ({@code IDE_PROJECT_DIR} set directly to the project
     * directory and no filter) no project name is needed.
     * </p>
     * @return true if an explicit project name must be supplied by the caller
     */
    public static boolean needsProjectName() {
        String projectFilterProp = System.getProperty("IDE_PROJECT_FILTER");
        return projectFilterProp != null && !projectFilterProp.isBlank();
    }

    /**
     * Determines whether at least one add-on directory is configured
     * ({@code IDE_PROJECT_ADDON_DIR.N}).
     * <p>
     * If true, a project name may (optionally) be supplied to select an add-on
     * directory, in addition to the usual project directories.
     * </p>
     * @return true if add-on directories are configured
     */
    public static boolean hasAddonDirectories() {
        parseAddonConfiguration();
        return !addonDirByName.isEmpty();
    }

    /**
     * Returns the sorted names of the configured add-on directories (folder names).
     * @return sorted add-on names (empty if none configured)
     */
    public static List<String> addonNames() {
        parseAddonConfiguration();
        return addonNamesOrdered;
    }

    /**
     * Returns the canonical absolute path of an add-on directory by name.
     * @param name add-on folder name
     * @return canonical path, or null if not configured
     */
    public static Path addonPath(String name) {
        parseAddonConfiguration();
        AddonDir addon = addonDirByName.get(name);
        return addon != null ? addon.path() : null;
    }

    /**
     * Returns the configured add-on directories as {@code name -&gt; description}.
     * <p>
     * Descriptions are supplied by the administrator ({@code IDE_PROJECT_ADDON_DESC.N})
     * and never contain absolute paths, so that the map can safely be handed to an LLM.
     * </p>
     * @return add-on names with their descriptions (empty if none configured)
     */
    public static Map<String, String> listAddons() {
        parseAddonConfiguration();
        Map<String, String> result = new LinkedHashMap<>();
        for (String name : addonNamesOrdered) {
            result.put(name, addonDirByName.get(name).description());
        }
        return result;
    }

    /**
     * Returns all caller-selectable targets as {@code name -&gt; short description}.
     * <p>
     * In a single-project setup (no {@code IDE_PROJECT_FILTER}) this includes the main
     * project (base directory) plus all add-on directories. If a filter is configured the
     * project sub-directories cannot be enumerated without scanning the base directory,
     * so only the add-on directories are returned.
     * </p>
     * @return selectable target names with descriptions (never absolute paths)
     */
    public static Map<String, String> listProjects() {
        parseAddonConfiguration();
        Map<String, String> result = new LinkedHashMap<>();
        String baseProjectName = projectBaseName();
        if (baseProjectName != null && !needsProjectName()) {
            result.put(baseProjectName, "Main project directory");
        }
        result.putAll(listAddons());
        return result;
    }

    /**
     * Builds a short description of the available targets for tool schema parameter
     * descriptions (e.g. the {@code projectName} parameter). Never contains absolute paths.
     * @return description text
     */
    public static String projectNameDescription() {
        Map<String, String> projects = listProjects();
        if (projects.isEmpty()) {
            return "Name of the project";
        }
        StringBuilder sb = new StringBuilder("Name of the project or add-on directory. Available targets: ");
        boolean first = true;
        for (Map.Entry<String, String> entry : projects.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(entry.getKey());
            if (entry.getValue() != null && !entry.getValue().isBlank()) {
                sb.append(" (").append(entry.getValue()).append(")");
            }
            first = false;
        }
        return sb.toString();
    }

    /**
     * Validates the add-on configuration (called once at server startup).
     * <p>
     * Throws {@link InvalidAddonConfiguration} if an add-on directory is missing, is
     * not a directory, has an empty/or duplicate folder name or lacks a mandatory
     * description ({@code IDE_PROJECT_ADDON_DESC.N}). Logs warnings for names that
     * conflict with a potential project sub-directory or with the project base directory.
     * </p>
     */
    public static void validateAddonConfiguration() {
        parseAddonConfiguration();
        LOGGER.debug("Validated add-on configuration: {}", addonNamesOrdered);
    }

    /**
     * Clears the cached add-on configuration so that the numbered system properties
     * ({@code IDE_PROJECT_ADDON_DIR.N}, {@code IDE_PROJECT_ADDON_DESC.N}) are re-read on
     * the next access.
     * <p>
     * Mainly intended for tests and administrative configuration reloads.
     * </p>
     */
    public static synchronized void resetAddonConfiguration() {
        addonDirByName = null;
        addonNamesOrdered = null;
    }

    /**
     * Looks up and validates a project by name.
     * Validates system properties, project filter, directory existence and path traversal.
     * <p>
     * Resolution order (see class javadoc): blank name &rarr; base/no-filter handling;
     * add-on folder name &rarr; add-on directory; otherwise the project logic
     * ({@code IDE_PROJECT_DIR/name} plus filter).
     * </p>
     *
     * @param projectName the name of the project or add-on folder to look up
     * @param result a map to store error messages if validation fails
     * @return a WorkProject instance if validation succeeds, null otherwise
     */
    public static WorkProject lookupProject(String projectName, Map<String, Object> result) {
        String projectDirProp = System.getProperty("IDE_PROJECT_DIR");
        if (projectDirProp == null || projectDirProp.isBlank()) {
            result.put("error", "System property IDE_PROJECT_DIR is not set");
            LOGGER.error("IDE_PROJECT_DIR system property is not defined.");
            return null;
        }

        Path projectBaseDir = Paths.get(projectDirProp).toAbsolutePath().normalize();
        if (!Files.exists(projectBaseDir)) {
            result.put("error", "Project base directory does not exist.");
            LOGGER.error("Project base directory does not exist: {}", projectBaseDir);
            return null;
        }

        String projectFilterProp = System.getProperty("IDE_PROJECT_FILTER");
        Pattern projectFilterPattern = compileProjectFilter(result);
        if (projectFilterPattern == null && result.containsKey("error")) {
            return null;
        }

        // 1) Blank name: no filter -> the base directory itself; with filter -> error.
        if (projectName == null || projectName.isBlank()) {
            if (projectFilterPattern != null) {
                result.put("error", "Project name is required.");
                LOGGER.warn("Project name is missing but IDE_PROJECT_FILTER ({}) is set.", projectFilterProp);
                return null;
            }
            return new WorkProject(projectBaseDir, projectBaseDir, projectBaseDir.getFileName().toString());
        }

        // 2) Add-on folder name (independent of any filter).
        AddonDir addon = addonByName(projectName);
        if (addon != null) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Resolved project '{}' to add-on directory.", projectName);
            }
            return new WorkProject(addon.path(), addon.path(), addon.name());
        }

        // 3) Filter check for project sub-directories.
        if (projectFilterPattern != null && !projectFilterPattern.matcher(projectName).matches()) {
            result.put("error", "Project name '" + projectName + "' is not allowed by filter");
            LOGGER.warn("Access denied to project '{}' due to filter.", projectName);
            return null;
        }

        // 4) Base directory itself (no filter, name equals the base folder name).
        if (projectFilterPattern == null && projectName.equals(projectBaseDir.getFileName().toString())) {
            return new WorkProject(projectBaseDir, projectBaseDir, projectBaseDir.getFileName().toString());
        }

        // 5) Project sub-directory.
        Path projectDir = projectBaseDir.resolve(projectName).normalize();
        if (!projectDir.startsWith(projectBaseDir)) {
            result.put("error", "Project directory is outside base directory, access denied");
            LOGGER.warn("Attempted directory traversal in project name: {}", projectName);
            return null;
        }

        if (!Files.exists(projectDir)) {
            result.put("error", "Project directory does not exist: " + projectBaseDir.relativize(projectDir));
            LOGGER.error("Project directory does not exist: {}", projectDir);
            return null;
        }

        return new WorkProject(projectBaseDir, projectDir, projectName);
    }

    /**
     * Compiles the project filter regex, if configured.
     * @param result result map (an "error" entry is added on invalid syntax)
     * @return the compiled pattern, or null if none configured or on error
     */
    private static Pattern compileProjectFilter(Map<String, Object> result) {
        String projectFilterProp = System.getProperty("IDE_PROJECT_FILTER");
        if (projectFilterProp == null || projectFilterProp.isBlank()) {
            return null;
        }
        try {
            return Pattern.compile(projectFilterProp);
        } catch (PatternSyntaxException e) {
            result.put("error", "Invalid regex in IDE_PROJECT_FILTER");
            LOGGER.error("Invalid regex in IDE_PROJECT_FILTER: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Returns the folder name of the project base directory, or null if not configured.
     * @return base folder name
     */
    private static String projectBaseName() {
        String projectDirProp = System.getProperty("IDE_PROJECT_DIR");
        if (projectDirProp == null || projectDirProp.isBlank()) {
            return null;
        }
        try {
            return Paths.get(projectDirProp).toAbsolutePath().normalize().getFileName().toString();
        } catch (RuntimeException e) {
            LOGGER.debug("Couldn't determine project base folder name: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Returns the add-on directory with the given folder name.
     * @param name add-on folder name
     * @return add-on, or null if not configured
     */
    private static AddonDir addonByName(String name) {
        parseAddonConfiguration();
        return addonDirByName.get(name);
    }

    /**
     * Parses and validates the add-on configuration from the numbered system properties
     * {@code IDE_PROJECT_ADDON_DIR.N} and {@code IDE_PROJECT_ADDON_DESC.N}.
     * <p>
     * Runs once (lazily); throws {@link InvalidAddonConfiguration} on a fatal
     * misconfiguration (missing description, inaccessible path, duplicate name).
     * </p>
     */
    private static synchronized void parseAddonConfiguration() {
        if (addonDirByName != null) {
            return;
        }
        Map<String, AddonDir> addons = new LinkedHashMap<>();
        TreeMap<Integer, Boolean> indices = new TreeMap<>();
        for (String propertyName : System.getProperties().stringPropertyNames()) {
            if (propertyName.startsWith(PROP_ADDON_DIR_PREFIX)) {
                String indexString = propertyName.substring(PROP_ADDON_DIR_PREFIX.length());
                try {
                    indices.put(Integer.parseInt(indexString), Boolean.TRUE);
                } catch (NumberFormatException e) {
                    LOGGER.warn("Ignoring property with invalid add-on index: {}", propertyName);
                }
            }
        }

        for (int index : indices.keySet()) {
            String dirProperty = PROP_ADDON_DIR_PREFIX + index;
            String dirValue = System.getProperty(dirProperty);
            if (dirValue == null || dirValue.isBlank()) {
                String msg = dirProperty + " is blank/not set. Each add-on directory property must have a value.";
                LOGGER.error(msg);
                throw new InvalidAddonConfiguration(msg);
            }
            String descProperty = PROP_ADDON_DESC_PREFIX + index;
            String description = System.getProperty(descProperty);
            if (description == null || description.isBlank()) {
                String msg = descProperty + " is missing/blank for " + dirProperty
                        + ". A description is mandatory so that no absolute path is exposed to the LLM; "
                        + "configure it explicitly if the LLM should know the location.";
                LOGGER.error(msg);
                throw new InvalidAddonConfiguration(msg);
            }

            Path rawPath = expandHome(dirValue);
            Path path;
            try {
                path = rawPath.toAbsolutePath().normalize().toRealPath();
            } catch (IOException e) {
                String msg = dirProperty + " is not accessible: " + rawPath + " (" + e.getMessage() + ")";
                LOGGER.error(msg);
                throw new InvalidAddonConfiguration(msg);
            }
            if (!Files.isDirectory(path)) {
                String msg = dirProperty + " is not a directory: " + rawPath;
                LOGGER.error(msg);
                throw new InvalidAddonConfiguration(msg);
            }

            String name = path.getFileName().toString();
            if (name.isEmpty() || name.isBlank()) {
                String msg = dirProperty + " has no folder name: " + rawPath;
                LOGGER.error(msg);
                throw new InvalidAddonConfiguration(msg);
            }
            if (addons.containsKey(name)) {
                String msg = "Duplicate add-on folder name '" + name + "' (" + dirProperty + "); "
                        + "add-on names must be unique.";
                LOGGER.error(msg);
                throw new InvalidAddonConfiguration(msg);
            }
            addons.put(name, new AddonDir(name, path, description.trim()));
        }

        warnAboutConflicts(addons);

        addonDirByName = Collections.unmodifiableMap(addons);
        List<String> names = new ArrayList<>(addons.keySet());
        Collections.sort(names);
        addonNamesOrdered = Collections.unmodifiableList(names);
    }

    /**
     * Logs warnings for add-on directories that conflict with the project base directory
     * or with potential project sub-directories.
     * @param addons the parsed add-on directories
     */
    private static void warnAboutConflicts(Map<String, AddonDir> addons) {
        String projectDirProp = System.getProperty("IDE_PROJECT_DIR");
        if (projectDirProp == null || projectDirProp.isBlank()) {
            return;
        }
        Path base;
        try {
            base = Paths.get(projectDirProp).toAbsolutePath().normalize().toRealPath();
        } catch (IOException e) {
            LOGGER.debug("Couldn't resolve project directory for add-on conflict check: {}", e.getMessage());
            return;
        }
        for (AddonDir addon : addons.values()) {
            Path addonPath = addon.path();
            if (addonPath.equals(base)) {
                LOGGER.warn("Add-on directory '{}' is identical to the project directory (IDE_PROJECT_DIR).",
                        addon.name());
            } else if (addonPath.startsWith(base)) {
                Path relative = base.relativize(addonPath);
                if (relative.getNameCount() == 1 && relative.getName(0).toString().equals(addon.name())) {
                    LOGGER.warn("Add-on directory '{}' is a direct subdirectory of the project directory; "
                            + "the add-on name shadows a potential project sub-directory (the add-on wins).",
                            addon.name());
                } else {
                    LOGGER.warn("Add-on directory '{}' is located inside the project directory.", addon.name());
                }
            } else if (base.startsWith(addonPath)) {
                LOGGER.warn("Add-on directory '{}' contains the project directory (IDE_PROJECT_DIR).",
                        addon.name());
            }
        }
    }

    /**
     * Expands a leading {@code ~} to the user's home directory.
     * @param value path expression
     * @return a Path with {@code ~} expanded (not yet normalized)
     */
    private static Path expandHome(String value) {
        if (value.equals("~") || value.startsWith("~/") || value.startsWith("~\\")) {
            return Paths.get(System.getProperty("user.home"), value.substring(1));
        }
        return Paths.get(value);
    }
}
