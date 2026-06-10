package com.contextsolutions.mobileagent.ui.settings

import androidx.compose.runtime.Composable

/**
 * Android no-op (PR #78): the GPU device pin is a desktop llama-server concern; Android runs
 * LiteRT-LM with no device selection, so there's no in-app GPU picker on mobile.
 */
@Composable
actual fun DesktopGpuSection() = Unit
