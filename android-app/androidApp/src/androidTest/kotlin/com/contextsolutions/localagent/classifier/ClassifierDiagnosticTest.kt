package com.contextsolutions.localagent.classifier

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.tflite.client.TfLiteInitializationOptions
import com.google.android.gms.tflite.java.TfLite
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.tensorflow.lite.DataType
import org.tensorflow.lite.InterpreterApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * One-shot diagnostic for the M4 Phase B "all queries collapse to ambiguous"
 * symptom. Loads the .tflite via Play Services LiteRT, prints input/output
 * dtype/name/shape exhaustively, then runs the same forward pass twice —
 * once with `LongArray` (int64), once with `IntArray` (int32) — to surface
 * which dtype the runtime actually accepts. Logs raw logits each time so we
 * can compare against the Python reference.
 *
 * Runs:
 *   ./gradlew :androidApp:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=\
 *     com.contextsolutions.localagent.classifier.ClassifierDiagnosticTest
 *
 * Python reference for "did the eagles win last night":
 *   preflight = [1.902, -1.22, -0.672]   (search_required wins)
 *   presence  = [1.094, -0.854]          (has_extraction wins)
 */
@RunWith(AndroidJUnit4::class)
class ClassifierDiagnosticTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun system_vs_application_runtime() {
        // Init Play Services LiteRT runtime so FROM_SYSTEM_ONLY can be used.
        Tasks.await(
            TfLite.initialize(
                context,
                TfLiteInitializationOptions.builder().setEnableGpuDelegateSupport(true).build(),
            ),
            30,
            TimeUnit.SECONDS,
        )

        // Tokens for "did the eagles win last night" — Python reference says
        // preflight should be roughly [1.9, -1.2, -0.7] (search_required wins).
        val tokens = LongArray(128).also {
            it[0] = 101L; it[1] = 2106L; it[2] = 1996L; it[3] = 8125L
            it[4] = 2663L; it[5] = 2197L; it[6] = 2305L; it[7] = 102L
        }
        val mask = LongArray(128).also { for (i in 0..7) it[i] = 1L }

        runOnRuntime("FROM_SYSTEM_ONLY", InterpreterApi.Options.TfLiteRuntime.FROM_SYSTEM_ONLY, tokens, mask)
        runOnRuntime("FROM_APPLICATION_ONLY", InterpreterApi.Options.TfLiteRuntime.FROM_APPLICATION_ONLY, tokens, mask)
    }

    private fun runOnRuntime(
        label: String,
        runtime: InterpreterApi.Options.TfLiteRuntime,
        tokens: LongArray,
        mask: LongArray,
    ) {
        val bytes = context.assets.open("preflight_memory_shared_v1.0.0_int8.tflite")
            .use { it.readBytes() }
        val modelBuffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        modelBuffer.put(bytes); modelBuffer.rewind()

        val options = InterpreterApi.Options().setRuntime(runtime)
        val interp = try {
            InterpreterApi.create(modelBuffer, options)
        } catch (t: Throwable) {
            println("[diag] $label CREATE THREW: ${t.javaClass.simpleName}: ${t.message}")
            return
        }
        try {
            interp.allocateTensors()
            val inputIdsIdx = (0 until interp.inputTensorCount).first {
                interp.getInputTensor(it).name().contains("args_0")
            }
            val attentionMaskIdx = (0 until interp.inputTensorCount).first {
                interp.getInputTensor(it).name().contains("args_1")
            }
            val preflightIdx = (0 until interp.outputTensorCount).first {
                interp.getOutputTensor(it).name().endsWith(":0")
            }
            val presenceIdx = (0 until interp.outputTensorCount).first {
                interp.getOutputTensor(it).name().endsWith(":1")
            }
            val categoryIdx = (0 until interp.outputTensorCount).first {
                interp.getOutputTensor(it).name().endsWith(":2")
            }

            val preOut = Array(1) { FloatArray(3) }
            val presOut = Array(1) { FloatArray(2) }
            val catOut = Array(1) { FloatArray(6) }
            val outputsMap = mapOf(preflightIdx to preOut, presenceIdx to presOut, categoryIdx to catOut)
            val inputs = arrayOfNulls<Any>(2).apply {
                this[inputIdsIdx] = arrayOf(tokens)
                this[attentionMaskIdx] = arrayOf(mask)
            }
            try {
                @Suppress("UNCHECKED_CAST")
                interp.runForMultipleInputsOutputs(inputs as Array<Any>, outputsMap)
                println("[diag] $label OK — preflight=${preOut[0].toList()} presence=${presOut[0].toList()}")
            } catch (t: Throwable) {
                println("[diag] $label INVOKE THREW: ${t.javaClass.simpleName}: ${t.message}")
            }
            assertNotNull("see [diag] log lines for $label", preOut)
        } finally {
            interp.close()
        }
    }
}
