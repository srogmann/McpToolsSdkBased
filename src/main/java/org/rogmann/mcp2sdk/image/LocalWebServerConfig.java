package org.rogmann.mcp2sdk.image;

import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The web server the {@code image} module may publish generated images to - the seam that keeps
 * the module free of Spring, of the chat UI's URL layout and of any knowledge about ports or
 * context paths.
 * <p>
 * The module owns the <em>content</em> (a directory {@code <dir>/images/…} plus the gallery
 * {@code <dir>/images.html}); the implementation owns the <em>mapping</em> from a file to the
 * URL a browser can open. That split is deliberate: a module that builds URLs itself would have
 * to guess the context path, the port and the encoding - all things only the server knows.
 * </p>
 * <p>
 * Registration is static ({@link JsImage#setLocalWebServer(LocalWebServerConfig)}) because a
 * {@code JsImage} instance lives for one script call only: the configuration outlives the call,
 * the instance does not. This is why the contract is an interface and not a record - an
 * implementation may answer differently per request (e.g. while the server is starting).
 * </p>
 */
public interface LocalWebServerConfig {

    /**
     * Whether the server is running and would answer a request for a file of
     * {@link #getWebServerDir()} right now.
     * @return true when a URL from {@link #uriFor(Path)} would be reachable
     */
    boolean isActive();

    /**
     * The directory the server publishes - the module writes {@code <dir>/images/…} and
     * {@code <dir>/images.html} into it. Must lie inside the project directory, otherwise the
     * {@code fs} rules would not let the module write there (the module reports that as a
     * configuration error at use time).
     * @return the published directory (absolute, normalized)
     */
    Path getWebServerDir();

    /**
     * Why this server would not answer a URL for a file of {@link #getWebServerDir()}, in one
     * sentence - {@code null} when it would.
     * <p>
     * The module puts this into its {@code hint} when a picture was written but has no URL:
     * "the server is not active" alone would leave the operator guessing which reason applies
     * (no published directory, no known port).
     * </p>
     * @return the reason, or {@code null} when a URL from {@link #uriFor(Path)} is reachable
     */
    default String unavailableReason() {
        return null;
    }

    /**
     * Maps a file of {@link #getWebServerDir()} to the URL that serves it.
     * @param file an absolute file inside {@link #getWebServerDir()}
     * @return the URL, or empty when this server does not serve that file (the paths do not
     *         match) - the module then reports the mismatch instead of inventing a URL
     */
    Optional<URI> uriFor(Path file);
}
