package com.contextsolutions.localagent.search

import com.contextsolutions.localagent.platform.HttpEngineFactory
import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/**
 * [BraveSearchClient] backed by Brave's **LLM Context** endpoint
 * (`/res/v1/llm/context`) instead of `/web/search`. It returns pre-extracted,
 * relevance-ranked page content rather than index snippets — see
 * [BraveLlmContextResponse]. Implements the same interface as
 * [KtorBraveSearchClient], so it drops into [SearchService] unchanged (key
 * resolution, caching, counters all reused). Every Brave-backed vertical runs
 * through it (GENERAL/NEWS/FINANCE-fallback + SPORTS) since PR #42 — see
 * [maxUrls].
 *
 * Same auth and same Search-plan billing as the web client
 * (`X-Subscription-Token`, $5/1000). The query is passed through verbatim;
 * `site:` pinning (when a vertical uses it) is applied upstream by
 * [com.contextsolutions.localagent.search.vertical.BraveSiteFilterAdapter].
 *
 * Budget caps are sent on every request so the returned context stays small
 * enough for on-device Gemma 4 E2B; [LlmContextPostProcessor] is the
 * client-side backstop.
 *
 * [logger] mirrors [KtorBraveSearchClient]: a diagnostic logcat sink that
 * records the outgoing `q`. Local diagnostics, NOT telemetry egress.
 */
class KtorBraveLlmContextClient internal constructor(
    private val httpClient: HttpClient,
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val maxUrls: Int = DEFAULT_MAX_URLS,
    private val maxTokens: Int = MAX_TOKENS,
    private val maxSnippetsPerUrl: Int = MAX_SNIPPETS_PER_URL,
    private val logger: (String) -> Unit = {},
) : BraveSearchClient {

    /**
     * Builds the Ktor [HttpClient] internally so the Hilt module stays
     * Ktor-free. [maxUrls] is the per-vertical URL budget (PR #42): SPORTS
     * passes 1 (single `site:`-pinned clean source — invariant #35);
     * GENERAL/NEWS/FINANCE-fallback pass more for multi-source synthesis.
     */
    constructor(
        httpEngineFactory: HttpEngineFactory,
        maxUrls: Int = DEFAULT_MAX_URLS,
        logger: (String) -> Unit = {},
    ) : this(httpEngineFactory.create(), maxUrls = maxUrls, logger = logger)

    // Mirrors the server's ContentNegotiation config (ignores unknown keys, see
    // BraveLlmContextResponse) — we decode the text body ourselves so the raw
    // response can be logged before parsing.
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(query: String, apiKey: String): BraveSearchResult {
        logger("llm-context q=\"$query\"")
        val response = try {
            httpClient.get(endpoint) {
                parameter("q", query)
                parameter("maximum_number_of_urls", maxUrls)
                parameter("maximum_number_of_tokens", maxTokens)
                parameter("maximum_number_of_snippets_per_url", maxSnippetsPerUrl)
                header(HttpHeaders.Accept, "application/json")
                header(SUBSCRIPTION_TOKEN_HEADER, apiKey)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpRequestTimeoutException) {
            return BraveSearchResult.Error(BraveSearchResult.ErrorKind.Network, "Brave LLM Context timed out")
        } catch (e: SocketTimeoutException) {
            return BraveSearchResult.Error(BraveSearchResult.ErrorKind.Network, "Brave LLM Context timed out")
        } catch (e: Throwable) {
            return BraveSearchResult.Error(
                BraveSearchResult.ErrorKind.Network,
                "Brave LLM Context network error: ${e::class.simpleName}",
            )
        }

        if (!response.status.isSuccess()) {
            return BraveSearchResult.Error(
                response.status.toErrorKind(),
                "Brave LLM Context HTTP ${response.status.value}",
            )
        }

        // Read the body as text first so we can dump it verbatim to logcat —
        // mirrors AgentLoop's unredacted [SEARCH CONTEXT] dump, letting the
        // user compare Brave's RAW response against the post-processed block
        // the model actually reads (e.g. to confirm whether a corrupted number
        // like "1110" came from the source data or the model). Chunked to beat
        // logcat's ~4000-char per-line ceiling.
        val rawBody = try {
            response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return BraveSearchResult.Error(
                BraveSearchResult.ErrorKind.BadResponse,
                "Brave LLM Context returned an unreadable response",
            )
        }
        logger(">>> LLM CONTEXT RAW RESPONSE START >>> len=${rawBody.length}")
        var offset = 0
        while (offset < rawBody.length) {
            val end = (offset + LOG_CHUNK).coerceAtMost(rawBody.length)
            logger("rawResp@$offset: ${rawBody.substring(offset, end)}")
            offset = end
        }
        logger("<<< LLM CONTEXT RAW RESPONSE END <<<")

        val parsed = try {
            json.decodeFromString(BraveLlmContextResponse.serializer(), rawBody)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return BraveSearchResult.Error(
                BraveSearchResult.ErrorKind.BadResponse,
                "Brave LLM Context returned an unparseable response",
            )
        }

        val formatted = LlmContextPostProcessor.format(parsed)
        if (formatted.sources.isEmpty()) {
            return BraveSearchResult.Error(
                BraveSearchResult.ErrorKind.BadResponse,
                "Brave LLM Context returned no usable results",
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
        const val DEFAULT_ENDPOINT = "https://api.search.brave.com/res/v1/llm/context"
        const val SUBSCRIPTION_TOKEN_HEADER = "X-Subscription-Token"

        // Budget caps — keep the returned context small for on-device Gemma.
        // [maxUrls] is now per-vertical (PR #42); this is only the default the
        // bare-factory constructor falls back to. DEFAULT = 1 preserves SPORTS
        // behaviour: SPORTS is `site:`-pinned to a single domain (espn.com /
        // tsn.ca), and on-device testing showed extra URLs from that domain are
        // noise (a VideoObject blob, a standings table) full of confusing
        // numbers the 2B model mis-transcribes. One URL = the top scores page,
        // the cleanest single source. GENERAL/NEWS/FINANCE-fallback inject a
        // higher value (3) from SearchModule for multi-source synthesis.
        private const val DEFAULT_MAX_URLS = 1
        private const val MAX_TOKENS = 1800
        private const val MAX_SNIPPETS_PER_URL = 6

        // Logcat per-line ceiling is ~4000 chars on Pixel; chunk below it.
        private const val LOG_CHUNK = 3500
    }
}
