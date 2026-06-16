package com.contextsolutions.localagent.canonical

import com.contextsolutions.localagent.agent.ChatMessage
import com.contextsolutions.localagent.agent.PromptAssembler
import com.contextsolutions.localagent.agent.TimeContext
import com.contextsolutions.localagent.classifier.ClassifierAccelerator
import com.contextsolutions.localagent.classifier.ClassifierEngine
import com.contextsolutions.localagent.classifier.ClassifierOutput
import com.contextsolutions.localagent.classifier.PreflightConfig
import com.contextsolutions.localagent.classifier.PreflightDecision
import com.contextsolutions.localagent.classifier.PreflightRouter
import com.contextsolutions.localagent.classifier.QueryRewriter
import com.contextsolutions.localagent.classifier.Vocab
import com.contextsolutions.localagent.classifier.WordPieceTokenizer
import com.contextsolutions.localagent.memory.Memory
import com.contextsolutions.localagent.memory.MemoryCategory
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M6 Phase F — canonical routing + prompt-assembly eval gate.
 *
 * Catches regressions in:
 *   * [PreflightRouter] band thresholds + decision logic.
 *   * [QueryRewriter] date / possessive substitution.
 *   * [PromptAssembler] system-prompt structure (memory block,
 *     pre-flight notice, tool list, behavior guidelines).
 *
 * **What this DOES NOT cover:** classifier numerical accuracy. That's
 * the responsibility of `ct-regression-check` (Python-side, runs against
 * the v1.0 regression JSONL). Phase F's canonical eval feeds the
 * classifier hardcoded logits per query so the routing layer can be
 * tested independently of the classifier model.
 *
 * **Schema.** Each [CanonicalQuery] declares the query text, memory
 * seed, expected classifier output (as raw logits), and expected
 * outcomes (band, rewritten query tokens, prompt blocks present).
 *
 * **Failure mode.** The test iterates the whole canonical set and
 * surfaces ALL failures in one report. Bugs in PromptAssembler etc.
 * typically affect multiple queries at once; one assertion per query
 * would mean fixing them one at a time on each iteration.
 *
 * Locked into M6 Phase F (2026-05-11). The corresponding GHA workflow
 * is `.github/workflows/prompt-eval-gate.yml`. The canonical set
 * version lives in [CANONICAL_VERSION]; bumping it requires updating
 * `eval/canonical/README.md`.
 */
@OptIn(ExperimentalUuidApi::class)
class CanonicalEvalTest {

    private val timeContext = TimeContext(
        // Frozen for reproducibility — "yesterday" always resolves to
        // 2026-05-09, "last night" to 2026-05-09, etc.
        now = LocalDateTime(2026, 5, 10, 14, 32),
        timeZoneId = "America/Toronto",
        timeZoneAbbreviation = "EDT",
        utcOffset = "-04:00",
    )
    private val rewriter = QueryRewriter { timeContext }
    private val config = PreflightConfig.DEFAULT
    private val assembler = PromptAssembler(timeContextProvider = { timeContext })
    private val stubTokenizer = buildStubTokenizer()

    @Test
    fun canonical_set_passes() = runTest {
        val failures = mutableListOf<String>()
        for (q in CANONICAL_SET) {
            val router = PreflightRouter(
                engine = fixedEngine(q.classifierLogits),
                tokenizer = stubTokenizer,
                rewriter = rewriter,
                configProvider = { config },
                searchAvailableProvider = { q.searchAvailable },
            )
            val decision = router.route(q.query, q.memorySeed)
            failures += checkBand(q, decision)
            failures += checkRewrittenQuery(q, decision)
            failures += checkPromptStructure(q, decision)
        }
        assertTrue(
            "Canonical eval found ${failures.size} regression(s):\n" +
                failures.joinToString("\n").prependIndent("  "),
            failures.isEmpty(),
        )
    }

    // -- Assertions per canonical query --------------------------------------

