package com.contextsolutions.localagent.agent

import com.contextsolutions.localagent.clock.AlarmDay

/**
 * Deterministic regex-based parser for unambiguous clock commands. Runs
 * BEFORE the LLM in [AgentLoop.run] so we don't pay for Gemma's number-
 * mangling, wrong-tool selection, or token noise observed in production
 * logs ("11" -> "1:1", `<|"|>` quote tokens, set_timer chosen instead of
 * set_alarm for "11:45 AM weekdays", etc.).
 *
 * Inspired by `local_ai_agent/agent/timer_preflight.py`. The strategy:
 *
 *  1. Try to match the message against a small set of high-confidence
 *     phrasings for each command type (set/cancel/list timer and alarm).
 *  2. If anything matches, return a typed [ClockCommand] the agent loop
 *     can dispatch directly via [ClockToolHandler], skipping Gemma.
 *  3. If nothing matches (compound queries, natural-language paraphrases,
 *     anything the regex set doesn't cover), return null. The agent loop
 *     then falls through to the existing LLM path, which keeps every
 *     reliability fallback we built around Gemma's structured tool channel
 *     as a safety net.
 *
 * Coverage is intentionally conservative: a missed regex match falls
 * through to the LLM; a false-positive match would dispatch the wrong
 * tool. So patterns demand unambiguous keywords (timer/countdown,
 * alarm/wake/alert) plus structured numeric content.
 *
 * Defaults:
 *  - Hours 1-11 with no AM/PM specifier default to **AM** (per user
 *    preference — matches the common "wake me at 7" / "alarm for 11:45"
 *    intent).
 *  - Hours 13-23 are taken as 24h and converted internally.
 *  - Missing recurrence on a set-alarm command means a one-shot alarm.
 */
object ClockCommandParser {

    fun parse(message: String): ClockCommand? {
        val raw = message.trim()
        if (raw.isEmpty()) return null
        // Strip leading conversational scaffolding ("now what alarms...",
        // "actually, list my timers", "and cancel the gym alarm"). These
        // are common in continuation turns and otherwise defeat the
        // ^-anchored list/cancel patterns.
        val msg = LEADING_SCAFFOLDING_REGEX.replaceFirst(raw, "")
            .ifBlank { raw }
        // Order matters: list/cancel patterns are more specific than
        // set_* and would otherwise be caught by them ("cancel my 7am
        // alarm" mustn't fire set_alarm).
        return parseListAlarms(msg)
            ?: parseListTimers(msg)
            ?: parseCancelAlarm(msg)
            ?: parseCancelTimer(msg)
            ?: parseSetAlarm(msg)
            ?: parseSetTimer(msg)
    }

    private val LEADING_SCAFFOLDING_REGEX = Regex(
        """^\s*(?:now|actually|so|and|ok|okay|well|um|er|but|please|hey|can you|could you|would you|will you|i want to|i'd like to|let's)\s*[,\.]?\s*""",
        RegexOption.IGNORE_CASE,
    )

    // ---- list ---------------------------------------------------------------

    private val LIST_ALARMS_REGEX = Regex(
        """^\s*(?:(?:what|which|list|show|tell me(?:\s+about)?|get me|do i have|how many)\s+)?(?:my\s+|the\s+|are\s+my\s+)?(?:current\s+|active\s+|scheduled\s+|set\s+)?alarms?(?:\s+(?:do\s+i\s+have\s*(?:set|scheduled|active)?|are\s+(?:set|scheduled|active|running)))?\s*\??\s*$""",
        RegexOption.IGNORE_CASE,
    )

    private val LIST_TIMERS_REGEX = Regex(
        """^\s*(?:(?:what|which|list|show|tell me(?:\s+about)?|get me|do i have|how many)\s+)?(?:my\s+|the\s+|are\s+my\s+)?(?:current\s+|active\s+|running\s+)?timers?(?:\s+(?:do\s+i\s+have|are\s+(?:set|running|active)))?\s*\??\s*$""",
        RegexOption.IGNORE_CASE,
    )

    private fun parseListAlarms(msg: String): ClockCommand? =
        if (LIST_ALARMS_REGEX.matches(msg)) ClockCommand.ListAlarms else null

