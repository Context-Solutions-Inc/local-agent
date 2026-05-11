package com.contextsolutions.mobileagent.telemetry

/**
 * UTC-day window arithmetic for the telemetry pipeline. All aggregate rows
 * are keyed by the start-of-day UTC epoch-ms of their window. Keeping the
 * math in one place avoids per-call drift if a caller forgets to floor.
 */
internal object TimeWindow {

    private const val MS_PER_DAY: Long = 24L * 60L * 60L * 1000L

    /** Returns the start-of-day UTC epoch-ms containing [epochMs]. */
    fun windowStartOf(epochMs: Long): Long = (epochMs / MS_PER_DAY) * MS_PER_DAY

    /** Returns the start-of-day UTC epoch-ms of the day BEFORE [epochMs]'s day. */
    fun previousWindowStartOf(epochMs: Long): Long = windowStartOf(epochMs) - MS_PER_DAY
}
