package com.contextsolutions.localagent.i18n

import com.contextsolutions.localagent.i18n.StringValue.Plural
import com.contextsolutions.localagent.i18n.StringValue.Simple

/**
 * The English [StringPack] — the always-present fallback floor. English is held
 * in code (not a bundled `strings_en.json`) so it can never fail to load and is
 * complete by construction: the guardrail test asserts this map's keys equal
 * [StringKeys.ALL]. New languages ship as pure JSON data files parsed by
 * [StringPack.parse] and overlaid on this floor (the "new language = data file"
 * goal); only the floor is in code.
 *
 * Values are byte-identical to the literals they replaced in the formatters and
 * `AgentLoop`, so the migration is behaviour-preserving for English.
 */
object EnglishStrings {

    val pack: StringPack by lazy {
        StringPack(code = "en", values = values, plurals = PluralRules.ENGLISH)
    }

    private val values: Map<String, StringValue> = mapOf(
        // ── Shared ──
        StringKeys.COMMON_DONE to Simple("Done."),
        StringKeys.COMMON_ERROR_GENERIC to Simple("Sorry, that didn't work: %1\$s"),
        StringKeys.COMMON_UNKNOWN_ERROR to Simple("unknown error"),
        StringKeys.COMMON_SOURCE to Simple("Source: "),

        // ── Agent loop ──
        StringKeys.AGENT_ENGINE_ERROR to
            Simple("Sorry, I had trouble processing that request. Please try again."),

        // ── Clock ──
        StringKeys.CLOCK_GUIDANCE to Simple(
            "Sorry, I didn't quite understand that clock command. Try " +
                "phrasings like \"set a 5 minute timer\", \"set an alarm for " +
                "7am every weekday\", \"cancel my tea timer\", or \"what " +
                "alarms do I have\".",
        ),
        StringKeys.CLOCK_ALARMS_NONE to Simple("You don't have any alarms set."),
        StringKeys.CLOCK_ALARMS_ONE to Simple("You have one alarm set: %1\$s."),
        StringKeys.CLOCK_ALARMS_HEADER to Simple("You have %1\$d alarms set:"),
        StringKeys.CLOCK_TIMERS_NONE to Simple("You don't have any timers running."),
        StringKeys.CLOCK_TIMERS_ONE to Simple("You have one timer running: %1\$s."),
        StringKeys.CLOCK_TIMERS_HEADER to Simple("You have %1\$d timers running:"),
        StringKeys.CLOCK_ALARM_SET to Simple("Alarm set for "),
        StringKeys.CLOCK_TIMER_SET to Simple("Timer set for "),
        StringKeys.CLOCK_TIMER_REMAINING to Simple("%1\$s remaining"),
        StringKeys.CLOCK_CANCELLED_ALARMS to Plural(
            mapOf("one" to "Cancelled %1\$d alarm.", "other" to "Cancelled %1\$d alarms."),
        ),
        StringKeys.CLOCK_CANCELLED_TIMERS to Plural(
            mapOf("one" to "Cancelled %1\$d timer.", "other" to "Cancelled %1\$d timers."),
        ),
        StringKeys.CLOCK_DURATION_ZERO to Simple("0 seconds"),
        StringKeys.CLOCK_DURATION_HOURS to Plural(
            mapOf("one" to "%1\$d hour", "other" to "%1\$d hours"),
        ),
        StringKeys.CLOCK_DURATION_MINUTES to Plural(
            mapOf("one" to "%1\$d minute", "other" to "%1\$d minutes"),
        ),
        StringKeys.CLOCK_DURATION_SECONDS to Plural(
            mapOf("one" to "%1\$d second", "other" to "%1\$d seconds"),
        ),

        // ── Todo ──
        StringKeys.TODO_GUIDANCE to Simple(
            "Sorry, I didn't quite understand that todo command. Try " +
                "phrasings like \"add buy milk to my todos\", \"add finish " +
                "report with high priority by tomorrow\", \"list my todos\", " +
                "\"complete #2\", \"delete the gym task\", or \"set #1 to " +
                "high priority\". Due dates accept today, tomorrow, or an " +
                "ISO date like 2026-05-20.",
        ),
        StringKeys.TODO_ADDED to Simple("Added \"%1\$s\""),
        StringKeys.TODO_DUE to Simple("due %1\$s"),
        StringKeys.TODO_PRIORITY_HIGH to Simple("High"),
        StringKeys.TODO_PRIORITY_MEDIUM to Simple("Medium"),
        StringKeys.TODO_PRIORITY_LOW to Simple("Low"),
        StringKeys.TODO_PRIORITY_TAG to Simple("priority %1\$s"),
        StringKeys.TODO_NONE_ALL to Simple("You don't have any todos."),
        StringKeys.TODO_NONE_OPEN to Simple("You don't have any open todos."),
        StringKeys.TODO_ONE_HEADER to Simple("You have one todo:"),
        StringKeys.TODO_HEADER to Simple("You have %1\$d todos:"),
        StringKeys.TODO_DONE_MARKER to Simple("[done]"),
        StringKeys.TODO_MARKED_DONE to Simple("Marked \"%1\$s\" as done."),
        StringKeys.TODO_REOPENED to Simple("Reopened \"%1\$s\"."),
        StringKeys.TODO_DELETED to Simple("Deleted \"%1\$s\"."),
        StringKeys.TODO_UPDATED to Simple("Updated \"%1\$s\""),
        StringKeys.TODO_CLEAR_NONE to Simple("No completed todos to clear."),
        StringKeys.TODO_CLEARED to Plural(
            mapOf(
                "one" to "Cleared %1\$d completed todo.",
                "other" to "Cleared %1\$d completed todos.",
            ),
        ),
        StringKeys.TODO_DUE_TODAY to Simple("today"),
        StringKeys.TODO_DUE_TOMORROW to Simple("tomorrow"),
        StringKeys.TODO_DUE_YESTERDAY to Simple("yesterday"),

        // ── Weather ──
        StringKeys.WEATHER_LOCATION_PROMPT to Simple(
            "Which city would you like the weather for? Tell me the city and " +
                "state or province — for example, \"weather in Miami, Florida\" " +
                "or \"weather in Toronto, Ontario\".",
        ),
        StringKeys.WEATHER_HEADER to Simple("Weather for %1\$s"),
        StringKeys.WEATHER_YOUR_AREA to Simple("your area"),
        StringKeys.WEATHER_ALERT_WARNING to Simple("%1\$s warning in effect"),
        StringKeys.WEATHER_ALERT_WATCH to Simple("%1\$s watch in effect"),
        StringKeys.WEATHER_ALERT_ADVISORY to Simple("%1\$s advisory in effect"),
        StringKeys.WEATHER_ALERT_STATEMENT to Simple("%1\$s statement issued"),
        StringKeys.WEATHER_ALERT_GENERIC to Simple("%1\$s alert in effect"),
        StringKeys.WEATHER_NOW_PREFIX to Simple("Now: "),
        StringKeys.WEATHER_HUMIDEX_LABEL to Simple("humidex"),
        StringKeys.WEATHER_WIND to Simple("Wind %1\$s"),
        StringKeys.WEATHER_HUMIDITY to Simple("Humidity %1\$s%%"),
        StringKeys.WEATHER_UPDATED to Simple("Updated"),
        StringKeys.WEATHER_DISAMBIGUATION to Simple(
            "There's more than one place called %1\$s. " +
                "Did you mean %2\$s? Tell me which — for example, \"weather in %3\$s\".",
        ),

        // ── Stock ──
        StringKeys.STOCK_FALLBACK_NAME to Simple("Stock"),
        StringKeys.STOCK_DAY_RANGE to Simple("Day %1\$s–%2\$s"),
        StringKeys.STOCK_WEEK52_RANGE to Simple("52-wk %1\$s–%2\$s"),
        StringKeys.STOCK_MARKET_CAP to Simple("Mkt cap %1\$s"),
        StringKeys.STOCK_PE to Simple("P/E %1\$s"),
        StringKeys.STOCK_VOLUME to Simple("Vol %1\$s"),
        StringKeys.STOCK_AS_OF to Simple("As of %1\$s"),

        // ── Jobs (inline run) ──
        StringKeys.JOB_RUN_PROMPT to Simple(
            "Tell me which job to run, e.g. \"run job <job name> <keywords>\".",
        ),
        StringKeys.JOB_NOT_FOUND to Simple(
            "I couldn't find a job named \"%1\$s\". Check the Jobs list for the exact name.",
        ),
        StringKeys.JOB_NO_OUTPUT to Simple("(no output)"),
        StringKeys.JOB_FAILED to Simple("The job \"%1\$s\" didn't complete successfully."),

        // ── Memory acks ──
        StringKeys.MEMORY_ACK_REMEMBER to Simple("OK, I'll remember that."),
        StringKeys.MEMORY_ACK_FORGET to Simple("OK, I'll forget that."),
    )
}
