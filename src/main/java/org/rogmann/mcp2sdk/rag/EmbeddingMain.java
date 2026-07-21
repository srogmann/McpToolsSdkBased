package org.rogmann.mcp2sdk.rag;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Command-line application for managing text embeddings using {@link EmbeddingClient}.
 *
 * <p>This main class supports three modes of operation:
 * <ul>
 *   <li><b>Embed mode</b> ({@code --embed-text} or {@code --embed-files}): Computes embeddings for
 *       the specified text files and stores them in a CSV database.</li>
 *   <li><b>Search mode</b> ({@code --search} or {@code --read-stdin}): Searches the embedding database
 *       for entries similar to a query text.</li>
 *   <li><b>Stdin mode</b> (no mode flags): Computes an embedding for text read from standard input
 *       and prints the vector statistics.</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 * java org.rogmann.mcp2sdk.rag.EmbeddingMain --url &lt;URL&gt; --model &lt;MODEL&gt;
 *     [--db-file &lt;CSV&gt;] [--chunk-size &lt;SIZE&gt;] [--overlap-size &lt;SIZE&gt;]
 *     [--lookback &lt;SIZE&gt;] [--format &lt;Q8_0|F32&gt;]
 *     [--search &lt;FILE&gt; | --read-stdin | --embed-text &lt;FILES...&gt;]
 *     [--write-results &lt;FILE&gt;]
 * </pre>
 */
public class EmbeddingMain {

    /** Logger instance. */
    private static final Logger LOG = LoggerFactory.getLogger(EmbeddingMain.class);

    /** Default number of search results to display. */
    private static final int DEFAULT_MAX_HITS = 8;

    /** Default format if not specified. */
    private static final String DEFAULT_FORMAT = "F32";

    /** The underlying embedding client for database operations. */
    private final EmbeddingClient client;

    /** URL of the embedding API (needed for standalone stdin mode). */
    private final String url;

    /** Model name (needed for standalone stdin mode). */
    private final String model;

    /** Shared HTTP client for standalone API calls (stdin mode). */
    private final HttpClient httpClient;

    /** Pattern for extracting prompt_tokens from JSON responses. */
    private static final Pattern PATTERN_PROMPT_TOKENS =
            Pattern.compile("\"prompt_tokens\":\\s*(\\d+)");

    /** Pattern for extracting total_tokens from JSON responses. */
    private static final Pattern PATTERN_TOTAL_TOKENS =
            Pattern.compile("\"total_tokens\":\\s*(\\d+)");

    /** Pattern for extracting embedding arrays from JSON responses. */
    private static final Pattern PATTERN_EMBEDDING =
            Pattern.compile("\"embedding\":\\s*\\[(.*?)\\]");

    /**
     * Constructs an EmbeddingMain with the given client, URL, and model.
     *
     * @param client the embedding client to use for database operations
     * @param url    the URL of the embedding API
     * @param model  the model name
     */
    public EmbeddingMain(EmbeddingClient client, String url, String model) {
        this.client = client;
        this.url = url;
        this.model = model;
        this.httpClient = HttpClient.newHttpClient();
    }