    private fun parseListTimers(msg: String): ClockCommand? =
        if (LIST_TIMERS_REGEX.matches(msg)) ClockCommand.ListTimers else null

    // ---- cancel -------------------------------------------------------------

    // "cancel all alarms" / "cancel all timers" / "stop all timers"
    private val CANCEL_ALL_ALARMS_REGEX = Regex(
        """\b(?:cancel|stop|delete|remove|clear)\s+(?:all\s+(?:of\s+my\s+|the\s+)?|every\s+)?(?:my\s+|the\s+)?alarms?\b""",
        RegexOption.IGNORE_CASE,
    )
    private val CANCEL_ALL_TIMERS_REGEX = Regex(
        """\b(?:cancel|stop|delete|remove|clear)\s+(?:all\s+(?:of\s+my\s+|the\s+)?|every\s+)?(?:my\s+|the\s+)?timers?\b""",
        RegexOption.IGNORE_CASE,
    )

    // "cancel my <label> alarm" / "cancel the gym alarm" / "stop the tea timer"
    private val CANCEL_LABELED_ALARM_REGEX = Regex(
        """\b(?:cancel|stop|delete|remove)\s+(?:my\s+|the\s+)?(.+?)\s+alarm\b""",
        RegexOption.IGNORE_CASE,
    )
    private val CANCEL_LABELED_TIMER_REGEX = Regex(
        """\b(?:cancel|stop|delete|remove)\s+(?:my\s+|the\s+)?(.+?)\s+timer\b""",
        RegexOption.IGNORE_CASE,
    )

    private fun parseCancelAlarm(msg: String): ClockCommand? {
        // The "all" form must precede the labeled form, otherwise
        // "cancel all alarms" gets parsed as label "all".
        if (CANCEL_ALL_ALARMS_REGEX.containsMatchIn(msg) &&
            !containsBareLabel(msg, alarmContext = true)
        ) {
            return ClockCommand.CancelAlarm(label = null, all = true)
        }
        CANCEL_LABELED_ALARM_REGEX.find(msg)?.let { m ->
            val label = m.groupValues[1].trim().trim('"', '\'').lowercase()
            // Discard scaffolding-only matches like "the" -> empty label.
            val cleaned = label
                .removePrefix("my ").removePrefix("the ")
                .removeSuffix(" alarm")
                .trim()
            if (cleaned.isBlank() || cleaned in CANCEL_SCAFFOLDING) {
                return ClockCommand.CancelAlarm(label = null, all = true)
            }
            return ClockCommand.CancelAlarm(label = cleaned, all = false)
        }
        return null
    }

    private fun parseCancelTimer(msg: String): ClockCommand? {
        if (CANCEL_ALL_TIMERS_REGEX.containsMatchIn(msg) &&
            !containsBareLabel(msg, alarmContext = false)
        ) {
            return ClockCommand.CancelTimer(label = null, all = true)
        }
        CANCEL_LABELED_TIMER_REGEX.find(msg)?.let { m ->
            val label = m.groupValues[1].trim().trim('"', '\'').lowercase()
            val cleaned = label
                .removePrefix("my ").removePrefix("the ")
                .removeSuffix(" timer")
                .trim()
            if (cleaned.isBlank() || cleaned in CANCEL_SCAFFOLDING) {
                return ClockCommand.CancelTimer(label = null, all = true)
            }
            return ClockCommand.CancelTimer(label = cleaned, all = false)
        }
        return null
    }

    /**
     * True when the message has a clearly-labeled cancel like "cancel my
     * tea timer" — used to avoid the cancel-all path swallowing labeled
     * cancels that happen to contain the word "alarms"/"timers".
     */
    private fun containsBareLabel(msg: String, alarmContext: Boolean): Boolean {
        val labelRegex = if (alarmContext) CANCEL_LABELED_ALARM_REGEX else CANCEL_LABELED_TIMER_REGEX
        val m = labelRegex.find(msg) ?: return false
        val label = m.groupValues[1].trim().lowercase()
        return label.isNotBlank() && label !in CANCEL_SCAFFOLDING
    }

