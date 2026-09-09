package org.rogmann.mcp2sdk.js;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Controlled read-only access to SQLite database files for JavaScript.
 * <p>
 * A small self-contained parser for the SQLite database file format (as documented in
 * <em>https://sqlite.org/fileformat.html</em>) - deliberately without any external
 * dependency such as xerial/sqlite-jdbc. The supported scope is intentionally simple:
 * </p>
 * <ul>
 *   <li>Listing the schema objects (tables, indexes, views, triggers) as stored in the
 *       {@code sqlite_schema} table (page 1).</li>
 *   <li>Reading all rows of an ordinary (rowid) table: the table b-tree is walked from its
 *       root page (interior pages 0x05 / leaf pages 0x0d), records are decoded according to
 *       the record format (serial types 0-9, text and blob), including cell payloads that
 *       spill onto overflow pages (X/M/K computation per the file format spec).</li>
 * </ul>
 * <p>
 * Not supported (rejected with a clear error message): write access, SQL execution,
 * index/view/trigger traversal, WITHOUT ROWID tables (their root page is an index b-tree),
 * virtual tables and shadow data in WAL files (only the main database file is read; a
 * write-version of 2, i.e. WAL mode, is logged as a warning because recent changes may
 * still reside in the {@code -wal} file).
 * </p>
 * <p>
 * Value mapping: NULL &rarr; {@code null}, INTEGER &rarr; {@link Long}, REAL &rarr; {@link Double},
 * TEXT &rarr; {@link String} (decoded with the database text encoding), BLOB &rarr; {@code byte[]}
 * (exposed as a {@code Uint8Array} in JavaScript). The {@code rowid} of each row is returned
 * as an additional {@code "rowid"} entry; an {@code INTEGER PRIMARY KEY} column aliases the
 * rowid and is stored as NULL in the record, so it appears as {@code null} - use {@code rowid}.
 * </p>
 * <p>
 * The database file must live inside the project base directory and is resolved with the
 * same security checks as {@link JsFileSystem} (system property {@code IDE_PROJECT_DIR}).
 * Alternatively raw database bytes (e.g. from {@code fs.readBytes}) can be passed.
 * </p>
 *
 * <h3>Usage in JavaScript</h3>
 * <pre>{@code
 * var tables = sqlite.tables("data/app.db");               // [{name, type, tblName, rootpage, sql}]
 * var rows   = sqlite.rows("data/app.db", "animals");      // [{rowid, id, name, num_legs}, ...]
 * sqlite.forEachRow("data/app.db", "animals", row => {
 *   if (row.name == "bird") return false;                  // return false to stop early
 *   return true;
 * });
 * }</pre>
 */
public class JsSQLite {

    private static final Logger LOG = LoggerFactory.getLogger(JsSQLite.class);

    /** Upper bound for the size of one database file (bytes); protects the JS engine heap. */
    public static final long MAX_DB_BYTES = 256L * 1024 * 1024;

    /** Upper bound for the payload of a single row (bytes); protects the JS engine heap. */
    public static final long MAX_ROW_PAYLOAD_BYTES = 64L * 1024 * 1024;

    /** Maximum b-tree depth (guards against corrupt files with cyclic parent/child links). */
    private static final int MAX_BTREE_DEPTH = 64;

    private static final byte[] MAGIC = {
            0x53, 0x51, 0x4c, 0x69, 0x74, 0x65, 0x20, 0x66,
            0x6f, 0x72, 0x6d, 0x61, 0x74, 0x20, 0x33, 0x00};

    // Database header offsets (all big-endian).
    private static final int OFF_PAGE_SIZE = 16;
    private static final int OFF_WRITE_VERSION = 18;
    private static final int OFF_READ_VERSION = 19;
    private static final int OFF_RESERVED = 20;
    private static final int OFF_DB_SIZE_PAGES = 28;
    private static final int OFF_TEXT_ENCODING = 56;

    // B-tree page type flags.
    private static final int FLAG_INTERIOR_INDEX = 0x02;
    private static final int FLAG_INTERIOR_TABLE = 0x05;
    private static final int FLAG_LEAF_INDEX = 0x0a;
    private static final int FLAG_LEAF_TABLE = 0x0d;

    private JsSQLite() {
        // Utility class
    }

    // ========================================================================
    // Public API
    // ========================================================================

    /**
     * Lists all schema objects (tables, indexes, views, triggers) of the database.
     * @param filePath path of the SQLite database file, relative to the project base directory
     * @return list of {name, type, tblName, rootpage, sql} in sqlite_schema order
     * @throws JsUserRuntimeException if the file is missing, outside the base directory or
     *         not a valid SQLite database
     */
    public static List<Map<String, Object>> tables(String filePath) {
        return tables(readDbFile(filePath));
    }

    /**
     * Lists all schema objects (tables, indexes, views, triggers) of the database.
     * @param db raw database file bytes
     * @return list of {name, type, tblName, rootpage, sql} in sqlite_schema order
     * @throws JsUserRuntimeException if the data is not a valid SQLite database
     */
    public static List<Map<String, Object>> tables(byte[] db) {
        Db database = parseDb(db);
        List<Map<String, Object>> result = new ArrayList<>();
        walkTableBtree(database, 1, row -> result.add(schemaRow(row)));
        return result;
    }

    /**
     * Reads all rows of an ordinary (rowid) table.
     * @param filePath path of the SQLite database file, relative to the project base directory
     * @param tableName name of the table
     * @return list of rows ({@code rowid} plus one entry per column)
     * @throws JsUserRuntimeException if the file/table is missing, the table is not an
     *         ordinary rowid table, or the database is corrupt
     */
    public static List<Map<String, Object>> rows(String filePath, String tableName) {
        return rows(readDbFile(filePath), tableName);
    }

    /**
     * Reads all rows of an ordinary (rowid) table.
     * @param db raw database file bytes
     * @param tableName name of the table
     * @return list of rows ({@code rowid} plus one entry per column)
     * @throws JsUserRuntimeException if the table is missing, not an ordinary rowid table,
     *         or the database is corrupt
     */
    public static List<Map<String, Object>> rows(byte[] db, String tableName) {
        Db database = parseDb(db);
        List<Map<String, Object>> result = new ArrayList<>();
        readTable(database, tableName, result::add);
        return result;
    }

    /**
     * Streams all rows of an ordinary (rowid) table to a handler function.
     * <p>
     * The handler is called once per row with the row object. If the handler returns
     * {@code false}, the iteration stops early (a simple way to filter/search without
     * materializing all rows).
     * </p>
     * @param filePath path of the SQLite database file, relative to the project base directory
     * @param tableName name of the table
     * @param handler function called per row; return {@code false} to stop
     * @return the number of rows visited (including the row that stopped the iteration)
     * @throws JsUserRuntimeException if the file/table is missing, the table is not an
     *         ordinary rowid table, or the database is corrupt
     */
    public static long forEachRow(String filePath, String tableName,
                                  java.util.function.Function<Map<String, Object>, Boolean> handler) {
        return forEachRow(readDbFile(filePath), tableName, handler);
    }

    /**
     * Streams all rows of an ordinary (rowid) table to a handler function.
     * @param db raw database file bytes
     * @param tableName name of the table
     * @param handler function called per row; return {@code false} to stop
     * @return the number of rows visited (including the row that stopped the iteration)
     * @throws JsUserRuntimeException if the table is missing, not an ordinary rowid table,
     *         or the database is corrupt
     */
    public static long forEachRow(byte[] db, String tableName,
                                  java.util.function.Function<Map<String, Object>, Boolean> handler) {
        Db database = parseDb(db);
        if (handler == null) {
            throw new IllegalArgumentException("Usage: sqlite.forEachRow(pathOrBytes, tableName, function(row) {...})");
        }
        final long[] count = {0};
        readTable(database, tableName, row -> {
            count[0]++;
            Boolean proceed = handler.apply(row);
            return proceed == null || proceed;
        });
        return count[0];
    }

    // ========================================================================
    // File access
    // ========================================================================

    /**
     * Reads and validates a database file inside the project base directory.
     * @param filePath path relative to the base directory
     * @return raw database bytes
     */
    static byte[] readDbBytes(String filePath) {
        return readDbFile(filePath);
    }

    /**
     * Reads a database file inside the project base directory.
     * @param filePath path relative to the base directory
     * @return raw database bytes
     */
    private static byte[] readDbFile(String filePath) {
        Path path = JsFileSystem.resolveSafePath(filePath);
        if (!Files.isRegularFile(path)) {
            throw new JsUserRuntimeException("File not found: " + JsFileSystem.toRelative(path));
        }
        try {
            long size = Files.size(path);
            if (size > MAX_DB_BYTES) {
                throw new JsUserRuntimeException(String.format(
                        "SQLite database too large: %s has %d bytes (limit %d bytes)",
                        JsFileSystem.toRelative(path), size, MAX_DB_BYTES));
            }
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new JsUserRuntimeException("Failed to read SQLite database: "
                    + JsFileSystem.toRelative(path), e);
        }
    }

    // ========================================================================
    // Database header
    // ========================================================================

    /** Parsed database header plus the raw file bytes. */
    private record Db(byte[] data, int pageSize, int reserved, int usable,
                      int textEncoding, long pageCount) {
    }

    /**
     * Validates the database header and returns a parsed view of the database.
     * @param data raw database bytes
     * @return parsed database
     */
    private static Db parseDb(byte[] data) {
        if (data.length < 100) {
            throw new JsUserRuntimeException(
                    "Not a SQLite database: file is smaller than the 100-byte database header");
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (data[i] != MAGIC[i]) {
                throw new JsUserRuntimeException(
                        "Not a SQLite database: magic header string mismatch (expected \"SQLite format 3\\000\")");
            }
        }
        int pageSize = u16(data, OFF_PAGE_SIZE);
        if (pageSize == 1) {
            pageSize = 65536;
        }
        if (pageSize < 512 || pageSize > 65536 || Integer.bitCount(pageSize) != 1) {
            throw new JsUserRuntimeException("Corrupt SQLite database: invalid page size " + u16(data, OFF_PAGE_SIZE));
        }
        int reserved = data[OFF_RESERVED] & 0xFF;
        int usable = pageSize - reserved;
        if (usable < 480) {
            throw new JsUserRuntimeException("Corrupt SQLite database: usable page size below 480 bytes (page size "
                    + pageSize + ", reserved " + reserved + ")");
        }
        int textEncoding = u32(data, OFF_TEXT_ENCODING);
        if (textEncoding != 1 && textEncoding != 2 && textEncoding != 3) {
            throw new JsUserRuntimeException("Corrupt SQLite database: invalid text encoding " + textEncoding);
        }
        int writeVersion = data[OFF_WRITE_VERSION] & 0xFF;
        if (writeVersion == 2) {
            LOG.warn("SQLite database is in WAL mode (write version 2): only the main database file is read, "
                    + "recent transactions may still reside in the -wal file");
        }
        long pageCount = u32(data, OFF_DB_SIZE_PAGES) & 0xFFFFFFFFL;
        return new Db(data, pageSize, reserved, usable, textEncoding, pageCount);
    }

    // ========================================================================
    // Schema handling
    // ========================================================================

    /**
     * Finds a table entry in sqlite_schema and returns its root page.
     * @param db parsed database
     * @param tableName table name
     * @return the matching schema row
     */
    private static Map<String, Object> findTable(Db db, String tableName) {
        final Map<String, Object> found = new LinkedHashMap<>();
        final boolean[] foundFlag = {false};
        walkTableBtree(db, 1, record -> {
            if (foundFlag[0]) {
                return false;
            }
            Map<String, Object> row = schemaRow(record);
            Object type = row.get("type");
            Object name = row.get("name");
            if ("table".equals(type) && tableName.equals(name)) {
                found.putAll(row);
                foundFlag[0] = true;
                return false;
            }
            return true;
        });
        if (!foundFlag[0]) {
            List<String> tableNames = new ArrayList<>();
            for (Map<String, Object> t : tables(db.data())) {
                if ("table".equals(t.get("type"))) {
                    tableNames.add(String.valueOf(t.get("name")));
                }
            }
            throw new JsUserRuntimeException("Table not found: " + tableName
                    + " (tables: " + String.join(", ", tableNames) + ")");
        }
        return found;
    }

    /**
     * Reads all rows of a table b-tree in rowid order.
     * @param db parsed database
     * @param tableName table name
     * @param sink row consumer; returning {@code false} stops the traversal
     */
    private static void readTable(Db db, String tableName, RowSink sink) {
        Map<String, Object> schemaRow = findTable(db, tableName);
        Object rootObj = schemaRow.get("rootpage");
        if (!(rootObj instanceof Long rootPage) || rootPage <= 0) {
            throw new JsUserRuntimeException("Object '" + tableName
                    + "' has no b-tree root page (virtual table or view?)");
        }
        List<String> columns = extractColumnNames((String) schemaRow.get("sql"));
        Charset cs = textCharset(db);
        walkTableBtree(db, (int) (long) rootPage, record -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rowid", record.get("rowid"));
            List<Object> values = record.get("values") instanceof List<?> v ? (List<Object>) v : List.of();
            for (int i = 0; i < values.size(); i++) {
                row.put(i < columns.size() ? columns.get(i) : ("col" + i), values.get(i));
            }
            // Rows written before an ALTER TABLE ... ADD COLUMN have fewer values than columns.
            for (int i = values.size(); i < columns.size(); i++) {
                row.put(columns.get(i), null);
            }
            return sink.apply(row);
        });
    }

    /**
     * Determines the text charset from the database text encoding.
     * @param db parsed database
     * @return charset (UTF-8, UTF-16LE or UTF-16BE)
     */
    private static Charset textCharset(Db db) {
        return switch (db.textEncoding()) {
            case 2 -> StandardCharsets.UTF_16LE;
            case 3 -> StandardCharsets.UTF_16BE;
            default -> StandardCharsets.UTF_8;
        };
    }

    /**
     * Converts a raw sqlite_schema record into a {name, type, tblName, rootpage, sql} map.
     * @param record raw record (values in sqlite_schema column order: type, name, tbl_name,
     *        rootpage, sql)
     * @return schema entry map
     */
    private static Map<String, Object> schemaRow(Map<String, Object> record) {
        List<Object> values = record.get("values") instanceof List<?> v ? (List<Object>) v : List.of();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", values.size() > 0 ? values.get(0) : null);
        result.put("name", values.size() > 1 ? values.get(1) : null);
        result.put("tblName", values.size() > 2 ? values.get(2) : null);
        result.put("rootpage", values.size() > 3 ? values.get(3) : null);
        result.put("sql", values.size() > 4 ? values.get(4) : null);
        return result;
    }

    // ========================================================================
    // B-tree traversal
    // ========================================================================

    /** Consumer of decoded row maps; returning {@code false} stops the walk. */
    private interface RowSink extends java.util.function.Function<Map<String, Object>, Boolean> {
    }

    /**
     * Walks a table b-tree in key (rowid) order and decodes every leaf record.
     * @param db parsed database
     * @param rootPage root page of the b-tree (page 1 = sqlite_schema, header at offset 100)
     * @param sink record consumer; returning {@code false} stops the traversal
     */
    private static void walkTableBtree(Db db, int rootPage, RowSink sink) {
        if (rootPage < 1 || rootPage > db.pageCount()) {
            throw new JsUserRuntimeException("Corrupt SQLite database: root page " + rootPage
                    + " outside the database (" + db.pageCount() + " pages)");
        }
        Set<Integer> visited = new HashSet<>();
        walkPage(db, rootPage, sink, visited, 0, textCharset(db));
    }

    /**
     * Walks a single table b-tree page.
     * @param db parsed database
     * @param pageNo page number (1-based)
     * @param sink record consumer for leaf cells; returning {@code false} stops the traversal
     * @param visited set of already visited pages (cycle guard)
     * @param depth current b-tree depth
     * @param cs text charset (database text encoding)
     * @return {@code true} to continue, {@code false} if the sink stopped the traversal
     */
    private static boolean walkPage(Db db, int pageNo, RowSink sink, Set<Integer> visited, int depth, Charset cs) {
        if (depth > MAX_BTREE_DEPTH) {
            throw new JsUserRuntimeException("Corrupt SQLite database: b-tree deeper than " + MAX_BTREE_DEPTH + " levels");
        }
        if (!visited.add(pageNo)) {
            throw new JsUserRuntimeException("Corrupt SQLite database: cyclic b-tree reference to page " + pageNo);
        }
        byte[] data = db.data();
        int base = (pageNo - 1) * db.pageSize();
        int header = base + (pageNo == 1 ? 100 : 0);
        int flag = data[header] & 0xFF;
        if (flag == FLAG_INTERIOR_INDEX || flag == FLAG_LEAF_INDEX) {
            throw new JsUserRuntimeException("Unsupported table type: the root page of the table is an index b-tree "
                    + "(WITHOUT ROWID tables are not supported)");
        }
        if (flag != FLAG_INTERIOR_TABLE && flag != FLAG_LEAF_TABLE) {
            throw new JsUserRuntimeException("Corrupt SQLite database: page " + pageNo
                    + " is not a table b-tree page (type " + flag + ")");
        }
        int cellCount = u16(data, header + 3);
        int ptrArray = header + (flag == FLAG_INTERIOR_TABLE ? 12 : 8);
        for (int i = 0; i < cellCount; i++) {
            int cellOff = base + u16(data, ptrArray + 2 * i);
            if (flag == FLAG_INTERIOR_TABLE) {
                // Interior cell: 4-byte left child pointer + varint key.
                int child = u32(data, cellOff);
                if (!walkPage(db, child, sink, visited, depth + 1, cs)) {
                    return false;
                }
            } else {
                // Leaf cell: varint payload size, varint rowid, payload (possibly overflowing).
                long[] sizeLen = readVarint(data, cellOff);
                long payloadSize = sizeLen[0];
                long[] rowidLen = readVarint(data, cellOff + (int) sizeLen[1]);
                long rowid = rowidLen[0];
                int payloadStart = cellOff + (int) sizeLen[1] + (int) rowidLen[1];
                byte[] payload = readPayload(db, payloadStart, payloadSize, pageNo);
                if (!sink.apply(decodeRecord(rowid, payload, cs))) {
                    return false;
                }
            }
        }
        if (flag == FLAG_INTERIOR_TABLE) {
            int rightMost = u32(data, header + 8);
            return walkPage(db, rightMost, sink, visited, depth + 1, cs);
        }
        return true;
    }

    // ========================================================================
    // Cell payload incl. overflow pages
    // ========================================================================

    /**
     * Assembles a cell payload from its local (on-page) part plus the overflow page chain.
     * <p>
     * The local/overflow split follows the file format specification: with U the usable page
     * size and P the payload size, X = U-35 for table leaf cells, M = ((U-12)*32/255)-23 and
     * K = M+((P-M) mod (U-4)); the local part is P if P&le;X, else K if K&le;X, else M.
     * </p>
     * @param db parsed database
     * @param payloadStart offset of the local payload within the file
     * @param payloadSize total payload size in bytes
     * @param containingPage page holding the cell (for error messages)
     * @return the complete payload
     */
    private static byte[] readPayload(Db db, int payloadStart, long payloadSize, int containingPage) {
        if (payloadSize < 0 || payloadSize > MAX_ROW_PAYLOAD_BYTES) {
            throw new JsUserRuntimeException(String.format(
                    "Corrupt SQLite database: payload of a cell on page %d exceeds the limit of %d bytes",
                    containingPage, MAX_ROW_PAYLOAD_BYTES));
        }
        int usable = db.usable();
        int x = usable - 35; // table b-tree leaf cells
        int m = ((usable - 12) * 32 / 255) - 23;
        int k = (int) (m + ((payloadSize - m) % (usable - 4)));
        int local;
        if (payloadSize <= x) {
            local = (int) payloadSize;
        } else if (k <= x) {
            local = k;
        } else {
            local = m;
        }
        byte[] data = db.data();
        if (payloadSize <= local) {
            return extract(data, payloadStart, (int) payloadSize);
        }
        byte[] payload = new byte[(int) payloadSize];
        System.arraycopy(data, payloadStart, payload, 0, local);
        int overflowPage = u32(data, payloadStart + local);
        int filled = local;
        Set<Integer> visited = new HashSet<>();
        while (filled < payloadSize) {
            if (overflowPage < 1 || overflowPage > db.pageCount()) {
                throw new JsUserRuntimeException("Corrupt SQLite database: invalid overflow page number "
                        + overflowPage + " (page " + containingPage + ")");
            }
            if (!visited.add(overflowPage)) {
                throw new JsUserRuntimeException("Corrupt SQLite database: cyclic overflow chain at page "
                        + overflowPage);
            }
            int obase = (overflowPage - 1) * db.pageSize();
            int next = u32(data, obase);
            int chunk = (int) Math.min(usable - 4, payloadSize - filled);
            System.arraycopy(data, obase + 4, payload, filled, chunk);
            filled += chunk;
            overflowPage = next;
        }
        return payload;
    }

    // ========================================================================
    // Record format (serial types)
    // ========================================================================

    /**
     * Decodes a record (header of serial types + body of values).
     * @param rowid rowid of the containing table b-tree entry
     * @param payload record bytes
     * @param cs text charset (database text encoding)
     * @return map with {@code rowid} and a {@code values} list
     */
    private static Map<String, Object> decodeRecord(long rowid, byte[] payload, Charset cs) {
        long[] sizeLen = readVarint(payload, 0);
        int headerSize = (int) sizeLen[0];
        if (headerSize < sizeLen[1] || headerSize > payload.length) {
            throw new JsUserRuntimeException("Corrupt SQLite database: invalid record header size " + headerSize);
        }
        List<Long> serialTypes = new ArrayList<>();
        int pos = (int) sizeLen[1];
        while (pos < headerSize) {
            long[] typeLen = readVarint(payload, pos);
            serialTypes.add(typeLen[0]);
            pos += (int) typeLen[1];
        }
        List<Object> values = new ArrayList<>(serialTypes.size());
        int body = headerSize;
        for (long typeLong : serialTypes) {
            long type = typeLong;
            if (type == 0) {
                values.add(null);
            } else if (type >= 1 && type <= 6) {
                int len = (int) (type == 5 ? 6 : type); // 1,2,3,4,6,8
                values.add(signedBigEndian(payload, body, len));
                body += len;
            } else if (type == 7) {
                values.add(Double.longBitsToDouble(signedBigEndian(payload, body, 8)));
                body += 8;
            } else if (type == 8) {
                values.add(0L);
            } else if (type == 9) {
                values.add(1L);
            } else if (type == 10 || type == 11) {
                throw new JsUserRuntimeException("Corrupt SQLite database: reserved serial type " + type
                        + " in a persistent record");
            } else if (type % 2 == 0) {
                int len = (int) ((type - 12) / 2);
                values.add(extract(payload, body, len));
                body += len;
            } else {
                int len = (int) ((type - 13) / 2);
                values.add(decodeText(cs, payload, body, len));
                body += len;
            }
            if (body > payload.length) {
                throw new JsUserRuntimeException("Corrupt SQLite database: record body exceeds the payload size");
            }
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("rowid", rowid);
        record.put("values", values);
        return record;
    }

    /**
     * Decodes a text value with the database text encoding.
     * @param cs text charset
     * @param data record bytes
     * @param offset offset of the text
     * @param len length in bytes
     * @return decoded string
     */
    private static String decodeText(Charset cs, byte[] data, int offset, int len) {
        return new String(data, offset, len, cs);
    }

    // ========================================================================
    // Column names from CREATE TABLE
    // ========================================================================

    /**
     * Extracts the column names from a normalized CREATE TABLE statement.
     * <p>
     * A pragmatic tokenizer splits the column definition list at top-level commas and takes
     * the first identifier of each definition; table constraints (PRIMARY, UNIQUE, CHECK,
     * FOREIGN, CONSTRAINT) are skipped. Quoted identifiers ({"x"}, {[x]}, {`x`}, {'x'}) and
     * comments are handled. If parsing fails, the returned list is empty (rows fall back to
     * positional names {@code col0}, {@code col1}, ...).
     * </p>
     * @param sql CREATE TABLE statement text from sqlite_schema (may be null)
     * @return column names in declaration order (possibly empty)
     */
    static List<String> extractColumnNames(String sql) {
        List<String> columns = new ArrayList<>();
        if (sql == null) {
            return columns;
        }
        int open = indexOfTopLevelParen(sql);
        if (open < 0) {
            return columns;
        }
        int depth = 0;
        int start = open + 1;
        int i = open;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '"' || c == '\'' || c == '`') {
                i = skipQuoted(sql, i, c);
                continue;
            }
            if (c == '[') {
                i = skipQuoted(sql, i, ']');
                continue;
            }
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                i = sql.indexOf('\n', i);
                if (i < 0) {
                    break;
                }
                continue;
            }
            if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                i = sql.indexOf("*/", i + 2);
                if (i < 0) {
                    break;
                }
                i += 2;
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    // closing parenthesis of the column definition list: last column
                    addColumnName(sql.substring(start, i), columns);
                    break;
                }
            } else if (c == ',' && depth == 1) {
                addColumnName(sql.substring(start, i), columns);
                start = i + 1;
            }
            i++;
        }
        return columns;
    }

    /**
     * Finds the opening parenthesis of the column definition list (outside quotes/comments).
     * @param sql CREATE TABLE statement
     * @return index of the parenthesis or -1
     */
    private static int indexOfTopLevelParen(String sql) {
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '"' || c == '\'' || c == '`') {
                i = skipQuoted(sql, i, c);
                continue;
            }
            if (c == '[') {
                i = skipQuoted(sql, i, ']');
                continue;
            }
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                i = sql.indexOf('\n', i);
                if (i < 0) {
                    return -1;
                }
                continue;
            }
            if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                i = sql.indexOf("*/", i + 2);
                if (i < 0) {
                    return -1;
                }
                i += 2;
                continue;
            }
            if (c == '(') {
                return i;
            }
            i++;
        }
        return -1;
    }

    /**
     * Skips a quoted region starting at {@code start}.
     * @param sql statement text
     * @param start index of the opening quote/bracket
     * @param quote closing character (for {@code [} the closing is {@code ]})
     * @return index after the closing quote
     */
    private static int skipQuoted(String sql, int start, char quote) {
        char close = quote == '[' ? ']' : quote;
        for (int i = start + 1; i < sql.length(); i++) {
            if (sql.charAt(i) == close) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == close) {
                    i++; // escaped quote ("" -> ")
                    continue;
                }
                return i + 1;
            }
        }
        return sql.length();
    }

    /**
     * Extracts the column name of one column definition and adds it to the list,
     * unless the definition is a table constraint.
     * @param definition one column definition (or table constraint)
     * @param columns accumulator list
     */
    private static void addColumnName(String definition, List<String> columns) {
        String def = definition.trim();
        if (def.isEmpty()) {
            return;
        }
        char c = def.charAt(0);
        String name;
        if (c == '"' || c == '\'' || c == '`') {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < def.length(); i++) {
                if (def.charAt(i) == c) {
                    if (i + 1 < def.length() && def.charAt(i + 1) == c) {
                        sb.append(c);
                        i++;
                        continue;
                    }
                    break;
                }
                sb.append(def.charAt(i));
            }
            name = sb.toString();
        } else if (c == '[') {
            int end = def.indexOf(']', 1);
            name = end > 0 ? def.substring(1, end) : def.substring(1);
        } else {
            int end = 0;
            while (end < def.length() && !Character.isWhitespace(def.charAt(end))
                    && def.charAt(end) != '(' && def.charAt(end) != ',') {
                end++;
            }
            name = def.substring(0, end);
        }
        String upper = name.toUpperCase(Locale.ROOT);
        if (upper.equals("PRIMARY") || upper.equals("UNIQUE") || upper.equals("CHECK")
                || upper.equals("FOREIGN") || upper.equals("CONSTRAINT")) {
            return; // table-level constraint, not a column
        }
        if (!name.isEmpty()) {
            columns.add(name);
        }
    }

    // ========================================================================
    // Low-level helpers
    // ========================================================================

    /**
     * Reads a SQLite varint (1-9 bytes, big-endian, 7 bits per byte except the 9th).
     * @param data source bytes
     * @param offset offset of the varint
     * @return {value, length} (value is the 64-bit twos-complement result)
     */
    private static long[] readVarint(byte[] data, int offset) {
        long result = 0;
        for (int i = 0; i < 8; i++) {
            if (offset + i >= data.length) {
                throw new JsUserRuntimeException("Corrupt SQLite database: truncated varint at offset " + offset);
            }
            int b = data[offset + i] & 0xFF;
            result = (result << 7) | (b & 0x7F);
            if ((b & 0x80) == 0) {
                return new long[]{result, i + 1};
            }
        }
        if (offset + 8 >= data.length) {
            throw new JsUserRuntimeException("Corrupt SQLite database: truncated varint at offset " + offset);
        }
        result = (result << 8) | (data[offset + 8] & 0xFF);
        return new long[]{result, 9};
    }

    /**
     * Reads a big-endian unsigned 16-bit integer.
     * @param data source bytes
     * @param offset offset
     * @return value
     */
    private static int u16(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    /**
     * Reads a big-endian unsigned 32-bit integer.
     * @param data source bytes
     * @param offset offset
     * @return value
     */
    private static int u32(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
    }

    /**
     * Reads a big-endian integer of 1-8 bytes and sign-extends it (twos-complement).
     * @param data source bytes
     * @param offset offset
     * @param len length in bytes (1-8)
     * @return signed value
     */
    private static long signedBigEndian(byte[] data, int offset, int len) {
        long value = 0;
        for (int i = 0; i < len; i++) {
            value = (value << 8) | (data[offset + i] & 0xFF);
        }
        int shift = 64 - 8 * len;
        return value << shift >> shift; // sign extension
    }

    /**
     * Extracts a byte range with bounds checking.
     * @param data source bytes
     * @param offset start offset
     * @param len length
     * @return copied range
     */
    private static byte[] extract(byte[] data, int offset, int len) {
        if (offset < 0 || len < 0 || offset + len > data.length) {
            throw new JsUserRuntimeException("Corrupt SQLite database: record body exceeds the payload size");
        }
        byte[] result = new byte[len];
        System.arraycopy(data, offset, result, 0, len);
        return result;
    }

    // ========================================================================
    // Help / Documentation
    // ========================================================================

    /**
     * Returns a help text describing the JS sqlite API with usage examples.
     * @return help text as a multi-line string
     */
    public static String help() {
        return """
                JS SQLite API (namespace 'sqlite')
                ==================================

                Read-only access to SQLite database files (.db, .db3, .sqlite, .sqlite3) with a
                small built-in parser (no sqlite-jdbc dependency). Database files must live
                inside the project base directory; security is identical to fs.* (no '..',
                no absolute paths outside the base, no symbolic links leaving the base).
                Alternatively raw database bytes (e.g. fs.readBytes(path)) can be passed
                instead of a path. Only the main database file is read.

                Supported: listing schema objects and reading rows of ordinary (rowid) tables,
                including multi-level table b-trees and cell payloads that spill onto overflow
                pages. Value mapping: NULL -> null, INTEGER -> number (64-bit), REAL -> number,
                TEXT -> string (database text encoding), BLOB -> Uint8Array. Each row carries
                its "rowid"; an INTEGER PRIMARY KEY column is stored as NULL in the record
                (it aliases the rowid) - use row.rowid for it.

                Not supported: writing, SQL statements, index/view/trigger traversal, WITHOUT
                ROWID tables (root page is an index b-tree), virtual tables, and WAL content
                (a WAL-mode database is read as-is with a log warning).

                sqlite.tables(pathOrBytes)            - List schema objects as
                                                         [{name, type, tblName, rootpage, sql}]
                                                         with type one of 'table', 'index',
                                                         'view', 'trigger'.
                sqlite.rows(pathOrBytes, tableName)   - Read all rows of a table as
                                                         [{rowid, <col1>, <col2>, ...}], in
                                                         rowid order. Column names are taken
                                                         from the CREATE TABLE statement
                                                         (fallback: col0, col1, ...).
                sqlite.forEachRow(pathOrBytes, tableName, fn)
                                                      - Stream rows to fn(row); fn returning
                                                         false stops the iteration (filtering
                                                         without materializing all rows).
                                                         Returns the number of rows visited.

                Limits: database files up to %d bytes (%.0f MiB), single row payloads up to
                %d bytes (%.0f MiB). Corrupt files, unsupported page types and cyclic
                b-tree/overflow references are rejected with clear error messages.

                --- Help ---
                sqlite.help()                         - This help text.

                Examples:
                    var names = sqlite.tables("data/app.db").map(t => t.name);
                    var rows = sqlite.rows("src/test/resources/js/animals.db", "animals");
                    rows.filter(r => r.num_legs == 4).map(r => r.name);  // -> ["dog"]
                    sqlite.forEachRow("data/app.db", "log", row => {
                      if (row.level == "ERROR") { console.log(row); return false; }
                      return true;
                    });
                """.formatted(MAX_DB_BYTES, MAX_DB_BYTES / (1024.0 * 1024.0),
                MAX_ROW_PAYLOAD_BYTES, MAX_ROW_PAYLOAD_BYTES / (1024.0 * 1024.0));
    }
}
