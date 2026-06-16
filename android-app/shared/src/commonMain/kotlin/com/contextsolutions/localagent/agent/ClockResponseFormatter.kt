package com.contextsolutions.localagent.agent

import com.contextsolutions.localagent.i18n.StringKeys
import com.contextsolutions.localagent.i18n.Strings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Renders a [ClockToolHandler] JSON result into a short human-readable
 * sentence. Used when Gemma emits a text-marker tool call instead of going
 * through the structured channel (small-model glitch — Gemma 4 E2B
 * occasionally writes `<|tool_call>call:list_alarms<tool_call|>` literally
 * in its output instead of invoking the registered tool). In that path the
 * agent loop runs the tool itself and replaces the marker text with the
 * formatter's output so the user gets a useful response either way.
 *
 * User-visible text resolves through the per-turn [Strings] (PR #96 i18n);
 * symbols/units (`•`, `:`) and tool-supplied data stay structural. Defaults to
 * [Strings.ENGLISH] so direct callers and tests keep producing English.
 */
object ClockResponseFormatter {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun format(toolName: String, resultJson: String, strings: Strings = Strings.ENGLISH): String {
        val obj = try {
            json.parseToJsonElement(resultJson).jsonObject
        } catch (_: Throwable) {
            return strings.get(StringKeys.COMMON_DONE)
        }
        if (obj["status"]?.jsonPrimitive?.content == "error") {
            val msg = obj["message"]?.jsonPrimitive?.content ?: strings.get(StringKeys.COMMON_UNKNOWN_ERROR)
            return strings.get(StringKeys.COMMON_ERROR_GENERIC, msg)
        }
        return when (toolName) {
            ClockToolHandler.LIST_ALARMS_NAME -> formatListAlarms(obj, strings)
            ClockToolHandler.LIST_TIMERS_NAME -> formatListTimers(obj, strings)
            ClockToolHandler.SET_ALARM_NAME -> formatSetAlarm(obj, strings)
            ClockToolHandler.SET_TIMER_NAME -> formatSetTimer(obj, strings)
            ClockToolHandler.CANCEL_ALARM_NAME ->
                strings.plural(StringKeys.CLOCK_CANCELLED_ALARMS, obj.cancelledCount(), obj.cancelledCount())
            ClockToolHandler.CANCEL_TIMER_NAME ->
                strings.plural(StringKeys.CLOCK_CANCELLED_TIMERS, obj.cancelledCount(), obj.cancelledCount())
            else -> strings.get(StringKeys.COMMON_DONE)
        }
    }

    private fun formatListAlarms(obj: JsonObject, strings: Strings): String {
        val alarms = obj["alarms"]?.jsonArray.orEmpty().map { it.jsonObject }
        if (alarms.isEmpty()) return strings.get(StringKeys.CLOCK_ALARMS_NONE)
        val rows = alarms.map { renderAlarmRow(it) }
        return if (rows.size == 1) {
            strings.get(StringKeys.CLOCK_ALARMS_ONE, rows.first())
        } else {
            strings.get(StringKeys.CLOCK_ALARMS_HEADER, rows.size) + "\n" +
                rows.joinToString("\n") { "• $it" }
        }
    }

    private fun formatListTimers(obj: JsonObject, strings: Strings): String {
        val timers = obj["timers"]?.jsonArray.orEmpty().map { it.jsonObject }
        if (timers.isEmpty()) return strings.get(StringKeys.CLOCK_TIMERS_NONE)
        val rows = timers.map { renderTimerRow(it, strings) }
        return if (rows.size == 1) {
            strings.get(StringKeys.CLOCK_TIMERS_ONE, rows.first())
        } else {
            strings.get(StringKeys.CLOCK_TIMERS_HEADER, rows.size) + "\n" +
                rows.joinToString("\n") { "• $it" }
        }
    }

    private fun formatSetAlarm(obj: JsonObject, strings: Strings): String {
        val time = obj.timeString() ?: "alarm"
        val recurrence = obj["recurrence"]?.jsonPrimitive?.content?.lowercase()
        val label = obj["label"]?.jsonPrimitive?.content
        return buildString {
            append(strings.get(StringKeys.CLOCK_ALARM_SET))
            append(time)
            if (recurrence != null && recurrence != "once") {
                append(", ")
                append(recurrence)
            }
            if (!label.isNullOrBlank()) {
                append(" (")
                append(label)
                append(")")
            }
            append(".")
        }
    }

    private fun formatSetTimer(obj: JsonObject, strings: Strings): String {
        val secs = obj["duration_seconds"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0
        val label = obj["label"]?.jsonPrimitive?.content
        return buildString {
            append(strings.get(StringKeys.CLOCK_TIMER_SET))
            append(formatDuration(secs, strings))
            if (!label.isNullOrBlank()) {
                append(" (")
                append(label)
                append(")")
            }
            append(".")
        }
    }

    private fun renderAlarmRow(row: JsonObject): String {
        val time = row.timeString() ?: "?"
        val recurrence = row["recurrence"]?.jsonPrimitive?.content?.lowercase()
        val label = row["label"]?.jsonPrimitive?.content
        return buildString {
            append(time)
            if (recurrence != null && recurrence != "once") {
                append(", ")
                append(recurrence)
            }
            if (!label.isNullOrBlank()) {
                append(" — ")
                append(label)
            }
        }
    }

    private fun renderTimerRow(row: JsonObject, strings: Strings): String {
        val secs = row["remaining_seconds"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0
        val label = row["label"]?.jsonPrimitive?.content
        return buildString {
            append(strings.get(StringKeys.CLOCK_TIMER_REMAINING, formatDuration(secs, strings)))
            if (!label.isNullOrBlank()) {
                append(" — ")
                append(label)
            }
        }
    }

    /**
     * Render `H:MM PERIOD` from the structured fields the tool returns.
     * Hour and minute are pre-formatted strings (minute zero-padded) — see
     * the comment in ClockToolHandler.alarmJson — so we just concatenate.
     */
    private fun JsonObject.timeString(): String? {
        val hour = this["hour"]?.jsonPrimitive?.content ?: return null
        val minute = this["minute"]?.jsonPrimitive?.content ?: return null
        val period = this["period"]?.jsonPrimitive?.content ?: return null
        return "$hour:$minute $period"
    }

    private fun JsonObject.cancelledCount(): Int =
        this["cancelled_count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0

    private fun formatDuration(totalSeconds: Long, strings: Strings): String {
        if (totalSeconds <= 0) return strings.get(StringKeys.CLOCK_DURATION_ZERO)
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        val parts = mutableListOf<String>()
        if (h > 0) parts += strings.plural(StringKeys.CLOCK_DURATION_HOURS, h.toInt(), h)
        if (m > 0) parts += strings.plural(StringKeys.CLOCK_DURATION_MINUTES, m.toInt(), m)
        if (s > 0 && h == 0L) parts += strings.plural(StringKeys.CLOCK_DURATION_SECONDS, s.toInt(), s)
        return parts.joinToString(" ")
    }
}
