package org.rogmann.mcp2sdk.js;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;

import java.time.Duration;
import java.util.*;
import java.util.logging.Logger;

/**
 * Proxy that makes MCP server tools available to JavaScript via GraalVM Polyglot.
 * <p>
 * Usage in JavaScript:
 * <pre>
 * // mcp.glossaryTool.call({words: ["MCP"]})
 * // mcp.glossaryTool.help()
 * </pre>
 * Tool names are converted from kebab-case to camelCase
 * (e.g., "glossary_tool" -> "glossaryTool").
 * </p>
 */
public class JsMcpProxy implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(JsMcpProxy.class.getName());

    private final McpSyncClient mcpClient;
    private final List<Tool> tools;

    /**
     * Creates a JsMcpProxy and connects to the given MCP server URL.
     * @param mcpServerUrl the URL of the MCP server (e.g., "http://localhost:8080")
     */
    public JsMcpProxy(String mcpServerUrl) {
        LOGGER.info("Connecting to MCP server: " + mcpServerUrl);
        
        McpClientTransport transport = HttpClientStreamableHttpTransport
                .builder(mcpServerUrl)
                .endpoint("/mcp")
                .build();
        
        mcpClient = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(10))
                .capabilities(McpSchema.ClientCapabilities.builder()
                        .build())
                .build();

        // Initialize connection
        mcpClient.initialize();

        // List available tools
        McpSchema.ListToolsResult toolsResult = mcpClient.listTools();
        this.tools = toolsResult.tools();
        
        LOGGER.info("Loaded " + tools.size() + " tools from MCP server");
        for (Tool tool : tools) {
            LOGGER.info("  Tool: " + tool.name());
        }
    }

    /**
     * Creates a ProxyObject representing the MCP tools namespace for JavaScript.
     * The returned object can be bound to a JavaScript context as "mcp".
     * @return ProxyObject with tool methods
     */
    public ProxyObject createMcpNamespace() {
        Map<String, Object> toolProxies = new HashMap<>();
        
        for (Tool tool : tools) {
            String jsName = convertToCamelCase(tool.name());
            ProxyObject toolProxy = createToolProxy(tool);
            toolProxies.put(jsName, toolProxy);
        }
        
        return ProxyObject.fromMap(toolProxies);
    }

    /**
     * Converts a kebab-case or snake_case tool name to camelCase.
     * E.g., "glossary_tool" -> "glossaryTool", "read_file" -> "readFile".
     * Package-visible so that {@link JsMcpProxyBridge} can reuse it when building
     * the module summary/help-tip from the loaded tool names.
     */
    static String convertToCamelCase(String name) {
        StringBuilder sb = new StringBuilder();
        boolean nextUpper = false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '-' || c == '_') {
                nextUpper = true;
            } else if (nextUpper) {
                sb.append(Character.toUpperCase(c));
                nextUpper = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Creates a ProxyObject for a single MCP tool with call() and help() methods.
     */
    private ProxyObject createToolProxy(Tool tool) {
        Map<String, Object> methods = new HashMap<>();
        
        // call method: mcp.toolName.call(inputDict)
        methods.put("call", (ProxyExecutable) args -> {
            if (args.length < 1) {
                throw new IllegalArgumentException("call() requires an arguments object");
            }
            Value argValue = args[0];
            Map<String, Object> toolArgs = convertValueToMap(argValue);
            
            LOGGER.info("Calling MCP tool '" + tool.name() + "' with args: " + toolArgs);
            
            CallToolResult result = mcpClient.callTool(new CallToolRequest(tool.name(), toolArgs));
            
            // Convert result to a JavaScript-friendly format
            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("isError", result.isError());
            
            List<Object> contentList = new ArrayList<>();
            if (result.content() != null) {
                for (McpSchema.Content content : result.content()) {
                    if (content instanceof McpSchema.TextContent tc) {
                        Map<String, Object> contentItem = new HashMap<>();
                        contentItem.put("type", "text");
                        contentItem.put("text", tc.text());
                        contentList.add(contentItem);
                    } else if (content instanceof McpSchema.ImageContent ic) {
                        Map<String, Object> contentItem = new HashMap<>();
                        contentItem.put("type", "image");
                        contentItem.put("data", ic.data());
                        contentItem.put("mimeType", ic.mimeType());
                        contentList.add(contentItem);
                    } else if (content instanceof McpSchema.ResourceContent rc) {
                        Map<String, Object> contentItem = new HashMap<>();
                        contentItem.put("type", "resource");
                        contentItem.put("uri", rc.uri());
                        contentItem.put("text", rc.toString());
                        contentList.add(contentItem);
                    } else {
                        contentList.add(content.toString());
                    }
                }
            }
            resultMap.put("content", contentList);
            
            // Convenience field: concatenated text content
            StringBuilder textContent = new StringBuilder();
            for (Object item : contentList) {
                if (item instanceof Map<?, ?> map) {
                    if ("text".equals(map.get("type"))) {
                        if (!textContent.isEmpty()) textContent.append("\n");
                        textContent.append(map.get("text"));
                    }
                }
            }
            resultMap.put("text", textContent.toString());
            
            return ProxyObject.fromMap(resultMap);
        });
        
        // help method: mcp.toolName.help()
        methods.put("help", (ProxyExecutable) args -> {
            StringBuilder helpText = new StringBuilder();
            helpText.append("Tool: ").append(tool.name()).append("\n");
            helpText.append("Description: ").append(tool.description()).append("\n\n");
            
            JsonSchema schema = tool.inputSchema();
            if (schema != null) {
                helpText.append("Input Schema:\n");
                helpText.append("  type: ").append(schema.type()).append("\n");
                
                Map<String, Object> properties = schema.properties();
                if (properties != null && !properties.isEmpty()) {
                    helpText.append("  properties:\n");
                    for (Map.Entry<String, Object> entry : properties.entrySet()) {
                        helpText.append("    ").append(entry.getKey()).append(": ");
                        if (entry.getValue() instanceof Map<?, ?> propMap) {
                            helpText.append(propMap.get("type"));
                            Object desc = propMap.get("description");
                            if (desc != null) {
                                helpText.append(" - ").append(desc);
                            }
                        } else {
                            helpText.append(entry.getValue());
                        }
                        helpText.append("\n");
                    }
                }
                
                List<String> required = schema.required();
                if (required != null && !required.isEmpty()) {
                    helpText.append("  required: ").append(String.join(", ", required)).append("\n");
                }
            }
            
            return helpText.toString();
        });
        
        return ProxyObject.fromMap(methods);
    }

    /**
     * Converts a GraalVM Value (JavaScript object) to a Java Map.
     */
    private Map<String, Object> convertValueToMap(Value value) {
        if (value == null || !value.hasMembers()) {
            return Collections.emptyMap();
        }
        Map<String, Object> result = new HashMap<>();
        for (String key : value.getMemberKeys()) {
            Value member = value.getMember(key);
            result.put(key, convertValue(member));
        }
        return result;
    }

    /**
     * Converts a GraalVM Value to a Java-friendly type.
     */
    private Object convertValue(Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isString()) {
            return value.asString();
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isNumber()) {
            if (value.fitsInLong()) {
                return value.asLong();
            }
            return value.asDouble();
        }
        if (value.hasArrayElements()) {
            List<Object> list = new ArrayList<>();
            for (long i = 0; i < value.getArraySize(); i++) {
                list.add(convertValue(value.getArrayElement(i)));
            }
            return list;
        }
        if (value.hasMembers()) {
            return convertValueToMap(value);
        }
        return value.asString();
    }

    /**
     * Binds the MCP namespace to a GraalVM Polyglot Context under the name "mcp".
     * @param context the GraalVM Polyglot context
     */
    public void bindToContext(Context context) {
        bindToContext(context, "mcp");
    }

    /**
     * Binds the MCP namespace to a GraalVM Polyglot Context with a custom name.
     * @param context the GraalVM Polyglot context
     * @param namespaceName the name of the namespace variable (e.g. "mcp")
     */
    public void bindToContext(Context context, String namespaceName) {
        ProxyObject mcpNamespace = createMcpNamespace();
        context.getBindings("js").putMember(namespaceName, mcpNamespace);
        LOGGER.info("Bound MCP namespace '" + namespaceName + "' with " + tools.size()
                + " tools to JavaScript context");
    }

    /**
     * Returns the list of tools loaded from the MCP server.
     * @return list of tools
     */
    public List<Tool> getTools() {
        return tools;
    }

    /**
     * Closes the MCP client connection.
     */
    @Override
    public void close() {
        if (mcpClient != null) {
            try {
                mcpClient.close();
            } catch (Exception e) {
                LOGGER.warning("Error closing MCP client: " + e.getMessage());
            }
        }
    }
}
