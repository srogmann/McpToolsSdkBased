package org.rogmann.mcp2sdk.js;

import org.rogmann.mcp2sdk.diff.DiffResult;
import org.rogmann.mcp2sdk.diff.HunkBuilder;
import org.rogmann.mcp2sdk.diff.UnifiedRenderer;
import org.rogmann.mcp2sdk.git.GitCommand;
import org.rogmann.mcp2sdk.git.GitDiff;
import org.rogmann.mcp2sdk.git.GitLog;
import org.rogmann.mcp2sdk.git.GitObjects;
import org.rogmann.mcp2sdk.git.GitProcess;
import org.rogmann.mcp2sdk.git.GitRefs;
import org.rogmann.mcp2sdk.git.GitRepo;
import org.rogmann.mcp2sdk.git.GitStatus;
import org.rogmann.mcp2sdk.git.forge.ForgeConfig;
import org.rogmann.mcp2sdk.git.forge.ForgeHttp;
import org.rogmann.mcp2sdk.git.forge.ForgeRepo;
import org.rogmann.mcp2sdk.git.forge.GitHubForge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The {@code git} namespace of the JavaScript sandbox: reading the local repository and asking the
 * configured code-hosting server what it has.
 *
 * <p>
 * <b>Read-only, and honest about it.</b> What exists here reads: the repository and its state, the
 * working state, the branches and the remotes, the history, blobs and trees at a revision, local
 * and server-side comparisons, and the pull requests of the configured server. Writing (commit,
 * restore) will only ever run behind the four-eyes review; a caller must never get a writing
 * command from a JS call.
 * </p>
 *
 * <p>
 * <b>Sources come from git, comparisons come from the diff engine.</b> When a file at a revision is
 * read, it is a blob (with its blob id as identity) and {@code DiffEngine} compares - never
 * {@code git diff} text. That way a local comparison, a historical one and a remote one produce the
 * same hunks, and the object identity in a {@code DiffResult.Source} is the id git itself uses.
 * </p>
 *
 * <p>
 * <b>Nothing absolute leaves the server.</b> Paths in results and messages are relative to the
 * project directory; a file outside it is not addressable at all ({@link JsFileSystem}).
 * </p>
 */
public final class JsGit {

    /** Default number of status records returned. */
    private static final int DEFAULT_MAX_FILES = 200;

    /** Upper bound of status records returned. */
    private static final int MAX_FILES = 2_000;

    /** Default number of branches returned. */
    private static final int DEFAULT_MAX_BRANCHES = 50;

    /** Upper bound of branches returned. */
    private static final int MAX_BRANCHES = 500;

    /** Accepted values of the {@code untracked} option. */
    private static final List<String> UNTRACKED_MODES = List.of("normal", "all", "no");

    /** Default number of commits returned by {@code git.log}. */
    private static final int DEFAULT_MAX_COMMITS = 50;

    /** Upper bound of commits returned by {@code git.log}. */
    private static final int MAX_COMMITS = 5000;

    /** Default number of commits examined when a pattern has to be matched. */
    private static final int DEFAULT_MAX_SCAN = 5_000;

    /** Upper bound of commits examined in one query. */
    private static final int MAX_SCAN = 50_000;

    /** Default number of commits delivered by {@code git.eachCommit}. */
    private static final int DEFAULT_MAX_STREAM = 1_000;

    /** Default maximum size of one blob, in bytes. */
    private static final int DEFAULT_MAX_BYTES = 1_048_576;

    /** Upper bound of one blob, in bytes. */
    private static final int MAX_BYTES = 8_388_608;

    /** Default number of tree entries returned. */
    private static final int DEFAULT_MAX_ENTRIES = 500;

    /** Upper bound of tree entries returned. */
    private static final int MAX_ENTRIES = 10_000;

    /** Default number of pull requests, files or comments of a server list. */
    private static final int DEFAULT_MAX_PULL_ITEMS = 30;

    /** Upper bound of a pull request number (the server numbers them with 32-bit integers). */
    private static final int MAX_PULL_NUMBER = 1_000_000;

    /** Accepted values of the pull-request {@code state} filter. */
    private static final List<String> PULL_STATES = List.of("open", "closed", "all");

    /**
     * Receives one commit of {@code git.eachCommit}.
     */
    @FunctionalInterface
    public interface CommitVisitor {

        /**
         * @param commit the commit, already converted to the JS shape
         * @return true to continue, false to stop the query
         */
        boolean visit(Map<String, Object> commit);
    }

    /**
     * One HTTP client per API base: the rate-limit counters belong to the connection, and a cached
     * client keeps them visible across calls without leaking one client per call.
     */
    private static final Map<String, ForgeHttp> CLIENTS = new ConcurrentHashMap<>();

    private JsGit() {
    }

    // ========================================================================
    // Repository and working state
    // ========================================================================

