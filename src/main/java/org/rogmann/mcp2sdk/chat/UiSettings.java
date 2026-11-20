package org.rogmann.mcp2sdk.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;

/**
 * The server-side Web UI settings of {@link WebUiProxy}: the {@code ui_settings} node of the
 * {@code /chat/props} response, and the default MCP server shipped in it.
 *
 * <p>Split out of the controller because it is a contract of its own with no HTTP, no streaming and
 * no model knowledge (docs/webui.md §2, R7): llama.cpp's {@code --ui-config} counterpart, which this
 * application assembles from {@value #PROP_UI_CONFIG}, {@value #PROP_CUSTOM_CSS},
 * {@value #PROP_SYSTEM_MESSAGE} and the MCP seed below. {@code WebUiProxy.getProps} stays the entry
 * point (headers, caching, request origin) and
 * {@code buildJsonProps} stays there too - it needs the model properties and the chat template,
 * which the completion path owns as well.</p>
 *
 * <p>Key naming follows the Web UI's settings store: the keys are the camelCase names of the
 * settings dialog ({@code customCss}, {@code sendOnEnter}, {@code theme}, ...), so whatever the
 * "Settings" page can set can also be shipped from the server.</p>
 *
 * <p><b>It stays a default</b> (behaviour of the built Web UI, not of this class): a browser applies
 * {@code ui_settings} only while it has no own copy of the settings in {@code localStorage}, only for
 * entries it never changed itself, and otherwise on "Reset Settings to Default". A developer who has
 * set "Custom CSS" locally therefore keeps the local value and is not disturbed by whatever the
 * server ships.</p>
 *
 * <p>Note also that the Web UI only consults {@code ui_settings} when the generation settings of
 * {@code default_generation_settings.params} yielded at least one known parameter (its sync step
 * returns early otherwise) - those are part of {@code buildJsonProps} and must stay there.</p>
 *
 * <p><b>The system prompt is always part of it.</b> Besides the two settings an operator writes
 * ({@code uiConfig}, {@code customCss}) the node carries the {@code systemMessage} - without it a
 * fresh browser would start with an empty one, and the model would not know that this backend can
 * read files through its MCP tools. The shipped value is {@value #DEFAULT_SYSTEM_MESSAGE} unless
 * {@value #PROP_SYSTEM_MESSAGE} or a {@code systemMessage} entry of {@code uiConfig} names another
 * one (see {@link #resolveSystemMessage(ObjectNode)}), so the node is never empty.</p>
 */
@Component
public class UiSettings {

    /** Logger */
    private static final Logger LOG = LoggerFactory.getLogger(UiSettings.class);

    /**
     * Server-side UI defaults as a JSON object (property {@value #PROP_UI_CONFIG}), served as
     * {@code ui_settings}. This is the counterpart of llama.cpp's {@code --ui-config} argument,
     * e.g. {@code {"customCss": ".markdown-content { ... }"}}.
     */
    static final String PROP_UI_CONFIG = "webui.uiConfig";

    /** Convenience accessor for the single {@code customCss} UI setting (the "Custom CSS" field). */
    static final String PROP_CUSTOM_CSS = "webui.customCss";

    /**
     * System prompt shipped to a Web UI without settings of its own (property
     * {@value #PROP_SYSTEM_MESSAGE}). Unset - the normal case - means the built-in
     * {@value #DEFAULT_SYSTEM_MESSAGE}; see {@link #resolveSystemMessage(ObjectNode)}.
     */
    static final String PROP_SYSTEM_MESSAGE = "webui.systemMessage";

    /**
     * The system prompt a fresh Web UI starts with ("System Message" field of the chat): the model
     * is told that it is a local assistant which reads {@code AGENTS.md} - the file that carries
     * the project's own conventions, see {@code AGENTS.md} in the project root.
     */
    static final String DEFAULT_SYSTEM_MESSAGE =
            "You are a local coach, and for many use cases, you may be the right one. Read AGENTS.md (if present).";

    /**
     * Switches the {@code mcpServers} seed on. Without a configured MCP server the Web UI has no
     * tools at all, and on hosts that clear the browser cache at logoff the entry has to be typed
     * in again after every restart - the seed saves that (property
     * {@value #PROP_MCP_SERVER_ENABLED}, default true).
     */
    static final String PROP_MCP_SERVER_ENABLED = "webui.mcpServer.enabled";

