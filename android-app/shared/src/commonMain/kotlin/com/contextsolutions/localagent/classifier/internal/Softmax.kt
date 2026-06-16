package com.contextsolutions.localagent.classifier.internal

import kotlin.math.exp
import kotlin.math.max

/**
 * Numerically-stable softmax. Subtracts the max before exponentiating to
 * avoid overflow on the very large INT8 logit magnitudes the v1.0 .tflite
 * produces (the M4 Phase A spike saw raw logits in the thousands; naive
 * `exp(x)` would saturate to `Float.POSITIVE_INFINITY` and emit NaNs after
 * normalization).
 */
fun softmax(logits: FloatArray): FloatArray {
    require(logits.isNotEmpty()) { "softmax input must not be empty" }
    var maxLogit = Float.NEGATIVE_INFINITY
    for (v in logits) maxLogit = max(maxLogit, v)
    if (!maxLogit.isFinite()) {
        // All -infinity (or all NaN): subtracting maxLogit would yield NaN, so
        // bypass the math and return a uniform distribution. Caller treats
        // this as "no information" — the right behavior at the routing layer
        // is FallThrough.
        val uniform = 1f / logits.size
        return FloatArray(logits.size) { uniform }
    }
    val out = FloatArray(logits.size)
    var sum = 0f
    for (i in logits.indices) {
        val e = exp((logits[i] - maxLogit).toDouble()).toFloat()
        out[i] = e
        sum += e
    }
    if (sum == 0f) {
        val uniform = 1f / logits.size
        return FloatArray(logits.size) { uniform }
    }
    for (i in out.indices) out[i] = out[i] / sum
    return out
}

/** Element-wise sigmoid for the multi-label memory-category head. */
fun sigmoid(logits: FloatArray): FloatArray {
    val out = FloatArray(logits.size)
    for (i in logits.indices) {
        // 1 / (1 + e^-x), computed in a way that doesn't overflow for very
        // negative or very positive inputs. For x < 0 we evaluate as
        // e^x / (1 + e^x); for x >= 0 we use the standard form.
        val x = logits[i]
        out[i] = if (x >= 0f) {
            val ex = exp((-x).toDouble()).toFloat()
            1f / (1f + ex)
        } else {
            val ex = exp(x.toDouble()).toFloat()
            ex / (1f + ex)
        }
    }
    return out
}

/** Index of the largest element. Returns 0 on tie at the leftmost max. */
fun argMax(values: FloatArray): Int {
    require(values.isNotEmpty()) { "argMax input must not be empty" }
    var bestIdx = 0
    var bestVal = values[0]
    for (i in 1 until values.size) {
        if (values[i] > bestVal) {
            bestVal = values[i]
            bestIdx = i
        }
    }
    return bestIdx
}
