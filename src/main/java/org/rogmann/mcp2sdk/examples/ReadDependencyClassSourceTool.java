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
import org.springframework.core.env.Environment;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;

/**
 * MCP tool implementation for reading Java class sources from Maven dependencies in the local M2 repository.
 * Supports three modes: read_metadata, read_class_structure, and read_source.
 */
public class ReadDependencyClassSourceTool {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReadDependencyClassSourceTool.class);

    private static final String NAME = "read_dependency_class_source_tool";

    /** Default M2 repository path */
    private static final String DEFAULT_M2_PATH = System.getProperty("user.home") + "/.m2/repository";

    /** Default Maven command */
    private static final String DEFAULT_MAVEN_COMMAND = "mvn";

    /** Spring Environment for configuration (set by McpConfig) */
    private static Environment environment;

    /**
     * Sets the Spring Environment for configuration lookup.
     * Called by McpConfig during initialization.
     * @param env the Spring Environment
     */
    public static void setEnvironment(Environment env) {
        environment = env;
    }

    /** Tool state (active-flag, statistics) */
    private final ToolState state;

    private ReadDependencyClassSourceTool() {
        state = new ToolState();
    }

    /**
     * Creates the synchronous tool specification for reading dependency class sources.
     * @return the tool specification and its state
     */
    public static ToolSpecWithState createToolInstance() {
        // Define Input Schema properties
        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> pathPomProp = new HashMap<>();
        pathPomProp.put("type", "string");
        pathPomProp.put("description", "Path to the pom.xml whose classpath should be determined. Can be relative or absolute. If relative and projectName is provided, the path is resolved relative to the project directory.");
        properties.put("path_pom", pathPomProp);

        Map<String, Object> projectNameProp = new HashMap<>();
        projectNameProp.put("type", "string");
        projectNameProp.put("description", "Optional: Name of the project. Used to resolve relative paths to pom.xml when the path is not found directly.");
        properties.put("projectName", projectNameProp);

        Map<String, Object> classNameProp = new HashMap<>();
        classNameProp.put("type", "array");
        classNameProp.put("description", "One or more fully qualified class names or class names without package specification");
        classNameProp.put("items", new HashMap<String, Object>() {{ put("type", "string"); }});
        properties.put("class_name", classNameProp);

        Map<String, Object> commandProp = new HashMap<>();
        commandProp.put("type", "string");
        commandProp.put("description", "read_metadata (output only JAR, class size and modification date), read_class_structure (output class structure from .class file), read_source (output source; if source ZIP is missing, it will be downloaded via Maven if tools.maven.autoDownloadSources=true)");
        commandProp.put("enum", List.of("read_metadata", "read_class_structure", "read_source"));
        properties.put("command", commandProp);

        Map<String, Object> startLineProp = new HashMap<>();
        startLineProp.put("type", "integer");
        startLineProp.put("description", "Start line (default is 1)");
        properties.put("start_line", startLineProp);

        Map<String, Object> endLineProp = new HashMap<>();
        endLineProp.put("type", "integer");
        endLineProp.put("description", "1-based end line (default is start_line + 999)");
        properties.put("end_line", endLineProp);

        List<String> requiredFields = List.of("path_pom", "class_name", "command");

        JsonSchema inputSchema = new JsonSchema("object", properties, requiredFields, null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(NAME)
                .title("Read Dependency Class Source")
                .description("This tool reads the source of Java classes in Maven dependencies (i.e., in the local M2) from the current classpath. " +
                        "If source JARs are missing, they can be automatically downloaded via Maven (controlled by system property tools.maven.autoDownloadSources, default: true).")
                .inputSchema(inputSchema)
                .build();

        ReadDependencyClassSourceTool toolImpl = new ReadDependencyClassSourceTool();

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

        String pathPom = (String) arguments.get("path_pom");
        String projectName = (String) arguments.get("projectName");
        @SuppressWarnings("unchecked")
        List<String> classNames = (List<String>) arguments.get("class_name");
        String command = (String) arguments.get("command");
        Integer startLine = (Integer) arguments.get("start_line");
        Integer endLine = (Integer) arguments.get("end_line");

        // Validate inputs
        if (pathPom == null || pathPom.isBlank()) {
            return CallToolResult.builder()
                    .isError(true)
                    .addTextContent("path_pom is required")
                    .build();
        }

        if (classNames == null || classNames.isEmpty()) {
            return CallToolResult.builder()
                    .isError(true)
                    .addTextContent("class_name must contain at least one class name")
                    .build();
        }

        if (command == null || !List.of("read_metadata", "read_class_structure", "read_source").contains(command)) {
            return CallToolResult.builder()
                    .isError(true)
                    .addTextContent("command must be one of: read_metadata, read_class_structure, read_source")
                    .build();
        }

        // Get configuration from Spring Environment (with fallback to system properties/defaults)
        String m2Path = getProperty("tools.maven.m2-path", "tools.maven.m2Path", DEFAULT_M2_PATH);
        String mavenCommand = getProperty("tools.maven.path", DEFAULT_MAVEN_COMMAND);
        boolean autoDownloadSources = Boolean.parseBoolean(
                getProperty("tools.maven.auto-download-sources", "tools.maven.autoDownloadSources", "true"));
        int timeoutSeconds = Integer.parseInt(
                getProperty("tools.maven.timeout-seconds", "tools.maven.timeoutSeconds", "120"));
        LOGGER.info("Configuration: m2Path={}, mavenCommand={}, autoDownloadSources={}, timeoutSeconds={}", 
                m2Path, mavenCommand, autoDownloadSources, timeoutSeconds);

        // Resolve pomPath: try direct path first, then use WorkProject if projectName is provided
        Path pomPath = Path.of(pathPom);
        if (!Files.exists(pomPath)) {
            // If path not found and projectName is provided, try to resolve via WorkProject
            if (projectName != null && !projectName.isBlank()) {
                Map<String, Object> lookupResult = new HashMap<>();
                WorkProject workProject = WorkProject.lookupProject(projectName, lookupResult);
                if (workProject == null) {
                    String error = (String) lookupResult.get("error");
                    return CallToolResult.builder()
                            .isError(true)
                            .addTextContent(error != null ? error : "Project not found: " + projectName)
                            .build();
                }
                // Resolve pomPath relative to project directory
                try {
                    pomPath = workProject.projectDir().resolve(pathPom).normalize();
                } catch (InvalidPathException e) {
                    LOGGER.error("Can't resolve path ({}) of project directory ({})", pathPom, workProject.projectDir(), e);
                    throw new RuntimeException("Internal path error");
                }

                // Security check: ensure pomPath is within project directory
                if (!pomPath.startsWith(workProject.projectDir())) {
                    return CallToolResult.builder()
                            .isError(true)
                            .addTextContent("Path traversal detected in path_pom: " + pathPom)
                            .build();
                }
                
                if (!Files.exists(pomPath)) {
                    return CallToolResult.builder()
                            .isError(true)
                            .addTextContent("pom.xml not found in project " + projectName + ": " + pathPom)
                            .build();
                }
            } else {
                return CallToolResult.builder()
                        .isError(true)
                        .addTextContent("pom.xml not found: " + pathPom + " (consider providing projectName for relative paths)")
                        .build();
            }
        }
        LOGGER.info("POM-Path: {}", pomPath);

        try {
            // Resolve classpath using Maven
            Map<String, Path> classpathMap = resolveClasspath(pomPath, mavenCommand, m2Path);

            // Process each requested class
            List<Map<String, Object>> results = new ArrayList<>();
            for (String className : classNames) {
                Map<String, Object> classResult = processClass(className, command, classpathMap, m2Path, startLine, endLine, 
                        pomPath, mavenCommand, autoDownloadSources, timeoutSeconds);
                results.add(classResult);
            }

            // Increment success counter
            state.callsOk().incrementAndGet();

            // Build response
            Map<String, Object> structuredContent = new HashMap<>();
            structuredContent.put("status", "success");
            structuredContent.put("command", command);
            structuredContent.put("results", results);
            structuredContent.put("classCount", results.size());

            // Build detailed text output
            StringBuilder textOutput = new StringBuilder();
            textOutput.append("Processed ").append(results.size()).append(" class(s) with command: ").append(command).append("\n\n");
            for (Map<String, Object> result : results) {
                textOutput.append("Class: ").append(result.get("className")).append("\n");
                textOutput.append("  Status: ").append(result.get("status")).append("\n");
                if (result.containsKey("jarPath")) {
                    textOutput.append("  JAR: ").append(result.get("jarPath")).append("\n");
                }
                if ("read_metadata".equals(command) && "success".equals(result.get("status"))) {
                    if (result.containsKey("size")) {
                        textOutput.append("  Size: ").append(result.get("size")).append(" bytes\n");
                    }
                    if (result.containsKey("modificationTime")) {
                        textOutput.append("  Modified: ").append(result.get("modificationTime")).append("\n");
                    }
                    if (result.containsKey("method")) {
                        textOutput.append("  Compression: ").append(result.get("method")).append("\n");
                    }
                }
                if ("read_class_structure".equals(command) && "success".equals(result.get("status"))) {
                    if (result.containsKey("structure")) {
                        textOutput.append("  Structure:\n").append(result.get("structure")).append("\n");
                    }
                }
                if ("read_source".equals(command) && "success".equals(result.get("status"))) {
                    if (result.containsKey("source")) {
                        textOutput.append("  Source:\n").append(result.get("source")).append("\n");
                    }
                    if (result.containsKey("message")) {
                        textOutput.append("  ").append(result.get("message")).append("\n");
                    }
                }
                if (result.containsKey("error")) {
                    textOutput.append("  Error: ").append(result.get("error")).append("\n");
                }
                textOutput.append("\n");
            }

            return CallToolResult.builder()
                    .isError(false)
                    .addTextContent(textOutput.toString())
                    .structuredContent(structuredContent)
                    .build();

        } catch (IOException e) {
            LOGGER.error("Exception during dependency class source reading", e);
            return CallToolResult.builder()
                    .isError(true)
                    .addTextContent("Failed to read dependency class source: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Resolves the Maven classpath by executing mvn dependency:build-classpath
     */
    private Map<String, Path> resolveClasspath(Path pomPath, String mavenCommand, String m2Path) throws IOException {
        Map<String, Path> classpathMap = new HashMap<>();

        // Create temp file in system temp directory
        Path tempDir = Files.createTempDirectory("mcp-read-dep-");
        Path cpFile = tempDir.resolve("cp.txt");

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    mavenCommand,
                    "dependency:build-classpath",
                    "-Dmdep.outputFile=" + cpFile,
                    "-q",
                    "-f", pomPath.toString()
            );
            // Handle case where getParent() returns null (pomPath is at root level)
            Path pomDir = pomPath.getParent();
            pb.directory(pomDir != null ? pomDir.toFile() : new File("."));
            pb.redirectErrorStream(true);

            Process process = pb.start();
            int maxSeconds = 60;
            boolean isOk;
            try {
                isOk = process.waitFor(maxSeconds, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Couldn't read class-path of pom (%s) in %d seconds".formatted(pomPath, maxSeconds), e);
            }

            if (!isOk) {
                String errorOutput = new BufferedReader(new InputStreamReader(process.getInputStream()))
                        .lines().collect(Collectors.joining("\n"));
                throw new IOException("Maven command failed: " + errorOutput);
            } else if (process.exitValue() != 0) {
                String errorOutput = new BufferedReader(new InputStreamReader(process.getInputStream()))
                        .lines().collect(Collectors.joining("\n"));
                throw new IOException("Maven command failed with " + process.exitValue() + ": " + errorOutput);
            }

            // Read classpath file
            if (Files.exists(cpFile)) {
                String classpath = Files.readString(cpFile).trim();
                String[] jars = classpath.split(File.pathSeparator);

                for (String jarPath : jars) {
                    Path jar = Path.of(jarPath);
                    if (Files.exists(jar)) {
                        // Extract artifact info from path (pass m2Path to strip prefix)
                        ArtifactInfo artifactInfo = extractArtifactInfo(jar, m2Path);
                        if (artifactInfo != null) {
                            String key = artifactInfo.groupId + ":" + artifactInfo.artifactId;
                            classpathMap.put(key, jar);
                        }
                    }
                }
            }
        } finally {
            // Clean up temp file and directory
            Files.deleteIfExists(cpFile);
            Files.deleteIfExists(tempDir);
        }

        return classpathMap;
    }

    /**
     * Extracts artifact information from a JAR path
     * @param jarPath the path to the JAR file
     * @return the artifact info, or null if the path doesn't match the expected pattern
     */
    private ArtifactInfo extractArtifactInfo(Path jarPath) {
        return extractArtifactInfo(jarPath, null);
    }

    /**
     * Extracts artifact information from a JAR path
     * @param jarPath the path to the JAR file
     * @param m2Path optional path to the M2 repository (used to strip the prefix from jarPath)
     * @return the artifact info, or null if the path doesn't match the expected pattern
     */
    private ArtifactInfo extractArtifactInfo(Path jarPath, String m2Path) {
        String pathStr = jarPath.toString();
        
        // If m2Path is provided, strip it from the path to get the relative path
        if (m2Path != null && !m2Path.isBlank()) {
            Path m2Repo = Path.of(m2Path);
            String m2RepoStr = m2Repo.toString();
            if (pathStr.startsWith(m2RepoStr)) {
                pathStr = pathStr.substring(m2RepoStr.length());
                // Remove leading separator
                if (pathStr.startsWith(File.separator)) {
                    pathStr = pathStr.substring(1);
                }
            }
        }
        
        // Pattern: groupId/artifactId/version/artifactId-version.jar
        // groupId can have multiple parts (e.g., org/slf4j or com/fasterxml/jackson/core)
        Pattern pattern = Pattern.compile("(.+)/([^/]+)/([^/]+)/\\2-\\3(?:-sources)?\\.jar$");
        Matcher matcher = pattern.matcher(pathStr);

        if (matcher.find()) {
            String groupIdPath = matcher.group(1);
            String groupId = groupIdPath.replace(File.separator, ".");
            String artifactId = matcher.group(2);
            String version = matcher.group(3);

            return new ArtifactInfo(groupId, artifactId, version);
        }

        return null;
    }

    /**
     * Processes a single class based on the command type
     */
    private Map<String, Object> processClass(String className, String command,
                                              Map<String, Path> classpathMap, String m2Path,
                                              Integer startLine, Integer endLine,
                                              Path pomPath, String mavenCommand,
                                              boolean autoDownloadSources, int timeoutSeconds) {
        Map<String, Object> result = new HashMap<>();
        result.put("className", className);
        result.put("command", command);

        try {
            // For read_source command, prefer sources JAR; otherwise use binary JAR
            boolean preferSources = "read_source".equals(command);
            JarLocation jarLocation = findJarForClass(className, classpathMap, m2Path, preferSources, pomPath, mavenCommand,
                    autoDownloadSources, timeoutSeconds);

            if (jarLocation == null) {
                result.put("status", "not_found");
                result.put("error", "Class not found in any dependency JAR");
                LOGGER.info("Class {}: not found", className);
                return result;
            }
            LOGGER.info("Class {}: JAR {} (isSources={})", className, jarLocation.jarPath(), jarLocation.isSources());

            result.put("jarPath", jarLocation.jarPath().toString());
            result.put("isSources", jarLocation.isSources());

            switch (command) {
                case "read_metadata":
                    readMetadata(jarLocation, className, result);
                    break;
                case "read_class_structure":
                    readClassStructure(jarLocation, className, result, startLine, endLine);
                    break;
                case "read_source":
                    readSource(jarLocation, className, result, startLine, endLine);
                    break;
                default:
                    result.put("status", "error");
                    result.put("error", "Unknown command: " + command);
                    return result;
            }
            return result;

        } catch (Exception e) {
            LOGGER.error("Error processing class {}", className, e);
            result.put("status", "error");
            result.put("error", e.getMessage());
            return result;
        }
    }

    /**
     * Finds the JAR file containing the specified class
     * @param className the fully qualified class name
     * @param classpathMap map of artifact coordinates to JAR paths (from Maven classpath)
     * @param m2Path path to the M2 repository
     * @param preferSources if true, search for sources JAR first; otherwise prefer binary JAR from classpath
     * @param pomPath path to the pom.xml (used for downloading sources)
     * @param mavenCommand Maven command to use
     * @param autoDownloadSources whether to automatically download source JARs if missing
     * @param timeoutSeconds timeout in seconds for Maven operations
     */
    private JarLocation findJarForClass(String className, Map<String, Path> classpathMap, String m2Path, 
                                         boolean preferSources, Path pomPath, String mavenCommand,
                                         boolean autoDownloadSources, int timeoutSeconds) throws IOException {
        String classNamePath = className.replace(".", "/") + ".java";
        String classNameClass = className.replace(".", "/") + ".class";

        if (preferSources) {
            // For read_source: search M2 repository for sources JAR first
            Path m2Repo = Path.of(m2Path);
            if (Files.isDirectory(m2Repo)) {
                try (var stream = Files.walk(m2Repo)) {
                    for (Path jarPath : stream.filter(p -> p.toString().endsWith("-sources.jar"))
                            .toList()) {
                        if (hasEntryInJar(jarPath, classNamePath)) {
                            LOGGER.debug("Found sources JAR: {}", jarPath);
                            return new JarLocation(jarPath, true);
                        }
                    }
                }
            }
            
            // Source JAR not found - try to download it if auto-download is enabled
            if (autoDownloadSources && pomPath != null) {
                LOGGER.info("Source JAR not found for class {}, attempting to download...", className);
                ArtifactInfo artifactInfo = findArtifactForClass(className, classpathMap, m2Path);
                if (artifactInfo != null) {
                    boolean downloaded = downloadSourceJar(artifactInfo, mavenCommand, pomPath, timeoutSeconds);
                    if (downloaded) {
                        // Retry search after download
                        try (var stream = Files.walk(m2Repo)) {
                            for (Path jarPath : stream.filter(p -> p.toString().endsWith("-sources.jar"))
                                    .toList()) {
                                if (hasEntryInJar(jarPath, classNamePath)) {
                                    LOGGER.info("Successfully downloaded and found sources JAR: {}", jarPath);
                                    return new JarLocation(jarPath, true);
                                }
                            }
                        }
                    }
                }
            }
        }

        // Try to find in existing classpath JARs (binary JARs from Maven)
        for (Path jarPath : classpathMap.values()) {
            if (hasClassInJar(jarPath, className)) {
                LOGGER.debug("Found binary JAR in classpath: {}", jarPath);
                return new JarLocation(jarPath, false);
            }
        }

        // If not found in classpath, search M2 repository for binary JAR
        Path m2Repo = Path.of(m2Path);
        if (Files.isDirectory(m2Repo)) {
            try (var stream = Files.walk(m2Repo)) {
                for (Path jarPath : stream.filter(p -> p.toString().endsWith(".jar") && !p.toString().endsWith("-sources.jar"))
                        .toList()) {
                    if (hasEntryInJar(jarPath, classNameClass)) {
                        LOGGER.debug("Found binary JAR in M2: {}", jarPath);
                        return new JarLocation(jarPath, false);
                    }
                }
            }
        }

        return null;
    }

    /**
     * Finds artifact information for a class by searching in classpath JARs
     */
    private ArtifactInfo findArtifactForClass(String className, Map<String, Path> classpathMap, String m2Path) throws IOException {
        String classNameClass = className.replace(".", "/") + ".class";
        
        // First try to find in classpath map
        for (Map.Entry<String, Path> entry : classpathMap.entrySet()) {
            if (hasClassInJar(entry.getValue(), className)) {
                String[] parts = entry.getKey().split(":");
                if (parts.length >= 2) {
                    // Extract version from JAR path
                    String version = extractVersionFromJarPath(entry.getValue());
                    return new ArtifactInfo(parts[0], parts[1], version);
                }
            }
        }
        
        // Fallback: search M2 for the binary JAR and extract artifact info
        Path m2Repo = Path.of(m2Path);
        if (Files.isDirectory(m2Repo)) {
            try (var stream = Files.walk(m2Repo)) {
                for (Path jarPath : stream.filter(p -> p.toString().endsWith(".jar") && !p.toString().endsWith("-sources.jar"))
                        .toList()) {
                    if (hasEntryInJar(jarPath, classNameClass)) {
                        return extractArtifactInfo(jarPath, m2Path);
                    }
                }
            }
        }
        
        return null;
    }

    /**
     * Extracts version from a JAR path
     */
    private String extractVersionFromJarPath(Path jarPath) {
        String pathStr = jarPath.toString();
        Pattern pattern = Pattern.compile("(.+)/([^/]+)/([^/]+)/\\2-\\3\\.jar$");
        Matcher matcher = pattern.matcher(pathStr);
        if (matcher.find()) {
            return matcher.group(3);
        }
        // Fallback: try to extract from filename
        String fileName = jarPath.getFileName().toString();
        Matcher matcher2 = Pattern.compile("(.+)-([0-9][^-]*)(?:-sources)?\\.jar$").matcher(fileName);
        if (matcher2.find()) {
            return matcher2.group(2);
        }
        return "unknown";
    }

    /**
     * Downloads source JAR for an artifact using Maven
     * @param artifactInfo the artifact coordinates
     * @param mavenCommand Maven command to use
     * @param pomPath path to pom.xml (used as working directory)
     * @param timeoutSeconds timeout in seconds for the Maven operation
     * @return true if download was successful, false otherwise
     */
    private boolean downloadSourceJar(ArtifactInfo artifactInfo, String mavenCommand, Path pomPath, int timeoutSeconds) {
        LOGGER.info("Downloading source JAR for {}:{}", artifactInfo.groupId(), artifactInfo.artifactId());
        
        // Use mvn dependency:get to download specific source JAR
        String artifactCoord = String.format("%s:%s:%s:jar:sources", 
                artifactInfo.groupId(), artifactInfo.artifactId(), artifactInfo.version());
        
        ProcessBuilder pb = new ProcessBuilder(
                mavenCommand,
                "dependency:get",
                "-Dartifact=" + artifactCoord,
                "-q"
        );
        
        // Use pom directory as working directory
        Path pomDir = pomPath.getParent();
        pb.directory(pomDir != null ? pomDir.toFile() : new File("."));
        pb.redirectErrorStream(true);
        
        try {
            Process process = pb.start();
            boolean isOk = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            
            if (!isOk) {
                LOGGER.warn("Maven download timed out after {} seconds", timeoutSeconds);
                process.destroyForcibly();
                return false;
            }
            
            if (process.exitValue() != 0) {
                String errorOutput = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))
                        .lines().collect(Collectors.joining("\n"));
                LOGGER.warn("Maven download failed with exit code {}: {}", process.exitValue(), errorOutput);
                return false;
            }
            
            LOGGER.info("Successfully downloaded source JAR for {}:{}", artifactInfo.groupId(), artifactInfo.artifactId());
            return true;
            
        } catch (IOException | InterruptedException e) {
            LOGGER.warn("Failed to download source JAR for {}:{}: {}", 
                    artifactInfo.groupId(), artifactInfo.artifactId(), e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    /**
     * Checks if a JAR contains the specified class
     */
    private boolean hasClassInJar(Path jarPath, String className) throws IOException {
        String classEntry = className.replace(".", "/") + ".class";
        String sourceEntry = className.replace(".", "/") + ".java";

        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            return jarFile.getEntry(classEntry) != null || jarFile.getEntry(sourceEntry) != null;
        }
    }

    /**
     * Checks if a JAR contains the specified entry
     */
    private boolean hasEntryInJar(Path jarPath, String entryName) throws IOException {
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            return jarFile.getEntry(entryName) != null;
        }
    }

    /**
     * Reads metadata (JAR path, class size, modification date)
     */
    private void readMetadata(JarLocation jarLocation, String className,
                                              Map<String, Object> result) throws IOException {
        Path jarPath = jarLocation.jarPath();
        String entryName = jarLocation.isSources()
                ? className.replace(".", "/") + ".java"
                : className.replace(".", "/") + ".class";

        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            JarEntry entry = jarFile.getJarEntry(entryName);

            if (entry == null) {
                result.put("status", "not_found");
                result.put("error", "Entry not found in JAR: " + entryName);
                return;
            }

            result.put("status", "success");
            result.put("entryName", entryName);
            result.put("size", entry.getSize());
            result.put("compressedSize", entry.getCompressedSize());
            result.put("modificationTime", Instant.ofEpochMilli(entry.getTime()).toString());
            result.put("method", entry.getMethod() == ZipEntry.STORED ? "STORED" : "DEFLATED");
        }
    }

    /**
     * Reads class structure using javap (for binary JARs) or parses source (for source JARs)
     */
    private void readClassStructure(JarLocation jarLocation, String className,
                                                    Map<String, Object> result, Integer startLine, Integer endLine) throws IOException {
        int start = Optional.ofNullable(startLine).orElse(1);
        int end = Optional.ofNullable(endLine).orElse(start + 999);

        if (start < 1) {
            start = 1;
        }
        if (end < start) {
            end = start + 999;
        }

        if (jarLocation.isSources()) {
            // Parse source file for structure
            readStructureFromSource(jarLocation, className, result, start, end);
        } else {
            // Use javap for binary class
            readStructureFromBinary(jarLocation, className, result);
        }
    }

    /**
     * Reads class structure from binary .class file using javap
     */
    private void readStructureFromBinary(JarLocation jarLocation, String className,
                                                         Map<String, Object> result) throws IOException {
        Path jarPath = jarLocation.jarPath();

        // Run javap with -protected to show public and protected members (API)
        // Use the JAR directly as classpath instead of extracting the class file
        ProcessBuilder pb = new ProcessBuilder("javap", "-protected", "-cp", jarPath.toString(), className);

        Process process = pb.start();
        String output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))
                .lines().collect(Collectors.joining("\n"));
        String error = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))
                .lines().collect(Collectors.joining("\n"));

        final int maxSeconds = 30;
        try {
            process.waitFor(maxSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Couldn't read class-file (%s) in %d seconds".formatted(className, maxSeconds), e);
        }

        LOGGER.info("javap exit value: {}, output length: {}, error: {}", process.exitValue(), output.length(), error);

        if (process.exitValue() != 0) {
            result.put("status", "error");
            result.put("error", "javap failed: " + error);
            return;
        }

        result.put("status", "success");
        result.put("structure", output);
        result.put("type", "binary");
    }

    /**
     * Reads structure from source file (simplified parsing)
     */
    private void readStructureFromSource(JarLocation jarLocation, String className,
                                                         Map<String, Object> result, int startLine, int endLine) throws IOException {
        String sourceEntry = className.replace(".", "/") + ".java";
        Path jarPath = jarLocation.jarPath();

        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            JarEntry entry = jarFile.getJarEntry(sourceEntry);
            if (entry == null) {
                result.put("status", "not_found");
                result.put("error", "Source not found in JAR: " + sourceEntry);
                return;
            }

            // Read all lines into a list (single stream, proper cleanup)
            try (InputStream stream = jarFile.getInputStream(entry);
                 InputStreamReader isr = new InputStreamReader(stream, StandardCharsets.UTF_8);
                 BufferedReader reader = new BufferedReader(isr)) {

                List<String> allLines = reader.lines().toList();
                long totalLines = allLines.size();

                // Build the content with header information
                StringBuilder content = new StringBuilder();
                content.append("--- File: ").append(sourceEntry).append(" ---\n");
                content.append("Lines ").append(startLine).append("-").append(Math.min(endLine, totalLines)).append(" of ").append(totalLines).append("\n\n");

                // Extract only the requested range from the list
                int linesRead = 0;
                int startIdx = Math.max(0, startLine - 1);
                int endIdx = Math.min((int) totalLines, endLine);

                for (int i = startIdx; i < endIdx; i++) {
                    if (linesRead > 0) {
                        content.append("\n");
                    }
                    content.append(allLines.get(i));
                    linesRead++;
                }

                result.put("status", "success");
                result.put("structure", content.toString());
                result.put("type", "source");
                result.put("linesRead", linesRead);
                result.put("totalLines", totalLines);
                result.put("startLine", startLine);
                result.put("endLine", Math.min(endLine, (int) totalLines));

                if (totalLines > endLine) {
                    result.put("truncated", true);
                    result.put("message", String.format("Showing lines %d-%d of %d total. Use start_line=%d to continue.",
                            startLine, endLine, totalLines, endLine + 1));
                } else {
                    result.put("truncated", false);
                    result.put("message", String.format("File has %d lines total.", totalLines));
                }
            }
        }
    }

    /**
     * Reads source code from source JAR
     */
    private void readSource(JarLocation jarLocation, String className,
                                            Map<String, Object> result, Integer startLine, Integer endLine) throws IOException {
        int start = Optional.ofNullable(startLine).orElse(1);
        int end = Optional.ofNullable(endLine).orElse(start + 999);

        if (start < 1) {
            start = 1;
        }
        if (end < start) {
            end = start + 999;
        }

        // If we have a sources JAR, read from it
        if (jarLocation.isSources()) {
            readSourceFromJar(jarLocation.jarPath(), className, result, start, end);
        } else {
            // No sources available, try to get structure from binary
            result.put("status", "sources_not_available");
            result.put("message", "Source JAR not found. Returning class structure from binary instead.");
            readStructureFromBinary(jarLocation, className, result);
        }
    }

    /**
     * Reads source code from a JAR file
     */
    private void readSourceFromJar(Path jarPath, String className,
                                                   Map<String, Object> result, int startLine, int endLine) throws IOException {
        String sourceEntry = className.replace(".", "/") + ".java";

        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            JarEntry entry = jarFile.getJarEntry(sourceEntry);
            if (entry == null) {
                result.put("status", "not_found");
                result.put("error", "Source not found in JAR: " + sourceEntry);
                return;
            }

            // Read all lines into a list (single stream, proper cleanup)
            try (InputStream stream = jarFile.getInputStream(entry);
                 InputStreamReader isr = new InputStreamReader(stream, StandardCharsets.UTF_8);
                 BufferedReader reader = new BufferedReader(isr)) {

                List<String> allLines = reader.lines().toList();
                long totalLines = allLines.size();

                // Build the source content with header information
                StringBuilder content = new StringBuilder();
                content.append("--- File: ").append(sourceEntry).append(" ---\n");
                content.append("Lines ").append(startLine).append("-").append(Math.min(endLine, totalLines)).append(" of ").append(totalLines).append("\n\n");

                // Extract only the requested range from the list
                int linesRead = 0;
                int startIdx = Math.max(0, startLine - 1);
                int endIdx = Math.min((int) totalLines, endLine);

                for (int i = startIdx; i < endIdx; i++) {
                    if (linesRead > 0) {
                        content.append("\n");
                    }
                    content.append(String.format("%4d | %s", i + 1, allLines.get(i)));
                    linesRead++;
                }

                result.put("status", "success");
                result.put("source", content.toString());
                result.put("linesRead", linesRead);
                result.put("totalLines", totalLines);
                result.put("startLine", startLine);
                result.put("endLine", Math.min(endLine, (int) totalLines));
                result.put("jarPath", jarPath.toString());

                if (totalLines > endLine) {
                    result.put("truncated", true);
                    result.put("message", String.format("Showing lines %d-%d of %d total. Use start_line=%d to continue.",
                            startLine, endLine, totalLines, endLine + 1));
                } else {
                    result.put("truncated", false);
                    result.put("message", String.format("File has %d lines total.", totalLines));
                }
            }
        }
    }

    /**
     * Gets a property value from Spring Environment or falls back to default.
     * Tries multiple property names (for kebab-case and camelCase support).
     * @param keys the property keys to try (in order of preference)
     * @param defaultValue the default value if not found
     * @return the property value or default
     */
    private static String getProperty(String[] keys, String defaultValue) {
        if (environment != null) {
            for (String key : keys) {
                String value = environment.getProperty(key);
                if (value != null) {
                    LOGGER.debug("Resolved property {} = {}", key, value);
                    return value;
                }
            }
        }
        // Fallback to system property
        for (String key : keys) {
            String value = System.getProperty(key);
            if (value != null) {
                LOGGER.debug("Resolved system property {} = {}", key, value);
                return value;
            }
        }
        LOGGER.debug("Using default value for properties {}: {}", String.join(",", keys), defaultValue);
        return defaultValue;
    }

    /**
     * Gets a property value from Spring Environment or falls back to default.
     * @param key the property key
     * @param defaultValue the default value if not found
     * @return the property value or default
     */
    private static String getProperty(String key, String defaultValue) {
        return getProperty(new String[]{key}, defaultValue);
    }

    /**
     * Gets a property value from Spring Environment or falls back to default.
     * @param key1 first property key to try (e.g., kebab-case)
     * @param key2 second property key to try (e.g., camelCase)
     * @param defaultValue the default value if not found
     * @return the property value or default
     */
    private static String getProperty(String key1, String key2, String defaultValue) {
        return getProperty(new String[]{key1, key2}, defaultValue);
    }

    /**
     * Record for artifact information
     */
    private record ArtifactInfo(String groupId, String artifactId, String version) {}

    /**
     * Record for JAR location
     */
    private record JarLocation(Path jarPath, boolean isSources) {}
}
