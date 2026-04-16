package org.rogmann.mcp2sdk.examples;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Logger;
import java.util.function.BiFunction;

/**
 * MCP tool implementation for explaining technical words or concepts from a glossary.
 * Refactored to use McpServerFeatures.SyncToolSpecification.
 * The tool reads a markdown-formatted glossary file and looks up terms provided by the user.
 * It supports term references (->) to redirect lookups to related entries.
 */
public class GlossaryTool {

    private static final Logger LOGGER = Logger.getLogger(GlossaryTool.class.getName());

    private static final String NAME = "glossary-tool";

    /** Property name of a path to a markdown file */
    private static final String PROP_PATH = "mcp.glossary.path";

    /** Property name of a description of the glossary */
    private static final String PROP_DESCRIPTION = "mcp.glossary.description";

    private GlossaryTool() {
        // Prevent instantiation, this class serves as a factory for the tool specification
    }

    /**
     * Entry of the glossary.
     * @param term name of the term
     * @param description description of the term
     */
    record GlossaryEntry(String term, String description) { }

    /**
     * Creates the synchronous tool specification for the glossary lookup tool.
     * Initializes the glossary from the configured markdown file.
     * @return a SyncToolSpecification ready to be registered with an MCP server
     * @throws RuntimeException if the glossary file is not configured or cannot be read
     */
    public static McpServerFeatures.SyncToolSpecification createSpecification() {
        // Load glossary data during specification creation
        Map<String, GlossaryEntry> mapGlossary = loadGlossary();
        Map<String, String> mapReferences = loadReferences(mapGlossary);

        String toolDescription = System.getProperty(PROP_DESCRIPTION, 
            "Tool to explain technical words or concepts from a glossary.");

        // Define Input Schema properties
        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> wordsProp = new HashMap<>();
        wordsProp.put("type", "array");
        wordsProp.put("description", "List of words or concepts to be explained");
        Map<String, Object> itemsProp = new HashMap<>();
        itemsProp.put("type", "string");
        wordsProp.put("items", itemsProp);
        properties.put("words", wordsProp);

        List<String> requiredFields = List.of("words");

        JsonSchema inputSchema = new JsonSchema("object", properties, requiredFields, null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(NAME)
            .title("Glossary Tool")
            .description(toolDescription)
            .inputSchema(inputSchema)
            .build();

        BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> handler = (exchange, request) -> {
            Map<String, Object> arguments = request.arguments();

            Object oWords = arguments.get("words");
            if (oWords == null) {
                return CallToolResult.builder()
                    .isError(true)
                    .addTextContent("Missing 'words' parameter in request")
                    .build();
            }

            List<String> listWords = new ArrayList<>();
            if (oWords instanceof List<?> wordsList) {
                wordsList.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .forEach(listWords::add);
            } else if (oWords instanceof String[] wordsArray) {
                listWords.addAll(Arrays.asList(wordsArray));
            } else {
                // Handle comma-separated string
                String[] wordsArray = oWords.toString().split(" *, *");
                listWords.addAll(Arrays.asList(wordsArray));
            }

            LOGGER.info("Glossary lookup for words: " + listWords);

            Set<String> setKeysProcessed = new HashSet<>();
            List<String> listTermsFound = new ArrayList<>();
            StringBuilder sb = new StringBuilder();

            for (String word : listWords) {
                String key = convertToKey(word);
                processKey(key, mapReferences, mapGlossary, setKeysProcessed, sb, listTermsFound);
            }

            LOGGER.info("Keys checked: " + setKeysProcessed);
            String textResponse;
            if (sb.isEmpty()) {
                textResponse = "Unfortunately none of the words is known to the glossary tool";
            } else {
                textResponse = sb.toString();
                LOGGER.info("Terms found: " + listTermsFound);
            }

            // Prepare structured content for programmatic access
            Map<String, Object> structuredContent = new HashMap<>();
            structuredContent.put("status", "success");
            structuredContent.put("termsFound", listTermsFound);
            structuredContent.put("wordsRequested", listWords);
            structuredContent.put("message", sb.isEmpty() ? "No terms found" : "Found " + listTermsFound.size() + " term(s)");

            return CallToolResult.builder()
                .isError(false)
                .addTextContent(textResponse)
                .structuredContent(structuredContent)
                .build();
        };

        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler(handler)
            .build();
    }

    /**
     * Loads the glossary entries from the configured markdown file.
     * @return a map from normalized key to GlossaryEntry
     * @throws RuntimeException if the glossary file is not configured or cannot be read
     */
    private static Map<String, GlossaryEntry> loadGlossary() {
        String path = System.getProperty(PROP_PATH);
        if (path == null || path.isBlank()) {
            throw new RuntimeException("Property " + PROP_PATH + " is not set");
        }

        Path filePath = Paths.get(path);
        if (!Files.exists(filePath)) {
            throw new RuntimeException("Glossary file not found: " + path);
        }

        Map<String, GlossaryEntry> mapGlossary = new LinkedHashMap<>();

        try (BufferedReader br = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            String currentTerm = null;
            StringBuilder sbDescription = new StringBuilder();

            while (true) {
                String line = br.readLine();
                if (line == null) {
                    break;
                }
                if (line.startsWith("# ")) {
                    // Save previous term and description if exists
                    String description = sbDescription.toString().trim();
                    addEntry(currentTerm, description, mapGlossary);
                    // Start new term
                    currentTerm = line.substring(2).trim();
                    sbDescription.setLength(0);
                } else if (currentTerm != null) {
                    // Append to current description
                    if (!sbDescription.isEmpty()) {
                        sbDescription.append("\n");
                    }
                    sbDescription.append(line);
                }
                // Ignore lines not part of any term description.
            }

            // Don't forget the last term.
            String description = sbDescription.toString().trim();
            addEntry(currentTerm, description, mapGlossary);
            LOGGER.info("Glossary loaded with " + mapGlossary.size() + " entries");
        } catch (IOException e) {
            throw new RuntimeException("Error reading glossary file: " + path, e);
        }

        return mapGlossary;
    }

    /**
     * Builds a map of references from terms that redirect to other terms.
     * @param mapGlossary the glossary entries map
     * @return a map from key to referenced key
     */
    private static Map<String, String> loadReferences(Map<String, GlossaryEntry> mapGlossary) {
        Map<String, String> mapReferences = new HashMap<>();
        for (Map.Entry<String, GlossaryEntry> entry : mapGlossary.entrySet()) {
            String description = entry.getValue().description();
            if (description.startsWith("-> ")) {
                String keyRef = convertToKey(description.substring(3));
                mapReferences.put(entry.getKey(), keyRef);
            }
        }
        return mapReferences;
    }

    /**
     * Adds an entry to the glossary map.
     * @param term the term name
     * @param description the term description
     * @param mapGlossary the glossary map to add to
     */
    private static void addEntry(String term, String description, Map<String, GlossaryEntry> mapGlossary) {
        if (term != null && !description.isEmpty()) {
            String key = convertToKey(term);
            
            if (description.startsWith("-> ")) {
                // Reference entries are handled separately, don't add to glossary
                return;
            }

            GlossaryEntry existingEntry = mapGlossary.get(key);
            if (existingEntry != null) {
                LOGGER.warning(String.format("Duplicate key (%s) of terms (%s) and (%s) in glossary-file.",
                        key, existingEntry.term(), term));
            } else {
                mapGlossary.put(key, new GlossaryEntry(term, description));
            }
        }
    }

    /**
     * Converts a term to a normalized lookup key.
     * @param term the term to convert
     * @return the normalized key (lowercase, no spaces/underscores/hyphens, quotes removed)
     */
    private static String convertToKey(String term) {
        if (term == null) {
            return "";
        }
        return term.toLowerCase()
            .replaceAll("[ _-]", "")
            .replaceAll("^\"(.*)\"$", "$1");
    }

    /**
     * Processes a lookup key and appends the term description to the result builder.
     * @param key the normalized lookup key
     * @param mapReferences the references map
     * @param mapGlossary the glossary entries map
     * @param setKeysProcessed set of already processed keys to avoid duplicates
     * @param sb the StringBuilder to append results to
     * @param listTermsFound list to track found terms
     */
    private static void processKey(String key, Map<String, String> mapReferences, 
            Map<String, GlossaryEntry> mapGlossary, Set<String> setKeysProcessed, 
            StringBuilder sb, List<String> listTermsFound) {
        String keyRef = mapReferences.get(key);
        String keyLookup = (keyRef == null) ? key : keyRef;

        if (setKeysProcessed.add(keyLookup)) {
            GlossaryEntry entry = mapGlossary.get(keyLookup);
            if (entry != null) {
                if (!sb.isEmpty()) {
                    sb.append("\n\n");
                }
                sb.append("# ").append(entry.term()).append('\n');
                sb.append(entry.description());
                listTermsFound.add(entry.term());
            }
        }
    }

    /**
     * Checks if a glossary file is configured and exists.
     * @return true if a glossary file is available, false otherwise
     */
    public static boolean hasGlossary() {
        String path = System.getProperty(PROP_PATH);
        if (path == null || path.isBlank()) {
            return false;
        }
        if (System.getProperty(PROP_DESCRIPTION) == null) {
            return false;
        }

        Path filePath = Paths.get(path);
        return Files.exists(filePath);
    }
}
