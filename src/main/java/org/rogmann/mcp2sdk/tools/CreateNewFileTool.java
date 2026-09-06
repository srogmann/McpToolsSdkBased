package org.rogmann.mcp2sdk.tools;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import org.rogmann.mcp2sdk.ToolSpecWithState;
import org.rogmann.mcp2sdk.ToolState;
import org.rogmann.mcp2sdk.WorkProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MCP tool implementation for creating a new file in a project.
 * The tool ensures that only allowed projects and safe paths are handled.
 * It supports optional overwriting of existing files.
 */
public class CreateNewFileTool {

    private static final Logger LOGGER = LoggerFactory.getLogger(CreateNewFileTool.class);

    private static final String NAME = "create_new_file";

    /** maximum number of lines counted for the size metrics (keeps the error
     *  path cheap even for very large files) */
    private static final int MAX_COUNTED_LINES = 100_000;

    /** tool state (active-flag, statistics) */
    private final ToolState state;

    private CreateNewFileTool() {
        state = new ToolState();
    }

    /**
     * Creates the synchronous tool specification for creating a new file.
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

        Map<String, Object> pathInProjectProp = new HashMap<>();
        pathInProjectProp.put("type", "string");
        pathInProjectProp.put("description", "Path of the file relative to the project directory");
        properties.put("pathInProject", pathInProjectProp);

        Map<String, Object> textProp = new HashMap<>();
        textProp.put("type", "string");
        textProp.put("description", "Content of the file (e.g. Java source code or HTML)");
        properties.put("text", textProp);

        Map<String, Object> overwriteProp = new HashMap<>();
        overwriteProp.put("type", "boolean");
        overwriteProp.put("description", "Whether an existing file may be overwritten");
        properties.put("overwrite", overwriteProp);

        List<String> requiredFields = List.of("pathInProject", "text");

        JsonSchema inputSchema = new JsonSchema("object", properties, requiredFields, null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(NAME)
            .title("Create New File")
            .description("Creates a new file in the specified project and path if conditions are met.")
            .inputSchema(inputSchema)
            .build();

        CreateNewFileTool toolImpl = new CreateNewFileTool();

        return new ToolSpecWithState(McpServerFeatures.SyncToolSpecification.builder()
                    .tool(tool)
                    .callHandler(toolImpl::call)
                    .build(),
                toolImpl.state);
    }

    /**
     * Handles the tool call request.
     * @param exchange the server exchange
     * @param request the tool call request
     * @return the tool call result
     */
    McpSchema.CallToolResult call(McpSyncServerExchange exchange, CallToolRequest request) {
        // Increment call count
        state.callCount().incrementAndGet();

        Map<String, Object> arguments = request.arguments();

        String projectName = (String) arguments.get("projectName");
        String pathInProject = (String) arguments.get("pathInProject");
        String text = (String) arguments.get("text");
        Boolean overwrite = Optional.ofNullable((Boolean) arguments.get("overwrite")).orElse(false);

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

        Path projectBaseDir = workProject.projectBaseDir();
        Path projectDir = workProject.projectDir();

        Path targetFile = projectDir.resolve(pathInProject).normalize();
        if (!targetFile.startsWith(projectDir)) {
            String errorMsg = "Path traversal detected in pathInProject: " + pathInProject;
            LOGGER.warn("Path traversal attempt detected: " + pathInProject);
            return CallToolResult.builder()
                .isError(true)
                .addTextContent(errorMsg)
                .build();
        }

        if (Files.exists(targetFile) && !overwrite) {
            String errorMsg = "File already exists and overwrite is not allowed: "
                + projectBaseDir.relativize(targetFile)
                + " (existing file: " + describeFileMetrics(targetFile)
                + "; submitted text: " + describeTextMetrics(text)
                + "; set overwrite=true to replace the file)";
            LOGGER.info("File exists, overwrite=false: " + targetFile);
            return CallToolResult.builder()
                .isError(true)
                .addTextContent(errorMsg)
                .build();
        }

        try {
            Files.createDirectories(targetFile.getParent());
            Files.writeString(targetFile, text,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            state.callsOk().incrementAndGet();

            LOGGER.info("Successfully created file: " + targetFile);

            // Prepare structured content for programmatic access if needed
            Map<String, Object> structuredContent = new HashMap<>();
            structuredContent.put("status", "success");
            structuredContent.put("path", projectBaseDir.relativize(targetFile).toString());
            structuredContent.put("message", "File written in project " + projectName + ": " + projectDir.relativize(targetFile));

            return CallToolResult.builder()
                .isError(false)
                .addTextContent("File created successfully: " + projectDir.relativize(targetFile))
                .structuredContent(structuredContent)
                .build();

        } catch (IOException e) {
            String errorMsg = "Failed to write file: " + e.getMessage();
            LOGGER.error("IOException while writing file " + targetFile, e);
            return CallToolResult.builder()
                .isError(true)
                .addTextContent(errorMsg)
                .build();
        }
    }

    /**
     * Describes the size metrics of an existing file (byte size plus line
     * count, capped at {@link #MAX_COUNTED_LINES} lines).
     * @param file existing file
     * @return human-readable metrics, e.g. "1234 lines / 56789 bytes"
     */
    private static String describeFileMetrics(Path file) {
        try {
            long bytes = Files.size(file);
            long lines = 0;
            boolean capped = false;
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                while (reader.readLine() != null) {
                    lines++;
                    if (lines >= MAX_COUNTED_LINES) {
                        capped = true;
                        break;
                    }
                }
            }
            return (capped ? ">" + lines : String.valueOf(lines)) + " lines / " + bytes + " bytes";
        } catch (IOException e) {
            LOGGER.warn("Could not read metrics of {}", file, e);
            return "? lines / ? bytes";
        }
    }

    /**
     * Describes the size metrics of the submitted text (byte size in UTF-8
     * plus line count; a trailing line break does not open an extra line).
     * @param text submitted text
     * @return human-readable metrics, e.g. "42 lines / 12345 bytes"
     */
    private static String describeTextMetrics(String text) {
        if (text == null || text.isEmpty()) {
            return "0 lines / 0 bytes";
        }
        long newlines = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                newlines++;
            }
        }
        long lines = newlines + (text.endsWith("\n") ? 0 : 1);
        return lines + " lines / " + text.getBytes(StandardCharsets.UTF_8).length + " bytes";
    }
}
