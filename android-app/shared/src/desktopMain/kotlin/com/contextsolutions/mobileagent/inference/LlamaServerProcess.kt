package com.contextsolutions.mobileagent.inference

import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.util.UUID
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * One `llama-server` child process (docs/DESKTOP_LLAMA_SERVER_PLAN.md). Spawns the prebuilt
 * binary bound to an ephemeral `127.0.0.1` port with a random API key, streams its
 * stdout/stderr to [logger] (so the native `srv`/`slot` lines stay visible), and waits for
 * `/health` to report ready. Held for the app's lifetime by [LlamaServerInferenceEngine] via
 * `WarmModel`; [stop] tears it down (also wired to a JVM shutdown hook as a backstop).
 *
 * Loads the model **once** — in the server — so there is no second in-JVM copy (the JNI
 * binding loaded a duplicate). Vision works because llama-server's `/v1/chat/completions`
 * feeds `image_url` content through `mtmd` (the reason for PR #55, Option 3).
 */
class LlamaServerProcess(
    private val binary: File,
    private val modelPath: String,
    private val mmprojPath: String?,
    private val ctxTokens: Int,
    private val gpuLayers: Int,
    private val logger: (String) -> Unit = { System.err.println("[LlamaServer] $it") },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Per-process key sent on every request so other local processes can't drive our server. */
    val apiKey: String = UUID.randomUUID().toString()

    @Volatile var baseUrl: String = ""
        private set

    @Volatile private var process: Process? = null
    private var shutdownHook: Thread? = null

    /**
     * Launch the server and suspend until `/health` is ready (or [readyTimeoutMs] elapses).
     * Returns the base URL (`http://127.0.0.1:<port>`). Throws on spawn failure / timeout.
     */
    suspend fun start(readyTimeoutMs: Long = 120_000): String = withContext(ioDispatcher) {
        val port = freePort()
        val url = "http://127.0.0.1:$port"
        val cmd = buildList {
            add(binary.absolutePath)
            add("-m"); add(modelPath)
            mmprojPath?.let { add("--mmproj"); add(it) }
            add("--host"); add("127.0.0.1")
            add("--port"); add(port.toString())
            add("-c"); add(ctxTokens.toString())
            add("-ngl"); add(gpuLayers.toString())
            // Single-user desktop: one slot (auto picks 4, splitting the KV cache for no
            // benefit and 4x the memory). Flash attention speeds Gemma's SWA decode.
            add("--parallel"); add("1")
            add("-fa"); add("on")
            add("--jinja")
            add("--no-webui")
            add("--api-key"); add(apiKey)
            // Power-user tuning without a rebuild, e.g. KV-cache quant for a bandwidth-bound
            // iGPU: MOBILEAGENT_LLAMA_SERVER_ARGS="--cache-type-k q8_0 --cache-type-v q8_0".
            System.getenv(ENV_EXTRA_ARGS)?.trim()?.takeIf { it.isNotEmpty() }
                ?.split(Regex("\\s+"))?.let { addAll(it) }
        }
        logger("starting: ${cmd.joinToString(" ").replace(apiKey, "<key>")}")

        val pb = ProcessBuilder(cmd)
            .directory(binary.parentFile)
            .redirectErrorStream(true)
        // The shared libs sit beside the binary; put that dir on the native lib path.
        val libDir = binary.parentFile.absolutePath
        pb.environment().apply {
            merge("LD_LIBRARY_PATH", libDir) { old, new -> "$new${File.pathSeparator}$old" }
            merge("DYLD_LIBRARY_PATH", libDir) { old, new -> "$new${File.pathSeparator}$old" }
            merge("PATH", libDir) { old, new -> "$new${File.pathSeparator}$old" }
        }
        val proc = pb.start()
        process = proc
        baseUrl = url

        // Tee server output to the logger so the user keeps seeing srv/slot lines.
        thread(isDaemon = true, name = "llama-server-log") {
            runCatching {
                proc.inputStream.bufferedReader().forEachLine { logger(it) }
            }
        }
        // Backstop: kill the server if the JVM exits without an explicit stop().
        shutdownHook = thread(start = false) { runCatching { proc.destroyForcibly() } }
            .also { Runtime.getRuntime().addShutdownHook(it) }

        awaitHealthy(url, readyTimeoutMs)
        logger("ready at $url")
        url
    }

    /** Poll `GET /health` until `{"status":"ok"}` (200), or fail after [timeoutMs]. */
    private suspend fun awaitHealthy(url: String, timeoutMs: Long) {
        var waited = 0L
        val step = 250L
        while (waited < timeoutMs) {
            currentCoroutineContext().ensureActive()
            if (process?.isAlive != true) error("llama-server exited during startup (see log above)")
            if (probeHealth(url)) return
            delay(step)
            waited += step
        }
        stop()
        error("llama-server did not become healthy within ${timeoutMs}ms")
    }

    private fun probeHealth(url: String): Boolean = runCatching {
        val conn = (URI("$url/health").toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 1_000
            readTimeout = 1_000
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            conn.responseCode == 200
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(false)

    /** Stop the server. Idempotent and safe if never started. */
    fun stop() {
        shutdownHook?.let { runCatching { Runtime.getRuntime().removeShutdownHook(it) } }
        shutdownHook = null
        process?.let { p ->
            p.destroy()
            if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
            logger("stopped")
        }
        process = null
    }

    private companion object {
        const val ENV_EXTRA_ARGS = "MOBILEAGENT_LLAMA_SERVER_ARGS"

        /** Grab a free localhost port (small race window between close and server bind). */
        fun freePort(): Int = ServerSocket(0).use { it.localPort }
    }
}
