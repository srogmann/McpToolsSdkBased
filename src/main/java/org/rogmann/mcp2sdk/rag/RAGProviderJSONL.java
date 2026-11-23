package org.rogmann.mcp2sdk.rag;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A RAG source read from a JSONL file: one header line, then one line per chunk.
 * <p>
 * The header carries the facts that are needed without loading the vectors, so a
 * server start does not depend on the size of a corpus and a model/dimension mismatch
 * is detected when the source is registered, not when somebody searches:
 * </p>
 * <pre>
 * {"kind":"rag-jsonl","v":1,"model":"nomic-embed-text-v1.5","dimension":768,
 *  "format":"Q8_0","count":1043,"metadata":{"language":"de"}}
 * {"referenceId":"dlf-2024-05-12","offsetStart":0,"offsetEnd":5943,"numberTokens":312,
 *  "model":"nomic-embed-text-v1.5","text":"...",
 *  "metadata":{"title":"Koalition einigt sich","source":"https://..."},
 *  "vector":"&lt;base64&gt;"}
 * </pre>
 * <p>
 * The vector field is Base64-encoded in the format the header names ({@link VectorCodec});
 * {@code text} may be omitted (the chunk text is then resolved by the provider, which
 * for a JSONL source simply means: it is not available). Every line is validated
 * strictly - a malformed line is an error naming file and line, never a silently
 * skipped chunk.
 * </p>
 */
public class RAGProviderJSONL implements RAGProvider {

    /** Logger instance. */
    private static final Logger LOG = LoggerFactory.getLogger(RAGProviderJSONL.class);

    /** Value of the header field {@code kind}. */
    private static final String KIND = "rag-jsonl";

    /** Supported header version. */
    private static final int VERSION = 1;

    /** JSON mapper of the provider. */
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** The JSONL file. */
    private final Path file;

    /** Topic of the source. */
    private final String topic;

    /** Name of the source. */
    private final String name;

    /** One-line description of the source. */
    private final String shortDescription;

    /** Embedding model named in the header. */
    private final String model;

    /** Vector dimension named in the header. */
    private final int dimension;

    /** Storage format named in the header. */
    private final VectorCodec.Format format;

    /** Chunk count named in the header, or {@code -1}. */
    private final int declaredChunkCount;

    /** Free-form facts of the header. */
    private final Map<String, String> metadata;

    /** Chunks after the first {@link #embeddings()} call. */
    private final AtomicReference<List<EmbeddingChunk>> loaded = new AtomicReference<>();

    /** Guards the one-time load. */
    private final ReentrantLock loadLock = new ReentrantLock();

    /** Warnings collected while loading. */
    private final AtomicReference<List<String>> warningRef = new AtomicReference<>(List.of());

    /**
     * Opens a JSONL source and reads its header line.
     *
     * @param file             the JSONL file
     * @param topic            the topic the source belongs to
     * @param name             the name of the source
     * @param shortDescription a one-line description for {@code rag.help()}
     * @throws IOException if the file cannot be read or has no valid header
     */
    public RAGProviderJSONL(Path file, String topic, String name, String shortDescription)
            throws IOException {
        this.file = file;
        this.topic = requireText(topic, "topic");
        this.name = requireText(name, "name");
        this.shortDescription = shortDescription == null ? "" : shortDescription;

        String headerLine;
        int headerLineNumber;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line = reader.readLine();
            int number = 1;
            while (line != null && line.isBlank()) {
                line = reader.readLine();
                number++;
            }
            if (line == null) {
                throw new IOException(file + ": file is empty - expected a JSONL header line");
            }
            headerLine = line;
            headerLineNumber = number;
        }

        JsonNode header = parse(headerLine, headerLineNumber);
        String kind = text(header, "kind");
        if (kind != null && !KIND.equals(kind)) {
            throw new IOException(file + ":" + headerLineNumber + ": unknown kind '" + kind
                    + "' - expected '" + KIND + "'");
        }
        int version = header.path("v").asInt(VERSION);
        if (version != VERSION) {
            throw new IOException(file + ":" + headerLineNumber + ": unsupported version " + version
                    + " - expected " + VERSION);
        }
        this.model = requireHeader(header, "model", headerLineNumber);
        this.dimension = header.path("dimension").asInt(0);
        if (dimension <= 0) {
            throw new IOException(file + ":" + headerLineNumber
                    + ": header field 'dimension' is missing or not positive");
        }
        String formatName = requireHeader(header, "format", headerLineNumber);
        try {
            this.format = VectorCodec.Format.parse(formatName, file + ":" + headerLineNumber);
            VectorCodec.validateDimension(dimension, format, file + ":" + headerLineNumber);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
        this.declaredChunkCount = header.path("count").asInt(-1);
        this.metadata = stringMap(header.path("metadata"),
                file + ":" + headerLineNumber + ": header metadata");

        LOG.debug("RAG source '{}' ({}): file={}, model={}, dimension={}, format={}, chunks={}",
                name, topic, file, model, dimension, format, declaredChunkCount);
    }

