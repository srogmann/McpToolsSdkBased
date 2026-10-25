package org.rogmann.mcp2sdk.util;

/**
 * Text accumulator with a hard character limit: once the limit is reached, further text is only
 * counted and {@link #text()} names the dropped characters, so a bounded excerpt never looks like
 * the complete text.
 *
 * <p>Used by {@code chat/SseResponseLog} for the {@code webui.logResponses} DEBUG dump and by the
 * fork reports of {@code agent/PrefixForkTool}. It lived as a nested class of {@code WebUiProxy}
 * until docs/webui.md R1 moved it out - a nested class of a controller is no usable dependency.
 * Lives in {@code util} so that packages other than {@code chat} can use it.</p>
 */
public final class BoundedText {
    /** Cap for the captured characters. */
    private final int maxChars;
    /** Captured characters. */
    private final StringBuilder sb;
    /** Characters seen beyond the cap. */
    private long droppedChars = 0L;

    /**
     * @param maxChars maximum number of characters to keep
     */
    public BoundedText(int maxChars) {
        this.maxChars = maxChars;
        this.sb = new StringBuilder(Math.min(256, maxChars));
    }

    /**
     * Appends a delta; null, empty and over-limit text are ignored (and counted).
     *
     * @param text delta text or null
     */
    public void append(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        int free = maxChars - sb.length();
        if (free <= 0) {
            droppedChars += text.length();
        } else if (text.length() > free) {
            sb.append(text, 0, free);
            droppedChars += text.length() - free;
        } else {
            sb.append(text);
        }
    }

    /**
     * @return true if nothing was captured
     */
    public boolean isEmpty() {
        return sb.isEmpty();
    }

    /**
     * @return the captured text, suffixed by the number of dropped characters if any
     */
    public String text() {
        if (droppedChars == 0L) {
            return sb.toString();
        }
        return sb.append(" [...").append(droppedChars).append(" more chars]").toString();
    }
}
