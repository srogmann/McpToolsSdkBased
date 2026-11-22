package org.rogmann.mcp2sdk.skills;

import java.nio.file.Path;
import java.util.List;

/**
 * One discovered skill (docs/skills.md): the manifest of a {@code SKILL.md} (or of a flat
 * {@code <id>.md} at a source root) plus the instructions and the files beside it.
 *
 * @param id          the path-derived, case-sensitive id the model uses to load the skill
 * @param name        display name from the frontmatter, defaults to the id
 * @param description one-line summary used to advertise the skill, {@code null} when absent
 * @param advertised  whether the skill belongs into the model's skill list
 *                    ({@code description} present and no {@code autoinvoke: false})
 * @param body        the instructions, frontmatter stripped
 * @param baseDir     the directory the skill's own relative paths refer to
 * @param files       supporting files relative to {@code baseDir}, sorted, capped
 * @param sourceLabel label of the {@link SkillSource} the skill came from
 */
public record Skill(String id, String name, String description, boolean advertised, String body,
                    Path baseDir, List<String> files, String sourceLabel) {

    /**
     * Defensive copy of the file list.
     */
    public Skill {
        files = List.copyOf(files);
    }
}
