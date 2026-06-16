package com.contextsolutions.localagent.classifier.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SoftmaxTest {

    @Test
    fun softmax_distributes_to_one() {
        val out = softmax(floatArrayOf(1f, 2f, 3f))
        assertProbabilityDistribution(out)
        // exp([1,2,3] - 3) / sum = [e^-2, e^-1, e^0] / sum
        assertNear(0.0900f, out[0], 1e-3f)
        assertNear(0.2447f, out[1], 1e-3f)
        assertNear(0.6652f, out[2], 1e-3f)
    }

    @Test
    fun softmax_handles_large_logit_magnitudes_without_overflow() {
        // Phase A spike saw raw logits in the thousands; naive softmax overflows.
        val out = softmax(floatArrayOf(1758.7f, -2336.7f, 3072.0f))
        assertProbabilityDistribution(out)
        assertEquals(2, argMax(out))
        // The largest logit dominates so heavily the others should be ~0.
        assertTrue("p[0]=${out[0]} should be effectively zero", out[0] < 1e-6f)
        assertTrue("p[1]=${out[1]} should be effectively zero", out[1] < 1e-6f)
        assertNear(1.0f, out[2], 1e-3f)
    }

    @Test
    fun softmax_uniform_input_yields_uniform_output() {
        val out = softmax(floatArrayOf(7f, 7f, 7f, 7f))
        assertProbabilityDistribution(out)
        for (p in out) assertNear(0.25f, p, 1e-6f)
    }

    @Test
    fun softmax_pathological_neg_infinity_returns_uniform_not_nan() {
        val out = softmax(floatArrayOf(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY))
        assertProbabilityDistribution(out)
        assertNear(0.5f, out[0], 1e-6f)
    }

    @Test
    fun sigmoid_negative_and_positive_inputs() {
        val out = sigmoid(floatArrayOf(-100f, -1f, 0f, 1f, 100f))
        assertNear(0f, out[0], 1e-3f)
        assertNear(0.2689f, out[1], 1e-3f)
        assertNear(0.5f, out[2], 1e-6f)
        assertNear(0.7311f, out[3], 1e-3f)
        assertNear(1f, out[4], 1e-3f)
    }

    @Test
    fun argMax_returns_leftmost_on_tie() {
        assertEquals(0, argMax(floatArrayOf(3f, 3f, 1f)))
        assertEquals(1, argMax(floatArrayOf(1f, 3f, 3f)))
    }

    private fun assertProbabilityDistribution(probs: FloatArray) {
        val sum = probs.sum()
        assertNear(1f, sum, 1e-4f)
        for (p in probs) {
            assertTrue("p=$p out of [0,1]", p in 0f..1f)
        }
    }

    private fun assertNear(expected: Float, actual: Float, tolerance: Float) {
        assertTrue(
            "expected $expected (±$tolerance), got $actual",
            abs(expected - actual) < tolerance,
        )
    }
}
