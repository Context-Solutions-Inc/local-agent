package com.contextsolutions.localagent.voice

import kotlinx.coroutines.flow.Flow

/**
 * Persistent on/off state for the read-aloud (text-to-speech) speaker toggle
 * (docs/DESKTOP_PORT_PLAN.md Phase 9). Default `false` — the app stays silent
 * until the user opts in. Promoted to commonMain so the shared `ChatViewModel`
 * drives either platform; Android persists to `SharedPreferences`, desktop to
 * a JSON file.
 */
interface TtsPreferences {
    fun isEnabled(): Boolean
    fun enabledFlow(): Flow<Boolean>
    fun setEnabled(enabled: Boolean)
}
