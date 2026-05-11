package com.contextsolutions.mobileagent.inference

import com.contextsolutions.mobileagent.platform.SecureStorage
import com.contextsolutions.mobileagent.platform.SecureStorageKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultHfAuthTokenProviderTest {

    @Test
    fun `prefers user token over dev token`() {
        val provider = DefaultHfAuthTokenProvider(
            InMemorySecureStorage("user-token"),
            devToken = "dev-token",
        )
        assertEquals("user-token", provider.currentToken())
        assertTrue(provider.hasToken())
    }

    @Test
    fun `falls back to dev token when user token is absent`() {
        val provider = DefaultHfAuthTokenProvider(
            InMemorySecureStorage(null),
            devToken = "dev-token",
        )
        assertEquals("dev-token", provider.currentToken())
    }

    @Test
    fun `treats blank user token as absent`() {
        val provider = DefaultHfAuthTokenProvider(
            InMemorySecureStorage("   "),
            devToken = "dev-token",
        )
        assertEquals("dev-token", provider.currentToken())
    }

    @Test
    fun `treats blank dev token as absent`() {
        val provider = DefaultHfAuthTokenProvider(
            InMemorySecureStorage(null),
            devToken = "",
        )
        assertNull(provider.currentToken())
        assertFalse(provider.hasToken())
    }

    @Test
    fun `null when no token configured anywhere`() {
        val provider = DefaultHfAuthTokenProvider(
            InMemorySecureStorage(null),
            devToken = null,
        )
        assertNull(provider.currentToken())
        assertFalse(provider.hasToken())
    }
}

private class InMemorySecureStorage(initialToken: String?) : SecureStorage {
    private val map = mutableMapOf<String, String>()

    init {
        if (initialToken != null) map[SecureStorageKeys.HF_AUTH_TOKEN] = initialToken
    }

    override fun put(key: String, value: String) { map[key] = value }
    override fun get(key: String): String? = map[key]
    override fun remove(key: String) { map.remove(key) }
    override fun contains(key: String): Boolean = map.containsKey(key)
}
