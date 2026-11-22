package org.rogmann.mcp2sdk.skills;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The result of scanning one {@link SkillSource} at one point in time (docs/skills.md §2).
 *
 * <p>The list is sorted by id, so two scans of an unchanged directory produce byte-identical
 * prompt text - the requirement behind the prefix cache (docs/webui.md, PrefixFork F3).</p>
 *
 * @param root     the scanned directory
 * @param skills   the skills of this source, sorted by id
 * @param warnings what was skipped and why (never silently dropped)
 */
record SkillsSnapshot(Path root, List<Skill> skills, List<String> warnings) {

    /**
     * Defensive copies of both lists.
     */
    SkillsSnapshot {
        skills = List.copyOf(skills);
        warnings = List.copyOf(warnings);
    }

    /** An empty snapshot, used for a source directory that does not exist. */
    static SkillsSnapshot empty(Path root) {
        return new SkillsSnapshot(root, List.of(), List.of());
    }

    /**
     * Looks a skill up by its id.
     *
     * @param id case-sensitive skill id
     * @return the skill, empty when this source does not define it
     */
    Optional<Skill> find(String id) {
        for (Skill skill : skills) {
            if (skill.id().equals(id)) {
                return Optional.of(skill);
            }
        }
        return Optional.empty();
    }
}
