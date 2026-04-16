package org.rogmann.mcp2sdk.examples;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.function.BiFunction;

/**
 * MCP tool implementation for playing a video file.
 * Refactored to use McpServerFeatures.SyncToolSpecification.
 * The tool validates system properties for video folder, player executable, and arguments.
 * It ensures that only files within the configured video folder can be played.
 */
public class VideoPlayerTool {

    private static final Logger LOGGER = Logger.getLogger(VideoPlayerTool.class.getName());

    private static final String NAME = "video-player";

    private static final String PROP_FOLDER = "mcp.videosearch.folder";
    private static final String PROP_PLAYER = "mcp.videoplayer.executable";
    private static final String PROP_ARGS = "mcp.videoplayer.arguments";

    private static final DateTimeFormatter DF_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private VideoPlayerTool() {
        // Prevent instantiation, this class serves as a factory for the tool specification
    }

    /**
     * Creates the synchronous tool specification for playing videos.
     * Validates required system properties during specification creation.
     * @return a SyncToolSpecification ready to be registered with an MCP server
     * @throws RuntimeException if required system properties are not set or invalid
     */
    public static McpServerFeatures.SyncToolSpecification createSpecification() {
        // Validate system properties upfront
        String folderName = System.getProperty(PROP_FOLDER);
        if (folderName == null || folderName.isBlank()) {
            throw new RuntimeException("Folder property is not set: " + PROP_FOLDER);
        }
        File folderVideos = new File(folderName);
        if (!folderVideos.isDirectory()) {
            throw new RuntimeException("Invalid folder: " + folderVideos);
        }

        String pathExecutable = System.getProperty(PROP_PLAYER);
        if (pathExecutable == null || pathExecutable.isBlank()) {
            throw new RuntimeException("Player property is not set: " + PROP_PLAYER);
        }
        File fileExecutable = new File(pathExecutable);
        if (!fileExecutable.isFile()) {
            throw new RuntimeException("Invalid file: " + fileExecutable);
        }

        String sArgs = System.getProperty(PROP_ARGS);
        if (sArgs == null || sArgs.isBlank()) {
            throw new RuntimeException("Missing arguments property: " + PROP_ARGS);
        }
        String[] playerArgs = sArgs.split(" +");

        // Define Input Schema properties
        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> fileNameProp = new HashMap<>();
        fileNameProp.put("type", "string");
        fileNameProp.put("description", "File name of a video to be played (no path separators allowed)");
        properties.put("file_name", fileNameProp);

        List<String> requiredFields = List.of("file_name");

        JsonSchema inputSchema = new JsonSchema("object", properties, requiredFields, null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(NAME)
            .title("Filesystem Video Player")
            .description("Play a local video with a given file name from the configured video folder.")
            .inputSchema(inputSchema)
            .build();

        BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> handler = (exchange, request) -> {
            Map<String, Object> arguments = request.arguments();

            String fileName = (String) arguments.get("file_name");
            if (fileName == null || fileName.isBlank()) {
                return CallToolResult.builder()
                    .isError(true)
                    .addTextContent("Missing file_name in tool call")
                    .build();
            }

            // Security check: prevent path traversal
            if (fileName.contains(File.separator) || fileName.contains("/") || fileName.contains("\\")) {
                String errorMsg = "Invalid file name: path separators not allowed";
                LOGGER.warning("Path traversal attempt detected in file name: " + fileName);
                return CallToolResult.builder()
                    .isError(true)
                    .addTextContent(errorMsg)
                    .build();
            }

            final File fileVideo = new File(folderVideos, fileName);

            // Additional security check: ensure file is within the configured folder
            try {
                String canonicalVideoPath = fileVideo.getCanonicalPath();
                String canonicalFolderPath = folderVideos.getCanonicalPath();
                if (!canonicalVideoPath.startsWith(canonicalFolderPath)) {
                    String errorMsg = "Access denied: file is outside configured video folder";
                    LOGGER.warning("Attempted access outside video folder: " + fileName);
                    return CallToolResult.builder()
                        .isError(true)
                        .addTextContent(errorMsg)
                        .build();
                }
            } catch (IOException e) {
                String errorMsg = "Failed to resolve file path: " + e.getMessage();
                LOGGER.severe("IOException while resolving file path: " + e.getMessage());
                return CallToolResult.builder()
                    .isError(true)
                    .addTextContent(errorMsg)
                    .build();
            }

            if (!fileVideo.isFile()) {
                String errorMsg = "No such file: " + fileName;
                LOGGER.info("File not found: " + fileVideo.getAbsolutePath());
                return CallToolResult.builder()
                    .isError(true)
                    .addTextContent(errorMsg)
                    .build();
            }

            // Build and execute the player command
            List<String> cmdArgs = new ArrayList<>();
            cmdArgs.add(fileExecutable.getAbsolutePath());
            cmdArgs.addAll(Arrays.asList(playerArgs));
            cmdArgs.add(fileVideo.getAbsolutePath());

            Process process;
            try {
                process = new ProcessBuilder(cmdArgs).start();
            } catch (IOException e) {
                String errorMsg = "IO error after starting the player: " + e.getMessage();
                LOGGER.severe("IOException while starting player with: " + cmdArgs + " - " + e.getMessage());
                return CallToolResult.builder()
                    .isError(true)
                    .addTextContent(errorMsg)
                    .build();
            }

            // Gather file metadata for the response
            Instant tsFile = Instant.ofEpochMilli(fileVideo.lastModified());
            LocalDateTime ldtFile = LocalDateTime.ofInstant(tsFile, ZoneId.of("Europe/Berlin"));
            String status = String.format("Started playing file \"%s\" (pid %d, size %d bytes, saved on %s)",
                    fileName, process.pid(), fileVideo.length(), DF_DATE.format(ldtFile));

            LOGGER.info("Video player started: " + fileName + " (pid " + process.pid() + ")");

            // Prepare structured content for programmatic access
            Map<String, Object> structuredContent = new HashMap<>();
            structuredContent.put("status", "success");
            structuredContent.put("fileName", fileName);
            structuredContent.put("processId", process.pid());
            structuredContent.put("fileSize", fileVideo.length());
            structuredContent.put("lastModified", DF_DATE.format(ldtFile));
            structuredContent.put("message", status);

            return CallToolResult.builder()
                .isError(false)
                .addTextContent(status)
                .structuredContent(structuredContent)
                .build();
        };

        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler(handler)
            .build();
    }
}
