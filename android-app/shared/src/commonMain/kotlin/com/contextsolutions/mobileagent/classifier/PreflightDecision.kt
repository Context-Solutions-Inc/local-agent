package com.contextsolutions.mobileagent.classifier

import com.contextsolutions.mobileagent.search.SearchSubtype

/**
 * Outcome of [PreflightRouter.route] for a single user turn. The agent
 * loop branches on the variant before invoking Gemma per PRD §3.2.1.
 */
sealed class PreflightDecision {

    /** The probability the classifier assigned to "search_required", if computed. */
    abstract val pSearchRequired: Float?

    /**
     * High-band hit (`p_search_required > highBand`) AND the query rewriter
     * produced a confident search query. The agent fires the matching vertical
     * adapter for [subtype] with [rewrittenQuery] and injects the result as a
     * plain-text `[SEARCH CONTEXT]` block in the system instruction (see
     * `PromptAssembler.searchContext`).
     *
     * [subtype] defaults to [SearchSubtype.GENERAL] (Brave Web Search) so
     * callers that ignore the field reproduce the pre-PR-#23 behaviour
     * verbatim — the field is purely additive on the existing variant.
     */
    data class FireSearch(
        val originalQuery: String,
        val rewrittenQuery: String,
        override val pSearchRequired: Float,
        val subtype: SearchSubtype = SearchSubtype.GENERAL,
    ) : PreflightDecision()

    /**
     * Low-band hit (`p_search_required < lowBand`). No pre-flight search is
     * executed; the LLM answers from training/memory. (LLM-side tool calls
     * are fully disabled — Gemma cannot fire its own search.)
     */
    data class SkipSearch(
        override val pSearchRequired: Float,
    ) : PreflightDecision()

    /**
     * Middle band, OR rewriter could not produce a confident query, OR
     * classifier is unavailable. No pre-flight search is fired and the LLM
     * answers from training/memory.
     */
    data class FallThrough(
        val reason: FallThroughReason,
        override val pSearchRequired: Float?,
    ) : PreflightDecision()

    /**
     * Search is disabled in settings (toggle off, or no Brave key). Pre-flight
     * never fires regardless of classifier output. The router short-circuits
     * before tokenizing to save the inference cost.
     */
    data object SearchDisabled : PreflightDecision() {
        override val pSearchRequired: Float? = null
    }
}

/** Reason a high-band candidate fell through instead of firing search. */
enum class FallThroughReason {
    /** `p_search_required` landed in `[lowBand, highBand]`. */
    MiddleBand,

    /**
     * High-band hit but the deterministic rewriter aborted (e.g., the query
     * contains a possessive reference like "my team" that requires memory
     * context unavailable until M5).
     */
    RewriterAbort,

    /**
     * Classifier failed to load or threw during inference. Engine returned
     * null. Logged once per app lifetime; subsequent calls take this path
     * silently.
     */
    ClassifierUnavailable,
}
