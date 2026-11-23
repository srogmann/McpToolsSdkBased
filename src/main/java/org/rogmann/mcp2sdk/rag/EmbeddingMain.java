package org.rogmann.mcp2sdk.rag;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    /** The underlying embedding client: database operations and the one embedding call. */
    private final EmbeddingClient client;

    /**
     * Constructs an EmbeddingMain for the given client. The client carries URL and model,
     * so the CLI has no second copy of the embedding call to keep in sync.
     *
     * @param client the embedding client to use
     */
    public EmbeddingMain(EmbeddingClient client) {
        this.client = client;
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

        // Create and configure the client. The chunk parameters are one object
        // (ChunkOptions): defaults and -Dmcp.rag.chunk.* first, the CLI flags win.
        EmbeddingClient client = new EmbeddingClient(url, model);
        try {
            ChunkOptions fromProperties = ChunkOptions.from(System::getProperty);
            ChunkOptions options = new ChunkOptions(
                    chunkSize != null ? chunkSize : fromProperties.maxChars(),
                    overlapSize != null ? overlapSize : fromProperties.overlap(),
                    lookback != null ? lookback : fromProperties.lookback());
            client.setChunkOptions(options);
            client.setMaxInputChars(options.maxChars());
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
            return;
        }

        EmbeddingMain app = new EmbeddingMain(client);

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
        EmbeddingClient.EmbeddingResult emb = client.computeEmbedding(input);

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
        // The CLI is one source over a local CSV file: it uses the same provider and the same
        // search as the rag module, so a CLI result and a tool result cannot drift apart.
        RAGProviderCSV provider = new RAGProviderCSV(dbFile.toPath(), "cli", dbFile.getName(),
                "Local CSV embedding database", client.model());
        EmbeddingDb db = new EmbeddingDb(List.of(provider), client, RagLimits.defaults());
        // maxChars 0: the CLI prints the whole chunk (the tool's per-hit limit is a context
        // budget, not a file length).
        RAGResult result = db.search(content, RAGScope.ALL,
                RAGOptions.defaults(DEFAULT_MAX_HITS, 0));
        List<RAGHit> hits = result.hits();

        System.out.println("Top " + DEFAULT_MAX_HITS + " matches for " + sourceName + ":");
        for (int i = 0; i < hits.size(); i++) {
            RAGHit hit = hits.get(i);
            String location;
            if (hit.startOffset() >= 0 && hit.endOffset() >= hit.startOffset()) {
                location = ", Start-Offset (0-based): " + hit.startOffset()
                        + ", End-Offset (exclusive): " + hit.endOffset();
            } else {
                location = ", Start-Offset: -, End-Offset: -";
            }
            System.out.println((i + 1) + ". " + hit.referenceId()
                    + " (Score: " + String.format(Locale.ROOT, "%.4f", hit.score())
                    + location + ")");
        }
        for (String warning : result.warnings()) {
            System.out.println("Warning: " + warning);
        }

        if (resultsFile != null) {
            writeResultsToFile(resultsFile, hits);
        }
    }

    /**
     * Writes search results to a Markdown file with detailed excerpts.
     *
     * @param resultsFile path to the output Markdown file
     * @param hits        the search hits to write
     * @throws IOException if an I/O error occurs
     */
    private void writeResultsToFile(String resultsFile, List<RAGHit> hits) throws IOException {
        try (PrintWriter pw = new PrintWriter(new FileWriter(resultsFile, StandardCharsets.UTF_8))) {
            pw.println("# Search Results");
            pw.println();
            pw.println("## Summary");
            pw.println("| Source | Score | Range (Lines) |");
            pw.println("|---|---|---|");

            List<ResultDetail> details = new ArrayList<>();

            for (int i = 0; i < Math.min(20, hits.size()); i++) {
                RAGHit hit = hits.get(i);
                File file = new File(hit.referenceId());
                if (!file.exists()) {
                    pw.println("| " + hit.referenceId() + " | "
                            + String.format(Locale.ROOT, "%.4f", hit.score()) + " | Not Found |");
                    continue;
                }
                String fileContent = Files.readString(file.toPath(), StandardCharsets.UTF_8);

                int start = hit.startOffset();
                int end = hit.endOffset();
                if (start <= 0 && end <= 0) {
                    start = 0;
                    end = fileContent.length();
                }

                int startLine = getLineNumber(fileContent, start);
                int endLine = getLineNumber(fileContent, end);

                pw.println("| " + hit.referenceId() + " | "
                        + String.format(Locale.ROOT, "%.4f", hit.score())
                        + " | " + startLine + ".." + endLine + " |");

                // The provider resolved the text; the offsets are only the fallback when it
                // could not (e.g. the file changed since the index was built).
                String chunk = hit.text() != null
                        ? hit.text()
                        : fileContent.substring(start, Math.min(end, fileContent.length()));
                details.add(new ResultDetail(hit.referenceId(), startLine, endLine, chunk));
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
