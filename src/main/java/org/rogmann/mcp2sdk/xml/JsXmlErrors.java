package org.rogmann.mcp2sdk.xml;

import java.io.Serial;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Internal control-flow exceptions of the {@code xml} module.
 * <p>
 * They are thrown deep inside source handling and parsing and translated into result
 * envelopes with an explicit {@code status} by {@link JsXml}. A caller never sees these
 * exception types for expected situations (missing file, size limit, cache full, malformed
 * document); they only surface for bugs, where the generic error path is right.
 * </p>
 */
final class JsXmlErrors {

    private JsXmlErrors() {
        // Container class
    }

    /** A size or count limit was exceeded (result status {@code tooLarge}). */
    static final class TooLarge extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 20260920L;

        TooLarge(String message) {
            super(message);
        }
    }

    /** The source does not exist or an archive entry is missing (result status {@code notFound}). */
    static final class NotFound extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 20260920L;

        NotFound(String message) {
            super(message);
        }
    }

    /**
     * The document is not well-formed (result status {@code parseError}).
     * <p>
     * Line and column are 1-based when the parser reported them and {@code 0} otherwise;
     * the message never contains an absolute path.
     * </p>
     */
    static final class Parse extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 20260920L;

        /** Display path of the source. */
        private final String display;
        /** Reported line (1-based) or 0. */
        private final int line;
        /** Reported column (1-based) or 0. */
        private final int column;

        Parse(String display, int line, int column, String message) {
            super(message);
            this.display = display;
            this.line = line;
            this.column = column;
        }

        String display() {
            return display;
        }

        int line() {
            return line;
        }

        int column() {
            return column;
        }

        /** Result map of the parse error ({@code {file, line, column, message}}). */
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("file", display);
            map.put("line", line);
            map.put("column", column);
            map.put("message", getMessage());
            return map;
        }
    }
}
