package org.rogmann.mcp2sdk.skills;

import java.nio.file.Path;

/**
 * One directory skills are read from (docs/skills.md).
 *
 * <p>The label is the display form used in prompts and tool output - project-relative when the
 * directory lies inside the project base, absolute otherwise. It is deliberately not derived on
 * every access: a prompt must be byte-stable from one turn to the next (prefix cache), and a label
 * that changes shape between calls breaks exactly that.</p>
 *
 * @param label  display label, e.g. {@code .agents/skills}
 * @param root   the scanned directory (already normalized, may not exist)
 * @param strict whether a malformed manifest aborts loudly ({@code true} for operator-controlled
 *               directories, {@code false} for directories we merely discovered) - AGENTS.md:
 *               never fall back to a default silently
 */
public record SkillSource(String label, Path root, boolean strict) {
}
