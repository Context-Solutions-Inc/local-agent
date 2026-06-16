package com.contextsolutions.localagent.classifier

import com.contextsolutions.localagent.classifier.internal.softmax
import com.contextsolutions.localagent.memory.Memory
import com.contextsolutions.localagent.search.ExplicitSearchDetector
import com.contextsolutions.localagent.search.RelativeTemporalDetector
import com.contextsolutions.localagent.search.SearchSubtype
import com.contextsolutions.localagent.search.SearchSubtypeDetector
import com.contextsolutions.localagent.telemetry.CounterNames
import com.contextsolutions.localagent.telemetry.LatencyNames
import com.contextsolutions.localagent.telemetry.NoOpTelemetryCounters
import com.contextsolutions.localagent.telemetry.TelemetryCounters

/**
 * Phase C / WS-8 routing decision (PRD §3.2.1). Drives the three-band
 * outcome the agent loop branches on:
 *
 *  - `p_search_required > thresholds.highBand` → run the rewriter
 *    - rewriter returns null → [PreflightDecision.FallThrough] (`RewriterAbort`)
 *    - else → [PreflightDecision.FireSearch] with the rewritten query
 *  - `p_search_required < thresholds.lowBand` → [PreflightDecision.SkipSearch]
 *    (web_search tool stays registered — see M4_PLAN.md §2; Gemma can still
 *    call it if its judgment differs)
 *  - middle band → [PreflightDecision.FallThrough] (`MiddleBand`) — standard
 *    Gemma tool-calling per PRD §3.2.1
 *
 * **Temporal force-fire (invariant #38).** Independent of the band, a query
 * carrying a relative temporal reference ("last year", "yesterday", "tomorrow")
 * takes the FireSearch path: the LLM's fixed cutoff can't answer a now-relative
 * question, and the classifier under-fires on these. The gate widens to
 * `pSearch > highBand || temporalDetector.matches(query)`; the existing rewriter
 * (resolves "last year" → a concrete year) and subtype detection then run.
 *
 * **Explicit-search force-fire (invariant #43).** A query that OPENS with an
 * explicit web command ("web search …", "search the web …", "search online …";
 * see [ExplicitSearchDetector]) also takes the FireSearch path regardless of the
 * band — the user's deterministic escape hatch. The command words are stripped
 * before the classifier, rewriter, and subtype detector see them, so they run on
 * the real question. Unlike the temporal/high-band path, an explicit demand fires
 * even when the rewriter aborts (it fires the stripped query verbatim) — the user
 * asked for search, so falling through to no-search would be surprising.
 *
 * **Graceful degradation (PRD §3.2.1 failure modes).** Classifier
 * load/inference failure NEVER throws into the agent. The router catches
 * `null` returns from [ClassifierEngine.classify] and emits
 * [PreflightDecision.FallThrough] (`ClassifierUnavailable`) so the user
 * request continues unchanged. Logged once per construction at INFO level
 * to keep telemetry honest without spamming logcat on every query.
 *
 * **Settings short-circuit.** When the user has search disabled (or no
 * Brave key), the router returns [PreflightDecision.SearchDisabled] before
 * tokenizing — we don't waste classifier cycles on a query that can't
 * fire search anyway.
 *
 * **Logging.** Each `route()` call logs the chosen decision +
 * `p_search_required` (and rewritten query for FireSearch) at INFO level
 * via [logger]. Probabilities for all 3 classes are deferred to a debug
 * flag (M6 telemetry replaces this surface).
 */
