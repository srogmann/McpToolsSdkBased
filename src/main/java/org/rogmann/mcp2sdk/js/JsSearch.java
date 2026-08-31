package org.rogmann.mcp2sdk.js;

import java.io.BufferedInputStream;
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
 * Content matching uses JavaScript {@code RegExp} semantics: the caller (see
 * {@link JsSearchBridge}) provides a {@link LineMatcher} backed by a real JS regular
 * expression, so pattern behaviour is what a JavaScript author expects, not Java's.
 * </p>
 * <p>
 * The Java API is polyglot-free ({@link LineMatcher}, {@link PathFilter}, plain option maps),
 * which keeps the engine unit-testable without a JavaScript context.
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

    /** Default limit for the number of collected matches. */
    public static final int DEFAULT_MAX_MATCHES = 2000;
    /** Default size limit for a plain file in bytes (320 MiB). */
    public static final long DEFAULT_MAX_FILE_BYTES = 320 * 1048576L;
    /** Default size limit for an archive entry in bytes (320 MiB). */
    public static final long DEFAULT_MAX_ENTRY_BYTES = 320 * 1048576L;
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

    /** Valid JavaScript regular expression flags (also accepted by the {@code flags} option). */
    private static final String VALID_REGEX_FLAGS = "dgimsuvy";

    /** All recognized option names (strict: unknown options are rejected). */
    private static final Set<String> OPTION_NAMES = new LinkedHashSet<>(List.of(
            "recursive", "mode", "filename", "lineNumbers",
            "before", "after", "context", "B", "A", "C",
            "flags", "caseInsensitive", "encoding", "binary",
            "include", "exclude",
            "archives", "recursiveArchives", "maxArchiveDepth",
            "maxMatches", "maxFileBytes", "maxEntryBytes", "maxOutputBytes"));

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
        String binary = "skip";
        List<PathFilter> include = List.of();
        List<PathFilter> exclude = List.of();
        boolean archives;
        boolean recursiveArchives;
        Integer maxArchiveDepth;
        int maxMatches = DEFAULT_MAX_MATCHES;
        long maxFileBytes = DEFAULT_MAX_FILE_BYTES;
        long maxEntryBytes = DEFAULT_MAX_ENTRY_BYTES;
        int maxOutputBytes = DEFAULT_MAX_OUTPUT_BYTES;

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
                    if (!"skip".equals(bin)) {
                        throw new IllegalArgumentException(
                                "Option 'binary' only supports 'skip' (got '" + bin + "')");
                    }
                    o.binary = bin;
                }
                case "include" -> o.include = toFilters(name, v, o.caseInsensitive);
                case "exclude" -> o.exclude = toFilters(name, v, o.caseInsensitive);
                case "archives" -> o.archives = toBool(name, v);
                case "recursiveArchives" -> o.recursiveArchives = toBool(name, v);
                case "maxArchiveDepth" -> o.maxArchiveDepth = toNullablePositiveInt(name, v);
                case "maxMatches" -> o.maxMatches = toNonNegativeInt(name, v);
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
        o.before = resolveContext(raw, "before", "B");
        o.after = resolveContext(raw, "after", "A");
        if (o.recursiveArchives && !o.archives) {
            throw new IllegalArgumentException("recursiveArchives requires archives: true");
        }
        parseMode(raw.get("mode"), modeContext, o);
        return o;
    }

    /** Mode validation per entry point (spec section 19.3). */
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
            if (!MODE_CONTENT.equals(s) && !MODE_FILES_WITH_MATCHES.equals(s)) {
                throw new IllegalArgumentException("search.grep() supports mode '" + MODE_CONTENT + "' or '"
                        + MODE_FILES_WITH_MATCHES + "' only");
            }
        } else if ("search.find".equals(context)) {
            if (!MODE_STRUCTURED.equals(s)) {
                throw new IllegalArgumentException("search.find() supports mode '" + MODE_STRUCTURED + "' only");
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
        Options options = parseOptions(rawOptions, "search.find");
        return run(matcher, target, options).toStructured();
    }

    /**
     * Runs a search and returns grep-like text ({@code search.grep()}).
     * @param matcher line matcher
     * @param target target, see {@link #find(LineMatcher, Object, Map)}
     * @param rawOptions raw option map, may be null
     * @return formatted output, possibly ending with a truncation marker
     */
    public static String grep(LineMatcher matcher, Object target, Map<String, Object> rawOptions) {
        Options options = parseOptions(rawOptions, "search.grep");
        Engine engine = run(matcher, target, options);
        return engine.toText(options);
    }

    /**
     * Runs a search and returns the matching display paths ({@code search.files()}).
     * @param matcher line matcher
     * @param target target, see {@link #find(LineMatcher, Object, Map)}
     * @param rawOptions raw option map, may be null
     * @return unique, sorted display paths
     */
    public static List<String> files(LineMatcher matcher, Object target, Map<String, Object> rawOptions) {
        Options options = parseOptions(rawOptions, "search.files");
        return run(matcher, target, options).matchedPaths();
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
        if (matcher == null) {
            throw new IllegalArgumentException("pattern is required");
        }
        if (target == null) {
            throw new IllegalArgumentException("target is required");
        }
        // flags/caseInsensitive describe the pattern, so they are applied by the pattern owner
        // (see LineMatcher.withPatternOptions); a matcher that cannot rebuild itself keeps them.
        matcher = matcher.withPatternOptions(options.flags, options.caseInsensitive);
        Engine engine = new Engine(matcher, options);
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
     * Engine state of a single search: traversal, counting and result collection.
     */
    private static final class Engine {

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

        Engine(LineMatcher matcher, Options opts) {
            this.matcher = matcher;
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

        /** Scans one virtual text file (plain file, archive entry or gzipped text). */
        private void scanSource(Source src, boolean explicit) {
            if (stopRequested) {
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
                        + (src.depth > 0 ? "maxEntryBytes" : "maxFileBytes") + " (" + maxBytes + " bytes): "
                        + src.displayPath);
            }
            counts.skipped++;
            addWarning("Skipped large file " + src.displayPath + ": size exceeds "
                    + (src.depth > 0 ? "maxEntryBytes" : "maxFileBytes"));
        }

        private long maxBytesFor(int depth) {
            return depth > 0 ? opts.maxEntryBytes : opts.maxFileBytes;
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
            List<Object> matches = new ArrayList<>();
            for (List<MatchRecord> list : byPath.values()) {
                for (MatchRecord m : list) {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("displayPath", m.displayPath);
                    map.put("line", m.line);
                    map.put("text", m.text);
                    map.put("before", lineRefs(m.before));
                    map.put("after", lineRefs(m.after));
                    matches.add(map);
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

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("matches", matches);
            result.put("files", matchedPaths());
            result.put("counts", countsMap);
            result.put("truncated", truncated);
            result.put("truncatedReason", truncatedReason);
            result.put("warnings", new ArrayList<Object>(warnings));
            return result;
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

    /** One matching line with its context. */
    private static final class MatchRecord {
        final String displayPath;
        final int line;
        final String text;
        final List<LineRef> before = new ArrayList<>(2);
        final List<LineRef> after = new ArrayList<>(2);

        MatchRecord(String displayPath, int line, String text) {
            this.displayPath = displayPath;
            this.line = line;
            this.text = text;
        }
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
            "",
            "--- Patterns ---",
            "pattern is a string (JavaScript regular expression source) or a RegExp object.",
            "Matching is line by line with JavaScript RegExp semantics; 'g'/'y' are dropped",
            "internally. An empty pattern throws. Example:",
            "    search.grep(\"TODO\", \"src\", { recursive: true })",
            "    search.grep(/password|secret/i, \"config\", { recursive: true, archives: true })",
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
            "Output:     mode=\"content\"|\"filesWithMatches\" (find: \"structured\"),",
            "            filename=true, lineNumbers=true",
            "Context:    before=0, after=0, context=0 (aliases B, A, C; conflicts are errors)",
            "Pattern:    flags=\"\" (any of " + VALID_REGEX_FLAGS + "), caseInsensitive=false",
            "Text:       encoding=\"UTF-8\" (only), binary=\"skip\" (only)",
            "Filtering:  include=[], exclude=[] (arrays of glob strings or RegExp objects)",
            "Archives:   archives=false, recursiveArchives=false, maxArchiveDepth=null",
            "Limits:     maxMatches=" + DEFAULT_MAX_MATCHES + ", maxFileBytes=" + DEFAULT_MAX_FILE_BYTES
                    + ",",
            "            maxEntryBytes=" + DEFAULT_MAX_ENTRY_BYTES + ", maxOutputBytes="
                    + DEFAULT_MAX_OUTPUT_BYTES,
            "",
            "Content output: 'displayPath:line:text' for matches, 'displayPath-line-text' for",
            "context lines; filename:false drops the path, lineNumbers:false the number. With",
            "context, overlapping groups are merged and separated by a '--' line. String output",
            "is cut at maxOutputBytes and ends with a '-- truncated by search limit: ...' marker.",
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
            "            archiveEntriesScanned, skipped, excluded, errors },",
            "  truncated: boolean, truncatedReason: \"maxMatches\"|null,",
            "  warnings: [string] }",
            "Paths are sorted by Unicode code point, line numbers are 1-based.",
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
            "--- Limitations (v1) ---",
            "- encoding only \"UTF-8\"; binary files are skipped (NUL byte or invalid UTF-8 in the",
            "  first " + BINARY_SNIFF_BYTES + " bytes); an explicit binary or oversized target throws,",
            "- no invertMatch / wholeWord / multiline / per-file match limits,",
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
