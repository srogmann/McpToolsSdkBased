package org.rogmann.mcp2sdk.rag;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client for managing text embeddings with support for F32 and Q8_0 quantization formats.
 *
 * <p>This client provides methods to compute embeddings for text files via an OpenAI-compatible
 * embedding API, store them in a CSV-based database, and perform similarity searches against
 * the stored embeddings. Embeddings can be stored either as full-precision 32-bit floats (F32)
 * or in the Q8_0 quantized format (8-bit with block-wise float16 scale factors).
 *
 * <p>The CSV database format uses the following columns:
 * <ul>
 *   <li><b>Key</b> - unique identifier for the entry (e.g. file path)</li>
 *   <li><b>modTs</b> - last modification timestamp of the source file</li>
 *   <li><b>size</b> - file size in bytes</li>
 *   <li><b>promptTokens</b> - total prompt tokens used for all chunks</li>
 *   <li><b>totalTokens</b> - total tokens (prompt + completion) for all chunks</li>
 *   <li><b>Format</b> - storage format: {@code "F32"} or {@code "Q8_0"}</li>
 *   <li><b>numEmbeddings</b> - number of embedding vectors</li>
 *   <li><b>Intervals and Embeddings</b> - for each embedding: start offset, end offset, and Base64-encoded vector data</li>
 * </ul>
 *
 * <p>For F32 format, the vector data is stored as Base64-encoded raw 32-bit floats (4 bytes per element).
 * For Q8_0 format, the vector data is stored as Base64-encoded Q8_0 quantized blocks using
 * {@link Q8_0Vector}.
 *
 * <p>Text files larger than the configured chunk size are automatically split into overlapping
 * chunks using a sliding window approach. Each chunk is embedded separately, and all resulting
 * vectors are stored along with their character offset intervals.
 */
public class EmbeddingClient {

    /** Logger instance. */
    private static final Logger LOG = LoggerFactory.getLogger(EmbeddingClient.class);

    /** Map of keys to embedding entries, preserving insertion order. */
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    /** URL of the OpenAI-compatible embedding API endpoint. */
    private final String url;

    /** Model name to use for embedding computation. */
    private final String model;

    /** Shared HTTP client for API calls. */
    private final HttpClient httpClient;

    /** Maximum chunk size in characters for splitting large files. */
    private int chunkSize = 6000;

    /** Overlap size in characters between consecutive chunks. */
    private int overlapSize = 480;

    /** Lookback window in characters for finding clean split points. */
    private int lookback = 240;

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
     * Constructs a new EmbeddingClient with the specified API endpoint and model.
     *
     * @param url   the URL of the OpenAI-compatible embedding API (e.g. {@code http://localhost:8080/v1/embeddings})
     * @param model the model name to use for embedding computation (e.g. {@code "nomic-embed-text-v1.5"})
     */
    public EmbeddingClient(String url, String model) {
        this.url = url;
        this.model = model;
        this.httpClient = HttpClient.newHttpClient();
    }

    // ---- Configuration of chunking parameters ----

    /**
     * Sets the maximum chunk size in characters for splitting large files.
     *
     * @param chunkSize maximum chunk size in characters (must be positive)
     */
    public void setChunkSize(int chunkSize) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive, got: " + chunkSize);
        }
        this.chunkSize = chunkSize;
    }

    /**
     * Sets the overlap size in characters between consecutive chunks.
     *
     * @param overlapSize overlap size in characters (must be non-negative)
     */
    public void setOverlapSize(int overlapSize) {
        if (overlapSize < 0) {
            throw new IllegalArgumentException("overlapSize must be non-negative, got: " + overlapSize);
        }
        this.overlapSize = overlapSize;
    }

    /**
     * Sets the lookback window in characters for finding clean split points (sentence boundaries).
     *
     * @param lookback lookback window in characters (must be non-negative)
     */
    public void setLookback(int lookback) {
        if (lookback < 0) {
            throw new IllegalArgumentException("lookback must be non-negative, got: " + lookback);
        }
        this.lookback = lookback;
    }

    // ---- Public API ----

