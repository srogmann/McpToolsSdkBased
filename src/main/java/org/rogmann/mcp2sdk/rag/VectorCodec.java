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

    /**
     * Separator between a format prefix and the Base64 payload, e.g. {@code "Q8:<base64>"}.
     * The Base64 alphabet never contains it, so the first occurrence is the separator.
     */
    public static final char PREFIX_SEPARATOR = ':';

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
     * Encodes a vector with its format prefix, e.g. {@code "Q8:<base64>"}.
     * <p>
     * A store that keeps the format next to the value (instead of in a column or a header)
     * can use this, and read it back with {@link #decodeAuto(String, Format)}. It is the
     * one spelling of "format plus payload" in this package - a provider implementation
     * should not invent its own.
     * </p>
     *
     * @param vector the vector
     * @param format the storage format
     * @return the prefixed Base64 string
     */
    public static String encodePrefixed(float[] vector, Format format) {
        return format.name() + PREFIX_SEPARATOR + encode(vector, format);
    }

    /**
     * Returns the format a value names, or the fallback when it carries no prefix.
     *
     * @param value    the value, e.g. {@code "Q8:<base64>"} or {@code "<base64>"}
     * @param fallback the format to assume without a prefix, may be {@code null}
     * @return the format
     * @throws IllegalArgumentException if the prefix is unknown or empty, or there is no
     *                                  prefix and no fallback
     */
    public static Format formatOf(String value, Format fallback) {
        int separator = value.indexOf(PREFIX_SEPARATOR);
        if (separator < 0) {
            if (fallback == null) {
                throw new IllegalArgumentException(
                        "vector value has no format prefix and no declared format");
            }
            return fallback;
        }
        return Format.parse(value.substring(0, separator), "vector prefix");
    }

    /**
     * Decodes a value that may carry a format prefix.
     * <p>
     * Without a prefix the {@code expected} format is used. With a prefix, the prefix wins -
     * but only if it agrees with a declared format: a {@code "Q8:"} value in a file whose
     * header says {@code F32} is a contradiction, not a feature.
     * </p>
     *
     * @param value    the value, e.g. {@code "Q8:<base64>"} or {@code "<base64>"}
     * @param expected the declared format of the surrounding file, may be {@code null}
     * @return the vector
     * @throws IllegalArgumentException if the prefix is unknown, empty, or contradicts
     *                                  {@code expected}, or if there is neither
     */
    public static float[] decodeAuto(String value, Format expected) {
        int separator = value.indexOf(PREFIX_SEPARATOR);
        if (separator < 0) {
            if (expected == null) {
                throw new IllegalArgumentException(
                        "vector value has no format prefix and no declared format");
            }
            if (value.isEmpty()) {
                throw new IllegalArgumentException("vector value is empty");
            }
            return decode(value, expected);
        }
        Format prefixed = Format.parse(value.substring(0, separator), "vector prefix");
        if (expected != null && prefixed != expected) {
            throw new IllegalArgumentException("vector prefix '" + prefixed
                    + "' contradicts the declared format '" + expected + "'");
        }
        String payload = value.substring(separator + 1);
        if (payload.isEmpty()) {
            throw new IllegalArgumentException("vector value has an empty payload");
        }
        return decode(payload, prefixed);
    }

    /**
     * Tolerance for the normalization check: a vector whose length differs from 1 by more
     * than this is reported (and optionally normalized). Technical constant, not a tuning
     * knob - the sources of this project are built with normalized embeddings.
     */
    public static final double NORMALIZATION_TOLERANCE = 1e-3;

    /**
     * Returns the Euclidean length of a vector.
     *
     * @param vector the vector
     * @return the length
     */
    public static double norm(float[] vector) {
        double sum = 0;
        for (float value : vector) {
            sum += (double) value * value;
        }
        return Math.sqrt(sum);
    }

    /**
     * Indicates whether a vector has unit length within a tolerance.
     *
     * @param vector    the vector
     * @param tolerance the accepted deviation of the length from 1
     * @return true if the vector is normalized
     */
    public static boolean isNormalized(float[] vector, double tolerance) {
        return Math.abs(norm(vector) - 1.0) <= tolerance;
    }

    /**
     * Returns a normalized copy of a vector.
     *
     * @param vector the vector
     * @return the unit vector, or {@code null} if the vector has length 0
     */
    public static float[] normalize(float[] vector) {
        double norm = norm(vector);
        if (norm == 0) {
            return null;
        }
        float[] unit = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            unit[i] = (float) (vector[i] / norm);
        }
        return unit;
    }

    /**
     * Normalizes a vector in place.
     * <p>
     * Used only when a source is loaded with {@code mcp.rag.normalizeOnLoad=true}: the
     * chunks' arrays are the ones the provider holds, so the change is in memory only and
     * never written back.
     * </p>
     *
     * @param vector the vector to change
     * @return true if the vector was normalized, false if it was a zero vector
     */
    public static boolean normalizeInPlace(float[] vector) {
        double norm = norm(vector);
        if (norm == 0) {
            return false;
        }
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] / norm);
        }
        return true;
    }

    /**
     * Computes the dot product of two vectors - the measure the search uses.
     * <p>
     * For unit vectors the dot product <em>is</em> the cosine; the query is normalized once
     * per search and the sources are checked at load ({@link #isNormalized}), so the hot
     * loop needs one multiplication per element instead of three.
     * </p>
     *
     * @param a first vector
     * @param b second vector
     * @return the dot product
     * @throws IllegalArgumentException if the dimensions differ
     */
    public static double dot(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException(
                    "vectors have different dimensions: " + a.length + " and " + b.length);
        }
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += (double) a[i] * b[i];
        }
        return sum;
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
