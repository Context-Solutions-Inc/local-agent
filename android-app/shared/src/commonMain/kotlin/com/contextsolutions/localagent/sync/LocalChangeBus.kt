package com.contextsolutions.localagent.sync

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-singleton signal that a *genuine local* write happened to a synced
 * store (a user-created conversation, an appended message, a saved/forgotten
 * memory) — PR #57. The repos call [notifyChanged] from their user-driven write
 * paths ONLY; the sync apply-from-peer path writes through the raw SqlDelight
 * queries instead, so a remote-applied change never re-fires this bus and there
 * is no echo loop.
 *
 * Consumers: the mobile [SyncController] (debounces, then pushes local changes to
 * the desktop) and the desktop link server's `/sync/subscribe` SSE (tells the
 * phone to pull). [tryEmit] never blocks the writer.
 */
class LocalChangeBus {
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    fun notifyChanged() {
        _changes.tryEmit(Unit)
    }
}
