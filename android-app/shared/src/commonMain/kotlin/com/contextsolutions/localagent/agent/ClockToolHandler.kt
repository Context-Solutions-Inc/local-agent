package com.contextsolutions.localagent.agent

import com.contextsolutions.localagent.clock.AlarmDay
import com.contextsolutions.localagent.clock.AlarmEntry
import com.contextsolutions.localagent.clock.ClockService
import com.contextsolutions.localagent.clock.TimerEntry
import com.contextsolutions.localagent.inference.PendingToolCall
import com.contextsolutions.localagent.inference.ToolDefinition
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Tool handler that exposes the on-device clock (alarms + timers) to Gemma
 * via the LiteRT-LM tool channel. Six tools:
 *
 *  - [SET_TIMER_NAME] — start a countdown timer (hours/minutes/seconds + label)
 *  - [SET_ALARM_NAME] — schedule a wall-clock alarm (24h time + optional weekday recurrence)
 *  - [CANCEL_TIMER_NAME] — cancel by label substring match or `all: true`
 *  - [CANCEL_ALARM_NAME] — same for alarms
 *  - [LIST_TIMERS_NAME] — current timers (label + remaining time)
 *  - [LIST_ALARMS_NAME] — current alarms (time + recurrence + enabled)
 *
 * Tool descriptions are deliberately terse so Gemma doesn't over-call (the
 * web_search description block already trained the prompt budget). The
 * "when to use" cue is in the description's first sentence; everything else
 * is parameter docs.
 *
 * Cancellation uses label match (not id) so the chat path doesn't need to
 * round-trip an opaque uuid through history. Ids are still surfaced in
 * `list_*` results for completeness — Gemma can call `cancel_*` with `id`
 * if it has one, but the simpler label/`all` path is what the descriptions
 * encourage.
 */
