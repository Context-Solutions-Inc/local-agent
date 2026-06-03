package com.contextsolutions.mobileagent.sync

/**
 * Persists the last-sync watermark (the highest peer `updatedAt` already merged)
 * so a reconnect only pulls/pushes what changed since (PR #57). Kept separate
 * from `DesktopLinkPreferences` on purpose: the watermark updates on every sync,
 * and the link config flow is observed to drop the resident model — churning it
 * there would reload the model on every sync tick.
 */
interface SyncWatermarkStore {
    fun get(): Long
    fun set(value: Long)
}
