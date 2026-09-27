package org.rogmann.mcp2sdk.review;

/**
 * What the human clicked in the review UI.
 * <p>
 * Deliberately small: approve or deny, plus an optional comment. There is no "approve with
 * modifications" and no partial approval - the approval binds to the content that was shown
 * ({@link AttributeKind#HASH}, {@link AttributeKind#CODE}), so "yes, but&hellip;" would mean
 * approving something the reviewer never saw. A reviewer who wants a change denies with a comment;
 * the tool then asks again with the change in place.
 * </p>
 *
 * @param id       id of the request the decision belongs to
 * @param approved true to approve, false to deny
 * @param comment  reviewer comment (shown to the model, especially on a denial), may be empty
 * @param decidedAt ISO-8601 timestamp of the decision
 */
public record ReviewDecision(String id, boolean approved, String comment, String decidedAt) {

    /**
     * Compact constructor; normalizes the text parts.
     */
    public ReviewDecision {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("A review decision needs the request id.");
        }
        id = id.trim();
        comment = ReviewAttributes.sanitize(comment, ReviewAttributes.MAX_REASON_CHARS);
        decidedAt = (decidedAt == null) ? "" : decidedAt.trim();
    }
}
