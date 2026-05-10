package com.contextsolutions.mobileagent.memory

/**
 * Persistent memory store per PRD §3.2.4. Backed by SQLDelight on Android;
 * the `Memories.sq` schema is the source of truth for the table layout.
 *
 * The store stays small (≤ 1,000 entries by default; user-configurable).
 * At that scale brute-force cosine similarity over all non-expired rows
 * is sub-10 ms on Pixel 7 — no native vector index is needed. Eviction
 * keeps the store bounded and is the responsibility of [MemoryEvictor]
 * (composed at call sites; not folded into this interface so the store
 * stays a dumb persistence seam).
 *
 * **Threading.** All suspending methods MUST be safe to call from a
 * background coroutine. Implementations wrap in `withContext(Dispatchers.IO)`.
 *
 * **Failure mode.** Persistence errors propagate as exceptions; callers
 * (`MemoryRetriever`, `MemoryExtractor`) catch and degrade to no-op so the
 * user-facing turn never fails because of a memory-layer hiccup.
 */
interface MemoryStore {

    /** Insert a new memory. Caller is responsible for deduplication via [findCosineMatch]. */
    suspend fun insert(memory: Memory)

    /** Delete a memory by primary key. No-op if not present. */
    suspend fun deleteById(id: String)

    /**
     * Delete the highest-cosine match against [embedding] when its similarity
     * exceeds [threshold]. Returns the deleted memory, or `null` if no row
     * cleared the threshold. Used by the explicit-forget command path.
     *
     * Expired rows are filtered out before scoring — a forget command should
     * not match memories the user has already aged out.
     */
    suspend fun deleteByCosine(
        embedding: FloatArray,
        threshold: Double = DEFAULT_DEDUP_THRESHOLD,
        now: Long,
    ): Memory?

    /**
     * Top-K nearest non-expired memories by cosine similarity. Filters expired
     * rows (`expires_at_epoch_ms <= now`) before scoring. On a hit, the row's
     * `last_accessed_epoch_ms` and `access_count` are updated atomically.
     *
     * @param queryEmbedding the L2-normalised query vector.
     * @param k max results to return; the v1 default is 5 (PRD §3.2.4).
     * @param threshold minimum cosine similarity to include.
     * @param now current wall-clock epoch ms (injected for testability).
     */
    suspend fun retrieveTopK(
        queryEmbedding: FloatArray,
        k: Int = DEFAULT_TOP_K,
        threshold: Double = DEFAULT_RETRIEVAL_THRESHOLD,
        now: Long,
    ): List<MemoryHit>

    /**
     * Find the single highest-cosine memory whose similarity exceeds
     * [threshold]. Returns `null` if no row clears the threshold. Used by
     * the extractor's pre-insert dedup pass (PRD §3.2.4 — cosine > 0.85
     * means the memory already exists).
     *
     * Expired rows are filtered out before scoring.
     */
    suspend fun findCosineMatch(
        embedding: FloatArray,
        threshold: Double = DEFAULT_DEDUP_THRESHOLD,
        now: Long,
    ): Memory?

    /** Total non-expired entry count. Used by the eviction pre-insert check. */
    suspend fun count(now: Long): Int

    /** All memories created in [conversationId], newest first. */
    suspend fun listForConversation(conversationId: String): List<Memory>

    /** Number of memories created in [conversationId]. Used by the conversation-list badge. */
    suspend fun countForConversation(conversationId: String): Int

    /** Every memory in the store, regardless of expiry (UI uses this for the management screen). */
    suspend fun listAll(): List<Memory>

    /** Delete every memory. Used by the "clear all" UI affordance. */
    suspend fun deleteAll()

    /**
     * Tier-1 eviction sweep — drop every row whose `expires_at_epoch_ms` has
     * passed. Caller is `MemoryEvictor`. Returns the number of rows deleted
     * for telemetry / logging.
     */
    suspend fun deleteExpired(now: Long): Int

    /**
     * Tier-2 / tier-3 eviction candidate IDs. Returns up to [limit] memory
     * IDs whose `last_accessed_epoch_ms < lastAccessedCutoff`, ordered by
     * `(last_accessed ASC, access_count ASC)` — least-recently-accessed
     * first, with least-frequent as the tie-break (PRD §3.2.4 "LRU + access-
     * frequency heuristic").
     *
     * Tier 2 passes `now - 90 days` for the cutoff with a large limit;
     * tier 3 passes `Long.MAX_VALUE` (matching every row) with a small
     * positive limit equal to the over-cap delta.
     */
    suspend fun selectLruEvictionCandidateIds(
        lastAccessedCutoff: Long,
        limit: Int,
    ): List<String>

    companion object {
        /** PRD §3.2.4 — default retrieval K. */
        const val DEFAULT_TOP_K: Int = 5

        /** PRD §3.2.4 — default retrieval threshold (recall-leaning). */
        const val DEFAULT_RETRIEVAL_THRESHOLD: Double = 0.5

        /** PRD §3.2.4 — default dedup threshold (precision-leaning). */
        const val DEFAULT_DEDUP_THRESHOLD: Double = 0.85
    }
}

/**
 * A retrieved memory paired with its similarity score. Sorted by [similarity]
 * descending in [MemoryStore.retrieveTopK]'s return list.
 */
data class MemoryHit(
    val memory: Memory,
    val similarity: Double,
)
