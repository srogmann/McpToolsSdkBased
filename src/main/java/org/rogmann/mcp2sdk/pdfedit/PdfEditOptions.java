package org.rogmann.mcp2sdk.pdfedit;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The options of {@code pdfedit.create()} and {@code pdfedit.edit()}, parsed strictly.
 * <p>
 * Unknown names are an error that names the supported ones (house rule: a typo in an option must
 * not silently change what is written), and every value is validated where it is read. The
 * defaults are the decisions of the design: no incremental update, no object-stream compression,
 * no dates, verify on.
 * </p>
 */
public final class PdfEditOptions {

    /** Supported option names - the only keys a caller may pass. */
    public static final Set<String> NAMES = Set.of("incremental", "compress", "id", "dates",
            "verify", "maxOutputBytes");

    /** Whether the changes are appended as a new revision (§2.4 level 3). */
    private final boolean incremental;

    /** Whether the full save packs objects into object streams (and writes an xref stream). */
    private final boolean compress;

    /** The {@code /ID} seed, or {@code null} to derive one from input and ops. */
    private final Long id;

    /** The document dates to write, or {@code null} to keep whatever the document has. */
    private final Calendar created;

    /** The {@code /ModDate} to write, or {@code null} to keep whatever the document has. */
    private final Calendar modified;

    /** Whether the result is verified with the project's own reader. */
    private final boolean verify;

    /** The size limit for the result. */
    private final long maxOutputBytes;

    private PdfEditOptions(boolean incremental, boolean compress, Long id, Calendar created,
                           Calendar modified, boolean verify, long maxOutputBytes) {
        this.incremental = incremental;
        this.compress = compress;
        this.id = id;
        this.created = created;
        this.modified = modified;
        this.verify = verify;
        this.maxOutputBytes = maxOutputBytes;
    }

    /** The default options, as {@code pdfedit.edit(path, ops)} uses them. */
    public static PdfEditOptions defaults() {
        return new PdfEditOptions(false, false, null, null, null, true,
                PdfEditLimits.MAX_OUTPUT_BYTES);
    }

    /**
     * Parses an options object (already converted to Java structures).
     * @param values the options map, or {@code null}
     * @return the parsed options
     * @throws PdfEditException with code {@code usage} for an unknown name or a wrong value
     */
    public static PdfEditOptions parse(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return defaults();
        }
        Set<String> unknown = new TreeSet<>(values.keySet());
        unknown.removeAll(NAMES);
        if (!unknown.isEmpty()) {
            throw PdfEditException.usage("unknown option(s) " + unknown,
                    "supported: " + new TreeSet<>(NAMES));
        }
        boolean incremental = bool(values.get("incremental"), "incremental", false);
        boolean compress = bool(values.get("compress"), "compress", false);
        Long id = id(values.get("id"));
        boolean verify = bool(values.get("verify"), "verify", true);
        long maxOutputBytes = number(values.get("maxOutputBytes"), "maxOutputBytes",
                PdfEditLimits.MAX_OUTPUT_BYTES);
        if (maxOutputBytes < 1 || maxOutputBytes > PdfEditLimits.MAX_OUTPUT_BYTES) {
            throw PdfEditException.usage("maxOutputBytes is " + maxOutputBytes,
                    "allowed range is 1.." + PdfEditLimits.MAX_OUTPUT_BYTES);
        }
        Calendar created = null;
        Calendar modified = null;
        Object dates = values.get("dates");
        if (dates != null) {
            if (!(dates instanceof Map<?, ?> map)) {
                throw PdfEditException.usage("dates is not an object",
                        "dates:{created:'2026-11-06T09:00:00Z', modified:'2026-11-06T09:30:00Z'}"
                                + " - without dates no date is written");
            }
            Map<String, Object> dateMap = stringKeys(map);
            Set<String> unknownDates = new TreeSet<>(dateMap.keySet());
            unknownDates.removeAll(Set.of("created", "modified"));
            if (!unknownDates.isEmpty()) {
                throw PdfEditException.usage("unknown date field(s) " + unknownDates,
                        "dates takes created and modified");
            }
            created = date(dateMap.get("created"), "dates.created");
            modified = date(dateMap.get("modified"), "dates.modified");
        }
        if (incremental && compress) {
            throw PdfEditException.usage("incremental:true cannot be combined with compress:true",
                    "an incremental update appends to the existing xref form;"
                            + " use compress only on a full save");
        }
        return new PdfEditOptions(incremental, compress, id, created, modified, verify,
                maxOutputBytes);
    }

    private static Map<String, Object> stringKeys(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private static boolean bool(Object value, String name, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        throw PdfEditException.usage("option " + name + " is " + describe(value),
                name + " is true or false");
    }

    private static long number(Object value, String name, long fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        throw PdfEditException.usage("option " + name + " is " + describe(value),
                name + " is a number");
    }

    private static Long id(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean || !(value instanceof Number n)) {
            throw PdfEditException.usage("option id is " + describe(value),
                    "id is a signed 64-bit number, e.g. 4711 - it seeds the trailer's /ID");
        }
        return n.longValue();
    }

    /**
     * Parses an ISO-8601 date. Three spellings are accepted, because all three occur in practice:
     * a full timestamp with zone ({@code 2026-11-06T09:30:00Z}), a local timestamp (read as UTC)
     * and a plain date (midnight UTC).
     * @param value the value
     * @param what the option path for the error message
     * @return the calendar, or {@code null} when no value was given
     */
    private static Calendar date(Object value, String what) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw PdfEditException.usage(what + " is " + describe(value),
                    what + " is an ISO-8601 string, e.g. '2026-11-06T09:30:00Z'");
        }
        String trimmed = text.trim();
        try {
            ZonedDateTime zoned = OffsetDateTime.parse(trimmed).toZonedDateTime();
            return GregorianCalendar.from(zoned);
        } catch (DateTimeParseException ignored) {
            // Not a timestamp with a zone - the local and the date forms follow.
        }
        try {
            return GregorianCalendar.from(LocalDateTime.parse(trimmed).atZone(ZoneOffset.UTC));
        } catch (DateTimeParseException ignored) {
            // Not a local timestamp - the plain date form follows.
        }
        try {
            return GregorianCalendar.from(LocalDate.parse(trimmed).atStartOfDay(ZoneOffset.UTC));
        } catch (DateTimeParseException e) {
            throw PdfEditException.usage(what + " reads '" + text + "'",
                    "an ISO-8601 date is expected: '2026-11-06T09:30:00Z',"
                            + " '2026-11-06T09:30:00' or '2026-11-06'");
        }
    }

    private static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        return "'" + value + "' (" + value.getClass().getSimpleName() + ")";
    }

    /** @return whether the changes are appended as a new revision */
    public boolean incremental() {
        return incremental;
    }

    /** @return whether object streams are used */
    public boolean compress() {
        return compress;
    }

    /** @return the {@code /ID} seed or {@code null} */
    public Long id() {
        return id;
    }

    /** @return the {@code /CreationDate} to write or {@code null} */
    public Calendar created() {
        return created;
    }

    /** @return the {@code /ModDate} to write or {@code null} */
    public Calendar modified() {
        return modified;
    }

    /** @return whether the result is verified with the own reader */
    public boolean verify() {
        return verify;
    }

    /** @return the size limit of the result */
    public long maxOutputBytes() {
        return maxOutputBytes;
    }
}
