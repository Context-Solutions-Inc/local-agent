package com.contextsolutions.localagent.clock

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function checks for [NextFireCalculator]. DST edge cases are left to
 * kotlinx-datetime's library tests; we cover the "today vs tomorrow" and
 * weekday-walk logic.
 */
class NextFireCalculatorTest {

    private val tz = TimeZone.of("America/Toronto")

    private fun instant(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime(y, mo, d, h, mi).toInstant(tz)

    private fun baseAlarm() = AlarmEntry(
        id = "a",
        label = null,
        hour = 7,
        minute = 0,
        recurringDays = emptySet(),
        enabled = true,
        createdAtEpochMs = 0,
    )

    @Test
    fun `one-shot fires today when time still in future`() {
        val now = instant(2026, 5, 14, 6, 30)
        val next = NextFireCalculator.next(baseAlarm(), now, tz)
        assertEquals(instant(2026, 5, 14, 7, 0).toEpochMilliseconds(), next)
    }

    @Test
    fun `one-shot rolls to tomorrow when time already passed`() {
        val now = instant(2026, 5, 14, 7, 30)
        val next = NextFireCalculator.next(baseAlarm(), now, tz)
        assertEquals(instant(2026, 5, 15, 7, 0).toEpochMilliseconds(), next)
    }

    @Test
    fun `disabled alarm returns null`() {
        val now = instant(2026, 5, 14, 6, 0)
        val next = NextFireCalculator.next(baseAlarm().copy(enabled = false), now, tz)
        assertNull(next)
    }

    @Test
    fun `recurring weekday alarm matches today when day is in set and time hasn't passed`() {
        // 2026-05-14 is a Thursday.
        val alarm = baseAlarm().copy(recurringDays = setOf(AlarmDay.THURSDAY))
        val now = instant(2026, 5, 14, 6, 30)
        val next = NextFireCalculator.next(alarm, now, tz)
        assertEquals(instant(2026, 5, 14, 7, 0).toEpochMilliseconds(), next)
    }

    @Test
    fun `recurring weekday alarm skips to next match when today's time passed`() {
        // 2026-05-14 = Thursday; days = {Mon, Wed, Fri} → next is Friday 2026-05-15.
        val alarm = baseAlarm().copy(
            recurringDays = setOf(AlarmDay.MONDAY, AlarmDay.WEDNESDAY, AlarmDay.FRIDAY),
        )
        val now = instant(2026, 5, 14, 8, 0)
        val next = NextFireCalculator.next(alarm, now, tz)
        assertEquals(instant(2026, 5, 15, 7, 0).toEpochMilliseconds(), next)
    }

    @Test
    fun `recurring weekday alarm wraps to next week when no day this week`() {
        // 2026-05-14 = Thursday; days = {Sunday} → next is Sunday 2026-05-17.
        val alarm = baseAlarm().copy(recurringDays = setOf(AlarmDay.SUNDAY))
        val now = instant(2026, 5, 14, 8, 0)
        val next = NextFireCalculator.next(alarm, now, tz)
        assertEquals(instant(2026, 5, 17, 7, 0).toEpochMilliseconds(), next)
    }

    @Test
    fun `skipNextDate causes today to be skipped`() {
        // Recurring every day, current time before 7am, but today is marked skip.
        val alarm = baseAlarm().copy(
            recurringDays = AlarmDay.entries.toSet(),
            skipNextDateIso = "2026-05-14",
        )
        val now = instant(2026, 5, 14, 6, 30)
        val next = NextFireCalculator.next(alarm, now, tz)
        assertEquals(instant(2026, 5, 15, 7, 0).toEpochMilliseconds(), next)
    }

    @Test
    fun `skipNextDate with mismatched iso is ignored`() {
        val alarm = baseAlarm().copy(
            recurringDays = AlarmDay.entries.toSet(),
            skipNextDateIso = "garbage",
        )
        val now = instant(2026, 5, 14, 6, 30)
        val next = NextFireCalculator.next(alarm, now, tz)
        assertTrue(next != null && next > 0)
    }
}
