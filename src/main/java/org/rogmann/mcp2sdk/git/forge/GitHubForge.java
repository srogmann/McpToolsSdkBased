package org.rogmann.mcp2sdk.git.forge;

import org.rogmann.mcp2sdk.git.GitObjects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The read side of a GitHub server - github.com and a GitHub Enterprise instance through the same
 * code, because they speak the same REST API (only the base URL differs).
 *
 * <p>
 * Everything here reads: repository description, branches, one directory level, one file, the
 * commit list, the comparison of two revisions and the pull requests. Nothing approves, merges,
 * comments or writes - that is not a missing feature but the contract of {@link ForgeHttp}, which
 * only knows {@code GET}.
 * </p>
 *
 * <p>
 * The shapes are the ones the local commands already use: a file is described like {@code git.file}
 * (blob id, size, lines, line endings, text or binary), a commit like one entry of {@code git.log},
 * a changed file carries git's status letters ({@code A}, {@code D}, {@code M}, {@code R},
 * {@code C}) instead of the server's words ({@code added}, {@code removed}, ...). A caller
 * therefore learns one set of shapes, not two.
 * </p>
 *
 * <p>
 * <b>The server's patch text is never the comparison.</b> A comparison in the sense of this module
 * is a file list plus the content of both sides; the hunks are computed by the diff engine from
 * those contents, not read out of a patch string. The one exception is a pull request: there the
 * patch is what the server shows the reviewer, so {@code pullFiles} passes it on unchanged - for
 * reading, not for applying.
 * </p>
 */
public final class GitHubForge {

    /** The one JSON mapper of this class (Jackson 3, as everywhere in this project). */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The server caps the file list of one comparison at this number. */
    private static final int COMPARE_FILE_CAP = 300;

    /** Characters of one pull-request patch handed on to a caller. */
    private static final int MAX_PATCH_CHARS = 20_000;

    private final ForgeHttp http;

