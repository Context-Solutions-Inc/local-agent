package com.contextsolutions.localagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockResponseFormatterTest {

    @Test
    fun `list_alarms with one row produces a one-line sentence`() {
        // hour and minute are pre-formatted strings (minute zero-padded) —
        // matches the contract the tool handler now emits.
        val result = """
            {
              "count": 1,
              "alarms": [
                {"id":"a","hour":"3","minute":"55","period":"PM","enabled":true,"recurrence":"Every day"}
              ]
            }
        """.trimIndent()
        val rendered = ClockResponseFormatter.format(ClockToolHandler.LIST_ALARMS_NAME, result)
        assertEquals("You have one alarm set: 3:55 PM, every day.", rendered)
    }

    @Test
    fun `list_alarms with no rows says so`() {
        val result = """{"count":0,"alarms":[]}"""
        val rendered = ClockResponseFormatter.format(ClockToolHandler.LIST_ALARMS_NAME, result)
        assertEquals("You don't have any alarms set.", rendered)
    }

    @Test
    fun `list_alarms with multiple rows uses a bullet list`() {
        val result = """
            {
              "count": 2,
              "alarms": [
                {"hour":"7","minute":"30","period":"AM","recurrence":"Weekdays","label":"gym"},
                {"hour":"9","minute":"00","period":"AM","recurrence":"Once"}
              ]
            }
        """.trimIndent()
        val rendered = ClockResponseFormatter.format(ClockToolHandler.LIST_ALARMS_NAME, result)
        assertTrue(rendered.startsWith("You have 2 alarms set:\n"))
        assertTrue(rendered.contains("• 7:30 AM, weekdays — gym"))
        assertTrue(rendered.contains("• 9:00 AM"))
    }

    @Test
    fun `set_timer renders duration and label`() {
        val result = """{"status":"ok","id":"t","duration_seconds":60,"label":"tea"}"""
        val rendered = ClockResponseFormatter.format(ClockToolHandler.SET_TIMER_NAME, result)
        assertEquals("Timer set for 1 minute (tea).", rendered)
    }

    @Test
    fun `error result returns a friendly apology`() {
        val result = """{"status":"error","message":"duration must be positive"}"""
        val rendered = ClockResponseFormatter.format(ClockToolHandler.SET_TIMER_NAME, result)
        assertEquals("Sorry, that didn't work: duration must be positive", rendered)
    }
}