    @Override
    public String topic() {
        return topic;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String shortDescription() {
        return shortDescription;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public int chunkCount() {
        return declaredChunkCount;
    }

    @Override
    public Map<String, String> metadata() {
        return metadata;
    }

    @Override
    public List<EmbeddingChunk> embeddings() throws IOException {
        List<EmbeddingChunk> chunks = loaded.get();
        if (chunks != null) {
            return chunks;
        }
        loadLock.lock();
        try {
            chunks = loaded.get();
            if (chunks == null) {
                chunks = load();
                loaded.set(chunks);
            }
            return chunks;
        } finally {
            loadLock.unlock();
        }
    }

    @Override
    public List<String> warnings() {
        return warningRef.get();
    }

    /**
     * Reads the chunk lines.
     *
     * @return the chunks
     * @throws IOException if a line cannot be read or is malformed
     */
    private List<EmbeddingChunk> load() throws IOException {
        long started = System.nanoTime();
        List<EmbeddingChunk> chunks = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Boolean> seenKeys = new LinkedHashMap<>();

        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            boolean headerSeen = false;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                if (!headerSeen) {
                    // The header line was already validated by the constructor.
                    headerSeen = true;
                    continue;
                }
                EmbeddingChunk chunk = parseChunk(line, lineNumber, warnings);
                String identity = chunk.referenceId() + '\u0000' + chunk.offsetStart();
                if (seenKeys.put(identity, Boolean.TRUE) != null) {
                    warnings.add("duplicate chunk key '" + chunk.referenceId() + "' at offset "
                            + chunk.offsetStart() + " (" + file.getFileName() + ":" + lineNumber + ")");
                }
                chunks.add(chunk);
            }
        }

        if (declaredChunkCount >= 0 && declaredChunkCount != chunks.size()) {
            warnings.add("header declares " + declaredChunkCount + " chunks, file has "
                    + chunks.size() + " (" + file.getFileName() + ")");
        }

        warningRef.set(capWarnings(warnings));
        LOG.info("Loaded RAG source '{}' ({}): {} chunks from {} in {} ms",
                name, topic, chunks.size(), file, (System.nanoTime() - started) / 1_000_000L);
        return Collections.unmodifiableList(chunks);
    }

    /**
     * Parses one chunk line and validates it against the header.
     *
     * @param line       the JSON line
     * @param lineNumber the 1-based line number, for error messages
     * @param warnings   collector for data-quality findings
     * @return the chunk
     * @throws IOException if the line is malformed or contradicts the header
     */
    private EmbeddingChunk parseChunk(String line, int lineNumber, List<String> warnings)
            throws IOException {
        String where = file + ":" + lineNumber;
        JsonNode node = parse(line, lineNumber);
        String referenceId = text(node, "referenceId");
        if (referenceId == null || referenceId.isBlank()) {
            throw new IOException(where + ": chunk has no 'referenceId'");
        }
        String chunkModel = text(node, "model");
        if (chunkModel == null) {
            chunkModel = model;
        } else if (!chunkModel.equals(model)) {
            throw new IOException(where + ": chunk model '" + chunkModel
                    + "' differs from the header model '" + model + "'");
        }
        String vectorBase64 = text(node, "vector");
        if (vectorBase64 == null || vectorBase64.isBlank()) {
            throw new IOException(where + ": chunk '" + referenceId + "' has no 'vector'");
        }
        float[] vector;
        try {
            // A chunk may spell its own format ("Q8:<base64>"); it has to agree with the
            // header, which stays authoritative.
            vector = VectorCodec.decodeAuto(vectorBase64, format);
        } catch (IllegalArgumentException e) {
            throw new IOException(where + ": chunk '" + referenceId + "': " + e.getMessage(), e);
        }
        if (vector.length != dimension) {
            throw new IOException(where + ": chunk '" + referenceId + "' has dimension "
                    + vector.length + ", header says " + dimension);
        }
        int offsetStart = node.path("offsetStart").asInt(-1);
        int offsetEnd = node.path("offsetEnd").asInt(-1);
        String text = text(node, "text");
        // The offsets are offsets in the REFERENCED document, the text is the chunk: only the
        // SPAN has to fit the text. A mismatch is a data-quality finding, not a reason to
        // reject the chunk - a producer may trim or normalize whitespace.
        if (text != null && offsetStart >= 0 && offsetEnd >= offsetStart
                && offsetEnd - offsetStart != text.length()) {
            warnings.add("chunk '" + referenceId + "' offsets span "
                    + (offsetEnd - offsetStart) + " characters but its text has "
                    + text.length() + " (" + file.getFileName() + ":" + lineNumber + ")");
        }
        try {
            return new EmbeddingChunk(referenceId, offsetStart, offsetEnd,
                    node.path("numberTokens").asInt(0), chunkModel, vector, text,
                    stringMap(node.path("metadata"), where + ": chunk metadata"));
        } catch (IllegalArgumentException e) {
            throw new IOException(where + ": " + e.getMessage(), e);
        }
    }

