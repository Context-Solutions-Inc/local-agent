package com.contextsolutions.localagent.agent

import com.contextsolutions.localagent.clock.AlarmDay
import com.contextsolutions.localagent.clock.AlarmScheduler
import com.contextsolutions.localagent.clock.ClockRepository
import com.contextsolutions.localagent.clock.ClockService
import com.contextsolutions.localagent.clock.AlarmEntry
import com.contextsolutions.localagent.clock.TimerEntry
import com.contextsolutions.localagent.inference.PendingToolCall
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Asserts the JSON-shaped contract between Gemma and the clock tools. The
 * tool descriptions promise:
 *
 *  - set_timer accepts hours/minutes/seconds + optional label
 *  - set_alarm requires hour + minute, accepts days[] + label
 *  - cancel_* dispatches by id, label substring, or all=true
 *  - list_* returns shape callers (and the model) can parse without hassle
 */
class ClockToolHandlerTest {

    private val clock = FakeClock(Instant.fromEpochMilliseconds(1_000_000_000L))
    private val repo = InMemoryClockRepository()
    private val scheduler = NoopScheduler()
    private val service = ClockService(
        repository = repo,
        scheduler = scheduler,
        clock = clock,
        timeZone = { kotlinx.datetime.TimeZone.UTC },
    )
    private val handler = ClockToolHandler(service, clock)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `set_timer with minutes creates a timer of that duration`() = runBlocking {
        val out = handler.execute(
            PendingToolCall("set_timer", """{"minutes": 5}"""),
        )
        val obj = json.parseToJsonElement(out).jsonObject
        assertEquals("ok", obj["status"]?.jsonPrimitive?.content)
        assertEquals("300", obj["duration_seconds"]?.jsonPrimitive?.content)
        assertEquals(1, repo.snapshotTimers().size)
    }

