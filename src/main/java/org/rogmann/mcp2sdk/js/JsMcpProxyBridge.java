package org.rogmann.mcp2sdk.js;

import io.modelcontextprotocol.spec.McpSchema.Tool;

import org.graalvm.polyglot.Value;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * Bridge between GraalVM JavaScript and {@link JsMcpProxy}.
 * <p>
 * Implements {@link JsModuleInterface} for the optional {@code "mcp"} namespace: MCP server
 * tools are only bound when the system property {@value #PROP_MCP_SERVER_URL} is set.
 * Otherwise the module is disabled ({@link #isEnabled()} returns {@code false}) and nothing
 * is bound.
 * </p>
 * <p>
 * When enabled, the available MCP tools are determined <em>once in the constructor</em>
 * (a short-lived connection is opened, the tool list is read and the connection is closed
 * again). This makes {@link #getSummary()} and {@link #getHelpTip()} reflect the actual tool
 * names right from the start, so the MCP tool-description and error hints already know the
 * concrete tools.
 * </p>
 * <p>
 * The MCP connection used to actually serve JavaScript calls is still established
 * <em>per call</em>: {@link #wireApi(Value)} creates a fresh {@link JsMcpProxy}, binds its
 * namespace and returns the proxy as the per-call resource, which the JavaScript tool closes
 * after the call (equivalent to the original per-call lifecycle).
 * </p>
 *
 * <h3>Usage in JavaScript</h3>
 * <pre>{@code
 * // mcp.glossaryTool.call({words: ["MCP"]})
 * // mcp.glossaryTool.help()
 * }</pre>
 */
public class JsMcpProxyBridge implements JsModuleInterface {

    private static final Logger LOGGER = Logger.getLogger(JsMcpProxyBridge.class.getName());

    /** System property to optionally connect to an MCP server and make its tools available in JavaScript via JsMcpProxy. */
    private static final String PROP_MCP_SERVER_URL = "mcp.js.mcpServer.url";

    /** The configured MCP server URL, or {@code null} if the property is not set (module disabled). */
    private final String mcpServerUrl;

    /**
     * The JS names (camelCase) of the tools offered by the configured MCP server,
     * or an empty list if the module is disabled or the tool list could not be read.
     */
    private final List<String> toolJsNames;

    /** Summary/help-tip fallbacks used when no concrete tool name could be determined. */
    private static final String SUMMARY_FALLBACK = "`mcp.<toolName>()` calls tools of an MCP server";
    private static final String HELPTIP_FALLBACK = "mcp.<toolName>.call(...)";

    /**
     * Creates the bridge and reads the configuration. If the system property
     * {@value #PROP_MCP_SERVER_URL} is set, the module is enabled and the available MCP
     * tools are determined immediately (a short-lived connection to the server); the
     * per-call MCP connection used to actually serve JavaScript calls is established in
     * {@link #wireApi(Value)}.
     */
    public JsMcpProxyBridge() {
        String url = System.getProperty(PROP_MCP_SERVER_URL);
        mcpServerUrl = (url != null && !url.isBlank()) ? url : null;
        this.toolJsNames = (mcpServerUrl != null) ? loadToolNames(mcpServerUrl) : Collections.emptyList();
    }

    /**
     * Opens a short-lived connection to the MCP server, reads the list of available tools
     * and returns their JavaScript (camelCase) names. The connection is closed afterwards.
     * @param serverUrl the MCP server URL
     * @return the tool JS names, or an empty list if the tool list could not be read
     */
    private static List<String> loadToolNames(String serverUrl) {
        List<String> names = new ArrayList<>();
        try (JsMcpProxy proxy = new JsMcpProxy(serverUrl)) {
            for (Tool tool : proxy.getTools()) {
                names.add(JsMcpProxy.convertToCamelCase(tool.name()));
            }
            if (names.isEmpty()) {
                LOGGER.warning("MCP server '" + serverUrl + "' reported no tools");
            } else {
                LOGGER.info("Determined " + names.size() + " MCP tools for summary/help: " + names);
            }
        } catch (Exception e) {
            LOGGER.warning("Could not determine MCP tools from '" + serverUrl + "': " + e.getMessage());
        }
        return names;
    }

    @Override
    public String getNamespace() {
        return "mcp";
    }

    @Override
    public String getSummary() {
        if (toolJsNames.isEmpty()) {
            return SUMMARY_FALLBACK;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < toolJsNames.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('`').append("mcp.").append(toolJsNames.get(i)).append("()`");
        }
        sb.append(" call tools of an MCP server (use mcp.<toolName>.help() for details)");
        return sb.toString();
    }

    @Override
    public String getHelpTip() {
        if (toolJsNames.isEmpty()) {
            return HELPTIP_FALLBACK;
        }
        StringBuilder sb = new StringBuilder("mcp.");
        for (int i = 0; i < toolJsNames.size(); i++) {
            if (i > 0) {
                sb.append(", mcp.");
            }
            sb.append(toolJsNames.get(i)).append(".help()");
        }
        sb.append(" (MCP server tools)");
        return sb.toString();
    }

    @Override
    public boolean hasRequireAlias() {
        return false;
    }

    @Override
    public boolean isEnabled() {
        return mcpServerUrl != null;
    }

    @Override
    public AutoCloseable wireApi(Value jsBindings) {
        if (mcpServerUrl == null) {
            return null;
        }
        JsMcpProxy proxy = new JsMcpProxy(mcpServerUrl);
        jsBindings.putMember(getNamespace(), proxy.createMcpNamespace());
        LOGGER.info("MCP namespace '" + getNamespace() + "' bound to JavaScript context");
        return proxy;
    }
}