    /**
     * Parses one JSON line.
     *
     * @param line       the line
     * @param lineNumber the 1-based line number
     * @return the parsed node
     * @throws IOException if the line is not a JSON object
     */
    private JsonNode parse(String line, int lineNumber) throws IOException {
        JsonNode node;
        try {
            node = MAPPER.readTree(line);
        } catch (RuntimeException e) {
            throw new IOException(file + ":" + lineNumber + ": not valid JSON: " + e.getMessage(), e);
        }
        if (node == null || !node.isObject()) {
            throw new IOException(file + ":" + lineNumber + ": expected a JSON object");
        }
        return node;
    }

    /**
     * Reads a mandatory header field.
     *
     * @param header     the header node
     * @param field      the field name
     * @param lineNumber the header line number
     * @return the value
     * @throws IOException if the field is missing or blank
     */
    private String requireHeader(JsonNode header, String field, int lineNumber) throws IOException {
        String value = text(header, field);
        if (value == null || value.isBlank()) {
            throw new IOException(file + ":" + lineNumber + ": header field '" + field
                    + "' is missing");
        }
        return value;
    }

    /**
     * Reads a string field; numbers and booleans are rendered as text.
     *
     * @param node  the object node
     * @param field the field name
     * @return the value, or {@code null}
     */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asString();
    }

    /**
     * Reads a JSON object as a string map (numbers and booleans become text, nested
     * structures their JSON representation).
     *
     * @param node  the node, may be missing
     * @param where description of the place, for the error message
     * @return the map, never {@code null}
     * @throws IOException if the node is not an object or a value is null
     */
    private static Map<String, String> stringMap(JsonNode node, String where) throws IOException {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw new IOException(where + ": expected an object");
        }
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            JsonNode value = entry.getValue();
            if (value == null || value.isNull()) {
                throw new IOException(where + ": value of '" + entry.getKey() + "' is null");
            }
            map.put(entry.getKey(), value.isString() ? value.asString() : value.toString());
        }
        return Collections.unmodifiableMap(map);
    }

    /**
     * Caps a warning list at {@link RagLimits#maxWarnings()} entries, naming the
     * number of suppressed warnings.
     *
     * @param warnings the warnings
     * @return the capped, immutable list
     */
    private static List<String> capWarnings(List<String> warnings) {
        int max = RagLimits.current().maxWarnings();
        if (max <= 0) {
            return List.of();
        }
        if (warnings.size() <= max) {
            return List.copyOf(warnings);
        }
        List<String> capped = new ArrayList<>(warnings.subList(0, max));
        capped.add("... and " + (warnings.size() - max) + " more warnings");
        return List.copyOf(capped);
    }

    /**
     * Validates a required provider attribute.
     *
     * @param value the value
     * @param field the field name
     * @return the value
     */
    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("RAGProviderJSONL: " + field + " must not be null or blank");
        }
        return value.trim();
    }

    @Override
    public String toString() {
        return "RAGProviderJSONL{" + topic + "/" + name + ", file=" + file
                + ", model=" + model + ", dim=" + dimension + ", format=" + format
                + ", chunks=" + declaredChunkCount + "}";
    }
}
