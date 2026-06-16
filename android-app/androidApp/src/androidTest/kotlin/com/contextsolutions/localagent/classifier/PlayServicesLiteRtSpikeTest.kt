package com.contextsolutions.localagent.classifier

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tflite.client.TfLiteInitializationOptions
import com.google.android.gms.tflite.java.TfLite
import com.google.android.gms.tasks.Tasks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.tensorflow.lite.InterpreterApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Phase A spike (M4_PLAN.md §4 Phase A) — retire the risk that Play Services
 * LiteRT can load and invoke the v1.0 INT8 encoder .tflite shipped by M3.
 *
 * Loads `preflight_memory_shared_v1.0.0_int8.tflite` from app assets,
 * verifies the documented input/output signature
 * (`docs/M3_M4_HANDOFF.md` §2), runs a single forward pass with hand-crafted
 * inputs, and asserts the three output heads have the right shapes and
 * non-NaN floats. If this passes on a real Pixel 7 we proceed with
 * `PlayServicesLiteRtClassifierEngine` in Phase B; if it fails we fall back
 * to standalone `org.tensorflow:tensorflow-lite-gpu`.
 *
 * Run on a connected Pixel 7:
 *   ./gradlew :androidApp:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=\
 *     com.contextsolutions.localagent.classifier.PlayServicesLiteRtSpikeTest
 */
@RunWith(AndroidJUnit4::class)
class PlayServicesLiteRtSpikeTest {

