package com.contextsolutions.localagent.platform

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

// Identical to the Android actual: kotlinx.datetime resolves the host clock/zone
// on the JVM the same way. Kept as a separate actual so the desktop target need
// not depend on androidMain.
actual class AgentClock {
    actual fun now(): Instant = Clock.System.now()
    actual fun nowEpochMs(): Long = now().toEpochMilliseconds()
    actual fun systemTimeZone(): TimeZone = TimeZone.currentSystemDefault()
    actual fun localNow(): LocalDateTime = now().toLocalDateTime(systemTimeZone())
}
