package org.rogmann.mcp2sdk.js;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link JsSQLite} (read-only SQLite access incl. overflow-page mechanics).
 */
class JsSQLiteTest {

    private static final int PAGE_SIZE = 512;

    private String oldProjectDir;

    @BeforeEach
    void setUp() {
        oldProjectDir = System.getProperty("IDE_PROJECT_DIR");
        System.setProperty("IDE_PROJECT_DIR", Path.of(".").toAbsolutePath().normalize().toString());
    }

    @AfterEach
    void tearDown() {
        if (oldProjectDir != null) {
            System.setProperty("IDE_PROJECT_DIR", oldProjectDir);
        } else {
            System.clearProperty("IDE_PROJECT_DIR");
        }
    }

    // ========================================================================
    // Fixture database (animals.db from src/test/resources/js)
    // ========================================================================

    @Test
    void tablesOfFixtureDatabase() throws IOException {
        byte[] db = fixtureDb();
        List<Map<String, Object>> tables = JsSQLite.tables(db);
        assertEquals(2, tables.size());

        Map<String, Object> animals = tables.get(0);
        assertEquals("animals", animals.get("name"));
        assertEquals("table", animals.get("type"));
        assertEquals("animals", animals.get("tblName"));
        assertEquals(2L, animals.get("rootpage"));
        assertEquals("CREATE TABLE animals(id INTEGER, name, num_legs INTEGER)", animals.get("sql"));

        Map<String, Object> memos = tables.get(1);
        assertEquals("memos", memos.get("name"));
        assertEquals("table", memos.get("type"));
        assertEquals(3L, memos.get("rootpage"));
    }

    @Test
    void rowsOfFixtureDatabase() throws IOException {
        byte[] db = fixtureDb();
        List<Map<String, Object>> rows = JsSQLite.rows(db, "animals");
        assertEquals(4, rows.size());

        Map<String, Object> first = rows.get(0);
        assertEquals(1L, first.get("rowid"));
        assertEquals(1L, first.get("id"));
        assertEquals("dog", first.get("name"));
        assertEquals(4L, first.get("num_legs"));

        assertEquals("fish", rows.get(1).get("name"));
        assertEquals("bird", rows.get(2).get("name"));
        assertEquals("bug", rows.get(3).get("name"));
        assertEquals(0L, rows.get(1).get("num_legs"));
        assertEquals(6L, rows.get(3).get("num_legs"));
    }

    @Test
    void rowsOfSecondTable() throws IOException {
        byte[] db = fixtureDb();
        List<Map<String, Object>> rows = JsSQLite.rows(db, "memos");
        assertEquals(2, rows.size());

        Map<String, Object> first = rows.get(0);
        assertEquals(1L, first.get("rowid"));
        assertEquals("deliver project description", first.get("text"));
        assertEquals(10L, first.get("priority"));

        // Second record with a long text spilling onto overflow pages
        // (payload 10784 bytes, page size 4096 -> K-branch, 2 overflow pages).
        Map<String, Object> second = rows.get(1);
        assertEquals(2L, second.get("rowid"));
        assertEquals(20L, second.get("priority"));
        String longText = (String) second.get("text");
        assertEquals(10778, longText.length());
        assertTrue(longText.startsWith("Preamble\n\nWhereas recognition of the inherent dignity"));
        assertTrue(longText.contains("inalienable rights"));
    }

    @Test
    void forEachRowWithEarlyAbort() throws IOException {
        byte[] db = fixtureDb();
        List<String> visited = new java.util.ArrayList<>();
        long count = JsSQLite.forEachRow(db, "animals", row -> {
            visited.add((String) row.get("name"));
            return visited.size() < 2; // stop after the 2nd row
        });
        assertEquals(2L, count);
        assertEquals(List.of("dog", "fish"), visited);
    }

    @Test
    void forEachRowWithoutAbortVisitsAllRows() throws IOException {
        byte[] db = fixtureDb();
        long count = JsSQLite.forEachRow(db, "animals", row -> true);
        assertEquals(4L, count);
    }

