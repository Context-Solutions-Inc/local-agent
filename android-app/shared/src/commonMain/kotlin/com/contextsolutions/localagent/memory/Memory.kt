package com.contextsolutions.localagent.memory

/**
 * A single memory entry per PRD §3.2.4. Mirrors the `memories` SQLDelight table.
 *
 * The [embedding] is the 384-dim float32 sentence vector produced by the
 * v1 embedder (`all-MiniLM-L6-v2` INT8, see M5_PLAN.md). Stored as a BLOB
 * (little-endian float32) in SQLite; held as `FloatArray` here.
 *
 * [conversationId] is a soft reference — a memory may outlive the source
 * conversation (deleting a conversation does not cascade into memories).
 *
 * [expiresAtEpochMs] is non-null only for `temporary_context` memories.
 * Eviction's tier-1 sweep uses this column directly.
 */
data class Memory(
    val id: String,
    val text: String,
    val category: MemoryCategory,
    val conversationId: String?,
    val createdAtEpochMs: Long,
    val lastAccessedEpochMs: Long,
    val accessCount: Int,
    val embedding: FloatArray,
    val expiresAtEpochMs: Long?,
) {
    init {
        require(embedding.size == EMBEDDING_DIM) {
            "embedding must be size $EMBEDDING_DIM, was ${embedding.size}"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Memory) return false
        return id == other.id &&
            text == other.text &&
            category == other.category &&
            conversationId == other.conversationId &&
            createdAtEpochMs == other.createdAtEpochMs &&
            lastAccessedEpochMs == other.lastAccessedEpochMs &&
            accessCount == other.accessCount &&
            expiresAtEpochMs == other.expiresAtEpochMs &&
            embedding.contentEquals(other.embedding)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + text.hashCode()
        result = 31 * result + category.hashCode()
        result = 31 * result + (conversationId?.hashCode() ?: 0)
        result = 31 * result + createdAtEpochMs.hashCode()
        result = 31 * result + lastAccessedEpochMs.hashCode()
        result = 31 * result + accessCount
        result = 31 * result + (expiresAtEpochMs?.hashCode() ?: 0)
        result = 31 * result + embedding.contentHashCode()
        return result
    }

    companion object {
        /** Sentence-vector dimension produced by `all-MiniLM-L6-v2`. */
        const val EMBEDDING_DIM: Int = 384
    }
}
