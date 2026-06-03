package com.contextsolutions.mobileagent.link

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Encode/parse round-trip + malformed-input rejection for the pairing QR (PR #57). */
class LinkPairingPayloadTest {

    @Test
    fun roundTrips() {
        val original = LinkPairingPayload(
            host = "192.168.1.20",
            port = 47215,
            token = "a1b2c3d4-e5f6",
            deviceId = "dev-abc123",
        )
        val parsed = LinkPairingPayload.parse(original.encode())
        assertEquals(original, parsed)
    }

    @Test
    fun rejectsNonPairingStrings() {
        assertNull(LinkPairingPayload.parse("https://example.com"))
        assertNull(LinkPairingPayload.parse("magent://link?h=&p=1&t=x&d=y")) // blank host
        assertNull(LinkPairingPayload.parse("magent://link?h=host&p=notaport&t=x&d=y")) // bad port
        assertNull(LinkPairingPayload.parse("random text"))
    }

    @Test
    fun toleratesEmptyDeviceId() {
        val parsed = LinkPairingPayload.parse("magent://link?h=10.0.0.5&p=47215&t=tok")
        assertEquals("10.0.0.5", parsed?.host)
        assertEquals(47215, parsed?.port)
        assertEquals("tok", parsed?.token)
        assertEquals("", parsed?.deviceId)
    }
}