    /**
     * URL of the MCP server the Web UI is seeded with (property {@value #PROP_MCP_SERVER_URL}).
     * Unset - the normal case - means "derive it": the origin of the {@code /props} request, and
     * without a request the configured {@code server.address} plus the bound port.
     */
    static final String PROP_MCP_SERVER_URL = "webui.mcpServer.url";

    /** Display name of the seeded MCP server entry (property {@value #PROP_MCP_SERVER_NAME}). */
    static final String PROP_MCP_SERVER_NAME = "webui.mcpServer.name";

    /** Field name of the server-side UI defaults in the {@code /props} response. */
    static final String PROPS_FIELD = "ui_settings";

    /** Key of the "Custom CSS" setting inside {@code ui_settings} (Web UI: {@code customCss}). */
    static final String SETTING_CUSTOM_CSS = "customCss";

    /**
     * Key of the system prompt inside {@code ui_settings} (Web UI settings key:
     * {@code systemMessage}, the "System Message" field of the settings dialog).
     *
     * <p>Unlike {@code mcpServers} the value is a plain string, not a JSON string: the Web UI keeps
     * it as it is and sends it as the top-level {@code systemMessage} parameter of the completion
     * request (bundle: {@code e.systemMessage && (n.systemMessage = e.systemMessage)}). This proxy
     * forwards that field unchanged - {@code ChatRequestPreparer} neither reads nor rewrites it.</p>
     */
    static final String SETTING_SYSTEM_MESSAGE = "systemMessage";

    /**
     * Key of the MCP server list inside {@code ui_settings} (Web UI settings key:
     * {@code mcpServers}).
     *
     * <p>The value is a <em>JSON string</em> holding an array of server objects - exactly the shape
     * the Web UI's settings store keeps in {@code localStorage["LlamaUi.config"]}, not an array
     * node. That is not cosmetic: the settings dialog edits the value as text and
     * {@code exportSettings()} runs {@code JSON.parse} on it, so an array node would be dropped by
     * the UI. See docs/webui.md §6.</p>
     */
    static final String SETTING_MCP_SERVERS = "mcpServers";

    /** Stable id of the seeded entry: the Web UI keys its server list by id. */
    static final String MCP_SERVER_ID = "mcp2sdk";

    /** Path of this application's Streamable-HTTP MCP endpoint (see {@code McpConfig}). */
    static final String MCP_ENDPOINT_PATH = "/mcp";

    /** Host used when {@code server.address} names no reachable address (wildcard, unset). */
    static final String MCP_SEED_FALLBACK_HOST = "127.0.0.1";

    /** JsonMapper for the settings nodes (Jackson 3; one instance is enough, it is immutable). */
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    /**
     * Server-side UI defaults as JSON object (property {@value #PROP_UI_CONFIG}). Empty by default,
     * which keeps the field out of the response entirely.
     *
     * <p>Package-private, like the fields below: the unit tests are in this package and hand the
     * values in directly instead of driving them through Reflection (the reason this class exists).</p>
     */
    @Value("${" + PROP_UI_CONFIG + ":}")
    String uiConfigJson;

    /**
     * The "Custom CSS" of the Web UI (property {@value #PROP_CUSTOM_CSS}). Takes precedence over a
     * {@code customCss} entry of {@link #uiConfigJson} because it is the more specific switch.
     */
    @Value("${" + PROP_CUSTOM_CSS + ":}")
    String customCss;

    /**
     * The system prompt the Web UI is seeded with (property {@value #PROP_SYSTEM_MESSAGE}). Blank -
     * the normal case, i.e. no explicit configuration - means the built-in
     * {@value #DEFAULT_SYSTEM_MESSAGE}, which a {@code systemMessage} entry of
     * {@link #uiConfigJson} still wins over; see {@link #resolveSystemMessage(ObjectNode)}.
     */
    @Value("${" + PROP_SYSTEM_MESSAGE + ":}")
    String systemMessage;

    /**
     * Whether the Web UI is seeded with a default MCP server (property
     * {@value #PROP_MCP_SERVER_ENABLED}, default true).
     *
     * <p>The field initializer is the value for a hand-constructed instance (unit tests): Spring
     * overwrites it from the property, the no-arg construction keeps the default.</p>
     */
    @Value("${" + PROP_MCP_SERVER_ENABLED + ":true}")
    boolean mcpServerEnabled = true;

    /**
     * Explicit URL of the seeded MCP server (property {@value #PROP_MCP_SERVER_URL}). Empty means
     * "derive it", see {@link #resolveMcpServerUrl(String)}.
     */
    @Value("${" + PROP_MCP_SERVER_URL + ":}")
    String mcpServerUrl;

