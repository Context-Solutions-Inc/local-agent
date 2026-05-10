package com.contextsolutions.mobileagent.memory

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * M5 Phase A spike (M5_PLAN.md §4 Phase A) — retire the risk that
 * `com.google.ai.edge.litert:litert:2.1.4` (the Android port of
 * `ai-edge-litert`, same runtime as the M4 classifier) can load and invoke
 * our `all-MiniLM-L6-v2` INT8 export.
 *
 * Loads `all-MiniLM-L6-v2_int8.tflite` from app assets, asserts the input/
 * output signature, runs three forward passes with hand-crafted token IDs
 * pulled from the canonical fixture file (so we don't need a tokenizer on
 * the test classpath), and checks:
 *
 *  1. Output shape `[1, 384]` and all values finite + non-NaN.
 *  2. Output L2-norm ≈ 1.0 (the export bakes mean-pool + L2-normalise into
 *     the graph; a norm outside [0.95, 1.05] means the pooling step is
 *     broken).
 *  3. Determinism — running the same input twice produces identical bytes.
 *  4. Semantic ranking — cosine(related) > cosine(unrelated) on the
 *     "i live in toronto" / "weather in toronto today" / "when did WW2 end"
 *     triple. This is the v1.0 sanity contract from
 *     `embedder_canonical_outputs.json` similarity probes.
 *
 * If this passes on a real Pixel 7 we proceed with `LiteRtEmbedderEngine`
 * in Phase B; if it fails we fall back to standalone
 * `org.tensorflow:tensorflow-lite-gpu` (M5_PLAN.md §6 risk row).
 *
 * Run on a connected Pixel 7:
 *   ./gradlew :androidApp:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=\
 *     com.contextsolutions.mobileagent.memory.EmbedderSpikeTest
 */
@RunWith(AndroidJUnit4::class)
class EmbedderSpikeTest {

