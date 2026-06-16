package com.contextsolutions.localagent.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Piper host→asset resolution, URL/extension shape, voice catalog, and rate→length_scale
 * mapping (PR #66). Pure logic — no download, no process.
 */
class PiperTest {

    @Test
    fun `host resolution picks the right archive per os and arch`() {
        assertEquals(PiperRelease.LINUX_X64, PiperRelease.assetForHost("linux", "amd64"))
        assertEquals(PiperRelease.LINUX_ARM64, PiperRelease.assetForHost("linux", "aarch64"))
        assertEquals(PiperRelease.MACOS_X64, PiperRelease.assetForHost("mac os x", "x86_64"))
        assertEquals(PiperRelease.MACOS_ARM64, PiperRelease.assetForHost("mac os x", "aarch64"))
        assertEquals(PiperRelease.WINDOWS_X64, PiperRelease.assetForHost("windows 11", "amd64"))
    }

    @Test
    fun `windows arm and unknown os have no prebuilt`() {
        assertNull(PiperRelease.assetForHost("windows 11", "aarch64"))
        assertNull(PiperRelease.assetForHost("sunos", "sparc"))
    }

    @Test
    fun `windows is a zip with an exe, others are tar with a unix binary`() {
        assertTrue(PiperRelease.WINDOWS_X64.isZip)
        assertEquals("piper/piper.exe", PiperRelease.WINDOWS_X64.binaryRelPath)
        assertTrue(!PiperRelease.LINUX_X64.isZip)
        assertEquals("piper/piper", PiperRelease.LINUX_X64.binaryRelPath)
    }

    @Test
    fun `download url is built from the pinned tag`() {
        val url = PiperRelease.downloadUrl(PiperRelease.LINUX_X64)
        assertEquals(
            "https://github.com/rhasspy/piper/releases/download/2023.11.14-2/piper_linux_x86_64.tar.gz",
            url,
        )
    }

    @Test
    fun `voice catalog resolves by id and falls back to default`() {
        assertEquals(PiperVoices.LESSAC_MEDIUM, PiperVoices.byId("en_US-lessac-medium"))
        assertEquals(PiperVoices.DEFAULT, PiperVoices.byId(null))
        assertEquals(PiperVoices.DEFAULT, PiperVoices.byId("does-not-exist"))
        assertEquals("en_US-lessac-medium.onnx", PiperVoices.LESSAC_MEDIUM.onnxFilename)
        assertEquals("en_US-lessac-medium.onnx.json", PiperVoices.LESSAC_MEDIUM.jsonFilename)
        assertEquals(22_050, PiperVoices.LESSAC_MEDIUM.sampleRate)
    }

    @Test
    fun `rate maps to length scale, smaller is faster`() {
        assertEquals(1.0, PiperSpeechSynthesizer.lengthScaleFor(0))
        assertEquals(0.5, PiperSpeechSynthesizer.lengthScaleFor(100))
        assertEquals(1.5, PiperSpeechSynthesizer.lengthScaleFor(-100))
        // Clamps out-of-range input.
        assertEquals(0.5, PiperSpeechSynthesizer.lengthScaleFor(500))
    }

    @Test
    fun `piper engine sentinel routes around the spd-say -o flag`() {
        // engine == "piper" must NOT be emitted as a speech-dispatcher output module.
        val cmd = DesktopTtsSpeaker(osName = "linux")
            .commandFor("hi", DesktopVoiceConfig(engine = DesktopVoiceConfig.PIPER_ENGINE))
        assertNotNull(cmd)
        assertTrue(!cmd.contains("-o"), "must not pass piper as an spd-say output module: $cmd")
    }
}
