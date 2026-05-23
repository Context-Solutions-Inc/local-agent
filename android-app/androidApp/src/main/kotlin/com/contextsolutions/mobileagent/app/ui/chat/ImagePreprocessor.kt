package com.contextsolutions.mobileagent.app.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Decodes a gallery [Uri] into a downscaled JPEG for the model plus a thumbnail
 * for the chat bubble (PR #48). Pure Android-side (needs `ContentResolver` +
 * `ImageDecoder`), so it lives in `:androidApp` rather than the KMP shared module.
 *
 * Why downscale: Gemma's vision tower works at a small fixed resolution
 * (~768 px). Sending a full 12 MP photo wastes prefill tokens and heap on an
 * 8 GB Pixel 7 (the same memory budget that ruled out E4B). We bound the
 * longest edge to [TARGET_LONGEST_EDGE_PX] and re-encode as JPEG before the
 * bytes ever reach `Content.ImageBytes`.
 *
 * `ImageDecoder` applies EXIF orientation automatically (unlike `BitmapFactory`),
 * so a portrait photo reaches the model upright with no manual rotation.
 */
object ImagePreprocessor {

    /** Longest-edge cap for the bitmap handed to the model. */
    const val TARGET_LONGEST_EDGE_PX = 768

    private const val JPEG_QUALITY = 85
    private const val TAG = "ImagePreprocessor"

    /** Result of preparing a picked image: model bytes + a UI thumbnail. */
    data class Prepared(val jpegBytes: ByteArray, val thumbnail: ImageBitmap)

    /**
     * Decode [uri], downscale to [TARGET_LONGEST_EDGE_PX], and return both the
     * JPEG bytes (for the model) and an [ImageBitmap] (for the bubble). Returns
     * null if the content can't be decoded as an image. Runs on [Dispatchers.IO].
     */
    suspend fun prepare(context: Context, uri: Uri): Prepared? = withContext(Dispatchers.IO) {
        try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                // SOFTWARE allocator so the bitmap is readable — a HARDWARE
                // bitmap (the default) can't be compressed or have its pixels
                // read back for ImageBitmap conversion.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val (w, h) = info.size.width to info.size.height
                val longest = maxOf(w, h)
                if (longest > TARGET_LONGEST_EDGE_PX) {
                    val scale = TARGET_LONGEST_EDGE_PX.toFloat() / longest
                    decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                }
            }
            val jpeg = ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
            Prepared(jpegBytes = jpeg, thumbnail = bitmap.asImageBitmap())
        } catch (t: Throwable) {
            Log.w(TAG, "failed to decode image uri=$uri: ${t.message}")
            null
        }
    }

    /**
     * Decode a persisted JPEG (already downscaled to [TARGET_LONGEST_EDGE_PX] by
     * [prepare]) back into an [ImageBitmap] for the bubble on conversation
     * resume (PR #49). `BitmapFactory` is enough here — the bytes are our own
     * re-encoded, EXIF-normalised JPEG, so there's no orientation to reapply.
     * Returns null on any decode failure (the bubble then shows text only).
     */
    fun decodeThumbnail(bytes: ByteArray): ImageBitmap? =
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
            .getOrNull()
}