    private fun checkBand(q: CanonicalQuery, decision: PreflightDecision): List<String> {
        val actual = decision.bandName()
        return if (actual == q.expectedBand) emptyList()
        else listOf("[${q.id}] expected band=${q.expectedBand}, got $actual (decision=$decision)")
    }

    private fun checkRewrittenQuery(q: CanonicalQuery, decision: PreflightDecision): List<String> {
        if (q.expectedRewrittenContains.isEmpty()) return emptyList()
        if (decision !is PreflightDecision.FireSearch) {
            return listOf(
                "[${q.id}] expectedRewrittenContains was set but decision wasn't FireSearch ($decision)",
            )
        }
        val rewritten = decision.rewrittenQuery.lowercase()
        return q.expectedRewrittenContains
            .filterNot { token -> rewritten.contains(token.lowercase()) }
            .map { token -> "[${q.id}] rewritten='${decision.rewrittenQuery}' missing expected token '$token'" }
    }

    private fun checkPromptStructure(q: CanonicalQuery, decision: PreflightDecision): List<String> {
        // FireSearch turns inject a `[SEARCH CONTEXT]` block; we synthesize a
        // placeholder here since the canonical eval doesn't actually run a
        // search (it's a pure prompt-assembly test).
        val searchContext = if (decision is PreflightDecision.FireSearch) {
            "[SEARCH CONTEXT]\nquery: ${decision.rewrittenQuery}\n[/SEARCH CONTEXT]"
        } else null
        val prompt = assembler.assembleStructured(
            history = listOf(ChatMessage.User(q.query)),
            memoryBlock = PromptAssembler.renderMemoryBlock(q.memorySeed),
            searchContext = searchContext,
            searchAvailable = q.searchAvailable,
        )

        // The [SEARCH CONTEXT] block now rides on the current user turn rather
        // than the system instruction (PR #39 recency fix), so detect prompt
        // blocks against the full assembled prompt — system instruction plus
        // the tail user message. Memory/tool/guideline blocks still live in the
        // system instruction; the search-context block lives in the user turn.
        val sys = prompt.systemInstruction + "\n" + (prompt.history.lastOrNull()?.text ?: "")
        val failures = mutableListOf<String>()

        for (block in q.expectedPromptBlocks) {
            if (!block.contains(sys, q)) {
                failures += "[${q.id}] expected prompt block ${block.name} missing"
            }
        }
        for (block in q.forbiddenPromptBlocks) {
            if (block.contains(sys, q)) {
                failures += "[${q.id}] forbidden prompt block ${block.name} unexpectedly present"
            }
        }
        return failures
    }

    private fun PreflightDecision.bandName(): String = when (this) {
        is PreflightDecision.FireSearch -> "HighFireSearch"
        is PreflightDecision.SkipSearch -> "LowSkipSearch"
        is PreflightDecision.FallThrough -> "FallThrough_${reason.name}"
        is PreflightDecision.SearchDisabled -> "SearchDisabled"
    }

    // -- Fakes ---------------------------------------------------------------

    private fun fixedEngine(logits: FloatArray?) = object : ClassifierEngine {
        override val isLoaded: Boolean = logits != null
        override suspend fun warmUp(): ClassifierAccelerator? = ClassifierAccelerator.CPU
        override suspend fun classify(inputIds: LongArray, attentionMask: LongArray): ClassifierOutput? =
            logits?.let {
                ClassifierOutput(
                    preflightLogits = it,
                    presenceLogits = floatArrayOf(0f, 0f),
                    categoryLogits = FloatArray(6),
                )
            }
        override suspend fun unload() = Unit
    }

    private fun buildStubTokenizer(): WordPieceTokenizer {
        val tokens = mutableMapOf(
            "[PAD]" to 0,
            "[UNK]" to 100,
            "[CLS]" to 101,
            "[SEP]" to 102,
        )
        return WordPieceTokenizer(Vocab(tokens, tokens.entries.associate { (k, v) -> v to k }))
    }

