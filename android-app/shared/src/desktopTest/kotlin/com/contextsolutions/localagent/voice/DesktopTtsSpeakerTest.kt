package com.contextsolutions.localagent.voice

import com.contextsolutions.localagent.platform.DesktopJsonStore
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Voice-config → command-line mapping for the desktop speaker (PR #66), plus the
 * voice-prefs round-trip. Pure string assembly — no process is spawned.
 */
class DesktopTtsSpeakerTest {

    private fun speaker(os: String, cfg: DesktopVoiceConfig) =
        DesktopTtsSpeaker(osName = os.lowercase(), voiceConfig = { cfg })

    @Test
    fun `linux passes engine, voice and rate through to spd-say`() {
        val cmd = speaker("Linux", DesktopVoiceConfig(engine = "espeak-ng", voice = "English", rate = 30))
            .commandFor("hi", DesktopVoiceConfig(engine = "espeak-ng", voice = "English", rate = 30))!!
        assertEquals(listOf("spd-say", "--wait", "-o", "espeak-ng", "-y", "English", "-r", "30", "hi"), cmd)
    }

    @Test
    fun `linux default config is just spd-say --wait`() {
        val cmd = speaker("Linux", DesktopVoiceConfig()).commandFor("hi", DesktopVoiceConfig())!!
        assertEquals(listOf("spd-say", "--wait", "hi"), cmd)
    }

    @Test
    fun `mac maps normalized rate to words-per-minute and sets the voice`() {
        val cmd = speaker("Mac OS X", DesktopVoiceConfig(voice = "Alex", rate = 25))
            .commandFor("hi", DesktopVoiceConfig(voice = "Alex", rate = 25))!!
        assertEquals(listOf("say", "-v", "Alex", "-r", "200", "hi"), cmd)
    }

    @Test
    fun `windows scales rate into the engine range`() {
        val cmd = speaker("Windows 11", DesktopVoiceConfig(rate = 100))
            .commandFor("hi", DesktopVoiceConfig(rate = 100))!!
        val script = cmd.last()
        assertTrue(script.contains("\$s.Rate = 10"), script)
    }

    @Test
    fun `voice prefs round-trip and reload`() {
        val dir: File = Files.createTempDirectory("tts-test").toFile()
        val file = File(dir, "tts_prefs.json")
        DesktopTtsPreferences(DesktopJsonStore(file)).apply {
            setVoiceConfig(DesktopVoiceConfig(engine = "espeak-ng", voice = "English", rate = -40))
        }
        val reloaded = DesktopTtsPreferences(DesktopJsonStore(file)).voiceConfig()
        assertEquals(DesktopVoiceConfig(engine = "espeak-ng", voice = "English", rate = -40), reloaded)
        dir.deleteRecursively()
    }
}
