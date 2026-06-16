package com.contextsolutions.localagent.inference

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Prebuilt `llama-server` binaries for the host OS/arch (docs/DESKTOP_LLAMA_SERVER_PLAN.md).
 * Desktop inference runs through llama.cpp's reference server (PR #55, Option 3) instead of
 * the `net.ladenthin:llama` JNI binding, which drops images before the vision encoder and
 * ships CPU-only natives. The official release archives are tiny (CPU 13 MB, Vulkan 30 MB)
 * so we download-on-first-run and cache, reusing [DesktopModelDownloader]'s resumable/
 * SHA-verified streaming; only the unpack step is new.
 *
 * **Variants (PR #55 GPU pass):** CPU (always available) and **Vulkan** (cross-vendor GPU:
 * Intel/AMD/NVIDIA on Linux + Windows). macOS ships a single Metal-capable archive for both
 * — GPU there is just `-ngl`. The engine requests a GPU variant by default and falls back to
 * CPU if the GPU server can't start (no driver). Each variant caches under its own dir
 * (`<app-data>/server/<label>/llama-<tag>/`) keyed by the asset label so they coexist.
 *
 * `LOCALAGENT_LLAMA_SERVER` overrides the binary path to an existing build (dev/testing;
 * skips download), and `LOCALAGENT_LLAMA_SERVER_VARIANT` (`cpu`|`vulkan`|`auto`) overrides
 * the variant choice — see [LlamaServerInferenceEngine].
 */
class LlamaServerBinaryStore(
    private val baseDir: File = DesktopAppDirs.dataDir(),
    private val downloaderFactory: (DesktopModelInventory) -> DesktopModelDownloader = { inv ->
        DesktopModelDownloader(inv, logger = { System.err.println("[LlamaServerDownload] $it") })
    },
    private val logger: (String) -> Unit = { System.err.println("[LlamaServer] $it") },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val serverDir: File get() = File(baseDir, "server").apply { mkdirs() }

    // Serializes acquisition so concurrent ensure() calls (the Main prefetch + the
    // eager-warm loadModel both request the GPU variant at startup) don't race on the same
    // download — that collision produced a "Size mismatch … got 0" and a spurious CPU
    // fallback on first launch. The loser waits, then reuses the freshly-extracted binary.
    private val acquireMutex = Mutex()

    /** Per-variant cache dir, keyed by the asset label so CPU/Vulkan archives don't collide. */
    private fun extractedBinary(asset: LlamaServerAsset): File {
        val name = if (isWindows()) "llama-server.exe" else "llama-server"
        return File(File(File(serverDir, asset.label), LlamaServerRelease.ARCHIVE_ROOT), name)
    }

    /** The env-override binary if set+present (takes precedence over any downloaded variant). */
    private fun overrideBinary(): File? =
        System.getenv(ENV_OVERRIDE)?.takeIf { it.isNotBlank() }?.let { File(it).takeIf { f -> f.isFile } }

    fun isPresent(wantGpu: Boolean): Boolean = resolveBinaryOrNull(wantGpu) != null

    fun resolveBinaryOrNull(wantGpu: Boolean): File? {
        overrideBinary()?.let { return it }
        val asset = LlamaServerRelease.assetForHost(wantGpu) ?: return null
        return extractedBinary(asset).takeIf { it.isFile }
    }

    /**
     * Returns the runnable `llama-server` for the requested variant, downloading + extracting
     * it on first call. Idempotent; throws when no build maps to this OS/arch or the
     * download/extract fails. The engine catches a GPU failure and retries with [wantGpu] =
     * false. Runs on [ioDispatcher].
     */
    suspend fun ensure(
        wantGpu: Boolean,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): File = withContext(ioDispatcher) {
        overrideBinary()?.let { return@withContext it }

        val asset = LlamaServerRelease.assetForHost(wantGpu)
            ?: error(
                "No prebuilt llama-server (gpu=$wantGpu) for os=${System.getProperty("os.name")} " +
                    "arch=${System.getProperty("os.arch")}. Set $ENV_OVERRIDE to a local binary.",
            )
        // Fast path: already on disk (avoids taking the lock on every load).
        resolveExtracted(asset)?.let { return@withContext it }

        acquireMutex.withLock {
            // Re-check inside the lock: a concurrent caller may have just finished.
            resolveExtracted(asset)?.let { return@withLock it }

            logger("acquiring llama-server ${LlamaServerRelease.TAG} [${asset.label}]")
            val spec = DesktopModelSpec(
                filename = asset.assetName,
                downloadUrl = LlamaServerRelease.downloadUrl(asset),
                sha256 = asset.sha256,
                sizeBytes = asset.sizeBytes,
            )
            // baseDir = the variant's cache dir ⇒ archive lands in <…>/<label>/models/<asset>.
            val variantDir = File(serverDir, asset.label)
            val inventory = DesktopModelInventory(spec, baseDir = variantDir)
            when (val result = downloaderFactory(inventory).download(onProgress)) {
                is ModelDownloadResult.Success -> extract(result.file, variantDir)
                is ModelDownloadResult.Failure ->
                    error("llama-server download failed (retryable=${result.retryable}): ${result.message}")
            }

            val binary = extractedBinary(asset)
            check(binary.isFile) { "llama-server not found after extract at ${binary.path}" }
            if (!isWindows()) binary.setExecutable(true, false)
            logger("llama-server ready [${asset.label}]: ${binary.path}")
            binary
        }
    }

    private fun resolveExtracted(asset: LlamaServerAsset): File? =
        extractedBinary(asset).takeIf { it.isFile }?.also {
            if (!isWindows()) it.setExecutable(true, false)
        }

    /**
     * Extract the archive into [targetDir] (yielding `llama-<tag>/`). Shells out to the
     * system `tar` (GNU on Linux, bsdtar on macOS/Windows 10+), which autodetects gzip/zip
     * and — unlike `java.util.zip` — preserves the `.so` version symlinks
     * (`libllama.so → libllama.so.0 → …`) the loader needs.
     */
    private fun extract(archive: File, targetDir: File) {
        targetDir.mkdirs()
        val proc = ProcessBuilder("tar", "-xf", archive.absolutePath, "-C", targetDir.absolutePath)
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        check(code == 0) { "tar extract failed (exit=$code): ${out.take(500)}" }
        archive.delete()
    }

    private companion object {
        const val ENV_OVERRIDE = "LOCALAGENT_LLAMA_SERVER"
        fun isWindows() = System.getProperty("os.name").orEmpty().lowercase().contains("win")
    }
}

