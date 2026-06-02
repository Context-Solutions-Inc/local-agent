package com.contextsolutions.mobileagent.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.contextsolutions.mobileagent.vision.ImagePreprocessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Android actual: the Photo Picker (`PickVisualMedia`, no storage permission).
 * The per-call `onPicked` callback is stashed in composition state and invoked
 * once the launcher delivers a content `Uri`; the bytes are read off the main
 * thread and run through [ImagePreprocessor.toModelJpeg] (decode + downscale +
 * JPEG re-encode) before reaching the ViewModel (invariant #39).
 */
@Composable
actual fun rememberImagePicker(): ImagePicker {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preprocessor = koinInject<ImagePreprocessor>()
    var pending by remember { mutableStateOf<((ByteArray?) -> Unit)?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val callback = pending
        pending = null
        if (uri == null) {
            callback?.invoke(null)
        } else {
            scope.launch {
                val jpeg = withContext(Dispatchers.IO) {
                    val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    raw?.let { preprocessor.toModelJpeg(it) }
                }
                callback?.invoke(jpeg)
            }
        }
    }

    return remember(launcher) {
        object : ImagePicker {
            override fun launch(onPicked: (ByteArray?) -> Unit) {
                pending = onPicked
                launcher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            }
        }
    }
}
