package com.contextsolutions.localagent.inference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit coverage for the PR #78 GPU device pin: the pure `buildServerArgs` flag builder and the
 * `parseDeviceList` `--list-devices` parser. The launch + native probe need a real binary
 * (operator on-device), so these assert the argv shape and the parsing in isolation.
 */
class LlamaServerGpuPinTest {

    private fun args(gpuLayers: Int, device: String?, extra: String? = null) = buildServerArgs(
        binary = "/srv/llama-server",
        modelPath = "/models/gemma.gguf",
        mmprojPath = null,
        host = "127.0.0.1",
        port = 8080,
        ctxTokens = 8192,
        gpuLayers = gpuLayers,
        vulkanDevice = device,
        apiKey = "key",
        extraArgs = extra,
    )

    /** A pair-wise lookup so we don't depend on absolute indices as flags shift. */
    private fun List<String>.valueOf(flag: String): String? =
        indexOf(flag).takeIf { it >= 0 && it + 1 <= lastIndex }?.let { this[it + 1] }

    @Test
    fun pinAppendsDeviceFlagOnGpu() {
        val out = args(gpuLayers = 999, device = "Vulkan1")
        assertTrue("--device" in out)
        assertEquals("Vulkan1", out.valueOf("--device"))
        // Order: --device sits after -ngl and before --parallel.
        assertTrue(out.indexOf("-ngl") < out.indexOf("--device"))
        assertTrue(out.indexOf("--device") < out.indexOf("--parallel"))
    }

    @Test
    fun blankPinOmitsDeviceFlag() {
        assertFalse("--device" in args(gpuLayers = 999, device = null))
        assertFalse("--device" in args(gpuLayers = 999, device = "   "))
    }

    @Test
    fun cpuVariantNeverPins() {
        // A pin set while on the CPU fallback (gpuLayers = 0) must not leak --device.
        assertFalse("--device" in args(gpuLayers = 0, device = "Vulkan1"))
    }

    @Test
    fun extraArgsStayLastSoTheyCanOverride() {
        val out = args(gpuLayers = 999, device = "Vulkan0", extra = "--device Vulkan1")
        // Our pin appears first; the escape-hatch override appears after it.
        val first = out.indexOf("--device")
        val last = out.lastIndexOf("--device")
        assertTrue(last > first)
        assertEquals("Vulkan1", out[last + 1])
    }

    @Test
    fun parsesMultiGpuListDroppingCpu() {
        val output = """
            ggml_vulkan: Found 2 Vulkan devices:
            Available devices:
              CPU: 13th Gen Intel(R) Core(TM) i9
              Vulkan0: Intel(R) Graphics (16384 MiB, 15923 MiB free)
              Vulkan1: NVIDIA GeForce RTX 5090 (24564 MiB, 24000 MiB free)
        """.trimIndent()
        val devices = parseDeviceList(output)
        assertEquals(
            listOf(
                GpuDevice("Vulkan0", "Intel(R) Graphics (16384 MiB, 15923 MiB free)"),
                GpuDevice("Vulkan1", "NVIDIA GeForce RTX 5090 (24564 MiB, 24000 MiB free)"),
            ),
            devices,
        )
    }

    @Test
    fun parsesSingleGpu() {
        val output = """
            Available devices:
              Vulkan0: NVIDIA GeForce RTX 5090 (24564 MiB, 24000 MiB free)
        """.trimIndent()
        assertEquals(listOf(GpuDevice("Vulkan0", "NVIDIA GeForce RTX 5090 (24564 MiB, 24000 MiB free)")), parseDeviceList(output))
    }

    @Test
    fun stopsAtFirstNonRowAfterHeader() {
        val output = """
            Available devices:
              Vulkan0: NVIDIA GeForce RTX 5090 (24564 MiB)

            other diagnostic: Vulkan9 noise
        """.trimIndent()
        assertEquals(listOf(GpuDevice("Vulkan0", "NVIDIA GeForce RTX 5090 (24564 MiB)")), parseDeviceList(output))
    }

    @Test
    fun emptyOnGarbageOrNoHeader() {
        assertTrue(parseDeviceList("").isEmpty())
        assertTrue(parseDeviceList("ggml_vulkan: nothing useful here").isEmpty())
    }
}
