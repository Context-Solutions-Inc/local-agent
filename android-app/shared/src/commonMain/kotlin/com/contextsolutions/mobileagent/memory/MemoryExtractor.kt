package com.contextsolutions.mobileagent.memory

import com.contextsolutions.mobileagent.classifier.ClassifierEngine
import com.contextsolutions.mobileagent.classifier.ClassifierOutput
import com.contextsolutions.mobileagent.classifier.WordPieceTokenizer
import com.contextsolutions.mobileagent.classifier.internal.argMax
import com.contextsolutions.mobileagent.classifier.internal.sigmoid
import com.contextsolutions.mobileagent.telemetry.CounterNames
import com.contextsolutions.mobileagent.telemetry.NoOpTelemetryCounters
import com.contextsolutions.mobileagent.telemetry.TelemetryCounters
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Post-turn memory extraction (M5 Phase D, PRD §3.2.4). Runs in a
 * background coroutine after [com.contextsolutions.mobileagent.agent.AgentEvent.Done]
 * — failure here MUST NEVER affect the user-facing turn.
 *
 * **Two paths.**
 *
 * 1. **Explicit command** (via [RememberForgetDetector]). "remember that
 *    I'm allergic to peanuts" force-creates a memory regardless of the
 *    classifier's verdict. "forget what I said about my job" embeds the
 *    payload and `deleteByCosine`s the closest match (>0.85). This
 *    bypass exists because the shipped classifier folds explicit-remember
 *    /-forget into `presence` rather than exposing a dedicated head — Q2
 *    in M5_PLAN.md §2.
 *
 * 2. **Classifier path.** Pair-encode `[CLS] userMessage [SEP] assistantResponse [SEP]`
 *    via [WordPieceTokenizer.encodePair] and read the classifier's
 *    `presence` + `category` heads in one forward pass. If `argMax(presence)`
 *    is `HAS_EXTRACTION`, walk the multi-label `category` sigmoid and
 *    create one memory per active category (>0.5 threshold).
 *
 * **Candidate text (v1 simple).** The memory `text` is the verbatim user
 * message — Q3 in M5_PLAN.md §2. v1.x replaces with Gemma-generated
 * canonical sentences. The category prefix in [PromptAssembler.renderMemoryBlock]
 * makes the rough text legible to Gemma.
 *
 * **Dedup.** Each candidate is embedded once; the embedding seeds both
 * `findCosineMatch` (skip if cosine > 0.85 against an existing row) and
 * the row that actually gets persisted.
 *
 * **Eviction.** [evictor.maybeEvict] runs once per extraction call before
 * any inserts so a burst-of-categories turn doesn't temporarily push
 * count past [MemoryEvictor.DEFAULT_CAPACITY].
 *
 * **Telemetry exclusion (PRD §4.4 + WS-12).** The injected [logger] emits
 * counts, IDs, and exception classes only — never memory text, user
 * messages, or assistant responses. Audited 2026-05-10 (M5_PLAN.md §7).
 * If the M6 WS-13 opt-in telemetry pipeline ever bridges to this logger,
 * sanitize at the bridge.
 */