/**
 * Verified coordinates of one prebuilt `llama-server` archive (sha256 = GitHub asset digest).
 * [label] is the cache-dir key (`assetName` minus the `llama-<tag>-bin-` prefix + extension),
 * e.g. `ubuntu-vulkan-x64`.
 */
data class LlamaServerAsset(val assetName: String, val sha256: String, val sizeBytes: Long) {
    val label: String = assetName
        .removePrefix("llama-${LlamaServerRelease.TAG}-bin-")
        .removeSuffix(".tar.gz")
        .removeSuffix(".zip")
}

/**
 * Pinned llama.cpp release + per-host asset map (CPU + Vulkan GPU). Bumping [TAG] requires
 * refreshing every asset's sha256/size from the release's `digest` field (verify the
 * resolved binary on-device, since this is the inference runtime).
 */
object LlamaServerRelease {
    const val TAG: String = "b9478"

    /** The single top-level dir inside every archive. */
    const val ARCHIVE_ROOT: String = "llama-b9478"

    private const val BASE: String = "https://github.com/ggml-org/llama.cpp/releases/download"

    // -- CPU --
    val LINUX_X64 = asset("ubuntu-x64.tar.gz", "d23cbbf78f7fa0c0f9a84d2a997b5bf02501ae89d682318283976f63fc1feae2", 14_540_495)
    val LINUX_ARM64 = asset("ubuntu-arm64.tar.gz", "9689f76fe26400608c83614feaa7f10b9c81220b512317b08b795f9b5b543812", 11_566_557)
    val MACOS_ARM64 = asset("macos-arm64.tar.gz", "600b1e5de564c4576d56c086b2b09f5f1e99fc44b7baaa1c90d5239aaefa9c1f", 9_672_399)
    val MACOS_X64 = asset("macos-x64.tar.gz", "546543647f20dd5b1df1e0569822a8295fe0744857ceab0b1f3f252f3c61efa5", 9_942_894)
    val WINDOWS_X64 = asset("win-cpu-x64.zip", "cfda69c5f5b05c71ad39f2511a2bd3beeeff41dd7c29cd2e26790cd1bd1744a9", 16_120_149)

    // -- Vulkan (cross-vendor GPU: Intel/AMD/NVIDIA). macOS uses Metal via its CPU archive. --
    val LINUX_VULKAN_X64 = asset("ubuntu-vulkan-x64.tar.gz", "a803ab4d7aea0e9c0e5ebe365542ad1e4be4b5b654b986ca497afdf6790929da", 32_265_474)
    val LINUX_VULKAN_ARM64 = asset("ubuntu-vulkan-arm64.tar.gz", "28c39d79aa37f004f91e4ed6262efe1825220be7a7d15f9983ba8c6ea8854d14", 25_443_545)
    val WINDOWS_VULKAN_X64 = asset("win-vulkan-x64.zip", "3f7470ffce62bc061486eabe911e72631f2d02b245424b8df37f59b794956d64", 33_071_323)

    private fun asset(suffix: String, sha: String, size: Long) =
        LlamaServerAsset("llama-$TAG-bin-$suffix", sha, size)

    fun downloadUrl(asset: LlamaServerAsset): String = "$BASE/$TAG/${asset.assetName}"

    /**
     * Archive for this host. When [wantGpu], returns the GPU build — Vulkan on Linux/Windows,
     * the (Metal-capable) macOS archive on macOS; falls back to the CPU archive when no GPU
     * build maps to the platform. Null only when the OS/arch has no mapped archive at all.
     */
    fun assetForHost(
        wantGpu: Boolean,
        os: String = System.getProperty("os.name").orEmpty().lowercase(),
        arch: String = System.getProperty("os.arch").orEmpty().lowercase(),
    ): LlamaServerAsset? {
        val isArm = arch.contains("aarch64") || arch.contains("arm64")
        return when {
            // macOS: one archive serves CPU + Metal; `-ngl` decides offload.
            os.contains("mac") || os.contains("darwin") -> if (isArm) MACOS_ARM64 else MACOS_X64
            os.contains("win") -> when {
                isArm -> null
                wantGpu -> WINDOWS_VULKAN_X64
                else -> WINDOWS_X64
            }
            os.contains("nux") || os.contains("nix") -> when {
                wantGpu && isArm -> LINUX_VULKAN_ARM64
                wantGpu -> LINUX_VULKAN_X64
                isArm -> LINUX_ARM64
                else -> LINUX_X64
            }
            else -> null
        }
    }
}
