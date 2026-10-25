package org.rogmann.mcp2sdk.js;

import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.rogmann.mcp2sdk.agent.AgentBoardAccess;
import org.rogmann.mcp2sdk.agent.ForkCallers;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Bridge between GraalVM JavaScript and the board of the running fork
 * ({@code AgentBoardAccess}, docs/subagent.md §14, P1).
 *
 * <p><b>What it is for.</b> In the measured run {@code b-505c} the rendezvous of two threads cost
 * three extra model turns and 822k prompt tokens against 410k, because every glance at the board is
 * a turn when the caller is a model. Inside a script a glance is free and a wait is a sleep, so the
 * board stops being something to check and becomes something to answer. That is the only reason
 * this module exists; everything it can do, {@code agent_board} can already do, on the same board,
 * under the same monitor, with the same rules (see {@link AgentBoardAccess} - both surfaces share
 * it so that the comparison M4 compares one board and not two).</p>
 *
 * <p><b>Who sees it.</b> Two conditions, deliberately different (decision D3): the system property
 * {@value #PROP_ENABLED} decides whether the module is <em>named</em> in the description of
 * {@code javascript_tool} at all - off by default, and off means the word "board" appears nowhere a
 * model reads it. And {@link ForkCallers#current()} decides whether it is <em>bound</em> in this
 * call: outside a thread of a fork there is nothing to bind, and {@code require('board')} then falls
 * back to the plain list of available modules rather than pretending a board exists.</p>
 *
 * <p><b>Refusals are throws</b>, not result texts: {@code try { board.claim('m3') } catch (e) {
 * console.log(e.message) }}. The MCP surface keeps answering JSON with {@code refused} - each
 * surface speaks its own language, and neither has to guess what the other meant.</p>
 *
 * <h3>Usage in JavaScript (a thread of a fork batch with board:true)</h3>
 * <pre>{@code
 * board.post('number: 447', {kind: 'INFO'});
 * var mine = board.mine();
 * var answer = board.wait('m1', {timeoutMs: 20000});
 * if (answer.changed) { console.log(JSON.stringify(answer.entry.thread)); }
 * }</pre>
 */
public class JsAgentBoardBridge implements JsModuleInterface {

    /**
     * Switch of the whole module (decision D3): {@code -Dagent.fork.board.js=true}. Off by default,
     * like {@code mcp.js.mcpServer.url} is off by not being set - a module nobody configured gets
     * no word in the tool description.
     */
    public static final String PROP_ENABLED = AgentBoardAccess.PROP_JS_SURFACE;

    /** @return true when the module is switched on, whatever the current call is */
    public static boolean enabledByProperty() {
        return AgentBoardAccess.isJsSurfaceOn();
    }

    @Override
    public String getNamespace() {
        return "board";
    }

    @Override
    public String getSummary() {
        return "`board.help()` explains the message board of the running fork";
    }

    @Override
    public String getHelpTip() {
        return "board.help() (fork message board)";
    }

    /**
     * Bound only for a thread of a fork and only while the module is switched on. Asked per call,
     * which is exactly the question: this is either a fork thread's script or it is not.
     */
    @Override
    public boolean isEnabled() {
        return enabledByProperty() && AgentBoardAccess.isThreadOfAFork();
    }

    /**
     * The description is one text for every conversation, so the module is advertised by
     * configuration, not by the accident of who is calling (see
     * {@link JsModuleInterface#isAdvertised()}).
     */
    @Override
    public boolean isAdvertised() {
        return enabledByProperty();
    }

    @Override
    public AutoCloseable wireApi(Value jsBindings) {
        jsBindings.putMember(getNamespace(), createBoardNamespace());
        return null;
    }

    /**
     * Creates the {@code board} namespace. Public so a unit test can drive it over a real Graal
     * context without a fork, a server or a model - the namespace itself holds no state, every call
     * resolves the caller of the current thread again.
     *
     * @return ProxyObject with the board functions
     */
    public static ProxyObject createBoardNamespace() {
        Map<String, Object> methods = new HashMap<>();

        methods.put("help", (ProxyExecutable) args -> AgentBoardAccess.helpOf(ForkCallers.current()));

        methods.put("post", (ProxyExecutable) args -> {
            String subject = (args == null || args.length < 1) ? null : text(args[0]);
            if (subject == null) {
                throw new JsUserRuntimeException("Usage: board.post(subject[, {kind: 'INFO'}])");
            }
            return guard(() -> GraalProxies.toProxyObject(access().post(subject, option(args, 1, "kind"))));
        });

        methods.put("comment", (ProxyExecutable) args -> {
            String taskId = (args == null || args.length < 1) ? null : text(args[0]);
            String line = (args == null || args.length < 2) ? null : text(args[1]);
            if (taskId == null || line == null) {
                throw new JsUserRuntimeException("Usage: board.comment(taskId, text)");
            }
            return guard(() -> GraalProxies.toProxyObject(access().comment(taskId, line)));
        });

        methods.put("list", (ProxyExecutable) args -> guard(() -> GraalProxies.toProxyObject(access().list())));

        methods.put("get", (ProxyExecutable) args -> {
            String taskId = (args == null || args.length < 1) ? null : text(args[0]);
            if (taskId == null) {
                throw new JsUserRuntimeException("Usage: board.get(taskId)");
            }
            return guard(() -> GraalProxies.toProxyObject(access().get(taskId)));
        });

        methods.put("mine", (ProxyExecutable) args -> guard(() -> GraalProxies.toProxyObject(access().mine())));

        methods.put("claim", (ProxyExecutable) args -> {
            String taskId = (args == null || args.length < 1) ? null : text(args[0]);
            if (taskId == null) {
                throw new JsUserRuntimeException("Usage: board.claim(taskId)");
            }
            return guard(() -> GraalProxies.toProxyObject(access().claim(taskId)));
        });

        methods.put("close", (ProxyExecutable) args -> {
            String taskId = (args == null || args.length < 1) ? null : text(args[0]);
            if (taskId == null) {
                throw new JsUserRuntimeException("Usage: board.close(taskId[, outcome])");
            }
            String outcome = (args.length >= 2) ? text(args[1]) : null;
            return guard(() -> GraalProxies.toProxyObject(access().close(taskId, outcome)));
        });

        methods.put("wait", (ProxyExecutable) args -> {
            String taskId = (args == null || args.length < 1) ? null : text(args[0]);
            if (taskId == null) {
                throw new JsUserRuntimeException("Usage: board.wait(taskId[, {timeoutMs: 20000}])");
            }
            Long timeoutMs = timeoutOf(args, 1);
            return guard(() -> GraalProxies.toProxyObject(access().waitFor(taskId, timeoutMs)));
        });

        return ProxyObject.fromMap(methods);
    }

    // --- internals ---

    /** The board of the thread whose script is running; throws with a usable text if there is none. */
    private static AgentBoardAccess access() {
        return AgentBoardAccess.open(ForkCallers.current());
    }

    /**
     * Runs one board call. The board refuses with {@code IllegalStateException} /
     * {@code IllegalArgumentException} - right for a tool, and here the same text has to arrive as a
     * JavaScript exception the script can catch, which is what {@link JsUserRuntimeException} is for
     * in this house.
     */
    private static Object guard(Supplier<Object> call) {
        try {
            return call.get();
        } catch (JsUserRuntimeException e) {
            throw e;
        } catch (RuntimeException e) {
            String message = (e.getMessage() == null || e.getMessage().isBlank())
                    ? e.getClass().getSimpleName() : e.getMessage();
            throw new JsUserRuntimeException(message, e);
        }
    }

    /**
     * @return the trimmed text of an argument, or null when there is nothing usable. A number is
     *         text enough (a posted finding often is one); an object is not, and saying so through
     *         the Usage message beats turning {@code [object Object]} into a board entry.
     */
    private static String text(Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString() && !value.isNumber() && !value.isBoolean()) {
            return null;
        }
        String text;
        try {
            text = value.asString();
        } catch (RuntimeException e) {
            return null;
        }
        return (text == null || text.isBlank()) ? null : text.trim();
    }

    /**
     * Reads one member of an options object: {@code board.post(x, {kind: 'INFO'})}. A bare string
     * where the options belong is accepted for the kind - the way the other modules of this house
     * forgive a short form ({@code yaml.verify(path, schema, 'id')}).
     */
    private static String option(Value[] args, int index, String member) {
        if (args == null || args.length <= index || args[index] == null || args[index].isNull()) {
            return null;
        }
        Value options = args[index];
        if (options.isString() && "kind".equals(member)) {
            return text(options);
        }
        if (!options.hasMembers()) {
            return null;
        }
        Value value = options.getMember(member);
        return (value == null || value.isNull()) ? null : text(value);
    }

    /**
     * The requested sleep, in milliseconds, or null for the board's default. A number straight from
     * the second argument is forgiven; anything that is not a number is refused rather than read as
     * zero, because a zero-second wait that reports "nothing happened" is the false green of this
     * function.
     */
    private static Long timeoutOf(Value[] args, int index) {
        if (args == null || args.length <= index || args[index] == null || args[index].isNull()) {
            return null;
        }
        Value given = args[index];
        Value value = given;
        if (!given.isNumber()) {
            if (!given.hasMembers()) {
                throw new JsUserRuntimeException("board.wait takes a timeout in milliseconds, e.g."
                        + " board.wait('m1', {timeoutMs: 20000}) - got: " + given);
            }
            value = given.getMember("timeoutMs");
            if (value == null || value.isNull()) {
                return null;
            }
        }
        if (!value.isNumber()) {
            throw new JsUserRuntimeException("timeoutMs must be a number of milliseconds, got: " + value);
        }
        long millis = value.asLong();
        if (millis <= 0L) {
            throw new JsUserRuntimeException("timeoutMs must be positive; do not wait at all instead"
                    + " of asking whether waiting worked");
        }
        return millis;
    }
}
