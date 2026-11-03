package org.rogmann.mcp2sdk.git;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The working state of a repository, read from git's machine format
 * ({@code status --porcelain=v2 -z --branch}) and parsed without a process.
 *
 * <p>
 * Version 2 of the porcelain format is what makes this cheap and unambiguous: one record per path
 * with the two status characters <em>separate</em> for index and working tree, submodule state,
 * renames with their source path, and a branch header that already carries the commit id, the
 * upstream and the ahead/behind counts. No second call is needed to answer "where are we", and no
 * human-oriented {@code git status} text has to be interpreted.
 * </p>
 *
 * <p>
 * The parser knows no process at all, so it is tested against recorded output; the {@code -z}
 * record separator (NUL) is what makes paths with spaces, quotes or newlines safe.
 * </p>
 */
public final class GitStatus {

    /**
     * One record of the working state.
     *
     * @param path      path relative to the repository root
     * @param origPath  source path of a rename or copy, {@code null} otherwise
     * @param index     status character of the index ({@code '.'} = unchanged)
     * @param worktree  status character of the working tree ({@code '.'} = unchanged)
     * @param kind      {@code changed}, {@code renamed}, {@code copied}, {@code unmerged} or
     *                  {@code untracked}
     * @param submodule the submodule state field ({@code N...} when it is not a submodule)
     */
    public record Entry(String path, String origPath, char index, char worktree, String kind,
                        String submodule) {
    }

    /**
     * The branch header.
     *
     * @param oid      commit id of {@code HEAD}, {@code null} before the first commit
     * @param head     branch name, {@code null} when detached
     * @param upstream upstream branch name, {@code null} when none is configured
     * @param ahead    commits ahead of the upstream, {@code null} when unknown
     * @param behind   commits behind the upstream, {@code null} when unknown
     * @param initial  true before the first commit
     * @param detached true when {@code HEAD} is detached
     */
    public record Branch(String oid, String head, String upstream, Integer ahead, Integer behind,
                         boolean initial, boolean detached) {
    }

    /**
     * Counts over all records (not only the returned ones).
     *
     * @param staged     paths with an index change
     * @param unstaged   paths with a working-tree change
     * @param untracked  untracked paths
     * @param conflicted paths in an unresolved merge
     */
    public record Counts(int staged, int unstaged, int untracked, int conflicted) {
    }

    /**
     * The whole working state.
     *
     * @param branch    the branch header
     * @param entries   the records, at most {@code maxFiles} of them
     * @param counts    the counts over all records
     * @param total     number of records found
     * @param truncated true if {@code entries} holds fewer records than {@code total}
     */
    public record Status(Branch branch, List<Entry> entries, Counts counts, int total,
                         boolean truncated) {
    }

    private GitStatus() {
    }

    /**
     * Reads the working state of a repository.
     *
     * @param repo      the repository
     * @param untracked {@code normal}, {@code all} or {@code no}
     * @param maxFiles  maximum number of records returned
     * @return the state
     * @throws IOException if git fails or its output exceeds the budget
     */
    public static Status read(GitRepo repo, String untracked, int maxFiles) throws IOException {
        String mode = GitCommand.oneOf(untracked, "normal", "untracked",
                List.of("normal", "all", "no"));
        List<String> arguments = List.of("status", "--porcelain=v2", "-z", "--branch",
                "--untracked-files=" + mode, "--ignored=no");
        GitProcess.Result result = GitProcess.run(repo.root(), arguments);
        return parse(result.stdout(), maxFiles);
    }

