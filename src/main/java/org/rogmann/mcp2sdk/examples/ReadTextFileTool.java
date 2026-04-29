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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MCP tool implementation for reading text from a file in a project.
 * Refactored to use ToolRegistry pattern with ToolState for statistics.
 * The tool ensures that only allowed projects and safe paths are accessed.
 * It supports limiting the number of lines read from the file.
 */
public class ReadTextFileTool {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReadTextFileTool.class);

    private static final String NAME = "get_file_text_by_path";

    /** tool state (active-flag, statistics) */
    private final ToolState state;

    private ReadTextFileTool() {
        state = new ToolState();
    }

    /**
     * Creates the synchronous tool specification for reading file text.
     * @return the tool specification and its state
     */
    public static ToolSpecWithState createToolInstance() {
        // Define Input Schema properties
        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> projectNameProp = new HashMap<>();
        projectNameProp.put("type", "string");
        projectNameProp.put("description", "Name of the project");
        properties.put("projectName", projectNameProp);

        Map<String, Object> pathInProjectProp = new HashMap<>();
        pathInProjectProp.put("type", "string");
        pathInProjectProp.put("description", "Path of the file relative to the project directory");
        properties.put("pathInProject", pathInProjectProp);

        Map<String, Object> maxLinesCountProp = new HashMap<>();
        maxLinesCountProp.put("type", "integer");
        maxLinesCountProp.put("description", "Optional maximum number of lines to read from the file");
        properties.put("maxLinesCount", maxLinesCountProp);

        List<String> requiredFields = List.of("projectName", "pathInProject");

        JsonSchema inputSchema = new JsonSchema("object", properties, requiredFields, null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(NAME)
                .title("Read File Text by Path")
                .description("Reads text content from a file in the specified project if access conditions are met.")
                .inputSchema(inputSchema)
                .build();

        ReadTextFileTool toolImpl = new ReadTextFileTool();

        return new ToolSpecWithState(McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(toolImpl::call)
                .build(),
                toolImpl.state);
    }

    /**
     * Handles the tool call request.
     *
     * @param exchange the server exchange context
     * @param request the tool call request
     * @return the tool call result
     */
    private McpSchema.CallToolResult call(McpSyncServerExchange exchange, CallToolRequest request) {
        // Increment call count
        state.callCount().incrementAndGet();

        Map<String, Object> arguments = request.arguments();

        String projectName = (String) arguments.get("projectName");
        String pathInProject = (String) arguments.get("pathInProject");
        Integer maxLinesCount = (Integer) arguments.get("maxLinesCount");

        // Use a temporary map to capture potential error from WorkProject.lookupProject
        Map<String, Object> tempResult = new HashMap<>();
        WorkProject workProject = WorkProject.lookupProject(projectName, tempResult);

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
            String errorMsg = "Path traversal detected in pathInProject: " + projectName;
            LOGGER.warn("Path traversal attempt detected: " + pathInProject);
            return CallToolResult.builder()
                    .isError(true)
                    .addTextContent(errorMsg)
                    .build();
        }

        if (!Files.exists(targetFile)) {
            String errorMsg = "File does not exist in project " + projectName + ": " + projectDir.relativize(targetFile);
            LOGGER.info("File not found: " + targetFile);
            return CallToolResult.builder()
                    .isError(true)
                    .addTextContent(errorMsg)
                    .build();
        }

        if (Files.isDirectory(targetFile)) {
            String errorMsg = "Path refers to a directory, not a file: " + projectDir.relativize(targetFile);
            LOGGER.info("Cannot read text from directory: " + targetFile);
            return CallToolResult.builder()
                    .isError(true)
                    .addTextContent(errorMsg)
                    .build();
        }

        try (BufferedReader reader = Files.newBufferedReader(targetFile, StandardCharsets.UTF_8)) {
            StringBuilder content = new StringBuilder();
            int linesRead = 0;
            int maxLines = Optional.ofNullable(maxLinesCount).orElse(Integer.MAX_VALUE);

            while (true) {
                String line = reader.readLine();
                if (line == null || linesRead >= maxLines) {
                    break;
                }
                if (linesRead > 0) {
                    content.append("\n");
                }
                content.append(line);
                linesRead++;
            }

            // Increment success counter
            state.callsOk().incrementAndGet();

            LOGGER.debug("Successfully read " + linesRead + " lines from file: " + targetFile);

            // Prepare structured content for programmatic access if needed
            Map<String, Object> structuredContent = new HashMap<>();
            structuredContent.put("status", "success");
            structuredContent.put("text", content.toString());
            structuredContent.put("linesRead", linesRead);
            structuredContent.put("message", "Successfully read from file: " + projectBaseDir.relativize(targetFile));

            return CallToolResult.builder()
                    .isError(false)
                    .addTextContent(content.toString())
                    .structuredContent(structuredContent)
                    .build();

        } catch (IOException e) {
            String errorMsg = "Failed to read file: " + e.getMessage();
            LOGGER.error("IOException while reading file " + targetFile, e);
            return CallToolResult.builder()
                    .isError(true)
                    .addTextContent(errorMsg)
                    .build();
        }
    }
}
