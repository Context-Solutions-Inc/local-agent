package com.contextsolutions.mobileagent.search

/**
 * Single HTTP call to Brave Web Search. Stateless; the [SearchService] layer adds
 * caching and key resolution. The interface lives in commonMain so a fake can
 * stand in for unit tests; the production impl is [KtorBraveSearchClient].
 */
interface BraveSearchClient {
    suspend fun search(query: String, apiKey: String): BraveSearchResult
}

sealed interface BraveSearchResult {
    data class Success(val payload: FormattedSearchPayload) : BraveSearchResult
    data class Error(val kind: ErrorKind, val message: String) : BraveSearchResult

    /**
     * Error categories visible to the agent loop. The user-facing message we hand
     * back to Gemma as a tool_result depends on the kind; PRD §6.2 calls out that
     * each of these surfaces as a conversational tool error rather than a dialog.
     */
    enum class ErrorKind {
        /** I/O failure or timeout — typically transient. */
        Network,
        /** 401/403 — key invalid, expired, or revoked. */
        Auth,
        /** 429 — caller is over their Brave quota. */
        RateLimited,
        /** Non-2xx (other), unparseable body, or an empty result set we can't act on. */
        BadResponse,
    }
}