    /**
     * Main entry point.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        // ---- Parse arguments ----
        String url = null;
        String model = null;
        String dbFile = null;
        String searchFile = null;
        String resultsFile = null;
        String formatStr = DEFAULT_FORMAT;
        Integer chunkSize = null;
        Integer overlapSize = null;
        Integer lookback = null;
        List<String> embedFiles = new ArrayList<>();
        boolean searchMode = false;
        boolean embedMode = false;
        boolean readStdin = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--url" -> {
                    if (i + 1 < args.length) {
                        url = args[++i];
                    }
                }
                case "--model" -> {
                    if (i + 1 < args.length) {
                        model = args[++i];
                    }
                }
                case "--db-file" -> {
                    if (i + 1 < args.length) {
                        dbFile = args[++i];
                    }
                }
                case "--chunk-size" -> {
                    if (i + 1 < args.length) {
                        chunkSize = Integer.parseInt(args[++i]);
                    }
                }
                case "--overlap-size" -> {
                    if (i + 1 < args.length) {
                        overlapSize = Integer.parseInt(args[++i]);
                    }
                }
                case "--lookback" -> {
                    if (i + 1 < args.length) {
                        lookback = Integer.parseInt(args[++i]);
                    }
                }
                case "--format" -> {
                    if (i + 1 < args.length) {
                        formatStr = args[++i];
                    }
                }
                case "--search" -> {
                    if (i + 1 < args.length) {
                        searchFile = args[++i];
                        searchMode = true;
                    }
                }
                case "--write-results" -> {
                    if (i + 1 < args.length) {
                        resultsFile = args[++i];
                    }
                }
                case "--read-stdin" -> {
                    readStdin = true;
                    searchMode = true;
                }
                case "--embed-text", "--embed-files" -> {
                    embedMode = true;
                    for (int j = i + 1; j < args.length; j++) {
                        embedFiles.add(args[j]);
                    }
                    i = args.length; // consume remaining args
                }
                default -> {
                    System.err.println("Unknown option: " + args[i]);
                    printUsage();
                    System.exit(1);
                }
            }
        }

        // Validate required arguments.
        if (url == null || model == null) {
            System.err.println("Error: --url and --model are required.");
            printUsage();
            System.exit(1);
        }

        // Validate format.
        boolean useQ8;
        if ("Q8_0".equalsIgnoreCase(formatStr)) {
            useQ8 = true;
        } else if ("F32".equalsIgnoreCase(formatStr)) {
            useQ8 = false;
        } else {
            System.err.println("Error: Invalid format '" + formatStr + "'. Use 'Q8_0' or 'F32'.");
            System.exit(1);
            return;
        }

        // Create and configure the client.
        EmbeddingClient client = new EmbeddingClient(url, model);
        if (chunkSize != null) {
            client.setChunkSize(chunkSize);
        }
        if (overlapSize != null) {
            client.setOverlapSize(overlapSize);
        }
        if (lookback != null) {
            client.setLookback(lookback);
        }

        EmbeddingMain app = new EmbeddingMain(client, url, model);

        try {
            if (searchMode) {
                if (dbFile == null) {
                    System.err.println("Error: --db-file is required for search mode.");
                    System.exit(1);
                }
                String content;
                String sourceName;
                if (readStdin) {
                    content = readStdinContent();
                    sourceName = "STDIN";
                } else {
                    content = Files.readString(Paths.get(searchFile), StandardCharsets.UTF_8);
                    sourceName = searchFile;
                }
                app.handleSearch(new File(dbFile), content, sourceName, resultsFile);
            } else if (embedMode) {
                if (dbFile == null) {
                    System.err.println("Error: --db-file is required for embed mode.");
                    System.exit(1);
                }
                app.handleEmbed(new File(dbFile), embedFiles, useQ8);
            } else {
                // Stdin mode: compute embedding and print statistics.
                app.handleStdin();
            }
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            LOG.error("Fatal error", e);
            System.exit(1);
        }
    }

    /**
     * Prints usage information to standard error.
     */
    private static void printUsage() {
        System.err.println("Usage: java " + EmbeddingMain.class.getName()
                + " --url <URL> --model <MODEL>"
                + " [--db-file <CSV>] [--chunk-size <SIZE>] [--overlap-size <SIZE>]"
                + " [--lookback <SIZE>] [--format <Q8_0|F32>]"
                + " [--search <FILE> | --read-stdin | --embed-text <FILES...>]"
                + " [--write-results <FILE>]");
    }

    /**
     * Reads all text from standard input (UTF-8).
     *
     * @return the content read from stdin
     * @throws IOException if an I/O error occurs
     */
    private static String readStdinContent() throws IOException {
        try (InputStreamReader isr = new InputStreamReader(System.in, StandardCharsets.UTF_8);
             BufferedReader br = new BufferedReader(isr)) {
            return br.lines().collect(Collectors.joining("\n"));
        }
    }

    // ---- Stdin mode ----

