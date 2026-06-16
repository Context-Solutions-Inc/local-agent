package com.contextsolutions.localagent.inference

import com.contextsolutions.localagent.platform.HttpEngineFactory
import com.contextsolutions.localagent.platform.SecureStorage
import com.contextsolutions.localagent.platform.SecureStorageKeys
import com.contextsolutions.localagent.preferences.RemoteServerType
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Thin Ktor client for an Ollama server's *control-plane* endpoints (PR #56):
 * model discovery ([listModels]) and a reachability probe ([health]). The
 * streaming generation path lives in [OllamaInferenceEngine] — kept separate
 * because generation needs an un-timed client (long SSE streams) while these
 * short calls want a tight per-request timeout so a misconfigured host fails
 * fast (Settings "Test connection" + the engine's load-time fallback probe).
 *
 * Built from the platform [HttpEngineFactory] so it reuses the same engine
 * (OkHttp on Android, CIO on desktop), JSON negotiation and redacting logger as
 * the Brave search clients. There is one [OllamaClient] for the process; it is
 * shared by the Settings UI and the engine so the Ollama wire shapes are
 * decoded in exactly one place.
 */
class OllamaClient internal constructor(
    private val client: HttpClient,
    /**
     * Optional — supplies the user's [SecureStorageKeys.OLLAMA_API_KEY] (PR #58),
     * attached as `Authorization: Bearer` on the control-plane calls so Settings
     * "Test" + the health probe work against a protected server. Read per-request;
     * null/blank ⇒ no auth header.
     */
    private val secureStorage: SecureStorage? = null,
    /**
     * PR #73 — diagnostic logcat sink. Logs the full outbound control-plane
     * request: method, URL (incl. port) and the Bearer token. **Prints the API
     * key in cleartext** — a deliberate debug aid; logcat is on-device only.
     */
    private val logger: (String) -> Unit = {},
) {

    constructor(
        httpEngineFactory: HttpEngineFactory,
        secureStorage: SecureStorage? = null,
        logger: (String) -> Unit = {},
    ) : this(httpEngineFactory.create(), secureStorage, logger)

    /**
     * Model discovery → the installed models, by [serverType] (PR #73): Ollama
     * hits `GET <baseUrl>/api/tags`, an OpenAI-compatible server hits
     * `GET <baseUrl>/v1/models`. Vision-capability is a best-effort heuristic on
     * the model name (Ollama also surfaces a `clip`/`mllama` family); it only
     * sorts the Settings vision dropdown, never gates anything. Returns an empty
     * list on any error (caller treats empty as "unreachable").
     */
    suspend fun listModels(
        baseUrl: String,
        serverType: RemoteServerType = RemoteServerType.OLLAMA,
    ): List<OllamaModel> = try {
        val url = "${baseUrl.trimEnd('/')}${serverType.modelsPath}"
        logger("GET $url | header Authorization: ${apiKey()?.let { "Bearer $it" } ?: "(none)"}")
        val response = client.get(url) {
            timeout { requestTimeoutMillis = LIST_TIMEOUT_MS; connectTimeoutMillis = CONNECT_TIMEOUT_MS }
            apiKey()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        }
        if (!response.status.isSuccess()) {
            emptyList()
        } else when (serverType) {
            RemoteServerType.OLLAMA -> parseModels(response.bodyAsText())
            RemoteServerType.OPENAI -> parseOpenAiModels(response.bodyAsText())
        }
    } catch (c: CancellationException) {
        throw c
    } catch (_: Throwable) {
        emptyList()
    }

    /**
     * Cheap reachability check used by [OllamaInferenceEngine.loadModel] before
     * committing to the remote backend; on false the router falls back to the
     * on-device model. Tight timeout so an unreachable host doesn't stall the
     * first turn. Probes the [serverType]'s model-list endpoint (PR #73).
     */
    suspend fun health(
        baseUrl: String,
        serverType: RemoteServerType = RemoteServerType.OLLAMA,
    ): Boolean = try {
        val url = "${baseUrl.trimEnd('/')}${serverType.modelsPath}"
        logger("GET $url | header Authorization: ${apiKey()?.let { "Bearer $it" } ?: "(none)"} (health)")
        client.get(url) {
            timeout { requestTimeoutMillis = HEALTH_TIMEOUT_MS; connectTimeoutMillis = CONNECT_TIMEOUT_MS }
            apiKey()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        }.status.isSuccess()
    } catch (c: CancellationException) {
        throw c
    } catch (_: Throwable) {
        false
    }

    /** The configured outbound API key, or null when unset/blank (PR #58). */
    private fun apiKey(): String? =
        secureStorage?.get(SecureStorageKeys.OLLAMA_API_KEY)?.takeIf { it.isNotBlank() }

    private fun parseModels(raw: String): List<OllamaModel> = runCatching {
        JSON.parseToJsonElement(raw).jsonObject["models"]?.jsonArray.orEmpty().mapNotNull { el ->
            val obj = el.jsonObject
            val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val families = obj["details"]?.jsonObject?.get("families")?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.content }.orEmpty()
            OllamaModel(name = name, isVisionCapable = isVisionCapable(name, families))
        }.sortedBy { it.name }
    }.getOrDefault(emptyList())

    /** Parse the OpenAI `/v1/models` shape `{"data":[{"id":"…"}]}` (PR #73). */
    private fun parseOpenAiModels(raw: String): List<OllamaModel> = runCatching {
        JSON.parseToJsonElement(raw).jsonObject["data"]?.jsonArray.orEmpty().mapNotNull { el ->
            val name = el.jsonObject["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            OllamaModel(name = name, isVisionCapable = isVisionCapable(name, emptyList()))
        }.sortedBy { it.name }
    }.getOrDefault(emptyList())

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        const val CONNECT_TIMEOUT_MS = 2_500L
        const val HEALTH_TIMEOUT_MS = 3_000L
        const val LIST_TIMEOUT_MS = 5_000L

        // Families/name fragments that signal a multimodal (vision) model.
        val VISION_FAMILIES = setOf("clip", "mllama")
        val VISION_NAME_HINTS = listOf("llava", "vl", "vision", "bakllava", "moondream", "minicpm-v")

        fun isVisionCapable(name: String, families: List<String>): Boolean {
            if (families.any { it.lowercase() in VISION_FAMILIES }) return true
            val n = name.lowercase()
            return VISION_NAME_HINTS.any { n.contains(it) }
        }
    }
}

/** One installed Ollama model, as surfaced by `/api/tags`. */
data class OllamaModel(
    val name: String,
    val isVisionCapable: Boolean,
)
