package org.rogmann.mcp2sdk.skills;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discovers skills below one source directory (docs/skills.md §2).
 *
 * <p>The rules are the OpenCode discovery rules, because the directory layout is theirs:</p>
 * <ul>
 *   <li>a flat {@code <id>.md} at the source root is a skill; flat files below the root are not,</li>
 *   <li>a directory containing {@code SKILL.md} is one skill - the scan does not descend into it,</li>
 *   <li>hidden entries and {@code node_modules} are skipped, symbolic links are never followed,</li>
 *   <li>the scan stops {@value #MAX_DEPTH} directories below the source root,</li>
 *   <li>the id is path-derived and case-sensitive: {@code teams/release/SKILL.md} is {@code release}.</li>
 * </ul>
 *
 * <p>Every skipped or unreadable entry ends up in {@link SkillsSnapshot#warnings()} - a skill that
 * silently disappears is the kind of bug this class must not produce.</p>
 */
final class SkillScanner {

    /** Logger */
    private static final Logger LOG = LoggerFactory.getLogger(SkillScanner.class);

    /** Name of the manifest file of a directory-style skill. */
    static final String SKILL_FILE = "SKILL.md";

    /** Directory name never searched (dependencies, not skills). */
    static final String NODE_MODULES = "node_modules";

    /** How many directories below a source root are searched at most. */
    static final int MAX_DEPTH = 6;

    /** How many supporting file paths are kept per skill (a sample, not an inventory). */
    static final int MAX_FILES = 100;

    /** Upper bound for a skill file; a longer one is truncated and reported. */
    static final int MAX_CHARS = 256 * 1024;

    private SkillScanner() {
    }

    /**
     * Scans one source.
     *
     * @param source the source to scan
     * @return the skills found, sorted by id, plus the warnings
     * @throws IllegalStateException when a manifest of a strict source is malformed
     */
    static SkillsSnapshot scan(SkillSource source) {
        Path root = source.root();
        if (!Files.isDirectory(root)) {
            return SkillsSnapshot.empty(root);
        }
        List<Skill> skills = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Deque<Level> stack = new ArrayDeque<>();
        stack.push(new Level(root, 0));
        while (!stack.isEmpty()) {
            Level level = stack.pop();
            for (Path child : children(level.dir(), warnings)) {
                if (Files.isSymbolicLink(child)) {
                    warnings.add("skipped symbolic link " + child);
                    continue;
                }
                if (Files.isDirectory(child)) {
                    String name = child.getFileName().toString();
                    if (isHidden(name) || NODE_MODULES.equals(name)) {
                        continue;
                    }
                    boolean inReach = level.depth() + 1 <= MAX_DEPTH;
                    Path skillFile = child.resolve(SKILL_FILE);
                    if (inReach && Files.isRegularFile(skillFile)) {
                        Skill skill = readSkill(child, skillFile, child.getFileName().toString(),
                                source, true, warnings);
                        if (skill != null) {
                            skills.add(skill);
                        }
                        // A directory containing SKILL.md is one skill - never searched further.
                        continue;
                    }
                    if (inReach) {
                        stack.push(new Level(child, level.depth() + 1));
                    }
                    continue;
                }
                if (level.depth() == 0) {
                    addFlatSkill(child, source, skills, warnings);
                }
            }
        }
        skills.sort((first, second) -> first.id().compareTo(second.id()));
        return new SkillsSnapshot(root, skills, warnings);
    }

    /** Adds a flat {@code <id>.md} of the source root, if the entry is one. */
    private static void addFlatSkill(Path file, SkillSource source, List<Skill> skills,
                                     List<String> warnings) {
        String fileName = file.getFileName().toString();
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".md")
                || SKILL_FILE.equalsIgnoreCase(fileName)) {
            return;
        }
        if (!Files.isRegularFile(file)) {
            return;
        }
        if (isHidden(fileName)) {
            return;
        }
        String id = fileName.substring(0, fileName.length() - ".md".length());
        Skill skill = readSkill(file.getParent(), file, id, source, false, warnings);
        if (skill != null) {
            skills.add(skill);
        }
    }

    /**
     * Reads one skill file and builds the skill.
     *
     * @param listSupportingFiles whether the files beside the skill are listed (a flat skill has
     *                            no private directory, so its neighbours are none of its business)
     * @return the skill, or {@code null} when the file could not be read
     * @throws IllegalStateException for a malformed manifest of a strict source
     */
    private static Skill readSkill(Path baseDir, Path skillFile, String id, SkillSource source,
                                   boolean listSupportingFiles, List<String> warnings) {
        String text;
        try {
            text = Files.readString(skillFile, StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            LOG.warn("Cannot read skill file {}", skillFile, e);
            warnings.add("cannot read " + skillFile + ": " + e.getMessage());
            return null;
        }
        if (text.length() > MAX_CHARS) {
            warnings.add("skill file " + skillFile + " is larger than " + MAX_CHARS
                    + " chars, truncated");
            text = text.substring(0, MAX_CHARS);
        }
        SkillManifest.Parsed parsed = SkillManifest.parse(text);
        if (!parsed.problems().isEmpty()) {
            String message = "invalid frontmatter in " + skillFile + ": "
                    + String.join("; ", parsed.problems());
            if (source.strict()) {
                throw new IllegalStateException(message);
            }
            LOG.warn("{} - skill \"{}\" skipped", message, id);
            warnings.add(message + " - skill skipped");
            return null;
        }
        SkillManifest manifest = parsed.manifest();
        String name = manifest.name() != null && !manifest.name().isBlank() ? manifest.name() : id;
        String description = manifest.description() != null && !manifest.description().isBlank()
                ? manifest.description() : null;
        boolean advertised = description != null && !manifest.modelInvocationDisabled();
        List<String> files = listSupportingFiles ? supportingFiles(baseDir, warnings) : List.of();
        return new Skill(id, name, description, advertised, parsed.body().strip(), baseDir, files,
                source.label());
    }

    /** Lists the files beside a skill file, relative to the skill directory, sorted. */
    private static List<String> supportingFiles(Path baseDir, List<String> warnings) {
        List<String> files = new ArrayList<>();
        Deque<Level> stack = new ArrayDeque<>();
        stack.push(new Level(baseDir, 0));
        while (!stack.isEmpty() && files.size() < MAX_FILES) {
            Level level = stack.pop();
            for (Path child : children(level.dir(), warnings)) {
                if (Files.isSymbolicLink(child)) {
                    continue;
                }
                String name = child.getFileName().toString();
                if (isHidden(name) || NODE_MODULES.equals(name)) {
                    continue;
                }
                if (Files.isDirectory(child)) {
                    if (level.depth() + 1 <= MAX_DEPTH) {
                        stack.push(new Level(child, level.depth() + 1));
                    }
                }
                else if (!(level.depth() == 0 && name.equals(SKILL_FILE))) {
                    files.add(toUnixPath(baseDir.relativize(child)));
                }
            }
        }
        files.sort(String::compareTo);
        if (files.size() >= MAX_FILES) {
            warnings.add("skill directory " + baseDir + " has more than " + MAX_FILES
                    + " files, list truncated");
        }
        return files;
    }

    /** Lists the children of a directory, sorted by name; an unreadable directory is a warning. */
    private static List<Path> children(Path dir, List<String> warnings) {
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                children.add(child);
            }
        }
        catch (IOException e) {
            LOG.warn("Cannot list directory {}", dir, e);
            warnings.add("cannot list " + dir + ": " + e.getMessage());
            return List.of();
        }
        children.sort((first, second) -> first.getFileName().toString()
                .compareTo(second.getFileName().toString()));
        return children;
    }

    /** A directory name that must not be searched. */
    private static boolean isHidden(String name) {
        return name.startsWith(".");
    }

    /** Renders a path with '/' separators, independent of the platform. */
    static String toUnixPath(Path path) {
        return path.toString().replace('\\', '/');
    }

    /**
     * One directory of the walk.
     *
     * @param dir   the directory
     * @param depth distance from the source root, the root itself being 0
     */
    private record Level(Path dir, int depth) {
    }
}
