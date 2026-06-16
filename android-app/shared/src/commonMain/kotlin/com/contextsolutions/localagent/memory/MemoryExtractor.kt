package com.contextsolutions.localagent.memory

import com.contextsolutions.localagent.classifier.ClassifierEngine
import com.contextsolutions.localagent.classifier.ClassifierOutput
import com.contextsolutions.localagent.classifier.WordPieceTokenizer
import com.contextsolutions.localagent.classifier.internal.argMax
import com.contextsolutions.localagent.classifier.internal.sigmoid
import com.contextsolutions.localagent.classifier.internal.softmax
import com.contextsolutions.localagent.telemetry.CounterNames
import com.contextsolutions.localagent.telemetry.NoOpTelemetryCounters
import com.contextsolutions.localagent.telemetry.TelemetryCounters
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Post-turn memory extraction (M5 Phase D, PRD §3.2.4). Runs in a
 * background coroutine after [com.contextsolutions.localagent.agent.AgentEvent.Done]
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
 *    in M5_PLAN.md §2. Explicit commands always save / forget immediately;
 *    the prompt-card flow below only applies to the classifier path.
 *
 * 2. **Classifier path with two-band routing.** Pair-encode
 *    `[CLS] userMessage [SEP] assistantResponse [SEP]` via
 *    [WordPieceTokenizer.encodePair] and read the classifier's
 *    `presence` + `category` heads in one forward pass.
 *
 *    `p_has_extraction = softmax(presenceLogits)[HAS_EXTRACTION]` is then
 *    routed against [MemoryConfig.thresholds]:
 *
 *      - `>= ask`  → return [ExtractionReport.PromptRequested] with
 *        one [MemoryPromptCandidate] per active category. The UI surfaces
 *        a Save / Dismiss card per candidate; nothing is written to the
 *        store until [acceptPromptCandidate] is called.
 *      - otherwise → silent skip
 *
 *    PR#7 dropped the high-band auto-save: every classifier-driven save
 *    now requires explicit user consent via the prompt card. Only the
 *    Remember command path above bypasses the card.
 *
 *    The active-category set is the multi-label `category` sigmoid above
 *    [MemoryThresholds.category] (default 0.5).
 *
 * **Candidate text (v1 simple).** The memory `text` is the verbatim user
 * message — Q3 in M5_PLAN.md §2. v1.x replaces with Gemma-generated
 * canonical sentences. The category prefix in [PromptAssembler.renderMemoryBlock]
 * makes the rough text legible to Gemma.
 *
 * **Dedup.** Each candidate is embedded once; the embedding seeds both
 * `findCosineMatch` (skip if cosine > 0.85 against an existing row) and
 * the row that actually gets persisted. For the prompt-card flow, dedup
 * runs TWICE: once at proposal time (so we don't ask the user to save
 * something we already remember) and again at [acceptPromptCandidate]
 * time (in case another turn added a near-match between Show and Save).
 *
 * **Hard cap.** The store is bounded by a hard cap ([MemoryConfig.maxMemories],
 * default 100) enforced at save time: when the non-expired count is already at
 * the cap, a save is *refused* (the UI surfaces a "limit reached" dialog) rather
 * than evicting an existing memory to make room. Memories are only ever added or
 * removed by explicit user action — there is no automatic eviction.
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
    private val detector: RememberForgetDetector,
    private val dateParser: TempContextDateParser,
    private val nowProvider: () -> Long,
    private val configProvider: () -> MemoryConfig = { MemoryConfig.DEFAULT },
    private val creationEnabledProvider: () -> Boolean = { true },
    private val questionDetector: QuestionDetector = QuestionDetector(),
    private val idGenerator: () -> String = { "mem-${Uuid.random()}" },
    private val logger: (String) -> Unit = {},
    private val counters: TelemetryCounters = NoOpTelemetryCounters,
) {

    /**
     * Run extraction on a completed turn. Always returns; never throws.
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

    /**
     * Persist a middle-band candidate after the user tapped Save. Runs
     * dedup again because another turn may have inserted a near-match
     * between proposal time and acceptance.
     *
     * The hard cap is checked *after* dedup, so saving something we already
     * remember is reported as [AcceptOutcome.Deduped] rather than a false
     * [AcceptOutcome.CapReached].
     */
    suspend fun acceptPromptCandidate(candidate: MemoryPromptCandidate): AcceptOutcome {
        return try {
            val now = nowProvider()
            val existing = store.findCosineMatch(candidate.embedding, now = now)
            if (existing != null) {
                counters.increment(CounterNames.MEMORY_DEDUP_SKIPPED_TOTAL)
                counters.increment(CounterNames.MEMORY_PROMPT_ACCEPTED_TOTAL)
                logger("[memory-extract] accept candidate=${candidate.id} deduped=${existing.id}")
                return AcceptOutcome.Deduped
            }
            val limit = configProvider().maxMemories
            if (store.count(now) >= limit) {
                counters.increment(CounterNames.MEMORY_CAP_REACHED_TOTAL)
                logger("[memory-extract] accept candidate=${candidate.id} cap-reached limit=$limit")
                return AcceptOutcome.CapReached(limit)
            }
            val memory = candidate.toMemory(id = idGenerator(), now = now)
            store.insert(memory)
            counters.increment(CounterNames.MEMORY_EXTRACTED_TOTAL)
            counters.increment(CounterNames.MEMORY_EXTRACTED_PROMPTED_TOTAL)
            counters.increment(CounterNames.MEMORY_PROMPT_ACCEPTED_TOTAL)
            logger("[memory-extract] accept candidate=${candidate.id} inserted=${memory.id}")
            AcceptOutcome.Saved(memory)
        } catch (t: Throwable) {
            logger("[memory-extract] accept candidate=${candidate.id} failed: ${t.message}")
            AcceptOutcome.Failed
        }
    }

    /**
     * Propose remembering the user's location, stated in a weather query
     * (PR #37). The deterministic weather path resolves a city + state/province
     * from the user's own words and calls this so a later bare "what's the
     * weather?" can reuse it. Returns a [ExtractionReport.PromptRequested] with
     * a single [MemoryCategory.PERSONAL_IDENTITY] candidate — surfaced as the
     * same Save / Dismiss consent card the classifier path uses (post-PR#7,
     * nothing auto-saves except explicit "Remember:" commands). Dedups first:
     * if the location is already known, returns [ExtractionReport.NoOp] so the
     * user isn't re-prompted on every weather query for the same place.
     * Honours the creation toggle. Always returns; never throws.
     */
    suspend fun proposeLocationMemory(locationText: String, conversationId: String?): ExtractionReport {
        if (!creationEnabledProvider()) return ExtractionReport.SkippedDisabled
        val text = locationText.trim()
        if (text.isEmpty()) return ExtractionReport.NoOp
        return try {
            val now = nowProvider()
            val embedding = embedder.embed(text)?.vector
                ?: return ExtractionReport.SkippedNoEmbedder
            val existingMatch = store.findCosineMatch(embedding, now = now)
            if (existingMatch != null) {
                counters.increment(CounterNames.MEMORY_DEDUP_SKIPPED_TOTAL)
                logger("[memory-extract] proposeLocation deduped=${existingMatch.id}")
                return ExtractionReport.NoOp
            }
            val candidate = MemoryPromptCandidate(
                id = idGenerator(),
                text = text,
                category = MemoryCategory.PERSONAL_IDENTITY,
                embedding = embedding,
                conversationId = conversationId,
                proposedAtEpochMs = now,
                expiresAtEpochMs = null,
            )
            counters.increment(CounterNames.MEMORY_PROMPT_SHOWN_TOTAL)
            logger("[memory-extract] proposeLocation candidate=${candidate.id}")
            ExtractionReport.PromptRequested(listOf(candidate))
        } catch (t: Throwable) {
            logger("[memory-extract] proposeLocation failed: ${t.message}")
            ExtractionReport.Errored(t.message ?: t::class.simpleName ?: "unknown")
        }
    }

    /** User tapped Dismiss (or a new turn auto-dismissed the card). */
    fun dismissPromptCandidate(candidate: MemoryPromptCandidate) {
        counters.increment(CounterNames.MEMORY_PROMPT_DISMISSED_TOTAL)
        logger("[memory-extract] dismiss candidate=${candidate.id}")
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

        val limit = configProvider().maxMemories
        if (store.count(now) >= limit) {
            counters.increment(CounterNames.MEMORY_CAP_REACHED_TOTAL)
            logger("[memory-extract] command=Remember cap-reached limit=$limit")
            return ExtractionReport.CapReached(limit)
        }

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
        counters.increment(CounterNames.MEMORY_EXTRACTED_AUTO_TOTAL, by = created.size.toLong())
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

        // Guard against the assistant-echoes-a-fact pattern: when the user
        // asks a recall question and the agent answers from an existing
        // memory, the pair classifier sees the memorable content in the
        // assistant half and would otherwise save the QUESTION as a new
        // memory (since memory text = user message). Skip these
        // deterministically — explicit Remember/Forget commands ran above
        // this branch so they're unaffected.
        if (questionDetector.isQuestionOrRecall(userMessage)) {
            logger("[memory-extract] presence=skip-question")
            return ExtractionReport.NoOp
        }

        val tokenized = tokenizer.encodePair(userMessage, assistantResponse)
        val output = classifier.classify(tokenized.inputIds, tokenized.attentionMask)
            ?: return ExtractionReport.SkippedNoClassifier

        val thresholds = configProvider().thresholds
        val presenceProbs = softmax(output.presenceLogits)
        val pHas = presenceProbs[ClassifierOutput.PRESENCE_INDEX_HAS_EXTRACTION]
        // Cheap pre-formatted threshold tag so every band log line is grep-able
        // with the same suffix shape (e.g. `adb logcat -s MemoryExtractor:I |
        // grep '\[memory-extract\] presence='`). Per inv. #27 / #28, log
        // numbers / counts only — never the memory text itself.
        val bandTag = "p_has=${pHas.formatProb()} ask=${thresholds.ask.formatProb()}"

        // Below `ask` — silent skip. No card, no insert.
        if (pHas < thresholds.ask) {
            logger("[memory-extract] presence=skip $bandTag")
            return ExtractionReport.NoOp
        }

        // Category gate. Default path: every sigmoid prob > `category` is an
        // active category. Fallback: if presence crossed `ask` but no
        // category did, take argMax of the category logits. Mirrors how an
        // explicit "remember" command always settles on a single category —
        // we'd rather pick the model's most-confident bucket than silently
        // drop a confident-presence turn.
        val categoryProbs = sigmoid(output.categoryLogits)
        val activeCategoriesByThreshold = activeCategoriesIn(categoryProbs, thresholds.category)
        val (activeCategories, categoryTag) = if (activeCategoriesByThreshold.isNotEmpty()) {
            activeCategoriesByThreshold to "categories=${activeCategoriesByThreshold.size}"
        } else {
            val topIdx = argMax(categoryProbs)
            val topCat = MemoryCategory.fromCategoryIndex(topIdx)
            if (topCat == null) {
                logger("[memory-extract] presence=ask $bandTag categories=0 (no valid argMax)")
                return ExtractionReport.NoOp
            }
            setOf(topCat) to "categories=0 fallback=${topCat.wireName}@${categoryProbs[topIdx].formatProb()}"
        }

        val embedding = embedder.embed(userMessage)?.vector
            ?: return ExtractionReport.SkippedNoEmbedder

        val now = nowProvider()
        val existing = store.findCosineMatch(embedding, now = now)
        if (existing != null) {
            counters.increment(CounterNames.MEMORY_DEDUP_SKIPPED_TOTAL)
            logger("[memory-extract] presence=dedup $bandTag dedupedId=${existing.id}")
            return ExtractionReport.Created(emptyList(), deduped = listOf(existing.id))
        }

        // Post-PR#7: every classifier-path save above `ask` flows through a
        // user-consent card. No high-band auto-save branch — explicit
        // Remember commands are the only auto-save path.
        val candidates = activeCategories.map { category ->
            MemoryPromptCandidate(
                id = idGenerator(),
                text = userMessage,
                category = category,
                embedding = embedding,
                conversationId = conversationId,
                proposedAtEpochMs = now,
                expiresAtEpochMs = if (category == MemoryCategory.TEMPORARY_CONTEXT) {
                    dateParser.parse(userMessage) ?: (now + DEFAULT_TEMP_CONTEXT_EXPIRY_MS)
                } else {
                    null
                },
            )
        }
        counters.increment(CounterNames.MEMORY_PROMPT_SHOWN_TOTAL, by = candidates.size.toLong())
        logger("[memory-extract] presence=ask $bandTag $categoryTag candidates=${candidates.size}")
        return ExtractionReport.PromptRequested(candidates)
    }

    /**
     * Two-decimal probability formatter for logcat. Avoids `String.format`
     * (not on KMP commonMain) and `kotlin.text.format` (Kotlin 2.x preview).
     */
    private fun Float.formatProb(): String {
        val rounded = (this * 100f).toInt() // 0..100
        val whole = rounded / 100
        val frac = rounded % 100
        return if (frac < 10) "$whole.0$frac" else "$whole.$frac"
    }

    // -- Helpers -------------------------------------------------------------

    private suspend fun classifyCategories(text: String): Set<MemoryCategory>? {
        val tokenized = tokenizer.encodeSingle(text)
        val output = classifier.classify(tokenized.inputIds, tokenized.attentionMask) ?: return null
        return activeCategoriesOf(output.categoryLogits, configProvider().thresholds.category)
            .ifEmpty { null }
    }

    private fun activeCategoriesOf(categoryLogits: FloatArray, threshold: Float): Set<MemoryCategory> =
        activeCategoriesIn(sigmoid(categoryLogits), threshold)

    private fun activeCategoriesIn(categoryProbs: FloatArray, threshold: Float): Set<MemoryCategory> {
        val out = mutableSetOf<MemoryCategory>()
        for (i in categoryProbs.indices) {
            if (categoryProbs[i] > threshold) {
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
        data class PromptRequested(val candidates: List<MemoryPromptCandidate>) : ExtractionReport

        /** The store is at the hard cap ([MemoryConfig.maxMemories]); nothing was saved. */
        data class CapReached(val limit: Int) : ExtractionReport
        data class Errored(val reason: String) : ExtractionReport
    }

    /** Outcome of an [acceptPromptCandidate] call (user tapped Save on a card). */
    sealed interface AcceptOutcome {
        data class Saved(val memory: Memory) : AcceptOutcome
        data object Deduped : AcceptOutcome

        /** The store is at the hard cap; the candidate was NOT saved. */
        data class CapReached(val limit: Int) : AcceptOutcome
        data object Failed : AcceptOutcome
    }

    companion object {
        /**
         * Legacy hard-coded category cutoff. New callers should source
         * [MemoryThresholds.category] from [MemoryConfig] instead — kept
         * here so existing tests that don't supply a config keep building.
         */
        const val CATEGORY_THRESHOLD: Float = 0.5f

        /** Q5 fallback in M5_PLAN.md §2 — when [TempContextDateParser] returns null. */
        const val DEFAULT_TEMP_CONTEXT_EXPIRY_MS: Long = 30L * 24 * 60 * 60 * 1_000  // 30 days
    }
}
