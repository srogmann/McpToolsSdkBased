package org.rogmann.mcp2sdk;

import io.modelcontextprotocol.server.McpServerFeatures;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Represents the runtime state of a registered MCP Tool.
 *
 * @param isActive    Flag indicating if the tool is currently registered on the server.
 * @param callCount   Atomic counter for the number of times the tool has been invoked.
 * @param callsOk     Atomic counter for the number of successful calls.
 */
public record ToolState(
        AtomicBoolean isActive,
        AtomicLong callCount,
        AtomicLong callsOk) {

    /**
     * Constructor
     */
    public ToolState() {
        this(new AtomicBoolean(), new AtomicLong(), new AtomicLong());
    }
}
