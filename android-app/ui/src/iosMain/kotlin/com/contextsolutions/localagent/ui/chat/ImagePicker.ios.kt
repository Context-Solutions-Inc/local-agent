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
import platform.UIKit.UIApplication
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePickerController
import platform.UIKit.UIImagePickerControllerDelegateProtocol
import platform.UIKit.UIImagePickerControllerOriginalImage
import platform.UIKit.UIImagePickerControllerSourceType
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.UIKit.UIViewController
import platform.darwin.NSObject
import platform.posix.memcpy

/**
 * iOS actual (PR #44): in-app **camera capture** via `UIImagePickerController`
 * (`sourceType = .camera`) — the user takes a new photo without leaving the app,
 * mirroring the Android camera flow (invariant #39). The captured `UIImage` is
 * re-encoded then decoded + downscaled to the model-ready JPEG via [ImagePreprocessor];
 * the bytes ride the current turn only. `onPicked(null)` on cancel or if no camera is
 * available (e.g. the Simulator).
 *
 * Requires the `NSCameraUsageDescription` Info.plist key (iOS crashes accessing the
 * camera without it); the OS shows the camera-permission prompt on first use.
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
    // holds its delegate weakly). Cleared when capture finishes/cancels.
    private var delegate: Delegate? = null

    override fun launch(onPicked: (ByteArray?) -> Unit) {
        val host = topmostViewController()
        if (host == null) {
            onPicked(null)
            return
        }
        val cameraType = UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera
        if (!UIImagePickerController.isSourceTypeAvailable(cameraType)) {
            onPicked(null) // no camera (e.g. Simulator) — nothing to capture
            return
        }
        val picker = UIImagePickerController()
        picker.sourceType = cameraType
        picker.allowsEditing = false
        val d = Delegate(onPicked)
        delegate = d
        picker.delegate = d
        host.presentViewController(picker, animated = true, completion = null)
    }

    private inner class Delegate(
        private val onPicked: (ByteArray?) -> Unit,
    ) : NSObject(), UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {

        override fun imagePickerController(
            picker: UIImagePickerController,
            didFinishPickingMediaWithInfo: Map<Any?, *>,
        ) {
            picker.dismissViewControllerAnimated(true, completion = null)
            delegate = null
            val image = didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage] as? UIImage
            val jpeg = image?.let { UIImageJPEGRepresentation(it, CAPTURE_QUALITY)?.toByteArray() }
            if (jpeg == null) {
                onPicked(null)
                return
            }
            scope.launch { onPicked(preprocessor.toModelJpeg(jpeg)) }
        }

        override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
            picker.dismissViewControllerAnimated(true, completion = null)
            delegate = null
            onPicked(null)
        }
    }

    private companion object {
        // Encode the camera UIImage near-lossless; the preprocessor does the real
        // downscale + JPEG@0.85 that reaches the model.
        private const val CAPTURE_QUALITY = 0.95
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
