package com.contextsolutions.localagent.memory

import com.contextsolutions.localagent.agent.TimeContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TempContextDateParserTest {

    private val today = LocalDate(2026, 5, 10) // Sunday
    private val zone = TimeZone.UTC

    private val parser = TempContextDateParser(
        timeContextProvider = {
            TimeContext(
                now = LocalDateTime(today.year, today.monthNumber, today.dayOfMonth, 14, 32),
                timeZoneId = "UTC",
                timeZoneAbbreviation = "UTC",
                utcOffset = "+00:00",
            )
        },
        timeZoneProvider = { zone },
    )

    private fun expectedEpochMs(date: LocalDate): Long =
        date.atStartOfDayIn(zone).toEpochMilliseconds()

    // -- Phrase rules ---------------------------------------------------

    @Test
    fun parses_today() {
        assertEquals(
            expectedEpochMs(today),
            parser.parse("i have a deadline today")!!,
        )
    }

    @Test
    fun parses_tonight_as_today() {
        assertEquals(
            expectedEpochMs(today),
            parser.parse("party tonight at 8")!!,
        )
    }

    @Test
    fun parses_tomorrow() {
        assertEquals(
            expectedEpochMs(LocalDate(2026, 5, 11)),
            parser.parse("flight tomorrow")!!,
        )
    }

    @Test
    fun parses_next_week() {
        assertEquals(
            expectedEpochMs(LocalDate(2026, 5, 17)),
            parser.parse("traveling next week")!!,
        )
    }

    @Test
    fun parses_next_month() {
        assertEquals(
            expectedEpochMs(LocalDate(2026, 6, 10)),
            parser.parse("renovation starts next month")!!,
        )
    }

    @Test
    fun parses_next_year() {
        assertEquals(
            expectedEpochMs(LocalDate(2027, 5, 10)),
            parser.parse("wedding next year")!!,
        )
    }

    // -- "in N (units)" --------------------------------------------------

    @Test
    fun parses_in_three_days() {
        assertEquals(
            expectedEpochMs(LocalDate(2026, 5, 13)),
            parser.parse("appointment in 3 days")!!,
        )
    }

    @Test
    fun parses_in_two_weeks() {
        assertEquals(
            expectedEpochMs(LocalDate(2026, 5, 24)),
            parser.parse("conference in 2 weeks")!!,
        )
    }

    @Test
    fun parses_in_one_month() {
        assertEquals(
            expectedEpochMs(LocalDate(2026, 6, 10)),
            parser.parse("renewal in 1 month")!!,
        )
    }

    // -- Weekdays --------------------------------------------------------

    @Test
    fun parses_on_friday_resolves_to_next_friday() {
        // Today is 2026-05-10 (Sunday). Next Friday = 2026-05-15.
        assertEquals(
            expectedEpochMs(LocalDate(2026, 5, 15)),
            parser.parse("meeting on Friday")!!,
        )
    }

    @Test
    fun parses_on_friday_when_today_is_friday_means_a_week_out() {
        val futureFriday = LocalDate(2026, 5, 15) // Friday
        val parserOnFriday = TempContextDateParser(
            timeContextProvider = {
                TimeContext(
                    now = LocalDateTime(futureFriday.year, futureFriday.monthNumber, futureFriday.dayOfMonth, 9, 0),
                    timeZoneId = "UTC",
                    timeZoneAbbreviation = "UTC",
                    utcOffset = "+00:00",
                )
            },
            timeZoneProvider = { zone },
        )
        assertEquals(
            expectedEpochMs(LocalDate(2026, 5, 22)),
            parserOnFriday.parse("by Friday")!!,
        )
    }

    @Test
    fun parses_short_weekday_forms() {
        // Today = Sunday; next Tue = 2026-05-12.
        assertEquals(
            expectedEpochMs(LocalDate(2026, 5, 12)),
            parser.parse("ping me on tue")!!,
        )
    }

    // -- ISO date wins -----------------------------------------------------

    @Test
    fun isoDate_in_message_takes_precedence() {
        val expected = expectedEpochMs(LocalDate(2026, 8, 1))
        val result = parser.parse("save the date 2026-08-01 for the move")
        assertEquals(expected, result!!)
    }

    // -- Negative cases ----------------------------------------------------

    @Test
    fun returns_null_for_text_with_no_temporal_signal() {
        assertNull(parser.parse("i'm vegetarian"))
        assertNull(parser.parse("my favorite team is the eagles"))
    }

    @Test
    fun returns_null_for_blank_text() {
        assertNull(parser.parse(""))
    }

    @Test
    fun does_not_match_random_words_starting_with_in() {
        // "in toronto" should NOT match the "in N units" rule.
        assertNull(parser.parse("i live in toronto"))
    }
}
