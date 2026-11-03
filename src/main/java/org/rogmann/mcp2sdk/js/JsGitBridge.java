package org.rogmann.mcp2sdk.js;

import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.rogmann.mcp2sdk.git.Config;
import org.rogmann.mcp2sdk.git.GitProcess;
import org.rogmann.mcp2sdk.git.forge.ForgeConfig;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bridge between GraalVM JavaScript and {@link JsGit}: the {@code git} namespace.
 *
 * <p>
 * The namespace is deliberately small - the reading functions on the local repository, the server
 * description and the help text. A git MCP tool was ruled out on purpose (a tool schema costs
 * context in every request, while a git call is rare and heavily parameterised); the module costs
 * one line in the tool description and is otherwise only read when {@code git.help()} is called.
 * </p>
 *
 * <h3>Usage in JavaScript</h3>
 * <pre>{@code
 * var st = git.status({untracked: 'all'});
 * print(st.branch.branch, st.counts.staged, st.counts.unstaged);
 * git.remotes().forEach(r => print(r.name, r.fetch));
 * var info = git.hub.info();          // configured server, or the missing property
 * var cmp = git.hub.compare({repo: 'o/r', from: 'main', to: 'feature/x', diff: true});
 * }</pre>
 *
 * <h3>Availability</h3>
 * <p>
 * The module can be switched off with {@code -Dmcp2sdk.module.git.enabled=false} and is not
 * advertised when no git executable is found - a module that cannot work should not appear in the
 * tool description. The server part is bound independently of that: without
 * {@code mcp2sdk.git.remote.url} only {@code git.hub.info()} exists and names the missing property.
 * </p>
 */
public class JsGitBridge implements JsModuleInterface {

    /** Switches the whole module off. */
    public static final String PROP_ENABLED = "mcp2sdk.module.git.enabled";

    @Override
    public String getNamespace() {
        return "git";
    }

    @Override
    public String getSummary() {
        return "`git.help()` explains read-only git access (status, branches, remotes, history,"
                + " file contents, diffs) and the configured GitHub/GHE server (compare, pull"
                + " requests)";
    }

    @Override
    public String getHelpTip() {
        return "git.help() (status, branches, remotes, history)";
    }

    @Override
    public boolean isEnabled() {
        return Config.bool(PROP_ENABLED, true);
    }

    @Override
    public boolean isAdvertised() {
        return isEnabled() && GitProcess.locateBinary() != null;
    }

    @Override
    public AutoCloseable wireApi(Value jsBindings) {
        jsBindings.putMember("git", createGitNamespace(jsBindings.getMember("Uint8Array")));
        return null;
    }

    /**
     * Creates the {@code git} namespace object for a JavaScript context.
     *
     * @param uint8ArrayCtor the JS {@code Uint8Array} constructor from the bindings
     * @return ProxyObject with the git methods
     */
    public static ProxyObject createGitNamespace(Value uint8ArrayCtor) {
        Map<String, Object> methods = new HashMap<>();

        methods.put("help", (ProxyExecutable) args -> JsGit.help());

        // repo([{repo}])
        methods.put("repo", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(unchecked(() -> JsGit.repo(optionMap(args, 0)))));

        // status([{untracked, maxFiles, repo}])
        methods.put("status", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(unchecked(() -> JsGit.status(optionMap(args, 0)))));

        // remotes([{repo}])
        methods.put("remotes", (ProxyExecutable) args ->
                GraalProxies.toProxyArray(unchecked(() -> JsGit.remotes(optionMap(args, 0)))));

        // branches([{remote, max, repo}])
        methods.put("branches", (ProxyExecutable) args ->
                GraalProxies.toProxyArray(unchecked(() -> JsGit.branches(optionMap(args, 0)))));

        // log([{...}])
        methods.put("log", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(unchecked(() -> JsGit.log(optionMap(args, 0)))));

        // eachCommit([{...}], function (commit) { ... })
        methods.put("eachCommit", (ProxyExecutable) args -> {
            requireArgs(args, 2, "git.eachCommit({options}, function (commit) { ... })");
            Value callback = args[1];
            if (callback == null || callback.isNull() || !callback.canExecute()) {
                throw new IllegalArgumentException("git.eachCommit needs a function as its second "
                        + "argument, e.g. git.eachCommit({max: 10}, c => print(c.subject)) - see "
                        + "git.help()");
            }
            return GraalProxies.toProxyObject(unchecked(() -> JsGit.eachCommit(optionMap(args, 0),
                    commit -> {
                        Value answer = callback.execute(GraalProxies.toProxyObject(commit));
                        // Only an explicit false stops the query; undefined or any other value
                        // continues (a callback without a return value is the common case).
                        return answer == null || answer.isNull()
                                || !(answer.isBoolean() && !answer.asBoolean());
                    })));
        });

        // file({rev, path, maxBytes})
        methods.put("file", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(unchecked(() -> JsGit.file(optionMap(args, 0)))));

        // blob({rev, path, maxBytes}) -> Uint8Array
        methods.put("blob", (ProxyExecutable) args -> {
            byte[] data = unchecked(() -> JsGit.blob(optionMap(args, 0)));
            return GraalProxies.toUint8Array(uint8ArrayCtor, data);
        });

        // tree({rev, path, max})
        methods.put("tree", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(unchecked(() -> JsGit.tree(optionMap(args, 0)))));

        // diff({path, from, to, commit, maxFiles, maxBytes, patch})
        methods.put("diff", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(unchecked(() -> JsGit.diff(optionMap(args, 0)))));

        methods.put("hub", createHubNamespace());

        return ProxyObject.fromMap(methods);
    }