class ClockToolHandler(
    private val clockService: ClockService,
    private val clock: Clock = Clock.System,
) : ToolHandler {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override val definitions: List<ToolDefinition> = listOf(
        ToolDefinition(SET_TIMER_NAME, SET_TIMER_SCHEMA),
        ToolDefinition(SET_ALARM_NAME, SET_ALARM_SCHEMA),
        ToolDefinition(CANCEL_TIMER_NAME, CANCEL_TIMER_SCHEMA),
        ToolDefinition(CANCEL_ALARM_NAME, CANCEL_ALARM_SCHEMA),
        ToolDefinition(LIST_TIMERS_NAME, LIST_TIMERS_SCHEMA),
        ToolDefinition(LIST_ALARMS_NAME, LIST_ALARMS_SCHEMA),
    )

    private val toolNames: Set<String> = definitions.map { it.name }.toSet()

    override fun handles(toolName: String): Boolean = toolName in toolNames

    override suspend fun execute(call: PendingToolCall): String {
        val args = parseArgs(call.argumentsJson) ?: return errorPayload("could not parse arguments")
        return when (call.name) {
            SET_TIMER_NAME -> handleSetTimer(args)
            SET_ALARM_NAME -> handleSetAlarm(args)
            CANCEL_TIMER_NAME -> handleCancelTimer(args)
            CANCEL_ALARM_NAME -> handleCancelAlarm(args)
            LIST_TIMERS_NAME -> handleListTimers()
            LIST_ALARMS_NAME -> handleListAlarms()
            else -> errorPayload("unknown tool '${call.name}'")
        }
    }

    private fun handleSetTimer(args: JsonObject): String {
        val hours = args["hours"]?.asInt() ?: 0
        val minutes = args["minutes"]?.asInt() ?: 0
        val seconds = args["seconds"]?.asInt() ?: 0
        val label = args["label"]?.asStringOrNull()
        val totalMs = ((hours * 3600L) + (minutes * 60L) + seconds) * 1000L
        if (totalMs <= 0) return errorPayload("timer duration must be positive (hours/minutes/seconds)")
        val timer = clockService.createTimer(totalMs, label)
        return resultJson {
            put("status", "ok")
            put("id", timer.id)
            put("duration_seconds", totalMs / 1000)
            put("fires_at_epoch_ms", timer.fireAtEpochMs)
            if (timer.label != null) put("label", timer.label)
        }
    }

    private fun handleSetAlarm(args: JsonObject): String {
        val hour = args["hour"]?.asInt() ?: return errorPayload("'hour' (0-23) required")
        val minute = args["minute"]?.asInt() ?: return errorPayload("'minute' (0-59) required")
        if (hour !in 0..23) return errorPayload("'hour' must be 0-23")
        if (minute !in 0..59) return errorPayload("'minute' must be 0-59")
        val days: Set<AlarmDay> = parseDays(args["days"])
        val label = args["label"]?.asStringOrNull()
        val alarm = clockService.createAlarm(hour, minute, days, label)
            ?: return errorPayload("alarm creation failed")
        return resultJson {
            put("status", "ok")
            put("id", alarm.id)
            val (display12, period) = to12hDisplay(alarm.hour)
            put("hour", display12.toString())
            put("minute", alarm.minute.toString().padStart(2, '0'))
            put("period", period)
            put("recurrence", recurrenceLabel(alarm.recurringDays))
            if (alarm.label != null) put("label", alarm.label)
        }
    }

    private fun handleCancelTimer(args: JsonObject): String {
        val all = args["all"]?.asBoolean() ?: false
        val id = args["id"]?.asStringOrNull()
        val label = args["label"]?.asStringOrNull()
        val timers = clockService.timersSnapshot()
        val matches = when {
            all -> timers
            id != null -> timers.filter { it.id == id }
            label != null -> timers.filter { it.label?.contains(label, ignoreCase = true) == true }
            else -> return errorPayload("specify 'all', 'id', or 'label'")
        }
        for (t in matches) clockService.cancelTimer(t.id)
        return resultJson {
            put("status", "ok")
            put("cancelled_count", matches.size)
        }
    }

    private fun handleCancelAlarm(args: JsonObject): String {
        val all = args["all"]?.asBoolean() ?: false
        val id = args["id"]?.asStringOrNull()
        val label = args["label"]?.asStringOrNull()
        val alarms = clockService.alarmsSnapshot()
        val matches = when {
            all -> alarms
            id != null -> alarms.filter { it.id == id }
            label != null -> alarms.filter { it.label?.contains(label, ignoreCase = true) == true }
            else -> return errorPayload("specify 'all', 'id', or 'label'")
        }
        for (a in matches) clockService.cancelAlarm(a.id)
        return resultJson {
            put("status", "ok")
            put("cancelled_count", matches.size)
        }
    }

    private fun handleListTimers(): String {
        val nowMs = clock.now().toEpochMilliseconds()
        val timers = clockService.timersSnapshot()
        return resultJson {
            put("count", timers.size)
            put("timers", buildJsonArray {
                for (t in timers) add(timerJson(t, nowMs))
            })
        }
    }

    private fun handleListAlarms(): String {
        val alarms = clockService.alarmsSnapshot()
        return resultJson {
            put("count", alarms.size)
            put("alarms", buildJsonArray {
                for (a in alarms) add(alarmJson(a))
            })
        }
    }

    private fun timerJson(t: TimerEntry, nowMs: Long): JsonObject = buildJsonObject {
        put("id", t.id)
        if (t.label != null) put("label", t.label)
        val remaining = (t.fireAtEpochMs - nowMs).coerceAtLeast(0)
        put("remaining_seconds", remaining / 1000)
    }

    private fun alarmJson(a: AlarmEntry): JsonObject = buildJsonObject {
        put("id", a.id)
        if (a.label != null) put("label", a.label)
        // Time as pre-formatted string fields: hour ("1".."12"), minute
        // ("00".."59"), period ("AM"/"PM"). Earlier iterations sent these
        // as JSON ints — Gemma then appended extra digits when rendering
        // ("3:55" became "3:555" in a real log) because small models are
        // unreliable at re-emitting numeric tokens. Strings copy through
        // verbatim, so the model just concatenates them as `H:MM PERIOD`.
        val (display12, period) = to12hDisplay(a.hour)
        put("hour", display12.toString())
        put("minute", a.minute.toString().padStart(2, '0'))
        put("period", period)
        put("enabled", a.enabled)
        put("recurrence", recurrenceLabel(a.recurringDays))
    }

    private fun parseArgs(raw: String): JsonObject? = try {
        json.parseToJsonElement(raw).jsonObject
    } catch (_: Throwable) {
        // Some tool calls arrive with empty args ({} or "") for list_* tools.
        try {
            json.parseToJsonElement(if (raw.isBlank()) "{}" else raw).jsonObject
        } catch (_: Throwable) {
            null
        }
    }

    private fun resultJson(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String =
        Json.encodeToString(JsonObject.serializer(), buildJsonObject(build))

    private fun errorPayload(message: String): String =
        resultJson {
            put("status", "error")
            put("message", message)
        }

    /**
     * Accepts both shapes Gemma actually emits:
     *  - A proper JSON array: `["mon","wed","fri"]`
     *  - A *string* that looks like an array: `"[mon, tue, wed, thu, fri, sat, sun]"`
     *    or `"mon,tue,wed"` — observed in production logs where the model
     *    inlined the schema's `items: {type: "string"}` constraint to mean
     *    "stringify the whole thing".
     *
     * Anything else (null, unrecognised type, all-day-tokens-invalid) returns
     * an empty set so the alarm falls back to one-shot, which is at least
     * recoverable via the UI vs. a hard error.
     */
    private fun parseDays(element: kotlinx.serialization.json.JsonElement?): Set<AlarmDay> {
        if (element == null) return emptySet()
        return when (element) {
            is JsonArray -> element
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull()?.let(::parseDay) }
                .toSet()
            is JsonPrimitive -> {
                if (!element.isString) return emptySet()
                element.content
                    .trim()
                    .removePrefix("[").removeSuffix("]")
                    .split(',')
                    .mapNotNull { token -> parseDay(token.trim().trim('"', '\'')) }
                    .toSet()
            }
            else -> emptySet()
        }
    }

    private fun parseDay(s: String): AlarmDay? = when (s.trim().lowercase()) {
        "mon", "monday" -> AlarmDay.MONDAY
        "tue", "tues", "tuesday" -> AlarmDay.TUESDAY
        "wed", "weds", "wednesday" -> AlarmDay.WEDNESDAY
        "thu", "thurs", "thursday" -> AlarmDay.THURSDAY
        "fri", "friday" -> AlarmDay.FRIDAY
        "sat", "saturday" -> AlarmDay.SATURDAY
        "sun", "sunday" -> AlarmDay.SUNDAY
        else -> null
    }

    private fun pad(v: Int): String = v.toString().padStart(2, '0')

    /**
     * Convert a 24h wall-clock hour to the (display-hour, period) pair the
     * tool returns. Returned hour is 1-12 (never 0 or 13+). Period is "AM"
     * or "PM". Midnight (0) → (12, "AM"); noon (12) → (12, "PM").
     */
    private fun to12hDisplay(hour: Int): Pair<Int, String> {
        val period = if (hour < 12) "AM" else "PM"
        val display = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        return display to period
    }

    /**
     * Human-readable recurrence label that Gemma can repeat verbatim.
     * Returns "Once" / "Every day" / "Weekdays" / "Weekends" / "Mon, Wed, Fri".
     * Mirrors the same set comparisons the AlarmSheet UI uses so chat and
     * UI stay consistent.
     */
    private fun recurrenceLabel(days: Set<AlarmDay>): String {
        if (days.isEmpty()) return "Once"
        return when (days) {
            WEEKDAYS -> "Weekdays"
            WEEKENDS -> "Weekends"
            ALL_DAYS -> "Every day"
            else -> days.toList()
                .sortedBy { it.ordinal }
                .joinToString(", ") { it.shortName() }
        }
    }

    private fun AlarmDay.shortName(): String = when (this) {
        AlarmDay.MONDAY -> "Mon"
        AlarmDay.TUESDAY -> "Tue"
        AlarmDay.WEDNESDAY -> "Wed"
        AlarmDay.THURSDAY -> "Thu"
        AlarmDay.FRIDAY -> "Fri"
        AlarmDay.SATURDAY -> "Sat"
        AlarmDay.SUNDAY -> "Sun"
    }


    /**
     * Accepts integer-or-float JSON numeric content. Gemma routinely emits
     * `1.0` for parameters typed as `integer` in the schema (LLM tokenisation
     * over numbers is sloppy), and `"1.0".toIntOrNull()` returns null, which
     * silently became "duration of 0" in v1. We fall back to a Double parse
     * and truncate so 1.0 → 1 and 1.7 → 1 (and would be clear to the user
     * from the result that we rounded down).
     */
    private fun kotlinx.serialization.json.JsonElement.asInt(): Int? = try {
        val p = (this as? JsonPrimitive) ?: return null
        val content = p.content
        content.toIntOrNull() ?: content.toDoubleOrNull()?.toInt()
    } catch (_: Throwable) {
        null
    }

    private fun kotlinx.serialization.json.JsonElement.asBoolean(): Boolean? = try {
        (this as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
    } catch (_: Throwable) {
        null
    }

    private fun kotlinx.serialization.json.JsonElement.asStringOrNull(): String? {
        val p = this as? JsonPrimitive ?: return null
        if (!p.isString) return null
        return p.content.takeIf { it.isNotBlank() }
    }

    private fun JsonPrimitive.contentOrNull(): String? =
        if (isString) content else content.takeIf { it.isNotBlank() }

    companion object {
        const val SET_TIMER_NAME = "set_timer"
        const val SET_ALARM_NAME = "set_alarm"
        const val CANCEL_TIMER_NAME = "cancel_timer"
        const val CANCEL_ALARM_NAME = "cancel_alarm"
        const val LIST_TIMERS_NAME = "list_timers"
        const val LIST_ALARMS_NAME = "list_alarms"

        // Schemas — kept compact. Gemma over-calls when descriptions are too
        // chatty. First sentence is the "when to use" cue.
        const val SET_TIMER_SCHEMA = """{
  "name": "set_timer",
  "description": "CALL THIS for RELATIVE durations only — the user wants to be alerted N seconds/minutes/hours from now. Examples that call this tool: 'set a 5 minute timer', '25 minute timer for studying', 'remind me in 90 seconds', '1-minute timer for tea'. DO NOT call this for wall-clock times like '11:45 AM' or 'wake me at 7am' — those are alarms, use set_alarm instead.",
  "parameters": {
    "type": "object",
    "properties": {
      "hours": {"type": "integer", "description": "Hours portion of the duration. Optional."},
      "minutes": {"type": "integer", "description": "Minutes portion of the duration. Optional."},
      "seconds": {"type": "integer", "description": "Seconds portion of the duration. Optional."},
      "label": {"type": "string", "description": "Short label (e.g., 'tea', 'studying', 'laundry'). Optional but use it if the user mentioned what the timer is for."}
    }
  }
}"""

        const val SET_ALARM_SCHEMA = """{
  "name": "set_alarm",
  "description": "CALL THIS IMMEDIATELY when the user asks to set, schedule, or create a wall-clock alarm. Examples that MUST call this tool: 'wake me at 7am', 'alarm for 6:45 on weekdays', 'set an alarm for 10pm'. Convert AM/PM to 24h (7am -> 7, 10pm -> 22, 12am -> 0, 12pm -> 12). Do not ask for confirmation when the time is given.",
  "parameters": {
    "type": "object",
    "properties": {
      "hour": {"type": "integer", "description": "Hour in 24-hour format, 0-23."},
      "minute": {"type": "integer", "description": "Minute, 0-59."},
      "days": {"type": "array", "items": {"type": "string"}, "description": "Days the alarm repeats. Use: mon/tue/wed/thu/fri/sat/sun. For 'weekdays' use all five (mon-fri). For 'every day' use all seven. Omit for a one-shot alarm."},
      "label": {"type": "string", "description": "Short label. Optional."}
    },
    "required": ["hour", "minute"]
  }
}"""

        const val CANCEL_TIMER_SCHEMA = """{
  "name": "cancel_timer",
  "description": "CALL THIS when the user asks to cancel, stop, or remove a timer. Match by label substring (case-insensitive) when the user names it ('cancel my tea timer'), or pass all=true for 'cancel all timers' / 'stop all timers'.",
  "parameters": {
    "type": "object",
    "properties": {
      "label": {"type": "string", "description": "Label substring to match. Optional."},
      "id": {"type": "string", "description": "Exact timer id from list_timers. Optional."},
      "all": {"type": "boolean", "description": "If true, cancel every timer. Optional."}
    }
  }
}"""

        const val CANCEL_ALARM_SCHEMA = """{
  "name": "cancel_alarm",
  "description": "CALL THIS when the user asks to cancel, remove, or delete an alarm. Match by label substring, or pass all=true for 'cancel all alarms'.",
  "parameters": {
    "type": "object",
    "properties": {
      "label": {"type": "string", "description": "Label substring to match. Optional."},
      "id": {"type": "string", "description": "Exact alarm id from list_alarms. Optional."},
      "all": {"type": "boolean", "description": "If true, cancel every alarm. Optional."}
    }
  }
}"""

        const val LIST_TIMERS_SCHEMA = """{
  "name": "list_timers",
  "description": "CALL THIS when the user asks about their current timers ('what timers do I have?', 'how long left on my timer?'). Returns id, label, remaining_seconds for each.",
  "parameters": {"type": "object", "properties": {}}
}"""

        const val LIST_ALARMS_SCHEMA = """{
  "name": "list_alarms",
  "description": "CALL THIS when the user asks about their current alarms ('what alarms do I have?', 'when is my next alarm?'). Returns each alarm as {id, hour, minute, period, recurrence, label, enabled}. hour/minute/period are PRE-FORMATTED strings — concatenate exactly as 'hour:minute period' (e.g., '7:30 AM'). Do not re-format or pad.",
  "parameters": {"type": "object", "properties": {}}
}"""
    }
}

// Top-level rather than inside a companion object: the class already has a
// public companion object holding the tool-name / schema constants, and
// Kotlin allows only one companion per class. Keeping these file-private
// keeps them out of the public API while still letting recurrenceLabel use
// them.
private val WEEKDAYS: Set<AlarmDay> = setOf(
    AlarmDay.MONDAY, AlarmDay.TUESDAY, AlarmDay.WEDNESDAY,
    AlarmDay.THURSDAY, AlarmDay.FRIDAY,
)
private val WEEKENDS: Set<AlarmDay> = setOf(AlarmDay.SATURDAY, AlarmDay.SUNDAY)
private val ALL_DAYS: Set<AlarmDay> = WEEKDAYS + WEEKENDS
