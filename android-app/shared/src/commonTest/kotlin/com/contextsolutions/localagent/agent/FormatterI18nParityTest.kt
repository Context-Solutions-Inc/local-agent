package com.contextsolutions.localagent.agent

import com.contextsolutions.localagent.search.FormattedSearchPayload
import com.contextsolutions.localagent.search.SearchSource
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks the i18n migration (PR #96) to behaviour-preserving for English: each
 * formatter, called with the default [com.contextsolutions.localagent.i18n.Strings.ENGLISH],
 * must reproduce the exact text it emitted before the catalog was introduced.
 * Mirrors the golden assertions in the `:androidApp` formatter tests but lives
 * in commonTest so it runs on the Android-free desktop JVM target.
 */
class FormatterI18nParityTest {

    // ── Stock (golden bubble, byte-for-byte) ────────────────────────────────

    @Test
    fun stock_golden_bubble_unchanged() {
        val json = """
            {"subtype":"stock_quote","query":"nvidia stock price","quote":{
              "symbol":"NVDA","name":"NVIDIA Corporation","exchange":"NASDAQ",
              "latest_price":219.31,"change":-4.16,"change_percent":-1.86,
              "day_low":217.93,"day_high":227.40,
              "week_52_low":129.16,"week_52_high":236.54,
              "market_cap":"5.31T","pe_ratio":"33.59","volume":101695848,
              "as_of":"May 21, 2026, 11:20 AM EDT"}}
        """.trimIndent()
        val payload = FormattedSearchPayload(
            json = json,
            sources = listOf(SearchSource("NVIDIA", "https://stockanalysis.com/stocks/nvda/", "")),
        )
        assertEquals(
            """
            NVIDIA Corporation (NVDA) — ${'$'}219.31  ▼ -4.16 (-1.86%)
            Day 217.93–227.40 · 52-wk 129.16–236.54
            Mkt cap 5.31T · P/E 33.59 · Vol 101.7M
            Source: stockanalysis.com · As of May 21, 2026, 11:20 AM EDT
            """.trimIndent(),
            StockResponseFormatter.format(payload),
        )
    }

    // ── Clock ───────────────────────────────────────────────────────────────

    @Test
    fun clock_one_alarm_unchanged() {
        val json = """{"alarms":[{"hour":"3","minute":"55","period":"PM","recurrence":"Every day"}]}"""
        assertEquals(
            "You have one alarm set: 3:55 PM, every day.",
            ClockResponseFormatter.format(ClockToolHandler.LIST_ALARMS_NAME, json),
        )
    }

    @Test
    fun clock_no_alarms_unchanged() {
        assertEquals(
            "You don't have any alarms set.",
            ClockResponseFormatter.format(ClockToolHandler.LIST_ALARMS_NAME, """{"alarms":[]}"""),
        )
    }

    @Test
    fun clock_set_timer_unchanged() {
        assertEquals(
            "Timer set for 1 minute (tea).",
            ClockResponseFormatter.format(
                ClockToolHandler.SET_TIMER_NAME,
                """{"status":"ok","duration_seconds":60,"label":"tea"}""",
            ),
        )
    }

    @Test
    fun clock_error_unchanged() {
        assertEquals(
            "Sorry, that didn't work: duration must be positive",
            ClockResponseFormatter.format(
                ClockToolHandler.SET_TIMER_NAME,
                """{"status":"error","message":"duration must be positive"}""",
            ),
        )
    }

    @Test
    fun clock_cancel_plural_unchanged() {
        assertEquals(
            "Cancelled 1 alarm.",
            ClockResponseFormatter.format(ClockToolHandler.CANCEL_ALARM_NAME, """{"cancelled_count":1}"""),
        )
        assertEquals(
            "Cancelled 2 alarms.",
            ClockResponseFormatter.format(ClockToolHandler.CANCEL_ALARM_NAME, """{"cancelled_count":2}"""),
        )
    }

    @Test
    fun clock_duration_hours_minutes_unchanged() {
        // 1h 5m → "Timer set for 1 hour 5 minutes."
        assertEquals(
            "Timer set for 1 hour 5 minutes.",
            ClockResponseFormatter.format(
                ClockToolHandler.SET_TIMER_NAME,
                """{"status":"ok","duration_seconds":3900}""",
            ),
        )
    }

    // ── My List ──────────────────────────────────────────────────────────────

    private val myList = MyListResponseFormatter()

    @Test
    fun mylist_add_unchanged() {
        assertEquals(
            "Added \"buy milk\".",
            myList.format(MyListToolHandler.ADD_ITEM_NAME, """{"title":"buy milk"}"""),
        )
    }

    @Test
    fun mylist_empty_open_unchanged() {
        assertEquals(
            "You don't have any open items.",
            myList.format(MyListToolHandler.SHOW_LIST_NAME, """{"items":[]}"""),
        )
    }

    @Test
    fun mylist_clear_completed_plural_unchanged() {
        assertEquals(
            "No completed items to clear.",
            myList.format(MyListToolHandler.CLEAR_COMPLETED_NAME, """{"deleted_count":0}"""),
        )
        assertEquals(
            "Cleared 1 completed item.",
            myList.format(MyListToolHandler.CLEAR_COMPLETED_NAME, """{"deleted_count":1}"""),
        )
        assertEquals(
            "Cleared 3 completed items.",
            myList.format(MyListToolHandler.CLEAR_COMPLETED_NAME, """{"deleted_count":3}"""),
        )
    }

    @Test
    fun mylist_mark_done_unchanged() {
        assertEquals(
            "Marked \"gym\" as done.",
            myList.format(MyListToolHandler.COMPLETE_ITEM_NAME, """{"title":"gym","completed":true}"""),
        )
    }
}
