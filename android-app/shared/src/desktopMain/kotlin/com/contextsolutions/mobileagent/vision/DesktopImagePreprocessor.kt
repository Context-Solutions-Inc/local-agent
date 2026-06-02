package com.contextsolutions.mobileagent.vision

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.max
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Desktop [ImagePreprocessor] (docs/DESKTOP_PORT_PLAN.md, Phase 7) — decode +
 * downscale via `javax.imageio.ImageIO` + `BufferedImage`, the desktop
 * counterpart of Android's `ImageDecoder`-based one (invariant #39).
 *
 * Bilinear-scales the longest edge to [ImagePreprocessor.TARGET_LONGEST_EDGE_PX]
 * (no upscaling), flattens onto an opaque RGB canvas (JPEG has no alpha), and
 * re-encodes as JPEG at [jpegQuality]. Runs on [ioDispatcher].
 *
 * Note: unlike Android's `ImageDecoder`, `ImageIO.read` does NOT auto-apply EXIF
 * orientation — desktop exports/screenshots are generally already upright, so
 * orientation handling is deferred (a metadata pass can be added if needed).
 */
class DesktopImagePreprocessor(
    private val targetLongestEdgePx: Int = ImagePreprocessor.TARGET_LONGEST_EDGE_PX,
    private val jpegQuality: Float = 0.85f,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val logger: (String) -> Unit = { System.err.println("[ImagePreprocessor] $it") },
) : ImagePreprocessor {

    override suspend fun toModelJpeg(imageBytes: ByteArray): ByteArray? = withContext(ioDispatcher) {
        try {
            val source = ImageIO.read(ByteArrayInputStream(imageBytes))
                ?: run {
                    logger("ImageIO.read returned null (unsupported/corrupt format), ${imageBytes.size} bytes in")
                    return@withContext null
                }
            val rgb = scaleToRgb(source)
            val jpeg = encodeJpeg(rgb)
            logger(
                "processed image: in=${imageBytes.size}B ${source.width}x${source.height} → " +
                    "out=${jpeg.size}B ${rgb.width}x${rgb.height} (longestEdge≤$targetLongestEdgePx)",
            )
            jpeg
        } catch (t: Throwable) {
            logger("failed to process image: ${t.message}")
            null
        }
    }

    /** Downscale (if needed) onto an opaque TYPE_INT_RGB canvas. */
    private fun scaleToRgb(source: BufferedImage): BufferedImage {
        val longest = max(source.width, source.height)
        val scale = if (longest > targetLongestEdgePx) targetLongestEdgePx.toFloat() / longest else 1f
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        val dst = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = dst.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(source, 0, 0, width, height, null)
        } finally {
            g.dispose()
        }
        return dst
    }

    private fun encodeJpeg(image: BufferedImage): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val param = writer.defaultWriteParam.apply {
            if (canWriteCompressed()) {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = jpegQuality
            }
        }
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            writer.write(null, IIOImage(image, null, null), param)
        }
        writer.dispose()
        return out.toByteArray()
    }
}
