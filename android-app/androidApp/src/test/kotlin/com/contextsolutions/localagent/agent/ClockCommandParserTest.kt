package com.contextsolutions.localagent.agent

import com.contextsolutions.localagent.clock.AlarmDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the regex parser through every phrasing that surfaced during
 * PR #11 testing — both the working cases and the ones that previously
 * tripped Gemma — to lock in deterministic behaviour and prevent
 * regression.
 */
class ClockCommandParserTest {

    @Test
    fun `set a 1 minute timer for tea`() {
        val cmd = ClockCommandParser.parse("set a 1-minute timer for tea")
        assertEquals(ClockCommand.SetTimer(totalSeconds = 60, label = "tea"), cmd)
    }

    @Test
    fun `set a one-minute timer with no label`() {
        // We don't try to interpret English number words — fall through.
        assertNull(ClockCommandParser.parse("set a one minute timer for tea"))
    }

    @Test
    fun `5 minute timer parses as 300 seconds`() {
        val cmd = ClockCommandParser.parse("5 minute timer")
        assertEquals(ClockCommand.SetTimer(totalSeconds = 300, label = null), cmd)
    }

    @Test
    fun `25 minute timer for studying`() {
        val cmd = ClockCommandParser.parse("25 minute timer for studying")
        assertEquals(ClockCommand.SetTimer(totalSeconds = 1500, label = "studying"), cmd)
    }

    @Test
    fun `set a timer for 1 hour 30 minutes`() {
        val cmd = ClockCommandParser.parse("set a timer for 1 hour 30 minutes")
        assertEquals(ClockCommand.SetTimer(totalSeconds = 5400, label = null), cmd)
    }

    @Test
    fun `remind me in 90 seconds`() {
        val cmd = ClockCommandParser.parse("remind me in 90 seconds")
        assertEquals(ClockCommand.SetTimer(totalSeconds = 90, label = null), cmd)
    }

    @Test
    fun `remind me in 5 min`() {
        val cmd = ClockCommandParser.parse("remind me in 5 min")
        assertEquals(ClockCommand.SetTimer(totalSeconds = 300, label = null), cmd)
    }

    @Test
    fun `timer with wall-clock time falls through to LLM`() {
        // "11:45 AM" indicates an alarm, not a timer — should NOT be
        // parsed as set_timer even though the message says "timer".
        // Falls through so set_alarm (if applicable) or the LLM can pick it.
        val cmd = ClockCommandParser.parse("set a timer for 11:45 a.m. on weekdays")
        // Should be parsed as SetAlarm via the set_alarm path, since the
        // alarm parser triggers on the time-of-day. Or null if we couldn't
        // disambiguate; either way it must not be a SetTimer with garbage.
        assertTrue(
            "expected alarm or null, got $cmd",
            cmd == null || cmd is ClockCommand.SetAlarm,
        )
    }

    @Test
    fun `set an alarm for 7am`() {
        val cmd = ClockCommandParser.parse("set an alarm for 7am")
        assertEquals(
            ClockCommand.SetAlarm(hour = 7, minute = 0, days = emptySet(), label = null),
            cmd,
        )
    }

    @Test
    fun `set an alarm for 7 defaults to AM`() {
        val cmd = ClockCommandParser.parse("set an alarm for 7")
        assertEquals(
            ClockCommand.SetAlarm(hour = 7, minute = 0, days = emptySet(), label = null),
            cmd,
        )
    }

    @Test
    fun `wake me at 6 30 every weekday`() {
        val cmd = ClockCommandParser.parse("wake me at 6:30 every weekday")
        assertEquals(
            ClockCommand.SetAlarm(
                hour = 6,
                minute = 30,
                days = setOf(
                    AlarmDay.MONDAY, AlarmDay.TUESDAY, AlarmDay.WEDNESDAY,
                    AlarmDay.THURSDAY, AlarmDay.FRIDAY,
                ),
                label = null,
            ),
            cmd,
        )
    }

    @Test
    fun `alarm for 11 45 a m on weekdays`() {
        // The exact field-report phrasing.
        val cmd = ClockCommandParser.parse("add another alarm for 11:45 a.m. on weekdays")
        assertEquals(
            ClockCommand.SetAlarm(
                hour = 11,
                minute = 45,
                days = setOf(
                    AlarmDay.MONDAY, AlarmDay.TUESDAY, AlarmDay.WEDNESDAY,
                    AlarmDay.THURSDAY, AlarmDay.FRIDAY,
                ),
                label = null,
            ),
            cmd,
        )
    }

    @Test
    fun `alarm at 3 55 PM every day`() {
        val cmd = ClockCommandParser.parse("set an alarm at 3:55 PM every day")
        assertEquals(
            ClockCommand.SetAlarm(
                hour = 15,
                minute = 55,
                days = AlarmDay.entries.toSet(),
                label = null,
            ),
            cmd,
        )
    }