    /**
     * Parses {@code status --porcelain=v2 -z --branch} output.
     *
     * @param porcelain the raw output
     * @param maxFiles  maximum number of records returned
     * @return the state
     * @throws IllegalArgumentException if the output contains a record this parser does not know
     */
    public static Status parse(String porcelain, int maxFiles) {
        if (porcelain == null) {
            throw new IllegalArgumentException("git status output must not be null");
        }
        List<Entry> entries = new ArrayList<>();
        List<String> headerLines = new ArrayList<>();

        String[] tokens = porcelain.split("\u0000", -1);
        for (int i = 0; i < tokens.length; i++) {
            String token = stripLineEnd(tokens[i]);
            if (token.isEmpty()) {
                continue;
            }
            if (token.startsWith("#")) {
                // Whether git terminates a header with NUL or LF under -z is not something this
                // parser should depend on: a header token may carry several lines.
                for (String line : token.split("\n")) {
                    String header = stripLineEnd(line);
                    if (!header.isBlank()) {
                        headerLines.add(header);
                    }
                }
                continue;
            }
            if (token.startsWith("1 ")) {
                String[] fields = token.split(" ", 9);
                if (fields.length < 9) {
                    throw malformed(token);
                }
                entries.add(new Entry(fields[8], null, fields[1].charAt(0), fields[1].charAt(1),
                        "changed", fields[2]));
            } else if (token.startsWith("2 ")) {
                String[] fields = token.split(" ", 10);
                if (fields.length < 10 || i + 1 >= tokens.length) {
                    throw malformed(token);
                }
                String origPath = stripLineEnd(tokens[++i]);
                char status = fields[8].charAt(0);
                String kind = (status == 'C') ? "copied" : "renamed";
                entries.add(new Entry(fields[9], origPath, fields[1].charAt(0),
                        fields[1].charAt(1), kind, fields[2]));
            } else if (token.startsWith("u ")) {
                String[] fields = token.split(" ", 11);
                if (fields.length < 11) {
                    throw malformed(token);
                }
                entries.add(new Entry(fields[10], null, fields[1].charAt(0), fields[1].charAt(1),
                        "unmerged", fields[2]));
            } else if (token.startsWith("? ")) {
                entries.add(new Entry(token.substring(2), null, '.', '.', "untracked", "N..."));
            } else if (token.startsWith("! ")) {
                // ignored - never requested (--ignored=no), but harmless if it appears
                continue;
            } else {
                throw malformed(token);
            }
        }

        int total = entries.size();
        List<Entry> kept = (maxFiles >= 0 && total > maxFiles)
                ? List.copyOf(entries.subList(0, maxFiles))
                : List.copyOf(entries);
        Branch branch = parseHeader(headerLines);
        return new Status(branch, kept, count(entries), total, kept.size() < total);
    }

    /**
     * Builds the branch description from the {@code # branch.*} header lines.
     *
     * @param headers the header lines (any order)
     * @return the branch description
     */
    private static Branch parseHeader(List<String> headers) {
        String oid = null;
        String head = null;
        String upstream = null;
        Integer ahead = null;
        Integer behind = null;
        boolean initial = false;
        boolean detached = false;
        for (String header : headers) {
            if (header.startsWith("# branch.oid ")) {
                String value = header.substring(13);
                if ("(initial)".equals(value)) {
                    initial = true;
                } else {
                    oid = value;
                }
            } else if (header.startsWith("# branch.head ")) {
                String value = header.substring(14);
                if ("(detached)".equals(value)) {
                    detached = true;
                } else {
                    head = value;
                }
            } else if (header.startsWith("# branch.upstream ")) {
                upstream = header.substring(18);
            } else if (header.startsWith("# branch.ab ")) {
                String[] ab = header.substring(12).split(" ");
                if (ab.length != 2 || !ab[0].startsWith("+") || !ab[1].startsWith("-")) {
                    throw new IllegalArgumentException("Unexpected git status header: '" + header
                            + "' (expected '# branch.ab +<ahead> -<behind>')");
                }
                ahead = GitParse.requireCount(ab[0].substring(1), "branch.ab ahead");
                behind = GitParse.requireCount(ab[1].substring(1), "branch.ab behind");
            }
            // other headers (e.g. a stash count in a newer git) are ignored on purpose
        }
        return new Branch(oid, head, upstream, ahead, behind, initial, detached);
    }

    /** Counts over all records, so a truncated list still reports the true numbers. */
    private static Counts count(List<Entry> entries) {
        int staged = 0;
        int unstaged = 0;
        int untracked = 0;
        int conflicted = 0;
        for (Entry entry : entries) {
            switch (entry.kind()) {
                case "unmerged" -> conflicted++;
                case "untracked" -> untracked++;
                default -> {
                    if (isChanged(entry.index())) {
                        staged++;
                    }
                    if (isChanged(entry.worktree())) {
                        unstaged++;
                    }
                }
            }
        }
        return new Counts(staged, unstaged, untracked, conflicted);
    }

    private static boolean isChanged(char status) {
        return status != '.' && status != ' ';
    }

    private static String stripLineEnd(String token) {
        String result = token;
        if (result.endsWith("\n")) {
            result = result.substring(0, result.length() - 1);
        }
        if (result.endsWith("\r")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static IllegalArgumentException malformed(String token) {
        String shown = token.length() > 60 ? token.substring(0, 60) + "..." : token;
        return new IllegalArgumentException("Unexpected git status v2 record: '" + shown + "'");
    }
}
