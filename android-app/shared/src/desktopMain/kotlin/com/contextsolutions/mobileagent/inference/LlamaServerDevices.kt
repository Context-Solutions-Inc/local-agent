package com.contextsolutions.mobileagent.inference

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A GPU device llama-server can offload to, as reported by `--list-devices` (#78). */
data class GpuDevice(val id: String, val description: String)

/**
 * Enumerates the host's GPU devices by running the prebuilt `llama-server --list-devices`
 * (#78), so the Settings UI can show the user whether to pin `Vulkan0` vs `Vulkan1`.
 *
 * Ensures the **GPU** binary ([LlamaServerBinaryStore.ensure] with `wantGpu = true`) — which may
 * download the ~32 MB Vulkan archive on first use — then spawns it with `--list-devices` under
 * the same native-lib env the server uses (so the probe finds the Vulkan/Metal driver) and parses
 * stdout. The id strings it returns are exactly what `--device` expects.
 */
class LlamaServerDevices(
    private val binaryStore: LlamaServerBinaryStore,
    private val logger: (String) -> Unit = { System.err.println("[LlamaServer] $it") },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun list(onProgress: (Long, Long) -> Unit = { _, _ -> }): List<GpuDevice> =
        withContext(ioDispatcher) {
            val binary = binaryStore.ensure(wantGpu = true, onProgress = onProgress)
            val proc = ProcessBuilder(binary.absolutePath, "--list-devices")
                .directory(binary.parentFile)
                .redirectErrorStream(true)
                .withNativeLibPath(binary.parentFile)
                .start()
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            parseDeviceList(output).also { logger("detected ${it.size} GPU device(s)") }
        }
}

private val DEVICE_LINE = Regex("""^\s+(\S+):\s+(.*\S)\s*$""")

/**
 * Parse `llama-server --list-devices` stdout into the offloadable GPU devices. Pure + `internal`
 * so it's unit-testable without a process. llama.cpp prints an `Available devices:` header
 * followed by indented `  <id>: <description>` rows; we collect the contiguous indented block and
 * drop the pure-`CPU` entry (the pin is for GPU offload). Returns empty on unexpected output.
 */
internal fun parseDeviceList(output: String): List<GpuDevice> {
    val lines = output.lines()
    val header = lines.indexOfFirst { it.trim().equals("Available devices:", ignoreCase = true) }
    if (header < 0) return emptyList()
    val devices = mutableListOf<GpuDevice>()
    for (line in lines.drop(header + 1)) {
        val match = DEVICE_LINE.matchEntire(line) ?: break // block ends at the first non-row line
        val id = match.groupValues[1]
        if (id.equals("CPU", ignoreCase = true)) continue
        devices += GpuDevice(id = id, description = match.groupValues[2])
    }
    return devices
}
