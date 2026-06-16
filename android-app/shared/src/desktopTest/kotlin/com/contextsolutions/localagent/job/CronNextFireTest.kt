package com.contextsolutions.localagent.job

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards the cron format the Jobs UI generates (`buildJobCron` → `minute hour * *
 * dows`, dows = `*` or a 0=Sun..6=Sat list) against the cron-utils UNIX parser the
 * desktop scheduler uses (PR #70). If these drift, jobs would silently never fire.
 */
class CronNextFireTest {

    private val utc = ZoneId.of("UTC")
    // 2026-06-05T00:00:00Z, a fixed "now" so the test is deterministic.
    private val from = 1_780_963_200_000L

    @Test
    fun everyDayCronParsesAndHasAFutureFire() {
        assertTrue(CronNextFire.isValid("0 9 * * *"))
        assertNotNull(CronNextFire.next("0 9 * * *", from, utc))
    }

    @Test
    fun dayListCronParsesAndHasAFutureFire() {
        // Mon/Wed/Fri at 09:00 — the shape the day-chip UI emits.
        assertTrue(CronNextFire.isValid("0 9 * * 1,3,5"))
        val next = CronNextFire.next("0 9 * * 1,3,5", from, utc)
        assertNotNull(next)
        assertTrue(next > from)
    }

    @Test
    fun sundayIsZero() {
        assertTrue(CronNextFire.isValid("30 8 * * 0"))
        assertNotNull(CronNextFire.next("30 8 * * 0", from, utc))
    }

    @Test
    fun garbageCronIsRejected() {
        assertTrue(!CronNextFire.isValid("not a cron"))
    }
}