    /**
     * Loads embedding entries from a CSV file.
     *
     * <p>Existing entries in this client are replaced with the data from the file.
     * If the file does not exist, this method returns without loading anything.
     *
     * @param embedCsv the CSV file containing embedding data
     * @throws IOException if an I/O error occurs while reading the file
     */
    public void load(File embedCsv) throws IOException {
        if (!embedCsv.exists()) {
            LOG.info("Embedding CSV file does not exist, skipping load: {}", embedCsv.getAbsolutePath());
            return;
        }

        entries.clear();
        int lineCount = 0;

        try (BufferedReader br = new BufferedReader(new FileReader(embedCsv, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                Entry entry = parseCsvLine(line);
                if (entry != null) {
                    entries.put(entry.key(), entry);
                    lineCount++;
                } else {
                    LOG.warn("Skipping malformed CSV line: {}", line);
                }
            }
        }

        LOG.info("Loaded {} embedding entries from CSV file: {}", lineCount, embedCsv.getAbsolutePath());
    }

    /**
     * Saves all embedding entries to a CSV file.
     *
     * <p>If {@code useQ8} is {@code true}, embeddings are stored in the Q8_0 quantized format;
     * otherwise, they are stored as full-precision 32-bit floats (F32).
     *
     * @param embedCsv the CSV file to write to
     * @param useQ8    {@code true} to use Q8_0 quantization, {@code false} to use F32
     * @throws IOException if an I/O error occurs while writing the file
     */
    public void save(File embedCsv, boolean useQ8) throws IOException {
        final String format = useQ8 ? "Q8_0" : "F32";
        int entryCount = 0;

        try (PrintWriter pw = new PrintWriter(new FileWriter(embedCsv, StandardCharsets.UTF_8))) {
            for (Entry entry : entries.values()) {
                StringBuilder sb = new StringBuilder(512);
                sb.append(escapeCsvField(entry.key())).append(';');
                sb.append(entry.modTs()).append(';');
                sb.append(entry.size()).append(';');
                sb.append(entry.promptTokens()).append(';');
                sb.append(entry.totalTokens()).append(';');
                sb.append(format).append(';');
                sb.append(entry.embeddings().size()).append(';');

                for (int i = 0; i < entry.embeddings().size(); i++) {
                    if (i > 0) {
                        sb.append(';');
                    }
                    int[] interval = i < entry.intervals().size()
                            ? entry.intervals().get(i)
                            : new int[]{0, 0};
                    sb.append(interval[0]).append(';');
                    sb.append(interval[1]).append(';');

                    float[] vector = entry.embeddings().get(i);
                    if (useQ8) {
                        sb.append(floatArrayToBase64Q8(vector));
                    } else {
                        sb.append(floatArrayToBase64(vector));
                    }
                }
                pw.println(sb.toString());
                entryCount++;
            }
        }

        LOG.info("Saved {} embedding entries to CSV file (format={}): {}",
                entryCount, format, embedCsv.getAbsolutePath());
    }

    /**
     * Computes embedding vectors for the content of the given file and adds them to the database.
     *
     * <p>The file content is read as UTF-8 text. If the file is larger than the configured chunk size,
     * it is split into overlapping chunks using a sliding window approach. Each chunk is sent to the
     * embedding API separately.
     *
     * <p>If an entry with the same key already exists and its modification timestamp and file size
     * match, the existing entry is kept unchanged.
     *
     * @param key  the index key to use for this entry in the embedding database
     * @param data the file whose content should be embedded
     * @throws IOException if an I/O error occurs while reading the file or calling the API
     */
    public void addFile(String key, File data) throws IOException {
        if (!data.exists()) {
            LOG.warn("File does not exist, skipping: {}", data.getAbsolutePath());
            return;
        }

        long modTs = data.lastModified();
        long size = data.length();

        // Check if an up-to-date entry already exists.
        Entry existing = entries.get(key);
        if (existing != null && existing.modTs() == modTs && existing.size() == size) {
            LOG.info("Entry '{}' is up to date (modTs={}, size={}), skipping.", key, modTs, size);
            return;
        }

        LOG.info("Computing embeddings for file '{}' (key={})...", data.getAbsolutePath(), key);
        String content = Files.readString(data.toPath(), StandardCharsets.UTF_8);

        List<float[]> embeddings = new ArrayList<>();
        List<int[]> intervals = new ArrayList<>();
        int promptTokensSum = 0;
        int totalTokensSum = 0;

        int pos = 0;
        while (pos < content.length()) {
            int idealEnd = pos + chunkSize;
            int endPos = Math.min(idealEnd, content.length());

            // Find a clean split point near the end of the chunk.
            if (endPos < content.length()) {
                endPos = findGoodSplitPoint(content, endPos, lookback);
            }

            String chunk = content.substring(pos, endPos);
            EmbeddingResult emb = computeEmbedding(chunk);
            embeddings.add(emb.embedding());
            intervals.add(new int[]{pos, endPos});
            promptTokensSum += emb.promptTokens();
            totalTokensSum += emb.totalTokens();

            // If we have reached the end of the file, stop.
            if (endPos >= content.length()) {
                break;
            }

            // Find the start position for the next chunk with overlap.
            int idealStart = endPos - overlapSize;
            if (idealStart <= pos) {
                pos = endPos;
            } else {
                int nextStart = findGoodSplitPoint(content, idealStart, lookback);
                pos = Math.max(pos + 1, nextStart);
            }
        }

        Entry newEntry = new Entry(key, modTs, size, promptTokensSum, totalTokensSum,
                List.copyOf(embeddings), List.copyOf(intervals));
        entries.put(key, newEntry);

        LOG.info("Added entry '{}': {} chunks, {} prompt tokens, {} total tokens, vector size={}",
                key, embeddings.size(), promptTokensSum, totalTokensSum,
                embeddings.isEmpty() ? 0 : embeddings.get(0).length);
    }

    /**
     * Searches the embedding database for entries most similar to the given query text.
     *
     * <p>The query text is first embedded using the configured API. Then, cosine similarity
     * (via dot product of normalized vectors) is computed against all stored embeddings.
     * For each database entry, the best-matching chunk is returned.
     *
     * @param content the query text to search for
     * @param maxHits the maximum number of search results to return
     * @return a list of {@link SearchResult} entries, sorted by descending score
     * @throws IOException if an I/O error occurs while calling the embedding API
     */
    public List<SearchResult> search(String content, int maxHits) throws IOException {
        // Truncate query to chunk size for embedding.
        String queryText = content.substring(0, Math.min(content.length(), chunkSize));
        EmbeddingResult queryEmb = computeEmbedding(queryText);
        float[] queryVector = queryEmb.embedding();

        List<SearchResult> results = new ArrayList<>();

        for (Entry entry : entries.values()) {
            float bestScore = Float.NEGATIVE_INFINITY;
            int bestStart = -1;
            int bestEnd = -1;
            int bestChunkIndex = -1;

            for (int i = 0; i < entry.embeddings().size(); i++) {
                float[] vector = entry.embeddings().get(i);
                float score = dotProduct(queryVector, vector);
                if (score > bestScore) {
                    bestScore = score;
                    bestChunkIndex = i;
                    int[] interval = i < entry.intervals().size()
                            ? entry.intervals().get(i)
                            : new int[]{0, 0};
                    bestStart = interval[0];
                    bestEnd = interval[1];
                }
            }

            results.add(new SearchResult(entry.key(), bestScore, bestStart, bestEnd, bestChunkIndex));
        }

        results.sort(Comparator.comparingDouble(SearchResult::score).reversed());

        // Limit to maxHits.
        if (results.size() > maxHits) {
            results = results.subList(0, maxHits);
        }

        LOG.debug("Search returned {} results for query ({} chars).", results.size(), content.length());
        return results;
    }

    // ---- Internal helpers ----

    /**
     * Finds a good split point in the text by searching backwards from {@code maxPos}
     * within the {@code lookback} window. Prioritizes sentence endings (., !, ?) followed
     * by whitespace, then line breaks, and finally falls back to {@code maxPos}.
     *
     * @param text     the full text
     * @param maxPos   the maximum position (exclusive) for the split
     * @param lookback the number of characters to look back from {@code maxPos}
     * @return a character index suitable for splitting the text
     */
    private static int findGoodSplitPoint(String text, int maxPos, int lookback) {
        int startPos = Math.max(0, maxPos - lookback);
        String searchRegion = text.substring(startPos, maxPos);

        // Search backwards for sentence endings.
        for (int i = searchRegion.length() - 1; i >= 0; i--) {
            char c = searchRegion.charAt(i);
            if (c == '.' || c == '!' || c == '?') {
                if (i + 1 >= searchRegion.length() || Character.isWhitespace(searchRegion.charAt(i + 1))) {
                    return startPos + i + 1;
                }
            }
        }

        // Search backwards for line breaks.
        for (int i = searchRegion.length() - 1; i >= 0; i--) {
            if (searchRegion.charAt(i) == '\n' || searchRegion.charAt(i) == '\r') {
                return startPos + i + 1;
            }
        }

        return maxPos;
    }

    /**
     * Computes an embedding vector for the given text by calling the OpenAI-compatible API.
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

    /**
     * Escapes a CSV field value. If the value contains a semicolon, it is wrapped in quotes.
     *
     * @param value the field value
     * @return the escaped field value
     */
    private static String escapeCsvField(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(";") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /**
     * Computes the dot product of two float vectors.
     *
     * @param v1 the first vector
     * @param v2 the second vector
     * @return the dot product
     */
    private static float dotProduct(float[] v1, float[] v2) {
        float sum = 0f;
        for (int i = 0; i < v1.length; i++) {
            sum += v1[i] * v2[i];
        }
        return sum;
    }

    // ---- Base64 encoding / decoding for F32 ----

    /**
     * Encodes a float array to a Base64 string (4 bytes per float, big-endian).
     *
     * @param floats the float array
     * @return the Base64-encoded string
     */
    private static String floatArrayToBase64(float[] floats) {
        ByteBuffer buffer = ByteBuffer.allocate(floats.length * 4);
        for (float f : floats) {
            buffer.putFloat(f);
        }
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    /**
     * Decodes a Base64 string to a float array (4 bytes per float, big-endian).
     *
     * @param base64 the Base64-encoded string
     * @return the decoded float array
     */
    private static float[] base64ToFloatArray(String base64) {
        byte[] bytes = Base64.getDecoder().decode(base64);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        float[] floats = new float[bytes.length / 4];
        for (int i = 0; i < floats.length; i++) {
            floats[i] = buffer.getFloat();
        }
        return floats;
    }

    // ---- Base64 encoding / decoding for Q8_0 ----

    /**
     * Encodes a float array to a Base64 string using Q8_0 quantization.
     *
     * @param floats the float array to quantize
     * @return the Base64-encoded Q8_0 quantized data
     */
    private static String floatArrayToBase64Q8(float[] floats) {
        byte[] q8bytes = Q8_0Vector.writeVectorToBytes(floats);
        return Base64.getEncoder().encodeToString(q8bytes);
    }

    // ---- CSV parsing ----

    /**
     * Parses a single CSV line into an {@link Entry}.
     *
     * <p>The expected format is:
     * <pre>
     * Key;modTs;size;promptTokens;totalTokens;Format;numEmbeddings;start1;end1;embedding1;start2;end2;embedding2;...
     * </pre>
     *
     * @param line the CSV line to parse
     * @return the parsed entry, or {@code null} if the line is malformed
     */
    private Entry parseCsvLine(String line) {
        // Use a simple split-based parser; quoted fields are handled by unescapeCsvField.
        // For simplicity, we split by ';' and handle quoted values.
        List<String> fields = splitCsvLine(line);
        if (fields.size() < 7) {
            LOG.warn("CSV line has fewer than 7 fields: {}", line);
            return null;
        }

        try {
            String key = fields.get(0);
            long modTs = Long.parseLong(fields.get(1));
            long size = Long.parseLong(fields.get(2));
            int promptTokens = Integer.parseInt(fields.get(3));
            int totalTokens = Integer.parseInt(fields.get(4));
            String format = fields.get(5);
            int numEmbeddings = Integer.parseInt(fields.get(6));

            // Check if we have enough fields for the embeddings.
            int expectedFields = 7 + numEmbeddings * 3; // start, end, data for each embedding
            if (fields.size() < expectedFields) {
                LOG.warn("CSV line has {} fields but expected {} for {} embeddings: {}",
                        fields.size(), expectedFields, numEmbeddings, line);
                return null;
            }

            List<float[]> embeddings = new ArrayList<>(numEmbeddings);
            List<int[]> intervals = new ArrayList<>(numEmbeddings);

            int idx = 7;
            for (int i = 0; i < numEmbeddings; i++) {
                int start = Integer.parseInt(fields.get(idx++));
                int end = Integer.parseInt(fields.get(idx++));
                String data = fields.get(idx++);

                float[] vector;
                if ("Q8_0".equals(format)) {
                    // Determine the vector size from the Q8_0 byte data.
                    // Each Q8_0 block encodes 32 floats in 34 bytes (2 scale + 32 values).
                    byte[] q8bytes = Base64.getDecoder().decode(data);
                    int vectorSize = (q8bytes.length / Q8_0Vector.BLOCK_BYTES) * Q8_0Vector.BLOCK_SIZE;
                    vector = Q8_0Vector.readVectorFromBytes(q8bytes, vectorSize);
                } else {
                    // F32 format
                    vector = base64ToFloatArray(data);
                }

                embeddings.add(vector);
                intervals.add(new int[]{start, end});
            }

            return new Entry(key, modTs, size, promptTokens, totalTokens,
                    List.copyOf(embeddings), List.copyOf(intervals));

        } catch (NumberFormatException e) {
            LOG.warn("Failed to parse numeric field in CSV line: {}", line, e);
            return null;
        }
    }

    /**
     * Splits a CSV line into fields, respecting quoted values.
     *
     * @param line the CSV line
     * @return a list of field values
     */
    private static List<String> splitCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    // Escaped quote.
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ';' && !inQuotes) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());

        return fields;
    }

