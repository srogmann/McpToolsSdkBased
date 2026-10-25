package org.rogmann.mcp2sdk.acp;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.Optional;

import com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport;
import com.agentclientprotocol.sdk.client.transport.StreamableHttpAcpClientTransport;
import com.agentclientprotocol.sdk.client.transport.WebSocketAcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Thin stdio-to-HTTP bridge (docs/acp.md §5, Option A): IntelliJ launches this process as a
 * subprocess, and it relays every JSON-RPC message between the IDE's stdio transport and the
 * running server's {@code /acp} endpoint. It has no ACP logic of its own — the agent loop
 * stays server-side; the bridge is the "small client that maps stdin to our URLs".
 *
 * <p>Both directions carry requests: the IDE's (initialize, session/new, session/prompt)
 * towards the server, and the server agent's (fs/read_text_file, fs/write_text_file,
 * request_permission) towards the IDE. That is why the bridge pumps both ways instead of
 * answering anything itself: each side's JSON-RPC session matches responses by id, and the
 * transports are documented to carry requests, responses and notifications alike. The agent
 * transport's handler returns empty (the answer comes later over the other channel), and the
 * client transport's handler is documented to return empty anyway.</p>
 *
 * <p>Discipline like {@code HelloAcpAgent}: stdout carries only the protocol; logging goes
 * to stderr with a {@code [acp-bridge]} prefix.</p>
 *
 * <p>Endpoint resolution: system property {@code acp.agent.url}, then environment variable
 * {@code ACP_AGENT_URL}, then the default {@code ws://127.0.0.1:9091/acp}; an
 * {@code http(s)://} URL switches to the Streamable HTTP client transport. The URI must
 * include its path ({@code /acp}).</p>
 */
public final class AcpBridge {

    private static final String DEFAULT_URL = "ws://127.0.0.1:9091/acp";

    private AcpBridge() {
    }

    /**
     * Runs the bridge until the IDE closes standard input (then the server connection is
     * closed) or the server connection ends (then the stdio transport is closed).
     * @param args ignored
     */
    public static void main(String[] args) {
        // Resolution order: system property acp.agent.url, then the environment, then the
        // default — the IDE launch can override via acp.json's "env" or a -D in a launcher.
        String url = Optional.ofNullable(System.getProperty("acp.agent.url"))
                .filter(s -> !s.isBlank())
                .orElseGet(() -> Optional.ofNullable(System.getenv("ACP_AGENT_URL")).orElse(DEFAULT_URL));
        err("starting, pid=" + ProcessHandle.current().pid() + ", server=" + describe(url));
        AcpClientTransport serverTransport = serverTransport(url);
        AcpAgentTransport ideTransport = new StdioAcpAgentTransport();

        // Register the server side first: the IDE's first message can arrive as soon as
        // start() runs, and the WebSocket transport opens its connection on connect().
        // The handler param is a one-element Mono of the message (AcpTransport contract):
        // subscribe it here and relay the raw message; return empty, so neither transport
        // writes a reply itself — the answers arrive as messages over the other side.
        serverTransport.connect(messageMono -> {
            messageMono.subscribe(
                message -> ideTransport.sendMessage(message).subscribe(ignored -> { }, AcpBridge::logSendError),
                AcpBridge::logSendError);
            return Mono.empty();
        }).block();

        // Reactor is lazy: start() only runs when subscribed. Without block() the stdio
        // side never reads stdin and the bridge hangs without any output (S2 lesson).
        ideTransport.start(messageMono -> {
            messageMono.subscribe(
                message -> serverTransport.sendMessage(message).subscribe(ignored -> { }, AcpBridge::logSendError),
                AcpBridge::logSendError);
            return Mono.empty();
        }).block();

        Thread serverWatchdog = new Thread(() -> {
            serverTransport.awaitTermination().block();
            err("server connection ended — closing the IDE transport");
            ideTransport.close();
        }, "acp-bridge-watchdog");
        serverWatchdog.setDaemon(true);
        serverWatchdog.start();

        err("relaying until the IDE closes stdin");
        ideTransport.awaitTermination().block();
        serverTransport.close();
        err("done");
    }

    private static AcpClientTransport serverTransport(String url) {
        URI uri = URI.create(url);
        String scheme = (uri.getScheme() == null) ? "" : uri.getScheme().toLowerCase();
        return switch (scheme) {
            case "ws", "wss" -> new WebSocketAcpClientTransport(uri);
            case "http", "https" -> new StreamableHttpAcpClientTransport(uri);
            default -> throw new IllegalArgumentException("Unsupported scheme in ACP_AGENT_URL: " + url);
        };
    }

    private static String describe(String url) {
        URI uri = URI.create(url);
        int port = uri.getPort();
        return uri.getScheme() + "://" + uri.getHost() + (port < 0 ? "" : ":" + port) + uri.getPath();
    }

    private static void logSendError(Throwable t) {
        LoggerFactory.getLogger(AcpBridge.class).debug("Relaying a message failed", t);
        err("send failed: " + t);
    }

    private static void err(String message) {
        System.err.println("[acp-bridge " + LocalDateTime.now() + "] " + message);
    }
}
