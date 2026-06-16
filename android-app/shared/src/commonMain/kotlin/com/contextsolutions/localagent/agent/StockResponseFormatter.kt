package com.contextsolutions.localagent.agent

import com.contextsolutions.localagent.i18n.StringKeys
import com.contextsolutions.localagent.i18n.Strings
import com.contextsolutions.localagent.search.FormattedSearchPayload
import kotlin.math.abs
import kotlin.math.round
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Renders a FINANCE [FormattedSearchPayload] carrying a stockanalysis.com quote
 * (parsed by [com.contextsolutions.localagent.search.vertical.StockAnalysisParser]
 * via [com.contextsolutions.localagent.search.vertical.FinanceQuoteAdapter])
 * into a deterministic chat bubble — no LLM (PR #38). Same rationale as
 * [WeatherResponseFormatter]: Gemma mangles numbers and refuses "real-time"
 * data, so structured quotes render directly.
 *
 * Only renders the `"subtype":"stock_quote"` envelope; returns `null` for any
 * other payload (a fallback web-snippet result) so the agent loop falls through
 * to the LLM path.
 *
 * Example:
 * ```
 * NVIDIA Corporation (NVDA) — $219.31  ▼ -4.16 (-1.86%)
 * Day 217.93–227.40 · 52-wk 129.16–236.54
 * Mkt cap 5.31T · P/E 33.59 · Vol 101.7M
 * Source: stockanalysis.com · As of May 21, 2026, 11:20 AM EDT
 * ```
 */
object StockResponseFormatter {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun format(payload: FormattedSearchPayload, strings: Strings = Strings.ENGLISH): String? = try {
        val root = json.parseToJsonElement(payload.json).jsonObject
        if (root["subtype"]?.jsonPrimitive?.contentOrNull != "stock_quote") return null
        val q = root["quote"]?.jsonObject ?: return null
        val price = q.d("latest_price") ?: return null

        val symbol = q.s("symbol")
        val name = q.s("name") ?: symbol ?: strings.get(StringKeys.STOCK_FALLBACK_NAME)
        val change = q.d("change")
        val pct = q.d("change_percent")

        buildString {
            // Header: Name (SYM) — $price  ▲ +chg (+pct%)
            append(name)
            if (symbol != null) append(" (").append(symbol).append(')')
            append(" — $").append(money(price))
            if (change != null && change != 0.0) {
                append("  ").append(if (change > 0) "▲" else "▼")
                append(' ').append(signed(change))
                if (pct != null) append(" (").append(signed(pct)).append("%)")
            }
            append('\n')

            // Day low–high · 52-wk low–high
            val dayLow = q.d("day_low"); val dayHigh = q.d("day_high")
            val w52low = q.d("week_52_low"); val w52high = q.d("week_52_high")
            val ranges = buildList {
                if (dayLow != null && dayHigh != null) {
                    add(strings.get(StringKeys.STOCK_DAY_RANGE, money(dayLow), money(dayHigh)))
                }
                if (w52low != null && w52high != null) {
                    add(strings.get(StringKeys.STOCK_WEEK52_RANGE, money(w52low), money(w52high)))
                }
            }
            if (ranges.isNotEmpty()) append(ranges.joinToString(" · ")).append('\n')

            // Mkt cap · P/E · Vol — market cap & P/E arrive pre-formatted from
            // stockanalysis.com (e.g. "5.31T", "33.59"); volume is numeric.
            val mktCap = q.s("market_cap"); val pe = q.s("pe_ratio"); val vol = q.d("volume")
            val stats = buildList {
                if (mktCap != null) add(strings.get(StringKeys.STOCK_MARKET_CAP, mktCap))
                if (pe != null) add(strings.get(StringKeys.STOCK_PE, pe))
                if (vol != null) add(strings.get(StringKeys.STOCK_VOLUME, compact(vol)))
            }
            if (stats.isNotEmpty()) append(stats.joinToString(" · ")).append('\n')

            // Source · As of (as_of is already a human label from the page)
            val domain = payload.sources.firstOrNull()?.url?.let { domainOf(it) } ?: "stockanalysis.com"
            append(strings.get(StringKeys.COMMON_SOURCE)).append(domain)
            q.s("as_of")?.let { append(" · ").append(strings.get(StringKeys.STOCK_AS_OF, it)) }
        }
    } catch (_: Throwable) {
        null
    }

    private fun JsonObject.d(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull
    private fun JsonObject.s(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    /** Two-decimal fixed format, locale-free (KMP commonMain has no String.format). */
    private fun money(v: Double): String {
        val cents = round(abs(v) * 100).toLong()
        val whole = cents / 100
        val frac = (cents % 100).toInt()
        val sign = if (v < 0) "-" else ""
        return "$sign$whole.${if (frac < 10) "0$frac" else "$frac"}"
    }

    /** Signed two-decimal: `+2.34` / `-2.34`. */
    private fun signed(v: Double): String = (if (v >= 0) "+" else "-") + money(abs(v))

    /** One decimal, trailing `.0` trimmed: `3.2`, `845`, `101.7`. */
    private fun oneDecimal(v: Double): String {
        val tenths = round(abs(v) * 10).toLong()
        val whole = tenths / 10
        val frac = (tenths % 10).toInt()
        val sign = if (v < 0) "-" else ""
        return if (frac == 0) "$sign$whole" else "$sign$whole.$frac"
    }

    /** Compact large numbers: 3.2T / 845B / 245M / 12K. */
    private fun compact(v: Double): String {
        val a = abs(v)
        return when {
            a >= 1e12 -> "${oneDecimal(v / 1e12)}T"
            a >= 1e9 -> "${oneDecimal(v / 1e9)}B"
            a >= 1e6 -> "${oneDecimal(v / 1e6)}M"
            a >= 1e3 -> "${oneDecimal(v / 1e3)}K"
            else -> oneDecimal(v)
        }
    }

    /** Best-effort host extraction (mirrors WeatherResponseFormatter). */
    private fun domainOf(url: String): String {
        val afterScheme = url.substringAfter("://", url)
        return afterScheme.substringBefore('/').substringBefore('?').removePrefix("www.")
    }
}
