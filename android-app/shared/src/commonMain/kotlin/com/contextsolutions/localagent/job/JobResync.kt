package com.contextsolutions.localagent.job

/**
 * Mobile-only manual "reset & re-pull jobs from the desktop" action (#39 follow-up).
 *
 * PR #39 auto-wipes stale local jobs when the phone pairs a DIFFERENT desktop (stable
 * X25519 pubkey changed). That misses a *fresh re-install on the SAME desktop*: the
 * pubkey is restored unchanged, so the auto-wipe never fires while the reinstalled
 * desktop's job DB is empty — and incremental sync can't fix it (an empty desktop has
 * nothing newer than the watermark to send), so the phone shows ghost jobs forever.
 *
 * This seam is the user's escape hatch: wipe local jobs + reset the sync watermark +
 * force an immediate reconcile, so the desktop's current state re-pulls fresh.
 *
 * Bound only on mobile; desktop leaves it unbound (the desktop is authoritative).
 */
fun interface JobResync {
    /** Wipe local jobs, reset the sync watermark, and force an immediate reconcile. */
    suspend fun resyncFromDesktop()
}

/**
 * Compose the standard mobile re-sync action: hard-wipe local jobs, reset the sync
 * watermark to 0 (so the desktop's `selectJobsChangedSince(0)` returns everything), then
 * nudge an immediate reconcile. Order matters — wipe + reset MUST complete before the
 * reconcile pulls. Kept abstract over the sync collaborators ([resetWatermark]/[forceSync])
 * so this stays free of a `job → sync` package dependency; the DI binds them to
 * `SyncWatermarkStore.set(0)` + `SyncController.requestSync()`.
 *
 * Named to match the type (Kotlin's "fake constructor" idiom, cf. `MutableStateFlow()`)
 * so the call site reads `JobResync(...)`; the three-arg signature doesn't clash with the
 * single-arg SAM lambda form `JobResync { ... }`.
 */
fun JobResync(
    jobRepository: JobRepository,
    resetWatermark: () -> Unit,
    forceSync: () -> Unit,
): JobResync = JobResync {
    jobRepository.wipeLocal()
    resetWatermark()
    forceSync()
}
