package org.rogmann.mcp2sdk.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Which conversation an MCP session belongs to: the map {@code MCP session -> chat id}
 * (docs/acp.md §6, S6 — the second pairing {@code ForkIdentityFilter} observes).
 *
 * <p>{@code PrefixForkTool} needs the conversation of its caller to fork from the right
 * prefix. The call carries its conversation id in the {@code X-Conversation-Id} header,
 * set by the caller's MCP client out of its {@code McpTransportContext} — an identity
 * that comes from outside the model's arguments, the P0 rule of docs/subagent.md §14
 * (an identity a caller could write into its tool arguments is no identity). The filter
 * pairs the header with the session the server handed out, and the tool reads the
 * pairing of its own exchange.</p>
 *
 * <p><b>Known race, stated openly.</b> One bridge serves every ACP session of an
 * IntelliJ connection, and they share one MCP session: two sessions calling at the same
 * time overwrite each other's entry, and the earlier reader may see the later caller's
 * conversation. A fork that starts from the wrong prefix is visible (its report names
 * the parent conversation) and bounded to that coincidence; the alternative would be an
 * MCP session per ACP session, which the shared client does not have. Entries expire
 * like in {@link ForkIdentity} — a registry with only a happy path is a leak that looks
 * tidy.</p>
 */
public final class McpConversations {

    private static final Logger LOG = LoggerFactory.getLogger(McpConversations.class);

    /** Entries older than this are dropped on the next registration. */
    private static final long MAX_AGE_NANOS = TimeUnit.MINUTES.toNanos(30);

    /** The one registry: the filter writes, the tools read. */
    private static final McpConversations INSTANCE = new McpConversations();

    /** @return the shared registry */
    public static McpConversations getInstance() {
        return INSTANCE;
    }

    /** An entry plus the moment it was seen, so expiry can tell a live session from a leftover. */
    private record Stamped(String conversationId, long atNano) {
    }

    private final Map<String, Stamped> bySession = new ConcurrentHashMap<>();

    private McpConversations() {
        // singleton
    }

    /**
     * Records what one request said about itself. A request without a session (the
     * {@code initialize()} of a client) or without the conversation header (every caller
     * that is not the ACP bridge) maps nothing — the latter is the normal case and logs
     * nothing.
     *
     * @param sessionId      value of the MCP session header, may be null
     * @param conversationId value of {@code X-Conversation-Id}, may be null
     */
    public void register(String sessionId, String conversationId) {
        if (sessionId == null || sessionId.isBlank()
                || conversationId == null || conversationId.isBlank()) {
            return;
        }
        pruneExpired();
        Stamped previous = bySession.put(sessionId.trim(),
                new Stamped(conversationId.trim(), System.nanoTime()));
        if (previous == null || !previous.conversationId().equals(conversationId.trim())) {
            LOG.info("MCP conversation: session {} speaks for {}", sessionId.trim(),
                    conversationId.trim());
        }
    }

    /**
     * @param sessionId MCP session of the call, may be null
     * @return the conversation that call belongs to, or {@code null} for anyone else
     *         (never a guess)
     */
    public String lookup(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        Stamped stamped = bySession.get(sessionId);
        if (stamped == null) {
            return null;
        }
        if (System.nanoTime() - stamped.atNano() > MAX_AGE_NANOS) {
            bySession.remove(sessionId, stamped);
            return null;
        }
        return stamped.conversationId();
    }

    /** Empties the registry (tests). */
    public void clear() {
        bySession.clear();
    }

    /** Drops entries nobody refreshed; cheap, the map holds one entry per MCP session. */
    private void pruneExpired() {
        long now = System.nanoTime();
        bySession.entrySet().removeIf(e -> now - e.getValue().atNano() > MAX_AGE_NANOS);
    }
}
