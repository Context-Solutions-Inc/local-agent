package com.contextsolutions.localagent.voice

import org.vosk.LibVosk
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards the [org.vosk.LibVosk] shadow (shared/desktopMain/java) that works around
 * the vosk 0.3.45 macOS packaging bug: the bundled darwin dylib lacks the
 * `vosk_recognizer_set_grm` symbol, which JNA's eager `Native.register` would
 * crash on at class-load — killing all dictation on macOS.
 *
 * Touching any [LibVosk] member triggers its static initializer, i.e. the full
 * `Native.register(LibVosk.class, ...)` pass against the bundled native for the
 * current OS. With the upstream class this throws on macOS
 * (`UnsatisfiedLinkError: Error looking up function 'vosk_recognizer_set_grm'`);
 * with the shadow (which omits that one unused binding) it succeeds on every
 * desktop OS. Running this test on the macos CI runner is the regression gate.
 */
class LibVoskShadowTest {

    @Test
    fun voskBindingRegistersWithoutTheMissingGrammarSymbol() {
        // Forces class init -> Native.register over the whole declared API, then a
        // real native call. No acoustic model required. Throws if any declared
        // symbol is missing in the bundled native (the original failure mode).
        LibVosk.vosk_set_log_level(-1)

        // Confirm we actually loaded OUR shadow (the one without set_grm), not the
        // jar's class — otherwise the test would pass for the wrong reason.
        val declaresSetGrm = LibVosk::class.java.declaredMethods.any { it.name == "vosk_recognizer_set_grm" }
        assertTrue(!declaresSetGrm, "Expected the shadow LibVosk (no vosk_recognizer_set_grm); got the upstream class")
    }
}