    /** Display name of the seeded MCP server entry (property {@value #PROP_MCP_SERVER_NAME}). */
    @Value("${" + PROP_MCP_SERVER_NAME + ":" + MCP_SERVER_ID + "}")
    String mcpServerName;

    /**
     * Spring environment, read <em>lazily</em> when the seeded MCP server URL has to be derived
     * without a request at hand (startup log, unit test): {@code server.port=0} binds an arbitrary
     * port and only {@code local.server.port} knows it - and that property appears after the web
     * server has started, i.e. long after this bean was created (same pattern as {@code McpConfig}
     * and {@code BrowserListeners}).
     *
     * <p>{@code required = false} keeps the no-arg construction of the unit tests working; the
     * field is null there and the URL falls back to the request origin or stays unset.</p>
     */
    @Autowired(required = false)
    Environment environment;

    /**
     * Builds the {@code ui_settings} node for a caller without a request (the startup log).
     *
     * @return the node to serve; never empty, because the system prompt is always shipped
     */
    ObjectNode build() {
        return build(null);
    }

    /**
     * Builds the {@code ui_settings} node of the {@code /props} response.
     *
     * @param requestBaseUrl origin the page was requested from ({@code scheme://host:port} plus
     *                       context path, no trailing slash), or {@code null} to derive the MCP
     *                       server URL from the configuration
     * @return the node to serve; never empty, because the system prompt is always shipped
     */
    ObjectNode build(String requestBaseUrl) {
        ObjectNode uiSettings = jsonMapper.createObjectNode();

        if (uiConfigJson != null && !uiConfigJson.isBlank()) {
            try {
                JsonNode node = jsonMapper.readTree(uiConfigJson);
                if (node != null && node.isObject()) {
                    uiSettings.setAll((ObjectNode) node);
                } else {
                    LOG.warn("{} is not a JSON object, ignoring it (expected e.g. "
                            + "{\"customCss\": \"...\"})", PROP_UI_CONFIG);
                }
            } catch (RuntimeException e) {
                // A broken property must not break /props: the UI then simply uses its defaults.
                LOG.warn("Cannot parse {} as JSON, ignoring it: {}", PROP_UI_CONFIG, e.getMessage());
            }
        }

        // The dedicated property wins over a customCss entry of the JSON blob: it is the one that
        // is written in multi-line YAML style, so it is what an operator actually edits.
        if (customCss != null && !customCss.isBlank()) {
            uiSettings.put(SETTING_CUSTOM_CSS, customCss);
        }

        // System prompt of the chat (the "System Message" field). A browser without settings of its
        // own would otherwise start with an empty one, i.e. the model would not know that this
        // backend reads AGENTS.md.
        String prompt = resolveSystemMessage(uiSettings);
        uiSettings.put(SETTING_SYSTEM_MESSAGE, prompt);

        // Default MCP server (docs/webui.md §6). An entry the operator wrote into uiConfig wins:
        // that is a deliberate configuration, this is only the built-in convenience.
        if (!uiSettings.has(SETTING_MCP_SERVERS)) {
            String servers = buildMcpServersSetting(requestBaseUrl);
            if (servers != null) {
                uiSettings.put(SETTING_MCP_SERVERS, servers);
            }
        }

        return uiSettings;
    }

    /**
     * Determines the system prompt to ship, in three steps:
     *
     * <ol>
     *   <li>{@value #PROP_SYSTEM_MESSAGE}, if set (trimmed): the operator knows best - it is the
     *       dedicated switch, the counterpart of the {@code customCss} property and therefore more
     *       specific than the generic JSON blob,</li>
     *   <li>a {@code systemMessage} entry of {@link #uiConfigJson}, if the operator wrote one
     *       there (that is the way to seed an <em>empty</em> prompt: {@code {"systemMessage": ""}}),</li>
     *   <li>the built-in {@value #DEFAULT_SYSTEM_MESSAGE} - the reason this method exists: a fresh
     *       browser must not start with an empty system prompt.</li>
     * </ol>
     *
     * <p>Note that a blank property is not a value but "not configured": an operator who wants no
     * system prompt at all writes the empty entry into {@code webui.uiConfig}, which is the channel
     * meant for a whole settings object.</p>
     *
     * <p>Package-private so the unit test can drive the decision without a running application.</p>
     *
     * @param uiSettings the settings built so far (the {@code uiConfig} entries are already in it)
     * @return the prompt to serve, never {@code null}
     */
    String resolveSystemMessage(ObjectNode uiSettings) {
        if (systemMessage != null && !systemMessage.isBlank()) {
            return systemMessage.trim();
        }
        JsonNode configured = uiSettings.get(SETTING_SYSTEM_MESSAGE);
        if (configured != null) {
            if (configured.isString()) {
                return configured.asString();
            }
            LOG.warn("{} must be a string, ignoring it (value: {}); the built-in default system prompt"
                    + " is shipped instead", SETTING_SYSTEM_MESSAGE, configured);
        }
        return DEFAULT_SYSTEM_MESSAGE;
    }

