package com.contextsolutions.mobileagent.memory

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryEvictorTest {

    private val now = 100_000L

    @Test
    fun no_op_when_under_capacity() = runTest {
        val store = SpyStore(initialCount = 5)
        val evictor = MemoryEvictor(capacity = 10)

        val report = evictor.maybeEvict(store, now)

        assertSame(MemoryEvictor.EvictionReport.NoOp, report)
        assertEquals("no eviction queries should run", 0, store.deleteExpiredCalls)
        assertEquals(0, store.lruCandidatesCalls)
    }

    @Test
    fun tier1_alone_clears_capacity_when_enough_expired() = runTest {
        val store = SpyStore(
            initialCount = 12,
            expiredToDelete = 5,
        )
        val evictor = MemoryEvictor(capacity = 10)

        val report = evictor.maybeEvict(store, now) as MemoryEvictor.EvictionReport.Ran

        assertEquals(5, report.deletedExpired)
        assertEquals(0, report.deletedStale)
        assertEquals(0, report.deletedLru)
        assertEquals(1, store.deleteExpiredCalls)
        // Tier 2 + 3 never query candidates.
        assertEquals(0, store.lruCandidatesCalls)
    }

    @Test
    fun tier2_runs_when_tier1_is_insufficient() = runTest {
        // 12 rows; tier 1 evicts 1 (still 11 ≥ cap=10); tier 2 evicts the
        // 2 rows older than the cutoff; final = 9 < cap.
        val store = SpyStore(
            initialCount = 12,
            expiredToDelete = 1,
            staleIds = listOf("stale-a", "stale-b"),
        )
        val evictor = MemoryEvictor(capacity = 10)

        val report = evictor.maybeEvict(store, now) as MemoryEvictor.EvictionReport.Ran

        assertEquals(1, report.deletedExpired)
        assertEquals(2, report.deletedStale)
        assertEquals(0, report.deletedLru)
        assertEquals(setOf("stale-a", "stale-b"), store.deletedIds)
    }

    @Test
    fun tier3_runs_when_tier1_and_tier2_are_insufficient() = runTest {
        // No expired, no stale; 12 fresh rows. Tier 3 evicts (12 - 10) + 1 = 3.
        val store = SpyStore(
            initialCount = 12,
            expiredToDelete = 0,
            staleIds = emptyList(),
            lruIds = listOf("lru-a", "lru-b", "lru-c"),
        )
        val evictor = MemoryEvictor(capacity = 10)

        val report = evictor.maybeEvict(store, now) as MemoryEvictor.EvictionReport.Ran

        assertEquals(0, report.deletedExpired)
        assertEquals(0, report.deletedStale)
        assertEquals(3, report.deletedLru)
        assertEquals(setOf("lru-a", "lru-b", "lru-c"), store.deletedIds)
    }

    @Test
    fun report_total_aggregates_all_tiers() = runTest {
        val store = SpyStore(
            initialCount = 12,
            expiredToDelete = 1,
            staleIds = listOf("s1"),
            lruIds = listOf("l1"),
        )
        val evictor = MemoryEvictor(capacity = 10)

        val report = evictor.maybeEvict(store, now) as MemoryEvictor.EvictionReport.Ran
        assertTrue(report.total >= report.deletedExpired + report.deletedStale + report.deletedLru)
    }

    // -- Spy store --------------------------------------------------------

    private class SpyStore(
        initialCount: Int,
        private var expiredToDelete: Int = 0,
        private val staleIds: List<String> = emptyList(),
        private val lruIds: List<String> = emptyList(),
    ) : MemoryStore {

        // Mutable so tier transitions can re-read count() between tiers.
        private var simulatedCount: Int = initialCount

        var deleteExpiredCalls: Int = 0
        var lruCandidatesCalls: Int = 0
        val deletedIds: MutableSet<String> = mutableSetOf()

        // True if tier 2 has consumed its budget — switches the next call
        // to lruCandidates over to the LRU pool.
        private var staleConsumed: Boolean = false

        override suspend fun count(now: Long): Int = simulatedCount

        override suspend fun deleteExpired(now: Long): Int {
            deleteExpiredCalls += 1
            simulatedCount -= expiredToDelete
            val deleted = expiredToDelete
            expiredToDelete = 0
            return deleted
        }

        override suspend fun selectLruEvictionCandidateIds(
            lastAccessedCutoff: Long,
            limit: Int,
        ): List<String> {
            lruCandidatesCalls += 1
            return if (!staleConsumed) {
                staleConsumed = true
                staleIds
            } else {
                lruIds
            }
        }

        override suspend fun deleteById(id: String) {
            if (deletedIds.add(id)) simulatedCount -= 1
        }

        // Unused by the evictor.
        override suspend fun insert(memory: Memory) = Unit
        override suspend fun deleteByCosine(embedding: FloatArray, threshold: Double, now: Long): Memory? = null
        override suspend fun retrieveTopK(
            queryEmbedding: FloatArray,
            k: Int,
            threshold: Double,
            now: Long,
        ): List<MemoryHit> = emptyList()
        override suspend fun findCosineMatch(embedding: FloatArray, threshold: Double, now: Long): Memory? = null
        override suspend fun listForConversation(conversationId: String): List<Memory> = emptyList()
        override suspend fun countForConversation(conversationId: String): Int = 0
        override suspend fun listAll(): List<Memory> = emptyList()
        override suspend fun deleteAll() = Unit
    }
}
