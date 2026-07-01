package com.contextsolutions.localagent.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.contextsolutions.localagent.vision.ImagePreprocessor
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.darwin.NSObject
import platform.posix.memcpy

/**
 * iOS actual (PR #44): a `PHPickerViewController`-backed gallery picker. PHPicker
 * runs out-of-process, so it needs **no** photo-library permission (no Info.plist
 * usage-description key). The chosen image is loaded as image data, then decoded +
 * downscaled to the model-ready JPEG via [ImagePreprocessor] (invariant #39); the
 * bytes ride the current turn only. `onPicked(null)` on cancel or a decode failure.
 *
 * Mirrors the desktop actual (`ImagePicker.desktop.kt`) — pick then preprocess — with
 * the platform picker + a `PHPickerViewControllerDelegate` presented from the Compose
 * host's top-most view controller.
 */
@Composable
actual fun rememberImagePicker(): ImagePicker {
    val scope = rememberCoroutineScope()
    val preprocessor = koinInject<ImagePreprocessor>()
    return remember(scope, preprocessor) { IosImagePicker(scope, preprocessor) }
}

@OptIn(ExperimentalForeignApi::class)
private class IosImagePicker(
    private val scope: CoroutineScope,
    private val preprocessor: ImagePreprocessor,
) : ImagePicker {

    // Strong ref keeps the delegate alive while the sheet is presented (the picker
    // holds its delegate weakly). Cleared when picking finishes/cancels.
    private var delegate: Delegate? = null

    override fun launch(onPicked: (ByteArray?) -> Unit) {
        val host = topmostViewController()
        if (host == null) {
            onPicked(null)
            return
        }
        val config = PHPickerConfiguration().apply {
            filter = PHPickerFilter.imagesFilter()
            selectionLimit = 1L
        }
        val picker = PHPickerViewController(configuration = config)
        val d = Delegate(onPicked)
        delegate = d
        picker.delegate = d
        host.presentViewController(picker, animated = true, completion = null)
    }

    private inner class Delegate(
        private val onPicked: (ByteArray?) -> Unit,
    ) : NSObject(), PHPickerViewControllerDelegateProtocol {

        override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
            picker.dismissViewControllerAnimated(true, completion = null)
            delegate = null
            val provider = (didFinishPicking.firstOrNull() as? PHPickerResult)?.itemProvider
            if (provider == null) {
                onPicked(null)
                return
            }
            // Completion runs on a background queue; hop to the remembered (Main) scope.
            provider.loadDataRepresentationForTypeIdentifier(PUBLIC_IMAGE) { data: NSData?, _: NSError? ->
                val bytes = data?.toByteArray()
                scope.launch {
                    onPicked(bytes?.let { preprocessor.toModelJpeg(it) })
                }
            }
        }
    }

    private companion object {
        private const val PUBLIC_IMAGE = "public.image"
    }
}

private fun topmostViewController(): UIViewController? {
    var vc = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (vc?.presentedViewController != null) {
        vc = vc.presentedViewController
    }
    return vc
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val len = length.toInt()
    if (len == 0) return ByteArray(0)
    val out = ByteArray(len)
    out.usePinned { pinned ->
        memcpy(pinned.addressOf(0), bytes, length)
    }
    return out
}
