package org.rogmann.mcp2sdk.js;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Grep-like search over the controlled project file system for JavaScript
 * (namespace {@code search}, specification: docs/js/search.md).
 *
 * <h3>Public JavaScript API</h3>
 * <ul>
 *   <li>{@code search.help()} - help text</li>
 *   <li>{@code search.grep(pattern, target[, options])} - formatted text output</li>
 *   <li>{@code search.find(pattern, target[, options])} - structured result object</li>
 *   <li>{@code search.files(pattern, target[, options])} - array of matching display paths</li>
 *   <li>{@code search.hexgrep(hexPattern, target[, options])} - hex pattern, byte search</li>
 * </ul>
 *
 * <h3>Design</h3>
 * <p>
 * Everything is stream based: plain files, ZIP-like archives and tar/gzip streams are
 * traversed entry by entry without extracting anything to disk. Results refer to real files
 * or to <em>virtual</em> files inside archives; archive levels are separated by {@code #} in
 * the display path ({@code a.ear#b.war#WEB-INF/web.xml}).
 * </p>
 * <p>
 * There are two scans. Text search reads lines and asks a {@link LineMatcher} (a boolean per
 * line). Byte search ({@code binary: "bytes"}, spec 18.1) reads windows of bytes, never
 * decodes and never splits lines, and asks a {@link SpanMatcher} for start and length of each
 * match, so results can carry {@code byteOffset} instead of a line number.
 * </p>
 * <p>
 * Content matching uses JavaScript {@code RegExp} semantics: the caller (see
 * {@link JsSearchBridge}) provides a {@link PatternFactory} that compiles a real JS regular
 * expression, so pattern behaviour is what a JavaScript author expects, not Java's.
 * </p>
 * <p>
 * The Java API is polyglot-free ({@link SearchPattern}, {@link LineMatcher},
 * {@link PathFilter}, plain option maps), which keeps the engine unit-testable without a
 * JavaScript context.
 * </p>
 */
public final class JsSearch {

    private JsSearch() {
        // Utility class
    }

    // ========================================================================
    // Matcher abstractions
    // ========================================================================

    /**
     * Tests a single line of text. {@link JsSearchBridge} implements this with a JavaScript
     * {@code RegExp} (the spec requires JavaScript pattern semantics).
     */
    public interface LineMatcher {
        /**
         * @param line text of one line, without line terminator
         * @return true if the pattern matches the line
         */
        boolean test(String line);

        /**
         * Returns a matcher for the same pattern with the pattern-level options applied.
         * <p>
         * {@code flags} and {@code caseInsensitive} describe the <em>pattern</em>, and only the
         * owner of a pattern can honour them: the engine never sees pattern source code, it only
         * asks for line matches. So {@link #find}/{@link #grep}/{@link #files} call this method
         * once before scanning, and the pattern owner (the JavaScript bridge, or any matcher
         * created from a source string) rebuilds its expression. Implementations that cannot
         * change their flags simply return {@code this}.
         * </p>
         * @param extraFlags the {@code flags} option (empty or {@code null} if not given); it is
         *                   ignored by implementations that were created from a RegExp object,
         *                   because a RegExp carries its own flags
         * @param caseInsensitive the {@code caseInsensitive} option
         * @return a matcher honouring the options, or {@code this} if nothing changed
         */
        default LineMatcher withPatternOptions(String extraFlags, boolean caseInsensitive) {
            return this;
        }
    }

    /**
     * A match inside a scanned window: window-relative start and length, counted in
     * <em>bytes</em> (the window string holds one character per byte, so both are the same).
     *
     * @param start window-relative start index, 0-based
     * @param length length of the match in bytes, always {@code >= 1} as produced by the engine
     */
    public record MatchSpan(int start, int length) {
    }

    /**
     * Finds matches in a window of the byte carrier of {@link #BINARY_WINDOW_BYTES} sized
     * reads: a {@link String} in which every character is exactly one byte (values 0..255).
     * <p>
     * This is what makes byte search (spec 18.1) possible without giving up JavaScript pattern
     * semantics: the regular expression runs over a lossless one-byte-per-character carrier, so
     * the guest {@code RegExp} of the calling context does the matching while the engine keeps
     * ownership of offsets, windows, limits and output. A {@link LineMatcher} cannot express
     * this - it reports a boolean only, without offset, length or per-file accounting.
     * </p>
     */
    public interface SpanMatcher {

        /**
         * Finds the next match at or after {@code from}.
         * @param window carrier string of the current window (1 char = 1 byte)
         * @param from window-relative index to start searching from, 0-based
         * @return the match, or {@code null} if there is none
         */
        MatchSpan find(String window, int from);
    }

    /**
     * Compiles a regular expression into a {@link SpanMatcher}. The JavaScript bridge supplies
     * one that builds a real guest {@code RegExp} (JavaScript semantics, spec 10.2); the engine
     * itself never compiles expressions, it only hands over the effective flags - the user flags
     * plus {@code g} for iterating a window (see {@link #binaryFlags(String)}).
     */
    public interface PatternFactory {
        /**
         * @param source regular expression source (ASCII, checked by the engine for byte search)
         * @param flags effective flags, already merged with the pattern options
         * @return a matcher for that expression
         */
        SpanMatcher compile(String source, String flags);
    }

    /**
     * The pattern of one search, in exactly one of three kinds:
     * <ul>
     *   <li>{@link #byteLiteral(byte[])} - a byte literal, byte search only (spec 10.4),</li>
     *   <li>{@link #regExp(String, String, boolean, PatternFactory)} - a regular expression,
     *       usable in text and in byte search,</li>
     *   <li>{@link #of(LineMatcher)} / {@link #of(SpanMatcher)} - a matcher supplied by a host
     *       side caller (the shape the engine used before byte search existed).</li>
     * </ul>
     * Instances are immutable; {@link #withPatternOptions(String, boolean)} returns a new one.
     */
    public static final class SearchPattern {

        /** What the pattern is made of. */
        enum Kind { BYTE_LITERAL, REGEX, MATCHER }

        private final Kind kind;
        private final byte[] literalBytes;
        private final String literalCarrier;
        private final String regexSource;
        private final String regexFlags;
        private final boolean fromRegExp;
        private final PatternFactory factory;
        private final SpanMatcher fixedSpan;
        private final LineMatcher fixedLine;
        /** Lazily compiled matcher of a REGEX pattern (one search per pattern instance). */
        private SpanMatcher compiled;

        private SearchPattern(Kind kind, byte[] literalBytes, String literalCarrier,
                              String regexSource, String regexFlags, boolean fromRegExp,
                              PatternFactory factory, SpanMatcher fixedSpan, LineMatcher fixedLine) {
            this.kind = kind;
            this.literalBytes = literalBytes;
            this.literalCarrier = literalCarrier;
            this.regexSource = regexSource;
            this.regexFlags = regexFlags;
            this.fromRegExp = fromRegExp;
            this.factory = factory;
            this.fixedSpan = fixedSpan;
            this.fixedLine = fixedLine;
        }

        /**
         * A byte literal: these bytes, in this order, nothing else (spec 10.4).
         * @param bytes the bytes to find, must not be empty
         * @return a pattern for {@code binary: "bytes"}
         */
        public static SearchPattern byteLiteral(byte[] bytes) {
            if (bytes == null) {
                throw new IllegalArgumentException("pattern is required (string, RegExp or byte array)");
            }
            if (bytes.length == 0) {
                throw new IllegalArgumentException("pattern must not be empty");
            }
            byte[] copy = bytes.clone();
            return new SearchPattern(Kind.BYTE_LITERAL, copy,
                    new String(copy, StandardCharsets.ISO_8859_1),
                    null, "", false, null, null, null);
        }

        /**
         * A regular expression.
         * @param source expression source (JavaScript syntax)
         * @param flags flags of the pattern itself (a RegExp object's flags, or empty for a
         *              string pattern)
         * @param fromRegExp true if the pattern came from a RegExp object (its own flags win,
         *                   the {@code flags} option is ignored - spec 10.2)
         * @param factory compiles the expression (a real JS {@code RegExp} in the bridge)
         * @return the pattern
         */
        public static SearchPattern regExp(String source, String flags, boolean fromRegExp,
                                           PatternFactory factory) {
            if (source == null || source.isEmpty()) {
                throw new IllegalArgumentException("pattern must not be empty");
            }
            if (factory == null) {
                throw new IllegalArgumentException("a regular expression pattern needs a pattern factory");
            }
            return new SearchPattern(Kind.REGEX, null, null, source,
                    flags == null ? "" : flags, fromRegExp, factory, null, null);
        }

        /**
         * A pattern from a caller-provided line matcher (text search; byte search is not
         * possible because a boolean says nothing about offsets).
         * @param matcher the matcher
         * @return the pattern
         */
        public static SearchPattern of(LineMatcher matcher) {
            if (matcher == null) {
                throw new IllegalArgumentException("pattern is required (string, RegExp or byte array)");
            }
            return new SearchPattern(Kind.MATCHER, null, null, null, "", false, null, null, matcher);
        }

        /**
         * A pattern from a caller-provided span matcher (usable in both modes; line semantics
         * are derived: a line matches if the matcher finds a span in it).
         * @param matcher the matcher
         * @return the pattern
         */
        public static SearchPattern of(SpanMatcher matcher) {
            if (matcher == null) {
                throw new IllegalArgumentException("pattern is required (string, RegExp or byte array)");
            }
            return new SearchPattern(Kind.MATCHER, null, null, null, "", false, null, matcher, null);
        }

        /** @return what this pattern is made of */
        public Kind kind() {
            return kind;
        }

        /** @return true for a byte-array pattern (spec 10.4) */
        public boolean isByteLiteral() {
            return kind == Kind.BYTE_LITERAL;
        }

        /** @return the bytes of a byte literal, {@code null} for other patterns */
        public byte[] bytes() {
            return literalBytes;
        }

        /** @return the source of a regular expression pattern, {@code null} otherwise */
        public String regexSource() {
            return regexSource;
        }

        /** @return the flags of a regular expression pattern (empty for the other kinds) */
        public String regexFlags() {
            return regexFlags;
        }

        @Override
        public String toString() {
            return switch (kind) {
                case BYTE_LITERAL -> "Uint8Array(" + literalBytes.length + " bytes)";
                case REGEX -> "RegExp /" + regexSource + "/" + regexFlags;
                case MATCHER -> "matcher";
            };
        }

        /**
         * Applies the pattern options, see {@link LineMatcher#withPatternOptions(String, boolean)}:
         * only the owner of a pattern can honour them. A byte literal has no flags and a bare
         * {@link SpanMatcher} cannot rebuild itself, so both are returned unchanged; a wrapped
         * {@link LineMatcher} keeps its own contract - the engine applies the options through it
         * exactly the way it did before byte search existed.
         */
        public SearchPattern withPatternOptions(String extraFlags, boolean caseInsensitive) {
            if (kind == Kind.MATCHER && fixedLine != null) {
                LineMatcher rebuilt = fixedLine.withPatternOptions(extraFlags, caseInsensitive);
                return rebuilt == fixedLine ? this
                        : new SearchPattern(Kind.MATCHER, null, null, null, "", false, null,
                                fixedSpan, rebuilt);
            }
            if (kind != Kind.REGEX) {
                return this;
            }
            String merged = regexFlags;
            if (!fromRegExp && extraFlags != null && !extraFlags.isEmpty()) {
                merged = mergeFlags(merged, extraFlags);
            }
            if (caseInsensitive) {
                merged = mergeFlags(merged, "i");
            }
            return merged.equals(regexFlags) ? this
                    : new SearchPattern(Kind.REGEX, null, null, regexSource, merged,
                            fromRegExp, factory, null, null);
        }

        /** Appends every flag of {@code extra} that {@code flags} does not carry yet. */
        private static String mergeFlags(String flags, String extra) {
            StringBuilder sb = new StringBuilder(flags);
            for (int i = 0; i < extra.length(); i++) {
                char c = extra.charAt(i);
                if (sb.indexOf(String.valueOf(c)) < 0) {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        /** @return true if a byte search ({@code binary: "bytes"}) can be run with this pattern */
        public boolean supportsByteSearch() {
            return kind != Kind.MATCHER || fixedSpan != null;
        }

        /**
         * The matcher of this pattern for byte search.
         * @return the matcher
         * @throws IllegalArgumentException if the pattern cannot produce offsets (a plain
         *         {@link LineMatcher}) - the caller turns that into a user-facing message
         */
        public SpanMatcher spanMatcher() {
            switch (kind) {
                case BYTE_LITERAL:
                    final String carrier = literalCarrier;
                    return (window, from) -> {
                        int at = window.indexOf(carrier, Math.max(0, from));
                        return at < 0 ? null : new MatchSpan(at, carrier.length());
                    };
                case REGEX:
                    if (compiled == null) {
                        String effective = binaryFlags(regexFlags);
                        compiled = factory.compile(regexSource, effective);
                        if (compiled == null) {
                            throw new IllegalArgumentException("the pattern factory returned no matcher"
                                    + " for /" + regexSource + "/" + effective);
                        }
                    }
                    return compiled;
                case MATCHER:
                default:
                    if (fixedSpan != null) {
                        return fixedSpan;
                    }
                    throw new IllegalArgumentException("A byte array pattern or a regular expression is"
                            + " needed for binary: \"bytes\" (the given pattern reports lines only)");
            }
        }

        /**
         * The matcher of this pattern for text search.
         * @return a line matcher
         * @throws IllegalArgumentException if none can be derived (a byte literal)
         */
        public LineMatcher lineMatcher() {
            switch (kind) {
                case MATCHER:
                    if (fixedLine != null) {
                        return fixedLine;
                    }
                    final SpanMatcher spans = fixedSpan;
                    return line -> spans.find(line, 0) != null;
                case REGEX:
                    // Line semantics are derived from the same matcher: find(line, 0) != null is
                    // exactly RegExp.prototype.test for a matcher whose lastIndex is set per call.
                    final SpanMatcher regexSpans = spanMatcher();
                    return line -> regexSpans.find(line, 0) != null;
                case BYTE_LITERAL:
                default:
                    throw new IllegalArgumentException("A byte array pattern needs binary: \"bytes\""
                            + " (a byte literal has no line semantics, see search.help())");
            }
        }
    }

    /**
     * Flags the byte scan compiles a regular expression with: {@code g} (iterate all matches of
     * a window, driven by {@code lastIndex}) is added, {@code y} is dropped like in text mode
     * (spec 10.2 and 18.1).
     * <p>
     * {@code d} is deliberately <em>not</em> added: the offsets come from {@code result.index}
     * plus the length of {@code result[0]}, which is portable. Measured on this GraalVM,
     * {@code result.indices} is {@code [[start, end]]} rather than the flat
     * {@code [start, end, ...]} of the specification, so a matcher that trusted it would report
     * wrong offsets.
     * </p>
     * @param flags user flags of the pattern
     * @return the effective flags
     */
    static String binaryFlags(String flags) {
        StringBuilder sb = new StringBuilder(flags == null ? "" : flags);
        for (int i = sb.length() - 1; i >= 0; i--) {
            if (sb.charAt(i) == 'y') {
                sb.deleteCharAt(i);
            }
        }
        if (indexOf(sb, 'g') < 0) {
            sb.append('g');
        }
        return sb.toString();
    }

    private static int indexOf(CharSequence cs, char c) {
        for (int i = 0; i < cs.length(); i++) {
            if (cs.charAt(i) == c) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Rejects a non-ASCII regular expression source in byte search (spec 18.1): outside the
     * byte domain a pattern is a typo, and {@code \xNN} escapes always ASCII.
     * @param source expression source
     * @throws IllegalArgumentException naming the character and its index
     */
    static void requireAsciiRegexSource(String source) {
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c > 0x7F) {
                throw new IllegalArgumentException("binary regex source must be ASCII"
                        + " (use \\xNN escapes); found U+"
                        + String.format("%04X", (int) c) + " at index " + i);
            }
        }
    }

    /**
     * A {@link SpanMatcher} over {@link java.util.regex.Pattern} for host-side callers and unit
     * tests. Production code (the bridge) always compiles a JavaScript {@code RegExp}, because
     * spec 10.2 asks for JavaScript pattern semantics - this helper exists so that the engine can
     * be tested without a JavaScript context. It cannot reproduce ECMAScript anchor behaviour at
     * a scan offset exactly (a Java matcher anchors {@code ^} at the region start).
     * @param source expression source
     * @param flags ECMAScript flags; {@code g}, {@code d} and {@code y} are ignored, the others
     *              are mapped to their Java counterparts
     * @return a matcher over the byte carrier
     */
    public static SpanMatcher javaRegexSpanMatcher(String source, String flags) {
        int javaFlags = 0;
        String f = flags == null ? "" : flags;
        for (int i = 0; i < f.length(); i++) {
            switch (f.charAt(i)) {
                case 'i' -> javaFlags |= Pattern.CASE_INSENSITIVE;
                case 'm' -> javaFlags |= Pattern.MULTILINE;
                case 's' -> javaFlags |= Pattern.DOTALL;
                case 'u' -> javaFlags |= Pattern.UNICODE_CASE;
                case 'x' -> javaFlags |= Pattern.COMMENTS;
                default -> { /* g, d, y, v: no Java equivalent needed here */ }
            }
        }
        final Pattern pattern = Pattern.compile(source, javaFlags);
        return (window, from) -> {
            Matcher m = pattern.matcher(window);
            if (!m.find(Math.max(0, from))) {
                return null;
            }
            return new MatchSpan(m.start(), m.end() - m.start());
        };
    }

    /**
     * Include/exclude filter. It is tested against every candidate path of an item
     * (see {@link #collectMatchingPaths(String)}) and against its basename, so a filter
     * may be archive-local.
     */
    public interface PathFilter {
        /**
         * @param path one candidate path (slash separated)
         * @return true if the path matches this filter
         */
        boolean test(String path);
    }

    /**
     * Builds a {@link PathFilter} from a glob pattern.
     * @param glob glob pattern ({@code * ? [...] **}, see {@link #help()})
     * @param caseInsensitive whether the glob is matched case-insensitively
     * @return a filter matching the glob against paths and basenames
     * @throws IllegalArgumentException if the glob is malformed
     */
    public static PathFilter globFilter(String glob, boolean caseInsensitive) {
        if (glob == null || glob.isEmpty()) {
            throw new IllegalArgumentException("glob pattern must not be empty");
        }
        final Pattern pattern = compileGlob(glob, caseInsensitive);
        final boolean pathGlob = glob.indexOf('/') >= 0;
        return new PathFilter() {
            @Override
            public boolean test(String path) {
                if (pattern.matcher(path).matches()) {
                    return true;
                }
                if (pathGlob) {
                    // Archive-local suffixes: everything after a '#' archive separator.
                    for (int idx = path.indexOf('#'); idx >= 0; idx = path.indexOf('#', idx + 1)) {
                        if (pattern.matcher(path.substring(idx + 1)).matches()) {
                            return true;
                        }
                    }
                }
                return false;
            }
        };
    }

    // ========================================================================
    // Constants / defaults
    // ========================================================================

    /** Separator between archive levels in a display path. */
    public static final String ARCHIVE_SEPARATOR = "#";

    /** Lowercase hex digits (canonical byte representation, spec 18.2). */
    private static final String HEX_DIGITS = "0123456789abcdef";

    /**
     * Bytes in one mebibyte. All size limits are expressed in this unit so that the numeric
     * value and the human-readable value shown in {@link #help()} cannot drift apart; use
     * {@link #mebibytes(long)} for the readable form.
     */
    private static final long MEBIBYTE = 1024L * 1024L;

    /** Default limit for the number of collected matches. */
    public static final int DEFAULT_MAX_MATCHES = 2000;
    /**
     * Default size limit for a plain file in bytes (320 MiB). Single source of truth:
     * docs/js/search.md and {@link #help()} derive from this constant, guarded by
     * {@code JsSearchLimitConsistencyTest}.
     */
    public static final long DEFAULT_MAX_FILE_BYTES = 320 * MEBIBYTE;
    /** Default size limit for an archive entry in bytes (320 MiB), see {@link #DEFAULT_MAX_FILE_BYTES}. */
    public static final long DEFAULT_MAX_ENTRY_BYTES = 320 * MEBIBYTE;
    /** Default output limit of the string methods in bytes. */
    public static final int DEFAULT_MAX_OUTPUT_BYTES = 200000;
    /** Default archive nesting limit when {@code recursiveArchives} is enabled. */
    public static final int DEFAULT_RECURSIVE_ARCHIVE_DEPTH = 8;
    /** Number of bytes inspected for binary detection. */
    public static final int BINARY_SNIFF_BYTES = 8192;

    /** Reason value of {@code truncatedReason}. */
    public static final String REASON_MAX_MATCHES = "maxMatches";
    /** Reason value of {@code truncatedReason}. */
    public static final String REASON_MAX_OUTPUT_BYTES = "maxOutputBytes";

    /** Prefix of the truncation marker appended to string output. */
    public static final String TRUNCATION_MARKER_PREFIX = "-- truncated by search limit: ";

    /** Marker line between non-contiguous context groups. */
    private static final String GROUP_SEPARATOR = "--";

    /** Maximum number of warnings collected in {@code find().warnings}. */
    private static final int MAX_WARNINGS = 20;

    /** Mode of {@code search.grep()} (default): grep-like line output. */
    public static final String MODE_CONTENT = "content";
    /** Mode returning one matching path per line. */
    public static final String MODE_FILES_WITH_MATCHES = "filesWithMatches";
    /** Mode of {@code search.find()}. */
    public static final String MODE_STRUCTURED = "structured";
    /** Mode of the byte search: one {@code path:0xoffset+length} line per match (spec 19.4). */
    public static final String MODE_OFFSETS = "offsets";
    /** Mode of the byte search: one {@code path:count} line per file (spec 19.5). */
    public static final String MODE_COUNTS = "counts";

    /** Value of the {@code binary} option: skip binary files (default, unchanged behaviour). */
    public static final String BINARY_SKIP = "skip";
    /** Value of the {@code binary} option: byte-exact scan without lines (spec 18.1). */
    public static final String BINARY_BYTES = "bytes";

    /** Value of the {@code render} option: escaped ASCII (spec 18.2). */
    public static final String RENDER_ESCAPED = "escaped";
    /** Value of the {@code render} option: canonical lowercase hex, like {@code fs.readHex}. */
    public static final String RENDER_HEX = "hex";

    /**
     * Bytes read per window in byte search. The data of a file or entry is never materialized
     * (the size limits default to 320 MiB, which as a String plus copies would be a heap risk).
     */
    public static final int BINARY_WINDOW_BYTES = 1024 * 1024;
    /**
     * Bytes carried over from one byte-search window to the next for a regular expression
     * (decision D4). A match longer than this that crosses a window boundary can be missed or
     * reported shorter - documented in spec 18.1. Byte literals carry their pattern length
     * instead and are therefore exact.
     */
    public static final int BINARY_REGEX_OVERLAP_BYTES = 32 * 1024;
    /** Largest buffer the byte scan is willing to allocate for a pathological pattern. */
    private static final int BINARY_BUFFER_HARD_CAP = 64 * 1024 * 1024;
    /** Highest accepted value of the {@code preview} option. */
    public static final int MAX_PREVIEW_BYTES = 256;
    /** Default {@code preview} of a byte literal: the offset is the whole information (D5). */
    public static final int DEFAULT_PREVIEW_LITERAL = 0;
    /** Default {@code preview} of a regular expression: a short hex dump helps (D5). */
    public static final int DEFAULT_PREVIEW_REGEX = 16;

    /** Valid JavaScript regular expression flags (also accepted by the {@code flags} option). */
    private static final String VALID_REGEX_FLAGS = "dgimsuvy";

    /** All recognized option names (strict: unknown options are rejected). */
    private static final Set<String> OPTION_NAMES = new LinkedHashSet<>(List.of(
            "recursive", "mode", "filename", "lineNumbers",
            "before", "after", "context", "B", "A", "C",
            "flags", "caseInsensitive", "encoding", "binary", "preview", "render",
            "include", "exclude",
            "archives", "recursiveArchives", "maxArchiveDepth",
            "maxMatches", "maxMatchesPerFile", "maxFileBytes", "maxEntryBytes", "maxOutputBytes"));

    /**
     * Options that describe lines and are therefore rejected for {@code binary: "bytes"}
     * (decision D3). Only an explicitly supplied option triggers the error: {@code lineNumbers}
     * has a default of {@code true}, which byte search simply ignores.
     */
    private static final Set<String> LINE_ONLY_OPTIONS = new LinkedHashSet<>(List.of(
            "lineNumbers", "before", "after", "context", "B", "A", "C"));

    /** Option names in declaration order, used in error messages. */
    private static final String OPTION_LIST = String.join(", ", OPTION_NAMES);

    /** ZIP-like archive extensions. */
    private static final Set<String> ZIP_EXTENSIONS = Set.of("zip", "jar", "war", "ear", "rar", "apk",
            "xlsx", "docx", "pptx", "vsix", "epub");

    /** Plain tar extension. */
    private static final String TAR_EXTENSION = "tar";

    /** Extensions of a single-member gzip stream (text after gunzip). */
    private static final Set<String> GZIP_EXTENSIONS = Set.of("gz", "gzip");

    /** Suffixes meaning "gzip stream containing a tar archive". */
    private static final Set<String> TARBALL_SUFFIXES = Set.of(".tar.gz", ".tgz");

    /** Sort order: lexicographic by Unicode code point (docs/js/search.md, section 4.4). */
    private static final Comparator<String> DISPLAY_PATH_ORDER = JsSearch::compareByCodePoint;

    /**
     * Compares strings by Unicode code points (differs from {@link String#compareTo(String)}
     * only for supplementary characters, but the spec asks for code point order).
     * @param a first string
     * @param b second string
     * @return negative, zero or positive
     */
    static int compareByCodePoint(String a, String b) {
        int i = 0;
        while (i < a.length() && i < b.length()) {
            int cpA = a.codePointAt(i);
            int cpB = b.codePointAt(i);
            if (cpA != cpB) {
                return Integer.compare(cpA, cpB);
            }
            i += Character.charCount(cpA);
        }
        return Integer.compare(a.length(), b.length());
    }

    /**
     * Classifies a file or entry name by its extension (recognition is extension based).
     * @param name file or entry name (may contain slashes)
     * @return {@code "zip"}, {@code "tar"}, {@code "targz"} (gzip + tar), {@code "gzip"}
     *         (single-member gzip) or {@code null} for a plain file
     */
    public static String archiveKindOf(String name) {
        if (name == null) {
            return null;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String suffix : TARBALL_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return "targz";
            }
        }
        int dot = lower.lastIndexOf('.');
        if (dot < 0) {
            return null;
        }
        String ext = lower.substring(dot + 1);
        if (ZIP_EXTENSIONS.contains(ext)) {
            return "zip";
        }
        if (TAR_EXTENSION.equals(ext)) {
            return "tar";
        }
        if (GZIP_EXTENSIONS.contains(ext)) {
            return "gzip";
        }
        return null;
    }

    // ========================================================================
    // Options
    // ========================================================================

    /** Parsed and validated search options. */
    static final class Options {
        boolean recursive;
        String mode = MODE_CONTENT;
        boolean filename = true;
        boolean lineNumbers = true;
        int before;
        int after;
        String flags = "";
        boolean caseInsensitive;
        String encoding = "UTF-8";
        String binary = BINARY_SKIP;
        /** Bytes of context around a byte match, {@code null} = decide by pattern kind (D5). */
        Integer preview;
        String render = RENDER_ESCAPED;
        List<PathFilter> include = List.of();
        List<PathFilter> exclude = List.of();
        boolean archives;
        boolean recursiveArchives;
        Integer maxArchiveDepth;
        int maxMatches = DEFAULT_MAX_MATCHES;
        /** Matches per file in byte search, {@code null} = unlimited (spec 18.1). */
        Integer maxMatchesPerFile;
        long maxFileBytes = DEFAULT_MAX_FILE_BYTES;
        long maxEntryBytes = DEFAULT_MAX_ENTRY_BYTES;
        int maxOutputBytes = DEFAULT_MAX_OUTPUT_BYTES;

        /** True if the scan is the byte-exact one ({@code binary: "bytes"}). */
        boolean binaryBytes() {
            return BINARY_BYTES.equals(binary);
        }

        /** Effective {@code preview}: explicit value, else 0 for a byte literal, 16 for regex. */
        int effectivePreview(boolean byteLiteral) {
            if (preview != null) {
                return preview;
            }
            return byteLiteral ? DEFAULT_PREVIEW_LITERAL : DEFAULT_PREVIEW_REGEX;
        }

        /** Effective archive nesting limit (spec section 28.3). */
        int effectiveMaxArchiveDepth() {
            if (maxArchiveDepth != null) {
                return maxArchiveDepth;
            }
            return recursiveArchives ? DEFAULT_RECURSIVE_ARCHIVE_DEPTH : 1;
        }
    }

    /**
     * Parses and validates the options of one entry point (strict: unknown options throw).
     *
     * @param rawOptions option map as converted by the bridge: values are {@code Boolean},
     *                   {@code Number}, {@code String}, {@code List}, {@link PathFilter} or
     *                   {@code null}; {@code null} means "no options"
     * @param modeContext one of {@code "search.grep"}, {@code "search.find"},
     *                    {@code "search.files"} - decides which {@code mode} is accepted
     * @return the validated options
     */
    public static Options parseOptions(Map<String, Object> rawOptions, String modeContext) {
        Options o = new Options();
        final Map<String, Object> raw = rawOptions == null ? Map.of() : rawOptions;
        for (String key : raw.keySet()) {
            if (!OPTION_NAMES.contains(key)) {
                throw new IllegalArgumentException("Unknown search option '" + key + "'."
                        + " Valid options: " + OPTION_LIST + ". See search.help().");
            }
        }
        // Read first: the include/exclude globs depend on the case sensitivity.
        Object ci = raw.get("caseInsensitive");
        if (ci != null) {
            o.caseInsensitive = toBool("caseInsensitive", ci);
        }
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            String name = e.getKey();
            Object v = e.getValue();
            switch (name) {
                case "recursive" -> o.recursive = toBool(name, v);
                case "filename" -> o.filename = toBool(name, v);
                case "lineNumbers" -> o.lineNumbers = toBool(name, v);
                case "caseInsensitive" -> { /* read before this loop */ }
                case "flags" -> o.flags = toStringOption(name, v, true);
                case "encoding" -> {
                    String enc = toStringOption(name, v, false);
                    String normalized = enc.replace("-", "").replace("_", "").toUpperCase(Locale.ROOT);
                    if (!"UTF8".equals(normalized)) {
                        throw new IllegalArgumentException("Encoding '" + enc + "' is not supported"
                                + " (only 'UTF-8' in this version)");
                    }
                    o.encoding = "UTF-8";
                }
                case "binary" -> {
                    String bin = toStringOption(name, v, false);
                    if (!BINARY_SKIP.equals(bin) && !BINARY_BYTES.equals(bin)) {
                        throw new IllegalArgumentException("Option 'binary' only supports '"
                                + BINARY_SKIP + "' or '" + BINARY_BYTES + "' (got '" + bin + "')");
                    }
                    o.binary = bin;
                }
                case "preview" -> {
                    if (v != null) {
                        int p = toNonNegativeInt(name, v);
                        if (p > MAX_PREVIEW_BYTES) {
                            throw new IllegalArgumentException("Option 'preview' must be at most "
                                    + MAX_PREVIEW_BYTES + " (got " + p + ")");
                        }
                        o.preview = p;
                    }
                }
                case "render" -> {
                    String r = toStringOption(name, v, false);
                    if (!RENDER_ESCAPED.equals(r) && !RENDER_HEX.equals(r)) {
                        throw new IllegalArgumentException("Option 'render' only supports '"
                                + RENDER_ESCAPED + "' or '" + RENDER_HEX + "' (got '" + r + "')");
                    }
                    o.render = r;
                }
                case "include" -> o.include = toFilters(name, v, o.caseInsensitive);
                case "exclude" -> o.exclude = toFilters(name, v, o.caseInsensitive);
                case "archives" -> o.archives = toBool(name, v);
                case "recursiveArchives" -> o.recursiveArchives = toBool(name, v);
                case "maxArchiveDepth" -> o.maxArchiveDepth = toNullablePositiveInt(name, v);
                case "maxMatches" -> o.maxMatches = toNonNegativeInt(name, v);
                case "maxMatchesPerFile" -> o.maxMatchesPerFile = toNullablePositiveInt(name, v);
                case "maxOutputBytes" -> o.maxOutputBytes = toNonNegativeInt(name, v);
                case "maxFileBytes" -> o.maxFileBytes = toNonNegativeLong(name, v);
                case "maxEntryBytes" -> o.maxEntryBytes = toNonNegativeLong(name, v);
                default -> { /* mode and the context options are handled below */ }
            }
        }
        for (int i = 0; i < o.flags.length(); i++) {
            if (VALID_REGEX_FLAGS.indexOf(o.flags.charAt(i)) < 0) {
                throw new IllegalArgumentException("Invalid regular expression flag '" + o.flags.charAt(i)
                        + "' in flags '" + o.flags + "' (valid flags: " + VALID_REGEX_FLAGS + ")");
            }
        }
        // Byte search has no lines: an option that asks for line numbers or line context is a
        // contradiction, not something to ignore silently (decision D3).
        if (o.binaryBytes()) {
            for (String name : LINE_ONLY_OPTIONS) {
                if (raw.get(name) != null) {
                    throw new IllegalArgumentException("Option '" + name + "' is not supported with"
                            + " binary: \"" + BINARY_BYTES + "\" (a byte search has no lines;"
                            + " byte context is called 'preview', see search.help())");
                }
            }
        }
        o.before = resolveContext(raw, "before", "B");
        o.after = resolveContext(raw, "after", "A");
        if (o.recursiveArchives && !o.archives) {
            throw new IllegalArgumentException("recursiveArchives requires archives: true");
        }
        parseMode(raw.get("mode"), modeContext, o);
        if (MODE_COUNTS.equals(o.mode) && !o.filename) {
            throw new IllegalArgumentException("Option 'filename' cannot be false with mode '"
                    + MODE_COUNTS + "': a line of bare numbers could not be traced back to a file");
        }
        return o;
    }

    /**
     * Mode validation per entry point (spec section 19.3, modes of the byte search 19.4/19.5).
     * <ul>
     *   <li>{@code search.grep}: content, filesWithMatches, offsets, counts</li>
     *   <li>{@code search.find}: structured, offsets (counts is string-only)</li>
     *   <li>{@code search.files}: filesWithMatches (that is all it returns)</li>
     * </ul>
     */
    private static void parseMode(Object v, String context, Options o) {
        if (v == null) {
            o.mode = MODE_STRUCTURED.equals(context) ? MODE_STRUCTURED
                    : "search.files".equals(context) ? MODE_FILES_WITH_MATCHES : MODE_CONTENT;
            return;
        }
        if (!(v instanceof String s)) {
            throw new IllegalArgumentException("Option 'mode' must be a string");
        }
        if ("search.grep".equals(context)) {
            if (!MODE_CONTENT.equals(s) && !MODE_FILES_WITH_MATCHES.equals(s)
                    && !MODE_OFFSETS.equals(s) && !MODE_COUNTS.equals(s)) {
                throw new IllegalArgumentException("search.grep() supports mode '" + MODE_CONTENT
                        + "', '" + MODE_FILES_WITH_MATCHES + "', '" + MODE_OFFSETS + "' or '"
                        + MODE_COUNTS + "'");
            }
        } else if ("search.find".equals(context)) {
            if (!MODE_STRUCTURED.equals(s) && !MODE_OFFSETS.equals(s)) {
                throw new IllegalArgumentException("search.find() supports mode '" + MODE_STRUCTURED
                        + "' or '" + MODE_OFFSETS + "' only ('" + MODE_COUNTS + "' is a string mode"
                        + " of search.grep())");
            }
        } else if (!MODE_FILES_WITH_MATCHES.equals(s)) {
            throw new IllegalArgumentException("search.files() supports mode '" + MODE_FILES_WITH_MATCHES
                    + "' only (or omit mode)");
        }
        o.mode = s;
    }

    /**
     * Resolves one context option together with its alias and the {@code context} default
     * (spec sections 14 and 15).
     * @param raw raw option map
     * @param canonical canonical name ({@code before} or {@code after})
     * @param alias short alias ({@code B} or {@code A})
     * @return the effective number of context lines
     */
    private static int resolveContext(Map<String, Object> raw, String canonical, String alias) {
        boolean hasCanonical = raw.get(canonical) != null;
        boolean hasAlias = raw.get(alias) != null;
        int own = -1;
        if (hasCanonical && hasAlias) {
            int a = toNonNegativeInt(canonical, raw.get(canonical));
            int b = toNonNegativeInt(alias, raw.get(alias));
            if (a != b) {
                throw new IllegalArgumentException("Conflicting options: '" + canonical + "' and '" + alias + "'");
            }
            own = a;
        } else if (hasCanonical) {
            own = toNonNegativeInt(canonical, raw.get(canonical));
        } else if (hasAlias) {
            own = toNonNegativeInt(alias, raw.get(alias));
        }
        if (own >= 0) {
            return own;
        }
        // Fall back to context/C (both must agree if both are given).
        boolean hasContext = raw.get("context") != null;
        boolean hasContextAlias = raw.get("C") != null;
        if (!hasContext && !hasContextAlias) {
            return 0;
        }
        int a = hasContext ? toNonNegativeInt("context", raw.get("context")) : -1;
        int b = hasContextAlias ? toNonNegativeInt("C", raw.get("C")) : -1;
        if (a >= 0 && b >= 0 && a != b) {
            throw new IllegalArgumentException("Conflicting options: 'context' and 'C'");
        }
        return Math.max(a, b);
    }

    private static boolean toBool(String name, Object v) {
        if (!(v instanceof Boolean b)) {
            throw new IllegalArgumentException("Option '" + name + "' must be a boolean");
        }
        return b;
    }

    private static String toStringOption(String name, Object v, boolean allowEmpty) {
        if (!(v instanceof String s)) {
            throw new IllegalArgumentException("Option '" + name + "' must be a string");
        }
        if (s.isEmpty() && !allowEmpty) {
            throw new IllegalArgumentException("Option '" + name + "' must not be empty");
        }
        return s;
    }

    private static long toNonNegativeLong(String name, Object v) {
        if (!(v instanceof Number n)) {
            throw new IllegalArgumentException("Option '" + name + "' must be a non-negative integer number");
        }
        double d = n.doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d) || d != Math.rint(d)) {
            throw new IllegalArgumentException("Option '" + name + "' must be a non-negative integer number");
        }
        long l = n.longValue();
        if (l < 0) {
            throw new IllegalArgumentException("Option '" + name + "' must be >= 0 (got " + l + ")");
        }
        return l;
    }

    private static int toNonNegativeInt(String name, Object v) {
        long l = toNonNegativeLong(name, v);
        if (l > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Option '" + name + "' is too large (max " + Integer.MAX_VALUE + ")");
        }
        return (int) l;
    }

    private static Integer toNullablePositiveInt(String name, Object v) {
        if (v == null) {
            return null;
        }
        long l = toNonNegativeLong(name, v);
        if (l < 1) {
            throw new IllegalArgumentException("Option '" + name + "' must be >= 1 (got " + l + ")");
        }
        return (int) Math.min(l, Integer.MAX_VALUE);
    }

    /** Converts an include/exclude option value into filters. */
    private static List<PathFilter> toFilters(String name, Object v, boolean caseInsensitive) {
        if (v == null) {
            return List.of();
        }
        if (!(v instanceof List<?> list)) {
            throw new IllegalArgumentException("Option '" + name + "' must be an array");
        }
        List<PathFilter> filters = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof String glob) {
                try {
                    filters.add(globFilter(glob, caseInsensitive));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(
                            "Invalid " + name + " pattern '" + glob + "': " + e.getMessage());
                }
            } else if (item instanceof PathFilter filter) {
                filters.add(filter);
            } else {
                throw new IllegalArgumentException("All items of '" + name + "' must be strings (glob)"
                        + " or RegExp objects");
            }
        }
        return filters;
    }

    // ========================================================================
    // Glob support
    // ========================================================================

    /**
     * Compiles a glob pattern into an anchored regular expression.
     * <p>
     * Supported: {@code *} (anything except {@code /}), {@code ?} (one character except
     * {@code /}), character classes ({@code [abc]}, {@code [a-z]}, {@code [!a-z]}), and
     * {@code **} (anything, including {@code /}). A {@code "**&#47;"} sequence matches zero or
     * more path segments; consecutive {@code **} collapse to one.
     * </p>
     * @param glob glob pattern
     * @param caseInsensitive whether to ignore case
     * @return the compiled pattern (use {@link Matcher#matches()})
     * @throws IllegalArgumentException if the glob is malformed
     */
    public static Pattern compileGlob(String glob, boolean caseInsensitive) {
        StringBuilder re = new StringBuilder(glob.length() * 2 + 8);
        int n = glob.length();
        int i = 0;
        while (i < n) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < n && glob.charAt(i + 1) == '*') {
                    int j = i + 2;
                    while (j + 1 < n && glob.charAt(j) == '*' && glob.charAt(j + 1) == '*') {
                        j += 2; // collapse "****"
                    }
                    if (j < n && glob.charAt(j) == '/') {
                        re.append("(?:[^/]+/)*"); // zero or more path segments
                        i = j + 1;
                    } else {
                        re.append(".*");
                        i = j;
                    }
                    continue;
                }
                re.append("[^/]*");
                i++;
                continue;
            }
            if (c == '?') {
                re.append("[^/]");
                i++;
                continue;
            }
            if (c == '[') {
                int j = i + 1;
                boolean negated = j < n && (glob.charAt(j) == '!' || glob.charAt(j) == '^');
                if (negated) {
                    j++;
                }
                StringBuilder cls = new StringBuilder(negated ? "^" : "");
                boolean first = true;
                while (j < n && (first || glob.charAt(j) != ']')) {
                    char cc = glob.charAt(j);
                    if (cc == '\\' || cc == '[' || cc == '^') {
                        cls.append('\\');
                    }
                    cls.append(cc);
                    first = false;
                    j++;
                }
                if (j >= n) {
                    throw new IllegalArgumentException("invalid character class (missing ']'): " + glob);
                }
                re.append('[').append(cls).append(']');
                i = j + 1;
                continue;
            }
            // Escape regular expression metacharacters; a backslash in a glob stays literal.
            re.append(".+^${}()|[]?*\\/".indexOf(c) >= 0 ? "\\" + c : String.valueOf(c));
            i++;
        }
        try {
            return Pattern.compile(re.toString(),
                    caseInsensitive ? (Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE) : 0);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(
                    "invalid glob pattern '" + glob + "': " + e.getDescription());
        }
    }

    // ========================================================================
    // Display paths / filter candidate paths
    // ========================================================================

    /**
     * Escapes one segment of an archive display path ({@code #} and {@code %} are
     * percent-encoded, so display paths stay unambiguous and reversible).
     * @param name raw entry name
     * @return escaped name
     */
    public static String escapeDisplaySegment(String name) {
        String result = name;
        if (result.indexOf('%') >= 0) {
            result = replaceLiteral(result, "%", "%25");
        }
        if (result.indexOf('#') >= 0) {
            result = replaceLiteral(result, "#", "%23");
        }
        return result;
    }

    /** Reverses {@link #escapeDisplaySegment(String)}; unknown escape sequences are kept. */
    public static String unescapeDisplaySegment(String s) {
        if (s == null || s.indexOf('%') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    sb.append((char) (hi * 16 + lo));
                    i += 3;
                    continue;
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    /** Literal string replacement (without regular expressions). */
    private static String replaceLiteral(String s, String from, String to) {
        int idx = s.indexOf(from);
        if (idx < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        int start = 0;
        while (idx >= 0) {
            sb.append(s, start, idx).append(to);
            start = idx + from.length();
            idx = s.indexOf(from, start);
        }
        sb.append(s, start, s.length());
        return sb.toString();
    }

    /**
     * Collects the candidate paths an include/exclude filter is tested against
     * (spec section 22).
     * <p>
     * A plain file yields its relative path; an archive entry yields the display path with
     * {@code #} rendered as {@code /} plus every suffix starting after an archive separator,
     * so patterns like {@code WEB-INF/*.xml} work inside archives.
     * </p>
     * @param displayPath display path of the candidate
     * @return candidate paths (never empty)
     */
    public static List<String> collectMatchingPaths(String displayPath) {
        List<String> paths = new ArrayList<>(4);
        paths.add(displayPath.replace('#', '/'));
        for (int idx = displayPath.indexOf('#'); idx >= 0; idx = displayPath.indexOf('#', idx + 1)) {
            paths.add(displayPath.substring(idx + 1).replace('#', '/'));
        }
        return paths;
    }

    /**
     * Declared size of a real file in bytes.
     * @param path absolute path inside a permitted directory
     * @return the file size, or -1 if it cannot be determined (treated as "size unknown")
     */
    private static long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return -1;
        }
    }

    /** Basename of a display path (part after the last {@code /} or {@code #}). */
    public static String basenameOf(String displayPath) {
        int idx = Math.max(displayPath.lastIndexOf('/'), displayPath.lastIndexOf('#'));
        return idx >= 0 ? displayPath.substring(idx + 1) : displayPath;
    }

    /** True if any filter matches one of the candidate paths or the basename. */
    private static boolean matchesAny(List<PathFilter> filters, String displayPath) {
        if (filters.isEmpty()) {
            return false;
        }
        List<String> paths = collectMatchingPaths(displayPath);
        String base = basenameOf(displayPath);
        for (PathFilter f : filters) {
            for (String p : paths) {
                if (f.test(p) || f.test(base)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ========================================================================
    // Public Java entry points (polyglot-free, used by the bridge and by tests)
    // ========================================================================

    /**
     * Runs a search and returns the structured result ({@code search.find()}).
     *
     * @param matcher line matcher (JavaScript RegExp semantics)
     * @param target {@code String} (path or archive display path), {@code List} of targets,
     *               or {@code Map} with {@code path} / {@code entry} / {@code archiveChain}
     *               (raw, unescaped entry names)
     * @param rawOptions raw option map, see {@link #parseOptions(Map, String)}; may be null
     * @return map with {@code matches}, {@code files}, {@code counts}, {@code truncated},
     *         {@code truncatedReason}, {@code warnings}
     */
    public static Map<String, Object> find(LineMatcher matcher, Object target, Map<String, Object> rawOptions) {
        return find(SearchPattern.of(matcher), target, rawOptions);
    }

    /**
     * Runs a search and returns grep-like text ({@code search.grep()}).
     * @param matcher line matcher
     * @param target target, see {@link #find(LineMatcher, Object, Map)}
     * @param rawOptions raw option map, may be null
     * @return formatted output, possibly ending with a truncation marker
     */
    public static String grep(LineMatcher matcher, Object target, Map<String, Object> rawOptions) {
        return grep(SearchPattern.of(matcher), target, rawOptions);
    }

    /**
     * Runs a search and returns the matching display paths ({@code search.files()}).
     * @param matcher line matcher
     * @param target target, see {@link #find(LineMatcher, Object, Map)}
     * @param rawOptions raw option map, may be null
     * @return unique, sorted display paths
     */
    public static List<String> files(LineMatcher matcher, Object target, Map<String, Object> rawOptions) {
        return files(SearchPattern.of(matcher), target, rawOptions);
    }

    /**
     * Structured result of a {@link SearchPattern} - the entry point that supports byte search
     * ({@code binary: "bytes"}, spec 18.1), because only a pattern can provide offsets.
     * @param pattern byte literal, regular expression or matcher
     * @param target target, see {@link #find(LineMatcher, Object, Map)}
     * @param rawOptions raw option map, may be null
     * @return structured result, see {@link #find(LineMatcher, Object, Map)}
     */
    public static Map<String, Object> find(SearchPattern pattern, Object target,
                                           Map<String, Object> rawOptions) {
        Options options = parseOptions(rawOptions, "search.find");
        return run(pattern, target, options).toStructured();
    }

    /**
     * Text result of a {@link SearchPattern} (see {@link #find(SearchPattern, Object, Map)}).
     * @param pattern byte literal, regular expression or matcher
     * @param target target, see {@link #find(LineMatcher, Object, Map)}
     * @param rawOptions raw option map, may be null
     * @return formatted output, possibly ending with a truncation marker
     */
    public static String grep(SearchPattern pattern, Object target, Map<String, Object> rawOptions) {
        Options options = parseOptions(rawOptions, "search.grep");
        Engine engine = run(pattern, target, options);
        return engine.toText(options);
    }

    /**
     * Matching display paths of a {@link SearchPattern} (see {@link #find(SearchPattern, Object, Map)}).
     * @param pattern byte literal, regular expression or matcher
     * @param target target, see {@link #find(LineMatcher, Object, Map)}
     * @param rawOptions raw option map, may be null
     * @return unique, sorted display paths
     */
    public static List<String> files(SearchPattern pattern, Object target, Map<String, Object> rawOptions) {
        Options options = parseOptions(rawOptions, "search.files");
        return run(pattern, target, options).matchedPaths();
    }

    /**
     * Runs {@code search.hexgrep()} (spec 18.3): a hex pattern such as {@code "4d5a 9000"} with
     * {@code .} for any byte, searched with the same target and option rules as
     * {@link #grep(LineMatcher, Object, Map)} (use it with {@code binary: "bytes"}). Parsing
     * lives here (not in the bridge) so that it stays polyglot-free and unit testable.
     *
     * @param hexPattern hex digits, spaces/underscores (ignored) and {@code .} for one byte
     * @param target target, see {@link #find(LineMatcher, Object, Map)}
     * @param rawOptions raw option map, may be null
     * @param factory compiles the expression built from a hex pattern containing {@code .}
     * @return formatted output, like {@code search.grep()}
     */
    public static String hexgrep(String hexPattern, Object target, Map<String, Object> rawOptions,
                                 PatternFactory factory) {
        return grep(hexToPattern(hexPattern, factory), target, rawOptions);
    }

    /**
     * Converts a hex pattern (spec 18.3) into a search pattern: without a {@code .} wildcard the
     * result is an exact byte literal, with one it is a regular expression over byte escapes.
     *
     * @param hexPattern e.g. {@code "4d5a 9000"} or {@code "4d.5a"}
     * @param factory only needed for patterns containing {@code .}
     * @return the pattern
     * @throws IllegalArgumentException for an empty pattern, an odd number of hex digits or any
     *         other character (naming it and its index)
     */
    public static SearchPattern hexToPattern(String hexPattern, PatternFactory factory) {
        if (hexPattern == null || hexPattern.isEmpty()) {
            throw new IllegalArgumentException("hex pattern must not be empty"
                    + " (e.g. \"4d5a 9000\", \".\" matches any byte)");
        }
        List<Byte> fixed = new ArrayList<>();      // bytes before the first wildcard
        List<String> parts = new ArrayList<>();    // regex parts once a wildcard appeared
        boolean wildcard = false;
        int nibble = -1;
        for (int i = 0; i < hexPattern.length(); i++) {
            char c = hexPattern.charAt(i);
            if (c == ' ' || c == '_' || c == '\t') {
                continue; // readability separators
            }
            if (c == '.') {
                if (nibble >= 0) {
                    throw new IllegalArgumentException("Invalid hex pattern \"" + hexPattern
                            + "\": a byte must be two hex digits, \".\" stands for a whole byte"
                            + " (at index " + i + ")");
                }
                wildcard = true;
                if (parts.isEmpty()) {
                    for (byte b : fixed) {
                        parts.add(byteEscape(b));
                    }
                }
                parts.add("[\\x00-\\xff]");
                continue;
            }
            int value = Character.digit(c, 16);
            if (value < 0) {
                throw new IllegalArgumentException("Invalid hex pattern \"" + hexPattern
                        + "\": unexpected character '" + c + "' at index " + i
                        + " (hex digits, spaces, \"_\" and \".\" are allowed)");
            }
            if (nibble < 0) {
                nibble = value;
            } else {
                int combined = (nibble << 4) | value;
                nibble = -1;
                if (wildcard) {
                    parts.add(byteEscape((byte) combined));
                } else {
                    fixed.add((byte) combined);
                }
            }
        }
        if (nibble >= 0) {
            throw new IllegalArgumentException("Invalid hex pattern \"" + hexPattern
                    + "\": odd number of hex digits - bytes are pairs, use \".\" for a whole byte");
        }
        if (!wildcard) {
            if (fixed.isEmpty()) {
                throw new IllegalArgumentException("hex pattern must not be empty"
                        + " (e.g. \"4d5a 9000\", \".\" matches any byte)");
            }
            byte[] bytes = new byte[fixed.size()];
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = fixed.get(i);
            }
            return SearchPattern.byteLiteral(bytes);
        }
        if (factory == null) {
            throw new IllegalArgumentException("A hex pattern with \".\" needs a pattern factory"
                    + " to build the expression");
        }
        return SearchPattern.regExp(String.join("", parts), "", false, factory);
    }

    /** {@code \xnn} escape of one byte (lowercase, JavaScript and Java compatible). */
    static String byteEscape(byte b) {
        return String.format("\\x%02x", b & 0xFF);
    }

    /** Convenience overload without options. */
    public static Map<String, Object> find(SearchPattern pattern, Object target) {
        return find(pattern, target, null);
    }

    /** Convenience overload without options. */
    public static String grep(SearchPattern pattern, Object target) {
        return grep(pattern, target, null);
    }

    /** Convenience overload without options. */
    public static List<String> files(SearchPattern pattern, Object target) {
        return files(pattern, target, null);
    }

    /** Convenience overload without options. */
    public static Map<String, Object> find(LineMatcher matcher, Object target) {
        return find(matcher, target, null);
    }

    /** Convenience overload without options. */
    public static String grep(LineMatcher matcher, Object target) {
        return grep(matcher, target, null);
    }

    /** Convenience overload without options. */
    public static List<String> files(LineMatcher matcher, Object target) {
        return files(matcher, target, null);
    }

    // ========================================================================
    // Targets
    // ========================================================================

    /** A target before existence checks: a file path plus an optional archive entry chain. */
    private static final class RawTarget {
        final String path;
        /** Raw (unescaped) entry names; empty for plain file, directory or whole-archive targets. */
        final List<String> chain;

        RawTarget(String path, List<String> chain) {
            this.path = path;
            this.chain = chain;
        }
    }

    /** Normalizes the polymorphic target argument into a list of {@link RawTarget}s. */
    private static List<RawTarget> parseTargets(Object target) {
        List<RawTarget> result = new ArrayList<>();
        collectTargets(target, result);
        if (result.isEmpty()) {
            throw new IllegalArgumentException("target is required");
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static void collectTargets(Object target, List<RawTarget> out) {
        if (target == null) {
            throw new IllegalArgumentException("target is required");
        }
        if (target instanceof String s) {
            if (s.isEmpty()) {
                throw new IllegalArgumentException("target must not be empty");
            }
            out.add(parseDisplayPathTarget(s));
            return;
        }
        if (target instanceof List<?> list) {
            for (Object item : list) {
                collectTargets(item, out);
            }
            return;
        }
        if (target instanceof Map<?, ?> map) {
            out.add(parseObjectTarget((Map<String, Object>) map));
            return;
        }
        throw new IllegalArgumentException("target must be a string, an array of targets or an object"
                + " {path[, entry][, archiveChain]} (got " + target.getClass().getSimpleName() + ")");
    }

    /** Parses a string target, splitting archive levels at {@code #}. */
    private static RawTarget parseDisplayPathTarget(String s) {
        List<String> chain = new ArrayList<>();
        if (".".equals(s)) {
            return new RawTarget(".", chain);
        }
        String path = s;
        if (s.indexOf('#') >= 0 && !isRegularFilePath(s)) {
            // Only a real file whose *whole* name contains '#' wins over the display syntax.
            // Probing just the part before the first '#' would classify every archive target
            // ("app.jar#application.yml") as a plain file path and report "File not found".
            String[] segments = s.split("#", -1);
            path = segments[0];
            for (int i = 1; i < segments.length; i++) {
                chain.add(unescapeDisplaySegment(segments[i]));
            }
        }
        return new RawTarget(path, chain);
    }

    /**
     * Checks whether a string addresses an existing regular file.
     * @param s project-relative path (may contain {@code '#'} and percent escapes)
     * @return true if it is an existing regular file; false for anything else, including
     *         paths that cannot be resolved at all (never throws)
     */
    private static boolean isRegularFilePath(String s) {
        try {
            return Files.isRegularFile(JsFileSystem.resolveSafePath(s), LinkOption.NOFOLLOW_LINKS);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Parses an object target {@code {path, entry, archiveChain}} (spec section 11.3). */
    private static RawTarget parseObjectTarget(Map<String, Object> map) {
        for (String key : map.keySet()) {
            if (!"path".equals(key) && !"entry".equals(key) && !"archiveChain".equals(key)) {
                throw new IllegalArgumentException("Unknown target property '" + key
                        + "'. Valid properties: path, entry, archiveChain");
            }
        }
        Object pathObj = map.get("path");
        if (pathObj == null) {
            throw new IllegalArgumentException("Target property 'path' is required");
        }
        if (!(pathObj instanceof String pathStr)) {
            throw new IllegalArgumentException("Target property 'path' must be a string");
        }
        List<String> chain = new ArrayList<>();
        Object chainObj = map.get("archiveChain");
        if (chainObj != null) {
            if (!(chainObj instanceof List<?> list)) {
                throw new IllegalArgumentException(
                        "Target property 'archiveChain' must be an array of strings");
            }
            for (Object item : list) {
                if (!(item instanceof String segment)) {
                    throw new IllegalArgumentException(
                            "Target property 'archiveChain' must be an array of strings");
                }
                chain.add(segment);
            }
        }
        Object entryObj = map.get("entry");
        if (entryObj != null) {
            if (!(entryObj instanceof String entryStr)) {
                throw new IllegalArgumentException("Target property 'entry' must be a string");
            }
            chain.add(entryStr);
        }
        return new RawTarget(pathStr, chain);
    }

    // ========================================================================
    // Engine
    // ========================================================================

    /** Runs the traversal shared by find/grep/files. */
    private static Engine run(LineMatcher matcher, Object target, Options options) {
        return run(SearchPattern.of(matcher), target, options);
    }

    /** Runs the traversal shared by find/grep/files for a {@link SearchPattern}. */
    private static Engine run(SearchPattern pattern, Object target, Options options) {
        if (pattern == null) {
            throw new IllegalArgumentException("pattern is required");
        }
        if (target == null) {
            throw new IllegalArgumentException("target is required");
        }
        // flags/caseInsensitive describe the pattern, so they are applied by the pattern owner
        // (see LineMatcher.withPatternOptions); a pattern that cannot rebuild itself keeps them.
        SearchPattern applied = pattern.withPatternOptions(options.flags, options.caseInsensitive);
        if (options.binaryBytes()) {
            // Fail before the first byte is read: a non-ASCII regex source cannot match bytes,
            // and a matcher that only reports booleans cannot report offsets (spec 18.1).
            if (!applied.supportsByteSearch()) {
                throw new IllegalArgumentException("A byte array pattern or a regular expression is"
                        + " needed for binary: \"" + BINARY_BYTES + "\" (the given pattern reports"
                        + " lines only)");
            }
            requireByteSearchable(applied);
        } else if (applied.isByteLiteral()) {
            throw new IllegalArgumentException("A byte array pattern needs binary: \"" + BINARY_BYTES
                    + "\" (a byte literal has no line semantics, see search.help())");
        }
        Engine engine = new Engine(applied, options);
        if (options.maxMatches == 0) {
            engine.truncated = true;
            engine.truncatedReason = REASON_MAX_MATCHES;
            return engine;
        }
        for (RawTarget rt : parseTargets(target)) {
            engine.process(rt);
            if (engine.stopRequested) {
                break;
            }
        }
        return engine;
    }

    /**
     * Fails fast on a pattern that cannot be used for a byte search, before any file is
     * opened: in byte search a regular expression source must be ASCII (spec 18.1). A byte
     * literal is always fine; a plain {@link LineMatcher} fails where it is used, in
     * {@link #scanOrSkipBinary(Source, boolean)}, because only there the source is known.
     * @param pattern the pattern to check
     */
    private static void requireByteSearchable(SearchPattern pattern) {
        if (pattern.kind() == SearchPattern.Kind.REGEX) {
            requireAsciiRegexSource(pattern.regexSource());
        }
    }

    /**
     * Engine state of a single search: traversal, counting and result collection.
     */
    private static final class Engine {

        private final SearchPattern pattern;
        /** Line matcher of {@link #pattern}; {@code null} for a byte literal (no lines). */
        private final LineMatcher matcher;
        private final Options opts;
        /** Matches per display path, sorted by display path. */
        private final Map<String, List<MatchRecord>> byPath = new TreeMap<>(DISPLAY_PATH_ORDER);
        private final Counts counts = new Counts();
        private final List<String> warnings = new ArrayList<>();

        private boolean truncated;
        private String truncatedReason;
        private boolean stopRequested;
        private int totalMatches;

        /** State of an explicit archive-chain lookup. */
        private boolean entryFound;
        private Source foundSource;
        /** Entry names of the deepest archive level visited so far (used by {@link #entryHint()}). */
        private final List<String> lastEntryNames = new ArrayList<>();
        /** Chain index the {@link #lastEntryNames} belong to, -1 if none was visited yet. */
        private int hintChainIndex = -1;

        Engine(SearchPattern pattern, Options opts) {
            this.pattern = pattern;
            // The line matcher is derived lazily-ish here: a byte literal has none, and asking
            // for one would throw. Text scanning is never reached in that case (spec 10.4).
            this.matcher = pattern.isByteLiteral() ? null : pattern.lineMatcher();
            this.opts = opts;
        }

        // ----------------------------------------------------------------
        // Target processing
        // ----------------------------------------------------------------

        void process(RawTarget rt) {
            Path path = JsFileSystem.resolveSafePath(rt.path);
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new JsUserRuntimeException("File not found: " + JsFileSystem.toRelative(path));
            }
            String display = JsFileSystem.toRelative(path);
            if (Files.isDirectory(path)) {
                if (!rt.chain.isEmpty()) {
                    throw new JsUserRuntimeException("Cannot search entries inside a directory: " + display);
                }
                if (!opts.recursive) {
                    throw new IllegalArgumentException(
                            "Cannot search directory without recursive: true (target: " + display + ")");
                }
                scanDirectory(display, path);
                return;
            }
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new JsUserRuntimeException("Not a regular file: " + display);
            }
            String kind = archiveKindOf(display);
            if (kind != null && !opts.archives) {
                throw new IllegalArgumentException(
                        "Cannot search archive without archives: true (target: " + display + ")");
            }
            Source src = new Source(display, 0, fileSize(path), () -> Files.newInputStream(path));
            if (rt.chain.isEmpty()) {
                openSource(src, kind, true);
                return;
            }
            navigateChain(src, rt.chain);
        }

        /** Resolves an explicit archive chain and searches the addressed entry. */
        private void navigateChain(Source src, List<String> chain) {
            String kind = archiveKindOf(src.displayPath);
            if (kind == null) {
                throw new JsUserRuntimeException("Not an archive: " + src.displayPath
                        + " (only archives have entries)");
            }
            if (chain.size() > 1 && !opts.recursiveArchives) {
                // Checked before the depth limit: without recursiveArchives the effective depth
                // is 1, so the depth check alone would report a limit the user never set.
                throw new IllegalArgumentException("Cannot search nested archive without"
                        + " recursiveArchives: true (target: "
                        + expectedDisplay(src.displayPath, chain) + ")");
            }
            int maxDepth = opts.effectiveMaxArchiveDepth();
            if (chain.size() > maxDepth) {
                throw new IllegalArgumentException("Archive depth of the target exceeds maxArchiveDepth ("
                        + maxDepth + "): " + expectedDisplay(src.displayPath, chain));
            }
            entryFound = false;
            foundSource = null;
            lastEntryNames.clear();
            hintChainIndex = -1;
            // The data of the found entry is a *view* on the archive stream that produced it, so
            // every cursor of the chain has to stay open until the entry was scanned. Closing
            // them when the lookup returns would fail the read with "Stream closed".
            Deque<EntryCursor> openCursors = new ArrayDeque<>();
            try {
                navigateStream(src, kind, chain.toArray(new String[0]), 0, openCursors);
                if (!entryFound || foundSource == null) {
                    throw new JsUserRuntimeException("Entry not found: "
                            + expectedDisplay(src.displayPath, chain)
                            + " (entries of the last archive: " + entryHint() + ")");
                }
                openSource(foundSource, archiveKindOf(foundSource.displayPath), true);
            } catch (JsUserRuntimeException | IllegalArgumentException e) {
                throw e; // user-facing errors and option/limit errors keep their own wording
            } catch (IOException | RuntimeException e) {
                throw new JsUserRuntimeException("Failed to open archive " + src.displayPath + ": "
                        + e.getMessage(), e);
            } finally {
                for (EntryCursor cursor : openCursors) {
                    closeQuietly(cursor);
                }
            }
        }

        /**
         * Streams an archive and looks for the chain segment at {@code index}; recurses into
         * the matching nested archive for the following segments.
         *
         * @param openCursors every cursor opened on the way down; closed by the caller after
         *                    the found entry has been scanned (see {@link #navigateChain})
         */
        private void navigateStream(Source src, String kind, String[] chain, int index,
                                    Deque<EntryCursor> openCursors) throws IOException {
            if (index > 0 && !opts.recursiveArchives) {
                throw new IllegalArgumentException("Cannot search nested archive without recursiveArchives: true"
                        + " (target: " + src.displayPath + ")");
            }
            if (index > hintChainIndex) {
                // The hint shall describe the deepest archive that was really opened, so the
                // names of the parent level must not be carried over.
                hintChainIndex = index;
                lastEntryNames.clear();
            }
            EntryCursor cursor = openCursor(src, kind);
            openCursors.push(cursor);
            int entriesSeen = 0;
            while (cursor.next()) {
                entriesSeen++;
                String name = cursor.name();
                rememberEntry(name);
                if (!name.equals(chain[index])) {
                    continue;
                }
                String childDisplay = src.displayPath + ARCHIVE_SEPARATOR + escapeDisplaySegment(name);
                Source child = new Source(childDisplay, src.depth + 1, cursor.size(), cursor.dataOpener());
                if (index + 1 == chain.length) {
                    entryFound = true;
                    foundSource = child;
                    return;
                }
                String childKind = archiveKindOf(name);
                if (childKind == null) {
                    throw new JsUserRuntimeException("Not an archive: " + childDisplay
                            + " (a plain file cannot contain entries)");
                }
                navigateStream(child, childKind, chain, index + 1, openCursors);
                return;
            }
            if (unreadableArchive(entriesSeen, cursor)) {
                throw new JsUserRuntimeException("Not a valid archive: " + src.displayPath
                        + " (no archive signature found)");
            }
        }

        /** Display path of a chain target (used in error messages). */
        private static String expectedDisplay(String display, List<String> chain) {
            StringBuilder sb = new StringBuilder(display);
            for (String segment : chain) {
                sb.append(ARCHIVE_SEPARATOR).append(escapeDisplaySegment(segment));
            }
            return sb.toString();
        }

        private void rememberEntry(String name) {
            if (lastEntryNames.size() < 10) {
                lastEntryNames.add(name);
            }
        }

        private String entryHint() {
            if (lastEntryNames.isEmpty()) {
                return "none";
            }
            return String.join(", ", lastEntryNames) + (lastEntryNames.size() >= 10 ? ", ..." : "");
        }

        // ----------------------------------------------------------------
        // Directory traversal
        // ----------------------------------------------------------------

        private void scanDirectory(String display, Path path) {
            if (stopRequested) {
                return;
            }
            List<Path> children = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
                for (Path child : stream) {
                    children.add(child);
                }
            } catch (IOException | DirectoryIteratorException e) {
                counts.errors++;
                addWarning("Failed to list directory " + display + ": " + e.getMessage());
                return;
            }
            children.sort(Comparator.comparing((Path p) -> p.getFileName().toString(), DISPLAY_PATH_ORDER));
            for (Path child : children) {
                if (stopRequested) {
                    return;
                }
                String childDisplay = ".".equals(display)
                        ? child.getFileName().toString() : display + "/" + child.getFileName();
                if (matchesAny(opts.exclude, childDisplay)) {
                    counts.excluded++; // an excluded directory prunes its subtree
                    continue;
                }
                if (Files.isSymbolicLink(child)) {
                    continue; // symbolic links are never followed (security, cycles)
                }
                if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    scanDirectory(childDisplay, child);
                    continue;
                }
                if (!Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                String kind = archiveKindOf(childDisplay);
                if (kind != null && !opts.archives) {
                    continue; // archives are skipped silently when archives: false
                }
                if (!includeAllows(childDisplay)) {
                    continue;
                }
                Source src = new Source(childDisplay, 0, fileSize(child), () -> Files.newInputStream(child));
                if (kind == null) {
                    scanSource(src, false);
                } else {
                    scanArchive(src, false);
                }
            }
        }

        /** Include rule: a non-empty include list requires at least one match. */
        private boolean includeAllows(String displayPath) {
            return opts.include.isEmpty() || matchesAny(opts.include, displayPath);
        }

        // ----------------------------------------------------------------
        // Archives
        // ----------------------------------------------------------------

        /**
         * Opens a source according to its kind: plain text, single-member gzip text or an
         * archive container (zip / tar / tar.gz).
         */
        private void openSource(Source src, String kind, boolean explicit) {
            if (kind == null) {
                scanSource(src, explicit);
            } else if ("gzip".equals(kind)) {
                scanSource(src.withGzip(), explicit);
            } else {
                scanArchive(src, explicit);
            }
        }

        /** Opens and searches a zip / tar / tar.gz container. */
        private void scanArchive(Source src, boolean explicit) {
            if (stopRequested) {
                return;
            }
            String kind = archiveKindOf(src.displayPath);
            if (kind == null || "gzip".equals(kind)) {
                openSource(src, kind, explicit);
                return;
            }
            if (src.depth + 1 > opts.effectiveMaxArchiveDepth()) {
                counts.skipped++;
                addWarning("Skipped nested archive " + src.displayPath + ": archive depth exceeds "
                        + opts.effectiveMaxArchiveDepth());
                return;
            }
            if (src.depth == 0 && src.knownSize > opts.maxFileBytes) {
                oversized(src, explicit, opts.maxFileBytes);
                return;
            }
            try (EntryCursor cursor = openCursor(src, kind)) {
                if (src.depth == 0) {
                    counts.archivesOpened++;
                }
                scanArchiveEntries(src, cursor);
            } catch (JsUserRuntimeException e) {
                archiveFailure(src, explicit, e);
            } catch (IOException | RuntimeException e) {
                archiveFailure(src, explicit, e);
            }
        }

        private void archiveFailure(Source src, boolean explicit, Exception e) {
            if (explicit) {
                throw new JsUserRuntimeException("Failed to open archive " + src.displayPath + ": "
                        + e.getMessage(), e);
            }
            counts.errors++;
            counts.skipped++;
            addWarning("Failed to open archive " + src.displayPath + ": " + e.getMessage());
        }

        /** Iterates the entries of an opened container. */
        private void scanArchiveEntries(Source src, EntryCursor cursor) throws IOException {
            int entriesSeen = 0;
            while (cursor.next()) {
                entriesSeen++;
                if (stopRequested) {
                    return;
                }
                String name = cursor.name();
                if (cursor.isDirectory() || cursor.isLink()) {
                    continue; // no content to search
                }
                String childDisplay = src.displayPath + ARCHIVE_SEPARATOR + escapeDisplaySegment(name);
                if (matchesAny(opts.exclude, childDisplay)) {
                    counts.excluded++;
                    continue;
                }
                if (!includeAllows(childDisplay)) {
                    continue;
                }
                Source child = new Source(childDisplay, src.depth + 1, cursor.size(), cursor.dataOpener());
                String childKind = archiveKindOf(name);
                if (childKind == null || "gzip".equals(childKind)) {
                    // Plain file or gzipped text entry: search it as text.
                    scanSource(childKind == null ? child : child.withGzip(), false);
                } else if (opts.recursiveArchives) {
                    scanArchive(child, false);
                } else {
                    counts.skipped++; // nested archives need recursiveArchives: true
                }
            }
            if (unreadableArchive(entriesSeen, cursor)) {
                // The path is added by archiveFailure() ("Failed to open archive <path>: ...").
                throw new JsUserRuntimeException("no archive signature found");
            }
        }

        /**
         * Detects a container that opened without a single entry and without an archive
         * signature (a text file named {@code .zip}). Recursive scans turn it into a warning
         * plus a counted problem, explicit targets into a hard error - both by the caller,
         * see spec sections 30 and 33.35.
         */
        private static boolean unreadableArchive(int entriesSeen, EntryCursor cursor) {
            return entriesSeen == 0 && !cursor.looksLikeArchive();
        }

        /** Opens a cursor over the entries of an archive source. */
        private static EntryCursor openCursor(Source src, String kind) throws IOException {
            InputStream raw = src.opener.open();
            try {
                if ("tar".equals(kind)) {
                    return new TarCursor(raw);
                }
                if ("targz".equals(kind)) {
                    return new TarCursor(new GZIPInputStream(new BufferedInputStream(raw)));
                }
                if ("zip".equals(kind)) {
                    return new ZipCursor(raw);
                }
                throw new JsUserRuntimeException("Unsupported archive format: " + src.displayPath);
            } catch (IOException | RuntimeException e) {
                closeQuietly(raw);
                throw e;
            }
        }

        /** Closes a stream or cursor, ignoring any problem (used in cleanup paths). */
        private static void closeQuietly(Closeable closeable) {
            try {
                closeable.close();
            } catch (IOException ignored) {
                // best effort
            }
        }

        // ----------------------------------------------------------------
        // Text sources
        // ----------------------------------------------------------------

        /**
         * Scans one virtual file - either byte-exactly ({@code binary: "bytes"}, spec 18.1) or
         * as text (lines, UTF-8, binary detection).
         */
        private void scanSource(Source src, boolean explicit) {
            if (stopRequested) {
                return;
            }
            if (opts.binaryBytes()) {
                scanBinary(src, explicit);
                return;
            }
            long maxBytes = maxBytesFor(src.depth);
            if (src.knownSize > maxBytes) {
                oversized(src, explicit, maxBytes);
                return;
            }
            try (InputStream raw = src.opener.open();
                 InputStream content = src.gzipped
                         ? new GZIPInputStream(new BufferedInputStream(raw)) : raw) {
                SniffedInputStream in = new SniffedInputStream(content);
                if (isBinary(in)) {
                    counts.skipped++;
                    if (explicit) {
                        throw new JsUserRuntimeException(
                                "Cannot search binary file as text: " + src.displayPath);
                    }
                    return;
                }
                FileScan scan = scanContent(src, in, maxBytes);
                if (scan.oversized) {
                    oversized(src, explicit, maxBytes);
                    return;
                }
                commit(src, scan);
            } catch (JsUserRuntimeException e) {
                throw e;
            } catch (IOException e) {
                if (explicit) {
                    throw new JsUserRuntimeException(
                            "Failed to read file: " + src.displayPath + " (" + e.getMessage() + ")", e);
                }
                counts.errors++;
                counts.skipped++;
                addWarning("Failed to read " + src.displayPath + ": " + e.getMessage());
            }
        }

        private void oversized(Source src, boolean explicit, long maxBytes) {
            if (explicit) {
                throw new JsUserRuntimeException("File exceeds "
                        + (src.depth > 0 ? "maxEntryBytes" : "maxFileBytes") + " (" + maxBytes
                        + " bytes" + sizeHint(maxBytes) + "): " + src.displayPath);
            }
            counts.skipped++;
            addWarning("Skipped large file " + src.displayPath + ": size exceeds "
                    + (src.depth > 0 ? "maxEntryBytes" : "maxFileBytes"));
        }

        private long maxBytesFor(int depth) {
            return depth > 0 ? opts.maxEntryBytes : opts.maxFileBytes;
        }

        // ----------------------------------------------------------------
        // Byte sources (binary: "bytes", spec 18.1)
        // ----------------------------------------------------------------

        /**
         * Scans one virtual file byte-exactly: no binary detection, no line splitting, no
         * character decoding. The data is read in windows into a reused buffer, so even a
         * 320 MiB source costs a constant amount of memory; offsets are absolute (entry-relative
         * in the decompressed stream).
         */
        private void scanBinary(Source src, boolean explicit) {
            long maxBytes = maxBytesFor(src.depth);
            if (src.knownSize > maxBytes) {
                oversized(src, explicit, maxBytes);
                return;
            }
            SpanMatcher matcher = pattern.spanMatcher();
            int preview = opts.effectivePreview(pattern.isByteLiteral());
            try (InputStream raw = src.opener.open();
                 InputStream content = src.gzipped
                         ? new GZIPInputStream(new BufferedInputStream(raw)) : raw) {
                BinaryScan scan = scanBinaryContent(content, matcher, maxBytes, preview,
                        src.displayPath, src.knownSize);
                counts.bytesScanned += scan.bytesScanned;
                if (scan.oversized) {
                    // Read past the configured limit: hard error for an explicit target, warning
                    // plus "skipped" in a recursive scan; found matches are dropped either way.
                    oversized(src, explicit, maxBytes);
                    return;
                }
                FileScan asFileScan = new FileScan();
                asFileScan.matches.addAll(scan.matches);
                commit(src, asFileScan);
                if (scan.perFileLimitCut && !truncated) {
                    // maxMatchesPerFile dropped at least one match: the result is cut, and
                    // maxMatches is the closest documented reason.
                    truncated = true;
                    truncatedReason = REASON_MAX_MATCHES;
                }
            } catch (JsUserRuntimeException e) {
                throw e;
            } catch (IOException e) {
                if (explicit) {
                    throw new JsUserRuntimeException(
                            "Failed to read file: " + src.displayPath + " (" + e.getMessage() + ")", e);
                }
                counts.errors++;
                counts.skipped++;
                addWarning("Failed to read " + src.displayPath + ": " + e.getMessage());
            }
        }

        /**
         * The window loop itself. Buffer layout of one window: first the bytes carried over from
         * the previous window (their absolute offsets start at {@code windowBase}), then the
         * freshly read data. The carry is {@code overlap + 2 * preview}, and
         * {@code overlap} is the <em>pattern length</em> for a byte literal and {@code R} for a
         * regular expression:
         * <ul>
         *   <li>a match is reported in the first window that holds it completely <em>including
         *       its preview</em>; a match that reaches the window end is deferred, because a
         *       greedy expression may extend it - the carry brings those bytes back, so nothing
         *       is reported twice and nothing is lost (as long as the match fits into the
         *       carry, which a byte literal always does and a regular expression does up to
         *       {@code R});</li>
         *   <li>{@code preview} bytes of context on both sides must survive the carry as well,
         *       so a {@code previewHex} is identical no matter where the window borders fall.</li>
         * </ul>
         */
        private BinaryScan scanBinaryContent(InputStream in, SpanMatcher matcher, long maxBytes,
                                             int preview, String display, long knownSize)
                throws IOException {
            BinaryScan out = new BinaryScan();
            long overlap = pattern.isByteLiteral()
                    ? (long) pattern.bytes().length            // exact for a byte literal
                    : BINARY_REGEX_OVERLAP_BYTES;              // R for a regex (D4)
            long carryWanted = overlap + 2L * preview;
            int bufSize = binaryBufferSize(overlap, preview);
            if (knownSize >= 0) {
                // A 4 KiB file does not need a 1 MiB buffer. Never shrink below "carry plus one
                // byte", otherwise a full window could no longer read anything and the loop
                // would never advance.
                long needed = knownSize + 1;
                if (needed > carryWanted + 1 && needed < bufSize) {
                    bufSize = (int) needed;
                }
            }
            byte[] buf = new byte[bufSize];
            long windowBase = 0;     // absolute offset of buf[0]
            int carryLen = 0;        // bytes of the previous window at the front of buf
            long prevWindowEnd = 0;  // exclusive end of the previous window
            long readLimit = maxBytes + 1; // one byte more shows that the source is too large
            while (true) {
                int filled = carryLen;
                boolean lastWindow = false;
                while (filled < buf.length && out.bytesScanned < readLimit) {
                    int budget = (int) Math.min((long) buf.length - filled, readLimit - out.bytesScanned);
                    int n = in.read(buf, filled, budget);
                    if (n < 0) {
                        lastWindow = true;
                        break;
                    }
                    filled += n;
                    out.bytesScanned += n;
                }
                if (filled <= 0) {
                    return finishBinary(out, maxBytes);
                }
                if (out.bytesScanned >= readLimit) {
                    lastWindow = true; // the limit is reached: this is the last window we scan
                }
                String window = new String(buf, 0, filled, StandardCharsets.ISO_8859_1);
                scanBinaryWindow(window, windowBase, windowBase + filled, prevWindowEnd, matcher,
                        preview, lastWindow, display, out);
                if (lastWindow) {
                    return finishBinary(out, maxBytes);
                }
                int nextCarry = (int) Math.min(carryWanted, filled);
                if (nextCarry <= 0) {
                    windowBase += filled;
                    carryLen = 0;
                } else {
                    System.arraycopy(buf, filled - nextCarry, buf, 0, nextCarry);
                    windowBase = windowBase + filled - nextCarry;
                    carryLen = nextCarry;
                }
                prevWindowEnd = windowBase + nextCarry; // = end of the window just scanned
            }
        }

        /** Turns an oversized byte scan into its final state. */
        private static BinaryScan finishBinary(BinaryScan out, long maxBytes) {
            if (out.bytesScanned > maxBytes) {
                out.oversized = true;
                out.matches.clear();
            }
            return out;
        }

        /**
         * Scans one window of the carrier string and collects the matches it can report.
         * <p>
         * The three skip rules (validated against a whole-input reference scan over every
         * window size, preview and needle position - see the model check in the plan document):
         * </p>
         * <ul>
         *   <li>a zero-length match is skipped (flood and the classic {@code lastIndex} dead
         *       end, spec 18.1);</li>
         *   <li>a match that starts inside or overlaps an already reported match is skipped -
         *       the scan continues behind that match, like {@code grep};</li>
         *   <li>a match that was already completely visible (with preview) in the previous
         *       window is skipped; a match that reached the previous window end was deferred
         *       there and is reported now.</li>
         * </ul>
         */
        private void scanBinaryWindow(String window, long windowBase, long windowEnd,
                                      long prevWindowEnd, SpanMatcher matcher, int preview,
                                      boolean lastWindow, String display, BinaryScan out) {
            int from = 0;
            Integer perFile = opts.maxMatchesPerFile;
            while (from <= window.length()) {
                MatchSpan span = matcher.find(window, from);
                if (span == null) {
                    return;
                }
                int start = span.start();
                int length = span.length();
                if (length <= 0) {
                    // Zero-length matches are skipped: they would flood the output and are the
                    // classic lastIndex dead end (spec 18.1).
                    from = start + 1;
                    continue;
                }
                long startAbs = windowBase + start;
                long endAbs = startAbs + length;
                if (startAbs < out.lastEndAbs) {
                    // Inside or overlapping an already reported match: not a new match. Continue
                    // behind that match, but never behind `start` (guaranteed progress).
                    from = (int) Math.max((long) start + 1, out.lastEndAbs - windowBase);
                    continue;
                }
                if (endAbs + preview < prevWindowEnd) {
                    // Strictly smaller: this match was already reported in an earlier window.
                    // (Equality is the deferred case - it is reported now.)
                    from = start + 1;
                    continue;
                }
                if (!lastWindow && endAbs + preview >= windowEnd) {
                    // The match reaches the window end: a greedy expression may extend it, so
                    // it is reported from the next window on - the carry brings these bytes back.
                    from = start + 1;
                    continue;
                }
                if (perFile != null && out.matches.size() >= perFile) {
                    out.perFileLimitCut = true;
                    return;
                }
                out.matches.add(MatchRecord.ofBytes(display, startAbs, start, length, window,
                        preview, opts.render));
                out.lastEndAbs = endAbs;
                from = (int) (endAbs - windowBase); // non-overlapping: continue after the match
            }
        }

        /** Matches of one byte scan plus the state the window loop needs. */
        private static final class BinaryScan {
            final List<MatchRecord> matches = new ArrayList<>(4);
            /** Bytes read from the stream (carried bytes are not counted twice). */
            long bytesScanned;
            /** End of the last reported match; keeps offsets unique and non-overlapping. */
            long lastEndAbs;
            boolean oversized;
            boolean perFileLimitCut;
        }

        /** Binary detection heuristic: NUL byte or invalid UTF-8 in the first bytes. */
        private static boolean isBinary(SniffedInputStream in) throws IOException {
            byte[] prefix = in.prefix();
            if (prefix.length == 0) {
                return false; // empty file: not binary, simply no matches
            }
            for (byte b : prefix) {
                if (b == 0) {
                    return true;
                }
            }
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            try {
                decoder.decode(ByteBuffer.wrap(prefix));
                return false;
            } catch (CharacterCodingException e) {
                return true;
            }
        }

        /** Reads the lines of one source and collects matching lines with their context. */
        private FileScan scanContent(Source src, SniffedInputStream in, long maxBytes) throws IOException {
            FileScan result = new FileScan();
            int limit = (int) Math.min(Math.max(1L, maxBytes), Integer.MAX_VALUE);
            LineSplitter lines = new LineSplitter(in, limit);
            boolean filesOnly = MODE_FILES_WITH_MATCHES.equals(opts.mode);
            // Context is collected for content and structured output; in files-with-matches
            // mode the context options are ignored (spec section 9) and scanning stops at the
            // first match per file.
            int beforeN = filesOnly ? 0 : opts.before;
            int afterN = filesOnly ? 0 : opts.after;
            Deque<LineRef> ring = new ArrayDeque<>();
            List<MatchRecord> pendingAfter = new ArrayList<>();
            String line;
            while ((line = lines.next()) != null) {
                int lineNo = lines.lineNumber();
                for (Iterator<MatchRecord> it = pendingAfter.iterator(); it.hasNext(); ) {
                    MatchRecord pending = it.next();
                    if (pending.after.size() < afterN) {
                        pending.after.add(new LineRef(lineNo, line));
                    }
                    if (pending.after.size() >= afterN) {
                        it.remove();
                    }
                }
                if (matcher.test(line)) {
                    MatchRecord m = new MatchRecord(src.displayPath, lineNo, line);
                    if (beforeN > 0 && !ring.isEmpty()) {
                        m.before.addAll(ring);
                    }
                    result.matches.add(m);
                    if (filesOnly || result.matches.size() >= opts.maxMatches) {
                        return result;
                    }
                    if (afterN > 0) {
                        pendingAfter.add(m);
                    }
                }
                if (beforeN > 0) {
                    ring.addLast(new LineRef(lineNo, line));
                    while (ring.size() > beforeN) {
                        ring.removeFirst();
                    }
                }
            }
            if (lines.oversized()) {
                // The source is larger than the configured limit: it counts as skipped, so
                // matches that were found before the limit are discarded.
                result.oversized = true;
                result.matches.clear();
            }
            return result;
        }

        /** Adds the matches of one scanned source to the result. */
        private void commit(Source src, FileScan scan) {
            counts.filesScanned++;
            if (src.depth > 0) {
                counts.archiveEntriesScanned++;
            }
            List<MatchRecord> matches = scan.matches;
            if (matches.isEmpty()) {
                return;
            }
            int room = opts.maxMatches - totalMatches;
            if (room <= 0) {
                truncated = true;
                truncatedReason = REASON_MAX_MATCHES;
                stopRequested = true;
                return;
            }
            if (matches.size() > room) {
                matches = new ArrayList<>(matches.subList(0, room));
                truncated = true;
                truncatedReason = REASON_MAX_MATCHES;
            }
            totalMatches += matches.size();
            counts.matches += matches.size();
            counts.filesMatched++;
            byPath.computeIfAbsent(src.displayPath, k -> new ArrayList<>()).addAll(matches);
            if (totalMatches >= opts.maxMatches) {
                truncated = true;
                truncatedReason = REASON_MAX_MATCHES;
                stopRequested = true;
            }
        }

        private void addWarning(String message) {
            if (warnings.size() < MAX_WARNINGS) {
                warnings.add(message);
            }
        }

        // ----------------------------------------------------------------
        // Results
        // ----------------------------------------------------------------

        /** Display paths with at least one match, sorted (spec: unique and sorted). */
        List<String> matchedPaths() {
            return new ArrayList<>(byPath.keySet());
        }

        Map<String, Object> toStructured() {
            boolean reduced = MODE_OFFSETS.equals(opts.mode);
            List<Object> matches = new ArrayList<>();
            for (List<MatchRecord> list : byPath.values()) {
                for (MatchRecord m : list) {
                    matches.add(m.byteOffset >= 0 ? byteMatchMap(m, reduced) : lineMatchMap(m));
                }
            }
            Map<String, Object> countsMap = new LinkedHashMap<>();
            countsMap.put("filesScanned", counts.filesScanned);
            countsMap.put("filesMatched", counts.filesMatched);
            countsMap.put("matches", counts.matches);
            countsMap.put("archivesOpened", counts.archivesOpened);
            countsMap.put("archiveEntriesScanned", counts.archiveEntriesScanned);
            countsMap.put("skipped", counts.skipped);
            countsMap.put("excluded", counts.excluded);
            countsMap.put("errors", counts.errors);
            countsMap.put("bytesScanned", counts.bytesScanned);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("matches", matches);
            result.put("files", matchedPaths());
            result.put("counts", countsMap);
            result.put("truncated", truncated);
            result.put("truncatedReason", truncatedReason);
            result.put("warnings", new ArrayList<Object>(warnings));
            return result;
        }

        /** Match object of a text search (spec 8.2). */
        private static Map<String, Object> lineMatchMap(MatchRecord m) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("displayPath", m.displayPath);
            map.put("line", m.line);
            map.put("text", m.text);
            map.put("before", lineRefs(m.before));
            map.put("after", lineRefs(m.after));
            return map;
        }

        /**
         * Match object of a byte search (spec 18.1); in {@code mode: "offsets"} the text and
         * preview fields are left out (spec 19.4).
         */
        private static Map<String, Object> byteMatchMap(MatchRecord m, boolean reduced) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("displayPath", m.displayPath);
            map.put("byteOffset", m.byteOffset);
            map.put("length", m.length);
            map.put("hex", m.hex);
            if (!reduced) {
                map.put("text", m.text);
            }
            map.put("line", null);   // never a line number in byte search (decision D3)
            map.put("before", lineRefs(m.before));
            map.put("after", lineRefs(m.after));
            if (!reduced && m.previewHex != null) {
                map.put("previewHex", m.previewHex);
                map.put("previewAscii", m.previewAscii);
            }
            return map;
        }

        private static List<Object> lineRefs(List<LineRef> refs) {
            List<Object> list = new ArrayList<>(refs.size());
            for (LineRef r : refs) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("line", r.line);
                map.put("text", r.text);
                list.add(map);
            }
            return list;
        }

        /**
         * Formats the result as grep-like text ({@code search.grep()}).
         * @param options the options of the current call
         * @return formatted output, possibly ending with a truncation marker
         */
        String toText(Options options) {
            StringBuilder sb = new StringBuilder();
            OutputLimiter limiter = new OutputLimiter(options.maxOutputBytes);
            if (MODE_FILES_WITH_MATCHES.equals(opts.mode)) {
                for (String path : byPath.keySet()) {
                    if (!limiter.append(sb, path)) {
                        break;
                    }
                }
                return finish(sb, limiter);
            }
            if (opts.binaryBytes()) {
                // Byte search has no lines: content, offsets and counts all come from the byte
                // formatter (spec 19.4, 19.5 and 19.6).
                return byteModeText(sb, limiter, options);
            }
            // Separator lines only make sense with context (GNU grep behaviour): without
            // context every match would be its own group and the output would be flooded.
            boolean useSeparators = options.filename && (options.before > 0 || options.after > 0);
            boolean firstPath = true;
            for (Map.Entry<String, List<MatchRecord>> pathEntry : byPath.entrySet()) {
                List<MatchRecord> records = pathEntry.getValue();
                if (records.isEmpty()) {
                    continue;
                }
                if (!firstPath && useSeparators && !limiter.append(sb, GROUP_SEPARATOR)) {
                    return finish(sb, limiter);
                }
                firstPath = false;
                // Merge the context windows of all matches of this path into one block.
                TreeMap<Integer, EmitLine> block = new TreeMap<>();
                for (MatchRecord m : records) {
                    for (LineRef ref : m.before) {
                        block.putIfAbsent(ref.line, new EmitLine(ref.line, ref.text, false));
                    }
                    block.put(m.line, new EmitLine(m.line, m.text, true));
                    for (LineRef ref : m.after) {
                        block.putIfAbsent(ref.line, new EmitLine(ref.line, ref.text, false));
                    }
                }
                int lastLine = Integer.MIN_VALUE;
                for (EmitLine e : block.values()) {
                    if (useSeparators && lastLine != Integer.MIN_VALUE && e.line > lastLine + 1) {
                        if (!limiter.append(sb, GROUP_SEPARATOR)) {
                            return finish(sb, limiter);
                        }
                    }
                    lastLine = e.line;
                    if (!limiter.append(sb, formatLine(pathEntry.getKey(), e, options))) {
                        return finish(sb, limiter);
                    }
                }
            }
            return finish(sb, limiter);
        }

        /**
         * Output of every byte-search mode: {@code path:count} (spec 19.5),
         * {@code path:0xoffset+length[  hex  |ascii|]} (spec 19.4) and the same line plus the
         * text column for content mode (spec 19.6).
         */
        private String byteModeText(StringBuilder sb, OutputLimiter limiter, Options options) {
            boolean countsOnly = MODE_COUNTS.equals(opts.mode);
            boolean offsetsOnly = MODE_OFFSETS.equals(opts.mode);
            for (Map.Entry<String, List<MatchRecord>> pathEntry : byPath.entrySet()) {
                List<MatchRecord> records = pathEntry.getValue();
                if (records.isEmpty()) {
                    continue;
                }
                if (countsOnly) {
                    if (!limiter.append(sb, pathPrefix(pathEntry.getKey()) + records.size())) {
                        return finish(sb, limiter);
                    }
                    continue;
                }
                for (MatchRecord m : records) {
                    if (!limiter.append(sb, formatByteLine(pathEntry.getKey(), m, options, offsetsOnly))) {
                        return finish(sb, limiter);
                    }
                }
            }
            return finish(sb, limiter);
        }

        /** {@code displayPath:} prefix (empty when {@code filename: false}). */
        private String pathPrefix(String displayPath) {
            return opts.filename ? displayPath + ":" : "";
        }

        /** One byte-search line (spec 19.4 and 19.6). */
        private String formatByteLine(String displayPath, MatchRecord m, Options options,
                                      boolean offsetsOnly) {
            StringBuilder sb = new StringBuilder();
            sb.append(pathPrefix(displayPath))
                    .append("0x").append(Long.toString(m.byteOffset, 16))
                    .append('+').append(m.length);
            if (!offsetsOnly) {
                sb.append("  ").append(m.text);
            }
            if (m.previewHex != null) {
                sb.append("  ").append(m.previewHex)
                        .append("  |").append(m.previewAscii).append('|');
            }
            return sb.toString();
        }

        /** One output line: {@code path:line:text} for matches, {@code path-line-text} for context. */
        private static String formatLine(String displayPath, EmitLine e, Options options) {
            StringBuilder sb = new StringBuilder();
            if (options.filename) {
                sb.append(displayPath).append(e.isMatch ? ':' : '-');
            }
            if (options.lineNumbers) {
                sb.append(e.line).append(e.isMatch ? ':' : '-');
            }
            sb.append(e.text);
            return sb.toString();
        }

        /** Completes the output, appending the truncation marker if needed. */
        private String finish(StringBuilder sb, OutputLimiter limiter) {
            String out = sb.toString();
            String reason = limiter.truncated ? REASON_MAX_OUTPUT_BYTES : (truncated ? truncatedReason : null);
            if (reason == null) {
                return out;
            }
            if (!out.isEmpty() && !out.endsWith("\n")) {
                out = out + "\n";
            }
            return out + TRUNCATION_MARKER_PREFIX + reason;
        }
    }

    // ========================================================================
    // Entry cursors (ZIP / tar)
    // ========================================================================

    /**
     * Sequential cursor over the entries of an archive. {@link #dataOpener()} returns an
     * opener for the data of the current entry, valid until the next {@link #next()} call
     * (which skips whatever was not read).
     */
    private interface EntryCursor extends Closeable {
        /** @return true if another entry was read */
        boolean next() throws IOException;

        /** @return entry name as stored in the archive */
        String name();

        /** @return declared entry size in bytes, -1 if unknown */
        long size();

        /** @return true for directory entries */
        boolean isDirectory();

        /** @return true for symbolic or hard link entries */
        boolean isLink();

        /** @return opener for the data of the current entry */
        StreamOpener dataOpener();

        /**
         * Tells whether the opened stream looks like an archive at all.
         * <p>
         * {@link ZipInputStream} does not complain about a foreign file - it reports the end of
         * the archive at once - so a text file named {@code .zip} would be "searched" silently
         * without a single entry and without a warning (spec section 33.35 requires one). Such
         * cursors return {@code false} here and the caller reports the problem.
         * </p>
         * @return false if the content has no archive signature (default: true)
         */
        default boolean looksLikeArchive() {
            return true;
        }
    }

    /** ZIP cursor: streams entries with {@link ZipInputStream} (nothing is extracted). */
    private static final class ZipCursor implements EntryCursor {

        private final ZipInputStream zis;
        /** False if the stream does not start with a ZIP signature ("PK.."). */
        private final boolean zipSignature;
        private ZipEntry entry;

        ZipCursor(InputStream in) throws IOException {
            PushbackInputStream pin = new PushbackInputStream(in, 4);
            byte[] head = new byte[4];
            int read = readUpTo(pin, head);
            // 'PK\003\004' (first local header), 'PK\005\006' (empty archive) and
            // 'PK\007\008' (streamed data descriptor) all start with "PK". SFX archives and
            // other prefixed containers are out of scope (recognition is by extension anyway).
            this.zipSignature = read >= 2 && head[0] == 'P' && head[1] == 'K';
            if (read > 0) {
                pin.unread(head, 0, read);
            }
            this.zis = new ZipInputStream(new BufferedInputStream(pin));
        }

        /** Fills {@code buf} completely or stops at end of stream. */
        private static int readUpTo(InputStream in, byte[] buf) throws IOException {
            int off = 0;
            while (off < buf.length) {
                int r = in.read(buf, off, buf.length - off);
                if (r < 0) {
                    break;
                }
                off += r;
            }
            return off;
        }

        @Override
        public boolean looksLikeArchive() {
            return zipSignature;
        }

        @Override
        public boolean next() throws IOException {
            entry = zis.getNextEntry();
            return entry != null;
        }

        @Override
        public String name() {
            return entry.getName();
        }

        @Override
        public long size() {
            return entry.getSize(); // -1 if the archive does not declare it
        }

        @Override
        public boolean isDirectory() {
            return entry.isDirectory();
        }

        @Override
        public boolean isLink() {
            // UNIX symlinks store their target as entry content; that content is searched as
            // text, which is harmless and never touches the file system.
            return false;
        }

        @Override
        public StreamOpener dataOpener() {
            return () -> new FilterInputStream(zis) {
                @Override
                public void close() {
                    // Ending an entry is done by getNextEntry(); the archive stays open.
                }
            };
        }

        @Override
        public void close() throws IOException {
            zis.close();
        }
    }

    /** tar cursor over a {@link JsSearchTar} stream. */
    private static final class TarCursor implements EntryCursor {

        private final InputStream in;
        private final JsSearchTar tar;

        TarCursor(InputStream in) {
            this.in = in;
            this.tar = new JsSearchTar(in);
        }

        @Override
        public boolean next() throws IOException {
            return tar.nextEntry();
        }

        @Override
        public String name() {
            return tar.name();
        }

        @Override
        public long size() {
            return tar.size();
        }

        @Override
        public boolean isDirectory() {
            return tar.isDirectory();
        }

        @Override
        public boolean isLink() {
            return tar.isLink();
        }

        @Override
        public StreamOpener dataOpener() {
            return () -> new FilterInputStream(tar.data()) {
                @Override
                public void close() {
                    // nextEntry() skips the rest; the tar stream stays open.
                }
            };
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    /** Opens the content stream of a source (may fail with IOException). */
    private interface StreamOpener {
        InputStream open() throws IOException;
    }

    // ========================================================================
    // Result data types
    // ========================================================================

    /**
     * One match: a matching line (text search) or a byte range (byte search, spec 18.1).
     * The two modes share the record so that sorting, truncation and the counts stay one code
     * path; {@code line} is {@code -1} and {@code byteOffset} {@code >= 0} in byte search.
     */
    private static final class MatchRecord {
        final String displayPath;
        /** 1-based line number in text mode, -1 in byte search (where {@code line} is null). */
        final int line;
        /** Text mode: the matched line. Byte search: escaped bytes, or hex (spec 18.2). */
        final String text;
        /** 0-based byte offset in the (decompressed) source, -1 in text mode. */
        final long byteOffset;
        /** Matched bytes in byte search, 0 in text mode. */
        final int length;
        /** Matched bytes as canonical lowercase hex (byte search), else {@code null}. */
        final String hex;
        /** Hex of the preview range (byte search with {@code preview > 0}), else {@code null}. */
        final String previewHex;
        /** One character per byte of {@link #previewHex}, non-printable as {@code '.'}. */
        final String previewAscii;
        final List<LineRef> before = new ArrayList<>(2);
        final List<LineRef> after = new ArrayList<>(2);

        MatchRecord(String displayPath, int line, String text) {
            this(displayPath, line, text, -1L, 0, null, null, null);
        }

        private MatchRecord(String displayPath, int line, String text, long byteOffset, int length,
                            String hex, String previewHex, String previewAscii) {
            this.displayPath = displayPath;
            this.line = line;
            this.text = text;
            this.byteOffset = byteOffset;
            this.length = length;
            this.hex = hex;
            this.previewHex = previewHex;
            this.previewAscii = previewAscii;
        }

        /**
         * A byte-search match. All representations are derived here, from the carrier window,
         * so escaping rules (spec 18.2) live in exactly one place.
         *
         * @param displayPath display path of the source
         * @param byteOffset  absolute (entry-relative) offset of the match
         * @param matchStart  window-relative index of the match inside {@code carrier}
         * @param length      match length in bytes
         * @param carrier     the window (one character per byte)
         * @param preview     requested context bytes on each side; {@code 0} means no preview
         *                    fields at all - the match itself is never mistaken for one (D5)
         * @param render      {@code "escaped"} or {@code "hex"}
         * @return the record
         */
        static MatchRecord ofBytes(String displayPath, long byteOffset, int matchStart, int length,
                                   String carrier, int preview, String render) {
            String hex = hexOf(carrier, matchStart, matchStart + length);
            String text = RENDER_HEX.equals(render) ? hex : escapeBytes(carrier, matchStart,
                    matchStart + length);
            String previewHex = null;
            String previewAscii = null;
            if (preview > 0) {
                int previewStart = Math.max(0, matchStart - preview);
                int previewEnd = Math.min(carrier.length(), matchStart + length + preview);
                previewHex = hexOf(carrier, previewStart, previewEnd);
                previewAscii = asciiSidebar(carrier, previewStart, previewEnd);
            }
            return new MatchRecord(displayPath, -1, text, byteOffset, length, hex,
                    previewHex, previewAscii);
        }
    }

    // ========================================================================
    // Byte representation (spec 18.2)
    // ========================================================================

    /**
     * Canonical lowercase hex of a range of the carrier, like {@code fs.readHex}.
     * @param carrier one character per byte
     * @param from start index
     * @param to exclusive end index
     * @return hex string (two digits per byte)
     */
    static String hexOf(CharSequence carrier, int from, int to) {
        StringBuilder sb = new StringBuilder(Math.max(0, to - from) * 2);
        for (int i = from; i < to; i++) {
            int b = carrier.charAt(i) & 0xFF;
            sb.append(HEX_DIGITS.charAt(b >>> 4)).append(HEX_DIGITS.charAt(b & 0xF));
        }
        return sb.toString();
    }

    /**
     * Lossless, injection-preserving ASCII rendering of bytes (spec 18.2): only
     * {@code 0x20..0x7E} appear unchanged, {@code \n}, {@code \r} and {@code \t} keep their
     * names, everything else becomes {@code \xnn} with lowercase digits, and a backslash is
     * doubled. Doubling is what makes the mapping injective - the four bytes {@code 5c 78 66 66}
     * (the text {@code \xff}) must not look like the single byte {@code 0xff}.
     *
     * @param carrier one character per byte
     * @param from start index
     * @param to exclusive end index
     * @return pure ASCII representation
     */
    static String escapeBytes(CharSequence carrier, int from, int to) {
        StringBuilder sb = new StringBuilder(Math.max(0, to - from) + 8);
        for (int i = from; i < to; i++) {
            int b = carrier.charAt(i) & 0xFF;
            switch (b) {
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\\' -> sb.append("\\\\");
                default -> {
                    if (b >= 0x20 && b <= 0x7E) {
                        sb.append((char) b);
                    } else {
                        sb.append("\\x").append(HEX_DIGITS.charAt(b >>> 4))
                                .append(HEX_DIGITS.charAt(b & 0xF));
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * Display-only sidebar of a preview: one character per byte, {@code '.'} for anything that
     * is not printable ASCII. It is never the authoritative column - the hex next to it is.
     *
     * @param carrier one character per byte
     * @param from start index
     * @param to exclusive end index
     * @return the sidebar text (without the surrounding {@code |})
     */
    static String asciiSidebar(CharSequence carrier, int from, int to) {
        StringBuilder sb = new StringBuilder(Math.max(0, to - from));
        for (int i = from; i < to; i++) {
            int b = carrier.charAt(i) & 0xFF;
            sb.append(b >= 0x20 && b <= 0x7E ? (char) b : '.');
        }
        return sb.toString();
    }

    /** The reverse of {@link #escapeBytes(CharSequence, int, int)} - used by the round-trip test. */
    public static byte[] unescapeBytes(String escaped) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(escaped.length());
        for (int i = 0; i < escaped.length(); i++) {
            char c = escaped.charAt(i);
            if (c != '\\' || i + 1 >= escaped.length()) {
                out.write(c);
                continue;
            }
            char n = escaped.charAt(++i);
            switch (n) {
                case 'n' -> out.write('\n');
                case 'r' -> out.write('\r');
                case 't' -> out.write('\t');
                case '\\' -> out.write('\\');
                case 'x' -> {
                    if (i + 2 >= escaped.length()) {
                        out.write('\\');
                        i -= 1;
                    } else {
                        int hi = Character.digit(escaped.charAt(i + 1), 16);
                        int lo = Character.digit(escaped.charAt(i + 2), 16);
                        if (hi < 0 || lo < 0) {
                            out.write('\\');
                            i -= 1;
                        } else {
                            out.write((hi << 4) | lo);
                            i += 2;
                        }
                    }
                }
                default -> out.write(n);
            }
        }
        return out.toByteArray();
    }

    /** One context line (1-based line number and text). */
    private static final class LineRef {
        final int line;
        final String text;

        LineRef(int line, String text) {
            this.line = line;
            this.text = text;
        }
    }

    /** One line to emit in content output. */
    private static final class EmitLine {
        final int line;
        final String text;
        final boolean isMatch;

        EmitLine(int line, String text, boolean isMatch) {
            this.line = line;
            this.text = text;
            this.isMatch = isMatch;
        }
    }

    /** Counters of {@code find().counts}. */
    private static final class Counts {
        int filesScanned;
        int filesMatched;
        int matches;
        int archivesOpened;
        int archiveEntriesScanned;
        int skipped;
        int excluded;
        int errors;
        /** Bytes read by the byte search (spec 18.1); 0 in text mode. */
        long bytesScanned;
    }

    /** Matches of one scanned source. */
    private static final class FileScan {
        final List<MatchRecord> matches = new ArrayList<>(4);
        boolean oversized;
    }

    /** A searchable source: a plain file or an archive entry, addressed by display path. */
    private static final class Source {
        final String displayPath;
        /** 0 = plain file, 1 = entry of a top-level archive, 2 = entry in a nested archive. */
        final int depth;
        /** Declared size in bytes, -1 if unknown. */
        final long knownSize;
        /** True if the content is a single-member gzip stream (e.g. {@code log.txt.gz}). */
        final boolean gzipped;
        final StreamOpener opener;

        Source(String displayPath, int depth, long knownSize, StreamOpener opener) {
            this(displayPath, depth, knownSize, opener, false);
        }

        Source(String displayPath, int depth, long knownSize, StreamOpener opener, boolean gzipped) {
            this.displayPath = displayPath;
            this.depth = depth;
            this.knownSize = knownSize;
            this.opener = opener;
            this.gzipped = gzipped;
        }

        /** Copy with the gzip flag set (searched as decompressed text). */
        Source withGzip() {
            return new Source(displayPath, depth, knownSize, opener, true);
        }
    }

    // ========================================================================
    // Stream helpers
    // ========================================================================

    /**
     * Streams a file or entry while counting bytes; the first {@link #BINARY_SNIFF_BYTES}
     * bytes are buffered so that binary detection and text decoding see the same data.
     */
    private static final class SniffedInputStream extends InputStream {

        private final InputStream delegate;
        private final byte[] first = new byte[BINARY_SNIFF_BYTES];
        private int firstLen = -1;
        private int firstPos;
        private long beyondFirst;

        SniffedInputStream(InputStream in) {
            this.delegate = in;
        }

        /** Reads (once) the bytes used for binary detection. */
        byte[] prefix() throws IOException {
            if (firstLen < 0) {
                int off = 0;
                while (off < BINARY_SNIFF_BYTES) {
                    int n = delegate.read(first, off, BINARY_SNIFF_BYTES - off);
                    if (n < 0) {
                        break;
                    }
                    off += n;
                }
                firstLen = off;
            }
            return Arrays.copyOf(first, firstLen);
        }

        @Override
        public int read() throws IOException {
            if (firstPos < firstLen) {
                return first[firstPos++] & 0xFF;
            }
            int v = delegate.read();
            if (v >= 0) {
                beyondFirst++;
            }
            return v;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            if (len <= 0) {
                return 0;
            }
            if (firstPos < firstLen) {
                int n = Math.min(len, firstLen - firstPos);
                System.arraycopy(first, firstPos, buf, off, n);
                firstPos += n;
                return n;
            }
            int n = delegate.read(buf, off, len);
            if (n > 0) {
                beyondFirst += n;
            }
            return n;
        }

        /** Total number of bytes taken from the underlying source. */
        long count() {
            return Math.max(0, firstLen) + beyondFirst;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    /**
     * Splits a byte stream into lines: UTF-8, separators LF, CRLF and CR, 1-based numbering.
     * The last line without a trailing separator is still returned; empty input has no lines.
     */
    private static final class LineSplitter {

        private final Reader reader;
        private final SniffedInputStream source;
        private final int maxBytes;
        private final char[] cbuf = new char[8192];
        private final StringBuilder current = new StringBuilder();
        private int bufLen;
        private int bufPos;
        private boolean pendingCR;
        private boolean eof;
        private boolean oversized;
        private int lineNumber;
        private String ready;

        LineSplitter(SniffedInputStream source, int maxBytes) {
            this.source = source;
            this.maxBytes = maxBytes;
            this.reader = new InputStreamReader(source, StandardCharsets.UTF_8);
        }

        /** @return the next line, or null at end of input */
        String next() throws IOException {
            while (true) {
                if (ready != null) {
                    String line = ready;
                    ready = null;
                    lineNumber++;
                    return line;
                }
                if (oversized) {
                    return null;
                }
                if (bufPos >= bufLen) {
                    if (eof) {
                        if (current.length() > 0 || pendingCR) {
                            pendingCR = false;
                            ready = takeLine();
                        } else {
                            return null;
                        }
                        continue;
                    }
                    int n = reader.read(cbuf);
                    if (n < 0) {
                        eof = true;
                    } else if (n > 0) {
                        bufLen = n;
                        bufPos = 0;
                        if (source.count() > maxBytes) {
                            oversized = true;
                        }
                    }
                    continue;
                }
                char c = cbuf[bufPos];
                if (pendingCR) {
                    pendingCR = false;
                    if (c == '\n') {
                        bufPos++; // second half of a CRLF pair
                    }
                    ready = takeLine(); // the CR (or CRLF) terminated the line
                    continue;
                }
                if (c == '\n') {
                    bufPos++;
                    ready = takeLine();
                    continue;
                }
                if (c == '\r') {
                    pendingCR = true;
                    bufPos++;
                    continue;
                }
                current.append(c);
                bufPos++;
            }
        }

        private String takeLine() {
            String line = current.toString();
            current.setLength(0);
            return line;
        }

        /** @return true if the size limit was exceeded while reading */
        boolean oversized() {
            return oversized;
        }

        /** @return 1-based number of the line returned by the last {@link #next()} call */
        int lineNumber() {
            return lineNumber;
        }
    }

    /**
     * Collects output lines while enforcing {@code maxOutputBytes}. Once the limit is
     * reached, further lines are dropped and {@link #truncated} is set.
     */
    private static final class OutputLimiter {

        private final int maxBytes;
        private int bytes;
        private boolean truncated;

        OutputLimiter(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        /** @return false if the line did not fit (output was truncated) */
        boolean append(StringBuilder sb, String line) {
            int lineBytes = utf8Length(line) + 1;
            if (bytes + lineBytes > maxBytes) {
                truncated = true;
                return false;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
            bytes += lineBytes;
            return true;
        }

        /** UTF-8 length of a string without allocating a byte array. */
        private static int utf8Length(String s) {
            int n = 0;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c < 0x80) {
                    n++;
                } else if (c < 0x800) {
                    n += 2;
                } else if (Character.isHighSurrogate(c) && i + 1 < s.length()
                        && Character.isLowSurrogate(s.charAt(i + 1))) {
                    n += 4;
                    i++;
                } else {
                    n += 3;
                }
            }
            return n;
        }
    }

    // ========================================================================
    // Help text
    // ========================================================================

    /**
     * Buffer size of one byte-scan window: {@link #BINARY_WINDOW_BYTES} bytes of data plus the
     * carried bytes in front of them ({@code overlap + 2 * preview}, see the window loop in
     * {@code Engine}). Package private because the window-boundary tests are built around
     * exactly this geometry instead of guessing it.
     *
     * @param overlap bytes that must be carried so that the pattern can match across a border
     * @param preview requested context bytes on each side of a match
     * @return the buffer size in bytes
     */
    static int binaryBufferSize(long overlap, int preview) {
        long wanted = (long) BINARY_WINDOW_BYTES + Math.max(0L, overlap)
                + 2L * Math.max(0, preview);
        return (int) Math.min(Math.max((long) BINARY_WINDOW_BYTES + 1L, wanted),
                BINARY_BUFFER_HARD_CAP);
    }

    /**
     * Formats a byte count the way the help text and size errors state it, e.g.
     * {@code 335544320 -> "320 MiB"}. Keeps numbers and units in sync (single source: the
     * {@code DEFAULT_*_BYTES} constants).
     *
     * @param bytes byte count
     * @return human readable size, e.g. {@code "320 MiB"}
     */
    static String mebibytes(long bytes) {
        return (bytes / MEBIBYTE) + " MiB";
    }

    /**
     * Human readable hint for a limit value, e.g. {@code " (320 MiB)"}. Empty for values that
     * are not a whole number of mebibytes (a caller-chosen {@code maxFileBytes: 100} must not
     * be described as {@code "0 MiB"}).
     *
     * @param bytes byte count
     * @return the hint including the leading space, or an empty string
     */
    static String sizeHint(long bytes) {
        return bytes > 0 && bytes % MEBIBYTE == 0 ? " (" + mebibytes(bytes) + ")" : "";
    }

    /**
     * Returns the help text of the search module.
     * @return help text
     */
    public static String help() {
        return HELP;
    }

    private static final String HELP = String.join("\n",
            "JS search module (namespace 'search')",
            "===================================",
            "",
            "Grep-like search over the controlled project file system, including archives.",
            "Archive entries are virtual: display paths use '#' between archive levels, e.g.",
            "    config/app.properties",
            "    app.jar#application.yml",
            "    example.ear#admin.war#WEB-INF/web.xml",
            "    backup/logs.tar.gz#service.log",
            "Entry names containing '#' or '%' are escaped as %23 / %25 in display paths.",
            "Nothing is ever extracted to disk.",
            "",
            "--- API ---",
            "search.help()                              - this text",
            "search.grep(pattern, target[, options])    - formatted text output (String)",
            "search.find(pattern, target[, options])    - structured result (Object)",
            "search.files(pattern, target[, options])   - array of display paths with matches",
            "search.hexgrep(hex, target[, options])     - hex pattern such as \"4d5a . 9000\"",
            "",
            "--- Patterns ---",
            "pattern is a string (JavaScript regular expression source), a RegExp object or a",
            "byte array (Uint8Array / number[] 0-255, byte search only). Matching in text mode is",
            "line by line with JavaScript RegExp semantics; 'g'/'y' are dropped internally. An",
            "empty pattern throws. Hex is never guessed from a string - use search.hexgrep().",
            "    search.grep(\"TODO\", \"src\", { recursive: true })",
            "    search.grep(/password|secret/i, \"config\", { recursive: true, archives: true })",
            "    search.grep(new Uint8Array([0x4d,0x5a]), \"boot.bin\", { binary: \"bytes\" })",
            "",
            "--- Targets ---",
            "target is a path, an archive display path, an array of targets or an object:",
            "    \"README.md\"",
            "    \"src\"                                  - directory, needs recursive: true",
            "    \"libs/app.jar#application.yml\"         - entry inside an archive",
            "    [\"README.md\", \"src\", \"libs/app.jar\"]   - every element is searched",
            "    { path: \"example.ear\", archiveChain: [\"admin.war\"],",
            "      entry: \"WEB-INF/web.xml\" }           - raw entry names, no escaping needed",
            "Missing targets throw; directories need recursive: true, archives archives: true.",
            "",
            "--- Options (strict: unknown options are rejected) ---",
            "Scope:      recursive=false",
            "Output:     mode=\"content\"|\"filesWithMatches\"|\"offsets\"|\"counts\" (grep);",
            "            \"structured\"|\"offsets\" (find); \"filesWithMatches\" (files);",
            "            filename=true, lineNumbers=true",
            "Context:    before=0, after=0, context=0 (aliases B, A, C; conflicts are errors)",
            "Pattern:    flags=\"\" (any of " + VALID_REGEX_FLAGS + "), caseInsensitive=false",
            "Text:       encoding=\"UTF-8\" (only), binary=\"skip\"|\"bytes\"",
            "Byte scan:  preview=" + MAX_PREVIEW_BYTES + " max (0 for a byte literal, "
                    + DEFAULT_PREVIEW_REGEX + " for a regex), render=\"escaped\"|\"hex\"",
            "Filtering:  include=[], exclude=[] (arrays of glob strings or RegExp objects)",
            "Archives:   archives=false, recursiveArchives=false, maxArchiveDepth=null",
            "Limits:     maxMatches=" + DEFAULT_MAX_MATCHES + ", maxMatchesPerFile=null (byte scan),",
            "            maxFileBytes=" + DEFAULT_MAX_FILE_BYTES + " ("
                    + mebibytes(DEFAULT_MAX_FILE_BYTES) + "),",
            "            maxEntryBytes=" + DEFAULT_MAX_ENTRY_BYTES + " ("
                    + mebibytes(DEFAULT_MAX_ENTRY_BYTES) + "), maxOutputBytes=" + DEFAULT_MAX_OUTPUT_BYTES,
            "",
            "Content output: 'displayPath:line:text' for matches, 'displayPath-line-text' for",
            "context lines; filename:false drops the path, lineNumbers:false the number. With",
            "context, overlapping groups are merged and separated by a '--' line. String output",
            "is cut at maxOutputBytes and ends with a '-- truncated by search limit: ...' marker.",
            "",
            "--- Byte search (binary: \"bytes\") ---",
            "No binary detection, no lines, no decoding: the stream (a file, an archive entry or",
            "a gzipped entry) is scanned byte-exactly in windows of " + BINARY_WINDOW_BYTES / 1024 + " KiB, offsets are",
            "entry-relative in the DECOMPRESSED stream. lineNumbers/before/after/context/B/A/C",
            "are rejected; 'preview' is the byte context instead.",
            "A byte array pattern is exact; a regex runs over a byte carrier ('g' is added,",
            "source must be ASCII, \\xNN escapes are byte values, with 's' a dot also",
            "matches 0x0A). Matches never overlap, zero-length matches are skipped. A regex",
            "match longer than " + BINARY_REGEX_OVERLAP_BYTES / 1024 + " KiB across a window boundary may be missed.",
            "Output is always ASCII: printable bytes as themselves, \\n \\r \\t by name, a",
            "backslash doubled, everything else \\xnn (lowercase). Modes: content shows",
            "'path:0xoffset+length  text', mode:\"offsets\" shows 'path:0xoffset+length'",
            "(plus '  hex  |ascii|' when preview > 0), mode:\"counts\" shows 'path:count'.",
            "    search.grep([0x68,0x00,0x61], \"utf16.txt\", { binary: \"bytes\", preview: 8 })",
            "    search.hexgrep(\"4d5a .. 9000\", \"image.bin\", { mode: \"counts\" })",
            "",
            "--- Globs (include/exclude strings) ---",
            "* (never matches /), ? (one character, never /), [abc], [a-z], [!a-z],",
            "** (anything, including /); '**&#47;' matches zero or more path segments.",
            "A glob without '/' is matched against the basename, a glob with '/' against the",
            "path and against archive-local suffixes, so \"WEB-INF/*.xml\" also matches inside",
            "archives. Globs are NOT JavaScript regular expressions - pass a RegExp for regexes.",
            "exclude wins over include; excluded directories are not traversed.",
            "    exclude: [\"*.dat\", \"target/**\", /(^|\\/)node_modules(\\/|$)/]",
            "",
            "--- Structured result (search.find) ---",
            "{ matches: [{ displayPath, line, text, before: [{line,text}], after: [...] }],",
            "  files: [displayPath],",
            "  counts: { filesScanned, filesMatched, matches, archivesOpened,",
            "            archiveEntriesScanned, skipped, excluded, errors, bytesScanned },",
            "  truncated: boolean, truncatedReason: \"maxMatches\"|null,",
            "  warnings: [string] }",
            "Paths are sorted by Unicode code point, line numbers are 1-based.",
            "Byte search instead: { displayPath, byteOffset, length, hex, text, line: null,",
            "before: [], after: [] } plus previewHex/previewAscii when preview > 0; with",
            "mode:\"offsets\" the match keeps only displayPath, byteOffset, length, hex, line.",
            "",
            "--- Archives ---",
            "Recognized by extension: .zip .jar .war .ear .aar .apk .xlsx .docx .pptx",
            ".tar .tar.gz .tgz .gz (.gz is searched as text after gunzip, .tar.gz/.tgz as tar).",
            "Archives inside archives need recursiveArchives: true (maxArchiveDepth, default 8).",
            "",
            "--- Example ---",
            "var r = search.find(/TODO|FIXME/i, \"src\", {",
            "    recursive: true, before: 2, after: 2, exclude: [\"generated\"] });",
            "console.log(r.counts, r.files);",
            "for (var m of r.matches) {",
            "    console.log(m.displayPath + \":\" + m.line + \": \" + m.text);",
            "}",
            "",
            "--- Limitations ---",
            "- text mode decodes UTF-8 only; a file is binary when the first " + BINARY_SNIFF_BYTES + " bytes",
            "  contain NUL or invalid UTF-8. binary:\"skip\" (default) skips it - an explicit",
            "  binary or oversized target throws - and binary:\"bytes\" searches it exactly,",
            "- byte search: regex source must be ASCII, characters above 0xFF never match, a regex",
            "  match longer than " + BINARY_REGEX_OVERLAP_BYTES / 1024 + " KiB over a window boundary may be missed,",
            "- UTF-16 text is bytes, not characters: search \"h\\x00a\\x00l\\x00l\\x00o\\x00\", the",
            "  bytes 68 00 61 00 6c 00 6c 00 6f 00, or search.hexgrep(\"680061006c006c006f00\");",
            "  a comfortable 'encoding' for text search is planned, not built,",
            "- no invertMatch / wholeWord / multiline; maxMatchesPerFile works in byte search only,",
            "- symbolic links are never followed (such files and directories are skipped),",
            "- link entries in archives: tar links are skipped, ZIP symlink entries are searched",
            "  as text (their content is the link target; the file system is never touched),",
            "- archives are recognized by extension only (an extensionless ZIP is plain text),",
            "- no 7z / rar / xz support, no file-type filters (like -t java),",
            "- recursive scanning never stops for a single bad file: problems are counted in",
            "  counts.skipped / counts.errors and reported in warnings (max " + MAX_WARNINGS + " messages).",
            "",
            "See docs/js/search.md for the full specification.");
}
