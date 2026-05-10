package com.contextsolutions.mobileagent.memory

import com.contextsolutions.mobileagent.memory.internal.cosine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class CosineTest {

    @Test
    fun identical_unit_vectors_score_one() {
        val v = floatArrayOf(0.6f, 0.8f) // ||v|| = 1
        assertEquals(1.0, cosine(v, v), 1e-9)
    }

    @Test
    fun orthogonal_vectors_score_zero() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(0f, 1f)
        assertEquals(0.0, cosine(a, b), 1e-9)
    }

    @Test
    fun antiparallel_vectors_score_minus_one() {
        val a = floatArrayOf(1f, 1f)
        val b = floatArrayOf(-1f, -1f)
        assertEquals(-1.0, cosine(a, b), 1e-9)
    }

    @Test
    fun zero_vector_returns_zero_not_NaN() {
        val a = floatArrayOf(0f, 0f)
        val b = floatArrayOf(1f, 0f)
        // Without the divisor guard this would be NaN.
        assertEquals(0.0, cosine(a, b), 0.0)
    }

    @Test
    fun normalises_when_inputs_arent_unit_length() {
        // Same direction, different magnitudes.
        val a = floatArrayOf(3f, 0f)
        val b = floatArrayOf(0.1f, 0f)
        assertEquals(1.0, cosine(a, b), 1e-9)
    }

    @Test
    fun mismatched_dim_throws() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(1f, 0f, 0f)
        try {
            cosine(a, b)
            assert(false) { "expected IllegalArgumentException" }
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun handles_384_dim_vectors_in_reasonable_time() {
        val a = FloatArray(384) { (it * 0.01f) }
        val b = FloatArray(384) { ((it + 1) * 0.01f) }
        val result = cosine(a, b)
        // Manual sanity check: monotonically increasing series, very close
        // to parallel — cosine should be just under 1.
        assertTrue("expected cosine near 1, got $result", result > 0.99 && result <= 1.0 + 1e-9)
    }

    @Test
    fun matches_hand_computation_on_small_example() {
        val a = floatArrayOf(1f, 2f, 3f)
        val b = floatArrayOf(4f, 5f, 6f)
        val expected = (1*4 + 2*5 + 3*6).toDouble() / (sqrt((1+4+9).toDouble()) * sqrt((16+25+36).toDouble()))
        assertEquals(expected, cosine(a, b), 1e-9)
    }
}
