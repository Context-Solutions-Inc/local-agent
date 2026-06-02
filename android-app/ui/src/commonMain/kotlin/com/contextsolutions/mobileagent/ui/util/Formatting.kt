package com.contextsolutions.mobileagent.ui.util

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs

/**
 * Cross-platform UI formatting helpers (docs/DESKTOP_PORT_PLAN.md Phase 9).
 *
 * Replace the Android-only `android.text.format.DateUtils` /
 * `android.text.format.Formatter` the migrated screens used, so the screens
 * compile in shared `:ui` commonMain. Pure Kotlin (kotlinx-datetime) — no
 * platform dependency, identical output on Android and desktop.
 */

private val MONTHS = arrayOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

/**
 * Abbreviated relative time, mirroring `DateUtils.getRelativeTimeSpanString`
 * with `FORMAT_ABBREV_RELATIVE` + a 1-minute minimum resolution: "now",
 * "5m ago" / "in 5m", "3h ago", "2d ago", and an absolute "MMM d" / "MMM d,
 * yyyy" date once the gap exceeds a week. Handles past and future.
 */
fun formatRelativeTime(epochMs: Long, nowMs: Long): String {
    val deltaMs = nowMs - epochMs
    val past = deltaMs >= 0
    val mins = abs(deltaMs) / 60_000
    return when {
        mins < 1 -> "now"
        mins < 60 -> relative(mins, "m", past)
        mins < 60 * 24 -> relative(mins / 60, "h", past)
        mins < 60 * 24 * 7 -> relative(mins / (60 * 24), "d", past)
        else -> absoluteDate(epochMs, nowMs)
    }
}

private fun relative(value: Long, unit: String, past: Boolean): String =
    if (past) "$value$unit ago" else "in $value$unit"

private fun absoluteDate(epochMs: Long, nowMs: Long): String {
    val tz = TimeZone.currentSystemDefault()
    val date = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(tz).date
    val nowYear = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(tz).date.year
    val month = MONTHS[date.monthNumber - 1]
    return if (date.year == nowYear) "$month ${date.dayOfMonth}"
    else "$month ${date.dayOfMonth}, ${date.year}"
}

/**
 * Extract the host of a URL for the citation chip label (replaces Android's
 * `Uri.parse(url).host`). Strips scheme + userinfo + port + path; returns null
 * if there's nothing host-like, so the caller falls back to the full URL.
 */
fun urlHost(url: String): String? {
    val afterScheme = url.substringAfter("://", url)
    val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
    val hostPort = authority.substringAfter('@')
    val host = hostPort.substringBefore(':').trim()
    return host.ifBlank { null }
}
