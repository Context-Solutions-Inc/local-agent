package com.contextsolutions.localagent.ui.job

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Android actual: a no-op. Choosing a job program only makes sense on the desktop
 * (where jobs run); the Browse button is gated on `isDesktopPlatform` and never
 * shown on mobile, so this exists only to satisfy the `expect`.
 */
@Composable
actual fun rememberJobProgramPicker(): JobProgramPicker = remember {
    object : JobProgramPicker {
        override fun launch(onPicked: (PickedJobProgram?) -> Unit) = onPicked(null)
    }
}