    @Test
    void rowsViaPath() throws IOException {
        // resolveSafePath uses IDE_PROJECT_DIR -> the fixture lives at src/test/resources/js/animals.db
        List<Map<String, Object>> rows = JsSQLite.rows("src/test/resources/js/animals.db", "animals");
        assertEquals(4, rows.size());
        assertEquals("dog", rows.get(0).get("name"));
    }

    // ========================================================================
    // Overflow-page mechanics (synthetic database, K-branch and M-branch)
    // ========================================================================

    @Test
    void overflowSinglePageKBranch() {
        // textLen = 798 -> payload P = 803, K = 295 <= X = 477 -> local = K (K-branch),
        // 508 overflow bytes = exactly one full overflow page.
        byte[] db = buildOverflowDb(798);
        List<Map<String, Object>> rows = JsSQLite.rows(db, "big");
        assertEquals(1, rows.size());
        assertEquals(1L, rows.get(0).get("rowid"));
        assertEquals(1L, rows.get(0).get("id"));
        assertEquals(798, ((String) rows.get(0).get("txt")).length());
        assertEquals(expectedText(798), rows.get(0).get("txt"));
    }

    @Test
    void overflowTwoPagesMBranch() {
        // textLen = 998 -> payload P = 1003, K = 495 > X = 477 -> local = M = 39 (M-branch),
        // 964 overflow bytes = two overflow pages (508 + 456).
        byte[] db = buildOverflowDb(998);
        List<Map<String, Object>> rows = JsSQLite.rows(db, "big");
        assertEquals(1, rows.size());
        assertEquals(998, ((String) rows.get(0).get("txt")).length());
        assertEquals(expectedText(998), rows.get(0).get("txt"));
    }

    @Test
    void overflowMultiPageChain() {
        // textLen = 4000 -> payload P = 4005; overflow = 4005 - 39 = 3966 bytes
        // -> 8 overflow pages (7*508 + 410).
        byte[] db = buildOverflowDb(4000);
        List<Map<String, Object>> rows = JsSQLite.rows(db, "big");
        assertEquals(1, rows.size());
        assertEquals(4000, ((String) rows.get(0).get("txt")).length());
        assertEquals(expectedText(4000), rows.get(0).get("txt"));
    }

    @Test
    void smallPayloadWithoutOverflow() {
        byte[] db = buildOverflowDb(10);
        List<Map<String, Object>> rows = JsSQLite.rows(db, "big");
        assertEquals(1, rows.size());
        assertEquals(10, ((String) rows.get(0).get("txt")).length());
        assertEquals(expectedText(10), rows.get(0).get("txt"));
    }

    // ========================================================================
    // Column name extraction
    // ========================================================================

    @Test
    void extractColumnNamesSimple() {
        assertEquals(List.of("id", "name", "num_legs"),
                JsSQLite.extractColumnNames("CREATE TABLE animals(id INTEGER, name, num_legs INTEGER)"));
    }

    @Test
    void extractColumnNamesWithConstraints() {
        assertEquals(List.of("a", "b", "c"),
                JsSQLite.extractColumnNames(
                        "CREATE TABLE t(a INTEGER PRIMARY KEY, b VARCHAR(10) NOT NULL, c TEXT, "
                                + "UNIQUE(b), CHECK (b != 'x'), FOREIGN KEY (c) REFERENCES o(x))"));
    }

    @Test
    void extractColumnNamesQuoted() {
        assertEquals(List.of("select", "weird \"name\"", "bracket"),
                JsSQLite.extractColumnNames(
                        "CREATE TABLE t(\"select\" TEXT, \"weird \"\"name\"\"\" TEXT, [bracket] TEXT)"));
    }

    @Test
    void extractColumnNamesFallback() {
        assertTrue(JsSQLite.extractColumnNames("not a create statement").isEmpty());
        assertTrue(JsSQLite.extractColumnNames(null).isEmpty());
    }

