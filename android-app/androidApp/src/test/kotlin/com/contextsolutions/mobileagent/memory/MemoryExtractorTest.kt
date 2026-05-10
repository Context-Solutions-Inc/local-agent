package com.contextsolutions.mobileagent.memory

import com.contextsolutions.mobileagent.agent.TimeContext
import com.contextsolutions.mobileagent.classifier.ClassifierAccelerator
import com.contextsolutions.mobileagent.classifier.ClassifierEngine
import com.contextsolutions.mobileagent.classifier.ClassifierOutput
import com.contextsolutions.mobileagent.classifier.Vocab
import com.contextsolutions.mobileagent.classifier.WordPieceTokenizer
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryExtractorTest {

    // Anchor `now` to the same instant the timeContextProvider claims as
    // 2026-05-10 14:32 UTC so the date parser ("next week") produces an
    // expiry whose epoch-ms delta from `now` is ~7 days, not the ~56-year
    // gap between the unix epoch and 2026.
    private val nowDate = LocalDate(2026, 5, 10)
    private val now = nowDate.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds() + 14 * 3_600_000L + 32 * 60_000L

    // -- Classifier path ---------------------------------------------------

    @Test
    fun no_op_when_creation_disabled() = runTest {
        val store = TrackingStore()
        val extractor = buildExtractor(store = store, creationEnabled = false)

        val report = extractor.extract(
            userMessage = "i'm a software engineer",
            assistantResponse = "noted",
            conversationId = null,
        )

        assertSame(MemoryExtractor.ExtractionReport.SkippedDisabled, report)
        assertTrue("no inserts when disabled", store.inserted.isEmpty())
    }

    @Test
    fun no_op_when_user_message_blank() = runTest {
        val store = TrackingStore()
        val extractor = buildExtractor(store = store)

        val report = extractor.extract(
            userMessage = "  ",
            assistantResponse = "noted",
            conversationId = null,
        )

        assertSame(MemoryExtractor.ExtractionReport.NoOp, report)
    }

    @Test
    fun no_op_when_assistant_response_blank() = runTest {
        val store = TrackingStore()
        val extractor = buildExtractor(store = store)

        val report = extractor.extract(
            userMessage = "i'm a software engineer",
            assistantResponse = "",
            conversationId = null,
        )
        assertSame(MemoryExtractor.ExtractionReport.NoOp, report)
    }

    @Test
    fun skips_when_classifier_unavailable() = runTest {
        val store = TrackingStore()
        val extractor = buildExtractor(
            store = store,
            classifier = NullClassifier,
        )

        val report = extractor.extract(
            userMessage = "i'm a software engineer",
            assistantResponse = "noted",
            conversationId = null,
        )

        assertSame(MemoryExtractor.ExtractionReport.SkippedNoClassifier, report)
    }

    @Test
    fun no_op_when_presence_head_says_no_extraction() = runTest {
        val store = TrackingStore()
        val classifier = StubClassifier(
            // presence: NO_EXTRACTION wins (logit[0]=5, logit[1]=0)
            presenceLogits = floatArrayOf(5f, 0f),
            // Even with active categories, presence vetoes.
            categoryLogits = floatArrayOf(5f, 0f, 0f, 0f, 0f, 0f),
        )
        val extractor = buildExtractor(store = store, classifier = classifier)

        val report = extractor.extract(
            userMessage = "what's the weather today",
            assistantResponse = "let me check",
            conversationId = null,
        )

        assertSame(MemoryExtractor.ExtractionReport.NoOp, report)
        assertTrue(store.inserted.isEmpty())
    }

    @Test
    fun creates_one_memory_per_active_category() = runTest {
        val store = TrackingStore()
        val classifier = StubClassifier(
            presenceLogits = floatArrayOf(0f, 5f), // HAS_EXTRACTION
            // PERSONAL_IDENTITY (idx 0) + PROFESSIONAL (idx 2) cross 0.5
            categoryLogits = floatArrayOf(5f, -5f, 5f, -5f, -5f, -5f),
        )
        val extractor = buildExtractor(store = store, classifier = classifier, ids = listOf("m-1", "m-2"))

        val report = extractor.extract(
            userMessage = "i'm a software engineer in toronto",
            assistantResponse = "got it!",
            conversationId = "conv-1",
        ) as MemoryExtractor.ExtractionReport.Created

        assertEquals(2, report.createdIds.size)
        assertEquals(2, store.inserted.size)
        // Both share the same embedding (same user message).
        assertTrue(
            "memories share embedding",
            store.inserted[0].embedding.contentEquals(store.inserted[1].embedding),
        )
        // Categories ordered by enum index.
        assertEquals(MemoryCategory.PERSONAL_IDENTITY, store.inserted[0].category)
        assertEquals(MemoryCategory.PROFESSIONAL, store.inserted[1].category)
        // Verbatim user-message text per Q3 / M5_PLAN.md §2.
        assertEquals("i'm a software engineer in toronto", store.inserted[0].text)
        assertEquals("conv-1", store.inserted[0].conversationId)
    }

    @Test
    fun temporary_context_memory_gets_expiration_from_user_message() = runTest {
        val store = TrackingStore()
        val classifier = StubClassifier(
            presenceLogits = floatArrayOf(0f, 5f),
            // TEMPORARY_CONTEXT (idx 5)
            categoryLogits = floatArrayOf(-5f, -5f, -5f, -5f, -5f, 5f),
        )
        val extractor = buildExtractor(store = store, classifier = classifier)

        extractor.extract(
            userMessage = "i'm traveling to tokyo next week",
            assistantResponse = "have a great trip",
            conversationId = null,
        )

        val mem = store.inserted.single()
        assertEquals(MemoryCategory.TEMPORARY_CONTEXT, mem.category)
        assertNotNull("expiresAt must be set on temporary_context", mem.expiresAtEpochMs)
        // "next week" with the test clock at 2026-05-10 → 2026-05-17.
        // We just sanity-check the offset is roughly +7 days from `now`,
        // not exact ms (TempContextDateParserTest covers exactness).
        val deltaDays = (mem.expiresAtEpochMs!! - now) / (24L * 60 * 60 * 1_000)
        assertTrue("expected ~7 days delta, got $deltaDays", deltaDays in 5..14)
    }

    @Test
    fun temporary_context_falls_back_to_30_day_default_when_no_date() = runTest {
        val store = TrackingStore()
        val classifier = StubClassifier(
            presenceLogits = floatArrayOf(0f, 5f),
            categoryLogits = floatArrayOf(-5f, -5f, -5f, -5f, -5f, 5f),
        )
        val extractor = buildExtractor(store = store, classifier = classifier)

        extractor.extract(
            userMessage = "i have a temporary thing going on",
            assistantResponse = "ok",
            conversationId = null,
        )

        val mem = store.inserted.single()
        val deltaDays = (mem.expiresAtEpochMs!! - now) / (24L * 60 * 60 * 1_000)
        assertEquals(30L, deltaDays)
    }

    @Test
    fun dedup_skips_when_existing_memory_within_threshold() = runTest {
        val store = TrackingStore(
            existingMatch = stubMemory("existing"),
        )
        val classifier = StubClassifier(
            presenceLogits = floatArrayOf(0f, 5f),
            categoryLogits = floatArrayOf(5f, -5f, -5f, -5f, -5f, -5f),
        )
        val extractor = buildExtractor(store = store, classifier = classifier)

        val report = extractor.extract(
            userMessage = "i'm a software engineer",
            assistantResponse = "noted",
            conversationId = null,
        ) as MemoryExtractor.ExtractionReport.Created

        assertEquals(0, report.createdIds.size)
        assertEquals(listOf("existing"), report.deduped)
        assertTrue(store.inserted.isEmpty())
    }

    @Test
    fun runs_evictor_before_inserting() = runTest {
        val store = TrackingStore()
        val evictor = SpyEvictor()
        val classifier = StubClassifier(
            presenceLogits = floatArrayOf(0f, 5f),
            categoryLogits = floatArrayOf(5f, -5f, -5f, -5f, -5f, -5f),
        )
        val extractor = buildExtractor(store = store, classifier = classifier, evictor = evictor)

        extractor.extract(
            userMessage = "i'm a software engineer",
            assistantResponse = "noted",
            conversationId = null,
        )

        assertEquals(1, evictor.calls)
    }

    // -- Remember command --------------------------------------------------

    @Test
    fun remember_command_force_creates_memory_bypassing_presence() = runTest {
        val store = TrackingStore()
        // Classifier reports NO_EXTRACTION, but the explicit command
        // overrides.
        val classifier = StubClassifier(
            presenceLogits = floatArrayOf(5f, 0f),
            categoryLogits = floatArrayOf(5f, -5f, -5f, -5f, -5f, -5f),
        )
        val extractor = buildExtractor(store = store, classifier = classifier, ids = listOf("force-1"))

        val report = extractor.extract(
            userMessage = "remember that I'm allergic to peanuts",
            assistantResponse = "got it",
            conversationId = null,
        ) as MemoryExtractor.ExtractionReport.Created

        assertEquals(1, report.createdIds.size)
        assertEquals("I'm allergic to peanuts", store.inserted.single().text)
    }

    @Test
    fun remember_command_dedupes_against_existing_memory() = runTest {
        val store = TrackingStore(existingMatch = stubMemory("existing"))
        val classifier = StubClassifier(
            presenceLogits = floatArrayOf(5f, 0f),
            categoryLogits = floatArrayOf(5f, -5f, -5f, -5f, -5f, -5f),
        )
        val extractor = buildExtractor(store = store, classifier = classifier)

        val report = extractor.extract(
            userMessage = "remember that I'm allergic to peanuts",
            assistantResponse = "got it",
            conversationId = null,
        ) as MemoryExtractor.ExtractionReport.Created

        assertEquals(0, report.createdIds.size)
        assertEquals(listOf("existing"), report.deduped)
        assertTrue(store.inserted.isEmpty())
    }

    // -- Forget command ----------------------------------------------------

    @Test
    fun forget_command_calls_deleteByCosine() = runTest {
        val deleted = stubMemory("victim")
        val store = TrackingStore(cosineMatchToDelete = deleted)
        val extractor = buildExtractor(store = store)

        val report = extractor.extract(
            userMessage = "forget about my anniversary",
            assistantResponse = "ok",
            conversationId = null,
        ) as MemoryExtractor.ExtractionReport.Forgot

        assertEquals("victim", report.deletedId)
        assertEquals(1, store.deleteByCosineCalls)
        assertTrue("no inserts on forget", store.inserted.isEmpty())
    }

    @Test
    fun forget_command_uses_retrieval_threshold_not_dedup_threshold() = runTest {
        // Locks the bug-fix from the on-device run: dedup at 0.85 was too
        // strict for forget queries like "forget what I said about ice cream"
        // (cos against "I love chocolate ice cream" lands ~0.6). Retrieval's
        // 0.5 is the right floor.
        val store = TrackingStore(cosineMatchToDelete = stubMemory("victim"))
        val extractor = buildExtractor(store = store)

        extractor.extract(
            userMessage = "forget what I said about ice cream",
            assistantResponse = "ok",
            conversationId = null,
        )

        assertEquals(
            MemoryStore.DEFAULT_RETRIEVAL_THRESHOLD,
            store.lastDeleteByCosineThreshold,
            1e-9,
        )
    }

    @Test
    fun forget_command_with_no_match_is_silent_noop() = runTest {
        val store = TrackingStore()
        val extractor = buildExtractor(store = store)

        val report = extractor.extract(
            userMessage = "forget about something nonexistent",
            assistantResponse = "ok",
            conversationId = null,
        ) as MemoryExtractor.ExtractionReport.Forgot

        assertEquals(null, report.deletedId)
    }

    // -- Failure modes -----------------------------------------------------

    @Test
    fun engine_failure_returns_skipped_not_throws() = runTest {
        val store = TrackingStore()
        val extractor = buildExtractor(
            store = store,
            embedder = NullEmbedder,
        )
        val report = extractor.extract(
            userMessage = "remember that I'm vegan",
            assistantResponse = "got it",
            conversationId = null,
        )
        assertSame(MemoryExtractor.ExtractionReport.SkippedNoEmbedder, report)
    }

    // -- Test fixtures -----------------------------------------------------

    private fun buildExtractor(
        store: MemoryStore,
        classifier: ClassifierEngine = StubClassifier(),
        embedder: EmbedderEngine = StubEmbedder(),
        evictor: MemoryEvictor = MemoryEvictor(capacity = 10_000),
        creationEnabled: Boolean = true,
        ids: List<String> = listOf("m-1", "m-2", "m-3", "m-4", "m-5"),
    ): MemoryExtractor {
        val idIter = ids.iterator()
        val tokenizer = WordPieceTokenizer(stubVocab)
        val timeContext = {
            TimeContext(
                now = LocalDateTime(2026, 5, 10, 14, 32),
                timeZoneId = "UTC",
                timeZoneAbbreviation = "UTC",
                utcOffset = "+00:00",
            )
        }
        val parser = TempContextDateParser(
            timeContextProvider = timeContext,
            timeZoneProvider = { TimeZone.UTC },
        )
        return MemoryExtractor(
            classifier = classifier,
            tokenizer = tokenizer,
            embedder = embedder,
            store = store,
            evictor = evictor,
            detector = RememberForgetDetector(),
            dateParser = parser,
            nowProvider = { now },
            creationEnabledProvider = { creationEnabled },
            idGenerator = { if (idIter.hasNext()) idIter.next() else "m-overflow" },
        )
    }

    private val stubVocab = Vocab(
        tokenToId = mapOf("[PAD]" to 0, "[UNK]" to 100, "[CLS]" to 101, "[SEP]" to 102),
        idToToken = mapOf(0 to "[PAD]", 100 to "[UNK]", 101 to "[CLS]", 102 to "[SEP]"),
    )

    /**
     * Default presence = HAS_EXTRACTION; default category = PREFERENCE only.
     */
    private class StubClassifier(
        private val presenceLogits: FloatArray = floatArrayOf(0f, 5f),
        private val categoryLogits: FloatArray = floatArrayOf(-5f, 5f, -5f, -5f, -5f, -5f),
    ) : ClassifierEngine {
        override val isLoaded: Boolean = true
        override suspend fun warmUp(): ClassifierAccelerator = ClassifierAccelerator.CPU
        override suspend fun classify(inputIds: LongArray, attentionMask: LongArray): ClassifierOutput =
            ClassifierOutput(
                preflightLogits = floatArrayOf(0f, 0f, 0f),
                presenceLogits = presenceLogits,
                categoryLogits = categoryLogits,
            )
        override suspend fun unload() = Unit
    }

    private object NullClassifier : ClassifierEngine {
        override val isLoaded: Boolean = false
        override suspend fun warmUp(): ClassifierAccelerator? = null
        override suspend fun classify(inputIds: LongArray, attentionMask: LongArray): ClassifierOutput? = null
        override suspend fun unload() = Unit
    }

    private class StubEmbedder : EmbedderEngine {
        override val isLoaded: Boolean = true
        override suspend fun warmUp(): EmbedderAccelerator = EmbedderAccelerator.CPU
        override suspend fun embed(text: String): EmbedderOutput =
            EmbedderOutput(FloatArray(Memory.EMBEDDING_DIM) { (it % 7) * 0.001f })
        override suspend fun unload() = Unit
    }

    private object NullEmbedder : EmbedderEngine {
        override val isLoaded: Boolean = false
        override suspend fun warmUp(): EmbedderAccelerator? = null
        override suspend fun embed(text: String): EmbedderOutput? = null
        override suspend fun unload() = Unit
    }

    private class TrackingStore(
        private val existingMatch: Memory? = null,
        private val cosineMatchToDelete: Memory? = null,
    ) : MemoryStore {
        val inserted: MutableList<Memory> = mutableListOf()
        var deleteByCosineCalls: Int = 0
        var lastDeleteByCosineThreshold: Double = -1.0

        override suspend fun insert(memory: Memory) {
            inserted += memory
        }

        override suspend fun deleteByCosine(embedding: FloatArray, threshold: Double, now: Long): Memory? {
            deleteByCosineCalls += 1
            lastDeleteByCosineThreshold = threshold
            return cosineMatchToDelete
        }

        override suspend fun findCosineMatch(embedding: FloatArray, threshold: Double, now: Long): Memory? =
            existingMatch

        override suspend fun count(now: Long): Int = inserted.size

        // Unused branches.
        override suspend fun deleteById(id: String) = Unit
        override suspend fun retrieveTopK(
            queryEmbedding: FloatArray,
            k: Int,
            threshold: Double,
            now: Long,
        ): List<MemoryHit> = emptyList()
        override suspend fun listForConversation(conversationId: String): List<Memory> = emptyList()
        override suspend fun countForConversation(conversationId: String): Int = 0
        override suspend fun listAll(): List<Memory> = inserted.toList()
        override suspend fun deleteAll() = Unit
        override suspend fun deleteExpired(now: Long): Int = 0
        override suspend fun selectLruEvictionCandidateIds(
            lastAccessedCutoff: Long,
            limit: Int,
        ): List<String> = emptyList()
    }

    private class SpyEvictor : MemoryEvictor() {
        var calls: Int = 0
        override suspend fun maybeEvict(store: MemoryStore, now: Long): EvictionReport {
            calls += 1
            return EvictionReport.NoOp
        }
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
