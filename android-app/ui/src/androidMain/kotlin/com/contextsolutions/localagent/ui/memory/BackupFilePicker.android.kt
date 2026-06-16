package com.contextsolutions.localagent.ui.memory

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.contextsolutions.localagent.memory.BackupReader
import com.contextsolutions.localagent.memory.BackupWriter

/**
 * Android actual: Storage Access Framework launchers. The per-call `onPicked`
 * callback is stashed in composition state and invoked when the launcher
 * delivers (or cancels with) a `Uri`, which is wrapped as a
 * [BackupWriter]/[BackupReader] over `contentResolver`.
 */
@Composable
actual fun rememberBackupFilePicker(): BackupFilePicker {
    val context = LocalContext.current
    var exportCallback by remember { mutableStateOf<((BackupWriter?) -> Unit)?>(null) }
    var importCallback by remember { mutableStateOf<((BackupReader?) -> Unit)?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        exportCallback?.invoke(uri?.let { UriBackupWriter(context, it) })
        exportCallback = null
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        importCallback?.invoke(uri?.let { UriBackupReader(context, it) })
        importCallback = null
    }

    return remember {
        object : BackupFilePicker {
            override fun launchExport(suggestedName: String, onPicked: (BackupWriter?) -> Unit) {
                exportCallback = onPicked
                exportLauncher.launch(suggestedName)
            }

            override fun launchImport(onPicked: (BackupReader?) -> Unit) {
                importCallback = onPicked
                importLauncher.launch(arrayOf("application/json"))
            }
        }
    }
}

private class UriBackupWriter(
    private val context: Context,
    private val uri: Uri,
) : BackupWriter {
    override suspend fun writeText(text: String) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
            stream.write(text.toByteArray(Charsets.UTF_8))
            stream.flush()
        } ?: error("Couldn't open the chosen file for writing.")
    }
}

private class UriBackupReader(
    private val context: Context,
    private val uri: Uri,
) : BackupReader {
    override suspend fun readText(): String =
        context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.readBytes().toString(Charsets.UTF_8)
        } ?: error("Couldn't open the chosen file for reading.")
}
