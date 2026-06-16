package com.contextsolutions.localagent.platform

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

actual class AgentClock {
    actual fun now(): Instant = Clock.System.now()
    actual fun nowEpochMs(): Long = now().toEpochMilliseconds()
    actual fun systemTimeZone(): TimeZone = TimeZone.currentSystemDefault()
    actual fun localNow(): LocalDateTime = now().toLocalDateTime(systemTimeZone())
}
