package com.contextsolutions.localagent.search.vertical

import com.contextsolutions.localagent.preferences.GpsCoordinates
import com.contextsolutions.localagent.preferences.UserLocation
import com.contextsolutions.localagent.preferences.VerticalPreferences
import com.contextsolutions.localagent.search.FormattedSearchPayload
import com.contextsolutions.localagent.search.SearchOutcome
import com.contextsolutions.localagent.search.SearchSource
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * FINANCE adapter (PR #38). For a single-instrument query it resolves the
 * ticker via Brave (the company's `finance.yahoo.com/quote/<TICKER>/` result),
 * then fetches that ticker's stockanalysis.com page and parses the live quote
 * into a `"subtype":"stock_quote"` payload that
 * [com.contextsolutions.localagent.agent.StockResponseFormatter] renders as a
 * deterministic card (no LLM, like the WEATHER path).
 *
 * Two calls:
 *  1. Brave web search via [fallback] (`site:finance.yahoo.com`) — the result
 *     URL carries the ticker, and the result title carries the company name.
 *  2. `GET stockanalysis.com/stocks/<ticker>/` → [StockAnalysisParser].
 *
 * On any miss (no ticker in the Brave results, fetch/parse failure) it returns
 * the Brave [fallback] outcome unchanged — the web-snippet payload has no
 * `stock_quote` marker, so the agent loop's FINANCE block declines it and the
 * turn answers from snippets via the LLM (pre-PR-#38 behavior).
 *
 * (PR #27 implemented a similar two-call flow but resolved the ticker via
 * stockanalysis.com's own search API and fed the page text to the LLM; this
 * uses Brave for resolution and renders deterministically instead.)
 */
class FinanceQuoteAdapter(
    private val httpClient: HttpClient,
    private val fallback: VerticalSearchAdapter,
    private val parser: StockAnalysisParser = StockAnalysisParser(),
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val logger: (String) -> Unit = {},
) : VerticalSearchAdapter {

    private val json = Json { encodeDefaults = false; prettyPrint = false }

    override suspend fun fetch(
        query: String,
        prefs: VerticalPreferences,
        location: UserLocation?,
        gps: GpsCoordinates?,
    ): SearchOutcome = withContext(Dispatchers.IO) {
        // 1. Brave resolves the ticker (+ company name) from its finance result.
        val webOutcome = fallback.fetch(query, prefs, location, gps)
        if (webOutcome !is SearchOutcome.Success) return@withContext webOutcome
        val resolved = resolveTicker(webOutcome.payload.sources)
        if (resolved == null) {
            logger("[vertical:FINANCE] no ticker in Brave results — snippet fallback")
            return@withContext webOutcome
        }
        val (ticker, name) = resolved
        logger("[vertical:FINANCE] resolved ticker=$ticker name=\"$name\" (from Brave finance result)")

        // 2. stockanalysis.com for the structured quote.
        val pageUrl = "$baseUrl/stocks/${ticker.lowercase()}/"
        val quote = try {
            val body = httpClient.get(pageUrl).bodyAsText()
            logger("[vertical:FINANCE] GET $pageUrl bodyLen=${body.length}")
            parser.parse(body)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logger("[vertical:FINANCE] stockanalysis fetch failed: ${t.message ?: t::class.simpleName} — snippet fallback")
            null
        }
        if (quote == null) {
            logger("[vertical:FINANCE] no quote parsed for $ticker — snippet fallback")
            return@withContext webOutcome
        }
        logger("[vertical:FINANCE] quote $ticker price=${quote.price} change=${quote.change}")
        SearchOutcome.Success(buildPayload(query, ticker, name, quote, pageUrl), fromCache = false)
    }

    /** First `finance.yahoo.com/quote/<TICKER>/` result → (base ticker, company name). */
    private fun resolveTicker(sources: List<SearchSource>): Pair<String, String>? {
        for (s in sources) {
            val m = YAHOO_QUOTE.find(s.url) ?: continue
            // Strip a foreign-listing suffix (NVDA.TO → NVDA) — stockanalysis.com
            // keys US listings on the bare symbol.
            val ticker = m.groupValues[1].uppercase().substringBefore('.')
            if (ticker.isBlank()) continue
            val name = s.title.substringBefore(" (").trim().ifBlank { ticker }
            return ticker to name
        }
        return null
    }

    private fun buildPayload(
        query: String,
        ticker: String,
        name: String,
        quote: StockAnalysisParser.Quote,
        pageUrl: String,
    ): FormattedSearchPayload {
        val envelope = buildJsonObject {
            put("subtype", "stock_quote")
            put("query", query)
            put("quote", buildJsonObject {
                put("symbol", ticker)
                put("name", name)
                quote.exchange?.let { put("exchange", it) }
                put("latest_price", quote.price)
                quote.change?.let { put("change", it) }
                quote.changePercent?.let { put("change_percent", it) }
                quote.dayLow?.let { put("day_low", it) }
                quote.dayHigh?.let { put("day_high", it) }
                quote.week52Low?.let { put("week_52_low", it) }
                quote.week52High?.let { put("week_52_high", it) }
                quote.volume?.let { put("volume", it) }
                quote.marketCap?.let { put("market_cap", it) }
                quote.peRatio?.let { put("pe_ratio", it) }
                quote.asOf?.let { put("as_of", it) }
            })
        }
        return FormattedSearchPayload(
            json = json.encodeToString(JsonObject.serializer(), envelope),
            sources = listOf(SearchSource(title = "$name ($ticker)", url = pageUrl, snippet = "")),
        )
    }

    private companion object {
        const val DEFAULT_BASE_URL = "https://stockanalysis.com"
        val YAHOO_QUOTE = Regex("""finance\.yahoo\.com/quote/([A-Za-z0-9.\-]+)""")
    }
}
