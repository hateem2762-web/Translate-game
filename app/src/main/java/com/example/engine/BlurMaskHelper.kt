package com.example.engine

import android.graphics.Bitmap
import android.graphics.Color
import com.example.model.BlurBackdropStyle
import com.example.model.RoiRect
import kotlin.math.roundToInt

data class BlurredPatchResult(
    val blurredBitmap: Bitmap?,
    val frostedTintColor: Int
)

/**
 * Generates background blur patches around an OCR bounding box:
 * - [BlurBackdropStyle.DARK_FROSTED]: preserved semi-transparent dark obsidian frosted patch.
 * - [BlurBackdropStyle.REAL_BLUR]: crops the exact game region and applies a strong 3-pass
 *   Gaussian-approximation blur scaled by [blurIntensity] so original text blends away naturally.
 */
object BlurMaskHelper {

    fun createPatch(
        sourceBitmap: Bitmap,
        box: RoiRect,
        opacity: Float,
        blurStyle: BlurBackdropStyle = BlurBackdropStyle.DARK_FROSTED,
        blurIntensity: Float = 0.70f
    ): BlurredPatchResult {
        return when (blurStyle) {
            BlurBackdropStyle.DARK_FROSTED -> createFrostedPatch(sourceBitmap, box, opacity)
            BlurBackdropStyle.REAL_BLUR -> createRealBlurPatch(sourceBitmap, box, blurIntensity)
        }
    }

    fun createRealBlurPatch(
        sourceBitmap: Bitmap,
        box: RoiRect,
        blurIntensity: Float
    ): BlurredPatchResult {
        val w = sourceBitmap.width
        val h = sourceBitmap.height
        if (w <= 0 || h <= 0) {
            return BlurredPatchResult(null, Color.argb(120, 12, 16, 24))
        }

        val clampedIntensity = blurIntensity.coerceIn(0.20f, 1.0f)
        val marginX = (box.width * 0.10f).toInt().coerceIn(8, 28)
        val marginY = (box.height * 0.16f).toInt().coerceIn(6, 22)

        val left = (box.x - marginX).coerceIn(0, w - 1)
        val top = (box.y - marginY).coerceIn(0, h - 1)
        val right = (box.x + box.width + marginX).coerceIn(left + 1, w)
        val bottom = (box.y + box.height + marginY).coerceIn(top + 1, h)
        val boxW = (right - left).coerceAtLeast(1)
        val boxH = (bottom - top).coerceAtLeast(1)

        val avgColor = sampleBorderColor(sourceBitmap, left, top, boxW, boxH)
        val patch = runCatching {
            val crop = Bitmap.createBitmap(sourceBitmap, left, top, boxW, boxH)
            // Keep smooth half-resolution buffer so Gaussian blur stays silky rather than blocky
            val workW = (boxW / 2).coerceIn(24, 400)
            val workH = (boxH / 2).coerceIn(16, 200)
            val work = if (crop.width != workW || crop.height != workH) {
                Bitmap.createScaledBitmap(crop, workW, workH, true).also { crop.recycle() }
            } else {
                crop
            }
            val radius = (3f + clampedIntensity * 16f).roundToInt().coerceIn(3, 20)
            val blurred = gaussianApproxBlur(work, radius = radius, passes = 3)
            if (blurred !== work) work.recycle()
            blurred
        }.getOrNull()

        return BlurredPatchResult(blurredBitmap = patch, frostedTintColor = avgColor)
    }

    fun createFrostedPatch(sourceBitmap: Bitmap, box: RoiRect, opacity: Float): BlurredPatchResult {
        val w = sourceBitmap.width
        val h = sourceBitmap.height
        if (w <= 0 || h <= 0) {
            return BlurredPatchResult(null, defaultTint(opacity))
        }

        // Expand sampling region slightly beyond tight OCR bounds for natural feathered continuity
        val marginX = (box.width * 0.10f).toInt().coerceIn(8, 28)
        val marginY = (box.height * 0.18f).toInt().coerceIn(6, 20)

        val left = (box.x - marginX).coerceIn(0, w - 1)
        val top = (box.y - marginY).coerceIn(0, h - 1)
        val right = (box.x + box.width + marginX).coerceIn(left + 1, w)
        val bottom = (box.y + box.height + marginY).coerceIn(top + 1, h)
        val boxW = (right - left).coerceAtLeast(1)
        val boxH = (bottom - top).coerceAtLeast(1)

        val avgColor = sampleBorderColor(sourceBitmap, left, top, boxW, boxH)
        // Blend sampled game background color with deep obsidian for high-contrast subtitle legibility
        val blendedR = (Color.red(avgColor) * 0.30f + 8f * 0.70f).toInt().coerceIn(0, 255)
        val blendedG = (Color.green(avgColor) * 0.30f + 13f * 0.70f).toInt().coerceIn(0, 255)
        val blendedB = (Color.blue(avgColor) * 0.30f + 22f * 0.70f).toInt().coerceIn(0, 255)
        val alpha = (opacity.coerceIn(0.68f, 0.96f) * 255).toInt()
        val tintColor = Color.argb(alpha, blendedR, blendedG, blendedB)

        val patch = runCatching {
            val crop = Bitmap.createBitmap(sourceBitmap, left, top, boxW, boxH)
            // Keep moderate resolution for smooth blur; pass 1 downscales to ~1/8, pass 2 blurs pixels
            val downW = (boxW / 8).coerceIn(6, 72)
            val downH = (boxH / 8).coerceIn(4, 36)
            val tiny = Bitmap.createScaledBitmap(crop, downW, downH, true)
            crop.recycle()
            val softened = gaussianApproxBlur(tiny, radius = 2, passes = 1)
            if (softened !== tiny) tiny.recycle()
            val outW = (boxW / 2).coerceIn(16, 360)
            val outH = (boxH / 2).coerceIn(12, 180)
            val upscaled = Bitmap.createScaledBitmap(softened, outW, outH, true)
            softened.recycle()
            upscaled
        }.getOrNull()

        return BlurredPatchResult(blurredBitmap = patch, frostedTintColor = tintColor)
    }