    /**
     * The server part of the namespace. {@code info} exists always (it is how a caller learns that
     * no server is configured); the reading functions exist only when a server is configured - a
     * function that cannot work should not be there at all, so that no caller runs into an error
     * it could not have known.
     *
     * @return ProxyObject with the hub members
     */
    private static ProxyObject createHubNamespace() {
        Map<String, Object> members = new LinkedHashMap<>();
        members.put("info", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(JsGit.hubInfo()));
        if (!ForgeConfig.isConfigured()) {
            return ProxyObject.fromMap(members);
        }
        members.put("repo", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(unchecked(() -> JsGit.hubRepo(optionMap(args, 0)))));
        members.put("branches", (ProxyExecutable) args ->
                GraalProxies.toProxyArray(unchecked(() -> JsGit.hubBranches(optionMap(args, 0)))));
        members.put("tree", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(unchecked(() -> JsGit.hubTree(optionMap(args, 0)))));
        members.put("file", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(unchecked(() -> JsGit.hubFile(optionMap(args, 0)))));
        members.put("commits", (ProxyExecutable) args ->
                GraalProxies.toProxyArray(unchecked(() -> JsGit.hubCommits(optionMap(args, 0)))));
        members.put("compare", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(unchecked(() -> JsGit.hubCompare(optionMap(args, 0)))));
        members.put("compareWithLocal", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(unchecked(() ->
                        JsGit.hubCompareWithLocal(optionMap(args, 0)))));
        members.put("pulls", (ProxyExecutable) args ->
                GraalProxies.toProxyArray(unchecked(() -> JsGit.hubPulls(optionMap(args, 0)))));
        members.put("pull", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(unchecked(() -> JsGit.hubPull(optionMap(args, 0)))));
        members.put("pullFiles", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(unchecked(() -> JsGit.hubPullFiles(optionMap(args, 0)))));
        members.put("pullComments", (ProxyExecutable) args ->
                GraalProxies.toProxyObject(unchecked(() ->
                        JsGit.hubPullComments(optionMap(args, 0)))));
        return ProxyObject.fromMap(members);
    }

    // ---------------------------------------------------------------
    // Argument helpers
    // ---------------------------------------------------------------

    /** Throws the usage line when a required argument is missing. */
    private static void requireArgs(Value[] args, int count, String usage) {
        if (args == null || args.length < count || args[0] == null || args[0].isNull()) {
            throw new IllegalArgumentException("Usage: " + usage + " - see git.help()");
        }
    }

    /** Converts the option object at {@code index} (may be absent/null) into a Java map. */
    private static Map<String, Object> optionMap(Value[] args, int index) {
        if (args == null || args.length <= index || args[index] == null || args[index].isNull()) {
            return Map.of();
        }
        Value obj = args[index];
        if (!obj.hasMembers()) {
            throw new IllegalArgumentException("The options must be an object, e.g. "
                    + "git.status({untracked: 'all'}) - see git.help()");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (String key : obj.getMemberKeys()) {
            Value value = obj.getMember(key);
            if (value == null || value.isNull()) {
                map.put(key, null);
            } else if (value.isBoolean()) {
                map.put(key, value.asBoolean());
            } else if (value.isNumber()) {
                map.put(key, value.asLong());
            } else if (value.isString()) {
                map.put(key, value.asString());
            } else {
                throw new IllegalArgumentException("Option '" + key
                        + "' must be a string, number or boolean - see git.help()");
            }
        }
        return map;
    }

    /** Runs a throwing supplier, wrapping IOException in a JS-visible runtime exception. */
    private static <T> T unchecked(IoSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (IOException e) {
            throw new JsUserRuntimeException(e.getMessage(), e);
        }
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws IOException;
    }
}
