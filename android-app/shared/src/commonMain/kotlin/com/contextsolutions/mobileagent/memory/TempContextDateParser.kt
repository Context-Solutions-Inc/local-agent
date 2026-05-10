package com.contextsolutions.mobileagent.memory

import com.contextsolutions.mobileagent.agent.TimeContext
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus

/**
 * Best-effort date extractor for `temporary_context` memories per Q5 in
 * M5_PLAN.md §2 — "I'm traveling next week" → epoch ms for next week's
 * Monday-ish anchor, "I have a deadline on Friday" → that Friday, ISO
 * dates parsed verbatim.
 *
 * Returns `null` when no date can be confidently extracted; the caller
 * (`MemoryExtractor`) then defaults to `now + 30 days` (Q5 fallback).
 *
 * Pure-Kotlin, lives in commonMain so iOS gets it for free in Phase 2.
 *
 * **What we recognise:**
 *
 *  - "today" / "tonight" → today
 *  - "tomorrow" / "tomorrow morning|evening" → today+1
 *  - "next week" → today + 7
 *  - "next month" → today + 1 month
 *  - "next year" → today + 1 year
 *  - "in N days|weeks|months|years" → today + N
 *  - "on (mon|tue|wed|thu|fri|sat|sun)[day]" → next occurrence of that day
 *  - ISO-8601 date `YYYY-MM-DD` anywhere in the text — exact match
 *
 * What we DON'T parse: month-name absolute dates ("on May 17"), times
 * of day ("at 3pm"), durations without a unit ("for a while"). Those
 * fall through to the 30-day default. v1.x can broaden if telemetry
 * shows real users phrasing temp-context memories that way.
 */
class TempContextDateParser(
    private val timeContextProvider: () -> TimeContext,
    private val timeZoneProvider: () -> TimeZone = { TimeZone.UTC },
) {

    /**
     * Parse a date out of [text]. Returns `null` when nothing matches.
     */
    fun parse(text: String): Long? {
        val ctx = timeContextProvider()
        val today = LocalDate(ctx.now.year, ctx.now.monthNumber, ctx.now.dayOfMonth)
        val zone = timeZoneProvider()
        val lower = text.lowercase()

        // ISO date — most specific wins if present anywhere in the text.
        ISO_DATE_REGEX.find(lower)?.let { match ->
            return runCatching { LocalDate.parse(match.value) }
                .getOrNull()?.atStartOfDayIn(zone)?.toEpochMilliseconds()
        }

        // "in N (days|weeks|months|years)"
        IN_N_UNITS_REGEX.find(lower)?.let { match ->
            val n = match.groupValues[1].toIntOrNull() ?: return@let
            val unit = match.groupValues[2]
            val target = when (unit) {
                "day", "days" -> today.plus(DatePeriod(days = n))
                "week", "weeks" -> today.plus(DatePeriod(days = n * 7))
                "month", "months" -> today.plus(DatePeriod(months = n))
                "year", "years" -> today.plus(DatePeriod(years = n))
                else -> return@let
            }
            return target.atStartOfDayIn(zone).toEpochMilliseconds()
        }

        // "on (weekday)" — next occurrence
        WEEKDAY_REGEX.find(lower)?.let { match ->
            val day = parseWeekday(match.groupValues[1]) ?: return@let
            val daysAhead = ((day.ordinal - today.dayOfWeek.ordinal) + 7) % 7
            // 0 means "today" — for "on Friday" said on a Friday, we
            // mean *next* Friday, not today.
            val effective = if (daysAhead == 0) 7 else daysAhead
            val target = today.plus(DatePeriod(days = effective))
            return target.atStartOfDayIn(zone).toEpochMilliseconds()
        }

        // Fixed phrases.
        for ((phrase, offset) in PHRASE_RULES) {
            if (lower.contains(phrase)) {
                val target = applyOffset(today, offset)
                return target.atStartOfDayIn(zone).toEpochMilliseconds()
            }
        }

        return null
    }

    private fun applyOffset(today: LocalDate, offset: DateOffset): LocalDate = when (offset) {
        is DateOffset.Days -> today.plus(DatePeriod(days = offset.n))
        is DateOffset.Months -> today.plus(DatePeriod(months = offset.n))
        is DateOffset.Years -> today.plus(DatePeriod(years = offset.n))
    }

    private fun parseWeekday(token: String): DayOfWeek? = when (token) {
        "mon", "monday" -> DayOfWeek.MONDAY
        "tue", "tues", "tuesday" -> DayOfWeek.TUESDAY
        "wed", "weds", "wednesday" -> DayOfWeek.WEDNESDAY
        "thu", "thur", "thurs", "thursday" -> DayOfWeek.THURSDAY
        "fri", "friday" -> DayOfWeek.FRIDAY
        "sat", "saturday" -> DayOfWeek.SATURDAY
        "sun", "sunday" -> DayOfWeek.SUNDAY
        else -> null
    }

    private sealed interface DateOffset {
        data class Days(val n: Int) : DateOffset
        data class Months(val n: Int) : DateOffset
        data class Years(val n: Int) : DateOffset
    }

    private companion object {
        // ISO-8601 date — rough anchor: 4-digit year + 2-digit month + 2-digit day.
        private val ISO_DATE_REGEX = Regex("""\b(\d{4}-\d{2}-\d{2})\b""")

        private val IN_N_UNITS_REGEX = Regex(
            """(?i)\bin\s+(\d{1,3})\s+(days?|weeks?|months?|years?)\b""",
        )

        private val WEEKDAY_REGEX = Regex(
            "(?i)\\b(?:on|by|for|next|this)\\s+" +
                "(monday|mon|tuesday|tues?|wednesday|weds?|thursday|thur?s?|friday|fri|saturday|sat|sunday|sun)" +
                "\\b",
        )

        // Order matters — longer / more-specific phrases first.
        private val PHRASE_RULES: List<Pair<String, DateOffset>> = listOf(
            "tomorrow morning" to DateOffset.Days(1),
            "tomorrow evening" to DateOffset.Days(1),
            "tomorrow night" to DateOffset.Days(1),
            "tomorrow" to DateOffset.Days(1),
            "tonight" to DateOffset.Days(0),
            "today" to DateOffset.Days(0),
            "next week" to DateOffset.Days(7),
            "next month" to DateOffset.Months(1),
            "next year" to DateOffset.Years(1),
            "this week" to DateOffset.Days(0),
            "this month" to DateOffset.Days(0),
            "this year" to DateOffset.Days(0),
        )
    }
}
