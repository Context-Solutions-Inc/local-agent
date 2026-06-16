package com.contextsolutions.localagent.voice

import com.contextsolutions.localagent.inference.DesktopAppDirs
import com.contextsolutions.localagent.inference.DesktopModelDownloader
import com.contextsolutions.localagent.inference.DesktopModelInventory
import com.contextsolutions.localagent.inference.DesktopModelSpec
import com.contextsolutions.localagent.inference.ModelDownloadResult
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One prebuilt Piper binary archive (rhasspy/piper [PiperRelease.TAG]) for a given
 * OS/arch. Every archive extracts to a top-level `piper/` dir holding the executable
 * + its bundled `libonnxruntime`/`libpiper_phonemize`/`espeak-ng-data` (self-contained
 * via `$ORIGIN` rpath), so no system Piper/espeak install is needed.
 */
data class PiperAsset(
    val archiveName: String,
    val sha256: String,
    val sizeBytes: Long,
    /** Path of the executable inside the extracted archive. */
    val binaryRelPath: String,
    /** `.zip` (Windows) is unpacked with java.util.zip; `.tar.gz` with system `tar`. */
    val isZip: Boolean,
) {
    /** Stable per-asset cache subdir (archive name minus extension). */
    val label: String = archiveName.removeSuffix(".tar.gz").removeSuffix(".zip")
}

/**
 * Pinned Piper release + the per-host asset table (PR #66). Mirrors
 * [com.contextsolutions.localagent.inference.LlamaServerRelease]: a fixed TAG, verified
 * sha256/size per asset, and host→asset resolution. Bump TAG + all hashes together.
 */
object PiperRelease {
    const val TAG: String = "2023.11.14-2"
    private const val BASE: String = "https://github.com/rhasspy/piper/releases/download"

    val LINUX_X64 = tar("piper_linux_x86_64.tar.gz", "a50cb45f355b7af1f6d758c1b360717877ba0a398cc8cbe6d2a7a3a26e225992", 26_460_462)
    val LINUX_ARM64 = tar("piper_linux_aarch64.tar.gz", "fea0fd2d87c54dbc7078d0f878289f404bd4d6eea6e7444a77835d1537ab88eb", 26_004_717)
    val MACOS_X64 = tar("piper_macos_x64.tar.gz", "ced85c0a3df13945b1e623b878a48fdc2854d5c485b4b67f62857cf551deaf8b", 19_146_927)
    val MACOS_ARM64 = tar("piper_macos_aarch64.tar.gz", "6b1eb03b3735946cb35216e063e7eebcc33a6bbf5dd96ec0217959bf1cdcb0cc", 19_146_957)
    val WINDOWS_X64 = zip("piper_windows_amd64.zip", "f3c58906402b24f3a96d92145f58acba6d86c9b5db896d207f78dc80811efcea", 22_477_236)

    private fun tar(name: String, sha: String, size: Long) =
        PiperAsset(name, sha, size, binaryRelPath = "piper/piper", isZip = false)

    private fun zip(name: String, sha: String, size: Long) =
        PiperAsset(name, sha, size, binaryRelPath = "piper/piper.exe", isZip = true)

    fun downloadUrl(asset: PiperAsset): String = "$BASE/$TAG/${asset.archiveName}"

    fun assetForHost(
        os: String = System.getProperty("os.name").orEmpty().lowercase(),
        arch: String = System.getProperty("os.arch").orEmpty().lowercase(),
    ): PiperAsset? {
        val isArm = arch.contains("aarch64") || arch.contains("arm64")
        return when {
            os.contains("mac") || os.contains("darwin") -> if (isArm) MACOS_ARM64 else MACOS_X64
            os.contains("win") -> if (isArm) null else WINDOWS_X64
            os.contains("nux") || os.contains("nix") -> if (isArm) LINUX_ARM64 else LINUX_X64
            else -> null
        }
    }
}

