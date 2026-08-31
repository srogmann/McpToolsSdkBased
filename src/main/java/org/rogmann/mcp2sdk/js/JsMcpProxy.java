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
 * // mcp.someToolWithoutArguments.call()
 * // mcp.glossaryTool.help()
 * </pre>
 * Tool names are converted from kebab-case or snake_case to camelCase
 * (e.g., "glossary_tool" -> "glossaryTool"). If two MCP tools generate the same JavaScript
 * property name, a {@link JsUserRuntimeException} is thrown instead of silently overwriting
 * one tool with the other.
 * </p>
 * <p>
 * If an MCP tool returns {@code isError == true}, a {@link JsUserRuntimeException} is thrown
 * so that the JavaScript tool layer receives a clear user-facing error instead of a normal
 * successful-looking result.
 * </p>
 * <p>
 * The default request timeout is 30 seconds. It can optionally be configured with the system
 * property {@value #PROP_REQUEST_TIMEOUT_SECONDS}, where the value is the timeout in seconds.
 * </p>
 */
public class JsMcpProxy implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(JsMcpProxy.class.getName());

    /** System property to configure the MCP request timeout in seconds. */
    private static final String PROP_REQUEST_TIMEOUT_SECONDS = "mcp.js.requestTimeoutSeconds";

    /** Default MCP request timeout used when no property is configured. */
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private McpSyncClient mcpClient;
    private List<Tool> tools = Collections.emptyList();

    /**
     * Creates a JsMcpProxy and connects to the given MCP server URL.
     * <p>
     * The request timeout is resolved from the system property
     * {@value #PROP_REQUEST_TIMEOUT_SECONDS}, falling back to 30 seconds.
     * </p>
     * @param mcpServerUrl the URL of the MCP server (e.g., "http://localhost:8080")
     */
    public JsMcpProxy(String mcpServerUrl) {
        Duration requestTimeout = resolveRequestTimeout();
        boolean isOk = false;
        try {
            Duration effectiveTimeout = requestTimeout != null ? requestTimeout : DEFAULT_REQUEST_TIMEOUT;

            LOGGER.info("Connecting to MCP server: " + mcpServerUrl
                    + " (requestTimeout=" + effectiveTimeout + ")");

            McpClientTransport transport = HttpClientStreamableHttpTransport
                    .builder(mcpServerUrl)
                    .endpoint("/mcp")
                    .build();

            mcpClient = McpClient.sync(transport)
                    .requestTimeout(effectiveTimeout)
                    .capabilities(McpSchema.ClientCapabilities.builder()
                            .build())
                    .build();

            // Initialize connection
            mcpClient.initialize();

            // List available tools
            McpSchema.ListToolsResult toolsResult = mcpClient.listTools();
            List<Tool> loadedTools = toolsResult.tools();
            this.tools = loadedTools == null ? Collections.emptyList() : List.copyOf(loadedTools);

            LOGGER.info("Loaded " + tools.size() + " tools from MCP server");
            for (Tool tool : tools) {
                LOGGER.info("  Tool: " + tool.name());
            }

            isOk = true;
        } finally {
            if (!isOk) {
                close();
            }
        }
    }

    /**
     * Creates a ProxyObject representing the MCP tools namespace for JavaScript.
     * The returned object can be bound to a JavaScript context as "mcp".
     * @return ProxyObject with tool methods
     * @throws JsUserRuntimeException if the proxy is closed or if two MCP tools generate the
     *         same JavaScript tool name
     */
    public ProxyObject createMcpNamespace() {
        final McpSyncClient client = mcpClient;
        if (client == null) {
            throw new JsUserRuntimeException("MCP proxy is closed.");
        }

        Map<String, Object> toolProxies = new LinkedHashMap<>();
        Map<String, String> jsNameToMcpName = new LinkedHashMap<>();

        for (Tool tool : tools) {
            String jsName = convertToCamelCase(tool.name());
            String existingMcpName = jsNameToMcpName.putIfAbsent(jsName, tool.name());
            if (existingMcpName != null) {
                throw new JsUserRuntimeException("Duplicate JavaScript tool name '" + jsName
                        + "' generated by MCP tools '" + existingMcpName + "' and '" + tool.name() + "'.");
            }
            toolProxies.put(jsName, createToolProxy(tool, client));
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
    private ProxyObject createToolProxy(Tool tool, final McpSyncClient client) {
        Map<String, Object> methods = new HashMap<>();

        // call method: mcp.toolName.call(inputDict)
        methods.put("call", (ProxyExecutable) args -> {
            Value firstArg = args != null && args.length > 0 ? args[0] : null;
            Map<String, Object> toolArgs = convertToolArguments(firstArg);

            LOGGER.info("Calling MCP tool '" + tool.name() + "'");

            CallToolResult result = client.callTool(new CallToolRequest(tool.name(), toolArgs));
            if (result == null) {
                throw new JsUserRuntimeException("MCP tool '" + tool.name() + "' returned no result.");
            }

            // Convert result to a JavaScript-friendly format
            List<Object> contentList = convertContentList(result.content());

            // Convenience field: concatenated text content
            StringBuilder textContent = new StringBuilder();
            for (Object item : contentList) {
                if (item instanceof Map<?, ?> map) {
                    if ("text".equals(map.get("type"))) {
                        Object text = map.get("text");
                        if (text != null) {
                            if (!textContent.isEmpty()) {
                                textContent.append('\n');
                            }
                            textContent.append(text);
                        }
                    }
                }
            }

            if (Boolean.TRUE.equals(result.isError())) {
                String errorText = textContent.toString();
                if (errorText.isEmpty() && result.content() != null && !result.content().isEmpty()) {
                    errorText = result.content().toString();
                }
                if (errorText.isEmpty()) {
                    errorText = "no error content";
                }
                LOGGER.severe("MCP tool '" + tool.name() + "' returned an error: " + errorText);
                throw new JsUserRuntimeException("MCP tool '" + tool.name() + "' failed: " + errorText);
            }

            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("isError", false);
            resultMap.put("content", contentList);
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
     * Converts JavaScript call arguments into an MCP argument map.
     * <p>
     * Calls without arguments and {@code null}/{@code undefined} are valid and map to an empty
     * argument object, because many MCP tools do not require input properties.
     * </p>
     */
    private Map<String, Object> convertToolArguments(Value argValue) {
        if (argValue == null || argValue.isNull()) {
            return Collections.emptyMap();
        }
        if (!argValue.hasMembers()) {
            throw new JsUserRuntimeException(
                    "MCP tool arguments must be a JavaScript object, e.g. call({ key: 'value' }).");
        }
        return convertValueToMap(argValue);
    }

    /**
     * Converts MCP result content into JavaScript-friendly list entries.
     */
    private List<Object> convertContentList(List<McpSchema.Content> content) {
        if (content == null) {
            return Collections.emptyList();
        }

        List<Object> contentList = new ArrayList<>();
        for (McpSchema.Content item : content) {
            if (item == null) {
                continue;
            }

            if (item instanceof McpSchema.TextContent tc) {
                Map<String, Object> contentItem = new HashMap<>();
                contentItem.put("type", "text");
                contentItem.put("text", tc.text());
                contentList.add(contentItem);
            } else if (item instanceof McpSchema.ImageContent ic) {
                Map<String, Object> contentItem = new HashMap<>();
                contentItem.put("type", "image");
                contentItem.put("data", ic.data());
                contentItem.put("mimeType", ic.mimeType());
                contentList.add(contentItem);
            } else if (item instanceof McpSchema.ResourceContent rc) {
                Map<String, Object> contentItem = new HashMap<>();
                contentItem.put("type", "resource");
                contentItem.put("uri", rc.uri());
                contentItem.put("text", rc.toString());
                contentList.add(contentItem);
            } else {
                contentList.add(item.toString());
            }
        }

        return contentList;
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
        if (value.isHostObject()) {
            return value.asHostObject();
        }
        return value.toString();
    }

    /**
     * Resolves the request timeout from the optional system property
     * {@value #PROP_REQUEST_TIMEOUT_SECONDS}.
     * @return the configured timeout or the default 30 seconds
     */
    private static Duration resolveRequestTimeout() {
        String value = System.getProperty(PROP_REQUEST_TIMEOUT_SECONDS);
        if (value == null || value.isBlank()) {
            return DEFAULT_REQUEST_TIMEOUT;
        }
        try {
            long seconds = Long.parseLong(value.trim());
            if (seconds <= 0) {
                LOGGER.warning("Ignoring invalid '" + PROP_REQUEST_TIMEOUT_SECONDS
                        + "' value '" + value + "' (must be > 0); using default timeout "
                        + DEFAULT_REQUEST_TIMEOUT.getSeconds() + " seconds.");
                return DEFAULT_REQUEST_TIMEOUT;
            }
            return Duration.ofSeconds(seconds);
        } catch (NumberFormatException e) {
            LOGGER.warning("Ignoring invalid '" + PROP_REQUEST_TIMEOUT_SECONDS + "' value '" + value
                    + "' (not a number); using default timeout "
                    + DEFAULT_REQUEST_TIMEOUT.getSeconds() + " seconds.");
            return DEFAULT_REQUEST_TIMEOUT;
        }
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
            } finally {
                mcpClient = null;
            }
        }
    }
}
