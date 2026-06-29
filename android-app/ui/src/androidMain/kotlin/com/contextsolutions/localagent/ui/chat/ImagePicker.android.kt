package com.contextsolutions.localagent.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.contextsolutions.localagent.vision.ImagePreprocessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.io.File

/**
 * Android actual: capture a photo with the device **camera** (PR #38 — replaces the
 * Photo Picker gallery flow). `ActivityResultContracts.TakePicture` writes the
 * full-resolution JPEG to a caller-supplied content `Uri` (a temp file in `cacheDir`
 * exposed via a `FileProvider`), returning success/failure. CAMERA is requested at
 * runtime (mirrors [rememberMicPermission]); the permission is already declared in
 * the manifest. On success the bytes are read off the main thread and run through
 * [ImagePreprocessor.toModelJpeg] (decode + downscale + JPEG re-encode, applying EXIF
 * orientation) before reaching the ViewModel (invariant #39). Desktop is unchanged.
 */
@Composable
actual fun rememberImagePicker(): ImagePicker {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preprocessor = koinInject<ImagePreprocessor>()
    var pending by remember { mutableStateOf<((ByteArray?) -> Unit)?>(null) }
    var captureFile by remember { mutableStateOf<File?>(null) }

    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val callback = pending
        pending = null
        val file = captureFile
        captureFile = null
        if (success && file != null) {
            scope.launch {
                val jpeg = withContext(Dispatchers.IO) {
                    runCatching { preprocessor.toModelJpeg(file.readBytes()) }.getOrNull()
                        .also { file.delete() }
                }
                callback?.invoke(jpeg)
            }
        } else {
            file?.delete()
            callback?.invoke(null)
        }
    }

    // Create a fresh temp file + content Uri and launch the camera.
    val startCapture: () -> Unit = startCapture@{
        val file = runCatching { File.createTempFile("camera_", ".jpg", context.cacheDir) }.getOrNull()
        if (file == null) {
            val cb = pending; pending = null; cb?.invoke(null)
            return@startCapture
        }
        captureFile = file
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        takePicture.launch(uri)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startCapture()
        } else {
            val cb = pending; pending = null; cb?.invoke(null)
        }
    }

    return remember(takePicture, permissionLauncher) {
        object : ImagePicker {
            override fun launch(onPicked: (ByteArray?) -> Unit) {
                pending = onPicked
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
                if (granted) startCapture() else permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }
}