    companion object {
        /** Bump when the canonical set changes. Documented in `eval/canonical/README.md`. */
        const val CANONICAL_VERSION = "v1.0.0"
    }

    // ============================================================================
    // Canonical set
    // ============================================================================
    //
    // Coverage targets:
    //   - Pre-flight high band (web-search clearly needed): 4 queries
    //   - Pre-flight middle band (Gemma decides): 3 queries
    //   - Pre-flight low band (settled history / definition / coding / math): 4
    //   - Memory-conditional rewriting: 3 queries (with + without seed)
    //   - Rewriter abort (memory-context query, no seed): 1
    //   - Search disabled: 1
    //   - Prompt block presence: spot-checked in every query

    private val CANONICAL_SET: List<CanonicalQuery> = listOf(
        // ── High band — direct search ─────────────────────────────────────
        CanonicalQuery(
            id = "high_sports_recent",
            description = "Recent sports result; should fire search + substitute the date relative.",
            query = "did the eagles win yesterday",
            classifierLogits = HIGH_BAND_LOGITS,
            expectedBand = "HighFireSearch",
            expectedRewrittenContains = listOf("eagles", "2026-05-09"),
            expectedPromptBlocks = setOf(PromptBlock.PreflightNotice, PromptBlock.ToolDefinition, PromptBlock.CitationGuideline),
        ),
        CanonicalQuery(
            id = "high_markets_today",
            description = "Markets query with 'today' should fire search + substitute date.",
            query = "what is apple stock at today",
            classifierLogits = HIGH_BAND_LOGITS,
            expectedBand = "HighFireSearch",
            expectedRewrittenContains = listOf("apple", "2026-05-10"),
            expectedPromptBlocks = setOf(PromptBlock.PreflightNotice, PromptBlock.ToolDefinition),
        ),
        CanonicalQuery(
            id = "high_news_event",
            description = "Generic news/current-events query; no relative date — passes through verbatim.",
            query = "what is happening with the fed",
            classifierLogits = HIGH_BAND_LOGITS,
            expectedBand = "HighFireSearch",
            expectedRewrittenContains = listOf("fed"),
            expectedPromptBlocks = setOf(PromptBlock.PreflightNotice, PromptBlock.ToolDefinition),
        ),
        CanonicalQuery(
            id = "high_weather_today",
            description = "Weather query with 'today' substitutes the date. ('now' is intentionally not in the rewriter rule table — see QueryRewriter.applyDateTimeSubstitutions.)",
            query = "weather in seattle today",
            classifierLogits = HIGH_BAND_LOGITS,
            expectedBand = "HighFireSearch",
            expectedRewrittenContains = listOf("seattle", "2026-05-10"),
        ),

        // ── Middle band — Gemma decides via tool-calling ──────────────────
        CanonicalQuery(
            id = "middle_ambiguous_recommendation",
            description = "Ambiguous query — Gemma might or might not search.",
            query = "what's a good restaurant for italian food",
            classifierLogits = MIDDLE_BAND_LOGITS,
            expectedBand = "FallThrough_MiddleBand",
            expectedPromptBlocks = setOf(PromptBlock.ToolDefinition),
            forbiddenPromptBlocks = setOf(PromptBlock.PreflightNotice),
        ),
        CanonicalQuery(
            id = "middle_question_about_company",
            description = "Could be settled history or could be recent; let Gemma decide.",
            query = "what does anthropic do",
            classifierLogits = MIDDLE_BAND_LOGITS,
            expectedBand = "FallThrough_MiddleBand",
            forbiddenPromptBlocks = setOf(PromptBlock.PreflightNotice),
        ),
        CanonicalQuery(
            id = "middle_personal_advice",
            description = "Subjective + maybe needs recency; middle.",
            query = "should i upgrade to the latest iphone",
            classifierLogits = MIDDLE_BAND_LOGITS,
            expectedBand = "FallThrough_MiddleBand",
        ),

        // ── Low band — settled / on-device only ───────────────────────────
        CanonicalQuery(
            id = "low_settled_history",
            description = "Settled history; no search.",
            query = "what is the capital of france",
            classifierLogits = LOW_BAND_LOGITS,
            expectedBand = "LowSkipSearch",
            expectedPromptBlocks = setOf(PromptBlock.ToolDefinition),
            forbiddenPromptBlocks = setOf(PromptBlock.PreflightNotice, PromptBlock.MemoryContext),
        ),
        CanonicalQuery(
            id = "low_definition",
            description = "Word definition; no search.",
            query = "what does ephemeral mean",
            classifierLogits = LOW_BAND_LOGITS,
            expectedBand = "LowSkipSearch",
        ),
        CanonicalQuery(
            id = "low_coding",
            description = "How-to coding question; no search.",
            query = "how do i reverse a string in python",
            classifierLogits = LOW_BAND_LOGITS,
            expectedBand = "LowSkipSearch",
        ),
        CanonicalQuery(
            id = "low_math",
            description = "Math question; no search.",
            query = "what is 13 squared",
            classifierLogits = LOW_BAND_LOGITS,
            expectedBand = "LowSkipSearch",
        ),

        // ── Memory-conditional ─────────────────────────────────────────────
        CanonicalQuery(
            id = "memory_my_team_with_seed",
            description = "Possessive 'my team' resolves via memory → fires search with rewritten team name.",
            query = "did my team win last night",
            memorySeed = listOf(memory("user's favorite team is the philadelphia eagles", MemoryCategory.PREFERENCE)),
            classifierLogits = HIGH_BAND_LOGITS,
            expectedBand = "HighFireSearch",
            expectedRewrittenContains = listOf("philadelphia eagles", "2026-05-09"),
            expectedPromptBlocks = setOf(PromptBlock.MemoryContext, PromptBlock.PreflightNotice, PromptBlock.ToolDefinition),
        ),
        CanonicalQuery(
            id = "memory_my_team_without_seed",
            description = "Possessive 'my team' without memory → RewriterAbort, falls through to Gemma.",
            query = "did my team win last night",
            classifierLogits = HIGH_BAND_LOGITS,
            expectedBand = "FallThrough_RewriterAbort",
            forbiddenPromptBlocks = setOf(PromptBlock.MemoryContext, PromptBlock.PreflightNotice),
        ),
        CanonicalQuery(
            id = "memory_low_band_with_seed",
            description = "Low-band query + memory seed should still show memory in prompt.",
            query = "what is my favorite team",
            memorySeed = listOf(memory("user's favorite team is the philadelphia eagles", MemoryCategory.PREFERENCE)),
            classifierLogits = LOW_BAND_LOGITS,
            expectedBand = "LowSkipSearch",
            expectedPromptBlocks = setOf(PromptBlock.MemoryContext),
            forbiddenPromptBlocks = setOf(PromptBlock.PreflightNotice),
        ),

        // ── Explicit web-search force-fire (invariant #43) ─────────────────
        CanonicalQuery(
            id = "explicit_web_search_middle_band",
            description = "Mid-band query opening with 'web search' fires anyway; command words are stripped from the search query.",
            query = "web search the url of the android open source project",
            classifierLogits = MIDDLE_BAND_LOGITS,
            expectedBand = "HighFireSearch",
            expectedRewrittenContains = listOf("android open source project"),
            expectedPromptBlocks = setOf(PromptBlock.PreflightNotice, PromptBlock.ToolDefinition),
        ),

        // ── Search disabled ────────────────────────────────────────────────
        CanonicalQuery(
            id = "search_disabled_high_band",
            description = "Even a high-confidence search query is bypassed when the toggle is off.",
            query = "did the eagles win yesterday",
            classifierLogits = HIGH_BAND_LOGITS,
            searchAvailable = false,
            expectedBand = "SearchDisabled",
            forbiddenPromptBlocks = setOf(PromptBlock.PreflightNotice, PromptBlock.ToolDefinition),
        ),

        // ── System-prompt structure spot-check ─────────────────────────────
        CanonicalQuery(
            id = "system_prompt_always_has_citation_guideline",
            description = "Citation guideline must appear in every assembled prompt (PRD §3.2.3).",
            query = "what is the capital of france",
            classifierLogits = LOW_BAND_LOGITS,
            expectedBand = "LowSkipSearch",
            expectedPromptBlocks = setOf(PromptBlock.CitationGuideline),
        ),
    )

