package com.contextsolutions.localagent.memory

import com.contextsolutions.localagent.memory.internal.EmbeddingBlob
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class EmbeddingBlobTest {

    @Test
    fun encoded_size_matches_constant() {
        assertEquals(Memory.EMBEDDING_DIM * 4, EmbeddingBlob.ENCODED_SIZE)
    }

    @Test
    fun round_trips_a_random_vector() {
        val random = FloatArray(Memory.EMBEDDING_DIM) { i ->
            // Mix of small/large magnitudes, signs, and one subnormal.
            when (i) {
                0 -> 0f
                1 -> -1f
                2 -> Float.MIN_VALUE
                3 -> Float.MAX_VALUE
                else -> ((i * 0.0123f) - 1.5f)
            }
        }
        val encoded = EmbeddingBlob.encode(random)
        assertEquals(EmbeddingBlob.ENCODED_SIZE, encoded.size)

        val decoded = EmbeddingBlob.decode(encoded)
        assertArrayEquals(random, decoded, 0f)
    }

    @Test
    fun encodes_first_float_as_little_endian_ieee754() {
        // Known float: 1.0f → bits 0x3F800000 → LE bytes 00 00 80 3F
        val v = FloatArray(Memory.EMBEDDING_DIM) { 0f }
        v[0] = 1.0f
        val bytes = EmbeddingBlob.encode(v)
        assertEquals(0x00.toByte(), bytes[0])
        assertEquals(0x00.toByte(), bytes[1])
        assertEquals(0x80.toByte(), bytes[2])
        assertEquals(0x3F.toByte(), bytes[3])
    }

    @Test
    fun rejects_wrong_input_dim_on_encode() {
        try {
            EmbeddingBlob.encode(FloatArray(Memory.EMBEDDING_DIM - 1))
            assert(false) { "expected IllegalArgumentException" }
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun rejects_wrong_blob_size_on_decode() {
        try {
            EmbeddingBlob.decode(ByteArray(EmbeddingBlob.ENCODED_SIZE - 4))
            assert(false) { "expected IllegalArgumentException" }
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun handles_NaN_and_infinity_via_toRawBits() {
        val v = FloatArray(Memory.EMBEDDING_DIM) { 0f }
        v[0] = Float.NaN
        v[1] = Float.POSITIVE_INFINITY
        v[2] = Float.NEGATIVE_INFINITY
        val decoded = EmbeddingBlob.decode(EmbeddingBlob.encode(v))
        // NaN ≠ NaN by IEEE rules; check via bit pattern.
        assertEquals(Float.NaN.toRawBits(), decoded[0].toRawBits())
        assertEquals(Float.POSITIVE_INFINITY, decoded[1], 0f)
        assertEquals(Float.NEGATIVE_INFINITY, decoded[2], 0f)
    }
}