    /**
     * Handles stdin mode: reads text from standard input, computes an embedding
     * via the API, and prints vector statistics (token counts and first/last values).
     *
     * @throws IOException if an I/O error occurs
     */
    private void handleStdin() throws IOException {
        String input = readStdinContent();
        EmbeddingResult emb = computeEmbedding(input);

        float[] vector = emb.embedding();
        System.out.println("Prompt tokens: " + emb.promptTokens()
                + ", Total tokens: " + emb.totalTokens());
        System.out.println("Vector length: " + vector.length);
        System.out.print("First 3: ");
        for (int i = 0; i < Math.min(3, vector.length); i++) {
            System.out.print(vector[i] + (i < Math.min(3, vector.length) - 1 ? ", " : ""));
        }
        System.out.println();
        System.out.print("Last 3: ");
        for (int i = Math.max(0, vector.length - 3); i < vector.length; i++) {
            System.out.print(vector[i] + (i < vector.length - 1 ? ", " : ""));
        }
        System.out.println();
    }

    /**
     * Internal record holding the result of a single embedding API call.
     *
     * @param promptTokens number of prompt tokens used
     * @param totalTokens  total number of tokens
     * @param embedding    the embedding vector as a float array
     */
    private record EmbeddingResult(int promptTokens, int totalTokens, float[] embedding) {
    }

    /**
     * Computes an embedding vector for the given text by calling the OpenAI-compatible API.
     * This is used for the standalone stdin mode (without the database).
     *
     * @param text the input text to embed
     * @return the embedding result containing token counts and the vector
     * @throws IOException if an I/O error occurs during the API call
     */
    private EmbeddingResult computeEmbedding(String text) throws IOException {
        String jsonBody = String.format(
                "{\"input\": \"%s\", \"model\": \"%s\", \"encoding_format\": \"float\"}",
                escapeJson(text),
                escapeJson(model));

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer no-key")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOG.error("Embedding API returned HTTP {}: {}", response.statusCode(), response.body());
                throw new IOException("Failed to compute embedding: HTTP " + response.statusCode());
            }

            String body = response.body();

            int promptTokens = parseInteger(body, PATTERN_PROMPT_TOKENS);
            int totalTokens = parseInteger(body, PATTERN_TOTAL_TOKENS);
            float[] embedding = parseEmbedding(body);

            LOG.debug("Computed embedding: promptTokens={}, totalTokens={}, vectorSize={}",
                    promptTokens, totalTokens, embedding.length);

