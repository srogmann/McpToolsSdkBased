package org.rogmann.mcp2sdk.skills;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The skills of a workspace (docs/skills.md): discovery, precedence, prompt index and the text
 * behind the {@code skills} tool.
 *
 * <p><b>Sources.</b> The registry looks for {@value #SKILLS_DIR} (and, where that is missing,
 * {@value #SKILLS_DIR_OPENCODE}) in the workspace directory and in every ancestor up to the project
 * base ({@code IDE_PROJECT_DIR}) - the OpenCode rule, because the layout is OpenCode's. Duplicate
 * ids are resolved by precedence: the directory nearest to the workspace wins, and the shadowing is
 * logged (a silently overridden skill is a bug nobody finds).</p>
 *
 * <p><b>Caching.</b> ACP sessions carry their own {@code cwd}, so the same registry serves several
 * workspaces at once: the cache is keyed by source directory, not by registry instance. Each entry
 * lives {@value #DEFAULT_CACHE_MILLIS} ms by default (property {@value #PROP_CACHE_MILLIS}, {@code 0}
 * disables the cache); that keeps a tool loop from re-walking the tree on every call while a newly
 * written skill file still appears within seconds.</p>
 *
 * <p><b>Security.</b> Skill files are read with plain {@code java.nio} - deliberately not through
 * the JavaScript file bridge, which confines paths to the project: a skills directory may well lie
 * outside it later ({@code ~/.agents/skills}). What a skill contains is prompt input, never a
 * permission: the {@code metadata} block is a hint for the model, not a grant (docs/skills.md §6).</p>
 */
public final class SkillsRegistry {

    /** Logger */
    private static final Logger LOG = LoggerFactory.getLogger(SkillsRegistry.class);

    /** Directory (relative to a workspace or ancestor) that holds skills. */
    public static final String SKILLS_DIR = ".agents/skills";

    /**
     * Directory searched only when {@value #SKILLS_DIR} is missing at that level - OpenCode's own
     * directory. A workspace that keeps both is not merged twice: the entry closer to us wins.
     */
    public static final String SKILLS_DIR_OPENCODE = ".opencode/skills";

    /**
     * Property with the lifetime of a cached scan in milliseconds ({@value #DEFAULT_CACHE_MILLIS}
     * default, {@code 0} means "scan on every call").
     */
    public static final String PROP_CACHE_MILLIS = "skills.cacheMillis";

    /**
     * Property that switches the index injection into the system prompt of ACP chat and Web UI
     * (default {@code true}). The pure MCP server never injects - there the calling agent owns the
     * prompt - it only offers the {@code skills} tool.
     */
    public static final String PROP_INJECT = "skills.inject";

    /** Default cache lifetime. */
    static final long DEFAULT_CACHE_MILLIS = 5_000L;

    /** How many supporting file paths a load report shows. */
    private static final int MAX_LISTED_FILES = 10;

    private static final SkillsRegistry INSTANCE = new SkillsRegistry();

    /** Scan cache, keyed by source directory. */
    private final Map<Path, CachedSnapshot> cache = new ConcurrentHashMap<>();

    private SkillsRegistry() {
    }

    /** @return the process-wide registry (the tools and the chat surfaces share one cache) */
    public static SkillsRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * The workspace directory the registry reads for when no explicit one is passed: the project
     * base if configured, else the process working directory.
     *
     * @return the normalized workspace directory
     * @throws IllegalStateException when {@code IDE_PROJECT_DIR} is set but names no directory
     */
    public static Path baseDir() {
        String projectDir = System.getProperty("IDE_PROJECT_DIR");
        if (projectDir == null || projectDir.isBlank()) {
            return Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        }
        Path base = Path.of(projectDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(base)) {
            throw new IllegalStateException("Property \"IDE_PROJECT_DIR\" names no directory: \""
                    + projectDir + "\"");
        }
        return base;
    }

    /**
     * Whether the system prompt of ACP chat and Web UI carries the skill index (property
     * {@value #PROP_INJECT}, default {@code true}).
     *
     * @return {@code true} when the index is injected
     */
    public static boolean injectionEnabled() {
        String raw = System.getProperty(PROP_INJECT);
        if (raw == null || raw.isBlank()) {
            return true;
        }
        if ("true".equalsIgnoreCase(raw.trim())) {
            return true;
        }
        if ("false".equalsIgnoreCase(raw.trim())) {
            return false;
        }
        throw new IllegalStateException("Property \"" + PROP_INJECT + "\" has an invalid value \""
                + raw + "\": expected true or false");
    }

    /**
     * The source directories for a workspace, ordered from lowest to highest precedence.
     *
     * <p>Per level {@value #SKILLS_DIR} is preferred; {@value #SKILLS_DIR_OPENCODE} is used only
     * when the former is missing there. Merging both at one level would double every skill for a
     * workspace that keeps the same set in both directories.</p>
     *
     * @param baseDir the workspace directory, may be {@code null} for {@link #baseDir()}
     * @return the existing source directories, farthest ancestor first
     */
    public List<SkillSource> sources(Path baseDir) {
        Path base = normalize(baseDir);
        Path stop = projectRoot();
        List<SkillSource> found = new ArrayList<>();
        Path current = base;
        while (current != null) {
            Path root = skillsDirectory(current);
            if (root != null) {
                found.add(new SkillSource(labelFor(base, root), root, true));
            }
            if (current.equals(stop) || current.getParent() == null) {
                break;
            }
            current = current.getParent();
        }
        Collections.reverse(found);
        return List.copyOf(found);
    }

    /**
     * The skills directory of one level: {@value #SKILLS_DIR} if it exists, else
     * {@value #SKILLS_DIR_OPENCODE}.
     *
     * @param dir the directory to look in
     * @return the directory to scan, {@code null} when this level has none
     */
    private static Path skillsDirectory(Path dir) {
        Path preferred = dir.resolve(SKILLS_DIR);
        if (Files.isDirectory(preferred)) {
            return preferred;
        }
        Path opencode = dir.resolve(SKILLS_DIR_OPENCODE);
        if (Files.isDirectory(opencode)) {
            return opencode;
        }
        return null;
    }

    /**
     * Whether any source directory exists for a workspace.
     *
     * @param baseDir the workspace directory, may be {@code null} for {@link #baseDir()}
     * @return {@code true} when there is at least one skills directory
     */
    public boolean hasSources(Path baseDir) {
        return !sources(baseDir).isEmpty();
    }

    /**
     * All skills visible in a workspace, merged across the sources (nearest wins), sorted by id.
     *
     * @param baseDir the workspace directory, may be {@code null} for {@link #baseDir()}
     * @return the skills, sorted by id
     */
    public List<Skill> skills(Path baseDir) {
        List<SkillSource> sources = sources(baseDir);
        if (sources.isEmpty()) {
            return List.of();
        }
        Map<String, Skill> merged = new TreeMap<>();
        for (SkillSource source : sources) {
            for (Skill skill : snapshot(source).skills()) {
                Skill previous = merged.put(skill.id(), skill);
                if (previous != null) {
                    LOG.info("Skill \"{}\": {} in {} shadows the one in {}", skill.id(),
                            source.label(), source.root(), previous.baseDir());
                }
            }
        }
        return List.copyOf(merged.values());
    }

    /**
     * Looks a skill up by id.
     *
     * @param baseDir the workspace directory, may be {@code null} for {@link #baseDir()}
     * @param id      the case-sensitive skill id
     * @return the skill, empty when no source defines it
     */
    public Optional<Skill> find(Path baseDir, String id) {
        if (id == null) {
            return Optional.empty();
        }
        for (Skill skill : skills(baseDir)) {
            if (skill.id().equals(id)) {
                return Optional.of(skill);
            }
        }
        return Optional.empty();
    }

    /**
     * The block that goes into a system prompt: id, name and description of every advertised
     * skill - never a body.
     *
     * @param baseDir the workspace directory, may be {@code null} for {@link #baseDir()}
     * @return the block, or {@code null} when there is nothing to advertise
     */
    public String indexBlock(Path baseDir) {
        Path base = normalize(baseDir);
        List<Skill> all = skills(base);
        StringBuilder sb = new StringBuilder();
        int advertised = 0;
        for (Skill skill : all) {
            if (!skill.advertised()) {
                continue;
            }
            if (advertised == 0) {
                sb.append("Skills of this workspace - load one with the tool `skills` ")
                        .append("({\"id\": \"<id>\"}); paths written inside a skill are relative ")
                        .append("to its base directory:\n");
            }
            advertised++;
            sb.append("- ").append(skill.id()).append(" - ").append(skill.name());
            if (skill.description() != null) {
                sb.append(": ").append(skill.description());
            }
            sb.append(" [").append(labelFor(base, skill.baseDir())).append("]\n");
        }
        if (advertised == 0) {
            return null;
        }
        int hidden = all.size() - advertised;
        if (hidden > 0) {
            sb.append("(plus ").append(hidden)
                    .append(" skill(s) not advertised here; they are loadable by their id)\n");
        }
        return sb.toString().stripTrailing();
    }

    /**
     * The text the {@code skills} tool answers without an id: every skill with its id, name,
     * description and base directory.
     *
     * @param baseDir the workspace directory, may be {@code null} for {@link #baseDir()}
     * @return the list, or {@code null} when there is no skill at all
     */
    public String listReport(Path baseDir) {
        Path base = normalize(baseDir);
        List<Skill> all = skills(base);
        if (all.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Skills of this workspace (load one with {\"id\": \"<id>\"}):\n");
        for (Skill skill : all) {
            sb.append("- ").append(skill.id()).append(" - ").append(skill.name());
            if (skill.description() != null) {
                sb.append(": ").append(skill.description());
            }
            if (!skill.advertised()) {
                sb.append(" (not advertised to the model)");
            }
            sb.append(" [").append(labelFor(base, skill.baseDir())).append("]\n");
        }
        return sb.toString().stripTrailing();
    }

    /**
     * The text the {@code skills} tool answers with an id: the instructions without frontmatter,
     * the base directory and a sample of the files beside it.
     *
     * @param baseDir the workspace directory, may be {@code null} for {@link #baseDir()}
     * @param skill   the skill to report
     * @return the report
     */
    public String loadReport(Path baseDir, Skill skill) {
        Path base = normalize(baseDir);
        String baseLabel = labelFor(base, skill.baseDir());
        StringBuilder sb = new StringBuilder();
        sb.append("Skill \"").append(skill.id()).append("\"");
        if (!skill.name().equals(skill.id())) {
            sb.append(" (").append(skill.name()).append(")");
        }
        sb.append("\n");
        sb.append("Its base directory is ").append(baseLabel)
                .append(" - every path written inside the skill is relative to it.\n");
        List<String> files = skill.files();
        if (!files.isEmpty()) {
            sb.append("Supporting files:\n");
            int listed = Math.min(files.size(), MAX_LISTED_FILES);
            for (int i = 0; i < listed; i++) {
                sb.append("- ").append(baseLabel).append('/').append(files.get(i)).append("\n");
            }
            if (files.size() > listed) {
                sb.append("- ... (").append(files.size() - listed).append(" more)\n");
            }
        }
        sb.append("\n").append(skill.body()).append("\n");
        return sb.toString();
    }

    /** Drops every cached scan - for tests and for a manual "skills changed, re-read now". */
    public void clearCache() {
        cache.clear();
    }

    /** Returns the (possibly cached) snapshot of one source. */
    private SkillsSnapshot snapshot(SkillSource source) {
        long now = System.currentTimeMillis();
        long ttlMillis = cacheMillis();
        CachedSnapshot cached = cache.get(source.root());
        if (cached != null && cached.expiresAtMillis() > now) {
            return cached.snapshot();
        }
        SkillsSnapshot snapshot = SkillScanner.scan(source);
        for (String warning : snapshot.warnings()) {
            LOG.warn("skills in {}: {}", source.label(), warning);
        }
        cache.put(source.root(), new CachedSnapshot(snapshot, now + ttlMillis));
        return snapshot;
    }

    /** Reads and validates the cache lifetime. */
    private static long cacheMillis() {
        String raw = System.getProperty(PROP_CACHE_MILLIS);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_CACHE_MILLIS;
        }
        try {
            long value = Long.parseLong(raw.trim());
            if (value < 0) {
                throw new NumberFormatException("negative");
            }
            return value;
        }
        catch (NumberFormatException e) {
            LOG.debug("Invalid cache lifetime", e);
            throw new IllegalStateException("Property \"" + PROP_CACHE_MILLIS
                    + "\" has an invalid value \"" + raw
                    + "\": expected a non-negative number of milliseconds");
        }
    }

    /** The project base, used as the upper end of the ancestor walk; {@code null} when unset. */
    private static Path projectRoot() {
        String projectDir = System.getProperty("IDE_PROJECT_DIR");
        if (projectDir == null || projectDir.isBlank()) {
            return null;
        }
        Path root = Path.of(projectDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("Property \"IDE_PROJECT_DIR\" names no directory: \""
                    + projectDir + "\"");
        }
        return root;
    }

    /** Normalizes a workspace directory, falling back to {@link #baseDir()}. */
    private static Path normalize(Path baseDir) {
        if (baseDir == null) {
            return baseDir();
        }
        return baseDir.toAbsolutePath().normalize();
    }

    /** Renders a directory relative to the workspace when possible, absolute otherwise. */
    private static String labelFor(Path base, Path target) {
        if (target.startsWith(base)) {
            return SkillScanner.toUnixPath(base.relativize(target));
        }
        return SkillScanner.toUnixPath(target);
    }

    /**
     * A snapshot and the point in time it stops being trusted.
     *
     * @param snapshot         the scan result
     * @param expiresAtMillis  epoch millis after which the snapshot is stale
     */
    private record CachedSnapshot(SkillsSnapshot snapshot, long expiresAtMillis) {
    }
}
