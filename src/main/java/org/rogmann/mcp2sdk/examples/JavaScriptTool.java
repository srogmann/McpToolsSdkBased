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
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.rogmann.mcp2sdk.ToolSpecWithState;
import org.rogmann.mcp2sdk.ToolState;
import org.rogmann.mcp2sdk.js.JsArchiveBridge;
import org.rogmann.mcp2sdk.js.JsCryptoBridge;
import org.rogmann.mcp2sdk.js.JsFileSystemBridge;
import org.rogmann.mcp2sdk.js.JsMcpProxyBridge;
import org.rogmann.mcp2sdk.js.JsModuleInterface;
import org.rogmann.mcp2sdk.js.JsObjdumpBridge;
import org.rogmann.mcp2sdk.js.JsPythonBridge;
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
import java.util.logging.Logger;

/**
 * MCP tool implementation for writing and executing small JavaScript scripts.
 * Uses GraalVM Polyglot to run JavaScript code and captures console.log output.
 */
public class JavaScriptTool {

    private static final Logger LOGGER = Logger.getLogger(JavaScriptTool.class.getName());

    private static final String NAME = "javascript_tool";

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
        modules.put("objdump", new JsObjdumpBridge());
        modules.put("python", new JsPythonBridge());
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
        return sb.toString();
    }

    /**
     * Creates the synchronous tool specification for the JavaScript execution tool.
     * @return the tool specification and its state
     */
    public static ToolSpecWithState createToolInstance() {
        LinkedHashMap<String, JsModuleInterface> modules = createModules();
        String description = buildDescription(modules);

        // Define Input Schema properties
        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> scriptProp = new HashMap<>();
        scriptProp.put("type", "string");
        scriptProp.put("description", "JavaScript source code");
        properties.put("script", scriptProp);

        List<String> requiredFields = List.of("script");

        JsonSchema inputSchema = new JsonSchema("object", properties, requiredFields, null, null, null);

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
     * @param exchange the server exchange
     * @param request the tool call request
     * @return the tool call result
     */
    McpSchema.CallToolResult call(McpSyncServerExchange exchange, CallToolRequest request) {
        // Increment call count
        state.callCount().incrementAndGet();

        Map<String, Object> arguments = request.arguments();

        Object oScript = arguments.get("script");
        if (oScript == null) {
            return CallToolResult.builder()
                .isError(true)
                .addTextContent("Missing 'script' parameter in request")
                .build();
        }

        String script = oScript.toString();
        LOGGER.info("Executing JavaScript: " + script);

        // Capture console.log output (stdout)
        ByteArrayOutputStream baosOut = new ByteArrayOutputStream();
        PrintStream outCapture = new PrintStream(baosOut, true, StandardCharsets.UTF_8);

        // Capture stderr separately (e.g. Truffle warnings) for logging only
        ByteArrayOutputStream baosErr = new ByteArrayOutputStream();
        PrintStream errCapture = new PrintStream(baosErr, true, StandardCharsets.UTF_8);

        // Per-call resources returned by module wiring (e.g. an MCP client connection),
        // closed after the JavaScript call.
        List<AutoCloseable> callResources = new ArrayList<>();

        try (Context context = Context.newBuilder("js")
                .out(outCapture)
                .err(errCapture)
                .in(InputStream.nullInputStream())
                .sandbox(SandboxPolicy.CONSTRAINED)
                .build()) {

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

            // Execute the JavaScript code and capture the return value
            org.graalvm.polyglot.Value result = context.eval("js", script);

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
                    org.graalvm.polyglot.SourceSection loc = pe.getSourceLocation();
                    sbMsg.append(String.format("\nSource location: (line %d, column %d)",
                            loc.getStartLine(), loc.getStartColumn()));
                }
                sbMsg.append(buildHint());
            } else {
                sbMsg.append(e.getMessage());
            }
            String errorMessage = sbMsg.toString();
            LOGGER.severe("Error during JavaScript execution: " + errorMessage);
            return CallToolResult.builder()
                .isError(true)
                .addTextContent(errorMessage)
                .build();
        } finally {
            // Close per-call module resources (e.g. the MCP client connection).
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
     * The full Java class name remains visible in the server's SLF4J error log. A full Java
     * backend stacktrace is not available here; GraalJS only provides the guest-language program
     * location, which is not useful to print.
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

}
