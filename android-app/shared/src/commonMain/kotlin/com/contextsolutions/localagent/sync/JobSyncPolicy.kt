package com.contextsolutions.localagent.sync

/**
 * The desktop/mobile asymmetry for job sync (PR #70), injected into the otherwise
 * platform-identical [SqlDelightLinkSyncService]. This is the §2 trust boundary in
 * code: a job runs arbitrary OS commands, so a command may only ever be *defined*
 * on the locally-trusted desktop — the LAN link can never introduce or mutate one.
 *
 * - [outgoing] filters what this side is allowed to PUSH.
 * - [apply] decides how ONE incoming peer record is applied, given whether a local
 *   row already exists.
 *
 * [DesktopJobSyncPolicy] is the fail-closed default (drops remote inserts +
 * tombstones, applies only a paused toggle to existing rows). [MobileJobSyncPolicy]
 * trusts the authoritative desktop and applies everything verbatim.
 */
interface JobSyncPolicy {
    /** Records this side may push to the peer. */
    fun outgoing(all: List<JobSyncRecord>): List<JobSyncRecord>

    /** How to apply one incoming peer record. [exists] = a local row with this id is present. */
    fun apply(incoming: JobSyncRecord, exists: Boolean): JobApplyAction
}

sealed interface JobApplyAction {
    /** Ignore the record entirely (rejected by the trust boundary). */
    data object Drop : JobApplyAction

    /** Upsert the full record verbatim (mobile applying an authoritative desktop record). */
    data class UpsertFull(val record: JobSyncRecord) : JobApplyAction

    /** Apply ONLY the paused toggle to an existing row (desktop applying a mobile change). */
    data class PausedOnly(val record: JobSyncRecord) : JobApplyAction
}

/**
 * Mobile (the client / remote view). The desktop is authoritative, so every
 * desktop-originated record — including tombstones — is applied verbatim. Mobile
 * only ever toggles `paused` locally, so its outgoing set is whatever changed
 * (the desktop's [DesktopJobSyncPolicy] is the authoritative guard on apply).
 */
class MobileJobSyncPolicy : JobSyncPolicy {
    override fun outgoing(all: List<JobSyncRecord>): List<JobSyncRecord> = all

    override fun apply(incoming: JobSyncRecord, exists: Boolean): JobApplyAction =
        JobApplyAction.UpsertFull(incoming)
}

/**
 * Desktop (the authority). Fails closed: a remote insert (no existing row) or a
 * remote tombstone is dropped — a peer can neither create nor delete a job. An
 * existing job accepts only a paused toggle; command/prompt/schedule/last-run are
 * never taken from a peer.
 */
class DesktopJobSyncPolicy : JobSyncPolicy {
    override fun outgoing(all: List<JobSyncRecord>): List<JobSyncRecord> = all

    override fun apply(incoming: JobSyncRecord, exists: Boolean): JobApplyAction = when {
        !exists -> JobApplyAction.Drop                       // reject remote insert
        incoming.deletedAtEpochMs != null -> JobApplyAction.Drop  // reject remote tombstone
        else -> JobApplyAction.PausedOnly(incoming)
    }
}
