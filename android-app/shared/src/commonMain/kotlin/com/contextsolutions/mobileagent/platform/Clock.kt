package com.contextsolutions.mobileagent.platform

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone

/**
 * Source of truth for "now" used everywhere in the agent loop.
 *
 * The system prompt's temporal context block (SYSTEM_PROMPT.md section 4) is constructed
 * from this; pre-flight query rewriting also uses it to resolve relative time expressions.
 *
 * Tests substitute a fixed Clock so prompt assembly is reproducible.
 */
expect class AgentClock() {
    fun now(): Instant
    fun nowEpochMs(): Long
    fun systemTimeZone(): TimeZone
    fun localNow(): LocalDateTime
}
