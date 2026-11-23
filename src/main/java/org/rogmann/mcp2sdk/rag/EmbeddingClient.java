package org.rogmann.mcp2sdk.rag;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Client for managing text embeddings with support for F32 and Q8_0 quantization formats.
 *
 * <p>This client provides methods to compute embeddings for text files via an OpenAI-compatible
 * embedding API, store them in a CSV-based database, and perform similarity searches against
 * the stored embeddings. Embeddings can be stored either as full-precision 32-bit floats (F32)
 * or in the Q8_0 quantized format (8-bit with block-wise float16 scale factors).
 *
 * <p>The CSV format is owned by {@link CsvEmbeddingStore} (reading and writing go through
 * the same code); this class keeps the legacy CLI methods {@code load}/{@code save} on top of
 * it. The search of this project is {@link EmbeddingDb} - there is no second one here.</p>
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
public class EmbeddingClient implements EmbeddingModel {

    /** Logger instance. */
    private static final Logger LOG = LoggerFactory.getLogger(EmbeddingClient.class);

    /** Map of keys to embedding entries, preserving insertion order. */
    private final Map<String, CsvEmbeddingStore.Entry> entries = new LinkedHashMap<>();

    /** URL of the OpenAI-compatible embedding API endpoint. */
    private final String url;

    /** Model name to use for embedding computation. */
    private final String model;

    /** Optional bearer token sent to the endpoint ({@code null}: a placeholder is sent). */
    private final String apiKey;

    /** Connect and read timeout of the embedding calls. */
    private final Duration requestTimeout;

    /** Shared HTTP client for API calls. */
    private final HttpClient httpClient;

    /**
     * The chunking parameters - the only place they live (see {@link ChunkOptions}); the
     * chunker itself is {@link TextChunker}, so indexing has no second implementation.
     */
    private ChunkOptions chunkOptions = ChunkOptions.defaults();

    /**
     * Largest accepted input of one embedding call; {@code Integer.MAX_VALUE} means "no
     * guard of its own". The query path sets it to {@code mcp.rag.maxQueryChars}, the
     * indexing path to the chunk size - a too long input is refused, never truncated
     * silently.
     */
    private int maxInputChars = Integer.MAX_VALUE;

    /** JSON mapper for the request body and the response of the embedding endpoint. */
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /**
     * Constructs a new EmbeddingClient with the specified API endpoint and model.
     *
     * @param url   the URL of the OpenAI-compatible embedding API (e.g. {@code http://localhost:8080/v1/embeddings})
     * @param model the model name to use for embedding computation (e.g. {@code "nomic-embed-text-v1.5"})
     */
    public EmbeddingClient(String url, String model) {
        this(url, model, null, Duration.ofSeconds(20));
    }

