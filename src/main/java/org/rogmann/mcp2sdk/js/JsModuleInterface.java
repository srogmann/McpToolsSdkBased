package org.rogmann.mcp2sdk.js;

import org.graalvm.polyglot.Value;

/**
 * Interface of a JavaScript-module, e.g. `fs` or `archive`.
 */
public interface JsModuleInterface {
    /**
     * Gets the namespace of the JS-module, e.g. "fs" or "archive".
     * @return namespace
     */
    String getNamespace();

    /**
     * Gets the summary which explains the module in a few words, e.g. "`fs.help()` explains controlled file access".
     * Typically the summary mentions the help-function to get details.
     * This summary is used in the MCP tool-description.
     *
     * @return short summary
     */
    String getSummary();

    /**
     * Gets a short help-hint to be used in error messages, e.g. "archive.help() (ZIP/tar, gzip)".
     * @return short help-hint
     */
    String getHelpTip();

    /**
     * Indicates whether this module is reachable via a CommonJS {@code require()}
     * (including its {@code node:}-prefixed alias), e.g. {@code require('fs')} /
     * {@code require('node:fs')}.
     * <p>
     * Modules that are not statically available as plain function namespaces (e.g. the
     * optional, server-dependent {@code mcp} module) should return {@code false}.
     * </p>
     * @return true if the module may be required (default), false otherwise
     */
    default boolean hasRequireAlias() {
        return true;
    }

    /**
     * Indicates whether this module is currently enabled and may be bound into the
     * JavaScript context (and, if {@link #hasRequireAlias()} is true, be required).
     * <p>
     * This allows modules to be enabled/disabled at runtime, e.g. depending on a
     * system property ({@code mcp}) or, later, per user.
     * </p>
     * <p>
     * Asked <em>per call</em> while wiring. A module whose availability depends on the caller -
     * the {@code board} module exists only for a thread of a running fork - is therefore not
     * advertised in the tool description by this method; see {@link #isAdvertised()}.
     * </p>
     * @return true if the module is enabled (default), false otherwise
     */
    default boolean isEnabled() {
        return true;
    }

    /**
     * Indicates whether the module's {@link #getSummary()} appears in the MCP tool-description of
     * the JavaScript tool. The description is built once at startup and travels in every request,
     * so advertising is a configuration question (a property) while {@link #isEnabled()} is a
     * per-call question (is there something to bind <em>here</em>). For a module that does not
     * depend on the caller the two are the same, which is why this defaults to it.
     *
     * @return true if the summary is advertised (default: whatever {@link #isEnabled()} says)
     */
    default boolean isAdvertised() {
        return isEnabled();
    }

    /**
     * Registers the module in the js-bindings.
     * @param jsBindings us-bindings
     * @return an optional per-call resource that must be closed after the JavaScript call
     *         (e.g. an MCP client connection), or {@code null} if the module keeps no
     *         per-call resources
     */
    AutoCloseable wireApi(Value jsBindings);

}
