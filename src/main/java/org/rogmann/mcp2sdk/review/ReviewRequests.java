package org.rogmann.mcp2sdk.review;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The shared plumbing of a request: id, run directory, artifact files, YAML rendering.
 * <p>
 * Why this is central: a tool should be able to create a request without knowing anything about
 * how requests are named or where they live, and then only <em>add</em> its own artifacts to the
 * directory it was given. If every tool invented its own id format, two tools would eventually
 * produce the same id - and since the review list is one list now and the id is its URL path, a
 * collision would mean two requests fighting for one page.
 * </p>
 * <p>
 * Id format (unchanged from the Python workflow, so existing run directories and documentation stay
 * true): {@code yyyyMMdd'T'HHmmss-<6 hex>} in UTC. The timestamp makes the id sortable by time, the
 * random suffix separates requests created in the same second; the whole id is unique across tools.
 * </p>
 */
public final class ReviewRequests {

    private static final Logger LOG = LoggerFactory.getLogger(ReviewRequests.class);

    /** Root for run directories of tools that do not name their own. */
    public static final String DEFAULT_RUNS_DIR = "runs/approvals";

    /** Id timestamp format (UTC), the first part of every request id. */
    private static final DateTimeFormatter ID_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(ZoneOffset.UTC);

    /** Timestamp format of the artifact files. */
    private static final DateTimeFormatter STAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    /** Characters of the random id suffix. */
    private static final int RANDOM_SUFFIX_CHARS = 6;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private ReviewRequests() {
        // Utility class
    }

    // ========================================================================
    // Identity and time
    // ========================================================================

    /**
     * Allocates a new, tool-independent request id.
     *
     * @return id like {@code 20260922T071530-3f9a1c}
     */
    public static String newId() {
        StringBuilder sb = new StringBuilder(20)
                .append(ID_FORMAT.format(Instant.now())).append('-');
        for (int i = 0; i < RANDOM_SUFFIX_CHARS; i++) {
            sb.append(HEX[ThreadLocalRandom.current().nextInt(HEX.length)]);
        }
        return sb.toString();
    }

    /**
     * @return the current instant in ISO-8601, as stored in requests and artifacts
     */
    public static String now() {
        return Instant.now().toString();
    }

    /**
     * Formats an epoch-millis value for a human-readable artifact line.
     *
     * @param epochMillis wall-clock time, 0 or less for "never"
     * @return formatted timestamp or {@code "-"}
     */
    public static String format(long epochMillis) {
        if (epochMillis <= 0) {
            return "-";
        }
        return STAMP_FORMAT.format(Instant.ofEpochMilli(epochMillis));
    }

    // ========================================================================
    // Run directory
    // ========================================================================

    /**
     * Default directory of a tool's request artifacts: {@code runs/approvals/<toolId>/<id>}.
     * <p>
     * A tool that already has an established layout (the Python workflow: {@code runs/python-exec/})
     * passes its own instead - existing run directories and their documentation must not move for a
     * refactoring. The tool's request then simply carries the other path, and the review UI reads it
     * from {@link ReviewEntry#runDir()}.
     * </p>
     *
     * @param toolId the tool id (used as directory name, non-alphanumerics are replaced)
     * @param id     the request id
     * @return the project-relative directory path, with forward slashes
     */
    public static String defaultRunDir(String toolId, String id) {
        String safeTool = (toolId == null || toolId.isBlank())
                ? "unknown"
                : toolId.trim().replaceAll("[^A-Za-z0-9._-]", "_");
        return DEFAULT_RUNS_DIR + "/" + safeTool + "/" + id;
    }

