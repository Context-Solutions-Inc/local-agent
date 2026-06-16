package com.contextsolutions.localagent.ui.util

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Decode JPEG/PNG bytes into a Compose [ImageBitmap] for display (the chat
 * input chip + a resumed conversation's photo bubble — invariant #39). Not a
 * `@Composable` so callers can run it off the main thread (`produceState` +
 * `withContext`). Returns null on any decode failure (the bubble then shows
 * text only). Android uses `BitmapFactory`; desktop uses Skia.
 */
expect fun decodeImageBitmap(bytes: ByteArray): ImageBitmap?
