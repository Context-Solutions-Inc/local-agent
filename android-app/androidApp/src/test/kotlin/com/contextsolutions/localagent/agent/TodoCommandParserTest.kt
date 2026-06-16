package com.contextsolutions.localagent.agent

import com.contextsolutions.localagent.todo.TodoPriority
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks down [TodoCommandParser] coverage for PR #15. The parser is the
 * deterministic gate that keeps TODO turns out of Gemma; any false-positive
 * here would silently dispatch the wrong tool, and any false-negative would
 * surface a guidance reply instead of a proper action. Both directions are
 * load-bearing — the test suite covers each verb family, the leading
 * scaffolding strip, ref resolution (index + title substring), and the
 * partial-match cases that MUST return null.
 */
class TodoCommandParserTest {

    private val fixedNow: Instant = Instant.parse("2026-05-15T12:00:00Z")
    private val tz: TimeZone = TimeZone.UTC
    private val parser = TodoCommandParser(
        clock = object : Clock { override fun now(): Instant = fixedNow },
        timeZoneProvider = { tz },
    )

    private fun dueDateEpochMs(daysFromBase: Int): Long {
        val base = fixedNow.toLocalDateTime(tz).date.plus(daysFromBase, DateTimeUnit.DAY)
        return LocalDateTime(base, LocalTime(0, 0)).toInstant(tz).toEpochMilliseconds()
    }

    // -- add ------------------------------------------------------------------

    @Test
    fun `add bare title resolves to plain Add`() {
        val cmd = parser.parse("add buy milk to my todos")
        assertEquals(TodoCommand.Add("buy milk", null, null), cmd)
    }

    @Test
    fun `add with trailing priority`() {
        val cmd = parser.parse("add finish report with high priority")
        assertEquals(TodoCommand.Add("finish report", TodoPriority.HIGH, null), cmd)
    }

    @Test
    fun `add with priority and due date`() {
        val cmd = parser.parse("add finish report with high priority by tomorrow")
        assertEquals(
            TodoCommand.Add("finish report", TodoPriority.HIGH, dueDateEpochMs(1)),
            cmd,
        )
    }

    @Test
    fun `add with ISO date`() {
        val cmd = parser.parse("add submit taxes due 2026-06-01")
        assertEquals(
            TodoCommand.Add(
                "submit taxes",
                null,
                LocalDateTime(2026, 6, 1, 0, 0).toInstant(tz).toEpochMilliseconds(),
            ),
            cmd,
        )
    }

    @Test
    fun `remember to is an add anchor`() {
        val cmd = parser.parse("remember to call mom")
        assertEquals(TodoCommand.Add("call mom", null, null), cmd)
    }

    @Test
    fun `i need to is an add anchor`() {
        val cmd = parser.parse("i need to email Alex by today")
        assertEquals(
            TodoCommand.Add("email Alex", null, dueDateEpochMs(0)),
            cmd,
        )
    }

    @Test
    fun `leading scaffolding stripped before add anchor`() {
        val cmd = parser.parse("actually, add buy milk to my todos")
        assertEquals(TodoCommand.Add("buy milk", null, null), cmd)
    }

    @Test
    fun `add high-priority noun-prefix form`() {
        val cmd = parser.parse("add a high priority todo: ship PR")
        assertEquals(TodoCommand.Add("ship PR", TodoPriority.HIGH, null), cmd)
    }

    // -- list -----------------------------------------------------------------

    @Test
    fun `list todos`() {
        assertEquals(TodoCommand.List(includeCompleted = false), parser.parse("list my todos"))
    }

    @Test
    fun `whats on my todo list`() {
        assertNotNull(parser.parse("what's on my todo list"))
    }

    @Test
    fun `show open tasks`() {
        assertEquals(
            TodoCommand.List(includeCompleted = false),
            parser.parse("show open tasks"),
        )
    }

    @Test
    fun `list all todos includes completed`() {
        assertEquals(
            TodoCommand.List(includeCompleted = true),
            parser.parse("list all todos"),
        )
    }

    // -- complete / uncomplete -----------------------------------------------

    @Test
    fun `complete by index`() {
        val cmd = parser.parse("complete #2")
        assertEquals(TodoCommand.SetCompleted(TodoRef.Index(2), completed = true), cmd)
    }

    @Test
    fun `mark todo 3 done`() {
        val cmd = parser.parse("mark todo 3 done")
        assertEquals(TodoCommand.SetCompleted(TodoRef.Index(3), completed = true), cmd)
    }

    @Test
    fun `tick off the gym task`() {
        val cmd = parser.parse("tick off the gym task")
        assertEquals(
            TodoCommand.SetCompleted(TodoRef.TitleSubstring("gym"), completed = true),
            cmd,
        )
    }