    // ========================================================================
    // Error handling
    // ========================================================================

    @Test
    void invalidMagic() {
        byte[] garbage = new byte[512];
        garbage[0] = 'S';
        JsUserRuntimeException e = assertThrows(JsUserRuntimeException.class, () -> JsSQLite.tables(garbage));
        assertTrue(e.getMessage().contains("Not a SQLite database"));
    }

    @Test
    void tooSmallFile() {
        JsUserRuntimeException e = assertThrows(JsUserRuntimeException.class,
                () -> JsSQLite.tables(new byte[42]));
        assertTrue(e.getMessage().contains("100-byte database header"));
    }

    @Test
    void unknownTable() {
        byte[] db = fixtureDbUnchecked();
        JsUserRuntimeException e = assertThrows(JsUserRuntimeException.class,
                () -> JsSQLite.rows(db, "nonexistent"));
        assertTrue(e.getMessage().contains("Table not found: nonexistent"));
        assertTrue(e.getMessage().contains("animals"));
        assertTrue(e.getMessage().contains("memos"));
    }

    @Test
    void pathTraversalRejected() {
        assertThrows(JsUserRuntimeException.class,
                () -> JsSQLite.rows("../../../../etc/passwd", "x"));
        assertThrows(JsUserRuntimeException.class,
                () -> JsSQLite.rows("src/test/resources/js/nonexistent.db", "x"));
    }

    // ========================================================================
    // Fixture access
    // ========================================================================

    private byte[] fixtureDb() throws IOException {
        return fixtureDbUnchecked();
    }

