package com.contextsolutions.mobileagent.ui.util

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Android actual: `BitmapFactory` is enough — the bytes are our own
 * already-downscaled, EXIF-normalised JPEG (no orientation to reapply).
 */
actual fun decodeImageBitmap(bytes: ByteArray): ImageBitmap? =
    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
        .getOrNull()
