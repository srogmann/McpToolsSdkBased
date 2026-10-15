package org.rogmann.mcp2sdk.poi;

import org.rogmann.mcp2sdk.WorkProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * File-path resolution for the POI toolboxes (xlsx/docx/pptx).
 * <p>
 * All three namespaces read and write inside the same project base directory, so they share
 * one rule set: a relative path is resolved against that directory, {@code ".."} may not leave
 * it, and the add-on mount syntax {@code /addonName/...} addresses the read-only add-on folders.
 * </p>
 * <p>
 * This used to live inside {@link PoiToolBox}, which meant that {@code docx} and {@code pptx}
 * asked the <em>xlsx</em> toolbox for every path they touched - a dependency of two toolboxes on
 * the third one for something that is not about spreadsheets. Same reasoning as
 * {@link PoiColorUtil} and {@link PoiJsArgs}.
 * </p>
 */
public final class PoiPaths {

    private static final Logger LOG = LoggerFactory.getLogger(PoiPaths.class);

    private PoiPaths() {
        // Utility class
    }

    /**
     * Returns the project base directory for file access.
     * <p>
     * The base directory is determined via {@link WorkProject#lookupProject(Map)}.
     * </p>
     * @return the resolved base path
     * @throws PoiUserRuntimeException if {@code IDE_PROJECT_DIR} is not available
     */
    public static Path getBasePath() {
        Map<String, Object> result = new HashMap<>();
        WorkProject workProject = WorkProject.lookupProject(result);
        if (workProject == null) {
            String error = (String) result.get("error");
            throw new PoiUserRuntimeException(
                    "No project base directory available. Ensure IDE_PROJECT_DIR is set. "
                    + (error != null ? error : ""));
        }
        Path projectBaseDir = workProject.projectBaseDir();
        LOG.debug("Using project base directory as POI base path: {}", projectBaseDir);
        return projectBaseDir;
    }

    /**
     * Resolves a file path against the base directory and validates it.
     * @param filePath relative path (or absolute within base directory)
     * @return the resolved absolute path
     * @throws PoiUserRuntimeException if the path is invalid or outside the base directory
     */
    public static Path resolveSafePath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new PoiUserRuntimeException("File path must not be empty.");
        }
        // Add-on mount syntax: "/addonName/path/within/addon"
        if (filePath.startsWith("/")) {
            int firstSlash = filePath.indexOf('/', 1);
            if (firstSlash > 1) {
                String addonName = filePath.substring(1, firstSlash);
                Path addonRoot = WorkProject.addonPath(addonName);
                if (addonRoot != null) {
                    String remainder = filePath.substring(firstSlash);
                    String rel = remainder.startsWith("/") ? remainder.substring(1) : remainder;
                    Path resolved = addonRoot.resolve(rel).normalize();
                    if (!resolved.startsWith(addonRoot)) {
                        LOG.warn("Path traversal attempt in add-on '{}': filePath='{}'", addonName, filePath);
                        throw new PoiUserRuntimeException(
                                "Access denied: the specified path is not within the permitted add-on directory.");
                    }
                    return resolved;
                }
                throw new PoiUserRuntimeException("Add-on directory not configured: '" + addonName
                        + "'. Available add-ons: " + String.join(", ", WorkProject.addonNames()));
            }
        }
        Path basePath = getBasePath();
        // Normalize to prevent path traversal
        Path resolved = basePath.resolve(filePath).normalize();
        if (!resolved.startsWith(basePath)) {
            LOG.warn("Path traversal attempt detected: filePath='{}' resolved to '{}'", filePath, resolved);
            throw new PoiUserRuntimeException(
                    "Access denied: the specified path is not within the permitted directory.");
        }
        return resolved;
    }
}
