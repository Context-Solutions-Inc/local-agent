package com.contextsolutions.mobileagent.preferences

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Persistent user preference pointing the *large chat LLM* at a remote
 * [Ollama](https://ollama.com) server on the user's LAN (PR #56). When
 * [OllamaConfig.isConfigured] is true the agent routes chat generation to that
 * server instead of the on-device runtime (LiteRT-LM on Android, llama-server on
 * desktop) — the pre-flight classifier, embedder, search verticals and memory
 * stay fully on-device regardless.
 *
 * Mirrors [com.contextsolutions.mobileagent.language.LanguagePreferences]:
 *
 *  - Plain `SharedPreferences` on Android / a `DesktopJsonStore` file on desktop
 *    (host/port/model are configuration, not credentials).
 *  - In-memory `MutableStateFlow` seeded from disk at construction; writes update
 *    both for next-process recovery and current-process subscribers.
 *  - The whole [OllamaConfig] is stored as one JSON blob under a single key.
 *
 * The [configFlow] is load-bearing: editing the server in Settings must drop a
 * resident model so the next turn re-decides the backend (see
 * `RoutingInferenceEngine`). The Android session manager / desktop warm-model
 * runtime observe it and force an unload on change.
 */
interface OllamaPreferences {

    /** Snapshot read. Safe from any dispatcher; serves from in-memory state. */
    fun config(): OllamaConfig

    /** Reactive read. Emits the current value on subscribe, then each change. */
    fun configFlow(): Flow<OllamaConfig>

    /** Persist [config] for current and future processes. Idempotent. */
    fun setConfig(config: OllamaConfig)

    /** Clear the remote-server config (reverts to the on-device model). */
    fun clear() = setConfig(OllamaConfig.EMPTY)
}

/**
 * Backend type the remote chat LLM speaks (PR #73).
 *
 *  - [OLLAMA] — an Ollama server addressed as `host:port`; the engine appends
 *    `/api/tags` (model discovery) and `/v1/chat/completions` (generation), and
 *    it defaults to plain HTTP.
 *  - [OPENAI] — a generic OpenAI-compatible server (OpenAI, OpenRouter, LM Studio,
 *    vLLM, llama-server, LocalAI). The user supplies the full **base URL**
 *    (the OpenAI `base_url` convention, e.g. `https://openrouter.ai/api/v1` or
 *    `http://localhost:1234/v1`); the engine appends `/models` and
 *    `/chat/completions`. Always HTTPS unless the base URL pins `http://`.
 */
@Serializable
enum class RemoteServerType {
    OLLAMA,
    OPENAI,
    ;

    /** Control-plane model-list path appended to [OllamaConfig.baseUrl]. */
    val modelsPath: String
        get() = when (this) {
            OLLAMA -> "/api/tags"
            OPENAI -> "/models"
        }

    /** Chat-completions path appended to [OllamaConfig.baseUrl]. */
    val chatCompletionsPath: String
        get() = when (this) {
            OLLAMA -> "/v1/chat/completions"
            OPENAI -> "/chat/completions"
        }
}

/**
 * Remote chat-LLM server configuration. For Ollama, [host] is an IP or hostname
 * (scheme optional) and [port] is the listen port (default 11434). For an
 * OpenAI-compatible server, [host] holds the full base URL (e.g.
 * `https://openrouter.ai/api/v1`) and [port] is ignored. [chatModel] serves text
 * turns; [visionModel] serves image turns — when blank, image turns fall back to
 * [chatModel] (many Ollama models, e.g. `gemma3:4b`, are multimodal).
 *
 * [serverType] selects the control-plane wire shape (PR #73); [useSsl] forces an
 * `https://` scheme. An [OPENAI][RemoteServerType.OPENAI] server is implicitly
 * SSL regardless of [useSsl].
 */
@Serializable
data class OllamaConfig(
    val host: String = "",
    val port: Int? = null,
    val chatModel: String = "",
    val visionModel: String = "",
    val serverType: RemoteServerType = RemoteServerType.OLLAMA,
    val useSsl: Boolean = false,
    /**
     * User on/off switch for the remote connection (PR #73). Defaults `true` so an
     * existing saved config (persisted before this field existed) keeps routing
     * remotely after upgrade. When `false`, chat stays on-device even though the
     * server details are still saved — the user can flip it back on without
     * re-entering anything. Routing gates on [isActive], not [isConfigured].
     */
    val enabled: Boolean = true,
) {
    /**
     * True once the server + chat model are set. Ollama needs host + port; an
     * OpenAI-compatible server needs only the base URL ([host]) since its port
     * lives in that URL. Note: "configured" ≠ "in use" — see [isActive].
     */
    val isConfigured: Boolean
        get() = when (serverType) {
            RemoteServerType.OLLAMA -> host.isNotBlank() && port != null && chatModel.isNotBlank()
            RemoteServerType.OPENAI -> host.isNotBlank() && chatModel.isNotBlank()
        }

    /** The routing gate: configured AND switched on. Disables the local LLM only when true. */
    val isActive: Boolean
        get() = enabled && isConfigured

    /** Effective SSL: explicit [useSsl], or implied by an OpenAI-compatible server. */
    val sslEnabled: Boolean
        get() = useSsl || serverType == RemoteServerType.OPENAI

    /**
     * Base URL the engine appends the control-plane / chat paths to (see
     * [RemoteServerType.modelsPath] / [RemoteServerType.chatCompletionsPath]).
     *
     *  - [OLLAMA][RemoteServerType.OLLAMA]: `scheme://host:port`
     *    (e.g. `http://192.168.1.50:11434`); null when host/port are unset.
     *  - [OPENAI][RemoteServerType.OPENAI]: the user-supplied base URL verbatim
     *    (e.g. `https://openrouter.ai/api/v1`), so any path is preserved and the
     *    [port] field is ignored; null when [host] is blank.
     *
     * An explicit scheme the user typed always wins; otherwise the scheme is
     * `https://` when [sslEnabled] (SSL box ticked or OpenAI-compatible), else
     * `http://` (a bare LAN Ollama is plain HTTP).
     */
    fun baseUrl(): String? {
        if (host.isBlank()) return null
        val h = host.trim().trimEnd('/')
        val schemed = when {
            h.startsWith("http://") || h.startsWith("https://") -> h
            sslEnabled -> "https://$h"
            else -> "http://$h"
        }
        return when (serverType) {
            RemoteServerType.OPENAI -> schemed
            RemoteServerType.OLLAMA -> if (port == null) null else "$schemed:$port"
        }
    }

    /** Model to use for a turn, picking [visionModel] for image turns when set. */
    fun modelFor(hasImage: Boolean): String =
        if (hasImage && visionModel.isNotBlank()) visionModel else chatModel

    companion object {
        const val DEFAULT_PORT = 11434
        val EMPTY = OllamaConfig()
    }
}
