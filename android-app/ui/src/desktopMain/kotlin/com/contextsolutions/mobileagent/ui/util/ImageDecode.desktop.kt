package com.contextsolutions.mobileagent.ui.util

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

/**
 * Desktop actual: decode via Skia ([Image.makeFromEncoded]) and convert to a
 * Compose [ImageBitmap]. Returns null on any decode failure.
 */
actual fun decodeImageBitmap(bytes: ByteArray): ImageBitmap? =
    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }
        .getOrNull()
