package org.rogmann.mcp2sdk.tools;

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
import org.rogmann.mcp2sdk.js.JsJavapBridge;
import org.rogmann.mcp2sdk.js.JsMcpProxyBridge;
import org.rogmann.mcp2sdk.js.JsModuleInterface;
import org.rogmann.mcp2sdk.js.JsSearchBridge;
import org.rogmann.mcp2sdk.js.JsSQLiteBridge;
import org.rogmann.mcp2sdk.poi.DocxToolBoxJsBridge;
import org.rogmann.mcp2sdk.poi.PoiToolBoxJsBridge;
import org.rogmann.mcp2sdk.poi.PptxToolBoxJsBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

    private static final Logger LOGGER = LoggerFactory.getLogger(JavaScriptTool.class);

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
        // Read-only access to SQLite database files (tables/rows/forEachRow) with a small
        // built-in parser - no sqlite-jdbc dependency.
        modules.put("sqlite", new JsSQLiteBridge());
        // Grep-like search over files, directories and archives (docs/js/search.md).
        // Uses the same path rules as `fs` and the same archive formats as `archive`.
        modules.put("search", new JsSearchBridge());
        modules.put("javap", new JsJavapBridge());
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
        sb.append(" CommonJS-style project modules are supported: load('./lib.js') or")
          .append(" load('./lib.js', {sha256: '<64 hex chars>'}) (also require('./lib.js'))")
          .append(" evaluate .js files from the permitted directories; paths starting with")
          .append(" './' or '../' are resolved relative to the file given as 'path' (project")
          .append(" base for inline scripts).");
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
                + "executed. At least one of 'script' or 'path' must be provided. Relative paths "
                + "in require('./x.js')/load('./x.js') are resolved against this file's directory.");
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
        return runScript(script, sourceName, timeoutSeconds,
                (oPath != null) ? oPath.toString() : null);
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
                LOGGER.warn("Ignoring out-of-range '{}' value '{}' (allowed {}..{}); using default {} seconds.",
                        PROP_TIMEOUT_SECONDS, value, MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS,
                        DEFAULT_TIMEOUT_SECONDS);
                return DEFAULT_TIMEOUT_SECONDS;
            }
            return seconds;
        } catch (NumberFormatException e) {
            LOGGER.warn("Ignoring invalid '{}' value '{}' (not a number); using default {} seconds.",
                    PROP_TIMEOUT_SECONDS, value, DEFAULT_TIMEOUT_SECONDS);
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
     * @param basePath display path of the file executed via the 'path' parameter (null for
     *        inline-only); the base for resolving relative paths in require()/load()
     * @return the tool call result
     */
    private CallToolResult runScript(String script, String sourceName, long timeoutSeconds,
            String basePath) {
        LOGGER.info("Executing JavaScript ({}, timeout {} s): {}", sourceName, timeoutSeconds, script);

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
            return executeInSandbox(script, sourceName, timeoutSeconds, baosOut, cancelRequested,
                    contextRef, basePath);
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
            LOGGER.error(message, e);
            return CallToolResult.builder()
                .isError(true)
                .addTextContent(message)
                .build();
        } catch (ExecutionException e) {
            // Only reachable for errors the worker does not handle itself (e.g. OutOfMemoryError).
            Throwable cause = (e.getCause() != null) ? e.getCause() : e;
            String message = "Error during JavaScript execution: " + cause;
            LOGGER.error("Error during JavaScript execution ({}): {}", sourceName, message, cause);
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

        LOGGER.warn("JavaScript execution ({}) exceeded its timeout of {} s, requesting cancellation.",
                sourceName, timeoutSeconds);
        cancelExecution(contextRef, cancelRequested);

        try {
            CallToolResult result = future.get(CANCEL_GRACE_SECONDS, TimeUnit.SECONDS);
            if (!Boolean.TRUE.equals(result.isError())) {
                // The script finished in the moment between the deadline and the cancellation.
                LOGGER.warn("JavaScript execution ({}) exceeded the timeout but completed before "
                        + "the cancellation took effect; returning its result.", sourceName);
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
            LOGGER.error(message, cause);
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

        LOGGER.error(message + "\nJava stack of thread '"
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
            LOGGER.warn("Cancelling the JavaScript execution failed", e);
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
     * @param basePath display path of the file executed via the 'path' parameter (null for
     *        inline-only); the base for resolving relative paths in require()/load()
     * @return the tool call result
     */
    private CallToolResult executeInSandbox(String script, String sourceName, long timeoutSeconds,
            ByteArrayOutputStream baosOut, AtomicBoolean cancelRequested, AtomicReference<Context> contextRef,
            String basePath) {

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
                    LOGGER.info("Module '{}' disabled, skipped", m.getNamespace());
                    continue;
                }
                AutoCloseable resource = m.wireApi(jsBindings);
                LOGGER.debug("Module '{}' bound to JavaScript context", m.getNamespace());
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

            // --- Wire the project-file module loader (CommonJS-style) ---
            // load('./lib.js') / load('./lib.js', {sha256: '<hex>'}) and require('./lib.js')
            // evaluate a '.js' file from the permitted directories as a CommonJS module and
            // return its module.exports. The module cache lives for this execution only
            // (the context - and with it the loader - is created fresh per tool call).
            ModuleLoader moduleLoader = new ModuleLoader(context, cancelRequested, sourceName,
                    basePath, jsBindings);

            // --- Wire CommonJS-style require for the provided namespaces ---
            // LLMs often write Node.js-style code and expect require('fs') to work.
            // Map the offered namespaces onto the bound proxy objects and produce a
            // clear, actionable error for anything else. Relative paths and '.js' files
            // are delegated to the project-file module loader.
            String requireList = "'" + String.join("', '", requireNames) + "'";
            ProxyExecutable requireFunc = (cArgs) -> {
                if (cArgs == null || cArgs.length < 1 || cArgs[0].isNull()) {
                    throw new IllegalArgumentException(
                            "require(module) requires exactly one module name.");
                }
                String module = cArgs[0].asString();
                Value target = requireTargets.get(module);
                if (target == null) {
                    if (moduleLoader.looksLikeModuleFile(module)) {
                        return moduleLoader.load(module, null);
                    }
                    throw new IllegalArgumentException(
                            "Cannot find module '" + module + "'. This sandbox provides only "
                            + requireList + " (also bound globally as "
                            + String.join(", ", requireNames) + "); project files can be loaded "
                            + "with require('./file.js') or load('./file.js'). "
                            + "There is no Node.js require for arbitrary modules, no process, "
                            + "no Buffer and no network access.");
                }
                return target;
            };
            context.getBindings("js").putMember("require", requireFunc);
            LOGGER.info("CommonJS 'require' shim bound to JavaScript context (modules: {})",
                    String.join(", ", requireNames));

            // --- Wire load() for project files with optional sha256 pinning ---
            ProxyExecutable loadFunc = (cArgs) -> {
                if (cArgs == null || cArgs.length < 1 || cArgs[0].isNull()) {
                    throw new IllegalArgumentException(
                            "load(path) requires a module path, optionally followed by an options "
                            + "object, e.g. load('./lib.js', {sha256: '<64 hex chars>'}).");
                }
                if (cArgs.length > 2) {
                    throw new IllegalArgumentException("load(path, options) takes at most two "
                            + "arguments, got " + cArgs.length + ".");
                }
                String expectedSha256 = null;
                if (cArgs.length > 1 && cArgs[1] != null && !cArgs[1].isNull()) {
                    expectedSha256 = moduleLoader.extractPinnedSha256(cArgs[1]);
                }
                return moduleLoader.load(cArgs[0].asString(), expectedSha256);
            };
            context.getBindings("js").putMember("load", loadFunc);
            LOGGER.info("CommonJS module loader bound to JavaScript context "
                    + "(require/load of '.js' project files, relative paths based on: {})",
                    (basePath != null) ? basePath : "<project base>");

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
                LOGGER.warn("JavaScript stderr output: {}", capturedErr);
            }

            LOGGER.info("JavaScript executed successfully, output: {}", capturedOutput);

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
            LOGGER.error("Error during JavaScript execution ({}): {}", sourceName, errorMessage, e);
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
                    LOGGER.warn("Error closing a per-call module resource: {}", e.getMessage());
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
        LOGGER.error("JavaScript execution timed out after {} s ({}):\n{}",
                timeoutSeconds, sourceName, message, cause);

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
            LOGGER.debug("The JavaScript stack of the cancelled script could not be read", e);
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
            LOGGER.debug("Closing the JavaScript context failed", e);
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
                + "(no Node.js require for arbitrary packages, no process, no Buffer, "
                + "no network; '.js' project files can be loaded with "
                + "require('./file.js') or load('./file.js')); ");
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
     * CommonJS-style loader for JavaScript files from the permitted directories.
     * <p>
     * {@code load('./lib.js')} (and {@code require('./lib.js')}) read a '.js' file through the
     * controlled fs access (same path rules as {@code fs}: project-base relative,
     * {@code /addonName/...} prefixes, no traversal, no escaping symbolic links), evaluate it as
     * a CommonJS module and return its {@code module.exports}. Relative paths starting with
     * {@code ./} or {@code ../} are resolved against the directory of the file passed as the
     * tool's 'path' parameter (project base for inline scripts) and, for nested loads, against
     * the directory of the loading module. Everything else is resolved relative to the project
     * base directory.
     * </p>
     * <p>
     * The optional second argument of {@code load} pins the file content: the SHA-256 of the
     * file (identical to {@code crypto.sha256(path)}, i.e. over the raw UTF-8 file bytes) must
     * match, otherwise the module is not executed. This lets a caller verify that exactly the
     * reviewed source version runs, even when the file changed between writing the script and
     * executing it.
     * </p>
     * <p>
     * The module cache lives for one execution only (the loader is created per tool call), with
     * Node.js semantics for cyclic requires (the still-initializing partial {@code exports}
     * object is returned).
     * </p>
     */
    private static final class ModuleLoader {

        /** Maximum nesting depth of module loads (defensive bound; cycles are caught by the cache). */
        private static final int MAX_MODULE_DEPTH = 32;

        private final Context context;
        private final AtomicBoolean cancelRequested;
        private final String sourceName;

        /** Display path of the file executed via the 'path' parameter (null for inline-only). */
        private final String basePath;
        /** JS bindings of the context, used to fetch the require shim for the modules. */
        private final Value jsBindings;

        /** Modules of this execution: display path -&gt; exports value. */
        private final Map<String, Value> moduleCache = new HashMap<>();
        /** SHA-256 (lowercase hex) of the file content at load time, keyed by display path. */
        private final Map<String, String> moduleSha256 = new HashMap<>();
        /** Modules currently being evaluated (innermost first), for relative path resolution. */
        private final Deque<String> evaluationStack = new ArrayDeque<>();

        /**
         * Constructor.
         * @param context the (sandboxed) GraalVM context
         * @param cancelRequested cancellation flag of the enclosing execution
         * @param sourceName human-readable name of the enclosing execution (for messages)
         * @param basePath display path of the file executed via 'path' (null for inline-only)
         * @param jsBindings the JS bindings, source of the require shim handed to the modules
         */
        ModuleLoader(Context context, AtomicBoolean cancelRequested, String sourceName,
                String basePath, Value jsBindings) {
            this.context = context;
            this.cancelRequested = cancelRequested;
            this.sourceName = sourceName;
            this.basePath = basePath;
            this.jsBindings = jsBindings;
        }

        /**
         * Decides whether a require() argument that is not a provided namespace looks like a
         * project-file module and should therefore be delegated to {@link #load}.
         * @param module the require() argument
         * @return true for relative paths, mount-prefixed paths and '.js' files
         */
        boolean looksLikeModuleFile(String module) {
            return module.startsWith("./") || module.startsWith("../") || module.startsWith("/")
                    || module.toLowerCase(Locale.ROOT).endsWith(".js");
        }

        /**
         * Extracts and validates the optional 'sha256' pin from the load() options object.
         * @param options the second load() argument (a JS object)
         * @return the expected SHA-256 as 64 lowercase hex characters, or null if not given
         */
        String extractPinnedSha256(Value options) {
            if (!options.hasMembers()) {
                throw new IllegalArgumentException("load(path, options): options must be an object "
                        + "with an optional 'sha256' member, got a non-object.");
            }
            for (String member : options.getMemberKeys()) {
                if (!"sha256".equals(member)) {
                    throw new IllegalArgumentException("load(path, options): unknown option '"
                            + member + "' (supported: 'sha256').");
                }
            }
            Value sha = options.getMember("sha256");
            if (sha == null || sha.isNull()) {
                return null;
            }
            if (!sha.isString()) {
                throw new IllegalArgumentException("load(path, options): 'sha256' must be a "
                        + "string of 64 hex characters.");
            }
            String pinned = sha.asString().trim().toLowerCase(Locale.ROOT);
            if (!pinned.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("load(path, options): 'sha256' must be a "
                        + "string of 64 hex characters, got: " + sha.asString());
            }
            return pinned;
        }

        /**
         * Loads (and evaluates) a project-file module.
         * <p>
         * The pin check happens strictly before evaluation, so a module whose content does not
         * match the pinned SHA-256 never runs. On a cache hit the pin is compared against the
         * hash recorded at load time - the pin refers to what was actually executed.
         * </p>
         * @param moduleArg the path as given by the caller
         * @param expectedSha256 the pinned SHA-256 (64 lowercase hex characters) or null
         * @return the module's exports (for a cyclic require: the partial exports object)
         */
        Value load(String moduleArg, String expectedSha256) {
            if (moduleArg == null || moduleArg.isBlank()) {
                throw new IllegalArgumentException("load(path): the module path must not be empty.");
            }
            String candidate;
            if (moduleArg.startsWith("./") || moduleArg.startsWith("../")) {
                candidate = currentDir() + "/" + moduleArg;
            } else {
                candidate = moduleArg;
            }
            // Same path rules as fs: containment, no traversal, no escaping symbolic links.
            Path safePath = JsFileSystem.resolveSafePath(candidate);
            String display = JsFileSystem.toRelative(safePath);
            if (!display.toLowerCase(Locale.ROOT).endsWith(".js")) {
                throw new IllegalArgumentException("Only '.js' files can be loaded as modules, "
                        + "got: " + display + " (asked for '" + moduleArg + "')");
            }

            Value cached = moduleCache.get(display);
            if (cached != null) {
                // Cyclic require: the still-initializing module returns its partial exports.
                if (expectedSha256 != null && !expectedSha256.equals(moduleSha256.get(display))) {
                    throw new IllegalArgumentException(
                            pinningMismatchMessage(display, expectedSha256, moduleSha256.get(display)));
                }
                LOGGER.debug("JavaScript module cache hit ({}), returning exports", display);
                return cached;
            }

            String content = JsFileSystem.readFile(display);
            String sha256 = sha256Hex(content);
            if (expectedSha256 != null && !expectedSha256.equals(sha256)) {
                throw new IllegalArgumentException(
                        pinningMismatchMessage(display, expectedSha256, sha256));
            }
            if (evaluationStack.size() >= MAX_MODULE_DEPTH) {
                throw new IllegalArgumentException("Module nesting deeper than " + MAX_MODULE_DEPTH
                        + " while loading '" + display + "' (in " + sourceName + ").");
            }
            LOGGER.info("JavaScript module loaded: {} (sha256: {})", display, sha256);

            Value exportsObj = context.eval("js", "({})");
            // Inserted before the evaluation so that a cyclic require gets the partial exports.
            moduleCache.put(display, exportsObj);
            moduleSha256.put(display, sha256);
            evaluationStack.push(display);
            try {
                checkNotCancelled(cancelRequested, sourceName);
                Value moduleObj = context.eval("js", "({exports: null})");
                moduleObj.putMember("exports", exportsObj);
                // The source is named after the file, so JavaScript stack traces (e.g. in
                // timeout messages) point into the module file.
                String wrapped = "(function (exports, require, module, __filename, __dirname) {\n"
                        + content + "\n})";
                Source moduleSource = Source.newBuilder("js", wrapped, display).build();
                Value moduleFn = context.eval(moduleSource);
                Value requireValue = jsBindings.getMember("require");
                moduleFn.execute(exportsObj, requireValue, moduleObj,
                        context.asValue(display), context.asValue(dirOf(display)));
                Value finalExports = moduleObj.getMember("exports");
                moduleCache.put(display, finalExports);
                return finalExports;
            } catch (IOException e) {
                // Do not leave a half-initialized module in the cache.
                moduleCache.remove(display);
                moduleSha256.remove(display);
                throw new IllegalArgumentException("Cannot load module '" + display + "': " + e.getMessage(), e);
            } catch (RuntimeException e) {
                // Do not leave a half-initialized module in the cache.
                moduleCache.remove(display);
                moduleSha256.remove(display);
                throw e;
            } finally {
                evaluationStack.pop();
            }
        }

        /**
         * Returns the directory (display path) of the module currently being evaluated, or the
         * base directory of the execution (directory of the 'path' file, project base for
         * inline scripts).
         * @return directory display path ("." for the project base)
         */
        private String currentDir() {
            String currentFile = evaluationStack.isEmpty() ? basePath : evaluationStack.peek();
            return (currentFile != null) ? dirOf(currentFile) : ".";
        }

        /**
         * Returns the directory part of a display path.
         * @param displayPath a project-relative (or mount-prefixed) file path
         * @return the directory part ("." if the path has no directory)
         */
        private static String dirOf(String displayPath) {
            int slash = displayPath.lastIndexOf('/');
            return (slash < 0) ? "." : displayPath.substring(0, slash);
        }

        /**
         * Builds the error message for a sha256 pinning mismatch.
         * @param display display path of the module
         * @param expected the pinned hash
         * @param actual the hash of the loaded content (or null on a cache hit without hash)
         * @return the error message
         */
        private static String pinningMismatchMessage(String display, String expected, String actual) {
            return "sha256 mismatch for module " + display + ": pinned " + expected
                    + ", but the content hashes to " + ((actual != null) ? actual : "<unknown>")
                    + ". The file changed after the hash was pinned - re-read it, re-pin the new "
                    + "hash, or load it without a pin.";
        }

        /**
         * Computes the SHA-256 of a module file's content. The content is the strict UTF-8
         * decoding of the raw file bytes (see {@link JsFileSystem#readFile(String)}), so the
         * hash is identical to {@code crypto.sha256(path)} over the raw file bytes.
         * @param content the file content
         * @return lowercase hex hash
         */
        private static String sha256Hex(String content) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(hash);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is not available", e);
            }
        }
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
