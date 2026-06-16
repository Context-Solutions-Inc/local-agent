package com.contextsolutions.localagent.i18n

/**
 * Stable IDs for every localizable string. Consumers reference
 * `StringKeys.X` — never a raw string literal — which gives find-usages,
 * rename safety, and lets the guardrail test assert the English floor
 * ([EnglishStrings]) defines exactly this set.
 *
 * Scope (PR #96): the `:shared` agent-layer surfaces — deterministic
 * formatters + `AgentLoop` user-facing replies. The ~525 Compose UI literals
 * and voice-command phrase lists are a documented follow-up and are NOT keyed
 * here yet.
 *
 * Naming: `area.thing[.variant]`. Placeholders use `%1$s` / `%2$s` (text) and
 * `%1$d` (count); `%%` is a literal percent.
 */
object StringKeys {

    // ── Shared across areas ──────────────────────────────────────────────
    const val COMMON_DONE = "common.done"
    const val COMMON_ERROR_GENERIC = "common.error_generic"
    const val COMMON_UNKNOWN_ERROR = "common.unknown_error"
    const val COMMON_SOURCE = "common.source"

    // ── Agent loop ───────────────────────────────────────────────────────
    const val AGENT_ENGINE_ERROR = "agent.engine_error"

    // ── Clock formatter + guidance ───────────────────────────────────────
    const val CLOCK_GUIDANCE = "clock.guidance"
    const val CLOCK_ALARMS_NONE = "clock.alarms.none"
    const val CLOCK_ALARMS_ONE = "clock.alarms.one"
    const val CLOCK_ALARMS_HEADER = "clock.alarms.header"
    const val CLOCK_TIMERS_NONE = "clock.timers.none"
    const val CLOCK_TIMERS_ONE = "clock.timers.one"
    const val CLOCK_TIMERS_HEADER = "clock.timers.header"
    const val CLOCK_ALARM_SET = "clock.alarm_set"
    const val CLOCK_TIMER_SET = "clock.timer_set"
    const val CLOCK_TIMER_REMAINING = "clock.timer_remaining"
    const val CLOCK_CANCELLED_ALARMS = "clock.cancelled_alarms"
    const val CLOCK_CANCELLED_TIMERS = "clock.cancelled_timers"
    const val CLOCK_DURATION_ZERO = "clock.duration.zero"
    const val CLOCK_DURATION_HOURS = "clock.duration.hours"
    const val CLOCK_DURATION_MINUTES = "clock.duration.minutes"
    const val CLOCK_DURATION_SECONDS = "clock.duration.seconds"

    // ── Todo formatter + guidance ────────────────────────────────────────
    const val TODO_GUIDANCE = "todo.guidance"
    const val TODO_ADDED = "todo.added"
    const val TODO_DUE = "todo.due"
    const val TODO_PRIORITY_HIGH = "todo.priority.high"
    const val TODO_PRIORITY_MEDIUM = "todo.priority.medium"
    const val TODO_PRIORITY_LOW = "todo.priority.low"
    const val TODO_PRIORITY_TAG = "todo.priority_tag"
    const val TODO_NONE_ALL = "todo.none_all"
    const val TODO_NONE_OPEN = "todo.none_open"
    const val TODO_ONE_HEADER = "todo.one_header"
    const val TODO_HEADER = "todo.header"
    const val TODO_DONE_MARKER = "todo.done_marker"
    const val TODO_MARKED_DONE = "todo.marked_done"
    const val TODO_REOPENED = "todo.reopened"
    const val TODO_DELETED = "todo.deleted"
    const val TODO_UPDATED = "todo.updated"
    const val TODO_CLEAR_NONE = "todo.clear_none"
    const val TODO_CLEARED = "todo.cleared"
    const val TODO_DUE_TODAY = "todo.due.today"
    const val TODO_DUE_TOMORROW = "todo.due.tomorrow"
    const val TODO_DUE_YESTERDAY = "todo.due.yesterday"