    /**
     * Resolves a project-relative path against the project base directory
     * ({@code IDE_PROJECT_DIR}), refusing anything that leaves it.
     * <p>
     * Kept free of a {@code JsFileSystem} dependency on purpose: the review area must work outside
     * the JavaScript sandbox (unit tests, tools that are not JS modules). The rules are the same as
     * everywhere else in this project - no {@code ..}, no absolute escape.
     * </p>
     *
     * @param relativePath path relative to the project base
     * @return the absolute path
     * @throws IllegalStateException if no project base directory is configured or the path escapes it
     */
    public static Path resolveInProject(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("A project-relative path is required.");
        }
        String base = System.getProperty("IDE_PROJECT_DIR");
        if (base == null || base.isBlank()) {
            throw new IllegalStateException(
                    "No project base directory available (system property IDE_PROJECT_DIR is not set).");
        }
        Path root = Paths.get(base).toAbsolutePath().normalize();
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalStateException("Path escapes the project directory: " + relativePath);
        }
        return resolved;
    }

    /**
     * Creates the run directory of a request if it does not exist yet.
     *
     * @param projectRelativeDir the directory to create (from {@link #defaultRunDir} or from the tool)
     * @return the absolute directory
     * @throws IllegalStateException if the directory cannot be created
     */
    public static Path createRunDir(String projectRelativeDir) {
        Path dir = resolveInProject(projectRelativeDir);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create the run directory '"
                    + projectRelativeDir + "': " + e.getMessage(), e);
        }
        return dir;
    }

    // ========================================================================
    // Artifacts
    // ========================================================================

    /**
     * Writes an artifact file, logging a failure instead of throwing.
     * <p>
     * Deliberately quiet: a request that was approved must not be lost because an extra log file
     * could not be written, and the reviewer's decision must not fail because of the disk. A missing
     * artifact is visible in the directory, so nothing is silently wrong.
     * </p>
     *
     * @param file    absolute target path
     * @param content the content
     */
    public static void writeQuietly(Path file, String content) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            LOG.error("Failed to write review artifact {}: {}", file, e.getMessage());
        }
    }

    /**
     * Writes an artifact only when it does not exist yet - for files the tool streams while it works
     * (the complete value of an oversized attribute, a log), where a later summary must not
     * overwrite the record.
     *
     * @param file    absolute target path
     * @param content the fallback content
     */
    public static void writeIfMissing(Path file, String content) {
        if (Files.exists(file)) {
            return;
        }
        writeQuietly(file, content != null ? content : "");
    }

    /**
     * Convenience: absolute path of an artifact inside a run directory.
     *
     * @param runDir absolute run directory
     * @param name   file name
     * @return the resolved path
     */
    public static Path artifactIn(Path runDir, String name) {
        return runDir.resolve(name);
    }

    /**
     * Convenience: project-relative display path of an artifact, for notes and the UI.
     *
     * @param runDir project-relative run directory
     * @param name   file name
     * @return {@code <runDir>/<name>}
     */
    public static String displayPath(String runDir, String name) {
        if (runDir == null || runDir.isBlank()) {
            return name;
        }
        return runDir.endsWith("/") ? runDir + name : runDir + "/" + name;
    }

    // ========================================================================
    // YAML rendering (the simple format used by request.yaml / result.yaml)
    // ========================================================================

    /**
     * Quotes a value for the simplified YAML files: single-quoted, doubled quotes, newlines escaped.
     *
     * @param value the value (may be null)
     * @return the quoted value, {@code "-"} for null
     */
    public static String quoteYaml(String value) {
        if (value == null) {
            return "-";
        }
        return "'" + value.replace("'", "''").replace("\n", "\\n") + "'";
    }

    /**
     * Appends a YAML string list (skipped when empty).
     *
     * @param sb     target buffer
     * @param key    the list key
     * @param values the values
     */
    public static void appendYamlList(StringBuilder sb, String key, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        sb.append(key).append(":\n");
        for (String value : values) {
            sb.append("  - ").append(quoteYaml(value)).append('\n');
        }
    }

    /**
     * Appends the attributes of a request or a response as YAML, so that an approved tuple is
     * reconstructable from the artifact alone (which is what makes an approval auditable after the
     * fact instead of a remembered yes).
     *
     * @param sb         target buffer
     * @param key        the list key ({@code attributes}, {@code result_attributes})
     * @param attributes the attributes
     */
    public static void appendAttributesYaml(StringBuilder sb, String key,
            List<ReviewAttribute> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return;
        }
        sb.append(key).append(":\n");
        for (ReviewAttribute attribute : attributes) {
            sb.append("  - key: ").append(quoteYaml(attribute.key())).append('\n');
            sb.append("    label: ").append(quoteYaml(attribute.label())).append('\n');
            sb.append("    kind: ").append(attribute.kind().name()).append('\n');
            sb.append("    value: ").append(quoteYaml(attribute.value())).append('\n');
            if (attribute.truncated()) {
                sb.append("    truncated: true\n");
                sb.append("    value_chars: ").append(attribute.valueBytes()).append('\n');
            }
            if (attribute.artifact() != null && !attribute.artifact().isEmpty()) {
                sb.append("    artifact: ").append(quoteYaml(attribute.artifact())).append('\n');
            }
            if (attribute.note() != null && !attribute.note().isEmpty()) {
                sb.append("    note: ").append(quoteYaml(attribute.note())).append('\n');
            }
            if (!attribute.checks().isEmpty()) {
                sb.append("    checks:\n");
                for (ReviewCheck check : attribute.checks()) {
                    sb.append("      - expectation: ").append(quoteYaml(check.expectation()))
                            .append('\n');
                    sb.append("        met: ").append(check.met()).append('\n');
                    sb.append("        detail: ").append(quoteYaml(check.detail())).append('\n');
                }
            }
        }
    }
}
