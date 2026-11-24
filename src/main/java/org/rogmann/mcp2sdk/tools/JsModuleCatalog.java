package org.rogmann.mcp2sdk.tools;

import org.rogmann.mcp2sdk.js.JsModuleInterface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The JS modules of the {@code javascript} tool and their runtime switches.
 *
 * <p>
 * There is exactly one catalog per process, and it holds exactly one set of modules
 * ({@link JavaScriptTool#createModules()}). The bridges are stateless and wired per call, so a
 * second set would only be a second answer to "is this module enabled" (and a second connection
 * for the modules whose constructor reads one - {@code mcp} asks the MCP server for its tool names).
 * </p>
 *
 * <p><b>Two different questions, two different sources.</b> {@link JsModuleInterface#isEnabled()}
 * answers whether the <em>configuration</em> offers a module at all
 * ({@code -Dmcp2sdk.module.git.enabled=false}, no git executable, no MCP server URL, no fork
 * thread). That is not a switch: a module the configuration denies is absent, is not listed, and
 * cannot be switched on here. This catalog adds the second question - is the module switched
 * <em>on</em> right now - with a plain boolean per name, kept in memory, effective from the next
 * tool call (the modules are bound per call, a running script keeps its context).
 * </p>
 *
 * <p><b>Only the overrides are stored.</b> The set holds what was switched <em>off</em>; a module
 * that is added in a later build is therefore on by default, and the start-up value comes from
 * {@value #PROP_DISABLED} (e.g. {@code -Dmcp.js.modules.disabled=git,shipped}) - without that
 * property a runtime switch would be forgotten at the next restart, as with
 * {@code tools.active} for whole tools.
 * </p>
 *
 * <p><b>The shipped libraries are one block.</b> The modules of {@code js/tools/modules.json} load
 * each other ({@code diagram} requires its frontends, backends and layout; {@code pdftext} requires
 * {@code diagram}'s page and PDF parts), so they are switched as {@value #SHIPPED} rather than one
 * by one. Their dependencies on bound namespaces ({@code pdfedit}, {@code pptx}) are not enforced:
 * a namespace that is off answers with {@code Cannot find module} at the call that needs it, which
 * names the modules that are bound.
 * </p>
 */
final class JsModuleCatalog {

    private static final Logger LOGGER = LoggerFactory.getLogger(JsModuleCatalog.class);

    /**
     * Property with the modules switched off at start-up, comma-separated, e.g.
     * {@code -Dmcp.js.modules.disabled=git,shipped}. Valid values are the namespaces of the tool
     * and the block name {@value #SHIPPED}; an unknown name aborts the start (a typo would
     * otherwise look like "the switch does not work").
     */
    static final String PROP_DISABLED = "mcp.js.modules.disabled";

    /** Switch name of the libraries that ship inside the server's jar (one block). */
    static final String SHIPPED = "shipped";

    private static final JsModuleCatalog INSTANCE = new JsModuleCatalog();

    /** The modules in the tool's insertion order (built once, shared by tool, chat and Web UI). */
    private final Map<String, JsModuleInterface> modules;

    /** The names switched off at runtime - only overrides, so a new module starts on. */
    private final Set<String> switchedOff = ConcurrentHashMap.newKeySet();

    /**
     * Builds the catalog: the modules, and the switches configured for the start.
     */
    private JsModuleCatalog() {
        this.modules = Collections.unmodifiableMap(JavaScriptTool.createModules());
        this.switchedOff.addAll(configuredSwitches());
        if (!switchedOff.isEmpty()) {
            LOGGER.info("JS modules switched off at start-up ({}): {}", PROP_DISABLED,
                    join(switchedOff));
        }
    }

    /**
     * The catalog of this process.
     * @return the catalog
     */
    static JsModuleCatalog getInstance() {
        return INSTANCE;
    }

    /**
     * The modules of the tool, in the tool's insertion order.
     * @return the unmodifiable module map
     */
    Map<String, JsModuleInterface> modules() {
        return modules;
    }

    /**
     * The switchable names: every namespace of the tool and the shipped block.
     * @return the names, insertion-ordered, the block last
     */
    List<String> names() {
        List<String> names = new ArrayList<>(modules.keySet());
        names.add(SHIPPED);
        return names;
    }

    /**
     * Validates a switch name and returns it in normalized (lowercase) form.
     * @param name the name as the caller wrote it
     * @return the normalized name
     * @throws IllegalArgumentException if the name is empty or not switchable
     */
    String requireKnown(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A JS module name is required; switchable are: "
                    + join(names()));
        }
        String key = name.trim().toLowerCase(Locale.ROOT);
        if (!SHIPPED.equals(key) && !modules.containsKey(key)) {
            throw new IllegalArgumentException("Unknown JS module '" + name + "'; switchable are: "
                    + join(names()));
        }
        return key;
    }

    /**
     * Whether the runtime switch of a name is on (the configuration is not asked).
     * @param key the normalized switch name
     * @return true if nothing switched the name off
     */
    boolean isSwitchedOn(String key) {
        return !switchedOff.contains(key);
    }

    /**
     * Whether the block of shipped libraries is switched on.
     * @return true if {@code loadShipped(name)} may load a library
     */
    boolean isShippedSwitchedOn() {
        return !switchedOff.contains(SHIPPED);
    }

    /**
     * Whether the configuration offers a name at all.
     * @param key the normalized switch name
     * @return true if the module exists and {@link JsModuleInterface#isEnabled()} says yes
     */
    boolean isAvailable(String key) {
        if (SHIPPED.equals(key)) {
            return !ShippedJsModules.byName().isEmpty();
        }
        JsModuleInterface module = modules.get(key);
        return module != null && module.isEnabled();
    }

    /**
     * Whether a name is bound right now: offered by the configuration and not switched off.
     * @param name the name as the caller wrote it
     * @return true if the next script call would bind it
     * @throws IllegalArgumentException if the name is not switchable
     */
    boolean isBound(String name) {
        String key = requireKnown(name);
        return isSwitchedOn(key) && isAvailable(key);
    }

    /**
     * Switches a module (or the shipped block) on or off for all callers of this server.
     * <p>
     * Switching <em>on</em> is refused for a module the configuration denies - a switch must not
     * overrule {@code -D...=false}; it would bind a module the operator ruled out. Switching
     * <em>off</em> is always allowed. Effective from the next tool call: a running script has
     * already wired its modules.
     * </p>
     * @param name the namespace ({@code fs}) or the block name {@value #SHIPPED}
     * @param enabled the desired state
     * @return true if the switch changed, false if it already had that state
     * @throws IllegalArgumentException if the name is not switchable or the configuration denies it
     */
    boolean setEnabled(String name, boolean enabled) {
        String key = requireKnown(name);
        if (enabled && !isAvailable(key)) {
            throw new IllegalArgumentException("The JS module '" + key + "' is switched off by the"
                    + " configuration (its isEnabled() is false, e.g. a -D...=false property); it"
                    + " can be switched off at runtime but not on - change the configuration and"
                    + " restart the server instead.");
        }
        boolean changed = enabled ? switchedOff.remove(key) : switchedOff.add(key);
        if (changed) {
            LOGGER.info("JS module '{}' switched {} at runtime", key, enabled ? "on" : "off");
        }
        return changed;
    }

    /**
     * Forgets the runtime switches (the modules themselves stay, they are stateless).
     * <p>
     * Needed by tests: the catalog is a singleton, so a switch of one test would be visible in the
     * next (same JVM, see the Maven Surefire default).
     * </p>
     */
    void clearSwitchesForTest() {
        switchedOff.clear();
    }

    /**
     * Reads {@value #PROP_DISABLED} and validates it strictly - an invalid value aborts the start
     * with the property name and its value quoted.
     * @return the configured switches (empty if the property is not set)
     */
    private Set<String> configuredSwitches() {
        String value = System.getProperty(PROP_DISABLED);
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        Set<String> configured = new LinkedHashSet<>();
        for (String part : value.split(",", -1)) {
            String name = part.trim().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) {
                throw new IllegalStateException("'" + PROP_DISABLED + "' contains an empty entry"
                        + " (value: '" + value + "'); write a comma-separated list of module names: "
                        + join(names()));
            }
            if (!SHIPPED.equals(name) && !modules.containsKey(name)) {
                throw new IllegalStateException("'" + PROP_DISABLED + "' names the unknown module '"
                        + name + "' (value: '" + value + "'); switchable are: " + join(names()));
            }
            configured.add(name);
        }
        return configured;
    }

    /**
     * Formats names for a message.
     * @param names the names
     * @return the names in single quotes, comma-separated, sorted for a stable message
     */
    private static String join(Collection<String> names) {
        List<String> sorted = new ArrayList<>(names);
        Collections.sort(sorted);
        StringBuilder sb = new StringBuilder();
        for (String name : sorted) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append('\'').append(name).append('\'');
        }
        return sb.isEmpty() ? "(none)" : sb.toString();
    }
}
