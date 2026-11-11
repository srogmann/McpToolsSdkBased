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
 * <strong>Who does what.</strong> An implementation names a directory and maps files to URLs. It
 * does <em>not</em> relativise, check or create anything: {@link #getWebServerDir()} may return an
 * absolute path (that is the normal case), and the module translates it against the project base
 * directory, refuses a directory outside the project and refuses the project directory itself,
 * each with a clear message. The same holds for a relative path, which is resolved against the
 * project base. An implementation therefore needs no knowledge of the sandbox's path rules - and
 * must not do that work itself, or the two would disagree on where the pictures go.
 * </p>
 * <p>
 * <strong>The directory must be the module's own.</strong> The module writes {@code images/} (the
 * pictures) and {@code images.html} (the gallery) directly into it, so a directory shared with
 * other pages is wrong twice over: a {@code images.html} next to an {@code index.html} is one more
 * page of that site by accident, and a delivery controller that redirects to "the one HTML file"
 * of a directory turns two HTML files into a 404. Return a dedicated sub directory (the shipped
 * implementation uses {@code gen-images}) even when the operator releases a folder of their own.
 * </p>
 * <p>
 * <strong>Every call may fail, and may answer differently.</strong> Nothing is cached: the module
 * asks per {@code image.generate}/{@code image.edit} call (the help text and {@code status()} ask
 * too). An implementation may therefore read its configuration on every call - so a switch in the
 * application's UI takes effect without a restart - and it <em>may throw</em>
 * ({@code RuntimeException}) when, say, no folder is configured at all. The module catches that
 * and reports it as a configuration error of the {@code image} module, naming the function; a
 * foreign exception never reaches the script. {@link #getWebServerDir()} may also return
 * {@code null} for "no directory configured", which is reported the same way.
 * </p>
 * <p>
 * Registration is static ({@link JsImage#setLocalWebServer(LocalWebServerConfig)}) because a
 * {@code JsImage} instance lives for one script call only: the configuration outlives the call,
 * the instance does not. This is why the contract is an interface and not a record - an
 * implementation may answer differently per request (e.g. while the server is starting).
 * </p>
 * <p>
 * <strong>Who registers.</strong> The application registers its <em>default</em> server with
 * {@link JsImage#setLocalWebServerIfAbsent(LocalWebServerConfig)}: an embedding application that
 * brings a server of its own (a desktop UI with a switch for the published folder, say) registers
 * it <em>before</em> the application's configuration runs and keeps it - the default then steps
 * back instead of overruling the operator. {@link JsImage#setLocalWebServer(LocalWebServerConfig)}
 * is the explicit override (and {@code null} switches the channel off). Both are read once per
 * call in the module.
 * </p>
 */
public interface LocalWebServerConfig {

    /**
     * Whether the server is running and would answer a request for a file of
     * {@link #getWebServerDir()} right now.
     * <p>
     * May throw ({@code RuntimeException}); the module catches it and reports it as a
     * configuration error.
     * </p>
     * @return true when a URL from {@link #uriFor(Path)} would be reachable
     */
    boolean isActive();

    /**
     * The directory the server publishes - the module writes {@code <dir>/images/…} and
     * {@code <dir>/images.html} into it.
     * <p>
     * The directory has to be a directory of the module's own (see the type comment) and has to
     * lie inside the project directory: the module writes with the {@code fs} rules, so anything
     * else is an error at use time. Absolute is the normal case; a relative path is resolved
     * against the project base directory. The module relativises and checks - the implementation
     * does not have to. {@code null} means "no directory configured" and is reported as a
     * configuration error.
     * </p>
     * <p>
     * May throw ({@code RuntimeException}), e.g. when the configuration is read per call and no
     * folder is configured; the module catches it and reports it as a configuration error.
     * </p>
     * @return the published directory (absolute, normalized), or {@code null}
     */
    Path getWebServerDir();

    /**
     * Why this server would not answer a URL for a file of {@link #getWebServerDir()}, in one
     * sentence - {@code null} when it would.
     * <p>
     * The module puts this into its {@code hint} when a picture was written but has no URL:
     * "the server is not active" alone would leave the operator guessing which reason applies
     * (no published directory, no known port). The text is shown to the calling LLM, so it should
     * not carry absolute paths - the module strips its own project base prefix, a path outside the
     * project is the implementation's business.
     * </p>
     * <p>
     * May throw ({@code RuntimeException}); the module catches it and reports it as a
     * configuration error.
     * </p>
     * @return the reason, or {@code null} when a URL from {@link #uriFor(Path)} is reachable
     */
    default String unavailableReason() {
        return null;
    }

    /**
     * Maps a file of {@link #getWebServerDir()} to the URL that serves it.
     * <p>
     * An empty result means "this server does not serve that file" - the paths do not match, the
     * implementation's directory and the URL layout disagree. The module then reports the mismatch
     * instead of inventing a URL, and names the file it has already written: the picture and the
     * gallery are on disk at that point, the configuration is still wrong.
     * </p>
     * <p>
     * May throw ({@code RuntimeException}); the module catches it and reports it as a
     * configuration error. Returning {@code null} is treated like an empty result.
     * </p>
     * @param file an absolute file inside {@link #getWebServerDir()}
     * @return the URL, or empty when this server does not serve that file
     */
    Optional<URI> uriFor(Path file);
}
