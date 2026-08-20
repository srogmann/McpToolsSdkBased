package org.rogmann.mcp2sdk.examples;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import org.rogmann.mcp2sdk.ToolSpecWithState;
import org.rogmann.mcp2sdk.ToolState;
import org.rogmann.mcp2sdk.WorkProject;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * MCP tool implementation for finding files in a project using a glob pattern.
 * Refactored to use McpServerFeatures.SyncToolSpecification.
 * The tool ensures that only allowed projects and safe paths are accessed.
 * It supports limiting the number of returned files and optional subdirectory filtering.
 */
public class FindFilesByGlobTool {

    private static final Logger LOGGER = Logger.getLogger(FindFilesByGlobTool.class.getName());

    private static final String NAME = "find_files_by_glob";

    /** tool state (active-flag, statistics) */
    private final ToolState state;

    private FindFilesByGlobTool() {
        state = new ToolState();
    }

    /**
     * Creates the synchronous tool specification for finding files by glob pattern.
     * @return the tool specification and its state
     */
    public static ToolSpecWithState createToolInstance() {
        // Define Input Schema properties
        Map<String, Object> properties = new HashMap<>();

        // Offer the project name if several projects are possible (a filter is set) or
        // if add-on directories are configured (then it is optional).
        if (WorkProject.needsProjectName() || WorkProject.hasAddonDirectories()) {
            Map<String, Object> projectNameProp = new HashMap<>();
            projectNameProp.put("type", "string");
            projectNameProp.put("description", WorkProject.projectNameDescription());
            properties.put("projectName", projectNameProp);
        }

        Map<String, Object> globPatternProp = new HashMap<>();
        globPatternProp.put("type", "string");
        globPatternProp.put("description", "Glob pattern for matching files relative to the project directory, e.g. **/*.java. Note: a leading '**/' also matches files directly in the project root (e.g. '**/*.java' includes 'Main.java').");
        properties.put("globPattern", globPatternProp);

        Map<String, Object> fileCountLimitProp = new HashMap<>();
        fileCountLimitProp.put("type", "integer");
        fileCountLimitProp.put("description", "Optional maximum number of files to return");
        properties.put("fileCountLimit", fileCountLimitProp);

        Map<String, Object> subDirectoryRelativePathProp = new HashMap<>();
        subDirectoryRelativePathProp.put("type", "string");
        subDirectoryRelativePathProp.put("description", "Optional subdirectory path relative to the project root where the search should be limited to, e.g. src/main/java");
        properties.put("subDirectoryRelativePath", subDirectoryRelativePathProp);

        List<String> requiredFields = List.of("globPattern");

        JsonSchema inputSchema = new JsonSchema("object", properties, requiredFields, null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(NAME)
            .title("Find Files by Glob")
            .description("Finds files in the specified project matching the given glob pattern if access conditions are met.")
            .inputSchema(inputSchema)
            .build();

        FindFilesByGlobTool toolImpl = new FindFilesByGlobTool();

        return new ToolSpecWithState(McpServerFeatures.SyncToolSpecification.builder()
                    .tool(tool)
                    .callHandler(toolImpl::call)
                    .build(),
                toolImpl.state);
    }

    /**
     * Handles the tool call request.
     *
     * @param exchange the server exchange
     * @param request the tool call request
     * @return the tool call result
     */
    McpSchema.CallToolResult call(McpSyncServerExchange exchange, CallToolRequest request) {
        // Increment call count
        state.callCount().incrementAndGet();

        Map<String, Object> arguments = request.arguments();

        String projectName = (String) arguments.get("projectName");
        String globPattern = (String) arguments.get("globPattern");
        if (globPattern == null) {
            globPattern = "**/*.*";
        }
        Integer fileCountLimit = (Integer) arguments.get("fileCountLimit");
        String subDirectoryRelativePath = (String) arguments.get("subDirectoryRelativePath");

        // Use a temporary map to capture potential error from WorkProject.lookupProject
        Map<String, Object> tempResult = new HashMap<>();
        WorkProject workProject = (projectName == null || projectName.isBlank())
                ? WorkProject.lookupProject(tempResult)
                : WorkProject.lookupProject(projectName, tempResult);

        if (workProject == null) {
            String error = (String) tempResult.get("error");
            return CallToolResult.builder()
                .isError(true)
                .addTextContent(error != null ? error : "Project not found: " + projectName)
                .build();
        }

        Path projectDir = workProject.projectDir();

        String projectSubExcludeFilterProp = System.getProperty("IDE_PROJECT_SUB_EXCLUDE_FILTER");
        Pattern projectSubExcludePattern = null;
        if (projectSubExcludeFilterProp != null && !projectSubExcludeFilterProp.isBlank()) {
            try {
                projectSubExcludePattern = Pattern.compile(projectSubExcludeFilterProp);
            } catch (PatternSyntaxException e) {
                LOGGER.severe("Invalid regex in IDE_PROJECT_SUB_EXCLUDE_FILTER: " + e.getMessage());
                return CallToolResult.builder()
                    .isError(true)
                    .addTextContent("Invalid IDE_PROJECT_SUB_EXCLUDE_FILTER: " + e.getMessage())
                    .build();
            }
        }

        Path patternRoot = projectDir.resolve(".").normalize();
        if (!patternRoot.startsWith(projectDir)) {
            LOGGER.warning("Pattern root path resolves outside project directory: " + patternRoot);
            return CallToolResult.builder()
                .isError(true)
                .addTextContent("Invalid pattern root path after resolution of " + projectDir)
                .build();
        }

        // Compute search-root directory
        Path searchRoot;
        if (subDirectoryRelativePath != null && !subDirectoryRelativePath.isBlank()) {
            Path subDirPath = projectDir.resolve(subDirectoryRelativePath).normalize();
            if (!subDirPath.startsWith(projectDir)) {
                LOGGER.warning("Attempted directory traversal in subDirectoryRelativePath: " + subDirectoryRelativePath);
                return CallToolResult.builder()
                    .isError(true)
                    .addTextContent("Subdirectory path resolves outside project directory, access denied")
                    .build();
            }
            if (!Files.exists(subDirPath)) {
                LOGGER.severe("Subdirectory does not exist: " + subDirPath);
                return CallToolResult.builder()
                    .isError(true)
                    .addTextContent("Subdirectory does not exist: " + subDirPath)
                    .build();
            }
            if (!Files.isDirectory(subDirPath)) {
                LOGGER.severe("Subdirectory path is not a directory: " + subDirPath);
                return CallToolResult.builder()
                    .isError(true)
                    .addTextContent("Subdirectory path is not a directory: " + subDirPath)
                    .build();
            }
            searchRoot = subDirPath;
        } else {
            searchRoot = projectDir;
        }

        int limit = Optional.ofNullable(fileCountLimit).orElse(Integer.MAX_VALUE);
        if (limit <= 0) {
            // Increment success count
            state.callsOk().incrementAndGet();

            LOGGER.fine("File count limit is non-positive: " + limit);
            Map<String, Object> structuredContent = new HashMap<>();
            structuredContent.put("status", "success");
            structuredContent.put("files", new ArrayList<>());
            structuredContent.put("fileCount", 0);
            structuredContent.put("message", "No files returned due to zero or negative limit");
            return CallToolResult.builder()
                .isError(false)
                .addTextContent("No files returned due to zero or negative limit")
                .structuredContent(structuredContent)
                .build();
        }

        List<Map<String, Object>> matchingFiles = new ArrayList<>();
        try {
            var fileSystem = FileSystems.getDefault();
            String qualifiedPattern = "glob:" + globPattern;
            DirectoryStream.Filter<Path> filter = fileSystem.getPathMatcher(qualifiedPattern)::matches;

            // Java's glob requires at least one directory level for a leading "**/":
            // a file directly in the search root (e.g. "Main.java" for "**/*.java") is
            // not matched by the pattern alone. Build an additional matcher that tests
            // the pattern variant without the "**/" prefix for root-level entries.
            DirectoryStream.Filter<Path> rootLevelFilter = buildRootLevelFilter(fileSystem, globPattern);

            walkAndMatch(searchRoot, projectDir, filter, rootLevelFilter, projectSubExcludePattern, matchingFiles, limit);

            LOGGER.fine("Found " + matchingFiles.size() + " file(s) matching pattern '" + globPattern +
                    "' in project '" + projectName + "' under subdirectory '" + subDirectoryRelativePath + "'");

            // Increment success count
            state.callsOk().incrementAndGet();

            Map<String, Object> structuredContent = new HashMap<>();
            structuredContent.put("status", "success");
            structuredContent.put("files", matchingFiles);
            structuredContent.put("fileCount", matchingFiles.size());
            structuredContent.put("message", "Found " + matchingFiles.size() + " file(s) matching pattern");

            StringBuilder textContentBuilder = new StringBuilder();
            textContentBuilder.append("Found ").append(matchingFiles.size()).append(" file(s) matching pattern '").append(globPattern).append("':\n");
            for (Map<String, Object> fileInfo : matchingFiles) {
                textContentBuilder.append("  - ").append(fileInfo.get("path"));
                Long size = (Long) fileInfo.get("size");
                if (size != null && size >= 0) {
                    textContentBuilder.append(" (").append(size).append(" bytes)");
                }
                textContentBuilder.append("\n");
            }

            return CallToolResult.builder()
                .isError(false)
                .addTextContent(textContentBuilder.toString())
                .structuredContent(structuredContent)
                .build();

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Exception during glob processing for pattern '" + globPattern + "' in: " + projectName + " / " + searchRoot, e);
            return CallToolResult.builder()
                .isError(true)
                .addTextContent("Failed to process glob pattern '" + globPattern + "' for '" + projectName + "': " + e.getMessage())
                .build();
        }
    }

    /**
     * Builds an additional matcher for files directly in the search root, if the glob
     * pattern starts with "{@code "&amp;&amp;/}.
     * <p>
     * Java's {@code PathMatcher} glob semantics require at least one directory level
     * for a leading {@code &amp;&amp;/} (e.g. {@code &amp;&amp;/a.txt} matches {@code sub/a.txt} but
     * not a root-level {@code a.txt}). The returned variant strips that prefix so that
     * root-level entries are matched, too.
     * </p>
     * @param fileSystem the default file system (for path matchers)
     * @param globPattern the user-supplied glob pattern
     * @return a root-level matcher, or {@code null} if not applicable
     */
    static DirectoryStream.Filter<Path> buildRootLevelFilter(FileSystem fileSystem, String globPattern) {
        if (globPattern != null && globPattern.startsWith("**/") && globPattern.length() > 3) {
            return fileSystem.getPathMatcher("glob:" + globPattern.substring(3))::matches;
        }
        return null;
    }

    /**
     * Tests a path (relative to the project root) against the main filter, and - for
     * entries directly in the search root (single path component) - additionally against
     * the root-level filter to compensate Java's {@code &amp;&amp;/} semantics.
     * @param filter the main glob filter
     * @param rootLevelFilter an optional root-level filter (see {@link #buildRootLevelFilter})
     * @param relativePath path relative to the project root
     * @return true if the path matches
     * @throws IOException if a directory entry cannot be read
     */
    static boolean matchesPath(DirectoryStream.Filter<Path> filter, DirectoryStream.Filter<Path> rootLevelFilter,
                               Path relativePath) throws IOException {
        boolean matches = filter.accept(relativePath);
        if (!matches && rootLevelFilter != null && relativePath.getNameCount() == 1) {
            matches = rootLevelFilter.accept(relativePath);
        }
        return matches;
    }

    /**
     * Recursively walks through the directory tree and collects files matching the filter up to the limit.
     *
     * @param basePath base path to start walking from
     * @param rootDir base directory of the search (for relativization)
     * @param filter filter to apply on paths
     * @param rootLevelFilter optional root-level matcher for leading {@code &amp;&amp;/} patterns
     * @param projectSubExcludePattern exclude-filter pattern
     * @param result list to collect matched files
     * @param limit maximum number of files to collect
     * @throws IOException if an I/O error occurs
     */
    private static void walkAndMatch(Path basePath, Path rootDir, DirectoryStream.Filter<Path> filter,
                                     DirectoryStream.Filter<Path> rootLevelFilter,
                                     Pattern projectSubExcludePattern, List<Map<String, Object>> result, int limit) throws IOException {
        if (result.size() >= limit) {
            return;
        }

        if (Files.isDirectory(basePath)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(basePath)) {
                for (Path entry : stream) {
                    if (result.size() >= limit) {
                        break;
                    }

                    Path relativePath = rootDir.relativize(entry);
                    if (projectSubExcludePattern != null && projectSubExcludePattern.matcher(relativePath.toString()).matches()) {
                        continue;
                    }
                    if (matchesPath(filter, rootLevelFilter, relativePath)) {
                        Map<String, Object> fileInfo = new HashMap<>();
                        fileInfo.put("path", relativePath.toString());
                        try {
                            fileInfo.put("size", Files.size(entry));
                        } catch (IOException e) {
                            fileInfo.put("size", -1L);
                        }
                        result.add(fileInfo);
                    }

                    if (Files.isDirectory(entry)) {
                        walkAndMatch(entry, rootDir, filter, rootLevelFilter, projectSubExcludePattern, result, limit);
                    }
                }
            }
        } else {
            if (matchesPath(filter, rootLevelFilter, rootDir.relativize(basePath))) {
                Map<String, Object> fileInfo = new HashMap<>();
                Path relativePath = rootDir.relativize(basePath);
                fileInfo.put("path", relativePath.toString());
                try {
                    fileInfo.put("size", Files.size(basePath));
                } catch (IOException e) {
                    fileInfo.put("size", -1L);
                }
                result.add(fileInfo);
            }
        }
    }
}