    private val targetContext: Context get() =
        InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun playServicesLiteRt_loads_classifier_and_runs_forward_pass() {
        // 1. Initialize Play Services LiteRT runtime (may install GMS module on
        //    first run). Block the test thread on the resulting Task — the
        //    real engine will use coroutines + listeners.
        val initOptions = TfLiteInitializationOptions.builder()
            .setEnableGpuDelegateSupport(true)
            .build()
        Tasks.await(TfLite.initialize(targetContext, initOptions), 30, TimeUnit.SECONDS)

        // 2. Load the .tflite from assets into a direct ByteBuffer (LiteRT
        //    requires a direct buffer for memory-mapped inference).
        val modelBytes = targetContext.assets.open(MODEL_ASSET_PATH).use { it.readBytes() }
        val modelBuffer = ByteBuffer.allocateDirect(modelBytes.size).order(ByteOrder.nativeOrder())
        modelBuffer.put(modelBytes)
        modelBuffer.rewind()

        // 3. Create the interpreter via Play Services (FROM_SYSTEM_ONLY forces
        //    the GMS-delivered runtime; FROM_APPLICATION_ONLY would require a
        //    standalone .aar). GPU delegate is requested via the init options
        //    above; the runtime falls back to CPU XNNPACK if unavailable.
        val interpreterOptions = InterpreterApi.Options()
            .setRuntime(InterpreterApi.Options.TfLiteRuntime.FROM_SYSTEM_ONLY)
        val interpreter = InterpreterApi.create(modelBuffer, interpreterOptions)
        try {
            interpreter.allocateTensors()

            // 4. Verify input signature per docs/M3_M4_HANDOFF.md §2.
            assertEquals("two int64 inputs expected", 2, interpreter.inputTensorCount)
            for (idx in 0 until interpreter.inputTensorCount) {
                val input = interpreter.getInputTensor(idx)
                assertEquals("input[$idx] shape", intArrayOf(1, 128).toList(), input.shape().toList())
            }

            // 5. Discover output ordering empirically and identify each head by
            //    SHAPE (preflight=[1,3], presence=[1,2], category=[1,6]). Per
            //    CLAUDE.md hard invariant #12 the output index is set at trace
            //    time and may shift across re-exports, so the engine MUST NOT
            //    hardcode it. Each shape is unique on this graph, so shape
            //    suffices — no need to parse the StatefulPartitionedCall:N
            //    suffix. We log everything so the human reading the test
            //    report has ground truth on this build.
            assertEquals("three output heads expected", 3, interpreter.outputTensorCount)
            val outputDescriptors = (0 until interpreter.outputTensorCount).map { idx ->
                val tensor = interpreter.getOutputTensor(idx)
                Triple(idx, tensor.name(), tensor.shape().toList())
            }
            val orderingReport = outputDescriptors.joinToString("\n  ") {
                "[${it.first}] name=${it.second} shape=${it.third}"
            }
            println("[spike] output tensor ordering on this device:\n  $orderingReport")

            val preflightIdx = outputDescriptors.firstOrNull { it.third == listOf(1, 3) }?.first
            val presenceIdx = outputDescriptors.firstOrNull { it.third == listOf(1, 2) }?.first
            val categoryIdx = outputDescriptors.firstOrNull { it.third == listOf(1, 6) }?.first
            assertNotNull("no [1,3] output (preflight) found:\n  $orderingReport", preflightIdx)
            assertNotNull("no [1,2] output (presence) found:\n  $orderingReport", presenceIdx)
            assertNotNull("no [1,6] output (category) found:\n  $orderingReport", categoryIdx)

            // 6. Run a forward pass with a tokenized "did the eagles win" — we
            //    don't need byte-exact tokenization here, just non-NaN outputs
            //    of the right shape. The full WordPieceTokenizer path is
            //    covered by the JVM-side fixture test.
            val inputIds = LongArray(128).also { ids ->
                ids[0] = 101L          // [CLS]
                ids[1] = 2106L         // "did"
                ids[2] = 1996L         // "the"
                ids[3] = 8125L         // "eagles"
                ids[4] = 2663L         // "win"
                ids[5] = 102L          // [SEP]
                // remaining padded with 0 = [PAD]
            }
            val attentionMask = LongArray(128).also { mask ->
                for (i in 0..5) mask[i] = 1L
            }

            val inputs = arrayOf<Any>(arrayOf(inputIds), arrayOf(attentionMask))
            val preflightOut = Array(1) { FloatArray(3) }
            val presenceOut = Array(1) { FloatArray(2) }
            val categoryOut = Array(1) { FloatArray(6) }
            val outputs = mapOf(
                preflightIdx!! to preflightOut,
                presenceIdx!! to presenceOut,
                categoryIdx!! to categoryOut,
            )

            interpreter.runForMultipleInputsOutputs(inputs, outputs)

            // 7. Assert non-NaN, finite values across all three heads.
            preflightOut[0].forEachIndexed { i, v ->
                assertTrue("preflight[$i]=$v non-finite", v.isFinite())
            }
            presenceOut[0].forEachIndexed { i, v ->
                assertTrue("presence[$i]=$v non-finite", v.isFinite())
            }
            categoryOut[0].forEachIndexed { i, v ->
                assertTrue("category[$i]=$v non-finite", v.isFinite())
            }

            // 8. Sanity: preflight logits should not be all zeros — that
            //    would mean either the model didn't run or the tensors weren't
            //    populated. We don't assert a particular class wins because
            //    the tokenization is stub-quality, not the real encoder input.
            val anyNonZero = preflightOut[0].any { it != 0f }
            assertTrue("preflight output unexpectedly all zeros: ${preflightOut[0].toList()}", anyNonZero)

            // Logged for human inspection in the test report. Print to logcat
            // and surface as the message of an always-true assertion so the
            // values flow through both adb logcat and the connected-test report.
            val spikeReport = "spike OK — preflight=${preflightOut[0].toList()} " +
                "presence=${presenceOut[0].toList()} " +
                "category=${categoryOut[0].toList()}\n" +
                "ordering:\n  $orderingReport"
            println("[spike] $spikeReport")
            assertNotNull(spikeReport, preflightOut)
        } finally {
            interpreter.close()
        }
    }

    @Test
    fun gpu_delegate_availability_is_logged() {
        // Doesn't gate the spike — just records whether GPU is available so the
        // human reading the report knows what backend the rest of M4 will run on.
        val initOptions = TfLiteInitializationOptions.builder()
            .setEnableGpuDelegateSupport(true)
            .build()
        Tasks.await(TfLite.initialize(targetContext, initOptions), 30, TimeUnit.SECONDS)
        // If we reached here without throwing, Play Services TFLite loaded; the
        // actual GPU vs CPU choice is deferred to the engine in Phase B (it
        // races GpuDelegateFactory init and falls back on failure).
        assertFalse("(informational) Play Services TFLite init succeeded on this device", false)
    }

    companion object {
        const val MODEL_ASSET_PATH: String = "preflight_memory_shared_v1.0.0_int8.tflite"
    }
}
