package com.contextsolutions.localagent.platform

import java.security.Key
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM authenticated-encryption envelope — the at-rest primitive that replaces
 * androidx `EncryptedSharedPreferences`/`EncryptedFile` (security finding L4: that library is an
 * alpha and Google has deprecated it). Pure JCA so it works identically with the production
 * AndroidKeyStore-resident AES key ([KeystoreAesKey]) and a software `SecretKeySpec` in unit tests.
 *
 * Wire format = `iv ‖ ciphertext+tag`. The IV is **not** caller-supplied: an AndroidKeyStore GCM
 * key has `setRandomizedEncryptionRequired(true)` by default and rejects a caller IV on encrypt, so
 * we let the provider mint a fresh random IV per [seal] (SunJCE does the same for a software key)
 * and read it back from `Cipher.getIV()`. Both providers use a 12-byte GCM IV and a 128-bit tag.
 */
object AesGcmEnvelope {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128

    /** Encrypts [plaintext] under [key], returning `iv ‖ ciphertext+tag`. */
    fun seal(key: Key, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        require(iv.size == IV_LENGTH) { "unexpected GCM IV length ${iv.size}" }
        return iv + cipher.doFinal(plaintext)
    }

    /** Decrypts an `iv ‖ ciphertext+tag` blob produced by [seal]; throws on tamper / wrong key. */
    fun open(key: Key, sealed: ByteArray): ByteArray {
        require(sealed.size > IV_LENGTH) { "sealed payload too short (${sealed.size} bytes)" }
        val iv = sealed.copyOfRange(0, IV_LENGTH)
        val ciphertext = sealed.copyOfRange(IV_LENGTH, sealed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }
}
