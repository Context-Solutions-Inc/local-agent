package com.contextsolutions.mobileagent.search

import com.contextsolutions.mobileagent.telemetry.CounterNames
import com.contextsolutions.mobileagent.telemetry.LatencyNames
import com.contextsolutions.mobileagent.telemetry.NoOpTelemetryCounters
import com.contextsolutions.mobileagent.telemetry.TelemetryCounters

/**
 * Orchestrates a single web search end-to-end:
 *  1. Resolve the API key (early return on no-key — search is a degraded-mode
 *     feature, not a hard failure per PRD §6.2).
 *  2. Look the normalized query up in the cache; on hit, return without touching
 *     the network — the UI uses [SearchOutcome.Success.fromCache] to render the
 *     cache-hit indicator (PRD §3.4).
 *  3. On miss, call [BraveSearchClient]; on success, persist to the cache and
 *     return. Errors propagate without poisoning the cache.
 *
 * The agent loop (Phase C / WS-3) calls [search] when Gemma emits a `web_search`
 * tool call; the pre-flight short-circuit path (M4) will use the same entry point.
 */
class SearchService(
    private val keyProvider: BraveKeyProvider,
    private val client: BraveSearchClient,
    private val cache: SearchCacheDao,
    private val isEnabled: () -> Boolean = { true },
    private val counters: TelemetryCounters = NoOpTelemetryCounters,
    private val nowEpochMs: () -> Long = { kotlinx.datetime.Clock.System.now().toEpochMilliseconds() },
) {
    /**
     * True when a search would actually run if attempted. The agent loop calls
     * this to decide whether to advertise the `web_search` tool in the system
     * prompt — the cleaner alternative to letting the model emit a tool call
     * we'll only reject. Mirrors the gates in [search] (toggle + key
     * configured) but skips the ones that depend on per-call state (cache, network).
     */
    fun isAvailable(): Boolean = isEnabled() && keyProvider.hasKey()

    suspend fun search(query: String): SearchOutcome {
        // M6 Phase C counters: increment once per invocation regardless of
        // outcome. Latency observation wraps the full path including cache
        // hits (the lookup is the work, not the cost-savings reporting).
        counters.increment(CounterNames.SEARCH_INVOKED_TOTAL)
        val startMs = nowEpochMs()
        try {
            if (!isEnabled()) {
                counters.increment(CounterNames.SEARCH_DISABLED_TOTAL)
                return SearchOutcome.Error(
                    SearchOutcome.ErrorKind.Disabled,
                    "Web search is disabled in settings",
                )
            }
            val key = keyProvider.currentKey()
            if (key.isNullOrBlank()) {
                counters.increment(CounterNames.SEARCH_NO_KEY_TOTAL)
                return SearchOutcome.Error(SearchOutcome.ErrorKind.NoKey, "No Brave Search key configured")
            }

            when (val cached = cache.lookup(query)) {
                is SearchCacheDao.CacheLookup.Hit -> {
                    counters.increment(CounterNames.SEARCH_CACHE_HIT_TOTAL)
                    return SearchOutcome.Success(cached.payload, fromCache = true)
                }
                SearchCacheDao.CacheLookup.Miss -> Unit
            }

            return when (val result = client.search(query, key)) {
                is BraveSearchResult.Success -> {
                    cache.store(query, result.payload)
                    SearchOutcome.Success(result.payload, fromCache = false)
                }
                is BraveSearchResult.Error -> {
                    counters.increment(
                        CounterNames.SEARCH_ERROR_TOTAL,
                        tag = result.kind.toCounterTag(),
                    )
                    SearchOutcome.Error(result.kind.toServiceKind(), result.message)
                }
            }
        } finally {
            counters.observeLatency(LatencyNames.SEARCH_MS, nowEpochMs() - startMs)
        }
    }

    private fun BraveSearchResult.ErrorKind.toCounterTag(): String = when (this) {
        BraveSearchResult.ErrorKind.Network -> "network"
        BraveSearchResult.ErrorKind.Auth -> "client_error"
        BraveSearchResult.ErrorKind.RateLimited -> "server_error"
        BraveSearchResult.ErrorKind.BadResponse -> "unexpected"
    }

    private fun BraveSearchResult.ErrorKind.toServiceKind(): SearchOutcome.ErrorKind = when (this) {
        BraveSearchResult.ErrorKind.Network -> SearchOutcome.ErrorKind.Network
        BraveSearchResult.ErrorKind.Auth -> SearchOutcome.ErrorKind.Auth
        BraveSearchResult.ErrorKind.RateLimited -> SearchOutcome.ErrorKind.RateLimited
        BraveSearchResult.ErrorKind.BadResponse -> SearchOutcome.ErrorKind.BadResponse
    }
}

/**
 * Stable result type the agent loop and UI consume. Wider error surface than
 * [BraveSearchResult] because [SearchService] also reports key-availability and
 * (post-WS-11) user-toggled-search-disabled cases.
 */
sealed interface SearchOutcome {
    data class Success(val payload: FormattedSearchPayload, val fromCache: Boolean) : SearchOutcome
    data class Error(val kind: ErrorKind, val message: String) : SearchOutcome

    enum class ErrorKind {
        /** No Brave key configured (BYOK not done; dev key not bundled in this build). */
        NoKey,
        /** User has toggled web search off in settings. */
        Disabled,
        Network,
        Auth,
        RateLimited,
        BadResponse,
    }
}
