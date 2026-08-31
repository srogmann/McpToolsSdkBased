package org.rogmann.mcp2sdk.examples;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.SandboxPolicy;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.SourceSection;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.rogmann.mcp2sdk.ToolSpecWithState;
import org.rogmann.mcp2sdk.ToolState;
import org.rogmann.mcp2sdk.js.JsArchiveBridge;
import org.rogmann.mcp2sdk.js.JsCryptoBridge;
import org.rogmann.mcp2sdk.js.JsFileSystem;
import org.rogmann.mcp2sdk.js.JsFileSystemBridge;
import org.rogmann.mcp2sdk.js.JsMcpProxyBridge;
import org.rogmann.mcp2sdk.js.JsModuleInterface;
import org.rogmann.mcp2sdk.js.JsSearchBridge;
import org.rogmann.mcp2sdk.poi.DocxToolBoxJsBridge;
import org.rogmann.mcp2sdk.poi.PoiToolBoxJsBridge;
import org.rogmann.mcp2sdk.poi.PptxToolBoxJsBridge;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MCP tool implementation for writing and executing small JavaScript scripts.
 * Uses GraalVM Polyglot to run JavaScript code and captures console.log output.
 * <p>
 * Each script runs with an execution timeout (default {@value #DEFAULT_TIMEOUT_SECONDS} seconds,
 * overridable per call via {@code timeoutSeconds} and globally via the system property
 * {@value #PROP_TIMEOUT_SECONDS}). A timed-out script is cancelled by closing its GraalVM
 * {@link Context} from the waiting thread; the caller then receives an error containing the
 * JavaScript stack of the cancellation point plus the output produced so far, while the full Java
 * stack trace is written to the server's error log.
 * </p>
 * <p>
 * For that cancellation the script cannot run on the calling thread: a GraalVM {@link Context} is
 * bound to the thread that created it and may only be used from there, so the whole execution
 * (context creation, module wiring, evaluation) is moved into a worker thread
 * ({@link #JS_EXECUTOR}). The calling thread waits for the result with a deadline and cancels the
 * context of the worker thread if the deadline elapses - GraalVM explicitly supports closing a
 * context that is executing on <i>another</i> thread (see {@link Context#close(boolean)}).
 * </p>
 */
public class JavaScriptTool {

    private static final Logger LOGGER = Logger.getLogger(JavaScriptTool.class.getName());

    private static final String NAME = "javascript_tool";

    /**
     * System property to configure the default execution timeout in seconds, e.g.
     * {@code -Dmcp.js.executionTimeoutSeconds=120}. Used when a call does not pass
     * {@code timeoutSeconds}.
     */
    private static final String PROP_TIMEOUT_SECONDS = "mcp.js.executionTimeoutSeconds";

    /** Execution timeout in seconds used when neither {@code timeoutSeconds} nor the system property is given. */
    private static final long DEFAULT_TIMEOUT_SECONDS = 60;

    /** Smallest accepted execution timeout in seconds. */
    private static final long MIN_TIMEOUT_SECONDS = 1;

    /** Largest accepted execution timeout in seconds (long jobs should be split up instead). */
    private static final long MAX_TIMEOUT_SECONDS = 1800;

    /**
     * Time in seconds to wait after a cancellation request for the worker thread to report its
     * (cancelled) result. Cancellation takes effect at the next guest safepoint, so a script that
     * is blocked inside a host call needs longer than that and is then left running in the
     * background (the thread is a daemon thread and does not keep the JVM alive).
     */
    private static final long CANCEL_GRACE_SECONDS = 10;

    /** Maximum number of JavaScript stack frames reported in a timeout message. */
    private static final int MAX_STACK_FRAMES = 24;

    /** Maximum number of characters of already produced output reported in a timeout message. */
    private static final int MAX_TIMEOUT_OUTPUT_CHARS = 4000;

    /** Maximum length of the source snippet shown for a JavaScript stack frame. */
    private static final int MAX_SNIPPET_CHARS = 160;

    /** Counter for readable worker thread names. */
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

    /**
     * Runs each JavaScript call in its own thread. Needed for the timeout handling: the context is
     * created and used by the worker thread, while the calling thread enforces the deadline and
     * cancels the context. Daemon threads, so a script that cannot be cancelled never blocks the
     * JVM shutdown.
     */
    private static final ExecutorService JS_EXECUTOR = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "javascript-tool-" + THREAD_COUNTER.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    /** Default execution timeout resolved from the system property (read once at class initialization). */
    private static final long EFFECTIVE_DEFAULT_TIMEOUT_SECONDS = resolveConfiguredTimeoutSeconds();

    /** tool state (active-flag, statistics) */
    private final ToolState state;

    /** The available JS-modules (namespace -&gt; module), wired into the JavaScript context on each call. */
    private final LinkedHashMap<String, JsModuleInterface> modules;

    private JavaScriptTool(LinkedHashMap<String, JsModuleInterface> modules) {
        this.modules = modules;
        this.state = new ToolState();
    }

    /**
     * Creates the set of available JS-modules in a deterministic (insertion) order.
     * @return module map
     */
    private static LinkedHashMap<String, JsModuleInterface> createModules() {
        LinkedHashMap<String, JsModuleInterface> modules = new LinkedHashMap<>();
        modules.put("fs", new JsFileSystemBridge());
        modules.put("crypto", new JsCryptoBridge());
        modules.put("archive", new JsArchiveBridge());
        // Grep-like search over files, directories and archives (docs/js/search.md).
        // Uses the same path rules as `fs` and the same archive formats as `archive`.
        modules.put("search", new JsSearchBridge());
        modules.put("poi", new PoiToolBoxJsBridge());
        modules.put("docx", new DocxToolBoxJsBridge());
        modules.put("pptx", new PptxToolBoxJsBridge());
        modules.put("mcp", new JsMcpProxyBridge());
        return modules;
    }

    /**
     * Builds the MCP tool-description from the summaries of the enabled modules.
     * @param modules available modules
     * @return description text
     */
    private static String buildDescription(LinkedHashMap<String, JsModuleInterface> modules) {
        StringBuilder sb = new StringBuilder(
                "Use this tool to write and execute small scripts in JavaScript to do calculations.");
        boolean first = true;
        for (JsModuleInterface m : modules.values()) {
            if (!m.isEnabled()) {
                continue;
            }
            sb.append(first ? " " : ", ");
            sb.append(m.getSummary());
            first = false;
        }
        if (!first) {
            sb.append(".");
        }
        sb.append(" Scripts run with an execution timeout (default ")
          .append(EFFECTIVE_DEFAULT_TIMEOUT_SECONDS)
          .append(" s, override with 'timeoutSeconds'); a script exceeding it is cancelled and the")
          .append(" JavaScript stack of the cancellation point is reported.");
        return sb.toString();
    }

    /**
     * Creates the synchronous tool specification for the JavaScript execution tool.
     * <p>
     * The tool accepts {@code script} (inline source), {@code path} (a saved JS file from the
     * project), or both. When both are given, the inline {@code script} runs first as a
     * pre-initialization step and the file content is appended - the concatenation is executed
     * as one script. This lets the LLM keep a stable bootstrap/helper prefix in {@code script}
     * while the (frequently edited) main logic lives in the file, without copying the
     * initialization into every file or editing it in. All variants are routed through the same
     * sandbox engine ({@link #runScript}), so a JavaScript file always runs under exactly the
     * same restrictions as inline source.
     * </p>
     * @return the tool specification and its state
     */
    public static ToolSpecWithState createToolInstance() {
        LinkedHashMap<String, JsModuleInterface> modules = createModules();
        String description = buildDescription(modules);

        // Define Input Schema properties
        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> scriptProp = new HashMap<>();
        scriptProp.put("type", "string");
        scriptProp.put("description",
                "JavaScript source code to execute inline. If 'path' is also given, this script "
                + "runs first (e.g. pre-initializations / helpers) and the file content is "
                + "appended afterwards. At least one of 'script' or 'path' must be provided.");
        properties.put("script", scriptProp);

        Map<String, Object> pathProp = new HashMap<>();
        pathProp.put("type", "string");
        pathProp.put("description",
                "Path of an existing JavaScript file in the project (relative to the project base "
                + "directory, optionally prefixed with /addonName/...), e.g. created/edited with "
                + "create_new_file/edit_file or fs.writeFile. It is read with the same controlled "
                + "fs access and executed in exactly the same sandbox as 'script'. If 'script' is "
                + "also given, the file runs after the inline script; otherwise the file alone is "
                + "executed. At least one of 'script' or 'path' must be provided.");
        properties.put("path", pathProp);

        Map<String, Object> timeoutProp = new HashMap<>();
        timeoutProp.put("type", "integer");
        timeoutProp.put("description",
                "Optional execution timeout in seconds (default " + EFFECTIVE_DEFAULT_TIMEOUT_SECONDS
                + ", allowed " + MIN_TIMEOUT_SECONDS + ".." + MAX_TIMEOUT_SECONDS + "). A script "
                + "that exceeds the timeout is cancelled; the error message then contains the "
                + "JavaScript stack at the cancellation point and the output written so far.");
        properties.put("timeoutSeconds", timeoutProp);

        // At least one of 'script' or 'path' must be provided; enforced in call().
        JsonSchema inputSchema = new JsonSchema("object", properties, List.of(), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(NAME)
            .title("JavaScript Tool")
            .description(description)
            .inputSchema(inputSchema)
            .build();

        JavaScriptTool toolImpl = new JavaScriptTool(modules);

        return new ToolSpecWithState(McpServerFeatures.SyncToolSpecification.builder()
                    .tool(tool)
                    .callHandler(toolImpl::call)
                    .build(),
                toolImpl.state);
    }

    /**
     * Handles the tool call request.
     * <p>
     * Accepts {@code script} (inline source), {@code path} (a JS file to execute), or both.
     * A file is read through the controlled {@code fs} access (so only project-base / add-on
     * paths are allowed). If both are given, the inline {@code script} is executed first as a
     * pre-initialization step and the file content is appended; all combinations are executed
     * by the same engine {@link #runScript}, guaranteeing identical sandbox restrictions.
     * </p>
     * @param exchange the server exchange
     * @param request the tool call request
     * @return the tool call result
     */
    McpSchema.CallToolResult call(McpSyncServerExchange exchange, CallToolRequest request) {
        // Increment call count
        state.callCount().incrementAndGet();

        Map<String, Object> arguments = request.arguments();

        Object oScript = arguments.get("script");
        Object oPath = arguments.get("path");
        if (oScript == null && oPath == null) {
            return CallToolResult.builder()
                .isError(true)
                .addTextContent("Provide at least one of 'script' (inline JavaScript source code) "
                        + "or 'path' (a JavaScript file in the project to execute).")
                .build();
        }

        final long timeoutSeconds;
        try {
            timeoutSeconds = resolveTimeoutSeconds(arguments.get("timeoutSeconds"));
        } catch (IllegalArgumentException e) {
            return CallToolResult.builder()
                .isError(true)
                .addTextContent(e.getMessage())
                .build();
        }

        final String script;
        final String sourceName;
        if (oPath == null) {
            // Inline-only.
            script = oScript.toString();
            sourceName = "inline";
        } else {
            String path = oPath.toString();
            String fileContent;
            try {
                fileContent = JsFileSystem.readFile(path);
            } catch (RuntimeException e) {
                return CallToolResult.builder()
                    .isError(true)
                    .addTextContent("Cannot read JavaScript file '" + path + "': " + e.getMessage())
                    .build();
            }
            if (oScript == null) {
                // File-only.
                script = fileContent;
                sourceName = path;
            } else {
                // Combined: pre-initialization (inline script) first, then the file.
                script = oScript.toString() + "\n" + fileContent;
                sourceName = "inline+" + path;
            }
        }
        return runScript(script, sourceName, timeoutSeconds);
    }

    /**
     * Resolves the effective execution timeout from the optional {@code timeoutSeconds} argument.
     * @param argument the raw argument value ({@code null} = not given)
     * @return the timeout in seconds
     * @throws IllegalArgumentException if the value is not a number or out of the allowed range
     */
    private static long resolveTimeoutSeconds(Object argument) {
        if (argument == null) {
            return EFFECTIVE_DEFAULT_TIMEOUT_SECONDS;
        }
        long seconds;
        if (argument instanceof Number number) {
            seconds = Math.round(number.doubleValue());
        } else {
            try {
                seconds = Long.parseLong(argument.toString().trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Parameter 'timeoutSeconds' must be a number of "
                        + "seconds, got: " + argument);
            }
        }
        if (seconds < MIN_TIMEOUT_SECONDS || seconds > MAX_TIMEOUT_SECONDS) {
            throw new IllegalArgumentException("Parameter 'timeoutSeconds' must be between "
                    + MIN_TIMEOUT_SECONDS + " and " + MAX_TIMEOUT_SECONDS + ", got: " + argument);
        }
        return seconds;
    }

    /**
     * Reads the default timeout from the system property {@value #PROP_TIMEOUT_SECONDS}.
     * @return the configured default timeout in seconds, or {@value #DEFAULT_TIMEOUT_SECONDS}
     */
    private static long resolveConfiguredTimeoutSeconds() {
        String value = System.getProperty(PROP_TIMEOUT_SECONDS);
        if (value == null || value.isBlank()) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
        try {
            long seconds = Long.parseLong(value.trim());
            if (seconds < MIN_TIMEOUT_SECONDS || seconds > MAX_TIMEOUT_SECONDS) {
                LOGGER.warning("Ignoring out-of-range '" + PROP_TIMEOUT_SECONDS + "' value '" + value
                        + "' (allowed " + MIN_TIMEOUT_SECONDS + ".." + MAX_TIMEOUT_SECONDS
                        + "); using default " + DEFAULT_TIMEOUT_SECONDS + " seconds.");
                return DEFAULT_TIMEOUT_SECONDS;
            }
            return seconds;
        } catch (NumberFormatException e) {
            LOGGER.warning("Ignoring invalid '" + PROP_TIMEOUT_SECONDS + "' value '" + value
                    + "' (not a number); using default " + DEFAULT_TIMEOUT_SECONDS + " seconds.");
            return DEFAULT_TIMEOUT_SECONDS;
        }
    }

    /**
     * Executes JavaScript source code in the sandboxed GraalVM JS context, enforcing an execution
     * timeout.
     * <p>
     * All modes of {@code javascript_tool} - inline {@code script}, file {@code path}, or the
     * combined {@code script}+{@code path} concatenation - delegate here, so every execution
     * uses the exact same sandbox, module wiring, console capture and error formatting. This is
     * what guarantees that a JavaScript file is executed under precisely the same restrictions
     * as a script passed directly by the LLM.
     * </p>
     * <p>
     * The execution itself runs in a worker thread (see {@link #JS_EXECUTOR}); this method only
     * waits for its result and cancels the execution if {@code timeoutSeconds} elapses.
     * </p>
     * @param script the JavaScript source code to execute
     * @param sourceName a human-readable name for logging (e.g. "inline" or the file path)
     * @param timeoutSeconds maximum execution time in seconds
     * @return the tool call result
     */
    private CallToolResult runScript(String script, String sourceName, long timeoutSeconds) {
        LOGGER.info("Executing JavaScript (" + sourceName + ", timeout " + timeoutSeconds
                + " s): " + script);

        // The stdout capture buffer is created here (not in the worker) so that the output written
        // so far can be attached to the timeout message. ByteArrayOutputStream is synchronized,
        // so reading it while the worker writes is safe.
        ByteArrayOutputStream baosOut = new ByteArrayOutputStream();

        // Set before the context is cancelled; lets the worker classify its exception as timeout.
        AtomicBoolean cancelRequested = new AtomicBoolean(false);
        // The context as soon as the worker created it (cancellation target of this thread).
        AtomicReference<Context> contextRef = new AtomicReference<>();
        // The worker thread, for diagnostics if it cannot be cancelled.
        AtomicReference<Thread> workerRef = new AtomicReference<>();

        Future<CallToolResult> future = JS_EXECUTOR.submit(() -> {
            workerRef.set(Thread.currentThread());
            return executeInSandbox(script, sourceName, timeoutSeconds, baosOut, cancelRequested, contextRef);
        });

        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return handleTimeout(future, contextRef, cancelRequested, workerRef, baosOut,
                    sourceName, timeoutSeconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelExecution(contextRef, cancelRequested);
            future.cancel(true);
            String message = "Error during JavaScript execution: The call was interrupted while "
                    + "waiting for the script (" + sourceName + ").";
            LOGGER.log(Level.SEVERE, message, e);
            return CallToolResult.builder()
                .isError(true)
                .addTextContent(message)
                .build();
        } catch (ExecutionException e) {
            // Only reachable for errors the worker does not handle itself (e.g. OutOfMemoryError).
            Throwable cause = (e.getCause() != null) ? e.getCause() : e;
            String message = "Error during JavaScript execution: " + cause;
            LOGGER.log(Level.SEVERE, "Error during JavaScript execution (" + sourceName + "): "
                    + message, cause);
            return CallToolResult.builder()
                .isError(true)
                .addTextContent(message)
                .build();
        }
    }

    /**
     * Timeout handler of {@link #runScript}: requests cancellation of the running script and waits
     * a grace period for the worker to report the resulting (cancelled) exception, which carries
     * the JavaScript stack of the cancellation point.
     * @param future the worker future
     * @param contextRef the context reference (may not be set yet)
     * @param cancelRequested cancellation flag
     * @param workerRef the worker thread reference
     * @param outCapture the stdout capture buffer
     * @param sourceName the source name for logging
     * @param timeoutSeconds the timeout that was applied
     * @return the tool call result
     */
    private CallToolResult handleTimeout(Future<CallToolResult> future, AtomicReference<Context> contextRef,
            AtomicBoolean cancelRequested, AtomicReference<Thread> workerRef, ByteArrayOutputStream outCapture,
            String sourceName, long timeoutSeconds) {

        LOGGER.warning("JavaScript execution (" + sourceName + ") exceeded its timeout of "
                + timeoutSeconds + " s, requesting cancellation.");
        cancelExecution(contextRef, cancelRequested);

        try {
            CallToolResult result = future.get(CANCEL_GRACE_SECONDS, TimeUnit.SECONDS);
            if (!Boolean.TRUE.equals(result.isError())) {
                // The script finished in the moment between the deadline and the cancellation.
                LOGGER.warning("JavaScript execution (" + sourceName + ") exceeded the timeout but "
                        + "completed before the cancellation took effect; returning its result.");
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return notCancellableResult(workerRef, outCapture, sourceName, timeoutSeconds, future, e);
        } catch (TimeoutException e) {
            return notCancellableResult(workerRef, outCapture, sourceName, timeoutSeconds, future, e);
        } catch (ExecutionException e) {
            Throwable cause = (e.getCause() != null) ? e.getCause() : e;
            String message = "Error during JavaScript execution: The script was cancelled after "
                    + timeoutSeconds + " s (source: " + sourceName + "), but its worker thread "
                    + "reported: " + cause;
            LOGGER.log(Level.SEVERE, message, cause);
            return CallToolResult.builder()
                .isError(true)
                .addTextContent(message)
                .build();
        }
    }

    /**
     * Builds the result for a script that did not stop after the cancellation request (typically
     * because it is blocked in a host call, where no guest safepoint is reached). The thread is
     * interrupted in the background; its Java stack trace is written to the error log to show
     * where it hangs.
     * @param workerRef the worker thread reference
     * @param outCapture the stdout capture buffer
     * @param sourceName the source name for logging
     * @param timeoutSeconds the timeout that was applied
     * @param future the worker future
     * @param cause the exception that led here (timeout of the grace period / interruption)
     * @return the tool call result
     */
    private CallToolResult notCancellableResult(AtomicReference<Thread> workerRef, ByteArrayOutputStream outCapture,
            String sourceName, long timeoutSeconds, Future<CallToolResult> future, Exception cause) {

        Thread worker = workerRef.get();
        String workerStack = (worker != null) ? formatThreadStack(worker) : "(the worker thread is unknown)";
        future.cancel(true);

        StringBuilder sb = new StringBuilder("Error during JavaScript execution: The script did not "
                + "finish within " + timeoutSeconds + " s (source: " + sourceName + ") and could not "
                + "be cancelled either.");
        sb.append("\nThe script is most likely blocked inside a host call (a file, archive or "
                + "module operation) which cannot be interrupted at a JavaScript safepoint.");
        sb.append("\nThe worker thread is interrupted in the background and does not block the server.");
        appendPartialOutput(sb, outCapture);
        String message = sb.toString();

        LOGGER.log(Level.SEVERE, message + "\nJava stack of thread '"
                + ((worker != null) ? worker.getName() : "?") + "':\n" + workerStack, cause);

        return CallToolResult.builder()
            .isError(true)
            .addTextContent(message)
            .build();
    }

    /**
     * Requests cancellation of a running JavaScript execution.
     * <p>
     * {@link Context#close(boolean)} with {@code true} may be called from another thread: it
     * closes the context and cancels the evaluation that is in progress. The evaluation then
     * throws a {@link PolyglotException} with {@code isCancelled()} set, which contains the
     * JavaScript stack of the cancellation point. Cancellation happens at the next guest
     * safepoint, so a script blocked in a host call keeps running until that call returns.
     * </p>
     * @param contextRef the context reference (may not be set yet)
     * @param cancelRequested cancellation flag (set even if there is no context yet)
     */
    private static void cancelExecution(AtomicReference<Context> contextRef, AtomicBoolean cancelRequested) {
        cancelRequested.set(true);
        Context context = contextRef.get();
        if (context == null) {
            // The worker has not created the context yet; it checks the flag itself.
            return;
        }
        try {
            context.close(true);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Cancelling the JavaScript execution failed", e);
        }
    }

    /**
     * Creates the sandboxed GraalVM context, wires the modules and evaluates the script.
     * <p>
     * This method runs in the worker thread of {@link #JS_EXECUTOR}; the created context is
     * published via {@code contextRef} so that the calling thread can cancel it, and
     * {@code cancelRequested} is used to distinguish a cancellation-triggered exception from a
     * regular script error.
     * </p>
     * @param script the JavaScript source code
     * @param sourceName a human-readable name for logging and for the JavaScript source name
     * @param timeoutSeconds the timeout applied by the caller (used in the timeout message)
     * @param baosOut the (caller-owned) stdout capture buffer
     * @param cancelRequested cancellation flag
     * @param contextRef reference to publish the created context
     * @return the tool call result
     */
    private CallToolResult executeInSandbox(String script, String sourceName, long timeoutSeconds,
            ByteArrayOutputStream baosOut, AtomicBoolean cancelRequested, AtomicReference<Context> contextRef) {

        // Capture console.log output (stdout)
        PrintStream outCapture = new PrintStream(baosOut, true, StandardCharsets.UTF_8);

        // Capture stderr separately (e.g. Truffle warnings) for logging only
        ByteArrayOutputStream baosErr = new ByteArrayOutputStream();
        PrintStream errCapture = new PrintStream(baosErr, true, StandardCharsets.UTF_8);

        // Per-call resources returned by module wiring (e.g. an MCP client connection),
        // closed after the JavaScript call.
        List<AutoCloseable> callResources = new ArrayList<>();

        Context context = null;
        try {
            context = Context.newBuilder("js")
                    .out(outCapture)
                    .err(errCapture)
                    .in(InputStream.nullInputStream())
                    .sandbox(SandboxPolicy.CONSTRAINED)
                    .build();
            contextRef.set(context);
            checkNotCancelled(cancelRequested, sourceName);

            // --- Wire console.log to capture output ---
            // Mimic native console.log: join all arguments with a space, support
            // zero arguments and non-string values (e.g. numbers).
            // Capture the guest String() function: for a host-wrapped exception it reliably yields
            // 'HostExceptionClass: message' (interop does not expose name/message as strings for
            // such wrapped exceptions, Object.keys(e) is empty); the Java class name is then
            // stripped in formatLogValue.
            Value jsStringFn = context.eval("js", "(function(v) { return String(v); })");
            ProxyExecutable logFunc = (cArgs) -> {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < cArgs.length; i++) {
                    if (i > 0) {
                        sb.append(' ');
                    }
                    formatLogValue(sb, cArgs[i], jsStringFn);
                }
                outCapture.println(sb.toString());
                return null;
            };
            ProxyObject consoleObj = ProxyObject.fromMap(Map.of(
                    "log", logFunc));
            context.getBindings("js").putMember("console", consoleObj);

            Value jsBindings = context.getBindings("js");

            // --- Wire the configured JS-modules generically ---
            // Build the CommonJS require-targets on the fly from the modules that are
            // enabled and require()-aliasable.
            List<String> requireNames = new ArrayList<>();
            Map<String, Value> requireTargets = new HashMap<>();
            for (JsModuleInterface m : modules.values()) {
                if (!m.isEnabled()) {
                    LOGGER.info("Module '" + m.getNamespace() + "' disabled, skipped");
                    continue;
                }
                AutoCloseable resource = m.wireApi(jsBindings);
                LOGGER.info("Module '" + m.getNamespace() + "' bound to JavaScript context");
                if (resource != null) {
                    callResources.add(resource);
                }
                if (m.hasRequireAlias()) {
                    requireNames.add(m.getNamespace());
                    Value nsValue = jsBindings.getMember(m.getNamespace());
                    requireTargets.put(m.getNamespace(), nsValue);
                    requireTargets.put("node:" + m.getNamespace(), nsValue);
                }
            }

            // --- Wire CommonJS-style require for the provided namespaces ---
            // LLMs often write Node.js-style code and expect require('fs') to work.
            // Map the offered namespaces onto the bound proxy objects and produce a
            // clear, actionable error for anything else.
            String requireList = "'" + String.join("', '", requireNames) + "'";
            ProxyExecutable requireFunc = (cArgs) -> {
                if (cArgs == null || cArgs.length < 1 || cArgs[0].isNull()) {
                    throw new IllegalArgumentException(
                            "require(module) requires exactly one module name.");
                }
                String module = cArgs[0].asString();
                Value target = requireTargets.get(module);
                if (target == null) {
                    throw new IllegalArgumentException(
                            "Cannot find module '" + module + "'. This sandbox provides only "
                            + requireList + " (also bound globally as "
                            + String.join(", ", requireNames) + "). "
                            + "There is no Node.js require for arbitrary modules, no process, "
                            + "no Buffer and no network access.");
                }
                return target;
            };
            context.getBindings("js").putMember("require", requireFunc);
            LOGGER.info("CommonJS 'require' shim bound to JavaScript context (modules: "
                    + String.join(", ", requireNames) + ")");

            checkNotCancelled(cancelRequested, sourceName);

            // Execute the JavaScript code and capture the return value.
            // An explicit Source gives the script a readable name in JavaScript stack traces
            // (instead of GraalVM's default "Unnamed"), which matters for the timeout message.
            Source jsSource = Source.newBuilder("js", script, sourceName).build();
            Value result = context.eval(jsSource);

            outCapture.flush();
            errCapture.flush();
            String capturedOutput = baosOut.toString(StandardCharsets.UTF_8).trim();
            String capturedErr = baosErr.toString(StandardCharsets.UTF_8).trim();

            // Append the script's return value to the output if it is a meaningful string
            if (result != null && !result.isNull()) {
                String resultStr;
                if (result.isString()) {
                    resultStr = result.asString();
                } else if (result.hasArrayElements()) {
                    // e.g. fs.readBytes(...) as the trailing expression: format like console.log
                    StringBuilder sbRes = new StringBuilder();
                    formatValue(sbRes, result, 0);
                    resultStr = sbRes.toString();
                } else {
                    resultStr = String.valueOf(result);
                }
                if (resultStr != null && !resultStr.isEmpty() && !"undefined".equals(resultStr)) {
                    if (!capturedOutput.isEmpty()) {
                        capturedOutput += "\n";
                    }
                    capturedOutput += resultStr;
                }
            }

            // Log stderr output (e.g. Truffle warnings) to the server log, not to the MCP result
            if (!capturedErr.isEmpty()) {
                LOGGER.warning("JavaScript stderr output: " + capturedErr);
            }

            LOGGER.info("JavaScript executed successfully, output: " + capturedOutput);

            state.callsOk().incrementAndGet();

            // Prepare structured content
            Map<String, Object> structuredContent = new HashMap<>();
            structuredContent.put("status", "success");
            structuredContent.put("output", capturedOutput);

            return CallToolResult.builder()
                .isError(false)
                .addTextContent(capturedOutput)
                .structuredContent(structuredContent)
                .build();
        } catch (Exception e) {
            if (isCancellation(e, cancelRequested)) {
                return buildTimeoutResult(e, sourceName, timeoutSeconds, baosOut);
            }
            StringBuilder sbMsg = new StringBuilder("Error during JavaScript execution: ");
            if (e instanceof PolyglotException pe) {
                sbMsg.append(pe.getMessage());
                if (pe.isSyntaxError()) {
                    sbMsg.append("\nThe script contains a JavaScript syntax error.");
                }
                if (pe.isHostException() && pe.asHostException() != null) {
                    // Unwrap to show the original user-facing message (e.g. from fs.*/poi.*).
                    sbMsg.append("\nRoot cause: ").append(pe.asHostException().getMessage());
                }
                if (pe.getSourceLocation() != null) {
                    SourceSection loc = pe.getSourceLocation();
                    sbMsg.append(String.format("\nSource location: (line %d, column %d)",
                            loc.getStartLine(), loc.getStartColumn()));
                }
                sbMsg.append(buildHint());
            } else {
                sbMsg.append(e.getMessage());
            }
            String errorMessage = sbMsg.toString();
            // The exception (and with it the Java stack trace, GraalVM also prints the JavaScript
            // frames) goes to the error log, not to the caller.
            LOGGER.log(Level.SEVERE, "Error during JavaScript execution (" + sourceName + "): "
                    + errorMessage, e);
            return CallToolResult.builder()
                .isError(true)
                .addTextContent(errorMessage)
                .build();
        } finally {
            closeContextQuietly(context);
            // Close per-call module resources (e.g. the MCP client connection). This is done by
            // the worker thread itself; if it is stuck and cannot be cancelled, the resources are
            // released when it finally terminates (closing them concurrently would race with the
            // still running script).
            for (AutoCloseable ac : callResources) {
                try {
                    ac.close();
                } catch (Exception e) {
                    LOGGER.warning("Error closing a per-call module resource: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Throws if a cancellation has already been requested (the timeout elapsed while the context
     * was created or the modules were wired, i.e. before the script itself was started).
     * @param cancelRequested cancellation flag
     * @param sourceName the source name for the message
     */
    private static void checkNotCancelled(AtomicBoolean cancelRequested, String sourceName) {
        if (cancelRequested.get()) {
            throw new CancelledException("The execution timeout elapsed before the JavaScript code "
                    + "in '" + sourceName + "' could be started.");
        }
    }

    /**
     * Decides whether an exception of the worker thread was caused by the timeout cancellation.
     * @param e the exception
     * @param cancelRequested cancellation flag
     * @return true if the exception (or its context) comes from a cancellation
     */
    private static boolean isCancellation(Exception e, AtomicBoolean cancelRequested) {
        return cancelRequested.get() || (e instanceof PolyglotException pe && pe.isCancelled());
    }

    /**
     * Builds the tool result of a cancelled (timed-out) script.
     * <p>
     * The caller gets the JavaScript stack of the cancellation point plus the output produced so
     * far; the error log additionally gets the Java stack trace (GraalVM prints the interleaved
     * Java/JavaScript stack of a {@link PolyglotException}).
     * </p>
     * @param cause the exception thrown by the cancelled evaluation
     * @param sourceName the source name for logging
     * @param timeoutSeconds the timeout that was applied
     * @param outCapture the stdout capture buffer
     * @return the tool call result
     */
    private CallToolResult buildTimeoutResult(Exception cause, String sourceName, long timeoutSeconds,
            ByteArrayOutputStream outCapture) {

        StringBuilder sb = new StringBuilder("Error during JavaScript execution: The script was "
                + "cancelled because it exceeded its timeout of " + timeoutSeconds + " s (source: "
                + sourceName + ").");
        sb.append("\nIt probably contains an endless loop, recurses without end or computes far "
                + "too much.");
        if (cause instanceof PolyglotException pe) {
            appendGuestStack(sb, pe);
        } else {
            sb.append("\nJavaScript stack at the cancellation point: not available (")
              .append(cause.getMessage()).append(')');
        }
        appendPartialOutput(sb, outCapture);
        sb.append("\nHint: Add a loop guard, reduce the work per call (e.g. process the data in ")
          .append("smaller chunks, one call each) or pass a larger 'timeoutSeconds' (default ")
          .append(EFFECTIVE_DEFAULT_TIMEOUT_SECONDS).append(" s, maximum ")
          .append(MAX_TIMEOUT_SECONDS).append(" s).");

        String message = sb.toString();
        LOGGER.log(Level.SEVERE, "JavaScript execution timed out after " + timeoutSeconds
                + " s (" + sourceName + "):\n" + message, cause);

        return CallToolResult.builder()
            .isError(true)
            .addTextContent(message)
            .build();
    }

    /**
     * Appends the JavaScript (guest language) stack of an exception to a message.
     * <p>
     * Host (Java) frames are skipped - they belong to the error log, not to the script author.
     * Each frame additionally shows the source code of its source section, so a timeout directly
     * shows the statement the script is stuck in.
     * </p>
     * @param sb output buffer
     * @param pe the (usually cancelled) polyglot exception
     */
    private static void appendGuestStack(StringBuilder sb, PolyglotException pe) {
        List<String> frames = new ArrayList<>();
        boolean moreFrames = false;
        try {
            for (PolyglotException.StackFrame frame : pe.getPolyglotStackTrace()) {
                if (!frame.isGuestFrame()) {
                    continue;
                }
                if (frames.size() >= MAX_STACK_FRAMES) {
                    moreFrames = true;
                    break;
                }
                String text;
                try {
                    text = frame.toString();
                } catch (RuntimeException e) {
                    text = null;
                }
                if (text == null || text.isEmpty()) {
                    text = "<unknown>";
                }
                if (!text.startsWith("at ")) {
                    text = "at " + text;
                }
                String snippet = sourceSnippet(frame);
                frames.add(snippet.isEmpty() ? text : text + "  |  " + snippet);
            }
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "The JavaScript stack of the cancelled script could not be read", e);
        }
        sb.append("\nJavaScript stack at the cancellation point:");
        if (frames.isEmpty()) {
            sb.append("\n\t(unavailable)");
            return;
        }
        for (String frame : frames) {
            sb.append("\n\t").append(frame);
        }
        if (moreFrames) {
            sb.append("\n\t... (more frames omitted)");
        }
    }

    /**
     * Gets a one-line snippet of the source code a stack frame points into.
     * @param frame the guest stack frame
     * @return the snippet, or an empty string if it is not available
     */
    private static String sourceSnippet(PolyglotException.StackFrame frame) {
        try {
            SourceSection section = frame.getSourceLocation();
            if (section == null || !section.isAvailable()) {
                return "";
            }
            CharSequence code = section.getCode();
            if (code == null) {
                return "";
            }
            String oneLine = code.toString().replaceAll("\\s+", " ").trim();
            if (oneLine.isEmpty()) {
                return "";
            }
            if (oneLine.length() > MAX_SNIPPET_CHARS) {
                oneLine = oneLine.substring(0, MAX_SNIPPET_CHARS - 3) + "...";
            }
            return oneLine;
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * Appends the output a script had already written when it was cancelled.
     * @param sb output buffer
     * @param outCapture the stdout capture buffer
     */
    private static void appendPartialOutput(StringBuilder sb, ByteArrayOutputStream outCapture) {
        String partial;
        try {
            partial = outCapture.toString(StandardCharsets.UTF_8).trim();
        } catch (RuntimeException e) {
            return;
        }
        if (partial.isEmpty()) {
            return;
        }
        sb.append("\nOutput written before the timeout:");
        if (partial.length() > MAX_TIMEOUT_OUTPUT_CHARS) {
            sb.append("\n").append(partial, 0, MAX_TIMEOUT_OUTPUT_CHARS);
            sb.append("\n... (output truncated, total ").append(outCapture.size()).append(" bytes)");
        } else {
            sb.append("\n").append(partial);
        }
    }

    /**
     * Renders the current Java stack trace of a thread.
     * @param thread the thread
     * @return the stack trace, one frame per line
     */
    private static String formatThreadStack(Thread thread) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement element : thread.getStackTrace()) {
            sb.append("\tat ").append(element).append('\n');
        }
        return sb.toString();
    }

    /**
     * Closes a context without failing the call. The context may already have been closed by the
     * timeout handler ({@link #cancelExecution}), which is not an error.
     * @param context the context to close (may be null)
     */
    private static void closeContextQuietly(Context context) {
        if (context == null) {
            return;
        }
        try {
            context.close();
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "Closing the JavaScript context failed", e);
        }
    }

    /**
     * Builds a help-hint for JavaScript error messages from the enabled,
     * require()-aliasable modules.
     * @return the hint text (starting with a newline and "Hint: ")
     */
    private String buildHint() {
        List<String> tips = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (JsModuleInterface m : modules.values()) {
            if (m.isEnabled() && m.hasRequireAlias()) {
                tips.add(m.getHelpTip());
                names.add(m.getNamespace());
            }
        }
        StringBuilder sb = new StringBuilder("\nHint: Use ");
        if (tips.size() == 1) {
            sb.append(tips.get(0));
        } else if (tips.size() >= 2) {
            sb.append(String.join(", ", tips.subList(0, tips.size() - 1)));
            sb.append(" or ").append(tips.get(tips.size() - 1));
        }
        sb.append(". The script runs in a sandboxed GraalVM JS context "
                + "(no Node.js require for arbitrary modules, no process, no Buffer, "
                + "no network); ");
        if (names.isEmpty()) {
            sb.append("no modules are bound globally.");
        } else {
            sb.append("'").append(String.join("', '", names))
              .append("' are bound globally.");
        }
        return sb.toString();
    }

    /**
     * Appends a string representation of a GraalVM value to a StringBuilder.
     * <p>
     * Unlike a plain {@code String.valueOf}, this handles arrays and objects
     * recursively so that e.g. {@code console.log(fs.readdir("."))} prints the
     * actual entries instead of a proxy hash code.
     * </p>
     * @param sb output buffer
     * @param arg value to format
     * @param depth current recursion depth (guards against circular structures)
     */
    private static void formatValue(StringBuilder sb, Value arg, int depth) {
        if (arg == null || arg.isNull()) {
            sb.append("null");
            return;
        }
        if (depth > 8) {
            sb.append("...");
            return;
        }
        if (arg.isString()) {
            sb.append(arg.asString());
            return;
        }
        if (arg.isNumber()) {
            sb.append(arg.fitsInLong() ? Long.toString(arg.asLong())
                    : Double.toString(arg.asDouble()));
            return;
        }
        if (arg.isBoolean()) {
            sb.append(Boolean.toString(arg.asBoolean()));
            return;
        }
        if (arg.hasArrayElements()) {
            sb.append('[');
            long size = arg.getArraySize();
            for (long i = 0; i < size; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                formatValue(sb, arg.getArrayElement(i), depth + 1);
            }
            sb.append(']');
            return;
        }
        if (arg.hasMembers()) {
            String[] keys = arg.getMemberKeys().toArray(new String[0]);
            sb.append('{');
            Arrays.sort(keys);
            for (int i = 0; i < keys.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(keys[i]).append(": ");
                formatValue(sb, arg.getMember(keys[i]), depth + 1);
            }
            sb.append('}');
            return;
        }
        // Fallback: best effort representation
        sb.append(String.valueOf(arg));
    }

    /**
     * Formats a single console.log argument. Exception values (e.g. a caught Error) are rendered
     * as a plain {@code Error: message}: they are first converted with the guest {@code String()}
     * function (reliably yielding {@code 'HostExceptionClass: message'} even though interop does
     * not expose {@code name}/{@code message} as strings for host-wrapped exceptions), then a
     * leading fully qualified Java class name is stripped. All other values fall through to
     * {@link #formatValue}.
     * <p>
     * The full Java class name remains visible in the server's error log (see
     * {@link #executeInSandbox}, which logs the exception with its stack trace).
     * </p>
     * @param sb output buffer
     * @param arg the console.log argument
     * @param guestStringFn the guest {@code String} function (from the JS bindings)
     */
    private static void formatLogValue(StringBuilder sb, Value arg, Value guestStringFn) {
        if (arg != null && arg.isException()) {
            try {
                Value s = guestStringFn.execute(arg);
                if (s != null && s.isString()) {
                    String txt = s.asString();
                    if (txt != null && !txt.isEmpty()) {
                        sb.append(stripJavaFqcnPrefix(txt));
                        return;
                    }
                }
            } catch (RuntimeException ignore) {
                // guest String() failed -> fall through to the generic formatter
            }
        }
        formatValue(sb, arg, 0);
    }

    /**
     * Removes a leading Java class name from a host-exception string representation, e.g.
     * {@code "org.rogmann.mcp2sdk.js.JsUserRuntimeException: msg"} becomes {@code "Error: msg"}.
     *
     * @param text the string representation (non-null)
     * @return the cleaned string
     */
    private static String stripJavaFqcnPrefix(String text) {
        int colon = text.indexOf(':');
        if (colon <= 0) {
            return text;
        }
        String head = text.substring(0, colon);
        if (!looksLikeJavaFqcn(head)) {
            return text;
        }
        String rest = text.substring(colon + 1).trim();
        return rest.isEmpty() ? "Error" : "Error: " + rest;
    }

    /**
     * Heuristic for a fully qualified Java class name (e.g. {@code a.b.C}): at least one dot and a
     * lowercase (package-like) first segment. Plain JS error names like {@code SyntaxError} have no
     * dot and are not matches, so they are kept unchanged.
     *
     * @param name candidate string
     * @return true if it looks like a Java fully qualified class name
     */
    private static boolean looksLikeJavaFqcn(String name) {
        if (name == null) {
            return false;
        }
        int firstDot = name.indexOf('.');
        return firstDot > 0
                && firstDot < name.length() - 1
                && Character.isLowerCase(name.charAt(0));
    }

    /**
     * Internal signal that a timeout elapsed before the script could be handed to GraalVM
     * (context creation / module wiring). It is handled like a cancelled evaluation.
     */
    private static final class CancelledException extends RuntimeException {

        /**
         * Constructor.
         * @param message detail message
         */
        CancelledException(String message) {
            super(message);
        }
    }

}
