package org.rogmann.mcp2sdk.rag;

import java.nio.ByteBuffer;
import java.util.Base64;

/**
 * Encodes and decodes embedding vectors in the two storage formats of the RAG
 * sources: {@code F32} (raw 32-bit floats) and {@code Q8_0} (8-bit with block-wise
 * float16 scales, see {@link Q8_0Vector}).
 * <p>
 * Both formats are stored Base64-encoded inside the text formats (CSV, JSONL), so
 * that a store remains line-oriented and diffable.
 * </p>
 */
public final class VectorCodec {

    /** Storage format of a vector. */
    public enum Format {
        /** Full precision: 4 bytes per value. */
        F32,
        /** Quantized: 34 bytes per block of 32 values. */
        Q8_0;

        /**
         * Parses a format name strictly.
         *
         * @param name the name, e.g. {@code "Q8_0"} or {@code "F32"} (case-insensitive)
         * @param where description of the place the name was read from, for the error message
         * @return the format
         * @throws IllegalArgumentException if the name is unknown
         */
        public static Format parse(String name, String where) {
            if (name != null) {
                for (Format format : values()) {
                    if (format.name().equalsIgnoreCase(name.trim())) {
                        return format;
                    }
                }
            }
            throw new IllegalArgumentException(
                    where + ": unknown vector format '" + name + "' - expected F32 or Q8_0");
        }
    }

    private VectorCodec() {
        // utility class
    }

    /**
     * Encodes a vector.
     *
     * @param vector the vector
     * @param format the storage format
     * @return the Base64-encoded data
     */
    public static String encode(float[] vector, Format format) {
        if (format == Format.Q8_0) {
            // bytesPerVector validates the 256-value alignment of Q8_0.
            Q8_0Vector.bytesPerVector(vector.length);
            return Base64.getEncoder().encodeToString(Q8_0Vector.writeVectorToBytes(vector));
        }
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * 4);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    /**
     * Decodes a vector whose dimension is not known in advance.
     *
     * @param base64 the Base64-encoded data
     * @param format the storage format
     * @return the vector
     */
    public static float[] decode(String base64, Format format) {
        byte[] bytes = Base64.getDecoder().decode(base64);
        if (format == Format.Q8_0) {
            if (bytes.length % Q8_0Vector.BLOCK_BYTES != 0) {
                throw new IllegalArgumentException("Q8_0 data of " + bytes.length
                        + " bytes is not a whole number of blocks of " + Q8_0Vector.BLOCK_BYTES + " bytes");
            }
            int dimension = (bytes.length / Q8_0Vector.BLOCK_BYTES) * Q8_0Vector.BLOCK_SIZE;
            return Q8_0Vector.readVectorFromBytes(bytes, dimension);
        }
        if (bytes.length % 4 != 0) {
            throw new IllegalArgumentException("F32 data of " + bytes.length
                    + " bytes is not a whole number of 4-byte floats");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        float[] vector = new float[bytes.length / 4];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = buffer.getFloat();
        }
        return vector;
    }

    /**
     * Computes the cosine similarity of two vectors.
     *
     * @param a first vector
     * @param b second vector
     * @return the cosine in {@code [-1, 1]}, or {@code 0} if a vector has length 0
     * @throws IllegalArgumentException if the dimensions differ
     */
    public static double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException(
                    "vectors have different dimensions: " + a.length + " and " + b.length);
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            double x = a[i];
            double y = b[i];
            dot += x * y;
            normA += x * x;
            normB += y * y;
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / Math.sqrt(normA * normB);
    }

    /**
     * Checks a dimension against a storage format (Q8_0 requires a multiple of 256 values).
     *
     * @param dimension the vector dimension
     * @param format    the storage format
     * @param where     description of the place, for the error message
     * @throws IllegalArgumentException if the dimension does not fit the format
     */
    public static void validateDimension(int dimension, Format format, String where) {
        if (dimension <= 0) {
            throw new IllegalArgumentException(where + ": vector dimension must be positive, got: " + dimension);
        }
        if (format == Format.Q8_0) {
            try {
                Q8_0Vector.bytesPerVector(dimension);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(where + ": dimension " + dimension
                        + " cannot be stored as Q8_0 (" + e.getMessage() + ")");
            }
        }
    }
}
