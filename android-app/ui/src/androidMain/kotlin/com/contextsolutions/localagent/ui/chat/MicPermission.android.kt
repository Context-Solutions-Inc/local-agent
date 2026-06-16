package com.contextsolutions.localagent.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Android actual: `RECORD_AUDIO` via a `RequestPermission` launcher, plus a
 * `SpeechRecognizer.isRecognitionAvailable` availability check. [granted] is
 * composition state so a grant re-enables the mic without a screen reload.
 */
@Composable
actual fun rememberMicPermission(): MicPermission {
    val context = LocalContext.current
    val available = remember { SpeechRecognizer.isRecognitionAvailable(context) }
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var pending by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { result ->
        granted = result
        val cb = pending
        pending = null
        cb?.invoke(result)
    }
    return remember(available, granted, launcher) {
        object : MicPermission {
            override val available: Boolean = available
            override val granted: Boolean = granted
            override fun request(onResult: (Boolean) -> Unit) {
                if (granted) {
                    onResult(true)
                } else {
                    pending = onResult
                    launcher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }
}
