package org.rogmann.mcp2sdk.examples;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import org.rogmann.mcp2sdk.WorkProject;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.function.BiFunction;

/**
 * MCP tool implementation for reading text from a file in a project.
 * Refactored to use McpServerFeatures.SyncToolSpecification.
 * The tool ensures that only allowed projects and safe paths are accessed.
 * It supports limiting the number of lines read from the file.
 */
public class ReadTextFileTool {

    private static final Logger LOGGER = Logger.getLogger(ReadTextFileTool.class.getName());

    private static final String NAME = "get_file_text_by_path";

    private ReadTextFileTool() {
        // Prevent instantiation, this class serves as a factory for the tool specification
    }

    /**
     * Creates the synchronous tool specification for reading file text.
     * @return a SyncToolSpecification ready to be registered with an MCP server
     */
    public static McpServerFeatures.SyncToolSpecification createSpecification() {
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

        BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> handler = (exchange, request) -> {
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
                LOGGER.warning("Path traversal attempt detected: " + pathInProject);
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

                LOGGER.fine("Successfully read " + linesRead + " lines from file: " + targetFile);
                
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
                LOGGER.severe("IOException while reading file " + targetFile + ": " + e.getMessage());
                return CallToolResult.builder()
                    .isError(true)
                    .addTextContent(errorMsg)
                    .build();
            }
        };

        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler(handler)
            .build();
    }
}
