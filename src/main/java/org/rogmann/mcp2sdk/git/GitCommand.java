package org.rogmann.mcp2sdk.git;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The argument grammar of the git module: every value a caller contributes is checked here before
 * it becomes part of an argument vector.
 *
 * <p>
 * This class is the answer to "no shell, and no command string either": a caller never hands over
 * an argument, only a <em>typed</em> value (revision, path, pattern, date, number, enum). The
 * checks here make sure that such a value cannot turn into an option - no leading {@code -}, no
 * {@code ..} range or leading {@code ^} exclusion in a revision, no {@code rev:path} form, no
 * control characters - and the calling code additionally glues every value to its option
 * ({@code --grep=<value>}) and puts paths behind {@code --}. Two independent layers, because the
 * first one is easy to forget in a new call site.
 * </p>
 *
 * <p>
 * All methods throw {@link IllegalArgumentException} with the offending value quoted; nothing is
 * silently repaired (a corrected revision would read as if the caller had asked for something
 * else).
 * </p>
 */
public final class GitCommand {

    /** Longest accepted revision expression. */
    private static final int MAX_REVISION_LENGTH = 200;

    /** Longest accepted path (relative to the repository root). */
    private static final int MAX_PATH_LENGTH = 1024;

    /** Longest accepted free-text pattern (grep, author). */
    private static final int MAX_PATTERN_LENGTH = 200;

    /**
     * Characters of a revision expression: a ref name, a shortened or full object id, and the
     * operators {@code ~ ^ @ { }} for "three commits back" or "the reflog entry before".
     */
    private static final Pattern REVISION_CHARS = Pattern.compile("[A-Za-z0-9._/~^@{}-]+");

    private GitCommand() {
    }

    /**
     * Validates a revision expression ({@code HEAD}, {@code main}, {@code v1.2.3},
     * {@code 4b825dc}, {@code HEAD~3}, {@code main@{1}}).
     *
     * @param value the revision as the caller named it
     * @return the validated revision, unchanged
     * @throws IllegalArgumentException if the value is blank, too long, starts with {@code -},
     *         starts with {@code ^} (git's exclusion operator), contains {@code ..} (a range is two
     *         revisions and belongs in two arguments), contains {@code :} (the {@code rev:path}
     *         form is expressed by a separate {@code path} argument), or contains a character
     *         outside the revision alphabet
     */
    public static String revision(String value) {
        String rev = requireText(value, "revision");
        if (rev.length() > MAX_REVISION_LENGTH) {
            throw new IllegalArgumentException("Revision is too long (" + rev.length()
                    + " characters, at most " + MAX_REVISION_LENGTH + " are accepted): " + rev);
        }
        if (rev.startsWith("-")) {
            throw new IllegalArgumentException("A revision must not start with '-' (that would be "
                    + "an option): '" + rev + "'");
        }
        if (rev.startsWith("^")) {
            // git's exclusion operator. A revision set without a positive side makes git walk
            // nothing: the caller gets an empty answer instead of a refusal - the one failure mode
            // this module must not have (see the '^' note in docs/js/git.md, section 10).
            throw new IllegalArgumentException("A revision must not start with '^' (that is git's "
                    + "exclusion operator; a set without a positive side gives an empty result, not "
                    + "an error). To leave a revision out, name both sides ('from' and 'to'): '"
                    + rev + "'");
        }
        if (rev.contains("..")) {
            throw new IllegalArgumentException("A revision must not contain '..' - a range is two "
                    + "revisions; use separate 'from' and 'to' arguments: '" + rev + "'");
        }
        if (rev.contains(":")) {
            throw new IllegalArgumentException("A revision must not contain ':' - use the 'path' "
                    + "argument instead of the rev:path form: '" + rev + "'");
        }
        if (!REVISION_CHARS.matcher(rev).matches()) {
            throw new IllegalArgumentException("Revision contains a character that is not allowed "
                    + "(allowed: letters, digits and . _ / ~ ^ @ { } -): '" + rev + "'");
        }
        return rev;
    }