    private val CANCEL_SCAFFOLDING = setOf(
        "all", "every", "any", "the", "my", "all my", "all of my",
    )

    // ---- set_alarm ----------------------------------------------------------

    /**
     * Time portion of an alarm command. Matches:
     *  - `7`, `7am`, `7 a.m.`, `7 AM`
     *  - `7:30`, `7:30am`, `7:30 a.m.`, `07:30`
     *  - `12:00 PM`, `12am` (midnight), `12 PM` (noon)
     *  - `14:30` (24h, > 12)
     *
     * Group 1: hour (1-23 raw). Group 2: minute (optional, 0-59).
     * Group 3: AM/PM marker (optional). Captures the marker without the
     * dots/case so the caller can decide if it's AM or PM.
     */
    private val TIME_REGEX = Regex(
        """(?<![\d:])(\d{1,2})(?::(\d{2}))?\s*([apAP]\.?\s*[mM]\.?)?""",
    )

    // Intent anchors — alarm verbs followed by a time.
    private val SET_ALARM_REGEX = Regex(
        """\b(?:set|add|create|schedule)\s+(?:a\s+|an\s+|another\s+|the\s+)?alarm\s+(?:for|at|to)\s+""",
        RegexOption.IGNORE_CASE,
    )
    private val WAKE_ME_REGEX = Regex(
        """\b(?:wake|alert)\s+me(?:\s+up)?(?:\s+at|\s+for)?\s+""",
        RegexOption.IGNORE_CASE,
    )
    private val ALARM_AT_REGEX = Regex(
        """\balarm\s+(?:for|at)\s+""",
        RegexOption.IGNORE_CASE,
    )

    private fun parseSetAlarm(msg: String): ClockCommand? {
        // Find an alarm-intent anchor; the time and recurrence are pulled
        // from the rest of the message (allows "set an alarm for 7am every
        // weekday" or "set an alarm every weekday at 7am").
        val anchorMatch = SET_ALARM_REGEX.find(msg)
            ?: WAKE_ME_REGEX.find(msg)
            ?: ALARM_AT_REGEX.find(msg)
            ?: return null

        // Look for the time anywhere after the anchor first; fall back to
        // the whole message if the anchor is at the end.
        val searchTail = msg.substring(anchorMatch.range.last + 1).ifBlank { msg }
        val time = extractTime(searchTail) ?: extractTime(msg) ?: return null

        val days = extractRecurrence(msg)
        val label = extractAlarmLabel(msg)
        return ClockCommand.SetAlarm(
            hour = time.hour24,
            minute = time.minute,
            days = days,
            label = label,
        )
    }

    /**
     * Pulls the first wall-clock time from [text]. Hours 1-11 with no
     * AM/PM specifier default to AM (per user preference). Hours 13-23
     * are taken as 24h. 12 with no specifier defaults to PM (noon, the
     * common "12 o'clock" reading); 12 AM is explicitly midnight.
     */
    private fun extractTime(text: String): TimeOfDay? {
        TIME_REGEX.findAll(text).forEach { m ->
            val rawHour = m.groupValues[1].toIntOrNull() ?: return@forEach
            if (rawHour > 23) return@forEach
            val rawMinute = m.groupValues[2].toIntOrNull() ?: 0
            if (rawMinute > 59) return@forEach
            val periodRaw = m.groupValues[3].replace(Regex("""[.\s]"""), "").lowercase()
            val period = when (periodRaw) {
                "am" -> AmPm.AM
                "pm" -> AmPm.PM
                else -> null
            }
            val hour24 = when {
                rawHour >= 13 -> rawHour // already 24h
                period == AmPm.AM -> if (rawHour == 12) 0 else rawHour
                period == AmPm.PM -> if (rawHour == 12) 12 else rawHour + 12
                rawHour == 12 -> 12 // bare "12" -> noon
                else -> rawHour // bare 1-11 -> AM per default
            }
            return TimeOfDay(hour24 = hour24, minute = rawMinute)
        }
        return null
    }

