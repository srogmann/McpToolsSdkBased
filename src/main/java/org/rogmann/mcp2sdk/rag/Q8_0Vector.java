package org.rogmann.mcp2sdk.rag;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.lang.Float.SIZE;

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
            float scale = float16ToFloat(scaleBits);

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
            short scaleBits = floatToFloat16(scale);
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
                buffer.putShort(floatToFloat16(scale));

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

    // The following methods can removed after change to java21ff.
    //

    /**
     * The number of bits in the significand of a {@code float} value.
     * This is the parameter N in section {@jls 4.2.3} of
     * <cite>The Java Language Specification</cite>.
     *
     * @since 19
     */
    public static final int PRECISION = 24;

    /**
     * The number of logical bits in the significand of a
     * {@code float} number, including the implicit bit.
     */
    public static final int SIGNIFICAND_WIDTH = PRECISION;

    /**
     * Bias used in representing a {@code float} exponent.
     */
    public static final int EXP_BIAS =
            (1 << (SIZE - SIGNIFICAND_WIDTH - 1)) - 1; // 127

    static short floatToFloat16(float f) {
        int doppel = Float.floatToRawIntBits(f);
        short sign_bit = (short)((doppel & 0x8000_0000) >> 16);

        if (Float.isNaN(f)) {
            // Preserve sign and attempt to preserve significand bits
            return (short)(sign_bit
                    | 0x7c00 // max exponent + 1
                    // Preserve high order bit of float NaN in the
                    // binary16 result NaN (tenth bit); OR in remaining
                    // bits into lower 9 bits of binary 16 significand.
                    | (doppel & 0x007f_e000) >> 13 // 10 bits
                    | (doppel & 0x0000_1ff0) >> 4  //  9 bits
                    | (doppel & 0x0000_000f));     //  4 bits
        }

        float abs_f = Math.abs(f);

        // The overflow threshold is binary16 MAX_VALUE + 1/2 ulp
        if (abs_f >= (0x1.ffcp15f + 0x0.002p15f) ) {
            return (short)(sign_bit | 0x7c00); // Positive or negative infinity
        }

        // Smallest magnitude nonzero representable binary16 value
        // is equal to 0x1.0p-24; half-way and smaller rounds to zero.
        if (abs_f <= 0x1.0p-24f * 0.5f) { // Covers float zeros and subnormals.
            return sign_bit; // Positive or negative zero
        }

        // Dealing with finite values in exponent range of binary16
        // (when rounding is done, could still round up)
        int exp = Math.getExponent(f);
        assert -25 <= exp && exp <= 15;

        // For binary16 subnormals, beside forcing exp to -15, retain
        // the difference expdelta = E_min - exp.  This is the excess
        // shift value, in addition to 13, to be used in the
        // computations below.  Further the (hidden) msb with value 1
        // in f must be involved as well.
        int expdelta = 0;
        int msb = 0x0000_0000;
        if (exp < -14) {
            expdelta = -14 - exp;
            exp = -15;
            msb = 0x0080_0000;
        }
        int f_signif_bits = doppel & 0x007f_ffff | msb;

        // Significand bits as if using rounding to zero (truncation).
        short signif_bits = (short)(f_signif_bits >> (13 + expdelta));

        // For round to nearest even, determining whether or not to
        // round up (in magnitude) is a function of the least
        // significant bit (LSB), the next bit position (the round
        // position), and the sticky bit (whether there are any
        // nonzero bits in the exact result to the right of the round
        // digit). An increment occurs in three cases:
        //
        // LSB  Round Sticky
        // 0    1     1
        // 1    1     0
        // 1    1     1
        // See "Computer Arithmetic Algorithms," Koren, Table 4.9

        int lsb    = f_signif_bits & (1 << 13 + expdelta);
        int round  = f_signif_bits & (1 << 12 + expdelta);
        int sticky = f_signif_bits & ((1 << 12 + expdelta) - 1);

        if (round != 0 && ((lsb | sticky) != 0 )) {
            signif_bits++;
        }

        // No bits set in significand beyond the *first* exponent bit,
        // not just the significand; quantity is added to the exponent
        // to implement a carry out from rounding the significand.
        assert (0xf800 & signif_bits) == 0x0;

        return (short)(sign_bit | ( ((exp + 15) << 10) + signif_bits ) );
    }

    public static float float16ToFloat(short floatBinary16) {
        /*
         * The binary16 format has 1 sign bit, 5 exponent bits, and 10
         * significand bits. The exponent bias is 15.
         */
        int bin16arg = (int)floatBinary16;
        int bin16SignBit     = 0x8000 & bin16arg;
        int bin16ExpBits     = 0x7c00 & bin16arg;
        int bin16SignifBits  = 0x03FF & bin16arg;

        // Shift left difference in the number of significand bits in
        // the float and binary16 formats
        final int SIGNIF_SHIFT = (SIGNIFICAND_WIDTH - 11);

        float sign = (bin16SignBit != 0) ? -1.0f : 1.0f;

        // Extract binary16 exponent, remove its bias, add in the bias
        // of a float exponent and shift to correct bit location
        // (significand width includes the implicit bit so shift one
        // less).
        int bin16Exp = (bin16ExpBits >> 10) - 15;
        if (bin16Exp == -15) {
            // For subnormal binary16 values and 0, the numerical
            // value is 2^24 * the significand as an integer (no
            // implicit bit).
            return sign * (0x1p-24f * bin16SignifBits);
        } else if (bin16Exp == 16) {
            return (bin16SignifBits == 0) ?
                    sign * Float.POSITIVE_INFINITY :
                    Float.intBitsToFloat((bin16SignBit << 16) |
                            0x7f80_0000 |
                            // Preserve NaN signif bits
                            ( bin16SignifBits << SIGNIF_SHIFT ));
        }

        assert -15 < bin16Exp  && bin16Exp < 16;

        int floatExpBits = (bin16Exp + EXP_BIAS)
                << (SIGNIFICAND_WIDTH - 1);

        // Compute and combine result sign, exponent, and significand bits.
        return Float.intBitsToFloat((bin16SignBit << 16) |
                floatExpBits |
                (bin16SignifBits << SIGNIF_SHIFT));
    }

}
