package com.contextsolutions.localagent.sync

import com.contextsolutions.localagent.preferences.DesktopLinkPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Mobile-side orchestrator for mobile↔desktop sync (PR #57). The desktop is the
 * server; the phone is the client, so all transport originates here:
 *
 *  - **On link-up** (config becomes enabled + paired) → a full reconcile.
 *  - **On a local change** ([LocalChangeBus], debounced) → reconcile (pushes the
 *    new local rows; the pull half is cheap + idempotent).
 *  - **On a desktop-side change** (the `/sync/subscribe` SSE) → reconcile (pulls).
 *  - **Periodic safety net** → reconcile, so a missed signal still converges.
 *
 * A single [reconcile] does both directions: pull peer changes since the
 * watermark and apply them (LWW), push local changes since the watermark, then
 * advance the watermark. It is serialized by a mutex so the triggers can't race.
 *
 * Lives on both platforms but is only meaningful on the phone (the desktop never
 * enables the link); on desktop the config stays unconfigured so it idles.
 */
class SyncController(
    private val preferences: DesktopLinkPreferences,
    private val local: LinkSyncService,
    private val http: LinkSyncClient,
    private val watermarks: SyncWatermarkStore,
    private val lastSync: LastSyncStore,
    private val lastSyncStatus: MutableLastSyncStatus,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val nowEpochMs: () -> Long = { kotlinx.datetime.Clock.System.now().toEpochMilliseconds() },
    private val logger: (String) -> Unit = {},
) {
    private val reconcileMutex = Mutex()

    fun start() {
        scope.launch {
            preferences.configFlow().collectLatest { cfg ->
                if (!cfg.isLinkConfigured) return@collectLatest
                logger("link up — starting sync")
                // (a) Initial full reconcile.
                reconcile()
                // (b) Push on local change (debounced).
                launch {
                    local.localChanges.debounce(LOCAL_DEBOUNCE_MS).collect { reconcile() }
                }
                // (c) Pull on desktop change (SSE), restarting on drop.
                launch {
                    while (isActive) {
                        runCatching { http.subscribe().collect { reconcile() } }
                        delay(SUBSCRIBE_RETRY_MS)
                    }
                }
                // (d) Periodic safety reconcile.
                launch {
                    while (isActive) {
                        delay(SAFETY_INTERVAL_MS)
                        reconcile()
                    }
                }
            }
        }
    }

    private suspend fun reconcile() = reconcileMutex.withLock {
        val cfg = preferences.config()
        if (!cfg.isLinkConfigured) return
        val wm = watermarks.get()

        // Pull peer changes since the watermark and apply LWW.
        val peer = http.fetchChanges(wm)
        if (peer == null) {
            logger("reconcile: desktop unreachable")
            return
        }
        // Peer reached — record the wall-clock sync time (PR #70) even if nothing
        // moved, so the Jobs screen can show "Synced Nm ago".
        nowEpochMs().also { lastSync.set(it); lastSyncStatus.mark(it) }
        if (!peer.isEmpty) local.applyFromPeer(peer)

        // Push local changes since the watermark.
        val localBundle = local.changesSince(wm)
        if (!localBundle.isEmpty) http.pushChanges(localBundle)

        // Advance the watermark past everything seen this round.
        val newWm = maxOf(wm, peer.maxWatermarkMs, localBundle.maxWatermarkMs)
        if (newWm > wm) watermarks.set(newWm)
        if (!peer.isEmpty || !localBundle.isEmpty) {
            logger(
                "reconciled: pulled=${peer.conversations.size}c/${peer.memories.size}m/${peer.jobs.size}j " +
                    "pushed=${localBundle.conversations.size}c/${localBundle.memories.size}m/${localBundle.jobs.size}j wm=$newWm",
            )
        }
    }

    private companion object {
        const val LOCAL_DEBOUNCE_MS = 800L
        const val SUBSCRIBE_RETRY_MS = 5_000L
        const val SAFETY_INTERVAL_MS = 30_000L
    }
}
