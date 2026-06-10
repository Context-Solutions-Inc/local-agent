package com.contextsolutions.mobileagent.link.transport

import com.contextsolutions.mobileagent.preferences.DesktopLinkConfig
import com.contextsolutions.mobileagent.preferences.DesktopLinkPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P4 routing: relay-QR detection + config gating (relay-only since PR #80). */
class RelayRoutingTest {

    private val relayQr = """
        {"v":1,"pairing_token":"tok123","desktop_pubkey":"pk","desktop_device_id":"dev-d",
         "endpoints":{"relay":"wss://gw/v1/connect","auth":"https://gw"},"account_secret":"sek"}
    """.trimIndent()

    @Test
    fun detectsRelayQrAndIgnoresLanAndJunk() {
        val relay = RelayQrPayload.parseOrNull(relayQr)
        assertTrue(relay != null && relay.isRelayQr)
        assertEquals("sek", relay.accountSecret)
        assertEquals("dev-d", relay.desktopDeviceId)

        // The old LAN `magent://` QR is no longer a recognized pairing payload.
        assertNull(RelayQrPayload.parseOrNull("magent://link?h=1.2.3.4&p=47215&t=tok&d=dev"))
        assertNull(RelayQrPayload.parseOrNull("""{"v":1,"endpoints":{}}""")) // no token/relay
        assertNull(RelayQrPayload.parseOrNull("not json"))
    }

    @Test
    fun configGatesOnRelayQrAndToggle() {
        val relay = DesktopLinkConfig(enabled = true, relayQrJson = relayQr)
        assertTrue(relay.isPaired && relay.isLinkConfigured && relay.isRelayConfigured)

        val relayOff = relay.copy(enabled = false)
        assertTrue(!relayOff.isLinkConfigured && !relayOff.isRelayConfigured)

        val noQr = DesktopLinkConfig(enabled = true)
        assertTrue(!noQr.isPaired && !noQr.isLinkConfigured)
    }

    private class FakePrefs(private var cfg: DesktopLinkConfig) : DesktopLinkPreferences {
        private val flow = MutableStateFlow(cfg)
        override fun config() = cfg
        override fun configFlow(): Flow<DesktopLinkConfig> = flow
        override fun setConfig(config: DesktopLinkConfig) { cfg = config; flow.value = config }
    }

    @Test
    fun providerNullWhenUnconfigured() = runTest {
        val unconfigured = DefaultLinkTransportProvider(FakePrefs(DesktopLinkConfig()))
        assertNull(unconfigured.current())
    }

    @Test
    fun providerReturnsNullForRelayUntilPipeConnects() = runTest {
        // No relay factory ⇒ relay-configured but no pipe ⇒ current() is null (→ local fallback).
        val relayCfg = DesktopLinkConfig(enabled = true, relayQrJson = relayQr)
        val provider = DefaultLinkTransportProvider(FakePrefs(relayCfg))
        assertNull(provider.current())
    }
}