    private fun extractRecurrence(msg: String): Set<AlarmDay> {
        val lower = msg.lowercase()
        // Phrase-level patterns first — order matters: "every weekday"
        // before bare "every" (which has no match), "weekends" before any
        // single-day mention.
        if (Regex("""\b(?:every\s+day|daily|all\s+days|7\s+days\s+a\s+week)\b""").containsMatchIn(lower)) {
            return ALL_DAYS
        }
        if (Regex("""\b(?:every\s+(?:week)?day|weekdays?|work\s+days?|monday\s+(?:to|through|-)\s+friday|mon-fri)\b""").containsMatchIn(lower)) {
            return WEEKDAYS
        }
        if (Regex("""\bweekends?\b""").containsMatchIn(lower)) {
            return WEEKENDS
        }
        // Explicit comma/and list: "on mon, wed and fri", "tuesdays and thursdays"
        val days = mutableSetOf<AlarmDay>()
        for ((token, day) in DAY_TOKEN_MAP) {
            if (Regex("""\b$token\b""").containsMatchIn(lower)) {
                days += day
            }
        }
        return days
    }

    /**
     * Extracts an alarm label after "called", "named", "labeled", or
     * "labelled". Free-form text matches are too noisy — there's no
     * reliable boundary in natural language — so we only accept explicit
     * naming verbs.
     */
    private fun extractAlarmLabel(msg: String): String? {
        val m = Regex(
            """\b(?:called|named|label(?:l)?ed|titled)\s+["']?([\w\s\-]+?)["']?(?:\s*[\.\?!]|\s*$)""",
            RegexOption.IGNORE_CASE,
        ).find(msg) ?: return null
        return m.groupValues[1].trim().takeIf { it.isNotBlank() }
    }

    // ---- set_timer ----------------------------------------------------------

