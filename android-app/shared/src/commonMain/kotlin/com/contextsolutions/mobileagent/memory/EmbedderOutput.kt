package com.contextsolutions.mobileagent.memory

/**
 * Sentence vector emitted by the embedder.
 *
 * v1 is `all-MiniLM-L6-v2` INT8 weight-only — exports a single 384-dim
 * float32 output, mean-pooled and L2-normalised inside the graph (so
 * cosine similarity between two outputs is a plain dot product).
 *
 * Wrapping the array buys us:
 *  - dimension assertion at construction (catches a re-exported model
 *    that changes hidden size before it silently mis-reads the BLOB),
 *  - a single place to evolve the contract if v1.x adds a second
 *    encoding (e.g. query vs document, or non-normalised variants).
 */
data class EmbedderOutput(val vector: FloatArray) {
    init {
        require(vector.size == EMBEDDING_DIM) {
            "embedding must be size $EMBEDDING_DIM, was ${vector.size}"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmbedderOutput) return false
        return vector.contentEquals(other.vector)
    }

    override fun hashCode(): Int = vector.contentHashCode()

    companion object {
        const val EMBEDDING_DIM: Int = 384
    }
}
