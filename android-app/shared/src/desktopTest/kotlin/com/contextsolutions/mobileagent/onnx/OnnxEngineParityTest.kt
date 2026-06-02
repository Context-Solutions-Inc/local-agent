package com.contextsolutions.mobileagent.onnx

import com.contextsolutions.mobileagent.classifier.DesktopVocabLoader
import com.contextsolutions.mobileagent.classifier.OnnxClassifierEngine
import com.contextsolutions.mobileagent.classifier.WordPieceTokenizer
import com.contextsolutions.mobileagent.inference.DesktopAuxModels
import com.contextsolutions.mobileagent.memory.OnnxEmbedderEngine
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Desktop numeric-parity gate for the Phase-5 ONNX engines (docs/DESKTOP_PORT_PLAN.md).
 *
 * Drives the real [OnnxClassifierEngine] / [OnnxEmbedderEngine] over the same
 * canonical strings the Python export captured (`classifier_onnx_canonical_outputs.json`
 * / `embedder_onnx_canonical_outputs.json`, emitted by `ct-export-onnx` /
 * `export_minilm_onnx.py`) and asserts the Kotlin-ORT outputs match the
 * Python-ORT reference within tolerance. This validates the runtime wiring the
 * engine adds on top of the model: the by-name head dispatch (invariant #12),
 * the `[1, 128]` int64 tensor layout (#15), and that the shared
 * [WordPieceTokenizer] + bundled `vocab.txt` reproduce the Python tokenisation
 * (#13). (Re-export *fidelity* vs the shipped `.tflite` is the separate
 * `--compare-tflite` / `--compare-litert` Python-side check.)
 *
 * **CI-skip contract.** The `.onnx` artifacts are too large to bundle and aren't
 * produced in CI (no trained checkpoint / GPU), so each test no-ops with a
 * printed SKIP when the model file is absent — same manual-verification pattern
 * as the GGUF-dependent paths. The operator runs this against exported artifacts
 * by placing them in the app-data `models/` dir or pointing
 * `MOBILEAGENT_CLASSIFIER_ONNX` / `MOBILEAGENT_EMBEDDER_ONNX` at them.
 *
 * Same-runtime, same-graph parity is near-exact; [TOLERANCE] leaves headroom for
 * float reduction-order differences across ORT builds. Logits are O(1–10) and
 * embedding components O(0.1), so an absolute 1e-3 bound is tight.
 */
class OnnxEngineParityTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun classifier_matches_python_onnx_reference() = runTest {
        val model = DesktopAuxModels.classifierModel()
        if (!model.isFile) {
            println("SKIP classifier parity — no .onnx at $model (set ${DesktopAuxModels.CLASSIFIER_ENV})")
            return@runTest
        }
        val payload = loadFixture("classifier_onnx_canonical_outputs.json") {
            json.decodeFromString<ClassifierFixturePayload>(it)
        } ?: return@runTest

        val tokenizer = WordPieceTokenizer(checkNotNull(DesktopVocabLoader.loadOrNull()) {
            "bundled vocab.txt missing from desktop classpath"
        })
        val engine = OnnxClassifierEngine(model, logger = { println("[classifier] $it") })
        assertNotNull(engine.warmUp(), "classifier warmUp returned null for $model")
        try {
            for (fx in payload.fixtures) {
                val enc = tokenizer.encodeSingle(fx.text)
                val out = assertNotNull(engine.classify(enc.inputIds, enc.attentionMask), "${fx.id}: null output")
                assertClose(fx.preflightLogits, out.preflightLogits, "${fx.id} preflight")
                assertClose(fx.presenceLogits, out.presenceLogits, "${fx.id} presence")
                assertClose(fx.categoryLogits, out.categoryLogits, "${fx.id} category")
            }
            println("classifier parity OK — ${payload.fixtures.size} fixtures within $TOLERANCE")
        } finally {
            engine.unload()
        }
    }

    @Test
    fun embedder_matches_python_onnx_reference() = runTest {
        val model = DesktopAuxModels.embedderModel()
        if (!model.isFile) {
            println("SKIP embedder parity — no .onnx at $model (set ${DesktopAuxModels.EMBEDDER_ENV})")
            return@runTest
        }
        val payload = loadFixture("embedder_onnx_canonical_outputs.json") {
            json.decodeFromString<EmbedderFixturePayload>(it)
        } ?: return@runTest

        val tokenizer = WordPieceTokenizer(checkNotNull(DesktopVocabLoader.loadOrNull()) {
            "bundled vocab.txt missing from desktop classpath"
        })
        val engine = OnnxEmbedderEngine(tokenizer, model, logger = { println("[embedder] $it") })
        assertNotNull(engine.warmUp(), "embedder warmUp returned null for $model")
        try {
            for (fx in payload.fixtures) {
                val out = assertNotNull(engine.embed(fx.text), "${fx.id}: null output")
                assertClose(fx.vector, out.vector, fx.id)
            }
            println("embedder parity OK — ${payload.fixtures.size} fixtures within $TOLERANCE")
        } finally {
            engine.unload()
        }
    }

    // -- helpers ------------------------------------------------------------

    /** Decode a staged fixture from the test classpath, or null+SKIP if absent. */
    private fun <T> loadFixture(name: String, decode: (String) -> T): T? {
        val stream = javaClass.classLoader?.getResourceAsStream(name)
        if (stream == null) {
            println("SKIP — fixture '$name' not on test classpath (run ct-export-onnx / export_minilm_onnx.py)")
            return null
        }
        return decode(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
    }

    private fun assertClose(expected: List<Float>, actual: FloatArray, label: String) {
        assertTrue(
            expected.size == actual.size,
            "$label: size ${actual.size} != reference ${expected.size}",
        )
        for (i in expected.indices) {
            val diff = kotlin.math.abs(expected[i] - actual[i])
            assertTrue(
                diff <= TOLERANCE,
                "$label[$i]: |${actual[i]} - ${expected[i]}| = $diff > $TOLERANCE",
            )
        }
    }

    private companion object {
        const val TOLERANCE: Float = 1e-3f
    }
}

// Fixture shapes — match the JSON emitted by ct-export-onnx / export_minilm_onnx.py.
// ignoreUnknownKeys drops the diagnostic *_probs / vector_norm / metadata fields.

@Serializable
private data class ClassifierFixturePayload(val fixtures: List<ClassifierFixture>)

@Serializable
private data class ClassifierFixture(
    val id: String,
    val text: String,
    @SerialName("preflight_logits") val preflightLogits: List<Float>,
    @SerialName("presence_logits") val presenceLogits: List<Float>,
    @SerialName("category_logits") val categoryLogits: List<Float>,
)

@Serializable
private data class EmbedderFixturePayload(val fixtures: List<EmbedderFixture>)

@Serializable
private data class EmbedderFixture(
    val id: String,
    val text: String,
    val vector: List<Float>,
)
