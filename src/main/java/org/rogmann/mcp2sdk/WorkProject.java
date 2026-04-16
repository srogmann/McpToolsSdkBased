package org.rogmann.mcp2sdk;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Represents a validated work project with base directory and project directory.
 * Provides a static lookup method to validate and resolve project directories.
 * 
 * @param projectBaseDir base directory
 * @param projectDir project directory
 * @param projectName project name
 */
public record WorkProject(Path projectBaseDir, Path projectDir, String projectName) {

    private static final Logger LOGGER = Logger.getLogger(WorkProject.class.getName());

    /**
     * Looks up and validates a project by name.
     * Validates system properties, project filter, directory existence and path traversal.
     *
     * @param projectName the name of the project to look up
     * @param result a map to store error messages if validation fails
     * @return a WorkProject instance if validation succeeds, null otherwise
     */
    public static WorkProject lookupProject(String projectName, Map<String, Object> result) {
        String projectDirProp = System.getProperty("IDE_PROJECT_DIR");
        if (projectDirProp == null || projectDirProp.isBlank()) {
            result.put("error", "System property IDE_PROJECT_DIR is not set");
            LOGGER.severe("IDE_PROJECT_DIR system property is not defined.");
            return null;
        }

        Path projectBaseDir = Paths.get(projectDirProp).toAbsolutePath().normalize();
        if (!Files.exists(projectBaseDir)) {
            result.put("error", "Project base directory does not exist.");
            LOGGER.severe("Project base directory does not exist: " + projectBaseDir);
            return null;
        }

        String projectFilterProp = System.getProperty("IDE_PROJECT_FILTER");
        Pattern projectFilterPattern = null;
        if (projectFilterProp != null && !projectFilterProp.isBlank()) {
            try {
                projectFilterPattern = Pattern.compile(projectFilterProp);
            } catch (PatternSyntaxException e) {
                result.put("error", "Invalid regex in IDE_PROJECT_FILTER");
                LOGGER.severe("Invalid regex in IDE_PROJECT_FILTER: " + e.getMessage());
                return null;
            }
        }

        if (projectFilterPattern != null && !projectFilterPattern.matcher(projectName).matches()) {
            result.put("error", "Project name '" + projectName + "' is not allowed by filter");
            LOGGER.warning("Access denied to project '" + projectName + "' due to filter.");
            return null;
        }

        Path projectDir = projectBaseDir.resolve(projectName).normalize();
        if (!projectDir.startsWith(projectBaseDir)) {
            result.put("error", "Project directory is outside base directory, access denied");
            LOGGER.warning("Attempted directory traversal in project name: " + projectName);
            return null;
        }

        if (!Files.exists(projectDir)) {
            result.put("error", "Project directory does not exist: " + projectBaseDir.relativize(projectDir));
            LOGGER.severe("Project directory does not exist: " + projectDir);
            return null;
        }

        return new WorkProject(projectBaseDir, projectDir, projectName);
    }
}