    /**
     * Builds the {@code mcpServers} setting: a JSON <em>string</em> holding the Web UI's server list
     * with one entry that points at this application's own MCP endpoint.
     *
     * <p>Why this exists: the Web UI keeps its MCP servers in {@code localStorage} only, so without
     * an entry the chat has no tools at all (only {@code get_info} from {@code /chat/tools}). On
     * hosts that clear the browser cache at logoff the entry would have to be typed in again after
     * every restart. The Web UI applies {@code ui_settings} on the first visit of a browser (empty
     * {@code localStorage}) and on "Reset Settings to Default" - precisely the situations that cost
     * the typing. A browser that already has its own settings keeps them, so this stays a default
     * and never overwrites a decision a user made.</p>
     *
     * <p>Package-private so the unit test can drive the derivation without a running application.</p>
     *
     * @param requestBaseUrl origin of the page, or {@code null} to use the configuration
     * @return the JSON string to serve, or {@code null} when nothing is seeded (switched off, no
     *         URL derivable)
     */
    String buildMcpServersSetting(String requestBaseUrl) {
        if (!mcpServerEnabled) {
            LOG.debug("{} is false: the Web UI keeps its own MCP server list.", PROP_MCP_SERVER_ENABLED);
            return null;
        }
        String url = resolveMcpServerUrl(requestBaseUrl);
        if (url == null) {
            // Nothing half-baked: a URL with port 0 would be a server the browser can never reach,
            // and it would occupy the "first visit" application, blocking a later correct value.
            LOG.info("No {} seed: the MCP server URL cannot be derived ({} unset and no request"
                            + " origin, or no port known). Set {} to name it explicitly.",
                    SETTING_MCP_SERVERS, PROP_MCP_SERVER_URL, PROP_MCP_SERVER_URL);
            return null;
        }

        ObjectNode entry = jsonMapper.createObjectNode();
        entry.put("id", MCP_SERVER_ID);
        entry.put("name", serverDisplayName());
        entry.put("url", url);
        // Only an enabled server with a URL is counted by the Web UI, and 'enabled' is what its
        // toggle switch shows: a seed the user has to switch on first would miss the point.
        entry.put("enabled", true);

        ArrayNode servers = jsonMapper.createArrayNode();
        servers.add(entry);
        try {
            return jsonMapper.writeValueAsString(servers);
        } catch (RuntimeException e) {
            LOG.warn("Cannot serialize the {} seed, the Web UI keeps its own server list: {}",
                    SETTING_MCP_SERVERS, e.getMessage());
            return null;
        }
    }

    /** Name of the seeded server entry (property {@value #PROP_MCP_SERVER_NAME}, fallback: its id). */
    private String serverDisplayName() {
        String name = (mcpServerName == null) ? "" : mcpServerName.trim();
        return name.isEmpty() ? MCP_SERVER_ID : name;
    }

    /**
     * Resolves the MCP endpoint URL the Web UI is seeded with, in three steps:
     *
     * <ol>
     *   <li>{@value #PROP_MCP_SERVER_URL}, if set: the operator knows best (reverse proxy, another
     *       transport such as {@code /mcp/sse}, another host),</li>
     *   <li>the origin of the {@code /props} request plus {@value #MCP_ENDPOINT_PATH}: the spelling
     *       the browser itself used, which keeps the MCP call same-origin - no CORS, and the
     *       session cookie travels along that {@code McpLockdownFilter} asks for,</li>
     *   <li>the configured address and bound port (see {@link #mcpServerUrlFromConfig()}) for the
     *       cases without a request.</li>
     * </ol>
     *
     * @param requestBaseUrl origin of the page, may be {@code null}
     * @return the URL, or {@code null} when none can be derived
     */
    String resolveMcpServerUrl(String requestBaseUrl) {
        if (mcpServerUrl != null && !mcpServerUrl.isBlank()) {
            return stripTrailingSlash(mcpServerUrl.trim());
        }
        if (requestBaseUrl != null && !requestBaseUrl.isBlank()) {
            return stripTrailingSlash(requestBaseUrl.trim()) + MCP_ENDPOINT_PATH;
        }
        return mcpServerUrlFromConfig();
    }