    @Test
    fun `reopen by index`() {
        val cmd = parser.parse("reopen #1")
        assertEquals(TodoCommand.SetCompleted(TodoRef.Index(1), completed = false), cmd)
    }

    @Test
    fun `mark #3 not done`() {
        val cmd = parser.parse("mark #3 as not done")
        assertEquals(TodoCommand.SetCompleted(TodoRef.Index(3), completed = false), cmd)
    }

    // -- delete --------------------------------------------------------------

    @Test
    fun `delete by index`() {
        assertEquals(
            TodoCommand.Delete(TodoRef.Index(2)),
            parser.parse("delete #2"),
        )
    }

    @Test
    fun `delete by title substring`() {
        assertEquals(
            TodoCommand.Delete(TodoRef.TitleSubstring("milk")),
            parser.parse("delete the milk task"),
        )
    }

    @Test
    fun `clear completed`() {
        assertEquals(
            TodoCommand.ClearCompleted,
            parser.parse("clear completed todos"),
        )
    }

    @Test
    fun `delete completed task is clear-completed`() {
        // The clear-completed pattern wins over the generic delete pattern.
        assertEquals(
            TodoCommand.ClearCompleted,
            parser.parse("delete all completed tasks"),
        )
    }

    // -- priority / due date / rename ----------------------------------------

    @Test
    fun `set index to high priority`() {
        assertEquals(
            TodoCommand.SetPriority(TodoRef.Index(2), TodoPriority.HIGH),
            parser.parse("set #2 to high priority"),
        )
    }

    @Test
    fun `make task low priority`() {
        // "make" verb; "todo 1" → Index(1); trailing "low priority"
        assertEquals(
            TodoCommand.SetPriority(TodoRef.Index(1), TodoPriority.LOW),
            parser.parse("make todo 1 low priority"),
        )
    }

    @Test
    fun `move #2 to tomorrow`() {
        assertEquals(
            TodoCommand.SetDueDate(TodoRef.Index(2), dueDateEpochMs(1)),
            parser.parse("move #2 to tomorrow"),
        )
    }

    @Test
    fun `set due date of milk to 2026-06-01`() {
        assertEquals(
            TodoCommand.SetDueDate(
                TodoRef.TitleSubstring("milk"),
                LocalDateTime(2026, 6, 1, 0, 0).toInstant(tz).toEpochMilliseconds(),
            ),
            parser.parse("set the due date of the milk task to 2026-06-01"),
        )
    }

    @Test
    fun `rename to new title`() {
        assertEquals(
            TodoCommand.SetTitle(TodoRef.Index(2), "buy oat milk"),
            parser.parse("rename #2 to buy oat milk"),
        )
    }

    @Test
    fun `rename with quoted title`() {
        assertEquals(
            TodoCommand.SetTitle(TodoRef.Index(3), "Call mom"),
            parser.parse("""rename #3 to "Call mom""""),
        )
    }

    // -- partial-match / non-matches MUST return null ------------------------

    @Test
    fun `vague todo prose returns null`() {
        assertNull(parser.parse("do something with my todos"))
    }

    @Test
    fun `bare 'tasks' word with no verb returns null`() {
        assertNull(parser.parse("we have tasks at work today"))
    }

    @Test
    fun `empty input returns null`() {
        assertNull(parser.parse("   "))
    }

    @Test
    fun `clock-style phrase returns null for todo parser`() {
        assertNull(parser.parse("set a 5 minute timer"))
    }

    @Test
    fun `set with no valid priority or date returns null`() {
        // "set #2 to the gym" — "gym" isn't a priority or a date.
        assertNull(parser.parse("set #2 to the gym"))
    }

    @Test
    fun `move with natural-language date that is NOT in the narrow grammar returns null`() {
        // "next tuesday" is intentionally NOT supported in v1; the parser
        // must return null so the agent loop emits the static guidance
        // reply rather than guessing.
        assertNull(parser.parse("move #2 to next tuesday"))
    }

    // -- ref resolution (indirect, through the verb-anchored regexes) -------

    @Test
    fun `index ref with hash prefix`() {
        assertEquals(
            TodoCommand.Delete(TodoRef.Index(7)),
            parser.parse("delete #7"),
        )
    }

    @Test
    fun `index ref without hash`() {
        assertEquals(
            TodoCommand.SetCompleted(TodoRef.Index(7), completed = true),
            parser.parse("complete 7"),
        )
    }

    @Test
    fun `title substring strips trailing task word`() {
        assertEquals(
            TodoCommand.Delete(TodoRef.TitleSubstring("gym")),
            parser.parse("delete the gym task"),
        )
    }
}