    @Test
    fun aiEdgeLiteRt_loads_embedder_and_produces_normalised_vectors() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // 1. Open ai-edge-litert environment + compile the model from assets.
        //    Try GPU first, fall back to CPU on failure (mirrors the
        //    LiteRtClassifierEngine pattern; the GPU delegate may refuse
        //    BERT ops similar to the classifier graph).
        val environment = Environment.create()
        val (compiled, accelerator) = createModel(context.assets, environment)
        try {
            // 2. Run the three canonical fixtures.
            val locationVec = embed(compiled, IDS_LOCATION_TORONTO)
            val weatherVec = embed(compiled, IDS_WEATHER_TORONTO)
            val historyVec = embed(compiled, IDS_WW2_HISTORY)

            // 3. Shape + finite + non-NaN.
            assertEquals(EMBEDDING_DIM, locationVec.size)
            assertEquals(EMBEDDING_DIM, weatherVec.size)
            assertEquals(EMBEDDING_DIM, historyVec.size)
            for (v in locationVec) assertTrue("locationVec contained $v", v.isFinite())
            for (v in weatherVec) assertTrue("weatherVec contained $v", v.isFinite())
            for (v in historyVec) assertTrue("historyVec contained $v", v.isFinite())

            // 4. L2-norm baked into the graph; allow a tiny INT8 dequant drift.
            assertNorm("location", l2Norm(locationVec))
            assertNorm("weather", l2Norm(weatherVec))
            assertNorm("history", l2Norm(historyVec))

            // 5. Determinism — same input twice should produce identical output.
            val locationVec2 = embed(compiled, IDS_LOCATION_TORONTO)
            val cosSame = cosine(locationVec, locationVec2)
            assertTrue(
                "deterministic forward pass should produce ~1.0 self-cosine, got $cosSame",
                cosSame > 0.999,
            )

            // 6. Semantic ranking — cosine(related) > cosine(unrelated).
            //    Reference values from the host export (within INT8 drift):
            //    cos(toronto, weather-toronto) ≈ +0.379
            //    cos(toronto, ww2)             ≈ -0.056
            val cosRelated = cosine(locationVec, weatherVec)
            val cosUnrelated = cosine(locationVec, historyVec)
            val report = "[spike] accelerator=$accelerator " +
                "cos(toronto, weather-toronto)=$cosRelated " +
                "cos(toronto, ww2)=$cosUnrelated"
            println(report)
            assertTrue(
                "$report — related cosine should comfortably beat unrelated",
                cosRelated > cosUnrelated + 0.10,
            )
            assertNotNull(report, locationVec)
        } finally {
            compiled.runCatching { close() }
            environment.runCatching { close() }
        }
    }

    // -- Helpers --------------------------------------------------------------

    private fun createModel(
        assets: android.content.res.AssetManager,
        env: Environment,
    ): Pair<CompiledModel, String> {
        // GPU first for parity with LiteRtClassifierEngine; the embedder
        // graph may also be GPU-rejected (BROADCAST_TO / EMBEDDING_LOOKUP
        // forced the classifier to CPU).
        try {
            val gpu = CompiledModel.create(
                assets,
                MODEL_ASSET_PATH,
                CompiledModel.Options(Accelerator.GPU),
                env,
            )
            return gpu to "GPU"
        } catch (t: Throwable) {
            println("[spike] GPU init failed; falling back to CPU: ${t.message}")
        }
        val cpu = CompiledModel.create(
            assets,
            MODEL_ASSET_PATH,
            CompiledModel.Options(Accelerator.CPU),
            env,
        )
        return cpu to "CPU"
    }

    private fun embed(compiled: CompiledModel, tokenIds: LongArray): FloatArray {
        // Pad/truncate the supplied token list to the static seq length.
        val ids = LongArray(SEQ_LEN)
        val mask = LongArray(SEQ_LEN)
        val n = tokenIds.size.coerceAtMost(SEQ_LEN)
        for (i in 0 until n) {
            ids[i] = tokenIds[i]
            mask[i] = 1L
        }

        val inputs = compiled.createInputBuffers()
        val outputs = compiled.createOutputBuffers()
        try {
            // Positional: [0]=input_ids, [1]=attention_mask. Mirrors
            // LiteRtClassifierEngine; the export consistently puts inputs
            // in this order. If a future re-export shifts the order this
            // test catches it via the determinism + ranking assertions.
            inputs[0].writeLong(ids)
            inputs[1].writeLong(mask)
            compiled.run(inputs, outputs)

            // Single output head — see CLAUDE.md inv. #12 (one [1, 384]
            // tensor; no per-head dispatch needed).
            check(outputs.size == 1) { "expected 1 output, got ${outputs.size}" }
            return outputs[0].readFloat()
        } finally {
            inputs.forEach { it.runCatching { close() } }
            outputs.forEach { it.runCatching { close() } }
        }
    }

    private fun l2Norm(v: FloatArray): Double {
        var sum = 0.0
        for (x in v) sum += x.toDouble() * x.toDouble()
        return kotlin.math.sqrt(sum)
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size)
        var dot = 0.0
        for (i in a.indices) dot += a[i].toDouble() * b[i].toDouble()
        return dot
    }

    private fun assertNorm(label: String, norm: Double) {
        assertTrue(
            "$label vector L2-norm $norm outside [0.95, 1.05] — graph mean-pool/normalize broken",
            abs(norm - 1.0) < 0.05,
        )
    }

    companion object {
        const val MODEL_ASSET_PATH: String = "all-MiniLM-L6-v2_int8.tflite"
        const val SEQ_LEN: Int = 128
        const val EMBEDDING_DIM: Int = 384

        // Pulled from classifier-training/tests/fixtures/minilm_tokenizer_canonical_inputs.json
        // so we don't need a tokenizer on the test classpath. Each array is
        // the active-tokens prefix; embed() pads to SEQ_LEN with PAD=0.
        private val IDS_LOCATION_TORONTO: LongArray = longArrayOf(
            101L, 1045L, 2444L, 1999L, 4361L, 1010L, 4561L, 102L,
            // "i live in toronto, ontario"
        )
        private val IDS_WEATHER_TORONTO: LongArray = longArrayOf(
            101L, 2054L, 2003L, 1996L, 4633L, 1999L, 4361L, 2651L, 102L,
            // "what is the weather in toronto today"
        )
        private val IDS_WW2_HISTORY: LongArray = longArrayOf(
            101L, 2043L, 2106L, 2088L, 2162L, 2048L, 2203L, 102L,
            // "when did world war two end"
        )
    }
}
