package com.contextsolutions.localagent.language

import kotlinx.coroutines.flow.Flow

/**
 * Persistent user preference for the assistant's response language.
 * Mirrors the shape of [com.contextsolutions.localagent.memory.MemoryPreferences]
 * and [com.contextsolutions.localagent.telemetry.TelemetryConsentManager]:
 *
 *  - Plain `SharedPreferences` on Android (the preference is non-sensitive).
 *  - In-memory `MutableStateFlow` seeded from disk at construction; writes
 *    update both for next-process recovery and current-process subscribers.
 *  - Default is [PreferredLanguage.DEFAULT] (English) on fresh installs.
 *
 * The agent layer reads [preferredLanguage] through this interface every
 * turn so a Settings flip takes effect on the very next message without a
 * process restart.
 */
interface LanguagePreferences {

    /** Snapshot read. Safe from any dispatcher; serves from in-memory state. */
    fun preferredLanguage(): PreferredLanguage

    /** Reactive read. Emits current value on subscribe, then each transition. */
    fun preferredLanguageFlow(): Flow<PreferredLanguage>

    /** Persist [language] for current and future processes. Idempotent. */
    fun setPreferredLanguage(language: PreferredLanguage)
}
