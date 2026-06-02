package com.contextsolutions.mobileagent.ui.chat

import androidx.compose.runtime.Composable

/**
 * Platform image picker for the chat surface (docs/DESKTOP_PORT_PLAN.md Phase 9,
 * invariant #39). Android registers a Photo Picker (`PickVisualMedia`) launcher
 * in composition (so the `expect` is a `@Composable`); desktop shows a Swing
 * chooser via [com.contextsolutions.mobileagent.vision.FilePicker]. Either way
 * the chosen file is decoded + downscaled to the model-ready JPEG through
 * [com.contextsolutions.mobileagent.vision.ImagePreprocessor] before [launch]
 * hands the bytes back; `onPicked(null)` means cancelled or undecodable.
 */
interface ImagePicker {
    /** Show the picker; deliver the model-ready JPEG bytes, or null if cancelled/failed. */
    fun launch(onPicked: (ByteArray?) -> Unit)
}

@Composable
expect fun rememberImagePicker(): ImagePicker
