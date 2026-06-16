package com.contextsolutions.localagent.memory

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRetrieverTest {

    @Test
    fun returns_empty_for_blank_query() = runTest {
        val embedder = AlwaysReadyEmbedder()
        val store = StubMemoryStore(emptyList())
        val retriever = MemoryRetriever(embedder, store, nowProvider = { 1_000L })

        assertTrue(retriever.retrieve("   ").isEmpty())
        assertEquals("blank query should not embed", 0, embedder.embedCalls)
        assertEquals("blank query should not query store", 0, store.retrieveCalls)
    }

    @Test
    fun returns_empty_when_embedder_unavailable() = runTest {
        val logged = mutableListOf<String>()
        val embedder = NullReturningEmbedder()
        val store = StubMemoryStore(emptyList())
        val retriever = MemoryRetriever(
            embedder = embedder,
            store = store,
            nowProvider = { 1_000L },
            logger = logged::add,
        )

        val result = retriever.retrieve("hello world")
        assertTrue(result.isEmpty())
        assertEquals("store should not be queried when embedder fails", 0, store.retrieveCalls)
        assertEquals("first failure logs once", 1, logged.size)
    }

    @Test
    fun does_not_spam_logs_after_first_failure() = runTest {
        val logged = mutableListOf<String>()
        val embedder = NullReturningEmbedder()
        val store = StubMemoryStore(emptyList())
        val retriever = MemoryRetriever(
            embedder = embedder,
            store = store,
            nowProvider = { 1_000L },
            logger = logged::add,
        )

        retriever.retrieve("a query")
        retriever.retrieve("another query")
        retriever.retrieve("a third query")

        assertEquals("subsequent failures should be silent", 1, logged.size)
    }

    @Test
    fun forwards_top_K_threshold_and_now_to_the_store() = runTest {
        val embedder = AlwaysReadyEmbedder()
        val store = StubMemoryStore(
            preset = listOf(MemoryHit(stubMemory("a"), 0.9)),
        )
        val retriever = MemoryRetriever(embedder, store, nowProvider = { 42_000L })

        val result = retriever.retrieve("hello", k = 3, threshold = 0.42)

        assertEquals(1, result.size)
        assertEquals("a", result[0].memory.id)
        assertEquals(3, store.lastK)
        assertEquals(0.42, store.lastThreshold, 1e-9)
        assertEquals(42_000L, store.lastNow)
    }

    @Test
    fun returns_empty_and_logs_when_store_throws() = runTest {
        val logged = mutableListOf<String>()
        val embedder = AlwaysReadyEmbedder()
        val store = ThrowingMemoryStore()
        val retriever = MemoryRetriever(
            embedder = embedder,
            store = store,
            nowProvider = { 1_000L },
            logger = logged::add,
        )

        val result = retriever.retrieve("hello")
        assertTrue(result.isEmpty())
        assertEquals(1, logged.size)
    }

    // -- Stubs ---------------------------------------------------------------

    private class AlwaysReadyEmbedder : EmbedderEngine {
        var embedCalls: Int = 0
        override val isLoaded: Boolean get() = true
        override suspend fun warmUp(): EmbedderAccelerator = EmbedderAccelerator.CPU
        override suspend fun embed(text: String): EmbedderOutput {
            embedCalls += 1
            return EmbedderOutput(FloatArray(Memory.EMBEDDING_DIM) { 0f })
        }
        override suspend fun unload() = Unit
    }

    private class NullReturningEmbedder : EmbedderEngine {
        override val isLoaded: Boolean get() = false
        override suspend fun warmUp(): EmbedderAccelerator? = null
        override suspend fun embed(text: String): EmbedderOutput? = null
        override suspend fun unload() = Unit
    }

    private class StubMemoryStore(private val preset: List<MemoryHit>) : MemoryStore {
        var retrieveCalls: Int = 0
        var lastK: Int = -1
        var lastThreshold: Double = -1.0
        var lastNow: Long = -1L

        override suspend fun insert(memory: Memory) = Unit
        override suspend fun deleteById(id: String) = Unit
        override suspend fun deleteByCosine(embedding: FloatArray, threshold: Double, now: Long): Memory? = null
        override suspend fun retrieveTopK(
            queryEmbedding: FloatArray,
            k: Int,
            threshold: Double,
            now: Long,
        ): List<MemoryHit> {
            retrieveCalls += 1
            lastK = k
            lastThreshold = threshold
            lastNow = now
            return preset
        }
        override suspend fun findCosineMatch(embedding: FloatArray, threshold: Double, now: Long): Memory? = null
        override suspend fun count(now: Long): Int = 0
        override suspend fun listForConversation(conversationId: String): List<Memory> = emptyList()
        override suspend fun countForConversation(conversationId: String): Int = 0
        override suspend fun listAll(): List<Memory> = emptyList()
        override suspend fun deleteAll() = Unit
    }

    private class ThrowingMemoryStore : MemoryStore {
        override suspend fun insert(memory: Memory) = Unit
        override suspend fun deleteById(id: String) = Unit
        override suspend fun deleteByCosine(embedding: FloatArray, threshold: Double, now: Long): Memory? = null
        override suspend fun retrieveTopK(
            queryEmbedding: FloatArray,
            k: Int,
            threshold: Double,
            now: Long,
        ): List<MemoryHit> = throw IllegalStateException("simulated store failure")
        override suspend fun findCosineMatch(embedding: FloatArray, threshold: Double, now: Long): Memory? = null
        override suspend fun count(now: Long): Int = 0
        override suspend fun listForConversation(conversationId: String): List<Memory> = emptyList()
        override suspend fun countForConversation(conversationId: String): Int = 0
        override suspend fun listAll(): List<Memory> = emptyList()
        override suspend fun deleteAll() = Unit
    }

    private fun stubMemory(id: String): Memory = Memory(
        id = id,
        text = "memory $id",
        category = MemoryCategory.PREFERENCE,
        conversationId = null,
        createdAtEpochMs = 0L,
        lastAccessedEpochMs = 0L,
        accessCount = 0,
        embedding = FloatArray(Memory.EMBEDDING_DIM) { 0f },
        expiresAtEpochMs = null,
    )
}
