package com.contextsolutions.localagent.agent

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
 */
object ClockResponseFormatter {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun format(toolName: String, resultJson: String): String {
        val obj = try {
            json.parseToJsonElement(resultJson).jsonObject
        } catch (_: Throwable) {
            return "Done."
        }
        if (obj["status"]?.jsonPrimitive?.content == "error") {
            val msg = obj["message"]?.jsonPrimitive?.content ?: "unknown error"
            return "Sorry, that didn't work: $msg"
        }
        return when (toolName) {
            ClockToolHandler.LIST_ALARMS_NAME -> formatListAlarms(obj)
            ClockToolHandler.LIST_TIMERS_NAME -> formatListTimers(obj)
            ClockToolHandler.SET_ALARM_NAME -> formatSetAlarm(obj)
            ClockToolHandler.SET_TIMER_NAME -> formatSetTimer(obj)
            ClockToolHandler.CANCEL_ALARM_NAME ->
                "Cancelled ${obj.cancelledCount()} alarm${plural(obj.cancelledCount())}."
            ClockToolHandler.CANCEL_TIMER_NAME ->
                "Cancelled ${obj.cancelledCount()} timer${plural(obj.cancelledCount())}."
            else -> "Done."
        }
    }

    private fun formatListAlarms(obj: JsonObject): String {
        val alarms = obj["alarms"]?.jsonArray.orEmpty().map { it.jsonObject }
        if (alarms.isEmpty()) return "You don't have any alarms set."
        val rows = alarms.map(::renderAlarmRow)
        return if (rows.size == 1) {
            "You have one alarm set: ${rows.first()}."
        } else {
            "You have ${rows.size} alarms set:\n" + rows.joinToString("\n") { "• $it" }
        }
    }

    private fun formatListTimers(obj: JsonObject): String {
        val timers = obj["timers"]?.jsonArray.orEmpty().map { it.jsonObject }
        if (timers.isEmpty()) return "You don't have any timers running."
        val rows = timers.map(::renderTimerRow)
        return if (rows.size == 1) {
            "You have one timer running: ${rows.first()}."
        } else {
            "You have ${rows.size} timers running:\n" + rows.joinToString("\n") { "• $it" }
        }
    }

    private fun formatSetAlarm(obj: JsonObject): String {
        val time = obj.timeString() ?: "alarm"
        val recurrence = obj["recurrence"]?.jsonPrimitive?.content?.lowercase()
        val label = obj["label"]?.jsonPrimitive?.content
        return buildString {
            append("Alarm set for ")
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

    private fun formatSetTimer(obj: JsonObject): String {
        val secs = obj["duration_seconds"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0
        val label = obj["label"]?.jsonPrimitive?.content
        return buildString {
            append("Timer set for ")
            append(formatDuration(secs))
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

    private fun renderTimerRow(row: JsonObject): String {
        val secs = row["remaining_seconds"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0
        val label = row["label"]?.jsonPrimitive?.content
        return buildString {
            append(formatDuration(secs))
            append(" remaining")
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

    private fun plural(n: Int): String = if (n == 1) "" else "s"

    private fun formatDuration(totalSeconds: Long): String {
        if (totalSeconds <= 0) return "0 seconds"
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        val parts = mutableListOf<String>()
        if (h > 0) parts += "$h hour${plural(h.toInt())}"
        if (m > 0) parts += "$m minute${plural(m.toInt())}"
        if (s > 0 && h == 0L) parts += "$s second${plural(s.toInt())}"
        return parts.joinToString(" ")
    }
}