    private byte[] fixtureDbUnchecked() {
        try (InputStream in = JsSQLiteTest.class.getResourceAsStream("/js/animals.db")) {
            if (in == null) {
                throw new IllegalStateException("Test fixture /js/animals.db not found");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load test fixture", e);
        }
    }

    // ========================================================================
    // Synthetic overflow database builder
    // ========================================================================

    /**
     * Builds a minimal valid SQLite database with page size 512 containing one table
     * {@code big(id INTEGER, txt TEXT)} with a single row whose text spills onto
     * {@code ceil(overflow / (U-4))} overflow pages (U = 512, no reserved space).
     * @param textLen length of the text value (controls the number of overflow pages)
     * @return database file bytes
     */
    private static byte[] buildOverflowDb(int textLen) {
        String text = expectedText(textLen);
        int usable = PAGE_SIZE; // reserved = 0

        // --- record: (id = 1, txt = text) ---
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        byte[] serialId = {(byte) 0x01};                       // serial type 1: 8-bit int
        byte[] serialTxt = varint(13L + 2L * textLen);         // serial type: text
        byte[] body = new byte[1 + textLen];
        body[0] = 0x01;                                        // id = 1
        System.arraycopy(textBytes, 0, body, 1, textLen);
        byte[] header = concat(varint(1 + serialId.length + serialTxt.length), serialId, serialTxt);
        byte[] record = concat(header, body);
        long payloadSize = record.length;

        // --- local/overflow split per file format spec (table b-tree leaf) ---
        int x = usable - 35;
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
        long overflowTotal = payloadSize - local;
        int overflowPages = (int) ((overflowTotal + usable - 4 - 1) / (usable - 4));
        int totalPages = 2 + overflowPages; // page 1 = schema, page 2 = table root, then overflow

        // --- cell on page 2 ---
        byte[] cell = concat(varint(payloadSize), varint(1L), // payload size, rowid 1
                slice(record, 0, local),
                overflowPages > 0 ? u32(3) : new byte[0]);    // first overflow page = 3
        int cellOffset = PAGE_SIZE - cell.length;

        // --- page 2: table leaf b-tree root ---
        byte[] page2 = new byte[PAGE_SIZE];
        page2[0] = 0x0d;
        putU16(page2, 1, 0);                       // no freeblocks
        putU16(page2, 3, 1);                       // 1 cell
        putU16(page2, 5, cellOffset);              // content area start
        page2[7] = 0;                              // no fragmented bytes
        putU16(page2, 8, cellOffset);              // cell pointer array
        System.arraycopy(cell, 0, page2, cellOffset, cell.length);

        // --- overflow pages 3..(2+overflowPages) ---
        byte[][] overflow = new byte[overflowPages][];
        int remaining = (int) overflowTotal;
        int recordPos = local;
        for (int i = 0; i < overflowPages; i++) {
            byte[] page = new byte[PAGE_SIZE];
            boolean last = i == overflowPages - 1;
            putU32(page, 0, last ? 0 : 4 + i);     // next overflow page (0 = end of chain)
            int chunk = Math.min(usable - 4, remaining);
            System.arraycopy(record, recordPos, page, 4, chunk);
            recordPos += chunk;
            remaining -= chunk;
            overflow[i] = page;
        }

        // --- page 1: header + sqlite_schema leaf ---
        byte[] page1 = new byte[PAGE_SIZE];
        putBytes(page1, 0, "SQLite format 3\u0000".getBytes(StandardCharsets.US_ASCII));
        putU16(page1, 16, PAGE_SIZE);
        page1[18] = 1;                             // write version (legacy)
        page1[19] = 1;                             // read version
        page1[20] = 0;                             // reserved space
        page1[21] = 64;                            // max embedded payload fraction
        page1[22] = 32;                            // min embedded payload fraction
        page1[23] = 32;                            // leaf payload fraction
        putU32(page1, 24, 1);                      // file change counter
        putU32(page1, 28, totalPages);             // in-header database size
        putU32(page1, 32, 0);                      // no freelist
        putU32(page1, 36, 0);
        putU32(page1, 40, 1);                      // schema cookie
        putU32(page1, 44, 4);                      // schema format
        putU32(page1, 48, 0);                      // default cache size
        putU32(page1, 52, 0);                      // largest root page (no auto-vacuum)
        putU32(page1, 56, 1);                      // text encoding: UTF-8
        putU32(page1, 60, 0);                      // user version
        putU32(page1, 64, 0);                      // no incremental vacuum
        putU32(page1, 68, 0);                      // application id
        putU32(page1, 92, 1);                      // version-valid-for
        putU32(page1, 96, 3045000);                // SQLITE_VERSION_NUMBER

        byte[] schemaRecord = recordOf("table", "big", "big", 2L, "CREATE TABLE big(id INTEGER, txt TEXT)");
        byte[] schemaCell = concat(varint(schemaRecord.length), varint(1L), schemaRecord);
        int schemaCellOffset = PAGE_SIZE - schemaCell.length;
        int schemaHeader = 100;
        page1[schemaHeader] = 0x0d;                // leaf table b-tree
        putU16(page1, schemaHeader + 1, 0);        // no freeblocks
        putU16(page1, schemaHeader + 3, 1);        // 1 cell
        putU16(page1, schemaHeader + 5, schemaCellOffset);
        page1[schemaHeader + 7] = 0;               // no fragmented bytes
        putU16(page1, schemaHeader + 8, schemaCellOffset);
        System.arraycopy(schemaCell, 0, page1, schemaCellOffset, schemaCell.length);

        // --- assemble the file ---
        byte[] db = new byte[totalPages * PAGE_SIZE];
        System.arraycopy(page1, 0, db, 0, PAGE_SIZE);
        System.arraycopy(page2, 0, db, PAGE_SIZE, PAGE_SIZE);
        for (int i = 0; i < overflowPages; i++) {
            System.arraycopy(overflow[i], 0, db, (2 + i) * PAGE_SIZE, PAGE_SIZE);
        }
        return db;
    }

    /**
     * Expected synthetic text: first half 'A', second half 'B'.
     * @param textLen length of the text
     * @return text of the synthetic row
     */
    private static String expectedText(int textLen) {
        return "A".repeat(textLen / 2) + "B".repeat(textLen - textLen / 2);
    }

    /**
     * Builds a record in record format from string and long values
     * (strings -> TEXT serial type, longs -> minimal 8-bit/16-bit/32-bit integer type).
     * @param values column values
     * @return record bytes
     */
    private static byte[] recordOf(Object... values) {
        byte[][] serials = new byte[values.length][];
        byte[][] bodies = new byte[values.length][];
        for (int i = 0; i < values.length; i++) {
            Object v = values[i];
            if (v == null) {
                serials[i] = varint(0);
                bodies[i] = new byte[0];
            } else if (v instanceof String s) {
                byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
                serials[i] = varint(13L + 2L * bytes.length);
                bodies[i] = bytes;
            } else if (v instanceof Long l) {
                if (l >= -128 && l <= 127) {
                    serials[i] = varint(1);
                    bodies[i] = new byte[]{(byte) (long) l};
                } else if (l >= -32768 && l <= 32767) {
                    serials[i] = varint(2);
                    bodies[i] = u16(l.intValue());
                } else {
                    serials[i] = varint(4);
                    bodies[i] = u32(l.intValue());
                }
            } else {
                throw new IllegalArgumentException("Unsupported value: " + v);
            }
        }
        int serialTotal = 0;
        for (byte[] serial : serials) {
            serialTotal += serial.length;
        }
        int headerSize = serialTotal + 1; // + size varint
        while (varint(headerSize).length + serialTotal != headerSize) {
            headerSize = varint(headerSize).length + serialTotal;
        }
        byte[] header = new byte[headerSize];
        byte[] sizeVarint = varint(headerSize);
        int pos = 0;
        System.arraycopy(sizeVarint, 0, header, pos, sizeVarint.length);
        pos += sizeVarint.length;
        for (byte[] serial : serials) {
            System.arraycopy(serial, 0, header, pos, serial.length);
            pos += serial.length;
        }
        return concat(header, concat(bodies));
    }

    /**
     * Encodes a non-negative long as a SQLite varint (7 bits per byte, big-endian,
     * high-order bit set on all but the last byte).
     * @param value value (must be non-negative)
     * @return varint bytes
     */
    private static byte[] varint(long value) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        if (value == 0) {
            out.write(0);
            return out.toByteArray();
        }
        // collect the 7-bit groups (least significant group last)
        java.util.ArrayDeque<Integer> groups = new java.util.ArrayDeque<>();
        long v = value;
        while (v != 0) {
            groups.push((int) (v & 0x7F));
            v >>>= 7;
        }
        // the head of the deque is the most significant group
        while (!groups.isEmpty()) {
            int b = groups.pop();
            out.write(groups.isEmpty() ? b : (b | 0x80));
        }
        return out.toByteArray();
    }

