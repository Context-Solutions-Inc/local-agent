package com.contextsolutions.localagent.clock

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Pure function: compute the next wall-clock instant an [AlarmEntry] should
 * fire, given the current instant and time zone. Stateless so it's trivially
 * testable.
 *
 * Algorithm:
 *  - One-shot (empty [AlarmEntry.recurringDays]): today at `hour:minute` if
 *    still in the future, otherwise tomorrow at `hour:minute`.
 *  - Recurring: walk 0..7 days forward, skipping [AlarmEntry.skipNextDateIso]
 *    and any candidate not in [AlarmEntry.recurringDays]. The 0-day case
 *    matches today only if `hour:minute` hasn't already passed locally.
 *
 * DST: kotlinx-datetime's `toInstant(timeZone)` handles fall-back and spring-
 * forward correctly — the LocalDateTime constructed at hour:minute always
 * resolves to a defined instant. We don't try to "do the right thing" for
 * the duplicated/skipped hour around DST transitions beyond what the library
 * provides; the OS's own clock app has the same behaviour.
 */
object NextFireCalculator {

    fun next(alarm: AlarmEntry, now: Instant, tz: TimeZone): Long? {
        if (!alarm.enabled) return null
        val localNow = now.toLocalDateTime(tz)
        val time = LocalTime(alarm.hour, alarm.minute, 0)

        if (alarm.recurringDays.isEmpty()) {
            val today = LocalDateTime(localNow.date, time).toInstant(tz)
            val candidate = if (today > now) today
            else LocalDateTime(localNow.date.plus(1, DateTimeUnit.DAY), time).toInstant(tz)
            return candidate.toEpochMilliseconds()
        }

        val skipDate: LocalDate? = alarm.skipNextDateIso?.let(::parseIsoDateOrNull)
        for (offset in 0..7) {
            val date = localNow.date.plus(offset, DateTimeUnit.DAY)
            if (skipDate != null && date == skipDate) continue
            val day = AlarmDay.fromDayOfWeek(date.dayOfWeek)
            if (day !in alarm.recurringDays) continue
            val candidate = LocalDateTime(date, time).toInstant(tz)
            if (candidate > now) return candidate.toEpochMilliseconds()
        }
        return null
    }

    private fun parseIsoDateOrNull(iso: String): LocalDate? = try {
        LocalDate.parse(iso)
    } catch (_: Throwable) {
        null
    }
}
