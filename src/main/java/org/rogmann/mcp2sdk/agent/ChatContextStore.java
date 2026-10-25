package org.rogmann.mcp2sdk.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The last thing the chat sent: {@code WebUiProxy} records every incoming completion request here,
 * and {@link PrefixForkTool} reads it back to start a child conversation from the <em>current
 * prefix</em> of the main chat (docs/webui.md §3.2).
 *
 * <p><b>What is stored is the incoming body, before any mutation</b> (decision F4). The preparation
 * of a request is not idempotent - {@code ChatTemplateInfo.applyVllmReasoning} reads
 * {@code chat_template_kwargs.enable_thinking} and <em>removes</em> the field - so a child built
 * from the already-prepared body would silently switch thinking off and destroy the very prefix the
 * fork exists for. A child therefore gets the stored input and lets the proxy prepare it, exactly
 * like its parent ({@code chat/ChatRequestPreparer}).</p>
 *
 * <p>Singleton in the style of {@code review/ReviewStore}: tool instances are created statically by
 * {@code McpConfig} and get no Spring beans, and the writer is a controller. The header
 * {@value #HEADER_CONVERSATION_ID} names the conversation; without it (older UI bundle, another
 * client) the key is {@value #DEFAULT_CONVERSATION_ID}, which is correct for the local single-user
 * setup this is written for.</p>
 *
 * <p>Bounded on purpose: {@value #RING_PER_CONVERSATION} snapshots per conversation,
 * {@value #MAX_CONVERSATIONS} conversations, {@value #TTL_MINUTES} minutes, bodies beyond
 * {@value #MAX_BODY_CHARS} characters are dropped. A 200k-token context is about a megabyte of
 * JSON, so two entries per chat are harmless locally and ten are not.</p>
 *
 * <p><b>Cost per request, stated openly:</b> recording serializes the body once (size check and the
 * two digests) and deep-copies it. That is single-digit milliseconds on a megabyte, against a chat
 * turn that takes seconds - and it is cheaper than what the proxy already does per request for the
 * DEBUG request line, whose argument is evaluated whether or not DEBUG is on.</p>
 */
public final class ChatContextStore {

    private static final Logger LOG = LoggerFactory.getLogger(ChatContextStore.class);

    /** Request header the Web UI bundle sends with every completion (conversation id plus model). */
    public static final String HEADER_CONVERSATION_ID = "X-Conversation-Id";

    /**
     * Request header a forked child sets on its loopback request. It is what lets
     * {@link PrefixForkTool} refuse a fork inside a fork (decision F7) - the MCP call of a child
     * carries no other trace of where it came from.
     */
    public static final String HEADER_FORK_DEPTH = "X-Fork-Depth";

    /** Key of a request whose conversation header was absent or blank. */
    public static final String DEFAULT_CONVERSATION_ID = "default";

    /** Snapshots kept per conversation (newest last). */
    static final int RING_PER_CONVERSATION = 2;

    /** Conversations remembered at all; the eldest is dropped first. */
    static final int MAX_CONVERSATIONS = Integer.getInteger("agent.max.conversations", 4);

    /** A snapshot older than this is treated as absent (docs/webui.md §3.2). */
    static final long TTL_MINUTES = 30L;

    /** Bodies beyond this many characters are not remembered (a full context is about a megabyte). */
    static final int MAX_BODY_CHARS = 4 * 1024 * 1024;

    /** The one instance (see class javadoc). */
    private static final ChatContextStore INSTANCE = new ChatContextStore();

    /** Mapper for the digests and the size estimate. */
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    /** Snapshots by conversation key, insertion-ordered so the eldest conversation is first. */
    private final Map<String, ArrayDeque<ContextSnapshot>> byConversation = new LinkedHashMap<>();

    /** Monotonically increasing sequence number of the recorded snapshots. */
    private long sequence = 0L;

    private ChatContextStore() {
        // Singleton
    }

    /**
     * @return the shared store
     */
    public static ChatContextStore getInstance() {
        return INSTANCE;
    }

    /**
     * One conversation prefix, as it arrived.
     *
     * @param conversationId key of the snapshot, {@value #DEFAULT_CONVERSATION_ID} without header
     * @param seq            order of recording, higher is newer
     * @param capturedAt     when the request arrived
     * @param body           deep copy of the <em>incoming</em> body (F4), never prepared
     * @param selfBaseUrl    base URL the proxy was addressed by, for the loopback of the child
     * @param messageCount   number of messages, for the report header
     * @param approxChars    size of the body in characters, for the report header
     * @param messagesDigest SHA-256 over the serialized {@code messages[]}
     * @param toolsDigest    SHA-256 over the serialized {@code tools[]}, empty when there are none
     * @param forkDepth      {@code 0} for a human request, {@code >= 1} for a forked child
     */
    public record ContextSnapshot(String conversationId, long seq, Instant capturedAt, ObjectNode body,
                                  String selfBaseUrl, int messageCount, int approxChars,
                                  String messagesDigest, String toolsDigest, int forkDepth) {

        /** @return short label for the report and the log, e.g. {@code "seq 17"} */
        public String label() {
            return "seq " + seq + " of " + conversationId;
        }

        /** @return the digests in the form the report shows them */
        public String digests() {
            return "messages=" + shortDigest(messagesDigest) + " tools=" + shortDigest(toolsDigest);
        }

        /** @param digest hex digest @return first 12 characters, or {@code "-"} when absent */
        private static String shortDigest(String digest) {
            return (digest == null || digest.isEmpty()) ? "-" : digest.substring(0, Math.min(12, digest.length()));
        }
    }

    /**
     * Remembers one incoming completion request. Called by the proxy directly after parsing the
     * body, before the request is prepared for either forwarding path.
     *
     * @param conversationIdHeader value of {@value #HEADER_CONVERSATION_ID}, may be null
     * @param forkDepthHeader      value of {@value #HEADER_FORK_DEPTH}, may be null
     * @param body                 the parsed incoming request body (copied, never mutated here)
     * @param selfBaseUrl          URL the proxy was addressed with, e.g. {@code http://127.0.0.1:9091}
     * @return the stored snapshot, or {@code null} when the request was not remembered
     */
    public ContextSnapshot record(String conversationIdHeader, String forkDepthHeader,
                                  JsonNode body, String selfBaseUrl) {
        return recordAt(conversationIdHeader, forkDepthHeader, body, selfBaseUrl, Instant.now());
    }

    /**
     * Same as {@link #record}, with the capture time given - the store's TTL is otherwise only
     * reachable after half an hour, which is a long unit test. Package-private on purpose.
     *
     * @param conversationIdHeader value of {@value #HEADER_CONVERSATION_ID}, may be null
     * @param forkDepthHeader      value of {@value #HEADER_FORK_DEPTH}, may be null
     * @param body                 parsed incoming request body
     * @param selfBaseUrl          base URL of the proxy
     * @param capturedAt           capture time to store
     * @return the stored snapshot or {@code null}
     */
    ContextSnapshot recordAt(String conversationIdHeader, String forkDepthHeader,
                             JsonNode body, String selfBaseUrl, Instant capturedAt) {
        if (body == null || !body.isObject()) {
            return null;
        }
        String conversationId = (conversationIdHeader == null || conversationIdHeader.isBlank())
                ? DEFAULT_CONVERSATION_ID : conversationIdHeader.trim();
        int forkDepth = parseInt(forkDepthHeader);
        try {
            String serialized = jsonMapper.writeValueAsString(body);
            if (serialized.length() > MAX_BODY_CHARS) {
                LOG.info("Not remembering conversation '{}': body of {} chars exceeds the {} limit",
                        conversationId, serialized.length(), MAX_BODY_CHARS);
                return null;
            }
            ObjectNode copy = (ObjectNode) body.deepCopy();
            ContextSnapshot snapshot = new ContextSnapshot(conversationId, nextSequence(), capturedAt,
                    copy, trimTrailingSlash(selfBaseUrl),
                    body.path("messages").isArray() ? body.path("messages").size() : 0,
                    serialized.length(),
                    digest(body.get("messages")), digest(body.get("tools")), forkDepth);
            remember(snapshot);
            LOG.debug("Recorded chat context {}: {} messages, {} chars, fork depth {}",
                    snapshot.label(), snapshot.messageCount(), snapshot.approxChars(), forkDepth);
            return snapshot;
        } catch (RuntimeException e) {
            // A snapshot is diagnostics plus a fork source, never a reason to fail a chat request.
            LOG.warn("Failed to record chat context of '{}'", conversationId, e);
            return null;
        }
    }

    /**
     * Newest snapshot of one conversation, ignoring expired entries.
     *
     * @param conversationId key, {@code null} for {@value #DEFAULT_CONVERSATION_ID}
     * @return the snapshot or {@code null}
     */
    public synchronized ContextSnapshot latest(String conversationId) {
        String key = (conversationId == null || conversationId.isBlank())
                ? DEFAULT_CONVERSATION_ID : conversationId.trim();
        return newestOf(byConversation.get(key));
    }

    /**
     * Newest snapshot of any conversation - the fallback when the tool cannot know which chat it
     * belongs to (no conversation header was seen at all).
     *
     * @return the snapshot or {@code null}
     */
    public synchronized ContextSnapshot latestAny() {
        ContextSnapshot best = null;
        for (ArrayDeque<ContextSnapshot> ring : new ArrayList<>(byConversation.values())) {
            ContextSnapshot candidate = newestOf(ring);
            if (candidate != null && (best == null || candidate.seq() > best.seq())) {
                best = candidate;
            }
        }
        return best;
    }

    /** @return true when at least one unexpired snapshot exists */
    public synchronized boolean hasAny() {
        return latestAny() != null;
    }

    /**
     * Newest snapshot of one conversation that a <em>human</em> request produced ({@code forkDepth}
     * 0). This is what {@link PrefixForkTool} forks from.
     *
     * <p>The distinction matters because a child's own completion arrives at the same proxy and is
     * recorded too - under {@value #DEFAULT_CONVERSATION_ID}, since the runner sends no conversation
     * header. Treating that as "the current prefix" would make the second fork of a chat start from
     * the first fork's child, and (through the depth check) refuse the human's next call outright.</p>
     *
     * @param conversationId key, {@code null} for {@value #DEFAULT_CONVERSATION_ID}
     * @return the snapshot or {@code null}
     */
    public synchronized ContextSnapshot latestRoot(String conversationId) {
        String key = (conversationId == null || conversationId.isBlank())
                ? DEFAULT_CONVERSATION_ID : conversationId.trim();
        return newestRootOf(byConversation.get(key));
    }

    /** @return newest human snapshot of any conversation, or {@code null} */
    public synchronized ContextSnapshot latestRootAny() {
        ContextSnapshot best = null;
        for (ArrayDeque<ContextSnapshot> ring : new ArrayList<>(byConversation.values())) {
            ContextSnapshot candidate = newestRootOf(ring);
            if (candidate != null && (best == null || candidate.seq() > best.seq())) {
                best = candidate;
            }
        }
        return best;
    }

    /** Drops every snapshot (unit tests). */
    public synchronized void clear() {
        byConversation.clear();
    }

    /**
     * How many snapshots a conversation currently has. Package-private: the unit test of the ring
     * bound, and a diagnostic endpoint would be the other reader.
     *
     * @param conversationId key, {@code null} for {@value #DEFAULT_CONVERSATION_ID}
     * @return number of stored snapshots, 0 for an unknown conversation
     */
    synchronized int snapshotCount(String conversationId) {
        String key = (conversationId == null || conversationId.isBlank())
                ? DEFAULT_CONVERSATION_ID : conversationId.trim();
        ArrayDeque<ContextSnapshot> ring = byConversation.get(key);
        return (ring == null) ? 0 : ring.size();
    }

    /** @return number of conversations currently remembered (unit tests, diagnostics) */
    synchronized int conversationCount() {
        return byConversation.size();
    }

    /** @return next sequence number */
    private synchronized long nextSequence() {
        return ++sequence;
    }

    /**
     * Puts the snapshot into the ring of its conversation and applies the ring and conversation
     * limits.
     *
     * @param snapshot snapshot to remember
     */
    private synchronized void remember(ContextSnapshot snapshot) {
        ArrayDeque<ContextSnapshot> ring = byConversation.computeIfAbsent(
                snapshot.conversationId(), key -> new ArrayDeque<>());
        ring.addLast(snapshot);
        while (ring.size() > RING_PER_CONVERSATION) {
            ring.removeFirst();
        }
        byConversation.remove(snapshot.conversationId());
        byConversation.put(snapshot.conversationId(), ring);   // re-insert: marks the entry as the youngest
        while (byConversation.size() > MAX_CONVERSATIONS) {
            Iterator<String> keys = byConversation.keySet().iterator();
            if (!keys.hasNext()) {
                break;
            }
            String eldest = keys.next();
            keys.remove();
            LOG.debug("Dropping the snapshots of conversation '{}', more than {} conversations active",
                    eldest, MAX_CONVERSATIONS);
        }
    }

    /**
     * @param ring ring to read, may be null
     * @return the newest unexpired snapshot of the ring, dropping the expired ones on the way
     */
    private ContextSnapshot newestOf(ArrayDeque<ContextSnapshot> ring) {
        ArrayDeque<ContextSnapshot> live = unexpired(ring);
        return (live == null) ? null : live.peekLast();
    }

    /**
     * Same as {@link #newestOf}, but skipping the snapshots a forked child produced.
     *
     * @param ring ring to read, may be null
     * @return the newest unexpired human snapshot, or {@code null}
     */
    private ContextSnapshot newestRootOf(ArrayDeque<ContextSnapshot> ring) {
        ArrayDeque<ContextSnapshot> live = unexpired(ring);
        if (live == null) {
            return null;
        }
        Iterator<ContextSnapshot> fromNewest = live.descendingIterator();
        while (fromNewest.hasNext()) {
            ContextSnapshot candidate = fromNewest.next();
            if (candidate.forkDepth() == 0) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * @param ring ring to read, may be null
     * @return the ring without its expired front entries, or {@code null} when nothing is left
     */
    private ArrayDeque<ContextSnapshot> unexpired(ArrayDeque<ContextSnapshot> ring) {
        if (ring == null) {
            return null;
        }
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(TTL_MINUTES));
        // The ring is time-ordered, so the expired entries sit in front of the live ones.
        while (!ring.isEmpty() && ring.peekFirst().capturedAt().isBefore(cutoff)) {
            ring.removeFirst();
        }
        return ring.isEmpty() ? null : ring;
    }

    /**
     * @param node node to digest, may be null or missing
     * @return lowercase SHA-256 hex of its serialization, empty string for an absent node
     */
    private String digest(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha.digest(jsonMapper.writeValueAsString(node).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Digest unavailable", e);
        }
    }

    /** @param text header value @return the int it holds, 0 when absent or unparsable */
    private static int parseInt(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(text.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** @param url URL as the servlet reported it @return the same URL without a trailing slash */
    private static String trimTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
