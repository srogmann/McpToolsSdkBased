package org.rogmann.mcp2sdk.js;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hashing (MD5, SHA-1, SHA-256) for byte arrays and files.
 * <p>
 * Byte arrays are hashed in memory; files are hashed by streaming through an
 * {@link InputStream} so that arbitrarily large files cActivatedNeu an be processed with
 * constant memory. All file access uses the same security checks as
 * {@link JsFileSystem} (project base directory from system property
 * {@code IDE_PROJECT_DIR}).
 * </p>
 *
 * <h3>Security</h3>
 * <ul>
 *   <li>Paths are resolved relative to the project base directory.</li>
 *   <li>Parent-directory traversal ({@code ..}) and absolute paths outside the base are rejected.</li>
 *   <li>Symbolic links are only followed if the real path stays inside the base directory.</li>
 *   <li>Results and error messages contain only relative paths, never absolute paths.</li>
 * </ul>
 */
public class JsCrypto {

    private static final Logger LOG = LoggerFactory.getLogger(JsCrypto.class);

    /** Streaming buffer size when hashing files. */
    private static final int STREAM_BUFFER_SIZE = 64 * 1024;

    /** Lowercase hex digits. */
    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    private JsCrypto() {
        // Utility class
    }

    /**
     * Computes the MD5 hash of a byte array.
     * @param data bytes to hash
     * @return lowercase hex string
     * @throws IllegalArgumentException if data is null
     */
    public static String md5(byte[] data) {
        return digest("MD5", data);
    }

    /**
     * Computes the MD5 hash of a file (streamed, constant memory).
     * @param filePath path relative to the base directory
     * @return lowercase hex string
     * @throws JsUserRuntimeException if the file does not exist or an I/O error occurs
     */
    public static String md5(String filePath) {
        return digestFile("MD5", filePath);
    }

    /**
     * Computes the SHA-1 hash of a byte array.
     * @param data bytes to hash
     * @return lowercase hex string
     * @throws IllegalArgumentException if data is null
     */
    public static String sha1(byte[] data) {
        return digest("SHA-1", data);
    }

    /**
     * Computes the SHA-1 hash of a file (streamed, constant memory).
     * @param filePath path relative to the base directory
     * @return lowercase hex string
     * @throws JsUserRuntimeException if the file does not exist or an I/O error occurs
     */
    public static String sha1(String filePath) {
        return digestFile("SHA-1", filePath);
    }

    /**
     * Computes the SHA-256 hash of a byte array.
     * @param data bytes to hash
     * @return lowercase hex string
     * @throws IllegalArgumentException if data is null
     */
    public static String sha256(byte[] data) {
        return digest("SHA-256", data);
    }

    /**
     * Computes the SHA-256 hash of a file (streamed, constant memory).
     * @param filePath path relative to the base directory
     * @return lowercase hex string
     * @throws JsUserRuntimeException if the file does not exist or an I/O error occurs
     */
    public static String sha256(String filePath) {
        return digestFile("SHA-256", filePath);
    }

    /**
     * Hashes a byte array.
     */
    private static String digest(String algorithm, byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            return toHex(md.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Algorithm not available: " + algorithm, e);
        }
    }

    /**
     * Hashes a file by streaming through an InputStream (constant memory).
     */
    private static String digestFile(String algorithm, String filePath) {
        Path path = JsFileSystem.resolveSafePath(filePath);
        if (!Files.isRegularFile(path)) {
            throw new JsUserRuntimeException("File not found: " + JsFileSystem.toRelative(path));
        }
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            try (InputStream in = Files.newInputStream(path)) {
                byte[] buffer = new byte[STREAM_BUFFER_SIZE];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    md.update(buffer, 0, n);
                }
            }
            return toHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Algorithm not available: " + algorithm, e);
        } catch (IOException e) {
            LOG.error("Failed to hash file: " + path, e);
            throw new JsUserRuntimeException("Failed to hash file: " + JsFileSystem.toRelative(path), e);
        }
    }

    /**
     * Formats bytes as a lowercase hex string.
     * @param data bytes
     * @return hex string (empty for an empty array)
     */
    private static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            int v = b & 0xFF;
            sb.append(HEX_DIGITS[v >>> 4]).append(HEX_DIGITS[v & 0x0F]);
        }
        return sb.toString();
    }

    /**
     * Returns a help text describing the JS crypto API with usage examples.
     * @return help text as a multi-line string
     */
    public static String help() {
        return """
                JS Crypto API (namespace 'crypto')
                ==================================

                Hashing of byte arrays or files. A string argument is treated as a file path
                relative to the project base directory; the file is hashed by streaming
                (constant memory, no upper size limit). A byte array (Uint8Array or array of
                numbers 0-255) is hashed in memory. The return value is always a lowercase
                hex string. Security is identical to fs.* (no '..', no absolute paths outside
                the base, no symbolic links leaving the base).

                crypto.md5(pathOrData)     - MD5 hash as lowercase hex string.
                crypto.sha1(pathOrData)    - SHA-1 hash as lowercase hex string.
                crypto.sha256(pathOrData)  - SHA-256 hash as lowercase hex string.

                Examples:
                    crypto.sha256("out.bin")                  // hash a file (streamed)
                    crypto.sha256(new Uint8Array([1, 2, 3]))  // hash a byte array
                    crypto.md5("out.bin")

                --- Help ---
                crypto.help()              - This help text.
                """;
    }
}