    @Test
    fun `set_timer with zero duration errors`() = runBlocking {
        val out = handler.execute(PendingToolCall("set_timer", """{"minutes": 0}"""))
        val obj = json.parseToJsonElement(out).jsonObject
        assertEquals("error", obj["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `set_timer tolerates float-shaped numbers from the model`() = runBlocking {
        // Gemma routinely emits `1.0` (or `5.0`) for parameters the schema
        // typed as integer — LLM tokenisation over numbers is sloppy. The
        // handler must accept this rather than silently treating it as 0.
        val out = handler.execute(
            PendingToolCall("set_timer", """{"minutes": 1.0, "label": "tea"}"""),
        )
        val obj = json.parseToJsonElement(out).jsonObject
        assertEquals("ok", obj["status"]?.jsonPrimitive?.content)
        assertEquals("60", obj["duration_seconds"]?.jsonPrimitive?.content)
    }

    @Test
    fun `set_alarm tolerates days serialised as a string`() = runBlocking {
        // Field-report regression: Gemma emitted
        //   "days":"[mon, tue, wed, thu, fri, sat, sun]"
        // (the whole array stringified) instead of a real JSON array.
        // The old parser silently dropped it and the alarm became one-shot.
        val out = handler.execute(
            PendingToolCall(
                "set_alarm",
                """{"hour": 7, "minute": 30, "days": "[mon, tue, wed, thu, fri, sat, sun]"}""",
            ),
        )
        val obj = json.parseToJsonElement(out).jsonObject
        assertEquals("ok", obj["status"]?.jsonPrimitive?.content)
        assertEquals("Every day", obj["recurrence"]?.jsonPrimitive?.content)
        assertEquals(7, repo.snapshotAlarms().single().recurringDays.size)
    }

    @Test
    fun `set_alarm tolerates bare CSV days string`() = runBlocking {
        val out = handler.execute(
            PendingToolCall(
                "set_alarm",
                """{"hour": 6, "minute": 0, "days": "mon,wed,fri"}""",
            ),
        )
        val obj = json.parseToJsonElement(out).jsonObject
        assertEquals("ok", obj["status"]?.jsonPrimitive?.content)
        assertEquals("Mon, Wed, Fri", obj["recurrence"]?.jsonPrimitive?.content)
    }

    @Test
    fun `set_alarm tolerates float-shaped hour and minute`() = runBlocking {
        val out = handler.execute(
            PendingToolCall("set_alarm", """{"hour": 7.0, "minute": 30.0}"""),
        )
        val obj = json.parseToJsonElement(out).jsonObject
        assertEquals("ok", obj["status"]?.jsonPrimitive?.content)
        assertEquals(1, repo.snapshotAlarms().size)
        assertEquals(7, repo.snapshotAlarms().single().hour)
        assertEquals(30, repo.snapshotAlarms().single().minute)
    }

    @Test
    fun `set_alarm with weekday list creates recurring alarm`() = runBlocking {
        val out = handler.execute(
            PendingToolCall(
                "set_alarm",
                """{"hour": 6, "minute": 45, "days": ["mon", "wed", "fri"], "label": "gym"}""",
            ),
        )
        val obj = json.parseToJsonElement(out).jsonObject
        assertEquals("ok", obj["status"]?.jsonPrimitive?.content)
        assertEquals("6", obj["hour"]?.jsonPrimitive?.content)
        assertEquals("45", obj["minute"]?.jsonPrimitive?.content)
        assertEquals("AM", obj["period"]?.jsonPrimitive?.content)
        assertEquals("Mon, Wed, Fri", obj["recurrence"]?.jsonPrimitive?.content)
        assertEquals("gym", repo.snapshotAlarms().single().label)
    }

    @Test
    fun `set_alarm rejects out-of-range hour`() = runBlocking {
        val out = handler.execute(PendingToolCall("set_alarm", """{"hour": 26, "minute": 0}"""))
        val obj = json.parseToJsonElement(out).jsonObject
        assertEquals("error", obj["status"]?.jsonPrimitive?.content)
        assertTrue(repo.snapshotAlarms().isEmpty())
    }

    @Test
    fun `cancel_timer by label substring removes matching rows`() = runBlocking {
        service.createTimer(60_000, "laundry")
        service.createTimer(60_000, "tea")
        val out = handler.execute(PendingToolCall("cancel_timer", """{"label": "laun"}"""))
        val obj = json.parseToJsonElement(out).jsonObject
        assertEquals("1", obj["cancelled_count"]?.jsonPrimitive?.content)
        assertEquals(1, repo.snapshotTimers().size)
        assertEquals("tea", repo.snapshotTimers().single().label)
    }

    @Test
    fun `cancel_alarm all=true clears everything`() = runBlocking {
        service.createAlarm(7, 0, setOf(AlarmDay.MONDAY))
        service.createAlarm(8, 30)
        val out = handler.execute(PendingToolCall("cancel_alarm", """{"all": true}"""))
        val obj = json.parseToJsonElement(out).jsonObject
        assertEquals("2", obj["cancelled_count"]?.jsonPrimitive?.content)
        assertTrue(repo.snapshotAlarms().isEmpty())
    }

    @Test
    fun `list_timers returns remaining_seconds for each row`() = runBlocking {
        service.createTimer(60_000, "tea")
        val out = handler.execute(PendingToolCall("list_timers", ""))
        val obj = json.parseToJsonElement(out).jsonObject
        assertEquals("1", obj["count"]?.jsonPrimitive?.content)
        val rows = obj["timers"]?.jsonArray
        assertNotNull(rows)
        val first = rows!!.first().jsonObject
        assertEquals("tea", first["label"]?.jsonPrimitive?.content)
        assertEquals("60", first["remaining_seconds"]?.jsonPrimitive?.content)
    }

    @Test
    fun `list_alarms returns 12h time parts and human recurrence label`() = runBlocking {
        // 15:55 every day — covers the bug from the field report: the model
        // used to receive "15:55" and a raw days array and render "5:5:5
        // on weekdays" in its reply. Split fields shift rendering to the
        // model itself so it can't garble the "H:MM PERIOD" pattern.
        service.createAlarm(
            hour = 15,
            minute = 55,
            recurringDays = setOf(
                AlarmDay.MONDAY, AlarmDay.TUESDAY, AlarmDay.WEDNESDAY,
                AlarmDay.THURSDAY, AlarmDay.FRIDAY, AlarmDay.SATURDAY, AlarmDay.SUNDAY,
            ),
            label = "afternoon",
        )
        val out = handler.execute(PendingToolCall("list_alarms", "{}"))
        val obj = json.parseToJsonElement(out).jsonObject
        val first = obj["alarms"]!!.jsonArray.first().jsonObject
        assertEquals("3", first["hour"]?.jsonPrimitive?.content)
        assertEquals("55", first["minute"]?.jsonPrimitive?.content)
        assertEquals("PM", first["period"]?.jsonPrimitive?.content)
        assertEquals("Every day", first["recurrence"]?.jsonPrimitive?.content)
        assertEquals("true", first["enabled"]?.jsonPrimitive?.content)
        assertEquals("afternoon", first["label"]?.jsonPrimitive?.content)
    }

    @Test
    fun `list_alarms zero-pads single-digit minutes as a string`() = runBlocking {
        // Minutes used to be emitted as JSON ints; small models occasionally
        // appended an extra digit when re-emitting ("3:55" -> "3:555" in a
        // real log). The handler now emits the minute as a pre-padded string
        // so the model concatenates a string token instead of formatting an int.
        service.createAlarm(hour = 7, minute = 5)
        val out = handler.execute(PendingToolCall("list_alarms", "{}"))
        val obj = json.parseToJsonElement(out).jsonObject
        val first = obj["alarms"]!!.jsonArray.first().jsonObject
        assertEquals("05", first["minute"]?.jsonPrimitive?.content)
    }

    @Test
    fun `list_alarms 12h conversion handles midnight and noon`() = runBlocking {
        service.createAlarm(hour = 0, minute = 0)
        service.createAlarm(hour = 12, minute = 0)
        val out = handler.execute(PendingToolCall("list_alarms", "{}"))
        val obj = json.parseToJsonElement(out).jsonObject
        val rows = obj["alarms"]!!.jsonArray.map { it.jsonObject }
        val pairs = rows.map {
            it["hour"]?.jsonPrimitive?.content to it["period"]?.jsonPrimitive?.content
        }.toSet()
        assertEquals(setOf("12" to "AM", "12" to "PM"), pairs)
    }

    @Test
    fun `handles returns true for clock names false for others`() {
        assertTrue(handler.handles("set_timer"))
        assertTrue(handler.handles("list_alarms"))
        assertEquals(false, handler.handles("web_search"))
    }
}

private class FakeClock(private var instant: Instant) : Clock {
    override fun now(): Instant = instant
}

private class NoopScheduler : AlarmScheduler {
    override fun scheduleTimer(timerId: String, fireAtEpochMs: Long) = Unit
    override fun cancelTimer(timerId: String) = Unit
    override fun scheduleAlarm(alarmId: String, fireAtEpochMs: Long) = Unit
    override fun cancelAlarm(alarmId: String) = Unit
    override fun stopFiringAlarm(alarmId: String) = Unit
}

private class InMemoryClockRepository : ClockRepository {
    private val timersState = MutableStateFlow<List<TimerEntry>>(emptyList())
    private val alarmsState = MutableStateFlow<List<AlarmEntry>>(emptyList())
    override fun timers() = timersState.asStateFlow()
    override fun alarms() = alarmsState.asStateFlow()
    override fun snapshotTimers() = timersState.value
    override fun snapshotAlarms() = alarmsState.value
    override fun upsertTimer(timer: TimerEntry) {
        timersState.value = timersState.value.filterNot { it.id == timer.id } + timer
    }
    override fun deleteTimer(id: String) {
        timersState.value = timersState.value.filterNot { it.id == id }
    }
    override fun upsertAlarm(alarm: AlarmEntry) {
        alarmsState.value = alarmsState.value.filterNot { it.id == alarm.id } + alarm
    }
    override fun deleteAlarm(id: String) {
        alarmsState.value = alarmsState.value.filterNot { it.id == id }
    }
}