    // ---- Inner types ----

    /**
     * Represents a single embedding entry in the database.
     *
     * @param key           the unique key (e.g. file path)
     * @param modTs         last modification timestamp of the source file
     * @param size          file size in bytes
     * @param promptTokens  total prompt tokens used for all chunks
     * @param totalTokens   total tokens (prompt + completion) for all chunks
     * @param embeddings    list of embedding vectors (one per chunk)
     * @param intervals     list of character offset intervals (one per chunk), each as {@code [start, end]}
     */
    public record Entry(String key, long modTs, long size, int promptTokens, int totalTokens,
                        List<float[]> embeddings, List<int[]> intervals) {

        /**
         * Compact canonical constructor that creates defensive copies of the lists.
         */
        public Entry {
            embeddings = List.copyOf(embeddings);
            intervals = List.copyOf(intervals);
        }
    }

    /**
     * Represents the result of a similarity search.
     *
     * @param key          the key of the matching entry
     * @param score        the similarity score (dot product)
     * @param startOffset  the start character offset of the best-matching chunk (0-based)
     * @param endOffset    the end character offset of the best-matching chunk (exclusive)
     * @param chunkIndex   the index of the best-matching chunk within the entry
     */
    public record SearchResult(String key, float score, int startOffset, int endOffset, int chunkIndex) {

        /**
         * Returns a string representation of this search result.
         *
         * @return a formatted string
         */
        @Override
        public String toString() {
            return String.format("SearchResult{key='%s', score=%.4f, offsets=[%d..%d], chunk=%d}",
                    key, score, startOffset, endOffset, chunkIndex);
        }
    }

    /**
     * Internal record holding the result of a single embedding API call.
     *
     * @param promptTokens  number of prompt tokens used
     * @param totalTokens   total number of tokens
     * @param embedding     the embedding vector as a float array
     */
    private record EmbeddingResult(int promptTokens, int totalTokens, float[] embedding) {
    }
}