@OptIn(ExperimentalUuidApi::class)
class MemoryExtractor(
    private val classifier: ClassifierEngine,
    private val tokenizer: WordPieceTokenizer,
    private val embedder: EmbedderEngine,
    private val store: MemoryStore,
    private val evictor: MemoryEvictor,
    private val detector: RememberForgetDetector,
    private val dateParser: TempContextDateParser,
    private val nowProvider: () -> Long,
    private val creationEnabledProvider: () -> Boolean = { true },
    private val idGenerator: () -> String = { "mem-${Uuid.random()}" },
    private val logger: (String) -> Unit = {},
    private val counters: TelemetryCounters = NoOpTelemetryCounters,
) {

    /**
     * Run extraction on a completed turn. Always returns; never throws.
     *
     * @return a small report enumerating what (if anything) changed.
     *   Useful for logging + tests; not part of the production code path.
     */
    suspend fun extract(
        userMessage: String,
        assistantResponse: String,
        conversationId: String?,
    ): ExtractionReport {
        if (!creationEnabledProvider()) {
            counters.increment(CounterNames.MEMORY_CREATION_DISABLED_TOTAL)
            return ExtractionReport.SkippedDisabled
        }
        val trimmedUser = userMessage.trim()
        if (trimmedUser.isEmpty()) return ExtractionReport.NoOp

        return try {
            when (val command = detector.classify(trimmedUser)) {
                is RememberForgetDetector.Command.Remember -> handleRemember(
                    payload = command.payload,
                    conversationId = conversationId,
                )
                is RememberForgetDetector.Command.Forget -> handleForget(command.payload)
                RememberForgetDetector.Command.None -> handleClassifierPath(
                    userMessage = trimmedUser,
                    assistantResponse = assistantResponse,
                    conversationId = conversationId,
                )
            }
        } catch (t: Throwable) {
            logger("[memory-extract] unhandled failure: ${t.message}")
            ExtractionReport.Errored(t.message ?: t::class.simpleName ?: "unknown")
        }
    }

    // -- Remember path -------------------------------------------------------

    private suspend fun handleRemember(
        payload: String,
        conversationId: String?,
    ): ExtractionReport {
        val now = nowProvider()
        // Force-extract: classify the payload to pick a category but
        // ignore the presence verdict.
        val categories = classifyCategories(payload)
            ?: setOf(MemoryCategory.PREFERENCE) // safe default if classifier is down
        val embedding = embedder.embed(payload)?.vector
            ?: return ExtractionReport.SkippedNoEmbedder

        // Dedup against existing memories — even an explicit command
        // shouldn't create duplicates. If a near-match exists, treat the
        // command as a no-op (the user already told us this).
        val existingMatch = store.findCosineMatch(embedding, now = now)
        if (existingMatch != null) {
            counters.increment(CounterNames.MEMORY_DEDUP_SKIPPED_TOTAL)
            return ExtractionReport.Created(
                emptyList(),
                deduped = listOf(existingMatch.id),
            )
        }

        evictor.maybeEvict(store, now)
        val created = mutableListOf<Memory>()
        for (category in categories) {
            val memory = buildMemory(
                text = payload,
                category = category,
                embedding = embedding,
                conversationId = conversationId,
                now = now,
            )
            store.insert(memory)
            created += memory
        }
        counters.increment(CounterNames.MEMORY_EXTRACTED_TOTAL, by = created.size.toLong())
        logger("[memory-extract] command=Remember created=${created.size}")
        return ExtractionReport.Created(created.map { it.id }, deduped = emptyList())
    }

    // -- Forget path ---------------------------------------------------------

    private suspend fun handleForget(payload: String): ExtractionReport {
        val now = nowProvider()
        val embedding = embedder.embed(payload)?.vector
            ?: return ExtractionReport.SkippedNoEmbedder
        // Forget is retrieval-shaped, not dedup-shaped — the user typically
        // names the memory loosely ("ice cream", "my job") rather than
        // re-stating it verbatim. The dedup threshold (0.85) is too strict
        // for partial-text overlaps; PRD §3.2.4's *retrieval* threshold
        // (0.5) is the right floor here. v1.x can expose this as a tunable
        // if false-deletes show up in telemetry.
        val deleted = store.deleteByCosine(
            embedding = embedding,
            threshold = MemoryStore.DEFAULT_RETRIEVAL_THRESHOLD,
            now = now,
        )
        if (deleted != null) {
            counters.increment(CounterNames.MEMORY_FORGOTTEN_TOTAL)
            logger("[memory-extract] command=Forget deleted=${deleted.id}")
        }
        return ExtractionReport.Forgot(deletedId = deleted?.id)
    }

    // -- Classifier path -----------------------------------------------------

    private suspend fun handleClassifierPath(
        userMessage: String,
        assistantResponse: String,
        conversationId: String?,
    ): ExtractionReport {
        if (assistantResponse.isBlank()) return ExtractionReport.NoOp

        val tokenized = tokenizer.encodePair(userMessage, assistantResponse)
        val output = classifier.classify(tokenized.inputIds, tokenized.attentionMask)
            ?: return ExtractionReport.SkippedNoClassifier

        val presenceIdx = argMax(output.presenceLogits)
        if (presenceIdx == ClassifierOutput.PRESENCE_INDEX_NO_EXTRACTION) {
            return ExtractionReport.NoOp
        }

        val activeCategories = activeCategoriesOf(output.categoryLogits)
        if (activeCategories.isEmpty()) return ExtractionReport.NoOp

        val embedding = embedder.embed(userMessage)?.vector
            ?: return ExtractionReport.SkippedNoEmbedder

        val now = nowProvider()
        val existing = store.findCosineMatch(embedding, now = now)
        if (existing != null) {
            counters.increment(CounterNames.MEMORY_DEDUP_SKIPPED_TOTAL)
            return ExtractionReport.Created(emptyList(), deduped = listOf(existing.id))
        }

        evictor.maybeEvict(store, now)
        val created = mutableListOf<Memory>()
        for (category in activeCategories) {
            val memory = buildMemory(
                text = userMessage,
                category = category,
                embedding = embedding,
                conversationId = conversationId,
                now = now,
            )
            store.insert(memory)
            created += memory
        }
        counters.increment(CounterNames.MEMORY_EXTRACTED_TOTAL, by = created.size.toLong())
        logger("[memory-extract] presence=has categories=${activeCategories.size} created=${created.size}")
        return ExtractionReport.Created(created.map { it.id }, deduped = emptyList())
    }

    // -- Helpers -------------------------------------------------------------

    private suspend fun classifyCategories(text: String): Set<MemoryCategory>? {
        val tokenized = tokenizer.encodeSingle(text)
        val output = classifier.classify(tokenized.inputIds, tokenized.attentionMask) ?: return null
        return activeCategoriesOf(output.categoryLogits).ifEmpty { null }
    }

    private fun activeCategoriesOf(categoryLogits: FloatArray): Set<MemoryCategory> {
        val probs = sigmoid(categoryLogits)
        val out = mutableSetOf<MemoryCategory>()
        for (i in probs.indices) {
            if (probs[i] > CATEGORY_THRESHOLD) {
                MemoryCategory.fromCategoryIndex(i)?.let(out::add)
            }
        }
        return out
    }

    private fun buildMemory(
        text: String,
        category: MemoryCategory,
        embedding: FloatArray,
        conversationId: String?,
        now: Long,
    ): Memory {
        val expiresAt = if (category == MemoryCategory.TEMPORARY_CONTEXT) {
            dateParser.parse(text) ?: (now + DEFAULT_TEMP_CONTEXT_EXPIRY_MS)
        } else {
            null
        }
        return Memory(
            id = idGenerator(),
            text = text,
            category = category,
            conversationId = conversationId,
            createdAtEpochMs = now,
            lastAccessedEpochMs = now,
            accessCount = 0,
            embedding = embedding,
            expiresAtEpochMs = expiresAt,
        )
    }

    /** Result of an extraction attempt. Used for logging + test assertions. */
    sealed interface ExtractionReport {
        data object NoOp : ExtractionReport
        data object SkippedDisabled : ExtractionReport
        data object SkippedNoClassifier : ExtractionReport
        data object SkippedNoEmbedder : ExtractionReport
        data class Forgot(val deletedId: String?) : ExtractionReport
        data class Created(val createdIds: List<String>, val deduped: List<String>) : ExtractionReport
        data class Errored(val reason: String) : ExtractionReport
    }

    companion object {
        /** Multi-label sigmoid threshold per M3 / model card §threshold defaults. */
        const val CATEGORY_THRESHOLD: Float = 0.5f

        /** Q5 fallback in M5_PLAN.md §2 — when [TempContextDateParser] returns null. */
        const val DEFAULT_TEMP_CONTEXT_EXPIRY_MS: Long = 30L * 24 * 60 * 60 * 1_000  // 30 days
    }
}
