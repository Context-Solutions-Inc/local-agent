package com.contextsolutions.localagent.job

import com.contextsolutions.localagent.inference.DesktopAppDirs
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Per-job marker (`.localagent-init.json`) recording a successful initialization (PR #100). */
@Serializable
internal data class JobInitMarker(
    val manifestVersion: Int,
    val completedAtEpochMs: Long,
)

/**
 * Desktop [JobInitializer] (PR #100). Builds an ordered list of init units from the job's
 * `init` block — runtime checks (`requires`), non-interactive `run` steps, interactive
 * `launch` steps, and the `verify` check — exposes it via [plan], and runs it via [initialize]
 * reporting per-step progress.
 *
 *  - **runtime** (`requires: ["node"]`): ensure Node.js + npm; install a private copy into
 *    app-data if the system has none ([DesktopNodeProvisioner]). Subsequent steps + the job's
 *    own runs then get it on `PATH` ([DesktopJobRuntimeEnv]).
 *  - **non-interactive** ([JobInitStep.run]): run the shell command; non-zero ⇒ failure.
 *  - **interactive** ([JobInitStep.launch]): show instructions, launch the command (e.g. a real
 *    Chrome window), wait for the user to close it; only a launch that never STARTS fails.
 *  - **verify** ([JobInitSpec.verify]): a final OS-keyed command that must exit 0.
 *
 * On success a `.localagent-init.json` marker (manifest version) is written so re-choosing the
 * job — or a new app deployment that leaves the marker — skips setup. Cancellation-aware: the
 * waits poll + `ensureActive`, so cancelling the caller (the dialog's Cancel) kills the
 * subprocess. Commands are full shell strings (`sh -c` / `powershell -Command`) run in the job
 * dir — the manifest is trusted bundled content, so no injection-safe positional binding.
 *
 * **Security note (L4 — accepted risk).** The `init` manifest is read from the *writable* overlay
 * `<app-data>/agent-jobs` ([DesktopJobLibraryStore.dir]), not the read-only classpath bundle, and
 * the overlay is never pruned. There is **no remote / LLM input path** to these commands, so this
 * is a purely **local-tamper** concern (an attacker who can already write the user's app-data dir).
 * The extracted exec bit is owner-only ([DesktopJobLibraryStore.markExecutables], fix F3). Reading
 * the manifest from the bundle / hash-verifying the tree is out of scope for the current threat
 * model; revisit if untrusted job sources are ever introduced.
 */
class DesktopJobInitializer(
    private val nodeProvisioner: DesktopNodeProvisioner? = null,
    private val baseDir: File = DesktopAppDirs.dataDir(),
    private val nonInteractiveTimeoutSeconds: Long = DEFAULT_RUN_TIMEOUT_SECONDS,
    private val interactiveTimeoutSeconds: Long = DEFAULT_INTERACTIVE_TIMEOUT_SECONDS,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val logger: (String) -> Unit = {},
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val browserDetector: () -> File? = { DesktopToolPreflight.detectBrowser() },
) : JobInitializer {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

    override suspend fun plan(entry: JobCatalogEntry): List<JobInitStepInfo> =
        withContext(ioDispatcher) { buildUnits(entry).map { it.info } }

    override suspend fun initialize(
        entry: JobCatalogEntry,
        onProgress: (JobInitProgress) -> Unit,
    ): JobInitResult = withContext(ioDispatcher) {
        val workingDir = File(entry.workingDir)
        val settings = JobSettingsLoader.load(workingDir)
            ?: return@withContext JobInitResult.Failed("Read manifest", "Couldn't read ${JobSettingsLoader.FILE_NAME} in ${workingDir.path}.")
        val units = buildUnits(entry)
        if (units.isEmpty()) return@withContext JobInitResult.AlreadyInitialized

        if (isAlreadyInitialized(workingDir, settings.version)) {
            units.indices.forEach { onProgress(JobInitProgress(it, JobInitStepState.DONE)) }
            logger("'${entry.id}' already initialized (manifest v${settings.version})")
            return@withContext JobInitResult.AlreadyInitialized
        }

        units.forEachIndexed { index, unit ->
            val failure = runUnit(index, unit, workingDir, onProgress)
            if (failure != null) {
                onProgress(JobInitProgress(index, JobInitStepState.FAILED, failure.reason))
                return@withContext failure
            }
            onProgress(JobInitProgress(index, JobInitStepState.DONE))
        }

        writeMarker(workingDir, settings.version)
        logger("'${entry.id}' initialized (manifest v${settings.version})")
        JobInitResult.Succeeded
    }

    /** Run one unit; return a [JobInitResult.Failed] on failure, else null. */
    private suspend fun runUnit(
        index: Int,
        unit: InitUnit,
        workingDir: File,
        onProgress: (JobInitProgress) -> Unit,
    ): JobInitResult.Failed? = when (unit) {
        is InitUnit.Runtime -> {
            onProgress(JobInitProgress(index, JobInitStepState.RUNNING))
            val provisioner = nodeProvisioner
                ?: return JobInitResult.Failed(unit.info.title, "Node.js provisioning is unavailable.")
            when (val r = provisioner.ensure { msg -> onProgress(JobInitProgress(index, JobInitStepState.RUNNING, msg)) }) {
                is NodeResult.Available -> null
                is NodeResult.Failed -> JobInitResult.Failed(unit.info.title, r.reason)
            }
        }
        is InitUnit.Tool -> {
            onProgress(JobInitProgress(index, JobInitStepState.RUNNING))
            val found = when (unit.tool) {
                "chrome" -> browserDetector()
                else -> null
            }
            if (found == null) JobInitResult.Failed(unit.info.title, DesktopToolPreflight.friendlyToolMessage(unit.tool))
            else null
        }
        is InitUnit.Run -> {
            onProgress(JobInitProgress(index, JobInitStepState.RUNNING))
            val r = runCommand(unit.command, workingDir, nonInteractiveTimeoutSeconds)
            if (r.exitCode != 0 || r.startFailed) JobInitResult.Failed(unit.info.title, failureReason(r)) else null
        }
        is InitUnit.Launch -> {
            // Pre-flight: a missing launch program exits 127 under `sh -c` (startFailed = false)
            // and would otherwise be silently marked DONE. Catch it before launching.
            val exe = DesktopToolPreflight.leadingExecutable(unit.command, isWindows)
            if (exe != null && DesktopToolPreflight.resolveExecutable(
                    exe,
                    extraDirs = DesktopJobRuntimeEnv.extraPathEntries(baseDir).map(::File),
                ) == null
            ) {
                JobInitResult.Failed(unit.info.title, DesktopToolPreflight.friendlyToolMessage(exe))
            } else {
                onProgress(JobInitProgress(index, JobInitStepState.AWAITING_USER, unit.info.instructions))
                val r = runCommand(unit.command, workingDir, interactiveTimeoutSeconds, failIfNonZero = false)
                // Only a launch that never started is a failure; the exit code of an interactive
                // program the user closes isn't meaningful.
                if (r.startFailed) JobInitResult.Failed(unit.info.title, failureReason(r)) else null
            }
        }
        is InitUnit.Verify -> {
            onProgress(JobInitProgress(index, JobInitStepState.RUNNING))
            val r = runCommand(unit.command, workingDir, nonInteractiveTimeoutSeconds)
            if (r.exitCode != 0) JobInitResult.Failed(unit.info.title, failureReason(r)) else null
        }
    }

    // ---- plan construction -------------------------------------------------

    private fun buildUnits(entry: JobCatalogEntry): List<InitUnit> {
        val settings = JobSettingsLoader.load(File(entry.workingDir)) ?: return emptyList()
        val spec = settings.init ?: return emptyList()
        val osKey = JobSettingsLoader.currentOsKey() ?: return emptyList()
        val units = mutableListOf<InitUnit>()

        spec.requires.forEach { req ->
            when (req.lowercase()) {
                "node", "npm", "nodejs" -> units += InitUnit.Runtime("node", stepInfo("require-node", "Set up Node.js", false, ""))
                "chrome", "google-chrome", "chromium", "browser" ->
                    units += InitUnit.Tool("chrome", stepInfo("require-chrome", "Check for Google Chrome", false, ""))
                else -> logger("unknown required runtime '$req' — ignored")
            }
        }
        spec.steps.forEachIndexed { i, step ->
            val title = step.title ?: step.id ?: "Step ${i + 1}"
            val info = stepInfo(step.id ?: "step-$i", title, step.interactive, step.instructions.orEmpty())
            if (step.interactive) {
                step.launch[osKey]?.takeIf { it.isNotBlank() }?.let { units += InitUnit.Launch(it, info) }
            } else {
                step.run[osKey]?.takeIf { it.isNotBlank() }?.let { units += InitUnit.Run(it, info) }
            }
        }
        spec.verify[osKey]?.takeIf { it.isNotBlank() }?.let {
            units += InitUnit.Verify(it, stepInfo("verify", "Verify setup", false, ""))
        }
        return units
    }

    private fun stepInfo(id: String, title: String, interactive: Boolean, instructions: String) =
        JobInitStepInfo(id = id, title = title, interactive = interactive, instructions = instructions)

    private sealed interface InitUnit {
        val info: JobInitStepInfo
        data class Runtime(val name: String, override val info: JobInitStepInfo) : InitUnit
        data class Tool(val tool: String, override val info: JobInitStepInfo) : InitUnit
        data class Run(val command: String, override val info: JobInitStepInfo) : InitUnit
        data class Launch(val command: String, override val info: JobInitStepInfo) : InitUnit
        data class Verify(val command: String, override val info: JobInitStepInfo) : InitUnit
    }

    private fun failureReason(r: CommandResult): String {
        val out = r.output.trim()
        return when {
            r.startFailed ->
                DesktopToolPreflight.extractMissingProgram(out)?.let { DesktopToolPreflight.friendlyToolMessage(it, out) }
                    ?: out.ifBlank { "Command failed to start." }
            r.timedOut -> (out + "\n(timed out)").trim()
            r.exitCode == 127 ->
                DesktopToolPreflight.extractMissingProgram(out)?.let { DesktopToolPreflight.friendlyToolMessage(it, out) }
                    ?: (out.ifBlank { "(no output)" } + "\n(exit code 127)")
            else -> (out.ifBlank { "(no output)" } + "\n(exit code ${r.exitCode})")
        }
    }

    // ---- subprocess --------------------------------------------------------

    private suspend fun runCommand(
        command: String,
        workingDir: File,
        timeoutSeconds: Long,
        failIfNonZero: Boolean = true,
    ): CommandResult {
        val proc = runCatching {
            ProcessBuilder(shellArgv(command))
                .directory(workingDir)
                .redirectErrorStream(true)
                .apply { DesktopJobRuntimeEnv.applyTo(environment(), baseDir) }
                .start()
        }.getOrElse { return CommandResult(exitCode = null, output = it.message.orEmpty(), startFailed = true) }

        val buffer = StringBuilder()
        val reader = thread(isDaemon = true, name = "job-init-out") {
            runCatching {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(buffer) { if (buffer.length < MAX_OUTPUT_CHARS) buffer.append(line).append('\n') }
                }
            }
        }
        val deadline = now() + timeoutSeconds * 1_000
        try {
            while (true) {
                currentCoroutineContext().ensureActive() // cancel → finally kills the process
                if (proc.waitFor(POLL_MS, TimeUnit.MILLISECONDS)) break
                if (now() >= deadline) {
                    proc.destroyForcibly()
                    reader.join(1_000)
                    return CommandResult(exitCode = null, output = output(buffer), timedOut = true)
                }
            }
            reader.join(1_000)
            return CommandResult(exitCode = proc.exitValue(), output = output(buffer))
        } finally {
            if (proc.isAlive) {
                proc.destroyForcibly()
                reader.join(1_000)
            }
        }
    }

    private fun output(buffer: StringBuilder): String = synchronized(buffer) { buffer.toString().trim() }

    // ---- marker ------------------------------------------------------------

    private fun isAlreadyInitialized(workingDir: File, manifestVersion: Int): Boolean {
        val marker = File(workingDir, MARKER_NAME).takeIf { it.isFile } ?: return false
        return runCatching { json.decodeFromString<JobInitMarker>(marker.readText()) }
            .getOrNull()?.manifestVersion == manifestVersion
    }

    private fun writeMarker(workingDir: File, manifestVersion: Int) {
        runCatching {
            File(workingDir, MARKER_NAME).writeText(
                json.encodeToString(JobInitMarker.serializer(), JobInitMarker(manifestVersion, now())),
            )
        }.onFailure { logger("failed to write init marker: ${it.message}") }
    }

    private data class CommandResult(
        val exitCode: Int?,
        val output: String,
        val startFailed: Boolean = false,
        val timedOut: Boolean = false,
    )

    companion object {
        const val MARKER_NAME = ".localagent-init.json"
        const val DEFAULT_RUN_TIMEOUT_SECONDS = 900L          // npm install (+ playwright) can be slow
        const val DEFAULT_INTERACTIVE_TIMEOUT_SECONDS = 1_800L // give the user 30 min to clear the bot check
        private const val MAX_OUTPUT_CHARS = 8 * 1024
        private const val POLL_MS = 200L

        private val isWindows: Boolean
            get() = System.getProperty("os.name").orEmpty().lowercase().contains("win")

        /** Run a full shell command string: `sh -c "<cmd>"` (POSIX) / `powershell -Command "<cmd>"`. */
        internal fun shellArgv(command: String): List<String> = if (isWindows) {
            listOf("powershell", "-NoProfile", "-Command", command)
        } else {
            listOf("sh", "-c", command)
        }
    }
}
