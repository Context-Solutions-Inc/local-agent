package com.contextsolutions.mobileagent.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device guard for the [AgentLoop] companion's `Regex` initialisers.
 *
 * Why this is instrumented and not a plain JVM unit test: the desktop JVM's
 * `java.util.regex.Pattern` is more lenient than Android's. A pattern with,
 * e.g., an unescaped closing `}` / `]` compiles fine on the JVM (so unit tests
 * pass) but throws `PatternSyntaxException` on Android. Because these regexes
 * are companion `val`s, that failure surfaces as `ExceptionInInitializerError`
 * the first time `AgentLoop` is touched at runtime — i.e. on the user's first
 * turn, not at test time. (Exactly the PR #30 regression in
 * `SEARCH_CONTEXT_JSON_LINE`.)
 *
 * Touching the companion here forces its `<clinit>` on ART, constructing every
 * `Regex` val, so any future Android-incompatible pattern fails THIS test
 * instead of reaching a device.
 *
 * Runs:
 *   ./gradlew :androidApp:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=\
 *     com.contextsolutions.mobileagent.agent.AgentLoopCompanionInitTest
 */
@RunWith(AndroidJUnit4::class)
class AgentLoopCompanionInitTest {

    @Test
    fun companion_regexes_compile_and_scrub_on_android() {
        // Calling the scrubber forces the companion's <clinit> to run on the
        // device, which constructs every Regex val (MINUTE_GLITCH_REGEX,
        // SEARCH_CONTEXT_MARKER_LINE, SEARCH_CONTEXT_SCAFFOLD_LINE,
        // SEARCH_CONTEXT_JSON_LINE, LOOSE_CALL_REGEX, …). If any is invalid
        // under Android's stricter Pattern, this call throws
        // ExceptionInInitializerError and the test fails here.
        val fabricated = """
            [SEARCH CONTEXT]
            query: us markets
            subtype: finance
            [{"title":"x","url":"https://e.com","snippet":"y"}]
            Markets were mixed today.
            [/SEARCH CONTEXT]
        """.trimIndent()

        // Also asserts behaviour on ART — the JSON-line + marker regexes that
        // broke must actually strip on-device, keeping only the prose.
        assertEquals(
            "Markets were mixed today.",
            AgentLoop.stripSearchContextScaffolding(fabricated),
        )
    }
}
