package com.contextsolutions.localagent.classifier

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.contextsolutions.localagent.classifier.internal.softmax
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase B end-to-end on Pixel 7. Loads the real assets (vocab.txt + .tflite +
 * preflight_config.json) via the same code path the production agent will
 * use, tokenizes known queries through [WordPieceTokenizer], and asserts
 * that the classifier's preflight head sorts queries on the expected side
 * of `p_search_required = 0.5`.
 *
 * The pass criteria here are loose (`>0.5` / `<0.5`) rather than tight
 * (`>0.85` / `<0.15`) because:
 *
 *  - The model card reports test-set high-band precision at 88.6%, not 100%
 *    — so a single query could legitimately fall in the middle band.
 *  - INT8 quantization shifts the operating point relative to FP32 in ways
 *    documented in the model card §INT8 accuracy regression — we don't want
 *    test flakes from probability deltas of a few percent.
 *  - These fixtures are smoke tests, not the regression set; the real
 *    regression gate is `ct-eval-classifier --split regression` (Phase E).
 *
 * Run on a connected Pixel 7:
 *   ./gradlew :androidApp:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=\
 *     com.contextsolutions.localagent.classifier.ClassifierEndToEndTest
 */
@RunWith(AndroidJUnit4::class)
class ClassifierEndToEndTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var engine: LiteRtClassifierEngine
    private lateinit var tokenizer: WordPieceTokenizer

    @Before
    fun setUp() = runBlocking {
        engine = LiteRtClassifierEngine(context)
        val accelerator = engine.warmUp()
        assertNotNull("engine.warmUp() returned null — Play Services LiteRT unavailable?", accelerator)

        val vocab = context.assets.open("vocab.txt").bufferedReader(Charsets.UTF_8).use { reader ->
            Vocab.fromLines(reader.lineSequence())
        }
        tokenizer = WordPieceTokenizer(vocab)
    }

    @After
    fun tearDown() = runBlocking {
        engine.unload()
    }

    @Test
    fun timeSensitive_query_routes_search_required() = runBlocking {
        val pSearch = pSearchRequiredFor("did the eagles win last night")
        assertTrue(
            "expected p_search_required > 0.5 for time-sensitive query, got $pSearch",
            pSearch > 0.5f,
        )
    }

    @Test
    fun settled_history_query_routes_search_not_required() = runBlocking {
        // "When was the first Super Bowl" is a fixed historical fact — the
        // model should land it firmly in search_not_required.
        val pSearch = pSearchRequiredFor("when was the first super bowl")
        assertTrue(
            "expected p_search_required < 0.5 for settled history query, got $pSearch",
            pSearch < 0.5f,
        )
    }

    @Test
    fun definition_query_routes_search_not_required() = runBlocking {
        val pSearch = pSearchRequiredFor("what is photosynthesis")
        assertTrue(
            "expected p_search_required < 0.5 for definition query, got $pSearch",
            pSearch < 0.5f,
        )
    }

    @Test
    fun current_market_query_routes_search_required() = runBlocking {
        val pSearch = pSearchRequiredFor("what is tesla stock at right now")
        assertTrue(
            "expected p_search_required > 0.5 for current market query, got $pSearch",
            pSearch > 0.5f,
        )
    }

    @Test
    fun classifier_output_shapes_are_documented() = runBlocking {
        val output = classifyOrFail("hello world")
        assertEquals(ClassifierOutput.PREFLIGHT_CLASSES, output.preflightLogits.size)
        assertEquals(ClassifierOutput.PRESENCE_CLASSES, output.presenceLogits.size)
        assertEquals(ClassifierOutput.CATEGORY_CLASSES, output.categoryLogits.size)
        // Probabilities should be valid distributions after softmax.
        val pre = softmax(output.preflightLogits); assertProbabilitiesValid(pre)
        val pres = softmax(output.presenceLogits); assertProbabilitiesValid(pres)
    }

    private suspend fun pSearchRequiredFor(query: String): Float {
        val output = classifyOrFail(query)
        val probs = softmax(output.preflightLogits)
        // Log everything so the human reading the test report has the full
        // diagnostic when a flaky model decision causes a failure.
        println("[e2e] query=\"$query\" probs=${probs.toList()} (search_required, search_not_required, ambiguous)")
        return probs[ClassifierOutput.PREFLIGHT_INDEX_SEARCH_REQUIRED]
    }

    private suspend fun classifyOrFail(query: String): ClassifierOutput {
        val tokenized = tokenizer.encodeSingle(query)
        val output = engine.classify(tokenized.inputIds, tokenized.attentionMask)
        assertNotNull("classify() returned null for \"$query\"", output)
        return output!!
    }

    private fun assertProbabilitiesValid(probs: FloatArray) {
        val sum = probs.sum()
        assertTrue("probabilities don't sum to ~1: sum=$sum", kotlin.math.abs(sum - 1f) < 1e-3f)
        for (p in probs) {
            assertTrue("probability $p out of [0,1]", p in 0f..1f)
            assertTrue("probability is NaN", !p.isNaN())
        }
    }
}
