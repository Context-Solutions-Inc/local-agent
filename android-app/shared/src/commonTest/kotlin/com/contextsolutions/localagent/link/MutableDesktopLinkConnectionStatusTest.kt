package com.contextsolutions.localagent.link

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** PR #90 — the relay's UP/DOWN/DISABLED collapse into a 3-state presence for the UI. */
class MutableDesktopLinkConnectionStatusTest {

    @Test
    fun defaultsToUnpaired() {
        val status = MutableDesktopLinkConnectionStatus()
        assertEquals(MobileLinkPresence.UNPAIRED, status.presence.value)
        assertFalse(status.mobileConnected.value)
        assertEquals(MobileLinkKind.NONE, status.connectionKind.value)
    }

    @Test
    fun connectedMapsToConnectedRelay() {
        val status = MutableDesktopLinkConnectionStatus()
        status.update(connected = true, everPaired = true)
        assertEquals(MobileLinkPresence.CONNECTED, status.presence.value)
        assertTrue(status.mobileConnected.value)
        assertEquals(MobileLinkKind.RELAY, status.connectionKind.value)
    }

    @Test
    fun pairedButDownMapsToOffline() {
        val status = MutableDesktopLinkConnectionStatus()
        status.update(connected = false, everPaired = true)
        assertEquals(MobileLinkPresence.OFFLINE, status.presence.value)
        assertFalse(status.mobileConnected.value)
        assertEquals(MobileLinkKind.NONE, status.connectionKind.value)
    }

    @Test
    fun neverPairedAndDownMapsToUnpaired() {
        val status = MutableDesktopLinkConnectionStatus()
        // A live UP first, then a peer-unpair (everPaired false) drops to UNPAIRED.
        status.update(connected = true, everPaired = true)
        status.update(connected = false, everPaired = false)
        assertEquals(MobileLinkPresence.UNPAIRED, status.presence.value)
        assertFalse(status.mobileConnected.value)
        assertEquals(MobileLinkKind.NONE, status.connectionKind.value)
    }
}
