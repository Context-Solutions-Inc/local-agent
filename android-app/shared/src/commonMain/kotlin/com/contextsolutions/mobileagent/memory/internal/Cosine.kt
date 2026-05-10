package com.contextsolutions.mobileagent.memory.internal

import kotlin.math.sqrt

/**
 * Cosine similarity over two FloatArrays. Pure-Kotlin, no native deps.
 *
 * Computes `dot(a, b) / (||a|| * ||b||)` in a single pass. Returns 0 when
 * either vector is the zero vector (avoids div-by-zero; the embedder never
 * produces the zero vector in practice but the guard makes the function
 * total).
 *
 * The embedder bakes L2-normalisation into the graph, so in steady state
 * `||a|| ≈ ||b|| ≈ 1.0` (within INT8 quantisation drift) and this reduces
 * to a plain dot product. We still compute the divisor to stay correct when
 * the invariant is wobbly — the cost is negligible at 384 dim / 1k rows
 * (Phase B benchmark gates this).
 *
 * Single-precision input, double-precision accumulators — float32 sums of
 * 384 terms are accurate enough but JVM hotspot prefers double.
 */
fun cosine(a: FloatArray, b: FloatArray): Double {
    require(a.size == b.size) {
        "vector dim mismatch: ${a.size} vs ${b.size}"
    }
    var dot = 0.0
    var na = 0.0
    var nb = 0.0
    for (i in a.indices) {
        val x = a[i].toDouble()
        val y = b[i].toDouble()
        dot += x * y
        na += x * x
        nb += y * y
    }
    val denom = sqrt(na) * sqrt(nb)
    return if (denom > 0.0) dot / denom else 0.0
}
