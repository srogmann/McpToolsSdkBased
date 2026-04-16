package org.rogmann.mcp2sdk.examples;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import org.rogmann.mcp2sdk.WorkProject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.function.BiFunction;

/**
 * MCP tool implementation for creating a new file in a project.
 * Refactored to use McpServerFeatures.SyncToolSpecification.
 * The tool ensures that only allowed projects and safe paths are handled.
 * It supports optional overwriting of existing files.
 */
public class CreateNewFileTool {

    private static final Logger LOGGER = Logger.getLogger(CreateNewFileTool.class.getName());

    private static final String NAME = "create_new_file";

    private CreateNewFileTool() {
        // Prevent instantiation, this class serves as a factory for the tool specification
    }

    /**
     * Creates the synchronous tool specification for creating a new file.
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

        Map<String, Object> textProp = new HashMap<>();
        textProp.put("type", "string");
        textProp.put("description", "Content of the file (e.g. Java source code or HTML)");
        properties.put("text", textProp);

        Map<String, Object> overwriteProp = new HashMap<>();
        overwriteProp.put("type", "boolean");
        overwriteProp.put("description", "Whether an existing file may be overwritten");
        properties.put("overwrite", overwriteProp);

        List<String> requiredFields = List.of("projectName", "pathInProject", "text");

        JsonSchema inputSchema = new JsonSchema("object", properties, requiredFields, null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(NAME)
            .title("Create New File")
            .description("Creates a new file in the specified project and path if conditions are met.")
            .inputSchema(inputSchema)
            .build();

        BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> handler = (exchange, request) -> {
            Map<String, Object> arguments = request.arguments();

            String projectName = (String) arguments.get("projectName");
            String pathInProject = (String) arguments.get("pathInProject");
            String text = (String) arguments.get("text");
            Boolean overwrite = Optional.ofNullable((Boolean) arguments.get("overwrite")).orElse(false);

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
                String errorMsg = "Path traversal detected in pathInProject: " + pathInProject;
                LOGGER.warning("Path traversal attempt detected: " + pathInProject);
                return CallToolResult.builder()
                    .isError(true)
                    .addTextContent(errorMsg)
                    .build();
            }

            if (Files.exists(targetFile) && !overwrite) {
                String errorMsg = "File already exists and overwrite is not allowed: " + projectBaseDir.relativize(targetFile);
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
                LOGGER.log(Level.SEVERE, "IOException while writing file " + targetFile, e);
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
