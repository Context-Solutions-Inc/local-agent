package com.contextsolutions.localagent.platform

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import javax.crypto.SecretKey

/**
 * Keystore-direct AES-256-GCM implementation of [SecureStorage] (security finding L4 — replaces the
 * deprecated alpha `androidx.security:security-crypto` `EncryptedSharedPreferences`).
 *
 * Values are sealed with [AesGcmEnvelope] under a hardware-backed [KeystoreAesKey] and stored as
 * Base64 in a plain [SharedPreferences] file. Key **names** are stored in the clear — they aren't
 * sensitive (the threat model is at-rest theft of the values, not disclosure of which keys exist),
 * the one deliberate difference from androidx's AES256-SIV key encryption. Per PRD §4.4 the values
 * (Brave/HF/Ollama keys, the M1 SQLCipher DB key, relay credentials) are never logged and never
 * written to plaintext disk.
 */
class AndroidSecureStorage internal constructor(
    private val prefs: SharedPreferences,
    private val key: SecretKey,
) : SecureStorage {
    override fun put(key: String, value: String) {
        val sealed = AesGcmEnvelope.seal(this.key, value.encodeToByteArray())
        prefs.edit().putString(key, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    override fun get(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        // A decrypt failure (corrupt blob, key rotation, tamper) reads as absent rather than crashing
        // the caller — the M1 DB-key guard then fails loudly on its own if a key truly vanished.
        return runCatching {
            AesGcmEnvelope.open(this.key, Base64.decode(stored, Base64.NO_WRAP)).decodeToString()
        }.getOrNull()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun contains(key: String): Boolean = prefs.contains(key)
}

object SecureStorageFactory {
    /** New store file — distinct from the legacy androidx `local_agent_secure_prefs` (cleared by
     *  [CleanBreakReset] on a pre-L4 upgrade). */
    private const val PREF_FILE = "local_agent_secure_store"

    fun create(context: Context): SecureStorage {
        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        return AndroidSecureStorage(prefs, KeystoreAesKey.loadOrCreate())
    }
}
