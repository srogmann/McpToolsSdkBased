/**
 * ACP (Agent Client Protocol) integration: this project as the ACP <em>agent</em>, with the
 * IDE as the client. The rough plan is docs/acp.md; the stages there (S0–S6) build up from
 * a stdio hello-world to the real {@code AcpChat} behind the {@code /acp} endpoint, reached
 * from the IDE through the stdio-to-HTTP bridge.
 *
 * <p>The classes here are deliberately free of Spring: the IDE launches them as plain
 * subprocesses, and the only thing that may appear on stdout is the JSON-RPC protocol.</p>
 */
package org.rogmann.mcp2sdk.acp;