    /**
     * Describes the repository the project directory belongs to.
     *
     * @param options {@code repo} (an optional sub-directory of the project that holds the
     *                repository)
     * @return {@code {root, gitDir, state, head:{ref, branch, id, shortId, detached, initial}}}
     * @throws IOException if the project directory is not a git worktree
     */
    public static Map<String, Object> repo(Map<String, Object> options) throws IOException {
        requireKnownOptions("git.repo", options, Set.of("repo"));
        GitRepo repo = detect(options);
        Map<String, Object> head = new LinkedHashMap<>();
        GitProcess.Result branchResult = GitProcess.runLenient(repo.root(),
                List.of("rev-parse", "--abbrev-ref", "HEAD"));
        String branchName = (branchResult.exitCode() == 0) ? branchResult.stdout().strip() : null;
        boolean detached = "HEAD".equals(branchName);
        if (detached) {
            branchName = null;
        }
        GitProcess.Result headResult = GitProcess.runLenient(repo.root(),
                List.of("rev-parse", "HEAD"));
        String id = (headResult.exitCode() == 0) ? headResult.stdout().strip() : null;
        head.put("ref", (branchName == null) ? null : "refs/heads/" + branchName);
        head.put("branch", branchName);
        head.put("id", id);
        head.put("shortId", (id == null) ? null : id.substring(0, Math.min(7, id.length())));
        head.put("detached", detached);
        head.put("initial", id == null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("root", ".");
        result.put("gitDir", repo.displayGitDir());
        result.put("state", repo.state());
        result.put("head", head);
        return result;
    }

    /**
     * Reads the working state: branch, ahead/behind, and one record per changed, renamed or
     * untracked path.
     *
     * @param options {@code untracked} ({@code normal}, {@code all}, {@code no}) and
     *                {@code maxFiles}, plus {@code repo}
     * @return {@code {branch:{oid, branch, upstream, ahead, behind, detached, initial}, counts,
     *         entries:[{path, origPath, index, worktree, kind, submodule}], total, truncated}}
     * @throws IOException if git fails or the output budget is exhausted
     */
    public static Map<String, Object> status(Map<String, Object> options) throws IOException {
        requireKnownOptions("git.status", options, Set.of("untracked", "maxFiles", "repo"));
        String untracked = GitCommand.oneOf(
                stringOption(options, "untracked", "normal", "git.status"),
                "normal", "untracked", UNTRACKED_MODES);
        int maxFiles = numberOption(options, "maxFiles", DEFAULT_MAX_FILES, 1, MAX_FILES,
                "git.status");
        GitRepo repo = detect(options);
        GitStatus.Status status = GitStatus.read(repo, untracked, maxFiles);

        Map<String, Object> branch = new LinkedHashMap<>();
        branch.put("oid", status.branch().oid());
        branch.put("branch", status.branch().head());
        branch.put("upstream", status.branch().upstream());
        branch.put("ahead", status.branch().ahead());
        branch.put("behind", status.branch().behind());
        branch.put("detached", status.branch().detached());
        branch.put("initial", status.branch().initial());

        List<Object> entries = new ArrayList<>();
        for (GitStatus.Entry entry : status.entries()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", entry.path());
            item.put("origPath", entry.origPath());
            item.put("index", String.valueOf(entry.index()));
            item.put("worktree", String.valueOf(entry.worktree()));
            item.put("kind", entry.kind());
            item.put("submodule", entry.submodule());
            entries.add(item);
        }
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("staged", status.counts().staged());
        counts.put("unstaged", status.counts().unstaged());
        counts.put("untracked", status.counts().untracked());
        counts.put("conflicted", status.counts().conflicted());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("branch", branch);
        result.put("counts", counts);
        result.put("entries", entries);
        result.put("total", status.total());
        result.put("truncated", status.truncated());
        return result;
    }

    /**
     * Reads the configured remotes with their URLs (credential parts removed).
     *
     * @param options {@code repo}
     * @return one entry per remote
     * @throws IOException if git fails
     */
    public static List<Object> remotes(Map<String, Object> options) throws IOException {
        requireKnownOptions("git.remotes", options, Set.of("repo"));
        GitRepo repo = detect(options);
        List<Object> result = new ArrayList<>();
        for (GitRefs.Remote remote : GitRefs.readRemotes(repo)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", remote.name());
            item.put("fetch", remote.fetchUrl());
            item.put("push", remote.pushUrl());
            item.put("redacted", remote.redacted());
            result.add(item);
        }
        return result;
    }

    /**
     * Lists local or remote-tracking branches with their upstream and ahead/behind counts.
     *
     * @param options {@code remote} (true for remote-tracking branches), {@code max}, {@code repo}
     * @return the branches, sorted by name
     * @throws IOException if git fails
     */
    public static List<Object> branches(Map<String, Object> options) throws IOException {
        requireKnownOptions("git.branches", options, Set.of("remote", "max", "repo"));
        boolean remote = booleanOption(options, "remote", false, "git.branches");
        int max = numberOption(options, "max", DEFAULT_MAX_BRANCHES, 1, MAX_BRANCHES,
                "git.branches");
        GitRepo repo = detect(options);
        List<Object> result = new ArrayList<>();
        for (GitRefs.BranchRef branch : GitRefs.readBranches(repo, remote, max)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", branch.name());
            item.put("id", branch.id());
            item.put("shortId", branch.shortId());
            item.put("upstream", branch.upstream());
            item.put("ahead", branch.ahead());
            item.put("behind", branch.behind());
            item.put("gone", branch.gone());
            item.put("current", branch.current());
            result.add(item);
        }
        return result;
    }

    // ========================================================================
    // Server
    // ========================================================================

    /**
     * Describes the configured code-hosting server: base URL, whether a token is present, the
     * owner allowlist, the link conventions and (if a request was made) the rate limit.
     *
     * @return the description; {@code {configured:false, property, hint}} when no server is
     *         configured
     */
    public static Map<String, Object> hubInfo() {
        Map<String, Object> info = ForgeConfig.info();
        if (Boolean.TRUE.equals(info.get("configured"))) {
            String rate = http().rateLimitLine();
            if (rate != null) {
                info.put("rateLimit", rate);
            }
        }
        return info;
    }

    /**
     * Reads the description of one repository on the configured server.
     *
     * @param options {@code repo} ({@code owner/name})
     * @return {@code {fullName, defaultBranch, private, archived, sizeKb, pushedAt, htmlUrl,
     *         description}}
     * @throws IOException if the server does not answer usefully
     */
    public static Map<String, Object> hubRepo(Map<String, Object> options) throws IOException {
        requireKnownOptions("git.hub.repo", options, Set.of("repo"));
        return forge().repo(forgeRepo(options, "git.hub.repo"));
    }

    /**
     * Lists the branches of a repository on the server.
     *
     * @param options {@code repo}, {@code max}
     * @return {@code [{name, sha, protected}]}
     * @throws IOException if the server does not answer usefully
     */
    public static List<Object> hubBranches(Map<String, Object> options) throws IOException {
        requireKnownOptions("git.hub.branches", options, Set.of("repo", "max"));
        int max = remoteMax(options, DEFAULT_MAX_BRANCHES, "git.hub.branches");
        return forge().branches(forgeRepo(options, "git.hub.branches"), max);
    }

    /**
     * Lists one level of a directory on the server.
     *
     * @param options {@code repo}, {@code ref}, {@code path}, {@code max}
     * @return {@code {path, ref, entries:[{name, path, type, size, sha}], total, truncated}}
     * @throws IOException if the server does not answer usefully
     */
    public static Map<String, Object> hubTree(Map<String, Object> options) throws IOException {
        requireKnownOptions("git.hub.tree", options, Set.of("repo", "ref", "path", "max"));
        String function = "git.hub.tree";
        int max = remoteMax(options, DEFAULT_MAX_ENTRIES, function);
        return forge().tree(forgeRepo(options, function),
                refOption(options, function), pathOptionRemote(options, function), max);
    }

    /**
     * Reads one file on the server.
     *
     * @param options {@code repo}, {@code ref}, {@code path}
     * @return the same shape {@code git.file} produces (plus {@code note} when the server cannot
     *         deliver the content)
     * @throws IOException if the server does not answer usefully
     */
    public static Map<String, Object> hubFile(Map<String, Object> options) throws IOException {
        requireKnownOptions("git.hub.file", options, Set.of("repo", "ref", "path"));
        String function = "git.hub.file";
        String path = requiredString(options, "path", function);
        return forge().file(forgeRepo(options, function), refOption(options, function), path);
    }

    /**
     * Lists the last commits on the server.
     *
     * @param options {@code repo}, {@code ref}, {@code path}, {@code max}
     * @return one entry per commit in the shape {@code git.log} uses
     * @throws IOException if the server does not answer usefully
     */
    public static List<Object> hubCommits(Map<String, Object> options) throws IOException {
        requireKnownOptions("git.hub.commits", options, Set.of("repo", "ref", "path", "max"));
        String function = "git.hub.commits";
        int max = remoteMax(options, DEFAULT_MAX_COMMITS, function);
        return forge().commits(forgeRepo(options, function), refOption(options, function),
                pathOptionRemote(options, function), max);
    }

    /**
     * Compares two revisions on the configured server.
     *
     * <p>
     * The file list comes from the server (it detects renames, and one request is cheaper than
     * listing two trees), the hunks come from the diff engine - so a remote comparison has the
     * same shape as a local one. {@code diff: false} (the default) answers with the file list and
     * the commit range; {@code diff: true} fetches both contents of every listed file, which costs
     * two requests per file and should be a conscious decision.
     * </p>
     *
     * @param options {@code repo}, {@code from}, {@code to}, {@code diff}, {@code patch},
     *                {@code maxFiles}
     * @return {@code {repo, from, to, status, aheadBy, behindBy, mergeBase, commits, totalCommits,
     *         commitsTruncated, files:[…], total, truncated, notes}}
     * @throws IOException if the server does not answer usefully
     */
    public static Map<String, Object> hubCompare(Map<String, Object> options) throws IOException {
        requireKnownOptions("git.hub.compare", options,
                Set.of("repo", "from", "to", "diff", "patch", "maxFiles"));
        String function = "git.hub.compare";
        ForgeRepo repo = forgeRepo(options, function);
        String from = requiredRevision(options, "from", function);
        String to = requiredRevision(options, "to", function);
        boolean withDiff = booleanOption(options, "diff", false, function);
        boolean withPatch = booleanOption(options, "patch", false, function);
        int maxFiles = numberOption(options, "maxFiles", GitDiff.DEFAULT_MAX_FILES, 1,
                GitDiff.MAX_FILES, function);
        if (withPatch && !withDiff) {
            throw new IllegalArgumentException(function + ": 'patch' needs 'diff: true' - the patch "
                    + "is rendered from the comparison, not from the server's text");
        }
        GitHubForge forge = forge();
        GitHubForge.Comparison comparison = forge.compare(repo, from, to, maxFiles);
        // The old content is read at the merge base, not at 'from': on diverged branches those are
        // different commits, and reading at 'from' would compare against content the server's
        // file list never looked at.
        String oldRef = (comparison.mergeBase() != null) ? comparison.mergeBase() : from;
        List<String> notes = new ArrayList<>(comparison.notes());
        List<Object> files = new ArrayList<>();
        for (GitHubForge.ChangedFile change : comparison.files()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", change.path());
            item.put("status", change.status());
            item.put("origPath", change.origPath());
            item.put("additions", change.additions());
            item.put("deletions", change.deletions());
            if (withDiff) {
                addRemoteComparison(item, forge, repo, oldRef, to, change, withPatch, notes);
            }
            files.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("repo", repo.fullName());
        result.put("from", from);
        result.put("to", to);
        result.put("status", comparison.status());
        result.put("aheadBy", comparison.aheadBy());
        result.put("behindBy", comparison.behindBy());
        result.put("mergeBase", comparison.mergeBase());
        result.put("commits", comparison.commits());
        result.put("totalCommits", comparison.totalCommits());
        result.put("commitsTruncated", comparison.commitsTruncated());
        result.put("files", files);
        result.put("total", comparison.total());
        result.put("truncated", comparison.truncated());
        result.put("notes", notes);
        return result;
    }

    /**
     * Compares a file of the configured server with a file of the project - without a clone and
     * without a fetch.
     *
     * <p>
     * The remote side is a blob at {@code ref} (its {@code sha} is the git blob id), the local side
     * is read through the same path rules as {@code fs.*}, and both go through the diff engine.
     * Old side is the server, new side is the project - so {@code status: 'D'} means "the file is
     * gone here", exactly as {@code git.diff} reports it against the working tree.
     * </p>
     *
     * @param options {@code repo} ({@code owner/name}), {@code ref}, {@code path}, {@code maxBytes},
     *                {@code patch}
     * @return the shape of one {@code git.diff} file entry, plus {@code repo} and {@code ref}
     * @throws IOException if neither side exists or the server does not answer usefully
     */
    public static Map<String, Object> hubCompareWithLocal(Map<String, Object> options)
            throws IOException {
        requireKnownOptions("git.hub.compareWithLocal", options,
                Set.of("repo", "ref", "path", "maxBytes", "patch"));
        String function = "git.hub.compareWithLocal";
        ForgeRepo repo = forgeRepo(options, function);
        String revision = refOption(options, function);
        String rawPath = requiredString(options, "path", function);
        int maxBytes = numberOption(options, "maxBytes", DEFAULT_MAX_BYTES, 1, MAX_BYTES, function);
        boolean withPatch = booleanOption(options, "patch", false, function);
        // The local path is resolved before the first request: a path that leaves the project is a
        // usage error, not a question for the server.
        Path file = JsFileSystem.resolveSafePath(rawPath);
        String localPath = JsFileSystem.toRelative(file);
        if (localPath.startsWith("/")) {
            throw new IllegalArgumentException(function + ": '" + localPath + "' lies in an add-on "
                    + "folder, so it has no path in a repository; compare a file of the project "
                    + "directory.");
        }
        localPath = GitCommand.path(localPath);
        byte[] localData = readLocal(file, maxBytes);

        GitHubForge forge = forge();
        GitObjects.Blob remoteBlob = forge.blobOrNull(repo, revision, localPath);
        if (remoteBlob == null && revision != null) {
            // "No such file" and "no such branch" are the same 404 - ask which one it is, so a
            // typo in the revision is not reported as a deleted file.
            forge.requireRevision(repo, revision);
        }
        if (remoteBlob == null && localData == null) {
            throw new IOException("'" + localPath + "' exists neither in " + repo.fullName()
                    + " at '" + displayRefOf(revision) + "' nor in the project directory.");
        }
        if (remoteBlob != null && remoteBlob.truncated()) {
            throw new IOException("The server delivers no content for '" + localPath + "' ("
                    + remoteBlob.size() + " bytes; the contents API stops at 1 MB) - compare a "
                    + "smaller file, or read it locally with git.file().");
        }
        String status = (remoteBlob == null) ? "A" : ((localData == null) ? "D" : "M");
        GitObjects.Blob localBlob = (localData == null) ? null
                : new GitObjects.Blob(localPath, "", null, localData, localData.length, false);
        GitDiff.FileDiff comparison = GitDiff.compareSides(
                new GitDiff.Change(status, localPath, null), remoteBlob, localBlob, null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("repo", repo.fullName());
        result.put("ref", displayRefOf(revision));
        result.putAll(fileEntry(comparison, withPatch));
        return result;
    }

    /**
     * Lists the pull requests of a repository on the server.
     *
     * @param options {@code repo}, {@code state}, {@code head}, {@code base}, {@code max}
     * @return {@code [{number, title, state, draft, author, createdAt, updatedAt, head, base,
     *         htmlUrl}]}
     * @throws IOException if the server does not answer usefully
     */
    public static List<Object> hubPulls(Map<String, Object> options) throws IOException {
        requireKnownOptions("git.hub.pulls", options,
                Set.of("repo", "state", "head", "base", "max"));
        String function = "git.hub.pulls";
        String state = GitCommand.oneOf(stringOption(options, "state", "open", function), "open",
                "state", PULL_STATES);
        String head = stringOption(options, "head", null, function);
        String base = stringOption(options, "base", null, function);
        int max = remoteMax(options, DEFAULT_MAX_PULL_ITEMS, function);
        return forge().pulls(forgeRepo(options, function), state, head, base, max);
    }

    /**
     * Reads one pull request: header, body, merge state and the counters.
     *
     * @param options {@code repo}, {@code number}
     * @return the description; {@code mergeable} is {@code null} while the server computes it
     * @throws IOException if the pull request does not exist or the server does not answer usefully
     */
    public static Map<String, Object> hubPull(Map<String, Object> options) throws IOException {
        requireKnownOptions("git.hub.pull", options, Set.of("repo", "number"));
        String function = "git.hub.pull";
        return forge().pull(forgeRepo(options, function), pullNumber(options, function));
    }

    /**
     * Lists the files of a pull request with the patch text the server renders.
     *
     * @param options {@code repo}, {@code number}, {@code max}
     * @return {@code {number, files:[{path, status, origPath, additions, deletions, patch,
     *         patchTruncated}], total, truncated, notes}}
     * @throws IOException if the pull request does not exist or the server does not answer usefully
     */
    public static Map<String, Object> hubPullFiles(Map<String, Object> options) throws IOException {
        requireKnownOptions("git.hub.pullFiles", options, Set.of("repo", "number", "max"));
        String function = "git.hub.pullFiles";
        int max = remoteMax(options, DEFAULT_MAX_PULL_ITEMS, function);
        return forge().pullFiles(forgeRepo(options, function), pullNumber(options, function), max);
    }

    /**
     * Reads the comments of a pull request: the conversation and the code review comments, merged
     * in time order.
     *
     * @param options {@code repo}, {@code number}, {@code max}
     * @return {@code {number, comments:[…], total, truncated, notes}}
     * @throws IOException if the pull request does not exist or the server does not answer usefully
     */
    public static Map<String, Object> hubPullComments(Map<String, Object> options)
            throws IOException {
        requireKnownOptions("git.hub.pullComments", options, Set.of("repo", "number", "max"));
        String function = "git.hub.pullComments";
        int max = remoteMax(options, DEFAULT_MAX_PULL_ITEMS, function);
        return forge().pullComments(forgeRepo(options, function), pullNumber(options, function),
                max);
    }

    /** @return the server client of the configured API base (one per base, so the rate limit lives) */
    private static GitHubForge forge() {
        ForgeHttp.requireConfigured();
        return new GitHubForge(CLIENTS.computeIfAbsent(ForgeConfig.apiBase(),
                base -> ForgeHttp.create()));
    }

    /** @return the client of the configured API base, for {@link #hubInfo()} */
    private static ForgeHttp http() {
        return CLIENTS.computeIfAbsent(ForgeConfig.apiBase(), base -> ForgeHttp.create());
    }

    /** Reads the {@code repo} option as {@code owner/name} (and checks the allowlist). */
    private static ForgeRepo forgeRepo(Map<String, Object> options, String function) {
        return ForgeRepo.parse(requiredString(options, "repo", function));
    }

    /** Reads the optional {@code ref} option of a server call (checked by the revision grammar). */
    private static String refOption(Map<String, Object> options, String function) {
        String raw = stringOption(options, "ref", null, function);
        return (raw == null) ? null : GitCommand.revision(raw);
    }

    /** Reads a required revision of a server call (checked by the revision grammar). */
    private static String requiredRevision(Map<String, Object> options, String key,
                                           String function) {
        return GitCommand.revision(requiredString(options, key, function));
    }

    /** Reads the optional {@code path} option of a server call. */
    private static String pathOptionRemote(Map<String, Object> options, String function) {
        return stringOption(options, "path", null, function);
    }

    /** Reads an option that must be present. */
    private static String requiredString(Map<String, Object> options, String key,
                                         String function) {
        String value = stringOption(options, key, null, function);
        if (value == null) {
            throw new IllegalArgumentException(function + ": the option '" + key + "' is required "
                    + "- see git.help()");
        }
        return value;
    }

    // ========================================================================
    // History
    // ========================================================================

    /**
     * Reads the history: the last {@code max} commits that match, newest first.
     *
     * @param options {@code rev}, {@code from}, {@code to}, {@code path}, {@code follow},
     *                {@code all}, {@code firstParent}, {@code author}, {@code grep},
     *                {@code fixedStrings}, {@code since}, {@code until}, {@code files}, {@code max},
     *                {@code maxScan}, {@code repo}
     * @return {@code {from, to, commits:[…], scanned, matched, truncated, stopped, notes}}
     * @throws IOException if git fails or a limit ends the query
     */
    public static Map<String, Object> log(Map<String, Object> options) throws IOException {
        Query query = query("git.log", options, DEFAULT_MAX_COMMITS, MAX_COMMITS);
        List<Map<String, Object>> commits = new ArrayList<>();
        GitLog.Outcome outcome = GitLog.stream(query.repo(), query.selection(), query.limits(),
                query.grep(), commit -> {
                    commits.add(commitToMap(commit, query.limits().files()));
                    return true;
                });
        return historyResult(commits, outcome, query.selection());
    }

    /**
     * Streams the history, one commit at a time, without materialising it.
     *
     * @param options  as {@link #log(Map)}
     * @param visitor  receives each matching commit; {@code false} stops the query (and kills the
     *                 git process)
     * @return {@code {from, to, scanned, matched, truncated, stopped, notes}}
     * @throws IOException if git fails or a limit ends the query
     */
    public static Map<String, Object> eachCommit(Map<String, Object> options, CommitVisitor visitor)
            throws IOException {
        Query query = query("git.eachCommit", options, DEFAULT_MAX_STREAM, MAX_SCAN);
        GitLog.Outcome outcome = GitLog.stream(query.repo(), query.selection(), query.limits(),
                query.grep(), commit -> visitor.visit(commitToMap(commit, query.limits().files())));
        Map<String, Object> result = historyResult(List.of(), outcome, query.selection());
        result.remove("commits");
        return result;
    }

    /**
     * Reads one file at a revision.
     *
     * @param options {@code rev} (default {@code HEAD}), {@code path}, {@code maxBytes},
     *                {@code repo}
     * @return {@code {rev, path, blobId, size, bytes, truncated, binary, lines, eol,
     *         finalNewline, bom, text}}
     * @throws IOException if the path does not exist at that revision
     */
    public static Map<String, Object> file(Map<String, Object> options) throws IOException {
        requireKnownOptions("git.file", options, Set.of("rev", "path", "maxBytes", "repo"));
        BlobRequest request = blobRequest("git.file", options);
        GitObjects.Blob blob = GitObjects.readBlob(request.repo(), request.rev(), request.path(),
                request.maxBytes());
        return blob.toMap();
    }

    /**
     * Reads one file at a revision as bytes (for {@code Uint8Array} in JavaScript).
     *
     * @param options {@code rev}, {@code path}, {@code maxBytes}, {@code repo}
     * @return the bytes
     * @throws IOException if the path does not exist at that revision
     */
    public static byte[] blob(Map<String, Object> options) throws IOException {
        requireKnownOptions("git.blob", options, Set.of("rev", "path", "maxBytes", "repo"));
        BlobRequest request = blobRequest("git.blob", options);
        return GitObjects.readBlob(request.repo(), request.rev(), request.path(),
                request.maxBytes()).data();
    }

    /**
     * Lists one level of a directory at a revision.
     *
     * @param options {@code rev} (default {@code HEAD}), {@code path} (default: the root),
     *                {@code max}, {@code repo}
     * @return {@code {rev, path, entries:[{name, path, type, mode, size, id}], total, truncated}}
     * @throws IOException if git fails or the object is not a directory
     */
    public static Map<String, Object> tree(Map<String, Object> options) throws IOException {
        requireKnownOptions("git.tree", options, Set.of("rev", "path", "max", "repo"));
        String function = "git.tree";
        String rev = revisionOption(options, function);
        int max = numberOption(options, "max", DEFAULT_MAX_ENTRIES, 1, MAX_ENTRIES, function);
        GitRepo repo = detect(options);
        String path = pathOption(options, function, repo);
        GitObjects.Tree tree = GitObjects.readTree(repo, rev, path, max);
        List<Object> entries = new ArrayList<>();
        for (GitObjects.TreeEntry entry : tree.entries()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", entry.name());
            item.put("path", entry.path());
            item.put("type", entry.type());
            item.put("mode", entry.mode());
            item.put("size", entry.hasSize() ? entry.size() : null);
            item.put("id", entry.id());
            entries.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rev", rev);
        result.put("path", path == null ? "." : path);
        result.put("entries", entries);
        result.put("total", tree.total());
        result.put("truncated", tree.truncated());
        return result;
    }

    // ========================================================================
    // Comparison
    // ========================================================================

    /**
     * Compares revisions (and the working tree) through the diff engine.
     *
     * @param options {@code path}, {@code from}, {@code to}, {@code commit}, {@code maxFiles},
     *                {@code maxBytes}, {@code patch}, {@code detail}, {@code repo}
     * @return {@code {from, to, worktree, path, detail, files:[…], total, truncated, notes}}; each
     *         file carries the same shape {@code diff.files} produces, plus {@code path},
     *         {@code status} and optionally {@code patch} - with {@code detail: 'names'} only
     *         {@code path}, {@code status} and {@code origPath}
     * @throws IOException if git fails or a side does not exist
     */
    public static Map<String, Object> diff(Map<String, Object> options) throws IOException {
        requireKnownOptions("git.diff", options, Set.of("path", "from", "to", "commit", "maxFiles",
                "maxBytes", "patch", "detail", "repo"));
        String function = "git.diff";
        String rawFrom = stringOption(options, "from", null, function);
        String rawTo = stringOption(options, "to", null, function);
        String rawCommit = stringOption(options, "commit", null, function);
        boolean withPatch = booleanOption(options, "patch", false, function);
        String detail = detailOption(options, function);
        int maxFiles = numberOption(options, "maxFiles", GitDiff.DEFAULT_MAX_FILES, 1,
                maxFilesFor(detail), function);
        int maxBytes = numberOption(options, "maxBytes", DEFAULT_MAX_BYTES, 1, MAX_BYTES, function);
        if (rawCommit != null && (rawFrom != null || rawTo != null)) {
            throw new IllegalArgumentException(function + ": name either 'commit' or 'from'/'to', "
                    + "not both - see git.help()");
        }
        if (rawCommit == null && rawFrom == null && rawTo == null) {
            // Checked before the repository is touched: a question about nothing is a usage
            // error, not a repository question.
            throw new IllegalArgumentException(function + ": name what to compare - 'commit', "
                    + "'from' (optionally with 'to'), 'path' with 'from', or 'from' alone for the "
                    + "uncommitted changes; e.g. git.diff({path: 'src/x.java', from: 'HEAD'}) - see "
                    + "git.help()");
        }
        if (withPatch && !"full".equals(detail)) {
            throw new IllegalArgumentException(function + ": 'patch' needs detail: 'full' - a "
                    + "patch consists of the hunks detail: '" + detail + "' does not build");
        }
        GitRepo repo = detect(options);
        String path = pathOption(options, function, repo);

        String from;
        String to;
        if (rawCommit != null) {
            // A commit is compared with its parent; a root commit adds every file.
            String commit = GitCommand.revision(rawCommit);
            from = GitDiff.firstParent(repo, commit);
            to = commit;
        } else {
            from = (rawFrom == null) ? null : GitCommand.revision(rawFrom);
            to = (rawTo == null) ? null : GitCommand.revision(rawTo);
        }

        GitDiff.Request request = new GitDiff.Request(path, from, to, maxFiles, maxBytes, null);
        if ("names".equals(detail)) {
            // No blob, no engine: the question is "what is different", not "how".
            return namesResult(request, GitDiff.names(repo, request));
        }
        GitDiff.Comparison comparison = GitDiff.compare(repo, request,
                "stats".equals(detail) ? HunkBuilder.Sink.NONE : null);
        List<Object> files = new ArrayList<>();
        for (GitDiff.FileDiff file : comparison.files()) {
            files.add(fileEntry(file, withPatch));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", from);
        result.put("to", to);
        result.put("worktree", Boolean.valueOf(to == null));
        result.put("path", path);
        result.put("detail", detail);
        result.put("files", files);
        result.put("total", comparison.total());
        result.put("truncated", comparison.truncated());
        result.put("notes", comparison.notes());
        return result;
    }

    /**
     * The answer of {@code detail: 'names'}: which files differ, without a single blob read.
     *
     * @param request the comparison that was asked for
     * @param listing the changed files git reported
     * @return the result in the shape of {@code git.diff}
     */
    private static Map<String, Object> namesResult(GitDiff.Request request, GitDiff.Listing listing) {
        List<Object> files = new ArrayList<>();
        for (GitDiff.Change change : listing.files()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", change.path());
            item.put("status", change.status());
            item.put("origPath", change.origPath());
            files.add(item);
        }
        List<String> notes = new ArrayList<>();
        if (listing.truncated()) {
            notes.add("only " + request.maxFiles() + " of " + listing.total() + " changed file(s) "
                    + "were listed; raise 'maxFiles' - detail: 'names' reads no content, so up to "
                    + GitDiff.MAX_FILES_NAMES + " are accepted");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", request.from());
        result.put("to", request.to());
        result.put("worktree", Boolean.valueOf(request.worktree()));
        result.put("path", request.path());
        result.put("detail", "names");
        result.put("files", files);
        result.put("total", listing.total());
        result.put("truncated", listing.truncated());
        result.put("notes", notes);
        return result;
    }

    /**
     * Renders the git-style patch of one compared file: {@code diff --git}, the {@code index} line
     * with the real blob ids, and the hunks. The same text {@code git apply} would take.
     */
    private static String patchOf(GitDiff.FileDiff file) {
        String oldPath = (file.change().origPath() != null) ? file.change().origPath()
                : file.change().path();
        DiffResult result = file.result();
        String oldId = (result.oldSource() == null) ? null : result.oldSource().id();
        String newId = (result.newSource() == null) ? null : result.newSource().id();
        return UnifiedRenderer.render(result, "a/" + oldPath, "b/" + file.change().path(),
                UnifiedRenderer.Style.GIT, oldId, newId, null);
    }

    /**
     * One compared file as a script sees it: the shape of {@code diff.files} plus {@code path},
     * {@code status} and {@code origPath} - and the patch on request.
     *
     * <p>
     * One place for this shape, because three doors deliver it: {@code git.diff},
     * {@code git.hub.compare} and {@code git.hub.compareWithLocal}. A caller learns it once.
     * </p>
     *
     * @param file      the compared file
     * @param withPatch true to add the unified patch in git style
     * @return the entry
     */
    private static Map<String, Object> fileEntry(GitDiff.FileDiff file, boolean withPatch) {
        Map<String, Object> item = JsDiffBridge.toMap(file.result(), true);
        item.put("path", file.change().path());
        item.put("status", file.change().status());
        item.put("origPath", file.change().origPath());
        if (file.binary()) {
            // One side is binary, so there is no text diff - and "same" would be a lie.
            item.put("binary", Boolean.TRUE);
            item.put("same", Boolean.FALSE);
            item.put("hunks", List.of());
        }
        if (withPatch) {
            item.put("patch", patchOf(file));
        }
        return item;
    }

    /**
     * Adds the comparison of one file of a server-side revision comparison to its entry: both
     * contents from the server, the hunks from the diff engine.
     */
    private static void addRemoteComparison(Map<String, Object> item, GitHubForge forge,
            ForgeRepo repo, String oldRef, String newRef, GitHubForge.ChangedFile change,
            boolean withPatch, List<String> notes) throws IOException {
        String oldPath = (change.origPath() != null) ? change.origPath() : change.path();
        GitObjects.Blob oldBlob = change.added() ? null : forge.blobOrNull(repo, oldRef, oldPath);
        GitObjects.Blob newBlob = change.deleted() ? null : forge.blobOrNull(repo, newRef,
                change.path());
        if ((oldBlob != null && oldBlob.truncated()) || (newBlob != null && newBlob.truncated())) {
            // The server sends no content above 1 MB. Comparing the missing bytes as an empty side
            // would read as "the whole file was deleted" - so this file is named, not compared.
            item.put("content", Boolean.FALSE);
            item.put("note", "no comparison: the server delivers no content above 1 MB (size "
                    + (oldBlob != null && oldBlob.truncated() ? oldBlob.size() : newBlob.size())
                    + " bytes)");
            notes.add("'" + change.path() + "' was not compared - too large for the contents API");
            return;
        }
        GitDiff.FileDiff comparison = GitDiff.compareSides(
                new GitDiff.Change(change.status(), change.path(), change.origPath()), oldBlob,
                newBlob, null);
        item.put("content", Boolean.TRUE);
        item.putAll(fileEntry(comparison, withPatch));
    }

    /** Reads one file of the project, bounded; {@code null} if it is not a regular file. */
    private static byte[] readLocal(Path file, int maxBytes) throws IOException {
        if (Files.isDirectory(file)) {
            throw new IllegalArgumentException("'" + JsFileSystem.toRelative(file) + "' is a "
                    + "directory - compareWithLocal compares one file");
        }
        if (!Files.isRegularFile(file)) {
            return null;
        }
        long size = Files.size(file);
        if (size > maxBytes) {
            throw new IllegalArgumentException("'" + JsFileSystem.toRelative(file) + "' has " + size
                    + " bytes, but at most " + maxBytes + " are compared per side; raise "
                    + "'maxBytes'");
        }
        return Files.readAllBytes(file);
    }

    /**
     * Reads the {@code detail} option of {@code git.diff}: {@code full}, {@code stats} or
     * {@code names}.
     *
     * @param options  the call's options
     * @param function the function name for the error message
     * @return the mode, one of {@code full}, {@code stats}, {@code names}
     */
    private static String detailOption(Map<String, Object> options, String function) {
        String detail = stringOption(options, "detail", "full", function);
        return switch (detail.trim().toLowerCase(Locale.ROOT)) {
            case "full" -> "full";
            case "stats" -> "stats";
            case "names" -> "names";
            default -> throw new IllegalArgumentException(function + ": unknown detail '" + detail
                    + "'; valid values are 'full', 'stats' and 'names'");
        };
    }

    /**
     * The hard ceiling of {@code maxFiles}, per detail.
     *
     * <p>
     * The ceiling follows the cost of the answer, not of the work: measured in this project, one
     * compared file costs about 10 ms in every mode, but 17-25 KiB of hunk text with
     * {@code detail: 'full'}, 0.6 KiB with {@code 'stats'} and three short values with
     * {@code 'names'} (which reads no content at all).
     * </p>
     *
     * @param detail the mode
     * @return the accepted maximum of {@code maxFiles}
     */
    private static int maxFilesFor(String detail) {
        return switch (detail) {
            case "names" -> GitDiff.MAX_FILES_NAMES;
            case "stats" -> GitDiff.MAX_FILES_STATS;
            default -> GitDiff.MAX_FILES;
        };
    }

    /** Reads the required pull-request {@code number}. */
    private static int pullNumber(Map<String, Object> options, String function) {
        if (options == null || options.get("number") == null) {
            throw new IllegalArgumentException(function + ": the option 'number' is required (the "
                    + "number the pull request has on the server, e.g. 42) - see git.help()");
        }
        return numberOption(options, "number", 0, 1, MAX_PULL_NUMBER, function);
    }

    /**
     * Reads the {@code max} option of a server list.
     *
     * <p>
     * The upper bound is the operator's {@code maxItems}: a caller must not be able to pull a
     * paginated list of unknown length into the context. Asking for more is refused with the
     * property and its value in the message - clamping it silently would look like a complete
     * answer and be a cut one.
     * </p>
     */
    private static int remoteMax(Map<String, Object> options, int absent, String function) {
        int cap = ForgeConfig.maxItems();
        int value = numberOption(options, "max", Math.min(absent, cap), 1, Integer.MAX_VALUE,
                function);
        if (value > cap) {
            throw new IllegalArgumentException(function + ": 'max' is at most " + cap
                    + " for one server list (property '" + ForgeConfig.PROP_MAX_ITEMS + "="
                    + cap + "'), but was: " + value);
        }
        return value;
    }

    /** Names the revision of a server call, or says that the default branch was meant. */
    private static String displayRefOf(String ref) {
        return (ref == null || ref.isBlank()) ? "the default branch" : ref;
    }

    // ========================================================================
    // Help / documentation
    // ========================================================================

    /**
     * Describes the JS API of this module.
     *
     * @return the help text
     */
    public static String help() {
        return """
                JS git module (namespace 'git')
                ==============================

                Read-only access to the git repository of the project directory - the same anchor
                as fs.*. Nothing here changes a file, the index or the history: writing (commit,
                restore) does not exist yet and, when it comes, will only run after a human
                approval.

                The repository root must BE the project directory. A project that happens to lie
                inside another repository is refused instead of being answered by the parent
                repository. Every git process runs with a fixed argument vector (no shell, no
                command string): you pass typed values - a revision, a path, an option - never
                arguments.

                --- Where are we ---

                git.repo([{repo}])
                    {root: '.', gitDir: '.git', state: 'clean',
                     head: {ref: 'refs/heads/main', branch: 'main', id: '<sha>', shortId: '<7>',
                            detached: false, initial: false}}
                    state: clean | merging | rebasing | cherry-picking | reverting | bisecting
                    initial is true before the first commit (head.id is null then); detached is
                    true when HEAD is not on a branch (head.branch is null then).

                git.status([{untracked: 'normal'|'all'|'no', maxFiles: 200, repo}])
                    {branch: {oid, branch, upstream, ahead, behind, detached, initial},
                     counts: {staged, unstaged, untracked, conflicted},
                     entries: [{path, origPath, index, worktree, kind, submodule}],
                     total, truncated}
                    kind: changed | renamed | copied | unmerged | untracked
                    index/worktree hold the status character ('.' = unchanged), e.g. 'M' 'A' 'D'
                    'R'. ahead/behind come from the same call - no second query, no parsing of
                    painted 'git status' text. 'counts' always describes ALL entries, also when
                    'entries' was cut to maxFiles ('truncated').

                git.remotes([{repo}])
                    [{name, fetch, push, redacted}]
                    A password or token inside a configured URL is replaced by '***'
                    (redacted: true); host and path stay readable.

                git.branches([{remote: false, max: 50, repo}])
                    [{name, id, shortId, upstream, ahead, behind, gone, current}] - sorted by
                    name, at most 'max' entries; 'remote: true' lists the remote-tracking
                    branches. 'gone: true' means an upstream is configured but no longer exists:
                    ahead/behind are null then BECAUSE the upstream is gone, not because nothing
                    changed.

                --- History ---

                git.log([{rev, from, to, path, follow, all, firstParent, author, grep,
                          fixedStrings, since, until, files, max, maxScan, repo}])
                    {from, to, commits: [{id, shortId, parents: [], author: {name, email},
                                          time: '2026-11-01T09:12:33Z', subject, body,
                                          files?: [{status, path, origPath}]}],
                     scanned, matched, truncated, stopped, notes}
                    Newest first. git filters what it can do exactly (path, author, since,
                    until); 'grep' is matched HERE against subject and body as a JavaScript/Java
                    regular expression - not with git's POSIX one, so there is only one dialect to
                    learn. A search therefore looks at up to maxScan commits (default 5000) and
                    says how many it saw: 'truncated' means the history was NOT exhausted, so
                    "not found" is never silently "not there".

                    'from' and 'to' answer "what is here that is not there" - in ONE process,
                    computed by git ('log <to> ^<from>'):
                      git.log({from: 'main'})              commits in the current branch that main
                                                           does not have ('to' defaults to HEAD)
                      git.log({from: 'main', to: 'feature/x'})  the same, other way round
                    'rev' and 'from'/'to' cannot be combined, and 'to' alone is refused (use 'rev'
                    for one revision). Write neither '^main' nor 'main..HEAD': those forms are
                    refused, and an unknown revision is an error - never a shorter or empty
                    history.

                git.eachCommit([{...the same options...}], function (commit) { ... })
                    Streams the history without materialising it; return false from the callback
                    to stop - the git process is killed then. Returns
                    {from, to, scanned, matched, truncated, stopped, notes}.
                    Example: git.eachCommit({path: 'docs', grep: 'typo'},
                                            c => print(c.shortId, c.subject));

                --- Sources at a revision ---

                git.file({rev, path, maxBytes})
                    {rev, path, blobId, size, bytes, truncated, binary, lines, eol,
                     finalNewline, bom, text}
                    The content of the BLOB - i.e. after .gitattributes, not the working tree.
                    'binary' is git's own heuristic (a NUL byte in the first 8000 bytes); 'text'
                    is null then, and also when the bytes are not valid UTF-8. Content over
                    maxBytes (default 1 MiB) is cut and 'truncated' says so.

                git.blob({rev, path, maxBytes})     - the same content as Uint8Array

                git.tree({rev, path, max})
                    {entries: [{name, path, type: 'blob'|'tree'|'commit', mode, size, id}],
                     total, truncated}
                    One level of a directory at a revision; 'path' defaults to the repository root.

                --- Comparisons ---

                git.diff({path, from, to, commit, maxFiles, maxBytes, patch, detail, repo})
                    {from, to, worktree, path, detail, files: [...], total, truncated, notes}
                    Five shapes, all through the same diff engine:
                      git.diff({path: 'src/x.java', from: 'HEAD~3'})   revision vs working tree
                      git.diff({path: 'src/x.java', from: 'HEAD~3', to: 'HEAD'})
                      git.diff({commit: 'abc1234'})     that commit against its parent
                      git.diff({from: 'main', to: 'feature/x'})   all changed files
                      git.diff({from: 'HEAD'})          all UNCOMMITTED changes (working tree)
                    'from' and 'to' are branches, tags or commit ids; there is no pseudo-revision
                    like 'WORKTREE' - the working tree is what you get by leaving 'to' out, and an
                    unknown revision is an error (never a diff in which every file looks deleted).
                    'path' names ONE file - or a DIRECTORY, which then narrows the list (git's
                    pathspec): git.diff({path: 'src/main/java/.../git', from: 'main', to: 'HEAD'}).
                    'detail' decides how much is answered, and the ceiling of 'maxFiles' with it:
                      'full'  (default) hunks - 17-25 KiB per file, up to 500 files
                      'stats' statistics only, no hunk text - 0.6 KiB per file, up to 5000
                      'names' path, status and origPath only, NO content read at all,
                              up to 20000 - the way to ask "what is different?" for a whole tree
                    Raise 'maxFiles' explicitly to see the whole tree; the default is 50.
                    Each entry of 'files' has the same shape diff.files produces (same, stats,
                    hunks, files: {old, new}), plus 'path' and 'status'. 'status' is git's
                    (M, A, D, R100): renames are git's decision, not a heuristic of this module.
                    'files.old' is null for a side that does not exist (a new or a deleted file),
                    never an empty file. A side from a revision carries the git blob id ('id'), a
                    side from the working tree its sha256.
                    'binary: true' means one side looked binary: then there are no hunks, but both
                    sides are still described. 'patch: true' adds the unified patch in git style
                    (with the index line) - the text 'git apply' would take.
                    'detail: stats' answers with the statistics only (no hunk text, hunks: []) -
                    the cheap overview of a big change; 'detail: names' answers with 'path',
                    'status' and 'origPath' only and reads no content, so it is the one mode that
                    can list a whole tree.

                --- The configured server (operator decision, not yours) ---

                git.hub.info()
                    {configured: true, kind: 'github', serverUrl: 'https://github.com',
                     apiBase: 'https://api.github.com', tokenPresent: true, orgs: [],
                     links: {project, blob, commit, compare, pull, user}, rateLimit: null}
                    The server URL is a system property; you never pass a host or a URL, only
                    owner/repo. A 'ref' is a branch, a tag or a commit id and follows the same
                    revision rules as the local commands. 'links' are templates for building a link
                    to this server, e.g.
                    links.blob = '<server>/{owner}/{repo}/blob/{ref}/{path}' - substitute and use.
                    'rateLimit' is filled as soon as a request was made.
                    Without a configured server: {configured: false, property, hint}, and the
                    functions below do not exist at all (typeof git.hub.pulls === 'undefined').

                git.hub.repo({repo: 'owner/name'})
                    {fullName, defaultBranch, private, archived, sizeKb, pushedAt, htmlUrl,
                     description}

                git.hub.branches({repo, max: 50})        [{name, sha, protected}]

                git.hub.tree({repo, ref, path, max: 500})
                    {path, ref, entries: [{name, path, type: 'blob'|'tree'|'commit', size, sha}],
                     total, truncated}                      one level of a directory

                git.hub.file({repo, ref, path})
                    the same shape git.file produces (blobId is the file's sha, text/lines/eol
                    where the content is text). Above 1 MB the server sends no content: then
                    'truncated: true' and a 'note' say so.

                git.hub.commits({repo, ref, path, max: 50})
                    [{id, shortId, author: {name, email}, time, subject, body, htmlUrl}]
                    The same shape as one entry of git.log.

                git.hub.compare({repo, from, to, diff: false, maxFiles: 50, patch: false})
                    {repo, from, to, status: 'ahead'|'behind'|'diverged'|'identical',
                     aheadBy, behindBy, mergeBase,
                     commits: [{...same shape as git.log...}], totalCommits, commitsTruncated,
                     files: [{path, status: 'M'|'A'|'D'|'R'|'C', origPath, additions, deletions}],
                     total, truncated, notes}
                    Compares two revisions ON THE SERVER - no clone, no fetch. The file list is the
                    server's (it finds renames); the old content is read at the merge base, not at
                    'from' - on diverged branches those are different commits.
                    diff: false (default) answers the change set: how far apart, which files, how
                    many lines. diff: true fetches both contents of every listed file (two
                    requests per file) and compares them through the diff engine; each entry then
                    carries content: true plus same/stats/hunks/files like an entry of git.diff. A
                    file above 1 MB has no content on this route: content: false and a note.
                    patch: true (needs diff: true) adds the unified patch with the real blob ids.

                git.hub.compareWithLocal({repo, ref, path, maxBytes, patch})
                    {repo, ref, path, status: 'M'|'A'|'D', same, binary, stats, hunks,
                     files: {old, new}, patch?}
                    The remote file at 'ref' against the file in the project directory, through
                    the diff engine: the old side is the server (its sha is the blob id), the new
                    side is your working copy. status 'D' means "the file is gone here", 'A' means
                    "it exists only here". 'ref' defaults to the default branch.

                --- Pull requests (read-only) ---

                git.hub.pulls({repo, state: 'open'|'closed'|'all', head, base, max: 30})
                    [{number, title, state, draft, author, createdAt, updatedAt,
                      head: {ref, sha, label}, base: {ref, sha, label}, htmlUrl}]
                    'head' is 'owner:branch', 'base' is the branch the pull request targets.

                git.hub.pull({repo, number})
                    the same header plus body, merged, mergeable, mergeableState, closedAt,
                    mergedAt, commits, additions, deletions, changedFiles, comments,
                    reviewComments
                    'mergeable' is null while the server computes it - ask again, it is not "no".

                git.hub.pullFiles({repo, number, max: 30})
                    {number, files: [{path, status, origPath, additions, deletions, patch,
                                      patchTruncated}], total, truncated, notes}
                    'patch' is the SERVER's text - what the pull request shows, for reading, not
                    for applying (no diff is computed here). It is null when the server sends none
                    (binary or too large) and cut at 20000 characters per file otherwise.

                git.hub.pullComments({repo, number, max: 30})
                    {number, comments: [{kind: 'issue'|'review', id, author, createdAt, body,
                                         htmlUrl, path?, line?, side?, commitId?, inReplyToId?,
                                         hunk?}], total, truncated, notes}
                    The conversation and the code review comments, merged in time order.

                Everything here reads. There is no approve, no merge, no comment: the client has
                no method that would send anything but a GET.

                --- Options, paths, budgets ---

                Every function accepts an options object; a misspelled option is an error that
                lists the known ones (a typo must not look like a default). 'repo' names a
                sub-directory of the project that holds the repository (default: the project
                directory itself).

                Paths are relative to the project directory and validated like fs.* (no '..', no
                symlink leaving the project). Result paths are always relative - an absolute path
                never leaves the server.

                Budgets: 200 status records (max 2000), 50 branches (max 500), one git process may
                run 20 s. A list from the server is capped by the operator's 'maxItems' (default
                100): a default above it is lowered to it, and asking for more is an error naming
                that property - a paginated list of unknown length does not land in the context. A
                cut result says so ('truncated'), it never silently shrinks.

                --- Errors ---

                A failing git command reports its exit code and the last lines of stderr; an
                unknown revision is an error, not an empty result.

                --- Help ---

                git.help()   - this text
                """;
    }

    // ========================================================================
    // Internals
    // ========================================================================

    /** One history query: what to look at, the budgets and the compiled pattern. */
    private record Query(GitRepo repo, GitLog.Selection selection, GitLog.Limits limits,
                         Pattern grep) {
    }

    /** One blob request: where and how much. */
    private record BlobRequest(GitRepo repo, String rev, String path, int maxBytes) {
    }

    /**
     * Builds a history query from the options (and rejects everything it does not understand).
     */
    private static Query query(String function, Map<String, Object> options, int defaultMax,
                               int hardMax) throws IOException {
        requireKnownOptions(function, options, Set.of("rev", "from", "to", "path", "follow", "all",
                "firstParent", "author", "grep", "fixedStrings", "since", "until", "files",
                "max", "maxScan", "repo"));
        // Options first, the repository last: a typo must not be answered with "not a git
        // worktree" just because the project happens to have no repository.
        String revRaw = stringOption(options, "rev", null, function);
        String rev = (revRaw == null) ? null : GitCommand.revision(revRaw);
        String from = optionalRevision(options, "from", function);
        String to = optionalRevision(options, "to", function);
        GitLog.requireUsable(rev, from, to);
        boolean follow = booleanOption(options, "follow", false, function);
        boolean all = booleanOption(options, "all", false, function);
        boolean firstParent = booleanOption(options, "firstParent", false, function);
        String author = stringOption(options, "author", null, function);
        String since = dateOption(options, "since", function);
        String until = dateOption(options, "until", function);
        int max = numberOption(options, "max", defaultMax, 1, hardMax, function);
        int maxScan = numberOption(options, "maxScan", DEFAULT_MAX_SCAN, 1, MAX_SCAN, function);
        boolean files = booleanOption(options, "files", false, function);
        Pattern grep = grepOption(options, function);

        GitRepo repo = detect(options);
        String path = pathOption(options, function, repo);
        return new Query(repo, new GitLog.Selection(rev, from, to, path, follow, all, firstParent,
                author, since, until), new GitLog.Limits(max, maxScan, files), grep);
    }

    /** Compiles the {@code grep} option (literal when {@code fixedStrings} is set). */
    private static Pattern grepOption(Map<String, Object> options, String function) {
        String pattern = stringOption(options, "grep", null, function);
        if (pattern == null) {
            return null;
        }
        boolean literal = booleanOption(options, "fixedStrings", false, function);
        try {
            return literal ? Pattern.compile(pattern, Pattern.LITERAL) : Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(function + ": 'grep' is not a valid regular "
                    + "expression (" + e.getDescription() + "). Use {fixedStrings: true} for a "
                    + "literal search.");
        }
    }

    /** Builds a blob request from the options. */
    private static BlobRequest blobRequest(String function, Map<String, Object> options)
            throws IOException {
        String rawPath = stringOption(options, "path", null, function);
        if (rawPath == null) {
            throw new IllegalArgumentException(function + ": the option 'path' is required - see "
                    + "git.help()");
        }
        String rev = revisionOption(options, function);
        int maxBytes = numberOption(options, "maxBytes", DEFAULT_MAX_BYTES, 1, MAX_BYTES, function);
        GitRepo repo = detect(options);
        return new BlobRequest(repo, rev, repo.relative(JsFileSystem.resolveSafePath(rawPath)),
                maxBytes);
    }

    /** Reads and validates the {@code rev} option (default {@code HEAD}). */
    private static String revisionOption(Map<String, Object> options, String function) {
        return GitCommand.revision(stringOption(options, "rev", "HEAD", function));
    }

    /**
     * Reads and validates an optional revision ({@code from}, {@code to}).
     *
     * @param options  the call's options
     * @param key      {@code from} or {@code to}
     * @param function the function name for the error message
     * @return the validated revision, or {@code null} when the option is absent
     */
    private static String optionalRevision(Map<String, Object> options, String key,
                                           String function) {
        String raw = stringOption(options, key, null, function);
        return (raw == null) ? null : GitCommand.revision(raw);
    }

    /**
     * Reads the {@code path} option as a path relative to the repository root (or {@code null}).
     * The project-relative form is resolved like {@code fs.*} first, so '..' and a symlink leaving
     * the project are refusals and not a git question.
     */
    private static String pathOption(Map<String, Object> options, String function, GitRepo repo) {
        String raw = stringOption(options, "path", null, function);
        if (raw == null) {
            return null;
        }
        return repo.relative(JsFileSystem.resolveSafePath(raw));
    }

    /** Reads and validates a date option into the {@code @<epoch>} form git accepts. */
    private static String dateOption(Map<String, Object> options, String key, String function) {
        String raw = stringOption(options, key, null, function);
        return (raw == null) ? null : GitCommand.date(raw, key);
    }

    /** The answer shape of {@code git.log} and {@code git.eachCommit}. */
    private static Map<String, Object> historyResult(List<Map<String, Object>> commits,
                                                     GitLog.Outcome outcome,
                                                     GitLog.Selection selection) {
        Map<String, Object> result = new LinkedHashMap<>();
        // The two sides of the question, with 'to' resolved: a caller who wrote only 'from' sees
        // that HEAD was the other side.
        result.put("from", selection.from());
        result.put("to", GitLog.startRevision(selection));
        result.put("commits", commits);
        result.put("scanned", outcome.scanned());
        result.put("matched", outcome.matched());
        result.put("truncated", outcome.truncated());
        result.put("stopped", outcome.stopped());
        result.put("notes", outcome.notes());
        return result;
    }

    /** Converts one commit into the shape a script sees. */
    private static Map<String, Object> commitToMap(GitLog.Commit commit, boolean withFiles) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", commit.id());
        map.put("shortId", commit.shortId());
        map.put("parents", commit.parents());
        Map<String, Object> author = new LinkedHashMap<>();
        author.put("name", commit.authorName());
        author.put("email", commit.authorEmail());
        map.put("author", author);
        map.put("time", Instant.ofEpochSecond(commit.epochSeconds()).toString());
        map.put("subject", commit.subject());
        map.put("body", commit.body().isBlank() ? null : commit.body());
        if (withFiles) {
            List<Object> files = new ArrayList<>();
            for (GitLog.FileEntry entry : commit.files()) {
                Map<String, Object> file = new LinkedHashMap<>();
                file.put("status", entry.status());
                file.put("path", entry.path());
                file.put("origPath", entry.origPath());
                files.add(file);
            }
            map.put("files", files);
        }
        return map;
    }

    /**
     * Resolves the repository to work on: the {@code repo} option (a sub-directory of the project)
     * or the project directory itself. Both go through the same path rules as {@code fs.*}.
     */
    private static GitRepo detect(Map<String, Object> options) throws IOException {
        String sub = stringOption(options, "repo", null, "git");
        Path base;
        if (sub == null) {
            base = JsFileSystem.projectBase();
        } else {
            Path resolved = JsFileSystem.resolveSafePath(sub);
            if (!Files.isDirectory(resolved)) {
                throw new IllegalArgumentException("'repo' is not a directory: '"
                        + JsFileSystem.toRelative(resolved) + "'");
            }
            base = resolved;
        }
        return GitRepo.detect(base);
    }

    /**
     * Rejects unknown option names instead of ignoring them (a typo would otherwise look like a
     * default).
     */
    private static void requireKnownOptions(String function, Map<String, Object> options,
                                            Set<String> known) {
        if (options == null) {
            return;
        }
        for (String key : options.keySet()) {
            if (!known.contains(key)) {
                throw new IllegalArgumentException(function + ": unknown option '" + key
                        + "' (known: " + String.join(", ", known.stream().sorted().toList())
                        + ") - see git.help()");
            }
        }
    }

    private static String stringOption(Map<String, Object> options, String key, String absent,
                                       String function) {
        Object value = (options == null) ? null : options.get(key);
        if (value == null) {
            return absent;
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(function + ": option '" + key
                    + "' must be a string - see git.help()");
        }
        return GitCommand.pattern(text, key);
    }

    private static boolean booleanOption(Map<String, Object> options, String key, boolean absent,
                                         String function) {
        Object value = (options == null) ? null : options.get(key);
        if (value == null) {
            return absent;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw new IllegalArgumentException(function + ": option '" + key
                + "' must be true or false - see git.help()");
    }

    private static int numberOption(Map<String, Object> options, String key, int absent, int min,
                                    int max, String function) {
        Object value = (options == null) ? null : options.get(key);
        if (value == null) {
            return absent;
        }
        long number;
        if (value instanceof Number n) {
            number = n.longValue();
        } else if (value instanceof String text) {
            try {
                number = Long.parseLong(text.strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(function + ": option '" + key
                        + "' must be a number between " + min + " and " + max);
            }
        } else {
            throw new IllegalArgumentException(function + ": option '" + key
                    + "' must be a number between " + min + " and " + max);
        }
        if (number < min || number > max) {
            throw new IllegalArgumentException(function + ": option '" + key
                    + "' must be between " + min + " and " + max + ", but was: " + number);
        }
        return (int) number;
    }
}