    /**
     * Validates a path relative to the repository root.
     *
     * <p>
     * The path is expected to be <em>already resolved</em> against the permitted directory (the
     * caller uses the same resolution as {@code fs.*}, including the symbolic-link check); this
     * method only guarantees that the value cannot be read as an option, cannot address the parent
     * directory and contains no control characters. Callers pass the result behind {@code --} with
     * {@code --literal-pathspecs} active, so glob characters stay literal.
     * </p>
     *
     * @param value the repository-relative path ({@code .} is accepted and means the root)
     * @return the validated path, unchanged
     * @throws IllegalArgumentException if the path is blank, absolute, starts with {@code -},
     *         contains a {@code ..} segment, a backslash or a control character
     */
    public static String path(String value) {
        String p = requireText(value, "path");
        if (p.length() > MAX_PATH_LENGTH) {
            throw new IllegalArgumentException("Path is too long (" + p.length()
                    + " characters, at most " + MAX_PATH_LENGTH + " are accepted)");
        }
        if (p.startsWith("/") || p.startsWith("\\")) {
            throw new IllegalArgumentException("Path must be relative to the project directory: '"
                    + p + "'");
        }
        if (p.startsWith("-")) {
            throw new IllegalArgumentException("A path must not start with '-' (that would be an "
                    + "option): '" + p + "'");
        }
        if (p.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Path must use '/' as separator: '" + p + "'");
        }
        if (containsControlCharacter(p)) {
            throw new IllegalArgumentException("Path contains a control character: '" + p + "'");
        }
        for (String segment : p.split("/")) {
            if ("..".equals(segment)) {
                throw new IllegalArgumentException("Path must not contain '..': '" + p + "'");
            }
        }
        return p;
    }

    /**
     * Validates a free-text pattern (a grep or author expression). The caller attaches it to its
     * option with {@code =}, so a leading {@code -} is harmless here; a control character is not
     * (it would break the record separation of the output).
     *
     * @param value   the pattern
     * @param what    the field name used in the error message (e.g. {@code grep})
     * @return the validated pattern, unchanged
     * @throws IllegalArgumentException if the value is blank, too long or contains a control
     *         character
     */
    public static String pattern(String value, String what) {
        String text = requireText(value, what);
        if (text.length() > MAX_PATTERN_LENGTH) {
            throw new IllegalArgumentException("'" + what + "' is too long (" + text.length()
                    + " characters, at most " + MAX_PATTERN_LENGTH + " are accepted)");
        }
        if (containsControlCharacter(text)) {
            throw new IllegalArgumentException("'" + what + "' contains a control character");
        }
        return text;
    }

    /**
     * Translates an ISO-8601 date or timestamp into the {@code @<seconds>} form git accepts for
     * {@code --since} and {@code --until}, so no locale or time zone of the machine can influence
     * the value.
     *
     * @param value either {@code YYYY-MM-DD} (midnight UTC) or an ISO offset date time
     *              ({@code 2026-11-01T09:30:00+01:00}, {@code 2026-11-01T09:30:00Z})
     * @param what  the field name used in the error message (e.g. {@code since})
     * @return the epoch-seconds expression, e.g. {@code @1793520000}
     * @throws IllegalArgumentException if the value is not one of the two accepted forms
     */
    public static String date(String value, String what) {
        String text = requireText(value, what);
        try {
            LocalDate day = LocalDate.parse(text);
            return "@" + day.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        } catch (DateTimeParseException e) {
            // not a plain date, try the full timestamp
        }
        try {
            OffsetDateTime time = OffsetDateTime.parse(text);
            return "@" + time.toEpochSecond();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("'" + what + "' must be YYYY-MM-DD or an ISO-8601 "
                    + "timestamp with offset (e.g. 2026-11-01T09:30:00+01:00), but was: '" + text
                    + "'");
        }
    }

    /**
     * Range check for a numeric argument.
     *
     * @param value the value
     * @param min   smallest accepted value
     * @param max   largest accepted value
     * @param what  the field name used in the error message
     * @return the value, unchanged
     * @throws IllegalArgumentException if the value is outside the range
     */
    public static int number(int value, int min, int max, String what) {
        if (value < min || value > max) {
            throw new IllegalArgumentException("'" + what + "' must be between " + min + " and "
                    + max + ", but was: " + value);
        }
        return value;
    }

    /**
     * Enum check with the accepted values in the error message.
     *
     * @param value   the value as the caller gave it (may be null)
     * @param absent  the value to return when the caller gave nothing (may be null)
     * @param what    the field name used in the error message
     * @param allowed the accepted values
     * @return the validated value (or {@code absent})
     * @throws IllegalArgumentException if the value is not one of the accepted ones
     */
    public static String oneOf(String value, String absent, String what, List<String> allowed) {
        if (value == null) {
            return absent;
        }
        if (!allowed.contains(value)) {
            throw new IllegalArgumentException("'" + what + "' must be one of "
                    + String.join(", ", allowed) + ", but was: '" + value + "'");
        }
        return value;
    }

    /**
     * Requires a non-blank text without surrounding blanks (a leading blank in a revision is a
     * typo that would otherwise become a mysterious "unknown revision").
     *
     * @param value the raw value
     * @param what  the field name used in the error message
     * @return the trimmed, non-empty value
     * @throws IllegalArgumentException if the value is null or blank
     */
    private static String requireText(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("'" + what + "' must not be empty");
        }
        return value.strip();
    }

    /**
     * @param text the text to check
     * @return true if the text contains a character below U+0020 or U+007F
     */
    private static boolean containsControlCharacter(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return true;
            }
        }
        return false;
    }
}
