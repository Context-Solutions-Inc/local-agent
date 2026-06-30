package com.contextsolutions.localagent.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The §2 trust boundary in code (PR #70). [DesktopJobSyncPolicy] must fail closed:
 * a peer can neither create (remote insert) nor delete (remote tombstone) a job,
 * and may only toggle `paused` on an existing one. [MobileJobSyncPolicy] trusts
 * the authoritative desktop and applies everything verbatim.
 */
class JobSyncPolicyTest {

    private fun record(
        id: String = "job-1",
        paused: Boolean = false,
        deletedAt: Long? = null,
    ) = JobSyncRecord(
        id = id,
        name = "nightly",
        command = "echo",
        prompt = "hi",
        scheduleType = "CRON",
        cronExpression = "0 9 * * *",
        paused = paused,
        createdAtEpochMs = 1,
        updatedAtEpochMs = 2,
        deletedAtEpochMs = deletedAt,
    )

    // ---- Desktop (authority, fails closed) ---------------------------------

    @Test
    fun desktopDropsRemoteInsert() {
        val action = DesktopJobSyncPolicy().apply(record(), exists = false)
        assertEquals(JobApplyAction.Drop, action)
    }

    @Test
    fun desktopDropsRemoteTombstone() {
        val action = DesktopJobSyncPolicy().apply(record(deletedAt = 99), exists = true)
        assertEquals(JobApplyAction.Drop, action)
    }

    @Test
    fun desktopAcceptsPausedToggleOnExisting() {
        val action = DesktopJobSyncPolicy().apply(record(paused = true), exists = true)
        assertTrue(action is JobApplyAction.PausedOnly)
        assertTrue(action.record.paused)
    }

    // ---- Mobile (trusts the desktop) ---------------------------------------

    @Test
    fun mobileAppliesDesktopRecordVerbatim() {
        val rec = record()
        val action = MobileJobSyncPolicy().apply(rec, exists = false)
        assertTrue(action is JobApplyAction.UpsertFull)
        assertEquals(rec, action.record)
    }

    @Test
    fun mobileAppliesDesktopTombstoneVerbatim() {
        val action = MobileJobSyncPolicy().apply(record(deletedAt = 99), exists = true)
        assertTrue(action is JobApplyAction.UpsertFull)
        assertEquals(99L, action.record.deletedAtEpochMs)
    }
}
