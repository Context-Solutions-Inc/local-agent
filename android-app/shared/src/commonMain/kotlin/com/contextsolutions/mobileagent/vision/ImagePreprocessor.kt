package com.contextsolutions.mobileagent.vision

/**
 * Decodes + downscales a picked image into the JPEG bytes the vision model
 * consumes (docs/DESKTOP_PORT_PLAN.md, Phase 7, invariant #39) — the
 * cross-platform seam extracted from `:androidApp`'s `ImagePreprocessor`.
 *
 * Input is the raw bytes of a chosen image file (from [FilePicker]); output is a
 * re-encoded JPEG whose longest edge is bounded to [TARGET_LONGEST_EDGE_PX]
 * (Gemma's vision tower works at ~768 px; sending a full photo wastes prefill
 * tokens + memory). Taking/returning `ByteArray` keeps the interface free of
 * platform image types so it sits in `commonMain`: the Android actual (Phase 9)
 * uses `BitmapFactory`/`ImageDecoder`, the desktop actual `javax.imageio.ImageIO`.
 *
 * The result feeds `HistoryMessage.imageBytes` on the current turn only (#39).
 */
interface ImagePreprocessor {

    /**
     * Decode [imageBytes], downscale the longest edge to [TARGET_LONGEST_EDGE_PX]
     * (no upscaling), and re-encode as JPEG. Returns null if the bytes can't be
     * decoded as an image.
     */
    suspend fun toModelJpeg(imageBytes: ByteArray): ByteArray?

    companion object {
        /** Longest-edge cap for the image handed to the model (PRD / invariant #39). */
        const val TARGET_LONGEST_EDGE_PX = 768
    }
}
