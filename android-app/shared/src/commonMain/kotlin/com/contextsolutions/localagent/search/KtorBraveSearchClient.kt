package com.contextsolutions.localagent.search

import com.contextsolutions.localagent.platform.HttpEngineFactory
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException

/**
 * Ktor implementation of [BraveSearchClient]. Holds a single [HttpClient] for the
 * process lifetime; it's built from the platform's [HttpEngineFactory], which on
 * Android installs the 10s request timeout (PRD §3.3), JSON content negotiation,
 * and the redacting logger that scrubs `X-Subscription-Token` and full query
 * strings out of any error logs (PRD §4.4).
 *
 * The Brave key is passed in rather than pulled from a key provider so callers
 * can short-circuit the no-key case before hitting the network.
 *
 * [logger] is a diagnostic logcat sink — every outgoing Brave request logs its
 * actual `q` here. This is the single authoritative chokepoint for "what query
 * hit Brave" across all verticals (GENERAL, plus the `site:`-augmented NEWS /
 * SPORTS / FINANCE queries). It is intentional local diagnostics, NOT telemetry
 * egress, and is distinct from the redacting HTTP error logger (PRD §4.4) that
 * scrubs query strings out of error logs.
 *
 * **PR #42:** GENERAL/NEWS/SPORTS moved to [KtorBraveLlmContextClient]
 * (`/llm/context`), but FINANCE stays here on `/web/search` — its ticker
 * resolution parses the `finance.yahoo.com/quote/<TICKER>/` URL that only
 * `/web/search` returns (see `@FinanceSearch` / invariant #37). Wired via the
 * `@FinanceSearch` [SearchService].
 */
class KtorBraveSearchClient internal constructor(
    private val httpClient: HttpClient,
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val logger: (String) -> Unit = {},
) : BraveSearchClient {

    constructor(httpEngineFactory: HttpEngineFactory) : this(httpEngineFactory.create())
    constructor(httpEngineFactory: HttpEngineFactory, endpoint: String) : this(httpEngineFactory.create(), endpoint)
    constructor(httpEngineFactory: HttpEngineFactory, logger: (String) -> Unit) :
        this(httpEngineFactory.create(), logger = logger)

    override suspend fun search(query: String, apiKey: String): BraveSearchResult {
        logger("q=\"$query\"")
        val response = try {
            httpClient.get(endpoint) {
                parameter("q", query)
                header(HttpHeaders.Accept, "application/json")
                header(SUBSCRIPTION_TOKEN_HEADER, apiKey)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpRequestTimeoutException) {
            return BraveSearchResult.Error(BraveSearchResult.ErrorKind.Network, "Brave Search timed out")
        } catch (e: SocketTimeoutException) {
            return BraveSearchResult.Error(BraveSearchResult.ErrorKind.Network, "Brave Search timed out")
        } catch (e: Throwable) {
            return BraveSearchResult.Error(
                BraveSearchResult.ErrorKind.Network,
                "Brave Search network error: ${e::class.simpleName}",
            )
        }

        if (!response.status.isSuccess()) {
            return BraveSearchResult.Error(response.status.toErrorKind(), "Brave Search HTTP ${response.status.value}")
        }

        val parsed = try {
            response.body<BraveSearchResponse>()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return BraveSearchResult.Error(
                BraveSearchResult.ErrorKind.BadResponse,
                "Brave Search returned an unparseable response",
            )
        }

        val formatted = SearchPostProcessor.format(parsed)
        if (formatted.sources.isEmpty()) {
            return BraveSearchResult.Error(
                BraveSearchResult.ErrorKind.BadResponse,
                "Brave Search returned no usable results",
            )
        }
        return BraveSearchResult.Success(formatted)
    }

    private fun HttpStatusCode.toErrorKind(): BraveSearchResult.ErrorKind = when (value) {
        401, 403 -> BraveSearchResult.ErrorKind.Auth
        429 -> BraveSearchResult.ErrorKind.RateLimited
        else -> BraveSearchResult.ErrorKind.BadResponse
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.search.brave.com/res/v1/web/search"
        const val SUBSCRIPTION_TOKEN_HEADER = "X-Subscription-Token"
    }
}
