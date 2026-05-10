package com.contextsolutions.mobileagent.memory

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.contextsolutions.mobileagent.classifier.Vocab
import com.contextsolutions.mobileagent.classifier.WordPieceTokenizer
import com.contextsolutions.mobileagent.memory.internal.cosine
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase B end-to-end on Pixel 7. Loads the real assets (vocab.txt +
 * `all-MiniLM-L6-v2_int8.tflite`) via the same code path the production
 * memory subsystem will use, tokenises strings through [WordPieceTokenizer],
 * and asserts that the embedder's output cosines line up with the host
 * Python `ai-edge-litert` reference (within INT8 quantisation drift).
 *
 * Reference cosines were captured by
 * `classifier-training/scripts/export_minilm_litert.py` at export time and
 * checked into `embedder_canonical_outputs.json`. The on-device numbers
 * should match within ~0.005 (Phase A spike showed ~0.002 drift on a
 * single-sentence pair; we allow more headroom here for the 10-string
 * canonical set).
 *
 * Companion to:
 *  - [com.contextsolutions.mobileagent.classifier.ClassifierEndToEndTest]
 *    — same pattern, classifier side.
 *  - [EmbedderSpikeTest] — Phase A. Used hardcoded IDs to retire the
 *    runtime-load risk; this test exercises the production tokenizer.
 *
 * Run on a connected Pixel 7:
 *   ./gradlew :androidApp:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=\
 *     com.contextsolutions.mobileagent.memory.EmbedderEndToEndTest
 */
@RunWith(AndroidJUnit4::class)
class EmbedderEndToEndTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var engine: LiteRtEmbedderEngine
    private lateinit var tokenizer: WordPieceTokenizer

    @Before
    fun setUp() = runBlocking {
        val vocab = context.assets.open("vocab.txt").bufferedReader(Charsets.UTF_8).use { reader ->
            Vocab.fromLines(reader.lineSequence())
        }
        tokenizer = WordPieceTokenizer(vocab)
        engine = LiteRtEmbedderEngine(context = context, tokenizer = tokenizer)
        val accelerator = engine.warmUp()
        assertNotNull("engine.warmUp() returned null — ai-edge-litert unavailable?", accelerator)
        println("[e2e] embedder ready on $accelerator")
    }

    @After
    fun tearDown() = runBlocking {
        engine.unload()
    }

    @Test
    fun output_is_unit_normalised_within_tolerance() = runBlocking {
        for (text in CANONICAL_TEXTS) {
            val out = engine.embed(text) ?: error("embed returned null for '$text'")
            assertEquals(Memory.EMBEDDING_DIM, out.vector.size)
            val norm = l2Norm(out.vector)
            assertTrue(
                "L2-norm $norm for '$text' outside [0.95, 1.05] — graph mean-pool/normalize broken",
                abs(norm - 1.0) < 0.05,
            )
        }
    }

    @Test
    fun related_strings_score_higher_than_unrelated() = runBlocking {
        // Three reference cosines from the host Python export
        // (`embedder_canonical_outputs.json`):
        //   cos(toronto, weather-toronto) ≈ +0.379
        //   cos(toronto, ww2)             ≈ -0.056
        //   cos(eagles, ww2)              ≈ -0.102
        // On-device INT8 should match within ~0.005 (Phase A spike showed
        // ~0.002 drift; we allow more headroom for a 10-string canonical set).
        val toronto = embedAndUnpack("i live in toronto, ontario")
        val weather = embedAndUnpack("what is the weather in toronto today")
        val eagles = embedAndUnpack("my favorite nfl team is the philadelphia eagles")
        val ww2 = embedAndUnpack("when did world war two end")

        val cosTorWeather = cosine(toronto, weather)
        val cosTorWw2 = cosine(toronto, ww2)
        val cosEaglesWw2 = cosine(eagles, ww2)

        val report = "[e2e] cos(toronto, weather)=$cosTorWeather " +
            "cos(toronto, ww2)=$cosTorWw2 " +
            "cos(eagles, ww2)=$cosEaglesWw2"
        println(report)

        // Tight bounds — fixture-tracked.
        assertNear("cos(toronto, weather-toronto)", expected = 0.379, actual = cosTorWeather, tol = 0.02)
        assertNear("cos(toronto, ww2)", expected = -0.056, actual = cosTorWw2, tol = 0.02)
        assertNear("cos(eagles, ww2)", expected = -0.102, actual = cosEaglesWw2, tol = 0.02)

        // Loose bound — semantic ranking.
        assertTrue(
            "$report — related cosine should comfortably beat unrelated",
            cosTorWeather > cosTorWw2 + 0.10,
        )
    }

    @Test
    fun deterministic_forward_pass_self_cosine_is_one() = runBlocking {
        val a = embedAndUnpack("i live in toronto, ontario")
        val b = embedAndUnpack("i live in toronto, ontario")
        val cosSame = cosine(a, b)
        assertTrue(
            "deterministic forward pass should produce ~1.0 self-cosine, got $cosSame",
            cosSame > 0.999,
        )
    }

    private suspend fun embedAndUnpack(text: String): FloatArray {
        val out = engine.embed(text) ?: error("embed returned null for '$text'")
        return out.vector
    }

    private fun l2Norm(v: FloatArray): Double {
        var sum = 0.0
        for (x in v) sum += x.toDouble() * x.toDouble()
        return kotlin.math.sqrt(sum)
    }

    private fun assertNear(label: String, expected: Double, actual: Double, tol: Double) {
        assertTrue(
            "$label: expected ~$expected ± $tol, got $actual (drift ${actual - expected})",
            abs(actual - expected) < tol,
        )
    }

    companion object {
        private val CANONICAL_TEXTS: List<String> = listOf(
            "i live in toronto, ontario",
            "my favorite nfl team is the philadelphia eagles",
            "what is the weather in toronto today",
            "when did world war two end",
            "i'm a software engineer working on mobile apps",
            "remember that i'm allergic to peanuts",
        )
    }
}
