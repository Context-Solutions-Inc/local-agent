package com.contextsolutions.localagent.platform

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * EncryptedSharedPreferences-backed implementation. Provided to consumers via Hilt;
 * see [SecureStorageFactory.create] for construction.
 */
class AndroidSecureStorage internal constructor(
    private val prefs: SharedPreferences,
) : SecureStorage {
    override fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun contains(key: String): Boolean = prefs.contains(key)
}

object SecureStorageFactory {
    private const val PREF_FILE = "local_agent_secure_prefs"

    fun create(context: Context): SecureStorage {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val prefs = EncryptedSharedPreferences.create(
            context,
            PREF_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        return AndroidSecureStorage(prefs)
    }
}
