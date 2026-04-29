package org.rogmann.mcp2sdk.examples;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import org.rogmann.mcp2sdk.ToolSpecWithState;
import org.rogmann.mcp2sdk.ToolState;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * MCP tool implementation for searching local video files by keyword.
 * Refactored to use McpServerFeatures.SyncToolSpecification with Tool-Toggle mechanism.
 * The tool searches a configured folder for .mp4 and .webm files matching provided keywords.
 */
public class VideoSearchTool {

    private static final Logger LOGGER = Logger.getLogger(VideoSearchTool.class.getName());

    private static final String NAME = "video-search";
    private static final String PROP_FOLDER = "mcp.videosearch.folder";

    /** tool state (active-flag, statistics) */
    private final ToolState state;

    /** Video folder */
    private File folderVideos;

    private VideoSearchTool() {
        state = new ToolState();
    }

    /**
     * Creates the synchronous tool specification for searching video files.
     * @return the tool specification and its state
     */
    public static ToolSpecWithState createToolInstance() {
        // Define Input Schema properties
        Map<String, Object> properties = new HashMap<>();

        // Define 'keywords' property (array of strings)
        Map<String, Object> keywordsProp = new HashMap<>();
        keywordsProp.put("type", "array");
        keywordsProp.put("description", "One or more keywords which might be in the title or description of a video.");
        
        Map<String, Object> itemsSchema = new HashMap<>();
        itemsSchema.put("type", "string");
        keywordsProp.put("items", itemsSchema);

        properties.put("keywords", keywordsProp);

        List<String> requiredFields = List.of("keywords");

        JsonSchema inputSchema = new JsonSchema("object", properties, requiredFields, null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(NAME)
            .title("Filesystem Video Files Provider")
            .description("Search for local video files by keyword")
            .inputSchema(inputSchema)
            .build();

        VideoSearchTool toolImpl = new VideoSearchTool();

        // Get folder configuration
        String folderName = System.getProperty(PROP_FOLDER);
        if (folderName == null) {
            throw new RuntimeException("Folder property is not set: " + PROP_FOLDER);
        }
        File folderVideos = new File(folderName);
        if (!folderVideos.isDirectory()) {
            throw new RuntimeException("Invalid folder: " + folderVideos.getAbsolutePath());
        }
        toolImpl.folderVideos = folderVideos;

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

        @SuppressWarnings("unchecked")
        List<String> keywords = (List<String>) arguments.get("keywords");

        if (keywords == null || keywords.isEmpty()) {
            return CallToolResult.builder()
                .isError(true)
                .addTextContent("Missing keywords in tool-call")
                .build();
        }

        // Normalize keywords as per original logic
        List<String> lKeywords = keywords.stream()
            .map(s -> s.replace(" ", ""))
            .map(String::toLowerCase)
            .toList();

        List<String> results = new ArrayList<>();
        File[] files = folderVideos.listFiles();
        
        if (files != null) {
            for (File file : files) {
                if (!file.isFile()) {
                    continue;
                }
                String name = file.getName().toLowerCase().replaceAll("_", "").replaceAll(" ", "");
                if (name.endsWith(".mp4") || name.endsWith(".webm")) {
                    for (String lKeyword : lKeywords) {
                        if (name.contains(lKeyword)) {
                            results.add("File-name of a local video: " + file.getName());
                            break; // Match found for this file, move to next file
                        }
                    }
                }
            }
        }

        // Increment success counter
        state.callsOk().incrementAndGet();

        if (results.isEmpty()) {
            LOGGER.info("No video files found matching keywords: " + lKeywords);
            // Return success but with message indicating no results, or empty content
            // Original returned empty list, MCP usually expects some content or isError
            // We return success with a text indicating no matches found to be polite
            return CallToolResult.builder()
                .isError(false)
                .addTextContent("No video files found matching the provided keywords.")
                .build();
        }

        CallToolResult.Builder resultBuilder = CallToolResult.builder().isError(false);
        for (String resultText : results) {
            resultBuilder.addTextContent(resultText);
        }

        LOGGER.fine("Found " + results.size() + " video files matching keywords.");
        return resultBuilder.build();
    }
}
