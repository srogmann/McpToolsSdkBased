package org.rogmann.mcp2sdk.agent;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One entry of the board of a fork batch: a piece of work ({@link AgentTaskKind#TASK}) or an
 * announcement ({@link AgentTaskKind#INFO}), with the messages hanging off it.
 *
 * <p>Every mutation goes through {@link AgentBoard}, which guards each change with that board's
 * lock; the setters are package-private on purpose. The {@code lastContact} map registers
 * <em>contact</em> - post, comment, claim, close, or a list that returned the entry - not attention:
 * a model that remembers an entry from an earlier result without calling the board appears nowhere
 * (decision B3, docs/subagent.md §5).</p>
 */
final class AgentTask {

    private final String id;
    /** Task id of the caller's round this entry was seeded from ({@code t1}, ...), or null. */
    private final String origin;
    private final AgentTaskKind kind;
    private final String subject;
    private final Instant createdAt;
    private final AgentId createdBy;
    private final Map<AgentId, Instant> lastContact = new ConcurrentHashMap<>();
    private final List<AgentMessage> messages = new CopyOnWriteArrayList<>();

    private AgentTaskStatus status = AgentTaskStatus.OPEN;
    private AgentId claimedBy;
    /**
     * Counts every change of this entry (message, status, claim). A thread sleeping in
     * {@link AgentBoard#awaitMessages} watches this number instead of re-reading the entry: the
     * sleep is what a model turn cannot afford, and a counter is what makes it cheap enough to
     * poll safely (docs/subagent.md §14, P1).
     */
    private long revision = 0L;

    AgentTask(String id, String origin, AgentTaskKind kind, String subject, Instant createdAt,
              AgentId createdBy) {
        this.id = id;
        this.origin = origin;
        this.kind = kind;
        this.subject = subject;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
    }

    String id() {
        return id;
    }

    String origin() {
        return origin;
    }

    AgentTaskKind kind() {
        return kind;
    }

    String subject() {
        return subject;
    }

    Instant createdAt() {
        return createdAt;
    }

    AgentId createdBy() {
        return createdBy;
    }

    /** @return which agent last had contact with this entry, and when */
    Map<AgentId, Instant> lastContact() {
        return lastContact;
    }

    List<AgentMessage> messages() {
        return messages;
    }

    AgentTaskStatus status() {
        return status;
    }

    AgentId claimedBy() {
        return claimedBy;
    }

    /** @return the number of changes this entry went through (the sleeper's watch word) */
    long revision() {
        return revision;
    }

    void setStatus(AgentTaskStatus status) {
        this.status = status;
        this.revision++;
    }

    void setClaimedBy(AgentId claimedBy) {
        this.claimedBy = claimedBy;
        this.revision++;
    }

    void addMessage(AgentMessage message) {
        messages.add(message);
        this.revision++;
    }

    void touch(AgentId who, Instant when) {
        lastContact.put(who, when);
    }
}
