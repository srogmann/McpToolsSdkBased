package org.rogmann.mcp2sdk.git;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Branches and remotes of a repository - the answer to "where are we, and where does it come from".
 *
 * <p>
 * Both come from machine formats: {@code for-each-ref} with a NUL-separated format (branches with
 * id, upstream and ahead/behind, which {@code branch -vv} would only offer as painted text) and
 * {@code remote -v}, whose output is grouped into one entry per remote.
 * </p>
 *
 * <p>
 * <b>Redaction.</b> A configured remote URL may carry credentials
 * ({@code https://user:token@host/repo.git}); {@link #redactUrl(String)} replaces the secret and
 * leaves the rest untouched, because the host and the path are what a caller needs to see.
 * </p>
 */
public final class GitRefs {

    /**
     * One remote, with the URLs of the fetch and the push side.
     *
     * @param name     remote name (e.g. {@code origin})
     * @param fetchUrl URL used for fetching, {@code null} if none is configured
     * @param pushUrl  URL used for pushing, {@code null} if none is configured
     * @param redacted true if credentials were removed from a URL
     */
    public record Remote(String name, String fetchUrl, String pushUrl, boolean redacted) {
    }

    /**
     * One branch (local or remote-tracking).
     *
     * @param name     branch name without the {@code refs/heads/} prefix
     * @param id       commit id
     * @param shortId  abbreviated commit id
     * @param upstream name of the tracked branch, {@code null} if none
     * @param ahead    commits ahead of the upstream, {@code null} if not tracked
     * @param behind   commits behind the upstream, {@code null} if not tracked
     * @param gone     true if an upstream is configured but no longer exists ({@code [gone]});
     *                 then {@code ahead} and {@code behind} are {@code null} <em>because the
     *                 upstream is gone</em>, not because nothing changed
     * @param current  true for the checked-out branch
     */
    public record BranchRef(String name, String id, String shortId, String upstream, Integer ahead,
                            Integer behind, boolean gone, boolean current) {
    }

    /** Format of one branch record: fields separated by NUL, records by newline. */
    private static final String BRANCH_FORMAT =
            "--format=%(refname:short)%00%(objectname)%00%(objectname:short)%00"
            + "%(upstream:short)%00%(upstream:track)%00%(HEAD)";

    private GitRefs() {
    }

    /**
     * Reads the local or the remote-tracking branches.
     *
     * @param repo   the repository
     * @param remote true for {@code refs/remotes}, false for {@code refs/heads}
     * @param max    maximum number of branches (git stops after that many, sorted by name)
     * @return the branches, sorted by name
     * @throws IOException if git fails
     */
    public static List<BranchRef> readBranches(GitRepo repo, boolean remote, int max)
            throws IOException {
        List<String> arguments = List.of("for-each-ref", BRANCH_FORMAT, "--sort=refname",
                "--count=" + max, remote ? "refs/remotes" : "refs/heads");
        return parseBranches(GitProcess.run(repo.root(), arguments).stdout());
    }

    /**
     * Reads the configured remotes.
     *
     * @param repo the repository
     * @return one entry per remote, in configuration order
     * @throws IOException if git fails
     */
    public static List<Remote> readRemotes(GitRepo repo) throws IOException {
        return parseRemotes(GitProcess.run(repo.root(), List.of("remote", "-v")).stdout());
    }

    /**
     * Parses {@code for-each-ref} output in {@link #BRANCH_FORMAT}.
     *
     * @param output the raw output
     * @return the branches
     * @throws IllegalArgumentException if a tracking field has a shape this parser does not know
     */
    public static List<BranchRef> parseBranches(String output) {
        List<BranchRef> branches = new ArrayList<>();
        for (String line : splitLines(output)) {
            String[] fields = line.split("\u0000", -1);
            if (fields.length < 6) {
                throw new IllegalArgumentException("Unexpected for-each-ref record: '" + line + "'");
            }
            Integer ahead = null;
            Integer behind = null;
            boolean gone = false;
            String track = fields[4];
            if (track.startsWith("[") && track.endsWith("]")) {
                for (String part : track.substring(1, track.length() - 1).split(", ")) {
                    if (part.startsWith("ahead ")) {
                        ahead = GitParse.requireCount(part.substring(6), "upstream:track ahead");
                    } else if (part.startsWith("behind ")) {
                        behind = GitParse.requireCount(part.substring(7), "upstream:track behind");
                    } else if ("gone".equals(part)) {
                        // The upstream ref no longer exists: ahead/behind are not "0", they are
                        // unknown - which the caller must be able to tell apart from "nothing
                        // to push".
                        gone = true;
                    } else {
                        throw new IllegalArgumentException("Unexpected value in git output "
                                + "(upstream:track): '" + part + "' in '" + track + "'");
                    }
                }
            }
            branches.add(new BranchRef(fields[0], fields[1], fields[2],
                    fields[3].isEmpty() ? null : fields[3], ahead, behind, gone,
                    "*".equals(fields[5])));
        }
        return branches;
    }

    /**
     * Parses {@code git remote -v} output.
     *
     * @param output the raw output
     * @return one entry per remote, in the order of first appearance
     */
    public static List<Remote> parseRemotes(String output) {
        Map<String, String[]> byName = new LinkedHashMap<>();
        for (String line : splitLines(output)) {
            int tab = line.indexOf('\t');
            int bracket = line.lastIndexOf(" (");
            if (tab <= 0 || bracket <= tab || !line.endsWith(")")) {
                continue;
            }
            String name = line.substring(0, tab);
            String url = line.substring(tab + 1, bracket);
            String kind = line.substring(bracket + 2, line.length() - 1);
            String[] urls = byName.computeIfAbsent(name, key -> new String[2]);
            if ("fetch".equals(kind)) {
                urls[0] = url;
            } else if ("push".equals(kind)) {
                urls[1] = url;
            }
        }
        List<Remote> remotes = new ArrayList<>();
        for (Map.Entry<String, String[]> entry : byName.entrySet()) {
            String fetch = entry.getValue()[0];
            String push = entry.getValue()[1];
            String redactedFetch = redactUrl(fetch);
            String redactedPush = redactUrl(push);
            boolean redacted = !equalsNullSafe(fetch, redactedFetch)
                    || !equalsNullSafe(push, redactedPush);
            remotes.add(new Remote(entry.getKey(), redactedFetch, redactedPush, redacted));
        }
        return remotes;
    }

    /**
     * Removes the password/token from a URL, keeping scheme, user, host and path.
     *
     * <p>
     * Handles the HTTP(S) and ssh forms with userinfo ({@code https://user:token@host/path}) and
     * the scp-like ssh form ({@code git@host:path}), which carries no secret and is returned
     * unchanged.
     * </p>
     *
     * @param url the configured URL, may be {@code null}
     * @return the URL without a password, or {@code null}
     */
    public static String redactUrl(String url) {
        if (url == null) {
            return null;
        }
        int schemeEnd = url.indexOf("://");
        if (schemeEnd <= 0) {
            return url;
        }
        int authorityStart = schemeEnd + 3;
        int at = url.indexOf('@', authorityStart);
        if (at < 0) {
            return url;
        }
        int slash = url.indexOf('/', authorityStart);
        if (slash >= 0 && slash < at) {
            // the '@' belongs to the path, not to the authority
            return url;
        }
        String userInfo = url.substring(authorityStart, at);
        int colon = userInfo.indexOf(':');
        if (colon < 0) {
            // only a user name, no secret
            return url;
        }
        return url.substring(0, authorityStart) + userInfo.substring(0, colon) + ":***"
                + url.substring(at);
    }

    private static boolean equalsNullSafe(String left, String right) {
        return (left == null) ? right == null : left.equals(right);
    }

    private static List<String> splitLines(String output) {
        List<String> lines = new ArrayList<>();
        if (output == null) {
            return lines;
        }
        for (String line : output.split("\n", -1)) {
            String stripped = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (!stripped.isBlank()) {
                lines.add(stripped);
            }
        }
        return lines;
    }
}
