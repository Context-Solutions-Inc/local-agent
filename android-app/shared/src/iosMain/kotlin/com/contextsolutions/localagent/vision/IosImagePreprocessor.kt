package com.contextsolutions.localagent.vision

import com.contextsolutions.localagent.platform.platformIoDispatcher
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.create
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.posix.memcpy

/**
 * iOS [ImagePreprocessor] (invariant #39) — the counterpart of the Android
 * `ImageDecoder`-based / desktop `ImageIO`-based ones. Decodes via `UIImage`
 * (which bakes in EXIF orientation), downscales the longest edge to
 * [ImagePreprocessor.TARGET_LONGEST_EDGE_PX] (~768 px; Gemma's vision tower works
 * small and a full photo wastes prefill tokens), and re-encodes as JPEG.
 *
 * Renders at scale 1.0 so the output pixel dimensions equal the point dimensions
 * (~768 px), not device-multiplied (2×/3×). Returns null if the bytes can't be
 * decoded. The result feeds `HistoryMessage.imageBytes` on the current turn only.
 */
@OptIn(ExperimentalForeignApi::class)
class IosImagePreprocessor : ImagePreprocessor {

    override suspend fun toModelJpeg(imageBytes: ByteArray): ByteArray? =
        withContext(platformIoDispatcher) {
            runCatching {
                // UIImage(data:) is a non-null constructor in the K/N binding; invalid
                // bytes throw (caught by runCatching) rather than returning null.
                val image = UIImage(data = imageBytes.toNSData())
                val (srcW, srcH) = image.size.useContents { width to height }
                if (srcW <= 0.0 || srcH <= 0.0) return@runCatching null

                val longest = maxOf(srcW, srcH)
                val cap = ImagePreprocessor.TARGET_LONGEST_EDGE_PX.toDouble()
                val factor = if (longest > cap) cap / longest else 1.0 // shrink only
                val targetW = srcW * factor
                val targetH = srcH * factor

                val format = UIGraphicsImageRendererFormat.defaultFormat().apply {
                    setOpaque(true) // JPEG has no alpha
                    setScale(1.0) // output px == points, ~768 not 768×deviceScale
                }
                val renderer = UIGraphicsImageRenderer(
                    size = CGSizeMake(targetW, targetH),
                    format = format,
                )
                val scaled = renderer.imageWithActions {
                    image.drawInRect(CGRectMake(0.0, 0.0, targetW, targetH))
                }
                UIImageJPEGRepresentation(scaled, JPEG_QUALITY)?.toByteArray()
            }.getOrNull()
        }

    private companion object {
        private const val JPEG_QUALITY = 0.85
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) {
        NSData()
    } else {
        usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
        }
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