    @Test
    fun `12 am midnight conversion`() {
        val cmd = ClockCommandParser.parse("set an alarm for 12:00 AM")
        assertEquals(
            ClockCommand.SetAlarm(hour = 0, minute = 0, days = emptySet(), label = null),
            cmd,
        )
    }

    @Test
    fun `12 pm noon conversion`() {
        val cmd = ClockCommandParser.parse("set an alarm for 12:00 PM")
        assertEquals(
            ClockCommand.SetAlarm(hour = 12, minute = 0, days = emptySet(), label = null),
            cmd,
        )
    }

    @Test
    fun `bare 12 defaults to noon`() {
        val cmd = ClockCommandParser.parse("set an alarm for 12")
        assertEquals(
            ClockCommand.SetAlarm(hour = 12, minute = 0, days = emptySet(), label = null),
            cmd,
        )
    }

    @Test
    fun `24h hour passes through`() {
        val cmd = ClockCommandParser.parse("set an alarm for 14:30")
        assertEquals(
            ClockCommand.SetAlarm(hour = 14, minute = 30, days = emptySet(), label = null),
            cmd,
        )
    }

    @Test
    fun `weekends preset`() {
        val cmd = ClockCommandParser.parse("set an alarm for 9am on weekends")
        assertEquals(
            ClockCommand.SetAlarm(
                hour = 9, minute = 0,
                days = setOf(AlarmDay.SATURDAY, AlarmDay.SUNDAY),
                label = null,
            ),
            cmd,
        )
    }

    @Test
    fun `explicit day list`() {
        val cmd = ClockCommandParser.parse("set an alarm for 6:45 AM on mon, wed and fri")
        assertEquals(
            ClockCommand.SetAlarm(
                hour = 6, minute = 45,
                days = setOf(AlarmDay.MONDAY, AlarmDay.WEDNESDAY, AlarmDay.FRIDAY),
                label = null,
            ),
            cmd,
        )
    }

    @Test
    fun `what alarms do I have`() {
        assertEquals(ClockCommand.ListAlarms, ClockCommandParser.parse("what alarms do I have"))
    }

    @Test
    fun `what alarms do I have set`() {
        assertEquals(
            ClockCommand.ListAlarms,
            ClockCommandParser.parse("what alarms do I have set"),
        )
    }

    @Test
    fun `list my alarms`() {
        assertEquals(ClockCommand.ListAlarms, ClockCommandParser.parse("list my alarms"))
    }

    @Test
    fun `show alarms`() {
        assertEquals(ClockCommand.ListAlarms, ClockCommandParser.parse("show alarms"))
    }

    @Test
    fun `what timers are running`() {
        assertEquals(
            ClockCommand.ListTimers,
            ClockCommandParser.parse("what timers are running"),
        )
    }

    @Test
    fun `cancel all alarms`() {
        assertEquals(
            ClockCommand.CancelAlarm(label = null, all = true),
            ClockCommandParser.parse("cancel all alarms"),
        )
    }

    @Test
    fun `cancel my tea timer`() {
        assertEquals(
            ClockCommand.CancelTimer(label = "tea", all = false),
            ClockCommandParser.parse("cancel my tea timer"),
        )
    }

    @Test
    fun `stop all timers`() {
        assertEquals(
            ClockCommand.CancelTimer(label = null, all = true),
            ClockCommandParser.parse("stop all timers"),
        )
    }

    @Test
    fun `non-clock messages return null`() {
        assertNull(ClockCommandParser.parse("what's the weather"))
        assertNull(ClockCommandParser.parse("tell me about photosynthesis"))
        assertNull(ClockCommandParser.parse("5 minutes ago I ate lunch"))
    }

    @Test
    fun `now what alarms do I have set parses despite leading 'now'`() {
        // Field report — the LLM otherwise sees this turn and garbles the
        // response ("3:5:5 PM" etc.). Leading conversational scaffolding
        // ("now", "actually", "and") gets stripped before pattern matching.
        assertEquals(
            ClockCommand.ListAlarms,
            ClockCommandParser.parse("now what alarms do I have set"),
        )
    }

    @Test
    fun `actually list my timers parses`() {
        assertEquals(
            ClockCommand.ListTimers,
            ClockCommandParser.parse("actually, list my timers"),
        )
    }

    @Test
    fun `can you set an alarm for 7am parses`() {
        assertEquals(
            ClockCommand.SetAlarm(hour = 7, minute = 0, days = emptySet(), label = null),
            ClockCommandParser.parse("can you set an alarm for 7am"),
        )
    }

    @Test
    fun `compound questions fall through to LLM`() {
        // Hard to disambiguate — punt to the LLM rather than risk a
        // false positive.
        val cmd = ClockCommandParser.parse(
            "I want to set an alarm but first what's the time in Tokyo",
        )
        // Either null (best) or a set_alarm with no clear time (acceptable).
        // We assert it's NOT a confidently-wrong command.
        assertTrue(
            "expected null or alarm; got $cmd",
            cmd == null || cmd is ClockCommand.SetAlarm,
        )
    }
}
