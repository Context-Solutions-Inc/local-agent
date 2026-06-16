package com.contextsolutions.localagent.vision

import java.io.File
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Desktop [FilePicker] (docs/DESKTOP_PORT_PLAN.md, Phase 7) — an image chooser
 * via Swing's [JFileChooser] (filtered to common image extensions), the desktop
 * counterpart of Android's Photo Picker (invariant #39).
 *
 * The dialog is shown on the AWT event-dispatch thread (`invokeAndWait`) from an
 * IO-dispatcher coroutine, then the chosen file's bytes are read off the EDT.
 * Returns null if the user cancels or the file can't be read.
 */
class DesktopFilePicker(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val logger: (String) -> Unit = { System.err.println("[FilePicker] $it") },
) : FilePicker {

    override suspend fun pickImage(): ByteArray? = withContext(ioDispatcher) {
        val chosen = AtomicReference<File?>()
        try {
            SwingUtilities.invokeAndWait {
                val chooser = JFileChooser().apply {
                    dialogTitle = "Choose an image"
                    isMultiSelectionEnabled = false
                    fileFilter = FileNameExtensionFilter(
                        "Images", "jpg", "jpeg", "png", "webp", "gif", "bmp",
                    )
                }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    chosen.set(chooser.selectedFile)
                }
            }
        } catch (t: Throwable) {
            logger("file chooser failed: ${t.message}")
            return@withContext null
        }
        chosen.get()?.let { file ->
            runCatching { file.readBytes() }
                .onFailure { logger("read failed: ${it.message}") }
                .getOrNull()
        }
    }
}
