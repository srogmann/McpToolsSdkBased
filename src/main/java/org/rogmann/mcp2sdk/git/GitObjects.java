package org.rogmann.mcp2sdk.git;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sourcen at a revision: one blob (with its id) and one directory listing.
 *
 * <p>
 * A file at a revision is read through {@code cat-file blob}, so what a caller sees is exactly the
 * object git stored - after {@code .gitattributes} filters, with the line endings of the blob, not
 * of the working tree. The blob id comes along ({@code rev-parse --verify <rev>:<path>}) because it
 * is the identity a diff wants to print in its {@code index} line; nothing here computes a second
 * hash.
 * </p>
 *
 * <p>
 * "Binary" is decided the way git decides it (a NUL byte in the first 8000 bytes), so the module
 * and git never disagree about whether a file is text. A blob over {@code maxBytes} is reported as
 * truncated - never silently cut.
 * </p>
 */
public final class GitObjects {

    /** Number of leading bytes git itself looks at when it calls a blob binary. */
    private static final int BINARY_SNIFF_BYTES = 8_000;

    /** Sentinel size of an entry that has none (a tree or a submodule). */
    public static final long NO_SIZE = -1L;

    /** Byte order mark of UTF-8. */
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /**
     * One blob (a file at a revision).
     *
     * @param path       the path as the caller named it
     * @param rev        the revision as the caller named it
     * @param id         the blob id (40 hex characters)
     * @param data       the bytes, at most the requested maximum
     * @param size       size of the blob in bytes
     * @param truncated  true if {@code data} holds fewer bytes than the blob
     */
    public record Blob(String path, String rev, String id, byte[] data, long size,
                       boolean truncated) {

        /**
         * @return true if the content looks binary (a NUL byte in the first 8000 bytes, the
         *         heuristic git itself uses)
         */
        public boolean binary() {
            int limit = Math.min(data.length, BINARY_SNIFF_BYTES);
            for (int i = 0; i < limit; i++) {
                if (data[i] == 0) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Decodes the content as UTF-8.
         *
         * <p>
         * Binary content has no text form - a NUL byte is valid UTF-8, but a string carrying one
         * is not what a caller wants to see (and not what an editor would show). So this answers
         * {@code null} for binary content as well as for bytes that are not UTF-8; the caller then
         * works with {@link #data()}.
         * </p>
         *
         * @return the text, or {@code null} if the content is binary or not valid UTF-8
         */
        public String text() {
            if (binary()) {
                return null;
            }
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(data))
                        .toString();
            } catch (CharacterCodingException e) {
                return null;
            }
        }

        /**
         * @return number of lines as an editor shows them (0 for an empty file, a file without a
         *         final newline counts its last line)
         */
        public int lines() {
            if (data.length == 0) {
                return 0;
            }
            int count = 0;
            for (byte b : data) {
                if (b == '\n') {
                    count++;
                }
            }
            return (data[data.length - 1] == '\n') ? count : count + 1;
        }

        /**
         * @return the kind of line ending: {@code lf}, {@code crlf}, {@code cr}, {@code mixed},
         *         or {@code null} if the content has no line ending at all
         */
        public String eol() {
            boolean crlf = false;
            boolean lfOnly = false;
            boolean crOnly = false;
            for (int i = 0; i < data.length; i++) {
                if (data[i] == '\n') {
                    if (i > 0 && data[i - 1] == '\r') {
                        crlf = true;
                    } else {
                        lfOnly = true;
                    }
                } else if (data[i] == '\r') {
                    boolean followedByLf = (i + 1 < data.length) && data[i + 1] == '\n';
                    if (!followedByLf) {
                        crOnly = true;
                    }
                }
            }
            int kinds = (crlf ? 1 : 0) + (lfOnly ? 1 : 0) + (crOnly ? 1 : 0);
            if (kinds == 0) {
                return null;
            }
            if (kinds > 1) {
                return "mixed";
            }
            return crlf ? "crlf" : (crOnly ? "cr" : "lf");
        }

        /** @return true if the content ends with a line terminator */
        public boolean finalNewline() {
            return data.length > 0 && data[data.length - 1] == '\n';
        }

        /** @return true if the content starts with a UTF-8 byte order mark */
        public boolean bom() {
            if (data.length < UTF8_BOM.length) {
                return false;
            }
            for (int i = 0; i < UTF8_BOM.length; i++) {
                if (data[i] != UTF8_BOM[i]) {
                    return false;
                }
            }
            return true;
        }

        /**
         * The shape a caller sees - the same for a local blob and one read from the server, so a
         * script learns it once.
         *
         * @return {@code {path, rev, blobId, size, bytes, truncated, binary, lines, eol,
         *         finalNewline, bom, text}}
         */
        public Map<String, Object> toMap() {
            boolean isBinary = binary();
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("path", path);
            map.put("rev", rev);
            map.put("blobId", id);
            map.put("size", size);
            map.put("bytes", data.length);
            map.put("truncated", truncated);
            map.put("binary", isBinary);
            map.put("lines", isBinary ? null : lines());
            map.put("eol", eol());
            map.put("finalNewline", finalNewline());
            map.put("bom", bom());
            map.put("text", text());
            return map;
        }
    }

    /**
     * One entry of a directory listing.
     *
     * @param name the file name (last path segment)
     * @param path the path relative to the repository root
     * @param type {@code blob}, {@code tree} or {@code commit} (a submodule)
     * @param mode the mode as git prints it (e.g. {@code 100644}, {@code 040000})
     * @param size size in bytes, {@link #NO_SIZE} for a tree or submodule
     * @param id   the object id
     */
    public record TreeEntry(String name, String path, String type, String mode, long size,
                            String id) {

        /** @return true if this entry has a size (i.e. it is a blob) */
        public boolean hasSize() {
            return size != NO_SIZE;
        }
    }

    /**
     * One directory listing.
     *
     * @param entries   the entries, at most {@code max} of them
     * @param total     number of entries found
     * @param truncated true if {@code entries} holds fewer entries than {@code total}
     */
    public record Tree(List<TreeEntry> entries, int total, boolean truncated) {
    }

    private GitObjects() {
    }

    /**
     * Reads one file at a revision.
     *
     * @param repo     the repository
     * @param rev      the revision (already validated, without a ':')
     * @param path     the path relative to the repository root
     * @param maxBytes maximum number of bytes returned
     * @return the blob with its id
     * @throws IOException if the path does not exist at that revision, git fails, or the revision
     *         is unknown
     */
    public static Blob readBlob(GitRepo repo, String rev, String path, int maxBytes)
            throws IOException {
        Blob blob = tryReadBlob(repo, rev, path, maxBytes);
        if (blob == null) {
            throw new IOException("There is no file '" + path + "' at revision '" + rev
                    + "'. Check the path (it is relative to the project directory) and the "
                    + "revision, or ask git.status() what the working tree has.");
        }
        return blob;
    }

    /**
     * Reads one file at a revision, or answers {@code null} when it does not exist there - the
     * normal case on one side of an added or deleted file.
     *
     * @param repo     the repository
     * @param rev      the revision (already validated, without a ':')
     * @param path     the path relative to the repository root
     * @param maxBytes maximum number of bytes returned
     * @return the blob, or {@code null} if that path does not exist at that revision
     * @throws IOException if git fails
     */
    public static Blob tryReadBlob(GitRepo repo, String rev, String path, int maxBytes)
            throws IOException {
        String spec = rev + ":" + path;
        GitProcess.Result idResult = GitProcess.runLenient(repo.root(),
                List.of("rev-parse", "--verify", "--quiet", spec));
        String id = idResult.stdout().strip();
        if (idResult.exitCode() != 0 || id.isEmpty()) {
            return null;
        }
        GitProcess.Bytes bytes = GitProcess.runBytes(repo.root(),
                List.of("cat-file", "blob", spec), maxBytes);
        return new Blob(path, rev, id, bytes.data(), bytes.size(), bytes.truncated());
    }

    /**
     * Checks whether a revision names a commit.
     *
     * <p>
     * Used before a comparison or a history walk answers something: a revision that does not exist
     * must be an error, never "the file is not there" (a diff in which everything looks deleted) and
     * never "nothing to show" (a short history). One {@code rev-parse --verify --quiet} per call;
     * the <em>wording</em> belongs to the question, so each caller phrases its own message and this
     * method only answers yes or no.
     * </p>
     *
     * @param repo the repository
     * @param rev  the revision (already validated by {@link GitCommand#revision(String)})
     * @return true if git resolves the revision to a commit
     * @throws IOException if git fails
     */
    public static boolean isCommit(GitRepo repo, String rev) throws IOException {
        GitProcess.Result result = GitProcess.runLenient(repo.root(),
                List.of("rev-parse", "--verify", "--quiet", rev + "^{commit}"));
        return result.exitCode() == 0;
    }

    /**
     * What a path is at a revision: {@code blob}, {@code tree}, {@code commit} - or nothing.
     *
     * <p>
     * Used before a path is read as a file: a directory is a valid pathspec for git, and reading it
     * with {@code cat-file blob} produces the raw message {@code fatal: ... bad file}. Asking what
     * it is costs one process and turns that into a decision.
     * </p>
     *
     * @param repo the repository
     * @param rev  the revision (already validated)
     * @param path the path relative to the repository root
     * @return the object type as git names it, or {@code null} if the path does not exist there
     * @throws IOException if git fails
     */
    public static String kind(GitRepo repo, String rev, String path) throws IOException {
        GitProcess.Result result = GitProcess.runLenient(repo.root(),
                List.of("cat-file", "-t", rev + ":" + path));
        if (result.exitCode() != 0) {
            return null;
        }
        String kind = result.stdout().strip();
        return kind.isEmpty() ? null : kind;
    }

    /**
     * The SHA-256 of some bytes, lowercase hex - the identity of a side that git does not name
     * (the working tree).
     *
     * @param data the bytes
     * @return the digest
     */
    public static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /**
     * Lists one level of a directory at a revision.
     *
     * @param repo the repository
     * @param rev  the revision (already validated, without a ':')
     * @param path the directory relative to the repository root, or {@code null}/{@code "."} for
     *             the root itself
     * @param max  maximum number of entries returned
     * @return the listing
     * @throws IOException if git fails or the object is not a directory
     */
    public static Tree readTree(GitRepo repo, String rev, String path, int max) throws IOException {
        boolean root = (path == null || ".".equals(path));
        String spec = root ? rev : rev + ":" + path;
        GitProcess.Result result = GitProcess.runLenient(repo.root(),
                List.of("ls-tree", "-z", "--long", spec));
        if (result.exitCode() != 0) {
            throw new IOException("There is no directory '" + path + "' at revision '" + rev
                    + "' (git ls-tree said: " + lastLine(result.stderr()) + ").");
        }
        if (result.stdoutTruncated()) {
            throw new IOException("The listing of '" + (root ? "." : path) + "' at revision '" + rev
                    + "' is larger than the output budget of the module; name a sub-directory with "
                    + "'path' or raise '" + GitProcess.PROP_MAX_OUTPUT_CHARS + "'.");
        }
        String prefix = root ? "" : path + "/";
        List<TreeEntry> entries = parseTree(result.stdout(), prefix);
        int total = entries.size();
        List<TreeEntry> kept = (total > max) ? List.copyOf(entries.subList(0, max)) : entries;
        return new Tree(kept, total, kept.size() < total);
    }

    /**
     * Parses {@code ls-tree -z --long} output.
     *
     * @param output the raw output
     * @param prefix path prefix prepended to the printed names (the tree was addressed as
     *               {@code rev:path}, so git prints the names within that tree)
     * @return the entries
     * @throws IllegalArgumentException if a record has an unexpected shape
     */
    public static List<TreeEntry> parseTree(String output, String prefix) {
        List<TreeEntry> entries = new ArrayList<>();
        if (output == null) {
            return entries;
        }
        for (String record : output.split("\u0000", -1)) {
            if (record.isBlank()) {
                continue;
            }
            int tab = record.indexOf('\t');
            if (tab < 0) {
                throw new IllegalArgumentException("Unexpected ls-tree record: '" + record + "'");
            }
            String[] meta = record.substring(0, tab).strip().split("\\s+");
            if (meta.length < 3) {
                throw new IllegalArgumentException("Unexpected ls-tree record: '" + record + "'");
            }
            String mode = meta[0];
            String type = meta[1];
            String id = meta[2];
            // --long prints the size for blobs and "-" for trees and submodules.
            long size = (meta.length > 3 && !"-".equals(meta[3]))
                    ? GitParse.requireLong(meta[3], "ls-tree size") : NO_SIZE;
            String name = record.substring(tab + 1);
            // A tree addressed as <rev>:<path> is listed with the names inside it; if git already
            // printed the full path, it must not be prefixed twice.
            String full = (prefix.isEmpty() || name.startsWith(prefix)) ? name : prefix + name;
            String shortName = name.substring(name.lastIndexOf('/') + 1);
            entries.add(new TreeEntry(shortName, full, type, mode, size, id));
        }
        return entries;
    }

    /** Last non-blank line of a message (git's own {@code fatal: ...} line). */
    private static String lastLine(String message) {
        if (message == null || message.isBlank()) {
            return "unknown cause";
        }
        String[] lines = message.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].isBlank()) {
                return lines[i].strip();
            }
        }
        return "unknown cause";
    }
}