    /**
     * Derives the MCP endpoint URL from the configuration, for callers without a request (startup
     * log, unit test): {@code server.address} or the loopback address, the bound port, the context
     * path and {@value #MCP_ENDPOINT_PATH}.
     *
     * @return the URL, or {@code null} when no port is known (no {@link Environment}, or
     *         {@code server.port} still 0)
     */
    String mcpServerUrlFromConfig() {
        if (environment == null) {
            return null;
        }
        int port = configuredPort();
        if (port <= 0) {
            return null;
        }
        return "http://" + configuredHost() + ":" + port + configuredContextPath() + MCP_ENDPOINT_PATH;
    }

    /**
     * The port this application listens on: {@code local.server.port} - the bound one, the only
     * truth when {@code server.port=0} - and otherwise {@code server.port}.
     *
     * @return the port, or -1 when none is known
     */
    private int configuredPort() {
        Integer bound = environment.getProperty("local.server.port", Integer.class);
        if (bound != null && bound > 0) {
            return bound;
        }
        Integer configured = environment.getProperty("server.port", Integer.class);
        return (configured == null) ? -1 : configured;
    }

    /**
     * Host of the seeded URL: {@code server.address}, or the loopback address when it is unset or a
     * wildcard. Wildcards are the case that matters: {@code 0.0.0.0} is what the server binds, not
     * what a browser can open.
     *
     * @return a host (IPv6 literals bracketed), never blank
     */
    private String configuredHost() {
        String address = environment.getProperty("server.address", "");
        if (address == null) {
            address = "";
        }
        address = address.trim();
        if (address.isEmpty() || "0.0.0.0".equals(address) || "::".equals(address)
                || "[::]".equals(address) || "*".equals(address)) {
            return MCP_SEED_FALLBACK_HOST;
        }
        if (address.indexOf(':') >= 0 && !address.startsWith("[")) {
            return "[" + address + "]";
        }
        return address;
    }

    /**
     * Context path of the application as a URL prefix ("" when there is none), because the MCP
     * endpoint moves with it.
     *
     * @return the prefix, without a trailing slash
     */
    private String configuredContextPath() {
        String contextPath = environment.getProperty("server.servlet.context-path", "");
        if (contextPath == null || contextPath.isBlank() || "/".equals(contextPath.trim())) {
            return "";
        }
        String path = stripTrailingSlash(contextPath.trim());
        return path.startsWith("/") ? path : "/" + path;
    }

    /**
     * Removes trailing slashes, so path concatenation cannot produce a double slash.
     *
     * @param value value to normalize
     * @return the value without trailing slashes
     */
    private static String stripTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /**
     * Logs once at startup which UI settings the server contributes, because {@code ui_settings}
     * acts invisibly from the browser side: it is neither visible in the props response of a
     * running page nor in the settings dialog once the browser has applied it.
     */
    @PostConstruct
    public void logUiSettingsConfig() {
        ObjectNode uiSettings = build();
        List<String> keys = uiSettings.properties().stream().map(Map.Entry::getKey).toList();
        boolean promptConfigured = systemMessage != null && !systemMessage.isBlank();
        LOG.info("Web UI defaults via /props {}={}: {} setting(s), customCss {} chars, systemMessage"
                        + " ({}): \"{}\". They act as defaults only: a browser with its own stored"
                        + " settings keeps those, they are applied on the first visit or after"
                        + " 'Reset Settings to Default'.",
                PROPS_FIELD, keys, keys.size(),
                customCss == null ? 0 : customCss.length(),
                promptConfigured ? PROP_SYSTEM_MESSAGE : "built-in default",
                uiSettings.get(SETTING_SYSTEM_MESSAGE).asString());

        // The MCP seed is the one setting whose effect an operator cannot see in the browser (it
        // appears in a dialog, and only once), so name the URL that would be shipped. Note that
        // this line derives the URL from the configuration: the origin the browser actually used
        // is only known per /props request, and it is what /props ships.
        JsonNode seededServers = uiSettings.get(SETTING_MCP_SERVERS);
        if (seededServers != null && seededServers.isString()) {
            LOG.info("Web UI default MCP server ({}): {} - applied by a browser without settings of"
                            + " its own, i.e. after a cleared cache or on 'Reset Settings to Default'"
                            + " (docs/webui.md §6.2).",
                    PROP_MCP_SERVER_ENABLED, seededServers.asString());
        }
    }
}