    /**
     * O(w * h) per-pass separable sliding-window blur.
     * Running 3 passes converges cleanly to a true Gaussian blur with zero pixel blockiness.
     */
    private fun gaussianApproxBlur(src: Bitmap, radius: Int, passes: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w < 3 || h < 3 || radius <= 0) return src
        val pixels = IntArray(w * h)
        val temp = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val effectiveRadius = radius.coerceAtMost(maxOf(w, h))
        val div = effectiveRadius * 2 + 1

        repeat(passes.coerceIn(1, 4)) {
            // Horizontal sliding-window pass
            for (y in 0 until h) {
                val rowOffset = y * w
                var rSum = 0
                var gSum = 0
                var bSum = 0
                for (dx in -effectiveRadius..effectiveRadius) {
                    val sx = dx.coerceIn(0, w - 1)
                    val px = pixels[rowOffset + sx]
                    rSum += (px ushr 16) and 0xFF
                    gSum += (px ushr 8) and 0xFF
                    bSum += px and 0xFF
                }
                for (x in 0 until w) {
                    temp[rowOffset + x] = (0xFF shl 24) or
                        ((rSum / div) shl 16) or
                        ((gSum / div) shl 8) or
                        (bSum / div)

                    val outIdx = (x - effectiveRadius).coerceIn(0, w - 1)
                    val inIdx = (x + effectiveRadius + 1).coerceIn(0, w - 1)
                    val pOut = pixels[rowOffset + outIdx]
                    val pIn = pixels[rowOffset + inIdx]
                    rSum += ((pIn ushr 16) and 0xFF) - ((pOut ushr 16) and 0xFF)
                    gSum += ((pIn ushr 8) and 0xFF) - ((pOut ushr 8) and 0xFF)
                    bSum += (pIn and 0xFF) - (pOut and 0xFF)
                }
            }

            // Vertical sliding-window pass
            for (x in 0 until w) {
                var rSum = 0
                var gSum = 0
                var bSum = 0
                for (dy in -effectiveRadius..effectiveRadius) {
                    val sy = dy.coerceIn(0, h - 1)
                    val px = temp[sy * w + x]
                    rSum += (px ushr 16) and 0xFF
                    gSum += (px ushr 8) and 0xFF
                    bSum += px and 0xFF
                }
                for (y in 0 until h) {
                    pixels[y * w + x] = (0xFF shl 24) or
                        ((rSum / div) shl 16) or
                        ((gSum / div) shl 8) or
                        (bSum / div)

                    val outIdx = (y - effectiveRadius).coerceIn(0, h - 1)
                    val inIdx = (y + effectiveRadius + 1).coerceIn(0, h - 1)
                    val pOut = temp[outIdx * w + x]
                    val pIn = temp[inIdx * w + x]
                    rSum += ((pIn ushr 16) and 0xFF) - ((pOut ushr 16) and 0xFF)
                    gSum += ((pIn ushr 8) and 0xFF) - ((pOut ushr 8) and 0xFF)
                    bSum += (pIn and 0xFF) - (pOut and 0xFF)
                }
            }
        }

        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun sampleBorderColor(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        boxW: Int,
        boxH: Int
    ): Int {
        val right = (left + boxW - 1).coerceAtMost(bitmap.width - 1)
        val bottom = (top + boxH - 1).coerceAtMost(bitmap.height - 1)

        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var count = 0

        val stepX = (boxW / 6).coerceAtLeast(1)
        for (x in left..right step stepX) {
            val topPx = bitmap.getPixel(x, top)
            val botPx = bitmap.getPixel(x, bottom)
            rSum += Color.red(topPx) + Color.red(botPx)
            gSum += Color.green(topPx) + Color.green(botPx)
            bSum += Color.blue(topPx) + Color.blue(botPx)
            count += 2
        }

        if (count == 0) return Color.rgb(8, 13, 22)
        return Color.rgb((rSum / count).toInt(), (gSum / count).toInt(), (bSum / count).toInt())
    }

    private fun defaultTint(opacity: Float): Int {
        val alpha = (opacity.coerceIn(0.68f, 0.96f) * 255).toInt()
        return Color.argb(alpha, 8, 13, 22)
    }
}
