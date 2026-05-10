package com.contextsolutions.mobileagent.memory.internal

import com.contextsolutions.mobileagent.memory.Memory

/**
 * Little-endian Float32 ↔ ByteArray codec for the `embedding` BLOB column.
 * Stored layout: 384 floats × 4 bytes = 1,536 bytes per row.
 *
 * Pure-Kotlin (no `ByteBuffer`) so the encoding is identical on Android and
 * iOS — the SQLite file is portable across the two platforms (a future v1.x
 * cross-platform memory-export feature relies on this contract). KMP doesn't
 * have `ByteBuffer.order(LITTLE_ENDIAN)` in commonMain, so we shift bits by
 * hand.
 *
 * Format choice: little-endian because every Android ARM64 device is LE,
 * every iOS device is LE, and writing big-endian would mean a flip on every
 * read. Float bit layout is IEEE 754 single — the same on both platforms.
 */
object EmbeddingBlob {

    /** Number of bytes per encoded vector. */
    val ENCODED_SIZE: Int = Memory.EMBEDDING_DIM * 4

    fun encode(vector: FloatArray): ByteArray {
        require(vector.size == Memory.EMBEDDING_DIM) {
            "vector must be size ${Memory.EMBEDDING_DIM}, was ${vector.size}"
        }
        val out = ByteArray(ENCODED_SIZE)
        for (i in vector.indices) {
            val bits = vector[i].toRawBits()
            val base = i * 4
            out[base + 0] = (bits and 0xFF).toByte()
            out[base + 1] = ((bits ushr 8) and 0xFF).toByte()
            out[base + 2] = ((bits ushr 16) and 0xFF).toByte()
            out[base + 3] = ((bits ushr 24) and 0xFF).toByte()
        }
        return out
    }

    fun decode(bytes: ByteArray): FloatArray {
        require(bytes.size == ENCODED_SIZE) {
            "embedding blob must be $ENCODED_SIZE bytes, was ${bytes.size}"
        }
        val out = FloatArray(Memory.EMBEDDING_DIM)
        for (i in out.indices) {
            val base = i * 4
            val bits =
                (bytes[base + 0].toInt() and 0xFF) or
                    ((bytes[base + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[base + 2].toInt() and 0xFF) shl 16) or
                    ((bytes[base + 3].toInt() and 0xFF) shl 24)
            out[i] = Float.fromBits(bits)
        }
        return out
    }
}
