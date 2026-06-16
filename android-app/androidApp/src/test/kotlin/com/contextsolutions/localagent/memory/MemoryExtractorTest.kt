package com.contextsolutions.localagent.memory

import com.contextsolutions.localagent.agent.TimeContext
import com.contextsolutions.localagent.classifier.ClassifierAccelerator
import com.contextsolutions.localagent.classifier.ClassifierEngine
import com.contextsolutions.localagent.classifier.ClassifierOutput
import com.contextsolutions.localagent.classifier.Vocab
import com.contextsolutions.localagent.classifier.WordPieceTokenizer
import com.contextsolutions.localagent.telemetry.CounterNames
import com.contextsolutions.localagent.telemetry.NoOpTelemetryCounters
import com.contextsolutions.localagent.telemetry.TelemetryCounters
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
    fun proposes_one_candidate_per_active_category() = runTest {
        // Pre-PR#7 (PR #4 era) this test was
        // `creates_one_memory_per_active_category` and asserted on the
        // store. PR#7 collapsed the high band into the prompt path, so
        // every classifier-driven extraction surfaces candidates instead
        // of inserting directly. The accept-then-insert path is covered
        // separately by the accept_* tests below.
        val store = TrackingStore()
        val classifier = StubClassifier(
            presenceLogits = floatArrayOf(0f, 5f), // HAS_EXTRACTION
            // PERSONAL_IDENTITY (idx 0) + PROFESSIONAL (idx 2) cross 0.5
            categoryLogits = floatArrayOf(5f, -5f, 5f, -5f, -5f, -5f),
        )
        val extractor = buildExtractor(store = store, classifier = classifier, ids = listOf("cand-1", "cand-2"))

        val report = extractor.extract(
            userMessage = "i'm a software engineer in toronto",
            assistantResponse = "got it!",
            conversationId = "conv-1",
        ) as MemoryExtractor.ExtractionReport.PromptRequested

        assertEquals(2, report.candidates.size)
        assertTrue("no inserts pre-acceptance", store.inserted.isEmpty())
        // Both share the same embedding (same user message).
        assertTrue(
            "candidates share embedding",
            report.candidates[0].embedding.contentEquals(report.candidates[1].embedding),
        )
        // Categories ordered by enum index.
        assertEquals(MemoryCategory.PERSONAL_IDENTITY, report.candidates[0].category)
        assertEquals(MemoryCategory.PROFESSIONAL, report.candidates[1].category)
        // Verbatim user-message text per Q3 / M5_PLAN.md §2.
        assertEquals("i'm a software engineer in toronto", report.candidates[0].text)
        assertEquals("conv-1", report.candidates[0].conversationId)
    }

    @Test
    fun temporary_context_candidate_gets_expiration_from_user_message() = runTest {
        val store = TrackingStore()
        val classifier = StubClassifier(
            presenceLogits = floatArrayOf(0f, 5f),
            // TEMPORARY_CONTEXT (idx 5)
            categoryLogits = floatArrayOf(-5f, -5f, -5f, -5f, -5f, 5f),
        )
        val extractor = buildExtractor(store = store, classifier = classifier)

        val report = extractor.extract(
            userMessage = "i'm traveling to tokyo next week",
            assistantResponse = "have a great trip",
            conversationId = null,
        ) as MemoryExtractor.ExtractionReport.PromptRequested

        val candidate = report.candidates.single()
        assertEquals(MemoryCategory.TEMPORARY_CONTEXT, candidate.category)
        assertNotNull("expiresAt must be set on temporary_context", candidate.expiresAtEpochMs)
        // "next week" with the test clock at 2026-05-10 → 2026-05-17.
        // We just sanity-check the offset is roughly +7 days from `now`,
        // not exact ms (TempContextDateParserTest covers exactness).
        val deltaDays = (candidate.expiresAtEpochMs!! - now) / (24L * 60 * 60 * 1_000)
        assertTrue("expected ~7 days delta, got $deltaDays", deltaDays in 5..14)
    }

    @Test
    fun temporary_context_candidate_falls_back_to_30_day_default_when_no_date() = runTest {
        val store = TrackingStore()
        val classifier = StubClassifier(
            presenceLogits = floatArrayOf(0f, 5f),
            categoryLogits = floatArrayOf(-5f, -5f, -5f, -5f, -5f, 5f),
        )
        val extractor = buildExtractor(store = store, classifier = classifier)

        val report = extractor.extract(
            userMessage = "i have a temporary thing going on",
            assistantResponse = "ok",
            conversationId = null,
        ) as MemoryExtractor.ExtractionReport.PromptRequested

        val candidate = report.candidates.single()
        val deltaDays = (candidate.expiresAtEpochMs!! - now) / (24L * 60 * 60 * 1_000)
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
    fun accept_prompt_candidate_refuses_at_hard_cap() = runTest {
        // PR#46: at the hard cap, a Save is refused (CapReached) and nothing
        // is inserted — no eviction-to-make-room. Cap is 1 here; the store
        // reports count == inserted.size, so the first accept fills it.
        val store = TrackingStore()
        val extractor = buildExtractor(
            store = store,
            config = MemoryConfig.DEFAULT.copy(maxMemories = 1),
            ids = listOf("m-1", "m-2"),
        )
        val first = candidate("first")
        val second = candidate("second")

        assertTrue(extractor.acceptPromptCandidate(first) is MemoryExtractor.AcceptOutcome.Saved)
        assertEquals(1, store.inserted.size)

        val outcome = extractor.acceptPromptCandidate(second)
        assertTrue("second save refused at cap", outcome is MemoryExtractor.AcceptOutcome.CapReached)
        assertEquals(1, (outcome as MemoryExtractor.AcceptOutcome.CapReached).limit)
        assertEquals("nothing inserted past the cap", 1, store.inserted.size)
    }

    @Test
    fun accept_prompt_candidate_dedup_takes_precedence_over_cap() = runTest {
        // A near-duplicate at the cap is reported as Deduped, not CapReached.
        val store = TrackingStore(existingMatch = stubMemory("preexisting"))
        val extractor = buildExtractor(
            store = store,
            config = MemoryConfig.DEFAULT.copy(maxMemories = 1),
        )
        val outcome = extractor.acceptPromptCandidate(candidate("dupe"))
        assertTrue(outcome is MemoryExtractor.AcceptOutcome.Deduped)
        assertTrue(store.inserted.isEmpty())
    }

    @Test
    fun remember_command_refuses_at_hard_cap() = runTest {
        // The explicit Remember path is capped too; at the cap it returns
        // CapReached and inserts nothing.
        val store = TrackingStore()
        store.inserted += stubMemory("preexisting") // count == 1
        val extractor = buildExtractor(
            store = store,
            config = MemoryConfig.DEFAULT.copy(maxMemories = 1),
        )

        val report = extractor.extract(
            userMessage = "remember that i love sushi",
            assistantResponse = "",
            conversationId = null,
        )
        assertTrue(report is MemoryExtractor.ExtractionReport.CapReached)
        assertEquals(1, (report as MemoryExtractor.ExtractionReport.CapReached).limit)
        assertEquals("nothing inserted past the cap", 1, store.inserted.size)
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

    // -- proposeLocationMemory (PR #37 weather path) -----------------------

    @Test
    fun proposeLocationMemory_proposes_personal_identity_consent_card() = runTest {
        val store = TrackingStore()
        val extractor = buildExtractor(store = store, ids = listOf("loc-1"))

        val report = extractor.proposeLocationMemory(
            locationText = "I live in Miami, Florida",
            conversationId = null,
        ) as MemoryExtractor.ExtractionReport.PromptRequested

        val candidate = report.candidates.single()
        assertEquals("loc-1", candidate.id)
        assertEquals("I live in Miami, Florida", candidate.text)
        assertEquals(MemoryCategory.PERSONAL_IDENTITY, candidate.category)
        // Consent card only — nothing is saved until the user taps Save.
        assertTrue(store.inserted.isEmpty())
    }

    @Test
    fun proposeLocationMemory_skips_when_location_already_known() = runTest {
        val store = TrackingStore(existingMatch = stubMemory("existing-loc"))
        val extractor = buildExtractor(store = store)

        // Already-known location → no re-prompt (NoOp), no insert.
        val report = extractor.proposeLocationMemory("I live in Miami, Florida", conversationId = null)

        assertSame(MemoryExtractor.ExtractionReport.NoOp, report)
        assertTrue(store.inserted.isEmpty())
    }

    @Test
    fun proposeLocationMemory_respects_creation_disabled() = runTest {
        val store = TrackingStore()
        val extractor = buildExtractor(store = store, creationEnabled = false)

        val report = extractor.proposeLocationMemory("I live in Miami, Florida", conversationId = null)

        assertSame(MemoryExtractor.ExtractionReport.SkippedDisabled, report)
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

    // -- Two-band routing (PR#7 — was three-band in PR#4) ------------------

    @Test
    fun middle_band_returns_prompt_requested_without_inserting() = runTest {
        // softmax([0, 1]) ≈ [0.27, 0.73] — above ask (0.6).
        val store = TrackingStore()
        val classifier = StubClassifier(
            presenceLogits = floatArrayOf(0f, 1f),
            categoryLogits = floatArrayOf(5f, -5f, 5f, -5f, -5f, -5f),
        )
        val extractor = buildExtractor(store = store, classifier = classifier, ids = listOf("cand-1", "cand-2"))

        val report = extractor.extract(
            userMessage = "i used to live in toronto",
            assistantResponse = "noted",
            conversationId = "conv-1",
        ) as MemoryExtractor.ExtractionReport.PromptRequested

        assertEquals(2, report.candidates.size)
        assertTrue("no inserts in middle band", store.inserted.isEmpty())
        assertEquals(setOf(MemoryCategory.PERSONAL_IDENTITY, MemoryCategory.PROFESSIONAL),
            report.candidates.map { it.category }.toSet())
        assertEquals("conv-1", report.candidates.first().conversationId)
        assertEquals("i used to live in toronto", report.candidates.first().text)
    }

    @Test
    fun low_band_silently_drops() = runTest {
        // softmax([5, -5]) ≈ [0.9999, 0.0001] — far below ask (0.6).
        val store = TrackingStore()
        val classifier = StubClassifier(
            presenceLogits = floatArrayOf(5f, -5f),
            categoryLogits = floatArrayOf(5f, -5f, -5f, -5f, -5f, -5f),
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
    fun high_confidence_classifier_still_prompts_user() = runTest {
        // softmax([0, 5]) ≈ [0.0067, 0.9933] — pre-PR#7 this auto-saved;
        // post-PR#7 every classifier-driven save must pass through the
        // user-consent card. Only explicit `remember` commands auto-save.
        val store = TrackingStore()
        val classifier = StubClassifier(
            presenceLogits = floatArrayOf(0f, 5f),
            categoryLogits = floatArrayOf(5f, -5f, -5f, -5f, -5f, -5f),
        )
        val extractor = buildExtractor(store = store, classifier = classifier, ids = listOf("cand-1"))

        val report = extractor.extract(
            userMessage = "i'm a software engineer",
            assistantResponse = "noted",
            conversationId = null,
        ) as MemoryExtractor.ExtractionReport.PromptRequested
        assertEquals(1, report.candidates.size)
        assertTrue("no inserts pre-acceptance even at high confidence", store.inserted.isEmpty())
        assertEquals(MemoryCategory.PERSONAL_IDENTITY, report.candidates.single().category)
    }

    @Test
    fun high_confidence_classifier_path_does_not_bump_auto_counter() = runTest {
        // Regression guard for PR#7: `MEMORY_EXTRACTED_AUTO_TOTAL` must
        // only count explicit Remember commands. The classifier path —
        // even at p_has ≈ 0.99 — flows through the prompt card and bumps
        // `MEMORY_PROMPT_SHOWN_TOTAL` instead.
        val store = TrackingStore()
        val counters = RecordingCounters()
        val classifier = StubClassifier(
            presenceLogits = floatArrayOf(0f, 5f),
            categoryLogits = floatArrayOf(5f, -5f, -5f, -5f, -5f, -5f),
        )
        val extractor = buildExtractor(
            store = store,
            classifier = classifier,
            counters = counters,
            ids = listOf("cand-1"),
        )

        extractor.extract(
            userMessage = "i'm a software engineer",
            assistantResponse = "noted",
            conversationId = null,
        )

        assertEquals(0L, counters.totals[CounterNames.MEMORY_EXTRACTED_AUTO_TOTAL] ?: 0L)
        assertEquals(1L, counters.totals[CounterNames.MEMORY_PROMPT_SHOWN_TOTAL])
    }

    @Test
    fun accept_prompt_candidate_inserts_into_store() = runTest {
        val store = TrackingStore()
        val classifier = StubClassifier(
            presenceLogits = floatArrayOf(0f, 1f),
            categoryLogits = floatArrayOf(5f, -5f, -5f, -5f, -5f, -5f),
        )
        val extractor = buildExtractor(store = store, classifier = classifier, ids = listOf("cand-1", "m-1"))

        val report = extractor.extract(
            userMessage = "i used to live in toronto",
            assistantResponse = "noted",
            conversationId = "conv-1",
        ) as MemoryExtractor.ExtractionReport.PromptRequested
        val candidate = report.candidates.single()

        val outcome = extractor.acceptPromptCandidate(candidate)
        assertTrue(outcome is MemoryExtractor.AcceptOutcome.Saved)
        assertEquals(1, store.inserted.size)
        assertEquals("i used to live in toronto", store.inserted.single().text)
        assertEquals(MemoryCategory.PERSONAL_IDENTITY, store.inserted.single().category)
    }

    @Test
    fun accept_prompt_candidate_dedups_at_save_time() = runTest {
        // Another memory was created between proposal and acceptance.
        val store = TrackingStore(existingMatch = stubMemory("preexisting"))
        val classifier = StubClassifier(
            presenceLogits = floatArrayOf(0f, 1f),
            categoryLogits = floatArrayOf(5f, -5f, -5f, -5f, -5f, -5f),
        )
        val extractor = buildExtractor(store = store, classifier = classifier, ids = listOf("cand-1"))

        // Bypass the proposal-time dedup branch by constructing the candidate manually.
        val candidate = MemoryPromptCandidate(
            id = "cand-1",
            text = "i used to live in toronto",
            category = MemoryCategory.PERSONAL_IDENTITY,
            embedding = FloatArray(Memory.EMBEDDING_DIM) { 0f },
            conversationId = "conv-1",
            proposedAtEpochMs = now,
            expiresAtEpochMs = null,
        )

        val result = extractor.acceptPromptCandidate(candidate)
        assertTrue(result is MemoryExtractor.AcceptOutcome.Deduped)
        assertTrue("dedup prevents insert", store.inserted.isEmpty())
    }

    @Test
    fun high_confidence_falls_back_to_argmax_category_when_none_cross_threshold() = runTest {
        // Reproduces the on-device case: presence head is confident
        // (p_has=0.95) but every category sigmoid sits below 0.5.
        // Pre-fix this dropped silently; fix surfaces a prompt under
        // argMax category. Pre-PR#7 the high band saved directly; post-PR#7
        // the same input goes through the prompt card.
        val store = TrackingStore()
        val classifier = StubClassifier(
            presenceLogits = floatArrayOf(0f, 3f), // softmax ~ [0.05, 0.95]
            // All negative logits → all sigmoid probs < 0.5. Index 1 (PREFERENCE)
            // is the largest by a hair.
            categoryLogits = floatArrayOf(-1f, -0.1f, -1f, -1f, -1f, -1f),
        )
        val extractor = buildExtractor(store = store, classifier = classifier, ids = listOf("cand-1"))

        val report = extractor.extract(
            userMessage = "i'm a software engineer",
            assistantResponse = "noted",
            conversationId = null,
        ) as MemoryExtractor.ExtractionReport.PromptRequested

        assertEquals(1, report.candidates.size)
        assertEquals(MemoryCategory.PREFERENCE, report.candidates.single().category)
        assertTrue("still no insert pre-acceptance", store.inserted.isEmpty())
    }

    @Test
    fun ask_band_also_falls_back_to_argmax_category() = runTest {
        val store = TrackingStore()
        val classifier = StubClassifier(
            presenceLogits = floatArrayOf(0f, 1f), // ~0.73 → ask band
            categoryLogits = floatArrayOf(-1f, -1f, -0.2f, -1f, -1f, -1f),
        )
        val extractor = buildExtractor(store = store, classifier = classifier, ids = listOf("cand-1"))

        val report = extractor.extract(
            userMessage = "i'm a software engineer",
            assistantResponse = "noted",
            conversationId = null,
        ) as MemoryExtractor.ExtractionReport.PromptRequested

        // Index 2 (PROFESSIONAL) wins argMax even at <0.5 sigmoid.
        assertEquals(1, report.candidates.size)
        assertEquals(MemoryCategory.PROFESSIONAL, report.candidates.single().category)
        assertTrue("still no insert pre-acceptance", store.inserted.isEmpty())
    }

    @Test
    fun question_user_message_skips_extraction_even_when_classifier_says_yes() = runTest {
        // Reproduces the bug: user asks a recall question, agent answers
        // from existing memory, classifier sees memorable content in the
        // assistant half and would otherwise save the QUESTION as a new
        // memory. QuestionDetector cuts this off before classification.
        val store = TrackingStore()
        val classifier = StubClassifier(
            // The stub would say "auto-save, preference category" if we asked.
            presenceLogits = floatArrayOf(0f, 5f),
            categoryLogits = floatArrayOf(-5f, 5f, -5f, -5f, -5f, -5f),
        )
        val extractor = buildExtractor(store = store, classifier = classifier)

        val report = extractor.extract(
            userMessage = "what is my favorite sports team",
            assistantResponse = "Your favorite team is the Toronto Blue Jays.",
            conversationId = "conv-1",
        )

        assertSame(MemoryExtractor.ExtractionReport.NoOp, report)
        assertTrue("no inserts on a recall question", store.inserted.isEmpty())
    }

    @Test
    fun dismiss_prompt_candidate_does_not_insert() = runTest {
        val store = TrackingStore()
        val extractor = buildExtractor(store = store)
        val candidate = MemoryPromptCandidate(
            id = "cand-1",
            text = "i used to live in toronto",
            category = MemoryCategory.PERSONAL_IDENTITY,
            embedding = FloatArray(Memory.EMBEDDING_DIM) { 0f },
            conversationId = null,
            proposedAtEpochMs = now,
            expiresAtEpochMs = null,
        )
        extractor.dismissPromptCandidate(candidate)
        assertTrue(store.inserted.isEmpty())
    }

    // -- Test fixtures -----------------------------------------------------

    private fun buildExtractor(
        store: MemoryStore,
        classifier: ClassifierEngine = StubClassifier(),
        embedder: EmbedderEngine = StubEmbedder(),
        config: MemoryConfig = MemoryConfig.DEFAULT,
        creationEnabled: Boolean = true,
        ids: List<String> = listOf("m-1", "m-2", "m-3", "m-4", "m-5"),
        counters: TelemetryCounters = NoOpTelemetryCounters,
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
            detector = RememberForgetDetector(),
            dateParser = parser,
            nowProvider = { now },
            configProvider = { config },
            creationEnabledProvider = { creationEnabled },
            idGenerator = { if (idIter.hasNext()) idIter.next() else "m-overflow" },
            counters = counters,
        )
    }

    /**
     * In-memory `TelemetryCounters` impl for asserting counter increments.
     * The tagged-`increment` default impl joins to `name:tag`, so a tagged
     * call lands under that joined key; PR#7 tests inspect untagged
     * counters only and don't need to disambiguate.
     */
    private class RecordingCounters : TelemetryCounters {
        val totals: MutableMap<String, Long> = mutableMapOf()
        override fun increment(name: String, by: Long) {
            totals[name] = (totals[name] ?: 0L) + by
        }
        override fun observeLatency(metric: String, durationMs: Long) = Unit
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

    private fun candidate(text: String): MemoryPromptCandidate = MemoryPromptCandidate(
        id = "cand-$text",
        text = text,
        category = MemoryCategory.PREFERENCE,
        embedding = FloatArray(Memory.EMBEDDING_DIM) { 0f },
        conversationId = "conv-1",
        proposedAtEpochMs = now,
        expiresAtEpochMs = null,
    )
}
