package com.contextsolutions.mobileagent.language

import com.contextsolutions.mobileagent.platform.DesktopJsonStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Desktop [LanguagePreferences] (docs/DESKTOP_PORT_PLAN.md, Phase 7), the
 * counterpart of Android's `SharedPreferencesLanguagePreferences`. The stored
 * value is the ISO 639-1 code; [PreferredLanguage.fromCode] falls back to
 * [PreferredLanguage.DEFAULT] for unknown/missing codes so a future enum
 * reshuffle can't break a persisted preference. Backed by a [DesktopJsonStore]
 * file (the preference is a locale choice, not a credential).
 */
class DesktopLanguagePreferences(private val store: DesktopJsonStore) : LanguagePreferences {

    private val state = MutableStateFlow(
        PreferredLanguage.fromCode(store.getString(KEY_LANGUAGE_CODE)),
    )

    override fun preferredLanguage(): PreferredLanguage = state.value

    override fun preferredLanguageFlow(): Flow<PreferredLanguage> = state.asStateFlow()

    override fun setPreferredLanguage(language: PreferredLanguage) {
        if (state.value == language) return // idempotent
        state.value = language
        store.putString(KEY_LANGUAGE_CODE, language.code)
    }

    private companion object {
        const val KEY_LANGUAGE_CODE = "language_code"
    }
}
