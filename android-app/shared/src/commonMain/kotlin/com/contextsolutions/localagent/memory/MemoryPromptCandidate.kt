package com.contextsolutions.localagent.memory

/**
 * A middle-band memory proposal. Surfaced to the user as an inline
 * Save / Dismiss card. The embedding and expiry are precomputed at
 * proposal time so accepting later is a single `store.insert`.
 *
 * NOTE: [id] here is the candidate's identifier (used by the UI to
 * route Save / Dismiss callbacks). The persisted [Memory.id] is
 * generated freshly inside [MemoryExtractor.acceptPromptCandidate]
 * so a single candidate could in theory be saved more than once
 * after de-dup checks pass — though the UI flow makes that
 * effectively impossible.
 */
data class MemoryPromptCandidate(
    val id: String,
    val text: String,
    val category: MemoryCategory,
    val embedding: FloatArray,
    val conversationId: String?,
    val proposedAtEpochMs: Long,
    val expiresAtEpochMs: Long?,
) {
    /** Build the [Memory] row to persist if the user accepts. */
    fun toMemory(id: String, now: Long): Memory = Memory(
        id = id,
        text = text,
        category = category,
        conversationId = conversationId,
        createdAtEpochMs = now,
        lastAccessedEpochMs = now,
        accessCount = 0,
        embedding = embedding,
        expiresAtEpochMs = expiresAtEpochMs,
    )

    // Data-class equality across a FloatArray needs `contentEquals`, but
    // this class is only ever used as an in-memory holder keyed by [id].
    // Implementing equals/hashCode by id keeps the contract simple and
    // avoids the FloatArray pitfall.
    override fun equals(other: Any?): Boolean = other is MemoryPromptCandidate && other.id == id
    override fun hashCode(): Int = id.hashCode()
}