/**
 * Downloads + caches the prebuilt Piper executable on first use (PR #66), the desktop
 * neural-TTS analogue of [com.contextsolutions.localagent.inference.LlamaServerBinaryStore].
 * Self-contained (no Python, no system speech engine). [ensure] returns the executable, or
 * null when there's no prebuilt Piper for this OS/arch (caller falls back to the OS engine).
 */
class PiperBinaryStore(
    private val baseDir: File = DesktopAppDirs.dataDir(),
    private val downloaderFactory: (DesktopModelInventory) -> DesktopModelDownloader = { inv ->
        DesktopModelDownloader(inv, logger = { System.err.println("[PiperBinaryDownload] $it") })
    },
    private val logger: (String) -> Unit = { System.err.println("[PiperBinary] $it") },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val piperDir: File get() = File(baseDir, "piper/bin").apply { mkdirs() }
    private val acquireMutex = Mutex()

    fun assetForHost(): PiperAsset? = PiperRelease.assetForHost()

    private fun envOverride(): File? =
        System.getenv(ENV_OVERRIDE)?.takeIf { it.isNotBlank() }?.let { File(it).takeIf(File::isFile) }

    private fun extractedBinary(asset: PiperAsset): File =
        File(File(piperDir, asset.label), asset.binaryRelPath)

    fun resolveExistingOrNull(): File? {
        envOverride()?.let { return it }
        val asset = assetForHost() ?: return null
        return extractedBinary(asset).takeIf(File::isFile)
    }

    suspend fun ensure(
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): File? = withContext(ioDispatcher) {
        resolveExistingOrNull()?.let { return@withContext it }
        val asset = assetForHost() ?: run {
            logger("no prebuilt Piper for os=${System.getProperty("os.name")} arch=${System.getProperty("os.arch")}")
            return@withContext null
        }

        acquireMutex.withLock {
            resolveExistingOrNull()?.let { return@withLock it }

            logger("acquiring Piper ${PiperRelease.TAG} [${asset.label}] (~${asset.sizeBytes / 1_000_000} MB)…")
            val variantDir = File(piperDir, asset.label)
            val spec = DesktopModelSpec(
                filename = asset.archiveName,
                downloadUrl = PiperRelease.downloadUrl(asset),
                sha256 = asset.sha256,
                sizeBytes = asset.sizeBytes,
            )
            val inventory = DesktopModelInventory(spec, baseDir = variantDir)
            when (val result = downloaderFactory(inventory).download(onProgress)) {
                is ModelDownloadResult.Success -> extract(result.file, variantDir, asset.isZip)
                is ModelDownloadResult.Failure -> {
                    logger("Piper binary download failed (retryable=${result.retryable}): ${result.message}")
                    return@withLock null
                }
            }
            val binary = extractedBinary(asset)
            if (!binary.isFile) {
                logger("Piper binary missing after extract at ${binary.path}")
                return@withLock null
            }
            if (!asset.isZip) binary.setExecutable(true, false)
            logger("Piper ready [${asset.label}]: ${binary.path}")
            binary
        }
    }

    /** `tar` for `.tar.gz` (preserves the bundled `.so` symlinks); java.util.zip for Windows `.zip`. */
    private fun extract(archive: File, targetDir: File, isZip: Boolean) {
        targetDir.mkdirs()
        if (isZip) {
            val canonical = targetDir.canonicalFile.toPath()
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val out = File(targetDir, entry.name)
                    check(out.canonicalFile.toPath().startsWith(canonical)) { "unsafe zip entry: ${entry.name}" }
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                }
            }
        } else {
            val proc = ProcessBuilder("tar", "-xf", archive.absolutePath, "-C", targetDir.absolutePath)
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().readText()
            val code = proc.waitFor()
            check(code == 0) { "tar extract failed (exit=$code): ${out.take(500)}" }
        }
        archive.delete()
    }

    private companion object {
        const val ENV_OVERRIDE = "LOCALAGENT_PIPER_BINARY"
    }
}
