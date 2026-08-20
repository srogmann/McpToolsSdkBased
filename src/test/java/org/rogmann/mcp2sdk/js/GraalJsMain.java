package org.rogmann.mcp2sdk.js;

import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.SandboxPolicy;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.rogmann.mcp2sdk.poi.PoiToolBox;
import org.rogmann.mcp2sdk.poi.PoiToolBoxJsBridge;

/**
 * Example of executing JavaScript with GraalVM Polyglot.
 * <p>
 * Supports an optional {@code --mcp-url <url>} argument to connect to an MCP server
 * and make its tools available as the {@code mcp} namespace in JavaScript.
 * </p>
 */
public class GraalJsMain {

    public static void main(String[] mainArgs) {
        // Parse command-line arguments
        String mcpUrl = null;
        for (int i = 0; i < mainArgs.length; i++) {
            if ("--mcp-url".equals(mainArgs[i]) && i + 1 < mainArgs.length) {
                mcpUrl = mainArgs[++i];
            }
        }

        // Ensure test.txt exists for the demo below.
        ToolStore.writeFile("test.txt", "Zeile 1\nZeile 2\nZeile 3\n");

        PrintStream outWrapper = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        PrintStream errWrapper = new PrintStream(System.err, true, StandardCharsets.UTF_8);

        JsMcpProxy mcpProxy = null;
        try (Context context = Context.newBuilder("js")
                .out(outWrapper)
                .err(errWrapper)
                .in(InputStream.nullInputStream())
                .sandbox(SandboxPolicy.CONSTRAINED)
                .build()) {

            // --- Wire console.log ---
            ProxyExecutable logFunc = (cArgs) -> {
                String msg = cArgs[0].asString();
                System.out.println("console.log: " + msg);
                return null;
            };
            ProxyObject consoleObj = ProxyObject.fromMap(Map.of(
                    "log", logFunc));
            context.getBindings("js").putMember("console", consoleObj);

            // --- Wire ToolStore as a JS namespace object "tool" ---
            ProxyObject toolObj = ProxyObject.fromMap(Map.of(
                "readFile", (ProxyExecutable) args -> ToolStore.readFile(args[0].asString()),
                "writeFile", (ProxyExecutable) args -> {
                    ToolStore.writeFile(args[0].asString(), args[1].asString());
                    return null;
                },
                "readFileNames", (ProxyExecutable) args ->
                    ToolStore.readFileNames().toArray(new String[0])
            ));
            context.getBindings("js").putMember("tool", toolObj);

            // --- Wire POI namespace for .xlsx access ---
            ProxyObject poiObj = PoiToolBoxJsBridge.createPoiNamespace(new PoiToolBox());
            context.getBindings("js").putMember("poi", poiObj);
            System.out.println("POI namespace 'poi' bound to JavaScript context");

            // --- Wire fs namespace for controlled file access ---
            Value uint8ArrayCtor = context.getBindings("js").getMember("Uint8Array");
            ProxyObject fsObj = JsFileSystemBridge.createFsNamespace(uint8ArrayCtor);
            context.getBindings("js").putMember("fs", fsObj);
            System.out.println("File system namespace 'fs' bound to JavaScript context");

            // --- Wire archive namespace for ZIP/tar read access ---
            ProxyObject archiveObj = JsArchiveBridge.createArchiveNamespace(uint8ArrayCtor);
            context.getBindings("js").putMember("archive", archiveObj);
            System.out.println("Archive namespace 'archive' bound to JavaScript context");

            // --- Wire python namespace for compile-only Python syntax checks ---
            // (requires IDE_PROJECT_DIR to be set, like fs.*; compile never executes the code)
            ProxyObject pythonObj = JsPythonBridge.createPythonNamespace();
            context.getBindings("js").putMember("python", pythonObj);
            System.out.println("Python namespace 'python' bound to JavaScript context (compile/syntax check only)");

            // --- Wire MCP namespace if --mcp-url was provided ---
            if (mcpUrl != null) {
                System.out.println("Connecting to MCP server: " + mcpUrl);
                mcpProxy = new JsMcpProxy(mcpUrl);
                mcpProxy.bindToContext(context);
                System.out.println("MCP namespace 'mcp' bound with " + mcpProxy.getTools().size() + " tools");
            }

            // --- JavaScript: read test.txt and count lines ---
            String jsCode = """
                    var content = tool.readFile("test.txt");
                    var lines = content.split('\\n').length;
                    console.log("Datei test.txt hat " + lines + " Zeile(n).");
                    console.log("Inhalt:\\n" + content);

                    // --- fs namespace demo ---
                    var names = fs.readdir(".");
                    console.log("Dateien: " + names.join(", "));
                    var st = fs.stat("test.txt");
                    console.log("test.txt: " + st.size + " bytes, isFile=" + st.isFile);
                    var r = fs.createLineReader("test.txt");
                    var line;
                    var count = 0;
                    while ((line = r.next()) !== null) {
                        count++;
                    }
                    r.close();
                    console.log("Zeilen via LineReader: " + count);
                    fs.writeFile("copy.txt", "Hello fs\\n");
                    console.log("copy.txt: " + fs.readFile("copy.txt"));
                    console.log(fs.help());
                    """;

            // If MCP tools are available, demonstrate them as well
            if (mcpUrl != null) {
                jsCode = jsCode + """
                        // List available MCP tools
                        var toolNames = Object.keys(mcp);
                        console.log("Verf\\u00fcgbare MCP-Tools: " + toolNames.join(", "));
                        
                        // Show help for the first tool
                        if (toolNames.length > 0) {
                            var firstTool = mcp[toolNames[0]];
                            console.log("Hilfe f\\u00fcr " + toolNames[0] + ":\\n" + firstTool.help());
                        }
                        """;
            }

            context.eval("js", jsCode);

            System.out.println("--- JavaScript executed successfully ---");
        } catch (Exception e) {
            throw new RuntimeException("Error during JavaScript execution", e);
        } finally {
            if (mcpProxy != null) {
                try {
                    mcpProxy.close();
                } catch (Exception e) {
                    System.err.println("Error closing JsMcpProxy: " + e.getMessage());
                }
            }
        }
    }

}
