package com.contextsolutions.localagent.vision

/**
 * Opens the OS image picker and returns the chosen file's raw bytes
 * (docs/DESKTOP_PORT_PLAN.md, Phase 7, invariant #39) — the cross-platform seam
 * behind Android's Photo Picker (`PickVisualMedia`) and desktop's
 * `FileDialog`/`JFileChooser`.
 *
 * Returns the raw image bytes so the result flows straight into
 * [ImagePreprocessor.toModelJpeg]; returns null if the user cancels. Keeping it
 * `ByteArray`-based (not a `Uri`/`File`) lets the interface live in `commonMain`.
 */
interface FilePicker {
    /** Show the image picker; the chosen file's bytes, or null if cancelled. */
    suspend fun pickImage(): ByteArray?
}
