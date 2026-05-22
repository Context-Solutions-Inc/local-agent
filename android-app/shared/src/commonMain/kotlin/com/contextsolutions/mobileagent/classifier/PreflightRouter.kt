package com.contextsolutions.mobileagent.classifier

import com.contextsolutions.mobileagent.classifier.internal.softmax
import com.contextsolutions.mobileagent.memory.Memory
import com.contextsolutions.mobileagent.search.RelativeTemporalDetector
import com.contextsolutions.mobileagent.search.SearchSubtype
import com.contextsolutions.mobileagent.search.SearchSubtypeDetector
import com.contextsolutions.mobileagent.telemetry.CounterNames
import com.contextsolutions.mobileagent.telemetry.LatencyNames
import com.contextsolutions.mobileagent.telemetry.NoOpTelemetryCounters
import com.contextsolutions.mobileagent.telemetry.TelemetryCounters

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
    private val logger: (String) -> Unit = {},
    private val counters: TelemetryCounters = NoOpTelemetryCounters,
    private val nowEpochMs: () -> Long = { kotlinx.datetime.Clock.System.now().toEpochMilliseconds() },
) {

    private var classifierUnavailableLogged = false

    /**
     * @param memories optional retrieved memories from the [com.contextsolutions.mobileagent.memory.MemoryRetriever]
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

        val startMs = nowEpochMs()
        val tokenized = tokenizer.encodeSingle(query)
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
            pSearch > thresholds.highBand || forceTemporal -> {
                val rewritten = rewriter.rewrite(query, memories)
                if (rewritten == null) {
                    PreflightDecision.FallThrough(
                        reason = FallThroughReason.RewriterAbort,
                        pSearchRequired = pSearch,
                    )
                } else {
                    // Subtype is detected on the ORIGINAL query — the
                    // rewriter mutates keywords for time/possessive
                    // resolution and can strip the verticals' anchor words
                    // ("weather", "score", etc.). Detection on the literal
                    // user text preserves intent.
                    PreflightDecision.FireSearch(
                        originalQuery = query,
                        rewrittenQuery = rewritten,
                        pSearchRequired = pSearch,
                        subtype = subtypeDetector.detect(query),
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
                // Sub-counter: a force-fire the band alone would NOT have produced.
                // The guard makes it mean exactly that — a query that is both
                // high-band and temporal would have fired anyway, so don't count it.
                if (forceTemporal && pSearch <= thresholds.highBand) {
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

        logger(formatLogLine(query, decision, forcedTemporal = forceTemporal && pSearch <= thresholds.highBand))
        return decision
    }

    private fun formatLogLine(query: String, decision: PreflightDecision, forcedTemporal: Boolean): String {
        val name = when (decision) {
            is PreflightDecision.FireSearch -> "FireSearch"
            is PreflightDecision.SkipSearch -> "SkipSearch"
            is PreflightDecision.FallThrough -> "FallThrough(${decision.reason})"
            is PreflightDecision.SearchDisabled -> "SearchDisabled"
        }
        val pSearch = decision.pSearchRequired
        val pStr = if (pSearch != null) " p_search_required=${pSearch.formatProb()}" else ""
        val extra = when (decision) {
            is PreflightDecision.FireSearch ->
                " subtype=${decision.subtype.name} rewritten=\"${redact(decision.rewrittenQuery)}\"" +
                    if (forcedTemporal) " forced=temporal" else ""
            else -> ""
        }
        return "[preflight] decision=$name$pStr query=\"${redact(query)}\"$extra"
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
