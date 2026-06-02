package com.contextsolutions.mobileagent.vision

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Android [ImagePreprocessor] (docs/DESKTOP_PORT_PLAN.md Phase 9, invariant #39)
 * — decode + downscale via `ImageDecoder`, the counterpart of the desktop
 * `ImageIO`-based one. Adapted from `:androidApp`'s former
 * `app.ui.chat.ImagePreprocessor.prepare`, now operating on raw bytes (from the
 * Photo Picker) so it sits behind the cross-platform [ImagePreprocessor] seam.
 *
 * Bounds the longest edge to [ImagePreprocessor.TARGET_LONGEST_EDGE_PX] (~768 px;
 * Gemma's vision tower works small, and a full photo wastes prefill tokens +
 * heap on an 8 GB Pixel 7) and re-encodes as JPEG. `ImageDecoder` applies EXIF
 * orientation automatically (unlike `BitmapFactory`), so a portrait photo
 * reaches the model upright.
 */
class AndroidImagePreprocessor : ImagePreprocessor {

    override suspend fun toModelJpeg(imageBytes: ByteArray): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val source = ImageDecoder.createSource(ByteBuffer.wrap(imageBytes))
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                // SOFTWARE allocator so the bitmap is readable for compress() — a
                // HARDWARE bitmap (the default) can't be re-encoded.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                val longest = maxOf(w, h)
                if (longest > ImagePreprocessor.TARGET_LONGEST_EDGE_PX) {
                    val scale = ImagePreprocessor.TARGET_LONGEST_EDGE_PX.toFloat() / longest
                    decoder.setTargetSize(
                        (w * scale).toInt().coerceAtLeast(1),
                        (h * scale).toInt().coerceAtLeast(1),
                    )
                }
            }
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
        } catch (t: Throwable) {
            null
        }
    }

    private companion object {
        private const val JPEG_QUALITY = 85
    }
}
