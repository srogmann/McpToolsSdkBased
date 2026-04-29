package org.rogmann.mcp2sdk;

import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Central registry for managing MCP Tool states, activation, and call counting.
 * <p>
 * Maintains a sorted map of tools and synchronizes their registration status
 * with all active McpSyncServer instances.
 * </p>
 */
@Component
public class ToolRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(ToolRegistry.class);

    /**
     * Sorted map storing tools by name. 
     * Using ConcurrentSkipListMap to satisfy the requirement for a sorted structure 
     * (acting as SortedMap<String, ToolState>).
     */
    private final ConcurrentSkipListMap<String, ToolSpecWithState> mapTools = new ConcurrentSkipListMap<>();

    /**
     * List of MCP servers to synchronize tool registration with.
     */
    private final List<McpSyncServer> servers = new ArrayList<>();

    /**
     * Comma-separated list of tool names to activate on startup.
     */
    @Value("${tools.active:}")
    private String activeToolsProperty;
    
    /**
     * Set of tool names that should be activated (parsed from property).
     */
    private Set<String> activeToolNamesOnInit = new HashSet<>();

    /**
     * Registers a server instance with this registry.
     * <p>
     * Called by McpConfig during server bean creation.
     * </p>
     *
     * @param server The MCP server instance to manage.
     */
    public void registerServer(McpSyncServer server) {
        synchronized (servers) {
            servers.add(server);
            // Register currently active tools on the new server
            activateToolsOnServer(server);
        }
    }

    /**
     * Initializes the list of tools to activate on startup.
     * <p>
     * Actual activation happens during registerToolDefinition() when tools are added.
     */
    @PostConstruct
    public void init() {
        LOG.info("Initializing ToolRegistry");
        // Parse the active tools property and store names for later activation
        if (activeToolsProperty != null && !activeToolsProperty.isBlank()) {
            activeToolNamesOnInit = Arrays.stream(activeToolsProperty.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(java.util.stream.Collectors.toSet());
            LOG.info("Tools to activate on startup: {}", activeToolNamesOnInit);
        }
    }

    /**
     * Registers a tool definition into the registry.
     * <p>
     * Called by McpConfig during startup to populate the tool inventory.
     * If the tool is in the startup activation list, it will be activated immediately.
     * </p>
     *
     * @param specWithState the tool and its state.
     */
    public void registerToolDefinition(ToolSpecWithState specWithState) {
        McpSchema.Tool tool = specWithState.spec().tool();
        mapTools.put(tool.name(), specWithState);
        LOG.info("Registered tool definition: {}", tool.name());
        
        // Check if this tool should be activated on startup
        if (activeToolNamesOnInit.contains(tool.name())) {
            setActive(tool.name(), true);
        }
    }

    /**
     * Toggles the active state of a tool.
     *
     * @param toolName The name of the tool to toggle.
     * @return The new active state.
     */
    public boolean toggle(String toolName) {
        ToolSpecWithState specWithState = mapTools.get(toolName);
        if (specWithState == null) {
            LOG.warn("Tool not found for toggle: {}", toolName);
            return false;
        }

        ToolState state = specWithState.state();
        boolean newState = !state.isActive().get();
        setActive(toolName, newState);
        return newState;
    }

    /**
     * Sets the active state of a tool and updates all registered servers.
     *
     * @param toolName The name of the tool.
     * @param active   The desired active state.
     */
    private void setActive(String toolName, boolean active) {
        ToolSpecWithState specWithState = mapTools.get(toolName);
        if (specWithState == null) return;

        ToolState state = specWithState.state();
        boolean wasActive = state.isActive().getAndSet(active);

        if (active && !wasActive) {
            // Register on all servers
            synchronized (servers) {
                for (McpSyncServer server : servers) {
                    server.addTool(specWithState.spec());
                    server.notifyToolsListChanged();
                }
            }
            LOG.info("Activated tool: {}", toolName);
        } else if (!active && wasActive) {
            // Deregister from all servers
            synchronized (servers) {
                for (McpSyncServer server : servers) {
                    server.removeTool(toolName);
                    server.notifyToolsListChanged();
                }
            }
            LOG.info("Deactivated tool: {}", toolName);
        }
    }

    /**
     * Activates all currently active tools on a specific server.
     *
     * @param server The server to update.
     */
    private void activateToolsOnServer(McpSyncServer server) {
        for (ToolSpecWithState specWithState : mapTools.values()) {
            if (specWithState.state().isActive().get()) {
                server.addTool(specWithState.spec());
            }
        }
        if (!mapTools.isEmpty()) {
            server.notifyToolsListChanged();
        }
    }

    /**
     * Retrieves the sorted map of tools.
     *
     * @return The map of tool names to tool states.
     */
    public Map<String, ToolSpecWithState> getMapTools() {
        return Collections.unmodifiableMap(mapTools);
    }
}