    private val TIMER_INTENT_REGEX = Regex(
        """\b(?:set|start|create|add)?\s*(?:a\s+|the\s+|another\s+)?(?:\d+[\s\-]*(?:hours?|h|hrs?|minutes?|min|mins?|m|seconds?|sec|secs?|s)\s*)?(?:timer|countdown)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val REMIND_ME_IN_REGEX = Regex(
        """\bremind\s+me\s+(?:in|after)\s+""",
        RegexOption.IGNORE_CASE,
    )

    private val DURATION_PART_REGEX = Regex(
        // Allow whitespace OR a hyphen between the quantity and the unit so
        // "1-minute" / "5-min" tokenise the same as "1 minute" / "5 min".
        """(\d+)[\s\-]*(hours?|h|hrs?|minutes?|min|mins?|m|seconds?|sec|secs?|s)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val UNIT_TO_SECONDS: Map<String, Long> = mapOf(
        "h" to 3600L, "hr" to 3600L, "hrs" to 3600L, "hour" to 3600L, "hours" to 3600L,
        "m" to 60L, "min" to 60L, "mins" to 60L, "minute" to 60L, "minutes" to 60L,
        "s" to 1L, "sec" to 1L, "secs" to 1L, "second" to 1L, "seconds" to 1L,
    )

    private fun parseSetTimer(msg: String): ClockCommand? {
        // Bail if this looks like an alarm (wall-clock time present) — the
        // alarm parser should have caught those. Catches "set a timer for
        // 11:45 AM" where the user used the word "timer" but meant alarm.
        if (Regex("""\b\d{1,2}:\d{2}(?:\s*[apAP]\.?\s*[mM]\.?)?\b""").containsMatchIn(msg)) {
            return null
        }
        if (!TIMER_INTENT_REGEX.containsMatchIn(msg) &&
            !REMIND_ME_IN_REGEX.containsMatchIn(msg)
        ) {
            return null
        }
        val seconds = extractDurationSeconds(msg) ?: return null
        if (seconds <= 0 || seconds > 24 * 3600) return null
        return ClockCommand.SetTimer(
            totalSeconds = seconds,
            label = extractTimerLabel(msg),
        )
    }

    private fun extractDurationSeconds(text: String): Long? {
        val matches = DURATION_PART_REGEX.findAll(text).toList()
        if (matches.isEmpty()) return null
        var total = 0L
        for (m in matches) {
            val qty = m.groupValues[1].toLongOrNull() ?: return null
            val unit = m.groupValues[2].lowercase()
            val secsPer = UNIT_TO_SECONDS[unit] ?: return null
            total += qty * secsPer
        }
        return total
    }

    /**
     * Extracts a timer label from "for <noun>" — "5 minute timer for tea"
     * -> "tea". The duration phrase is excluded so "for 5 minutes" doesn't
     * become a label. Returns null when no clear noun follows "for".
     */
    private fun extractTimerLabel(msg: String): String? {
        // Strip the duration parts first so they don't get picked up as
        // labels by the "for X" rule.
        val withoutDurations = DURATION_PART_REGEX.replace(msg, "")
        val m = Regex(
            """\b(?:timer|countdown)\s+(?:for\s+|to\s+|named\s+|called\s+)([\w\s\-]+?)(?:\s*[\.\?!,]|\s*$)""",
            RegexOption.IGNORE_CASE,
        ).find(withoutDurations) ?: return null
        val raw = m.groupValues[1].trim()
            .removePrefix("my ").removePrefix("the ").removePrefix("a ").removePrefix("an ")
            .trim()
        // Reject single-word filler that's not actually a label.
        return raw.takeIf { it.isNotBlank() && it.lowercase() !in TIMER_LABEL_SCAFFOLDING }
    }

    private val TIMER_LABEL_SCAFFOLDING = setOf("now", "me", "us")

    // ---- shared maps --------------------------------------------------------

    private val DAY_TOKEN_MAP: Map<String, AlarmDay> = mapOf(
        "mondays?" to AlarmDay.MONDAY,
        "tuesdays?" to AlarmDay.TUESDAY,
        "wednesdays?" to AlarmDay.WEDNESDAY,
        "thursdays?" to AlarmDay.THURSDAY,
        "fridays?" to AlarmDay.FRIDAY,
        "saturdays?" to AlarmDay.SATURDAY,
        "sundays?" to AlarmDay.SUNDAY,
        "mon" to AlarmDay.MONDAY,
        "tue" to AlarmDay.TUESDAY,
        "tues" to AlarmDay.TUESDAY,
        "wed" to AlarmDay.WEDNESDAY,
        "weds" to AlarmDay.WEDNESDAY,
        "thu" to AlarmDay.THURSDAY,
        "thur" to AlarmDay.THURSDAY,
        "thurs" to AlarmDay.THURSDAY,
        "fri" to AlarmDay.FRIDAY,
        "sat" to AlarmDay.SATURDAY,
        "sun" to AlarmDay.SUNDAY,
    )

    private val WEEKDAYS: Set<AlarmDay> = setOf(
        AlarmDay.MONDAY, AlarmDay.TUESDAY, AlarmDay.WEDNESDAY,
        AlarmDay.THURSDAY, AlarmDay.FRIDAY,
    )
    private val WEEKENDS: Set<AlarmDay> = setOf(AlarmDay.SATURDAY, AlarmDay.SUNDAY)
    private val ALL_DAYS: Set<AlarmDay> = WEEKDAYS + WEEKENDS

    private enum class AmPm { AM, PM }

    private data class TimeOfDay(val hour24: Int, val minute: Int)
}

/**
 * Typed result of [ClockCommandParser.parse]. Each sub-type maps 1:1 to
 * a [ClockToolHandler] tool call.
 */
sealed interface ClockCommand {
    /** Start a one-shot countdown timer. */
    data class SetTimer(val totalSeconds: Long, val label: String?) : ClockCommand

    /** Schedule a wall-clock alarm. Empty [days] -> one-shot. */
    data class SetAlarm(
        val hour: Int,
        val minute: Int,
        val days: Set<AlarmDay>,
        val label: String?,
    ) : ClockCommand

    /** Cancel timers. [all] = true cancels everything; otherwise match by [label]. */
    data class CancelTimer(val label: String?, val all: Boolean) : ClockCommand

    /** Cancel alarms. [all] = true cancels everything; otherwise match by [label]. */
    data class CancelAlarm(val label: String?, val all: Boolean) : ClockCommand

    data object ListTimers : ClockCommand
    data object ListAlarms : ClockCommand
}