class PreflightRouter(
    private val engine: ClassifierEngine,
    private val tokenizer: WordPieceTokenizer,
    private val rewriter: QueryRewriter,
    private val configProvider: () -> PreflightConfig,
    private val searchAvailableProvider: suspend () -> Boolean,
    private val subtypeDetector: SearchSubtypeDetector = SearchSubtypeDetector(),
    private val temporalDetector: RelativeTemporalDetector = RelativeTemporalDetector(),
    private val explicitDetector: ExplicitSearchDetector = ExplicitSearchDetector(),
    private val logger: (String) -> Unit = {},
    private val counters: TelemetryCounters = NoOpTelemetryCounters,
    private val nowEpochMs: () -> Long = { kotlinx.datetime.Clock.System.now().toEpochMilliseconds() },
) {

    private var classifierUnavailableLogged = false

    /**
     * @param memories optional retrieved memories from the [com.contextsolutions.localagent.memory.MemoryRetriever]
     *   side of the agent loop. Threaded into the rewriter so possessives
     *   like "my team" get substituted with the matching memory text
     *   (M5 Phase C). Empty list reproduces M4 behavior — the rewriter
     *   aborts on possessives.
     */
    suspend fun route(
        query: String,
        memories: List<Memory> = emptyList(),
    ): PreflightDecision {
        if (!searchAvailableProvider()) {
            logger("[preflight] decision=SearchDisabled query=\"${redact(query)}\"")
            // SearchDisabled is not a band — don't record a band counter.
            // Phase C `daily_search.search_disabled_total` is recorded by
            // SearchService where the toggle actually short-circuits.
            return PreflightDecision.SearchDisabled
        }

        // Invariant #43 — an explicit web command at the START of the query
        // ("web search …") forces FireSearch regardless of the band. Strip the
        // command words up front so the classifier/rewriter/subtype detector
        // operate on the real question, not the imperative.
        val forceExplicit = explicitDetector.matches(query)
        val effectiveQuery = if (forceExplicit) explicitDetector.stripPrefix(query) else query

        val startMs = nowEpochMs()
        val tokenized = tokenizer.encodeSingle(effectiveQuery)
        val output = engine.classify(tokenized.inputIds, tokenized.attentionMask)
        if (output == null) {
            if (!classifierUnavailableLogged) {
                logger("[preflight] classifier unavailable; all queries fall through to Gemma")
                classifierUnavailableLogged = true
            }
            counters.increment(CounterNames.PREFLIGHT_CLASSIFIER_UNAVAILABLE_TOTAL)
            return PreflightDecision.FallThrough(
                reason = FallThroughReason.ClassifierUnavailable,
                pSearchRequired = null,
            )
        }

        val probs = softmax(output.preflightLogits)
        val pSearch = probs[ClassifierOutput.PREFLIGHT_INDEX_SEARCH_REQUIRED]
        val thresholds = configProvider().thresholds

        // Invariant #38 — a relative temporal reference ("last year", "yesterday",
        // "tomorrow") forces the FireSearch path regardless of the band: the LLM's
        // fixed cutoff can't answer a now-relative question, and the classifier
        // under-fires on these (the canonical "who won the super bowl last year"
        // scored 0.175, mid band → stale Gemma). Computed AFTER classify so a
        // forced query still carries a real pSearch for logging/telemetry.
        val forceTemporal = temporalDetector.matches(query)
        val decision = when {
            pSearch > thresholds.highBand || forceTemporal || forceExplicit -> {
                val rewritten = rewriter.rewrite(effectiveQuery, memories)
                if (rewritten == null && !forceExplicit) {
                    PreflightDecision.FallThrough(
                        reason = FallThroughReason.RewriterAbort,
                        pSearchRequired = pSearch,
                    )
                } else {
                    // Subtype is detected on the (stripped) effective query —
                    // the rewriter mutates keywords for time/possessive
                    // resolution and can strip the verticals' anchor words
                    // ("weather", "score", etc.). Detection on the literal
                    // user text preserves intent. An explicit search whose
                    // rewriter aborted fires the stripped query verbatim (the
                    // user demanded search — see invariant #43).
                    PreflightDecision.FireSearch(
                        originalQuery = effectiveQuery,
                        rewrittenQuery = rewritten ?: effectiveQuery,
                        pSearchRequired = pSearch,
                        subtype = subtypeDetector.detect(effectiveQuery),
                    )
                }
            }
            pSearch < thresholds.lowBand -> PreflightDecision.SkipSearch(pSearchRequired = pSearch)
            else -> PreflightDecision.FallThrough(
                reason = FallThroughReason.MiddleBand,
                pSearchRequired = pSearch,
            )
        }

        // M6 Phase C — counter + latency observation. Recorded after the
        // decision so the metric reflects the full inference + dispatch
        // cost. Band counters are mutually exclusive for a given query.
        counters.observeLatency(LatencyNames.PREFLIGHT_MS, nowEpochMs() - startMs)
        when (decision) {
            is PreflightDecision.FireSearch -> {
                counters.increment(
                    CounterNames.PREFLIGHT_HIGH_BAND_TOTAL,
                    tag = decision.subtype.name.lowercase(),
                )
                // Sub-counters: a force-fire the band alone would NOT have produced.
                // The guard makes each mean exactly that — a query that is already
                // high-band would have fired anyway, so don't count it. Explicit
                // takes precedence over temporal when both qualify (the stronger,
                // user-driven signal); the two sub-counters stay mutually exclusive.
                if (forceExplicit && pSearch <= thresholds.highBand) {
                    counters.increment(CounterNames.PREFLIGHT_EXPLICIT_SEARCH_FORCE_TOTAL)
                } else if (forceTemporal && pSearch <= thresholds.highBand) {
                    counters.increment(CounterNames.PREFLIGHT_TEMPORAL_FORCE_TOTAL)
                }
            }
            is PreflightDecision.SkipSearch -> counters.increment(CounterNames.PREFLIGHT_LOW_BAND_TOTAL)
            is PreflightDecision.FallThrough -> when (decision.reason) {
                FallThroughReason.MiddleBand -> counters.increment(CounterNames.PREFLIGHT_MIDDLE_BAND_TOTAL)
                FallThroughReason.RewriterAbort -> counters.increment(CounterNames.PREFLIGHT_REWRITER_ABORT_TOTAL)
                FallThroughReason.ClassifierUnavailable -> Unit // handled above before this branch
            }
            is PreflightDecision.SearchDisabled -> Unit // can't reach here, see above
        }

        val forcedExplicit = forceExplicit && pSearch <= thresholds.highBand
        logger(
            formatLogLine(
                query,
                decision,
                thresholds = thresholds,
                forcedExplicit = forcedExplicit,
                forcedTemporal = !forcedExplicit && forceTemporal && pSearch <= thresholds.highBand,
            ),
        )
        return decision
    }

    private fun formatLogLine(
        query: String,
        decision: PreflightDecision,
        thresholds: PreflightThresholds,
        forcedExplicit: Boolean,
        forcedTemporal: Boolean,
    ): String {
        val name = when (decision) {
            is PreflightDecision.FireSearch -> "FireSearch"
            is PreflightDecision.SkipSearch -> "SkipSearch"
            is PreflightDecision.FallThrough -> "FallThrough(${decision.reason})"
            is PreflightDecision.SearchDisabled -> "SearchDisabled"
        }
        val pSearch = decision.pSearchRequired
        val pStr = if (pSearch != null) " p_search_required=${pSearch.formatProb()}" else ""
        // Surface the configured bands next to the score so it's clear how close
        // pSearch landed to the high/low thresholds (#14 — they're tunable).
        val bandsStr = " thresholds=[low=${thresholds.lowBand.formatProb()},high=${thresholds.highBand.formatProb()}]"
        val extra = when (decision) {
            is PreflightDecision.FireSearch ->
                " subtype=${decision.subtype.name} rewritten=\"${redact(decision.rewrittenQuery)}\"" +
                    when {
                        forcedExplicit -> " forced=explicit"
                        forcedTemporal -> " forced=temporal"
                        else -> ""
                    }
            else -> ""
        }
        return "[preflight] decision=$name$pStr$bandsStr query=\"${redact(query)}\"$extra"
    }

    private fun Float.formatProb(): String {
        // 3 decimal places, locale-independent. Avoids printf/Locale dependency
        // on commonMain.
        val rounded = (this * 1000f).toInt()
        val whole = rounded / 1000
        val frac = (rounded % 1000).toString().padStart(3, '0')
        return "$whole.$frac"
    }

    /**
     * Truncate to 80 chars for log readability. Pre-flight never sends queries
     * to a server, but log lines could end up in Crashlytics — better to keep
     * them short. M6 telemetry handles full-text capture under explicit
     * consent (PRD §3.2.1).
     */
    private fun redact(text: String): String =
        if (text.length <= 80) text else text.take(77) + "..."
}
