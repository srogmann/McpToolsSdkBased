package org.rogmann.mcp2sdk.git;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The repository this module works on: anchored at the project directory, discovered by git
 * itself, and never anywhere else.
 *
 * <p>
 * The anchor is the one {@code fs.*} uses (the project base directory). A repository is accepted
 * only if its root <em>is</em> that directory: a project that happens to sit inside another
 * repository would otherwise be answered by the parent repository - the same class of mistake the
 * browser module had to fix for data paths, and the reason {@code GIT_CEILING_DIRECTORIES} is set
 * in {@link GitProcess} as a second line of defence.
 * </p>
 *
 * <p>
 * Discovery costs one process ({@code rev-parse --show-toplevel --absolute-git-dir}); nothing is
 * cached, because a repository that appears or disappears during a session must be visible
 * immediately and {@code rev-parse} takes milliseconds.
 * </p>
 *
 * @param base   the project directory (the anchor, absolute)
 * @param root   the repository root (absolute, equal to {@code base} after resolving symlinks)
 * @param gitDir the git directory of this worktree, absolute
 */
public record GitRepo(Path base, Path root, Path gitDir) {

    private static final Logger LOG = LoggerFactory.getLogger(GitRepo.class);

    /**
     * Markers of a work in progress, checked by their existence in the git directory. They are
     * plain, long-stable files - the one place where reading the git directory is cheaper and
     * safer than starting a process for it.
     */
    private static final String[][] STATE_MARKERS = {
            {"MERGE_HEAD", "merging"},
            {"rebase-merge", "rebasing"},
            {"rebase-apply", "rebasing"},
            {"CHERRY_PICK_HEAD", "cherry-picking"},
            {"REVERT_HEAD", "reverting"},
            {"BISECT_LOG", "bisecting"}};

    /**
     * Discovers the repository of the given project directory.
     *
     * @param projectBase the project directory (the {@code fs.*} anchor)
     * @return the repository
     * @throws IOException if the directory is not a git worktree, or if the repository that git
     *         found is not rooted at the project directory itself
     */
    public static GitRepo detect(Path projectBase) throws IOException {
        Path base = projectBase.toAbsolutePath().normalize();
        // Discovery deliberately climbs (GitProcess.discover): with the ceiling in place, a
        // project inside another repository would be answered "not a repository" and the real
        // reason would stay hidden. The answer is only compared, never used for data access.
        GitProcess.Result result = GitProcess.discover(base,
                List.of("rev-parse", "--show-toplevel", "--absolute-git-dir"));
        if (result.exitCode() != 0) {
            throw new IOException("The project directory is not a git worktree ("
                    + lastLine(result.stderr()) + "). Git commands need a repository whose root is "
                    + "the project directory.");
        }
        List<String> lines = result.stdout().lines().filter(l -> !l.isBlank()).toList();
        if (lines.size() < 2) {
            throw new IOException("git rev-parse did not report a worktree root and a git "
                    + "directory; the project directory is probably not a git worktree.");
        }
        Path root = Path.of(lines.get(0).strip()).toAbsolutePath().normalize();
        Path gitDir = Path.of(lines.get(1).strip());
        if (!gitDir.isAbsolute()) {
            gitDir = root.resolve(gitDir).normalize();
        }

        Path realBase;
        Path realRoot;
        try {
            realBase = base.toRealPath();
            realRoot = root.toRealPath();
        } catch (IOException e) {
            throw new IOException("Cannot resolve the project or repository directory.", e);
        }
        if (!realRoot.equals(realBase)) {
            throw new IOException("The git repository found is rooted at "
                    + describeRelative(realBase, realRoot) + ", not at the project directory. "
                    + "Only a repository whose root is the project directory is used - a project "
                    + "inside another repository is not answered by that repository.");
        }
        LOG.debug("git repository at {} (git dir {})", base, gitDir);
        return new GitRepo(base, realRoot, gitDir);
    }

    /**
     * Renders a path relative to the repository root, with forward slashes.
     *
     * @param path a path inside the repository
     * @return the relative path, {@code "."} for the root itself
     */
    public String relative(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        String relative = root.relativize(normalized).toString().replace('\\', '/');
        return relative.isEmpty() ? "." : relative;
    }

    /**
     * @return the git directory as shown to a caller: relative when it lies inside the project
     *         (the normal case, {@code .git}), otherwise just its file name (a linked worktree or
     *         a separate git directory - no absolute path ever leaves the server)
     */
    public String displayGitDir() {
        Path normalized = gitDir.toAbsolutePath().normalize();
        if (normalized.startsWith(base)) {
            String relative = base.relativize(normalized).toString().replace('\\', '/');
            return relative.isEmpty() ? "." : relative;
        }
        Path name = normalized.getFileName();
        return (name == null) ? "." : name.toString();
    }

    /**
     * @return {@code clean}, or what is in progress ({@code merging}, {@code rebasing},
     *         {@code cherry-picking}, {@code reverting}, {@code bisecting})
     */
    public String state() {
        for (String[] marker : STATE_MARKERS) {
            if (Files.exists(gitDir.resolve(marker[0]))) {
                return marker[1];
            }
        }
        return "clean";
    }

    /** Describes where a foreign repository root lies, without exposing an absolute path. */
    private static String describeRelative(Path base, Path root) {
        if (root.startsWith(base)) {
            String relative = base.relativize(root).toString().replace('\\', '/');
            return "'" + relative + "'";
        }
        return "a parent directory";
    }

    /**
     * Last non-blank line of a message - that is git's own {@code fatal: ...} line, while the
     * first line only names the command.
     */
    private static String lastLine(String message) {
        if (message == null || message.isBlank()) {
            return "unknown cause";
        }
        String[] lines = message.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].isBlank()) {
                return lines[i].strip();
            }
        }
        return "unknown cause";
    }
}
