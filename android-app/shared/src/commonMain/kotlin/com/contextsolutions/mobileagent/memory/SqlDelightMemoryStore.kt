package com.contextsolutions.mobileagent.memory

import com.contextsolutions.mobileagent.db.Memories as MemoriesRow
import com.contextsolutions.mobileagent.db.MemoriesQueries
import com.contextsolutions.mobileagent.memory.internal.EmbeddingBlob
import com.contextsolutions.mobileagent.memory.internal.cosine
import com.contextsolutions.mobileagent.sync.LocalChangeBus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

/**
 * [MemoryStore] backed by SQLDelight. Brute-force cosine over up to ~1,000
 * non-expired entries — sub-10 ms on Pixel 7 per the Phase B benchmark
 * (M5_PLAN.md §4 Phase B). The store is otherwise a thin DAO wrapper.
 *
 * **Threading.** Every public method dispatches the SQLDelight call onto
 * [ioDispatcher] (default `Dispatchers.IO`). The retrieval path also runs
 * cosine scoring inside that dispatcher because it touches every embedding
 * row — at 1k × 384-dim it's the dominant cost.
 *
 * **Why not an in-memory vector index.** PRD §3.2.4 caps the store at
 * 1,000 entries by default. That puts brute-force linear scan inside the
 * <100 ms p95 retrieval budget without an index. M6 telemetry will tell
 * us whether real corpora exceed the cap (in which case adding hnsw / faiss
 * is a v1.x decision); for v1 a linear scan is the simplest correct thing.
 */
class SqlDelightMemoryStore(
    private val queries: MemoriesQueries,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** PR #57 — fired after a genuine local write so the SyncController pushes it. */
    private val localChangeBus: LocalChangeBus? = null,
) : MemoryStore {

    override suspend fun insert(memory: Memory): Unit = withContext(ioDispatcher) {
        queries.insertMemory(
            id = memory.id,
            text = memory.text,
            category = memory.category.wireName,
            conversation_id = memory.conversationId,
            created_at_epoch_ms = memory.createdAtEpochMs,
            last_accessed_epoch_ms = memory.lastAccessedEpochMs,
            access_count = memory.accessCount.toLong(),
            embedding = EmbeddingBlob.encode(memory.embedding),
            expires_at_epoch_ms = memory.expiresAtEpochMs,
            // PR #57: a fresh insert is its own first write — seed the LWW sync key.
            updated_at_epoch_ms = memory.createdAtEpochMs,
        )
        localChangeBus?.notifyChanged()
    }

    override suspend fun deleteById(id: String): Unit = withContext(ioDispatcher) {
        // PR #57 — soft-delete so the "forget" propagates to the paired device.
        queries.softDeleteMemory(nowEpochMs = Clock.System.now().toEpochMilliseconds(), id = id)
        localChangeBus?.notifyChanged()
    }

    override suspend fun deleteByCosine(
        embedding: FloatArray,
        threshold: Double,
        now: Long,
    ): Memory? = withContext(ioDispatcher) {
        val match = highestCosineMatchInternal(embedding, threshold, now) ?: return@withContext null
        queries.softDeleteMemory(nowEpochMs = Clock.System.now().toEpochMilliseconds(), id = match.id)
        localChangeBus?.notifyChanged()
        match.toMemory()
    }

    override suspend fun retrieveTopK(
        queryEmbedding: FloatArray,
        k: Int,
        threshold: Double,
        now: Long,
    ): List<MemoryHit> = withContext(ioDispatcher) {
        val rows = queries.selectAllNonExpiredWithEmbedding(now).executeAsList()
        if (rows.isEmpty()) return@withContext emptyList()

        // Score every row, drop sub-threshold, keep top-K. Sorted insertion
        // would be slightly faster but at 1k rows the constant factor is
        // dominated by the cosine itself.
        val scored = ArrayList<Pair<MemoriesRow, Double>>(rows.size)
        for (row in rows) {
            val emb = EmbeddingBlob.decode(row.embedding)
            val sim = cosine(queryEmbedding, emb)
            if (sim >= threshold) scored += row to sim
        }
        if (scored.isEmpty()) return@withContext emptyList()

        val topK = scored.sortedByDescending { it.second }.take(k)

        // Atomically bump last_accessed + access_count for each hit. Wrap in
        // a transaction so partial failures don't leave the store in a
        // half-updated state.
        queries.transaction {
            for ((row, _) in topK) {
                queries.incrementAccessAndUpdateLastAccessed(now, row.id)
            }
        }

        // Return memories with the bumped values reflected — callers expect
        // the persisted state. accessCount and lastAccessedEpochMs are local
        // updates; everything else reads through.
        topK.map { (row, sim) ->
            MemoryHit(
                memory = row.toMemory(
                    overrideLastAccessed = now,
                    overrideAccessCount = (row.access_count + 1).toInt(),
                ),
                similarity = sim,
            )
        }
    }

    override suspend fun findCosineMatch(
        embedding: FloatArray,
        threshold: Double,
        now: Long,
    ): Memory? = withContext(ioDispatcher) {
        highestCosineMatchInternal(embedding, threshold, now)?.toMemory()
    }

    override suspend fun count(now: Long): Int = withContext(ioDispatcher) {
        queries.countNonExpired(now).executeAsOne().toInt()
    }

    override suspend fun listForConversation(conversationId: String): List<Memory> =
        withContext(ioDispatcher) {
            queries.selectByConversationId(conversationId).executeAsList().map { it.toMemory() }
        }

    override suspend fun countForConversation(conversationId: String): Int =
        withContext(ioDispatcher) {
            queries.countByConversationId(conversationId).executeAsOne().toInt()
        }

    override suspend fun listAll(): List<Memory> = withContext(ioDispatcher) {
        queries.selectAllMemories().executeAsList().map { it.toMemory() }
    }

    override suspend fun deleteAll(): Unit = withContext(ioDispatcher) {
        queries.deleteAllMemories()
    }

    // -- Internals ---------------------------------------------------------

    private fun highestCosineMatchInternal(
        query: FloatArray,
        threshold: Double,
        now: Long,
    ): MemoriesRow? {
        val rows = queries.selectAllForCosineSearch(now).executeAsList()
        var bestRow: MemoriesRow? = null
        var bestSim = threshold // strict inequality below filters out equal-to-threshold scores
        for (row in rows) {
            val sim = cosine(query, EmbeddingBlob.decode(row.embedding))
            if (sim > bestSim) {
                bestSim = sim
                bestRow = row
            }
        }
        return bestRow
    }

    private fun MemoriesRow.toMemory(
        overrideLastAccessed: Long? = null,
        overrideAccessCount: Int? = null,
    ): Memory = Memory(
        id = id,
        text = text,
        category = MemoryCategory.fromWireName(category)
            ?: error("unknown category in DB row $id: $category"),
        conversationId = conversation_id,
        createdAtEpochMs = created_at_epoch_ms,
        lastAccessedEpochMs = overrideLastAccessed ?: last_accessed_epoch_ms,
        accessCount = overrideAccessCount ?: access_count.toInt(),
        embedding = EmbeddingBlob.decode(embedding),
        expiresAtEpochMs = expires_at_epoch_ms,
    )
}