    @OptIn(ExperimentalUuidApi::class)
    private fun memory(text: String, category: MemoryCategory): Memory = Memory(
        id = "mem-${Uuid.random()}",
        text = text,
        category = category,
        conversationId = null,
        createdAtEpochMs = 1_700_000_000_000L,
        lastAccessedEpochMs = 1_700_000_000_000L,
        accessCount = 0,
        embedding = FloatArray(384),
        expiresAtEpochMs = null,
    )

    /** Single canonical case. */
    private data class CanonicalQuery(
        val id: String,
        val description: String,
        val query: String,
        val memorySeed: List<Memory> = emptyList(),
        val classifierLogits: FloatArray,
        val searchAvailable: Boolean = true,
        val expectedBand: String,
        val expectedRewrittenContains: List<String> = emptyList(),
        val expectedPromptBlocks: Set<PromptBlock> = emptySet(),
        val forbiddenPromptBlocks: Set<PromptBlock> = emptySet(),
    )

    /**
     * Named regions of the assembled system prompt the canonical test
     * asserts on. Each value knows how to detect itself in the assembled
     * `systemInstruction` text.
     */
    private enum class PromptBlock {
        MemoryContext {
            override fun contains(systemInstruction: String, q: CanonicalQuery): Boolean =
                systemInstruction.contains("Relevant context from previous conversations")
        },
        PreflightNotice {
            // PR-#23-followup: pre-flight results are now injected as a
            // plain-text `[SEARCH CONTEXT]` block in the system prompt
            // instead of a synthetic tool-call/tool-response pair.
            override fun contains(systemInstruction: String, q: CanonicalQuery): Boolean =
                systemInstruction.contains("=== Search context for this turn ===")
        },
        ToolDefinition {
            // LLM-side tool calling is fully disabled, but the no-tools
            // block has two variants:
            //   - default (search-on): tells the model to consume
            //     `[SEARCH CONTEXT]` if present; treated as "tools are
            //     conceptually available via pre-flight"
            //   - search-off: tells the model search is disabled in
            //     settings; treated as "no tools, no pre-flight"
            // This predicate returns true for the default variant only, so
            // the `forbiddenPromptBlocks` mechanism on the search-disabled
            // fixture continues to assert the search-off variant fires.
            override fun contains(systemInstruction: String, q: CanonicalQuery): Boolean =
                systemInstruction.contains("=== Available tools ===") &&
                    !systemInstruction.contains("web search is disabled")
        },
        CitationGuideline {
            override fun contains(systemInstruction: String, q: CanonicalQuery): Boolean =
                systemInstruction.contains("Citation: When you use information")
        };

        abstract fun contains(systemInstruction: String, q: CanonicalQuery): Boolean
    }
}

// Pre-baked classifier logits — softmax({5, 0, 0}) ≈ {0.95, 0.02, 0.02},
// so search_required is the dominant class for the high-band fixture.
// The middle band keeps search_required leading but well under the
// configured highBand (currently 0.5 in `preflight_config.json`); softmax
// of {0.5, 0, 0} ≈ {0.452, 0.274, 0.274}. The low band picks
// search_not_required dominant.
private val HIGH_BAND_LOGITS = floatArrayOf(5f, 0f, 0f)
private val MIDDLE_BAND_LOGITS = floatArrayOf(0.5f, 0f, 0f)
private val LOW_BAND_LOGITS = floatArrayOf(0f, 5f, 0f)
