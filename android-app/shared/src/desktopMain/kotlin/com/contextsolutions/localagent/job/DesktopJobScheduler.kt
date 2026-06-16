package com.contextsolutions.localagent.job

import com.contextsolutions.localagent.platform.AgentClock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job as CoJob
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Coroutine `delay`-until-instant registry for desktop jobs (PR #70) — the same
 * pattern as [com.contextsolutions.localagent.clock.DesktopAlarmScheduler].
 *
 *  - **Idempotent at id granularity**: [schedule] cancels an id's prior coroutine
 *    first; [cancel] of an unknown id is a no-op.
 *  - **No own persistence**: the durable record lives in the `jobs` table.
 *    [JobService.rearmAll] re-creates the coroutines on startup.
 *  - **Drift-tolerant wait** (chunked, recomputed against [AgentClock]) so a fire
 *    survives laptop sleep / clock drift.
 *  - **`jobServiceProvider` is lazy** to break the JobService↔scheduler DI cycle.
 */
class DesktopJobScheduler(
    private val jobServiceProvider: () -> JobService,
    private val clock: AgentClock = AgentClock(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val logger: (String) -> Unit = {},
) {
    private val armed = ConcurrentHashMap<String, CoJob>()

    fun schedule(jobId: String, fireAtEpochMs: Long) {
        armed.remove(jobId)?.cancel()
        logger("arm job $jobId at $fireAtEpochMs")
        armed[jobId] = scope.launch {
            waitUntil(fireAtEpochMs)
            armed.remove(jobId)
            jobServiceProvider().onJobFired(jobId)
        }
    }

    fun cancel(jobId: String) {
        armed.remove(jobId)?.cancel()
    }

    /** Cancel every armed coroutine (used before a full re-arm). */
    fun cancelAll() {
        armed.keys.toList().forEach { cancel(it) }
    }

    private suspend fun waitUntil(fireAtEpochMs: Long) {
        while (true) {
            val remaining = fireAtEpochMs - clock.nowEpochMs()
            if (remaining <= 0L) return
            delay(remaining.coerceAtMost(WAIT_CHUNK_MS))
        }
    }

    private companion object {
        const val WAIT_CHUNK_MS = 60_000L
    }
}
