package org.rogmann.mcp2sdk.agent;

import java.time.Instant;

/**
 * One line in the thread of an {@link AgentTask}: who wrote what, when.
 *
 * <p>Deliberately <b>not</b> a task of its own (not an {@code AgentTask} of kind INFO with a parent
 * reference): a message has no status, no claimer and no id of its own, and making each message an
 * entry would double the id space, thread the ordering through a parent field and give every line
 * dead claim machinery. A comment is a message on the entry it comments (decision B1,
 * docs/subagent.md §4); only tasks carry work, messages carry words.</p>
 */
record AgentMessage(Instant at, AgentId author, String text) {
}
