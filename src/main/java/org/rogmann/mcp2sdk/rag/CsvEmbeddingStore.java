package org.rogmann.mcp2sdk.rag;

import java.io.BufferedReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The semicolon-separated embedding database: one line per indexed file, each carrying
 * the chunk intervals and the Base64-encoded vectors.
 * <p>
 * The class owns <strong>the format</strong> - reading and writing go through the same
 * code and the same codec ({@link VectorCodec}), so a writer can never encode
 * differently from a reader. It is deliberately independent of the embedding endpoint:
 * a provider or a command-line tool reads a database without constructing an
 * {@link EmbeddingClient}.
 * </p>
 * <p>
 * The format is <strong>exemplary</strong>: one provider implementation next to the
 * JSONL one, not a contract for other tools.
 * </p>
 * <pre>
 * Key;modTs;size;promptTokens;totalTokens;Format;numEmbeddings;start1;end1;data1;...
 * </pre>
 * <p>
 * Reading is <strong>strict</strong>: a malformed line aborts with file and line
 * number, like the JSONL provider does. A silently skipped line would remove chunks
 * from a corpus without anybody noticing - every hit after that is computed on an
 * incomplete index.
 * </p>
 */
public final class CsvEmbeddingStore {

    /** Logger instance. */
    private static final Logger LOG = LoggerFactory.getLogger(CsvEmbeddingStore.class);

    /** Number of leading fields before the chunk triples. */
    private static final int HEADER_FIELDS = 7;

    /**
     * One line of the database: an indexed file with its chunks.
     *
     * @param key          the unique key (e.g. the file path)
     * @param modTs        last modification timestamp of the source file
     * @param size         file size in bytes
     * @param promptTokens prompt tokens of all chunks together
     * @param totalTokens  total tokens of all chunks together
     * @param embeddings   the vectors, one per chunk
     * @param intervals    the chunk intervals, one per vector, each as {@code [start, end]}
     */
    public record Entry(String key, long modTs, long size, int promptTokens, int totalTokens,
                        List<float[]> embeddings, List<int[]> intervals) {

        /**
         * Compact canonical constructor: defensive copies of both lists.
         */
        public Entry {
            embeddings = List.copyOf(embeddings);
            intervals = List.copyOf(intervals);
        }
    }

    private CsvEmbeddingStore() {
        // utility class
    }

    /**
     * Reads a database.
     *
     * @param file the CSV file
     * @return the entries in file order
     * @throws IOException if the file is missing, too large or a line is malformed
     */
    public static List<Entry> read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException(file + ": file does not exist");
        }
        long maxBytes = RagLimits.current().maxSourceBytes();
        long size = Files.size(file);
        if (size > maxBytes) {
            throw new IOException(file + ": " + size + " bytes exceed the limit of " + maxBytes
                    + " bytes (mcp.rag.maxSourceBytes)");
        }

        List<Entry> entries = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                Entry entry = parseLine(line, lineNumber, file);
                if (!keys.add(entry.key())) {
                    throw new IOException(file + ":" + lineNumber + ": duplicate key '"
                            + entry.key() + "'");
                }
                entries.add(entry);
            }
        }
        LOG.debug("Read {} embedding entries from {}", entries.size(), file);
        return List.copyOf(entries);
    }

    /**
     * Writes a database.
     *
     * @param file    the CSV file
     * @param entries the entries, in write order
     * @param format  the storage format of the vectors
     * @throws IOException if the file cannot be written
     */
    public static void write(Path file, Collection<Entry> entries, VectorCodec.Format format)
            throws IOException {
        try (PrintWriter writer = new PrintWriter(new FileWriter(file.toFile(),
                StandardCharsets.UTF_8))) {
            for (Entry entry : entries) {
                StringBuilder sb = new StringBuilder(512);
                sb.append(escapeField(entry.key())).append(';');
                sb.append(entry.modTs()).append(';');
                sb.append(entry.size()).append(';');
                sb.append(entry.promptTokens()).append(';');
                sb.append(entry.totalTokens()).append(';');
                sb.append(format.name()).append(';');
                sb.append(entry.embeddings().size()).append(';');

                for (int i = 0; i < entry.embeddings().size(); i++) {
                    if (i > 0) {
                        sb.append(';');
                    }
                    int[] interval = i < entry.intervals().size()
                            ? entry.intervals().get(i)
                            : new int[]{0, 0};
                    sb.append(interval[0]).append(';').append(interval[1]).append(';');
                    sb.append(VectorCodec.encode(entry.embeddings().get(i), format));
                }
                writer.println(sb.toString());
            }
        }
        LOG.debug("Wrote {} embedding entries (format={}) to {}", entries.size(), format, file);
    }

    /**
     * Parses one line.
     *
     * @param line       the line
     * @param lineNumber the 1-based line number
     * @param file       the file, for the error message
     * @return the entry
     * @throws IOException if the line is malformed
     */
    private static Entry parseLine(String line, int lineNumber, Path file) throws IOException {
        String where = file + ":" + lineNumber;
        List<String> fields = split(line);
        if (fields.size() < HEADER_FIELDS) {
            throw new IOException(where + ": expected at least " + HEADER_FIELDS
                    + " fields, got " + fields.size());
        }
        try {
            String key = fields.get(0);
            long modTs = Long.parseLong(fields.get(1));
            long size = Long.parseLong(fields.get(2));
            int promptTokens = Integer.parseInt(fields.get(3));
            int totalTokens = Integer.parseInt(fields.get(4));
            VectorCodec.Format format = VectorCodec.Format.parse(fields.get(5), where);
            int numEmbeddings = Integer.parseInt(fields.get(6));

            if (numEmbeddings <= 0) {
                throw new IOException(where + ": numEmbeddings must be positive, got: "
                        + numEmbeddings);
            }
            int expectedFields = HEADER_FIELDS + numEmbeddings * 3;
            if (fields.size() < expectedFields) {
                throw new IOException(where + ": " + fields.size() + " fields, but "
                        + numEmbeddings + " embeddings need " + expectedFields);
            }

            List<float[]> embeddings = new ArrayList<>(numEmbeddings);
            List<int[]> intervals = new ArrayList<>(numEmbeddings);
            int index = HEADER_FIELDS;
            for (int i = 0; i < numEmbeddings; i++) {
                int start = Integer.parseInt(fields.get(index++));
                int end = Integer.parseInt(fields.get(index++));
                embeddings.add(VectorCodec.decode(fields.get(index++), format));
                intervals.add(new int[]{start, end});
            }
            return new Entry(key, modTs, size, promptTokens, totalTokens, embeddings, intervals);

        } catch (IllegalArgumentException e) {
            // NumberFormatException is an IllegalArgumentException: one catch covers a bad
            // number, an unknown vector format and undecodable Base64.
            throw new IOException(where + ": " + e.getMessage(), e);
        }
    }

    /**
     * Splits a line into fields, respecting quoted values.
     *
     * @param line the line
     * @return the field values
     */
    private static List<String> split(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
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

    /**
     * Escapes a field value: a value containing a semicolon, a quote or a line break is
     * wrapped in quotes.
     *
     * @param value the value, may be {@code null}
     * @return the escaped value
     */
    private static String escapeField(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(";") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
