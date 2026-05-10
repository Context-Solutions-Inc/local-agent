package com.contextsolutions.mobileagent.memory

/**
 * Persistent user preferences for the memory subsystem. v1 ships exactly
 * one toggle (creation enabled) per PRD §3.2.4 — Phase E surfaces it in
 * the management UI; Phase D needs the read side so the extractor can
 * skip when the user has opted out.
 *
 * Implementations are platform-specific:
 *  - Android: `SharedPreferences`-backed (boolean is non-secret; no need
 *    for the `EncryptedSharedPreferences` machinery used for the Brave
 *    key).
 *  - iOS: stub for Phase 2.
 *
 * The extractor reads through [creationEnabled] every call so a toggle
 * change takes effect on the next user turn without a process restart.
 */
interface MemoryPreferences {

    /** True if the extractor should run after each turn (PRD §3.2.4 default ON). */
    fun creationEnabled(): Boolean

    /** Persist [enabled] for future calls and Phase E UI sync. */
    fun setCreationEnabled(enabled: Boolean)

    companion object {
        /** PRD §3.2.4 default — memory creation is on out of the box. */
        const val DEFAULT_CREATION_ENABLED: Boolean = true
    }
}
