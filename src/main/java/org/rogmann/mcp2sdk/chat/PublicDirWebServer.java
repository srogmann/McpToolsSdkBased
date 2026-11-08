package org.rogmann.mcp2sdk.chat;

import org.rogmann.mcp2sdk.image.LocalWebServerConfig;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.function.IntSupplier;

/**
 * The web server of this application as the {@code image} module sees it: a sub directory of
 * {@code webui.public.path}, which {@link WebUiProxy#serveStaticResource} serves under
 * {@code /chat/}.
 * <p>
 * The module gets a directory of its own - {@code <webui.public.path>/gen-images} - instead of
 * the Web UI's root: generated pictures and their gallery page must not share a directory with
 * {@code index.html}, {@code app.js} and whatever else the UI ships, and a {@code images.html}
 * written next to them would be one more page of the UI by accident. The URL follows the same
 * split: {@code http://host:port/chat/gen-images/...}.
 * </p>
 * <p>
 * The port is read <em>lazily</em> ({@link IntSupplier}): {@code server.port=0} binds an
 * arbitrary port and only {@code local.server.port} knows it - and that property exists after
 * the web server has started, which is long before the first script runs.
 * </p>
 * <p>
 * {@link #isActive()} asks two cheap questions - is a port known, does the published root exist -
 * and {@link #unavailableReason()} names the one that failed. The tool only runs inside the
 * running application, so the server is up whenever a script is; whether anything can be
 * <em>served</em> depends on the published root existing (without {@code webui.public.path} the
 * Web UI falls back to the classpath resources inside the JAR, which nothing can be written to).
 * The {@code gen-images} directory itself is created on the first write.
 * </p>
 */
public class PublicDirWebServer implements LocalWebServerConfig {

    /** Sub directory of {@code webui.public.path} that belongs to the image module. */
    public static final String SUB_DIR = "gen-images";

    /** Path prefix the Web UI serves {@code webui.public.path} under. */
    private static final String PREFIX = "/chat";

    /** Scheme and host of the URLs (the application binds to the loopback interface). */
    private static final String SCHEME = "http";
    private static final String HOST = "127.0.0.1";

    private final Path root;
    private final Path dir;
    private final IntSupplier port;

    /**
     * Creates the configuration.
     * @param publicPath the published root ({@code webui.public.path}), absolute
     * @param port supplies the port the application listens on (read at request time)
     */
    public PublicDirWebServer(Path publicPath, IntSupplier port) {
        this.root = publicPath.toAbsolutePath().normalize();
        this.dir = root.resolve(SUB_DIR);
        this.port = port;
    }

    @Override
    public boolean isActive() {
        return unavailableReason() == null;
    }

    @Override
    public String unavailableReason() {
        int current = port.getAsInt();
        if (current <= 0) {
            return "no port is known (server.port is 0 or unset, and local.server.port appears"
                    + " only after the web server has started)";
        }
        if (!Files.isDirectory(root)) {
            return "the published directory '" + root + "' does not exist - set "
                    + "webui.public.path to a directory inside the project";
        }
        return null;
    }

    @Override
    public Path getWebServerDir() {
        return dir;
    }

    @Override
    public Optional<URI> uriFor(Path file) {
        if (file == null) {
            return Optional.empty();
        }
        Path absolute = file.toAbsolutePath().normalize();
        if (!absolute.startsWith(dir) || absolute.equals(dir)) {
            return Optional.empty();
        }
        String relative = dir.relativize(absolute).toString().replace('\\', '/');
        try {
            return Optional.of(new URI(SCHEME, null, HOST, port.getAsInt(),
                    PREFIX + "/" + SUB_DIR + "/" + relative, null, null));
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    /**
     * Builds the configuration of the running application, or {@code null} when no public
     * directory is configured (then the Web UI serves from the classpath and nothing can be
     * written there).
     * @param publicPath the value of {@code webui.public.path}, may be null/blank
     * @param port supplies the port the application listens on
     * @return the configuration, or null
     */
    public static PublicDirWebServer of(String publicPath, IntSupplier port) {
        if (publicPath == null || publicPath.isBlank()) {
            return null;
        }
        return new PublicDirWebServer(Paths.get(publicPath.trim()), port);
    }
}
