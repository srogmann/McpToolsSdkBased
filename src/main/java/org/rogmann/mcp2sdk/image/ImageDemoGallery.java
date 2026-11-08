package org.rogmann.mcp2sdk.image;

import org.rogmann.mcp2sdk.js.JsUserRuntimeException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The demo gallery of the {@code image} module: the generated pictures land in
 * {@code <dir>/images/<stamp>-<slug>.<ext>} and {@code <dir>/images.html} is rewritten after
 * every write - newest entry first.
 * <p>
 * The gallery only ever touches {@code <dir>/images/} and the one file {@code <dir>/images.html}
 * (a clear removes the files of {@code images/}, nothing else). The page is written to a
 * temporary file and then moved into place, so a browser never sees a half-written gallery while
 * a script is running.
 * </p>
 */
final class ImageDemoGallery {

    /** Sub directory of the published directory that holds the generated pictures. */
    static final String IMAGE_DIR = "images";

    /** File name of the gallery page inside the published directory. */
    static final String GALLERY_FILE = "images.html";

    /** Entries the gallery lists (the files stay, an old entry is simply not linked any more). */
    private static final int MAX_ENTRIES = 500;

    /** Extensions the gallery links (a generated {@code .webp} is published, just not read). */
    private static final Set<String> EXTENSIONS =
            Set.of("png", "jpg", "jpeg", "gif", "bmp", "webp");

    /** Name stamp of a generated file: sortable, so "newest first" is a string comparison. */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss",
            Locale.ROOT);

    private ImageDemoGallery() {
        // static helpers only
    }

    /**
     * Writes one generated picture and rewrites the gallery.
     * @param demoDir the published directory
     * @param prompt the prompt the file name is derived from
     * @param ext file extension without the dot ({@code png}, {@code jpeg}, …)
     * @param bytes the picture
     * @return the written file
     */
    static Path addImage(Path demoDir, String prompt, String ext, byte[] bytes) {
        Path imageDir = demoDir.resolve(IMAGE_DIR);
        String stamp = LocalDateTime.now().format(STAMP);
        String base = stamp + "-" + slug(prompt);
        String extension = ext == null || ext.isBlank() ? "png" : ext.toLowerCase(Locale.ROOT);
        try {
            Files.createDirectories(imageDir);
            Path file = imageDir.resolve(base + "." + extension);
            for (int i = 2; Files.exists(file) && i < 100; i++) {
                file = imageDir.resolve(base + "-" + i + "." + extension);
            }
            Files.write(file, bytes);
            rewriteGallery(demoDir);
            return file;
        } catch (IOException e) {
            throw new JsUserRuntimeException("image: writing the demo picture into '"
                    + imageDir + "' failed: " + e.getMessage(), e);
        }
    }

    /**
     * Removes the pictures of the gallery directory ({@code demoClear: true}).
     * @param demoDir the published directory
     * @return how many files were removed
     */
    static int clear(Path demoDir) {
        Path imageDir = demoDir.resolve(IMAGE_DIR);
        if (!Files.isDirectory(imageDir)) {
            return 0;
        }
        int removed = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(imageDir)) {
            for (Path file : stream) {
                if (Files.isRegularFile(file) && Files.deleteIfExists(file)) {
                    removed++;
                }
            }
        } catch (IOException e) {
            throw new JsUserRuntimeException("image: clearing the demo directory '" + imageDir
                    + "' failed: " + e.getMessage(), e);
        }
        return removed;
    }

    /**
     * The gallery page of a published directory.
     * @param demoDir the published directory
     * @return the page file
     */
    static Path galleryFile(Path demoDir) {
        return demoDir.resolve(GALLERY_FILE);
    }

    /**
     * Rewrites {@code <dir>/images.html} from the files currently in {@code <dir>/images/},
     * newest first, atomically (write, then move).
     * @param demoDir the published directory
     */
    static void rewriteGallery(Path demoDir) {
        List<String> names = new ArrayList<>();
        Path imageDir = demoDir.resolve(IMAGE_DIR);
        if (Files.isDirectory(imageDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(imageDir)) {
                for (Path file : stream) {
                    String name = file.getFileName().toString();
                    int dot = name.lastIndexOf('.');
                    if (Files.isRegularFile(file) && dot > 0
                            && EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT))) {
                        names.add(name);
                    }
                }
            } catch (IOException e) {
                throw new JsUserRuntimeException("image: reading the demo directory '" + imageDir
                        + "' failed: " + e.getMessage(), e);
            }
        }
        // The name starts with yyyyMMdd-HHmmss, so a reverse string sort is "newest first" -
        // no file times involved (they lie after a copy and differ per file system).
        names.sort(Comparator.reverseOrder());
        int listed = Math.min(names.size(), MAX_ENTRIES);
        StringBuilder html = new StringBuilder(4096);
        html.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<title>image gallery</title>\n<style>\n")
                .append("body{font-family:system-ui,sans-serif;margin:1.5rem;background:#fafafa;")
                .append("color:#222}h1{font-size:1.2rem}figure{margin:0 0 1.5rem}")
                .append("img{max-width:100%;border:1px solid #ccc;background:#fff}")
                .append("figcaption{font-family:ui-monospace,monospace;font-size:.8rem;")
                .append("color:#555;word-break:break-all}small{color:#777}\n")
                .append("</style>\n</head>\n<body>\n<h1>Generated images</h1>\n")
                .append("<p><small>newest first - ").append(names.size()).append(" file(s)")
                .append(listed < names.size() ? ", " + listed + " listed" : "")
                .append("</small></p>\n");
        for (String name : names.subList(0, listed)) {
            String href = escape(IMAGE_DIR + "/" + name);
            html.append("<figure><a href=\"").append(href).append("\"><img src=\"").append(href)
                    .append("\" alt=\"").append(escape(name)).append("\" loading=\"lazy\"></a>")
                    .append("<figcaption>").append(escape(name)).append("</figcaption></figure>\n");
        }
        if (names.isEmpty()) {
            html.append("<p><small>no pictures yet</small></p>\n");
        }
        html.append("</body>\n</html>\n");
        writeAtomically(galleryFile(demoDir), html.toString());
    }

    /** Writes a page through a temporary file, so a running server never delivers half of it. */
    private static void writeAtomically(Path target, String content) {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new JsUserRuntimeException("image: writing the gallery '" + target
                    + "' failed: " + e.getMessage(), e);
        }
    }

    /**
     * A file-name slug of the prompt: lower case, runs of anything that is not a letter or a
     * digit collapsed into one dash, at most 40 characters.
     * @param prompt the prompt, may be null
     * @return the slug, never empty
     */
    static String slug(String prompt) {
        if (prompt == null) {
            return "image";
        }
        StringBuilder sb = new StringBuilder();
        boolean dash = false;
        for (int i = 0; i < prompt.length() && sb.length() < 40; i++) {
            char c = Character.toLowerCase(prompt.charAt(i));
            if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9') {
                sb.append(c);
                dash = false;
            } else if (!dash && sb.length() > 0) {
                sb.append('-');
                dash = true;
            }
        }
        String slug = sb.toString();
        while (slug.endsWith("-")) {
            slug = slug.substring(0, slug.length() - 1);
        }
        return slug.isEmpty() ? "image" : slug;
    }

    /** Escapes the five characters that would break out of the page's HTML. */
    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
