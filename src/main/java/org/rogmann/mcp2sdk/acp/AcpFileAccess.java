package org.rogmann.mcp2sdk.acp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.rogmann.mcp2sdk.WorkProject;
import org.rogmann.mcp2sdk.watch.FileChangeWatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Path resolution and the two write routes of the ACP file handling (docs/acp.md S5):
 * the classic local write — the normal case, no review, the user checks the changes
 * before building — and the IDE diff, which is governed by the system property
 * {@code acp.ide-diff} (default true, re-read per edit; not in the LLM's hands — no
 * tool parameter): when enabled, the change is surfaced to the IDE as a
 * {@code ToolCallDiff} content block (the client renders old → new as a diff in the
 * chat); the {@code fs/write_text_file} route was verified to be applied silently by
 * the IntelliJ client and is not used.
 */
final class AcpFileAccess {

    private static final Logger LOG = LoggerFactory.getLogger(AcpFileAccess.class);

    private AcpFileAccess() {
    }

    /**
     * Resolves the target file with the WorkProject rules (project name, path traversal).
     * @throws IllegalArgumentException with the reason if the project is unknown or the
     *         path escapes the project directory
     */
    static Path resolveWorkFile(String projectName, String pathInProject) {
        Map<String, Object> tempResult = new HashMap<>();
        WorkProject workProject = (projectName == null || projectName.isBlank())
                ? WorkProject.lookupProject(tempResult)
                : WorkProject.lookupProject(projectName, tempResult);
        if (workProject == null) {
            throw new IllegalArgumentException((String) tempResult.getOrDefault("error",
                    "Project not found: " + projectName));
        }
        Path projectDir = workProject.projectDir();
        Path targetFile = projectDir.resolve(pathInProject).normalize();
        if (!targetFile.startsWith(projectDir)) {
            throw new IllegalArgumentException("Path traversal detected in pathInProject: " + pathInProject);
        }
        return targetFile;
    }

    /** Reads the file, refusing directories and missing files. */
    static String readFile(Path targetFile) {
        if (!Files.exists(targetFile)) {
            throw new IllegalArgumentException("File does not exist: " + targetFile);
        }
        if (Files.isDirectory(targetFile)) {
            throw new IllegalArgumentException("Path refers to a directory, not a file: " + targetFile);
        }
        try {
            return Files.readString(targetFile);
        }
        catch (IOException e) {
            throw new IllegalArgumentException("Cannot read " + targetFile + ": " + e.getMessage(), e);
        }
    }

    /** Writes locally (the classic path, with the same FileChangeWatch hook the MCP tools use). */
    static void writeLocal(Path targetFile, String content) {
        try {
            FileChangeWatch.beforeWrite(targetFile, FileChangeWatch.Kind.MODIFIED);
            Files.writeString(targetFile, content);
        }
        catch (IOException e) {
            throw new IllegalArgumentException("Cannot write " + targetFile + ": " + e.getMessage(), e);
        }
    }
}
