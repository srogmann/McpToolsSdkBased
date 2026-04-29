package org.rogmann.mcp2sdk;

import io.modelcontextprotocol.server.McpServerFeatures;

/**
 * Tool-specification with tool-state (statistics).
 * @param spec tool-specification
 * @param state tool-state
 */
public record ToolSpecWithState(McpServerFeatures.SyncToolSpecification spec, ToolState state) {
}
