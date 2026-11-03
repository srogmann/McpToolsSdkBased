package org.rogmann.mcp2sdk.git;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The history of a repository, read as a stream of commits.
 *
 * <p>
 * The command is {@code git log -z --format=...} (plus {@code --raw -M} when the changed files are
 * wanted), so one commit is one NUL-terminated record whose fields are separated by 0x1F; the body
 * is the last field and may contain anything. Nothing is scraped from painted text, and a rename
 * keeps its source path.
 * </p>
 *
 * <p>
 * <b>Why the search runs here and not in git.</b> {@code git --grep} uses POSIX basic regular
 * expressions - a third dialect next to the ECMAScript one of {@code search}/{@code diff} and the
 * Java one this parser uses. A caller would have to know which syntax applies where. So git filters
 * what it can do cheaply and exactly ({@code -- path}, {@code --author}, {@code --since},
 * {@code --until}), and the pattern is matched against subject+ticket text here. The price is that
 * a search has to look at commits: {@code maxScan} bounds that, and the result says how many were
 * examined - it never pretends that "not found in 5000 commits" means "not in the history".
 * </p>
 *
 * <p>
 * <b>A range is two arguments, not a syntax.</b> {@code from}/{@code to} answer "what is here that
 * is not there": git is asked for {@code log <to> ^<from>} and computes the set itself - one
 * process, no set difference over two full histories in memory. The caller names two revisions and
 * never writes {@code ^}, {@code ..} or {@code --not}; that is {@link #requireUsable}'s job.
 * </p>
 *
 * <p>
 * <b>Streaming is the point.</b> {@link #stream} stops as soon as the consumer has enough and the
 * process is killed; the whole history never has to be produced for a question about three commits.
 * </p>
 */
public final class GitLog {

    /** Field separator inside one commit record (US, 0x1F). */
    private static final char FIELD = '\u001f';

    /**
     * The record format: full id, short id, parents, author name, author email, epoch seconds,
     * subject, body. The body is last because it is the only field that may contain anything, and
     * the record ends with an explicit NUL - so the separation does not depend on whether
     * {@code -z} also terminates a {@code --format} output.
     *
     * <p>
     * The {@code %} in front of {@code x00} is the whole point: {@code %b%x00} is "the body, then
     * a NUL", while {@code %bx00} is "the body, then the three characters x, 0 and 0" - git
     * prints that without complaint, and every body then looks like data. That is not a theory:
     * in this project all 644 commits reported the body {@code x00}. Next to {@code -z} the
     * trailing NUL costs one empty token, which the parser skips.
     * </p>
     */
    private static final String FORMAT =
            "--format=%H%x1f%h%x1f%P%x1f%an%x1f%ae%x1f%at%x1f%s%x1f%b%x00";

    /** Number of fields in one record (the body is the last one). */
    private static final int FIELD_COUNT = 8;

    /**
     * One changed file of a commit.
     *
     * @param status    the status token git printed, e.g. {@code M}, {@code A}, {@code D},
     *                  {@code R100}
     * @param path      the (new) path
     * @param origPath  the source path of a rename or copy, {@code null} otherwise
     */
    public record FileEntry(String status, String path, String origPath) {
    }

    /**
     * One commit.
     *
     * @param id           full object id
     * @param shortId      abbreviated object id
     * @param parents      parent ids (empty for a root commit)
     * @param authorName   author name
     * @param authorEmail  author email
     * @param epochSeconds author time in seconds since the epoch
     * @param subject      first line of the message
     * @param body         rest of the message (may be empty)
     * @param files        changed files, empty unless {@link Limits#files()} was requested
     */
    public record Commit(String id, String shortId, List<String> parents, String authorName,
                         String authorEmail, long epochSeconds, String subject, String body,
                         List<FileEntry> files) {
    }

    /**
     * What to look at (the part git can answer exactly and cheaply).
     *
     * @param rev         revision to start from, {@code null} for the current branch; must not be
     *                    combined with {@code from}/{@code to}
     * @param from        revision to leave out ({@code log <to> ^<from>}), {@code null} for none
     * @param to          the revision to start from in a comparison, {@code null} means
     *                    {@code HEAD} when {@code from} is set (see {@link #startRevision}) and is
     *                    unused otherwise
     * @param path        path relative to the repository root, {@code null} for all paths
     * @param follow      follow renames (needs exactly one path)
     * @param all         all refs instead of just the current branch
     * @param firstParent only follow the first parent (merge-friendly timeline)
     * @param author      author filter (git regex, glued to its option)
     * @param since       {@code @<epoch>} lower bound, {@code null} for none
     * @param until       {@code @<epoch>} upper bound, {@code null} for none
     */
    public record Selection(String rev, String from, String to, String path, boolean follow,
                            boolean all, boolean firstParent, String author, String since,
                            String until) {
    }

    /**
     * The budgets of one query.
     *
     * @param max     maximum number of <em>matching</em> commits handed to the sink (0 = no cap)
     * @param maxScan maximum number of commits examined (the search budget)
     * @param files   true to read the changed files of each commit
     */
    public record Limits(int max, int maxScan, boolean files) {
    }

    /**
     * What the query did.
     *
     * @param scanned   commits examined
     * @param matched   commits that passed the filter
     * @param truncated true if a limit ended the query before the history did
     * @param stopped   true if the query was ended early (by a sink or a limit)
     * @param notes     human-readable remarks, never {@code null}
     */
    public record Outcome(int scanned, int matched, boolean truncated, boolean stopped,
                          List<String> notes) {
    }

    private GitLog() {
    }

    /**
     * Checks that {@code rev}, {@code from} and {@code to} do not contradict each other.
     *
     * <p>
     * Called by the caller <em>before</em> the repository is touched, so that a usage error is
     * never answered with "not a git worktree".
     * </p>
     *
     * @param rev  one revision, or {@code null}
     * @param from the revision to leave out, or {@code null}
     * @param to   the revision to start from in that comparison, or {@code null}
     * @throws IllegalArgumentException if {@code rev} is combined with a comparison, or {@code to}
     *         is named without {@code from}
     */
    public static void requireUsable(String rev, String from, String to) {
        if (rev != null && (from != null || to != null)) {
            throw new IllegalArgumentException("'rev' names one revision, 'from'/'to' compare two - "
                    + "use either 'rev' or 'from'/'to' (not both): rev '" + rev + "'"
                    + (from != null ? ", from '" + from + "'" : "")
                    + (to != null ? ", to '" + to + "'" : ""));
        }
        if (to != null && from == null) {
            throw new IllegalArgumentException("'to' needs 'from' (it is the second side of a "
                    + "comparison); a single revision is 'rev': '" + to + "'");
        }
    }

    /**
     * The revision git is asked to start from.
     *
     * <p>
     * {@code from} without {@code to} means the current branch - git's own reading of
     * {@code main..} and the question a caller usually has ("what is in this branch that main does
     * not have"). The default is resolved here, in the one place that also builds the argument
     * vector, so the answer cannot report a different revision from the one git was given.
     * </p>
     *
     * @param selection what to look at
     * @return the revision to start from, or {@code null} for git's default
     * @throws IllegalArgumentException if the selection contradicts itself (see
     *         {@link #requireUsable})
     */
    public static String startRevision(Selection selection) {
        requireUsable(selection.rev(), selection.from(), selection.to());
        if (selection.from() == null) {
            return selection.rev();
        }
        return (selection.to() != null) ? selection.to() : "HEAD";
    }

    /**
     * Reads the history and hands each matching commit to the sink.
     *
     * <p>
     * {@code from} and {@code to} are checked before the walk: the excluded side reaches git as
     * {@code ^<rev>}, a spelling the caller never wrote, so git's own refusal would quote a form
     * that does not appear in the request - and a typo would be answered with a history that
     * happens to be shorter or empty instead of an error. {@code rev} is not checked up front
     * because git's refusal already names it as the caller wrote it.
     * </p>
     *
     * @param repo      the repository
     * @param selection what to look at
     * @param limits    the budgets
     * @param grep      pattern matched against {@code subject + "\n" + body}, or {@code null}
     * @param sink      receives each matching commit; {@code false} stops the query
     * @return what the query did
     * @throws IOException if git fails or the process had to be killed by the timeout; a stream cut
     *         by the character budget is reported in the {@link Outcome} ({@code truncated} plus a
     *         note), not thrown
     * @throws IllegalArgumentException if the selection contradicts itself (see
     *         {@link #requireUsable})
     */
    public static Outcome stream(GitRepo repo, Selection selection, Limits limits, Pattern grep,
                                 Predicate<Commit> sink) throws IOException {
        List<String> args = arguments(selection, limits);
        requireCommit(repo, selection.from(), "from");
        if (selection.to() != null) {
            requireCommit(repo, selection.to(), "to");
        }
        Parser parser = new Parser(limits, grep, sink);
        StringBuilder buffer = new StringBuilder();
        GitProcess.StreamResult result = GitProcess.stream(repo.root(), args,
                chunk -> {
                    buffer.append(chunk);
                    int end;
                    while ((end = buffer.indexOf("\u0000")) >= 0) {
                        String token = buffer.substring(0, end);
                        buffer.delete(0, end + 1);
                        if (!parser.accept(token)) {
                            return false;
                        }
                    }
                    return true;
                });
        if (!buffer.isEmpty() && !parser.done() && !result.truncated()) {
            // The last record has no trailing NUL when the process ended on its own. After a cut by
            // the budget the remainder is HALF a record: parsing it would invent a commit or throw,
            // and the outcome already says that the history was not exhausted.
            parser.accept(buffer.toString());
        }
        parser.finish();
        return parser.outcome(result);
    }

    /**
     * Refuses a revision the caller named that git does not know.
     *
     * @param repo the repository
     * @param rev  the revision, or {@code null} (nothing to check)
     * @param role the option the revision came from ({@code from} or {@code to})
     * @throws IOException if git does not know the revision
     */
    private static void requireCommit(GitRepo repo, String rev, String role) throws IOException {
        if (rev == null || GitObjects.isCommit(repo, rev)) {
            return;
        }
        String hint = "HEAD".equals(rev)
                ? " A repository without commits has no 'HEAD' yet." : "";
        throw new IOException("There is no revision '" + rev + "' (option '" + role + "'). "
                + "Check the name - git.branches() lists the branches, git.log() the commits. "
                + "An unknown revision is an error here, never a shorter or empty history." + hint);
    }

    /**
     * Builds the git arguments of one query.
     *
     * @param selection what to look at
     * @param limits    the budgets (only {@link Limits#files()} is used here - the ceilings are
     *                  enforced by the parser, so the process can be stopped in the middle)
     * @return the arguments after the prelude
     * @throws IllegalArgumentException if {@code follow} is requested without exactly one path, or
     *         the selection contradicts itself (see {@link #requireUsable})
     */
    public static List<String> arguments(Selection selection, Limits limits) {
        if (selection.follow() && selection.path() == null) {
            throw new IllegalArgumentException("'follow' follows the renames of ONE file and "
                    + "therefore needs 'path'");
        }
        String start = startRevision(selection);
        List<String> args = new ArrayList<>();
        args.add("log");
        args.add("-z");
        args.add(FORMAT);
        if (limits.files()) {
            args.add("--raw");
            args.add("-M");
        }
        if (selection.firstParent()) {
            args.add("--first-parent");
        }
        if (selection.all()) {
            args.add("--all");
        }
        if (selection.since() != null) {
            args.add("--since=" + selection.since());
        }
        if (selection.until() != null) {
            args.add("--until=" + selection.until());
        }
        if (selection.author() != null) {
            args.add("--author=" + selection.author());
        }
        if (selection.follow()) {
            args.add("--follow");
        }
        if (start != null) {
            args.add(start);
        }
        if (selection.from() != null) {
            // The excluded side. Two typed arguments instead of a range string: the caller never
            // writes '^', and the value cannot turn into an option (GitCommand.revision refuses a
            // leading '^' and a '-', and this one is glued to the caret here).
            args.add("^" + selection.from());
        }
        if (selection.path() != null) {
            args.add("--");
            args.add(selection.path());
        }
        return args;
    }

    /**
     * The state machine over the NUL-separated tokens of {@code git log -z ... [--raw -M]}.
     *
     * <p>
     * Three kinds of tokens arrive: a commit record (starts with an object id), a raw entry
     * ({@code :<oldmode> <newmode> <oldsha> <newsha> <status>}) and a path. The path of a raw entry
     * may itself start with {@code :} or look like an id, so the parser cannot tell them apart by
     * their shape alone - it knows how many paths the last raw entry announced (two for a rename or
     * copy, one otherwise) and reads accordingly. That is why this is a state machine and not a
     * regular expression.
     * </p>
     */
    static final class Parser {

        private final Limits limits;
        private final Pattern grep;
        private final Predicate<Commit> sink;

        private Fields fields;
        private final List<FileEntry> files = new ArrayList<>();
        private int pathsExpected;
        private String renameOrig;
        private String status;

        private int scanned;
        private int matched;
        private boolean stoppedByLimit;
        private boolean stoppedBySink;
        private boolean budgetExceeded;

        Parser(Limits limits, Pattern grep, Predicate<Commit> sink) {
            this.limits = limits;
            this.grep = grep;
            this.sink = sink;
        }

        /** @return true to continue reading, false to stop the process */
        boolean accept(String token) {
            if (pathsExpected > 0) {
                // A path is taken verbatim: a file name may contain anything, including a newline.
                acceptPath(token);
                return true;
            }
            String entry = stripLeadingLineBreaks(token);
            if (entry.isEmpty()) {
                return true;
            }
            if (entry.startsWith(":")) {
                acceptRawEntry(entry);
                return true;
            }
            acceptCommitRecord(entry);
            return !done();
        }

        /**
         * git separates the formatted record from the raw entries of the same commit with a line
         * break, which (with a NUL-terminated format) starts the token of the raw entry. That
         * break is not data, so it is removed before the token is classified - but only here, not
         * for a path.
         */
        private static String stripLeadingLineBreaks(String token) {
            int start = 0;
            while (start < token.length()
                    && (token.charAt(start) == '\n' || token.charAt(start) == '\r')) {
                start++;
            }
            return (start == 0) ? token : token.substring(start);
        }

        private void acceptPath(String token) {
            pathsExpected--;
            if (pathsExpected == 1) {
                // first of two paths: the source of a rename or copy
                renameOrig = token;
                return;
            }
            files.add(new FileEntry(status, token, renameOrig));
            renameOrig = null;
        }

        private void acceptRawEntry(String token) {
            String[] meta = token.substring(1).strip().split("\\s+");
            if (meta.length < 5) {
                throw new IllegalArgumentException("Unexpected raw record in git log output: '"
                        + token + "'");
            }
            status = meta[4];
            pathsExpected = (status.startsWith("R") || status.startsWith("C")) ? 2 : 1;
        }

        private void acceptCommitRecord(String token) {
            // The commit collected so far is finished as soon as the next one appears.
            flush();
            if (done()) {
                return;
            }
            // The limit is checked when a NEW commit arrives: at that moment it is certain that
            // the history continues, so "truncated" is never a false alarm.
            if (limits.maxScan() > 0 && scanned >= limits.maxScan()) {
                stoppedByLimit = true;
                return;
            }
            if (limits.max() > 0 && matched >= limits.max()) {
                stoppedByLimit = true;
                return;
            }
            String[] fieldsInToken = token.split(String.valueOf(FIELD), FIELD_COUNT);
            if (fieldsInToken.length < FIELD_COUNT) {
                throw new IllegalArgumentException("Unexpected record in git log output: '"
                        + shorten(token) + "'");
            }
            String body = fieldsInToken[7];
            if (body.endsWith("\n")) {
                // Guard, not the normal path: without -z git ends a formatted record with a line
                // break, with -z that break IS the NUL and the body arrives unadorned.
                body = body.substring(0, body.length() - 1);
            }
            fields = new Fields(fieldsInToken[0], fieldsInToken[1], parents(fieldsInToken[2]),
                    fieldsInToken[3], fieldsInToken[4],
                    GitParse.requireLong(fieldsInToken[5], "commit time"), fieldsInToken[6], body);
        }

        /** Emits the commit that was being collected, and applies the filter. */
        private void flush() {
            if (fields == null) {
                return;
            }
            Fields current = fields;
            fields = null;
            scanned++;
            Commit commit = new Commit(current.id(), current.shortId(), current.parents(),
                    current.authorName(), current.authorEmail(), current.epochSeconds(),
                    current.subject(), current.body(), List.copyOf(files));
            files.clear();
            if (grep != null && !grep.matcher(current.subject() + "\n" + current.body()).find()) {
                return;
            }
            matched++;
            if (!sink.test(commit)) {
                stoppedBySink = true;
            }
        }

        void finish() {
            flush();
        }

        boolean done() {
            return stoppedBySink || stoppedByLimit || budgetExceeded;
        }

        Outcome outcome(GitProcess.StreamResult result) {
            // Two booleans side by side in one record are a trap: they were swapped here once.
            // The names say what each one means, and both are covered by a test of their own.
            boolean cutByLimit = stoppedByLimit || result.truncated();
            boolean endedEarly = cutByLimit || stoppedBySink;
            if (result.truncated()) {
                budgetExceeded = true;
            }
            List<String> notes = new ArrayList<>();
            if (stoppedByLimit) {
                notes.add("stopped after " + scanned + " commit(s) without reaching the end of the "
                        + "history: " + matched + " matched (max=" + limits.max() + ", maxScan="
                        + limits.maxScan() + "); narrow with path/since/until or raise the limit");
            }
            if (stoppedBySink) {
                notes.add("stopped by the caller after " + matched + " matched commit(s)");
            }
            if (result.truncated()) {
                notes.add("the output budget of the module ended the query after " + result.chars()
                        + " characters; narrow the request");
            }
            return new Outcome(scanned, matched, cutByLimit, endedEarly, List.copyOf(notes));
        }

        /** The fields of one record before the files are complete. */
        private record Fields(String id, String shortId, List<String> parents, String authorName,
                              String authorEmail, long epochSeconds, String subject, String body) {
        }

        private static List<String> parents(String field) {
            if (field.isBlank()) {
                return List.of();
            }
            List<String> parents = new ArrayList<>();
            for (String parent : field.strip().split(" ")) {
                if (!parent.isBlank()) {
                    parents.add(parent);
                }
            }
            return List.copyOf(parents);
        }

        private static String shorten(String token) {
            return (token.length() > 80) ? token.substring(0, 80) + "..." : token;
        }
    }
}