    /**
     * One changed file of a revision comparison, as the server reports it.
     *
     * @param status    the status in git's letters ({@code A}, {@code D}, {@code M}, {@code R},
     *                  {@code C}); a value the server invents stays as it came
     * @param path      the (new) path
     * @param origPath  the source path of a rename or copy, {@code null} otherwise
     * @param additions number of added lines
     * @param deletions number of removed lines
     */
    public record ChangedFile(String status, String path, String origPath, int additions,
            int deletions) {

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
     * One revision comparison as the server describes it: the two ends, the merge base, the commits
     * in between and the changed files.
     *
     * <p>
     * {@code total} counts the files the server listed, not the files of the repository - a
     * comparison lists at most {@value #COMPARE_FILE_CAP} of them, which {@link #notes()} says.
     * </p>
     *
     * @param status           {@code ahead}, {@code behind}, {@code diverged} or {@code identical}
     * @param aheadBy          commits the target is ahead of the base
     * @param behindBy         commits the target is behind the base
     * @param mergeBase        commit the two revisions were compared from, or {@code null}
     * @param commits          the commits between the revisions, in the shape {@code git.log} uses
     * @param totalCommits     number of commits between the revisions, as the server counts them
     * @param commitsTruncated true if the server delivered fewer commits than it counted
     * @param files            the changed files, at most {@code maxFiles} of them
     * @param total            number of changed files the server listed
     * @param truncated        true if {@code files} holds fewer files than {@code total}
     * @param notes            human-readable remarks, never {@code null}
     */
    public record Comparison(String status, int aheadBy, int behindBy, String mergeBase,
            List<Map<String, Object>> commits, int totalCommits, boolean commitsTruncated,
            List<ChangedFile> files, int total, boolean truncated, List<String> notes) {
    }

    /**
     * @param http the client that reaches the configured server
     */
    public GitHubForge(ForgeHttp http) {
        this.http = http;
    }

    /**
     * @return the client, for its rate-limit state
     */
    public ForgeHttp http() {
        return http;
    }

    // ========================================================================
    // Repository
    // ========================================================================

    /**
     * Reads the description of a repository.
     *
     * @param repo the repository
     * @return {@code {fullName, defaultBranch, private, sizeKb, pushedAt, htmlUrl, description,
     *         archived}}
     * @throws IOException if the answer is not usable
     */
    public Map<String, Object> repo(ForgeRepo repo) throws IOException {
        JsonNode node = JSON.readTree(http.get(repo.apiPath()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fullName", node.path("full_name").asString(repo.fullName()));
        result.put("defaultBranch", node.path("default_branch").asString(""));
        result.put("private", node.path("private").asBoolean(false));
        result.put("archived", node.path("archived").asBoolean(false));
        result.put("sizeKb", node.path("size").asLong(-1));
        result.put("pushedAt", node.path("pushed_at").asString(""));
        result.put("htmlUrl", node.path("html_url").asString(repo.htmlUrl()));
        result.put("description", node.path("description").asString(""));
        return result;
    }

    // ========================================================================
    // Files and revisions
    // ========================================================================

    /**
     * Lists the branches.
     *
     * @param repo the repository
     * @param max  maximum number of branches
     * @return {@code [{name, sha, protected}]}
     * @throws IOException if the answer is not usable
     */
    public List<Object> branches(ForgeRepo repo, int max) throws IOException {
        JsonNode array = JSON.readTree(http.get(repo.apiPath() + "/branches?per_page=" + max));
        List<Object> branches = new ArrayList<>();
        for (JsonNode entry : requireArray(array, "the branch list")) {
            Map<String, Object> branch = new LinkedHashMap<>();
            branch.put("name", entry.path("name").asString(""));
            branch.put("sha", entry.path("commit").path("sha").asString(""));
            branch.put("protected", entry.path("protected").asBoolean(false));
            branches.add(branch);
        }
        return branches;
    }

    /**
     * Lists one level of a directory.
     *
     * @param repo the repository
     * @param ref  the branch, tag or commit id ({@code null} for the default branch)
     * @param path the directory ({@code null} or {@code ""} for the root)
     * @param max  maximum number of entries
     * @return {@code {path, ref, entries:[{name, path, type, size, sha}], total, truncated}}
     * @throws IOException if git-like ref/path do not exist or the object is not a directory
     */
    public Map<String, Object> tree(ForgeRepo repo, String ref, String path, int max)
            throws IOException {
        JsonNode node = JSON.readTree(http.get(contentsUrl(repo, ref, path)));
        if (!node.isArray()) {
            throw new IOException("'" + displayPath(path) + "' is a file at '" + displayRef(ref)
                    + "', not a directory - use git.hub.file() for one file.");
        }
        JsonNode array = requireArray(node, "the directory listing");
        List<Object> entries = new ArrayList<>();
        int total = array.size();
        for (JsonNode entry : array) {
            if (entries.size() >= max) {
                break;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", entry.path("name").asString(""));
            item.put("path", entry.path("path").asString(""));
            item.put("type", typeOf(entry.path("type").asString("")));
            item.put("size", entry.path("size").isNumber() ? entry.path("size").asLong() : null);
            item.put("sha", entry.path("sha").asString(""));
            entries.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", displayPath(path));
        result.put("ref", displayRef(ref));
        result.put("entries", entries);
        result.put("total", total);
        result.put("truncated", entries.size() < total);
        return result;
    }

    /**
     * Reads one file.
     *
     * @param repo the repository
     * @param ref  the branch, tag or commit id ({@code null} for the default branch)
     * @param path the file path
     * @return the same shape {@code git.file} produces, plus {@code note} when the server could not
     *         deliver the content
     * @throws IOException if the path does not exist or the answer is not usable
     */
    public Map<String, Object> file(ForgeRepo repo, String ref, String path) throws IOException {
        GitObjects.Blob blob = blob(repo, ref, path);
        if (!blob.truncated()) {
            return blob.toMap();
        }
        // The contents API stops delivering content above 1 MB (encoding "none", empty body).
        // Answering with an empty text would read as "the file is empty" - it is not.
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", path);
        result.put("rev", displayRef(ref));
        result.put("blobId", blob.id());
        result.put("size", blob.size());
        result.put("bytes", 0);
        result.put("truncated", Boolean.TRUE);
        result.put("binary", null);
        result.put("lines", null);
        result.put("eol", null);
        result.put("finalNewline", null);
        result.put("bom", null);
        result.put("text", null);
        result.put("note", "The server delivers file content only up to 1 MB (this file has "
                + blob.size() + " bytes). Use git.hub.tree() for the metadata, or read it locally "
                + "with git.file() if the project has the same revision.");
        return result;
    }

    /**
     * Reads one file as a blob, with the server's {@code sha} as its id - that id <em>is</em> the
     * git blob id, which is why a remote side can stand in a patch's {@code index} line.
     *
     * @param repo the repository
     * @param ref  the branch, tag or commit id ({@code null} for the default branch)
     * @param path the file path
     * @return the blob; {@code truncated} is true when the server sent no content (above 1 MB)
     * @throws IOException if the path does not exist or it is a directory
     */
    public GitObjects.Blob blob(ForgeRepo repo, String ref, String path) throws IOException {
        GitObjects.Blob blob = blobOrNull(repo, ref, path);
        if (blob == null) {
            throw new IOException("There is no file '" + displayPath(path) + "' at '"
                    + displayRef(ref) + "' in " + repo.fullName() + ". Check the revision and the "
                    + "path (relative, without a leading '/'); git.hub.tree() shows what is there.");
        }
        return blob;
    }

    /**
     * Reads one file as a blob, or answers {@code null} when the server does not know that path at
     * that revision - the normal case on one side of an added or deleted file.
     *
     * @param repo the repository
     * @param ref  the branch, tag or commit id ({@code null} for the default branch)
     * @param path the file path
     * @return the blob, or {@code null} if the path does not exist at that revision
     * @throws IOException if the answer is not usable or the object is a directory
     */
    public GitObjects.Blob blobOrNull(ForgeRepo repo, String ref, String path) throws IOException {
        String body = http.getOrNull(contentsUrl(repo, ref, path));
        if (body == null) {
            return null;
        }
        JsonNode node = JSON.readTree(body);
        if (node.isArray()) {
            throw new IOException("'" + displayPath(path) + "' is a directory at '"
                    + displayRef(ref) + "' - use git.hub.tree() for a directory.");
        }
        String sha = node.path("sha").asString("");
        long size = node.path("size").asLong(-1);
        if (!"base64".equals(node.path("encoding").asString(""))) {
            // No content above 1 MB: the id and the size stay, the bytes do not exist here.
            return new GitObjects.Blob(path, displayRef(ref), sha, new byte[0], size, true);
        }
        byte[] data = Base64.getMimeDecoder().decode(node.path("content").asString(""));
        return new GitObjects.Blob(path, displayRef(ref), sha, data, data.length, false);
    }

    /**
     * Checks that the server knows a revision.
     *
     * <p>
     * Needed where a missing file and a misspelled branch produce the same 404: without this check
     * a typo in {@code ref} would be reported as "the file was deleted", which is a wrong answer to
     * a question nobody asked.
     * </p>
     *
     * @param repo the repository
     * @param ref  the branch, tag or commit id
     * @throws IOException if the server does not know the revision
     */
    public void requireRevision(ForgeRepo repo, String ref) throws IOException {
        String body = http.getOrNull(repo.apiPath() + "/commits/" + encode(ref));
        if (body == null) {
            throw new IOException("There is no revision '" + ref + "' in " + repo.fullName()
                    + ". Check the branch, the tag or the commit id - git.hub.branches() lists the"
                    + " branches.");
        }
        // Parsed, so a 200 with something that is not JSON does not pass as "the revision exists".
        JSON.readTree(body);
    }

    // ========================================================================
    // Commits
    // ========================================================================

    /**
     * Lists the last commits.
     *
     * @param repo the repository
     * @param ref  the branch, tag or commit id ({@code null} for the default branch)
     * @param path an optional path to filter by
     * @param max  maximum number of commits
     * @return one entry per commit, in the shape {@code git.log} uses
     * @throws IOException if the answer is not usable
     */
    public List<Object> commits(ForgeRepo repo, String ref, String path, int max) throws IOException {
        StringBuilder url = new StringBuilder(repo.apiPath()).append("/commits?per_page=").append(max);
        if (ref != null) {
            url.append("&sha=").append(encode(ref));
        }
        if (path != null) {
            url.append("&path=").append(encode(path));
        }
        JsonNode array = JSON.readTree(http.get(url.toString()));
        List<Object> commits = new ArrayList<>();
        for (JsonNode entry : requireArray(array, "the commit list")) {
            commits.add(commitOf(entry));
        }
        return commits;
    }

    // ========================================================================
    // Comparison
    // ========================================================================

    /**
     * Compares two revisions the way the server does: {@code base...head}, i.e. from the merge base
     * to {@code head}.
     *
     * <p>
     * The file list is the server's (it detects renames, and it is cheaper than listing two trees
     * here); the merge base travels along, because the old content of a file has to be read at the
     * merge base and not at {@code base} - on diverged branches those are different commits, and
     * reading at {@code base} would produce a diff against content the comparison never saw.
     * </p>
     *
     * @param repo     the repository
     * @param base     the base revision (branch, tag or commit id)
     * @param head     the revision to compare with (branch, tag or commit id)
     * @param maxFiles maximum number of changed files described
     * @return the comparison
     * @throws IOException if the answer is not usable
     */
    public Comparison compare(ForgeRepo repo, String base, String head, int maxFiles)
            throws IOException {
        String url = repo.apiPath() + "/compare/" + encodePath(base) + "..." + encodePath(head);
        JsonNode node = JSON.readTree(http.get(url));
        List<String> notes = new ArrayList<>();

        List<Map<String, Object>> commits = new ArrayList<>();
        JsonNode commitArray = node.path("commits");
        if (commitArray.isArray()) {
            for (JsonNode entry : commitArray) {
                commits.add(commitOf(entry));
            }
        }
        int totalCommits = node.path("total_commits").asInt(commits.size());
        boolean commitsTruncated = totalCommits > commits.size();
        if (commitsTruncated) {
            notes.add("the server lists only part of the commits of a comparison (" + commits.size()
                    + " of " + totalCommits + "); the file list is complete");
        }

        List<ChangedFile> changed = new ArrayList<>();
        JsonNode fileArray = node.path("files");
        if (fileArray.isArray()) {
            for (JsonNode entry : fileArray) {
                changed.add(new ChangedFile(statusLetter(entry.path("status").asString("")),
                        entry.path("filename").asString(""),
                        optionalText(entry, "previous_filename"),
                        entry.path("additions").asInt(0),
                        entry.path("deletions").asInt(0)));
            }
        }
        if (changed.size() >= COMPARE_FILE_CAP) {
            notes.add("the server lists at most " + COMPARE_FILE_CAP + " files of a comparison - "
                    + "there may be more; narrow the range (e.g. compare two nearby tags)");
        }

        int total = changed.size();
        boolean truncated = total > maxFiles;
        List<ChangedFile> kept = truncated ? List.copyOf(changed.subList(0, maxFiles)) : changed;
        if (truncated) {
            notes.add("only " + maxFiles + " of " + total + " changed file(s) are described; raise "
                    + "'maxFiles' or compare a narrower range");
        }
        return new Comparison(node.path("status").asString(""), node.path("ahead_by").asInt(0),
                node.path("behind_by").asInt(0), optionalText(node, "merge_base_commit", "sha"),
                List.copyOf(commits), totalCommits, commitsTruncated, kept, total, truncated,
                List.copyOf(notes));
    }

    // ========================================================================
    // Pull requests
    // ========================================================================

    /**
     * Lists pull requests.
     *
     * @param repo  the repository
     * @param state {@code open}, {@code closed} or {@code all}
     * @param head  optional filter {@code owner:branch}
     * @param base  optional filter: the branch the pull request targets
     * @param max   maximum number of pull requests
     * @return one entry per pull request (the header, without the body)
     * @throws IOException if the answer is not usable
     */
    public List<Object> pulls(ForgeRepo repo, String state, String head, String base, int max)
            throws IOException {
        StringBuilder url = new StringBuilder(repo.apiPath()).append("/pulls?state=")
                .append(encode(state)).append("&per_page=").append(max);
        if (head != null) {
            url.append("&head=").append(encode(head));
        }
        if (base != null) {
            url.append("&base=").append(encode(base));
        }
        JsonNode array = JSON.readTree(http.get(url.toString()));
        List<Object> pulls = new ArrayList<>();
        for (JsonNode entry : requireArray(array, "the pull-request list")) {
            pulls.add(pullHead(entry));
        }
        return pulls;
    }

    /**
     * Reads one pull request: the header plus body, merge state and the counters.
     *
     * @param repo   the repository
     * @param number the pull request number
     * @return the description
     * @throws IOException if the pull request does not exist or the answer is not usable
     */
    public Map<String, Object> pull(ForgeRepo repo, int number) throws IOException {
        JsonNode node = JSON.readTree(http.get(repo.apiPath() + "/pulls/" + number));
        Map<String, Object> pull = pullHead(node);
        pull.put("body", node.path("body").asString(""));
        pull.put("merged", node.path("merged").asBoolean(false));
        pull.put("mergeable", optionalBoolean(node, "mergeable"));
        pull.put("mergeableState", optionalText(node, "mergeable_state"));
        pull.put("closedAt", optionalText(node, "closed_at"));
        pull.put("mergedAt", optionalText(node, "merged_at"));
        pull.put("commits", node.path("commits").asInt(0));
        pull.put("additions", node.path("additions").asInt(0));
        pull.put("deletions", node.path("deletions").asInt(0));
        pull.put("changedFiles", node.path("changed_files").asInt(0));
        pull.put("comments", node.path("comments").asInt(0));
        pull.put("reviewComments", node.path("review_comments").asInt(0));
        return pull;
    }

    /**
     * Lists the files of a pull request, with the patch text the server renders.
     *
     * <p>
     * The patch is passed on as it comes - it is what the reviewer sees on the server, not what
     * this module computed; it is for reading, not for applying. The server sends none for binary
     * files and for files it considers too large ({@code patch} is {@code null} then).
     * </p>
     *
     * @param repo   the repository
     * @param number the pull request number
     * @param max    maximum number of files
     * @return {@code {number, files:[{path, status, origPath, additions, deletions, patch,
     *         patchTruncated}], total, truncated, notes}}
     * @throws IOException if the pull request does not exist or the answer is not usable
     */
    public Map<String, Object> pullFiles(ForgeRepo repo, int number, int max) throws IOException {
        JsonNode array = JSON.readTree(http.get(repo.apiPath() + "/pulls/" + number
                + "/files?per_page=" + max));
        List<Object> files = new ArrayList<>();
        for (JsonNode entry : requireArray(array, "the file list of the pull request")) {
            String patch = optionalText(entry, "patch");
            boolean cut = patch != null && patch.length() > MAX_PATCH_CHARS;
            Map<String, Object> file = new LinkedHashMap<>();
            file.put("path", entry.path("filename").asString(""));
            file.put("status", statusLetter(entry.path("status").asString("")));
            file.put("origPath", optionalText(entry, "previous_filename"));
            file.put("additions", entry.path("additions").asInt(0));
            file.put("deletions", entry.path("deletions").asInt(0));
            file.put("patch", cut
                    ? patch.substring(0, MAX_PATCH_CHARS) + "\n… (patch cut at " + MAX_PATCH_CHARS
                            + " characters)\n"
                    : patch);
            file.put("patchTruncated", cut ? Boolean.TRUE : Boolean.FALSE);
            files.add(file);
        }
        List<String> notes = new ArrayList<>();
        boolean truncated = files.size() >= max;
        if (truncated) {
            notes.add("the list may be cut at 'max' (" + max + " file(s) arrived); raise it to see"
                    + " more");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("number", number);
        result.put("files", files);
        result.put("total", files.size());
        result.put("truncated", truncated);
        result.put("notes", notes);
        return result;
    }

    /**
     * Reads the comments of a pull request: the conversation (issue comments) and the code review
     * comments, merged and sorted by time.
     *
     * @param repo   the repository
     * @param number the pull request number
     * @param max    maximum number of comments
     * @return {@code {number, comments:[…], total, truncated, notes}}
     * @throws IOException if the pull request does not exist or the answer is not usable
     */
    public Map<String, Object> pullComments(ForgeRepo repo, int number, int max) throws IOException {
        JsonNode issueComments = JSON.readTree(http.get(repo.apiPath() + "/issues/" + number
                + "/comments?per_page=" + max));
        JsonNode reviewComments = JSON.readTree(http.get(repo.apiPath() + "/pulls/" + number
                + "/comments?per_page=" + max));
        List<Map<String, Object>> comments = new ArrayList<>();
        for (JsonNode entry : requireArray(issueComments, "the comments of the pull request")) {
            comments.add(commentOf(entry, "issue"));
        }
        for (JsonNode entry : requireArray(reviewComments, "the review comments")) {
            comments.add(commentOf(entry, "review"));
        }
        comments.sort(Comparator.comparing(comment -> Objects.toString(
                comment.get("createdAt"), "")));
        int total = comments.size();
        boolean truncated = total > max;
        List<Object> kept = List.copyOf(truncated ? comments.subList(0, max) : comments);
        List<String> notes = new ArrayList<>();
        if (truncated) {
            notes.add("only " + max + " of " + total + " comment(s) are shown; raise 'max'");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("number", number);
        result.put("comments", kept);
        result.put("total", total);
        result.put("truncated", truncated);
        result.put("notes", notes);
        return result;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** Builds the URL of the contents API for one path at one ref. */
    private static String contentsUrl(ForgeRepo repo, String ref, String path) {
        StringBuilder url = new StringBuilder(repo.apiPath()).append("/contents");
        if (path != null && !path.isBlank() && !".".equals(path)) {
            url.append('/').append(encodePath(path));
        }
        if (ref != null && !ref.isBlank()) {
            url.append("?ref=").append(encode(ref));
        }
        return url.toString();
    }

    /** Maps one commit of a JSON answer to the shape {@code git.log} uses. */
    private static Map<String, Object> commitOf(JsonNode entry) {
        JsonNode commit = entry.path("commit");
        String message = commit.path("message").asString("");
        Map<String, Object> item = new LinkedHashMap<>();
        String id = entry.path("sha").asString("");
        item.put("id", id);
        item.put("shortId", id.length() >= 7 ? id.substring(0, 7) : id);
        Map<String, Object> author = new LinkedHashMap<>();
        author.put("name", commit.path("author").path("name").asString(""));
        author.put("email", commit.path("author").path("email").asString(""));
        item.put("author", author);
        item.put("time", commit.path("author").path("date").asString(""));
        item.put("subject", subjectOf(message));
        item.put("body", bodyOf(message));
        item.put("htmlUrl", entry.path("html_url").asString(""));
        return item;
    }

    /** Maps the header of a pull request (the part the list and the single view share). */
    private static Map<String, Object> pullHead(JsonNode node) {
        Map<String, Object> pull = new LinkedHashMap<>();
        pull.put("number", node.path("number").asInt(0));
        pull.put("title", node.path("title").asString(""));
        pull.put("state", node.path("state").asString(""));
        pull.put("draft", node.path("draft").asBoolean(false));
        pull.put("author", node.path("user").path("login").asString(""));
        pull.put("createdAt", optionalText(node, "created_at"));
        pull.put("updatedAt", optionalText(node, "updated_at"));
        pull.put("head", refOf(node.path("head")));
        pull.put("base", refOf(node.path("base")));
        pull.put("htmlUrl", node.path("html_url").asString(""));
        return pull;
    }

    /** Maps the {@code head} or {@code base} block of a pull request. */
    private static Map<String, Object> refOf(JsonNode node) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("ref", node.path("ref").asString(""));
        ref.put("sha", node.path("sha").asString(""));
        ref.put("label", node.path("label").asString(""));
        return ref;
    }

    /** Maps one comment; review comments carry the file and line they belong to. */
    private static Map<String, Object> commentOf(JsonNode entry, String kind) {
        Map<String, Object> comment = new LinkedHashMap<>();
        comment.put("kind", kind);
        comment.put("id", entry.path("id").asLong(0));
        comment.put("author", entry.path("user").path("login").asString(""));
        comment.put("createdAt", optionalText(entry, "created_at"));
        comment.put("body", entry.path("body").asString(""));
        comment.put("htmlUrl", optionalText(entry, "html_url"));
        if ("review".equals(kind)) {
            comment.put("path", optionalText(entry, "path"));
            comment.put("line", optionalNumber(entry, "line"));
            comment.put("side", optionalText(entry, "side"));
            comment.put("commitId", optionalText(entry, "commit_id"));
            comment.put("inReplyToId", optionalNumber(entry, "in_reply_to_id"));
            comment.put("hunk", optionalText(entry, "diff_hunk"));
        }
        return comment;
    }

    /** Encodes one path segment, keeping the separators. */
    private static String encodePath(String path) {
        StringBuilder encoded = new StringBuilder();
        for (String segment : path.split("/", -1)) {
            if (!encoded.isEmpty()) {
                encoded.append('/');
            }
            encoded.append(encode(segment));
        }
        return encoded.toString();
    }

    /** Encodes one value for a query string or a path segment. */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static JsonNode requireArray(JsonNode node, String what) throws IOException {
        if (node == null || !node.isArray()) {
            throw new IOException("Unexpected answer from the server for " + what
                    + " (no JSON array).");
        }
        return node;
    }

    /** Maps the server's object type to the one {@code ls-tree} prints. */
    private static String typeOf(String type) {
        return switch (type) {
            case "dir" -> "tree";
            case "file" -> "blob";
            case "submodule" -> "commit";
            default -> type;
        };
    }

    /**
     * Maps the server's word for a change to git's letter, so a caller reads {@code M}, {@code A},
     * {@code D} in both the local and the remote comparison. A value this module does not know is
     * passed through unchanged - inventing a letter would hide a new server vocabulary.
     */
    private static String statusLetter(String status) {
        return switch (status) {
            case "added" -> "A";
            case "removed" -> "D";
            case "modified", "changed" -> "M";
            case "renamed" -> "R";
            case "copied" -> "C";
            default -> status;
        };
    }

    /** Reads a text field that may be absent or {@code null}. */
    private static String optionalText(JsonNode node, String... fields) {
        JsonNode current = node;
        for (String field : fields) {
            if (current == null) {
                return null;
            }
            current = current.get(field);
        }
        if (current == null || current.isNull()) {
            return null;
        }
        String text = current.asString("");
        return text.isEmpty() ? null : text;
    }

    /** Reads a boolean field that may be absent or {@code null} (the server computes some late). */
    private static Boolean optionalBoolean(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value.asBoolean();
    }

    /** Reads a number field that may be absent or {@code null}. */
    private static Integer optionalNumber(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value.asInt();
    }

    private static String displayPath(String path) {
        return (path == null || path.isBlank()) ? "." : path;
    }

    private static String displayRef(String ref) {
        return (ref == null || ref.isBlank()) ? "the default branch" : ref;
    }

    private static String subjectOf(String message) {
        int newline = message.indexOf('\n');
        return (newline < 0) ? message.strip() : message.substring(0, newline).strip();
    }

    private static String bodyOf(String message) {
        int newline = message.indexOf('\n');
        if (newline < 0) {
            return null;
        }
        String body = message.substring(newline + 1).strip();
        return body.isEmpty() ? null : body;
    }
}