    /**
     * Constructs a client with an explicit API key and timeout.
     *
     * @param url            the URL of the OpenAI-compatible embedding API
     * @param model          the model name
     * @param apiKey         the bearer token, or {@code null}
     * @param requestTimeout connect and read timeout of the calls
     */
    public EmbeddingClient(String url, String model, String apiKey, Duration requestTimeout) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("EmbeddingClient: url must not be null or blank");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("EmbeddingClient: model must not be null or blank");
        }
        this.url = url.trim();
        this.model = model.trim();
        this.apiKey = (apiKey == null || apiKey.isBlank()) ? null : apiKey.trim();
        this.requestTimeout = requestTimeout != null ? requestTimeout : Duration.ofSeconds(20);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.requestTimeout)
                .build();
    }

    // ---- Configuration ----

    /**
     * Sets the chunking parameters (the one place they live, see {@link ChunkOptions}).
     *
     * @param options the validated chunk options
     */
    public void setChunkOptions(ChunkOptions options) {
        if (options == null) {
            throw new IllegalArgumentException("setChunkOptions: options must not be null");
        }
        this.chunkOptions = options;
    }

    /**
     * Returns the chunking parameters of this client.
     *
     * @return the options
     */
    public ChunkOptions chunkOptions() {
        return chunkOptions;
    }

    /**
     * Sets the largest accepted input of one embedding call.
     *
     * @param maxInputChars the limit in characters, {@code Integer.MAX_VALUE} for none
     */
    public void setMaxInputChars(int maxInputChars) {
        if (maxInputChars <= 0) {
            throw new IllegalArgumentException(
                    "maxInputChars must be positive, got: " + maxInputChars);
        }
        this.maxInputChars = maxInputChars;
    }

    // ---- Public API ----

    /**
     * Loads embedding entries from a CSV file.
     *
     * <p>Existing entries in this client are replaced with the data from the file.
     * If the file does not exist, this method returns without loading anything.
     * The format itself lives in {@link CsvEmbeddingStore}, which is strict: a malformed
     * line aborts with file and line number instead of being skipped.
     *
     * @param embedCsv the CSV file containing embedding data
     * @throws IOException if an I/O error occurs while reading the file, or a line is malformed
     */
    public void load(File embedCsv) throws IOException {
        if (!embedCsv.exists()) {
            LOG.info("Embedding CSV file does not exist, skipping load: {}", embedCsv.getAbsolutePath());
            return;
        }

        entries.clear();
        // The format lives in CsvEmbeddingStore - one code path for reading and writing,
        // strict on a malformed line (file and line number) instead of skipping it.
        for (CsvEmbeddingStore.Entry entry : CsvEmbeddingStore.read(embedCsv.toPath())) {
            entries.put(entry.key(), entry);
        }
        LOG.info("Loaded {} embedding entries from CSV file: {}", entries.size(),
                embedCsv.getAbsolutePath());
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
        VectorCodec.Format format = useQ8 ? VectorCodec.Format.Q8_0 : VectorCodec.Format.F32;
        CsvEmbeddingStore.write(embedCsv.toPath(), entries.values(), format);
        LOG.info("Saved {} embedding entries to CSV file (format={}): {}",
                entries.size(), format, embedCsv.getAbsolutePath());
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
        CsvEmbeddingStore.Entry existing = entries.get(key);
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

        for (TextChunker.Span span : chunker().split(content)) {
            EmbeddingResult emb = computeEmbedding(span.text(content));
            embeddings.add(emb.embedding());
            intervals.add(new int[]{span.start(), span.end()});
            promptTokensSum += emb.promptTokens();
            totalTokensSum += emb.totalTokens();
        }

        CsvEmbeddingStore.Entry newEntry = new CsvEmbeddingStore.Entry(key, modTs, size,
                promptTokensSum, totalTokensSum, List.copyOf(embeddings), List.copyOf(intervals));
        entries.put(key, newEntry);

        LOG.info("Added entry '{}': {} chunks, {} prompt tokens, {} total tokens, vector size={}",
                key, embeddings.size(), promptTokensSum, totalTokensSum,
                embeddings.isEmpty() ? 0 : embeddings.get(0).length);
    }

    /**
     * Splits a text with this client's {@link ChunkOptions} and embeds every chunk - the
     * indexing path of a RAG source.
     * <p>
     * Each chunk gets the real character offsets of its span (UTF-16 code units of the
     * decoded text) and the token count of its own embedding call, so that a hit can be
     * resolved and budgeted later. The chunking itself is {@link TextChunker}: a caller must
     * not split on its own, or two indexes of the same text would disagree.
     * </p>
     *
     * @param id   the reference id of the text (e.g. the file path); carried by all chunks
     * @param text the text to split and embed
     * @return the chunks in reading order
     * @throws IOException if an embedding call fails
     */
    public List<EmbeddingChunk> computeChunks(String id, String text) throws IOException {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("computeChunks: id must not be null or blank");
        }
        String content = text == null ? "" : text;
        List<EmbeddingChunk> chunks = new ArrayList<>();
        for (TextChunker.Span span : chunker().split(content)) {
            EmbeddingResult emb = computeEmbedding(span.text(content));
            chunks.add(new EmbeddingChunk(id, span.start(), span.end(), emb.promptTokens(), model,
                    emb.embedding(), null, Map.of()));
        }
        return List.copyOf(chunks);
    }

    /**
     * Returns a chunker for the current options.
     *
     * @return a chunker (cheap; it only holds the options)
     */
    private TextChunker chunker() {
        return new TextChunker(chunkOptions);
    }

    // ---- Internal helpers ----

    /**
     * Computes the embedding of a text and packages it as a chunk.
     * <p>
     * This is the {@link EmbeddingModel} seam used by a RAG search: the query is
     * embedded once per search, and a test can replace it with a fake.
     * </p>
     *
     * @param id   the caller-defined identifier of the text (carried into the chunk)
     * @param text the text to embed
     * @return the chunk with the vector, the model name and the prompt token count
     * @throws IOException if an I/O error occurs during the API call
     */
    /**
     * Returns the model this client embeds with.
     *
     * @return the model name
     */
    @Override
    public String model() {
        return model;
    }

    @Override
    public EmbeddingChunk computeEmbedding(String id, String text) throws IOException {
        String chunkId = (id == null || id.isBlank()) ? "embedding" : id;
        String content = text == null ? "" : text;
        EmbeddingResult result = computeEmbedding(content);
        return new EmbeddingChunk(chunkId, 0, content.length(), result.promptTokens(), model,
                result.embedding(), null, Map.of());
    }

    /**
     * Computes an embedding vector for the given text by calling the OpenAI-compatible API.
     * <p>
     * This is the method the indexing path ({@code addFile}, the CLI's stdin mode) uses when
     * it needs both token counts of the call. The request body and the response are handled
     * with Jackson: a text containing quotes, control characters or a newline is escaped by
     * the mapper, and a response with a nested structure ({@code data[0].embedding}) is
     * parsed instead of matched by a regular expression.
     * </p>
     *
     * @param text the input text to embed
     * @return the embedding result containing token counts and the vector
     * @throws IOException if an I/O error occurs during the API call
     */
    public EmbeddingResult computeEmbedding(String text) throws IOException {
        String content = text == null ? "" : text;
        if (content.length() > maxInputChars) {
            // No silent truncation: a cut text would be embedded as if it were complete.
            throw new IllegalArgumentException("embedding input has " + content.length()
                    + " characters, the limit is " + maxInputChars
                    + " (see setMaxInputChars, mcp.rag.maxQueryChars, mcp.rag.chunk.maxChars)");
        }
        ObjectNode requestBody = MAPPER.createObjectNode();
        requestBody.put("input", content);
        requestBody.put("model", model);
        requestBody.put("encoding_format", "float");
        String jsonBody = MAPPER.writeValueAsString(requestBody);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + (apiKey != null ? apiKey : "no-key"))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOG.error("Embedding API returned HTTP {}: {}", response.statusCode(), response.body());
                throw new IOException("Failed to compute embedding: HTTP " + response.statusCode());
            }

            EmbeddingResult result = parseResponse(response.body());

            LOG.debug("Computed embedding: promptTokens={}, totalTokens={}, vectorSize={}",
                    result.promptTokens(), result.totalTokens(), result.embedding().length);

            return result;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Embedding API call was interrupted", e);
        }
    }

    /**
     * Parses the response of an OpenAI-compatible embedding endpoint.
     *
     * @param body the JSON response
     * @return the embedding result
     * @throws IOException if the response carries no usable embedding
     */
    private static EmbeddingResult parseResponse(String body) throws IOException {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (RuntimeException e) {
            throw new IOException("Embedding API answered with invalid JSON: " + e.getMessage(), e);
        }
        JsonNode data = root.path("data");
        if (!data.isArray() || data.size() == 0) {
            throw new IOException("Embedding API response has no 'data' array");
        }
        JsonNode vectorNode = data.get(0).path("embedding");
        if (!vectorNode.isArray() || vectorNode.size() == 0) {
            throw new IOException("Embedding API response has no 'embedding' array");
        }
        float[] embedding = new float[vectorNode.size()];
        for (int i = 0; i < embedding.length; i++) {
            JsonNode value = vectorNode.get(i);
            if (!value.isNumber()) {
                throw new IOException("Embedding API response has a non-numeric value at index " + i);
            }
            embedding[i] = (float) value.asDouble();
        }
        JsonNode usage = root.path("usage");
        return new EmbeddingResult(usage.path("prompt_tokens").asInt(0),
                usage.path("total_tokens").asInt(0), embedding);
    }

    // ---- Inner types ----

    /**
     * Internal record holding the result of a single embedding API call.
     *
     * @param promptTokens  number of prompt tokens used
     * @param totalTokens   total number of tokens
     * @param embedding     the embedding vector as a float array
     */
    public record EmbeddingResult(int promptTokens, int totalTokens, float[] embedding) {
    }
}
