package com.contextsolutions.localagent.agent

import com.contextsolutions.localagent.mylist.MyListItemPriority
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
import org.junit.Test

/**
 * Locks down [MyListCommandParser] coverage. The parser is the deterministic
 * gate that keeps My List turns out of Gemma; any false-positive here would
 * silently dispatch the wrong tool, and any false-negative would surface a
 * guidance reply instead of a proper action.
 *
 * PR #99 invariant: every command MUST name the surface as "my list". The
 * parser peels off the "<preposition> my list" locator before running the
 * verb grammar, so "complete #2 on my list" resolves the same as the old
 * bare "complete #2" — but the bare form (no "my list") now returns null.
 */
class MyListCommandParserTest {

    private val fixedNow: Instant = Instant.parse("2026-05-15T12:00:00Z")
    private val tz: TimeZone = TimeZone.UTC
    private val parser = MyListCommandParser(
        clock = object : Clock { override fun now(): Instant = fixedNow },
        timeZoneProvider = { tz },
    )

    private fun dueDateEpochMs(daysFromBase: Int): Long {
        val base = fixedNow.toLocalDateTime(tz).date.plus(daysFromBase, DateTimeUnit.DAY)
        return LocalDateTime(base, LocalTime(0, 0)).toInstant(tz).toEpochMilliseconds()
    }

    // -- the "my list" gate ---------------------------------------------------

    @Test
    fun `command without my list phrase returns null`() {
        // The whole point of PR #99: a bare verb command is no longer a list
        // command — the surface must be named explicitly.
        assertNull(parser.parse("complete #2"))
        assertNull(parser.parse("add buy milk"))
        assertNull(parser.parse("list the planets"))
    }

    // -- add ------------------------------------------------------------------

    @Test
    fun `add bare title resolves to plain Add`() {
        val cmd = parser.parse("add buy milk to my list")
        assertEquals(MyListCommand.Add("buy milk", null, null), cmd)
    }

    @Test
    fun `add with trailing priority`() {
        val cmd = parser.parse("add finish report with high priority to my list")
        assertEquals(MyListCommand.Add("finish report", MyListItemPriority.HIGH, null), cmd)
    }

    @Test
    fun `add with priority and due date`() {
        val cmd = parser.parse("add finish report with high priority by tomorrow to my list")
        assertEquals(
            MyListCommand.Add("finish report", MyListItemPriority.HIGH, dueDateEpochMs(1)),
            cmd,
        )
    }

    @Test
    fun `add with ISO date`() {
        val cmd = parser.parse("add submit taxes due 2026-06-01 to my list")
        assertEquals(
            MyListCommand.Add(
                "submit taxes",
                null,
                LocalDateTime(2026, 6, 1, 0, 0).toInstant(tz).toEpochMilliseconds(),
            ),
            cmd,
        )
    }

    @Test
    fun `remember to is an add anchor`() {
        val cmd = parser.parse("remember to call mom on my list")
        assertEquals(MyListCommand.Add("call mom", null, null), cmd)
    }

    @Test
    fun `i need to is an add anchor`() {
        val cmd = parser.parse("i need to email Alex by today on my list")
        assertEquals(
            MyListCommand.Add("email Alex", null, dueDateEpochMs(0)),
            cmd,
        )
    }

    @Test
    fun `leading scaffolding stripped before add anchor`() {
        val cmd = parser.parse("actually, add buy milk to my list")
        assertEquals(MyListCommand.Add("buy milk", null, null), cmd)
    }

    @Test
    fun `add high-priority noun-prefix form`() {
        val cmd = parser.parse("add a high priority item: ship PR to my list")
        assertEquals(MyListCommand.Add("ship PR", MyListItemPriority.HIGH, null), cmd)
    }

    // -- show -----------------------------------------------------------------

    @Test
    fun `show my list`() {
        assertEquals(MyListCommand.Show(includeCompleted = false), parser.parse("show my list"))
    }

    @Test
    fun `bare my list`() {
        assertEquals(MyListCommand.Show(includeCompleted = false), parser.parse("my list"))
    }

    @Test
    fun `whats on my list`() {
        assertNotNull(parser.parse("what's on my list"))
    }

    @Test
    fun `show all of my list includes completed`() {
        // The "my list" gate keeps the phrase intact, so the completed-view
        // qualifier rides before it ("all of my list") rather than between.
        assertEquals(
            MyListCommand.Show(includeCompleted = true),
            parser.parse("show all of my list"),
        )
        assertEquals(
            MyListCommand.Show(includeCompleted = true),
            parser.parse("show my list including completed"),
        )
    }

    // -- complete / uncomplete -----------------------------------------------

    @Test
    fun `complete by index`() {
        val cmd = parser.parse("complete #2 on my list")
        assertEquals(MyListCommand.SetCompleted(MyListRef.Index(2), completed = true), cmd)
    }

