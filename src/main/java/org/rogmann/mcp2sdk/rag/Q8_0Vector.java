package org.rogmann.mcp2sdk.rag;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility for reading and writing vectors in the Q8_0 quantization format (GGUF/GGML).
 *
 * <p>The Q8_0 format stores vectors in blocks of 32 float values.
 * Each block consists of a float16 scale factor (2 bytes, little-endian)
 * followed by 32 signed byte values. The dequantized value is computed as:
 * {@code value = quantizedByte * scale}, where {@code scale = Float.float16ToFloat(rawShort)}.
 *
 * <p>Vector sizes are required to be divisible by 256 (i.e. an integer multiple of 8 blocks),
 * which is a common alignment constraint for embedding vectors.
 *
 * <p>This implementation does not use the Java Vector API.
 */
public class Q8_0Vector {

    /** Logger */
    private static final Logger LOG = LoggerFactory.getLogger(Q8_0Vector.class);

    /** Number of float values per Q8_0 block. */
    public static final int BLOCK_SIZE = 32;

    /** Number of bytes per Q8_0 block: 2 bytes (float16 scale) + 32 bytes (quantized values). */
    public static final int BLOCK_BYTES = 2 + BLOCK_SIZE;

    /** Required alignment: vector size must be a multiple of this value (256 = 8 * 32). */
    public static final int ALIGNMENT = 256;

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private Q8_0Vector() {
        // Utility class, not meant to be instantiated.
    }

    /**
     * Validates that the given vector size is compatible with the Q8_0 format.
     *
     * @param vectorSize the size of a vector in float elements
     * @throws IllegalArgumentException if the vector size is not positive or not divisible by {@value #ALIGNMENT}
     */
    private static void validateVectorSize(int vectorSize) {
        if (vectorSize <= 0) {
            throw new IllegalArgumentException("Vector size must be positive, got: " + vectorSize);
        }
        if (vectorSize % ALIGNMENT != 0) {
            throw new IllegalArgumentException(
                    "Vector size must be divisible by " + ALIGNMENT + ", got: " + vectorSize);
        }
    }

    /**
     * Validates that the given ByteBuffer has enough remaining data to hold the specified number of vectors.
     *
     * @param buffer      the ByteBuffer
     * @param vectorCount number of vectors
     * @param vectorSize  size of each vector in float elements
     * @throws IllegalArgumentException if the buffer does not contain enough data
     */
    private static void validateBufferCapacity(ByteBuffer buffer, int vectorCount, int vectorSize) {
        int bytesPerVector = vectorSize / BLOCK_SIZE * BLOCK_BYTES;
        int requiredBytes = vectorCount * bytesPerVector;
        if (buffer.remaining() < requiredBytes) {
            throw new IllegalArgumentException(String.format(
                    "Buffer too small: remaining=%d, required=%d (vectors=%d, vectorSize=%d, bytesPerVector=%d)",
                    buffer.remaining(), requiredBytes, vectorCount, vectorSize, bytesPerVector));
        }
    }

    /**
     * Returns the number of bytes required to store a vector of the given size in Q8_0 format.
     *
     * @param vectorSize the size of the vector in float elements (must be divisible by {@value #ALIGNMENT})
     * @return the number of bytes in Q8_0 format
     */
    public static int bytesPerVector(int vectorSize) {
        validateVectorSize(vectorSize);
        return vectorSize / BLOCK_SIZE * BLOCK_BYTES;
    }

    /**
     * Reads a single Q8_0 quantized vector from a ByteBuffer and converts it to a float array.
     *
     * <p>The buffer's position is advanced by the number of bytes consumed.
     * The buffer must be in little-endian byte order.
     *
     * @param buffer     the ByteBuffer containing Q8_0 quantized data (little-endian)
     * @param vectorSize the size of the vector in float elements (must be divisible by {@value #ALIGNMENT})
     * @return a float array of length {@code vectorSize} containing the dequantized values
     * @throws IllegalArgumentException if the buffer has insufficient remaining data
     */
    public static float[] readVector(ByteBuffer buffer, int vectorSize) {
        validateVectorSize(vectorSize);
        validateBufferCapacity(buffer, 1, vectorSize);

        final int blockCount = vectorSize / BLOCK_SIZE;
        final float[] result = new float[vectorSize];

        for (int block = 0; block < blockCount; block++) {
            // Read float16 scale (2 bytes, little-endian)
            short scaleBits = buffer.getShort();
            float scale = Float.float16ToFloat(scaleBits);

            // Read 32 signed byte values
            int baseIndex = block * BLOCK_SIZE;
            for (int i = 0; i < BLOCK_SIZE; i++) {
                byte quantized = buffer.get();
                result[baseIndex + i] = quantized * scale;
            }
        }

        LOG.debug("Read Q8_0 vector of size {} ({} blocks, {} bytes)", vectorSize, blockCount, blockCount * BLOCK_BYTES);
        return result;
    }

