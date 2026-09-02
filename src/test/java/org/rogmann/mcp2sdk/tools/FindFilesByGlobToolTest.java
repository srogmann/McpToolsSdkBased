package org.rogmann.mcp2sdk.tools;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the root-level glob fix in {@link FindFilesByGlobTool}.
 * <p>
 * Java's {@code PathMatcher} glob requires at least one directory level for a leading
 * {@code &amp;&amp;/}: {@code glob:&amp;&amp;/a.txt} matches {@code sub/a.txt} but not a root-level
 * {@code a.txt}. These tests verify that {@code buildRootLevelFilter}/{@code matchesPath}
 * close exactly that gap without changing the behaviour for nested paths.
 * </p>
 */
class FindFilesByGlobToolTest {

    private final FileSystem fileSystem = FileSystems.getDefault();

    @Test
    void rootLevelFilterIsBuiltForLeadingGlobstar() {
        DirectoryStream.Filter<Path> rootLevel =
                FindFilesByGlobTool.buildRootLevelFilter(fileSystem, "**/grep_simulation*.py");
        assertNotNull(rootLevel);
        assertTrue(matches(rootLevel, "grep_simulation.py"));
        assertTrue(matches(rootLevel, "grep_simulation_v2.py"));
        // The root-level variant itself must not match nested paths (that's the main filter's job).
        assertFalse(matches(rootLevel, "sub/grep_simulation.py"));
    }

    @Test
    void noRootLevelFilterForPlainPattern() {
        assertNull(FindFilesByGlobTool.buildRootLevelFilter(fileSystem, "*.py"));
        assertNull(FindFilesByGlobTool.buildRootLevelFilter(fileSystem, "**grep_simulation*.py"));
        assertNull(FindFilesByGlobTool.buildRootLevelFilter(fileSystem, "**/"));
    }

    @Test
    void mainGlobAloneFailsForRootFile() {
        DirectoryStream.Filter<Path> main = fileSystem.getPathMatcher("glob:**/grep_simulation*.py")::matches;
        // This is the Java quirk we compensate: the plain pattern does NOT match a root-level file ...
        assertFalse(matches(main, "grep_simulation.py"));
        // ... but does match a nested one.
        assertTrue(matches(main, "sub/grep_simulation.py"));
    }

    @Test
    void matchesPathIncludesRootLevelFile() throws IOException {
        DirectoryStream.Filter<Path> main = fileSystem.getPathMatcher("glob:**/grep_simulation*.py")::matches;
        DirectoryStream.Filter<Path> rootLevel = FindFilesByGlobTool.buildRootLevelFilter(fileSystem, "**/grep_simulation*.py");

        assertTrue(FindFilesByGlobTool.matchesPath(main, rootLevel, Path.of("grep_simulation.py")));
        assertTrue(FindFilesByGlobTool.matchesPath(main, rootLevel, Path.of("sub/grep_simulation.py")));
        assertFalse(FindFilesByGlobTool.matchesPath(main, rootLevel, Path.of("other.txt")));
    }

    private static boolean matches(DirectoryStream.Filter<Path> filter, String path) {
        try {
            return filter.accept(Path.of(path));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