    private static byte[] u16(int value) {
        return new byte[]{(byte) ((value >> 8) & 0xFF), (byte) (value & 0xFF)};
    }

    private static byte[] u32(long value) {
        return new byte[]{(byte) ((value >> 24) & 0xFF), (byte) ((value >> 16) & 0xFF),
                (byte) ((value >> 8) & 0xFF), (byte) (value & 0xFF)};
    }

    private static void putU16(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >> 8) & 0xFF);
        data[offset + 1] = (byte) (value & 0xFF);
    }

    private static void putU32(byte[] data, int offset, long value) {
        data[offset] = (byte) ((value >> 24) & 0xFF);
        data[offset + 1] = (byte) ((value >> 16) & 0xFF);
        data[offset + 2] = (byte) ((value >> 8) & 0xFF);
        data[offset + 3] = (byte) (value & 0xFF);
    }

    private static void putBytes(byte[] target, int offset, byte[] source) {
        System.arraycopy(source, 0, target, offset, source.length);
    }

    private static byte[] slice(byte[] data, int offset, int len) {
        byte[] result = new byte[len];
        System.arraycopy(data, offset, result, 0, len);
        return result;
    }

    private static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] a : arrays) {
            total += a.length;
        }
        byte[] result = new byte[total];
        int pos = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, result, pos, a.length);
            pos += a.length;
        }
        return result;
    }
}