            return new EmbeddingResult(promptTokens, totalTokens, embedding);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Embedding API call was interrupted", e);
        }
    }

    /**
     * Parses an integer value from a JSON response using the given pattern.
     *
     * @param json    the JSON response string
     * @param pattern the compiled regex pattern with a single capturing group
     * @return the parsed integer, or 0 if not found
     */
    private static int parseInteger(String json, Pattern pattern) {
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return 0;
    }

    /**
     * Parses a float array from a JSON embedding response.
     *
     * @param json the JSON response string containing an {@code "embedding"} array
     * @return the parsed float array
     * @throws IOException if the embedding data cannot be found or parsed
     */
    private static float[] parseEmbedding(String json) throws IOException {
        Matcher matcher = PATTERN_EMBEDDING.matcher(json);
        if (matcher.find()) {
            String content = matcher.group(1);
            String[] parts = content.split(",");
            float[] embedding = new float[parts.length];
            for (int i = 0; i < parts.length; i++) {
                embedding[i] = Float.parseFloat(parts[i].trim());
            }
            return embedding;
        }
        throw new IOException("Embedding data not found in API response");
    }

    /**
     * Escapes a string for safe inclusion in JSON.
     *
     * @param input the input string
     * @return the escaped string
     */
    private static String escapeJson(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ---- Embed mode ----

    /**
     * Handles embed mode: loads the existing database, computes embeddings for the
     * specified files, and saves the updated database.
     *
     * @param dbFile     the CSV database file
     * @param files      the list of file paths to embed
     * @param useQ8      {@code true} to use Q8_0 quantization, {@code false} for F32
     * @throws IOException if an I/O error occurs
     */
    private void handleEmbed(File dbFile, List<String> files, boolean useQ8) throws IOException {
        client.load(dbFile);

        for (String filePath : files) {
            File file = new File(filePath);
            if (!file.exists()) {
                System.err.println("File not found: " + filePath);
                continue;
            }

            // Use the absolute path as the key.
            String key = file.getAbsolutePath();

            client.addFile(key, file);
        }

        client.save(dbFile, useQ8);
        System.out.println("Database saved to " + dbFile.getAbsolutePath()
                + " (format: " + (useQ8 ? "Q8_0" : "F32") + ").");
    }

    // ---- Search mode ----

    /**
     * Handles search mode: loads the database, searches for the query text,
     * and displays the top matches.
     *
     * @param dbFile      the CSV database file
     * @param content     the query text
     * @param sourceName  a name describing the query source (file path or "STDIN")
     * @param resultsFile optional path to write detailed results as Markdown
     * @throws IOException if an I/O error occurs
     */
    private void handleSearch(File dbFile, String content, String sourceName, String resultsFile)
            throws IOException {
        client.load(dbFile);

        List<EmbeddingClient.SearchResult> results = client.search(content, DEFAULT_MAX_HITS);

        System.out.println("Top " + DEFAULT_MAX_HITS + " matches for " + sourceName + ":");
        for (int i = 0; i < results.size(); i++) {
            EmbeddingClient.SearchResult res = results.get(i);
            String location;
            if (res.startOffset() >= 0 && res.endOffset() >= res.startOffset()) {
                location = ", Start-Offset (0-based): " + res.startOffset()
                        + ", End-Offset (exclusive): " + res.endOffset();
            } else {
                location = ", Start-Offset: -, End-Offset: -";
            }
            System.out.println((i + 1) + ". " + res.key() + " (Score: " + String.format("%.4f", res.score()) + location + ")");
        }

        if (resultsFile != null) {
            writeResultsToFile(resultsFile, results);
        }
    }

    /**
     * Writes search results to a Markdown file with detailed excerpts.
     *
     * @param resultsFile path to the output Markdown file
     * @param results     the search results to write
     * @throws IOException if an I/O error occurs
     */
    private void writeResultsToFile(String resultsFile, List<EmbeddingClient.SearchResult> results)
            throws IOException {
        try (PrintWriter pw = new PrintWriter(new FileWriter(resultsFile, StandardCharsets.UTF_8))) {
            pw.println("# Search Results");
            pw.println();
            pw.println("## Summary");
            pw.println("| Source | Score | Range (Lines) |");
            pw.println("|---|---|---|");

            List<ResultDetail> details = new ArrayList<>();

            for (int i = 0; i < Math.min(20, results.size()); i++) {
                EmbeddingClient.SearchResult res = results.get(i);
                File file = new File(res.key());
                if (!file.exists()) {
                    pw.println("| " + res.key() + " | " + String.format("%.4f", res.score()) + " | Not Found |");
                    continue;
                }
                String fileContent = Files.readString(file.toPath(), StandardCharsets.UTF_8);

                int start = res.startOffset();
                int end = res.endOffset();
                if (start <= 0 && end <= 0) {
                    start = 0;
                    end = fileContent.length();
                }

                int startLine = getLineNumber(fileContent, start);
                int endLine = getLineNumber(fileContent, end);

                pw.println("| " + res.key() + " | " + String.format("%.4f", res.score())
                        + " | " + startLine + ".." + endLine + " |");

                String chunk = fileContent.substring(start, Math.min(end, fileContent.length()));
                details.add(new ResultDetail(res.key(), startLine, endLine, chunk));
            }

            pw.println();
            pw.println("## Details");
            for (int i = 0; i < details.size(); i++) {
                ResultDetail d = details.get(i);
                pw.println("### " + (i + 1) + ". " + d.filename + " (Lines " + d.startLine + ".." + d.endLine + ")");
                pw.println("```text");
                pw.println(d.chunk);
                pw.println("```");
                pw.println();
            }
        }

        System.out.println("Detailed results written to: " + resultsFile);
    }

    /**
     * Computes the 1-based line number for a given character offset in text.
     *
     * @param text   the full text
     * @param offset the character offset (0-based)
     * @return the 1-based line number
     */
    private static int getLineNumber(String text, int offset) {
        if (offset <= 0) {
            return 1;
        }
        if (offset >= text.length()) {
            offset = text.length() - 1;
        }
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /**
     * Internal record for a detailed result with a text excerpt.
     *
     * @param filename  the key of the matching entry
     * @param startLine the 1-based start line number
     * @param endLine   the 1-based end line number
     * @param chunk     the text excerpt
     */
    private record ResultDetail(String filename, int startLine, int endLine, String chunk) {
    }
}