    // ── Weather formatter + prompts ──────────────────────────────────────
    const val WEATHER_LOCATION_PROMPT = "weather.location_prompt"
    const val WEATHER_HEADER = "weather.header"
    const val WEATHER_YOUR_AREA = "weather.your_area"
    const val WEATHER_ALERT_WARNING = "weather.alert.warning"
    const val WEATHER_ALERT_WATCH = "weather.alert.watch"
    const val WEATHER_ALERT_ADVISORY = "weather.alert.advisory"
    const val WEATHER_ALERT_STATEMENT = "weather.alert.statement"
    const val WEATHER_ALERT_GENERIC = "weather.alert.generic"
    const val WEATHER_NOW_PREFIX = "weather.now_prefix"
    const val WEATHER_HUMIDEX_LABEL = "weather.humidex_label"
    const val WEATHER_WIND = "weather.wind"
    const val WEATHER_HUMIDITY = "weather.humidity"
    const val WEATHER_UPDATED = "weather.updated"
    const val WEATHER_DISAMBIGUATION = "weather.disambiguation"

    // ── Stock (finance) formatter ────────────────────────────────────────
    const val STOCK_FALLBACK_NAME = "stock.fallback_name"
    const val STOCK_DAY_RANGE = "stock.day_range"
    const val STOCK_WEEK52_RANGE = "stock.week52_range"
    const val STOCK_MARKET_CAP = "stock.market_cap"
    const val STOCK_PE = "stock.pe"
    const val STOCK_VOLUME = "stock.volume"
    const val STOCK_AS_OF = "stock.as_of"

    // ── Inline "run job" command ─────────────────────────────────────────
    const val JOB_RUN_PROMPT = "job.run_prompt"
    const val JOB_NOT_FOUND = "job.not_found"
    const val JOB_NO_OUTPUT = "job.no_output"
    const val JOB_FAILED = "job.failed"

    // ── Memory remember/forget acks ──────────────────────────────────────
    const val MEMORY_ACK_REMEMBER = "memory.ack_remember"
    const val MEMORY_ACK_FORGET = "memory.ack_forget"

    /** Every key above — the guardrail test asserts the English floor covers exactly this set. */
    val ALL: List<String> = listOf(
        COMMON_DONE, COMMON_ERROR_GENERIC, COMMON_UNKNOWN_ERROR, COMMON_SOURCE,
        AGENT_ENGINE_ERROR,
        CLOCK_GUIDANCE, CLOCK_ALARMS_NONE, CLOCK_ALARMS_ONE, CLOCK_ALARMS_HEADER,
        CLOCK_TIMERS_NONE, CLOCK_TIMERS_ONE, CLOCK_TIMERS_HEADER,
        CLOCK_ALARM_SET, CLOCK_TIMER_SET, CLOCK_TIMER_REMAINING,
        CLOCK_CANCELLED_ALARMS, CLOCK_CANCELLED_TIMERS,
        CLOCK_DURATION_ZERO, CLOCK_DURATION_HOURS, CLOCK_DURATION_MINUTES, CLOCK_DURATION_SECONDS,
        TODO_GUIDANCE, TODO_ADDED, TODO_DUE,
        TODO_PRIORITY_HIGH, TODO_PRIORITY_MEDIUM, TODO_PRIORITY_LOW, TODO_PRIORITY_TAG,
        TODO_NONE_ALL, TODO_NONE_OPEN, TODO_ONE_HEADER, TODO_HEADER, TODO_DONE_MARKER,
        TODO_MARKED_DONE, TODO_REOPENED, TODO_DELETED, TODO_UPDATED,
        TODO_CLEAR_NONE, TODO_CLEARED,
        TODO_DUE_TODAY, TODO_DUE_TOMORROW, TODO_DUE_YESTERDAY,
        WEATHER_LOCATION_PROMPT, WEATHER_HEADER, WEATHER_YOUR_AREA,
        WEATHER_ALERT_WARNING, WEATHER_ALERT_WATCH, WEATHER_ALERT_ADVISORY,
        WEATHER_ALERT_STATEMENT, WEATHER_ALERT_GENERIC,
        WEATHER_NOW_PREFIX, WEATHER_HUMIDEX_LABEL, WEATHER_WIND, WEATHER_HUMIDITY,
        WEATHER_UPDATED, WEATHER_DISAMBIGUATION,
        STOCK_FALLBACK_NAME, STOCK_DAY_RANGE, STOCK_WEEK52_RANGE,
        STOCK_MARKET_CAP, STOCK_PE, STOCK_VOLUME, STOCK_AS_OF,
        JOB_RUN_PROMPT, JOB_NOT_FOUND, JOB_NO_OUTPUT, JOB_FAILED,
        MEMORY_ACK_REMEMBER, MEMORY_ACK_FORGET,
    )
}
