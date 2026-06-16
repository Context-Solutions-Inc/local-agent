package com.contextsolutions.localagent.language

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android [LanguagePreferences] backed by a non-encrypted SharedPreferences
 * file. The preference is a privacy/locale choice, not a credential, so the
 * EncryptedSharedPreferences machinery used for the Brave key is unnecessary.
 *
 * The stored value is the ISO 639-1 code (`"en"`, `"ja"`, etc.); the
 * companion deserialiser falls back to [PreferredLanguage.DEFAULT] for
 * codes that don't match a known enum entry, so a future enum reshuffle
 * doesn't crash existing installs.
 */
class SharedPreferencesLanguagePreferences(context: Context) : LanguagePreferences {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val state = MutableStateFlow(
        PreferredLanguage.fromCode(prefs.getString(KEY_LANGUAGE_CODE, null)),
    )

    override fun preferredLanguage(): PreferredLanguage = state.value

    override fun preferredLanguageFlow(): Flow<PreferredLanguage> = state.asStateFlow()

    override fun setPreferredLanguage(language: PreferredLanguage) {
        if (state.value == language) return // idempotent
        state.value = language
        prefs.edit().putString(KEY_LANGUAGE_CODE, language.code).apply()
    }

    private companion object {
        private const val PREFS_NAME = "language_prefs"
        private const val KEY_LANGUAGE_CODE = "language_code"
    }
}
