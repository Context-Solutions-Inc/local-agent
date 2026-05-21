package com.contextsolutions.mobileagent.agent

import com.contextsolutions.mobileagent.search.FormattedSearchPayload
import com.contextsolutions.mobileagent.search.SearchSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StockResponseFormatterTest {

    private fun payload(json: String, url: String = "https://stockanalysis.com/stocks/nvda/") =
        FormattedSearchPayload(
            json = json,
            sources = listOf(SearchSource(title = "NVIDIA Corporation (NVDA)", url = url, snippet = "")),
        )

    private val nvdaJson = """
        {"subtype":"stock_quote","query":"nvidia stock price","quote":{
          "symbol":"NVDA","name":"NVIDIA Corporation","exchange":"NASDAQ",
          "latest_price":219.31,"change":-4.16,"change_percent":-1.86,
          "day_low":217.93,"day_high":227.40,
          "week_52_low":129.16,"week_52_high":236.54,
          "market_cap":"5.31T","pe_ratio":"33.59","volume":101695848,
          "as_of":"May 21, 2026, 11:20 AM EDT"}}
    """.trimIndent()

    @Test
    fun renders_full_golden_bubble() {
        val out = StockResponseFormatter.format(payload(nvdaJson))
        assertEquals(
            """
            NVIDIA Corporation (NVDA) — ${'$'}219.31  ▼ -4.16 (-1.86%)
            Day 217.93–227.40 · 52-wk 129.16–236.54
            Mkt cap 5.31T · P/E 33.59 · Vol 101.7M
            Source: stockanalysis.com · As of May 21, 2026, 11:20 AM EDT
            """.trimIndent(),
            out,
        )
    }

    @Test
    fun up_move_uses_up_arrow_and_positive_signs() {
        val json = """
            {"subtype":"stock_quote","quote":{"symbol":"NVDA","name":"NVIDIA","latest_price":131.26,
              "change":2.34,"change_percent":1.81}}
        """.trimIndent()
        val out = StockResponseFormatter.format(payload(json))!!
        assertTrue(out.contains("— ${'$'}131.26  ▲ +2.34 (+1.81%)"))
        assertFalse(out.contains("▼"))
    }

    @Test
    fun flat_move_omits_arrow_and_delta() {
        val json = """
            {"subtype":"stock_quote","quote":{"symbol":"T","name":"AT&T","latest_price":18.00,
              "change":0.0,"change_percent":0.0}}
        """.trimIndent()
        val out = StockResponseFormatter.format(payload(json))!!
        assertTrue(out.startsWith("AT&T (T) — ${'$'}18.00"))
        assertFalse(out.contains("▲"))
        assertFalse(out.contains("▼"))
    }

    @Test
    fun preformatted_market_cap_and_pe_pass_through_volume_compacted() {
        val json = """
            {"subtype":"stock_quote","quote":{"symbol":"X","name":"X","latest_price":1.0,
              "market_cap":"845.2B","pe_ratio":"12.3","volume":2.45e8}}
        """.trimIndent()
        val out = StockResponseFormatter.format(payload(json))!!
        assertTrue("market cap verbatim", out.contains("Mkt cap 845.2B"))
        assertTrue("pe verbatim", out.contains("P/E 12.3"))
        assertTrue("volume compacted", out.contains("Vol 245M"))
    }

    @Test
    fun omits_null_optional_fields() {
        val json = """
            {"subtype":"stock_quote","quote":{"symbol":"NVDA","name":"NVIDIA","latest_price":219.31,
              "change":-4.16,"change_percent":-1.86}}
        """.trimIndent()
        val out = StockResponseFormatter.format(payload(json))!!
        assertFalse("no day/52-wk line", out.contains("Day "))
        assertFalse("no 52-wk", out.contains("52-wk"))
        assertFalse("no stats line", out.contains("Mkt cap"))
        assertFalse("no P/E", out.contains("P/E"))
        assertFalse("no as-of when missing", out.contains("As of"))
        assertTrue("still has source", out.contains("Source: stockanalysis.com"))
    }

    @Test
    fun returns_null_for_non_stock_quote_payload() {
        assertNull(StockResponseFormatter.format(payload("""{"subtype":"weather","sources":[]}""")))
    }

    @Test
    fun returns_null_when_latest_price_absent() {
        assertNull(StockResponseFormatter.format(payload("""{"subtype":"stock_quote","quote":{"symbol":"NVDA","change":2.34}}""")))
    }

    @Test
    fun returns_null_on_malformed_json() {
        assertNull(StockResponseFormatter.format(payload("not json")))
    }
}
