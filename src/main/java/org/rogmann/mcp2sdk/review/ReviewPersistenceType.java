package org.rogmann.mcp2sdk.review;

/**
 * Where the artifacts of a request live - declared by the tool, read by the review UI.
 * <p>
 * The review area does not persist anything itself (it is a page, not a store). It only needs to
 * know what it may promise a reviewer: with {@link #FILESYSTEM} the page shows the run directory
 * and the file names, with {@link #MEMORY} it must say that the request - and with it every
 * captured output - disappears on a server restart. Saying that out loud is the difference
 * between an honest limitation and a silent one.
 * </p>
 * <p>
 * Extending the enum (a database, a signed audit log) is deliberately possible: the UI branches on
 * the type, not on the tool.
 * </p>
 */
public enum ReviewPersistenceType {

    /**
     * Nothing is written by the workflow; the request and its artifacts live in memory only and
     * are lost on restart. Suitable for cheap, re-askable requests.
     */
    MEMORY,

    /**
     * The tool writes its artifacts into the run directory that
     * {@link ReviewService#createRequestEntry} created for the request. The review UI shows that
     * directory, so an approval can be reconstructed afterwards.
     */
    FILESYSTEM
}