    /**
     * Reads multiple Q8_0 quantized vectors from a ByteBuffer and converts them to float arrays.
     *
     * <p>The buffer's position is advanced by the number of bytes consumed.
     * The buffer must be in little-endian byte order.
     *
     * @param buffer      the ByteBuffer containing Q8_0 quantized data (little-endian)
     * @param vectorCount the number of vectors to read
     * @param vectorSize  the size of each vector in float elements (must be divisible by {@value #ALIGNMENT})
     * @return an array of float arrays, each of length {@code vectorSize}
     * @throws IllegalArgumentException if the buffer has insufficient remaining data
     */
    public static float[][] readVectors(ByteBuffer buffer, int vectorCount, int vectorSize) {
        validateVectorSize(vectorSize);
        validateBufferCapacity(buffer, vectorCount, vectorSize);

        final float[][] result = new float[vectorCount][];
        for (int v = 0; v < vectorCount; v++) {
            result[v] = readVector(buffer, vectorSize);
        }

        LOG.debug("Read {} Q8_0 vectors of size {}", vectorCount, vectorSize);
        return result;
    }

    /**
     * Converts a single float vector to Q8_0 format and returns a ByteBuffer containing the quantized data.
     *
     * <p>The returned buffer is in little-endian byte order, positioned at the start (flipped).
     *
     * @param vector the float vector to quantize (length must be divisible by {@value #ALIGNMENT})
     * @return a ByteBuffer (little-endian, flipped) containing the Q8_0 quantized data
     * @throws IllegalArgumentException if the vector length is invalid
     */
    public static ByteBuffer writeVector(float[] vector) {
        final int vectorSize = vector.length;
        validateVectorSize(vectorSize);

        final int blockCount = vectorSize / BLOCK_SIZE;
        final int byteCount = blockCount * BLOCK_BYTES;
        final ByteBuffer buffer = ByteBuffer.allocate(byteCount).order(ByteOrder.LITTLE_ENDIAN);

        for (int block = 0; block < blockCount; block++) {
            int baseIndex = block * BLOCK_SIZE;

            // Find the maximum absolute value in this block for scale calculation.
            float maxAbs = 0f;
            for (int i = 0; i < BLOCK_SIZE; i++) {
                float abs = Math.abs(vector[baseIndex + i]);
                if (abs > maxAbs) {
                    maxAbs = abs;
                }
            }

            // Compute scale factor: map the range [-maxAbs, +maxAbs] to [-127, +127].
            // If maxAbs is zero, all values are zero; use a scale of 1.0f.
            final float scale;
            if (maxAbs == 0f) {
                scale = 1.0f;
            } else {
                scale = maxAbs / 127.0f;
            }

            // Write float16 scale (2 bytes, little-endian).
            short scaleBits = Float.floatToFloat16(scale);
            buffer.putShort(scaleBits);

            // Quantize and write 32 signed byte values.
            for (int i = 0; i < BLOCK_SIZE; i++) {
                float value = vector[baseIndex + i];
                int quantized = Math.round(value / scale);
                // Clamp to signed byte range [-128, 127].
                if (quantized < -128) {
                    quantized = -128;
                } else if (quantized > 127) {
                    quantized = 127;
                }
                buffer.put((byte) quantized);
            }
        }

        buffer.flip();
        LOG.debug("Wrote Q8_0 vector of size {} ({} blocks, {} bytes)", vectorSize, blockCount, byteCount);
        return buffer;
    }

