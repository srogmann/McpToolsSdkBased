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
     * @return true if the module is enabled (default), false otherwise
     */
    default boolean isEnabled() {
        return true;
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
