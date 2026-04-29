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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MCP tool implementation for editing a file in a project using search-replace.
 * The tool ensures that only allowed projects and safe paths are handled.
 * It supports replacing all occurrences or just the first occurrence.
 */
public class EditFileTool {

    private static final Logger LOGGER = LoggerFactory.getLogger(EditFileTool.class);
    private static final String NAME = "edit_file";

    /** tool state (active-flag, statistics) */
    private final ToolState state;

    private EditFileTool() {
        state = new ToolState();
    }

    /**
     * Creates the synchronous tool specification for editing file content.
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

        Map<String, Object> oldStringProp = new HashMap<>();
        oldStringProp.put("type", "string");
        oldStringProp.put("description", "The string to search for in the file");
        properties.put("oldString", oldStringProp);

        Map<String, Object> newStringProp = new HashMap<>();
        newStringProp.put("type", "string");
        newStringProp.put("description", "The string to replace the old string with");
        properties.put("newString", newStringProp);

        Map<String, Object> replaceAllProp = new HashMap<>();
        replaceAllProp.put("type", "boolean");
        replaceAllProp.put("description", "Whether to replace all occurrences (default: false)");
        properties.put("replaceAll", replaceAllProp);

        List<String> requiredFields = List.of("projectName", "pathInProject", "oldString", "newString");

        JsonSchema inputSchema = new JsonSchema("object", properties, requiredFields, null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(NAME)
            .title("Edit File")
            .description("Edits a file in the specified project by searching and replacing text.")
            .inputSchema(inputSchema)
            .build();

        EditFileTool toolImpl = new EditFileTool();

        return new ToolSpecWithState(McpServerFeatures.SyncToolSpecification.builder()
                    .tool(tool)
                    .callHandler(toolImpl::call)
                    .build(),
                toolImpl.state);
    }

    McpSchema.CallToolResult call(McpSyncServerExchange exchange, CallToolRequest request) {
        // Increment call count
        state.callCount().incrementAndGet();

        Map<String, Object> arguments = request.arguments();

        String projectName = (String) arguments.get("projectName");
        String pathInProject = (String) arguments.get("pathInProject");
        String oldString = (String) arguments.get("oldString");
        String newString = (String) arguments.get("newString");
        Boolean replaceAll = Optional.ofNullable((Boolean) arguments.get("replaceAll")).orElse(false);

        Map<String, Object> result = new HashMap<>();

        // Validate oldString is not empty to prevent infinite loops or unintended behavior
        if (oldString == null || oldString.isEmpty()) {
            LOGGER.warn("EditFileTool called with empty oldString");
            return CallToolResult.builder()
                    .isError(true)
                    .addTextContent("oldString cannot be empty")
                    .build();
        }

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

        if (!Files.exists(targetFile)) {
            String errorMsg = "File does not exist in project " + projectName + ": " + projectDir.relativize(targetFile);
            LOGGER.info("File not found for editing: " + targetFile);
            return CallToolResult.builder()
                    .isError(true)
                    .addTextContent(errorMsg)
                    .build();
        }

        if (Files.isDirectory(targetFile)) {
            String errorMsg = "Path refers to a directory, not a file: " + projectDir.relativize(targetFile);
            LOGGER.info("Cannot edit directory: " + targetFile);
            return CallToolResult.builder()
                    .isError(true)
                    .addTextContent(errorMsg)
                    .build();
        }

        try {
            String content = Files.readString(targetFile);
            String newContent;
            int replacements = 0;

            if (replaceAll) {
                // Count occurrences
                int index = 0;
                while ((index = content.indexOf(oldString, index)) != -1) {
                    replacements++;
                    index += oldString.length();
                }

                if (replacements > 0) {
                    newContent = content.replace(oldString, newString);
                } else {
                    newContent = content;
                }
            } else {
                // Replace first occurrence only
                int index = content.indexOf(oldString);
                if (index != -1) {
                    replacements = 1;
                    newContent = content.substring(0, index) + newString + content.substring(index + oldString.length());
                } else {
                    newContent = content;
                }
            }

            if (replacements > 0) {
                state.callsOk().incrementAndGet();

                Files.writeString(targetFile, newContent);
                String successMsg = "Successfully replaced " + replacements + " occurrence(s) in file: " + projectDir.relativize(targetFile);
                LOGGER.info("Successfully edited file: " + targetFile + " (" + replacements + " replacements)");

                Map<String, Object> structuredContent = new HashMap<>();
                structuredContent.put("status", "success");
                structuredContent.put("replacements", replacements);
                structuredContent.put("message", successMsg);

                return CallToolResult.builder()
                        .isError(false)
                        .addTextContent(successMsg)
                        .structuredContent(structuredContent)
                        .build();
            } else {
                // Find longest matching prefix and mismatch details for debugging
                Map<String, Object> prefixInfo = findLongestPrefix(projectDir, targetFile, content, oldString);
                String noMatchMsg = (String) prefixInfo.get("message");
                LOGGER.info("No matches found in file: " + targetFile);

                Map<String, Object> structuredContent = new HashMap<>();
                structuredContent.put("status", "success");
                structuredContent.put("replacements", 0);
                structuredContent.put("message", noMatchMsg);
                structuredContent.put("longestPrefixLength", prefixInfo.get("longestPrefixLength"));
                structuredContent.put("searchedChar", prefixInfo.get("searchedChar"));
                structuredContent.put("actualChar", prefixInfo.get("actualChar"));

                return CallToolResult.builder()
                        .isError(false)
                        .addTextContent(noMatchMsg)
                        .structuredContent(structuredContent)
                        .build();
            }
        } catch (IOException e) {
            String errorMsg = "Failed to edit file: " + e.getMessage();
            LOGGER.error("IOException while editing file {}", targetFile, e);

            return CallToolResult.builder()
                    .isError(true)
                    .addTextContent(errorMsg)
                    .build();
        }
    }

    private static Map<String, Object> findLongestPrefix(Path projectDir, Path targetFile, String content, String oldString) {
        Map<String, Object> result = new HashMap<>();
        int maxPrefixLength = 0;
        char searchedChar = 0;
        char actualChar = 0;
        int searchedCharUnicode = 0;
        int actualCharUnicode = 0;

        for (int i = 0; i < content.length(); i++) {
            int prefixLen = 0;
            for (int j = 0; j < oldString.length() && (i + j) < content.length(); j++) {
                char oldChar = oldString.charAt(j);
                char contentChar = content.charAt(i + j);
                
                if (oldChar == contentChar) {
                    prefixLen++;
                } else {
                    // Mismatch found
                    if (prefixLen >= maxPrefixLength) {
                        maxPrefixLength = prefixLen;
                        searchedChar = oldChar;
                        actualChar = contentChar;
                        searchedCharUnicode = (int) oldChar;
                        actualCharUnicode = (int) contentChar;
                    }
                    break;
                }
            }
            if (prefixLen == oldString.length()) {
                // Full match found (shouldn't happen since indexOf returned -1)
                break;
            }
        }

        String message = "No occurrences of oldString found in file: " + projectDir.relativize(targetFile) + 
            ". Longest matching prefix length: " + maxPrefixLength + 
            ", searched char: '" + searchedChar + "' (U+" + String.format("%04X", searchedCharUnicode) + ")" +
            ", actual char: '" + actualChar + "' (U+" + String.format("%04X", actualCharUnicode) + ")";
        
        result.put("message", message);
        result.put("longestPrefixLength", maxPrefixLength);
        result.put("searchedChar", "'" + searchedChar + "' (U+" + String.format("%04X", searchedCharUnicode) + ")");
        result.put("actualChar", "'" + actualChar + "' (U+" + String.format("%04X", actualCharUnicode) + ")");
        
        return result;
    }
}
