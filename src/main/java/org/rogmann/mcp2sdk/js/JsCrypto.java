package org.rogmann.mcp2sdk.js;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Core cryptographic functions operating on byte arrays: hashing (MD5, SHA-1, SHA-256), HMAC,
 * AES-CBC/CTR convenience operations, the raw AES/ECB block engine and deflate/inflate.
 * <p>
 * Hashing works on byte arrays in memory or on files by streaming through an
 * {@link InputStream} (constant memory). All file access uses the same security checks as
 * {@link JsFileSystem} (project base directory from system property {@code IDE_PROJECT_DIR}).
 * </p>
 *
 * <h3>Security</h3>
 * <ul>
 *   <li>Paths are resolved relative to the project base directory.</li>
 *   <li>Parent-directory traversal ({@code ..}) and absolute paths outside the base are rejected.</li>
 *   <li>Symbolic links are only followed if the real path stays inside the base directory.</li>
 *   <li>Results and error messages contain only relative paths, never absolute paths.</li>
 *   <li>All operations run purely in memory on caller-supplied byte arrays.</li>
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

    // ------------------------------------------------------------------
    // Hashing
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // Raw AES block engine
    // ------------------------------------------------------------------

    /**
     * Encrypts a single 16-byte block with AES-ECB (raw bytes, big-endian standard
     * convention, no padding). Supports AES-128/192/256 keys (16/24/32 bytes).
     *
     * @param key raw key bytes (16, 24 or 32)
     * @param block 16 bytes to encrypt
     * @return 16 encrypted bytes
     */
    public static byte[] aesBlockEncrypt(byte[] key, byte[] block) {
        checkKey(key);
        if (block == null || block.length != 16) {
            throw new IllegalArgumentException("block must be exactly 16 bytes (got "
                    + (block == null ? "null" : block.length) + ")");
        }
        try {
            SecretKeySpec ks = new SecretKeySpec(key, "AES");
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, ks);
            return cipher.doFinal(block);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES not available", e);
        }
    }

    private static void checkKey(byte[] key) {
        if (key == null || (key.length != 16 && key.length != 24 && key.length != 32)) {
            throw new IllegalArgumentException("key must be 16, 24 or 32 bytes (got "
                    + (key == null ? "null" : key.length) + ")");
        }
    }

    // ------------------------------------------------------------------
    // Convenience block ciphers (normal use: key + IV + data)
    // ------------------------------------------------------------------

    /**
     * AES-CBC encryption with PKCS#5/#7 padding ("standard AES" for normal use).
     * Uses a 16-byte IV and returns padded ciphertext (empty input yields a full extra
     * padding block, so the result is always a non-empty multiple of 16 bytes).
     * @param key raw key bytes (16, 24 or 32)
     * @param iv 16-byte IV (must be unique per key usage)
     * @param data plaintext (may be empty)
     * @return padded ciphertext
     */
    public static byte[] aesCbcEncrypt(byte[] key, byte[] iv, byte[] data) {
        checkKey(key);
        require16(iv, "iv");
        if (data == null) {
            data = new byte[0];
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-CBC not available", e);
        }
    }

    /**
     * AES-CBC decryption with PKCS#5/#7 padding removal.
     * @param key raw key bytes (16, 24 or 32)
     * @param iv 16-byte IV used for encryption
     * @param ct ciphertext (non-empty multiple of 16 bytes)
     * @return unpadded plaintext
     * @throws IllegalArgumentException if the ciphertext length is invalid or the padding
     *         / authentication of the last block is invalid
     */
    public static byte[] aesCbcDecrypt(byte[] key, byte[] iv, byte[] ct) {
        checkKey(key);
        require16(iv, "iv");
        if (ct == null || ct.length == 0 || (ct.length & 15) != 0) {
            throw new IllegalArgumentException("ciphertext must be a non-empty multiple of 16 bytes (got "
                    + (ct == null ? "null" : ct.length) + ")");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(ct);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("AES-CBC decryption failed (wrong key/IV or corrupted "
                    + "ciphertext/padding)", e);
        }
    }

    /**
     * AES-CTR encryption (counter mode, standard big-endian counter starting at the given
     * IV counter block). In CTR mode encryption and decryption are identical, so the same
     * function serves both directions; it is a stream cipher so a non-multiple-of-16
     * {@code data} length is fine.
     * @param key raw key bytes (16, 24 or 32)
     * @param iv 16-byte initial counter block (must be unique per key usage)
     * @param data plaintext or ciphertext bytes
     * @return the XORed output bytes (ciphertext when encrypting, plaintext when decrypting)
     */
    public static byte[] aesCtrXor(byte[] key, byte[] iv, byte[] data) {
        checkKey(key);
        require16(iv, "iv");
        if (data == null) {
            data = new byte[0];
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-CTR not available", e);
        }
    }

    private static void require16(byte[] b, String name) {
        if (b == null || b.length != 16) {
            throw new IllegalArgumentException(name + " must be exactly 16 bytes (got "
                    + (b == null ? "null" : b.length) + ")");
        }
    }

    // ------------------------------------------------------------------
    // HMAC
    // ------------------------------------------------------------------

    /**
     * Computes HMAC-MD5, HMAC-SHA-1 or HMAC-SHA-256 (RFC 2104; block-size handling is
     * done by the JDK's {@link Mac} implementation, so the 64-byte block size of
     * MD5/SHA-1/SHA-256 and the 128-byte block size of SHA-384/512 are covered).
     * @param algorithm "md5", "sha1" or "sha256"
     * @param key HMAC key
     * @param data message bytes
     * @return raw MAC bytes
     * @throws IllegalArgumentException on null inputs or an unknown algorithm name
     */
    public static byte[] hmac(String algorithm, byte[] key, byte[] data) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        String jceAlg = switch (algorithm == null ? "" : algorithm.toLowerCase(Locale.ROOT)) {
            case "md5" -> "HmacMD5";
            case "sha1" -> "HmacSHA1";
            case "sha256" -> "HmacSHA256";
            default -> throw new IllegalArgumentException("Unsupported HMAC algorithm '"
                    + algorithm + "' (supported: md5, sha1, sha256)");
        };
        try {
            Mac mac = Mac.getInstance(jceAlg);
            mac.init(new SecretKeySpec(key, jceAlg));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC not available: " + jceAlg, e);
        }
    }

    // ------------------------------------------------------------------
    // Hex helpers
    // ------------------------------------------------------------------

    /**
     * Formats bytes as a lowercase hex string.
     * @param data bytes
     * @return hex string (empty for an empty array)
     */
    public static String toHex(byte[] data) {
        if (data == null) {
            return "";
        }
        return toHex(data, 0, data.length);
    }

    /**
     * Formats a sub-range of bytes as a lowercase hex string.
     */
    private static String toHex(byte[] data, int off, int len) {
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = off; i < off + len; i++) {
            int v = data[i] & 0xFF;
            sb.append(HEX_DIGITS[v >>> 4]).append(HEX_DIGITS[v & 0x0F]);
        }
        return sb.toString();
    }

    /**
     * Parses a hex string (paired digits, may contain whitespace) into bytes.
     * @param hex hex string
     * @return bytes
     * @throws IllegalArgumentException on invalid hex or odd length
     */
    public static byte[] hexToBytes(String hex) {
        if (hex == null) {
            return new byte[0];
        }
        String cleaned = hex.replaceAll("\\s+", "");
        if ((cleaned.length() & 1) != 0) {
            throw new IllegalArgumentException("hex string must have even length");
        }
        byte[] out = new byte[cleaned.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(cleaned.charAt(2 * i), 16);
            int lo = Character.digit(cleaned.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("invalid hex digit in \"" + hex + "\"");
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Compression primitives (deflate / inflate)
    // ------------------------------------------------------------------

    /** Upper bound for the decompressed size of one call (guard against decompression bombs). */
    public static final long MAX_INFLATED_BYTES = 256L * 1024 * 1024;

    /** zlib wrapper (RFC 1950) - the flavour PDF calls {@code /FlateDecode}. */
    public static final String FORMAT_ZLIB = "zlib";

    /** Raw deflate (RFC 1951) without any wrapper - what ZIP uses. */
    public static final String FORMAT_RAW = "raw";

    /** gzip container (RFC 1952). */
    public static final String FORMAT_GZIP = "gzip";

    /** Try zlib, then raw deflate, then gzip. */
    public static final String FORMAT_AUTO = "auto";

    /** gzip magic bytes. */
    private static final int GZIP_MAGIC_0 = 0x1F;
    private static final int GZIP_MAGIC_1 = 0x8B;

    /**
     * Decompresses deflate data.
     * <p>
     * This is the primitive behind {@code pdf.stream()} for {@code /FlateDecode} streams and
     * the tool of choice when a file format carries a deflate stream inside its own container
     * (PNG IDAT chunks, {@code .docx}/{@code .xlsx} internals, PCAP payloads, ...). For ZIP
     * and tar entries use the {@code archive} module, which already knows the containers.
     * </p>
     * @param data compressed bytes
     * @param format {@code "zlib"} (default, the PDF/FlateDecode flavour), {@code "raw"}
     *               (deflate without wrapper, aliases {@code "deflate"}, {@code "nowrap"}),
     *               {@code "gzip"} (aliases {@code "gz"}), or {@code "auto"} which tries
     *               zlib, then raw deflate, then gzip
     * @return the decompressed bytes
     * @throws JsUserRuntimeException for unusable input or a size above
     *         {@link #MAX_INFLATED_BYTES}
     */
    public static byte[] inflate(byte[] data, String format) {
        if (data == null) {
            throw new IllegalArgumentException("Usage: crypto.inflate(data[, format]) - data"
                    + " must be a Uint8Array or an array of numbers 0-255");
        }
        String requested = normalizeFormat(format, FORMAT_ZLIB);
        if (!FORMAT_AUTO.equals(requested)) {
            try {
                return inflateWithFormat(data, requested);
            } catch (IOException | RuntimeException e) {
                throw new JsUserRuntimeException("Cannot inflate the data as " + requested + ": "
                        + reasonOf(e), e);
            }
        }
        IOException lastError = null;
        for (String candidate : new String[] {FORMAT_ZLIB, FORMAT_RAW, FORMAT_GZIP}) {
            try {
                return inflateWithFormat(data, candidate);
            } catch (IOException e) {
                lastError = e;
            }
        }
        throw new JsUserRuntimeException("Cannot inflate the data as zlib, raw deflate or gzip ("
                + reasonOf(lastError) + "). If you know the container, name it explicitly:"
                + " crypto.inflate(data, 'gzip')", lastError);
    }

    /**
     * Decompresses deflate data (zlib wrapper first, raw deflate as fallback).
     * @param data compressed bytes
     * @return the decompressed bytes
     */
    public static byte[] inflate(byte[] data) {
        return inflate(data, FORMAT_AUTO);
    }

    /**
     * Compresses data with deflate.
     * @param data plain bytes
     * @param format {@code "zlib"} (default), {@code "raw"} or {@code "gzip"}
     * @param level compression level -1 (default), 0 (store) .. 9
     * @return compressed bytes
     */
    public static byte[] deflate(byte[] data, String format, int level) {
        if (data == null) {
            throw new IllegalArgumentException("Usage: crypto.deflate(data[, format, level])");
        }
        if (level < -1 || level > 9) {
            throw new IllegalArgumentException("deflate level must be -1 (default) or 0..9,"
                    + " but is " + level);
        }
        String requested = normalizeFormat(format, FORMAT_ZLIB);
        if (FORMAT_AUTO.equals(requested)) {
            throw new IllegalArgumentException("deflate needs a concrete format ('zlib', 'raw'"
                    + " or 'gzip'), not 'auto'");
        }
        int effectiveLevel = level;
        try {
            byte[] rawDeflate;
            // Only the zlib flavour carries the two wrapper bytes inside the deflate stream;
            // gzip holds plain deflate and gets its container added below.
            boolean nowrap = !FORMAT_ZLIB.equals(requested);
            Deflater deflater = new Deflater(effectiveLevel, nowrap);
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(32, data.length / 2));
                 DeflaterOutputStream out = new DeflaterOutputStream(
                    baos, deflater, 8192)) {
                out.write(data);
                out.finish();

                rawDeflate = baos.toByteArray();
            } finally {
                deflater.end();
            }
            if (FORMAT_GZIP.equals(requested)) {
                return wrapGzip(data, rawDeflate);
            }
            return rawDeflate;
        } catch (IOException e) {
            throw new JsUserRuntimeException("Cannot deflate the data as " + requested + ": "
                    + reasonOf(e), e);
        }
    }

    /**
     * Compresses data with deflate (zlib wrapper, default level).
     * @param data plain bytes
     * @return compressed bytes
     */
    public static byte[] deflate(byte[] data) {
        return deflate(data, FORMAT_ZLIB, -1);
    }

    /**
     * Inflates with one concrete format.
     * @param data compressed bytes
     * @param format one of {@link #FORMAT_ZLIB}, {@link #FORMAT_RAW}, {@link #FORMAT_GZIP}
     * @return the decompressed bytes
     * @throws IOException for corrupt input
     */
    private static byte[] inflateWithFormat(byte[] data, String format) throws IOException {
        if (FORMAT_GZIP.equals(format)) {
            if (data.length < 10 || (data[0] & 0xFF) != GZIP_MAGIC_0
                    || (data[1] & 0xFF) != GZIP_MAGIC_1) {
                throw new IOException("missing gzip magic 1f 8b");
            }
            try (InputStream in = new java.util.zip.GZIPInputStream(
                    new ByteArrayInputStream(data), 8192)) {
                return readLimitedBytes(in);
            }
        }
        Inflater inflater = new Inflater(FORMAT_RAW.equals(format));
        try (InputStream in = new InflaterInputStream(
                new ByteArrayInputStream(data), inflater, 8192)) {
            return readLimitedBytes(in);
        } finally {
            inflater.end();
        }
    }

    /**
     * Builds a gzip container around raw deflate data (the JDK's GZIPOutputStream cannot take
     * a compression level, and the container is ten header bytes plus CRC32 and size).
     */
    private static byte[] wrapGzip(byte[] plain, byte[] rawDeflate) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(plain, 0, plain.length);
        long checksum = crc.getValue();
        long size = plain.length & 0xFFFFFFFFL;
        ByteArrayOutputStream out = new ByteArrayOutputStream(rawDeflate.length + 18);
        out.write(GZIP_MAGIC_0);
        out.write(GZIP_MAGIC_1);
        out.write(8);           // CM = deflate
        out.write(0);           // FLG: no name, no comment, no CRC16
        out.write(0);           // MTIME = 0 (reproducible output)
        out.write(0);
        out.write(0);
        out.write(0);
        out.write(0);
        out.write(0xFF);        // OS = unknown
        out.write(rawDeflate, 0, rawDeflate.length);
        out.write((int) (checksum & 0xFF));
        out.write((int) ((checksum >>> 8) & 0xFF));
        out.write((int) ((checksum >>> 16) & 0xFF));
        out.write((int) ((checksum >>> 24) & 0xFF));
        out.write((int) (size & 0xFF));
        out.write((int) ((size >>> 8) & 0xFF));
        out.write((int) ((size >>> 16) & 0xFF));
        out.write((int) ((size >>> 24) & 0xFF));
        return out.toByteArray();
    }

    /**
     * Reads a stream up to {@link #MAX_INFLATED_BYTES}.
     */
    private static byte[] readLimitedBytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) > 0) {
            total += read;
            if (total > MAX_INFLATED_BYTES) {
                throw new JsUserRuntimeException("Decompressed data would exceed "
                        + MAX_INFLATED_BYTES + " bytes (decompression-bomb guard)");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /**
     * Normalises a format name (case-insensitive, with the usual aliases).
     */
    private static String normalizeFormat(String format, String fallback) {
        if (format == null || format.isBlank()) {
            return fallback;
        }
        String name = format.trim().toLowerCase(Locale.ROOT);
        switch (name) {
            case "zlib", "rfc1950", "deflate-zlib" -> {
                return FORMAT_ZLIB;
            }
            case "raw", "nowrap", "deflate", "rfc1951" -> {
                return FORMAT_RAW;
            }
            case "gzip", "gz", "rfc1952" -> {
                return FORMAT_GZIP;
            }
            case "auto", "" -> {
                return FORMAT_AUTO;
            }
            default -> throw new IllegalArgumentException("Unknown compression format '" + format
                    + "'. Use 'zlib' (PDF /FlateDecode), 'raw' (deflate without wrapper),"
                    + " 'gzip' or 'auto'.");
        }
    }

    private static String reasonOf(Throwable e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return message;
    }

    // ------------------------------------------------------------------
    // Help
    // ------------------------------------------------------------------

    /**
     * Returns the sections of the core help text: hashing, HMAC, AES-CBC/CTR and
     * deflate/inflate. {@link JsCryptoExt#help()} puts them in front of its own sections, so
     * the namespace ships as one single help document.
     * @return help text as a multi-line string
     */
    public static String helpSections() {
        return """
                JS Crypto API (namespace 'crypto')
                ==================================

                1) Hashing of byte arrays or files.
                   A string argument is treated as a file path relative to the project base
                   directory (streamed, constant memory). A byte array (Uint8Array or array of
                   numbers 0-255) is hashed in memory. Return value is a lowercase hex string.
                   crypto.md5(pathOrData) / crypto.sha1(pathOrData) / crypto.sha256(pathOrData)

                2) AES-CBC and AES-CTR (normal use: key + IV + data, standard JCA
                   transformations; the IV is 16 bytes):
                   crypto.aesCbcEncrypt(key, iv16, data) -> padded ciphertext (PKCS#7)
                   crypto.aesCbcDecrypt(key, iv16, ct)   -> unpadded plaintext
                   crypto.aesCtrXor(key, iv16, data)     -> XOR-stream result (CTR; encrypt=decrypt)

                3) HMAC (RFC 2104, block-size handling by the JDK):
                   crypto.hmac('md5'|'sha1'|'sha256', key, data) -> raw MAC bytes (Uint8Array)

                4) Compression primitives (deflate / inflate) - for compressed payloads that
                   are not a ZIP/tar entry (PNG IDAT, PDF /FlateDecode, network payloads).
                   crypto.inflate(data[, format]) -> Uint8Array
                       format: 'auto' (default: zlib, then raw, then gzip), 'zlib' (RFC 1950,
                       the PDF /FlateDecode flavour), 'raw' (RFC 1951 without wrapper, the ZIP
                       flavour), 'gzip' (RFC 1952). Bounded by MAX_INFLATED_BYTES.
                   crypto.deflate(data[, format, level]) -> Uint8Array
                       format: 'zlib' (default), 'raw' or 'gzip'; level -1 (default) .. 9.
                   (Whole archives stay the job of the 'archive' module; pdf.stream() applies
                   the PDF filters including predictors.)

                Examples:
                    var h = crypto.sha256("out.bin");                 // hash a file
                    var pt = crypto.aesCbcDecrypt(key, iv, ct);       // padded AES-CBC
                    var raw = crypto.inflate(bytes, 'zlib');          // PDF /FlateDecode
                    Reference vectors are validated by the JUnit tests JsCryptoTest and
                    JsCryptoKatTest.

                --- Help ---
                crypto.help()              - This help text.
                """;
    }
}
