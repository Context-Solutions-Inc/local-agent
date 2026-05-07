package com.contextsolutions.mobileagent.search

import com.contextsolutions.mobileagent.platform.SecureStorage
import com.contextsolutions.mobileagent.platform.SecureStorageKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultBraveKeyProviderTest {

    @Test
    fun `prefers user key over dev key`() {
        val provider = DefaultBraveKeyProvider(InMemorySecureStorage("user-key"), devKey = "dev-key")
        assertEquals("user-key", provider.currentKey())
        assertTrue(provider.hasKey())
    }

    @Test
    fun `falls back to dev key when user key is absent`() {
        val provider = DefaultBraveKeyProvider(InMemorySecureStorage(null), devKey = "dev-key")
        assertEquals("dev-key", provider.currentKey())
    }

    @Test
    fun `treats blank user key as absent`() {
        val provider = DefaultBraveKeyProvider(InMemorySecureStorage("   "), devKey = "dev-key")
        assertEquals("dev-key", provider.currentKey())
    }

    @Test
    fun `treats blank dev key as absent`() {
        val provider = DefaultBraveKeyProvider(InMemorySecureStorage(null), devKey = "")
        assertNull(provider.currentKey())
        assertFalse(provider.hasKey())
    }

    @Test
    fun `null when no key configured anywhere`() {
        val provider = DefaultBraveKeyProvider(InMemorySecureStorage(null), devKey = null)
        assertNull(provider.currentKey())
        assertFalse(provider.hasKey())
    }
}

private class InMemorySecureStorage(initialKey: String?) : SecureStorage {
    private val map = mutableMapOf<String, String>()

    init {
        if (initialKey != null) map[SecureStorageKeys.BRAVE_API_KEY] = initialKey
    }

    override fun put(key: String, value: String) { map[key] = value }
    override fun get(key: String): String? = map[key]
    override fun remove(key: String) { map.remove(key) }
    override fun contains(key: String): Boolean = map.containsKey(key)
}
