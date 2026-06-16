package com.contextsolutions.localagent.clock

import kotlinx.datetime.DayOfWeek
import kotlinx.serialization.Serializable

/**
 * User-set countdown timer. Multiple may run concurrently. A timer is a
 * one-shot: when it fires, the firing notification posts and the row is
 * removed from the repository.
 *
 * [fireAtEpochMs] is the absolute wall-clock instant the timer should ring;
 * stored alongside [durationMs] so the UI can render both the original
 * duration and the live remaining time without recomputation.
 */
@Serializable
data class TimerEntry(
    val id: String,
    val label: String?,
    val durationMs: Long,
    val fireAtEpochMs: Long,
    val createdAtEpochMs: Long,
)

/**
 * User-set alarm. One-shot when [recurringDays] is empty; otherwise repeats
 * on those weekdays. [enabled] lets the user mute a recurring alarm without
 * losing its configuration.
 *
 * [hour] / [minute] are wall-clock local time; the next firing instant is
 * recomputed against the device clock + time zone whenever the alarm is
 * scheduled or re-armed (handles DST + travel).
 *
 * [skipNextDateIso] is set when the user taps "Off for today" on a recurring
 * alarm that's ringing — the next-fire computation skips that date and
 * resumes the recurrence schedule. Plain ISO date (`YYYY-MM-DD`) for
 * serialisation portability.
 */
@Serializable
data class AlarmEntry(
    val id: String,
    val label: String?,
    val hour: Int,
    val minute: Int,
    val recurringDays: Set<AlarmDay> = emptySet(),
    val enabled: Boolean = true,
    val createdAtEpochMs: Long,
    val skipNextDateIso: String? = null,
) {
    val isRecurring: Boolean get() = recurringDays.isNotEmpty()
}

/**
 * Serialisable mirror of [kotlinx.datetime.DayOfWeek]. Stored as a string in
 * JSON so a future enum reshuffle (very unlikely) doesn't break installs.
 */
@Serializable
enum class AlarmDay {
    MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY;

    fun toDayOfWeek(): DayOfWeek = when (this) {
        MONDAY -> DayOfWeek.MONDAY
        TUESDAY -> DayOfWeek.TUESDAY
        WEDNESDAY -> DayOfWeek.WEDNESDAY
        THURSDAY -> DayOfWeek.THURSDAY
        FRIDAY -> DayOfWeek.FRIDAY
        SATURDAY -> DayOfWeek.SATURDAY
        SUNDAY -> DayOfWeek.SUNDAY
    }

    companion object {
        fun fromDayOfWeek(d: DayOfWeek): AlarmDay = when (d) {
            DayOfWeek.MONDAY -> MONDAY
            DayOfWeek.TUESDAY -> TUESDAY
            DayOfWeek.WEDNESDAY -> WEDNESDAY
            DayOfWeek.THURSDAY -> THURSDAY
            DayOfWeek.FRIDAY -> FRIDAY
            DayOfWeek.SATURDAY -> SATURDAY
            DayOfWeek.SUNDAY -> SUNDAY
        }
    }
}
