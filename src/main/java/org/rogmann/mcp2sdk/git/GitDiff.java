package org.rogmann.mcp2sdk.git;

import org.rogmann.mcp2sdk.diff.DiffEngine;
import org.rogmann.mcp2sdk.diff.DiffResult;
import org.rogmann.mcp2sdk.diff.HunkBuilder;
import org.rogmann.mcp2sdk.diff.LineIndex;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Compares what git has with what is there: a file at a revision against the working tree, one
 * revision against another, or a commit against its parent.
 *
 * <p>
 * <b>git names the files, the diff engine compares them.</b> The list of changed files comes from
 * {@code diff-tree -r -M --name-status} - that is where renames are detected, and git is better at
 * that than any heuristic here. The content of each side is read as a blob ({@code cat-file blob},
 * so exactly what git stored, after {@code .gitattributes}) and handed to {@link DiffEngine}, which
 * produces the hunks. No {@code git diff} text is parsed and none is produced: the patch a caller
 * sees is rendered from the same result.
 * </p>
 *
 * <p>
 * <b>Where the bytes come from does not matter.</b> {@link #compareSides(Change, GitObjects.Blob,
 * GitObjects.Blob, DiffEngine.Options)} takes two prepared sides, which is how a blob of the
 * configured server is compared with a file of the project - the same hunks, the same patch, no
 * clone and no fetch.
 * </p>
 *
 * <p>
 * <b>Two identities, on purpose.</b> A side that comes from a revision carries its <em>blob id</em>
 * (what git calls it, printed in the patch's {@code index} line); a side that comes from the
 * working tree carries its <em>SHA-256</em>, because git has no id for bytes it has not stored yet.
 * A side that does not exist is {@link DiffResult.Source#absent(String)}, never an empty file.
 * </p>
 *
 * <p>
 * <b>Binary is git's decision.</b> If either side looks binary (a NUL byte in the first 8000
 * bytes), there is no text diff: the result carries the two sources and no hunks, and the caller
 * marks it. Content that is not valid UTF-8 is decoded as ISO-8859-1 for the comparison, which
 * keeps every byte visible instead of replacing it.
 * </p>
 */
public final class GitDiff {

    /** Default number of files compared in one call. */
    public static final int DEFAULT_MAX_FILES = 50;

    /**
     * Upper bound of files compared <em>with hunks</em>.
     *
     * <p>
     * The limit is the answer, not the time: measured in this project, one compared file costs about
     * 10 ms but 17-25 KiB of hunk text, so 500 files are already 10 MB - a number nobody reads and
     * no sandbox enjoys carrying.
     * </p>
     */
    public static final int MAX_FILES = 500;

    /**
     * Upper bound of files for a statistics-only comparison ({@code detail: 'stats'}).
     *
     * <p>
     * Same work, far smaller answer: no hunk text is built, so one file costs about 0.6 KiB instead
     * of 17-25 KiB. Measured: 400 files in 4 s and 573 files in about 6 s - within the budgets of
     * §9 of {@code docs/js/git.md}, which is why this ceiling can be higher than {@link #MAX_FILES}.
     * </p>
     */
    public static final int MAX_FILES_STATS = 5_000;

    /**
     * Upper bound of files for a names-only listing ({@link #names}).
     *
     * <p>
     * No blob is read at all - one {@code diff-tree} answers the question, so the cost per file is
     * a line of text, not a comparison. The ceiling therefore only protects against a listing no
     * one asked for.
     * </p>
     */
    public static final int MAX_FILES_NAMES = 20_000;

    /**
     * One changed file, as {@code --name-status} reports it.
     *
     * @param status   the status token, e.g. {@code M}, {@code A}, {@code D}, {@code R100}
     * @param path     the (new) path
     * @param origPath the source path of a rename or copy, {@code null} otherwise
     */
    public record Change(String status, String path, String origPath) {

        /** @return true if the file exists only on the new side */
        public boolean added() {
            return status.startsWith("A");
        }

        /** @return true if the file exists only on the old side */
        public boolean deleted() {
            return status.startsWith("D");
        }
    }

    /**
     * One comparison.
     *
     * @param path     a single file to compare, or {@code null} for every changed file
     * @param from     the old revision, or {@code null} when the old side does not exist
     * @param to       the new revision, or {@code null} to compare against the working tree
     * @param maxFiles maximum number of files compared
     * @param maxBytes maximum number of bytes read per side
     * @param options  options for the diff engine
     */
    public record Request(String path, String from, String to, int maxFiles, int maxBytes,
                          DiffEngine.Options options) {

        /** @return true if the new side is the working tree */
        public boolean worktree() {
            return to == null;
        }
    }

    /**
     * One compared file.
     *
     * @param change what git says about the file
     * @param result the comparison (sources always, hunks only for text), never {@code null}
     * @param binary true if one side looked binary, so the result has no hunks on purpose
     */
    public record FileDiff(Change change, DiffResult result, boolean binary) {
    }

    /**
     * The outcome of one comparison.
     *
     * @param files     the compared files, at most {@code maxFiles}
     * @param total     number of changed files found
     * @param truncated true if fewer files were compared than found
     * @param notes     human-readable remarks, never {@code null}
     */
    public record Comparison(List<FileDiff> files, int total, boolean truncated,
                             List<String> notes) {
    }

    /**
     * The changed files of one comparison, without looking into them.
     *
     * @param files     the changed files, at most {@code maxFiles}
     * @param total     number of changed files found
     * @param truncated true if fewer files are listed than found
     */
    public record Listing(List<Change> files, int total, boolean truncated) {
    }

    private GitDiff() {
    }

    /**
     * Compares according to the request.
     *
     * <p>
     * Three shapes: one file (a {@code path} is named, the other side is a revision or the working
     * tree), two revisions, or - without {@code path} and without {@code to} - a revision against
     * the working tree, i.e. "what is not committed here".
     * </p>
     *
     * @param repo    the repository
     * @param request what to compare
     * @return the comparison
     * @throws IOException              if git fails or a named revision does not exist
     * @throws IllegalArgumentException if the request names nothing to compare
     */
    public static Comparison compare(GitRepo repo, Request request) throws IOException {
        return compare(repo, request, null);
    }

    /**
     * Compares according to the request, with a sink for the hunks.
     *
     * @param repo    the repository
     * @param request what to compare
     * @param sink    receives the hunks, or {@code null} to build them; {@link HunkBuilder.Sink#NONE}
     *                answers with the statistics only and no hunk text
     * @return the comparison
     * @throws IOException              if git fails or a named revision does not exist
     * @throws IllegalArgumentException if the request names nothing to compare
     */
    public static Comparison compare(GitRepo repo, Request request, HunkBuilder.Sink sink)
            throws IOException {
        // Checked first: a revision that does not exist would otherwise be read as "the file is
        // not there" and produce a comparison in which everything looks deleted. One rev-parse per
        // side is cheap next to reading the blobs.
        if (request.from() != null) {
            requireRevision(repo, request.from(), "from");
        }
        if (request.to() != null) {
            requireRevision(repo, request.to(), "to");
        }
        List<String> notes = new ArrayList<>();
        List<FileDiff> files = new ArrayList<>();
        if (request.path() != null && !isDirectory(repo, request)) {
            Change change = singleChange(repo, request);
            files.add(compareOne(repo, request, change, sink));
            return new Comparison(files, 1, false, List.copyOf(notes));
        }
        List<Change> changes = changedFiles(repo, request);
        int total = changes.size();
        boolean truncated = total > request.maxFiles();
        List<Change> kept = truncated ? changes.subList(0, request.maxFiles()) : changes;
        for (Change change : kept) {
            files.add(compareOne(repo, request, change, sink));
        }
        if (truncated) {
            notes.add("only " + request.maxFiles() + " of " + total + " changed file(s) were "
                    + "compared; raise 'maxFiles' or name a 'path'");
        }
        return new Comparison(files, total, truncated, List.copyOf(notes));
    }

    /**
     * Lists the changed files without reading either side - "what is different", not "how".
     *
     * <p>
     * The list comes from one {@code diff-tree} ({@code --name-status -z -M}), the same call a full
     * comparison starts with: no blob is read, nothing is hashed, {@link DiffEngine} is not involved.
     * That is what makes a whole tree affordable (573 files in this project) where a comparison of
     * the same tree would be 10 MB of hunk text.
     * </p>
     *
     * @param repo    the repository
     * @param request what to compare (a {@code path} narrows the list; a directory is a pathspec)
     * @return the changed files, truncated to {@code maxFiles}
     * @throws IOException              if git fails or a named revision does not exist
     * @throws IllegalArgumentException if the request names nothing to compare
     */
    public static Listing names(GitRepo repo, Request request) throws IOException {
        if (request.from() != null) {
            requireRevision(repo, request.from(), "from");
        }
        if (request.to() != null) {
            requireRevision(repo, request.to(), "to");
        }
        List<Change> changes = changedFiles(repo, request);
        int total = changes.size();
        boolean truncated = total > request.maxFiles();
        return new Listing(truncated ? changes.subList(0, request.maxFiles()) : changes, total,
                truncated);
    }

    /**
     * Chooses the git command for the changed files of a request.
     *
     * @param repo    the repository
     * @param request what to compare
     * @return the changed files, possibly narrowed by {@code path}
     * @throws IOException              if git fails
     * @throws IllegalArgumentException if the request names nothing to compare
     */
    private static List<Change> changedFiles(GitRepo repo, Request request) throws IOException {
        if (request.to() == null) {
            if (request.from() == null) {
                throw new IllegalArgumentException("A comparison needs a target: name two revisions "
                        + "('from' and 'to'), a 'commit', or one revision ('from') to compare with "
                        + "the working tree.");
            }
            return changesAgainstWorktree(repo, request.from(), request.path());
        }
        return changes(repo, request.from(), request.to(), request.path());
    }

    /**
     * Decides whether {@code path} names a directory, i.e. whether it is a pathspec for git.
     *
     * <p>
     * A directory is the natural way to ask "what changed in this package", and git expands it in a
     * pathspec. Reading it as a file instead ends in {@code fatal: ... bad file} from
     * {@code cat-file} - a raw git message handed to a caller who did nothing wrong. The answer
     * costs one {@code cat-file -t} per side, and for a comparison with the working tree also one
     * look at the disk (the working tree has no revision to ask).
     * </p>
     *
     * @param repo    the repository
     * @param request what to compare
     * @return true if the path is a directory on either side
     * @throws IOException if git fails
     */
    private static boolean isDirectory(GitRepo repo, Request request) throws IOException {
        boolean treeInTo = request.to() != null
                && "tree".equals(GitObjects.kind(repo, request.to(), request.path()));
        boolean treeInFrom = request.from() != null
                && "tree".equals(GitObjects.kind(repo, request.from(), request.path()));
        if (treeInTo || treeInFrom) {
            return true;
        }
        return request.worktree() && Files.isDirectory(repo.root().resolve(request.path()));
    }

    /**
     * Reads the changed files between two revisions (or of one commit, when {@code to} is
     * {@code null}: that is the commit against its first parent, or against nothing for a root
     * commit).
     *
     * @param repo the repository
     * @param from the old revision, or {@code null} to let git compare {@code to} with its parent
     * @param to   the new revision, or {@code null}
     * @param path a file or directory to narrow the list, or {@code null} for all paths
     * @return the changed files
     * @throws IOException if git fails
     */
    public static List<Change> changes(GitRepo repo, String from, String to, String path)
            throws IOException {
        List<String> args = new ArrayList<>();
        args.add("diff-tree");
        args.add("-r");
        args.add("-M");
        args.add("-z");
        args.add("--no-commit-id");
        args.add("--name-status");
        args.add("--root");
        if (from != null) {
            args.add(from);
        }
        if (to != null) {
            args.add(to);
        }
        if (path != null) {
            args.add("--");
            args.add(path);
        }
        return parseNameStatus(GitProcess.run(repo.root(), args).stdout());
    }

    /**
     * Reads the files that differ between a revision and the working tree.
     *
     * <p>
     * {@code diff-tree} cannot see the working tree, so this one question goes to {@code git diff} -
     * but only for the <em>names</em> ({@code --name-status -z}, the same machine format
     * {@code diff-tree} prints). No patch text is read and none is produced: the hunks come from
     * {@link DiffEngine} as everywhere else. Untracked files are not part of it (git does not
     * compare either) - {@code git.status} lists those.
     * </p>
     *
     * @param repo the repository
     * @param from the revision the working tree is compared with
     * @param path a file or directory to narrow the list, or {@code null} for all paths
     * @return the changed files
     * @throws IOException if git fails (for example because the revision does not exist)
     */
    public static List<Change> changesAgainstWorktree(GitRepo repo, String from, String path)
            throws IOException {
        List<String> args = new ArrayList<>();
        args.add("diff");
        args.add("--name-status");
        args.add("-z");
        args.add("-M");
        args.add("--no-ext-diff");
        args.add("--no-textconv");
        args.add("--no-color");
        args.add(from);
        if (path != null) {
            args.add("--");
            args.add(path);
        }
        return parseNameStatus(GitProcess.run(repo.root(), args).stdout());
    }

    /**
     * Checks that a revision exists - before a missing revision is read as a missing file.
     *
     * <p>
     * A blob lookup answers "there is nothing" for an unknown revision as well as for a path that
     * does not exist there. Without this check a typo like {@code to: 'WORKTREE'} produces a
     * comparison in which every file lost all its lines: a wrong answer to a question nobody
     * asked, and one that looks like a finding.
     * </p>
     *
     * @param repo the repository
     * @param rev  the revision as the caller named it
     * @param role the option the revision came from ({@code from} or {@code to})
     * @throws IOException if git does not know the revision
     */
    private static void requireRevision(GitRepo repo, String rev, String role) throws IOException {
        if (GitObjects.isCommit(repo, rev)) {
            return;
        }
        String hint = "HEAD".equals(rev)
                ? " A repository without commits has no 'HEAD' yet." : "";
        throw new IOException("There is no revision '" + rev + "' (option '" + role + "'). "
                + "Check the name - git.branches() lists the branches, git.log() the commits. "
                + "To compare the working tree, name a revision in 'from' and leave 'to' out."
                + hint);
    }
    /**
     * Parses {@code --name-status -z} output: a status token, then the path (two paths for a
     * rename or copy: source first).
     *
     * @param output the raw output
     * @return the changes
     * @throws IllegalArgumentException if a record is incomplete
     */
    public static List<Change> parseNameStatus(String output) {
        List<Change> changes = new ArrayList<>();
        if (output == null) {
            return changes;
        }
        String[] tokens = output.split("\u0000", -1);
        for (int i = 0; i < tokens.length; i++) {
            String status = stripLineEnd(tokens[i]);
            if (status.isEmpty()) {
                continue;
            }
            if (i + 1 >= tokens.length) {
                throw new IllegalArgumentException("Unexpected name-status output: the status '"
                        + status + "' has no path");
            }
            if (status.startsWith("R") || status.startsWith("C")) {
                if (i + 2 >= tokens.length) {
                    throw new IllegalArgumentException("Unexpected name-status output: the status '"
                            + status + "' has only one of its two paths");
                }
                String origPath = tokens[++i];
                String path = tokens[++i];
                if (path.isEmpty()) {
                    throw new IllegalArgumentException("Unexpected name-status output: the status '"
                            + status + "' has only one of its two paths");
                }
                changes.add(new Change(status, path, origPath));
            } else {
                String path = tokens[++i];
                if (path.isEmpty()) {
                    throw new IllegalArgumentException("Unexpected name-status output: the status '"
                            + status + "' has no path");
                }
                changes.add(new Change(status, path, null));
            }
        }
        return changes;
    }

    /**
     * The first parent of a commit.
     *
     * @param repo   the repository
     * @param commit the commit (already validated)
     * @return the parent id, or {@code null} for a root commit
     * @throws IOException if git cannot be asked
     */
    public static String firstParent(GitRepo repo, String commit) throws IOException {
        GitProcess.Result result = GitProcess.runLenient(repo.root(),
                List.of("rev-parse", "--verify", "--quiet", commit + "^"));
        String id = result.stdout().strip();
        return (result.exitCode() == 0 && !id.isEmpty()) ? id : null;
    }

    // ------------------------------------------------------------------
    // One file
    // ------------------------------------------------------------------

    /** Builds the change description of a single-file comparison. */
    private static Change singleChange(GitRepo repo, Request request) throws IOException {
        String path = request.path();
        boolean oldExists = request.from() != null
                && GitObjects.tryReadBlob(repo, request.from(), path, 1) != null;
        boolean newExists;
        if (request.worktree()) {
            Raw raw = readWorktree(repo, path, 0);
            newExists = raw != null;
        } else {
            newExists = GitObjects.tryReadBlob(repo, request.to(), path, 1) != null;
        }
        if (!oldExists && !newExists) {
            throw new IOException("'" + path + "' exists neither in "
                    + (request.from() == null ? "the old revision" : "'" + request.from() + "'")
                    + " nor in " + (request.worktree() ? "the working tree"
                    : "'" + request.to() + "'") + ".");
        }
        String status = !oldExists ? "A" : (!newExists ? "D" : "M");
        return new Change(status, path, null);
    }

    /**
     * Compares two sides that the caller has already read.
     *
     * <p>
     * This is the one place where {@link DiffEngine} sees a pair of git-like sides, so every
     * comparison of this module - local working tree, revision against revision, or a remote blob
     * against a local file - produces the same hunks. A side is a {@link GitObjects.Blob}: its
     * {@code id} is the identity of the content (a blob id for a revision, {@code null} for bytes
     * that were never stored, which makes the result carry their SHA-256 instead). A side that does
     * not exist is {@code null} and becomes {@link DiffResult.Source#absent(String)} - never an
     * empty file.
     * </p>
     *
     * @param change  what changed (status, path, source path of a rename)
     * @param oldBlob the old content, or {@code null} if that side does not exist
     * @param newBlob the new content, or {@code null} if that side does not exist
     * @param options options for the diff engine, {@code null} for the defaults
     * @return the comparison, with {@code binary} set when one side looks binary
     */
    public static FileDiff compareSides(Change change, GitObjects.Blob oldBlob,
            GitObjects.Blob newBlob, DiffEngine.Options options) {
        return compareSides(change, oldBlob, newBlob, options, null);
    }

    /**
     * Compares two sides that the caller has already read, with a sink for the hunks.
     *
     * @param change  what changed (status, path, source path of a rename)
     * @param oldBlob the old content, or {@code null} if that side does not exist
     * @param newBlob the new content, or {@code null} if that side does not exist
     * @param options options for the diff engine, {@code null} for the defaults
     * @param sink    receives the hunks, or {@code null}; {@link HunkBuilder.Sink#NONE} computes the
     *                statistics without materialising hunk text
     * @return the comparison, with {@code binary} set when one side looks binary
     */
    public static FileDiff compareSides(Change change, GitObjects.Blob oldBlob,
            GitObjects.Blob newBlob, DiffEngine.Options options, HunkBuilder.Sink sink) {
        String oldPath = (change.origPath() != null) ? change.origPath() : change.path();
        DiffResult.Source oldSource = source(oldPath, oldBlob);
        DiffResult.Source newSource = source(change.path(), newBlob);
        byte[] oldData = (oldBlob == null) ? null : oldBlob.data();
        byte[] newData = (newBlob == null) ? null : newBlob.data();
        boolean binary = isBinary(oldData) || isBinary(newData);
        DiffEngine.Options effective = (options != null) ? options : DiffEngine.Options.defaults();
        String oldText = binary ? "" : text(oldData);
        String newText = binary ? "" : text(newData);
        DiffResult result = DiffEngine.diff(LineIndex.of(oldText), LineIndex.of(newText),
                oldSource, newSource, effective, sink);
        return new FileDiff(change, result, binary);
    }

    /**
     * Compares the two sides of one file.
     *
     * <p>
     * Reading is separated from comparing: the bytes of a revision come from {@code cat-file}, the
     * bytes of the working tree from the file system, and a remote comparison gets them from the
     * server - all three end in {@link #compareSides(Change, GitObjects.Blob, GitObjects.Blob,
     * DiffEngine.Options)}.
     * </p>
     */
    private static FileDiff compareOne(GitRepo repo, Request request, Change change,
            HunkBuilder.Sink sink) throws IOException {
        String oldPath = (change.origPath() != null) ? change.origPath() : change.path();

        GitObjects.Blob oldBlob = null;
        if (request.from() != null) {
            oldBlob = GitObjects.tryReadBlob(repo, request.from(), oldPath, request.maxBytes());
        }
        GitObjects.Blob newBlob;
        if (request.worktree()) {
            Raw raw = readWorktree(repo, change.path(), request.maxBytes());
            newBlob = (raw == null) ? null : new GitObjects.Blob(change.path(), "", null,
                    raw.data(), raw.data().length, raw.truncated());
        } else {
            newBlob = GitObjects.tryReadBlob(repo, request.to(), change.path(),
                    request.maxBytes());
        }
        return compareSides(change, oldBlob, newBlob, request.options(), sink);
    }

    /**
     * Describes one side for the diff engine. The metadata (lines, line ending, BOM) is taken from
     * the very helpers a blob uses, so a working-tree side is described exactly like a blob.
     */
    private static DiffResult.Source source(String path, GitObjects.Blob blob) {
        if (blob == null) {
            return DiffResult.Source.absent(path);
        }
        String id = blob.id();
        String sha256 = (id == null || id.isBlank()) ? GitObjects.sha256Hex(blob.data()) : null;
        GitObjects.Blob probe = new GitObjects.Blob(path, "", (id == null) ? "" : id, blob.data(),
                blob.data().length, blob.truncated());
        int lines = probe.binary() ? -1 : probe.lines();
        return new DiffResult.Source(path, id, blob.data().length, lines, sha256, probe.eol(),
                probe.finalNewline(), probe.bom(), true);
    }

    /** Reads a file of the working tree, bounded; {@code null} if it does not exist. */
    private static Raw readWorktree(GitRepo repo, String path, int maxBytes) throws IOException {
        Path file = repo.root().resolve(path).normalize();
        if (!file.startsWith(repo.root())) {
            throw new IllegalArgumentException("The path leaves the repository: '" + path + "'");
        }
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] data = in.readNBytes(maxBytes + 1);
            if (data.length > maxBytes) {
                return new Raw(Arrays.copyOf(data, maxBytes), true);
            }
            return new Raw(data, false);
        }
    }

    private static boolean isBinary(byte[] data) {
        return data != null && new GitObjects.Blob("", "", "", data, data.length, false).binary();
    }

    /**
     * Decodes content for the comparison: UTF-8, and ISO-8859-1 when that fails (a source file in a
     * legacy encoding stays comparable, and every byte stays visible).
     */
    private static String text(byte[] data) {
        if (data == null) {
            return "";
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(data, StandardCharsets.ISO_8859_1);
        }
    }

    private static String stripLineEnd(String token) {
        String result = token;
        while (result.endsWith("\n") || result.endsWith("\r")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /** Bytes read from the working tree. */
    private record Raw(byte[] data, boolean truncated) {
    }
}
