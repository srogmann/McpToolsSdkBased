package org.rogmann.mcp2sdk.tools;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.rogmann.mcp2sdk.ToolSpecWithState;
import org.rogmann.mcp2sdk.ToolState;
import org.rogmann.mcp2sdk.skills.Skill;
import org.rogmann.mcp2sdk.skills.SkillsRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCP tool that lists the skills of the workspace and loads one of them (docs/skills.md §4).
 *
 * <p>One tool, two stages - the whole point of skills: the index (id, name, one-line description)
 * is cheap enough to sit in every prompt, the instructions behind it are only read when a skill is
 * actually used. A second tool for "read the skill body" would double the surface for nothing; a
 * separate tool per skill would flood the tool list.</p>
 *
 * <p>Without an {@code id} the answer is the index; with an {@code id} it is the skill's body,
 * its base directory and a sample of the files beside it. The base directory matters: our file
 * tools resolve paths against the project, not against the skill, so a skill that says
 * {@code references/policy.md} is unreadable without it.</p>
 */
public class SkillsTool {

    /** Logger */
    private static final Logger LOG = LoggerFactory.getLogger(SkillsTool.class);

    /** Tool name as the model sees it. */
    private static final String NAME = "skills";

    /** tool state (active-flag, statistics) */
    private final ToolState state;

    private SkillsTool() {
        state = new ToolState();
    }

    /**
     * Whether the workspace has at least one skills directory. Without one the tool is not
     * registered at all - an empty tool in the list costs tokens in every request and answers
     * nothing (the same rule as {@code glossary_tool}).
     *
     * @return {@code true} when the tool should be offered
     * @throws IllegalStateException when {@code IDE_PROJECT_DIR} is set but invalid
     */
    public static boolean isAvailable() {
        return SkillsRegistry.getInstance().hasSources(SkillsRegistry.baseDir());
    }

    /**
     * Creates the synchronous tool specification.
     *
     * @return the tool specification and its state
     */
    public static ToolSpecWithState createToolInstance() {
        Map<String, Object> properties = new HashMap<>();
        Map<String, Object> idProp = new HashMap<>();
        idProp.put("type", "string");
        idProp.put("description", "Id of the skill to load. Omit it to list the available skills.");
        properties.put("id", idProp);

        JsonSchema inputSchema = new JsonSchema("object", properties, List.of(), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(NAME)
                .title("Skills")
                .description("Reusable instructions for this workspace, kept in "
                        + SkillsRegistry.SKILLS_DIR + " or " + SkillsRegistry.SKILLS_DIR_OPENCODE
                        + " (each skill is a SKILL.md with a name and "
                        + "a description, plus optional scripts and references beside it). Without "
                        + "'id' it lists the skills with their ids and descriptions; with 'id' it "
                        + "returns the skill's instructions, its base directory and the files next "
                        + "to it. Paths written inside a skill are relative to that base directory.")
                .inputSchema(inputSchema)
                .build();

        SkillsTool toolImpl = new SkillsTool();
        return new ToolSpecWithState(McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(toolImpl::call)
                .build(),
                toolImpl.state);
    }

    /**
     * Handles the tool call.
     *
     * @param exchange the server exchange
     * @param request  the tool call request
     * @return the tool call result
     */
    McpSchema.CallToolResult call(McpSyncServerExchange exchange, CallToolRequest request) {
        state.callCount().incrementAndGet();
        Map<String, Object> arguments = request.arguments();
        Object idArgument = arguments == null ? null : arguments.get("id");
        String id = idArgument == null ? null : idArgument.toString().trim();
        Path baseDir = SkillsRegistry.baseDir();
        SkillsRegistry registry = SkillsRegistry.getInstance();
        try {
            if (id == null || id.isEmpty()) {
                String list = registry.listReport(baseDir);
                if (list == null) {
                    return CallToolResult.builder()
                            .addTextContent("No skill found. Skills live in " + SkillsRegistry.SKILLS_DIR
                                    + " (or " + SkillsRegistry.SKILLS_DIR_OPENCODE
                                    + ") as <id>/SKILL.md or as <id>.md at the directory's root.")
                            .build();
                }
                state.callsOk().incrementAndGet();
                return CallToolResult.builder().addTextContent(list).build();
            }
            Optional<Skill> skill = registry.find(baseDir, id);
            if (skill.isEmpty()) {
                state.callsOk().incrementAndGet();
                return CallToolResult.builder()
                        .isError(true)
                        .addTextContent("Unknown skill id \"" + id + "\". "
                                + "Call this tool without an id to see the available skills.")
                        .build();
            }
            state.callsOk().incrementAndGet();
            return CallToolResult.builder()
                    .addTextContent(registry.loadReport(baseDir, skill.get()))
                    .build();
        }
        catch (IllegalStateException e) {
            LOG.warn("Cannot read skills below {}", baseDir, e);
            return CallToolResult.builder()
                    .isError(true)
                    .addTextContent(e.getMessage())
                    .build();
        }
    }
}
