package com.example.engine

import android.graphics.Bitmap
import android.graphics.Color
import com.example.model.RoiRect

data class BlurredPatchResult(
    val blurredBitmap: Bitmap?,
    val frostedTintColor: Int
)

/**
 * Generates an expanded, smooth blurred background patch and samples the surrounding game
 * background color around an OCR bounding box for seamless frosted-glass In-Place masking.
 * On Android 12+ (API 31+), [InPlaceRenderView] layers a GPU RenderEffect Gaussian blur on top
 * of this patch; on lower API levels, the two-pass downscale + separable box blur keeps edges silky.
 */
object BlurMaskHelper {

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
            val softened = fastBoxBlur(tiny, radius = 2)
            if (softened !== tiny) tiny.recycle()
            val outW = (boxW / 2).coerceIn(16, 360)
            val outH = (boxH / 2).coerceIn(12, 180)
            val upscaled = Bitmap.createScaledBitmap(softened, outW, outH, true)
            softened.recycle()
            upscaled
        }.getOrNull()

        return BlurredPatchResult(blurredBitmap = patch, frostedTintColor = tintColor)
    }

    private fun fastBoxBlur(src: Bitmap, radius: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w < 3 || h < 3 || radius <= 0) return src
        val pixels = IntArray(w * h)
        val temp = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        // Horizontal pass
        val div = radius * 2 + 1
        for (y in 0 until h) {
            val rowOffset = y * w
            for (x in 0 until w) {
                var r = 0
                var g = 0
                var b = 0
                for (dx in -radius..radius) {
                    val sx = (x + dx).coerceIn(0, w - 1)
                    val px = pixels[rowOffset + sx]
                    r += Color.red(px)
                    g += Color.green(px)
                    b += Color.blue(px)
                }
                temp[rowOffset + x] = Color.rgb(r / div, g / div, b / div)
            }
        }

        // Vertical pass
        for (x in 0 until w) {
            for (y in 0 until h) {
                var r = 0
                var g = 0
                var b = 0
                for (dy in -radius..radius) {
                    val sy = (y + dy).coerceIn(0, h - 1)
                    val px = temp[sy * w + x]
                    r += Color.red(px)
                    g += Color.green(px)
                    b += Color.blue(px)
                }
                pixels[y * w + x] = Color.rgb(r / div, g / div, b / div)
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
