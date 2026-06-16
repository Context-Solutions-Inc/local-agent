package com.contextsolutions.localagent.ui.job

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.contextsolutions.localagent.job.GitignoreMatcher
import com.contextsolutions.localagent.job.JobSettingsLoader
import java.awt.Color
import java.awt.Toolkit
import java.io.File
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.filechooser.FileFilter

private const val NEEDS_MANIFEST = "Select a ${JobSettingsLoader.FILE_NAME} file."
private val VALID_GREEN = Color(0x2E, 0x7D, 0x32)
private val INVALID_RED = Color(0xC6, 0x28, 0x28)

/**
 * Desktop actual: a Swing file chooser (run on the EDT via `invokeAndWait`) that
 * accepts only a `job.settings.json` manifest file — every other file is hidden so
 * the user sees exactly which file to pick for a job. From the selected file it reads
 * the manifest, resolves the program for the current OS, and returns the resolved
 * program path + the manifest's containing folder (the working dir). Returns `null`
 * (callback) on cancel or an invalid manifest.
 *
 * Polished (PR #87): renders with the system (GTK) look-and-feel scoped to this
 * dialog, hides gitignored clutter, and shows a live ✓/✗ validity accessory.
 */
@Composable
actual fun rememberJobProgramPicker(): JobProgramPicker = remember {
    object : JobProgramPicker {
        override fun launch(onPicked: (PickedJobProgram?) -> Unit) {
            onPicked(chooseJobProgram())
        }
    }
}

private fun chooseJobProgram(): PickedJobProgram? {
    var result: PickedJobProgram? = null
    val task = Runnable {
        // Use the system L&F (GTK on Linux) for the Swing chooser only; restore it
        // afterwards so nothing else is affected. Compose renders via Skiko, so the
        // app UI is unchanged regardless.
        val previousLaf = UIManager.getLookAndFeel()
        try {
            runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
                .onFailure { System.err.println("[JobProgramPicker] system L&F unavailable: ${it.message}") }

            // Enforce the manifest at selection time: only a job.settings.json file
            // can be confirmed (approveSelection refuses anything else).
            val chooser = object : JFileChooser() {
                override fun approveSelection() {
                    val file = selectedFile
                    if (file != null && file.isFile && file.name == JobSettingsLoader.FILE_NAME) {
                        super.approveSelection()
                    } else {
                        Toolkit.getDefaultToolkit().beep()
                        JOptionPane.showMessageDialog(this, NEEDS_MANIFEST, "Choose a job file", JOptionPane.WARNING_MESSAGE)
                    }
                }
            }.apply {
                dialogTitle = "Choose a ${JobSettingsLoader.FILE_NAME} file"
                fileSelectionMode = JFileChooser.FILES_ONLY
                isMultiSelectionEnabled = false
                isFileHidingEnabled = true  // drops dotfiles like .env/.git
                toolTipText = NEEDS_MANIFEST
            }

            installGitignoreFilter(chooser)
            installValidityAccessory(chooser)

            if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return@Runnable

            // The user picks the manifest file; the job's working dir is its folder.
            val folder: File = chooser.selectedFile?.parentFile ?: return@Runnable
            val settings = JobSettingsLoader.load(folder)
            if (settings == null) {
                // Defensive — approveSelection already guaranteed the file exists.
                JOptionPane.showMessageDialog(null, "Couldn't read ${JobSettingsLoader.FILE_NAME} in ${folder.path}.", "Invalid job settings", JOptionPane.ERROR_MESSAGE)
                return@Runnable
            }
            val program = JobSettingsLoader.resolveProgram(settings, folder)
            if (program == null) {
                val os = JobSettingsLoader.currentOsKey() ?: "this OS"
                JOptionPane.showMessageDialog(null, "${JobSettingsLoader.FILE_NAME} has no program entry for $os.", "Unsupported OS", JOptionPane.ERROR_MESSAGE)
                return@Runnable
            }
            result = PickedJobProgram(programPath = program.absolutePath, workingDir = folder.absolutePath)
        } finally {
            runCatching { UIManager.setLookAndFeel(previousLaf) }
        }
    }
    if (SwingUtilities.isEventDispatchThread()) task.run() else SwingUtilities.invokeAndWait(task)
    return result
}

/**
 * Show only the `job.settings.json` manifest among files (so the user sees exactly which
 * file to pick) while keeping navigable, non-gitignored directories visible. Best-effort.
 */
private fun installGitignoreFilter(chooser: JFileChooser) {
    var matcher = GitignoreMatcher.forDirectory(chooser.currentDirectory)
    chooser.addPropertyChangeListener(JFileChooser.DIRECTORY_CHANGED_PROPERTY) {
        matcher = GitignoreMatcher.forDirectory(chooser.currentDirectory)
    }
    chooser.fileFilter = object : FileFilter() {
        override fun accept(f: File): Boolean = when {
            f.isDirectory -> !matcher.isIgnored(f)
            else -> f.name == JobSettingsLoader.FILE_NAME
        }
        override fun getDescription(): String = JobSettingsLoader.FILE_NAME
    }
}

/** A left-side accessory panel that reflects whether the highlighted file is a valid job manifest. */
private fun installValidityAccessory(chooser: JFileChooser) {
    val label = JLabel(" ")
    val panel = JPanel().apply { add(label) }
    chooser.accessory = panel

    fun refresh(selected: File?) {
        val folder = selected
            ?.takeIf { it.isFile && it.name == JobSettingsLoader.FILE_NAME }
            ?.parentFile
        val settings = folder?.let { JobSettingsLoader.load(it) }
        val program = settings?.let { JobSettingsLoader.resolveProgram(it, folder) }
        if (program != null) {
            val os = JobSettingsLoader.currentOsKey() ?: "this OS"
            label.foreground = VALID_GREEN
            label.text = "✓ Valid job file — runs ${program.name} on $os"
        } else {
            label.foreground = INVALID_RED
            label.text = "✗ Select a ${JobSettingsLoader.FILE_NAME} file"
        }
    }
    refresh(chooser.selectedFile)
    chooser.addPropertyChangeListener(JFileChooser.SELECTED_FILE_CHANGED_PROPERTY) {
        refresh(chooser.selectedFile)
    }
}
