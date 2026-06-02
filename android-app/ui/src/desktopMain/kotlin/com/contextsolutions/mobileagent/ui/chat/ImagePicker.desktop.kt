package com.contextsolutions.mobileagent.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.contextsolutions.mobileagent.vision.FilePicker
import com.contextsolutions.mobileagent.vision.ImagePreprocessor
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Desktop actual: a Swing chooser via [FilePicker], then decode + downscale to
 * the model-ready JPEG via [ImagePreprocessor] (invariant #39). Both run on a
 * coroutine off the composition thread; `onPicked(null)` on cancel or a
 * decode failure.
 */
@Composable
actual fun rememberImagePicker(): ImagePicker {
    val scope = rememberCoroutineScope()
    val filePicker = koinInject<FilePicker>()
    val preprocessor = koinInject<ImagePreprocessor>()
    return remember(filePicker, preprocessor) {
        object : ImagePicker {
            override fun launch(onPicked: (ByteArray?) -> Unit) {
                scope.launch {
                    val raw = filePicker.pickImage()
                    onPicked(raw?.let { preprocessor.toModelJpeg(it) })
                }
            }
        }
    }
}