    /**
     * Converts multiple float vectors to Q8_0 format and returns a ByteBuffer containing the quantized data.
     *
     * <p>The returned buffer is in little-endian byte order, positioned at the start (flipped).
     *
     * @param vectors the float vectors to quantize (each length must be divisible by {@value #ALIGNMENT})
     * @return a ByteBuffer (little-endian, flipped) containing the Q8_0 quantized data
     * @throws IllegalArgumentException if any vector length is invalid
     */
    public static ByteBuffer writeVectors(float[][] vectors) {
        if (vectors == null || vectors.length == 0) {
            throw new IllegalArgumentException("Vectors array must not be null or empty");
        }

        final int vectorSize = vectors[0].length;
        validateVectorSize(vectorSize);

        // Verify all vectors have the same size.
        for (int v = 0; v < vectors.length; v++) {
            if (vectors[v].length != vectorSize) {
                throw new IllegalArgumentException(String.format(
                        "Vector at index %d has size %d, expected %d", v, vectors[v].length, vectorSize));
            }
        }

        final int blockCount = vectorSize / BLOCK_SIZE;
        final int bytesPerVector = blockCount * BLOCK_BYTES;
        final int totalBytes = vectors.length * bytesPerVector;
        final ByteBuffer buffer = ByteBuffer.allocate(totalBytes).order(ByteOrder.LITTLE_ENDIAN);

        for (float[] vector : vectors) {
            for (int block = 0; block < blockCount; block++) {
                int baseIndex = block * BLOCK_SIZE;

                // Find the maximum absolute value in this block.
                float maxAbs = 0f;
                for (int i = 0; i < BLOCK_SIZE; i++) {
                    float abs = Math.abs(vector[baseIndex + i]);
                    if (abs > maxAbs) {
                        maxAbs = abs;
                    }
                }

                // Compute scale factor.
                final float scale;
                if (maxAbs == 0f) {
                    scale = 1.0f;
                } else {
                    scale = maxAbs / 127.0f;
                }

                // Write float16 scale.
                buffer.putShort(Float.floatToFloat16(scale));

                // Quantize and write 32 signed byte values.
                for (int i = 0; i < BLOCK_SIZE; i++) {
                    float value = vector[baseIndex + i];
                    int quantized = Math.round(value / scale);
                    if (quantized < -128) {
                        quantized = -128;
                    } else if (quantized > 127) {
                        quantized = 127;
                    }
                    buffer.put((byte) quantized);
                }
            }
        }

        buffer.flip();
        LOG.debug("Wrote {} Q8_0 vectors of size {} ({} bytes)", vectors.length, vectorSize, totalBytes);
        return buffer;
    }

    /**
     * Reads a single Q8_0 quantized vector from a byte array and converts it to a float array.
     *
     * <p>This is a convenience method that wraps the byte array in a ByteBuffer.
     *
     * @param data       the byte array containing Q8_0 quantized data (little-endian)
     * @param vectorSize the size of the vector in float elements (must be divisible by {@value #ALIGNMENT})
     * @return a float array of length {@code vectorSize} containing the dequantized values
     */
    public static float[] readVectorFromBytes(byte[] data, int vectorSize) {
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        return readVector(buffer, vectorSize);
    }

    /**
     * Reads multiple Q8_0 quantized vectors from a byte array and converts them to float arrays.
     *
     * <p>This is a convenience method that wraps the byte array in a ByteBuffer.
     *
     * @param data        the byte array containing Q8_0 quantized data (little-endian)
     * @param vectorCount the number of vectors to read
     * @param vectorSize  the size of each vector in float elements (must be divisible by {@value #ALIGNMENT})
     * @return an array of float arrays, each of length {@code vectorSize}
     */
    public static float[][] readVectorsFromBytes(byte[] data, int vectorCount, int vectorSize) {
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        return readVectors(buffer, vectorCount, vectorSize);
    }

    /**
     * Converts a single float vector to Q8_0 format and returns a byte array.
     *
     * <p>This is a convenience method that extracts the bytes from the ByteBuffer.
     *
     * @param vector the float vector to quantize (length must be divisible by {@value #ALIGNMENT})
     * @return a byte array containing the Q8_0 quantized data
     */
    public static byte[] writeVectorToBytes(float[] vector) {
        ByteBuffer buffer = writeVector(vector);
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    /**
     * Converts multiple float vectors to Q8_0 format and returns a byte array.
     *
     * <p>This is a convenience method that extracts the bytes from the ByteBuffer.
     *
     * @param vectors the float vectors to quantize (each length must be divisible by {@value #ALIGNMENT})
     * @return a byte array containing the Q8_0 quantized data
     */
    public static byte[] writeVectorsToBytes(float[][] vectors) {
        ByteBuffer buffer = writeVectors(vectors);
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }
}
