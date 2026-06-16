package com.contextsolutions.localagent.ui.job

import androidx.compose.runtime.Composable

/**
 * The result of choosing a job: the OS-resolved program to run and the folder it
 * lives in (which becomes the subprocess working directory so the program picks
 * up cwd-relative state like `.env`). See [JobProgramPicker].
 */
data class PickedJobProgram(val programPath: String, val workingDir: String)

/**
 * Platform picker for a job's program (PR #86). Mirrors the memory-backup picker
 * (`BackupFilePicker`): a commonMain seam with a `@Composable expect`, a desktop
 * Swing actual, and a no-op Android actual.
 *
 * The desktop actual makes the user pick a *job folder* that contains a
 * `job.settings.json` manifest (enforced — a folder without it can't be
 * confirmed), reads the manifest, resolves the correct entry point for the
 * current OS, and returns it as a [PickedJobProgram]. Mobile never picks (jobs run
 * on the desktop), so its actual is a no-op and the Browse button is gated on
 * `isDesktopPlatform`.
 */
interface JobProgramPicker {
    /** Opens a native chooser; calls back with the resolved program + folder, or null if cancelled/invalid. */
    fun launch(onPicked: (PickedJobProgram?) -> Unit)
}

@Composable
expect fun rememberJobProgramPicker(): JobProgramPicker
