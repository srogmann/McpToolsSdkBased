package org.rogmann.mcp2sdk.acp;

import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpSyncAgent;
import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * S1 hello-world ACP agent (docs/acp.md §6): SDK-only, stdio, no Spring, no server.
 *
 * <p>IntelliJ launches this class as a subprocess (acp.json: {@code command}, e.g. via
 * {@code scripts/acp-hello.sh}), and it speaks JSON-RPC over stdio like any ACP agent.
 * It exists to settle three questions cheaply before any real ACP work starts: does the
 * IDE launch the command as its own process, does its chat window render session updates,
 * and does the IDE's ACP client advertise the fs capabilities that produce editor diffs.</p>
 *
 * <p>Discipline: stdout carries only the protocol (the transport wraps it); everything
 * worth knowing goes to stderr with a {@code [hello-acp]} prefix — pid, the client's
 * {@code initialize} payload (client info and client capabilities), the session cwd and
 * every prompt. A prompt containing {@code diff} or {@code write} asks the client to write
 * {@value #DEMO_FILE} into the session cwd via {@code fs/write_text_file} — the S1 diff
 * test. A prompt containing {@code think} sends a thought chunk first.</p>
 */
public final class HelloAcpAgent {

    /** Name of the file the write demo asks the client to create. */
    private static final String DEMO_FILE = "acp-hello.txt";

    /** cwd of the latest session (the hello agent handles one session at a time). */
    private final AtomicReference<String> sessionCwd = new AtomicReference<>();

    private HelloAcpAgent() {
    }

    /**
     * Entry point: builds the sync agent over the stdio transport and blocks until the
     * client disconnects.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        err("starting, pid=" + ProcessHandle.current().pid() + ", args=" + List.of(args));
        HelloAcpAgent hello = new HelloAcpAgent();
        AcpSyncAgent agent = AcpAgent.sync(new StdioAcpAgentTransport())
            .initializeHandler(hello::onInitialize)
            .newSessionHandler(hello::onNewSession)
            .promptHandler(hello::onPrompt)
            .build();
        agent.run();
        err("client closed stdin — exiting");
    }

    private AcpSchema.InitializeResponse onInitialize(AcpSchema.InitializeRequest request) {
        err("initialize: protocolVersion=" + request.protocolVersion()
                + ", clientInfo=" + request.clientInfo()
                + ", clientCapabilities=" + request.clientCapabilities());
        return AcpSchema.InitializeResponse.ok();
    }

    private AcpSchema.NewSessionResponse onNewSession(AcpSchema.NewSessionRequest request) {
        sessionCwd.set(request.cwd());
        err("session/new: cwd=" + request.cwd() + ", mcpServers="
                + (request.mcpServers() == null ? 0 : request.mcpServers().size()));
        return new AcpSchema.NewSessionResponse(UUID.randomUUID().toString());
    }

    private AcpSchema.PromptResponse onPrompt(AcpSchema.PromptRequest request, SyncPromptContext context) {
        String text = extractText(request.prompt());
        err("prompt in session " + request.sessionId() + ": "
                + (text.length() > 200 ? text.substring(0, 200) + "…" : text));
        String lower = text.toLowerCase();
        if (lower.contains("think")) {
            context.sendThought("HelloAcpAgent has no thoughts, but it can send thought chunks.");
        }
        context.sendMessage("Hello from mcp2sdk! You said: "
                + (text.isBlank() ? "(nothing)" : text));
        if (lower.contains("diff") || lower.contains("write")) {
            demoWrite(context);
        }
        return AcpSchema.PromptResponse.endTurn();
    }

    private void demoWrite(SyncPromptContext context) {
        String cwd = sessionCwd.get();
        String path = (cwd == null || cwd.isBlank()) ? DEMO_FILE : Paths.get(cwd, DEMO_FILE).toString();
        String content = "Hello from ACP.\nWritten by HelloAcpAgent at " + LocalDateTime.now() + ".\n";
        try {
            context.writeFile(path, content);
            context.sendMessage("\n\nI asked the client to write `" + path
                    + "` — look for the diff.");
            err("fs/write_text_file answered for " + path);
        } catch (RuntimeException e) {
            context.sendMessage("\n\nNo write demo: the client refused or cannot write files ("
                    + e.getClass().getSimpleName() + ": " + e.getMessage() + ").");
            err("writeFile failed: " + e);
        }
    }

    private static String extractText(List<AcpSchema.ContentBlock> prompt) {
        StringBuilder sb = new StringBuilder();
        for (AcpSchema.ContentBlock block : prompt) {
            if (block instanceof AcpSchema.TextContent tc && tc.text() != null) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(tc.text());
            }
        }
        return sb.toString();
    }

    private static void err(String message) {
        System.err.println("[hello-acp " + LocalDateTime.now() + "] " + message);
    }
}