    @Test
    fun `mark item 3 done`() {
        val cmd = parser.parse("mark item 3 done on my list")
        assertEquals(MyListCommand.SetCompleted(MyListRef.Index(3), completed = true), cmd)
    }

    @Test
    fun `tick off the gym item`() {
        val cmd = parser.parse("tick off the gym item on my list")
        assertEquals(
            MyListCommand.SetCompleted(MyListRef.TitleSubstring("gym"), completed = true),
            cmd,
        )
    }

    @Test
    fun `reopen by index`() {
        val cmd = parser.parse("reopen #1 on my list")
        assertEquals(MyListCommand.SetCompleted(MyListRef.Index(1), completed = false), cmd)
    }

    @Test
    fun `mark #3 not done`() {
        val cmd = parser.parse("mark #3 as not done on my list")
        assertEquals(MyListCommand.SetCompleted(MyListRef.Index(3), completed = false), cmd)
    }

    // -- delete --------------------------------------------------------------

    @Test
    fun `delete by index`() {
        assertEquals(
            MyListCommand.Delete(MyListRef.Index(2)),
            parser.parse("delete #2 from my list"),
        )
    }

    @Test
    fun `delete by title substring`() {
        assertEquals(
            MyListCommand.Delete(MyListRef.TitleSubstring("milk")),
            parser.parse("remove the milk item from my list"),
        )
    }

    @Test
    fun `clear completed`() {
        assertEquals(
            MyListCommand.ClearCompleted,
            parser.parse("clear completed items from my list"),
        )
    }

    @Test
    fun `delete completed items is clear-completed`() {
        // The clear-completed pattern wins over the generic delete pattern.
        assertEquals(
            MyListCommand.ClearCompleted,
            parser.parse("delete all completed items from my list"),
        )
    }

    // -- priority / due date / rename ----------------------------------------

    @Test
    fun `set index to high priority`() {
        assertEquals(
            MyListCommand.SetPriority(MyListRef.Index(2), MyListItemPriority.HIGH),
            parser.parse("set #2 to high priority on my list"),
        )
    }

    @Test
    fun `make item low priority`() {
        assertEquals(
            MyListCommand.SetPriority(MyListRef.Index(1), MyListItemPriority.LOW),
            parser.parse("make item 1 low priority on my list"),
        )
    }

    @Test
    fun `move #2 to tomorrow`() {
        assertEquals(
            MyListCommand.SetDueDate(MyListRef.Index(2), dueDateEpochMs(1)),
            parser.parse("move #2 to tomorrow on my list"),
        )
    }

    @Test
    fun `set due date of milk to 2026-06-01`() {
        assertEquals(
            MyListCommand.SetDueDate(
                MyListRef.TitleSubstring("milk"),
                LocalDateTime(2026, 6, 1, 0, 0).toInstant(tz).toEpochMilliseconds(),
            ),
            parser.parse("set the due date of the milk item to 2026-06-01 on my list"),
        )
    }

    @Test
    fun `rename to new title`() {
        assertEquals(
            MyListCommand.SetTitle(MyListRef.Index(2), "buy oat milk"),
            parser.parse("rename #2 to buy oat milk on my list"),
        )
    }

    @Test
    fun `rename with quoted title`() {
        assertEquals(
            MyListCommand.SetTitle(MyListRef.Index(3), "Call mom"),
            parser.parse("""rename #3 to "Call mom" on my list"""),
        )
    }

    // -- partial-match / non-matches MUST return null ------------------------

    @Test
    fun `vague my-list prose returns null`() {
        assertNull(parser.parse("do something with my list"))
    }

    @Test
    fun `empty input returns null`() {
        assertNull(parser.parse("   "))
    }

    @Test
    fun `clock-style phrase returns null`() {
        assertNull(parser.parse("set a 5 minute timer"))
    }

    @Test
    fun `set with no valid priority or date returns null`() {
        // "set #2 to the gym" — "gym" isn't a priority or a date.
        assertNull(parser.parse("set #2 to the gym on my list"))
    }

    @Test
    fun `move with natural-language date that is NOT in the narrow grammar returns null`() {
        // "next tuesday" is intentionally NOT supported in v1; the parser
        // must return null so the agent loop emits the static guidance
        // reply rather than guessing.
        assertNull(parser.parse("move #2 to next tuesday on my list"))
    }

    // -- ref resolution (indirect, through the verb-anchored regexes) -------

    @Test
    fun `index ref with hash prefix`() {
        assertEquals(
            MyListCommand.Delete(MyListRef.Index(7)),
            parser.parse("delete #7 from my list"),
        )
    }

    @Test
    fun `index ref without hash`() {
        assertEquals(
            MyListCommand.SetCompleted(MyListRef.Index(7), completed = true),
            parser.parse("complete 7 on my list"),
        )
    }

    @Test
    fun `title substring strips trailing item word`() {
        assertEquals(
            MyListCommand.Delete(MyListRef.TitleSubstring("gym")),
            parser.parse("delete the gym item from my list"),
        )
    }
}
