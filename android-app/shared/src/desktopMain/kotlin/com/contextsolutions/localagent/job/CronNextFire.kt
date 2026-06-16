package com.contextsolutions.localagent.job

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Desktop-only 5-field (UNIX) cron evaluation (PR #70). The synced job model
 * carries only the `cronExpression` string; only the desktop interprets it, via
 * cron-utils. Returns the next fire instant strictly after [fromEpochMs], or
 * `null` for an unparseable expression / no future occurrence.
 */
object CronNextFire {
    private val parser = CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX))

    fun next(expr: String, fromEpochMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long? =
        runCatching {
            val cron = parser.parse(expr.trim())
            val from = ZonedDateTime.ofInstant(Instant.ofEpochMilli(fromEpochMs), zone)
            ExecutionTime.forCron(cron)
                .nextExecution(from)
                .map { it.toInstant().toEpochMilli() }
                .orElse(null)
        }.getOrNull()

    /** True when [expr] parses as a valid 5-field cron expression. */
    fun isValid(expr: String): Boolean =
        runCatching { parser.parse(expr.trim()); true }.getOrDefault(false)
}
