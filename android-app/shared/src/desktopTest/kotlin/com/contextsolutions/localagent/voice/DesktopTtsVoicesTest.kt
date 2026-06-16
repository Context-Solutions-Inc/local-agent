package com.contextsolutions.localagent.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Parser coverage for the desktop voice enumerator (PR #66). Pure string parsing —
 * the actual subprocess is stubbed via the [DesktopTtsVoices.runCommand] seam, so
 * this runs in CI with no system speech engine.
 */
class DesktopTtsVoicesTest {

    private fun voices(os: String, responses: Map<String, String>) =
        DesktopTtsVoices(osName = os.lowercase(), runCommand = { cmd -> responses[cmd.joinToString(" ")] })

    @Test
    fun `linux output modules skip the header`() {
        val e = voices("Linux", mapOf("spd-say -O" to "OUTPUT MODULES\nespeak-ng\nopenjtalk\n")).engines()
        assertEquals(listOf("espeak-ng", "openjtalk"), e.map { it.id })
    }

    @Test
    fun `linux voices skip the NAME header and label with language`() {
        val raw = """
                     NAME                 LANGUAGE                  VARIANT
                  English                    en-us                     none
             English+Alex                       en                     Alex
        """.trimIndent()
        val v = voices("Linux", mapOf("spd-say -L" to raw)).voices()
        assertEquals(listOf("English", "English+Alex"), v.map { it.id })
        assertTrue(v[0].label.contains("en-us"))
    }

    @Test
    fun `mac voices keep the name and strip the sample after the hash`() {
        val raw = """
            Alex                en_US    # Most people recognize me by my voice.
            Bad News            en_US    # The light you see at the end of the tunnel...
        """.trimIndent()
        val v = voices("Mac OS X", mapOf("say -v ?" to raw)).voices()
        assertEquals("Alex", v[0].id)
        assertEquals("Bad News", v[1].id)
        assertEquals("Bad News (en_US)", v[1].label)
    }

    @Test
    fun `mac and windows report no engines`() {
        assertTrue(voices("Mac OS X", emptyMap()).engines().isEmpty())
        assertTrue(voices("Windows 11", emptyMap()).engines().isEmpty())
    }

    @Test
    fun `missing engine yields empty lists, never throws`() {
        val v = voices("Linux", emptyMap())
        assertTrue(v.engines().isEmpty())
        assertTrue(v.voices().isEmpty())
    }
}
